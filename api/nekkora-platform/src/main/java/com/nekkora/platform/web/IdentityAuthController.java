package com.nekkora.platform.web;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContextFilter;
import com.nekkora.platform.config.PlatformProperties;
import com.nekkora.platform.identity.IdentityCredentialService;
import com.nekkora.platform.identity.IdentityLoginService;
import com.nekkora.platform.identity.IdentityPasswordRecoveryService;
import com.nekkora.platform.identity.IdentityStepUpService;

/**
 * Entrada geral da identidade (Missao 1, desenho §6.3), fora de {@code /api/v1/o/{org}}. Senha em pre-autenticacao;
 * cadastro e verificacao de MFA no desafio de identidade (cookie {@code __Host-nekkora_id_mfa}, SameSite=Strict); a
 * sessao de identidade vai so no cookie {@code __Host-nekkora_id} (SameSite=Lax). Corpos allowlisted; tokens nunca no
 * corpo. Login e MFA: chave {@code nekkora.entrada-geral.enabled} ({@link IdentityRoutesGate} e a mesma chave aqui),
 * separada da do cadastro. Logout: sem chave. Step-up da sessao de identidade (P1): sessao de identidade do cookie
 * {@code __Host-nekkora_id}, CSRF dela, mesma chave da entrada geral. Sem acesso direto ao banco.
 *
 * <p>Cadastro de fator em DOIS modos explicitos ({@link Routes#enrollMode}), nas mesmas rotas: desafio de login (primeiro
 * fator, cookie {@code __Host-nekkora_id_mfa}, CSRF do desafio) ou sessao de identidade (substituicao autorizada pela
 * DC-17.13, cookie {@code __Host-nekkora_id}, CSRF da sessao). Credenciais dos dois modos na mesma requisicao: 400, antes
 * do banco (CsrfFilter) e de novo aqui. {@code restricted} explicito nas respostas que abrem ou mudam a sessao e em
 * {@code GET /api/v1/me/session}.
 */
@RestController
public class IdentityAuthController {

    public static final String LOGIN = "/api/v1/auth/login";
    public static final String ENROLL_START = "/api/v1/auth/mfa/enroll/start";
    public static final String ENROLL_CONFIRM = "/api/v1/auth/mfa/enroll/confirm";
    public static final String VERIFY = "/api/v1/auth/mfa/verify";
    public static final String LOGOUT = "/api/v1/auth/logout";
    public static final String STEP_UP = "/api/v1/auth/step-up";
    public static final String ME_SESSION = "/api/v1/me/session";
    public static final String PASSWORD = "/api/v1/auth/password";
    public static final String RECOVERY_CODES = "/api/v1/auth/mfa/recovery-codes";
    public static final String PASSWORD_RESET_REQUEST = "/api/v1/auth/password-reset/request";
    public static final String PASSWORD_RESET_CONFIRM = "/api/v1/auth/password-reset/confirm";

    public record Login(String email, char[] password) {
    }

    public record CodeRequest(String code, String recoveryCode) {
    }

    /** Pedido de recuperacao de senha da identidade: so o e-mail. */
    public record RecoveryRequest(String email) {
    }

    /** Confirmacao da recuperacao: token do e-mail e senha nova (zerada depois do uso). */
    public record RecoveryConfirmation(String token, char[] newPassword) {
    }

    /** Troca de senha da identidade: as duas senhas sao zeradas depois do uso. */
    public record PasswordChange(char[] currentPassword, char[] newPassword) {
    }

    /** Step-up: so o TOTP (codigo de recuperacao nao e alternativa). */
    public record StepUpRequest(String code) {
    }

    private final IdentityLoginService logins;
    private final IdentityStepUpService stepUps;
    private final IdentityCredentialService credentials;
    private final IdentityPasswordRecoveryService recoveries;
    private final PreAuthCsrf csrfPre;
    private final PlatformProperties.Session cfg;
    private final boolean enabled;
    private final boolean recoveryEnabled;

    public IdentityAuthController(IdentityLoginService logins, IdentityStepUpService stepUps, IdentityCredentialService credentials,
                                  IdentityPasswordRecoveryService recoveries, PreAuthCsrf csrfPre, PlatformProperties.Session cfg,
                                  @Value("${nekkora.entrada-geral.enabled:false}") boolean enabled,
                                  @Value("${nekkora.recuperacao-senha.enabled:false}") boolean recoveryEnabled) {
        this.logins = logins;
        this.stepUps = stepUps;
        this.credentials = credentials;
        this.recoveries = recoveries;
        this.recoveryEnabled = recoveryEnabled;
        this.csrfPre = csrfPre;
        this.cfg = cfg;
        this.enabled = enabled;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ApiException(ApiError.NOT_FOUND, "entrada_geral_desligada", null);
        }
    }

    @PostMapping(LOGIN)
    public Map<String, String> login(@RequestBody(required = false) Login body, HttpServletRequest req, HttpServletResponse res) {
        char[] password = body == null ? null : body.password();
        try {
            requireEnabled();
            IdentityLoginService.LoginResult r = logins.login(body == null ? null : body.email(), password, RequestContextFilter.ip(req));
            csrfPre.discard(Cookies.read(req, Cookies.PRE));
            Cookies.expire(res, Cookies.PRE, "Strict");
            Cookies.identityChallenge(res, r.challengeToken(), cfg.challenge());
            return Map.of("status", r.status(), "csrfToken", r.csrfToken());
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    /** Modo do cadastro de fator; ambiguo: 400 (segunda barreira, depois do CsrfFilter). */
    private static Routes.EnrollMode enrollMode(HttpServletRequest req) {
        Routes.EnrollMode m = Routes.enrollMode(req);
        if (m == Routes.EnrollMode.AMBIGUOUS) {
            throw ApiException.invalid("modo_ambiguo");
        }
        return m;
    }

    @PostMapping(ENROLL_START)
    public Map<String, String> startEnrollment(HttpServletRequest req) {
        requireEnabled();
        String uri = switch (enrollMode(req)) {
            case SESSION -> credentials.startReplacement(IdentityFilter.principalOrNull(req), IdentityFilter.token(req), RequestContextFilter.ip(req));
            default -> logins.startEnrollment(Cookies.read(req, Cookies.IDENTITY_CHALLENGE), RequestContextFilter.ip(req));
        };
        return Map.of("otpauthUri", uri);
    }

    @PostMapping(ENROLL_CONFIRM)
    public Map<String, Object> confirmEnrollment(@RequestBody(required = false) CodeRequest body, HttpServletRequest req, HttpServletResponse res) {
        requireEnabled();
        Map<String, Object> r = new LinkedHashMap<>();
        if (enrollMode(req) == Routes.EnrollMode.SESSION) {
            // substituicao: so o TOTP do fator novo (codigo de recuperacao nao confirma fator)
            if (body == null || body.code() == null || body.recoveryCode() != null) {
                throw ApiException.invalid("codigo");
            }
            IdentityCredentialService.Replaced s = credentials.confirmReplacement(IdentityFilter.principalOrNull(req), IdentityFilter.token(req),
                    body.code(), RequestContextFilter.ip(req));
            r.put("recoveryCodes", List.copyOf(s.recoveryCodes()));
            r.put("restricted", s.restricted());
            return r;
        }
        IdentityLoginService.NewSession s = logins.confirmEnrollment(Cookies.read(req, Cookies.IDENTITY_CHALLENGE), body == null ? null : body.code(),
                RequestContextFilter.ip(req));
        issueSession(res, s);
        r.put("recoveryCodes", List.copyOf(s.recoveryCodes()));
        r.put("csrfToken", s.csrfToken());
        r.put("restricted", s.restricted());
        return r;
    }

    @PostMapping(VERIFY)
    public Map<String, Object> verify(@RequestBody(required = false) CodeRequest body, HttpServletRequest req, HttpServletResponse res) {
        requireEnabled();
        IdentityLoginService.NewSession s = logins.verify(Cookies.read(req, Cookies.IDENTITY_CHALLENGE), body == null ? null : body.code(),
                body == null ? null : body.recoveryCode(), RequestContextFilter.ip(req));
        issueSession(res, s);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("csrfToken", s.csrfToken());
        r.put("restricted", s.restricted());
        return r;
    }

    /**
     * Estado da sessao de identidade do cookie (para a interface depois de recarregar): {@code restricted} (nascida de
     * codigo de recuperacao, antes da troca do fator) e o CSRF dela. Sem sessao valida: 401 (cadeia de seguranca).
     */
    @GetMapping(ME_SESSION)
    public Map<String, Object> session(HttpServletRequest req) {
        requireEnabled();
        var p = IdentityFilter.principalOrNull(req);
        if (p == null) {
            throw ApiException.unauthenticated("sessao_identidade");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("restricted", p.restricted());
        r.put("csrfToken", p.csrfToken());
        return r;
    }

    /** Step-up da sessao de identidade (P1). Codigo ausente: 400; o resto e do servico. */
    @PostMapping(STEP_UP)
    public ResponseEntity<Void> stepUp(@RequestBody(required = false) StepUpRequest body, HttpServletRequest req) {
        requireEnabled();
        if (body == null || body.code() == null) {
            throw ApiException.invalid("codigo");
        }
        stepUps.stepUp(IdentityFilter.principalOrNull(req), IdentityFilter.token(req), body.code(), RequestContextFilter.ip(req));
        return ResponseEntity.noContent().build();
    }

    /**
     * Troca de senha da identidade: sessao comum com step-up recente, senha atual e senha nova (politica da Platform). 204;
     * corpo incompleto ou senha nova fora da politica: 400; senha atual errada, sem step-up ou sessao restrita: 403.
     */
    @PostMapping(PASSWORD)
    public ResponseEntity<Void> changePassword(@RequestBody(required = false) PasswordChange body, HttpServletRequest req) {
        char[] current = body == null ? null : body.currentPassword();
        char[] next = body == null ? null : body.newPassword();
        try {
            requireEnabled();
            if (current == null || next == null) {
                throw ApiException.invalid("senha");
            }
            credentials.changePassword(IdentityFilter.principalOrNull(req), IdentityFilter.token(req), current, next, RequestContextFilter.ip(req));
            return ResponseEntity.noContent().build();
        } finally {
            if (current != null) {
                Arrays.fill(current, '\0');
            }
            if (next != null) {
                Arrays.fill(next, '\0');
            }
        }
    }

    /** Regeneracao independente dos codigos de recuperacao: sessao comum com step-up recente. Conjunto novo uma unica vez. */
    @PostMapping(RECOVERY_CODES)
    public Map<String, Object> regenerateRecoveryCodes(HttpServletRequest req) {
        requireEnabled();
        List<String> codes = credentials.regenerateRecoveryCodes(IdentityFilter.principalOrNull(req), IdentityFilter.token(req), RequestContextFilter.ip(req));
        return Map.of("recoveryCodes", List.copyOf(codes));
    }

    /** Recuperacao de senha: chave propria E a da entrada geral; desligada, 404 (segunda barreira, depois do gate). */
    private void requireRecoveryEnabled() {
        if (!enabled || !recoveryEnabled) {
            throw new ApiException(ApiError.NOT_FOUND, "recuperacao_senha_desligada", null);
        }
    }

    /** Pedido de recuperacao de senha da identidade: sempre 202 sem corpo (e-mail malformado: 400). */
    @PostMapping(PASSWORD_RESET_REQUEST)
    public ResponseEntity<Void> requestPasswordReset(@RequestBody(required = false) RecoveryRequest body, HttpServletRequest req) {
        requireRecoveryEnabled();
        recoveries.request(body == null ? null : body.email(), RequestContextFilter.ip(req));
        return ResponseEntity.accepted().build();
    }

    /** Confirmacao da recuperacao: 204; nenhuma sessao criada. Qualquer recusa: 400. */
    @PostMapping(PASSWORD_RESET_CONFIRM)
    public ResponseEntity<Void> confirmPasswordReset(@RequestBody(required = false) RecoveryConfirmation body, HttpServletRequest req) {
        char[] next = body == null ? null : body.newPassword();
        try {
            requireRecoveryEnabled();
            if (body == null || body.token() == null || next == null) {
                throw ApiException.invalid("corpo");
            }
            recoveries.confirm(body.token(), next, RequestContextFilter.ip(req));
            return ResponseEntity.noContent().build();
        } finally {
            if (next != null) {
                Arrays.fill(next, '\0');
            }
        }
    }

    /** Sem chave e sem limitador (reduz acesso); sem sessao valida, no-op. Sempre 204 e o cookie expirado. */
    @PostMapping(LOGOUT)
    public ResponseEntity<Void> logout(HttpServletRequest req, HttpServletResponse res) {
        logins.logout(Cookies.read(req, Cookies.IDENTITY_SESSION));
        Cookies.expire(res, Cookies.IDENTITY_SESSION, "Lax");
        return ResponseEntity.noContent().build();
    }

    /** Sessao nova: cookie de identidade; o desafio usado expira. */
    private static void issueSession(HttpServletResponse res, IdentityLoginService.NewSession s) {
        Cookies.expire(res, Cookies.IDENTITY_CHALLENGE, "Strict");
        Cookies.identitySession(res, s.token(), s.expiresAt());
    }
}
