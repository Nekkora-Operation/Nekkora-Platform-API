package com.nekkora.platform.mail;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.platform.auth.crypto.SecretCipher;

/**
 * Despacho da fila persistente {@code mail_outbox} (missao 002, PR 5; desenho de 2026-10-11, §6). Cada ciclo:
 * <ol>
 * <li><b>recuperacao apos reinicio</b>: trabalho ENVIANDO com posse vencida -> INCERTO (o envio pode ter saido; nunca
 * reenviado automaticamente);</li>
 * <li><b>prazo</b>: trabalho PENDENTE com prazo vencido -> EXPIRADO, sem envio;</li>
 * <li>ate o orcamento do minuto, UM trabalho por vez: <b>reivindicacao</b> ({@code FOR UPDATE SKIP LOCKED}, varias
 * instancias nunca pegam o mesmo) -> montagem -> <b>reconferencia imediatamente antes do envio</b> (ainda ENVIANDO, mesma
 * tentativa, posse e prazo validos pelo relogio do banco; a posse e renovada no mesmo comando) -> envio fora de transacao
 * -> desfecho preso a tentativa. Sem posse ou com prazo vencido na reconferencia: NAO envia. Nenhum trabalho fica
 * reivindicado esperando a vez num lote: o que nao foi enviado continua PENDENTE para qualquer instancia. Posse perdida
 * antes do envio: o trabalho volta a PENDENTE (sabidamente nao enviado) com espera de 30 s e o ciclo termina. Trabalho que
 * chegou ao limite de tentativas nao e mais reivindicado (expira pelo prazo).</li>
 * </ol>
 * Terminais (ACEITO, RECUSADO, INCERTO, EXPIRADO) APAGAM o payload cifrado. RETENTAVEL volta a PENDENTE com espera
 * crescente ou o {@code Retry-After} do provedor, o que for MAIOR (o prazo informado e respeitado; se passar do prazo da
 * mensagem, ela expira); ate {@link #MAX_ATTEMPTS}; esgotadas, INCERTO (desenho §6.1). Aviso da recuperacao emergencial:
 * o desfecho terminal volta a tentativa de origem ({@code notice_ref}) pelo {@code emergencyResults}.
 *
 * <p><b>Ritmo.</b> No maximo {@code perMinute} envios por minuto POR INSTANCIA (padrao 25), abaixo do limite de 30
 * mensagens por minuto por caixa do Exchange Online; envio um a um, abaixo do limite de 4 pedidos concorrentes do Graph.
 *
 * <p><b>Retencao.</b> {@link #purgeFinished()} apaga, em lotes, trabalhos terminais encerrados ha mais de 30 dias (a trigger
 * da 021 recusa qualquer outro DELETE).
 */
public final class MailDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(MailDispatcher.class);
    static final int MAX_ATTEMPTS = 5;
    static final Duration DEFAULT_LEASE = Duration.ofMinutes(2);
    private static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofMinutes(30));
    private static final int BATCH = 20;
    private static final int PURGE_BATCH = 500;

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
    private final Duration lease;
    private final BiConsumer<UUID, MailSender.Outcome> emergencyResults;
    private final java.util.ArrayDeque<Long> window = new java.util.ArrayDeque<>();

    public MailDispatcher(OrgTransactions tx, SecretCipher cipher, MailTransport transport, MailTemplates templates, int perMinute, Duration lease,
                          BiConsumer<UUID, MailSender.Outcome> emergencyResults) {
        if (perMinute < 1) {
            throw new IllegalArgumentException("nekkora.mail.despacho.por-minuto deve ser positivo");
        }
        this.tx = tx;
        this.cipher = cipher;
        this.transport = transport;
        this.templates = templates;
        this.perMinute = perMinute;
        this.lease = lease;
        this.emergencyResults = emergencyResults;
    }

    private enum Delivered { SENT, NOT_SENT, LOST_POSSESSION }

    private record Claimed(UUID job, MailSender.Kind kind, UUID org, SecretCipher.Encrypted payload, int attempts, UUID correlation, UUID notice) {
    }

    /** Um ciclo. Devolve quantos trabalhos foram entregues ao transporte neste ciclo. */
    public int dispatchPending() {
        List<UUID[]> lost = tx.withoutContext(j -> j.sql("""
                UPDATE public.mail_outbox SET state = 5, lease_until = NULL, finished_at = clock_timestamp(),
                       payload_ciphertext = NULL, payload_nonce = NULL, key_id = NULL
                 WHERE state = 2 AND lease_until < clock_timestamp()
                RETURNING job_id, notice_ref
                """).query((rs, n) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}).list());
        if (!lost.isEmpty()) {
            LOG.warn("evento=mail.posse_vencida quantidade={} desfecho=INCERTO alerta=sim", lost.size());
            lost.forEach(l -> notify(l[1], MailSender.Outcome.INCERTO));
        }
        List<UUID[]> expired = tx.withoutContext(j -> j.sql("""
                UPDATE public.mail_outbox SET state = 6, finished_at = clock_timestamp(), payload_ciphertext = NULL, payload_nonce = NULL, key_id = NULL
                 WHERE state = 1 AND deadline_at <= clock_timestamp()
                RETURNING job_id, notice_ref
                """).query((rs, n) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}).list());
        if (!expired.isEmpty()) {
            LOG.warn("evento=mail.prazo_vencido quantidade={} desfecho=EXPIRADO", expired.size());
            expired.forEach(e -> notify(e[1], MailSender.Outcome.RECUSADO_DEFINITIVO));
        }
        int sent = 0;
        for (int i = 0; i < BATCH; i++) {
            if (reserve() == 0) {
                break;
            }
            Optional<Claimed> c = claimOne();
            if (c.isEmpty()) {
                release();
                break;
            }
            Delivered d = deliver(c.get());
            if (d == Delivered.SENT) {
                sent++;
            } else {
                release();
            }
            if (d == Delivered.LOST_POSSESSION) {
                // posse perdida antes do envio indica atraso desta instancia: encerra o ciclo (o proximo recomeca do zero)
                break;
            }
        }
        return sent;
    }

    /** Retencao: apaga um lote de trabalhos terminais encerrados ha mais de 30 dias. Devolve quantos. */
    public int purgeFinished() {
        int n = tx.withoutContext(j -> j.sql("""
                DELETE FROM public.mail_outbox WHERE job_id IN (
                    SELECT job_id FROM public.mail_outbox WHERE state >= 3 AND finished_at < clock_timestamp() - interval '30 days'
                     ORDER BY finished_at LIMIT ?)
                """).param(PURGE_BATCH).update());
        if (n > 0) {
            LOG.info("evento=mail.retencao_apagados quantidade={}", n);
        }
        return n;
    }

    private Optional<Claimed> claimOne() {
        return tx.withoutContext(j -> j.sql("""
                UPDATE public.mail_outbox o SET state = 2, attempts = o.attempts + 1, lease_until = clock_timestamp() + ?::interval
                  FROM (SELECT job_id FROM public.mail_outbox
                         WHERE state = 1 AND next_attempt_at <= clock_timestamp() AND deadline_at > clock_timestamp() AND attempts < ?
                         ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED) c
                 WHERE o.job_id = c.job_id
                RETURNING o.job_id, o.kind, o.target_organization_id, o.payload_ciphertext, o.payload_nonce, o.key_id, o.attempts, o.correlation_id,
                          o.notice_ref
                """).param(millis(lease)).param(MAX_ATTEMPTS)
                .query((rs, n) -> new Claimed(rs.getObject(1, UUID.class), MailSender.Kind.valueOf(rs.getString(2)), rs.getObject(3, UUID.class),
                        new SecretCipher.Encrypted(rs.getBytes(4), rs.getBytes(5), rs.getString(6)), rs.getInt(7), rs.getObject(8, UUID.class),
                        rs.getObject(9, UUID.class)))
                .optional());
    }

    /**
     * Ainda ENVIANDO, mesma tentativa, posse e prazo validos DEPOIS de qualquer espera; renova a posse. Dois comandos na mesma
     * transacao: primeiro so o lock da linha (pode esperar outra transacao que a segura); depois, num comando NOVO - com o
     * relogio lido depois da espera -, a conferencia e a renovacao. Num comando unico, a condicao com clock_timestamp() pode
     * ter sido avaliada antes da espera pelo lock (a mesma classe de problema do P1).
     */
    private boolean stillMine(Claimed c) {
        return tx.withoutContext(j -> {
            if (j.sql("SELECT 1 FROM public.mail_outbox WHERE job_id = ? FOR UPDATE").param(c.job()).query(Integer.class).optional().isEmpty()) {
                return false;
            }
            beforeConfirm.run();
            return j.sql("""
                    UPDATE public.mail_outbox SET lease_until = clock_timestamp() + ?::interval
                     WHERE job_id = ? AND state = 2 AND attempts = ? AND lease_until > clock_timestamp() AND deadline_at > clock_timestamp()
                    """).param(millis(lease)).param(c.job()).param(c.attempts()).update() == 1;
        });
    }

    /** Ponto de teste: depois do lock da linha e antes da conferencia (nada em producao). */
    private volatile Runnable beforeConfirm = () -> { };
    /** Ponto de teste: entre a reivindicacao e a reconferencia (nada em producao). */
    private volatile Runnable afterClaim = () -> { };

    void testHooks(Runnable afterClaim, Runnable beforeConfirm) {
        this.afterClaim = afterClaim;
        this.beforeConfirm = beforeConfirm;
    }

    /** Desfecho da vez: enviado ao transporte, nao enviado, ou posse perdida antes do envio. */
    private Delivered deliver(Claimed c) {
        MailTransport.Message m;
        byte[] plain = null;
        try {
            plain = cipher.decryptForMail(c.payload(), c.job());
            Map<String, String> p = OutboxMailSender.decode(plain);
            MailTemplates.Rendered r = switch (c.kind()) {
                case NOTICE_MFA_EMERGENCY_HOLDER, NOTICE_MFA_EMERGENCY_ADMIN -> templates.renderEmergency(c.kind(), c.org(), p.get("token"), p.get("contest"));
                default -> templates.render(c.kind(), c.org(), p.get("token"));
            };
            m = new MailTransport.Message(c.correlation(), p.get("to"), r.subject(), r.body());
        } catch (RuntimeException e) {
            // payload que nao decifra ou mensagem que nao monta: nada foi enviado e repetir nao muda o resultado
            LOG.warn("evento=mail.payload_invalido job={} tipo={} causa={}", c.job(), c.kind(), e.getClass().getSimpleName());
            record(c, new MailTransport.Delivery(MailTransport.Result.RECUSADO, null, null));
            return Delivered.NOT_SENT;
        } finally {
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
        afterClaim.run();
        if (!stillMine(c)) {
            // posse vencida ou prazo vencido entre a reivindicacao e o envio: NAO envia. Se ninguem levou o trabalho a outro
            // estado, ele volta a PENDENTE (sabidamente nao enviado); o prazo vencido e tratado pela varredura seguinte
            int back = tx.withoutContext(j -> j.sql("""
                    UPDATE public.mail_outbox SET state = 1, lease_until = NULL, next_attempt_at = clock_timestamp() + ?::interval
                     WHERE job_id = ? AND state = 2 AND attempts = ?
                    """).param(BACKOFF.get(0).toSeconds() + " seconds").param(c.job()).param(c.attempts()).update());
            LOG.warn("evento=mail.envio_cancelado_sem_posse job={} tipo={} tentativa={} devolvido={} alerta=sim", c.job(), c.kind(), c.attempts(),
                    back == 1);
            return Delivered.LOST_POSSESSION;
        }
        record(c, transport.deliver(m));
        return Delivered.SENT;
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
            // a posse venceu DURANTE o envio e outro ciclo ja levou o trabalho a INCERTO: o desfecho fica so no log
            LOG.warn("evento=mail.desfecho_sem_posse job={} tipo={} tentativa={} desfecho={} alerta=sim", c.job(), c.kind(), c.attempts(), result);
            return;
        }
        LOG.info("evento=mail.desfecho job={} tipo={} tentativa={} desfecho={} status={} correlacao={}", c.job(), c.kind(), c.attempts(), result,
                d.status(), c.correlation());
        if (result != MailTransport.Result.RETENTAVEL) {
            notify(c.notice(), switch (result) {
                case ACEITO -> MailSender.Outcome.ACEITO_PELO_TRANSPORTE;
                case RECUSADO -> MailSender.Outcome.RECUSADO_DEFINITIVO;
                default -> MailSender.Outcome.INCERTO;
            });
        }
    }

    private void notify(UUID notice, MailSender.Outcome outcome) {
        if (notice == null) {
            return;
        }
        try {
            emergencyResults.accept(notice, outcome);
        } catch (RuntimeException e) {
            // o registro na 016 nao aconteceu: a varredura dela leva a tentativa parada a DESCONHECIDO (nunca a ACEITA)
            LOG.warn("evento=mail.desfecho_emergencial_nao_registrado alerta=sim");
        }
    }

    private synchronized int reserve() {
        long now = System.nanoTime();
        while (!window.isEmpty() && now - window.peekFirst() >= 60_000_000_000L) {
            window.pollFirst();
        }
        if (window.size() >= perMinute) {
            return 0;
        }
        window.addLast(now);
        return 1;
    }

    private synchronized void release() {
        window.pollLast();
    }

    private static String millis(Duration d) {
        return d.toMillis() + " milliseconds";
    }
}
