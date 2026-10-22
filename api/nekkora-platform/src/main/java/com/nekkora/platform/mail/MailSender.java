package com.nekkora.platform.mail;

import java.util.UUID;

/**
 * Porta de e-mail (contrato, §6.1). Implementacoes aprovadas: local (so registra a intencao, sem token nem link)
 * e de teste (entrega em memoria). ENTREGA REAL e requisito antes de disponibilizar recuperacao, verificacao e
 * avisos de MFA em producao - gate de liberacao, nao item fechado pela v1.
 *
 * <p>Chamado DEPOIS do commit (nunca dentro da transacao). O token so existe em memoria ate aqui.
 */
public interface MailSender {

    enum Kind {
        PASSWORD_RECOVERY, NOTICE_RECOVERY_CODE_USED, NOTICE_MFA_RESET, NOTICE_PASSWORD_CHANGED,
        /** Reset administrativo de MFA de OUTRO ORG_ADMIN: aviso aos demais administradores (contrato MFA emergencial, 9). */
        NOTICE_MFA_RESET_ADMIN_TARGET,
        /** Recuperacao emergencial pre-aprovada: ao titular (CONFIRMAR e CONTESTAR). */
        NOTICE_MFA_EMERGENCY_HOLDER,
        /** Recuperacao emergencial pre-aprovada: aos demais ORG_ADMIN (so CONTESTAR). */
        NOTICE_MFA_EMERGENCY_ADMIN,
        /** Recuperacao emergencial executada (informativo, sem token). */
        NOTICE_MFA_EMERGENCY_EXECUTED,
        /** Cadastro web (Missao 1): confirmacao do e-mail da identidade, com o token de uso unico (sem organizacao). */
        IDENTITY_SIGNUP_CONFIRMATION,
        /** Cadastro web: aviso "ja existe uma conta com este e-mail" (sem token e sem link de cadastro). */
        IDENTITY_SIGNUP_ALREADY_REGISTERED,
        /** Vinculacao (contrato P3): aviso de que a conta da organizacao passou a entrar pela identidade (sem token). */
        NOTICE_IDENTITY_LINKED,
        /** Substituicao do fator da identidade concluida (DC-17.13, T1): aviso ao titular, sem token. */
        NOTICE_MFA_REPLACED
    }

    void send(Kind kind, UUID organization, String recipient, String tokenOrNull);

    /**
     * Desfecho da entrega de um aviso da recuperacao emergencial (revisao 4, item 10). O retorno desta porta NAO e
     * recebimento nem confirmacao:
     * <ul>
     * <li>{@code ENFILEIRADO}: entregue a uma fila ou so registrada a intencao (o que o {@link LocalMailSender} faz);</li>
     * <li>{@code ACEITO_PELO_TRANSPORTE}: o transporte real aceitou a mensagem (por exemplo, resposta positiva do
     * servidor SMTP); nao prova recebimento;</li>
     * <li>{@code RECUSADO_DEFINITIVO}: a fila ou o transporte recusou com certeza de nao entrega;</li>
     * <li>{@code INCERTO}: tempo esgotado, excecao ou queda depois de entregar - pode ter saido.</li>
     * </ul>
     */
    enum Outcome { ENFILEIRADO, ACEITO_PELO_TRANSPORTE, RECUSADO_DEFINITIVO, INCERTO }

    /**
     * Aviso da recuperacao emergencial (contrato 6.1): ate dois tokens, cada um com a sua finalidade. Os links levam
     * o token no FRAGMENTO da pagina estatica; abrir o link nao confirma nem contesta. {@code attemptId} identifica a
     * tentativa para correlacionar o resultado do transporte; ele NAO garante deduplicacao pelo SMTP.
     */
    Outcome sendEmergencyNotice(Kind kind, UUID organization, String recipient, String confirmTokenOrNull, String contestTokenOrNull,
                                UUID attemptId);

    /**
     * {@code true} so no transporte de HOMOLOGACAO (caixa local, sem envio externo): todo desfecho dele e gravado no
     * banco com categoria de simulacao (ex.: ACEITE_SIMULADO), para nunca se confundir com aceite de transporte real.
     */
    default boolean simulated() {
        return false;
    }
}
