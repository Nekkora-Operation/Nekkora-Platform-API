package com.nekkora.agentgateway;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.nekkora.core.web.ApiException;

import tools.jackson.databind.JsonNode;

/**
 * Relatorio do supervisor (contrato, sec. 7): os campos do {@code estado.json} 1.0.0 do Agent. O estado so muda por aqui.
 * Os instantes sao do relogio do Agent: guardados como informados, nunca usados para ordenar nem decidir.
 */
public record SupervisorReport(State state, String code, Integer exitCode, Map<String, Long> counts, Instant created, Instant started, Instant ended,
                               boolean adopted, int suspensions, int locks) {

    /** Estados do Agent, com o codigo permanente da 025 (agent_runs.supervisor_state). */
    public enum State {
        READY(1), STARTING(2), RUNNING(3), SUCCEEDED(4), FAILED(5), CANCELLED(6), TIMED_OUT(7), BLOCKED(8), OUTCOME_UNKNOWN(9);

        final short code;

        State(int code) {
            this.code = (short) code;
        }

        public short code() {
            return code;
        }

        public boolean terminal() {
            return code >= 4;
        }

        /** Ordem do ciclo: READY < STARTING < RUNNING < terminal. */
        int rank() {
            return Math.min(code, 4);
        }

        public static State of(short code) {
            for (State s : values()) {
                if (s.code == code) {
                    return s;
                }
            }
            throw new IllegalArgumentException("estado " + code);
        }

        /** As transicoes do Agent (exec/ExecutionState.java) - as mesmas de public.agent_run_transition_allowed. */
        public boolean canMoveTo(State to) {
            return switch (this) {
                case READY -> List.of(STARTING, BLOCKED, CANCELLED).contains(to);
                case STARTING -> List.of(RUNNING, BLOCKED, FAILED, CANCELLED, OUTCOME_UNKNOWN).contains(to);
                case RUNNING -> List.of(SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, OUTCOME_UNKNOWN).contains(to);
                default -> false;
            };
        }
    }

    /** Como o relatorio foi tratado (agent_run_reports.outcome). */
    public enum Outcome {
        APPLIED(1), UNCHANGED(2), STALE(3), CONFLICT(4);

        final short code;

        Outcome(int code) {
            this.code = (short) code;
        }

        public short code() {
            return code;
        }
    }

    static final Pattern CODE = Pattern.compile("AGT-[A-Z]{2,5}-[0-9]{3}");
    static final Pattern COUNT_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");

    /** Decisao sobre o relatorio {@code next} diante do registro atual {@code current} (nada e gravado aqui). */
    public static Outcome decide(SupervisorReport current, SupervisorReport next) {
        if (current.state == next.state) {
            if (current.sameDetails(next)) {
                return Outcome.UNCHANGED;
            }
            // terminal congelado: o mesmo estado com outros dados e contradicao; nao terminal: os dados evoluem
            return current.state.terminal() ? Outcome.CONFLICT : Outcome.APPLIED;
        }
        if (current.state.canMoveTo(next.state)) {
            return Outcome.APPLIED;
        }
        if (!next.state.terminal() && next.state.rank() < current.state.rank()) {
            return Outcome.STALE;
        }
        return Outcome.CONFLICT;
    }

    boolean sameDetails(SupervisorReport o) {
        return Objects.equals(code, o.code) && Objects.equals(exitCode, o.exitCode) && counts.equals(o.counts) && Objects.equals(created, o.created)
                && Objects.equals(started, o.started) && Objects.equals(ended, o.ended) && adopted == o.adopted && suspensions == o.suspensions
                && locks == o.locks;
    }

    /** Validacao do corpo; recusa como 400 sem ecoar o valor. */
    public static SupervisorReport parse(JsonNode n) {
        if (n == null || !n.isObject()) {
            throw ApiException.invalid("supervisor");
        }
        for (Map.Entry<String, JsonNode> kv : n.properties()) {
            if (!List.of("state", "code", "exitCode", "counts", "created", "started", "ended", "adopted", "suspensions", "locks").contains(kv.getKey())) {
                throw ApiException.invalid("supervisor_campo");
            }
        }
        State state;
        try {
            state = n.get("state") != null && n.get("state").isString() ? State.valueOf(n.get("state").asString()) : null;
        } catch (IllegalArgumentException e) {
            state = null;
        }
        if (state == null) {
            throw ApiException.invalid("supervisor_estado");
        }
        String code = nullableText(n, "code");
        if (code != null && !CODE.matcher(code).matches() || code != null && !state.terminal()) {
            throw ApiException.invalid("supervisor_codigo");
        }
        Integer exit = null;
        if (n.has("exitCode") && !n.get("exitCode").isNull()) {
            JsonNode v = n.get("exitCode");
            if (!v.isIntegralNumber() || !v.canConvertToInt()) {
                throw ApiException.invalid("supervisor_saida");
            }
            exit = v.asInt();
        }
        Map<String, Long> counts = new TreeMap<>();
        if (n.has("counts") && !n.get("counts").isNull()) {
            JsonNode c = n.get("counts");
            if (!c.isObject() || c.size() > 16) {
                throw ApiException.invalid("supervisor_contagens");
            }
            for (Map.Entry<String, JsonNode> kv : c.properties()) {
                JsonNode v = kv.getValue();
                if (!COUNT_KEY.matcher(kv.getKey()).matches() || !v.isIntegralNumber() || !v.canConvertToLong() || v.asLong() < 0
                        || v.asLong() >= ObsEnvelope.MAX_NUMBER) {
                    throw ApiException.invalid("supervisor_contagens");
                }
                counts.put(kv.getKey(), v.asLong());
            }
        }
        return new SupervisorReport(state, code, exit, Map.copyOf(counts), instant(n, "created"), instant(n, "started"), instant(n, "ended"),
                bool(n, "adopted"), small(n, "suspensions"), small(n, "locks"));
    }

    private static String nullableText(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isString()) {
            throw ApiException.invalid("supervisor_" + f);
        }
        return v.asString();
    }

    private static Instant instant(JsonNode n, String f) {
        String s = nullableText(n, f);
        if (s == null) {
            return null;
        }
        if (!s.endsWith("Z") || s.length() > 40) {
            throw ApiException.invalid("supervisor_instante");
        }
        try {
            // o banco guarda microssegundos: truncado aqui, o relatorio repetido compara igual ao gravado
            return Instant.parse(s).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        } catch (DateTimeParseException e) {
            throw ApiException.invalid("supervisor_instante");
        }
    }

    private static boolean bool(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) {
            return false;
        }
        if (!v.isBoolean()) {
            throw ApiException.invalid("supervisor_" + f);
        }
        return v.asBoolean();
    }

    private static int small(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) {
            return 0;
        }
        if (!v.isIntegralNumber() || !v.canConvertToInt() || v.asInt() < 0 || v.asInt() > 1_000_000) {
            throw ApiException.invalid("supervisor_" + f);
        }
        return v.asInt();
    }
}
