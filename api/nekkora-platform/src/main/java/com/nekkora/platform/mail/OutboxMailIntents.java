package com.nekkora.platform.mail;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.web.RequestContext;
import com.nekkora.platform.auth.crypto.IdentityHmac;
import com.nekkora.platform.auth.crypto.SecretCipher;

import tools.jackson.databind.json.JsonMapper;

/**
 * Intencao DURAVEL (transporte real): grava o trabalho em {@code mail_outbox} (021) com o cliente da transacao do ato.
 * Destinatario e tokens vao CIFRADOS juntos ({@link SecretCipher#encryptForMail}, AAD = job_id); em claro, so o HMAC do
 * destinatario e a chave do evento. {@code ON CONFLICT (event_key) DO NOTHING}: o mesmo evento nunca vira dois trabalhos.
 */
public final class OutboxMailIntents implements MailIntents {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxMailIntents.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OrgTransactions tx;
    private final SecretCipher cipher;
    private final IdentityHmac hmac;
    private final Function<MailSender.Kind, Duration> deadline;

    public OutboxMailIntents(OrgTransactions tx, SecretCipher cipher, IdentityHmac hmac, Function<MailSender.Kind, Duration> deadline) {
        this.tx = tx;
        this.cipher = cipher;
        this.hmac = hmac;
        this.deadline = deadline;
    }

    @Override
    public void record(JdbcClient j, Intent i) {
        insert(j, i, i.token(), null, null);
    }

    @Override
    public void recordAlone(Intent i) {
        tx.withoutContext(j -> {
            insert(j, i, i.token(), null, null);
            return null;
        });
    }

    /** Aviso da recuperacao emergencial (016): dois tokens e a tentativa de origem, para o desfecho assincrono. */
    void recordEmergency(MailSender.Kind kind, UUID organization, String recipient, String confirmToken, String contestToken, UUID attempt) {
        Intent i = new Intent(kind, organization, recipient, null, "tentativa:" + attempt);
        tx.withoutContext(j -> {
            insert(j, i, confirmToken, contestToken, attempt);
            return null;
        });
    }

    private void insert(JdbcClient j, Intent i, String token, String contest, UUID notice) {
        UUID job = UUID.randomUUID();
        UUID correlation = correlation();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("to", i.recipient());
        p.put("token", token);
        p.put("contest", contest);
        byte[] plain = JSON.writeValueAsBytes(p);
        SecretCipher.Encrypted enc;
        try {
            enc = cipher.encryptForMail(plain, job);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        int n = j.sql("""
                INSERT INTO public.mail_outbox (job_id, kind, target_organization_id, recipient_hmac, event_key, notice_ref, payload_ciphertext,
                                                payload_nonce, key_id, state, next_attempt_at, deadline_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, clock_timestamp(), clock_timestamp() + ?::interval, ?)
                ON CONFLICT (event_key) DO NOTHING
                """).param(job).param(i.kind().name()).param(i.organization()).param(hmac.global(i.recipient())).param(i.fullKey()).param(notice)
                .param(enc.ciphertext()).param(enc.nonce()).param(enc.keyId()).param(deadline.apply(i.kind()).toSeconds() + " seconds").param(correlation)
                .update();
        if (n == 1) {
            LOG.info("evento=mail.intencao_gravada job={} tipo={} correlacao={}", job, i.kind(), correlation);
        } else {
            LOG.info("evento=mail.intencao_repetida tipo={} correlacao={}", i.kind(), correlation);
        }
    }

    private static UUID correlation() {
        try {
            return UUID.fromString(RequestContext.correlation());
        } catch (IllegalArgumentException | NullPointerException e) {
            return UUID.randomUUID();
        }
    }
}
