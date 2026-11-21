package com.nekkora.portal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * Area do cliente (PLATFORM-FLUXO-002, 070): so GET, pela sessao de IDENTIDADE (cookie {@code __Host-nekkora_id}). A pessoa e
 * autenticada pela identidade global; o que ela ve em cada organizacao e o que o vinculo de cliente e as concessoes vigentes
 * permitem, reconferido no banco a cada requisicao. R7 (limite de leitura do portal) por IP antes e por sessao depois.
 */
@RestController
public class PortalClientController {

    private static final String COOKIE = "__Host-nekkora_id";
    private static final String BASE = "/portal/api/v1";

    private final PortalClientService client;
    private final RedisLimiter redis;
    private final PortalProperties.Limits limits;

    public PortalClientController(PortalClientService client, RedisLimiter redis, PortalProperties props) {
        this.client = client;
        this.redis = redis;
        this.limits = props.limits();
    }

    @GetMapping(BASE + "/me/client-organizations")
    public Map<String, Object> organizations(HttpServletRequest req) {
        String token = before(req);
        List<Map<String, Object>> r = client.organizations(token);
        after(token);
        return Map.of("items", r);
    }

    @GetMapping(BASE + "/o/{org}/shared-automations")
    public Map<String, Object> automations(@PathVariable UUID org, HttpServletRequest req) {
        String token = before(req);
        List<Map<String, Object>> r = client.automations(org, token);
        after(token);
        return Map.of("items", r);
    }

    @GetMapping(BASE + "/o/{org}/shared-automations/{automation}/executions")
    public Map<String, Object> executions(@PathVariable UUID org, @PathVariable UUID automation, @RequestParam(required = false) String cursor,
                                          @RequestParam(required = false) Integer limit, HttpServletRequest req) {
        String token = before(req);
        Map<String, Object> r = client.executions(org, token, automation, cursor, limit == null ? PortalClientService.DEFAULT_PAGE : limit);
        after(token);
        return r;
    }

    @GetMapping(BASE + "/o/{org}/shared-executions/{kind}/{id}")
    public Map<String, Object> execution(@PathVariable UUID org, @PathVariable String kind, @PathVariable UUID id, HttpServletRequest req) {
        String token = before(req);
        Map<String, Object> r = client.execution(org, token, kind, id);
        after(token);
        return r;
    }

    @GetMapping(BASE + "/o/{org}/shared-executions/remote/{id}/attempts/{attempt}/logs")
    public ResponseEntity<Map<String, Object>> logs(@PathVariable UUID org, @PathVariable UUID id, @PathVariable UUID attempt,
                                                    @RequestParam(required = false) Integer after, @RequestParam(required = false) Integer limit,
                                                    HttpServletRequest req) {
        String token = before(req);
        Map<String, Object> r = client.logs(org, token, id, attempt, after == null ? 0 : after, limit == null ? PortalClientService.MAX_LOG_PAGE : limit);
        after(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(r);
    }

    @GetMapping(BASE + "/o/{org}/shared-executions/remote/{id}/attempts/{attempt}/results")
    public ResponseEntity<Map<String, Object>> results(@PathVariable UUID org, @PathVariable UUID id, @PathVariable UUID attempt, HttpServletRequest req) {
        String token = before(req);
        Map<String, Object> r = client.results(org, token, id, attempt);
        after(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(r);
    }

    /** Os bytes como anexo, nunca interpretados pelo navegador (como a rota interna T03b). */
    @GetMapping(BASE + "/o/{org}/shared-executions/remote/{id}/attempts/{attempt}/results/{number}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID org, @PathVariable UUID id, @PathVariable UUID attempt, @PathVariable int number,
                                          HttpServletRequest req) {
        String token = before(req);
        PortalClientService.Download d = client.download(org, token, id, attempt, number);
        after(token);
        String base = d.fileName().substring(d.fileName().lastIndexOf('/') + 1);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", ContentDisposition.attachment().filename(base, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").header("Nekkora-Result-Sha256", d.sha256()).body(d.bytes());
    }

    private String before(HttpServletRequest req) {
        applyLimit("ip:" + RequestContextFilter.ip(req));
        String token = cookie(req);
        PortalClientService.requireFormat(token);
        return token;
    }

    private void after(String token) {
        applyLimit("id:" + Tokens.sha256Hex(token));
    }

    private void applyLimit(String key) {
        try {
            long waitTime = redis.consume("nk:rl:r7p:" + key, limits.r7Capacity(), limits.r7PerSecond());
            if (waitTime > 0) {
                throw new ApiException(ApiError.RATE_LIMITED, "R7", Math.max(1, (waitTime + 999) / 1000));
            }
        } catch (LimiterUnavailable e) {
            org.slf4j.LoggerFactory.getLogger(PortalClientController.class).warn("evento=limitador.indisponivel regra=R7 efeito=leitura_segue");
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
