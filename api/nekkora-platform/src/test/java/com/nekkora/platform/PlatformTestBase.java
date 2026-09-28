package com.nekkora.platform;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.RealDatabase;
import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.testsupport.RealRedis;
import com.nekkora.platform.auth.crypto.Base32;
import com.nekkora.platform.auth.crypto.PasswordHasher;
import com.nekkora.platform.auth.crypto.Totp;
import com.nekkora.platform.mail.MailSender;

/**
 * Base dos testes HTTP da Platform: processo real (porta aleatoria), PostgreSQL 16 real com 001-014 e o papel
 * nekkora_platform, Redis real, chaves AES/HMAC aleatorias por execucao. A fixture e preparada como
 * superusuario (DECLARADO: preparacao, nao evidencia) e o ORG_ADMIN inicial pelo caminho autorizado da 012
 * (nekkora_provisioning). Tudo o que e testado passa pela API com o papel real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {PlatformApplication.class, PlatformTestBase.TestMailConfig.class})
public abstract class PlatformTestBase {

    public static final String ORIGIN = "https://portal.nekkora.test";
    protected static final RealDatabase DB = RealDatabase.shared();
    protected static final PasswordHasher HASHER = new PasswordHasher(new PasswordHasher.Parameters(65536, 3, 1), 4, 8, 5000);
    private static final SecureRandom RANDOM = new SecureRandom();

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestMailSender testMail;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry r) {
        r.add("nekkora.database.url", DB::url);
        r.add("nekkora.database.user", () -> "nekkora_platform");
        r.add("nekkora.database.password", () -> DB.password("nekkora_platform"));
        r.add("spring.data.redis.host", () -> redisForClass().host());
        r.add("spring.data.redis.port", () -> redisForClass().port());
        r.add("nekkora.public-origin", () -> ORIGIN);
        r.add("nekkora.trusted-proxies", () -> "127.0.0.1/32,::1/128");
        r.add("nekkora.keys.aes-current", () -> "k1");
        r.add("nekkora.keys.aes-by-id", () -> "k1:" + randomBase64(32));
        r.add("nekkora.keys.hmac-id", () -> "h1");
        r.add("nekkora.keys.hmac", () -> randomBase64(32));
    }

    /**
     * Redis EXCLUSIVO de uma classe que o derruba de proposito: definido no inicio dela e limpo no fim, para que o
     * contexto das demais classes use o compartilhado.
     */
    protected static volatile RealRedis exclusiveRedis;

    protected static RealRedis redisForClass() {
        RealRedis r = exclusiveRedis;
        return r != null ? r : RealRedis.shared();
    }

    /** Cada navegador e um cliente distinto atras do proxy confiavel (IP proprio via X-Forwarded-For). */
    protected BrowserClient browser() {
        BrowserClient n = new BrowserClient("http://localhost:" + port);
        n.forwardedIp = "10." + RANDOM.nextInt(250) + "." + RANDOM.nextInt(250) + "." + (1 + RANDOM.nextInt(250));
        return n;
    }

    protected static String randomBase64(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    // ------------------------------------------------------------------ fixture (superusuario, declarada)

    public record Org(UUID id, UUID adminId, UUID adminMembership, String adminEmail) {
    }

    public record TestUser(UUID id, String email, char[] password) {
    }

    protected static final String PASSWORD = "senha-de-teste-longa-1";

    /** Organizacao nova com um ORG_ADMIN (bootstrap pelo nekkora_provisioning). */
    protected static Org newOrganization() {
        UUID org = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        String email = "admin-" + org.toString().substring(0, 8) + "@o.test";
        DB.asSuperuser("INSERT INTO organizations (organization_id, name, status) VALUES ('" + org + "', 'Org teste', 1)");
        insertUser(org, admin, 1, email, true, 2);
        DB.asSuperuser("INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('"
                + membership + "', '" + org + "', '" + admin + "', 1, 2)");
        try (Connection c = DB.connectAs("nekkora_provisioning");
             PreparedStatement ps = c.prepareStatement("SELECT provision_first_org_admin(?, ?, ?, 'teste', gen_random_uuid())")) {
            ps.setObject(1, org);
            ps.setObject(2, membership);
            ps.setObject(3, admin);
            ps.execute();
        } catch (Exception e) {
            throw new IllegalStateException("bootstrap do ORG_ADMIN falhou", e);
        }
        return new Org(org, admin, membership, email);
    }

    protected static TestUser internalUser(Org o, boolean verified, int status) {
        UUID u = UUID.randomUUID();
        String email = "int-" + u.toString().substring(0, 8) + "@o.test";
        insertUser(o.id(), u, 1, email, verified, status);
        DB.asSuperuser("INSERT INTO organization_memberships (organization_id, user_id, account_type, status) VALUES ('"
                + o.id() + "', '" + u + "', 1, 2)");
        return new TestUser(u, email, PASSWORD.toCharArray());
    }

    /** Conta externa com cliente, vinculo e (se {@code workspace} != null) concessao ativa. */
    protected static TestUser externalUser(Org o, UUID client, UUID workspace) {
        UUID u = UUID.randomUUID();
        String email = "ext-" + u.toString().substring(0, 8) + "@cliente.test";
        insertUser(o.id(), u, 2, email, true, 2);
        UUID cm = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO client_memberships (client_membership_id, organization_id, client_id, user_id, account_type, status) VALUES ('"
                + cm + "', '" + o.id() + "', '" + client + "', '" + u + "', 2, 2)");
        if (workspace != null) {
            DB.asSuperuser("INSERT INTO client_workspace_grants (organization_id, client_id, client_membership_id, workspace_id, status, "
                    + "approved_by_membership_id, approved_at, starts_at, next_review_at) VALUES ('" + o.id() + "', '" + client + "', '" + cm + "', '"
                    + workspace + "', 2, '" + o.adminMembership() + "', now() - interval '1 day', now() - interval '1 day', now() + interval '30 days')");
        }
        return new TestUser(u, email, PASSWORD.toCharArray());
    }

    protected static UUID newClient(Org o) {
        UUID c = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO clients (client_id, organization_id, name, status) VALUES ('" + c + "', '" + o.id() + "', 'Cliente', 1)");
        return c;
    }

    protected static UUID newWorkspace(Org o, UUID client) {
        UUID w = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO workspaces (workspace_id, organization_id, client_id, name, status) VALUES ('" + w + "', '" + o.id() + "', "
                + (client == null ? "NULL" : "'" + client + "'") + ", 'WS " + w.toString().substring(0, 4) + "', 1)");
        return w;
    }

    private static void insertUser(UUID org, UUID u, int kind, String email, boolean verified, int status) {
        DB.asSuperuser("INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('"
                + u + "', '" + org + "', " + kind + ", '" + email + "', " + (verified ? "now()" : "NULL") + ", " + status + ", 'Pessoa de teste')",
                "INSERT INTO user_credentials (user_id, password_hash) VALUES ('" + u + "', '" + HASHER.generate(PASSWORD.toCharArray()) + "')");
    }

    // ------------------------------------------------------------------ fluxo HTTP e TOTP

    /** Segredo TOTP de cada conta cadastrada + ultimo passo usado (evita reuso: o anti-replay da 014 o rejeitaria). */
    protected static final Map<UUID, byte[]> SECRETS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_STEP = new ConcurrentHashMap<>();

    /** Proximo codigo TOTP ainda nao usado para a conta (espera o proximo passo de 30 s se preciso). */
    protected static String code(UUID user) {
        byte[] s = SECRETS.get(user);
        while (true) {
            Instant now = Instant.now();
            long currentTime = Totp.step(now);
            // o passo anterior so e usado no inicio do passo atual: perto da virada (>= 25 s) ele chegaria ao servidor
            // dois passos atras e seria recusado (tolerancia de +-1 do contrato)
            long earliest = now.getEpochSecond() % 30 < 25 ? currentTime - 1 : currentTime;
            long candidate = Math.max(earliest, LAST_STEP.getOrDefault(user, Long.MIN_VALUE) + 1);
            if (candidate <= currentTime + 1) {
                LAST_STEP.put(user, candidate);
                return Totp.code(s, candidate);
            }
            pause(1000);
        }
    }

    protected static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected BrowserClient.Response fetchPreAuthCsrf(BrowserClient n) {
        BrowserClient.Response r = n.get("/api/v1/csrf");
        n.csrf = r.field("csrfToken");
        return r;
    }

    protected BrowserClient.Response passwordLogin(BrowserClient n, UUID org, TestUser u) {
        fetchPreAuthCsrf(n);
        BrowserClient.Response r = n.post("/api/v1/o/" + org + "/auth/login", "{\"email\":\"" + u.email() + "\",\"password\":\"" + new String(u.password()) + "\"}");
        if (r.status() == 200) {
            n.csrf = r.field("csrfToken");
        }
        return r;
    }

    /** Login completo com cadastro de MFA (primeiro acesso). Devolve os codigos de recuperacao. */
    protected List<String> firstLogin(BrowserClient n, UUID org, TestUser u) {
        BrowserClient.Response r = passwordLogin(n, org, u);
        enforce(r.status() == 200 && "mfa_enrollment_required".equals(r.field("status")), "login passo 1: " + r.status());
        BrowserClient.Response enrollStart = n.post("/api/v1/o/" + org + "/auth/mfa/enroll/start", null);
        enforce(enrollStart.status() == 200, "enroll/start: " + enrollStart.status());
        String uri = enrollStart.field("otpauthUri").replace("\\u0026", "&");
        String secret = uri.replaceAll(".*[?&]secret=([A-Z2-7]+).*", "$1");
        SECRETS.put(u.id(), Base32.decode(secret));
        BrowserClient.Response confirmation = n.post("/api/v1/o/" + org + "/auth/mfa/enroll/confirm", "{\"code\":\"" + code(u.id()) + "\"}");
        enforce(confirmation.status() == 200, "enroll/confirm: " + confirmation.status());
        n.csrf = confirmation.field("csrfToken");
        return new ArrayList<>(confirmation.listField("recoveryCodes"));
    }

    /** Login completo de conta com MFA ja cadastrado. */
    protected BrowserClient.Response fullLogin(BrowserClient n, UUID org, TestUser u) {
        BrowserClient.Response r = passwordLogin(n, org, u);
        enforce(r.status() == 200 && "mfa_required".equals(r.field("status")), "login passo 1: " + r.status() + " " + r.body());
        BrowserClient.Response v = n.post("/api/v1/o/" + org + "/auth/mfa/verify", "{\"code\":\"" + code(u.id()) + "\"}");
        if (v.status() == 200) {
            n.csrf = v.field("csrfToken");
        }
        return v;
    }

    protected static void enforce(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    // ------------------------------------------------------------------ e-mail em memoria

    /**
     * {@code token}: token unico (ou CONFIRMAR, nos avisos emergenciais); {@code contestToken}: CONTESTAR (so emergencial);
     * {@code attempt}: tentativa do aviso emergencial (item 10), nulo nos demais.
     */
    public record SentMessage(MailSender.Kind kind, UUID org, String recipient, String token, String contestToken, UUID attempt) {
        public SentMessage(MailSender.Kind kind, UUID org, String recipient, String token, String contestToken) {
            this(kind, org, recipient, token, contestToken, null);
        }
    }

    /**
     * Transporte SIMULADO (e-mail real pausado): guarda as chamadas observadas pelo harness e devolve o desfecho
     * configurado ({@code ACEITO_PELO_TRANSPORTE} por padrao). Contar chamadas por tentativa NAO e garantia de
     * recebimento exatamente uma vez pelo destinatario.
     */
    public static final class TestMailSender implements MailSender {
        public final List<SentMessage> sent = new CopyOnWriteArrayList<>();
        public volatile Outcome outcome = Outcome.ACEITO_PELO_TRANSPORTE;

        @Override
        public void send(Kind kind, UUID organization, String recipient, String tokenOrNull) {
            sent.add(new SentMessage(kind, organization, recipient, tokenOrNull, null));
        }

        @Override
        public Outcome sendEmergencyNotice(Kind kind, UUID organization, String recipient, String confirmTokenOrNull, String contestTokenOrNull,
                                           UUID attemptId) {
            sent.add(new SentMessage(kind, organization, recipient, confirmTokenOrNull, contestTokenOrNull, attemptId));
            return outcome;
        }

        public long callsFor(UUID attempt) {
            return sent.stream().filter(m -> attempt.equals(m.attempt())).count();
        }
    }

    @TestConfiguration
    public static class TestMailConfig {
        @Bean
        @Primary
        TestMailSender testMailSender() {
            return new TestMailSender();
        }
    }
}
