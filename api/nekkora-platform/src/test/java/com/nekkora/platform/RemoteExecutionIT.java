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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.nekkora.core.testsupport.BrowserClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T02 - a solicitacao de execucao remota pelo lado das PESSOAS, com o recurso LIGADO,
 * pela API real e pelo papel real nekkora_platform. Decisao da titular (15/11): aceitar exige pessoa com automation.execute,
 * Worker autorizado no ambiente, maquina habilitada, nao pausada e compativel, e autorizacao vigente da versao publicada para
 * AQUELE Worker; cada exigencia que falta e dita pelo seu motivo; na repeticao em outra maquina vale a autorizacao do Worker
 * de destino, nunca transferida. A maquina e simulada por SQL com o papel real nekkora_ingestion (o canal dela, por HTTP, e
 * conferido no modulo do gateway: RemoteDeliveryIT).
 */
@TestPropertySource(properties = {"nekkora.remote-execution.enabled=true", "nekkora.remote-execution.reconcile-seconds=0", "nekkora.resultados.dir=${java.io.tmpdir}/nekkora-platform-it-resultados"})
class RemoteExecutionIT extends PlatformTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FINGERPRINT = "5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d";

    private record Setup(Org org, BrowserClient admin, UUID workspace, UUID automation, UUID version) {
        String o() {
            return "/api/v1/o/" + org.id();
        }

        String requests() {
            return o() + "/workspaces/" + workspace + "/remote-executions";
        }
    }

    /** Uma maquina habilitada: Worker, Agent, e o nome (para a presenca). */
    private record Machine(UUID worker, UUID agent, String name) {
    }

    private static final String EXTERNAL = "nekkora-agent/external-python-fingerprint/1";
    private static final String PACKAGE_SHA = "5e".repeat(32);
    private static final String STUDIO = "nekkora-agent/package-fingerprint/1";

    /** A versao publicada e de um pacote Python externo: o unico tipo que a execucao remota aceita. */
    private Setup setup() {
        return setup(EXTERNAL);
    }

    private Setup setup(String rule) {
        Org o = newOrganization();
        for (int code = 1; code <= 5; code++) {
            DB.asSuperuser("INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, "
                    + "limit_value, provisioned_by_user_id) VALUES ('" + o.id() + "', " + code + ", 1, now(), 2, NULL, '" + o.adminId() + "')");
        }
        UUID ws = newWorkspace(o, newClient(o));
        UUID automation = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO automations (automation_id, organization_id, workspace_id, name, status, created_by_membership_id) VALUES ('" + automation
                        + "', '" + o.id() + "', '" + ws + "', 'Conciliacao', 1, '" + o.adminMembership() + "')",
                "INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id) "
                        + "VALUES ('" + version + "', '" + o.id() + "', '" + ws + "', '" + automation + "', 1, jsonb_build_object('contract_version',1,'artifact',"
                        + "jsonb_build_object('reference','conciliacao','sha256','" + "ab".repeat(32) + "'),'agent_package',jsonb_build_object('fingerprint_rule',"
                        + "'" + rule + "','fingerprint','" + FINGERPRINT + "'),'runtime','python3.12','entrypoint','main.py',"
                        + "'arguments','[]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb), '" + o.adminMembership() + "')",
                "UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '" + o.adminMembership()
                        + "' WHERE automation_version_id = '" + version + "'");
        // 048: a tentativa so nasce com o pacote da versao REGISTRADO (e dele que o banco fixa o que a maquina podera baixar)
        String common = "INSERT INTO automation_artifacts (organization_id, workspace_id, automation_id, automation_version_id, sha256, size_bytes, "
                + "package_kind, fingerprint_rule, fingerprint, files_count, manifest_version, storage_key, received_by_membership_id, ";
        String ids = "'" + o.id() + "', '" + ws + "', '" + automation + "', '" + version + "', '" + PACKAGE_SHA + "', 1234, ";
        String tail = "'" + PACKAGE_SHA + "', '" + o.adminMembership() + "', ";
        DB.asSuperuser("ALTER TABLE automation_artifacts DISABLE TRIGGER USER",
                EXTERNAL.equals(rule)
                        ? common + "python_requirement, dependencies, package_name, package_version) VALUES (" + ids + "2, '" + rule + "', '" + FINGERPRINT
                                + "', 3, '1.0.0', " + tail + "'>=3.12', '[]'::jsonb, 'conciliacao', '1.0.0')"
                        : common + "framework, execution_mode) VALUES (" + ids + "1, '" + rule + "', '" + FINGERPRINT + "', 3, '1.4.0', " + tail
                                + "'nekkora-rpa==1.0.0', 'single')",
                "ALTER TABLE automation_artifacts ENABLE TRIGGER USER");
        BrowserClient b = browser();
        firstLogin(b, o.id(), new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray()));
        Setup s = new Setup(o, b, ws, automation, version);
        stepUp(s);
        return s;
    }

    private void stepUp(Setup s) {
        assertThat(s.admin().post(s.o() + "/auth/mfa/step-up", "{\"code\":\"" + code(s.org().adminId()) + "\"}").status()).isEqualTo(204);
    }

    private BrowserClient.Response put(BrowserClient b, String path, String body) {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        return b.send(body == null ? r.PUT(HttpRequest.BodyPublishers.noBody()) : r.PUT(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json"));
    }

    /** Codigo marcado pela API real + resgate pela maquina (papel da ingestao, uma transacao). {@code inWorkspace}: autoriza o Worker no ambiente. */
    private Machine machine(Setup s, String name, boolean inWorkspace) throws Exception {
        return machine(s, name, inWorkspace, true);
    }

    /** {@code python}: publica um ambiente e escolhe o Python da automacao nesta maquina (so com o Worker no ambiente). */
    private Machine machine(Setup s, String name, boolean inWorkspace, boolean python) throws Exception {
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
        if (inWorkspace) {
            assertThat(put(s.admin(), s.o() + "/workers/" + worker + "/workspaces/" + s.workspace(), null).status()).isEqualTo(201);
        }
        Machine m = new Machine(worker, UUID.fromString(value("SELECT agent_id FROM agents WHERE organization_id = '" + org + "' AND name = '" + agentName + "'")),
                agentName);
        if (inWorkspace && python) {
            // 047: o inventario que a maquina publicaria e a escolha do Python desta automacao nela, pela API real
            inventory(s, m, ENV_A, "3.12.10");
            BrowserClient.Response chosen = selectPython(s, m, ENV_A);
            assertThat(chosen.status()).as(chosen.body()).isEqualTo(200);
        }
        return m;
    }

    static final String ENV_A = "A".repeat(43);
    static final String ENV_B = "B".repeat(43);

    /** O inventario de Python da maquina, como o gateway gravaria (papel da ingestao). */
    private static void inventory(Setup s, Machine m, String environment, String version) {
        assertThat(as("nekkora_ingestion", s.org().id(), "INSERT INTO agent_python_environments (agent_id, environment_id, organization_id, python_version, kind, "
                + "observed_at) VALUES ('" + m.agent() + "', '" + environment + "', '" + s.org().id() + "', '" + version + "', 1, clock_timestamp())")).isEqualTo("OK");
    }

    private BrowserClient.Response selectPython(Setup s, Machine m, String environment) {
        return put(s.admin(), s.o() + "/automations/" + s.automation() + "/remote-machines/" + m.agent() + "/python-selection",
                "{\"environmentId\":\"" + environment + "\"}");
    }

    /** A presenca da maquina, como o gateway: {@code capable} declara REMOTE_RUN_1_0. */
    private static void heartbeat(Setup s, Machine m, boolean capable) {
        String org = s.org().id().toString();
        assertThat(as("nekkora_ingestion", s.org().id(),
                "UPDATE agents SET last_heartbeat_at = clock_timestamp() WHERE organization_id = '" + org + "' AND agent_id = '" + m.agent() + "'",
                "INSERT INTO agent_presence (agent_id, organization_id, contract_version, capabilities, reported_at) VALUES ('" + m.agent() + "', '" + org
                        + "', '1.0', '" + (capable ? "{REMOTE_RUN_1_0,PY_INVENTORY_1_0}" : "{PY_INVENTORY_1_0}") + "'::text[], clock_timestamp()) "
                        + "ON CONFLICT (agent_id) DO UPDATE SET capabilities = EXCLUDED.capabilities, reported_at = EXCLUDED.reported_at")).isEqualTo("OK");
    }

    /** Autorizacao de execucao da versao para o Worker, pela API real (com step-up). Devolve o id. */
    private String authorize(Setup s, Machine m) {
        BrowserClient.Response a = s.admin().post(s.o() + "/workspaces/" + s.workspace() + "/local-run-authorizations", "{\"workerId\":\"" + m.worker()
                + "\",\"automationVersionId\":\"" + s.version() + "\",\"validUntil\":\"" + OffsetDateTime.now(ZoneOffset.UTC).plusDays(30).withNano(0) + "\"}");
        assertThat(a.status()).as(a.body()).isEqualTo(201);
        return a.field("authorizationId");
    }

    private void declare(Setup s, String text) {
        Integer current = Integer.valueOf(valueUnchecked("SELECT coalesce(max(revision_number), 0) FROM automation_runtime_requirements WHERE automation_id = '"
                + s.automation() + "'"));
        String body = text == null ? "{\"source\":\"PACKAGE\"" : "{\"source\":\"TEXT\",\"text\":" + JSON.writeValueAsString(text);
        assertThat(put(s.admin(), s.o() + "/automations/" + s.automation() + "/runtime-requirements", body + (current == 0 ? "" : ",\"expectedRevision\":" + current) + "}")
                .status()).isEqualTo(201);
    }

    private static String body(Setup s, Machine m, UUID key) {
        return "{\"automationVersionId\":\"" + s.version() + "\",\"agentId\":\"" + m.agent() + "\",\"requestKey\":\"" + key + "\"}";
    }

    private BrowserClient.Response request(Setup s, Machine m) {
        return s.admin().post(s.requests(), body(s, m, UUID.randomUUID()));
    }

    private static void refused(BrowserClient.Response r, String reason) {
        assertThat(r.status()).as(r.body()).isEqualTo(409);
        assertThat(r.body()).isEqualTo("{\"error\":\"conflict\",\"reason\":\"" + reason + "\"}");
    }

    private List<String> missing(Setup s, Machine m) {
        BrowserClient.Response r = s.admin().get(s.requests() + "/candidates?automationVersion=" + s.version());
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        for (JsonNode n : JSON.readTree(r.body()).get("items")) {
            if (n.get("agentId").asString().equals(m.agent().toString())) {
                List<String> out = new ArrayList<>();
                n.get("missing").forEach(x -> out.add(x.asString()));
                assertThat(n.get("requestable").asBoolean()).isEqualTo(out.isEmpty());
                return out;
            }
        }
        return null;
    }

    /** A maquina avanca a tentativa (papel da ingestao), como o gateway faria. */
    private static void machineMoves(Setup s, String attempt, int... states) {
        for (int state : states) {
            String token = state == 2 ? ", token_hash = repeat('a', 64), epoch = epoch + 1" : state == 11 ? ", refusal_reason = 'BUSY'" : "";
            assertThat(as("nekkora_ingestion", s.org().id(), "UPDATE remote_attempts SET state = " + state + token + " WHERE attempt_id = '" + attempt + "'"))
                    .as("estado " + state).isEqualTo("OK");
        }
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

    /** Como o papel, SEM contexto de organizacao. */
    private static String valueAs(String role, String sql) throws Exception {
        try (Connection c = DB.connectAs(role); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
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

    private static void grantWorkspaceRole(Org o, TestUser u, UUID ws, String role) throws Exception {
        String m = value("SELECT membership_id FROM organization_memberships WHERE user_id = '" + u.id() + "'");
        DB.asSuperuser("INSERT INTO workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status, assigned_by_membership_id) "
                + "SELECT '" + o.id() + "', '" + ws + "', '" + m + "', role_id, 2, 1, '" + o.adminMembership() + "' FROM roles WHERE code = '" + role + "'");
    }

    @Test
    void cadaExigenciaQueFaltaTemOSeuMotivoENadaEGravadoAteTodasValerem() throws Exception {
        Setup s = setup();
        Machine m = machine(s, "Fiscal", true);
        // nada declarado, sem autorizacao da versao, aplicativo incompativel (nunca bateu)
        assertThat(missing(s, m)).containsExactly("REQUIREMENTS_NOT_DECLARED", "AUTHORIZATION_MISSING", "MACHINE_INCOMPATIBLE");
        refused(request(s, m), "remote_requirements_not_declared");
        declare(s, null);
        refused(request(s, m), "remote_machine_incompatible");
        heartbeat(s, m, false);
        refused(request(s, m), "remote_machine_incompatible");
        heartbeat(s, m, true);
        assertThat(missing(s, m)).containsExactly("AUTHORIZATION_MISSING");
        refused(request(s, m), "remote_authorization_missing");
        // habilitar a maquina nao criou autorizacao nenhuma, e a solicitacao tambem nao cria
        assertThat(value("SELECT count(*) FROM local_run_authorizations WHERE organization_id = '" + s.org().id() + "'")).isEqualTo("0");
        String authorization = authorize(s, m);
        assertThat(missing(s, m)).isEmpty();
        // pausada
        assertThat(as("nekkora_ingestion", s.org().id(), "UPDATE agent_remote_enablements SET receiving = 2 WHERE agent_id = '" + m.agent() + "'")).isEqualTo("OK");
        assertThat(missing(s, m)).containsExactly("MACHINE_PAUSED");
        refused(request(s, m), "remote_machine_paused");
        assertThat(as("nekkora_ingestion", s.org().id(), "UPDATE agent_remote_enablements SET receiving = 1 WHERE agent_id = '" + m.agent() + "'")).isEqualTo("OK");
        // dependencias em conflito com o pacote nao existem aqui (o pacote nao declara nenhuma); declaracao com acrescimo e congelada
        declare(s, "idna==3.7\n");
        assertThat(value("SELECT count(*) FROM remote_executions WHERE organization_id = '" + s.org().id() + "'")).as("nenhuma recusa gravou nada").isEqualTo("0");

        // todas valendo: aceita, com a primeira tentativa na fila e a autorizacao DESTE Worker
        UUID key = UUID.randomUUID();
        BrowserClient.Response ok = s.admin().post(s.requests(), body(s, m, key));
        assertThat(ok.status()).as(ok.body()).isEqualTo(201);
        assertThat(ok.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        JsonNode x = JSON.readTree(ok.body());
        assertThat(x.get("state").asString()).isEqualTo("QUEUED");
        assertThat(x.get("requirementsRevision").asInt()).isEqualTo(2);
        assertThat(x.get("dependencies").toString()).isEqualTo("[\"idna==3.7\"]");
        assertThat(x.get("attempts").size()).isEqualTo(1);
        JsonNode a = x.get("attempts").get(0);
        assertThat(a.get("agentId").asString()).isEqualTo(m.agent().toString());
        assertThat(a.get("communication").asString()).isEqualTo("NONE");
        String execution = x.get("remoteExecutionId").asString();
        assertThat(value("SELECT authorization_id || '|' || created_by_membership_id FROM remote_attempts WHERE remote_execution_id = '" + execution + "'"))
                .isEqualTo(authorization + "|" + s.org().adminMembership());
        assertThat(value("SELECT package_fingerprint || '|' || requested_by_membership_id FROM remote_executions WHERE remote_execution_id = '" + execution + "'"))
                .isEqualTo(FINGERPRINT + "|" + s.org().adminMembership());
        // resposta perdida: a mesma chave devolve a mesma solicitacao; com outro conteudo, 409
        BrowserClient.Response same = s.admin().post(s.requests(), body(s, m, key));
        assertThat(same.status()).isEqualTo(200);
        assertThat(JSON.readTree(same.body()).get("remoteExecutionId").asString()).isEqualTo(execution);
        Machine other = machine(s, "Outra", true);
        assertThat(s.admin().post(s.requests(), body(s, other, key)).status()).isEqualTo(409);
        // a maquina esta ocupada: outra solicitacao para ela e recusada (a reserva e atomica)
        assertThat(missing(s, m)).containsExactly("MACHINE_BUSY");
        refused(request(s, m), "remote_machine_busy");
        assertThat(value("SELECT count(*) FROM remote_executions WHERE organization_id = '" + s.org().id() + "'")).isEqualTo("1");
        // a declaracao muda depois: a solicitacao ja feita continua com o retrato dela
        declare(s, null);
        assertThat(JSON.readTree(s.admin().get(s.o() + "/remote-executions/" + execution).body()).get("dependencies").toString()).isEqualTo("[\"idna==3.7\"]");
        // autorizacao revogada: a mesma autorizacao deixa de servir para NOVAS solicitacoes, com o motivo proprio
        machineMoves(s, a.get("attemptId").asString(), 2, 3, 9);
        assertThat(s.admin().post(s.o() + "/workspaces/" + s.workspace() + "/local-run-authorizations/" + authorization + "/revoke", null).status()).isEqualTo(204);
        assertThat(missing(s, m)).containsExactly("AUTHORIZATION_REVOKED");
        refused(request(s, m), "remote_authorization_revoked");
        // nova autorizacao: volta a valer para novas solicitacoes, enquanto nao vencer nem for revogada
        authorize(s, m);
        assertThat(request(s, m).status()).isEqualTo(201);
    }

    @Test
    void pacoteNoFormatoStudioERecusadoAntesDeCriarASolicitacaoMesmoComTodoORestoValendo() throws Exception {
        Setup s = setup(STUDIO);
        declare(s, null);
        Machine m = machine(s, "Studio", true);
        heartbeat(s, m, true);
        authorize(s, m);
        // tudo o mais vale (maquina habilitada, compativel, livre, autorizacao vigente, Python escolhido, dependencias declaradas):
        // o unico impedimento e o tipo do pacote, e ele vale para qualquer maquina
        assertThat(missing(s, m)).containsExactly("PACKAGE_NOT_SUPPORTED");
        refused(request(s, m), "remote_package_not_supported");
        assertThat(value("SELECT count(*) FROM remote_executions WHERE organization_id = '" + s.org().id() + "'")).isEqualTo("0");
        assertThat(value("SELECT count(*) FROM remote_attempts WHERE organization_id = '" + s.org().id() + "'")).isEqualTo("0");
        // a recusa vem antes das outras exigencias: sem autorizacao e sem Python escolhido, o motivo continua sendo o pacote
        Machine bare = machine(s, "Studio-2", true, false);
        heartbeat(s, bare, true);
        assertThat(missing(s, bare)).startsWith("PACKAGE_NOT_SUPPORTED");
        refused(request(s, bare), "remote_package_not_supported");
        // uma solicitacao que ja existia com pacote Studio (anterior a esta regra) nao ganha nova tentativa em outra maquina
        Setup e = setup();
        declare(e, null);
        Machine a = machine(e, "Antiga-A", true);
        Machine b = machine(e, "Antiga-B", true);
        heartbeat(e, a, true);
        heartbeat(e, b, true);
        authorize(e, a);
        authorize(e, b);
        BrowserClient.Response created = request(e, a);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String execution = created.field("remoteExecutionId");
        String attempt = value("SELECT attempt_id FROM remote_attempts WHERE remote_execution_id = '" + execution + "'");
        machineMoves(e, attempt, 2, 11);
        DB.asSuperuser("ALTER TABLE remote_executions DISABLE TRIGGER USER",
                "UPDATE remote_executions SET package_fingerprint_rule = '" + STUDIO + "' WHERE remote_execution_id = '" + execution + "'",
                "ALTER TABLE remote_executions ENABLE TRIGGER USER");
        refused(e.admin().post(e.o() + "/remote-executions/" + execution + "/attempts", "{\"agentId\":\"" + b.agent() + "\"}"), "remote_package_not_supported");
        assertThat(value("SELECT count(*) FROM remote_attempts WHERE remote_execution_id = '" + execution + "'")).isEqualTo("1");
    }

    @Test
    void versaoSemPacoteRegistradoNaoGanhaSolicitacaoNemTentativaEOPacoteFixadoNaoEEscolhidoPorQuemInsere() throws Exception {
        Setup s = setup();
        declare(s, null);
        Machine a = machine(s, "Pacote-A", true);
        Machine b = machine(s, "Pacote-B", true);
        heartbeat(s, a, true);
        heartbeat(s, b, true);
        String authorization = authorize(s, a);
        authorize(s, b);
        // com o pacote registrado: a tentativa nasce com o pacote FIXADO pelo banco (maquina, sha256 e tamanho lidos das fontes)
        BrowserClient.Response created = request(s, a);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String execution = created.field("remoteExecutionId");
        String attempt = value("SELECT attempt_id FROM remote_attempts WHERE remote_execution_id = '" + execution + "'");
        assertThat(value("SELECT agent_id || '|' || sha256 || '|' || size_bytes FROM remote_attempt_packages WHERE attempt_id = '" + attempt + "'"))
                .isEqualTo(a.agent() + "|" + PACKAGE_SHA + "|1234");
        // as pessoas (nekkora_platform) nao escolhem o que a maquina baixa: nem informando valores, nem mudando, nem apagando
        String org = s.org().id().toString();
        assertThat(as("nekkora_platform", s.org().id(), "INSERT INTO remote_attempt_packages (attempt_id, organization_id, agent_id, sha256, size_bytes) VALUES ('"
                + attempt + "', '" + org + "', '" + b.agent() + "', '" + "9".repeat(64) + "', 1)")).as("valores informados").isEqualTo("42501");
        assertThat(as("nekkora_platform", s.org().id(), "INSERT INTO remote_attempt_packages (attempt_id, organization_id) VALUES ('" + attempt + "', '" + org
                + "')")).as("segunda linha da mesma tentativa").isIn("23505", "23514");
        assertThat(as("nekkora_platform", s.org().id(), "UPDATE remote_attempt_packages SET sha256 = '" + "9".repeat(64) + "'")).isEqualTo("42501");
        assertThat(as("nekkora_platform", s.org().id(), "DELETE FROM remote_attempt_packages")).isEqualTo("42501");
        // o registro do pacote some (so a fixture consegue): a versao deixa de ser solicitavel, em qualquer maquina, com o motivo proprio
        machineMoves(s, attempt, 2, 11);
        DB.asSuperuser("ALTER TABLE automation_artifacts DISABLE TRIGGER USER", "DELETE FROM automation_artifacts WHERE automation_version_id = '" + s.version() + "'",
                "ALTER TABLE automation_artifacts ENABLE TRIGGER USER");
        assertThat(missing(s, a)).containsExactly("PACKAGE_NOT_REGISTERED");
        assertThat(missing(s, b)).containsExactly("PACKAGE_NOT_REGISTERED");
        refused(request(s, b), "remote_package_not_registered");
        refused(s.admin().post(s.o() + "/remote-executions/" + execution + "/attempts", "{\"agentId\":\"" + b.agent() + "\"}"), "remote_package_not_registered");
        assertThat(value("SELECT count(*) FROM remote_executions WHERE organization_id = '" + org + "'")).isEqualTo("1");
        assertThat(value("SELECT count(*) FROM remote_attempts WHERE organization_id = '" + org + "'")).isEqualTo("1");
        // e o banco reconfere por conta propria: sem pacote registrado, a tentativa nao nasce, mesmo com tudo o mais valendo
        assertThat(as("nekkora_platform", s.org().id(), "INSERT INTO remote_attempts (organization_id, remote_execution_id, attempt_number, agent_id, worker_id, "
                + "authorization_id, created_by_membership_id, offer_valid_until) VALUES ('" + org + "', '" + execution + "', 2, '" + a.agent() + "', '" + a.worker()
                + "', '" + authorization + "', '" + s.org().adminMembership() + "', clock_timestamp() + interval '15 minutes')")).isEqualTo("23514");
        assertThat(value("SELECT count(*) FROM remote_attempts WHERE organization_id = '" + org + "'")).isEqualTo("1");
    }

    @Test
    void oPythonEEscolhidoPorMaquinaEAutomacaoESemEleNadaESolicitado() throws Exception {
        Setup s = setup();
        declare(s, null);
        Machine m = machine(s, "Python", true, false);
        heartbeat(s, m, true);
        authorize(s, m);
        // tudo vale, menos a escolha: a tela sabe o que falta e a solicitacao e recusada com o motivo; nada e gravado
        assertThat(missing(s, m)).containsExactly("PYTHON_NOT_SELECTED");
        refused(request(s, m), "remote_python_not_selected");
        assertThat(value("SELECT count(*) FROM remote_executions WHERE organization_id = '" + s.org().id() + "'")).isEqualTo("0");
        // so um ambiente do inventario ATUAL da maquina pode ser escolhido
        refused(selectPython(s, m, ENV_A), "remote_python_not_in_inventory");
        assertThat(selectPython(s, m, "C:\\\\Python312\\\\python.exe").status()).as("um caminho nunca e aceito").isEqualTo(400);
        inventory(s, m, ENV_A, "3.12.10");
        inventory(s, m, ENV_B, "3.13.1");
        BrowserClient.Response chosen = selectPython(s, m, ENV_A);
        assertThat(chosen.status()).as(chosen.body()).isEqualTo(200);
        assertThat(chosen.field("pythonVersion")).as("a versao vem do inventario, nao de quem escolhe").isEqualTo("3.12.10");
        assertThat(JSON.readTree(chosen.body()).get("inInventory").asBoolean()).isTrue();
        assertThat(value("SELECT count(*) FROM automation_admin_events WHERE automation_id = '" + s.automation() + "' AND act = 11")).isEqualTo("1");
        assertThat(missing(s, m)).isEmpty();
        // a solicitacao guarda na tentativa o retrato da escolha; trocar depois nao muda a tentativa ja criada
        BrowserClient.Response ok = request(s, m);
        assertThat(ok.status()).as(ok.body()).isEqualTo(201);
        String attempt = JSON.readTree(ok.body()).get("attempts").get(0).get("attemptId").asString();
        assertThat(value("SELECT python_environment_id FROM remote_attempts WHERE attempt_id = '" + attempt + "'")).isEqualTo(ENV_A);
        assertThat(selectPython(s, m, ENV_B).field("pythonVersion")).isEqualTo("3.13.1");
        assertThat(value("SELECT count(*) || '|' || max(environment_id) FROM automation_python_selections WHERE agent_id = '" + m.agent() + "'")).isEqualTo("1|" + ENV_B);
        assertThat(value("SELECT python_environment_id FROM remote_attempts WHERE attempt_id = '" + attempt + "'")).isEqualTo(ENV_A);
        assertThat(as("nekkora_ingestion", s.org().id(), "UPDATE remote_attempts SET python_environment_id = '" + ENV_B + "' WHERE attempt_id = '" + attempt + "'"))
                .as("o retrato da tentativa nao muda").isEqualTo("42501");
        machineMoves(s, attempt, 2, 3, 9);
        // a maquina publica um inventario sem o ambiente escolhido: a escolha deixa de servir, e nenhum outro e escolhido sozinho
        assertThat(as("nekkora_ingestion", s.org().id(), "DELETE FROM agent_python_environments WHERE agent_id = '" + m.agent() + "' AND environment_id = '" + ENV_B
                + "'")).isEqualTo("OK");
        assertThat(missing(s, m)).containsExactly("PYTHON_NOT_IN_INVENTORY");
        refused(request(s, m), "remote_python_not_in_inventory");
        // a escolha de uma maquina nao vale para outra, nem para maquina de Worker fora do ambiente
        Machine other = machine(s, "Python outra", true, false);
        heartbeat(s, other, true);
        authorize(s, other);
        assertThat(missing(s, other)).containsExactly("PYTHON_NOT_SELECTED");
        Machine outside = machine(s, "Python fora", false, false);
        assertThat(selectPython(s, outside, ENV_A).status()).isEqualTo(404);
        // quem so executa nao escolhe (automation.manage); outra organizacao nao enxerga a automacao
        TestUser operator = internalUser(s.org(), true, 2);
        grantWorkspaceRole(s.org(), operator, s.workspace(), "OPERATOR");
        BrowserClient op = browser();
        firstLogin(op, s.org().id(), operator);
        assertThat(put(op, s.o() + "/automations/" + s.automation() + "/remote-machines/" + m.agent() + "/python-selection",
                "{\"environmentId\":\"" + ENV_A + "\"}").status()).isIn(403, 404);
        // guardas do banco: as pessoas nao inserem escolha fora do inventario; a maquina nao le nem escreve a escolha
        String insert = "INSERT INTO automation_python_selections (automation_id, agent_id, organization_id, workspace_id, environment_id, python_version, "
                + "selected_by_membership_id) VALUES ('" + s.automation() + "', '" + other.agent() + "', '" + s.org().id() + "', '" + s.workspace() + "', '" + ENV_B
                + "', '3.13.1', '" + s.org().adminMembership() + "')";
        assertThat(as("nekkora_platform", s.org().id(), insert)).isEqualTo("23514");
        assertThat(as("nekkora_ingestion", s.org().id(), "SELECT count(*) FROM automation_python_selections")).isEqualTo("42501");
        assertThat(as("nekkora_platform", s.org().id(), "UPDATE automation_python_selections SET environment_id = '" + ENV_A + "' WHERE agent_id = '" + m.agent()
                + "'")).isEqualTo("42501");
    }

    @Test
    void osRegistrosDaTentativaSaoLidosEmPaginasSoPorQuemPodeVerRegistrosEDizemOQueFoiDescartado() throws Exception {
        Setup s = setup();
        declare(s, null);
        Machine m = machine(s, "Registros", true);
        heartbeat(s, m, true);
        authorize(s, m);
        BrowserClient.Response ok = request(s, m);
        assertThat(ok.status()).as(ok.body()).isEqualTo(201);
        String execution = ok.field("remoteExecutionId");
        String attempt = JSON.readTree(ok.body()).get("attempts").get(0).get("attemptId").asString();
        String base = s.o() + "/remote-executions/" + execution + "/attempts/" + attempt + "/logs";
        // sem nada enviado: nenhuma linha, envio nao fechado
        JsonNode empty = JSON.readTree(s.admin().get(base).body());
        assertThat(empty.get("items").size()).isZero();
        assertThat(empty.get("total").asInt()).isZero();
        assertThat(empty.get("nextAfter").isNull()).isTrue();
        assertThat(empty.get("closure").isNull()).isTrue();
        assertThat(empty.get("retentionLoss").isNull()).as("nenhum aviso de remocao pela maquina").isTrue();
        // a maquina executa e envia (como o gateway, com o papel da ingestao): 1.200 linhas, os tres canais
        machineMoves(s, attempt, 2, 3, 4, 5, 6);
        assertThat(as("nekkora_ingestion", s.org().id(), "INSERT INTO remote_attempt_log_lines (attempt_id, sequence, organization_id, captured_at, channel, "
                + "truncated, message) SELECT '" + attempt + "', g, '" + s.org().id() + "', clock_timestamp(), 1 + (g % 3), g = 7, 'linha ' || g FROM "
                + "generate_series(1, 1200) g")).isEqualTo("OK");
        // com a execucao em andamento ja se le o que chegou; o envio ainda nao foi fechado
        BrowserClient.Response page = s.admin().get(base);
        assertThat(page.status()).as(page.body()).isEqualTo(200);
        assertThat(page.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        JsonNode p1 = JSON.readTree(page.body());
        assertThat(p1.get("items").size()).isEqualTo(500);
        assertThat(p1.get("total").asInt()).isEqualTo(1200);
        // o que a automacao escreveu e o que o aplicativo da maquina avisou sao contados separados (um terco das linhas e EXECUTOR)
        assertThat(p1.get("automationLines").asInt()).isEqualTo(800);
        assertThat(p1.get("systemLines").asInt()).isEqualTo(400);
        assertThat(p1.get("nextAfter").asInt()).isEqualTo(500);
        assertThat(p1.get("closure").isNull()).isTrue();
        JsonNode first = p1.get("items").get(0);
        assertThat(first.get("sequence").asInt()).isEqualTo(1);
        assertThat(first.get("channel").asString()).isEqualTo("STDERR");
        assertThat(first.get("level").asString()).as("STDERR e canal, nao nivel").isEqualTo("UNKNOWN");
        assertThat(first.get("message").asString()).isEqualTo("linha 1");
        assertThat(p1.get("items").get(1).get("channel").asString()).isEqualTo("EXECUTOR");
        assertThat(p1.get("items").get(2).get("channel").asString()).isEqualTo("STDOUT");
        assertThat(p1.get("items").get(6).get("truncated").asBoolean()).isTrue();
        // paginas seguintes, pela sequencia; a ultima diz que acabou
        JsonNode p2 = JSON.readTree(s.admin().get(base + "?after=500").body());
        assertThat(p2.get("items").get(0).get("sequence").asInt()).isEqualTo(501);
        assertThat(p2.get("nextAfter").asInt()).isEqualTo(1000);
        JsonNode p3 = JSON.readTree(s.admin().get(base + "?after=1000&limit=200").body());
        assertThat(p3.get("items").size()).isEqualTo(200);
        assertThat(p3.get("nextAfter").isNull()).as("chegou ao fim do que ha agora").isTrue();
        assertThat(JSON.readTree(s.admin().get(base + "?after=1190&limit=5").body()).get("nextAfter").asInt()).isEqualTo(1195);
        assertThat(s.admin().get(base + "?limit=501").status()).isEqualTo(400);
        assertThat(s.admin().get(base + "?after=-1").status()).isEqualTo(400);
        // o robo falha; a maquina fecha o envio dizendo quantas linhas descartou por limite
        machineMoves(s, attempt, 8);
        assertThat(as("nekkora_ingestion", s.org().id(), "INSERT INTO remote_attempt_log_closures (attempt_id, organization_id, lines, dropped_lines) VALUES ('"
                + attempt + "', '" + s.org().id() + "', 1200, 73000)")).isEqualTo("OK");
        JsonNode closed = JSON.readTree(s.admin().get(base + "?after=1199").body());
        assertThat(closed.get("closure").get("lines").asInt()).isEqualTo(1200);
        assertThat(closed.get("closure").get("droppedLines").asLong()).as("o que foi descartado por limite e dito").isEqualTo(73000);
        assertThat(closed.get("retentionLoss").isNull()).isTrue();
        // a maquina avisa que removeu, pelo prazo de guarda dela, registros e resultados sem confirmacao: a leitura diz quanto HAVIA
        // nela, ao lado do que a Platform tem - e nada do que ja estava gravado muda
        String results = s.o() + "/remote-executions/" + execution + "/attempts/" + attempt + "/results";
        assertThat(JSON.readTree(s.admin().get(results).body()).get("retentionLoss").isNull()).isTrue();
        String loss = "INSERT INTO remote_attempt_retention_losses (attempt_id, kind, organization_id, local_count) VALUES ('" + attempt + "', %d, '" + s.org().id()
                + "', %d)";
        assertThat(as("nekkora_ingestion", s.org().id(), loss.formatted(1, 1500))).isEqualTo("OK");
        assertThat(as("nekkora_ingestion", s.org().id(), loss.formatted(2, 4))).isEqualTo("OK");
        JsonNode lost = JSON.readTree(s.admin().get(base + "?after=1199").body());
        assertThat(lost.get("retentionLoss").get("localCount").asInt()).isEqualTo(1500);
        assertThat(lost.get("retentionLoss").get("reportedAt").asString()).isNotBlank();
        assertThat(lost.get("total").asInt()).as("o que a Platform tem nao muda").isEqualTo(1200);
        assertThat(lost.get("closure").get("lines").asInt()).isEqualTo(1200);
        JsonNode lostResults = JSON.readTree(s.admin().get(results).body());
        assertThat(lostResults.get("retentionLoss").get("localCount").asInt()).isEqualTo(4);
        assertThat(lostResults.get("items").size()).isZero();
        // as pessoas nao escrevem o aviso; ninguem o muda nem apaga; um por tentativa e por tipo
        assertThat(as("nekkora_platform", s.org().id(), loss.formatted(1, 1))).isEqualTo("42501");
        assertThat(as("nekkora_ingestion", s.org().id(), loss.formatted(1, 9))).isEqualTo("23505");
        assertThat(as("nekkora_ingestion", s.org().id(), "UPDATE remote_attempt_retention_losses SET local_count = 0")).isEqualTo("42501");
        assertThat(as("nekkora_ingestion", s.org().id(), "DELETE FROM remote_attempt_retention_losses")).isEqualTo("42501");
        // nem os registros, nem o descarte, nem o aviso mudaram o resultado da tentativa
        assertThat(JSON.readTree(s.admin().get(s.o() + "/remote-executions/" + execution).body()).get("state").asString()).isEqualTo("FAILED");
        // tentativa que nao existe: 404
        assertThat(s.admin().get(s.o() + "/remote-executions/" + execution + "/attempts/" + UUID.randomUUID() + "/logs").status()).isEqualTo(404);
        // quem nao tem permissao no ambiente nao le; a Platform nao escreve nem apaga linha
        TestUser outsider = internalUser(s.org(), true, 2);
        BrowserClient ob = browser();
        firstLogin(ob, s.org().id(), outsider);
        assertThat(ob.get(base).status()).isIn(403, 404);
        assertThat(as("nekkora_platform", s.org().id(), "DELETE FROM remote_attempt_log_lines WHERE attempt_id = '" + attempt + "'")).isEqualTo("42501");
        assertThat(as("nekkora_platform", s.org().id(), "UPDATE remote_attempt_log_lines SET message = '' WHERE attempt_id = '" + attempt + "'")).isEqualTo("42501");
    }

    @Test
    void osResultadosSaoListadosEBaixadosSoPorQuemPodeVerRegistrosEODownloadFicaNaTrilha() throws Exception {
        Setup s = setup();
        declare(s, null);
        Machine m = machine(s, "Resultados", true);
        heartbeat(s, m, true);
        authorize(s, m);
        BrowserClient.Response ok = request(s, m);
        assertThat(ok.status()).as(ok.body()).isEqualTo(201);
        String execution = ok.field("remoteExecutionId");
        String attempt = JSON.readTree(ok.body()).get("attempts").get(0).get("attemptId").asString();
        String base = s.o() + "/remote-executions/" + execution + "/attempts/" + attempt + "/results";
        // sem nada enviado: lista vazia, envio nao fechado
        JsonNode empty = JSON.readTree(s.admin().get(base).body());
        assertThat(empty.get("items").size()).isZero();
        assertThat(empty.get("closure").isNull()).isTrue();
        // a maquina executa e envia (como o gateway: bytes no armazenamento, metadado com o papel da ingestao)
        machineMoves(s, attempt, 2, 3, 4, 5, 6);
        byte[] bytes = "resultado;ok\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        com.nekkora.core.artifact.ResultStore store = com.nekkora.core.artifact.ResultStore.at(System.getProperty("java.io.tmpdir") + "/nekkora-platform-it-resultados");
        store.put(s.org().id(), sha, bytes);
        String insert = "INSERT INTO remote_attempt_results (attempt_id, result_number, organization_id, file_name, size_bytes, sha256) VALUES ('" + attempt
                + "', %d, '" + s.org().id() + "', '%s', %d, '%s')";
        assertThat(as("nekkora_ingestion", s.org().id(), insert.formatted(1, "dados/relatório final.csv", bytes.length, sha),
                insert.formatted(2, "sumido.txt", 3, "b".repeat(64)))).isEqualTo("OK");
        machineMoves(s, attempt, 7);
        assertThat(as("nekkora_ingestion", s.org().id(), "INSERT INTO remote_attempt_result_closures (attempt_id, organization_id, sent, skipped_by_limit) VALUES ('"
                + attempt + "', '" + s.org().id() + "', 2, 5)")).isEqualTo("OK");
        BrowserClient.Response list = s.admin().get(base);
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        assertThat(list.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        JsonNode r = JSON.readTree(list.body());
        assertThat(r.get("items").size()).isEqualTo(2);
        assertThat(r.get("items").get(0).get("fileName").asString()).isEqualTo("dados/relatório final.csv");
        assertThat(r.get("items").get(0).get("sizeBytes").asInt()).isEqualTo(bytes.length);
        assertThat(r.get("items").get(0).get("available").asBoolean()).isTrue();
        assertThat(r.get("closure").get("sent").asInt()).isEqualTo(2);
        assertThat(r.get("closure").get("skippedByLimit").asInt()).as("o que ficou de fora por limite e dito").isEqualTo(5);
        // o resultado nao mudou o estado da tentativa
        assertThat(JSON.readTree(s.admin().get(s.o() + "/remote-executions/" + execution).body()).get("state").asString()).isEqualTo("SUCCEEDED");
        // download: os bytes conferidos, sempre como anexo, sem cache; fica na trilha da automacao
        BrowserClient.Response file = s.admin().get(base + "/1/content");
        assertThat(file.status()).as(file.body()).isEqualTo(200);
        assertThat(file.body()).isEqualTo("resultado;ok\n");
        assertThat(file.headers().firstValue("Content-Disposition").orElse("")).startsWith("attachment").doesNotContain("dados/");
        assertThat(file.headers().firstValue("Content-Type").orElse("")).startsWith("application/octet-stream");
        assertThat(file.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        assertThat(file.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(file.headers().firstValue("Nekkora-Result-Sha256").orElse("")).isEqualTo(sha);
        assertThat(value("SELECT count(*) FROM automation_admin_events WHERE automation_id = '" + s.automation() + "' AND act = 12")).isEqualTo("1");
        // bytes que nao estao no armazenamento: 409 com o motivo; arquivo ou tentativa que nao existem: 404
        refused(s.admin().get(base + "/2/content"), "remote_result_unavailable");
        assertThat(s.admin().get(base + "/9/content").status()).isEqualTo(404);
        assertThat(s.admin().get(s.o() + "/remote-executions/" + execution + "/attempts/" + UUID.randomUUID() + "/results").status()).isEqualTo(404);
        // quem nao tem permissao no ambiente nao lista nem baixa; outra organizacao nao enxerga
        TestUser outsider = internalUser(s.org(), true, 2);
        BrowserClient ob = browser();
        firstLogin(ob, s.org().id(), outsider);
        assertThat(ob.get(base).status()).isIn(403, 404);
        assertThat(ob.get(base + "/1/content").status()).isIn(403, 404);
        assertThat(value("SELECT count(*) FROM automation_admin_events WHERE automation_id = '" + s.automation() + "' AND act = 12")).as("recusa nao vira download")
                .isEqualTo("1");
    }

    /**
     * 060 (decisoes de 16/11): terminar o processo PRINCIPAL nao libera a maquina enquanto houver processo da tentativa ativo,
     * tambem numa execucao NORMAL. O estado e o codigo de saida ficam registrados; a ocupacao e a repeticao esperam. Se a maquina
     * deixar de saber, a ocupacao passa a incerta. A declaracao de um ORG_ADMIN libera a repeticao, nunca a maquina.
     */
    @Test
    void terminarOProcessoPrincipalNaoLiberaAMaquinaNemARepeticaoEnquantoHouverProcessoDaTentativa() throws Exception {
        Setup s = setup();
        declare(s, null);
        Machine a = machine(s, "Finalizando", true);
        Machine b = machine(s, "OutraMaquina", true);
        heartbeat(s, a, true);
        heartbeat(s, b, true);
        authorize(s, a);
        authorize(s, b);
        JsonNode x = JSON.readTree(request(s, a).body());
        String execution = x.get("remoteExecutionId").asString();
        String first = x.get("attempts").get(0).get("attemptId").asString();
        String retry = s.o() + "/remote-executions/" + execution + "/attempts";
        String report = "INSERT INTO remote_attempt_process_reports (attempt_id, kind, organization_id, agent_id) SELECT attempt_id, %d, organization_id, agent_id "
                + "FROM remote_attempts WHERE attempt_id = '%s'";
        machineMoves(s, first, 2, 3, 4, 5, 6);
        // em execucao: nenhum relato de processos e aceito
        assertThat(as("nekkora_ingestion", s.org().id(), report.formatted(4, first))).isEqualTo("23514");
        // o processo principal falhou com codigo 3, e ha processos da automacao ainda ativos (papel da ingestao, como o gateway)
        assertThat(as("nekkora_ingestion", s.org().id(), "UPDATE remote_attempts SET state = 8, exit_code = 3 WHERE attempt_id = '" + first + "'",
                report.formatted(4, first))).isEqualTo("OK");
        JsonNode finishing = attemptOf(s, execution, 0);
        assertThat(finishing.get("state").asString()).as("o desfecho do processo principal fica registrado").isEqualTo("FAILED");
        assertThat(finishing.get("exitCode").asInt()).isEqualTo(3);
        assertThat(finishing.get("processes").asString()).isEqualTo("ACTIVE");
        assertThat(finishing.get("processesAt").asString()).isNotBlank();
        assertThat(finishing.get("reconciliations").size()).isZero();
        assertThat(missing(s, a)).as("finalizando: a maquina continua ocupada").contains("MACHINE_FINISHING").doesNotContain("MACHINE_BUSY", "MACHINE_UNCONFIRMED");
        stepUp(s);
        refused(request(s, a), "remote_machine_finishing");
        refused(s.admin().post(retry, "{\"agentId\":\"" + b.agent() + "\"}"), "remote_previous_processes_pending");
        // tempo e novo contato nao liberam
        heartbeat(s, a, true);
        assertThat(missing(s, a)).contains("MACHINE_FINISHING");
        // "ainda executa" e fato de tentativa NAO CONFIRMADA: aqui nao cabe; e so a maquina informa
        assertThat(as("nekkora_ingestion", s.org().id(), report.formatted(3, first))).isEqualTo("23514");
        assertThat(as("nekkora_platform", s.org().id(), report.formatted(4, first))).isIn("42501", "23505");
        // 059: com processos ativos a execucao esta ATIVA - o teto por falta de progresso nao se aplica, por mais antiga que seja
        String ceiling = "SELECT coalesce(how::text, 'nulo') FROM remote_attempt_delivery_ended_at('" + s.org().id() + "', '" + first + "', 1::smallint)";
        assertThat(value(ceiling)).isEqualTo("nulo");
        // a maquina perdeu a capacidade de identificar os processos: a ocupacao passa a INCERTA
        assertThat(as("nekkora_ingestion", s.org().id(), report.formatted(5, first))).isEqualTo("OK");
        assertThat(attemptOf(s, execution, 0).get("processes").asString()).isEqualTo("UNCERTAIN");
        assertThat(missing(s, a)).contains("MACHINE_UNCONFIRMED").doesNotContain("MACHINE_FINISHING", "MACHINE_BUSY");
        refused(request(s, a), "remote_machine_unconfirmed");
        refused(s.admin().post(retry, "{\"agentId\":\"" + b.agent() + "\"}"), "remote_previous_processes_pending");
        assertThat(value(ceiling)).isEqualTo("nulo");
        // a declaracao de um ORG_ADMIN libera a repeticao em OUTRA maquina; o desfecho nao muda; a maquina antiga continua indisponivel
        String declare = s.o() + "/remote-executions/" + execution + "/attempts/" + first + "/reconciliation";
        assertThat(s.admin().post(declare, "{\"reason\":\"PROCESS_ENDED_BY_ADMINISTRATOR\",\"verification\":\"Processos encerrados no computador por quem o administra.\","
                + "\"acknowledgePartialEffects\":true}").status()).isEqualTo(201);
        JsonNode declared = attemptOf(s, execution, 0);
        assertThat(declared.get("state").asString()).isEqualTo("FAILED");
        assertThat(declared.get("exitCode").asInt()).isEqualTo(3);
        assertThat(declared.get("processes").asString()).as("a declaracao nao muda o que a maquina disse").isEqualTo("UNCERTAIN");
        assertThat(missing(s, a)).as("a declaracao nao libera a maquina").contains("MACHINE_UNCONFIRMED");
        refused(request(s, a), "remote_machine_unconfirmed");
        assertThat(value(ceiling)).as("declarada, a execucao deixa de ser incerta para o teto").isEqualTo("4");
        BrowserClient.Response again = s.admin().post(retry, "{\"agentId\":\"" + b.agent() + "\"}");
        assertThat(again.status()).as(again.body()).isEqualTo(201);
        machineMoves(s, JSON.readTree(again.body()).get("attempts").get(1).get("attemptId").asString(), 2, 3, 11);
        // a propria maquina confirma que nao ha mais processo (o grupo esvaziou, ou a sessao do Windows acabou): so isso a libera
        assertThat(as("nekkora_ingestion", s.org().id(), report.formatted(1, first))).isEqualTo("OK");
        JsonNode freed = attemptOf(s, execution, 0);
        assertThat(freed.get("processes").isNull()).isTrue();
        List<String> kinds = new ArrayList<>();
        freed.get("reconciliations").forEach(r -> kinds.add(r.get("kind").asString()));
        assertThat(kinds).containsExactly("MANUAL", "MACHINE");
        assertThat(missing(s, a)).doesNotContain("MACHINE_UNCONFIRMED", "MACHINE_FINISHING", "MACHINE_BUSY");
        // depois de "ausente" a maquina nao volta atras
        assertThat(as("nekkora_ingestion", s.org().id(), "INSERT INTO remote_attempt_process_reports (attempt_id, kind, organization_id, agent_id) VALUES ('"
                + first + "', 4, '" + s.org().id() + "', '" + a.agent() + "')")).isIn("23514", "23505");
        // a PRESENCA da maquina traz dois sinais genericos - ocupada e pouco espaco - e so eles: a lista passa a mostra-los, sem
        // nenhuma tentativa envolvida (e o caso da execucao iniciada na propria maquina, de que a Platform nao recebe nada)
        String signals = "UPDATE agent_presence SET remote_busy = %s, disk_pressure = %s WHERE agent_id = '" + a.agent() + "'";
        String attemptsBefore = value("SELECT count(*) FROM remote_attempts WHERE agent_id = '" + a.agent() + "'");
        assertThat(as("nekkora_ingestion", s.org().id(), signals.formatted("true", "false"))).isEqualTo("OK");
        assertThat(missing(s, a)).containsExactly("MACHINE_OCCUPIED");
        assertThat(as("nekkora_ingestion", s.org().id(), signals.formatted("true", "true"))).isEqualTo("OK");
        assertThat(missing(s, a)).containsExactly("MACHINE_OCCUPIED", "MACHINE_DISK_PRESSURE");
        assertThat(as("nekkora_ingestion", s.org().id(), signals.formatted("false", "false"))).isEqualTo("OK");
        assertThat(missing(s, a)).isEmpty();
        assertThat(value("SELECT count(*) FROM remote_attempts WHERE agent_id = '" + a.agent() + "'")).as("os sinais nao criam tentativa nenhuma")
                .isEqualTo(attemptsBefore);
        // uma execucao normal de um aplicativo que nao diz nada dos processos (o antigo) continua liberando a maquina como antes
        JsonNode y = JSON.readTree(request(s, a).body());
        String legacy = y.get("attempts").get(0).get("attemptId").asString();
        machineMoves(s, legacy, 2, 3, 4, 5, 6, 7);
        assertThat(attemptOf(s, y.get("remoteExecutionId").asString(), 0).get("processes").isNull()).isTrue();
        assertThat(missing(s, a)).doesNotContain("MACHINE_UNCONFIRMED", "MACHINE_FINISHING", "MACHINE_BUSY");
        // e uma tentativa sem processo pendente nao tem o que declarar
        refused(s.admin().post(s.o() + "/remote-executions/" + y.get("remoteExecutionId").asString() + "/attempts/" + legacy + "/reconciliation",
                "{\"reason\":\"OTHER_VERIFIED\",\"verification\":\"Verificacao feita no computador pela equipe.\",\"acknowledgePartialEffects\":true}"),
                "remote_attempt_not_unconfirmed");
    }

    @Test
    void repetirEmOutraMaquinaUsaAAutorizacaoDoWorkerDeDestinoENuncaATransfere() throws Exception {
        Setup s = setup();
        declare(s, null);
        Machine a = machine(s, "Origem", true);
        Machine b = machine(s, "Destino", true);
        heartbeat(s, a, true);
        heartbeat(s, b, true);
        String authA = authorize(s, a);
        JsonNode x = JSON.readTree(request(s, a).body());
        String execution = x.get("remoteExecutionId").asString();
        String first = x.get("attempts").get(0).get("attemptId").asString();
        String retry = s.o() + "/remote-executions/" + execution + "/attempts";
        // a tentativa anterior nao terminou: nada de segunda maquina
        refused(s.admin().post(retry, "{\"agentId\":\"" + b.agent() + "\"}"), "remote_attempt_not_finished");
        machineMoves(s, first, 2, 3, 4);
        refused(s.admin().post(retry, "{\"agentId\":\"" + b.agent() + "\"}"), "remote_attempt_not_finished");
        // comunicacao perdida (prazo vencido) NAO e termino: continua recusado, sem troca automatica
        assertThat(as("nekkora_ingestion", s.org().id(), "UPDATE remote_attempts SET lease_expires_at = clock_timestamp() - interval '10 minutes' WHERE attempt_id = '"
                + first + "'")).isEqualTo("OK");
        JsonNode lost = JSON.readTree(s.admin().get(s.o() + "/remote-executions/" + execution).body()).get("attempts").get(0);
        assertThat(lost.get("communication").asString()).isEqualTo("LOST");
        assertThat(lost.get("state").asString()).as("comunicacao perdida nao muda o estado").isEqualTo("PREPARING");
        refused(s.admin().post(retry, "{\"agentId\":\"" + b.agent() + "\"}"), "remote_attempt_not_finished");
        // a maquina voltou e disse que terminou sem prova do resultado
        machineMoves(s, first, 13);
        // 058: sem prova do resultado E sem confirmacao de que o processo acabou, a repeticao fica bloqueada
        refused(s.admin().post(retry, "{\"agentId\":\"" + b.agent() + "\"}"), "remote_previous_result_unconfirmed");
        // a propria maquina confirma que o processo nao esta mais em execucao (papel da ingestao, como o gateway): reconciliada por ela
        assertThat(as("nekkora_ingestion", s.org().id(), "INSERT INTO remote_attempt_process_reports (attempt_id, kind, organization_id, agent_id) "
                + "SELECT attempt_id, 1, organization_id, agent_id FROM remote_attempts WHERE attempt_id = '" + first + "'")).isEqualTo("OK");
        // o Worker de destino NAO tem autorizacao desta versao: a da origem nao e transferida
        refused(s.admin().post(retry, "{\"agentId\":\"" + b.agent() + "\"}"), "remote_authorization_missing");
        assertThat(value("SELECT count(*) FROM local_run_authorizations WHERE worker_id = '" + b.worker() + "'")).isEqualTo("0");
        assertThat(value("SELECT count(*) FROM remote_attempts WHERE remote_execution_id = '" + execution + "'")).isEqualTo("1");
        String authB = authorize(s, b);
        BrowserClient.Response ok = s.admin().post(retry, "{\"agentId\":\"" + b.agent() + "\"}");
        assertThat(ok.status()).as(ok.body()).isEqualTo(201);
        JsonNode again = JSON.readTree(ok.body());
        assertThat(again.get("remoteExecutionId").asString()).as("a MESMA solicitacao").isEqualTo(execution);
        assertThat(again.get("attempts").size()).isEqualTo(2);
        assertThat(again.get("attempts").get(0).get("state").asString()).isEqualTo("ENDED_UNKNOWN");
        assertThat(again.get("attempts").get(1).get("agentId").asString()).isEqualTo(b.agent().toString());
        assertThat(again.get("state").asString()).isEqualTo("QUEUED");
        assertThat(value("SELECT string_agg(authorization_id::text, ',' ORDER BY attempt_number) FROM remote_attempts WHERE remote_execution_id = '" + execution + "'"))
                .isEqualTo(authA + "," + authB);
        assertThat(authA).isNotEqualTo(authB);
        // a autorizacao da origem continua intacta (nem consumida, nem movida)
        assertThat(value("SELECT worker_id || '|' || (revoked_at IS NULL) FROM local_run_authorizations WHERE authorization_id = '" + authA + "'"))
                .isEqualTo(a.worker() + "|true");
        // maquina de um Worker que nao esta neste ambiente: 404
        Machine elsewhere = machine(s, "ForaDoAmbiente", false);
        heartbeat(s, elsewhere, true);
        String second = again.get("attempts").get(1).get("attemptId").asString();
        machineMoves(s, second, 2, 3, 11);
        assertThat(s.admin().post(retry, "{\"agentId\":\"" + elsewhere.agent() + "\"}").status()).isEqualTo(404);
        assertThat(s.admin().post(retry, "{}").status()).isEqualTo(400);
    }

    @Test
    void cancelarOQueAMaquinaNaoRecebeuEImediatoEOQueJaRecebeuDependeDela() throws Exception {
        Setup s = setup();
        declare(s, null);
        Machine m = machine(s, "Cancelamento", true);
        heartbeat(s, m, true);
        authorize(s, m);
        // na fila: cancelada na hora, e a maquina fica livre
        JsonNode x = JSON.readTree(request(s, m).body());
        String execution = x.get("remoteExecutionId").asString();
        BrowserClient.Response c = s.admin().post(s.o() + "/remote-executions/" + execution + "/cancel", null);
        assertThat(c.status()).as(c.body()).isEqualTo(200);
        JsonNode cancelled = JSON.readTree(c.body());
        assertThat(cancelled.get("state").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancelRequestedAt").isNull()).isFalse();
        assertThat(value("SELECT cancel_requested_by_membership_id FROM remote_executions WHERE remote_execution_id = '" + execution + "'"))
                .isEqualTo(s.org().adminMembership().toString());
        refused(s.admin().post(s.o() + "/remote-executions/" + execution + "/cancel", null), "remote_attempt_already_finished");
        refused(s.admin().post(s.o() + "/remote-executions/" + execution + "/attempts", "{\"agentId\":\"" + m.agent() + "\"}"), "remote_execution_cancel_requested");

        // ja recebida e rodando: o pedido fica gravado, o estado NAO muda ate a maquina dizer
        JsonNode y = JSON.readTree(request(s, m).body());
        String running = y.get("remoteExecutionId").asString();
        String attempt = y.get("attempts").get(0).get("attemptId").asString();
        machineMoves(s, attempt, 2, 3, 4, 5, 6);
        JsonNode asked = JSON.readTree(s.admin().post(s.o() + "/remote-executions/" + running + "/cancel", null).body());
        assertThat(asked.get("state").asString()).as("so a maquina encerra o que ja recebeu").isEqualTo("RUNNING");
        assertThat(asked.get("cancelRequestedAt").isNull()).isFalse();
        assertThat(s.admin().post(s.o() + "/remote-executions/" + running + "/cancel", null).status()).as("repetir o pedido e idempotente").isEqualTo(200);
        machineMoves(s, attempt, 9);
        assertThat(JSON.readTree(s.admin().get(s.o() + "/remote-executions/" + running).body()).get("state").asString()).isEqualTo("CANCELLED");
        // a lista do ambiente traz as duas, a mais recente primeiro
        JsonNode list = JSON.readTree(s.admin().get(s.requests()).body()).get("items");
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(0).get("remoteExecutionId").asString()).isEqualTo(running);
    }

    /** Telas Execucoes e Supervisao: a lista por organizacao mostra a MESMA solicitacao, so dos ambientes visiveis para quem le. */
    @Test
    void listaPorOrganizacaoMostraAMesmaSolicitacaoSoDosAmbientesVisiveis() throws Exception {
        Setup s = setup();
        declare(s, null);
        Machine m = machine(s, "Lista", true);
        heartbeat(s, m, true);
        authorize(s, m);
        BrowserClient.Response created = request(s, m);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String execution = created.field("remoteExecutionId");
        String all = s.o() + "/remote-executions";

        JsonNode items = JSON.readTree(s.admin().get(all).body()).get("items");
        assertThat(items).hasSize(1);
        JsonNode x = items.get(0);
        assertThat(x.get("remoteExecutionId").asString()).as("a mesma solicitacao, nao uma copia").isEqualTo(execution);
        assertThat(x.get("automationName").asString()).isEqualTo("Conciliacao");
        assertThat(x.get("workspaceName").asString()).isNotBlank();
        assertThat(x.get("versionNumber").asInt()).isPositive();
        assertThat(x.get("attempts")).hasSize(1);
        assertThat(x.get("attempts").get(0).get("agentName").asString()).isEqualTo("Maquina Lista");
        assertThat(x.get("attempts").get(0).get("workerId").asString()).isEqualTo(m.worker().toString());
        assertThat(value("SELECT count(*) FROM remote_executions WHERE organization_id = '" + s.org().id() + "'")).as("ler nao cria nada").isEqualTo("1");
        assertThat(value("SELECT count(*) FROM executions WHERE organization_id = '" + s.org().id() + "'")).as("nenhuma execucao do modelo anterior").isEqualTo("0");
        // a leitura unitaria traz os mesmos nomes
        assertThat(JSON.readTree(s.admin().get(all + "/" + execution).body()).get("automationName").asString()).isEqualTo("Conciliacao");

        // filtros: ambiente, automacao e periodo da solicitacao
        assertThat(JSON.readTree(s.admin().get(all + "?workspaceId=" + s.workspace()).body()).get("items")).hasSize(1);
        assertThat(JSON.readTree(s.admin().get(all + "?workspaceId=" + UUID.randomUUID()).body()).get("items")).isEmpty();
        assertThat(JSON.readTree(s.admin().get(all + "?automationId=" + s.automation()).body()).get("items")).hasSize(1);
        assertThat(JSON.readTree(s.admin().get(all + "?automationId=" + UUID.randomUUID()).body()).get("items")).isEmpty();
        assertThat(JSON.readTree(s.admin().get(all + "?from=2000-01-01T00:00:00Z&to=2000-01-02T00:00:00Z").body()).get("items")).isEmpty();
        assertThat(JSON.readTree(s.admin().get(all + "?from=2000-01-01T00:00:00Z").body()).get("items")).hasSize(1);
        assertThat(s.admin().get(all + "?from=2000-01-02T00:00:00Z&to=2000-01-01T00:00:00Z").status()).isEqualTo(400);
        assertThat(s.admin().get(all + "?from=ontem").status()).isEqualTo(400);
        assertThat(s.admin().get(all + "?limit=0").status()).isEqualTo(400);

        // quem so tem outro ambiente nao ve esta solicitacao; com papel de leitura neste ambiente, ve
        UUID other = newWorkspace(s.org(), newClient(s.org()));
        TestUser person = internalUser(s.org(), true, 2);
        grantWorkspaceRole(s.org(), person, other, "VIEWER");
        BrowserClient p = browser();
        firstLogin(p, s.org().id(), person);
        assertThat(JSON.readTree(p.get(all).body()).get("items")).as("sem acesso ao ambiente da solicitacao").isEmpty();
        assertThat(JSON.readTree(p.get(all + "?workspaceId=" + s.workspace()).body()).get("items")).as("nem pedindo o ambiente pelo id").isEmpty();
        grantWorkspaceRole(s.org(), person, s.workspace(), "VIEWER");
        assertThat(JSON.readTree(p.get(all).body()).get("items")).hasSize(1);
        // pessoa sem ambiente nenhum, outra organizacao, sem sessao
        TestUser outsider = internalUser(s.org(), true, 2);
        BrowserClient ob = browser();
        firstLogin(ob, s.org().id(), outsider);
        assertThat(JSON.readTree(ob.get(all).body()).get("items")).isEmpty();
        Setup foreign = setup();
        assertThat(JSON.readTree(foreign.admin().get(foreign.o() + "/remote-executions").body()).get("items")).isEmpty();
        assertThat(foreign.admin().get(all).status()).as("a sessao de outra organizacao nao le").isIn(401, 403, 404);
        assertThat(browser().get(all).status()).isEqualTo(401);
    }

    @Test
    void permissoesConfirmacaoReforcadaIsolamentoEGuardasDoBanco() throws Exception {
        Setup s = setup();
        declare(s, null);
        Machine m = machine(s, "Permissoes", true);
        heartbeat(s, m, true);
        String authorization = authorize(s, m);
        // OPERATOR (automation.execute): sem step-up 403; com step-up solicita. VIEWER: 404 para solicitar, mas le
        TestUser operator = internalUser(s.org(), true, 2);
        grantWorkspaceRole(s.org(), operator, s.workspace(), "OPERATOR");
        BrowserClient op = browser();
        firstLogin(op, s.org().id(), operator);
        assertThat(op.post(s.requests(), body(s, m, UUID.randomUUID())).status()).as("sem confirmacao reforcada").isEqualTo(403);
        assertThat(op.post(s.o() + "/auth/mfa/step-up", "{\"code\":\"" + code(operator.id()) + "\"}").status()).isEqualTo(204);
        BrowserClient.Response byOperator = op.post(s.requests(), body(s, m, UUID.randomUUID()));
        assertThat(byOperator.status()).as(byOperator.body()).isEqualTo(201);
        String execution = byOperator.field("remoteExecutionId");
        TestUser viewer = internalUser(s.org(), true, 2);
        grantWorkspaceRole(s.org(), viewer, s.workspace(), "VIEWER");
        BrowserClient v = browser();
        firstLogin(v, s.org().id(), viewer);
        assertThat(v.post(s.requests(), body(s, m, UUID.randomUUID())).status()).isEqualTo(404);
        assertThat(v.post(s.o() + "/remote-executions/" + execution + "/cancel", null).status()).isEqualTo(404);
        assertThat(v.get(s.o() + "/remote-executions/" + execution).status()).isEqualTo(200);
        assertThat(v.get(s.requests() + "/candidates?automationVersion=" + s.version()).status()).isEqualTo(200);
        // outra organizacao, sem sessao, versao que nao e publicada, pedidos malformados
        Setup foreign = setup();
        assertThat(foreign.admin().get(foreign.o() + "/remote-executions/" + execution).status()).isEqualTo(404);
        assertThat(foreign.admin().post(foreign.o() + "/workspaces/" + s.workspace() + "/remote-executions", body(s, m, UUID.randomUUID())).status()).isEqualTo(404);
        assertThat(browser().get(s.o() + "/remote-executions/" + execution).status()).isEqualTo(401);
        assertThat(s.admin().post(s.requests(), "{\"automationVersionId\":\"" + UUID.randomUUID() + "\",\"agentId\":\"" + m.agent() + "\",\"requestKey\":\""
                + UUID.randomUUID() + "\"}").status()).isEqualTo(404);
        for (String bad : new String[] {"{}", "{\"automationVersionId\":\"x\",\"agentId\":\"y\",\"requestKey\":\"z\"}"}) {
            assertThat(s.admin().post(s.requests(), bad).status()).as(bad).isEqualTo(400);
        }
        assertThat(s.admin().get(s.requests() + "/candidates").status()).isEqualTo(400);

        // o banco reconfere: as pessoas (nekkora_platform) nao criam tentativa com a autorizacao de outro Worker, nem para maquina
        // ocupada, nem por quem nao tem automation.execute; nao mudam o estado do que a maquina recebeu; nao apagam
        String attempt = value("SELECT attempt_id FROM remote_attempts WHERE remote_execution_id = '" + execution + "'");
        Machine other = machine(s, "OutroWorker", true);
        heartbeat(s, other, true);
        String viewerMembership = value("SELECT membership_id FROM organization_memberships WHERE user_id = '" + viewer.id() + "'");
        machineMoves(s, attempt, 2, 3, 11);
        String insert = "INSERT INTO remote_attempts (organization_id, remote_execution_id, attempt_number, agent_id, worker_id, authorization_id, "
                + "created_by_membership_id, offer_valid_until) VALUES ('" + s.org().id() + "', '" + execution + "', 2, '%s', '%s', '%s', '%s', clock_timestamp() + interval '15 minutes')";
        String admin = s.org().adminMembership().toString();
        assertThat(as("nekkora_platform", s.org().id(), insert.formatted(other.agent(), other.worker(), authorization, admin))).as("autorizacao de outro Worker").isEqualTo("23514");
        assertThat(as("nekkora_platform", s.org().id(), insert.formatted(m.agent(), m.worker(), authorization, viewerMembership))).as("sem automation.execute").isEqualTo("42501");
        assertThat(as("nekkora_platform", s.org().id(), insert.formatted(m.agent(), m.worker(), authorization, admin))).as("com tudo valendo").isEqualTo("OK");
        String second = value("SELECT attempt_id FROM remote_attempts WHERE remote_execution_id = '" + execution + "' AND attempt_number = 2");
        machineMoves(s, second, 2, 3, 4);
        assertThat(as("nekkora_platform", s.org().id(), "UPDATE remote_attempts SET state = 9 WHERE attempt_id = '" + second + "'")).isEqualTo("42501");
        assertThat(as("nekkora_platform", s.org().id(), "DELETE FROM remote_attempts WHERE attempt_id = '" + second + "'")).isEqualTo("42501");
        assertThat(as("nekkora_platform", s.org().id(), "UPDATE remote_executions SET requested_by_membership_id = '" + viewerMembership + "' WHERE remote_execution_id = '"
                + execution + "'")).isEqualTo("42501");
        assertThat(as("nekkora_platform", s.org().id(), "UPDATE remote_executions SET cancel_requested_by_membership_id = '" + viewerMembership
                + "' WHERE remote_execution_id = '" + execution + "'")).as("cancelamento por quem nao executa").isEqualTo("42501");
    }

    // ------------------------------------------------------------------ 057: retencao no servidor

    @org.springframework.beans.factory.annotation.Autowired
    private com.nekkora.platform.admin.RemoteRetention retention;

    @org.springframework.beans.factory.annotation.Autowired
    private com.nekkora.core.db.OrgTransactions orgTransactions;

    private static final String RESULTS_DIR = System.getProperty("java.io.tmpdir") + "/nekkora-platform-it-resultados";

    /** Uma tentativa em execucao, com {@code lines} linhas (os tres canais). */
    private String runningWithLines(Setup s, Machine m, int lines) throws Exception {
        String attempt = JSON.readTree(request(s, m).body()).get("attempts").get(0).get("attemptId").asString();
        machineMoves(s, attempt, 2, 3, 4, 5, 6);
        assertThat(as("nekkora_ingestion", s.org().id(), "INSERT INTO remote_attempt_log_lines (attempt_id, sequence, organization_id, captured_at, channel, "
                + "truncated, message) SELECT '" + attempt + "', g, '" + s.org().id() + "', clock_timestamp(), 1 + (g % 3), false, 'linha ' || g FROM "
                + "generate_series(1, " + lines + ") g")).isEqualTo("OK");
        return attempt;
    }

    private static String store(Setup s, String text) throws Exception {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        com.nekkora.core.artifact.ResultStore.at(RESULTS_DIR).put(s.org().id(), sha, bytes);
        return sha;
    }

    private static boolean stored(Setup s, String sha) throws Exception {
        return com.nekkora.core.artifact.ResultStore.at(RESULTS_DIR).get(s.org().id(), sha).isPresent();
    }

    private static void result(Setup s, String attempt, int number, String name, String text, String sha) {
        assertThat(as("nekkora_ingestion", s.org().id(), "INSERT INTO remote_attempt_results (attempt_id, result_number, organization_id, file_name, "
                + "size_bytes, sha256) VALUES ('" + attempt + "', " + number + ", '" + s.org().id() + "', '" + name + "', "
                + text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + ", '" + sha + "')")).isEqualTo("OK");
    }

    /** A maquina fecha os dois envios (entrega concluida). */
    private static void closeDelivery(Setup s, String attempt, int lines, int files) {
        assertThat(as("nekkora_ingestion", s.org().id(),
                "INSERT INTO remote_attempt_log_closures (attempt_id, organization_id, lines, dropped_lines) VALUES ('" + attempt + "', '" + s.org().id() + "', "
                        + lines + ", 0)",
                "INSERT INTO remote_attempt_result_closures (attempt_id, organization_id, sent, skipped_by_limit) VALUES ('" + attempt + "', '" + s.org().id()
                        + "', " + files + ", 0)")).isEqualTo("OK");
    }

    /** O relogio do teste: a entrega terminou ha {@code days} dias. So o superusuario muda essas datas. */
    private static void deliveryClosedDaysAgo(String attempt, int days) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("UPDATE remote_attempt_log_closures SET closed_at = closed_at - interval '" + days + " days' WHERE attempt_id = '" + attempt + "'");
            st.execute("UPDATE remote_attempt_result_closures SET closed_at = closed_at - interval '" + days + " days' WHERE attempt_id = '" + attempt + "'");
        }
    }

    private JsonNode read(Setup s, String attempt, String what) throws Exception {
        String execution = value("SELECT remote_execution_id FROM remote_attempts WHERE attempt_id = '" + attempt + "'");
        BrowserClient.Response r = s.admin().get(s.o() + "/remote-executions/" + execution + "/attempts/" + attempt + "/" + what);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    /**
     * 30 dias contados do FIM DA ENTREGA. Vencido: o texto das linhas e apagado e os bytes dos arquivos saem; a linha, o nome, o
     * tamanho, o SHA-256, a tentativa, os fechamentos e o registro do que venceu ficam. Entrega aberta nao vence. Repetir e seguro.
     */
    @Test
    void retencaoNoServidorVenceDoFimDaEntregaApagaSoOConteudoEPodeSerRepetida() throws Exception {
        Setup s = setup();
        declare(s, null);
        Machine a = machine(s, "Concluida", true);
        Machine b = machine(s, "EntregaAberta", true);
        Machine c = machine(s, "Revogada", true);
        Machine d = machine(s, "MesmosBytes", true);
        Machine e = machine(s, "AindaNoPrazo", true);
        Machine f = machine(s, "FalhaNaRemocao", true);
        Machine g = machine(s, "SemProgresso", true);
        Machine h = machine(s, "Incerta", true);
        for (Machine m : new Machine[] {a, b, c, d, e, f, g, h}) {
            heartbeat(s, m, true);
            authorize(s, m);
        }
        String shared = store(s, "bytes iguais em duas tentativas\n");
        String only = store(s, "bytes so da tentativa vencida\n");
        String later = store(s, "bytes da tentativa cuja remocao falha primeiro\n");

        // A: entrega concluida ha 31 dias - vence
        String expired = runningWithLines(s, a, 20);
        result(s, expired, 1, "igual.txt", "bytes iguais em duas tentativas\n", shared);
        result(s, expired, 2, "so-desta.txt", "bytes so da tentativa vencida\n", only);
        machineMoves(s, expired, 7);
        closeDelivery(s, expired, 20, 2);
        deliveryClosedDaysAgo(expired, 31);
        // D: os mesmos bytes, entrega concluida agora - nao vence, e os bytes compartilhados ficam
        String keeps = runningWithLines(s, d, 3);
        result(s, keeps, 1, "igual.txt", "bytes iguais em duas tentativas\n", shared);
        machineMoves(s, keeps, 7);
        closeDelivery(s, keeps, 3, 1);
        // B: terminou, mas a maquina nunca fechou o envio - entrega aberta, nada vence, por mais antiga que seja
        String open = runningWithLines(s, b, 5);
        machineMoves(s, open, 7);
        // E: concluida ha 29 dias - ainda no prazo
        String inTime = runningWithLines(s, e, 4);
        machineMoves(s, inTime, 7);
        closeDelivery(s, inTime, 4, 0);
        deliveryClosedDaysAgo(inTime, 29);
        // C: em execucao quando a maquina foi revogada ha 31 dias - encerramento explicito da entrega
        String revoked = runningWithLines(s, c, 6);
        stepUp(s);
        assertThat(s.admin().post(s.o() + "/agents/" + c.agent() + "/revoke", "{\"reason\":\"ADMINISTRATIVE\"}").status()).isEqualTo(204);
        assertThat(row(revoked)).isEqualTo("13|-|MACHINE_REVOKED|-");
        try (Connection cn = DB.connectAsSuperuser(); Statement st = cn.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("UPDATE remote_attempts SET created_at = created_at - interval '31 days', offer_valid_until = offer_valid_until - interval '31 days', "
                    + "offered_at = offered_at - interval '31 days', received_at = received_at - interval '31 days', started_at = started_at - interval '31 days', "
                    + "ended_at = ended_at - interval '31 days', state_changed_at = state_changed_at - interval '31 days', "
                    + "lease_expires_at = lease_expires_at - interval '31 days' WHERE attempt_id = '" + revoked + "'");
            st.execute("UPDATE worker_agent_assignments SET created_at = created_at - interval '40 days', ended_at = ended_at - interval '31 days' "
                    + "WHERE agent_id = '" + c.agent() + "'");
            st.execute("UPDATE agents SET updated_at = updated_at - interval '31 days' WHERE agent_id = '" + c.agent() + "'");
        }
        // 059 - G: terminou ha 31 dias, a maquina NUNCA fechou o envio e nada mais chegou: venceu o teto sem progresso de entrega
        String stalled = runningWithLines(s, g, 7);
        machineMoves(s, stalled, 7);
        // 059 - H: resultado nao confirmado, sem reconciliacao, ha 31 dias: execucao INCERTA - nada vence
        String uncertain = runningWithLines(s, h, 3);
        machineMoves(s, uncertain, 13);
        try (Connection cn = DB.connectAsSuperuser(); Statement st = cn.createStatement()) {
            st.execute("SET session_replication_role = replica");
            for (String id : new String[] {stalled, uncertain}) {
                st.execute("UPDATE remote_attempts SET created_at = created_at - interval '31 days', offer_valid_until = offer_valid_until - interval '31 days', "
                        + "offered_at = offered_at - interval '31 days', received_at = received_at - interval '31 days', started_at = started_at - interval '31 days', "
                        + "ended_at = ended_at - interval '31 days', state_changed_at = state_changed_at - interval '31 days', "
                        + "lease_expires_at = lease_expires_at - interval '31 days' WHERE attempt_id = '" + id + "'");
                st.execute("UPDATE remote_attempt_log_lines SET received_at = received_at - interval '31 days', captured_at = captured_at - interval '31 days' "
                        + "WHERE attempt_id = '" + id + "'");
            }
        }
        // a presenca da maquina NAO renova o prazo
        heartbeat(s, g, true);
        heartbeat(s, h, true);
        String before = value("SELECT count(*) FROM remote_attempt_log_lines WHERE organization_id = '" + s.org().id() + "'");

        // antes do ciclo o banco nao deixa a Platform apagar nada: sem vencimento registrado nao ha remocao; e nao aceita vencimento
        // de entrega aberta nem de prazo que nao venceu
        assertThat(as("nekkora_platform", s.org().id(), "UPDATE remote_attempt_log_lines SET message = '', redacted_at = clock_timestamp() WHERE attempt_id = '"
                + expired + "'")).isEqualTo("42501");
        assertThat(as("nekkora_platform", s.org().id(), "UPDATE remote_attempt_results SET removed_at = clock_timestamp() WHERE attempt_id = '" + expired + "'"))
                .isEqualTo("42501");
        String ins = "INSERT INTO remote_attempt_content_expirations (attempt_id, kind, organization_id, delivery_ended_at, delivery_end, retention_days, "
                + "items, bytes) VALUES ('%s', 1, '" + s.org().id() + "', clock_timestamp() - interval '90 days', 1, %d, 0, 0)";
        assertThat(as("nekkora_platform", s.org().id(), ins.formatted(open, 30))).as("o teto da entrega aberta ainda nao venceu").isEqualTo("23514");
        assertThat(as("nekkora_platform", s.org().id(), ins.formatted(uncertain, 30))).as("execucao incerta nao vence").isEqualTo("23514");
        assertThat(as("nekkora_platform", s.org().id(), ins.formatted(inTime, 30))).as("o banco usa a data dele, nao a de quem insere").isEqualTo("23514");
        assertThat(as("nekkora_platform", s.org().id(), ins.formatted(expired, 0))).isEqualTo("23514");
        assertThat(as("nekkora_ingestion", s.org().id(), ins.formatted(expired, 30))).as("o canal das maquinas nao registra vencimento").isEqualTo("42501");

        // --- falha na remocao dos bytes: o vencimento fica registrado, nada e marcado como removido, e o ciclo seguinte retoma
        String retried = runningWithLines(s, f, 2);
        result(s, retried, 1, "depois.txt", "bytes da tentativa cuja remocao falha primeiro\n", later);
        machineMoves(s, retried, 7);
        closeDelivery(s, retried, 2, 1);
        deliveryClosedDaysAgo(retried, 45);
        com.nekkora.platform.admin.RemoteRetention broken = new com.nekkora.platform.admin.RemoteRetention(orgTransactions,
                com.nekkora.core.artifact.ResultPurge.at(""), 44);
        com.nekkora.platform.admin.RemoteRetention.Cycle failing = broken.cycle();
        assertThat(failing.pending()).as("o arquivo ficou para o ciclo seguinte").isGreaterThanOrEqualTo(1);
        JsonNode half = read(s, retried, "results");
        assertThat(half.get("expiration").get("confirmedAt").isNull()).as("vencido, remocao ainda nao confirmada").isTrue();
        assertThat(half.get("expiration").get("retentionDays").asInt()).isEqualTo(44);
        assertThat(half.get("items").get(0).get("available").asBoolean()).as("nada e marcado como removido sem os bytes terem saido").isTrue();
        assertThat(stored(s, later)).isTrue();
        assertThat(as("nekkora_platform", s.org().id(), "UPDATE remote_attempt_content_expirations SET confirmed_at = clock_timestamp() WHERE attempt_id = '"
                + retried + "' AND kind = 2")).as("o banco nao aceita a confirmacao com conteudo presente").isEqualTo("23514");
        // com 44 dias, as tentativas de 31 dias NAO venceram
        assertThat(read(s, expired, "logs").get("expiration").isNull()).isTrue();

        // --- o ciclo de verdade (30 dias)
        assertThat(retention.days()).isEqualTo(30);
        com.nekkora.platform.admin.RemoteRetention.Cycle cycle = retention.cycle();
        assertThat(cycle.failed()).isZero();
        assertThat(cycle.pending()).isZero();
        assertThat(cycle.expired()).as("registros e resultados de A, registros de C").isGreaterThanOrEqualTo(3);

        // A: o texto saiu, as linhas ficaram; a leitura diz que venceu, quando, o prazo e como a entrega tinha terminado
        JsonNode logs = read(s, expired, "logs");
        assertThat(logs.get("total").asInt()).isEqualTo(20);
        assertThat(logs.get("items").size()).isEqualTo(20);
        for (JsonNode line : logs.get("items")) {
            assertThat(line.get("textRemoved").asBoolean()).isTrue();
            assertThat(line.get("message").asString()).isEmpty();
            assertThat(line.get("channel").asString()).isIn("STDOUT", "STDERR", "EXECUTOR");
        }
        assertThat(logs.get("closure").get("lines").asInt()).as("o fechamento fica").isEqualTo(20);
        JsonNode x = logs.get("expiration");
        assertThat(x.get("deliveryEnd").asString()).isEqualTo("CLOSED");
        assertThat(x.get("retentionDays").asInt()).isEqualTo(30);
        assertThat(x.get("items").asInt()).isEqualTo(20);
        assertThat(x.get("confirmedAt").isNull()).isFalse();
        JsonNode files = read(s, expired, "results");
        assertThat(files.get("items").size()).isEqualTo(2);
        assertThat(files.get("items").get(0).get("available").asBoolean()).isFalse();
        assertThat(files.get("items").get(1).get("available").asBoolean()).isFalse();
        assertThat(files.get("items").get(1).get("fileName").asString()).as("nome, tamanho e SHA-256 ficam").isEqualTo("so-desta.txt");
        assertThat(files.get("items").get(1).get("sha256").asString()).isEqualTo(only);
        assertThat(files.get("expiration").get("items").asInt()).isEqualTo(2);
        assertThat(files.get("expiration").get("confirmedAt").isNull()).isFalse();
        assertThat(stored(s, only)).as("os bytes so desta tentativa sairam do armazenamento").isFalse();
        assertThat(stored(s, shared)).as("os bytes que outra tentativa ainda usa ficam").isTrue();
        String execution = value("SELECT remote_execution_id FROM remote_attempts WHERE attempt_id = '" + expired + "'");
        refused(s.admin().get(s.o() + "/remote-executions/" + execution + "/attempts/" + expired + "/results/1/content"), "remote_result_unavailable");
        // historico e auditoria ficam: a tentativa, o desfecho, os fechamentos
        assertThat(row(expired)).isEqualTo("7|-|-|-");
        assertThat(value("SELECT count(*) FROM remote_attempt_log_lines WHERE organization_id = '" + s.org().id() + "'")).as("nenhuma linha de tabela apagada")
                .isEqualTo(String.valueOf(Integer.parseInt(before) + 2));
        assertThat(value("SELECT count(*) FROM remote_attempt_result_closures WHERE attempt_id = '" + expired + "'")).isEqualTo("1");

        // D: mesmos bytes, no prazo - intacta e baixavel
        JsonNode keptFiles = read(s, keeps, "results");
        assertThat(keptFiles.get("expiration").isNull()).isTrue();
        assertThat(keptFiles.get("items").get(0).get("available").asBoolean()).isTrue();
        String keptExecution = value("SELECT remote_execution_id FROM remote_attempts WHERE attempt_id = '" + keeps + "'");
        assertThat(s.admin().get(s.o() + "/remote-executions/" + keptExecution + "/attempts/" + keeps + "/results/1/content").body())
                .isEqualTo("bytes iguais em duas tentativas\n");
        // G (059): entrega INCOMPLETA - o teto venceu sem progresso; o conteudo sai, e nada indica recebimento (nao ha fechamento)
        JsonNode incomplete = read(s, stalled, "logs");
        assertThat(incomplete.get("expiration").get("deliveryEnd").asString()).isEqualTo("NO_PROGRESS");
        assertThat(incomplete.get("expiration").get("retentionDays").asInt()).isEqualTo(30);
        assertThat(incomplete.get("closure").isNull()).as("a maquina nao fechou o envio, e a leitura nao inventa um fechamento").isTrue();
        assertThat(incomplete.get("items").get(0).get("textRemoved").asBoolean()).isTrue();
        assertThat(value("SELECT count(*) FROM remote_attempt_log_closures WHERE attempt_id = '" + stalled + "'")).isEqualTo("0");
        assertThat(row(stalled)).isEqualTo("7|-|-|-");
        // H (059): execucao incerta - nada vence, por mais antiga que seja, com ou sem presenca da maquina
        JsonNode kept = read(s, uncertain, "logs");
        assertThat(kept.get("expiration").isNull()).isTrue();
        assertThat(kept.get("items").get(0).get("message").asString()).isEqualTo("linha 1");
        // B: terminou agora e a entrega esta aberta - o teto ainda nao venceu
        JsonNode stillOpen = read(s, open, "logs");
        assertThat(stillOpen.get("expiration").isNull()).isTrue();
        assertThat(stillOpen.get("items").get(0).get("message").asString()).isEqualTo("linha 1");
        // E: 29 dias - no prazo
        assertThat(read(s, inTime, "logs").get("expiration").isNull()).isTrue();
        assertThat(read(s, inTime, "logs").get("items").get(0).get("textRemoved").asBoolean()).isFalse();
        // C: vinculo encerrado ha 31 dias - encerramento explicito da entrega; o desfecho "sem confirmacao" continua la
        JsonNode gone = read(s, revoked, "logs");
        assertThat(gone.get("expiration").get("deliveryEnd").asString()).isEqualTo("LINK_ENDED");
        assertThat(gone.get("items").get(0).get("textRemoved").asBoolean()).isTrue();
        assertThat(row(revoked)).isEqualTo("13|-|MACHINE_REVOKED|-");
        // F: o ciclo seguinte retomou a remocao que tinha falhado - o prazo registrado continua o de quando venceu
        JsonNode done = read(s, retried, "results");
        assertThat(done.get("expiration").get("confirmedAt").isNull()).isFalse();
        assertThat(done.get("expiration").get("retentionDays").asInt()).isEqualTo(44);
        assertThat(done.get("items").get(0).get("available").asBoolean()).isFalse();
        assertThat(stored(s, later)).isFalse();

        // repetir nao muda nada
        com.nekkora.platform.admin.RemoteRetention.Cycle again = retention.cycle();
        assertThat(again.failed()).isZero();
        assertThat(again.linesRedacted()).isZero();
        assertThat(again.filesRemoved()).isZero();
        assertThat(read(s, open, "logs").get("items").get(0).get("message").asString()).isEqualTo("linha 1");

        // depois do vencimento: nada novo entra; o registro do que venceu nao muda nem some; a confirmacao e uma vez so
        assertThat(as("nekkora_ingestion", s.org().id(), "INSERT INTO remote_attempt_log_lines (attempt_id, sequence, organization_id, captured_at, channel, "
                + "truncated, message) VALUES ('" + revoked + "', 7, '" + s.org().id() + "', clock_timestamp(), 1, false, 'tarde demais')")).isEqualTo("23514");
        assertThat(as("nekkora_platform", s.org().id(), "DELETE FROM remote_attempt_content_expirations WHERE attempt_id = '" + expired + "'")).isEqualTo("42501");
        assertThat(as("nekkora_platform", s.org().id(), "UPDATE remote_attempt_content_expirations SET retention_days = 1 WHERE attempt_id = '" + expired + "'"))
                .isEqualTo("42501");
        assertThat(as("nekkora_platform", s.org().id(), "UPDATE remote_attempt_content_expirations SET confirmed_at = clock_timestamp() WHERE attempt_id = '"
                + expired + "' AND kind = 1")).isEqualTo("42501");
        assertThat(as("nekkora_platform", s.org().id(), "DELETE FROM remote_attempt_log_lines WHERE attempt_id = '" + expired + "'")).isEqualTo("42501");
        // outra organizacao nao ve o vencimento
        Setup other = setup();
        assertThat(as("nekkora_platform", other.org().id(), "DO $$ BEGIN IF (SELECT count(*) FROM remote_attempt_content_expirations) > 0 THEN "
                + "RAISE EXCEPTION 'vazou' USING ERRCODE = 'P0001'; END IF; END $$")).isEqualTo("OK");
    }

    // ------------------------------------------------------------------ 055: reconciliacao periodica

    @org.springframework.beans.factory.annotation.Autowired
    private com.nekkora.platform.admin.RemoteReconciler reconciler;

    /**
     * Ninguem abre a tela e a maquina nao volta: o ciclo do servidor encerra, sozinho, o que nao tem mais quem conclua - com as
     * mesmas regras da leitura, sem inventar desfecho, e sem tocar no que ainda esta dentro do prazo nem em outra organizacao.
     */
    @Test
    void oCicloDoServidorEncerraSemLeituraESemAMaquinaOQueNaoTemMaisQuemConclua() throws Exception {
        Setup s = setup();
        declare(s, null);
        Machine silent = machine(s, "Silenciosa", true);
        Machine waiting = machine(s, "SilenciosaAntes", true);
        Machine orphan = machine(s, "Orfa", true);
        Machine recent = machine(s, "Recente", true);
        Machine offered = machine(s, "NuncaRecebeu", true);
        for (Machine m : new Machine[] {silent, waiting, orphan, recent, offered}) {
            heartbeat(s, m, true);
            authorize(s, m);
        }
        // a organizacao entra na lista do ciclo na primeira tentativa, pela propria transacao que a criou
        assertThat(value("SELECT count(*) FROM remote_execution_organizations WHERE organization_id = '" + s.org().id() + "'")).isEqualTo("0");
        String lost = JSON.readTree(request(s, silent).body()).get("attempts").get(0).get("attemptId").asString();
        assertThat(value("SELECT count(*) FROM remote_execution_organizations WHERE organization_id = '" + s.org().id() + "'")).isEqualTo("1");
        machineMoves(s, lost, 2, 3, 4, 5, 6);
        String neverStarted = JSON.readTree(request(s, waiting).body()).get("attempts").get(0).get("attemptId").asString();
        machineMoves(s, neverStarted, 2, 3);
        String stuck = JSON.readTree(request(s, orphan).body()).get("attempts").get(0).get("attemptId").asString();
        machineMoves(s, stuck, 2, 3, 4, 5, 6);
        String inTime = JSON.readTree(request(s, recent).body()).get("attempts").get(0).get("attemptId").asString();
        machineMoves(s, inTime, 2, 3, 4, 5, 6);
        String neverReceived = JSON.readTree(request(s, offered).body()).get("attempts").get(0).get("attemptId").asString();
        // outra organizacao, com uma tentativa em execucao e dentro do prazo: o ciclo passa por ela e nao mexe
        Setup other = setup();
        declare(other, null);
        Machine far = machine(other, "DeOutra", true);
        heartbeat(other, far, true);
        authorize(other, far);
        String foreign = JSON.readTree(request(other, far).body()).get("attempts").get(0).get("attemptId").asString();
        machineMoves(other, foreign, 2, 3, 4, 5, 6);

        // dentro do prazo, o ciclo nao encerra nada destas organizacoes
        reconciler.cycle();
        assertThat(row(lost)).isEqualTo("6|-|-|-");
        assertThat(row(neverStarted)).isEqualTo("3|-|-|-");

        silentFor(s, lost, 121);
        silentFor(s, neverStarted, 121);
        silentFor(s, inTime, 100);
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("UPDATE agents SET status = 4 WHERE agent_id = '" + orphan.agent() + "'");
            st.execute("UPDATE remote_attempts SET offer_valid_until = clock_timestamp() - interval '1 minute', created_at = clock_timestamp() - interval '1 hour' "
                    + "WHERE attempt_id = '" + neverReceived + "'");
        }
        // NENHUMA leitura, nenhum contato de maquina: so o ciclo
        com.nekkora.platform.admin.RemoteReconciler.Cycle c = reconciler.cycle();
        assertThat(c.failed()).isZero();
        assertThat(c.lost()).isGreaterThanOrEqualTo(2);
        assertThat(c.unlinked()).isGreaterThanOrEqualTo(1);
        assertThat(c.expired()).isGreaterThanOrEqualTo(1);
        assertThat(row(lost)).as("em execucao, sem comunicacao alem da espera: sem confirmacao, sem codigo de saida").isEqualTo("13|-|COMMUNICATION_LOST|-");
        assertThat(row(neverStarted)).as("o servidor nunca autorizou o inicio: nao iniciou").isEqualTo("11|COMMUNICATION_LOST|COMMUNICATION_LOST|-");
        assertThat(row(stuck)).as("maquina que nao esta mais vinculada").isEqualTo("13|-|MACHINE_REVOKED|-");
        assertThat(row(neverReceived)).as("oferta vencida").isEqualTo("12|-|-|-");
        assertThat(row(inTime)).as("vencido ha menos que a espera: continua em execucao").isEqualTo("6|-|-|-");
        assertThat(row(foreign)).as("outra organizacao, dentro do prazo").isEqualTo("6|-|-|-");
        // repetir o ciclo nao muda nada
        com.nekkora.platform.admin.RemoteReconciler.Cycle again = reconciler.cycle();
        assertThat(row(lost)).isEqualTo("13|-|COMMUNICATION_LOST|-");
        assertThat(row(inTime)).isEqualTo("6|-|-|-");
        assertThat(again.failed()).isZero();

        // a lista do ciclo: a Platform le so identificadores; nao altera nem apaga; o canal das maquinas nao le; sem contexto,
        // as tentativas continuam invisiveis
        assertThat(valueAs("nekkora_platform", "SELECT count(*) FROM remote_attempts")).as("sem contexto, nenhuma tentativa").isEqualTo("0");
        assertThat(Integer.parseInt(valueAs("nekkora_platform", "SELECT count(*) FROM remote_execution_organizations"))).isGreaterThanOrEqualTo(2);
        assertThat(as("nekkora_platform", s.org().id(), "DELETE FROM remote_execution_organizations")).isEqualTo("42501");
        assertThat(as("nekkora_platform", s.org().id(), "UPDATE remote_execution_organizations SET first_attempt_at = now()")).isEqualTo("42501");
        assertThat(as("nekkora_platform", s.org().id(), "INSERT INTO remote_execution_organizations (organization_id) VALUES ('" + UUID.randomUUID() + "')"))
                .as("so a propria organizacao, com contexto").isEqualTo("42501");
        assertThat(as("nekkora_ingestion", s.org().id(), "SELECT count(*) FROM remote_execution_organizations")).isEqualTo("42501");
    }

    // ------------------------------------------------------------------ 053: encerramento sem confirmacao

    private JsonNode attemptOf(Setup s, String execution, int index) {
        BrowserClient.Response r = s.admin().get(s.o() + "/remote-executions/" + execution);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body()).get("attempts").get(index);
    }

    private static String row(String attempt) throws Exception {
        return value("SELECT state || '|' || coalesce(refusal_reason, '-') || '|' || coalesce(closure_cause, '-') || '|' || coalesce(exit_code::text, '-') "
                + "FROM remote_attempts WHERE attempt_id = '" + attempt + "'");
    }

    private static void silentFor(Setup s, String attempt, int minutes) {
        assertThat(as("nekkora_ingestion", s.org().id(), "UPDATE remote_attempts SET lease_expires_at = clock_timestamp() - interval '" + minutes
                + " minutes' WHERE attempt_id = '" + attempt + "'")).isEqualTo("OK");
    }

    @Test
    void revogacaoEPerdaDeComunicacaoEncerramATentativaSemInventarDesfechoEALeituraNuncaFicaEmExecucao() throws Exception {
        Setup s = setup();
        declare(s, null);
        Machine revoked = machine(s, "Revogada", true);
        Machine early = machine(s, "RevogadaAntes", true);
        Machine silent = machine(s, "Silenciosa", true);
        Machine waiting = machine(s, "SilenciosaAntes", true);
        Machine orphan = machine(s, "Orfa", true);
        Machine next = machine(s, "Destino", true);
        for (Machine m : new Machine[] {revoked, early, silent, waiting, orphan, next}) {
            heartbeat(s, m, true);
            authorize(s, m);
        }

        // --- revogacao por quem administra, com a tentativa em execucao e com outra ainda em preparacao
        JsonNode x = JSON.readTree(request(s, revoked).body());
        String execution = x.get("remoteExecutionId").asString();
        String running = x.get("attempts").get(0).get("attemptId").asString();
        machineMoves(s, running, 2, 3, 4, 5, 6);
        JsonNode y = JSON.readTree(request(s, early).body());
        String preparing = y.get("attempts").get(0).get("attemptId").asString();
        machineMoves(s, preparing, 2, 3, 4);
        stepUp(s);
        assertThat(s.admin().post(s.o() + "/agents/" + revoked.agent() + "/revoke", "{\"reason\":\"COMPROMISED\"}").status()).isEqualTo(204);
        assertThat(s.admin().post(s.o() + "/agents/" + early.agent() + "/revoke", "{\"reason\":\"ADMINISTRATIVE\"}").status()).isEqualTo(204);
        // em execucao: sem confirmacao (nunca sucesso, falha ou cancelamento; sem codigo de saida). Em preparacao: nao iniciou
        assertThat(row(running)).isEqualTo("13|-|MACHINE_REVOKED|-");
        assertThat(row(preparing)).isEqualTo("11|MACHINE_NOT_LINKED|MACHINE_REVOKED|-");
        JsonNode a = attemptOf(s, execution, 0);
        assertThat(a.get("state").asString()).isEqualTo("ENDED_UNKNOWN");
        assertThat(a.get("closureCause").asString()).isEqualTo("MACHINE_REVOKED");
        assertThat(a.get("exitCode").isNull()).isTrue();
        assertThat(a.get("lateReport").isNull()).isTrue();
        assertThat(a.get("endedAt").isNull()).isFalse();
        assertThat(a.get("communication").asString()).isEqualTo("NONE");
        JsonNode b = attemptOf(s, y.get("remoteExecutionId").asString(), 0);
        assertThat(b.get("state").asString()).isEqualTo("REFUSED");
        assertThat(b.get("refusalReason").asString()).isEqualTo("MACHINE_NOT_LINKED");
        assertThat(b.get("closureCause").asString()).isEqualTo("MACHINE_REVOKED");
        // 058: com resultado nao confirmado a repeticao fica BLOQUEADA. Revogar a maquina nao reconcilia nada
        String revokedRetry = s.o() + "/remote-executions/" + execution + "/attempts";
        refused(s.admin().post(revokedRetry, "{\"agentId\":\"" + next.agent() + "\"}"), "remote_previous_result_unconfirmed");
        assertThat(a.get("reconciliations").size()).isZero();
        // a maquina revogada nao tem como confirmar: so a declaracao de um ORG_ADMIN, com motivo, verificacao e a afirmacao dos
        // efeitos parciais. Faltando qualquer um, 400 e nada gravado
        String declare = s.o() + "/remote-executions/" + execution + "/attempts/" + running + "/reconciliation";
        String verified = "Computador desligado pela equipe e mantido fora de operacao.";
        assertThat(s.admin().post(declare, "{\"reason\":\"MACHINE_OFF_OUT_OF_OPERATION\",\"verification\":\"" + verified
                + "\",\"acknowledgePartialEffects\":false}").status()).as("sem afirmar os efeitos parciais").isEqualTo(400);
        assertThat(s.admin().post(declare, "{\"reason\":\"MACHINE_OFF_OUT_OF_OPERATION\",\"verification\":\"curto\",\"acknowledgePartialEffects\":true}")
                .status()).as("sem descrever a verificacao").isEqualTo(400);
        assertThat(s.admin().post(declare, "{\"reason\":\"ACEITO_O_RISCO\",\"verification\":\"" + verified + "\",\"acknowledgePartialEffects\":true}").status())
                .as("motivo fora da lista").isEqualTo(400);
        assertThat(value("SELECT count(*) FROM remote_attempt_process_reports WHERE attempt_id = '" + running + "'")).isEqualTo("0");
        // quem executa mas nao e ORG_ADMIN nao declara
        TestUser operatorUser = internalUser(s.org(), true, 2);
        grantWorkspaceRole(s.org(), operatorUser, s.workspace(), "OPERATOR");
        BrowserClient executor = browser();
        firstLogin(executor, s.org().id(), operatorUser);
        assertThat(executor.post(s.o() + "/auth/mfa/step-up", "{\"code\":\"" + code(operatorUser.id()) + "\"}").status()).isEqualTo(204);
        BrowserClient.Response denied = executor.post(declare, "{\"reason\":\"MACHINE_OFF_OUT_OF_OPERATION\",\"verification\":\"" + verified
                + "\",\"acknowledgePartialEffects\":true}");
        assertThat(denied.status()).as(denied.body()).isEqualTo(403);
        assertThat(value("SELECT count(*) FROM remote_attempt_process_reports WHERE attempt_id = '" + running + "'")).as("a chamada direta recusada nao grava nada")
                .isEqualTo("0");
        assertThat(JSON.readTree(s.admin().get(s.requests()).body()).toString()).as("nem aparece na leitura").doesNotContain("\"kind\":\"MANUAL\"");
        // a tentativa que nunca iniciou nao tem o que reconciliar
        refused(s.admin().post(s.o() + "/remote-executions/" + y.get("remoteExecutionId").asString() + "/attempts/" + preparing + "/reconciliation",
                "{\"reason\":\"MACHINE_OFF_OUT_OF_OPERATION\",\"verification\":\"" + verified + "\",\"acknowledgePartialEffects\":true}"),
                "remote_attempt_not_unconfirmed");
        stepUp(s);
        BrowserClient.Response declared = s.admin().post(declare, "{\"reason\":\"MACHINE_OFF_OUT_OF_OPERATION\",\"verification\":\"" + verified
                + "\",\"acknowledgePartialEffects\":true}");
        assertThat(declared.status()).as(declared.body()).isEqualTo(201);
        JsonNode manual = JSON.readTree(declared.body()).get("attempts").get(0);
        // o resultado anterior continua "nao confirmado": nada de sucesso, falha ou codigo de saida; fica quem, quando, por que
        assertThat(manual.get("state").asString()).isEqualTo("ENDED_UNKNOWN");
        assertThat(manual.get("exitCode").isNull()).isTrue();
        assertThat(manual.get("closureCause").asString()).isEqualTo("MACHINE_REVOKED");
        assertThat(manual.get("reconciliations").size()).isEqualTo(1);
        assertThat(manual.get("reconciliations").get(0).get("kind").asString()).isEqualTo("MANUAL");
        assertThat(manual.get("reconciliations").get(0).get("reason").asString()).isEqualTo("MACHINE_OFF_OUT_OF_OPERATION");
        assertThat(manual.get("reconciliations").get(0).get("verification").asString()).isEqualTo(verified);
        assertThat(manual.get("reconciliations").get(0).get("byMembershipId").asString()).isEqualTo(s.org().adminMembership().toString());
        assertThat(row(running)).isEqualTo("13|-|MACHINE_REVOKED|-");
        assertThat(value("SELECT agent_id || '|' || kind FROM remote_attempt_process_reports WHERE attempt_id = '" + running + "'"))
                .as("a maquina e a tentativa envolvidas ficam no registro").isEqualTo(revoked.agent() + "|2");
        assertThat(value("SELECT count(*) FROM remote_attempts WHERE remote_execution_id = '" + execution + "'")).as("a reconciliacao nao executa nada")
                .isEqualTo("1");
        refused(s.admin().post(declare, "{\"reason\":\"OTHER_VERIFIED\",\"verification\":\"" + verified + "\",\"acknowledgePartialEffects\":true}"),
                "remote_attempt_already_reconciled");
        // liberada: a repeticao continua sendo um ato de uma pessoa, em outra maquina, com a autorizacao do Worker de destino
        BrowserClient.Response again = s.admin().post(revokedRetry, "{\"agentId\":\"" + next.agent() + "\"}");
        assertThat(again.status()).as(again.body()).isEqualTo(201);
        String second = JSON.readTree(again.body()).get("attempts").get(1).get("attemptId").asString();
        machineMoves(s, second, 2, 3, 11);
        // guardas do banco: so a Platform declara, e so por ORG_ADMIN; a maquina nao declara por uma pessoa; nada muda nem some
        String raw = "INSERT INTO remote_attempt_process_reports (attempt_id, kind, organization_id, agent_id, reason, verification, by_membership_id) VALUES ('%s', 2, '"
                + s.org().id() + "', '" + revoked.agent() + "', 'OTHER_VERIFIED', 'verificacao feita por fora da aplicacao', '%s')";
        assertThat(as("nekkora_ingestion", s.org().id(), raw.formatted(running, s.org().adminMembership()))).isIn("42501", "23505");
        assertThat(as("nekkora_platform", s.org().id(), "UPDATE remote_attempt_process_reports SET reason = 'OTHER_VERIFIED' WHERE attempt_id = '" + running + "'"))
                .isEqualTo("42501");
        assertThat(as("nekkora_platform", s.org().id(), "DELETE FROM remote_attempt_process_reports WHERE attempt_id = '" + running + "'")).isEqualTo("42501");

        // --- comunicacao perdida
        JsonNode z = JSON.readTree(request(s, silent).body());
        String lostExecution = z.get("remoteExecutionId").asString();
        String lost = z.get("attempts").get(0).get("attemptId").asString();
        machineMoves(s, lost, 2, 3, 4, 5, 6);
        JsonNode w = JSON.readTree(request(s, waiting).body());
        String neverStarted = w.get("attempts").get(0).get("attemptId").asString();
        machineMoves(s, neverStarted, 2, 3);
        // vencido ha menos que a espera (120 min): continua em execucao, com a comunicacao perdida a vista; nada e gravado
        silentFor(s, lost, 100);
        JsonNode still = attemptOf(s, lostExecution, 0);
        assertThat(still.get("state").asString()).isEqualTo("RUNNING");
        assertThat(still.get("communication").asString()).isEqualTo("LOST");
        assertThat(still.get("closureCause").isNull()).isTrue();
        assertThat(row(lost)).isEqualTo("6|-|-|-");
        refused(request(s, silent), "remote_machine_busy");
        // alem da espera: a LEITURA ja mostra o encerramento, e ele esta gravado (nao e so apresentacao)
        silentFor(s, lost, 121);
        silentFor(s, neverStarted, 121);
        JsonNode list = JSON.readTree(s.admin().get(s.requests()).body()).get("items");
        for (JsonNode e : list) {
            for (JsonNode t : e.get("attempts")) {
                assertThat(t.get("state").asString()).as("nenhuma tentativa fica em andamento sem ter quem a conclua")
                        .isIn("ENDED_UNKNOWN", "REFUSED");
            }
        }
        assertThat(row(lost)).isEqualTo("13|-|COMMUNICATION_LOST|-");
        assertThat(row(neverStarted)).isEqualTo("11|COMMUNICATION_LOST|COMMUNICATION_LOST|-");
        JsonNode unknown = attemptOf(s, lostExecution, 0);
        assertThat(unknown.get("state").asString()).isEqualTo("ENDED_UNKNOWN");
        assertThat(unknown.get("closureCause").asString()).isEqualTo("COMMUNICATION_LOST");
        assertThat(unknown.get("lateReport").isNull()).isTrue();
        // 058: a espera vencida consolida o resultado, mas NAO da a maquina por livre nem libera a repeticao
        assertThat(missing(s, silent)).contains("MACHINE_UNCONFIRMED").doesNotContain("MACHINE_BUSY");
        refused(request(s, silent), "remote_machine_unconfirmed");
        stepUp(s);
        String retryPath = s.o() + "/remote-executions/" + lostExecution + "/attempts";
        refused(s.admin().post(retryPath, "{\"agentId\":\"" + next.agent() + "\"}"), "remote_previous_result_unconfirmed");
        // "aceito o risco" nao existe mais: o campo antigo nem e aceito
        assertThat(s.admin().post(retryPath, "{\"agentId\":\"" + next.agent() + "\",\"acknowledgeUnconfirmed\":true}").status()).isIn(400, 409);
        assertThat(value("SELECT count(*) FROM remote_attempts WHERE remote_execution_id = '" + lostExecution + "'")).as("nada foi gravado").isEqualTo("1");
        // a maquina que nunca iniciou (o servidor nao autorizou o inicio) nao tem processo: nao fica com ocupacao incerta
        assertThat(missing(s, waiting)).doesNotContain("MACHINE_UNCONFIRMED");
        // um NOVO CONTATO nao torna a maquina livre nem libera a repeticao
        heartbeat(s, silent, true);
        assertThat(missing(s, silent)).contains("MACHINE_UNCONFIRMED");
        refused(s.admin().post(retryPath, "{\"agentId\":\"" + next.agent() + "\"}"), "remote_previous_result_unconfirmed");
        // a maquina voltou e disse que AINDA EXECUTA (papel da ingestao, como o gateway): passa a OCUPADA; a repeticao segue bloqueada
        assertThat(as("nekkora_ingestion", s.org().id(), "INSERT INTO remote_attempt_process_reports (attempt_id, kind, organization_id, agent_id) VALUES ('"
                + lost + "', 3, '" + s.org().id() + "', '" + silent.agent() + "')")).isEqualTo("OK");
        assertThat(missing(s, silent)).contains("MACHINE_BUSY").doesNotContain("MACHINE_UNCONFIRMED");
        refused(request(s, silent), "remote_machine_busy");
        assertThat(attemptOf(s, lostExecution, 0).get("machineStillRunningAt").isNull()).isFalse();
        refused(s.admin().post(retryPath, "{\"agentId\":\"" + next.agent() + "\"}"), "remote_previous_result_unconfirmed");
        // declaracao manual: libera uma nova tentativa em OUTRA maquina; a maquina antiga continua indisponivel
        String declareLost = s.o() + "/remote-executions/" + lostExecution + "/attempts/" + lost + "/reconciliation";
        assertThat(s.admin().post(declareLost, "{\"reason\":\"PROCESS_ENDED_BY_ADMINISTRATOR\",\"verification\":\"Processo encerrado no computador por quem o administra.\","
                + "\"acknowledgePartialEffects\":true}").status()).isEqualTo(201);
        assertThat(missing(s, silent)).as("a declaracao manual nao libera a maquina antiga").contains("MACHINE_BUSY");
        refused(s.admin().post(retryPath, "{\"agentId\":\"" + silent.agent() + "\"}"), "remote_machine_busy");
        BrowserClient.Response released = s.admin().post(retryPath, "{\"agentId\":\"" + next.agent() + "\"}");
        assertThat(released.status()).as(released.body()).isEqualTo(201);
        machineMoves(s, JSON.readTree(released.body()).get("attempts").get(1).get("attemptId").asString(), 2, 3, 11);
        // 060: a maquina que disse "ainda executa" informa depois o fim SEM confirmar os processos: a ocupacao dela passa a
        // INCERTA - nao continua "em andamento"
        assertThat(as("nekkora_ingestion", s.org().id(), "INSERT INTO remote_attempt_process_reports (attempt_id, kind, organization_id, agent_id) VALUES ('"
                + lost + "', 5, '" + s.org().id() + "', '" + silent.agent() + "')")).isEqualTo("OK");
        assertThat(missing(s, silent)).contains("MACHINE_UNCONFIRMED").doesNotContain("MACHINE_BUSY");
        refused(request(s, silent), "remote_machine_unconfirmed");
        assertThat(attemptOf(s, lostExecution, 0).get("machineStillRunningAt").isNull()).as("'ainda executa' deixou de valer").isTrue();
        assertThat(attemptOf(s, lostExecution, 0).get("processes").asString()).isEqualTo("UNCERTAIN");
        // a maquina diz como terminou (evidencia tardia): fica AO LADO da decisao manual, que nao e alterada; o estado nao muda
        assertThat(as("nekkora_ingestion", s.org().id(), "UPDATE remote_attempts SET late_state = 7, late_exit_code = 0 WHERE attempt_id = '" + lost + "'",
                "INSERT INTO remote_attempt_process_reports (attempt_id, kind, organization_id, agent_id) VALUES ('" + lost + "', 1, '" + s.org().id() + "', '"
                        + silent.agent() + "')")).isEqualTo("OK");
        JsonNode told = attemptOf(s, lostExecution, 0);
        assertThat(told.get("state").asString()).isEqualTo("ENDED_UNKNOWN");
        assertThat(told.get("exitCode").isNull()).isTrue();
        assertThat(told.get("lateReport").get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(told.get("lateReport").get("exitCode").asInt()).isZero();
        assertThat(told.get("lateReport").get("reportedAt").asString()).isNotBlank();
        assertThat(told.get("machineStillRunningAt").isNull()).as("ela confirmou o fim").isTrue();
        List<String> kinds = new ArrayList<>();
        told.get("reconciliations").forEach(r -> kinds.add(r.get("kind").asString()));
        assertThat(kinds).as("a decisao manual e a confirmacao da maquina convivem").containsExactly("MANUAL", "MACHINE");
        assertThat(told.get("reconciliations").get(0).get("reason").asString()).isEqualTo("PROCESS_ENDED_BY_ADMINISTRATOR");
        // depois de "ausente", "ainda executa" nao e mais aceito
        assertThat(as("nekkora_ingestion", s.org().id(), "INSERT INTO remote_attempt_process_reports (attempt_id, kind, organization_id, agent_id) VALUES ('"
                + neverStarted + "', 1, '" + s.org().id() + "', '" + waiting.agent() + "')")).as("so tentativa nao confirmada").isEqualTo("23514");
        // a maquina reconciliou a propria ocupacao: uma nova solicitacao para ela e aceita
        assertThat(missing(s, silent)).doesNotContain("MACHINE_BUSY").doesNotContain("MACHINE_UNCONFIRMED");
        BrowserClient.Response fresh = request(s, silent);
        assertThat(fresh.status()).as(fresh.body()).isEqualTo(201);

        // --- tentativa que ja estava parada quando a regra chegou (vinculo encerrado sem o encerramento da tentativa)
        JsonNode o = JSON.readTree(request(s, orphan).body());
        String stuck = o.get("attempts").get(0).get("attemptId").asString();
        machineMoves(s, stuck, 2, 3, 4, 5, 6);
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("UPDATE agents SET status = 4 WHERE agent_id = '" + orphan.agent() + "'");
        }
        assertThat(row(stuck)).isEqualTo("6|-|-|-");
        JsonNode reconciled = attemptOf(s, o.get("remoteExecutionId").asString(), 0);
        assertThat(reconciled.get("state").asString()).isEqualTo("ENDED_UNKNOWN");
        assertThat(reconciled.get("closureCause").asString()).isEqualTo("MACHINE_REVOKED");
        assertThat(row(stuck)).isEqualTo("13|-|MACHINE_REVOKED|-");
        // os registros e resultados que existirem continuam consultaveis: o encerramento nao apaga nada
        assertThat(s.admin().get(s.o() + "/remote-executions/" + o.get("remoteExecutionId").asString() + "/attempts/" + stuck + "/logs").status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ 054: verificacao de dependencias sob demanda

    private BrowserClient.Response checkDependencies(Setup s, Machine m) {
        return s.admin().post(s.o() + "/automations/" + s.automation() + "/remote-machines/" + m.agent() + "/dependency-check", null);
    }

    private JsonNode checkOf(Setup s, Machine m) {
        BrowserClient.Response r = s.admin().get(s.requests() + "/candidates?automationVersion=" + s.version());
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        for (JsonNode n : JSON.readTree(r.body()).get("items")) {
            if (n.get("agentId").asString().equals(m.agent().toString())) {
                return n.get("dependencyCheck");
            }
        }
        throw new AssertionError("maquina fora da lista");
    }

    /** Recua o pedido mais recente (fixture): o limite de um por minuto deixa de valer para o proximo. */
    private static void olderCheck(Machine m, String what) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("UPDATE remote_dependency_checks SET " + what + " WHERE agent_id = '" + m.agent() + "'");
        }
    }

    @Test
    void verificacaoSobDemandaCongelaOQueSeraConferidoTemLimiteESemRespostaNuncaViraIncompativel() throws Exception {
        Setup s = setup();
        Machine m = machine(s, "Verifica", true);
        heartbeat(s, m, true);
        // sem dependencias declaradas, nao ha o que conferir
        refused(checkDependencies(s, m), "remote_requirements_not_declared");
        declare(s, "idna==3.7\nrequests==2.32.3");
        assertThat(checkOf(s, m).isNull()).as("nunca houve verificacao").isTrue();
        // o pedido congela o ambiente escolhido e a lista efetiva de agora; nasce aguardando a maquina
        BrowserClient.Response asked = checkDependencies(s, m);
        assertThat(asked.status()).as(asked.body()).isEqualTo(201);
        JsonNode pending = JSON.readTree(asked.body());
        assertThat(pending.get("state").asString()).isEqualTo("PENDING");
        assertThat(pending.get("outcome").isNull()).isTrue();
        assertThat(pending.get("environmentId").asString()).isEqualTo(ENV_A);
        assertThat(pending.get("current").asBoolean()).isTrue();
        String check = pending.get("checkId").asString();
        assertThat(value("SELECT dependencies::text || '|' || requirements_revision || '|' || requested_by_membership_id FROM remote_dependency_checks "
                + "WHERE check_id = '" + check + "'")).isEqualTo("[\"idna==3.7\", \"requests==2.32.3\"]|1|" + s.org().adminMembership());
        assertThat(checkOf(s, m).get("state").asString()).isEqualTo("PENDING");
        // limite: um pedido por minuto por maquina e automacao; o segundo nao cria nada
        BrowserClient.Response again = checkDependencies(s, m);
        assertThat(again.status()).as(again.body()).isEqualTo(429);
        assertThat(again.body()).isEqualTo("{\"error\":\"rate_limited\",\"reason\":\"remote_dependency_check_too_soon\"}");
        assertThat(value("SELECT count(*) FROM remote_dependency_checks WHERE agent_id = '" + m.agent() + "'")).isEqualTo("1");
        // 056: o intervalo que vale e o da aplicacao (60 s nesta configuracao); o banco guarda so o piso de 10 s. Meio minuto depois
        // a aplicacao ainda recusa - e o limite e desta maquina e desta automacao: outra maquina nao e segurada por este pedido
        olderCheck(m, "requested_at = requested_at - interval '30 seconds', answer_valid_until = answer_valid_until - interval '30 seconds'");
        assertThat(checkDependencies(s, m).status()).isEqualTo(429);
        Machine neighbour = machine(s, "Vizinha", true);
        heartbeat(s, neighbour, true);
        BrowserClient.Response other = checkDependencies(s, neighbour);
        assertThat(other.status()).as("o pedido para outra maquina nao e segurado: " + other.body()).isEqualTo(201);
        olderCheck(m, "requested_at = requested_at + interval '30 seconds', answer_valid_until = answer_valid_until + interval '30 seconds'");
        // nada mudou alem do pedido: a escolha, as condicoes da solicitacao e a maquina continuam como estavam
        assertThat(missing(s, m)).containsExactly("AUTHORIZATION_MISSING");
        // a maquina responde (papel da ingestao, como o gateway): falta uma
        assertThat(as("nekkora_ingestion", s.org().id(), "UPDATE remote_dependency_checks SET outcome = 2, missing = '[\"requests==2.32.3\"]'::jsonb "
                + "WHERE check_id = '" + check + "'")).isEqualTo("OK");
        JsonNode answered = checkOf(s, m);
        assertThat(answered.get("state").asString()).isEqualTo("ANSWERED");
        assertThat(answered.get("outcome").asString()).isEqualTo("MISSING");
        assertThat(answered.get("missing").toString()).isEqualTo("[\"requests==2.32.3\"]");
        assertThat(answered.get("answeredAt").isNull()).isFalse();
        assertThat(answered.get("current").asBoolean()).isTrue();
        // a escolha do Python mudou: a resposta continua la, mas e dita como de OUTRA configuracao
        inventory(s, m, ENV_B, "3.13.1");
        assertThat(selectPython(s, m, ENV_B).status()).isEqualTo(200);
        assertThat(checkOf(s, m).get("current").asBoolean()).isFalse();
        assertThat(checkOf(s, m).get("outcome").asString()).isEqualTo("MISSING");
        // novo pedido (passado o minuto): confere a escolha NOVA. Sem resposta dentro do prazo: UNANSWERED - nao foi possivel
        // verificar; nenhum resultado e inventado, e nunca "incompativel"
        olderCheck(m, "requested_at = requested_at - interval '5 minutes', answer_valid_until = answer_valid_until - interval '5 minutes'");
        BrowserClient.Response second = checkDependencies(s, m);
        assertThat(second.status()).as(second.body()).isEqualTo(201);
        assertThat(JSON.readTree(second.body()).get("environmentId").asString()).isEqualTo(ENV_B);
        olderCheck(m, "requested_at = requested_at - interval '5 minutes', answer_valid_until = answer_valid_until - interval '5 minutes'");
        JsonNode unanswered = checkOf(s, m);
        assertThat(unanswered.get("state").asString()).isEqualTo("UNANSWERED");
        assertThat(unanswered.get("outcome").isNull()).isTrue();
        assertThat(unanswered.get("missing").size()).isZero();
        assertThat(unanswered.get("current").asBoolean()).isTrue();
        // a revisao das dependencias mudou: a verificacao anterior deixa de ser a de agora
        declare(s, "idna==3.7");
        assertThat(checkOf(s, m).get("current").asBoolean()).isFalse();
        // sem Python escolhido; maquina de Worker fora do ambiente; maquina inexistente
        Machine bare = machine(s, "Sem Python", true, false);
        refused(checkDependencies(s, bare), "remote_python_not_selected");
        Machine outside = machine(s, "Fora", false, false);
        assertThat(checkDependencies(s, outside).status()).isEqualTo(404);
        assertThat(s.admin().post(s.o() + "/automations/" + s.automation() + "/remote-machines/" + UUID.randomUUID() + "/dependency-check", null).status())
                .isEqualTo(404);
        assertThat(s.admin().post(s.o() + "/automations/" + s.automation() + "/remote-machines/nao-e-uuid/dependency-check", null).status()).isEqualTo(400);
        // o ambiente escolhido saiu do inventario: nao ha o que pedir a maquina
        assertThat(as("nekkora_ingestion", s.org().id(), "DELETE FROM agent_python_environments WHERE agent_id = '" + m.agent() + "' AND environment_id = '" + ENV_B
                + "'")).isEqualTo("OK");
        olderCheck(m, "requested_at = requested_at - interval '5 minutes', answer_valid_until = answer_valid_until - interval '5 minutes'");
        refused(checkDependencies(s, m), "remote_python_not_in_inventory");
        // quem so le nao pede (automation.execute); outra organizacao nao enxerga a automacao
        TestUser viewer = internalUser(s.org(), true, 2);
        grantWorkspaceRole(s.org(), viewer, s.workspace(), "VIEWER");
        BrowserClient v = browser();
        firstLogin(v, s.org().id(), viewer);
        assertThat(v.post(s.o() + "/automations/" + s.automation() + "/remote-machines/" + m.agent() + "/dependency-check", null).status()).isIn(403, 404);
        Setup foreign = setup();
        assertThat(foreign.admin().post(foreign.o() + "/automations/" + s.automation() + "/remote-machines/" + m.agent() + "/dependency-check", null).status())
                .isEqualTo(404);
        // guardas do banco: as pessoas nao gravam resposta; ninguem cria pedido para outro ambiente que nao o escolhido
        assertThat(as("nekkora_platform", s.org().id(), "UPDATE remote_dependency_checks SET outcome = 1 WHERE check_id = '" + check + "'")).isEqualTo("42501");
        assertThat(as("nekkora_platform", s.org().id(), "INSERT INTO remote_dependency_checks (organization_id, workspace_id, automation_id, agent_id, "
                + "environment_id, requirements_revision, dependencies, requested_by_membership_id, answer_valid_until) VALUES ('" + s.org().id() + "', '"
                + s.workspace() + "', '" + s.automation() + "', '" + bare.agent() + "', '" + ENV_A + "', 2, '[]'::jsonb, '" + s.org().adminMembership()
                + "', clock_timestamp() + interval '1 minute')")).isEqualTo("23514");
    }

    // ------------------------------------------------------------------ PLATFORM-LICENCIAMENTO-001 (064): a licenca nos fluxos

    private BrowserClient.Response authorizeUntil(Setup s, Machine m, int days) {
        return s.admin().post(s.o() + "/workspaces/" + s.workspace() + "/local-run-authorizations", "{\"workerId\":\"" + m.worker()
                + "\",\"automationVersionId\":\"" + s.version() + "\",\"validUntil\":\"" + OffsetDateTime.now(ZoneOffset.UTC).plusDays(days).withNano(0) + "\"}");
    }

    private static String periodEnd(UUID license, int number) {
        return "(SELECT ends_at FROM organization_license_periods WHERE license_id = '" + license + "' AND period_number = " + number + ")";
    }

    /**
     * No modelo por licenca, o SERVIDOR confere a licenca em cada inicio de trabalho: solicitacao, tentativa, autorizacao. A
     * validade da autorizacao nunca atravessa uma lacuna entre periodos (D3); o prazo da autorizacao deixa de recusar a execucao
     * remota e a revogacao continua valendo (S1); o aceite da versao no mesmo passo e explicito e fica na trilha (S2); vencida a
     * licenca, trabalho novo e recusado e o que ja comecou termina e entrega.
     */
    @Test
    void noModeloPorLicencaOServidorConfereALicencaEmCadaInicioDeTrabalho() throws Exception {
        Setup s = setup();
        UUID org = s.org().id();
        Machine m = machine(s, "Fiscal", true);
        declare(s, null);
        heartbeat(s, m, true);
        String first = authorize(s, m);
        assertThat(missing(s, m)).as("modelo anterior: nada muda").isEmpty();
        // a autorizacao de m vence (o tempo passou): no modelo anterior, o prazo recusa
        DB.asSuperuser("SET session_replication_role = replica", "UPDATE local_run_authorizations SET granted_at = clock_timestamp() - interval '40 days', "
                + "valid_until = clock_timestamp() - interval '1 day' WHERE authorization_id = '" + first + "'");
        assertThat(missing(s, m)).as("modelo anterior: a autorizacao vencida recusa").containsExactly("AUTHORIZATION_EXPIRED");
        refused(request(s, m), "remote_authorization_expired");

        // ---- a organizacao passa ao modelo por licenca; a automacao nao tem licenca
        LicenseFixtures.enforce(org, s.org().adminId());
        assertThat(missing(s, m)).as("o direito ao servico vem antes do resto").containsExactly("LICENSE_REQUIRED");
        refused(request(s, m), "license_required");
        Machine m2 = machine(s, "Contabil", true);
        heartbeat(s, m2, true);
        refused(authorizeUntil(s, m2, 30), "license_required");
        refused(s.admin().post(s.requests(), body(s, m2, UUID.randomUUID()).replace("}", ",\"authorizeVersion\":true}")), "license_required");
        assertThat(value("SELECT count(*) FROM remote_executions WHERE organization_id = '" + org + "'")).as("nada foi admitido").isEqualTo("0");
        assertThat(value("SELECT count(*) FROM local_run_authorizations WHERE organization_id = '" + org + "'")).as("nenhuma autorizacao nova").isEqualTo("1");
        // o banco reconfere: o papel real do servico nao grava trabalho novo por fora da regra
        assertThat(as("nekkora_platform", org, "INSERT INTO remote_executions (organization_id, workspace_id, automation_id, automation_version_id, "
                + "package_fingerprint, package_fingerprint_rule, requirements_revision, effective_dependencies, request_key, requested_by_membership_id) VALUES ('"
                + org + "', '" + s.workspace() + "', '" + s.automation() + "', '" + s.version() + "', '" + FINGERPRINT + "', '" + EXTERNAL + "', 1, '[]'::jsonb, '"
                + UUID.randomUUID() + "', '" + s.org().adminMembership() + "')")).as("gatilho da 064").isEqualTo("23514");

        // ---- licenca vigente por 10 dias, vinculada pelo administrador (rota real)
        UUID license = LicenseFixtures.license(org, 1, "clock_timestamp() - interval '1 day'", "clock_timestamp() + interval '10 days'");
        BrowserClient.Response linked = s.admin().post(s.o() + "/licenses/" + license + "/assignment", "{\"automationId\":\"" + s.automation() + "\"}");
        assertThat(linked.status()).as(linked.body()).isEqualTo(200);
        // S1: a autorizacao de m continua vencida, e no modelo por licenca isso nao recusa a execucao remota
        assertThat(missing(s, m)).as("S1: o prazo e o da licenca").isEmpty();
        BrowserClient.Response ok = request(s, m);
        assertThat(ok.status()).as(ok.body()).isEqualTo(201);
        String execution = JSON.readTree(ok.body()).get("remoteExecutionId").asString();
        String attempt = JSON.readTree(ok.body()).get("attempts").get(0).get("attemptId").asString();

        // ---- D3: a validade pedida (30 dias) e limitada ao fim da cobertura (10 dias)
        BrowserClient.Response capped = authorizeUntil(s, m2, 30);
        assertThat(capped.status()).as(capped.body()).isEqualTo(201);
        assertThat(value("SELECT valid_until = " + periodEnd(license, 1) + " FROM local_run_authorizations WHERE authorization_id = '"
                + capped.field("authorizationId") + "'")).as("a autorizacao nao passa do fim da licenca").isEqualTo("t");
        // renovacao CONTIGUA (comeca onde o periodo 1 termina, vai ate 40 dias): a cobertura segue ate o fim dela
        LicenseFixtures.renew(org, license, 2, periodEnd(license, 1), "clock_timestamp() + interval '40 days'");
        // e uma renovacao DEPOIS DE UMA LACUNA (comeca em 60 dias): nao estende a autorizacao
        LicenseFixtures.renew(org, license, 3, "clock_timestamp() + interval '60 days'", "clock_timestamp() + interval '120 days'");
        Machine m3 = machine(s, "Folha", true);
        heartbeat(s, m3, true);
        BrowserClient.Response chained = authorizeUntil(s, m3, 100);
        assertThat(chained.status()).as(chained.body()).isEqualTo(201);
        assertThat(value("SELECT valid_until = " + periodEnd(license, 2) + " FROM local_run_authorizations WHERE authorization_id = '"
                + chained.field("authorizationId") + "'")).as("D3: para no fim do periodo contiguo; nunca atravessa a lacuna").isEqualTo("t");
        // o banco reconfere a validade: alem da cobertura, recusa
        Machine m4 = machine(s, "Estoque", true);
        heartbeat(s, m4, true);
        assertThat(as("nekkora_platform", org, "INSERT INTO local_run_authorizations (organization_id, workspace_id, worker_id, worker_workspace_assignment_id, "
                + "automation_id, automation_version_id, package_fingerprint, package_fingerprint_rule, source_kind, granted_by_membership_id, valid_until) SELECT '"
                + org + "', '" + s.workspace() + "', '" + m4.worker() + "', assignment_id, '" + s.automation() + "', '" + s.version() + "', '" + FINGERPRINT + "', '"
                + EXTERNAL + "', 2, '" + s.org().adminMembership() + "', clock_timestamp() + interval '50 days' FROM worker_workspace_assignments WHERE worker_id = '"
                + m4.worker() + "' AND workspace_id = '" + s.workspace() + "'")).as("validade dentro da lacuna").isEqualTo("23514");

        // ---- S2: sem autorizacao da versao em m4. Sem o aceite, recusa; com o aceite explicito, autoriza e solicita no mesmo passo
        assertThat(missing(s, m4)).containsExactly("AUTHORIZATION_MISSING");
        refused(request(s, m4), "remote_authorization_missing");
        refused(s.admin().post(s.requests(), body(s, m4, UUID.randomUUID()).replace("}", ",\"authorizeVersion\":false}")), "remote_authorization_missing");
        String before = value("SELECT count(*) FROM agent_admin_events WHERE organization_id = '" + org + "' AND act = 10");
        BrowserClient.Response accepted = s.admin().post(s.requests(), body(s, m4, UUID.randomUUID()).replace("}", ",\"authorizeVersion\":true}"));
        assertThat(accepted.status()).as(accepted.body()).isEqualTo(201);
        assertThat(value("SELECT automation_version_id || '|' || granted_by_membership_id || '|' || (valid_until = " + periodEnd(license, 2)
                + ") FROM local_run_authorizations WHERE organization_id = '" + org + "' AND worker_id = '" + m4.worker() + "'"))
                .as("S2: a autorizacao e DESTA versao, de quem aceitou, e respeita a cobertura").isEqualTo(s.version() + "|" + s.org().adminMembership() + "|true");
        assertThat(value("SELECT count(*) FROM agent_admin_events WHERE organization_id = '" + org + "' AND act = 10"))
                .as("S2: o aceite fica na trilha como ato proprio").isEqualTo(String.valueOf(Integer.parseInt(before) + 1));
        assertThat(value("SELECT actor_membership_id || '|' || worker_id FROM agent_admin_events WHERE organization_id = '" + org
                + "' AND act = 10 ORDER BY created_at DESC LIMIT 1")).isEqualTo(s.org().adminMembership() + "|" + m4.worker());
        // S2 nao da o que a pessoa nao tem: quem so le o ambiente nao autoriza nem solicita
        TestUser viewer = internalUser(s.org(), true, 2);
        grantWorkspaceRole(s.org(), viewer, s.workspace(), "VIEWER");
        BrowserClient reader = browser();
        firstLogin(reader, org, viewer);
        assertThat(reader.post(s.requests(), body(s, m3, UUID.randomUUID()).replace("}", ",\"authorizeVersion\":true}")).status()).isIn(403, 404);
        assertThat(value("SELECT count(*) FROM local_run_authorizations WHERE worker_id = '" + m3.worker() + "'")).as("nenhuma autorizacao a mais em m3").isEqualTo("1");

        // ---- a revogacao continua valendo
        DB.asSuperuser("SET session_replication_role = replica", "UPDATE local_run_authorizations SET revoked_at = clock_timestamp(), revoked_by_membership_id = '"
                + s.org().adminMembership() + "' WHERE authorization_id = '" + capped.field("authorizationId") + "'");
        assertThat(missing(s, m2)).as("S1 nao mexe na revogacao").containsExactly("AUTHORIZATION_REVOKED");
        refused(request(s, m2), "remote_authorization_revoked");

        // ---- a licenca vence. A tentativa de m ja esta em execucao; a de m4 ainda nao comecou
        machineMoves(s, attempt, 2, 3, 4, 5, 6);
        String waiting = JSON.readTree(accepted.body()).get("attempts").get(0).get("attemptId").asString();
        LicenseFixtures.endPeriodNow(license, 1);
        DB.asSuperuser("SET session_replication_role = replica", "DELETE FROM organization_license_periods WHERE license_id = '" + license + "' AND period_number > 1");
        assertThat(missing(s, m3)).containsExactly("LICENSE_EXPIRED");
        refused(request(s, m3), "license_expired");
        refused(authorizeUntil(s, m2, 5), "license_expired");
        // antes de INICIAR, a revalidacao do servidor diz que a licenca venceu (a tentativa que ainda nao comecou nao comeca)
        assertThat(valueIn("nekkora_ingestion", org, "SELECT public.remote_attempt_blocker('" + org + "', '" + accepted.field("remoteExecutionId") + "', '"
                + m4.agent() + "', '" + m4.worker() + "', authorization_id) FROM remote_attempts WHERE attempt_id = '" + waiting + "'"))
                .as("validacao da licenca antes de autorizar o inicio").isEqualTo("LICENSE_EXPIRED");
        // o que ja estava em execucao termina e entrega; o historico continua legivel
        machineMoves(s, attempt, 7);
        JsonNode done = JSON.readTree(s.admin().get(s.o() + "/remote-executions/" + execution).body());
        assertThat(done.get("attempts").get(0).get("state").asString()).as("a execucao admitida na validade conclui depois do vencimento").isEqualTo("SUCCEEDED");
        assertThat(s.admin().get(s.requests()).status()).as("historico preservado").isEqualTo(200);
        // e uma nova tentativa da mesma solicitacao e trabalho novo: recusada
        refused(s.admin().post(s.o() + "/remote-executions/" + execution + "/attempts", "{\"agentId\":\"" + m3.agent() + "\"}"), "license_expired");

        // ---- outra organizacao, no modelo anterior, nao e afetada pela licenca desta
        assertThat(value("SELECT public.license_state('" + newOrganization().id() + "', '" + s.automation() + "')")).isEqualTo("LEGACY");
    }

    private static String valueIn(String role, UUID org, String sql) throws Exception {
        try (Connection c = DB.connectAs(role)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT set_config('nekkora.organization_id', '" + org + "', true)");
                try (ResultSet rs = st.executeQuery(sql)) {
                    return rs.next() ? rs.getString(1) : null;
                }
            } finally {
                c.rollback();
            }
        }
    }

    // ------------------------------------------------------------------ PLATFORM-LICENCIAMENTO-001 (066, D2): capacidade tecnica

    /** Organizacao NOVA de verdade: sem limites gravados e aguardando ativacao, como nasce pelo cadastro. */
    private Setup bare() {
        Org o = newOrganization();
        BrowserClient b = browser();
        firstLogin(b, o.id(), new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray()));
        Setup s = new Setup(o, b, newWorkspace(o, newClient(o)), null, null);
        stepUp(s);
        return s;
    }

    private BrowserClient.Response enroll(Setup s, String name) {
        return s.admin().post(s.o() + "/agent-enrollments", "{\"workerName\":\"" + name + "\",\"agentName\":\"Maquina " + name
                + "\",\"expiresInMinutes\":15,\"remoteExecution\":true}");
    }

    private static List<String> usage(JsonNode capacity, String key) {
        return List.of(capacity.get(key).get("used").asString(), capacity.get(key).get("limit").isNull() ? "-" : capacity.get(key).get("limit").asString());
    }

    /**
     * D2: no modelo por licenca a organizacao registra maquina SEM a ativacao do operador, e os limites de capacidade sao
     * tecnicos (50 Workers, 5 em andamento, 50 pendentes), configuraveis e separados das licencas. Bater num limite e recusa de
     * capacidade - nao de licenca - e nao consome licenca nenhuma. No modelo anterior, tudo como antes.
     */
    @Test
    void capacidadeTecnicaSubstituiAAtivacaoNoModeloPorLicencaESeparadaDasLicencas() throws Exception {
        // ---- modelo anterior: organizacao aguardando ativacao nao registra Worker
        Setup waiting = bare();
        BrowserClient.Response blocked = enroll(waiting, "Antes");
        assertThat(blocked.status()).as(blocked.body()).isEqualTo(409);
        assertThat(blocked.body()).contains("worker_limit_reached");
        JsonNode legacy = JSON.readTree(waiting.admin().get(waiting.o() + "/capacity").body());
        assertThat(legacy.get("model").asString()).isEqualTo("legacy");
        assertThat(usage(legacy, "workers")).as("modelo anterior: limites pela ativacao, nao por aqui").containsExactly("0", "-");

        // ---- modelo por licenca: registra sem ativacao, ate o limite tecnico
        LicenseFixtures.enforce(waiting.org().id(), waiting.org().adminId());
        assertThat(enroll(waiting, "Depois").status()).as("sem ato de ativacao").isEqualTo(201);
        assertThat(value("SELECT state FROM ops_activation.organization_states WHERE organization_id = '" + waiting.org().id() + "'"))
                .as("a organizacao continua SEM ativacao: nada foi ativado por baixo").isEqualTo("1");
        JsonNode capacity = JSON.readTree(waiting.admin().get(waiting.o() + "/capacity").body());
        assertThat(capacity.get("model").asString()).isEqualTo("licensed");
        assertThat(usage(capacity, "workers")).containsExactly("1", "50");
        assertThat(usage(capacity, "concurrentExecutions")).containsExactly("0", "5");
        assertThat(usage(capacity, "pendingExecutions")).containsExactly("0", "50");
        // o limite e CONFIGURAVEL (procedimento do migrador): com 2, o terceiro Worker e recusado
        DB.asSuperuser("UPDATE technical_capacity_limits SET limit_value = 2, updated_by = 'teste de capacidade' WHERE resource_code = 1");
        try {
            assertThat(enroll(waiting, "Segundo").status()).isEqualTo(201);
            BrowserClient.Response third = enroll(waiting, "Terceiro");
            assertThat(third.status()).isEqualTo(409);
            assertThat(third.body()).contains("worker_limit_reached");
        } finally {
            DB.asSuperuser("UPDATE technical_capacity_limits SET limit_value = 50, updated_by = 'teste de capacidade (restaurado)' WHERE resource_code = 1");
        }
        // quem nao administra nao le a capacidade; outra organizacao nao le a desta
        assertThat(setup().admin().get(waiting.o() + "/capacity").status()).isIn(401, 403, 404);

        // ---- execucao remota: limites de pendentes e de em andamento, com motivo proprio
        Setup s = setup();
        UUID org = s.org().id();
        declare(s, null);
        LicenseFixtures.enforce(org, s.org().adminId());
        UUID license = LicenseFixtures.license(org, 1, "clock_timestamp() - interval '1 day'", "clock_timestamp() + interval '30 days'");
        LicenseFixtures.assign(org, license, s.workspace(), s.automation(), s.org().adminMembership());
        Machine a = machine(s, "A", true);
        Machine b = machine(s, "B", true);
        Machine c = machine(s, "C", true);
        for (Machine m : List.of(a, b, c)) {
            heartbeat(s, m, true);
        }
        String accept = ",\"authorizeVersion\":true}";
        DB.asSuperuser("UPDATE technical_capacity_limits SET limit_value = 1, updated_by = 'teste de capacidade' WHERE resource_code IN (2, 3)");
        try {
            BrowserClient.Response first = s.admin().post(s.requests(), body(s, a, UUID.randomUUID()).replace("}", accept));
            assertThat(first.status()).as(first.body()).isEqualTo(201);
            // 1 pendente (limite 1): a segunda solicitacao e recusada por CAPACIDADE
            refused(s.admin().post(s.requests(), body(s, b, UUID.randomUUID()).replace("}", accept)), "capacity_pending_limit_reached");
            assertThat(value("SELECT count(*) FROM remote_executions WHERE organization_id = '" + org + "'")).as("nada gravado").isEqualTo("1");
            // o banco reconfere: a tentativa direta tambem e recusada
            assertThat(usage(JSON.readTree(s.admin().get(s.o() + "/capacity").body()), "pendingExecutions")).containsExactly("1", "1");
            // a primeira passa a executar: sai das pendentes, entra nas em andamento (limite 1)
            machineMoves(s, JSON.readTree(first.body()).get("attempts").get(0).get("attemptId").asString(), 2, 3, 4, 5, 6);
            refused(s.admin().post(s.requests(), body(s, b, UUID.randomUUID()).replace("}", accept)), "capacity_concurrent_limit_reached");
            JsonNode full = JSON.readTree(s.admin().get(s.o() + "/capacity").body());
            assertThat(usage(full, "concurrentExecutions")).containsExactly("1", "1");
            assertThat(usage(full, "pendingExecutions")).containsExactly("0", "1");
            // CAPACIDADE NAO E LICENCA: a conta de licencas e a mesma, e a automacao continua com direito
            JsonNode licenses = JSON.readTree(s.admin().get(s.o() + "/licenses/summary").body());
            assertThat(List.of(licenses.get("contracted").asLong(), licenses.get("used").asLong(), licenses.get("available").asLong()))
                    .as("bater no limite de capacidade nao consome licenca").containsExactly(1L, 1L, 0L);
            assertThat(value("SELECT public.license_state('" + org + "', '" + s.automation() + "')")).isEqualTo("OK");
            assertThat(missing(s, b)).as("a maquina B nao tem pendencia de licenca").doesNotContain("LICENSE_REQUIRED", "LICENSE_EXPIRED");
            // terminou: volta a caber
            machineMoves(s, JSON.readTree(first.body()).get("attempts").get(0).get("attemptId").asString(), 7);
            assertThat(s.admin().post(s.requests(), body(s, b, UUID.randomUUID()).replace("}", accept)).status()).isEqualTo(201);
        } finally {
            DB.asSuperuser("UPDATE technical_capacity_limits SET limit_value = 5, updated_by = 'teste de capacidade (restaurado)' WHERE resource_code = 2",
                    "UPDATE technical_capacity_limits SET limit_value = 50, updated_by = 'teste de capacidade (restaurado)' WHERE resource_code = 3");
        }
        assertThat(value("SELECT string_agg(resource_code || '=' || limit_value, ',' ORDER BY resource_code) FROM technical_capacity_limits"))
                .as("valores decididos: 50 Workers, 5 simultaneas, 50 pendentes").isEqualTo("1=50,2=5,3=50");
        // o papel do servico nao altera os limites
        assertThat(as("nekkora_platform", org, "UPDATE technical_capacity_limits SET limit_value = 9999 WHERE resource_code = 1")).isEqualTo("42501");
    }
}
