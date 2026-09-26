package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nekkora.platform.mail.MailSender;
import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;

/**
 * Fluxo de autenticacao da Platform, ponta a ponta pela API, com o papel real nekkora_platform (contrato 8.6/8.7/8.10,
 * revisao 4). Verificacoes de estado no banco sao feitas como superusuario (declarado: leitura de verificacao).
 */
class AuthenticationFlowIT extends PlatformTestBase {

    private static Org org;
    private static UUID client;
    private static UUID ws;

    @BeforeAll
    static void fixture() {
        org = newOrganization();
        client = newClient(org);
        ws = newWorkspace(org, client);
    }

    @Test
    void firstLoginEnrollsMfaAndCreatesSessionOnlyAfterSecondFactor() throws Exception {
        TestUser u = externalUser(org, client, ws);
        BrowserClient n = browser();
        BrowserClient.Response step1 = passwordLogin(n, org.id(), u);
        assertThat(step1.status()).isEqualTo(200);
        assertThat(step1.field("status")).isEqualTo("mfa_enrollment_required");
        assertThat(n.cookies()).containsKey("__Host-nekkora_mfa").doesNotContainKey("__Host-nekkora_session");
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + u.id() + "'")).as("nenhuma sessao antes do MFA").isZero();
        assertThat(count("SELECT count(*) FROM auth_challenges WHERE user_id = '" + u.id() + "' AND credential_hash IS NOT NULL")).isEqualTo(1);
        assertThat(step1.setCookies()).anySatisfy(c -> assertThat(c).startsWith("__Host-nekkora_mfa=")
                .contains("Path=/").contains("Secure").contains("HttpOnly").contains("SameSite=Strict").doesNotContain("Domain"));

        n.cookies().clear();
        List<String> codes = firstLogin(n, org.id(), externalUser(org, client, ws));
        assertThat(codes).hasSize(10).allMatch(c -> c.matches("^[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}$"));
        String token = n.cookies().get("__Host-nekkora_session");
        assertThat(Tokens.isValidFormat(token)).as("token de sessao no contrato D2").isTrue();
        assertThat(count("SELECT count(*) FROM user_sessions WHERE session_token_hash = '" + Tokens.sha256Hex(token)
                + "' AND mfa_verified_at IS NOT NULL")).as("banco guarda so o SHA-256 hex minusculo").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM user_sessions WHERE session_token_hash = '" + token + "'")).as("token bruto nunca persistido").isZero();
        BrowserClient.Response me = n.get("/api/v1/o/" + org.id() + "/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body()).contains("\"accountType\":\"external\"").doesNotContain(token);
        assertThat(me.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(me.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("JSESSIONID"));
    }

    @Test
    void genericResponsesWithoutEnumeration() throws Exception {
        TestUser valid = internalUser(org, true, 2);
        TestUser unverified = internalUser(org, false, 2);
        TestUser suspended = internalUser(org, true, 3);
        UUID noMembership = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('"
                + noMembership + "', '" + org.id() + "', 1, 'sem-vinculo-" + noMembership.toString().substring(0, 6) + "@o.test', now(), 2, 'x')",
                "INSERT INTO user_credentials (user_id, password_hash) VALUES ('" + noMembership + "', '" + HASHER.generate(PASSWORD.toCharArray()) + "')");
        List<BrowserClient.Response> rs = new ArrayList<>();
        rs.add(passwordLogin(browser(), org.id(), new TestUser(null, "inexistente-" + UUID.randomUUID() + "@o.test", PASSWORD.toCharArray())));
        rs.add(passwordLogin(browser(), org.id(), new TestUser(null, valid.email(), "senha-errada-123456".toCharArray())));
        rs.add(passwordLogin(browser(), org.id(), unverified));
        rs.add(passwordLogin(browser(), org.id(), suspended));
        rs.add(passwordLogin(browser(), org.id(), new TestUser(null, "sem-vinculo-" + noMembership.toString().substring(0, 6) + "@o.test", PASSWORD.toCharArray())));
        Org other = newOrganization();
        rs.add(passwordLogin(browser(), other.id(), valid));
        for (BrowserClient.Response r : rs) {
            assertThat(r.status()).isEqualTo(401);
            assertThat(r.body()).isEqualTo("{\"error\":\"unauthenticated\"}");
            assertThat(r.setCookies()).noneMatch(c -> c.startsWith("__Host-nekkora_mfa="));
        }
        assertThat(passwordLogin(browser(), org.id(), valid).status()).as("a propria conta valida ainda entra").isEqualTo(200);
    }

    @Test
    void nonexistentAccountTimingWithinWrongPasswordBand() throws Exception {
        TestUser u = internalUser(org, true, 2);
        long[] nonexistent = new long[4];
        long[] wrongPassword = new long[4];
        for (int i = 0; i < 4; i++) {
            BrowserClient a = browser();
            a.forwardedIp = "10.9.0." + i;
            fetchPreAuthCsrf(a);
            long t0 = System.nanoTime();
            a.post("/api/v1/o/" + org.id() + "/auth/login", "{\"email\":\"nao-existe-" + i + "@o.test\",\"password\":\"qualquer-senha-12\"}");
            nonexistent[i] = System.nanoTime() - t0;
            BrowserClient b = browser();
            b.forwardedIp = "10.9.1." + i;
            fetchPreAuthCsrf(b);
            t0 = System.nanoTime();
            b.post("/api/v1/o/" + org.id() + "/auth/login", "{\"email\":\"" + u.email() + "\",\"password\":\"qualquer-senha-12\"}");
            wrongPassword[i] = System.nanoTime() - t0;
        }
        java.util.Arrays.sort(nonexistent);
        java.util.Arrays.sort(wrongPassword);
        double ratio = (double) nonexistent[2] / wrongPassword[2];
        assertThat(ratio).as("mediana conta inexistente / senha errada (hash ficticio com o mesmo custo)").isBetween(0.5, 2.0);
        assertThat(nonexistent[2] / 1_000_000).as("conta inexistente calcula Argon2 (ms)").isGreaterThan(50);
    }

    @Test
    void wrongCodePersistsAttemptsAndLocksOnFifth() throws Exception {
        TestUser u = internalUser(org, true, 2);
        firstLogin(browser(), org.id(), u);
        BrowserClient n = browser();
        n.forwardedIp = "10.20.0.1";
        assertThat(passwordLogin(n, org.id(), u).status()).isEqualTo(200);
        for (int i = 1; i <= 5; i++) {
            BrowserClient.Response r = n.post("/api/v1/o/" + org.id() + "/auth/mfa/verify", "{\"code\":\"000000\"}");
            assertThat(r.status()).isEqualTo(401);
            assertThat(count("SELECT attempts FROM auth_challenges WHERE user_id = '" + u.id() + "' AND consumed_at IS NULL ORDER BY created_at DESC LIMIT 1"))
                    .as("tentativa %s persistida apesar do 401", i).isEqualTo(i);
        }
        assertThat(count("SELECT count(*) FROM auth_challenges WHERE user_id = '" + u.id() + "' AND invalidated_at IS NOT NULL AND attempts = 5 AND credential_hash IS NULL"))
                .as("quinta tentativa invalida o desafio e apaga os hashes").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM mfa_events WHERE user_id = '" + u.id() + "' AND event_type = 5")).as("evento challenge_locked").isEqualTo(1);
        n.forwardedIp = "10.20.0.2"; // outro IP: fora do R1 (identidade+IP) ja esgotado pelas 5 falhas
        BrowserClient.Response correct = n.post("/api/v1/o/" + org.id() + "/auth/mfa/verify", "{\"code\":\"" + code(u.id()) + "\"}");
        assertThat(correct.status()).as("desafio invalidado: nem o codigo certo cria sessao").isEqualTo(401);
    }

    @Test
    void totpStepCannotBeReused() throws Exception {
        TestUser u = internalUser(org, true, 2);
        firstLogin(browser(), org.id(), u);
        BrowserClient a = browser();
        passwordLogin(a, org.id(), u);
        String c = code(u.id());
        assertThat(a.post("/api/v1/o/" + org.id() + "/auth/mfa/verify", "{\"code\":\"" + c + "\"}").status()).isEqualTo(200);
        BrowserClient b = browser();
        passwordLogin(b, org.id(), u);
        assertThat(b.post("/api/v1/o/" + org.id() + "/auth/mfa/verify", "{\"code\":\"" + c + "\"}").status())
                .as("mesmo OTP de novo (RFC 6238): rejeitado").isEqualTo(401);
    }

    @Test
    void concurrentConfirmationsOfSameChallengeCreateOneSession() throws Exception {
        TestUser u = internalUser(org, true, 2);
        firstLogin(browser(), org.id(), u);
        BrowserClient n = browser();
        passwordLogin(n, org.id(), u);
        String c = code(u.id());
        long before = count("SELECT count(*) FROM user_sessions WHERE user_id = '" + u.id() + "'");
        ExecutorService ex = Executors.newFixedThreadPool(4);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            BrowserClient copy = browser();
            copy.cookies().putAll(n.cookies());
            copy.csrf = n.csrf;
            fs.add(ex.submit((Callable<Integer>) () -> copy.post("/api/v1/o/" + org.id() + "/auth/mfa/verify", "{\"code\":\"" + c + "\"}").status()));
        }
        List<Integer> status = new ArrayList<>();
        for (Future<Integer> f : fs) {
            status.add(f.get());
        }
        ex.shutdown();
        assertThat(status).as("cada requisicao concorrente verificada separadamente").filteredOn(s -> s == 200).hasSize(1);
        assertThat(status).filteredOn(s -> s == 401).hasSize(3);
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + u.id() + "'") - before).isEqualTo(1);
    }

    @Test
    void recoveryCodeRevokesOtherSessionsAndIsSingleUse() throws Exception {
        TestUser u = internalUser(org, true, 2);
        BrowserClient first = browser();
        List<String> codes = firstLogin(first, org.id(), u);
        BrowserClient n = browser();
        passwordLogin(n, org.id(), u);
        BrowserClient.Response r = n.post("/api/v1/o/" + org.id() + "/auth/mfa/verify", "{\"recoveryCode\":\"" + codes.get(0) + "\"}");
        assertThat(r.status()).isEqualTo(200);
        assertThat(first.get("/api/v1/o/" + org.id() + "/me").status()).as("sessao anterior revogada").isEqualTo(401);
        assertThat(n.get("/api/v1/o/" + org.id() + "/me").status()).isEqualTo(200);
        assertThat(testMail.sent).anyMatch(m -> m.recipient().equals(u.email()) && m.kind() == MailSender.Kind.NOTICE_RECOVERY_CODE_USED);
        BrowserClient other = browser();
        passwordLogin(other, org.id(), u);
        assertThat(other.post("/api/v1/o/" + org.id() + "/auth/mfa/verify", "{\"recoveryCode\":\"" + codes.get(0) + "\"}").status())
                .as("codigo de uso unico").isEqualTo(401);
    }

    @Test
    void credentialChangedDuringChallengePreventsSession() throws Exception {
        TestUser u = internalUser(org, true, 2);
        firstLogin(browser(), org.id(), u);
        BrowserClient n = browser();
        passwordLogin(n, org.id(), u);
        DB.asSuperuser("UPDATE user_credentials SET password_hash = '" + HASHER.generate("outra-senha-longa-9".toCharArray()) + "' WHERE user_id = '" + u.id() + "'");
        BrowserClient.Response r = n.post("/api/v1/o/" + org.id() + "/auth/mfa/verify", "{\"code\":\"" + code(u.id()) + "\"}");
        assertThat(r.status()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM auth_challenges WHERE user_id = '" + u.id() + "' AND invalidated_at IS NOT NULL")).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + u.id() + "' AND revoked_at IS NULL")).as("so a sessao do primeiro acesso").isEqualTo(1);
    }

    @Test
    void rehashOnlyUpwardAndAppliedAfterMfa() throws Exception {
        TestUser weak = internalUser(org, true, 2);
        TestUser strong = internalUser(org, true, 2);
        firstLogin(browser(), org.id(), weak);
        firstLogin(browser(), org.id(), strong);
        com.nekkora.platform.auth.crypto.PasswordHasher floor = new com.nekkora.platform.auth.crypto.PasswordHasher(
                new com.nekkora.platform.auth.crypto.PasswordHasher.Parameters(19456, 2, 1), 2, 2, 5000);
        com.nekkora.platform.auth.crypto.PasswordHasher above = new com.nekkora.platform.auth.crypto.PasswordHasher(
                new com.nekkora.platform.auth.crypto.PasswordHasher.Parameters(131072, 3, 1), 2, 2, 5000);
        DB.asSuperuser("UPDATE user_credentials SET password_hash = '" + floor.generate(PASSWORD.toCharArray()) + "' WHERE user_id = '" + weak.id() + "'",
                "UPDATE user_credentials SET password_hash = '" + above.generate(PASSWORD.toCharArray()) + "' WHERE user_id = '" + strong.id() + "'");
        BrowserClient n = browser();
        passwordLogin(n, org.id(), weak);
        assertThat(queryText("SELECT password_hash FROM user_credentials WHERE user_id = '" + weak.id() + "'")).as("antes do MFA: inalterado")
                .contains("m=19456,t=2,p=1");
        assertThat(queryText("SELECT pending_password_hash FROM auth_challenges WHERE user_id = '" + weak.id() + "' AND consumed_at IS NULL AND invalidated_at IS NULL"))
                .contains("m=65536,t=3,p=1");
        n.post("/api/v1/o/" + org.id() + "/auth/mfa/verify", "{\"code\":\"" + code(weak.id()) + "\"}");
        assertThat(queryText("SELECT password_hash FROM user_credentials WHERE user_id = '" + weak.id() + "'")).as("depois do MFA: atualizado")
                .contains("m=65536,t=3,p=1");
        assertThat(fullLogin(browser(), org.id(), strong).status()).isEqualTo(200);
        assertThat(queryText("SELECT password_hash FROM user_credentials WHERE user_id = '" + strong.id() + "'")).as("mais forte que o vigente: sem downgrade")
                .contains("m=131072,t=3,p=1");
    }

    @Test
    void csrfOriginAndTokenPerContext() throws Exception {
        TestUser u = internalUser(org, true, 2);
        BrowserClient noToken = browser();
        noToken.get("/api/v1/csrf");
        assertThat(noToken.post("/api/v1/o/" + org.id() + "/auth/login", "{\"email\":\"" + u.email() + "\",\"password\":\"" + PASSWORD + "\"}").status())
                .as("sem X-CSRF-Token").isEqualTo(403);
        BrowserClient otherOrigin = browser();
        fetchPreAuthCsrf(otherOrigin);
        otherOrigin.origin = "https://atacante.test";
        assertThat(otherOrigin.post("/api/v1/o/" + org.id() + "/auth/login", "{\"email\":\"" + u.email() + "\",\"password\":\"" + PASSWORD + "\"}").status())
                .as("Origin diferente").isEqualTo(403);
        BrowserClient noOrigin = browser();
        fetchPreAuthCsrf(noOrigin);
        noOrigin.origin = null;
        assertThat(noOrigin.post("/api/v1/o/" + org.id() + "/auth/login", "{\"email\":\"" + u.email() + "\",\"password\":\"" + PASSWORD + "\"}").status())
                .as("sem Origin nem Referer").isEqualTo(403);
        BrowserClient a = browser();
        fetchPreAuthCsrf(a);
        BrowserClient b = browser();
        fetchPreAuthCsrf(b);
        a.csrf = b.csrf;
        assertThat(a.post("/api/v1/o/" + org.id() + "/auth/login", "{\"email\":\"" + u.email() + "\",\"password\":\"" + PASSWORD + "\"}").status())
                .as("token de outro contexto pre-autenticacao").isEqualTo(403);
        BrowserClient s = browser();
        firstLogin(s, org.id(), u);
        String sessionToken = s.csrf;
        s.csrf = null;
        assertThat(s.post("/api/v1/o/" + org.id() + "/auth/sessions/revoke-all", null).status()).as("escrita autenticada sem token").isEqualTo(403);
        s.csrf = sessionToken;
        assertThat(s.get("/api/v1/o/" + org.id() + "/auth/csrf").field("csrfToken")).isEqualTo(sessionToken);
        BrowserClient anon = browser();
        anon.csrf = null;
        assertThat(anon.post("/api/v1/o/" + org.id() + "/auth/logout", null).status()).as("logout sem sessao: no-op").isEqualTo(204);
    }

    static long count(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    static String queryText(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
