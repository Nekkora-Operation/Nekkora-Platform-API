package com.nekkora.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Chave propria da criacao de organizacao (contrato P2): a rota so abre com ela E a entrada geral ligadas; as demais
 * rotas da entrada geral nao dependem dela. Desligada: 404 antes da cadeia (a cadeia nao e chamada).
 */
class IdentityRoutesGateTest {

    @Test
    void criacaoDeOrganizacaoExigeAsDuasChaves() throws Exception {
        for (boolean entrada : new boolean[] {false, true}) {
            for (boolean criacao : new boolean[] {false, true}) {
                IdentityRoutesGate g = new IdentityRoutesGate(false, entrada, criacao);
                for (String metodo : new String[] {"POST", "GET"}) {
                    assertThat(passa(g, metodo, IdentityOrganizationsController.ORGANIZATIONS))
                            .as("entrada=" + entrada + " criacao=" + criacao + " " + metodo).isEqualTo(entrada && criacao);
                }
                assertThat(passa(g, "POST", "/api/v1/%6Frganizations")).as("caminho escapado: mesma decisao").isEqualTo(entrada && criacao);
                assertThat(passa(g, "GET", IdentityOrganizationsController.MY_ORGANIZATIONS)).as("a lista depende so da entrada geral").isEqualTo(entrada);
                assertThat(passa(g, "POST", IdentityAuthController.STEP_UP)).as("o step-up depende so da entrada geral").isEqualTo(entrada);
            }
        }
    }

    private static boolean passa(IdentityRoutesGate g, String metodo, String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(metodo, uri);
        req.setServletPath(uri.replace("%6F", "o"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        g.doFilter(req, res, chain);
        if (chain.getRequest() == null) {
            assertThat(res.getStatus()).isEqualTo(404);
            return false;
        }
        return true;
    }
}
