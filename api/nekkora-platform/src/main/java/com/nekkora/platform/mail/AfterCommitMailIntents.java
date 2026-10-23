package com.nekkora.platform.mail;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Intencao SEM persistencia (transportes local e de homologacao, testes): o envio e agendado para DEPOIS do commit da
 * transacao do ato, como antes da fila. Mensagens do cadastro e da recuperacao de senha da identidade saem fora do
 * caminho da resposta ({@link Queue}, a fila em memoria do cadastro), as demais na hora. Falha do transporte: so log -
 * nunca desfaz o ato nem muda a resposta.
 */
public final class AfterCommitMailIntents implements MailIntents {

    private static final Logger LOG = LoggerFactory.getLogger(AfterCommitMailIntents.class);

    /** Envio fora do caminho da resposta (a fila do cadastro). */
    @FunctionalInterface
    public interface Queue {
        void enqueue(MailSender.Kind kind, String recipient, String token);
    }

    private final MailSender mail;
    private final Queue queue;

    public AfterCommitMailIntents(MailSender mail, Queue queue) {
        this.mail = mail;
        this.queue = queue;
    }

    @Override
    public void record(JdbcClient j, Intent i) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("intencao de e-mail fora de transacao");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deliver(i);
            }
        });
    }

    @Override
    public void recordAlone(Intent i) {
        deliver(i);
    }

    private void deliver(Intent i) {
        try {
            if (async(i.kind(), i.organization())) {
                queue.enqueue(i.kind(), i.recipient(), i.token());
            } else {
                mail.send(i.kind(), i.organization(), i.recipient(), i.token());
            }
        } catch (RuntimeException e) {
            LOG.warn("evento=mail.envio_direto_falhou tipo={} causa={}", i.kind(), e.getClass().getSimpleName());
        }
    }

    private static boolean async(MailSender.Kind kind, UUID organization) {
        return kind == MailSender.Kind.IDENTITY_SIGNUP_CONFIRMATION || kind == MailSender.Kind.IDENTITY_SIGNUP_ALREADY_REGISTERED
                || (kind == MailSender.Kind.PASSWORD_RECOVERY && organization == null);
    }
}
