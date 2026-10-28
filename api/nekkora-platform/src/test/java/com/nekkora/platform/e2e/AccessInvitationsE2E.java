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
import com.nekkora.core.token.Tokens;
import com.nekkora.platform.auth.crypto.Base32;
import com.nekkora.platform.auth.crypto.PasswordHasher;
import com.nekkora.platform.auth.crypto.Totp;

/**
 * Ponta a ponta ENTRE PROCESSOS dos acessos (023; contrato de 2026-10-27), pela stack de api/e2e (run.sh), com a entrada
 * geral, a criacao de organizacao e os convites LIGADOS. O cliente so fala com o nginx. O banco e acessado para a fixture
 * (superusuario, DECLARADO: identidades ja confirmadas, porque o e-mail real esta fora; o workspace; e UM convite com token
 * conhecido, emitido pelo mesmo administrador, porque o transporte local nao entrega o token) e para conferir o estado.
 * Fora do padrao do surefire: pulado sem E2E_*.
 *
 * <p>Jornada: a administradora cria a organizacao pela identidade, troca de sessao e faz o step-up por organizacao;
 * emite, lista e revoga um convite pela API; a convidada aceita (identidade com step-up), troca de sessao e ve o workspace
 * do papel; a administradora retira o vinculo e a sessao da convidada cai, com a identidade dela intacta; nenhum segredo
 * nos logs dos containers.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccessInvitationsE2E {

    static final String BASE = System.getenv("E2E_BASE");
    static final UUID ADMIN = UUID.randomUUID();
    static final UUID CONVIDADA = UUID.randomUUID();
    static final String EMAIL_ADMIN = "adm-" + ADMIN.toString().substring(0, 8) + "@o.test";
    static final String EMAIL_CONVIDADA = "conv-" + CONVIDADA.toString().substring(0, 8) + "@o.test";
    static final String SENHA_ADMIN = "senha-admin-e2e-" + UUID.randomUUID().toString().substring(0, 8);
    static final String SENHA_CONVIDADA = "senha-conv-e2e-" + UUID.randomUUID().toString().substring(0, 8);
    static final List<String> SEGREDOS = new ArrayList<>();
    static final Map<String, byte[]> FATORES = new HashMap<>();
    static final Map<String, Long> ULTIMO = new HashMap<>();
    static HttpClient http;
    static BrowserClient adminId;
    static BrowserClient adminOrg;
    static BrowserClient convId;
    static BrowserClient convOrg;
    static UUID org;
    static UUID workspace;

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
        Thread.sleep(3000); // a camada de borda (limit_req do nginx) se recompoe depois das rajadas anteriores
        PasswordHasher h = new PasswordHasher(new PasswordHasher.Parameters(65536, 3, 1), 1, 1, 5000);
        for (Object[] p : new Object[][] { { ADMIN, EMAIL_ADMIN, SENHA_ADMIN }, { CONVIDADA, EMAIL_CONVIDADA, SENHA_CONVIDADA } }) {
            sql("INSERT INTO identities (identity_id, email, display_name, status) VALUES ('" + p[0] + "', '" + p[1] + "', 'Pessoa', 1)",
                    "UPDATE identities SET email_verified_at = now(), status = 2 WHERE identity_id = '" + p[0] + "'",
                    "INSERT INTO identity_credentials (identity_id, password_hash) VALUES ('" + p[0] + "', '" + h.generate(((String) p[2]).toCharArray()) + "')");
        }
        SEGREDOS.addAll(List.of(SENHA_ADMIN, SENHA_CONVIDADA));
    }

    @Test
    @Order(1)
    void administradoraCriaAOrganizacaoETrocaDeSessao() throws Exception {
        adminId = entrar(EMAIL_ADMIN, SENHA_ADMIN, "admin");
        assertThat(adminId.post("/api/v1/auth/step-up", "{\"code\":\"" + code("admin") + "\"}").status()).isEqualTo(204);
        BrowserClient.Response c = adminId.post("/api/v1/organizations", "{\"name\":\"Org E2E acessos\"}");
        assertThat(c.status()).isEqualTo(201);
        org = UUID.fromString(c.field("organizationId"));
        workspace = UUID.randomUUID();
        sqlIn(org, "INSERT INTO workspaces (workspace_id, organization_id, name, status) VALUES ('" + workspace + "', '" + org + "', 'WS E2E', 1)");
        adminOrg = trocar(adminId, org);
        assertThat(adminOrg.post(org(org) + "/auth/mfa/step-up", "{\"code\":\"" + code("admin") + "\"}").status()).as("step-up por organizacao (P4)").isEqualTo(204);
        segredos(adminOrg);
    }

    @Test
    @Order(2)
    void emiteListaERevogaPelaApiSemTokenNaResposta() throws Exception {
        BrowserClient.Response e = adminOrg.post(org(org) + "/invitations",
                "{\"email\":\"" + EMAIL_CONVIDADA + "\",\"role\":\"VIEWER\",\"workspaceId\":\"" + workspace + "\"}");
        assertThat(e.status()).isEqualTo(201);
        assertThat(e.body()).doesNotContain("token");
        String inv = e.field("invitationId");
        BrowserClient.Response l = adminOrg.get(org(org) + "/invitations");
        assertThat(l.status()).isEqualTo(200);
        assertThat(l.body()).contains(inv).contains("\"state\":\"PENDENTE\"").doesNotContain("token");
        assertThat(adminOrg.post(org(org) + "/invitations/" + inv + "/revoke", null).status()).isEqualTo(204);
        SEGREDOS.add(valor("SELECT token_hash FROM identity_invitations WHERE invitation_id = '" + inv + "'"));
        assertThat(valor("SELECT count(*) FROM access_events WHERE organization_id = '" + org + "' AND event_type IN (1, 2)")).isEqualTo("2");
    }

    @Test
    @Order(3)
    void convidadaAceitaTrocaDeSessaoEVeOWorkspaceDoPapel() throws Exception {
        // convite com token conhecido (fixture DECLARADA): mesmo emissor, mesmo papel; o transporte local nao entrega o token
        String token = Tokens.newToken();
        SEGREDOS.add(token);
        String adminUser = valor("SELECT u.user_id FROM users u JOIN identity_users iu ON iu.user_id = u.user_id WHERE iu.identity_id = '" + ADMIN
                + "' AND u.organization_id = '" + org + "'");
        String adminM = valor("SELECT membership_id FROM organization_memberships WHERE user_id = '" + adminUser + "'");
        sqlIn(org, "INSERT INTO identity_invitations (organization_id, invited_account_type, email_normalized, issued_by, token_hash, expires_at,"
                + " issued_by_membership_id, role_id, role_scope, workspace_id) SELECT '" + org + "', 1, '" + EMAIL_CONVIDADA + "', '" + adminUser + "', '"
                + Tokens.sha256Hex(token) + "', now() + interval '1 day', '" + adminM + "', role_id, 2, '" + workspace + "' FROM roles WHERE code = 'VIEWER'");
        convId = entrar(EMAIL_CONVIDADA, SENHA_CONVIDADA, "convidada");
        String aceite = "{\"organizationId\":\"" + org + "\",\"token\":\"" + token + "\"}";
        assertThat(convId.post("/api/v1/invitations/accept", aceite).status()).as("sem step-up: 404 unico").isEqualTo(404);
        assertThat(convId.post("/api/v1/auth/step-up", "{\"code\":\"" + code("convidada") + "\"}").status()).isEqualTo(204);
        BrowserClient.Response a = convId.post("/api/v1/invitations/accept", aceite);
        assertThat(a.status()).as(a.body()).isEqualTo(200);
        assertThat(valor("SELECT link_channel FROM identity_users WHERE identity_id = '" + CONVIDADA + "'")).isEqualTo("convite");
        convOrg = trocar(convId, org);
        assertThat(convOrg.get(org(org) + "/workspaces/" + workspace).status()).as("VIEWER ve o workspace").isEqualTo(200);
        assertThat(convOrg.get(org(org) + "/members").status()).as("sem org.manage").isEqualTo(403);
        assertThat(convId.post("/api/v1/invitations/accept", aceite).status()).as("uso unico").isEqualTo(404);
        segredos(convId);
        segredos(convOrg);
    }

    @Test
    @Order(4)
    void retiradaDoVinculoDerrubaASessaoDaConvidadaEPreservaAIdentidade() throws Exception {
        String conta = valor("SELECT u.user_id FROM users u JOIN identity_users iu ON iu.user_id = u.user_id WHERE iu.identity_id = '" + CONVIDADA + "'");
        assertThat(adminOrg.post(org(org) + "/users/" + conta + "/membership/revoke", null).status()).isEqualTo(204);
        assertThat(convOrg.get(org(org) + "/me").status()).as("sessao da organizacao cai").isEqualTo(401);
        BrowserClient.Response lista = convId.get("/api/v1/me/organizations");
        assertThat(lista.status()).as("a identidade continua").isEqualTo(200);
        assertThat(lista.body()).doesNotContain(org.toString());
        assertThat(valor("SELECT count(*) FROM identity_mfa_factors WHERE identity_id = '" + CONVIDADA + "' AND disabled_at IS NULL")).isEqualTo("1");
        assertThat(valor("SELECT string_agg(event_type::text, ',' ORDER BY created_at) FROM access_events WHERE organization_id = '" + org + "'"))
                .isEqualTo("1,2,4,8");
    }

    @Test
    @Order(5)
    void nenhumSegredoNosLogsDosContainers() throws Exception {
        Process p = new ProcessBuilder("docker", "compose", "-f", System.getenv("E2E_COMPOSE"), "logs", "--no-color", "--no-log-prefix")
                .redirectErrorStream(true).start();
        String logs = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor()).isZero();
        assertThat(logs).contains("evento=convite.aceito");
        for (byte[] f : FATORES.values()) {
            SEGREDOS.add(Base32.encode(f));
        }
        for (String s : SEGREDOS) {
            assertThat(logs.contains(s)).as("segredo visto pelo cliente aparece nos logs").isFalse();
        }
    }

    // ------------------------------------------------------------------ apoio

    static BrowserClient entrar(String email, String senha, String fator) {
        BrowserClient b = browser();
        b.csrf = b.get("/api/v1/csrf").field("csrfToken");
        BrowserClient.Response l = b.post("/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + senha + "\"}");
        assertThat(l.field("status")).isEqualTo("mfa_enrollment_required");
        b.csrf = l.field("csrfToken");
        String uri = b.post("/api/v1/auth/mfa/enroll/start", null).field("otpauthUri");
        FATORES.put(fator, Base32.decode(URI.create(uri).getRawQuery().replaceAll(".*secret=([A-Z2-7]+).*", "$1")));
        BrowserClient.Response c = b.post("/api/v1/auth/mfa/enroll/confirm", "{\"code\":\"" + code(fator) + "\"}");
        assertThat(c.status()).isEqualTo(200);
        b.csrf = c.field("csrfToken");
        SEGREDOS.addAll(c.listField("recoveryCodes"));
        segredos(b);
        return b;
    }

    static BrowserClient browser() {
        BrowserClient n = new BrowserClient(BASE, http);
        n.origin = BASE;
        return n;
    }

    /** Troca pela sessao de identidade, num navegador novo com o cookie dela; devolve-o com o CSRF da organizacao. */
    static BrowserClient trocar(BrowserClient identidade, UUID organizacao) {
        BrowserClient b = browser();
        b.cookies().put("__Host-nekkora_id", identidade.cookies().get("__Host-nekkora_id"));
        b.csrf = identidade.csrf;
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
