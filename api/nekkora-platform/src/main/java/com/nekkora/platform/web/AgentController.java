package com.nekkora.platform.web;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;
import com.nekkora.core.web.ApiException;
import com.nekkora.platform.access.WorkspaceAccess;
import com.nekkora.platform.admin.AgentAdminService;
import com.nekkora.platform.paging.CursorCodec;
import com.nekkora.platform.paging.Paging;
import com.nekkora.platform.session.ValidSession;

/**
 * Lado das PESSOAS do canal do Agent (contrato Platform-Agent 1.0): codigos de pareamento e rotacao, revogacao, Worker no
 * workspace, autorizacao de execucao local e consultas das execucoes registradas pelo Agent. Sessao da organizacao e CSRF
 * dela (cadeia comum de {@code /api/v1/o/{org}}). O lado da MAQUINA fica em outro processo ({@code nekkora-agent-gateway}).
 */
@RestController
public class AgentController {

    static final String ROUTE_RUNS = "listAgentRuns";
    static final String ROUTE_RUN_EVENTS = "listAgentRunEvents";
    static final String SORT_REGISTERED_ASC = "registeredAt.asc+id.asc";
    static final String SORT_SEQUENCE_ASC = "sequence.asc";
    static final Duration MAX_PERIOD = Duration.ofDays(366);
    static final List<String> STATE_NAMES = List.of("READY", "STARTING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "BLOCKED",
            "OUTCOME_UNKNOWN");

    public record PairingRequest(String workerId, String workerName, String agentName, Integer expiresInMinutes) {
    }

    public record RotationRequest(Integer expiresInMinutes) {
    }

    public record RevokeRequest(String reason) {
    }

    public record LocalRunRequest(String workerId, String automationVersionId, String validUntil) {
    }

    private final AgentAdminService admin;
    private final OrgTransactions tx;
    private final AgentRunReads reads;
    private final CursorCodec cursors;

    public AgentController(AgentAdminService admin, OrgTransactions tx, WorkspaceAccess access, CursorCodec cursors) {
        this.admin = admin;
        this.tx = tx;
        this.reads = new AgentRunReads(access);
        this.cursors = cursors;
    }

    // ------------------------------------------------------------------ codigos

    @PostMapping("/api/v1/o/{org}/agent-enrollments")
    public ResponseEntity<AgentAdminService.CodeIssued> pairing(@PathVariable UUID org, @RequestBody(required = false) PairingRequest body,
                                                                HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(admin.createPairing(org, SessionFilter.principal(req), SessionFilter.token(req), uuidOrNull(body.workerId(), "worker"),
                        body.workerName(), body.agentName(), body.expiresInMinutes()));
    }

    @PostMapping("/api/v1/o/{org}/agent-enrollments/{enrollment}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID org, @PathVariable UUID enrollment, HttpServletRequest req) {
        admin.cancelCode(org, SessionFilter.principal(req), SessionFilter.token(req), enrollment);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/o/{org}/agents/{agent}/credential-rotations")
    public ResponseEntity<AgentAdminService.CodeIssued> rotation(@PathVariable UUID org, @PathVariable UUID agent,
                                                                 @RequestBody(required = false) RotationRequest body, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(admin.createRotation(org, SessionFilter.principal(req), SessionFilter.token(req), agent, body == null ? null : body.expiresInMinutes()));
    }

    @PostMapping("/api/v1/o/{org}/agents/{agent}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable UUID org, @PathVariable UUID agent, @RequestBody(required = false) RevokeRequest body,
                                       HttpServletRequest req) {
        admin.revokeAgent(org, SessionFilter.principal(req), SessionFilter.token(req), agent, body == null ? null : body.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/o/{org}/agents")
    public Map<String, List<AgentAdminService.AgentView>> agents(@PathVariable UUID org, HttpServletRequest req) {
        return Map.of("items", admin.listAgents(org, SessionFilter.principal(req), SessionFilter.token(req)));
    }

    @GetMapping("/api/v1/o/{org}/workers")
    public Map<String, List<AgentAdminService.WorkerView>> workers(@PathVariable UUID org, HttpServletRequest req) {
        return Map.of("items", admin.listWorkers(org, SessionFilter.principal(req), SessionFilter.token(req)));
    }

    // ------------------------------------------------------------------ Worker no workspace e autorizacao local

    @PutMapping("/api/v1/o/{org}/workers/{worker}/workspaces/{workspace}")
    public ResponseEntity<AgentAdminService.WorkerWorkspace> authorizeWorker(@PathVariable UUID org, @PathVariable UUID worker,
                                                                             @PathVariable UUID workspace, HttpServletRequest req) {
        AgentAdminService.WorkerWorkspace w = admin.authorizeWorker(org, SessionFilter.principal(req), SessionFilter.token(req), worker, workspace);
        return ResponseEntity.status(w.created() ? HttpStatus.CREATED : HttpStatus.OK).body(w);
    }

    @DeleteMapping("/api/v1/o/{org}/workers/{worker}/workspaces/{workspace}")
    public ResponseEntity<Void> revokeWorker(@PathVariable UUID org, @PathVariable UUID worker, @PathVariable UUID workspace, HttpServletRequest req) {
        admin.revokeWorker(org, SessionFilter.principal(req), SessionFilter.token(req), worker, workspace);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/o/{org}/workspaces/{workspace}/local-run-authorizations")
    public ResponseEntity<AgentAdminService.LocalAuthorization> authorizeLocalRun(@PathVariable UUID org, @PathVariable UUID workspace,
                                                                                  @RequestBody(required = false) LocalRunRequest body,
                                                                                  HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.authorizeLocalRun(org, SessionFilter.principal(req), SessionFilter.token(req), workspace,
                uuid(body.workerId(), "worker"), uuid(body.automationVersionId(), "versao"), instant(body.validUntil())));
    }

    @GetMapping("/api/v1/o/{org}/workspaces/{workspace}/local-run-authorizations")
    public Map<String, List<AgentAdminService.LocalAuthorization>> localRuns(@PathVariable UUID org, @PathVariable UUID workspace, HttpServletRequest req) {
        return Map.of("items", admin.listLocalRuns(org, SessionFilter.principal(req), SessionFilter.token(req), workspace));
    }

    @PostMapping("/api/v1/o/{org}/workspaces/{workspace}/local-run-authorizations/{authorization}/revoke")
    public ResponseEntity<Void> revokeLocalRun(@PathVariable UUID org, @PathVariable UUID workspace, @PathVariable UUID authorization,
                                               HttpServletRequest req) {
        admin.revokeLocalRun(org, SessionFilter.principal(req), SessionFilter.token(req), workspace, authorization);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ consultas das execucoes do Agent

    @GetMapping("/api/v1/o/{org}/agent-runs")
    public Map<String, Object> runs(@PathVariable UUID org, @RequestParam(required = false) UUID workspaceId, @RequestParam(required = false) UUID automationId,
                                    @RequestParam(required = false) List<String> state, @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to, @RequestParam(required = false) String cursor,
                                    @RequestParam(required = false) Integer limit, HttpServletRequest req) {
        ValidSession s = SessionFilter.principal(req);
        int pageSize = Paging.limit(limit);
        List<Short> codes = null;
        if (state != null) {
            codes = new ArrayList<>();
            for (String st : state) {
                int i = STATE_NAMES.indexOf(st);
                if (i < 0) {
                    throw ApiException.invalid("state");
                }
                codes.add((short) (i + 1));
            }
        }
        OffsetDateTime fromTime = from == null ? null : instant(from);
        OffsetDateTime toTime = to == null ? null : instant(to);
        if (fromTime != null && toTime != null && (toTime.isBefore(fromTime) || Duration.between(fromTime, toTime).compareTo(MAX_PERIOD) > 0)) {
            throw ApiException.invalid("periodo");
        }
        CursorCodec.Scope scope = new CursorCodec.Scope(ROUTE_RUNS, org, s.membershipId(), null, CursorCodec.filters("workspaceId", workspaceId,
                "automationId", automationId, "state", state, "from", fromTime, "to", toTime), SORT_REGISTERED_ASC);
        CursorCodec.Position after = cursor == null ? null : cursors.decode(cursor, scope).expectSize(2);
        internal(s);
        AgentRunReads.Filter filter = new AgentRunReads.Filter(workspaceId, automationId, codes, fromTime, toTime);
        List<Map<String, Object>> rows = tx.inOrganization(org, Mode.READ_ONLY, j -> reads.list(j, org, s.membershipId(), filter,
                after == null ? null : after.instant(0), after == null ? null : after.uuid(1), pageSize + 1));
        return page(rows, pageSize, last -> cursors.encode(scope, List.of(last.get("registeredAt"), last.get("runId"))));
    }

    @GetMapping("/api/v1/o/{org}/agent-runs/{run}")
    public Map<String, Object> run(@PathVariable UUID org, @PathVariable UUID run, HttpServletRequest req) {
        ValidSession s = internal(SessionFilter.principal(req));
        return tx.inOrganization(org, Mode.READ_ONLY, j -> reads.get(j, org, s.membershipId(), run));
    }

    @GetMapping("/api/v1/o/{org}/agent-runs/{run}/events")
    public Map<String, Object> runEvents(@PathVariable UUID org, @PathVariable UUID run, @RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) Integer limit, HttpServletRequest req) {
        ValidSession s = SessionFilter.principal(req);
        int pageSize = Paging.limit(limit);
        CursorCodec.Scope scope = new CursorCodec.Scope(ROUTE_RUN_EVENTS, org, s.membershipId(), run, CursorCodec.filters(), SORT_SEQUENCE_ASC);
        Long after = cursor == null ? null : cursors.decode(cursor, scope).expectSize(1).integer(0);
        internal(s);
        List<Map<String, Object>> rows = tx.inOrganization(org, Mode.READ_ONLY, j -> reads.events(j, org, s.membershipId(), run, after, pageSize + 1));
        return page(rows, pageSize, last -> cursors.encode(scope, List.of(((Number) last.get("sequence")).longValue())));
    }

    // ------------------------------------------------------------------ apoio

    private static ValidSession internal(ValidSession s) {
        if (!s.isInternal()) {
            throw ApiException.forbidden("conta_externa");
        }
        return s;
    }

    private static Map<String, Object> page(List<Map<String, Object>> rows, int pageSize, java.util.function.Function<Map<String, Object>, String> cursorOf) {
        String next = rows.size() > pageSize ? cursorOf.apply(rows.get(pageSize - 1)) : null;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", new ArrayList<>(rows.subList(0, Math.min(pageSize, rows.size()))));
        r.put("nextCursor", next);
        return r;
    }

    private static OffsetDateTime instant(String s) {
        if (s == null) {
            throw ApiException.invalid("data");
        }
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException e) {
            throw ApiException.invalid("data");
        }
    }

    private static UUID uuid(String s, String field) {
        if (s == null) {
            throw ApiException.invalid(field);
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid(field);
        }
    }

    private static UUID uuidOrNull(String s, String field) {
        return s == null ? null : uuid(s, field);
    }
}
