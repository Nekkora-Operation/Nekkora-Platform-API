package com.nekkora.platform;

import static com.nekkora.platform.AccessFixtures.assignOrgRole;
import static com.nekkora.platform.AccessFixtures.assignWorkspaceRole;
import static com.nekkora.platform.AccessFixtures.membershipOf;
import static com.nekkora.platform.AccessFixtures.revokeWorkspaceAssignment;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.core.testsupport.BrowserClient;

import tools.jackson.databind.JsonNode;

/**
 * GAP-06, §5.2: a MESMA tabela de visibilidade (tabela V, derivada da §1.2) aplicada as OITO rotas de leitura. A
 * expectativa de cada ator x rota x recurso sai da tabela, e NAO e uma resposta unica para a matriz inteira:
 * - listas sem workspace no caminho (L01 e L06 sem filtro): so os itens visiveis; sem nenhum visivel -> 200 com lista vazia;
 * - detalhes e recursos subordinados (L02, L03, L04, L05, L07, L08) e filtros da L06: 200 se visivel, senao 404 com corpo
 *   identico para "sem acesso", "de outra organizacao" e "inexistente";
 * - conta externa: 403 em todas; sem sessao ou sessao de outra organizacao: 401 em todas.
 */
class ProductReadMatrixIT extends ReadRoutesSupport {

    private record Res(UUID workspace, UUID automation, UUID execution) {
    }

    @Test
    void visibilityTableVAcrossTheEightRoutes() {
        Org o = newOrganization();
        Org b = newOrganization();
        ProductFixtures.unlimited(o.id(), o.adminId());
        ProductFixtures.unlimited(b.id(), b.adminId());
        Map<String, Res> res = new LinkedHashMap<>();
        for (String w : List.of("W1", "W2", "W3")) {
            res.put(w, resource(o, AccessFixtures.namedWorkspace(o.id(), w, 1)));
        }
        DB.asSuperuser("UPDATE workspaces SET status = 3 WHERE workspace_id = '" + res.get("W3").workspace() + "'"); // arquivado depois da massa
        res.put("B", resource(b, AccessFixtures.namedWorkspace(b.id(), "WB", 1)));
        Res missing = new Res(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        res.put("inexistente", missing);
        UUID w1 = res.get("W1").workspace();
        UUID w2 = res.get("W2").workspace();
        UUID w3 = res.get("W3").workspace();

        TestUser admin2 = internalUser(o, true, 2);
        assignOrgRole(o.id(), membershipOf(o.id(), admin2.id()), "ORG_ADMIN", o.adminMembership());
        assignWorkspaceRole(o.id(), membershipOf(o.id(), admin2.id()), w3, "VIEWER", o.adminMembership());
        TestUser wsAdmin = internalUser(o, true, 2);
        assignWorkspaceRole(o.id(), membershipOf(o.id(), wsAdmin.id()), w1, "WORKSPACE_ADMIN", o.adminMembership());
        TestUser operator = internalUser(o, true, 2);
        assignWorkspaceRole(o.id(), membershipOf(o.id(), operator.id()), w1, "OPERATOR", o.adminMembership());
        TestUser viewer = internalUser(o, true, 2);
        assignWorkspaceRole(o.id(), membershipOf(o.id(), viewer.id()), w2, "VIEWER", o.adminMembership());
        TestUser revoked = internalUser(o, true, 2);
        revokeWorkspaceAssignment(assignWorkspaceRole(o.id(), membershipOf(o.id(), revoked.id()), w1, "OPERATOR", o.adminMembership()),
                o.adminMembership());
        TestUser none = internalUser(o, true, 2);

        // tabela V (§5.2): ator -> workspaces visiveis
        Map<String, Object[]> table = new LinkedHashMap<>();
        table.put("ORG_ADMIN sem atribuicao de workspace", new Object[] {admin(o), Set.of("W1", "W2", "W3")});
        table.put("ORG_ADMIN + VIEWER explicito em W3", new Object[] {login(o, admin2), Set.of("W1", "W2", "W3")});
        table.put("WORKSPACE_ADMIN em W1", new Object[] {login(o, wsAdmin), Set.of("W1")});
        table.put("OPERATOR em W1", new Object[] {login(o, operator), Set.of("W1")});
        table.put("VIEWER em W2", new Object[] {login(o, viewer), Set.of("W2")});
        table.put("atribuicao revogada em W1", new Object[] {login(o, revoked), Set.of()});
        table.put("sem papeis", new Object[] {login(o, none), Set.of()});
        for (Map.Entry<String, Object[]> e : table.entrySet()) {
            BrowserClient n = (BrowserClient) e.getValue()[0];
            @SuppressWarnings("unchecked")
            Set<String> visible = (Set<String>) e.getValue()[1];
            checkActor(e.getKey(), n, o.id(), res, visible);
        }

        // conta externa: 403 em todas as rotas; sem sessao e sessao de outra organizacao: 401 em todas
        UUID client = newClient(o);
        TestUser ext = externalUser(o, client, null);
        BrowserClient external = login(o, ext);
        BrowserClient anonymous = browser();
        BrowserClient otherOrg = admin(b);
        for (String route : routes(res.get("W1"))) {
            assertError(get(external, path(o.id(), route, null)), 403, "forbidden");
            assertError(get(anonymous, path(o.id(), route, null)), 401, "unauthenticated");
            assertError(get(otherOrg, path(o.id(), route, null)), 401, "unauthenticated");
        }
    }

    private static List<String> routes(Res r) {
        return List.of("workspaces", "workspaces/" + r.workspace(), "workspaces/" + r.workspace() + "/automations", "automations/" + r.automation(),
                "automations/" + r.automation() + "/versions", "executions", "executions/" + r.execution(), "executions/" + r.execution() + "/events");
    }

    private Res resource(Org org, UUID workspace) {
        UUID a = ProductFixtures.automation(org.id(), workspace, null, "Automacao", org.adminMembership());
        UUID v = ProductFixtures.version(org.id(), workspace, a, true, org.adminMembership());
        UUID x = ProductFixtures.execution(org.id(), workspace, a, v, null, org.adminMembership());
        return new Res(workspace, a, x);
    }

    private static void checkActor(String actor, BrowserClient n, UUID org, Map<String, Res> res, Set<String> visible) {
        String as = actor + ": ";
        Set<String> expectedWorkspaces = new TreeSet<>();
        Set<String> expectedExecutions = new TreeSet<>();
        for (String k : visible) {
            expectedWorkspaces.add(res.get(k).workspace().toString());
            expectedExecutions.add(res.get(k).execution().toString());
        }
        // listas sem workspace no caminho: so os visiveis; nenhum -> 200 com lista vazia
        Got l01 = get(n, path(org, "workspaces", null));
        assertOk(l01, as + "L01");
        assertThat(new TreeSet<>(field(l01.body().get("items"), "workspaceId"))).as(as + "L01 itens").isEqualTo(expectedWorkspaces);
        Got l06 = get(n, path(org, "executions", null));
        assertOk(l06, as + "L06");
        assertThat(new TreeSet<>(field(l06.body().get("items"), "executionId"))).as(as + "L06 itens").isEqualTo(expectedExecutions);

        for (Map.Entry<String, Res> e : res.entrySet()) {
            Res r = e.getValue();
            boolean ok = visible.contains(e.getKey());
            String where = as + e.getKey() + " ";
            expect(get(n, path(org, "workspaces/" + r.workspace(), null)), ok, where + "L02", r.workspace().toString(), "workspaceId");
            Got l03 = get(n, path(org, "workspaces/" + r.workspace() + "/automations", null));
            expectList(l03, ok, where + "L03", r.automation().toString(), "automationId");
            expect(get(n, path(org, "automations/" + r.automation(), null)), ok, where + "L04", r.automation().toString(), "automationId");
            Got l05 = get(n, path(org, "automations/" + r.automation() + "/versions", null));
            if (ok) {
                assertOk(l05, where + "L05");
                assertThat(l05.body().get("items").size()).as(where + "L05").isEqualTo(1);
            } else {
                assertError(l05, 404, "not_found");
            }
            expectList(get(n, path(org, "executions", "workspaceId=" + r.workspace())), ok, where + "L06 workspaceId", r.execution().toString(),
                    "executionId");
            expectList(get(n, path(org, "executions", "automationId=" + r.automation())), ok, where + "L06 automationId", r.execution().toString(),
                    "executionId");
            expect(get(n, path(org, "executions/" + r.execution(), null)), ok, where + "L07", r.execution().toString(), "executionId");
            Got l08 = get(n, path(org, "executions/" + r.execution() + "/events", null));
            if (ok) {
                assertOk(l08, where + "L08");
                assertThat(l08.body().get("items").size()).as(where + "L08 sem eventos").isZero();
            } else {
                assertError(l08, 404, "not_found");
            }
        }
    }

    private static void expect(Got g, boolean ok, String what, String id, String idField) {
        if (ok) {
            assertOk(g, what);
            assertThat(g.body().get(idField).asString()).as(what).isEqualTo(id);
        } else {
            assertError(g, 404, "not_found");
            assertThat(keys(g.body())).as(what + ": corpo identico do 404").containsExactly("error");
        }
    }

    private static void expectList(Got g, boolean ok, String what, String id, String idField) {
        if (ok) {
            assertOk(g, what);
            JsonNode items = g.body().get("items");
            assertThat(field(items, idField)).as(what).containsExactly(id);
        } else {
            assertError(g, 404, "not_found");
            assertThat(keys(g.body())).as(what + ": corpo identico do 404").containsExactly("error");
        }
    }
}
