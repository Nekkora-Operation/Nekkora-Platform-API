package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.platform.admin.LicenseOperatorService;

import tools.jackson.databind.JsonNode;

/**
 * PLATFORM-LICENCIAMENTO-001 (D5, migration 063) - os atos do operador da Nekkora PELA API REAL: quem pode, conceder, renovar,
 * encerrar, a previa e ligar/desligar a exigencia, com a trilha. E a prova de que NADA disso muda o que a organizacao executa:
 * nenhum fluxo confere licenca nesta etapa.
 *
 * <p>A organizacao interna da Nekkora e a da configuracao desta classe. O operador e incluido no cadastro como o procedimento
 * administrativo faz: direto no banco, pelo migrador (aqui, o superusuario do teste).
 */
class LicenseOperatorIT extends ReadRoutesSupport {

    private static Org nekkora;

    private static synchronized Org nekkora() {
        if (nekkora == null) {
            nekkora = newOrganization();
        }
        return nekkora;
    }

    @DynamicPropertySource
    static void operatorOrganization(DynamicPropertyRegistry registry) {
        registry.add("nekkora.licensing.operator-organization", () -> nekkora().id().toString());
    }

    private static String one(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static String ops(UUID target, String rest) {
        return "/api/v1/o/" + nekkora().id() + "/license-operations/organizations/" + target + rest;
    }

    private void stepUp(BrowserClient n, UUID org, UUID user) {
        assertThat(n.post("/api/v1/o/" + org + "/auth/mfa/step-up", "{\"code\":\"" + code(user) + "\"}").status()).isEqualTo(204);
    }

    private static void conflict(BrowserClient.Response r, String reason) throws Exception {
        assertThat(r.status()).as(r.body()).isEqualTo(409);
        assertThat(JSON.readTree(r.body()).get("reason").asString()).isEqualTo(reason);
    }

    private static List<Long> counts(JsonNode s) {
        return List.of(s.get("contracted").asLong(), s.get("used").asLong(), s.get("available").asLong());
    }

    @Test
    void operadorConcedeRenovaEncerraELigaAExigenciaComPreviaETrilha() throws Exception {
        Org n = nekkora();
        Org client = newOrganization();
        UUID m = client.adminMembership();
        UUID w = AccessFixtures.namedWorkspace(client.id(), "Financeiro", 1);
        UUID a1 = ProductFixtures.automation(client.id(), w, null, "Conciliacao", m);
        ProductFixtures.version(client.id(), w, a1, true, m);
        UUID a2 = ProductFixtures.automation(client.id(), w, null, "Apuracao", m);
        UUID a3 = ProductFixtures.automation(client.id(), w, null, "Sem uso", m);
        String justification = "contrato de ensaio numero 123 assinado";
        String from = OffsetDateTime.now().minusDays(1).toString();
        String until = OffsetDateTime.now().plusDays(29).toString();
        String grant = "{\"count\":2,\"startsAt\":\"" + from + "\",\"endsAt\":\"" + until + "\",\"justification\":\"" + justification + "\"}";

        // ---- quem NAO e operador: administrador da Nekkora fora do cadastro; administrador do cliente; sem sessao
        BrowserClient staff = admin(n);
        stepUp(staff, n.id(), n.adminId());
        assertThat(staff.post(ops(client.id(), "/licenses"), grant).status()).as("administrador da organizacao interna, fora do cadastro").isEqualTo(403);
        assertThat(staff.get(ops(client.id(), "/enforcement-preview")).status()).isEqualTo(403);
        BrowserClient clientAdmin = admin(client);
        stepUp(clientAdmin, client.id(), client.adminId());
        assertThat(clientAdmin.post("/api/v1/o/" + client.id() + "/license-operations/organizations/" + client.id() + "/licenses", grant).status())
                .as("o administrador do cliente nao concede licenca a si mesmo: a rota nao existe fora da organizacao interna").isEqualTo(404);
        assertThat(clientAdmin.post(ops(client.id(), "/licenses"), grant).status()).as("sessao do cliente na rota da organizacao interna").isIn(401, 403, 404);
        assertThat(browser().post(ops(client.id(), "/licenses"), grant).status()).isIn(401, 403);
        assertThat(one("SELECT count(*) FROM organization_licenses WHERE organization_id = '" + client.id() + "'")).isEqualTo("0");

        // ---- inclusao no cadastro (procedimento administrativo) e, a partir dai, os atos pela aplicacao - sem elevacao por ato
        DB.asSuperuser("INSERT INTO license_operators (operator_user_id, organization_id, registered_by) VALUES ('" + n.adminId() + "', '" + n.id()
                + "', 'procedimento de ensaio do teste')");
        assertThat(staff.post(ops(n.id(), "/licenses"), grant).status()).as("a organizacao interna nao e alvo de si mesma").isEqualTo(404);
        assertThat(staff.post(ops(UUID.randomUUID(), "/licenses"), grant).status()).as("organizacao inexistente").isEqualTo(404);
        assertThat(staff.post(ops(client.id(), "/licenses"), grant.replace(justification, "curta")).status()).as("justificativa obrigatoria").isEqualTo(400);

        BrowserClient.Response granted = staff.post(ops(client.id(), "/licenses"), grant);
        assertThat(granted.status()).as(granted.body()).isEqualTo(200);
        JsonNode items = JSON.readTree(granted.body()).get("items");
        assertThat(field(items, "number")).containsExactly("1", "2");
        UUID l1 = UUID.fromString(items.get(0).get("licenseId").asString());
        UUID l2 = UUID.fromString(items.get(1).get("licenseId").asString());
        assertThat(one("SELECT string_agg(act || ':' || operator_user_id || ':' || justification, ';' ORDER BY occurred_at, license_id) FROM "
                + "organization_license_acts WHERE organization_id = '" + client.id() + "'"))
                .as("trilha: um ato 1 por licenca, com o operador e a justificativa").contains("1:" + n.adminId() + ":" + justification);
        assertThat(one("SELECT count(*) FROM organization_license_acts WHERE organization_id = '" + client.id() + "' AND act = 1")).isEqualTo("2");
        assertThat(one("SELECT count(*) FROM organization_license_assignments WHERE organization_id = '" + client.id() + "'"))
                .as("conceder nao vincula: vincular e ato do administrador").isEqualTo("0");
        // o administrador do cliente ve as licencas e o modelo; a organizacao continua no modelo anterior
        JsonNode s = get(clientAdmin, path(client.id(), "licenses/summary", null)).body();
        assertThat(counts(s)).containsExactly(2L, 0L, 2L);
        assertThat(s.get("model").asString()).as("conceder nao liga a exigencia").isEqualTo("legacy");

        // ---- o administrador vincula a licenca 1 (D1); a previa passa a mostrar a automacao como licenciada
        BrowserClient.Response linked = clientAdmin.post(path(client.id(), "licenses/" + l1 + "/assignment", null), "{\"automationId\":\"" + a1 + "\"}");
        assertThat(linked.status()).as(linked.body()).isEqualTo(200);
        // a2 esta em uso (autorizacao vigente numa maquina) e sem licenca; "Sem uso" nao entra em lista nenhuma
        DB.asSuperuser("SET session_replication_role = replica",
                "INSERT INTO local_run_authorizations (organization_id, workspace_id, worker_id, worker_workspace_assignment_id, automation_id, source_kind, "
                        + "package_fingerprint_rule, granted_by_membership_id, granted_at, valid_until) VALUES ('" + client.id() + "', '" + w + "', gen_random_uuid(), gen_random_uuid(), '"
                        + a2 + "', 3, NULL, '" + m + "', clock_timestamp(), clock_timestamp() + interval '90 days')");
        BrowserClient.Response pv = staff.get(ops(client.id(), "/enforcement-preview"));
        assertThat(pv.status()).as(pv.body()).isEqualTo(200);
        JsonNode preview = JSON.readTree(pv.body());
        assertThat(keys(preview)).containsExactly("organizationId", "model", "modelSince", "contracted", "used", "available", "blockedAutomations",
                "licensedAutomations", "offlineAuthorizations", "offlineAuthorizationsValidUntil", "workInProgress", "previewToken", "asOf");
        assertThat(preview.get("licensedAutomations").get(0).get("licenseId").asString()).as("a cobertura diz QUAL licenca").isEqualTo(l1.toString());
        assertThat(field(preview.get("blockedAutomations"), "name")).as("em uso e sem licenca: ficaria impedida de iniciar").containsExactly("Apuracao");
        assertThat(preview.get("blockedAutomations").get(0).get("inUseBecause").get(0).asString()).isEqualTo("validAuthorization");
        assertThat(field(preview.get("licensedAutomations"), "name")).containsExactly("Conciliacao");
        assertThat(preview.get("offlineAuthorizations").asLong()).as("LIMITACAO a vista: autorizacao ja emitida vale sem conexao ate a validade").isEqualTo(1);
        assertThat(preview.get("offlineAuthorizationsValidUntil").isNull()).isFalse();
        assertThat(one("SELECT count(*) FROM organization_license_enforcement WHERE organization_id = '" + client.id() + "'")).as("a previa nao altera").isEqualTo("0");

        // ---- ligar: exige o resumo do CONJUNTO apresentado; conjunto diferente (a previa mudou) e recusado e nada e gravado
        String on = "{\"model\":\"licensed\",\"confirmPreview\":\"%s\",\"justification\":\"" + justification + "\"}";
        assertThat(staff.post(ops(client.id(), "/enforcement"), "{\"model\":\"licensed\",\"justification\":\"" + justification + "\"}").status())
                .as("ligar sem confirmar a previa").isEqualTo(400);
        String shown = preview.get("previewToken").asString();
        conflict(staff.post(ops(client.id(), "/enforcement"), on.formatted("0".repeat(64))), "license_preview_changed");
        // MESMA QUANTIDADE, OUTRO CONJUNTO: a autorizacao de a2 e revogada e "Sem uso" passa a estar em uso. Continua 1 impedida,
        // mas nao e a automacao que o operador viu: exige nova previa
        DB.asSuperuser("SET session_replication_role = replica",
                "UPDATE local_run_authorizations SET revoked_at = clock_timestamp(), revoked_by_membership_id = '" + m + "' WHERE automation_id = '" + a2 + "'",
                "INSERT INTO local_run_authorizations (organization_id, workspace_id, worker_id, worker_workspace_assignment_id, automation_id, source_kind, "
                        + "package_fingerprint_rule, granted_by_membership_id, granted_at, valid_until) VALUES ('" + client.id() + "', '" + w + "', gen_random_uuid(), gen_random_uuid(), '"
                        + a3 + "', 3, NULL, '" + m + "', clock_timestamp(), clock_timestamp() + interval '90 days')");
        JsonNode again = JSON.readTree(staff.get(ops(client.id(), "/enforcement-preview")).body());
        assertThat(field(again.get("blockedAutomations"), "name")).as("a mesma quantidade, outra automacao").containsExactly("Sem uso");
        assertThat(again.get("previewToken").asString()).as("o resumo depende das identidades, nao da quantidade").isNotEqualTo(shown);
        conflict(staff.post(ops(client.id(), "/enforcement"), on.formatted(shown)), "license_preview_changed");
        assertThat(one("SELECT count(*) FROM organization_license_enforcement WHERE organization_id = '" + client.id() + "'")).isEqualTo("0");
        // MESMAS AUTOMACOES, OUTRA COBERTURA: o resumo muda quando a licenca que cobre e outra
        assertThat(LicenseOperatorService.token("legacy", java.util.List.of(), java.util.List.of(java.util.Map.of("automationId", a1, "licenseId", l1))))
                .isNotEqualTo(LicenseOperatorService.token("legacy", java.util.List.of(), java.util.List.of(java.util.Map.of("automationId", a1, "licenseId", l2))));
        shown = again.get("previewToken").asString();
        BrowserClient.Response turned = staff.post(ops(client.id(), "/enforcement"), on.formatted(shown));
        assertThat(turned.status()).as(turned.body()).isEqualTo(200);
        assertThat(one("SELECT act || '|' || (detail->>'blockedAutomations') || '|' || (detail->>'offlineAuthorizations') || '|' || (detail->'blockedAutomationIds'->>0) "
                + "|| '|' || (detail->'licensedAutomationIds'->>0) || '|' || (detail->>'previewToken') FROM organization_license_acts "
                + "WHERE organization_id = '" + client.id() + "' AND act = 6")).as("o retrato da previa, com as identidades, fica na trilha")
                .isEqualTo("6|1|1|" + a3 + "|" + a1 + "|" + shown);
        s = get(clientAdmin, path(client.id(), "licenses/summary", null)).body();
        assertThat(s.get("model").asString()).as("quem esta em qual modelo e um dado visivel").isEqualTo("licensed");
        assertThat(s.get("modelSince").isNull()).isFalse();
        conflict(staff.post(ops(client.id(), "/enforcement"),
                on.formatted(JSON.readTree(staff.get(ops(client.id(), "/enforcement-preview")).body()).get("previewToken").asString())), "license_model_unchanged");

        // ---- NESTA ETAPA o modelo e so um dado: nenhum servico de execucao ou de monitoracao le as tabelas de licenca (conferido
        //      no codigo: so LicenseReadController, LicenseService e LicenseOperatorService as citam). As leituras seguem iguais
        assertThat(get(clientAdmin, path(client.id(), "execution-summary", "from=" + from.replace("+", "%2B") + "&to=" + until.replace("+", "%2B"))).status())
                .as("as leituras de execucao seguem iguais com a exigencia ligada").isEqualTo(200);

        // ---- renovar: acrescenta periodo, preserva o anterior, mantem o vinculo; periodo sobreposto e recusado
        String later = OffsetDateTime.now().plusDays(59).toString();
        conflict(staff.post(ops(client.id(), "/licenses/" + l1 + "/renewal"), "{\"startsAt\":\"" + from + "\",\"endsAt\":\"" + later + "\",\"justification\":\""
                + justification + "\"}"), "license_period_overlaps");
        BrowserClient.Response renewed = staff.post(ops(client.id(), "/licenses/" + l1 + "/renewal"), "{\"endsAt\":\"" + later + "\",\"justification\":\""
                + justification + "\"}");
        assertThat(renewed.status()).as(renewed.body()).isEqualTo(200);
        assertThat(one("SELECT string_agg(period_number || ':' || kind, ',' ORDER BY period_number) FROM organization_license_periods WHERE license_id = '" + l1 + "'"))
                .isEqualTo("1:1,2:2");
        assertThat(one("SELECT automation_id FROM organization_license_assignments WHERE license_id = '" + l1 + "' AND released_at IS NULL"))
                .as("renovar mantem o vinculo").isEqualTo(a1.toString());
        assertThat(one("SELECT count(*) FROM organization_license_acts WHERE license_id = '" + l1 + "' AND act = 2")).isEqualTo("1");

        // ---- encerrar: a licenca sai da conta e o vinculo dela e encerrado, com registro; nao reabre nem renova
        BrowserClient.Response ended = staff.post(ops(client.id(), "/licenses/" + l1 + "/end"), "{\"justification\":\"" + justification + "\"}");
        assertThat(ended.status()).as(ended.body()).isEqualTo(204);
        assertThat(one("SELECT release_reason FROM organization_license_assignments WHERE license_id = '" + l1 + "'")).isEqualTo("2");
        assertThat(one("SELECT act || '|' || from_automation_id FROM organization_license_acts WHERE license_id = '" + l1 + "' AND act = 3"))
                .isEqualTo("3|" + a1);
        conflict(staff.post(ops(client.id(), "/licenses/" + l1 + "/end"), "{\"justification\":\"" + justification + "\"}"), "license_ended");
        conflict(staff.post(ops(client.id(), "/licenses/" + l1 + "/renewal"), "{\"endsAt\":\"" + OffsetDateTime.now().plusDays(90) + "\",\"justification\":\""
                + justification + "\"}"), "license_ended");
        assertThat(counts(get(clientAdmin, path(client.id(), "licenses/summary", null)).body())).containsExactly(1L, 0L, 1L);
        assertThat(staff.post(ops(client.id(), "/licenses/" + UUID.randomUUID() + "/end"), "{\"justification\":\"" + justification + "\"}").status()).isEqualTo(404);
        assertThat(l2).isNotEqualTo(l1);

        // ---- desligar volta ao modelo anterior, tambem com registro
        BrowserClient.Response off = staff.post(ops(client.id(), "/enforcement"), "{\"model\":\"legacy\",\"justification\":\"" + justification + "\"}");
        assertThat(off.status()).as(off.body()).isEqualTo(200);
        assertThat(get(clientAdmin, path(client.id(), "licenses/summary", null)).body().get("model").asString()).isEqualTo("legacy");
        assertThat(one("SELECT string_agg(act::text, ',' ORDER BY occurred_at) FROM organization_license_acts WHERE organization_id = '" + client.id()
                + "' AND act IN (6, 7)")).isEqualTo("6,7");

        // ---- sem confirmacao reforcada recente o ato e recusado; operador revogado deixa de operar
        TestUser secondOperator = internalUser(n, true, 2);
        DB.asSuperuser("INSERT INTO license_operators (operator_user_id, organization_id, registered_by) VALUES ('" + secondOperator.id() + "', '" + n.id()
                + "', 'procedimento de ensaio do teste')");
        BrowserClient fresh = login(n, secondOperator);
        assertThat(fresh.get(ops(client.id(), "/enforcement-preview")).status()).as("operador no cadastro le a previa").isEqualTo(200);
        assertThat(fresh.post(ops(client.id(), "/licenses"), grant).status()).as("no cadastro, mas sem confirmacao reforcada recente").isEqualTo(403);
        stepUp(fresh, n.id(), secondOperator.id());
        assertThat(fresh.post(ops(client.id(), "/licenses"), grant.replace("\"count\":2", "\"count\":1")).status()).as("com a confirmacao, concede").isEqualTo(200);
        DB.asSuperuser("UPDATE license_operators SET revoked_at = clock_timestamp() WHERE operator_user_id = '" + n.adminId() + "'");
        assertThat(staff.post(ops(client.id(), "/licenses"), grant).status()).as("operador revogado").isEqualTo(403);
        assertThat(one("SELECT count(*) FROM organization_licenses WHERE organization_id = '" + client.id() + "'")).as("2 da primeira concessao + 1 do segundo operador; nada depois da revogacao").isEqualTo("3");

        // ---- CONCORRENCIA entre a previa e o ato de ligar: um vinculo EM CURSO (ainda sem confirmar) segura a trava compartilhada
        //      do modelo; ligar espera, refaz a previa DEPOIS de o vinculo confirmar e recusa, porque a cobertura mudou
        String seen = JSON.readTree(fresh.get(ops(client.id(), "/enforcement-preview")).body()).get("previewToken").asString();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (Connection held = DB.connectAsSuperuser(); Statement st = held.createStatement()) {
            held.setAutoCommit(false);
            st.execute("INSERT INTO organization_license_assignments (organization_id, license_id, workspace_id, automation_id, assigned_by_membership_id) VALUES ('"
                    + client.id() + "', '" + l2 + "', '" + w + "', '" + a3 + "', '" + m + "')");
            java.util.concurrent.Future<BrowserClient.Response> turning = pool.submit(() -> fresh.post(ops(client.id(), "/enforcement"), on.formatted(seen)));
            Thread.sleep(1500);
            assertThat(turning.isDone()).as("ligar espera o vinculo em curso terminar").isFalse();
            assertThat(one("SELECT count(*) FROM organization_license_enforcement WHERE organization_id = '" + client.id() + "' AND model = 2")).isEqualTo("0");
            held.commit();
            conflict(turning.get(30, java.util.concurrent.TimeUnit.SECONDS), "license_preview_changed");
        } finally {
            pool.shutdownNow();
        }
        assertThat(one("SELECT model FROM organization_license_enforcement WHERE organization_id = '" + client.id() + "'")).as("nada foi ligado").isEqualTo("1");
        JsonNode after = JSON.readTree(fresh.get(ops(client.id(), "/enforcement-preview")).body());
        assertThat(field(after.get("licensedAutomations"), "name")).as("a previa nova mostra a cobertura que entrou").containsExactly("Sem uso");
        assertThat(after.get("blockedAutomations").size()).isZero();

        // ---- o banco reconfere o operador: o papel da Platform nao concede em nome de quem nao esta no cadastro, nem escreve direto
        try (Connection c = DB.connectAs("nekkora_platform"); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            for (String[] attempt : new String[][] {
                    {"SELECT * FROM public.license_op_grant('" + client.adminId() + "', '" + client.id() + "', 1, clock_timestamp(), clock_timestamp() + interval "
                            + "'30 days', 'tentativa direta sem ser operador')", "OPERATOR_NOT_AUTHORIZED"},
                    {"SELECT public.license_op_require('" + n.adminId() + "', '" + client.id() + "', 'tentativa de usar a funcao interna')", "permission denied"},
                    {"SELECT count(*) FROM public.license_operators", "permission denied"},
                    {"INSERT INTO public.organization_licenses (organization_id, license_number) VALUES ('" + client.id() + "', 9)", "permission denied"},
                    {"INSERT INTO public.organization_license_enforcement (organization_id, model, act_id) VALUES ('" + client.id() + "', 2, gen_random_uuid())",
                            "permission denied"}}) {
                st.execute("SAVEPOINT s");
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
                    try (Statement x = c.createStatement()) {
                        x.execute(attempt[0]);
                    }
                }).as(attempt[0].substring(0, Math.min(60, attempt[0].length()))).hasMessageContaining(attempt[1]);
                st.execute("ROLLBACK TO SAVEPOINT s");
            }
            c.rollback();
        }
    }
}
