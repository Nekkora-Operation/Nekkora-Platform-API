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
    /**
     * Desvinculo pela maquina (PLATFORM-AGENT-V2-004, T13): o vinculo DESTA credencial ja foi encerrado por outra operacao de
     * desvinculo da propria maquina. Nada mudou agora. So para quem prova o segredo daquela credencial.
     */
    LINK_ENDED_BY_MACHINE(HttpStatus.CONFLICT, "conflict", "link_ended_by_machine"),
    /** Idem: o vinculo desta credencial ja foi encerrado pela administracao ("Revogar vinculo"). Nada mudou agora. */
    LINK_ENDED_BY_ADMINISTRATION(HttpStatus.CONFLICT, "conflict", "link_ended_by_administration"),
    /**
     * Execucao remota (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01a): o recurso esta DESLIGADO neste servidor
     * (NEKKORA_EXECUCAO_REMOTA). Nada criado, nada consumido; o uso local do aplicativo nao depende disto.
     */
    REMOTE_EXECUTION_UNAVAILABLE(HttpStatus.CONFLICT, "conflict", "remote_execution_unavailable"),
    /**
     * Idem: codigo de pareamento MARCADO para execucao remota resgatado por um aplicativo que nao declarou a habilitacao
     * (um Agent anterior, por exemplo). Nada consumido: a maquina nao fica habilitada sem a confirmacao feita nela.
     */
    REMOTE_CODE_WITHOUT_ENABLEMENT(HttpStatus.CONFLICT, "conflict", "remote_code_without_enablement"),
    /** Idem: a maquina pediu a habilitacao com um codigo de pareamento comum (nao marcado). Nada consumido. */
    CODE_NOT_FOR_REMOTE_EXECUTION(HttpStatus.CONFLICT, "conflict", "code_not_for_remote_execution"),
    /** Idem: a maquina desta credencial nao esta habilitada para execucao remota. */
    REMOTE_EXECUTION_NOT_ENABLED(HttpStatus.CONFLICT, "conflict", "remote_execution_not_enabled"),
    /** Idem (T01b): a credencial ainda esta longe do vencimento; a renovacao so e aceita dentro da janela. */
    CREDENTIAL_RENEWAL_NOT_DUE(HttpStatus.CONFLICT, "conflict", "credential_renewal_not_due"),
    /** Dependencias declaradas (T01c): o texto tem linha que nao se representa como nome==versao; nada foi gravado. */
    RUNTIME_REQUIREMENTS_UNRESOLVED(HttpStatus.CONFLICT, "conflict", "runtime_requirements_unresolved"),
    /** Idem: outra pessoa declarou depois da revisao que esta pessoa viu; nada foi gravado. */
    RUNTIME_REQUIREMENTS_CHANGED(HttpStatus.CONFLICT, "conflict", "runtime_requirements_changed"),
    // Solicitacao de execucao remota (T02): cada exigencia que falta tem o seu motivo, para a tela dizer o que falta.
    /** Nao ha autorizacao de execucao desta versao para o Worker da maquina. */
    REMOTE_AUTHORIZATION_MISSING(HttpStatus.CONFLICT, "conflict", "remote_authorization_missing"),
    REMOTE_AUTHORIZATION_REVOKED(HttpStatus.CONFLICT, "conflict", "remote_authorization_revoked"),
    REMOTE_AUTHORIZATION_EXPIRED(HttpStatus.CONFLICT, "conflict", "remote_authorization_expired"),
    /** A maquina esta vinculada, mas nao foi habilitada para execucao remota. */
    REMOTE_MACHINE_NOT_ENABLED(HttpStatus.CONFLICT, "conflict", "remote_machine_not_enabled"),
    REMOTE_MACHINE_PAUSED(HttpStatus.CONFLICT, "conflict", "remote_machine_paused"),
    /** O aplicativo da maquina nao executa solicitacoes da Platform. */
    REMOTE_MACHINE_INCOMPATIBLE(HttpStatus.CONFLICT, "conflict", "remote_machine_incompatible"),
    /** A maquina ja tem uma tentativa nao terminada (a reserva e atomica). */
    REMOTE_MACHINE_BUSY(HttpStatus.CONFLICT, "conflict", "remote_machine_busy"),
    /** A ultima tentativa desta maquina foi encerrada por falta de comunicacao e ela ainda nao voltou: nao se sabe se esta livre. */
    REMOTE_MACHINE_UNCONFIRMED(HttpStatus.CONFLICT, "conflict", "remote_machine_unconfirmed"),
    /** 058: com resultado nao confirmado, a repeticao fica bloqueada ate a reconciliacao (pela maquina, ou declarada por um ORG_ADMIN). */
    REMOTE_PREVIOUS_RESULT_UNCONFIRMED(HttpStatus.CONFLICT, "conflict", "remote_previous_result_unconfirmed"),
    REMOTE_ATTEMPT_NOT_UNCONFIRMED(HttpStatus.CONFLICT, "conflict", "remote_attempt_not_unconfirmed"),
    /** 060: o processo principal da ultima tentativa desta maquina terminou, mas ha processos da automacao ainda ativos nela. */
    REMOTE_MACHINE_FINISHING(HttpStatus.CONFLICT, "conflict", "remote_machine_finishing"),
    /** 060: a ultima tentativa terminou, mas os processos dela ainda nao foram confirmados ausentes: a repeticao fica bloqueada. */
    REMOTE_PREVIOUS_PROCESSES_PENDING(HttpStatus.CONFLICT, "conflict", "remote_previous_processes_pending"),
    REMOTE_ATTEMPT_ALREADY_RECONCILED(HttpStatus.CONFLICT, "conflict", "remote_attempt_already_reconciled"),
    /** PLATFORM-LICENCIAMENTO-001 (062): a licenca ja esta vinculada a uma automacao (uma licenca atende uma automacao por vez). */
    LICENSE_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "conflict", "license_already_assigned"),
    /** 062: a automacao ja consome uma licenca. */
    AUTOMATION_ALREADY_LICENSED(HttpStatus.CONFLICT, "conflict", "automation_already_licensed"),
    /** 062: a licenca nao esta vigente (vencida, ainda nao iniciada ou encerrada). */
    LICENSE_NOT_VALID(HttpStatus.CONFLICT, "conflict", "license_not_valid"),
    /** 062: a automacao esta arquivada; uma licenca nao e vinculada a uma automacao que nao executa. */
    LICENSE_AUTOMATION_ARCHIVED(HttpStatus.CONFLICT, "conflict", "license_automation_archived"),
    /** 063 (atos do operador): a licenca ja foi encerrada. */
    LICENSE_ENDED(HttpStatus.CONFLICT, "conflict", "license_ended"),
    /** 063: o periodo pedido comeca antes do fim do ultimo periodo da licenca. */
    LICENSE_PERIOD_OVERLAPS(HttpStatus.CONFLICT, "conflict", "license_period_overlaps"),
    /** 063: o periodo pedido ja terminou. */
    LICENSE_PERIOD_ALREADY_ENDED(HttpStatus.CONFLICT, "conflict", "license_period_already_ended"),
    /** 063: a organizacao ja esta no modelo pedido. */
    LICENSE_MODEL_UNCHANGED(HttpStatus.CONFLICT, "conflict", "license_model_unchanged"),
    /** 063: o conjunto de automacoes impedidas ou cobertas mudou desde a previa confirmada; refazer a previa. */
    LICENSE_PREVIEW_CHANGED(HttpStatus.CONFLICT, "conflict", "license_preview_changed"),
    /** 066 (capacidade tecnica): a organizacao ja tem o limite de execucoes em andamento; nao e recusa de licenca. */
    CAPACITY_CONCURRENT_LIMIT_REACHED(HttpStatus.CONFLICT, "conflict", "capacity_concurrent_limit_reached"),
    /** 066: a organizacao ja tem o limite de execucoes pendentes; nao e recusa de licenca. */
    CAPACITY_PENDING_LIMIT_REACHED(HttpStatus.CONFLICT, "conflict", "capacity_pending_limit_reached"),
    /** 065 (transferencia): a licenca nao esta vinculada a nenhuma automacao; nao ha de onde transferir. */
    LICENSE_NOT_ASSIGNED(HttpStatus.CONFLICT, "conflict", "license_not_assigned"),
    /** 065: a automacao de destino e a mesma de origem. */
    LICENSE_TRANSFER_SAME_AUTOMATION(HttpStatus.CONFLICT, "conflict", "license_transfer_same_automation"),
    /** 065: a automacao de origem tem trabalho em andamento; precisa terminar ou ter o cancelamento confirmado antes. */
    LICENSE_TRANSFER_WORK_IN_PROGRESS(HttpStatus.CONFLICT, "conflict", "license_transfer_work_in_progress"),
    /** 065: o solicitante informado nao administra a organizacao neste instante. */
    LICENSE_TRANSFER_REQUESTER_NOT_ADMIN(HttpStatus.CONFLICT, "conflict", "license_transfer_requester_not_admin"),
    /** 064 (conferencia nos fluxos): a organizacao exige licenca e a automacao nao tem licenca vinculada; nada foi iniciado. */
    LICENSE_REQUIRED(HttpStatus.CONFLICT, "conflict", "license_required"),
    /** 064: a licenca vinculada a automacao esta fora de periodo (vencida, a espera de renovacao); nada foi iniciado. */
    LICENSE_EXPIRED(HttpStatus.CONFLICT, "conflict", "license_expired"),
    /** 064, canal da maquina: registro recusado, a automacao nao tem licenca (403: o Agent anterior trata 409 como registro divergente). */
    RUN_LICENSE_REQUIRED(HttpStatus.FORBIDDEN, "forbidden", "license_required"),
    /** 064, canal da maquina: registro recusado, a licenca da automacao estava vencida no inicio informado. */
    RUN_LICENSE_EXPIRED(HttpStatus.FORBIDDEN, "forbidden", "license_expired"),
    /** 064, canal da maquina: registro recusado, a autorizacao usada nao valia no inicio informado. */
    RUN_NOT_COVERED(HttpStatus.FORBIDDEN, "forbidden", "run_not_covered"),
    REMOTE_REQUIREMENTS_NOT_DECLARED(HttpStatus.CONFLICT, "conflict", "remote_requirements_not_declared"),
    REMOTE_REQUIREMENTS_CONFLICT(HttpStatus.CONFLICT, "conflict", "remote_requirements_conflict"),
    /** O pacote da versao nao e um pacote Python externo: a execucao remota nao o aceita (o formato Studio fica de fora). */
    REMOTE_PACKAGE_NOT_SUPPORTED(HttpStatus.CONFLICT, "conflict", "remote_package_not_supported"),
    REMOTE_PACKAGE_NOT_REGISTERED(HttpStatus.CONFLICT, "conflict", "remote_package_not_registered"),
    /** Ninguem escolheu ainda o ambiente Python desta automacao nesta maquina. */
    REMOTE_PYTHON_NOT_SELECTED(HttpStatus.CONFLICT, "conflict", "remote_python_not_selected"),
    /** O ambiente Python indicado (ou o escolhido antes) nao esta no inventario atual da maquina. */
    REMOTE_PYTHON_NOT_IN_INVENTORY(HttpStatus.CONFLICT, "conflict", "remote_python_not_in_inventory"),
    /** Verificacao sob demanda: ja houve um pedido para esta maquina e automacao ha menos de um minuto. Nada foi criado. */
    REMOTE_DEPENDENCY_CHECK_TOO_SOON(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "remote_dependency_check_too_soon"),
    /** Verificacao sob demanda: o pedido ja foi respondido com outro resultado, ou o prazo da resposta venceu. */
    REMOTE_DEPENDENCY_CHECK_CLOSED(HttpStatus.CONFLICT, "conflict", "remote_dependency_check_closed"),
    /** Repetir em outra maquina: a tentativa anterior ainda nao terminou. */
    REMOTE_ATTEMPT_NOT_FINISHED(HttpStatus.CONFLICT, "conflict", "remote_attempt_not_finished"),
    /** Cancelar: a tentativa ja terminou. */
    REMOTE_ATTEMPT_ALREADY_FINISHED(HttpStatus.CONFLICT, "conflict", "remote_attempt_already_finished"),
    /** Repetir: o cancelamento desta solicitacao ja foi pedido. */
    REMOTE_EXECUTION_CANCEL_REQUESTED(HttpStatus.CONFLICT, "conflict", "remote_execution_cancel_requested"),
    /** Canal da maquina: o token ou a epoca da atribuicao nao sao os vigentes; nada foi alterado. */
    REMOTE_ATTEMPT_STALE(HttpStatus.CONFLICT, "conflict", "remote_attempt_stale"),
    /** Canal da maquina: a mudanca de estado pedida nao e permitida a partir do estado atual. */
    REMOTE_ATTEMPT_TRANSITION(HttpStatus.CONFLICT, "conflict", "remote_attempt_transition"),
    /** Canal da maquina: os bytes do pacote da tentativa nao estao no armazenamento, ou nao conferem com o que foi publicado. */
    REMOTE_PACKAGE_UNAVAILABLE(HttpStatus.CONFLICT, "conflict", "remote_package_unavailable"),
    /** Canal da maquina: o numero do resultado ja foi usado com outro arquivo, ou o envio desta tentativa ja foi fechado. */
    REMOTE_RESULT_CONFLICT(HttpStatus.CONFLICT, "conflict", "remote_result_conflict"),
    /** Canal da maquina: o arquivo faria a tentativa passar do limite total de resultados. */
    REMOTE_RESULT_LIMIT(HttpStatus.CONFLICT, "conflict", "remote_result_limit"),
    /** Canal da maquina: lote de registros com buraco na sequencia, linha repetida com outro conteudo, ou envio ja fechado. */
    REMOTE_LOG_CONFLICT(HttpStatus.CONFLICT, "conflict", "remote_log_conflict"),
    /** Canal da maquina: o lote faria a tentativa passar do limite total de registros. */
    REMOTE_LOG_LIMIT(HttpStatus.CONFLICT, "conflict", "remote_log_limit"),
    /** Os bytes do resultado nao estao mais no armazenamento (removidos pela retencao, ausentes ou que nao conferem). */
    REMOTE_RESULT_UNAVAILABLE(HttpStatus.CONFLICT, "conflict", "remote_result_unavailable"),
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
