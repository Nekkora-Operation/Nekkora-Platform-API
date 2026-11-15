package com.nekkora.agentgateway;

import static org.assertj.core.api.Assertions.assertThat;

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
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01a - com o recurso DESLIGADO (o padrao), nenhuma maquina e habilitada, mesmo com um
 * codigo marcado valido e a confirmacao da maquina; a recusa e compreensivel e nada e consumido. O pareamento comum e o
 * batimento de um Agent anterior continuam como sempre.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = AgentGatewayApplication.class)
class RemoteEnablementDisabledIT {

    static final RealDatabase DB = RealDatabase.shared();
    static AgentFixture fx;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry r) {
        RealRedis redis = RealRedis.shared();
        r.add("nekkora.database.url", DB::url);
        r.add("nekkora.database.user", () -> "nekkora_ingestion");
        r.add("nekkora.database.password", () -> DB.password("nekkora_ingestion"));
        r.add("spring.data.redis.host", redis::host);
        r.add("spring.data.redis.port", redis::port);
        r.add("nekkora.trusted-proxies", () -> "127.0.0.1/32,::1/128");
        r.add("nekkora.limits.redeem-failures-per-hour", () -> "1000");
        // nekkora.remote-execution.enabled ausente: desligado
    }

    @BeforeAll
    static void fixture() {
        fx = new AgentFixture(DB);
    }

    MachineClient client() {
        return new MachineClient(port, "10.62." + (int) (Math.random() * 250) + "." + (1 + (int) (Math.random() * 250)));
    }

    @Test
    void comORecursoDesligadoOCodigoMarcadoNaoHabilitaENadaEConsumido() {
        UUID worker = fx.worker("W-desl-" + UUID.randomUUID().toString().substring(0, 6));
        String marked = fx.remotePairingCode(worker, "Estacao marcada");
        MachineClient.Response r = client().redeemWithIntent(marked, Tokens.newToken(), "pair-remote");
        assertThat(r.status()).as(r.raw()).isEqualTo(409);
        assertThat(r.body().get("error").asString()).isEqualTo("conflict");
        assertThat(r.body().get("reason").asString()).isEqualTo("remote_execution_unavailable");
        assertThat(fx.value("SELECT count(*) FROM agent_enrollments WHERE worker_id = '" + worker + "' AND consumed_at IS NOT NULL")).isEqualTo("0");
        assertThat(fx.value("SELECT count(*) FROM agent_remote_enablements WHERE worker_id = '" + worker + "'")).isEqualTo("0");
        assertThat(fx.value("SELECT count(*) FROM worker_agent_assignments WHERE worker_id = '" + worker + "'")).isEqualTo("0");
        // o canal de sempre nao muda: pareamento comum e batimento de um Agent anterior
        MachineClient legacy = client();
        legacy.pair(fx.pairingCode(fx.worker("W-leg-" + UUID.randomUUID().toString().substring(0, 6)), "Estacao comum", "15 minutes"));
        MachineClient.Response hb = legacy.heartbeat();
        assertThat(hb.status()).isEqualTo(200);
        assertThat(hb.body().has("remote")).isFalse();
        // T01b: com o recurso desligado, nenhuma credencial e renovada pela maquina
        MachineClient.Response renew = legacy.post("/remote/credential/renew", "{\"credentialSecret\":\"" + Tokens.newToken() + "\"}", legacy.credential);
        assertThat(renew.status()).isEqualTo(409);
        assertThat(renew.body().get("reason").asString()).isEqualTo("remote_execution_unavailable");
        assertThat(legacy.heartbeat().status()).isEqualTo(200);
    }
}
