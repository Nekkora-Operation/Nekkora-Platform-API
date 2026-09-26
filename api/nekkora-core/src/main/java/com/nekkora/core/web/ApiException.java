package com.nekkora.core.web;

/**
 * Falha com resposta generica do contrato 8.7. {@code motivoInterno} vai so para o log sanitizado (nunca para
 * o cliente) e nao pode conter dado sensivel nem distinguir existencia de conta. {@code retryAfterSegundos}
 * alimenta o cabecalho Retry-After (429/503).
 */
public class ApiException extends RuntimeException {

    private final ApiError apiError;
    private final String internalReason;
    private final Long retryAfterSeconds;

    public ApiException(ApiError apiError, String internalReason) {
        this(apiError, internalReason, null);
    }

    public ApiException(ApiError apiError, String internalReason, Long retryAfterSeconds) {
        super(apiError.code(), null, false, false);
        this.apiError = apiError;
        this.internalReason = internalReason;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ApiError apiError() {
        return apiError;
    }

    public String internalReason() {
        return internalReason;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static ApiException unauthenticated(String reason) {
        return new ApiException(ApiError.UNAUTHENTICATED, reason);
    }

    public static ApiException invalid(String reason) {
        return new ApiException(ApiError.INVALID_REQUEST, reason);
    }

    public static ApiException forbidden(String reason) {
        return new ApiException(ApiError.FORBIDDEN, reason);
    }
}
