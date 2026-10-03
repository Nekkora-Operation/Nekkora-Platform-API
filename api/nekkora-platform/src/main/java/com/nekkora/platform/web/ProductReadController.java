package com.nekkora.platform.web;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import jakarta.servlet.http.HttpServletRequest;

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
 * GAP-06: leitura interna de workspaces, automacoes, versoes, execucoes e eventos (contrato GAP-02/03/06, ROTA-L01 a L08;
 * DC-07 a DC-13 decididos pela responsavel em 2026-10-03). Ordem das verificacoes (§1.3): 401 (filtro) -> 400 (parametros,
 * periodo e cursor) -> 403 (conta externa, DC-02) -> 404 (componente compartilhado). A autorizacao e a consulta ficam em
 * {@link ProductReads}, usado tambem pela medicao do DC-07.
 */
@RestController
public class ProductReadController {

    static final String ROUTE_WORKSPACES = "listWorkspaces";
    static final String ROUTE_AUTOMATIONS = "listAutomations";
    static final String ROUTE_VERSIONS = "listAutomationVersions";
    static final String ROUTE_EXECUTIONS = "listExecutions";
    static final String ROUTE_EVENTS = "listExecutionEvents";
    static final String SORT_NAME48 = "name48.asc+id.asc";
    static final String SORT_VERSION_DESC = "versionNumber.desc";
    static final String SORT_CREATED_DESC = "createdAt.desc+id.desc";
    static final String SORT_SEQUENCE_ASC = "sequence.asc";
    /** §1.6: periodo maximo exato (366 x 24 h). */
    static final Duration MAX_PERIOD = Duration.ofDays(366);

    private final OrgTransactions tx;
    private final ProductReads reads;
    private final CursorCodec cursors;

    public ProductReadController(OrgTransactions tx, WorkspaceAccess access, CursorCodec cursors) {
        this.tx = tx;
        this.reads = new ProductReads(access);
        this.cursors = cursors;
    }

    // ------------------------------------------------------------------ L01, L02

    @GetMapping("/api/v1/o/{org}/workspaces")
    public Map<String, Object> workspaces(@PathVariable UUID org, @RequestParam(required = false) List<String> status,
                                          @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
                                          HttpServletRequest req) {
        ValidSession s = SessionFilter.principal(req);
        int pageSize = Paging.limit(limit);
        List<Short> codes = Labels.statusFilter(Labels.Kind.RESOURCE, status, 3);
        CursorCodec.Scope scope = new CursorCodec.Scope(ROUTE_WORKSPACES, org, s.membershipId(), null, CursorCodec.filters("status", status), SORT_NAME48);
        CursorCodec.Position after = cursor == null ? null : cursors.decode(cursor, scope).expectSize(2);
        String afterKey = after == null ? null : after.text(0);
        UUID afterId = after == null ? null : after.uuid(1);
        internal(s);
        List<Map<String, Object>> rows = tx.inOrganization(org, Mode.READ_ONLY,
                j -> reads.listWorkspaces(j, org, s.membershipId(), codes, afterKey, afterId, pageSize + 1));
        return pageBySortKey(rows, pageSize, last -> cursors.encode(scope, List.of(last.get(ProductReads.SORT_KEY), last.get("workspaceId"))));
    }

    @GetMapping("/api/v1/o/{org}/workspaces/{workspaceId}")
    public Map<String, Object> workspace(@PathVariable UUID org, @PathVariable UUID workspaceId, HttpServletRequest req) {
        ValidSession s = internal(SessionFilter.principal(req));
        return tx.inOrganization(org, Mode.READ_ONLY, j -> reads.getWorkspace(j, org, s.membershipId(), workspaceId));
    }

    // ------------------------------------------------------------------ L03, L04, L05

    @GetMapping("/api/v1/o/{org}/workspaces/{workspaceId}/automations")
    public Map<String, Object> automations(@PathVariable UUID org, @PathVariable UUID workspaceId, @RequestParam(required = false) List<String> status,
                                           @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
                                           HttpServletRequest req) {
        ValidSession s = SessionFilter.principal(req);
        int pageSize = Paging.limit(limit);
        List<Short> codes = Labels.statusFilter(Labels.Kind.RESOURCE, status, 3);
        CursorCodec.Scope scope = new CursorCodec.Scope(ROUTE_AUTOMATIONS, org, s.membershipId(), workspaceId, CursorCodec.filters("status", status),
                SORT_NAME48);
        CursorCodec.Position after = cursor == null ? null : cursors.decode(cursor, scope).expectSize(2);
        String afterKey = after == null ? null : after.text(0);
        UUID afterId = after == null ? null : after.uuid(1);
        internal(s);
        List<Map<String, Object>> rows = tx.inOrganization(org, Mode.READ_ONLY,
                j -> reads.listAutomations(j, org, s.membershipId(), workspaceId, codes, afterKey, afterId, pageSize + 1));
        return pageBySortKey(rows, pageSize, last -> cursors.encode(scope, List.of(last.get(ProductReads.SORT_KEY), last.get("automationId"))));
    }

    @GetMapping("/api/v1/o/{org}/automations/{automationId}")
    public Map<String, Object> automation(@PathVariable UUID org, @PathVariable UUID automationId, HttpServletRequest req) {
        ValidSession s = internal(SessionFilter.principal(req));
        return tx.inOrganization(org, Mode.READ_ONLY, j -> reads.getAutomation(j, org, s.membershipId(), automationId));
    }

    @GetMapping("/api/v1/o/{org}/automations/{automationId}/versions")
    public Map<String, Object> versions(@PathVariable UUID org, @PathVariable UUID automationId, @RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) Integer limit, HttpServletRequest req) {
        ValidSession s = SessionFilter.principal(req);
        int pageSize = Paging.limit(limit);
        CursorCodec.Scope scope = new CursorCodec.Scope(ROUTE_VERSIONS, org, s.membershipId(), automationId, CursorCodec.filters(), SORT_VERSION_DESC);
        Long before = cursor == null ? null : cursors.decode(cursor, scope).expectSize(1).integer(0);
        internal(s);
        List<Map<String, Object>> rows = tx.inOrganization(org, Mode.READ_ONLY,
                j -> reads.listVersions(j, org, s.membershipId(), automationId, before, pageSize + 1));
        return page(rows, pageSize, last -> cursors.encode(scope, List.of(((Number) last.get("versionNumber")).longValue())));
    }

    // ------------------------------------------------------------------ L06, L07, L08

    @GetMapping("/api/v1/o/{org}/executions")
    public Map<String, Object> executions(@PathVariable UUID org, @RequestParam(required = false) UUID workspaceId,
                                          @RequestParam(required = false) UUID automationId, @RequestParam(required = false) List<String> status,
                                          @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                          @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
                                          HttpServletRequest req) {
        ValidSession s = SessionFilter.principal(req);
        int pageSize = Paging.limit(limit);
        List<Short> codes = Labels.statusFilter(Labels.Kind.EXECUTION_STATUS, status, 6);
        OffsetDateTime fromTime = instant(from);
        OffsetDateTime toTime = instant(to);
        if (fromTime != null && toTime != null && (toTime.isBefore(fromTime) || Duration.between(fromTime, toTime).compareTo(MAX_PERIOD) > 0)) {
            throw ApiException.invalid("periodo");
        }
        CursorCodec.Scope scope = new CursorCodec.Scope(ROUTE_EXECUTIONS, org, s.membershipId(), null, CursorCodec.filters("workspaceId", workspaceId,
                "automationId", automationId, "status", status, "from", fromTime, "to", toTime), SORT_CREATED_DESC);
        CursorCodec.Position after = cursor == null ? null : cursors.decode(cursor, scope).expectSize(2);
        OffsetDateTime afterCreated = after == null ? null : after.instant(0);
        UUID afterId = after == null ? null : after.uuid(1);
        internal(s);
        ProductReads.ExecutionFilter filter = new ProductReads.ExecutionFilter(workspaceId, automationId, codes, fromTime, toTime);
        List<Map<String, Object>> rows = tx.inOrganization(org, Mode.READ_ONLY,
                j -> reads.listExecutions(j, org, s.membershipId(), filter, afterCreated, afterId, pageSize + 1));
        return page(rows, pageSize, last -> cursors.encode(scope, List.of(last.get("createdAt"), last.get("executionId"))));
    }

    @GetMapping("/api/v1/o/{org}/executions/{executionId}")
    public Map<String, Object> execution(@PathVariable UUID org, @PathVariable UUID executionId, HttpServletRequest req) {
        ValidSession s = internal(SessionFilter.principal(req));
        return tx.inOrganization(org, Mode.READ_ONLY, j -> reads.getExecution(j, org, s.membershipId(), executionId));
    }

    @GetMapping("/api/v1/o/{org}/executions/{executionId}/events")
    public Map<String, Object> events(@PathVariable UUID org, @PathVariable UUID executionId, @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit, HttpServletRequest req) {
        ValidSession s = SessionFilter.principal(req);
        int pageSize = Paging.limit(limit);
        CursorCodec.Scope scope = new CursorCodec.Scope(ROUTE_EVENTS, org, s.membershipId(), executionId, CursorCodec.filters(), SORT_SEQUENCE_ASC);
        Long after = cursor == null ? null : cursors.decode(cursor, scope).expectSize(1).integer(0);
        internal(s);
        List<Map<String, Object>> rows = tx.inOrganization(org, Mode.READ_ONLY,
                j -> reads.listEvents(j, org, s.membershipId(), executionId, after, pageSize + 1));
        return page(rows, pageSize, last -> cursors.encode(scope, List.of(((Number) last.get("sequence")).longValue())));
    }

    // ------------------------------------------------------------------ comum

    /** Data ISO-8601 COM fuso (§1.6); sem fuso ou invalida -> 400. */
    private static OffsetDateTime instant(String s) {
        if (s == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException e) {
            throw ApiException.invalid("data");
        }
    }

    /** Conta externa -> 403 nas rotas internas (DC-02). */
    private static ValidSession internal(ValidSession s) {
        if (!s.isInternal()) {
            throw ApiException.forbidden("conta_externa");
        }
        return s;
    }

    /** Le {@code limit + 1}: nextCursor so se havia mais itens no momento da consulta (§1.4). */
    private static Map<String, Object> page(List<Map<String, Object>> rows, int pageSize, Function<Map<String, Object>, String> cursorOf) {
        String next = rows.size() > pageSize ? cursorOf.apply(rows.get(pageSize - 1)) : null;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", new ArrayList<>(rows.subList(0, Math.min(pageSize, rows.size()))));
        r.put("nextCursor", next);
        return r;
    }

    /** Como {@link #page}, retirando a chave interna de ordenacao por nome de todos os itens depois de emitir o cursor. */
    private static Map<String, Object> pageBySortKey(List<Map<String, Object>> rows, int pageSize, Function<Map<String, Object>, String> cursorOf) {
        Map<String, Object> r = page(rows, pageSize, cursorOf);
        rows.forEach(m -> m.remove(ProductReads.SORT_KEY));
        return r;
    }
}
