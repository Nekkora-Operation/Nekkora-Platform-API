package com.nekkora.platform.mail;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.web.RequestContext;
import com.nekkora.platform.auth.crypto.IdentityHmac;
import com.nekkora.platform.auth.crypto.SecretCipher;

import tools.jackson.databind.json.JsonMapper;

/**
 * Porta {@link MailSender} do transporte real (missao 002, PR 5): {@link #send} NAO envia - grava um trabalho na fila
 * persistente {@code mail_outbox} (021), com destinatario e token CIFRADOS ({@link SecretCipher#encryptForMail}, AAD =
 * job_id) e so o HMAC do destinatario em claro. O {@link MailDispatcher} envia depois, com tentativas limitadas, prazo por
 * tipo e recuperacao apos reinicio. Chamado DEPOIS do commit da alteracao de seguranca (como as demais portas): a falha do
 * transporte, ou ate da gravacao na fila, nunca desfaz a alteracao.
 *
 * <p>Os avisos da recuperacao emergencial (016) tem persistencia e desfechos proprios (EmergencyNotices): vao direto ao
 * transporte, com ate 3 tentativas so para RETENTAVEL e espera curta (desenho §6.2), e o desfecho e mapeado para
 * {@link MailSender.Outcome}.
 *
 * <p>Log: job, tipo e correlacao. Nunca destinatario, token, link ou conteudo.
 */
public final class OutboxMailSender implements MailSender {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxMailSender.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int EMERGENCY_TRIES = 3;
    private static final Duration EMERGENCY_MAX_WAIT = Duration.ofSeconds(2);

    private final OrgTransactions tx;
    private final SecretCipher cipher;
    private final IdentityHmac hmac;
    private final MailTransport transport;
    private final MailTemplates templates;
    private final Function<Kind, Duration> deadline;

    /** {@code deadline}: prazo de cada tipo (mensagem com token: o prazo do token; aviso: 24 h). */
    public OutboxMailSender(OrgTransactions tx, SecretCipher cipher, IdentityHmac hmac, MailTransport transport, MailTemplates templates,
                            Function<Kind, Duration> deadline) {
        this.tx = tx;
        this.cipher = cipher;
        this.hmac = hmac;
        this.transport = transport;
        this.templates = templates;
        this.deadline = deadline;
    }

    @Override
    public void send(Kind kind, UUID organization, String recipient, String tokenOrNull) {
        if (kind == Kind.NOTICE_MFA_EMERGENCY_HOLDER || kind == Kind.NOTICE_MFA_EMERGENCY_ADMIN) {
            throw new IllegalArgumentException("aviso de recuperacao emergencial usa sendEmergencyNotice");
        }
        UUID job = UUID.randomUUID();
        UUID correlation = correlation();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("to", recipient);
        p.put("token", tokenOrNull);
        byte[] plain = JSON.writeValueAsBytes(p);
        SecretCipher.Encrypted enc;
        try {
            enc = cipher.encryptForMail(plain, job);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        long seconds = deadline.apply(kind).toSeconds();
        tx.withoutContext(j -> j.sql("""
                INSERT INTO public.mail_outbox (job_id, kind, target_organization_id, recipient_hmac, payload_ciphertext, payload_nonce, key_id,
                                                state, next_attempt_at, deadline_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, clock_timestamp(), clock_timestamp() + ?::interval, ?)
                """).param(job).param(kind.name()).param(organization).param(hmac.global(recipient)).param(enc.ciphertext()).param(enc.nonce())
                .param(enc.keyId()).param(seconds + " seconds").param(correlation).update());
        LOG.info("evento=mail.enfileirado job={} tipo={} correlacao={}", job, kind, correlation);
    }

    @Override
    public Outcome sendEmergencyNotice(Kind kind, UUID organization, String recipient, String confirmTokenOrNull, String contestTokenOrNull,
                                       UUID attemptId) {
        // EXECUTADA e informativo, sem token; HOLDER/ADMIN levam ate dois links
        MailTemplates.Rendered r = kind == Kind.NOTICE_MFA_EMERGENCY_EXECUTED ? templates.render(kind, organization, null)
                : templates.renderEmergency(kind, organization, confirmTokenOrNull, contestTokenOrNull);
        MailTransport.Message m = new MailTransport.Message(attemptId, recipient, r.subject(), r.body());
        for (int i = 1; ; i++) {
            MailTransport.Delivery d = transport.deliver(m);
            switch (d.result()) {
                case ACEITO:
                    return Outcome.ACEITO_PELO_TRANSPORTE;
                case RECUSADO:
                    return Outcome.RECUSADO_DEFINITIVO;
                case INCERTO:
                    return Outcome.INCERTO;
                case RETENTAVEL:
                    if (i >= EMERGENCY_TRIES) {
                        // desenho §6.1: tentativas esgotadas -> INCERTO (nunca reentregue automaticamente)
                        return Outcome.INCERTO;
                    }
                    pause(d.retryAfter() == null ? Duration.ofMillis(250L * i) : min(d.retryAfter(), EMERGENCY_MAX_WAIT));
                    break;
                default:
                    return Outcome.INCERTO;
            }
        }
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static void pause(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static UUID correlation() {
        try {
            return UUID.fromString(RequestContext.correlation());
        } catch (IllegalArgumentException | NullPointerException e) {
            return UUID.randomUUID();
        }
    }

    static Map<String, String> decode(byte[] plain) {
        Map<String, String> m = new LinkedHashMap<>();
        tools.jackson.databind.JsonNode n = JSON.readTree(new String(plain, StandardCharsets.UTF_8));
        m.put("to", n.path("to").asString(null));
        m.put("token", n.path("token").isNull() ? null : n.path("token").asString(null));
        return m;
    }
}
