package com.nekkora.platform.mail;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import com.nekkora.platform.secrets.AwsSecretsManager;

/**
 * Prova de homologacao do transporte real (missao 002, PR 5; desenho de 2026-10-11, §3.3.1 e §8): UMA mensagem de teste
 * por execucao, com o aplicativo e o certificado do ambiente, pelo remetente INFORMADO.
 * <ul>
 * <li>{@code --remetente no-reply@nekkora.com}: exigido 202 (aceite; a entrega e conferida na caixa controlada);</li>
 * <li>{@code --remetente <outra caixa do tenant>}: exigida a RECUSA (o RBAC para Aplicacoes so autoriza a no-reply).</li>
 * </ul>
 * Uso, no container da Platform, com as variaveis do ambiente: {@code NEKKORA_M365_TENANT} e, para cliente e certificado,
 * {@code NEKKORA_M365_SEGREDO_AWS} e {@code NEKKORA_M365_AWS_REGIAO} (com as credenciais temporarias da AWS) ou
 * {@code NEKKORA_M365_CLIENT}, {@code NEKKORA_M365_CERT} e {@code NEKKORA_M365_CERT_SENHA}; {@code NEKKORA_M365_PROXY}, se a
 * saida for por proxy. Com {@code NEKKORA_MAIL_DESTINATARIOS_PERMITIDOS} definida (obrigatoria em {@code ensaio-*}), o
 * {@code --para} tem de estar na lista; fora dela, nada e enviado (codigo 64):
 * <pre>
 * java -cp app.jar -Dloader.main=com.nekkora.platform.mail.ProvaEnvioM365 org.springframework.boot.loader.launch.PropertiesLauncher \
 *      --remetente no-reply@nekkora.com --para &lt;caixa controlada&gt;
 * </pre>
 * Imprime so o desfecho, o status HTTP e o identificador da mensagem (que aparece no corpo, para achar a mensagem na
 * caixa). Nunca imprime token de acesso, certificado ou senha. Com {@code NEKKORA_MAIL_TENTATIVAS_DIR} e
 * {@code NEKKORA_MAIL_TENTATIVAS_MAXIMO} (obrigatorios em {@code ensaio-*}), a prova conta no MESMO teto global da fila
 * ({@link TentativasEnvio}). Codigo de saida: 0 aceito, 2 recusado (inclusive sem vaga no teto), 3 retentavel, 4 incerto,
 * 64 uso incorreto, 65 teto ja atingido antes de tentar.
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
        java.util.Set<String> allowed = DestinatariosPermitidos.ler(env.get("NEKKORA_MAIL_DESTINATARIOS_PERMITIDOS"));
        String environment = env.get("NEKKORA_AMBIENTE");
        if (allowed.isEmpty() && environment != null && CaixaHomologacaoMailSender.AMBIENTE.matcher(environment).matches()) {
            err.println("ensaio-* exige NEKKORA_MAIL_DESTINATARIOS_PERMITIDOS");
            return 64;
        }
        if (!allowed.isEmpty() && !DestinatariosPermitidos.permitido(allowed, to)) {
            err.println("--para fora de NEKKORA_MAIL_DESTINATARIOS_PERMITIDOS: nada enviado");
            return 64;
        }
        // o mesmo teto global da fila (mesmo diretorio no mesmo container): sem vaga, nada e enviado
        TentativasEnvio teto;
        try {
            teto = TentativasEnvio.daConfiguracao(environment, env.get("NEKKORA_MAIL_TENTATIVAS_DIR"), env.get("NEKKORA_MAIL_TENTATIVAS_MAXIMO"), "prova");
        } catch (IllegalStateException e) {
            err.println(e.getMessage());
            return 64;
        }
        if (teto.limitado() && teto.usadas() >= teto.maximo()) {
            out.println("prova_envio teto_atingido usadas=" + teto.usadas() + " maximo=" + teto.maximo() + " (nada enviado)");
            return 65;
        }
        GraphMailTransport t = transport(env, sender);
        if (teto.limitado()) {
            t.limitarTentativas(teto);
        }
        UUID id = UUID.randomUUID();
        MailTransport.Delivery d = t.deliverAs(sender, new MailTransport.Message(id, to, "Prova de homologacao do e-mail da Nekkora",
                "Mensagem de prova do transporte de e-mail da Nekkora (homologacao). Identificador: " + id
                        + "\n\nNao contem link nem codigo. Pode ser descartada depois da conferencia dos cabecalhos."));
        out.println("prova_envio desfecho=" + d.result() + " status=" + d.status() + " identificador=" + id + " certificado_dias="
                + t.certificateDaysLeft() + (teto.limitado() ? " tentativas_usadas=" + teto.usadas() + " maximo=" + teto.maximo() : ""));
        return switch (d.result()) {
            case ACEITO -> 0;
            case RECUSADO -> 2;
            case RETENTAVEL -> 3;
            case INCERTO -> 4;
        };
    }

    /** Cliente e certificado do segredo da AWS (se configurado) ou do arquivo PKCS#12; a mesma regra da partida da Platform. */
    private static GraphMailTransport transport(java.util.Map<String, String> env, String sender) {
        String tenant = env.get("NEKKORA_M365_TENANT");
        String proxy = env.get("NEKKORA_M365_PROXY");
        Duration timeout = Duration.ofSeconds(10);
        String login = envOr(env, "NEKKORA_M365_LOGIN_URL", "https://login.microsoftonline.com");
        String graph = envOr(env, "NEKKORA_M365_GRAPH_URL", "https://graph.microsoft.com");
        String secretId = env.get("NEKKORA_M365_SEGREDO_AWS");
        if (secretId != null && !secretId.isBlank()) {
            AwsSecretsManager.Secret secret = new AwsSecretsManager(env.get("NEKKORA_M365_AWS_REGIAO"), AwsSecretsManager.Credentials.fromEnvironment(env),
                    GraphMailTransport.client(timeout, proxy), timeout).getSecretString(secretId);
            M365Segredo m = M365Segredo.ler(secret.value(), tenant);
            try {
                return new GraphMailTransport(new GraphMailTransport.Config(tenant, m.clientId(), sender, null, m.senha(), timeout, login, graph, proxy),
                        m.pkcs12());
            } finally {
                m.apagar();
            }
        }
        String cert = env.get("NEKKORA_M365_CERT");
        String pass = env.get("NEKKORA_M365_CERT_SENHA");
        return new GraphMailTransport(new GraphMailTransport.Config(tenant, env.get("NEKKORA_M365_CLIENT"), sender, cert == null ? null : Path.of(cert),
                pass == null ? new char[0] : pass.toCharArray(), timeout, login, graph, proxy));
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
