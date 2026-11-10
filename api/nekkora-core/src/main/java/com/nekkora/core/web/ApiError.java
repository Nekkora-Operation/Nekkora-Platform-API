package com.nekkora.core.web;

import org.springframework.http.HttpStatus;

/** Codigos de erro do contrato 8.7 (§5.2): o corpo e sempre {"error":"<codigo>"}, sem mensagem nem causa. */
public enum ApiError {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "invalid_request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "unauthenticated"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden"),
    /**
     * Ato sensivel sem confirmacao de identidade valida NESTA sessao (PLATFORM-V2-001, T01): o resto (sessao, organizacao,
     * permissao) ja foi conferido e nada foi feito. Confirme (TOTP ou, sem MFA, a senha) e repita o ato. Codigo CONHECIDO
     * (forbidden) com o motivo em "reason": o cliente que nao conhece o motivo ve a recusa comum, nunca um codigo desconhecido.
     */
    STEP_UP_REQUIRED(HttpStatus.FORBIDDEN, "forbidden", "step_up_required"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "not_found"),
    /** Estado atual impede o ato (convite pendente, papel ou concessao duplicada, destinataria ja cadastrada). */
    CONFLICT(HttpStatus.CONFLICT, "conflict"),
    /** Aceite de convite: a identidade ja e membro ativo da organizacao. */
    ALREADY_MEMBER(HttpStatus.CONFLICT, "already_member"),
    /** Aceite de convite: ha conta da organizacao com o e-mail, nao ligada a identidade (use a vinculacao). */
    ACCOUNT_EXISTS(HttpStatus.CONFLICT, "account_exists"),
    /** Aceite de convite: a conta ligada a identidade esta suspensa ou com vinculo nao retirado e inativo. */
    ACCOUNT_UNAVAILABLE(HttpStatus.CONFLICT, "account_unavailable"),
    /**
     * Workspace: ja existe outro com o mesmo nome (sem distincao de maiusculas nem espacos nas extremidades) na organizacao
     * (PLATFORM-V2-001, T03). Codigo CONHECIDO (conflict) com o motivo em "reason": o cliente que nao conhece o motivo ve a
     * recusa comum de conflito (nada foi feito), nunca um codigo desconhecido.
     */
    WORKSPACE_NAME_TAKEN(HttpStatus.CONFLICT, "conflict", "workspace_name_taken"),
    /**
     * Versao: a automacao ja tem um rascunho (PLATFORM-AGENT-V2-002, T09, no maximo um por automacao). Rota antiga de
     * criacao de versao (Web V1). Codigo CONHECIDO (conflict) com o motivo em "reason". Nada foi criado.
     */
    DRAFT_EXISTS(HttpStatus.CONFLICT, "conflict", "draft_exists"),
    /**
     * Versao: o rascunho da automacao nao e mais o esperado pelo envio (outra aba ou pessoa enviou outro pacote, ou ele foi
     * publicado) (T09). Nada foi sobrescrito; o cliente rele e mostra qual pacote permaneceu.
     */
    DRAFT_CHANGED(HttpStatus.CONFLICT, "conflict", "draft_changed"),
    /** Canal do Agent (contrato Platform-Agent 1.0, sec. 15): versao maior do contrato nao suportada. */
    UNSUPPORTED_CONTRACT(HttpStatus.BAD_REQUEST, "unsupported_contract"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "rate_limited"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable");

    private final HttpStatus status;
    private final String code;

    private final String reason;

    ApiError(HttpStatus status, String code) {
        this(status, code, null);
    }

    ApiError(HttpStatus status, String code, String reason) {
        this.status = status;
        this.code = code;
        this.reason = reason;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** Motivo publicado em "reason" (vocabulario fechado do contrato), ou {@code null}. */
    public String reason() {
        return reason;
    }

    /** Corpo JSON fixo (sem serializador: nao ha nada variavel). */
    public String body() {
        return reason == null ? "{\"error\":\"" + code + "\"}" : "{\"error\":\"" + code + "\",\"reason\":\"" + reason + "\"}";
    }
}
