package com.nekkora.platform.identity;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nekkora.core.web.RequestContext;
import com.nekkora.platform.mail.MailSender;

/**
 * Envio dos e-mails do cadastro FORA do caminho da resposta: a duracao do transporte nao pode distinguir os caminhos que
 * enviam (e-mail livre, PENDING, ACTIVE) dos que nao enviam (SUSPENDED, CLOSED, R9b excedido, desconhecido).
 *
 * <p>Chamado DEPOIS do commit. Threads proprias e fila LIMITADA. Tratamento de falhas, definido:
 * <ul>
 * <li>excecao do transporte: registrada ({@code evento=cadastro.mail_falhou}), SEM nova tentativa;</li>
 * <li>fila cheia: a mensagem e descartada e registrada ({@code evento=cadastro.mail_descartado});</li>
 * <li>desligamento da aplicacao: espera o prazo de drenagem (padrao {@link #DRAIN_SECONDS} s) pelo que esta na fila; as
 * tarefas que nao comecaram sao descartadas, descontadas de {@link #pending()} e registradas; as em execucao sao
 * interrompidas e descontam ao terminar.</li>
 * </ul>
 * Em todos os casos a pessoa recupera pelo reenvio da confirmacao (sujeito a R9b). O token so existe em memoria, nesta
 * fila, e nunca e persistido em claro nem registrado; o log leva so o tipo e a correlacao do pedido.
 */
public final class SignupMailer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SignupMailer.class);
    static final int DRAIN_SECONDS = 5;

    private final MailSender mail;
    private final ThreadPoolExecutor executor;
    private final AtomicInteger pending = new AtomicInteger();
    private final long drainMillis;

    public SignupMailer(MailSender mail, int threads, int queueCapacity) {
        this(mail, threads, queueCapacity, TimeUnit.SECONDS.toMillis(DRAIN_SECONDS));
    }

    public SignupMailer(MailSender mail, int threads, int queueCapacity, long drainMillis) {
        this.mail = mail;
        this.drainMillis = drainMillis;
        AtomicInteger n = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity), r -> {
            Thread t = new Thread(r, "cadastro-mail-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Agenda o envio e retorna imediatamente. Nunca lanca: a resposta HTTP nao depende do transporte. */
    public void enqueue(MailSender.Kind kind, String recipient, String tokenOrNull) {
        String correlation = RequestContext.correlation();
        pending.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    mail.send(kind, null, recipient, tokenOrNull);
                } catch (RuntimeException e) {
                    LOG.warn("evento=cadastro.mail_falhou tipo={} correlacao={}", kind, correlation);
                } finally {
                    pending.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            pending.decrementAndGet();
            LOG.warn("evento=cadastro.mail_descartado motivo=fila_cheia tipo={} correlacao={}", kind, correlation);
        }
    }

    /** Envios agendados e ainda nao concluidos (na fila ou em execucao). */
    public int pending() {
        return pending.get();
    }

    @Override
    public void close() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(drainMillis, TimeUnit.MILLISECONDS)) {
            // as que nao comecaram voltam aqui e nunca vao rodar: saem da contagem agora
            int descartadas = executor.shutdownNow().size();
            pending.addAndGet(-descartadas);
            LOG.warn("evento=cadastro.mail_descartado motivo=desligamento descartadas={} em_execucao={}", descartadas, pending.get());
        }
    }
}
