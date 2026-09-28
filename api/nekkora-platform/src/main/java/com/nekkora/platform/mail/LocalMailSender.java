package com.nekkora.platform.mail;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implementacao local: registra so a intencao (tipo e organizacao), nunca destinatario, token nem link. */
public final class LocalMailSender implements MailSender {

    private static final Logger LOG = LoggerFactory.getLogger(LocalMailSender.class);

    @Override
    public void send(Kind kind, UUID organization, String recipient, String tokenOrNull) {
        LOG.info("evento=mail.intencao tipo={} org={} entrega=nenhuma(local)", kind, organization);
    }

    @Override
    public void sendEmergencyNotice(Kind kind, UUID organization, String recipient, String confirmTokenOrNull, String contestTokenOrNull) {
        LOG.info("evento=mail.intencao tipo={} org={} entrega=nenhuma(local)", kind, organization);
    }
}
