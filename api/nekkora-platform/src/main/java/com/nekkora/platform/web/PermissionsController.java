package com.nekkora.platform.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;
import com.nekkora.core.web.ApiException;
import com.nekkora.platform.access.WorkspaceAccess;
import com.nekkora.platform.paging.CursorCodec;
import com.nekkora.platform.paging.Paging;
import com.nekkora.platform.session.ValidSession;

/**
 * GAP-02: permissoes do usuario interno autenticado (contrato GAP-02/03/06, ROTA-P01 e ROTA-P02). So apoio a interface:
 * cada rota autoriza no backend por conta propria. Ordem das verificacoes (§1.3): 401 (filtro) -> 400 (parametros e
 * cursor) -> 403 (conta externa, DC-02).
 */
@RestController
public class PermissionsController {

    static final String ROUTE_WORKSPACES = "listMyWorkspacePermissions";
    static final String SORT_WORKSPACES = "workspaceId.asc";

    private final OrgTransactions tx;
    private final WorkspaceAccess access;
    private final CursorCodec cursors;

    public PermissionsController(OrgTransactions tx, WorkspaceAccess access, CursorCodec cursors) {
        this.tx = tx;
        this.access = access;
        this.cursors = cursors;
    }

    /** ROTA-P01: atribuicoes organizacionais e o que vale em todos os workspaces (regra a, DC-01). */
    @GetMapping("/api/v1/o/{org}/me/permissions")
    public Map<String, Object> permissions(@PathVariable UUID org, HttpServletRequest req) {
        ValidSession s = internal(req);
        return tx.inOrganization(org, Mode.READ_ONLY, j -> {
            WorkspaceAccess.OrganizationGrant g = access.organizationGrant(j, org, s.membershipId());
            Map<String, Object> organization = new LinkedHashMap<>();
            organization.put("roles", g.roles());
            organization.put("permissions", g.permissions());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("organizationId", org.toString());
            r.put("membershipId", s.membershipId().toString());
            r.put("organization", organization);
            r.put("allWorkspaces", Map.of("permissions", g.allWorkspacesPermissions()));
            r.put("hasWorkspaceAssignments", access.hasWorkspaceAssignments(j, org, s.membershipId()));
            return r;
        });
    }

    /** ROTA-P02: atribuicoes explicitas por workspace (regra b), paginadas por workspace_id, sem truncar (DC-03). */
    @GetMapping("/api/v1/o/{org}/me/permissions/workspaces")
    public Map<String, Object> workspaces(@PathVariable UUID org, @RequestParam(required = false) String cursor,
                                          @RequestParam(required = false) Integer limit, HttpServletRequest req) {
        ValidSession s = SessionFilter.principal(req);
        int pageSize = Paging.limit(limit);
        CursorCodec.Scope scope = new CursorCodec.Scope(ROUTE_WORKSPACES, org, s.membershipId(), null, CursorCodec.filters(), SORT_WORKSPACES);
        UUID after = cursor == null ? null : cursors.decode(cursor, scope).expectSize(1).uuid(0);
        internal(req);
        List<WorkspaceAccess.WorkspaceAssignment> rows = tx.inOrganization(org, Mode.READ_ONLY,
                j -> access.workspaceAssignments(j, org, s.membershipId(), after, pageSize + 1));
        List<Map<String, Object>> items = new ArrayList<>();
        for (WorkspaceAccess.WorkspaceAssignment a : rows.subList(0, Math.min(pageSize, rows.size()))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("workspaceId", a.workspaceId().toString());
            item.put("roles", a.roles());
            item.put("permissions", a.permissions());
            items.add(item);
        }
        String next = rows.size() > pageSize ? cursors.encode(scope, List.of(rows.get(pageSize - 1).workspaceId())) : null;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", items);
        r.put("nextCursor", next);
        return r;
    }

    /** Conta externa -> 403 nas rotas internas (DC-02, decisao da responsavel em 2026-10-03). */
    private static ValidSession internal(HttpServletRequest req) {
        ValidSession s = SessionFilter.principal(req);
        if (!s.isInternal()) {
            throw ApiException.forbidden("conta_externa");
        }
        return s;
    }
}
