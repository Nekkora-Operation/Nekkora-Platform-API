package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.core.testsupport.BrowserClient;

/**
 * Ciclo das concessoes externas (contrato de 2026-10-27, §7; 003 e 023), pelo papel real nekkora_platform: criar
 * (PENDENTE), aprovar (ATIVA, aprovacao INITIAL), revisar (KEEP, SUSPEND, REVOKE), renovar (RENEWAL) e revogar, cada ato
 * com o estado da concessao, a linha propria da 003 e o evento em {@code access_events}. O acesso do Portal e conferido pela
 * MESMA condicao de portal_authorized_workspaces (013): ATIVA, aprovada, no prazo e dentro da revisao.
 */
class GrantLifecycleIT extends PlatformTestBase {

    private record Setup(Org org, BrowserClient admin, UUID clientMembership, UUID workspace) {
    }

    private Setup setup() throws Exception {
        Org o = newOrganization();
        UUID client = newClient(o);
        UUID ws = newWorkspace(o, client);
        TestUser ext = externalUser(o, client, null);
        UUID cm = UUID.fromString(value("SELECT client_membership_id FROM client_memberships WHERE user_id = '" + ext.id() + "'"));
        BrowserClient b = browser();
        firstLogin(b, o.id(), new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray()));
        return new Setup(o, b, cm, ws);
    }

    private void stepUp(Setup s) {
        assertThat(s.admin().post("/api/v1/o/" + s.org().id() + "/auth/mfa/step-up", "{\"code\":\"" + code(s.org().adminId()) + "\"}").status()).isEqualTo(204);
    }

    private static String future(int days) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(days).withNano(0).toString();
    }

    private static long events(UUID org, int type) throws Exception {
        return count("SELECT count(*) FROM access_events WHERE organization_id = '" + org + "' AND event_type = " + type);
    }

    /**
     * Caminho completo: criar PENDENTE (sem acesso), aprovar com step-up (ATIVA + INITIAL), KEEP com nova data (step-up),
     * SUSPEND, renovar (RENEWAL com valores anteriores e novos), REVOKE pela revisao; cada passo com o evento; a revogacao
     * direta de uma revogada e idempotente sem evento.
     */
    @Test
    void cicloCompletoComAprovacoesRevisoesEAuditoria() throws Exception {
        Setup s = setup();
        String base = "/api/v1/o/" + s.org().id() + "/client-grants";
        BrowserClient.Response c = s.admin().post(base, "{\"clientMembershipId\":\"" + s.clientMembership() + "\",\"workspaceId\":\"" + s.workspace() + "\"}");
        assertThat(c.status()).as(c.body()).isEqualTo(201);
        UUID g = UUID.fromString(c.field("grantId"));
        assertThat(c.field("status")).isEqualTo("PENDENTE");
        assertThat(portalAccess(g)).as("pendente nao da acesso").isFalse();
        assertThat(s.admin().post(base, "{\"clientMembershipId\":\"" + s.clientMembership() + "\",\"workspaceId\":\"" + s.workspace() + "\"}").status())
                .as("duplicada").isEqualTo(409);

        String aprovar = "{\"nextReviewAt\":\"" + future(30) + "\",\"justification\":\"contrato vigente\"}";
        assertThat(s.admin().post(base + "/" + g + "/approve", aprovar).status()).as("sem step-up").isEqualTo(403);
        stepUp(s);
        BrowserClient.Response ap = s.admin().post(base + "/" + g + "/approve", aprovar);
        assertThat(ap.status()).as(ap.body()).isEqualTo(200);
        assertThat(ap.field("status")).isEqualTo("ATIVA");
        assertThat(portalAccess(g)).isTrue();
        assertThat(count("SELECT count(*) FROM client_workspace_grant_approvals WHERE grant_id = '" + g + "' AND approval_type = 1")).isEqualTo(1);
        assertThat(s.admin().post(base + "/" + g + "/approve", aprovar).status()).as("aprovar de novo").isEqualTo(409);

        assertThat(s.admin().post(base + "/" + g + "/review", "{\"decision\":\"KEEP\"}").status()).as("KEEP sem data").isEqualTo(400);
        assertThat(s.admin().post(base + "/" + g + "/review", "{\"decision\":\"KEEP\",\"nextReviewAt\":\"" + future(60) + "\"}").status()).isEqualTo(200);
        assertThat(s.admin().post(base + "/" + g + "/review", "{\"decision\":\"SUSPEND\",\"notes\":\"auditoria\"}").field("status")).isEqualTo("SUSPENSA");
        assertThat(portalAccess(g)).as("suspensa nao da acesso").isFalse();
        assertThat(s.admin().post(base + "/" + g + "/renew", "{\"nextReviewAt\":\"" + future(30) + "\"}").status()).as("renovar sem justificativa").isEqualTo(400);
        BrowserClient.Response rn = s.admin().post(base + "/" + g + "/renew", "{\"nextReviewAt\":\"" + future(45) + "\",\"justification\":\"regularizado\"}");
        assertThat(rn.field("status")).isEqualTo("ATIVA");
        assertThat(value("SELECT (previous_next_review_at IS NOT NULL) || '|' || (new_next_review_at > previous_next_review_at) FROM client_workspace_grant_approvals"
                + " WHERE grant_id = '" + g + "' AND approval_type = 2")).isEqualTo("true|false");
        assertThat(s.admin().post(base + "/" + g + "/review", "{\"decision\":\"REVOKE\"}").field("status")).isEqualTo("REVOGADA");
        assertThat(portalAccess(g)).isFalse();
        assertThat(s.admin().post(base + "/" + g + "/renew", "{\"nextReviewAt\":\"" + future(30) + "\",\"justification\":\"x\"}").status())
                .as("revogada nao renova").isEqualTo(409);
        assertThat(s.admin().post(base + "/" + g + "/revoke", null).status()).as("revogacao direta idempotente").isEqualTo(204);

        assertThat(count("SELECT count(*) FROM client_workspace_grant_reviews WHERE grant_id = '" + g + "'")).isEqualTo(3);
        assertThat(events(s.org().id(), 9)).isEqualTo(1);
        assertThat(events(s.org().id(), 10)).isEqualTo(1);
        assertThat(events(s.org().id(), 11)).isEqualTo(1);
        assertThat(events(s.org().id(), 12)).isEqualTo(3);
        assertThat(events(s.org().id(), 13)).as("a revogacao direta de uma ja revogada nao grava").isZero();
        assertThat(value("SELECT string_agg(decision::text, ',' ORDER BY created_at) FROM access_events WHERE organization_id = '" + s.org().id()
                + "' AND event_type = 12")).isEqualTo("1,2,3");
    }

    /**
     * Revogacao direta (rota da GAP-01) passa a travar a organizacao e a auditar: evento 13 uma vez. Sem
     * external_access.manage: 403 e nada muda. Vinculo de cliente de outra organizacao ou workspace de outro cliente: 404.
     */
    @Test
    void revogacaoDiretaAuditaEPermissaoEEscopoSaoConferidos() throws Exception {
        Setup s = setup();
        String base = "/api/v1/o/" + s.org().id() + "/client-grants";
        UUID g = UUID.fromString(s.admin().post(base, "{\"clientMembershipId\":\"" + s.clientMembership() + "\",\"workspaceId\":\"" + s.workspace() + "\"}")
                .field("grantId"));
        TestUser comum = internalUser(s.org(), true, 2);
        BrowserClient c = browser();
        firstLogin(c, s.org().id(), comum);
        assertThat(c.post(base + "/" + g + "/revoke", null).status()).isEqualTo(403);
        assertThat(value("SELECT status FROM client_workspace_grants WHERE grant_id = '" + g + "'")).isEqualTo("1");
        assertThat(s.admin().post(base + "/" + g + "/revoke", null).status()).isEqualTo(204);
        assertThat(value("SELECT status FROM client_workspace_grants WHERE grant_id = '" + g + "'")).isEqualTo("4");
        assertThat(events(s.org().id(), 13)).isEqualTo(1);

        UUID outroWs = newWorkspace(s.org(), newClient(s.org()));
        assertThat(s.admin().post(base, "{\"clientMembershipId\":\"" + s.clientMembership() + "\",\"workspaceId\":\"" + outroWs + "\"}").status())
                .as("workspace de outro cliente").isEqualTo(404);
        assertThat(s.admin().post(base, "{\"clientMembershipId\":\"" + UUID.randomUUID() + "\",\"workspaceId\":\"" + s.workspace() + "\"}").status())
                .isEqualTo(404);
    }

    /** Garantia do banco: aprovacao gravada pela Platform com aprovador sem external_access.manage -> 42501. */
    @Test
    void bancoRecusaAprovacaoPorQuemNaoTemAPermissao() throws Exception {
        Setup s = setup();
        UUID g = UUID.fromString(s.admin().post("/api/v1/o/" + s.org().id() + "/client-grants",
                "{\"clientMembershipId\":\"" + s.clientMembership() + "\",\"workspaceId\":\"" + s.workspace() + "\"}").field("grantId"));
        TestUser comum = internalUser(s.org(), true, 2);
        String m = value("SELECT membership_id FROM organization_memberships WHERE user_id = '" + comum.id() + "'");
        assertThat(AccessInvitationsIT.platformSql(s.org().id(), "INSERT INTO client_workspace_grant_approvals (organization_id, grant_id, approval_type,"
                + " approved_by_membership_id, new_starts_at) VALUES ('" + s.org().id() + "', '" + g + "', 1, '" + m + "', now())")).isEqualTo("42501");
        assertThat(AccessInvitationsIT.platformSql(s.org().id(), "UPDATE client_workspace_grants SET status = 4, revoked_at = now(), revoked_by_membership_id = '"
                + m + "' WHERE grant_id = '" + g + "'")).isEqualTo("42501");
    }

    /** Condicao de acesso do Portal (013, portal_authorized_workspaces): ATIVA, aprovada, no prazo e dentro da revisao. */
    private static boolean portalAccess(UUID grant) throws Exception {
        return "t".equals(value("SELECT status = 2 AND approved_at IS NOT NULL AND starts_at <= now() AND (expires_at IS NULL OR expires_at > now())"
                + " AND next_review_at > now() FROM client_workspace_grants WHERE grant_id = '" + grant + "'"));
    }

    private static String value(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static long count(String sql) throws Exception {
        String v = value(sql);
        return v == null ? 0 : Long.parseLong(v);
    }
}
