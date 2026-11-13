package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;

/**
 * Prazos do login do aplicativo desktop (contrato desktop-login/1), encurtados so neste contexto para caberem num teste:
 * pedido 2 s, codigo 1 s, sessao 3 s. Em producao: 5 min, 60 s e 15 min, sem renovacao. O vencimento e do Redis real.
 */
class DesktopLoginPrazosIT extends IdentityTestBase {

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.desktop-login.enabled", () -> "true");
        r.add("nekkora.desktop-login.request-ttl", () -> "2s");
        r.add("nekkora.desktop-login.code-ttl", () -> "1s");
        r.add("nekkora.desktop-login.session-ttl", () -> "3s");
    }

    private final String verifier = Tokens.newToken();
    private final String state = Tokens.newToken();
    private final String redirect = "http://127.0.0.1:41234/callback";

    private String pedido() {
        return "{\"responseType\":\"code\",\"clientId\":\"nekkora-agent-client\",\"redirectUri\":\"" + redirect + "\",\"state\":\"" + state
                + "\",\"codeChallenge\":\"" + DesktopLoginIT.s256(verifier) + "\",\"codeChallengeMethod\":\"S256\",\"scope\":\"identity\"}";
    }

    private BrowserClient logadoComPedido(String email) {
        BrowserClient b = client();
        assertThat(b.post("/api/v1/desktop/authorizations", pedido()).status()).isEqualTo(204);
        assertThat(login(b, email, SENHA).field("status")).isEqualTo("authenticated");
        return b;
    }

    private BrowserClient.Response trocar(String code) {
        BrowserClient a = browser();
        a.origin = null;
        String body = "grant_type=authorization_code&code=" + code + "&redirect_uri=" + URLEncoder.encode(redirect, StandardCharsets.UTF_8)
                + "&client_id=nekkora-agent-client&code_verifier=" + verifier;
        return a.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/desktop/token"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/x-www-form-urlencoded"));
    }

    private static String code(BrowserClient.Response approve) {
        Matcher m = Pattern.compile("[?&]code=([^&\"]*)").matcher(approve.body());
        assertThat(m.find()).as(approve.body()).isTrue();
        return m.group(1);
    }

    @Test
    void pedidoVencidoNaoEAprovado() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        BrowserClient b = logadoComPedido(e);
        pause(2600);
        assertThat(b.get("/api/v1/desktop/authorization").status()).isEqualTo(404);
        assertThat(b.post("/api/v1/desktop/authorization/approve", null).status()).isEqualTo(404);
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + id + "' AND event_type = 21")).isZero();
    }

    @Test
    void codigoVencidoNaoETrocado() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        BrowserClient b = logadoComPedido(e);
        String code = code(b.post("/api/v1/desktop/authorization/approve", null));
        pause(1600);
        BrowserClient.Response r = trocar(code);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body()).isEqualTo("{\"error\":\"invalid_grant\"}");
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + id + "' AND event_type = 22")).isZero();
    }

    /** A sessao do aplicativo acaba no prazo, sem renovacao: usa-la nao a estende. */
    @Test
    void sessaoDoAplicativoVenceSemRenovacao() throws Exception {
        String e = email();
        confirmed(e, SENHA);
        BrowserClient b = logadoComPedido(e);
        BrowserClient.Response t = trocar(code(b.post("/api/v1/desktop/authorization/approve", null)));
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        assertThat(t.body()).contains("\"expires_in\":3");
        String access = t.field("access_token");
        BrowserClient a = browser();
        a.origin = null;
        HttpRequest.Builder get = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/desktop/session")).GET().header("Authorization", "Bearer " + access);
        assertThat(a.send(get).status()).isEqualTo(200);
        pause(1700);
        assertThat(a.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/desktop/session")).GET().header("Authorization", "Bearer " + access)).status())
                .as("usada no meio do prazo").isEqualTo(200);
        pause(1700);
        assertThat(a.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/desktop/session")).GET().header("Authorization", "Bearer " + access)).status())
                .as("o uso nao estendeu o prazo").isEqualTo(401);
        assertThat(b.get("/api/v1/me/session").status()).as("a sessao do navegador nao muda de prazo").isEqualTo(200);
    }
}
