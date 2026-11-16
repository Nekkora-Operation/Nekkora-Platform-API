package com.nekkora.agentgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

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

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T02 - entrega de uma tentativa a maquina, pelo gateway REAL (HTTP, papel
 * nekkora_ingestion, PostgreSQL com as migrations ate a 046, recurso LIGADO). A solicitacao e a tentativa sao criadas por
 * fixture (como superusuario, com os gatilhos da 046 valendo): a criacao pelas pessoas, com permissoes, confirmacao reforcada
 * e o motivo de cada exigencia que falta, e conferida no modulo da Platform (RemoteExecutionIT).
 */
class RemoteDeliveryIT extends RemoteEnabledGatewayIT {

    static AgentFixture fx;

    @BeforeAll
    static void fixture() {
        fx = new AgentFixture(DB);
        // as dependencias da automacao da fixture foram declaradas (a solicitacao fixa a revisao vigente)
        DB.asSuperuser("INSERT INTO automation_runtime_requirements (automation_id, revision_number, organization_id, workspace_id, source, dependencies, "
                + "content_sha256, declared_by_membership_id) VALUES ('" + fx.automation + "', 1, '" + fx.org + "', '" + fx.workspace + "', 3, '[\"idna==3.7\"]'::jsonb, '"
                + "a".repeat(64) + "', '" + fx.adminMembership + "')");
        // 048: toda tentativa nasce com o pacote da versao fixado; sem pacote registrado, a tentativa nao nasce
        try {
            publishPackage(("PK pacote da fixture " + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** O identificador opaco de um ambiente Python (43 caracteres base64url), como a maquina o publicaria. */
    static final String ENVIRONMENT = "E".repeat(43);
    static final String CAPABLE = "{\"agentVersion\":\"0.2.0+client\",\"contractVersion\":\"1.0\",\"capabilities\":[\"REMOTE_RUN_1_0\",\"PY_INVENTORY_1_0\"]}";

    /** Uma maquina habilitada, compativel e com a autorizacao da versao para o Worker dela. */
    private record Machine(MachineClient client, String agent, UUID worker, UUID workerWorkspace, UUID authorization, String credential) {
    }

    private Machine machine(boolean capable) {
        UUID worker = fx.worker("W-ent-" + UUID.randomUUID().toString().substring(0, 6));
        UUID ww = fx.authorizeWorker(worker);
        UUID authorization = fx.localRun(worker, ww);
        MachineClient m = new MachineClient(port, "10.65." + (int) (Math.random() * 250) + "." + (1 + (int) (Math.random() * 250)));
        String secret = Tokens.newToken();
        MachineClient.Response r = m.redeemWithIntent(fx.remotePairingCode(worker, "Estacao remota"), secret, "pair-remote");
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        String credential = MachineToken.credential(fx.org, UUID.fromString(r.body().get("credentialId").asString()), secret);
        if (capable) {
            assertThat(m.post("/heartbeat", CAPABLE, credential).status()).isEqualTo(200);
        }
        // 047: a tentativa so nasce com o Python da automacao escolhido NESTA maquina, do inventario dela
        String agent = r.body().get("agentId").asString();
        DB.asSuperuser("INSERT INTO agent_python_environments (agent_id, environment_id, organization_id, python_version, kind, observed_at) VALUES ('" + agent
                + "', '" + ENVIRONMENT + "', '" + fx.org + "', '3.12.10', 1, clock_timestamp())");
        DB.asSuperuser("INSERT INTO automation_python_selections (automation_id, agent_id, organization_id, workspace_id, environment_id, python_version, "
                + "selected_by_membership_id) VALUES ('" + fx.automation + "', '" + agent + "', '" + fx.org + "', '" + fx.workspace + "', '" + ENVIRONMENT
                + "', '3.0.0', '" + fx.adminMembership + "')");
        return new Machine(m, agent, worker, ww, authorization, credential);
    }

    /** Solicitacao e primeira tentativa, na fila (fixture; os gatilhos da 046 conferem as exigencias). Devolve o id da tentativa. */
    private String queued(Machine m) {
        String execution = execution();
        return attempt(execution, 1, m);
    }

    private String execution() {
        return fx.value("INSERT INTO remote_executions (organization_id, workspace_id, automation_id, automation_version_id, package_fingerprint, "
                + "package_fingerprint_rule, requirements_revision, effective_dependencies, request_key, requested_by_membership_id) SELECT '" + fx.org + "', '"
                + fx.workspace + "', '" + fx.automation + "', '" + fx.version + "', command_json -> 'agent_package' ->> 'fingerprint', "
                + "command_json -> 'agent_package' ->> 'fingerprint_rule', 1, '[\"idna==3.7\"]'::jsonb, gen_random_uuid(), '" + fx.adminMembership
                + "' FROM automation_versions WHERE automation_version_id = '" + fx.version + "' RETURNING remote_execution_id");
    }

    private String attempt(String execution, int number, Machine m) {
        return fx.value("INSERT INTO remote_attempts (organization_id, remote_execution_id, attempt_number, agent_id, worker_id, authorization_id, "
                + "created_by_membership_id, offer_valid_until) VALUES ('" + fx.org + "', '" + execution + "', " + number + ", '" + m.agent() + "', '" + m.worker()
                + "', '" + m.authorization() + "', '" + fx.adminMembership + "', clock_timestamp() + interval '15 minutes') RETURNING attempt_id");
    }

    private MachineClient.Response next(Machine m) {
        return m.client().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/agent/api/v1/remote/next")).GET(), m.credential());
    }

    private static String grant(JsonNode offer) {
        return "\"token\":\"" + offer.get("token").asString() + "\",\"epoch\":" + offer.get("epoch").asInt();
    }

    private MachineClient.Response ack(Machine m, String attempt, String grant) {
        return m.client().post("/remote/attempts/" + attempt + "/ack", "{" + grant + "}", m.credential());
    }

    private MachineClient.Response state(Machine m, String attempt, String grant, String state, String extra) {
        return m.client().post("/remote/attempts/" + attempt + "/state", "{" + grant + ",\"state\":\"" + state + "\"" + (extra == null ? "" : "," + extra) + "}",
                m.credential());
    }

    /** Leva a tentativa ate RECEIVED e devolve token e epoca vigentes. */
    private String received(Machine m, String attempt) {
        JsonNode offer = next(m).body().get("attempt");
        assertThat(offer.get("attemptId").asString()).isEqualTo(attempt);
        String grant = grant(offer);
        assertThat(ack(m, attempt, grant).status()).isEqualTo(200);
        return grant;
    }

    private MachineClient.Response download(Machine m, String attempt, String token, Integer epoch) {
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://localhost:" + port + "/agent/api/v1/remote/attempts/" + attempt + "/package")).GET();
        if (token != null) {
            b.header("Nekkora-Attempt-Token", token);
        }
        if (epoch != null) {
            b.header("Nekkora-Attempt-Epoch", String.valueOf(epoch));
        }
        return m.client().send(b, m.credential());
    }

    private static String sha256(byte[] data) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
    }

    /** O pacote publicado da versao da fixture: os bytes no armazenamento e a linha do artefato com a impressao da versao. */
    private static String publishPackage(byte[] zip) throws Exception {
        String sha = sha256(zip);
        new com.nekkora.core.artifact.LocalArtifactStore(PACKAGES).put(fx.org, sha, zip);
        untriggered("DELETE FROM automation_artifacts WHERE automation_version_id = '" + fx.version + "'");
        untriggered("INSERT INTO automation_artifacts (organization_id, workspace_id, automation_id, automation_version_id, sha256, size_bytes, package_kind, "
                + "fingerprint_rule, fingerprint, files_count, manifest_version, framework, execution_mode, storage_key, received_by_membership_id) SELECT '"
                + fx.org + "', '" + fx.workspace + "', '" + fx.automation + "', '" + fx.version + "', '" + sha + "', " + zip.length + ", 1, "
                + "command_json -> 'agent_package' ->> 'fingerprint_rule', command_json -> 'agent_package' ->> 'fingerprint', 3, '1.4.0', 'nekkora-rpa==1.0.0', "
                + "'single', '" + sha + "', '" + fx.adminMembership + "' FROM automation_versions WHERE automation_version_id = '" + fx.version + "'");
        return sha;
    }

    private MachineClient.Response upload(Machine m, String attempt, String token, Integer epoch, int number, String name, byte[] data, String sha) {
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://localhost:" + port + "/agent/api/v1/remote/attempts/" + attempt + "/results/" + number))
                .header("Content-Type", "application/octet-stream").PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(data));
        if (token != null) {
            b.header("Nekkora-Attempt-Token", token);
        }
        if (epoch != null) {
            b.header("Nekkora-Attempt-Epoch", String.valueOf(epoch));
        }
        if (name != null) {
            b.header("Nekkora-Result-Name", java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
        }
        if (sha != null) {
            b.header("Nekkora-Result-Sha256", sha);
        }
        return m.client().send(b, m.credential());
    }

    private MachineClient.Response complete(Machine m, String attempt, String grant, int sent, int skipped) {
        return m.client().post("/remote/attempts/" + attempt + "/results/complete", "{" + grant + ",\"sent\":" + sent + ",\"skippedByLimit\":" + skipped + "}",
                m.credential());
    }

    @Test
    void aMaquinaEnviaOsResultadosDaTentativaQueExecutouEFechaOEnvio() throws Exception {
        Machine m = machine(true);
        String attempt = queued(m);
        JsonNode offer = next(m).body().get("attempt");
        String token = offer.get("token").asString();
        int epoch = offer.get("epoch").asInt();
        String grant = grant(offer);
        assertThat(ack(m, attempt, grant).status()).isEqualTo(200);
        byte[] report = "linha 1\nlinha 2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = sha256(report);
        // tentativa que ainda nao iniciou: nenhum resultado e aceito
        assertThat(state(m, attempt, grant, "PREPARING", null).status()).isEqualTo(200);
        assertThat(state(m, attempt, grant, "STARTING", null).status()).isEqualTo(200);
        assertThat(upload(m, attempt, token, epoch, 1, "relatorio.txt", report, sha).body().get("reason").asString()).isEqualTo("remote_attempt_transition");
        assertThat(state(m, attempt, grant, "RUNNING", null).status()).isEqualTo(200);
        // o que nao e aceito, sem gravar nada: sem token, numero fora da faixa, nome que sai da pasta, SHA-256 que nao e o dos bytes
        assertThat(upload(m, attempt, null, null, 1, "relatorio.txt", report, sha).status()).isEqualTo(400);
        assertThat(upload(m, attempt, token, epoch, 51, "relatorio.txt", report, sha).status()).isEqualTo(400);
        assertThat(upload(m, attempt, token, epoch, 0, "relatorio.txt", report, sha).status()).isEqualTo(400);
        for (String bad : new String[] {"../segredo.txt", "pasta/../../x", "/etc/passwd", "C:\\Users\\x.txt", "pasta\\x.txt", "pasta//x", "pasta/", ""}) {
            assertThat(upload(m, attempt, token, epoch, 1, bad, report, sha).status()).as("nome: " + bad).isEqualTo(400);
        }
        assertThat(upload(m, attempt, token, epoch, 1, "relatorio.txt", report, "0".repeat(64)).status()).isEqualTo(400);
        assertThat(fx.value("SELECT count(*) FROM remote_attempt_results WHERE attempt_id = '" + attempt + "'")).isEqualTo("0");
        // aceito: bytes no armazenamento (pelo hash), metadado no banco; repetir o mesmo nao muda nada
        MachineClient.Response ok = upload(m, attempt, token, epoch, 1, "relatorio.txt", report, sha);
        assertThat(ok.status()).as(ok.raw()).isEqualTo(200);
        assertThat(ok.body().get("created").asBoolean()).isTrue();
        assertThat(ok.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(java.nio.file.Files.readAllBytes(RESULTS.resolve(fx.org.toString()).resolve(sha + ".zip"))).isEqualTo(report);
        assertThat(upload(m, attempt, token, epoch, 1, "relatorio.txt", report, sha).body().get("created").asBoolean()).isFalse();
        // o mesmo numero com outro arquivo, ou o mesmo nome em outro numero: conflito
        byte[] other = "outro".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(upload(m, attempt, token, epoch, 1, "relatorio.txt", other, sha256(other)).body().get("reason").asString()).isEqualTo("remote_result_conflict");
        assertThat(upload(m, attempt, token, epoch, 2, "relatorio.txt", other, sha256(other)).body().get("reason").asString()).isEqualTo("remote_result_conflict");
        // token ou epoca que nao sao os vigentes; outra maquina
        assertThat(upload(m, attempt, Tokens.newToken(), epoch, 2, "x.txt", other, sha256(other)).body().get("reason").asString()).isEqualTo("remote_attempt_stale");
        Machine stranger = machine(true);
        assertThat(upload(stranger, attempt, token, epoch, 2, "x.txt", other, sha256(other)).status()).isEqualTo(404);
        // fechar antes do termino: nao
        assertThat(complete(m, attempt, grant, 1, 0).body().get("reason").asString()).isEqualTo("remote_attempt_transition");
        assertThat(state(m, attempt, grant, "SUCCEEDED", "\"exitCode\":0").status()).isEqualTo(200);
        // depois do termino o token continua valendo para os resultados: subpasta e nome com acento, arquivo vazio
        MachineClient.Response sub = upload(m, attempt, token, epoch, 2, "dados/relatório final.csv", other, sha256(other));
        assertThat(sub.status()).as(sub.raw()).isEqualTo(200);
        assertThat(upload(m, attempt, token, epoch, 3, "vazio.txt", new byte[0], sha256(new byte[0])).status()).isEqualTo(200);
        assertThat(fx.value("SELECT string_agg(file_name || ':' || size_bytes, ',' ORDER BY result_number) FROM remote_attempt_results WHERE attempt_id = '"
                + attempt + "'")).isEqualTo("relatorio.txt:" + report.length + ",dados/relatório final.csv:5,vazio.txt:0");
        // o estado da tentativa nao mudou por causa dos resultados
        assertThat(stateOf(attempt)).isEqualTo("7|-");
        // fechamento: a contagem tem de ser a que o servidor recebeu; repetir o mesmo nao muda; outro, conflito
        assertThat(complete(m, attempt, grant, 2, 0).body().get("reason").asString()).isEqualTo("remote_result_conflict");
        MachineClient.Response closed = complete(m, attempt, grant, 3, 4);
        assertThat(closed.status()).as(closed.raw()).isEqualTo(200);
        assertThat(closed.body().get("skippedByLimit").asInt()).isEqualTo(4);
        assertThat(complete(m, attempt, grant, 3, 4).status()).isEqualTo(200);
        assertThat(complete(m, attempt, grant, 3, 5).body().get("reason").asString()).isEqualTo("remote_result_conflict");
        assertThat(fx.value("SELECT sent || '|' || skipped_by_limit FROM remote_attempt_result_closures WHERE attempt_id = '" + attempt + "'")).isEqualTo("3|4");
        // depois do fechamento, nada mais entra
        assertThat(upload(m, attempt, token, epoch, 4, "tarde.txt", other, sha256(other)).body().get("reason").asString()).isEqualTo("remote_result_conflict");

        // limite total de 50 MiB por tentativa: guardado tambem no banco (metadados de 20 MiB cada; o terceiro nao entra)
        Machine big = machine(true);
        String attempt2 = queued(big);
        String grant2 = received(big, attempt2);
        for (String st : new String[] {"PREPARING", "STARTING", "RUNNING"}) {
            assertThat(state(big, attempt2, grant2, st, null).status()).isEqualTo(200);
        }
        String row = "INSERT INTO remote_attempt_results (attempt_id, result_number, organization_id, file_name, size_bytes, sha256) VALUES ('" + attempt2
                + "', %d, '" + fx.org + "', 'grande-%d.bin', 20971520, '" + "a".repeat(64) + "')";
        assertThat(asRole("nekkora_ingestion", row.formatted(1, 1), row.formatted(2, 2))).isEqualTo("OK");
        assertThat(asRole("nekkora_ingestion", row.formatted(3, 3))).isEqualTo("23514");
        byte[] small = new byte[11 * 1024 * 1024];
        String[] g = grant2.replace("\"", "").split("[:,]");
        MachineClient.Response over = upload(big, attempt2, g[1], Integer.valueOf(g[3]), 3, "passa-do-total.bin", small, sha256(small));
        assertThat(over.status()).as(over.raw()).isEqualTo(409);
        assertThat(over.body().get("reason").asString()).isEqualTo("remote_result_limit");
        // guardas do banco: a maquina nao altera nem apaga; as pessoas nao escrevem; resultado so de tentativa que iniciou
        assertThat(asRole("nekkora_ingestion", "UPDATE remote_attempt_results SET file_name = 'x' WHERE attempt_id = '" + attempt + "'")).isEqualTo("42501");
        assertThat(asRole("nekkora_ingestion", "DELETE FROM remote_attempt_results WHERE attempt_id = '" + attempt + "'")).isEqualTo("42501");
        assertThat(asRole("nekkora_platform", row.formatted(4, 4))).isEqualTo("42501");
        Machine idle = machine(true);
        String never = queued(idle);
        assertThat(asRole("nekkora_ingestion", "INSERT INTO remote_attempt_results (attempt_id, result_number, organization_id, file_name, size_bytes, sha256) "
                + "VALUES ('" + never + "', 1, '" + fx.org + "', 'x.txt', 1, '" + "a".repeat(64) + "')")).isEqualTo("23514");
    }

    private static String line(int sequence, String channel, String message) {
        return "{\"sequence\":" + sequence + ",\"capturedAt\":\"2026-11-15T12:00:0" + (sequence % 10) + "Z\",\"channel\":\"" + channel + "\",\"message\":"
                + tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(message) + "}";
    }

    private MachineClient.Response logs(Machine m, String attempt, String grant, String... lines) {
        return m.client().post("/remote/attempts/" + attempt + "/logs", "{" + grant + ",\"lines\":[" + String.join(",", lines) + "]}", m.credential());
    }

    private MachineClient.Response logsComplete(Machine m, String attempt, String grant, int lines, long dropped) {
        return m.client().post("/remote/attempts/" + attempt + "/logs/complete", "{" + grant + ",\"lines\":" + lines + ",\"droppedLines\":" + dropped + "}",
                m.credential());
    }

    private MachineClient.Response loss(Machine m, String attempt, String grant, String kind, Integer localCount) {
        return m.client().post("/remote/attempts/" + attempt + "/retention-loss", "{" + grant + (kind == null ? "" : ",\"kind\":\"" + kind + "\"")
                + (localCount == null ? "" : ",\"localCount\":" + localCount) + "}", m.credential());
    }

    @Test
    void aMaquinaAvisaQueRemoveuPeloPrazoDeGuardaUmaVezPorTipoSoDeTentativaTerminadaESemMudarNada() {
        Machine m = machine(true);
        String attempt = queued(m);
        JsonNode offer = next(m).body().get("attempt");
        String grant = grant(offer);
        assertThat(ack(m, attempt, grant).status()).isEqualTo(200);
        // antes de iniciar e com a execucao em andamento nao ha o que remover por prazo: o aviso e recusado
        assertThat(loss(m, attempt, grant, "LOGS", 10).body().get("reason").asString()).isEqualTo("remote_attempt_transition");
        for (String st : new String[] {"PREPARING", "STARTING", "RUNNING"}) {
            assertThat(state(m, attempt, grant, st, null).status()).isEqualTo(200);
        }
        assertThat(loss(m, attempt, grant, "LOGS", 10).body().get("reason").asString()).isEqualTo("remote_attempt_transition");
        assertThat(logs(m, attempt, grant, line(1, "STDOUT", "uma linha que chegou")).status()).isEqualTo(200);
        assertThat(state(m, attempt, grant, "FAILED", "\"exitCode\":2").status()).isEqualTo(200);
        // pedido malformado: nada e gravado
        assertThat(loss(m, attempt, grant, "OUTRO", 1).status()).isEqualTo(400);
        assertThat(loss(m, attempt, grant, null, 1).status()).isEqualTo(400);
        assertThat(loss(m, attempt, grant, "LOGS", null).status()).isEqualTo(400);
        assertThat(loss(m, attempt, grant, "LOGS", -1).status()).isEqualTo(400);
        assertThat(loss(m, attempt, grant, "LOGS", 1_000_001).status()).isEqualTo(400);
        // token que nao e o da atribuicao, e outra maquina: nada
        assertThat(loss(m, attempt, "\"token\":\"" + Tokens.newToken() + "\",\"epoch\":" + offer.get("epoch").asInt(), "LOGS", 5).body().get("reason").asString())
                .isEqualTo("remote_attempt_stale");
        Machine other = machine(true);
        assertThat(loss(other, attempt, grant, "LOGS", 5).status()).isEqualTo(404);
        assertThat(fx.value("SELECT count(*) FROM remote_attempt_retention_losses WHERE attempt_id = '" + attempt + "'")).isEqualTo("0");
        // o aviso: a fila de registros tinha 300 linhas na maquina (a Platform recebeu 1) e foi removida sem fechamento
        MachineClient.Response logsLost = loss(m, attempt, grant, "LOGS", 300);
        assertThat(logsLost.status()).as(logsLost.raw()).isEqualTo(200);
        assertThat(logsLost.body().get("kind").asString()).isEqualTo("LOGS");
        assertThat(logsLost.body().get("localCount").asInt()).isEqualTo(300);
        String at = logsLost.body().get("reportedAt").asString();
        // repetir (resposta perdida), mesmo com outro numero: devolve o que ja estava gravado
        MachineClient.Response again = loss(m, attempt, grant, "LOGS", 999);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("localCount").asInt()).isEqualTo(300);
        assertThat(again.body().get("reportedAt").asString()).isEqualTo(at);
        // o outro tipo tem o aviso dele
        assertThat(loss(m, attempt, grant, "RESULTS", 0).body().get("localCount").asInt()).isZero();
        assertThat(fx.value("SELECT string_agg(kind || ':' || local_count, ',' ORDER BY kind) FROM remote_attempt_retention_losses WHERE attempt_id = '" + attempt
                + "'")).isEqualTo("1:300,2:0");
        // nada mais mudou: o estado, a linha que chegou, e o envio continua sem fechamento
        assertThat(stateOf(attempt)).isEqualTo("8|-");
        assertThat(fx.value("SELECT count(*) FROM remote_attempt_log_lines WHERE attempt_id = '" + attempt + "'")).isEqualTo("1");
        assertThat(fx.value("SELECT count(*) FROM remote_attempt_log_closures WHERE attempt_id = '" + attempt + "'")).isEqualTo("0");
        // e o fechamento, se a maquina ainda conseguir, continua aceito depois do aviso
        assertThat(logsComplete(m, attempt, grant, 1, 0).status()).isEqualTo(200);
    }

    @Test
    void aMaquinaEnviaASaidaDoProcessoEmLotesEFechaOEnvioSemMudarOResultado() {
        Machine m = machine(true);
        String attempt = queued(m);
        JsonNode offer = next(m).body().get("attempt");
        String token = offer.get("token").asString();
        String grant = grant(offer);
        assertThat(ack(m, attempt, grant).status()).isEqualTo(200);
        // tentativa que ainda nao iniciou: nenhuma linha e aceita
        assertThat(state(m, attempt, grant, "PREPARING", null).status()).isEqualTo(200);
        assertThat(state(m, attempt, grant, "STARTING", null).status()).isEqualTo(200);
        assertThat(logs(m, attempt, grant, line(1, "STDOUT", "cedo demais")).body().get("reason").asString()).isEqualTo("remote_attempt_transition");
        assertThat(state(m, attempt, grant, "RUNNING", null).status()).isEqualTo(200);
        // o que nao e aceito, sem gravar nada: lote vazio, canal desconhecido, quebra de linha no texto, linha de mais de 8 KiB,
        // sequencia que pula dentro do lote, sem token
        assertThat(logs(m, attempt, grant).status()).isEqualTo(400);
        assertThat(logs(m, attempt, grant, line(1, "ERROR", "x")).status()).isEqualTo(400);
        assertThat(logs(m, attempt, grant, line(1, "STDOUT", "duas\nlinhas")).status()).isEqualTo(400);
        assertThat(logs(m, attempt, grant, line(1, "STDOUT", "x".repeat(8193))).status()).isEqualTo(400);
        assertThat(logs(m, attempt, grant, line(1, "STDOUT", "a"), line(3, "STDOUT", "c")).status()).isEqualTo(400);
        assertThat(logs(m, attempt, "\"epoch\":1", line(1, "STDOUT", "a")).status()).isEqualTo(400);
        assertThat(fx.value("SELECT count(*) FROM remote_attempt_log_lines WHERE attempt_id = '" + attempt + "'")).isEqualTo("0");
        // o lote comeca na linha seguinte a ultima guardada: buraco e conflito
        assertThat(logs(m, attempt, grant, line(2, "STDOUT", "pulou a primeira")).body().get("reason").asString()).isEqualTo("remote_log_conflict");
        // aceito, com a tentativa em andamento: os dois canais, na ordem da captura; STDERR e canal, nao nivel
        MachineClient.Response first = logs(m, attempt, grant, line(1, "STDOUT", "iniciando"), line(2, "STDERR", "Traceback (most recent call last):"),
                line(3, "STDERR", "\tValueError: acentuação e tabulação"));
        assertThat(first.status()).as(first.raw()).isEqualTo(200);
        assertThat(first.body().get("stored").asInt()).isEqualTo(3);
        assertThat(first.body().get("lastSequence").asInt()).isEqualTo(3);
        assertThat(first.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(fx.value("SELECT string_agg(sequence || ':' || channel || ':' || level || ':' || message, '|' ORDER BY sequence) FROM remote_attempt_log_lines "
                + "WHERE attempt_id = '" + attempt + "'")).isEqualTo("1:1:0:iniciando|2:2:0:Traceback (most recent call last):|3:2:0:\tValueError: acentuação e tabulação");
        // lote repetido (resposta perdida), inteiro ou sobreposto: nada duplica; o que e novo entra
        assertThat(logs(m, attempt, grant, line(1, "STDOUT", "iniciando"), line(2, "STDERR", "Traceback (most recent call last):")).body().get("stored").asInt())
                .isZero();
        MachineClient.Response overlap = logs(m, attempt, grant, line(3, "STDERR", "\tValueError: acentuação e tabulação"), line(4, "EXECUTOR", "aviso do aplicativo"));
        assertThat(overlap.body().get("stored").asInt()).isEqualTo(1);
        // a mesma sequencia com outro conteudo: conflito, nada muda
        assertThat(logs(m, attempt, grant, line(4, "EXECUTOR", "outro texto")).body().get("reason").asString()).isEqualTo("remote_log_conflict");
        // segredos nunca sao gravados: credencial de maquina, codigo de pareamento, cabecalho de autorizacao, o token da atribuicao
        MachineClient.Response secret = logs(m, attempt, grant, line(5, "STDOUT", "cred nka1." + "A".repeat(20) + ".x fim"),
                line(6, "STDOUT", "codigo nkp1.org.enr." + "B".repeat(43)), line(7, "STDERR", "Authorization: Bearer abcdefghijklmnop resto"),
                line(8, "STDOUT", "token " + token + " ok"), line(9, "STDOUT", "curl -H 'authorization=Basic dXNlcjpzZW5oYQ=='"));
        assertThat(secret.status()).as(secret.raw()).isEqualTo(200);
        String stored = fx.value("SELECT string_agg(message, '|' ORDER BY sequence) FROM remote_attempt_log_lines WHERE attempt_id = '" + attempt + "' AND sequence >= 5");
        assertThat(stored).isEqualTo("cred [removido] fim|codigo [removido]|Authorization: [removido] resto|token [removido] ok|curl -H 'authorization=[removido]");
        assertThat(stored).doesNotContain("nka1.").doesNotContain("nkp1.").doesNotContain(token).doesNotContain("abcdefghijklmnop").doesNotContain("dXNlcjpzZW5oYQ");
        // repetir o lote com os segredos e reconhecido como o mesmo (a comparacao e com o texto ja limpo)
        assertThat(logs(m, attempt, grant, line(8, "STDOUT", "token " + token + " ok")).body().get("stored").asInt()).isZero();
        // token que nao e o vigente; outra maquina
        assertThat(logs(m, attempt, "\"token\":\"" + Tokens.newToken() + "\",\"epoch\":" + offer.get("epoch").asInt(), line(10, "STDOUT", "x")).body()
                .get("reason").asString()).isEqualTo("remote_attempt_stale");
        Machine stranger = machine(true);
        assertThat(logs(stranger, attempt, grant, line(10, "STDOUT", "x")).status()).isEqualTo(404);
        // fechar antes do termino: nao
        assertThat(logsComplete(m, attempt, grant, 9, 0).body().get("reason").asString()).isEqualTo("remote_attempt_transition");
        assertThat(state(m, attempt, grant, "FAILED", "\"exitCode\":3").status()).isEqualTo(200);
        // depois do termino o token continua valendo para o que faltava enviar
        assertThat(logs(m, attempt, grant, "{\"sequence\":10,\"capturedAt\":\"2026-11-15T12:00:09Z\",\"channel\":\"STDOUT\",\"truncated\":true,\"message\":\"cortada\"}")
                .status()).isEqualTo(200);
        assertThat(fx.value("SELECT truncated FROM remote_attempt_log_lines WHERE attempt_id = '" + attempt + "' AND sequence = 10")).isEqualTo("t");
        // o resultado do robo nao mudou por causa dos registros
        assertThat(stateOf(attempt)).isEqualTo("8|-");
        // fechamento: a contagem tem de ser a que o servidor recebeu; as descartadas por limite sao ditas; repetir nao muda; outro, conflito
        assertThat(logsComplete(m, attempt, grant, 9, 0).body().get("reason").asString()).isEqualTo("remote_log_conflict");
        MachineClient.Response closed = logsComplete(m, attempt, grant, 10, 1234);
        assertThat(closed.status()).as(closed.raw()).isEqualTo(200);
        assertThat(closed.body().get("droppedLines").asLong()).isEqualTo(1234);
        assertThat(logsComplete(m, attempt, grant, 10, 1234).status()).isEqualTo(200);
        assertThat(logsComplete(m, attempt, grant, 10, 0).body().get("reason").asString()).isEqualTo("remote_log_conflict");
        assertThat(fx.value("SELECT lines || '|' || dropped_lines FROM remote_attempt_log_closures WHERE attempt_id = '" + attempt + "'")).isEqualTo("10|1234");
        assertThat(logs(m, attempt, grant, line(11, "STDOUT", "tarde")).body().get("reason").asString()).isEqualTo("remote_log_conflict");
        assertThat(stateOf(attempt)).isEqualTo("8|-");

        // limite por tentativa (20 MiB): o lote que passaria nao entra, com o motivo; guardado tambem no banco
        Machine big = machine(true);
        String attempt2 = queued(big);
        String grant2 = received(big, attempt2);
        for (String st : new String[] {"PREPARING", "STARTING", "RUNNING"}) {
            assertThat(state(big, attempt2, grant2, st, null).status()).isEqualTo(200);
        }
        String rows = "INSERT INTO remote_attempt_log_lines (attempt_id, sequence, organization_id, captured_at, channel, message) SELECT '" + attempt2
                + "', g, '" + fx.org + "', clock_timestamp(), 1, repeat('x', 8192) FROM generate_series(%d, %d) g";
        assertThat(asRole("nekkora_ingestion", rows.formatted(1, 2559))).isEqualTo("OK");
        MachineClient.Response over = logs(big, attempt2, grant2, line(2560, "STDOUT", "y".repeat(8000)), line(2561, "STDOUT", "y".repeat(8000)));
        assertThat(over.status()).as(over.raw()).isEqualTo(409);
        assertThat(over.body().get("reason").asString()).isEqualTo("remote_log_limit");
        assertThat(fx.value("SELECT count(*) FROM remote_attempt_log_lines WHERE attempt_id = '" + attempt2 + "'")).isEqualTo("2559");
        assertThat(asRole("nekkora_ingestion", rows.formatted(2560, 2561))).as("o banco tambem guarda o total").isEqualTo("23514");
        // guardas do banco: buraco na sequencia; a maquina nao altera nem apaga; as pessoas nao escrevem; so de tentativa que iniciou
        String one = "INSERT INTO remote_attempt_log_lines (attempt_id, sequence, organization_id, captured_at, channel, message) VALUES ('%s', %d, '" + fx.org
                + "', clock_timestamp(), 1, 'x')";
        assertThat(asRole("nekkora_ingestion", one.formatted(attempt2, 2565))).isEqualTo("23514");
        assertThat(asRole("nekkora_ingestion", "UPDATE remote_attempt_log_lines SET message = 'x' WHERE attempt_id = '" + attempt + "'")).isEqualTo("42501");
        assertThat(asRole("nekkora_ingestion", "DELETE FROM remote_attempt_log_lines WHERE attempt_id = '" + attempt + "'")).isEqualTo("42501");
        assertThat(asRole("nekkora_platform", one.formatted(attempt2, 2560))).isEqualTo("42501");
        Machine idle = machine(true);
        String never = queued(idle);
        assertThat(asRole("nekkora_ingestion", one.formatted(never, 1))).isEqualTo("23514");
    }

    @Test
    void aMaquinaRecusaQuandoNaoHaSessaoUtilizavelEOMotivoFicaGravado() {
        // 049: tela bloqueada ou sem sessao interativa - o aplicativo nao inicia, e diz por que
        Machine m = machine(true);
        String attempt = queued(m);
        String grant = received(m, attempt);
        assertThat(state(m, attempt, grant, "PREPARING", null).status()).isEqualTo(200);
        assertThat(state(m, attempt, grant, "STARTING", null).status()).isEqualTo(200);
        MachineClient.Response r = state(m, attempt, grant, "REFUSED", "\"refusalReason\":\"SESSION_UNAVAILABLE\"");
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        assertThat(r.body().get("proceed").asBoolean()).isFalse();
        assertThat(stateOf(attempt)).isEqualTo("11|SESSION_UNAVAILABLE");
        assertThat(fx.value("SELECT started_at IS NULL FROM remote_attempts WHERE attempt_id = '" + attempt + "'")).isEqualTo("t");
    }

    @Test
    void aMaquinaBaixaSoOPacoteDaTentativaQueRecebeuEAntesDeIniciar() throws Exception {
        byte[] zip = ("PK pacote sintetico " + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String sha = publishPackage(zip);
        Machine m = machine(true);
        String attempt = queued(m);
        JsonNode offer = next(m).body().get("attempt");
        String token = offer.get("token").asString();
        int epoch = offer.get("epoch").asInt();
        // sem o token da atribuicao, ou antes de confirmar o recebimento: nada
        assertThat(download(m, attempt, null, null).status()).isEqualTo(400);
        MachineClient.Response early = download(m, attempt, token, epoch);
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.body().get("reason").asString()).isEqualTo("remote_attempt_transition");
        assertThat(ack(m, attempt, grant(offer)).status()).isEqualTo(200);
        // recebida: os bytes da versao fixada, com o SHA-256, sem cache
        MachineClient.Response ok = download(m, attempt, token, epoch);
        assertThat(ok.status()).as(ok.raw()).isEqualTo(200);
        assertThat(ok.raw()).isEqualTo(new String(zip, java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(ok.headers().firstValue("Nekkora-Package-Sha256").orElse("")).isEqualTo(sha);
        assertThat(ok.headers().firstValue("Content-Type").orElse("")).startsWith("application/zip");
        assertThat(ok.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        // token ou epoca que nao sao os vigentes: 409, e nada muda
        MachineClient.Response stale = download(m, attempt, Tokens.newToken(), epoch);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("reason").asString()).isEqualTo("remote_attempt_stale");
        assertThat(download(m, attempt, token, epoch + 1).body().get("reason").asString()).isEqualTo("remote_attempt_stale");
        // outra maquina, mesmo com o token: a tentativa nao existe para ela
        Machine other = machine(true);
        assertThat(download(other, attempt, token, epoch).status()).isEqualTo(404);
        // preparando ainda pode; a partir do inicio, nao
        assertThat(state(m, attempt, grant(offer), "PREPARING", null).status()).isEqualTo(200);
        assertThat(download(m, attempt, token, epoch).status()).isEqualTo(200);
        assertThat(state(m, attempt, grant(offer), "STARTING", null).status()).isEqualTo(200);
        assertThat(download(m, attempt, token, epoch).body().get("reason").asString()).isEqualTo("remote_attempt_transition");

        // bytes trocados no armazenamento, ou ausentes: 409 com o motivo proprio; nunca bytes que nao conferem
        String attempt2 = queued(other);
        JsonNode offer2 = next(other).body().get("attempt");
        assertThat(ack(other, attempt2, grant(offer2)).status()).isEqualTo(200);
        java.nio.file.Path file = PACKAGES.resolve(fx.org.toString()).resolve(sha + ".zip");
        java.nio.file.Files.write(file, "outro conteudo".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        MachineClient.Response corrupted = download(other, attempt2, offer2.get("token").asString(), offer2.get("epoch").asInt());
        assertThat(corrupted.status()).isEqualTo(409);
        assertThat(corrupted.body().get("reason").asString()).isEqualTo("remote_package_unavailable");
        assertThat(corrupted.raw()).doesNotContain("outro conteudo");
        java.nio.file.Files.delete(file);
        assertThat(download(other, attempt2, offer2.get("token").asString(), offer2.get("epoch").asInt()).body().get("reason").asString())
                .isEqualTo("remote_package_unavailable");

        // 048 (restrita): o canal da maquina NAO le a chave do armazenamento em automation_artifacts, nem por coluna
        assertThat(asRole("nekkora_ingestion", "SELECT sha256, size_bytes FROM automation_artifacts")).isEqualTo("42501");
        assertThat(asRole("nekkora_ingestion", "SELECT storage_key FROM automation_artifacts")).isEqualTo("42501");
        assertThat(asRole("nekkora_ingestion", "SELECT received_by_membership_id FROM automation_artifacts")).isEqualTo("42501");
        assertThat(asRole("nekkora_ingestion", "UPDATE automation_artifacts SET size_bytes = 1")).isEqualTo("42501");
    }

    /** Quantas linhas de pacote o canal da maquina enxerga com este contexto de transacao (organizacao e maquina; nulo = nao definido). */
    private static String visible(String org, String agent, String attempt) {
        try (Connection c = DB.connectAs("nekkora_ingestion")) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                if (org != null) {
                    st.execute("SELECT set_config('nekkora.organization_id', '" + org + "', true)");
                }
                if (agent != null) {
                    st.execute("SELECT set_config('nekkora.agent_id', '" + agent + "', true)");
                }
                try (java.sql.ResultSet rs = st.executeQuery("SELECT count(*) || '/' || coalesce(max(sha256), '-') FROM remote_attempt_packages"
                        + (attempt == null ? "" : " WHERE attempt_id = '" + attempt + "'"))) {
                    rs.next();
                    return rs.getString(1);
                }
            } finally {
                c.rollback();
            }
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    @Test
    void oBancoSoMostraOPacoteAMaquinaDaTentativaNaOrganizacaoDelaEDuranteAPreparacao() throws Exception {
        byte[] zip = ("PK pacote isolado " + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String sha = publishPackage(zip);
        String org = fx.org.toString();
        Machine a = machine(true);
        Machine b = machine(true);
        String attemptA = queued(a);
        String attemptB = queued(b);
        // a linha nasceu com a tentativa, com a maquina e o pacote lidos pelo banco
        assertThat(fx.value("SELECT agent_id || '|' || sha256 || '|' || size_bytes FROM remote_attempt_packages WHERE attempt_id = '" + attemptA + "'"))
                .isEqualTo(a.agent() + "|" + sha + "|" + zip.length);
        // na fila e oferecida: ainda nao e preparacao - nem a propria maquina ve
        assertThat(visible(org, a.agent(), attemptA)).isEqualTo("0/-");
        JsonNode offerA = next(a).body().get("attempt");
        assertThat(visible(org, a.agent(), attemptA)).isEqualTo("0/-");
        assertThat(ack(a, attemptA, grant(offerA)).status()).isEqualTo(200);
        String grantB = received(b, attemptB);
        // recebida: SO a maquina da tentativa, na organizacao dela
        assertThat(visible(org, a.agent(), attemptA)).isEqualTo("1/" + sha);
        assertThat(visible(org, a.agent(), null)).as("a maquina A so ve a propria linha").isEqualTo("1/" + sha);
        assertThat(visible(org, b.agent(), attemptA)).as("outra maquina da mesma organizacao").isEqualTo("0/-");
        assertThat(visible(org, null, attemptA)).as("transacao sem maquina autenticada").isEqualTo("0/-");
        assertThat(visible(org, "", attemptA)).as("maquina vazia").isEqualTo("0/-");
        assertThat(visible(org, UUID.randomUUID().toString(), attemptA)).as("maquina que nao existe").isEqualTo("0/-");
        assertThat(visible(UUID.randomUUID().toString(), a.agent(), attemptA)).as("outra organizacao, mesmo com a maquina certa").isEqualTo("0/-");
        assertThat(visible(null, a.agent(), attemptA)).as("sem organizacao").isEqualTo("0/-");
        // pelo canal real: a credencial de B nao alcanca o pacote da tentativa de A, mesmo com o token e a epoca de A
        assertThat(download(b, attemptA, offerA.get("token").asString(), offerA.get("epoch").asInt()).status()).isEqualTo(404);
        assertThat(download(a, attemptA, offerA.get("token").asString(), offerA.get("epoch").asInt()).status()).isEqualTo(200);
        // preparando: ainda ve; iniciando, executando e terminada: nao ve mais
        assertThat(state(a, attemptA, grant(offerA), "PREPARING", null).status()).isEqualTo(200);
        assertThat(visible(org, a.agent(), attemptA)).isEqualTo("1/" + sha);
        assertThat(state(a, attemptA, grant(offerA), "STARTING", null).status()).isEqualTo(200);
        assertThat(visible(org, a.agent(), attemptA)).as("iniciando").isEqualTo("0/-");
        assertThat(state(a, attemptA, grant(offerA), "RUNNING", null).status()).isEqualTo(200);
        assertThat(visible(org, a.agent(), attemptA)).as("executando").isEqualTo("0/-");
        assertThat(state(a, attemptA, grant(offerA), "SUCCEEDED", "\"exitCode\":0").status()).isEqualTo(200);
        assertThat(visible(org, a.agent(), attemptA)).as("terminada").isEqualTo("0/-");
        // tentativa encerrada por recusa, ainda na preparacao: tambem deixa de ser visivel
        assertThat(visible(org, b.agent(), attemptB)).isEqualTo("1/" + sha);
        assertThat(state(b, attemptB, grantB, "REFUSED", "\"refusalReason\":\"PACKAGE_INVALID\"").status()).isEqualTo(200);
        assertThat(visible(org, b.agent(), attemptB)).as("recusada").isEqualTo("0/-");
        // o canal da maquina so le: nao insere, nao muda, nao apaga
        assertThat(asRole("nekkora_ingestion", "INSERT INTO remote_attempt_packages (attempt_id, organization_id) VALUES ('" + attemptA + "', '" + org + "')"))
                .isEqualTo("42501");
        assertThat(asRole("nekkora_ingestion", "UPDATE remote_attempt_packages SET size_bytes = 1")).isEqualTo("42501");
        assertThat(asRole("nekkora_ingestion", "DELETE FROM remote_attempt_packages")).isEqualTo("42501");
    }

    private String stateOf(String attempt) {
        return fx.value("SELECT state || '|' || coalesce(refusal_reason, '-') FROM remote_attempts WHERE attempt_id = '" + attempt + "'");
    }

    /** Fixture sem os gatilhos (so para adiantar prazos, que a aplicacao nao tem como mudar). */
    private static void untriggered(String sql) {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("fixture falhou (sqlstate " + e.getSQLState() + ")", e);
        }
    }

    private static String asRole(String role, String... sqls) {
        try (Connection c = DB.connectAs(role)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT set_config('nekkora.organization_id', '" + fx.org + "', true)");
                for (String sql : sqls) {
                    st.execute(sql);
                }
                c.commit();
                return "OK";
            } catch (SQLException e) {
                c.rollback();
                return e.getSQLState();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aMaquinaPuxaConfirmaEConduzOEstadoComOTokenEAEpocaVigentes() {
        Machine m = machine(true);
        assertThat(next(m).raw()).as("nada para esta maquina").isEqualTo("{}");
        String attempt = queued(m);

        MachineClient.Response first = next(m);
        assertThat(first.status()).as(first.raw()).isEqualTo(200);
        JsonNode offer = first.body().get("attempt");
        assertThat(offer.get("attemptId").asString()).isEqualTo(attempt);
        assertThat(offer.get("automationVersionId").asString()).isEqualTo(fx.version.toString());
        assertThat(offer.get("packageFingerprint").asString()).matches("[0-9a-f]{64}");
        assertThat(offer.get("dependencies").toString()).isEqualTo("[\"idna==3.7\"]");
        // 047: o ambiente Python escolhido para a automacao NESTA maquina - o identificador que ela publicou, nunca um caminho
        assertThat(offer.get("pythonEnvironmentId").asString()).isEqualTo(ENVIRONMENT);
        // o ambiente da solicitacao: o aplicativo o grava no registro da execucao dele
        assertThat(offer.get("workspaceId").asString()).isEqualTo(fx.workspace.toString());
        assertThat(offer.get("epoch").asInt()).isEqualTo(1);
        assertThat(offer.get("leaseSeconds").asInt()).isEqualTo(90);
        String token = offer.get("token").asString();
        // o servidor guarda so o hash do token
        assertThat(fx.value("SELECT token_hash FROM remote_attempts WHERE attempt_id = '" + attempt + "'")).isEqualTo(Tokens.sha256Hex(token)).isNotEqualTo(token);
        assertThat(stateOf(attempt)).isEqualTo("2|-");

        // resposta perdida: perguntar de novo entrega a MESMA tentativa, com token e epoca novos; os anteriores nao valem mais
        JsonNode again = next(m).body().get("attempt");
        assertThat(again.get("attemptId").asString()).isEqualTo(attempt);
        assertThat(again.get("epoch").asInt()).isEqualTo(2);
        assertThat(again.get("token").asString()).isNotEqualTo(token);
        MachineClient.Response stale = ack(m, attempt, grant(offer));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("reason").asString()).isEqualTo("remote_attempt_stale");
        assertThat(stateOf(attempt)).as("o consumidor obsoleto nao alterou nada").isEqualTo("2|-");
        // token certo com a epoca antiga, e vice-versa: tambem obsoleto
        assertThat(ack(m, attempt, "\"token\":\"" + again.get("token").asString() + "\",\"epoch\":1").status()).isEqualTo(409);

        String grant = grant(again);
        MachineClient.Response ok = ack(m, attempt, grant);
        assertThat(ok.status()).as(ok.raw()).isEqualTo(200);
        assertThat(ok.body().get("state").asString()).isEqualTo("RECEIVED");
        assertThat(ok.body().get("proceed").asBoolean()).isTrue();
        assertThat(ack(m, attempt, grant).status()).as("confirmar de novo e idempotente").isEqualTo(200);
        assertThat(next(m).raw()).as("ja recebida: nada novo a entregar").isEqualTo("{}");

        for (String s : new String[] {"PREPARING", "PREPARING", "STARTING", "RUNNING", "RUNNING"}) {
            MachineClient.Response r = state(m, attempt, grant, s, null);
            assertThat(r.status()).as(s + " " + r.raw()).isEqualTo(200);
            assertThat(r.body().get("state").asString()).isEqualTo(s);
            assertThat(r.body().get("proceed").asBoolean()).isTrue();
            assertThat(r.body().get("cancelRequested").asBoolean()).isFalse();
        }
        MachineClient.Response done = state(m, attempt, grant, "SUCCEEDED", "\"exitCode\":0");
        assertThat(done.body().get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.body().get("proceed").asBoolean()).isFalse();
        assertThat(fx.value("SELECT state || '|' || exit_code || '|' || (offered_at IS NOT NULL) || (received_at IS NOT NULL) || (started_at IS NOT NULL) "
                + "|| (ended_at IS NOT NULL) || (lease_expires_at IS NULL) FROM remote_attempts WHERE attempt_id = '" + attempt + "'")).isEqualTo("7|0|truetruetruetruetrue");
        // terminada: nada mais muda, e a maquina fica livre para a proxima
        assertThat(state(m, attempt, grant, "FAILED", "\"exitCode\":1").body().get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(next(m).raw()).isEqualTo("{}");
        String second = queued(m);
        assertThat(next(m).body().get("attempt").get("attemptId").asString()).isEqualTo(second);
    }

    @Test
    void antesDeIniciarOServidorRevalidaENaoIniciaSeAAutorizacaoCaiu() {
        // revogada durante a preparacao
        Machine a = machine(true);
        String t1 = queued(a);
        String g1 = received(a, t1);
        assertThat(state(a, t1, g1, "PREPARING", null).status()).isEqualTo(200);
        DB.asSuperuser("UPDATE local_run_authorizations SET revoked_at = clock_timestamp(), revoked_by_membership_id = '" + fx.adminMembership
                + "' WHERE authorization_id = '" + a.authorization() + "'");
        MachineClient.Response r = state(a, t1, g1, "STARTING", null);
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        assertThat(r.body().get("state").asString()).isEqualTo("REFUSED");
        assertThat(r.body().get("refusalReason").asString()).isEqualTo("AUTHORIZATION_REVOKED");
        assertThat(r.body().get("proceed").asBoolean()).as("a maquina NAO inicia").isFalse();
        assertThat(stateOf(t1)).isEqualTo("11|AUTHORIZATION_REVOKED");
        assertThat(fx.value("SELECT started_at IS NULL FROM remote_attempts WHERE attempt_id = '" + t1 + "'")).isEqualTo("t");
        // a maquina que insistir nao reabre a tentativa
        assertThat(state(a, t1, g1, "RUNNING", null).body().get("state").asString()).isEqualTo("REFUSED");

        // vencida durante a espera (prazo adiantado por fixture)
        Machine b = machine(true);
        String t2 = queued(b);
        String g2 = received(b, t2);
        untriggered("UPDATE local_run_authorizations SET granted_at = clock_timestamp() - interval '2 days', valid_until = clock_timestamp() - interval '1 minute' "
                + "WHERE authorization_id = '" + b.authorization() + "'");
        MachineClient.Response e = state(b, t2, g2, "PREPARING", null);
        assertThat(e.body().get("state").asString()).as("preparar ainda nao e iniciar").isEqualTo("PREPARING");
        e = state(b, t2, g2, "STARTING", null);
        assertThat(e.body().get("refusalReason").asString()).isEqualTo("AUTHORIZATION_EXPIRED");
        assertThat(e.body().get("proceed").asBoolean()).isFalse();

        // Worker retirado do ambiente
        Machine c = machine(true);
        String t3 = queued(c);
        String g3 = received(c, t3);
        state(c, t3, g3, "PREPARING", null);
        DB.asSuperuser("UPDATE worker_workspace_assignments SET status = 2, revoked_at = clock_timestamp(), revoked_by_membership_id = '" + fx.adminMembership
                + "' WHERE assignment_id = '" + c.workerWorkspace() + "'");
        assertThat(state(c, t3, g3, "STARTING", null).body().get("refusalReason").asString()).isEqualTo("WORKER_NOT_AUTHORIZED");

        // revogada antes mesmo de entregar: nada e entregue, e o motivo fica gravado
        Machine d = machine(true);
        String t4 = queued(d);
        DB.asSuperuser("UPDATE local_run_authorizations SET revoked_at = clock_timestamp(), revoked_by_membership_id = '" + fx.adminMembership
                + "' WHERE authorization_id = '" + d.authorization() + "'");
        assertThat(next(d).raw()).isEqualTo("{}");
        assertThat(stateOf(t4)).isEqualTo("11|AUTHORIZATION_REVOKED");
        // com tudo valendo, STARTING passa e a execucao segue
        Machine ok = machine(true);
        String t5 = queued(ok);
        String g5 = received(ok, t5);
        state(ok, t5, g5, "PREPARING", null);
        assertThat(state(ok, t5, g5, "STARTING", null).body().get("proceed").asBoolean()).isTrue();
    }

    @Test
    void cancelamentoPausaEPrazoDaOferta() {
        // cancelamento pedido antes de a maquina receber: cancelada na consulta, nada entregue
        Machine a = machine(true);
        String t1 = queued(a);
        String cancel = "UPDATE remote_executions SET cancel_requested_by_membership_id = '" + fx.adminMembership + "' WHERE remote_execution_id = "
                + "(SELECT remote_execution_id FROM remote_attempts WHERE attempt_id = '%s')";
        DB.asSuperuser(cancel.formatted(t1));
        assertThat(next(a).raw()).isEqualTo("{}");
        assertThat(stateOf(t1)).isEqualTo("9|-");

        // pedido com a tentativa ja em execucao: o estado NAO muda sozinho; a maquina e avisada e e ela quem diz CANCELLED
        String t2 = queued(a);
        String g2 = received(a, t2);
        for (String s : new String[] {"PREPARING", "STARTING", "RUNNING"}) {
            state(a, t2, g2, s, null);
        }
        DB.asSuperuser(cancel.formatted(t2));
        assertThat(stateOf(t2)).as("pedir o cancelamento nao encerra a tentativa").isEqualTo("6|-");
        MachineClient.Response notice = next(a);
        assertThat(notice.body().get("cancel").get("attemptId").asString()).isEqualTo(t2);
        assertThat(notice.body().has("attempt")).isFalse();
        MachineClient.Response beat = state(a, t2, g2, "RUNNING", null);
        assertThat(beat.body().get("cancelRequested").asBoolean()).isTrue();
        assertThat(beat.body().get("state").asString()).isEqualTo("RUNNING");
        assertThat(state(a, t2, g2, "CANCELLED", null).body().get("state").asString()).isEqualTo("CANCELLED");

        // pedido entre o recebimento e o inicio: o servidor cancela na revalidacao, e a maquina nao inicia
        String t3 = queued(a);
        String g3 = received(a, t3);
        state(a, t3, g3, "PREPARING", null);
        DB.asSuperuser(cancel.formatted(t3));
        MachineClient.Response blocked = state(a, t3, g3, "STARTING", null);
        assertThat(blocked.body().get("state").asString()).isEqualTo("CANCELLED");
        assertThat(blocked.body().get("proceed").asBoolean()).isFalse();

        // maquina pausada: nada e entregue; retomou, recebe
        Machine p = machine(true);
        String t4 = queued(p);
        assertThat(p.client().post("/remote/receiving", "{\"receiving\":\"PAUSED\"}", p.credential()).status()).isEqualTo(200);
        assertThat(next(p).raw()).isEqualTo("{}");
        assertThat(stateOf(t4)).as("a tentativa espera").isEqualTo("1|-");
        assertThat(p.client().post("/remote/receiving", "{\"receiving\":\"ENABLED\"}", p.credential()).status()).isEqualTo(200);
        assertThat(next(p).body().get("attempt").get("attemptId").asString()).isEqualTo(t4);

        // prazo da oferta vencido sem a maquina receber: EXPIRED, nada entregue, vaga liberada
        Machine e = machine(true);
        String t5 = queued(e);
        untriggered("UPDATE remote_attempts SET created_at = clock_timestamp() - interval '1 hour', offer_valid_until = clock_timestamp() - interval '1 minute' "
                + "WHERE attempt_id = '" + t5 + "'");
        assertThat(next(e).raw()).isEqualTo("{}");
        assertThat(stateOf(t5)).isEqualTo("12|-");
        assertThat(next(e).body().has("attempt")).isFalse();
        String t6 = queued(e);
        assertThat(next(e).body().get("attempt").get("attemptId").asString()).isEqualTo(t6);
    }

    @Test
    void soAMaquinaDaTentativaEscreveETransicoesEMotivosSaoFechados() {
        Machine m = machine(true);
        Machine other = machine(true);
        String attempt = queued(m);
        String grant = received(m, attempt);
        // outra maquina, mesmo com o token certo: a tentativa nao existe para ela
        assertThat(ack(other, attempt, grant).status()).isEqualTo(404);
        assertThat(state(other, attempt, grant, "PREPARING", null).status()).isEqualTo(404);
        assertThat(next(other).raw()).isEqualTo("{}");
        // sem credencial; tentativa inexistente; corpo malformado
        assertThat(m.client().post("/remote/attempts/" + attempt + "/state", "{" + grant + ",\"state\":\"PREPARING\"}", null).status()).isEqualTo(401);
        assertThat(state(m, UUID.randomUUID().toString(), grant, "PREPARING", null).status()).isEqualTo(404);
        assertThat(m.client().post("/remote/attempts/nao-e-uuid/ack", "{" + grant + "}", m.credential()).status()).isEqualTo(400);
        assertThat(m.client().post("/remote/attempts/" + attempt + "/ack", "{\"token\":\"curto\",\"epoch\":1}", m.credential()).status()).isEqualTo(400);
        assertThat(m.client().post("/remote/attempts/" + attempt + "/ack", "{}", m.credential()).status()).isEqualTo(400);
        // pular etapas: RECEIVED nao vai direto a RUNNING nem a SUCCEEDED
        for (String skip : new String[] {"RUNNING", "SUCCEEDED", "STARTING"}) {
            MachineClient.Response r = state(m, attempt, grant, skip, skip.equals("SUCCEEDED") ? "\"exitCode\":0" : null);
            assertThat(r.status()).as(skip).isEqualTo(409);
            assertThat(r.body().get("reason").asString()).isEqualTo("remote_attempt_transition");
        }
        // estados e motivos fora da lista da maquina; codigo de saida fora de lugar
        String[] invalid = {"\"state\":\"QUEUED\"", "\"state\":\"EXPIRED\"", "\"state\":\"REFUSED\"", "\"state\":\"REFUSED\",\"refusalReason\":\"AUTHORIZATION_REVOKED\"",
                "\"state\":\"PREPARING\",\"refusalReason\":\"BUSY\"", "\"state\":\"RUNNING\",\"exitCode\":0", "\"state\":\"PREPARING\",\"exitCode\":\"zero\""};
        for (String body : invalid) {
            assertThat(m.client().post("/remote/attempts/" + attempt + "/state", "{" + grant + "," + body + "}", m.credential()).status()).as(body).isEqualTo(400);
        }
        assertThat(stateOf(attempt)).as("nenhuma recusa mexeu na tentativa").isEqualTo("3|-");
        // recusa da maquina, com motivo da lista dela
        MachineClient.Response refused = state(m, attempt, grant, "REFUSED", "\"refusalReason\":\"DEPENDENCIES_MISSING\"");
        assertThat(refused.body().get("state").asString()).isEqualTo("REFUSED");
        assertThat(stateOf(attempt)).isEqualTo("11|DEPENDENCIES_MISSING");

        // maquina vinculada mas nao habilitada (o Agent anterior): a rota nao e para ela
        MachineClient plain = new MachineClient(port, "10.66.1.1");
        plain.pair(fx.pairingCode(fx.worker("W-comum-" + UUID.randomUUID().toString().substring(0, 6)), "Estacao comum", "15 minutes"));
        MachineClient.Response no = plain.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/agent/api/v1/remote/next")).GET(), plain.credential);
        assertThat(no.status()).isEqualTo(409);
        assertThat(no.body().get("reason").asString()).isEqualTo("remote_execution_not_enabled");
    }

    @Test
    void oBancoGuardaAReservaAsExigenciasEAsTransicoes() {
        Machine m = machine(true);
        String attempt = queued(m);
        // a reserva: uma tentativa nao terminada por maquina, e uma por solicitacao
        String execution = fx.value("SELECT remote_execution_id FROM remote_attempts WHERE attempt_id = '" + attempt + "'");
        try {
            attempt(execution(), 1, m);
            throw new AssertionError("a maquina ja tem uma tentativa nao terminada");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("23505");
        }
        Machine free = machine(true);
        try {
            attempt(execution, 2, free);
            throw new AssertionError("a solicitacao ja tem uma tentativa nao terminada");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("23505");
        }
        // exigencias na criacao: maquina incompativel, pausada, autorizacao de OUTRO Worker, autorizacao revogada
        Machine incompatible = machine(false);
        Machine paused = machine(true);
        paused.client().post("/remote/receiving", "{\"receiving\":\"PAUSED\"}", paused.credential());
        Machine revoked = machine(true);
        DB.asSuperuser("UPDATE local_run_authorizations SET revoked_at = clock_timestamp(), revoked_by_membership_id = '" + fx.adminMembership
                + "' WHERE authorization_id = '" + revoked.authorization() + "'");
        Machine borrowed = new Machine(free.client(), free.agent(), free.worker(), free.workerWorkspace(), m.authorization(), free.credential());
        for (Machine bad : new Machine[] {incompatible, paused, revoked, borrowed}) {
            try {
                queued(bad);
                throw new AssertionError("tentativa criada sem as exigencias");
            } catch (IllegalStateException e) {
                assertThat(e.getMessage()).contains("23514");
            }
        }
        // a maquina (nekkora_ingestion) nao cria solicitacao nem tentativa, nao troca a identidade e nao apaga
        assertThat(asRole("nekkora_ingestion", "INSERT INTO remote_attempts (organization_id, remote_execution_id, attempt_number, agent_id, worker_id, authorization_id, "
                + "created_by_membership_id, offer_valid_until) SELECT organization_id, remote_execution_id, 2, agent_id, worker_id, authorization_id, "
                + "created_by_membership_id, clock_timestamp() + interval '5 minutes' FROM remote_attempts WHERE attempt_id = '" + attempt + "'")).isEqualTo("42501");
        assertThat(asRole("nekkora_ingestion", "UPDATE remote_attempts SET authorization_id = '" + free.authorization() + "' WHERE attempt_id = '" + attempt + "'"))
                .isEqualTo("42501");
        assertThat(asRole("nekkora_ingestion", "DELETE FROM remote_attempts WHERE attempt_id = '" + attempt + "'")).isEqualTo("42501");
        assertThat(asRole("nekkora_ingestion", "UPDATE remote_executions SET cancel_requested_by_membership_id = '" + fx.adminMembership + "'")).isEqualTo("42501");
        // transicoes: da fila nao se vai a RUNNING; terminada nao muda; a epoca so cresce
        assertThat(asRole("nekkora_ingestion", "UPDATE remote_attempts SET state = 6 WHERE attempt_id = '" + attempt + "'")).isEqualTo("23514");
        String grant = received(m, attempt);
        assertThat(asRole("nekkora_ingestion", "UPDATE remote_attempts SET epoch = 0 WHERE attempt_id = '" + attempt + "'")).isEqualTo("23514");
        state(m, attempt, grant, "REFUSED", "\"refusalReason\":\"BUSY\"");
        assertThat(asRole("nekkora_ingestion", "UPDATE remote_attempts SET state = 4, refusal_reason = NULL WHERE attempt_id = '" + attempt + "'")).isEqualTo("23514");
        // as pessoas (nekkora_platform) so encerram o que a maquina ainda nao recebeu
        String queued = queued(m);
        String g = received(m, queued);
        assertThat(asRole("nekkora_platform", "UPDATE remote_attempts SET state = 9 WHERE attempt_id = '" + queued + "'")).isEqualTo("42501");
        assertThat(g).isNotBlank();
    }

    // ------------------------------------------------------------------ 053: encerramento sem confirmacao

    private String cause(String attempt) {
        return fx.value("SELECT state || '|' || coalesce(refusal_reason, '-') || '|' || coalesce(closure_cause, '-') || '|' || coalesce(exit_code::text, '-') "
                + "|| '|' || (ended_at IS NOT NULL) FROM remote_attempts WHERE attempt_id = '" + attempt + "'");
    }

    private String running(Machine m, String attempt) {
        String grant = received(m, attempt);
        for (String st : new String[] {"PREPARING", "STARTING", "RUNNING"}) {
            assertThat(state(m, attempt, grant, st, null).status()).isEqualTo(200);
        }
        return grant;
    }

    private static MachineClient.Response unlink(Machine m) {
        return m.client().unlinkWith(m.credential(), UUID.randomUUID().toString(), m.agent(), java.util.List.of());
    }

    @Test
    void desvinculoPelaMaquinaEncerraATentativaDelaSemInventarDesfecho() {
        // tres maquinas, tres momentos: na fila, em preparacao (o servidor nunca autorizou o inicio) e em execucao
        Machine queued = machine(true);
        String inQueue = queued(queued);
        Machine preparing = machine(true);
        String inPreparation = queued(preparing);
        String gp = received(preparing, inPreparation);
        assertThat(state(preparing, inPreparation, gp, "PREPARING", null).status()).isEqualTo(200);
        Machine busy = machine(true);
        String inExecution = queued(busy);
        String gr = running(busy, inExecution);
        assertThat(logs(busy, inExecution, gr, line(1, "STDOUT", "linha antes do desvinculo")).status()).isEqualTo(200);
        // uma quarta, de outra maquina, nao e tocada
        Machine other = machine(true);
        String untouched = queued(other);
        String go = running(other, untouched);

        for (Machine m : new Machine[] {queued, preparing, busy}) {
            MachineClient.Response r = unlink(m);
            assertThat(r.status()).as(r.raw()).isEqualTo(200);
        }
        // o que o servidor nunca autorizou a iniciar: "nao iniciou", com o motivo. O que podia estar em execucao: sem confirmacao.
        // Nunca sucesso, falha nem cancelamento; sem codigo de saida; a tentativa tem fim
        assertThat(cause(inQueue)).isEqualTo("11|MACHINE_NOT_LINKED|MACHINE_UNLINKED|-|true");
        assertThat(cause(inPreparation)).isEqualTo("11|MACHINE_NOT_LINKED|MACHINE_UNLINKED|-|true");
        assertThat(cause(inExecution)).isEqualTo("13|-|MACHINE_UNLINKED|-|true");
        assertThat(cause(untouched)).isEqualTo("6|-|-|-|false");
        // a maquina desvinculada nao escreve mais nada: nem estado, nem registros (a revogacao nao e contornada)
        assertThat(state(busy, inExecution, gr, "SUCCEEDED", "\"exitCode\":0").status()).isEqualTo(401);
        assertThat(logs(busy, inExecution, gr, line(2, "STDOUT", "depois do desvinculo")).status()).isEqualTo(401);
        assertThat(cause(inExecution)).isEqualTo("13|-|MACHINE_UNLINKED|-|true");
        // o que ja tinha chegado continua la
        assertThat(fx.value("SELECT count(*) FROM remote_attempt_log_lines WHERE attempt_id = '" + inExecution + "'")).isEqualTo("1");
        // a outra maquina segue e termina normalmente
        assertThat(state(other, untouched, go, "SUCCEEDED", "\"exitCode\":0").status()).isEqualTo(200);
        assertThat(cause(untouched)).isEqualTo("7|-|-|0|true");
    }

    /** Recua o prazo da comunicacao (fixture): a maquina esta sem falar ha {@code minutes} alem do vencimento. */
    private static void silentFor(String attempt, int minutes) {
        untriggered("UPDATE remote_attempts SET lease_expires_at = clock_timestamp() - interval '" + minutes + " minutes' WHERE attempt_id = '" + attempt + "'");
    }

    @Test
    void semComunicacaoAlemDaEsperaATentativaFicaSemConfirmacaoEORelatoTardioFicaAoLado() throws Exception {
        Machine m = machine(true);
        String attempt = queued(m);
        JsonNode offer = next(m).body().get("attempt");
        String grant = grant(offer);
        assertThat(ack(m, attempt, grant).status()).isEqualTo(200);
        for (String st : new String[] {"PREPARING", "STARTING", "RUNNING"}) {
            assertThat(state(m, attempt, grant, st, null).status()).isEqualTo(200);
        }
        assertThat(logs(m, attempt, grant, line(1, "STDOUT", "primeira linha")).status()).isEqualTo(200);
        // prazo vencido ha pouco (menos que a espera de 120 min): comunicacao perdida NAO encerra; a maquina volta e segue
        silentFor(attempt, 30);
        MachineClient.Response back = state(m, attempt, grant, "RUNNING", null);
        assertThat(back.body().get("state").asString()).isEqualTo("RUNNING");
        assertThat(back.body().get("proceed").asBoolean()).isTrue();
        assertThat(cause(attempt)).isEqualTo("6|-|-|-|false");
        // vencido ha mais que a espera: quando a maquina volta, a tentativa ja esta encerrada - sem confirmacao, sem desfecho inventado
        silentFor(attempt, 121);
        MachineClient.Response late = state(m, attempt, grant, "RUNNING", null);
        assertThat(late.status()).as(late.raw()).isEqualTo(200);
        assertThat(late.body().get("state").asString()).isEqualTo("ENDED_UNKNOWN");
        assertThat(late.body().get("proceed").asBoolean()).isFalse();
        assertThat(late.body().get("closureCause").asString()).isEqualTo("COMMUNICATION_LOST");
        assertThat(cause(attempt)).isEqualTo("13|-|COMMUNICATION_LOST|-|true");
        assertThat(fx.value("SELECT late_state IS NULL FROM remote_attempts WHERE attempt_id = '" + attempt + "'")).isEqualTo("t");
        // a maquina diz como o processo terminou: fica AO LADO; o estado da tentativa nao muda
        MachineClient.Response told = state(m, attempt, grant, "SUCCEEDED", "\"exitCode\":0");
        assertThat(told.status()).isEqualTo(200);
        assertThat(told.body().get("state").asString()).isEqualTo("ENDED_UNKNOWN");
        assertThat(cause(attempt)).isEqualTo("13|-|COMMUNICATION_LOST|-|true");
        assertThat(fx.value("SELECT late_state || '|' || late_exit_code || '|' || (late_reported_at IS NOT NULL) FROM remote_attempts WHERE attempt_id = '"
                + attempt + "'")).isEqualTo("7|0|true");
        // uma vez so: outro relato nao troca o primeiro
        assertThat(state(m, attempt, grant, "FAILED", "\"exitCode\":3").status()).isEqualTo(200);
        assertThat(fx.value("SELECT late_state || '|' || late_exit_code FROM remote_attempts WHERE attempt_id = '" + attempt + "'")).isEqualTo("7|0");
        // encerrar o estado NAO encerra a entrega: registros e resultados continuam aceitos, e os fechamentos tambem
        assertThat(logs(m, attempt, grant, line(2, "STDOUT", "linha enviada depois do encerramento")).status()).isEqualTo(200);
        assertThat(logsComplete(m, attempt, grant, 2, 0).status()).isEqualTo(200);
        byte[] file = "resultado entregue depois".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(upload(m, attempt, offer.get("token").asString(), offer.get("epoch").asInt(), 1, "saida.txt", file, sha256(file)).status()).isEqualTo(200);
        assertThat(complete(m, attempt, grant, 1, 0).status()).isEqualTo(200);

        // em preparacao (o servidor nunca autorizou o inicio): "nao iniciou", com o motivo proprio; a maquina que volta nao inicia
        Machine p = machine(true);
        String preparing = queued(p);
        String gp = received(p, preparing);
        assertThat(state(p, preparing, gp, "PREPARING", null).status()).isEqualTo(200);
        silentFor(preparing, 500);
        MachineClient.Response start = state(p, preparing, gp, "STARTING", null);
        assertThat(start.status()).isEqualTo(200);
        assertThat(start.body().get("state").asString()).isEqualTo("REFUSED");
        assertThat(start.body().get("proceed").asBoolean()).isFalse();
        assertThat(start.body().get("refusalReason").asString()).isEqualTo("COMMUNICATION_LOST");
        assertThat(cause(preparing)).isEqualTo("11|COMMUNICATION_LOST|COMMUNICATION_LOST|-|true");
        // quem nao iniciou nao tem relato tardio de desfecho
        assertThat(state(p, preparing, gp, "ENDED_UNKNOWN", null).status()).isEqualTo(200);
        assertThat(fx.value("SELECT late_state IS NULL FROM remote_attempts WHERE attempt_id = '" + preparing + "'")).isEqualTo("t");
        // a consulta seguinte da maquina tambem aplica a regra (o diario dela pode ter se perdido)
        Machine q = machine(true);
        String forgotten = queued(q);
        running(q, forgotten);
        silentFor(forgotten, 200);
        assertThat(next(q).status()).isEqualTo(200);
        assertThat(cause(forgotten)).isEqualTo("13|-|COMMUNICATION_LOST|-|true");
    }

    @Test
    void oBancoSoAceitaOEncerramentoPeloServidorComOFatoConferido() {
        Machine m = machine(true);
        String attempt = queued(m);
        running(m, attempt);
        String lost = "UPDATE remote_attempts SET state = 13, closure_cause = 'COMMUNICATION_LOST' WHERE attempt_id = '" + attempt + "'";
        String revoked = "UPDATE remote_attempts SET state = 13, closure_cause = 'MACHINE_REVOKED' WHERE attempt_id = '" + attempt + "'";
        for (String role : new String[] {"nekkora_platform", "nekkora_ingestion"}) {
            // comunicacao em dia: nao esta perdida. Maquina vinculada: nao foi revogada nem desvinculada
            assertThat(asRole(role, lost)).as(role).isEqualTo("23514");
            assertThat(asRole(role, revoked)).as(role).isEqualTo("23514");
            assertThat(asRole(role, revoked.replace("MACHINE_REVOKED", "MACHINE_UNLINKED"))).as(role).isEqualTo("23514");
            // o servidor nunca grava sucesso, falha ou cancelamento como encerramento; nem causa sem encerrar; nem causa inventada
            for (int state : new int[] {7, 8, 9, 10}) {
                assertThat(asRole(role, "UPDATE remote_attempts SET state = " + state + ", closure_cause = 'COMMUNICATION_LOST' WHERE attempt_id = '" + attempt
                        + "'")).as(role + " " + state).isEqualTo("23514");
            }
            assertThat(asRole(role, "UPDATE remote_attempts SET closure_cause = 'COMMUNICATION_LOST' WHERE attempt_id = '" + attempt + "'")).isEqualTo("23514");
            assertThat(asRole(role, "UPDATE remote_attempts SET state = 13, closure_cause = 'OUTRA' WHERE attempt_id = '" + attempt + "'")).isEqualTo("23514");
            // em execucao nao vira "nao iniciou"
            assertThat(asRole(role, "UPDATE remote_attempts SET state = 11, refusal_reason = 'COMMUNICATION_LOST', closure_cause = 'COMMUNICATION_LOST' "
                    + "WHERE attempt_id = '" + attempt + "'")).isEqualTo("23514");
        }
        // as pessoas continuam sem mudar o estado de uma tentativa recebida por outro caminho, e nao gravam relato tardio
        assertThat(asRole("nekkora_platform", "UPDATE remote_attempts SET state = 13 WHERE attempt_id = '" + attempt + "'")).isEqualTo("42501");
        assertThat(asRole("nekkora_platform", "UPDATE remote_attempts SET late_state = 7 WHERE attempt_id = '" + attempt + "'")).isEqualTo("42501");
        // relato tardio so depois de um encerramento por comunicacao perdida
        assertThat(asRole("nekkora_ingestion", "UPDATE remote_attempts SET late_state = 7 WHERE attempt_id = '" + attempt + "'")).isEqualTo("23514");
        // com o prazo vencido, o banco aceita - das pessoas tambem (e o caminho da leitura na Platform)
        silentFor(attempt, 1);
        assertThat(asRole("nekkora_platform", lost)).isEqualTo("OK");
        assertThat(cause(attempt)).isEqualTo("13|-|COMMUNICATION_LOST|-|true");
        // terminada: nada mais muda, a nao ser o relato tardio, uma vez, e so ele
        assertThat(asRole("nekkora_ingestion", "UPDATE remote_attempts SET state = 7 WHERE attempt_id = '" + attempt + "'")).isEqualTo("23514");
        assertThat(asRole("nekkora_ingestion", "UPDATE remote_attempts SET late_state = 7, exit_code = 0 WHERE attempt_id = '" + attempt + "'")).isEqualTo("23514");
        assertThat(asRole("nekkora_ingestion", "UPDATE remote_attempts SET late_state = 9, late_exit_code = 1 WHERE attempt_id = '" + attempt + "'")).isEqualTo("23514");
        assertThat(asRole("nekkora_ingestion", "UPDATE remote_attempts SET late_state = 8, late_exit_code = 2 WHERE attempt_id = '" + attempt + "'")).isEqualTo("OK");
        assertThat(asRole("nekkora_ingestion", "UPDATE remote_attempts SET late_state = 7, late_exit_code = 0 WHERE attempt_id = '" + attempt + "'")).isEqualTo("23514");
        // um encerramento por desvinculo nao aceita relato tardio (a maquina nao tem mais como falar)
        Machine u = machine(true);
        String gone = queued(u);
        running(u, gone);
        assertThat(unlink(u).status()).isEqualTo(200);
        assertThat(asRole("nekkora_ingestion", "UPDATE remote_attempts SET late_state = 7 WHERE attempt_id = '" + gone + "'")).isEqualTo("23514");
    }

    @Test
    void pausarNovosTrabalhosNaoMexeNaExecucaoEmAndamentoQueSegueAcompanhadaAteTerminar() {
        Machine m = machine(true);
        String attempt = queued(m);
        String grant = running(m, attempt);
        assertThat(m.client().post("/remote/receiving", "{\"receiving\":\"PAUSED\"}", m.credential()).status()).isEqualTo(200);
        // a tentativa em andamento nao muda, a maquina segue (proceed), a comunicacao e renovada e os registros chegam
        assertThat(cause(attempt)).isEqualTo("6|-|-|-|false");
        MachineClient.Response still = state(m, attempt, grant, "RUNNING", null);
        assertThat(still.body().get("proceed").asBoolean()).isTrue();
        assertThat(still.body().get("state").asString()).isEqualTo("RUNNING");
        assertThat(fx.value("SELECT lease_expires_at > clock_timestamp() FROM remote_attempts WHERE attempt_id = '" + attempt + "'")).isEqualTo("t");
        assertThat(logs(m, attempt, grant, line(1, "STDOUT", "linha com a maquina pausada")).status()).isEqualTo(200);
        // pausada, ela nao recebe trabalho NOVO: nada e entregue alem do que ja tinha
        assertThat(next(m).body().has("attempt")).isFalse();
        // o desfecho verdadeiro, informado por ela, e gravado normalmente, com o codigo de saida
        assertThat(state(m, attempt, grant, "SUCCEEDED", "\"exitCode\":0").status()).isEqualTo(200);
        assertThat(cause(attempt)).isEqualTo("7|-|-|0|true");
        assertThat(logsComplete(m, attempt, grant, 1, 0).status()).isEqualTo(200);
        // e continua pausada: uma tentativa nova nao nasce para ela
        try {
            queued(m);
            throw new AssertionError("maquina pausada nao recebe tentativa nova");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("23514");
        }
    }
}
