package com.nekkora.platform.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import java.net.http.HttpClient;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.platform.auth.crypto.Base32;
import com.nekkora.platform.auth.crypto.PasswordHasher;
import com.nekkora.platform.auth.crypto.Totp;

/**
 * Ponta a ponta ENTRE PROCESSOS, pela stack de api/e2e (run.sh): nginx de origem unica com TLS -> Platform
 * (nekkora_platform) e Portal (processo e papel nekkora_portal proprios) -> PostgreSQL 16 com 001-014 e Redis.
 * O cliente so fala com o nginx; o banco e acessado so para a fixture (superusuario, DECLARADO) e nao ha
 * nenhuma chamada direta as aplicacoes. Fora do padrao do surefire (*Test/*IT): pulado sem as variaveis E2E_*.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrossProcessE2E {

    static final String BASE = System.getenv("E2E_BASE");
    static final UUID ORG = UUID.randomUUID();
    static final UUID ADMIN = UUID.randomUUID();
    static final UUID ADMIN_MEMBERSHIP = UUID.randomUUID();
    static final UUID CLIENT = UUID.randomUUID();
    static final UUID CLIENT2 = UUID.randomUUID();
    static final UUID WS1 = UUID.randomUUID();
    static final UUID WS_OTHER = UUID.randomUUID();
    static final UUID EXT = UUID.randomUUID();
    static final String EMAIL = "ext-" + EXT.toString().substring(0, 8) + "@cliente.test";
    static final String PASSWORD = "senha-e2e-longa-" + UUID.randomUUID().toString().substring(0, 8);
    static final List<UUID> EXECS = new ArrayList<>();
    static UUID execOther;

    /** Todo valor secreto visto pelo cliente: nenhum pode aparecer em log de nenhum container. */
    static final List<String> SEEN_SECRETS = new ArrayList<>();
    static HttpClient http;
    static byte[] totpSecret;
    static long lastStep = Long.MIN_VALUE;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(BASE != null, "E2E_BASE ausente: rode api/e2e/run.sh");
        try (InputStream in = Files.newInputStream(Path.of(System.getenv("E2E_CERT")))) {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("e2e", CertificateFactory.getInstance("X.509").generateCertificate(in));
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, tmf.getTrustManagers(), null);
            http = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        }
        fixture();
    }

    // ------------------------------------------------------------------ fixture (superusuario, declarada)

    static void sql(String... commands) throws Exception {
        try (Connection c = DriverManager.getConnection(System.getenv("E2E_PG_URL"), "nekkora", System.getenv("E2E_PG_SUPER_SENHA"));
             Statement st = c.createStatement()) {
            for (String s : commands) {
                st.execute(s);
            }
        }
    }

    static void fixture() throws Exception {
        String o = "'" + ORG + "'";
        String hash = new PasswordHasher(new PasswordHasher.Parameters(65536, 3, 1), 1, 1, 5000).generate(PASSWORD.toCharArray());
        UUID cm = UUID.randomUUID();
        sql("INSERT INTO organizations (organization_id, name, status) VALUES (" + o + ", 'Org E2E', 1)",
                "INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('" + ADMIN + "', " + o
                        + ", 1, 'adm-" + ADMIN.toString().substring(0, 6) + "@o.test', now(), 2, 'Adm')",
                "INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('" + ADMIN_MEMBERSHIP + "', " + o + ", '" + ADMIN + "', 1, 2)",
                "INSERT INTO clients (client_id, organization_id, name, status) VALUES ('" + CLIENT + "', " + o + ", 'C1', 1), ('" + CLIENT2 + "', " + o + ", 'C2', 1)",
                "INSERT INTO workspaces (workspace_id, organization_id, client_id, name, status) VALUES ('" + WS1 + "', " + o + ", '" + CLIENT + "', 'WS1', 1), ('"
                        + WS_OTHER + "', " + o + ", '" + CLIENT2 + "', 'WS outro cliente', 1)",
                "INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('" + EXT + "', " + o + ", 2, '"
                        + EMAIL + "', now(), 2, 'Pessoa externa')",
                "INSERT INTO user_credentials (user_id, password_hash) VALUES ('" + EXT + "', '" + hash + "')",
                "INSERT INTO client_memberships (client_membership_id, organization_id, client_id, user_id, account_type, status) VALUES ('" + cm + "', " + o + ", '"
                        + CLIENT + "', '" + EXT + "', 2, 2)",
                "INSERT INTO client_workspace_grants (organization_id, client_id, client_membership_id, workspace_id, status, approved_by_membership_id, approved_at, "
                        + "starts_at, next_review_at) VALUES (" + o + ", '" + CLIENT + "', '" + cm + "', '" + WS1 + "', 2, '" + ADMIN_MEMBERSHIP + "', now() - interval '1 day', "
                        + "now() - interval '1 day', now() + interval '30 days')");
        for (int code = 1; code <= 5; code++) {
            sql("INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, limit_value, "
                    + "provisioned_by_user_id) VALUES (" + o + ", " + code + ", 1, now(), 2, NULL, '" + ADMIN + "')");
        }
        UUID automation1 = createAutomation(WS1);
        for (int i = 0; i < 3; i++) {
            EXECS.add(execution(WS1, automation1, i));
        }
        execOther = execution(WS_OTHER, createAutomation(WS_OTHER), 9);
    }

    static UUID createAutomation(UUID ws) throws Exception {
        UUID a = UUID.randomUUID();
        UUID v = UUID.randomUUID();
        sql("INSERT INTO automations (automation_id, organization_id, workspace_id, name, status, created_by_membership_id) VALUES ('" + a + "', '" + ORG + "', '"
                        + ws + "', 'Automacao', 1, '" + ADMIN_MEMBERSHIP + "')",
                "INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id) VALUES ('"
                        + v + "', '" + ORG + "', '" + ws + "', '" + a + "', 1, jsonb_build_object('contract_version',1,'artifact',jsonb_build_object('reference','v1','sha256',repeat('a',64)),"
                        + "'runtime','python3.11','entrypoint','main.py','arguments','[]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb), '" + ADMIN_MEMBERSHIP + "')",
                "UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '" + ADMIN_MEMBERSHIP + "' WHERE automation_version_id = '" + v + "'");
        return a;
    }

    static UUID execution(UUID ws, UUID automation, int minutes) throws Exception {
        UUID e = UUID.randomUUID();
        sql("INSERT INTO executions (execution_id, organization_id, workspace_id, automation_id, automation_version_id, request_key, request_fingerprint, "
                + "requested_by_membership_id, command_snapshot, status, origin, created_at) SELECT '" + e + "', '" + ORG + "', '" + ws + "', '" + automation
                + "', v.automation_version_id, 'req-" + e + "', repeat('b', 64), '" + ADMIN_MEMBERSHIP + "', v.command_json, 1, 1, now() - interval '" + minutes
                + " minutes' FROM automation_versions v WHERE v.automation_id = '" + automation + "'");
        return e;
    }

    // ------------------------------------------------------------------ cliente

    static BrowserClient browser() {
        BrowserClient n = new BrowserClient(BASE, http);
        n.origin = BASE;
        n.forwardedIp = "203.0.113.77"; // forjado pelo cliente: o nginx sobrescreve
        return n;
    }

    static String code() {
        while (true) {
            long currentTime = Totp.step(Instant.now());
            long candidate = Math.max(currentTime - 1, lastStep + 1);
            if (candidate <= currentTime + 1) {
                lastStep = candidate;
                return Totp.code(totpSecret, candidate);
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    static String org() {
        return "/api/v1/o/" + ORG;
    }

    static String portal() {
        return "/portal/api/v1/o/" + ORG;
    }

    static void collectSecrets(BrowserClient n) {
        SEEN_SECRETS.addAll(n.cookies().values());
        if (n.csrf != null) {
            SEEN_SECRETS.add(n.csrf);
        }
    }

    static String cookie(BrowserClient.Response r, String name) {
        return r.setCookies().stream().filter(c -> c.startsWith(name + "=")).findFirst().orElseThrow(() -> new AssertionError("sem Set-Cookie " + name));
    }

    static void assertHostCookieAttributes(String setCookie, String sameSite) {
        assertThat(setCookie).contains("; Secure").contains("; HttpOnly").contains("; Path=/").contains("SameSite=" + sameSite).doesNotContainIgnoringCase("Domain=");
    }

    // ------------------------------------------------------------------ cenarios

    static BrowserClient session;

    @Test
    @Order(1)
    void platformLoginWithMfaEnrollmentThroughSingleOrigin() {
        BrowserClient n = browser();
        BrowserClient.Response pre = n.get("/api/v1/csrf");
        assertThat(pre.status()).isEqualTo(200);
        assertHostCookieAttributes(cookie(pre, "__Host-nekkora_pre"), "Strict");
        n.csrf = pre.field("csrfToken");
        collectSecrets(n);

        BrowserClient.Response login = n.post(org() + "/auth/login", "{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.status()).as(login.body()).isEqualTo(200);
        assertThat(login.field("status")).isEqualTo("mfa_enrollment_required");
        assertHostCookieAttributes(cookie(login, "__Host-nekkora_mfa"), "Strict");
        assertThat(cookie(login, "__Host-nekkora_mfa")).contains("Max-Age=300");
        n.csrf = login.field("csrfToken");
        collectSecrets(n);

        BrowserClient.Response enrollStart = n.post(org() + "/auth/mfa/enroll/start", null);
        assertThat(enrollStart.status()).isEqualTo(200);
        String secret = enrollStart.field("otpauthUri").replace("\\u0026", "&").replaceAll(".*[?&]secret=([A-Z2-7]+).*", "$1");
        SEEN_SECRETS.add(secret);
        totpSecret = Base32.decode(secret);

        BrowserClient.Response confirmation = n.post(org() + "/auth/mfa/enroll/confirm", "{\"code\":\"" + code() + "\"}");
        assertThat(confirmation.status()).as(confirmation.body()).isEqualTo(200);
        String sc = cookie(confirmation, "__Host-nekkora_session");
        assertHostCookieAttributes(sc, "Lax");
        long maxAge = Long.parseLong(sc.replaceAll(".*Max-Age=(\\d+).*", "$1"));
        assertThat(maxAge).isPositive().isLessThanOrEqualTo(12 * 3600);
        SEEN_SECRETS.addAll(confirmation.listField("recoveryCodes"));
        n.csrf = confirmation.field("csrfToken");
        collectSecrets(n);
        assertThat(n.cookies()).containsKey("__Host-nekkora_session");

        assertThat(n.get(org() + "/me").status()).as("Platform reconhece a sessao").isEqualTo(200);
        session = n;
    }

    @Test
    @Order(2)
    void portalInAnotherProcessAcceptsSameCookie() {
        BrowserClient n = session;
        BrowserClient.Response ws = n.get(portal() + "/workspaces");
        assertThat(ws.status()).as(ws.body()).isEqualTo(200);
        assertThat(ws.body()).contains(WS1.toString()).doesNotContain(WS_OTHER.toString());

        BrowserClient.Response ex = n.get(portal() + "/executions");
        assertThat(ex.status()).as(ex.body()).isEqualTo(200);
        EXECS.forEach(e -> assertThat(ex.body()).contains(e.toString()));
        assertThat(ex.body()).doesNotContain(execOther.toString());

        assertThat(n.get(portal() + "/executions/" + EXECS.get(0)).status()).isEqualTo(200);
        assertThat(n.get(portal() + "/executions/" + EXECS.get(0) + "/events").status()).isEqualTo(200);
        assertThat(n.get(portal() + "/executions/" + execOther).status()).as("execucao de outro cliente: 404").isEqualTo(404);
        assertThat(n.post(portal() + "/workspaces", null).status()).as("portal so GET").isEqualTo(404);
        assertThat(browser().get(portal() + "/workspaces").status()).as("sem cookie").isEqualTo(401);
        assertThat(n.get("/portal/api/v1/o/" + UUID.randomUUID() + "/workspaces").status()).as("org da URL nao e autoridade").isEqualTo(401);
        assertThat(n.get("/qualquer").status()).as("fora das rotas publicadas pelo proxy").isEqualTo(404);
    }

    @Test
    @Order(3)
    void platformLogoutRevokesPortalAccess() {
        BrowserClient n = session;
        String oldCookie = n.cookies().get("__Host-nekkora_session");
        assertThat(n.post(org() + "/auth/logout", null).status()).isEqualTo(204);
        assertThat(n.cookies()).doesNotContainKey("__Host-nekkora_session");
        BrowserClient reuse = browser();
        reuse.cookies().put("__Host-nekkora_session", oldCookie);
        assertThat(reuse.get(portal() + "/workspaces").status()).as("cookie revogado no Portal").isEqualTo(401);
        assertThat(reuse.get(org() + "/me").status()).as("cookie revogado na Platform").isEqualTo(401);
    }

    @Test
    @Order(4)
    void secondLoginWithMfaAndRevokeAll() {
        BrowserClient n = browser();
        BrowserClient.Response pre = n.get("/api/v1/csrf");
        n.csrf = pre.field("csrfToken");
        BrowserClient.Response login = n.post(org() + "/auth/login", "{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.field("status")).isEqualTo("mfa_required");
        n.csrf = login.field("csrfToken");
        collectSecrets(n);
        BrowserClient.Response v = n.post(org() + "/auth/mfa/verify", "{\"code\":\"" + code() + "\"}");
        assertThat(v.status()).as(v.body()).isEqualTo(200);
        n.csrf = v.field("csrfToken");
        collectSecrets(n);
        String sessionCookie = n.cookies().get("__Host-nekkora_session");
        assertThat(n.get(portal() + "/workspaces").status()).isEqualTo(200);

        BrowserClient noCsrf = browser();
        noCsrf.cookies().put("__Host-nekkora_session", sessionCookie);
        assertThat(noCsrf.post(org() + "/auth/sessions/revoke-all", null).status()).as("escrita sem CSRF").isEqualTo(403);
        BrowserClient otherOrigin = browser();
        otherOrigin.cookies().put("__Host-nekkora_session", sessionCookie);
        otherOrigin.csrf = n.csrf;
        otherOrigin.origin = "https://atacante.example";
        assertThat(otherOrigin.post(org() + "/auth/sessions/revoke-all", null).status()).as("Origin diferente").isEqualTo(403);

        assertThat(n.post(org() + "/auth/sessions/revoke-all", null).status()).isEqualTo(204);
        BrowserClient reuse = browser();
        reuse.cookies().put("__Host-nekkora_session", sessionCookie);
        assertThat(reuse.get(portal() + "/workspaces").status()).isEqualTo(401);
    }

    @Test
    @Order(5)
    void noSecretsInContainerLogs() throws Exception {
        String logs = stackLogs();
        assertThat(logs).as("logs coletados de todos os servicos").contains("database system is ready to accept connections").contains("GET /portal/api/v1/o/");
        assertThat(SEEN_SECRETS).hasSizeGreaterThan(8);
        for (String s : SEEN_SECRETS) {
            assertThat(logs.contains(s)).as("segredo visto pelo cliente aparece nos logs").isFalse();
        }
        assertThat(logs).doesNotContain(PASSWORD).doesNotContain(EMAIL);
        assertThat(logs).as("IP forjado pelo cliente nunca chega como IP").doesNotContain("203.0.113.77");
    }

    @Test
    @Order(6)
    void firstRateLimitLayerAtProxy() throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(16);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            fs.add(ex.submit(() -> browser().get(portal() + "/workspaces").status()));
        }
        int limited = 0;
        for (Future<Integer> f : fs) {
            int s = f.get();
            assertThat(s).isIn(401, 429);
            limited += s == 429 ? 1 : 0;
        }
        ex.shutdown();
        assertThat(limited).as("rajada acima do burst do nginx").isPositive();
        assertThat(stackLogs()).as("recusa registrada pelo nginx (camada de borda)").contains("limiting requests");
    }

    static String stackLogs() throws Exception {
        Process p = new ProcessBuilder("docker", "compose", "-f", System.getenv("E2E_COMPOSE"), "logs", "--no-color", "--no-log-prefix")
                .redirectErrorStream(true).start();
        byte[] output = p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).isZero();
        return new String(output, StandardCharsets.UTF_8);
    }
}
