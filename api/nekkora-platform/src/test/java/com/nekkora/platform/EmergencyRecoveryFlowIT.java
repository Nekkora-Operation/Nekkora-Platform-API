package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.testsupport.RealRedis;
import com.nekkora.ops.cli.ExecutorAtos;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;
import com.nekkora.platform.auth.crypto.Base32;
import com.nekkora.platform.auth.crypto.PasswordHasher;
import com.nekkora.platform.auth.crypto.Totp;
import com.nekkora.platform.emergency.EmergencyNotices;
import com.nekkora.platform.mail.MailSender;

/**
 * Fluxo completo da recuperacao emergencial (missao, item 6) com o processo REAL da Platform ligado ao PostgreSQL com
 * TLS e autenticacao por certificado (OpsBanco): pedido -> verificacao -> aprovacao -> aviso (e-mail capturado
 * localmente) -> confirmacao por POST -> espera (fixture temporal) -> aprovacao final -> execucao (CLI com certificado)
 * -> novo cadastro de MFA pela API. Senha, papeis, vinculos e concessoes inalterados. Rotas de aviso: POST explicito
 * com CSRF de pre-autenticacao e Origin, resposta identica nas recusas, sem sessao.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {PlatformApplication.class, PlatformTestBase.TestMailConfig.class})
class EmergencyRecoveryFlowIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);
    static final PasswordHasher HASHER = new PasswordHasher(new PasswordHasher.Parameters(65536, 3, 1), 4, 8, 5000);
    static final String SENHA = "senha-de-teste-longa-1";
    private static final SecureRandom RANDOM = new SecureRandom();

    @LocalServerPort
    int port;

    @Autowired
    PlatformTestBase.TestMailSender mail;

    @Autowired
    EmergencyNotices notices;

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("nekkora.database.url", DB::urlTls);
        r.add("nekkora.database.user", () -> "nekkora_platform");
        r.add("nekkora.database.password", () -> DB.senha("nekkora_platform"));
        r.add("spring.data.redis.host", () -> RealRedis.shared().host());
        r.add("spring.data.redis.port", () -> RealRedis.shared().port());
        r.add("nekkora.public-origin", () -> PlatformTestBase.ORIGIN);
        r.add("nekkora.trusted-proxies", () -> "127.0.0.1/32,::1/128");
        r.add("nekkora.keys.aes-current", () -> "k1");
        r.add("nekkora.keys.aes-by-id", () -> "k1:" + b64(32));
        r.add("nekkora.keys.hmac-id", () -> "h1");
        r.add("nekkora.keys.hmac", () -> b64(32));
    }

    private static String b64(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return java.util.Base64.getEncoder().encodeToString(b);
    }

    private BrowserClient navegador() {
        BrowserClient n = new BrowserClient("http://localhost:" + port);
        n.forwardedIp = "10." + RANDOM.nextInt(250) + "." + RANDOM.nextInt(250) + "." + (1 + RANDOM.nextInt(250));
        return n;
    }

    private static String email(UUID user) {
        return DB.valor(String.class, "SELECT email FROM users WHERE user_id = ?", user);
    }

    /** Senha real (Argon2) para a conta sintetica - preparacao declarada. */
    private static void senhaReal(UUID user) {
        DB.sql("UPDATE user_credentials SET password_hash = '" + HASHER.generate(SENHA.toCharArray()) + "' WHERE user_id = '" + user + "'");
    }

    private BrowserClient.Response postarAviso(BrowserClient n, UUID org, String rota, String token) {
        n.csrf = n.get("/api/v1/csrf").field("csrfToken");
        return n.post("/api/v1/o/" + org + "/auth/mfa-emergencia/aviso/" + rota, token == null ? "{}" : "{\"token\":\"" + token + "\"}");
    }

    private List<PlatformTestBase.SentMessage> enviadosDesde(int i) {
        return List.copyOf(mail.sent.subList(i, mail.sent.size()));
    }

    @Test
    void fluxoCompletoAteONovoCadastroDeMfaPelaApi() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID org = al.org().id();
        UUID outroAdmin = DB.outroAdmin(al.org());
        senhaReal(al.titular());

        // pedido -> verificacao -> pre-aprovacao (CLI com certificado)
        UUID s = C.preAprovada(eq, al);

        // aviso: emitido pela Platform (identidade de servico), e-mail capturado localmente
        int i0 = mail.sent.size();
        assertThat(notices.dispatchPending()).isEqualTo(2);
        List<PlatformTestBase.SentMessage> avisos = enviadosDesde(i0);
        PlatformTestBase.SentMessage titular = avisos.stream().filter(m -> m.kind() == MailSender.Kind.NOTICE_MFA_EMERGENCY_HOLDER).findFirst().orElseThrow();
        PlatformTestBase.SentMessage admin = avisos.stream().filter(m -> m.kind() == MailSender.Kind.NOTICE_MFA_EMERGENCY_ADMIN).findFirst().orElseThrow();
        assertThat(titular.recipient()).isEqualTo(email(al.titular()));
        assertThat(titular.token()).isNotNull();
        assertThat(titular.contestToken()).isNotNull();
        assertThat(admin.recipient()).isEqualTo(email(outroAdmin));
        assertThat(admin.token()).as("so o titular confirma").isNull();
        assertThat(admin.contestToken()).isNotNull();
        assertThat(notices.dispatchPending()).as("emissao unica").isZero();

        // abrir o link (GET) nao confirma; POST sem CSRF ou com outra origem e negado
        BrowserClient n = navegador();
        assertThat(n.get("/api/v1/o/" + org + "/auth/mfa-emergencia/aviso/confirmar").status()).isNotIn(200, 204);
        n.csrf = null;
        assertThat(n.post("/api/v1/o/" + org + "/auth/mfa-emergencia/aviso/confirmar", "{\"token\":\"" + titular.token() + "\"}").status())
                .as("sem CSRF de pre-autenticacao").isEqualTo(403);
        BrowserClient estranho = navegador();
        estranho.origin = "https://atacante.test";
        assertThat(postarAviso(estranho, org, "confirmar", titular.token()).status()).as("outra origem").isEqualTo(403);
        assertThat(DB.valor(Boolean.class, "SELECT notice_confirmed_at IS NULL FROM ops.ops_mfa_recovery_requests WHERE request_id = ?", s)).isTrue();

        // recusas: mesma resposta publica
        BrowserClient.Response finalidade = postarAviso(n, org, "confirmar", titular.contestToken());
        BrowserClient.Response outro = postarAviso(n, org, "confirmar", admin.contestToken());
        BrowserClient.Response desconhecido = postarAviso(n, org, "confirmar", com.nekkora.core.token.Tokens.newToken());
        BrowserClient.Response malformado = postarAviso(n, org, "confirmar", "x");
        BrowserClient.Response vazio = postarAviso(n, org, "confirmar", null);
        for (BrowserClient.Response r : List.of(finalidade, outro, desconhecido, malformado, vazio)) {
            assertThat(r.status()).isEqualTo(400);
            assertThat(r.body()).isEqualTo(finalidade.body()).isEqualTo("{\"error\":\"invalid_request\"}");
        }

        // confirmacao valida: 204, sem sessao, sem aprovar
        BrowserClient.Response ok = postarAviso(n, org, "confirmar", titular.token());
        assertThat(ok.status()).isEqualTo(204);
        assertThat(ok.setCookies()).noneMatch(c -> c.startsWith("__Host-nekkora_session"));
        assertThat(C.estado(s)).isEqualTo("PRE_APROVADA");
        assertThat(postarAviso(n, org, "confirmar", titular.token()).status()).as("uso unico").isEqualTo(400);

        // espera de 24 h (fixture temporal), aprovacao final e execucao
        assertThat(eq.a().aprovar(s).motivo()).isEqualTo("ESPERA_NAO_CUMPRIDA");
        DB.recuarSolicitacao(s, Cenarios.ESPERA);
        Cenarios.exigirOk(eq.a().aprovar(s), "aprovar");
        String identidade = C.identidade(org, al.titular());
        ExecutorAtos.Resultado r = eq.e().executar(s);
        assertThat(r.ok()).isTrue();
        assertThat(C.acesso(org, al.titular())).isEqualTo("0|0|0|0");
        assertThat(C.identidade(org, al.titular())).as("senha, papeis, vinculos e concessoes").isEqualTo(identidade);
        int i1 = mail.sent.size();
        assertThat(notices.dispatchPending()).isEqualTo(2);
        assertThat(enviadosDesde(i1)).allMatch(m -> m.kind() == MailSender.Kind.NOTICE_MFA_EMERGENCY_EXECUTED && m.token() == null && m.contestToken() == null);
        assertThat(postarAviso(n, org, "contestar", admin.contestToken()).status()).as("contestacao depois da execucao").isEqualTo(400);

        // novo cadastro de MFA: login com a senha ATUAL -> cadastro obrigatorio -> sessao
        BrowserClient t = navegador();
        t.csrf = t.get("/api/v1/csrf").field("csrfToken");
        BrowserClient.Response login = t.post("/api/v1/o/" + org + "/auth/login", "{\"email\":\"" + email(al.titular()) + "\",\"password\":\"" + SENHA + "\"}");
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.field("status")).isEqualTo("mfa_enrollment_required");
        t.csrf = login.field("csrfToken");
        BrowserClient.Response inicio = t.post("/api/v1/o/" + org + "/auth/mfa/enroll/start", null);
        assertThat(inicio.status()).isEqualTo(200);
        byte[] segredo = Base32.decode(inicio.field("otpauthUri").replace("\\u0026", "&").replaceAll(".*[?&]secret=([A-Z2-7]+).*", "$1"));
        BrowserClient.Response confirmacao = t.post("/api/v1/o/" + org + "/auth/mfa/enroll/confirm",
                "{\"code\":\"" + Totp.code(segredo, Totp.step(Instant.now())) + "\"}");
        assertThat(confirmacao.status()).isEqualTo(200);
        t.csrf = confirmacao.field("csrfToken");
        assertThat(t.get("/api/v1/o/" + org + "/me").status()).isEqualTo(200);
        assertThat(C.acesso(org, al.titular())).as("novo fator, novos codigos, uma sessao").isEqualTo("1|10|0|1");
        assertThat(C.identidade(org, al.titular())).isEqualTo(identidade);

        // prova de conta para vinculo impeditivo: codigo exibido so ao titular autenticado; o operador o usa na CLI
        BrowserClient.Response codigo = t.post("/api/v1/o/" + org + "/me/operational-link-code", null);
        assertThat(codigo.status()).isEqualTo(200);
        assertThat(C.vincularConta(eq.verificador(), codigo.field("code"))).isEqualTo("OK");
        assertThat(navegador().post("/api/v1/o/" + org + "/me/operational-link-code", null).status()).as("sem sessao").isEqualTo(401);
    }

    @Test
    void contestacaoPelaRotaCancelaAntesDaExecucao() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID org = al.org().id();
        DB.outroAdmin(al.org());
        UUID s = C.preAprovada(eq, al);
        int i0 = mail.sent.size();
        notices.dispatchPending();
        List<PlatformTestBase.SentMessage> avisos = enviadosDesde(i0);
        String confirmar = avisos.stream().filter(m -> m.kind() == MailSender.Kind.NOTICE_MFA_EMERGENCY_HOLDER).findFirst().orElseThrow().token();
        String contestar = avisos.stream().filter(m -> m.kind() == MailSender.Kind.NOTICE_MFA_EMERGENCY_ADMIN).findFirst().orElseThrow().contestToken();
        BrowserClient n = navegador();
        assertThat(postarAviso(n, org, "confirmar", confirmar).status()).isEqualTo(204);
        assertThat(postarAviso(n, org, "contestar", confirmar).status()).as("CONFIRMAR nao contesta").isEqualTo(400);
        assertThat(postarAviso(n, org, "contestar", contestar).status()).isEqualTo(204);
        assertThat(C.estado(s)).isEqualTo("CANCELADA");
        DB.recuarSolicitacao(s, Cenarios.ESPERA);
        assertThat(eq.a().aprovar(s).motivo()).isEqualTo("ESTADO_INVALIDO");
        assertThat(C.resets(s)).isZero();
        // token da organizacao A na rota da organizacao B: mesma resposta
        OpsBanco.Org outra = DB.organizacao();
        assertThat(postarAviso(n, outra.id(), "contestar", contestar).status()).isEqualTo(400);
    }
}
