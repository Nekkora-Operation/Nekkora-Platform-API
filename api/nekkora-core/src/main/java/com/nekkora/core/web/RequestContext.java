package com.nekkora.core.web;

import org.slf4j.MDC;

/** Correlacao da requisicao corrente (gerada no servidor; nunca copiada de cabecalho do cliente). */
public final class RequestContext {

    public static final String MDC_CORRELATION = "correlacao";

    private RequestContext() {
    }

    public static String correlation() {
        String c = MDC.get(MDC_CORRELATION);
        return c == null ? "-" : c;
    }
}
