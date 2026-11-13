package com.nekkora.platform.desktop;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.db.IdentityTransactions;
import com.nekkora.core.db.IdentityTransactions.Mode;
import com.nekkora.core.db.IdentityTransactions.SessaoDeOrigem;
import com.nekkora.core.db.IdentityTransactions.TokenSessao;
import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContext;
import com.nekkora.platform.desktop.DesktopStore.Grant;
import com.nekkora.platform.desktop.DesktopStore.Request;
import com.nekkora.platform.identity.IdentityAuthRepository;
import com.nekkora.platform.identity.IdentitySessionResolver.IdentityPrincipal;

/**
 * Login do aplicativo desktop pelo navegador (AGENT-CLIENT-LOGIN-001, contrato desktop-login/1): Authorization Code com
 * PKCE S256 e redirecionamento de loopback (RFC 6749 4.1, RFC 7636, RFC 8252 7.3, {@code iss} da RFC 9207), para UM
 * cliente publico fixo, sobre a identidade que ja existe. Nao e um servidor OAuth generico nem OIDC.
 *
 * <p>Regras que nao dependem do cliente:
 * <ul>
 * <li>o pedido fica no servidor; o navegador so carrega o cookie opaco dele. Login e MFA nao reenviam parametros;</li>
 * <li>o codigo so nasce de uma sessao de identidade plena (nao restrita), valida no instante da aprovacao;</li>
 * <li>o codigo e consumido antes de qualquer conferencia: erro de PKCE, cliente ou redirect tambem o queima;</li>
 * <li>a sessao do desktop vale 15 min, sem renovacao, e SO enquanto a sessao de identidade que a autorizou continuar
 * ativa no banco (conferido a cada uso): logout e as revogacoes da 017/020/029 a encerram sem codigo novo;</li>
 * <li>escopo unico {@code identity}: ler o proprio e-mail e encerrar a propria sessao.</li>
 * </ul>
 */
public final class DesktopLoginService {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopLoginService.class);

    public static final String CLIENT_ID = "nekkora-agent-client";
    public static final String APPLICATION = "Nekkora Agent";
    public static final String SCOPE = "identity";
    /** So o IP literal de loopback e o caminho fixo; a porta e a unica parte livre (RFC 8252 7.3). */
    private static final Pattern REDIRECT = Pattern.compile("^http://127\\.0\\.0\\.1:([1-9][0-9]{3,4})/callback$");
    private static final Pattern STATE = Pattern.compile("^[A-Za-z0-9_-]{22,128}$");
    private static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    /** RFC 7636 4.1: 43 a 128 caracteres nao reservados. */
    private static final Pattern VERIFIER = Pattern.compile("^[A-Za-z0-9._~-]{43,128}$");

    /** identity_events.event_type (037). */
    public static final short EV_AUTHORIZED = 21;
    public static final short EV_SESSION_ISSUED = 22;
    public static final short EV_SESSION_REVOKED = 23;

    public record Prazos(Duration request, Duration code, Duration session) {
    }

    public record Started(String requestToken, Duration ttl) {
    }

    public record Pending(String application, String email) {
    }

    public record Issued(String accessToken, long expiresIn) {
    }

    public record Current(String email, long expiresIn) {
    }

    /** Erro da troca, com o codigo da RFC 6749 5.2. */
    public static final class TokenError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        public TokenError(String code) {
            super(code, null, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final DesktopStore store;
    private final IdentityTransactions tx;
    private final IdentityAuthRepository auth;
    private final String issuer;
    private final Prazos prazos;

    public DesktopLoginService(DesktopStore store, IdentityTransactions tx, IdentityAuthRepository auth, String publicOrigin, Prazos prazos) {
        this.store = store;
        this.tx = tx;
        this.auth = auth;
        this.issuer = URI.create(publicOrigin).normalize().toString().replaceAll("/+$", "");
        this.prazos = prazos;
    }

    public String issuer() {
        return issuer;
    }

    // ------------------------------------------------------------------ navegador

    /** Valida o pedido inteiro e o guarda. Qualquer campo fora da regra: 400, sem redirecionar a lugar nenhum. */
    public Started start(String responseType, String clientId, String redirectUri, String state, String challenge, String method, String scope) {
        if (!"code".equals(responseType) || !CLIENT_ID.equals(clientId) || !SCOPE.equals(scope)) {
            throw ApiException.invalid("desktop_pedido");
        }
        // PKCE obrigatorio e so S256: "plain" ou ausencia nunca abrem um pedido (sem downgrade)
        if (!"S256".equals(method) || challenge == null || !CHALLENGE.matcher(challenge).matches()) {
            throw ApiException.invalid("desktop_pkce");
        }
        if (state == null || !STATE.matcher(state).matches()) {
            throw ApiException.invalid("desktop_state");
        }
        if (!loopback(redirectUri)) {
            throw ApiException.invalid("desktop_redirect");
        }
        String token = Tokens.newToken();
        store.saveRequest(token, new Request(clientId, redirectUri, state, challenge), prazos.request());
        return new Started(token, prazos.request());
    }

    static boolean loopback(String redirectUri) {
        if (redirectUri == null) {
            return false;
        }
        Matcher m = REDIRECT.matcher(redirectUri);
        if (!m.matches()) {
            return false;
        }
        int port = Integer.parseInt(m.group(1));
        return port >= 1024 && port <= 65535;
    }

    /** O que a pagina de confirmacao mostra: o aplicativo e a conta que vai autorizar. Sem pedido vivo: 404. */
    public Pending pending(String requestToken, IdentityPrincipal p, String sessionToken) {
        requireFull(p);
        store.request(requestToken).orElseThrow(() -> new ApiException(ApiError.NOT_FOUND, "desktop_sem_pedido", null));
        return new Pending(APPLICATION, email(sessionToken));
    }

    /**
     * Aprovacao pela pessoa autenticada: consome o pedido e emite o codigo, preso ao pedido, a identidade e a ESTA sessao
     * de identidade. Devolve a URL de retorno, montada so aqui, a partir do redirect ja validado.
     */
    public String approve(String requestToken, IdentityPrincipal p, String sessionToken) {
        requireFull(p);
        Request r = store.takeRequest(requestToken).orElseThrow(() -> new ApiException(ApiError.NOT_FOUND, "desktop_sem_pedido", null));
        // sessao reconferida no banco AGORA, com a identidade travada (ordem da 017), no mesmo commit do evento
        boolean ok = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_WRITE, (id, j) -> {
            if (id.referencia().isEmpty() || !p.sessionId().equals(id.referencia().get()) || !auth.lockIdentity(j, id.identityId())) {
                return Boolean.FALSE;
            }
            Optional<IdentityAuthRepository.LockedSession> s = auth.lockSession(j, p.sessionId());
            if (s.isEmpty() || !s.get().active() || s.get().restricted()) {
                return Boolean.FALSE;
            }
            auth.event(j, id.identityId(), EV_AUTHORIZED, correlation());
            return Boolean.TRUE;
        }).orElse(Boolean.FALSE);
        if (!ok) {
            throw ApiException.unauthenticated("desktop_sessao_identidade");
        }
        String code = Tokens.newToken();
        store.saveCode(code, new Grant(r, p.identityId(), p.sessionId()), prazos.code());
        LOG.info("evento=desktop.autorizado correlacao={}", RequestContext.correlation());
        return r.redirectUri() + "?code=" + code + "&state=" + enc(r.state()) + "&iss=" + enc(issuer);
    }

    /** Recusa (com ou sem sessao): consome o pedido e devolve o retorno com {@code error=access_denied}. */
    public String deny(String requestToken) {
        Request r = store.takeRequest(requestToken).orElseThrow(() -> new ApiException(ApiError.NOT_FOUND, "desktop_sem_pedido", null));
        LOG.info("evento=desktop.negado correlacao={}", RequestContext.correlation());
        return r.redirectUri() + "?error=access_denied&state=" + enc(r.state()) + "&iss=" + enc(issuer);
    }

    // ------------------------------------------------------------------ aplicativo

    /** Troca do codigo (RFC 6749 4.1.3 + RFC 7636 4.6). Nenhum cookie participa. */
    public Issued exchange(String grantType, String code, String redirectUri, String clientId, String verifier) {
        if (grantType == null || code == null || redirectUri == null || clientId == null || verifier == null) {
            throw new TokenError("invalid_request");
        }
        if (!"authorization_code".equals(grantType)) {
            throw new TokenError("unsupported_grant_type");
        }
        if (!CLIENT_ID.equals(clientId)) {
            throw new TokenError("invalid_client");
        }
        if (!Tokens.isValidFormat(code)) {
            throw new TokenError("invalid_grant");
        }
        Optional<Grant> taken = store.takeCode(code);
        if (taken.isEmpty()) {
            // codigo desconhecido, vencido ou JA TROCADO: no ultimo caso, a sessao que ele emitiu e revogada
            if (store.revokeIfReused(code)) {
                LOG.warn("evento=desktop.codigo_reusado correlacao={}", RequestContext.correlation());
            }
            throw new TokenError("invalid_grant");
        }
        Grant g = taken.get();
        // a partir daqui o codigo ja nao existe: qualquer recusa o deixa queimado
        if (!g.request().clientId().equals(clientId) || !g.request().redirectUri().equals(redirectUri)
                || !VERIFIER.matcher(verifier).matches() || !Tokens.constantTimeEquals(g.request().challenge(), s256(verifier))) {
            throw new TokenError("invalid_grant");
        }
        String token = Tokens.newToken();
        boolean ok = tx.inIdentity(new SessaoDeOrigem(g.identityId(), g.originSessionId()), Mode.READ_WRITE, (id, j) -> {
            if (!auth.lockIdentity(j, id.identityId()) || !originActive(j, g.originSessionId())) {
                return Boolean.FALSE;
            }
            auth.event(j, id.identityId(), EV_SESSION_ISSUED, correlation());
            return Boolean.TRUE;
        }).orElse(Boolean.FALSE);
        if (!ok) {
            throw new TokenError("invalid_grant");
        }
        store.saveSession(token, new DesktopStore.Session(g.identityId(), g.originSessionId(), Instant.now().getEpochSecond()), prazos.session());
        store.markUsed(code, token, prazos.request());
        LOG.info("evento=desktop.sessao_emitida correlacao={}", RequestContext.correlation());
        return new Issued(token, prazos.session().toSeconds());
    }

    /** Sessao do desktop pelo token Bearer: viva no Redis E com a sessao de identidade de origem ativa no banco. */
    public Current session(String accessToken) {
        DesktopStore.Session s = store.session(accessToken).orElseThrow(() -> ApiException.unauthenticated("desktop_sessao"));
        String email = tx.inIdentity(new SessaoDeOrigem(s.identityId(), s.originSessionId()), Mode.READ_ONLY,
                (id, j) -> originActive(j, s.originSessionId()) ? auth.email(j, id.identityId()).orElse(null) : null).orElse(null);
        long remaining = store.sessionRemainingSeconds(accessToken);
        if (email == null || remaining <= 0) {
            // origem revogada (logout, troca de senha, MFA...): a sessao do desktop acaba aqui
            store.deleteSession(accessToken);
            throw ApiException.unauthenticated("desktop_sessao");
        }
        return new Current(email, remaining);
    }

    /** Encerramento pelo aplicativo: idempotente; token desconhecido nao e erro. */
    public void revoke(String accessToken) {
        Optional<DesktopStore.Session> s = store.session(accessToken);
        if (s.isEmpty() || !store.deleteSession(accessToken)) {
            return;
        }
        tx.inIdentity(new SessaoDeOrigem(s.get().identityId(), s.get().originSessionId()), Mode.READ_WRITE, (id, j) -> {
            // o evento so e gravado se a identidade ainda existe no contexto (a RLS devolve zero linhas, senao)
            if (auth.lockIdentity(j, id.identityId())) {
                auth.event(j, id.identityId(), EV_SESSION_REVOKED, correlation());
            }
            return Boolean.TRUE;
        });
        LOG.info("evento=desktop.sessao_encerrada correlacao={}", RequestContext.correlation());
    }

    // ------------------------------------------------------------------ apoio

    /** A mesma regra do IdentityAuthRepository.lockSession (029), sem lock: ativa, no prazo, nao restrita e com o fator em dia. */
    private static boolean originActive(JdbcClient j, UUID sessionId) {
        return j.sql("""
                SELECT EXISTS (SELECT 1 FROM public.identity_sessions s
                                WHERE s.identity_session_id = ? AND s.revoked_at IS NULL AND s.expires_at > clock_timestamp()
                                  AND NOT (s.recovery_used_at IS NOT NULL AND s.recovery_cleared_at IS NULL)
                                  AND (s.mfa_verified_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM public.identity_mfa_factors f
                                        WHERE f.identity_id = s.identity_id AND f.confirmed_at IS NOT NULL)))
                """).param(sessionId).query(Boolean.class).single();
    }

    private String email(String sessionToken) {
        return tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_ONLY, (id, j) -> auth.email(j, id.identityId()).orElse(null))
                .orElseThrow(() -> ApiException.unauthenticated("desktop_sessao_identidade"));
    }

    private static void requireFull(IdentityPrincipal p) {
        if (p == null) {
            throw ApiException.unauthenticated("desktop_sessao_identidade");
        }
        if (p.restricted()) {
            // sessao nascida de codigo de recuperacao, antes da troca do fator: nao autoriza aplicativo
            throw ApiException.forbidden("desktop_sessao_restrita");
        }
    }

    static String s256(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static UUID correlation() {
        try {
            return UUID.fromString(RequestContext.correlation());
        } catch (IllegalArgumentException | NullPointerException e) {
            return UUID.randomUUID();
        }
    }
}
