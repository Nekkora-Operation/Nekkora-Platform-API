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
import com.nekkora.core.token.Tokens;

/**
 * Lado das PESSOAS do canal do Agent (contrato Platform-Agent 1.0, 025) pelo papel real nekkora_platform: codigos de
 * pareamento e rotacao (permissoes, step-up, auditoria sem o codigo), Worker no workspace, autorizacao de execucao local,
 * revogacao do Agent e consultas das execucoes registradas (correlacao, quatro dimensoes, etapas por sequence, isolamento
 * por workspace). O lado da maquina e escrito com o papel REAL nekkora_ingestion por SQL (simulando o gateway, que tem os
 * proprios testes): os gatilhos da 025 valem igual.
 */
class AgentAdminIT extends PlatformTestBase {

    static final String FINGERPRINT = "5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d";

    private record Setup(Org org, BrowserClient admin, UUID workspace, UUID automation, UUID version) {
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
                        + "', '" + o.id() + "', '" + ws + "', 'Biografias', 1, '" + o.adminMembership() + "')",
                "INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id) "
                        + "VALUES ('" + version + "', '" + o.id() + "', '" + ws + "', '" + automation + "', 1, jsonb_build_object('contract_version',1,'artifact',"
                        + "jsonb_build_object('reference','biografias-python','sha256','" + "ab".repeat(32) + "'),'agent_package',jsonb_build_object('fingerprint_rule','nekkora-agent/package-fingerprint/1','fingerprint','" + FINGERPRINT + "'),'runtime','python3.12','entrypoint','main.py',"
                        + "'arguments','[]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb), '" + o.adminMembership() + "')",
                "UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '" + o.adminMembership()
                        + "' WHERE automation_version_id = '" + version + "'");
        BrowserClient b = browser();
        firstLogin(b, o.id(), new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray()));
        return new Setup(o, b, ws, automation, version);
    }

    private void stepUp(Setup s) {
        assertThat(s.admin().post("/api/v1/o/" + s.org().id() + "/auth/mfa/step-up", "{\"code\":\"" + code(s.org().adminId()) + "\"}").status()).isEqualTo(204);
    }

    // =========================================================================== codigos e revogacao

    @Test
    void codigoDePareamentoExigePermissoesEStepUpEAuditaSemOCodigo() throws Exception {
        Setup s = setup();
        String base = "/api/v1/o/" + s.org().id() + "/agent-enrollments";
        String body = "{\"workerName\":\"Estacao fiscal\",\"agentName\":\"Maquina 1\",\"expiresInMinutes\":15}";
        assertThat(s.admin().post(base, body).status()).as("sem step-up").isEqualTo(403);
        stepUp(s);
        assertThat(s.admin().post(base, "{\"agentName\":\"x\"}").status()).as("sem worker").isEqualTo(400);
        assertThat(s.admin().post(base, "{\"workerName\":\"W\",\"agentName\":\"x\",\"expiresInMinutes\":61}").status()).as("validade").isEqualTo(400);
        BrowserClient.Response r = s.admin().post(base, body);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        String code = r.field("code");
        assertThat(code).matches("nkp1\\." + s.org().id() + "\\.[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}");
        UUID enrollment = UUID.fromString(r.field("enrollmentId"));
        UUID worker = UUID.fromString(r.field("workerId"));
        assertThat(value("SELECT code_hash FROM agent_enrollments WHERE enrollment_id = '" + enrollment + "'")).isEqualTo(Tokens.sha256Hex(code));
        assertThat(count("SELECT count(*) FROM agent_admin_events WHERE organization_id = '" + s.org().id() + "' AND act IN (1, 7)")).isEqualTo(2);
        // o codigo (nem o segredo dele) nao fica em nenhuma tabela do canal
        String secret = code.substring(code.lastIndexOf('.') + 1);
        assertThat(count("SELECT count(*) FROM agent_enrollments e WHERE e::text LIKE '%" + secret + "%'")).isZero();
        assertThat(count("SELECT count(*) FROM agent_admin_events e WHERE e::text LIKE '%" + secret + "%'")).isZero();

        // Worker com Agent ativo: outro codigo para ele e recusado
        agentOn(s.org(), worker);
        assertThat(s.admin().post(base, "{\"workerId\":\"" + worker + "\",\"agentName\":\"Maquina 2\"}").status()).isEqualTo(409);
        // cancelar: uma vez
        BrowserClient.Response other = s.admin().post(base, "{\"workerName\":\"Outra\",\"agentName\":\"Maquina 3\"}");
        String cancel = base + "/" + other.field("enrollmentId") + "/cancel";
        assertThat(s.admin().post(cancel, null).status()).isEqualTo(204);
        assertThat(s.admin().post(cancel, null).status()).isEqualTo(409);

        // pessoa sem a permissao (conta interna sem papel)
        TestUser plain = internalUser(s.org(), true, 2);
        BrowserClient p = browser();
        firstLogin(p, s.org().id(), plain);
        assertThat(p.post("/api/v1/o/" + s.org().id() + "/auth/mfa/step-up", "{\"code\":\"" + code(plain.id()) + "\"}").status()).isEqualTo(204);
        assertThat(p.post(base, body).status()).isEqualTo(403);
        assertThat(p.get("/api/v1/o/" + s.org().id() + "/agents").status()).isEqualTo(403);
    }

    @Test
    void quemCriouOCodigoPerdeuAAutoridadeEOResgateERecusadoNoBanco() throws Exception {
        Setup s = setup();
        // segundo ORG_ADMIN cria o codigo; depois perde o papel (o primeiro continua: a 003 nao deixa a organizacao sem ORG_ADMIN)
        TestUser second = internalUser(s.org(), true, 2);
        String membership = value("SELECT membership_id FROM organization_memberships WHERE user_id = '" + second.id() + "'");
        String assignment = value("INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id) "
                + "SELECT '" + s.org().id() + "', '" + membership + "', role_id, 1, 1, '" + s.org().adminMembership() + "' FROM roles WHERE code = 'ORG_ADMIN' "
                + "RETURNING assignment_id");
        BrowserClient b = browser();
        firstLogin(b, s.org().id(), second);
        assertThat(b.post("/api/v1/o/" + s.org().id() + "/auth/mfa/step-up", "{\"code\":\"" + code(second.id()) + "\"}").status()).isEqualTo(204);
        BrowserClient.Response r = b.post("/api/v1/o/" + s.org().id() + "/agent-enrollments", "{\"workerName\":\"W\",\"agentName\":\"M\"}");
        assertThat(r.status()).isEqualTo(201);
        DB.asSuperuser("UPDATE organization_role_assignments SET status = 2, revoked_at = now(), revoked_by_membership_id = '" + s.org().adminMembership()
                + "' WHERE assignment_id = '" + assignment + "'");
        // consumo do codigo pelo papel REAL da maquina (o que o gateway faz no resgate): 42501
        UUID agent = agentRow(s.org(), "M");
        UUID credential = UUID.randomUUID();
        String state = asIngestion(s.org().id(), "INSERT INTO agent_credentials (credential_id, organization_id, agent_id, credential_hash, issued_by_membership_id, "
                        + "expires_at) VALUES ('" + credential + "', '" + s.org().id() + "', '" + agent + "', repeat('c', 64), '" + membership + "', now() + interval '1 day')",
                "UPDATE agent_enrollments SET consumed_at = clock_timestamp(), consumed_credential_id = '" + credential + "', agent_id = '" + agent
                        + "' WHERE enrollment_id = '" + r.field("enrollmentId") + "'");
        assertThat(state).isEqualTo("42501");
        assertThat(value("SELECT consumed_at IS NULL FROM agent_enrollments WHERE enrollment_id = '" + r.field("enrollmentId") + "'")).isEqualTo("t");
    }

    @Test
    void revogacaoDoAgentEncerraCredencialAssociacaoECodigosAbertos() throws Exception {
        Setup s = setup();
        UUID worker = workerRow(s.org(), "W-rev");
        UUID agent = agentOn(s.org(), worker);
        String base = "/api/v1/o/" + s.org().id() + "/agents/" + agent;
        stepUp(s);
        BrowserClient.Response rot = s.admin().post(base + "/credential-rotations", "{}");
        assertThat(rot.status()).as(rot.body()).isEqualTo(201);
        assertThat(s.admin().post(base + "/revoke", "{\"reason\":\"QUALQUER\"}").status()).isEqualTo(400);
        assertThat(s.admin().post(base + "/revoke", "{\"reason\":\"COMPROMISED\"}").status()).isEqualTo(204);
        assertThat(value("SELECT status FROM agents WHERE agent_id = '" + agent + "'")).isEqualTo("4");
        assertThat(value("SELECT revocation_reason FROM agent_credentials WHERE agent_id = '" + agent + "'")).isEqualTo("3");
        assertThat(value("SELECT status FROM worker_agent_assignments WHERE agent_id = '" + agent + "'")).isEqualTo("2");
        assertThat(value("SELECT cancelled_at IS NOT NULL FROM agent_enrollments WHERE enrollment_id = '" + rot.field("enrollmentId") + "'")).isEqualTo("t");
        assertThat(count("SELECT count(*) FROM agent_admin_events WHERE agent_id = '" + agent + "' AND act = 6 AND reason_code = 'COMPROMISED'")).isEqualTo(1);
        assertThat(s.admin().post(base + "/revoke", "{\"reason\":\"COMPROMISED\"}").status()).isEqualTo(409);
        // maquina que perdeu a credencial: so um NOVO ato autorizado a recupera (revogacao + novo pareamento, outro agent_id);
        // o Worker ficou livre com a revogacao
        BrowserClient.Response repair = s.admin().post("/api/v1/o/" + s.org().id() + "/agent-enrollments",
                "{\"workerId\":\"" + worker + "\",\"agentName\":\"Estacao reinstalada\"}");
        assertThat(repair.status()).as(repair.body()).isEqualTo(201);
        // a listagem mostra o estado, sem hash de credencial
        BrowserClient.Response list = s.admin().get("/api/v1/o/" + s.org().id() + "/agents");
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body()).contains("\"status\":\"REVOKED\"").doesNotContain("credential_hash").doesNotContain("cccccccc");
    }

    // =========================================================================== autorizacao local e consultas

    @Test
    void autorizacaoLocalVinculaWorkerAutorizadoVersaoPublicadaEPacote() throws Exception {
        Setup s = setup();
        UUID worker = workerRow(s.org(), "W-aut");
        String o = "/api/v1/o/" + s.org().id();
        String req = "{\"workerId\":\"" + worker + "\",\"automationVersionId\":\"" + s.version() + "\",\"validUntil\":\"" + future(30) + "\"}";
        stepUp(s);
        assertThat(s.admin().post(o + "/workspaces/" + s.workspace() + "/local-run-authorizations", req).status()).as("worker sem autorizacao no workspace")
                .isEqualTo(409);
        BrowserClient.Response put = s.admin().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(baseUrl() + o + "/workers/" + worker + "/workspaces/"
                + s.workspace())).PUT(java.net.http.HttpRequest.BodyPublishers.noBody()));
        assertThat(put.status()).as(put.body()).isEqualTo(201);
        BrowserClient.Response a = s.admin().post(o + "/workspaces/" + s.workspace() + "/local-run-authorizations", req);
        assertThat(a.status()).as(a.body()).isEqualTo(201);
        assertThat(a.field("packageFingerprint")).isEqualTo(FINGERPRINT);
        assertThat(a.field("automationId")).isEqualTo(s.automation().toString());
        // versao rascunho: recusada
        UUID draft = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id) "
                + "SELECT '" + draft + "', organization_id, workspace_id, automation_id, 1, command_json, created_by_membership_id FROM automation_versions WHERE "
                + "automation_version_id = '" + s.version() + "'");
        assertThat(s.admin().post(o + "/workspaces/" + s.workspace() + "/local-run-authorizations", req.replace(s.version().toString(), draft.toString())).status())
                .isEqualTo(409);
        // versao publicada sem impressao declarada (so o sha256 dos bytes do artefato, mesmo igual a impressao): recusada
        UUID onlyArtifact = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id) "
                        + "SELECT '" + onlyArtifact + "', organization_id, workspace_id, automation_id, 1, (command_json - 'agent_package') || jsonb_build_object('artifact', "
                        + "jsonb_build_object('reference', 'biografias-python', 'sha256', '" + FINGERPRINT + "')), created_by_membership_id FROM automation_versions WHERE "
                        + "automation_version_id = '" + s.version() + "'",
                "UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = created_by_membership_id WHERE automation_version_id = '"
                        + onlyArtifact + "'");
        BrowserClient.Response noPackage = s.admin().post(o + "/workspaces/" + s.workspace() + "/local-run-authorizations",
                req.replace(s.version().toString(), onlyArtifact.toString()));
        assertThat(noPackage.status()).as(noPackage.body()).isEqualTo(409);
        BrowserClient.Response list = s.admin().get(o + "/workspaces/" + s.workspace() + "/local-run-authorizations");
        assertThat(list.body()).contains(a.field("authorizationId"));
        assertThat(s.admin().post(o + "/workspaces/" + s.workspace() + "/local-run-authorizations/" + a.field("authorizationId") + "/revoke", null).status())
                .isEqualTo(204);
        assertThat(count("SELECT count(*) FROM agent_admin_events WHERE organization_id = '" + s.org().id() + "' AND act IN (8, 10, 11)")).isEqualTo(3);
    }

    @Test
    void consultasMostramCorrelacaoQuatroDimensoesEEtapasPorSequenceComIsolamento() throws Exception {
        Setup s = setup();
        UUID worker = workerRow(s.org(), "W-con");
        UUID agent = agentOn(s.org(), worker);
        UUID wwa = UUID.fromString(value("INSERT INTO worker_workspace_assignments (organization_id, worker_id, workspace_id, status, authorized_by_membership_id) "
                + "VALUES ('" + s.org().id() + "', '" + worker + "', '" + s.workspace() + "', 1, '" + s.org().adminMembership() + "') RETURNING assignment_id"));
        UUID authorization = UUID.fromString(value("INSERT INTO local_run_authorizations (organization_id, workspace_id, worker_id, worker_workspace_assignment_id, "
                + "automation_id, automation_version_id, package_fingerprint, granted_by_membership_id, valid_until) VALUES ('" + s.org().id() + "', '" + s.workspace()
                + "', '" + worker + "', '" + wwa + "', '" + s.automation() + "', '" + s.version() + "', '" + FINGERPRINT + "', '" + s.org().adminMembership()
                + "', now() + interval '30 days') RETURNING authorization_id"));
        String waa = value("SELECT assignment_id FROM worker_agent_assignments WHERE agent_id = '" + agent + "'");
        String ex = "ex-" + "cd".repeat(16);
        UUID run = UUID.randomUUID();
        // como o gateway grava: registro, tres eventos (um do Framework com correlacao), ausencia declarada e desfecho desconhecido
        String state = asIngestion(s.org().id(),
                "INSERT INTO agent_runs (agent_run_id, organization_id, workspace_id, automation_id, automation_version_id, authorization_id, agent_id, worker_id, "
                        + "worker_agent_assignment_id, agent_execution_id, package_fingerprint, authorization_state_at_registration, supervisor_state) VALUES ('" + run
                        + "', '" + s.org().id() + "', '" + s.workspace() + "', '" + s.automation() + "', '" + s.version() + "', '" + authorization + "', '" + agent + "', '"
                        + worker + "', '" + waa + "', '" + ex + "', '" + FINGERPRINT + "', 1, 3)",
                event(s.org().id(), run, ex, 1, "AGENT", "RUNNING", "LIFECYCLE", null),
                event(s.org().id(), run, ex, 2, "FRAMEWORK", "ACTION_COMPLETED", "ACTIVITY", "search.fill_search_term"),
                event(s.org().id(), run, ex, 5, "AGENT", "OUTCOME_UNKNOWN", "LIFECYCLE", null),
                "INSERT INTO agent_run_missing (organization_id, agent_run_id, seq_from, seq_to) VALUES ('" + s.org().id() + "', '" + run + "', 3, 4)",
                "UPDATE agent_runs SET events_stored = 3, stored_through = 2, settled_through = 5, max_sequence = 5, declared_missing = 2, lost_local = true, "
                        + "framework_events = 1, framework_execution_id = 'ex-d1dd0f7020ff40aeb12b', supervisor_state = 9, supervisor_code = 'AGT-EXE-025' WHERE agent_run_id = '"
                        + run + "'");
        assertThat(state).isEqualTo("OK");
        String o = "/api/v1/o/" + s.org().id();
        BrowserClient.Response list = s.admin().get(o + "/agent-runs");
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        assertThat(list.body()).contains(run.toString()).contains("\"agentExecutionId\":\"" + ex + "\"")
                .contains("\"frameworkExecutionId\":\"ex-d1dd0f7020ff40aeb12b\"").contains("\"platformExecutionId\":null")
                .contains("\"origin\":\"LOCAL_AUTHORIZED\"").contains("\"process\":\"ENDED\"").contains("\"outcome\":\"UNKNOWN\"")
                .contains("\"completeness\":\"INCOMPLETE\"").contains("LOST_LOCAL").contains("DELIVERY_PENDING")
                .contains("\"connectivity\":\"DISCONNECTED\"").contains("\"stepsAvailable\":true");
        BrowserClient.Response one = s.admin().get(o + "/agent-runs/" + run);
        assertThat(one.status()).isEqualTo(200);
        assertThat(one.field("state")).isEqualTo("OUTCOME_UNKNOWN");
        // etapas em ordem de sequence, paginadas; a ausencia declarada nao vira evento
        BrowserClient.Response page1 = s.admin().get(o + "/agent-runs/" + run + "/events?limit=2");
        assertThat(page1.body()).contains("\"sequence\":1").contains("\"sequence\":2").doesNotContain("\"sequence\":5");
        String next = page1.field("nextCursor");
        assertThat(next).isNotNull();
        BrowserClient.Response page2 = s.admin().get(o + "/agent-runs/" + run + "/events?limit=2&cursor=" + next);
        assertThat(page2.body()).contains("\"sequence\":5").doesNotContain("\"sequence\":3");
        assertThat(page2.field("nextCursor")).isNull();

        // isolamento: sem automation.read nesse workspace, a execucao nao aparece e o detalhe e 404
        TestUser viewer = internalUser(s.org(), true, 2);
        UUID otherWs = newWorkspace(s.org(), newClient(s.org()));
        String vm = value("SELECT membership_id FROM organization_memberships WHERE user_id = '" + viewer.id() + "'");
        DB.asSuperuser("INSERT INTO workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status, assigned_by_membership_id) "
                + "SELECT '" + s.org().id() + "', '" + otherWs + "', '" + vm + "', role_id, 2, 1, '" + s.org().adminMembership() + "' FROM roles WHERE code = 'VIEWER'");
        BrowserClient v = browser();
        firstLogin(v, s.org().id(), viewer);
        assertThat(v.get(o + "/agent-runs").body()).doesNotContain(run.toString());
        assertThat(v.get(o + "/agent-runs/" + run).status()).isEqualTo(404);
        assertThat(v.get(o + "/agent-runs/" + run + "/events").status()).isEqualTo(404);
        // outra organizacao nao ve nada (a RLS e a autorizacao): mesmo id, 404
        Setup other = setup();
        assertThat(other.admin().get("/api/v1/o/" + other.org().id() + "/agent-runs/" + run).status()).isEqualTo(404);
    }

    // =========================================================================== apoio

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    void expurgoDoServidorApareceComoRetencaoNuncaComoPerda() throws Exception {
        Setup s = setup();
        UUID worker = workerRow(s.org(), "W-ret");
        UUID agent = agentOn(s.org(), worker);
        UUID wwa = UUID.fromString(value("INSERT INTO worker_workspace_assignments (organization_id, worker_id, workspace_id, status, authorized_by_membership_id) "
                + "VALUES ('" + s.org().id() + "', '" + worker + "', '" + s.workspace() + "', 1, '" + s.org().adminMembership() + "') RETURNING assignment_id"));
        UUID authorization = UUID.fromString(value("INSERT INTO local_run_authorizations (organization_id, workspace_id, worker_id, worker_workspace_assignment_id, "
                + "automation_id, automation_version_id, package_fingerprint, granted_by_membership_id, valid_until) VALUES ('" + s.org().id() + "', '" + s.workspace()
                + "', '" + worker + "', '" + wwa + "', '" + s.automation() + "', '" + s.version() + "', '" + FINGERPRINT + "', '" + s.org().adminMembership()
                + "', now() + interval '30 days') RETURNING authorization_id"));
        String waa = value("SELECT assignment_id FROM worker_agent_assignments WHERE agent_id = '" + agent + "'");
        String ex = "ex-" + "ef".repeat(16);
        UUID run = UUID.randomUUID();
        // historico COMPLETO entregue (1..3, coleta concluida na 3) e desfecho conhecido
        assertThat(asIngestion(s.org().id(),
                "INSERT INTO agent_runs (agent_run_id, organization_id, workspace_id, automation_id, automation_version_id, authorization_id, agent_id, worker_id, "
                        + "worker_agent_assignment_id, agent_execution_id, package_fingerprint, authorization_state_at_registration, supervisor_state) VALUES ('" + run
                        + "', '" + s.org().id() + "', '" + s.workspace() + "', '" + s.automation() + "', '" + s.version() + "', '" + authorization + "', '" + agent + "', '"
                        + worker + "', '" + waa + "', '" + ex + "', '" + FINGERPRINT + "', 1, 3)",
                event(s.org().id(), run, ex, 1, "AGENT", "RUNNING", "LIFECYCLE", null),
                event(s.org().id(), run, ex, 2, "FRAMEWORK", "ACTION_COMPLETED", "ACTIVITY", "search.fill_search_term"),
                event(s.org().id(), run, ex, 3, "AGENT", "COLLECTION_COMPLETED", "LIFECYCLE", null),
                "UPDATE agent_runs SET events_stored = 3, stored_through = 3, settled_through = 3, max_sequence = 3, collection_completed_sequence = 3, "
                        + "framework_events = 1, supervisor_state = 4, exit_code = 0 WHERE agent_run_id = '" + run + "'")).isEqualTo("OK");
        // a ingestao (026) gravou received_at sob a barreira do recurso 6 do dia
        assertThat(count("SELECT count(*) FROM organization_usage_daily WHERE organization_id = '" + s.org().id() + "' AND resource_code = 6 "
                + "AND usage_date = (clock_timestamp() AT TIME ZONE 'UTC')::date AND status = 1")).isEqualTo(1);
        String o = "/api/v1/o/" + s.org().id();
        BrowserClient.Response before = s.admin().get(o + "/agent-runs/" + run);
        assertThat(before.body()).contains("\"completeness\":\"COMPLETE\"").contains("\"state\":\"AVAILABLE\"").contains("\"eventsPurged\":0");

        // fixture: os eventos foram recebidos ha 3 dias, com politica de 1 dia ja vigente; o dia e fechado e o expurgo roda
        String day = value("SELECT (clock_timestamp() AT TIME ZONE 'UTC')::date - 3");
        String user = value("SELECT user_id FROM organization_memberships WHERE membership_id = '" + s.org().adminMembership() + "'");
        DB.asSuperuser("SET session_replication_role = replica",
                "UPDATE agent_run_events SET received_at = ('" + day + "'::date + time '12:00') AT TIME ZONE 'UTC' WHERE agent_run_id = '" + run + "'",
                "INSERT INTO organization_retention_policy_revisions (organization_id, category, revision_number, effective_from, retention_mode, retention_days, "
                        + "provisioned_by_user_id) VALUES ('" + s.org().id() + "', 3, 1, now() - interval '10 days', 1, 1, '" + user + "')",
                "SET session_replication_role = origin",
                "SELECT public.close_usage_day('" + s.org().id() + "', 6, '" + day + "')");
        assertThat(value("SELECT public.purge_expired_records('" + s.org().id() + "', 3, '" + user + "', 100)")).isEqualTo("3");

        // expurgo NAO e perda: a completude continua a da entrega; a retencao aparece em dimensao propria
        BrowserClient.Response after = s.admin().get(o + "/agent-runs/" + run);
        assertThat(after.status()).isEqualTo(200);
        assertThat(after.body()).contains("\"completeness\":\"COMPLETE\"").contains("\"reasons\":[]").contains("\"storedThrough\":3")
                .contains("\"state\":\"PURGED\"").contains("\"eventsPurged\":3").doesNotContain("LOST_");
        BrowserClient.Response events = s.admin().get(o + "/agent-runs/" + run + "/events");
        assertThat(events.body()).contains("\"purged\":true").contains("\"eventCode\":\"ACTION_COMPLETED\"").doesNotContain("search.fill_search_term")
                .doesNotContain("\"purged\":false");
        // a contabilizacao do dia fechado ficou com os eventos do Agent
        assertThat(value("SELECT records_count || '|' || status FROM organization_usage_daily WHERE organization_id = '" + s.org().id() + "' AND resource_code = 6 "
                + "AND usage_date = '" + day + "'")).isEqualTo("3|2");
    }

    private static String event(UUID org, UUID run, String ex, int seq, String source, String code, String category, String step) {
        String env = "{\"schema\":\"nekkora-agent/observability\",\"version\":\"1.0.0\",\"agent_execution_id\":\"" + ex + "\",\"sequence\":" + seq
                + ",\"observed_utc\":\"2026-10-28T12:35:06.0Z\",\"source\":\"" + source + "\",\"event_code\":\"" + code + "\",\"severity\":\"INFO\",\"category\":\""
                + category + "\"" + (step == null ? "" : ",\"step_id\":\"" + step + "\"") + "}";
        short src = (short) ("AGENT".equals(source) ? 1 : 2);
        short cat = (short) ("LIFECYCLE".equals(category) ? 1 : 2);
        return "INSERT INTO agent_run_events (organization_id, agent_run_id, sequence, content_sha256, envelope, source, event_code, severity, category, step_id, "
                + "observed_utc) VALUES ('" + org + "', '" + run + "', " + seq + ", '" + Tokens.sha256Hex(env) + "', '" + env + "'::jsonb, " + src + ", '" + code
                + "', 1, " + cat + ", " + (step == null ? "NULL" : "'" + step + "'") + ", '2026-10-28T12:35:06Z')";
    }

    private static String future(int days) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(days).withNano(0).toString();
    }

    private static UUID workerRow(Org o, String name) throws Exception {
        return UUID.fromString(value("INSERT INTO workers (organization_id, name, status) VALUES ('" + o.id() + "', '" + name + "', 1) RETURNING worker_id"));
    }

    private static UUID agentRow(Org o, String name) throws Exception {
        return UUID.fromString(value("INSERT INTO agents (organization_id, name, agent_version, status) VALUES ('" + o.id() + "', '" + name + "', '0.1.0', 2) "
                + "RETURNING agent_id"));
    }

    /** Agent ativo com credencial e associacao ao Worker (fixture declarada, como superusuario). */
    private static UUID agentOn(Org o, UUID worker) throws Exception {
        UUID agent = agentRow(o, "Agent " + worker.toString().substring(0, 4));
        DB.asSuperuser("INSERT INTO worker_agent_assignments (organization_id, worker_id, agent_id, status, assigned_by_membership_id) VALUES ('" + o.id() + "', '"
                        + worker + "', '" + agent + "', 1, '" + o.adminMembership() + "')",
                "INSERT INTO agent_credentials (organization_id, agent_id, credential_hash, issued_by_membership_id, expires_at) VALUES ('" + o.id() + "', '" + agent
                        + "', repeat('c', 64), '" + o.adminMembership() + "', now() + interval '90 days')");
        return agent;
    }

    /** Executa como o papel REAL da maquina, no contexto da organizacao; devolve "OK" ou o SQLSTATE da recusa. */
    private static String asIngestion(UUID org, String... sqls) {
        try (Connection c = DB.connectAs("nekkora_ingestion")) {
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
