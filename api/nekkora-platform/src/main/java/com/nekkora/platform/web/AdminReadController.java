package com.nekkora.platform.web;

import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;
import com.nekkora.core.web.ApiException;
import com.nekkora.platform.access.PermissionCatalog;
import com.nekkora.platform.access.WorkspaceAccess;
import com.nekkora.platform.paging.CursorCodec;
import com.nekkora.platform.paging.Paging;
import com.nekkora.platform.session.ValidSession;

/**
 * GAP-03: leitura administrativa (contrato GAP-02/03/06, ROTA-M01, M02 e M03; DC-04, DC-05 e DC-06 decididos pela
 * responsavel em 2026-10-03). Permissao ORGANIZACIONAL pelo componente compartilhado (consistencia do catalogo conferida
 * antes). Ordem das verificacoes (§1.3): 401 (filtro) -> 400 (parametros e cursor) -> 403 (conta externa; permissao).
 * Paginacao keyset sobre o estado atual, autorizacao reaplicada a cada pagina (§1.4).
 */
@RestController
public class AdminReadController {

    static final String ROUTE_MEMBERS = "listMembers";
    static final String ROUTE_GRANTS = "listClientGrants";
    static final String ROUTE_CLIENTS = "listClients";
    static final String SORT_CREATED_ASC = "createdAt.asc+id.asc";
    static final String SORT_CREATED_DESC = "createdAt.desc+id.desc";
    static final String SORT_NAME48 = "name48.asc+id.asc";
    /** Tamanho do prefixo de ordenacao por nome (§1.5.1): mesma expressao no ORDER BY e na comparacao da pagina seguinte. */
    static final int NAME_SORT_PREFIX = 48;

    private final OrgTransactions tx;
    private final WorkspaceAccess access;
    private final CursorCodec cursors;

    public AdminReadController(OrgTransactions tx, WorkspaceAccess access, CursorCodec cursors) {
        this.tx = tx;
        this.access = access;
        this.cursors = cursors;
    }

    // ------------------------------------------------------------------ ROTA-M01

    @GetMapping("/api/v1/o/{org}/members")
    public Map<String, Object> members(@PathVariable UUID org, @RequestParam(required = false) List<String> status,
                                       @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
                                       HttpServletRequest req) {
        ValidSession s = SessionFilter.principal(req);
        int pageSize = Paging.limit(limit);
        List<Short> codes = Labels.statusFilter(Labels.Kind.MEMBERSHIP, status, 4);
        CursorCodec.Scope scope = new CursorCodec.Scope(ROUTE_MEMBERS, org, s.membershipId(), null,
                CursorCodec.filters("status", status), SORT_CREATED_ASC);
        CursorCodec.Position after = cursor == null ? null : cursors.decode(cursor, scope).expectSize(2);
        OffsetDateTime afterCreated = after == null ? null : after.instant(0);
        UUID afterId = after == null ? null : after.uuid(1);
        internal(s);
        List<Map<String, Object>> rows = tx.inOrganization(org, Mode.READ_ONLY, j -> {
            access.requireAnyOrganizationPermission(j, org, s.membershipId(), "org.manage");
            StringBuilder sql = new StringBuilder("""
                    SELECT om.membership_id, om.user_id, u.display_name, u.email, u.email_verified_at IS NOT NULL AS email_verified,
                           u.status AS account_status, om.status AS membership_status, om.created_at,
                           EXISTS (SELECT 1 FROM public.user_mfa_factors f
                                    WHERE f.organization_id = om.organization_id AND f.user_id = om.user_id
                                      AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL) AS mfa_configured,
                           ARRAY(SELECT r.code FROM public.organization_role_assignments ra JOIN public.roles r ON r.role_id = ra.role_id
                                  WHERE ra.organization_id = om.organization_id AND ra.membership_id = om.membership_id AND ra.status = 1
                                  ORDER BY r.code) AS roles
                      FROM public.organization_memberships om
                      JOIN public.users u ON u.organization_id = om.organization_id AND u.user_id = om.user_id
                     WHERE om.organization_id = :org
                    """);
            if (codes != null) {
                sql.append(" AND om.status IN (:status)");
            }
            if (after != null) {
                sql.append(" AND (om.created_at, om.membership_id) > (:afterCreated, :afterId)");
            }
            sql.append(" ORDER BY om.created_at, om.membership_id LIMIT :max");
            JdbcClient.StatementSpec q = j.sql(sql.toString()).param("org", org).param("max", pageSize + 1);
            if (codes != null) {
                q = q.param("status", codes);
            }
            if (after != null) {
                q = q.param("afterCreated", afterCreated).param("afterId", afterId);
            }
            return q.query((rs, n) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("membershipId", rs.getObject("membership_id", UUID.class).toString());
                m.put("userId", rs.getObject("user_id", UUID.class).toString());
                m.put("displayName", rs.getString("display_name"));
                m.put("email", rs.getString("email"));
                m.put("emailVerified", rs.getBoolean("email_verified"));
                m.put("accountStatus", Labels.label(Labels.Kind.ACCOUNT, rs.getInt("account_status")));
                m.put("membershipStatus", Labels.label(Labels.Kind.MEMBERSHIP, rs.getInt("membership_status")));
                m.put("mfaConfigured", rs.getBoolean("mfa_configured"));
                List<String> roles = new ArrayList<>();
                for (String r : strings(rs.getArray("roles"))) {
                    roles.add(WorkspaceAccess.validatedRole(r, PermissionCatalog.Scope.ORGANIZATION));
                }
                m.put("organizationRoles", roles);
                m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
                return m;
            }).list();
        });
        return page(rows, pageSize, last -> cursors.encode(scope, List.of(last.get("createdAt"), last.get("membershipId"))));
    }

    // ------------------------------------------------------------------ ROTA-M02

    /**
     * DC-05: clientId/workspaceId sao filtros de rota organizacional; inexistente ou de outra organizacao resulta em lista
     * vazia pela propria consulta (RLS por organizacao + filtro), sem consulta de existencia e sem 404.
     */
    @GetMapping("/api/v1/o/{org}/client-grants")
    public Map<String, Object> clientGrants(@PathVariable UUID org, @RequestParam(required = false) List<String> status,
                                            @RequestParam(required = false) UUID clientId, @RequestParam(required = false) UUID workspaceId,
                                            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
                                            HttpServletRequest req) {
        ValidSession s = SessionFilter.principal(req);
        int pageSize = Paging.limit(limit);
        List<Short> codes = Labels.statusFilter(Labels.Kind.GRANT, status, 4);
        CursorCodec.Scope scope = new CursorCodec.Scope(ROUTE_GRANTS, org, s.membershipId(), null,
                CursorCodec.filters("status", status, "clientId", clientId, "workspaceId", workspaceId), SORT_CREATED_DESC);
        CursorCodec.Position after = cursor == null ? null : cursors.decode(cursor, scope).expectSize(2);
        OffsetDateTime afterCreated = after == null ? null : after.instant(0);
        UUID afterId = after == null ? null : after.uuid(1);
        internal(s);
        List<Map<String, Object>> rows = tx.inOrganization(org, Mode.READ_ONLY, j -> {
            access.requireAnyOrganizationPermission(j, org, s.membershipId(), "external_access.manage");
            StringBuilder sql = new StringBuilder("""
                    SELECT g.grant_id, g.status, g.client_id, c.name AS client_name, g.workspace_id, w.name AS workspace_name,
                           g.client_membership_id, cm.user_id, u.display_name, u.email, cm.status AS membership_status,
                           g.approved_at, g.starts_at, g.expires_at, g.next_review_at, g.last_access_at, g.revoked_at, g.created_at
                      FROM public.client_workspace_grants g
                      JOIN public.clients c ON c.organization_id = g.organization_id AND c.client_id = g.client_id
                      JOIN public.workspaces w ON w.organization_id = g.organization_id AND w.workspace_id = g.workspace_id
                      JOIN public.client_memberships cm ON cm.organization_id = g.organization_id
                                                       AND cm.client_membership_id = g.client_membership_id
                      JOIN public.users u ON u.organization_id = cm.organization_id AND u.user_id = cm.user_id
                     WHERE g.organization_id = :org
                    """);
            if (codes != null) {
                sql.append(" AND g.status IN (:status)");
            }
            if (clientId != null) {
                sql.append(" AND g.client_id = :clientId");
            }
            if (workspaceId != null) {
                sql.append(" AND g.workspace_id = :workspaceId");
            }
            if (after != null) {
                sql.append(" AND (g.created_at, g.grant_id) < (:afterCreated, :afterId)");
            }
            sql.append(" ORDER BY g.created_at DESC, g.grant_id DESC LIMIT :max");
            JdbcClient.StatementSpec q = j.sql(sql.toString()).param("org", org).param("max", pageSize + 1);
            if (codes != null) {
                q = q.param("status", codes);
            }
            if (clientId != null) {
                q = q.param("clientId", clientId);
            }
            if (workspaceId != null) {
                q = q.param("workspaceId", workspaceId);
            }
            if (after != null) {
                q = q.param("afterCreated", afterCreated).param("afterId", afterId);
            }
            return q.query((rs, n) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("grantId", rs.getObject("grant_id", UUID.class).toString());
                m.put("status", Labels.label(Labels.Kind.GRANT, rs.getInt("status")));
                m.put("client", ref("clientId", rs.getObject("client_id", UUID.class), rs.getString("client_name")));
                m.put("workspace", ref("workspaceId", rs.getObject("workspace_id", UUID.class), rs.getString("workspace_name")));
                Map<String, Object> ext = new LinkedHashMap<>();
                ext.put("clientMembershipId", rs.getObject("client_membership_id", UUID.class).toString());
                ext.put("userId", rs.getObject("user_id", UUID.class).toString());
                ext.put("displayName", rs.getString("display_name"));
                ext.put("email", rs.getString("email"));
                ext.put("membershipStatus", Labels.label(Labels.Kind.MEMBERSHIP, rs.getInt("membership_status")));
                m.put("externalAccount", ext);
                for (String[] c : new String[][] {{"approvedAt", "approved_at"}, {"startsAt", "starts_at"}, {"expiresAt", "expires_at"},
                        {"nextReviewAt", "next_review_at"}, {"lastAccessAt", "last_access_at"}, {"revokedAt", "revoked_at"},
                        {"createdAt", "created_at"}}) {
                    m.put(c[0], rs.getObject(c[1], OffsetDateTime.class));
                }
                return m;
            }).list();
        });
        return page(rows, pageSize, last -> cursors.encode(scope, List.of(last.get("createdAt"), last.get("grantId"))));
    }

    // ------------------------------------------------------------------ ROTA-M03

    @GetMapping("/api/v1/o/{org}/clients")
    public Map<String, Object> clients(@PathVariable UUID org, @RequestParam(required = false) List<String> status,
                                       @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
                                       HttpServletRequest req) {
        ValidSession s = SessionFilter.principal(req);
        int pageSize = Paging.limit(limit);
        List<Short> codes = Labels.statusFilter(Labels.Kind.RESOURCE, status, 3);
        CursorCodec.Scope scope = new CursorCodec.Scope(ROUTE_CLIENTS, org, s.membershipId(), null,
                CursorCodec.filters("status", status), SORT_NAME48);
        CursorCodec.Position after = cursor == null ? null : cursors.decode(cursor, scope).expectSize(2);
        String afterKey = after == null ? null : after.text(0);
        UUID afterId = after == null ? null : after.uuid(1);
        internal(s);
        List<Map<String, Object>> rows = tx.inOrganization(org, Mode.READ_ONLY, j -> {
            access.requireAnyOrganizationPermission(j, org, s.membershipId(), "org.manage", "external_access.manage");
            StringBuilder sql = new StringBuilder("""
                    SELECT c.client_id, c.name, left(c.name, :prefix) AS sort_key, c.status, c.created_at, c.updated_at
                      FROM public.clients c
                     WHERE c.organization_id = :org
                    """);
            if (codes != null) {
                sql.append(" AND c.status IN (:status)");
            }
            if (after != null) {
                // mesma expressao do ORDER BY (§1.5.1): o cursor guarda exatamente a chave de ordenacao limitada
                sql.append(" AND (left(c.name, :prefix) COLLATE \"C\", c.client_id) > (CAST(:afterKey AS text) COLLATE \"C\", :afterId)");
            }
            sql.append(" ORDER BY left(c.name, :prefix) COLLATE \"C\", c.client_id LIMIT :max");
            JdbcClient.StatementSpec q = j.sql(sql.toString()).param("org", org).param("prefix", NAME_SORT_PREFIX).param("max", pageSize + 1);
            if (codes != null) {
                q = q.param("status", codes);
            }
            if (after != null) {
                q = q.param("afterKey", afterKey).param("afterId", afterId);
            }
            return q.query((rs, n) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("clientId", rs.getObject("client_id", UUID.class).toString());
                m.put("name", rs.getString("name"));
                m.put("status", Labels.label(Labels.Kind.RESOURCE, rs.getInt("status")));
                m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
                m.put("updatedAt", rs.getObject("updated_at", OffsetDateTime.class));
                m.put(SORT_KEY, rs.getString("sort_key"));
                return m;
            }).list();
        });
        Map<String, Object> r = page(rows, pageSize, last -> cursors.encode(scope, List.of(last.get(SORT_KEY), last.get("clientId"))));
        rows.forEach(m -> m.remove(SORT_KEY));
        return r;
    }

    // ------------------------------------------------------------------ comum

    /** Chave interna de ordenacao (prefixo do nome como o banco devolveu); nunca sai na resposta. */
    private static final String SORT_KEY = "\u0000sortKey";

    /** Conta externa -> 403 nas rotas internas (DC-02). */
    private static void internal(ValidSession s) {
        if (!s.isInternal()) {
            throw ApiException.forbidden("conta_externa");
        }
    }

    private static Map<String, Object> ref(String idField, UUID id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(idField, id.toString());
        m.put("name", name);
        return m;
    }

    private static List<String> strings(Array a) throws SQLException {
        List<String> out = new ArrayList<>();
        if (a != null) {
            for (Object o : (Object[]) a.getArray()) {
                out.add((String) o);
            }
        }
        return out;
    }

    /** Le {@code limit + 1}: nextCursor so se havia mais itens no momento da consulta (§1.4). */
    private static Map<String, Object> page(List<Map<String, Object>> rows, int pageSize,
                                            java.util.function.Function<Map<String, Object>, String> cursorOf) {
        String next = rows.size() > pageSize ? cursorOf.apply(rows.get(pageSize - 1)) : null;
        List<Map<String, Object>> items = new ArrayList<>(rows.subList(0, Math.min(pageSize, rows.size())));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", items);
        r.put("nextCursor", next);
        return r;
    }
}
