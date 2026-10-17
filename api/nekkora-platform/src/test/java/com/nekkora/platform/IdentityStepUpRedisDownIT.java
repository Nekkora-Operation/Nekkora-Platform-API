package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.testsupport.RealRedis;

/**
 * Step-up da sessao de identidade (P1) com o Redis INDISPONIVEL: R12 e R8m falham fechado (503), sem conferir o codigo,
 * sem marcador, sem passo e sem evento. Redis EXCLUSIVO desta classe, derrubado de proposito.
 */
// contexto proprio (propriedades da classe): fechado ao fim dela, para nao manter um pool a mais aberto contra o limite
// de conexoes do PostgreSQL de teste, compartilhado pela suite inteira
@DirtiesContext
class IdentityStepUpRedisDownIT extends IdentityTestBase {

    private static final RealRedis REDIS = RealRedis.exclusive();

    static {
        exclusiveRedis = REDIS;
    }

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.test.exclusive-redis-step-up", () -> "yes");
    }

    @AfterAll
    static void cleanUp() {
        exclusiveRedis = null;
    }

    @Test
    void stepUpFalhaFechadoSemRedis() throws Exception {
        String e = email();
        UUID id = identidadeAtiva(e, SENHA);
        BrowserClient b = sessaoPelaEntradaGeral(e, id);
        String passo = value("SELECT last_used_step FROM identity_mfa_factors WHERE identity_id = '" + id + "' AND confirmed_at IS NOT NULL");
        REDIS.shutDown();

        BrowserClient.Response r = b.post("/api/v1/auth/step-up", "{\"code\":\"" + code(id) + "\"}");
        assertThat(r.status()).isEqualTo(503);
        assertThat(count("SELECT count(*) FROM identity_sessions WHERE identity_id = '" + id + "' AND mfa_step_up_at IS NOT NULL")).as("sem marcador").isZero();
        assertThat(value("SELECT last_used_step FROM identity_mfa_factors WHERE identity_id = '" + id + "' AND confirmed_at IS NOT NULL"))
                .as("codigo nao conferido: passo intocado").isEqualTo(passo);
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + id + "' AND event_type = 13")).as("sem evento").isZero();
    }
}
