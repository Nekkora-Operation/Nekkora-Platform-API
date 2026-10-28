package com.nekkora.agentgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.RealDatabase;
import com.nekkora.core.testsupport.RealRedis;
import com.nekkora.core.token.Tokens;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Canal da maquina de ponta a ponta no processo REAL do gateway (porta aleatoria, papel nekkora_ingestion), PostgreSQL 16
 * com 001-025 e Redis reais. A maquina e um Agent SIMULADO ({@link MachineClient}) alimentado com os registros REAIS do Agent
 * empacotado (COMPAT-001): envelopes e estado.json do sucesso (a1), do cancelamento (a2) e da queda do Agent (a5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = AgentGatewayApplication.class)
class AgentGatewayIT {

    static final RealDatabase DB = RealDatabase.shared();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static AgentFixture fx;
    static AgentFixture other;

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
        other = new AgentFixture(DB);
    }

    MachineClient client() {
        return new MachineClient(port, "10.60." + (int) (Math.random() * 250) + "." + (1 + (int) (Math.random() * 250)));
    }

    /** Maquina pareada num Worker novo, autorizado no workspace, com uma autorizacao de execucao local. */
    record Paired(MachineClient m, UUID worker, UUID authorization, JsonNode identity) {
    }

    Paired paired(AgentFixture f) {
        UUID worker = f.worker("W-" + UUID.randomUUID().toString().substring(0, 6));
        UUID wwa = f.authorizeWorker(worker);
        UUID authorization = f.localRun(worker, wwa);
        MachineClient m = client();
        JsonNode id = m.pair(f.pairingCode(worker, "Estacao 1", "15 minutes"));
        return new Paired(m, worker, authorization, id);
    }

    static List<String> envelopes(String name) throws IOException {
        try (InputStream in = AgentGatewayIT.class.getResourceAsStream("/agent-real/" + name + "-atividade.jsonl")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
        }
    }

    /** O relatorio do supervisor como o Agent o montaria do estado.json real (so os campos do contrato). */
    static String supervisorFromEstado(String name) throws IOException {
        try (InputStream in = AgentGatewayIT.class.getResourceAsStream("/agent-real/" + name + "-estado.json")) {
            JsonNode e = JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            ObjectNode s = JSON.createObjectNode();
            for (String k : List.of("state", "code", "exitCode", "counts", "created", "started", "ended", "adopted", "suspensions", "locks")) {
                s.set(k, e.get(k));
            }
            return s.toString();
        }
    }

    static String executionOf(String name) throws IOException {
        return JSON.readTree(envelopes(name).get(0)).get("agent_execution_id").asString();
    }

    static String running() {
        return "{\"state\":\"RUNNING\",\"started\":\"2026-10-28T12:35:05.958158Z\"}";
    }

    // =========================================================================== pareamento e credencial

    @Test
    void pareamentoRepetidoComRespostaPerdidaDevolveOMesmoResultadoEOutroSegredoERecusado() {
        UUID worker = fx.worker("W-rep");
        String code = fx.pairingCode(worker, "Estacao rep", "15 minutes");
        MachineClient m = client();
        String secret = Tokens.newToken();
        MachineClient.Response first = m.redeem(code, secret);
        MachineClient.Response again = m.redeem(code, secret);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("credentialId")).isEqualTo(first.body().get("credentialId"));
        assertThat(again.body().get("agentId")).isEqualTo(first.body().get("agentId"));
        assertThat(first.raw()).doesNotContain(secret);
        assertThat(m.redeem(code, Tokens.newToken()).status()).isEqualTo(401);
        assertThat(fx.count("SELECT count(*) FROM agents WHERE organization_id = '" + fx.org + "' AND name = 'Estacao rep'")).isEqualTo(1);
        assertThat(fx.count("SELECT count(*) FROM agent_admin_events WHERE organization_id = '" + fx.org + "' AND act = 3 AND worker_id = '" + worker + "'"))
                .isEqualTo(1);
        // o banco guarda so o hash do segredo
        assertThat(fx.value("SELECT credential_hash FROM agent_credentials WHERE credential_id = '" + first.body().get("credentialId").asString() + "'"))
                .isEqualTo(Tokens.sha256Hex(secret));
    }

    @Test
    void codigoVencidoCanceladoMalformadoOuComOrganizacaoTrocadaEUmSo401() throws Exception {
        UUID worker = fx.worker("W-venc");
        String expired = fx.pairingCode(worker, "Estacao venc", "1 second");
        String cancelled = fx.pairingCode(worker, "Estacao canc", "15 minutes");
        UUID cancelledId = UUID.fromString(cancelled.split("\\.")[2]);
        DB.asSuperuser("UPDATE agent_enrollments SET cancelled_at = now(), cancelled_by_membership_id = '" + fx.adminMembership + "' WHERE enrollment_id = '"
                + cancelledId + "'");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (fx.count("SELECT count(*) FROM agent_enrollments WHERE code_hash = '" + Tokens.sha256Hex(expired) + "' AND expires_at < clock_timestamp()") == 0) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(100);
        }
        String valid = fx.pairingCode(worker, "Estacao troca", "15 minutes");
        String swappedOrg = valid.replace(fx.org.toString(), other.org.toString());
        MachineClient m = client();
        for (String code : List.of(expired, cancelled, swappedOrg, "nkp1.lixo", valid.substring(0, valid.length() - 1) + "A")) {
            MachineClient.Response r = m.redeem(code, Tokens.newToken());
            assertThat(r.status()).as(code.length() > 20 ? "codigo" : code).isEqualTo(401);
            assertThat(r.raw()).isEqualTo("{\"error\":\"unauthenticated\"}");
        }
        assertThat(m.redeem(valid, Tokens.newToken()).status()).isEqualTo(200);
    }

    @Test
    void credencialDeMaquinaSeparadaDeSessaoEContratoObrigatorio() {
        Paired p = paired(fx);
        assertThat(p.m().heartbeat().status()).isEqualTo(200);
        MachineClient anon = client();
        assertThat(anon.heartbeat().status()).isEqualTo(401);
        // cookie de sessao humana nao vale aqui
        MachineClient.Response cookie = anon.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(anon.base + "/authorizations"))
                .header("Cookie", "__Host-nekkora_session=" + Tokens.newToken()).GET(), null);
        assertThat(cookie.status()).isEqualTo(401);
        // segredo errado, organizacao trocada
        String wrong = p.m().credential.substring(0, p.m().credential.lastIndexOf('.') + 1) + Tokens.newToken();
        String hb = "{\"agentVersion\":\"1\",\"contractVersion\":\"1.0\",\"capabilities\":[]}";
        assertThat(anon.post("/heartbeat", hb, wrong).status()).isEqualTo(401);
        assertThat(anon.post("/heartbeat", hb, p.m().credential.replace(fx.org.toString(), other.org.toString())).status()).isEqualTo(401);
        // sem o cabecalho de contrato, ou com versao maior desconhecida
        MachineClient.Response noContract = anon.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(anon.base + "/authorizations")).GET()
                .header("Nekkora-Agent-Contract", "2.0"), p.m().credential);
        assertThat(noContract.status()).isEqualTo(400);
        assertThat(noContract.raw()).isEqualTo("{\"error\":\"unsupported_contract\"}");
        // heartbeat: presenca e capacidades; credencial com prazo
        assertThat(fx.value("SELECT capabilities::text FROM agent_presence WHERE agent_id = '" + p.identity().get("agentId").asString() + "'"))
                .isEqualTo("{OBS_1_0,RUN_LOCAL,SUPERVISOR_REPORT_1_0}");
        assertThat(fx.value("SELECT last_heartbeat_at IS NOT NULL FROM agents WHERE agent_id = '" + p.identity().get("agentId").asString() + "'")).isEqualTo("t");
    }

    @Test
    void rotacaoExigeACredencialAtualERevogaAAnteriorNaMesmaTransacao() {
        Paired p = paired(fx);
        UUID agent = UUID.fromString(p.identity().get("agentId").asString());
        String oldCredential = p.m().credential;
        String rotation = fx.rotationCode(agent, p.worker());
        // sem a credencial atual: recusado
        MachineClient anon = client();
        assertThat(anon.redeem(rotation, Tokens.newToken()).status()).isEqualTo(401);
        String newSecret = Tokens.newToken();
        MachineClient.Response r = p.m().redeem(rotation, newSecret);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("agentId").asString()).isEqualTo(agent.toString());
        // repeticao com resposta perdida: mesmo resultado (a maquina ainda so tem a credencial antiga)
        assertThat(p.m().redeem(rotation, newSecret).body().get("credentialId")).isEqualTo(r.body().get("credentialId"));
        assertThat(anon.post("/heartbeat", "{\"agentVersion\":\"1\",\"contractVersion\":\"1.0\",\"capabilities\":[]}", oldCredential).status()).isEqualTo(401);
        String fresh = MachineToken.credential(fx.org, UUID.fromString(r.body().get("credentialId").asString()), newSecret);
        assertThat(anon.post("/heartbeat", "{\"agentVersion\":\"1\",\"contractVersion\":\"1.0\",\"capabilities\":[]}", fresh).status()).isEqualTo(200);
        assertThat(fx.value("SELECT revocation_reason || ':' || (revoked_by_membership_id = '" + fx.adminMembership + "') FROM agent_credentials "
                + "WHERE credential_id = '" + p.identity().get("credentialId").asString() + "'")).isEqualTo("1:true");
    }

    @Test
    void repeticaoDoResgateNaoDesfazRotacaoNemReativaCredencialRevogadaNemRestauraAssociacao() {
        UUID worker = fx.worker("W-repr");
        String code = fx.pairingCode(worker, "Estacao repr", "15 minutes");
        MachineClient m = client();
        JsonNode first = m.pair(code);
        String secret = m.secret;
        UUID agent = UUID.fromString(first.get("agentId").asString());
        String hb = "{\"agentVersion\":\"1\",\"contractVersion\":\"1.0\",\"capabilities\":[]}";
        // rotacao feita: repetir o PAREAMENTO com o segredo antigo nao devolve o vinculo antigo
        String rotation = fx.rotationCode(agent, worker);
        String newSecret = Tokens.newToken();
        MachineClient.Response rotated = m.redeem(rotation, newSecret);
        assertThat(rotated.status()).isEqualTo(200);
        MachineClient.Response oldRepeat = m.redeem(code, secret);
        assertThat(oldRepeat.status()).isEqualTo(401);
        assertThat(oldRepeat.raw()).isEqualTo("{\"error\":\"unauthenticated\"}");
        // a repeticao da rotacao com o segredo novo ainda vale enquanto a credencial nova vale
        assertThat(m.redeem(rotation, newSecret).status()).isEqualTo(200);
        // revogacao do Agent (o que a Platform faz): nenhuma repeticao devolve vinculo nem muda nada
        String newCredential = rotated.body().get("credentialId").asString();
        DB.asSuperuser("UPDATE agent_credentials SET revoked_at = now(), revoked_by_membership_id = '" + fx.adminMembership
                + "', revocation_reason = 3 WHERE credential_id = '" + newCredential + "'");
        DB.asSuperuser("UPDATE worker_agent_assignments SET status = 2, ended_at = now(), ended_by_membership_id = '" + fx.adminMembership
                + "' WHERE agent_id = '" + agent + "' AND status = 1");
        DB.asSuperuser("UPDATE agents SET status = 4 WHERE agent_id = '" + agent + "'");
        for (String[] attempt : List.of(new String[] {rotation, newSecret}, new String[] {code, secret})) {
            MachineClient.Response r = client().redeem(attempt[0], attempt[1]);
            assertThat(r.status()).isEqualTo(401);
            assertThat(r.raw()).isEqualTo("{\"error\":\"unauthenticated\"}");
        }
        String fresh = MachineToken.credential(fx.org, UUID.fromString(newCredential), newSecret);
        assertThat(client().post("/heartbeat", hb, fresh).status()).isEqualTo(401);
        assertThat(fx.value("SELECT a.status || '|' || (SELECT count(*) FROM agent_credentials c WHERE c.agent_id = a.agent_id AND c.revoked_at IS NULL) || '|' "
                + "|| (SELECT count(*) FROM worker_agent_assignments wa WHERE wa.agent_id = a.agent_id AND wa.status = 1) FROM agents a WHERE a.agent_id = '"
                + agent + "'")).isEqualTo("4|0|0");
    }

    @Test
    void doisResgatesConcorrentesNoMesmoWorkerUmPassa() throws Exception {
        UUID worker = fx.worker("W-corrida");
        String c1 = fx.pairingCode(worker, "Estacao A", "15 minutes");
        String c2 = fx.pairingCode(worker, "Estacao B", "15 minutes");
        CountDownLatch go = new CountDownLatch(1);
        CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() -> { await(go); return client().redeem(c1, Tokens.newToken()).status(); });
        CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> { await(go); return client().redeem(c2, Tokens.newToken()).status(); });
        go.countDown();
        List<Integer> statuses = new ArrayList<>(List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS)));
        statuses.sort(Integer::compareTo);
        assertThat(statuses).containsExactly(200, 409);
        assertThat(fx.count("SELECT count(*) FROM worker_agent_assignments WHERE worker_id = '" + worker + "' AND status = 1")).isEqualTo(1);
        assertThat(fx.count("SELECT count(*) FROM agent_enrollments WHERE worker_id = '" + worker + "' AND consumed_at IS NOT NULL")).isEqualTo(1);
    }

    static void await(CountDownLatch l) {
        try {
            l.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =========================================================================== registro, eventos e desfecho

    @Test
    void execucaoRealDoAgentForaDeOrdemComAckPerdidoEDesfechoDoSupervisor() throws Exception {
        Paired p = paired(fx);
        MachineClient m = p.m();
        JsonNode auths = m.authorizations().body().get("items");
        assertThat(auths).hasSize(1);
        assertThat(auths.get(0).get("packageFingerprint").asString()).isEqualTo(AgentFixture.REAL_FINGERPRINT);
        assertThat(auths.get(0).get("packageFingerprintRule").asString()).isEqualTo("nekkora-agent/package-fingerprint/1");
        String ex = executionOf("a1-sucesso");
        MachineClient.Response reg = m.register(ex, p.authorization().toString(), AgentFixture.REAL_FINGERPRINT, running());
        assertThat(reg.status()).isEqualTo(201);
        String runId = reg.body().get("runId").asString();
        // registro repetido: o mesmo runId; com outro pacote: 409
        MachineClient.Response again = m.register(ex, p.authorization().toString(), AgentFixture.REAL_FINGERPRINT, running());
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("runId").asString()).isEqualTo(runId);
        assertThat(m.register(ex, p.authorization().toString(), "0".repeat(64), running()).status()).isEqualTo(409);

        List<String> all = envelopes("a1-sucesso");
        // lote 2 antes do lote 1: gravado, mas o ACK nao avanca sobre o buraco
        MachineClient.Response b2 = m.events(ex, all.subList(100, 200), null);
        assertThat(b2.status()).isEqualTo(200);
        assertThat(b2.body().get("storedThrough").asLong()).isZero();
        MachineClient.Response b1 = m.events(ex, all.subList(0, 100), null);
        assertThat(b1.body().get("storedThrough").asLong()).isEqualTo(200);
        // ACK perdido: o mesmo lote de novo e DUPLICATE, e o ACK e o mesmo
        MachineClient.Response dup = m.events(ex, all.subList(0, 100), null);
        assertThat(dup.body().get("storedThrough").asLong()).isEqualTo(200);
        dup.body().get("results").forEach(x -> assertThat(x.get("status").asString()).isEqualTo("DUPLICATE"));
        MachineClient.Response rest = m.events(ex, all.subList(200, all.size()), null);
        assertThat(rest.body().get("storedThrough").asLong()).isEqualTo(all.size());
        assertThat(rest.body().get("settledThrough").asLong()).isEqualTo(all.size());

        // desfecho: so pelo supervisor
        assertThat(fx.value("SELECT supervisor_state FROM agent_runs WHERE agent_run_id = '" + runId + "'")).isEqualTo("3");
        MachineClient.Response done = m.supervisor(ex, supervisorFromEstado("a1-sucesso"));
        assertThat(done.status()).isEqualTo(200);
        assertThat(done.body().get("outcome").asString()).isEqualTo("APPLIED");
        assertThat(m.supervisor(ex, supervisorFromEstado("a1-sucesso")).body().get("outcome").asString()).isEqualTo("UNCHANGED");
        MachineClient.Response contra = m.supervisor(ex, "{\"state\":\"FAILED\",\"code\":\"AGT-EXE-022\",\"exitCode\":1}");
        assertThat(contra.status()).isEqualTo(409);
        assertThat(contra.body().get("state").asString()).isEqualTo("SUCCEEDED");

        String row = fx.value("SELECT supervisor_state || '|' || exit_code || '|' || supervisor_counts::text || '|' || events_stored || '|' || stored_through || '|' "
                + "|| collection_completed_sequence || '|' || framework_execution_id || '|' || supervisor_conflicts || '|' || framework_events "
                + "FROM agent_runs WHERE agent_run_id = '" + runId + "'");
        assertThat(row).isEqualTo("4|0|{\"SUCCEEDED\": 5}|294|294|294|ex-d1dd0f7020ff40aeb12b|1|289");
        assertThat(fx.count("SELECT count(*) FROM agent_run_reports WHERE agent_run_id = '" + runId + "'")).isEqualTo(5);
        // cada envelope gravado e exatamente o canonico do Agent (sem duplicata)
        assertThat(fx.count("SELECT count(DISTINCT sequence) FROM agent_run_events WHERE agent_run_id = '" + runId + "'")).isEqualTo(294);
    }

    @Test
    void mesmaSequenceComConteudoDiferenteERecusadaERegistrada() throws Exception {
        Paired p = paired(fx);
        String ex = executionOf("a2-cancelamento");
        p.m().register(ex, p.authorization().toString(), AgentFixture.REAL_FINGERPRINT, running());
        List<String> all = envelopes("a2-cancelamento");
        p.m().events(ex, all.subList(0, 10), null);
        ObjectNode forged = (ObjectNode) JSON.readTree(all.get(4));
        forged.put("severity", "ERROR");
        MachineClient.Response r = p.m().events(ex, List.of(forged.toString()), null);
        assertThat(r.body().get("results").get(0).get("status").asString()).isEqualTo("CONFLICT");
        assertThat(fx.value("SELECT severity FROM agent_run_events e JOIN agent_runs r ON r.agent_run_id = e.agent_run_id WHERE r.agent_execution_id = '" + ex
                + "' AND r.agent_id = '" + p.identity().get("agentId").asString() + "' AND e.sequence = 5")).isEqualTo("1");
        assertThat(fx.value("SELECT reason || ':' || (existing_sha256 <> offered_sha256) FROM agent_ingestion_refusals WHERE agent_id = '"
                + p.identity().get("agentId").asString() + "' AND kind = 1")).isEqualTo("sequence_conflict:true");
    }

    @Test
    void lacunaDeclaradaNaoContaComoRecebidaEResultadoDesconhecidoContinuaDesconhecido() throws Exception {
        Paired p = paired(fx);
        MachineClient m = p.m();
        String ex = executionOf("a5-agent-derrubado");
        m.register(ex, p.authorization().toString(), AgentFixture.REAL_FINGERPRINT, running());
        List<String> all = envelopes("a5-agent-derrubado");
        m.events(ex, all.subList(0, 299), null);
        MachineClient.Response gap = m.events(ex, all.subList(310, 500), "[{\"from\":300,\"to\":310}]");
        assertThat(gap.body().get("storedThrough").asLong()).isEqualTo(299);
        assertThat(gap.body().get("settledThrough").asLong()).isEqualTo(500);
        assertThat(gap.body().get("missing").get(0).get("status").asString()).isEqualTo("STORED");
        // evento de um numero declarado ausente: recusado; declaracao sobre evento gravado: recusada
        MachineClient.Response late = m.events(ex, List.of(all.get(304)), null);
        assertThat(late.body().get("results").get(0).get("status").asString()).isEqualTo("CONFLICT");
        assertThat(m.events(ex, List.of(), "[{\"from\":10,\"to\":12}]").body().get("missing").get(0).get("status").asString()).isEqualTo("CONFLICT");
        m.events(ex, all.subList(500, all.size()), null);
        assertThat(m.supervisor(ex, supervisorFromEstado("a5-agent-derrubado")).body().get("state").asString()).isEqualTo("OUTCOME_UNKNOWN");
        String row = fx.value("SELECT supervisor_state || '|' || supervisor_code || '|' || stored_through || '|' || settled_through || '|' || lost_local || '|' "
                + "|| declared_missing FROM agent_runs WHERE agent_execution_id = '" + ex + "' AND agent_id = '" + p.identity().get("agentId").asString() + "'");
        assertThat(row).isEqualTo("9|AGT-EXE-025|299|668|true|11");
    }

    @Test
    void falhaDeGravacaoNoMeioDoLoteNaoDaAckNemGravaNada() throws Exception {
        Paired p = paired(fx);
        MachineClient m = p.m();
        String ex = executionOf("a1-sucesso");
        m.register(ex, p.authorization().toString(), AgentFixture.REAL_FINGERPRINT, running());
        List<String> all = envelopes("a1-sucesso");
        String run = fx.value("SELECT agent_run_id FROM agent_runs WHERE agent_execution_id = '" + ex + "' AND agent_id = '" + p.identity().get("agentId").asString() + "'");
        // falha provocada DEPOIS das primeiras insercoes do lote (gatilho de teste, criado e removido por fora)
        DB.asSuperuser("CREATE FUNCTION public.teste_falha_025() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.agent_run_id = '" + run
                        + "' AND NEW.sequence = 50 THEN RAISE EXCEPTION 'falha provocada'; END IF; RETURN NEW; END $$",
                "CREATE TRIGGER trg_teste_falha_025 BEFORE INSERT ON public.agent_run_events FOR EACH ROW EXECUTE FUNCTION public.teste_falha_025()");
        try {
            MachineClient.Response r = m.events(ex, all.subList(0, 100), null);
            assertThat(r.status()).isEqualTo(500);
            assertThat(r.body().has("storedThrough")).isFalse();
            assertThat(fx.count("SELECT count(*) FROM agent_run_events WHERE agent_run_id = '" + run + "'")).isZero();
            assertThat(fx.value("SELECT stored_through FROM agent_runs WHERE agent_run_id = '" + run + "'")).isEqualTo("0");
        } finally {
            DB.asSuperuser("DROP TRIGGER trg_teste_falha_025 ON public.agent_run_events", "DROP FUNCTION public.teste_falha_025()");
        }
        // o reenvio entrega os mesmos fatos
        assertThat(m.events(ex, all.subList(0, 100), null).body().get("storedThrough").asLong()).isEqualTo(100);
    }

    @Test
    void revogacaoConcorrenteEsperaOLoteEmCursoOuORecusaSeConfirmadaAntes() throws Exception {
        Paired p = paired(fx);
        MachineClient m = p.m();
        String ex = executionOf("a2-cancelamento");
        m.register(ex, p.authorization().toString(), AgentFixture.REAL_FINGERPRINT, running());
        List<String> all = envelopes("a2-cancelamento");
        String credentialId = p.identity().get("credentialId").asString();
        try (Connection admin = DB.connectAs("nekkora_platform")) {
            admin.setAutoCommit(false);
            try (Statement st = admin.createStatement()) {
                st.execute("SELECT set_config('nekkora.organization_id', '" + fx.org + "', true)");
                st.executeUpdate("UPDATE agent_credentials SET revoked_at = clock_timestamp(), revoked_by_membership_id = '" + fx.adminMembership
                        + "', revocation_reason = 3 WHERE credential_id = '" + credentialId + "'");
                // lote durante a revogacao NAO confirmada: espera o lock da credencial (observado no pg_stat_activity)
                CompletableFuture<MachineClient.Response> batch = CompletableFuture.supplyAsync(() -> m.events(ex, all.subList(0, 50), null));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (waitingOnLock() == 0) {
                    assertThat(System.nanoTime()).isLessThan(deadline);
                    Thread.sleep(50);
                }
                assertThat(batch.isDone()).isFalse();
                admin.commit();
                MachineClient.Response r = batch.get(30, TimeUnit.SECONDS);
                assertThat(r.status()).isEqualTo(401);
            }
        }
        assertThat(fx.count("SELECT count(*) FROM agent_run_events e JOIN agent_runs r ON r.agent_run_id = e.agent_run_id WHERE r.agent_execution_id = '" + ex
                + "' AND r.agent_id = '" + p.identity().get("agentId").asString() + "'")).isZero();
    }

    static long waitingOnLock() throws Exception {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE usename = 'nekkora_ingestion' AND wait_event_type = 'Lock'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // =========================================================================== isolamento e entrada maliciosa

    @Test
    void autorizacaoDeOutroWorkerOuDeOutraOrganizacaoERecusada() throws Exception {
        Paired p = paired(fx);
        Paired q = paired(fx);
        Paired o = paired(other);
        String ex = "ex-" + "ab".repeat(16);
        assertThat(p.m().register(ex, q.authorization().toString(), AgentFixture.REAL_FINGERPRINT, running()).status()).isEqualTo(403);
        assertThat(p.m().register(ex, o.authorization().toString(), AgentFixture.REAL_FINGERPRINT, running()).status()).isEqualTo(403);
        assertThat(p.m().register(ex, p.authorization().toString(), "1".repeat(64), running()).status()).isEqualTo(403);
        // nenhum registro criado por essas tentativas
        assertThat(fx.count("SELECT count(*) FROM agent_runs WHERE agent_execution_id = '" + ex + "'")).isZero();
        // a mesma identidade local em duas maquinas sao duas execucoes distintas (nunca vinculo por coincidencia)
        assertThat(p.m().register(ex, p.authorization().toString(), AgentFixture.REAL_FINGERPRINT, running()).status()).isEqualTo(201);
        assertThat(q.m().register(ex, q.authorization().toString(), AgentFixture.REAL_FINGERPRINT, running()).status()).isEqualTo(201);
        assertThat(fx.count("SELECT count(*) FROM agent_runs WHERE agent_execution_id = '" + ex + "'")).isEqualTo(2);
        // eventos de uma execucao com o id de outra (na URL) sao recusados um a um
        List<String> a1 = envelopes("a1-sucesso");
        MachineClient.Response mismatch = p.m().events(ex, a1.subList(0, 3), null);
        mismatch.body().get("results").forEach(x -> assertThat(x.get("reason").asString()).isEqualTo("execution_mismatch"));
    }

    @Test
    void entradaMaliciosaERecusadaSemTocarOBanco() throws Exception {
        Paired p = paired(fx);
        String ex = executionOf("a1-sucesso");
        p.m().register(ex, p.authorization().toString(), AgentFixture.REAL_FINGERPRINT, running());
        ObjectNode free = (ObjectNode) JSON.readTree(envelopes("a1-sucesso").get(0));
        free.put("message", "SENTINELA_CPF_123.456.789-00");
        MachineClient.Response r = p.m().events(ex, List.of(free.toString()), null);
        assertThat(r.body().get("results").get(0).get("status").asString()).isEqualTo("INVALID");
        assertThat(fx.count("SELECT count(*) FROM agent_run_events WHERE envelope::text LIKE '%SENTINELA%'")).isZero();
        // chave duplicada e profundidade acima do limite: 400 antes do banco
        String dupKey = "{\"events\":[],\"events\":[]}";
        assertThat(p.m().post("/runs/" + ex + "/events", dupKey, p.m().credential).status()).isEqualTo(400);
        String deep = "{\"events\":[" + "[".repeat(12) + "]".repeat(12) + "]}";
        assertThat(p.m().post("/runs/" + ex + "/events", deep, p.m().credential).status()).isEqualTo(400);
        // lote acima de 500 eventos
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            many.add(envelopes("a1-sucesso").get(0));
        }
        assertThat(p.m().events(ex, many, null).status()).isEqualTo(400);
        // nenhum segredo, codigo ou credencial no log do servidor de banco
        assertThat(DB.serverLog()).doesNotContain(p.m().secret);
    }
}
