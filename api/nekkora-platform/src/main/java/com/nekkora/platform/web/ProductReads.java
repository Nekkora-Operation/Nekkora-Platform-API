package com.nekkora.platform.web;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.platform.access.WorkspaceAccess;

/**
 * GAP-06: autorizacao + consulta de cada rota de leitura do produto interno (contrato GAP-02/03/06, ROTA-L01 a L08;
 * DC-08 a DC-12). Usada pelo controlador E pela medicao do DC-07, para que a medicao execute exatamente o mesmo SQL, os
 * mesmos parametros e a mesma autorizacao (inclusive a checagem de consistencia do catalogo). Sempre dentro de uma
 * transacao da organizacao (OrgTransactions.inOrganization: RLS e contexto organizacional ativos). Toda autorizacao passa
 * pelos metodos do componente compartilhado (nunca SQL de autorizacao proprio). NAO expoe command_json,
 * command_snapshot, payload, error_summary, pid, exit_code, Worker/Agent nem o criador da automacao.
 */
public final class ProductReads {

    public static final String READ = "automation.read";
    /** Prefixo de ordenacao por nome (§1.5.1): mesma expressao no ORDER BY e na comparacao da pagina seguinte. */
    public static final int NAME_SORT_PREFIX = 48;
    /** Chave interna da ordenacao por nome; o controlador a retira antes de responder. */
    public static final String SORT_KEY = "\u0000sortKey";

    private final WorkspaceAccess access;

    public ProductReads(WorkspaceAccess access) {
        this.access = access;
    }

    /** Filtro da L06 (ja validado; datas com fuso, periodo semiaberto [from, to)). */
    public record ExecutionFilter(UUID workspaceId, UUID automationId, List<Short> status, OffsetDateTime from, OffsetDateTime to) {
    }

    // ------------------------------------------------------------------ L01 / L02

    private static final String WORKSPACE_COLUMNS = """
            SELECT w.workspace_id, w.name, left(w.name, :prefix) AS sort_key, w.description, w.status, w.client_id, c.name AS client_name,
                   w.created_at, w.updated_at
              FROM public.workspaces w
              LEFT JOIN public.clients c ON c.organization_id = w.organization_id AND c.client_id = w.client_id
            """;

    public List<Map<String, Object>> listWorkspaces(JdbcClient j, UUID org, UUID membership, List<Short> status, String afterKey, UUID afterId,
                                                    int max) {
        JdbcClient.StatementSpec q = access.visibleWorkspacesQuery(j, workspacesSql(status != null, afterId != null), org, membership, READ)
                .param("prefix", NAME_SORT_PREFIX).param("max", max);
        if (status != null) {
            q = q.param("status", status);
        }
        if (afterId != null) {
            q = q.param("afterKey", afterKey).param("afterId", afterId);
        }
        return q.query((rs, n) -> workspace(rs)).list();
    }

    /** SQL da L01 (tambem usado pela medicao do DC-07, para medir exatamente o texto da rota). */
    public static String workspacesSql(boolean status, boolean after) {
        StringBuilder sql = new StringBuilder(WORKSPACE_COLUMNS).append(" WHERE ").append(WorkspaceAccess.VISIBLE_WORKSPACE_CONDITION);
        if (status) {
            sql.append(" AND w.status IN (:status)");
        }
        if (after) {
            sql.append(" AND (left(w.name, :prefix) COLLATE \"C\", w.workspace_id) > (CAST(:afterKey AS text) COLLATE \"C\", :afterId)");
        }
        return sql.append(" ORDER BY left(w.name, :prefix) COLLATE \"C\", w.workspace_id LIMIT :max").toString();
    }

    public Map<String, Object> getWorkspace(JdbcClient j, UUID org, UUID membership, UUID workspace) {
        access.requireWorkspace(j, org, membership, workspace, READ);
        Map<String, Object> m = j.sql(WORKSPACE_COLUMNS + " WHERE w.organization_id = :org AND w.workspace_id = :workspace")
                .param("prefix", NAME_SORT_PREFIX).param("org", org).param("workspace", workspace).query((rs, n) -> workspace(rs)).single();
        m.remove(SORT_KEY);
        return m;
    }

    private static Map<String, Object> workspace(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workspaceId", rs.getObject("workspace_id", UUID.class).toString());
        m.put("name", rs.getString("name"));
        m.put("description", rs.getString("description"));
        m.put("status", Labels.label(Labels.Kind.RESOURCE, rs.getInt("status")));
        UUID client = rs.getObject("client_id", UUID.class);
        m.put("client", client == null ? null : ref("clientId", client, rs.getString("client_name")));
        m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
        m.put("updatedAt", rs.getObject("updated_at", OffsetDateTime.class));
        m.put(SORT_KEY, rs.getString("sort_key"));
        return m;
    }

    // ------------------------------------------------------------------ L03 / L04

    private static final String AUTOMATION_COLUMNS = """
            SELECT a.automation_id, a.workspace_id, a.name, left(a.name, :prefix) AS sort_key, a.description, a.status, a.created_at, a.updated_at,
                   lv.automation_version_id AS latest_version_id, lv.version_number AS latest_version_number, lv.published_at AS latest_published_at
              FROM public.automations a
              LEFT JOIN LATERAL (SELECT v.automation_version_id, v.version_number, v.published_at
                                   FROM public.automation_versions v
                                  WHERE v.organization_id = a.organization_id AND v.automation_id = a.automation_id AND v.status = 2
                                  ORDER BY v.version_number DESC LIMIT 1) lv ON true
            """;

    public List<Map<String, Object>> listAutomations(JdbcClient j, UUID org, UUID membership, UUID workspace, List<Short> status, String afterKey,
                                                     UUID afterId, int max) {
        access.requireWorkspace(j, org, membership, workspace, READ);
        JdbcClient.StatementSpec q = j.sql(automationsSql(status != null, afterId != null)).param("org", org).param("workspace", workspace).param("prefix", NAME_SORT_PREFIX)
                .param("max", max);
        if (status != null) {
            q = q.param("status", status);
        }
        if (afterId != null) {
            q = q.param("afterKey", afterKey).param("afterId", afterId);
        }
        return q.query((rs, n) -> automation(rs)).list();
    }

    /** SQL da L03 (tambem usado pela medicao do DC-07). */
    public static String automationsSql(boolean status, boolean after) {
        StringBuilder sql = new StringBuilder(AUTOMATION_COLUMNS).append(" WHERE a.organization_id = :org AND a.workspace_id = :workspace");
        if (status) {
            sql.append(" AND a.status IN (:status)");
        }
        if (after) {
            sql.append(" AND (left(a.name, :prefix) COLLATE \"C\", a.automation_id) > (CAST(:afterKey AS text) COLLATE \"C\", :afterId)");
        }
        return sql.append(" ORDER BY left(a.name, :prefix) COLLATE \"C\", a.automation_id LIMIT :max").toString();
    }

    public Map<String, Object> getAutomation(JdbcClient j, UUID org, UUID membership, UUID automation) {
        access.requireAutomation(j, org, membership, automation, READ);
        Map<String, Object> m = j.sql(AUTOMATION_COLUMNS + " WHERE a.organization_id = :org AND a.automation_id = :automation")
                .param("prefix", NAME_SORT_PREFIX).param("org", org).param("automation", automation).query((rs, n) -> automation(rs)).single();
        m.remove(SORT_KEY);
        return m;
    }

    private static Map<String, Object> automation(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("automationId", rs.getObject("automation_id", UUID.class).toString());
        m.put("workspaceId", rs.getObject("workspace_id", UUID.class).toString());
        m.put("name", rs.getString("name"));
        m.put("description", rs.getString("description"));
        m.put("status", Labels.label(Labels.Kind.RESOURCE, rs.getInt("status")));
        UUID latest = rs.getObject("latest_version_id", UUID.class);
        if (latest == null) {
            m.put("latestPublishedVersion", null);
        } else {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("automationVersionId", latest.toString());
            v.put("versionNumber", rs.getInt("latest_version_number"));
            v.put("publishedAt", rs.getObject("latest_published_at", OffsetDateTime.class));
            m.put("latestPublishedVersion", v);
        }
        m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
        m.put("updatedAt", rs.getObject("updated_at", OffsetDateTime.class));
        m.put(SORT_KEY, rs.getString("sort_key"));
        return m;
    }

    // ------------------------------------------------------------------ L05

    public List<Map<String, Object>> listVersions(JdbcClient j, UUID org, UUID membership, UUID automation, Long beforeNumber, int max) {
        access.requireAutomation(j, org, membership, automation, READ);
        JdbcClient.StatementSpec q = j.sql(versionsSql(beforeNumber != null)).param("org", org).param("automation", automation).param("max", max);
        if (beforeNumber != null) {
            q = q.param("before", beforeNumber);
        }
        return q.query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("automationVersionId", rs.getObject("automation_version_id", UUID.class).toString());
            m.put("versionNumber", rs.getInt("version_number"));
            m.put("status", Labels.label(Labels.Kind.VERSION, rs.getInt("status")));
            m.put("publishedAt", rs.getObject("published_at", OffsetDateTime.class));
            m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
            return m;
        }).list();
    }

    /** SQL da L05 (tambem usado pela medicao do DC-07). */
    public static String versionsSql(boolean after) {
        return "SELECT automation_version_id, version_number, status, published_at, created_at FROM public.automation_versions "
                + "WHERE organization_id = :org AND automation_id = :automation" + (after ? " AND version_number < :before" : "")
                + " ORDER BY version_number DESC LIMIT :max";
    }

    // ------------------------------------------------------------------ L06 / L07

    private static final String EXECUTION_COLUMNS = """
            SELECT e.execution_id, e.workspace_id, e.automation_id, a.name AS automation_name, e.automation_version_id, v.version_number,
                   e.origin, e.status, e.status_reason, e.created_at, e.started_at, e.finished_at, e.cancel_requested_at
            """;
    private static final String EXECUTION_FROM = """
              FROM public.executions e
              JOIN public.automations a ON a.organization_id = e.organization_id AND a.automation_id = e.automation_id
              JOIN public.automation_versions v ON v.organization_id = e.organization_id AND v.automation_version_id = e.automation_version_id
            """;

    /**
     * L06: filtros por workspace/automacao seguem a regra geral (404 pelo componente); a lista e SEMPRE restrita aos
     * workspaces visiveis pela condicao compartilhada.
     */
    public List<Map<String, Object>> listExecutions(JdbcClient j, UUID org, UUID membership, ExecutionFilter f, OffsetDateTime afterCreated,
                                                    UUID afterId, int max) {
        if (f.workspaceId() != null) {
            access.requireWorkspace(j, org, membership, f.workspaceId(), READ);
        }
        if (f.automationId() != null) {
            access.requireAutomation(j, org, membership, f.automationId(), READ);
        }
        JdbcClient.StatementSpec q = access.visibleWorkspacesQuery(j, executionsSql(f, afterId != null), org, membership, READ).param("org", org).param("max", max);
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
        if (afterId != null) {
            q = q.param("afterCreated", afterCreated).param("afterId", afterId);
        }
        return q.query((rs, n) -> execution(rs)).list();
    }

    /** SQL da L06 (tambem usado pela medicao do DC-07). */
    public static String executionsSql(ExecutionFilter f, boolean after) {
        StringBuilder sql = new StringBuilder(EXECUTION_COLUMNS).append(EXECUTION_FROM)
                .append(" WHERE e.organization_id = :org AND e.workspace_id IN (SELECT w.workspace_id FROM public.workspaces w WHERE ")
                .append(WorkspaceAccess.VISIBLE_WORKSPACE_CONDITION).append(")");
        if (f.workspaceId() != null) {
            sql.append(" AND e.workspace_id = :workspace");
        }
        if (f.automationId() != null) {
            sql.append(" AND e.automation_id = :automation");
        }
        if (f.status() != null) {
            sql.append(" AND e.status IN (:status)");
        }
        if (f.from() != null) {
            sql.append(" AND e.created_at >= :from");
        }
        if (f.to() != null) {
            sql.append(" AND e.created_at < :to");
        }
        if (after) {
            sql.append(" AND (e.created_at, e.execution_id) < (:afterCreated, :afterId)");
        }
        return sql.append(" ORDER BY e.created_at DESC, e.execution_id DESC LIMIT :max").toString();
    }

    public Map<String, Object> getExecution(JdbcClient j, UUID org, UUID membership, UUID execution) {
        access.requireExecution(j, org, membership, execution, READ);
        return j.sql(EXECUTION_COLUMNS + """
                       , e.retry_of_execution_id, e.requested_by_membership_id, ru.display_name AS requester_name
                """ + EXECUTION_FROM + """
                  LEFT JOIN public.organization_memberships rm ON rm.organization_id = e.organization_id
                                                              AND rm.membership_id = e.requested_by_membership_id
                  LEFT JOIN public.users ru ON ru.organization_id = rm.organization_id AND ru.user_id = rm.user_id
                 WHERE e.organization_id = :org AND e.execution_id = :execution
                """).param("org", org).param("execution", execution).query((rs, n) -> {
                    Map<String, Object> m = execution(rs);
                    UUID retry = rs.getObject("retry_of_execution_id", UUID.class);
                    m.put("retryOfExecutionId", retry == null ? null : retry.toString());
                    UUID requester = rs.getObject("requested_by_membership_id", UUID.class);
                    // DC-09: solicitante da execucao manual; nulo na agendada (009: requested_by nulo <=> origin = 2)
                    m.put("requestedBy", requester == null ? null : Map.of("membershipId", requester.toString(), "displayName",
                            rs.getString("requester_name")));
                    return m;
                }).single();
    }

    private static Map<String, Object> execution(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("executionId", rs.getObject("execution_id", UUID.class).toString());
        m.put("workspaceId", rs.getObject("workspace_id", UUID.class).toString());
        m.put("automation", ref("automationId", rs.getObject("automation_id", UUID.class), rs.getString("automation_name")));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("automationVersionId", rs.getObject("automation_version_id", UUID.class).toString());
        v.put("versionNumber", rs.getInt("version_number"));
        m.put("automationVersion", v);
        m.put("origin", Labels.label(Labels.Kind.ORIGIN, rs.getInt("origin")));
        m.put("status", Labels.label(Labels.Kind.EXECUTION_STATUS, rs.getInt("status")));
        int reason = rs.getInt("status_reason");
        m.put("statusReason", rs.wasNull() ? null : Labels.label(Labels.Kind.EXECUTION_REASON, reason));
        OffsetDateTime started = rs.getObject("started_at", OffsetDateTime.class);
        OffsetDateTime finished = rs.getObject("finished_at", OffsetDateTime.class);
        m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
        m.put("startedAt", started);
        m.put("finishedAt", finished);
        // §1.7: finished - started em ms inteiros (truncado); nulo se faltar algum; nunca contra o relogio atual
        m.put("durationMs", started != null && finished != null ? Duration.between(started, finished).toMillis() : null);
        m.put("cancelRequestedAt", rs.getObject("cancel_requested_at", OffsetDateTime.class));
        return m;
    }

    // ------------------------------------------------------------------ L08

    /** DC-12: execucao inexistente ou sem acesso -> 404 (pelo componente), diferente do portal (lista vazia). */
    public List<Map<String, Object>> listEvents(JdbcClient j, UUID org, UUID membership, UUID execution, Long afterSequence, int max) {
        access.requireExecution(j, org, membership, execution, READ);
        JdbcClient.StatementSpec q = j.sql(eventsSql(afterSequence != null)).param("org", org).param("execution", execution).param("max", max);
        if (afterSequence != null) {
            q = q.param("after", afterSequence);
        }
        return q.query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sequence", rs.getLong("sequence"));
            m.put("type", Labels.label(Labels.Kind.EVENT_TYPE, rs.getInt("event_type")));
            m.put("occurredAt", rs.getObject("occurred_at", OffsetDateTime.class));
            m.put("receivedAt", rs.getObject("received_at", OffsetDateTime.class));
            return m;
        }).list();
    }

    /** SQL da L08 (tambem usado pela medicao do DC-07). */
    public static String eventsSql(boolean after) {
        return "SELECT sequence, event_type, occurred_at, received_at FROM public.execution_events WHERE organization_id = :org "
                + "AND execution_id = :execution" + (after ? " AND sequence > :after" : "") + " ORDER BY sequence LIMIT :max";
    }

    private static Map<String, Object> ref(String idField, UUID id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(idField, id.toString());
        m.put("name", name);
        return m;
    }
}
