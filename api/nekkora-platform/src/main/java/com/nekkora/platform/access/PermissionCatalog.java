package com.nekkora.platform.access;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Catalogo de permissoes e papeis da API (contrato GAP-02/03/06, §0.1): os 23 codigos de public.permissions (003, 004,
 * 005, 008, 009, 014) com a aplicabilidade definida pelo contrato, porque o banco nao guarda escopo de permissao. Fonte
 * dos enums do OpenAPI. Codigo que o banco devolva e o catalogo nao conheca e inconsistencia (500), nunca omitido; o IT
 * de catalogo compara este mapa com o banco.
 */
public final class PermissionCatalog {

    public enum Scope { ORGANIZATION, WORKSPACE }

    private static final Map<String, Scope> PERMISSIONS;
    private static final Map<String, Scope> ROLES;

    static {
        Map<String, Scope> p = new LinkedHashMap<>();
        for (String c : new String[] {"org.manage", "workspace.lifecycle", "role.assign_admin_roles", "external_access.manage",
                "worker.manage", "agent.manage", "agent_credential.manage", "worker_agent.assign", "worker_workspace.authorize",
                "identity.mfa.reset"}) {
            p.put(c, Scope.ORGANIZATION);
        }
        for (String c : new String[] {"workspace.configure", "role.assign_operator_viewer", "automation.read", "automation.execute",
                "logs.read", "automation.manage", "automation.publish", "workspace_parameter.read", "workspace_parameter.manage",
                "workspace_secret.read", "workspace_secret.manage", "automation_schedule.read", "automation_schedule.manage"}) {
            p.put(c, Scope.WORKSPACE);
        }
        PERMISSIONS = Collections.unmodifiableMap(p);
        Map<String, Scope> r = new LinkedHashMap<>();
        r.put("ORG_ADMIN", Scope.ORGANIZATION);
        r.put("WORKSPACE_ADMIN", Scope.WORKSPACE);
        r.put("OPERATOR", Scope.WORKSPACE);
        r.put("VIEWER", Scope.WORKSPACE);
        ROLES = Collections.unmodifiableMap(r);
    }

    private PermissionCatalog() {
    }

    public static Optional<Scope> permissionScope(String code) {
        return Optional.ofNullable(PERMISSIONS.get(code));
    }

    public static Optional<Scope> roleScope(String code) {
        return Optional.ofNullable(ROLES.get(code));
    }

    /** Todos os codigos de permissao, em ordem alfabetica. */
    public static Set<String> permissions() {
        return Collections.unmodifiableSet(new TreeSet<>(PERMISSIONS.keySet()));
    }

    public static Set<String> permissions(Scope scope) {
        Set<String> s = new TreeSet<>();
        PERMISSIONS.forEach((c, sc) -> {
            if (sc == scope) {
                s.add(c);
            }
        });
        return Collections.unmodifiableSet(s);
    }

    public static Set<String> roles() {
        return Collections.unmodifiableSet(new TreeSet<>(ROLES.keySet()));
    }
}
