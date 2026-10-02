package com.nekkora.platform.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * WIN-TRANSPORT-1: aquisicao da trava do controle (CaixaHomologacaoMailSender.adquirirTrava), sem sistema de arquivos:
 * o criador da trava e simulado. AccessDeniedException so e contencao no Windows e para o caminho EXATO da trava; o prazo
 * e UNICO (nunca reiniciado); esgotado o prazo depois de acesso negado, a causa final e o AccessDeniedException.
 */
class AquisicaoTravaTest {

    private static final Path TRAVA = Path.of("caixa-teste").resolve("controle.trava").toAbsolutePath();

    /** Criador que lanca as excecoes da sequencia (ciclica se {@code repete}) e depois cria com sucesso. */
    private static CaixaHomologacaoMailSender.CriadorTrava sequencia(AtomicInteger chamadas, boolean repete, IOException... falhas) {
        return p -> {
            int i = chamadas.getAndIncrement();
            if (repete) {
                throw falhas[i % falhas.length];
            }
            if (i < falhas.length) {
                throw falhas[i];
            }
        };
    }

    private static AccessDeniedException negado(Path p) {
        return new AccessDeniedException(p.toString());
    }

    @Test
    void adquireDeImediato() {
        AtomicInteger n = new AtomicInteger();
        CaixaHomologacaoMailSender.adquirirTrava(TRAVA, System.currentTimeMillis() + 1_000, sequencia(n, false), true);
        assertThat(n.get()).isEqualTo(1);
    }

    @Test
    void ocupadaEsperaEAdquire() {
        AtomicInteger n = new AtomicInteger();
        FileAlreadyExistsException ocupada = new FileAlreadyExistsException(TRAVA.toString());
        CaixaHomologacaoMailSender.adquirirTrava(TRAVA, System.currentTimeMillis() + 2_000, sequencia(n, false, ocupada, ocupada, ocupada), false);
        assertThat(n.get()).isEqualTo(4);
    }

    @Test
    void acessoNegadoNaTravaNoWindowsEContencaoTransitoria() {
        AtomicInteger n = new AtomicInteger();
        CaixaHomologacaoMailSender.adquirirTrava(TRAVA, System.currentTimeMillis() + 2_000, sequencia(n, false, negado(TRAVA), negado(TRAVA)), true);
        assertThat(n.get()).isEqualTo(3);
    }

    @Test
    void acessoNegadoForaDoWindowsEFalhaImediata() {
        AtomicInteger n = new AtomicInteger();
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> CaixaHomologacaoMailSender.adquirirTrava(TRAVA, System.currentTimeMillis() + 2_000, sequencia(n, false, negado(TRAVA)), false))
                .isInstanceOf(UncheckedIOException.class).hasCauseInstanceOf(AccessDeniedException.class);
        assertThat(n.get()).isEqualTo(1);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(500);
    }

    @Test
    void acessoNegadoEmOutroCaminhoEFalhaImediataMesmoNoWindows() {
        AtomicInteger n = new AtomicInteger();
        Path pai = TRAVA.getParent();
        assertThatThrownBy(() -> CaixaHomologacaoMailSender.adquirirTrava(TRAVA, System.currentTimeMillis() + 2_000, sequencia(n, false, negado(pai)), true))
                .isInstanceOf(UncheckedIOException.class).hasCauseInstanceOf(AccessDeniedException.class);
        assertThat(n.get()).isEqualTo(1);
    }

    @Test
    void acessoNegadoSemCaminhoEFalhaImediata() {
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> CaixaHomologacaoMailSender.adquirirTrava(TRAVA, System.currentTimeMillis() + 2_000,
                sequencia(n, false, new AccessDeniedException(null)), true)).isInstanceOf(UncheckedIOException.class);
        assertThat(n.get()).isEqualTo(1);
    }

    @Test
    void outraIoExceptionEFalhaImediata() {
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> CaixaHomologacaoMailSender.adquirirTrava(TRAVA, System.currentTimeMillis() + 2_000,
                sequencia(n, false, new NoSuchFileException(TRAVA.toString())), true))
                .isInstanceOf(UncheckedIOException.class).hasCauseInstanceOf(NoSuchFileException.class);
        assertThat(n.get()).isEqualTo(1);
    }

    @Test
    void acessoNegadoPersistenteNaoViraSucessoEPreservaACausa() {
        AtomicInteger n = new AtomicInteger();
        long t0 = System.currentTimeMillis();
        assertThatThrownBy(() -> CaixaHomologacaoMailSender.adquirirTrava(TRAVA, t0 + 300, sequencia(n, true, negado(TRAVA)), true))
                .isInstanceOf(UncheckedIOException.class).hasCauseInstanceOf(AccessDeniedException.class);
        assertThat(System.currentTimeMillis() - t0).isGreaterThanOrEqualTo(300);
        assertThat(n.get()).isGreaterThan(1);
    }

    @Test
    void prazoUnicoComOcupadaEAcessoNegadoAlternadosNaoReinicia() {
        AtomicInteger n = new AtomicInteger();
        long t0 = System.currentTimeMillis();
        assertThatThrownBy(() -> CaixaHomologacaoMailSender.adquirirTrava(TRAVA, t0 + 500,
                sequencia(n, true, new FileAlreadyExistsException(TRAVA.toString()), negado(TRAVA)), true))
                .isInstanceOf(UncheckedIOException.class).hasCauseInstanceOf(AccessDeniedException.class);
        long dur = System.currentTimeMillis() - t0;
        assertThat(dur).isGreaterThanOrEqualTo(500).isLessThan(1_500);   // o prazo total, sem reinicio
        assertThat(n.get()).isGreaterThan(4);                             // alternou varias vezes dentro do mesmo prazo
    }

    @Test
    void ocupadaPersistenteTerminaComoTravaOcupada() {
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> CaixaHomologacaoMailSender.adquirirTrava(TRAVA, System.currentTimeMillis() + 200,
                sequencia(n, true, new FileAlreadyExistsException(TRAVA.toString())), true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("trava do controle ocupada");
    }
}
