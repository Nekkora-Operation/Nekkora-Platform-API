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
 * PLATFORM-LICENCIAMENTO-001 (D1, migration 062) - o vinculo explicito da licenca a uma automacao pelo administrador, PELA API
 * REAL: sessao, CSRF, confirmacao reforcada, permissao, recusas com o motivo do contrato, trilha e CONCORRENCIA pelo caminho
 * real (duas sessoes de administradores pedindo ao mesmo tempo).
 *
 * <p>As licencas sao concedidas direto no banco, como superusuario: conceder e ato do operador da Nekkora e nao tem rota
 * nesta etapa. Tudo o que e conferido aqui passa pela rota de vinculo e pelo papel real do servico.
 */
class LicenseAssignmentIT extends ReadRoutesSupport {

    private static String one(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static UUID license(UUID org, String from, String to) {
        UUID id = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO organization_licenses (license_id, organization_id, license_number) VALUES ('" + id + "', '" + org + "', 1)",
                "INSERT INTO organization_license_periods (organization_id, license_id, period_number, kind, starts_at, ends_at) VALUES ('" + org + "', '" + id
                        + "', 1, 1, " + from + ", " + to + ")");
        return id;
    }

    private static UUID valid(UUID org) {
        return license(org, "clock_timestamp() - interval '1 day'", "clock_timestamp() + interval '29 days'");
    }

    private static BrowserClient.Response assign(BrowserClient n, UUID org, UUID license, UUID automation) {
        return n.post(path(org, "licenses/" + license + "/assignment", null), "{\"automationId\":\"" + automation + "\"}");
    }

    private static void conflict(BrowserClient.Response r, String reason) throws Exception {
        assertThat(r.status()).as(r.body()).isEqualTo(409);
        JsonNode b = JSON.readTree(r.body());
        assertThat(b.get("error").asString()).isEqualTo("conflict");
        assertThat(b.get("reason").asString()).isEqualTo(reason);
    }

    private void stepUp(BrowserClient n, UUID org, UUID user) {
        assertThat(n.post("/api/v1/o/" + org + "/auth/mfa/step-up", "{\"code\":\"" + code(user) + "\"}").status()).isEqualTo(204);
    }

    @Test
    void administradorVinculaComConfirmacaoETrilhaERecusasComMotivo() throws Exception {
        Org o = newOrganization();
        UUID m = o.adminMembership();
        UUID w = AccessFixtures.namedWorkspace(o.id(), "Financeiro", 1);
        UUID a1 = ProductFixtures.automation(o.id(), w, null, "Conciliacao", m);
        UUID a2 = ProductFixtures.automation(o.id(), w, null, "Apuracao", m);
        UUID a3 = ProductFixtures.automation(o.id(), w, null, "Arquivada", m);
        DB.asSuperuser("UPDATE automations SET status = 3 WHERE automation_id = '" + a3 + "'");
        UUID l1 = valid(o.id());
        UUID l2 = valid(o.id());
        UUID expired = license(o.id(), "clock_timestamp() - interval '40 days'", "clock_timestamp() - interval '10 days'");
        BrowserClient admin = admin(o);
        String summary = path(o.id(), "licenses/summary", null);

        // ---- sem confirmacao reforcada recente: recusado, nada gravado
        assertThat(assign(admin, o.id(), l1, a1).status()).as("sem confirmacao reforcada").isEqualTo(403);
        assertThat(one("SELECT count(*) FROM organization_license_assignments WHERE organization_id = '" + o.id() + "'")).isEqualTo("0");
        stepUp(admin, o.id(), o.adminId());

        // ---- vincula: a licenca passa a atender a automacao; o ato fica na trilha com quem fez
        BrowserClient.Response ok = assign(admin, o.id(), l1, a1);
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(ok.body());
        assertThat(keys(body)).containsExactly("licenseId", "number", "automationId", "workspaceId", "assignedAt");
        assertThat(body.get("licenseId").asString()).isEqualTo(l1.toString());
        assertThat(body.get("automationId").asString()).isEqualTo(a1.toString());
        assertThat(body.get("workspaceId").asString()).isEqualTo(w.toString());
        JsonNode s = get(admin, summary).body();
        assertThat(List.of(s.get("contracted").asLong(), s.get("used").asLong(), s.get("available").asLong())).containsExactly(2L, 1L, 1L);
        assertThat(one("SELECT act || '|' || actor_membership_id || '|' || license_id || '|' || to_automation_id || '|' || (operator_user_id IS NULL) "
                + "FROM organization_license_acts WHERE organization_id = '" + o.id() + "'")).as("trilha: ato 4, com quem vinculou")
                .isEqualTo("4|" + m + "|" + l1 + "|" + a1 + "|true");
        assertThat(one("SELECT assigned_by_membership_id FROM organization_license_assignments WHERE license_id = '" + l1 + "'")).isEqualTo(m.toString());

        // ---- recusas, cada uma com o seu motivo; nenhuma grava vinculo nem ato
        conflict(assign(admin, o.id(), l1, a2), "license_already_assigned");
        conflict(assign(admin, o.id(), l2, a1), "automation_already_licensed");
        conflict(assign(admin, o.id(), expired, a2), "license_not_valid");
        conflict(assign(admin, o.id(), l2, a3), "license_automation_archived");
        assertThat(assign(admin, o.id(), UUID.randomUUID(), a2).status()).as("licenca inexistente").isEqualTo(404);
        assertThat(assign(admin, o.id(), l2, UUID.randomUUID()).status()).as("automacao inexistente").isEqualTo(404);
        assertThat(admin.post(path(o.id(), "licenses/" + l2 + "/assignment", null), "{}").status()).isEqualTo(400);
        assertThat(admin.post(path(o.id(), "licenses/" + l2 + "/assignment", null), "{\"automationId\":\"nao-e-uuid\"}").status()).isEqualTo(400);
        assertThat(one("SELECT count(*) FROM organization_license_assignments WHERE organization_id = '" + o.id() + "'")).isEqualTo("1");
        assertThat(one("SELECT count(*) FROM organization_license_acts WHERE organization_id = '" + o.id() + "'")).isEqualTo("1");

        // ---- pausar e arquivar a automacao vinculada nao devolve a licenca
        DB.asSuperuser("UPDATE automations SET status = 2 WHERE automation_id = '" + a1 + "'");
        conflict(assign(admin, o.id(), l1, a2), "license_already_assigned");
        DB.asSuperuser("UPDATE automations SET status = 3 WHERE automation_id = '" + a1 + "'");
        conflict(assign(admin, o.id(), l1, a2), "license_already_assigned");
        s = get(admin, summary).body();
        assertThat(List.of(s.get("contracted").asLong(), s.get("used").asLong(), s.get("available").asLong())).as("arquivada continua consumindo")
                .containsExactly(2L, 1L, 1L);

        // ---- isolamento: licenca e automacao de outra organizacao nao existem para esta; a sessao de uma nao escreve na outra
        Org other = newOrganization();
        UUID ow = AccessFixtures.namedWorkspace(other.id(), "Outra", 1);
        UUID oa = ProductFixtures.automation(other.id(), ow, null, "Da outra", other.adminMembership());
        UUID ol = valid(other.id());
        assertThat(assign(admin, o.id(), ol, a2).status()).as("licenca de outra organizacao").isEqualTo(404);
        assertThat(assign(admin, o.id(), l2, oa).status()).as("automacao de outra organizacao").isEqualTo(404);
        assertThat(assign(admin, other.id(), ol, oa).status()).as("sessao desta organizacao na rota da outra").isIn(401, 403, 404);
        assertThat(one("SELECT count(*) FROM organization_license_assignments WHERE organization_id = '" + other.id() + "'")).isEqualTo("0");

        // ---- permissao: so quem administra a organizacao vincula (a confirmacao reforcada nao substitui a permissao)
        TestUser person = internalUser(o, true, 2);
        UUID pm = AccessFixtures.membershipOf(o.id(), person.id());
        AccessFixtures.assignWorkspaceRole(o.id(), pm, w, "WORKSPACE_ADMIN", m);
        BrowserClient member = login(o, person);
        stepUp(member, o.id(), person.id());
        assertThat(assign(member, o.id(), l2, a2).status()).as("administrador de workspace").isEqualTo(403);
        UUID client = newClient(o);
        assertThat(assign(login(o, externalUser(o, client, newWorkspace(o, client))), o.id(), l2, a2).status()).as("Cliente").isEqualTo(403);
        assertThat(assign(browser(), o.id(), l2, a2).status()).as("sem sessao").isIn(401, 403);
        assertThat(one("SELECT count(*) FROM organization_license_assignments WHERE organization_id = '" + o.id() + "'")).isEqualTo("1");

        // ---- o banco reconfere a permissao: o papel da Platform nao vincula em nome de quem nao administra, nem grava outro ato
        try (Connection c = DB.connectAs("nekkora_platform"); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("SELECT set_config('nekkora.organization_id', '" + o.id() + "', true)");
            for (String[] write : new String[][] {
                    {"INSERT INTO public.organization_license_assignments (organization_id, license_id, workspace_id, automation_id, assigned_by_membership_id) "
                            + "VALUES ('" + o.id() + "', '" + l2 + "', '" + w + "', '" + a2 + "', '" + pm + "')", "org.manage"},
                    {"INSERT INTO public.organization_license_assignments (organization_id, license_id, workspace_id, automation_id) VALUES ('" + o.id()
                            + "', '" + l2 + "', '" + w + "', '" + a2 + "')", "identifica quem vinculou"},
                    {"INSERT INTO public.organization_license_acts (organization_id, act, operator_user_id, justification) VALUES ('" + o.id() + "', 6, '"
                            + o.adminId() + "', 'tentativa de ligar a exigencia pela Platform')", "row-level security"},
                    {"INSERT INTO public.organization_license_assignments (organization_id, license_id, workspace_id, automation_id, assigned_by_membership_id) "
                            + "VALUES ('" + other.id() + "', '" + ol + "', '" + ow + "', '" + oa + "', '" + other.adminMembership() + "')", "licenca inexistente nesta organizacao"},
                    {"UPDATE public.organization_license_assignments SET released_at = clock_timestamp(), release_reason = 1 WHERE license_id = '" + l1 + "'",
                            "permission denied"},
                    {"DELETE FROM public.organization_license_assignments WHERE license_id = '" + l1 + "'", "permission denied"}}) {
                st.execute("SAVEPOINT s");
                assertThatThrownBy(() -> {
                    try (Statement x = c.createStatement()) {
                        x.execute(write[0]);
                    }
                }).as(write[0].substring(0, 70)).isInstanceOf(SQLException.class).hasMessageContaining(write[1]);
                st.execute("ROLLBACK TO SAVEPOINT s");
            }
            c.rollback();
        }
    }

    /**
     * Concorrencia PELO CAMINHO REAL DA API: duas sessoes de administradores diferentes disparam o vinculo ao mesmo tempo. Em
     * cada rodada, exatamente uma resposta e 200 e a outra e 409 com o motivo certo; nunca ha dois vinculos nem dois atos.
     */
    @Test
    void duasTentativasSimultaneasPelaApiNuncaProduzemDoisVinculos() throws Exception {
        Org o = newOrganization();
        UUID m = o.adminMembership();
        UUID w = AccessFixtures.namedWorkspace(o.id(), "Operacao", 1);
        TestUser second = internalUser(o, true, 2);
        AccessFixtures.assignOrgRole(o.id(), AccessFixtures.membershipOf(o.id(), second.id()), "ORG_ADMIN", m);
        BrowserClient first = admin(o);
        stepUp(first, o.id(), o.adminId());
        BrowserClient other = login(o, second);
        stepUp(other, o.id(), second.id());

        for (int round = 1; round <= 6; round++) {
            // (a) a MESMA licenca para duas automacoes diferentes
            UUID l = valid(o.id());
            UUID a1 = ProductFixtures.automation(o.id(), w, null, "Rodada " + round + " A", m);
            UUID a2 = ProductFixtures.automation(o.id(), w, null, "Rodada " + round + " B", m);
            List<BrowserClient.Response> r = together(() -> assign(first, o.id(), l, a1), () -> assign(other, o.id(), l, a2));
            assertOneWins(r, "license_already_assigned", "rodada " + round + ": mesma licenca");
            assertThat(one("SELECT count(*) FROM organization_license_assignments WHERE license_id = '" + l + "'")).as("um vinculo so").isEqualTo("1");
            assertThat(one("SELECT count(*) FROM organization_license_acts WHERE license_id = '" + l + "'")).as("um ato so").isEqualTo("1");

            // (b) DUAS licencas para a mesma automacao
            UUID l2 = valid(o.id());
            UUID l3 = valid(o.id());
            UUID a3 = ProductFixtures.automation(o.id(), w, null, "Rodada " + round + " C", m);
            r = together(() -> assign(first, o.id(), l2, a3), () -> assign(other, o.id(), l3, a3));
            assertOneWins(r, "automation_already_licensed", "rodada " + round + ": mesma automacao");
            assertThat(one("SELECT count(*) FROM organization_license_assignments WHERE automation_id = '" + a3 + "'")).isEqualTo("1");
            assertThat(one("SELECT count(*) FROM organization_license_acts WHERE to_automation_id = '" + a3 + "'")).isEqualTo("1");
        }
        // 6 rodadas: 6 + 6 vinculos, 6 licencas sobrando; a conta da API fecha com o banco
        JsonNode s = get(first, path(o.id(), "licenses/summary", null)).body();
        assertThat(List.of(s.get("contracted").asLong(), s.get("used").asLong(), s.get("available").asLong())).containsExactly(18L, 12L, 6L);
        assertThat(one("SELECT count(*) FROM (SELECT license_id FROM organization_license_assignments WHERE organization_id = '" + o.id()
                + "' AND released_at IS NULL GROUP BY license_id HAVING count(*) > 1) d")).as("nenhuma licenca com dois vinculos").isEqualTo("0");
        assertThat(one("SELECT count(*) FROM (SELECT automation_id FROM organization_license_assignments WHERE organization_id = '" + o.id()
                + "' AND released_at IS NULL GROUP BY automation_id HAVING count(*) > 1) d")).as("nenhuma automacao com duas licencas").isEqualTo("0");
    }

    private static void assertOneWins(List<BrowserClient.Response> r, String loserReason, String what) throws Exception {
        List<Integer> status = r.stream().map(BrowserClient.Response::status).sorted().toList();
        assertThat(status).as(what + ": " + r.get(0).body() + " / " + r.get(1).body()).containsExactly(200, 409);
        conflict(r.get(0).status() == 409 ? r.get(0) : r.get(1), loserReason);
    }

    private interface Call {
        BrowserClient.Response run() throws Exception;
    }

    /** Dispara as duas chamadas no mesmo instante, cada uma na sua thread. */
    private static List<BrowserClient.Response> together(Call a, Call b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<BrowserClient.Response>> futures = new ArrayList<>();
            for (Call call : List.of(a, b)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    return call.run();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<BrowserClient.Response> out = new ArrayList<>();
            for (Future<BrowserClient.Response> f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
