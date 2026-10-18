package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;
import com.nekkora.platform.auth.crypto.Base32;
import com.nekkora.platform.mail.MailSender;

/**
 * Vinculacao de conta antiga (contrato P3), pela API, com as chaves da entrada geral e da vinculacao ligadas. Papel real
 * nekkora_platform, 017 R5, Redis real. Cada recusa confere a resposta E o estado persistido (superusuario, sem RLS):
 * desafios de vinculacao, vinculo, fechamento da DC-17.12, passo e codigos do fator local, eventos e avisos.
 *
 * <p>Cenario de cada teste: conta antiga interna com fator local (login por organizacao completo) e identidade com o
 * MESMO e-mail, com sessao e fator proprios pela entrada geral.
 */
// contexto proprio (propriedades da classe): fechado ao fim dela, para nao manter um pool a mais aberto contra o limite
// de conexoes do PostgreSQL de teste, compartilhado pela suite inteira
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class AccountLinkIT extends IdentityTestBase {

    private static final String START = "/api/v1/links/start";
    private static final String CONFIRM = "/api/v1/links/confirm";
    private static final String FORBIDDEN = "{\"error\":\"forbidden\"}";
    private static final String LINK = "__Host-nekkora_link";

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.vinculacao.enabled", () -> "true");
    }

    /** Conta antiga (com a sessao do login local e os codigos locais) e a identidade do mesmo e-mail (com sessao). */
    record Cenario(Org o, TestUser conta, BrowserClient legada, List<String> codigos, UUID identity, BrowserClient id) {
        UUID org() {
            return o.id();
        }
    }

    private Cenario cenario() throws Exception {
        Org o = newOrganization();
        TestUser u = internalUser(o, true, 2);
        BrowserClient legada = browser();
        List<String> codigos = firstLogin(legada, o.id(), u);
        UUID identity = identidadeAtiva(u.email(), SENHA);
        BrowserClient id = sessaoPelaEntradaGeral(u.email(), identity);
        return new Cenario(o, u, legada, codigos, identity, id);
    }

    // ------------------------------------------------------------------ jornadas

    /**
     * Jornada completa com o TOTP da conta antiga: etapa 1 (204 + cookie do desafio), step-up da identidade, etapa 2 (204).
     * O fechamento da DC-17.12 acontece no mesmo ato: sessao, desafio, token, fator e codigos locais nao sobrevivem. Depois,
     * a porta local recusa, a troca da entrada geral emite a sessao de organizacao e o step-up por organizacao (P4) usa o
     * fator da identidade.
     */
    @Test
    void jornadaCompletaComTotpFechaAPortaLocalEAbreAEntradaGeral() throws Exception {
        Cenario c = cenario();
        BrowserClient pendente = browser();
        assertThat(passwordLogin(pendente, c.org(), c.conta()).field("status")).as("desafio local pendente").isEqualTo("mfa_required");
        assertThat(client().post("/api/v1/o/" + c.org() + "/auth/password-reset/request", "{\"email\":" + q(c.conta().email()) + "}").status()).isEqualTo(202);
        assertThat(vivos(c.conta().id())).as("antes: sessao, desafio, token, fator e codigos vivos").isEqualTo("1|1|1|1|10");

        BrowserClient.Response s = start(c.id(), c.org(), PASSWORD, code(c.conta().id()), null);
        assertThat(s.status()).isEqualTo(204);
        assertThat(s.body()).isEmpty();
        String cookie = cookie(s, LINK);
        assertThat(cookie).as("cookie do desafio").contains("Path=/").contains("Secure").contains("HttpOnly").contains("SameSite=Strict").contains("Max-Age=300");
        assertThat(value("SELECT count(*) || '|' || bool_and(account_credential_hash = (SELECT password_hash FROM user_credentials WHERE user_id = '" + c.conta().id()
                + "')) || '|' || bool_and(expires_at = created_at + interval '5 minutes') FROM identity_link_challenges WHERE identity_id = '" + c.identity()
                + "' AND user_id = '" + c.conta().id() + "' AND consumed_at IS NULL AND revoked_at IS NULL"))
                .as("desafio vivo, amarrado ao par, com a credencial conferida e 5 min").isEqualTo("1|true|true");
        assertThat(count("SELECT count(*) FROM identity_users WHERE user_id = '" + c.conta().id() + "'")).as("a etapa 1 nao vincula").isZero();

        assertThat(stepUpIdentidade(c)).isEqualTo(204);
        BrowserClient.Response r = confirm(c.id(), c.org());
        assertThat(r.status()).isEqualTo(204);
        assertThat(cookie(r, LINK)).as("o cookie do desafio expira").contains("Max-Age=0");
        assertThat(value("SELECT identity_id || '|' || link_channel FROM identity_users WHERE user_id = '" + c.conta().id() + "'"))
                .isEqualTo(c.identity() + "|vinculacao");
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + c.identity() + "' AND event_type = 9 AND target_organization_id = '" + c.org() + "'"))
                .isEqualTo(1);
        assertThat(vivos(c.conta().id())).as("fechamento da DC-17.12 no mesmo ato").isEqualTo("0|0|0|0|0");
        assertThat(count("SELECT count(*) FROM identity_link_challenges WHERE identity_id = '" + c.identity() + "' AND consumed_at IS NOT NULL")).isEqualTo(1);
        assertThat(mails(c.conta().email()).stream().filter(m -> m.kind() == MailSender.Kind.NOTICE_IDENTITY_LINKED).count()).as("aviso").isEqualTo(1);

        assertThat(c.legada().get("/api/v1/o/" + c.org() + "/me").status()).as("sessao antiga revogada").isEqualTo(401);
        BrowserClient.Response login = passwordLogin(browser(), c.org(), c.conta());
        assertThat(login.status()).as("porta local fechada").isEqualTo(401);
        assertThat(login.body()).isEqualTo(UNAUTH);
        assertThat(pendente.post("/api/v1/o/" + c.org() + "/auth/mfa/verify", "{\"code\":\"" + code(c.conta().id()) + "\"}").status())
                .as("o desafio local pendente foi invalidado").isEqualTo(401);

        BrowserClient org = trocar(c, c.org());
        assertThat(org.get("/api/v1/o/" + c.org() + "/me").status()).as("a entrada geral serve a conta").isEqualTo(200);
        assertThat(org.post("/api/v1/o/" + c.org() + "/auth/mfa/step-up", "{\"code\":\"" + code(c.identity()) + "\"}").status())
                .as("step-up por organizacao (P4) com o fator da identidade").isEqualTo(204);
        assertThat(org.post("/api/v1/o/" + c.org() + "/auth/password", "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"outra-senha-bem-longa-2\"}").status())
                .as("a senha local nao muda mais").isEqualTo(403);
        assertThat(count("SELECT count(*) FROM identity_mfa_factors WHERE identity_id = '" + c.identity() + "' AND disabled_at IS NULL")).as("fator da identidade intacto")
                .isEqualTo(1);
    }

    /** Etapa 1 com codigo de recuperacao da conta antiga: consumido ja na etapa 1, evento 2 da organizacao e aviso; reuso: 401. */
    @Test
    void jornadaComCodigoDeRecuperacaoConsomeOCodigoNaEtapa1() throws Exception {
        Cenario c = cenario();
        String codigo = c.codigos().get(0);
        assertThat(start(c.id(), c.org(), PASSWORD, null, codigo).status()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM user_mfa_recovery_codes WHERE user_id = '" + c.conta().id() + "' AND used_at IS NOT NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM mfa_events WHERE user_id = '" + c.conta().id() + "' AND event_type = 2")).isEqualTo(1);
        assertThat(mails(c.conta().email()).stream().filter(m -> m.kind() == MailSender.Kind.NOTICE_RECOVERY_CODE_USED).count()).isEqualTo(1);
        String primeiro = c.id().cookies().get(LINK);

        BrowserClient.Response reuso = start(c.id(), c.org(), PASSWORD, null, codigo);
        assertThat(reuso.status()).as("codigo ja usado").isEqualTo(401);
        assertThat(reuso.body()).isEqualTo(UNAUTH);
        assertThat(c.id().cookies().get(LINK)).as("o desafio anterior continua").isEqualTo(primeiro);
        assertThat(stepUpIdentidade(c)).isEqualTo(204);
        assertThat(confirm(c.id(), c.org()).status()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM identity_users WHERE user_id = '" + c.conta().id() + "'")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ recusas da etapa 1

    /**
     * Qualquer falha da prova: o MESMO 401, sem desafio e sem consumo. Senha errada (TOTP certo, passo intocado), TOTP errado,
     * identidade sem conta naquela organizacao (com a senha e o TOTP certos da conta de OUTRA pessoa), conta suspensa, conta
     * nao verificada, conta ja vinculada e organizacao suspensa.
     */
    @Test
    void falhasDaProvaRespondemOMesmo401SemEfeito() throws Exception {
        Cenario senha = cenario();
        long passo = passoLocal(senha.conta().id());
        assertRecusa(senha, start(senha.id(), senha.org(), "senha-errada-bem-longa-1", code(senha.conta().id()), null));
        assertThat(passoLocal(senha.conta().id())).as("senha errada: o TOTP nem foi consumido").isEqualTo(passo);

        Cenario totp = cenario();
        assertRecusa(totp, start(totp.id(), totp.org(), PASSWORD, "000000".equals(code(totp.conta().id())) ? "111111" : "000000", null));

        Cenario alheia = cenario();
        ComFator outra = comFator();
        BrowserClient.Response r = start(outra.browser(), alheia.org(), PASSWORD, code(alheia.conta().id()), null);
        assertThat(r.status()).as("conta de outra pessoa, com a senha e o TOTP dela").isEqualTo(401);
        assertThat(r.body()).isEqualTo(UNAUTH);
        assertThat(count("SELECT count(*) FROM identity_link_challenges WHERE identity_id = '" + outra.identity() + "'")).isZero();

        Cenario suspensa = cenario();
        exec("UPDATE users SET status = 3 WHERE user_id = '" + suspensa.conta().id() + "'");
        assertRecusa(suspensa, start(suspensa.id(), suspensa.org(), PASSWORD, code(suspensa.conta().id()), null));

        Cenario orgSuspensa = cenario();
        exec("UPDATE organizations SET status = 2 WHERE organization_id = '" + orgSuspensa.org() + "'");
        assertRecusa(orgSuspensa, start(orgSuspensa.id(), orgSuspensa.org(), PASSWORD, code(orgSuspensa.conta().id()), null));

        ComFator criadora = comFator();
        UUID propria = LinkedAccountLocalEntryIT.organizacaoDa(criadora.browser(), "Ja vinculada");
        BrowserClient.Response jaVinculada = start(criadora.browser(), propria, SENHA, code(criadora.identity()), null);
        assertThat(jaVinculada.status()).as("conta ja vinculada (cadastro)").isEqualTo(401);
        assertThat(jaVinculada.body()).isEqualTo(UNAUTH);
    }

    @Test
    void corpoInvalidoE400SessaoAusenteE401ERestritaE403() throws Exception {
        Cenario c = cenario();
        assertThat(c.id().post(START, "{\"organizationId\":\"" + c.org() + "\",\"password\":\"" + PASSWORD + "\"}").status()).as("sem codigo").isEqualTo(400);
        assertThat(c.id().post(START, "{\"organizationId\":\"" + c.org() + "\",\"password\":\"" + PASSWORD + "\",\"code\":\"123456\",\"recoveryCode\":\"" + c.codigos().get(0)
                + "\"}").status()).as("os dois codigos").isEqualTo(400);
        assertThat(c.id().post(START, "{\"organizationId\":\"nao-e-uuid\",\"password\":\"" + PASSWORD + "\",\"code\":\"123456\"}").status()).isEqualTo(400);
        assertThat(c.id().post(START, "{\"organizationId\":\"" + c.org() + "\",\"code\":\"123456\"}").status()).as("sem senha").isEqualTo(400);
        assertThat(c.id().post(CONFIRM, "{}").status()).isEqualTo(400);

        BrowserClient sem = client();
        assertThat(sem.post(START, corpo(c.org(), PASSWORD, "123456", null)).status()).isEqualTo(401);
        assertThat(sem.post(CONFIRM, "{\"organizationId\":\"" + c.org() + "\"}").status()).isEqualTo(401);

        // sessao restrita (DC-17.13): nasce de senha + codigo de recuperacao DA IDENTIDADE (os de comFator)
        ComFator f = comFator();
        BrowserClient r = client();
        login(r, f.email(), SENHA);
        BrowserClient.Response v = r.post("/api/v1/auth/mfa/verify", "{\"recoveryCode\":\"" + f.codes().get(0) + "\"}");
        assertThat(v.status()).isEqualTo(200);
        r.csrf = v.field("csrfToken");
        assertThat(start(r, c.org(), PASSWORD, code(c.conta().id()), null).status()).as("sessao restrita").isEqualTo(403);
        assertThat(r.post(CONFIRM, "{\"organizationId\":\"" + c.org() + "\"}").status()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM identity_link_challenges WHERE identity_id IN ('" + c.identity() + "', '" + f.identity() + "')")).isZero();
    }

    /** R13: 5 falhas da mesma identidade abrem o limite; a sexta tentativa, mesmo correta, recebe 429 sem desafio. */
    @Test
    void r13LimitaAsFalhasPorIdentidadeEContaAlvo() throws Exception {
        Cenario c = cenario();
        for (int i = 1; i <= 5; i++) {
            c.id().forwardedIp = "203.0.113." + i;
            assertThat(start(c.id(), c.org(), "senha-errada-numero-" + i, "123456", null).status()).as("falha " + i).isEqualTo(401);
        }
        c.id().forwardedIp = "203.0.113.50";
        long passo = passoLocal(c.conta().id());
        BrowserClient.Response r = start(c.id(), c.org(), PASSWORD, code(c.conta().id()), null);
        assertThat(r.status()).isEqualTo(429);
        assertThat(r.headers().firstValue("Retry-After")).isPresent();
        assertThat(count("SELECT count(*) FROM identity_link_challenges WHERE identity_id = '" + c.identity() + "'")).isZero();
        assertThat(passoLocal(c.conta().id())).as("nada conferido").isEqualTo(passo);
    }

    /** Anti-replay do fator local: o codigo usado no login local nao serve na etapa 1, e o da etapa 1 nao serve no login local. */
    @Test
    void codigoLocalUsadoNumLadoNaoServeNoOutro() throws Exception {
        Cenario c = cenario();
        BrowserClient l = browser();
        passwordLogin(l, c.org(), c.conta());
        String doLogin = code(c.conta().id());
        assertThat(l.post("/api/v1/o/" + c.org() + "/auth/mfa/verify", "{\"code\":\"" + doLogin + "\"}").status()).isEqualTo(200);
        assertThat(start(c.id(), c.org(), PASSWORD, doLogin, null).status()).as("codigo do login, na etapa 1").isEqualTo(401);

        BrowserClient l2 = browser();
        passwordLogin(l2, c.org(), c.conta());
        String daEtapa = code(c.conta().id());
        assertThat(start(c.id(), c.org(), PASSWORD, daEtapa, null).status()).isEqualTo(204);
        assertThat(l2.post("/api/v1/o/" + c.org() + "/auth/mfa/verify", "{\"code\":\"" + daEtapa + "\"}").status()).as("codigo da etapa 1, no login").isEqualTo(401);
    }

    /**
     * Troca da senha local DURANTE a espera da etapa 1 pela conta (depois do Argon2, antes do lock): a credencial e
     * reconferida sob o lock e tem de ser a mesma que o Argon2 conferiu. 401, sem desafio, sem consumo do TOTP.
     */
    @Test
    void senhaTrocadaDuranteAEsperaDaEtapa1InvalidaAProva() throws Exception {
        Cenario c = cenario();
        long passo = passoLocal(c.conta().id());
        String codigo = code(c.conta().id());
        String outroHash = HASHER.generate("senha-trocada-na-espera-7".toCharArray());
        BrowserClient.Response r = Barreiras.comLinhaTravada(DB, "SELECT 1 FROM users WHERE user_id = '" + c.conta().id() + "' FOR UPDATE",
                () -> start(c.id(), c.org(), PASSWORD, codigo, null), l -> {
                    Barreiras.sql(l, "SELECT set_config('nekkora.organization_id', '" + c.org() + "', true)");
                    Barreiras.sql(l, "UPDATE user_credentials SET password_hash = '" + outroHash + "', password_changed_at = clock_timestamp() WHERE user_id = '"
                            + c.conta().id() + "'");
                });
        assertRecusa(c, r);
        assertThat(passoLocal(c.conta().id())).as("o TOTP nao foi consumido").isEqualTo(passo);
    }

    /** Fator local desabilitado (reset) DURANTE a espera da etapa 1 pela conta: lido sob o lock. 401, sem desafio. */
    @Test
    void fatorLocalDesabilitadoDuranteAEsperaDaEtapa1InvalidaAProva() throws Exception {
        Cenario c = cenario();
        String codigo = code(c.conta().id());
        BrowserClient.Response r = Barreiras.comLinhaTravada(DB, "SELECT 1 FROM users WHERE user_id = '" + c.conta().id() + "' FOR UPDATE",
                () -> start(c.id(), c.org(), PASSWORD, codigo, null),
                l -> Barreiras.sql(l, "UPDATE user_mfa_factors SET disabled_at = clock_timestamp() WHERE user_id = '" + c.conta().id() + "' AND disabled_at IS NULL"));
        assertRecusa(c, r);
    }

    // ------------------------------------------------------------------ etapa 2: uso unico, par exato, prazos

    /**
     * O desafio e de uso unico, so o mais recente vale, e so para a identidade que o emitiu: confirmar duas vezes, usar o
     * desafio substituido ou apresentar o cookie em outra identidade (com step-up valido) recusa com 403, sem vinculo.
     */
    @Test
    void desafioDeUsoUnicoSoOMaisRecenteESoParaAPropriaIdentidade() throws Exception {
        Cenario c = cenario();
        assertThat(start(c.id(), c.org(), PASSWORD, code(c.conta().id()), null).status()).isEqualTo(204);
        String antigo = c.id().cookies().get(LINK);
        assertThat(start(c.id(), c.org(), PASSWORD, code(c.conta().id()), null).status()).isEqualTo(204);
        String novo = c.id().cookies().get(LINK);
        assertThat(count("SELECT count(*) FROM identity_link_challenges WHERE identity_id = '" + c.identity() + "' AND revoked_at IS NOT NULL"))
                .as("a etapa 1 nova revoga a anterior").isEqualTo(1);

        ComFator outra = comFator();
        assertThat(outra.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(outra.identity()) + "\"}").status()).isEqualTo(204);
        outra.browser().cookies().put(LINK, novo);
        assertThat(confirm(outra.browser(), c.org()).status()).as("cookie em outra identidade").isEqualTo(403);
        assertThat(count("SELECT count(*) FROM identity_users WHERE user_id = '" + c.conta().id() + "'")).isZero();

        assertThat(stepUpIdentidade(c)).isEqualTo(204);
        c.id().cookies().put(LINK, antigo);
        BrowserClient.Response substituido = confirm(c.id(), c.org());
        assertThat(substituido.status()).as("desafio substituido").isEqualTo(403);
        assertThat(substituido.body()).isEqualTo(FORBIDDEN);
        c.id().cookies().put(LINK, novo);
        assertThat(confirm(c.id(), c.org()).status()).isEqualTo(204);
        c.id().cookies().put(LINK, novo);
        assertThat(confirm(c.id(), c.org()).status()).as("segunda confirmacao com o mesmo desafio").isEqualTo(403);
        assertThat(count("SELECT count(*) FROM identity_users WHERE user_id = '" + c.conta().id() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + c.identity() + "' AND event_type = 9")).isEqualTo(1);
    }

    /** Sem step-up de ate 5 min: 403 sem efeito, e o desafio continua valido; com o step-up, a mesma confirmacao vincula. */
    @Test
    void confirmacaoSemStepUpRecusaSemConsumirODesafio() throws Exception {
        Cenario c = cenario();
        assertThat(start(c.id(), c.org(), PASSWORD, code(c.conta().id()), null).status()).isEqualTo(204);
        String desafio = c.id().cookies().get(LINK);
        assertThat(confirm(c.id(), c.org()).status()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM identity_link_challenges WHERE identity_id = '" + c.identity() + "' AND consumed_at IS NULL AND revoked_at IS NULL"))
                .isEqualTo(1);
        assertThat(vivos(c.conta().id())).as("nada fechado").startsWith("1|");
        assertThat(stepUpIdentidade(c)).isEqualTo(204);
        c.id().cookies().put(LINK, desafio);
        assertThat(confirm(c.id(), c.org()).status()).isEqualTo(204);
    }

    /** Desafio vencido (5 min contados da criacao): 403 sem vinculo, mesmo com step-up valido. */
    @Test
    void desafioVencidoERecusado() throws Exception {
        Cenario c = cenario();
        String token = Tokens.newToken();
        DB.asSuperuserIn(c.org(), "INSERT INTO identity_link_challenges (identity_id, user_id, token_hash, account_credential_hash, created_at, expires_at) VALUES ('"
                + c.identity() + "', '" + c.conta().id() + "', '" + Tokens.sha256Hex(token) + "', (SELECT password_hash FROM user_credentials WHERE user_id = '"
                + c.conta().id() + "'), now() - interval '6 minutes', now() - interval '1 minute')");
        assertThat(stepUpIdentidade(c)).isEqualTo(204);
        c.id().cookies().put(LINK, token);
        assertThat(confirm(c.id(), c.org()).status()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM identity_users WHERE user_id = '" + c.conta().id() + "'")).isZero();
    }

    /** Troca da senha local entre as etapas: a prova antiga nao autoriza o novo estado. 403 sem vinculo. */
    @Test
    void senhaLocalTrocadaEntreAsEtapasInvalidaAProva() throws Exception {
        Cenario c = cenario();
        assertThat(start(c.id(), c.org(), PASSWORD, code(c.conta().id()), null).status()).isEqualTo(204);
        assertThat(c.legada().post("/api/v1/o/" + c.org() + "/auth/password",
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"senha-nova-bem-longa-3\"}").status()).isEqualTo(204);
        assertThat(stepUpIdentidade(c)).isEqualTo(204);
        assertThat(confirm(c.id(), c.org()).status()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM identity_users WHERE user_id = '" + c.conta().id() + "'")).isZero();
        assertThat(c.legada().get("/api/v1/o/" + c.org() + "/me").status()).as("a conta segue local").isEqualTo(200);
    }

    // ------------------------------------------------------------------ concorrencia (desenho §4.5, pela API)

    /** Duas confirmacoes simultaneas com o mesmo desafio: uma vincula (204), a outra recusa (403). Um vinculo, um evento. */
    @Test
    void confirmacoesConcorrentesVinculamUmaVez() throws Exception {
        Cenario c = cenario();
        assertThat(start(c.id(), c.org(), PASSWORD, code(c.conta().id()), null).status()).isEqualTo(204);
        assertThat(stepUpIdentidade(c)).isEqualTo(204);
        BrowserClient segundo = browser();
        segundo.cookies().putAll(c.id().cookies());
        segundo.csrf = c.id().csrf;
        String corpo = "{\"organizationId\":\"" + c.org() + "\"}";
        ExecutorService ex = Executors.newFixedThreadPool(2);
        List<Integer> status = new ArrayList<>();
        try (Connection l = DB.connectAsSuperuser()) {
            l.setAutoCommit(false);
            Barreiras.sql(l, "SELECT 1 FROM identities WHERE identity_id = '" + c.identity() + "' FOR UPDATE");
            Future<BrowserClient.Response> a = ex.submit(() -> Barreiras.postLongo(port, c.id(), CONFIRM, corpo));
            Future<BrowserClient.Response> b = ex.submit(() -> Barreiras.postLongo(port, segundo, CONFIRM, corpo));
            assertThat(platformWaitingOnLock(2)).as("as duas esperam a identidade").isTrue();
            l.commit();
            status.add(a.get().status());
            status.add(b.get().status());
        } finally {
            ex.shutdownNow();
        }
        assertThat(status).containsExactlyInAnyOrder(204, 403);
        assertThat(count("SELECT count(*) FROM identity_users WHERE user_id = '" + c.conta().id() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + c.identity() + "' AND event_type = 9")).isEqualTo(1);
    }

    /**
     * Intercalamento 1 da §4.5: a vinculacao chega primeiro ao lock da conta e a senha local espera atras dela. Ao obter o
     * lock, a trigger da 017 recusa o desafio local: o login responde o 401 generico (antes desta missao, 500), sem desafio
     * vivo. A elegibilidade lida antes da espera nao basta, e o banco fecha.
     */
    @Test
    void loginLocalQueEsperaAVinculacaoERecusadoComO401Generico() throws Exception {
        Cenario c = cenario();
        assertThat(start(c.id(), c.org(), PASSWORD, code(c.conta().id()), null).status()).isEqualTo(204);
        assertThat(stepUpIdentidade(c)).isEqualTo(204);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        BrowserClient.Response vinculo;
        BrowserClient.Response login;
        BrowserClient local = browser();
        fetchPreAuthCsrf(local);
        try (Connection l = DB.connectAsSuperuser()) {
            l.setAutoCommit(false);
            Barreiras.sql(l, "SELECT 1 FROM users WHERE user_id = '" + c.conta().id() + "' FOR UPDATE");
            Future<BrowserClient.Response> v = ex.submit(() -> Barreiras.postLongo(port, c.id(), CONFIRM, "{\"organizationId\":\"" + c.org() + "\"}"));
            assertThat(platformWaitingOnLock(1)).as("a vinculacao espera a conta").isTrue();
            Future<BrowserClient.Response> p = ex.submit(() -> Barreiras.postLongo(port, local, "/api/v1/o/" + c.org() + "/auth/login",
                    "{\"email\":" + q(c.conta().email()) + ",\"password\":\"" + PASSWORD + "\"}"));
            assertThat(platformWaitingOnLock(2)).as("o login local espera atras dela").isTrue();
            l.commit();
            vinculo = v.get();
            login = p.get();
        } finally {
            ex.shutdownNow();
        }
        assertThat(vinculo.status()).isEqualTo(204);
        assertThat(login.status()).isEqualTo(401);
        assertThat(login.body()).isEqualTo(UNAUTH);
        assertThat(vivos(c.conta().id())).isEqualTo("0|0|0|0|0");
    }

    // ------------------------------------------------------------------ vazamento

    /** Nenhum log (processo e PostgreSQL) contem senha, codigo, token, segredo de fator, URI otpauth ou e-mail das jornadas. */
    @Test
    void logsSemSegredos(CapturedOutput output) throws Exception {
        Cenario c = cenario();
        String totp = code(c.conta().id());
        start(c.id(), c.org(), "senha-sentinela-XYZ-987", "654321", null);
        assertThat(start(c.id(), c.org(), PASSWORD, totp, null).status()).isEqualTo(204);
        String link = c.id().cookies().get(LINK);
        assertThat(stepUpIdentidade(c)).isEqualTo(204);
        assertThat(confirm(c.id(), c.org()).status()).isEqualTo(204);
        BrowserClient org = trocar(c, c.org());
        String totpOrg = code(c.identity());
        assertThat(org.post("/api/v1/o/" + c.org() + "/auth/mfa/step-up", "{\"code\":\"" + totpOrg + "\"}").status()).isEqualTo(204);

        List<String> segredos = new ArrayList<>(List.of(PASSWORD, SENHA, "senha-sentinela-XYZ-987", totp, totpOrg, link, c.conta().email(),
                c.id().cookies().get("__Host-nekkora_id"), org.cookies().get("__Host-nekkora_session"), org.csrf, Tokens.sha256Hex(link),
                Base32.encode(SECRETS.get(c.conta().id())), Base32.encode(SECRETS.get(c.identity())), "otpauth://"));
        segredos.addAll(c.codigos());
        String app = output.getAll();
        String pg = DB.serverLog();
        for (String s : segredos) {
            assertThat(app).as("log do processo").doesNotContain(s);
            assertThat(pg).as("log do PostgreSQL").doesNotContain(s);
        }
        assertThat(app).contains("evento=identidade.vinculacao_prova_negada").contains("evento=identidade.vinculada")
                .contains("evento=identidade.step_up_organizacao ");
    }

    // ------------------------------------------------------------------ apoio

    private static String corpo(UUID org, String senha, String code, String recovery) {
        return "{\"organizationId\":\"" + org + "\",\"password\":" + q(senha) + (code != null ? ",\"code\":" + q(code) : "")
                + (recovery != null ? ",\"recoveryCode\":" + q(recovery) : "") + "}";
    }

    private static BrowserClient.Response start(BrowserClient b, UUID org, String senha, String code, String recovery) {
        return b.post(START, corpo(org, senha, code, recovery));
    }

    private static BrowserClient.Response confirm(BrowserClient b, UUID org) {
        return b.post(CONFIRM, "{\"organizationId\":\"" + org + "\"}");
    }

    private int stepUpIdentidade(Cenario c) {
        return c.id().post("/api/v1/auth/step-up", "{\"code\":\"" + code(c.identity()) + "\"}").status();
    }

    /** Troca pela sessao de identidade do cenario, num navegador novo com o cookie dela; devolve-o com o CSRF da organizacao. */
    private BrowserClient trocar(Cenario c, UUID org) {
        BrowserClient b = browser();
        b.cookies().put("__Host-nekkora_id", c.id().cookies().get("__Host-nekkora_id"));
        b.csrf = c.id().csrf;
        BrowserClient.Response r = b.post("/api/v1/sessions/organization", "{\"organizationId\":\"" + org + "\"}");
        assertThat(r.field("status")).isEqualTo("sessao_criada");
        b.csrf = r.field("csrfToken");
        return b;
    }

    /** "sessoes|desafios|tokens|fatores|codigos" locais VIVOS da conta. */
    private static String vivos(UUID conta) throws Exception {
        return value("SELECT (SELECT count(*) FROM user_sessions WHERE user_id = '" + conta + "' AND revoked_at IS NULL) || '|' || "
                + "(SELECT count(*) FROM auth_challenges WHERE user_id = '" + conta + "' AND consumed_at IS NULL AND invalidated_at IS NULL) || '|' || "
                + "(SELECT count(*) FROM password_reset_tokens WHERE user_id = '" + conta + "' AND consumed_at IS NULL AND revoked_at IS NULL) || '|' || "
                + "(SELECT count(*) FROM user_mfa_factors WHERE user_id = '" + conta + "' AND disabled_at IS NULL) || '|' || "
                + "(SELECT count(*) FROM user_mfa_recovery_codes WHERE user_id = '" + conta + "' AND used_at IS NULL AND invalidated_at IS NULL)");
    }

    private static long passoLocal(UUID conta) throws Exception {
        String p = value("SELECT last_used_step FROM user_mfa_factors WHERE user_id = '" + conta + "' AND disabled_at IS NULL");
        return p == null ? -1 : Long.parseLong(p);
    }

    private static void assertRecusa(Cenario c, BrowserClient.Response r) throws Exception {
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body()).isEqualTo(UNAUTH);
        assertThat(c.id().cookies()).as("sem cookie de desafio").doesNotContainKey(LINK);
        assertThat(count("SELECT count(*) FROM identity_link_challenges WHERE identity_id = '" + c.identity() + "'")).as("sem desafio").isZero();
        assertThat(count("SELECT count(*) FROM user_mfa_recovery_codes WHERE user_id = '" + c.conta().id() + "' AND used_at IS NOT NULL")).isZero();
        assertThat(count("SELECT count(*) FROM identity_users WHERE user_id = '" + c.conta().id() + "'")).isZero();
    }
}
