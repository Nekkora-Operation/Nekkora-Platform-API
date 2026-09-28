package com.nekkora.ops.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Log local da CLI (contrato 7): so categorias sanitizadas - ato, id da solicitacao, classe do resultado, codigo do
 * motivo, SQLSTATE e etapa. Nunca mensagem do servidor, token, codigo de uso unico, chave ou caminho de credencial.
 * {@link #alerta} marca o que exige atencao humana (resultado desconhecido, registro separado indisponivel).
 */
public final class LogLocal {

    private static final Pattern CODIGO = Pattern.compile("^[A-Z0-9_]{1,40}$");

    private final Path arquivo;
    private final List<String> alertas = new ArrayList<>();

    public LogLocal(Path arquivo) {
        this.arquivo = arquivo;
    }

    public synchronized void registrar(String ato, UUID solicitacao, String classe, String motivo, String sqlstate, String etapa) {
        String linha = Instant.now().truncatedTo(ChronoUnit.SECONDS) + " ato=" + codigo(ato) + " solicitacao="
                + (solicitacao == null ? "-" : solicitacao) + " resultado=" + codigo(classe) + " motivo=" + codigo(motivo)
                + " sqlstate=" + codigo(sqlstate) + " etapa=" + codigo(etapa);
        escrever(linha);
    }

    public synchronized void alerta(String ato, UUID solicitacao, String categoria) {
        String linha = Instant.now().truncatedTo(ChronoUnit.SECONDS) + " ALERTA ato=" + codigo(ato) + " solicitacao="
                + (solicitacao == null ? "-" : solicitacao) + " categoria=" + codigo(categoria);
        alertas.add(linha);
        escrever(linha);
        System.err.println("ALERTA nekkora-ops: " + codigo(categoria) + " (ato " + codigo(ato) + ") - verifique o estado da solicitacao");
    }

    public synchronized List<String> alertas() {
        return List.copyOf(alertas);
    }

    public Path arquivo() {
        return arquivo;
    }

    static String codigo(String s) {
        if (s == null) {
            return "-";
        }
        return CODIGO.matcher(s).matches() ? s : "NAO_CATEGORIZADO";
    }

    private void escrever(String linha) {
        try {
            Files.writeString(arquivo, linha + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("ALERTA nekkora-ops: log local indisponivel");
        }
    }
}
