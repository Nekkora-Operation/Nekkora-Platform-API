package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.nekkora.core.testsupport.BrowserClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01a - lado das PESSOAS da habilitacao, com o recurso LIGADO, pelo papel real
 * nekkora_platform: o codigo de pareamento MARCADO para execucao remota (mesmas permissoes e confirmacao reforcada do
 * pareamento comum; a resposta comum nao muda) e a lista das maquinas habilitadas de um ambiente, com o estado indicativo.
 * O resgate pela maquina e simulado com o papel REAL nekkora_ingestion por SQL, numa unica transacao, como o gateway faz
 * (que tem os proprios testes, por HTTP): os gatilhos da 042 valem igual. O recurso DESLIGADO e conferido no AgentAdminIT.
 */
@TestPropertySource(properties = {"nekkora.remote-execution.enabled=true", "nekkora.remote-execution.reconcile-seconds=0"})
class RemoteMachinesIT extends PlatformTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ALL = "{REMOTE_RUN_1_0,REMOTE_LOGS_1_0,REMOTE_RESULTS_1_0,PY_INVENTORY_1_0}";

    private record Setup(Org org, BrowserClient admin, UUID workspace) {
        String o() {
            return "/api/v1/o/" + org.id();
        }
    }

    private Setup setup() {
        Org o = newOrganization();
        for (int code = 1; code <= 5; code++) {
            DB.asSuperuser("INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, "
                    + "limit_value, provisioned_by_user_id) VALUES ('" + o.id() + "', " + code + ", 1, now(), 2, NULL, '" + o.adminId() + "')");
        }
        UUID ws = newWorkspace(o, newClient(o));
        BrowserClient b = browser();
        firstLogin(b, o.id(), new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray()));
        assertThat(b.post("/api/v1/o/" + o.id() + "/auth/mfa/step-up", "{\"code\":\"" + code(o.adminId()) + "\"}").status()).isEqualTo(204);
        return new Setup(o, b, ws);
    }

    /** Cria, pela API real, um codigo marcado para um Worker novo; devolve {enrollmentId, workerId}. */
    private UUID[] markedCode(Setup s, String workerName) {
        BrowserClient.Response r = s.admin().post(s.o() + "/agent-enrollments",
                "{\"workerName\":\"" + workerName + "\",\"agentName\":\"Maquina de " + workerName + "\",\"expiresInMinutes\":15,\"remoteExecution\":true}");
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return new UUID[] {UUID.fromString(r.field("enrollmentId")), UUID.fromString(r.field("workerId"))};
    }

    /** O resgate do codigo pela maquina, como o gateway: uma transacao, papel nekkora_ingestion. Devolve "OK" ou o SQLSTATE. */
    private static String redeem(Setup s, UUID enrollment, UUID worker, String agentName, boolean enable) {
        String org = s.org().id().toString();
        String agent = "(SELECT agent_id FROM agents WHERE organization_id = '" + org + "' AND name = '" + agentName + "')";
        String creator = "(SELECT created_by_membership_id FROM agent_enrollments WHERE enrollment_id = '" + enrollment + "')";
        java.util.List<String> sqls = new java.util.ArrayList<>(java.util.List.of(
                "INSERT INTO agents (organization_id, name, agent_version, status) VALUES ('" + org + "', '" + agentName + "', '0.2.0', 2)",
                "INSERT INTO worker_agent_assignments (organization_id, worker_id, agent_id, status, assigned_by_membership_id) SELECT '" + org + "', '" + worker
                        + "', " + agent + ", 1, " + creator,
                "INSERT INTO agent_credentials (organization_id, agent_id, credential_hash, issued_by_membership_id, issued_at, expires_at) SELECT '" + org + "', "
                        + agent + ", encode(sha256('" + agentName + org + "'::bytea), 'hex'), " + creator + ", clock_timestamp(), clock_timestamp() + interval '90 days'",
                "UPDATE agent_enrollments SET consumed_at = clock_timestamp(), consumed_credential_id = (SELECT credential_id FROM agent_credentials WHERE agent_id = "
                        + agent + "), agent_id = " + agent + " WHERE organization_id = '" + org + "' AND enrollment_id = '" + enrollment + "'"));
        if (enable) {
            sqls.add("INSERT INTO agent_remote_enablements (agent_id, organization_id, worker_id, enrollment_id, enabled_by_membership_id) SELECT " + agent + ", '"
                    + org + "', '" + worker + "', '" + enrollment + "', " + creator);
        }
        return as("nekkora_ingestion", s.org().id(), sqls.toArray(String[]::new));
    }

    /** O batimento da maquina, como o gateway. */
    private static void heartbeat(Setup s, String agentName, String capabilities, boolean busy, boolean diskPressure) {
        String org = s.org().id().toString();
        String agent = "(SELECT agent_id FROM agents WHERE organization_id = '" + org + "' AND name = '" + agentName + "')";
        assertThat(as("nekkora_ingestion", s.org().id(),
                "UPDATE agents SET last_heartbeat_at = clock_timestamp() WHERE organization_id = '" + org + "' AND name = '" + agentName + "'",
                "INSERT INTO agent_presence (agent_id, organization_id, contract_version, capabilities, reported_at, remote_busy, disk_pressure) SELECT " + agent
                        + ", '" + org + "', '1.0', '" + capabilities + "'::text[], clock_timestamp(), " + busy + ", " + diskPressure
                        + " ON CONFLICT (agent_id) DO UPDATE SET capabilities = EXCLUDED.capabilities, reported_at = EXCLUDED.reported_at, "
                        + "remote_busy = EXCLUDED.remote_busy, disk_pressure = EXCLUDED.disk_pressure")).isEqualTo("OK");
    }

    private void authorize(Setup s, UUID worker, UUID workspace) {
        BrowserClient.Response r = s.admin().send(java.net.http.HttpRequest
                .newBuilder(java.net.URI.create("http://localhost:" + port + s.o() + "/workers/" + worker + "/workspaces/" + workspace))
                .PUT(java.net.http.HttpRequest.BodyPublishers.noBody()));
        assertThat(r.status()).as(r.body()).isEqualTo(201);
    }

    private static JsonNode machines(BrowserClient b, Setup s, UUID workspace) {
        BrowserClient.Response r = b.get(s.o() + "/workspaces/" + workspace + "/remote-machines");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body()).get("items");
    }

    private static String stateOf(JsonNode items, UUID worker) {
        for (JsonNode n : items) {
            if (n.get("workerId").asString().equals(worker.toString())) {
                return n.get("state").asString();
            }
        }
        return null;
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

    private static void grantWorkspaceRole(Org o, TestUser u, UUID ws, String role) throws Exception {
        String m = value("SELECT membership_id FROM organization_memberships WHERE user_id = '" + u.id() + "'");
        DB.asSuperuser("INSERT INTO workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status, assigned_by_membership_id) "
                + "SELECT '" + o.id() + "', '" + ws + "', '" + m + "', role_id, 2, 1, '" + o.adminMembership() + "' FROM roles WHERE code = '" + role + "'");
    }

    @Test
    void codigoMarcadoExigeAsMesmasPermissoesEAConfirmacaoReforcadaEARespostaComumNaoMuda() throws Exception {
        Org o = newOrganization();
        for (int code = 1; code <= 5; code++) {
            DB.asSuperuser("INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, "
                    + "limit_value, provisioned_by_user_id) VALUES ('" + o.id() + "', " + code + ", 1, now(), 2, NULL, '" + o.adminId() + "')");
        }
        BrowserClient admin = browser();
        firstLogin(admin, o.id(), new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray()));
        String base = "/api/v1/o/" + o.id() + "/agent-enrollments";
        String marked = "{\"workerName\":\"Estacao remota\",\"agentName\":\"Maquina 1\",\"expiresInMinutes\":15,\"remoteExecution\":true}";
        assertThat(admin.post(base, marked).status()).as("sem confirmacao reforcada").isEqualTo(403);
        assertThat(value("SELECT count(*) FROM workers WHERE organization_id = '" + o.id() + "'")).isEqualTo("0");
        assertThat(admin.post("/api/v1/o/" + o.id() + "/auth/mfa/step-up", "{\"code\":\"" + code(o.adminId()) + "\"}").status()).isEqualTo(204);
        BrowserClient.Response r = admin.post(base, marked);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(JSON.readTree(r.body()).get("remoteExecution").asBoolean()).isTrue();
        assertThat(r.field("code")).as("o formato de sempre: a marca fica no servidor, nao no codigo").matches("nkp1\\." + o.id() + "\\.[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}");
        String enrollment = r.field("enrollmentId");
        assertThat(value("SELECT remote_execution || '|' || created_by_membership_id FROM agent_enrollments WHERE enrollment_id = '" + enrollment + "'"))
                .isEqualTo("true|" + o.adminMembership());
        // o codigo sozinho nao habilita nada
        assertThat(value("SELECT count(*) FROM agent_remote_enablements WHERE organization_id = '" + o.id() + "'")).isEqualTo("0");
        // auditoria do codigo: sem o valor do codigo
        assertThat(value("SELECT count(*) FROM agent_admin_events WHERE enrollment_id = '" + enrollment + "' AND act = 1")).isEqualTo("1");
        // pareamento comum (sem o campo, ou com false): a resposta de sempre, sem o campo novo
        for (String plain : new String[] {"{\"workerName\":\"Comum A\",\"agentName\":\"M\",\"expiresInMinutes\":15}",
                "{\"workerName\":\"Comum B\",\"agentName\":\"M\",\"expiresInMinutes\":15,\"remoteExecution\":false}"}) {
            BrowserClient.Response p = admin.post(base, plain);
            assertThat(p.status()).isEqualTo(201);
            assertThat(JSON.readTree(p.body()).has("remoteExecution")).isFalse();
            assertThat(value("SELECT remote_execution FROM agent_enrollments WHERE enrollment_id = '" + p.field("enrollmentId") + "'")).isEqualTo("f");
        }
        // quem nao administra o vinculo e o Worker nao cria codigo marcado, mesmo com confirmacao reforcada
        TestUser operator = internalUser(o, true, 2);
        grantWorkspaceRole(o, operator, newWorkspace(o, newClient(o)), "OPERATOR");
        BrowserClient op = browser();
        firstLogin(op, o.id(), operator);
        assertThat(op.post("/api/v1/o/" + o.id() + "/auth/mfa/step-up", "{\"code\":\"" + code(operator.id()) + "\"}").status()).isEqualTo(204);
        assertThat(op.post(base, marked.replace("Estacao remota", "Estacao do operador")).status()).isEqualTo(403);
        // a marca nao e reescrita depois de criada, nem pela Platform nem pela maquina
        String comum = value("SELECT enrollment_id FROM agent_enrollments WHERE organization_id = '" + o.id() + "' AND NOT remote_execution LIMIT 1");
        for (String role : new String[] {"nekkora_platform", "nekkora_ingestion"}) {
            assertThat(as(role, o.id(), "UPDATE agent_enrollments SET remote_execution = true WHERE enrollment_id = '" + comum + "'")).as(role).isEqualTo("42501");
        }
        // codigo de rotacao nunca e marcado
        assertThat(value("SELECT count(*) FROM pg_constraint WHERE conrelid = 'public.agent_enrollments'::regclass AND pg_get_constraintdef(oid) LIKE '%remote_execution%purpose%'"))
                .isEqualTo("1");
    }

    @Test
    void maquinasHabilitadasDoAmbienteComOEstadoIndicativo() throws Exception {
        Setup s = setup();
        String[] names = {"Offline", "Incompativel", "Pausada", "SemDisco", "Ocupada", "Disponivel"};
        UUID[] workers = new UUID[names.length];
        for (int i = 0; i < names.length; i++) {
            UUID[] c = markedCode(s, names[i]);
            workers[i] = c[1];
            assertThat(redeem(s, c[0], c[1], "Maquina " + names[i], true)).as(names[i]).isEqualTo("OK");
            authorize(s, c[1], s.workspace());
        }
        // Offline: nunca bateu. Incompativel: bate sem declarar REMOTE_RUN_1_0 (um aplicativo anterior).
        heartbeat(s, "Maquina Incompativel", "{RUN_REQUEST_1_0}", false, false);
        heartbeat(s, "Maquina Pausada", ALL, true, true);
        assertThat(as("nekkora_ingestion", s.org().id(), "UPDATE agent_remote_enablements SET receiving = 2 WHERE worker_id = '" + workers[2] + "'")).isEqualTo("OK");
        heartbeat(s, "Maquina SemDisco", ALL, true, true);
        heartbeat(s, "Maquina Ocupada", ALL, true, false);
        heartbeat(s, "Maquina Disponivel", ALL, false, false);
        // pareada por codigo COMUM e autorizada no ambiente: nao e maquina de execucao remota
        BrowserClient.Response plain = s.admin().post(s.o() + "/agent-enrollments", "{\"workerName\":\"Comum\",\"agentName\":\"Maquina Comum\",\"expiresInMinutes\":15}");
        UUID plainWorker = UUID.fromString(plain.field("workerId"));
        assertThat(redeem(s, UUID.fromString(plain.field("enrollmentId")), plainWorker, "Maquina Comum", false)).isEqualTo("OK");
        authorize(s, plainWorker, s.workspace());
        heartbeat(s, "Maquina Comum", ALL, false, false);
        // habilitada, mas o Worker so esta autorizado em OUTRO ambiente
        UUID other = newWorkspace(s.org(), newClient(s.org()));
        UUID[] elsewhere = markedCode(s, "Outro ambiente");
        assertThat(redeem(s, elsewhere[0], elsewhere[1], "Maquina Outro ambiente", true)).isEqualTo("OK");
        authorize(s, elsewhere[1], other);
        heartbeat(s, "Maquina Outro ambiente", ALL, false, false);

        JsonNode items = machines(s.admin(), s, s.workspace());
        assertThat(items.size()).isEqualTo(6);
        String[] expected = {"OFFLINE", "INCOMPATIBLE", "PAUSED", "NO_DISK_SPACE", "BUSY", "AVAILABLE"};
        for (int i = 0; i < names.length; i++) {
            assertThat(stateOf(items, workers[i])).as(names[i]).isEqualTo(expected[i]);
        }
        assertThat(stateOf(items, plainWorker)).isNull();
        assertThat(stateOf(items, elsewhere[1])).isNull();
        assertThat(stateOf(machines(s.admin(), s, other), elsewhere[1])).isEqualTo("AVAILABLE");
        // o item: nome do Worker e da maquina, versao, recebimento e datas; nada de credencial, instalacao ou codigo
        JsonNode item = null;
        for (JsonNode n : items) {
            if (n.get("workerId").asString().equals(workers[2].toString())) {
                item = n;
            }
        }
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("workerId", "workerName", "agentId", "agentName", "agentVersion", "state", "receiving",
                "lastHeartbeatAt", "enabledAt");
        assertThat(item.get("receiving").asString()).isEqualTo("PAUSED");
        assertThat(item.get("workerName").asString()).isEqualTo("Pausada");
        assertThat(item.get("agentVersion").asString()).isEqualTo("0.2.0");

        // quem pode ver: OPERATOR do ambiente sim; VIEWER nao (404, como as demais leituras); outro ambiente nao; outra organizacao nao
        TestUser operator = internalUser(s.org(), true, 2);
        grantWorkspaceRole(s.org(), operator, s.workspace(), "OPERATOR");
        BrowserClient op = browser();
        firstLogin(op, s.org().id(), operator);
        assertThat(machines(op, s, s.workspace()).size()).isEqualTo(6);
        assertThat(op.get(s.o() + "/workspaces/" + other + "/remote-machines").status()).isEqualTo(404);
        TestUser viewer = internalUser(s.org(), true, 2);
        grantWorkspaceRole(s.org(), viewer, s.workspace(), "VIEWER");
        BrowserClient v = browser();
        firstLogin(v, s.org().id(), viewer);
        assertThat(v.get(s.o() + "/workspaces/" + s.workspace() + "/remote-machines").status()).isEqualTo(404);
        Setup foreign = setup();
        assertThat(foreign.admin().get(foreign.o() + "/workspaces/" + s.workspace() + "/remote-machines").status()).isEqualTo(404);
        assertThat(browser().get(s.o() + "/workspaces/" + s.workspace() + "/remote-machines").status()).as("sem sessao").isEqualTo(401);

        // Agent revogado deixa de aparecer; a habilitacao nao precisa ser apagada
        String agent = value("SELECT agent_id FROM agent_remote_enablements WHERE worker_id = '" + workers[5] + "'");
        assertThat(s.admin().post(s.o() + "/auth/mfa/step-up", "{\"code\":\"" + code(s.org().adminId()) + "\"}").status()).isEqualTo(204);
        BrowserClient.Response revoke = s.admin().post(s.o() + "/agents/" + agent + "/revoke", "{\"reason\":\"COMPROMISED\"}");
        assertThat(revoke.status()).as(revoke.body()).isBetween(200, 204);
        assertThat(stateOf(machines(s.admin(), s, s.workspace()), workers[5])).isNull();
        assertThat(value("SELECT count(*) FROM agent_remote_enablements WHERE agent_id = '" + agent + "'")).isEqualTo("1");
    }

    @Test
    void aHabilitacaoSoNasceNaTransacaoDoResgateDoCodigoMarcado() throws Exception {
        Setup s = setup();
        String org = s.org().id().toString();
        // codigo marcado resgatado SEM habilitar (transacao encerrada): nao da para habilitar depois, nem a maquina nem a Platform
        UUID[] c = markedCode(s, "Tardia");
        assertThat(redeem(s, c[0], c[1], "Maquina Tardia", false)).isEqualTo("OK");
        String insert = "INSERT INTO agent_remote_enablements (agent_id, organization_id, worker_id, enrollment_id, enabled_by_membership_id) SELECT agent_id, '" + org
                + "', '" + c[1] + "', '" + c[0] + "', '" + s.org().adminMembership() + "' FROM agents WHERE organization_id = '" + org + "' AND name = 'Maquina Tardia'";
        assertThat(as("nekkora_ingestion", s.org().id(), insert)).isEqualTo("42501");
        assertThat(as("nekkora_platform", s.org().id(), insert)).isEqualTo("42501");
        // codigo COMUM: o resgate nao habilita
        BrowserClient.Response plain = s.admin().post(s.o() + "/agent-enrollments", "{\"workerName\":\"Comum\",\"agentName\":\"Maquina Comum\",\"expiresInMinutes\":15}");
        assertThat(redeem(s, UUID.fromString(plain.field("enrollmentId")), UUID.fromString(plain.field("workerId")), "Maquina Comum", true)).isEqualTo("42501");
        assertThat(value("SELECT count(*) FROM agents WHERE organization_id = '" + org + "' AND name = 'Maquina Comum'")).as("a transacao inteira e desfeita").isEqualTo("0");
        // habilitada: a Platform (pessoas) nao pausa, nao altera e nao apaga; a maquina so muda o recebimento
        UUID[] ok = markedCode(s, "Habilitada");
        assertThat(redeem(s, ok[0], ok[1], "Maquina Habilitada", true)).isEqualTo("OK");
        assertThat(as("nekkora_platform", s.org().id(), "UPDATE agent_remote_enablements SET receiving = 2 WHERE worker_id = '" + ok[1] + "'")).isEqualTo("42501");
        assertThat(as("nekkora_platform", s.org().id(), "DELETE FROM agent_remote_enablements WHERE worker_id = '" + ok[1] + "'")).isEqualTo("42501");
        assertThat(as("nekkora_ingestion", s.org().id(), "UPDATE agent_remote_enablements SET worker_id = '" + c[1] + "' WHERE worker_id = '" + ok[1] + "'")).isEqualTo("42501");
        assertThat(as("nekkora_ingestion", s.org().id(), "DELETE FROM agent_remote_enablements WHERE worker_id = '" + ok[1] + "'")).isEqualTo("42501");
        assertThat(value("SELECT enabled_by_membership_id FROM agent_remote_enablements WHERE worker_id = '" + ok[1] + "'")).isEqualTo(s.org().adminMembership().toString());
    }

    @Test
    void inventarioDePythonDaMaquinaSoParaQuemExecutaNoAmbienteESemCaminho() throws Exception {
        Setup s = setup();
        UUID[] c = markedCode(s, "Inventario");
        assertThat(redeem(s, c[0], c[1], "Maquina Inventario", true)).isEqualTo("OK");
        authorize(s, c[1], s.workspace());
        String agent = value("SELECT agent_id FROM agent_remote_enablements WHERE worker_id = '" + c[1] + "'");
        String base = s.o() + "/workspaces/" + s.workspace() + "/remote-machines/";
        // ainda sem inventario: lista vazia (a maquina nao publicou)
        BrowserClient.Response empty = s.admin().get(base + agent + "/python-environments");
        assertThat(empty.status()).as(empty.body()).isEqualTo(200);
        assertThat(empty.body()).isEqualTo("{\"items\":[]}");
        // a maquina publica (papel real da ingestao, como o gateway)
        String insert = "INSERT INTO agent_python_environments (agent_id, environment_id, organization_id, python_version, kind, observed_at) VALUES ('" + agent
                + "', '%s', '" + s.org().id() + "', '%s', %d, clock_timestamp() - interval '1 minute')";
        assertThat(as("nekkora_ingestion", s.org().id(), insert.formatted("a".repeat(43), "3.12.4", 1), insert.formatted("b".repeat(43), "3.13.1", 2))).isEqualTo("OK");
        JsonNode items = JSON.readTree(s.admin().get(base + agent + "/python-environments").body()).get("items");
        assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(0).propertyNames()).containsExactlyInAnyOrder("environmentId", "pythonVersion", "kind", "observedAt", "reportedAt");
        assertThat(items.get(0).get("pythonVersion").asString()).as("mais novo primeiro").isEqualTo("3.13.1");
        assertThat(items.get(0).get("kind").asString()).isEqualTo("VIRTUAL_ENVIRONMENT");
        assertThat(items.get(1).get("environmentId").asString()).isEqualTo("a".repeat(43));
        assertThat(items.get(1).get("kind").asString()).isEqualTo("INSTALLATION");
        // quem ve: OPERATOR do ambiente; VIEWER, outro ambiente, outra organizacao e sem sessao nao
        TestUser operator = internalUser(s.org(), true, 2);
        grantWorkspaceRole(s.org(), operator, s.workspace(), "OPERATOR");
        BrowserClient op = browser();
        firstLogin(op, s.org().id(), operator);
        assertThat(op.get(base + agent + "/python-environments").status()).isEqualTo(200);
        TestUser viewer = internalUser(s.org(), true, 2);
        grantWorkspaceRole(s.org(), viewer, s.workspace(), "VIEWER");
        BrowserClient v = browser();
        firstLogin(v, s.org().id(), viewer);
        assertThat(v.get(base + agent + "/python-environments").status()).isEqualTo(404);
        UUID other = newWorkspace(s.org(), newClient(s.org()));
        assertThat(s.admin().get(s.o() + "/workspaces/" + other + "/remote-machines/" + agent + "/python-environments").status())
                .as("Worker nao autorizado nesse ambiente").isEqualTo(404);
        Setup foreign = setup();
        assertThat(foreign.admin().get(foreign.o() + "/workspaces/" + s.workspace() + "/remote-machines/" + agent + "/python-environments").status()).isEqualTo(404);
        assertThat(browser().get(base + agent + "/python-environments").status()).isEqualTo(401);
        // maquina que nao existe, malformada, ou pareada por codigo comum: 404 / 400
        assertThat(s.admin().get(base + UUID.randomUUID() + "/python-environments").status()).isEqualTo(404);
        assertThat(s.admin().get(base + "nao-e-uuid/python-environments").status()).isEqualTo(400);
        BrowserClient.Response plain = s.admin().post(s.o() + "/agent-enrollments", "{\"workerName\":\"Comum inv\",\"agentName\":\"Maquina Comum inv\",\"expiresInMinutes\":15}");
        UUID plainWorker = UUID.fromString(plain.field("workerId"));
        assertThat(redeem(s, UUID.fromString(plain.field("enrollmentId")), plainWorker, "Maquina Comum inv", false)).isEqualTo("OK");
        authorize(s, plainWorker, s.workspace());
        String plainAgent = value("SELECT agent_id FROM agents WHERE organization_id = '" + s.org().id() + "' AND name = 'Maquina Comum inv'");
        assertThat(s.admin().get(base + plainAgent + "/python-environments").status()).isEqualTo(404);
        // Agent revogado deixa de existir para a consulta; o retrato nao precisa ser apagado
        assertThat(s.admin().post(s.o() + "/auth/mfa/step-up", "{\"code\":\"" + code(s.org().adminId()) + "\"}").status()).isEqualTo(204);
        assertThat(s.admin().post(s.o() + "/agents/" + agent + "/revoke", "{\"reason\":\"ADMINISTRATIVE\"}").status()).isEqualTo(204);
        assertThat(s.admin().get(base + agent + "/python-environments").status()).isEqualTo(404);
    }
}
