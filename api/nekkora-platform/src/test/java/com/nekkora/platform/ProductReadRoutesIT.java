package com.nekkora.platform;

import static com.nekkora.platform.AccessFixtures.assignOrgRole;
import static com.nekkora.platform.AccessFixtures.assignWorkspaceRole;
import static com.nekkora.platform.AccessFixtures.membershipOf;
import static com.nekkora.platform.AccessFixtures.revokeOrgAssignment;
import static com.nekkora.platform.AccessFixtures.revokeWorkspaceAssignment;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.platform.paging.CursorCodec;

import tools.jackson.databind.JsonNode;

/**
 * GAP-06 (ROTA-L01 a L08; DC-08 a DC-12): campos e exclusoes, solicitante (manual x agendada), durationMs, eventos, periodo
 * e filtros da L06, ordenacao composta por data com empates e microssegundos, nomes longos, mudanca concorrente,
 * revogacao entre paginas e acesso direto, cursores entre rotas paginadas reais e a comparacao DC-05 x DC-12. A matriz de
 * visibilidade das oito rotas esta em ProductReadMatrixIT.
 */
class ProductReadRoutesIT extends ReadRoutesSupport {

    @Autowired
    private CursorCodec cursors;

    private static final List<String> FORBIDDEN_KEYS = List.of("commandJson", "command_json", "command", "commandSnapshot", "payload", "errorSummary",
            "error_summary", "pid", "exitCode", "exit_code", "worker", "workerId", "agent", "agentId", "createdBy", "createdByMembershipId", "requestKey",
            "requestFingerprint", "authorizedBy");

    private static void assertNoForbiddenKeys(JsonNode n, String what) {
        assertThat(keys(n)).as(what + ": campos excluidos (DC-08..DC-11)").doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
    }

    private static UUID id(int firstByte) {
        String r = UUID.randomUUID().toString();
        return UUID.fromString(String.format("%02x", firstByte) + r.substring(2));
    }

    // ================================================================== campos, exclusoes, solicitante, durationMs, eventos

    @Test
    void fieldsExclusionsRequesterDurationAndEvents() {
        Org o = newOrganization();
        ProductFixtures.unlimited(o.id(), o.adminId());
        UUID client = newClient(o);
        UUID wInternal = AccessFixtures.namedWorkspace(o.id(), "Interno", 1);
        UUID wClient = newWorkspace(o, client);
        UUID m = o.adminMembership();
        UUID a = ProductFixtures.automation(o.id(), wInternal, null, "Principal", m);
        UUID v1 = ProductFixtures.version(o.id(), wInternal, a, true, m);
        UUID v2 = ProductFixtures.version(o.id(), wInternal, a, true, m);
        ProductFixtures.version(o.id(), wInternal, a, false, m); // v3 rascunho posterior
        UUID draftOnly = ProductFixtures.automation(o.id(), wInternal, null, "So rascunho", m);
        ProductFixtures.version(o.id(), wInternal, draftOnly, false, m);

        UUID done = ProductFixtures.execution(o.id(), wInternal, a, v2, null, m);
        ProductFixtures.Runtime rt = ProductFixtures.start(o.id(), wInternal, done, m);
        ProductFixtures.succeed(done, "1.234567 seconds");
        for (int s = 1; s <= 5; s++) {
            ProductFixtures.event(o.id(), wInternal, done, rt, s, s % 2 == 1 ? 1 : 2);
        }
        UUID running = ProductFixtures.execution(o.id(), wInternal, a, v2, null, m);
        ProductFixtures.start(o.id(), wInternal, running, m);
        UUID pending = ProductFixtures.execution(o.id(), wInternal, a, v1, null, m, done);
        UUID cancelled = ProductFixtures.execution(o.id(), wInternal, a, v2, null, m);
        ProductFixtures.cancelBeforeDispatch(cancelled, m);
        UUID scheduled = ProductFixtures.scheduledExecution(o.id(), wInternal, a, v2, m);

        BrowserClient n = admin(o);
        // L01/L02: client nulo em workspace interno; referencia em workspace de cliente
        Got l02 = get(n, path(o.id(), "workspaces/" + wInternal, null));
        assertOk(l02, "L02");
        assertThat(keys(l02.body())).containsExactly("workspaceId", "name", "description", "status", "client", "createdAt", "updatedAt");
        assertThat(l02.body().get("client").isNull()).isTrue();
        assertThat(l02.body().get("description").isNull()).isTrue();
        Got l02c = get(n, path(o.id(), "workspaces/" + wClient, null));
        assertThat(l02c.body().get("client").get("clientId").asString()).isEqualTo(client.toString());
        for (JsonNode w : get(n, path(o.id(), "workspaces", null)).body().get("items")) {
            assertThat(keys(w)).as("item da L01 = L02 (sem a chave interna de ordenacao)").containsExactly("workspaceId", "name", "description", "status",
                    "client", "createdAt", "updatedAt");
        }
        // L03/L04: latestPublishedVersion = MAIOR publicada (v2), mesmo com rascunho posterior; nulo sem publicada
        Got l04 = get(n, path(o.id(), "automations/" + a, null));
        assertOk(l04, "L04");
        assertThat(keys(l04.body())).containsExactly("automationId", "workspaceId", "name", "description", "status", "latestPublishedVersion",
                "createdAt", "updatedAt");
        assertNoForbiddenKeys(l04.body(), "L04");
        assertThat(l04.body().get("latestPublishedVersion").get("automationVersionId").asString()).isEqualTo(v2.toString());
        assertThat(l04.body().get("latestPublishedVersion").get("versionNumber").asInt()).isEqualTo(2);
        assertThat(get(n, path(o.id(), "automations/" + draftOnly, null)).body().get("latestPublishedVersion").isNull()).isTrue();
        JsonNode l03 = get(n, path(o.id(), "workspaces/" + wInternal + "/automations", null)).body().get("items");
        assertThat(field(l03, "automationId")).containsExactly(a.toString(), draftOnly.toString());
        // L05: ordem descendente pelo numero, rascunho com publishedAt nulo, sem command_json
        List<JsonNode> versions = allPages(n, o.id(), "automations/" + a + "/versions", null, 2);
        assertThat(field(versions, "versionNumber")).containsExactly("3", "2", "1");
        assertThat(versions.get(0).get("status").asString()).isEqualTo("draft");
        assertThat(versions.get(0).get("publishedAt").isNull()).isTrue();
        assertThat(versions.get(1).get("status").asString()).isEqualTo("published");
        assertThat(keys(versions.get(0))).containsExactly("automationVersionId", "versionNumber", "status", "publishedAt", "createdAt");

        // L07: campos exatos; solicitante na manual, nulo na agendada (DC-09); retryOf
        Got d = get(n, path(o.id(), "executions/" + done, null));
        assertOk(d, "L07");
        assertThat(keys(d.body())).containsExactly("executionId", "workspaceId", "automation", "automationVersion", "origin", "status", "statusReason",
                "createdAt", "startedAt", "finishedAt", "durationMs", "cancelRequestedAt", "retryOfExecutionId", "requestedBy");
        assertNoForbiddenKeys(d.body(), "L07");
        assertThat(d.body().get("origin").asString()).isEqualTo("manual");
        assertThat(d.body().get("status").asString()).isEqualTo("succeeded");
        assertThat(d.body().get("statusReason").asString()).isEqualTo("process_completed");
        assertThat(d.body().get("durationMs").asLong()).as("1,234567 s truncado em ms").isEqualTo(1234L);
        assertThat(d.body().get("requestedBy").get("membershipId").asString()).isEqualTo(m.toString());
        assertThat(d.body().get("requestedBy").get("displayName").asString()).isEqualTo("Pessoa de teste");
        assertThat(d.body().get("automationVersion").get("versionNumber").asInt()).isEqualTo(2);
        Got s = get(n, path(o.id(), "executions/" + scheduled, null));
        assertOk(s, "L07 agendada");
        assertThat(s.body().get("origin").asString()).isEqualTo("scheduled");
        assertThat(s.body().get("requestedBy").isNull()).as("agendada: sem solicitante").isTrue();
        assertThat(get(n, path(o.id(), "executions/" + pending, null)).body().get("retryOfExecutionId").asString()).isEqualTo(done.toString());
        // durationMs nulo em andamento, pendente e cancelada antes do despacho
        for (UUID x : List.of(running, pending, cancelled)) {
            Got g = get(n, path(o.id(), "executions/" + x, null));
            assertThat(g.body().get("durationMs").isNull()).as("durationMs nulo: " + g.body().get("status").asString()).isTrue();
        }
        assertThat(get(n, path(o.id(), "executions/" + cancelled, null)).body().get("statusReason").asString()).isEqualTo("cancelled_before_dispatch");
        assertThat(get(n, path(o.id(), "executions/" + running, null)).body().get("startedAt").isString()).isTrue();
        // L06: item sem os campos do detalhe nem os excluidos
        for (JsonNode x : get(n, path(o.id(), "executions", null)).body().get("items")) {
            assertThat(keys(x)).containsExactly("executionId", "workspaceId", "automation", "automationVersion", "origin", "status", "statusReason",
                    "createdAt", "startedAt", "finishedAt", "durationMs", "cancelRequestedAt");
        }
        // L08: eventos em ordem de sequence, paginados, sem payload; execucao sem eventos -> 200 vazio
        List<JsonNode> events = allPages(n, o.id(), "executions/" + done + "/events", null, 2);
        assertThat(field(events, "sequence")).containsExactly("1", "2", "3", "4", "5");
        assertThat(events.get(0).get("type").asString()).isEqualTo("process_started");
        assertThat(events.get(1).get("type").asString()).isEqualTo("process_succeeded");
        assertThat(keys(events.get(0))).containsExactly("sequence", "type", "occurredAt", "receivedAt");
        Got empty = get(n, path(o.id(), "executions/" + pending + "/events", null));
        assertOk(empty, "L08 sem eventos");
        assertThat(empty.body().get("items").size()).isZero();
    }

    // ================================================================== periodo e filtros da L06

    private record Scene(Org o, UUID w1, UUID w2, UUID a1, UUID a2, UUID v1, UUID v2) {
    }

    private static Scene scene() {
        Org o = newOrganization();
        ProductFixtures.unlimited(o.id(), o.adminId());
        UUID w1 = AccessFixtures.namedWorkspace(o.id(), "W1", 1);
        UUID w2 = AccessFixtures.namedWorkspace(o.id(), "W2", 1);
        UUID a1 = ProductFixtures.automation(o.id(), w1, null, "A1", o.adminMembership());
        UUID a2 = ProductFixtures.automation(o.id(), w2, null, "A2", o.adminMembership());
        UUID v1 = ProductFixtures.version(o.id(), w1, a1, true, o.adminMembership());
        UUID v2 = ProductFixtures.version(o.id(), w2, a2, true, o.adminMembership());
        return new Scene(o, w1, w2, a1, a2, v1, v2);
    }

    @Test
    void executionPeriodAndFilters() {
        Scene sc = scene();
        UUID m = sc.o().adminMembership();
        UUID atFrom = ProductFixtures.execution(sc.o().id(), sc.w1(), sc.a1(), sc.v1(), "'2026-01-10T00:00:00Z'", m);
        UUID inside = ProductFixtures.execution(sc.o().id(), sc.w1(), sc.a1(), sc.v1(), "'2026-01-15T00:00:00Z'", m);
        UUID atTo = ProductFixtures.execution(sc.o().id(), sc.w1(), sc.a1(), sc.v1(), "'2026-01-20T00:00:00Z'", m);
        UUID other = ProductFixtures.execution(sc.o().id(), sc.w2(), sc.a2(), sc.v2(), "'2026-01-12T00:00:00Z'", m);
        ProductFixtures.cancelBeforeDispatch(other, m);
        BrowserClient n = admin(sc.o());
        UUID o = sc.o().id();
        java.util.function.Function<String, List<String>> ids = q -> {
            Got g = get(n, path(o, "executions", q));
            assertOk(g, q);
            return field(g.body().get("items"), "executionId");
        };
        assertThat(ids.apply(null)).as("sem periodo").containsExactly(atTo.toString(), inside.toString(), other.toString(), atFrom.toString());
        assertThat(ids.apply("from=2026-01-10T00:00:00Z&to=2026-01-20T00:00:00Z")).as("[from, to): from inclusivo, to exclusivo")
                .containsExactly(inside.toString(), other.toString(), atFrom.toString());
        assertThat(ids.apply("from=2026-01-15T00:00:00Z")).as("so from").containsExactly(atTo.toString(), inside.toString());
        assertThat(ids.apply("to=2026-01-12T00:00:00Z")).as("so to (exclusivo)").containsExactly(atFrom.toString());
        assertThat(ids.apply("from=2026-01-15T00:00:00-03:00&to=2026-01-15T03:00:00Z")).as("to = from (mesmo instante, fusos diferentes)").isEmpty();
        assertThat(ids.apply("from=2025-01-20T00:00:00Z&to=2026-01-21T00:00:00Z")).as("exatamente 366 dias").hasSize(4);
        assertError(get(n, path(o, "executions", "from=2025-01-20T00:00:00Z&to=2026-01-21T00:00:00.001Z")), 400, "invalid_request");
        assertError(get(n, path(o, "executions", "from=2026-01-20T00:00:00Z&to=2026-01-10T00:00:00Z")), 400, "invalid_request");
        assertError(get(n, path(o, "executions", "from=2026-01-10T00:00:00")), 400, "invalid_request");
        assertError(get(n, path(o, "executions", "to=ontem")), 400, "invalid_request");
        // filtros
        assertThat(ids.apply("workspaceId=" + sc.w2())).containsExactly(other.toString());
        assertThat(ids.apply("automationId=" + sc.a1())).containsExactly(atTo.toString(), inside.toString(), atFrom.toString());
        assertThat(ids.apply("status=cancelled")).containsExactly(other.toString());
        assertThat(ids.apply("status=pending&status=cancelled&workspaceId=" + sc.w1())).hasSize(3);
        assertThat(ids.apply("workspaceId=" + sc.w1() + "&automationId=" + sc.a2())).as("acessiveis mas incompativeis -> vazio").isEmpty();
        for (String q : List.of("status=nada", "status=", "status=pending&status=pending",
                "status=pending&status=starting&status=running&status=succeeded&status=failed&status=cancelled&status=pending",
                "workspaceId=nao-e-uuid", "automationId=123", "limit=0", "limit=101", "cursor=x.y")) {
            assertError(get(n, path(o, "executions", q)), 400, "invalid_request");
        }
        assertError(get(n, path(o, "executions", "workspaceId=" + UUID.randomUUID())), 404, "not_found");
        assertError(get(n, path(o, "executions", "automationId=" + UUID.randomUUID())), 404, "not_found");
        // automationId de workspace sem acesso -> 404 (regra geral, sem a excecao da M02)
        TestUser operator = internalUser(sc.o(), true, 2);
        assignWorkspaceRole(o, membershipOf(o, operator.id()), sc.w1(), "OPERATOR", m);
        BrowserClient op = login(sc.o(), operator);
        assertError(get(op, path(o, "executions", "automationId=" + sc.a2())), 404, "not_found");
        assertThat(field(get(op, path(o, "executions", "automationId=" + sc.a1())).body().get("items"), "executionId")).hasSize(3);
    }

    // ================================================================== ordenacao composta por data (L06)

    private static final List<String> INSTANTS_BY_ASCENDING_ID = List.of("'2026-03-01T00:00:00.000003Z'", "'2026-03-01T00:00:00.000003Z'",
            "'2026-03-01T00:00:00.000002Z'", "'2026-03-01T00:00:00.000001Z'", "'2026-02-01T00:00:00Z'", "'2026-02-01T00:00:00Z'", "'2026-01-01T00:00:00Z'");

    private record Dated(String id, Instant at) {
    }

    @Test
    void executionsOrderByCreatedAtThenIdDescendingWithMicrosecondsAndTies() {
        Scene sc = scene();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < INSTANTS_BY_ASCENDING_ID.size(); i++) {
            ids.add(UUID.randomUUID().toString());
        }
        ids.sort(null);
        List<Dated> items = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            ProductFixtures.executionWithId(UUID.fromString(ids.get(i)), sc.o().id(), sc.w1(), sc.a1(), sc.v1(), INSTANTS_BY_ASCENDING_ID.get(i),
                    sc.o().adminMembership(), null);
            String at = INSTANTS_BY_ASCENDING_ID.get(i);
            items.add(new Dated(ids.get(i), Instant.parse(at.substring(1, at.length() - 1))));
        }
        Comparator<Dated> c = Comparator.comparing(Dated::at).thenComparing(Dated::id);
        List<String> expected = items.stream().sorted(c.reversed()).map(Dated::id).toList();
        assertThat(expected).as("a massa distingue a chave principal").isNotEqualTo(ids).isNotEqualTo(ids.reversed());
        BrowserClient n = admin(sc.o());
        for (int limit : List.of(1, 2, 3, 7)) {
            List<JsonNode> got = allPages(n, sc.o().id(), "executions", null, limit);
            assertThat(field(got, "executionId")).as("data e id descendentes, limit " + limit).isEqualTo(expected);
            for (JsonNode x : got) {
                assertThat(items).as("createdAt em microssegundos")
                        .contains(new Dated(x.get("executionId").asString(), OffsetDateTime.parse(x.get("createdAt").asString()).toInstant()));
            }
        }
    }

    // ================================================================== nomes: ordenacao binaria, desempate e nomes longos (L01, L03)

    @Test
    void namesBinaryOrderTiesAndLongNamesInWorkspacesAndAutomations() {
        Org o = newOrganization();
        String p48 = "P".repeat(48);
        List<String> wsNames = List.of("Beta", "Zeta", "alpha", "Ábaco", p48 + "zzz", p48 + "aaa", "a".repeat(1000), "b" + "\u0001".repeat(999),
                "c" + "😀".repeat(999));
        int[] firstBytes = {0x10, 0x11, 0x12, 0x13, 0x20, 0xf0, 0x30, 0x31, 0x32};
        List<String> created = new ArrayList<>();
        for (int i = 0; i < wsNames.size(); i++) {
            UUID w = id(firstBytes[i]);
            DB.asSuperuser("INSERT INTO workspaces (workspace_id, organization_id, name, status) VALUES ('" + w + "', '" + o.id() + "', 'tmp', 1)");
            renameWorkspace(w, wsNames.get(i));
            created.add(w.toString());
        }
        // binaria: "Beta" < "PPP..zzz"(id 0x20) < "PPP..aaa"(id 0xf0) < "Zeta" < "a..." < "alpha" < "b\u0001..." < "c\uD83D..." < "Ábaco"
        List<String> expected = List.of(created.get(0), created.get(4), created.get(5), created.get(1), created.get(6), created.get(2), created.get(7),
                created.get(8), created.get(3));
        BrowserClient n = admin(o);
        int largest = 0;
        for (int limit : List.of(1, 2, 4)) {
            List<JsonNode> got = allPages(n, o.id(), "workspaces", null, limit);
            assertThat(field(got, "workspaceId")).as("L01 binaria, prefixo de 48 e desempate por id, limit " + limit).isEqualTo(expected);
        }
        String cursor = null;
        for (int page = 0; page < expected.size(); page++) {
            Got g = get(n, path(o.id(), "workspaces", "limit=1" + (cursor == null ? "" : "&cursor=" + cursor)));
            assertOk(g, "L01 pagina " + page);
            if (g.body().get("nextCursor").isNull()) {
                break;
            }
            cursor = g.body().get("nextCursor").asString();
            largest = Math.max(largest, cursor.length());
        }
        assertThat(largest).as("cursor com nome longo no fim da pagina").isLessThanOrEqualTo(872);

        // L03 no mesmo padrao, dentro de um workspace
        ProductFixtures.unlimited(o.id(), o.adminId());
        UUID w = UUID.fromString(created.get(0));
        List<String> autoNames = List.of(p48 + "zzz", p48 + "aaa", "x".repeat(1000), "Igual", "Igual");
        int[] autoBytes = {0x20, 0xf0, 0x40, 0xb0, 0xa0};
        List<String> autos = new ArrayList<>();
        for (int i = 0; i < autoNames.size(); i++) {
            autos.add(ProductFixtures.automation(o.id(), w, id(autoBytes[i]), autoNames.get(i), o.adminMembership()).toString());
        }
        List<String> expectedAutos = List.of(autos.get(4), autos.get(3), autos.get(0), autos.get(1), autos.get(2));
        for (int limit : List.of(1, 2, 5)) {
            assertThat(field(allPages(n, o.id(), "workspaces/" + w + "/automations", null, limit), "automationId")).as("L03 limit " + limit)
                    .isEqualTo(expectedAutos);
        }
    }

    private static void renameWorkspace(UUID w, String name) {
        try (var c = DB.connectAsSuperuser(); var ps = c.prepareStatement("UPDATE workspaces SET name = ? WHERE workspace_id = ?")) {
            ps.setString(1, name);
            ps.setObject(2, w);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ================================================================== mudanca concorrente (§5.4)

    @Test
    void concurrentChangesFollowTheDocumentedSemantics() {
        Org o = newOrganization();
        ProductFixtures.unlimited(o.id(), o.adminId());
        UUID b = AccessFixtures.namedWorkspace(o.id(), "B", 1);
        AccessFixtures.namedWorkspace(o.id(), "D", 1);
        UUID f = AccessFixtures.namedWorkspace(o.id(), "F", 1);
        UUID h = AccessFixtures.namedWorkspace(o.id(), "H", 1);
        BrowserClient n = admin(o);
        Got p1 = get(n, path(o.id(), "workspaces", "limit=2"));
        String c1 = p1.body().get("nextCursor").asString();
        renameWorkspace(h, "A");
        Got p2 = get(n, path(o.id(), "workspaces", "limit=2&cursor=" + c1));
        assertOk(p2, "L01 apos renomear para antes do cursor");
        assertThat(field(p2.body().get("items"), "workspaceId")).as("H renomeado para antes: omitido").containsExactly(f.toString());
        renameWorkspace(b, "G");
        assertThat(field(get(n, path(o.id(), "workspaces", "limit=2&cursor=" + c1)).body().get("items"), "workspaceId"))
                .as("B renomeado para depois: aparece").containsExactly(f.toString(), b.toString());

        // L06: execucao mais nova inserida depois da pagina 1 (ordem descendente) nao aparece nas seguintes
        UUID a = ProductFixtures.automation(o.id(), f, null, "A", o.adminMembership());
        UUID v = ProductFixtures.version(o.id(), f, a, true, o.adminMembership());
        List<String> old = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            old.add(ProductFixtures.execution(o.id(), f, a, v, "'2026-01-0" + i + "T00:00:00Z'", o.adminMembership()).toString());
        }
        Got e1 = get(n, path(o.id(), "executions", "limit=1"));
        UUID newer = ProductFixtures.execution(o.id(), f, a, v, "'2026-06-01T00:00:00Z'", o.adminMembership());
        Got e2 = get(n, path(o.id(), "executions", "limit=5&cursor=" + e1.body().get("nextCursor").asString()));
        assertOk(e2, "L06 apos insercao");
        assertThat(field(e2.body().get("items"), "executionId")).doesNotContain(newer.toString()).containsExactly(old.get(1), old.get(0));

        // L03: automacao inserida depois do cursor aparece
        UUID a2 = ProductFixtures.automation(o.id(), f, null, "M", o.adminMembership());
        Got q1 = get(n, path(o.id(), "workspaces/" + f + "/automations", "limit=1"));
        assertThat(field(q1.body().get("items"), "automationId")).containsExactly(a.toString());
        UUID late = ProductFixtures.automation(o.id(), f, null, "Z", o.adminMembership());
        Got q2 = get(n, path(o.id(), "workspaces/" + f + "/automations", "limit=5&cursor=" + q1.body().get("nextCursor").asString()));
        assertThat(field(q2.body().get("items"), "automationId")).containsExactly(a2.toString(), late.toString());
    }

    // ================================================================== revogacao entre paginas e acesso direto (§5.5)

    @Test
    void revocationBetweenPagesAndDirectAccess() {
        Scene sc = scene();
        UUID o = sc.o().id();
        UUID m = sc.o().adminMembership();
        UUID a1b = ProductFixtures.automation(o, sc.w1(), null, "A1b", m);
        UUID x1 = ProductFixtures.execution(o, sc.w1(), sc.a1(), sc.v1(), "'2026-05-01T00:00:00Z'", m);
        UUID x1b = ProductFixtures.execution(o, sc.w1(), sc.a1(), sc.v1(), "'2026-04-01T00:00:00Z'", m);
        UUID x2 = ProductFixtures.execution(o, sc.w2(), sc.a2(), sc.v2(), "'2026-03-01T00:00:00Z'", m);
        TestUser op = internalUser(sc.o(), true, 2);
        UUID opM = membershipOf(o, op.id());
        UUID asW1 = assignWorkspaceRole(o, opM, sc.w1(), "OPERATOR", m);
        assignWorkspaceRole(o, opM, sc.w2(), "OPERATOR", m);
        BrowserClient n = login(sc.o(), op);
        Got l06p1 = get(n, path(o, "executions", "limit=1"));
        assertThat(field(l06p1.body().get("items"), "executionId")).containsExactly(x1.toString());
        Got l03p1 = get(n, path(o, "workspaces/" + sc.w1() + "/automations", "limit=1"));
        Got l06w1p1 = get(n, path(o, "executions", "workspaceId=" + sc.w1() + "&limit=1"));
        Got l01p1 = get(n, path(o, "workspaces", "limit=1"));
        assertThat(field(l01p1.body().get("items"), "workspaceId")).containsExactly(sc.w1().toString());
        revokeWorkspaceAssignment(asW1, m);
        // listas sem workspace no caminho: itens de W1 deixam de aparecer; demais normais
        Got l06p2 = get(n, path(o, "executions", "limit=5&cursor=" + l06p1.body().get("nextCursor").asString()));
        assertOk(l06p2, "L06 pagina 2");
        assertThat(field(l06p2.body().get("items"), "executionId")).as("x1b (W1) some; x2 (W2) segue").containsExactly(x2.toString());
        Got l01p2 = get(n, path(o, "workspaces", "limit=5&cursor=" + l01p1.body().get("nextCursor").asString()));
        assertThat(field(l01p2.body().get("items"), "workspaceId")).containsExactly(sc.w2().toString());
        // W1 no caminho ou no filtro: 404
        assertError(get(n, path(o, "workspaces/" + sc.w1() + "/automations", "limit=1&cursor=" + l03p1.body().get("nextCursor").asString())), 404,
                "not_found");
        assertError(get(n, path(o, "executions", "workspaceId=" + sc.w1() + "&limit=1&cursor=" + l06w1p1.body().get("nextCursor").asString())), 404,
                "not_found");
        assertError(get(n, path(o, "executions", "automationId=" + sc.a1())), 404, "not_found");
        // acesso direto: 404 identico ao de inexistente
        for (String route : List.of("workspaces/" + sc.w1(), "automations/" + a1b, "automations/" + sc.a1() + "/versions", "executions/" + x1b,
                "executions/" + x1b + "/events")) {
            Got g = get(n, path(o, route, null));
            assertError(g, 404, "not_found");
            assertThat(keys(g.body())).containsExactly("error");
        }
        assertOk(get(n, path(o, "executions/" + x2, null)), "W2 continua acessivel");

        // ORG_ADMIN revogado (sem atribuicao de workspace): lista vazia e 404
        TestUser second = internalUser(sc.o(), true, 2);
        UUID orgAssignment = assignOrgRole(o, membershipOf(o, second.id()), "ORG_ADMIN", m);
        BrowserClient s = login(sc.o(), second);
        assertThat(get(s, path(o, "workspaces", null)).body().get("items").size()).isEqualTo(2);
        revokeOrgAssignment(orgAssignment, m);
        assertThat(get(s, path(o, "workspaces", null)).body().get("items").size()).isZero();
        assertError(get(s, path(o, "workspaces/" + sc.w2(), null)), 404, "not_found");
        // revogar as sessoes: 401
        assertThat(n.post("/api/v1/o/" + o + "/auth/sessions/revoke-all", null).status()).isEqualTo(204);
        assertError(get(n, path(o, "executions/" + x2, null)), 401, "unauthenticated");
    }

    // ================================================================== cursores entre rotas paginadas reais

    @Test
    void cursorsAreBoundToRouteParentAndFilters() {
        Scene sc = scene();
        UUID o = sc.o().id();
        UUID m = sc.o().adminMembership();
        ProductFixtures.automation(o, sc.w1(), null, "A1b", m);
        ProductFixtures.automation(o, sc.w2(), null, "A2b", m);
        ProductFixtures.version(o, sc.w1(), sc.a1(), true, m);
        for (int i = 0; i < 3; i++) {
            ProductFixtures.execution(o, sc.w1(), sc.a1(), sc.v1(), null, m);
            AccessFixtures.namedClient(o, UUID.randomUUID(), "Cliente " + i, 1);
        }
        AccessFixtures.namedWorkspace(o, "W3", 1);
        BrowserClient n = admin(sc.o());
        String l03w1 = get(n, path(o, "workspaces/" + sc.w1() + "/automations", "limit=1")).body().get("nextCursor").asString();
        String l01 = get(n, path(o, "workspaces", "limit=1")).body().get("nextCursor").asString();
        String m03 = get(n, path(o, "clients", "limit=1")).body().get("nextCursor").asString();
        String l06 = get(n, path(o, "executions", "limit=1")).body().get("nextCursor").asString();
        String l05 = get(n, path(o, "automations/" + sc.a1() + "/versions", "limit=1")).body().get("nextCursor").asString();
        assertOk(get(n, path(o, "workspaces/" + sc.w1() + "/automations", "limit=1&cursor=" + l03w1)), "controle L03");
        assertOk(get(n, path(o, "executions", "limit=1&cursor=" + l06)), "controle L06");
        assertError(get(n, path(o, "workspaces/" + sc.w2() + "/automations", "limit=1&cursor=" + l03w1)), 400, "invalid_request");
        assertError(get(n, path(o, "workspaces", "limit=1&cursor=" + l03w1)), 400, "invalid_request");
        assertError(get(n, path(o, "workspaces", "limit=1&cursor=" + m03)), 400, "invalid_request");
        assertError(get(n, path(o, "clients", "limit=1&cursor=" + l01)), 400, "invalid_request");
        assertError(get(n, path(o, "automations/" + sc.a1() + "/versions", "limit=1&cursor=" + l03w1)), 400, "invalid_request");
        assertError(get(n, path(o, "automations/" + sc.a2() + "/versions", "limit=1&cursor=" + l05)), 400, "invalid_request");
        assertError(get(n, path(o, "executions", "status=pending&limit=1&cursor=" + l06)), 400, "invalid_request");
        assertError(get(n, path(o, "executions", "from=2020-01-01T00:00:00Z&limit=1&cursor=" + l06)), 400, "invalid_request");
        String eventsOfOther = cursors.encode(new CursorCodec.Scope("listExecutionEvents", o, m, UUID.randomUUID(), CursorCodec.filters(), "sequence.asc"),
                List.of(1L));
        UUID x = UUID.fromString(get(n, path(o, "executions", "limit=1")).body().get("items").get(0).get("executionId").asString());
        assertError(get(n, path(o, "executions/" + x + "/events", "cursor=" + eventsOfOther)), 400, "invalid_request");
    }

    // ================================================================== DC-05 x DC-12, 400 de UUID malformado

    @Test
    void filterExceptionDc05VersusGeneralRuleAndMalformedIds() {
        Scene sc = scene();
        Org b = newOrganization();
        ProductFixtures.unlimited(b.id(), b.adminId());
        UUID bc = newClient(b);
        UUID bw = newWorkspace(b, bc);
        UUID ba = ProductFixtures.automation(b.id(), bw, null, "AB", b.adminMembership());
        UUID bv = ProductFixtures.version(b.id(), bw, ba, true, b.adminMembership());
        UUID bx = ProductFixtures.execution(b.id(), bw, ba, bv, null, b.adminMembership());
        BrowserClient n = admin(sc.o());
        UUID o = sc.o().id();
        // o MESMO workspaceId de outra organizacao: 200 vazio na M02 (excecao DC-05) e 404 na L06 (regra geral)
        Got m02 = get(n, path(o, "client-grants", "workspaceId=" + bw));
        assertOk(m02, "M02 excecao DC-05");
        assertThat(m02.body().get("items").size()).isZero();
        assertError(get(n, path(o, "executions", "workspaceId=" + bw)), 404, "not_found");
        // DC-12: eventos de execucao de outra organizacao -> 404 (o portal externo devolve lista vazia; diferenca documentada)
        assertError(get(n, path(o, "executions/" + bx + "/events", null)), 404, "not_found");
        // UUID malformado no caminho -> 400, inclusive para conta externa (ordem 400 antes de 403)
        UUID client = newClient(sc.o());
        BrowserClient ext = login(sc.o(), externalUser(sc.o(), client, null));
        for (String route : List.of("workspaces/nao-e-uuid", "workspaces/nao-e-uuid/automations", "automations/123", "automations/123/versions",
                "executions/x", "executions/x/events")) {
            assertError(get(n, path(o, route, null)), 400, "invalid_request");
            assertError(get(ext, path(o, route, null)), 400, "invalid_request");
        }
        assertError(get(ext, path(o, "workspaces/" + sc.w1(), null)), 403, "forbidden");
    }
}
