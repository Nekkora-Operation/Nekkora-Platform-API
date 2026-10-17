package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.core.testsupport.BrowserClient;

/**
 * DC-17.7 e chaves separadas: com a configuracao PADRAO (cadastro e entrada geral desligados), as rotas de cadastro e as
 * de login, MFA, step-up e criacao de organizacao (P2) respondem 404
 * {@code not_found}, com ou sem CSRF, em qualquer metodo e tambem com o caminho escapado; nada e criado e nenhum e-mail
 * sai. Usa o contexto padrao das demais classes.
 */
class SignupDisabledIT extends PlatformTestBase {

    private static final String NOT_FOUND = "{\"error\":\"not_found\"}";

    @Test
    void desligadoPorPadraoTodasAsRotasDeIdentidadeRespondem404SemEfeito() throws Exception {
        String e = "desligado-" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.test";
        String corpo = "{\"email\":\"" + e + "\",\"password\":\"" + PASSWORD + "\",\"displayName\":\"Nome\"}";
        BrowserClient comCsrf = browser();
        fetchPreAuthCsrf(comCsrf);
        BrowserClient semCsrf = browser();
        for (String rota : new String[] {"/api/v1/signup", "/api/v1/signup/confirm", "/api/v1/signup/confirmation/resend",
                "/api/v1/auth/login", "/api/v1/auth/mfa/enroll/start", "/api/v1/auth/mfa/enroll/confirm", "/api/v1/auth/mfa/verify",
                "/api/v1/auth/step-up", "/api/v1/organizations"}) {
            for (BrowserClient b : new BrowserClient[] {comCsrf, semCsrf}) {
                BrowserClient.Response r = b.post(rota, corpo);
                assertThat(r.status()).as(rota + (b == comCsrf ? " com CSRF" : " sem CSRF")).isEqualTo(404);
                assertThat(r.body()).isEqualTo(NOT_FOUND);
            }
            assertThat(comCsrf.get(rota).status()).as(rota + " GET").isEqualTo(404);
        }
        BrowserClient.Response escapada = comCsrf.post("/api/v1/%73ignup", corpo);
        assertThat(escapada.status()).as("caminho escapado nao contorna a chave").isIn(400, 404);
        try (Connection c = DB.connectAsSuperuser();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM identities WHERE email_normalized = lower(?)")) {
            ps.setString(1, e);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).as("nada criado").isZero();
            }
        }
        assertThat(testMail.sent).noneMatch(m -> e.equalsIgnoreCase(m.recipient()));
    }

    /**
     * O logout de identidade NAO tem chave: com tudo desligado ele continua encerrando sessoes. Sem sessao: no-op (204).
     * Com sessao (criada direto no banco, como a funcao da 017 a criaria): CSRF exigido e revogacao.
     */
    @Test
    void logoutDeIdentidadeContinuaDisponivelComTudoDesligado() throws Exception {
        BrowserClient semSessao = browser();
        assertThat(semSessao.post("/api/v1/auth/logout", null).status()).as("sem sessao: no-op").isEqualTo(204);
        UUID id = UUID.randomUUID();
        String token = com.nekkora.core.token.Tokens.newToken();
        String csrf = com.nekkora.core.token.Tokens.newToken();
        try (Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            for (String sql : new String[] {
                    "INSERT INTO identities (identity_id, email, display_name, status) VALUES ('" + id + "', 'logout-" + id.toString().substring(0, 8) + "@exemplo.test', 'P', 1)",
                    "UPDATE identities SET email_verified_at = now(), status = 2 WHERE identity_id = '" + id + "'",
                    "SET LOCAL ROLE nekkora_identity_owner",
                    "INSERT INTO identity_sessions (identity_id, session_token_hash, expires_at, mfa_verified_at, csrf_token) VALUES ('" + id + "', '"
                            + com.nekkora.core.token.Tokens.sha256Hex(token) + "', now() + interval '1 hour', now(), '" + csrf + "')"}) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.execute();
                }
            }
            c.commit();
        }
        BrowserClient b = browser();
        b.cookies().put("__Host-nekkora_id", token);
        b.csrf = "token-errado-" + "x".repeat(30);
        assertThat(b.post("/api/v1/auth/logout", null).status()).as("CSRF errado com sessao valida").isEqualTo(403);
        b.csrf = csrf;
        assertThat(b.post("/api/v1/auth/logout", null).status()).isEqualTo(204);
        try (Connection c = DB.connectAsSuperuser();
             PreparedStatement ps = c.prepareStatement("SELECT revoked_at IS NOT NULL FROM identity_sessions WHERE identity_id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getBoolean(1)).as("sessao revogada com tudo desligado").isTrue();
            }
        }
    }
}
