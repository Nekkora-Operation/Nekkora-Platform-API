package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.core.testsupport.BrowserClient;

/**
 * AGENT-PYTHON-001 (030), lado das PESSOAS, pelo papel real nekkora_platform: autorizacao de MONITORAMENTO de Python externo
 * (sem versao e sem impressao), leitura das execucoes com a origem explicita e o pedido de execucao (criar com step-up,
 * idempotencia pela chave, recusas, revogacao so antes da decisao, vencimento lido como EXPIRED). O lado da maquina e escrito
 * com o papel REAL nekkora_ingestion por SQL (o gateway tem os proprios testes); os gatilhos da 030 valem igual.
 */
class ExternalPythonRunRequestIT extends PlatformTestBase {

    static final String FINGERPRINT = "5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d";

    private record Setup(Org org, BrowserClient admin, UUID workspace, UUID automation, UUID version, UUID worker, UUID agent) {
        String o() {
            return "/api/v1/o/" + org.id();
        }
    }

    private Setup setup() throws Exception {
        Org o = newOrganization();
        for (int code = 1; code <= 5; code++) {
            DB.asSuperuser("INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, "
                    + "limit_value, provisioned_by_user_id) VALUES ('" + o.id() + "', " + code + ", 1, now(), 2, NULL, '" + o.adminId() + "')");
        }
        UUID ws = newWorkspace(o, newClient(o));
        UUID automation = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO automations (automation_id, organization_id, workspace_id, name, status, created_by_membership_id) VALUES ('" + automation
                        + "', '" + o.id() + "', '" + ws + "', 'Relatorio CSV', 1, '" + o.adminMembership() + "')",
                "INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id) "
                        + "VALUES ('" + version + "', '" + o.id() + "', '" + ws + "', '" + automation + "', 1, jsonb_build_object('contract_version',1,'artifact',"
                        + "jsonb_build_object('reference','relatorio-python','sha256','" + "ab".repeat(32) + "'),'agent_package',jsonb_build_object('fingerprint_rule',"
                        + "'nekkora-agent/package-fingerprint/1','fingerprint','" + FINGERPRINT + "'),'runtime','python3.12','entrypoint','main.py',"
                        + "'arguments','[]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb), '" + o.adminMembership() + "')",
                "UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '" + o.adminMembership()
                        + "' WHERE automation_version_id = '" + version + "'");
        UUID worker = UUID.fromString(value("INSERT INTO workers (organization_id, name, status) VALUES ('" + o.id() + "', 'W-py', 1) RETURNING worker_id"));
        DB.asSuperuser("INSERT INTO worker_workspace_assignments (organization_id, worker_id, workspace_id, status, authorized_by_membership_id) VALUES ('" + o.id()
                + "', '" + worker + "', '" + ws + "', 1, '" + o.adminMembership() + "')");
        UUID agent = agentOn(o, worker, "Estacao py");
        BrowserClient b = browser();
        firstLogin(b, o.id(), new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray()));
        return new Setup(o, b, ws, automation, version, worker, agent);
    }

    private void stepUp(Setup s) {
        assertThat(s.admin().post(s.o() + "/auth/mfa/step-up", "{\"code\":\"" + code(s.org().adminId()) + "\"}").status()).isEqualTo(204);
    }

    // =========================================================================== M1: monitoramento

    @Test
    void monitoramentoAutorizaAAutomacaoSemVersaoNemImpressaoEOCodigoNuncaChegaAPlatform() throws Exception {
        Setup s = setup();
        String url = s.o() + "/workspaces/" + s.workspace() + "/local-run-authorizations";
        String req = "{\"sourceKind\":\"EXTERNAL_MONITORED\",\"workerId\":\"" + s.worker() + "\",\"automationId\":\"" + s.automation() + "\",\"validUntil\":\""
                + future(30) + "\"}";
        assertThat(s.admin().post(url, req).status()).as("sem step-up").isEqualTo(403);
        stepUp(s);
        assertThat(s.admin().post(url, req.replace("\"sourceKind\":\"EXTERNAL_MONITORED\",", "\"sourceKind\":\"EXTERNAL_MONITORED\",\"automationVersionId\":\""
                + s.version() + "\",")).status()).as("monitoramento com versao").isEqualTo(400);
        assertThat(s.admin().post(url, "{\"sourceKind\":\"STUDIO_PACKAGE\",\"workerId\":\"" + s.worker() + "\",\"automationVersionId\":\"" + s.version()
                + "\",\"validUntil\":\"" + future(30) + "\"}").status()).as("origem de pacote vem da versao, nunca do corpo").isEqualTo(400);
        BrowserClient.Response a = s.admin().post(url, req);
        assertThat(a.status()).as(a.body()).isEqualTo(201);
        assertThat(a.field("sourceKind")).isEqualTo("EXTERNAL_MONITORED");
        assertThat(a.body()).contains("\"automationVersionId\":null").contains("\"packageFingerprint\":null").contains("\"packageFingerprintRule\":null");
        UUID authorization = UUID.fromString(a.field("authorizationId"));
        // a autorizacao de pacote existente continua igual (source_kind 1, regra /1)
        BrowserClient.Response pkg = s.admin().post(url, "{\"workerId\":\"" + s.worker() + "\",\"automationVersionId\":\"" + s.version() + "\",\"validUntil\":\""
                + future(30) + "\"}");
        assertThat(pkg.status()).as(pkg.body()).isEqualTo(201);
        assertThat(pkg.field("sourceKind")).isEqualTo("STUDIO_PACKAGE");
        assertThat(pkg.field("packageFingerprintRule")).isEqualTo("nekkora-agent/package-fingerprint/1");

        // banco: monitoramento com impressao inventada e recusado mesmo por quem grava direto com o papel da aplicacao
        assertThat(asPlatform(s.org().id(), "INSERT INTO local_run_authorizations (organization_id, workspace_id, worker_id, worker_workspace_assignment_id, "
                + "automation_id, automation_version_id, package_fingerprint, package_fingerprint_rule, source_kind, granted_by_membership_id, valid_until) "
                + "SELECT organization_id, workspace_id, worker_id, worker_workspace_assignment_id, automation_id, NULL, '" + FINGERPRINT + "', NULL, 3, "
                + "granted_by_membership_id, now() + interval '1 day' FROM local_run_authorizations WHERE authorization_id = '" + authorization + "'"))
                .isEqualTo("23514");

        // a maquina registra a execucao sem impressao (papel real da maquina) e a leitura mostra origem, automacao e maquina
        String exec = "ex-" + "1a".repeat(16);
        assertThat(asIngestion(s.org().id(), "INSERT INTO agent_runs (organization_id, workspace_id, automation_id, automation_version_id, authorization_id, "
                + "agent_id, worker_id, worker_agent_assignment_id, agent_execution_id, package_fingerprint, authorization_state_at_registration, supervisor_state, "
                + "source_kind) SELECT z.organization_id, z.workspace_id, z.automation_id, NULL, z.authorization_id, '" + s.agent() + "', z.worker_id, "
                + "a.assignment_id, '" + exec + "', NULL, 1, 3, 3 FROM local_run_authorizations z JOIN worker_agent_assignments a ON a.worker_id = z.worker_id "
                + "AND a.status = 1 WHERE z.authorization_id = '" + authorization + "'")).isEqualTo("OK");
        // impressao inventada num registro de monitoramento: recusada no banco
        assertThat(asIngestion(s.org().id(), "INSERT INTO agent_runs (organization_id, workspace_id, automation_id, automation_version_id, authorization_id, "
                + "agent_id, worker_id, worker_agent_assignment_id, agent_execution_id, package_fingerprint, authorization_state_at_registration, supervisor_state, "
                + "source_kind) SELECT z.organization_id, z.workspace_id, z.automation_id, '" + s.version() + "', z.authorization_id, '" + s.agent()
                + "', z.worker_id, a.assignment_id, 'ex-" + "2b".repeat(16) + "', '" + FINGERPRINT + "', 1, 3, 3 FROM local_run_authorizations z "
                + "JOIN worker_agent_assignments a ON a.worker_id = z.worker_id AND a.status = 1 WHERE z.authorization_id = '" + authorization + "'"))
                .isEqualTo("23514");
        // terminal com o codigo do monitoramento (codigo 0 sem sinal de negocio)
        assertThat(asIngestion(s.org().id(), "UPDATE agent_runs SET supervisor_state = 4, supervisor_code = 'AGT-EXT-001', exit_code = 0, agent_started_at = now(), "
                + "agent_ended_at = now() WHERE agent_execution_id = '" + exec + "'")).isEqualTo("OK");
        // identidade imutavel com colunas agora anulaveis: NULL -> valor recusado (a 025 usava "<>", que deixaria passar)
        assertThat(asIngestion(s.org().id(), "UPDATE agent_runs SET package_fingerprint = '" + FINGERPRINT + "' WHERE agent_execution_id = '" + exec + "'"))
                .isEqualTo("23514");
        BrowserClient.Response runs = s.admin().get(s.o() + "/agent-runs?workspaceId=" + s.workspace());
        assertThat(runs.status()).as(runs.body()).isEqualTo(200);
        assertThat(runs.body()).contains("\"sourceKind\":\"EXTERNAL_MONITORED\"").contains("\"origin\":\"LOCAL_AUTHORIZED\"")
                .contains("\"automationName\":\"Relatorio CSV\"").contains("\"agentName\":\"Estacao py\"").contains("\"code\":\"AGT-EXT-001\"")
                .contains("\"automationVersionId\":null");
    }

    // =========================================================================== M2: pedido de execucao

    @Test
    void pedidoExigeStepUpAutorizacaoDePacoteEAgentDoWorkerEERepetivelPelaChave() throws Exception {
        Setup s = setup();
        stepUp(s);
        UUID pkgAuth = authorizePackage(s);
        UUID monitored = authorizeMonitored(s);
        String url = s.o() + "/workspaces/" + s.workspace() + "/run-requests";
        String key = UUID.randomUUID().toString();
        String body = "{\"authorizationId\":\"" + pkgAuth + "\",\"agentId\":\"" + s.agent() + "\",\"validForSeconds\":600,\"requestKey\":\"" + key + "\"}";

        assertThat(s.admin().post(url, body.replace(pkgAuth.toString(), monitored.toString())).status()).as("monitoramento nao se dispara").isEqualTo(409);
        UUID otherWorker = UUID.fromString(value("INSERT INTO workers (organization_id, name, status) VALUES ('" + s.org().id() + "', 'W-outro', 1) RETURNING worker_id"));
        UUID otherAgent = agentOn(s.org(), otherWorker, "Estacao outra");
        assertThat(s.admin().post(url, body.replace(s.agent().toString(), otherAgent.toString())).status()).as("agent de outro worker").isEqualTo(409);
        assertThat(s.admin().post(url, body.replace("600", "30")).status()).as("validade abaixo de 60 s").isEqualTo(400);
        assertThat(s.admin().post(url, body.replace("600", "90000")).status()).as("validade acima de 24 h").isEqualTo(400);

        BrowserClient.Response r = s.admin().post(url, body);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        assertThat(r.field("state")).isEqualTo("REQUESTED");
        assertThat(r.body()).contains("\"run\":null").contains("\"sourceKind\":\"STUDIO_PACKAGE\"").contains("\"agentName\":\"Estacao py\"")
                .doesNotContain("main.py").doesNotContain("command");
        String id = r.field("runRequestId");
        // resposta perdida: a mesma chave devolve o mesmo pedido, sem segundo ato
        BrowserClient.Response again = s.admin().post(url, body);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.field("runRequestId")).isEqualTo(id);
        assertThat(count("SELECT count(*) FROM run_requests WHERE organization_id = '" + s.org().id() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM agent_admin_events WHERE organization_id = '" + s.org().id() + "' AND act = 12")).isEqualTo(1);
        // mesma chave com outro conteudo: 409
        assertThat(s.admin().post(url, body.replace("600", "700").replace(pkgAuth.toString(), pkgAuth.toString()).replace("\"agentId\":\"" + s.agent(),
                "\"agentId\":\"" + otherAgent)).status()).isEqualTo(409);

        // a validade conta do instante do servidor
        long span = Long.parseLong(value("SELECT round(extract(epoch FROM valid_until - requested_at)) FROM run_requests WHERE run_request_id = '" + id + "'"));
        assertThat(span).isEqualTo(600);

        // leitura e lista
        BrowserClient.Response get = s.admin().get(s.o() + "/run-requests/" + id);
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.field("state")).isEqualTo("REQUESTED");
        assertThat(s.admin().get(url).body()).contains(id);

        // a pessoa sem automation.execute nao cria nem revoga; a maquina nao cria pedido
        assertThat(asIngestion(s.org().id(), "INSERT INTO run_requests (organization_id, workspace_id, automation_id, automation_version_id, authorization_id, "
                + "worker_id, agent_id, source_kind, package_fingerprint, package_fingerprint_rule, request_key, requested_by_membership_id, valid_until) SELECT "
                + "organization_id, workspace_id, automation_id, automation_version_id, authorization_id, worker_id, agent_id, source_kind, package_fingerprint, "
                + "package_fingerprint_rule, gen_random_uuid(), requested_by_membership_id, now() + interval '5 minutes' FROM run_requests WHERE run_request_id = '"
                + id + "'")).isEqualTo("42501");
    }

    @Test
    void revogacaoSoAntesDaDecisaoEMaquinaDecideSoOQueEDela() throws Exception {
        Setup s = setup();
        stepUp(s);
        UUID pkgAuth = authorizePackage(s);
        String url = s.o() + "/workspaces/" + s.workspace() + "/run-requests";
        String first = create(s, url, pkgAuth);
        String second = create(s, url, pkgAuth);

        // revogar antes da decisao: 200 REVOKED, auditado; de novo: 409
        BrowserClient.Response rev = s.admin().post(s.o() + "/run-requests/" + first + "/revoke", null);
        assertThat(rev.status()).as(rev.body()).isEqualTo(200);
        assertThat(rev.field("state")).isEqualTo("REVOKED");
        assertThat(s.admin().post(s.o() + "/run-requests/" + first + "/revoke", null).status()).isEqualTo(409);
        assertThat(count("SELECT count(*) FROM agent_admin_events WHERE organization_id = '" + s.org().id() + "' AND act = 13")).isEqualTo(1);
        // a maquina nao aceita um pedido revogado (gatilho: transicao nao permitida)
        assertThat(asIngestion(s.org().id(), "UPDATE run_requests SET state = 3, agent_execution_id = 'ex-" + "3c".repeat(16) + "' WHERE run_request_id = '"
                + first + "'")).isEqualTo("23514");

        // a maquina aceita o segundo (entregue e aceito); depois disso a revogacao e 409 e o estado nao muda
        assertThat(asIngestion(s.org().id(), "UPDATE run_requests SET state = 2, deliveries = 1 WHERE run_request_id = '" + second + "'",
                "UPDATE run_requests SET state = 3, agent_execution_id = 'ex-" + "4d".repeat(16) + "' WHERE run_request_id = '" + second + "'")).isEqualTo("OK");
        assertThat(s.admin().post(s.o() + "/run-requests/" + second + "/revoke", null).status()).isEqualTo(409);
        BrowserClient.Response accepted = s.admin().get(s.o() + "/run-requests/" + second);
        assertThat(accepted.field("state")).isEqualTo("ACCEPTED");
        assertThat(accepted.body()).contains("\"run\":null"); // aceitar nao prova inicio
        // decisao imutavel: outro agentExecutionId recusado
        assertThat(asIngestion(s.org().id(), "UPDATE run_requests SET agent_execution_id = 'ex-" + "5e".repeat(16) + "' WHERE run_request_id = '" + second + "'"))
                .isEqualTo("23514");
        // registro pelo pedido: so com o agentExecutionId aceito; o pedido passa a RUN_LINKED e a leitura traz o desfecho do supervisor
        String reg = "INSERT INTO agent_runs (organization_id, workspace_id, automation_id, automation_version_id, authorization_id, agent_id, worker_id, "
                + "worker_agent_assignment_id, agent_execution_id, package_fingerprint, authorization_state_at_registration, supervisor_state, source_kind, origin, "
                + "run_request_id) SELECT q.organization_id, q.workspace_id, q.automation_id, q.automation_version_id, q.authorization_id, q.agent_id, q.worker_id, "
                + "a.assignment_id, '%s', q.package_fingerprint, 1, 3, q.source_kind, 2, q.run_request_id FROM run_requests q JOIN worker_agent_assignments a "
                + "ON a.agent_id = q.agent_id AND a.status = 1 WHERE q.run_request_id = '" + second + "'";
        assertThat(asIngestion(s.org().id(), reg.formatted("ex-" + "6f".repeat(16)))).as("outra execucao").isEqualTo("23514");
        assertThat(asIngestion(s.org().id(), reg.formatted("ex-" + "4d".repeat(16)),
                "UPDATE run_requests SET state = 6, agent_run_id = (SELECT agent_run_id FROM agent_runs WHERE agent_execution_id = 'ex-" + "4d".repeat(16)
                        + "') WHERE run_request_id = '" + second + "'",
                "UPDATE agent_runs SET agent_started_at = now() WHERE agent_execution_id = 'ex-" + "4d".repeat(16) + "'")).isEqualTo("OK");
        BrowserClient.Response linked = s.admin().get(s.o() + "/run-requests/" + second);
        assertThat(linked.field("state")).isEqualTo("RUN_LINKED");
        assertThat(linked.body()).contains("\"state\":\"RUNNING\"").contains("\"process\":\"RUNNING\"").contains("\"outcome\":\"PENDING\"");
        BrowserClient.Response runs = s.admin().get(s.o() + "/agent-runs?workspaceId=" + s.workspace());
        assertThat(runs.body()).contains("\"origin\":\"PLATFORM_REQUEST\"").contains("\"runRequestId\":\"" + second + "\"");
    }

    @Test
    void pedidoVencidoSemDecisaoELidoComoExpiradoENaoRecebeDecisao() throws Exception {
        Setup s = setup();
        stepUp(s);
        UUID pkgAuth = authorizePackage(s);
        String url = s.o() + "/workspaces/" + s.workspace() + "/run-requests";
        String id = create(s, url, pkgAuth);
        // fixture: o pedido passa a ter o prazo no passado (so os instantes; gatilhos desligados SO nesta conexao de superusuario)
        DB.asSuperuser("SET session_replication_role = replica",
                "UPDATE run_requests SET requested_at = now() - interval '20 minutes', valid_until = now() - interval '5 minutes' WHERE run_request_id = '" + id + "'",
                "SET session_replication_role = origin");
        BrowserClient.Response get = s.admin().get(s.o() + "/run-requests/" + id);
        assertThat(get.field("state")).isEqualTo("EXPIRED");
        assertThat(s.admin().post(s.o() + "/run-requests/" + id + "/revoke", null).status()).isEqualTo(409);
        assertThat(asIngestion(s.org().id(), "UPDATE run_requests SET state = 3, agent_execution_id = 'ex-" + "7a".repeat(16) + "' WHERE run_request_id = '"
                + id + "'")).as("aceitar depois do prazo").isEqualTo("23514");
        assertThat(asIngestion(s.org().id(), "UPDATE run_requests SET state = 7 WHERE run_request_id = '" + id + "'")).isEqualTo("OK");
        assertThat(value("SELECT expired_at IS NOT NULL FROM run_requests WHERE run_request_id = '" + id + "'")).isEqualTo("t");
    }

    // =========================================================================== apoio

    private String create(Setup s, String url, UUID authorization) {
        BrowserClient.Response r = s.admin().post(url, "{\"authorizationId\":\"" + authorization + "\",\"agentId\":\"" + s.agent()
                + "\",\"validForSeconds\":600,\"requestKey\":\"" + UUID.randomUUID() + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.field("runRequestId");
    }

    private UUID authorizePackage(Setup s) {
        BrowserClient.Response a = s.admin().post(s.o() + "/workspaces/" + s.workspace() + "/local-run-authorizations", "{\"workerId\":\"" + s.worker()
                + "\",\"automationVersionId\":\"" + s.version() + "\",\"validUntil\":\"" + future(30) + "\"}");
        assertThat(a.status()).as(a.body()).isEqualTo(201);
        return UUID.fromString(a.field("authorizationId"));
    }

    private UUID authorizeMonitored(Setup s) {
        BrowserClient.Response a = s.admin().post(s.o() + "/workspaces/" + s.workspace() + "/local-run-authorizations", "{\"sourceKind\":\"EXTERNAL_MONITORED\","
                + "\"workerId\":\"" + s.worker() + "\",\"automationId\":\"" + s.automation() + "\",\"validUntil\":\"" + future(30) + "\"}");
        assertThat(a.status()).as(a.body()).isEqualTo(201);
        return UUID.fromString(a.field("authorizationId"));
    }

    private static String future(int days) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(days).withNano(0).toString();
    }

    /** Agent ativo com credencial e associacao ao Worker (fixture declarada, como superusuario). */
    private static UUID agentOn(Org o, UUID worker, String name) throws Exception {
        UUID agent = UUID.fromString(value("INSERT INTO agents (organization_id, name, agent_version, status) VALUES ('" + o.id() + "', '" + name + "', '0.1.0', 2) "
                + "RETURNING agent_id"));
        DB.asSuperuser("INSERT INTO worker_agent_assignments (organization_id, worker_id, agent_id, status, assigned_by_membership_id) VALUES ('" + o.id() + "', '"
                        + worker + "', '" + agent + "', 1, '" + o.adminMembership() + "')",
                "INSERT INTO agent_credentials (organization_id, agent_id, credential_hash, issued_by_membership_id, expires_at) VALUES ('" + o.id() + "', '" + agent
                        + "', repeat('c', 64), '" + o.adminMembership() + "', now() + interval '90 days')");
        return agent;
    }

    private static String asIngestion(UUID org, String... sqls) {
        return asRole("nekkora_ingestion", org, sqls);
    }

    private static String asPlatform(UUID org, String... sqls) {
        return asRole("nekkora_platform", org, sqls);
    }

    /** Executa como o papel REAL, no contexto da organizacao; devolve "OK" ou o SQLSTATE da recusa. */
    private static String asRole(String role, UUID org, String... sqls) {
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

    private static long count(String sql) throws Exception {
        String v = value(sql);
        return v == null ? 0 : Long.parseLong(v);
    }
}
