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
 * Listagem e troca com o Redis INDISPONIVEL: R12 e R10 falham fechado (503), sem sessao emitida e sem evento. (O
 * IdentityFilter ainda resolve a sessao de identidade antes da recusa: nao se afirma ausencia de consulta ao banco.)
 * Redis EXCLUSIVO desta classe, derrubado de proposito.
 */
// contexto proprio (propriedades da classe): fechado ao fim dela, para nao manter um pool a mais aberto contra o limite
// de conexoes do PostgreSQL de teste, compartilhado pela suite inteira
@DirtiesContext
class IdentityOrgSessionRedisDownIT extends IdentityTestBase {

    private static final RealRedis REDIS = RealRedis.exclusive();

    static {
        exclusiveRedis = REDIS;
    }

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.test.exclusive-redis-org-session", () -> "yes");
    }

    @AfterAll
    static void cleanUp() {
        exclusiveRedis = null;
    }

    @Test
    void listagemETrocaFalhamFechadoSemRedis() throws Exception {
        String e = email();
        UUID id = identidadeAtiva(e, SENHA);
        BrowserClient b = sessaoPelaEntradaGeral(e, id);
        assertThat(b.get("/api/v1/me/organizations").status()).as("controle com o Redis no ar").isEqualTo(200);
        REDIS.shutDown();

        assertThat(b.get("/api/v1/me/organizations").status()).isEqualTo(503);
        BrowserClient.Response r = b.post("/api/v1/sessions/organization", "{\"organizationId\":\"" + UUID.randomUUID() + "\"}");
        assertThat(r.status()).isEqualTo(503);
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + id + "' AND event_type IN (7, 8)")).as("sem emissao nem evento").isZero();
        assertThat(count("SELECT count(*) FROM user_sessions s JOIN identity_sessions i ON i.identity_session_id = s.identity_session_id WHERE i.identity_id = '" + id + "'")).isZero();
    }
}
