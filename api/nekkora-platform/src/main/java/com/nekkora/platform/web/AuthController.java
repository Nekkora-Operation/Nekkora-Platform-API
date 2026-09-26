package com.nekkora.platform.web;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContextFilter;
import com.nekkora.platform.auth.AuthService;
import com.nekkora.platform.config.PlatformProperties;
import com.nekkora.platform.session.ValidSession;

/** Rotas de autenticacao da Platform (contrato 8.7, §5.3). Corpos de resposta allowlisted; tokens so em cookie. */
@RestController
public class AuthController {

    public record Login(String email, char[] password) {
    }

    public record CodeRequest(String code, String recoveryCode) {
    }

    public record PasswordChange(char[] currentPassword, char[] newPassword) {
    }

    public record RecoveryRequest(String email) {
    }

    public record RecoveryConfirmation(String token, char[] newPassword) {
    }

    public record Token(String token) {
    }

    private final AuthService auth;
    private final PreAuthCsrf csrfPre;
    private final PlatformProperties.Session cfg;

    public AuthController(AuthService auth, PreAuthCsrf csrfPre, PlatformProperties.Session cfg) {
        this.auth = auth;
        this.csrfPre = csrfPre;
        this.cfg = cfg;
    }

    @GetMapping("/api/v1/csrf")
    public Map<String, String> csrfPre(HttpServletResponse res) {
        PreAuthCsrf.Issued e = csrfPre.issue();
        Cookies.pre(res, e.preId(), PreAuthCsrf.VALIDITY);
        return Map.of("csrfToken", e.token());
    }

    @GetMapping("/api/v1/o/{org}/auth/csrf")
    public Map<String, String> sessionCsrf(HttpServletRequest req) {
        return Map.of("csrfToken", SessionFilter.principal(req).csrfToken());
    }

    @PostMapping("/api/v1/o/{org}/auth/login")
    public Map<String, String> login(@PathVariable UUID org, @RequestBody Login body, HttpServletRequest req, HttpServletResponse res) {
        try {
            AuthService.LoginResult r = auth.login(org, body.email(), body.password(), RequestContextFilter.ip(req));
            csrfPre.discard(Cookies.read(req, Cookies.PRE));
            Cookies.expire(res, Cookies.PRE, "Strict");
            Cookies.challenge(res, r.challengeToken(), cfg.challenge());
            return Map.of("status", r.status(), "csrfToken", r.csrfToken());
        } finally {
            cleanUp(body.password());
        }
    }

    @PostMapping("/api/v1/o/{org}/auth/mfa/enroll/start")
    public Map<String, String> startEnrollment(@PathVariable UUID org, HttpServletRequest req) {
        return Map.of("otpauthUri", auth.startEnrollment(org, Cookies.read(req, Cookies.CHALLENGE), RequestContextFilter.ip(req)));
    }

    @PostMapping("/api/v1/o/{org}/auth/mfa/enroll/confirm")
    public Map<String, Object> confirmEnrollment(@PathVariable UUID org, @RequestBody CodeRequest body, HttpServletRequest req, HttpServletResponse res) {
        AuthService.NewSession s = auth.confirmEnrollment(org, Cookies.read(req, Cookies.CHALLENGE), body.code(), RequestContextFilter.ip(req));
        issueSession(res, s);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("recoveryCodes", List.copyOf(s.recoveryCodes()));
        r.put("csrfToken", s.csrfToken());
        return r;
    }

    @PostMapping("/api/v1/o/{org}/auth/mfa/verify")
    public Map<String, String> verify(@PathVariable UUID org, @RequestBody CodeRequest body, HttpServletRequest req, HttpServletResponse res) {
        if ((body.code() == null) == (body.recoveryCode() == null)) {
            throw ApiException.invalid("codigo");
        }
        AuthService.NewSession s = auth.verify(org, Cookies.read(req, Cookies.CHALLENGE), body.code(), body.recoveryCode(),
                RequestContextFilter.ip(req));
        issueSession(res, s);
        return Map.of("csrfToken", s.csrfToken());
    }

    @PostMapping("/api/v1/o/{org}/auth/mfa/step-up")
    public ResponseEntity<Void> stepUp(@PathVariable UUID org, @RequestBody CodeRequest body, HttpServletRequest req) {
        if (body.code() == null) {
            throw ApiException.invalid("codigo");
        }
        auth.stepUp(org, SessionFilter.principal(req), SessionFilter.token(req), body.code(), RequestContextFilter.ip(req));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/o/{org}/auth/logout")
    public ResponseEntity<Void> logout(@PathVariable UUID org, HttpServletRequest req, HttpServletResponse res) {
        auth.logout(org, Cookies.read(req, Cookies.SESSION));
        Cookies.expire(res, Cookies.SESSION, "Lax");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/o/{org}/auth/sessions/revoke-all")
    public ResponseEntity<Void> revokeAll(@PathVariable UUID org, HttpServletRequest req, HttpServletResponse res) {
        auth.revokeAll(org, SessionFilter.principal(req), SessionFilter.token(req));
        Cookies.expire(res, Cookies.SESSION, "Lax");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/o/{org}/auth/password")
    public ResponseEntity<Void> changePassword(@PathVariable UUID org, @RequestBody PasswordChange body, HttpServletRequest req) {
        try {
            ValidSession s = SessionFilter.principal(req);
            auth.changePassword(org, s, SessionFilter.token(req), body.currentPassword(), body.newPassword(), RequestContextFilter.ip(req));
            return ResponseEntity.noContent().build();
        } finally {
            cleanUp(body.currentPassword());
            cleanUp(body.newPassword());
        }
    }

    @PostMapping("/api/v1/o/{org}/auth/password-reset/request")
    public ResponseEntity<Void> requestRecovery(@PathVariable UUID org, @RequestBody RecoveryRequest body, HttpServletRequest req) {
        auth.requestRecovery(org, body.email(), RequestContextFilter.ip(req));
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @PostMapping("/api/v1/o/{org}/auth/password-reset/confirm")
    public ResponseEntity<Void> confirmRecovery(@PathVariable UUID org, @RequestBody RecoveryConfirmation body) {
        try {
            auth.confirmRecovery(org, body.token(), body.newPassword());
            return ResponseEntity.noContent().build();
        } finally {
            cleanUp(body.newPassword());
        }
    }

    @PostMapping("/api/v1/o/{org}/auth/email-verification/confirm")
    public ResponseEntity<Void> confirmEmail(@PathVariable UUID org, @RequestBody Token body) {
        auth.confirmEmail(org, body.token());
        return ResponseEntity.noContent().build();
    }

    private void issueSession(HttpServletResponse res, AuthService.NewSession s) {
        Cookies.session(res, s.token(), s.expiresAt());
        Cookies.expire(res, Cookies.CHALLENGE, "Strict");
    }

    private static void cleanUp(char[] c) {
        if (c != null) {
            Arrays.fill(c, '\0');
        }
    }
}
