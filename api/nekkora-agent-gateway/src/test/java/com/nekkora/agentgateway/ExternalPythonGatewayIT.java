package com.nekkora.agentgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.RealDatabase;
import com.nekkora.core.testsupport.RealRedis;

import tools.jackson.databind.JsonNode;

/**
 * AGENT-PYTHON-001 (030) no processo REAL do gateway (papel nekkora_ingestion), PostgreSQL e Redis reais:
 * <ul>
 *   <li>M1: autorizacao de MONITORAMENTO entregue sem versao nem impressao; registro sem impressao, idempotente; impressao
 *       inventada recusada; autorizacao de pacote continua exigindo a impressao;</li>
 *   <li>M2: pedido de execucao puxado pela maquina (entregar marca DELIVERED, nunca inicio), decisao idempotente, conflito,
 *       recusa, registro pelo pedido aceito (RUN_LINKED), pedido de outra maquina invisivel e indecidivel, revogado e vencido
 *       nao aceitos.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = AgentGatewayApplication.class)
class ExternalPythonGatewayIT {

    static final RealDatabase DB = RealDatabase.shared();
    static AgentFixture fx;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry r) {
        RealRedis redis = RealRedis.shared();
        r.add("nekkora.database.url", DB::url);
        r.add("nekkora.database.user", () -> "nekkora_ingestion");
        r.add("nekkora.database.password", () -> DB.password("nekkora_ingestion"));
        r.add("spring.data.redis.host", redis::host);
        r.add("spring.data.redis.port", redis::port);
        r.add("nekkora.trusted-proxies", () -> "127.0.0.1/32,::1/128");
        r.add("nekkora.limits.batches-per-minute", () -> "100000");
        r.add("nekkora.limits.redeem-failures-per-hour", () -> "1000");
    }

    @BeforeAll
    static void fixture() {
        fx = new AgentFixture(DB);
    }

    record Paired(MachineClient m, UUID worker, UUID wwa, UUID agent) {
    }

    Paired paired() {
        UUID worker = fx.worker("W-" + UUID.randomUUID().toString().substring(0, 6));
        UUID wwa = fx.authorizeWorker(worker);
        MachineClient m = new MachineClient(port, "10.61." + (int) (Math.random() * 250) + "." + (1 + (int) (Math.random() * 250)));
        JsonNode id = m.pair(fx.pairingCode(worker, "Estacao py", "15 minutes"));
        return new Paired(m, worker, wwa, UUID.fromString(id.get("agentId").asString()));
    }

    UUID monitored(UUID worker, UUID wwa) {
        return UUID.fromString(fx.value("INSERT INTO local_run_authorizations (organization_id, workspace_id, worker_id, worker_workspace_assignment_id, automation_id, "
                + "automation_version_id, package_fingerprint, package_fingerprint_rule, source_kind, granted_by_membership_id, valid_until) VALUES ('" + fx.org + "', '"
                + fx.workspace + "', '" + worker + "', '" + wwa + "', '" + fx.automation + "', NULL, NULL, NULL, 3, '" + fx.adminMembership
                + "', now() + interval '30 days') RETURNING authorization_id"));
    }

    UUID request(UUID authorization, UUID worker, UUID agent) {
        return UUID.fromString(fx.value("INSERT INTO run_requests (organization_id, workspace_id, automation_id, automation_version_id, authorization_id, worker_id, "
                + "agent_id, source_kind, package_fingerprint, package_fingerprint_rule, request_key, requested_by_membership_id, valid_until) VALUES ('" + fx.org
                + "', '" + fx.workspace + "', '" + fx.automation + "', '" + fx.version + "', '" + authorization + "', '" + worker + "', '" + agent + "', 1, '"
                + AgentFixture.REAL_FINGERPRINT + "', 'nekkora-agent/package-fingerprint/1', gen_random_uuid(), '" + fx.adminMembership
                + "', now() + interval '10 minutes') RETURNING run_request_id"));
    }

    static String registerBody(String exec, UUID authorization, String fingerprint, UUID runRequest, String supervisor) {
        return "{\"agentExecutionId\":\"" + exec + "\",\"authorizationId\":\"" + authorization + "\""
                + (fingerprint == null ? "" : ",\"packageFingerprint\":\"" + fingerprint + "\"")
                + (runRequest == null ? "" : ",\"runRequestId\":\"" + runRequest + "\"") + ",\"supervisor\":" + supervisor + "}";
    }

    static String exec(String two) {
        return "ex-" + two.repeat(16);
    }

    MachineClient.Response list(MachineClient m) {
        return m.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/agent/api/v1/run-requests")).GET(), m.credential);
    }

    MachineClient.Response decide(MachineClient m, UUID request, String body) {
        return m.post("/run-requests/" + request + "/decision", body, m.credential);
    }

    // =========================================================================== M1

    @Test
    void monitoramentoEntregueSemImpressaoERegistroSemImpressaoIdempotente() {
        Paired p = paired();
        UUID auth = monitored(p.worker(), p.wwa());
        UUID pkg = fx.localRun(p.worker(), p.wwa());
        MachineClient.Response hb = p.m().post("/heartbeat", "{\"agentVersion\":\"0.2.0+py1\",\"contractVersion\":\"1.0\",\"capabilities\":[\"RUN_LOCAL\",\"OBS_1_0\","
                + "\"SUPERVISOR_REPORT_1_0\",\"EXTERNAL_PYTHON_1_0\",\"RUN_REQUEST_1_0\"]}", p.m().credential);
        assertThat(hb.status()).as(hb.raw()).isEqualTo(200);
        MachineClient.Response a = p.m().authorizations();
        assertThat(a.status()).isEqualTo(200);
        JsonNode mon = null;
        for (JsonNode i : a.body().get("items")) {
            if (i.get("authorizationId").asString().equals(auth.toString())) {
                mon = i;
            }
        }
        assertThat(mon).isNotNull();
        assertThat(mon.get("sourceKind").asString()).isEqualTo("EXTERNAL_MONITORED");
        assertThat(mon.get("packageFingerprint").isNull()).isTrue();
        assertThat(mon.get("packageFingerprintRule").isNull()).isTrue();
        assertThat(mon.get("automationVersionId").isNull()).isTrue();
        assertThat(a.raw()).contains("\"sourceKind\":\"STUDIO_PACKAGE\"");

        String running = "{\"state\":\"RUNNING\",\"started\":\"2026-11-03T12:00:00Z\"}";
        // impressao inventada para o monitoramento: 403
        assertThat(p.m().post("/runs", registerBody(exec("a1"), auth, AgentFixture.REAL_FINGERPRINT, null, running), p.m().credential).status()).isEqualTo(403);
        MachineClient.Response r1 = p.m().post("/runs", registerBody(exec("a1"), auth, null, null, running), p.m().credential);
        assertThat(r1.status()).as(r1.raw()).isEqualTo(201);
        MachineClient.Response r2 = p.m().post("/runs", registerBody(exec("a1"), auth, null, null, running), p.m().credential);
        assertThat(r2.status()).isEqualTo(200);
        assertThat(r2.body().get("runId")).isEqualTo(r1.body().get("runId"));
        assertThat(r2.body().get("created").asBoolean()).isFalse();
        assertThat(fx.count("SELECT count(*) FROM agent_runs WHERE agent_execution_id = '" + exec("a1") + "' AND source_kind = 3 AND package_fingerprint IS NULL "
                + "AND automation_version_id IS NULL")).isEqualTo(1);
        // desfecho do monitoramento: codigo 0 sem sinal de negocio
        MachineClient.Response done = p.m().supervisor(exec("a1"), "{\"state\":\"SUCCEEDED\",\"code\":\"AGT-EXT-001\",\"exitCode\":0,"
                + "\"started\":\"2026-11-03T12:00:00Z\",\"ended\":\"2026-11-03T12:00:05Z\"}");
        assertThat(done.status()).as(done.raw()).isEqualTo(200);
        assertThat(done.body().get("outcome").asString()).isEqualTo("APPLIED");
        // a autorizacao de pacote continua exigindo a impressao
        assertThat(p.m().post("/runs", registerBody(exec("b2"), pkg, null, null, running), p.m().credential).status()).isEqualTo(403);
        assertThat(p.m().post("/runs", registerBody(exec("b2"), pkg, AgentFixture.REAL_FINGERPRINT, null, running), p.m().credential).status()).isEqualTo(201);
    }

    // =========================================================================== M2

    @Test
    void pedidoEntregueDecididoERegistradoComIdempotencia() {
        Paired p = paired();
        UUID pkg = fx.localRun(p.worker(), p.wwa());
        UUID q = request(pkg, p.worker(), p.agent());

        MachineClient.Response l1 = list(p.m());
        assertThat(l1.status()).as(l1.raw()).isEqualTo(200);
        assertThat(l1.raw()).contains(q.toString()).contains("\"state\":\"DELIVERED\"").doesNotContain("main.py").doesNotContain("arguments");
        // reentrega (resposta perdida): o mesmo pedido de novo, contado
        assertThat(list(p.m()).raw()).contains(q.toString());
        assertThat(fx.value("SELECT state || ':' || deliveries FROM run_requests WHERE run_request_id = '" + q + "'")).isEqualTo("2:2");

        assertThat(decide(p.m(), q, "{\"decision\":\"ACCEPTED\"}").status()).as("sem execucao").isEqualTo(400);
        MachineClient.Response ok = decide(p.m(), q, "{\"decision\":\"ACCEPTED\",\"agentExecutionId\":\"" + exec("c3") + "\"}");
        assertThat(ok.status()).as(ok.raw()).isEqualTo(200);
        assertThat(ok.body().get("outcome").asString()).isEqualTo("APPLIED");
        // confirmacao perdida: a mesma decisao de novo e UNCHANGED; outra execucao e CONFLICT; recusar agora e CONFLICT
        MachineClient.Response same = decide(p.m(), q, "{\"decision\":\"ACCEPTED\",\"agentExecutionId\":\"" + exec("c3") + "\"}");
        assertThat(same.status()).isEqualTo(200);
        assertThat(same.body().get("outcome").asString()).isEqualTo("UNCHANGED");
        MachineClient.Response other = decide(p.m(), q, "{\"decision\":\"ACCEPTED\",\"agentExecutionId\":\"" + exec("d4") + "\"}");
        assertThat(other.status()).isEqualTo(409);
        assertThat(other.body().get("outcome").asString()).isEqualTo("CONFLICT");
        assertThat(decide(p.m(), q, "{\"decision\":\"REFUSED\",\"reason\":\"BUSY\"}").status()).isEqualTo(409);
        // o aceito continua na lista (a maquina reconcilia com o seu registro), com o agentExecutionId
        assertThat(list(p.m()).raw()).contains("\"state\":\"ACCEPTED\"").contains(exec("c3"));

        String running = "{\"state\":\"STARTING\"}";
        assertThat(p.m().post("/runs", registerBody(exec("d4"), pkg, AgentFixture.REAL_FINGERPRINT, q, running), p.m().credential).status())
                .as("outra execucao no pedido").isEqualTo(409);
        MachineClient.Response reg = p.m().post("/runs", registerBody(exec("c3"), pkg, AgentFixture.REAL_FINGERPRINT, q, running), p.m().credential);
        assertThat(reg.status()).as(reg.raw()).isEqualTo(201);
        assertThat(fx.value("SELECT state FROM run_requests WHERE run_request_id = '" + q + "'")).isEqualTo("6");
        assertThat(fx.value("SELECT origin || ':' || (run_request_id = '" + q + "') FROM agent_runs WHERE agent_execution_id = '" + exec("c3") + "'"))
                .isEqualTo("2:true");
        // registro repetido (ACK perdido): o mesmo runId; sem o runRequestId: divergente
        MachineClient.Response again = p.m().post("/runs", registerBody(exec("c3"), pkg, AgentFixture.REAL_FINGERPRINT, q, running), p.m().credential);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("runId")).isEqualTo(reg.body().get("runId"));
        assertThat(p.m().post("/runs", registerBody(exec("c3"), pkg, AgentFixture.REAL_FINGERPRINT, null, running), p.m().credential).status()).isEqualTo(409);
        // ligado: sai da lista; uma unica execucao para o pedido
        assertThat(list(p.m()).raw()).doesNotContain(q.toString());
        assertThat(fx.count("SELECT count(*) FROM agent_runs WHERE run_request_id = '" + q + "'")).isEqualTo(1);
    }

    @Test
    void recusaNaoIniciadoOutraMaquinaRevogadoEVencido() {
        Paired p = paired();
        Paired outra = paired();
        UUID pkg = fx.localRun(p.worker(), p.wwa());

        UUID refused = request(pkg, p.worker(), p.agent());
        assertThat(decide(p.m(), refused, "{\"decision\":\"REFUSED\",\"reason\":\"QUALQUER\"}").status()).as("motivo fora da lista").isEqualTo(400);
        MachineClient.Response r = decide(p.m(), refused, "{\"decision\":\"REFUSED\",\"reason\":\"PACKAGE_NOT_APPROVED\"}");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("state").asString()).isEqualTo("REFUSED");
        assertThat(decide(p.m(), refused, "{\"decision\":\"REFUSED\",\"reason\":\"PACKAGE_NOT_APPROVED\"}").body().get("outcome").asString()).isEqualTo("UNCHANGED");
        assertThat(decide(p.m(), refused, "{\"decision\":\"ACCEPTED\",\"agentExecutionId\":\"" + exec("e5") + "\"}").status()).isEqualTo(409);

        // aceito e comprovadamente nao iniciado
        UUID notStarted = request(pkg, p.worker(), p.agent());
        assertThat(decide(p.m(), notStarted, "{\"decision\":\"ACCEPTED\",\"agentExecutionId\":\"" + exec("f6") + "\"}").status()).isEqualTo(200);
        MachineClient.Response ns = decide(p.m(), notStarted, "{\"decision\":\"NOT_STARTED\",\"agentExecutionId\":\"" + exec("f6") + "\"}");
        assertThat(ns.status()).as(ns.raw()).isEqualTo(200);
        assertThat(ns.body().get("state").asString()).isEqualTo("NOT_STARTED");
        assertThat(p.m().post("/runs", registerBody(exec("f6"), pkg, AgentFixture.REAL_FINGERPRINT, notStarted, "{\"state\":\"STARTING\"}"), p.m().credential)
                .status()).as("nao iniciado nao registra").isEqualTo(409);

        // pedido de outra maquina: invisivel e indecidivel (404, sem revelar)
        UUID mine = request(pkg, p.worker(), p.agent());
        assertThat(list(outra.m()).raw()).doesNotContain(mine.toString());
        assertThat(decide(outra.m(), mine, "{\"decision\":\"ACCEPTED\",\"agentExecutionId\":\"" + exec("a7") + "\"}").status()).isEqualTo(404);

        // revogado pela pessoa: a maquina recebe REVOKED (409), nada e aceito
        UUID revoked = request(pkg, p.worker(), p.agent());
        fx.value("UPDATE run_requests SET state = 8, revoked_by_membership_id = '" + fx.adminMembership + "' WHERE run_request_id = '" + revoked
                + "' RETURNING state");
        MachineClient.Response rv = decide(p.m(), revoked, "{\"decision\":\"ACCEPTED\",\"agentExecutionId\":\"" + exec("b8") + "\"}");
        assertThat(rv.status()).isEqualTo(409);
        assertThat(rv.body().get("outcome").asString()).isEqualTo("REVOKED");

        // vencido: a lista grava EXPIRED e o aceite e 409 EXPIRED
        UUID expired = request(pkg, p.worker(), p.agent());
        DB.asSuperuser("SET session_replication_role = replica",
                "UPDATE run_requests SET requested_at = now() - interval '20 minutes', valid_until = now() - interval '1 minute' WHERE run_request_id = '" + expired + "'",
                "SET session_replication_role = origin");
        assertThat(list(p.m()).raw()).doesNotContain(expired.toString());
        assertThat(fx.value("SELECT state FROM run_requests WHERE run_request_id = '" + expired + "'")).isEqualTo("7");
        MachineClient.Response ex = decide(p.m(), expired, "{\"decision\":\"ACCEPTED\",\"agentExecutionId\":\"" + exec("c9") + "\"}");
        assertThat(ex.status()).isEqualTo(409);
        assertThat(ex.body().get("outcome").asString()).isEqualTo("EXPIRED");
        assertThat(fx.count("SELECT count(*) FROM agent_runs WHERE agent_execution_id IN ('" + exec("b8") + "', '" + exec("c9") + "', '" + exec("e5") + "')"))
                .isZero();
    }
}
