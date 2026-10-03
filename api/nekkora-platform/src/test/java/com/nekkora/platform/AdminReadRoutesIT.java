package com.nekkora.platform;

import static com.nekkora.platform.AccessFixtures.assignOrgRole;
import static com.nekkora.platform.AccessFixtures.assignWorkspaceRole;
import static com.nekkora.platform.AccessFixtures.membershipOf;
import static com.nekkora.platform.AccessFixtures.revokeOrgAssignment;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.platform.paging.CursorCodec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GAP-03 (contrato GAP-02/03/06, ROTA-M01 /members, ROTA-M02 /client-grants, ROTA-M03 /clients; DC-02, DC-04, DC-05 e
 * DC-06 decididos pela responsavel): resposta E dados conferidos; ordem 401 -> 400 -> 403; paginacao estavel com
 * desempate; mudanca concorrente (§5.4) separada da estavel; revogacao entre paginas (§5.5); excecao DC-05 com controles
 * positivos; nomes longos e ordenacao binaria pelo prefixo de 48 pontos de codigo (§1.5.1); isolamento entre organizacoes.
 */
class AdminReadRoutesIT extends PlatformTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private CursorCodec cursors;

    private record Got(int status, JsonNode body) {
    }

    private static Got get(BrowserClient n, String path) {
        BrowserClient.Response r = n.get(path);
        return new Got(r.status(), r.body() == null || r.body().isBlank() ? null : JSON.readTree(r.body()));
    }

    private BrowserClient login(Org o, TestUser u) {
        BrowserClient n = browser();
        firstLogin(n, o.id(), u);
        return n;
    }

    private BrowserClient admin(Org o) {
        return login(o, new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray()));
    }

    private static String path(UUID org, String route, String query) {
        return "/api/v1/o/" + org + "/" + route + (query == null ? "" : "?" + query);
    }

    /**
     * Diagnostico de falha SEM o corpo completo (parecer da GAP-03): status, codigo de erro e quantidade de itens; nunca o
     * nextCursor nem dados das contas.
     */
    private static String diag(Got g) {
        JsonNode b = g.body();
        String error = b != null && b.has("error") ? b.get("error").asString() : "-";
        String items = b != null && b.has("items") ? String.valueOf(b.get("items").size()) : "-";
        String next = b != null && b.has("nextCursor") ? (b.get("nextCursor").isNull() ? "nulo" : "presente (ocultado)") : "-";
        return "status=" + g.status() + " error=" + error + " items=" + items + " nextCursor=" + next;
    }

    private static void assertError(Got g, int status, String code) {
        assertThat(g.status()).as(diag(g)).isEqualTo(status);
        assertThat(g.body().get("error").asString()).isEqualTo(code);
    }

    private static List<String> field(JsonNode items, String name) {
        List<String> out = new ArrayList<>();
        items.forEach(x -> out.add(x.get(name).asString()));
        return out;
    }

    private static List<String> keys(JsonNode n) {
        return n.properties().stream().map(Map.Entry::getKey).toList();
    }

    /** Todas as paginas; confere que nenhuma pagina intermediaria vem vazia e que a ultima tem nextCursor nulo. */
    private static List<JsonNode> allPages(BrowserClient n, UUID org, String route, String filters, int limit) {
        List<JsonNode> items = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 1000; page++) {
            String q = (filters == null ? "" : filters + "&") + "limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor);
            Got g = get(n, path(org, route, q));
            assertThat(g.status()).as("pagina " + page + " " + diag(g)).isEqualTo(200);
            g.body().get("items").forEach(items::add);
            if (g.body().get("nextCursor").isNull()) {
                return items;
            }
            assertThat(g.body().get("items").size()).as("pagina intermediaria cheia").isEqualTo(limit);
            cursor = g.body().get("nextCursor").asString();
        }
        throw new AssertionError("paginacao nao terminou");
    }

    private static void sameCreatedAt(String table, String column, UUID org) {
        DB.asSuperuser("UPDATE " + table + " SET " + column + " = '2026-01-01T00:00:00Z' WHERE organization_id = '" + org + "'");
    }

    // ================================================================== ROTA-M01 /members

    @Test
    void membersListsInternalAccountsWithExactFieldsAndMfaBoolean() {
        Org o = newOrganization();
        Org b = newOrganization();
        TestUser noFactor = internalUser(o, false, 2);
        TestUser disabledFactor = internalUser(o, true, 2);
        AccessFixtures.mfaFactor(o.id(), disabledFactor.id(), true, true);
        TestUser unconfirmed = internalUser(o, true, 2);
        AccessFixtures.mfaFactor(o.id(), unconfirmed.id(), false, false);
        TestUser confirmed = internalUser(o, true, 3);
        AccessFixtures.mfaFactor(o.id(), confirmed.id(), true, false);
        TestUser revokedAdmin = internalUser(o, true, 2);
        revokeOrgAssignment(assignOrgRole(o.id(), membershipOf(o.id(), revokedAdmin.id()), "ORG_ADMIN", o.adminMembership()), o.adminMembership());
        DB.asSuperuser("UPDATE organization_memberships SET status = 4 WHERE user_id = '" + revokedAdmin.id() + "'");
        UUID client = newClient(o);
        externalUser(o, client, null);
        internalUser(b, true, 2);

        BrowserClient a = admin(o);
        Got g = get(a, path(o.id(), "members", null));
        assertThat(g.status()).isEqualTo(200);
        JsonNode items = g.body().get("items");
        Map<String, JsonNode> byUser = new java.util.HashMap<>();
        items.forEach(x -> byUser.put(x.get("userId").asString(), x));
        assertThat(byUser.keySet()).as("so os internos da organizacao A (externo e organizacao B ausentes)")
                .containsExactlyInAnyOrder(o.adminId().toString(), noFactor.id().toString(), disabledFactor.id().toString(),
                        unconfirmed.id().toString(), confirmed.id().toString(), revokedAdmin.id().toString());
        for (JsonNode x : items) {
            assertThat(keys(x)).containsExactly("membershipId", "userId", "displayName", "email", "emailVerified", "accountStatus",
                    "membershipStatus", "mfaConfigured", "organizationRoles", "createdAt");
            assertThat(x.get("createdAt").isString()).isTrue();
        }
        JsonNode adm = byUser.get(o.adminId().toString());
        assertThat(adm.get("membershipId").asString()).isEqualTo(o.adminMembership().toString());
        assertThat(adm.get("email").asString()).isEqualTo(o.adminEmail());
        assertThat(adm.get("mfaConfigured").asBoolean()).as("ORG_ADMIN logou com MFA").isTrue();
        assertThat(adm.get("organizationRoles").get(0).asString()).isEqualTo("ORG_ADMIN");
        assertThat(byUser.get(noFactor.id().toString()).get("mfaConfigured").asBoolean()).as("sem fator").isFalse();
        assertThat(byUser.get(noFactor.id().toString()).get("emailVerified").asBoolean()).isFalse();
        assertThat(byUser.get(disabledFactor.id().toString()).get("mfaConfigured").asBoolean()).as("fator desativado").isFalse();
        assertThat(byUser.get(unconfirmed.id().toString()).get("mfaConfigured").asBoolean()).as("fator nao confirmado").isFalse();
        assertThat(byUser.get(confirmed.id().toString()).get("mfaConfigured").asBoolean()).as("fator confirmado").isTrue();
        assertThat(byUser.get(confirmed.id().toString()).get("accountStatus").asString()).isEqualTo("suspended");
        JsonNode rev = byUser.get(revokedAdmin.id().toString());
        assertThat(rev.get("organizationRoles").size()).as("atribuicao revogada nao aparece").isZero();
        assertThat(rev.get("membershipStatus").asString()).isEqualTo("revoked");
        assertThat(items.toString()).as("campos proibidos nos itens").doesNotContain("email_normalized", "emailNormalized", "secret", "recovery", "session");

        List<String> filtered = field(get(a, path(o.id(), "members", "status=revoked")).body().get("items"), "userId");
        assertThat(filtered).containsExactly(revokedAdmin.id().toString());
        assertThat(get(a, path(o.id(), "members", "status=active&status=revoked")).body().get("items").size()).isEqualTo(6);
        assertThat(get(a, path(o.id(), "members", "status=suspended")).body().get("items").size()).as("vinculo suspenso: nenhum").isZero();
    }

    @Test
    void membersStablePaginationBreaksTiesByIdAndRevocationBetweenPagesGives403() {
        Org o = newOrganization();
        for (int i = 0; i < 4; i++) {
            internalUser(o, true, 2);
        }
        TestUser second = internalUser(o, true, 2);
        UUID secondAdmin = assignOrgRole(o.id(), membershipOf(o.id(), second.id()), "ORG_ADMIN", o.adminMembership());
        sameCreatedAt("organization_memberships", "created_at", o.id()); // desempate so pelo id
        List<String> expected = new ArrayList<>();
        try (var c = DB.connectAsSuperuser(); var ps = c.prepareStatement("SELECT membership_id::text FROM organization_memberships WHERE organization_id = ? ORDER BY membership_id")) {
            ps.setObject(1, o.id());
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    expected.add(rs.getString(1));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        BrowserClient a = admin(o);
        assertThat(field(new ObjectList(allPages(a, o.id(), "members", null, 2)).node(), "membershipId")).isEqualTo(expected);
        assertThat(expected).hasSize(6);

        BrowserClient s = login(o, second);
        Got p1 = get(s, path(o.id(), "members", "limit=2"));
        assertThat(p1.status()).isEqualTo(200);
        revokeOrgAssignment(secondAdmin, o.adminMembership());
        assertError(get(s, path(o.id(), "members", "limit=2&cursor=" + p1.body().get("nextCursor").asString())), 403, "forbidden");
    }

    @Test
    void membersAuthenticationParametersAndPermission() {
        Org o = newOrganization();
        Org b = newOrganization();
        UUID w = newWorkspace(o, null);
        TestUser operator = internalUser(o, true, 2);
        assignWorkspaceRole(o.id(), membershipOf(o.id(), operator.id()), w, "OPERATOR", o.adminMembership());
        TestUser none = internalUser(o, true, 2);
        UUID client = newClient(o);
        TestUser ext = externalUser(o, client, null);
        BrowserClient anonymous = browser();
        assertError(get(anonymous, path(o.id(), "members", null)), 401, "unauthenticated");
        BrowserClient a = admin(o);
        assertError(get(a, path(b.id(), "members", null)), 401, "unauthenticated");
        assertError(get(login(o, operator), path(o.id(), "members", null)), 403, "forbidden");
        assertError(get(login(o, none), path(o.id(), "members", null)), 403, "forbidden");
        BrowserClient e = login(o, ext);
        assertError(get(e, path(o.id(), "members", null)), 403, "forbidden");
        assertError(get(e, path(o.id(), "members", "status=nada")), 400, "invalid_request");
        for (String q : List.of("status=nada", "status=", "status=active&status=active", "status=pending&status=active&status=suspended&status=revoked&status=active",
                "limit=0", "limit=101", "limit=x", "cursor=abc.def")) {
            assertError(get(a, path(o.id(), "members", q)), 400, "invalid_request");
        }
        // cursor: de outra rota (P02), com filtro trocado, de outro vinculo
        UUID m = o.adminMembership();
        String p02 = cursors.encode(new CursorCodec.Scope("listMyWorkspacePermissions", o.id(), m, null, CursorCodec.filters(), "workspaceId.asc"),
                List.of(UUID.randomUUID()));
        assertError(get(a, path(o.id(), "members", "cursor=" + p02)), 400, "invalid_request");
        for (int i = 0; i < 3; i++) {
            internalUser(o, true, 2);
        }
        String c = get(a, path(o.id(), "members", "limit=1&status=active")).body().get("nextCursor").asString();
        assertThat(get(a, path(o.id(), "members", "limit=1&status=active&cursor=" + c)).status()).as("controle positivo").isEqualTo(200);
        assertError(get(a, path(o.id(), "members", "limit=1&cursor=" + c)), 400, "invalid_request");
        assertError(get(a, path(o.id(), "members", "limit=1&status=active&status=pending&cursor=" + c)), 400, "invalid_request");
        TestUser second = internalUser(o, true, 2);
        assignOrgRole(o.id(), membershipOf(o.id(), second.id()), "ORG_ADMIN", o.adminMembership());
        assertError(get(login(o, second), path(o.id(), "members", "limit=1&status=active&cursor=" + c)), 400, "invalid_request");
    }

    // ================================================================== ROTA-M02 /client-grants

    private record GrantScene(Org o, Org b, UUID c1, UUID c2, UUID w1, UUID w2, UUID g1, UUID g2, UUID g3Revoked, UUID g4Pending, UUID bClient,
                              UUID bWorkspace) {
    }

    /**
     * Concessao inserida com created_at escolhido (a trigger da 003 torna a origem, inclusive created_at, imutavel depois).
     * {@code active}: aprovada (2) com revisao; senao pendente (1), sem aprovacao.
     */
    private static UUID grant(Org o, UUID client, UUID workspace, boolean active, String createdAt) {
        return grant(o, UUID.randomUUID(), client, workspace, active, createdAt);
    }

    private static UUID grant(Org o, UUID grantId, UUID client, UUID workspace, boolean active, String createdAt) {
        UUID cm = AccessFixtures.clientMembershipOf(o.id(), externalUser(o, client, null).id());
        return UUID.fromString(AccessFixtures.value("INSERT INTO client_workspace_grants (grant_id, organization_id, client_id, client_membership_id, "
                + "workspace_id, status, approved_by_membership_id, approved_at, starts_at, next_review_at, created_at) VALUES ('" + grantId + "', '"
                + o.id() + "', '" + client + "', '" + cm + "', '" + workspace + "', " + (active ? "2, '" + o.adminMembership() + "', now(), now(), now() + interval '30 days'"
                : "1, NULL, NULL, now(), NULL") + ", " + (createdAt == null ? "now()" : "'" + createdAt + "'") + ") RETURNING grant_id"));
    }

    private static GrantScene grantScene() {
        return grantScene(null);
    }

    /** {@code createdAt} nao nulo: todas as concessoes de A com o mesmo instante (desempate so pelo id). */
    private static GrantScene grantScene(String createdAt) {
        Org o = newOrganization();
        Org b = newOrganization();
        UUID c1 = newClient(o);
        UUID c2 = newClient(o);
        UUID w1 = newWorkspace(o, c1);
        UUID w2 = newWorkspace(o, c2);
        UUID g1 = grant(o, c1, w1, true, createdAt);
        UUID g2 = grant(o, c2, w2, true, createdAt);
        UUID g3 = grant(o, c1, w1, true, createdAt);
        DB.asSuperuser("UPDATE client_workspace_grants SET status = 4, revoked_at = now(), revoked_by_membership_id = '" + o.adminMembership()
                + "' WHERE grant_id = '" + g3 + "'");
        UUID g4 = grant(o, c1, w1, false, createdAt);
        UUID bc = newClient(b);
        UUID bw = newWorkspace(b, bc);
        externalUser(b, bc, bw);
        return new GrantScene(o, b, c1, c2, w1, w2, g1, g2, g3, g4, bc, bw);
    }

    @Test
    void clientGrantsFieldsNullabilityAndFilters() {
        GrantScene s = grantScene();
        BrowserClient a = admin(s.o());
        Got g = get(a, path(s.o().id(), "client-grants", null));
        assertThat(g.status()).isEqualTo(200);
        JsonNode items = g.body().get("items");
        assertThat(field(items, "grantId")).as("so as concessoes de A").containsExactlyInAnyOrder(s.g1().toString(), s.g2().toString(),
                s.g3Revoked().toString(), s.g4Pending().toString());
        Map<String, JsonNode> byId = new java.util.HashMap<>();
        items.forEach(x -> byId.put(x.get("grantId").asString(), x));
        JsonNode active = byId.get(s.g1().toString());
        assertThat(keys(active)).containsExactly("grantId", "status", "client", "workspace", "externalAccount", "approvedAt", "startsAt", "expiresAt",
                "nextReviewAt", "lastAccessAt", "revokedAt", "createdAt");
        assertThat(keys(active.get("externalAccount"))).containsExactly("clientMembershipId", "userId", "displayName", "email", "membershipStatus");
        assertThat(active.get("status").asString()).isEqualTo("active");
        assertThat(active.get("client").get("clientId").asString()).isEqualTo(s.c1().toString());
        assertThat(active.get("workspace").get("workspaceId").asString()).isEqualTo(s.w1().toString());
        assertThat(active.get("externalAccount").get("membershipStatus").asString()).isEqualTo("active");
        assertThat(active.get("approvedAt").isString()).isTrue();
        assertThat(active.get("nextReviewAt").isString()).isTrue();
        for (String f : List.of("expiresAt", "lastAccessAt", "revokedAt")) {
            assertThat(active.get(f).isNull()).as(f + " nulo e presente").isTrue();
        }
        JsonNode revoked = byId.get(s.g3Revoked().toString());
        assertThat(revoked.get("status").asString()).isEqualTo("revoked");
        assertThat(revoked.get("revokedAt").isString()).isTrue();
        JsonNode pending = byId.get(s.g4Pending().toString());
        assertThat(pending.get("status").asString()).isEqualTo("pending");
        assertThat(pending.get("approvedAt").isNull()).isTrue();
        assertThat(pending.get("nextReviewAt").isNull()).isTrue();

        UUID o = s.o().id();
        assertThat(field(get(a, path(o, "client-grants", "status=revoked")).body().get("items"), "grantId")).containsExactly(s.g3Revoked().toString());
        assertThat(field(get(a, path(o, "client-grants", "clientId=" + s.c2())).body().get("items"), "grantId")).containsExactly(s.g2().toString());
        assertThat(field(get(a, path(o, "client-grants", "workspaceId=" + s.w1())).body().get("items"), "grantId"))
                .containsExactlyInAnyOrder(s.g1().toString(), s.g3Revoked().toString(), s.g4Pending().toString());
        assertThat(field(get(a, path(o, "client-grants", "clientId=" + s.c1() + "&status=active")).body().get("items"), "grantId"))
                .containsExactly(s.g1().toString());
    }

    @Test
    void clientGrantsFilterExceptionDc05GivesEmptyListWithPositiveControls() {
        GrantScene s = grantScene();
        BrowserClient a = admin(s.o());
        UUID o = s.o().id();
        assertThat(get(a, path(o, "client-grants", "clientId=" + s.c1())).body().get("items").size()).as("controle positivo").isEqualTo(3);
        assertThat(get(a, path(o, "client-grants", "workspaceId=" + s.w2())).body().get("items").size()).as("controle positivo").isEqualTo(1);
        for (String q : List.of("clientId=" + s.bClient(), "clientId=" + UUID.randomUUID(), "workspaceId=" + s.bWorkspace(),
                "workspaceId=" + UUID.randomUUID(), "clientId=" + s.c1() + "&workspaceId=" + s.w2())) {
            Got g = get(a, path(o, "client-grants", q));
            assertThat(g.status()).as(q + ": excecao DC-05 (nao 404)").isEqualTo(200);
            assertThat(g.body().get("items").size()).as(q).isZero();
            assertThat(g.body().get("nextCursor").isNull()).isTrue();
        }
        assertError(get(a, path(o, "client-grants", "clientId=nao-e-uuid")), 400, "invalid_request");
        assertError(get(a, path(o, "client-grants", "workspaceId=123")), 400, "invalid_request");
    }

    @Test
    void clientGrantsPaginationDescendingPermissionAndRevocationBetweenPages() {
        GrantScene s = grantScene("2026-01-01T00:00:00Z");
        List<String> expected = new ArrayList<>(List.of(s.g1().toString(), s.g2().toString(), s.g3Revoked().toString(), s.g4Pending().toString()));
        expected.sort(java.util.Comparator.reverseOrder()); // desc pelo texto minusculo = ordem do PostgreSQL
        BrowserClient a = admin(s.o());
        assertThat(field(new ObjectList(allPages(a, s.o().id(), "client-grants", null, 3)).node(), "grantId")).isEqualTo(expected);
        assertThat(field(new ObjectList(allPages(a, s.o().id(), "client-grants", null, 1)).node(), "grantId")).isEqualTo(expected);
        String c = get(a, path(s.o().id(), "client-grants", "limit=1")).body().get("nextCursor").asString();
        assertError(get(a, path(s.o().id(), "client-grants", "limit=1&status=active&cursor=" + c)), 400, "invalid_request");
        assertError(get(a, path(s.o().id(), "client-grants", "limit=1&clientId=" + s.c1() + "&cursor=" + c)), 400, "invalid_request");

        UUID w = newWorkspace(s.o(), null);
        TestUser operator = internalUser(s.o(), true, 2);
        assignWorkspaceRole(s.o().id(), membershipOf(s.o().id(), operator.id()), w, "WORKSPACE_ADMIN", s.o().adminMembership());
        assertError(get(login(s.o(), operator), path(s.o().id(), "client-grants", null)), 403, "forbidden");
        TestUser ext = externalUser(s.o(), s.c1(), null);
        assertError(get(login(s.o(), ext), path(s.o().id(), "client-grants", null)), 403, "forbidden");
        assertError(get(browser(), path(s.o().id(), "client-grants", null)), 401, "unauthenticated");

        TestUser second = internalUser(s.o(), true, 2);
        UUID assignment = assignOrgRole(s.o().id(), membershipOf(s.o().id(), second.id()), "ORG_ADMIN", s.o().adminMembership());
        BrowserClient n = login(s.o(), second);
        Got p1 = get(n, path(s.o().id(), "client-grants", "limit=1"));
        assertThat(p1.status()).isEqualTo(200);
        revokeOrgAssignment(assignment, s.o().adminMembership());
        assertError(get(n, path(s.o().id(), "client-grants", "limit=1&cursor=" + p1.body().get("nextCursor").asString())), 403, "forbidden");
    }

    // ================================================================== ordenacao composta por data (parecer da GAP-03)

    /**
     * Instantes atribuidos aos itens em ordem CRESCENTE de id: as datas DECRESCEM com o id (ordem do id contraria a da data),
     * com dois empates e passos de 1 microssegundo.
     */
    private static final List<String> INSTANTS_BY_ASCENDING_ID = List.of("2026-03-01T00:00:00.000003Z", "2026-03-01T00:00:00.000003Z",
            "2026-03-01T00:00:00.000002Z", "2026-03-01T00:00:00.000001Z", "2026-02-01T00:00:00Z", "2026-02-01T00:00:00Z", "2026-01-01T00:00:00Z");

    private record Dated(String id, java.time.Instant at) {
    }

    /** Ordem esperada por (data, id); id comparado pelo texto minusculo = ordem de bytes do PostgreSQL. */
    private static List<String> expectedOrder(List<Dated> items, boolean descending) {
        java.util.Comparator<Dated> c = java.util.Comparator.comparing(Dated::at).thenComparing(Dated::id);
        return items.stream().sorted(descending ? c.reversed() : c).map(Dated::id).toList();
    }

    private static void assertDatasetDistinguishesTheKeys(List<Dated> items, List<String> expected) {
        List<String> byIdOnly = items.stream().map(Dated::id).sorted().toList();
        assertThat(expected).as("a massa distingue a chave principal: a ordem esperada nao e a do id").isNotEqualTo(byIdOnly)
                .isNotEqualTo(byIdOnly.reversed());
        assertThat(items.stream().map(Dated::at).distinct().count()).as("ha empates de data").isLessThan(items.size());
    }

    @Test
    void membersOrderByCreatedAtThenIdAscendingWithMicrosecondsAndTies() {
        Org o = newOrganization();
        for (int i = 0; i < 6; i++) {
            internalUser(o, true, 2);
        }
        List<String> ids = new ArrayList<>();
        try (var c = DB.connectAsSuperuser();
             var ps = c.prepareStatement("SELECT membership_id::text FROM organization_memberships WHERE organization_id = ? ORDER BY membership_id")) {
            ps.setObject(1, o.id());
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(ids).hasSize(INSTANTS_BY_ASCENDING_ID.size());
        List<Dated> items = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            DB.asSuperuser("UPDATE organization_memberships SET created_at = '" + INSTANTS_BY_ASCENDING_ID.get(i) + "' WHERE membership_id = '"
                    + ids.get(i) + "'");
            items.add(new Dated(ids.get(i), java.time.Instant.parse(INSTANTS_BY_ASCENDING_ID.get(i))));
        }
        List<String> expected = expectedOrder(items, false);
        assertDatasetDistinguishesTheKeys(items, expected);
        BrowserClient a = admin(o);
        for (int limit : List.of(1, 2, 3, 7)) {
            List<JsonNode> got = allPages(a, o.id(), "members", null, limit);
            assertThat(field(new ObjectList(got).node(), "membershipId")).as("data e id ascendentes, limit " + limit).isEqualTo(expected);
            for (JsonNode x : got) {
                java.time.Instant at = java.time.OffsetDateTime.parse(x.get("createdAt").asString()).toInstant();
                assertThat(items).as("createdAt com microssegundos").contains(new Dated(x.get("membershipId").asString(), at));
            }
        }
    }

    @Test
    void clientGrantsOrderByCreatedAtThenIdDescendingWithMicrosecondsAndTies() {
        Org o = newOrganization();
        UUID c = newClient(o);
        UUID w = newWorkspace(o, c);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < INSTANTS_BY_ASCENDING_ID.size(); i++) {
            ids.add(UUID.randomUUID().toString());
        }
        ids.sort(null);
        List<Dated> items = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            grant(o, UUID.fromString(ids.get(i)), c, w, true, INSTANTS_BY_ASCENDING_ID.get(i));
            items.add(new Dated(ids.get(i), java.time.Instant.parse(INSTANTS_BY_ASCENDING_ID.get(i))));
        }
        List<String> expected = expectedOrder(items, true);
        assertDatasetDistinguishesTheKeys(items, expected);
        BrowserClient a = admin(o);
        for (int limit : List.of(1, 2, 3, 7)) {
            List<JsonNode> got = allPages(a, o.id(), "client-grants", null, limit);
            assertThat(field(new ObjectList(got).node(), "grantId")).as("data e id descendentes, limit " + limit).isEqualTo(expected);
            for (JsonNode x : got) {
                java.time.Instant at = java.time.OffsetDateTime.parse(x.get("createdAt").asString()).toInstant();
                assertThat(items).as("createdAt com microssegundos").contains(new Dated(x.get("grantId").asString(), at));
            }
        }
    }

    // ================================================================== ROTA-M03 /clients

    /** UUID com o primeiro octeto escolhido: fixa a ordem de desempate pelo id (ordem de bytes do PostgreSQL). */
    private static UUID id(int firstByte) {
        String r = UUID.randomUUID().toString();
        return UUID.fromString(String.format("%02x", firstByte) + r.substring(2));
    }

    @Test
    void clientsBinaryOrderTiesLongNamesAndFields() {
        Org o = newOrganization();
        Org b = newOrganization();
        String p48 = "P".repeat(48);
        UUID zeta = AccessFixtures.namedClient(o.id(), id(0x10), "Zeta", 1);
        UUID alpha = AccessFixtures.namedClient(o.id(), id(0x11), "alpha", 1);
        UUID abaco = AccessFixtures.namedClient(o.id(), id(0x12), "Ábaco", 3);
        UUID beta = AccessFixtures.namedClient(o.id(), id(0x13), "Beta", 2);
        UUID betaLower = AccessFixtures.namedClient(o.id(), id(0x14), "beta", 1);
        UUID igualB = AccessFixtures.namedClient(o.id(), id(0xb0), "Igual", 1);
        UUID igualA = AccessFixtures.namedClient(o.id(), id(0xa0), "Igual", 1);
        // mesmos 48 primeiros pontos de codigo: ordem pelo id, NAO pelo restante do nome (semantica documentada, §1.5.1)
        UUID longZzzSmallId = AccessFixtures.namedClient(o.id(), id(0x20), p48 + "zzz", 1);
        UUID longAaaBigId = AccessFixtures.namedClient(o.id(), id(0xf0), p48 + "aaa", 1);
        AccessFixtures.namedClient(b.id(), id(0x01), "Outra organizacao", 1);
        List<String> expected = List.of(beta, igualA, igualB, longZzzSmallId, longAaaBigId, zeta, alpha, betaLower, abaco).stream()
                .map(UUID::toString).toList();

        BrowserClient a = admin(o);
        Got g = get(a, path(o.id(), "clients", null));
        assertThat(g.status()).isEqualTo(200);
        assertThat(field(g.body().get("items"), "clientId")).as("binaria: maiusculas, minusculas, acento; desempate por id").isEqualTo(expected);
        JsonNode first = g.body().get("items").get(0);
        assertThat(keys(first)).containsExactly("clientId", "name", "status", "createdAt", "updatedAt");
        assertThat(first.get("status").asString()).isEqualTo("suspended");
        assertThat(g.body().get("items").get(3).get("name").asString()).as("nome completo na resposta").isEqualTo(p48 + "zzz");
        for (int limit : List.of(1, 2, 4)) {
            assertThat(field(new ObjectList(allPages(a, o.id(), "clients", null, limit)).node(), "clientId")).as("limit " + limit).isEqualTo(expected);
        }
        assertThat(field(get(a, path(o.id(), "clients", "status=archived")).body().get("items"), "clientId")).containsExactly(abaco.toString());
        assertThat(get(a, path(o.id(), "clients", "status=active&status=suspended")).body().get("items").size()).isEqualTo(8);
    }

    @Test
    void clientsLongUnicodeNamesAtTheEndOfAPageContinueWithoutError() {
        Org o = newOrganization();
        List<String> names = List.of("a".repeat(1000), "b" + "\u0001".repeat(999), "c" + "😀".repeat(999), "d" + "\"\\".repeat(499),
                "e" + "中".repeat(999), "f");
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            expected.add(AccessFixtures.namedClient(o.id(), id(0x30 + i), names.get(i), 1).toString());
        }
        BrowserClient a = admin(o);
        String cursor = null;
        List<String> seen = new ArrayList<>();
        int largest = 0;
        for (int page = 0; page < 10; page++) {
            Got g = get(a, path(o.id(), "clients", "limit=1" + (cursor == null ? "" : "&cursor=" + cursor)));
            assertThat(g.status()).as("pagina " + page).isEqualTo(200);
            seen.addAll(field(g.body().get("items"), "clientId"));
            assertThat(g.body().get("items").get(0).get("name").asString()).as("nome completo").isEqualTo(names.get(page));
            if (g.body().get("nextCursor").isNull()) {
                break;
            }
            cursor = g.body().get("nextCursor").asString();
            largest = Math.max(largest, cursor.length());
        }
        assertThat(seen).isEqualTo(expected);
        assertThat(largest).as("cursor emitido com nome longo no fim da pagina").isLessThanOrEqualTo(872);
    }

    @Test
    void clientsConcurrentChangesFollowTheDocumentedSemantics() {
        // renomear para ANTES do cursor: omitido (documentado)
        Org o1 = newOrganization();
        UUID b = AccessFixtures.namedClient(o1.id(), id(0x40), "B", 1);
        UUID d = AccessFixtures.namedClient(o1.id(), id(0x41), "D", 1);
        UUID f = AccessFixtures.namedClient(o1.id(), id(0x42), "F", 1);
        UUID h = AccessFixtures.namedClient(o1.id(), id(0x43), "H", 1);
        BrowserClient a1 = admin(o1);
        Got p1 = get(a1, path(o1.id(), "clients", "limit=2"));
        assertThat(field(p1.body().get("items"), "clientId")).containsExactly(b.toString(), d.toString());
        AccessFixtures.renameClient(h, "A");
        Got p2 = get(a1, path(o1.id(), "clients", "limit=2&cursor=" + p1.body().get("nextCursor").asString()));
        assertThat(p2.status()).as("mudanca de dados que nao retira autorizacao: sem erro").isEqualTo(200);
        assertThat(field(p2.body().get("items"), "clientId")).as("H renomeado para antes do cursor: omitido").containsExactly(f.toString());
        // renomear para DEPOIS do cursor: aparece (repeticao documentada)
        AccessFixtures.renameClient(b, "G");
        Got p2b = get(a1, path(o1.id(), "clients", "limit=2&cursor=" + p1.body().get("nextCursor").asString()));
        assertThat(field(p2b.body().get("items"), "clientId")).as("B renomeado para depois: aparece de novo").containsExactly(f.toString(), b.toString());

        // mudar so depois do 48o ponto de codigo: posicao inalterada; aparece uma vez, com o nome novo
        Org o2 = newOrganization();
        String k = "K".repeat(48);
        UUID first = AccessFixtures.namedClient(o2.id(), id(0x50), "A", 1);
        UUID kk = AccessFixtures.namedClient(o2.id(), id(0x51), k + "x", 1);
        UUID last = AccessFixtures.namedClient(o2.id(), id(0x52), "Z", 1);
        BrowserClient a2 = admin(o2);
        Got q1 = get(a2, path(o2.id(), "clients", "limit=1"));
        assertThat(field(q1.body().get("items"), "clientId")).containsExactly(first.toString());
        AccessFixtures.renameClient(kk, k + "y");
        Got q2 = get(a2, path(o2.id(), "clients", "limit=1&cursor=" + q1.body().get("nextCursor").asString()));
        assertThat(q2.body().get("items").get(0).get("name").asString()).isEqualTo(k + "y");
        Got q3 = get(a2, path(o2.id(), "clients", "limit=1&cursor=" + q2.body().get("nextCursor").asString()));
        assertThat(field(q3.body().get("items"), "clientId")).containsExactly(last.toString());
        assertThat(q3.body().get("nextCursor").isNull()).isTrue();

        // mudar o status com filtro de status: sai do filtro
        Org o3 = newOrganization();
        UUID x1 = AccessFixtures.namedClient(o3.id(), id(0x60), "X1", 1);
        UUID x2 = AccessFixtures.namedClient(o3.id(), id(0x61), "X2", 1);
        UUID x3 = AccessFixtures.namedClient(o3.id(), id(0x62), "X3", 1);
        BrowserClient a3 = admin(o3);
        Got r1 = get(a3, path(o3.id(), "clients", "status=active&limit=1"));
        assertThat(field(r1.body().get("items"), "clientId")).containsExactly(x1.toString());
        DB.asSuperuser("UPDATE clients SET status = 3 WHERE client_id = '" + x2 + "'");
        Got r2 = get(a3, path(o3.id(), "clients", "status=active&limit=1&cursor=" + r1.body().get("nextCursor").asString()));
        assertThat(field(r2.body().get("items"), "clientId")).as("arquivado saiu do filtro").containsExactly(x3.toString());
    }

    @Test
    void clientsAuthenticationParametersPermissionAndEmptyOrganization() {
        Org o = newOrganization();
        Org b = newOrganization();
        AccessFixtures.namedClient(b.id(), UUID.randomUUID(), "Cliente de B", 1);
        UUID w = newWorkspace(o, null);
        TestUser operator = internalUser(o, true, 2);
        assignWorkspaceRole(o.id(), membershipOf(o.id(), operator.id()), w, "OPERATOR", o.adminMembership());
        TestUser none = internalUser(o, true, 2);
        BrowserClient a = admin(o);
        Got empty = get(a, path(o.id(), "clients", null));
        assertThat(empty.status()).isEqualTo(200);
        assertThat(empty.body().get("items").size()).as("organizacao sem clientes; os de B ausentes").isZero();
        assertThat(empty.body().get("nextCursor").isNull()).isTrue();
        assertError(get(login(o, operator), path(o.id(), "clients", null)), 403, "forbidden");
        assertError(get(login(o, none), path(o.id(), "clients", null)), 403, "forbidden");
        UUID client = newClient(o);
        TestUser ext = externalUser(o, client, null);
        BrowserClient e = login(o, ext);
        assertError(get(e, path(o.id(), "clients", null)), 403, "forbidden");
        assertError(get(e, path(o.id(), "clients", "limit=0")), 400, "invalid_request");
        assertError(get(browser(), path(o.id(), "clients", null)), 401, "unauthenticated");
        assertError(get(a, path(b.id(), "clients", null)), 401, "unauthenticated");
        for (String q : List.of("status=revoked", "status=", "status=active&status=suspended&status=archived&status=active", "limit=101", "cursor=x.y")) {
            assertError(get(a, path(o.id(), "clients", q)), 400, "invalid_request");
        }
        assertThat(get(a, path(o.id(), "clients", null)).body().get("items").size()).as("controle positivo").isEqualTo(1);
    }

    /** §5.5 para a M03: perder a permissao organizacional entre paginas -> 403 com o cursor ainda valido. */
    @Test
    void clientsRevocationBetweenPagesGives403() {
        Org o = newOrganization();
        for (int i = 0; i < 3; i++) {
            AccessFixtures.namedClient(o.id(), UUID.randomUUID(), "Cliente " + i, 1);
        }
        TestUser second = internalUser(o, true, 2);
        UUID assignment = assignOrgRole(o.id(), membershipOf(o.id(), second.id()), "ORG_ADMIN", o.adminMembership());
        BrowserClient s = login(o, second);
        Got p1 = get(s, path(o.id(), "clients", "limit=1"));
        assertThat(p1.status()).as(diag(p1)).isEqualTo(200);
        String cursor = p1.body().get("nextCursor").asString();
        Got control = get(s, path(o.id(), "clients", "limit=1&cursor=" + cursor));
        assertThat(control.status()).as("controle positivo: o mesmo cursor vale antes da revogacao " + diag(control)).isEqualTo(200);
        // a primeira administradora (bootstrap) continua: a regra do ultimo ORG_ADMIN da 003 e preservada
        revokeOrgAssignment(assignment, o.adminMembership());
        assertError(get(s, path(o.id(), "clients", "limit=1&cursor=" + cursor)), 403, "forbidden");
        assertThat(get(admin(o), path(o.id(), "clients", "limit=1")).status()).as("a outra administradora segue com acesso").isEqualTo(200);
    }

    /** Adaptador: lista de nos -> no array (para reutilizar {@link #field}). */
    private record ObjectList(List<JsonNode> list) {
        JsonNode node() {
            tools.jackson.databind.node.ArrayNode a = JSON.createArrayNode();
            list.forEach(a::add);
            return a;
        }
    }
}
