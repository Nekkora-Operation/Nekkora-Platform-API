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
@TestPropertySource(properties = {"nekkora.remote-execution.enabled=true", "nekkora.resultados.dir=${java.io.tmpdir}/nekkora-platform-it-resultados"})
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
}
