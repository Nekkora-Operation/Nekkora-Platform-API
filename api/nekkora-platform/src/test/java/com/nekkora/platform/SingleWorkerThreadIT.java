package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.nekkora.core.testsupport.BrowserClient;

/**
 * Mesmo ponto do SecurityContextLifecycleIT, pelo Tomcat real com UM unico thread de trabalho: toda requisicao usa a
 * mesma thread. A rota inexistente distingue um contexto vazado: com autenticacao ela passa pela autorizacao e cai no
 * 404 (inclusive o despacho de erro para /error); sem autenticacao, recebe 401 antes. Se a identidade de uma
 * requisicao anterior ficasse na thread, a requisicao anonima seguinte receberia 404 em vez de 401.
 */
@TestPropertySource(properties = {"server.tomcat.threads.max=1", "server.tomcat.threads.min-spare=1"})
class SingleWorkerThreadIT extends PlatformTestBase {

    static Org org;

    @BeforeAll
    static void fixture() {
        org = newOrganization();
    }

    @Test
    void anonymousRequestAfterAuthenticatedOnesOnTheOnlyWorkerThreadIsUnauthenticated() throws Exception {
        TestUser u = internalUser(org, true, 2);
        BrowserClient auth = browser();
        firstLogin(auth, org.id(), u);
        BrowserClient anon = browser();
        String missing = "/api/v1/o/" + org.id() + "/rota-inexistente";

        assertThat(anon.get(missing).status()).as("controle: anonimo na rota inexistente").isEqualTo(401);
        for (int i = 0; i < 5; i++) {
            assertThat(auth.get("/api/v1/o/" + org.id() + "/me").status()).as("autenticada com sucesso").isEqualTo(200);
            assertThat(anon.get(missing).status()).as("anonima logo depois de um sucesso").isEqualTo(401);
            assertThat(auth.get(missing).status()).as("autenticada com erro (404 + despacho de erro)").isEqualTo(404);
            assertThat(anon.get(missing).status()).as("anonima logo depois de um erro").isEqualTo(401);
            assertThat(anon.get("/api/v1/o/" + org.id() + "/me").status()).isEqualTo(401);
        }
    }
}
