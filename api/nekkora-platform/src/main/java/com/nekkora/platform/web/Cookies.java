package com.nekkora.platform.web;

import java.time.Duration;
import java.time.OffsetDateTime;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Cookies do contrato 8.7 (§5.1). Todos com prefixo {@code __Host-}: Secure, Path=/, sem Domain (host-only), e
 * HttpOnly. O token so vai no cookie; nunca em corpo, URL ou log.
 */
public final class Cookies {

    public static final String SESSION = "__Host-nekkora_session";
    public static final String CHALLENGE = "__Host-nekkora_mfa";
    public static final String PRE = "__Host-nekkora_pre";

    private Cookies() {
    }

    public static String read(HttpServletRequest req, String name) {
        Cookie[] cs = req.getCookies();
        if (cs == null) {
            return null;
        }
        String value = null;
        for (Cookie c : cs) {
            if (name.equals(c.getName())) {
                if (value != null) {
                    return null; // cookie duplicado: ambiguo, trata como ausente
                }
                value = c.getValue();
            }
        }
        return value;
    }

    /** Sessao: SameSite=Lax; Max-Age = restante ate o prazo absoluto de 12 h; sem renovacao. */
    public static void session(HttpServletResponse res, String token, OffsetDateTime expiresAt) {
        long remaining = Math.max(0, Duration.between(OffsetDateTime.now(), expiresAt).toSeconds());
        writeCookie(res, SESSION, token, "Lax", remaining);
    }

    public static void challenge(HttpServletResponse res, String token, Duration validity) {
        writeCookie(res, CHALLENGE, token, "Strict", validity.toSeconds());
    }

    public static void pre(HttpServletResponse res, String id, Duration validity) {
        writeCookie(res, PRE, id, "Strict", validity.toSeconds());
    }

    public static void expire(HttpServletResponse res, String name, String sameSite) {
        writeCookie(res, name, "", sameSite, 0);
    }

    private static void writeCookie(HttpServletResponse res, String name, String value, String sameSite, long maxAge) {
        res.addHeader("Set-Cookie", name + "=" + value + "; Path=/; Secure; HttpOnly; SameSite=" + sameSite + "; Max-Age=" + maxAge);
    }
}
