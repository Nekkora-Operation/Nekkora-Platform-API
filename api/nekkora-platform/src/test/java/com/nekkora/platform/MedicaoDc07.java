package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;
import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.platform.access.WorkspaceAccess;
import com.nekkora.platform.paging.CursorCodec;
import com.nekkora.platform.web.AdminReadController;
import com.nekkora.platform.web.ProductReads;

/**
 * DC-07 (decisao da responsavel em 2026-10-03: medir com volume sintetico antes de fechar a GAP-06). NAO faz parte da
 * regressao: o nome nao casa com *Test/*IT; executar so por nome ({@code -Dtest=MedicaoDc07 -Dnekkora.medicao.saida=<dir>}).
 *
 * <p>Base: o PostgreSQL DESCARTAVEL do teste (Testcontainers, mesma imagem da regressao). Massa gerada como superusuario
 * (preparacao DECLARADA) com {@code session_replication_role = replica} SO durante a carga em lote: triggers e checagens
 * de FK desligadas na carga (CHECKs continuam ativos), dados gerados consistentes; depois ANALYZE.
 *
 * <p>O que e medido, e como (sem HTTP salvo onde indicado):
 * <ul>
 * <li>{@code componente}: a chamada REAL de ProductReads (ou do caminho da M03), dentro de OrgTransactions.inOrganization
 * com o papel nekkora_platform: inclui set_config do contexto organizacional, a autorizacao compartilhada (consistencia do
 * catalogo + regra por workspace) e a consulta da pagina com a RLS ativa;</li>
 * <li>{@code autorizacao}: so a parte do componente compartilhado que a rota executa antes/como parte da consulta
 * (requireConsistent; requireWorkspace/requireAutomation/requireExecution; requireAnyOrganizationPermission);</li>
 * <li>{@code consulta}: so o SQL da pagina (texto vindo do mesmo montador da rota, mesmos parametros), na mesma transacao
 * organizacional; nas listas o SQL contem o predicado de visibilidade;</li>
 * <li>{@code http}: requisicao HTTP completa contra a aplicacao em execucao (filtro de sessao, limitador R7 no Redis,
 * transacao, serializacao), num subconjunto de casos, com intervalo entre requisicoes para nao esgotar o R7;</li>
 * <li>{@code EXPLAIN (ANALYZE, BUFFERS)} da consulta de cada caso.</li>
 * </ul>
 * 3 execucoes de aquecimento descartadas e 30 medidas por item; p50, p95 (posto mais proximo) e maximo.
 */
class MedicaoDc07 extends PlatformTestBase {

    private static final int WARMUP = 3;
    private static final int RUNS = 30;
    private static final int PAGE = 25;
    private static final long HTTP_INTERVAL_MS = 600;

    @Autowired
    private OrgTransactions tx;

    @Autowired
    private WorkspaceAccess access;

    @Autowired
    private CursorCodec cursors;

    private Path out;
    private final List<String> results = new ArrayList<>();

    private record Actor(String name, UUID membership, BrowserClient http) {
    }

    @Test
    void medir() throws Exception {
        String dir = System.getProperty("nekkora.medicao.saida");
        assertThat(dir).as("-Dnekkora.medicao.saida=<diretorio novo>").isNotBlank();
        out = Path.of(dir);
        Files.createDirectories(out.resolve("planos"));
        Files.createDirectories(out.resolve("consultas"));
        results.add("caso\tator\titem\tn\tp50_ms\tp95_ms\tmax_ms\tlinhas");

        // ------------------------------------------------------------------ massa
        Org o = newOrganization();
        Org o2 = newOrganization();
        Org o3 = newOrganization();
        long t0 = System.nanoTime();
        Map<String, Object> hotO = generate(o, 200, 5000, 76, 1_000_000, 300_000, 5_000);
        generate(o2, 20, 76, 76, 100_000, 10_000, 1_000);
        generate(o3, 20, 76, 76, 100_000, 10_000, 1_000);
        UUID hot = (UUID) hotO.get("hot");
        UUID ws2 = (UUID) hotO.get("ws2");
        UUID ws3 = (UUID) hotO.get("ws3");
        ProductFixtures.unlimited(o.id(), o.adminId()); // a 010 exige limites configurados para criar Worker
        ProductFixtures.Runtime rt = ProductFixtures.runtime(o.id(), hot, o.adminMembership());
        DB.asSuperuser("SET session_replication_role = replica",
                "INSERT INTO execution_events (event_id, organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, "
                        + "received_at, payload, fingerprint) SELECT gen_random_uuid(), '" + o.id() + "', e.workspace_id, e.execution_id, '" + rt.agent()
                        + "', s, CASE WHEN s % 2 = 1 THEN 1 ELSE 2 END, e.created_at, e.created_at, CASE WHEN s % 2 = 1 THEN "
                        + "'{\"schema_version\":1,\"pid\":7}'::jsonb ELSE '{\"schema_version\":1,\"exit_code\":0}'::jsonb END, md5(e.execution_id::text || s) "
                        + "FROM (SELECT execution_id, workspace_id, created_at FROM executions WHERE organization_id = '" + o.id() + "' AND workspace_id = '" + hot
                        + "' AND status = 4 ORDER BY execution_id LIMIT 10000) e, generate_series(1, 5) s",
                "SET session_replication_role = origin",
                "ANALYZE");
        long loadMs = (System.nanoTime() - t0) / 1_000_000;

        TestUser opUser = internalUser(o, true, 2);
        UUID opM = AccessFixtures.membershipOf(o.id(), opUser.id());
        for (UUID w : List.of(hot, ws2, ws3)) {
            AccessFixtures.assignWorkspaceRole(o.id(), opM, w, "OPERATOR", o.adminMembership());
        }
        BrowserClient adminHttp = browser();
        firstLogin(adminHttp, o.id(), new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray()));
        BrowserClient opHttp = browser();
        firstLogin(opHttp, o.id(), opUser);
        List<Actor> actors = List.of(new Actor("ORG_ADMIN", o.adminMembership(), adminHttp), new Actor("OPERATOR_3_workspaces", opM, opHttp));

        writeEnvironment(o, o2, o3, hot, loadMs);

        UUID org = o.id();
        UUID hotAutomation = UUID.fromString(AccessFixtures.value("SELECT automation_id FROM automations WHERE workspace_id = '" + hot
                + "' ORDER BY automation_id LIMIT 1"));
        UUID eventsExecution = UUID.fromString(AccessFixtures.value("SELECT execution_id FROM execution_events WHERE organization_id = '" + org
                + "' ORDER BY execution_id LIMIT 1"));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // ------------------------------------------------------------------ L06
        for (Actor a : actors) {
            Map<String, ProductReads.ExecutionFilter> filters = new LinkedHashMap<>();
            filters.put("L06_sem_filtro", new ProductReads.ExecutionFilter(null, null, null, null, null));
            filters.put("L06_so_from_6_meses", new ProductReads.ExecutionFilter(null, null, null, now.minusMonths(6), null));
            filters.put("L06_workspace_quente", new ProductReads.ExecutionFilter(hot, null, null, null, null));
            filters.put("L06_status_seletivo_pending", new ProductReads.ExecutionFilter(null, null, List.of((short) 1), null, null));
            filters.put("L06_periodo_30_dias", new ProductReads.ExecutionFilter(null, null, null, now.minusDays(60), now.minusDays(30)));
            for (Map.Entry<String, ProductReads.ExecutionFilter> e : filters.entrySet()) {
                measureExecutions(e.getKey() + "_pagina1", a, org, e.getValue(), null, null);
            }
            for (String c : List.of("L06_sem_filtro", "L06_workspace_quente")) {
                ProductReads.ExecutionFilter f = filters.get(c);
                Object[] pos = deepExecutionPosition(org, a.membership(), f, 10_000);
                measureExecutions(c + "_profunda_apos_10000", a, org, f, (OffsetDateTime) pos[0], (UUID) pos[1]);
            }
        }
        // ------------------------------------------------------------------ L01, L03, L05, L08 e M03
        for (Actor a : actors) {
            measureWorkspaces("L01_200_workspaces_pagina1", a, org);
            measureAutomations("L03_workspace_5000_pagina1", a, org, hot, null, null);
            String[] deep = deepAutomationPosition(org, hot, 2_500);
            measureAutomations("L03_workspace_5000_profunda_apos_2500", a, org, hot, deep[0], UUID.fromString(deep[1]));
            measureVersions("L05_versoes", a, org, hotAutomation);
            measureEvents("L08_eventos", a, org, eventsExecution);
        }
        Actor admin = actors.get(0);
        measureClients("M03_5000_clientes_pagina1", admin, org, null, null);
        String[] deepClient = deepClientPosition(org, 2_500);
        measureClients("M03_5000_clientes_profunda_apos_2500", admin, org, deepClient[0], UUID.fromString(deepClient[1]));

        // ------------------------------------------------------------------ autorizacao isolada
        for (Actor a : actors) {
            UUID m = a.membership();
            time("AUT_requireConsistent", a, "autorizacao", () -> tx.inOrganization(org, Mode.READ_ONLY, j -> {
                access.requireConsistent(j, org, m);
                return 0;
            }));
            time("AUT_requireWorkspace_quente", a, "autorizacao", () -> tx.inOrganization(org, Mode.READ_ONLY, j -> {
                access.requireWorkspace(j, org, m, hot, ProductReads.READ);
                return 0;
            }));
            time("AUT_requireAutomation", a, "autorizacao", () -> tx.inOrganization(org, Mode.READ_ONLY, j -> {
                access.requireAutomation(j, org, m, hotAutomation, ProductReads.READ);
                return 0;
            }));
            time("AUT_requireExecution", a, "autorizacao", () -> tx.inOrganization(org, Mode.READ_ONLY, j -> {
                access.requireExecution(j, org, m, eventsExecution, ProductReads.READ);
                return 0;
            }));
        }
        time("AUT_requireAnyOrganizationPermission", admin, "autorizacao", () -> tx.inOrganization(org, Mode.READ_ONLY, j -> {
            access.requireAnyOrganizationPermission(j, org, admin.membership(), "org.manage", "external_access.manage");
            return 0;
        }));

        // ------------------------------------------------------------------ HTTP completo (subconjunto, com intervalo)
        for (Actor a : actors) {
            http("L06_sem_filtro_pagina1", a, path(org, "executions", null));
            Object[] pos = deepExecutionPosition(org, a.membership(), new ProductReads.ExecutionFilter(null, null, null, null, null), 10_000);
            String c = cursors.encode(new CursorCodec.Scope("listExecutions", org, a.membership(), null, CursorCodec.filters("workspaceId", null,
                    "automationId", null, "status", null, "from", null, "to", null), "createdAt.desc+id.desc"), List.of(pos[0], pos[1].toString()));
            http("L06_sem_filtro_profunda_apos_10000", a, path(org, "executions", "cursor=" + c));
            http("L03_workspace_5000_pagina1", a, path(org, "workspaces/" + hot + "/automations", null));
            http("L01_200_workspaces_pagina1", a, path(org, "workspaces", null));
        }
        http("M03_5000_clientes_pagina1", admin, path(org, "clients", null));

        Files.write(out.resolve("resultados.tsv"), results, StandardCharsets.UTF_8);
    }

    // ================================================================== massa

    /**
     * Gera, numa organizacao: {@code workspaces} workspaces (o 1o e o "quente"), {@code hotAutomations} automacoes no quente
     * e {@code otherAutomations} em cada um dos demais, uma versao publicada por automacao, {@code executions} execucoes em
     * 12 meses ({@code hotExecutions} no quente) com mistura de status, e {@code clients} clientes.
     */
    private Map<String, Object> generate(Org o, int workspaces, int hotAutomations, int otherAutomations, int executions, int hotExecutions, int clients) {
        String org = o.id().toString();
        String m = o.adminMembership().toString();
        DB.asSuperuser(
                "SET session_replication_role = replica",
                "DROP TABLE IF EXISTS m_ws, m_auto",
                "CREATE TEMP TABLE m_ws AS SELECT gen_random_uuid() AS workspace_id, i AS ws_index FROM generate_series(1, " + workspaces + ") i",
                "INSERT INTO workspaces (workspace_id, organization_id, client_id, name, status) SELECT workspace_id, '" + org
                        + "', NULL, 'Workspace ' || lpad(ws_index::text, 4, '0'), 1 FROM m_ws",
                "CREATE TEMP TABLE m_auto AS SELECT gen_random_uuid() AS automation_id, gen_random_uuid() AS version_id, w.workspace_id, w.ws_index, k AS auto_index "
                        + "FROM m_ws w, LATERAL generate_series(1, CASE WHEN w.ws_index = 1 THEN " + hotAutomations + " ELSE " + otherAutomations + " END) k",
                "CREATE INDEX ON m_auto (ws_index, auto_index)",
                "INSERT INTO automations (automation_id, organization_id, workspace_id, name, description, status, created_by_membership_id) "
                        + "SELECT automation_id, '" + org + "', workspace_id, 'Automacao ' || md5(automation_id::text), NULL, 1, '" + m + "' FROM m_auto",
                "INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, version_number, status, command_json, "
                        + "created_by_membership_id, published_by_membership_id, published_at) SELECT version_id, '" + org + "', workspace_id, automation_id, 1, 2, "
                        + ProductFixtures.COMMAND + ", '" + m + "', '" + m + "', now() FROM m_auto",
                "ANALYZE m_auto",
                "INSERT INTO executions (execution_id, organization_id, workspace_id, automation_id, automation_version_id, request_key, request_fingerprint, "
                        + "requested_by_membership_id, command_snapshot, status, status_reason, origin, created_at, started_at, finished_at) "
                        + "SELECT gen_random_uuid(), '" + org + "', a.workspace_id, a.automation_id, a.version_id, 'req-' || x.g, repeat('b', 64), '" + m + "', "
                        + ProductFixtures.COMMAND + ", "
                        + "CASE WHEN x.g % 20 = 0 THEN 1 WHEN x.g % 20 IN (1, 2) THEN 5 WHEN x.g % 20 = 3 THEN 6 ELSE 4 END, "
                        + "CASE WHEN x.g % 20 = 0 THEN NULL WHEN x.g % 20 IN (1, 2) THEN 3 WHEN x.g % 20 = 3 THEN 4 ELSE 1 END, 1, x.created, "
                        + "CASE WHEN x.g % 20 IN (0, 3) THEN NULL ELSE x.created + interval '1 second' END, "
                        + "CASE WHEN x.g % 20 = 0 THEN NULL WHEN x.g % 20 = 3 THEN x.created + interval '1 second' "
                        + "ELSE x.created + interval '1 second' + (random() * interval '10 minutes') END "
                        + "FROM (SELECT g, now() - (random() * interval '365 days') AS created, "
                        + "CASE WHEN g <= " + hotExecutions + " THEN 1 ELSE 2 + (g % " + (workspaces - 1) + ") END AS ws_index, "
                        + "1 + (g % CASE WHEN g <= " + hotExecutions + " THEN " + hotAutomations + " ELSE " + otherAutomations + " END) AS auto_index "
                        + "FROM generate_series(1, " + executions + ") g) x JOIN m_auto a ON a.ws_index = x.ws_index AND a.auto_index = x.auto_index",
                "INSERT INTO clients (client_id, organization_id, name, status) SELECT gen_random_uuid(), '" + org + "', 'Cliente ' || md5(g::text), "
                        + "CASE WHEN g % 10 = 0 THEN 3 WHEN g % 10 = 1 THEN 2 ELSE 1 END FROM generate_series(1, " + clients + ") g",
                "SET session_replication_role = origin");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("hot", UUID.fromString(AccessFixtures.value("SELECT workspace_id FROM workspaces WHERE organization_id = '" + org
                + "' AND name = 'Workspace 0001'")));
        r.put("ws2", UUID.fromString(AccessFixtures.value("SELECT workspace_id FROM workspaces WHERE organization_id = '" + org
                + "' AND name = 'Workspace 0002'")));
        r.put("ws3", UUID.fromString(AccessFixtures.value("SELECT workspace_id FROM workspaces WHERE organization_id = '" + org
                + "' AND name = 'Workspace 0003'")));
        return r;
    }

    // ================================================================== posicoes profundas (preparacao, mesma ordenacao da rota)

    private Object[] deepExecutionPosition(UUID org, UUID membership, ProductReads.ExecutionFilter f, int offset) {
        String sql = ProductReads.executionsSql(f, false).replace("LIMIT :max", "OFFSET :offset LIMIT 1")
                .replaceFirst("(?s)^\\s*SELECT .*?\\n\\s*FROM public.executions e", "SELECT e.created_at, e.execution_id FROM public.executions e");
        return tx.inOrganization(org, Mode.READ_ONLY, j -> bindExecutions(access.visibleWorkspacesQuery(j, sql, org, membership, ProductReads.READ), org, f)
                .param("offset", offset).query((rs, n) -> new Object[] {rs.getObject(1, OffsetDateTime.class), rs.getObject(2, UUID.class)}).single());
    }

    private String[] deepAutomationPosition(UUID org, UUID workspace, int offset) {
        return tx.inOrganization(org, Mode.READ_ONLY, j -> j.sql("SELECT left(a.name, 48), a.automation_id FROM public.automations a WHERE "
                + "a.organization_id = :org AND a.workspace_id = :w ORDER BY left(a.name, 48) COLLATE \"C\", a.automation_id OFFSET :off LIMIT 1")
                .param("org", org).param("w", workspace).param("off", offset)
                .query((rs, n) -> new String[] {rs.getString(1), rs.getObject(2, UUID.class).toString()}).single());
    }

    private String[] deepClientPosition(UUID org, int offset) {
        return tx.inOrganization(org, Mode.READ_ONLY, j -> j.sql("SELECT left(c.name, 48), c.client_id FROM public.clients c WHERE c.organization_id = :org "
                + "ORDER BY left(c.name, 48) COLLATE \"C\", c.client_id OFFSET :off LIMIT 1").param("org", org).param("off", offset)
                .query((rs, n) -> new String[] {rs.getString(1), rs.getObject(2, UUID.class).toString()}).single());
    }

    // ================================================================== casos

    private static JdbcClient.StatementSpec bindExecutions(JdbcClient.StatementSpec q, UUID org, ProductReads.ExecutionFilter f) {
        q = q.param("org", org);
        if (f.workspaceId() != null) {
            q = q.param("workspace", f.workspaceId());
        }
        if (f.automationId() != null) {
            q = q.param("automation", f.automationId());
        }
        if (f.status() != null) {
            q = q.param("status", f.status());
        }
        if (f.from() != null) {
            q = q.param("from", f.from());
        }
        if (f.to() != null) {
            q = q.param("to", f.to());
        }
        return q;
    }

    private static JdbcClient.StatementSpec visibility(JdbcClient.StatementSpec q, UUID org, UUID membership) {
        return q.param("access_org", org).param("access_membership", membership).param("access_permission", ProductReads.READ);
    }

    private void measureExecutions(String name, Actor a, UUID org, ProductReads.ExecutionFilter f, OffsetDateTime afterCreated, UUID afterId) {
        ProductReads reads = new ProductReads(access);
        UUID m = a.membership();
        String sql = ProductReads.executionsSql(f, afterId != null);
        Function<JdbcClient.StatementSpec, JdbcClient.StatementSpec> bind = q -> {
            JdbcClient.StatementSpec b = bindExecutions(visibility(q, org, m), org, f).param("max", PAGE + 1);
            return afterId == null ? b : b.param("afterCreated", afterCreated).param("afterId", afterId);
        };
        time(name, a, "componente", () -> tx.inOrganization(org, Mode.READ_ONLY, j -> reads.listExecutions(j, org, m, f, afterCreated, afterId, PAGE + 1).size()));
        time(name, a, "consulta", () -> tx.inOrganization(org, Mode.READ_ONLY, j -> bind.apply(j.sql(sql)).query().listOfRows().size()));
        explain(name, a, org, sql, bind);
    }

    private void measureWorkspaces(String name, Actor a, UUID org) {
        ProductReads reads = new ProductReads(access);
        UUID m = a.membership();
        String sql = ProductReads.workspacesSql(false, false);
        Function<JdbcClient.StatementSpec, JdbcClient.StatementSpec> bind = q -> visibility(q, org, m).param("prefix", ProductReads.NAME_SORT_PREFIX)
                .param("max", PAGE + 1);
        time(name, a, "componente", () -> tx.inOrganization(org, Mode.READ_ONLY, j -> reads.listWorkspaces(j, org, m, null, null, null, PAGE + 1).size()));
        time(name, a, "consulta", () -> tx.inOrganization(org, Mode.READ_ONLY, j -> bind.apply(j.sql(sql)).query().listOfRows().size()));
        explain(name, a, org, sql, bind);
    }

    private void measureAutomations(String name, Actor a, UUID org, UUID workspace, String afterKey, UUID afterId) {
        ProductReads reads = new ProductReads(access);
        UUID m = a.membership();
        String sql = ProductReads.automationsSql(false, afterId != null);
        Function<JdbcClient.StatementSpec, JdbcClient.StatementSpec> bind = q -> {
            JdbcClient.StatementSpec b = q.param("org", org).param("workspace", workspace).param("prefix", ProductReads.NAME_SORT_PREFIX).param("max", PAGE + 1);
            return afterId == null ? b : b.param("afterKey", afterKey).param("afterId", afterId);
        };
        time(name, a, "componente", () -> tx.inOrganization(org, Mode.READ_ONLY,
                j -> reads.listAutomations(j, org, m, workspace, null, afterKey, afterId, PAGE + 1).size()));
        time(name, a, "consulta", () -> tx.inOrganization(org, Mode.READ_ONLY, j -> bind.apply(j.sql(sql)).query().listOfRows().size()));
        explain(name, a, org, sql, bind);
    }

    private void measureVersions(String name, Actor a, UUID org, UUID automation) {
        ProductReads reads = new ProductReads(access);
        UUID m = a.membership();
        String sql = ProductReads.versionsSql(false);
        Function<JdbcClient.StatementSpec, JdbcClient.StatementSpec> bind = q -> q.param("org", org).param("automation", automation).param("max", PAGE + 1);
        time(name, a, "componente", () -> tx.inOrganization(org, Mode.READ_ONLY, j -> reads.listVersions(j, org, m, automation, null, PAGE + 1).size()));
        time(name, a, "consulta", () -> tx.inOrganization(org, Mode.READ_ONLY, j -> bind.apply(j.sql(sql)).query().listOfRows().size()));
        explain(name, a, org, sql, bind);
    }

    private void measureEvents(String name, Actor a, UUID org, UUID execution) {
        ProductReads reads = new ProductReads(access);
        UUID m = a.membership();
        String sql = ProductReads.eventsSql(false);
        Function<JdbcClient.StatementSpec, JdbcClient.StatementSpec> bind = q -> q.param("org", org).param("execution", execution).param("max", PAGE + 1);
        time(name, a, "componente", () -> tx.inOrganization(org, Mode.READ_ONLY, j -> reads.listEvents(j, org, m, execution, null, PAGE + 1).size()));
        time(name, a, "consulta", () -> tx.inOrganization(org, Mode.READ_ONLY, j -> bind.apply(j.sql(sql)).query().listOfRows().size()));
        explain(name, a, org, sql, bind);
    }

    /** M03: o caminho da rota e permissao organizacional + SQL de AdminReadController.clientsSql (mesmo texto). */
    private void measureClients(String name, Actor a, UUID org, String afterKey, UUID afterId) {
        UUID m = a.membership();
        String sql = AdminReadController.clientsSql(false, afterId != null);
        Function<JdbcClient.StatementSpec, JdbcClient.StatementSpec> bind = q -> {
            JdbcClient.StatementSpec b = q.param("org", org).param("prefix", 48).param("max", PAGE + 1);
            return afterId == null ? b : b.param("afterKey", afterKey).param("afterId", afterId);
        };
        time(name, a, "componente", () -> tx.inOrganization(org, Mode.READ_ONLY, j -> {
            access.requireAnyOrganizationPermission(j, org, m, "org.manage", "external_access.manage");
            return bind.apply(j.sql(sql)).query().listOfRows().size();
        }));
        time(name, a, "consulta", () -> tx.inOrganization(org, Mode.READ_ONLY, j -> bind.apply(j.sql(sql)).query().listOfRows().size()));
        explain(name, a, org, sql, bind);
    }

    // ================================================================== medidas

    private void time(String name, Actor a, String item, Supplier<Integer> call) {
        int rows = 0;
        for (int i = 0; i < WARMUP; i++) {
            rows = call.get();
        }
        List<Double> ms = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            long t = System.nanoTime();
            rows = call.get();
            ms.add((System.nanoTime() - t) / 1_000_000.0);
        }
        record(name, a.name(), item, ms, rows);
    }

    private void http(String name, Actor a, String path) throws InterruptedException {
        List<Double> ms = new ArrayList<>();
        int rows = -1;
        for (int i = 0; i < WARMUP + RUNS; i++) {
            Thread.sleep(HTTP_INTERVAL_MS);
            long t = System.nanoTime();
            BrowserClient.Response r = a.http().get(path);
            double d = (System.nanoTime() - t) / 1_000_000.0;
            assertThat(r.status()).as(name + " http status").isEqualTo(200);
            rows = ReadRoutesSupport.JSON.readTree(r.body()).get("items").size();
            if (i >= WARMUP) {
                ms.add(d);
            }
        }
        record(name, a.name(), "http", ms, rows);
    }

    private void record(String name, String actor, String item, List<Double> ms, int rows) {
        List<Double> s = new ArrayList<>(ms);
        Collections.sort(s);
        double p50 = s.get((int) Math.ceil(0.50 * s.size()) - 1);
        double p95 = s.get((int) Math.ceil(0.95 * s.size()) - 1);
        double max = s.get(s.size() - 1);
        results.add(String.format(java.util.Locale.ROOT, "%s\t%s\t%s\t%d\t%.2f\t%.2f\t%.2f\t%d", name, actor, item, s.size(), p50, p95, max, rows));
    }

    private void explain(String name, Actor a, UUID org, String sql, Function<JdbcClient.StatementSpec, JdbcClient.StatementSpec> bind) {
        List<String> plan = tx.inOrganization(org, Mode.READ_ONLY,
                j -> bind.apply(j.sql("EXPLAIN (ANALYZE, BUFFERS) " + sql)).query((rs, n) -> rs.getString(1)).list());
        try {
            Files.writeString(out.resolve("planos").resolve(name + "__" + a.name() + ".txt"), String.join("\n", plan) + "\n", StandardCharsets.UTF_8);
            Path q = out.resolve("consultas").resolve(name + ".sql");
            if (!Files.exists(q)) {
                Files.writeString(q, sql + "\n", StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String path(UUID org, String route, String query) {
        return "/api/v1/o/" + org + "/" + route + (query == null ? "" : "?" + query);
    }

    // ================================================================== ambiente e volumes

    private void writeEnvironment(Org o, Org o2, Org o3, UUID hot, long loadMs) throws Exception {
        List<String> env = new ArrayList<>();
        env.add("imagem=" + com.nekkora.core.testsupport.RealDatabase.IMAGE);
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement()) {
            env.add("driver=" + c.getMetaData().getDriverName() + " " + c.getMetaData().getDriverVersion());
            for (String q : List.of("SELECT version()", "SHOW shared_buffers", "SHOW work_mem", "SHOW effective_cache_size", "SHOW max_parallel_workers_per_gather",
                    "SHOW jit", "SHOW random_page_cost", "SELECT current_setting('server_version_num')")) {
                try (ResultSet rs = st.executeQuery(q)) {
                    rs.next();
                    env.add(q + " => " + rs.getString(1));
                }
            }
            List<String> vol = new ArrayList<>();
            vol.add("tabela\torganizacao\tlinhas");
            for (String t : List.of("workspaces", "automations", "automation_versions", "executions", "execution_events", "clients")) {
                try (ResultSet rs = st.executeQuery("SELECT organization_id::text, count(*) FROM " + t + " GROUP BY 1 ORDER BY 1")) {
                    while (rs.next()) {
                        String org = rs.getString(1);
                        String label = org.equals(o.id().toString()) ? "MEDIDA" : org.equals(o2.id().toString()) ? "O2" : org.equals(o3.id().toString()) ? "O3" : org;
                        vol.add(t + "\t" + label + "\t" + rs.getLong(2));
                    }
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM executions WHERE workspace_id = '" + hot + "'")) {
                rs.next();
                vol.add("executions\tMEDIDA/workspace_quente\t" + rs.getLong(1));
            }
            try (ResultSet rs = st.executeQuery("SELECT status, count(*) FROM executions WHERE organization_id = '" + o.id() + "' GROUP BY 1 ORDER BY 1")) {
                while (rs.next()) {
                    vol.add("executions\tMEDIDA/status=" + rs.getInt(1) + "\t" + rs.getLong(2));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT min(created_at)::text, max(created_at)::text FROM executions WHERE organization_id = '" + o.id() + "'")) {
                rs.next();
                vol.add("executions\tMEDIDA/created_at min..max\t" + rs.getString(1) + " .. " + rs.getString(2));
            }
            Files.write(out.resolve("volumes.tsv"), vol, StandardCharsets.UTF_8);
        }
        env.add("jvm_processadores_disponiveis=" + Runtime.getRuntime().availableProcessors());
        env.add("jvm_memoria_max_mb=" + Runtime.getRuntime().maxMemory() / 1024 / 1024);
        env.add("os=" + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        env.add("java=" + System.getProperty("java.version"));
        env.add("carga_da_massa_ms=" + loadMs + " (session_replication_role=replica so na carga; ANALYZE depois)");
        env.add("aquecimento=" + WARMUP + " execucoes descartadas; medidas=" + RUNS + "; pagina=" + PAGE + " (limit+1=" + (PAGE + 1) + " linhas lidas)");
        env.add("http: intervalo de " + HTTP_INTERVAL_MS + " ms entre requisicoes (limitador R7: 60 de capacidade, 2/s)");
        env.add(docker("info", "--format", "docker_ncpu={{.NCPU}} docker_mem_total={{.MemTotal}} docker_os={{.OperatingSystem}} docker_server={{.ServerVersion}}"));
        env.add(docker("ps", "--filter", "ancestor=" + com.nekkora.core.testsupport.RealDatabase.IMAGE, "--format", "container={{.ID}} {{.Image}} {{.Status}}"));
        Files.write(out.resolve("ambiente.txt"), env, StandardCharsets.UTF_8);
    }

    private static String docker(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            return "docker " + args[0] + ": " + s.replace("\n", " | ");
        } catch (Exception e) {
            return "docker " + args[0] + ": NAO OBTIDO (" + e.getClass().getSimpleName() + ")";
        }
    }
}
