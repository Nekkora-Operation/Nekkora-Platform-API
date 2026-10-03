package com.nekkora.platform.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;

/**
 * Decisao "ao menos uma" das permissoes organizacionais (GAP-03, ROTA-M03; correcao da preparacao pelo parecer de
 * 2026-10-03): chama o metodo REAL com as DUAS permissoes exigidas, variando as permissoes DISPONIVEIS por uma fonte
 * controlada (dependencia injetada so neste teste); a logica de decisao e a de producao. A mutacao OU -> E precisa
 * reprovar os dois primeiros casos.
 */
class OrganizationPermissionTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final String[] CLIENTS_ROUTE = {"org.manage", "external_access.manage"};

    private static WorkspaceAccess with(Set<String> available) {
        return new WorkspaceAccess((j, org, membership) -> available);
    }

    private static void assertForbidden(Runnable r) {
        assertThatThrownBy(r::run).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).apiError()).isEqualTo(ApiError.FORBIDDEN));
    }

    @Test
    void onlyOrgManageIsAllowed() {
        assertThatCode(() -> with(Set.of("org.manage")).requireAnyOrganizationPermission(null, ORG, MEMBERSHIP, CLIENTS_ROUTE))
                .doesNotThrowAnyException();
    }

    @Test
    void onlyExternalAccessManageIsAllowed() {
        assertThatCode(() -> with(Set.of("external_access.manage")).requireAnyOrganizationPermission(null, ORG, MEMBERSHIP, CLIENTS_ROUTE))
                .doesNotThrowAnyException();
    }

    @Test
    void bothAreAllowed() {
        assertThatCode(() -> with(Set.of("org.manage", "external_access.manage"))
                .requireAnyOrganizationPermission(null, ORG, MEMBERSHIP, CLIENTS_ROUTE)).doesNotThrowAnyException();
    }

    @Test
    void noneIsForbidden() {
        assertForbidden(() -> with(Set.of()).requireAnyOrganizationPermission(null, ORG, MEMBERSHIP, CLIENTS_ROUTE));
        // outras permissoes organizacionais que a rota nao exige tambem nao bastam
        assertForbidden(() -> with(Set.of("identity.mfa.reset", "worker.manage", "workspace.lifecycle"))
                .requireAnyOrganizationPermission(null, ORG, MEMBERSHIP, CLIENTS_ROUTE));
    }

    @Test
    void singlePermissionRoutesAndCodeValidation() {
        assertThatCode(() -> with(Set.of("org.manage")).requireAnyOrganizationPermission(null, ORG, MEMBERSHIP, "org.manage"))
                .doesNotThrowAnyException();
        assertForbidden(() -> with(Set.of("external_access.manage")).requireAnyOrganizationPermission(null, ORG, MEMBERSHIP, "org.manage"));
        assertThat(with(Set.of("org.manage")).hasOrganizationPermission(null, ORG, MEMBERSHIP, "org.manage")).isTrue();
        assertThat(with(Set.of("org.manage")).hasOrganizationPermission(null, ORG, MEMBERSHIP, "external_access.manage")).isFalse();
        // permissao de workspace, codigo desconhecido ou nenhuma exigida: erro de programacao, nao 403
        assertThatThrownBy(() -> with(Set.of()).requireAnyOrganizationPermission(null, ORG, MEMBERSHIP, "automation.read"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> with(Set.of()).requireAnyOrganizationPermission(null, ORG, MEMBERSHIP, "nao.existe"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> with(Set.of()).requireAnyOrganizationPermission(null, ORG, MEMBERSHIP))
                .isInstanceOf(IllegalStateException.class);
    }
}
