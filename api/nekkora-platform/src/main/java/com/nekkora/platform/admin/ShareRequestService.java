package com.nekkora.platform.admin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.db.DbErrors;
import com.nekkora.platform.admin.AccessActions.Failure;
import com.nekkora.platform.session.ValidSession;

import static com.nekkora.platform.admin.AccessActions.refuse;

/**
 * Pedido de compartilhamento por membro (PLATFORM-FLUXO-002, 071). Quem tem {@code client_share.request} num ambiente (hoje, o
 * WORKSPACE_ADMIN) pede o compartilhamento de UMA automacao dali com uma pessoa externa (nome e e-mail), so com conteudos que ele
 * mesmo pode ver (dashboard: automation.read; arquivos de retorno: logs.read). O pedido nao concede nada e nao envia e-mail: OUTRA
 * pessoa com org.manage e external_access.manage aprova com step-up (D-GRANT-1, antes do convite) e, no mesmo ato, emite o convite
 * (sem a excecao da administradora). Recusar e cancelar fecham o pedido. O banco (gatilho da 071) reconfere autoridade, segunda
 * pessoa, escopo e transicoes; o aceite reconfere a permissao de quem pediu. Auditoria: tipos 26..29.
 */
public final class ShareRequestService {

    public record ShareRequest(UUID shareRequestId, String status, String recipientName, String email, UUID workspaceId, UUID automationId,
                               UUID workerId, boolean canViewDashboard, boolean canDownloadResults, UUID requestedByMembershipId,
                               OffsetDateTime createdAt, OffsetDateTime decidedAt, UUID invitationId) {
    }

    private final AccessActions actions;
    private final AccessEvents events;
    private final InvitationService invitations;

    ShareRequestService(AccessActions actions, AccessEvents events, InvitationService invitations) {
        this.actions = actions;
        this.events = events;
        this.invitations = invitations;
    }

    public static ShareRequestService create(AccessActions actions, AccessEvents events, InvitationService invitations) {
        return new ShareRequestService(actions, events, invitations);
    }

    /** Pedido do membro. Sem step-up (nao concede nada). Recusas como as do convite de Cliente; ja ha pedido igual: 409 {@code pedido_pendente}. */
    public ShareRequest create(UUID org, ValidSession actor, String token, String rawEmail, String rawName, UUID automation, UUID worker,
                               boolean dashboard, boolean files) {
        String email = InvitationService.normalizedEmail(rawEmail);
        String name = InvitationService.recipientName(rawName);
        InvitationService.ClientScope scope = new InvitationService.ClientScope(null, automation, worker, dashboard, files);
        InvitationService.requireContent(scope);
        return actions.write(org, j -> {
            actions.lockOrganizationAndActor(j, org, actor.userId());
            actions.requireActor(j, org, actor, token);
            UUID workspace = InvitationService.requireAutomationScope(j, org, automation, worker);
            boolean allowed = j.sql("SELECT public.access_share_request_allowed(?, ?, ?, ?, ?)").param(org).param(actor.membershipId()).param(workspace)
                    .param(dashboard).param(files).query(Boolean.class).single();
            if (!allowed) {
                throw refuse(Failure.PERMISSION, "client_share.request");
            }
            InvitationService.requireClientRecipient(j, org, email, scope);
            ShareRequest out;
            try {
                out = j.sql("""
                        INSERT INTO public.client_share_requests AS r (organization_id, workspace_id, automation_id, worker_id, recipient_name, email_normalized,
                            can_view_dashboard, can_download_results, status, requested_by_membership_id, requested_by_user_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?) RETURNING\s""" + COLUMNS)
                        .param(org).param(workspace).param(automation).param(worker).param(name).param(email).param(dashboard).param(files)
                        .param(actor.membershipId()).param(actor.userId()).query(ShareRequestService::row).single();
            } catch (RuntimeException e) {
                if ("23505".equals(DbErrors.sqlState(e))) {
                    throw refuse(Failure.CONFLICT, "pedido_pendente");
                }
                throw e;
            }
            audit(j, org, actor, AccessEvents.Type.COMPARTILHAMENTO_SOLICITADO, out, null);
            return out;
        });
    }

    /** Aprovacao: outra pessoa (o solicitante: 403), org.manage e external_access.manage, step-up; emite o convite no mesmo ato. */
    public ShareRequest approve(UUID org, ValidSession actor, String token, UUID request) {
        return actions.write(org, j -> {
            authorizeDecision(j, org, actor, token);
            actions.requireStepUp(j, actor);
            ShareRequest r = lockPending(j, org, actor, request);
            ShareRequest decided = decide(j, org, actor, request, (short) 2);
            InvitationService.ClientCreated c = invitations.issueClientIn(j, org, actor, r.email(), r.recipientName(),
                    new InvitationService.ClientScope(null, r.automationId(), r.workerId(), r.canViewDashboard(), r.canDownloadResults()), request);
            audit(j, org, actor, AccessEvents.Type.COMPARTILHAMENTO_APROVADO, decided, c.clientId());
            return new ShareRequest(decided.shareRequestId(), decided.status(), decided.recipientName(), decided.email(), decided.workspaceId(),
                    decided.automationId(), decided.workerId(), decided.canViewDashboard(), decided.canDownloadResults(),
                    decided.requestedByMembershipId(), decided.createdAt(), decided.decidedAt(), c.invitationId());
        });
    }

    /** Recusa: outra pessoa com a mesma autoridade da aprovacao; sem step-up (nao concede nada). */
    public ShareRequest decline(UUID org, ValidSession actor, String token, UUID request) {
        return actions.write(org, j -> {
            authorizeDecision(j, org, actor, token);
            lockPending(j, org, actor, request);
            ShareRequest out = decide(j, org, actor, request, (short) 3);
            audit(j, org, actor, AccessEvents.Type.COMPARTILHAMENTO_RECUSADO, out, null);
            return out;
        });
    }

    /** Cancelamento: so quem pediu, enquanto pendente. */
    public ShareRequest cancel(UUID org, ValidSession actor, String token, UUID request) {
        return actions.write(org, j -> {
            actions.lockOrganizationAndActor(j, org, actor.userId());
            actions.requireActor(j, org, actor, token);
            ShareRequest r = lock(j, org, request);
            if (!r.requestedByMembershipId().equals(actor.membershipId())) {
                throw refuse(Failure.PERMISSION, "so_quem_pediu");
            }
            if (!"PENDENTE".equals(r.status())) {
                throw refuse(Failure.CONFLICT, "pedido_decidido");
            }
            ShareRequest out = decide(j, org, actor, request, (short) 4);
            audit(j, org, actor, AccessEvents.Type.COMPARTILHAMENTO_CANCELADO, out, null);
            return out;
        });
    }

    /**
     * Pedidos visiveis para quem consulta: external_access.manage ve todos; os demais, so os proprios. {@code automation}: filtro
     * opcional. Ate 200, do mais recente.
     */
    public List<ShareRequest> list(UUID org, ValidSession actor, String token, UUID automation, boolean pendingOnly) {
        return actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            boolean all = actions.accounts().hasPermission(j, org, actor.userId(), "external_access.manage");
            return j.sql("SELECT " + COLUMNS + " FROM public.client_share_requests r WHERE r.organization_id = ? AND (? OR r.requested_by_membership_id = ?) "
                            + "AND (?::uuid IS NULL OR r.automation_id = ?::uuid) AND (NOT ? OR r.status = 1) "
                            + "ORDER BY r.created_at DESC, r.share_request_id DESC LIMIT 200")
                    .param(org).param(all).param(actor.membershipId()).param(automation).param(automation).param(pendingOnly)
                    .query(ShareRequestService::row).list();
        });
    }

    private void authorizeDecision(JdbcClient j, UUID org, ValidSession actor, String token) {
        actions.lockOrganizationAndActor(j, org, actor.userId());
        actions.requireActor(j, org, actor, token);
        actions.requirePermission(j, org, actor, "org.manage");
        actions.requirePermission(j, org, actor, "external_access.manage");
    }

    private static ShareRequest lockPending(JdbcClient j, UUID org, ValidSession actor, UUID request) {
        ShareRequest r = lock(j, org, request);
        if (!"PENDENTE".equals(r.status())) {
            throw refuse(Failure.CONFLICT, "pedido_decidido");
        }
        if (r.requestedByMembershipId().equals(actor.membershipId())) {
            throw refuse(Failure.SECOND_PERSON, "aprovador_igual_solicitante");
        }
        return r;
    }

    private static ShareRequest lock(JdbcClient j, UUID org, UUID request) {
        return j.sql("SELECT " + COLUMNS + " FROM public.client_share_requests r WHERE r.organization_id = ? AND r.share_request_id = ? FOR UPDATE OF r")
                .param(org).param(request).query(ShareRequestService::row).optional()
                .orElseThrow(() -> refuse(Failure.TARGET_NOT_FOUND, "pedido_de_compartilhamento"));
    }

    private static ShareRequest decide(JdbcClient j, UUID org, ValidSession actor, UUID request, short status) {
        return j.sql("UPDATE public.client_share_requests r SET status = ?, decided_by_membership_id = ?, decided_at = greatest(clock_timestamp(), created_at) "
                        + "WHERE r.organization_id = ? AND r.share_request_id = ? AND r.status = 1 RETURNING " + COLUMNS)
                .param(status).param(actor.membershipId()).param(org).param(request).query(ShareRequestService::row).single();
    }

    private void audit(JdbcClient j, UUID org, ValidSession actor, AccessEvents.Type type, ShareRequest r, UUID client) {
        events.record(j, org, AccessEvents.ClientEvent.of(type, actor.membershipId(), actor.userId(), client)
                .scope(r.workspaceId(), r.automationId(), r.workerId(), r.canViewDashboard(), r.canDownloadResults()).shareRequest(r.shareRequestId()),
                AccessActions.correlation());
    }

    static final String COLUMNS = "r.share_request_id, r.status, r.recipient_name, r.email_normalized, r.workspace_id, r.automation_id, r.worker_id, "
            + "r.can_view_dashboard, r.can_download_results, r.requested_by_membership_id, r.created_at, r.decided_at, "
            + "(SELECT i.invitation_id FROM public.identity_invitations i WHERE i.organization_id = r.organization_id AND i.share_request_id = r.share_request_id "
            + "ORDER BY i.created_at DESC, i.invitation_id DESC LIMIT 1) AS invitation_id";

    public static String status(short s) {
        return switch (s) {
            case 1 -> "PENDENTE";
            case 2 -> "APROVADO";
            case 3 -> "RECUSADO";
            case 4 -> "CANCELADO";
            default -> throw new IllegalStateException("estado de pedido de compartilhamento fora do catalogo: " + s);
        };
    }

    private static ShareRequest row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new ShareRequest(rs.getObject(1, UUID.class), status(rs.getShort(2)), rs.getString(3), rs.getString(4), rs.getObject(5, UUID.class),
                rs.getObject(6, UUID.class), rs.getObject(7, UUID.class), rs.getBoolean(8), rs.getBoolean(9), rs.getObject(10, UUID.class),
                rs.getObject(11, OffsetDateTime.class), rs.getObject(12, OffsetDateTime.class), rs.getObject(13, UUID.class));
    }
}
