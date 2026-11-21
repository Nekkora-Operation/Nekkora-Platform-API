package com.nekkora.platform.web;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
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
 * PLATFORM-FLUXO-002 — "Pessoas e acessos": membros e convites de conta interna numa lista so, para a mesma permissao das
 * duas listas anteriores (org.manage). So leitura: emitir, reenviar, revogar e os acessos continuam nas rotas de sempre.
 *
 * Ordem (vale para a lista inteira, com cursor): convites PENDENTES primeiro, do mais recente ao mais antigo (criacao);
 * depois os membros, da entrada mais recente a mais antiga; por ultimo os convites encerrados (vencidos, revogados e
 * aceitos), so quando o filtro os pede. Sem filtro de situacao: pendentes e todos os membros — o convite aceito NAO aparece
 * ao lado do membro que ele criou (o mesmo ingresso nao se repete); o historico continua consultavel pelo filtro.
 *
 * Busca por nome ou e-mail (contem, sem diferenciar maiusculas). Convites so entram com a funcionalidade de convites ligada.
 */
@RestController
public class PeopleController {

    static final String ROUTE = "listPeople";
    static final String SORT = "group.asc+at.desc+id.desc";
    /** Situacoes do filtro: convite (pelo estado calculado) e membro (pelo vinculo). */
    static final List<String> SITUATIONS = List.of("invitation_pending", "invitation_expired", "invitation_revoked", "invitation_accepted",
            "member_pending", "member_active", "member_suspended", "member_revoked");
    static final Set<String> DEFAULT_SITUATIONS = new LinkedHashSet<>(List.of("invitation_pending", "member_pending", "member_active",
            "member_suspended", "member_revoked"));

    private final OrgTransactions tx;
    private final WorkspaceAccess access;
    private final CursorCodec cursors;
    private final boolean invitationsEnabled;

    public PeopleController(OrgTransactions tx, WorkspaceAccess access, CursorCodec cursors,
                            @Value("${nekkora.entrada-geral.enabled:false}") boolean entryEnabled,
                            @Value("${nekkora.convites.enabled:false}") boolean invitationsEnabled) {
        this.tx = tx;
        this.access = access;
        this.cursors = cursors;
        this.invitationsEnabled = entryEnabled && invitationsEnabled;
    }

    /** Uma linha: o grupo da ordem, o instante e o id do desempate, e a situacao (filtro). */
    private static final String UNIFIED = """
            SELECT 'invitation' AS kind, i.invitation_id AS id,
                   CASE WHEN i.accepted_at IS NOT NULL THEN 'invitation_accepted' WHEN i.revoked_at IS NOT NULL THEN 'invitation_revoked'
                        WHEN i.expires_at <= clock_timestamp() THEN 'invitation_expired' ELSE 'invitation_pending' END AS situation,
                   i.created_at AS at, i.email_normalized AS email, NULL::text AS display_name
              FROM public.identity_invitations i
             WHERE i.organization_id = :org AND i.invited_account_type = 1 AND :invitations
            UNION ALL
            SELECT 'member', om.membership_id,
                   CASE om.status WHEN 1 THEN 'member_pending' WHEN 2 THEN 'member_active' WHEN 3 THEN 'member_suspended' ELSE 'member_revoked' END,
                   om.created_at, u.email, u.display_name
              FROM public.organization_memberships om
              JOIN public.users u ON u.organization_id = om.organization_id AND u.user_id = om.user_id
             WHERE om.organization_id = :org
            """;

    @GetMapping("/api/v1/o/{org}/people")
    public Map<String, Object> people(@PathVariable UUID org, @RequestParam(required = false) List<String> situation,
                                      @RequestParam(required = false) String q, @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit, HttpServletRequest req) {
        ValidSession s = SessionFilter.principal(req);
        int pageSize = Paging.limit(limit);
        Set<String> situations = situations(situation);
        String search = q == null ? null : q.strip();
        if (search != null && (search.isEmpty() || search.length() > 120)) {
            throw ApiException.invalid("q");
        }
        CursorCodec.Scope scope = new CursorCodec.Scope(ROUTE, org, s.membershipId(), null,
                CursorCodec.filters("situation", situation == null ? null : String.join(",", situations), "q", search), SORT);
        CursorCodec.Position after = cursor == null ? null : cursors.decode(cursor, scope).expectSize(3);
        if (!s.isInternal()) {
            throw ApiException.forbidden("conta_externa");
        }
        List<Map<String, Object>> rows = tx.inOrganization(org, Mode.READ_ONLY, j -> {
            access.requireAnyOrganizationPermission(j, org, s.membershipId(), "org.manage");
            StringBuilder sql = new StringBuilder("SELECT u.* FROM (SELECT x.*, CASE WHEN x.situation = 'invitation_pending' THEN 0 "
                    + "WHEN x.kind = 'member' THEN 1 ELSE 2 END AS grp FROM (" + UNIFIED + ") x) u WHERE u.situation IN (:situations)");
            if (search != null) {
                sql.append(" AND (lower(u.email) LIKE :q ESCAPE '\\' OR lower(coalesce(u.display_name, '')) LIKE :q ESCAPE '\\')");
            }
            if (after != null) {
                // grupo crescente; dentro dele, instante e id decrescentes
                sql.append(" AND (u.grp > :afterGroup OR (u.grp = :afterGroup AND (u.at, u.id) < (:afterAt, :afterId)))");
            }
            sql.append(" ORDER BY u.grp, u.at DESC, u.id DESC LIMIT :max");
            JdbcClient.StatementSpec st = j.sql(sql.toString()).param("org", org).param("invitations", invitationsEnabled)
                    .param("situations", List.copyOf(situations)).param("max", pageSize + 1);
            if (search != null) {
                st = st.param("q", "%" + search.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            }
            if (after != null) {
                st = st.param("afterGroup", (int) after.integer(0)).param("afterAt", after.instant(1)).param("afterId", after.uuid(2));
            }
            List<Map<String, Object>> keys = st.query((rs, n) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("kind", rs.getString("kind"));
                m.put("id", rs.getObject("id", UUID.class));
                m.put("grp", rs.getInt("grp"));
                m.put("at", rs.getObject("at", OffsetDateTime.class));
                return m;
            }).list();
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> k : keys) {
                Map<String, Object> item = "member".equals(k.get("kind")) ? member(j, org, (UUID) k.get("id")) : invitation(j, org, (UUID) k.get("id"));
                item.put("\u0000grp", k.get("grp"));
                items.add(item);
            }
            return items;
        });
        String next = rows.size() > pageSize ? cursorOf(scope, rows.get(pageSize - 1)) : null;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows.subList(0, Math.min(pageSize, rows.size()))) {
            r.remove("\u0000grp");
            items.add(r);
        }
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", items);
        page.put("nextCursor", next);
        page.put("invitationsEnabled", invitationsEnabled);
        return page;
    }

    private String cursorOf(CursorCodec.Scope scope, Map<String, Object> last) {
        Object at = "member".equals(last.get("kind")) ? ((Map<?, ?>) last.get("member")).get("joinedAt") : ((Map<?, ?>) last.get("invitation")).get("createdAt");
        return cursors.encode(scope, List.of(((Number) last.get("\u0000grp")).longValue(), at, last.get("id")));
    }

    private static Set<String> situations(List<String> values) {
        if (values == null) {
            return DEFAULT_SITUATIONS;
        }
        if (values.isEmpty() || values.size() > SITUATIONS.size()) {
            throw ApiException.invalid("situation");
        }
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (!SITUATIONS.contains(v) || !out.add(v)) {
                throw ApiException.invalid("situation");
            }
        }
        return out;
    }

    private static Map<String, Object> member(JdbcClient j, UUID org, UUID membership) {
        return j.sql("""
                SELECT om.membership_id, om.user_id, u.display_name, u.email, u.email_verified_at IS NOT NULL AS email_verified,
                       u.status AS account_status, om.status AS membership_status, om.created_at,
                       EXISTS (SELECT 1 FROM public.user_mfa_factors f
                                WHERE f.organization_id = om.organization_id AND f.user_id = om.user_id
                                  AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL) AS mfa_configured
                  FROM public.organization_memberships om
                  JOIN public.users u ON u.organization_id = om.organization_id AND u.user_id = om.user_id
                 WHERE om.organization_id = :org AND om.membership_id = :m
                """).param("org", org).param("m", membership).query((rs, n) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("kind", "member");
                    item.put("id", membership.toString());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("membershipId", membership.toString());
                    m.put("userId", rs.getObject("user_id", UUID.class).toString());
                    m.put("displayName", rs.getString("display_name"));
                    m.put("email", rs.getString("email"));
                    m.put("emailVerified", rs.getBoolean("email_verified"));
                    m.put("accountStatus", Labels.label(Labels.Kind.ACCOUNT, rs.getInt("account_status")));
                    m.put("membershipStatus", Labels.label(Labels.Kind.MEMBERSHIP, rs.getInt("membership_status")));
                    m.put("mfaConfigured", rs.getBoolean("mfa_configured"));
                    m.put("joinedAt", rs.getObject("created_at", OffsetDateTime.class));
                    m.put("roles", roles(j, org, membership));
                    item.put("member", m);
                    item.put("invitation", null);
                    return item;
                }).single();
    }

    /** Papeis ATIVOS do membro: os da organizacao e os de ambiente (com o ambiente), pelo codigo. */
    private static List<Map<String, Object>> roles(JdbcClient j, UUID org, UUID membership) {
        return j.sql("""
                SELECT r.code, NULL::uuid AS workspace_id, NULL::text AS workspace_name, 1 AS scope
                  FROM public.organization_role_assignments ra JOIN public.roles r ON r.role_id = ra.role_id
                 WHERE ra.organization_id = :org AND ra.membership_id = :m AND ra.status = 1
                UNION ALL
                SELECT r.code, wa.workspace_id, w.name, 2
                  FROM public.workspace_role_assignments wa JOIN public.roles r ON r.role_id = wa.role_id
                  JOIN public.workspaces w ON w.organization_id = wa.organization_id AND w.workspace_id = wa.workspace_id
                 WHERE wa.organization_id = :org AND wa.membership_id = :m AND wa.status = 1
                 ORDER BY 4, 1, 3
                """).param("org", org).param("m", membership).query((rs, n) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    PermissionCatalog.Scope sc = rs.getInt("scope") == 1 ? PermissionCatalog.Scope.ORGANIZATION : PermissionCatalog.Scope.WORKSPACE;
                    r.put("role", WorkspaceAccess.validatedRole(rs.getString("code"), sc));
                    UUID w = rs.getObject("workspace_id", UUID.class);
                    r.put("workspaceId", w == null ? null : w.toString());
                    r.put("workspaceName", rs.getString("workspace_name"));
                    return r;
                }).list();
    }

    private static Map<String, Object> invitation(JdbcClient j, UUID org, UUID invitation) {
        return j.sql("""
                SELECT i.invitation_id, i.email_normalized, r.code, i.workspace_id, w.name AS workspace_name, i.created_at, i.expires_at,
                       i.accepted_at, i.revoked_at, i.revoke_reason, i.accepted_user_id,
                       CASE WHEN i.accepted_at IS NOT NULL THEN 'ACEITO' WHEN i.revoked_at IS NOT NULL THEN 'REVOGADO'
                            WHEN i.expires_at <= clock_timestamp() THEN 'EXPIRADO' ELSE 'PENDENTE' END AS state
                  FROM public.identity_invitations i
                  LEFT JOIN public.roles r ON r.role_id = i.role_id
                  LEFT JOIN public.workspaces w ON w.organization_id = i.organization_id AND w.workspace_id = i.workspace_id
                 WHERE i.organization_id = :org AND i.invitation_id = :i
                """).param("org", org).param("i", invitation).query((rs, n) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("kind", "invitation");
                    item.put("id", invitation.toString());
                    item.put("member", null);
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("invitationId", invitation.toString());
                    v.put("email", rs.getString("email_normalized"));
                    v.put("role", rs.getString("code"));
                    UUID w = rs.getObject("workspace_id", UUID.class);
                    v.put("workspaceId", w == null ? null : w.toString());
                    v.put("workspaceName", rs.getString("workspace_name"));
                    v.put("state", rs.getString("state"));
                    v.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
                    v.put("expiresAt", rs.getObject("expires_at", OffsetDateTime.class));
                    v.put("acceptedAt", rs.getObject("accepted_at", OffsetDateTime.class));
                    v.put("revokedAt", rs.getObject("revoked_at", OffsetDateTime.class));
                    v.put("revokeReason", reason(rs.getObject("revoke_reason", Short.class)));
                    UUID accepted = rs.getObject("accepted_user_id", UUID.class);
                    v.put("acceptedUserId", accepted == null ? null : accepted.toString());
                    item.put("invitation", v);
                    return item;
                }).single();
    }

    /** O mesmo rotulo da listagem de convites (InvitationService). */
    private static String reason(Short code) throws SQLException {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case 1 -> "REVOGADO";
            case 2 -> "REENVIADO";
            case 3 -> "EXPIRADO";
            case 4 -> "EMISSOR_SEM_AUTORIDADE";
            default -> throw new SQLException("revoke_reason desconhecido: " + code);
        };
    }
}
