package com.nekkora.agentgateway.bench;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.nekkora.agentgateway.MachineToken;
import com.nekkora.core.token.Tokens;

/**
 * Medicao da ingestao do canal (contrato Platform-Agent 1.0) contra a stack de api/e2e (nginx -> nekkora-agent-gateway ->
 * PostgreSQL), com N Agents SIMULADOS em paralelo. Os envelopes sao os do registro REAL a1 do Agent (COMPAT-001), renumerados
 * para o volume pedido. Fixture DECLARADA por superusuario (organizacao, Workers autorizados, autorizacoes locais e codigos).
 * Mede: eventos e lotes, tempo total, eventos/s, latencia por lote (p50, p95, p99, max) e o consumo dos containers antes e
 * depois (docker stats). Confere no banco: nenhum evento perdido nem duplicado. NAO e um teste do surefire (main).
 *
 * <p>Uso: java -cp &lt;classpath de teste do gateway&gt; com.nekkora.agentgateway.bench.AgentBench &lt;agents&gt; &lt;execucoes por
 * agent&gt; &lt;eventos por execucao&gt; &lt;lote&gt; (variaveis E2E_BASE, E2E_CERT, E2E_PG_URL, E2E_PG_SUPER_SENHA, E2E_PROJETO).
 */
public final class AgentBench {

    static final String FP = "5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d";
    static final Path REAL = Path.of("src/test/resources/agent-real/a1-sucesso-atividade.jsonl");
    static final Pattern SEQ = Pattern.compile("\"sequence\":[0-9]+");
    static final Pattern EX = Pattern.compile("ex-6f670400dd5d9a00d9569fdec8d2c4f4");

    public static void main(String[] args) throws Exception {
        int agents = Integer.parseInt(args[0]);
        int runs = Integer.parseInt(args[1]);
        int events = Integer.parseInt(args[2]);
        int batch = Integer.parseInt(args[3]);
        String base = System.getenv("E2E_BASE");
        HttpClient http = http(System.getenv("E2E_CERT"));
        List<String> template = Files.readString(REAL, StandardCharsets.UTF_8).lines()
                .filter(l -> !l.isBlank() && !l.contains("\"COLLECTION_COMPLETED\"")).toList();
        String completed = Files.readString(REAL, StandardCharsets.UTF_8).lines().filter(l -> l.contains("\"COLLECTION_COMPLETED\"")).findFirst().orElseThrow();

        // ---------- fixture declarada
        UUID org = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID adminM = UUID.randomUUID();
        UUID ws = UUID.randomUUID();
        UUID aut = UUID.randomUUID();
        UUID ver = UUID.randomUUID();
        sql("INSERT INTO organizations (organization_id, name, status) VALUES ('" + org + "', 'Org medicao', 1)",
                "INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('" + admin + "', '" + org
                        + "', 1, 'bench-" + admin.toString().substring(0, 8) + "@o.test', now(), 2, 'Bench')",
                "INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('" + adminM + "', '" + org + "', '" + admin
                        + "', 1, 2)",
                "INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id) SELECT '" + org + "', '"
                        + adminM + "', role_id, 1, 1, '" + adminM + "' FROM roles WHERE code = 'ORG_ADMIN'",
                "INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, limit_value, "
                        + "provisioned_by_user_id) SELECT '" + org + "', c, 1, now(), 2, NULL, '" + admin + "' FROM generate_series(1, 5) c",
                "INSERT INTO workspaces (workspace_id, organization_id, name, status) VALUES ('" + ws + "', '" + org + "', 'WS medicao', 1)",
                "INSERT INTO automations (automation_id, organization_id, workspace_id, name, status, created_by_membership_id) VALUES ('" + aut + "', '" + org + "', '"
                        + ws + "', 'Biografias', 1, '" + adminM + "')",
                "INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id) "
                        + "VALUES ('" + ver + "', '" + org + "', '" + ws + "', '" + aut + "', 1, jsonb_build_object('contract_version',1,'artifact',jsonb_build_object("
                        + "'reference','p','sha256','" + FP + "'),'runtime','python3.12','entrypoint','main.py','arguments','[]'::jsonb,'parameters','{}'::jsonb,"
                        + "'secret_bindings','{}'::jsonb), '" + adminM + "')",
                "UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '" + adminM + "' WHERE automation_version_id = '" + ver + "'");
        List<String[]> machines = new ArrayList<>();
        for (int i = 0; i < agents; i++) {
            String worker = value("INSERT INTO workers (organization_id, name, status) VALUES ('" + org + "', 'W" + i + "', 1) RETURNING worker_id");
            String wwa = value("INSERT INTO worker_workspace_assignments (organization_id, worker_id, workspace_id, status, authorized_by_membership_id) VALUES ('" + org
                    + "', '" + worker + "', '" + ws + "', 1, '" + adminM + "') RETURNING assignment_id");
            String authz = value("INSERT INTO local_run_authorizations (organization_id, workspace_id, worker_id, worker_workspace_assignment_id, automation_id, "
                    + "automation_version_id, package_fingerprint, granted_by_membership_id, valid_until) VALUES ('" + org + "', '" + ws + "', '" + worker + "', '" + wwa
                    + "', '" + aut + "', '" + ver + "', '" + FP + "', '" + adminM + "', now() + interval '1 day') RETURNING authorization_id");
            UUID enrollment = UUID.randomUUID();
            String code = MachineToken.enrollment(org, enrollment, Tokens.newToken());
            sql("INSERT INTO agent_enrollments (enrollment_id, organization_id, purpose, worker_id, agent_name, code_hash, created_by_membership_id, expires_at) VALUES ('"
                    + enrollment + "', '" + org + "', 1, '" + worker + "', 'Maquina " + i + "', '" + Tokens.sha256Hex(code) + "', '" + adminM
                    + "', clock_timestamp() + interval '30 minutes')");
            machines.add(new String[] {code, authz});
        }

        String before = stats();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        long t0 = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(agents);
        List<Future<Long>> results = new ArrayList<>();
        for (String[] m : machines) {
            results.add(pool.submit(() -> {
                String secret = Tokens.newToken();
                Resp r = call(http, base, "POST", "/enrollments/redeem", "{\"code\":\"" + m[0] + "\",\"credentialSecret\":\"" + secret
                        + "\",\"agentVersion\":\"bench\",\"contractVersions\":[\"1.0\"]}", null);
                check(r, 200);
                String cred = "nka1." + org + "." + field(r.body(), "credentialId") + "." + secret;
                check(call(http, base, "POST", "/heartbeat", "{\"agentVersion\":\"bench\",\"contractVersion\":\"1.0\",\"capabilities\":[\"OBS_1_0\"]}", cred), 200);
                long sent = 0;
                for (int k = 0; k < runs; k++) {
                    String ex = "ex-" + UUID.randomUUID().toString().replace("-", "");
                    check(call(http, base, "POST", "/runs", "{\"agentExecutionId\":\"" + ex + "\",\"authorizationId\":\"" + m[1] + "\",\"packageFingerprint\":\"" + FP
                            + "\",\"supervisor\":{\"state\":\"RUNNING\"}}", cred), 201);
                    List<String> all = new ArrayList<>(events);
                    for (int s = 1; s <= events; s++) {
                        String line = s == events ? completed : template.get((s - 1) % template.size());
                        all.add(SEQ.matcher(EX.matcher(line).replaceAll(ex)).replaceFirst("\"sequence\":" + s));
                    }
                    for (int from = 0; from < events; from += batch) {
                        List<String> part = all.subList(from, Math.min(from + batch, events));
                        long b0 = System.nanoTime();
                        Resp ack = call(http, base, "POST", "/runs/" + ex + "/events", "{\"events\":[" + String.join(",", part) + "]}", cred);
                        latencies.add((System.nanoTime() - b0) / 1_000_000);
                        check(ack, 200);
                        sent += part.size();
                    }
                    check(call(http, base, "POST", "/runs/" + ex + "/supervisor", "{\"state\":\"SUCCEEDED\",\"exitCode\":0,\"counts\":{\"SUCCEEDED\":1}}", cred), 200);
                }
                return sent;
            }));
        }
        long total = 0;
        for (Future<Long> f : results) {
            total += f.get();
        }
        long wallMs = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdown();
        String after = stats();

        // ---------- conferencia no banco: nada perdido, nada duplicado
        String db = value("SELECT count(*) || '|' || count(DISTINCT (e.agent_run_id, e.sequence)) || '|' || (SELECT count(*) FROM agent_runs r WHERE r.organization_id = '"
                + org + "' AND r.stored_through = " + events + " AND r.supervisor_state = 4) FROM agent_run_events e WHERE e.organization_id = '" + org + "'");
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        System.out.println("agents=" + agents + " execucoes_por_agent=" + runs + " eventos_por_execucao=" + events + " lote=" + batch);
        System.out.println("eventos_enviados=" + total + " lotes=" + sorted.size() + " tempo_total_ms=" + wallMs + " eventos_por_s="
                + String.format(java.util.Locale.ROOT, "%.0f", total * 1000.0 / wallMs));
        System.out.println("latencia_lote_ms p50=" + pct(sorted, 50) + " p95=" + pct(sorted, 95) + " p99=" + pct(sorted, 99) + " max=" + sorted.get(sorted.size() - 1));
        System.out.println("banco eventos|distintos|execucoes_completas_succeeded=" + db + " (esperado " + total + "|" + total + "|" + agents * runs + ")");
        System.out.println("tamanho agent_run_events=" + value("SELECT pg_size_pretty(pg_total_relation_size('agent_run_events'))"));
        System.out.println("docker stats ANTES:\n" + before + "docker stats DEPOIS:\n" + after);
        if (!db.equals(total + "|" + total + "|" + agents * runs)) {
            throw new AssertionError("conferencia do banco falhou: " + db);
        }
    }

    record Resp(int status, String body) {
    }

    static Resp call(HttpClient http, String base, String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/agent/api/v1" + path)).timeout(Duration.ofSeconds(120))
                .header("Nekkora-Agent-Contract", "1.0").header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 429 && attempt < 20) {
                Thread.sleep(500);   // so a camada de borda: o lote nao foi aceito, o mesmo fato e reenviado
                continue;
            }
            return new Resp(r.statusCode(), r.body());
        }
    }

    static void check(Resp r, int expected) {
        if (r.status() != expected) {
            throw new IllegalStateException("esperado " + expected + ", veio " + r.status() + ": " + r.body());
        }
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static long pct(List<Long> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }

    static String stats() throws Exception {
        String project = System.getenv("E2E_PROJETO");
        Process p = new ProcessBuilder("docker", "stats", "--no-stream", "--format", "{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}").redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        for (String l : new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
            if (l.startsWith(project + "-agent-gateway") || l.startsWith(project + "-postgres") || l.startsWith(project + "-nginx")) {
                out.append("  ").append(l.trim()).append('\n');
            }
        }
        p.waitFor();
        return out.toString();
    }

    static HttpClient http(String cert) throws Exception {
        try (InputStream in = Files.newInputStream(Path.of(cert))) {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("e2e", CertificateFactory.getInstance("X.509").generateCertificate(in));
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, tmf.getTrustManagers(), null);
            return HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(5)).build();
        }
    }

    static Connection conn() throws Exception {
        return DriverManager.getConnection(System.getenv("E2E_PG_URL"), "nekkora", System.getenv("E2E_PG_SUPER_SENHA"));
    }

    static void sql(String... commands) throws Exception {
        try (Connection c = conn(); Statement st = c.createStatement()) {
            for (String s : commands) {
                st.execute(s);
            }
        }
    }

    static String value(String q) throws Exception {
        try (Connection c = conn(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(q)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
