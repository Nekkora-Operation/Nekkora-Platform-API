package com.nekkora.platform.web;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;
import com.nekkora.core.ratelimit.RedisLimiter.LimiterUnavailable;
import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ErrorResponses;
import com.nekkora.core.web.RequestContext;
import com.nekkora.platform.mfa.MfaRepository;
import com.nekkora.platform.session.ValidSession;

/**
 * CSRF (contrato 8.7, §5.1): em TODA operacao que altera estado, (1) {@code Origin} igual a origem publica
 * configurada (ou {@code Referer} da mesma origem, se {@code Origin} faltar) e (2) token sincronizador em
 * {@code X-CSRF-Token}, comparado em tempo constante com o estado DO CONTEXTO que a requisicao apresenta:
 * pre-autenticacao (Redis, pelo cookie __Host-nekkora_pre), desafio (auth_challenges.csrf_token, pelo cookie
 * __Host-nekkora_mfa) ou sessao (user_sessions.csrf_token, da sessao ja validada). Logout sem sessao valida e
 * no-op (nada a proteger). Falha -> 403 generico. Roda depois da autorizacao (rota protegida sem sessao -> 401).
 */
public final class CsrfFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(CsrfFilter.class);
    public static final String HEADER = "X-CSRF-Token";

    private final String origin;
    private final PreAuthCsrf pre;
    private final OrgTransactions tx;
    private final MfaRepository mfa;

    public CsrfFilter(String publicOrigin, PreAuthCsrf pre, OrgTransactions tx, MfaRepository mfa) {
        this.origin = URI.create(publicOrigin).normalize().toString().replaceAll("/+$", "");
        this.pre = pre;
        this.tx = tx;
        this.mfa = mfa;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !Routes.isWrite(req) || !req.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if (!originMatches(req)) {
            reject(res, "origem");
            return;
        }
        String token = req.getHeader(HEADER);
        Routes.Context ctx = Routes.csrfContext(req);
        Object principal = req.getAttribute(SessionFilter.ATTRIBUTE);
        boolean ok;
        try {
            ok = switch (ctx) {
                case PRE_AUTHENTICATION -> pre.isValid(Cookies.read(req, Cookies.PRE), token);
                case CHALLENGE -> challengeMatches(req, token);
                case LOGOUT -> principal == null || Tokens.constantTimeEquals(((ValidSession) principal).csrfToken(), token);
                case SESSION -> principal instanceof ValidSession s && Tokens.constantTimeEquals(s.csrfToken(), token);
            };
        } catch (LimiterUnavailable e) {
            LOG.warn("evento=csrf.indisponivel contexto={} correlacao={}", ctx, RequestContext.correlation());
            ErrorResponses.writeError(res, ApiError.SERVICE_UNAVAILABLE, 1L);
            return;
        }
        if (!ok) {
            reject(res, "token_" + ctx);
            return;
        }
        chain.doFilter(req, res);
    }

    private boolean challengeMatches(HttpServletRequest req, String token) {
        Optional<UUID> org = Routes.organization(req);
        String challenge = Cookies.read(req, Cookies.CHALLENGE);
        if (org.isEmpty() || !Tokens.isValidFormat(challenge) || token == null) {
            return false;
        }
        String expected = tx.inOrganization(org.get(), Mode.READ_ONLY,
                j -> mfa.byToken(j, org.get(), Tokens.sha256Hex(challenge)).map(MfaRepository.Challenge::csrfToken).orElse(null));
        return Tokens.constantTimeEquals(expected, token);
    }

    private boolean originMatches(HttpServletRequest req) {
        String o = req.getHeader("Origin");
        if (o != null) {
            return o.equals(origin);
        }
        String ref = req.getHeader("Referer");
        if (ref == null) {
            return false;
        }
        try {
            URI u = URI.create(ref);
            String refOrigin = u.getScheme() + "://" + u.getRawAuthority();
            return refOrigin.equals(origin);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void reject(HttpServletResponse res, String reason) throws IOException {
        LOG.info("evento=csrf.negado motivo={} correlacao={}", reason, RequestContext.correlation());
        ErrorResponses.writeError(res, ApiError.FORBIDDEN, null);
    }
}
