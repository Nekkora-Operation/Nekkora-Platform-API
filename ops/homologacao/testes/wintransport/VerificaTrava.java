package com.nekkora.platform.mail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WIN-TRANSPORT-1, controle LEVE do validar-config (sem sistema de arquivos, sem estresse): exercita o adquirirTrava REAL
 * (compilado de copia verbatim) com um criador simulado e confere o contrato da correcao. Codigo 0 = todos OK.
 *   - acesso negado no caminho EXATO da trava, no Windows: contencao transitoria (adquire depois);
 *   - fora do Windows, em outro caminho ou sem caminho: falha imediata; outra IOException: falha imediata;
 *   - acesso negado persistente: nunca sucesso; no prazo, UncheckedIOException com AccessDeniedException como causa;
 *   - ocupada e acesso negado alternados: prazo UNICO (sem reinicio); ocupada persistente: "trava do controle ocupada".
 */
public final class VerificaTrava {

    static final Path TRAVA = Path.of("caixa-verificacao").resolve("controle.trava").toAbsolutePath();
    static int falhas = 0;

    interface Cenario { void rodar() throws Exception; }

    static void confere(String nome, Cenario c) {
        AtomicReference<Throwable> erro = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                c.rodar();
            } catch (Throwable e) {
                erro.set(e);
            }
        });
        t.setDaemon(true);
        t.start();
        try {
            t.join(3_000);   // um prazo reiniciado nunca termina: o limite do cenario detecta
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            erro.set(new AssertionError("nao terminou em 3 s (prazo reiniciado?)"));
        }
        if (erro.get() == null) {
            System.out.println("OK    " + nome);
        } else {
            falhas++;
            System.out.println("FALHA " + nome + ": " + erro.get());
        }
    }

    static void exige(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    static CaixaHomologacaoMailSender.CriadorTrava seq(AtomicInteger n, boolean repete, IOException... falhas) {
        return p -> {
            int i = n.getAndIncrement();
            if (repete) {
                throw falhas[i % falhas.length];
            }
            if (i < falhas.length) {
                throw falhas[i];
            }
        };
    }

    static Throwable falhaDe(long prazoMs, CaixaHomologacaoMailSender.CriadorTrava c, boolean windows) {
        try {
            CaixaHomologacaoMailSender.adquirirTrava(TRAVA, System.currentTimeMillis() + prazoMs, c, windows);
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("adquiriu (esperada falha)");
    }

    static AccessDeniedException negado(Path p) {
        return new AccessDeniedException(p == null ? null : p.toString());
    }

    public static void main(String[] a) {
        confere("acesso negado na trava exata, Windows: transitorio, adquire", () -> {
            AtomicInteger n = new AtomicInteger();
            CaixaHomologacaoMailSender.adquirirTrava(TRAVA, System.currentTimeMillis() + 1_000, seq(n, false, negado(TRAVA), negado(TRAVA)), true);
            exige(n.get() == 3, "tentativas=" + n.get());
        });
        confere("acesso negado fora do Windows: falha imediata com a causa", () -> {
            AtomicInteger n = new AtomicInteger();
            Throwable e = falhaDe(1_000, seq(n, false, negado(TRAVA)), false);
            exige(e instanceof UncheckedIOException && e.getCause() instanceof AccessDeniedException && n.get() == 1, e + " tentativas=" + n.get());
        });
        confere("acesso negado em outro caminho (pai), Windows: falha imediata", () -> {
            AtomicInteger n = new AtomicInteger();
            Throwable e = falhaDe(1_000, seq(n, false, negado(TRAVA.getParent())), true);
            exige(e instanceof UncheckedIOException && n.get() == 1, e + " tentativas=" + n.get());
        });
        confere("acesso negado sem caminho, Windows: falha imediata", () -> {
            AtomicInteger n = new AtomicInteger();
            Throwable e = falhaDe(1_000, seq(n, false, negado(null)), true);
            exige(e instanceof UncheckedIOException && n.get() == 1, e + " tentativas=" + n.get());
        });
        confere("outra IOException na aquisicao: falha imediata", () -> {
            AtomicInteger n = new AtomicInteger();
            Throwable e = falhaDe(1_000, seq(n, false, new NoSuchFileException(TRAVA.toString())), true);
            exige(e instanceof UncheckedIOException && e.getCause() instanceof NoSuchFileException && n.get() == 1, e + " tentativas=" + n.get());
        });
        confere("acesso negado persistente: nunca sucesso; no prazo, causa AccessDeniedException preservada", () -> {
            AtomicInteger n = new AtomicInteger();
            long t0 = System.currentTimeMillis();
            Throwable e = falhaDe(300, seq(n, true, negado(TRAVA)), true);
            long dur = System.currentTimeMillis() - t0;
            exige(e instanceof UncheckedIOException && e.getCause() instanceof AccessDeniedException, "excecao final " + e);
            exige(dur >= 300 && n.get() > 1, "dur=" + dur + " tentativas=" + n.get());
        });
        confere("ocupada e acesso negado alternados: prazo unico (sem reinicio), causa AccessDeniedException", () -> {
            AtomicInteger n = new AtomicInteger();
            long t0 = System.currentTimeMillis();
            Throwable e = falhaDe(500, seq(n, true, new FileAlreadyExistsException(TRAVA.toString()), negado(TRAVA)), true);
            long dur = System.currentTimeMillis() - t0;
            exige(e instanceof UncheckedIOException && e.getCause() instanceof AccessDeniedException, "excecao final " + e);
            exige(dur >= 500 && dur < 1_500 && n.get() > 4, "dur=" + dur + " tentativas=" + n.get());
        });
        confere("ocupada persistente: termina como trava ocupada", () -> {
            AtomicInteger n = new AtomicInteger();
            Throwable e = falhaDe(200, seq(n, true, new FileAlreadyExistsException(TRAVA.toString())), true);
            exige(e instanceof IllegalStateException && String.valueOf(e.getMessage()).contains("trava do controle ocupada"), "excecao final " + e);
        });
        System.out.println(falhas == 0 ? "RESULTADO: OK" : "RESULTADO: " + falhas + " falha(s)");
        System.exit(falhas == 0 ? 0 : 1);
    }
}
