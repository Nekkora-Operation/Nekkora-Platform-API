package com.nekkora.platform;

import static com.nekkora.platform.AccessFixtures.assignOrgRole;
import static com.nekkora.platform.AccessFixtures.assignWorkspaceRole;
import static com.nekkora.platform.AccessFixtures.membershipOf;
import static com.nekkora.platform.AccessFixtures.revokeOrgAssignment;
import static com.nekkora.platform.AccessFixtures.revokeWorkspaceAssignment;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.platform.paging.CursorCodec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GAP-02 (contrato GAP-02/03/06, ROTA-P01 e ROTA-P02; DC-01, DC-02, DC-03 e DC-15 decididos pela responsavel em
 * 2026-10-03): resposta E dados conferidos contra a tabela E da §5.2 (atribuicao explicita; nunca derivada da
 * visibilidade), ordem 401 -> 400 -> 403, paginacao estavel, revogacao entre paginas, vinculos do cursor, volume sem
 * truncar e isolamento entre organizacoes. Oraculo independente: listas literais de codigos, nao o catalogo da aplicacao.
 */
class MyPermissionsRouteIT extends PlatformTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final List<String> ALL_23 = sorted("org.manage", "workspace.lifecycle", "workspace.configure", "role.assign_admin_roles",
            "role.assign_operator_viewer", "automation.read", "automation.execute", "logs.read", "external_access.manage", "worker.manage",
            "agent.manage", "agent_credential.manage", "worker_agent.assign", "worker_workspace.authorize", "automation.manage",
            "automation.publish", "workspace_parameter.read", "workspace_parameter.manage", "workspace_secret.read", "workspace_secret.manage",
            "automation_schedule.read", "automation_schedule.manage", "identity.mfa.reset");
    static final List<String> WORKSPACE_13 = sorted("workspace.configure", "role.assign_operator_viewer", "automation.read",
            "automation.execute", "logs.read", "automation.manage", "automation.publish", "workspace_parameter.read",
            "workspace_parameter.manage", "workspace_secret.read", "workspace_secret.manage", "automation_schedule.read",
            "automation_schedule.manage");
    static final List<String> OPERATOR = sorted("automation.read", "automation.execute", "logs.read");
    static final List<String> VIEWER = List.of("automation.read");

    @Autowired
    private CursorCodec cursors;

    private static List<String> sorted(String... codes) {
        return List.copyOf(new TreeSet<>(List.of(codes)));
    }

    private record Got(int status, JsonNode body) {
    }

    private static Got get(BrowserClient n, String path) {
        BrowserClient.Response r = n.get(path);
        return new Got(r.status(), r.body() == null || r.body().isBlank() ? null : JSON.readTree(r.body()));
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(x -> out.add(x.asString()));
        return out;
    }

    private BrowserClient login(Org o, TestUser u) {
        BrowserClient n = browser();
        firstLogin(n, o.id(), u);
        return n;
    }

    private BrowserClient admin(Org o) {
        return login(o, new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray()));
    }

    private static String p01(UUID org) {
        return "/api/v1/o/" + org + "/me/permissions";
    }

    private static String p02(UUID org, String query) {
        return "/api/v1/o/" + org + "/me/permissions/workspaces" + (query == null ? "" : "?" + query);
    }

    private static void assertError(Got g, int status, String code) {
        assertThat(g.status()).isEqualTo(status);
        assertThat(g.body().get("error").asString()).isEqualTo(code);
    }

    /** Todas as paginas da P02 com {@code limit}; confere que nenhuma pagina intermediaria vem vazia. */
    private static List<JsonNode> allPages(BrowserClient n, UUID org, int limit) {
        List<JsonNode> items = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 1000; page++) {
            Got g = get(n, p02(org, "limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor)));
            assertThat(g.status()).as("pagina " + page).isEqualTo(200);
            g.body().get("items").forEach(items::add);
            JsonNode next = g.body().get("nextCursor");
            if (next.isNull()) {
                return items;
            }
            assertThat(g.body().get("items").size()).as("pagina intermediaria cheia").isEqualTo(limit);
            cursor = next.asString();
        }
        throw new AssertionError("paginacao nao terminou");
    }

    // ------------------------------------------------------------------ tabela E (§5.2)

    @Test
    void orgAdminWithoutAssignmentsHasEverythingInOrganizationAndEmptyP02() {
        Org o = newOrganization();
        newWorkspace(o, null);
        BrowserClient a = admin(o);
        Got g = get(a, p01(o.id()));
        assertThat(g.status()).isEqualTo(200);
        assertThat(g.body().get("organizationId").asString()).isEqualTo(o.id().toString());
        assertThat(g.body().get("membershipId").asString()).isEqualTo(o.adminMembership().toString());
        assertThat(texts(g.body().get("organization").get("roles"))).containsExactly("ORG_ADMIN");
        assertThat(texts(g.body().get("organization").get("permissions"))).as("as 23, em ordem").isEqualTo(ALL_23);
        assertThat(texts(g.body().get("allWorkspaces").get("permissions"))).as("so as 13 de workspace").isEqualTo(WORKSPACE_13);
        assertThat(g.body().get("hasWorkspaceAssignments").asBoolean()).isFalse();
        assertThat(g.body().properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactly("organizationId", "membershipId", "organization", "allWorkspaces", "hasWorkspaceAssignments");
        Got p = get(a, p02(o.id(), null));
        assertThat(p.status()).isEqualTo(200);
        assertThat(p.body().get("items").size()).as("ORG_ADMIN ve todos (regra a), mas a P02 so lista atribuicao explicita").isZero();
        assertThat(p.body().get("nextCursor").isNull()).isTrue();
    }

    @Test
    void orgAdminWithExplicitViewerSeesOnlyThatAssignmentInP02() {
        Org o = newOrganization();
        UUID w3 = AccessFixtures.namedWorkspace(o.id(), "W3 arquivado", 3);
        assignWorkspaceRole(o.id(), o.adminMembership(), w3, "VIEWER", o.adminMembership());
        BrowserClient a = admin(o);
        Got g = get(a, p01(o.id()));
        assertThat(g.body().get("hasWorkspaceAssignments").asBoolean()).isTrue();
        assertThat(texts(g.body().get("allWorkspaces").get("permissions"))).isEqualTo(WORKSPACE_13);
        JsonNode items = get(a, p02(o.id(), null)).body().get("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).get("workspaceId").asString()).as("workspace arquivado aparece").isEqualTo(w3.toString());
        assertThat(texts(items.get(0).get("roles"))).containsExactly("VIEWER");
        assertThat(texts(items.get(0).get("permissions"))).isEqualTo(VIEWER);
    }

    @Test
    void workspaceRolesAppearOnlyInP02WithTheirExactPermissions() {
        Org o = newOrganization();
        UUID w1 = newWorkspace(o, null);
        UUID w2 = newWorkspace(o, null);
        TestUser wsAdmin = internalUser(o, true, 2);
        TestUser operator = internalUser(o, true, 2);
        TestUser viewer = internalUser(o, true, 2);
        TestUser both = internalUser(o, true, 2);
        assignWorkspaceRole(o.id(), membershipOf(o.id(), wsAdmin.id()), w1, "WORKSPACE_ADMIN", o.adminMembership());
        assignWorkspaceRole(o.id(), membershipOf(o.id(), operator.id()), w1, "OPERATOR", o.adminMembership());
        assignWorkspaceRole(o.id(), membershipOf(o.id(), viewer.id()), w2, "VIEWER", o.adminMembership());
        UUID mb = membershipOf(o.id(), both.id());
        assignWorkspaceRole(o.id(), mb, w1, "OPERATOR", o.adminMembership());
        assignWorkspaceRole(o.id(), mb, w2, "VIEWER", o.adminMembership());
        assignWorkspaceRole(o.id(), mb, w2, "OPERATOR", o.adminMembership());

        for (Object[] c : new Object[][] {{wsAdmin, w1, List.of("WORKSPACE_ADMIN"), WORKSPACE_13}, {operator, w1, List.of("OPERATOR"), OPERATOR},
                {viewer, w2, List.of("VIEWER"), VIEWER}}) {
            BrowserClient n = login(o, (TestUser) c[0]);
            Got g = get(n, p01(o.id()));
            assertThat(g.status()).isEqualTo(200);
            assertThat(g.body().get("organization").get("roles").size()).as("sem papel organizacional").isZero();
            assertThat(g.body().get("organization").get("permissions").size()).isZero();
            assertThat(g.body().get("allWorkspaces").get("permissions").size()).as("regra (a) nao se aplica").isZero();
            assertThat(g.body().get("hasWorkspaceAssignments").asBoolean()).isTrue();
            JsonNode items = get(n, p02(o.id(), null)).body().get("items");
            assertThat(items.size()).isEqualTo(1);
            assertThat(items.get(0).get("workspaceId").asString()).isEqualTo(c[1].toString());
            assertThat(texts(items.get(0).get("roles"))).isEqualTo(c[2]);
            assertThat(texts(items.get(0).get("permissions"))).as("nenhuma organizacional").isEqualTo(c[3]);
        }
        BrowserClient n = login(o, both);
        JsonNode items = get(n, p02(o.id(), null)).body().get("items");
        assertThat(items.size()).isEqualTo(2);
        for (JsonNode it : items) {
            if (it.get("workspaceId").asString().equals(w2.toString())) {
                assertThat(texts(it.get("roles"))).as("dois papeis no mesmo workspace, sem repeticao").containsExactly("OPERATOR", "VIEWER");
                assertThat(texts(it.get("permissions"))).isEqualTo(OPERATOR);
            } else {
                assertThat(texts(it.get("roles"))).containsExactly("OPERATOR");
            }
        }
    }

    @Test
    void revokedAssignmentAndUserWithoutRolesGiveEmptyLists() {
        Org o = newOrganization();
        UUID w1 = newWorkspace(o, null);
        TestUser revoked = internalUser(o, true, 2);
        TestUser none = internalUser(o, true, 2);
        UUID a = assignWorkspaceRole(o.id(), membershipOf(o.id(), revoked.id()), w1, "OPERATOR", o.adminMembership());
        revokeWorkspaceAssignment(a, o.adminMembership());
        for (TestUser u : List.of(revoked, none)) {
            BrowserClient n = login(o, u);
            Got g = get(n, p01(o.id()));
            assertThat(g.status()).isEqualTo(200);
            assertThat(g.body().get("organization").get("roles").size()).isZero();
            assertThat(g.body().get("allWorkspaces").get("permissions").size()).isZero();
            assertThat(g.body().get("hasWorkspaceAssignments").asBoolean()).isFalse();
            Got p = get(n, p02(o.id(), null));
            assertThat(p.status()).isEqualTo(200);
            assertThat(p.body().get("items").size()).isZero();
            assertThat(p.body().get("nextCursor").isNull()).isTrue();
        }
    }

    // ------------------------------------------------------------------ 401, 400 e 403 (ordem da §1.3)

    @Test
    void authenticationThenParametersThenExternalAccount() {
        Org a = newOrganization();
        Org b = newOrganization();
        UUID client = newClient(a);
        UUID ws = newWorkspace(a, client);
        TestUser ext = externalUser(a, client, ws);
        BrowserClient anonymous = browser();
        assertError(get(anonymous, p01(a.id())), 401, "unauthenticated");
        assertError(get(anonymous, p02(a.id(), null)), 401, "unauthenticated");
        BrowserClient adminA = admin(a);
        assertError(get(adminA, p01(b.id())), 401, "unauthenticated");
        assertError(get(adminA, p02(b.id(), null)), 401, "unauthenticated");
        assertError(get(adminA, "/api/v1/o/nao-e-uuid/me/permissions"), 401, "unauthenticated");
        assertThat(get(adminA, p01(a.id())).status()).as("controle positivo").isEqualTo(200);

        BrowserClient external = login(a, ext);
        assertError(get(external, p01(a.id())), 403, "forbidden");
        assertError(get(external, p02(a.id(), null)), 403, "forbidden");
        assertError(get(external, p02(a.id(), "limit=0")), 400, "invalid_request");
        assertError(get(external, p02(a.id(), "cursor=adulterado")), 400, "invalid_request");

        for (String q : List.of("limit=0", "limit=101", "limit=abc", "limit=1.5", "cursor=", "cursor=abc.def", "cursor=" + "a".repeat(1100))) {
            assertError(get(adminA, p02(a.id(), q)), 400, "invalid_request");
        }
        assertThat(get(adminA, p02(a.id(), "limit=1")).status()).isEqualTo(200);
        assertThat(get(adminA, p02(a.id(), "limit=100")).status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ paginacao estavel (§5.3)

    @Test
    void stablePaginationVisitsEveryAssignmentOnceInWorkspaceIdOrder() {
        Org o = newOrganization();
        TestUser five = internalUser(o, true, 2);
        TestUser four = internalUser(o, true, 2);
        List<String> expectedFive = new ArrayList<>();
        List<String> expectedFour = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID w = newWorkspace(o, null);
            assignWorkspaceRole(o.id(), membershipOf(o.id(), five.id()), w, "VIEWER", o.adminMembership());
            expectedFive.add(w.toString());
            if (i < 4) {
                assignWorkspaceRole(o.id(), membershipOf(o.id(), four.id()), w, "OPERATOR", o.adminMembership());
                expectedFour.add(w.toString());
            }
        }
        expectedFive.sort(null); // texto minusculo do UUID = ordem de bytes = ordem do PostgreSQL
        expectedFour.sort(null);
        BrowserClient n = login(o, five);
        BrowserClient n4 = login(o, four);
        for (Object[] c : new Object[][] {{n, expectedFive}, {n4, expectedFour}}) {
            List<JsonNode> items = allPages((BrowserClient) c[0], o.id(), 2);
            List<String> ids = items.stream().map(x -> x.get("workspaceId").asString()).toList();
            assertThat(ids).as("cada uma uma vez, em ordem de workspaceId").isEqualTo(c[1]);
        }
        Got p1 = get(n, p02(o.id(), "limit=2"));
        Got p3 = get(n, p02(o.id(), "limit=2&cursor=" + get(n, p02(o.id(), "limit=2&cursor=" + p1.body().get("nextCursor").asString()))
                .body().get("nextCursor").asString()));
        assertThat(p3.body().get("items").size()).isEqualTo(1);
        assertThat(p3.body().get("nextCursor").isNull()).isTrue();
        Got changedLimit = get(n, p02(o.id(), "limit=3&cursor=" + p1.body().get("nextCursor").asString()));
        assertThat(changedLimit.status()).as("limit fora do vinculo do cursor").isEqualTo(200);
        assertThat(changedLimit.body().get("items").size()).isEqualTo(3);
    }

    @Test
    void moreThanFiveHundredAssignmentsAreAllReachableWithoutTruncation() {
        Org o = newOrganization();
        TestUser u = internalUser(o, true, 2);
        UUID m = membershipOf(o.id(), u.id());
        DB.asSuperuser("INSERT INTO workspaces (organization_id, name, status) SELECT '" + o.id() + "', 'Volume ' || g, 1 FROM generate_series(1, 501) g",
                "INSERT INTO workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status, assigned_by_membership_id) "
                        + "SELECT w.organization_id, w.workspace_id, '" + m + "', r.role_id, 2, 1, '" + o.adminMembership() + "' FROM workspaces w, roles r "
                        + "WHERE w.organization_id = '" + o.id() + "' AND r.code = 'VIEWER'");
        BrowserClient n = login(o, u);
        List<JsonNode> items = allPages(n, o.id(), 100);
        Set<String> ids = new HashSet<>();
        items.forEach(x -> ids.add(x.get("workspaceId").asString()));
        assertThat(items).hasSize(501);
        assertThat(ids).as("sem repeticao").hasSize(501);
        assertThat(allPages(n, o.id(), 25)).as("limit padrao de pagina").hasSize(501);
        assertThat(get(n, p02(o.id(), null)).body().get("items").size()).as("padrao 25").isEqualTo(25);
    }

    // ------------------------------------------------------------------ revogacao entre paginas e acesso (§5.5)

    @Test
    void revocationBetweenPagesIsAppliedOnTheNextPage() {
        Org o = newOrganization();
        TestUser u = internalUser(o, true, 2);
        UUID m = membershipOf(o.id(), u.id());
        List<UUID> ws = new ArrayList<>();
        List<UUID> assignments = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ws.add(newWorkspace(o, null));
        }
        ws.sort((x, y) -> x.toString().compareTo(y.toString()));
        for (UUID w : ws) {
            assignments.add(assignWorkspaceRole(o.id(), m, w, "OPERATOR", o.adminMembership()));
        }
        BrowserClient n = login(o, u);
        Got p1 = get(n, p02(o.id(), "limit=1"));
        assertThat(p1.body().get("items").get(0).get("workspaceId").asString()).isEqualTo(ws.get(0).toString());
        revokeWorkspaceAssignment(assignments.get(1), o.adminMembership());
        Got p2 = get(n, p02(o.id(), "limit=1&cursor=" + p1.body().get("nextCursor").asString()));
        assertThat(p2.status()).as("mudanca que retira autorizacao de um item nao invalida o cursor").isEqualTo(200);
        assertThat(p2.body().get("items").get(0).get("workspaceId").asString()).as("o item revogado some").isEqualTo(ws.get(2).toString());
        assertThat(p2.body().get("nextCursor").isNull()).isTrue();

        // revogar o ORG_ADMIN de um segundo administrador: allWorkspaces some na proxima consulta
        TestUser second = internalUser(o, true, 2);
        UUID orgAssignment = assignOrgRole(o.id(), membershipOf(o.id(), second.id()), "ORG_ADMIN", o.adminMembership());
        BrowserClient s = login(o, second);
        assertThat(texts(get(s, p01(o.id())).body().get("allWorkspaces").get("permissions"))).isEqualTo(WORKSPACE_13);
        revokeOrgAssignment(orgAssignment, o.adminMembership());
        Got after = get(s, p01(o.id()));
        assertThat(after.status()).isEqualTo(200);
        assertThat(after.body().get("organization").get("roles").size()).isZero();
        assertThat(after.body().get("allWorkspaces").get("permissions").size()).isZero();

        // revogar as sessoes: 401
        BrowserClient.Response r = n.post("/api/v1/o/" + o.id() + "/auth/sessions/revoke-all", null);
        assertThat(r.status()).isEqualTo(204);
        assertError(get(n, p02(o.id(), "limit=1&cursor=" + p1.body().get("nextCursor").asString())), 401, "unauthenticated");
        assertError(get(n, p01(o.id())), 401, "unauthenticated");
    }

    // ------------------------------------------------------------------ vinculos do cursor (§1.5, §5.6)

    @Test
    void cursorFromAnotherMembershipOrganizationRouteOrSortIsRefused() {
        Org a = newOrganization();
        Org b = newOrganization();
        TestUser u1 = internalUser(a, true, 2);
        TestUser u2 = internalUser(a, true, 2);
        TestUser ub = internalUser(b, true, 2);
        UUID m1 = membershipOf(a.id(), u1.id());
        for (int i = 0; i < 3; i++) {
            assignWorkspaceRole(a.id(), m1, newWorkspace(a, null), "VIEWER", a.adminMembership());
            assignWorkspaceRole(a.id(), membershipOf(a.id(), u2.id()), newWorkspace(a, null), "VIEWER", a.adminMembership());
            assignWorkspaceRole(b.id(), membershipOf(b.id(), ub.id()), newWorkspace(b, null), "VIEWER", b.adminMembership());
        }
        BrowserClient n1 = login(a, u1);
        BrowserClient n2 = login(a, u2);
        BrowserClient nb = login(b, ub);
        String c1 = get(n1, p02(a.id(), "limit=1")).body().get("nextCursor").asString();
        assertThat(get(n1, p02(a.id(), "limit=1&cursor=" + c1)).status()).as("controle positivo").isEqualTo(200);
        assertError(get(n2, p02(a.id(), "limit=1&cursor=" + c1)), 400, "invalid_request");
        assertError(get(nb, p02(b.id(), "limit=1&cursor=" + c1)), 400, "invalid_request");
        UUID any = UUID.randomUUID();
        String otherRoute = cursors.encode(new CursorCodec.Scope("listWorkspaces", a.id(), m1, null, CursorCodec.filters(), "workspaceId.asc"), List.of(any));
        String otherSort = cursors.encode(new CursorCodec.Scope("listMyWorkspacePermissions", a.id(), m1, null, CursorCodec.filters(), "workspaceId.desc"), List.of(any));
        String otherFilters = cursors.encode(new CursorCodec.Scope("listMyWorkspacePermissions", a.id(), m1, null, CursorCodec.filters("x", "y"),
                "workspaceId.asc"), List.of(any));
        String withParent = cursors.encode(new CursorCodec.Scope("listMyWorkspacePermissions", a.id(), m1, any, CursorCodec.filters(),
                "workspaceId.asc"), List.of(any));
        String wrongType = cursors.encode(new CursorCodec.Scope("listMyWorkspacePermissions", a.id(), m1, null, CursorCodec.filters(),
                "workspaceId.asc"), List.of(42L));
        String goodScope = cursors.encode(new CursorCodec.Scope("listMyWorkspacePermissions", a.id(), m1, null, CursorCodec.filters(),
                "workspaceId.asc"), List.of(any));
        for (String c : List.of(otherRoute, otherSort, otherFilters, withParent, wrongType)) {
            assertError(get(n1, p02(a.id(), "cursor=" + c)), 400, "invalid_request");
        }
        assertThat(get(n1, p02(a.id(), "cursor=" + goodScope)).status()).as("controle positivo do mesmo codificador").isEqualTo(200);
        String tampered = c1.substring(0, 10) + (c1.charAt(10) == 'A' ? 'B' : 'A') + c1.substring(11);
        assertError(get(n1, p02(a.id(), "cursor=" + tampered)), 400, "invalid_request");
    }
}
