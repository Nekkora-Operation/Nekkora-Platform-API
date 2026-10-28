package com.nekkora.agentgateway;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.ratelimit.RedisLimiter;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContext;
import com.nekkora.core.web.RequestContextFilter;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rotas da maquina ({@code /agent/api/v1}, contrato Platform-Agent 1.0). Sem cookie, sessao nem CSRF: so a credencial de
 * maquina no {@code Authorization}. Toda chamada declara a versao do contrato ({@code Nekkora-Agent-Contract}, maior 1).
 * Corpo JSON estrito: chave duplicada e profundidade acima de 8 sao recusadas antes de qualquer acesso ao banco.
 */
@RestController
public class GatewayController {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayController.class);
    static final String CONTRACT_HEADER = "Nekkora-Agent-Contract";
    static final Duration FAILURE_WINDOW = Duration.ofHours(1);

    static final JsonMapper STRICT = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).build())
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .build();

    private final GatewayService service;
    private final EventIngestion ingestion;
    private final RedisLimiter limiter;
    private final GatewayProperties.Limits limits;

    public GatewayController(GatewayService service, EventIngestion ingestion, RedisLimiter limiter, GatewayProperties properties) {
        this.service = service;
        this.ingestion = ingestion;
        this.limiter = limiter;
        this.limits = properties.limits();
    }

    // ------------------------------------------------------------------ resgate (pareamento e rotacao)

    @PostMapping("/agent/api/v1/enrollments/redeem")
    public ResponseEntity<GatewayService.Redeemed> redeem(@RequestBody(required = false) String body, HttpServletRequest req) {
        requireContract(req);
        String ip = RequestContextFilter.ip(req);
        guardFailures(ip);
        JsonNode n = parse(body);
        MachineToken code = MachineToken.parse(text(n, "code"), MachineToken.ENROLLMENT).orElse(null);
        if (code == null) {
            failure(ip, "codigo_formato");
        }
        JsonNode versions = n.get("contractVersions");
        if (versions == null || !versions.isArray() || versions.size() > 8 || !acceptsMajorOne(versions)) {
            throw new ApiException(ApiError.UNSUPPORTED_CONTRACT, "versoes");
        }
        Optional<MachineToken> current = MachineToken.bearer(req.getHeader("Authorization"));
        try {
            GatewayService.Redeemed r = service.redeem(code, text(n, "credentialSecret"), text(n, "agentVersion"), current);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(r);
        } catch (ApiException e) {
            if (e.apiError() == ApiError.UNAUTHENTICATED) {
                failure(ip, e.internalReason());
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ presenca e autorizacoes

    @PostMapping("/agent/api/v1/heartbeat")
    public GatewayService.Presence heartbeat(@RequestBody(required = false) String body, HttpServletRequest req) {
        MachineToken t = machine(req);
        JsonNode n = parse(body);
        List<String> caps = new ArrayList<>();
        JsonNode c = n.get("capabilities");
        if (c == null || !c.isArray() || c.size() > 8) {
            throw ApiException.invalid("capacidades");
        }
        c.forEach(x -> caps.add(x.isString() ? x.asString() : "?"));
        return authenticated(req, () -> service.heartbeat(t, text(n, "agentVersion"), text(n, "contractVersion"), caps));
    }

    @GetMapping("/agent/api/v1/authorizations")
    public Map<String, List<GatewayService.Authorization>> authorizations(HttpServletRequest req) {
        MachineToken t = machine(req);
        return Map.of("items", authenticated(req, () -> service.authorizations(t)));
    }

    // ------------------------------------------------------------------ registro, supervisor e eventos

    @PostMapping("/agent/api/v1/runs")
    public ResponseEntity<GatewayService.Registered> register(@RequestBody(required = false) String body, HttpServletRequest req) {
        MachineToken t = machine(req);
        JsonNode n = parse(body);
        UUID authorization = uuid(text(n, "authorizationId"));
        SupervisorReport report = SupervisorReport.parse(n.get("supervisor"));
        GatewayService.Registered r = authenticated(req, () -> service.register(t, text(n, "agentExecutionId"), authorization,
                text(n, "packageFingerprint"), report));
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(r);
    }

    @PostMapping("/agent/api/v1/runs/{agentExecutionId}/supervisor")
    public ResponseEntity<GatewayService.Reported> supervisor(@PathVariable String agentExecutionId, @RequestBody(required = false) String body,
                                                              HttpServletRequest req) {
        MachineToken t = machine(req);
        SupervisorReport report = SupervisorReport.parse(parse(body));
        GatewayService.Reported r = authenticated(req, () -> service.report(t, agentExecutionId, report));
        // CONFLICT ja foi registrado (commit): o estado nao mudou e a resposta diz isso
        return ResponseEntity.status("CONFLICT".equals(r.outcome()) ? HttpStatus.CONFLICT : HttpStatus.OK).body(r);
    }

    @PostMapping("/agent/api/v1/runs/{agentExecutionId}/events")
    public EventIngestion.Ack events(@PathVariable String agentExecutionId, @RequestBody(required = false) String body, HttpServletRequest req) {
        MachineToken t = machine(req);
        if (agentExecutionId == null || !GatewayService.EXECUTION.matcher(agentExecutionId).matches()) {
            throw ApiException.invalid("execucao");
        }
        JsonNode n = parse(body);
        JsonNode events = n.get("events");
        JsonNode missing = n.get("declaredMissing");
        if (events == null || !events.isArray() || events.size() > EventIngestion.MAX_EVENTS
                || missing != null && (!missing.isArray() || missing.size() > EventIngestion.MAX_MISSING_RANGES)) {
            throw ApiException.invalid("lote");
        }
        List<ObsEnvelope.Result> items = new ArrayList<>(events.size());
        events.forEach(e -> items.add(ObsEnvelope.validate(e, agentExecutionId)));
        List<EventIngestion.Range> ranges = new ArrayList<>();
        if (missing != null) {
            for (JsonNode r : missing) {
                if (!r.isObject() || r.size() != 2 || !r.path("from").isIntegralNumber() || !r.path("to").isIntegralNumber()) {
                    throw ApiException.invalid("ausencia");
                }
                ranges.add(new EventIngestion.Range(r.get("from").asLong(), r.get("to").asLong()));
            }
        }
        return authenticated(req, () -> ingestion.ingest(t, agentExecutionId, items, ranges, m -> {
            long wait = limiter.consume("agent:lotes:" + m.organization(), limits.batchesPerMinute(), limits.batchesPerMinute() / 60.0);
            if (wait > 0) {
                throw new ApiException(ApiError.RATE_LIMITED, "lotes_por_organizacao", Math.max(1, (wait + 999) / 1000));
            }
        }));
    }

    // ------------------------------------------------------------------ apoio

    private MachineToken machine(HttpServletRequest req) {
        requireContract(req);
        String ip = RequestContextFilter.ip(req);
        guardFailures(ip);
        return MachineToken.bearer(req.getHeader("Authorization")).orElseGet(() -> {
            failure(ip, "credencial_formato");
            return null;
        });
    }

    /** Executa o ato; um 401 (credencial recusada no banco) conta como falha do IP de origem. */
    private <T> T authenticated(HttpServletRequest req, java.util.function.Supplier<T> act) {
        try {
            return act.get();
        } catch (ApiException e) {
            if (e.apiError() == ApiError.UNAUTHENTICATED) {
                failure(RequestContextFilter.ip(req), e.internalReason());
            }
            throw e;
        }
    }

    private void guardFailures(String ip) {
        if (limiter.count("agent:falhas:" + ip, FAILURE_WINDOW) >= limits.redeemFailuresPerHour()) {
            throw new ApiException(ApiError.RATE_LIMITED, "falhas_por_ip", FAILURE_WINDOW.toSeconds());
        }
    }

    private void failure(String ip, String reason) {
        limiter.recordFailure("agent:falhas:" + ip, FAILURE_WINDOW);
        LOG.info("evento=agente.autenticacao_recusada motivo={} correlacao={}", reason, RequestContext.correlation());
        throw ApiException.unauthenticated(reason);
    }

    static void requireContract(HttpServletRequest req) {
        String v = req.getHeader(CONTRACT_HEADER);
        if (v == null || !v.matches("1\\.[0-9]{1,3}")) {
            throw new ApiException(ApiError.UNSUPPORTED_CONTRACT, "cabecalho");
        }
    }

    static boolean acceptsMajorOne(JsonNode versions) {
        for (JsonNode v : versions) {
            if (v.isString() && v.asString().matches("1\\.[0-9]{1,3}")) {
                return true;
            }
        }
        return false;
    }

    static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            throw ApiException.invalid("corpo");
        }
        try {
            JsonNode n = STRICT.readTree(body);
            if (n == null || !n.isObject()) {
                throw ApiException.invalid("corpo");
            }
            return n;
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ApiException.invalid("json");
        }
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isString() ? v.asString() : null;
    }

    static UUID uuid(String s) {
        if (s == null || !s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw ApiException.invalid("uuid");
        }
        return UUID.fromString(s);
    }
}
