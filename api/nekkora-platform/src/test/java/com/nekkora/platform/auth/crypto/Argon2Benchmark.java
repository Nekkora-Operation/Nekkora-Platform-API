package com.nekkora.platform.auth.crypto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.nekkora.core.web.ApiException;

/**
 * Benchmark do Argon2id (contrato 8.10, §4) com o PasswordHasher REAL, executado por api/bench-argon2/run.sh
 * dentro de um container com CPU e memoria limitados (perfil de referencia DECLARADO; o perfil minimo de
 * producao ainda nao existe, entao os numeros sao provisorios). Um container novo por perfil de custo, para
 * que memory.peak e cpu.stat do cgroup sejam so deste perfil.
 *
 * <p>Mede: (1) latencia sequencial de verificacao com hash real e com o hash FICTICIO (conta inexistente) - as
 * duas precisam ficar na mesma faixa; (2) latencia por operacao e vazao com 4 simultaneos (o semaforo);
 * (3) sobrecarga de 12 chegadas simultaneas (4 + fila de 8, espera 2 s): quantas recebem 503 e a latencia de
 * quem espera; (4) pico de memoria do container e throttling de CPU do cgroup.
 *
 * <p>Argumentos: memoriaKiB iteracoes [sequenciais=40] [porThread=15]
 */
public final class Argon2Benchmark {

    private static final char[] PASSWORD = "senha-de-benchmark-longa-1".toCharArray();

    public static void main(String[] args) throws Exception {
        int m = Integer.parseInt(args[0]);
        int t = Integer.parseInt(args[1]);
        int seq = args.length > 2 ? Integer.parseInt(args[2]) : 40;
        int perThread = args.length > 3 ? Integer.parseInt(args[3]) : 15;
        PasswordHasher h = new PasswordHasher(new PasswordHasher.Parameters(m, t, 1), 4, 8, 2000);
        String hash = h.generate(PASSWORD);
        String cpuBefore = read("/sys/fs/cgroup/cpu.stat");

        for (int i = 0; i < 5; i++) {
            h.verify(PASSWORD, hash);
            h.verify(PASSWORD, null);
        }
        List<Double> real = new ArrayList<>();
        List<Double> dummy = new ArrayList<>();
        for (int i = 0; i < seq; i++) {
            real.add(measure(() -> h.verify(PASSWORD, hash)));
            dummy.add(measure(() -> h.verify(PASSWORD, null)));
        }

        ExecutorService ex = Executors.newFixedThreadPool(12);
        List<Double> concurrentLatencies = Collections.synchronizedList(new ArrayList<>());
        long startNanos = System.nanoTime();
        List<Future<?>> fs = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            fs.add(ex.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    concurrentLatencies.add(measure(() -> h.verify(PASSWORD, hash)));
                }
                return null;
            }));
        }
        for (Future<?> f : fs) {
            f.get();
        }
        double throughput = 4.0 * perThread / ((System.nanoTime() - startNanos) / 1e9);

        CountDownLatch startSignal = new CountDownLatch(1);
        List<Double> overload = Collections.synchronizedList(new ArrayList<>());
        int[] rejections = new int[1];
        fs.clear();
        for (int k = 0; k < 12; k++) {
            fs.add(ex.submit(() -> {
                startSignal.await();
                long a = System.nanoTime();
                try {
                    h.verify(PASSWORD, hash);
                    overload.add((System.nanoTime() - a) / 1e6);
                } catch (ApiException e) {
                    synchronized (rejections) {
                        rejections[0]++;
                    }
                }
                return null;
            }));
        }
        startSignal.countDown();
        for (Future<?> f : fs) {
            f.get();
        }
        ex.shutdown();

        System.out.printf("perfil m=%d KiB t=%d p=1 | cpus=%s memoria_limite=%s%n", m, t, read("/sys/fs/cgroup/cpu.max").trim(),
                read("/sys/fs/cgroup/memory.max").trim());
        System.out.printf("sequencial hash_real  ms: p50=%.1f p95=%.1f max=%.1f (n=%d)%n", percentile(real, 50), percentile(real, 95), max(real), real.size());
        System.out.printf("sequencial ficticio   ms: p50=%.1f p95=%.1f max=%.1f (n=%d)%n", percentile(dummy, 50), percentile(dummy, 95), max(dummy), dummy.size());
        System.out.printf("4 simultaneos         ms: p50=%.1f p95=%.1f max=%.1f | vazao=%.1f verificacoes/s%n", percentile(concurrentLatencies, 50), percentile(concurrentLatencies, 95), max(concurrentLatencies), throughput);
        System.out.printf("12 chegadas (4+fila 8, espera 2 s): concluidas=%d recusadas_503=%d p95_concluidas=%.1f ms max=%.1f ms%n", overload.size(),
                rejections[0], overload.isEmpty() ? 0 : percentile(overload, 95), overload.isEmpty() ? 0 : max(overload));
        System.out.printf("memoria container: pico=%s MiB | heap_max_jvm=%d MiB%n", mib(read("/sys/fs/cgroup/memory.peak")),
                Runtime.getRuntime().maxMemory() / (1024 * 1024));
        System.out.printf("cpu.stat antes: %s%ncpu.stat depois: %s%n", oneLine(cpuBefore), oneLine(read("/sys/fs/cgroup/cpu.stat")));
    }

    private static double measure(Runnable r) {
        long a = System.nanoTime();
        r.run();
        return (System.nanoTime() - a) / 1e6;
    }

    private static double percentile(List<Double> v, int p) {
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        return s.get(Math.max(0, (int) Math.ceil(p / 100.0 * s.size()) - 1));
    }

    private static double max(List<Double> v) {
        return Collections.max(v);
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (Exception e) {
            return "indisponivel";
        }
    }

    private static String mib(String bytes) {
        try {
            return String.valueOf(Long.parseLong(bytes.trim()) / (1024 * 1024));
        } catch (NumberFormatException e) {
            return bytes.trim();
        }
    }

    private static String oneLine(String cpuStat) {
        return cpuStat.replaceAll("\\s+", " ").trim();
    }
}
