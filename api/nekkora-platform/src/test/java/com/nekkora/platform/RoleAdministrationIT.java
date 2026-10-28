package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.platform.mail.MailSender;

/**
 * Papeis e vinculo (contrato de 2026-10-27, §5 e §6; 023), pelo papel real nekkora_platform. Atores com login, MFA e
 * step-up reais; fixtures de vinculos e papeis como superusuario (DECLARADAS). Cada caso confere o estado persistido e a
 * auditoria ({@code access_events}), nao so o status HTTP.
 */
class RoleAdministrationIT extends PlatformTestBase {

    private record Actor(TestUser user, UUID membership, BrowserClient browser) {
    }

    private Actor login(Org o, TestUser u) throws Exception {
        BrowserClient b = browser();
        firstLogin(b, o.id(), u);
        return new Actor(u, membershipOf(u.id()), b);
    }

    private Actor admin(Org o) throws Exception {
        return login(o, new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray()));
    }

    private void stepUp(Org o, Actor a) {
        assertThat(a.browser().post("/api/v1/o/" + o.id() + "/auth/mfa/step-up", "{\"code\":\"" + code(a.user().id()) + "\"}").status()).isEqualTo(204);
    }

    private static BrowserClient.Response assign(Org o, Actor a, UUID user, String role, UUID ws) {
        return a.browser().post("/api/v1/o/" + o.id() + "/users/" + user + "/roles",
                "{\"role\":\"" + role + "\"" + (ws == null ? "" : ",\"workspaceId\":\"" + ws + "\"") + "}");
    }

    private static UUID membershipOf(UUID user) throws Exception {
        return UUID.fromString(value("SELECT membership_id FROM organization_memberships WHERE user_id = '" + user + "'"));
    }

    private static void grantRole(Org o, UUID user, String role, UUID ws) throws Exception {
        if (ws == null) {
            DB.asSuperuser("INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id)"
                    + " SELECT '" + o.id() + "', '" + membershipOf(user) + "', role_id, 1, 1, '" + o.adminMembership() + "' FROM roles WHERE code = '" + role + "'");
        } else {
            DB.asSuperuser("INSERT INTO workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status, assigned_by_membership_id)"
                    + " SELECT '" + o.id() + "', '" + ws + "', '" + membershipOf(user) + "', role_id, 2, 1, '" + o.adminMembership() + "' FROM roles WHERE code = '"
                    + role + "'");
        }
    }

    private static long activeRoles(UUID user) throws Exception {
        UUID m = membershipOf(user);
        return count("SELECT (SELECT count(*) FROM organization_role_assignments WHERE membership_id = '" + m + "' AND status = 1)"
                + " + (SELECT count(*) FROM workspace_role_assignments WHERE membership_id = '" + m + "' AND status = 1)");
    }

    private static long events(UUID org, int type) throws Exception {
        return count("SELECT count(*) FROM access_events WHERE organization_id = '" + org + "' AND event_type = " + type);
    }

    // ------------------------------------------------------------------ atribuicao

    /**
     * ORG_ADMIN atribui VIEWER num workspace: 200 com a atribuicao; repeticao devolve a mesma, sem evento novo; evento 5 com
     * autor, alvo, papel e workspace; aviso ao alvo na fila. A consulta do membro mostra o papel.
     */
    @Test
    void orgAdminAtribuiPapelDeWorkspaceComStepUpERepeticaoSemEfeito() throws Exception {
        Org o = newOrganization();
        UUID ws = newWorkspace(o, null);
        TestUser alvo = internalUser(o, true, 2);
        Actor a = admin(o);
        assertThat(assign(o, a, alvo.id(), "VIEWER", ws).status()).as("sem step-up").isEqualTo(403);
        assertThat(activeRoles(alvo.id())).isZero();
        stepUp(o, a);
        BrowserClient.Response r = assign(o, a, alvo.id(), "VIEWER", ws);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        UUID id = UUID.fromString(r.field("assignmentId"));
        assertThat(assign(o, a, alvo.id(), "VIEWER", ws).field("assignmentId")).as("repeticao: a mesma").isEqualTo(id.toString());
        assertThat(events(o.id(), 5)).isEqualTo(1);
        assertThat(value("SELECT actor_membership_id || '|' || target_user_id || '|' || workspace_id || '|' || assignment_id FROM access_events WHERE organization_id = '"
                + o.id() + "' AND event_type = 5")).isEqualTo(a.membership() + "|" + alvo.id() + "|" + ws + "|" + id);
        assertThat(testMail.sent.stream().filter(m -> m.kind() == MailSender.Kind.NOTICE_ORG_ACCESS_CHANGED && alvo.email().equals(m.recipient())).count())
                .isEqualTo(1);
        BrowserClient.Response lista = a.browser().get("/api/v1/o/" + o.id() + "/users/" + alvo.id() + "/roles");
        assertThat(lista.status()).isEqualTo(200);
        assertThat(lista.body()).contains("\"role\":\"VIEWER\"").contains(ws.toString()).contains("\"membershipState\":\"ATIVO\"");
    }

    /**
     * Autoridade: WORKSPACE_ADMIN atribui OPERATOR no PROPRIO workspace; recusa em outro workspace, recusa WORKSPACE_ADMIN e
     * ORG_ADMIN. VIEWER nao atribui nada. Ninguem atribui a si mesmo (nem o ORG_ADMIN). Alvo de outra organizacao: 404.
     * Nenhuma recusa grava atribuicao ou evento.
     */
    @Test
    void autoridadePorPapelEEscopoSemAutoatribuicaoNemOutraOrganizacao() throws Exception {
        Org o = newOrganization();
        UUID ws1 = newWorkspace(o, null);
        UUID ws2 = newWorkspace(o, null);
        TestUser wsAdmin = internalUser(o, true, 2);
        TestUser viewer = internalUser(o, true, 2);
        TestUser alvo = internalUser(o, true, 2);
        grantRole(o, wsAdmin.id(), "WORKSPACE_ADMIN", ws1);
        grantRole(o, viewer.id(), "VIEWER", ws1);
        Actor w = login(o, wsAdmin);
        stepUp(o, w);
        assertThat(assign(o, w, alvo.id(), "OPERATOR", ws1).status()).as("no proprio workspace").isEqualTo(200);
        assertThat(assign(o, w, alvo.id(), "OPERATOR", ws2).status()).as("em outro workspace").isEqualTo(403);
        assertThat(assign(o, w, alvo.id(), "WORKSPACE_ADMIN", ws1).status()).as("papel de administracao").isEqualTo(403);
        assertThat(assign(o, w, alvo.id(), "ORG_ADMIN", null).status()).isEqualTo(403);
        assertThat(assign(o, w, wsAdmin.id(), "VIEWER", ws1).status()).as("autoatribuicao").isEqualTo(403);
        Actor v = login(o, viewer);
        stepUp(o, v);
        assertThat(assign(o, v, alvo.id(), "VIEWER", ws1).status()).isEqualTo(403);
        Actor a = admin(o);
        stepUp(o, a);
        assertThat(assign(o, a, o.adminId(), "VIEWER", ws1).status()).as("ORG_ADMIN a si mesmo").isEqualTo(403);
        Org outra = newOrganization();
        TestUser alheio = internalUser(outra, true, 2);
        assertThat(assign(o, a, alheio.id(), "VIEWER", ws1).status()).as("alvo de outra organizacao").isEqualTo(404);
        assertThat(assign(o, a, alvo.id(), "VIEWER", newWorkspace(outra, null)).status()).as("workspace de outra organizacao").isEqualTo(404);
        assertThat(activeRoles(alvo.id())).isEqualTo(1);
        assertThat(activeRoles(alheio.id())).isZero();
        assertThat(events(o.id(), 5)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ alteracao, revogacao e efeito nas sessoes

    /**
     * Alteracao no mesmo workspace (VIEWER -> OPERATOR): a anterior revogada, a nova ativa, evento 7 com o papel anterior;
     * para o mesmo papel: sem efeito. Revogacao: evento 6; repeticao 204 sem evento. Efeito na sessao aberta do alvo: o
     * papel deixa de valer na requisicao seguinte (a sessao continua valida).
     */
    @Test
    void alteracaoRevogacaoEEfeitoImediatoNaSessaoAberta() throws Exception {
        Org o = newOrganization();
        UUID ws = newWorkspace(o, null);
        TestUser alvo = internalUser(o, true, 2);
        Actor a = admin(o);
        stepUp(o, a);
        UUID id = UUID.fromString(assign(o, a, alvo.id(), "VIEWER", ws).field("assignmentId"));
        BrowserClient.Response alt = a.browser().post("/api/v1/o/" + o.id() + "/role-assignments/" + id + "/replace", "{\"role\":\"OPERATOR\"}");
        assertThat(alt.status()).as(alt.body()).isEqualTo(200);
        UUID novo = UUID.fromString(alt.field("assignmentId"));
        assertThat(value("SELECT status FROM workspace_role_assignments WHERE assignment_id = '" + id + "'")).isEqualTo("2");
        assertThat(value("SELECT r.code FROM workspace_role_assignments a JOIN roles r ON r.role_id = a.role_id WHERE assignment_id = '" + novo + "' AND status = 1"))
                .isEqualTo("OPERATOR");
        assertThat(value("SELECT pr.code || '>' || r.code FROM access_events e JOIN roles r ON r.role_id = e.role_id JOIN roles pr ON pr.role_id = e.previous_role_id"
                + " WHERE e.organization_id = '" + o.id() + "' AND e.event_type = 7")).isEqualTo("VIEWER>OPERATOR");
        assertThat(a.browser().post("/api/v1/o/" + o.id() + "/role-assignments/" + novo + "/replace", "{\"role\":\"OPERATOR\"}").field("assignmentId"))
                .as("mesmo papel").isEqualTo(novo.toString());
        assertThat(a.browser().post("/api/v1/o/" + o.id() + "/role-assignments/" + novo + "/replace", "{\"role\":\"ORG_ADMIN\"}").status())
                .as("escopo diferente").isEqualTo(400);

        // sessao aberta do alvo: com o OPERATOR, ve o workspace; depois da revogacao, deixa de ver na requisicao seguinte
        Actor t = login(o, alvo);
        assertThat(t.browser().get("/api/v1/o/" + o.id() + "/workspaces/" + ws).status()).isEqualTo(200);
        assertThat(a.browser().post("/api/v1/o/" + o.id() + "/role-assignments/" + novo + "/revoke", null).status()).isEqualTo(204);
        assertThat(t.browser().get("/api/v1/o/" + o.id() + "/workspaces/" + ws).status()).as("papel revogado nao vale mais").isEqualTo(404);
        assertThat(t.browser().get("/api/v1/o/" + o.id() + "/me").status()).as("a sessao continua").isEqualTo(200);
        assertThat(a.browser().post("/api/v1/o/" + o.id() + "/role-assignments/" + novo + "/revoke", null).status()).as("repeticao").isEqualTo(204);
        assertThat(events(o.id(), 6)).isEqualTo(1);
    }

    /**
     * Ultimo ORG_ADMIN, no banco (a garantia da 003): o proprio ORG_ADMIN revogando a propria atribuicao como Platform e
     * recusado com P0001; pela API, nunca se atinge o alvo proprio (403 OWN_ACCOUNT).
     */
    @Test
    void ultimoOrgAdminProtegidoPeloBancoEPelaRegraDoAlvo() throws Exception {
        Org o = newOrganization();
        Actor a = admin(o);
        String propria = value("SELECT assignment_id FROM organization_role_assignments WHERE membership_id = '" + o.adminMembership() + "' AND status = 1");
        assertThat(a.browser().post("/api/v1/o/" + o.id() + "/role-assignments/" + propria + "/revoke", null).status()).isEqualTo(403);
        assertThat(AccessInvitationsIT.platformSql(o.id(), "UPDATE organization_role_assignments SET status = 2, revoked_at = now(), revoked_by_membership_id = '"
                + o.adminMembership() + "' WHERE assignment_id = '" + propria + "'")).isEqualTo("P0001");
        assertThat(value("SELECT status FROM organization_role_assignments WHERE assignment_id = '" + propria + "'")).isEqualTo("1");
    }

    /**
     * Duas revogacoes concorrentes entre os dois ultimos ORG_ADMIN (A revoga B e B revoga A, largada simultanea): serializam
     * na organizacao; exatamente uma passa (204) e a outra e recusada; a organizacao fica com exatamente um ORG_ADMIN efetivo
     * e um evento 6.
     */
    @Test
    void doisUltimosAdminsSeRevogandoAoMesmoTempoSobraExatamenteUm() throws Exception {
        Org o = newOrganization();
        TestUser b = internalUser(o, true, 2);
        grantRole(o, b.id(), "ORG_ADMIN", null);
        Actor ka = admin(o);
        Actor kb = login(o, b);
        String deA = value("SELECT assignment_id FROM organization_role_assignments WHERE membership_id = '" + o.adminMembership() + "' AND status = 1");
        String deB = value("SELECT assignment_id FROM organization_role_assignments WHERE membership_id = '" + kb.membership() + "' AND status = 1");
        CountDownLatch largada = new CountDownLatch(1);
        CompletableFuture<Integer> x = CompletableFuture.supplyAsync(() -> {
            await(largada);
            return ka.browser().post("/api/v1/o/" + o.id() + "/role-assignments/" + deB + "/revoke", null).status();
        });
        CompletableFuture<Integer> y = CompletableFuture.supplyAsync(() -> {
            await(largada);
            return kb.browser().post("/api/v1/o/" + o.id() + "/role-assignments/" + deA + "/revoke", null).status();
        });
        largada.countDown();
        List<Integer> st = List.of(x.orTimeout(60, TimeUnit.SECONDS).join(), y.orTimeout(60, TimeUnit.SECONDS).join()).stream().sorted().toList();
        assertThat(st).containsExactly(204, 403);
        assertThat(count("SELECT count(*) FROM organization_role_assignments a JOIN roles r ON r.role_id = a.role_id WHERE a.organization_id = '" + o.id()
                + "' AND r.code = 'ORG_ADMIN' AND a.status = 1")).isEqualTo(1);
        assertThat(events(o.id(), 6)).isEqualTo(1);
    }

    /**
     * Concessao enquanto o autor perde a autoridade: uma transacao (superusuario, como o ato de revogacao faria) trava a
     * organizacao e revoga o ORG_ADMIN de B; a atribuicao pedida por B espera o lock (observado); com o commit, B e
     * reconferido depois do lock e recusado (403). Nenhuma atribuicao nova nem evento 5.
     */
    @Test
    void atribuicaoEsperaAPerdaDeAutoridadeDoAutorERecusa() throws Exception {
        Org o = newOrganization();
        UUID ws = newWorkspace(o, null);
        TestUser b = internalUser(o, true, 2);
        TestUser alvo = internalUser(o, true, 2);
        grantRole(o, b.id(), "ORG_ADMIN", null);
        Actor kb = login(o, b);
        stepUp(o, kb);
        try (Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT 1 FROM organizations WHERE organization_id = '" + o.id() + "' FOR UPDATE");
                st.execute("UPDATE organization_role_assignments SET status = 2, revoked_at = now(), revoked_by_membership_id = '" + o.adminMembership()
                        + "' WHERE membership_id = '" + kb.membership() + "' AND status = 1");
            }
            CompletableFuture<BrowserClient.Response> r = CompletableFuture.supplyAsync(() -> assign(o, kb, alvo.id(), "VIEWER", ws));
            assertThat(waitingOnLock()).as("a atribuicao espera o lock da organizacao").isTrue();
            c.commit();
            assertThat(r.orTimeout(60, TimeUnit.SECONDS).join().status()).isEqualTo(403);
        }
        assertThat(activeRoles(alvo.id())).isZero();
        assertThat(events(o.id(), 5)).isZero();
    }

    // ------------------------------------------------------------------ retirada de vinculo

    /**
     * Retirada do vinculo (conta da organizacao, entrada local): sessoes do alvo caem na requisicao seguinte, papeis
     * revogados, desafios invalidados, evento 8 e aviso. Sem org.manage: 403. Proprio ator: 403. Repeticao: 204 sem evento.
     */
    @Test
    void retiradaDeVinculoDerrubaSessoesRevogaPapeisEAudita() throws Exception {
        Org o = newOrganization();
        UUID ws = newWorkspace(o, null);
        TestUser alvo = internalUser(o, true, 2);
        TestUser comum = internalUser(o, true, 2);
        grantRole(o, alvo.id(), "OPERATOR", ws);
        Actor t = login(o, alvo);
        assertThat(t.browser().get("/api/v1/o/" + o.id() + "/me").status()).isEqualTo(200);
        Actor c = login(o, comum);
        assertThat(c.browser().post("/api/v1/o/" + o.id() + "/users/" + alvo.id() + "/membership/revoke", null).status()).as("sem org.manage").isEqualTo(403);
        Actor a = admin(o);
        assertThat(a.browser().post("/api/v1/o/" + o.id() + "/users/" + o.adminId() + "/membership/revoke", null).status()).as("proprio").isEqualTo(403);
        assertThat(a.browser().post("/api/v1/o/" + o.id() + "/users/" + alvo.id() + "/membership/revoke", null).status()).isEqualTo(204);
        assertThat(t.browser().get("/api/v1/o/" + o.id() + "/me").status()).as("sessao do alvo cai").isEqualTo(401);
        assertThat(value("SELECT status FROM organization_memberships WHERE user_id = '" + alvo.id() + "'")).isEqualTo("4");
        assertThat(activeRoles(alvo.id())).isZero();
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + alvo.id() + "' AND revoked_at IS NULL")).isZero();
        assertThat(events(o.id(), 8)).isEqualTo(1);
        assertThat(testMail.sent.stream().anyMatch(m -> m.kind() == MailSender.Kind.NOTICE_ORG_MEMBERSHIP_REVOKED && alvo.email().equals(m.recipient()))).isTrue();
        assertThat(a.browser().post("/api/v1/o/" + o.id() + "/users/" + alvo.id() + "/membership/revoke", null).status()).isEqualTo(204);
        assertThat(events(o.id(), 8)).isEqualTo(1);
        BrowserClient.Response nova = login(o, internalUser(o, true, 2)).browser().get("/api/v1/o/" + o.id() + "/me");
        assertThat(nova.status()).as("outras contas seguem").isEqualTo(200);
    }

    /** Garantia do banco: a Platform nao grava atribuicao com autor igual ao alvo nem com autor sem autoridade (42501). */
    @Test
    void bancoRecusaAutoatribuicaoEAutorSemAutoridade() throws Exception {
        Org o = newOrganization();
        UUID ws = newWorkspace(o, null);
        TestUser comum = internalUser(o, true, 2);
        UUID m = membershipOf(comum.id());
        String role = value("SELECT role_id FROM roles WHERE code = 'VIEWER'");
        assertThat(AccessInvitationsIT.platformSql(o.id(), "INSERT INTO workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status,"
                + " assigned_by_membership_id) VALUES ('" + o.id() + "', '" + ws + "', '" + m + "', '" + role + "', 2, 1, '" + m + "')")).isEqualTo("42501");
        TestUser outro = internalUser(o, true, 2);
        assertThat(AccessInvitationsIT.platformSql(o.id(), "INSERT INTO workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status,"
                + " assigned_by_membership_id) VALUES ('" + o.id() + "', '" + ws + "', '" + membershipOf(outro.id()) + "', '" + role + "', 2, 1, '" + m + "')"))
                .as("autor sem papel").isEqualTo("42501");
        assertThat(AccessInvitationsIT.platformSql(o.id(), "INSERT INTO workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status,"
                + " assigned_by_membership_id) VALUES ('" + o.id() + "', '" + ws + "', '" + membershipOf(outro.id()) + "', '" + role + "', 2, 1, '"
                + o.adminMembership() + "')")).as("controle positivo: ORG_ADMIN como autor").isEqualTo("OK");
    }

    private static boolean waitingOnLock() throws Exception {
        for (int i = 0; i < 100; i++) {
            if ("t".equals(value("SELECT count(*) >= 1 FROM pg_stat_activity WHERE usename = 'nekkora_platform' AND wait_event_type = 'Lock'"))) {
                return true;
            }
            pause(50);
        }
        return false;
    }

    private static void await(CountDownLatch l) {
        try {
            l.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String value(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static long count(String sql) throws Exception {
        String v = value(sql);
        return v == null ? 0 : Long.parseLong(v);
    }
}
