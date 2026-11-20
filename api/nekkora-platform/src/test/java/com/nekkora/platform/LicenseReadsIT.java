package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.nekkora.core.testsupport.BrowserClient;

import tools.jackson.databind.JsonNode;

/**
 * PLATFORM-LICENCIAMENTO-001 (E1 + E2) - modelo de licencas da migration 061 e as duas leituras do administrador, pela API real
 * e pelo papel real nekkora_platform.
 *
 * <p>Nesta etapa NINGUEM tem rota para escrever licenca: a massa e gravada como superusuario (os gatilhos e as restricoes da
 * 061 continuam valendo; so o que precisa simular a passagem do tempo - uma licenca que VENCEU depois de vinculada - usa
 * {@code session_replication_role = replica}, dito no ponto). O que e conferido: as contas da API, o isolamento entre
 * organizacoes, as permissoes de leitura e as garantias do banco (consumo unico, tambem sob concorrencia; so acrescimo).
 */
class LicenseReadsIT extends ReadRoutesSupport {

    private static String one(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** Licenca com o primeiro periodo [from, to) (expressoes SQL). */
    private static UUID license(UUID org, String from, String to) {
        UUID id = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO organization_licenses (license_id, organization_id, license_number) VALUES ('" + id + "', '" + org + "', 1)",
                period(org, id, from, to));
        return id;
    }

    private static String period(UUID org, UUID license, String from, String to) {
        return "INSERT INTO organization_license_periods (organization_id, license_id, period_number, kind, starts_at, ends_at) VALUES ('" + org + "', '"
                + license + "', 1, 1, " + from + ", " + to + ")";
    }

    private static String assign(UUID org, UUID license, UUID ws, UUID automation, UUID by) {
        return "INSERT INTO organization_license_assignments (organization_id, license_id, workspace_id, automation_id, assigned_by_membership_id) VALUES ('"
                + org + "', '" + license + "', '" + ws + "', '" + automation + "', '" + by + "')";
    }

    /** O banco recusa, e PELO MOTIVO esperado ({@code reason}: trecho da mensagem do banco). */
    private static void refused(String what, String reason, String... sqls) {
        assertThatThrownBy(() -> DB.asSuperuser(sqls)).as(what).rootCause().isInstanceOf(SQLException.class).hasMessageContaining(reason);
    }

    private static void assertSummary(JsonNode s, String what, String model, long contracted, long used, long available, long expiredWithAutomation) {
        assertThat(keys(s)).as(what).containsExactly("model", "modelSince", "contracted", "used", "available", "licensedAutomations",
                "expiredWithAutomation", "asOf");
        assertThat(s.get("model").asString()).as(what + ": modelo").isEqualTo(model);
        assertThat(List.of(s.get("contracted").asLong(), s.get("used").asLong(), s.get("available").asLong(), s.get("licensedAutomations").asLong(),
                s.get("expiredWithAutomation").asLong())).as(what + " [contracted, used, available, licensedAutomations, expiredWithAutomation]")
                .containsExactly(contracted, used, available, used, expiredWithAutomation);
        assertThat(s.get("used").asLong() + s.get("available").asLong()).as(what + ": utilizadas + disponiveis = contratadas").isEqualTo(s.get("contracted").asLong());
    }

    @Test
    void resumoEListaDasLicencasComIsolamentoPermissoesEContasQueFecham() throws Exception {
        Org o = newOrganization();
        UUID m = o.adminMembership();
        UUID w1 = AccessFixtures.namedWorkspace(o.id(), "Financeiro", 1);
        UUID w2 = AccessFixtures.namedWorkspace(o.id(), "Fiscal", 1);
        UUID a1 = ProductFixtures.automation(o.id(), w1, null, "Conciliacao bancaria", m);
        UUID v1 = ProductFixtures.version(o.id(), w1, a1, true, m);
        UUID a2 = ProductFixtures.automation(o.id(), w2, null, "Apuracao mensal", m);
        UUID a3 = ProductFixtures.automation(o.id(), w2, null, "Fechamento", m);
        BrowserClient admin = admin(o);
        String summary = path(o.id(), "licenses/summary", null);
        String list = path(o.id(), "licenses", null);

        // ---- nada concedido: zero e zero, modelo anterior, lista vazia. A 061 nao cria licenca nem liga a exigencia
        Got empty = get(admin, summary);
        assertOk(empty, "resumo vazio");
        assertSummary(empty.body(), "sem licencas", "legacy", 0, 0, 0, 0);
        assertThat(empty.body().get("modelSince").isNull()).isTrue();
        assertThat(get(admin, list).body().get("items")).isEmpty();
        assertThat(one("SELECT count(*) FROM organization_licenses")).as("nenhuma licenca criada pela migration nem pela criacao da organizacao").isEqualTo("0");
        assertThat(one("SELECT count(*) FROM organization_license_enforcement")).isEqualTo("0");

        // ---- seis licencas: 1 e 2 vigentes e vinculadas; 3 vigente e disponivel; 4 venceu vinculada; 5 encerrada; 6 so no futuro
        UUID l1 = license(o.id(), "clock_timestamp() - interval '10 days'", "clock_timestamp() + interval '20 days'");
        UUID l2 = license(o.id(), "clock_timestamp() - interval '10 days'", "clock_timestamp() + interval '20 days'");
        UUID l3 = license(o.id(), "clock_timestamp() - interval '1 day'", "clock_timestamp() + interval '29 days'");
        UUID l4 = license(o.id(), "clock_timestamp() - interval '40 days'", "clock_timestamp() - interval '10 days'");
        UUID l5 = license(o.id(), "clock_timestamp() - interval '5 days'", "clock_timestamp() + interval '25 days'");
        UUID l6 = license(o.id(), "clock_timestamp() + interval '5 days'", "clock_timestamp() + interval '35 days'");
        DB.asSuperuser(assign(o.id(), l1, w1, a1, m), assign(o.id(), l2, w2, a2, m));
        // a 4 venceu DEPOIS de vinculada: o vinculo de uma licenca ja vencida so existe com o tempo, simulado aqui sem os gatilhos
        DB.asSuperuser("SET session_replication_role = replica",
                "INSERT INTO organization_license_assignments (organization_id, license_id, workspace_id, automation_id, assigned_at, assigned_by_membership_id) "
                        + "VALUES ('" + o.id() + "', '" + l4 + "', '" + w2 + "', '" + a3 + "', clock_timestamp() - interval '39 days', '" + m + "')");
        DB.asSuperuser("UPDATE organization_licenses SET ended_at = clock_timestamp() WHERE license_id = '" + l5 + "'");
        assertThat(one("SELECT string_agg(license_number::text, ',' ORDER BY license_number) FROM organization_licenses WHERE organization_id = '" + o.id() + "'"))
                .as("o numero e atribuido pelo servidor, em sequencia, mesmo que quem insere mande outro").isEqualTo("1,2,3,4,5,6");
        // a1 com execucao em andamento (solicitacao remota sem tentativa); a2 arquivada: continua consumindo a licenca
        DB.asSuperuser("SET session_replication_role = replica",
                "INSERT INTO remote_executions (organization_id, workspace_id, automation_id, automation_version_id, package_fingerprint, "
                        + "package_fingerprint_rule, requirements_revision, effective_dependencies, request_key, requested_by_membership_id) VALUES ('" + o.id()
                        + "', '" + w1 + "', '" + a1 + "', '" + v1 + "', '" + "ab".repeat(32) + "', 'nekkora-agent/external-python-fingerprint/1', 1, '[]'::jsonb, "
                        + "gen_random_uuid(), '" + m + "')",
                "UPDATE automations SET status = 3 WHERE automation_id = '" + a2 + "'");

        Got sg = get(admin, summary);
        assertOk(sg, "resumo");
        assertSummary(sg.body(), "seis licencas", "legacy", 3, 2, 1, 1);

        Got lg = get(admin, list);
        assertOk(lg, "lista");
        JsonNode items = lg.body().get("items");
        assertThat(keys(lg.body())).containsExactly("items", "nextCursor", "asOf");
        assertThat(field(items, "number")).containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(field(items, "status")).containsExactly("active", "active", "active", "expired", "ended", "scheduled");
        assertThat(field(items, "licenseId")).containsExactly(l1.toString(), l2.toString(), l3.toString(), l4.toString(), l5.toString(), l6.toString());
        JsonNode first = items.get(0);
        assertThat(keys(first)).containsExactly("licenseId", "number", "status", "validFrom", "validUntil", "nextPeriodStartsAt", "renewals", "automation");
        assertThat(keys(first.get("automation"))).containsExactly("automationId", "name", "status", "workspaceId", "workspaceName", "assignedAt",
                "executionInProgress");
        assertThat(first.get("automation").get("name").asString()).isEqualTo("Conciliacao bancaria");
        assertThat(first.get("automation").get("workspaceName").asString()).isEqualTo("Financeiro");
        assertThat(first.get("automation").get("executionInProgress").asBoolean()).as("licenca ativa E execucao em andamento").isTrue();
        JsonNode second = items.get(1);
        assertThat(second.get("status").asString()).as("automacao arquivada: a licenca continua ativa e vinculada").isEqualTo("active");
        assertThat(second.get("automation").get("status").asString()).isEqualTo("archived");
        assertThat(second.get("automation").get("executionInProgress").asBoolean()).as("licenca ativa SEM execucao em andamento").isFalse();
        assertThat(items.get(2).get("automation").isNull()).as("disponivel: sem automacao").isTrue();
        assertThat(items.get(3).get("automation").get("name").asString()).as("vencida: o vinculo permanece").isEqualTo("Fechamento");
        assertThat(items.get(5).get("nextPeriodStartsAt").isNull()).as("so no futuro: informa quando comeca").isFalse();

        // ---- as contas do resumo sao as da lista INTEIRA, lida em paginas de 2 (o resumo nao depende de pagina)
        List<JsonNode> all = allPages(admin, o.id(), "licenses", null, 2);
        assertThat(field(all, "number")).containsExactly("1", "2", "3", "4", "5", "6");
        long active = all.stream().filter(i -> i.get("status").asString().equals("active")).count();
        long activeUsed = all.stream().filter(i -> i.get("status").asString().equals("active") && !i.get("automation").isNull()).count();
        long expiredUsed = all.stream().filter(i -> i.get("status").asString().equals("expired") && !i.get("automation").isNull()).count();
        assertThat(List.of(active, activeUsed, active - activeUsed, expiredUsed)).as("lista inteira = resumo")
                .containsExactly(sg.body().get("contracted").asLong(), sg.body().get("used").asLong(), sg.body().get("available").asLong(),
                        sg.body().get("expiredWithAutomation").asLong());
        assertThat(get(admin, path(o.id(), "licenses", "limit=1")).body().get("items")).hasSize(1);
        assertError(get(admin, path(o.id(), "licenses", "limit=0")), 400, "invalid_request");

        // ---- renovar acrescenta um periodo, preserva o anterior e MANTEM o vinculo
        DB.asSuperuser("INSERT INTO organization_license_periods (organization_id, license_id, period_number, kind, starts_at, ends_at) SELECT organization_id, "
                + "license_id, 1, 1, ends_at, ends_at + interval '30 days' FROM organization_license_periods WHERE license_id = '" + l4 + "'");
        assertThat(one("SELECT string_agg(period_number || ':' || kind, ',' ORDER BY period_number) FROM organization_license_periods WHERE license_id = '" + l4 + "'"))
                .as("o periodo anterior fica; o novo e a renovacao 2").isEqualTo("1:1,2:2");
        JsonNode renewed = get(admin, list).body().get("items").get(3);
        assertThat(renewed.get("status").asString()).isEqualTo("active");
        assertThat(renewed.get("renewals").asInt()).isEqualTo(1);
        assertThat(renewed.get("automation").get("name").asString()).as("renovar mantem o vinculo").isEqualTo("Fechamento");
        assertSummary(get(admin, summary).body(), "depois de renovar a 4", "legacy", 4, 3, 1, 0);

        // ---- isolamento: outra organizacao tem as proprias licencas e nao le estas
        Org other = newOrganization();
        UUID ow = AccessFixtures.namedWorkspace(other.id(), "Outra", 1);
        UUID oa = ProductFixtures.automation(other.id(), ow, null, "Automacao da outra", other.adminMembership());
        UUID ol = license(other.id(), "clock_timestamp() - interval '1 day'", "clock_timestamp() + interval '29 days'");
        BrowserClient foreign = admin(other);
        assertSummary(get(foreign, path(other.id(), "licenses/summary", null)).body(), "outra organizacao", "legacy", 1, 0, 1, 0);
        assertThat(field(get(foreign, path(other.id(), "licenses", null)).body().get("items"), "licenseId")).containsExactly(ol.toString());
        assertThat(get(foreign, summary).status()).as("a sessao de outra organizacao nao le").isIn(401, 403, 404);
        assertThat(get(foreign, list).status()).isIn(401, 403, 404);
        refused("licenca de uma organizacao nao atende automacao de outra", "violates foreign key", assign(o.id(), l3, ow, oa, m));
        refused("nem com a organizacao da automacao", "licenca inexistente nesta organizacao", assign(other.id(), l3, ow, oa, other.adminMembership()));
        assertSummary(get(admin, summary).body(), "a outra organizacao nao muda esta", "legacy", 4, 3, 1, 0);

        // ---- permissoes: so quem administra a organizacao le
        TestUser person = internalUser(o, true, 2);
        DB.asSuperuser("INSERT INTO workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status, assigned_by_membership_id) "
                + "SELECT '" + o.id() + "', '" + w1 + "', om.membership_id, r.role_id, 2, 1, '" + m + "' FROM roles r, organization_memberships om "
                + "WHERE r.code = 'WORKSPACE_ADMIN' AND om.user_id = '" + person.id() + "'");
        BrowserClient member = login(o, person);
        assertThat(get(member, summary).status()).as("administrador de workspace nao e administrador da organizacao").isEqualTo(403);
        assertThat(get(member, list).status()).isEqualTo(403);
        UUID client = newClient(o);
        BrowserClient external = login(o, externalUser(o, client, newWorkspace(o, client)));
        assertThat(get(external, summary).status()).isEqualTo(403);
        assertThat(get(external, list).status()).isEqualTo(403);
        assertThat(browser().get(summary).status()).isEqualTo(401);
        assertThat(browser().get(list).status()).isEqualTo(401);

        // ---- o papel da Platform so le, e so a organizacao do contexto
        try (Connection c = DB.connectAs("nekkora_platform"); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM public.organization_licenses")) {
                rs.next();
                assertThat(rs.getInt(1)).as("sem contexto de organizacao: nenhuma linha").isZero();
            }
            st.execute("SELECT set_config('nekkora.organization_id', '" + other.id() + "', true)");
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM public.organization_licenses")) {
                rs.next();
                assertThat(rs.getInt(1)).as("no contexto da outra: so a dela").isEqualTo(1);
            }
            st.execute("SELECT set_config('nekkora.organization_id', '" + o.id() + "', true)");
            for (String write : List.of(
                    "INSERT INTO public.organization_licenses (organization_id, license_number) VALUES ('" + o.id() + "', 99)",
                    assign(o.id(), l3, w2, a3, m),
                    "UPDATE public.organization_licenses SET ended_at = clock_timestamp() WHERE license_id = '" + l3 + "'",
                    "INSERT INTO public.organization_license_enforcement (organization_id, model, act_id) VALUES ('" + o.id() + "', 2, gen_random_uuid())")) {
                assertThatThrownBy(() -> {
                    try (Statement w = c.createStatement()) {
                        w.execute("SAVEPOINT s");
                        w.execute(write);
                    }
                }).as("a Platform nao escreve: " + write.substring(0, 60)).isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
                st.execute("ROLLBACK TO SAVEPOINT s");
            }
            c.rollback();
        }
    }

    @Test
    void consumoUnicoTambemSobConcorrenciaESoAcrescimo() throws Exception {
        Org o = newOrganization();
        UUID m = o.adminMembership();
        UUID w = AccessFixtures.namedWorkspace(o.id(), "Operacao", 1);
        UUID a1 = ProductFixtures.automation(o.id(), w, null, "Primeira", m);
        UUID a2 = ProductFixtures.automation(o.id(), w, null, "Segunda", m);
        UUID a3 = ProductFixtures.automation(o.id(), w, null, "Terceira", m);
        UUID l1 = license(o.id(), "clock_timestamp() - interval '1 day'", "clock_timestamp() + interval '29 days'");
        UUID l2 = license(o.id(), "clock_timestamp() - interval '1 day'", "clock_timestamp() + interval '29 days'");

        // ---- duas ativacoes simultaneas da MESMA licenca para automacoes diferentes: exatamente uma vence
        assertThat(race(assign(o.id(), l1, w, a1, m), assign(o.id(), l1, w, a2, m))).as("mesma licenca, duas automacoes").isEqualTo(1);
        assertThat(one("SELECT count(*) FROM organization_license_assignments WHERE license_id = '" + l1 + "' AND released_at IS NULL")).isEqualTo("1");
        String winner = one("SELECT automation_id FROM organization_license_assignments WHERE license_id = '" + l1 + "'");
        UUID free = winner.equals(a1.toString()) ? a2 : a1;
        // ---- duas licencas simultaneas para a MESMA automacao (a que ja tem licenca): nenhuma entra
        refused("a automacao que ja consome uma licenca nao consome outra", "ux_organization_license_assignments_automation", assign(o.id(), l2, w, UUID.fromString(winner), m));
        // ---- e duas licencas diferentes, ao mesmo tempo, para a mesma automacao LIVRE: exatamente uma vence
        UUID l3 = license(o.id(), "clock_timestamp() - interval '1 day'", "clock_timestamp() + interval '29 days'");
        assertThat(race(assign(o.id(), l2, w, free, m), assign(o.id(), l3, w, free, m))).as("duas licencas, mesma automacao").isEqualTo(1);
        assertThat(one("SELECT count(*) FROM organization_license_assignments WHERE automation_id = '" + free + "' AND released_at IS NULL")).isEqualTo("1");
        assertThat(one("SELECT count(*) FROM organization_license_assignments WHERE organization_id = '" + o.id() + "' AND released_at IS NULL"))
                .as("tres licencas, duas automacoes vinculadas: nada consumido em dobro").isEqualTo("2");

        // ---- pausar, renomear e arquivar a automacao nao devolve a licenca
        DB.asSuperuser("UPDATE automations SET status = 2 WHERE automation_id = '" + winner + "'");
        refused("automacao suspensa: a licenca dela continua ocupada", "ux_organization_license_assignments_license", assign(o.id(), l1, w, a3, m));
        DB.asSuperuser("UPDATE automations SET status = 3 WHERE automation_id = '" + winner + "'");
        refused("automacao arquivada: a licenca dela continua ocupada", "ux_organization_license_assignments_license", assign(o.id(), l1, w, a3, m));
        assertThat(one("SELECT count(*) FROM organization_license_assignments WHERE license_id = '" + l1 + "' AND released_at IS NULL")).isEqualTo("1");

        // ---- licenca vencida, futura ou encerrada nao abre vinculo
        UUID expired = license(o.id(), "clock_timestamp() - interval '40 days'", "clock_timestamp() - interval '10 days'");
        UUID future = license(o.id(), "clock_timestamp() + interval '1 day'", "clock_timestamp() + interval '31 days'");
        refused("licenca vencida", "LICENSE_NOT_VALID", assign(o.id(), expired, w, a3, m));
        refused("licenca que ainda nao comecou", "LICENSE_NOT_VALID", assign(o.id(), future, w, a3, m));
        String unbound = one("SELECT l.license_id FROM organization_licenses l WHERE l.organization_id = '" + o.id() + "' AND l.license_id IN ('" + l2 + "', '" + l3
                + "') AND NOT EXISTS (SELECT 1 FROM organization_license_assignments g WHERE g.license_id = l.license_id)");
        DB.asSuperuser("UPDATE organization_licenses SET ended_at = clock_timestamp() WHERE license_id = '" + unbound + "'");
        refused("licenca encerrada", "LICENSE_NOT_VALID", assign(o.id(), UUID.fromString(unbound), w, a3, m));
        refused("licenca encerrada nao renova", "licenca encerrada nao recebe periodo", "INSERT INTO organization_license_periods (organization_id, license_id, period_number, kind, starts_at, ends_at) "
                + "VALUES ('" + o.id() + "', '" + unbound + "', 2, 2, clock_timestamp() + interval '40 days', clock_timestamp() + interval '70 days')");
        refused("licenca encerrada nao reabre", "so muda para ser encerrada", "UPDATE organization_licenses SET ended_at = NULL WHERE license_id = '" + unbound + "'");

        // ---- periodos: nao se sobrepoem, nao mudam, nao somem
        refused("renovacao que comeca antes do fim do periodo anterior", "nao pode comecar antes do fim do anterior", "INSERT INTO organization_license_periods (organization_id, license_id, period_number, "
                + "kind, starts_at, ends_at) VALUES ('" + o.id() + "', '" + l1 + "', 2, 2, clock_timestamp(), clock_timestamp() + interval '60 days')");
        refused("periodo nao muda", "atualizacao nao permitida", "UPDATE organization_license_periods SET ends_at = ends_at + interval '1 day' WHERE license_id = '" + l1 + "'");
        refused("periodo nao some", "exclusao nao permitida", "DELETE FROM organization_license_periods WHERE license_id = '" + l1 + "'");
        refused("vinculo nao some", "exclusao nao permitida", "DELETE FROM organization_license_assignments WHERE license_id = '" + l1 + "'");
        refused("vinculo nao troca de automacao", "so muda para ser encerrado", "UPDATE organization_license_assignments SET automation_id = '" + a3 + "' WHERE license_id = '" + l1 + "'");
        refused("licenca nao some", "exclusao nao permitida", "DELETE FROM organization_licenses WHERE license_id = '" + l1 + "'");

        // ---- o modelo so liga com o ato que liga; a trilha e so de acrescimo
        refused("exigencia sem ato", "nao e o que liga ou desliga", "INSERT INTO organization_license_enforcement (organization_id, model, act_id) VALUES ('" + o.id() + "', 2, gen_random_uuid())");
        refused("ato do operador sem justificativa", "violates check constraint", "INSERT INTO organization_license_acts (organization_id, act, operator_user_id) VALUES ('" + o.id() + "', 6, '"
                + o.adminId() + "')");
        UUID act = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO organization_license_acts (act_id, organization_id, act, operator_user_id, justification) VALUES ('" + act + "', '" + o.id()
                + "', 6, '" + o.adminId() + "', 'ensaio do modelo por licenca no teste')");
        refused("ato nao muda", "atualizacao nao permitida", "UPDATE organization_license_acts SET justification = 'outra justificativa qualquer' WHERE act_id = '" + act + "'");
        refused("ato nao some", "exclusao nao permitida", "DELETE FROM organization_license_acts WHERE act_id = '" + act + "'");
        refused("o ato que liga nao serve para registrar o modelo anterior", "nao e o que liga ou desliga",
                "INSERT INTO organization_license_enforcement (organization_id, model, act_id) VALUES ('" + o.id() + "', 1, '" + act + "')");
        DB.asSuperuser("INSERT INTO organization_license_enforcement (organization_id, model, act_id) VALUES ('" + o.id() + "', 2, '" + act + "')");
        JsonNode s = get(admin(o), path(o.id(), "licenses/summary", null)).body();
        assertThat(s.get("model").asString()).as("o modelo da organizacao e um dado visivel").isEqualTo("licensed");
        assertThat(s.get("modelSince").isNull()).isFalse();
    }

    /** Executa os dois comandos ao mesmo tempo, cada um na sua transacao; devolve quantos foram confirmados. */
    private static int race(String first, String second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (String sql : List.of(first, second)) {
                results.add(pool.submit(() -> {
                    try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement()) {
                        c.setAutoCommit(false);
                        ready.countDown();
                        go.await(10, TimeUnit.SECONDS);
                        try {
                            st.execute(sql);
                            c.commit();
                            return true;
                        } catch (SQLException e) {
                            c.rollback();
                            return false;
                        }
                    }
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            int ok = 0;
            for (Future<Boolean> f : results) {
                ok += f.get(30, TimeUnit.SECONDS) ? 1 : 0;
            }
            return ok;
        } finally {
            pool.shutdownNow();
        }
    }
}
