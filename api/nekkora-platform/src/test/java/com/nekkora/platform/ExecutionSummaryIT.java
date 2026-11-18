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
 * PLATFORM-INICIO-001 - resumo das execucoes do periodo e automacoes da organizacao com a ultima execucao, pela API real e pelo
 * papel real nekkora_platform. As regras conferidas sao as de docs/platform-inicio-001/contagem-de-execucoes.md.
 *
 * <p>MASSA SINTETICA: as linhas das quatro origens sao gravadas direto no banco, ja no estado final, com
 * {@code session_replication_role = replica} (gatilhos e chaves estrangeiras desligados SO na conexao da massa). E o unico jeito
 * de montar, num teste, mais de cem execucoes em todos os estados; as restricoes CHECK de cada tabela continuam valendo. Nada
 * aqui e caminho de producao: as leituras conferidas usam a API e o papel real.
 */
class ExecutionSummaryIT extends ReadRoutesSupport {

    private static final String FP = "'" + "ab".repeat(32) + "'";
    private static final String PERIOD = "from=2026-03-01T00:00:00Z&to=2026-04-01T00:00:00Z";

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

    /** Execucoes da Platform: {@code n} linhas iguais, de {@code at} em diante (um segundo entre elas). */
    private static String executions(UUID org, UUID ws, UUID a, UUID v, UUID m, int n, int status, String reason, String at, String retryOf) {
        return "INSERT INTO executions (organization_id, workspace_id, automation_id, automation_version_id, request_key, request_fingerprint, "
                + "requested_by_membership_id, retry_of_execution_id, command_snapshot, status, status_reason, origin, created_at, finished_at) "
                + "SELECT '" + org + "', '" + ws + "', '" + a + "', v.automation_version_id, 'k-' || gen_random_uuid(), repeat('b', 64), '" + m + "', "
                + (retryOf == null ? "NULL" : "'" + retryOf + "'") + ", v.command_json, " + status + ", " + reason + ", 1, TIMESTAMPTZ '" + at
                + "' + make_interval(secs => g), " + (status >= 4 ? "TIMESTAMPTZ '" + at + "' + make_interval(secs => g + 5)" : "NULL")
                + " FROM automation_versions v, generate_series(0, " + (n - 1) + ") g WHERE v.automation_version_id = '" + v + "'";
    }

    private static String run(UUID id, UUID org, UUID ws, UUID a, UUID v, int state, String at) {
        return "INSERT INTO agent_runs (agent_run_id, organization_id, workspace_id, automation_id, automation_version_id, authorization_id, agent_id, "
                + "worker_id, worker_agent_assignment_id, agent_execution_id, package_fingerprint, authorization_state_at_registration, supervisor_state, "
                + "registered_at) VALUES ('" + id + "', '" + org + "', '" + ws + "', '" + a + "', '" + v + "', gen_random_uuid(), gen_random_uuid(), "
                + "gen_random_uuid(), gen_random_uuid(), 'ex-' || replace(gen_random_uuid()::text, '-', ''), " + FP + ", 1, " + state + ", TIMESTAMPTZ '" + at + "')";
    }

    /** Pedido ao Agent anterior. {@code extra}: as colunas que o estado exige (CHECKs da 030). */
    private static String request(UUID org, UUID ws, UUID a, UUID v, UUID m, int state, String at, String validUntil, String extraColumns, String extraValues) {
        return "INSERT INTO run_requests (organization_id, workspace_id, automation_id, automation_version_id, authorization_id, worker_id, agent_id, "
                + "source_kind, package_fingerprint, package_fingerprint_rule, request_key, requested_by_membership_id, requested_at, valid_until, state"
                + extraColumns + ") VALUES ('" + org + "', '" + ws + "', '" + a + "', '" + v + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 1, "
                + FP + ", 'nekkora-agent/package-fingerprint/1', gen_random_uuid(), '" + m + "', TIMESTAMPTZ '" + at + "', " + validUntil + ", " + state
                + extraValues + ")";
    }

    private static String remote(UUID id, UUID org, UUID ws, UUID a, UUID v, UUID m, String at) {
        return "INSERT INTO remote_executions (remote_execution_id, organization_id, workspace_id, automation_id, automation_version_id, package_fingerprint, "
                + "package_fingerprint_rule, requirements_revision, effective_dependencies, request_key, requested_by_membership_id, requested_at) VALUES ('"
                + id + "', '" + org + "', '" + ws + "', '" + a + "', '" + v + "', " + FP + ", 'nekkora-agent/external-python-fingerprint/1', 1, '[]'::jsonb, "
                + "gen_random_uuid(), '" + m + "', TIMESTAMPTZ '" + at + "')";
    }

    /** Tentativa no estado final; {@code offerUntil}: expressao SQL do prazo da oferta. */
    private static String attempt(UUID org, UUID execution, int number, UUID m, int state, String at, String offerUntil) {
        boolean ended = state >= 7;
        return "INSERT INTO remote_attempts (organization_id, remote_execution_id, attempt_number, agent_id, worker_id, authorization_id, "
                + "created_by_membership_id, created_at, offer_valid_until, state, refusal_reason, token_hash, ended_at) VALUES ('" + org + "', '" + execution
                + "', " + number + ", gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), '" + m + "', TIMESTAMPTZ '" + at + "', " + offerUntil + ", " + state
                + ", " + (state == 11 ? "'BUSY'" : "NULL") + ", repeat('c', 64), " + (ended ? "TIMESTAMPTZ '" + at + "' + interval '1 minute'" : "NULL") + ")";
    }

    private static void grantWorkspaceRole(Org o, TestUser u, UUID ws, String role) throws Exception {
        String m = one("SELECT membership_id FROM organization_memberships WHERE user_id = '" + u.id() + "'");
        DB.asSuperuser("INSERT INTO workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status, assigned_by_membership_id) "
                + "SELECT '" + o.id() + "', '" + ws + "', '" + m + "', role_id, 2, 1, '" + o.adminMembership() + "' FROM roles WHERE code = '" + role + "'");
    }

    private static void assertCounts(JsonNode n, String what, long total, long succeeded, long failed, long inProgress, long cancelled, long notStarted,
                                     long unconfirmed) {
        assertThat(List.of(n.get("total").asLong(), n.get("succeeded").asLong(), n.get("failed").asLong(), n.get("inProgress").asLong(),
                n.get("cancelled").asLong(), n.get("notStarted").asLong(), n.get("unconfirmed").asLong()))
                .as(what + " [total, succeeded, failed, inProgress, cancelled, notStarted, unconfirmed]")
                .containsExactly(total, succeeded, failed, inProgress, cancelled, notStarted, unconfirmed);
        assertThat(n.get("succeeded").asLong() + n.get("failed").asLong() + n.get("inProgress").asLong() + n.get("cancelled").asLong()
                + n.get("notStarted").asLong() + n.get("unconfirmed").asLong()).as(what + ": a soma das situacoes e o total").isEqualTo(n.get("total").asLong());
    }

    @Test
    void resumoSomaAsQuatroOrigensSemRepetirEAutomacoesTrazemAUltimaExecucao() throws Exception {
        Org o = newOrganization();
        ProductFixtures.unlimited(o.id(), o.adminId());
        UUID m = o.adminMembership();
        UUID w1 = AccessFixtures.namedWorkspace(o.id(), "Financeiro", 1);
        UUID w2 = AccessFixtures.namedWorkspace(o.id(), "Fiscal", 1);
        UUID a1 = ProductFixtures.automation(o.id(), w1, null, "Conciliacao bancaria", m);
        UUID v1 = ProductFixtures.version(o.id(), w1, a1, true, m);
        UUID a2 = ProductFixtures.automation(o.id(), w2, null, "Apuracao mensal", m);
        UUID v2 = ProductFixtures.version(o.id(), w2, a2, true, m);
        UUID a3 = ProductFixtures.automation(o.id(), w2, null, "Sem execucoes", m);
        ProductFixtures.version(o.id(), w2, a3, false, m);

        // ---- (1) execucoes da Platform, no ambiente Financeiro: 139 no periodo (mais que o limite de 100 das listas)
        UUID first = UUID.fromString(one("SELECT gen_random_uuid()"));
        UUID lastRun = UUID.randomUUID();
        UUID linkedRun = UUID.randomUUID();
        UUID r1 = UUID.randomUUID(), r2 = UUID.randomUUID(), r3 = UUID.randomUUID(), r4 = UUID.randomUUID(), r5 = UUID.randomUUID(), r6 = UUID.randomUUID(),
                r7 = UUID.randomUUID(), r8 = UUID.randomUUID();
        mass(
                // a primeira cai exatamente no inicio do periodo (incluida); 129 sucessos a seguir
                "INSERT INTO executions (execution_id, organization_id, workspace_id, automation_id, automation_version_id, request_key, request_fingerprint, "
                        + "requested_by_membership_id, command_snapshot, status, status_reason, origin, created_at, finished_at) SELECT '" + first + "', '" + o.id()
                        + "', '" + w1 + "', '" + a1 + "', v.automation_version_id, 'k-primeira', repeat('b', 64), '" + m + "', v.command_json, 4, 1, 1, "
                        + "TIMESTAMPTZ '2026-03-01T00:00:00Z', TIMESTAMPTZ '2026-03-01T00:00:09Z' FROM automation_versions v WHERE v.automation_version_id = '"
                        + v1 + "'",
                executions(o.id(), w1, a1, v1, m, 129, 4, "1", "2026-03-02T08:00:00Z", null),
                // falha do processo: 2 pedidos e 1 REPETICAO (outro pedido: conta de novo)
                executions(o.id(), w1, a1, v1, m, 2, 5, "3", "2026-03-05T08:00:00Z", null),
                executions(o.id(), w1, a1, v1, m, 1, 5, "3", "2026-03-05T09:00:00Z", first.toString()),
                // falhou ANTES de o processo existir (START_FAILED) e prazo da fila vencido: nao iniciou, nao e erro
                executions(o.id(), w1, a1, v1, m, 2, 5, "2", "2026-03-06T08:00:00Z", null),
                executions(o.id(), w1, a1, v1, m, 1, 6, "6", "2026-03-06T09:00:00Z", null),
                // cancelada por uma pessoa; duas ainda na fila
                executions(o.id(), w1, a1, v1, m, 1, 6, "4", "2026-03-07T08:00:00Z", null),
                executions(o.id(), w1, a1, v1, m, 2, 1, "NULL", "2026-03-08T08:00:00Z", null),
                // fora do periodo: antes do inicio e exatamente no fim (o fim e exclusivo)
                executions(o.id(), w1, a1, v1, m, 1, 4, "1", "2026-02-28T23:59:59Z", null),
                executions(o.id(), w1, a1, v1, m, 1, 4, "1", "2026-04-01T00:00:00Z", null),

                // ---- (2) execucoes relatadas pelo Agent anterior, no Financeiro: 8
                run(UUID.randomUUID(), o.id(), w1, a1, v1, 4, "2026-03-10T08:00:00Z"),
                run(linkedRun, o.id(), w1, a1, v1, 4, "2026-03-10T09:00:00Z"),
                run(UUID.randomUUID(), o.id(), w1, a1, v1, 5, "2026-03-10T10:00:00Z"),
                run(UUID.randomUUID(), o.id(), w1, a1, v1, 7, "2026-03-10T11:00:00Z"),
                run(UUID.randomUUID(), o.id(), w1, a1, v1, 6, "2026-03-10T12:00:00Z"),
                run(UUID.randomUUID(), o.id(), w1, a1, v1, 8, "2026-03-10T13:00:00Z"),
                run(UUID.randomUUID(), o.id(), w1, a1, v1, 3, "2026-03-10T14:00:00Z"),
                run(UUID.randomUUID(), o.id(), w1, a1, v1, 9, "2026-03-30T08:00:00Z"),
                // a mais recente do Financeiro (fora do periodo do resumo: a ultima execucao nao depende do periodo): desfecho desconhecido
                run(lastRun, o.id(), w1, a1, v1, 9, "2026-04-02T08:00:00Z"),

                // ---- (3) pedidos ao Agent anterior: 4 que NAO viraram execucao contam; o que virou (RUN_LINKED) e contado em (2)
                request(o.id(), w1, a1, v1, m, 4, "2026-03-11T08:00:00Z", "TIMESTAMPTZ '2026-03-11T09:00:00Z'", ", delivered_at, decided_at, decision_reason",
                        ", TIMESTAMPTZ '2026-03-11T08:01:00Z', TIMESTAMPTZ '2026-03-11T08:02:00Z', 'BUSY'"),
                request(o.id(), w1, a1, v1, m, 7, "2026-03-11T10:00:00Z", "TIMESTAMPTZ '2026-03-11T11:00:00Z'", ", expired_at", ", TIMESTAMPTZ '2026-03-11T11:00:00Z'"),
                request(o.id(), w1, a1, v1, m, 8, "2026-03-11T12:00:00Z", "TIMESTAMPTZ '2026-03-11T13:00:00Z'", ", revoked_at, revoked_by_membership_id",
                        ", TIMESTAMPTZ '2026-03-11T12:30:00Z', '" + m + "'"),
                request(o.id(), w1, a1, v1, m, 3, "2026-03-11T14:00:00Z", "TIMESTAMPTZ '2026-03-11T15:00:00Z'", ", delivered_at, decided_at, agent_execution_id",
                        ", TIMESTAMPTZ '2026-03-11T14:01:00Z', TIMESTAMPTZ '2026-03-11T14:02:00Z', 'ex-" + "1f".repeat(16) + "'"),
                request(o.id(), w1, a1, v1, m, 6, "2026-03-10T08:59:00Z", "TIMESTAMPTZ '2026-03-10T09:59:00Z'",
                        ", delivered_at, decided_at, agent_execution_id, linked_at, agent_run_id",
                        ", TIMESTAMPTZ '2026-03-10T08:59:10Z', TIMESTAMPTZ '2026-03-10T08:59:20Z', 'ex-" + "2e".repeat(16) + "', TIMESTAMPTZ '2026-03-10T09:00:00Z', '"
                                + linkedRun + "'"),

                // ---- (4) execucoes remotas (Agent Client), no ambiente Fiscal: 8 solicitacoes, 9 tentativas
                remote(r1, o.id(), w2, a2, v2, m, "2026-03-15T08:00:00Z"), // falhou e foi repetida com sucesso: UMA execucao, sucesso
                attempt(o.id(), r1, 1, m, 8, "2026-03-15T08:00:00Z", "TIMESTAMPTZ '2026-03-15T08:15:00Z'"),
                attempt(o.id(), r1, 2, m, 7, "2026-03-15T09:00:00Z", "TIMESTAMPTZ '2026-03-15T09:15:00Z'"),
                remote(r2, o.id(), w2, a2, v2, m, "2026-03-16T08:00:00Z"), // terminou sem confirmacao
                attempt(o.id(), r2, 1, m, 13, "2026-03-16T08:00:00Z", "TIMESTAMPTZ '2026-03-16T08:15:00Z'"),
                remote(r3, o.id(), w2, a2, v2, m, "2026-03-17T08:00:00Z"), // recusada pela maquina
                attempt(o.id(), r3, 1, m, 11, "2026-03-17T08:00:00Z", "TIMESTAMPTZ '2026-03-17T08:15:00Z'"),
                remote(r4, o.id(), w2, a2, v2, m, "2026-03-18T08:00:00Z"), // cancelada
                attempt(o.id(), r4, 1, m, 9, "2026-03-18T08:00:00Z", "TIMESTAMPTZ '2026-03-18T08:15:00Z'"),
                remote(r5, o.id(), w2, a2, v2, m, "2026-03-19T08:00:00Z"), // oferecida e nunca recebida: a oferta venceu (vale expirada)
                attempt(o.id(), r5, 1, m, 2, "2026-03-19T08:00:00Z", "TIMESTAMPTZ '2026-03-19T08:15:00Z'"),
                remote(r6, o.id(), w2, a2, v2, m, "2026-03-20T08:00:00Z"), // em execucao
                attempt(o.id(), r6, 1, m, 6, "2026-03-20T08:00:00Z", "TIMESTAMPTZ '2026-03-20T08:15:00Z'"),
                remote(r7, o.id(), w2, a2, v2, m, "2026-03-21T08:00:00Z"), // estourou o tempo
                attempt(o.id(), r7, 1, m, 10, "2026-03-21T08:00:00Z", "TIMESTAMPTZ '2026-03-21T08:15:00Z'"),
                remote(r8, o.id(), w2, a2, v2, m, "2026-03-29T08:00:00Z")); // a mais recente do Fiscal, ainda sem tentativa

        BrowserClient admin = admin(o);
        String summary = path(o.id(), "execution-summary", PERIOD);
        Got g = get(admin, summary);
        assertOk(g, "resumo");
        JsonNode s = g.body();
        assertThat(keys(s)).containsExactly("from", "to", "total", "succeeded", "failed", "inProgress", "cancelled", "notStarted", "unconfirmed", "sources");
        assertThat(java.time.OffsetDateTime.parse(s.get("from").asString()).toInstant()).isEqualTo(java.time.Instant.parse("2026-03-01T00:00:00Z"));
        assertThat(java.time.OffsetDateTime.parse(s.get("to").asString()).toInstant()).isEqualTo(java.time.Instant.parse("2026-04-01T00:00:00Z"));
        assertCounts(s.get("sources").get("platform"), "platform", 139, 130, 3, 2, 1, 3, 0);
        assertCounts(s.get("sources").get("localAgent"), "localAgent", 12, 2, 2, 2, 2, 3, 1);
        assertCounts(s.get("sources").get("remote"), "remote", 8, 1, 1, 2, 1, 2, 1);
        assertCounts(s, "tudo", 159, 133, 6, 6, 4, 8, 2);
        assertThat(keys(s.get("sources"))).containsExactly("platform", "localAgent", "remote");
        // a lista de execucoes entrega no maximo 100 por pagina: o resumo NAO e a soma de uma pagina
        assertThat(get(admin, path(o.id(), "executions", PERIOD + "&limit=100")).body().get("items")).hasSize(100);
        // ler nao grava nada (a oferta vencida conta como expirada sem ser alterada)
        assertThat(one("SELECT state FROM remote_attempts WHERE remote_execution_id = '" + r5 + "'")).isEqualTo("2");

        // periodo: o que ficou de fora entra quando o periodo o cobre; fim exclusivo
        assertCounts(get(admin, path(o.id(), "execution-summary", "from=2026-02-28T00:00:00Z&to=2026-03-01T00:00:00Z")).body(), "vespera", 1, 1, 0, 0, 0, 0, 0);
        assertCounts(get(admin, path(o.id(), "execution-summary", "from=2026-04-01T00:00:00Z&to=2026-04-02T00:00:00Z")).body(), "dia seguinte", 1, 1, 0, 0, 0, 0, 0);
        assertCounts(get(admin, path(o.id(), "execution-summary", "from=2025-01-01T00:00:00Z&to=2025-02-01T00:00:00Z")).body(), "sem nada", 0, 0, 0, 0, 0, 0, 0);
        // fuso: o mesmo instante escrito em outro fuso da a mesma conta
        assertCounts(get(admin, path(o.id(), "execution-summary", "from=2026-02-28T21:00:00-03:00&to=2026-03-31T21:00:00-03:00")).body(), "em -03:00", 159, 133, 6, 6,
                4, 8, 2);

        // pedidos invalidos
        assertError(get(admin, path(o.id(), "execution-summary", null)), 400, "invalid_request");
        assertError(get(admin, path(o.id(), "execution-summary", "from=2026-03-01T00:00:00Z")), 400, "invalid_request");
        assertError(get(admin, path(o.id(), "execution-summary", "from=2026-04-01T00:00:00Z&to=2026-03-01T00:00:00Z")), 400, "invalid_request");
        assertError(get(admin, path(o.id(), "execution-summary", "from=2026-03-01T00:00:00Z&to=2026-03-01T00:00:00Z")), 400, "invalid_request");
        assertError(get(admin, path(o.id(), "execution-summary", "from=2025-01-01T00:00:00Z&to=2026-03-01T00:00:00Z")), 400, "invalid_request");
        assertError(get(admin, path(o.id(), "execution-summary", "from=2026-03-01T00:00:00&to=2026-04-01T00:00:00")), 400, "invalid_request");

        // ---- permissoes por ambiente: quem so le o Fiscal ve so as execucoes do Fiscal, tambem na agregacao
        TestUser person = internalUser(o, true, 2);
        grantWorkspaceRole(o, person, w2, "VIEWER");
        BrowserClient viewer = login(o, person);
        JsonNode vs = get(viewer, summary).body();
        assertCounts(vs, "so o Fiscal", 8, 1, 1, 2, 1, 2, 1);
        assertCounts(vs.get("sources").get("platform"), "so o Fiscal: platform", 0, 0, 0, 0, 0, 0, 0);
        assertCounts(vs.get("sources").get("localAgent"), "so o Fiscal: localAgent", 0, 0, 0, 0, 0, 0, 0);
        // pessoa sem ambiente nenhum: zeros, nunca os numeros da organizacao
        BrowserClient nobody = login(o, internalUser(o, true, 2));
        assertCounts(get(nobody, summary).body(), "sem ambiente", 0, 0, 0, 0, 0, 0, 0);
        // conta externa: rota interna
        UUID client = newClient(o);
        BrowserClient external = login(o, externalUser(o, client, newWorkspace(o, client)));
        assertThat(get(external, summary).status()).isEqualTo(403);
        assertThat(get(external, path(o.id(), "automations", null)).status()).isEqualTo(403);
        // outra organizacao: os proprios numeros (zero), e a sessao dela nao le esta
        Org other = newOrganization();
        BrowserClient foreign = admin(other);
        assertCounts(get(foreign, path(other.id(), "execution-summary", PERIOD)).body(), "outra organizacao", 0, 0, 0, 0, 0, 0, 0);
        assertThat(get(foreign, summary).status()).as("a sessao de outra organizacao nao le").isIn(401, 403, 404);
        assertThat(get(foreign, path(other.id(), "automations", null)).body().get("items")).isEmpty();
        assertThat(browser().get(summary).status()).isEqualTo(401);
        assertThat(browser().get(path(o.id(), "automations", null)).status()).isEqualTo(401);

        // ---- automacoes da organizacao, por nome, com o ambiente e a ultima execucao
        Got ag = get(admin, path(o.id(), "automations", null));
        assertOk(ag, "automacoes");
        JsonNode items = ag.body().get("items");
        assertThat(field(items, "name")).containsExactly("Apuracao mensal", "Conciliacao bancaria", "Sem execucoes");
        assertThat(ag.body().get("nextCursor").isNull()).isTrue();
        JsonNode fiscal = items.get(0);
        assertThat(keys(fiscal)).containsExactly("automationId", "workspaceId", "workspaceName", "name", "description", "status", "latestPublishedVersion",
                "lastExecution");
        assertThat(fiscal.get("workspaceName").asString()).isEqualTo("Fiscal");
        assertThat(fiscal.get("latestPublishedVersion").get("versionNumber").asInt()).isEqualTo(1);
        assertThat(keys(fiscal.get("lastExecution"))).containsExactly("source", "id", "outcome", "requestedAt");
        assertThat(fiscal.get("lastExecution").get("source").asString()).isEqualTo("remote");
        assertThat(fiscal.get("lastExecution").get("id").asString()).isEqualTo(r8.toString());
        assertThat(fiscal.get("lastExecution").get("outcome").asString()).isEqualTo("inProgress");
        JsonNode financeiro = items.get(1);
        assertThat(financeiro.get("workspaceName").asString()).isEqualTo("Financeiro");
        assertThat(financeiro.get("lastExecution").get("source").asString()).isEqualTo("localAgent");
        assertThat(financeiro.get("lastExecution").get("id").asString()).isEqualTo(lastRun.toString());
        assertThat(financeiro.get("lastExecution").get("outcome").asString()).as("desfecho desconhecido nunca vira erro").isEqualTo("unconfirmed");
        assertThat(java.time.OffsetDateTime.parse(financeiro.get("lastExecution").get("requestedAt").asString()).toInstant())
                .isEqualTo(java.time.Instant.parse("2026-04-02T08:00:00Z"));
        assertThat(items.get(2).get("lastExecution").isNull()).as("sem execucao: nulo, nada inventado").isTrue();
        assertThat(items.get(2).get("latestPublishedVersion").isNull()).isTrue();
        // paginacao e filtro de situacao
        assertThat(field(allPages(admin, o.id(), "automations", null, 1), "name")).containsExactly("Apuracao mensal", "Conciliacao bancaria", "Sem execucoes");
        assertThat(get(admin, path(o.id(), "automations", "status=archived")).body().get("items")).isEmpty();
        assertError(get(admin, path(o.id(), "automations", "status=qualquer")), 400, "invalid_request");
        assertError(get(admin, path(o.id(), "automations", "limit=0")), 400, "invalid_request");
        // quem so le o Fiscal ve so as automacoes do Fiscal; sem ambiente, nenhuma
        assertThat(field(get(viewer, path(o.id(), "automations", null)).body().get("items"), "name")).containsExactly("Apuracao mensal", "Sem execucoes");
        assertThat(get(nobody, path(o.id(), "automations", null)).body().get("items")).isEmpty();
        // a rota por identificador continua a mesma
        assertThat(get(admin, path(o.id(), "automations/" + a1, null)).body().get("name").asString()).isEqualTo("Conciliacao bancaria");
    }
}
