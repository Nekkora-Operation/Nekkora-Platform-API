package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.core.testsupport.BrowserClient;

import tools.jackson.databind.JsonNode;

/**
 * PLATFORM-FLUXO-002 - acompanhamento unificado pela API real: as quatro origens do resumo do Inicio, uma linha por execucao
 * (o pedido que virou execucao nao repete; a ligacao Agent-Platform, ainda proibida pelo banco, tambem nao), "Em andamento" sem periodo com as
 * pendencias de resultado desconhecido (nunca escondidas por periodo), "Historico" no periodo, filtro por maquina, a atividade
 * por maquina, cursor e a visibilidade por ambiente. A massa e declarada como superusuario; as leituras usam o papel real.
 */
class ActivityIT extends ReadRoutesSupport {

    private static final String FP = "'" + "ab".repeat(32) + "'";

    private static void mass(String... sqls) {
        List<String> all = new ArrayList<>();
        all.add("SET session_replication_role = replica");
        all.addAll(List.of(sqls));
        DB.asSuperuser(all.toArray(String[]::new));
    }

    private static String one(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static String execution(UUID id, UUID org, UUID ws, UUID a, UUID v, UUID m, int status, String at) {
        return "INSERT INTO executions (execution_id, organization_id, workspace_id, automation_id, automation_version_id, request_key, request_fingerprint, "
                + "requested_by_membership_id, command_snapshot, status, status_reason, origin, created_at, finished_at) SELECT '" + id + "', '" + org + "', '"
                + ws + "', '" + a + "', v.automation_version_id, 'k-' || gen_random_uuid(), repeat('b', 64), '" + m + "', v.command_json, " + status + ", "
                + (status >= 4 ? "1" : "NULL") + ", 1, TIMESTAMPTZ '" + at + "', " + (status >= 4 ? "TIMESTAMPTZ '" + at + "' + interval '5 seconds'" : "NULL")
                + " FROM automation_versions v WHERE v.automation_version_id = '" + v + "'";
    }

    private static String run(UUID id, UUID org, UUID ws, UUID a, UUID v, UUID worker, int state, String at, UUID platformExecution) {
        return "INSERT INTO agent_runs (agent_run_id, organization_id, workspace_id, automation_id, automation_version_id, authorization_id, agent_id, "
                + "worker_id, worker_agent_assignment_id, agent_execution_id, package_fingerprint, authorization_state_at_registration, supervisor_state, "
                + "registered_at, platform_execution_id) VALUES ('" + id + "', '" + org + "', '" + ws + "', '" + a + "', '" + v + "', gen_random_uuid(), "
                + "gen_random_uuid(), '" + worker + "', gen_random_uuid(), 'ex-' || replace(gen_random_uuid()::text, '-', ''), " + FP + ", 1, " + state
                + ", TIMESTAMPTZ '" + at + "', " + (platformExecution == null ? "NULL" : "'" + platformExecution + "'") + ")";
    }

    private static String remote(UUID id, UUID org, UUID ws, UUID a, UUID v, UUID m, String at) {
        return "INSERT INTO remote_executions (remote_execution_id, organization_id, workspace_id, automation_id, automation_version_id, package_fingerprint, "
                + "package_fingerprint_rule, requirements_revision, effective_dependencies, request_key, requested_by_membership_id, requested_at) VALUES ('"
                + id + "', '" + org + "', '" + ws + "', '" + a + "', '" + v + "', " + FP + ", 'nekkora-agent/external-python-fingerprint/1', 1, '[]'::jsonb, "
                + "gen_random_uuid(), '" + m + "', TIMESTAMPTZ '" + at + "')";
    }

    private static String attempt(UUID id, UUID org, UUID execution, UUID worker, UUID agent, UUID m, int state, String at) {
        boolean ended = state >= 7;
        return "INSERT INTO remote_attempts (attempt_id, organization_id, remote_execution_id, attempt_number, agent_id, worker_id, authorization_id, "
                + "created_by_membership_id, created_at, offer_valid_until, state, token_hash, ended_at) VALUES ('" + id + "', '" + org + "', '" + execution
                + "', 1, '" + agent + "', '" + worker + "', gen_random_uuid(), '" + m + "', TIMESTAMPTZ '" + at + "', TIMESTAMPTZ '" + at + "' + interval '15 minutes', "
                + state + ", repeat('c', 64), " + (ended ? "TIMESTAMPTZ '" + at + "' + interval '1 minute'" : "NULL") + ")";
    }

    private static List<String> kinds(JsonNode items) {
        List<String> out = new ArrayList<>();
        items.forEach(x -> out.add(x.get("kind").asString() + ":" + x.get("outcome").asString() + (x.get("attention").asBoolean() ? ":atencao" : "")));
        return out;
    }

    @Test
    void umaLinhaPorExecucaoPendenciasSempreVisiveisFiltroPorMaquinaEAtividade() throws Exception {
        Org o = newOrganization();
        ProductFixtures.unlimited(o.id(), o.adminId());
        UUID m = o.adminMembership();
        UUID ws = AccessFixtures.namedWorkspace(o.id(), "Financeiro", 1);
        UUID hidden = AccessFixtures.namedWorkspace(o.id(), "Restrito", 1);
        UUID a = ProductFixtures.automation(o.id(), ws, null, "Conciliacao", m);
        UUID v = ProductFixtures.version(o.id(), ws, a, true, m);
        UUID ah = ProductFixtures.automation(o.id(), hidden, null, "Oculta", m);
        UUID vh = ProductFixtures.version(o.id(), hidden, ah, true, m);
        UUID w1 = UUID.fromString(one("INSERT INTO workers (organization_id, name, status) VALUES ('" + o.id() + "', 'Estacao fiscal', 1) RETURNING worker_id"));
        UUID w2 = UUID.fromString(one("INSERT INTO workers (organization_id, name, status) VALUES ('" + o.id() + "', 'Estacao dois', 1) RETURNING worker_id"));
        UUID platformRunning = UUID.randomUUID(), platformDone = UUID.randomUUID(), linked = UUID.randomUUID();
        UUID oldUnknown = UUID.randomUUID(), reconciledUnknown = UUID.randomUUID(), runDone = UUID.randomUUID(), noSignal = UUID.randomUUID();
        UUID remoteUnknown = UUID.randomUUID(), remoteSolved = UUID.randomUUID(), attUnknown = UUID.randomUUID(), attSolved = UUID.randomUUID();
        mass(
                // Platform: uma em andamento muito antiga (nenhum periodo a esconde) e uma concluida; outra concluida que tem execucao do Agent ligada
                execution(platformRunning, o.id(), ws, a, v, m, 2, "2025-01-10T08:00:00Z"),
                execution(platformDone, o.id(), ws, a, v, m, 4, "2026-03-05T08:00:00Z"),
                execution(linked, o.id(), ws, a, v, m, 4, "2026-03-06T08:00:00Z"),
                // Agent anterior: desfecho desconhecido ANTIGO e nao reconciliado (pendencia), um reconciliado, um sucesso, e um ligado a Platform
                run(oldUnknown, o.id(), ws, a, v, w1, 9, "2025-02-01T08:00:00Z", null),
                run(reconciledUnknown, o.id(), ws, a, v, w1, 9, "2026-03-07T08:00:00Z", null),
                "INSERT INTO agent_run_reconciliations (organization_id, agent_run_id, state_at, last_report_at_seen, operator_user_id, justification, verification) VALUES ('" + o.id() + "', '"
                        + reconciledUnknown + "', 9, TIMESTAMPTZ '2026-03-07T08:00:00Z', '" + o.adminId() + "', 'reconciliacao na massa do teste', 'conferido na massa do teste de acompanhamento')",
                run(runDone, o.id(), ws, a, v, w1, 4, "2026-03-08T08:00:00Z", null),
                // Python externo monitorado: codigo 0 SEM sinal de sucesso (AGT-EXT-001) nunca vira "concluida"
                run(noSignal, o.id(), ws, a, v, w1, 4, "2026-03-08T09:00:00Z", null),
                "UPDATE agent_runs SET supervisor_code = 'AGT-EXT-001', exit_code = 0 WHERE agent_run_id = '" + noSignal + "'",
                // remota: ultima tentativa ENDED_UNKNOWN sem confirmacao (pendencia) e outra ja confirmada pela maquina
                remote(remoteUnknown, o.id(), ws, a, v, m, "2026-03-09T08:00:00Z"),
                attempt(attUnknown, o.id(), remoteUnknown, w2, UUID.randomUUID(), m, 13, "2026-03-09T08:00:00Z"),
                remote(remoteSolved, o.id(), ws, a, v, m, "2026-03-10T08:00:00Z"),
                attempt(attSolved, o.id(), remoteSolved, w2, UUID.randomUUID(), m, 13, "2026-03-10T08:00:00Z"),
                "INSERT INTO remote_attempt_process_reports (attempt_id, kind, organization_id, agent_id) SELECT attempt_id, 1, organization_id, agent_id "
                        + "FROM remote_attempts WHERE attempt_id = '" + attSolved + "'",
                // ambiente sem acesso para a pessoa limitada
                execution(UUID.randomUUID(), o.id(), hidden, ah, vh, m, 2, "2026-03-11T08:00:00Z"));
        BrowserClient admin = admin(o);

        // ---- Em andamento: SEM periodo; em andamento + pendencias (a antiga tambem); nunca as resolvidas
        Got inProgress = get(admin, path(o.id(), "activity", "tab=in_progress"));
        assertOk(inProgress, "em andamento");
        assertThat(kinds(inProgress.body().get("items"))).containsExactlyInAnyOrder("platform:in_progress", "platform:in_progress",
                "local_agent:unconfirmed:atencao", "remote:unconfirmed:atencao");
        // ---- Historico no periodo: o que tem desfecho; uma linha por execucao (a ligada e o pedido que virou execucao nao repetem)
        Got history = get(admin, path(o.id(), "activity", "tab=history&from=2026-03-01T00:00:00Z&to=2026-04-01T00:00:00Z"));
        assertOk(history, "historico");
        assertThat(kinds(history.body().get("items"))).containsExactly("remote:unconfirmed", "remote:unconfirmed:atencao", "local_agent:no_error_signaled",
                "local_agent:succeeded", "local_agent:unconfirmed", "platform:succeeded", "platform:succeeded");
        // o periodo nao esconde a pendencia antiga: ela continua em "Em andamento"
        assertThat(field(inProgress.body().get("items"), "id")).contains(oldUnknown.toString());

        // ---- a maquina de cada linha e o filtro por maquina
        JsonNode first = history.body().get("items").get(3);
        assertThat(first.get("machine").get("workerId").asString()).isEqualTo(w1.toString());
        assertThat(first.get("machine").get("name").asString()).isEqualTo("Estacao fiscal");
        Got byMachine = get(admin, path(o.id(), "activity", "tab=history&workerId=" + w2));
        assertThat(kinds(byMachine.body().get("items"))).containsExactly("remote:unconfirmed", "remote:unconfirmed:atencao");
        Got pendingOfMachine = get(admin, path(o.id(), "activity", "tab=in_progress&workerId=" + w1));
        assertThat(field(pendingOfMachine.body().get("items"), "id")).containsExactly(oldUnknown.toString());

        // ---- atividade por maquina: as mesmas linhas, contadas; pendencia nao conta como em andamento
        Got machines = get(admin, path(o.id(), "activity/machines", null));
        assertOk(machines, "atividade por maquina");
        machines.body().get("items").forEach(x -> {
            if (x.get("workerId").asString().equals(w1.toString())) {
                assertThat(List.of(x.get("inProgress").asLong(), x.get("attention").asLong())).containsExactly(0L, 1L);
            } else if (x.get("workerId").asString().equals(w2.toString())) {
                assertThat(List.of(x.get("inProgress").asLong(), x.get("attention").asLong())).containsExactly(0L, 1L);
            }
        });

        // ---- cursor: paginas sem repeticao nem buraco
        Got p1 = get(admin, path(o.id(), "activity", "tab=history&limit=4"));
        assertThat(p1.body().get("items").size()).isEqualTo(4);
        Got p2 = get(admin, path(o.id(), "activity", "tab=history&limit=4&cursor=" + p1.body().get("nextCursor").asString()));
        List<String> all = new ArrayList<>(field(p1.body().get("items"), "id"));
        all.addAll(field(p2.body().get("items"), "id"));
        assertThat(all).doesNotHaveDuplicates().hasSize(Number.class.cast(all.size()).intValue());
        assertThat(get(admin, path(o.id(), "activity", "cursor=lixo")).status()).isEqualTo(400);
        assertThat(get(admin, path(o.id(), "activity", "tab=outra")).status()).isEqualTo(400);

        // ---- visibilidade: quem so le o Financeiro nao ve a do Restrito
        TestUser viewer = internalUser(o, true, 2);
        AccessFixtures.assignWorkspaceRole(o.id(), AccessFixtures.membershipOf(o.id(), viewer.id()), ws, "VIEWER", m);
        BrowserClient vb = login(o, viewer);
        Got seen = get(vb, path(o.id(), "activity", "tab=in_progress"));
        assertOk(seen, "visivel");
        assertThat(seen.body().get("items").size()).isEqualTo(3);
    }
}
