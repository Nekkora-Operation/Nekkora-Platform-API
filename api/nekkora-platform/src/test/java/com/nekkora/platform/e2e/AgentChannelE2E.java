package com.nekkora.platform.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Primeiro marco do canal Platform-Agent ENTRE PROCESSOS, pela stack de api/e2e (run.sh): nginx -> Platform (pessoas) e
 * nekkora-agent-gateway (maquina) -> PostgreSQL 001-025 e Redis. O cliente so fala com o nginx. A maquina e um Agent SIMULADO
 * que entrega os registros REAIS do Agent empacotado (COMPAT-001: sucesso a1 e queda a5), lidos do modulo do gateway. O banco
 * e acessado so para a fixture DECLARADA (identidade ja confirmada; limites ilimitados por superusuario, porque a ativacao
 * operacional da 022 esta fora; automacao com versao publicada cujo artefato e a impressao do pacote real) e para conferir.
 * Fora do padrao do surefire: pulado sem E2E_*.
 *
 * <p>Jornada: a administradora cria o codigo de pareamento e a autorizacao pela API; a maquina resgata com o proprio
 * segredo, bate o heartbeat, baixa a autorizacao, registra a execucao e entrega os envelopes em lotes; no meio, o processo
 * do gateway PARA (transporte interrompido: 502, sem ACK) e volta; a maquina retoma do settledThrough e reenvia um lote ja
 * confirmado (ACK perdido: DUPLICATE); o supervisor informa SUCCEEDED. As consultas da Platform mostram a correlacao, o
 * desfecho e a completude COMPLETE, sem duplicata. A segunda execucao (queda do Agent, lacuna declarada) aparece
 * OUTCOME_UNKNOWN e INCOMPLETE. A revogacao corta a maquina. Nenhum segredo nos logs dos containers.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentChannelE2E {

    static final String BASE = System.getenv("E2E_BASE");
    static final Path REAL = Path.of("../nekkora-agent-gateway/src/test/resources/agent-real");
    static final String FINGERPRINT = "5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d";
    static final UUID ADMIN = UUID.randomUUID();
    static final String EMAIL = "adm-agente-" + ADMIN.toString().substring(0, 8) + "@o.test";
    static final String SENHA = "senha-agente-e2e-" + UUID.randomUUID().toString().substring(0, 8);
    static final List<String> SEGREDOS = new ArrayList<>();
    static final Map<String, byte[]> FATORES = new HashMap<>();
    static final Map<String, Long> ULTIMO = new HashMap<>();
    static HttpClient http;
    static BrowserClient admin;
    static UUID org;
    static UUID workspace;
    static UUID version;
    static String workerId;
    static String authorizationId;
    static String credential;
    static String runId;

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
        sql("INSERT INTO identities (identity_id, email, display_name, status) VALUES ('" + ADMIN + "', '" + EMAIL + "', 'Pessoa', 1)",
                "UPDATE identities SET email_verified_at = now(), status = 2 WHERE identity_id = '" + ADMIN + "'",
                "INSERT INTO identity_credentials (identity_id, password_hash) VALUES ('" + ADMIN + "', '" + h.generate(SENHA.toCharArray()) + "')");
        SEGREDOS.add(SENHA);
    }

    // =========================================================================== pessoas

    @Test
    @Order(1)
    void administradoraCriaOrganizacaoCodigoEAutorizacao() throws Exception {
        BrowserClient id = entrar(EMAIL, SENHA, "admin");
        assertThat(id.post("/api/v1/auth/step-up", "{\"code\":\"" + code("admin") + "\"}").status()).isEqualTo(204);
        BrowserClient.Response c = id.post("/api/v1/organizations", "{\"name\":\"Org E2E canal\"}");
        assertThat(c.status()).isEqualTo(201);
        org = UUID.fromString(c.field("organizationId"));
        workspace = UUID.randomUUID();
        version = UUID.randomUUID();
        UUID automation = UUID.randomUUID();
        String adminUser = valor("SELECT u.user_id FROM users u JOIN identity_users iu ON iu.user_id = u.user_id WHERE iu.identity_id = '" + ADMIN + "'");
        String adminM = valor("SELECT membership_id FROM organization_memberships WHERE user_id = '" + adminUser + "'");
        // fixture DECLARADA: limites ilimitados (a ativacao operacional da 022 esta fora), workspace, automacao publicada
        for (int code = 1; code <= 5; code++) {
            sqlIn(org, "INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, limit_value, "
                    + "provisioned_by_user_id) VALUES ('" + org + "', " + code + ", (SELECT coalesce(max(revision_number), 0) + 1 FROM organization_resource_limit_revisions "
                    + "WHERE organization_id = '" + org + "' AND resource_code = " + code + "), clock_timestamp(), 2, NULL, '" + adminUser + "')");
        }
        sqlIn(org, "INSERT INTO workspaces (workspace_id, organization_id, name, status) VALUES ('" + workspace + "', '" + org + "', 'WS E2E canal', 1)",
                "INSERT INTO automations (automation_id, organization_id, workspace_id, name, status, created_by_membership_id) VALUES ('" + automation + "', '" + org
                        + "', '" + workspace + "', 'Biografias', 1, '" + adminM + "')",
                "INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id) "
                        + "VALUES ('" + version + "', '" + org + "', '" + workspace + "', '" + automation + "', 1, jsonb_build_object('contract_version',1,'artifact',"
                        + "jsonb_build_object('reference','biografias-python','sha256','" + "ab".repeat(32) + "'),'agent_package',jsonb_build_object('fingerprint_rule','nekkora-agent/package-fingerprint/1','fingerprint','" + FINGERPRINT + "'),'runtime','python3.12','entrypoint','main.py',"
                        + "'arguments','[]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb), '" + adminM + "')",
                "UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '" + adminM + "' WHERE automation_version_id = '" + version + "'");
        admin = trocar(id, org);
        assertThat(admin.post(o() + "/auth/mfa/step-up", "{\"code\":\"" + code("admin") + "\"}").status()).isEqualTo(204);
        segredos(admin);

        BrowserClient.Response code = admin.post(o() + "/agent-enrollments", "{\"workerName\":\"Estacao E2E\",\"agentName\":\"Maquina E2E\"}");
        assertThat(code.status()).as(code.body()).isEqualTo(201);
        workerId = code.field("workerId");
        String pairing = code.field("code");
        SEGREDOS.add(pairing);
        BrowserClient.Response w = admin.send(HttpRequest.newBuilder(URI.create(BASE + o() + "/workers/" + workerId + "/workspaces/" + workspace))
                .PUT(HttpRequest.BodyPublishers.noBody()));
        assertThat(w.status()).as(w.body()).isEqualTo(201);
        BrowserClient.Response a = admin.post(o() + "/workspaces/" + workspace + "/local-run-authorizations", "{\"workerId\":\"" + workerId
                + "\",\"automationVersionId\":\"" + version + "\",\"validUntil\":\"" + Instant.now().plus(Duration.ofDays(30)).toString() + "\"}");
        assertThat(a.status()).as(a.body()).isEqualTo(201);
        authorizationId = a.field("authorizationId");

        // a MAQUINA resgata o codigo com o proprio segredo (pelo nginx, outro processo)
        String secret = Tokens.newToken();
        SEGREDOS.add(secret);
        Res r = machine("POST", "/enrollments/redeem", "{\"code\":\"" + pairing + "\",\"credentialSecret\":\"" + secret
                + "\",\"agentVersion\":\"0.1.0+e2e\",\"contractVersions\":[\"1.0\"]}", null);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        credential = "nka1." + org + "." + field(r.body(), "credentialId") + "." + secret;
        SEGREDOS.add(credential);
        assertThat(field(r.body(), "workerId")).isEqualTo(workerId);
    }

    // =========================================================================== maquina: o primeiro marco

    @Test
    @Order(2)
    void maquinaEntregaAExecucaoRealSobrevivendoAQuedaDoTransporte() throws Exception {
        assertThat(machine("POST", "/heartbeat", "{\"agentVersion\":\"0.1.0+e2e\",\"contractVersion\":\"1.0\",\"capabilities\":[\"RUN_LOCAL\",\"OBS_1_0\",\"SUPERVISOR_REPORT_1_0\"]}",
                credential).status()).isEqualTo(200);
        Res auths = machine("GET", "/authorizations", null, credential);
        assertThat(auths.body()).contains(authorizationId).contains(FINGERPRINT);
        List<String> env = lines("a1-sucesso-atividade.jsonl");
        String ex = field(env.get(0), "agent_execution_id");
        Res reg = machine("POST", "/runs", "{\"agentExecutionId\":\"" + ex + "\",\"authorizationId\":\"" + authorizationId + "\",\"packageFingerprint\":\""
                + FINGERPRINT + "\",\"supervisor\":{\"state\":\"RUNNING\"}}", credential);
        assertThat(reg.status()).as(reg.body()).isEqualTo(201);
        runId = field(reg.body(), "runId");

        long settled = 0;
        int batch = 50;
        boolean interrupted = false;
        while (settled < env.size()) {
            if (!interrupted && settled >= 100) {
                // transporte interrompido: o processo do gateway para; o lote nao recebe ACK (o nginx responde 502)
                compose("stop", "agent-gateway");
                try {
                    Res down = events(ex, env.subList((int) settled, (int) Math.min(settled + batch, env.size())), null);
                    assertThat(down.status()).isIn(502, 503, 504);
                    assertThat(down.body()).doesNotContain("storedThrough");
                } finally {
                    compose("start", "agent-gateway");
                    waitGateway();
                }
                // ACK perdido de um lote ja confirmado: o reenvio e DUPLICATE e o ACK e o mesmo
                Res dup = events(ex, env.subList(50, 100), null);
                assertThat(dup.status()).isEqualTo(200);
                assertThat(dup.body()).contains("\"DUPLICATE\"").doesNotContain("\"STORED\"");
                assertThat(Long.parseLong(number(dup.body(), "settledThrough"))).isEqualTo(settled);
                interrupted = true;
            }
            Res ack = events(ex, env.subList((int) settled, (int) Math.min(settled + batch, env.size())), null);
            assertThat(ack.status()).as(ack.body()).isEqualTo(200);
            settled = Long.parseLong(number(ack.body(), "settledThrough"));
        }
        assertThat(interrupted).isTrue();
        Res done = machine("POST", "/runs/" + ex + "/supervisor", estado("a1-sucesso-estado.json"), credential);
        assertThat(done.status()).as(done.body()).isEqualTo(200);
        assertThat(valor("SELECT count(*) || '|' || count(DISTINCT sequence) FROM agent_run_events WHERE agent_run_id = '" + runId + "'")).isEqualTo("294|294");
    }

    @Test
    @Order(3)
    void consultasDaPlatformMostramCorrelacaoDesfechoECompletude() throws Exception {
        BrowserClient.Response r = admin.get(o() + "/agent-runs/" + runId);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).contains("\"state\":\"SUCCEEDED\"").contains("\"outcome\":\"KNOWN\"").contains("\"process\":\"ENDED\"")
                .contains("\"completeness\":\"COMPLETE\"").contains("\"reasons\":[]").contains("\"frameworkExecutionId\":\"ex-d1dd0f7020ff40aeb12b\"")
                .contains("\"workerId\":\"" + workerId + "\"").contains("\"automationVersionId\":\"" + version + "\"").contains("\"connectivity\":\"CONNECTED\"")
                .contains("\"TRANSACTION_COMPLETED\":5");
        BrowserClient.Response ev = admin.get(o() + "/agent-runs/" + runId + "/events?limit=100");
        assertThat(ev.status()).isEqualTo(200);
        assertThat(ev.body()).contains("\"sequence\":1,").contains("\"sequence\":100,").contains("\"stepId\":\"search.fill_search_term\"");
        // sem duplicata pela API: a pagina seguinte comeca no 101
        BrowserClient.Response ev2 = admin.get(o() + "/agent-runs/" + runId + "/events?limit=100&cursor=" + ev.field("nextCursor"));
        assertThat(ev2.body()).contains("\"sequence\":101,").doesNotContain("\"sequence\":100,");
    }

    @Test
    @Order(4)
    void quedaDoAgentComLacunaDeclaradaFicaDesconhecidaEIncompleta() throws Exception {
        List<String> env = lines("a5-agent-derrubado-atividade.jsonl");
        String ex = field(env.get(0), "agent_execution_id");
        Res reg = machine("POST", "/runs", "{\"agentExecutionId\":\"" + ex + "\",\"authorizationId\":\"" + authorizationId + "\",\"packageFingerprint\":\""
                + FINGERPRINT + "\",\"supervisor\":{\"state\":\"RUNNING\"}}", credential);
        String run = field(reg.body(), "runId");
        for (int i = 0; i < 300; i += 100) {
            events(ex, env.subList(i, i + 100), null);
        }
        events(ex, env.subList(320, 500), "[{\"from\":301,\"to\":320}]");
        events(ex, env.subList(500, env.size()), null);
        assertThat(machine("POST", "/runs/" + ex + "/supervisor", estado("a5-agent-derrubado-estado.json"), credential).status()).isEqualTo(200);
        BrowserClient.Response r = admin.get(o() + "/agent-runs/" + run);
        assertThat(r.body()).contains("\"state\":\"OUTCOME_UNKNOWN\"").contains("\"outcome\":\"UNKNOWN\"").contains("\"code\":\"AGT-EXE-025\"")
                .contains("\"completeness\":\"INCOMPLETE\"").contains("LOST_LOCAL").contains("\"storedThrough\":300").contains("\"settledThrough\":668");
    }

    @Test
    @Order(5)
    void revogacaoCortaAMaquinaENenhumSegredoNosLogs() throws Exception {
        String agent = valor("SELECT agent_id FROM agent_runs WHERE agent_run_id = '" + runId + "'");
        assertThat(admin.post(o() + "/agents/" + agent + "/revoke", "{\"reason\":\"ADMINISTRATIVE\"}").status()).isEqualTo(204);
        assertThat(machine("POST", "/heartbeat", "{\"agentVersion\":\"0.1.0+e2e\",\"contractVersion\":\"1.0\",\"capabilities\":[]}", credential).status())
                .isEqualTo(401);
        String logs = compose("logs", "--no-color");
        for (String s : SEGREDOS) {
            assertThat(logs).as("segredo fora dos logs").doesNotContain(s);
        }
        assertThat(logs).contains("evento=agente.pareado").contains("evento=agente.revogado");
    }

    // =========================================================================== apoio

    record Res(int status, String body) {
    }

    static Res machine(String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + "/agent/api/v1" + path)).timeout(Duration.ofSeconds(60))
                .header("Nekkora-Agent-Contract", "1.0").header("Content-Type", "application/json");
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    static Res events(String ex, List<String> envelopes, String missing) throws Exception {
        return machine("POST", "/runs/" + ex + "/events", "{\"events\":[" + String.join(",", envelopes) + "]" + (missing == null ? "" : ",\"declaredMissing\":"
                + missing) + "}", credential);
    }

    static void waitGateway() throws Exception {
        for (int i = 0; i < 90; i++) {
            Res r = machine("GET", "/authorizations", null, credential);
            if (r.status() == 200) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("gateway nao voltou");
    }

    static List<String> lines(String name) throws Exception {
        return Files.readString(REAL.resolve(name), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
    }

    /** O relatorio do supervisor como o Agent o montaria do estado.json real (so os campos do contrato). */
    static String estado(String name) throws Exception {
        String e = Files.readString(REAL.resolve(name), StandardCharsets.UTF_8);
        StringBuilder s = new StringBuilder("{");
        for (String k : List.of("state", "code", "created", "started", "ended")) {
            String v = field(e, k);
            s.append(s.length() > 1 ? "," : "").append('"').append(k).append("\":").append(v == null ? "null" : "\"" + v + "\"");
        }
        String exit = number(e, "exitCode");
        s.append(",\"exitCode\":").append(exit == null ? "null" : exit);
        Matcher c = Pattern.compile("\"counts\"\\s*:\\s*(\\{[^}]*})").matcher(e);
        s.append(",\"counts\":").append(c.find() ? c.group(1) : "{}");
        for (String k : List.of("suspensions", "locks")) {
            s.append(",\"").append(k).append("\":").append(number(e, k));
        }
        s.append(",\"adopted\":").append(e.contains("\"adopted\": true")).append('}');
        return s.toString();
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static String number(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(-?[0-9]+)").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static String compose(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker", "compose", "-f", System.getenv("E2E_COMPOSE")));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor()).as(String.join(" ", cmd) + ": " + out).isZero();
        return out;
    }

    static String o() {
        return "/api/v1/o/" + org;
    }

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
