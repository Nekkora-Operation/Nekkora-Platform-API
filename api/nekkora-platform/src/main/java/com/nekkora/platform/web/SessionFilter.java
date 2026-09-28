package com.nekkora.platform.web;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;
import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.ErrorResponses;
import com.nekkora.core.web.RequestContextFilter;
import com.nekkora.platform.limits.Limits;
import com.nekkora.platform.session.SessionRepository;
import com.nekkora.platform.session.ValidSession;

/**
 * Autenticacao por requisicao (§3.2 e §9.2 do relatorio da API): cookie de sessao -> transacao de escrita com o
 * contexto da organizacao DA ROTA -> sessao travada, validada com um instante unico e tocada (<= 1/min). O
 * principal e reconstruido do banco a CADA requisicao e vive so no atributo da requisicao e num SecurityContext
 * sem persistencia (nada em HttpSession). Depois da sessao validada: R7 por sessao; sem sessao valida: R7 por IP;
 * logout e revoke-all: sem R7.
 */
public final class SessionFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = "nekkora.session";
    public static final String ATTRIBUTE_TOKEN = "nekkora.session.token";
    /** Estado do limitador R7 nesta requisicao (ATIVO | INDISPONIVEL), so para log sanitizado. */
    public static final String ATTRIBUTE_LIMITER = "nekkora.limiter.r7";

    private final OrgTransactions tx;
    private final SessionRepository sessions;
    private final Limits limits;

    public SessionFilter(OrgTransactions tx, SessionRepository sessions, Limits limits) {
        this.tx = tx;
        this.sessions = sessions;
        this.limits = limits;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        Optional<UUID> org = Routes.organization(req);
        String token = Cookies.read(req, Cookies.SESSION);
        Optional<ValidSession> session = Optional.empty();
        if (org.isPresent() && Tokens.isValidFormat(token)) {
            String hash = Tokens.sha256Hex(token);
            session = tx.inOrganization(org.get(), Mode.READ_WRITE, j -> sessions.validate(j, org.get(), hash, true));
        }
        // logout e revoke-all reduzem acesso e ficam FORA da R7 (contrato §5.3: "sem limitador"): nem 429 com a cota
        // esgotada, nem 503 com o Redis fora. Continuam valendo autenticacao (revoke-all), Origin e CSRF (CsrfFilter)
        // e a camada 1 do gateway.
        if (!Routes.reducesAccess(req)) {
            try {
                String key = session.isPresent() ? "s:" + Tokens.sha256Hex(token) : "ip:" + RequestContextFilter.ip(req);
                boolean limiterOk = limits.bySessionOrIp(key);
                req.setAttribute(ATTRIBUTE_LIMITER, limiterOk ? "ATIVO" : "INDISPONIVEL");
                if (!limiterOk && session.isPresent() && Routes.isWrite(req)) {
                    ErrorResponses.writeError(res, ApiError.SERVICE_UNAVAILABLE, 1L);
                    return;
                }
            } catch (ApiException e) {
                ErrorResponses.writeError(res, e.apiError(), e.retryAfterSeconds());
                return;
            }
        }
        if (session.isPresent()) {
            req.setAttribute(ATTRIBUTE, session.get());
            req.setAttribute(ATTRIBUTE_TOKEN, token);
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(session.get().sessionId().toString(), null, List.of()));
            SecurityContextHolder.setContext(ctx);
        }
        // sem limpar aqui: o ExceptionTranslationFilter (acima na cadeia) ainda precisa do contexto para distinguir
        // anonimo (401) de negado (403). Quem cuida do ThreadLocal e a propria cadeia: na ENTRADA, o
        // SecurityContextHolderFilter troca o contexto da thread pelo do repositorio (atributo da requisicao, vazio
        // numa requisicao nova); na SAIDA, ele e o FilterChainProxy chamam clearContext() em finally (sucesso ou
        // excecao). Evidencia: SecurityContextLifecycleIT e SingleWorkerThreadIT.
        chain.doFilter(req, res);
    }

    public static ValidSession principal(HttpServletRequest req) {
        Object s = req.getAttribute(ATTRIBUTE);
        if (!(s instanceof ValidSession v)) {
            throw ApiException.unauthenticated("sessao");
        }
        return v;
    }

    public static String token(HttpServletRequest req) {
        return (String) req.getAttribute(ATTRIBUTE_TOKEN);
    }

    /** Estado do limitador R7 registrado pelo filtro (ATIVO | INDISPONIVEL), ou nulo se a rota ficou fora dele. */
    public static String limiterState(HttpServletRequest req) {
        return (String) req.getAttribute(ATTRIBUTE_LIMITER);
    }
}
