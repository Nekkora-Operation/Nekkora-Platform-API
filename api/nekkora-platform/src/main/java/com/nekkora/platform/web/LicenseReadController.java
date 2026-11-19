package com.nekkora.platform.web;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;
import com.nekkora.core.web.ApiException;
import com.nekkora.platform.access.WorkspaceAccess;
import com.nekkora.platform.paging.CursorCodec;
import com.nekkora.platform.paging.Paging;
import com.nekkora.platform.session.ValidSession;

/**
 * PLATFORM-LICENCIAMENTO-001 (E2) - LEITURAS das licencas de execucao e monitoracao da organizacao, para quem a administra
 * ({@code org.manage}). Modelo na migration 061; contrato em docs/platform-licenciamento-001/t02-modelo-e-leituras.md.
 *
 * <p>So leitura. NADA aqui decide se uma execucao pode comecar: nesta etapa nenhum caminho de execucao ou de monitoracao
 * consulta licenca, e toda organizacao esta no modelo anterior ({@code legacy}) ate um ato ligar a exigencia.
 *
 * <p>VIGENTE, a mesma definicao no resumo e na lista: a licenca nao foi encerrada e tem um periodo com
 * {@code starts_at <= agora < ends_at}. O instante e um so por resposta ({@code asOf}), medido no banco.
 *
 * <p>O resumo e uma AGREGACAO sobre todas as licencas da organizacao; nunca a soma de uma pagina da lista.
 */
@RestController
public class LicenseReadController {

    static final String ROUTE_LICENSES = "listOrganizationLicenses";
    static final String SORT_NUMBER_ASC = "number.asc+id.asc";

    /** A licenca {@code l} tem periodo que cobre {@code :now}. */
    private static final String COVERED = """
            EXISTS (SELECT 1 FROM public.organization_license_periods p
                     WHERE p.organization_id = l.organization_id AND p.license_id = l.license_id AND p.starts_at <= :now AND p.ends_at > :now)""";

    /**
     * A automacao {@code a} tem trabalho em andamento em alguma das origens (as mesmas regras do resumo do Inicio,
     * {@link ExecutionSummaryReads}): execucao da Platform, execucao relatada pelo Agent, pedido ao Agent anterior ainda no prazo,
     * ou solicitacao de execucao remota cuja ultima tentativa nao terminou.
     */
    private static final String IN_PROGRESS = """
            (EXISTS (SELECT 1 FROM public.executions e
                      WHERE e.organization_id = a.organization_id AND e.automation_id = a.automation_id AND e.status IN (1, 2, 3))
             OR EXISTS (SELECT 1 FROM public.agent_runs r
                         WHERE r.organization_id = a.organization_id AND r.automation_id = a.automation_id AND r.supervisor_state IN (1, 2, 3))
             OR EXISTS (SELECT 1 FROM public.run_requests q
                         WHERE q.organization_id = a.organization_id AND q.automation_id = a.automation_id AND q.agent_run_id IS NULL
                           AND (q.state = 3 OR (q.state IN (1, 2) AND q.valid_until > :now)))
             OR EXISTS (SELECT 1 FROM public.remote_executions x
                         LEFT JOIN LATERAL (SELECT z.state, z.offer_valid_until FROM public.remote_attempts z
                                             WHERE z.organization_id = x.organization_id AND z.remote_execution_id = x.remote_execution_id
                                             ORDER BY z.attempt_number DESC LIMIT 1) t ON true
                        WHERE x.organization_id = a.organization_id AND x.automation_id = a.automation_id
                          AND (t.state IS NULL OR (t.state BETWEEN 3 AND 6) OR (t.state IN (1, 2) AND t.offer_valid_until > :now))))""";

    private final OrgTransactions tx;
    private final WorkspaceAccess access;
    private final CursorCodec cursors;

    public LicenseReadController(OrgTransactions tx, WorkspaceAccess access, CursorCodec cursors) {
        this.tx = tx;
        this.access = access;
        this.cursors = cursors;
    }

    /**
     * Resumo: modelo de habilitacao da organizacao e a conta das licencas - contratadas (vigentes), utilizadas (vigentes com
     * automacao vinculada), disponiveis (vigentes sem automacao) e as que venceram ainda vinculadas a uma automacao.
     */
    @GetMapping("/api/v1/o/{org}/licenses/summary")
    public Map<String, Object> summary(@PathVariable UUID org, HttpServletRequest req) {
        ValidSession s = internal(SessionFilter.principal(req));
        return tx.inOrganization(org, Mode.READ_ONLY, j -> {
            access.requireAnyOrganizationPermission(j, org, s.membershipId(), "org.manage");
            OffsetDateTime now = now(j);
            Map<String, Object> r = new LinkedHashMap<>();
            j.sql("SELECT model, since FROM public.organization_license_enforcement WHERE organization_id = :org").param("org", org)
                    .query((rs, n) -> {
                        r.put("model", rs.getInt("model") == 2 ? "licensed" : "legacy");
                        r.put("modelSince", rs.getObject("since", OffsetDateTime.class));
                        return 0;
                    }).optional();
            // sem linha: modelo anterior, desde sempre
            r.putIfAbsent("model", "legacy");
            r.putIfAbsent("modelSince", null);
            j.sql("""
                    SELECT count(*) FILTER (WHERE v.valid) AS contracted,
                           count(*) FILTER (WHERE v.valid AND v.assigned) AS used,
                           count(*) FILTER (WHERE v.valid AND NOT v.assigned) AS available,
                           count(*) FILTER (WHERE NOT v.valid AND v.assigned) AS expired_assigned
                      FROM (SELECT %s AS valid,
                                   EXISTS (SELECT 1 FROM public.organization_license_assignments g
                                            WHERE g.organization_id = l.organization_id AND g.license_id = l.license_id AND g.released_at IS NULL) AS assigned
                              FROM public.organization_licenses l
                             WHERE l.organization_id = :org AND l.ended_at IS NULL) v
                    """.formatted(COVERED)).param("org", org).param("now", now)
                    .query((rs, n) -> {
                        r.put("contracted", rs.getLong("contracted"));
                        r.put("used", rs.getLong("used"));
                        r.put("available", rs.getLong("available"));
                        // uma licenca atende uma automacao: as utilizadas SAO as automacoes com licenca ativa
                        r.put("licensedAutomations", rs.getLong("used"));
                        r.put("expiredWithAutomation", rs.getLong("expired_assigned"));
                        return 0;
                    }).single();
            r.put("asOf", now);
            return r;
        });
    }

    /**
     * As licencas da organizacao, pelo numero, com a situacao, a validade, a automacao vinculada (com o ambiente de trabalho) e,
     * A PARTE, se a automacao tem execucao em andamento. Licenca ativa e execucao em andamento sao campos distintos.
     */
    @GetMapping("/api/v1/o/{org}/licenses")
    public Map<String, Object> licenses(@PathVariable UUID org, @RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) Integer limit, HttpServletRequest req) {
        ValidSession s = internal(SessionFilter.principal(req));
        int pageSize = Paging.limit(limit);
        CursorCodec.Scope scope = new CursorCodec.Scope(ROUTE_LICENSES, org, s.membershipId(), null, CursorCodec.filters(), SORT_NUMBER_ASC);
        CursorCodec.Position after = cursor == null ? null : cursors.decode(cursor, scope).expectSize(2);
        Long afterNumber = after == null ? null : after.integer(0);
        UUID afterId = after == null ? null : after.uuid(1);
        return tx.inOrganization(org, Mode.READ_ONLY, j -> {
            access.requireAnyOrganizationPermission(j, org, s.membershipId(), "org.manage");
            OffsetDateTime now = now(j);
            StringBuilder sql = new StringBuilder("""
                    SELECT l.license_id, l.license_number, l.ended_at, %s AS valid,
                           (SELECT min(p.starts_at) FROM public.organization_license_periods p
                             WHERE p.organization_id = l.organization_id AND p.license_id = l.license_id) AS first_start,
                           (SELECT max(p.ends_at) FROM public.organization_license_periods p
                             WHERE p.organization_id = l.organization_id AND p.license_id = l.license_id) AS last_end,
                           (SELECT min(p.starts_at) FROM public.organization_license_periods p
                             WHERE p.organization_id = l.organization_id AND p.license_id = l.license_id AND p.starts_at > :now) AS next_start,
                           (SELECT count(*) FROM public.organization_license_periods p
                             WHERE p.organization_id = l.organization_id AND p.license_id = l.license_id AND p.kind = 2) AS renewals,
                           g.assigned_at, a.automation_id, a.name AS automation_name, a.status AS automation_status,
                           a.workspace_id, w.name AS workspace_name,
                           CASE WHEN a.automation_id IS NULL THEN NULL ELSE %s END AS in_progress
                      FROM public.organization_licenses l
                      LEFT JOIN public.organization_license_assignments g
                             ON g.organization_id = l.organization_id AND g.license_id = l.license_id AND g.released_at IS NULL
                      LEFT JOIN public.automations a ON a.organization_id = g.organization_id AND a.automation_id = g.automation_id
                      LEFT JOIN public.workspaces w ON w.organization_id = a.organization_id AND w.workspace_id = a.workspace_id
                     WHERE l.organization_id = :org
                    """.formatted(COVERED, IN_PROGRESS));
            if (after != null) {
                sql.append(" AND (l.license_number, l.license_id) > (:afterNumber, :afterId)");
            }
            sql.append(" ORDER BY l.license_number, l.license_id LIMIT :max");
            JdbcClient.StatementSpec q = j.sql(sql.toString()).param("org", org).param("now", now).param("max", pageSize + 1);
            if (after != null) {
                q = q.param("afterNumber", afterNumber.intValue()).param("afterId", afterId);
            }
            List<Map<String, Object>> rows = new ArrayList<>(q.query((rs, n) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("licenseId", rs.getObject("license_id", UUID.class));
                m.put("number", rs.getInt("license_number"));
                OffsetDateTime lastEnd = rs.getObject("last_end", OffsetDateTime.class);
                // ended: encerrada por ato; active: vigente; scheduled: so tem periodo no futuro; expired: o ultimo periodo venceu
                String status = rs.getObject("ended_at") != null ? "ended"
                        : rs.getBoolean("valid") ? "active"
                        : lastEnd != null && lastEnd.isAfter(now) ? "scheduled" : "expired";
                m.put("status", status);
                m.put("validFrom", rs.getObject("first_start", OffsetDateTime.class));
                m.put("validUntil", lastEnd);
                m.put("nextPeriodStartsAt", rs.getObject("next_start", OffsetDateTime.class));
                m.put("renewals", rs.getInt("renewals"));
                UUID automation = rs.getObject("automation_id", UUID.class);
                if (automation == null) {
                    m.put("automation", null);
                } else {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("automationId", automation);
                    a.put("name", rs.getString("automation_name"));
                    a.put("status", Labels.label(Labels.Kind.RESOURCE, rs.getInt("automation_status")));
                    a.put("workspaceId", rs.getObject("workspace_id", UUID.class));
                    a.put("workspaceName", rs.getString("workspace_name"));
                    a.put("assignedAt", rs.getObject("assigned_at", OffsetDateTime.class));
                    a.put("executionInProgress", rs.getBoolean("in_progress"));
                    m.put("automation", a);
                }
                return m;
            }).list());
            String next = rows.size() > pageSize
                    ? cursors.encode(scope, List.of(rows.get(pageSize - 1).get("number"), rows.get(pageSize - 1).get("licenseId"))) : null;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("items", new ArrayList<>(rows.subList(0, Math.min(pageSize, rows.size()))));
            r.put("nextCursor", next);
            r.put("asOf", now);
            return r;
        });
    }

    /** O instante da resposta, medido no banco (o mesmo relogio dos gatilhos da 061). */
    private static OffsetDateTime now(JdbcClient j) {
        return j.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
    }

    private static ValidSession internal(ValidSession s) {
        if (!s.isInternal()) {
            throw ApiException.forbidden("conta_externa");
        }
        return s;
    }
}
