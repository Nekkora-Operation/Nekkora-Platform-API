package com.nekkora.platform.admin;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.db.DbErrors;
import com.nekkora.platform.admin.AccessActions.Failure;
import com.nekkora.platform.session.ValidSession;

import static com.nekkora.platform.admin.AccessActions.refuse;

/**
 * Concessoes externas (contrato de 2026-10-27, §7): acesso fixo de leitura de uma conta de cliente a um workspace do mesmo
 * cliente, lido pelo Portal (013) a cada requisicao. Permissao {@code external_access.manage} (a 023 confere de novo em
 * aprovacoes, revisoes, aprovador e revogador). Organizacao {@code FOR UPDATE} -> conta do ator {@code FOR KEY SHARE} ->
 * concessao; reconferencias depois dos locks. Step-up so nos atos que ampliam o acesso (aprovar, renovar, revisar com KEEP).
 * Auditoria em {@code access_events} e, nas aprovacoes e revisoes, tambem nas tabelas proprias da 003.
 */
public final class GrantAdminService {

    public enum Decision {
        KEEP(1), SUSPEND(2), REVOKE(3);

        private final short code;

        Decision(int code) {
            this.code = (short) code;
        }

        public short code() {
            return code;
        }

        public static Optional<Decision> parse(String s) {
            for (Decision d : values()) {
                if (d.name().equals(s)) {
                    return Optional.of(d);
                }
            }
            return Optional.empty();
        }
    }

    public record Grant(UUID grantId, UUID clientMembershipId, UUID workspaceId, String status, OffsetDateTime startsAt,
                        OffsetDateTime expiresAt, OffsetDateTime nextReviewAt) {
    }

    private final AccessActions actions;
    private final AccessEvents events;

    GrantAdminService(AccessActions actions, AccessEvents events) {
        this.actions = actions;
        this.events = events;
    }

    public static GrantAdminService create(AccessActions actions, AccessEvents events) {
        return new GrantAdminService(actions, events);
    }

    /** Cria PENDENTE (sem acesso ate a aprovacao). Vinculo de cliente e cliente ativos; workspace ativo do mesmo cliente. */
    public Grant create(UUID org, ValidSession actor, String actorToken, UUID clientMembership, UUID workspace, OffsetDateTime startsAt,
                        OffsetDateTime expiresAt) {
        if (expiresAt != null && startsAt != null && !expiresAt.isAfter(startsAt)) {
            throw com.nekkora.core.web.ApiException.invalid("prazo");
        }
        return actions.write(org, j -> {
            authorize(j, org, actor, actorToken);
            UUID client = j.sql("""
                    SELECT cm.client_id FROM public.client_memberships cm
                      JOIN public.clients c ON c.organization_id = cm.organization_id AND c.client_id = cm.client_id
                      JOIN public.users u ON u.organization_id = cm.organization_id AND u.user_id = cm.user_id
                     WHERE cm.organization_id = ? AND cm.client_membership_id = ? AND cm.status = 2 AND c.status = 1 AND u.status = 2
                    """).param(org).param(clientMembership).query(UUID.class).optional()
                    .orElseThrow(() -> refuse(Failure.TARGET_NOT_FOUND, "vinculo_cliente"));
            boolean sameClient = j.sql("SELECT EXISTS (SELECT 1 FROM public.workspaces WHERE organization_id = ? AND workspace_id = ? AND client_id = ? AND status = 1)")
                    .param(org).param(workspace).param(client).query(Boolean.class).single();
            if (!sameClient) {
                throw refuse(Failure.TARGET_NOT_FOUND, "workspace");
            }
            Grant g;
            try {
                g = j.sql("""
                        INSERT INTO public.client_workspace_grants (organization_id, client_id, client_membership_id, workspace_id, status, starts_at, expires_at)
                        VALUES (?, ?, ?, ?, 1, coalesce(?, now()), ?)
                        RETURNING grant_id, client_membership_id, workspace_id, status, starts_at, expires_at, next_review_at
                        """).param(org).param(client).param(clientMembership).param(workspace).param(startsAt).param(expiresAt)
                        .query(GrantAdminService::map).single();
            } catch (RuntimeException e) {
                if ("23505".equals(DbErrors.sqlState(e))) {
                    throw refuse(Failure.CONFLICT, "concessao_existente");
                }
                throw e;
            }
            audit(j, org, actor, AccessEvents.Type.CONCESSAO_CRIADA, g.grantId(), null);
            return g;
        });
    }

    /** PENDENTE -> ATIVA, com a aprovacao INITIAL. */
    public Grant approve(UUID org, ValidSession actor, String actorToken, UUID grant, OffsetDateTime nextReviewAt, OffsetDateTime expiresAt,
                         String justification) {
        return actions.write(org, j -> {
            authorize(j, org, actor, actorToken);
            actions.requireStepUp(j, actor);
            Grant g = lock(j, org, grant);
            if (!"PENDENTE".equals(g.status())) {
                throw refuse(Failure.CONFLICT, "concessao_nao_pendente");
            }
            return approveOrRenew(j, org, actor, g, (short) 1, nextReviewAt, expiresAt, justification, AccessEvents.Type.CONCESSAO_APROVADA);
        });
    }

    /** ATIVA ou SUSPENSA -> ATIVA, com a aprovacao RENEWAL (valores anteriores e novos). Justificativa obrigatoria. */
    public Grant renew(UUID org, ValidSession actor, String actorToken, UUID grant, OffsetDateTime nextReviewAt, OffsetDateTime expiresAt,
                       String justification) {
        if (justification == null || justification.isBlank()) {
            throw com.nekkora.core.web.ApiException.invalid("justificativa");
        }
        return actions.write(org, j -> {
            authorize(j, org, actor, actorToken);
            actions.requireStepUp(j, actor);
            Grant g = lock(j, org, grant);
            if (!"ATIVA".equals(g.status()) && !"SUSPENSA".equals(g.status())) {
                throw refuse(Failure.CONFLICT, "concessao_nao_renovavel");
            }
            return approveOrRenew(j, org, actor, g, (short) 2, nextReviewAt, expiresAt, justification, AccessEvents.Type.CONCESSAO_RENOVADA);
        });
    }

    /**
     * Revisao periodica: KEEP (so ATIVA; prazo de revisao novo, com step-up), SUSPEND (so ATIVA), REVOKE (ATIVA ou SUSPENSA).
     */
    public Grant review(UUID org, ValidSession actor, String actorToken, UUID grant, Decision decision, String notes, OffsetDateTime nextReviewAt) {
        if (decision == Decision.KEEP && nextReviewAt == null) {
            throw com.nekkora.core.web.ApiException.invalid("proxima_revisao");
        }
        if (notes != null && notes.length() > 2000) {
            throw com.nekkora.core.web.ApiException.invalid("notas");
        }
        return actions.write(org, j -> {
            authorize(j, org, actor, actorToken);
            if (decision == Decision.KEEP) {
                actions.requireStepUp(j, actor);
            }
            Grant g = lock(j, org, grant);
            boolean allowed = switch (decision) {
                case KEEP, SUSPEND -> "ATIVA".equals(g.status());
                case REVOKE -> "ATIVA".equals(g.status()) || "SUSPENSA".equals(g.status());
            };
            if (!allowed) {
                throw refuse(Failure.CONFLICT, "concessao_estado");
            }
            j.sql("INSERT INTO public.client_workspace_grant_reviews (organization_id, grant_id, reviewed_by_membership_id, decision, notes) VALUES (?, ?, ?, ?, ?)")
                    .param(org).param(grant).param(actor.membershipId()).param(decision.code()).param(notes).update();
            Grant out = switch (decision) {
                case KEEP -> {
                    requireFuture(j, nextReviewAt);
                    yield update(j, org, grant, "next_review_at = ?", nextReviewAt);
                }
                case SUSPEND -> update(j, org, grant, "status = 3");
                case REVOKE -> update(j, org, grant, "status = 4, revoked_at = greatest(clock_timestamp(), created_at), revoked_by_membership_id = ?",
                        actor.membershipId());
            };
            audit(j, org, actor, AccessEvents.Type.CONCESSAO_REVISADA, grant, decision.code());
            return out;
        });
    }

    /** Revogacao direta (rota existente desde a GAP-01). Idempotente: ja revogada, 204 sem evento. */
    public void revoke(UUID org, ValidSession actor, String actorToken, UUID grant) {
        actions.write(org, j -> {
            authorize(j, org, actor, actorToken);
            Grant g = lock(j, org, grant);
            if (!"REVOGADA".equals(g.status())) {
                // revoked_at ajustado ao created_at se o relogio do banco recuou (CHECK da 003), como nas sessoes
                update(j, org, grant, "status = 4, revoked_at = greatest(clock_timestamp(), created_at), revoked_by_membership_id = ?", actor.membershipId());
                audit(j, org, actor, AccessEvents.Type.CONCESSAO_REVOGADA, grant, null);
            }
            return null;
        });
    }

    private void authorize(JdbcClient j, UUID org, ValidSession actor, String actorToken) {
        actions.lockOrganizationAndActor(j, org, actor.userId());
        actions.requireActor(j, org, actor, actorToken);
        actions.requirePermission(j, org, actor, "external_access.manage");
    }

    private Grant approveOrRenew(JdbcClient j, UUID org, ValidSession actor, Grant g, short type, OffsetDateTime nextReviewAt, OffsetDateTime expiresAt,
                                 String justification, AccessEvents.Type event) {
        if (nextReviewAt == null) {
            throw refuse(Failure.INVALID, "proxima_revisao");
        }
        if (justification != null && justification.length() > 2000) {
            throw refuse(Failure.INVALID, "justificativa");
        }
        requireFuture(j, nextReviewAt);
        if (expiresAt != null && !expiresAt.isAfter(g.startsAt())) {
            throw refuse(Failure.INVALID, "prazo");
        }
        j.sql("""
                INSERT INTO public.client_workspace_grant_approvals (organization_id, grant_id, approval_type, approved_by_membership_id, approved_at,
                    previous_starts_at, new_starts_at, previous_expires_at, new_expires_at, previous_next_review_at, new_next_review_at, justification)
                VALUES (?, ?, ?, ?, clock_timestamp(), ?, ?, ?, ?, ?, ?, ?)
                """).param(org).param(g.grantId()).param(type).param(actor.membershipId()).param(type == 1 ? null : g.startsAt()).param(g.startsAt())
                .param(type == 1 ? null : g.expiresAt()).param(expiresAt).param(type == 1 ? null : g.nextReviewAt()).param(nextReviewAt)
                .param(justification).update();
        Grant out = update(j, org, g.grantId(), "status = 2, approved_by_membership_id = ?, approved_at = clock_timestamp(), next_review_at = ?, expires_at = ?",
                actor.membershipId(), nextReviewAt, expiresAt);
        audit(j, org, actor, event, g.grantId(), null);
        return out;
    }

    private static void requireFuture(JdbcClient j, OffsetDateTime t) {
        if (!j.sql("SELECT ?::timestamptz > clock_timestamp()").param(t).query(Boolean.class).single()) {
            throw refuse(Failure.INVALID, "proxima_revisao");
        }
    }

    private void audit(JdbcClient j, UUID org, ValidSession actor, AccessEvents.Type type, UUID grant, Short decision) {
        events.record(j, org, AccessEvents.Event.of(type, actor.membershipId(), actor.userId()).grant(grant, decision), AccessActions.correlation());
    }

    private static final String COLUMNS = "grant_id, client_membership_id, workspace_id, status, starts_at, expires_at, next_review_at";

    private static Grant lock(JdbcClient j, UUID org, UUID grant) {
        return j.sql("SELECT " + COLUMNS + " FROM public.client_workspace_grants WHERE organization_id = ? AND grant_id = ? FOR UPDATE")
                .param(org).param(grant).query(GrantAdminService::map).optional().orElseThrow(() -> refuse(Failure.TARGET_NOT_FOUND, "concessao"));
    }

    private static Grant update(JdbcClient j, UUID org, UUID grant, String set, Object... values) {
        var spec = j.sql("UPDATE public.client_workspace_grants SET " + set + " WHERE organization_id = ? AND grant_id = ? RETURNING " + COLUMNS);
        for (Object v : values) {
            spec = spec.param(v);
        }
        return spec.param(org).param(grant).query(GrantAdminService::map).single();
    }

    private static Grant map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Grant(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), status(rs.getShort(4)),
                rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class), rs.getObject(7, OffsetDateTime.class));
    }

    static String status(short s) {
        return switch (s) {
            case 1 -> "PENDENTE";
            case 2 -> "ATIVA";
            case 3 -> "SUSPENSA";
            case 4 -> "REVOGADA";
            default -> throw new IllegalStateException("estado de concessao fora do catalogo: " + s);
        };
    }
}
