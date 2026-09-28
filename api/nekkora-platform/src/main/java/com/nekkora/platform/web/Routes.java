package com.nekkora.platform.web;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

/** Classificacao das rotas da Platform (contrato 8.7, §5.3) para os filtros de sessao e CSRF. */
public final class Routes {

    private static final Pattern ORG = Pattern.compile("^/api/v1/o/([0-9a-fA-F-]{36})(/.*)$");

    public enum Context { PRE_AUTHENTICATION, CHALLENGE, SESSION, LOGOUT }

    private Routes() {
    }

    public static Optional<UUID> organization(HttpServletRequest req) {
        Matcher m = ORG.matcher(req.getRequestURI());
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(m.group(1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static String routeSuffix(HttpServletRequest req) {
        Matcher m = ORG.matcher(req.getRequestURI());
        return m.matches() ? m.group(2) : req.getRequestURI();
    }

    /** Contexto de CSRF de uma operacao que altera estado. */
    public static Context csrfContext(HttpServletRequest req) {
        String r = routeSuffix(req);
        return switch (r) {
            case "/auth/login", "/auth/password-reset/request", "/auth/password-reset/confirm", "/auth/email-verification/confirm",
                 "/auth/mfa-emergencia/aviso/confirmar", "/auth/mfa-emergencia/aviso/contestar" -> Context.PRE_AUTHENTICATION;
            case "/auth/mfa/enroll/start", "/auth/mfa/enroll/confirm", "/auth/mfa/verify" -> Context.CHALLENGE;
            case "/auth/logout" -> Context.LOGOUT;
            default -> Context.SESSION;
        };
    }

    /** Escritas que reduzem acesso: sem limitador (R7), com cota esgotada ou Redis fora (contrato §2.1 item 6 e §5.3). */
    public static boolean reducesAccess(HttpServletRequest req) {
        String r = routeSuffix(req);
        return r.equals("/auth/logout") || r.equals("/auth/sessions/revoke-all");
    }

    public static boolean isWrite(HttpServletRequest req) {
        String m = req.getMethod();
        return !("GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m));
    }
}
