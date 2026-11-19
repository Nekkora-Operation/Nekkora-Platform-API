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

    /**
     * 065, D6 - transferencia pelo operador, a pedido de um administrador (atendimento externo). O ato exige o solicitante, a
     * referencia do chamado e a verificacao da identidade; preserva o fim do ciclo; troca o vinculo numa transacao so; e e
     * recusado enquanto a automacao de origem tiver trabalho em andamento.
     */
    @Test
    void transferenciaPeloOperadorComSolicitanteChamadoEVerificacaoSemUsoSimultaneo() throws Exception {
        Org n = nekkora();
        Org client = newOrganization();
        Org stranger = newOrganization();
        UUID m = client.adminMembership();
        UUID w = AccessFixtures.namedWorkspace(client.id(), "Fiscal", 1);
        UUID a1 = ProductFixtures.automation(client.id(), w, null, "Notas de entrada", m);
        UUID v1 = ProductFixtures.version(client.id(), w, a1, true, m);
        UUID a2 = ProductFixtures.automation(client.id(), w, null, "Notas de saida", m);
        UUID a3 = ProductFixtures.automation(client.id(), w, null, "Arquivada", m);
        UUID foreign = ProductFixtures.automation(stranger.id(), AccessFixtures.namedWorkspace(stranger.id(), "Outra", 1), null, "De outra organizacao",
                stranger.adminMembership());
        TestUser operator = internalUser(n, true, 2);
        DB.asSuperuser("INSERT INTO license_operators (operator_user_id, organization_id, registered_by) VALUES ('" + operator.id() + "', '" + n.id()
                + "', 'procedimento de ensaio do teste')");
        BrowserClient staff = login(n, operator);
        stepUp(staff, n.id(), operator.id());
        String justification = "pedido do cliente: a automacao foi substituida";
        BrowserClient.Response granted = staff.post(ops(client.id(), "/licenses"), "{\"count\":2,\"startsAt\":\"" + OffsetDateTime.now().minusDays(1)
                + "\",\"endsAt\":\"" + OffsetDateTime.now().plusDays(29) + "\",\"justification\":\"" + justification + "\"}");
        assertThat(granted.status()).as(granted.body()).isEqualTo(200);
        UUID l1 = UUID.fromString(JSON.readTree(granted.body()).get("items").get(0).get("licenseId").asString());
        UUID l2 = UUID.fromString(JSON.readTree(granted.body()).get("items").get(1).get("licenseId").asString());
        BrowserClient clientAdmin = admin(client);
        stepUp(clientAdmin, client.id(), client.adminId());
        String transfer = ops(client.id(), "/licenses/" + l1 + "/transfer");
        String body = "{\"toAutomationId\":\"%s\",\"requesterEmail\":\"%s\",\"ticketReference\":\"CH-2026-0042\",\"identityVerification\":"
                + "\"retorno por telefone ao numero do cadastro\",\"justification\":\"" + justification + "\"}";

        // ---- licenca sem vinculo: nao ha de onde transferir
        conflict(staff.post(transfer, body.formatted(a2, client.adminEmail())), "license_not_assigned");
        assertThat(clientAdmin.post(path(client.id(), "licenses/" + l1 + "/assignment", null), "{\"automationId\":\"" + a1 + "\"}").status()).isEqualTo(200);
        String cycle = one("SELECT string_agg(period_number || ':' || starts_at || ':' || ends_at, ',' ORDER BY period_number) FROM organization_license_periods "
                + "WHERE license_id = '" + l1 + "'");

        // ---- quem nao transfere: o administrador do cliente (nem por rota propria), e o ato sem os dados do pedido
        assertThat(clientAdmin.post(transfer, body.formatted(a2, client.adminEmail())).status()).isIn(401, 403, 404);
        assertThat(clientAdmin.post("/api/v1/o/" + client.id() + "/licenses/" + l1 + "/transfer", body.formatted(a2, client.adminEmail())).status())
                .as("o administrador nao tem rota de transferencia: so o suporte").isIn(404, 405);
        assertThat(staff.post(transfer, body.formatted(a2, client.adminEmail()).replace("CH-2026-0042", "")).status()).as("sem referencia do chamado").isEqualTo(400);
        assertThat(staff.post(transfer, body.formatted(a2, client.adminEmail()).replace("retorno por telefone ao numero do cadastro", "ok")).status())
                .as("sem verificacao da identidade").isEqualTo(400);
        assertThat(staff.post(transfer, "{\"toAutomationId\":\"" + a2 + "\",\"ticketReference\":\"CH-2026-0042\",\"identityVerification\":"
                + "\"retorno por telefone ao numero do cadastro\",\"justification\":\"" + justification + "\"}").status()).as("sem solicitante").isEqualTo(400);
        // o solicitante tem de administrar a organizacao AGORA: leitor, pessoa de outra organizacao e e-mail inexistente nao servem
        TestUser reader = internalUser(client, true, 2);
        conflict(staff.post(transfer, body.formatted(a2, reader.email())), "license_transfer_requester_not_admin");
        conflict(staff.post(transfer, body.formatted(a2, stranger.adminEmail())), "license_transfer_requester_not_admin");
        conflict(staff.post(transfer, body.formatted(a2, "ninguem@exemplo.test")), "license_transfer_requester_not_admin");

        // ---- destino invalido: a propria origem, automacao de outra organizacao, arquivada, ja licenciada
        conflict(staff.post(transfer, body.formatted(a1, client.adminEmail())), "license_transfer_same_automation");
        assertThat(staff.post(transfer, body.formatted(foreign, client.adminEmail())).status()).as("automacao de outra organizacao nao existe aqui").isEqualTo(404);
        DB.asSuperuser("SET session_replication_role = replica", "UPDATE automations SET status = 3 WHERE automation_id = '" + a3 + "'");
        conflict(staff.post(transfer, body.formatted(a3, client.adminEmail())), "license_automation_archived");
        assertThat(clientAdmin.post(path(client.id(), "licenses/" + l2 + "/assignment", null), "{\"automationId\":\"" + a2 + "\"}").status()).isEqualTo(200);
        conflict(staff.post(transfer, body.formatted(a2, client.adminEmail())), "automation_already_licensed");
        // (a licenca 2 e encerrada para liberar a2 como destino)
        assertThat(staff.post(ops(client.id(), "/licenses/" + l2 + "/end"), "{\"justification\":\"" + justification + "\"}").status()).isEqualTo(204);

        // ---- trabalho em andamento na ORIGEM: recusada ate terminar ou ter o cancelamento confirmado
        for (int code = 1; code <= 5; code++) {
            DB.asSuperuser("INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, "
                    + "limit_value, provisioned_by_user_id) VALUES ('" + client.id() + "', " + code + ", 1, now(), 2, NULL, '" + client.adminId() + "')");
        }
        UUID running = ProductFixtures.execution(client.id(), w, a1, v1, null, m);
        conflict(staff.post(transfer, body.formatted(a2, client.adminEmail())), "license_transfer_work_in_progress");
        assertThat(one("SELECT automation_id FROM organization_license_assignments WHERE license_id = '" + l1 + "' AND released_at IS NULL"))
                .as("nada mudou").isEqualTo(a1.toString());
        DB.asSuperuser("SET session_replication_role = replica", "UPDATE executions SET status = 6, status_reason = 4, finished_at = clock_timestamp() WHERE execution_id = '" + running + "'");

        // ---- os OUTROS fluxos da origem. Massa de ESTADOS gravada direto (sem gatilhos nem chaves: os caminhos que criam cada
        //      registro tem os proprios testes); o que se confere aqui e a regra da transferencia sobre cada estado, pela rota real
        String o = client.id().toString();
        String busy = "SELECT public.license_work_in_progress('" + o + "', '" + a1 + "')";
        String again = body.formatted(a2, client.adminEmail());
        assertThat(one(busy)).as("execucao cancelada: nada em andamento").isEqualTo("f");
        // (1) sessao do Agent anterior: pronta, iniciando ou em execucao seguram; terminada, nao
        UUID run = UUID.randomUUID();
        mass("INSERT INTO agent_runs (agent_run_id, organization_id, workspace_id, automation_id, automation_version_id, authorization_id, agent_id, worker_id, "
                + "worker_agent_assignment_id, agent_execution_id, package_fingerprint, authorization_state_at_registration, supervisor_state) VALUES ('" + run
                + "', '" + o + "', '" + w + "', '" + a1 + "', '" + v1 + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'ex-"
                + "1a".repeat(16) + "', repeat('a', 64), 1, 1)");
        for (int state : new int[] {1, 2, 3}) {
            mass("UPDATE agent_runs SET supervisor_state = " + state + " WHERE agent_run_id = '" + run + "'");
            conflict(staff.post(transfer, again), "license_transfer_work_in_progress");
        }
        mass("UPDATE agent_runs SET supervisor_state = 4 WHERE agent_run_id = '" + run + "'");
        assertThat(one(busy)).as("sessao do Agent anterior terminada").isEqualTo("f");
        // (2) pedido ao Agent anterior: pedido, entregue (no prazo) e aceito seguram; o aceito segura mesmo depois do prazo
        //     (a execucao pode nascer a qualquer momento); vencido sem decisao e recusado, nao
        UUID request = UUID.randomUUID();
        mass("INSERT INTO run_requests (run_request_id, organization_id, workspace_id, automation_id, automation_version_id, authorization_id, worker_id, "
                + "agent_id, source_kind, package_fingerprint, package_fingerprint_rule, request_key, requested_by_membership_id, valid_until) VALUES ('" + request
                + "', '" + o + "', '" + w + "', '" + a1 + "', '" + v1 + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 1, repeat('a', 64), "
                + "'nekkora-agent/package-fingerprint/1', gen_random_uuid(), '" + m + "', clock_timestamp() + interval '10 minutes')");
        conflict(staff.post(transfer, again), "license_transfer_work_in_progress");
        mass("UPDATE run_requests SET state = 2, delivered_at = clock_timestamp(), deliveries = 1 WHERE run_request_id = '" + request + "'");
        conflict(staff.post(transfer, again), "license_transfer_work_in_progress");
        mass("UPDATE run_requests SET requested_at = clock_timestamp() - interval '20 minutes', valid_until = clock_timestamp() - interval '5 minutes' "
                + "WHERE run_request_id = '" + request + "'");
        assertThat(one(busy)).as("pedido vencido sem decisao: a maquina ja nao pode aceita-lo").isEqualTo("f");
        mass("UPDATE run_requests SET state = 3, decided_at = clock_timestamp(), agent_execution_id = 'ex-" + "2b".repeat(16) + "' WHERE run_request_id = '"
                + request + "'");
        conflict(staff.post(transfer, again), "license_transfer_work_in_progress");
        mass("UPDATE run_requests SET state = 4, decision_reason = 'BUSY', agent_execution_id = NULL WHERE run_request_id = '" + request + "'");
        assertThat(one(busy)).as("pedido recusado pela maquina").isEqualTo("f");
        // (3) tentativa remota: na fila, oferecida, recebida ate em execucao seguram. A oferta VENCIDA que o servidor ainda nao
        //     encerrou tambem: a maquina pode confirmar o recebimento ate la
        UUID remote = UUID.randomUUID();
        UUID t1 = UUID.randomUUID();
        mass("INSERT INTO remote_executions (remote_execution_id, organization_id, workspace_id, automation_id, automation_version_id, package_fingerprint, "
                        + "package_fingerprint_rule, requirements_revision, effective_dependencies, request_key, requested_by_membership_id) VALUES ('" + remote
                        + "', '" + o + "', '" + w + "', '" + a1 + "', '" + v1 + "', repeat('a', 64), 'nekkora-agent/external-python-fingerprint/1', 1, "
                        + "'[]'::jsonb, gen_random_uuid(), '" + m + "')",
                attempt(t1, o, remote, 1, m));
        conflict(staff.post(transfer, again), "license_transfer_work_in_progress");
        mass("UPDATE remote_attempts SET created_at = clock_timestamp() - interval '2 hours', offer_valid_until = clock_timestamp() - interval '1 hour', "
                + "state = 2, token_hash = repeat('a', 64) WHERE attempt_id = '" + t1 + "'");
        conflict(staff.post(transfer, again), "license_transfer_work_in_progress");
        mass("UPDATE remote_attempts SET state = 12, ended_at = clock_timestamp() WHERE attempt_id = '" + t1 + "'");
        assertThat(one(busy)).as("oferta encerrada pelo servidor como vencida").isEqualTo("f");
        UUID t2 = UUID.randomUUID();
        mass(attempt(t2, o, remote, 2, m));
        for (int state : new int[] {3, 4, 5, 6}) {
            mass("UPDATE remote_attempts SET state = " + state + ", token_hash = repeat('a', 64) WHERE attempt_id = '" + t2 + "'");
            conflict(staff.post(transfer, again), "license_transfer_work_in_progress");
        }
        // (4) OCUPACAO INCERTA: terminou sem confirmacao (13). Nem o tempo, nem "ainda executa" liberam; a declaracao de um
        //     administrador (ou a confirmacao da maquina) libera
        mass("UPDATE remote_attempts SET state = 13, closure_cause = 'COMMUNICATION_LOST', ended_at = clock_timestamp() - interval '30 days' WHERE attempt_id = '"
                + t2 + "'");
        conflict(staff.post(transfer, again), "license_transfer_work_in_progress");
        mass(report(t2, o, 3, null));
        conflict(staff.post(transfer, again), "license_transfer_work_in_progress");
        mass(report(t2, o, 2, m));
        assertThat(one(busy)).as("declaracao do administrador: nao ha processo").isEqualTo("f");
        //     tentativa CONCLUIDA com processo ainda vivo (4) ou sem como saber (5): segura ate a maquina confirmar (1)
        UUID t3 = UUID.randomUUID();
        mass(attempt(t3, o, remote, 3, m),
                "UPDATE remote_attempts SET state = 7, token_hash = repeat('a', 64), exit_code = 0, ended_at = clock_timestamp() WHERE attempt_id = '" + t3 + "'");
        assertThat(one(busy)).as("concluida, sem fato pendente").isEqualTo("f");
        mass(report(t3, o, 4, null));
        conflict(staff.post(transfer, again), "license_transfer_work_in_progress");
        mass(report(t3, o, 5, null));
        conflict(staff.post(transfer, again), "license_transfer_work_in_progress");
        mass(report(t3, o, 1, null));
        assertThat(one(busy)).as("a maquina confirmou: nenhum processo da tentativa").isEqualTo("f");
        assertThat(one("SELECT automation_id FROM organization_license_assignments WHERE license_id = '" + l1 + "' AND released_at IS NULL"))
                .as("nenhuma recusa mudou o vinculo").isEqualTo(a1.toString());
        // (5) CONCORRENCIA com um inicio que SE CONFIRMA: o pedido novo ja passou pela conferencia de licenca e ainda nao
        //     confirmou; a transferencia espera e, quando anda, encontra o trabalho e e recusada
        UUID racing = UUID.randomUUID();
        java.util.concurrent.ExecutorService starting = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (Connection held = DB.connectAsSuperuser(); Statement st = held.createStatement()) {
            held.setAutoCommit(false);
            st.execute("SELECT public.license_gate('" + o + "', '" + a1 + "')");
            st.execute("SET LOCAL session_replication_role = replica");
            st.execute("INSERT INTO run_requests (run_request_id, organization_id, workspace_id, automation_id, automation_version_id, authorization_id, worker_id, "
                    + "agent_id, source_kind, package_fingerprint, package_fingerprint_rule, request_key, requested_by_membership_id, valid_until) VALUES ('"
                    + racing + "', '" + o + "', '" + w + "', '" + a1 + "', '" + v1 + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 1, "
                    + "repeat('a', 64), 'nekkora-agent/package-fingerprint/1', gen_random_uuid(), '" + m + "', clock_timestamp() + interval '10 minutes')");
            java.util.concurrent.Future<BrowserClient.Response> waiting = starting.submit(() -> staff.post(transfer, again));
            Thread.sleep(1500);
            assertThat(waiting.isDone()).as("a transferencia espera o inicio em curso").isFalse();
            held.commit();
            conflict(waiting.get(30, java.util.concurrent.TimeUnit.SECONDS), "license_transfer_work_in_progress");
        } finally {
            starting.shutdownNow();
        }
        mass("UPDATE run_requests SET state = 4, delivered_at = clock_timestamp(), decided_at = clock_timestamp(), decision_reason = 'BUSY' "
                + "WHERE run_request_id = '" + racing + "'");
        assertThat(one(busy)).isEqualTo("f");

        // ---- CONCORRENCIA: um inicio de trabalho em curso na origem (a conferencia de licenca ja passou, a transacao ainda nao
        //      confirmou) segura a transferencia; ela so anda depois, e reconfere
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (Connection held = DB.connectAs("nekkora_platform"); Statement st = held.createStatement()) {
            held.setAutoCommit(false);
            st.execute("SELECT set_config('nekkora.organization_id', '" + client.id() + "', true)");
            st.execute("SELECT public.license_gate('" + client.id() + "', '" + a1 + "')");
            java.util.concurrent.Future<BrowserClient.Response> moving = pool.submit(() -> staff.post(transfer, body.formatted(a2, client.adminEmail())));
            Thread.sleep(1500);
            assertThat(moving.isDone()).as("a transferencia espera o inicio de trabalho em curso").isFalse();
            assertThat(one("SELECT count(*) FROM organization_license_assignments WHERE license_id = '" + l1 + "'")).isEqualTo("1");
            held.rollback();
            BrowserClient.Response moved = moving.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(moved.status()).as(moved.body()).isEqualTo(200);
            JsonNode r = JSON.readTree(moved.body());
            assertThat(r.get("fromAutomationId").asString()).isEqualTo(a1.toString());
            assertThat(r.get("toAutomationId").asString()).isEqualTo(a2.toString());
        } finally {
            pool.shutdownNow();
        }

        // ---- o resultado: um vinculo encerrado como TRANSFERIDA e um aberto no destino; nunca os dois abertos; o ciclo e o mesmo
        assertThat(one("SELECT string_agg(automation_id || ':' || coalesce(release_reason::text, 'aberto'), ',' ORDER BY assigned_at) FROM "
                + "organization_license_assignments WHERE license_id = '" + l1 + "'")).isEqualTo(a1 + ":1," + a2 + ":aberto");
        assertThat(one("SELECT (SELECT released_at FROM organization_license_assignments WHERE license_id = '" + l1 + "' AND automation_id = '" + a1
                + "') <= (SELECT assigned_at FROM organization_license_assignments WHERE license_id = '" + l1 + "' AND automation_id = '" + a2 + "')"))
                .as("sem instante com as duas automacoes atendidas").isEqualTo("t");
        assertThat(one("SELECT string_agg(period_number || ':' || starts_at || ':' || ends_at, ',' ORDER BY period_number) FROM organization_license_periods "
                + "WHERE license_id = '" + l1 + "'")).as("o fim do ciclo e preservado").isEqualTo(cycle);
        assertThat(one("SELECT from_automation_id || '|' || to_automation_id || '|' || requester_membership_id || '|' || operator_user_id || '|' || justification "
                + "|| '|' || (detail->>'ticket') || '|' || (detail->>'identityCheck') || '|' || (occurred_at IS NOT NULL) FROM organization_license_acts "
                + "WHERE license_id = '" + l1 + "' AND act = 5"))
                .as("auditoria: origem, destino, solicitante, responsavel, justificativa, chamado, verificacao e data")
                .isEqualTo(a1 + "|" + a2 + "|" + m + "|" + operator.id() + "|" + justification + "|CH-2026-0042|retorno por telefone ao numero do cadastro|true");
        assertThat(counts(get(clientAdmin, path(client.id(), "licenses/summary", null)).body())).as("a conta nao muda: 1 contratada, 1 utilizada")
                .containsExactly(1L, 1L, 0L);
        // a origem deixou de ter direito; o destino passou a ter (a organizacao e ligada ao modelo por licenca para conferir)
        LicenseFixtures.enforce(client.id(), n.adminId());
        assertThat(one("SELECT public.license_state('" + client.id() + "', '" + a1 + "') || '|' || public.license_state('" + client.id() + "', '" + a2 + "')"))
                .isEqualTo("LICENSE_REQUIRED|OK");

        // ---- o banco reconfere: o papel do servico nao transfere em nome de quem nao e operador
        try (Connection c = DB.connectAs("nekkora_platform"); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> st.execute("SELECT public.license_op_transfer('" + client.adminId() + "', '" + client.id()
                    + "', '" + l1 + "', '" + a1 + "', '" + m + "', 'CH-1', 'verificacao inventada pelo teste', 'tentativa direta sem ser operador')"))
                    .hasMessageContaining("OPERATOR_NOT_AUTHORIZED");
            c.rollback();
        }
    }

    // ------------------------------------------------------------------ 068

    private TestUser operator(Org n) {
        TestUser operator = internalUser(n, true, 2);
        DB.asSuperuser("INSERT INTO license_operators (operator_user_id, organization_id, registered_by) VALUES ('" + operator.id() + "', '" + n.id()
                + "', 'procedimento de ensaio do teste')");
        return operator;
    }

    private static String agentRun(UUID run, String org, UUID workspace, UUID automation, UUID version, int state, String lastReport, int n) {
        return "INSERT INTO agent_runs (agent_run_id, organization_id, workspace_id, automation_id, automation_version_id, authorization_id, agent_id, worker_id, "
                + "worker_agent_assignment_id, agent_execution_id, package_fingerprint, authorization_state_at_registration, supervisor_state, last_report_at) "
                + "VALUES ('" + run + "', '" + org + "', '" + workspace + "', '" + automation + "', '" + version + "', gen_random_uuid(), gen_random_uuid(), "
                + "gen_random_uuid(), gen_random_uuid(), 'ex-" + ("" + n).repeat(32) + "', repeat('a', 64), 1, " + state + ", " + lastReport + ")";
    }

    /**
     * 068: o RESULTADO DESCONHECIDO do Agent anterior nao comprova o termino e segura a transferencia ate a reconciliacao pelo
     * suporte. A reconciliacao registra operador, justificativa e verificacao, NAO altera a sessao (desconhecido continua
     * desconhecido) e deixa de valer se a maquina informar depois. A sessao sem encerramento nao perde a vaga pelo tempo: so
     * pelo ato, e so depois de uma hora sem noticia.
     */
    @Test
    void resultadoDesconhecidoDoAgentAnteriorSeguraATransferenciaAteAReconciliacaoPeloSuporte() throws Exception {
        Org n = nekkora();
        Org client = newOrganization();
        Org other = newOrganization();
        UUID m = client.adminMembership();
        UUID w = AccessFixtures.namedWorkspace(client.id(), "Fiscal", 1);
        UUID a1 = ProductFixtures.automation(client.id(), w, null, "Notas de entrada", m);
        UUID v1 = ProductFixtures.version(client.id(), w, a1, true, m);
        UUID a2 = ProductFixtures.automation(client.id(), w, null, "Notas de saida", m);
        TestUser operator = operator(n);
        BrowserClient staff = login(n, operator);
        stepUp(staff, n.id(), operator.id());
        String justification = "chamado CH-2026-0077: maquina substituida pelo cliente";
        BrowserClient.Response granted = staff.post(ops(client.id(), "/licenses"), "{\"count\":1,\"startsAt\":\"" + OffsetDateTime.now().minusDays(1)
                + "\",\"endsAt\":\"" + OffsetDateTime.now().plusDays(29) + "\",\"justification\":\"" + justification + "\"}");
        UUID l1 = UUID.fromString(JSON.readTree(granted.body()).get("items").get(0).get("licenseId").asString());
        BrowserClient clientAdmin = admin(client);
        stepUp(clientAdmin, client.id(), client.adminId());
        assertThat(clientAdmin.post(path(client.id(), "licenses/" + l1 + "/assignment", null), "{\"automationId\":\"" + a1 + "\"}").status()).isEqualTo(200);
        String o = client.id().toString();
        String transfer = ops(client.id(), "/licenses/" + l1 + "/transfer");
        String body = "{\"toAutomationId\":\"" + a2 + "\",\"requesterEmail\":\"" + client.adminEmail() + "\",\"ticketReference\":\"CH-2026-0077\","
                + "\"identityVerification\":\"retorno por telefone ao numero do cadastro\",\"justification\":\"" + justification + "\"}";
        String busy = "SELECT public.license_work_in_progress('" + o + "', '" + a1 + "')";
        String slots = "SELECT concurrent_previous_agent || '+' || concurrent_previous_agent_uncertain FROM public.capacity_state('" + o + "')";

        // ---- duas sessoes: uma com RESULTADO DESCONHECIDO (9), outra EM EXECUCAO (3) sem noticia ha tres horas
        UUID unknown = UUID.randomUUID();
        UUID stale = UUID.randomUUID();
        mass(agentRun(unknown, o, w, a1, v1, 9, "clock_timestamp() - interval '2 days'", 1),
                agentRun(stale, o, w, a1, v1, 3, "clock_timestamp() - interval '3 hours'", 2));
        assertThat(one(busy)).isEqualTo("t");
        assertThat(one(slots)).as("uma execucao informada ativa + uma RESERVA por resultado desconhecido (que nao comprova o termino)")
                .isEqualTo("1+1");
        assertThat(one("SELECT concurrent_used FROM public.capacity_state('" + o + "')")).as("as duas ocupam vaga").isEqualTo("2");
        // a leitura da organizacao mostra as duas coisas SEPARADAS: execucoes ativas e reservas por situacao incerta
        LicenseFixtures.enforce(client.id(), client.adminId());
        JsonNode cap = JSON.readTree(clientAdmin.get(path(client.id(), "capacity", null)).body()).get("concurrentExecutions");
        assertThat(cap.get("active").asLong() + "|" + cap.get("reservedUncertain").asLong() + "|" + cap.get("used").asLong()).isEqualTo("1|1|2");
        assertThat(cap.get("breakdown").get("previousAgentUnconfirmed").asLong()).isEqualTo(1);
        conflict(staff.post(transfer, body), "license_transfer_work_in_progress");

        // ---- a lista do operador: so identificador, automacao, estado e instantes
        String sessions = ops(client.id(), "/previous-agent-sessions");
        JsonNode list = JSON.readTree(staff.get(sessions).body()).get("items");
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(0).get("sessionId").asString()).isEqualTo(unknown.toString());
        assertThat(list.get(0).get("state").asString()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(list.get(0).get("automationName").asString()).isEqualTo("Notas de entrada");
        assertThat(list.get(1).get("state").asString()).isEqualTo("RUNNING");
        assertThat(list.get(1).get("previouslyReconciledAt").isNull()).isTrue();
        assertThat(clientAdmin.get(sessions).status()).as("o administrador do cliente nao usa as rotas do operador").isIn(401, 403, 404);

        // ---- reconciliar: exige justificativa E a verificacao realizada; sessao de outra organizacao nao existe aqui
        String reconcile = sessions + "/" + unknown + "/reconciliation";
        String ok = "{\"justification\":\"" + justification + "\",\"verification\":\"cliente confirmou por escrito que o computador foi formatado\"}";
        assertThat(staff.post(reconcile, "{\"justification\":\"" + justification + "\"}").status()).as("sem verificacao").isEqualTo(400);
        assertThat(staff.post(reconcile, "{\"justification\":\"" + justification + "\",\"verification\":\"ok\"}").status()).isEqualTo(400);
        assertThat(staff.post(reconcile, "{\"verification\":\"cliente confirmou por escrito que o computador foi formatado\"}").status()).isEqualTo(400);
        assertThat(clientAdmin.post(reconcile, ok).status()).isIn(401, 403, 404);
        assertThat(staff.post(ops(other.id(), "/previous-agent-sessions/" + unknown + "/reconciliation"), ok).status()).isEqualTo(404);
        assertThat(one("SELECT count(*) FROM agent_run_reconciliations")).as("nada gravado pelas recusas").isEqualTo(
                one("SELECT count(*) FROM agent_run_reconciliations WHERE organization_id <> '" + o + "'"));

        BrowserClient.Response done = staff.post(reconcile, ok);
        assertThat(done.status()).as(done.body()).isEqualTo(200);
        assertThat(one("SELECT supervisor_state || '|' || (agent_ended_at IS NULL) FROM agent_runs WHERE agent_run_id = '" + unknown + "'"))
                .as("a sessao NAO muda: continua resultado desconhecido, sem desfecho nem fim gravados").isEqualTo("9|true");
        assertThat(one("SELECT state_at || '|' || operator_user_id || '|' || justification || '|' || verification FROM agent_run_reconciliations "
                + "WHERE agent_run_id = '" + unknown + "'")).isEqualTo("9|" + operator.id() + "|" + justification
                        + "|cliente confirmou por escrito que o computador foi formatado");
        conflict(staff.post(reconcile, ok), "previous_agent_session_already_reconciled");
        assertThat(one(slots)).as("reconciliada: a reserva por resultado desconhecido sai; a sessao ativa continua").isEqualTo("1+0");
        // a outra sessao (em execucao, sem encerramento) continua segurando a transferencia e a vaga
        assertThat(one(busy)).isEqualTo("t");
        conflict(staff.post(transfer, body), "license_transfer_work_in_progress");

        // ---- sessao sem encerramento: o TEMPO nao libera a vaga; o ATO libera, e nao ha espera obrigatoria (o que autoriza e a
        //      verificacao registrada: o processo terminou, ou a maquina esta fora de operacao)
        String reconcileStale = sessions + "/" + stale + "/reconciliation";
        mass("UPDATE agent_runs SET last_report_at = clock_timestamp() - interval '10 days' WHERE agent_run_id = '" + stale + "'");
        assertThat(one(slots)).as("dez dias sem noticia: a vaga continua ocupada").isEqualTo("1+0");
        mass("UPDATE agent_runs SET last_report_at = clock_timestamp() - interval '5 minutes' WHERE agent_run_id = '" + stale + "'");
        assertThat(staff.post(reconcileStale, "{\"justification\":\"" + justification + "\"}").status()).as("sem verificacao, nem com noticia antiga nem recente")
                .isEqualTo(400);
        BrowserClient.Response early = staff.post(reconcileStale, ok);
        assertThat(early.status()).as("noticia ha cinco minutos nao impede: " + early.body()).isEqualTo(200);
        assertThat(one("SELECT supervisor_state FROM agent_runs WHERE agent_run_id = '" + stale + "'")).as("continua em execucao para a Platform").isEqualTo("3");
        assertThat(one(slots)).as("reconciliada: deixa de ocupar a vaga").isEqualTo("0+0");
        assertThat(one(busy)).isEqualTo("f");
        assertThat(JSON.readTree(staff.get(sessions).body()).get("items").size()).isZero();

        // ---- RELATO POSTERIOR, tratado pelo conteudo. (a) a maquina informa RESULTADO DESCONHECIDO depois da declaracao: nao diz
        //      que ha execucao ativa, e NAO desfaz a declaracao
        mass("UPDATE agent_runs SET supervisor_state = 9, last_report_at = clock_timestamp() + interval '1 second' WHERE agent_run_id = '" + stale + "'");
        assertThat(one(slots)).as("relato tardio sem execucao ativa: a declaracao continua valendo").isEqualTo("0+0");
        assertThat(one(busy)).isEqualTo("f");
        conflict(staff.post(reconcileStale, ok), "previous_agent_session_already_reconciled");
        // (b) a maquina informa execucao AINDA ATIVA depois da declaracao: a ocupacao volta, e a declaracao anterior fica no historico
        mass("UPDATE agent_runs SET supervisor_state = 3, last_report_at = clock_timestamp() + interval '2 seconds' WHERE agent_run_id = '" + stale + "'");
        assertThat(one(slots)).as("a sessao esta viva: volta a ocupar a vaga").isEqualTo("1+0");
        assertThat(one(busy)).isEqualTo("t");
        conflict(staff.post(transfer, body), "license_transfer_work_in_progress");
        JsonNode back = JSON.readTree(staff.get(sessions).body()).get("items");
        assertThat(back.size()).isEqualTo(1);
        assertThat(back.get(0).get("previouslyReconciledAt").isNull()).as("a lista diz que ja houve declaracao antes do relato de execucao ativa").isFalse();
        assertThat(one("SELECT count(*) FROM agent_run_reconciliations WHERE agent_run_id = '" + stale + "'")).as("a declaracao anterior permanece").isEqualTo("1");
        // (c) a maquina informa o DESFECHO: resolve a duvida por si; nao ha o que reconciliar
        mass("UPDATE agent_runs SET supervisor_state = 4 WHERE agent_run_id = '" + stale + "'");
        conflict(staff.post(reconcileStale, ok), "previous_agent_session_has_outcome");
        assertThat(one(busy)).isEqualTo("f");
        assertThat(one(slots)).isEqualTo("0+0");

        // ---- agora a transferencia passa
        BrowserClient.Response moved = staff.post(transfer, body);
        assertThat(moved.status()).as(moved.body()).isEqualTo(200);
        // so de acrescimo: a reconciliacao nao e alterada nem apagada
        assertThat(one("SELECT count(*) FROM agent_run_reconciliations WHERE organization_id = '" + o + "'")).isEqualTo("2");
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> st.execute("DELETE FROM agent_run_reconciliations WHERE organization_id = '" + o + "'"))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    /**
     * 068: criacao excepcional. O operador indica a pessoa responsavel; ela recebe a administracao; o operador nao recebe acesso;
     * responsavel, operador e justificativa ficam gravados. Membro pode ser indicado e os limites de criacao nao seguram a
     * excecao; o Cliente continua de fora.
     */
    @Test
    void criacaoExcepcionalPeloOperadorComResponsavelIndicado() throws Exception {
        Org n = nekkora();
        Org existing = newOrganization();
        TestUser operator = operator(n);
        BrowserClient staff = login(n, operator);
        String route = "/api/v1/o/" + n.id() + "/license-operations/organizations";
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String email = "responsavel-" + tag + "@exemplo.test";
        UUID identity = UUID.randomUUID();
        // a pessoa indicada: identidade ativa com e-mail verificado, MEMBRO (sem papel de administrador) de uma organizacao
        TestUser memberAccount = internalUser(existing, true, 2);
        mass("INSERT INTO identities (identity_id, email, display_name, email_verified_at, status) VALUES ('" + identity + "', '" + email
                        + "', 'Pessoa Responsavel', now(), 2)",
                "INSERT INTO identity_users (identity_id, user_id, link_channel) VALUES ('" + identity + "', '" + memberAccount.id() + "', 'convite')");
        String justification = "contrato novo: matriz pediu organizacao separada para a filial";
        String body = "{\"responsibleEmail\":\"%s\",\"name\":\"%s\",\"legalName\":\"%s\",\"justification\":\"" + justification + "\"}";

        // ---- quem nao cria por excecao: sem confirmacao reforcada; o administrador de um cliente; sem justificativa; sem responsavel
        assertThat(staff.post(route, body.formatted(email, "Excecao " + tag, "Excecao " + tag + " Ltda")).status()).as("sem confirmacao reforcada").isEqualTo(403);
        stepUp(staff, n.id(), operator.id());
        BrowserClient clientAdmin = admin(existing);
        stepUp(clientAdmin, existing.id(), existing.adminId());
        assertThat(clientAdmin.post(route, body.formatted(email, "Excecao " + tag, "Excecao " + tag + " Ltda")).status()).isIn(401, 403, 404);
        assertThat(clientAdmin.post("/api/v1/o/" + existing.id() + "/license-operations/organizations", body.formatted(email, "Excecao " + tag, "Excecao Ltda"))
                .status()).as("fora da organizacao interna a rota nao existe").isEqualTo(404);
        assertThat(staff.post(route, body.formatted(email, "Excecao " + tag, "Excecao Ltda").replace(justification, "curta")).status()).isEqualTo(400);
        assertThat(staff.post(route, "{\"name\":\"Excecao\",\"legalName\":\"Excecao Ltda\",\"justification\":\"" + justification + "\"}").status())
                .as("o responsavel e sempre indicado explicitamente").isEqualTo(400);
        conflict(staff.post(route, body.formatted("ninguem-" + tag + "@exemplo.test", "Excecao " + tag, "Excecao Ltda")), "organization_responsible_not_found");
        // o Cliente nao recebe organizacao nem por excecao (D9)
        Org supplier = newOrganization();
        TestUser clientAccount = externalUser(supplier, newClient(supplier), null);
        mass("INSERT INTO identities (identity_id, email, display_name, email_verified_at, status) VALUES (gen_random_uuid(), '" + clientAccount.email()
                + "', 'Pessoa Cliente', now(), 2)");
        conflict(staff.post(route, body.formatted(clientAccount.email(), "Do cliente " + tag, "Do cliente Ltda")), "organization_responsible_is_client");
        assertThat(one("SELECT count(*) FROM organization_exceptional_creations WHERE operator_user_id = '" + operator.id() + "'")).as("nada criado pelas recusas")
                .isEqualTo("0");

        // ---- cria: a pessoa indicada administra a nova organizacao e e a responsavel; o operador nao entra
        BrowserClient.Response created = staff.post(route, body.formatted(email.toUpperCase(), "Excecao " + tag, "Excecao " + tag + " Ltda"));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String org = JSON.readTree(created.body()).get("organizationId").asString();
        assertThat(one("SELECT source || '|' || responsible_identity_id FROM organization_responsibility WHERE organization_id = '" + org + "'"))
                .isEqualTo("4|" + identity);
        assertThat(one("SELECT responsible_identity_id || '|' || operator_user_id || '|' || operator_organization_id || '|' || justification "
                + "FROM organization_exceptional_creations WHERE organization_id = '" + org + "'"))
                .isEqualTo(identity + "|" + operator.id() + "|" + n.id() + "|" + justification);
        assertThat(one("SELECT count(*) FROM organization_memberships m JOIN users u ON u.user_id = m.user_id JOIN identity_users iu ON iu.user_id = u.user_id "
                + "JOIN organization_role_assignments a ON a.organization_id = m.organization_id AND a.membership_id = m.membership_id AND a.status = 1 "
                + "JOIN roles r ON r.role_id = a.role_id AND r.code = 'ORG_ADMIN' WHERE m.organization_id = '" + org + "' AND iu.identity_id = '" + identity + "'"))
                .as("a pessoa indicada administra a nova organizacao").isEqualTo("1");
        assertThat(one("SELECT count(*) FROM organization_memberships WHERE organization_id = '" + org + "'")).as("so ela tem conta na organizacao").isEqualTo("1");
        assertThat(one("SELECT count(*) FROM users WHERE organization_id = '" + org + "' AND lower(email) = lower('" + operator.email() + "')"))
                .as("o operador nao ganha conta").isEqualTo("0");
        assertThat(staff.get("/api/v1/o/" + org + "/licenses/summary").status()).as("nem acesso com a sessao dele").isIn(401, 403, 404);

        // ---- o LIMITE DE VAZIAS (3) vale por padrao tambem na criacao pela Nekkora: a quarta e recusada, e nada e criado
        for (int i = 2; i <= 3; i++) {
            assertThat(staff.post(route, body.formatted(email, "Excecao " + tag + " " + i, "Excecao " + i + " Ltda")).status()).isEqualTo(201);
        }
        conflict(staff.post(route, body.formatted(email, "Excecao " + tag + " 4", "Excecao 4 Ltda")), "empty_organization_limit_reached");
        assertThat(one("SELECT count(*) FROM organizations WHERE name = 'Excecao " + tag + " 4'")).as("nada criado").isEqualTo("0");
        // ultrapassar e uma excecao EXPLICITA, com justificativa PROPRIA: sem ela, ou com ela curta, o pedido nao e aceito
        String over = "{\"responsibleEmail\":\"" + email + "\",\"name\":\"Excecao " + tag + " 4\",\"legalName\":\"Excecao 4 Ltda\",\"justification\":\""
                + justification + "\",\"exceedEmptyLimit\":true%s}";
        assertThat(staff.post(route, over.formatted("")).status()).as("sem a justificativa do limite").isEqualTo(400);
        assertThat(staff.post(route, over.formatted(",\"limitJustification\":\"curta\"")).status()).isEqualTo(400);
        BrowserClient.Response exceeded = staff.post(route, over.formatted(",\"limitJustification\":\"diretoria autorizou a quarta organizacao vazia\""));
        assertThat(exceeded.status()).as(exceeded.body()).isEqualTo(201);
        assertThat(JSON.readTree(exceeded.body()).get("emptyLimitExceeded").asBoolean()).isTrue();
        assertThat(JSON.readTree(created.body()).get("emptyLimitExceeded").asBoolean()).as("a primeira nao ultrapassou nada").isFalse();
        assertThat(one("SELECT string_agg(empty_limit_exceeded || ':' || coalesce(limit_justification, '-'), ',' ORDER BY created_at) "
                + "FROM organization_exceptional_creations WHERE responsible_identity_id = '" + identity + "'"))
                .as("so a que ultrapassou tem a excecao e a justificativa gravadas")
                .isEqualTo("false:-,false:-,false:-,true:diretoria autorizou a quarta organizacao vazia");
        // pedir a excecao sem precisar dela nao a grava: a pessoa do Cliente continua de fora mesmo com a excecao pedida
        conflict(staff.post(route, over.formatted(",\"limitJustification\":\"diretoria autorizou a quarta organizacao vazia\"")
                .replace(email, clientAccount.email()).replace("Excecao " + tag + " 4", "Do cliente " + tag)), "organization_responsible_is_client");
        assertThat(one("SELECT count(*) FROM organization_exceptional_creations WHERE responsible_identity_id = '" + identity + "'")).isEqualTo("4");
        // mesmo nome para a mesma pessoa: recusado como nas outras criacoes
        conflict(staff.post(route, over.formatted(",\"limitJustification\":\"diretoria autorizou mais uma organizacao vazia\"")
                .replace("Excecao " + tag + " 4", "Excecao " + tag)), "organization_name_taken");
        // operador retirado do cadastro: o banco recusa, mesmo chamado direto com o papel da Platform
        DB.asSuperuser("SET session_replication_role = replica", "UPDATE license_operators SET revoked_at = clock_timestamp() "
                + "WHERE operator_user_id = '" + operator.id() + "'");
        assertThat(staff.post(route, body.formatted(email, "Excecao " + tag + " 9", "Excecao 9 Ltda")).status()).isIn(403, 404);
    }

    /** Massa de estados, como superusuario e sem gatilhos nem chaves estrangeiras (so nesta conexao). */
    private static void mass(String... sqls) {
        String[] all = new String[sqls.length + 1];
        all[0] = "SET session_replication_role = replica";
        System.arraycopy(sqls, 0, all, 1, sqls.length);
        DB.asSuperuser(all);
    }

    private static String attempt(UUID id, String org, UUID execution, int number, UUID membership) {
        return "INSERT INTO remote_attempts (attempt_id, organization_id, remote_execution_id, attempt_number, agent_id, worker_id, authorization_id, "
                + "created_by_membership_id, offer_valid_until) VALUES ('" + id + "', '" + org + "', '" + execution + "', " + number + ", gen_random_uuid(), "
                + "gen_random_uuid(), gen_random_uuid(), '" + membership + "', clock_timestamp() + interval '15 minutes')";
    }

    /** Fato sobre os processos de uma tentativa (058, 060); o 2 e a declaracao de um administrador. */
    private static String report(UUID attempt, String org, int kind, UUID membership) {
        return "INSERT INTO remote_attempt_process_reports (attempt_id, kind, organization_id, agent_id, reason, verification, by_membership_id) VALUES ('"
                + attempt + "', " + kind + ", '" + org + "', gen_random_uuid(), " + (kind == 2 ? "'OTHER_VERIFIED', 'conferido na maquina: nenhum processo da tentativa', '"
                + membership + "'" : "NULL, NULL, NULL") + ")";
    }
}
