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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.RealDatabase;
import com.nekkora.core.testsupport.RealRedis;
import com.nekkora.core.token.Tokens;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01c (parte 1) - a maquina habilitada publica o inventario de Python, pelo gateway
 * REAL (HTTP, papel nekkora_ingestion, PostgreSQL com as migrations ate a 044, recurso LIGADO). O inventario e um retrato:
 * so identificador opaco, versao, tipo e instante da observacao - nenhum caminho entra, nem por campo a mais.
 */
class PythonInventoryIT extends RemoteEnabledGatewayIT {

    static AgentFixture fx;

    @BeforeAll
    static void fixture() {
        fx = new AgentFixture(DB);
    }

    private record Machine(MachineClient client, String agent, String credential) {
    }

    private Machine paired(boolean remote) {
        UUID worker = fx.worker("W-inv-" + UUID.randomUUID().toString().substring(0, 6));
        MachineClient m = new MachineClient(port, "10.64." + (int) (Math.random() * 250) + "." + (1 + (int) (Math.random() * 250)));
        String secret = Tokens.newToken();
        MachineClient.Response r = remote ? m.redeemWithIntent(fx.remotePairingCode(worker, "Estacao remota"), secret, "pair-remote")
                : m.redeemWithIntent(fx.pairingCode(worker, "Estacao comum", "15 minutes"), secret, "pair");
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        return new Machine(m, r.body().get("agentId").asString(), MachineToken.credential(fx.org, UUID.fromString(r.body().get("credentialId").asString()), secret));
    }

    private static String id(char c) {
        return String.valueOf(c).repeat(43);
    }

    private static String env(String id, String version, String kind) {
        return "{\"environmentId\":\"" + id + "\",\"pythonVersion\":\"" + version + "\",\"kind\":\"" + kind + "\",\"observedAt\":\"2026-11-15T12:00:00Z\"}";
    }

    private MachineClient.Response put(Machine m, String body, String bearer) {
        return m.client().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/agent/api/v1/remote/python-environments"))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body)), bearer);
    }

    private String stored(Machine m) {
        return fx.value("SELECT coalesce(string_agg(environment_id || ':' || python_version || ':' || kind, ',' ORDER BY environment_id), '') "
                + "FROM agent_python_environments WHERE agent_id = '" + m.agent() + "'");
    }

    @Test
    void aMaquinaHabilitadaPublicaORetratoECadaPublicacaoSubstituiAAnterior() {
        Machine m = paired(true);
        MachineClient.Response r = put(m, "{\"environments\":[" + env(id('a'), "3.12.4", "INSTALLATION") + "," + env(id('b'), "3.13.0rc1", "VIRTUAL_ENVIRONMENT") + "]}",
                m.credential());
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        assertThat(r.body().get("count").asInt()).isEqualTo(2);
        assertThat(r.body().propertyNames()).containsExactlyInAnyOrder("count", "reportedAt");
        assertThat(stored(m)).isEqualTo(id('a') + ":3.12.4:1," + id('b') + ":3.13.0rc1:2");
        // outra publicacao substitui o retrato inteiro
        assertThat(put(m, "{\"environments\":[" + env(id('c'), "3.14.1", "INSTALLATION") + "]}", m.credential()).status()).isEqualTo(200);
        assertThat(stored(m)).isEqualTo(id('c') + ":3.14.1:1");
        // lista vazia e valida: a maquina nao achou Python
        assertThat(put(m, "{\"environments\":[]}", m.credential()).body().get("count").asInt()).isZero();
        assertThat(stored(m)).isEmpty();
    }

    @Test
    void nenhumCaminhoNemCampoAMaisEntraERecusaNaoMudaORetrato() {
        Machine m = paired(true);
        String good = env(id('a'), "3.12.4", "INSTALLATION");
        assertThat(put(m, "{\"environments\":[" + good + "]}", m.credential()).status()).isEqualTo(200);
        String path = "C:\\\\Users\\\\fulano\\\\AppData\\\\Local\\\\Programs\\\\Python\\\\Python312";
        String[] refused = {
                // caminho no lugar do identificador, ou num campo a mais
                "{\"environments\":[" + env(path, "3.12.4", "INSTALLATION") + "]}",
                "{\"environments\":[" + good.replace("}", ",\"path\":\"" + path + "\"}") + "]}",
                "{\"environments\":[" + good.replace("}", ",\"packages\":[\"requests==2.32.3\"]}") + "]}",
                "{\"environments\":[" + good + "],\"hostname\":\"PC-DA-FULANA\"}",
                // versao, tipo e instante fora do formato
                "{\"environments\":[" + env(id('b'), "3.12", "INSTALLATION") + "]}",
                "{\"environments\":[" + env(id('b'), "2.7.18", "INSTALLATION") + "]}",
                "{\"environments\":[" + env(id('b'), "3.12.4 (C:\\\\Python312)", "INSTALLATION") + "]}",
                "{\"environments\":[" + env(id('b'), "3.12.4", "CONDA") + "]}",
                "{\"environments\":[" + env(id('b'), "3.12.4", "INSTALLATION").replace("2026-11-15T12:00:00Z", "ontem") + "]}",
                "{\"environments\":[" + env(id('b'), "3.12.4", "INSTALLATION").replace("2026-11-15", "2099-01-01") + "]}",
                // identificador repetido; corpo malformado
                "{\"environments\":[" + good + "," + good + "]}",
                "{\"environments\":\"todos\"}", "{}", "[]"};
        for (String body : refused) {
            assertThat(put(m, body, m.credential()).status()).as(body).isEqualTo(400);
        }
        // mais de 32 ambientes
        StringBuilder many = new StringBuilder("{\"environments\":[");
        for (int k = 0; k < 33; k++) {
            many.append(k == 0 ? "" : ",").append(env(String.format("%043d", k), "3.12.4", "INSTALLATION"));
        }
        assertThat(put(m, many.append("]}").toString(), m.credential()).status()).isEqualTo(400);
        assertThat(stored(m)).as("nenhuma recusa mexeu no retrato").isEqualTo(id('a') + ":3.12.4:1");
        // 32 e o teto
        StringBuilder full = new StringBuilder("{\"environments\":[");
        for (int k = 0; k < 32; k++) {
            full.append(k == 0 ? "" : ",").append(env(String.format("%043d", k), "3.12.4", "INSTALLATION"));
        }
        assertThat(put(m, full.append("]}").toString(), m.credential()).body().get("count").asInt()).isEqualTo(32);
    }

    @Test
    void soAMaquinaHabilitadaPublicaECadaUmaSoOProprioRetrato() {
        Machine a = paired(true);
        Machine b = paired(true);
        String body = "{\"environments\":[" + env(id('a'), "3.12.4", "INSTALLATION") + "]}";
        assertThat(put(a, body, a.credential()).status()).isEqualTo(200);
        assertThat(put(b, "{\"environments\":[" + env(id('b'), "3.13.1", "INSTALLATION") + "]}", b.credential()).status()).isEqualTo(200);
        assertThat(stored(a)).isEqualTo(id('a') + ":3.12.4:1");
        assertThat(stored(b)).isEqualTo(id('b') + ":3.13.1:1");
        // sem credencial; pareada mas NAO habilitada (o Agent anterior)
        assertThat(put(a, body, null).status()).isEqualTo(401);
        Machine plain = paired(false);
        MachineClient.Response no = put(plain, body, plain.credential());
        assertThat(no.status()).isEqualTo(409);
        assertThat(no.body().get("reason").asString()).isEqualTo("remote_execution_not_enabled");
        assertThat(stored(plain)).isEmpty();
        // o banco: a ingestao nao grava inventario de maquina nao habilitada, nao altera uma linha e nao passa de 32
        String insert = "INSERT INTO agent_python_environments (agent_id, environment_id, organization_id, python_version, kind, observed_at) VALUES ('%s', '%s', '"
                + fx.org + "', '3.12.4', 1, clock_timestamp())";
        assertThat(asRole("nekkora_ingestion", insert.formatted(plain.agent(), id('z')))).isEqualTo("42501");
        assertThat(asRole("nekkora_ingestion", "UPDATE agent_python_environments SET python_version = '3.99.0' WHERE agent_id = '" + a.agent() + "'")).isEqualTo("42501");
        assertThat(asRole("nekkora_ingestion", insert.formatted(a.agent(), "C:/Python312/python.exe"))).as("o banco tambem recusa um caminho").isEqualTo("23514");
        // as pessoas (nekkora_platform) so leem
        assertThat(asRole("nekkora_platform", insert.formatted(a.agent(), id('y')))).isEqualTo("42501");
        assertThat(asRole("nekkora_platform", "DELETE FROM agent_python_environments WHERE agent_id = '" + a.agent() + "'")).isEqualTo("42501");
        // limite de publicacoes por maquina: 6 por minuto
        int limited = 0;
        for (int k = 0; k < 8; k++) {
            limited += put(b, body, b.credential()).status() == 429 ? 1 : 0;
        }
        assertThat(limited).isGreaterThanOrEqualTo(1);
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
}
