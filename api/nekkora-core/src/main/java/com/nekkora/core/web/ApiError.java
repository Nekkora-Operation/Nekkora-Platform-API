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
    /**
     * Autorizacao local: o escopo ja tem uma autorizacao VIGENTE (PLATFORM-AGENT-V2-003, T12; 033): mesma autorizacao do Worker
     * no workspace e mesma versao (pacote) ou mesma automacao (monitoramento). Nada foi gravado, estendido nem revogado; a
     * Web rele a lista e mostra a vigente. Codigo CONHECIDO (conflict) com o motivo em "reason".
     */
    LOCAL_RUN_AUTHORIZATION_EXISTS(HttpStatus.CONFLICT, "conflict", "local_run_authorization_exists"),
    /**
     * Organizacao: a identidade ja criou, pelo cadastro, uma organizacao com esse nome (PLATFORM-AGENT-V2-003, T10; 034;
     * espacos nas pontas, espacos internos repetidos e maiusculas/minusculas nao distinguem). Nada foi criado. Codigo
     * CONHECIDO (conflict) com o motivo em "reason".
     */
    ORGANIZATION_NAME_TAKEN(HttpStatus.CONFLICT, "conflict", "organization_name_taken"),
    /**
     * Worker: a organizacao atingiu o limite de Workers cadastrados (WORKERS_REGISTERED; zero ate a ativacao)
     * (PLATFORM-AGENT-V2-002, T07). Codigo CONHECIDO (conflict) com o motivo em "reason". Nada foi gravado.
     */
    WORKER_LIMIT_REACHED(HttpStatus.CONFLICT, "conflict", "worker_limit_reached"),
    /**
     * Canal do Agent, resgate com intencao declarada (PLATFORM-AGENT-V2-002, T06): codigo de PAREAMENTO enviado como renovacao
     * de credencial. Nada consumido, nada alterado. Codigo CONHECIDO (conflict) com o motivo em "reason".
     */
    PAIRING_CODE_FOR_ROTATION(HttpStatus.CONFLICT, "conflict", "pairing_code_for_rotation"),
    /** Idem: codigo de RENOVACAO (rotacao) enviado como pareamento. Nada consumido, nada alterado. */
    ROTATION_CODE_FOR_PAIRING(HttpStatus.CONFLICT, "conflict", "rotation_code_for_pairing"),
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
