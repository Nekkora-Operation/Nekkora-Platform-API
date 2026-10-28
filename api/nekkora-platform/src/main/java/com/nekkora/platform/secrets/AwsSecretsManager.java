package com.nekkora.platform.secrets;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Leitura de UM segredo do AWS Secrets Manager ({@code GetSecretValue}), so na partida (missao do e-mail real em
 * homologacao). Sem SDK: um POST JSON assinado com Signature Version 4, pelo mesmo cliente HTTP (e o mesmo proxy de saida)
 * do transporte. Credenciais TEMPORARIAS do ambiente ({@code AWS_ACCESS_KEY_ID}, {@code AWS_SECRET_ACCESS_KEY},
 * {@code AWS_SESSION_TOKEN}): chave de longo prazo, sem token de sessao, e recusada.
 *
 * <p>Nada do segredo, da assinatura ou das credenciais vai para log, excecao ou {@code toString}: as falhas levam so o
 * status HTTP e o tipo de erro da AWS ({@code __type}, por exemplo {@code AccessDeniedException}).
 */
public final class AwsSecretsManager {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern REGION = Pattern.compile("^[a-z]{2}(-[a-z]+)+-[0-9]$");
    private static final Pattern ERROR_TYPE = Pattern.compile("^[A-Za-z0-9.#:_-]{1,128}$");
    private static final String SERVICE = "secretsmanager";

    /** Credenciais temporarias. {@code toString} nao mostra nenhum valor. */
    public record Credentials(String accessKeyId, String secretAccessKey, String sessionToken) {
        public Credentials {
            if (blank(accessKeyId) || blank(secretAccessKey)) {
                throw new IllegalStateException("credenciais da AWS ausentes (AWS_ACCESS_KEY_ID e AWS_SECRET_ACCESS_KEY)");
            }
            if (blank(sessionToken)) {
                throw new IllegalStateException("credenciais da AWS sem AWS_SESSION_TOKEN: so credenciais temporarias sao aceitas");
            }
        }

        public static Credentials fromEnvironment(Map<String, String> env) {
            return new Credentials(env.get("AWS_ACCESS_KEY_ID"), env.get("AWS_SECRET_ACCESS_KEY"), env.get("AWS_SESSION_TOKEN"));
        }

        @Override
        public String toString() {
            return "Credentials[<temporarias>]";
        }
    }

    /** Segredo lido: o texto e o identificador da versao (este pode ir para o registro; o texto nunca). */
    public record Secret(String value, String versionId) {
        @Override
        public String toString() {
            return "Secret[versao=" + versionId + "]";
        }
    }

    /** Falha da leitura: so o status HTTP e o tipo de erro da AWS. */
    public static final class Failure extends IllegalStateException {
        private final Integer status;
        private final String errorType;

        Failure(String message, Integer status, String errorType) {
            super(message + (status == null ? "" : " (status " + status + (errorType == null ? "" : ", " + errorType) + ")"));
            this.status = status;
            this.errorType = errorType;
        }

        public Integer status() {
            return status;
        }

        public String errorType() {
            return errorType;
        }
    }

    private final String region;
    private final Credentials credentials;
    private final HttpClient http;
    private final Clock clock;
    private final URI endpoint;
    private final Duration timeout;

    public AwsSecretsManager(String region, Credentials credentials, HttpClient http, Duration timeout) {
        this(region, credentials, http, Clock.systemUTC(), null, timeout);
    }

    /** {@code endpoint}: so em teste (servidor falso); nulo = {@code https://secretsmanager.<regiao>.amazonaws.com/}. */
    AwsSecretsManager(String region, Credentials credentials, HttpClient http, Clock clock, URI endpoint, Duration timeout) {
        if (region == null || !REGION.matcher(region).matches()) {
            throw new IllegalStateException("regiao da AWS invalida");
        }
        this.region = region;
        this.credentials = credentials;
        this.http = http;
        this.clock = clock;
        this.endpoint = endpoint != null ? endpoint : URI.create("https://" + SERVICE + "." + region + ".amazonaws.com/");
        this.timeout = timeout;
    }

    /** Valor de texto ({@code SecretString}) do segredo. Segredo binario, vazio ou com erro: falha. */
    public Secret getSecretString(String secretId) {
        if (blank(secretId)) {
            throw new IllegalStateException("identificador do segredo ausente");
        }
        byte[] body = JSON.writeValueAsBytes(Map.of("SecretId", secretId));
        String amzDate = AMZ_DATE.format(clock.instant());
        Map<String, String> headers = new TreeMap<>();
        headers.put("content-type", "application/x-amz-json-1.1");
        headers.put("host", endpoint.getRawAuthority());
        headers.put("x-amz-date", amzDate);
        headers.put("x-amz-security-token", credentials.sessionToken());
        headers.put("x-amz-target", "secretsmanager.GetSecretValue");
        String authorization = authorization("POST", endpoint.getRawPath().isEmpty() ? "/" : endpoint.getRawPath(), "", headers, body, amzDate,
                region, SERVICE, credentials);
        HttpRequest.Builder req = HttpRequest.newBuilder(endpoint).version(HttpClient.Version.HTTP_1_1).timeout(timeout).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach((k, v) -> {
            if (!"host".equals(k)) {
                req.header(k, v);
            }
        });
        req.header("Authorization", authorization);
        HttpResponse<String> r;
        try {
            r = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new Failure("segredo da AWS nao lido: " + e.getClass().getSimpleName(), null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("segredo da AWS nao lido: interrompido", null, null);
        }
        if (r.statusCode() != 200) {
            throw new Failure("segredo da AWS recusado", r.statusCode(), errorType(r.body()));
        }
        JsonNode node;
        try {
            node = JSON.readTree(r.body());
        } catch (RuntimeException e) {
            throw new Failure("resposta do Secrets Manager ilegivel", r.statusCode(), null);
        }
        String value = node.path("SecretString").asString("");
        if (value.isEmpty()) {
            throw new Failure("segredo da AWS sem SecretString", r.statusCode(), null);
        }
        return new Secret(value, node.path("VersionId").asString(""));
    }

    private static String errorType(String body) {
        try {
            String t = JSON.readTree(body).path("__type").asString("");
            int i = t.lastIndexOf('#');
            t = i >= 0 ? t.substring(i + 1) : t;
            return ERROR_TYPE.matcher(t).matches() ? t : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Cabecalho {@code Authorization} de Signature Version 4 ({@code headers}: nomes em minusculas, todos assinados).
     * Conferido no teste com o exemplo publicado na documentacao da AWS.
     */
    static String authorization(String method, String path, String query, Map<String, String> headers, byte[] payload, String amzDate,
                                String region, String service, Credentials c) {
        TreeMap<String, String> sorted = new TreeMap<>(headers);
        StringBuilder canonicalHeaders = new StringBuilder();
        sorted.forEach((k, v) -> canonicalHeaders.append(k).append(':').append(v.trim().replaceAll(" +", " ")).append('\n'));
        String signedHeaders = String.join(";", sorted.keySet());
        String canonicalRequest = method + "\n" + path + "\n" + query + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + hex(sha256(payload));
        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        byte[] k = hmac(("AWS4" + c.secretAccessKey()).getBytes(StandardCharsets.UTF_8), date);
        k = hmac(k, region);
        k = hmac(k, service);
        k = hmac(k, "aws4_request");
        String signature = hex(hmac(k, stringToSign));
        return "AWS4-HMAC-SHA256 Credential=" + c.accessKeyId() + "/" + scope + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    private static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 indisponivel");
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponivel");
        }
    }

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
