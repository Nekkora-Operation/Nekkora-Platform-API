package com.nekkora.platform;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.platform.mail.MailSender;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLATFORM-FLUXO-002 (070) — "Clientes e acessos", ponta a ponta contra o banco real: convite de Cliente para UMA automacao (com a
 * excecao a D-GRANT-1 registrada), aceite pela identidade global, area do cliente pelas funcoes do Portal (como o papel real
 * nekkora_portal, com o token da sessao de identidade), isolamento por automacao e por maquina, permissoes de registros e de
 * resultados, pedido e aprovacao por segunda pessoa, so-reducao, revogacao, suspensao e retirada com efeito na proxima consulta,
 * recusas (membro interno, conta antiga, convite vencido, rota direta), reenvio sem ampliar, historico com filtros e a permissao
 * propria da auditoria.
 */
@DirtiesContext
class ClientAccessIT extends IdentityTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FP = "'" + "ab".repeat(32) + "'";

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.convites.enabled", () -> "true");
    }

    private record Admin(Org org, TestUser user, BrowserClient browser) {
    }

    /** Organizacao com a administradora logada e com step-up. */
    private Admin admin() {
        Org o = newOrganization();
        TestUser u = new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray());
        BrowserClient b = browser();
        firstLogin(b, o.id(), u);
        stepUp(b, o.id(), u);
        return new Admin(o, u, b);
    }

    /** Segunda administradora da mesma organizacao (D-GRANT-1: a aprovacao e de outra pessoa). */
    private Admin secondAdmin(Org o) {
        TestUser u = internalUser(o, true, 2);
        AccessFixtures.assignOrgRole(o.id(), AccessFixtures.membershipOf(o.id(), u.id()), "ORG_ADMIN", o.adminMembership());
        BrowserClient b = browser();
        firstLogin(b, o.id(), u);
        stepUp(b, o.id(), u);
        return new Admin(o, u, b);
    }

    private void stepUp(BrowserClient b, UUID org, TestUser u) {
        assertThat(b.post("/api/v1/o/" + org + "/auth/mfa/step-up", "{\"code\":\"" + code(u.id()) + "\"}").status()).isEqualTo(204);
    }

    private String lastToken(String email) {
        List<PlatformTestBase.SentMessage> m = mails(email).stream().filter(x -> x.kind() == MailSender.Kind.ORG_INVITATION).toList();
        return m.get(m.size() - 1).token();
    }

    private static JsonNode json(BrowserClient.Response r) throws Exception {
        return JSON.readTree(r.body());
    }

    private static String inviteBody(String email, UUID client, UUID automation, UUID worker, boolean logs, boolean results) {
        return "{\"email\":" + q(email) + ",\"clientId\":\"" + client + "\",\"automationId\":\"" + automation + "\",\"workerId\":"
                + (worker == null ? "null" : "\"" + worker + "\"") + ",\"canReadLogs\":" + logs + ",\"canDownloadResults\":" + results + "}";
    }

    private BrowserClient.Response accept(ComFator f, UUID org, String token) {
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);
        return f.browser().post("/api/v1/invitations/accept", "{\"organizationId\":\"" + org + "\",\"token\":" + q(token) + "}");
    }

    // ------------------------------------------------------------------ Portal (como o papel real, pela sessao de identidade)

    /** Resultado de uma funcao do Portal: as linhas (primeira coluna como texto) ou o SQLSTATE da recusa. */
    private record P(List<List<String>> rows, String state, String message) {
        List<String> col(int i) {
            if (state != null) {
                throw new AssertionError("funcao do Portal recusou: " + state + " " + message);
            }
            List<String> out = new ArrayList<>();
            rows.forEach(r -> out.add(r.get(i)));
            return out;
        }
    }

    private static P portal(String sql, Object... args) {
        try (Connection c = DB.connectAs("nekkora_portal"); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            List<List<String>> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= n; i++) {
                        row.add(rs.getString(i));
                    }
                    rows.add(row);
                }
            }
            return new P(rows, null, null);
        } catch (SQLException e) {
            return new P(List.of(), e.getSQLState(), e.getMessage());
        }
    }

    private static String idToken(ComFator f) {
        return f.browser().cookies().get("__Host-nekkora_id");
    }

    private static P automations(UUID org, ComFator f) {
        return portal("SELECT automation_id::text, automation_name, all_machines::text, machines::text, can_read_logs::text, can_download_results::text "
                + "FROM public.portal_client_automations(?, ?)", org, idToken(f));
    }

    private static P executions(UUID org, ComFator f, UUID automation) {
        return portal("SELECT id::text, kind, worker_id::text FROM public.portal_client_list_executions(?, ?, ?, NULL, NULL, 100)", org, idToken(f), automation);
    }

    private static P execution(UUID org, ComFator f, String kind, UUID id) {
        return portal("SELECT id::text, attempts::text FROM public.portal_client_get_execution(?, ?, ?, ?)", org, idToken(f), kind, id);
    }

    private static P logs(UUID org, ComFator f, UUID execution, UUID attempt) {
        return portal("SELECT sequence::text, message FROM public.portal_client_list_logs(?, ?, ?, ?, 0, 500)", org, idToken(f), execution, attempt);
    }

    private static P results(UUID org, ComFator f, UUID execution, UUID attempt) {
        return portal("SELECT result_number::text, file_name FROM public.portal_client_list_results(?, ?, ?, ?)", org, idToken(f), execution, attempt);
    }

    // ------------------------------------------------------------------ massa

    private static void mass(String... sqls) {
        List<String> all = new ArrayList<>();
        all.add("SET session_replication_role = replica");
        all.addAll(List.of(sqls));
        DB.asSuperuser(all.toArray(String[]::new));
    }

    private static UUID one(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return UUID.fromString(rs.getString(1));
        }
    }

    private static String platformExecution(UUID id, UUID org, UUID ws, UUID a, UUID v, UUID m, UUID worker, String at) {
        return "INSERT INTO executions (execution_id, organization_id, workspace_id, automation_id, automation_version_id, request_key, request_fingerprint, "
                + "requested_by_membership_id, command_snapshot, status, status_reason, origin, created_at, finished_at, worker_id, worker_agent_assignment_id, "
                + "worker_workspace_assignment_id, reserved_at) SELECT '" + id + "', '" + org
                + "', '" + ws + "', '" + a + "', v.automation_version_id, 'k-' || gen_random_uuid(), repeat('b', 64), '" + m + "', v.command_json, 4, 1, 1, "
                + "TIMESTAMPTZ '" + at + "', TIMESTAMPTZ '" + at + "' + interval '5 seconds', '" + worker + "', gen_random_uuid(), gen_random_uuid(), TIMESTAMPTZ '" + at
                + "' FROM automation_versions v "
                + "WHERE v.automation_version_id = '" + v + "'";
    }

    private static String remote(UUID id, UUID org, UUID ws, UUID a, UUID v, UUID m, String at) {
        return "INSERT INTO remote_executions (remote_execution_id, organization_id, workspace_id, automation_id, automation_version_id, package_fingerprint, "
                + "package_fingerprint_rule, requirements_revision, effective_dependencies, request_key, requested_by_membership_id, requested_at) VALUES ('"
                + id + "', '" + org + "', '" + ws + "', '" + a + "', '" + v + "', " + FP + ", 'nekkora-agent/external-python-fingerprint/1', 1, '[]'::jsonb, "
                + "gen_random_uuid(), '" + m + "', TIMESTAMPTZ '" + at + "')";
    }

    private static String attempt(UUID id, UUID org, UUID execution, UUID worker, UUID m, String at) {
        return "INSERT INTO remote_attempts (attempt_id, organization_id, remote_execution_id, attempt_number, agent_id, worker_id, authorization_id, "
                + "created_by_membership_id, created_at, offer_valid_until, state, token_hash, ended_at) VALUES ('" + id + "', '" + org + "', '" + execution
                + "', 1, gen_random_uuid(), '" + worker + "', gen_random_uuid(), '" + m + "', TIMESTAMPTZ '" + at + "', TIMESTAMPTZ '" + at
                + "' + interval '15 minutes', 7, repeat('c', 64), TIMESTAMPTZ '" + at + "' + interval '1 minute')";
    }

    private record World(UUID ws, UUID a1, UUID a2, UUID w1, UUID w2, UUID e1, UUID e2, UUID e3, UUID r1, UUID att1, UUID r2, UUID att2) {
    }

    /**
     * Um ambiente com duas automacoes (a1, a2) e duas maquinas (w1, w2) autorizadas nele. a1 roda em w1 (e1, r1 com registros e
     * resultado) e em w2 (e2, r2); a2 roda em w1 (e3): a mesma maquina executa as duas.
     */
    private World world(Org o) throws Exception {
        ProductFixtures.unlimited(o.id(), o.adminId());
        UUID m = o.adminMembership();
        UUID ws = AccessFixtures.namedWorkspace(o.id(), "Fiscal", 1);
        UUID a1 = ProductFixtures.automation(o.id(), ws, null, "Conciliacao", m);
        UUID v1 = ProductFixtures.version(o.id(), ws, a1, true, m);
        UUID a2 = ProductFixtures.automation(o.id(), ws, null, "Folha interna", m);
        UUID v2 = ProductFixtures.version(o.id(), ws, a2, true, m);
        UUID w1 = one("INSERT INTO workers (organization_id, name, status) VALUES ('" + o.id() + "', 'Estacao fiscal', 1) RETURNING worker_id");
        UUID w2 = one("INSERT INTO workers (organization_id, name, status) VALUES ('" + o.id() + "', 'Estacao dois', 1) RETURNING worker_id");
        for (UUID w : List.of(w1, w2)) {
            DB.asSuperuser("INSERT INTO worker_workspace_assignments (organization_id, worker_id, workspace_id, status, authorized_by_membership_id) VALUES ('"
                    + o.id() + "', '" + w + "', '" + ws + "', 1, '" + m + "')");
        }
        World x = new World(ws, a1, a2, w1, w2, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        mass(platformExecution(x.e1(), o.id(), ws, a1, v1, m, w1, "2026-03-05T08:00:00Z"),
                platformExecution(x.e2(), o.id(), ws, a1, v1, m, w2, "2026-03-06T08:00:00Z"),
                platformExecution(x.e3(), o.id(), ws, a2, v2, m, w1, "2026-03-07T08:00:00Z"),
                remote(x.r1(), o.id(), ws, a1, v1, m, "2026-03-08T08:00:00Z"),
                attempt(x.att1(), o.id(), x.r1(), w1, m, "2026-03-08T08:00:00Z"),
                remote(x.r2(), o.id(), ws, a1, v1, m, "2026-03-09T08:00:00Z"),
                attempt(x.att2(), o.id(), x.r2(), w2, m, "2026-03-09T08:00:00Z"),
                "INSERT INTO remote_attempt_log_lines (attempt_id, sequence, organization_id, captured_at, channel, truncated, message) SELECT '" + x.att1()
                        + "', g, '" + o.id() + "', clock_timestamp(), 1, false, 'linha ' || g FROM generate_series(1, 3) g",
                "INSERT INTO remote_attempt_results (attempt_id, result_number, organization_id, file_name, size_bytes, sha256) VALUES ('" + x.att1()
                        + "', 1, '" + o.id() + "', 'relatorio.csv', 3, '" + "d".repeat(64) + "')");
        return x;
    }

    private static UUID createClient(Admin a, String name) throws Exception {
        BrowserClient.Response r = a.browser().post("/api/v1/o/" + a.org().id() + "/clients", "{\"name\":" + q(name) + "}");
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return UUID.fromString(r.field("clientId"));
    }

    private static List<String> types(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(n -> out.add(n.get("type").asString() + (n.get("grantException").asBoolean() ? "*" : "")));
        return out;
    }

    // ------------------------------------------------------------------ testes

    @Test
    void conviteDeClienteAceiteEscopoPorAutomacaoEMaquinaSegundaPessoaReducaoRevogacaoEAuditoria() throws Exception {
        Admin a = admin();
        UUID org = a.org().id();
        World x = world(a.org());
        UUID client = createClient(a, "Contabil Exemplo");
        ComFator f = comFator();

        // opcoes do formulario: as duas automacoes do ambiente
        BrowserClient.Response opt = a.browser().get("/api/v1/o/" + org + "/client-share-options");
        assertThat(opt.status()).as(opt.body()).isEqualTo(200);
        assertThat(json(opt).get("automations").size()).isEqualTo(2);

        // 1. convite: a1, so na maquina w1, com registros e sem resultados. Nada e concedido antes do aceite.
        BrowserClient.Response inv = a.browser().post("/api/v1/o/" + org + "/client-invitations", inviteBody(f.email(), client, x.a1(), x.w1(), true, false));
        assertThat(inv.status()).as(inv.body()).isEqualTo(201);
        String firstToken = lastToken(f.email());
        assertThat(portal("SELECT * FROM public.portal_client_organizations(?)", idToken(f)).rows()).isEmpty();
        assertThat(automations(org, f).state()).isEqualTo("NK403");

        // reenvio: o mesmo escopo, o link anterior deixa de valer
        BrowserClient.Response re = a.browser().post("/api/v1/o/" + org + "/invitations/" + inv.field("invitationId") + "/resend", null);
        assertThat(re.status()).as(re.body()).isEqualTo(201);
        assertThat(accept(f, org, firstToken).status()).isEqualTo(404);
        assertThat(value("SELECT automation_id::text || worker_id::text || can_read_logs::text || can_download_results::text FROM identity_invitations "
                + "WHERE invitation_id = '" + re.field("invitationId") + "'")).isEqualTo(x.a1().toString() + x.w1() + "truefalse");

        // 2. aceite pela identidade global: conta e vinculo de cliente, concessao ativa pela excecao
        BrowserClient.Response ac = accept(f, org, lastToken(f.email()));
        assertThat(ac.status()).as(ac.body()).isEqualTo(200);
        assertThat(ac.field("audience")).isEqualTo("client");
        assertThat(count("SELECT count(*) FROM users WHERE organization_id = '" + org + "' AND account_type = 2 AND email_normalized = '" + f.email().toLowerCase() + "'")).isEqualTo(1);
        assertThat(value("SELECT basis || ':' || status FROM client_automation_grants WHERE organization_id = '" + org + "'")).isEqualTo("2:2");
        // a conta de cliente nao abre sessao da organizacao (rotas da Platform continuam fechadas)
        BrowserClient.Response troca = f.browser().post("/api/v1/sessions/organization", "{\"organizationId\":\"" + org + "\"}");
        assertThat(troca.field("status")).as(troca.body()).isNotEqualTo("sessao_criada");
        assertThat(troca.setCookies()).noneMatch(c -> c.startsWith("__Host-nekkora_session="));

        // 3. area do cliente: so a1, so w1
        P orgs = portal("SELECT organization_id::text, shared_automations::text FROM public.portal_client_organizations(?)", idToken(f));
        assertThat(orgs.rows()).containsExactly(List.of(org.toString(), "1"));
        P au = automations(org, f);
        assertThat(au.col(0)).containsExactly(x.a1().toString());
        assertThat(au.rows().get(0).subList(2, 6)).containsExactly("false", "[{\"name\": \"Estacao fiscal\", \"workerId\": \"" + x.w1() + "\"}]", "true", "false");
        assertThat(executions(org, f, x.a1()).col(0)).containsExactlyInAnyOrder(x.e1().toString(), x.r1().toString());
        // a outra automacao da MESMA maquina e do MESMO ambiente continua inacessivel, por lista, detalhe e endereco
        assertThat(executions(org, f, x.a2()).state()).isEqualTo("NK404");
        assertThat(execution(org, f, "platform", x.e3()).state()).isEqualTo("NK404");
        // a mesma automacao em outra maquina: fora da restricao
        assertThat(execution(org, f, "platform", x.e2()).state()).isEqualTo("NK404");
        assertThat(execution(org, f, "remote", x.r2()).state()).isEqualTo("NK404");
        assertThat(logs(org, f, x.r2(), x.att2()).state()).isEqualTo("NK404");
        // registros concedidos; resultados nao
        assertThat(execution(org, f, "remote", x.r1()).rows().get(0).get(1)).contains("\"canReadLogs\": true", "\"canDownloadResults\": false");
        assertThat(logs(org, f, x.r1(), x.att1()).col(1)).containsExactly("linha 1", "linha 2", "linha 3");
        assertThat(results(org, f, x.r1(), x.att1()).state()).isEqualTo("NK403");
        // tentativa de outra execucao com a tentativa certa: fora do escopo
        assertThat(logs(org, f, x.r2(), x.att1()).state()).isEqualTo("NK404");
        // outra organizacao: sem vinculo
        Admin other = admin();
        assertThat(automations(other.org().id(), f).state()).isEqualTo("NK403");
        // sessao de identidade invalida
        assertThat(portal("SELECT * FROM public.portal_client_automations(?, ?)", org, "x".repeat(43)).state()).isEqualTo("NK401");

        // 4. Pessoas e acessos: o cliente aparece uma vez, com a concessao; o convite aceito so no historico
        JsonNode people = json(a.browser().get("/api/v1/o/" + org + "/people"));
        List<String> kinds = new ArrayList<>();
        people.get("items").forEach(n -> kinds.add(n.get("kind").asString()));
        assertThat(kinds).containsExactly("client", "member");
        JsonNode cl = people.get("items").get(0).get("client");
        assertThat(cl.get("clientName").asString()).isEqualTo("Contabil Exemplo");
        assertThat(cl.get("linkedToIdentity").asBoolean()).isTrue();
        assertThat(cl.get("grants").size()).isEqualTo(1);
        UUID membership = UUID.fromString(cl.get("clientMembershipId").asString());
        UUID g1 = UUID.fromString(cl.get("grants").get(0).get("grantId").asString());
        JsonNode accepted = json(a.browser().get("/api/v1/o/" + org + "/people?situation=invitation_accepted"));
        assertThat(accepted.get("items").get(0).get("invitation").get("audience").asString()).isEqualTo("client");

        // 5. compartilhar outra automacao depois: pedido PENDENTE; quem pediu nao aprova; outra administradora aprova
        Admin b = secondAdmin(a.org());
        BrowserClient.Response req = a.browser().post("/api/v1/o/" + org + "/client-automation-grants", "{\"clientMembershipId\":\"" + membership
                + "\",\"automationId\":\"" + x.a2() + "\",\"workerId\":null,\"canReadLogs\":false,\"canDownloadResults\":true}");
        assertThat(req.status()).as(req.body()).isEqualTo(201);
        UUID g2 = UUID.fromString(req.field("grantId"));
        assertThat(automations(org, f).col(0)).containsExactly(x.a1().toString());
        assertThat(a.browser().post("/api/v1/o/" + org + "/client-automation-grants/" + g2 + "/approve", null).status()).isEqualTo(403);
        BrowserClient.Response ap = b.browser().post("/api/v1/o/" + org + "/client-automation-grants/" + g2 + "/approve", null);
        assertThat(ap.status()).as(ap.body()).isEqualTo(200);
        assertThat(automations(org, f).col(0)).containsExactlyInAnyOrder(x.a1().toString(), x.a2().toString());
        assertThat(executions(org, f, x.a2()).col(0)).containsExactly(x.e3().toString());

        // 6. so reducao: ligar resultados em a1 exige outro pedido (que substitui a concessao na aprovacao)
        BrowserClient.Response widen = a.browser().post("/api/v1/o/" + org + "/client-automation-grants/" + g1 + "/reduce",
                "{\"canReadLogs\":true,\"canDownloadResults\":true}");
        assertThat(widen.status()).isEqualTo(409);
        assertThat(widen.body()).contains("client_grant_widening_requires_request");
        BrowserClient.Response wider = a.browser().post("/api/v1/o/" + org + "/client-automation-grants", "{\"clientMembershipId\":\"" + membership
                + "\",\"automationId\":\"" + x.a1() + "\",\"workerId\":\"" + x.w1() + "\",\"canReadLogs\":true,\"canDownloadResults\":true}");
        assertThat(wider.status()).as(wider.body()).isEqualTo(201);
        assertThat(results(org, f, x.r1(), x.att1()).state()).isEqualTo("NK403");
        assertThat(b.browser().post("/api/v1/o/" + org + "/client-automation-grants/" + wider.field("grantId") + "/approve", null).status()).isEqualTo(200);
        assertThat(results(org, f, x.r1(), x.att1()).col(1)).containsExactly("relatorio.csv");
        assertThat(value("SELECT status || ':' || revoke_reason FROM client_automation_grants WHERE grant_id = '" + g1 + "'")).isEqualTo("4:2");
        UUID g3 = UUID.fromString(wider.field("grantId"));
        // reduzir: registros desligados valem na proxima consulta
        assertThat(a.browser().post("/api/v1/o/" + org + "/client-automation-grants/" + g3 + "/reduce", "{\"canReadLogs\":false,\"canDownloadResults\":true}")
                .status()).isEqualTo(200);
        assertThat(logs(org, f, x.r1(), x.att1()).state()).isEqualTo("NK403");

        // 7. revogar so aquele acesso (a1 continua); a sessao do cliente segue aberta e a proxima consulta ja nao ve a2
        assertThat(a.browser().post("/api/v1/o/" + org + "/client-automation-grants/" + g2 + "/revoke", null).status()).isEqualTo(204);
        assertThat(executions(org, f, x.a2()).state()).isEqualTo("NK404");
        assertThat(automations(org, f).col(0)).containsExactly(x.a1().toString());

        // 8. suspender o vinculo barra tudo; reativar (com step-up) devolve o que estava concedido
        assertThat(a.browser().post("/api/v1/o/" + org + "/client-memberships/" + membership + "/suspend", null).status()).isEqualTo(200);
        assertThat(automations(org, f).state()).isEqualTo("NK403");
        assertThat(a.browser().post("/api/v1/o/" + org + "/client-memberships/" + membership + "/reactivate", null).status()).isEqualTo(200);
        assertThat(automations(org, f).col(0)).containsExactly(x.a1().toString());

        // 9. a excecao nao pode ser usada pelas rotas diretas nem pelo papel da Platform no banco
        String direct = "INSERT INTO client_automation_grants (organization_id, client_id, client_membership_id, workspace_id, automation_id, can_read_logs, "
                + "can_download_results, status, basis, invitation_id, requested_by_membership_id, approved_by_membership_id, approved_at) SELECT '" + org + "', '"
                + client + "', '" + membership + "', '" + x.ws() + "', '" + x.a2() + "', true, true, 2, 2, invitation_id, '" + a.org().adminMembership() + "', '"
                + a.org().adminMembership() + "', accepted_at FROM identity_invitations WHERE organization_id = '" + org + "' AND accepted_at IS NOT NULL LIMIT 1";
        assertThat(asPlatform(org, direct)).isEqualTo("42501");
        assertThat(asPlatform(org, "INSERT INTO client_automation_grants (organization_id, client_id, client_membership_id, workspace_id, automation_id, can_read_logs, "
                + "can_download_results, status, basis, requested_by_membership_id, approved_by_membership_id, approved_at) VALUES ('" + org + "', '" + client + "', '"
                + membership + "', '" + x.ws() + "', '" + x.a2() + "', true, true, 2, 1, '" + a.org().adminMembership() + "', '" + b.org().adminMembership()
                + "', now())")).isEqualTo("42501");
        assertThat(asPlatform(org, "UPDATE client_automation_grants SET can_read_logs = true WHERE grant_id = '" + g3 + "'")).isEqualTo("42501");

        // 10. auditoria: cada ato com responsavel e escopo; a excecao marcada no convite e na concessao aplicada no aceite
        JsonNode h = json(a.browser().get("/api/v1/o/" + org + "/access-history?clientId=" + client + "&limit=100"));
        assertThat(h.get("scope").asString()).isEqualTo("all");
        assertThat(types(h)).contains("CONVITE_EMITIDO*", "CONVITE_REENVIADO*", "CONVITE_ACEITO", "CLIENTE_CONCESSAO_APLICADA_NO_ACEITE*",
                "CLIENTE_CONCESSAO_SOLICITADA", "CLIENTE_CONCESSAO_APROVADA", "CLIENTE_CONCESSAO_REDUZIDA", "CLIENTE_CONCESSAO_REVOGADA",
                "VINCULO_CLIENTE_SUSPENSO", "VINCULO_CLIENTE_REATIVADO", "CLIENTE_CADASTRADO");
        JsonNode byGrant = json(a.browser().get("/api/v1/o/" + org + "/access-history?grantId=" + g2));
        assertThat(types(byGrant)).containsExactly("CLIENTE_CONCESSAO_REVOGADA", "CLIENTE_CONCESSAO_APROVADA", "CLIENTE_CONCESSAO_SOLICITADA");
        JsonNode approved = byGrant.get("items").get(1);
        assertThat(approved.get("actor").get("email").asString()).isEqualToIgnoringCase(b.user().email());
        assertThat(approved.get("automation").get("name").asString()).isEqualTo("Folha interna");
        assertThat(approved.get("target").get("email").asString()).isEqualToIgnoringCase(f.email());
        // concessoes: as duas especies, com filtros; a revogada continua consultavel
        JsonNode gr = json(a.browser().get("/api/v1/o/" + org + "/access-grants?clientId=" + client + "&situation=revoked"));
        assertThat(gr.get("items").size()).isEqualTo(2);
        JsonNode act = json(a.browser().get("/api/v1/o/" + org + "/access-grants?automationId=" + x.a1() + "&workerId=" + x.w1() + "&situation=active"));
        assertThat(act.get("items").size()).isEqualTo(1);
        assertThat(act.get("items").get(0).get("canDownloadResults").asBoolean()).isTrue();

        // 11. retirar o vinculo revoga as concessoes (motivo proprio) e barra o Portal; a identidade global continua
        assertThat(a.browser().post("/api/v1/o/" + org + "/client-memberships/" + membership + "/retire", null).status()).isEqualTo(200);
        assertThat(automations(org, f).state()).isEqualTo("NK403");
        assertThat(count("SELECT count(*) FROM client_automation_grants WHERE client_membership_id = '" + membership + "' AND status <> 4")).isZero();
        assertThat(value("SELECT status::text FROM identities WHERE identity_id = '" + f.identity() + "'")).isEqualTo("2");
        // readmissao por novo convite: a mesma conta, sem duplicar; so o escopo novo
        assertThat(a.browser().post("/api/v1/o/" + org + "/client-invitations", inviteBody(f.email(), client, x.a2(), null, false, false)).status()).isEqualTo(201);
        assertThat(accept(f, org, lastToken(f.email())).status()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM users WHERE organization_id = '" + org + "' AND email_normalized = '" + f.email().toLowerCase() + "'")).isEqualTo(1);
        assertThat(automations(org, f).col(0)).containsExactly(x.a2().toString());
    }

    @Test
    void recusasMembroInternoContaAntigaConviteVencidoClienteDiferenteEPermissaoPropriaDaAuditoria() throws Exception {
        Admin a = admin();
        UUID org = a.org().id();
        World x = world(a.org());
        UUID client = createClient(a, "Cliente Um");
        UUID otherClient = createClient(a, "Cliente Dois");

        // membro interno da organizacao: recusado (nao reduz privilegio)
        TestUser internal = internalUser(a.org(), true, 2);
        BrowserClient.Response r = a.browser().post("/api/v1/o/" + org + "/client-invitations", inviteBody(internal.email(), client, x.a1(), null, true, true));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).contains("client_invitation_internal_member");
        // conta de cliente antiga (sem identidade) com o e-mail: nunca ligada por coincidencia
        TestUser legacy = externalUser(a.org(), client, null);
        r = a.browser().post("/api/v1/o/" + org + "/client-invitations", inviteBody(legacy.email(), client, x.a1(), null, true, true));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).contains("client_invitation_legacy_account");
        // maquina fora do ambiente / automacao inexistente
        UUID strayWorker = one("INSERT INTO workers (organization_id, name, status) VALUES ('" + org + "', 'Fora do ambiente', 1) RETURNING worker_id");
        r = a.browser().post("/api/v1/o/" + org + "/client-invitations", inviteBody(email(), client, x.a1(), strayWorker, true, true));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).contains("client_scope_unavailable");
        assertThat(a.browser().post("/api/v1/o/" + org + "/client-invitations", inviteBody(email(), client, UUID.randomUUID(), null, true, true)).status())
                .isEqualTo(409);

        // convite vencido: o aceite nao aplica nada
        ComFator f = comFator();
        assertThat(a.browser().post("/api/v1/o/" + org + "/client-invitations", inviteBody(f.email(), client, x.a1(), null, true, false)).status()).isEqualTo(201);
        exec("SET session_replication_role = replica; UPDATE identity_invitations SET created_at = now() - interval '8 days', expires_at = now() - interval '1 minute' "
                + "WHERE organization_id = '" + org + "' AND email_normalized = '" + f.email().toLowerCase() + "'; SET session_replication_role = origin");
        assertThat(accept(f, org, lastToken(f.email())).status()).isEqualTo(404);
        assertThat(count("SELECT count(*) FROM users WHERE organization_id = '" + org + "' AND email_normalized = '" + f.email().toLowerCase() + "'")).isZero();

        // aceito por um cliente, convite por OUTRO cliente: recusado na emissao
        assertThat(a.browser().post("/api/v1/o/" + org + "/client-invitations", inviteBody(f.email(), client, x.a1(), null, true, false)).status()).isEqualTo(201);
        assertThat(accept(f, org, lastToken(f.email())).status()).isEqualTo(200);
        r = a.browser().post("/api/v1/o/" + org + "/client-invitations", inviteBody(f.email(), otherClient, x.a2(), null, true, false));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).contains("client_invitation_other_client");
        // a mesma automacao de novo: ja compartilhada
        r = a.browser().post("/api/v1/o/" + org + "/client-invitations", inviteBody(f.email(), client, x.a1(), null, true, false));
        assertThat(r.body()).contains("client_grant_exists");

        // permissoes proprias: quem so le um ambiente (Leitura) nao ve pessoas, concessoes nem o historico, e nao convida cliente.
        // (No catalogo atual so ORG_ADMIN tem external_access.manage; o recorte "client_access" do historico fica para um papel
        // com essa permissao sem org.manage - nao ha papel assim hoje, e papel fora do catalogo e recusado pela consistencia.)
        TestUser viewer = internalUser(a.org(), true, 2);
        AccessFixtures.assignWorkspaceRole(org, AccessFixtures.membershipOf(org, viewer.id()), x.ws(), "VIEWER", a.org().adminMembership());
        BrowserClient vb = browser();
        firstLogin(vb, org, viewer);
        stepUp(vb, org, viewer);
        assertThat(vb.get("/api/v1/o/" + org + "/access-history").status()).isEqualTo(403);
        assertThat(vb.get("/api/v1/o/" + org + "/access-grants").status()).isEqualTo(403);
        assertThat(vb.get("/api/v1/o/" + org + "/people").status()).isEqualTo(403);
        assertThat(vb.get("/api/v1/o/" + org + "/client-share-options").status()).isEqualTo(403);
        assertThat(vb.post("/api/v1/o/" + org + "/client-invitations", inviteBody(email(), client, x.a1(), null, false, false)).status()).isEqualTo(403);
        assertThat(vb.post("/api/v1/o/" + org + "/clients", "{\"name\":\"Nao pode\"}").status()).isEqualTo(403);
        // quem administra ve tudo, inclusive os atos de papel
        JsonNode roles = json(a.browser().get("/api/v1/o/" + org + "/access-history?category=roles"));
        assertThat(roles.get("scope").asString()).isEqualTo("all");
    }

    /** SQLSTATE de um comando como nekkora_platform no contexto da organizacao (ou "OK"). */
    private static String asPlatform(UUID org, String sql) {
        try (Connection c = DB.connectAs("nekkora_platform")) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT set_config('nekkora.organization_id', '" + org + "', true)");
                st.execute(sql);
                c.rollback();
                return "OK";
            }
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }
}
