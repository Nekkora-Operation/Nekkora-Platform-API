package com.nekkora.platform.auth.crypto;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Gera o hash PHC Argon2id de uma senha SINTETICA, com o PasswordHasher real, para a fixture da homologacao
 * (ops/homologacao). Le a senha da entrada padrao (nunca de argumento de linha de comando, que apareceria na lista
 * de processos) e escreve so o PHC. Argumentos: memoriaKiB iteracoes (os da configuracao da Platform).
 */
public final class PhcTool {

    private PhcTool() {
    }

    public static void main(String[] args) throws Exception {
        int m = Integer.parseInt(args[0]);
        int t = Integer.parseInt(args[1]);
        String password = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("senha ausente na entrada padrao");
        }
        System.out.println(new PasswordHasher(new PasswordHasher.Parameters(m, t, 1), 1, 1, 5000).generate(password.toCharArray()));
    }
}
