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

    /** So a intencao: nunca aceite pelo transporte (a condicao de aprovacao do item 10 nunca e satisfeita com ela). */
    @Override
    public Outcome sendEmergencyNotice(Kind kind, UUID organization, String recipient, String confirmTokenOrNull, String contestTokenOrNull,
                                       UUID attemptId) {
        LOG.info("evento=mail.intencao tipo={} org={} entrega=nenhuma(local)", kind, organization);
        return Outcome.ENFILEIRADO;
    }
}
