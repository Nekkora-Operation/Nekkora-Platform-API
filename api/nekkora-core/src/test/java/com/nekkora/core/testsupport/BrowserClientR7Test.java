package com.nekkora.core.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A repeticao do 429 do R7 no cliente de teste: espera, repete uma vez, sem duplicar Cookie e CSRF; desligada por padrao. */
class BrowserClientR7Test {

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<List<String>> cookieHeaders = new CopyOnWriteArrayList<>();
    private final List<List<String>> csrfHeaders = new CopyOnWriteArrayList<>();
    private volatile String firstBody = "{\"error\":\"rate_limited\"}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/x", ex -> {
            cookieHeaders.add(ex.getRequestHeaders().get("Cookie"));
            csrfHeaders.add(ex.getRequestHeaders().get("X-CSRF-Token"));
            boolean first = calls.getAndIncrement() == 0;
            byte[] body = (first ? firstBody : "{\"ok\":true}").getBytes(StandardCharsets.UTF_8);
            if (first) {
                ex.getResponseHeaders().add("Retry-After", "1");
            }
            ex.sendResponseHeaders(first ? 429 : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private BrowserClient client() {
        BrowserClient c = new BrowserClient("http://127.0.0.1:" + server.getAddress().getPort());
        c.cookies().put("nk_sessao", "abc");
        c.csrf = "tok";
        return c;
    }

    @Test
    void desligadoPorPadraoO429ChegaAoTeste() {
        BrowserClient.Response r = client().get("/x");
        assertThat(r.status()).isEqualTo(429);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void ligadoEsperaERepeteUmaVezComOsMesmosCabecalhos() {
        BrowserClient c = client();
        c.respeitarR7 = true;
        BrowserClient.Response r = c.get("/x");
        assertThat(r.status()).isEqualTo(200);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(cookieHeaders.get(1)).as("um Cookie so, igual ao primeiro").isEqualTo(cookieHeaders.get(0)).hasSize(1);
        assertThat(csrfHeaders.get(1)).as("um CSRF so").containsExactly("tok");
        assertThat(c.respeitarR7).isTrue();
    }

    @Test
    void limiteComMotivoProprioNaoEhRepetido() {
        firstBody = "{\"error\":\"rate_limited\",\"reason\":\"remote_dependency_check_too_soon\"}";
        BrowserClient c = client();
        c.respeitarR7 = true;
        assertThat(c.get("/x").status()).isEqualTo(429);
        assertThat(calls.get()).isEqualTo(1);
    }
}
