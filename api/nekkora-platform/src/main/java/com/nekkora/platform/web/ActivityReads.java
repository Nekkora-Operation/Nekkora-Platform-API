package com.nekkora.platform.web;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.web.ApiException;
import com.nekkora.platform.access.WorkspaceAccess;

/**
 * PLATFORM-FLUXO-002 - ACOMPANHAMENTO UNIFICADO (Execucoes: "Em andamento" e "Historico"; "Ver atividade" de uma maquina).
 *
 * <p>Uma linha por EXECUCAO, com as MESMAS quatro origens e as MESMAS situacoes do resumo do Inicio
 * ({@link ExecutionSummaryReads}, unico lugar que as define): execucao da Platform; execucao relatada pelo Agent anterior que
 * nao aponta para uma execucao da Platform; pedido ao Agent anterior que nao virou execucao; solicitacao remota pela ultima
 * tentativa. Nada e contado em dobro e nenhuma execucao e criada.
 *
 * <p><b>Pendencia de atencao</b> ({@code attention}): resultado desconhecido ainda nao reconciliado - execucao do Agent anterior
 * em OUTCOME_UNKNOWN sem reconciliacao (068), ou ultima tentativa remota ENDED_UNKNOWN sem confirmacao da maquina nem declaracao
 * (058). Nunca e tratada como em andamento nem como maquina livre: a situacao continua {@code unconfirmed}.
 *
 * <p><b>Abas</b>: {@code in_progress} = em andamento OU pendencia de atencao, SEM periodo (uma pendencia antiga nunca some);
 * {@code history} = o que ja tem desfecho, no periodo [from, to) pelo instante do pedido (as pendencias tambem aparecem aqui,
 * marcadas). Restrito aos ambientes com automation.read; filtros por ambiente, automacao e maquina (Worker). Ordem: pedido mais
 * recente primeiro, com cursor.
 */
public final class ActivityReads {

    static final String READ = ExecutionSummaryReads.READ;
    static final int MAX_LIMIT = 100;

    /** As quatro origens, com a maquina, os instantes, a situacao e a pendencia. Parametros nomeados: :org e os filtros. */
    private static final String UNIFIED = """
            SELECT 'platform' AS kind, e.execution_id AS id, e.workspace_id, e.automation_id, e.worker_id, e.created_at AS requested_at,
                   e.started_at, e.finished_at AS ended_at, %s AS outcome, false AS attention
              FROM public.executions e
             WHERE e.organization_id = :org AND e.workspace_id IN %s
            UNION ALL
            SELECT 'local_agent', r.agent_run_id, r.workspace_id, r.automation_id, r.worker_id, r.registered_at, r.agent_started_at,
                   r.agent_ended_at, %s,
                   r.supervisor_state = 9 AND NOT EXISTS (SELECT 1 FROM public.agent_run_reconciliations c WHERE c.agent_run_id = r.agent_run_id)
              FROM public.agent_runs r
             WHERE r.organization_id = :org AND r.workspace_id IN %s AND r.platform_execution_id IS NULL
            UNION ALL
            SELECT 'local_request', q.run_request_id, q.workspace_id, q.automation_id, q.worker_id, q.requested_at, NULL::timestamptz,
                   NULL::timestamptz, %s, false
              FROM public.run_requests q
             WHERE q.organization_id = :org AND q.workspace_id IN %s AND q.state <> 6 AND q.agent_run_id IS NULL
            UNION ALL
            SELECT 'remote', x.remote_execution_id, x.workspace_id, x.automation_id, t.worker_id, x.requested_at, t.started_at, t.ended_at, %s,
                   coalesce(t.state = 13 AND NOT EXISTS (SELECT 1 FROM public.remote_attempt_process_reports p
                                                          WHERE p.organization_id = t.organization_id AND p.attempt_id = t.attempt_id
                                                            AND p.kind IN (1, 2)), false)
              FROM public.remote_executions x
              LEFT JOIN LATERAL (SELECT z.organization_id, z.attempt_id, z.state, z.offer_valid_until, z.worker_id, z.started_at, z.ended_at
                                   FROM public.remote_attempts z
                                  WHERE z.organization_id = x.organization_id AND z.remote_execution_id = x.remote_execution_id
                                  ORDER BY z.attempt_number DESC LIMIT 1) t ON true
             WHERE x.organization_id = :org AND x.workspace_id IN %s
            """.formatted(ExecutionSummaryReads.PLATFORM_OUTCOME, ExecutionSummaryReads.VISIBLE, ExecutionSummaryReads.RUN_OUTCOME,
            ExecutionSummaryReads.VISIBLE, ExecutionSummaryReads.REQUEST_OUTCOME, ExecutionSummaryReads.VISIBLE, ExecutionSummaryReads.REMOTE_OUTCOME,
            ExecutionSummaryReads.VISIBLE);

    private final WorkspaceAccess access;

    public ActivityReads(WorkspaceAccess access) {
        this.access = access;
    }

    public record Filter(String tab, UUID workspaceId, UUID automationId, UUID workerId, OffsetDateTime from, OffsetDateTime to, String cursor,
                         int limit) {
    }

    /** Uma pagina do acompanhamento. */
    public Map<String, Object> list(JdbcClient j, UUID org, UUID membership, Filter f) {
        StringBuilder where = new StringBuilder(" WHERE true");
        if ("in_progress".equals(f.tab())) {
            where.append(" AND (u.outcome = 'in_progress' OR u.attention)");
        } else {
            where.append(" AND u.outcome <> 'in_progress'");
            if (f.from() != null) {
                where.append(" AND u.requested_at >= :from");
            }
            if (f.to() != null) {
                where.append(" AND u.requested_at < :to");
            }
        }
        if (f.workspaceId() != null) {
            where.append(" AND u.workspace_id = :workspace");
        }
        if (f.automationId() != null) {
            where.append(" AND u.automation_id = :automation");
        }
        if (f.workerId() != null) {
            where.append(" AND u.worker_id = :worker");
        }
        Object[] after = decode(f.cursor());
        if (after != null) {
            where.append(" AND (u.requested_at, u.id) < (:after_at, :after_id)");
        }
        String sql = """
                SELECT u.kind, u.id, u.workspace_id, w.name AS workspace_name, u.automation_id, a.name AS automation_name, u.worker_id,
                       k.name AS worker_name, ag.name AS computer_name, u.requested_at, u.started_at, u.ended_at, u.outcome, u.attention
                  FROM (%s) u
                  JOIN public.workspaces w ON w.organization_id = :org AND w.workspace_id = u.workspace_id
                  JOIN public.automations a ON a.organization_id = :org AND a.automation_id = u.automation_id
                  LEFT JOIN public.workers k ON k.organization_id = :org AND k.worker_id = u.worker_id
                  LEFT JOIN public.worker_agent_assignments wa ON wa.organization_id = :org AND wa.worker_id = u.worker_id AND wa.status = 1
                  LEFT JOIN public.agents ag ON ag.organization_id = :org AND ag.agent_id = wa.agent_id
                """.formatted(UNIFIED) + where + " ORDER BY u.requested_at DESC, u.id DESC LIMIT :limit";
        JdbcClient.StatementSpec q = access.visibleWorkspacesQuery(j, sql, org, membership, READ).param("org", org).param("limit", f.limit() + 1);
        if (f.from() != null && !"in_progress".equals(f.tab())) {
            q = q.param("from", f.from());
        }
        if (f.to() != null && !"in_progress".equals(f.tab())) {
            q = q.param("to", f.to());
        }
        if (f.workspaceId() != null) {
            q = q.param("workspace", f.workspaceId());
        }
        if (f.automationId() != null) {
            q = q.param("automation", f.automationId());
        }
        if (f.workerId() != null) {
            q = q.param("worker", f.workerId());
        }
        if (after != null) {
            q = q.param("after_at", after[0]).param("after_id", after[1]);
        }
        List<Map<String, Object>> rows = q.query((rs, n) -> item(rs)).list();
        String next = null;
        if (rows.size() > f.limit()) {
            Map<String, Object> last = rows.get(f.limit() - 1);
            next = encode((OffsetDateTime) last.get("requestedAt"), (UUID) last.get("id"));
            rows = new ArrayList<>(rows.subList(0, f.limit()));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", rows);
        r.put("nextCursor", next);
        return r;
    }

    /**
     * Resumo de atividade POR MAQUINA (as mesmas linhas, contadas no banco): em andamento, pendencias de atencao e o ultimo pedido.
     * Restrito aos ambientes visiveis da pessoa; uma maquina sem atividade visivel nao aparece.
     */
    public List<Map<String, Object>> machines(JdbcClient j, UUID org, UUID membership) {
        String sql = """
                SELECT u.worker_id, count(*) FILTER (WHERE u.outcome = 'in_progress' AND NOT u.attention) AS in_progress,
                       count(*) FILTER (WHERE u.attention) AS attention, max(u.requested_at) AS last_requested_at
                  FROM (%s) u WHERE u.worker_id IS NOT NULL GROUP BY u.worker_id
                """.formatted(UNIFIED);
        return access.visibleWorkspacesQuery(j, sql, org, membership, READ).param("org", org).query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("workerId", rs.getObject("worker_id", UUID.class));
            m.put("inProgress", rs.getLong("in_progress"));
            m.put("attention", rs.getLong("attention"));
            m.put("lastActivityAt", rs.getObject("last_requested_at", OffsetDateTime.class));
            return m;
        }).list();
    }

    private static Map<String, Object> item(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", rs.getString("kind"));
        m.put("id", rs.getObject("id", UUID.class));
        m.put("workspace", ref(rs.getObject("workspace_id", UUID.class), rs.getString("workspace_name")));
        m.put("automation", ref(rs.getObject("automation_id", UUID.class), rs.getString("automation_name")));
        UUID worker = rs.getObject("worker_id", UUID.class);
        if (worker == null) {
            m.put("machine", null);
        } else {
            Map<String, Object> mc = new LinkedHashMap<>();
            mc.put("workerId", worker);
            mc.put("name", rs.getString("worker_name"));
            mc.put("computerName", rs.getString("computer_name"));
            m.put("machine", mc);
        }
        m.put("outcome", rs.getString("outcome"));
        m.put("attention", rs.getBoolean("attention"));
        m.put("requestedAt", rs.getObject("requested_at", OffsetDateTime.class));
        m.put("startedAt", rs.getObject("started_at", OffsetDateTime.class));
        m.put("endedAt", rs.getObject("ended_at", OffsetDateTime.class));
        return m;
    }

    private static Map<String, Object> ref(UUID id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }

    private static String encode(OffsetDateTime at, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((at.toString() + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** Cursor opaco "instante|id"; malformado: 400 (nunca recomeca em silencio). */
    private static Object[] decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String[] p = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", 2);
            return new Object[] { OffsetDateTime.parse(p[0]), UUID.fromString(p[1]) };
        } catch (RuntimeException e) {
            throw ApiException.invalid("cursor");
        }
    }
}
