package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;

/**
 * GAP-01 (API-V1-INV-01): POST /api/v1/o/{org}/auth/email-verification/confirm, rota PUBLICA por token (contrato 8.7 §5.3;
 * sem 401 por ausencia de sessao). A API nao emite o token (lacuna GAP-04): a fixture o semeia como o produto o persiste
 * (SHA-256 em email_verification_tokens). Cada recusa confere a resposta HTTP E a ausencia de efeito persistido.
 */
class EmailVerificationRouteIT extends PlatformTestBase {

    private static Org org;
    private static Org other;

    @BeforeAll
    static void fixture() {
        org = newOrganization();
        other = newOrganization();
    }

    private static final String INVALID = "{\"error\":\"invalid_request\"}";

    /** Semeia um token para a conta; {@code expiresSql} e a expressao de expires_at (ex.: "now() + interval '24 hours'"). */
    private static String seed(TestUser u, String createdSql, String expiresSql, boolean revoked) {
        String token = Tokens.newToken();
        DB.asSuperuser("INSERT INTO email_verification_tokens (user_id, target_email, token_hash, created_at, expires_at, revoked_at) VALUES ('"
                + u.id() + "', '" + u.email() + "', '" + Tokens.sha256Hex(token) + "', " + createdSql + ", " + expiresSql + ", "
                + (revoked ? "now()" : "NULL") + ")");
        return token;
    }

    private static String seed(TestUser u) {
        return seed(u, "now()", "now() + interval '24 hours'", false);
    }

    private static String value(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static boolean verified(TestUser u) throws Exception {
        return value("SELECT email_verified_at IS NOT NULL FROM users WHERE user_id = '" + u.id() + "'").equals("t");
    }

    private static boolean consumed(String token) throws Exception {
        return value("SELECT consumed_at IS NOT NULL FROM email_verification_tokens WHERE token_hash = '" + Tokens.sha256Hex(token) + "'").equals("t");
    }

    private BrowserClient.Response confirm(BrowserClient n, UUID organization, String body) {
        return n.post("/api/v1/o/" + organization + "/auth/email-verification/confirm", body);
    }

    private BrowserClient.Response confirm(UUID organization, String token) {
        BrowserClient n = browser();
        fetchPreAuthCsrf(n);
        return confirm(n, organization, "{\"token\":\"" + token + "\"}");
    }

    @Test
    void validTokenVerifiesTheEmailWithoutSessionAndIsSingleUse() throws Exception {
        TestUser u = internalUser(org, false, 2);
        String token = seed(u);
        BrowserClient.Response ok = confirm(org.id(), token);
        assertThat(ok.status()).as("token valido, sem sessao: rota publica").isEqualTo(204);
        assertThat(verified(u)).as("efeito: e-mail verificado").isTrue();
        assertThat(consumed(token)).as("efeito: token consumido").isTrue();
        assertThat(ok.setCookies()).as("nao cria sessao").noneMatch(c -> c.startsWith("__Host-nekkora_session="));

        BrowserClient.Response again = confirm(org.id(), token);
        assertThat(again.status()).as("token reutilizado").isEqualTo(400);
        assertThat(again.body()).isEqualTo(INVALID);
    }

    @Test
    void expiredRevokedUnknownAndMalformedTokensAreRefusedWithoutEffect() throws Exception {
        TestUser expiredUser = internalUser(org, false, 2);
        String expired = seed(expiredUser, "now() - interval '25 hours'", "now() - interval '1 hour'", false);
        TestUser revokedUser = internalUser(org, false, 2);
        String revoked = seed(revokedUser, "now()", "now() + interval '24 hours'", true);

        for (String[] c : new String[][] {{expired, "token vencido"}, {revoked, "token revogado"}, {Tokens.newToken(), "token desconhecido"},
                {"x", "token malformado"}}) {
            BrowserClient.Response r = confirm(org.id(), c[0]);
            assertThat(r.status()).as(c[1]).isEqualTo(400);
            assertThat(r.body()).as(c[1]).isEqualTo(INVALID);
        }
        BrowserClient n = browser();
        fetchPreAuthCsrf(n);
        assertThat(confirm(n, org.id(), "{}").status()).as("sem token").isEqualTo(400);
        assertThat(verified(expiredUser)).as("sem efeito: vencido").isFalse();
        assertThat(verified(revokedUser)).as("sem efeito: revogado").isFalse();
        assertThat(consumed(expired)).as("vencido nao consumido").isFalse();
        assertThat(consumed(revoked)).as("revogado nao consumido").isFalse();
    }

    @Test
    void tokenOfAnotherOrganizationIsRefusedWithoutEffectAndStaysUsableInItsOwn() throws Exception {
        TestUser u = internalUser(org, false, 2);
        String token = seed(u);
        BrowserClient.Response wrongOrg = confirm(other.id(), token);
        assertThat(wrongOrg.status()).as("token da organizacao A na rota da organizacao B").isEqualTo(400);
        assertThat(wrongOrg.body()).isEqualTo(INVALID);
        assertThat(verified(u)).as("sem efeito na conta").isFalse();
        assertThat(consumed(token)).as("token intacto").isFalse();
        assertThat(confirm(org.id(), token).status()).as("o mesmo token continua valido na propria organizacao").isEqualTo(204);
        assertThat(verified(u)).isTrue();
    }

    @Test
    void csrfAndOriginAreRequiredAndRefusalHasNoEffect() throws Exception {
        TestUser u = internalUser(org, false, 2);
        String token = seed(u);
        String body = "{\"token\":\"" + token + "\"}";

        BrowserClient noCsrf = browser();
        noCsrf.csrf = null;
        assertThat(confirm(noCsrf, org.id(), body).status()).as("sem X-CSRF-Token de pre-autenticacao").isEqualTo(403);

        BrowserClient wrongCsrf = browser();
        fetchPreAuthCsrf(wrongCsrf);
        wrongCsrf.csrf = Tokens.newToken();
        assertThat(confirm(wrongCsrf, org.id(), body).status()).as("X-CSRF-Token que nao e o do cookie de pre-autenticacao").isEqualTo(403);

        BrowserClient foreign = browser();
        fetchPreAuthCsrf(foreign);
        foreign.origin = "https://atacante.test";
        assertThat(confirm(foreign, org.id(), body).status()).as("Origin de outra origem").isEqualTo(403);

        BrowserClient noOrigin = browser();
        fetchPreAuthCsrf(noOrigin);
        noOrigin.origin = null;
        assertThat(confirm(noOrigin, org.id(), body).status()).as("sem Origin nem Referer").isEqualTo(403);

        assertThat(verified(u)).as("nenhuma recusa teve efeito").isFalse();
        assertThat(consumed(token)).isFalse();
        assertThat(confirm(org.id(), token).status()).as("controle: com CSRF e Origin corretos").isEqualTo(204);
    }
}
