package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;

/**
 * GAP-01 (API-V1-INV-01): POST /api/v1/o/{org}/client-grants/{grant}/revoke (contrato 8.7 §5.3; permissao
 * external_access.manage da 003; AdminService.revokeGrant). Autenticacao, permissao, CSRF/Origin, idempotencia e isolamento
 * entre ORGANIZACOES (concessao de outra organizacao na rota propria; sessao numa rota de outra organizacao). Cada recusa
 * confere a resposta HTTP E que a concessao ficou intacta no banco.
 */
class ClientGrantRevokeRouteIT extends PlatformTestBase {

    /** Organizacao com cliente, workspace e uma concessao ATIVA (status 2) a uma conta externa. */
    private record Scene(Org org, UUID client, UUID workspace, TestUser external, UUID grant) {
    }

    private static Scene scene() throws Exception {
        Org o = newOrganization();
        UUID client = newClient(o);
        UUID ws = newWorkspace(o, client);
        TestUser ext = externalUser(o, client, ws);
        UUID grant = UUID.fromString(value("SELECT grant_id FROM client_workspace_grants WHERE organization_id = '" + o.id()
                + "' AND workspace_id = '" + ws + "'"));
        return new Scene(o, client, ws, ext, grant);
    }

    private static String value(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** status|revoked_at definido|revogador (vinculo) da concessao, lido como superusuario. */
    private static String state(UUID grant) throws Exception {
        return value("SELECT status || '|' || (revoked_at IS NOT NULL) || '|' || coalesce(revoked_by_membership_id::text, '-') "
                + "FROM client_workspace_grants WHERE grant_id = '" + grant + "'");
    }

    private static String revokedAt(UUID grant) throws Exception {
        return value("SELECT revoked_at::text FROM client_workspace_grants WHERE grant_id = '" + grant + "'");
    }

    private BrowserClient admin(Org o) {
        BrowserClient n = browser();
        firstLogin(n, o.id(), new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray()));
        return n;
    }

    private static BrowserClient.Response revoke(BrowserClient n, UUID org, UUID grant) {
        return n.post("/api/v1/o/" + org + "/client-grants/" + grant + "/revoke", null);
    }

    @Test
    void adminRevokesWithPersistedEffectAndRepeatIsIdempotent() throws Exception {
        Scene s = scene();
        assertThat(state(s.grant())).as("concessao ativa antes").isEqualTo("2|false|-");
        BrowserClient a = admin(s.org());
        assertThat(revoke(a, s.org().id(), s.grant()).status()).isEqualTo(204);
        assertThat(state(s.grant())).as("efeito: revogada (4), com instante e revogador = vinculo do ORG_ADMIN")
                .isEqualTo("4|true|" + s.org().adminMembership());
        String first = revokedAt(s.grant());
        assertThat(revoke(a, s.org().id(), s.grant()).status()).as("repeticao idempotente").isEqualTo(204);
        assertThat(revokedAt(s.grant())).as("repeticao nao altera o instante da revogacao").isEqualTo(first);
    }

    @Test
    void withoutSessionWithoutPermissionAndExternalAccountAreRefusedWithoutEffect() throws Exception {
        Scene s = scene();
        BrowserClient anonymous = browser();
        fetchPreAuthCsrf(anonymous);
        assertThat(revoke(anonymous, s.org().id(), s.grant()).status()).as("sem sessao").isEqualTo(401);

        TestUser plain = internalUser(s.org(), true, 2);
        BrowserClient p = browser();
        firstLogin(p, s.org().id(), plain);
        assertThat(revoke(p, s.org().id(), s.grant()).status()).as("conta interna sem external_access.manage").isEqualTo(403);

        BrowserClient e = browser();
        firstLogin(e, s.org().id(), s.external());
        assertThat(revoke(e, s.org().id(), s.grant()).status()).as("conta externa (a propria titular da concessao)").isEqualTo(403);

        assertThat(state(s.grant())).as("nenhuma recusa teve efeito").isEqualTo("2|false|-");
    }

    @Test
    void csrfAndOriginAreRequiredAndRefusalHasNoEffect() throws Exception {
        Scene s = scene();
        BrowserClient a = admin(s.org());
        String good = a.csrf;

        a.csrf = null;
        assertThat(revoke(a, s.org().id(), s.grant()).status()).as("sem X-CSRF-Token").isEqualTo(403);
        a.csrf = Tokens.newToken();
        assertThat(revoke(a, s.org().id(), s.grant()).status()).as("X-CSRF-Token que nao e o da sessao").isEqualTo(403);
        a.csrf = good;
        a.origin = "https://atacante.test";
        assertThat(revoke(a, s.org().id(), s.grant()).status()).as("Origin de outra origem").isEqualTo(403);
        a.origin = null;
        assertThat(revoke(a, s.org().id(), s.grant()).status()).as("sem Origin nem Referer").isEqualTo(403);

        assertThat(state(s.grant())).as("nenhuma recusa teve efeito").isEqualTo("2|false|-");
        a.origin = PlatformTestBase.ORIGIN;
        assertThat(revoke(a, s.org().id(), s.grant()).status()).as("controle: sessao, CSRF e Origin corretos").isEqualTo(204);
    }

    @Test
    void organizationsAreIsolatedInBothDirectionsAndUnknownGrantIsNotFound() throws Exception {
        Scene a = scene();
        Scene b = scene();
        BrowserClient adminA = admin(a.org());

        assertThat(revoke(adminA, a.org().id(), b.grant()).status())
                .as("ORG_ADMIN de A, rota de A, concessao da organizacao B: nao encontrada").isEqualTo(404);
        assertThat(revoke(adminA, b.org().id(), b.grant()).status())
                .as("sessao de A numa rota da organizacao B: sessao nao vale em B").isEqualTo(401);
        assertThat(state(b.grant())).as("concessao de B intacta").isEqualTo("2|false|-");

        assertThat(revoke(adminA, a.org().id(), UUID.randomUUID()).status()).as("concessao inexistente").isEqualTo(404);
        assertThat(state(a.grant())).as("concessao de A intacta (nenhuma das chamadas a atingiu)").isEqualTo("2|false|-");
    }
}
