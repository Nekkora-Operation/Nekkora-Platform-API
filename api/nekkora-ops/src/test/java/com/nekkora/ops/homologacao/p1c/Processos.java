package com.nekkora.ops.homologacao.p1c;


import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Processos externos dos testes da homologacao (P1c): Python (bibliotecas reais de ops/homologacao/lib), bash do Git
 * (runner real) e o cliente Docker. Sem shell intermediario: argumentos passados como lista.
 */
final class Processos {

    private Processos() {
    }

    record Resultado(int codigo, String saida, String erro) {
    }

    static final Path REPO = OpsBanco.raizDoRepositorio();
    static final Path HOMOLOGACAO = REPO.resolve("ops/homologacao");

    static Resultado rodar(List<String> cmd, String entrada, Map<String, String> env, Path dir, long timeoutS) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (env != null) {
                pb.environment().putAll(env);
            }
            if (dir != null) {
                pb.directory(dir.toFile());
            }
            Process p = pb.start();
            CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> ler(p.getInputStream()));
            CompletableFuture<byte[]> err = CompletableFuture.supplyAsync(() -> ler(p.getErrorStream()));
            if (entrada != null) {
                p.getOutputStream().write(entrada.getBytes(StandardCharsets.UTF_8));
            }
            p.getOutputStream().close();
            if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("tempo esgotado: " + cmd.get(0));
            }
            return new Resultado(p.exitValue(), new String(out.get(), StandardCharsets.UTF_8), new String(err.get(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("processo falhou: " + cmd.get(0), e);
        }
    }

    private static byte[] ler(java.io.InputStream in) {
        try {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** python ops/homologacao/lib/&lt;script&gt; args, com a entrada padrao indicada. */
    static Resultado python(String script, String entrada, String... args) {
        List<String> cmd = new ArrayList<>(List.of("python", HOMOLOGACAO.resolve("lib").resolve(script).toString()));
        cmd.addAll(List.of(args));
        return rodar(cmd, entrada, null, null, 120);
    }

    /**
     * bash do Git (no Windows, "bash" no PATH pode ser o do WSL, que nao enxerga o Docker Desktop nem o Python do host).
     * Propriedade nekkora.bash sobrepoe. Sem bash utilizavel, FALHA (nunca pula o teste).
     */
    static String bash() {
        String p = System.getProperty("nekkora.bash");
        if (p != null) {
            return p;
        }
        if (File.separatorChar == '\\') {
            for (String c : List.of("C:/Program Files/Git/bin/bash.exe", "C:/Program Files (x86)/Git/bin/bash.exe")) {
                if (Files.isExecutable(Path.of(c))) {
                    return c;
                }
            }
            throw new IllegalStateException("bash do Git nao encontrado (defina -Dnekkora.bash=...)");
        }
        return "bash";
    }

    /** Executavel real do cliente Docker (primeiro "docker"/"docker.exe" do PATH do processo Java). */
    static String dockerReal() {
        for (String d : System.getenv("PATH").split(File.pathSeparator)) {
            for (String n : List.of("docker.exe", "docker")) {
                Path c = Path.of(d, n);
                if (Files.isRegularFile(c) && Files.isExecutable(c)) {
                    return c.toString();
                }
            }
        }
        throw new IllegalStateException("cliente docker nao encontrado no PATH");
    }

    /** Sub-redes de TODAS as redes Docker existentes (somente leitura: docker network ls/inspect). */
    static String subredesExistentes() {
        Resultado ids = rodar(List.of(dockerReal(), "network", "ls", "-q"), null, null, null, 60);
        List<String> cmd = new ArrayList<>(List.of(dockerReal(), "network", "inspect", "-f", "{{range .IPAM.Config}}{{println .Subnet}}{{end}}"));
        cmd.addAll(ids.saida().lines().filter(l -> !l.isBlank()).toList());
        Resultado r = rodar(cmd, null, null, null, 60);
        if (r.codigo() != 0) {
            throw new IllegalStateException("docker network inspect falhou");
        }
        return r.saida();
    }

    /** A sub-rede de teste NAO pode sobrepor nenhuma rede existente (lib/redes.py real, a mesma regra do runner). */
    static void exigirSemSobreposicao(String cidr) {
        Resultado r = python("redes.py", subredesExistentes(), "sobreposicao", cidr);
        if (r.codigo() != 0) {
            throw new IllegalStateException("sub-rede de teste " + cidr + " sobrepoe rede existente: " + r.saida().trim());
        }
    }
}
