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
 * concessao; reconferencias depois dos locks. Step-up nos atos de aprovacao (inicial e de renovacao) e no KEEP.
 * Auditoria em {@code access_events} e, nas aprovacoes e revisoes, tambem nas tabelas proprias da 003.
 *
 * Segunda pessoa e aprovacao vinculada (D-GRANT-1 e D-GRANT-1a, 024; o banco confere de novo): a concessao nasce PENDENTE
 * com os valores pedidos (inicio, proxima revisao, prazo) e o solicitante; a aprovacao INITIAL e de outra pessoa e aplica
 * exatamente esses valores (o aprovador informa os valores que conferiu; diferentes: 409). Toda extensao de uma concessao
 * aprovada passa por um pedido de renovacao e pela aprovacao de outra pessoa com os valores pedidos; qualquer mudanca de
 * validade descarta o pedido pendente. KEEP so com a mesma data ou uma data mais cedo. Reducao, suspensao e revogacao: uma
 * pessoa com a permissao.
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
                        OffsetDateTime expiresAt, OffsetDateTime nextReviewAt, RenewalRequest renewal) {
    }

    /** Pedido de renovacao pendente (024): quem pediu e os valores pedidos ({@code expiresAt} nulo = sem prazo). */
    public record RenewalRequest(UUID requestedByMembershipId, OffsetDateTime requestedAt, OffsetDateTime nextReviewAt, OffsetDateTime expiresAt,
                                 String justification) {
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

    /**
     * Cria PENDENTE (sem acesso ate a aprovacao) com os valores pedidos: inicio, proxima revisao (obrigatoria, no futuro) e
     * prazo. Vinculo de cliente e cliente ativos; workspace ativo do mesmo cliente.
     */
    public Grant create(UUID org, ValidSession actor, String actorToken, UUID clientMembership, UUID workspace, OffsetDateTime startsAt,
                        OffsetDateTime expiresAt, OffsetDateTime nextReviewAt) {
        if (expiresAt != null && startsAt != null && !expiresAt.isAfter(startsAt)) {
            throw com.nekkora.core.web.ApiException.invalid("prazo");
        }
        if (nextReviewAt == null) {
            throw com.nekkora.core.web.ApiException.invalid("proxima_revisao");
        }
        return actions.write(org, j -> {
            authorize(j, org, actor, actorToken);
            requireFuture(j, nextReviewAt);
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
                        INSERT INTO public.client_workspace_grants (organization_id, client_id, client_membership_id, workspace_id, status, starts_at, expires_at,
                            next_review_at, requested_by_membership_id)
                        VALUES (?, ?, ?, ?, 1, coalesce(?, now()), ?, ?, ?)
                        """ + "RETURNING " + COLUMNS).param(org).param(client).param(clientMembership).param(workspace).param(startsAt).param(expiresAt)
                        .param(nextReviewAt).param(actor.membershipId()).query(GrantAdminService::map).single();
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

    /**
     * PENDENTE -> ATIVA, com a aprovacao INITIAL, por pessoa diferente do solicitante (sem solicitante gravado: 409). O
     * aprovador informa a proxima revisao e o prazo que conferiu; diferentes dos pedidos: 409 {@code valores_divergentes}.
     * Proxima revisao pedida ja vencida: 409 (revogue e peca de novo).
     */
    public Grant approve(UUID org, ValidSession actor, String actorToken, UUID grant, OffsetDateTime nextReviewAt, OffsetDateTime expiresAt,
                         String justification) {
        if (justification != null && justification.length() > 2000) {
            throw com.nekkora.core.web.ApiException.invalid("justificativa");
        }
        return actions.write(org, j -> {
            authorize(j, org, actor, actorToken);
            actions.requireStepUp(j, actor);
            Grant g = lock(j, org, grant);
            if (!"PENDENTE".equals(g.status())) {
                throw refuse(Failure.CONFLICT, "concessao_nao_pendente");
            }
            UUID requester = j.sql("SELECT requested_by_membership_id FROM public.client_workspace_grants WHERE organization_id = ? AND grant_id = ?")
                    .param(org).param(grant).query(UUID.class).optional().orElse(null);
            if (requester == null) {
                throw refuse(Failure.CONFLICT, "concessao_sem_solicitante");
            }
            if (requester.equals(actor.membershipId())) {
                throw refuse(Failure.SECOND_PERSON, "aprovador_igual_solicitante");
            }
            if (!same(nextReviewAt, g.nextReviewAt()) || !same(expiresAt, g.expiresAt())) {
                throw refuse(Failure.CONFLICT, "valores_divergentes");
            }
            if (!future(j, g.nextReviewAt())) {
                throw refuse(Failure.CONFLICT, "proxima_revisao_vencida");
            }
            OffsetDateTime at = insertApproval(j, org, actor, g.grantId(), (short) 1, requester, null, null, g.startsAt(), g.expiresAt(), g.nextReviewAt(),
                    justification);
            Grant out = update(j, org, g.grantId(), "status = 2, approved_by_membership_id = ?, approved_at = ?", actor.membershipId(), at);
            audit(j, org, actor, AccessEvents.Type.CONCESSAO_APROVADA, g.grantId(), null);
            return out;
        });
    }

    /**
     * Pedido de renovacao (ATIVA ou SUSPENSA): grava na concessao a proxima revisao e o prazo pedidos ({@code expiresAt} nulo
     * = sem prazo) e a justificativa, sem mudar o acesso; substitui um pedido anterior. Evento 14. Sem step-up: o pedido nao
     * amplia nada; quem amplia e a aprovacao, de outra pessoa.
     */
    public Grant requestRenewal(UUID org, ValidSession actor, String actorToken, UUID grant, OffsetDateTime nextReviewAt, OffsetDateTime expiresAt,
                                String justification) {
        if (justification == null || justification.isBlank()) {
            throw com.nekkora.core.web.ApiException.invalid("justificativa");
        }
        if (justification.length() > 2000) {
            throw com.nekkora.core.web.ApiException.invalid("justificativa");
        }
        if (nextReviewAt == null) {
            throw com.nekkora.core.web.ApiException.invalid("proxima_revisao");
        }
        return actions.write(org, j -> {
            authorize(j, org, actor, actorToken);
            Grant g = lock(j, org, grant);
            if (!"ATIVA".equals(g.status()) && !"SUSPENSA".equals(g.status())) {
                throw refuse(Failure.CONFLICT, "concessao_nao_renovavel");
            }
            requireFuture(j, nextReviewAt);
            if (expiresAt != null && !expiresAt.isAfter(g.startsAt())) {
                throw refuse(Failure.INVALID, "prazo");
            }
            Grant out = update(j, org, g.grantId(), "renewal_requested_by_membership_id = ?, renewal_requested_at = clock_timestamp(),"
                    + " renewal_next_review_at = ?, renewal_expires_at = ?, renewal_justification = ?", actor.membershipId(), nextReviewAt, expiresAt,
                    justification);
            audit(j, org, actor, AccessEvents.Type.CONCESSAO_RENOVACAO_SOLICITADA, g.grantId(), null);
            return out;
        });
    }

    /**
     * Aprovacao do pedido de renovacao: outra pessoa (o solicitante: 403), com step-up, informando os valores que conferiu
     * (diferentes dos pedidos: 409 {@code valores_divergentes}). Aplica exatamente os valores pedidos; ATIVA. Sem pedido
     * pendente (nunca houve, ou caiu com uma mudanca de validade): 409 {@code sem_renovacao_pendente}.
     */
    public Grant approveRenewal(UUID org, ValidSession actor, String actorToken, UUID grant, OffsetDateTime nextReviewAt, OffsetDateTime expiresAt) {
        return actions.write(org, j -> {
            authorize(j, org, actor, actorToken);
            actions.requireStepUp(j, actor);
            Grant g = lock(j, org, grant);
            if (!"ATIVA".equals(g.status()) && !"SUSPENSA".equals(g.status())) {
                throw refuse(Failure.CONFLICT, "concessao_nao_renovavel");
            }
            RenewalRequest r = g.renewal();
            if (r == null) {
                throw refuse(Failure.CONFLICT, "sem_renovacao_pendente");
            }
            if (r.requestedByMembershipId().equals(actor.membershipId())) {
                throw refuse(Failure.SECOND_PERSON, "aprovador_igual_solicitante");
            }
            if (!same(nextReviewAt, r.nextReviewAt()) || !same(expiresAt, r.expiresAt())) {
                throw refuse(Failure.CONFLICT, "valores_divergentes");
            }
            if (!future(j, r.nextReviewAt())) {
                throw refuse(Failure.CONFLICT, "proxima_revisao_vencida");
            }
            OffsetDateTime at = insertApproval(j, org, actor, g.grantId(), (short) 2, r.requestedByMembershipId(), g.expiresAt(), g.nextReviewAt(),
                    g.startsAt(), r.expiresAt(), r.nextReviewAt(), r.justification());
            Grant out = update(j, org, g.grantId(), "status = 2, approved_by_membership_id = ?, approved_at = ?, next_review_at = ?, expires_at = ?",
                    actor.membershipId(), at, r.nextReviewAt(), r.expiresAt());
            audit(j, org, actor, AccessEvents.Type.CONCESSAO_RENOVADA, g.grantId(), null);
            return out;
        });
    }

    /**
     * Revisao periodica: KEEP (so ATIVA, com step-up; sem data ou com a mesma data, so registra; com data mais cedo, reduz; data
     * mais tarde e extensao: 409 {@code keep_estenderia_prazo}, use o pedido de renovacao), SUSPEND (so ATIVA), REVOKE (ATIVA ou
     * SUSPENSA). Reducao, suspensao e revogacao descartam o pedido de renovacao pendente (024).
     */
    public Grant review(UUID org, ValidSession actor, String actorToken, UUID grant, Decision decision, String notes, OffsetDateTime nextReviewAt) {
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
            if (decision == Decision.KEEP && nextReviewAt != null) {
                if (nextReviewAt.isAfter(g.nextReviewAt())) {
                    throw refuse(Failure.CONFLICT, "keep_estenderia_prazo");
                }
                requireFuture(j, nextReviewAt);
            }
            j.sql("INSERT INTO public.client_workspace_grant_reviews (organization_id, grant_id, reviewed_by_membership_id, decision, notes) VALUES (?, ?, ?, ?, ?)")
                    .param(org).param(grant).param(actor.membershipId()).param(decision.code()).param(notes).update();
            Grant out = switch (decision) {
                case KEEP -> nextReviewAt == null || nextReviewAt.isEqual(g.nextReviewAt()) ? g
                        : update(j, org, grant, "next_review_at = ?", nextReviewAt);
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

    /**
     * Linha da aprovacao (003) com o solicitante (024); devolve o instante gravado, que a concessao recebe igual em
     * {@code approved_at} (o banco confere a aprovacao RENEWAL por esse instante). Na INITIAL os valores anteriores sao nulos.
     */
    private static OffsetDateTime insertApproval(JdbcClient j, UUID org, ValidSession actor, UUID grant, short type, UUID requester,
                                                 OffsetDateTime previousExpires, OffsetDateTime previousNextReview, OffsetDateTime startsAt,
                                                 OffsetDateTime newExpires, OffsetDateTime newNextReview, String justification) {
        return j.sql("""
                INSERT INTO public.client_workspace_grant_approvals (organization_id, grant_id, approval_type, approved_by_membership_id, approved_at,
                    requested_by_membership_id, previous_starts_at, new_starts_at, previous_expires_at, new_expires_at, previous_next_review_at,
                    new_next_review_at, justification)
                VALUES (?, ?, ?, ?, clock_timestamp(), ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING approved_at
                """).param(org).param(grant).param(type).param(actor.membershipId()).param(requester).param(type == 1 ? null : startsAt).param(startsAt)
                .param(previousExpires).param(newExpires).param(previousNextReview).param(newNextReview).param(justification)
                .query(OffsetDateTime.class).single();
    }

    private static boolean same(OffsetDateTime a, OffsetDateTime b) {
        return a == null ? b == null : b != null && a.isEqual(b);
    }

    private static boolean future(JdbcClient j, OffsetDateTime t) {
        return j.sql("SELECT ?::timestamptz > clock_timestamp()").param(t).query(Boolean.class).single();
    }

    private static void requireFuture(JdbcClient j, OffsetDateTime t) {
        if (!future(j, t)) {
            throw refuse(Failure.INVALID, "proxima_revisao");
        }
    }

    private void audit(JdbcClient j, UUID org, ValidSession actor, AccessEvents.Type type, UUID grant, Short decision) {
        events.record(j, org, AccessEvents.Event.of(type, actor.membershipId(), actor.userId()).grant(grant, decision), AccessActions.correlation());
    }

    private static final String COLUMNS = "grant_id, client_membership_id, workspace_id, status, starts_at, expires_at, next_review_at,"
            + " renewal_requested_by_membership_id, renewal_requested_at, renewal_next_review_at, renewal_expires_at, renewal_justification";

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
        UUID requester = rs.getObject(8, UUID.class);
        RenewalRequest renewal = requester == null ? null : new RenewalRequest(requester, rs.getObject(9, OffsetDateTime.class),
                rs.getObject(10, OffsetDateTime.class), rs.getObject(11, OffsetDateTime.class), rs.getString(12));
        return new Grant(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), status(rs.getShort(4)),
                rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class), rs.getObject(7, OffsetDateTime.class), renewal);
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
