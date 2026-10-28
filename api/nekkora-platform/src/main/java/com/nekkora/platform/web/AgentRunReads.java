package com.nekkora.platform.web;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.platform.access.WorkspaceAccess;

/**
 * Consultas das execucoes locais registradas pelo Agent (contrato Platform-Agent 1.0, sec. 9 e 10), sob
 * {@code automation.read} no workspace da execucao, pela regra unica de {@link WorkspaceAccess}. Quatro dimensoes sempre
 * separadas: processo, resultado, transporte e historico (com os motivos de lacuna, cada um com sua origem). Ordem dos
 * eventos: so a sequence (ordem de observacao do Agent), nunca o relogio. Nao expoe stdout/stderr, caminhos, conteudo de
 * negocio nem hash de credencial; o envelope ja nao tem texto livre.
 */
public final class AgentRunReads {

    public static final String READ = "automation.read";
    static final int CONNECTED_SECONDS = 180;
    static final String[] STATES = {null, "READY", "STARTING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "BLOCKED", "OUTCOME_UNKNOWN"};
    static final String[] SOURCES = {null, "AGENT", "FRAMEWORK", "COLLECTOR"};
    static final String[] SEVERITIES = {null, "INFO", "WARNING", "ERROR"};
    static final String[] CATEGORIES = {null, "LIFECYCLE", "ACTIVITY", "BUSINESS", "FAILURE", "TELEMETRY"};
    static final String[] AUTH_STATES = {null, "ACTIVE", "REVOKED", "EXPIRED"};

    private final WorkspaceAccess access;

    public AgentRunReads(WorkspaceAccess access) {
        this.access = access;
    }

    public record Filter(UUID workspaceId, UUID automationId, List<Short> states, OffsetDateTime from, OffsetDateTime to) {
    }

    static final String COLUMNS = """
            SELECT r.agent_run_id, r.agent_execution_id, r.framework_execution_id, r.platform_execution_id, r.agent_id, r.worker_id, r.workspace_id,
                   r.automation_id, r.automation_version_id, r.authorization_id, r.origin, r.authorization_state_at_registration, r.registered_at,
                   r.supervisor_state, r.supervisor_code, r.exit_code, r.supervisor_counts::text AS supervisor_counts, r.agent_created_at,
                   r.agent_started_at, r.agent_ended_at, r.adopted, r.suspensions, r.locks, r.supervisor_reports, r.supervisor_conflicts, r.last_report_at,
                   r.events_stored, r.stored_through, r.settled_through, r.max_sequence, r.declared_missing, r.collection_completed_sequence,
                   r.lost_at_source, r.lost_local, r.purged_local, r.invalid_refused, r.framework_events, r.last_event_at,
                   a.last_heartbeat_at, a.last_heartbeat_at > clock_timestamp() - make_interval(secs => :connected) AS connected,
                   (SELECT count(*) FROM public.agent_ingestion_refusals x WHERE x.agent_run_id = r.agent_run_id AND x.kind IN (1, 2, 5)) AS delivery_conflicts
              FROM public.agent_runs r
              JOIN public.workspaces w ON w.organization_id = r.organization_id AND w.workspace_id = r.workspace_id
              JOIN public.agents a ON a.organization_id = r.organization_id AND a.agent_id = r.agent_id
            """;

    public List<Map<String, Object>> list(JdbcClient j, UUID org, UUID membership, Filter f, OffsetDateTime afterRegistered, UUID afterId, int max) {
        StringBuilder sql = new StringBuilder(COLUMNS).append(" WHERE ").append(WorkspaceAccess.VISIBLE_WORKSPACE_CONDITION);
        if (f.workspaceId() != null) {
            sql.append(" AND r.workspace_id = :workspace");
        }
        if (f.automationId() != null) {
            sql.append(" AND r.automation_id = :automation");
        }
        if (f.states() != null) {
            sql.append(" AND r.supervisor_state IN (:states)");
        }
        if (f.from() != null) {
            sql.append(" AND r.registered_at >= :from");
        }
        if (f.to() != null) {
            sql.append(" AND r.registered_at < :to");
        }
        if (afterId != null) {
            sql.append(" AND (r.registered_at, r.agent_run_id) > (:afterRegistered, :afterId)");
        }
        sql.append(" ORDER BY r.registered_at, r.agent_run_id LIMIT :max");
        JdbcClient.StatementSpec q = access.visibleWorkspacesQuery(j, sql.toString(), org, membership, READ).param("connected", CONNECTED_SECONDS)
                .param("max", max);
        if (f.workspaceId() != null) {
            q = q.param("workspace", f.workspaceId());
        }
        if (f.automationId() != null) {
            q = q.param("automation", f.automationId());
        }
        if (f.states() != null) {
            q = q.param("states", f.states());
        }
        if (f.from() != null) {
            q = q.param("from", f.from());
        }
        if (f.to() != null) {
            q = q.param("to", f.to());
        }
        if (afterId != null) {
            q = q.param("afterRegistered", afterRegistered).param("afterId", afterId);
        }
        return q.query((rs, n) -> run(rs)).list();
    }

    public Map<String, Object> get(JdbcClient j, UUID org, UUID membership, UUID runId) {
        UUID workspace = j.sql("SELECT workspace_id FROM public.agent_runs WHERE organization_id = ? AND agent_run_id = ?").param(org).param(runId)
                .query(UUID.class).optional().orElseThrow(() -> new ApiException(ApiError.NOT_FOUND, "execucao_agent"));
        access.requireWorkspace(j, org, membership, workspace, READ);
        Map<String, Object> m = j.sql(COLUMNS + " WHERE r.organization_id = :org AND r.agent_run_id = :run").param("connected", CONNECTED_SECONDS)
                .param("org", org).param("run", runId).query((rs, n) -> run(rs)).single();
        // contagem das transacoes vistas nos eventos do Framework (so codigos; nunca valores)
        Map<String, Long> tx = new LinkedHashMap<>();
        j.sql("""
                SELECT event_code, count(*) FROM public.agent_run_events
                 WHERE agent_run_id = ? AND source = 2 AND event_code IN ('TRANSACTION_STARTED', 'TRANSACTION_COMPLETED', 'TRANSACTION_FAILED',
                       'BUSINESS_REJECTED', 'BUSINESS_RULE_REJECTED', 'TRANSACTION_SKIPPED', 'OUTCOME_UNKNOWN')
                 GROUP BY event_code ORDER BY event_code
                """).param(runId).query((rs, n) -> tx.put(rs.getString(1), rs.getLong(2))).list();
        m.put("transactionsSeen", tx);
        // retencao no servidor (026): dimensao propria; nunca muda a completude, que e a da entrega (contrato, sec. 9 e 11)
        m.put("retention", j.sql("SELECT count(*) FILTER (WHERE purged_at IS NOT NULL), count(*) FROM public.agent_run_events WHERE agent_run_id = ?")
                .param(runId).query((rs, n) -> {
                    long purged = rs.getLong(1);
                    long total = rs.getLong(2);
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("state", purged == 0 ? "AVAILABLE" : purged == total ? "PURGED" : "PARTIALLY_PURGED");
                    r.put("eventsPurged", purged);
                    return r;
                }).single());
        return m;
    }

    public List<Map<String, Object>> events(JdbcClient j, UUID org, UUID membership, UUID runId, Long afterSequence, int max) {
        UUID workspace = j.sql("SELECT workspace_id FROM public.agent_runs WHERE organization_id = ? AND agent_run_id = ?").param(org).param(runId)
                .query(UUID.class).optional().orElseThrow(() -> new ApiException(ApiError.NOT_FOUND, "execucao_agent"));
        access.requireWorkspace(j, org, membership, workspace, READ);
        return j.sql("""
                SELECT sequence, source, event_code, severity, category, step_id, studio_step_id, error_code, timestamp_utc, observed_utc, received_at,
                       purged_at IS NOT NULL AS purged,
                       envelope ->> 'phase' AS phase, envelope ->> 'action' AS action, envelope ->> 'status' AS status, envelope -> 'attempt' AS attempt,
                       envelope -> 'elapsed_ms' AS elapsed_ms, envelope ->> 'transaction_ref' AS transaction_ref, (envelope -> 'counts')::text AS counts
                  FROM public.agent_run_events
                 WHERE agent_run_id = ? AND sequence > ?
                 ORDER BY sequence LIMIT ?
                """).param(runId).param(afterSequence == null ? 0L : afterSequence).param(max).query((rs, n) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("sequence", rs.getLong("sequence"));
            e.put("source", SOURCES[rs.getShort("source")]);
            e.put("eventCode", rs.getString("event_code"));
            e.put("severity", SEVERITIES[rs.getShort("severity")]);
            e.put("category", CATEGORIES[rs.getShort("category")]);
            e.put("phase", rs.getString("phase"));
            e.put("stepId", rs.getString("step_id"));
            e.put("studioStepId", rs.getObject("studio_step_id", UUID.class));
            e.put("action", rs.getString("action"));
            e.put("status", rs.getString("status"));
            e.put("errorCode", rs.getString("error_code"));
            e.put("attempt", number(rs.getString("attempt")));
            e.put("elapsedMs", number(rs.getString("elapsed_ms")));
            e.put("transactionRef", rs.getString("transaction_ref"));
            e.put("counts", rs.getString("counts") == null ? null : RawJson.of(rs.getString("counts")));
            e.put("timestampUtc", rs.getObject("timestamp_utc", OffsetDateTime.class));
            e.put("observedUtc", rs.getObject("observed_utc", OffsetDateTime.class));
            e.put("receivedAt", rs.getObject("received_at", OffsetDateTime.class));
            // expurgado pela retencao: so a classificacao e os instantes ficam; o conteudo da etapa vem nulo
            e.put("purged", rs.getBoolean("purged"));
            return e;
        }).list();
    }

    private static Long number(String json) {
        return json == null ? null : Long.valueOf(json);
    }

    /** JSON ja validado na ingestao (contagens inteiras com chaves fechadas), devolvido como objeto. */
    static final class RawJson {
        private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

        @SuppressWarnings("unchecked")
        static Map<String, Object> of(String json) {
            return new LinkedHashMap<>(JSON.readValue(json, Map.class));
        }
    }

    private static Map<String, Object> run(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        short state = rs.getShort("supervisor_state");
        m.put("runId", rs.getObject("agent_run_id", UUID.class));
        Map<String, Object> corr = new LinkedHashMap<>();
        corr.put("agentExecutionId", rs.getString("agent_execution_id"));
        corr.put("frameworkExecutionId", rs.getString("framework_execution_id"));
        corr.put("platformExecutionId", rs.getObject("platform_execution_id", UUID.class));
        corr.put("agentId", rs.getObject("agent_id", UUID.class));
        corr.put("workerId", rs.getObject("worker_id", UUID.class));
        corr.put("workspaceId", rs.getObject("workspace_id", UUID.class));
        corr.put("automationId", rs.getObject("automation_id", UUID.class));
        corr.put("automationVersionId", rs.getObject("automation_version_id", UUID.class));
        corr.put("authorizationId", rs.getObject("authorization_id", UUID.class));
        m.put("correlation", corr);
        m.put("origin", "LOCAL_AUTHORIZED");
        m.put("authorizationStateAtRegistration", AUTH_STATES[rs.getShort("authorization_state_at_registration")]);
        m.put("registeredAt", rs.getObject("registered_at", OffsetDateTime.class));

        Map<String, Object> sup = new LinkedHashMap<>();
        sup.put("state", STATES[state]);
        sup.put("code", rs.getString("supervisor_code"));
        sup.put("exitCode", rs.getObject("exit_code"));
        sup.put("counts", RawJson.of(rs.getString("supervisor_counts")));
        sup.put("agentCreatedAt", rs.getObject("agent_created_at", OffsetDateTime.class));
        sup.put("agentStartedAt", rs.getObject("agent_started_at", OffsetDateTime.class));
        sup.put("agentEndedAt", rs.getObject("agent_ended_at", OffsetDateTime.class));
        sup.put("adopted", rs.getBoolean("adopted"));
        sup.put("suspensions", rs.getInt("suspensions"));
        sup.put("locks", rs.getInt("locks"));
        sup.put("reports", rs.getInt("supervisor_reports"));
        sup.put("conflicts", rs.getInt("supervisor_conflicts"));
        sup.put("lastReportAt", rs.getObject("last_report_at", OffsetDateTime.class));
        m.put("supervisor", sup);

        // dimensoes do contrato (sec. 9)
        m.put("process", switch (state) { case 1 -> "NOT_STARTED"; case 2 -> "STARTING"; case 3 -> "RUNNING"; default -> "ENDED"; });
        m.put("outcome", state < 4 ? "PENDING" : state == 9 ? "UNKNOWN" : "KNOWN");
        Map<String, Object> transport = new LinkedHashMap<>();
        transport.put("connectivity", rs.getBoolean("connected") ? "CONNECTED" : "DISCONNECTED");
        transport.put("lastHeartbeatAt", rs.getObject("last_heartbeat_at", OffsetDateTime.class));
        transport.put("lastEventAt", rs.getObject("last_event_at", OffsetDateTime.class));
        m.put("transport", transport);

        long stored = rs.getLong("stored_through");
        long settled = rs.getLong("settled_through");
        long maxSeq = rs.getLong("max_sequence");
        Long completed = (Long) rs.getObject("collection_completed_sequence");
        List<String> reasons = new ArrayList<>();
        boolean pendingDelivery = completed == null || settled < Math.max(maxSeq, completed);
        if (pendingDelivery) {
            reasons.add("DELIVERY_PENDING");
        }
        if (rs.getBoolean("lost_at_source")) {
            reasons.add("LOST_AT_SOURCE");
        }
        if (rs.getBoolean("lost_local")) {
            reasons.add("LOST_LOCAL");
        }
        if (rs.getBoolean("purged_local")) {
            reasons.add("PURGED_LOCAL");
        }
        if (rs.getLong("invalid_refused") > 0) {
            reasons.add("INVALID_REFUSED");
        }
        if (rs.getLong("delivery_conflicts") > 0) {
            reasons.add("DELIVERY_CONFLICT");
        }
        boolean lossy = reasons.stream().anyMatch(r -> !"DELIVERY_PENDING".equals(r));
        String history = lossy ? "INCOMPLETE" : pendingDelivery ? "PENDING" : stored == completed ? "COMPLETE" : "INCOMPLETE";
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("completeness", history);
        h.put("reasons", reasons);
        h.put("eventsStored", rs.getLong("events_stored"));
        h.put("storedThrough", stored);
        h.put("settledThrough", settled);
        h.put("maxSequence", maxSeq);
        h.put("declaredMissing", rs.getLong("declared_missing"));
        h.put("collectionCompletedSequence", completed);
        h.put("invalidRefused", rs.getLong("invalid_refused"));
        h.put("stepsAvailable", rs.getLong("framework_events") > 0);
        m.put("history", h);
        return m;
    }
}
