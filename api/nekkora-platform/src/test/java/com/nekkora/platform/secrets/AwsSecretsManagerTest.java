package com.nekkora.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Leitura do Secrets Manager: assinatura Signature Version 4 conferida com o exemplo publicado na documentacao da AWS (IAM
 * {@code ListUsers}, 2015-08-30) e, contra um Secrets Manager FALSO local, o pedido {@code GetSecretValue} (alvo, corpo,
 * token de sessao, assinatura recalculada), o valor lido e as falhas sem vazar o segredo nem as credenciais.
 */
class AwsSecretsManagerTest {

    private static final AwsSecretsManager.Credentials EXEMPLO = new AwsSecretsManager.Credentials("AKIDEXAMPLE",
            "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "token-de-sessao-de-teste");
    private static final Instant AGORA = Instant.parse("2026-10-24T12:00:00Z");

    record Pedido(Map<String, String> headers, String body) {
    }

    private HttpServer server;
    private final List<Pedido> pedidos = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String resposta = "{\"Name\":\"nekkora/homologacao/platform/m365\",\"SecretString\":\"{\\\"a\\\":1}\",\"VersionId\":\"v-1\"}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange x) throws IOException {
        Map<String, String> h = new TreeMap<>();
        x.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
        String body;
        try (InputStream in = x.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        pedidos.add(new Pedido(h, body));
        byte[] out = resposta.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(status, out.length);
        x.getResponseBody().write(out);
        x.close();
    }

    private AwsSecretsManager cliente() {
        return new AwsSecretsManager("sa-east-1", EXEMPLO, HttpClient.newHttpClient(), Clock.fixed(AGORA, ZoneOffset.UTC),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), Duration.ofSeconds(5));
    }

    @Test
    void assinaturaConfereComOExemploPublicadoDaAws() {
        Map<String, String> h = new TreeMap<>();
        h.put("content-type", "application/x-www-form-urlencoded; charset=utf-8");
        h.put("host", "iam.amazonaws.com");
        h.put("x-amz-date", "20150830T123600Z");
        String a = AwsSecretsManager.authorization("GET", "/", "Action=ListUsers&Version=2010-05-08", h, new byte[0], "20150830T123600Z",
                "us-east-1", "iam", EXEMPLO);
        assertThat(a).isEqualTo("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/iam/aws4_request, "
                + "SignedHeaders=content-type;host;x-amz-date, Signature=5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7");
    }

    @Test
    void leOSegredoComPedidoAssinadoEOTokenDeSessao() {
        AwsSecretsManager.Secret s = cliente().getSecretString("nekkora/homologacao/platform/m365");
        assertThat(s.value()).isEqualTo("{\"a\":1}");
        assertThat(s.versionId()).isEqualTo("v-1");
        assertThat(s.toString()).doesNotContain("\"a\"");
        assertThat(pedidos).hasSize(1);
        Pedido p = pedidos.get(0);
        assertThat(p.body()).isEqualTo("{\"SecretId\":\"nekkora/homologacao/platform/m365\"}");
        assertThat(p.headers()).containsEntry("x-amz-target", "secretsmanager.GetSecretValue")
                .containsEntry("content-type", "application/x-amz-json-1.1")
                .containsEntry("x-amz-date", "20261024T120000Z")
                .containsEntry("x-amz-security-token", "token-de-sessao-de-teste");
        // a assinatura recebida e a recalculada com os cabecalhos que chegaram
        Map<String, String> assinados = new TreeMap<>();
        for (String k : List.of("content-type", "host", "x-amz-date", "x-amz-security-token", "x-amz-target")) {
            assinados.put(k, p.headers().get(k));
        }
        assertThat(p.headers().get("authorization")).isEqualTo(AwsSecretsManager.authorization("POST", "/", "", assinados,
                p.body().getBytes(StandardCharsets.UTF_8), "20261024T120000Z", "sa-east-1", "secretsmanager", EXEMPLO))
                .startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261024/sa-east-1/secretsmanager/aws4_request, "
                        + "SignedHeaders=content-type;host;x-amz-date;x-amz-security-token;x-amz-target, Signature=");
    }

    @Test
    void recusaDaAwsLevaSoStatusETipoDeErro() {
        status = 400;
        resposta = "{\"__type\":\"com.amazonaws.secretsmanager#AccessDeniedException\",\"Message\":\"User: arn:aws:sts::1:assumed-role/x is not authorized\"}";
        assertThatThrownBy(() -> cliente().getSecretString("nekkora/homologacao/platform/m365"))
                .isInstanceOfSatisfying(AwsSecretsManager.Failure.class, f -> {
                    assertThat(f.status()).isEqualTo(400);
                    assertThat(f.errorType()).isEqualTo("AccessDeniedException");
                    assertThat(f.getMessage()).doesNotContain("arn:").doesNotContain("AKIDEXAMPLE").doesNotContain("token-de-sessao");
                });
    }

    @Test
    void segredoBinarioOuSemTextoERecusado() {
        resposta = "{\"Name\":\"x\",\"SecretBinary\":\"AAAA\",\"VersionId\":\"v-2\"}";
        assertThatThrownBy(() -> cliente().getSecretString("x")).isInstanceOf(AwsSecretsManager.Failure.class).hasMessageContaining("sem SecretString");
    }

    @Test
    void semConexaoFalhaSemDetalhe() {
        server.stop(0);
        assertThatThrownBy(() -> cliente().getSecretString("x")).isInstanceOf(AwsSecretsManager.Failure.class).hasMessageStartingWith("segredo da AWS nao lido");
    }

    @Test
    void soCredenciaisTemporariasERegiaoValida() {
        assertThatThrownBy(() -> AwsSecretsManager.Credentials.fromEnvironment(Map.of("AWS_ACCESS_KEY_ID", "AKIA", "AWS_SECRET_ACCESS_KEY", "s")))
                .hasMessageContaining("AWS_SESSION_TOKEN");
        assertThatThrownBy(() -> AwsSecretsManager.Credentials.fromEnvironment(Map.of())).hasMessageContaining("ausentes");
        assertThat(EXEMPLO.toString()).doesNotContain("AKIDEXAMPLE").doesNotContain("wJal").doesNotContain("token");
        assertThatThrownBy(() -> new AwsSecretsManager("", EXEMPLO, HttpClient.newHttpClient(), Duration.ofSeconds(1))).hasMessageContaining("regiao");
        assertThatThrownBy(() -> new AwsSecretsManager("sa-east-1.evil.test/x", EXEMPLO, HttpClient.newHttpClient(), Duration.ofSeconds(1)))
                .hasMessageContaining("regiao");
    }
}
