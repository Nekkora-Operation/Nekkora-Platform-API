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

    /** Login do aplicativo desktop (AGENT-CLIENT-LOGIN-001): chave propria E a entrada geral; encerrar a sessao nao tem chave. */
    @Test
    void loginDoAplicativoDesktopExigeAsDuasChaves() throws Exception {
        for (boolean entrada : new boolean[] {false, true}) {
            for (boolean desktop : new boolean[] {false, true}) {
                IdentityRoutesGate g = new IdentityRoutesGate(false, entrada, false, false, false, false, desktop);
                for (String rota : new String[] {DesktopLoginController.AUTHORIZATIONS, DesktopLoginController.AUTHORIZATION, DesktopLoginController.APPROVE,
                        DesktopLoginController.DENY, DesktopLoginController.TOKEN, DesktopLoginController.SESSION}) {
                    for (String metodo : new String[] {"POST", "GET"}) {
                        assertThat(passa(g, metodo, rota)).as("entrada=" + entrada + " desktop=" + desktop + " " + metodo + " " + rota)
                                .isEqualTo(entrada && desktop);
                    }
                }
                assertThat(passa(g, "POST", "/api/v1/de%73ktop/token")).as("caminho escapado: mesma decisao").isEqualTo(entrada && desktop);
                assertThat(passa(g, "POST", DesktopLoginController.REVOKE)).as("encerrar a sessao do aplicativo nunca depende de chave").isTrue();
                assertThat(passa(g, "POST", IdentityAuthController.LOGIN)).as("o login da Web depende so da entrada geral").isEqualTo(entrada);
            }
        }
    }

    /**
     * Navegacao do aplicativo desktop (AGENT-CLIENT-NAVEGACAO-001): rotas com ids no caminho, fechadas pela MESMA chave do
     * login desktop (e a entrada geral), tambem com o caminho escapado; encerrar a sessao continua sem chave.
     */
    @Test
    void navegacaoDoAplicativoDesktopExigeAsDuasChaves() throws Exception {
        String org = "/api/v1/desktop/organizations/11111111-2222-3333-4444-555555555555";
        String automacao = org + "/automations/66666666-7777-8888-9999-aaaaaaaaaaaa";
        for (boolean entrada : new boolean[] {false, true}) {
            for (boolean desktop : new boolean[] {false, true}) {
                IdentityRoutesGate g = new IdentityRoutesGate(false, entrada, false, false, false, false, desktop);
                for (String rota : new String[] {DesktopNavigationController.ME, DesktopNavigationController.ORGANIZATIONS, org + "/workspaces",
                        org + "/workspaces/66666666-7777-8888-9999-aaaaaaaaaaaa/automations", automacao + "/versions", automacao}) {
                    for (String metodo : new String[] {"GET", "PATCH", "POST"}) {
                        assertThat(passa(g, metodo, rota)).as("entrada=" + entrada + " desktop=" + desktop + " " + metodo + " " + rota)
                                .isEqualTo(entrada && desktop);
                    }
                }
                assertThat(passa(g, "GET", "/api/v1/de%73ktop/organizations")).as("caminho escapado: mesma decisao").isEqualTo(entrada && desktop);
                assertThat(passa(g, "PATCH", automacao.replace("desktop", "de%73ktop"))).as("renomeacao escapada: mesma decisao").isEqualTo(entrada && desktop);
                assertThat(passa(g, "POST", DesktopLoginController.REVOKE)).as("encerrar a sessao do aplicativo nunca depende de chave").isTrue();
                assertThat(passa(g, "GET", "/api/v1/me/organizations")).as("a lista da Web depende so da entrada geral").isEqualTo(entrada);
            }
        }
    }

    /**
     * Fora do CSRF do navegador so as escritas do APLICATIVO, pela forma exata: troca do codigo, encerramento e PATCH de
     * renomeacao (dois UUIDs). Qualquer outra escrita sob /api/v1/desktop/ continua no CSRF.
     */
    @Test
    void soAsEscritasDoAplicativoFicamForaDoCsrf() {
        String automacao = "/api/v1/desktop/organizations/11111111-2222-3333-4444-555555555555/automations/66666666-7777-8888-9999-aaaaaaaaaaaa";
        assertThat(Routes.isDesktopClient(new MockHttpServletRequest("PATCH", automacao))).isTrue();
        assertThat(Routes.isDesktopClient(new MockHttpServletRequest("POST", DesktopLoginController.TOKEN))).isTrue();
        assertThat(Routes.isDesktopClient(new MockHttpServletRequest("POST", DesktopLoginController.REVOKE))).isTrue();
        for (String[] fora : new String[][] {{"POST", automacao}, {"PUT", automacao}, {"DELETE", automacao}, {"PATCH", automacao + "/versions"},
                {"PATCH", automacao + "/"}, {"PATCH", automacao.replace("desktop", "de%73ktop")}, {"PATCH", "/api/v1/desktop/organizations/x/automations/y"},
                {"PATCH", automacao.replace("/api/v1/desktop/organizations", "/api/v1/o")}, {"PATCH", "/api/v1/desktop/organizations"},
                {"POST", DesktopLoginController.APPROVE}, {"POST", DesktopLoginController.AUTHORIZATIONS}, {"POST", DesktopLoginController.DENY}}) {
            assertThat(Routes.isDesktopClient(new MockHttpServletRequest(fora[0], fora[1]))).as(fora[0] + " " + fora[1]).isFalse();
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
