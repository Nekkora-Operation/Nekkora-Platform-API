package com.nekkora.platform.mail;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WIN-TRANSPORT-0: harness de concorrencia CONTROLADA da trava da caixa (controle.trava), SEM Docker e sem tocar
 * ambiente algum. Compilado num diretorio temporario junto com COPIAS VERBATIM de MailSender.java e
 * CaixaHomologacaoMailSender.java (sha256 registrado pelo roteiro): exercita o desfecho() real. Cada caso usa um
 * diretorio temporario exclusivo e grava CADA tentativa em CSV (caso,rodada,ator,tentativa,inicio_ns,dur_ns,resultado,excecao).
 *
 *   java -cp <classes>;<slf4j-api> com.nekkora.platform.mail.Harness <dir de saida> <caso> <rodadas> <tentativas> [python]
 * Casos: P1 (NIO puro, 2 threads criando e apagando a mesma trava), P2 (NIO x processo Python que cria/apaga),
 *        H1 (um transporte, sequencial), H2 (dois transportes, MESMA caixa, barreira), H3 (dois transportes, caixas
 *        DIFERENTES), H4 (transportes + escritor externo em THREAD com o protocolo da ferramenta, como o IT),
 *        H5 (transporte + escritor externo em OUTRO PROCESSO, Python, mesmo protocolo), H6 (idem com sh, protocolo exato do
 *        runner), C1 (erro de E/S FORA da aquisicao: fatal e imediato), C2 (acesso negado REAL e persistente na aquisicao:
 *        nunca sucesso; causa preservada), C3 (prazo unico com ocupada/acesso negado alternados). H4/H5/H6 registram o
 *        BALANCO de cada rodada (gravadas = consumidas + restantes).
 * Nao corrige nada: so mede. Codigo de saida 0 sempre que o caso rodou ate o fim (o resultado esta no CSV/resumo).
 */
public final class Harness {

    record Linha(String caso, int rodada, String ator, int tentativa, long inicio, long dur, String resultado, String excecao) {
    }

    static final ConcurrentLinkedQueue<Linha> LINHAS = new ConcurrentLinkedQueue<>();
    static final long T0 = System.nanoTime();

    static void reg(String caso, int rodada, String ator, int tent, long ini, String res, Throwable e) {
        LINHAS.add(new Linha(caso, rodada, ator, tent, ini - T0, System.nanoTime() - ini, res, e == null ? "-" : causa(e)));
    }

    static String causa(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String origem = "-";
        for (StackTraceElement f : c.getStackTrace()) {
            if (f.getClassName().endsWith("CaixaHomologacaoMailSender")) {
                origem = f.getMethodName() + ":" + f.getLineNumber();
                break;
            }
        }
        if (origem.equals("-")) {
            for (StackTraceElement f : c.getStackTrace()) {
                if (f.getClassName().startsWith("java.nio.file.Files")) {
                    origem = "Files." + f.getMethodName();
                    break;
                }
            }
        }
        return c.getClass().getSimpleName() + "@" + origem;
    }

    static Path novaCaixa(Path base, String nome) throws IOException {
        Path d = Files.createTempDirectory(base, nome + "-");
        return d;
    }

    // ---- P1: primitiva NIO, threads: A cria+apaga em laco; B tenta criar (FileAlreadyExists = ocupada)
    static void p1(Path base, int rodadas, int tent) throws Exception {
        for (int r = 0; r < rodadas; r++) {
            Path dir = novaCaixa(base, "p1-r" + r);
            Path trava = dir.resolve("controle.trava");
            CyclicBarrier bar = new CyclicBarrier(2);
            int rr = r;
            Thread a = new Thread(() -> laco("P1", rr, "A_cria_apaga", trava, tent, bar, true));
            Thread b = new Thread(() -> laco("P1", rr, "B_cria_apaga", trava, tent, bar, true));
            a.start(); b.start(); a.join(); b.join();
        }
    }

    static void laco(String caso, int r, String ator, Path trava, int tent, CyclicBarrier bar, boolean apaga) {
        try {
            bar.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (int i = 0; i < tent; i++) {
            long ini = System.nanoTime();
            try {
                Files.createDirectory(trava);
                reg(caso, r, ator, i, ini, "ADQUIRIU", null);
                if (apaga) {
                    long ini2 = System.nanoTime();
                    try {
                        Files.delete(trava);
                        reg(caso, r, ator + "_libera", i, ini2, "LIBEROU", null);
                    } catch (Exception e) {
                        reg(caso, r, ator + "_libera", i, ini2, "ERRO_LIBERAR", e);
                    }
                }
            } catch (FileAlreadyExistsException e) {
                reg(caso, r, ator, i, ini, "OCUPADA", e);
            } catch (AccessDeniedException e) {
                reg(caso, r, ator, i, ini, "ACESSO_NEGADO", e);
            } catch (Exception e) {
                reg(caso, r, ator, i, ini, "OUTRO_ERRO", e);
            }
        }
    }

    // ---- P2 / H5: escritor externo em PROCESSO Python
    static Process python(String py, Path dir, int tent, Path csv, boolean anexa) throws IOException {
        String prog = String.join("\n",
            "import os, sys, time",
            "d, n, csv, anexa = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4] == '1'",
            "t = os.path.join(d, 'controle.trava'); ok = 0; i = 0",
            "with open(csv, 'w') as f:",
            "    while ok < n and i < n * 2000:",
            "        i += 1; ini = time.perf_counter_ns()",
            "        try:",
            "            os.mkdir(t)",
            "        except FileExistsError:",
            "            f.write(f'{i},OCUPADA,FileExistsError\\n'); continue",
            "        except PermissionError as e:",
            "            f.write(f'{i},ACESSO_NEGADO,PermissionError\\n'); continue",
            "        try:",
            "            if anexa:",
            "                with open(os.path.join(d, 'controle.conf'), 'a') as c: c.write('proximo ENFILEIRADO\\n')",
            "            ok += 1",
            "        finally:",
            "            try:",
            "                os.rmdir(t); f.write(f'{i},ADQUIRIU_LIBEROU,-\\n')",
            "            except OSError as e:",
            "                f.write(f'{i},ERRO_LIBERAR,{type(e).__name__}\\n')",
            "print(ok)");
        Path script = dir.getParent().resolve("escritor_" + UUID.randomUUID() + ".py");
        Files.writeString(script, prog, StandardCharsets.UTF_8);
        return new ProcessBuilder(py, script.toString(), dir.toString(), String.valueOf(tent), csv.toString(), anexa ? "1" : "0")
                .redirectErrorStream(true).start();
    }

    static void p2(Path base, int rodadas, int tent, String py, Path out) throws Exception {
        for (int r = 0; r < rodadas; r++) {
            Path dir = novaCaixa(base, "p2-r" + r);
            Process p = python(py, dir, tent, out.resolve("P2_python_r" + r + ".csv"), false);
            laco("P2", r, "java_cria_apaga", dir.resolve("controle.trava"), tent, new CyclicBarrier(1), true);
            p.waitFor();
        }
    }

    // ---- H1..H5: o desfecho() REAL do transporte
    static final java.util.concurrent.atomic.AtomicInteger CONSUMIDAS = new java.util.concurrent.atomic.AtomicInteger();

    static void chamadas(String caso, int r, String ator, CaixaHomologacaoMailSender t, int tent, CyclicBarrier bar) {
        try {
            if (bar != null) {
                bar.await();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (int i = 0; i < tent; i++) {
            long ini = System.nanoTime();
            try {
                CaixaHomologacaoMailSender.Desfecho d = t.desfecho("0".repeat(64));
                if (d == CaixaHomologacaoMailSender.Desfecho.ENFILEIRADO) {
                    CONSUMIDAS.incrementAndGet();
                }
                reg(caso, r, ator, i, ini, "DESFECHO_" + d, null);
            } catch (Exception e) {
                reg(caso, r, ator, i, ini, "EXCECAO", e);
            }
        }
    }

    /**
     * Escritor externo com o protocolo da ferramenta (mkdir da trava, anexa, rmdir), com a MESMA regra do IT corrigido
     * (WIN-TRANSPORT-1): FileAlreadyExists = ocupada; AccessDenied SO no Windows e SO no caminho exato da trava =
     * contencao transitoria; qualquer outro erro e FATAL (registrado e o escritor para). Devolve as regras gravadas.
     */
    static int escritorThread(String caso, int r, Path dir, int regras, CyclicBarrier bar, AtomicBoolean fim) {
        try {
            bar.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Path trava = dir.resolve("controle.trava");
        int gravadas = 0, i = 0;
        while (gravadas < regras && !fim.get() && i < regras * 100_000) {
            i++;
            long ini = System.nanoTime();
            try {
                Files.createDirectory(trava);
            } catch (FileAlreadyExistsException e) {
                reg(caso, r, "escritor", i, ini, "OCUPADA", e);
                Thread.onSpinWait();
                continue;
            } catch (AccessDeniedException e) {
                if (CaixaHomologacaoMailSender.WINDOWS && e.getFile() != null && Path.of(e.getFile()).equals(trava)) {
                    reg(caso, r, "escritor", i, ini, "OCUPADA_REMOCAO_PENDENTE", e);
                    Thread.onSpinWait();
                    continue;
                }
                reg(caso, r, "escritor", i, ini, "ESCRITOR_FATAL", e);
                return gravadas;
            } catch (Exception e) {
                reg(caso, r, "escritor", i, ini, "ESCRITOR_FATAL", e);
                return gravadas;
            }
            try {
                Files.writeString(dir.resolve("controle.conf"), "proximo ENFILEIRADO\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                gravadas++;
                reg(caso, r, "escritor", i, ini, "GRAVOU", null);
            } catch (Exception e) {
                reg(caso, r, "escritor", i, ini, "ESCRITOR_FATAL", e);
                return gravadas;
            } finally {
                long ini2 = System.nanoTime();
                try {
                    Files.delete(trava);
                    reg(caso, r, "escritor_libera", i, ini2, "LIBEROU", null);
                } catch (Exception e) {
                    reg(caso, r, "escritor_libera", i, ini2, "ERRO_LIBERAR", e);
                }
            }
        }
        return gravadas;
    }

    /** Escritor externo em PROCESSO sh com o protocolo EXATO do runner (mkdir da trava && anexa && rmdir). */
    static Process escritorSh(Path dir, int regras) throws IOException {
        String prog = "d=\"$1\"; n=\"$2\"; ok=0; i=0; while [ $ok -lt $n ] && [ $i -lt $((n * 2000)) ]; do i=$((i + 1)); "
                + "if mkdir \"$d/controle.trava\" 2>/dev/null; then printf 'proximo ENFILEIRADO\\n' >> \"$d/controle.conf\"; "
                + "rmdir \"$d/controle.trava\"; ok=$((ok + 1)); fi; done; echo $ok";
        return new ProcessBuilder("sh", "-c", prog, "sh", dir.toString(), String.valueOf(regras)).redirectErrorStream(true).start();
    }

    static long restantes(Path dir) throws IOException {
        Path c = dir.resolve("controle.conf");
        return Files.exists(c) ? Files.readAllLines(c).stream().filter(l -> l.equals("proximo ENFILEIRADO")).count() : 0;
    }

    static void balanco(String caso, int r, long gravadas, long consumidas, long rest) {
        boolean ok = gravadas == consumidas + rest;
        LINHAS.add(new Linha(caso, r, "balanco", 0, 0, 0, ok ? "BALANCO_OK" : "BALANCO_FALHA",
                "gravadas=" + gravadas + ";consumidas=" + consumidas + ";restantes=" + rest));
    }

    static void h(String caso, Path base, int rodadas, int tent, String py, Path out) throws Exception {
        for (int r = 0; r < rodadas; r++) {
            Path c1 = novaCaixa(base, caso.toLowerCase() + "-r" + r + "-a");
            Path c2 = caso.equals("H3") ? novaCaixa(base, caso.toLowerCase() + "-r" + r + "-b") : c1;
            CaixaHomologacaoMailSender t1 = new CaixaHomologacaoMailSender(c1, "ensaio-harness");
            CaixaHomologacaoMailSender t2 = new CaixaHomologacaoMailSender(c2, "ensaio-harness");
            int rr = r;
            CONSUMIDAS.set(0);
            List<Thread> ts = new ArrayList<>();
            switch (caso) {
                case "H1" -> chamadas(caso, r, "t1", t1, tent, null);
                case "H2", "H3" -> {
                    CyclicBarrier bar = new CyclicBarrier(2);
                    ts.add(new Thread(() -> chamadas(caso, rr, "t1", t1, tent, bar)));
                    ts.add(new Thread(() -> chamadas(caso, rr, "t2", t2, tent, bar)));
                }
                case "H4" -> {
                    CyclicBarrier bar = new CyclicBarrier(3);
                    AtomicBoolean fim = new AtomicBoolean();
                    java.util.concurrent.atomic.AtomicInteger gr = new java.util.concurrent.atomic.AtomicInteger();
                    Thread esc = new Thread(() -> gr.set(escritorThread(caso, rr, c1, Math.max(1, tent / 3), bar, fim)));
                    Thread a = new Thread(() -> chamadas(caso, rr, "t1", t1, tent, bar));
                    Thread b = new Thread(() -> chamadas(caso, rr, "t2", t2, tent, bar));
                    esc.start(); a.start(); b.start(); esc.join(); a.join(); b.join();
                    balanco(caso, r, gr.get(), CONSUMIDAS.get(), restantes(c1));
                }
                case "H5", "H6" -> {
                    Process p = caso.equals("H5") ? python(py, c1, Math.max(1, tent / 3), out.resolve("H5_python_r" + r + ".csv"), true)
                            : escritorSh(c1, Math.max(1, tent / 3));
                    CyclicBarrier bar = new CyclicBarrier(2);
                    Thread a = new Thread(() -> chamadas(caso, rr, "t1", t1, tent, bar));
                    Thread b = new Thread(() -> chamadas(caso, rr, "t2", t2, tent, bar));
                    a.start(); b.start(); a.join(); b.join();
                    String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                    p.waitFor();
                    String[] ls = saida.split("\\R");
                    long gr = Long.parseLong(ls[ls.length - 1].trim());
                    balanco(caso, r, gr, CONSUMIDAS.get(), restantes(c1));
                }
                default -> throw new IllegalArgumentException(caso);
            }
            ts.forEach(Thread::start);
            for (Thread t : ts) {
                t.join();
            }
        }
    }

    // ---- controles da correcao (WIN-TRANSPORT-1)
    /** C1: AccessDenied (ou outro erro de E/S) FORA da aquisicao da trava continua fatal e IMEDIATO. */
    static void c1(Path base, int rodadas) throws Exception {
        for (int r = 0; r < rodadas; r++) {
            Path c = novaCaixa(base, "c1-r" + r);
            CaixaHomologacaoMailSender t = new CaixaHomologacaoMailSender(c, "ensaio-harness");
            Files.createDirectory(c.resolve("controle.conf"));   // ler um diretorio: AccessDenied no Windows, IOException no Linux
            long ini = System.nanoTime();
            try {
                t.desfecho("0".repeat(64));
                reg("C1", r, "t1", 0, ini, "SUCESSO_INDEVIDO", null);
            } catch (Exception e) {
                long ms = (System.nanoTime() - ini) / 1_000_000;
                reg("C1", r, "t1", 0, ini, (ms < 1_000 ? "FALHA_IMEDIATA" : "FALHA_LENTA") + "[" + e.getClass().getSimpleName() + "]", e);
            }
            Files.delete(c.resolve("controle.conf"));
        }
    }

    /** C2: acesso negado REAL e persistente na propria aquisicao (ACL/permissao da caixa): nunca vira sucesso; no Windows
     *  espera o prazo e termina com AccessDeniedException como causa; fora do Windows, falha imediata. */
    static void c2(Path base, int rodadas) throws Exception {
        for (int r = 0; r < rodadas; r++) {
            Path c = novaCaixa(base, "c2-r" + r);
            CaixaHomologacaoMailSender t = new CaixaHomologacaoMailSender(c, "ensaio-harness");
            String usuario = System.getProperty("user.name");
            if (CaixaHomologacaoMailSender.WINDOWS) {
                new ProcessBuilder("icacls", c.toString(), "/deny", usuario + ":(AD)").redirectErrorStream(true).start().waitFor();
            } else {
                Files.setPosixFilePermissions(c, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
            }
            long ini = System.nanoTime();
            try {
                t.desfecho("0".repeat(64));
                reg("C2", r, "t1", 0, ini, "SUCESSO_INDEVIDO", null);
            } catch (Exception e) {
                long ms = (System.nanoTime() - ini) / 1_000_000;
                reg("C2", r, "t1", 0, ini, "FALHA_EM_" + (ms / 100 * 100) + "MS[" + e.getClass().getSimpleName() + "]", e);
            } finally {
                if (CaixaHomologacaoMailSender.WINDOWS) {
                    new ProcessBuilder("icacls", c.toString(), "/remove:d", usuario).redirectErrorStream(true).start().waitFor();
                } else {
                    Files.setPosixFilePermissions(c, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
                }
            }
        }
    }

    /** C3: prazo UNICO com ocupada e acesso negado alternados (criador simulado, caminho exato, modo Windows). */
    static void c3(int rodadas) {
        for (int r = 0; r < rodadas; r++) {
            Path trava = Path.of("c3").resolve("controle.trava").toAbsolutePath();
            int[] n = {0};
            CaixaHomologacaoMailSender.CriadorTrava alterna = p -> {
                if (n[0]++ % 2 == 0) {
                    throw new FileAlreadyExistsException(p.toString());
                }
                throw new AccessDeniedException(p.toString());
            };
            long ini = System.nanoTime();
            try {
                CaixaHomologacaoMailSender.adquirirTrava(trava, System.currentTimeMillis() + 1_000, alterna, true);
                reg("C3", r, "aquisicao", 0, ini, "SUCESSO_INDEVIDO", null);
            } catch (Exception e) {
                long ms = (System.nanoTime() - ini) / 1_000_000;
                reg("C3", r, "aquisicao", n[0], ini, (ms >= 1_000 && ms < 2_000 ? "PRAZO_UNICO_OK_" : "PRAZO_FORA_") + (ms / 100 * 100) + "MS[" + e.getClass().getSimpleName() + "]", e);
            }
        }
    }

    public static void main(String[] a) throws Exception {
        Path out = Path.of(a[0]);
        String caso = a[1];
        int rodadas = Integer.parseInt(a[2]), tent = Integer.parseInt(a[3]);
        String py = a.length > 4 ? a[4] : "python";
        Files.createDirectories(out);
        Path base = Files.createTempDirectory("nekkora.wintransport." + caso + ".");   // pontos: nunca forma palavra de 43 caracteres base64url (formato de token da varredura)
        Files.writeString(out.resolve(caso + "_base.txt"), base.toString() + "\n");
        switch (caso) {
            case "P1" -> p1(base, rodadas, tent);
            case "P2" -> p2(base, rodadas, tent, py, out);
            case "C1" -> c1(base, rodadas);
            case "C2" -> c2(base, rodadas);
            case "C3" -> c3(rodadas);
            default -> h(caso, base, rodadas, tent, py, out);
        }
        try (BufferedWriter w = Files.newBufferedWriter(out.resolve(caso + ".csv"), StandardCharsets.UTF_8)) {
            w.write("caso,rodada,ator,tentativa,inicio_ns,dur_ns,resultado,excecao\n");
            for (Linha l : LINHAS) {
                w.write(l.caso() + "," + l.rodada() + "," + l.ator() + "," + l.tentativa() + "," + l.inicio() + "," + l.dur() + "," + l.resultado() + "," + l.excecao() + "\n");
            }
        }
        System.out.println("caso " + caso + ": " + LINHAS.size() + " tentativas registradas; base " + base);
    }
}
