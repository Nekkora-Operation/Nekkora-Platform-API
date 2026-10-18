package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.testsupport.RealDatabase;

/**
 * Barreiras controladas para os testes de concorrencia das contas vinculadas (P3 e P4): uma linha travada por uma
 * transacao do superusuario, a requisicao disparada, a espera REAL da Platform pelo lock observada em
 * {@code pg_stat_activity}, a alteracao concorrente e so entao o commit. Nada depende de pausa arbitraria.
 */
final class Barreiras {

    private Barreiras() {
    }

    @FunctionalInterface
    interface SqlDurante {
        void run(Connection c) throws Exception;
    }

    static void sql(Connection c, String s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(s)) {
            ps.execute();
        }
    }

    /** Trava a linha de {@code travaSql} (FOR UPDATE), dispara a acao, espera a Platform esperar o lock, roda {@code durante} e confirma. */
    static <T> T comLinhaTravada(RealDatabase db, String travaSql, Callable<T> acao, SqlDurante durante) throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (Connection c = db.connectAsSuperuser()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(travaSql)) {
                assertThat(ps.executeQuery().next()).as("linha travada").isTrue();
            }
            Future<T> f = ex.submit(acao);
            assertThat(IdentityTestBase.platformWaitingOnLock()).as("a requisicao espera o lock").isTrue();
            durante.run(c);
            c.commit();
            return f.get();
        } finally {
            ex.shutdownNow();
        }
    }

    /** POST com os mesmos cabecalhos do BrowserClient (cookies, Origin, CSRF, IP), mas prazo de 120 s. */
    static BrowserClient.Response postLongo(int port, BrowserClient b, String path, String json) throws Exception {
        java.net.http.HttpRequest.Builder rb = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path))
                .timeout(java.time.Duration.ofSeconds(120)).POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json").header("Origin", b.origin).header("X-Forwarded-For", b.forwardedIp);
        if (b.csrf != null) {
            rb.header("X-CSRF-Token", b.csrf);
        }
        if (!b.cookies().isEmpty()) {
            rb.header("Cookie", String.join("; ", b.cookies().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
        }
        java.net.http.HttpResponse<String> r = java.net.http.HttpClient.newHttpClient().send(rb.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        return new BrowserClient.Response(r.statusCode(), r.body(), r.headers());
    }
}
