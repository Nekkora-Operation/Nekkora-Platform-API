package com.nekkora.platform.mail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Microsoft Entra (endpoint de token v2) e Microsoft Graph ({@code sendMail}) FALSOS, locais, para os testes do transporte:
 * registram cada pedido (corpo e cabecalhos) e respondem pelo roteiro programado. Nada sai da maquina.
 */
public final class FakeGraph implements AutoCloseable {

    /** Resposta programada do sendMail: status, Retry-After (ou nulo) e atraso antes de responder. */
    record Reply(int status, String retryAfter, long delayMs) {
        static Reply of(int status) {
            return new Reply(status, null, 0);
        }
    }

    record Request(String path, String authorization, String clientRequestId, String body) {
    }

    final List<Request> tokenRequests = new CopyOnWriteArrayList<>();
    final List<Request> sendRequests = new CopyOnWriteArrayList<>();
    final ConcurrentLinkedDeque<Reply> script = new ConcurrentLinkedDeque<>();
    final AtomicInteger tokenCounter = new AtomicInteger();
    volatile int tokenStatus = 200;
    /** Token de acesso devolvido (nulo: "falso-N"); para a prova de autenticacao, um JWT com as reivindicacoes de controle. */
    volatile String accessToken;
    private final HttpServer server;

    public FakeGraph() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int sends() {
        return sendRequests.size();
    }

    private void handle(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        String body;
        try (InputStream in = x.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Request r = new Request(path, x.getRequestHeaders().getFirst("Authorization"), x.getRequestHeaders().getFirst("client-request-id"), body);
        if (path.endsWith("/oauth2/v2.0/token")) {
            tokenRequests.add(r);
            if (tokenStatus != 200) {
                respond(x, tokenStatus, "{\"error\":\"invalid_client\"}", null);
                return;
            }
            respond(x, 200, "{\"token_type\":\"Bearer\",\"expires_in\":3600,\"access_token\":\"" + (accessToken != null ? accessToken : "falso-" + tokenCounter.incrementAndGet()) + "\"}", null);
            return;
        }
        if (path.startsWith("/v1.0/users/") && path.endsWith("/sendMail")) {
            sendRequests.add(r);
            Reply p = script.pollFirst();
            if (p == null) {
                p = Reply.of(202);
            }
            if (p.delayMs() > 0) {
                try {
                    Thread.sleep(p.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            respond(x, p.status(), "", p.retryAfter());
            return;
        }
        respond(x, 404, "", null);
    }

    private static void respond(HttpExchange x, int status, String body, String retryAfter) throws IOException {
        if (retryAfter != null) {
            x.getResponseHeaders().add("Retry-After", retryAfter);
        }
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream o = x.getResponseBody()) {
                o.write(b);
            }
        }
        x.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** Certificado de TESTE autoassinado (RSA 2048, 2 dias) num PKCS#12 temporario, gerado pelo keytool do JDK. */
    public static Path testCertificate(Path dir, String password) throws Exception {
        Path p12 = dir.resolve("teste-m365.p12");
        Files.deleteIfExists(p12);
        Process k = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair", "-alias", "nekkora-teste",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=nekkora-teste", "-storetype", "PKCS12", "-keystore",
                p12.toString(), "-storepass", password, "-keypass", password).redirectErrorStream(true).start();
        String out = new String(k.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (k.waitFor() != 0) {
            throw new IllegalStateException("keytool falhou: " + out);
        }
        return p12;
    }
}
