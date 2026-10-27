package com.nekkora.core.web;

import org.springframework.http.HttpStatus;

/** Codigos de erro do contrato 8.7 (§5.2): o corpo e sempre {"error":"<codigo>"}, sem mensagem nem causa. */
public enum ApiError {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "invalid_request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "unauthenticated"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "not_found"),
    /** Estado atual impede o ato (convite pendente, papel ou concessao duplicada, destinataria ja cadastrada). */
    CONFLICT(HttpStatus.CONFLICT, "conflict"),
    /** Aceite de convite: a identidade ja e membro ativo da organizacao. */
    ALREADY_MEMBER(HttpStatus.CONFLICT, "already_member"),
    /** Aceite de convite: ha conta da organizacao com o e-mail, nao ligada a identidade (use a vinculacao). */
    ACCOUNT_EXISTS(HttpStatus.CONFLICT, "account_exists"),
    /** Aceite de convite: a conta ligada a identidade esta suspensa ou com vinculo nao retirado e inativo. */
    ACCOUNT_UNAVAILABLE(HttpStatus.CONFLICT, "account_unavailable"),
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
