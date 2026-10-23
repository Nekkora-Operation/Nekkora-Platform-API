package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;
import com.nekkora.platform.auth.crypto.Base32;
import com.nekkora.platform.mail.FakeGraph;

/**
 * Revisao 2 do PR 5: com a intencao de e-mail DURAVEL (transporte m365, fila gravada na transacao do ato), a gravacao pode
 * esperar locks - e nenhuma reconferencia temporal pode ter ficado ANTES dessa espera. Cada prova bloqueia a gravacao na
 * fila (LOCK TABLE mail_outbox IN SHARE MODE noutra transacao), deixa vencer o que a operacao confere e solta o bloqueio:
 * a operacao tem de recusar com rollback integral, inclusive da intencao de e-mail.
 */
class IdentityIntentFinalChecksIT extends IdentityTestBase {

    private static final String SENHA_CERT = "senha-cert-finais";
    private static final FakeGraph FAKE;
    private static final Path P12;

    static {
        try {
            FAKE = new FakeGraph();
            P12 = FakeGraph.testCertificate(Files.createTempDirectory("nekkora-finais-"), SENHA_CERT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.nekkora.platform.auth.crypto.IdentityHmac hmac;

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.mail.transport", () -> "m365");
        r.add("nekkora.mail.remetente", () -> "no-reply@exemplo.test");
        r.add("nekkora.mail.m365.tenant-id", () -> "00000000-0000-0000-0000-00000000aaaa");
        r.add("nekkora.mail.m365.client-id", () -> "00000000-0000-0000-0000-00000000bbbb");
        r.add("nekkora.mail.m365.certificado", P12::toString);
        r.add("nekkora.mail.m365.certificado-senha", () -> SENHA_CERT);
        r.add("nekkora.mail.m365.login-url", FAKE::base);
        r.add("nekkora.mail.m365.graph-url", FAKE::base);
        r.add("nekkora.mail.m365.timeout", () -> "1s");
        r.add("nekkora.mail.despacho.enabled", () -> "false");
    }

    /**
     * Troca de fator a partir da sessao RESTRITA (marca de recuperacao): a sessao vence enquanto a gravacao da intencao
     * espera. 401, e nada muda: fatores, codigos, marca de recuperacao, demais sessoes, evento 11 e intencao de e-mail.
     */
    @Test
    void trocaDeFatorComSessaoVencidaDuranteAGravacaoDaIntencaoDesfazTudo() throws Exception {
        String email = "finais-" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.test";
        UUID id = identidadeAtiva(email, SENHA);
        BrowserClient primeira = client();
        assertThat(login(primeira, email, SENHA).field("status")).isEqualTo("mfa_enrollment_required");
        BrowserClient.Response e = enroll(primeira, id);
        assertThat(e.status()).isEqualTo(200);
        List<String> codigos = e.listField("recoveryCodes");
        BrowserClient r = client();
        login(r, email, SENHA);
        BrowserClient.Response v = r.post("/api/v1/auth/mfa/verify", "{\"recoveryCode\":\"" + codigos.get(0) + "\"}");
        assertThat(v.status()).isEqualTo(200);
        r.csrf = v.field("csrfToken");
        BrowserClient.Response s = r.post("/api/v1/auth/mfa/enroll/start", null);
        assertThat(s.status()).isEqualTo(200);
        UUID chave = UUID.randomUUID();
        SECRETS.put(chave, Base32.decode(URI.create(s.field("otpauthUri")).getRawQuery().replaceAll(".*secret=([A-Z2-7]+).*", "$1")));
        String sessao = value("SELECT identity_session_id FROM identity_sessions WHERE session_token_hash = '"
                + Tokens.sha256Hex(r.cookies().get("__Host-nekkora_id")) + "'");
        semTriggers("UPDATE identity_sessions SET expires_at = clock_timestamp() + interval '3 seconds' WHERE identity_session_id = '" + sessao + "'");
        String antes = estado(id);
        String codigo = code(chave);

        BrowserClient.Response t = comFilaBloqueada(4500, () -> r.post("/api/v1/auth/mfa/enroll/confirm", "{\"code\":\"" + codigo + "\"}"));
        assertThat(t.status()).as(t.body()).isEqualTo(401);
        assertThat(estado(id)).as("rollback integral").isEqualTo(antes);
        assertThat(value("SELECT recovery_cleared_at IS NULL FROM identity_sessions WHERE identity_session_id = '" + sessao + "'"))
                .as("marca de recuperacao intacta").isEqualTo("t");
        assertThat(count("SELECT count(*) FROM mail_outbox WHERE kind = 'NOTICE_MFA_REPLACED' AND event_key LIKE '%" + fatorPendente(id) + "'"))
                .as("intencao desfeita junto").isZero();
    }

    /**
     * Troca de senha: o step-up vence enquanto a gravacao da intencao espera; a prova da funcao (medida depois) recusa.
     * 403, senha inalterada, nenhum evento 12, nenhuma intencao.
     */
    @Test
    void trocaDeSenhaComStepUpVencidoDuranteAGravacaoDaIntencaoNaoTroca() throws Exception {
        String email = "finais-" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.test";
        UUID id = identidadeAtiva(email, SENHA);
        BrowserClient b = sessaoPelaEntradaGeral(email, id);
        assertThat(b.post("/api/v1/auth/step-up", "{\"code\":\"" + code(id) + "\"}").status()).isEqualTo(204);
        String sessao = value("SELECT identity_session_id FROM identity_sessions WHERE session_token_hash = '"
                + Tokens.sha256Hex(b.cookies().get("__Host-nekkora_id")) + "'");
        // step-up que vence 3 s depois de agora (a janela e de 5 min)
        semTriggers("UPDATE identity_sessions SET mfa_step_up_at = clock_timestamp() - interval '297 seconds' WHERE identity_session_id = '" + sessao + "'");
        String hash = value("SELECT password_hash FROM identity_credentials WHERE identity_id = '" + id + "'");

        BrowserClient.Response t = comFilaBloqueada(4500,
                () -> b.post("/api/v1/auth/password", "{\"currentPassword\":" + q(SENHA) + ",\"newPassword\":\"senha-nova-bloqueada-9\"}"));
        assertThat(t.status()).as(t.body()).isEqualTo(403);
        assertThat(value("SELECT password_hash FROM identity_credentials WHERE identity_id = '" + id + "'")).isEqualTo(hash);
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + id + "' AND event_type = 12")).isZero();
        assertThat(count("SELECT count(*) FROM mail_outbox WHERE kind = 'NOTICE_PASSWORD_CHANGED' AND recipient_hmac = '" + hmac.global(email) + "'"))
                .as("nenhuma intencao").isZero();
    }

    // ------------------------------------------------------------------ apoio

    /** Bloqueia INSERT em mail_outbox por {@code ms} (outra transacao), dispara a requisicao e devolve a resposta. */
    private static BrowserClient.Response comFilaBloqueada(long ms, java.util.concurrent.Callable<BrowserClient.Response> req) throws Exception {
        CountDownLatch bloqueada = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try (Connection c = DB.connectAsSuperuser()) {
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement("LOCK TABLE mail_outbox IN SHARE MODE")) {
                    ps.execute();
                }
                bloqueada.countDown();
                Thread.sleep(ms);
                c.commit();
            } catch (Exception e) {
                bloqueada.countDown();
            }
        });
        t.setDaemon(true);
        t.start();
        assertThat(bloqueada.await(10, TimeUnit.SECONDS)).isTrue();
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<BrowserClient.Response> f = ex.submit(req);
            assertThat(platformWaitingOnLock()).as("a gravacao da intencao espera o bloqueio").isTrue();
            return f.get(60, TimeUnit.SECONDS);
        } finally {
            ex.shutdownNow();
            t.join(15_000);
        }
    }

    private static String estado(UUID identity) throws Exception {
        String w = " WHERE identity_id = '" + identity + "'";
        return value("SELECT string_agg(factor_id || ':' || (confirmed_at IS NOT NULL) || (disabled_at IS NOT NULL), ',' ORDER BY factor_id) FROM identity_mfa_factors" + w)
                + "|" + value("SELECT string_agg(code_id || ':' || (used_at IS NOT NULL) || (invalidated_at IS NOT NULL), ',' ORDER BY code_id) FROM identity_mfa_recovery_codes" + w)
                + "|" + value("SELECT string_agg(identity_session_id || ':' || (revoked_at IS NOT NULL) || (recovery_cleared_at IS NOT NULL), ',' ORDER BY identity_session_id) FROM identity_sessions" + w)
                + "|" + value("SELECT count(*) FROM identity_events" + w + " AND event_type = 11");
    }

    private static UUID fatorPendente(UUID identity) throws Exception {
        return UUID.fromString(value("SELECT factor_id FROM identity_mfa_factors WHERE identity_id = '" + identity + "' AND confirmed_at IS NULL AND disabled_at IS NULL"));
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
