package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nekkora.platform.mail.MailSender;
import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;

/**
 * Sessao revalidada a cada requisicao, revogacoes (§11.4), suspensao/reativacao, troca e recuperacao de senha, e
 * reset administrativo de MFA com step-up (contrato, §3.4). Estados alterados como superusuario sao DECLARADOS
 * como preparacao (simulam acoes de outros sistemas ou o passar do tempo); o que se verifica passa pela API.
 */
class SessionsAndAdministrationIT extends PlatformTestBase {

    /** Corpo do reset administrativo com o motivo codificado obrigatorio (016, D5). */
    static final String RESET_BODY = "{\"reason\":\"DISPOSITIVO_PERDIDO\"}";

    private static Org org;

    @org.springframework.beans.factory.annotation.Autowired
    com.nekkora.core.db.OrgTransactions tx;

    @org.springframework.beans.factory.annotation.Autowired
    com.nekkora.platform.session.SessionRepository sessions;

    @BeforeAll
    static void fixture() {
        org = newOrganization();
    }

    private String me(BrowserClient n) {
        return Integer.toString(n.get("/api/v1/o/" + org.id() + "/me").status());
    }

    @Test
    void revalidatesEveryRequestAndObservesStateChanges() throws Exception {
        UUID client = newClient(org);
        TestUser u = externalUser(org, client, newWorkspace(org, client));
        BrowserClient n = browser();
        firstLogin(n, org.id(), u);
        assertThat(me(n)).isEqualTo("200");
        DB.asSuperuser("UPDATE client_memberships SET status = 3 WHERE user_id = '" + u.id() + "'");
        assertThat(me(n)).as("vinculo suspenso: a proxima requisicao falha").isEqualTo("401");
        DB.asSuperuser("UPDATE client_memberships SET status = 2 WHERE user_id = '" + u.id() + "'");
        assertThat(me(n)).isEqualTo("200");
        DB.asSuperuser("UPDATE clients SET status = 2 WHERE client_id = '" + client + "'");
        assertThat(me(n)).as("cliente suspenso").isEqualTo("401");
        DB.asSuperuser("UPDATE clients SET status = 1 WHERE client_id = '" + client + "'");
        String hash = Tokens.sha256Hex(n.cookies().get("__Host-nekkora_session"));
        ageSession(hash, "16 minutes");
        assertThat(me(n)).as("externa inativa > 15 min").isEqualTo("401");
        assertThat(AuthenticationFlowIT.queryText("SELECT (last_seen_at < now() - interval '15 minutes')::text FROM user_sessions WHERE session_token_hash = '" + hash + "'"))
                .as("sessao inativa nao e revivida").isEqualTo("true");
    }

    @Test
    void absoluteLifetimeAndThirtyMinuteInactivity() throws Exception {
        TestUser u = internalUser(org, true, 2);
        BrowserClient n = browser();
        firstLogin(n, org.id(), u);
        String hash = Tokens.sha256Hex(n.cookies().get("__Host-nekkora_session"));
        ageSession(hash, "20 minutes");
        assertThat(me(n)).as("interna: 20 min ainda dentro dos 30").isEqualTo("200");
        ageSession(hash, "12 hours");
        assertThat(me(n)).as("prazo absoluto vencido").isEqualTo("401");
        assertThat(n.cookies().get("__Host-nekkora_session")).isNotNull();
        assertThat(n.get("/api/v1/o/" + org.id() + "/me").setCookies()).as("a API nunca renova o cookie").isEmpty();
    }

    @Test
    void logoutRevokeAllAndPasswordChange() throws Exception {
        TestUser u = internalUser(org, true, 2);
        BrowserClient a = browser();
        firstLogin(a, org.id(), u);
        BrowserClient b = browser();
        assertThat(fullLogin(b, org.id(), u).status()).isEqualTo(200);
        BrowserClient c = browser();
        assertThat(fullLogin(c, org.id(), u).status()).isEqualTo(200);

        BrowserClient.Response change = a.post("/api/v1/o/" + org.id() + "/auth/password",
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"nova-senha-bem-longa-2\"}");
        assertThat(change.status()).isEqualTo(204);
        assertThat(me(a)).as("troca de senha: a sessao atual continua").isEqualTo("200");
        assertThat(me(b)).as("troca de senha: as outras sao revogadas").isEqualTo("401");
        assertThat(me(c)).isEqualTo("401");
        TestUser updatedUser = new TestUser(u.id(), u.email(), "nova-senha-bem-longa-2".toCharArray());
        BrowserClient d = browser();
        assertThat(passwordLogin(d, org.id(), new TestUser(u.id(), u.email(), PASSWORD.toCharArray())).status()).as("senha antiga").isEqualTo(401);
        assertThat(fullLogin(d, org.id(), updatedUser).status()).isEqualTo(200);
        assertThat(a.post("/api/v1/o/" + org.id() + "/auth/password", "{\"currentPassword\":\"errada-errada-1\",\"newPassword\":\"outra-senha-longa-3\"}").status())
                .isEqualTo(401);
        assertThat(a.post("/api/v1/o/" + org.id() + "/auth/password", "{\"currentPassword\":\"nova-senha-bem-longa-2\",\"newPassword\":\"curta\"}").status())
                .as("politica: minimo 12 pontos de codigo").isEqualTo(400);

        assertThat(a.post("/api/v1/o/" + org.id() + "/auth/logout", null).status()).isEqualTo(204);
        assertThat(a.cookies()).doesNotContainKey("__Host-nekkora_session");
        assertThat(me(d)).as("logout so da atual").isEqualTo("200");
        assertThat(d.post("/api/v1/o/" + org.id() + "/auth/sessions/revoke-all", null).status()).isEqualTo(204);
        assertThat(me(d)).isEqualTo("401");
    }

    @Test
    void passwordRecoveryWithoutEnumerationRevokesAllAndKeepsMfa() throws Exception {
        TestUser u = internalUser(org, true, 2);
        BrowserClient s = browser();
        firstLogin(s, org.id(), u);
        int before = testMail.sent.size();
        BrowserClient x = browser();
        fetchPreAuthCsrf(x);
        long t0 = System.nanoTime();
        BrowserClient.Response existing = x.post("/api/v1/o/" + org.id() + "/auth/password-reset/request", "{\"email\":\"" + u.email() + "\"}");
        long tExisting = System.nanoTime() - t0;
        BrowserClient y = browser();
        fetchPreAuthCsrf(y);
        t0 = System.nanoTime();
        BrowserClient.Response nonexistent = y.post("/api/v1/o/" + org.id() + "/auth/password-reset/request", "{\"email\":\"ninguem-" + UUID.randomUUID() + "@o.test\"}");
        long tNonexistent = System.nanoTime() - t0;
        assertThat(existing.status()).isEqualTo(202);
        assertThat(nonexistent.status()).isEqualTo(202);
        assertThat(existing.body()).isEqualTo(nonexistent.body());
        assertThat(tNonexistent / 1_000_000).as("piso de tempo tambem sem conta (ms)").isGreaterThanOrEqualTo(290);
        assertThat(tExisting / 1_000_000).isGreaterThanOrEqualTo(290);
        assertThat(testMail.sent.size() - before).as("so a conta existente recebe").isEqualTo(1);
        String token = testMail.sent.get(testMail.sent.size() - 1).token();
        assertThat(Tokens.isValidFormat(token)).isTrue();
        assertThat(AuthenticationFlowIT.count("SELECT count(*) FROM password_reset_tokens WHERE token_hash = '" + token + "'")).as("token bruto nao persistido").isZero();

        BrowserClient r = browser();
        fetchPreAuthCsrf(r);
        assertThat(r.post("/api/v1/o/" + org.id() + "/auth/password-reset/confirm", "{\"token\":\"" + token + "\",\"newPassword\":\"senha-recuperada-44\"}").status())
                .isEqualTo(204);
        assertThat(me(s)).as("recuperacao revoga todas as sessoes").isEqualTo("401");
        BrowserClient r2 = browser();
        fetchPreAuthCsrf(r2);
        assertThat(r2.post("/api/v1/o/" + org.id() + "/auth/password-reset/confirm", "{\"token\":\"" + token + "\",\"newPassword\":\"outra-coisa-longa-5\"}").status())
                .as("token de uso unico").isEqualTo(400);
        BrowserClient l = browser();
        BrowserClient.Response step1 = passwordLogin(l, org.id(), new TestUser(u.id(), u.email(), "senha-recuperada-44".toCharArray()));
        assertThat(step1.field("status")).as("a recuperacao de senha nao dispensa o MFA").isEqualTo("mfa_required");
    }

    @Test
    void adminMfaResetRequiresStepUpPermissionAndAnotherTarget() throws Exception {
        TestUser admin = new TestUser(org.adminId(), org.adminEmail(), PASSWORD.toCharArray());
        BrowserClient a = browser();
        firstLogin(a, org.id(), admin);
        TestUser target = internalUser(org, true, 2);
        BrowserClient t = browser();
        firstLogin(t, org.id(), target);
        String route = "/api/v1/o/" + org.id() + "/users/" + target.id() + "/mfa/reset";

        assertThat(a.post(route, RESET_BODY).status()).as("sem step-up").isEqualTo(403);
        assertThat(a.post("/api/v1/o/" + org.id() + "/auth/mfa/step-up", "{\"code\":\"000000\"}").status()).as("step-up com codigo errado").isEqualTo(403);
        assertThat(a.post("/api/v1/o/" + org.id() + "/auth/mfa/step-up", "{\"code\":\"" + code(admin.id()) + "\"}").status()).isEqualTo(204);
        assertThat(a.post("/api/v1/o/" + org.id() + "/users/" + admin.id() + "/mfa/reset", RESET_BODY).status()).as("o proprio fator").isEqualTo(403);

        TestUser regular = internalUser(org, true, 2);
        BrowserClient c = browser();
        firstLogin(c, org.id(), regular);
        c.post("/api/v1/o/" + org.id() + "/auth/mfa/step-up", "{\"code\":\"" + code(regular.id()) + "\"}");
        assertThat(c.post(route, RESET_BODY).status()).as("sem identity.mfa.reset na matriz").isEqualTo(403);

        int notices = testMail.sent.size();
        assertThat(a.post(route, RESET_BODY).status()).isEqualTo(204);
        assertThat(me(t)).as("sessoes do alvo revogadas").isEqualTo("401");
        assertThat(testMail.sent.subList(notices, testMail.sent.size())).anyMatch(m -> m.recipient().equals(target.email())
                && m.kind() == MailSender.Kind.NOTICE_MFA_RESET);
        assertThat(AuthenticationFlowIT.count("SELECT count(*) FROM mfa_events WHERE user_id = '" + target.id() + "' AND event_type = 3 AND actor_user_id = '"
                + admin.id() + "'")).isEqualTo(1);
        BrowserClient after = browser();
        assertThat(passwordLogin(after, org.id(), target).field("status")).as("proximo login: cadastro obrigatorio").isEqualTo("mfa_enrollment_required");

        // step-up vencido: o tempo e simulado recuando mfa_step_up_at (a trigger da 014 so permite avancar; desligada
        // so nesta preparacao, pelo superusuario, e religada em seguida)
        String hashAdmin = Tokens.sha256Hex(a.cookies().get("__Host-nekkora_session"));
        TestUser target2 = internalUser(org, true, 2);
        firstLogin(browser(), org.id(), target2);
        DB.asSuperuser("ALTER TABLE user_sessions DISABLE TRIGGER trg_user_sessions_prevent_reactivation",
                "UPDATE user_sessions SET mfa_step_up_at = now() - interval '6 minutes' WHERE session_token_hash = '" + hashAdmin + "'",
                "ALTER TABLE user_sessions ENABLE TRIGGER trg_user_sessions_prevent_reactivation");
        assertThat(a.post("/api/v1/o/" + org.id() + "/users/" + target2.id() + "/mfa/reset", RESET_BODY).status()).as("step-up de 6 min: vencido").isEqualTo(403);
    }

    /** 016, D5: motivo codificado obrigatorio e gravado; reset de outro ORG_ADMIN avisa os demais administradores. */
    @Test
    void adminMfaResetRequiresCodedReasonAndNotifiesOtherAdmins() throws Exception {
        Org o = newOrganization();
        TestUser admin = new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray());
        BrowserClient a = browser();
        firstLogin(a, o.id(), admin);
        // segundo ORG_ADMIN (atribuido pelo primeiro; preparacao declarada) e um terceiro, so para receber o aviso
        TestUser admin2 = internalUser(o, true, 2);
        TestUser admin3 = internalUser(o, true, 2);
        for (TestUser u : java.util.List.of(admin2, admin3)) {
            DB.asSuperuser("INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id) "
                    + "SELECT '" + o.id() + "', m.membership_id, r.role_id, 1, 1, '" + o.adminMembership() + "' FROM organization_memberships m, roles r "
                    + "WHERE m.user_id = '" + u.id() + "' AND r.code = 'ORG_ADMIN'");
        }
        firstLogin(browser(), o.id(), admin2);
        assertThat(a.post("/api/v1/o/" + o.id() + "/auth/mfa/step-up", "{\"code\":\"" + code(admin.id()) + "\"}").status()).isEqualTo(204);
        String route = "/api/v1/o/" + o.id() + "/users/" + admin2.id() + "/mfa/reset";
        assertThat(a.post(route, null).status()).as("sem motivo").isEqualTo(400);
        assertThat(a.post(route, "{}").status()).as("motivo ausente").isEqualTo(400);
        assertThat(a.post(route, "{\"reason\":\"PORQUE_SIM\"}").status()).as("motivo fora da lista").isEqualTo(400);
        assertThat(AuthenticationFlowIT.count("SELECT count(*) FROM user_mfa_factors WHERE user_id = '" + admin2.id() + "' AND disabled_at IS NULL"))
                .as("nada muda sem motivo valido").isEqualTo(1);
        int notices = testMail.sent.size();
        assertThat(a.post(route, "{\"reason\":\"SUSPEITA_COMPROMETIMENTO\"}").status()).isEqualTo(204);
        assertThat(AuthenticationFlowIT.count("SELECT count(*) FROM mfa_events WHERE user_id = '" + admin2.id() + "' AND event_type = 3 AND reason_code = 3"))
                .isEqualTo(1);
        var enviados = testMail.sent.subList(notices, testMail.sent.size());
        assertThat(enviados).anyMatch(m -> m.recipient().equals(admin2.email()) && m.kind() == MailSender.Kind.NOTICE_MFA_RESET);
        assertThat(enviados.stream().filter(m -> m.kind() == MailSender.Kind.NOTICE_MFA_RESET_ADMIN_TARGET).map(SentMessage::recipient).sorted().toList())
                .as("demais ORG_ADMIN efetivos, sem o alvo").isEqualTo(java.util.stream.Stream.of(admin.email(), admin3.email()).sorted().toList());
        assertThat(enviados).allMatch(m -> m.token() == null && m.contestToken() == null);

        // alvo que nao e ORG_ADMIN: so o aviso ao titular
        TestUser comum = internalUser(o, true, 2);
        firstLogin(browser(), o.id(), comum);
        int antes = testMail.sent.size();
        assertThat(a.post("/api/v1/o/" + o.id() + "/users/" + comum.id() + "/mfa/reset", "{\"reason\":\"OUTRO\"}").status()).isEqualTo(204);
        assertThat(testMail.sent.subList(antes, testMail.sent.size())).extracting(SentMessage::kind).containsExactly(MailSender.Kind.NOTICE_MFA_RESET);
    }

    @Test
    void suspensionRevokesAndReactivationDoesNotRestore() throws Exception {
        TestUser admin = newOrganizationAdmin();
        BrowserClient a = browser();
        firstLogin(a, orgAdmin.id(), admin);
        TestUser target = internalUser(orgAdmin, true, 2);
        BrowserClient t = browser();
        firstLogin(t, orgAdmin.id(), target);
        assertThat(a.post("/api/v1/o/" + orgAdmin.id() + "/users/" + target.id() + "/suspend", null).status()).isEqualTo(204);
        assertThat(t.get("/api/v1/o/" + orgAdmin.id() + "/me").status()).isEqualTo(401);
        assertThat(a.post("/api/v1/o/" + orgAdmin.id() + "/users/" + target.id() + "/reactivate", null).status()).isEqualTo(204);
        assertThat(t.get("/api/v1/o/" + orgAdmin.id() + "/me").status()).as("reativacao nao restaura sessao revogada").isEqualTo(401);
        assertThat(a.post("/api/v1/o/" + orgAdmin.id() + "/users/" + admin.id() + "/suspend", null).status()).as("a propria conta").isEqualTo(403);
        assertThat(t.post("/api/v1/o/" + orgAdmin.id() + "/users/" + admin.id() + "/suspend", null).status()).as("sem sessao").isEqualTo(401);
    }

    @Test
    void tenSessionLimitRevokesOldest() throws Exception {
        TestUser u = internalUser(org, true, 2);
        firstLogin(browser(), org.id(), u);
        UUID first = UUID.fromString(AuthenticationFlowIT.queryText("SELECT session_id FROM user_sessions WHERE user_id = '" + u.id() + "' AND revoked_at IS NULL"));
        // 9 sessoes pelo repositorio REAL da aplicacao (mesmo SQL, papel nekkora_platform): 10 logins por TOTP
        // exigiriam esperar passos de 30 s (anti-replay). A 11a vem pela API.
        for (int i = 0; i < 9; i++) {
            tx.inOrganization(org.id(), com.nekkora.core.db.OrgTransactions.Mode.READ_WRITE,
                    j -> sessions.create(j, org.id(), u.id(), (short) 1, Tokens.sha256Hex(Tokens.newToken()), Tokens.newToken()));
        }
        assertThat(AuthenticationFlowIT.count("SELECT count(*) FROM user_sessions WHERE user_id = '" + u.id() + "' AND revoked_at IS NULL")).isEqualTo(10);
        assertThat(fullLogin(browser(), org.id(), u).status()).isEqualTo(200);
        assertThat(AuthenticationFlowIT.count("SELECT count(*) FROM user_sessions WHERE user_id = '" + u.id() + "' AND revoked_at IS NULL")).isEqualTo(10);
        assertThat(AuthenticationFlowIT.queryText("SELECT (revoked_at IS NOT NULL)::text FROM user_sessions WHERE session_id = '" + first + "'"))
                .as("a mais antiga foi revogada").isEqualTo("true");
    }

    private static Org orgAdmin;

    /**
     * Simula a passagem do tempo para UMA sessao (preparacao declarada, superusuario): recua created_at,
     * last_seen_at e expires_at juntos (a CHECK last_seen_at >= created_at da 002 impede recuar so a atividade;
     * a trigger da 014 impede mexer em created_at e e desligada so nesta preparacao).
     */
    private static void ageSession(String hash, String interval) {
        DB.asSuperuser("ALTER TABLE user_sessions DISABLE TRIGGER trg_user_sessions_prevent_reactivation",
                "UPDATE user_sessions SET created_at = created_at - interval '" + interval + "', last_seen_at = last_seen_at - interval '" + interval
                        + "', expires_at = expires_at - interval '" + interval + "', mfa_verified_at = mfa_verified_at - interval '" + interval
                        + "' WHERE session_token_hash = '" + hash + "'",
                "ALTER TABLE user_sessions ENABLE TRIGGER trg_user_sessions_prevent_reactivation");
    }

    private static TestUser newOrganizationAdmin() {
        orgAdmin = newOrganization();
        return new TestUser(orgAdmin.adminId(), orgAdmin.adminEmail(), PASSWORD.toCharArray());
    }
}
