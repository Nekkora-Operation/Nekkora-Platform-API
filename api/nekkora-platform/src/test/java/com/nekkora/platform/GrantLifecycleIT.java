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
 * Ciclo das concessoes externas (contrato de 2026-10-27, §7; 003, 023 e 024), pelo papel real nekkora_platform: criar
 * (PENDENTE, com os valores pedidos), aprovar (ATIVA, aprovacao INITIAL), revisar (KEEP, SUSPEND, REVOKE), pedir e aprovar a
 * renovacao (RENEWAL) e revogar, cada ato com o estado da concessao, a linha propria da 003 e o evento em
 * {@code access_events}. O acesso do Portal e conferido pela MESMA condicao de portal_authorized_workspaces (013): ATIVA,
 * aprovada, no prazo e dentro da revisao. Segunda pessoa (D-GRANT-1, 024): quem cria e o solicitante; a aprovacao INITIAL e
 * de OUTRO ORG_ADMIN, no servico e no banco. Regra de renovacao e KEEP (D-GRANT-1a, 024): aprovacao vinculada aos valores
 * pedidos; extensao so por pedido + aprovacao de outra pessoa; KEEP com uma pessoa so sem estender.
 */
class GrantLifecycleIT extends PlatformTestBase {

    private record Setup(Org org, BrowserClient admin, UUID clientMembership, UUID workspace, TestUser approverUser, BrowserClient approver) {
    }

    private Setup setup() throws Exception {
        Org o = newOrganization();
        UUID client = newClient(o);
        UUID ws = newWorkspace(o, client);
        TestUser ext = externalUser(o, client, null);
        UUID cm = UUID.fromString(value("SELECT client_membership_id FROM client_memberships WHERE user_id = '" + ext.id() + "'"));
        BrowserClient b = browser();
        firstLogin(b, o.id(), new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray()));
        // segundo ORG_ADMIN (fixture declarada, como superusuario): o aprovador diferente do solicitante
        TestUser second = internalUser(o, true, 2);
        DB.asSuperuser("INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id)"
                + " SELECT '" + o.id() + "', m.membership_id, r.role_id, 1, 1, '" + o.adminMembership() + "' FROM organization_memberships m, roles r"
                + " WHERE m.user_id = '" + second.id() + "' AND r.code = 'ORG_ADMIN'");
        BrowserClient a = browser();
        firstLogin(a, o.id(), second);
        return new Setup(o, b, cm, ws, second, a);
    }

    private void stepUp(Setup s) {
        assertThat(s.admin().post("/api/v1/o/" + s.org().id() + "/auth/mfa/step-up", "{\"code\":\"" + code(s.org().adminId()) + "\"}").status()).isEqualTo(204);
    }

    private void approverStepUp(Setup s) {
        assertThat(s.approver().post("/api/v1/o/" + s.org().id() + "/auth/mfa/step-up", "{\"code\":\"" + code(s.approverUser().id()) + "\"}").status())
                .isEqualTo(204);
    }

    /** Corpo da criacao com a proxima revisao pedida (obrigatoria desde a 024): 30 dias a partir de agora, sem prazo. */
    private static final String REVISAO_PEDIDA = future(30);

    private static String criar(UUID clientMembership, UUID workspace) {
        return "{\"clientMembershipId\":\"" + clientMembership + "\",\"workspaceId\":\"" + workspace + "\",\"nextReviewAt\":\"" + REVISAO_PEDIDA + "\"}";
    }

    private static String membershipOf(UUID user) throws Exception {
        return value("SELECT membership_id FROM organization_memberships WHERE user_id = '" + user + "'");
    }

    private static String future(int days) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(days).withNano(0).toString();
    }

    private static long events(UUID org, int type) throws Exception {
        return count("SELECT count(*) FROM access_events WHERE organization_id = '" + org + "' AND event_type = " + type);
    }

    /**
     * Caminho completo: criar PENDENTE com os valores pedidos (sem acesso, solicitante gravado; sem proxima revisao: 400), o
     * solicitante NAO aprova (403, nada muda), o segundo ORG_ADMIN aprova com step-up e os valores conferidos (diferentes: 409;
     * iguais: ATIVA + INITIAL com o solicitante), KEEP sem data e com data mais cedo (uma pessoa), KEEP com data mais tarde
     * (409, nada muda), SUSPEND, pedido de renovacao (202, acesso inalterado, evento 14), o solicitante nao aprova (403),
     * aprovacao com valores diferentes (409), aprovacao pelo outro (RENEWAL com o solicitante, valores anteriores e novos;
     * ATIVA), REVOKE pela revisao; cada passo com o evento; a revogacao direta de uma revogada e idempotente sem evento.
     */
    @Test
    void cicloCompletoComAprovacoesRevisoesEAuditoria() throws Exception {
        Setup s = setup();
        String base = "/api/v1/o/" + s.org().id() + "/client-grants";
        assertThat(s.admin().post(base, "{\"clientMembershipId\":\"" + s.clientMembership() + "\",\"workspaceId\":\"" + s.workspace() + "\"}").status())
                .as("sem proxima revisao pedida").isEqualTo(400);
        BrowserClient.Response c = s.admin().post(base, criar(s.clientMembership(), s.workspace()));
        assertThat(c.status()).as(c.body()).isEqualTo(201);
        UUID g = UUID.fromString(c.field("grantId"));
        assertThat(c.field("status")).isEqualTo("PENDENTE");
        assertThat(value("SELECT requested_by_membership_id FROM client_workspace_grants WHERE grant_id = '" + g + "'"))
                .isEqualTo(s.org().adminMembership().toString());
        assertThat(portalAccess(g)).as("pendente nao da acesso").isFalse();
        assertThat(s.admin().post(base, criar(s.clientMembership(), s.workspace())).status()).as("duplicada").isEqualTo(409);

        String aprovar = "{\"nextReviewAt\":\"" + REVISAO_PEDIDA + "\",\"justification\":\"contrato vigente\"}";
        assertThat(s.approver().post(base + "/" + g + "/approve", aprovar).status()).as("sem step-up").isEqualTo(403);
        stepUp(s);
        assertThat(s.admin().post(base + "/" + g + "/approve", aprovar).status()).as("o solicitante nao aprova").isEqualTo(403);
        assertThat(value("SELECT status FROM client_workspace_grants WHERE grant_id = '" + g + "'")).isEqualTo("1");
        assertThat(count("SELECT count(*) FROM client_workspace_grant_approvals WHERE grant_id = '" + g + "'")).isZero();
        assertThat(events(s.org().id(), 10)).isZero();
        approverStepUp(s);
        BrowserClient.Response divergente = s.approver().post(base + "/" + g + "/approve", "{\"nextReviewAt\":\"" + future(90) + "\"}");
        assertThat(divergente.status()).as("valores diferentes dos pedidos").isEqualTo(409);
        assertThat(s.approver().post(base + "/" + g + "/approve", "{\"nextReviewAt\":\"" + REVISAO_PEDIDA + "\",\"expiresAt\":\"" + future(60) + "\"}")
                .status()).as("prazo diferente do pedido (sem prazo)").isEqualTo(409);
        assertThat(value("SELECT status FROM client_workspace_grants WHERE grant_id = '" + g + "'")).isEqualTo("1");
        BrowserClient.Response ap = s.approver().post(base + "/" + g + "/approve", aprovar);
        assertThat(ap.status()).as(ap.body()).isEqualTo(200);
        assertThat(ap.field("status")).isEqualTo("ATIVA");
        assertThat(portalAccess(g)).isTrue();
        assertThat(value("SELECT approved_by_membership_id || '|' || requested_by_membership_id FROM client_workspace_grant_approvals WHERE grant_id = '"
                + g + "' AND approval_type = 1")).isEqualTo(membershipOf(s.approverUser().id()) + "|" + s.org().adminMembership());
        assertThat(s.approver().post(base + "/" + g + "/approve", aprovar).status()).as("aprovar de novo").isEqualTo(409);

        // KEEP: uma pessoa so quando nao estende
        assertThat(s.admin().post(base + "/" + g + "/review", "{\"decision\":\"KEEP\"}").status()).as("KEEP sem data: so registra").isEqualTo(200);
        String antes = value("SELECT next_review_at FROM client_workspace_grants WHERE grant_id = '" + g + "'");
        BrowserClient.Response estende = s.admin().post(base + "/" + g + "/review", "{\"decision\":\"KEEP\",\"nextReviewAt\":\"" + future(60) + "\"}");
        assertThat(estende.status()).as("KEEP com data mais tarde estenderia").isEqualTo(409);
        assertThat(value("SELECT next_review_at FROM client_workspace_grants WHERE grant_id = '" + g + "'")).isEqualTo(antes);
        String cedo = future(20);
        assertThat(s.admin().post(base + "/" + g + "/review", "{\"decision\":\"KEEP\",\"nextReviewAt\":\"" + cedo + "\"}").status())
                .as("KEEP com data mais cedo: reducao, uma pessoa").isEqualTo(200);
        assertThat(value("SELECT next_review_at < '" + antes + "'::timestamptz FROM client_workspace_grants WHERE grant_id = '" + g + "'")).isEqualTo("t");
        assertThat(s.admin().post(base + "/" + g + "/review", "{\"decision\":\"SUSPEND\",\"notes\":\"auditoria\"}").field("status")).isEqualTo("SUSPENSA");
        assertThat(portalAccess(g)).as("suspensa nao da acesso").isFalse();

        // renovacao: pedido de uma pessoa, aprovacao de outra com exatamente os valores pedidos
        assertThat(s.admin().post(base + "/" + g + "/renew", "{\"nextReviewAt\":\"" + future(30) + "\"}").status()).as("pedir sem justificativa").isEqualTo(400);
        assertThat(s.approver().post(base + "/" + g + "/renew/approve", "{\"nextReviewAt\":\"" + future(45) + "\"}").status())
                .as("aprovar sem pedido").isEqualTo(409);
        String renovada = future(45);
        BrowserClient.Response pedido = s.admin().post(base + "/" + g + "/renew", "{\"nextReviewAt\":\"" + renovada + "\",\"justification\":\"regularizado\"}");
        assertThat(pedido.status()).as(pedido.body()).isEqualTo(202);
        assertThat(pedido.field("status")).as("o pedido nao muda o acesso").isEqualTo("SUSPENSA");
        assertThat(pedido.body()).contains("\"requestedByMembershipId\":\"" + s.org().adminMembership() + "\"");
        assertThat(portalAccess(g)).isFalse();
        assertThat(events(s.org().id(), 14)).isEqualTo(1);
        assertThat(s.admin().post(base + "/" + g + "/renew/approve", "{\"nextReviewAt\":\"" + renovada + "\"}").status())
                .as("o solicitante nao aprova a renovacao").isEqualTo(403);
        assertThat(s.approver().post(base + "/" + g + "/renew/approve", "{\"nextReviewAt\":\"" + future(46) + "\"}").status())
                .as("valores diferentes do pedido").isEqualTo(409);
        assertThat(count("SELECT count(*) FROM client_workspace_grant_approvals WHERE grant_id = '" + g + "' AND approval_type = 2")).isZero();
        BrowserClient.Response rn = s.approver().post(base + "/" + g + "/renew/approve", "{\"nextReviewAt\":\"" + renovada + "\"}");
        assertThat(rn.status()).as(rn.body()).isEqualTo(200);
        assertThat(rn.field("status")).isEqualTo("ATIVA");
        assertThat(rn.body()).contains("\"renewal\":null");
        assertThat(portalAccess(g)).isTrue();
        assertThat(value("SELECT (previous_next_review_at IS NOT NULL) || '|' || (new_next_review_at > previous_next_review_at) || '|' || requested_by_membership_id"
                + " || '|' || approved_by_membership_id FROM client_workspace_grant_approvals WHERE grant_id = '" + g + "' AND approval_type = 2"))
                .isEqualTo("true|true|" + s.org().adminMembership() + "|" + membershipOf(s.approverUser().id()));
        assertThat(value("SELECT renewal_requested_by_membership_id IS NULL FROM client_workspace_grants WHERE grant_id = '" + g + "'"))
                .as("pedido consumido").isEqualTo("t");
        assertThat(s.approver().post(base + "/" + g + "/renew/approve", "{\"nextReviewAt\":\"" + renovada + "\"}").status())
                .as("o pedido nao serve duas vezes").isEqualTo(409);

        assertThat(s.admin().post(base + "/" + g + "/review", "{\"decision\":\"REVOKE\"}").field("status")).isEqualTo("REVOGADA");
        assertThat(portalAccess(g)).isFalse();
        assertThat(s.admin().post(base + "/" + g + "/renew", "{\"nextReviewAt\":\"" + future(30) + "\",\"justification\":\"x\"}").status())
                .as("revogada nao renova").isEqualTo(409);
        assertThat(s.admin().post(base + "/" + g + "/revoke", null).status()).as("revogacao direta idempotente").isEqualTo(204);

        assertThat(count("SELECT count(*) FROM client_workspace_grant_reviews WHERE grant_id = '" + g + "'")).isEqualTo(4);
        assertThat(events(s.org().id(), 9)).isEqualTo(1);
        assertThat(events(s.org().id(), 10)).isEqualTo(1);
        assertThat(events(s.org().id(), 11)).isEqualTo(1);
        assertThat(events(s.org().id(), 12)).isEqualTo(4);
        assertThat(events(s.org().id(), 13)).as("a revogacao direta de uma ja revogada nao grava").isZero();
        assertThat(events(s.org().id(), 14)).isEqualTo(1);
        assertThat(value("SELECT string_agg(decision::text, ',' ORDER BY created_at) FROM access_events WHERE organization_id = '" + s.org().id()
                + "' AND event_type = 12")).isEqualTo("1,1,2,3");
    }

    /**
     * Pedido de renovacao descartado por mudanca de validade (D-GRANT-1a): pedido feito, KEEP com data mais cedo (reducao) ->
     * o pedido cai e a aprovacao posterior e recusada (409 sem_renovacao_pendente; nada muda). Pedido novo substitui o anterior:
     * a aprovacao vale so com os valores do pedido vigente.
     */
    @Test
    void pedidoDeRenovacaoCaiComMudancaDeValidadeESubstituicao() throws Exception {
        Setup s = setup();
        String base = "/api/v1/o/" + s.org().id() + "/client-grants";
        UUID g = ativa(s, base);
        String p1 = future(50);
        assertThat(s.admin().post(base + "/" + g + "/renew", "{\"nextReviewAt\":\"" + p1 + "\",\"justification\":\"pedido 1\"}").status()).isEqualTo(202);
        stepUp(s);
        assertThat(s.admin().post(base + "/" + g + "/review", "{\"decision\":\"KEEP\",\"nextReviewAt\":\"" + future(10) + "\"}").status()).isEqualTo(200);
        assertThat(value("SELECT renewal_requested_by_membership_id IS NULL FROM client_workspace_grants WHERE grant_id = '" + g + "'"))
                .as("a reducao descarta o pedido").isEqualTo("t");
        BrowserClient.Response r = s.approver().post(base + "/" + g + "/renew/approve", "{\"nextReviewAt\":\"" + p1 + "\"}");
        assertThat(r.status()).isEqualTo(409);

        String p2 = future(40);
        String p3 = future(35);
        assertThat(s.admin().post(base + "/" + g + "/renew", "{\"nextReviewAt\":\"" + p2 + "\",\"justification\":\"pedido 2\"}").status()).isEqualTo(202);
        assertThat(s.admin().post(base + "/" + g + "/renew", "{\"nextReviewAt\":\"" + p3 + "\",\"expiresAt\":\"" + future(200)
                + "\",\"justification\":\"pedido 3\"}").status()).isEqualTo(202);
        assertThat(s.approver().post(base + "/" + g + "/renew/approve", "{\"nextReviewAt\":\"" + p2 + "\"}").status()).as("pedido substituido").isEqualTo(409);
        assertThat(s.approver().post(base + "/" + g + "/renew/approve", "{\"nextReviewAt\":\"" + p3 + "\"}").status())
                .as("sem o prazo pedido").isEqualTo(409);
        assertThat(count("SELECT count(*) FROM client_workspace_grant_approvals WHERE grant_id = '" + g + "' AND approval_type = 2")).isZero();
        assertThat(events(s.org().id(), 14)).isEqualTo(3);
    }

    /**
     * Garantias do banco da regra de renovacao (024), pelo papel nekkora_platform (cada caso em transacao propria): estender a
     * proxima revisao ou o prazo, remover o prazo, antecipar o inicio ou reativar uma SUSPENSA sem pedido -> 42501; aprovacao
     * RENEWAL pelo proprio solicitante ou com valores diferentes -> 42501; valores pedidos de uma PENDENTE imutaveis -> 42501;
     * pedido que muda a validade no mesmo ato -> 42501. Controle positivo: reducao da proxima revisao passa.
     */
    @Test
    void bancoExigePedidoEAprovacaoDeOutraPessoaParaEstender() throws Exception {
        Setup s = setup();
        UUID org = s.org().id();
        String base = "/api/v1/o/" + org + "/client-grants";
        UUID g = ativa(s, base);
        String solicitante = s.org().adminMembership().toString();
        String aprovador = membershipOf(s.approverUser().id());
        String alvo = " WHERE grant_id = '" + g + "'";
        assertThat(AccessInvitationsIT.platformSql(org, "UPDATE client_workspace_grants SET next_review_at = next_review_at + interval '1 day'" + alvo))
                .as("estender a revisao sem pedido").isEqualTo("42501");
        assertThat(AccessInvitationsIT.platformSql(org, "UPDATE client_workspace_grants SET expires_at = now() + interval '400 days'" + alvo))
                .as("prazo: sem prazo -> com prazo e reducao; passa").isEqualTo("OK");
        assertThat(AccessInvitationsIT.platformSql(org, "UPDATE client_workspace_grants SET expires_at = NULL" + alvo)).as("remover o prazo").isEqualTo("42501");
        assertThat(AccessInvitationsIT.platformSql(org, "UPDATE client_workspace_grants SET expires_at = expires_at + interval '1 day'" + alvo))
                .as("estender o prazo").isEqualTo("42501");
        assertThat(AccessInvitationsIT.platformSql(org, "UPDATE client_workspace_grants SET starts_at = starts_at - interval '1 day'" + alvo))
                .as("antecipar o inicio").isEqualTo("42501");
        assertThat(AccessInvitationsIT.platformSql(org, "UPDATE client_workspace_grants SET approved_by_membership_id = '" + aprovador
                + "', approved_at = clock_timestamp(), next_review_at = next_review_at + interval '1 day'" + alvo)).as("renovar sem pedido").isEqualTo("42501");
        assertThat(AccessInvitationsIT.platformSql(org, "UPDATE client_workspace_grants SET next_review_at = next_review_at - interval '1 day'" + alvo))
                .as("controle: reducao passa").isEqualTo("OK");
        // pedido gravado pela Platform; aprovacao RENEWAL pelo solicitante ou com outros valores: recusadas
        assertThat(AccessInvitationsIT.platformSql(org, "UPDATE client_workspace_grants SET renewal_requested_by_membership_id = '" + solicitante
                + "', renewal_requested_at = now(), renewal_next_review_at = now() + interval '60 days', renewal_justification = 'x',"
                + " next_review_at = next_review_at - interval '1 hour'" + alvo)).as("pedido que muda a validade no mesmo ato").isEqualTo("42501");
        assertThat(AccessInvitationsIT.platformSql(org, "UPDATE client_workspace_grants SET renewal_requested_by_membership_id = '" + solicitante
                + "', renewal_requested_at = now(), renewal_next_review_at = now() + interval '60 days', renewal_justification = 'x'" + alvo))
                .as("pedido").isEqualTo("OK");
        String renovacao = "INSERT INTO client_workspace_grant_approvals (organization_id, grant_id, approval_type, approved_by_membership_id,"
                + " requested_by_membership_id, previous_starts_at, new_starts_at, previous_expires_at, new_expires_at, previous_next_review_at, new_next_review_at)"
                + " SELECT organization_id, grant_id, 2, '%s', renewal_requested_by_membership_id, starts_at, starts_at, expires_at, renewal_expires_at,"
                + " next_review_at, %s FROM client_workspace_grants" + alvo;
        assertThat(AccessInvitationsIT.platformSql(org, String.format(renovacao, solicitante, "renewal_next_review_at")))
                .as("o solicitante aprova a propria renovacao").isEqualTo("42501");
        assertThat(AccessInvitationsIT.platformSql(org, String.format(renovacao, aprovador, "renewal_next_review_at + interval '1 day'")))
                .as("aprovacao com outro valor").isEqualTo("42501");
        assertThat(AccessInvitationsIT.platformSql(org, String.format(renovacao, aprovador, "renewal_next_review_at")
                + "; UPDATE client_workspace_grants SET next_review_at = next_review_at + interval '90 days', expires_at = renewal_expires_at, approved_by_membership_id = '" + aprovador
                + "', approved_at = (SELECT max(approved_at) FROM client_workspace_grant_approvals" + alvo + ")" + alvo))
                .as("aplicar outro valor que o aprovado").isEqualTo("42501");
        assertThat(AccessInvitationsIT.platformSql(org, String.format(renovacao, aprovador, "renewal_next_review_at")
                + "; UPDATE client_workspace_grants SET next_review_at = renewal_next_review_at, expires_at = renewal_expires_at, approved_by_membership_id = '" + aprovador
                + "', approved_at = (SELECT max(approved_at) FROM client_workspace_grant_approvals" + alvo + ")" + alvo))
                .as("controle positivo: aprovacao de outra pessoa com os valores pedidos").isEqualTo("OK");
        assertThat(value("SELECT status || '|' || (renewal_requested_by_membership_id IS NULL) FROM client_workspace_grants" + alvo)).isEqualTo("2|true");

        // reativacao de SUSPENSA sem pedido
        assertThat(s.admin().post(base + "/" + g + "/review", "{\"decision\":\"SUSPEND\"}").status()).isEqualTo(200);
        assertThat(AccessInvitationsIT.platformSql(org, "UPDATE client_workspace_grants SET status = 2" + alvo)).as("reativar sem pedido").isEqualTo("42501");

        // valores pedidos de uma PENDENTE sao imutaveis
        String client = value("SELECT client_id FROM client_memberships WHERE client_membership_id = '" + s.clientMembership() + "'");
        UUID ws2 = newWorkspace(s.org(), UUID.fromString(client));
        UUID p = UUID.fromString(s.admin().post(base, criar(s.clientMembership(), ws2)).field("grantId"));
        assertThat(AccessInvitationsIT.platformSql(org, "UPDATE client_workspace_grants SET next_review_at = next_review_at + interval '1 day'"
                + " WHERE grant_id = '" + p + "'")).as("valor pedido da pendente").isEqualTo("42501");
        assertThat(AccessInvitationsIT.platformSql(org, "UPDATE client_workspace_grants SET status = 2, approved_by_membership_id = '" + aprovador
                + "', approved_at = now(), next_review_at = next_review_at + interval '1 day' WHERE grant_id = '" + p + "'"))
                .as("aprovar com outro valor").isEqualTo("42501");
    }

    /** Concessao ATIVA pelo caminho completo (pedido do admin, aprovacao do segundo ORG_ADMIN). */
    private UUID ativa(Setup s, String base) throws Exception {
        UUID g = UUID.fromString(s.admin().post(base, criar(s.clientMembership(), s.workspace())).field("grantId"));
        approverStepUp(s);
        assertThat(s.approver().post(base + "/" + g + "/approve", "{\"nextReviewAt\":\"" + REVISAO_PEDIDA + "\"}").status()).isEqualTo(200);
        return g;
    }

    /**
     * Revogacao direta (rota da GAP-01) passa a travar a organizacao e a auditar: evento 13 uma vez. Sem
     * external_access.manage: 403 e nada muda. Vinculo de cliente de outra organizacao ou workspace de outro cliente: 404.
     */
    @Test
    void revogacaoDiretaAuditaEPermissaoEEscopoSaoConferidos() throws Exception {
        Setup s = setup();
        String base = "/api/v1/o/" + s.org().id() + "/client-grants";
        UUID g = UUID.fromString(s.admin().post(base, criar(s.clientMembership(), s.workspace()))
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
        assertThat(s.admin().post(base, criar(s.clientMembership(), outroWs)).status())
                .as("workspace de outro cliente").isEqualTo(404);
        assertThat(s.admin().post(base, criar(UUID.randomUUID(), s.workspace())).status())
                .isEqualTo(404);
    }

    /** Garantia do banco: aprovacao gravada pela Platform com aprovador sem external_access.manage -> 42501. */
    @Test
    void bancoRecusaAprovacaoPorQuemNaoTemAPermissao() throws Exception {
        Setup s = setup();
        UUID g = UUID.fromString(s.admin().post("/api/v1/o/" + s.org().id() + "/client-grants",
                criar(s.clientMembership(), s.workspace())).field("grantId"));
        TestUser comum = internalUser(s.org(), true, 2);
        String m = value("SELECT membership_id FROM organization_memberships WHERE user_id = '" + comum.id() + "'");
        assertThat(AccessInvitationsIT.platformSql(s.org().id(), "INSERT INTO client_workspace_grant_approvals (organization_id, grant_id, approval_type,"
                + " approved_by_membership_id, new_starts_at) VALUES ('" + s.org().id() + "', '" + g + "', 1, '" + m + "', now())")).isEqualTo("42501");
        assertThat(AccessInvitationsIT.platformSql(s.org().id(), "UPDATE client_workspace_grants SET status = 4, revoked_at = now(), revoked_by_membership_id = '"
                + m + "' WHERE grant_id = '" + g + "'")).isEqualTo("42501");
    }

    /**
     * Garantias do banco da segunda pessoa (024), pelo papel nekkora_platform: aprovacao INITIAL e PENDENTE -> ATIVA pelo
     * proprio solicitante -> 42501; solicitante imutavel -> 42501; concessao criada ja ativa, sem solicitante ou com
     * solicitante sem a permissao -> 42501. Pela rota: concessao pendente sem solicitante (anterior a 024; fixture declarada do
     * superusuario) -> 409, sem efeito; revogavel normalmente.
     */
    @Test
    void bancoExigeSegundaPessoaEOSolicitanteGravado() throws Exception {
        Setup s = setup();
        UUID org = s.org().id();
        UUID g = UUID.fromString(s.admin().post("/api/v1/o/" + org + "/client-grants",
                criar(s.clientMembership(), s.workspace())).field("grantId"));
        String solicitante = s.org().adminMembership().toString();
        assertThat(AccessInvitationsIT.platformSql(org, "INSERT INTO client_workspace_grant_approvals (organization_id, grant_id, approval_type,"
                + " approved_by_membership_id, new_starts_at) VALUES ('" + org + "', '" + g + "', 1, '" + solicitante + "', now())")).isEqualTo("42501");
        assertThat(AccessInvitationsIT.platformSql(org, "UPDATE client_workspace_grants SET status = 2, approved_by_membership_id = '" + solicitante
                + "', approved_at = now(), next_review_at = now() + interval '30 days' WHERE grant_id = '" + g + "'")).isEqualTo("42501");
        assertThat(AccessInvitationsIT.platformSql(org, "UPDATE client_workspace_grants SET requested_by_membership_id = '"
                + membershipOf(s.approverUser().id()) + "' WHERE grant_id = '" + g + "'")).as("solicitante imutavel").isEqualTo("42501");
        assertThat(value("SELECT status || '|' || requested_by_membership_id FROM client_workspace_grants WHERE grant_id = '" + g + "'"))
                .isEqualTo("1|" + solicitante);

        String client = value("SELECT client_id FROM client_memberships WHERE client_membership_id = '" + s.clientMembership() + "'");
        UUID ws2 = newWorkspace(s.org(), UUID.fromString(client));
        String insert = "INSERT INTO client_workspace_grants (organization_id, client_id, client_membership_id, workspace_id, status, starts_at";
        String alvo = "'" + org + "', '" + client + "', '" + s.clientMembership() + "', '" + ws2 + "'";
        assertThat(AccessInvitationsIT.platformSql(org, insert + ", approved_by_membership_id, approved_at, next_review_at, requested_by_membership_id)"
                + " VALUES (" + alvo + ", 2, now(), '" + solicitante + "', now(), now() + interval '30 days', '" + solicitante + "')"))
                .as("ja ativa").isEqualTo("42501");
        assertThat(AccessInvitationsIT.platformSql(org, insert + ", next_review_at) VALUES (" + alvo + ", 1, now(), now() + interval '30 days')"))
                .as("sem solicitante").isEqualTo("42501");
        TestUser comum = internalUser(s.org(), true, 2);
        assertThat(AccessInvitationsIT.platformSql(org, insert + ", next_review_at, requested_by_membership_id) VALUES (" + alvo
                + ", 1, now(), now() + interval '30 days', '" + membershipOf(comum.id()) + "')")).as("solicitante sem permissao").isEqualTo("42501");
        assertThat(AccessInvitationsIT.platformSql(org, insert + ", requested_by_membership_id) VALUES (" + alvo + ", 1, now(), '" + solicitante + "')"))
                .as("sem a proxima revisao pedida").isEqualTo("42501");
        assertThat(count("SELECT count(*) FROM client_workspace_grants WHERE workspace_id = '" + ws2 + "'")).isZero();

        // concessao pendente sem solicitante (anterior a 024): a rota nao aprova (409, sem efeito) e a revogacao funciona
        UUID legado = UUID.fromString(value(insert + ", next_review_at) VALUES (" + alvo + ", 1, now(), '" + REVISAO_PEDIDA + "') RETURNING grant_id"));
        approverStepUp(s);
        assertThat(s.approver().post("/api/v1/o/" + org + "/client-grants/" + legado + "/approve", "{\"nextReviewAt\":\"" + REVISAO_PEDIDA + "\"}").status())
                .isEqualTo(409);
        assertThat(value("SELECT status FROM client_workspace_grants WHERE grant_id = '" + legado + "'")).isEqualTo("1");
        assertThat(count("SELECT count(*) FROM client_workspace_grant_approvals WHERE grant_id = '" + legado + "'")).isZero();
        assertThat(s.approver().post("/api/v1/o/" + org + "/client-grants/" + legado + "/revoke", null).status()).isEqualTo(204);
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
