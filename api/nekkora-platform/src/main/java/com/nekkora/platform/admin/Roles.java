package com.nekkora.platform.admin;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.web.ApiException;

/** Papeis do catalogo da 003 aceitos nas rotas de acesso, com o escopo de cada um (1=organizacao, 2=workspace). */
final class Roles {

    static final Map<String, Short> SCOPE = Map.of("ORG_ADMIN", (short) 1, "WORKSPACE_ADMIN", (short) 2, "OPERATOR", (short) 2, "VIEWER", (short) 2);

    private Roles() {
    }

    /** Papel pedido: {@code code} nulo = nenhum papel. Papel de organizacao sem workspace; papel de workspace com ele. */
    record Requested(String code, Short scope, UUID workspace) {

        UUID roleId(JdbcClient j) {
            return code == null ? null : Roles.roleId(j, code);
        }
    }

    static Requested requested(String code, UUID workspace) {
        if (code == null) {
            if (workspace != null) {
                throw ApiException.invalid("workspace_sem_papel");
            }
            return new Requested(null, null, null);
        }
        Short scope = SCOPE.get(code);
        if (scope == null) {
            throw ApiException.invalid("papel");
        }
        if ((scope == 1) != (workspace == null)) {
            throw ApiException.invalid("escopo_do_papel");
        }
        return new Requested(code, scope, workspace);
    }

    static UUID roleId(JdbcClient j, String code) {
        return j.sql("SELECT role_id FROM public.roles WHERE code = ? AND scope = ?").param(code).param(SCOPE.get(code)).query(UUID.class).single();
    }

    static boolean workspaceActive(JdbcClient j, UUID org, UUID workspace) {
        return j.sql("SELECT EXISTS (SELECT 1 FROM public.workspaces WHERE organization_id = ? AND workspace_id = ? AND status = 1)")
                .param(org).param(workspace).query(Boolean.class).single();
    }
}
