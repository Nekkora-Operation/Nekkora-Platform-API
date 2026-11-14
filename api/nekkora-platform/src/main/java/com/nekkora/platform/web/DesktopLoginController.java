package com.nekkora.platform.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContextFilter;
import com.nekkora.platform.desktop.DesktopLoginService;
import com.nekkora.platform.limits.Limits;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Login do aplicativo desktop pelo navegador (AGENT-CLIENT-LOGIN-001, contrato desktop-login/1). Dois grupos de rotas,
 * que nunca se misturam:
 * <ul>
 * <li><b>navegador</b> ({@link #AUTHORIZATIONS}, {@link #AUTHORIZATION}, {@link #APPROVE}, {@link #DENY}): cookies e CSRF
 * da Web. So {@link #APPROVE} emite codigo, e so com a sessao de identidade;</li>
 * <li><b>aplicativo</b> ({@link #TOKEN}, {@link #SESSION}, {@link #REVOKE}): sem cookie nenhum. Vale o codigo com o
 * verificador PKCE, ou o token {@code Bearer}. Um cookie de sessao apresentado aqui e ignorado.</li>
 * </ul>
 * O escopo e o do pedido aprovado ({@code identity} ou {@code identity navigation}); as rotas do segundo ficam em
 * {@link DesktopNavigationController}.
 * Chave {@code nekkora.desktop-login.enabled} (padrao desligada), que so abre com a entrada geral tambem ligada. O token
 * do aplicativo nao autentica nenhuma outra rota: os filtros de sessao so leem cookies.
 */
@RestController
public class DesktopLoginController {

    public static final String AUTHORIZATIONS = "/api/v1/desktop/authorizations";
    public static final String AUTHORIZATION = "/api/v1/desktop/authorization";
    public static final String APPROVE = "/api/v1/desktop/authorization/approve";
    public static final String DENY = "/api/v1/desktop/authorization/deny";
    public static final String TOKEN = "/api/v1/desktop/token";
    public static final String SESSION = "/api/v1/desktop/session";
    public static final String REVOKE = "/api/v1/desktop/session/revoke";

    /** Pedido de autorizacao, com os nomes do contrato da Web (camelCase) para os parametros da RFC 6749/7636. */
    public record StartRequest(String responseType, String clientId, String redirectUri, String state, String codeChallenge,
                               String codeChallengeMethod, String scope) {
    }

    private final DesktopLoginService service;
    private final Limits limits;
    private final boolean enabled;

    public DesktopLoginController(DesktopLoginService service, Limits limits,
                                  @Value("${nekkora.desktop-login.enabled:false}") boolean desktopEnabled,
                                  @Value("${nekkora.entrada-geral.enabled:false}") boolean entryEnabled) {
        this.service = service;
        this.limits = limits;
        this.enabled = desktopEnabled && entryEnabled;
    }

    private void open(HttpServletRequest req) {
        if (!enabled) {
            throw new ApiException(ApiError.NOT_FOUND, "login_desktop_desligado", null);
        }
        limits.identityRoutes(RequestContextFilter.ip(req));
    }

    // ------------------------------------------------------------------ navegador

    /** Abre o pedido: 204 e o cookie opaco do pedido. Nada do pedido volta ao navegador. */
    @PostMapping(AUTHORIZATIONS)
    public ResponseEntity<Void> start(@RequestBody(required = false) StartRequest b, HttpServletRequest req, HttpServletResponse res) {
        open(req);
        if (b == null) {
            throw ApiException.invalid("desktop_pedido");
        }
        DesktopLoginService.Started s = service.start(b.responseType(), b.clientId(), b.redirectUri(), b.state(), b.codeChallenge(),
                b.codeChallengeMethod(), b.scope());
        Cookies.desktopRequest(res, s.requestToken(), s.ttl());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** O que a pagina de confirmacao mostra: aplicativo e conta. Sessao de identidade exigida pela cadeia (401). */
    @GetMapping(AUTHORIZATION)
    public ResponseEntity<Map<String, Object>> pending(HttpServletRequest req) {
        open(req);
        DesktopLoginService.Pending p = service.pending(Cookies.read(req, Cookies.DESKTOP_REQUEST), IdentityFilter.principalOrNull(req),
                IdentityFilter.token(req));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("application", p.application());
        r.put("account", Map.of("email", p.email()));
        // o escopo PEDIDO, para a pagina dizer a pessoa o que ela esta concedendo (identity | identity navigation)
        r.put("scope", p.scope());
        return noStore(r);
    }

    @PostMapping(APPROVE)
    public ResponseEntity<Map<String, Object>> approve(HttpServletRequest req, HttpServletResponse res) {
        open(req);
        String to = service.approve(Cookies.read(req, Cookies.DESKTOP_REQUEST), IdentityFilter.principalOrNull(req), IdentityFilter.token(req));
        Cookies.expire(res, Cookies.DESKTOP_REQUEST, "Strict");
        return noStore(Map.of("redirectTo", to));
    }

    @PostMapping(DENY)
    public ResponseEntity<Map<String, Object>> deny(HttpServletRequest req, HttpServletResponse res) {
        open(req);
        String to = service.deny(Cookies.read(req, Cookies.DESKTOP_REQUEST));
        Cookies.expire(res, Cookies.DESKTOP_REQUEST, "Strict");
        return noStore(Map.of("redirectTo", to));
    }

    // ------------------------------------------------------------------ aplicativo

    /**
     * Troca do codigo (RFC 6749 4.1.3): corpo {@code application/x-www-form-urlencoded}, cada parametro UMA vez e nenhum na
     * URL. Erros no formato da RFC 6749 5.2 ({@code {"error": "invalid_grant"}}), 400.
     */
    @PostMapping(value = TOKEN, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(HttpServletRequest req) {
        open(req);
        try {
            if (req.getQueryString() != null) {
                throw new DesktopLoginService.TokenError("invalid_request");
            }
            DesktopLoginService.Issued i = service.exchange(single(req, "grant_type"), single(req, "code"), single(req, "redirect_uri"),
                    single(req, "client_id"), single(req, "code_verifier"));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("access_token", i.accessToken());
            r.put("token_type", "Bearer");
            r.put("expires_in", i.expiresIn());
            r.put("scope", i.scope());
            return noStore(r);
        } catch (DesktopLoginService.TokenError e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
                    .body(Map.of("error", e.code()));
        }
    }

    @GetMapping(SESSION)
    public ResponseEntity<Map<String, Object>> session(HttpServletRequest req) {
        open(req);
        DesktopLoginService.Current c = service.session(bearer(req));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("account", Map.of("email", c.email()));
        r.put("scope", c.scope());
        r.put("expiresIn", c.expiresIn());
        return noStore(r);
    }

    /** Encerrar nunca depende da chave nem do limitador: reduz acesso (como o logout). */
    @PostMapping(REVOKE)
    public ResponseEntity<Void> revoke(HttpServletRequest req) {
        String token = bearerOrNull(req);
        if (token != null) {
            service.revoke(token);
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    // ------------------------------------------------------------------ apoio

    /** Parametro presente uma unica vez, ou nulo. Repetido: pedido invalido (nunca "o primeiro vale"). */
    private static String single(HttpServletRequest req, String name) {
        String[] v = req.getParameterValues(name);
        if (v == null) {
            return null;
        }
        if (v.length != 1) {
            throw new DesktopLoginService.TokenError("invalid_request");
        }
        return v[0];
    }

    static String bearer(HttpServletRequest req) {
        String t = bearerOrNull(req);
        if (t == null) {
            throw ApiException.unauthenticated("desktop_sessao");
        }
        return t;
    }

    private static String bearerOrNull(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        return h != null && h.startsWith("Bearer ") && h.length() == 7 + 43 ? h.substring(7) : null;
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
    }
}
