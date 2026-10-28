package com.nekkora.agentgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.RealDatabase;
import com.nekkora.core.testsupport.RealRedis;

/**
 * Limites e dependencias do canal: lotes por organizacao (429 com Retry-After, nada gravado) e Redis indisponivel (503 sem
 * ACK, nada gravado). Em nenhum caso a resposta pede reexecucao: o Agent so reenvia os mesmos fatos depois. Redis
 * EXCLUSIVO desta classe (e derrubado no ultimo teste).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = AgentGatewayApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentGatewayLimitsIT {

    static final RealDatabase DB = RealDatabase.shared();
    static final RealRedis REDIS = RealRedis.exclusive();
    static AgentFixture fx;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry r) {
        r.add("nekkora.database.url", DB::url);
        r.add("nekkora.database.user", () -> "nekkora_ingestion");
        r.add("nekkora.database.password", () -> DB.password("nekkora_ingestion"));
        r.add("spring.data.redis.host", REDIS::host);
        r.add("spring.data.redis.port", REDIS::port);
        r.add("nekkora.trusted-proxies", () -> "127.0.0.1/32,::1/128");
        r.add("nekkora.limits.batches-per-minute", () -> "2");
    }

    @BeforeAll
    static void fixture() {
        fx = new AgentFixture(DB);
    }

    @AfterAll
    static void down() {
        REDIS.shutDown();
    }

    String paired(MachineClient m) throws Exception {
        UUID worker = fx.worker("W-lim-" + UUID.randomUUID().toString().substring(0, 4));
        UUID authorization = fx.localRun(worker, fx.authorizeWorker(worker));
        m.pair(fx.pairingCode(worker, "Estacao lim", "15 minutes"));
        String ex = "ex-" + UUID.randomUUID().toString().replace("-", "");
        assertThat(m.register(ex, authorization.toString(), AgentFixture.REAL_FINGERPRINT, "{\"state\":\"RUNNING\"}").status()).isEqualTo(201);
        return ex;
    }

    static List<String> rewrite(String ex, int from, int to) throws Exception {
        return AgentGatewayIT.envelopes("a1-sucesso").subList(from, to).stream()
                .map(l -> l.replace("ex-6f670400dd5d9a00d9569fdec8d2c4f4", ex)).toList();
    }

    @Test
    @Order(1)
    void loteAcimaDaCotaDaOrganizacaoE429SemGravarNada() throws Exception {
        MachineClient m = new MachineClient(port, "10.70.0.1");
        String ex = paired(m);
        assertThat(m.events(ex, rewrite(ex, 0, 10), null).status()).isEqualTo(200);
        assertThat(m.events(ex, rewrite(ex, 10, 20), null).status()).isEqualTo(200);
        MachineClient.Response limited = m.events(ex, rewrite(ex, 20, 30), null);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        assertThat(fx.count("SELECT count(*) FROM agent_run_events e JOIN agent_runs r ON r.agent_run_id = e.agent_run_id WHERE r.agent_execution_id = '" + ex
                + "'")).isEqualTo(20);
    }

    @Test
    @Order(2)
    void redisForaDa503SemAckENadaGravado() throws Exception {
        MachineClient m = new MachineClient(port, "10.70.0.2");
        String ex = paired(m);
        REDIS.shutDown();
        MachineClient.Response r = m.events(ex, rewrite(ex, 0, 10), null);
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.raw()).isEqualTo("{\"error\":\"service_unavailable\"}");
        assertThat(fx.count("SELECT count(*) FROM agent_run_events e JOIN agent_runs r ON r.agent_run_id = e.agent_run_id WHERE r.agent_execution_id = '" + ex
                + "'")).isZero();
        assertThat(fx.value("SELECT supervisor_state FROM agent_runs WHERE agent_execution_id = '" + ex + "'")).isEqualTo("3");
    }
}
