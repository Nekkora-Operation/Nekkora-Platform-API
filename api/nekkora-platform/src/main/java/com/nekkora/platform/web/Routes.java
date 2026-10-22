package com.nekkora.platform.web;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

/** Classificacao das rotas da Platform (contrato 8.7, §5.3) para os filtros de sessao e CSRF. */
public final class Routes {

    private static final Pattern ORG = Pattern.compile("^/api/v1/o/([0-9a-fA-F-]{36})(/.*)$");

    /** IDENTITY_*: entrada geral (Missao 1, desenho §6.7), so nas rotas sem organizacao. */
    public enum Context { PRE_AUTHENTICATION, CHALLENGE, SESSION, LOGOUT, IDENTITY_CHALLENGE, IDENTITY_SESSION, IDENTITY_LOGOUT, IDENTITY_AMBIGUOUS }

    /**
     * Modo das rotas de cadastro de fator da identidade ({@code /api/v1/auth/mfa/enroll/start} e {@code /confirm}),
     * pela credencial APRESENTADA (cookie com valor, valido ou nao): so o desafio de login -> primeiro fator; so a sessao de
     * identidade -> substituicao; as duas -> ambigua, recusada (400) antes de qualquer acesso ao banco; nenhuma -> desafio
     * (falha como antes: CSRF do desafio ausente). Um so lugar decide, para o CsrfFilter e o controller concordarem.
     */
    public enum EnrollMode { CHALLENGE, SESSION, AMBIGUOUS }

    public static final String IDENTITY_ENROLL_START = "/api/v1/auth/mfa/enroll/start";
    public static final String IDENTITY_ENROLL_CONFIRM = "/api/v1/auth/mfa/enroll/confirm";

    public static EnrollMode enrollMode(HttpServletRequest req) {
        boolean challenge = present(Cookies.read(req, Cookies.IDENTITY_CHALLENGE));
        boolean session = present(Cookies.read(req, Cookies.IDENTITY_SESSION));
        if (challenge && session) {
            return EnrollMode.AMBIGUOUS;
        }
        return session ? EnrollMode.SESSION : EnrollMode.CHALLENGE;
    }

    public static boolean isIdentityEnroll(HttpServletRequest req) {
        String r = req.getRequestURI();
        return IDENTITY_ENROLL_START.equals(r) || IDENTITY_ENROLL_CONFIRM.equals(r);
    }

    private static boolean present(String cookie) {
        return cookie != null && !cookie.isEmpty();
    }

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
            // cadastro web (Missao 1), fora de /o/{org}: routeSuffix devolve o caminho inteiro. Mapeamento explicito; as
            // demais rotas sem organizacao continuam no padrao SESSION (fecham)
            case "/api/v1/signup", "/api/v1/signup/confirm", "/api/v1/signup/confirmation/resend" -> Context.PRE_AUTHENTICATION;
            // entrada geral (Missao 1): senha em pre-autenticacao; MFA no desafio de identidade; logout de identidade
            case "/api/v1/auth/login" -> Context.PRE_AUTHENTICATION;
            // recuperacao de senha da identidade (missao 002): pre-autenticacao, como a da organizacao
            case "/api/v1/auth/password-reset/request", "/api/v1/auth/password-reset/confirm" -> Context.PRE_AUTHENTICATION;
            // cadastro de fator: desafio de login (primeiro fator) ou sessao de identidade (substituicao); as duas, ambigua
            case IDENTITY_ENROLL_START, IDENTITY_ENROLL_CONFIRM -> switch (enrollMode(req)) {
                case CHALLENGE -> Context.IDENTITY_CHALLENGE;
                case SESSION -> Context.IDENTITY_SESSION;
                case AMBIGUOUS -> Context.IDENTITY_AMBIGUOUS;
            };
            case "/api/v1/auth/mfa/verify" -> Context.IDENTITY_CHALLENGE;
            case "/api/v1/auth/logout" -> Context.IDENTITY_LOGOUT;
            // troca por sessao de organizacao (Missao 1, desenho 6.5): CSRF da sessao de identidade
            // step-up da sessao de identidade (P1) e criacao de organizacao (P2)
            // vinculacao de conta antiga (P3): as duas etapas com a sessao de identidade
            case "/api/v1/sessions/organization", "/api/v1/auth/step-up", "/api/v1/organizations",
                 "/api/v1/links/start", "/api/v1/links/confirm" -> Context.IDENTITY_SESSION;
            // credenciais da identidade (missao 002): troca de senha e regeneracao dos codigos, com a sessao de identidade
            case "/api/v1/auth/password", "/api/v1/auth/mfa/recovery-codes" -> Context.IDENTITY_SESSION;
            case "/auth/mfa/enroll/start", "/auth/mfa/enroll/confirm", "/auth/mfa/verify" -> Context.CHALLENGE;
            case "/auth/logout" -> Context.LOGOUT;
            default -> Context.SESSION;
        };
    }

    /** Escritas que reduzem acesso: sem limitador (R7), com cota esgotada ou Redis fora (contrato §2.1 item 6 e §5.3). */
    public static boolean reducesAccess(HttpServletRequest req) {
        String r = routeSuffix(req);
        return r.equals("/auth/logout") || r.equals("/auth/sessions/revoke-all") || r.equals("/api/v1/auth/logout");
    }

    public static boolean isWrite(HttpServletRequest req) {
        String m = req.getMethod();
        return !("GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m));
    }
}
