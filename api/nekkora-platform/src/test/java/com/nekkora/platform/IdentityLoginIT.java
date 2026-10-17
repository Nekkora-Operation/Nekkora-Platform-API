package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;
import com.nekkora.platform.auth.crypto.Base32;
import com.nekkora.platform.auth.crypto.PasswordHasher;
import com.nekkora.platform.identity.SignupMailer;
import com.nekkora.platform.mail.MailSender;

/**
 * Entrada geral da identidade confirmada (Missao 1, desenho §6.3, DC-17.3, DC-17.13), com a chave LIGADA. Papel real
 * nekkora_platform, 017 R4b (funcoes identity_mfa_enroll_pending, identity_mfa_confirm_first, identity_login_complete),
 * Redis real. A identidade e criada pelo cadastro real (pedido + confirmacao), com o transporte de e-mail SIMULADO em
 * memoria ({@link PlatformTestBase.TestMailSender}). O TOTP e calculado do segredo da URI otpauth, como um aplicativo
 * autenticador faria. Cada recusa confere a resposta E o estado persistido (superusuario, sem RLS).
 */
class IdentityLoginIT extends IdentityTestBase {

    @Autowired
    com.nekkora.platform.auth.crypto.SecretCipher cipher;

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
    }

    // ------------------------------------------------------------------ passo 1

    @Test
    void loginSemFatorAbreDesafioDeCadastro() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        BrowserClient b = client();
        BrowserClient.Response r = login(b, e.toUpperCase(), SENHA);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.field("status")).isEqualTo("mfa_enrollment_required");
        String c = cookie(r, "__Host-nekkora_id_mfa");
        assertThat(c).as("cookie do desafio").contains("Path=/").contains("Secure").contains("HttpOnly").contains("SameSite=Strict");
        assertThat(cookie(r, "__Host-nekkora_id")).as("nenhuma sessao no passo 1").isNull();
        assertThat(value("SELECT purpose || '|' || (credential_hash IS NOT NULL) FROM identity_auth_challenges WHERE identity_id = '" + id + "'"))
                .isEqualTo("2|true");
        assertThat(sessoesAtivas(id)).isZero();
    }

    @Test
    void falhasDeCredencialTemRespostaUnica() throws Exception {
        String ativa = email();
        confirmed(ativa, SENHA);
        String pendente = email();
        assertThat(client().post("/api/v1/signup", "{\"email\":" + q(pendente) + "}").status()).isEqualTo(202);
        String suspensa = email();
        UUID idSuspensa = confirmed(suspensa, SENHA);
        exec("UPDATE identities SET status = 3 WHERE identity_id = '" + idSuspensa + "'");
        BrowserClient.Response semConta = login(client(), email(), SENHA);
        BrowserClient.Response senhaErrada = login(client(), ativa, "outra-senha-longa-9");
        BrowserClient.Response naoConfirmada = login(client(), pendente, SENHA);
        BrowserClient.Response suspensaComSenhaCerta = login(client(), suspensa, SENHA);
        for (BrowserClient.Response r : List.of(semConta, senhaErrada, naoConfirmada, suspensaComSenhaCerta)) {
            assertThat(r.status()).isEqualTo(401);
            assertThat(r.body()).isEqualTo(UNAUTH);
            assertThat(cookie(r, "__Host-nekkora_id_mfa")).isNull();
        }
        assertThat(login(client(), null, SENHA).body()).as("sem e-mail").isEqualTo(UNAUTH);
        assertThat(count("SELECT count(*) FROM identity_auth_challenges WHERE identity_id = '" + idSuspensa + "'")).as("suspensa: nenhum desafio").isZero();
    }

    @Test
    void r8LimitaSenhaErradaPorIdentidadeEIp() throws Exception {
        String e = email();
        confirmed(e, SENHA);
        BrowserClient b = client();
        for (int i = 0; i < 5; i++) {
            assertThat(login(b, e, "senha-errada-longa-" + i).status()).isEqualTo(401);
        }
        BrowserClient.Response limitado = login(b, e, SENHA);
        assertThat(limitado.status()).as("6a tentativa, mesmo com a senha certa").isEqualTo(429);
        assertThat(limitado.headers().firstValue("Retry-After")).isPresent();
    }

    // ------------------------------------------------------------------ primeiro fator

    @Test
    void cadastroDoPrimeiroFatorEmiteSessaoECodigos() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        BrowserClient b = client();
        login(b, e, SENHA);
        String csrf = b.csrf;
        b.csrf = null;
        assertThat(b.post("/api/v1/auth/mfa/enroll/start", null).status()).as("sem CSRF do desafio").isEqualTo(403);
        b.csrf = csrf;
        BrowserClient.Response start = b.post("/api/v1/auth/mfa/enroll/start", null);
        assertThat(start.field("otpauthUri")).startsWith("otpauth://totp/").contains("issuer=Nekkora");
        String secret = URI.create(start.field("otpauthUri")).getRawQuery().replaceAll(".*secret=([A-Z2-7]+).*", "$1");
        SECRETS.put(id, Base32.decode(secret));
        assertThat(value("SELECT count(*) FROM identity_mfa_factors WHERE identity_id = '" + id + "' AND confirmed_at IS NULL AND disabled_at IS NULL"))
                .as("fator pendente pela funcao").isEqualTo("1");

        BrowserClient.Response errado = b.post("/api/v1/auth/mfa/enroll/confirm", "{\"code\":\"000000\"}");
        assertThat(errado.status()).isEqualTo(401);
        assertThat(value("SELECT attempts FROM identity_auth_challenges WHERE identity_id = '" + id + "'")).as("tentativa registrada").isEqualTo("1");

        BrowserClient.Response ok = b.post("/api/v1/auth/mfa/enroll/confirm", "{\"code\":\"" + code(id) + "\"}");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.listField("recoveryCodes")).hasSize(10);
        assertThat(ok.field("csrfToken")).isNotBlank();
        String sc = cookie(ok, "__Host-nekkora_id");
        assertThat(sc).contains("SameSite=Lax").contains("HttpOnly").contains("Secure").contains("Path=/");
        assertThat(value("SELECT (confirmed_at IS NOT NULL) || '|' || key_id FROM identity_mfa_factors WHERE identity_id = '" + id + "' AND disabled_at IS NULL"))
                .startsWith("true|");
        assertThat(count("SELECT count(*) FROM identity_mfa_recovery_codes WHERE identity_id = '" + id + "' AND used_at IS NULL")).isEqualTo(10);
        assertThat(sessoesAtivas(id)).isEqualTo(1);
        assertThat(value("SELECT consumed_at IS NOT NULL FROM identity_auth_challenges WHERE identity_id = '" + id + "'")).isEqualTo("t");
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + id + "' AND event_type = 3")).as("MFA_ENROLLED").isEqualTo(1);
        String hashSessao = Tokens.sha256Hex(b.cookies().get("__Host-nekkora_id"));
        assertThat(count("SELECT count(*) FROM identity_sessions WHERE session_token_hash = '" + hashSessao + "' AND recovery_used_at IS NULL"))
                .as("so o hash do token da sessao no banco").isEqualTo(1);
    }

    @Test
    void desafioDeVerificacaoNaoServeParaCadastrarFator() throws Exception {
        ComFator f = comFator();
        BrowserClient b = client();
        assertThat(login(b, f.email(), SENHA).field("status")).isEqualTo("mfa_required");
        assertThat(b.post("/api/v1/auth/mfa/enroll/start", null).status()).as("proposito errado").isEqualTo(401);
        assertThat(count("SELECT count(*) FROM identity_mfa_factors WHERE identity_id = '" + f.identity() + "'")).as("nenhum fator novo").isEqualTo(1);
    }

    // ------------------------------------------------------------------ verificacao

    @Test
    void verificacaoTotpEmiteSessaoERecusaReplay() throws Exception {
        ComFator f = comFator();
        BrowserClient b = client();
        assertThat(login(b, f.email(), SENHA).field("status")).isEqualTo("mfa_required");
        String codigo = code(f.identity());
        BrowserClient.Response ok = b.post("/api/v1/auth/mfa/verify", "{\"code\":\"" + codigo + "\"}");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(cookie(ok, "__Host-nekkora_id")).contains("SameSite=Lax");
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + f.identity() + "' AND event_type = 4")).as("MFA_VERIFIED").isEqualTo(1);
        BrowserClient c = client();
        login(c, f.email(), SENHA);
        BrowserClient.Response replay = c.post("/api/v1/auth/mfa/verify", "{\"code\":\"" + codigo + "\"}");
        assertThat(replay.status()).as("passo ja usado (anti-replay no banco)").isEqualTo(401);
        assertThat(value("SELECT attempts FROM identity_auth_challenges WHERE identity_id = '" + f.identity()
                + "' AND consumed_at IS NULL AND invalidated_at IS NULL")).as("conta como tentativa").isEqualTo("1");
        assertThat(sessoesAtivas(f.identity())).isEqualTo(2);
    }

    @Test
    void codigoDeRecuperacaoCriaSessaoRestritaERevogaAsDemais() throws Exception {
        ComFator f = comFator();
        assertThat(sessoesAtivas(f.identity())).isEqualTo(1);
        BrowserClient b = client();
        login(b, f.email(), SENHA);
        String rc = f.codes().get(0);
        BrowserClient.Response ok = b.post("/api/v1/auth/mfa/verify", "{\"recoveryCode\":\"" + rc.toLowerCase() + "\"}");
        assertThat(ok.status()).isEqualTo(200);
        String hash = Tokens.sha256Hex(b.cookies().get("__Host-nekkora_id"));
        assertThat(value("SELECT (recovery_used_at IS NOT NULL) || '|' || (recovery_cleared_at IS NULL) FROM identity_sessions WHERE session_token_hash = '" + hash + "'"))
                .as("sessao restrita (T0)").isEqualTo("true|true");
        assertThat(sessoesAtivas(f.identity())).as("a sessao anterior foi revogada").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM identity_mfa_recovery_codes WHERE identity_id = '" + f.identity() + "' AND used_at IS NOT NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM identity_mfa_recovery_codes WHERE identity_id = '" + f.identity() + "' AND used_at IS NULL AND invalidated_at IS NULL"))
                .as("os demais codigos sobrevivem ate T1").isEqualTo(9);
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + f.identity() + "' AND event_type = 5")).as("RECOVERY_CODE_USED").isEqualTo(1);
        assertThat(mails(f.email())).anyMatch(m -> m.kind() == MailSender.Kind.NOTICE_RECOVERY_CODE_USED && m.token() == null);
        BrowserClient c = client();
        login(c, f.email(), SENHA);
        assertThat(c.post("/api/v1/auth/mfa/verify", "{\"recoveryCode\":\"" + rc + "\"}").status()).as("codigo de uso unico").isEqualTo(401);
    }

    @Test
    void verifyExigeExatamenteUmCodigo() throws Exception {
        ComFator f = comFator();
        BrowserClient b = client();
        login(b, f.email(), SENHA);
        assertThat(b.post("/api/v1/auth/mfa/verify", "{}").status()).isEqualTo(400);
        assertThat(b.post("/api/v1/auth/mfa/verify", "{\"code\":\"123456\",\"recoveryCode\":\"" + f.codes().get(1) + "\"}").status()).isEqualTo(400);
    }

    @Test
    void cincoCodigosErradosInvalidamODesafio() throws Exception {
        ComFator f = comFator();
        BrowserClient b = client();
        login(b, f.email(), SENHA);
        for (int i = 0; i < 5; i++) {
            assertThat(b.post("/api/v1/auth/mfa/verify", "{\"code\":\"000000\"}").status()).isEqualTo(401);
        }
        assertThat(value("SELECT (invalidated_at IS NOT NULL) || '|' || attempts || '|' || (credential_hash IS NULL) FROM identity_auth_challenges WHERE identity_id = '"
                + f.identity() + "' AND consumed_at IS NULL ORDER BY created_at DESC LIMIT 1")).isEqualTo("true|5|true");
        assertThat(verifyTotp(b, f.identity()).status()).as("desafio invalidado: nem o codigo certo passa").isIn(401, 403);
        assertThat(sessoesAtivas(f.identity())).as("so a do cadastro").isEqualTo(1);
    }

    @Test
    void senhaTrocadaDepoisDoPasso1NaoGeraSessao() throws Exception {
        ComFator f = comFator();
        BrowserClient b = client();
        login(b, f.email(), SENHA);
        exec("UPDATE identity_credentials SET password_hash = '" + HASHER.generate("senha-trocada-longa-7".toCharArray()) + "' WHERE identity_id = '" + f.identity() + "'");
        assertThat(verifyTotp(b, f.identity()).status()).as("SENHA_OBSOLETA").isEqualTo(401);
        assertThat(value("SELECT invalidated_at IS NOT NULL FROM identity_auth_challenges WHERE identity_id = '" + f.identity()
                + "' AND consumed_at IS NULL ORDER BY created_at DESC LIMIT 1")).as("desafio invalidado pela funcao").isEqualTo("t");
        assertThat(sessoesAtivas(f.identity())).isEqualTo(1);
    }

    @Test
    void credencialComParametrosAntigosERehashadaNaConclusao() throws Exception {
        ComFator f = comFator();
        // no piso OWASP aceito pelo hasher, abaixo dos parametros vigentes do teste (m=65536, t=3): needsRehash = true
        PasswordHasher fraco = new PasswordHasher(new PasswordHasher.Parameters(19456, 2, 1), 2, 2, 5000);
        exec("UPDATE identity_credentials SET password_hash = '" + fraco.generate(SENHA.toCharArray()) + "' WHERE identity_id = '" + f.identity() + "'");
        BrowserClient b = client();
        login(b, f.email(), SENHA);
        assertThat(value("SELECT pending_password_hash IS NOT NULL FROM identity_auth_challenges WHERE identity_id = '" + f.identity()
                + "' AND consumed_at IS NULL AND invalidated_at IS NULL")).as("re-hash pendente no desafio").isEqualTo("t");
        assertThat(verifyTotp(b, f.identity()).status()).isEqualTo(200);
        String novo = value("SELECT password_hash FROM identity_credentials WHERE identity_id = '" + f.identity() + "'");
        assertThat(novo).as("re-hash com os parametros vigentes").doesNotContain("m=19456");
        assertThat(HASHER.verify(SENHA.toCharArray(), novo)).isTrue();
    }

    @Test
    void verificacoesConcorrentesComOMesmoCodigoCriamUmaSessao() throws Exception {
        ComFator f = comFator();
        BrowserClient b = client();
        login(b, f.email(), SENHA);
        String codigo = code(f.identity());
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            // dois clientes com o MESMO desafio (cookie e CSRF copiados): cada um com o seu mapa de cookies
            BrowserClient c = browser();
            c.forwardedIp = b.forwardedIp;
            c.cookies().putAll(b.cookies());
            c.csrf = b.csrf;
            List<Future<Integer>> fs = new ArrayList<>();
            for (BrowserClient x : List.of(b, c)) {
                fs.add(ex.submit(() -> x.post("/api/v1/auth/mfa/verify", "{\"code\":\"" + codigo + "\"}").status()));
            }
            List<Integer> st = new ArrayList<>();
            for (Future<Integer> x : fs) {
                st.add(x.get());
            }
            assertThat(st).as("uma conclusao").containsOnlyOnce(200);
        } finally {
            ex.shutdownNow();
        }
        assertThat(sessoesAtivas(f.identity())).isEqualTo(2);
    }

    // ------------------------------------------------------------------ concorrencia (revisao do bloco 2)

    /** Roda {@code acao} numa thread enquanto o superusuario segura a identidade; {@code durante} roda com a espera observada. */
    private BrowserClient.Response comIdentidadeTravada(UUID identity, java.util.concurrent.Callable<BrowserClient.Response> acao,
                                                        SqlDurante durante) throws Exception {
        return comLinhaTravada("SELECT 1 FROM identities WHERE identity_id = '" + identity + "' FOR UPDATE", acao, durante);
    }

    /** Como acima, travando a linha que {@code travaSql} seleciona (FOR UPDATE, sem alterar). */
    private BrowserClient.Response comLinhaTravada(String travaSql, java.util.concurrent.Callable<BrowserClient.Response> acao,
                                                   SqlDurante durante) throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(travaSql)) {
                assertThat(ps.executeQuery().next()).as("linha travada").isTrue();
            }
            Future<BrowserClient.Response> f = ex.submit(acao);
            assertThat(platformWaitingOnLock()).as("a requisicao espera o lock da identidade").isTrue();
            durante.run(c);
            c.commit();
            return f.get();
        } finally {
            ex.shutdownNow();
        }
    }

    /** POST com os mesmos cabecalhos do BrowserClient (cookies, Origin, CSRF, IP), mas prazo de 120 s. */
    private BrowserClient.Response postLongo(BrowserClient b, String path, String json) throws Exception {
        java.net.http.HttpRequest.Builder rb = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path))
                .timeout(java.time.Duration.ofSeconds(120)).POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json").header("Origin", b.origin).header("X-CSRF-Token", b.csrf)
                .header("X-Forwarded-For", b.forwardedIp);
        if (!b.cookies().isEmpty()) {
            rb.header("Cookie", String.join("; ", b.cookies().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
        }
        java.net.http.HttpResponse<String> r = java.net.http.HttpClient.newHttpClient().send(rb.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        return new BrowserClient.Response(r.statusCode(), r.body(), r.headers());
    }

    @FunctionalInterface
    private interface SqlDurante {
        void run(Connection c) throws Exception;
    }

    private static void sql(Connection c, String s) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(s)) {
            ps.execute();
        }
    }

    /**
     * Fator A substituido por B DEPOIS do passo 1 e enquanto a verificacao espera: o codigo calculado de A nao pode
     * concluir sobre B. Recusa, nenhuma sessao, passo de B intocado.
     */
    @Test
    void trocaDeFatorDuranteAEsperaNaoConcluiSobreOFatorNovo() throws Exception {
        ComFator f = comFator();
        BrowserClient b = client();
        login(b, f.email(), SENHA);
        String fatorA = value("SELECT factor_id FROM identity_mfa_factors WHERE identity_id = '" + f.identity() + "' AND confirmed_at IS NOT NULL AND disabled_at IS NULL");
        String codigoDeA = code(f.identity());
        // B: segredo DIFERENTE, cifrado como a troca legitima o cifraria (AAD da identidade, chave vigente)
        byte[] segredoB = com.nekkora.platform.auth.crypto.Totp.newSecret();
        com.nekkora.platform.auth.crypto.SecretCipher.Encrypted b2 = cipher.encryptForIdentity(segredoB, f.identity());
        String ct = java.util.HexFormat.of().formatHex(b2.ciphertext());
        String nonce = java.util.HexFormat.of().formatHex(b2.nonce());
        long antes = sessoesAtivas(f.identity());
        BrowserClient.Response r = comIdentidadeTravada(f.identity(), () -> b.post("/api/v1/auth/mfa/verify", "{\"code\":\"" + codigoDeA + "\"}"), c -> {
            // a troca como identity_mfa_replace a faria (papel dono): A desabilitado, B confirmado
            sql(c, "SET LOCAL ROLE nekkora_identity_owner");
            sql(c, "UPDATE identity_mfa_factors SET disabled_at = clock_timestamp() WHERE factor_id = '" + fatorA + "'");
            sql(c, "INSERT INTO identity_mfa_factors (identity_id, factor_type, secret_ciphertext, secret_nonce, key_id) VALUES ('" + f.identity()
                    + "', 1, decode('" + ct + "', 'hex'), decode('" + nonce + "', 'hex'), '" + b2.keyId() + "')");
            sql(c, "UPDATE identity_mfa_factors SET confirmed_at = clock_timestamp() WHERE identity_id = '" + f.identity()
                    + "' AND confirmed_at IS NULL AND disabled_at IS NULL");
        });
        assertThat(r.status()).as("codigo de A nao conclui sobre B").isEqualTo(401);
        assertThat(sessoesAtivas(f.identity())).as("nenhuma sessao nova").isEqualTo(antes);
        assertThat(value("SELECT last_used_step IS NULL FROM identity_mfa_factors WHERE identity_id = '" + f.identity()
                + "' AND confirmed_at IS NOT NULL AND disabled_at IS NULL")).as("passo de B intocado").isEqualTo("t");
    }

    /** Passo TOTP valido no envio que VENCE durante a espera pelo lock: recusado, com o desafio ainda valido. */
    @Test
    void passoTotpVencidoDuranteAEsperaERecusado() throws Exception {
        ComFator f = comFator();
        BrowserClient b = client();
        login(b, f.email(), SENHA);
        // o passo precisa ser POSTERIOR ao ultimo usado pelo fator (senao o anti-replay recusaria antes de qualquer espera) e
        // o mais antigo ainda aceito (agora-1), para vencer na proxima virada: espera ate agora-1 > ultimo usado
        long ultimo = Long.parseLong(value("SELECT last_used_step FROM identity_mfa_factors WHERE identity_id = '" + f.identity()
                + "' AND confirmed_at IS NOT NULL AND disabled_at IS NULL"));
        while (com.nekkora.platform.auth.crypto.Totp.step(java.time.Instant.now()) - 1 <= ultimo) {
            pause(500);
        }
        long passo = com.nekkora.platform.auth.crypto.Totp.step(java.time.Instant.now()) - 1;
        String codigo = com.nekkora.platform.auth.crypto.Totp.code(SECRETS.get(f.identity()), passo);
        long antes = sessoesAtivas(f.identity());
        // a espera ate o passo sair da janela e de 30 a 60 s: envio com prazo longo (o BrowserClient usa 30 s)
        BrowserClient.Response r = comIdentidadeTravada(f.identity(), () -> postLongo(b, "/api/v1/auth/mfa/verify", "{\"code\":\"" + codigo + "\"}"), c -> {
            // segura ate o passo sair da janela aceita (agora-1 .. agora+1)
            while (com.nekkora.platform.auth.crypto.Totp.step(java.time.Instant.now()) < passo + 2) {
                pause(500);
            }
        });
        assertThat(r.status()).as("passo fora da janela depois da espera").isEqualTo(401);
        assertThat(value("SELECT (consumed_at IS NULL) || '|' || (invalidated_at IS NULL) || '|' || (expires_at > clock_timestamp()) FROM identity_auth_challenges WHERE identity_id = '"
                + f.identity() + "' ORDER BY created_at DESC LIMIT 1")).as("o desafio continua valido e nao foi consumido").isEqualTo("true|true|true");
        assertThat(sessoesAtivas(f.identity())).isEqualTo(antes);
    }

    /** Passo mais antigo ainda aceito (agora-1) e POSTERIOR a {@code ultimo}; espera a virada se preciso. */
    private static long passoNovoMaisAntigo(Long ultimo) {
        while (ultimo != null && com.nekkora.platform.auth.crypto.Totp.step(java.time.Instant.now()) - 1 <= ultimo) {
            pause(500);
        }
        return com.nekkora.platform.auth.crypto.Totp.step(java.time.Instant.now()) - 1;
    }

    /** Segura a linha ate o passo sair da janela aceita (agora-1 .. agora+1), sem alterar nada. */
    private static void ateOPassoVencer(long passo) {
        while (com.nekkora.platform.auth.crypto.Totp.step(java.time.Instant.now()) < passo + 2) {
            pause(500);
        }
    }

    /**
     * Espera POSTERIOR a validacao (revisao 2): outra transacao trava SO a linha do desafio, sem altera-la. A verificacao
     * trava a identidade, valida um TOTP valido e nao usado e fica esperando o desafio DENTRO de identity_login_complete.
     * O passo vence durante essa espera, com o desafio ainda valido. Recusa, sem sessao, sem consumo, sem avancar o fator.
     */
    @Test
    void passoTotpVencidoNaEsperaPeloDesafioDentroDaFuncaoERecusado() throws Exception {
        ComFator f = comFator();
        BrowserClient b = client();
        login(b, f.email(), SENHA);
        String desafio = value("SELECT challenge_id FROM identity_auth_challenges WHERE identity_id = '" + f.identity()
                + "' AND consumed_at IS NULL AND invalidated_at IS NULL ORDER BY created_at DESC LIMIT 1");
        Long ultimo = Long.valueOf(value("SELECT last_used_step FROM identity_mfa_factors WHERE identity_id = '" + f.identity()
                + "' AND confirmed_at IS NOT NULL AND disabled_at IS NULL"));
        long passo = passoNovoMaisAntigo(ultimo);
        String codigo = com.nekkora.platform.auth.crypto.Totp.code(SECRETS.get(f.identity()), passo);
        long antes = sessoesAtivas(f.identity());
        BrowserClient.Response r = comLinhaTravada("SELECT 1 FROM identity_auth_challenges WHERE challenge_id = '" + desafio + "' FOR UPDATE",
                () -> postLongo(b, "/api/v1/auth/mfa/verify", "{\"code\":\"" + codigo + "\"}"), c -> ateOPassoVencer(passo));
        assertThat(r.status()).as("passo vencido durante a espera dentro da funcao").isEqualTo(401);
        assertThat(sessoesAtivas(f.identity())).as("nenhuma sessao").isEqualTo(antes);
        assertThat(value("SELECT (consumed_at IS NULL) || '|' || (expires_at > clock_timestamp()) FROM identity_auth_challenges WHERE challenge_id = '" + desafio + "'"))
                .as("desafio nao consumido e ainda valido").isEqualTo("true|true");
        assertThat(value("SELECT last_used_step FROM identity_mfa_factors WHERE identity_id = '" + f.identity()
                + "' AND confirmed_at IS NOT NULL AND disabled_at IS NULL")).as("fator nao avancou").isEqualTo(String.valueOf(ultimo));
    }

    /** O mesmo intervalo no PRIMEIRO cadastro do fator: identity_mfa_confirm_first espera o desafio depois da validacao. */
    @Test
    void passoTotpVencidoNaEsperaPeloDesafioNoPrimeiroCadastroERecusado() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        BrowserClient b = client();
        assertThat(login(b, e, SENHA).field("status")).isEqualTo("mfa_enrollment_required");
        BrowserClient.Response start = b.post("/api/v1/auth/mfa/enroll/start", null);
        String segredo = URI.create(start.field("otpauthUri")).getRawQuery().replaceAll(".*secret=([A-Z2-7]+).*", "$1");
        byte[] s = Base32.decode(segredo);
        String desafio = value("SELECT challenge_id FROM identity_auth_challenges WHERE identity_id = '" + id + "' AND consumed_at IS NULL AND invalidated_at IS NULL");
        long passo = passoNovoMaisAntigo(null);
        String codigo = com.nekkora.platform.auth.crypto.Totp.code(s, passo);
        BrowserClient.Response r = comLinhaTravada("SELECT 1 FROM identity_auth_challenges WHERE challenge_id = '" + desafio + "' FOR UPDATE",
                () -> postLongo(b, "/api/v1/auth/mfa/enroll/confirm", "{\"code\":\"" + codigo + "\"}"), c -> ateOPassoVencer(passo));
        assertThat(r.status()).as("passo vencido durante a espera dentro da funcao").isEqualTo(401);
        assertThat(sessoesAtivas(id)).as("nenhuma sessao").isZero();
        assertThat(value("SELECT (consumed_at IS NULL) || '|' || (expires_at > clock_timestamp()) FROM identity_auth_challenges WHERE challenge_id = '" + desafio + "'"))
                .as("desafio nao consumido e ainda valido").isEqualTo("true|true");
        assertThat(value("SELECT (confirmed_at IS NULL) || '|' || (last_used_step IS NULL) FROM identity_mfa_factors WHERE identity_id = '" + id + "' AND disabled_at IS NULL"))
                .as("fator continua pendente, sem passo").isEqualTo("true|true");
        assertThat(count("SELECT count(*) FROM identity_mfa_recovery_codes WHERE identity_id = '" + id + "'")).as("nenhum codigo gravado").isZero();
    }

    /** Suspensao entre a primeira leitura (e o Argon2) e a emissao do desafio: resposta generica, nenhum desafio. */
    @Test
    void suspensaoAntesDaEmissaoNaoEmiteDesafio() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        BrowserClient b = client();
        BrowserClient.Response r = comIdentidadeTravada(id, () -> login(b, e, SENHA),
                c -> sql(c, "UPDATE identities SET status = 3 WHERE identity_id = '" + id + "'"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body()).isEqualTo(UNAUTH);
        assertThat(cookie(r, "__Host-nekkora_id_mfa")).isNull();
        assertThat(count("SELECT count(*) FROM identity_auth_challenges WHERE identity_id = '" + id + "'")).as("nenhum desafio").isZero();
    }

    /** Troca de senha entre a primeira leitura (e o Argon2) e a emissao do desafio: resposta generica, nenhum desafio. */
    @Test
    void trocaDeSenhaAntesDaEmissaoNaoEmiteDesafio() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        String novo = HASHER.generate("senha-nova-trocada-1".toCharArray());
        BrowserClient b = client();
        BrowserClient.Response r = comIdentidadeTravada(id, () -> login(b, e, SENHA),
                c -> sql(c, "UPDATE identity_credentials SET password_hash = '" + novo + "' WHERE identity_id = '" + id + "'"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body()).isEqualTo(UNAUTH);
        assertThat(count("SELECT count(*) FROM identity_auth_challenges WHERE identity_id = '" + id + "'")).as("nenhum desafio").isZero();
    }

    // ------------------------------------------------------------------ logout e fronteira dos cookies

    @Test
    void logoutRevogaASessaoEESemEfeitoSemSessao() throws Exception {
        ComFator f = comFator();
        BrowserClient b = f.browser();
        String hash = Tokens.sha256Hex(b.cookies().get("__Host-nekkora_id"));
        String csrf = b.csrf;
        b.csrf = "token-errado-" + "x".repeat(30);
        assertThat(b.post("/api/v1/auth/logout", null).status()).as("CSRF errado com sessao valida").isEqualTo(403);
        assertThat(sessoesAtivas(f.identity())).isEqualTo(1);
        b.csrf = csrf;
        BrowserClient.Response r = b.post("/api/v1/auth/logout", null);
        assertThat(r.status()).isEqualTo(204);
        assertThat(cookie(r, "__Host-nekkora_id")).contains("Max-Age=0");
        assertThat(value("SELECT revoked_at IS NOT NULL FROM identity_sessions WHERE session_token_hash = '" + hash + "'")).isEqualTo("t");
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + f.identity() + "' AND event_type = 10")).as("LOGOUT").isEqualTo(1);
        assertThat(client().post("/api/v1/auth/logout", null).status()).as("sem sessao: no-op").isEqualTo(204);
    }

    @Test
    void cookieDeIdentidadeNaoValeNasRotasDeOrganizacao() throws Exception {
        ComFator f = comFator();
        Org org = newOrganization();
        BrowserClient b = f.browser();
        assertThat(b.get("/api/v1/o/" + org.id() + "/me").status()).as("sessao de identidade nao e sessao de organizacao").isEqualTo(401);
    }
}
