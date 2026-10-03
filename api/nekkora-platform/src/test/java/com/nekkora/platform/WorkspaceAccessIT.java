package com.nekkora.platform;

import static com.nekkora.platform.AccessFixtures.assignOrgRole;
import static com.nekkora.platform.AccessFixtures.assignWorkspaceRole;
import static com.nekkora.platform.AccessFixtures.membershipOf;
import static com.nekkora.platform.AccessFixtures.revokeOrgAssignment;
import static com.nekkora.platform.AccessFixtures.revokeWorkspaceAssignment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.platform.access.WorkspaceAccess;

/**
 * Regra unica de acesso por workspace (contrato GAP-02/03/06, §1.2; DC-01 decidido pela responsavel em 2026-10-03),
 * conferida contra a tabela V da §5.2 (VISIBILIDADE; a tabela E, de atribuicao explicita, e conferida em
 * MyPermissionsRouteIT). Executa o componente real com o papel nekkora_platform (RLS da 012), na mesma forma em que as
 * rotas o usam: a decisao pontual (hasWorkspacePermission/requireWorkspace) e a condicao de listagem
 * (VISIBLE_WORKSPACE_CONDITION) precisam concordar.
 */
@ExtendWith(OutputCaptureExtension.class)
class WorkspaceAccessIT extends PlatformTestBase {

    @Autowired
    private OrgTransactions tx;

    @Autowired
    private WorkspaceAccess access;

    private Set<UUID> visible(UUID org, UUID membership, String permission) {
        return tx.inOrganization(org, Mode.READ_ONLY, j -> new TreeSet<>(access.visibleWorkspacesQuery(j,
                "SELECT w.workspace_id FROM public.workspaces w WHERE " + WorkspaceAccess.VISIBLE_WORKSPACE_CONDITION,
                org, membership, permission).query(UUID.class).list()));
    }

    private boolean has(UUID org, UUID membership, UUID workspace, String permission) {
        return tx.inOrganization(org, Mode.READ_ONLY, j -> access.hasWorkspacePermission(j, org, membership, workspace, permission));
    }

    @Test
    void visibilityTableV() {
        Org o = newOrganization();
        Org other = newOrganization();
        UUID w1 = newWorkspace(o, null);
        UUID w2 = newWorkspace(o, null);
        UUID w3 = AccessFixtures.namedWorkspace(o.id(), "W3 arquivado", 3);
        UUID foreign = newWorkspace(other, null);
        UUID admin2 = membershipOf(o.id(), internalUser(o, true, 2).id());
        assignOrgRole(o.id(), admin2, "ORG_ADMIN", o.adminMembership());
        assignWorkspaceRole(o.id(), admin2, w3, "VIEWER", o.adminMembership());
        UUID wsAdmin = membershipOf(o.id(), internalUser(o, true, 2).id());
        assignWorkspaceRole(o.id(), wsAdmin, w1, "WORKSPACE_ADMIN", o.adminMembership());
        UUID operator = membershipOf(o.id(), internalUser(o, true, 2).id());
        assignWorkspaceRole(o.id(), operator, w1, "OPERATOR", o.adminMembership());
        UUID viewer = membershipOf(o.id(), internalUser(o, true, 2).id());
        assignWorkspaceRole(o.id(), viewer, w2, "VIEWER", o.adminMembership());
        UUID revoked = membershipOf(o.id(), internalUser(o, true, 2).id());
        revokeWorkspaceAssignment(assignWorkspaceRole(o.id(), revoked, w1, "OPERATOR", o.adminMembership()), o.adminMembership());
        UUID none = membershipOf(o.id(), internalUser(o, true, 2).id());
        UUID suspended = membershipOf(o.id(), internalUser(o, true, 3).id());
        assignWorkspaceRole(o.id(), suspended, w1, "OPERATOR", o.adminMembership());
        UUID client = newClient(o);
        TestUser ext = externalUser(o, client, null); // so o vinculo externo; a concessao exige workspace do mesmo cliente
        UUID externalMembership = AccessFixtures.clientMembershipOf(o.id(), ext.id());

        Map<String, Object[]> table = new LinkedHashMap<>();
        table.put("ORG_ADMIN sem atribuicao de workspace", new Object[] {o.adminMembership(), Set.of(w1, w2, w3)});
        table.put("ORG_ADMIN + VIEWER explicito em W3", new Object[] {admin2, Set.of(w1, w2, w3)});
        table.put("WORKSPACE_ADMIN em W1", new Object[] {wsAdmin, Set.of(w1)});
        table.put("OPERATOR em W1", new Object[] {operator, Set.of(w1)});
        table.put("VIEWER em W2", new Object[] {viewer, Set.of(w2)});
        table.put("atribuicao revogada em W1", new Object[] {revoked, Set.of()});
        table.put("sem papeis", new Object[] {none, Set.of()});
        table.put("conta suspensa com OPERATOR em W1", new Object[] {suspended, Set.of()});
        table.put("vinculo externo (client_memberships)", new Object[] {externalMembership, Set.of()});
        for (Map.Entry<String, Object[]> e : table.entrySet()) {
            UUID m = (UUID) e.getValue()[0];
            @SuppressWarnings("unchecked")
            Set<UUID> expected = (Set<UUID>) e.getValue()[1];
            assertThat(visible(o.id(), m, "automation.read")).as("listagem: " + e.getKey()).isEqualTo(new TreeSet<>(expected));
            for (UUID w : List.of(w1, w2, w3, foreign, UUID.randomUUID())) {
                assertThat(has(o.id(), m, w, "automation.read")).as("decisao pontual: " + e.getKey() + " em " + w).isEqualTo(expected.contains(w));
            }
        }
        assertThat(visible(other.id(), o.adminMembership(), "automation.read")).as("vinculo de A na organizacao B").isEmpty();
    }

    @Test
    void permissionSpecificDecisionsFollowTheRoleMatrix() {
        Org o = newOrganization();
        UUID w1 = newWorkspace(o, null);
        UUID operator = membershipOf(o.id(), internalUser(o, true, 2).id());
        assignWorkspaceRole(o.id(), operator, w1, "OPERATOR", o.adminMembership());
        UUID viewer = membershipOf(o.id(), internalUser(o, true, 2).id());
        assignWorkspaceRole(o.id(), viewer, w1, "VIEWER", o.adminMembership());
        assertThat(has(o.id(), operator, w1, "automation.execute")).isTrue();
        assertThat(has(o.id(), operator, w1, "automation.manage")).isFalse();
        assertThat(has(o.id(), viewer, w1, "automation.execute")).isFalse();
        assertThat(has(o.id(), o.adminMembership(), w1, "workspace_secret.manage")).as("regra (a)").isTrue();
        assertThatThrownBy(() -> has(o.id(), o.adminMembership(), w1, "org.manage")).as("permissao organizacional nao vale em W")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> has(o.id(), o.adminMembership(), w1, "inexistente")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requireWorkspaceGivesTheSame404ForMissingForeignAndForbidden() {
        Org o = newOrganization();
        Org other = newOrganization();
        UUID w1 = newWorkspace(o, null);
        UUID w2 = newWorkspace(o, null);
        UUID foreign = newWorkspace(other, null);
        UUID operator = membershipOf(o.id(), internalUser(o, true, 2).id());
        UUID assignment = assignWorkspaceRole(o.id(), operator, w1, "OPERATOR", o.adminMembership());
        tx.inOrganization(o.id(), Mode.READ_ONLY, j -> {
            access.requireWorkspace(j, o.id(), operator, w1, "automation.read");
            return null;
        });
        for (UUID w : List.of(w2, foreign, UUID.randomUUID())) {
            assertThatThrownBy(() -> tx.inOrganization(o.id(), Mode.READ_ONLY, j -> {
                access.requireWorkspace(j, o.id(), operator, w, "automation.read");
                return null;
            })).isInstanceOf(ApiException.class).satisfies(e -> assertThat(((ApiException) e).apiError()).isEqualTo(ApiError.NOT_FOUND));
        }
        revokeWorkspaceAssignment(assignment, o.adminMembership());
        assertThat(has(o.id(), operator, w1, "automation.read")).as("revogacao lida no estado atual, sem cache").isFalse();

        UUID admin2 = membershipOf(o.id(), internalUser(o, true, 2).id());
        UUID orgAssignment = assignOrgRole(o.id(), admin2, "ORG_ADMIN", o.adminMembership());
        assertThat(has(o.id(), admin2, w2, "automation.read")).isTrue();
        revokeOrgAssignment(orgAssignment, o.adminMembership());
        assertThat(has(o.id(), admin2, w2, "automation.read")).as("ORG_ADMIN revogado perde a regra (a)").isFalse();
    }

    private void requireOn(UUID org, UUID membership, UUID workspace) {
        tx.inOrganization(org, Mode.READ_ONLY, j -> {
            access.requireWorkspace(j, org, membership, workspace, "automation.read");
            return null;
        });
    }

    private static void assertServerError(Runnable r) {
        assertThatThrownBy(r::run).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).apiError()).isEqualTo(ApiError.INTERNAL_ERROR));
    }

    /**
     * Parecer da GAP-02, item 1: papel fora do catalogo COM a permissao pedida (automation.read) nao concede acesso em
     * nenhum caminho; listagem, decisao pontual e requireWorkspace dao 500 com log, como a P02. Controles positivos com
     * papeis validos na mesma organizacao e nos mesmos workspaces.
     */
    @Test
    void roleOutsideCatalogIsAServerErrorInEveryAccessPath(CapturedOutput output) {
        Org o = newOrganization();
        UUID w1 = newWorkspace(o, null);
        UUID w2 = newWorkspace(o, null);
        AccessFixtures.TestRole wsRole = AccessFixtures.roleOutsideCatalog(2, "automation.read");
        AccessFixtures.TestRole orgRole = AccessFixtures.roleOutsideCatalog(1, "automation.read");
        UUID onlyUnknown = membershipOf(o.id(), internalUser(o, true, 2).id());
        assignWorkspaceRole(o.id(), onlyUnknown, w1, wsRole.code(), o.adminMembership());
        UUID mixed = membershipOf(o.id(), internalUser(o, true, 2).id());
        assignWorkspaceRole(o.id(), mixed, w1, wsRole.code(), o.adminMembership());
        assignWorkspaceRole(o.id(), mixed, w2, "OPERATOR", o.adminMembership());
        UUID orgUnknown = membershipOf(o.id(), internalUser(o, true, 2).id());
        assignOrgRole(o.id(), orgUnknown, orgRole.code(), o.adminMembership());
        UUID control = membershipOf(o.id(), internalUser(o, true, 2).id());
        assignWorkspaceRole(o.id(), control, w1, "OPERATOR", o.adminMembership());

        // controles positivos: papel valido no mesmo workspace; ORG_ADMIN
        assertThat(visible(o.id(), control, "automation.read")).containsExactly(w1);
        assertThat(has(o.id(), control, w1, "automation.read")).isTrue();
        requireOn(o.id(), control, w1);
        assertThat(visible(o.id(), o.adminMembership(), "automation.read")).containsExactlyInAnyOrder(w1, w2);
        int logsBefore = count(output.getOut(), "evento=api.catalogo_desconhecido");

        int refusals = 0;
        for (UUID m : java.util.List.of(onlyUnknown, mixed, orgUnknown)) {
            assertServerError(() -> visible(o.id(), m, "automation.read"));
            assertServerError(() -> has(o.id(), m, w1, "automation.read"));
            assertServerError(() -> requireOn(o.id(), m, w1));
            // a politica vale para o vinculo inteiro: tambem no workspace em que o papel valido existe
            assertServerError(() -> has(o.id(), m, w2, "automation.read"));
            refusals += 4;
        }
        assertThat(count(output.getOut(), "evento=api.catalogo_desconhecido") - logsBefore).as("um log por recusa").isEqualTo(refusals);
        // depois das recusas, os controles continuam iguais (nada foi escondido nem concedido por cache)
        assertThat(visible(o.id(), control, "automation.read")).containsExactly(w1);
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
