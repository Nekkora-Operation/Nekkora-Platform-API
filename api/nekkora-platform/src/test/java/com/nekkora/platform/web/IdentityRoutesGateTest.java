package com.nekkora.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Chaves proprias da criacao de organizacao (contrato P2) e da vinculacao (contrato P3): cada rota so abre com a sua chave
 * E a entrada geral ligadas; as demais rotas da entrada geral nao dependem delas. Desligada: 404 antes da cadeia (a cadeia
 * nao e chamada).
 */
class IdentityRoutesGateTest {

    @Test
    void criacaoDeOrganizacaoExigeAsDuasChaves() throws Exception {
        for (boolean entrada : new boolean[] {false, true}) {
            for (boolean criacao : new boolean[] {false, true}) {
                IdentityRoutesGate g = new IdentityRoutesGate(false, entrada, criacao, false);
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

    @Test
    void vinculacaoExigeAsDuasChavesEIndependeDaCriacao() throws Exception {
        for (boolean entrada : new boolean[] {false, true}) {
            for (boolean vinculacao : new boolean[] {false, true}) {
                IdentityRoutesGate g = new IdentityRoutesGate(false, entrada, true, vinculacao);
                for (String rota : new String[] {AccountLinkController.START, AccountLinkController.CONFIRM}) {
                    for (String metodo : new String[] {"POST", "GET"}) {
                        assertThat(passa(g, metodo, rota)).as("entrada=" + entrada + " vinculacao=" + vinculacao + " " + metodo + " " + rota)
                                .isEqualTo(entrada && vinculacao);
                    }
                }
                assertThat(passa(g, "POST", "/api/v1/links/%73tart")).as("caminho escapado: mesma decisao").isEqualTo(entrada && vinculacao);
                assertThat(passa(g, "POST", IdentityOrganizationsController.ORGANIZATIONS)).as("a criacao nao depende da vinculacao").isEqualTo(entrada);
                assertThat(passa(g, "POST", IdentityAuthController.STEP_UP)).as("o step-up depende so da entrada geral").isEqualTo(entrada);
            }
        }
    }

    /** Recuperacao de senha da identidade (missao 002): chave propria E a entrada geral; o resto da entrada nao depende dela. */
    @Test
    void recuperacaoDeSenhaExigeAsDuasChaves() throws Exception {
        for (boolean entrada : new boolean[] {false, true}) {
            for (boolean recuperacao : new boolean[] {false, true}) {
                IdentityRoutesGate g = new IdentityRoutesGate(false, entrada, false, false, recuperacao);
                for (String rota : new String[] {IdentityAuthController.PASSWORD_RESET_REQUEST, IdentityAuthController.PASSWORD_RESET_CONFIRM}) {
                    for (String metodo : new String[] {"POST", "GET"}) {
                        assertThat(passa(g, metodo, rota)).as("entrada=" + entrada + " recuperacao=" + recuperacao + " " + metodo + " " + rota)
                                .isEqualTo(entrada && recuperacao);
                    }
                }
                assertThat(passa(g, "POST", "/api/v1/auth/password-re%73et/request")).as("caminho escapado: mesma decisao").isEqualTo(entrada && recuperacao);
                assertThat(passa(g, "POST", IdentityAuthController.STEP_UP)).as("o step-up depende so da entrada geral").isEqualTo(entrada);
            }
        }
    }

    private static boolean passa(IdentityRoutesGate g, String metodo, String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(metodo, uri);
        req.setServletPath(uri.replace("%6F", "o").replace("%73", "s"));
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
