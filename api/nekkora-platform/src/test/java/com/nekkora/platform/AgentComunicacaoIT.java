package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.core.testsupport.BrowserClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PLATFORM-AGENT-V2-003, T11: vinculo, comunicacao e administracao do Worker sao dimensoes separadas nas leituras da
 * Platform. CONNECTED so com o Agent ATIVO, vinculado e com contato autenticado nos ultimos 180 s (relogio do servidor);
 * nunca antes do primeiro contato; revogado nunca volta a aparecer conectado, nem com um sinal gravado depois da
 * revogacao; a expiracao da comunicacao nao muda nada no servidor. O sinal e escrito com o papel REAL da maquina
 * (nekkora_ingestion), como o gateway faz no heartbeat; a revogacao passa pela API.
 */
class AgentComunicacaoIT extends PlatformTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Setup(Org org, BrowserClient admin, UUID workspace, UUID worker, UUID agent) {
    }

    private Setup setup() throws Exception {
        Org o = newOrganization();
        for (int code = 1; code <= 5; code++) {
            DB.asSuperuser("INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, "
                    + "limit_value, provisioned_by_user_id) VALUES ('" + o.id() + "', " + code + ", 1, now(), 2, NULL, '" + o.adminId() + "')");
        }
        UUID ws = newWorkspace(o, newClient(o));
        UUID worker = UUID.fromString(value("INSERT INTO workers (organization_id, name, status) VALUES ('" + o.id() + "', 'Estacao fiscal', 1) RETURNING worker_id"));
        UUID agent = UUID.fromString(value("INSERT INTO agents (organization_id, name, agent_version, status) VALUES ('" + o.id() + "', 'Maquina fiscal', '0.1.0', 2) "
                + "RETURNING agent_id"));
        DB.asSuperuser("INSERT INTO worker_agent_assignments (organization_id, worker_id, agent_id, status, assigned_by_membership_id) VALUES ('" + o.id() + "', '"
                        + worker + "', '" + agent + "', 1, '" + o.adminMembership() + "')",
                "INSERT INTO agent_credentials (organization_id, agent_id, credential_hash, issued_by_membership_id, expires_at) VALUES ('" + o.id() + "', '" + agent
                        + "', repeat('c', 64), '" + o.adminMembership() + "', now() + interval '90 days')",
                "INSERT INTO worker_workspace_assignments (organization_id, worker_id, workspace_id, status, authorized_by_membership_id) VALUES ('" + o.id() + "', '"
                        + worker + "', '" + ws + "', 1, '" + o.adminMembership() + "')");
        BrowserClient b = browser();
        firstLogin(b, o.id(), new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray()));
        return new Setup(o, b, ws, worker, agent);
    }

    /** Sinal de contato como o gateway grava no heartbeat aceito: papel REAL da maquina, relogio do servidor. */
    private static String signal(Setup s, String ago) {
        return asIngestion(s.org().id(), "UPDATE agents SET last_heartbeat_at = clock_timestamp() - interval '" + ago + "' WHERE organization_id = '" + s.org().id()
                + "' AND agent_id = '" + s.agent() + "'");
    }

    private static JsonNode agent(Setup s) throws Exception {
        BrowserClient.Response r = s.admin().get("/api/v1/o/" + s.org().id() + "/agents");
        assertThat(r.status()).isEqualTo(200);
        for (JsonNode n : JSON.readTree(r.body()).get("items")) {
            if (n.get("agentId").asString().equals(s.agent().toString())) {
                return n;
            }
        }
        throw new AssertionError("agent ausente da lista");
    }

    private static JsonNode worker(Setup s) throws Exception {
        BrowserClient.Response r = s.admin().get("/api/v1/o/" + s.org().id() + "/workers");
        assertThat(r.status()).isEqualTo(200);
        for (JsonNode n : JSON.readTree(r.body()).get("items")) {
            if (n.get("workerId").asString().equals(s.worker().toString())) {
                return n;
            }
        }
        throw new AssertionError("worker ausente da lista");
    }

    /** Estado administrativo do servidor (para provar que ler a comunicacao, ou ela expirar, nao muda nada). */
    private static String state(Setup s) throws Exception {
        String o = s.org().id().toString();
        return value("SELECT concat_ws('|', (SELECT status FROM agents WHERE agent_id = '" + s.agent() + "'), (SELECT status FROM workers WHERE worker_id = '" + s.worker()
                + "'), (SELECT string_agg(status::text, ',' ORDER BY assignment_id) FROM worker_agent_assignments WHERE organization_id = '" + o
                + "'), (SELECT string_agg(status::text, ',' ORDER BY assignment_id) FROM worker_workspace_assignments WHERE organization_id = '" + o
                + "'), (SELECT count(*) FROM agent_credentials WHERE organization_id = '" + o + "' AND revoked_at IS NULL), (SELECT count(*) FROM agent_admin_events "
                + "WHERE organization_id = '" + o + "'))");
    }

    @Test
    void semNenhumContatoNuncaAparecConectado() throws Exception {
        Setup s = setup();
        JsonNode a = agent(s);
        assertThat(a.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(a.get("connectivity").asString()).isEqualTo("DISCONNECTED");
        assertThat(a.get("lastHeartbeatAt").isNull()).isTrue();
        JsonNode w = worker(s);
        assertThat(w.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(w.get("agentStatus").asString()).isEqualTo("ACTIVE");
        assertThat(w.get("connectivity").asString()).isEqualTo("DISCONNECTED");
        assertThat(w.get("lastHeartbeatAt").isNull()).isTrue();
    }

    /** Janela de 180 s pelo relogio do servidor: 175 s conectado, 185 s sem comunicacao; o retorno do sinal reconecta. */
    @Test
    void comunicacaoExpiraEm180sSemMudarNadaEVoltaComONovoContato() throws Exception {
        Setup s = setup();
        assertThat(signal(s, "0 seconds")).isEqualTo("OK");
        assertThat(agent(s).get("connectivity").asString()).isEqualTo("CONNECTED");
        assertThat(worker(s).get("connectivity").asString()).isEqualTo("CONNECTED");
        String before = state(s);

        assertThat(signal(s, "175 seconds")).isEqualTo("OK");
        assertThat(agent(s).get("connectivity").asString()).as("dentro da tolerancia").isEqualTo("CONNECTED");
        assertThat(signal(s, "185 seconds")).isEqualTo("OK");
        JsonNode a = agent(s);
        assertThat(a.get("connectivity").asString()).as("fora da tolerancia").isEqualTo("DISCONNECTED");
        assertThat(a.get("status").asString()).as("o vinculo continua: offline nao e desvinculado").isEqualTo("ACTIVE");
        assertThat(a.get("lastHeartbeatAt").isNull()).as("o ultimo contato continua conhecido").isFalse();
        JsonNode w = worker(s);
        assertThat(w.get("connectivity").asString()).isEqualTo("DISCONNECTED");
        assertThat(w.get("agentId").asString()).as("o Worker continua com a maquina").isEqualTo(s.agent().toString());
        assertThat(state(s)).as("a expiracao da comunicacao nao muda nada no servidor").isEqualTo(before);

        // reconexao: o mesmo Agent, a mesma identidade
        assertThat(signal(s, "0 seconds")).isEqualTo("OK");
        assertThat(agent(s).get("connectivity").asString()).isEqualTo("CONNECTED");
        assertThat(agent(s).get("agentId").asString()).isEqualTo(s.agent().toString());
        assertThat(state(s)).isEqualTo(before);
    }

    /**
     * Revogacao pela pessoa com sinal recente: REVOKED e DISCONNECTED na hora (antes, a lista mostrava CONNECTED por ate
     * 180 s). Um sinal gravado DEPOIS da revogacao (heartbeat atrasado) nao ressuscita o vinculo nem a comunicacao.
     */
    @Test
    void revogadoNuncaApareceConectadoNemComSinalAtrasado() throws Exception {
        Setup s = setup();
        assertThat(signal(s, "0 seconds")).isEqualTo("OK");
        assertThat(agent(s).get("connectivity").asString()).isEqualTo("CONNECTED");
        assertThat(s.admin().post("/api/v1/o/" + s.org().id() + "/auth/mfa/step-up", "{\"code\":\"" + code(s.org().adminId()) + "\"}").status()).isEqualTo(204);
        BrowserClient.Response r = s.admin().post("/api/v1/o/" + s.org().id() + "/agents/" + s.agent() + "/revoke", "{\"reason\":\"ADMINISTRATIVE\"}");
        assertThat(r.status()).as(r.body()).isIn(200, 204);

        JsonNode a = agent(s);
        assertThat(a.get("status").asString()).isEqualTo("REVOKED");
        assertThat(a.get("connectivity").asString()).isEqualTo("DISCONNECTED");
        assertThat(a.get("workerId").isNull()).as("sem associacao ativa").isTrue();
        assertThat(worker(s).get("agentId").isNull()).as("o Worker fica sem maquina vinculada").isTrue();

        // heartbeat atrasado gravado depois da revogacao (o papel da maquina ainda pode atualizar a coluna)
        assertThat(signal(s, "0 seconds")).isEqualTo("OK");
        a = agent(s);
        assertThat(a.get("status").asString()).isEqualTo("REVOKED");
        assertThat(a.get("connectivity").asString()).isEqualTo("DISCONNECTED");
        assertThat(worker(s).get("agentId").isNull()).isTrue();
    }

    /**
     * PLATFORM-AGENT-V2-004, T13: desvinculo pela propria maquina (as mesmas instrucoes do gateway, com o papel REAL dela, na
     * mesma transacao da operacao): REVOKED com endedBy MACHINE, sem comunicacao, Worker livre e ativo com o workspace. A
     * revogacao pela Web depois disso nao acha nada a revogar (409); ativo: endedBy nulo; revogado pela Web: ADMINISTRATION.
     */
    @Test
    void desvinculoPelaMaquinaApareceComoEncerradoPelaMaquinaEOWorkerFicaLivre() throws Exception {
        Setup s = setup();
        assertThat(signal(s, "0 seconds")).isEqualTo("OK");
        assertThat(agent(s).get("endedBy").isNull()).as("ativo: nao encerrado").isTrue();
        String o = s.org().id().toString();
        String a = s.agent().toString();
        assertThat(asIngestionAll(s.org().id(),
                "INSERT INTO agent_unlink_operations (operation_id, organization_id, agent_id, worker_id, assignment_id, credential_id, run_requests_closed, "
                        + "run_requests_kept_accepted) SELECT gen_random_uuid(), wa.organization_id, wa.agent_id, wa.worker_id, wa.assignment_id, c.credential_id, 0, 0 "
                        + "FROM worker_agent_assignments wa JOIN agent_credentials c ON c.agent_id = wa.agent_id AND c.revoked_at IS NULL "
                        + "WHERE wa.organization_id = '" + o + "' AND wa.agent_id = '" + a + "' AND wa.status = 1",
                "UPDATE agent_credentials SET revoked_at = clock_timestamp(), revoked_by_agent_id = agent_id, revocation_reason = 5 WHERE agent_id = '" + a
                        + "' AND revoked_at IS NULL",
                "UPDATE worker_agent_assignments SET status = 2, ended_at = clock_timestamp(), ended_by_agent_id = agent_id WHERE agent_id = '" + a + "' AND status = 1",
                "UPDATE agents SET status = 4 WHERE agent_id = '" + a + "'",
                "INSERT INTO agent_admin_events (organization_id, act, actor_agent_id, agent_id, worker_id, reason_code) VALUES ('" + o + "', 14, '" + a + "', '" + a
                        + "', '" + s.worker() + "', 'MACHINE_UNLINK')")).isEqualTo("OK");
        JsonNode ag = agent(s);
        assertThat(ag.get("status").asString()).isEqualTo("REVOKED");
        assertThat(ag.get("endedBy").asString()).isEqualTo("MACHINE");
        assertThat(ag.get("connectivity").asString()).isEqualTo("DISCONNECTED");
        assertThat(ag.get("workerId").isNull()).isTrue();
        JsonNode w = worker(s);
        assertThat(w.get("agentId").isNull()).as("Sem maquina vinculada").isTrue();
        assertThat(w.get("status").asString()).as("o Worker continua administrativamente ativo").isEqualTo("ACTIVE");
        assertThat(w.get("workspaceIds").get(0).asString()).isEqualTo(s.workspace().toString());
        // a revogacao pela Web nao tem mais o que revogar
        assertThat(s.admin().post("/api/v1/o/" + o + "/auth/mfa/step-up", "{\"code\":\"" + code(s.org().adminId()) + "\"}").status()).isEqualTo(204);
        assertThat(s.admin().post("/api/v1/o/" + o + "/agents/" + a + "/revoke", "{\"reason\":\"ADMINISTRATIVE\"}").status()).isEqualTo(409);
        assertThat(agent(s).get("endedBy").asString()).isEqualTo("MACHINE");

        // revogado pela Web: ADMINISTRATION
        Setup t = setup();
        assertThat(t.admin().post("/api/v1/o/" + t.org().id() + "/auth/mfa/step-up", "{\"code\":\"" + code(t.org().adminId()) + "\"}").status()).isEqualTo(204);
        assertThat(t.admin().post("/api/v1/o/" + t.org().id() + "/agents/" + t.agent() + "/revoke", "{\"reason\":\"ADMINISTRATIVE\"}").status()).isIn(200, 204);
        assertThat(agent(t).get("endedBy").asString()).isEqualTo("ADMINISTRATION");
    }

    // =========================================================================== apoio

    private static String asIngestionAll(UUID org, String... sqls) {
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
                return e.getSQLState() + " " + e.getMessage();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String asIngestion(UUID org, String sql) {
        try (Connection c = DB.connectAs("nekkora_ingestion")) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT set_config('nekkora.organization_id', '" + org + "', true)");
                st.execute(sql);
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
}
