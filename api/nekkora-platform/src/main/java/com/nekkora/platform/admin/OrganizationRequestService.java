package com.nekkora.platform.admin;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nekkora.core.db.DbErrors;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.platform.access.WorkspaceAccess;
import com.nekkora.platform.admin.AccessActions.Failure;
import com.nekkora.platform.session.ValidSession;

/**
 * PLATFORM-LICENCIAMENTO-001 (067; D9 a D12) - os pedidos de criacao de organizacao feitos a ESTA organizacao, vistos e
 * decididos por quem a administra. Contrato: docs/platform-licenciamento-001/t08-organizacoes.md.
 *
 * <p>Quem decide: {@code org.manage}, com confirmacao reforcada. Na aprovacao ele escolhe o RESPONSAVEL pela nova organizacao -
 * ele mesmo (padrao) ou o solicitante. A escolha concede a administracao da nova organizacao e decide de quem e contado o
 * limite de organizacoes vazias. O banco ({@code identity_decide_organization_request}) reconfere que quem decide administra
 * esta organizacao agora, reconfere o limite do responsavel sob a trava dele e cria a organizacao na mesma transacao. So o
 * responsavel recebe acesso: o outro nao entra na nova organizacao.
 */
public class OrganizationRequestService {

    private static final Logger LOG = LoggerFactory.getLogger(OrganizationRequestService.class);
    static final List<String> STATES = List.of("PENDING", "APPROVED", "REFUSED");
    static final List<String> RESPONSIBLE = List.of("APPROVER", "REQUESTER");
    /** Indice unico da 034: quem ficaria responsavel ja criou uma organizacao com esse nome. */
    static final String ORGANIZATION_NAME_KEY = "ux_organization_authorship_creator_name";

    private final AccessActions actions;
    private final WorkspaceAccess access;

    public OrganizationRequestService(AccessActions actions, WorkspaceAccess access) {
        this.actions = actions;
        this.access = access;
    }

    /** Os pedidos feitos a esta organizacao, os pendentes primeiro. So para quem a administra. */
    public Map<String, Object> list(UUID org, ValidSession actor, String token) {
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            access.requireAnyOrganizationPermission(j, org, actor.membershipId(), "org.manage");
            List<Map<String, Object>> items = new ArrayList<>();
            j.sql("""
                    SELECT q.request_id, q.name, q.legal_name, q.state, q.requested_at, q.decided_at, q.responsible, q.refusal_reason,
                           u.display_name, u.email, q.requester_membership_id, d.display_name, q.requester_membership_id = ?
                      FROM public.organization_creation_requests q
                      JOIN public.organization_memberships m ON m.organization_id = q.organization_id AND m.membership_id = q.requester_membership_id
                      JOIN public.users u ON u.organization_id = m.organization_id AND u.user_id = m.user_id
                      LEFT JOIN public.organization_memberships dm ON dm.organization_id = q.organization_id AND dm.membership_id = q.decided_by_membership_id
                      LEFT JOIN public.users d ON d.organization_id = dm.organization_id AND d.user_id = dm.user_id
                     WHERE q.organization_id = ?
                     ORDER BY q.state, q.requested_at DESC, q.request_id LIMIT 200
                    """).param(actor.membershipId()).param(org).query((rs, n) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("requestId", rs.getObject(1, UUID.class));
                m.put("name", rs.getString(2));
                m.put("legalName", rs.getString(3));
                m.put("state", STATES.get(rs.getShort(4) - 1));
                m.put("requestedAt", rs.getObject(5, OffsetDateTime.class));
                m.put("decidedAt", rs.getObject(6, OffsetDateTime.class));
                m.put("responsible", rs.getObject(7) == null ? null : RESPONSIBLE.get(rs.getShort(7) - 1));
                m.put("refusalReason", rs.getString(8));
                Map<String, Object> requester = new LinkedHashMap<>();
                requester.put("membershipId", rs.getObject(11, UUID.class));
                requester.put("displayName", rs.getString(9));
                requester.put("email", rs.getString(10));
                m.put("requester", requester);
                m.put("decidedByDisplayName", rs.getString(12));
                m.put("ownRequest", rs.getBoolean(13));
                items.add(m);
                return m;
            }).list();
            return Map.of("items", items);
        });
    }

    /**
     * Aprova ({@code responsible}: APPROVER ou REQUESTER) ou recusa ({@code reason}) um pedido pendente. Devolve o pedido
     * decidido; {@code createdOrganizationId} so quando quem aprovou ficou responsavel (so ele recebe acesso a ela).
     */
    public Map<String, Object> decide(UUID org, ValidSession actor, String token, UUID request, boolean approve, String responsible, String reason) {
        Short chosen = approve ? (short) (RESPONSIBLE.indexOf(responsible) + 1) : null;
        if (approve && chosen == 0) {
            throw ApiException.invalid("responsavel");
        }
        if (!approve && (reason == null || reason.strip().length() < 10 || reason.strip().length() > 500)) {
            throw ApiException.invalid("motivo");
        }
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            access.requireAnyOrganizationPermission(j, org, actor.membershipId(), "org.manage");
            actions.requireStepUp(j, actor);
            String out;
            try {
                out = j.sql("SELECT public.identity_decide_organization_request(?, ?, ?, ?, ?::smallint, ?::text)").param(org).param(actor.membershipId())
                        .param(request).param(approve).param(chosen).param(reason == null ? null : reason.strip()).query(String.class).single();
            } catch (RuntimeException e) {
                if (DbErrors.uniqueViolation(e, ORGANIZATION_NAME_KEY)) {
                    LOG.info("evento=acesso.negado regra=CONFLICT motivo={} correlacao={}", ApiError.ORGANIZATION_NAME_TAKEN.reason(), AccessActions.correlation());
                    throw new ApiException(ApiError.ORGANIZATION_NAME_TAKEN, "nome_organizacao");
                }
                throw e;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("requestId", request);
            if (out.startsWith("APPROVED:")) {
                UUID created = UUID.fromString(out.substring("APPROVED:".length()));
                r.put("state", "APPROVED");
                r.put("responsible", responsible);
                r.put("createdOrganizationId", "APPROVER".equals(responsible) ? created : null);
                LOG.info("evento=organizacao.pedido_aprovado pedido={} organizacao={} responsavel={} correlacao={}", request, created, responsible,
                        AccessActions.correlation());
                return r;
            }
            if ("REFUSED".equals(out)) {
                r.put("state", "REFUSED");
                r.put("responsible", null);
                r.put("createdOrganizationId", null);
                LOG.info("evento=organizacao.pedido_recusado pedido={} correlacao={}", request, AccessActions.correlation());
                return r;
            }
            ApiError error = switch (out) {
                case "NOT_FOUND" -> throw AccessActions.refuse(Failure.TARGET_NOT_FOUND, "pedido");
                case "NOT_ADMIN" -> throw AccessActions.refuse(Failure.PERMISSION, "pedido_organizacao");
                case "NOT_PENDING" -> ApiError.ORGANIZATION_REQUEST_NOT_PENDING;
                case "OWN_REQUEST" -> ApiError.ORGANIZATION_REQUEST_OWN;
                case "APPROVER_WITHOUT_IDENTITY" -> ApiError.ORGANIZATION_REQUEST_APPROVER_WITHOUT_IDENTITY;
                case "EMPTY_LIMIT" -> ApiError.EMPTY_ORGANIZATION_LIMIT_REACHED;
                case "REQUESTER_NOT_ELIGIBLE", "RESPONSIBLE_NOT_ELIGIBLE" -> ApiError.ORGANIZATION_REQUEST_RESPONSIBLE_NOT_ELIGIBLE;
                default -> throw ApiException.invalid("decisao");
            };
            LOG.info("evento=acesso.negado regra=CONFLICT motivo={} correlacao={}", error.reason(), AccessActions.correlation());
            throw new ApiException(error, String.valueOf(error.reason()));
        });
    }
}
