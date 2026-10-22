package com.nekkora.platform.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

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
 * Ponta a ponta ENTRE PROCESSOS das credenciais da identidade (missao PLATFORM-API-IDENTIDADE-002), pela stack de
 * api/e2e (run.sh), com a entrada geral ligada no container da Platform. O cliente so fala com o nginx (TLS, origem
 * unica); o banco e acessado so para a fixture (superusuario, DECLARADO: identidade ja confirmada, porque o e-mail real
 * esta fora desta etapa) e para conferir o estado. Fora do padrao do surefire: pulado sem E2E_*.
 *
 * <p>Jornadas: (1) primeiro fator pelo desafio e organizacao criada; (2) substituicao do fator com step-up (caminho A),
 * outra sessao revogada; (3) senha + codigo de recuperacao -> sessao restrita -> troca do fator (caminho B) -> restricao
 * levantada e organizacao acessivel; (4) modo ambiguo recusado na borda; (5) nenhum segredo nos logs.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdentityCredentialsE2E {

    static final String BASE = System.getenv("E2E_BASE");
    static final UUID IDENTIDADE = UUID.randomUUID();
    static final String EMAIL = "cred-" + IDENTIDADE.toString().substring(0, 8) + "@o.test";
    static final String SENHA = "senha-credenciais-e2e-" + UUID.randomUUID().toString().substring(0, 8);
    static final List<String> SEGREDOS = new ArrayList<>();
    static final Map<String, byte[]> FATORES = new HashMap<>();
    static final Map<String, Long> ULTIMO = new HashMap<>();
    static HttpClient http;
    static BrowserClient id;
    static List<String> codigos;
    static UUID org;

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
        Thread.sleep(3000); // a camada de borda (limit_req do nginx) se recompoe depois da rajada do CrossProcessE2E
        PasswordHasher h = new PasswordHasher(new PasswordHasher.Parameters(65536, 3, 1), 1, 1, 5000);
        sql("INSERT INTO identities (identity_id, email, display_name, status) VALUES ('" + IDENTIDADE + "', '" + EMAIL + "', 'Pessoa', 1)",
                "UPDATE identities SET email_verified_at = now(), status = 2 WHERE identity_id = '" + IDENTIDADE + "'",
                "INSERT INTO identity_credentials (identity_id, password_hash) VALUES ('" + IDENTIDADE + "', '" + h.generate(SENHA.toCharArray()) + "')");
        SEGREDOS.addAll(List.of(SENHA, EMAIL));
    }

    @Test
    @Order(1)
    void primeiroFatorPeloDesafioEOrganizacaoCriada() {
        id = loginNovo("mfa_enrollment_required");
        String uri = id.post("/api/v1/auth/mfa/enroll/start", null).field("otpauthUri");
        FATORES.put("f1", segredo(uri));
        BrowserClient.Response c = id.post("/api/v1/auth/mfa/enroll/confirm", "{\"code\":\"" + code("f1") + "\"}");
        assertThat(c.status()).isEqualTo(200);
        assertThat(c.body()).contains("\"restricted\":false");
        id.csrf = c.field("csrfToken");
        codigos = c.listField("recoveryCodes");
        SEGREDOS.addAll(codigos);
        assertThat(id.post("/api/v1/auth/step-up", "{\"code\":\"" + code("f1") + "\"}").status()).isEqualTo(204);
        BrowserClient.Response o = id.post("/api/v1/organizations", "{\"name\":\"Org das credenciais E2E\"}");
        assertThat(o.status()).isEqualTo(201);
        org = UUID.fromString(o.field("organizationId"));
        segredos(id);
    }

    @Test
    @Order(2)
    void substituicaoComStepUpRevogaAOutraSessao() throws Exception {
        BrowserClient outra = loginNovo("mfa_required");
        BrowserClient.Response v = outra.post("/api/v1/auth/mfa/verify", "{\"code\":\"" + code("f1") + "\"}");
        assertThat(v.status()).isEqualTo(200);
        outra.csrf = v.field("csrfToken");
        assertThat(outra.get("/api/v1/me/organizations").status()).isEqualTo(200);

        assertThat(outra.post("/api/v1/auth/mfa/enroll/start", null).status()).as("sessao sem step-up").isEqualTo(403);
        assertThat(id.post("/api/v1/auth/step-up", "{\"code\":\"" + code("f1") + "\"}").status()).isEqualTo(204);
        FATORES.put("f2", segredo(id.post("/api/v1/auth/mfa/enroll/start", null).field("otpauthUri")));
        BrowserClient.Response r = id.post("/api/v1/auth/mfa/enroll/confirm", "{\"code\":\"" + code("f2") + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).contains("\"restricted\":false");
        codigos = r.listField("recoveryCodes");
        SEGREDOS.addAll(codigos);
        assertThat(outra.get("/api/v1/me/organizations").status()).as("outra sessao revogada").isEqualTo(401);
        assertThat(id.get("/api/v1/me/organizations").status()).as("a da troca continua").isEqualTo(200);
        assertThat(valor("SELECT count(*) FROM identity_events WHERE identity_id = '" + IDENTIDADE + "' AND event_type = 11")).isEqualTo("1");
        segredos(outra);
        segredos(id);
    }

    @Test
    @Order(3)
    void recuperacaoComCodigoTrocaOFatorELiberaASessao() throws Exception {
        BrowserClient r = loginNovo("mfa_required");
        BrowserClient.Response v = r.post("/api/v1/auth/mfa/verify", "{\"recoveryCode\":\"" + codigos.get(0) + "\"}");
        assertThat(v.status()).isEqualTo(200);
        assertThat(v.body()).contains("\"restricted\":true");
        r.csrf = v.field("csrfToken");
        assertThat(r.get("/api/v1/me/session").body()).contains("\"restricted\":true");
        assertThat(r.get("/api/v1/me/organizations").status()).as("restrita").isEqualTo(403);
        assertThat(id.get("/api/v1/me/organizations").status()).as("T0 revogou as demais").isEqualTo(401);

        FATORES.put("f3", segredo(r.post("/api/v1/auth/mfa/enroll/start", null).field("otpauthUri")));
        BrowserClient.Response t = r.post("/api/v1/auth/mfa/enroll/confirm", "{\"code\":\"" + code("f3") + "\"}");
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        assertThat(t.body()).contains("\"restricted\":false");
        SEGREDOS.addAll(t.listField("recoveryCodes"));
        assertThat(r.get("/api/v1/me/session").body()).contains("\"restricted\":false");
        assertThat(r.get("/api/v1/me/organizations").body()).contains(org.toString());
        BrowserClient o = browser();
        o.cookies().put("__Host-nekkora_id", r.cookies().get("__Host-nekkora_id"));
        o.csrf = r.csrf;
        BrowserClient.Response s = o.post("/api/v1/sessions/organization", "{\"organizationId\":\"" + org + "\"}");
        assertThat(s.field("status")).as("organizacao acessivel depois da troca").isEqualTo("sessao_criada");
        o.csrf = s.field("csrfToken");
        assertThat(o.get("/api/v1/o/" + org + "/me").status()).isEqualTo(200);
        id = r;
        segredos(r);
        segredos(o);
    }

    @Test
    @Order(4)
    void credenciaisDosDoisModosSaoRecusadasNaBorda() {
        BrowserClient d = loginNovo("mfa_required");
        BrowserClient b = browser();
        b.cookies().put("__Host-nekkora_id", id.cookies().get("__Host-nekkora_id"));
        b.cookies().put("__Host-nekkora_id_mfa", d.cookies().get("__Host-nekkora_id_mfa"));
        b.csrf = id.csrf;
        BrowserClient.Response r = b.post("/api/v1/auth/mfa/enroll/start", null);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body()).isEqualTo("{\"error\":\"invalid_request\"}");
        segredos(d);
    }

    @Test
    @Order(5)
    void nenhumSegredoNosLogsDosContainers() throws Exception {
        Process p = new ProcessBuilder("docker", "compose", "-f", System.getenv("E2E_COMPOSE"), "logs", "--no-color", "--no-log-prefix")
                .redirectErrorStream(true).start();
        String logs = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor()).isZero();
        assertThat(logs).contains("evento=identidade.fator_substituido ");
        for (byte[] f : FATORES.values()) {
            SEGREDOS.add(Base32.encode(f));
        }
        for (String s : SEGREDOS) {
            assertThat(logs.contains(s)).as("segredo visto pelo cliente aparece nos logs").isFalse();
        }
        assertThat(logs).doesNotContain("otpauth://");
    }

    // ------------------------------------------------------------------ apoio

    static BrowserClient loginNovo(String status) {
        BrowserClient b = browser();
        b.csrf = b.get("/api/v1/csrf").field("csrfToken");
        BrowserClient.Response l = b.post("/api/v1/auth/login", "{\"email\":\"" + EMAIL + "\",\"password\":\"" + SENHA + "\"}");
        assertThat(l.field("status")).isEqualTo(status);
        b.csrf = l.field("csrfToken");
        return b;
    }

    static byte[] segredo(String uri) {
        return Base32.decode(URI.create(uri).getRawQuery().replaceAll(".*secret=([A-Z2-7]+).*", "$1"));
    }

    static BrowserClient browser() {
        BrowserClient n = new BrowserClient(BASE, http);
        n.origin = BASE;
        return n;
    }

    static void segredos(BrowserClient n) {
        SEGREDOS.addAll(n.cookies().values());
        if (n.csrf != null) {
            SEGREDOS.add(n.csrf);
        }
    }

    /** Proximo codigo TOTP ainda nao usado do fator (anti-replay: espera o passo seguinte se preciso). */
    static String code(String fator) {
        while (true) {
            long agora = Totp.step(Instant.now());
            long candidato = Math.max(agora - 1, ULTIMO.getOrDefault(fator, Long.MIN_VALUE) + 1);
            if (candidato <= agora + 1) {
                ULTIMO.put(fator, candidato);
                String c = Totp.code(FATORES.get(fator), candidato);
                SEGREDOS.add(c);
                return c;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    static void sql(String... commands) throws Exception {
        try (Connection c = DriverManager.getConnection(System.getenv("E2E_PG_URL"), "nekkora", System.getenv("E2E_PG_SUPER_SENHA"));
             Statement st = c.createStatement()) {
            for (String s : commands) {
                st.execute(s);
            }
        }
    }

    static String valor(String q) throws Exception {
        try (Connection c = DriverManager.getConnection(System.getenv("E2E_PG_URL"), "nekkora", System.getenv("E2E_PG_SUPER_SENHA"));
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery(q)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
