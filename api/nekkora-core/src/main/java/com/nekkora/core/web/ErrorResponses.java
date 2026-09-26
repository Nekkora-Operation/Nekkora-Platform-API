package com.nekkora.core.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;

/** Escreve a resposta generica diretamente (usado por filtros, antes do DispatcherServlet). */
public final class ErrorResponses {

    private ErrorResponses() {
    }

    public static void writeError(HttpServletResponse res, ApiError apiError, Long retryAfterSeconds) throws IOException {
        if (res.isCommitted()) {
            return;
        }
        res.resetBuffer();
        res.setStatus(apiError.status().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        if (retryAfterSeconds != null) {
            res.setHeader("Retry-After", Long.toString(Math.max(1, retryAfterSeconds)));
        }
        res.getWriter().write(apiError.body());
        res.flushBuffer();
    }
}
