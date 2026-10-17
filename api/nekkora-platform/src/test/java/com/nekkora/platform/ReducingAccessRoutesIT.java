package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.testsupport.RealRedis;
import com.nekkora.core.token.Tokens;

/**
 * Contrato §5.3: logout e revoke-all sao "sem limitador". Com o Redis FUNCIONANDO e a cota R7 da sessao ESGOTADA
 * (controle: leitura e escrita comuns recebem 429), as duas rotas respondem 204 e revogam de fato. Autenticacao,
 * Origin e CSRF continuam valendo nelas.
 *
 * <p>Contexto e Redis EXCLUSIVOS desta classe, com a recomposicao da R7 reduzida a 0,001 ficha/s (uma ficha a cada
 * ~17 min): o balde da sessao fica esgotado durante todo o cenario, sem depender da latencia das requisicoes nem de
 * pausas da maquina. Script e decisao do limitador sao os reais; so a taxa muda, e so neste contexto. Com 2/s, a
 * fracao que sobra no balde e a recomposicao podiam devolver uma ficha antes do controle ou da acao.
 */
class ReducingAccessRoutesIT extends PlatformTestBase {

    /** Recomposicao exclusiva deste contexto (a capacidade continua 60). */
    static final String R7_PER_SECOND = "0.001";

    private static final RealRedis REDIS = RealRedis.exclusive();

    static {
        exclusiveRedis = REDIS;
    }

    static Org org;

    /** Metodo proprio: contexto Spring distinto, com o Redis exclusivo e a taxa reduzida. */
    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.limits.r7-per-second", () -> R7_PER_SECOND);
    }

    @AfterAll
    static void cleanUp() {
        exclusiveRedis = null;
        REDIS.shutDown();
    }

    @BeforeAll
    static void fixture() {
        org = newOrganization();
    }

    private String base() {
        return "/api/v1/o/" + org.id();
    }

    /** Esgota a cota R7 (capacidade 60) da sessao do navegador e confirma o 429 numa leitura e numa escrita comum. */
    private void exhaustR7(BrowserClient b) {
        int limited = 0;
        for (int i = 0; i < 90 && limited < 3; i++) {
            if (b.get(base() + "/me").status() == 429) {
                limited++;
            }
        }
        assertThat(limited).as("cota R7 da sessao esgotada").isEqualTo(3);
        BrowserClient.Response write = b.post(base() + "/auth/mfa/step-up", "{\"code\":\"000000\"}");
        assertThat(write.status()).as("controle: escrita comum com a cota esgotada").isEqualTo(429);
        assertThat(write.headers().firstValue("Retry-After")).isPresent();
    }

    /** Controle imediatamente antes da acao: a cota da sessao continua esgotada (leitura recusada nao consome ficha). */
    private void stillExhausted(BrowserClient b, String before) {
        assertThat(b.get(base() + "/me").status()).as("controle: cota ainda esgotada imediatamente antes de " + before).isEqualTo(429);
    }

    private static long active(String cookie) throws Exception {
        return AuthenticationFlowIT.count("SELECT count(*) FROM user_sessions WHERE session_token_hash = '" + Tokens.sha256Hex(cookie)
                + "' AND revoked_at IS NULL");
    }

    @Test
    void logoutIsNotBlockedByExhaustedR7() throws Exception {
        TestUser u = internalUser(org, true, 2);
        BrowserClient b = browser();
        firstLogin(b, org.id(), u);
        String cookie = b.cookies().get("__Host-nekkora_session");
        exhaustR7(b);
        stillExhausted(b, "logout");
        assertThat(b.post(base() + "/auth/logout", null).status()).as("logout com a cota esgotada").isEqualTo(204);
        assertThat(active(cookie)).as("sessao revogada de fato").isZero();
    }

    @Test
    void revokeAllIsNotBlockedByExhaustedR7() throws Exception {
        TestUser u = internalUser(org, true, 2);
        BrowserClient a = browser();
        firstLogin(a, org.id(), u);
        BrowserClient other = browser();
        assertThat(fullLogin(other, org.id(), u).status()).isEqualTo(200);
        String cookieA = a.cookies().get("__Host-nekkora_session");
        String cookieOther = other.cookies().get("__Host-nekkora_session");
        exhaustR7(a);

        String csrf = a.csrf;
        a.csrf = null;
        assertThat(a.post(base() + "/auth/sessions/revoke-all", null).status()).as("sem CSRF continua 403").isEqualTo(403);
        a.csrf = csrf;
        String origin = a.origin;
        a.origin = "https://atacante.example";
        assertThat(a.post(base() + "/auth/sessions/revoke-all", null).status()).as("Origin estranho continua 403").isEqualTo(403);
        a.origin = origin;
        assertThat(active(cookieA)).as("recusas acima nao revogaram nada").isEqualTo(1);

        stillExhausted(a, "revoke-all");
        assertThat(a.post(base() + "/auth/sessions/revoke-all", null).status()).as("revoke-all com a cota esgotada").isEqualTo(204);
        assertThat(active(cookieA)).as("sessao atual revogada").isZero();
        assertThat(active(cookieOther)).as("outra sessao revogada").isZero();
        assertThat(browser().post(base() + "/auth/sessions/revoke-all", null).status()).as("sem sessao continua 401").isEqualTo(401);
    }
}
