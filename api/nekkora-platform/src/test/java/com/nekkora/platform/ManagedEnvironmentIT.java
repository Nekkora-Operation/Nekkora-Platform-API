package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.remote.ManagedLock;
import com.nekkora.core.testsupport.BrowserClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AMBIENTE-PYTHON-001 (076) - o ambiente Python por execucao, lado das PESSOAS, com a execucao remota e o ambiente gerenciado
 * LIGADOS, pela API real e pelo papel real nekkora_platform. A maquina e simulada por SQL com o papel real nekkora_ingestion (o
 * canal dela, por HTTP, e conferido no gateway: ManagedEnvironmentGatewayIT). Mesmo contexto do RemoteExecutionIT.
 *
 * <p>Confere: a lista efetiva da VERSAO PEDIDA (correcao A1); a resolucao so com maquina compativel e Python base instalacao (D6);
 * um pedido em aberto por vez; o lock com as dependencias indiretas e os preparos externos (D5); o aceite explicito (confirmacao
 * reforcada, navegador do Playwright escolhido, hash conferido) e a trilha; a solicitacao MANAGED so com o aceite vigente e
 * correspondente, com o retrato do lock na tentativa; nova revisao das dependencias pede aceite novo (com o que mudou); voltar
 * ao Python preparado; e o registro do preparo por 30 dias (D7), inclusive de preparo que falhou antes de iniciar.
 */
@TestPropertySource(properties = {"nekkora.remote-execution.enabled=true", "nekkora.remote-execution.reconcile-seconds=0", "nekkora.resultados.dir=${java.io.tmpdir}/nekkora-platform-it-resultados",
        // AMBIENTE-PYTHON-001: ligado aqui tambem (mesmo contexto do ManagedEnvironmentIT) - o Python preparado nao muda com a chave ligada
        "nekkora.managed-environment.enabled=true"})
class ManagedEnvironmentIT extends PlatformTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FINGERPRINT = "5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d";
    private static final String EXTERNAL = "nekkora-agent/external-python-fingerprint/1";
    static final String ENV_A = "A".repeat(43);
    static final String ENV_V = "V".repeat(43);
    static final String SHA_A = "a1".repeat(32);
    static final String SHA_P = "c3".repeat(32);
    static final String SHA_G = "d4".repeat(32);
    static final String SHA_X = "e5".repeat(32);

    @Autowired
    OrgTransactions tx;

    private record Setup(Org org, BrowserClient admin, UUID workspace, UUID automation, UUID version) {
        String o() {
            return "/api/v1/o/" + org.id();
        }

        String requests() {
            return o() + "/workspaces/" + workspace + "/remote-executions";
        }

        String env(Machine m) {
            return o() + "/automations/" + automation + "/remote-machines/" + m.agent() + "/managed-environment";
        }
    }

    private record Machine(UUID worker, UUID agent, String name) {
    }

    /** Uma versao publicada (pacote Python externo) com o manifesto indicado. */
    private UUID publish(Org o, UUID ws, UUID automation, String manifest) {
        UUID version = UUID.randomUUID();
        String sha = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        DB.asSuperuser("INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id) "
                        + "VALUES ('" + version + "', '" + o.id() + "', '" + ws + "', '" + automation + "', 1, jsonb_build_object('contract_version',1,'artifact',"
                        + "jsonb_build_object('reference','conciliacao','sha256','" + "ab".repeat(32) + "'),'agent_package',jsonb_build_object('fingerprint_rule',"
                        + "'" + EXTERNAL + "','fingerprint','" + FINGERPRINT + "'),'runtime','python3.12','entrypoint','main.py',"
                        + "'arguments','[]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb), '" + o.adminMembership() + "')",
                "UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '" + o.adminMembership()
                        + "' WHERE automation_version_id = '" + version + "'",
                "ALTER TABLE automation_artifacts DISABLE TRIGGER USER",
                "INSERT INTO automation_artifacts (organization_id, workspace_id, automation_id, automation_version_id, sha256, size_bytes, package_kind, "
                        + "fingerprint_rule, fingerprint, files_count, manifest_version, storage_key, received_by_membership_id, python_requirement, dependencies, "
                        + "package_name, package_version) VALUES ('" + o.id() + "', '" + ws + "', '" + automation + "', '" + version + "', '" + sha + "', 1234, 2, '"
                        + EXTERNAL + "', '" + FINGERPRINT + "', 3, '1.0.0', '" + sha + "', '" + o.adminMembership() + "', '>=3.12', '" + manifest + "'::jsonb, "
                        + "'conciliacao', '1.0.0')",
                "ALTER TABLE automation_artifacts ENABLE TRIGGER USER");
        return version;
    }

    private Setup setup(String manifest) {
        Org o = newOrganization();
        for (int code = 1; code <= 5; code++) {
            DB.asSuperuser("INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, "
                    + "limit_value, provisioned_by_user_id) VALUES ('" + o.id() + "', " + code + ", 1, now(), 2, NULL, '" + o.adminId() + "')");
        }
        UUID ws = newWorkspace(o, newClient(o));
        UUID automation = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO automations (automation_id, organization_id, workspace_id, name, status, created_by_membership_id) VALUES ('" + automation
                + "', '" + o.id() + "', '" + ws + "', 'Conciliacao', 1, '" + o.adminMembership() + "')");
        UUID version = publish(o, ws, automation, manifest);
        BrowserClient b = browser();
        firstLogin(b, o.id(), new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray()));
        Setup s = new Setup(o, b, ws, automation, version);
        assertThat(s.admin().post(s.o() + "/auth/mfa/step-up", "{\"code\":\"" + code(s.org().adminId()) + "\"}").status()).isEqualTo(204);
        declare(s, null);
        return s;
    }

    private BrowserClient.Response put(BrowserClient b, String path, String body) {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        return b.send(body == null ? r.PUT(HttpRequest.BodyPublishers.noBody()) : r.PUT(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json"));
    }

    private BrowserClient.Response delete(BrowserClient b, String path) {
        return b.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).DELETE());
    }

    private Machine machine(Setup s, String name, boolean managedCapable) throws Exception {
        BrowserClient.Response r = s.admin().post(s.o() + "/agent-enrollments",
                "{\"workerName\":\"" + name + "\",\"agentName\":\"Maquina " + name + "\",\"expiresInMinutes\":15,\"remoteExecution\":true}");
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        UUID enrollment = UUID.fromString(r.field("enrollmentId"));
        UUID worker = UUID.fromString(r.field("workerId"));
        String org = s.org().id().toString();
        String agentName = "Maquina " + name;
        String agent = "(SELECT agent_id FROM agents WHERE organization_id = '" + org + "' AND name = '" + agentName + "')";
        String creator = "(SELECT created_by_membership_id FROM agent_enrollments WHERE enrollment_id = '" + enrollment + "')";
        assertThat(as("nekkora_ingestion", s.org().id(),
                "INSERT INTO agents (organization_id, name, agent_version, status) VALUES ('" + org + "', '" + agentName + "', '0.2.0', 2)",
                "INSERT INTO worker_agent_assignments (organization_id, worker_id, agent_id, status, assigned_by_membership_id) SELECT '" + org + "', '" + worker
                        + "', " + agent + ", 1, " + creator,
                "INSERT INTO agent_credentials (organization_id, agent_id, credential_hash, issued_by_membership_id, issued_at, expires_at) SELECT '" + org + "', "
                        + agent + ", encode(sha256('" + agentName + org + "'::bytea), 'hex'), " + creator + ", clock_timestamp(), clock_timestamp() + interval '90 days'",
                "UPDATE agent_enrollments SET consumed_at = clock_timestamp(), consumed_credential_id = (SELECT credential_id FROM agent_credentials WHERE agent_id = "
                        + agent + "), agent_id = " + agent + " WHERE organization_id = '" + org + "' AND enrollment_id = '" + enrollment + "'",
                "INSERT INTO agent_remote_enablements (agent_id, organization_id, worker_id, enrollment_id, enabled_by_membership_id) SELECT " + agent + ", '"
                        + org + "', '" + worker + "', '" + enrollment + "', " + creator)).isEqualTo("OK");
        assertThat(put(s.admin(), s.o() + "/workers/" + worker + "/workspaces/" + s.workspace(), null).status()).isEqualTo(201);
        Machine m = new Machine(worker, UUID.fromString(value("SELECT agent_id FROM agents WHERE organization_id = '" + org + "' AND name = '" + agentName + "'")),
                agentName);
        inventory(s, m, ENV_A, "3.12.10", 1);
        assertThat(selectPython(s, m, ENV_A).status()).isEqualTo(200);
        heartbeat(s, m, managedCapable);
        authorize(s, m, s.version());
        return m;
    }

    private static void inventory(Setup s, Machine m, String environment, String version, int kind) {
        assertThat(as("nekkora_ingestion", s.org().id(), "INSERT INTO agent_python_environments (agent_id, environment_id, organization_id, python_version, kind, "
                + "observed_at) VALUES ('" + m.agent() + "', '" + environment + "', '" + s.org().id() + "', '" + version + "', " + kind + ", clock_timestamp())"))
                .isEqualTo("OK");
    }

    private BrowserClient.Response selectPython(Setup s, Machine m, String environment) {
        return put(s.admin(), s.o() + "/automations/" + s.automation() + "/remote-machines/" + m.agent() + "/python-selection",
                "{\"environmentId\":\"" + environment + "\"}");
    }

    private static void heartbeat(Setup s, Machine m, boolean managed) {
        String org = s.org().id().toString();
        assertThat(as("nekkora_ingestion", s.org().id(),
                "INSERT INTO agent_presence (agent_id, organization_id, contract_version, capabilities, reported_at) VALUES ('" + m.agent() + "', '" + org
                        + "', '1.0', '" + (managed ? "{REMOTE_RUN_1_0,PY_INVENTORY_1_0,MANAGED_ENV_1_0}" : "{REMOTE_RUN_1_0,PY_INVENTORY_1_0}") + "'::text[], "
                        + "clock_timestamp()) ON CONFLICT (agent_id) DO UPDATE SET capabilities = EXCLUDED.capabilities, reported_at = EXCLUDED.reported_at"))
                .isEqualTo("OK");
    }

    private void authorize(Setup s, Machine m, UUID version) {
        BrowserClient.Response a = s.admin().post(s.o() + "/workspaces/" + s.workspace() + "/local-run-authorizations", "{\"workerId\":\"" + m.worker()
                + "\",\"automationVersionId\":\"" + version + "\",\"validUntil\":\"" + OffsetDateTime.now(ZoneOffset.UTC).plusDays(30).withNano(0) + "\"}");
        assertThat(a.status()).as(a.body()).isEqualTo(201);
    }

    private void declare(Setup s, String text) {
        String current = valueUnchecked("SELECT coalesce(max(revision_number), 0) FROM automation_runtime_requirements WHERE automation_id = '" + s.automation() + "'");
        String body = text == null ? "{\"source\":\"PACKAGE\"" : "{\"source\":\"TEXT\",\"text\":" + JSON.writeValueAsString(text);
        assertThat(put(s.admin(), s.o() + "/automations/" + s.automation() + "/runtime-requirements", body + ("0".equals(current) ? "" : ",\"expectedRevision\":" + current) + "}")
                .status()).isEqualTo(201);
    }

    private BrowserClient.Response request(Setup s, Machine m, UUID version, String mode) {
        return s.admin().post(s.requests(), "{\"automationVersionId\":\"" + version + "\",\"agentId\":\"" + m.agent() + "\",\"requestKey\":\"" + UUID.randomUUID()
                + "\"" + (mode == null ? "" : ",\"environmentMode\":\"" + mode + "\"") + "}");
    }

    private BrowserClient.Response view(Setup s, Machine m) {
        return s.admin().get(s.env(m) + "?versionId=" + s.version());
    }

    private BrowserClient.Response resolve(Setup s, Machine m) {
        return s.admin().post(s.env(m) + "/resolutions", "{\"versionId\":\"" + s.version() + "\"}");
    }

    private static String item(String name, String version, String sha) {
        return "{\"name\":\"" + name + "\",\"version\":\"" + version + "\",\"sha256\":\"" + sha + "\",\"fileName\":\"" + name.replace('-', '_') + "-" + version
                + "-py3-none-any.whl\"}";
    }

    /** A maquina responde a resolucao em aberto (papel da ingestao, como o gateway gravaria). Devolve o hash do lock. */
    private static String answer(Setup s, Machine m, List<ManagedLock.Item> lock) {
        StringBuilder json = new StringBuilder("[");
        for (ManagedLock.Item i : lock) {
            json.append(json.length() > 1 ? "," : "").append(item(i.name(), i.version(), i.sha256()));
        }
        json.append("]");
        List<ManagedLock.Item> withFiles = lock.stream().map(i -> new ManagedLock.Item(i.name(), i.version(), i.sha256(), i.name().replace('-', '_') + "-" + i.version()
                + "-py3-none-any.whl")).toList();
        String sha = ManagedLock.sha256(withFiles);
        assertThat(as("nekkora_ingestion", s.org().id(), "UPDATE managed_environment_resolutions SET outcome = 1, python_version = '3.12.10', pip_version = '25.0.1', "
                + "lock = '" + json + "'::jsonb, lock_sha256 = '" + sha + "' WHERE agent_id = '" + m.agent() + "' AND outcome IS NULL")).isEqualTo("OK");
        return sha;
    }

    private BrowserClient.Response accept(Setup s, Machine m, String resolution, String sha, String checks) {
        return s.admin().post(s.env(m) + "/authorizations", "{\"versionId\":\"" + s.version() + "\",\"resolutionId\":\"" + resolution + "\",\"lockSha256\":\"" + sha
                + "\",\"checks\":" + checks + ",\"acknowledge\":true}");
    }

    private static final List<ManagedLock.Item> LOCK = List.of(new ManagedLock.Item("nekkora-fixture-a", "1.2.3", SHA_A, null),
            new ManagedLock.Item("playwright", "1.48.0", SHA_P, null), new ManagedLock.Item("greenlet", "3.1.1", SHA_G, null));

    @Test
    void aSolicitacaoUsaAsDependenciasDaVersaoPedidaENaoDaUltimaPublicada() throws Exception {
        Setup s = setup("[]");
        Machine m = machine(s, "A1", false);
        // a versao 2, publicada depois, declara uma dependencia no manifesto; a 1 nao declara nenhuma
        UUID v2 = publish(s.org(), s.workspace(), s.automation(), "[\"nekkora-fixture-a==1.2.3\"]");
        authorize(s, m, v2);
        BrowserClient.Response r1 = request(s, m, s.version(), null);
        assertThat(r1.status()).as(r1.body()).isEqualTo(201);
        JsonNode x1 = JSON.readTree(r1.body());
        assertThat(x1.get("dependencies").size()).as("a versao 1 nao tem a dependencia da versao 2").isZero();
        assertThat(x1.get("environmentMode").asString()).isEqualTo("PREPARED");
        assertThat(x1.get("attempts").get(0).has("environment")).isFalse();
        // libera a maquina e pede a versao 2: a lista e a dela
        DB.asSuperuser("UPDATE remote_attempts SET state = 9 WHERE remote_execution_id = '" + x1.get("remoteExecutionId").asString() + "'");
        BrowserClient.Response r2 = request(s, m, v2, "PREPARED");
        assertThat(r2.status()).as(r2.body()).isEqualTo(201);
        assertThat(JSON.readTree(r2.body()).get("dependencies").get(0).asString()).isEqualTo("nekkora-fixture-a==1.2.3");
    }

    @Test
    void doPedidoDaListaCompletaAoAceiteEASolicitacaoManaged() throws Exception {
        Setup s = setup("[\"nekkora-fixture-a==1.2.3\"]");
        declare(s, "playwright==1.48.0");
        assertThat(s.admin().get(s.o() + "/me").body()).contains("\"managedEnvironmentAvailable\":true");
        Machine m = machine(s, "Gerenciada", false);
        // sem a capacidade, nada e pedido
        BrowserClient.Response incompatible = resolve(s, m);
        assertThat(incompatible.status()).isEqualTo(409);
        assertThat(incompatible.body()).contains("managed_environment_machine_incompatible");
        heartbeat(s, m, true);
        // D6: o Python base e uma instalacao; com um venv escolhido, a resolucao e recusada
        inventory(s, m, ENV_V, "3.12.10", 2);
        assertThat(selectPython(s, m, ENV_V).status()).isEqualTo(200);
        assertThat(resolve(s, m).body()).contains("managed_environment_base_not_installation");
        assertThat(selectPython(s, m, ENV_A).status()).isEqualTo(200);
        // sem aceite, a solicitacao MANAGED e recusada e nada e gravado
        long before = Long.parseLong(value("SELECT count(*) FROM remote_executions WHERE organization_id = '" + s.org().id() + "'"));
        assertThat(request(s, m, s.version(), "MANAGED").body()).contains("managed_environment_not_authorized");
        assertThat(Long.parseLong(value("SELECT count(*) FROM remote_executions WHERE organization_id = '" + s.org().id() + "'"))).isEqualTo(before);

        BrowserClient.Response pedido = resolve(s, m);
        assertThat(pedido.status()).as(pedido.body()).isEqualTo(201);
        JsonNode v = JSON.readTree(pedido.body());
        assertThat(v.get("resolution").get("state").asString()).isEqualTo("PENDING");
        assertThat(v.get("requirements").get("dependencies").toString()).isEqualTo("[\"nekkora-fixture-a==1.2.3\",\"playwright==1.48.0\"]");
        assertThat(v.get("externalPrerequisites").get(0).get("code").asString()).isEqualTo("PLAYWRIGHT_BROWSER");
        assertThat(resolve(s, m).body()).contains("managed_environment_resolution_pending");
        String sha = answer(s, m, LOCK);
        v = JSON.readTree(view(s, m).body());
        JsonNode res = v.get("resolution");
        assertThat(res.get("outcome").asString()).isEqualTo("RESOLVED");
        assertThat(res.get("current").asBoolean()).isTrue();
        assertThat(res.get("lockSha256").asString()).isEqualTo(sha);
        assertThat(res.get("lock").get(2).get("name").asString()).isEqualTo("greenlet");
        assertThat(res.get("lock").get(2).get("declared").asBoolean()).as("dependencia indireta").isFalse();
        assertThat(res.get("lock").get(1).get("declared").asBoolean()).isTrue();
        String resolution = res.get("resolutionId").asString();
        // aceite: com playwright, ao menos um navegador; o hash tem de ser o da resolucao
        assertThat(accept(s, m, resolution, sha, "[]").status()).isEqualTo(400);
        assertThat(accept(s, m, resolution, SHA_X, "[\"PLAYWRIGHT_CHROMIUM\"]").body()).contains("managed_environment_lock_changed");
        BrowserClient.Response ok = accept(s, m, resolution, sha, "[\"PLAYWRIGHT_CHROMIUM\"]");
        assertThat(ok.status()).as(ok.body()).isEqualTo(201);
        JsonNode auth = JSON.readTree(ok.body()).get("authorization");
        assertThat(auth.get("status").asString()).isEqualTo("CURRENT");
        assertThat(auth.get("packages").asInt()).isEqualTo(3);
        assertThat(value("SELECT count(*) FROM automation_admin_events WHERE automation_id = '" + s.automation() + "' AND act = 13")).isEqualTo("1");

        BrowserClient.Response managed = request(s, m, s.version(), "MANAGED");
        assertThat(managed.status()).as(managed.body()).isEqualTo(201);
        JsonNode x = JSON.readTree(managed.body());
        assertThat(x.get("environmentMode").asString()).isEqualTo("MANAGED");
        JsonNode env = x.get("attempts").get(0).get("environment");
        assertThat(env.get("lockSha256").asString()).isEqualTo(sha);
        assertThat(env.get("packages").asInt()).isEqualTo(3);
        assertThat(env.get("checks").get(0).asString()).isEqualTo("PLAYWRIGHT_CHROMIUM");
        assertThat(env.get("stages").size()).isZero();
        assertThat(env.get("report").isNull()).isTrue();
        JsonNode cand = candidate(s, m);
        assertThat(cand.get("managedEnvironment").get("authorized").asString()).isEqualTo("CURRENT");
        assertThat(cand.get("managedEnvironment").get("compatible").asBoolean()).isTrue();
    }

    private JsonNode candidate(Setup s, Machine m) {
        BrowserClient.Response r = s.admin().get(s.requests() + "/candidates?automationVersion=" + s.version());
        for (JsonNode n : JSON.readTree(r.body()).get("items")) {
            if (n.get("agentId").asString().equals(m.agent().toString())) {
                return n;
            }
        }
        return null;
    }

    /** Resolucao respondida e aceita (sem playwright). Devolve o hash aceito. */
    private String accepted(Setup s, Machine m, List<ManagedLock.Item> lock) {
        assertThat(resolve(s, m).status()).isEqualTo(201);
        String sha = answer(s, m, lock);
        String resolution = JSON.readTree(view(s, m).body()).get("resolution").get("resolutionId").asString();
        assertThat(accept(s, m, resolution, sha, "[]").status()).isEqualTo(201);
        return sha;
    }

    @Test
    void novaRevisaoDasDependenciasPedeAceiteNovoComOQueMudouEVoltarAoPythonPreparado() throws Exception {
        Setup s = setup("[\"nekkora-fixture-a==1.2.3\"]");
        Machine m = machine(s, "Revisao", true);
        accepted(s, m, List.of(new ManagedLock.Item("nekkora-fixture-a", "1.2.3", SHA_A, null)));
        declare(s, "extra-lib==2.0.0");
        JsonNode auth = JSON.readTree(view(s, m).body()).get("authorization");
        assertThat(auth.get("status").asString()).isEqualTo("STALE");
        assertThat(auth.get("staleReasons").toString()).isEqualTo("[\"REQUIREMENTS_CHANGED\"]");
        assertThat(candidate(s, m).get("managedEnvironment").get("authorized").asString()).isEqualTo("STALE");
        assertThat(request(s, m, s.version(), "MANAGED").body()).contains("managed_environment_changed");
        // a nova resolucao mostra o que mudou em relacao ao aceito
        assertThat(resolve(s, m).status()).isEqualTo(201);
        answer(s, m, List.of(new ManagedLock.Item("nekkora-fixture-a", "1.2.3", SHA_A, null), new ManagedLock.Item("extra-lib", "2.0.0", SHA_X, null)));
        JsonNode diff = JSON.readTree(view(s, m).body()).get("authorization").get("diff");
        assertThat(diff.get("added").toString()).isEqualTo("[\"extra-lib==2.0.0\"]");
        assertThat(diff.get("removed").size()).isZero();
        // voltar ao Python preparado: revoga; MANAGED recusada; PREPARED segue como sempre
        BrowserClient.Response back = delete(s.admin(), s.env(m) + "/authorizations/current?versionId=" + s.version());
        assertThat(back.status()).as(back.body()).isEqualTo(204);
        assertThat(JSON.readTree(view(s, m).body()).get("authorization").isNull()).isTrue();
        assertThat(value("SELECT count(*) FROM automation_admin_events WHERE automation_id = '" + s.automation() + "' AND act = 14")).isEqualTo("1");
        assertThat(request(s, m, s.version(), "MANAGED").body()).contains("managed_environment_not_authorized");
        assertThat(request(s, m, s.version(), null).status()).isEqualTo(201);
    }

    @Test
    void oRegistroDoPreparoValeTrintaDiasMesmoQuandoAAutomacaoNaoIniciou() throws Exception {
        Setup s = setup("[\"nekkora-fixture-a==1.2.3\"]");
        Machine m = machine(s, "Registro", true);
        accepted(s, m, List.of(new ManagedLock.Item("nekkora-fixture-a", "1.2.3", SHA_A, null)));
        JsonNode x = JSON.readTree(request(s, m, s.version(), "MANAGED").body());
        String execution = x.get("remoteExecutionId").asString();
        String attempt = x.get("attempts").get(0).get("attemptId").asString();
        // a maquina recebe, prepara, falha na instalacao e recusa: a automacao NAO iniciou
        assertThat(as("nekkora_ingestion", s.org().id(), "UPDATE remote_attempts SET state = 2, token_hash = repeat('a', 64), epoch = epoch + 1 WHERE attempt_id = '"
                        + attempt + "'", "UPDATE remote_attempts SET state = 3 WHERE attempt_id = '" + attempt + "'",
                "UPDATE remote_attempts SET state = 4 WHERE attempt_id = '" + attempt + "'",
                "INSERT INTO remote_attempt_environment_stages (attempt_id, stage, organization_id) VALUES ('" + attempt + "', 1, '" + s.org().id() + "')",
                "INSERT INTO remote_attempt_environment_reports (attempt_id, organization_id, outcome, installed, failure_requirement, failure_phase, failure_summary, "
                        + "preparation_log) VALUES ('" + attempt + "', '" + s.org().id() + "', 2, '[]'::jsonb, 'nekkora-fixture-a==1.2.3', 'NO_COMPATIBLE_WHEEL', "
                        + "'sem wheel', 'ERROR: No matching distribution')",
                "UPDATE remote_attempts SET state = 11, refusal_reason = 'DEPENDENCY_INSTALL_FAILED' WHERE attempt_id = '" + attempt + "'")).isEqualTo("OK");
        String path = s.o() + "/remote-executions/" + execution + "/attempts/" + attempt + "/environment-log";
        BrowserClient.Response log = s.admin().get(path);
        assertThat(log.status()).isEqualTo(200);
        assertThat(log.body()).isEqualTo("ERROR: No matching distribution");
        JsonNode env = JSON.readTree(s.admin().get(s.o() + "/remote-executions/" + execution).body()).get("attempts").get(0).get("environment");
        assertThat(env.get("report").get("outcome").asString()).isEqualTo("FAILED");
        assertThat(env.get("report").get("failure").get("phase").asString()).isEqualTo("NO_COMPATIBLE_WHEEL");
        assertThat(env.get("stages").get(0).get("stage").asString()).isEqualTo("ENV_CREATING");
        // antes dos 30 dias o banco recusa apagar; o ciclo nao apaga nada
        assertThat(as("nekkora_platform", s.org().id(), "UPDATE remote_attempt_environment_reports SET preparation_log = NULL WHERE attempt_id = '" + attempt + "'"))
                .isEqualTo("23514");
        new com.nekkora.platform.admin.ManagedEnvironmentRetention(tx).cycle();
        assertThat(s.admin().get(path).status()).isEqualTo(200);
        // passagem do tempo (fixture): o relatorio tem 31 dias
        DB.asSuperuser("SET session_replication_role = replica",
                "UPDATE remote_attempt_environment_reports SET reported_at = now() - interval '31 days' WHERE attempt_id = '" + attempt + "'");
        assertThat(new com.nekkora.platform.admin.ManagedEnvironmentRetention(tx).cycle()).isGreaterThanOrEqualTo(1);
        BrowserClient.Response gone = s.admin().get(path);
        assertThat(gone.status()).isEqualTo(410);
        assertThat(gone.body()).contains("managed_environment_log_expired");
        env = JSON.readTree(s.admin().get(s.o() + "/remote-executions/" + execution).body()).get("attempts").get(0).get("environment");
        assertThat(env.get("report").get("logAvailable").asBoolean()).isFalse();
        assertThat(env.get("report").get("logExpiredAt").isNull()).isFalse();
        assertThat(env.get("report").get("failure").get("requirement").asString()).as("o resumo da falha fica").isEqualTo("nekkora-fixture-a==1.2.3");
    }

    @Test
    void aPlatformNaoEscreveARespostaDaMaquinaEAMaquinaNaoAceita() throws Exception {
        Setup s = setup("[]");
        assertThat(as("nekkora_platform", s.org().id(), "UPDATE managed_environment_resolutions SET outcome = 1")).isEqualTo("42501");
        assertThat(as("nekkora_ingestion", s.org().id(), "INSERT INTO managed_environment_authorizations (organization_id, workspace_id, automation_id, "
                + "automation_version_id, agent_id, worker_id, environment_id, requirements_revision, resolution_id, lock_sha256, checks, granted_by_membership_id) "
                + "VALUES ('" + s.org().id() + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), '" + ENV_A
                + "', 1, gen_random_uuid(), '" + SHA_A + "', '[]'::jsonb, gen_random_uuid())")).isEqualTo("42501");
    }

    private static String as(String role, UUID org, String... sqls) {
        try (Connection c = DB.connectAs(role)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT set_config('nekkora.organization_id', '" + org + "', true)");
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

    private static String value(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static String valueUnchecked(String sql) {
        try {
            return value(sql);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
