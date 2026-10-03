package com.nekkora.platform.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;

/**
 * Validacao de codigos lidos do banco (contrato GAP-02/03/06, §0.1): codigo fora do catalogo e permissao organizacional
 * em papel de workspace viram 500, nunca sao omitidos. O caso de papel desconhecido tambem e coberto pela rota
 * (PermissionCatalogIT); o de permissao organizacional em papel de workspace nao pode ser montado no banco
 * compartilhado sem alterar papeis reais, por isso fica aqui.
 */
class CatalogValidationTest {

    private static void assertServerError(Runnable r) {
        assertThatThrownBy(r::run).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).apiError()).isEqualTo(ApiError.INTERNAL_ERROR));
    }

    @Test
    void knownCodesPass() {
        assertThat(WorkspaceAccess.permission("automation.read", PermissionCatalog.Scope.WORKSPACE)).isEqualTo("automation.read");
        assertThat(WorkspaceAccess.permission("org.manage", null)).isEqualTo("org.manage");
        assertThat(WorkspaceAccess.role("ORG_ADMIN", PermissionCatalog.Scope.ORGANIZATION)).isEqualTo("ORG_ADMIN");
        assertThat(WorkspaceAccess.role("VIEWER", PermissionCatalog.Scope.WORKSPACE)).isEqualTo("VIEWER");
    }

    @Test
    void unknownOrMisplacedCodesAreServerErrors() {
        assertServerError(() -> WorkspaceAccess.permission("org.manage", PermissionCatalog.Scope.WORKSPACE));
        assertServerError(() -> WorkspaceAccess.permission("identity.mfa.reset", PermissionCatalog.Scope.WORKSPACE));
        assertServerError(() -> WorkspaceAccess.permission("teste.desconhecida", null));
        assertServerError(() -> WorkspaceAccess.role("ORG_ADMIN", PermissionCatalog.Scope.WORKSPACE));
        assertServerError(() -> WorkspaceAccess.role("OPERATOR", PermissionCatalog.Scope.ORGANIZATION));
        assertServerError(() -> WorkspaceAccess.role("ADMIN", PermissionCatalog.Scope.ORGANIZATION));
    }

    @Test
    void allWorkspacesKeepsOnlyWorkspacePermissions() {
        WorkspaceAccess.OrganizationGrant g = new WorkspaceAccess.OrganizationGrant(java.util.List.of("ORG_ADMIN"),
                java.util.List.copyOf(PermissionCatalog.permissions()));
        assertThat(g.allWorkspacesPermissions()).containsExactlyElementsOf(PermissionCatalog.permissions(PermissionCatalog.Scope.WORKSPACE));
    }
}
