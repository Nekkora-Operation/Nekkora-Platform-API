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
 * Logout de IDENTIDADE "sem limitador" (como o logout por organizacao, ReducingAccessRoutesIT): com a cota R7 do IP
 * ESGOTADA (controle imediatamente antes: escrita comum recebe 429), o logout responde 204 e revoga de fato; o CSRF
 * continua valendo nele. Contexto e Redis EXCLUSIVOS, com a recomposicao da R7 reduzida a 0,001 ficha/s, so aqui.
 */
class IdentityLogoutLimitsIT extends IdentityTestBase {

    private static final RealRedis REDIS = RealRedis.exclusive();

    static {
        exclusiveRedis = REDIS;
    }

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.limits.r7-per-second", () -> "0.001");
    }

    @AfterAll
    static void cleanUp() {
        exclusiveRedis = null;
        REDIS.shutDown();
    }

    @Test
    void logoutDeIdentidadeNaoEBloqueadoPelaCotaR7Esgotada() throws Exception {
        String e = email();
        UUID id = identidadeAtiva(e, SENHA);
        BrowserClient b = sessaoPelaEntradaGeral(e, id);
        String hash = Tokens.sha256Hex(b.cookies().get("__Host-nekkora_id"));
        int limited = 0;
        for (int i = 0; i < 90 && limited < 3; i++) {
            if (b.get("/api/v1/csrf").status() == 429) {
                limited++;
            }
        }
        assertThat(limited).as("cota R7 do IP esgotada").isEqualTo(3);
        BrowserClient.Response controle = b.post("/api/v1/auth/login", "{\"email\":\"" + e + "\",\"password\":\"" + SENHA + "\"}");
        assertThat(controle.status()).as("controle: escrita comum com a cota esgotada, imediatamente antes").isEqualTo(429);
        String csrf = b.csrf;
        b.csrf = "token-errado-" + "x".repeat(30);
        assertThat(b.post("/api/v1/auth/logout", null).status()).as("CSRF continua valendo").isEqualTo(403);
        assertThat(value("SELECT revoked_at IS NULL FROM identity_sessions WHERE session_token_hash = '" + hash + "'")).isEqualTo("t");
        b.csrf = csrf;
        assertThat(b.post("/api/v1/auth/logout", null).status()).as("logout com a cota esgotada").isEqualTo(204);
        assertThat(value("SELECT revoked_at IS NOT NULL FROM identity_sessions WHERE session_token_hash = '" + hash + "'")).as("revogada de fato").isEqualTo("t");
    }
}
