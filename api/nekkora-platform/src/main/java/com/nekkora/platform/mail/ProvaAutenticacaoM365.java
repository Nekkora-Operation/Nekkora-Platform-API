package com.nekkora.platform.mail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

import com.nekkora.platform.secrets.AwsSecretsManager;

/**
 * Prova do caminho de autenticacao REAL do transporte (homologacao do e-mail real, relatorio de 2026-10-24), com o mesmo
 * codigo da partida da Platform e SEM enviar mensagem (nao conta no teto de tentativas):
 * <ul>
 * <li>{@code --segredo [--esperado-sha256 <hex>]}: le o segredo do AWS Secrets Manager (Signature Version 4, credenciais
 * TEMPORARIAS do ambiente), confere o formato {@code nekkora-m365/1} e o tenant e carrega o PKCS#12 no transporte (uma
 * chave). Com {@code --esperado-sha256}, compara o SHA-256 do SecretString lido com o do arquivo publicado, sem imprimir
 * nenhum dos dois: e a "leitura utilizavel" exigida antes de apagar a chave do disco (m365-aws.sh confirmar);</li>
 * <li>{@code --token}: o mesmo e, em seguida, o token de aplicacao pela assercao de certificado (o pedido do envio).
 * Confere tenant e aplicativo do token e que ele NAO traz papel de aplicacao (o envio so pode vir do RBAC para Aplicacoes
 * do Exchange, restrito a no-reply).</li>
 * </ul>
 * Ambiente: {@code NEKKORA_M365_TENANT}, {@code NEKKORA_M365_SEGREDO_AWS}, {@code NEKKORA_M365_AWS_REGIAO}, as credenciais
 * da AWS e, opcional, {@code NEKKORA_M365_PROXY}. Imprime so resultados de conferencia, a versao do segredo e a impressao
 * SHA-256 do certificado PUBLICO; nunca o segredo, a senha, a chave, o token ou as credenciais. Codigos: 0 tudo conferido,
 * 3 conteudo diferente do publicado, 5 segredo nao lido (AWS), 6 segredo ou certificado invalido, 7 token recusado ou sem
 * conexao, 8 reivindicacoes do token diferentes do esperado, 64 uso incorreto.
 */
public final class ProvaAutenticacaoM365 {

    private ProvaAutenticacaoM365() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, System.err));
    }

    static int run(String[] args, Map<String, String> env, java.io.PrintStream out, java.io.PrintStream err) {
        Duration timeout = Duration.ofSeconds(10);
        return run(args, env, out, err, id -> new AwsSecretsManager(env.get("NEKKORA_M365_AWS_REGIAO"), AwsSecretsManager.Credentials.fromEnvironment(env),
                GraphMailTransport.client(timeout, env.get("NEKKORA_M365_PROXY")), timeout).getSecretString(id));
    }

    /** {@code leitor}: a leitura do segredo (a real, {@link AwsSecretsManager}; nos testes, um leitor falso). */
    static int run(String[] args, Map<String, String> env, java.io.PrintStream out, java.io.PrintStream err,
                   java.util.function.Function<String, AwsSecretsManager.Secret> leitor) {
        boolean token = has(args, "--token");
        if (!token && !has(args, "--segredo")) {
            err.println("uso: --segredo [--esperado-sha256 <hex>] | --token");
            return 64;
        }
        String esperado = arg(args, "--esperado-sha256");
        if (esperado != null && !esperado.matches("[0-9a-f]{64}")) {
            err.println("--esperado-sha256: 64 digitos hexadecimais em minusculas");
            return 64;
        }
        String tenant = env.get("NEKKORA_M365_TENANT");
        String secretId = env.get("NEKKORA_M365_SEGREDO_AWS");
        if (blank(tenant) || blank(secretId) || blank(env.get("NEKKORA_M365_AWS_REGIAO"))) {
            err.println("NEKKORA_M365_TENANT, NEKKORA_M365_SEGREDO_AWS e NEKKORA_M365_AWS_REGIAO obrigatorios");
            return 64;
        }
        String proxy = env.get("NEKKORA_M365_PROXY");
        Duration timeout = Duration.ofSeconds(10);

        AwsSecretsManager.Secret secret;
        try {
            secret = leitor.apply(secretId);
        } catch (IllegalStateException e) {
            out.println("autenticacao_segredo leitura=FALHOU causa=" + e.getMessage());
            return 5;
        }
        String conteudo = esperado == null ? "NAO_CONFERIDO" : (esperado.equals(sha256(secret.value())) ? "IGUAL" : "DIFERENTE");
        GraphMailTransport t;
        String clientId;
        try {
            M365Segredo m = M365Segredo.ler(secret.value(), tenant);
            clientId = m.clientId();
            try {
                t = new GraphMailTransport(new GraphMailTransport.Config(tenant, m.clientId(), "no-reply@nekkora.com", null,
                        m.senha(), timeout, envOr(env, "NEKKORA_M365_LOGIN_URL", "https://login.microsoftonline.com"),
                        envOr(env, "NEKKORA_M365_GRAPH_URL", "https://graph.microsoft.com"), proxy), m.pkcs12());
            } finally {
                m.apagar();
            }
        } catch (IllegalStateException e) {
            out.println("autenticacao_segredo leitura=OK versao=" + secret.versionId() + " formato=INVALIDO causa=" + e.getMessage());
            return 6;
        }
        out.println("autenticacao_segredo leitura=OK versao=" + secret.versionId() + " formato=OK chave_privada=1 certificado_sha256="
                + t.certificateSha256Hex() + " certificado_dias=" + t.certificateDaysLeft() + " conteudo=" + conteudo);
        if ("DIFERENTE".equals(conteudo)) {
            return 3;
        }
        if (!token) {
            return 0;
        }
        Integer[] falha = new Integer[1];
        GraphMailTransport.TokenInfo info = t.inspecionarToken(falha);
        if (info == null) {
            out.println("autenticacao_token token=RECUSADO_OU_SEM_CONEXAO status=" + falha[0]);
            return 7;
        }
        if (!info.legivel()) {
            out.println("autenticacao_token token=OBTIDO reivindicacoes=ILEGIVEIS");
            return 8;
        }
        boolean tid = tenant.equals(info.tid());
        boolean app = clientId.equals(info.appid());
        out.println("autenticacao_token token=OBTIDO tid=" + (tid ? "OK" : "DIFERENTE") + " aplicativo=" + (app ? "OK" : "DIFERENTE")
                + " aud=" + info.aud().replaceAll("[^A-Za-z0-9:/._-]", "") + " papeis_de_aplicacao=" + info.roles()
                + " expira_em_min=" + info.expiraEmSegundos() / 60);
        return tid && app && info.roles() == 0 ? 0 : 8;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel");
        }
    }

    private static boolean has(String[] args, String name) {
        for (String a : args) {
            if (name.equals(a)) {
                return true;
            }
        }
        return false;
    }

    private static String arg(String[] args, String name) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String envOr(Map<String, String> env, String name, String fallback) {
        String v = env.get(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
