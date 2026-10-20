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
 * Ponta a ponta ENTRE PROCESSOS das contas vinculadas (contratos P3 e P4), pela stack de api/e2e (run.sh), com a entrada
 * geral, a criacao de organizacao e a vinculacao LIGADAS no container da Platform. O cliente so fala com o nginx (TLS,
 * origem unica); o banco e acessado so para a fixture (superusuario, DECLARADO: conta antiga e identidade ja confirmada,
 * porque o e-mail real esta fora desta missao) e para conferir o estado. Fora do padrao do surefire: pulado sem E2E_*.
 *
 * <p>Jornadas: (1) conta antiga com fator local + identidade do mesmo e-mail -> vinculacao em duas etapas -> a porta local
 * fecha e a troca abre a organizacao -> step-up por organizacao com o fator da identidade; (2) organizacao criada pelo
 * cadastro -> troca -> a acao administrativa protegida por step-up e recusada antes e aceita depois do P4.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LinkedAccountsE2E {

    static final String BASE = System.getenv("E2E_BASE");
    static final UUID ORG = UUID.randomUUID();
    static final UUID CONTA = UUID.randomUUID();
    static final UUID IDENTIDADE = UUID.randomUUID();
    static final String EMAIL = "vinc-" + CONTA.toString().substring(0, 8) + "@o.test";
    static final String SENHA_LOCAL = "senha-local-e2e-" + UUID.randomUUID().toString().substring(0, 8);
    static final String SENHA_IDENTIDADE = "senha-identidade-e2e-" + UUID.randomUUID().toString().substring(0, 8);
    static final List<String> SEGREDOS = new ArrayList<>();
    static final Map<String, byte[]> FATORES = new HashMap<>();
    static final Map<String, Long> ULTIMO = new HashMap<>();
    static HttpClient http;
    static BrowserClient local;
    static BrowserClient id;

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
        String o = "'" + ORG + "'";
        sqlIn(ORG, "INSERT INTO organizations (organization_id, name, status) VALUES (" + o + ", 'Org E2E vinculacao', 1)",
                "INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('" + CONTA + "', " + o
                        + ", 1, '" + EMAIL + "', now(), 2, 'Conta antiga')",
                "INSERT INTO organization_memberships (organization_id, user_id, account_type, status) VALUES (" + o + ", '" + CONTA + "', 1, 2)",
                "INSERT INTO user_credentials (user_id, password_hash) VALUES ('" + CONTA + "', '" + h.generate(SENHA_LOCAL.toCharArray()) + "')");
        sql("INSERT INTO identities (identity_id, email, display_name, status) VALUES ('" + IDENTIDADE + "', '" + EMAIL + "', 'Pessoa', 1)",
                "UPDATE identities SET email_verified_at = now(), status = 2 WHERE identity_id = '" + IDENTIDADE + "'",
                "INSERT INTO identity_credentials (identity_id, password_hash) VALUES ('" + IDENTIDADE + "', '" + h.generate(SENHA_IDENTIDADE.toCharArray()) + "')");
        SEGREDOS.addAll(List.of(SENHA_LOCAL, SENHA_IDENTIDADE, EMAIL));
    }

    // ------------------------------------------------------------------ jornada 1: vinculacao (P3) e step-up vinculado (P4)

    @Test
    @Order(1)
    void contaAntigaEntraPelaPortaLocalComFatorLocal() {
        local = browser();
        csrfPre(local);
        BrowserClient.Response l = local.post(org(ORG) + "/auth/login", "{\"email\":\"" + EMAIL + "\",\"password\":\"" + SENHA_LOCAL + "\"}");
        assertThat(l.field("status")).isEqualTo("mfa_enrollment_required");
        local.csrf = l.field("csrfToken");
        String uri = local.post(org(ORG) + "/auth/mfa/enroll/start", null).field("otpauthUri");
        FATORES.put("local", Base32.decode(uri.replace("\\u0026", "&").replaceAll(".*[?&]secret=([A-Z2-7]+).*", "$1")));
        BrowserClient.Response c = local.post(org(ORG) + "/auth/mfa/enroll/confirm", "{\"code\":\"" + code("local") + "\"}");
        assertThat(c.status()).isEqualTo(200);
        local.csrf = c.field("csrfToken");
        SEGREDOS.addAll(c.listField("recoveryCodes"));
        assertThat(local.get(org(ORG) + "/me").status()).isEqualTo(200);
        segredos(local);
    }

    @Test
    @Order(2)
    void identidadeEntraPelaEntradaGeral() {
        id = browser();
        csrfPre(id);
        BrowserClient.Response l = id.post("/api/v1/auth/login", "{\"email\":\"" + EMAIL + "\",\"password\":\"" + SENHA_IDENTIDADE + "\"}");
        assertThat(l.field("status")).isEqualTo("mfa_enrollment_required");
        id.csrf = l.field("csrfToken");
        String uri = id.post("/api/v1/auth/mfa/enroll/start", null).field("otpauthUri");
        FATORES.put("identidade", Base32.decode(URI.create(uri).getRawQuery().replaceAll(".*secret=([A-Z2-7]+).*", "$1")));
        BrowserClient.Response c = id.post("/api/v1/auth/mfa/enroll/confirm", "{\"code\":\"" + code("identidade") + "\"}");
        assertThat(c.status()).isEqualTo(200);
        id.csrf = c.field("csrfToken");
        SEGREDOS.addAll(c.listField("recoveryCodes"));
        segredos(id);
    }

    @Test
    @Order(3)
    void vinculacaoEmDuasEtapasFechaAPortaLocal() throws Exception {
        BrowserClient.Response s = id.post("/api/v1/links/start",
                "{\"organizationId\":\"" + ORG + "\",\"password\":\"" + SENHA_LOCAL + "\",\"code\":\"" + code("local") + "\"}");
        assertThat(s.status()).as("etapa 1").isEqualTo(204);
        assertThat(s.setCookies().stream().filter(x -> x.startsWith("__Host-nekkora_link=")).findFirst().orElseThrow())
                .contains("; Secure").contains("; HttpOnly").contains("; Path=/").contains("SameSite=Strict").contains("Max-Age=300");
        segredos(id);
        assertThat(id.post("/api/v1/auth/step-up", "{\"code\":\"" + code("identidade") + "\"}").status()).as("step-up da identidade").isEqualTo(204);
        assertThat(id.post("/api/v1/links/confirm", "{\"organizationId\":\"" + ORG + "\"}").status()).as("etapa 2").isEqualTo(204);
        assertThat(valor("SELECT link_channel FROM identity_users WHERE user_id = '" + CONTA + "'")).isEqualTo("vinculacao");

        assertThat(local.get(org(ORG) + "/me").status()).as("sessao local revogada no ato").isEqualTo(401);
        BrowserClient outra = browser();
        csrfPre(outra);
        BrowserClient.Response l = outra.post(org(ORG) + "/auth/login", "{\"email\":\"" + EMAIL + "\",\"password\":\"" + SENHA_LOCAL + "\"}");
        assertThat(l.status()).as("porta local fechada").isEqualTo(401);
        assertThat(l.body()).isEqualTo("{\"error\":\"unauthenticated\"}");
    }

    @Test
    @Order(4)
    void trocaEStepUpPorOrganizacaoComOFatorDaIdentidade() throws Exception {
        BrowserClient o = trocar(ORG);
        assertThat(o.get(org(ORG) + "/me").status()).isEqualTo(200);
        assertThat(o.post(org(ORG) + "/auth/mfa/step-up", "{\"code\":\"" + code("local") + "\"}").status()).as("o fator local nao serve mais").isEqualTo(403);
        assertThat(o.post(org(ORG) + "/auth/mfa/step-up", "{\"code\":\"" + code("identidade") + "\"}").status()).as("P4").isEqualTo(204);
        assertThat(valor("SELECT count(*) FROM identity_events WHERE identity_id = '" + IDENTIDADE + "' AND event_type = 14 AND target_organization_id = '" + ORG + "'"))
                .isEqualTo("1");
        segredos(o);
    }

    // ------------------------------------------------------------------ jornada 2: organizacao do cadastro e acao protegida

    @Test
    @Order(5)
    void organizacaoDoCadastroUsaAAcaoProtegidaDepoisDoStepUpVinculado() throws Exception {
        assertThat(id.post("/api/v1/auth/step-up", "{\"code\":\"" + code("identidade") + "\"}").status()).isEqualTo(204);
        BrowserClient.Response criada = id.post("/api/v1/organizations", "{\"name\":\"Org criada no E2E\"}");
        assertThat(criada.status()).as("criacao (P2)").isEqualTo(201);
        UUID nova = UUID.fromString(criada.field("organizationId"));
        UUID alvo = UUID.randomUUID();
        sqlIn(nova, "INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('" + alvo + "', '" + nova
                        + "', 1, 'alvo-" + alvo.toString().substring(0, 8) + "@o.test', now(), 2, 'Alvo')",
                "INSERT INTO organization_memberships (organization_id, user_id, account_type, status) VALUES ('" + nova + "', '" + alvo + "', 1, 2)");
        BrowserClient o = trocar(nova);
        String reset = org(nova) + "/users/" + alvo + "/mfa/reset";
        assertThat(o.post(reset, "{\"reason\":\"DISPOSITIVO_PERDIDO\"}").status()).as("sem step-up: recusada").isEqualTo(403);
        assertThat(o.post(org(nova) + "/auth/mfa/step-up", "{\"code\":\"" + code("identidade") + "\"}").status()).isEqualTo(204);
        assertThat(o.post(reset, "{\"reason\":\"DISPOSITIVO_PERDIDO\"}").status()).as("com o step-up vinculado: aceita").isEqualTo(204);
        segredos(o);
    }

    @Test
    @Order(6)
    void nenhumSegredoNosLogsDosContainers() throws Exception {
        Process p = new ProcessBuilder("docker", "compose", "-f", System.getenv("E2E_COMPOSE"), "logs", "--no-color", "--no-log-prefix")
                .redirectErrorStream(true).start();
        String logs = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor()).isZero();
        assertThat(logs).contains("evento=identidade.vinculada").contains("evento=identidade.step_up_organizacao ");
        for (byte[] f : FATORES.values()) {
            SEGREDOS.add(Base32.encode(f));
        }
        for (String s : SEGREDOS) {
            assertThat(logs.contains(s)).as("segredo visto pelo cliente aparece nos logs").isFalse();
        }
        assertThat(logs).doesNotContain("otpauth://");
    }

    // ------------------------------------------------------------------ apoio

    static BrowserClient browser() {
        BrowserClient n = new BrowserClient(BASE, http);
        n.origin = BASE;
        return n;
    }

    static void csrfPre(BrowserClient n) {
        n.csrf = n.get("/api/v1/csrf").field("csrfToken");
    }

    /** Troca pela sessao de identidade, num navegador novo com o cookie dela; devolve-o com o CSRF da organizacao. */
    static BrowserClient trocar(UUID organizacao) {
        BrowserClient b = browser();
        b.cookies().put("__Host-nekkora_id", id.cookies().get("__Host-nekkora_id"));
        b.csrf = id.csrf;
        BrowserClient.Response r = b.post("/api/v1/sessions/organization", "{\"organizationId\":\"" + organizacao + "\"}");
        assertThat(r.field("status")).isEqualTo("sessao_criada");
        b.csrf = r.field("csrfToken");
        return b;
    }

    static void segredos(BrowserClient n) {
        SEGREDOS.addAll(n.cookies().values());
        if (n.csrf != null) {
            SEGREDOS.add(n.csrf);
        }
    }

    static String org(UUID o) {
        return "/api/v1/o/" + o;
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

    static Connection super_() throws Exception {
        return DriverManager.getConnection(System.getenv("E2E_PG_URL"), "nekkora", System.getenv("E2E_PG_SUPER_SENHA"));
    }

    static void sql(String... commands) throws Exception {
        try (Connection c = super_(); Statement st = c.createStatement()) {
            for (String s : commands) {
                st.execute(s);
            }
        }
    }

    /** Numa transacao com o contexto da organizacao (a 017 exige o contexto nas escritas de entrada local). */
    static void sqlIn(UUID organizacao, String... commands) throws Exception {
        try (Connection c = super_()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT set_config('nekkora.organization_id', '" + organizacao + "', true)");
                for (String s : commands) {
                    st.execute(s);
                }
            }
            c.commit();
        }
    }

    static String valor(String q) throws Exception {
        try (Connection c = super_(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(q)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
