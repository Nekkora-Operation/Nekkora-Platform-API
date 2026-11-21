package com.nekkora.platform.web;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.platform.admin.AccessEvents;
import com.nekkora.platform.admin.ClientAccessService;
import com.nekkora.platform.admin.GrantAdminService;

/**
 * PLATFORM-FLUXO-002 (070): leituras de "Clientes e acessos" — opcoes de compartilhamento, detalhe do cliente, concessoes das duas
 * especies (por automacao, 070; por ambiente inteiro, 003) e o historico de acesso com filtros. Toda consulta roda na transacao da
 * organizacao (RLS); a permissao e conferida pelo controlador antes.
 */
final class ClientAccessReads {

    private ClientAccessReads() {
    }

    /** Situacoes do filtro de concessoes (as duas especies; "suspended" so existe na concessao por ambiente). */
    static final List<String> GRANT_SITUATIONS = List.of("pending", "active", "suspended", "revoked");

    /** Categorias do historico e os tipos de cada uma. */
    static final Map<String, List<Integer>> HISTORY_CATEGORIES = Map.of(
            "invitations", List.of(1, 2, 3, 4),
            "roles", List.of(5, 6, 7, 8),
            "grants", List.of(9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19),
            "client_links", List.of(20, 21, 22),
            "clients", List.of(23, 24),
            "share_requests", List.of(25, 26, 27, 28, 29));

    // ------------------------------------------------------------------ opcoes do formulario

    /**
     * Automacoes compartilhaveis (ativas, em ambiente ativo) com as maquinas desta automacao: as que ja receberam autorizacao de
     * execucao para ela (a ativacao) e continuam ativas e autorizadas no ambiente. A lista de maquinas so orienta a escolha; a
     * emissao e a aprovacao reconferem a maquina no servidor. {@code all}: administradora (todas, os dois conteudos); senao (071),
     * so as dos ambientes em que a pessoa tem client_share.request, com os conteudos que ela mesma pode ver.
     */
    static List<Map<String, Object>> shareOptions(JdbcClient j, UUID org, UUID membership, boolean all) {
        return j.sql("""
                SELECT * FROM (
                SELECT a.automation_id, a.name, a.workspace_id, w.name AS workspace_name,
                       coalesce((SELECT jsonb_agg(jsonb_build_object('workerId', k.worker_id, 'name', k.name) ORDER BY k.name, k.worker_id)
                                   FROM (SELECT DISTINCT z.worker_id FROM public.local_run_authorizations z
                                          WHERE z.organization_id = a.organization_id AND z.automation_id = a.automation_id) d
                                   JOIN public.workers k ON k.organization_id = a.organization_id AND k.worker_id = d.worker_id AND k.status = 1
                                  WHERE public.access_worker_in_workspace(a.organization_id, k.worker_id, a.workspace_id)), '[]'::jsonb)::text AS machines,
                       :all OR public.access_share_request_allowed(a.organization_id, :m, a.workspace_id, true, false) AS dashboard,
                       :all OR public.access_share_request_allowed(a.organization_id, :m, a.workspace_id, false, true) AS files
                  FROM public.automations a
                  JOIN public.workspaces w ON w.organization_id = a.organization_id AND w.workspace_id = a.workspace_id AND w.status = 1
                 WHERE a.organization_id = :org AND a.status = 1) o
                 WHERE o.dashboard OR o.files
                 ORDER BY o.workspace_name, o.workspace_id, o.name, o.automation_id
                """).param("org", org).param("m", membership).param("all", all).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("automationId", rs.getObject("automation_id", UUID.class).toString());
                    m.put("name", rs.getString("name"));
                    m.put("workspaceId", rs.getObject("workspace_id", UUID.class).toString());
                    m.put("workspaceName", rs.getString("workspace_name"));
                    m.put("machines", Json.parse(rs.getString("machines")));
                    m.put("canShareDashboard", rs.getBoolean("dashboard"));
                    m.put("canShareFiles", rs.getBoolean("files"));
                    return m;
                }).list();
    }

    /** Pedidos de compartilhamento com os nomes (automacao, ambiente, maquina, quem pediu, quem decidiu). */
    static List<Map<String, Object>> shareRequestItems(JdbcClient j, UUID org, List<com.nekkora.platform.admin.ShareRequestService.ShareRequest> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.nekkora.platform.admin.ShareRequestService.ShareRequest r : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shareRequestId", r.shareRequestId().toString());
            m.put("status", r.status());
            m.put("recipientName", r.recipientName());
            m.put("email", r.email());
            j.sql("""
                    SELECT a.name, w.name, k.name, ru.display_name, du.display_name
                      FROM public.client_share_requests q
                      JOIN public.automations a ON a.organization_id = q.organization_id AND a.automation_id = q.automation_id
                      JOIN public.workspaces w ON w.organization_id = q.organization_id AND w.workspace_id = q.workspace_id
                      LEFT JOIN public.workers k ON k.organization_id = q.organization_id AND k.worker_id = q.worker_id
                      JOIN public.organization_memberships rm ON rm.organization_id = q.organization_id AND rm.membership_id = q.requested_by_membership_id
                      JOIN public.users ru ON ru.organization_id = q.organization_id AND ru.user_id = rm.user_id
                      LEFT JOIN public.organization_memberships dm ON dm.organization_id = q.organization_id AND dm.membership_id = q.decided_by_membership_id
                      LEFT JOIN public.users du ON du.organization_id = q.organization_id AND du.user_id = dm.user_id
                     WHERE q.organization_id = :org AND q.share_request_id = :r
                    """).param("org", org).param("r", r.shareRequestId()).query((rs, n) -> {
                        m.put("automation", ref("automationId", r.automationId(), rs.getString(1)));
                        m.put("workspace", ref("workspaceId", r.workspaceId(), rs.getString(2)));
                        m.put("machine", r.workerId() == null ? null : ref("workerId", r.workerId(), rs.getString(3)));
                        m.put("requestedBy", rs.getString(4));
                        m.put("decidedBy", rs.getString(5));
                        return null;
                    }).list();
            m.put("canViewDashboard", r.canViewDashboard());
            m.put("canDownloadResults", r.canDownloadResults());
            m.put("requestedByMembershipId", r.requestedByMembershipId().toString());
            m.put("createdAt", r.createdAt());
            m.put("decidedAt", r.decidedAt());
            m.put("invitationId", r.invitationId() == null ? null : r.invitationId().toString());
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ concessoes de um vinculo

    /** As concessoes que ainda contam de um vinculo de cliente: pedidos e ativas (por automacao) e as por ambiente nao revogadas. */
    static List<Map<String, Object>> openGrants(JdbcClient j, UUID org, UUID clientMembership) {
        String sql = GRANTS_SELECT + " WHERE u.client_membership_id = :m AND u.situation <> 'revoked' ORDER BY u.created_at DESC, u.id DESC";
        return j.sql(sql).param("org", org).param("m", clientMembership).query(ClientAccessReads::grantItem).list();
    }

    /** As duas especies numa forma so. Parametro :org. */
    private static final String GRANTS_UNION = """
            SELECT 'automation' AS kind, g.grant_id AS id, CASE g.status WHEN 1 THEN 'pending' WHEN 2 THEN 'active' ELSE 'revoked' END AS situation,
                   g.created_at, g.client_membership_id, g.client_id, g.workspace_id, g.automation_id, g.worker_id, g.can_view_dashboard,
                   g.can_download_results, g.basis, g.requested_by_membership_id, g.approved_by_membership_id, g.approved_at, g.revoked_at,
                   g.revoke_reason, g.replaces_grant_id, NULL::timestamptz AS expires_at, NULL::timestamptz AS next_review_at
              FROM public.client_automation_grants g WHERE g.organization_id = :org
            UNION ALL
            SELECT 'workspace', g.grant_id, CASE g.status WHEN 1 THEN 'pending' WHEN 2 THEN 'active' WHEN 3 THEN 'suspended' ELSE 'revoked' END,
                   g.created_at, g.client_membership_id, g.client_id, g.workspace_id, NULL::uuid, NULL::uuid, true, false, 1::smallint,
                   g.requested_by_membership_id, g.approved_by_membership_id, g.approved_at, g.revoked_at, NULL::smallint, NULL::uuid,
                   g.expires_at, g.next_review_at
              FROM public.client_workspace_grants g WHERE g.organization_id = :org
            """;

    private static final String GRANTS_SELECT = """
            SELECT u.*, cm.user_id, cu.display_name AS person_name, cu.email AS person_email, cm.status AS membership_status, c.name AS client_name,
                   w.name AS workspace_name, a.name AS automation_name, k.name AS worker_name,
                   ru.display_name AS requested_by_name, au.display_name AS approved_by_name
              FROM (%s) u
              JOIN public.client_memberships cm ON cm.organization_id = :org AND cm.client_membership_id = u.client_membership_id
              JOIN public.users cu ON cu.organization_id = :org AND cu.user_id = cm.user_id
              JOIN public.clients c ON c.organization_id = :org AND c.client_id = u.client_id
              JOIN public.workspaces w ON w.organization_id = :org AND w.workspace_id = u.workspace_id
              LEFT JOIN public.automations a ON a.organization_id = :org AND a.automation_id = u.automation_id
              LEFT JOIN public.workers k ON k.organization_id = :org AND k.worker_id = u.worker_id
              LEFT JOIN public.organization_memberships rm ON rm.organization_id = :org AND rm.membership_id = u.requested_by_membership_id
              LEFT JOIN public.users ru ON ru.organization_id = :org AND ru.user_id = rm.user_id
              LEFT JOIN public.organization_memberships am ON am.organization_id = :org AND am.membership_id = u.approved_by_membership_id
              LEFT JOIN public.users au ON au.organization_id = :org AND au.user_id = am.user_id
            """.formatted(GRANTS_UNION);

    record GrantFilter(UUID person, UUID client, UUID workspace, UUID automation, UUID worker, List<String> situations, OffsetDateTime from,
                       OffsetDateTime to, OffsetDateTime afterAt, UUID afterId, int limit) {
    }

    /** Concessoes (as duas especies), da mais recente; filtros combinados com E. Le {@code limit + 1}. */
    static List<Map<String, Object>> grants(JdbcClient j, UUID org, GrantFilter f) {
        StringBuilder sql = new StringBuilder(GRANTS_SELECT).append(" WHERE true");
        if (f.person() != null) {
            sql.append(" AND cm.user_id = :person");
        }
        if (f.client() != null) {
            sql.append(" AND u.client_id = :client");
        }
        if (f.workspace() != null) {
            sql.append(" AND u.workspace_id = :workspace");
        }
        if (f.automation() != null) {
            sql.append(" AND u.automation_id = :automation");
        }
        if (f.worker() != null) {
            sql.append(" AND u.worker_id = :worker");
        }
        if (f.situations() != null) {
            sql.append(" AND u.situation IN (:situations)");
        }
        if (f.from() != null) {
            sql.append(" AND u.created_at >= :from");
        }
        if (f.to() != null) {
            sql.append(" AND u.created_at < :to");
        }
        if (f.afterAt() != null) {
            sql.append(" AND (u.created_at, u.id) < (:afterAt, :afterId)");
        }
        sql.append(" ORDER BY u.created_at DESC, u.id DESC LIMIT :max");
        JdbcClient.StatementSpec q = j.sql(sql.toString()).param("org", org).param("max", f.limit() + 1);
        q = bind(q, "person", f.person());
        q = bind(q, "client", f.client());
        q = bind(q, "workspace", f.workspace());
        q = bind(q, "automation", f.automation());
        q = bind(q, "worker", f.worker());
        if (f.situations() != null) {
            q = q.param("situations", f.situations());
        }
        q = bind(q, "from", f.from());
        q = bind(q, "to", f.to());
        if (f.afterAt() != null) {
            q = q.param("afterAt", f.afterAt()).param("afterId", f.afterId());
        }
        return q.query(ClientAccessReads::grantItem).list();
    }

    private static Map<String, Object> grantItem(ResultSet rs, int n) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean automation = "automation".equals(rs.getString("kind"));
        m.put("kind", rs.getString("kind"));
        m.put("grantId", rs.getObject("id", UUID.class).toString());
        m.put("situation", rs.getString("situation"));
        m.put("person", person(rs.getObject("user_id", UUID.class), rs.getString("person_name"), rs.getString("person_email")));
        m.put("clientMembershipId", rs.getObject("client_membership_id", UUID.class).toString());
        m.put("membershipStatus", ClientAccessService.membershipStatus(rs.getShort("membership_status")));
        m.put("client", ref("clientId", rs.getObject("client_id", UUID.class), rs.getString("client_name")));
        m.put("workspace", ref("workspaceId", rs.getObject("workspace_id", UUID.class), rs.getString("workspace_name")));
        m.put("automation", automation ? ref("automationId", rs.getObject("automation_id", UUID.class), rs.getString("automation_name")) : null);
        UUID worker = rs.getObject("worker_id", UUID.class);
        m.put("machine", worker == null ? null : ref("workerId", worker, rs.getString("worker_name")));
        m.put("canViewDashboard", rs.getBoolean("can_view_dashboard"));
        m.put("canDownloadResults", rs.getBoolean("can_download_results"));
        m.put("basis", automation ? (rs.getShort("basis") == 2 ? "client_invitation" : "second_person") : "second_person");
        m.put("requestedBy", rs.getString("requested_by_name"));
        m.put("approvedBy", rs.getString("approved_by_name"));
        m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
        m.put("approvedAt", rs.getObject("approved_at", OffsetDateTime.class));
        m.put("revokedAt", rs.getObject("revoked_at", OffsetDateTime.class));
        Object reason = rs.getObject("revoke_reason");
        m.put("revokeReason", reason == null ? null : switch (((Number) reason).intValue()) {
            case 1 -> "revoked";
            case 2 -> "replaced";
            case 3 -> "link_retired";
            default -> "request_cancelled";
        });
        UUID replaces = rs.getObject("replaces_grant_id", UUID.class);
        m.put("replacesGrantId", replaces == null ? null : replaces.toString());
        m.put("expiresAt", rs.getObject("expires_at", OffsetDateTime.class));
        m.put("nextReviewAt", rs.getObject("next_review_at", OffsetDateTime.class));
        return m;
    }

    // ------------------------------------------------------------------ historico

    record HistoryFilter(UUID person, UUID client, UUID workspace, UUID automation, UUID worker, UUID grant, List<Integer> types,
                         boolean clientOnly, OffsetDateTime from, OffsetDateTime to, OffsetDateTime afterAt, UUID afterId, int limit) {
    }

    /**
     * Eventos de acesso, do mais recente, com nomes. Cliente e ambiente das concessoes por ambiente (023) vem da propria concessao (o
     * evento so grava o id dela). {@code types}: os tipos permitidos (e filtrados) para quem consulta.
     */
    static List<Map<String, Object>> history(JdbcClient j, UUID org, HistoryFilter f) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.event_id, e.event_type, e.created_at, e.actor_user_id, e.actor_identity_id IS NOT NULL AS by_identity, e.target_user_id,
                       r.code AS role, pr.code AS previous_role, coalesce(e.workspace_id, cwg.workspace_id) AS workspace_id,
                       coalesce(e.client_id, cwg.client_id) AS client_id, e.automation_id, e.worker_id, e.invitation_id,
                       e.grant_id, e.automation_grant_id, e.decision, e.can_read_logs, e.can_download_results, e.can_view_dashboard, e.share_request_id,
                       e.grant_exception, e.correlation_id, sr.recipient_name AS share_name, sr.email_normalized AS share_email,
                       au.display_name AS actor_name, au.email AS actor_email, tu.display_name AS target_name,
                       coalesce(tu.email, inv.email_normalized, sr.email_normalized) AS target_email,
                       coalesce(tu.display_name, inv.recipient_name, sr.recipient_name) AS target_display,
                       w.name AS workspace_name, c.name AS client_name, a.name AS automation_name, k.name AS worker_name
                  FROM public.access_events e
                  LEFT JOIN public.client_workspace_grants cwg ON cwg.organization_id = e.organization_id AND cwg.grant_id = e.grant_id
                  LEFT JOIN public.roles r ON r.role_id = e.role_id
                  LEFT JOIN public.roles pr ON pr.role_id = e.previous_role_id
                  LEFT JOIN public.users au ON au.organization_id = e.organization_id AND au.user_id = e.actor_user_id
                  LEFT JOIN public.users tu ON tu.organization_id = e.organization_id AND tu.user_id = e.target_user_id
                  LEFT JOIN public.identity_invitations inv ON inv.organization_id = e.organization_id AND inv.invitation_id = e.invitation_id
                  LEFT JOIN public.client_share_requests sr ON sr.organization_id = e.organization_id AND sr.share_request_id = e.share_request_id
                  LEFT JOIN public.workspaces w ON w.organization_id = e.organization_id AND w.workspace_id = coalesce(e.workspace_id, cwg.workspace_id)
                  LEFT JOIN public.clients c ON c.organization_id = e.organization_id AND c.client_id = coalesce(e.client_id, cwg.client_id)
                  LEFT JOIN public.automations a ON a.organization_id = e.organization_id AND a.automation_id = e.automation_id
                  LEFT JOIN public.workers k ON k.organization_id = e.organization_id AND k.worker_id = e.worker_id
                 WHERE e.organization_id = :org AND e.event_type IN (:types)
                """);
        if (f.clientOnly()) {
            sql.append(" AND (coalesce(e.client_id, cwg.client_id) IS NOT NULL OR e.share_request_id IS NOT NULL)");
        }
        if (f.person() != null) {
            sql.append(" AND (e.target_user_id = :person OR e.actor_user_id = :person)");
        }
        if (f.client() != null) {
            sql.append(" AND coalesce(e.client_id, cwg.client_id) = :client");
        }
        if (f.workspace() != null) {
            sql.append(" AND coalesce(e.workspace_id, cwg.workspace_id) = :workspace");
        }
        if (f.automation() != null) {
            sql.append(" AND e.automation_id = :automation");
        }
        if (f.worker() != null) {
            sql.append(" AND e.worker_id = :worker");
        }
        if (f.grant() != null) {
            sql.append(" AND (e.grant_id = :grant OR e.automation_grant_id = :grant)");
        }
        if (f.from() != null) {
            sql.append(" AND e.created_at >= :from");
        }
        if (f.to() != null) {
            sql.append(" AND e.created_at < :to");
        }
        if (f.afterAt() != null) {
            sql.append(" AND (e.created_at, e.event_id) < (:afterAt, :afterId)");
        }
        sql.append(" ORDER BY e.created_at DESC, e.event_id DESC LIMIT :max");
        JdbcClient.StatementSpec q = j.sql(sql.toString()).param("org", org).param("types", f.types()).param("max", f.limit() + 1);
        q = bind(q, "person", f.person());
        q = bind(q, "client", f.client());
        q = bind(q, "workspace", f.workspace());
        q = bind(q, "automation", f.automation());
        q = bind(q, "worker", f.worker());
        q = bind(q, "grant", f.grant());
        q = bind(q, "from", f.from());
        q = bind(q, "to", f.to());
        if (f.afterAt() != null) {
            q = q.param("afterAt", f.afterAt()).param("afterId", f.afterId());
        }
        return q.query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            int type = rs.getInt("event_type");
            m.put("eventId", rs.getObject("event_id", UUID.class).toString());
            m.put("type", AccessEvents.Type.of(type).name());
            m.put("category", category(type));
            m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
            UUID actor = rs.getObject("actor_user_id", UUID.class);
            m.put("actor", actor == null ? null : person(actor, rs.getString("actor_name"), rs.getString("actor_email")));
            m.put("byInvitee", rs.getBoolean("by_identity"));
            UUID target = rs.getObject("target_user_id", UUID.class);
            m.put("target", target == null && rs.getString("target_email") == null ? null
                    : person(target, rs.getString("target_display"), rs.getString("target_email")));
            m.put("role", rs.getString("role"));
            m.put("previousRole", rs.getString("previous_role"));
            m.put("workspace", nullableRef("workspaceId", rs.getObject("workspace_id", UUID.class), rs.getString("workspace_name")));
            m.put("client", nullableRef("clientId", rs.getObject("client_id", UUID.class), rs.getString("client_name")));
            m.put("automation", nullableRef("automationId", rs.getObject("automation_id", UUID.class), rs.getString("automation_name")));
            m.put("machine", nullableRef("workerId", rs.getObject("worker_id", UUID.class), rs.getString("worker_name")));
            UUID wg = rs.getObject("grant_id", UUID.class);
            UUID ag = rs.getObject("automation_grant_id", UUID.class);
            m.put("grant", wg != null ? grantRef("workspace", wg) : ag != null ? grantRef("automation", ag) : null);
            UUID inv = rs.getObject("invitation_id", UUID.class);
            m.put("invitationId", inv == null ? null : inv.toString());
            Object d = rs.getObject("decision");
            m.put("decision", d == null ? null : GrantAdminService.Decision.values()[((Number) d).intValue() - 1].name());
            // 071: can_read_logs fica so no banco (historico); a resposta diz se o ato envolveu registros tecnicos (so os antigos)
            m.put("technicalLogs", Boolean.TRUE.equals(rs.getObject("can_read_logs")));
            m.put("canViewDashboard", rs.getObject("can_view_dashboard"));
            m.put("canDownloadResults", rs.getObject("can_download_results"));
            UUID sr = rs.getObject("share_request_id", UUID.class);
            m.put("shareRequestId", sr == null ? null : sr.toString());
            m.put("grantException", rs.getBoolean("grant_exception"));
            m.put("correlationId", rs.getObject("correlation_id", UUID.class).toString());
            return m;
        }).list();
    }

    static String category(int type) {
        for (Map.Entry<String, List<Integer>> e : HISTORY_CATEGORIES.entrySet()) {
            if (e.getValue().contains(type)) {
                return e.getKey();
            }
        }
        throw new IllegalStateException("tipo sem categoria: " + type);
    }

    // ------------------------------------------------------------------ detalhe do cliente

    static Map<String, Object> clientDetail(JdbcClient j, UUID org, UUID clientId) {
        List<Map<String, Object>> found = j.sql("SELECT client_id, name, status, created_at, updated_at FROM public.clients WHERE organization_id = :org "
                + "AND client_id = :c").param("org", org).param("c", clientId).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("clientId", rs.getObject(1, UUID.class).toString());
                    m.put("name", rs.getString(2));
                    m.put("status", ClientAccessService.clientStatus(rs.getShort(3)));
                    m.put("createdAt", rs.getObject(4, OffsetDateTime.class));
                    m.put("updatedAt", rs.getObject(5, OffsetDateTime.class));
                    return m;
                }).list();
        if (found.isEmpty()) {
            return null;
        }
        Map<String, Object> out = found.get(0);
        List<Map<String, Object>> people = j.sql("""
                SELECT cm.client_membership_id, cm.user_id, u.display_name, u.email, cm.status, cm.created_at,
                       public.identity_account_is_linked(cm.organization_id, cm.user_id) AS linked
                  FROM public.client_memberships cm JOIN public.users u ON u.organization_id = cm.organization_id AND u.user_id = cm.user_id
                 WHERE cm.organization_id = :org AND cm.client_id = :c ORDER BY cm.created_at DESC, cm.client_membership_id DESC LIMIT 200
                """).param("org", org).param("c", clientId).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("clientMembershipId", rs.getObject(1, UUID.class).toString());
                    m.put("person", person(rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)));
                    m.put("status", ClientAccessService.membershipStatus(rs.getShort(5)));
                    m.put("joinedAt", rs.getObject(6, OffsetDateTime.class));
                    m.put("linkedToIdentity", rs.getBoolean(7));
                    return m;
                }).list();
        for (Map<String, Object> p : people) {
            p.put("grants", openGrants(j, org, UUID.fromString((String) p.get("clientMembershipId"))));
        }
        out.put("people", people);
        out.put("pendingInvitations", j.sql("""
                SELECT i.invitation_id, i.email_normalized, a.name, i.created_at, i.expires_at FROM public.identity_invitations i
                  JOIN public.automations a ON a.organization_id = i.organization_id AND a.automation_id = i.automation_id
                 WHERE i.organization_id = :org AND i.client_id = :c AND i.invited_account_type = 2 AND i.accepted_at IS NULL AND i.revoked_at IS NULL
                   AND i.expires_at > clock_timestamp() ORDER BY i.created_at DESC LIMIT 200
                """).param("org", org).param("c", clientId).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("invitationId", rs.getObject(1, UUID.class).toString());
                    m.put("email", rs.getString(2));
                    m.put("automationName", rs.getString(3));
                    m.put("createdAt", rs.getObject(4, OffsetDateTime.class));
                    m.put("expiresAt", rs.getObject(5, OffsetDateTime.class));
                    return m;
                }).list());
        out.put("sharedAutomations", j.sql("""
                SELECT a.automation_id, a.name, w.workspace_id, w.name, count(DISTINCT g.client_membership_id)
                  FROM public.client_automation_grants g
                  JOIN public.automations a ON a.organization_id = g.organization_id AND a.automation_id = g.automation_id
                  JOIN public.workspaces w ON w.organization_id = g.organization_id AND w.workspace_id = g.workspace_id
                 WHERE g.organization_id = :org AND g.client_id = :c AND g.status = 2
                 GROUP BY a.automation_id, a.name, w.workspace_id, w.name ORDER BY a.name, a.automation_id
                """).param("org", org).param("c", clientId).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("automation", ref("automationId", rs.getObject(1, UUID.class), rs.getString(2)));
                    m.put("workspace", ref("workspaceId", rs.getObject(3, UUID.class), rs.getString(4)));
                    m.put("people", rs.getLong(5));
                    return m;
                }).list());
        return out;
    }

    // ------------------------------------------------------------------ comum

    private static Map<String, Object> person(UUID user, String name, String email) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", user == null ? null : user.toString());
        m.put("name", name);
        m.put("email", email);
        return m;
    }

    private static Map<String, Object> ref(String idField, UUID id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(idField, id.toString());
        m.put("name", name);
        return m;
    }

    private static Map<String, Object> nullableRef(String idField, UUID id, String name) {
        return id == null ? null : ref(idField, id, name);
    }

    private static Map<String, Object> grantRef(String kind, UUID id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("grantId", id.toString());
        return m;
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec q, String name, Object value) {
        return value == null ? q : q.param(name, value);
    }

    /** JSON simples do banco (jsonb::text) para a resposta. */
    static final class Json {
        private static final tools.jackson.databind.json.JsonMapper MAPPER = tools.jackson.databind.json.JsonMapper.builder().build();

        static Object parse(String s) {
            return s == null ? new ArrayList<>() : MAPPER.readValue(s, Object.class);
        }
    }
}
