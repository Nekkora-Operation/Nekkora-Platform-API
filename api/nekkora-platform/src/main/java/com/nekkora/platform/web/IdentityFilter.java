package com.nekkora.platform.web;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.nekkora.platform.identity.IdentitySessionResolver;
import com.nekkora.platform.identity.IdentitySessionResolver.IdentityPrincipal;

/**
 * Irmao do {@link SessionFilter} para a entrada geral (desenho §6.7): resolve a sessao de identidade do cookie
 * {@code __Host-nekkora_id} SOMENTE nas rotas sem organizacao, e a reconstroi do banco a cada requisicao, sem nada em
 * HttpSession. Nas rotas {@code /api/v1/o/{org}/...} nao roda: os dois cookies nunca valem na mesma rota. Sem sessao
 * valida, a requisicao segue sem principal (a rota decide: o logout, por exemplo, vira no-op).
 *
 * <p>Autenticacao do Spring Security SO nas rotas que exigem sessao de identidade ({@link #SESSION_ROUTES}, comparacao
 * exata da URI): nelas, sem sessao valida, a cadeia responde 401. Em qualquer outra rota sem organizacao a sessao de
 * identidade nao autentica nada, entao uma rota nova continua fechada ate ser classificada (levantamento, §5).
 */
public final class IdentityFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = "nekkora.identity.session";
    public static final String ATTRIBUTE_TOKEN = "nekkora.identity.session.token";
    /** Rotas autenticadas pela sessao de identidade (desenho §6.4 a §6.6; step-up, P1; criacao, P2; vinculacao, P3). A sessao restrita e recusada pelo servico (403). */
    static final Set<String> SESSION_ROUTES = Set.of(IdentityOrganizationsController.MY_ORGANIZATIONS, IdentityOrganizationsController.ORG_SESSION,
            IdentityAuthController.STEP_UP, IdentityOrganizationsController.ORGANIZATIONS, AccountLinkController.START, AccountLinkController.CONFIRM,
            IdentityAuthController.ME_SESSION);

    private final IdentitySessionResolver resolver;

    public IdentityFilter(IdentitySessionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/api/v1/") || Routes.organization(req).isPresent() || req.getRequestURI().startsWith("/api/v1/o/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String token = Cookies.read(req, Cookies.IDENTITY_SESSION);
        if (token != null) {
            resolver.session(token).ifPresent(p -> {
                req.setAttribute(ATTRIBUTE, p);
                req.setAttribute(ATTRIBUTE_TOKEN, token);
                if (SESSION_ROUTES.contains(req.getRequestURI())) {
                    // mesmo tratamento do SessionFilter: contexto so desta requisicao, limpo pela propria cadeia
                    SecurityContext ctx = SecurityContextHolder.createEmptyContext();
                    ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("identidade:" + p.sessionId(), null, List.of()));
                    SecurityContextHolder.setContext(ctx);
                }
            });
        }
        chain.doFilter(req, res);
    }

    public static IdentityPrincipal principalOrNull(HttpServletRequest req) {
        return req.getAttribute(ATTRIBUTE) instanceof IdentityPrincipal p ? p : null;
    }

    public static String token(HttpServletRequest req) {
        return (String) req.getAttribute(ATTRIBUTE_TOKEN);
    }
}
