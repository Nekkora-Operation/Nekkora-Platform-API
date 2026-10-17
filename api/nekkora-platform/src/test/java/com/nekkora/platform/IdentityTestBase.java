package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.platform.auth.crypto.Base32;
import com.nekkora.platform.identity.SignupMailer;

/**
 * Apoio dos testes da identidade (cadastro e entrada geral): identidade confirmada pelo cadastro real, login, cadastro do
 * primeiro fator com o TOTP calculado do segredo da URI otpauth, leituras como superusuario. Cada subclasse define o
 * proprio contexto (chaves ligadas, Redis exclusivo...).
 */
abstract class IdentityTestBase extends PlatformTestBase {

    @Autowired
    SignupMailer mailer;

    protected static final String UNAUTH = "{\"error\":\"unauthenticated\"}";
    protected static final String SENHA = "senha-da-identidade-1";

    // ------------------------------------------------------------------ apoio

    protected BrowserClient client() {
        BrowserClient b = browser();
        fetchPreAuthCsrf(b);
        return b;
    }

    protected static String q(String s) {
        return s == null ? "null" : "\"" + s.replace("\"", "\\\"") + "\"";
    }

    protected static String value(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    protected static void exec(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.execute();
        }
    }

    protected static long count(String sql) throws Exception {
        return Long.parseLong(value(sql));
    }

    protected void idle() {
        for (int i = 0; i < 300 && mailer.pending() > 0; i++) {
            pause(50);
        }
    }

    protected List<SentMessage> mails(String email) {
        idle();
        return testMail.sent.stream().filter(m -> email.equalsIgnoreCase(m.recipient())).toList();
    }

    /** Identidade confirmada pelo cadastro real; devolve o identity_id. */
    protected UUID confirmed(String email, String senha) throws Exception {
        assertThat(client().post("/api/v1/signup", "{\"email\":" + q(email) + "}").status()).isEqualTo(202);
        List<SentMessage> m = mails(email);
        String token = m.get(m.size() - 1).token();
        assertThat(client().post("/api/v1/signup/confirm", "{\"token\":" + q(token) + ",\"password\":" + q(senha) + ",\"displayName\":\"Pessoa\"}").status())
                .isEqualTo(204);
        return UUID.fromString(value("SELECT identity_id FROM identities WHERE email_normalized = lower('" + email + "')"));
    }

    protected static String email() {
        return "ent-" + UUID.randomUUID().toString().substring(0, 12) + "@exemplo.test";
    }

    /** Passo 1; em sucesso, o navegador guarda o cookie do desafio e passa a usar o CSRF do desafio. */
    protected BrowserClient.Response login(BrowserClient b, String email, String senha) {
        BrowserClient.Response r = b.post("/api/v1/auth/login", "{\"email\":" + q(email) + ",\"password\":" + q(senha) + "}");
        if (r.status() == 200) {
            b.csrf = r.field("csrfToken");
        }
        return r;
    }

    /** Cadastra o primeiro fator; guarda o segredo em SECRETS[identity] para code(identity). */
    protected BrowserClient.Response enroll(BrowserClient b, UUID identity) {
        BrowserClient.Response start = b.post("/api/v1/auth/mfa/enroll/start", null);
        assertThat(start.status()).as("enroll/start").isEqualTo(200);
        String uri = start.field("otpauthUri");
        String secret = URI.create(uri).getRawQuery().replaceAll(".*secret=([A-Z2-7]+).*", "$1");
        SECRETS.put(identity, Base32.decode(secret));
        return b.post("/api/v1/auth/mfa/enroll/confirm", "{\"code\":\"" + code(identity) + "\"}");
    }

    /** Identidade confirmada COM fator; devolve o navegador com a sessao e os codigos de recuperacao. */
    protected record ComFator(UUID identity, String email, BrowserClient browser, List<String> codes) {
    }

    protected ComFator comFator() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        BrowserClient b = client();
        assertThat(login(b, e, SENHA).field("status")).isEqualTo("mfa_enrollment_required");
        BrowserClient.Response r = enroll(b, id);
        assertThat(r.status()).isEqualTo(200);
        b.csrf = r.field("csrfToken");
        return new ComFator(id, e, b, r.listField("recoveryCodes"));
    }

    protected BrowserClient.Response verifyTotp(BrowserClient b, UUID identity) {
        return b.post("/api/v1/auth/mfa/verify", "{\"code\":\"" + code(identity) + "\"}");
    }

    protected static String cookie(BrowserClient.Response r, String name) {
        return r.setCookies().stream().filter(c -> c.startsWith(name + "=")).findFirst().orElse(null);
    }

    protected static long sessoesAtivas(UUID identity) throws Exception {
        return count("SELECT count(*) FROM identity_sessions WHERE identity_id = '" + identity + "' AND revoked_at IS NULL");
    }

    /**
     * Identidade ACTIVE com e-mail verificado e credencial, criada DIRETO no banco (superusuario): para os contextos em que o
     * cadastro esta desligado. Devolve o identity_id.
     */
    protected static UUID identidadeAtiva(String email, String senha) throws Exception {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO identities (identity_id, email, display_name, status) VALUES ('" + id + "', '" + email + "', 'Pessoa', 1)");
        exec("UPDATE identities SET email_verified_at = now(), status = 2 WHERE identity_id = '" + id + "'");
        exec("INSERT INTO identity_credentials (identity_id, password_hash) VALUES ('" + id + "', '" + HASHER.generate(senha.toCharArray()) + "')");
        return id;
    }

    /** Sessao de identidade com primeiro fator pela ENTRADA GERAL (login + cadastro do fator), para identidade ja ACTIVE. */
    protected BrowserClient sessaoPelaEntradaGeral(String email, UUID identity) {
        BrowserClient b = client();
        assertThat(login(b, email, SENHA).field("status")).isEqualTo("mfa_enrollment_required");
        BrowserClient.Response r = enroll(b, identity);
        assertThat(r.status()).isEqualTo(200);
        b.csrf = r.field("csrfToken");
        return b;
    }

    /** Espera, em pg_stat_activity, uma sessao de nekkora_platform esperando lock (ate ~5 s). */
    protected static boolean platformWaitingOnLock() throws Exception {
        return platformWaitingOnLock(1);
    }

    /** Espera, em pg_stat_activity, pelo menos {@code n} sessoes de nekkora_platform esperando lock (ate ~5 s). */
    protected static boolean platformWaitingOnLock(int n) throws Exception {
        for (int i = 0; i < 100; i++) {
            if ("t".equals(value("SELECT count(*) >= " + n + " FROM pg_stat_activity WHERE usename = 'nekkora_platform' AND wait_event_type = 'Lock'"))) {
                return true;
            }
            pause(50);
        }
        return false;
    }
}
