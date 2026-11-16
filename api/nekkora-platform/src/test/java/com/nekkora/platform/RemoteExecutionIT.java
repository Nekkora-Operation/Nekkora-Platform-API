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
@TestPropertySource(properties = "nekkora.remote-execution.enabled=true")
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

    private Setup setup() {
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
                        + "'nekkora-agent/package-fingerprint/1','fingerprint','" + FINGERPRINT + "'),'runtime','python3.12','entrypoint','main.py',"
                        + "'arguments','[]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb), '" + o.adminMembership() + "')",
                "UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '" + o.adminMembership()
                        + "' WHERE automation_version_id = '" + version + "'");
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
        return new Machine(worker, UUID.fromString(value("SELECT agent_id FROM agents WHERE organization_id = '" + org + "' AND name = '" + agentName + "'")), agentName);
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
