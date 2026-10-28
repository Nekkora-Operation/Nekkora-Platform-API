package com.nekkora.platform.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Teto global de tentativas ({@link TentativasEnvio}): vagas exclusivas em disco, contagem compartilhada entre processos
 * (Platform e prova) e entre reinicios, recusa sem vaga e falha fechada.
 */
class TentativasEnvioTest {

    private Path dir;

    @BeforeEach
    void dir() throws Exception {
        dir = Files.createTempDirectory("nekkora-tentativas-");
    }

    @AfterEach
    void limpa() throws Exception {
        if (Files.exists(dir)) {
            try (var s = Files.walk(dir)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void reservaAteOMaximoERecusaASeguinte() throws Exception {
        TentativasEnvio t = TentativasEnvio.em(dir, 3, "platform");
        for (int i = 1; i <= 3; i++) {
            TentativasEnvio.Reserva r = t.reservar(UUID.randomUUID());
            assertThat(r.concedida()).isTrue();
            assertThat(r.numero()).isEqualTo(i);
            t.registrar(r, MailTransport.Result.ACEITO, 202);
        }
        assertThat(t.reservar(UUID.randomUUID()).concedida()).as("sem vaga: nada sai").isFalse();
        assertThat(t.usadas()).isEqualTo(3);
        String registro = Files.readString(dir.resolve("tentativa-002"));
        assertThat(registro).startsWith("instante=").contains("origem=platform correlacao=").contains("desfecho=ACEITO status=202");
    }

    /** A prova e a Platform (dois processos no mesmo container) e um reinicio veem a mesma contagem. */
    @Test
    void contagemCompartilhadaEntreOrigensEReinicios() {
        TentativasEnvio platform = TentativasEnvio.em(dir, 4, "platform");
        TentativasEnvio prova = TentativasEnvio.em(dir, 4, "prova");
        assertThat(platform.reservar(UUID.randomUUID()).numero()).isEqualTo(1);
        assertThat(prova.reservar(UUID.randomUUID()).numero()).isEqualTo(2);
        TentativasEnvio depoisDoReinicio = TentativasEnvio.em(dir, 4, "platform");
        assertThat(depoisDoReinicio.usadas()).isEqualTo(2);
        assertThat(depoisDoReinicio.reservar(UUID.randomUUID()).numero()).isEqualTo(3);
        assertThat(prova.reservar(UUID.randomUUID()).numero()).isEqualTo(4);
        assertThat(platform.reservar(UUID.randomUUID()).concedida()).isFalse();
        assertThat(prova.reservar(UUID.randomUUID()).concedida()).isFalse();
    }

    /** Reservas simultaneas nunca passam do teto nem repetem uma vaga. */
    @Test
    void concorrenciaNuncaPassaDoTeto() throws Exception {
        TentativasEnvio a = TentativasEnvio.em(dir, 20, "platform");
        TentativasEnvio b = TentativasEnvio.em(dir, 20, "prova");
        ExecutorService ex = Executors.newFixedThreadPool(8);
        CountDownLatch largada = new CountDownLatch(1);
        Set<Integer> numeros = ConcurrentHashMap.newKeySet();
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            TentativasEnvio t = i % 2 == 0 ? a : b;
            fs.add(ex.submit(() -> {
                largada.await();
                TentativasEnvio.Reserva r = t.reservar(UUID.randomUUID());
                if (r.concedida()) {
                    assertThat(numeros.add(r.numero())).as("vaga repetida").isTrue();
                    return 1;
                }
                return 0;
            }));
        }
        largada.countDown();
        int concedidas = 0;
        for (Future<Integer> f : fs) {
            concedidas += f.get(30, TimeUnit.SECONDS);
        }
        ex.shutdown();
        assertThat(concedidas).isEqualTo(20);
        assertThat(numeros).hasSize(20);
        assertThat(a.usadas()).isEqualTo(20);
    }

    @Test
    void falhaFechada() throws Exception {
        assertThatThrownBy(() -> TentativasEnvio.em(dir.resolve("ausente"), 20, "platform")).hasMessageContaining("diretorio");
        assertThatThrownBy(() -> TentativasEnvio.em(dir, 0, "platform")).hasMessageContaining("maximo");
        TentativasEnvio t = TentativasEnvio.em(dir, 5, "platform");
        Files.delete(dir);
        assertThat(t.reservar(UUID.randomUUID()).concedida()).as("diretorio sumiu: recusa, nada sai").isFalse();
        assertThat(t.usadas()).as("ilegivel conta como esgotado").isEqualTo(5);
    }

    @Test
    void regraDeConfiguracao() {
        assertThatThrownBy(() -> TentativasEnvio.daConfiguracao("ensaio-api-v1-003", "", "20", "platform"))
                .hasMessageContaining("ensaio-* exige o teto de tentativas");
        assertThat(TentativasEnvio.daConfiguracao("producao", null, null, "platform").limitado()).as("fora de ensaio-*, opcional").isFalse();
        assertThatThrownBy(() -> TentativasEnvio.daConfiguracao("ensaio-x", dir.toString(), "vinte", "platform")).hasMessageContaining("maximo");
        TentativasEnvio t = TentativasEnvio.daConfiguracao("ensaio-x", dir.toString(), " 20 ", "prova");
        assertThat(t.limitado()).isTrue();
        assertThat(t.maximo()).isEqualTo(20);
        assertThat(TentativasEnvio.SEM_TETO.reservar(UUID.randomUUID()).concedida()).isTrue();
    }
}
