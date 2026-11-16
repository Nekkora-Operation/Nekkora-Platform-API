package com.nekkora.agentgateway;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T05 - o que NUNCA e gravado numa linha de registro, mesmo que o processo da automacao
 * (ou o aplicativo da maquina) o tenha escrito: credencial de maquina, codigo de pareamento, cabecalho de autorizacao e o
 * token da propria atribuicao. O aplicativo ja remove antes de enviar; aqui e a segunda barreira.
 *
 * <p>Nao ha promessa sobre outros dados que o proprio robo imprima.
 */
final class LogRedaction {

    static final String MARK = "[removido]";

    /** Credencial de maquina (nka1.) e codigo de pareamento ou de troca (nkp1.). */
    private static final Pattern MACHINE_SECRET = Pattern.compile("nk[ap]1\\.[A-Za-z0-9._~-]+");
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)\\b((?:proxy-)?authorization)\\b(\\s*[:=]\\s*)(?:\\S+)(?:[ \\t]+\\S+)?");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer)[ \\t]+[A-Za-z0-9._~+/=-]{8,}");

    private LogRedaction() {
    }

    /** {@code exact}: valores que esta requisicao conhece e que nao podem aparecer (o token da atribuicao). */
    static String redact(String message, String... exact) {
        String out = message;
        for (String secret : exact) {
            if (secret != null && secret.length() >= 8) {
                out = out.replace(secret, MARK);
            }
        }
        out = MACHINE_SECRET.matcher(out).replaceAll(Matcher.quoteReplacement(MARK));
        out = AUTHORIZATION.matcher(out).replaceAll("$1$2" + Matcher.quoteReplacement(MARK));
        return BEARER.matcher(out).replaceAll("$1 " + Matcher.quoteReplacement(MARK));
    }
}
