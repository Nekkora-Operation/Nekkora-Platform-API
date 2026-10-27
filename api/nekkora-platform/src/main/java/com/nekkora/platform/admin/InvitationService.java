package com.nekkora.platform.admin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.token.Tokens;
import com.nekkora.platform.admin.AccessActions.Failure;
import com.nekkora.platform.mail.MailIntents;
import com.nekkora.platform.mail.MailSender;
import com.nekkora.platform.session.ValidSession;

import static com.nekkora.platform.admin.AccessActions.refuse;

/**
 * Convites da organizacao, lado da administracao (contrato de 2026-10-27, §4): emissao, consulta, revogacao e reenvio, com
 * a sessao da organizacao. O aceite e da identidade ({@code identity.InvitationAcceptService}).
 *
 * <p>Emitir e reenviar: {@code org.manage} E autoridade sobre o papel do convite, com step-up. Revogar: {@code org.manage},
 * sem step-up (reduz acesso). Ordem: organizacao {@code FOR UPDATE} -> conta do ator {@code FOR KEY SHARE} -> convite;
 * sessao, permissao e step-up reconferidos depois dos locks. O token nasce aqui, vai so para a fila (cifrado) e o banco
 * guarda so o hash. Prazo: 7 dias (o gatilho da 023 recusa mais).
 */
public final class InvitationService {

    /** Prazo do convite (D-CONV-1); o gatilho da 023 recusa prazo maior. */
    public static final String VALIDITY = "7 days";

    private static final short REVOGADO = 1;
    private static final short REENVIADO = 2;
    private static final short EXPIRADO = 3;

    public record Created(UUID invitationId, String email, String role, UUID workspaceId, OffsetDateTime expiresAt) {
    }

    public record Item(UUID invitationId, String email, String role, UUID workspaceId, String state, OffsetDateTime createdAt,
                       OffsetDateTime expiresAt, OffsetDateTime acceptedAt, OffsetDateTime revokedAt, String revokeReason, UUID issuedBy,
                       UUID replacesInvitationId) {
    }

    private final AccessActions actions;
    private final AccessEvents events;
    private final MailIntents mail;

    InvitationService(AccessActions actions, AccessEvents events, MailIntents mail) {
        this.actions = actions;
        this.events = events;
        this.mail = mail;
    }

    public static InvitationService create(AccessActions actions, AccessEvents events, MailIntents mail) {
        return new InvitationService(actions, events, mail);
    }

    /** Emite um convite. {@code role}: nulo (membro sem papel), ORG_ADMIN, ou papel de workspace com {@code workspace}. */
    public Created issue(UUID org, ValidSession actor, String actorToken, String rawEmail, String role, UUID workspace) {
        String email = normalizedEmail(rawEmail);
        Roles.Requested r = Roles.requested(role, workspace);
        return actions.write(org, j -> {
            lockAndAuthorize(j, org, actor, actorToken, r);
            closeExpiredOpen(j, org, actor, email);
            if (open(j, org, email).isPresent()) {
                throw refuse(Failure.CONFLICT, "convite_pendente");
            }
            requireNotRegistered(j, org, email);
            Created c = insert(j, org, actor, email, r, null);
            events.record(j, org, AccessEvents.Event.of(AccessEvents.Type.CONVITE_EMITIDO, actor.membershipId(), actor.userId())
                    .role(r.roleId(j), null, workspace).invitation(c.invitationId()), AccessActions.correlation());
            return c;
        });
    }

    /** Reenvio: o convite aberto (no prazo ou vencido) e revogado com motivo REENVIADO e um novo, com token e prazo novos, o substitui. */
    public Created resend(UUID org, ValidSession actor, String actorToken, UUID invitation) {
        return actions.write(org, j -> {
            Optional<Row> row0 = row(j, org, invitation, false);
            if (row0.isEmpty()) {
                throw refuse(Failure.TARGET_NOT_FOUND, "convite");
            }
            Roles.Requested r = Roles.requested(row0.get().role(), row0.get().workspace());
            lockAndAuthorize(j, org, actor, actorToken, r);
            Row row = row(j, org, invitation, true).orElseThrow(() -> refuse(Failure.TARGET_NOT_FOUND, "convite"));
            if (row.acceptedAt() != null || row.revokedAt() != null) {
                throw refuse(Failure.CONFLICT, "convite_fechado");
            }
            close(j, org, actor, row.invitationId(), REENVIADO);
            requireNotRegistered(j, org, row.email());
            Created c = insert(j, org, actor, row.email(), r, row.invitationId());
            events.record(j, org, AccessEvents.Event.of(AccessEvents.Type.CONVITE_REENVIADO, actor.membershipId(), actor.userId())
                    .role(r.roleId(j), null, r.workspace()).invitation(c.invitationId()), AccessActions.correlation());
            return c;
        });
    }

    /** Revogacao: idempotente se ja revogado; convite aceito -> 409. */
    public void revoke(UUID org, ValidSession actor, String actorToken, UUID invitation) {
        actions.write(org, j -> {
            actions.lockOrganizationAndActor(j, org, actor.userId());
            actions.requireActor(j, org, actor, actorToken);
            actions.requirePermission(j, org, actor, "org.manage");
            Row row = row(j, org, invitation, true).orElseThrow(() -> refuse(Failure.TARGET_NOT_FOUND, "convite"));
            if (row.acceptedAt() != null) {
                throw refuse(Failure.CONFLICT, "convite_aceito");
            }
            if (row.revokedAt() == null) {
                close(j, org, actor, row.invitationId(), REVOGADO);
                events.record(j, org, AccessEvents.Event.of(AccessEvents.Type.CONVITE_REVOGADO, actor.membershipId(), actor.userId())
                        .invitation(row.invitationId()), AccessActions.correlation());
            }
            return null;
        });
    }

    /** Consulta (org.manage): sem token nem hash; {@code pendingOnly}: so os abertos no prazo. Ate 200, os mais recentes. */
    public List<Item> list(UUID org, ValidSession actor, String actorToken, boolean pendingOnly) {
        return actions.read(org, j -> {
            actions.requireActor(j, org, actor, actorToken);
            actions.requirePermission(j, org, actor, "org.manage");
            return j.sql("""
                    SELECT i.invitation_id, i.email_normalized, r.code, i.workspace_id, i.created_at, i.expires_at, i.accepted_at, i.revoked_at,
                           i.revoke_reason, i.issued_by, i.replaces_invitation_id,
                           CASE WHEN i.accepted_at IS NOT NULL THEN 'ACEITO' WHEN i.revoked_at IS NOT NULL THEN 'REVOGADO'
                                WHEN i.expires_at <= clock_timestamp() THEN 'EXPIRADO' ELSE 'PENDENTE' END AS estado
                      FROM public.identity_invitations i LEFT JOIN public.roles r ON r.role_id = i.role_id
                     WHERE i.organization_id = ? AND i.invited_account_type = 1
                       AND (NOT ? OR (i.accepted_at IS NULL AND i.revoked_at IS NULL AND i.expires_at > clock_timestamp()))
                     ORDER BY i.created_at DESC, i.invitation_id DESC
                     LIMIT 200
                    """).param(org).param(pendingOnly)
                    .query((rs, n) -> new Item(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                            rs.getString("estado"), rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class),
                            rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class), reason(rs.getObject(9, Short.class)),
                            rs.getObject(10, UUID.class), rs.getObject(11, UUID.class)))
                    .list();
        });
    }

    private void lockAndAuthorize(JdbcClient j, UUID org, ValidSession actor, String actorToken, Roles.Requested r) {
        actions.lockOrganizationAndActor(j, org, actor.userId());
        actions.requireActor(j, org, actor, actorToken);
        actions.requirePermission(j, org, actor, "org.manage");
        if (r.code() != null) {
            if (r.workspace() != null && !Roles.workspaceActive(j, org, r.workspace())) {
                throw refuse(Failure.TARGET_NOT_FOUND, "workspace");
            }
            actions.requireRoleAuthority(j, org, actor, r.roleId(j), r.workspace());
        }
        actions.requireStepUp(j, actor);
    }

    /**
     * Destinataria ja cadastrada na organizacao: 409, exceto a readmissao (conta ligada a uma identidade, ativa, com vinculo
     * retirado), que o aceite conclui (§4.3).
     */
    private static void requireNotRegistered(JdbcClient j, UUID org, String email) {
        Optional<Boolean> readmission = j.sql("""
                SELECT public.identity_account_is_linked(u.organization_id, u.user_id) AND u.status = 2
                       AND EXISTS (SELECT 1 FROM public.organization_memberships m WHERE m.organization_id = u.organization_id
                                     AND m.user_id = u.user_id AND m.status = 4)
                  FROM public.users u WHERE u.organization_id = ? AND u.email_normalized = ?
                """).param(org).param(email).query(Boolean.class).optional();
        if (readmission.isPresent() && !readmission.get()) {
            throw refuse(Failure.CONFLICT, "ja_cadastrada");
        }
    }

    private void closeExpiredOpen(JdbcClient j, UUID org, ValidSession actor, String email) {
        List<UUID> closed = j.sql("""
                UPDATE public.identity_invitations SET revoked_at = greatest(clock_timestamp(), created_at), revoke_reason = ?, revoked_by_membership_id = ?
                 WHERE organization_id = ? AND email_normalized = ? AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at <= clock_timestamp()
                RETURNING invitation_id
                """).param(EXPIRADO).param(actor.membershipId()).param(org).param(email).query(UUID.class).list();
        for (UUID id : closed) {
            events.record(j, org, AccessEvents.Event.of(AccessEvents.Type.CONVITE_REVOGADO, actor.membershipId(), actor.userId()).invitation(id),
                    AccessActions.correlation());
        }
    }

    private static Optional<UUID> open(JdbcClient j, UUID org, String email) {
        return j.sql("SELECT invitation_id FROM public.identity_invitations WHERE organization_id = ? AND email_normalized = ? AND accepted_at IS NULL AND revoked_at IS NULL")
                .param(org).param(email).query(UUID.class).optional();
    }

    private static void close(JdbcClient j, UUID org, ValidSession actor, UUID invitation, short reason) {
        j.sql("""
                UPDATE public.identity_invitations SET revoked_at = greatest(clock_timestamp(), created_at), revoke_reason = ?, revoked_by_membership_id = ?
                 WHERE organization_id = ? AND invitation_id = ? AND accepted_at IS NULL AND revoked_at IS NULL
                """).param(reason).param(actor.membershipId()).param(org).param(invitation).update();
    }

    private Created insert(JdbcClient j, UUID org, ValidSession actor, String email, Roles.Requested r, UUID replaces) {
        String token = Tokens.newToken();
        UUID roleId = r.code() == null ? null : r.roleId(j);
        Created c = j.sql("""
                INSERT INTO public.identity_invitations (organization_id, invited_account_type, email_normalized, issued_by, token_hash, expires_at,
                    issued_by_membership_id, role_id, role_scope, workspace_id, replaces_invitation_id)
                VALUES (?, 1, ?, ?, ?, now() + ?::interval, ?, ?, ?, ?, ?)
                RETURNING invitation_id, expires_at
                """).param(org).param(email).param(actor.userId()).param(Tokens.sha256Hex(token)).param(VALIDITY).param(actor.membershipId())
                .param(roleId).param(r.scope()).param(r.workspace()).param(replaces)
                .query((rs, n) -> new Created(rs.getObject(1, UUID.class), email, r.code(), r.workspace(), rs.getObject(2, OffsetDateTime.class)))
                .single();
        // intencao NA transacao do ato (021), chave = o convite: o mesmo convite nunca vira dois trabalhos
        mail.record(j, new MailIntents.Intent(MailSender.Kind.ORG_INVITATION, org, email, token, "convite:" + c.invitationId()));
        return c;
    }

    private record Row(UUID invitationId, String email, String role, UUID workspace, OffsetDateTime acceptedAt, OffsetDateTime revokedAt) {
    }

    private static Optional<Row> row(JdbcClient j, UUID org, UUID invitation, boolean lock) {
        return j.sql("""
                SELECT i.invitation_id, i.email_normalized, r.code, i.workspace_id, i.accepted_at, i.revoked_at
                  FROM public.identity_invitations i LEFT JOIN public.roles r ON r.role_id = i.role_id
                 WHERE i.organization_id = ? AND i.invitation_id = ? AND i.invited_account_type = 1
                """ + (lock ? " FOR UPDATE OF i" : "")).param(org).param(invitation)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class)))
                .optional();
    }

    private static String reason(Short code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case REVOGADO -> "REVOGADO";
            case REENVIADO -> "REENVIADO";
            case EXPIRADO -> "EXPIRADO";
            default -> throw new IllegalStateException("motivo de revogacao fora do catalogo: " + code);
        };
    }

    /** Forma do e-mail (a mesma regra das rotas publicas da identidade) e normalizacao (lower, Locale.ROOT, como a da 002). */
    static String normalizedEmail(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 254 || !raw.equals(raw.strip())) {
            throw refuseInvalid("email");
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                throw refuseInvalid("email");
            }
        }
        int at = raw.indexOf('@');
        if (!(at > 0 && at == raw.lastIndexOf('@') && at < raw.length() - 1)) {
            throw refuseInvalid("email");
        }
        return raw.toLowerCase(Locale.ROOT);
    }

    private static com.nekkora.core.web.ApiException refuseInvalid(String rule) {
        return com.nekkora.core.web.ApiException.invalid(rule);
    }
}
