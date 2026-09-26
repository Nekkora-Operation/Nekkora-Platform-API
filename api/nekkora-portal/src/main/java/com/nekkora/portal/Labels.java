package com.nekkora.portal;

import java.util.Map;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;

/**
 * Rotulos do catalogo externo v1 (§11.5) como codigos estaveis em ingles (contrato 8.7, §1); a traducao fica na
 * interface. Codigo desconhecido -> erro interno, sem expor o valor.
 */
public final class Labels {

    private static final Map<Integer, String> STATUS = Map.of(1, "pending", 2, "starting", 3, "running", 4, "succeeded", 5, "failed", 6, "cancelled");
    private static final Map<Integer, String> REASON = Map.of(1, "process_completed", 2, "start_failed", 3, "process_failed",
            4, "cancelled_before_dispatch", 5, "cancel_confirmed", 6, "queue_deadline_exceeded");
    private static final Map<Integer, String> EVENT = Map.of(1, "process_started", 2, "process_succeeded", 3, "process_failed",
            4, "process_start_failed", 5, "process_cancelled");

    private Labels() {
    }

    public static String status(int code) {
        return label(STATUS, code);
    }

    public static String reason(Integer code) {
        return code == null ? null : label(REASON, code);
    }

    public static String event(int code) {
        return label(EVENT, code);
    }

    /** Rotulo de status recebido em filtro -> codigo; desconhecido -> 400. */
    public static short statusCodeOf(String label) {
        return STATUS.entrySet().stream().filter(e -> e.getValue().equals(label)).map(e -> e.getKey().shortValue()).findFirst()
                .orElseThrow(() -> ApiException.invalid("status"));
    }

    private static String label(Map<Integer, String> m, int code) {
        String r = m.get(code);
        if (r == null) {
            throw new ApiException(ApiError.INTERNAL_ERROR, "rotulo_desconhecido");
        }
        return r;
    }
}
