package com.nekkora.platform.web;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.web.ApiException;
import com.nekkora.platform.access.WorkspaceAccess;
import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;
import com.nekkora.platform.session.ValidSession;

/**
 * PLATFORM-FLUXO-002 - leitura do acompanhamento unificado ({@link ActivityReads}): Execucoes ("Em andamento" e "Historico") e
 * a atividade por maquina. So conta interna; cada linha so de ambiente com automation.read. Nada e gravado.
 */
@RestController
public class ActivityController {

    private static final Duration MAX_PERIOD = Duration.ofDays(366);

    private final OrgTransactions tx;
    private final ActivityReads reads;

    public ActivityController(OrgTransactions tx, WorkspaceAccess access) {
        this.tx = tx;
        this.reads = new ActivityReads(access);
    }

    @GetMapping("/api/v1/o/{org}/activity")
    public Map<String, Object> list(@PathVariable UUID org, @RequestParam(required = false) String tab, @RequestParam(required = false) String workspaceId,
                                    @RequestParam(required = false) String automationId, @RequestParam(required = false) String workerId,
                                    @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                    @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit, HttpServletRequest req) {
        ValidSession s = internal(SessionFilter.principal(req));
        String t = tab == null ? "in_progress" : tab;
        if (!t.equals("in_progress") && !t.equals("history")) {
            throw ApiException.invalid("aba");
        }
        OffsetDateTime f = instant(from);
        OffsetDateTime u = instant(to);
        if (f != null && u != null && (!u.isAfter(f) || Duration.between(f, u).compareTo(MAX_PERIOD) > 0)) {
            throw ApiException.invalid("periodo");
        }
        int l = limit == null ? 25 : limit;
        if (l < 1 || l > ActivityReads.MAX_LIMIT) {
            throw ApiException.invalid("limit");
        }
        ActivityReads.Filter filter = new ActivityReads.Filter(t, uuid(workspaceId), uuid(automationId), uuid(workerId), f, u, cursor, l);
        return tx.inOrganization(org, Mode.READ_ONLY, j -> reads.list(j, org, s.membershipId(), filter));
    }

    @GetMapping("/api/v1/o/{org}/activity/machines")
    public Map<String, Object> machines(@PathVariable UUID org, HttpServletRequest req) {
        ValidSession s = internal(SessionFilter.principal(req));
        List<Map<String, Object>> items = tx.inOrganization(org, Mode.READ_ONLY, j -> reads.machines(j, org, s.membershipId()));
        return Map.of("items", items);
    }

    private static UUID uuid(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("identificador");
        }
    }

    private static OffsetDateTime instant(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(v);
        } catch (DateTimeParseException e) {
            throw ApiException.invalid("periodo");
        }
    }

    private static ValidSession internal(ValidSession s) {
        if (!s.isInternal()) {
            throw ApiException.forbidden("conta_externa");
        }
        return s;
    }
}
