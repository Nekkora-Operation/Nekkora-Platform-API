package com.nekkora.agentgateway;

import static org.assertj.core.api.Assertions.assertThat;

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
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01a - habilitacao de uma maquina para execucoes solicitadas pela Platform, pelo
 * gateway REAL (HTTP, papel nekkora_ingestion, PostgreSQL com as migrations ate a 042, recurso LIGADO). A habilitacao so
 * nasce do resgate de um codigo de pareamento MARCADO, com a intencao declarada pela maquina; pausar e retomar sao da
 * propria maquina; nada muda para um Agent anterior. O codigo marcado e criado por fixture (como superusuario): a criacao
 * pelas pessoas, com permissoes e confirmacao reforcada, e conferida no modulo da Platform (RemoteMachinesIT).
 */
class RemoteEnablementIT extends RemoteEnabledGatewayIT {

    static AgentFixture fx;

    @BeforeAll
    static void fixture() {
        fx = new AgentFixture(DB);
    }

    MachineClient client() {
        return new MachineClient(port, "10.61." + (int) (Math.random() * 250) + "." + (1 + (int) (Math.random() * 250)));
    }

    static final String REMOTE_HEARTBEAT = "{\"agentVersion\":\"0.2.0+client\",\"contractVersion\":\"1.0\",\"capabilities\":[\"REMOTE_RUN_1_0\",\"REMOTE_LOGS_1_0\","
            + "\"REMOTE_RESULTS_1_0\",\"PY_INVENTORY_1_0\"],\"remote\":{\"busy\":%s,\"diskPressure\":%s}}";

    /** Maquina habilitada: codigo marcado resgatado com a intencao pair-remote. Guarda a credencial no cliente. */
    private MachineClient enabled(UUID worker) {
        MachineClient m = client();
        String secret = Tokens.newToken();
        MachineClient.Response r = m.redeemWithIntent(fx.remotePairingCode(worker, "Estacao remota"), secret, "pair-remote");
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        m.credential = MachineToken.credential(fx.org, UUID.fromString(r.body().get("credentialId").asString()), secret);
        return m;
    }

    private long count(String sql) {
        return Long.parseLong(fx.value(sql));
    }

    /** Como o papel REAL da maquina (ou da Platform), no contexto da organizacao: "OK" ou o SQLSTATE da recusa. */
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

    @Test
    void codigoMarcadoComAConfirmacaoDaMaquinaHabilitaEGravaQuemHabilitou() {
        UUID worker = fx.worker("W-remoto-" + UUID.randomUUID().toString().substring(0, 6));
        MachineClient m = client();
        String code = fx.remotePairingCode(worker, "Estacao remota");
        String secret = Tokens.newToken();
        MachineClient.Response r = m.redeemWithIntent(code, secret, "pair-remote");
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        assertThat(r.body().get("remoteExecution").asBoolean()).isTrue();
        String agent = r.body().get("agentId").asString();
        assertThat(r.body().get("workerId").asString()).isEqualTo(worker.toString());
        // a habilitacao: do Agent e do Worker do codigo, e QUEM habilitou e a pessoa que criou o codigo (nao a maquina)
        assertThat(fx.value("SELECT worker_id || '|' || enabled_by_membership_id || '|' || receiving FROM agent_remote_enablements WHERE agent_id = '" + agent + "'"))
                .isEqualTo(worker + "|" + fx.adminMembership + "|1");
        assertThat(count("SELECT count(*) FROM agent_admin_events WHERE agent_id = '" + agent + "' AND act = 15 AND actor_membership_id IS NULL AND actor_agent_id = agent_id"))
                .isEqualTo(1);
        // resposta perdida: o mesmo codigo com o MESMO segredo devolve a mesma identidade, sem habilitar de novo
        MachineClient.Response again = m.redeemWithIntent(code, secret, "pair-remote");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("credentialId")).isEqualTo(r.body().get("credentialId"));
        assertThat(again.body().get("remoteExecution").asBoolean()).isTrue();
        assertThat(count("SELECT count(*) FROM agent_remote_enablements WHERE agent_id = '" + agent + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM agent_admin_events WHERE agent_id = '" + agent + "' AND act = 15")).isEqualTo(1);
        // outro segredo: recusado, como em qualquer codigo ja consumido
        assertThat(m.redeemWithIntent(code, Tokens.newToken(), "pair-remote").status()).isEqualTo(401);
    }

    @Test
    void codigoEIntencaoPrecisamCasarENadaEConsumidoNaRecusa() {
        UUID worker = fx.worker("W-casar-" + UUID.randomUUID().toString().substring(0, 6));
        long agents = count("SELECT count(*) FROM agents WHERE organization_id = '" + fx.org + "'");
        // codigo MARCADO resgatado sem a confirmacao da maquina: um Agent anterior (sem cabecalho) ou um pareamento comum
        String marked = fx.remotePairingCode(worker, "Estacao marcada");
        for (MachineClient.Response r : new MachineClient.Response[] {client().redeem(marked, Tokens.newToken()),
                client().redeemWithIntent(marked, Tokens.newToken(), "pair")}) {
            assertThat(r.status()).as(r.raw()).isEqualTo(409);
            assertThat(r.body().get("error").asString()).isEqualTo("conflict");
            assertThat(r.body().get("reason").asString()).isEqualTo("remote_code_without_enablement");
        }
        // codigo COMUM com a intencao de habilitar: a maquina nao se habilita sozinha
        UUID other = fx.worker("W-comum-" + UUID.randomUUID().toString().substring(0, 6));
        String plain = fx.pairingCode(other, "Estacao comum", "15 minutes");
        MachineClient.Response r = client().redeemWithIntent(plain, Tokens.newToken(), "pair-remote");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body().get("reason").asString()).isEqualTo("code_not_for_remote_execution");
        // nada consumido, nenhum Agent criado, nenhuma habilitacao
        assertThat(count("SELECT count(*) FROM agent_enrollments WHERE worker_id IN ('" + worker + "', '" + other + "') AND consumed_at IS NOT NULL")).isZero();
        assertThat(count("SELECT count(*) FROM agents WHERE organization_id = '" + fx.org + "'")).isEqualTo(agents);
        assertThat(count("SELECT count(*) FROM agent_remote_enablements WHERE worker_id IN ('" + worker + "', '" + other + "')")).isZero();
        // os dois codigos continuam validos para o uso certo
        assertThat(client().redeemWithIntent(marked, Tokens.newToken(), "pair-remote").status()).isEqualTo(200);
        MachineClient legacy = client();
        JsonNode id = legacy.pair(plain);
        assertThat(id.has("remoteExecution")).as("pareamento comum: a resposta de sempre, sem o campo novo").isFalse();
        MachineClient.Response hb = legacy.heartbeat();
        assertThat(hb.status()).isEqualTo(200);
        assertThat(hb.body().has("remote")).as("Agent anterior: o batimento de sempre").isFalse();
        // codigo de ROTACAO nunca habilita
        MachineClient.Response rot = legacy.redeemWithIntent(fx.rotationCode(UUID.fromString(id.get("agentId").asString()), other), Tokens.newToken(), "pair-remote");
        assertThat(rot.status()).isEqualTo(409);
        assertThat(rot.body().get("reason").asString()).isEqualTo("rotation_code_for_pairing");
    }

    @Test
    void batimentoAceitaAsCapacidadesNovasEDevolveORecebimento() {
        MachineClient m = enabled(fx.worker("W-bat-" + UUID.randomUUID().toString().substring(0, 6)));
        MachineClient.Response hb = m.post("/heartbeat", REMOTE_HEARTBEAT.formatted("true", "false"), m.credential);
        assertThat(hb.status()).as(hb.raw()).isEqualTo(200);
        assertThat(hb.body().get("remote").get("receiving").asString()).isEqualTo("ENABLED");
        String agent = fx.value("SELECT agent_id FROM agent_presence WHERE remote_busy AND NOT disk_pressure AND capabilities @> ARRAY['REMOTE_RUN_1_0','PY_INVENTORY_1_0'] "
                + "ORDER BY reported_at DESC LIMIT 1");
        assertThat(agent).isNotNull();
        // sinais malformados: recusados, sem gravar
        for (String bad : new String[] {"{\"busy\":\"sim\",\"diskPressure\":false}", "{\"busy\":true}", "[]", "\"x\""}) {
            String body = "{\"agentVersion\":\"0.2.0\",\"contractVersion\":\"1.0\",\"capabilities\":[\"REMOTE_RUN_1_0\"],\"remote\":" + bad + "}";
            assertThat(m.post("/heartbeat", body, m.credential).status()).as(bad).isEqualTo(400);
        }
        // capacidade desconhecida continua recusada
        assertThat(m.post("/heartbeat", "{\"agentVersion\":\"0.2.0\",\"contractVersion\":\"1.0\",\"capabilities\":[\"REMOTE_RUN_9_9\"]}", m.credential).status())
                .isEqualTo(400);
        // batimento sem "remote": os sinais voltam a falso (o que vale e o ultimo batimento)
        assertThat(m.post("/heartbeat", "{\"agentVersion\":\"0.2.0\",\"contractVersion\":\"1.0\",\"capabilities\":[\"REMOTE_RUN_1_0\"]}", m.credential).status())
                .isEqualTo(200);
        assertThat(fx.value("SELECT remote_busy OR disk_pressure FROM agent_presence WHERE agent_id = '" + agent + "'")).isEqualTo("f");
    }

    @Test
    void pausarERetomarSaoDaMaquinaIdempotentesEAuditados() {
        MachineClient m = enabled(fx.worker("W-pausa-" + UUID.randomUUID().toString().substring(0, 6)));
        String agent = fx.value("SELECT agent_id FROM agent_remote_enablements ORDER BY enabled_at DESC LIMIT 1");
        MachineClient.Response p = m.post("/remote/receiving", "{\"receiving\":\"PAUSED\"}", m.credential);
        assertThat(p.status()).as(p.raw()).isEqualTo(200);
        assertThat(p.body().get("receiving").asString()).isEqualTo("PAUSED");
        assertThat(m.post("/remote/receiving", "{\"receiving\":\"PAUSED\"}", m.credential).status()).as("repeticao").isEqualTo(200);
        assertThat(count("SELECT count(*) FROM agent_admin_events WHERE agent_id = '" + agent + "' AND act = 16")).as("uma pausa, um ato").isEqualTo(1);
        MachineClient.Response hb = m.post("/heartbeat", REMOTE_HEARTBEAT.formatted("false", "false"), m.credential);
        assertThat(hb.body().get("remote").get("receiving").asString()).as("o servidor e a referencia do recebimento").isEqualTo("PAUSED");
        assertThat(m.post("/remote/receiving", "{\"receiving\":\"ENABLED\"}", m.credential).body().get("receiving").asString()).isEqualTo("ENABLED");
        assertThat(count("SELECT count(*) FROM agent_admin_events WHERE agent_id = '" + agent + "' AND act = 17")).isEqualTo(1);
        // entradas invalidas e maquina sem credencial
        assertThat(m.post("/remote/receiving", "{\"receiving\":\"SIM\"}", m.credential).status()).isEqualTo(400);
        assertThat(m.post("/remote/receiving", "{}", m.credential).status()).isEqualTo(400);
        assertThat(client().post("/remote/receiving", "{\"receiving\":\"PAUSED\"}", null).status()).isEqualTo(401);
        // maquina pareada mas NAO habilitada
        MachineClient plain = client();
        plain.pair(fx.pairingCode(fx.worker("W-sem-" + UUID.randomUUID().toString().substring(0, 6)), "Estacao comum", "15 minutes"));
        MachineClient.Response no = plain.post("/remote/receiving", "{\"receiving\":\"PAUSED\"}", plain.credential);
        assertThat(no.status()).isEqualTo(409);
        assertThat(no.body().get("reason").asString()).isEqualTo("remote_execution_not_enabled");
    }

    @Test
    void oBancoSoAceitaAHabilitacaoPeloResgateDoCodigoMarcado() {
        // maquina pareada por um codigo COMUM: a ingestao nao consegue habilita-la por escrita direta
        UUID worker = fx.worker("W-banco-" + UUID.randomUUID().toString().substring(0, 6));
        MachineClient plain = client();
        JsonNode id = plain.pair(fx.pairingCode(worker, "Estacao comum", "15 minutes"));
        String agent = id.get("agentId").asString();
        String enrollment = fx.value("SELECT enrollment_id FROM agent_enrollments WHERE agent_id = '" + agent + "'");
        String insert = "INSERT INTO agent_remote_enablements (agent_id, organization_id, worker_id, enrollment_id, enabled_by_membership_id) VALUES ('" + agent
                + "', '" + fx.org + "', '" + worker + "', '" + enrollment + "', '" + fx.adminMembership + "')";
        assertThat(as("nekkora_ingestion", fx.org, insert)).as("codigo nao marcado").isEqualTo("42501");
        assertThat(as("nekkora_platform", fx.org, insert)).as("a Platform (pessoas) nao escreve habilitacao").isEqualTo("42501");
        // maquina habilitada: so o recebimento muda; quem habilitou, o Worker e o codigo sao imutaveis; ninguem apaga
        MachineClient m = enabled(fx.worker("W-imut-" + UUID.randomUUID().toString().substring(0, 6)));
        String remote = fx.value("SELECT agent_id FROM agent_remote_enablements ORDER BY enabled_at DESC LIMIT 1");
        assertThat(as("nekkora_ingestion", fx.org, "UPDATE agent_remote_enablements SET enabled_by_membership_id = '" + UUID.randomUUID() + "' WHERE agent_id = '" + remote + "'"))
                .isEqualTo("42501");
        assertThat(as("nekkora_ingestion", fx.org, "DELETE FROM agent_remote_enablements WHERE agent_id = '" + remote + "'")).isEqualTo("42501");
        assertThat(as("nekkora_ingestion", fx.org, "UPDATE agent_enrollments SET remote_execution = true WHERE agent_id = '" + agent + "'")).isEqualTo("42501");
        // um codigo marcado ja consumido NAO habilita outra maquina depois (a habilitacao e da transacao do resgate)
        String usedEnrollment = fx.value("SELECT enrollment_id FROM agent_remote_enablements WHERE agent_id = '" + remote + "'");
        assertThat(as("nekkora_ingestion", fx.org, insert.replace(enrollment, usedEnrollment))).isEqualTo("42501");
        assertThat(m.post("/heartbeat", REMOTE_HEARTBEAT.formatted("false", "false"), m.credential).status()).isEqualTo(200);
        // isolamento: a habilitacao de uma organizacao nao e visivel no contexto de outra
        AgentFixture other = new AgentFixture(DB);
        try (Connection c = DB.connectAs("nekkora_ingestion"); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("SELECT set_config('nekkora.organization_id', '" + other.org + "', true)");
            try (java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM agent_remote_enablements")) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
            c.rollback();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
