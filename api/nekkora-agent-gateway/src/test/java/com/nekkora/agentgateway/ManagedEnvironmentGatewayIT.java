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

import com.nekkora.core.remote.ManagedLock;
import com.nekkora.core.token.Tokens;

import tools.jackson.databind.JsonNode;

/**
 * AMBIENTE-PYTHON-001 (076) - o canal da MAQUINA para o ambiente por execucao, pelo gateway REAL (HTTP, papel nekkora_ingestion,
 * migrations ate a 076, execucao remota e ambiente por execucao LIGADOS). O pedido de resolucao, o aceite e a solicitacao
 * MANAGED sao criados por fixture (superusuario, com os gatilhos da 076 valendo); o lado das pessoas e conferido no modulo da
 * Platform (ManagedEnvironmentIT).
 */
class ManagedEnvironmentGatewayIT extends RemoteEnabledGatewayIT {

    static AgentFixture fx;
    static final String ENVIRONMENT = "M".repeat(43);
    static final String CAPABLE = "{\"agentVersion\":\"0.2.0+client\",\"contractVersion\":\"1.0\",\"capabilities\":[\"REMOTE_RUN_1_0\",\"PY_INVENTORY_1_0\",\"MANAGED_ENV_1_0\"]}";
    static final String OLD = "{\"agentVersion\":\"0.1.0+client\",\"contractVersion\":\"1.0\",\"capabilities\":[\"REMOTE_RUN_1_0\",\"PY_INVENTORY_1_0\"]}";
    static final String SHA_A = "a1".repeat(32);
    static final String SHA_B = "b2".repeat(32);

    @BeforeAll
    static void fixture() throws Exception {
        fx = new AgentFixture(DB);
        DB.asSuperuser("INSERT INTO automation_runtime_requirements (automation_id, revision_number, organization_id, workspace_id, source, dependencies, "
                + "content_sha256, declared_by_membership_id) VALUES ('" + fx.automation + "', 1, '" + fx.org + "', '" + fx.workspace + "', 3, "
                + "'[\"nekkora-fixture-a==1.2.3\"]'::jsonb, '" + "c".repeat(64) + "', '" + fx.adminMembership + "')");
        byte[] zip = ("PK pacote gerenciado " + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(zip));
        new com.nekkora.core.artifact.LocalArtifactStore(PACKAGES).put(fx.org, sha, zip);
        untriggered("INSERT INTO automation_artifacts (organization_id, workspace_id, automation_id, automation_version_id, sha256, size_bytes, package_kind, "
                + "fingerprint_rule, fingerprint, files_count, manifest_version, framework, execution_mode, storage_key, received_by_membership_id) SELECT '"
                + fx.org + "', '" + fx.workspace + "', '" + fx.automation + "', '" + fx.version + "', '" + sha + "', " + zip.length + ", 1, "
                + "command_json -> 'agent_package' ->> 'fingerprint_rule', command_json -> 'agent_package' ->> 'fingerprint', 3, '1.4.0', 'nekkora-rpa==1.0.0', "
                + "'single', '" + sha + "', '" + fx.adminMembership + "' FROM automation_versions WHERE automation_version_id = '" + fx.version + "'");
    }

    private record Machine(MachineClient client, String agent, UUID worker, UUID authorization, String credential) {
    }

    private Machine machine(String heartbeat) {
        UUID worker = fx.worker("W-amb-" + UUID.randomUUID().toString().substring(0, 6));
        UUID ww = fx.authorizeWorker(worker);
        UUID authorization = fx.localRun(worker, ww);
        MachineClient m = new MachineClient(port, "10.76." + (int) (Math.random() * 250) + "." + (1 + (int) (Math.random() * 250)));
        String secret = Tokens.newToken();
        MachineClient.Response r = m.redeemWithIntent(fx.remotePairingCode(worker, "Estacao gerenciada"), secret, "pair-remote");
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        String credential = MachineToken.credential(fx.org, UUID.fromString(r.body().get("credentialId").asString()), secret);
        assertThat(m.post("/heartbeat", heartbeat, credential).status()).isEqualTo(200);
        String agent = r.body().get("agentId").asString();
        DB.asSuperuser("INSERT INTO agent_python_environments (agent_id, environment_id, organization_id, python_version, kind, observed_at) VALUES ('" + agent
                + "', '" + ENVIRONMENT + "', '" + fx.org + "', '3.12.10', 1, clock_timestamp())");
        DB.asSuperuser("INSERT INTO automation_python_selections (automation_id, agent_id, organization_id, workspace_id, environment_id, python_version, "
                + "selected_by_membership_id) VALUES ('" + fx.automation + "', '" + agent + "', '" + fx.org + "', '" + fx.workspace + "', '" + ENVIRONMENT
                + "', '3.0.0', '" + fx.adminMembership + "')");
        return new Machine(m, agent, worker, authorization, credential);
    }

    /** Um pedido de resolucao em aberto para a maquina (como a Platform gravaria). */
    private String resolution(Machine m) {
        return fx.value("INSERT INTO managed_environment_resolutions (organization_id, workspace_id, automation_id, automation_version_id, agent_id, environment_id, "
                + "requirements_revision, dependencies, requested_by_membership_id, answer_valid_until) VALUES ('" + fx.org + "', '" + fx.workspace + "', '"
                + fx.automation + "', '" + fx.version + "', '" + m.agent() + "', '" + ENVIRONMENT + "', 1, '[\"nekkora-fixture-a==1.2.3\"]'::jsonb, '"
                + fx.adminMembership + "', clock_timestamp() + interval '10 minutes') RETURNING resolution_id");
    }

    private MachineClient.Response next(Machine m) {
        return m.client().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/agent/api/v1/remote/next")).GET(), m.credential());
    }

    private MachineClient.Response answer(Machine m, String resolution, String body) {
        return m.client().post("/remote/environment-resolutions/" + resolution + "/answer", body, m.credential());
    }

    static final String LOCK = "[{\"name\":\"nekkora_fixture_a\",\"version\":\"1.2.3\",\"sha256\":\"" + SHA_A + "\",\"fileName\":\"nekkora_fixture_a-1.2.3-py3-none-any.whl\"},"
            + "{\"name\":\"nekkora-fixture-b\",\"version\":\"0.1.0\",\"sha256\":\"" + SHA_B + "\",\"fileName\":\"nekkora_fixture_b-0.1.0-py3-none-any.whl\"}]";
    static final String RESOLVED = "{\"outcome\":\"RESOLVED\",\"pythonVersion\":\"3.12.10\",\"pipVersion\":\"25.0.1\",\"lock\":" + LOCK + "}";

    /** Aceite vigente do lock respondido e uma solicitacao MANAGED com a primeira tentativa (fixture). Devolve a tentativa. */
    private String managedAttempt(Machine m, String resolution) {
        String sha = fx.value("SELECT lock_sha256 FROM managed_environment_resolutions WHERE resolution_id = '" + resolution + "'");
        String auth = fx.value("INSERT INTO managed_environment_authorizations (organization_id, workspace_id, automation_id, automation_version_id, agent_id, "
                + "worker_id, environment_id, requirements_revision, resolution_id, lock_sha256, checks, granted_by_membership_id) VALUES ('" + fx.org + "', '"
                + fx.workspace + "', '" + fx.automation + "', '" + fx.version + "', '" + m.agent() + "', '" + m.worker() + "', '" + ENVIRONMENT + "', 1, '"
                + resolution + "', '" + sha + "', '[]'::jsonb, '" + fx.adminMembership + "') RETURNING authorization_id");
        String execution = fx.value("INSERT INTO remote_executions (organization_id, workspace_id, automation_id, automation_version_id, package_fingerprint, "
                + "package_fingerprint_rule, requirements_revision, effective_dependencies, request_key, requested_by_membership_id, environment_mode) SELECT '"
                + fx.org + "', '" + fx.workspace + "', '" + fx.automation + "', '" + fx.version + "', command_json -> 'agent_package' ->> 'fingerprint', "
                + "command_json -> 'agent_package' ->> 'fingerprint_rule', 1, '[\"nekkora-fixture-a==1.2.3\"]'::jsonb, gen_random_uuid(), '" + fx.adminMembership
                + "', 2 FROM automation_versions WHERE automation_version_id = '" + fx.version + "' RETURNING remote_execution_id");
        String attempt = fx.value("INSERT INTO remote_attempts (organization_id, remote_execution_id, attempt_number, agent_id, worker_id, authorization_id, "
                + "created_by_membership_id, offer_valid_until) VALUES ('" + fx.org + "', '" + execution + "', 1, '" + m.agent() + "', '" + m.worker() + "', '"
                + m.authorization() + "', '" + fx.adminMembership + "', clock_timestamp() + interval '15 minutes') RETURNING attempt_id");
        DB.asSuperuser("INSERT INTO remote_attempt_environments (attempt_id, organization_id, authorization_id, lock, lock_sha256, checks) SELECT '" + attempt
                + "', organization_id, '" + auth + "', lock, lock_sha256, '[\"PLAYWRIGHT_CHROMIUM\"]'::jsonb FROM managed_environment_resolutions WHERE resolution_id = '"
                + resolution + "'");
        return attempt;
    }

    private static String grant(JsonNode offer) {
        return "\"token\":\"" + offer.get("token").asString() + "\",\"epoch\":" + offer.get("epoch").asInt();
    }

    private MachineClient.Response post(Machine m, String path, String body) {
        return m.client().post(path, body, m.credential());
    }

    private MachineClient.Response report(Machine m, String attempt, String body) {
        return m.client().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/agent/api/v1/remote/attempts/" + attempt + "/environment-report"))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body)), m.credential());
    }

    @Test
    void aPresencaSoAnunciaOAmbienteGerenciadoAMaquinaHabilitadaEAceitaACapacidadeNova() {
        Machine old = machine(OLD);
        MachineClient.Response p = old.client().post("/heartbeat", OLD, old.credential());
        assertThat(p.status()).isEqualTo(200);
        assertThat(p.body().get("managedEnvironments").asBoolean()).isTrue();
        // o aplicativo novo declara a capacidade depois de ver o anuncio; o gateway aceita e a presenca guarda
        assertThat(old.client().post("/heartbeat", CAPABLE, old.credential()).status()).isEqualTo(200);
        assertThat(fx.value("SELECT capabilities @> ARRAY['MANAGED_ENV_1_0']::text[] FROM agent_presence WHERE agent_id = '" + old.agent() + "'")).isEqualTo("t");
    }

    @Test
    void aMaquinaRecebeAResolucaoERespondeUmaVezComOLockCompleto() {
        Machine m = machine(CAPABLE);
        String resolution = resolution(m);
        JsonNode n = next(m).body().get("environmentResolution");
        assertThat(n.get("resolutionId").asString()).isEqualTo(resolution);
        assertThat(n.get("pythonEnvironmentId").asString()).isEqualTo(ENVIRONMENT);
        assertThat(n.get("index").asString()).isEqualTo("PYPI");
        assertThat(n.get("dependencies").get(0).asString()).isEqualTo("nekkora-fixture-a==1.2.3");
        assertThat(fx.value("SELECT delivered_at IS NOT NULL FROM managed_environment_resolutions WHERE resolution_id = '" + resolution + "'")).isEqualTo("t");

        // recusado sem gravar: lock sem a dependencia pedida, versao diferente, hash malformado, arquivo que nao e wheel, caminho,
        // nome repetido (PEP 503), campo nao combinado, RESOLVED com falha, INCOMPATIBLE sem falha
        String noA = "[{\"name\":\"nekkora-fixture-b\",\"version\":\"0.1.0\",\"sha256\":\"" + SHA_B + "\",\"fileName\":\"b-0.1.0-py3-none-any.whl\"}]";
        String otherVersion = LOCK.replace("\"1.2.3\"", "\"1.2.4\"");
        for (String bad : new String[] {"{\"outcome\":\"RESOLVED\",\"lock\":" + noA + "}", "{\"outcome\":\"RESOLVED\",\"lock\":" + otherVersion + "}",
                "{\"outcome\":\"RESOLVED\",\"lock\":" + LOCK.replace(SHA_A, "XYZ") + "}",
                "{\"outcome\":\"RESOLVED\",\"lock\":" + LOCK.replace("nekkora_fixture_a-1.2.3-py3-none-any.whl", "nekkora_fixture_a-1.2.3.tar.gz") + "}",
                "{\"outcome\":\"RESOLVED\",\"lock\":" + LOCK.replace("nekkora_fixture_a-1.2.3-py3-none-any.whl", "..\\\\x.whl") + "}",
                "{\"outcome\":\"RESOLVED\",\"lock\":" + LOCK.replace("nekkora-fixture-b", "Nekkora.Fixture_A") + "}",
                "{\"outcome\":\"RESOLVED\",\"lock\":" + LOCK + ",\"indexUrl\":\"https://x.test/simple\"}",
                "{\"outcome\":\"RESOLVED\",\"lock\":" + LOCK + ",\"failure\":{\"phase\":\"OTHER\"}}", "{\"outcome\":\"INCOMPATIBLE\"}"}) {
            assertThat(answer(m, resolution, bad).status()).as(bad).isEqualTo(400);
        }
        assertThat(fx.value("SELECT outcome IS NULL FROM managed_environment_resolutions WHERE resolution_id = '" + resolution + "'")).isEqualTo("t");

        MachineClient.Response ok = answer(m, resolution, RESOLVED);
        assertThat(ok.status()).as(ok.raw()).isEqualTo(200);
        String expected = ManagedLock.sha256(java.util.List.of(
                new ManagedLock.Item("nekkora_fixture_a", "1.2.3", SHA_A, "nekkora_fixture_a-1.2.3-py3-none-any.whl"),
                new ManagedLock.Item("nekkora-fixture-b", "0.1.0", SHA_B, "nekkora_fixture_b-0.1.0-py3-none-any.whl")));
        assertThat(ok.body().get("lockSha256").asString()).isEqualTo(expected);
        assertThat(ok.body().get("packages").asInt()).isEqualTo(2);
        // repetir a MESMA resposta (resposta perdida) devolve o gravado; outra resposta e recusada
        assertThat(answer(m, resolution, RESOLVED).status()).isEqualTo(200);
        assertThat(answer(m, resolution, "{\"outcome\":\"NOT_RESOLVED\",\"failure\":{\"phase\":\"INDEX_UNREACHABLE\"}}").body().get("reason").asString())
                .isEqualTo("managed_environment_resolution_closed");
        assertThat(fx.value("SELECT outcome || '|' || lock_sha256 || '|' || python_version FROM managed_environment_resolutions WHERE resolution_id = '"
                + resolution + "'")).isEqualTo("1|" + expected + "|3.12.10");
        // nada mais pendente
        assertThat(next(m).body().has("environmentResolution")).isFalse();
    }

    @Test
    void aFalhaDaResolucaoDizADependenciaEAFaseSemSegredos() {
        Machine m = machine(CAPABLE);
        String resolution = resolution(m);
        MachineClient.Response r = answer(m, resolution, "{\"outcome\":\"INCOMPATIBLE\",\"pythonVersion\":\"3.12.10\",\"failure\":{\"requirement\":"
                + "\"nekkora-fixture-a==1.2.3\",\"phase\":\"NO_COMPATIBLE_WHEEL\",\"summary\":\"sem wheel em https://usuario:senha123@indice.test/simple token=abc123\"}}");
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        assertThat(fx.value("SELECT outcome || '|' || failure_requirement || '|' || failure_phase || '|' || failure_summary FROM managed_environment_resolutions "
                + "WHERE resolution_id = '" + resolution + "'"))
                .isEqualTo("2|nekkora-fixture-a==1.2.3|NO_COMPATIBLE_WHEEL|sem wheel em https://[removido]@indice.test/simple token=[removido]");
    }

    @Test
    void aOfertaManagedTrazOLockEAsEtapasEORelatorioSaoRegistradosAntesDaRecusa() {
        Machine m = machine(CAPABLE);
        String resolution = resolution(m);
        assertThat(answer(m, resolution, RESOLVED).status()).isEqualTo(200);
        String attempt = managedAttempt(m, resolution);
        JsonNode offer = next(m).body().get("attempt");
        assertThat(offer.get("attemptId").asString()).isEqualTo(attempt);
        JsonNode env = offer.get("environment");
        assertThat(env.get("mode").asString()).isEqualTo("MANAGED");
        assertThat(env.get("index").asString()).isEqualTo("PYPI");
        assertThat(env.get("lock").size()).isEqualTo(2);
        assertThat(env.get("lock").get(0).get("sha256").asString()).isEqualTo(SHA_A);
        assertThat(env.get("checks").get(0).asString()).isEqualTo("PLAYWRIGHT_CHROMIUM");
        String g = grant(offer);
        assertThat(post(m, "/remote/attempts/" + attempt + "/ack", "{" + g + "}").status()).isEqualTo(200);
        // etapa fora do preparo: recusada
        assertThat(post(m, "/remote/attempts/" + attempt + "/stage", "{" + g + ",\"stage\":\"ENV_CREATING\"}").body().get("reason").asString())
                .isEqualTo("remote_attempt_transition");
        assertThat(post(m, "/remote/attempts/" + attempt + "/state", "{" + g + ",\"state\":\"PREPARING\"}").status()).isEqualTo(200);
        assertThat(post(m, "/remote/attempts/" + attempt + "/stage", "{" + g + ",\"stage\":\"ENV_CREATING\"}").body().get("proceed").asBoolean()).isTrue();
        assertThat(post(m, "/remote/attempts/" + attempt + "/stage", "{" + g + ",\"stage\":\"DEPS_INSTALLING\"}").status()).isEqualTo(200);
        assertThat(post(m, "/remote/attempts/" + attempt + "/stage", "{" + g + ",\"stage\":\"DEPS_INSTALLING\"}").status()).isEqualTo(200);
        assertThat(post(m, "/remote/attempts/" + attempt + "/stage", "{" + g + ",\"stage\":\"RUNNING\"}").status()).isEqualTo(400);
        assertThat(fx.value("SELECT string_agg(stage::text, ',' ORDER BY stage) FROM remote_attempt_environment_stages WHERE attempt_id = '" + attempt + "'"))
                .isEqualTo("1,2");
        // relatorio FAILED: o registro do preparo sai sem segredos e com o FIM do texto
        String log = "x".repeat(70_000) + "\\nERROR: Could not install nekkora-fixture-b from https://user:pw@idx.test/simple Authorization: Basic dXNlcjpwdw==\\nfim";
        MachineClient.Response rep = report(m, attempt, "{" + g + ",\"outcome\":\"FAILED\",\"pythonVersion\":\"3.12.10\",\"pipVersion\":\"25.0.1\",\"installed\":[],"
                + "\"failure\":{\"requirement\":\"nekkora-fixture-b==0.1.0\",\"phase\":\"HASH_MISMATCH\",\"summary\":\"hash diferente\"},\"createMs\":800,"
                + "\"installMs\":1200,\"log\":\"" + log + "\"}");
        assertThat(rep.status()).as(rep.raw()).isEqualTo(200);
        assertThat(rep.body().get("outcome").asString()).isEqualTo("FAILED");
        String stored = fx.value("SELECT preparation_log FROM remote_attempt_environment_reports WHERE attempt_id = '" + attempt + "'");
        assertThat(stored).startsWith("[inicio do registro cortado]").endsWith("fim").contains("https://[removido]@idx.test").doesNotContain("pw@")
                .doesNotContain("dXNlcjpwdw==");
        assertThat(stored.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64 * 1024);
        // outro resultado depois: 409; o mesmo: devolve o gravado
        assertThat(report(m, attempt, "{" + g + ",\"outcome\":\"READY\",\"installed\":[]}").body().get("reason").asString())
                .isEqualTo("managed_environment_report_conflict");
        // a maquina recusa a tentativa com o motivo novo; a automacao nao iniciou
        MachineClient.Response refused = post(m, "/remote/attempts/" + attempt + "/state", "{" + g + ",\"state\":\"REFUSED\",\"refusalReason\":\"DEPENDENCY_INSTALL_FAILED\"}");
        assertThat(refused.status()).as(refused.raw()).isEqualTo(200);
        assertThat(fx.value("SELECT state || '|' || refusal_reason || '|' || (started_at IS NULL) FROM remote_attempts WHERE attempt_id = '" + attempt + "'"))
                .isEqualTo("11|DEPENDENCY_INSTALL_FAILED|true");
        // depois da recusa a etapa nao e mais registrada, e a maquina e mandada parar
        assertThat(post(m, "/remote/attempts/" + attempt + "/stage", "{" + g + ",\"stage\":\"DEPS_INSTALLING\"}").body().get("proceed").asBoolean()).isFalse();
    }

    @Test
    void oAceiteRevogadoDepoisDaSolicitacaoImpedeOInicio() {
        Machine m = machine(CAPABLE);
        String resolution = resolution(m);
        assertThat(answer(m, resolution, RESOLVED).status()).isEqualTo(200);
        String attempt = managedAttempt(m, resolution);
        JsonNode offer = next(m).body().get("attempt");
        String g = grant(offer);
        assertThat(post(m, "/remote/attempts/" + attempt + "/ack", "{" + g + "}").status()).isEqualTo(200);
        assertThat(post(m, "/remote/attempts/" + attempt + "/state", "{" + g + ",\"state\":\"PREPARING\"}").status()).isEqualTo(200);
        DB.asSuperuser("UPDATE managed_environment_authorizations SET revoked_at = now(), revoked_by_membership_id = '" + fx.adminMembership
                + "', revocation_reason = 'BACK_TO_PREPARED' WHERE resolution_id = '" + resolution + "'");
        MachineClient.Response starting = post(m, "/remote/attempts/" + attempt + "/state", "{" + g + ",\"state\":\"STARTING\"}");
        assertThat(starting.body().get("proceed").asBoolean()).isFalse();
        assertThat(starting.body().get("refusalReason").asString()).isEqualTo("AUTHORIZATION_REVOKED");
    }

    @Test
    void oPreparoInterrompidoPeloReinicioFicaNaoIniciadoComRelatorio() {
        Machine m = machine(CAPABLE);
        String resolution = resolution(m);
        assertThat(answer(m, resolution, RESOLVED).status()).isEqualTo(200);
        String attempt = managedAttempt(m, resolution);
        String g = grant(next(m).body().get("attempt"));
        assertThat(post(m, "/remote/attempts/" + attempt + "/ack", "{" + g + "}").status()).isEqualTo(200);
        assertThat(post(m, "/remote/attempts/" + attempt + "/state", "{" + g + ",\"state\":\"PREPARING\"}").status()).isEqualTo(200);
        assertThat(report(m, attempt, "{" + g + ",\"outcome\":\"INTERRUPTED\",\"installed\":[]}").status()).isEqualTo(200);
        assertThat(post(m, "/remote/attempts/" + attempt + "/state", "{" + g + ",\"state\":\"REFUSED\",\"refusalReason\":\"ENVIRONMENT_SETUP_INTERRUPTED\"}")
                .status()).isEqualTo(200);
        assertThat(fx.value("SELECT r.outcome || '|' || t.state || '|' || t.refusal_reason FROM remote_attempt_environment_reports r JOIN remote_attempts t "
                + "USING (attempt_id) WHERE r.attempt_id = '" + attempt + "'")).isEqualTo("3|11|ENVIRONMENT_SETUP_INTERRUPTED");
    }

    @Test
    void naTentativaDoPythonPreparadoNadaMuda() {
        Machine m = machine(CAPABLE);
        String execution = fx.value("INSERT INTO remote_executions (organization_id, workspace_id, automation_id, automation_version_id, package_fingerprint, "
                + "package_fingerprint_rule, requirements_revision, effective_dependencies, request_key, requested_by_membership_id) SELECT '" + fx.org + "', '"
                + fx.workspace + "', '" + fx.automation + "', '" + fx.version + "', command_json -> 'agent_package' ->> 'fingerprint', "
                + "command_json -> 'agent_package' ->> 'fingerprint_rule', 1, '[\"nekkora-fixture-a==1.2.3\"]'::jsonb, gen_random_uuid(), '" + fx.adminMembership
                + "' FROM automation_versions WHERE automation_version_id = '" + fx.version + "' RETURNING remote_execution_id");
        String attempt = fx.value("INSERT INTO remote_attempts (organization_id, remote_execution_id, attempt_number, agent_id, worker_id, authorization_id, "
                + "created_by_membership_id, offer_valid_until) VALUES ('" + fx.org + "', '" + execution + "', 1, '" + m.agent() + "', '" + m.worker() + "', '"
                + m.authorization() + "', '" + fx.adminMembership + "', clock_timestamp() + interval '15 minutes') RETURNING attempt_id");
        JsonNode offer = next(m).body().get("attempt");
        assertThat(offer.has("environment")).isFalse();
        String g = grant(offer);
        assertThat(post(m, "/remote/attempts/" + attempt + "/ack", "{" + g + "}").status()).isEqualTo(200);
        assertThat(post(m, "/remote/attempts/" + attempt + "/state", "{" + g + ",\"state\":\"PREPARING\"}").status()).isEqualTo(200);
        // etapas e relatorio nao existem no Python preparado
        assertThat(post(m, "/remote/attempts/" + attempt + "/stage", "{" + g + ",\"stage\":\"ENV_CREATING\"}").status()).isEqualTo(409);
        assertThat(report(m, attempt, "{" + g + ",\"outcome\":\"READY\",\"installed\":[]}").status()).isEqualTo(409);
    }

    @Test
    void aMaquinaNaoGravaAceiteNemPedidoDeResolucao() {
        assertThat(asRole("nekkora_ingestion", "INSERT INTO managed_environment_authorizations (organization_id, workspace_id, automation_id, automation_version_id, "
                + "agent_id, worker_id, environment_id, requirements_revision, resolution_id, lock_sha256, checks, granted_by_membership_id) VALUES ('" + fx.org
                + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), '" + ENVIRONMENT + "', 1, gen_random_uuid(), '"
                + SHA_A + "', '[]'::jsonb, gen_random_uuid())")).isEqualTo("42501");
        assertThat(asRole("nekkora_ingestion", "UPDATE managed_environment_resolutions SET dependencies = '[]'::jsonb")).isEqualTo("42501");
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

    private static void untriggered(String sql) {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("fixture falhou (sqlstate " + e.getSQLState() + ")", e);
        }
    }
}
