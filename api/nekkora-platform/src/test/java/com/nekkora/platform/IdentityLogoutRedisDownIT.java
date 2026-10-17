package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.testsupport.RealRedis;
import com.nekkora.core.token.Tokens;

/**
 * Logout de IDENTIDADE com o Redis INDISPONIVEL (como RedisUnavailableIT para o logout por organizacao): o login e as
 * demais escritas falham fechado (503), mas o logout responde 204 e revoga; o CSRF da sessao continua valendo (conferido
 * no banco, sem Redis). Redis EXCLUSIVO desta classe, derrubado de proposito.
 */
class IdentityLogoutRedisDownIT extends IdentityTestBase {

    private static final RealRedis REDIS = RealRedis.exclusive();

    static {
        exclusiveRedis = REDIS;
    }

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.test.exclusive-redis-identidade", () -> "yes");
    }

    @AfterAll
    static void cleanUp() {
        exclusiveRedis = null;
    }

    @Test
    void logoutDeIdentidadeFuncionaComORedisFora() throws Exception {
        String e = email();
        UUID id = identidadeAtiva(e, SENHA);
        BrowserClient b = sessaoPelaEntradaGeral(e, id);
        String hash = Tokens.sha256Hex(b.cookies().get("__Host-nekkora_id"));
        BrowserClient outro = client();
        REDIS.shutDown();

        BrowserClient.Response login = outro.post("/api/v1/auth/login", "{\"email\":\"" + e + "\",\"password\":\"" + SENHA + "\"}");
        assertThat(login.status()).as("controle: login falha fechado").isEqualTo(503);
        String csrf = b.csrf;
        b.csrf = "token-errado-" + "x".repeat(30);
        assertThat(b.post("/api/v1/auth/logout", null).status()).as("CSRF continua valendo sem Redis").isEqualTo(403);
        assertThat(value("SELECT revoked_at IS NULL FROM identity_sessions WHERE session_token_hash = '" + hash + "'")).isEqualTo("t");
        b.csrf = csrf;
        assertThat(b.post("/api/v1/auth/logout", null).status()).as("logout sem Redis").isEqualTo(204);
        assertThat(value("SELECT revoked_at IS NOT NULL FROM identity_sessions WHERE session_token_hash = '" + hash + "'")).as("revogada de fato").isEqualTo("t");
    }
}
