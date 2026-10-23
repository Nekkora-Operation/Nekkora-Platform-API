package com.nekkora.platform.mail;

import java.time.Duration;
import java.util.UUID;

/**
 * Transporte de UMA mensagem ja montada (missao 002, PR 5). O desfecho segue o desenho aprovado (2026-10-11, §6.1):
 * <ul>
 * <li>{@link Result#ACEITO}: o provedor aceitou (no Graph, 202). Aceite NAO e entrega;</li>
 * <li>{@link Result#RECUSADO}: recusa definitiva (configuracao, permissao, destinatario): nunca repetir;</li>
 * <li>{@link Result#RETENTAVEL}: com certeza NAO saiu (429, 503, 504, conexao recusada antes do envio): pode repetir, com
 * {@code retryAfter} quando o provedor informa;</li>
 * <li>{@link Result#INCERTO}: pode ter saido (tempo esgotado depois do envio, conexao cortada): nunca repetir
 * automaticamente.</li>
 * </ul>
 */
public interface MailTransport {

    enum Result { ACEITO, RECUSADO, RETENTAVEL, INCERTO }

    /** {@code status}: status HTTP do provedor, quando houve resposta. */
    record Delivery(Result result, Integer status, Duration retryAfter) {
    }

    /** Mensagem montada. {@code correlation} vai so para o log; nunca o destinatario, o assunto ou o corpo. */
    record Message(UUID correlation, String recipient, String subject, String body) {
        @Override
        public String toString() {
            return "Message[correlation=" + correlation + "]";
        }
    }

    Delivery deliver(Message message);
}
