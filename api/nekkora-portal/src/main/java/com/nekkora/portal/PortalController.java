package com.nekkora.portal;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.ratelimit.RedisLimiter;
import com.nekkora.core.ratelimit.RedisLimiter.LimiterUnavailable;
import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContextFilter;

/**
 * Rotas do portal (contrato 8.7, §5.4). O cookie de sessao e lido so para passar o token bruto como bind; o
 * processo nunca cria nem revoga sessao. R7: por IP antes da chamada; por sessao depois dela (a chamada que
 * excede o limite por sessao ja fez o trabalho - limitacao declarada no contrato).
 */
@RestController
public class PortalController {

    private static final String COOKIE = "__Host-nekkora_session";

    private final PortalService portal;
    private final RedisLimiter redis;
    private final PortalProperties.Limits limits;

    public PortalController(PortalService portal, RedisLimiter redis, PortalProperties props) {
        this.portal = portal;
        this.redis = redis;
        this.limits = props.limits();
    }

    @GetMapping("/portal/api/v1/o/{org}/workspaces")
    public Map<String, Object> workspaces(@PathVariable UUID org, HttpServletRequest req) {
        String token = before(req);
        List<Map<String, Object>> r = portal.workspaces(org, token);
        after(token);
        return Map.of("items", r);
    }

    @GetMapping("/portal/api/v1/o/{org}/executions")
    public Map<String, Object> executions(@PathVariable UUID org, @RequestParam(required = false) UUID workspaceId,
                                         @RequestParam(required = false) List<String> status, @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to, @RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) Integer limit, HttpServletRequest req) {
        String token = before(req);
        int pageSize = limit == null ? PortalService.DEFAULT_PAGE_SIZE : limit;
        if (pageSize < 1 || pageSize > PortalService.MAX_PAGE) {
            throw ApiException.invalid("limit");
        }
        short[] statusCodes = null;
        if (status != null) {
            if (status.isEmpty() || status.size() > 6) {
                throw ApiException.invalid("status");
            }
            statusCodes = new short[status.size()];
            for (int i = 0; i < statusCodes.length; i++) {
                statusCodes[i] = Labels.statusCodeOf(status.get(i));
            }
        }
        OffsetDateTime fromTime = data(from);
        OffsetDateTime toTime = data(to);
        if (fromTime != null && toTime != null && (toTime.isBefore(fromTime) || Duration.between(fromTime, toTime).toDays() > 366)) {
            throw ApiException.invalid("periodo");
        }
        Cursor c = cursor == null ? null : Cursor.decode(cursor);
        Map<String, Object> r = portal.executions(org, token, new PortalService.ExecutionFilter(workspaceId, statusCodes, fromTime, toTime, c, pageSize));
        after(token);
        return r;
    }

    @GetMapping("/portal/api/v1/o/{org}/executions/{id}")
    public Map<String, Object> execution(@PathVariable UUID org, @PathVariable UUID id, HttpServletRequest req) {
        String token = before(req);
        Map<String, Object> r = portal.execution(org, token, id);
        after(token);
        return r;
    }

    @GetMapping("/portal/api/v1/o/{org}/executions/{id}/events")
    public Map<String, Object> events(@PathVariable UUID org, @PathVariable UUID id, HttpServletRequest req) {
        String token = before(req);
        List<Map<String, Object>> r = portal.events(org, token, id);
        after(token);
        return Map.of("items", r);
    }

    private String before(HttpServletRequest req) {
        applyLimit("ip:" + RequestContextFilter.ip(req));
        String token = cookie(req);
        if (!Tokens.isValidFormat(token)) {
            throw ApiException.unauthenticated("sem_sessao");
        }
        return token;
    }

    private void after(String token) {
        applyLimit("s:" + Tokens.sha256Hex(token));
    }

    /** Redis indisponivel: leitura segue (a sessao e validada pela 013 a cada chamada), com alerta (§2.1, item 6). */
    private void applyLimit(String key) {
        try {
            long waitTime = redis.consume("nk:rl:r7p:" + key, limits.r7Capacity(), limits.r7PerSecond());
            if (waitTime > 0) {
                throw new ApiException(ApiError.RATE_LIMITED, "R7", Math.max(1, (waitTime + 999) / 1000));
            }
        } catch (LimiterUnavailable e) {
            org.slf4j.LoggerFactory.getLogger(PortalController.class).warn("evento=limitador.indisponivel regra=R7 efeito=leitura_segue");
        }
    }

    private static OffsetDateTime data(String s) {
        if (s == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException e) {
            throw ApiException.invalid("data");
        }
    }

    private static String cookie(HttpServletRequest req) {
        Cookie[] cs = req.getCookies();
        if (cs == null) {
            return null;
        }
        String v = null;
        for (Cookie c : cs) {
            if (COOKIE.equals(c.getName())) {
                if (v != null) {
                    return null;
                }
                v = c.getValue();
            }
        }
        return v;
    }
}
