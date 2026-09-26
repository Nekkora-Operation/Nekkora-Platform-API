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

    enum Kind { PASSWORD_RECOVERY, NOTICE_RECOVERY_CODE_USED, NOTICE_MFA_RESET, NOTICE_PASSWORD_CHANGED }

    void send(Kind kind, UUID organization, String recipient, String tokenOrNull);
}
