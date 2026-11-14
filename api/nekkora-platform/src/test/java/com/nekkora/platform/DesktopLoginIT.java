package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;

/**
 * Login do aplicativo desktop pelo navegador (AGENT-CLIENT-LOGIN-001, contrato desktop-login/1), na Platform real: papel
 * nekkora_platform, PostgreSQL com a 037 e Redis reais. O "navegador" e o {@link BrowserClient} (cookies, Origin e CSRF); o
 * "aplicativo" e um cliente SEM Origin e SEM cookies, que so apresenta o codigo com o verificador, ou o token Bearer. Cada
 * recusa confere a resposta e o que ficou (ou nao ficou) emitido.
 */
class DesktopLoginIT extends IdentityTestBase {

    private static final String AUTHORIZATIONS = "/api/v1/desktop/authorizations";
    private static final String AUTHORIZATION = "/api/v1/desktop/authorization";
    private static final String APPROVE = "/api/v1/desktop/authorization/approve";
    private static final String DENY = "/api/v1/desktop/authorization/deny";
    private static final String TOKEN = "/api/v1/desktop/token";
    private static final String SESSION = "/api/v1/desktop/session";
    private static final String REVOKE = "/api/v1/desktop/session/revoke";
    private static final String CLIENT = "nekkora-agent-client";
    private static final String ISS = "https://portal.nekkora.test";

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.desktop-login.enabled", () -> "true");
    }

    // ------------------------------------------------------------------ apoio

    /** Uma tentativa do aplicativo: state, verificador e redirect proprios. */
    record Attempt(String state, String verifier, String challenge, String redirect) {
        static Attempt nova() {
            String verifier = Tokens.newToken();
            return new Attempt(Tokens.newToken(), verifier, s256(verifier), "http://127.0.0.1:" + (20000 + (int) (Math.random() * 20000)) + "/callback");
        }

        String json() {
            return json(Map.of());
        }

        String json(Map<String, String> over) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("responseType", "code");
            m.put("clientId", CLIENT);
            m.put("redirectUri", redirect);
            m.put("state", state);
            m.put("codeChallenge", challenge);
            m.put("codeChallengeMethod", "S256");
            m.put("scope", "identity");
            m.putAll(over);
            StringBuilder s = new StringBuilder("{");
            m.forEach((k, v) -> {
                if (v != null) {
                    s.append(s.length() > 1 ? "," : "").append('"').append(k).append("\":\"").append(v).append('"');
                }
            });
            return s.append('}').toString();
        }
    }

    static String s256(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Aplicativo nativo: sem Origin, sem cookies, sem CSRF. */
    private BrowserClient app() {
        BrowserClient a = browser();
        a.origin = null;
        return a;
    }

    private BrowserClient.Response token(BrowserClient a, Map<String, String> form) {
        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> body.append(body.length() > 0 ? "&" : "").append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return a.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + TOKEN))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).header("Content-Type", "application/x-www-form-urlencoded"));
    }

    private static Map<String, String> form(String code, Attempt a) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("grant_type", "authorization_code");
        m.put("code", code);
        m.put("redirect_uri", a.redirect());
        m.put("client_id", CLIENT);
        m.put("code_verifier", a.verifier());
        return m;
    }

    private BrowserClient.Response withBearer(BrowserClient a, String method, String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        b = "GET".equals(method) ? b.GET() : b.POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return a.send(b);
    }

    private static String param(String url, String name) {
        Matcher m = Pattern.compile("[?&]" + name + "=([^&]*)").matcher(url);
        return m.find() ? java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8) : null;
    }

    /** Navegador com sessao de identidade SEM fator (029) e o CSRF da sessao. */
    private BrowserClient logado(String email) {
        BrowserClient b = client();
        assertThat(login(b, email, SENHA).field("status")).isEqualTo("authenticated");
        return b;
    }

    /** Abre o pedido no navegador (CSRF de pre-autenticacao) e devolve ao CSRF da sessao, se houver sessao. */
    private BrowserClient.Response abrir(BrowserClient b, Attempt a) {
        String sessionCsrf = b.cookies().containsKey("__Host-nekkora_id") ? b.csrf : null;
        fetchPreAuthCsrf(b);
        BrowserClient.Response r = b.post(AUTHORIZATIONS, a.json());
        if (sessionCsrf != null) {
            b.csrf = sessionCsrf;
        }
        return r;
    }

    /** Fluxo inteiro ate o token, para os testes que partem de uma sessao do aplicativo ja emitida. */
    private String entrar(BrowserClient b) {
        Attempt a = Attempt.nova();
        assertThat(abrir(b, a).status()).isEqualTo(204);
        BrowserClient.Response ap = b.post(APPROVE, null);
        assertThat(ap.status()).as(ap.body()).isEqualTo(200);
        BrowserClient.Response t = token(app(), form(param(ap.field("redirectTo"), "code"), a));
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        return t.field("access_token");
    }

    private static long eventos(UUID identity, int type) throws Exception {
        return count("SELECT count(*) FROM identity_events WHERE identity_id = '" + identity + "' AND event_type = " + type);
    }

    // ------------------------------------------------------------------ fluxo

    /** Pedido antes do login, conta SEM fator e SEM organizacao: login normal, confirmacao da conta, codigo, troca, sessao. */
    @Test
    void fluxoCompletoComLoginDepoisDoPedido() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        Attempt a = Attempt.nova();
        BrowserClient b = client();

        BrowserClient.Response st = b.post(AUTHORIZATIONS, a.json());
        assertThat(st.status()).isEqualTo(204);
        assertThat(st.body()).as("nada do pedido volta ao navegador").isEmpty();
        assertThat(cookie(st, "__Host-nekkora_desk")).contains("Path=/").contains("Secure").contains("HttpOnly").contains("SameSite=Strict").contains("Max-Age=300");
        assertThat(st.headers().firstValue("Cache-Control").orElse("")).contains("no-store");

        assertThat(b.get(AUTHORIZATION).status()).as("sem sessao de identidade nao ha o que confirmar").isEqualTo(401);
        assertThat(login(b, e, SENHA).field("status")).isEqualTo("authenticated");

        BrowserClient.Response pe = b.get(AUTHORIZATION);
        assertThat(pe.status()).isEqualTo(200);
        assertThat(pe.field("application")).isEqualTo("Nekkora Agent");
        assertThat(pe.field("email")).as("a pagina mostra a conta que vai autorizar").isEqualTo(e);
        assertThat(pe.field("scope")).as("e o escopo pedido").isEqualTo("identity");
        assertThat(pe.body()).doesNotContain(a.state()).doesNotContain(a.challenge()).doesNotContain("127.0.0.1");

        BrowserClient.Response ap = b.post(APPROVE, null);
        assertThat(ap.status()).as(ap.body()).isEqualTo(200);
        assertThat(ap.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        String to = ap.field("redirectTo");
        assertThat(to).startsWith(a.redirect() + "?code=");
        assertThat(param(to, "state")).isEqualTo(a.state());
        assertThat(param(to, "iss")).isEqualTo(ISS);
        String code = param(to, "code");
        assertThat(Tokens.isValidFormat(code)).isTrue();
        assertThat(cookie(ap, "__Host-nekkora_desk")).as("o pedido aprovado nao serve de novo").contains("Max-Age=0");
        assertThat(eventos(id, 21)).isEqualTo(1);
        assertThat(eventos(id, 22)).as("codigo emitido ainda nao e sessao").isZero();

        BrowserClient app = app();
        BrowserClient.Response t = token(app, form(code, a));
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        assertThat(t.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(t.setCookies()).as("a troca nao emite cookie").isEmpty();
        assertThat(t.field("token_type")).isEqualTo("Bearer");
        assertThat(t.field("scope")).isEqualTo("identity");
        assertThat(t.body()).contains("\"expires_in\":900").doesNotContain("refresh_token").doesNotContain("id_token");
        String access = t.field("access_token");
        assertThat(Tokens.isValidFormat(access)).isTrue();
        assertThat(eventos(id, 22)).isEqualTo(1);

        BrowserClient.Response se = withBearer(app, "GET", SESSION, access);
        assertThat(se.status()).isEqualTo(200);
        assertThat(se.field("email")).isEqualTo(e);
        assertThat(se.field("scope")).isEqualTo("identity");
        assertThat(se.body()).as("nenhuma organizacao, Worker ou permissao na sessao do aplicativo")
                .doesNotContain("organization").doesNotContain("worker").doesNotContain("role");
        Matcher m = Pattern.compile("\"expiresIn\":(\\d+)").matcher(se.body());
        assertThat(m.find()).isTrue();
        assertThat(Long.parseLong(m.group(1))).isBetween(880L, 900L);

        assertThat(sessoesAtivas(id)).as("a sessao do aplicativo nao e uma sessao de identidade a mais").isEqualTo(1);
    }

    /** Sessao ja aberta no navegador, conta COM fator: a confirmacao continua exigida e mostra a conta; nada e silencioso. */
    @Test
    void sessaoExistenteComMfaExigeConfirmacaoDaConta() throws Exception {
        ComFator f = comFator();
        BrowserClient b = f.browser();
        Attempt a = Attempt.nova();
        assertThat(abrir(b, a).status()).isEqualTo(204);
        assertThat(eventos(f.identity(), 21)).as("abrir o pedido com sessao existente nao autoriza nada").isZero();
        BrowserClient.Response pe = b.get(AUTHORIZATION);
        assertThat(pe.field("email")).isEqualTo(f.email());
        BrowserClient.Response ap = b.post(APPROVE, null);
        assertThat(ap.status()).isEqualTo(200);
        BrowserClient.Response t = token(app(), form(param(ap.field("redirectTo"), "code"), a));
        assertThat(t.status()).isEqualTo(200);
        assertThat(withBearer(app(), "GET", SESSION, t.field("access_token")).field("email")).isEqualTo(f.email());
    }

    // ------------------------------------------------------------------ pedido

    @Test
    void pedidoForaDaRegraERecusadoSemCookie() {
        Attempt a = Attempt.nova();
        Map<String, Map<String, String>> ruins = new LinkedHashMap<>();
        ruins.put("pkce plain", Map.of("codeChallengeMethod", "plain"));
        ruins.put("desafio curto", Map.of("codeChallenge", "abc"));
        ruins.put("localhost", Map.of("redirectUri", "http://localhost:40000/callback"));
        ruins.put("https", Map.of("redirectUri", "https://127.0.0.1:40000/callback"));
        ruins.put("outro host", Map.of("redirectUri", "http://example.com:40000/callback"));
        ruins.put("host parecido", Map.of("redirectUri", "http://127.0.0.1.example.com:40000/callback"));
        ruins.put("usuario", Map.of("redirectUri", "http://a@127.0.0.1:40000/callback"));
        ruins.put("outro caminho", Map.of("redirectUri", "http://127.0.0.1:40000/outro"));
        ruins.put("consulta", Map.of("redirectUri", "http://127.0.0.1:40000/callback?x=1"));
        ruins.put("fragmento", Map.of("redirectUri", "http://127.0.0.1:40000/callback#x"));
        ruins.put("porta baixa", Map.of("redirectUri", "http://127.0.0.1:1023/callback"));
        ruins.put("porta alta", Map.of("redirectUri", "http://127.0.0.1:65536/callback"));
        ruins.put("sem porta", Map.of("redirectUri", "http://127.0.0.1/callback"));
        ruins.put("ipv6", Map.of("redirectUri", "http://[::1]:40000/callback"));
        ruins.put("outro cliente", Map.of("clientId", "outro-cliente"));
        ruins.put("resposta implicita", Map.of("responseType", "token"));
        ruins.put("escopo maior", Map.of("scope", "identity organizations"));
        ruins.put("state curto", Map.of("state", "abc"));
        ruins.put("state com caractere de URL", Map.of("state", "aaaaaaaaaaaaaaaaaaaaaaaa&code=x"));
        ruins.forEach((nome, over) -> {
            BrowserClient b = client();
            BrowserClient.Response r = b.post(AUTHORIZATIONS, a.json(over));
            assertThat(r.status()).as(nome).isEqualTo(400);
            assertThat(cookie(r, "__Host-nekkora_desk")).as(nome + ": sem cookie de pedido").isNull();
        });
        // metodo e desafio AUSENTES (sem downgrade de PKCE)
        for (String campo : List.of("codeChallengeMethod", "codeChallenge", "state", "redirectUri")) {
            Map<String, String> sem = new LinkedHashMap<>();
            sem.put(campo, null);
            assertThat(client().post(AUTHORIZATIONS, a.json(sem)).status()).as("sem " + campo).isEqualTo(400);
        }
        assertThat(client().post(AUTHORIZATIONS, null).status()).isEqualTo(400);
    }

    @Test
    void abrirPedidoExigeOrigemECsrf() {
        Attempt a = Attempt.nova();
        BrowserClient semCsrf = browser();
        assertThat(semCsrf.post(AUTHORIZATIONS, a.json()).status()).as("sem CSRF de pre-autenticacao").isEqualTo(403);
        BrowserClient outraOrigem = client();
        outraOrigem.origin = "https://atacante.example";
        assertThat(outraOrigem.post(AUTHORIZATIONS, a.json()).status()).as("outra origem").isEqualTo(403);
    }

    // ------------------------------------------------------------------ aprovacao

    @Test
    void aprovarExigeSessaoCsrfDaSessaoEPedidoVivo() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        Attempt a = Attempt.nova();

        BrowserClient anon = client();
        assertThat(anon.post(AUTHORIZATIONS, a.json()).status()).isEqualTo(204);
        assertThat(anon.post(APPROVE, null).status()).as("sem sessao de identidade").isEqualTo(401);

        BrowserClient b = logado(e);
        assertThat(b.post(APPROVE, null).status()).as("sessao sem pedido").isEqualTo(404);
        assertThat(abrir(b, a).status()).isEqualTo(204);
        String certo = b.csrf;
        fetchPreAuthCsrf(b);
        assertThat(b.post(APPROVE, null).status()).as("CSRF de pre-autenticacao nao aprova").isEqualTo(403);
        b.csrf = Tokens.newToken();
        assertThat(b.post(APPROVE, null).status()).as("CSRF errado").isEqualTo(403);
        b.csrf = certo;
        b.origin = "https://atacante.example";
        assertThat(b.post(APPROVE, null).status()).as("outra origem").isEqualTo(403);
        b.origin = ISS;
        assertThat(eventos(id, 21)).as("nenhuma recusa emitiu codigo").isZero();

        assertThat(b.post(APPROVE, null).status()).isEqualTo(200);
        assertThat(b.post(APPROVE, null).status()).as("o pedido e de uso unico").isEqualTo(404);
        assertThat(eventos(id, 21)).isEqualTo(1);
    }

    /** Sessao nascida de codigo de recuperacao (restrita, DC-17.13) nao autoriza aplicativo. */
    @Test
    void sessaoRestritaNaoAutoriza() throws Exception {
        ComFator f = comFator();
        BrowserClient b = client();
        assertThat(login(b, f.email(), SENHA).field("status")).isEqualTo("mfa_required");
        BrowserClient.Response v = b.post("/api/v1/auth/mfa/verify", "{\"recoveryCode\":\"" + f.codes().get(0) + "\"}");
        assertThat(v.status()).as(v.body()).isEqualTo(200);
        b.csrf = v.field("csrfToken");
        assertThat(abrir(b, Attempt.nova()).status()).isEqualTo(204);
        assertThat(b.get(AUTHORIZATION).status()).isEqualTo(403);
        assertThat(b.post(APPROVE, null).status()).isEqualTo(403);
        assertThat(eventos(f.identity(), 21)).isZero();
    }

    @Test
    void recusarDevolveAccessDeniedEConsomeOPedido() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        Attempt a = Attempt.nova();
        BrowserClient b = client();
        assertThat(b.post(AUTHORIZATIONS, a.json()).status()).isEqualTo(204);
        BrowserClient.Response d = b.post(DENY, null);
        assertThat(d.status()).as("recusar nao exige sessao").isEqualTo(200);
        String to = d.field("redirectTo");
        assertThat(to).startsWith(a.redirect() + "?error=access_denied&state=").doesNotContain("code=");
        assertThat(param(to, "state")).isEqualTo(a.state());
        assertThat(param(to, "iss")).isEqualTo(ISS);
        assertThat(b.post(DENY, null).status()).isEqualTo(404);
        assertThat(login(b, e, SENHA).field("status")).isEqualTo("authenticated");
        assertThat(b.post(APPROVE, null).status()).as("pedido recusado nao e aprovado depois").isEqualTo(404);
        assertThat(eventos(id, 21)).isZero();
    }

    // ------------------------------------------------------------------ troca

    @Test
    void codigoEDeUsoUnicoEOReusoRevogaASessaoEmitida() throws Exception {
        String e = email();
        confirmed(e, SENHA);
        BrowserClient b = logado(e);
        Attempt a = Attempt.nova();
        assertThat(abrir(b, a).status()).isEqualTo(204);
        String code = param(b.post(APPROVE, null).field("redirectTo"), "code");
        BrowserClient.Response t = token(app(), form(code, a));
        assertThat(t.status()).isEqualTo(200);
        String access = t.field("access_token");
        assertThat(withBearer(app(), "GET", SESSION, access).status()).isEqualTo(200);

        BrowserClient.Response de = token(app(), form(code, a));
        assertThat(de.status()).isEqualTo(400);
        assertThat(de.body()).isEqualTo("{\"error\":\"invalid_grant\"}");
        assertThat(withBearer(app(), "GET", SESSION, access).status()).as("reuso do codigo revoga a sessao que ele emitiu").isEqualTo(401);
    }

    @Test
    void pkceErradoQueimaOCodigo() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        BrowserClient b = logado(e);
        Attempt a = Attempt.nova();
        assertThat(abrir(b, a).status()).isEqualTo(204);
        String code = param(b.post(APPROVE, null).field("redirectTo"), "code");

        Map<String, String> errado = form(code, a);
        errado.put("code_verifier", Tokens.newToken());
        BrowserClient.Response r = token(app(), errado);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body()).isEqualTo("{\"error\":\"invalid_grant\"}");
        assertThat(token(app(), form(code, a)).status()).as("depois do verificador errado, o certo tambem nao serve").isEqualTo(400);
        assertThat(eventos(id, 22)).isZero();
    }

    @Test
    void trocaRecusaVerificadorAusenteDesafioComoVerificadorClienteERedirectDiferentes() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        BrowserClient b = logado(e);

        // verificador ausente: pedido invalido (nenhuma troca sem PKCE)
        Attempt a1 = Attempt.nova();
        abrir(b, a1);
        String c1 = param(b.post(APPROVE, null).field("redirectTo"), "code");
        Map<String, String> sem = form(c1, a1);
        sem.remove("code_verifier");
        BrowserClient.Response r1 = token(app(), sem);
        assertThat(r1.status()).isEqualTo(400);
        assertThat(r1.body()).isEqualTo("{\"error\":\"invalid_request\"}");
        // o proprio desafio no lugar do verificador (como se o metodo fosse "plain")
        Map<String, String> plain = form(c1, a1);
        plain.put("code_verifier", a1.challenge());
        assertThat(token(app(), plain).body()).isEqualTo("{\"error\":\"invalid_grant\"}");

        Attempt a2 = Attempt.nova();
        abrir(b, a2);
        String c2 = param(b.post(APPROVE, null).field("redirectTo"), "code");
        Map<String, String> outroRedirect = form(c2, a2);
        outroRedirect.put("redirect_uri", "http://127.0.0.1:1999/callback");
        assertThat(token(app(), outroRedirect).body()).isEqualTo("{\"error\":\"invalid_grant\"}");

        Attempt a3 = Attempt.nova();
        abrir(b, a3);
        String c3 = param(b.post(APPROVE, null).field("redirectTo"), "code");
        Map<String, String> outroCliente = form(c3, a3);
        outroCliente.put("client_id", "outro-cliente");
        assertThat(token(app(), outroCliente).body()).isEqualTo("{\"error\":\"invalid_client\"}");
        Map<String, String> outraConcessao = form(c3, a3);
        outraConcessao.put("grant_type", "password");
        assertThat(token(app(), outraConcessao).body()).isEqualTo("{\"error\":\"unsupported_grant_type\"}");

        // codigo inventado, e parametros na URL ou repetidos
        assertThat(token(app(), form(Tokens.newToken(), a3)).body()).isEqualTo("{\"error\":\"invalid_grant\"}");
        BrowserClient.Response naUrl = app().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + TOKEN + "?code=" + c3))
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=authorization_code")).header("Content-Type", "application/x-www-form-urlencoded"));
        assertThat(naUrl.body()).isEqualTo("{\"error\":\"invalid_request\"}");
        BrowserClient.Response repetido = app().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + TOKEN))
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=authorization_code&code=" + c3 + "&code=" + c3 + "&redirect_uri=x&client_id=" + CLIENT + "&code_verifier=" + a3.verifier()))
                .header("Content-Type", "application/x-www-form-urlencoded"));
        assertThat(repetido.body()).isEqualTo("{\"error\":\"invalid_request\"}");
        assertThat(eventos(id, 22)).as("nenhuma recusa emitiu sessao").isZero();
    }

    /** Duas pessoas, duas tentativas ao mesmo tempo: o codigo de uma nao serve com o verificador da outra. */
    @Test
    void tentativasConcorrentesNaoSeAutenticamUmaAOutra() throws Exception {
        String e1 = email();
        String e2 = email();
        confirmed(e1, SENHA);
        confirmed(e2, SENHA);
        BrowserClient b1 = logado(e1);
        BrowserClient b2 = logado(e2);
        Attempt a1 = Attempt.nova();
        Attempt a2 = Attempt.nova();
        abrir(b1, a1);
        abrir(b2, a2);
        String c1 = param(b1.post(APPROVE, null).field("redirectTo"), "code");
        String to2 = b2.post(APPROVE, null).field("redirectTo");
        assertThat(param(to2, "state")).as("cada retorno leva o state da propria tentativa").isEqualTo(a2.state()).isNotEqualTo(a1.state());
        String c2 = param(to2, "code");

        // codigo da pessoa 1 apresentado pela tentativa 2 (verificador e redirect da 2)
        assertThat(token(app(), form(c1, a2)).body()).isEqualTo("{\"error\":\"invalid_grant\"}");
        BrowserClient.Response t2 = token(app(), form(c2, a2));
        assertThat(t2.status()).isEqualTo(200);
        assertThat(withBearer(app(), "GET", SESSION, t2.field("access_token")).field("email")).isEqualTo(e2);
    }

    /** Autorizacao dada e, antes da troca, a sessao do navegador encerrada: o codigo nao vira sessao. */
    @Test
    void codigoNaoViraSessaoSeAOrigemFoiEncerradaAntesDaTroca() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        BrowserClient b = logado(e);
        Attempt a = Attempt.nova();
        abrir(b, a);
        String code = param(b.post(APPROVE, null).field("redirectTo"), "code");
        assertThat(b.post("/api/v1/auth/logout", null).status()).isEqualTo(204);
        assertThat(token(app(), form(code, a)).body()).isEqualTo("{\"error\":\"invalid_grant\"}");
        assertThat(eventos(id, 22)).isZero();
    }

    // ------------------------------------------------------------------ sessao do aplicativo

    @Test
    void logoutNoNavegadorEncerraASessaoDoAplicativo() throws Exception {
        String e = email();
        confirmed(e, SENHA);
        BrowserClient b = logado(e);
        String access = entrar(b);
        assertThat(withBearer(app(), "GET", SESSION, access).status()).isEqualTo(200);
        assertThat(b.post("/api/v1/auth/logout", null).status()).isEqualTo(204);
        BrowserClient.Response r = withBearer(app(), "GET", SESSION, access);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body()).isEqualTo(UNAUTH);
    }

    /** Revogacao por evento de seguranca que JA existe (sessao de origem revogada no banco, como a troca de senha faz). */
    @Test
    void revogacaoDaSessaoDeOrigemEncerraASessaoDoAplicativo() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        BrowserClient b = logado(e);
        String access = entrar(b);
        assertThat(withBearer(app(), "GET", SESSION, access).status()).isEqualTo(200);
        // o que identity_password_change / identity_password_reset / identity_mfa_activate fazem as sessoes da identidade
        exec("UPDATE identity_sessions SET revoked_at = greatest(clock_timestamp(), created_at) WHERE identity_id = '" + id + "' AND revoked_at IS NULL");
        assertThat(withBearer(app(), "GET", SESSION, access).status()).isEqualTo(401);
    }

    @Test
    void aplicativoEncerraAPropriaSessao() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        BrowserClient b = logado(e);
        String access = entrar(b);
        BrowserClient app = app();
        assertThat(withBearer(app, "POST", REVOKE, access).status()).as("sem Origin e sem CSRF: e o token que autentica").isEqualTo(204);
        assertThat(eventos(id, 23)).isEqualTo(1);
        assertThat(withBearer(app, "GET", SESSION, access).status()).isEqualTo(401);
        assertThat(withBearer(app, "POST", REVOKE, access).status()).as("idempotente").isEqualTo(204);
        assertThat(withBearer(app, "POST", REVOKE, Tokens.newToken()).status()).as("token desconhecido nao e erro").isEqualTo(204);
        assertThat(withBearer(app, "POST", REVOKE, null).status()).isEqualTo(204);
        assertThat(eventos(id, 23)).isEqualTo(1);
        assertThat(b.get("/api/v1/me/session").status()).as("a sessao do navegador continua: sao sessoes separadas").isEqualTo(200);
    }

    /** O token do aplicativo so vale nas rotas dele: um Client alterado nao alcanca identidade, organizacao nem maquina. */
    @Test
    void tokenDoAplicativoNaoAutenticaNenhumaOutraRota() throws Exception {
        String e = email();
        confirmed(e, SENHA);
        String access = entrar(logado(e));
        BrowserClient app = app();
        UUID org = UUID.randomUUID();
        for (String rota : List.of("/api/v1/me/session", "/api/v1/me/organizations", "/api/v1/o/" + org + "/me", "/api/v1/o/" + org + "/members",
                "/api/v1/o/" + org + "/workers", "/api/v1/o/" + org + "/automations", AUTHORIZATION)) {
            assertThat(withBearer(app, "GET", rota, access).status()).as("GET " + rota).isEqualTo(401);
        }
        for (String rota : List.of("/api/v1/sessions/organization", "/api/v1/auth/password", "/api/v1/o/" + org + "/workers", APPROVE)) {
            assertThat(withBearer(app, "POST", rota, access).status()).as("POST " + rota).isIn(401, 403);
        }
        // criacao de organizacao: chave desligada neste contexto (404 antes da cadeia); ligada, cairia no 401 como as demais
        assertThat(withBearer(app, "POST", "/api/v1/organizations", access).status()).isIn(401, 403, 404);
        // e o token como se fosse cookie de sessao tambem nao vale
        BrowserClient comoCookie = browser();
        comoCookie.cookies().put("__Host-nekkora_id", access);
        assertThat(comoCookie.get("/api/v1/me/session").status()).isEqualTo(401);
    }

    /** As rotas do aplicativo ignoram cookies: a sessao do navegador, sozinha, nao e sessao do aplicativo. */
    @Test
    void cookieDeSessaoNaoValeNasRotasDoAplicativo() throws Exception {
        String e = email();
        confirmed(e, SENHA);
        BrowserClient b = logado(e);
        assertThat(b.get(SESSION).status()).as("cookie de identidade sem Bearer").isEqualTo(401);
        assertThat(withBearer(app(), "GET", SESSION, null).status()).isEqualTo(401);
        assertThat(withBearer(app(), "GET", SESSION, Tokens.newToken()).status()).isEqualTo(401);
        assertThat(withBearer(app(), "GET", SESSION, "curto").status()).isEqualTo(401);
        BrowserClient.Response t = b.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + TOKEN))
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=authorization_code")).header("Content-Type", "application/x-www-form-urlencoded"));
        assertThat(t.body()).as("o cookie nao substitui o codigo").isEqualTo("{\"error\":\"invalid_request\"}");
    }
}
