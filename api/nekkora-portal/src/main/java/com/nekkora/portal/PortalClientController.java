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
 * Area do cliente (PLATFORM-FLUXO-002, 070/071): so GET, pela sessao de IDENTIDADE (cookie {@code __Host-nekkora_id}). A pessoa e
 * autenticada pela identidade global; o que ela ve em cada organizacao e o que o vinculo de cliente e as concessoes vigentes
 * permitem (dashboard de execucao e arquivos de retorno; nunca registros tecnicos), reconferido no banco a cada requisicao. R7 (limite de leitura do portal) por IP antes e por sessao depois.
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

    /** Resumo do periodo (padrao: 30 dias) de uma automacao com dashboard; sem dashboard: 403; nao compartilhada: 404. */
    @GetMapping(BASE + "/o/{org}/shared-automations/{automation}/summary")
    public Map<String, Object> summary(@PathVariable UUID org, @PathVariable UUID automation, @RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to, HttpServletRequest req) {
        String token = before(req);
        Map<String, Object> r = client.summary(org, token, automation, PortalClientService.Period.of(from, to));
        after(token);
        return r;
    }

    @GetMapping(BASE + "/o/{org}/shared-automations/{automation}/executions")
    public Map<String, Object> executions(@PathVariable UUID org, @PathVariable UUID automation, @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to, @RequestParam(required = false) String cursor,
                                          @RequestParam(required = false) Integer limit, HttpServletRequest req) {
        String token = before(req);
        Map<String, Object> r = client.executions(org, token, automation, PortalClientService.Period.of(from, to), cursor,
                limit == null ? PortalClientService.DEFAULT_PAGE : limit);
        after(token);
        return r;
    }

    /** Arquivos de retorno autorizados (todas as automacoes com arquivos ou uma), pelo periodo da execucao e busca no nome. */
    @GetMapping(BASE + "/o/{org}/shared-files")
    public ResponseEntity<Map<String, Object>> files(@PathVariable UUID org, @RequestParam(required = false) UUID automationId,
                                                     @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                     @RequestParam(required = false) String q, @RequestParam(required = false) String cursor,
                                                     @RequestParam(required = false) Integer limit, HttpServletRequest req) {
        String token = before(req);
        Map<String, Object> r = client.files(org, token, automationId, PortalClientService.Period.of(from, to), q, cursor,
                limit == null ? PortalClientService.DEFAULT_PAGE : limit);
        after(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(r);
    }

    /**
     * Os bytes como anexo, nunca interpretados pelo navegador nesta origem (como a rota interna T03b). A tela "Visualizar" le os bytes
     * e mostra o conteudo na propria pagina (texto, imagem ou PDF), sem servir conteudo ativo pelo Portal.
     */
    @GetMapping(BASE + "/o/{org}/shared-files/{attempt}/{number}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID org, @PathVariable UUID attempt, @PathVariable int number, HttpServletRequest req) {
        String token = before(req);
        PortalClientService.Download d = client.download(org, token, attempt, number);
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
