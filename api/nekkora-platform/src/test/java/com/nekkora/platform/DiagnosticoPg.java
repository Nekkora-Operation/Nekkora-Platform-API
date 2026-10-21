package com.nekkora.platform;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nekkora.core.testsupport.RealDatabase;

/**
 * Diagnostico de falha de concorrencia pelo log do PostgreSQL de teste, SANITIZADO para ir aos relatorios e aos artefatos
 * do CI: so as linhas de erro (mensagem com SQLSTATE, restricao ou trigger), CONTEXT e STATEMENT (texto da consulta,
 * com os parametros como $n). A linha DETAIL "Failing row contains (...)" traz os valores da linha recusada (hashes de
 * token, CSRF) e e trocada por um marcador; parametros de consulta nunca sao impressos.
 */
final class DiagnosticoPg {

    private DiagnosticoPg() {
    }

    static List<String> linhas(RealDatabase db) {
        return db.serverLog().lines()
                .filter(l -> l.matches(".*(ERRO|ERROR|CONTEXT|STATEMENT|COMANDO|DETAIL|DETALHE).*"))
                .map(l -> l.matches(".*(DETAIL|DETALHE).*") ? l.replaceAll("(DETAIL|DETALHE):.*", "$1: [omitido: valores da linha]") : l)
                .filter(l -> !l.matches(".*parameters?:.*"))
                .toList();
    }

    private static final Pattern RESTRICAO = Pattern.compile("constraint \"([a-z0-9_]+)\"");

    static void imprimir(RealDatabase db, String rotulo) {
        for (String l : linhas(db)) {
            System.out.println("diagnostico pg " + rotulo + ": " + l);
            Matcher m = RESTRICAO.matcher(l);
            if (m.find()) {
                System.out.println("diagnostico pg " + rotulo + ": restricao " + m.group(1) + " = " + definicao(db, m.group(1)));
            }
        }
    }

    /** Definicao da restricao (nome gerado pelo PostgreSQL nas CHECKs sem nome), so do catalogo. */
    private static String definicao(RealDatabase db, String nome) {
        try (Connection c = db.connectAsSuperuser();
             PreparedStatement ps = c.prepareStatement("SELECT conrelid::regclass || ': ' || pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?")) {
            ps.setString(1, nome);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "(nao encontrada)";
            }
        } catch (Exception e) {
            return "(consulta falhou: " + e.getClass().getSimpleName() + ")";
        }
    }
}
