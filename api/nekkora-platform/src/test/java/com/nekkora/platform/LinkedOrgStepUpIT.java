package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;
import com.nekkora.platform.auth.crypto.Totp;

/**
 * Step-up por organizacao em sessao VINCULADA (contrato P4), pela rota existente {@code /api/v1/o/{org}/auth/mfa/step-up},
 * com a chave da entrada geral ligada. Papel real nekkora_platform, 017 R5 (evento 14 = ORG_STEP_UP), Redis real. Cada
 * recusa confere a resposta E o estado persistido (superusuario, sem RLS): marcador de step-up das sessoes de organizacao
 * e de identidade, passo do fator da identidade e eventos nas duas trilhas.
 *
 * <p>A conta vinculada aqui e a criada pelo cadastro ({@code identity_create_organization}, canal cadastro_web): a pessoa
 * que criou a organizacao e o ORG_ADMIN dela e nao tem fator local, entao so o P4 lhe da as acoes protegidas por step-up.
 */
// contexto proprio (propriedades da classe): fechado ao fim dela, para nao manter um pool a mais aberto contra o limite
// de conexoes do PostgreSQL de teste, compartilhado pela suite inteira
@DirtiesContext
class LinkedOrgStepUpIT extends IdentityTestBase {

    private static final String TROCA = "/api/v1/sessions/organization";
    private static final String FORBIDDEN = "{\"error\":\"forbidden\"}";

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
    }

    /** Navegador com os DOIS cookies (identidade e organizacao) e o CSRF da sessao de organizacao. */
    record Vinculada(UUID org, BrowserClient b, String identityCsrf, String orgSessionId, String identitySessionId) {
    }

    // ------------------------------------------------------------------ caminho feliz e alcance

    /**
     * Duas sessoes de organizacao da mesma sessao de identidade, uma terceira de outra sessao de identidade da mesma pessoa e
     * uma de outra identidade: so a solicitante recebe o step-up. A sessao de identidade nao e elevada. Eventos 6 (na
     * organizacao) e 14 (na identidade, com a organizacao) no mesmo commit; o passo avanca no fator da identidade. E o efeito
     * observavel: a acao administrativa protegida por step-up, recusada antes, passa a ser aceita SO nessa sessao.
     */
    @Test
    void elevaSoASessaoDeOrganizacaoSolicitanteEHabilitaAAcaoProtegida() throws Exception {
        ComFator a = comFator();
        UUID org = organizacaoPorOutraSessao(a, "Org vinculada");
        Vinculada x1 = sessaoDeOrganizacao(a.browser(), org);
        Vinculada x2 = sessaoDeOrganizacao(a.browser(), org);
        BrowserClient a2 = client();
        login(a2, a.email(), SENHA);
        BrowserClient.Response v = verifyTotp(a2, a.identity());
        assertThat(v.status()).isEqualTo(200);
        a2.csrf = v.field("csrfToken");
        Vinculada x3 = sessaoDeOrganizacao(a2, org);
        ComFator b = comFator();
        UUID orgB = organizacaoPorOutraSessao(b, "Org de outra pessoa");
        Vinculada y = sessaoDeOrganizacao(b.browser(), orgB);

        TestUser alvo = internalUser(new Org(org, null, null, null), true, 2);
        firstLogin(browser(), org, alvo);
        String reset = "/api/v1/o/" + org + "/users/" + alvo.id() + "/mfa/reset";
        assertThat(x1.b().post(reset, SessionsAndAdministrationIT.RESET_BODY).status()).as("antes do step-up: acao protegida recusada").isEqualTo(403);

        long passoAntes = passo(a.identity());
        BrowserClient.Response r = stepUp(x1, code(a.identity()));
        assertThat(r.status()).isEqualTo(204);
        assertThat(r.body()).isEmpty();

        assertThat(stepUpEm(x1.orgSessionId())).as("a sessao solicitante").isTrue();
        assertThat(stepUpEm(x2.orgSessionId())).as("outra sessao de organizacao da mesma sessao de identidade").isFalse();
        assertThat(stepUpEm(x3.orgSessionId())).as("sessao de organizacao de outra sessao de identidade da mesma pessoa").isFalse();
        assertThat(stepUpEm(y.orgSessionId())).as("sessao de outra identidade").isFalse();
        assertThat(count("SELECT count(*) FROM identity_sessions WHERE identity_id IN ('" + a.identity() + "', '" + b.identity() + "') AND mfa_step_up_at IS NOT NULL"
                + " AND revoked_at IS NULL"))
                .as("nenhuma sessao de identidade e elevada").isZero();
        assertThat(eventosOrg(org)).as("evento 6 na organizacao").isEqualTo(1);
        assertThat(eventos14(a.identity(), org)).as("evento 14 com a organizacao").isEqualTo(1);
        assertThat(eventos14(b.identity(), orgB)).isZero();
        assertThat(passo(a.identity())).as("o passo avancou no fator da identidade").isGreaterThan(passoAntes);

        assertThat(x2.b().post(reset, SessionsAndAdministrationIT.RESET_BODY).status()).as("outra sessao continua sem step-up").isEqualTo(403);
        assertThat(x1.b().post(reset, SessionsAndAdministrationIT.RESET_BODY).status()).as("depois do step-up: acao aceita").isEqualTo(204);
    }

    /**
     * Um so anti-replay, no fator da identidade: o codigo aceito aqui nao serve para outro step-up de organizacao, para o
     * step-up global nem para o login; e o codigo usado no step-up global nao serve aqui.
     */
    @Test
    void codigoAceitoNaoServeDeNovoEmNenhumLugar() throws Exception {
        ComFator a = comFator();
        UUID org = organizacaoPorOutraSessao(a, "Org replay");
        Vinculada x1 = sessaoDeOrganizacao(a.browser(), org);
        Vinculada x2 = sessaoDeOrganizacao(a.browser(), org);
        String codigo = code(a.identity());
        assertThat(stepUp(x1, codigo).status()).isEqualTo(204);
        long passo = passo(a.identity());

        assertThat(stepUp(x2, codigo).status()).as("replay em outra sessao de organizacao").isEqualTo(403);
        assertThat(stepUp(x1, codigo).status()).as("replay na mesma sessao").isEqualTo(403);
        BrowserClient global = client();
        global.cookies().put("__Host-nekkora_id", a.browser().cookies().get("__Host-nekkora_id"));
        global.csrf = x1.identityCsrf();
        assertThat(global.post("/api/v1/auth/step-up", "{\"code\":\"" + codigo + "\"}").status()).as("replay no step-up global").isEqualTo(403);
        BrowserClient entrada = client();
        login(entrada, a.email(), SENHA);
        assertThat(entrada.post("/api/v1/auth/mfa/verify", "{\"code\":\"" + codigo + "\"}").status()).as("replay no login").isEqualTo(401);
        assertThat(stepUpEm(x2.orgSessionId())).isFalse();
        assertThat(passo(a.identity())).isEqualTo(passo);
        assertThat(eventos14(a.identity(), org)).isEqualTo(1);

        String doGlobal = code(a.identity());
        assertThat(global.post("/api/v1/auth/step-up", "{\"code\":\"" + doGlobal + "\"}").status()).isEqualTo(204);
        assertThat(stepUp(x2, doGlobal).status()).as("o codigo do step-up global nao serve aqui").isEqualTo(403);
        assertThat(stepUpEm(x2.orgSessionId())).isFalse();
    }

    // ------------------------------------------------------------------ cookies e CSRF

    /**
     * Cookie de identidade ausente, de outra sessao da mesma identidade ou de outra identidade: 403 SEM conferir o codigo
     * (passo intocado, nada gravado, nenhuma falha contada). O CSRF global no lugar do da organizacao: 403 do filtro.
     */
    @Test
    void cookieDeIdentidadeDivergenteECsrfTrocadoSaoRecusadosSemEfeito() throws Exception {
        ComFator a = comFator();
        UUID org = organizacaoPorOutraSessao(a, "Org cookies");
        Vinculada x = sessaoDeOrganizacao(a.browser(), org);
        String origem = x.b().cookies().get("__Host-nekkora_id");
        BrowserClient a2 = client();
        login(a2, a.email(), SENHA);
        BrowserClient.Response v = verifyTotp(a2, a.identity());
        assertThat(v.status()).isEqualTo(200);
        ComFator b = comFator();
        long passo = passo(a.identity());
        String codigo = codigoDuravel(a.identity());

        x.b().cookies().remove("__Host-nekkora_id");
        BrowserClient.Response sem = stepUp(x, codigo);
        assertThat(sem.status()).as("sem o cookie de identidade").isEqualTo(403);
        assertThat(sem.body()).isEqualTo(FORBIDDEN);
        x.b().cookies().put("__Host-nekkora_id", a2.cookies().get("__Host-nekkora_id"));
        assertThat(stepUp(x, codigo).status()).as("outra sessao da mesma identidade").isEqualTo(403);
        x.b().cookies().put("__Host-nekkora_id", b.browser().cookies().get("__Host-nekkora_id"));
        assertThat(stepUp(x, code(b.identity())).status()).as("outra identidade, com o codigo dela").isEqualTo(403);
        x.b().cookies().put("__Host-nekkora_id", origem);
        String csrf = x.b().csrf;
        x.b().csrf = x.identityCsrf();
        assertThat(stepUp(x, codigo).status()).as("CSRF global no lugar do da organizacao").isEqualTo(403);
        x.b().csrf = null;
        assertThat(stepUp(x, codigo).status()).as("sem CSRF").isEqualTo(403);
        x.b().csrf = csrf;

        assertThat(stepUpEm(x.orgSessionId())).isFalse();
        assertThat(passo(a.identity())).as("o codigo nem foi conferido").isEqualTo(passo);
        assertThat(eventosOrg(org)).isZero();
        assertThat(eventos14(a.identity(), org)).isZero();
        assertThat(stepUp(x, codigo).status()).as("controle: cookie de origem e CSRF da organizacao").isEqualTo(204);
    }

    /** Codigo errado: 403 generico, nada gravado; conta em R8m pela identidade global (o mesmo contador do MFA da entrada geral). */
    @Test
    void codigoErradoRecusaSemEfeitoEContaEmR8m() throws Exception {
        ComFator a = comFator();
        UUID org = organizacaoPorOutraSessao(a, "Org codigo errado");
        Vinculada x = sessaoDeOrganizacao(a.browser(), org);
        long passo = passo(a.identity());
        for (int i = 1; i <= 12; i++) {
            if (i == 12) {
                pause(1100); // o atraso de 1 s aberto pela 11a falha passa; a 12a abre o de 2 s
            }
            x.b().forwardedIp = "198.51.100." + (20 + i);
            BrowserClient.Response r = stepUp(x, codigoErrado(a.identity()));
            assertThat(r.status()).as("falha " + i).isEqualTo(403);
            assertThat(r.body()).isEqualTo(FORBIDDEN);
        }
        assertThat(stepUpEm(x.orgSessionId())).isFalse();
        assertThat(passo(a.identity())).isEqualTo(passo);
        assertThat(eventosOrg(org)).isZero();
        x.b().forwardedIp = "198.51.100.99";
        BrowserClient global = client();
        global.cookies().put("__Host-nekkora_id", a.browser().cookies().get("__Host-nekkora_id"));
        global.csrf = x.identityCsrf();
        // um so codigo valido para as duas tentativas (o 429 nao o consome): sem esperar o proximo passo, o atraso de 2 s segue aberto
        String valido = code(a.identity());
        assertThat(stepUp(x, valido).status()).as("R8m: limite da identidade global").isEqualTo(429);
        assertThat(global.post("/api/v1/auth/step-up", "{\"code\":\"" + valido + "\"}").status())
                .as("o mesmo contador vale para o step-up global").isEqualTo(429);
    }

    /** Conta NAO vinculada: o caminho local de sempre, com o fator local; o cookie de identidade presente e ignorado. */
    @Test
    void contaNaoVinculadaContinuaNoCaminhoLocal() throws Exception {
        Org o = newOrganization();
        TestUser u = internalUser(o, true, 2);
        BrowserClient b = browser();
        firstLogin(b, o.id(), u);
        ComFator f = comFator();
        b.cookies().put("__Host-nekkora_id", f.browser().cookies().get("__Host-nekkora_id"));
        String sessao = value("SELECT session_id FROM user_sessions WHERE session_token_hash = '" + Tokens.sha256Hex(b.cookies().get("__Host-nekkora_session")) + "'");
        String passoIdentidade = value("SELECT last_used_step FROM identity_mfa_factors WHERE identity_id = '" + f.identity() + "' AND confirmed_at IS NOT NULL");

        assertThat(b.post("/api/v1/o/" + o.id() + "/auth/mfa/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status())
                .as("o fator da identidade nao serve para a conta local").isEqualTo(403);
        assertThat(b.post("/api/v1/o/" + o.id() + "/auth/mfa/step-up", "{\"code\":\"" + code(u.id()) + "\"}").status())
                .as("fator local: caminho de sempre").isEqualTo(204);
        assertThat(stepUpEm(sessao)).isTrue();
        assertThat(value("SELECT last_used_step FROM identity_mfa_factors WHERE identity_id = '" + f.identity() + "' AND confirmed_at IS NOT NULL"))
                .as("o fator da identidade nao foi tocado").isEqualTo(passoIdentidade);
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + f.identity() + "' AND event_type = 14")).isZero();
    }

    // ------------------------------------------------------------------ concorrencia, prazos e ordem de locks

    /**
     * Ordem global: com a ORGANIZACAO travada por outra transacao, o step-up espera segurando a identidade e a sessao de
     * identidade (FOR UPDATE NOWAIT nelas falha). A organizacao e suspensa durante a espera: ao obter o lock, a revalidacao
     * da sessao de organizacao recusa. 401, nada gravado.
     */
    @Test
    void organizacaoSuspensaDuranteAEsperaERecusadaComAIdentidadeJaTravada() throws Exception {
        ComFator a = comFator();
        UUID org = organizacaoPorOutraSessao(a, "Org suspensa na espera");
        Vinculada x = sessaoDeOrganizacao(a.browser(), org);
        long passo = passo(a.identity());
        String codigo = codigoDuravel(a.identity());
        BrowserClient.Response r = Barreiras.comLinhaTravada(DB, "SELECT 1 FROM organizations WHERE organization_id = '" + org + "' FOR UPDATE",
                () -> stepUp(x, codigo), c -> {
                    try (Connection o = DB.connectAsSuperuser()) {
                        o.setAutoCommit(false);
                        assertThatThrownBy(() -> Barreiras.sql(o, "SELECT 1 FROM identities WHERE identity_id = '" + a.identity() + "' FOR UPDATE NOWAIT"))
                                .as("a identidade ja esta travada pelo step-up").isInstanceOf(SQLException.class)
                                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("55P03"));
                        o.rollback();
                        assertThatThrownBy(() -> Barreiras.sql(o, "SELECT 1 FROM identity_sessions WHERE identity_session_id = '" + x.identitySessionId()
                                + "' FOR UPDATE NOWAIT")).as("a sessao de identidade tambem").isInstanceOf(SQLException.class);
                        o.rollback();
                    }
                    Barreiras.sql(c, "UPDATE organizations SET status = 2 WHERE organization_id = '" + org + "'");
                });
        assertThat(r.status()).as("organizacao suspensa na espera").isEqualTo(401);
        assertThat(stepUpEm(x.orgSessionId())).isFalse();
        assertThat(passo(a.identity())).as("o codigo nem foi conferido").isEqualTo(passo);
        assertThat(eventosOrg(org)).isZero();
        assertThat(eventos14(a.identity(), org)).isZero();
    }

    /** Sessao de organizacao revogada enquanto o step-up espera a conta: revalidada sob o lock. 401, nada gravado. */
    @Test
    void sessaoDeOrganizacaoRevogadaDuranteAEsperaERecusada() throws Exception {
        ComFator a = comFator();
        UUID org = organizacaoPorOutraSessao(a, "Org revogada na espera");
        Vinculada x = sessaoDeOrganizacao(a.browser(), org);
        long passo = passo(a.identity());
        String conta = LinkedAccountLocalEntryIT.contaDaIdentidade(a.identity(), org).toString();
        String codigo = codigoDuravel(a.identity());
        BrowserClient.Response r = Barreiras.comLinhaTravada(DB, "SELECT 1 FROM users WHERE user_id = '" + conta + "' FOR UPDATE",
                () -> stepUp(x, codigo),
                c -> Barreiras.sql(c, "UPDATE user_sessions SET revoked_at = clock_timestamp() WHERE session_id = '" + x.orgSessionId() + "'"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(value("SELECT mfa_step_up_at IS NULL FROM user_sessions WHERE session_id = '" + x.orgSessionId() + "'")).isEqualTo("t");
        assertThat(passo(a.identity())).isEqualTo(passo);
        assertThat(eventosOrg(org)).isZero();
        assertThat(eventos14(a.identity(), org)).isZero();
    }

    /** Logout da identidade enquanto o step-up espera a identidade: a sessao de origem e reconferida sob o lock. 401, nada gravado. */
    @Test
    void logoutDaIdentidadeDuranteAEsperaERecusado() throws Exception {
        ComFator a = comFator();
        UUID org = organizacaoPorOutraSessao(a, "Org logout na espera");
        Vinculada x = sessaoDeOrganizacao(a.browser(), org);
        long passo = passo(a.identity());
        String codigo = codigoDuravel(a.identity());
        BrowserClient.Response r = Barreiras.comLinhaTravada(DB, "SELECT 1 FROM identities WHERE identity_id = '" + a.identity() + "' FOR UPDATE",
                () -> stepUp(x, codigo),
                c -> Barreiras.sql(c, "UPDATE identity_sessions SET revoked_at = clock_timestamp() WHERE identity_session_id = '" + x.identitySessionId() + "'"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(stepUpEm(x.orgSessionId())).isFalse();
        assertThat(passo(a.identity())).isEqualTo(passo);
        assertThat(eventos14(a.identity(), org)).isZero();
    }

    /**
     * Espera DEPOIS da validacao do TOTP (linha do fator da identidade travada, sem alteracao): o passo vence durante a
     * espera do anti-replay. Janela reconferida antes do commit: rollback integral (passo, step-up da organizacao, eventos 6
     * e 14). 403.
     */
    @Test
    void passoVencidoDuranteOAntiReplayDesfazTudo() throws Exception {
        ComFator a = comFator();
        UUID org = organizacaoPorOutraSessao(a, "Org passo vencido");
        Vinculada x = sessaoDeOrganizacao(a.browser(), org);
        long ultimo = passo(a.identity());
        while (Totp.step(java.time.Instant.now()) - 1 <= ultimo) {
            pause(500);
        }
        long passo = Totp.step(java.time.Instant.now()) - 1;
        String codigo = Totp.code(SECRETS.get(a.identity()), passo);
        BrowserClient.Response r = Barreiras.comLinhaTravada(DB, travaFator(a.identity()),
                () -> Barreiras.postLongo(port, x.b(), stepUpPath(x), "{\"code\":\"" + codigo + "\"}"), c -> {
                    while (Totp.step(java.time.Instant.now()) < passo + 2) {
                        pause(500);
                    }
                });
        assertThat(r.status()).as("passo fora da janela no fim").isEqualTo(403);
        assertThat(stepUpEm(x.orgSessionId())).as("step-up da organizacao desfeito").isFalse();
        assertThat(passo(a.identity())).as("avanco do passo desfeito").isEqualTo(ultimo);
        assertThat(eventosOrg(org)).as("evento 6 desfeito").isZero();
        assertThat(eventos14(a.identity(), org)).as("evento 14 desfeito").isZero();
    }

    /**
     * Sessao de organizacao vence DEPOIS da validacao do TOTP, enquanto o anti-replay espera o fator (TOTP ainda valido).
     * Reconferida pelo relogio do banco antes do commit: 401 e rollback integral. Controle: a mesma espera liberada antes
     * do vencimento conclui com 204.
     */
    @Test
    void sessaoDeOrganizacaoVencidaDuranteOAntiReplayDesfazTudo() throws Exception {
        ComFator a = comFator();
        UUID org = organizacaoPorOutraSessao(a, "Org sessao vencida");
        Vinculada x = sessaoDeOrganizacao(a.browser(), org);
        long passo = passo(a.identity());
        BrowserClient.Response r = esperaComVencimento(x, a.identity(), true);
        assertThat(r.status()).as("sessao de organizacao vencida durante o anti-replay").isEqualTo(401);
        assertThat(stepUpEm(x.orgSessionId())).isFalse();
        assertThat(passo(a.identity())).isEqualTo(passo);
        assertThat(eventosOrg(org)).isZero();
        assertThat(eventos14(a.identity(), org)).isZero();

        Vinculada controle = sessaoDeOrganizacao(a.browser(), org);
        assertThat(esperaComVencimento(controle, a.identity(), false).status()).as("controle: liberada antes do vencimento").isEqualTo(204);
        assertThat(stepUpEm(controle.orgSessionId())).isTrue();
    }

    /** Falha ao gravar o evento 6 (organizacao) ou o 14 (identidade): o step-up inteiro e desfeito (mesma transacao). */
    @Test
    void falhaEmQualquerEventoDesfazOStepUp() throws Exception {
        ComFator a = comFator();
        UUID org = organizacaoPorOutraSessao(a, "Org auditoria");
        Vinculada x = sessaoDeOrganizacao(a.browser(), org);
        String sufixo = a.identity().toString().replace("-", "");
        for (String[] alvo : new String[][] {{"mfa_events", "NEW.organization_id = '" + org + "' AND NEW.event_type = 6"},
                {"identity_events", "NEW.identity_id = '" + a.identity() + "' AND NEW.event_type = 14"}}) {
            long passo = passo(a.identity());
            String gatilho = "teste_falha_p4_" + alvo[0] + "_" + sufixo;
            exec("CREATE FUNCTION public." + gatilho + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'falha injetada'; END $$");
            exec("CREATE TRIGGER " + gatilho + " BEFORE INSERT ON " + alvo[0] + " FOR EACH ROW WHEN (" + alvo[1] + ") EXECUTE FUNCTION public." + gatilho + "()");
            BrowserClient.Response r;
            try {
                r = stepUp(x, code(a.identity()));
            } finally {
                exec("DROP TRIGGER " + gatilho + " ON " + alvo[0]);
                exec("DROP FUNCTION public." + gatilho + "()");
            }
            assertThat(r.status()).as("falha em " + alvo[0]).isEqualTo(500);
            assertThat(stepUpEm(x.orgSessionId())).as("sem step-up (" + alvo[0] + ")").isFalse();
            assertThat(passo(a.identity())).as("sem avanco do passo (" + alvo[0] + ")").isEqualTo(passo);
            assertThat(eventosOrg(org)).isZero();
            assertThat(eventos14(a.identity(), org)).isZero();
        }
        assertThat(stepUp(x, code(a.identity())).status()).as("controle: sem falha injetada").isEqualTo(204);
    }

    /**
     * Dois step-ups simultaneos da mesma pessoa em duas sessoes de organizacao, com codigos de passos diferentes: os dois
     * serializam na identidade (sem impasse) e cada um eleva so a propria sessao.
     */
    @Test
    void stepUpsConcorrentesDaMesmaPessoaSerializamNaIdentidade() throws Exception {
        ComFator a = comFator();
        UUID org = organizacaoPorOutraSessao(a, "Org concorrente");
        Vinculada x1 = sessaoDeOrganizacao(a.browser(), org);
        Vinculada x2 = sessaoDeOrganizacao(a.browser(), org);
        long agora = Math.max(Totp.step(java.time.Instant.now()), passo(a.identity()) + 2);
        while (Totp.step(java.time.Instant.now()) < agora) {
            pause(500);
        }
        String c1 = Totp.code(SECRETS.get(a.identity()), agora - 1);
        String c2 = Totp.code(SECRETS.get(a.identity()), agora);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try (Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            Barreiras.sql(c, "SELECT 1 FROM identities WHERE identity_id = '" + a.identity() + "' FOR UPDATE");
            Future<BrowserClient.Response> f1 = ex.submit(() -> stepUp(x1, c1));
            assertThat(platformWaitingOnLock(1)).isTrue();
            Future<BrowserClient.Response> f2 = ex.submit(() -> stepUp(x2, c2));
            assertThat(platformWaitingOnLock(2)).as("os dois esperam a identidade").isTrue();
            c.commit();
            assertThat(f1.get().status()).isEqualTo(204);
            assertThat(f2.get().status()).isEqualTo(204);
        } finally {
            ex.shutdownNow();
        }
        assertThat(stepUpEm(x1.orgSessionId()) && stepUpEm(x2.orgSessionId())).isTrue();
        assertThat(eventos14(a.identity(), org)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ apoio

    private static final int PRAZO_CURTO_S = 5;

    private BrowserClient.Response esperaComVencimento(Vinculada x, UUID identity, boolean vencer) throws Exception {
        String codigo = codigoDuravel(identity);
        exec("UPDATE user_sessions SET expires_at = clock_timestamp() + interval '" + PRAZO_CURTO_S + " seconds' WHERE session_id = '" + x.orgSessionId() + "'");
        BrowserClient.Response r = Barreiras.comLinhaTravada(DB, travaFator(identity),
                () -> Barreiras.postLongo(port, x.b(), stepUpPath(x), "{\"code\":\"" + codigo + "\"}"), c -> {
                    if (vencer) {
                        while (!"t".equals(value("SELECT expires_at < clock_timestamp() - interval '300 milliseconds' FROM user_sessions WHERE session_id = '"
                                + x.orgSessionId() + "'"))) {
                            pause(200);
                        }
                    }
                });
        if (vencer) {
            assertThat(Totp.verify(SECRETS.get(identity), codigo, java.time.Instant.now())).as("o TOTP continua na janela").isPresent();
        }
        return r;
    }

    /** Troca pela sessao de identidade de {@code id}, num navegador novo que leva o cookie de identidade dela. */
    private Vinculada sessaoDeOrganizacao(BrowserClient id, UUID org) throws Exception {
        BrowserClient b = browser();
        b.cookies().put("__Host-nekkora_id", id.cookies().get("__Host-nekkora_id"));
        b.csrf = id.csrf;
        BrowserClient.Response r = b.post(TROCA, "{\"organizationId\":\"" + org + "\"}");
        assertThat(r.field("status")).as("troca").isEqualTo("sessao_criada");
        String identityCsrf = b.csrf;
        b.csrf = r.field("csrfToken");
        String orgSession = value("SELECT session_id FROM user_sessions WHERE session_token_hash = '" + Tokens.sha256Hex(b.cookies().get("__Host-nekkora_session")) + "'");
        String idSession = value("SELECT identity_session_id FROM identity_sessions WHERE session_token_hash = '" + Tokens.sha256Hex(id.cookies().get("__Host-nekkora_id")) + "'");
        return new Vinculada(org, b, identityCsrf, orgSession, idSession);
    }

    /**
     * Organizacao criada pela funcao da 017 a partir de OUTRA sessao de identidade da mesma pessoa (a preparacao grava
     * step-up nela), que depois sai de cena (revogada). A sessao do teste, a de {@code f.browser()}, nunca teve step-up.
     */
    private UUID organizacaoPorOutraSessao(ComFator f, String nome) throws Exception {
        BrowserClient c = client();
        login(c, f.email(), SENHA);
        BrowserClient.Response v = verifyTotp(c, f.identity());
        assertThat(v.status()).isEqualTo(200);
        UUID org = LinkedAccountLocalEntryIT.organizacaoDa(c, nome);
        exec("UPDATE identity_sessions SET revoked_at = clock_timestamp() WHERE session_token_hash = '" + Tokens.sha256Hex(c.cookies().get("__Host-nekkora_id")) + "'");
        return org;
    }

    private static String stepUpPath(Vinculada x) {
        return "/api/v1/o/" + x.org() + "/auth/mfa/step-up";
    }

    private static BrowserClient.Response stepUp(Vinculada x, String codigo) {
        return x.b().post(stepUpPath(x), "{\"code\":\"" + codigo + "\"}");
    }

    private static boolean stepUpEm(String orgSession) throws Exception {
        return "t".equals(value("SELECT mfa_step_up_at IS NOT NULL FROM user_sessions WHERE session_id = '" + orgSession + "'"));
    }

    private static long passo(UUID identity) throws Exception {
        String p = value("SELECT last_used_step FROM identity_mfa_factors WHERE identity_id = '" + identity + "' AND confirmed_at IS NOT NULL AND disabled_at IS NULL");
        return p == null ? -1 : Long.parseLong(p);
    }

    private static long eventosOrg(UUID org) throws Exception {
        return count("SELECT count(*) FROM mfa_events WHERE organization_id = '" + org + "' AND event_type = 6");
    }

    private static long eventos14(UUID identity, UUID org) throws Exception {
        return count("SELECT count(*) FROM identity_events WHERE identity_id = '" + identity + "' AND event_type = 14 AND target_organization_id = '" + org + "'");
    }

    private static String travaFator(UUID identity) throws Exception {
        return "SELECT 1 FROM identity_mfa_factors WHERE factor_id = '" + value("SELECT factor_id FROM identity_mfa_factors WHERE identity_id = '" + identity
                + "' AND confirmed_at IS NOT NULL AND disabled_at IS NULL") + "' FOR UPDATE";
    }

    /** Codigo do passo ATUAL, posterior ao ultimo usado: fica na janela (+-1) por pelo menos 30 s. */
    private static String codigoDuravel(UUID identity) throws Exception {
        long ultimo = passo(identity);
        while (Totp.step(java.time.Instant.now()) <= ultimo) {
            pause(500);
        }
        return Totp.code(SECRETS.get(identity), Totp.step(java.time.Instant.now()));
    }

    /** Codigo de 6 digitos diferente de todos os aceitos agora (agora-2 .. agora+2). */
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
}
