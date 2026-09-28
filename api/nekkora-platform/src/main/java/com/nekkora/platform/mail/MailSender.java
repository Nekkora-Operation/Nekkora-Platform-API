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
        NOTICE_MFA_EMERGENCY_EXECUTED
    }

    void send(Kind kind, UUID organization, String recipient, String tokenOrNull);

    /**
     * Aviso da recuperacao emergencial (contrato 6.1): ate dois tokens, cada um com a sua finalidade. Os links levam
     * o token no FRAGMENTO da pagina estatica; abrir o link nao confirma nem contesta.
     */
    void sendEmergencyNotice(Kind kind, UUID organization, String recipient, String confirmTokenOrNull, String contestTokenOrNull);
}
