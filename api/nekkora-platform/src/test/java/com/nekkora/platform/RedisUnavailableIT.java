package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.testsupport.RealRedis;

/**
 * Redis indisponivel (contrato 8.6, §2.1, item 6): 503 (nao 429) no pre-login e nas escritas autenticadas; logout
 * e revoke-all seguem; leituras autenticadas seguem (sessao validada no banco). Redis EXCLUSIVO desta classe,
 * derrubado de proposito.
 */
class RedisUnavailableIT extends PlatformTestBase {

    private static final RealRedis REDIS = RealRedis.exclusive();

    static {
        exclusiveRedis = REDIS;
    }

    /** Metodo proprio: garante um contexto Spring distinto (nao reaproveitado do cache das outras classes). */
    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.test.exclusive-redis", () -> "yes");
    }

    @org.junit.jupiter.api.AfterAll
    static void cleanUp() {
        exclusiveRedis = null;
    }

    @Test
    void withoutRedis() {
        Org org = newOrganization();
        TestUser u = internalUser(org, true, 2);
        BrowserClient n = browser();
        firstLogin(n, org.id(), u);
        BrowserClient other = browser();
        fetchPreAuthCsrf(other);
        REDIS.shutDown();

        BrowserClient.Response login = other.post("/api/v1/o/" + org.id() + "/auth/login", "{\"email\":\"" + u.email() + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.status()).as("pre-login falha fechado").isEqualTo(503);
        assertThat(login.body()).isEqualTo("{\"error\":\"service_unavailable\"}");
        assertThat(login.headers().firstValue("Retry-After")).isPresent();
        assertThat(browser().get("/api/v1/csrf").status()).as("CSRF pre-autenticacao depende do Redis").isEqualTo(503);
        assertThat(n.get("/api/v1/o/" + org.id() + "/me").status()).as("leitura autenticada segue").isEqualTo(200);
        assertThat(n.post("/api/v1/o/" + org.id() + "/auth/password", "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"nova-senha-longa-77\"}").status())
                .as("escrita autenticada: 503").isEqualTo(503);
        assertThat(n.post("/api/v1/o/" + org.id() + "/auth/sessions/revoke-all", null).status()).as("revoke-all segue sem limitador").isEqualTo(204);
        assertThat(n.get("/api/v1/o/" + org.id() + "/me").status()).isEqualTo(401);
    }
}
