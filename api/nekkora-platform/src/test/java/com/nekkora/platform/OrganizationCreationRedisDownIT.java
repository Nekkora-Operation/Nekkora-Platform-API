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
import com.nekkora.core.token.Tokens;

/**
 * Criacao de organizacao (P2) com o Redis INDISPONIVEL: R12 e R11 falham fechado (503), antes da funcao; nada criado.
 * Redis EXCLUSIVO desta classe, derrubado de proposito.
 */
// contexto proprio (propriedades da classe): fechado ao fim dela, para nao manter um pool a mais aberto contra o limite
// de conexoes do PostgreSQL de teste, compartilhado pela suite inteira
@DirtiesContext
class OrganizationCreationRedisDownIT extends IdentityTestBase {

    private static final RealRedis REDIS = RealRedis.exclusive();

    static {
        exclusiveRedis = REDIS;
    }

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.criacao-organizacao.enabled", () -> "true");
        r.add("nekkora.test.exclusive-redis-criacao", () -> "yes");
    }

    @AfterAll
    static void cleanUp() {
        exclusiveRedis = null;
    }

    @Test
    void criacaoFalhaFechadoSemRedis() throws Exception {
        String e = email();
        UUID id = identidadeAtiva(e, SENHA);
        BrowserClient b = sessaoPelaEntradaGeral(e, id);
        exec("UPDATE identity_sessions SET mfa_step_up_at = clock_timestamp() WHERE session_token_hash = '"
                + Tokens.sha256Hex(b.cookies().get("__Host-nekkora_id")) + "'");
        REDIS.shutDown();

        BrowserClient.Response r = b.post("/api/v1/organizations", "{\"name\":\"Sem Redis\"}");
        assertThat(r.status()).isEqualTo(503);
        assertThat(count("SELECT count(*) FROM identity_users WHERE identity_id = '" + id + "'")).as("nada criado").isZero();
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + id + "' AND event_type = 6")).as("sem evento").isZero();
        assertThat(count("SELECT count(*) FROM organizations WHERE name = 'Sem Redis'")).isZero();
    }
}
