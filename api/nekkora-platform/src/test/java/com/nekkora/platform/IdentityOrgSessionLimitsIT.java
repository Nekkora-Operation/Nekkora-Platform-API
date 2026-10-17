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
 * R10 (desenho da Missao 1, secao 7): a troca por sessao de organizacao e limitada POR SESSAO DE IDENTIDADE. Contexto e
 * Redis EXCLUSIVOS, com o teto reduzido a 2 trocas por janela, so aqui.
 */
// contexto proprio (propriedades da classe): fechado ao fim dela, para nao manter um pool a mais aberto contra o limite
// de conexoes do PostgreSQL de teste, compartilhado pela suite inteira
@DirtiesContext
class IdentityOrgSessionLimitsIT extends IdentityTestBase {

    private static final RealRedis REDIS = RealRedis.exclusive();

    static {
        exclusiveRedis = REDIS;
    }

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.limits.r10-exchanges", () -> "2");
    }

    @AfterAll
    static void cleanUp() {
        exclusiveRedis = null;
        REDIS.shutDown();
    }

    @Test
    void r10LimitaATrocaPorSessaoDeIdentidade() throws Exception {
        ComFator a = comFator();
        String corpo = "{\"organizationId\":\"" + UUID.randomUUID() + "\"}";
        assertThat(a.browser().post("/api/v1/sessions/organization", corpo).field("status")).isEqualTo("sem_acesso");
        assertThat(a.browser().post("/api/v1/sessions/organization", corpo).field("status")).isEqualTo("sem_acesso");
        BrowserClient.Response r = a.browser().post("/api/v1/sessions/organization", corpo);
        assertThat(r.status()).as("terceira troca da mesma sessao").isEqualTo(429);
        assertThat(r.headers().firstValue("Retry-After")).isPresent();

        ComFator b = comFator();
        assertThat(b.browser().post("/api/v1/sessions/organization", corpo).field("status")).as("outra sessao de identidade: cota propria")
                .isEqualTo("sem_acesso");
        assertThat(a.browser().get("/api/v1/me/organizations").status()).as("a listagem nao consome R10").isEqualTo(200);
    }
}
