package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.nekkora.platform.identity.SignupMailer;
import com.nekkora.platform.mail.MailSender;

/**
 * {@link SignupMailer}: o envio nao bloqueia quem chama, e o tratamento de falhas e o definido - excecao do transporte
 * registrada sem nova tentativa, fila cheia descartada e registrada, contagem de pendentes sempre coerente.
 */
class SignupMailerTest {

    /** Transporte simulado que segura cada envio ate o teste liberar, ou falha. */
    static final class Bloqueado implements MailSender {
        final CountDownLatch libera = new CountDownLatch(1);
        final List<String> entregues = new CopyOnWriteArrayList<>();
        volatile boolean falha;

        @Override
        public void send(Kind kind, UUID organization, String recipient, String tokenOrNull) {
            try {
                libera.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (falha) {
                throw new IllegalStateException("falha simulada");
            }
            entregues.add(recipient);
        }

        @Override
        public Outcome sendEmergencyNotice(Kind kind, UUID organization, String recipient, String c, String t, UUID attemptId) {
            throw new UnsupportedOperationException();
        }
    }

    private static void espera(SignupMailer m) throws InterruptedException {
        for (int i = 0; i < 200 && m.pending() > 0; i++) {
            Thread.sleep(25);
        }
    }

    @Test
    void naoBloqueiaQuemChamaEDescartaComAFilaCheia() throws Exception {
        Bloqueado t = new Bloqueado();
        try (SignupMailer m = new SignupMailer(t, 1, 1)) {
            long t0 = System.nanoTime();
            m.enqueue(MailSender.Kind.IDENTITY_SIGNUP_CONFIRMATION, "a@exemplo.test", "tok");   // em execucao (bloqueado)
            Thread.sleep(100);
            m.enqueue(MailSender.Kind.IDENTITY_SIGNUP_CONFIRMATION, "b@exemplo.test", "tok");   // na fila (capacidade 1)
            m.enqueue(MailSender.Kind.IDENTITY_SIGNUP_CONFIRMATION, "c@exemplo.test", "tok");   // fila cheia: descartado
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(ms).as("quem chama nao espera o transporte").isLessThan(1000);
            assertThat(m.pending()).as("um em execucao + um na fila").isEqualTo(2);
            t.libera.countDown();
            espera(m);
            assertThat(m.pending()).isZero();
            assertThat(t.entregues).containsExactly("a@exemplo.test", "b@exemplo.test");
        }
    }

    @Test
    void falhaDoTransporteERegistradaSemNovaTentativa() throws Exception {
        Bloqueado t = new Bloqueado();
        t.falha = true;
        t.libera.countDown();
        try (SignupMailer m = new SignupMailer(t, 1, 10)) {
            m.enqueue(MailSender.Kind.IDENTITY_SIGNUP_CONFIRMATION, "a@exemplo.test", "tok");
            espera(m);
            assertThat(m.pending()).isZero();
            assertThat(t.entregues).isEmpty();
            t.falha = false;
            m.enqueue(MailSender.Kind.IDENTITY_SIGNUP_CONFIRMATION, "b@exemplo.test", "tok");
            espera(m);
            assertThat(t.entregues).as("so o novo pedido; a falha nao foi repetida").containsExactly("b@exemplo.test");
        }
    }

    /**
     * Desligamento com tarefas AGUARDANDO: uma em execucao (presa no transporte) e duas na fila. Esgotado o prazo de
     * drenagem, as duas da fila sao descartadas e descontadas; a em execucao e interrompida e desconta ao terminar. Nada
     * da fila e entregue depois.
     */
    @Test
    void desligamentoDescontaAsTarefasQueNaoComecaram() throws Exception {
        Bloqueado t = new Bloqueado();
        SignupMailer m = new SignupMailer(t, 1, 5, 300);
        m.enqueue(MailSender.Kind.IDENTITY_SIGNUP_CONFIRMATION, "a@exemplo.test", "tok");
        Thread.sleep(100);
        m.enqueue(MailSender.Kind.IDENTITY_SIGNUP_CONFIRMATION, "b@exemplo.test", "tok");
        m.enqueue(MailSender.Kind.IDENTITY_SIGNUP_CONFIRMATION, "c@exemplo.test", "tok");
        assertThat(m.pending()).as("uma em execucao + duas aguardando").isEqualTo(3);
        m.close();
        assertThat(m.pending()).as("as duas aguardando sairam da contagem no desligamento").isLessThanOrEqualTo(1);
        espera(m);
        assertThat(m.pending()).as("a interrompida tambem saiu").isZero();
        assertThat(t.entregues).as("nada da fila foi entregue").doesNotContain("b@exemplo.test", "c@exemplo.test");
        m.enqueue(MailSender.Kind.IDENTITY_SIGNUP_CONFIRMATION, "d@exemplo.test", "tok");
        assertThat(m.pending()).as("depois do desligamento: recusado e nao contado").isZero();
    }
}
