package com.nekkora.platform.mail;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Prova de homologacao do transporte real (missao 002, PR 5; desenho de 2026-10-11, §3.3.1 e §8): UMA mensagem de teste
 * por execucao, com o aplicativo e o certificado do ambiente, pelo remetente INFORMADO.
 * <ul>
 * <li>{@code --remetente no-reply@nekkora.com}: exigido 202 (aceite; a entrega e conferida na caixa controlada);</li>
 * <li>{@code --remetente <outra caixa do tenant>}: exigida a RECUSA (o RBAC para Aplicacoes so autoriza a no-reply).</li>
 * </ul>
 * Uso, no container da Platform, com as variaveis {@code NEKKORA_M365_TENANT}, {@code NEKKORA_M365_CLIENT},
 * {@code NEKKORA_M365_CERT} e {@code NEKKORA_M365_CERT_SENHA} (e {@code NEKKORA_M365_PROXY}, se a saida for por proxy):
 * <pre>
 * java -cp app.jar -Dloader.main=com.nekkora.platform.mail.ProvaEnvioM365 org.springframework.boot.loader.launch.PropertiesLauncher \
 *      --remetente no-reply@nekkora.com --para &lt;caixa controlada&gt;
 * </pre>
 * Imprime so o desfecho, o status HTTP e o identificador da mensagem (que aparece no corpo, para achar a mensagem na
 * caixa). Nunca imprime token de acesso, certificado ou senha. Codigo de saida: 0 aceito, 2 recusado, 3 retentavel, 4
 * incerto, 64 uso incorreto.
 */
public final class ProvaEnvioM365 {

    private ProvaEnvioM365() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, System.err));
    }

    /** Uma prova. Devolve o codigo de saida; so o desfecho, o status e o identificador vao para {@code out}. */
    static int run(String[] args, java.util.Map<String, String> env, java.io.PrintStream out, java.io.PrintStream err) {
        String sender = arg(args, "--remetente");
        String to = arg(args, "--para");
        if (sender == null || to == null) {
            err.println("uso: --remetente <caixa> --para <caixa controlada>");
            return 64;
        }
        String cert = env.get("NEKKORA_M365_CERT");
        String pass = env.get("NEKKORA_M365_CERT_SENHA");
        GraphMailTransport t = new GraphMailTransport(new GraphMailTransport.Config(env.get("NEKKORA_M365_TENANT"), env.get("NEKKORA_M365_CLIENT"),
                sender, cert == null ? null : Path.of(cert), pass == null ? new char[0] : pass.toCharArray(), Duration.ofSeconds(10),
                envOr(env, "NEKKORA_M365_LOGIN_URL", "https://login.microsoftonline.com"), envOr(env, "NEKKORA_M365_GRAPH_URL", "https://graph.microsoft.com"),
                env.get("NEKKORA_M365_PROXY")));
        UUID id = UUID.randomUUID();
        MailTransport.Delivery d = t.deliverAs(sender, new MailTransport.Message(id, to, "Prova de homologacao do e-mail da Nekkora",
                "Mensagem de prova do transporte de e-mail da Nekkora (homologacao). Identificador: " + id
                        + "\n\nNao contem link nem codigo. Pode ser descartada depois da conferencia dos cabecalhos."));
        out.println("prova_envio desfecho=" + d.result() + " status=" + d.status() + " identificador=" + id + " certificado_dias="
                + t.certificateDaysLeft());
        return switch (d.result()) {
            case ACEITO -> 0;
            case RECUSADO -> 2;
            case RETENTAVEL -> 3;
            case INCERTO -> 4;
        };
    }

    private static String arg(String[] args, String name) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    private static String envOr(java.util.Map<String, String> env, String name, String fallback) {
        String v = env.get(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
