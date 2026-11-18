package com.nekkora.platform.web;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.platform.access.WorkspaceAccess;

/**
 * PLATFORM-INICIO-001: resumo das execucoes de um periodo e automacoes da organizacao com a ultima execucao, para o Inicio.
 * As regras de contagem estao em docs/platform-inicio-001/contagem-de-execucoes.md; este e o unico lugar que as aplica.
 *
 * <p><b>O que conta como UMA execucao</b>: um pedido de execucao, nunca uma tentativa. Quatro origens, sem sobreposicao:
 * <ol>
 * <li>{@code executions} (execucao da Platform, manual ou agendada): uma linha. A repeticao ({@code retry_of_execution_id}) e
 * outra linha e outra execucao, porque foi outro pedido.</li>
 * <li>{@code agent_runs} (execucao numa maquina, relatada pelo Agent): uma linha, EXCETO a que aponta para uma execucao da
 * Platform ({@code platform_execution_id}), que ja foi contada em (1).</li>
 * <li>{@code run_requests} (pedido de execucao ao Agent anterior) que NAO virou execucao: uma linha. O pedido que virou execucao
 * (RUN_LINKED) e contado pela execucao dele, em (2).</li>
 * <li>{@code remote_executions} (solicitacao de execucao remota ao Agent Client): uma linha, com o estado da ULTIMA tentativa.
 * Repetir em outra maquina cria tentativa, nao execucao: nao aumenta o total.</li>
 * </ol>
 * Execucoes locais privadas do Agent Client nunca chegam a Platform e nao entram.
 *
 * <p><b>Data</b>: o instante em que a Platform registrou o pedido ({@code created_at}, {@code registered_at},
 * {@code requested_at}), no intervalo semiaberto [from, to). Nunca o relogio da maquina.
 *
 * <p><b>Situacoes</b> (cada execucao cai em exatamente uma; a soma das seis e o total):
 * succeeded (terminou com sucesso), failed (o processo da automacao falhou ou estourou o tempo), in_progress (ainda sem
 * desfecho), cancelled (cancelada por uma pessoa), not_started (recusada, expirada, bloqueada ou que falhou antes de o
 * processo existir: nao e erro da automacao) e unconfirmed (terminou sem que o resultado fosse confirmado: nunca sucesso nem
 * erro).
 *
 * <p>Toda consulta e restrita aos ambientes em que a pessoa tem automation.read, pela condicao compartilhada
 * ({@link WorkspaceAccess#visibleWorkspacesQuery}); a agregacao e feita no banco, sobre TODAS as linhas do periodo.
 */
public final class ExecutionSummaryReads {

    public static final String READ = "automation.read";
    public static final List<String> OUTCOMES = List.of("succeeded", "failed", "in_progress", "cancelled", "not_started", "unconfirmed");
    public static final List<String> SOURCES = List.of("platform", "local_agent", "remote");

    private static final String VISIBLE = "(SELECT w.workspace_id FROM public.workspaces w WHERE " + WorkspaceAccess.VISIBLE_WORKSPACE_CONDITION + ")";

    /** executions: 5=FAILED com motivo START_FAILED (2) nao iniciou; 6=CANCELLED com motivo QUEUE_DEADLINE_EXCEEDED (6) nao iniciou. */
    private static final String PLATFORM_OUTCOME = """
            CASE WHEN e.status IN (1, 2, 3) THEN 'in_progress' WHEN e.status = 4 THEN 'succeeded'
                 WHEN e.status = 5 AND e.status_reason = 3 THEN 'failed' WHEN e.status = 5 THEN 'not_started'
                 WHEN e.status = 6 AND e.status_reason = 6 THEN 'not_started' ELSE 'cancelled' END""";
    /** agent_runs.supervisor_state: 7=TIMED_OUT e erro; 8=BLOCKED nao iniciou; 9=OUTCOME_UNKNOWN nao confirmado. */
    private static final String RUN_OUTCOME = """
            CASE WHEN r.supervisor_state IN (1, 2, 3) THEN 'in_progress' WHEN r.supervisor_state = 4 THEN 'succeeded'
                 WHEN r.supervisor_state IN (5, 7) THEN 'failed' WHEN r.supervisor_state = 6 THEN 'cancelled'
                 WHEN r.supervisor_state = 8 THEN 'not_started' ELSE 'unconfirmed' END""";
    /** run_requests sem execucao: 8=REVOKED cancelado; 4, 5, 7 (e o pedido vencido ainda nao marcado) nao iniciou. */
    private static final String REQUEST_OUTCOME = """
            CASE WHEN q.state = 8 THEN 'cancelled' WHEN q.state IN (4, 5, 7) THEN 'not_started'
                 WHEN q.state IN (1, 2) AND q.valid_until <= clock_timestamp() THEN 'not_started' ELSE 'in_progress' END""";
    /** remote_attempts da ULTIMA tentativa: oferta vencida vale EXPIRED (como na leitura); 10=TIMED_OUT e erro; 13 nao confirmado. */
    private static final String REMOTE_OUTCOME = """
            CASE WHEN t.state IS NULL THEN 'in_progress'
                 WHEN t.state IN (1, 2) AND t.offer_valid_until <= clock_timestamp() THEN 'not_started'
                 WHEN t.state BETWEEN 1 AND 6 THEN 'in_progress' WHEN t.state = 7 THEN 'succeeded'
                 WHEN t.state IN (8, 10) THEN 'failed' WHEN t.state = 9 THEN 'cancelled'
                 WHEN t.state IN (11, 12) THEN 'not_started' ELSE 'unconfirmed' END""";
    private static final String LAST_ATTEMPT = """
            LEFT JOIN LATERAL (SELECT z.state, z.offer_valid_until FROM public.remote_attempts z
                                WHERE z.organization_id = x.organization_id AND z.remote_execution_id = x.remote_execution_id
                                ORDER BY z.attempt_number DESC LIMIT 1) t ON true""";

    private static final String SUMMARY = """
            SELECT u.source, u.outcome, count(*) AS n FROM (
                SELECT 'platform' AS source, %s AS outcome
                  FROM public.executions e
                 WHERE e.organization_id = :org AND e.workspace_id IN %s AND e.created_at >= :from AND e.created_at < :to
                UNION ALL
                SELECT 'local_agent', %s
                  FROM public.agent_runs r
                 WHERE r.organization_id = :org AND r.workspace_id IN %s AND r.platform_execution_id IS NULL
                   AND r.registered_at >= :from AND r.registered_at < :to
                UNION ALL
                SELECT 'local_agent', %s
                  FROM public.run_requests q
                 WHERE q.organization_id = :org AND q.workspace_id IN %s AND q.state <> 6 AND q.agent_run_id IS NULL
                   AND q.requested_at >= :from AND q.requested_at < :to
                UNION ALL
                SELECT 'remote', %s
                  FROM public.remote_executions x
                  %s
                 WHERE x.organization_id = :org AND x.workspace_id IN %s AND x.requested_at >= :from AND x.requested_at < :to
            ) u GROUP BY u.source, u.outcome
            """.formatted(PLATFORM_OUTCOME, VISIBLE, RUN_OUTCOME, VISIBLE, REQUEST_OUTCOME, VISIBLE, REMOTE_OUTCOME, LAST_ATTEMPT, VISIBLE);

    private final WorkspaceAccess access;

    public ExecutionSummaryReads(WorkspaceAccess access) {
        this.access = access;
    }

    /**
     * Contagem das execucoes pedidas em [from, to) nos ambientes visiveis. Sempre completa: e uma agregacao do banco, nao a soma
     * de uma pagina. {@code total} e a soma das seis situacoes; {@code sources} repete a conta por origem.
     */
    public Map<String, Object> summary(JdbcClient j, UUID org, UUID membership, OffsetDateTime from, OffsetDateTime to) {
        Map<String, Map<String, Long>> bySource = new LinkedHashMap<>();
        for (String s : SOURCES) {
            bySource.put(s, zeros());
        }
        Map<String, Long> all = zeros();
        access.visibleWorkspacesQuery(j, SUMMARY, org, membership, READ).param("org", org).param("from", from).param("to", to).query(rs -> {
            long n = rs.getLong("n");
            bySource.get(rs.getString("source")).merge(rs.getString("outcome"), n, Long::sum);
            all.merge(rs.getString("outcome"), n, Long::sum);
        });
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from);
        m.put("to", to);
        m.putAll(counts(all));
        Map<String, Object> sources = new LinkedHashMap<>();
        m.put("sources", sources);
        sources.put("platform", counts(bySource.get("platform")));
        sources.put("localAgent", counts(bySource.get("local_agent")));
        sources.put("remote", counts(bySource.get("remote")));
        return m;
    }

    private static Map<String, Long> zeros() {
        Map<String, Long> z = new LinkedHashMap<>();
        for (String o : OUTCOMES) {
            z.put(o, 0L);
        }
        return z;
    }

    private static Map<String, Object> counts(Map<String, Long> c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", c.values().stream().mapToLong(Long::longValue).sum());
        m.put("succeeded", c.get("succeeded"));
        m.put("failed", c.get("failed"));
        m.put("inProgress", c.get("in_progress"));
        m.put("cancelled", c.get("cancelled"));
        m.put("notStarted", c.get("not_started"));
        m.put("unconfirmed", c.get("unconfirmed"));
        return m;
    }

    // ------------------------------------------------------------------ automacoes da organizacao

    /** A execucao mais recente da automacao, entre as quatro origens, pelas mesmas regras do resumo. */
    private static final String LAST_EXECUTION = """
            LEFT JOIN LATERAL (
                SELECT l.source, l.id, l.outcome, l.requested_at FROM (
                    (SELECT 'platform' AS source, e.execution_id AS id, %s AS outcome, e.created_at AS requested_at
                       FROM public.executions e
                      WHERE e.organization_id = a.organization_id AND e.automation_id = a.automation_id
                      ORDER BY e.created_at DESC LIMIT 1)
                    UNION ALL
                    (SELECT 'local_agent', r.agent_run_id, %s, r.registered_at
                       FROM public.agent_runs r
                      WHERE r.organization_id = a.organization_id AND r.automation_id = a.automation_id AND r.platform_execution_id IS NULL
                      ORDER BY r.registered_at DESC LIMIT 1)
                    UNION ALL
                    (SELECT 'local_agent_request', q.run_request_id, %s, q.requested_at
                       FROM public.run_requests q
                      WHERE q.organization_id = a.organization_id AND q.automation_id = a.automation_id AND q.state <> 6 AND q.agent_run_id IS NULL
                      ORDER BY q.requested_at DESC LIMIT 1)
                    UNION ALL
                    (SELECT 'remote', x.remote_execution_id, %s, x.requested_at
                       FROM public.remote_executions x
                       %s
                      WHERE x.organization_id = a.organization_id AND x.automation_id = a.automation_id
                      ORDER BY x.requested_at DESC LIMIT 1)
                ) l ORDER BY l.requested_at DESC LIMIT 1) le ON true
            """.formatted(PLATFORM_OUTCOME, RUN_OUTCOME, REQUEST_OUTCOME, REMOTE_OUTCOME, LAST_ATTEMPT);

    private static final String AUTOMATIONS = """
            SELECT a.automation_id, a.workspace_id, w.name AS workspace_name, a.name, left(a.name, :prefix) AS sort_key, a.description, a.status,
                   lv.automation_version_id AS latest_version_id, lv.version_number AS latest_version_number, lv.published_at AS latest_published_at,
                   le.source AS last_source, le.id AS last_id, le.outcome AS last_outcome, le.requested_at AS last_requested_at
              FROM public.automations a
              JOIN public.workspaces w ON w.organization_id = a.organization_id AND w.workspace_id = a.workspace_id
              LEFT JOIN LATERAL (SELECT v.automation_version_id, v.version_number, v.published_at
                                   FROM public.automation_versions v
                                  WHERE v.organization_id = a.organization_id AND v.automation_id = a.automation_id AND v.status = 2
                                  ORDER BY v.version_number DESC LIMIT 1) lv ON true
            """ + LAST_EXECUTION + " WHERE a.organization_id = :org AND " + WorkspaceAccess.VISIBLE_WORKSPACE_CONDITION;

    /**
     * As automacoes dos ambientes visiveis, por nome, cada uma com o ambiente e a ultima execucao (ou nulo). Paginada como a
     * lista por ambiente (L03): le {@code max} linhas a partir de (afterKey, afterId).
     */
    public List<Map<String, Object>> automations(JdbcClient j, UUID org, UUID membership, List<Short> status, String afterKey, UUID afterId, int max) {
        StringBuilder sql = new StringBuilder(AUTOMATIONS);
        if (status != null) {
            sql.append(" AND a.status IN (:status)");
        }
        if (afterId != null) {
            sql.append(" AND (left(a.name, :prefix) COLLATE \"C\", a.automation_id) > (CAST(:afterKey AS text) COLLATE \"C\", :afterId)");
        }
        sql.append(" ORDER BY left(a.name, :prefix) COLLATE \"C\", a.automation_id LIMIT :max");
        JdbcClient.StatementSpec q = access.visibleWorkspacesQuery(j, sql.toString(), org, membership, READ).param("org", org)
                .param("prefix", ProductReads.NAME_SORT_PREFIX).param("max", max);
        if (status != null) {
            q = q.param("status", status);
        }
        if (afterId != null) {
            q = q.param("afterKey", afterKey).param("afterId", afterId);
        }
        return q.query((rs, n) -> automation(rs)).list();
    }

    private static Map<String, Object> automation(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("automationId", rs.getObject("automation_id", UUID.class).toString());
        m.put("workspaceId", rs.getObject("workspace_id", UUID.class).toString());
        m.put("workspaceName", rs.getString("workspace_name"));
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
        String source = rs.getString("last_source");
        if (source == null) {
            m.put("lastExecution", null);
        } else {
            Map<String, Object> l = new LinkedHashMap<>();
            // o pedido ao Agent anterior que nao virou execucao nao tem tela de detalhe: sem identificador
            boolean request = source.equals("local_agent_request");
            l.put("source", switch (source) { case "platform" -> "platform"; case "remote" -> "remote"; default -> "localAgent"; });
            l.put("id", request ? null : rs.getObject("last_id", UUID.class).toString());
            l.put("outcome", switch (rs.getString("last_outcome")) {
                case "in_progress" -> "inProgress";
                case "not_started" -> "notStarted";
                default -> rs.getString("last_outcome");
            });
            l.put("requestedAt", rs.getObject("last_requested_at", OffsetDateTime.class));
            m.put("lastExecution", l);
        }
        m.put(ProductReads.SORT_KEY, rs.getString("sort_key"));
        return m;
    }
}
