package com.nekkora.platform.mail;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transporte Microsoft Graph so de aplicacao (desenho aprovado de 2026-10-11, DC-M2.2 a DC-M2.4): token por client
 * credentials com ASSERCAO DE CERTIFICADO (nenhum segredo de cliente), aplicativo de um unico tenant, envio por
 * {@code POST {graph}/v1.0/users/{remetente}/sendMail} com {@code saveToSentItems=false}. A autorizacao de enviar so como o
 * remetente vem do RBAC para Aplicacoes do Exchange Online (escopo de uma caixa); este codigo nao a amplia nem a confere.
 *
 * <p>Assercao (documentacao vigente de credenciais de certificado do Microsoft Entra): cabecalho {@code alg=PS256},
 * {@code typ=JWT}, {@code x5t#S256} (SHA-256 do DER do certificado); reivindicacoes {@code aud} (endpoint de token v2 do
 * tenant), {@code iss}={@code sub}=client id, {@code jti} unico, {@code nbf}/{@code iat} agora e {@code exp} em 5 min.
 * O token de acesso fica SO em memoria, renovado 5 min antes de vencer; nunca vai para log, resposta ou banco.
 *
 * <p>Desfechos (desenho §6.1): 202 -> ACEITO; 400/401/403/404 e demais 4xx -> RECUSADO; 429/503/504 -> RETENTAVEL (com
 * {@code Retry-After}); 500/502 e outros 5xx -> INCERTO (o provedor pode ter processado); conexao recusada ou tempo de
 * CONEXAO esgotado (nada foi enviado) -> RETENTAVEL; tempo de RESPOSTA esgotado ou conexao cortada depois do envio ->
 * INCERTO. Falha ao obter o token: RETENTAVEL se for de rede ou 5xx do Entra, RECUSADO se o Entra recusar (4xx).
 * Log: so evento, status e correlacao.
 */
public final class GraphMailTransport implements MailTransport {

    private static final Logger LOG = LoggerFactory.getLogger(GraphMailTransport.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration RENEW_BEFORE = Duration.ofMinutes(5);

    /** Configuracao (todo valor vem do ambiente; nada versionado). */
    public record Config(String tenantId, String clientId, String sender, Path certificate, char[] certificatePassword, Duration timeout,
                         String loginBase, String graphBase, String proxy) {
        public Config(String tenantId, String clientId, String sender, Path certificate, char[] certificatePassword, Duration timeout,
                      String loginBase, String graphBase) {
            this(tenantId, clientId, sender, certificate, certificatePassword, timeout, loginBase, graphBase, null);
        }

        @Override
        public String toString() {
            return "Config[tenant=" + tenantId + ", sender=<configurado>]";
        }
    }

    private final Config cfg;
    private final HttpClient http;
    private final Clock clock;
    private final PrivateKey key;
    private final X509Certificate certificate;
    private final String thumbprintS256;
    private volatile String accessToken;
    private volatile Instant accessTokenRenewAt = Instant.EPOCH;

    public GraphMailTransport(Config cfg) {
        this(cfg, client(cfg), Clock.systemUTC());
    }

    /**
     * Cliente HTTP. {@code proxy} ({@code host:porta}, opcional): saida por um proxy que so libera os endpoints do Entra e do
     * Graph, para ambientes cuja rede da Platform nao tem saida direta (a homologacao isolada).
     */
    private static HttpClient client(Config cfg) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(cfg.timeout()).followRedirects(HttpClient.Redirect.NEVER);
        if (cfg.proxy() != null && !cfg.proxy().isBlank()) {
            int i = cfg.proxy().lastIndexOf(':');
            if (i <= 0 || i == cfg.proxy().length() - 1) {
                throw new IllegalStateException("nekkora.mail.m365.proxy deve ser host:porta");
            }
            b.proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress(cfg.proxy().substring(0, i), Integer.parseInt(cfg.proxy().substring(i + 1)))));
        }
        return b.build();
    }

    GraphMailTransport(Config cfg, HttpClient http, Clock clock) {
        require(cfg.tenantId(), "nekkora.mail.m365.tenant-id");
        require(cfg.clientId(), "nekkora.mail.m365.client-id");
        require(cfg.sender(), "nekkora.mail.remetente");
        if (cfg.certificate() == null || !Files.isReadable(cfg.certificate())) {
            throw new IllegalStateException("nekkora.mail.m365.certificado ausente ou ilegivel");
        }
        this.cfg = cfg;
        this.http = http;
        this.clock = clock;
        try (InputStream in = Files.newInputStream(cfg.certificate())) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, cfg.certificatePassword());
            String alias = null;
            for (Enumeration<String> e = ks.aliases(); e.hasMoreElements();) {
                String a = e.nextElement();
                if (ks.isKeyEntry(a)) {
                    if (alias != null) {
                        throw new IllegalStateException("certificado do transporte com mais de uma chave");
                    }
                    alias = a;
                }
            }
            if (alias == null) {
                throw new IllegalStateException("certificado do transporte sem chave privada");
            }
            this.key = (PrivateKey) ks.getKey(alias, cfg.certificatePassword());
            this.certificate = (X509Certificate) ks.getCertificate(alias);
            this.thumbprintS256 = b64url(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("certificado do transporte nao carregado (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Dias ate o vencimento do certificado (alarme de 60 e 30 dias, DC-M2.4). */
    public long certificateDaysLeft() {
        return Duration.between(clock.instant(), certificate.getNotAfter().toInstant()).toDays();
    }

    /** Impressao digital SHA-256 do certificado (registro de troca: nunca a chave). */
    public String certificateThumbprint() {
        return thumbprintS256;
    }

    @Override
    public Delivery deliver(Message m) {
        String token;
        try {
            token = token();
        } catch (TokenFailure f) {
            LOG.warn("evento=mail.token_falhou status={} desfecho={} correlacao={}", f.status, f.result, m.correlation());
            return new Delivery(f.result, f.status, null);
        }
        return send(m, token, cfg.sender());
    }

    /**
     * Envio por um remetente EXPLICITO. So para a prova de homologacao (envio pela caixa autorizada aceito; por outra caixa,
     * recusado pelo RBAC): o transporte da fila sempre usa o remetente configurado.
     */
    public Delivery deliverAs(String sender, Message m) {
        require(sender, "remetente");
        String token;
        try {
            token = token();
        } catch (TokenFailure f) {
            return new Delivery(f.result, f.status, null);
        }
        return send(m, token, sender);
    }

    private Delivery send(Message m, String token, String sender) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("subject", m.subject());
        message.put("body", Map.of("contentType", "Text", "content", m.body()));
        message.put("toRecipients", List.of(Map.of("emailAddress", Map.of("address", m.recipient()))));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        payload.put("saveToSentItems", false);
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.graphBase() + "/v1.0/users/" + URLEncoder.encode(sender, StandardCharsets.UTF_8) + "/sendMail"))
                .timeout(cfg.timeout())
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("client-request-id", m.correlation().toString())
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
                .build();
        HttpResponse<Void> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (HttpConnectTimeoutException | ConnectException e) {
            LOG.info("evento=mail.graph_sem_conexao desfecho=RETENTAVEL correlacao={}", m.correlation());
            return new Delivery(Result.RETENTAVEL, null, null);
        } catch (IOException e) {
            LOG.warn("evento=mail.graph_incerto causa={} correlacao={}", e.getClass().getSimpleName(), m.correlation());
            return new Delivery(Result.INCERTO, null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Delivery(Result.INCERTO, null, null);
        }
        int s = r.statusCode();
        Result result;
        if (s == 202) {
            result = Result.ACEITO;
        } else if (s == 429 || s == 503 || s == 504) {
            result = Result.RETENTAVEL;
        } else if (s >= 400 && s < 500) {
            result = Result.RECUSADO;
            if (s == 401) {
                accessToken = null;
                accessTokenRenewAt = Instant.EPOCH;
            }
        } else {
            result = Result.INCERTO;
        }
        Duration retryAfter = r.headers().firstValue("Retry-After").flatMap(GraphMailTransport::seconds).orElse(null);
        LOG.info("evento=mail.graph status={} desfecho={} correlacao={}", s, result, m.correlation());
        return new Delivery(result, s, retryAfter);
    }

    private static java.util.Optional<Duration> seconds(String v) {
        try {
            long n = Long.parseLong(v.trim());
            return n >= 0 && n <= 3600 ? java.util.Optional.of(Duration.ofSeconds(n)) : java.util.Optional.empty();
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    // ------------------------------------------------------------------ token de aplicacao

    private static final class TokenFailure extends Exception {
        final Result result;
        final Integer status;

        TokenFailure(Result result, Integer status) {
            super(null, null, false, false);
            this.result = result;
            this.status = status;
        }
    }

    private synchronized String token() throws TokenFailure {
        Instant now = clock.instant();
        if (accessToken != null && now.isBefore(accessTokenRenewAt)) {
            return accessToken;
        }
        String form = "client_id=" + enc(cfg.clientId())
                + "&scope=" + enc(cfg.graphBase().replaceAll("/+$", "") + "/.default")
                + "&client_assertion_type=" + enc("urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
                + "&client_assertion=" + enc(assertion(now))
                + "&grant_type=client_credentials";
        HttpRequest req = HttpRequest.newBuilder(URI.create(tokenEndpoint()))
                .timeout(cfg.timeout())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new TokenFailure(Result.RETENTAVEL, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenFailure(Result.RETENTAVEL, null);
        }
        if (r.statusCode() != 200) {
            throw new TokenFailure(r.statusCode() >= 500 ? Result.RETENTAVEL : Result.RECUSADO, r.statusCode());
        }
        JsonNode body = JSON.readTree(r.body());
        String t = body.path("access_token").asString("");
        long expiresIn = body.path("expires_in").asLong(0);
        if (t.isEmpty() || expiresIn <= 0) {
            throw new TokenFailure(Result.RECUSADO, r.statusCode());
        }
        accessToken = t;
        accessTokenRenewAt = now.plusSeconds(expiresIn).minus(RENEW_BEFORE);
        return t;
    }

    private String tokenEndpoint() {
        return cfg.loginBase().replaceAll("/+$", "") + "/" + cfg.tenantId() + "/oauth2/v2.0/token";
    }

    /** Assercao de cliente PS256 com x5t#S256 (credenciais de certificado do Microsoft Entra). */
    String assertion(Instant now) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "PS256");
        header.put("typ", "JWT");
        header.put("x5t#S256", thumbprintS256);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("aud", tokenEndpoint());
        claims.put("iss", cfg.clientId());
        claims.put("sub", cfg.clientId());
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("nbf", now.getEpochSecond());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(Duration.ofMinutes(5)).getEpochSecond());
        String signingInput = b64url(JSON.writeValueAsBytes(header)) + "." + b64url(JSON.writeValueAsBytes(claims));
        try {
            Signature sig = Signature.getInstance("RSASSA-PSS");
            sig.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
            sig.initSign(key);
            sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + b64url(sig.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("assinatura da assercao do transporte falhou");
        }
    }

    private static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void require(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(name + " ausente");
        }
    }
}
