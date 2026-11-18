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

        // 048: a maquina le o sha256 e o tamanho do artefato da PROPRIA organizacao; nada alem disso
        assertThat(asRole("nekkora_ingestion", "SELECT sha256, size_bytes FROM automation_artifacts")).isEqualTo("OK");
        assertThat(asRole("nekkora_ingestion", "SELECT storage_key FROM automation_artifacts")).isEqualTo("42501");
        assertThat(asRole("nekkora_ingestion", "SELECT received_by_membership_id FROM automation_artifacts")).isEqualTo("42501");
        assertThat(asRole("nekkora_ingestion", "UPDATE automation_artifacts SET size_bytes = 1")).isEqualTo("42501");
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
}
