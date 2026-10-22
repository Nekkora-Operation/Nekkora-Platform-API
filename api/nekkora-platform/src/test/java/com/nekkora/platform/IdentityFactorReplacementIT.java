package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;
import com.nekkora.platform.auth.crypto.Base32;
import com.nekkora.platform.auth.crypto.Totp;
import com.nekkora.platform.mail.MailSender;

/**
 * Substituicao do fator da identidade pela SESSAO (missao PLATFORM-API-IDENTIDADE-002, PR 2; DC-17.13), nas rotas
 * {@code /api/v1/auth/mfa/enroll/start} e {@code /confirm} no modo sessao. Caminho A: sessao comum com step-up recente.
 * Caminho B: senha + codigo de recuperacao (sessao restrita, T0) e troca dentro de 15 min (T1), que levanta a restricao.
 * Papel real nekkora_platform, 017/018, Redis real. Cada recusa confere a resposta E o estado persistido (superusuario,
 * sem RLS): fatores, codigos, sessoes, restricao e eventos.
 */
class IdentityFactorReplacementIT extends IdentityTestBase {

    private static final String START = "/api/v1/auth/mfa/enroll/start";
    private static final String CONFIRM = "/api/v1/auth/mfa/enroll/confirm";
    private static final String FORBIDDEN = "{\"error\":\"forbidden\"}";
    private static final String INVALID = "{\"error\":\"invalid_request\"}";

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
    }

    @AfterEach
    void mailBack() {
        testMail.fail = false;
    }

    // ------------------------------------------------------------------ caminho A: sessao comum + step-up

    /**
     * Troca completa: fator antigo desabilitado, novo confirmado com o passo da confirmacao gravado (anti-replay), codigos
     * antigos invalidados e o conjunto novo (so os devolvidos) vivo, DEMAIS sessoes revogadas, a da requisicao continua,
     * evento 11 e aviso ao titular. Depois: o TOTP antigo nao serve mais; o codigo da confirmacao nao serve para step-up; o
     * TOTP novo serve.
     */
    @Test
    void caminhoAComStepUpSubstituiOFatorDeFormaCompleta() throws Exception {
        ComFator f = comFator();
        BrowserClient outra = segundaSessao(f);
        String s1 = sessao(f.browser());
        String s2 = sessao(outra);
        UUID antigo = fatorConfirmado(f.identity());
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);

        UUID novoKey = start(f.browser());
        UUID pendente = fatorPendente(f.identity());
        assertThat(pendente).isNotNull().isNotEqualTo(antigo);
        String codigo = code(novoKey);
        BrowserClient.Response r = f.browser().post(CONFIRM, "{\"code\":\"" + codigo + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        List<String> novos = r.listField("recoveryCodes");
        assertThat(r.body()).contains("\"restricted\":false").doesNotContain("csrfToken");

        assertThat(value("SELECT disabled_at IS NOT NULL FROM identity_mfa_factors WHERE factor_id = '" + antigo + "'")).as("antigo desabilitado").isEqualTo("t");
        assertThat(fatorConfirmado(f.identity())).as("o pendente conferido e o confirmado").isEqualTo(pendente);
        assertThat(Long.parseLong(value("SELECT last_used_step FROM identity_mfa_factors WHERE factor_id = '" + pendente + "'")))
                .as("passo da confirmacao gravado no fator novo").isEqualTo(passoDe(novoKey, codigo));
        assertThat(count("SELECT count(*) FROM identity_mfa_recovery_codes WHERE identity_id = '" + f.identity() + "' AND factor_id = '" + antigo
                + "' AND used_at IS NULL AND invalidated_at IS NULL")).as("codigos antigos invalidados").isZero();
        assertThat(count("SELECT count(*) FROM identity_mfa_recovery_codes WHERE identity_id = '" + f.identity() + "' AND factor_id = '" + pendente
                + "' AND used_at IS NULL AND invalidated_at IS NULL")).as("so o conjunto novo vivo").isEqualTo(novos.size());
        assertThat(novos).doesNotContainAnyElementsOf(f.codes());
        assertThat(value("SELECT revoked_at IS NOT NULL FROM identity_sessions WHERE identity_session_id = '" + s2 + "'")).as("outra sessao revogada").isEqualTo("t");
        assertThat(value("SELECT revoked_at IS NULL FROM identity_sessions WHERE identity_session_id = '" + s1 + "'")).as("a da requisicao continua").isEqualTo("t");
        assertThat(eventos(f.identity(), 11)).as("evento 11 (MFA_REPLACED)").isEqualTo(1);
        assertThat(mails(f.email()).stream().filter(m -> m.kind() == MailSender.Kind.NOTICE_MFA_REPLACED).count()).as("aviso ao titular").isEqualTo(1);

        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + codigo + "\"}").status()).as("codigo da confirmacao nao serve de novo").isEqualTo(403);
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).as("TOTP do fator antigo").isEqualTo(403);
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(novoKey) + "\"}").status()).as("TOTP do fator novo").isEqualTo(204);
        BrowserClient login = client();
        login(login, f.email(), SENHA);
        assertThat(login.post("/api/v1/auth/mfa/verify", "{\"recoveryCode\":\"" + f.codes().get(1) + "\"}").status()).as("codigo antigo").isEqualTo(401);
        assertThat(login.post("/api/v1/auth/mfa/verify", "{\"recoveryCode\":\"" + novos.get(0) + "\"}").status()).as("codigo novo").isEqualTo(200);
    }

    /** Sem step-up: o inicio e recusado pela funcao (prova de troca) sem criar fator; a conclusao sem pendente, tambem. */
    @Test
    void sessaoComumSemStepUpNaoInicia() throws Exception {
        ComFator f = comFator();
        Estado antes = estado(f.identity());
        BrowserClient.Response r = f.browser().post(START, null);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body()).isEqualTo(FORBIDDEN);
        assertThat(f.browser().post(CONFIRM, "{\"code\":\"123456\"}").status()).isEqualTo(403);
        assertThat(estado(f.identity())).isEqualTo(antes);
    }

    /**
     * Step-up que vence entre o inicio e a conclusao (sessao comum): a funcao reconfere a prova depois dos locks; 403 e
     * rollback integral (o pendente continua pendente, nada mais muda). Um step-up novo destrava a mesma conclusao.
     */
    @Test
    void stepUpVencidoAntesDaConclusaoRecusaSemEfeito() throws Exception {
        ComFator f = comFator();
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);
        UUID novoKey = start(f.browser());
        String s = sessao(f.browser());
        semTriggers("UPDATE identity_sessions SET mfa_step_up_at = mfa_step_up_at - interval '6 minutes' WHERE identity_session_id = '" + s + "'");
        Estado antes = estado(f.identity());
        assertThat(f.browser().post(CONFIRM, "{\"code\":\"" + code(novoKey) + "\"}").status()).isEqualTo(403);
        assertThat(estado(f.identity())).as("rollback integral").isEqualTo(antes);
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);
        assertThat(f.browser().post(CONFIRM, "{\"code\":\"" + code(novoKey) + "\"}").status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ caminho B: senha + codigo, sessao restrita

    /**
     * T0 -> T1: a sessao nascida de codigo de recuperacao e restrita (resposta e GET me/session), so troca o fator; depois
     * da troca, a MESMA sessao deixa de ser restrita (organizacoes liberadas), os codigos restantes do conjunto antigo sao
     * invalidados e a marca de recuperacao e levantada no banco.
     */
    @Test
    void caminhoBRecuperacaoDepoisTrocaLiberaASessaoRestrita() throws Exception {
        ComFator f = comFator();
        BrowserClient b = restrita(f);
        assertThat(b.get("/api/v1/me/session").body()).contains("\"restricted\":true");
        assertThat(b.get("/api/v1/me/organizations").status()).as("restrita: organizacoes recusadas").isEqualTo(403);
        assertThat(b.post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).as("restrita: sem step-up").isEqualTo(403);
        String s = sessao(b);

        UUID novoKey = start(b);
        BrowserClient.Response r = b.post(CONFIRM, "{\"code\":\"" + code(novoKey) + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).contains("\"restricted\":false");
        assertThat(value("SELECT recovery_cleared_at IS NOT NULL AND revoked_at IS NULL FROM identity_sessions WHERE identity_session_id = '" + s + "'"))
                .as("restricao levantada, sessao viva").isEqualTo("t");
        assertThat(b.get("/api/v1/me/session").body()).contains("\"restricted\":false");
        assertThat(b.get("/api/v1/me/organizations").status()).as("organizacoes liberadas").isEqualTo(200);
        assertThat(count("SELECT count(*) FROM identity_mfa_recovery_codes WHERE identity_id = '" + f.identity() + "' AND factor_id <> '"
                + fatorConfirmado(f.identity()) + "' AND used_at IS NULL AND invalidated_at IS NULL")).as("restantes do conjunto antigo invalidados").isZero();
        assertThat(eventos(f.identity(), 11)).isEqualTo(1);
    }

    /** Restricao sem saida depois de 15 min da marca: inicio e conclusao recusados, sem efeito; a sessao continua restrita. */
    @Test
    void sessaoRestritaForaDosQuinzeMinutosNaoTroca() throws Exception {
        ComFator f = comFator();
        BrowserClient b = restrita(f);
        UUID novoKey = start(b);
        String s = sessao(b);
        semTriggers("UPDATE identity_sessions SET recovery_used_at = recovery_used_at - interval '16 minutes' WHERE identity_session_id = '" + s + "'");
        Estado antes = estado(f.identity());
        assertThat(b.post(CONFIRM, "{\"code\":\"" + code(novoKey) + "\"}").status()).as("conclusao fora da janela").isEqualTo(403);
        assertThat(b.post(START, null).status()).as("inicio fora da janela").isEqualTo(403);
        assertThat(estado(f.identity())).isEqualTo(antes);
        assertThat(b.get("/api/v1/me/session").body()).contains("\"restricted\":true");
    }

    // ------------------------------------------------------------------ o TOTP tem de ser do fator novo

    /** Codigo do fator ANTIGO, codigo errado e codigo de um pendente descartado: 403, nada muda. Codigo de recuperacao: 400. */
    @Test
    void soOTotpDoFatorPendenteAtualConclui() throws Exception {
        ComFator f = comFator();
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);
        UUID primeiro = start(f.browser());
        UUID segundo = start(f.browser());
        Estado antes = estado(f.identity());
        assertThat(f.browser().post(CONFIRM, "{\"code\":\"" + code(f.identity()) + "\"}").status()).as("fator antigo").isEqualTo(403);
        assertThat(f.browser().post(CONFIRM, "{\"code\":\"" + code(primeiro) + "\"}").status()).as("pendente descartado").isEqualTo(403);
        assertThat(f.browser().post(CONFIRM, "{\"code\":\"000000\"}").status()).as("codigo qualquer").isEqualTo(403);
        BrowserClient.Response rc = f.browser().post(CONFIRM, "{\"recoveryCode\":\"" + f.codes().get(0) + "\"}");
        assertThat(rc.status()).as("codigo de recuperacao nao confirma fator").isEqualTo(400);
        assertThat(rc.body()).isEqualTo(INVALID);
        assertThat(f.browser().post(CONFIRM, "{\"code\":\"" + code(segundo) + "\",\"recoveryCode\":\"" + f.codes().get(0) + "\"}").status()).isEqualTo(400);
        assertThat(estado(f.identity())).isEqualTo(antes);
        assertThat(count("SELECT count(*) FROM identity_mfa_recovery_codes WHERE identity_id = '" + f.identity() + "' AND used_at IS NOT NULL")).isZero();
        assertThat(f.browser().post(CONFIRM, "{\"code\":\"" + code(segundo) + "\"}").status()).as("controle: pendente atual").isEqualTo(200);
    }

    /** Outra identidade, com step-up e o codigo correto do pendente de A, nao conclui nada de A (sessao e por identidade). */
    @Test
    void sessaoDeOutraIdentidadeNaoConcluiOPendenteAlheio() throws Exception {
        ComFator a = comFator();
        ComFator b = comFator();
        assertThat(a.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(a.identity()) + "\"}").status()).isEqualTo(204);
        assertThat(b.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(b.identity()) + "\"}").status()).isEqualTo(204);
        UUID pendenteDeA = start(a.browser());
        Estado ea = estado(a.identity());
        Estado eb = estado(b.identity());
        assertThat(b.browser().post(CONFIRM, "{\"code\":\"" + code(pendenteDeA) + "\"}").status()).isEqualTo(403);
        assertThat(estado(a.identity())).isEqualTo(ea);
        assertThat(estado(b.identity())).isEqualTo(eb);
    }

    // ------------------------------------------------------------------ modos, CSRF e sessao

    /** Os dois cookies: 400 nas duas rotas, sem tocar o banco (nem o desafio, nem o fator). */
    @Test
    void credenciaisDosDoisModosSaoRecusadas() throws Exception {
        ComFator f = comFator();
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);
        BrowserClient desafio = client();
        login(desafio, f.email(), SENHA);
        f.browser().cookies().put("__Host-nekkora_id_mfa", desafio.cookies().get("__Host-nekkora_id_mfa"));
        Estado antes = estado(f.identity());
        String tentativas = value("SELECT attempts FROM identity_auth_challenges WHERE identity_id = '" + f.identity() + "' AND consumed_at IS NULL AND invalidated_at IS NULL");
        for (String rota : List.of(START, CONFIRM)) {
            for (String csrf : new String[] {f.browser().csrf, desafio.csrf}) {
                BrowserClient.Response r = comCsrf(f.browser(), csrf, rota);
                assertThat(r.status()).as(rota).isEqualTo(400);
                assertThat(r.body()).isEqualTo(INVALID);
            }
        }
        assertThat(estado(f.identity())).isEqualTo(antes);
        assertThat(value("SELECT attempts FROM identity_auth_challenges WHERE identity_id = '" + f.identity() + "' AND consumed_at IS NULL AND invalidated_at IS NULL"))
                .as("desafio intocado").isEqualTo(tentativas);
        f.browser().cookies().remove("__Host-nekkora_id_mfa");
        assertThat(f.browser().post(START, null).status()).as("controle: so a sessao").isEqualTo(200);
    }

    /** Modo sessao exige o CSRF DA SESSAO e a Origin; sem eles, 403 e nada criado. */
    @Test
    void modoSessaoExigeCsrfDaSessao() throws Exception {
        ComFator f = comFator();
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);
        Estado antes = estado(f.identity());
        String csrf = f.browser().csrf;
        assertThat(comCsrf(f.browser(), null, START).status()).isEqualTo(403);
        assertThat(comCsrf(f.browser(), Tokens.newToken(), START).status()).isEqualTo(403);
        String origem = f.browser().origin;
        f.browser().origin = "https://outra.exemplo.test";
        try {
            assertThat(f.browser().post(START, null).status()).isEqualTo(403);
        } finally {
            f.browser().origin = origem;
        }
        assertThat(estado(f.identity())).isEqualTo(antes);
        f.browser().csrf = csrf;
        assertThat(f.browser().post(START, null).status()).as("controle").isEqualTo(200);
    }

    /** Sessao encerrada: 401 nas duas rotas e em me/session, sem efeito. */
    @Test
    void sessaoEncerradaE401() throws Exception {
        ComFator f = comFator();
        String token = f.browser().cookies().get("__Host-nekkora_id");
        String csrf = f.browser().csrf;
        assertThat(f.browser().post("/api/v1/auth/logout", null).status()).isEqualTo(204);
        f.browser().cookies().put("__Host-nekkora_id", token);
        f.browser().csrf = csrf;
        Estado antes = estado(f.identity());
        assertThat(f.browser().post(START, null).status()).isEqualTo(401);
        assertThat(f.browser().post(CONFIRM, "{\"code\":\"123456\"}").status()).isEqualTo(401);
        assertThat(f.browser().get("/api/v1/me/session").status()).isEqualTo(401);
        assertThat(estado(f.identity())).isEqualTo(antes);
    }

    // ------------------------------------------------------------------ esperas e dependencias

    /**
     * A conclusao espera o lock da identidade (outra transacao o segura); durante a espera, (a) o step-up vence, (b) a sessao
     * e revogada ou (c) o fator pendente e descartado. Em cada caso a conclusao recusa (403, 401, 403) com rollback integral.
     */
    @Test
    void autorizacaoSessaoEFatorReconferidosDepoisDaEspera() throws Exception {
        for (String caso : List.of("step_up", "sessao", "fator")) {
            ComFator f = comFator();
            assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);
            UUID novoKey = start(f.browser());
            String s = sessao(f.browser());
            String codigo = code(novoKey);
            ExecutorService ex = Executors.newSingleThreadExecutor();
            try (Connection c = DB.connectAsSuperuser()) {
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM identities WHERE identity_id = '" + f.identity() + "' FOR UPDATE")) {
                    ps.execute();
                }
                Future<BrowserClient.Response> conclusao = ex.submit(() -> f.browser().post(CONFIRM, "{\"code\":\"" + codigo + "\"}"));
                assertThat(platformWaitingOnLock()).as(caso + ": conclusao esperando a identidade").isTrue();
                switch (caso) {
                    case "step_up" -> semTriggers("UPDATE identity_sessions SET mfa_step_up_at = mfa_step_up_at - interval '6 minutes' WHERE identity_session_id = '" + s + "'");
                    case "sessao" -> exec("UPDATE identity_sessions SET revoked_at = greatest(clock_timestamp(), created_at) WHERE identity_session_id = '" + s + "'");
                    default -> semTriggers("UPDATE identity_mfa_factors SET disabled_at = greatest(clock_timestamp(), created_at) WHERE identity_id = '" + f.identity()
                            + "' AND confirmed_at IS NULL AND disabled_at IS NULL");
                }
                Estado antes = estado(f.identity());
                c.commit();
                BrowserClient.Response r = conclusao.get(30, TimeUnit.SECONDS);
                assertThat(r.status()).as(caso).isEqualTo("sessao".equals(caso) ? 401 : 403);
                assertThat(estado(f.identity())).as(caso + ": rollback integral").isEqualTo(antes);
            } finally {
                ex.shutdownNow();
            }
        }
    }

    /** Falha do aviso depois do commit nao desfaz a troca. */
    @Test
    void falhaDoAvisoNaoDesfazATroca() throws Exception {
        ComFator f = comFator();
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);
        UUID novoKey = start(f.browser());
        UUID pendente = fatorPendente(f.identity());
        testMail.fail = true;
        assertThat(f.browser().post(CONFIRM, "{\"code\":\"" + code(novoKey) + "\"}").status()).isEqualTo(200);
        assertThat(fatorConfirmado(f.identity())).isEqualTo(pendente);
        assertThat(eventos(f.identity(), 11)).isEqualTo(1);
    }

    /** Primeiro fator pelo desafio continua igual, agora com restricted=false explicito; verify por TOTP tambem. */
    @Test
    void modoDesafioContinuaEInformaRestricted() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        BrowserClient b = client();
        assertThat(login(b, e, SENHA).field("status")).isEqualTo("mfa_enrollment_required");
        BrowserClient.Response r = enroll(b, id);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("\"restricted\":false").contains("csrfToken").contains("recoveryCodes");
        BrowserClient c = client();
        login(c, e, SENHA);
        BrowserClient.Response v = verifyTotp(c, id);
        assertThat(v.status()).isEqualTo(200);
        assertThat(v.body()).contains("\"restricted\":false");
    }

    /**
     * Modo desafio: recusa da funcao (identidade suspensa depois do login, desafio ainda vivo) e 401 sem efeito, como o
     * contrato diz. Antes desta correcao, o NULL da funcao virava 500 (TypeMismatchDataAccessException no single()).
     */
    @Test
    void recusaDaFuncaoNoModoDesafioE401() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        BrowserClient b = client();
        assertThat(login(b, e, SENHA).field("status")).isEqualTo("mfa_enrollment_required");
        exec("UPDATE identities SET status = 3 WHERE identity_id = '" + id + "'");
        BrowserClient.Response r = b.post(START, null);
        assertThat(r.status()).as(r.body()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM identity_mfa_factors WHERE identity_id = '" + id + "'")).isZero();
    }

    // ------------------------------------------------------------------ apoio

    /** Estado persistido relevante da identidade: fatores, codigos, sessoes (revogacao e restricao) e eventos 11. */
    private record Estado(String fatores, String codigos, String sessoes, long eventos11) {
    }

    private static Estado estado(UUID identity) throws Exception {
        String w = " WHERE identity_id = '" + identity + "'";
        return new Estado(
                value("SELECT string_agg(factor_id || ':' || (confirmed_at IS NOT NULL) || (disabled_at IS NOT NULL) || coalesce(last_used_step::text, '-'), ',' ORDER BY factor_id) FROM identity_mfa_factors" + w),
                value("SELECT string_agg(code_id || ':' || (used_at IS NOT NULL) || (invalidated_at IS NOT NULL), ',' ORDER BY code_id) FROM identity_mfa_recovery_codes" + w),
                value("SELECT string_agg(identity_session_id || ':' || (revoked_at IS NOT NULL) || (recovery_cleared_at IS NOT NULL), ',' ORDER BY identity_session_id) FROM identity_sessions" + w),
                eventos(identity, 11));
    }

    /** Inicio no modo sessao; guarda o segredo novo sob uma chave propria (para code(chave)) e a devolve. */
    private static UUID start(BrowserClient b) {
        BrowserClient.Response r = b.post(START, null);
        assertThat(r.status()).as("enroll/start (sessao): " + r.body()).isEqualTo(200);
        String secret = URI.create(r.field("otpauthUri")).getRawQuery().replaceAll(".*secret=([A-Z2-7]+).*", "$1");
        UUID key = UUID.randomUUID();
        SECRETS.put(key, Base32.decode(secret));
        return key;
    }

    private static long passoDe(UUID key, String codigo) {
        long agora = Totp.step(java.time.Instant.now());
        for (long p = agora - 3; p <= agora + 1; p++) {
            if (Totp.code(SECRETS.get(key), p).equals(codigo)) {
                return p;
            }
        }
        throw new AssertionError("passo do codigo nao encontrado");
    }

    private BrowserClient segundaSessao(ComFator f) {
        BrowserClient outra = client();
        assertThat(login(outra, f.email(), SENHA).field("status")).isEqualTo("mfa_required");
        BrowserClient.Response v = verifyTotp(outra, f.identity());
        assertThat(v.status()).isEqualTo(200);
        outra.csrf = v.field("csrfToken");
        return outra;
    }

    /** Senha + codigo de recuperacao: sessao restrita (T0), com o CSRF dela. */
    private BrowserClient restrita(ComFator f) {
        BrowserClient b = client();
        login(b, f.email(), SENHA);
        BrowserClient.Response v = b.post("/api/v1/auth/mfa/verify", "{\"recoveryCode\":\"" + f.codes().get(0) + "\"}");
        assertThat(v.status()).isEqualTo(200);
        assertThat(v.body()).contains("\"restricted\":true");
        b.csrf = v.field("csrfToken");
        return b;
    }

    private static BrowserClient.Response comCsrf(BrowserClient b, String csrf, String rota) {
        String antes = b.csrf;
        b.csrf = csrf;
        try {
            return b.post(rota, CONFIRM.equals(rota) ? "{\"code\":\"123456\"}" : null);
        } finally {
            b.csrf = antes;
        }
    }

    private static String sessao(BrowserClient b) throws Exception {
        return value("SELECT identity_session_id FROM identity_sessions WHERE session_token_hash = '" + Tokens.sha256Hex(b.cookies().get("__Host-nekkora_id")) + "'");
    }

    private static UUID fatorConfirmado(UUID identity) throws Exception {
        return UUID.fromString(value("SELECT factor_id FROM identity_mfa_factors WHERE identity_id = '" + identity + "' AND confirmed_at IS NOT NULL AND disabled_at IS NULL"));
    }

    private static UUID fatorPendente(UUID identity) throws Exception {
        String v = value("SELECT factor_id FROM identity_mfa_factors WHERE identity_id = '" + identity + "' AND confirmed_at IS NULL AND disabled_at IS NULL");
        return v == null ? null : UUID.fromString(v);
    }

    private static long eventos(UUID identity, int tipo) throws Exception {
        return count("SELECT count(*) FROM identity_events WHERE identity_id = '" + identity + "' AND event_type = " + tipo);
    }

    /** Escrita de simulacao como superusuario sem triggers (as CHECKs continuam valendo). */
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
