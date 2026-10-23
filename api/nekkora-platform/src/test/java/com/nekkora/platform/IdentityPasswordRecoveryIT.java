package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;
import com.nekkora.platform.mail.MailSender;

/**
 * Recuperacao de senha da identidade por e-mail (missao PLATFORM-API-IDENTIDADE-002, PR 4; migration 020):
 * {@code POST /api/v1/auth/password-reset/request} e {@code /confirm}, com a chave propria ligada. Papel real
 * nekkora_platform, Redis real. Cada recusa confere a resposta E o estado persistido (superusuario, sem RLS). Cada teste
 * usa IPs proprios (R14 conta por IP).
 */
class IdentityPasswordRecoveryIT extends IdentityTestBase {

    private static final String REQUEST = "/api/v1/auth/password-reset/request";
    private static final String CONFIRM = "/api/v1/auth/password-reset/confirm";
    private static final String NOVA = "senha-recuperada-da-identidade-3";

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.recuperacao-senha.enabled", () -> "true");
    }

    /**
     * Pedido -> token de uso unico (so o hash no banco, proposito 2, prazo de 30 min, evento 16) -> confirmacao: senha nova,
     * TODAS as sessoes revogadas, nenhuma sessao criada, evento 17, aviso; MFA preservado (fator e codigos intocados; a
     * entrada seguinte exige o fator). O mesmo token nao serve de novo.
     */
    @Test
    void pedidoEConfirmacaoTrocamASenhaRevogamTudoEPreservamOMfa() throws Exception {
        ComFator f = comFator();
        BrowserClient outra = segundaSessao(f);
        String mfaAntes = mfa(f.identity());
        BrowserClient.Response p = pedido(f.email());
        assertThat(p.status()).isEqualTo(202);
        assertThat(p.body()).isEmpty();
        String token = tokenDoEmail(f.email());
        assertThat(value("SELECT count(*) FROM identity_email_tokens WHERE identity_id = '" + f.identity() + "' AND purpose = 2 AND token_hash = '"
                + Tokens.sha256Hex(token) + "' AND expires_at BETWEEN created_at + interval '29 minutes' AND created_at + interval '30 minutes'")).isEqualTo("1");
        assertThat(count("SELECT count(*) FROM identity_email_tokens WHERE token_hash = '" + token + "'")).as("token em claro nunca no banco").isZero();
        assertThat(eventos(f.identity(), 16)).isEqualTo(1);

        BrowserClient c = client();
        BrowserClient.Response r = c.post(CONFIRM, "{\"token\":" + q(token) + ",\"newPassword\":" + q(NOVA) + "}");
        assertThat(r.status()).as(r.body()).isEqualTo(204);
        assertThat(cookie(r, "__Host-nekkora_id")).as("nenhuma sessao criada").isNull();
        assertThat(sessoesAtivas(f.identity())).as("todas as sessoes revogadas").isZero();
        assertThat(f.browser().get("/api/v1/me/session").status()).isEqualTo(401);
        assertThat(outra.get("/api/v1/me/session").status()).isEqualTo(401);
        assertThat(eventos(f.identity(), 17)).isEqualTo(1);
        assertThat(mails(f.email()).stream().filter(m -> m.kind() == MailSender.Kind.NOTICE_PASSWORD_CHANGED).count()).isEqualTo(1);
        assertThat(mfa(f.identity())).as("MFA intocado").isEqualTo(mfaAntes);

        assertThat(client().post(CONFIRM, "{\"token\":" + q(token) + ",\"newPassword\":\"outra-senha-qualquer-44\"}").status()).as("uso unico").isEqualTo(400);
        assertThat(login(client(), f.email(), SENHA).status()).as("senha antiga").isEqualTo(401);
        BrowserClient n = client();
        assertThat(login(n, f.email(), NOVA).field("status")).as("MFA exigido na entrada seguinte").isEqualTo("mfa_required");
        assertThat(verifyTotp(n, f.identity()).status()).isEqualTo(200);
    }

    /**
     * Sem enumeracao: e-mail desconhecido, identidade PENDING e SUSPENDED recebem a MESMA resposta (202 vazia) que a ACTIVE,
     * todas com o piso de tempo; so a ACTIVE recebe token e e-mail.
     */
    @Test
    void respostaUniformeSemEnumeracao() throws Exception {
        ComFator ativa = comFator();
        String pendente = email();
        assertThat(client().post("/api/v1/signup", "{\"email\":" + q(pendente) + "}").status()).isEqualTo(202);
        ComFator suspensa = comFator();
        exec("UPDATE identities SET status = 3 WHERE identity_id = '" + suspensa.identity() + "'");
        int antesPendente = mails(pendente).size();
        for (String e : List.of(email(), pendente, suspensa.email(), ativa.email())) {
            long t0 = System.nanoTime();
            BrowserClient.Response r = pedido(e);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(r.status()).as(e).isEqualTo(202);
            assertThat(r.body()).isEmpty();
            assertThat(ms).as("piso de tempo").isGreaterThanOrEqualTo(290);
        }
        assertThat(mails(pendente)).hasSize(antesPendente);
        assertThat(mails(suspensa.email()).stream().filter(m -> m.kind() == MailSender.Kind.PASSWORD_RECOVERY)).isEmpty();
        assertThat(count("SELECT count(*) FROM identity_email_tokens WHERE purpose = 2 AND identity_id = '" + suspensa.identity() + "'")).isZero();
        assertThat(count("SELECT count(*) FROM identity_email_tokens t JOIN identities i ON i.identity_id = t.identity_id WHERE t.purpose = 2 AND i.email = '"
                + pendente + "'")).isZero();
        assertThat(mails(ativa.email()).stream().filter(m -> m.kind() == MailSender.Kind.PASSWORD_RECOVERY).count()).isEqualTo(1);
        assertThat(client().post(REQUEST, "{\"email\":\"sem-arroba\"}").status()).as("e-mail malformado").isEqualTo(400);
    }

    /** Um so token valido por vez: o pedido seguinte revoga o anterior. */
    @Test
    void novoPedidoRevogaOTokenAnterior() throws Exception {
        ComFator f = comFator();
        pedido(f.email());
        String primeiro = tokenDoEmail(f.email());
        pedido(f.email());
        String segundo = tokenDoEmail(f.email());
        assertThat(segundo).isNotEqualTo(primeiro);
        assertThat(client().post(CONFIRM, "{\"token\":" + q(primeiro) + ",\"newPassword\":" + q(NOVA) + "}").status()).isEqualTo(400);
        assertThat(client().post(CONFIRM, "{\"token\":" + q(segundo) + ",\"newPassword\":" + q(NOVA) + "}").status()).isEqualTo(204);
    }

    /**
     * Recusas sem efeito: token vencido; token de confirmacao de CADASTRO na recuperacao (e o de recuperacao no cadastro);
     * senha fora da politica (o token continua valido); identidade suspensa depois do pedido; token malformado.
     */
    @Test
    void recusasNaoConsomemNemTrocam() throws Exception {
        ComFator f = comFator();
        pedido(f.email());
        String token = tokenDoEmail(f.email());
        String hash = hash(f.identity());
        String mfa = mfa(f.identity());

        assertThat(client().post(CONFIRM, "{\"token\":" + q(token) + ",\"newPassword\":\"curta\"}").status()).as("politica").isEqualTo(400);
        assertThat(client().post("/api/v1/signup/confirm", "{\"token\":" + q(token) + ",\"password\":" + q(NOVA) + ",\"displayName\":\"X\"}").status())
                .as("token de recuperacao no cadastro").isEqualTo(400);
        assertThat(client().post(CONFIRM, "{\"token\":\"malformado\",\"newPassword\":" + q(NOVA) + "}").status()).isEqualTo(400);
        assertThat(client().post(CONFIRM, null).status()).isEqualTo(400);

        String pendente = email();
        assertThat(client().post("/api/v1/signup", "{\"email\":" + q(pendente) + "}").status()).isEqualTo(202);
        String cadastro = mails(pendente).get(mails(pendente).size() - 1).token();
        assertThat(client().post(CONFIRM, "{\"token\":" + q(cadastro) + ",\"newPassword\":" + q(NOVA) + "}").status()).as("token de cadastro").isEqualTo(400);
        assertThat(value("SELECT consumed_at IS NULL FROM identity_email_tokens WHERE token_hash = '" + Tokens.sha256Hex(cadastro) + "'"))
                .as("o token de cadastro continua vivo").isEqualTo("t");

        semTriggers("UPDATE identities SET status = 3 WHERE identity_id = '" + f.identity() + "'");
        assertThat(client().post(CONFIRM, "{\"token\":" + q(token) + ",\"newPassword\":" + q(NOVA) + "}").status()).as("suspensa").isEqualTo(400);
        semTriggers("UPDATE identities SET status = 2 WHERE identity_id = '" + f.identity() + "'");
        semTriggers("UPDATE identity_email_tokens SET created_at = created_at - interval '1 hour', expires_at = expires_at - interval '1 hour' WHERE token_hash = '"
                + Tokens.sha256Hex(token) + "'");
        assertThat(client().post(CONFIRM, "{\"token\":" + q(token) + ",\"newPassword\":" + q(NOVA) + "}").status()).as("vencido").isEqualTo(400);

        assertThat(hash(f.identity())).isEqualTo(hash);
        assertThat(mfa(f.identity())).isEqualTo(mfa);
        assertThat(value("SELECT consumed_at IS NULL FROM identity_email_tokens WHERE token_hash = '" + Tokens.sha256Hex(token) + "'")).isEqualTo("t");
        assertThat(eventos(f.identity(), 17)).isZero();
    }

    /** Prazo reconferido DEPOIS da espera pelo lock da identidade: vencido durante a espera, 400 sem efeito. */
    @Test
    void prazoReconferidoDepoisDaEspera() throws Exception {
        ComFator f = comFator();
        pedido(f.email());
        String token = tokenDoEmail(f.email());
        String hash = hash(f.identity());
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM identities WHERE identity_id = '" + f.identity() + "' FOR UPDATE")) {
                ps.execute();
            }
            Future<BrowserClient.Response> r = ex.submit(() -> client().post(CONFIRM, "{\"token\":" + q(token) + ",\"newPassword\":" + q(NOVA) + "}"));
            assertThat(platformWaitingOnLock()).isTrue();
            semTriggers("UPDATE identity_email_tokens SET created_at = created_at - interval '1 hour', expires_at = clock_timestamp() + interval '1 second' WHERE token_hash = '"
                    + Tokens.sha256Hex(token) + "'");
            pause(1500);
            c.commit();
            assertThat(r.get(30, TimeUnit.SECONDS).status()).isEqualTo(400);
        } finally {
            ex.shutdownNow();
        }
        assertThat(hash(f.identity())).isEqualTo(hash);
        assertThat(eventos(f.identity(), 17)).isZero();
    }

    /** Duas confirmacoes concorrentes do mesmo token: exatamente uma troca a senha. */
    @Test
    void confirmacoesConcorrentesTrocamUmaVez() throws Exception {
        ComFator f = comFator();
        pedido(f.email());
        String token = tokenDoEmail(f.email());
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<BrowserClient.Response> a = ex.submit(() -> client().post(CONFIRM, "{\"token\":" + q(token) + ",\"newPassword\":" + q(NOVA) + "}"));
            Future<BrowserClient.Response> b = ex.submit(() -> client().post(CONFIRM, "{\"token\":" + q(token) + ",\"newPassword\":\"outra-senha-concorrente-55\"}"));
            assertThat(List.of(a.get(30, TimeUnit.SECONDS).status(), b.get(30, TimeUnit.SECONDS).status())).containsExactlyInAnyOrder(204, 400);
        } finally {
            ex.shutdownNow();
        }
        assertThat(eventos(f.identity(), 17)).isEqualTo(1);
    }

    /** R14: por identidade (3 por hora); o quarto pedido responde igual e nao emite token nem e-mail. CSRF de pre-autenticacao exigido. */
    @Test
    void limiteDePedidosECsrf() throws Exception {
        ComFator f = comFator();
        String ip = "203.0.113." + (1 + (int) (Math.random() * 200));
        for (int i = 0; i < 4; i++) {
            BrowserClient b = client();
            b.forwardedIp = ip;
            assertThat(b.post(REQUEST, "{\"email\":" + q(f.email()) + "}").status()).isEqualTo(202);
        }
        assertThat(mails(f.email()).stream().filter(m -> m.kind() == MailSender.Kind.PASSWORD_RECOVERY).count()).isEqualTo(3);
        assertThat(eventos(f.identity(), 16)).isEqualTo(3);
        BrowserClient sem = client();
        sem.csrf = null;
        assertThat(sem.post(REQUEST, "{\"email\":" + q(f.email()) + "}").status()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ apoio

    private BrowserClient.Response pedido(String email) {
        BrowserClient b = client();
        b.forwardedIp = "198.51.100." + (1 + (int) (Math.random() * 250));
        return b.post(REQUEST, "{\"email\":" + q(email) + "}");
    }

    private String tokenDoEmail(String email) {
        List<SentMessage> m = mails(email).stream().filter(x -> x.kind() == MailSender.Kind.PASSWORD_RECOVERY).toList();
        assertThat(m).isNotEmpty();
        return m.get(m.size() - 1).token();
    }

    private BrowserClient segundaSessao(ComFator f) {
        BrowserClient outra = client();
        assertThat(login(outra, f.email(), SENHA).field("status")).isEqualTo("mfa_required");
        BrowserClient.Response v = verifyTotp(outra, f.identity());
        assertThat(v.status()).isEqualTo(200);
        outra.csrf = v.field("csrfToken");
        return outra;
    }

    private static String hash(UUID identity) throws Exception {
        return value("SELECT password_hash FROM identity_credentials WHERE identity_id = '" + identity + "'");
    }

    /** Fatores e codigos (estado completo): o que a recuperacao de senha nao pode tocar. */
    private static String mfa(UUID identity) throws Exception {
        String w = " WHERE identity_id = '" + identity + "'";
        return value("SELECT string_agg(factor_id || ':' || (confirmed_at IS NOT NULL) || (disabled_at IS NOT NULL), ',' ORDER BY factor_id) FROM identity_mfa_factors" + w)
                + "|" + value("SELECT string_agg(code_id || ':' || (used_at IS NOT NULL) || (invalidated_at IS NOT NULL), ',' ORDER BY code_id) FROM identity_mfa_recovery_codes" + w);
    }

    private static long eventos(UUID identity, int tipo) throws Exception {
        return count("SELECT count(*) FROM identity_events WHERE identity_id = '" + identity + "' AND event_type = " + tipo);
    }

    private static void semTriggers(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SET LOCAL session_replication_role = replica")) {
                ps.execute();
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.execute();
            }
            c.commit();
        }
    }
}
