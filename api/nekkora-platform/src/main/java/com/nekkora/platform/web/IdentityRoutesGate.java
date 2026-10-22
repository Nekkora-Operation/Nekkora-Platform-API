package com.nekkora.platform.web;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ErrorResponses;

/**
 * Chaves das rotas de identidade, SEPARADAS (revisao do bloco 2): suspender novos cadastros nao pode impedir quem ja tem
 * identidade de entrar ou sair.
 * <ul>
 * <li>{@code nekkora.cadastro.enabled} (DC-17.7): pedido, confirmacao e reenvio do cadastro;</li>
 * <li>{@code nekkora.entrada-geral.enabled}: login, cadastro do primeiro fator, verificacao de MFA, organizacoes da
 * identidade, troca por sessao de organizacao e step-up da sessao de identidade;</li>
 * <li>{@code nekkora.criacao-organizacao.enabled} (contrato P2): criacao de organizacao. Abre so com ela E a entrada
 * geral ligadas (sem entrada geral nao ha sessao de identidade);</li>
 * <li>{@code nekkora.vinculacao.enabled} (contrato P3): as duas etapas da vinculacao de conta antiga. Como a criacao, so
 * abre com a entrada geral tambem ligada. Os bloqueios da entrada local de conta vinculada NAO dependem desta chave: valem
 * para toda conta vinculada, inclusive as do cadastro;</li>
 * <li>o LOGOUT de identidade nao tem chave: encerrar sessao nunca depende de configuracao.</li>
 * </ul>
 * Desligada, a rota responde 404 {@code not_found} em qualquer metodo, ANTES da cadeia de seguranca: sem CSRF, sem
 * limitador, sem banco e sem e-mail. Os controllers recebem as mesmas chaves e recusam com 404 tambem (segunda barreira).
 * Ambas desligadas por padrao. Um desligamento completo da identidade, se vier a existir, tera nome e efeito proprios.
 */
public final class IdentityRoutesGate extends OncePerRequestFilter {

    static final Set<String> SIGNUP_ROUTES = Set.of(SignupController.SIGNUP, SignupController.CONFIRM, SignupController.RESEND);
    static final Set<String> ENTRY_ROUTES = Set.of(IdentityAuthController.LOGIN, IdentityAuthController.ENROLL_START,
            IdentityAuthController.ENROLL_CONFIRM, IdentityAuthController.VERIFY, IdentityAuthController.STEP_UP,
            IdentityOrganizationsController.MY_ORGANIZATIONS, IdentityOrganizationsController.ORG_SESSION, IdentityAuthController.ME_SESSION,
            IdentityAuthController.PASSWORD, IdentityAuthController.RECOVERY_CODES);
    static final Set<String> CREATION_ROUTES = Set.of(IdentityOrganizationsController.ORGANIZATIONS);
    static final Set<String> LINK_ROUTES = Set.of(AccountLinkController.START, AccountLinkController.CONFIRM);

    private final boolean signupEnabled;
    private final boolean entryEnabled;
    private final boolean creationEnabled;
    private final boolean linkEnabled;

    public IdentityRoutesGate(boolean signupEnabled, boolean entryEnabled, boolean creationEnabled, boolean linkEnabled) {
        this.signupEnabled = signupEnabled;
        this.entryEnabled = entryEnabled;
        this.creationEnabled = creationEnabled;
        this.linkEnabled = linkEnabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        // URI crua e caminho decodificado: um escape (%73ignup) nao contorna a chave
        return !(closed(req.getRequestURI()) || closed(req.getServletPath()));
    }

    private boolean closed(String path) {
        return (!signupEnabled && SIGNUP_ROUTES.contains(path)) || (!entryEnabled && ENTRY_ROUTES.contains(path))
                || (!(entryEnabled && creationEnabled) && CREATION_ROUTES.contains(path))
                || (!(entryEnabled && linkEnabled) && LINK_ROUTES.contains(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        ErrorResponses.writeError(res, ApiError.NOT_FOUND, null);
    }
}
