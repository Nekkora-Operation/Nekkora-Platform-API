package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;
import com.nekkora.platform.auth.crypto.Totp;

/**
 * Step-up da sessao de identidade (P1 do contrato de 2026-10-13, aprovado em 2026-10-14), com a chave da entrada geral
 * LIGADA. Papel real nekkora_platform, 017 R5 (evento 13 = STEP_UP), Redis real. Cada recusa confere a resposta E o
 * estado persistido (superusuario, sem RLS): marcador de step-up, passo do fator e eventos.
 */
class IdentityStepUpIT extends IdentityTestBase {

    private static final String STEP_UP = "/api/v1/auth/step-up";
    private static final String FORBIDDEN = "{\"error\":\"forbidden\"}";

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
    }

    // ------------------------------------------------------------------ caminho feliz

    /**
     * O step-up eleva SO a sessao da requisicao: marcador nela, nao na outra sessao da mesma identidade; evento 13 na mesma
     * transacao; o passo avanca no fator. O mesmo codigo nao serve de novo: nem para outro step-up, nem para o login.
     */
    @Test
    void stepUpElevaSoASessaoDaRequisicaoComEventoEAntiReplay() throws Exception {
        ComFator f = comFator();
        String s1 = sessao(f.browser());
        BrowserClient outra = client();
        assertThat(login(outra, f.email(), SENHA).field("status")).isEqualTo("mfa_required");
        BrowserClient.Response v = verifyTotp(outra, f.identity());
        assertThat(v.status()).isEqualTo(200);
        outra.csrf = v.field("csrfToken");
        String s2 = sessao(outra);
        long passoAntes = passo(f.identity());

        String codigo = code(f.identity());
        BrowserClient.Response r = stepUp(f.browser(), codigo);
        assertThat(r.status()).isEqualTo(204);
        assertThat(r.body()).isEmpty();
        assertThat(value("SELECT mfa_step_up_at IS NOT NULL AND mfa_step_up_at <= clock_timestamp() FROM identity_sessions WHERE identity_session_id = '" + s1 + "'"))
                .as("step-up gravado na sessao da requisicao").isEqualTo("t");
        assertThat(value("SELECT mfa_step_up_at IS NULL FROM identity_sessions WHERE identity_session_id = '" + s2 + "'"))
                .as("a outra sessao da mesma identidade nao e elevada").isEqualTo("t");
        assertThat(value("SELECT public.identity_session_proof('" + s1 + "', clock_timestamp(), 'step_up')")).as("prova de step-up da 017").isEqualTo("t");
        assertThat(eventos(f.identity())).as("evento 13 na mesma transacao").isEqualTo(1);
        assertThat(value("SELECT target_organization_id IS NULL FROM identity_events WHERE identity_id = '" + f.identity() + "' AND event_type = 13"))
                .isEqualTo("t");
        long passoDepois = passo(f.identity());
        assertThat(passoDepois).as("o passo avancou no fator").isGreaterThan(passoAntes);

        BrowserClient.Response replay = stepUp(outra, codigo);
        assertThat(replay.status()).as("replay em outra sessao").isEqualTo(403);
        assertThat(replay.body()).isEqualTo(FORBIDDEN);
        assertThat(value("SELECT mfa_step_up_at IS NULL FROM identity_sessions WHERE identity_session_id = '" + s2 + "'")).isEqualTo("t");
        BrowserClient.Response replayMesma = stepUp(f.browser(), codigo);
        assertThat(replayMesma.status()).as("replay na mesma sessao").isEqualTo(403);
        assertThat(eventos(f.identity())).isEqualTo(1);
        assertThat(passo(f.identity())).isEqualTo(passoDepois);

        BrowserClient terceira = client();
        login(terceira, f.email(), SENHA);
        assertThat(terceira.post("/api/v1/auth/mfa/verify", "{\"code\":\"" + codigo + "\"}").status())
                .as("o codigo do step-up nao serve para o login (mesmo fator, mesmo anti-replay)").isEqualTo(401);
    }

    /**
     * O step-up habilita o que a 017 exige com step-up de ate 5 min: a funcao de escrita da criacao (P2) recusa antes e
     * aceita depois, com o mesmo token. (A rota de criacao ainda nao existe; a funcao e chamada pelo superusuario.)
     */
    @Test
    void stepUpHabilitaAFuncaoDeCriacaoQueAntesRecusava() throws Exception {
        ComFator f = comFator();
        String token = f.browser().cookies().get("__Host-nekkora_id");
        assertThat(value("SELECT identity_create_organization('" + token + "', 'Antes do step-up', NULL)")).as("sem step-up: recusa").isNull();
        assertThat(stepUp(f.browser(), code(f.identity())).status()).isEqualTo(204);
        assertThat(value("SELECT identity_create_organization('" + token + "', 'Depois do step-up', NULL)")).as("com step-up: cria").isNotNull();
    }

    // ------------------------------------------------------------------ recusas

    @Test
    void codigoErradoRecusaSemGravarNada() throws Exception {
        ComFator f = comFator();
        long p = passo(f.identity());
        BrowserClient.Response r = stepUp(f.browser(), codigoErrado(f.identity()));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body()).isEqualTo(FORBIDDEN);
        assertNadaGravado(f, p);
        assertThat(stepUp(f.browser(), "12345").status()).as("formato invalido: a mesma recusa").isEqualTo(403);
        assertNadaGravado(f, p);
    }

    @Test
    void corpoSemCodigoE400ECodigoDeRecuperacaoNaoEAlternativa() throws Exception {
        ComFator f = comFator();
        long p = passo(f.identity());
        assertThat(f.browser().post(STEP_UP, null).status()).isEqualTo(400);
        assertThat(f.browser().post(STEP_UP, "{}").status()).isEqualTo(400);
        assertThat(f.browser().post(STEP_UP, "{\"recoveryCode\":\"" + f.codes().get(0) + "\"}").status()).as("recoveryCode nao substitui o TOTP")
                .isEqualTo(400);
        assertNadaGravado(f, p);
        assertThat(value("SELECT count(*) FROM identity_mfa_recovery_codes WHERE identity_id = '" + f.identity() + "' AND used_at IS NOT NULL"))
                .as("nenhum codigo de recuperacao consumido").isEqualTo("0");
    }

    /** Sessao restrita a recuperacao (DC-17.13): 403 sem conferir o codigo; nada gravado, passo intocado. */
    @Test
    void sessaoRestritaRecebe403SemConferirOCodigo() throws Exception {
        ComFator f = comFator();
        BrowserClient b = client();
        login(b, f.email(), SENHA);
        BrowserClient.Response v = b.post("/api/v1/auth/mfa/verify", "{\"recoveryCode\":\"" + f.codes().get(0) + "\"}");
        assertThat(v.status()).isEqualTo(200);
        b.csrf = v.field("csrfToken");
        String restrita = sessao(b);
        assertThat(value("SELECT recovery_used_at IS NOT NULL FROM identity_sessions WHERE identity_session_id = '" + restrita + "'")).isEqualTo("t");
        long p = passo(f.identity());
        BrowserClient.Response r = stepUp(b, code(f.identity()));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body()).isEqualTo(FORBIDDEN);
        assertThat(value("SELECT mfa_step_up_at IS NULL FROM identity_sessions WHERE identity_session_id = '" + restrita + "'")).isEqualTo("t");
        assertThat(passo(f.identity())).as("o codigo nem foi conferido: passo intocado").isEqualTo(p);
        assertThat(eventos(f.identity())).isZero();
    }

    @Test
    void semSessaoOuComSessaoEncerradaE401() throws Exception {
        BrowserClient sem = client();
        assertThat(sem.post(STEP_UP, "{\"code\":\"123456\"}").status()).as("sem cookie de identidade").isEqualTo(401);

        ComFator f = comFator();
        long p = passo(f.identity());
        String token = f.browser().cookies().get("__Host-nekkora_id");
        String csrf = f.browser().csrf;
        assertThat(f.browser().post("/api/v1/auth/logout", null).status()).isEqualTo(204);
        f.browser().cookies().put("__Host-nekkora_id", token);
        f.browser().csrf = csrf;
        assertThat(stepUp(f.browser(), code(f.identity())).status()).as("sessao encerrada").isEqualTo(401);
        assertThat(passo(f.identity())).isEqualTo(p);
        assertThat(eventos(f.identity())).isZero();
    }

    /** CSRF da sessao de identidade e Origin exigidos; qualquer falha: 403 generico, nada gravado. */
    @Test
    void csrfDaSessaoDeIdentidadeEOrigemSaoExigidos() throws Exception {
        ComFator f = comFator();
        long p = passo(f.identity());
        String csrf = f.browser().csrf;
        f.browser().csrf = null;
        assertThat(stepUp(f.browser(), code(f.identity())).status()).as("sem X-CSRF-Token").isEqualTo(403);
        f.browser().csrf = Tokens.newToken();
        assertThat(stepUp(f.browser(), code(f.identity())).status()).as("CSRF de outro contexto").isEqualTo(403);
        f.browser().csrf = csrf;
        String origem = f.browser().origin;
        f.browser().origin = "https://outra.exemplo.test";
        try {
            assertThat(stepUp(f.browser(), code(f.identity())).status()).as("Origin diferente").isEqualTo(403);
        } finally {
            f.browser().origin = origem;
        }
        assertNadaGravado(f, p);
        assertThat(stepUp(f.browser(), code(f.identity())).status()).as("controle: com CSRF e Origin corretos").isEqualTo(204);
    }

    /**
     * R8m pela identidade GLOBAL: codigos errados do step-up, cada um de um IP diferente (fora de R8 por identidade+IP),
     * contam no mesmo contador do MFA da entrada geral. Passado o limiar, o atraso vale para o step-up e para a
     * verificacao do login da mesma identidade, de qualquer IP.
     */
    @Test
    void codigoErradoContaEmR8mPelaIdentidadeGlobal() throws Exception {
        ComFator f = comFator();
        String ipOriginal = f.browser().forwardedIp;
        // desafio de login aberto ANTES das falhas (o passo da senha nao e afetado por R8m), para a conferencia final ser imediata
        BrowserClient c = client();
        c.forwardedIp = "198.51.100.201";
        assertThat(login(c, f.email(), SENHA).status()).isEqualTo(200);
        for (int i = 1; i <= 12; i++) {
            if (i == 12) {
                pause(1100); // o atraso de 1 s aberto pela 11a falha passa; a 12a abre o de 2 s
            }
            f.browser().forwardedIp = "198.51.100." + i;
            assertThat(stepUp(f.browser(), codigoErrado(f.identity())).status()).as("falha " + i).isEqualTo(403);
        }
        f.browser().forwardedIp = "198.51.100.200";
        BrowserClient.Response limitado = stepUp(f.browser(), code(f.identity()));
        assertThat(limitado.status()).as("step-up de outro IP: R8m").isEqualTo(429);
        assertThat(c.post("/api/v1/auth/mfa/verify", "{\"code\":\"" + code(f.identity()) + "\"}").status())
                .as("verificacao do login da mesma identidade, de outro IP: R8m").isEqualTo(429);
        f.browser().forwardedIp = ipOriginal;
        assertThat(eventos(f.identity())).isZero();
    }

    // ------------------------------------------------------------------ concorrencia e ordem de locks

    /** Logout DURANTE a espera pela identidade: a sessao e reconferida sob o lock. 401, nada gravado. */
    @Test
    void logoutDuranteAEsperaPelaIdentidadeERecusado() throws Exception {
        ComFator f = comFator();
        String s = sessao(f.browser());
        long p = passo(f.identity());
        String codigo = code(f.identity());
        BrowserClient.Response r = comLinhaTravada("SELECT 1 FROM identities WHERE identity_id = '" + f.identity() + "' FOR UPDATE",
                () -> stepUp(f.browser(), codigo),
                c -> sql(c, "UPDATE identity_sessions SET revoked_at = clock_timestamp() WHERE identity_session_id = '" + s + "'"));
        assertThat(r.status()).as("sessao revogada na espera").isEqualTo(401);
        assertThat(value("SELECT mfa_step_up_at IS NULL FROM identity_sessions WHERE identity_session_id = '" + s + "'")).isEqualTo("t");
        assertThat(passo(f.identity())).as("passo intocado").isEqualTo(p);
        assertThat(eventos(f.identity())).isZero();
    }

    /** Suspensao da identidade DURANTE a espera: reconferida sob o lock. 401, nada gravado. */
    @Test
    void suspensaoDuranteAEsperaPelaIdentidadeERecusada() throws Exception {
        ComFator f = comFator();
        String s = sessao(f.browser());
        long p = passo(f.identity());
        String codigo = code(f.identity());
        BrowserClient.Response r = comLinhaTravada("SELECT 1 FROM identities WHERE identity_id = '" + f.identity() + "' FOR UPDATE",
                () -> stepUp(f.browser(), codigo),
                c -> sql(c, "UPDATE identities SET status = 3 WHERE identity_id = '" + f.identity() + "'"));
        assertThat(r.status()).as("identidade suspensa na espera").isEqualTo(401);
        assertThat(value("SELECT mfa_step_up_at IS NULL FROM identity_sessions WHERE identity_session_id = '" + s + "'")).isEqualTo("t");
        assertThat(passo(f.identity())).isEqualTo(p);
        assertThat(eventos(f.identity())).isZero();
    }

    /**
     * Ordem global (identidade -> sessao de identidade): com a SESSAO travada por outra transacao, o step-up espera
     * segurando a IDENTIDADE (um FOR UPDATE NOWAIT nela falha). E o logout real, que trava identidade -> sessao, entra na
     * fila atras do step-up em vez de formar ciclo: liberada a sessao, o step-up conclui e depois o logout revoga.
     */
    @Test
    void stepUpTravaAIdentidadeAntesDaSessaoESemCicloComOLogout() throws Exception {
        ComFator f = comFator();
        String s = sessao(f.browser());
        String codigo = code(f.identity());
        String csrf = f.browser().csrf;
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try (Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            sql(c, "SELECT 1 FROM identity_sessions WHERE identity_session_id = '" + s + "' FOR UPDATE");
            Future<BrowserClient.Response> up = ex.submit(() -> stepUp(f.browser(), codigo));
            assertThat(platformWaitingOnLock()).as("o step-up espera a sessao").isTrue();
            try (Connection o = DB.connectAsSuperuser()) {
                o.setAutoCommit(false);
                assertThatThrownBy(() -> sql(o, "SELECT 1 FROM identities WHERE identity_id = '" + f.identity() + "' FOR UPDATE NOWAIT"))
                        .as("a identidade ja esta travada pelo step-up").isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("55P03"));
                o.rollback();
            }
            BrowserClient sair = browser();
            sair.forwardedIp = f.browser().forwardedIp;
            sair.cookies().putAll(f.browser().cookies());
            sair.csrf = csrf;
            Future<BrowserClient.Response> logout = ex.submit(() -> sair.post("/api/v1/auth/logout", null));
            assertThat(platformWaitingOnLock(2)).as("o logout espera atras do step-up").isTrue();
            c.commit();
            assertThat(up.get().status()).as("step-up conclui, sem impasse").isEqualTo(204);
            assertThat(logout.get().status()).as("logout conclui depois").isEqualTo(204);
        } finally {
            ex.shutdownNow();
        }
        assertThat(value("SELECT (mfa_step_up_at IS NOT NULL) || '|' || (revoked_at IS NOT NULL) || '|' || (revoked_at >= mfa_step_up_at) FROM identity_sessions WHERE identity_session_id = '"
                + s + "'")).as("step-up gravado e depois a revogacao").isEqualTo("true|true|true");
        assertThat(eventos(f.identity())).isEqualTo(1);
    }

    /**
     * Espera DEPOIS da validacao do TOTP: outra transacao trava so a linha do fator (sem alterar). O step-up valida o
     * passo e espera no anti-replay; o passo vence durante a espera. A janela e reconferida antes do commit: rollback
     * integral - sem marcador, sem passo, sem evento. 403.
     */
    @Test
    void passoVencidoDepoisDaValidacaoDesfazTudo() throws Exception {
        ComFator f = comFator();
        String s = sessao(f.browser());
        long ultimo = passo(f.identity());
        while (Totp.step(java.time.Instant.now()) - 1 <= ultimo) {
            pause(500);
        }
        long passo = Totp.step(java.time.Instant.now()) - 1;
        String codigo = Totp.code(SECRETS.get(f.identity()), passo);
        String fator = value("SELECT factor_id FROM identity_mfa_factors WHERE identity_id = '" + f.identity() + "' AND confirmed_at IS NOT NULL AND disabled_at IS NULL");
        BrowserClient.Response r = comLinhaTravada("SELECT 1 FROM identity_mfa_factors WHERE factor_id = '" + fator + "' FOR UPDATE",
                () -> postLongo(f.browser(), STEP_UP, "{\"code\":\"" + codigo + "\"}"), c -> {
                    while (Totp.step(java.time.Instant.now()) < passo + 2) {
                        pause(500);
                    }
                });
        assertThat(r.status()).as("passo fora da janela no fim").isEqualTo(403);
        assertThat(value("SELECT mfa_step_up_at IS NULL FROM identity_sessions WHERE identity_session_id = '" + s + "'")).isEqualTo("t");
        assertThat(passo(f.identity())).as("o avanco do passo foi desfeito").isEqualTo(ultimo);
        assertThat(eventos(f.identity())).isZero();
    }

    /**
     * Sessao vence ENQUANTO o step-up espera o lock da propria sessao (so essa linha travada, sem alteracao; TOTP ainda
     * valido). O prazo e medido num comando posterior a aquisicao do lock: 401, nada gravado. Controle: a mesma espera,
     * liberada antes do vencimento, conclui com 204.
     */
    @Test
    void sessaoVencidaDuranteAEsperaPelaSessaoE401SemEfeito() throws Exception {
        ComFator f = comFator();
        String s = sessao(f.browser());
        long p = passo(f.identity());
        BrowserClient.Response r = esperaComVencimento(s, "SELECT 1 FROM identity_sessions WHERE identity_session_id = '" + s + "' FOR UPDATE",
                f.browser(), codigoDuravel(f.identity()), true);
        assertThat(r.status()).as("sessao vencida durante a espera pela sessao").isEqualTo(401);
        assertThat(value("SELECT expires_at <= clock_timestamp() FROM identity_sessions WHERE identity_session_id = '" + s + "'")).isEqualTo("t");
        assertThat(value("SELECT mfa_step_up_at IS NULL FROM identity_sessions WHERE identity_session_id = '" + s + "'")).as("sem marcador").isEqualTo("t");
        assertThat(passo(f.identity())).as("passo intocado").isEqualTo(p);
        assertThat(eventos(f.identity())).as("sem evento").isZero();

        ComFator g = comFator();
        String sg = sessao(g.browser());
        BrowserClient.Response controle = esperaComVencimento(sg, "SELECT 1 FROM identity_sessions WHERE identity_session_id = '" + sg + "' FOR UPDATE",
                g.browser(), codigoDuravel(g.identity()), false);
        assertThat(controle.status()).as("controle: liberada antes do vencimento").isEqualTo(204);
        assertThat(eventos(g.identity())).isEqualTo(1);
    }

    /**
     * Sessao vence DEPOIS da validacao do TOTP, enquanto o anti-replay espera o lock do fator (so essa linha travada, sem
     * alteracao; o TOTP continua na janela). A sessao e reconferida pelo relogio do banco antes do commit: 401 e rollback
     * integral (passo, marcador, evento). Controle: a mesma espera, liberada antes do vencimento, conclui com 204.
     */
    @Test
    void sessaoVencidaDuranteOAntiReplayDesfazTudoCom401() throws Exception {
        ComFator f = comFator();
        String s = sessao(f.browser());
        long p = passo(f.identity());
        BrowserClient.Response r = esperaComVencimento(s, travaFator(f.identity()), f.browser(), codigoDuravel(f.identity()), true);
        assertThat(r.status()).as("sessao vencida durante o anti-replay").isEqualTo(401);
        assertThat(value("SELECT mfa_step_up_at IS NULL FROM identity_sessions WHERE identity_session_id = '" + s + "'")).as("marcador desfeito").isEqualTo("t");
        assertThat(passo(f.identity())).as("avanco do passo desfeito").isEqualTo(p);
        assertThat(eventos(f.identity())).as("evento desfeito").isZero();

        ComFator g = comFator();
        String sg = sessao(g.browser());
        BrowserClient.Response controle = esperaComVencimento(sg, travaFator(g.identity()), g.browser(), codigoDuravel(g.identity()), false);
        assertThat(controle.status()).as("controle: liberada antes do vencimento").isEqualTo(204);
        assertThat(value("SELECT mfa_step_up_at IS NOT NULL FROM identity_sessions WHERE identity_session_id = '" + sg + "'")).isEqualTo("t");
        assertThat(eventos(g.identity())).isEqualTo(1);
    }

    /** Falha ao gravar o evento 13: o step-up inteiro e desfeito (mesma transacao). */
    @Test
    void falhaAoGravarOEventoDesfazOStepUp() throws Exception {
        ComFator f = comFator();
        String s = sessao(f.browser());
        long p = passo(f.identity());
        String gatilho = "teste_falha_step_up_" + f.identity().toString().replace("-", "");
        exec("CREATE FUNCTION public." + gatilho + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'falha injetada'; END $$");
        exec("CREATE TRIGGER " + gatilho + " BEFORE INSERT ON identity_events FOR EACH ROW WHEN (NEW.identity_id = '" + f.identity()
                + "' AND NEW.event_type = 13) EXECUTE FUNCTION public." + gatilho + "()");
        BrowserClient.Response r;
        try {
            r = stepUp(f.browser(), code(f.identity()));
        } finally {
            exec("DROP TRIGGER " + gatilho + " ON identity_events");
            exec("DROP FUNCTION public." + gatilho + "()");
        }
        assertThat(r.status()).as("o step-up falha").isEqualTo(500);
        assertThat(value("SELECT mfa_step_up_at IS NULL FROM identity_sessions WHERE identity_session_id = '" + s + "'")).as("sem marcador").isEqualTo("t");
        assertThat(passo(f.identity())).as("sem avanco do passo").isEqualTo(p);
    }

    // ------------------------------------------------------------------ apoio

    private static final int PRAZO_CURTO_S = 5;

    /**
     * Encurta o prazo da sessao para {@link #PRAZO_CURTO_S} s (a 017 so permite encurtar), trava a linha de
     * {@code travaSql} sem altera-la, dispara o step-up e espera a Platform esperar o lock. Com {@code vencer}, so libera
     * depois que o relogio do banco passou do prazo; sem, libera logo (controle).
     */
    private BrowserClient.Response esperaComVencimento(String sessao, String travaSql, BrowserClient b, String codigo, boolean vencer) throws Exception {
        exec("UPDATE identity_sessions SET expires_at = clock_timestamp() + interval '" + PRAZO_CURTO_S + " seconds' WHERE identity_session_id = '" + sessao + "'");
        BrowserClient.Response r = comLinhaTravada(travaSql, () -> postLongo(b, STEP_UP, "{\"code\":\"" + codigo + "\"}"), c -> {
            if (vencer) {
                while (!"t".equals(value("SELECT expires_at < clock_timestamp() - interval '300 milliseconds' FROM identity_sessions WHERE identity_session_id = '" + sessao + "'"))) {
                    pause(200);
                }
            } else {
                assertThat(value("SELECT expires_at > clock_timestamp() + interval '2 seconds' FROM identity_sessions WHERE identity_session_id = '" + sessao + "'"))
                        .as("controle liberado antes do vencimento").isEqualTo("t");
            }
        });
        if (vencer) {
            assertThat(Totp.verify(SECRETS.get(identityDaSessao(sessao)), codigo, java.time.Instant.now())).as("o TOTP continua na janela").isPresent();
        }
        return r;
    }

    private static String travaFator(UUID identity) throws Exception {
        return "SELECT 1 FROM identity_mfa_factors WHERE factor_id = '" + value("SELECT factor_id FROM identity_mfa_factors WHERE identity_id = '" + identity
                + "' AND confirmed_at IS NOT NULL AND disabled_at IS NULL") + "' FOR UPDATE";
    }

    private static UUID identityDaSessao(String sessao) throws Exception {
        return UUID.fromString(value("SELECT identity_id FROM identity_sessions WHERE identity_session_id = '" + sessao + "'"));
    }

    /** Codigo do passo ATUAL, posterior ao ultimo usado: fica na janela (+-1) por pelo menos 30 s. */
    private static String codigoDuravel(UUID identity) throws Exception {
        long ultimo = passo(identity);
        while (Totp.step(java.time.Instant.now()) <= ultimo) {
            pause(500);
        }
        return Totp.code(SECRETS.get(identity), Totp.step(java.time.Instant.now()));
    }

    private static BrowserClient.Response stepUp(BrowserClient b, String codigo) {
        return b.post(STEP_UP, "{\"code\":\"" + codigo + "\"}");
    }

    private static String sessao(BrowserClient b) throws Exception {
        return value("SELECT identity_session_id FROM identity_sessions WHERE session_token_hash = '" + Tokens.sha256Hex(b.cookies().get("__Host-nekkora_id")) + "'");
    }

    private static long passo(UUID identity) throws Exception {
        return Long.parseLong(value("SELECT last_used_step FROM identity_mfa_factors WHERE identity_id = '" + identity
                + "' AND confirmed_at IS NOT NULL AND disabled_at IS NULL"));
    }

    private static long eventos(UUID identity) throws Exception {
        return count("SELECT count(*) FROM identity_events WHERE identity_id = '" + identity + "' AND event_type = 13");
    }

    /** Codigo de 6 digitos diferente de todos os aceitos agora (agora-1 .. agora+1). */
    private static String codigoErrado(UUID identity) {
        long agora = Totp.step(java.time.Instant.now());
        java.util.Set<String> aceitos = new java.util.HashSet<>();
        for (long p = agora - 2; p <= agora + 2; p++) {
            aceitos.add(Totp.code(SECRETS.get(identity), p));
        }
        for (int n = 0; ; n++) {
            String c = String.format("%06d", (n * 7919) % 1_000_000);
            if (!aceitos.contains(c)) {
                return c;
            }
        }
    }

    private static void assertNadaGravado(ComFator f, long passo) throws Exception {
        assertThat(count("SELECT count(*) FROM identity_sessions WHERE identity_id = '" + f.identity() + "' AND mfa_step_up_at IS NOT NULL"))
                .as("nenhum step-up gravado").isZero();
        assertThat(passo(f.identity())).as("passo intocado").isEqualTo(passo);
        assertThat(eventos(f.identity())).as("nenhum evento 13").isZero();
    }

    @FunctionalInterface
    private interface SqlDurante {
        void run(Connection c) throws Exception;
    }

    private static void sql(Connection c, String s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(s)) {
            ps.execute();
        }
    }

    /** Trava a linha de {@code travaSql} (FOR UPDATE), dispara a acao, espera a Platform esperar o lock, roda {@code durante} e confirma. */
    private BrowserClient.Response comLinhaTravada(String travaSql, Callable<BrowserClient.Response> acao, SqlDurante durante) throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(travaSql)) {
                assertThat(ps.executeQuery().next()).as("linha travada").isTrue();
            }
            Future<BrowserClient.Response> f = ex.submit(acao);
            assertThat(platformWaitingOnLock()).as("a requisicao espera o lock").isTrue();
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
}
