package com.nekkora.ops.homologacao.p1c;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Evidencia dos testes do P1c: texto SEM segredo (ids sinteticos, instantes, codigos de saida, mensagens do servidor e das
 * ferramentas), anexado em {@code <nekkora.p1c.evidencias>/<grupo>/<nome>.txt} (padrao {@code target/p1c-evidencias}).
 * Senhas, chaves e tokens nunca passam por aqui; a varredura do pacote confere.
 */
final class Evidencia {

    private Evidencia() {
    }

    static Path dir(String grupo) {
        Path d = Path.of(System.getProperty("nekkora.p1c.evidencias", "target/p1c-evidencias")).resolve(grupo);
        try {
            Files.createDirectories(d);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return d;
    }

    static synchronized void anexar(String grupo, String nome, String texto) {
        try {
            Files.writeString(dir(grupo).resolve(nome + ".txt"), "== " + Instant.now() + "\n" + texto + (texto.endsWith("\n") ? "" : "\n"),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
