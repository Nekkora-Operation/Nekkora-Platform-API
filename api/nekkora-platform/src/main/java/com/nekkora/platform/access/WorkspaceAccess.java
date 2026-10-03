package com.nekkora.platform.access;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;

/**
 * Regra UNICA de acesso efetivo por workspace (contrato GAP-02/03/06, §1.2), usada por todas as rotas internas; nenhum
 * controlador escreve SQL de autorizacao proprio. O vinculo M tem a permissao de workspace P em W se (a) M tem
 * atribuicao ORGANIZACIONAL ativa cujo papel inclui P (ORG_ADMIN vale para todos os workspaces: DC-01, decisao da
 * responsavel em 2026-10-03) ou (b) M tem atribuicao DE WORKSPACE ativa em W cujo papel inclui P. Vinculo e conta
 * precisam estar ativos. Le sempre o estado atual, na transacao da consulta (sem cache entre requisicoes).
 * <p>Consistencia do catalogo em TODOS os caminhos (listagem, decisao pontual, requireWorkspace, papeis organizacionais,
 * atribuicoes explicitas): antes de decidir, {@link #requireConsistent} confere todas as atribuicoes ativas do vinculo.
 * Papel fora do catalogo ou no escopo errado, permissao fora do catalogo, ou permissao organizacional em papel de
 * workspace -> 500 com log {@code evento=api.catalogo_desconhecido}; nunca omitido e nunca usado para conceder acesso.
 */
public final class WorkspaceAccess {

    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceAccess.class);

    /**
     * Condicao de visibilidade sobre {@code public.workspaces w}, com parametros nomeados {@code access_org},
     * {@code access_membership} e {@code access_permission}. Mesma regra em toda rota. Os caminhos implementados a
     * executam por {@link #visibleWorkspacesQuery}, que confere a consistencia do catalogo antes; a constante e publica
     * e NAO impede uso direto, entao rotas novas devem usar so os metodos deste componente (parecer da C1 da GAP-02).
     */
    public static final String VISIBLE_WORKSPACE_CONDITION = """
            w.organization_id = :access_org
            AND EXISTS (SELECT 1 FROM public.organization_memberships om
                          JOIN public.users u ON u.organization_id = om.organization_id AND u.user_id = om.user_id
                         WHERE om.organization_id = :access_org AND om.membership_id = :access_membership
                           AND om.status = 2 AND u.status = 2)
            AND (EXISTS (SELECT 1 FROM public.organization_role_assignments ra
                           JOIN public.role_permissions rp ON rp.role_id = ra.role_id
                           JOIN public.permissions p ON p.permission_id = rp.permission_id
                          WHERE ra.organization_id = :access_org AND ra.membership_id = :access_membership
                            AND ra.status = 1 AND p.code = :access_permission)
              OR EXISTS (SELECT 1 FROM public.workspace_role_assignments wa
                           JOIN public.role_permissions rp ON rp.role_id = wa.role_id
                           JOIN public.permissions p ON p.permission_id = rp.permission_id
                          WHERE wa.organization_id = :access_org AND wa.workspace_id = w.workspace_id
                            AND wa.membership_id = :access_membership AND wa.status = 1 AND p.code = :access_permission))
            """;

    /** Papeis e permissoes das atribuicoes organizacionais ativas (listas ordenadas, sem repeticao). */
    public record OrganizationGrant(List<String> roles, List<String> permissions) {

        /** Permissoes de workspace que valem em TODOS os workspaces pela regra (a). */
        public List<String> allWorkspacesPermissions() {
            return permissions.stream().filter(PermissionCatalog.permissions(PermissionCatalog.Scope.WORKSPACE)::contains).toList();
        }
    }

    /** Fonte das permissoes ORGANIZACIONAIS disponiveis ao vinculo (substituivel so em teste do componente). */
    @FunctionalInterface
    interface OrganizationPermissionSource {
        Set<String> load(JdbcClient j, UUID org, UUID membership);
    }

    private final OrganizationPermissionSource organizationPermissions;

    public WorkspaceAccess() {
        this.organizationPermissions = (j, org, membership) -> Set.copyOf(organizationGrant(j, org, membership).permissions());
    }

    /** So para o teste do componente: fornece conjuntos controlados mantendo a logica de decisao real. */
    WorkspaceAccess(OrganizationPermissionSource source) {
        this.organizationPermissions = source;
    }

    /** Atribuicoes explicitas (regra b) de um workspace. */
    public record WorkspaceAssignment(UUID workspaceId, List<String> roles, List<String> permissions) {
    }

    /**
     * Unica porta da condicao de visibilidade: confere a consistencia do catalogo para o vinculo (500 se inconsistente) e
     * devolve a consulta com os parametros {@code access_*} ligados. {@code sql} precisa conter
     * {@link #VISIBLE_WORKSPACE_CONDITION} sobre {@code public.workspaces w}.
     */
    public JdbcClient.StatementSpec visibleWorkspacesQuery(JdbcClient j, String sql, UUID org, UUID membership, String permission) {
        requireWorkspacePermission(permission);
        if (!sql.contains(VISIBLE_WORKSPACE_CONDITION)) {
            throw new IllegalStateException("consulta sem a condicao de visibilidade (erro de programacao)");
        }
        requireConsistent(j, org, membership);
        return j.sql(sql).param("access_org", org).param("access_membership", membership).param("access_permission", permission);
    }

    /** Regra (a) ou (b) para um workspace da organizacao. Workspace inexistente ou de outra organizacao -> false. */
    public boolean hasWorkspacePermission(JdbcClient j, UUID org, UUID membership, UUID workspace, String permission) {
        return visibleWorkspacesQuery(j, "SELECT EXISTS (SELECT 1 FROM public.workspaces w WHERE w.workspace_id = :access_workspace AND "
                + VISIBLE_WORKSPACE_CONDITION + ")", org, membership, permission)
                .param("access_workspace", workspace).query(Boolean.class).single();
    }

    /**
     * Consistencia do catalogo para TODAS as atribuicoes ativas (organizacionais e de workspace) do vinculo ativo: papel
     * conhecido no escopo da atribuicao; permissoes conhecidas; papel de workspace so com permissoes de workspace.
     * Qualquer divergencia -> 500 com log (nunca omitida, nunca usada para conceder acesso).
     */
    public void requireConsistent(JdbcClient j, UUID org, UUID membership) {
        j.sql("""
                SELECT 1 AS assignment_scope, r.code AS role_code, p.code AS permission_code
                  FROM public.organization_role_assignments ra
                  JOIN public.organization_memberships om ON om.organization_id = ra.organization_id AND om.membership_id = ra.membership_id
                  JOIN public.users u ON u.organization_id = om.organization_id AND u.user_id = om.user_id
                  JOIN public.roles r ON r.role_id = ra.role_id
                  LEFT JOIN public.role_permissions rp ON rp.role_id = ra.role_id
                  LEFT JOIN public.permissions p ON p.permission_id = rp.permission_id
                 WHERE ra.organization_id = :org AND ra.membership_id = :membership AND ra.status = 1 AND om.status = 2 AND u.status = 2
                UNION ALL
                SELECT 2, r.code, p.code
                  FROM public.workspace_role_assignments wa
                  JOIN public.organization_memberships om ON om.organization_id = wa.organization_id AND om.membership_id = wa.membership_id
                  JOIN public.users u ON u.organization_id = om.organization_id AND u.user_id = om.user_id
                  JOIN public.roles r ON r.role_id = wa.role_id
                  LEFT JOIN public.role_permissions rp ON rp.role_id = wa.role_id
                  LEFT JOIN public.permissions p ON p.permission_id = rp.permission_id
                 WHERE wa.organization_id = :org AND wa.membership_id = :membership AND wa.status = 1 AND om.status = 2 AND u.status = 2
                """).param("org", org).param("membership", membership).query((rs, n) -> {
                    PermissionCatalog.Scope scope = rs.getInt("assignment_scope") == 1 ? PermissionCatalog.Scope.ORGANIZATION
                            : PermissionCatalog.Scope.WORKSPACE;
                    role(rs.getString("role_code"), scope);
                    String p = rs.getString("permission_code");
                    if (p != null) {
                        permission(p, scope == PermissionCatalog.Scope.WORKSPACE ? PermissionCatalog.Scope.WORKSPACE : null);
                    }
                    return null;
                }).list();
    }

    /**
     * Permissao ORGANIZACIONAL do vinculo (atribuicoes organizacionais ativas), com a consistencia do catalogo conferida
     * antes (500 com log se inconsistente). {@code permission} precisa ser de escopo organizacao (erro de programacao
     * se nao for).
     */
    public boolean hasOrganizationPermission(JdbcClient j, UUID org, UUID membership, String permission) {
        requireOrganizationPermissionCodes(permission);
        return organizationPermissions.load(j, org, membership).contains(permission);
    }

    /** Exige AO MENOS UMA das permissoes organizacionais ({@code required}); sem nenhuma -> 403. */
    public void requireAnyOrganizationPermission(JdbcClient j, UUID org, UUID membership, String... required) {
        requireOrganizationPermissionCodes(required);
        Set<String> available = organizationPermissions.load(j, org, membership);
        for (String p : required) {
            if (available.contains(p)) {
                return;
            }
        }
        throw ApiException.forbidden("permissao_organizacional");
    }

    private static void requireOrganizationPermissionCodes(String... codes) {
        if (codes == null || codes.length == 0) {
            throw new IllegalStateException("nenhuma permissao exigida (erro de programacao)");
        }
        for (String c : codes) {
            if (PermissionCatalog.permissionScope(c).orElse(null) != PermissionCatalog.Scope.ORGANIZATION) {
                throw new IllegalStateException("permissao organizacional esperada (erro de programacao)");
            }
        }
    }

    /** Codigo de papel validado no catalogo para o escopo (fora do catalogo -> 500 com log). */
    public static String validatedRole(String code, PermissionCatalog.Scope scope) {
        return role(code, scope);
    }

    /** Execucao resolvida na organizacao da rota: workspace e automacao (para os filtros e o detalhe). */
    public record ExecutionScope(UUID workspaceId, UUID automationId) {
    }

    /**
     * GAP-06: automacao da ORGANIZACAO DA ROTA + regra por workspace. Inexistente, de outra organizacao ou sem acesso ->
     * o mesmo 404 de {@link #requireWorkspace}; a consistencia do catalogo e conferida em todos os casos (500 com log).
     * Devolve o workspace da automacao.
     */
    public UUID requireAutomation(JdbcClient j, UUID org, UUID membership, UUID automation, String permission) {
        UUID workspace = j.sql("SELECT workspace_id FROM public.automations WHERE organization_id = :org AND automation_id = :automation")
                .param("org", org).param("automation", automation).query(UUID.class).optional().orElse(null);
        if (workspace == null) {
            requireWorkspacePermission(permission);
            requireConsistent(j, org, membership);
            throw new ApiException(ApiError.NOT_FOUND, "automacao");
        }
        requireWorkspace(j, org, membership, workspace, permission);
        return workspace;
    }

    /** GAP-06: execucao da ORGANIZACAO DA ROTA + regra por workspace, com o mesmo 404 e a mesma consistencia. */
    public ExecutionScope requireExecution(JdbcClient j, UUID org, UUID membership, UUID execution, String permission) {
        ExecutionScope scope = j.sql("SELECT workspace_id, automation_id FROM public.executions WHERE organization_id = :org AND execution_id = :execution")
                .param("org", org).param("execution", execution)
                .query((rs, n) -> new ExecutionScope(rs.getObject("workspace_id", UUID.class), rs.getObject("automation_id", UUID.class)))
                .optional().orElse(null);
        if (scope == null) {
            requireWorkspacePermission(permission);
            requireConsistent(j, org, membership);
            throw new ApiException(ApiError.NOT_FOUND, "execucao");
        }
        requireWorkspace(j, org, membership, scope.workspaceId(), permission);
        return scope;
    }

    /** Sem a permissao: 404 identico para inexistente, de outra organizacao e sem acesso (§1.3, linha 5). */
    public void requireWorkspace(JdbcClient j, UUID org, UUID membership, UUID workspace, String permission) {
        if (!hasWorkspacePermission(j, org, membership, workspace, permission)) {
            throw new ApiException(ApiError.NOT_FOUND, "workspace");
        }
    }

    public OrganizationGrant organizationGrant(JdbcClient j, UUID org, UUID membership) {
        requireConsistent(j, org, membership);
        Set<String> roles = new TreeSet<>();
        Set<String> permissions = new TreeSet<>();
        j.sql("""
                SELECT r.code AS role_code, p.code AS permission_code
                  FROM public.organization_role_assignments ra
                  JOIN public.organization_memberships om ON om.organization_id = ra.organization_id AND om.membership_id = ra.membership_id
                  JOIN public.users u ON u.organization_id = om.organization_id AND u.user_id = om.user_id
                  JOIN public.roles r ON r.role_id = ra.role_id
                  LEFT JOIN public.role_permissions rp ON rp.role_id = ra.role_id
                  LEFT JOIN public.permissions p ON p.permission_id = rp.permission_id
                 WHERE ra.organization_id = :org AND ra.membership_id = :membership AND ra.status = 1 AND om.status = 2 AND u.status = 2
                """).param("org", org).param("membership", membership).query((rs, n) -> {
                    roles.add(role(rs.getString("role_code"), PermissionCatalog.Scope.ORGANIZATION));
                    String p = rs.getString("permission_code");
                    if (p != null) {
                        permissions.add(permission(p, null));
                    }
                    return null;
                }).list();
        return new OrganizationGrant(List.copyOf(roles), List.copyOf(permissions));
    }

    public boolean hasWorkspaceAssignments(JdbcClient j, UUID org, UUID membership) {
        requireConsistent(j, org, membership);
        return j.sql("""
                SELECT EXISTS (SELECT 1 FROM public.workspace_role_assignments wa
                                 JOIN public.organization_memberships om ON om.organization_id = wa.organization_id AND om.membership_id = wa.membership_id
                                 JOIN public.users u ON u.organization_id = om.organization_id AND u.user_id = om.user_id
                                WHERE wa.organization_id = :org AND wa.membership_id = :membership AND wa.status = 1 AND om.status = 2 AND u.status = 2)
                """).param("org", org).param("membership", membership).query(Boolean.class).single();
    }

    /**
     * Ate {@code max} workspaces com atribuicao explicita ativa, em ordem de workspace_id (ordem do PostgreSQL),
     * depois de {@code after} (exclusivo; nulo = desde o inicio).
     */
    public List<WorkspaceAssignment> workspaceAssignments(JdbcClient j, UUID org, UUID membership, UUID after, int max) {
        requireConsistent(j, org, membership);
        String base = """
                FROM public.workspace_role_assignments wa
                JOIN public.organization_memberships om ON om.organization_id = wa.organization_id AND om.membership_id = wa.membership_id
                JOIN public.users u ON u.organization_id = om.organization_id AND u.user_id = om.user_id
               WHERE wa.organization_id = :org AND wa.membership_id = :membership AND wa.status = 1 AND om.status = 2 AND u.status = 2
                """;
        String page = "SELECT DISTINCT wa.workspace_id " + base + (after == null ? "" : " AND wa.workspace_id > :after")
                + " ORDER BY wa.workspace_id LIMIT :max";
        JdbcClient.StatementSpec s = j.sql("""
                SELECT wa.workspace_id, r.code AS role_code, p.code AS permission_code
                  FROM public.workspace_role_assignments wa
                  JOIN public.roles r ON r.role_id = wa.role_id
                  LEFT JOIN public.role_permissions rp ON rp.role_id = wa.role_id
                  LEFT JOIN public.permissions p ON p.permission_id = rp.permission_id
                 WHERE wa.organization_id = :org AND wa.membership_id = :membership AND wa.status = 1
                   AND wa.workspace_id IN (""" + page + ") ORDER BY wa.workspace_id")
                .param("org", org).param("membership", membership).param("max", max);
        if (after != null) {
            s = s.param("after", after);
        }
        Map<UUID, Set<String>[]> byWorkspace = new LinkedHashMap<>();
        s.query((rs, n) -> {
            UUID w = rs.getObject("workspace_id", UUID.class);
            @SuppressWarnings("unchecked")
            Set<String>[] sets = byWorkspace.computeIfAbsent(w, k -> new Set[] {new TreeSet<String>(), new TreeSet<String>()});
            sets[0].add(role(rs.getString("role_code"), PermissionCatalog.Scope.WORKSPACE));
            String p = rs.getString("permission_code");
            if (p != null) {
                sets[1].add(permission(p, PermissionCatalog.Scope.WORKSPACE));
            }
            return null;
        }).list();
        List<WorkspaceAssignment> out = new ArrayList<>();
        byWorkspace.forEach((w, sets) -> out.add(new WorkspaceAssignment(w, List.copyOf(sets[0]), List.copyOf(sets[1]))));
        return out;
    }

    private static void requireWorkspacePermission(String permission) {
        if (PermissionCatalog.permissionScope(permission).orElse(null) != PermissionCatalog.Scope.WORKSPACE) {
            throw new IllegalStateException("a regra por workspace so vale para permissao de workspace (erro de programacao)");
        }
    }

    static String role(String code, PermissionCatalog.Scope expected) {
        if (PermissionCatalog.roleScope(code).orElse(null) != expected) {
            throw inconsistent("papel");
        }
        return code;
    }

    /** {@code expected} nulo: qualquer escopo do catalogo. */
    static String permission(String code, PermissionCatalog.Scope expected) {
        PermissionCatalog.Scope s = PermissionCatalog.permissionScope(code).orElse(null);
        if (s == null || (expected != null && s != expected)) {
            throw inconsistent("permissao");
        }
        return code;
    }

    private static ApiException inconsistent(String kind) {
        LOG.error("evento=api.catalogo_desconhecido tipo={}", kind);
        return new ApiException(ApiError.INTERNAL_ERROR, "catalogo_" + kind);
    }
}
