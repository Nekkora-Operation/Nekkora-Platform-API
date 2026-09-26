package com.nekkora.core.web;

import org.springframework.http.HttpStatus;

/** Codigos de erro do contrato 8.7 (§5.2): o corpo e sempre {"error":"<codigo>"}, sem mensagem nem causa. */
public enum ApiError {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "invalid_request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "unauthenticated"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "not_found"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "rate_limited"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable");

    private final HttpStatus status;
    private final String code;

    ApiError(HttpStatus status, String code) {
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** Corpo JSON fixo (sem serializador: nao ha nada variavel). */
    public String body() {
        return "{\"error\":\"" + code + "\"}";
    }
}
