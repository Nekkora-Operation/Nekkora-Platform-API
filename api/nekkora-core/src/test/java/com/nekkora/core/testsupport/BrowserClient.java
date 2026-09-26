package com.nekkora.core.testsupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente HTTP de teste que se comporta como um navegador na origem publica: guarda os cookies recebidos em
 * Set-Cookie (inclusive os __Host-, que o CookieManager do JDK nao enviaria por HTTP), envia Origin, o token CSRF
 * do contexto atual e, opcionalmente, X-Forwarded-For (simulando o proxy confiavel).
 */
public final class BrowserClient {

    public record Response(int status, String body, HttpHeaders headers) {
        public String field(String name) {
            Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]*)\"").matcher(body);
            return m.find() ? m.group(1) : null;
        }

        public List<String> listField(String name) {
            Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\":\\[([^\\]]*)]").matcher(body);
            List<String> r = new ArrayList<>();
            if (m.find()) {
                Matcher v = Pattern.compile("\"([^\"]*)\"").matcher(m.group(1));
                while (v.find()) {
                    r.add(v.group(1));
                }
            }
            return r;
        }

        public List<String> setCookies() {
            return headers.allValues("Set-Cookie");
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    private final HttpClient http;
    private final String base;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    public String origin = "https://portal.nekkora.test";
    public String csrf;
    public String forwardedIp;

    public BrowserClient(String base) {
        this(base, HTTP);
    }

    /** Cliente proprio (ex.: E2E por TLS com o certificado descartavel da stack). */
    public BrowserClient(String base, HttpClient http) {
        this.base = base;
        this.http = http;
    }

    public Map<String, String> cookies() {
        return cookies;
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    public Response post(String path, String json) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .POST(json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json");
        return send(b);
    }

    public Response send(HttpRequest.Builder b) {
        b.timeout(Duration.ofSeconds(30));
        if (!cookies.isEmpty()) {
            b.header("Cookie", String.join("; ", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
        }
        if (origin != null) {
            b.header("Origin", origin);
        }
        if (csrf != null) {
            b.header("X-CSRF-Token", csrf);
        }
        if (forwardedIp != null) {
            b.header("X-Forwarded-For", forwardedIp);
        }
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            for (String sc : r.headers().allValues("Set-Cookie")) {
                String[] parts = sc.split(";");
                String[] kv = parts[0].split("=", 2);
                boolean deleteCookie = sc.contains("Max-Age=0");
                if (deleteCookie) {
                    cookies.remove(kv[0].trim());
                } else {
                    cookies.put(kv[0].trim(), kv[1].trim());
                }
            }
            return new Response(r.statusCode(), r.body(), r.headers());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
