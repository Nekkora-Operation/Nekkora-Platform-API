package com.nekkora.agentgateway;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nekkora.core.token.Tokens;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validacao do envelope {@code nekkora-agent/observability} 1.0.0 com as MESMAS regras do Agent ({@code obs/ActivityEvent}
 * em {@code Nekkora-Agent} f77e00e; o arquivo de schema do Agent nao e carregado: achado A01 da COMPAT-001, escape invalido)
 * e forma canonica para a deduplicacao (chaves ordenadas, so os campos conhecidos, SHA-256 do texto UTF-8).
 *
 * <p>Compatibilidade (contrato, sec. 8 e 15): versao 1.0.x exige exatamente os campos conhecidos; 1.y (y maior que 0) e
 * aceita descartando e contando os campos novos; outra versao maior e recusada. A recusa traz so um motivo fechado (nunca o
 * valor recusado).
 */
public final class ObsEnvelope {

    public static final String SCHEMA = "nekkora-agent/observability";
    static final int MAX_BYTES = 4096;
    static final long MAX_SEQUENCE = 1_000_000L;
    static final long MAX_NUMBER = 1_000_000_000_000L;

    static final Pattern VERSION = Pattern.compile("1\\.([0-9]{1,3})\\.([0-9]{1,3})");
    static final Pattern EXECUTION = Pattern.compile("ex-[0-9a-f]{32}");
    static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    static final Pattern TX_REF = Pattern.compile("t-[0-9a-f]{16}");
    static final Pattern STEP = Pattern.compile("[a-z][a-z0-9_]{0,63}(\\.[a-z0-9_]{1,63}){0,7}");
    static final Pattern UUID_FORMAT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    static final Pattern ACTION = Pattern.compile("[a-z][a-z_]{0,39}");
    static final Pattern STATUS = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");
    static final Pattern ERROR = Pattern.compile("[A-Z][A-Z0-9_]{1,63}|AGT-[A-Z]{2,5}-[0-9]{3}");
    static final Pattern COUNT_KEY = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    static final Pattern FW_EXECUTION = Pattern.compile("ex-[0-9a-f]{8,64}");
    static final Pattern DIGIT_RUN = Pattern.compile("[0-9]{4,}");

    static final Set<String> SOURCES = Set.of("AGENT", "FRAMEWORK", "COLLECTOR");
    static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "ERROR");
    static final Set<String> CATEGORIES = Set.of("LIFECYCLE", "ACTIVITY", "BUSINESS", "FAILURE", "TELEMETRY");
    static final Set<String> PHASES = Set.of("INIT", "TRANSFER_DATA_IN", "PROCESS", "TRANSFER_DATA_OUT", "END");
    static final Set<String> FIELDS = Set.of("schema", "version", "agent_execution_id", "sequence", "timestamp_utc", "observed_utc", "source",
            "event_code", "severity", "category", "phase", "transaction_ref", "step_id", "studio_step_id", "action", "attempt", "elapsed_ms",
            "status", "error_code", "counts", "correlation");
    static final Set<String> CORRELATION_FIELDS = Set.of("framework_execution_id", "source_position");

    static final JsonMapper JSON = JsonMapper.builder().build();

    private ObsEnvelope() {
    }

    /** Envelope aceito: forma canonica, hash e as projecoes usadas nas consultas. */
    public record Valid(long sequence, String canonical, String sha256, short source, String eventCode, short severity, short category,
                        String stepId, UUID studioStepId, String errorCode, Instant timestampUtc, Instant observedUtc,
                        String frameworkExecutionId, int ignoredFields) {
    }

    /** Recusa: a sequence, quando legivel, e o motivo fechado. */
    public record Invalid(Long sequence, String reason) {
    }

    /** Resultado da validacao de um item do lote: exatamente um dos dois. */
    public record Result(Valid valid, Invalid invalid) {
        static Result ok(Valid v) {
            return new Result(v, null);
        }

        static Result refused(Long sequence, String reason) {
            return new Result(null, new Invalid(sequence, reason));
        }
    }

    private static final class Refused extends RuntimeException {
        final String reason;

        Refused(String reason) {
            super(null, null, false, false);
            this.reason = reason;
        }
    }

    private static void check(boolean ok, String reason) {
        if (!ok) {
            throw new Refused(reason);
        }
    }

    static boolean identifier(String s) {
        return !DIGIT_RUN.matcher(s).find();
    }

    /** Valida um envelope do lote da execucao {@code agentExecutionId} (a da URL). */
    public static Result validate(JsonNode e, String agentExecutionId) {
        Long seq = e != null && e.isObject() && e.get("sequence") != null && e.get("sequence").canConvertToLong() ? e.get("sequence").asLong() : null;
        try {
            return Result.ok(parse(e, agentExecutionId));
        } catch (Refused r) {
            return Result.refused(seq, r.reason);
        }
    }

    private static Valid parse(JsonNode e, String agentExecutionId) {
        check(e != null && e.isObject(), "not_object");
        check(SCHEMA.equals(text(e, "schema")), "schema");
        String version = text(e, "version");
        Matcher vm = version == null ? null : VERSION.matcher(version);
        check(vm != null && vm.matches(), "version_unsupported");
        boolean strict = Integer.parseInt(vm.group(1)) == 0;
        int ignored = 0;
        TreeMap<String, Object> canon = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = e.properties().iterator(); it.hasNext(); ) {
            String k = it.next().getKey();
            if (!FIELDS.contains(k)) {
                check(!strict, "unknown_field");
                ignored++;
            }
        }
        canon.put("schema", SCHEMA);
        canon.put("version", version);

        String id = text(e, "agent_execution_id");
        check(id != null && EXECUTION.matcher(id).matches(), "agent_execution_id");
        check(id.equals(agentExecutionId), "execution_mismatch");
        canon.put("agent_execution_id", id);

        long sequence = integer(e, "sequence", true, 1, MAX_SEQUENCE);
        canon.put("sequence", sequence);

        Instant occurred = instant(e, "timestamp_utc", false);
        if (occurred != null) {
            canon.put("timestamp_utc", text(e, "timestamp_utc"));
        }
        Instant observed = instant(e, "observed_utc", true);
        canon.put("observed_utc", text(e, "observed_utc"));

        String source = text(e, "source");
        check(source != null && SOURCES.contains(source), "source");
        canon.put("source", source);
        String code = text(e, "event_code");
        check(code != null && CODE.matcher(code).matches(), "event_code");
        canon.put("event_code", code);
        String severity = text(e, "severity");
        check(severity != null && SEVERITIES.contains(severity), "severity");
        canon.put("severity", severity);
        String category = text(e, "category");
        check(category != null && CATEGORIES.contains(category), "category");
        canon.put("category", category);

        optional(e, canon, "phase", v -> PHASES.contains(v));
        optional(e, canon, "transaction_ref", v -> TX_REF.matcher(v).matches());
        String step = optional(e, canon, "step_id", v -> v.length() <= 120 && STEP.matcher(v).matches() && identifier(v));
        String studioStep = optional(e, canon, "studio_step_id", v -> UUID_FORMAT.matcher(v).matches());
        optional(e, canon, "action", v -> ACTION.matcher(v).matches());
        if (e.has("attempt")) {
            canon.put("attempt", integer(e, "attempt", true, 0, 10_000));
        }
        if (e.has("elapsed_ms")) {
            canon.put("elapsed_ms", integer(e, "elapsed_ms", true, 0, MAX_NUMBER - 1));
        }
        optional(e, canon, "status", v -> STATUS.matcher(v).matches() && identifier(v));
        String error = optional(e, canon, "error_code", v -> ERROR.matcher(v).matches() && identifier(v));

        if (e.has("counts")) {
            JsonNode c = e.get("counts");
            check(c.isObject() && c.size() <= 16, "counts");
            TreeMap<String, Object> counts = new TreeMap<>();
            for (Map.Entry<String, JsonNode> kv : c.properties()) {
                check(COUNT_KEY.matcher(kv.getKey()).matches(), "counts");
                JsonNode v = kv.getValue();
                check(v.isIntegralNumber() && v.canConvertToLong() && v.asLong() >= 0 && v.asLong() < MAX_NUMBER, "counts");
                counts.put(kv.getKey(), v.asLong());
            }
            canon.put("counts", counts);
        }
        String fw = null;
        if (e.has("correlation")) {
            JsonNode c = e.get("correlation");
            check(c.isObject(), "correlation");
            TreeMap<String, Object> corr = new TreeMap<>();
            for (Map.Entry<String, JsonNode> kv : c.properties()) {
                if (!CORRELATION_FIELDS.contains(kv.getKey())) {
                    check(!strict, "unknown_field");
                    ignored++;
                }
            }
            if (c.has("framework_execution_id")) {
                JsonNode v = c.get("framework_execution_id");
                check(v.isString() && FW_EXECUTION.matcher(v.asString()).matches(), "framework_execution_id");
                fw = v.asString();
                corr.put("framework_execution_id", fw);
            }
            if (c.has("source_position")) {
                JsonNode v = c.get("source_position");
                check(v.isIntegralNumber() && v.canConvertToLong() && v.asLong() >= 0, "source_position");
                corr.put("source_position", v.asLong());
            }
            if (!corr.isEmpty()) {
                canon.put("correlation", corr);
            }
        }
        String canonical;
        try {
            canonical = JSON.writeValueAsString(canon);
        } catch (RuntimeException x) {
            throw new Refused("not_serializable");
        }
        check(canonical.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES, "too_large");
        return new Valid(sequence, canonical, Tokens.sha256Hex(canonical), code(SOURCES_ORDER, source), code, code(SEVERITY_ORDER, severity),
                code(CATEGORY_ORDER, category), step, studioStep == null ? null : UUID.fromString(studioStep), error, occurred, observed, fw, ignored);
    }

    static final String[] SOURCES_ORDER = {"AGENT", "FRAMEWORK", "COLLECTOR"};
    static final String[] SEVERITY_ORDER = {"INFO", "WARNING", "ERROR"};
    static final String[] CATEGORY_ORDER = {"LIFECYCLE", "ACTIVITY", "BUSINESS", "FAILURE", "TELEMETRY"};

    private static short code(String[] order, String value) {
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(value)) {
                return (short) (i + 1);
            }
        }
        throw new IllegalStateException(value);
    }

    private static String text(JsonNode e, String field) {
        JsonNode v = e.get(field);
        return v != null && v.isString() ? v.asString() : null;
    }

    private static long integer(JsonNode e, String field, boolean required, long min, long max) {
        JsonNode v = e.get(field);
        if (v == null) {
            check(!required, field);
            return 0;
        }
        check(v.isIntegralNumber() && v.canConvertToLong() && v.asLong() >= min && v.asLong() <= max, field);
        return v.asLong();
    }

    private static Instant instant(JsonNode e, String field, boolean required) {
        JsonNode v = e.get(field);
        if (v == null) {
            check(!required, field);
            return null;
        }
        check(v.isString() && v.asString().endsWith("Z") && v.asString().length() <= 40, field);
        try {
            return Instant.parse(v.asString());
        } catch (DateTimeParseException x) {
            throw new Refused(field);
        }
    }

    private static String optional(JsonNode e, TreeMap<String, Object> canon, String field, java.util.function.Predicate<String> rule) {
        JsonNode v = e.get(field);
        if (v == null) {
            return null;
        }
        check(v.isString() && rule.test(v.asString()), field);
        canon.put(field, v.asString());
        return v.asString();
    }
}
