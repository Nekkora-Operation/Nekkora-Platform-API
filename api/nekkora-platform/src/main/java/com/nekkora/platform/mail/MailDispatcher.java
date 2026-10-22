package com.nekkora.platform.mail;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.platform.auth.crypto.SecretCipher;

/**
 * Despacho da fila persistente {@code mail_outbox} (missao 002, PR 5; desenho de 2026-10-11, §6). Cada ciclo:
 * <ol>
 * <li><b>recuperacao apos reinicio</b>: trabalho ENVIANDO com posse vencida -> INCERTO (o envio pode ter saido antes da
 * queda; nunca reenviado automaticamente);</li>
 * <li><b>prazo</b>: trabalho PENDENTE com prazo vencido -> EXPIRADO, sem envio (um token vencido nao serve);</li>
 * <li><b>reivindicacao</b> de um lote com {@code FOR UPDATE SKIP LOCKED} (varias instancias nunca pegam o mesmo trabalho):
 * PENDENTE -> ENVIANDO, tentativa + 1, posse por {@link #LEASE};</li>
 * <li><b>envio</b> fora de transacao e <b>desfecho</b> preso a tentativa reivindicada (estado ENVIANDO e o mesmo numero de
 * tentativas): ACEITO, RECUSADO e INCERTO sao terminais e APAGAM o payload cifrado; RETENTAVEL volta a PENDENTE com espera
 * crescente (ou {@code Retry-After}), ate {@link #MAX_ATTEMPTS}; esgotadas, INCERTO (desenho §6.1).</li>
 * </ol>
 * Payload que nao decifra (chave retirada): RECUSADO, sem envio. Log: job, tipo, tentativa, desfecho, status e correlacao.
 *
 * <p><b>Ritmo.</b> No maximo {@code perMinute} envios por minuto POR INSTANCIA (padrao 25), abaixo do limite de 30
 * mensagens por minuto por caixa do Exchange Online (documentacao vigente de limites, 2026-10-19). Envio um a um (uma
 * thread), abaixo do limite de 4 pedidos concorrentes por aplicativo e caixa do Graph para o Outlook. Com mais de uma
 * instancia, a soma dos ritmos tem de caber no limite (configuracao); o excedente vira 429 e volta para a fila.
 */
public final class MailDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(MailDispatcher.class);
    static final int MAX_ATTEMPTS = 5;
    static final Duration LEASE = Duration.ofMinutes(2);
    private static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofMinutes(30));
    private static final int BATCH = 20;

    static final short PENDENTE = 1;
    static final short ENVIANDO = 2;
    static final short ACEITO = 3;
    static final short RECUSADO = 4;
    static final short INCERTO = 5;
    static final short EXPIRADO = 6;

    private final OrgTransactions tx;
    private final SecretCipher cipher;
    private final MailTransport transport;
    private final MailTemplates templates;
    private final int perMinute;
    private final java.util.ArrayDeque<Long> window = new java.util.ArrayDeque<>();

    public MailDispatcher(OrgTransactions tx, SecretCipher cipher, MailTransport transport, MailTemplates templates) {
        this(tx, cipher, transport, templates, 25);
    }

    public MailDispatcher(OrgTransactions tx, SecretCipher cipher, MailTransport transport, MailTemplates templates, int perMinute) {
        if (perMinute < 1) {
            throw new IllegalArgumentException("nekkora.mail.despacho.por-minuto deve ser positivo");
        }
        this.tx = tx;
        this.cipher = cipher;
        this.transport = transport;
        this.templates = templates;
        this.perMinute = perMinute;
    }

    /** Reserva ate {@code wanted} envios dentro da janela de 60 s; devolve quantos couberam. */
    private synchronized int reserve(int wanted) {
        long now = System.nanoTime();
        while (!window.isEmpty() && now - window.peekFirst() >= 60_000_000_000L) {
            window.pollFirst();
        }
        int granted = Math.max(0, Math.min(wanted, perMinute - window.size()));
        for (int i = 0; i < granted; i++) {
            window.addLast(now);
        }
        return granted;
    }

    /** Devolve reservas nao usadas (o lote reivindicado foi menor que o reservado). */
    private synchronized void release(int unused) {
        for (int i = 0; i < unused && !window.isEmpty(); i++) {
            window.pollLast();
        }
    }

    private record Claimed(UUID job, MailSender.Kind kind, UUID org, SecretCipher.Encrypted payload, int attempts, UUID correlation) {
    }

    /** Um ciclo. Devolve quantos trabalhos chegaram a um desfecho de envio neste ciclo. */
    public int dispatchPending() {
        int lost = tx.withoutContext(j -> j.sql("""
                UPDATE public.mail_outbox SET state = 5, lease_until = NULL, finished_at = clock_timestamp(),
                       payload_ciphertext = NULL, payload_nonce = NULL, key_id = NULL
                 WHERE state = 2 AND lease_until < clock_timestamp()
                """).update());
        if (lost > 0) {
            LOG.warn("evento=mail.posse_vencida quantidade={} desfecho=INCERTO alerta=sim", lost);
        }
        int expired = tx.withoutContext(j -> j.sql("""
                UPDATE public.mail_outbox SET state = 6, finished_at = clock_timestamp(), payload_ciphertext = NULL, payload_nonce = NULL, key_id = NULL
                 WHERE state = 1 AND deadline_at <= clock_timestamp()
                """).update());
        if (expired > 0) {
            LOG.warn("evento=mail.prazo_vencido quantidade={} desfecho=EXPIRADO", expired);
        }
        int budget = reserve(BATCH);
        if (budget == 0) {
            return 0;
        }
        List<Claimed> batch = tx.withoutContext(j -> j.sql("""
                UPDATE public.mail_outbox o SET state = 2, attempts = o.attempts + 1, lease_until = clock_timestamp() + ?::interval
                  FROM (SELECT job_id FROM public.mail_outbox
                         WHERE state = 1 AND next_attempt_at <= clock_timestamp() AND deadline_at > clock_timestamp()
                         ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED) c
                 WHERE o.job_id = c.job_id
                RETURNING o.job_id, o.kind, o.target_organization_id, o.payload_ciphertext, o.payload_nonce, o.key_id, o.attempts, o.correlation_id
                """).param(LEASE.toSeconds() + " seconds").param(budget)
                .query((rs, n) -> new Claimed(rs.getObject(1, UUID.class), MailSender.Kind.valueOf(rs.getString(2)), rs.getObject(3, UUID.class),
                        new SecretCipher.Encrypted(rs.getBytes(4), rs.getBytes(5), rs.getString(6)), rs.getInt(7), rs.getObject(8, UUID.class)))
                .list());
        release(budget - batch.size());
        int done = 0;
        for (Claimed c : batch) {
            deliver(c);
            done++;
        }
        return done;
    }

    private void deliver(Claimed c) {
        MailTransport.Delivery d;
        byte[] plain = null;
        try {
            plain = cipher.decryptForMail(c.payload(), c.job());
            Map<String, String> p = OutboxMailSender.decode(plain);
            MailTemplates.Rendered r = templates.render(c.kind(), c.org(), p.get("token"));
            d = transport.deliver(new MailTransport.Message(c.correlation(), p.get("to"), r.subject(), r.body()));
        } catch (RuntimeException e) {
            // payload que nao decifra ou mensagem que nao monta: nada foi enviado e repetir nao muda o resultado
            LOG.warn("evento=mail.payload_invalido job={} tipo={} causa={}", c.job(), c.kind(), e.getClass().getSimpleName());
            d = new MailTransport.Delivery(MailTransport.Result.RECUSADO, null, null);
        } finally {
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
        record(c, d);
    }

    private void record(Claimed c, MailTransport.Delivery d) {
        MailTransport.Result result = d.result();
        if (result == MailTransport.Result.RETENTAVEL && c.attempts() >= MAX_ATTEMPTS) {
            result = MailTransport.Result.INCERTO;
        }
        int rows;
        if (result == MailTransport.Result.RETENTAVEL) {
            Duration wait = BACKOFF.get(Math.min(c.attempts(), BACKOFF.size()) - 1);
            if (d.retryAfter() != null && d.retryAfter().compareTo(wait) > 0) {
                wait = d.retryAfter();
            }
            Duration w = wait;
            rows = tx.withoutContext(j -> j.sql("""
                    UPDATE public.mail_outbox SET state = 1, lease_until = NULL, last_status = ?, next_attempt_at = clock_timestamp() + ?::interval
                     WHERE job_id = ? AND state = 2 AND attempts = ?
                    """).param(d.status()).param(w.toSeconds() + " seconds").param(c.job()).param(c.attempts()).update());
        } else {
            short state = switch (result) {
                case ACEITO -> ACEITO;
                case RECUSADO -> RECUSADO;
                default -> INCERTO;
            };
            rows = tx.withoutContext(j -> j.sql("""
                    UPDATE public.mail_outbox SET state = ?, lease_until = NULL, last_status = ?, finished_at = clock_timestamp(),
                           payload_ciphertext = NULL, payload_nonce = NULL, key_id = NULL
                     WHERE job_id = ? AND state = 2 AND attempts = ?
                    """).param(state).param(d.status()).param(c.job()).param(c.attempts()).update());
        }
        if (rows != 1) {
            // a posse venceu durante o envio e outro ciclo ja levou o trabalho a INCERTO: o desfecho deste envio fica so no log
            LOG.warn("evento=mail.desfecho_sem_posse job={} tipo={} tentativa={} desfecho={} alerta=sim", c.job(), c.kind(), c.attempts(), result);
            return;
        }
        LOG.info("evento=mail.desfecho job={} tipo={} tentativa={} desfecho={} status={} correlacao={}", c.job(), c.kind(), c.attempts(), result,
                d.status(), c.correlation());
    }
}
