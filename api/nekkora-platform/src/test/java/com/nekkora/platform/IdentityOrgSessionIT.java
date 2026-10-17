package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;

/**
 * Organizacoes da identidade e troca por sessao de organizacao (Missao 1, desenho §6.4, §6.5, DC-17.4, DC-17.6,
 * DC-17.13; porta do levantamento de modularidade, §4.3), com a chave da entrada geral LIGADA. Papel real
 * nekkora_platform, 017 R4b, Redis real.
 *
 * <p>Preparacao: a organizacao da identidade e criada pela propria funcao da 017 ({@code identity_create_organization}),
 * chamada pelo superusuario depois de gravar o step-up na sessao de identidade - a rota de criacao ainda nao existe (ela
 * depende do contrato do step-up). Isso e so preparacao: a troca e a listagem passam pelo caminho real.
 */
// contexto proprio (propriedades da classe): fechado ao fim dela, para nao manter um pool a mais aberto contra o limite
// de conexoes do PostgreSQL de teste, compartilhado pela suite inteira
@DirtiesContext
class IdentityOrgSessionIT extends IdentityTestBase {

    private static final String TROCA = "/api/v1/sessions/organization";
    private static final String LISTA = "/api/v1/me/organizations";
    private static final String SEM_ACESSO = "{\"status\":\"sem_acesso\"}";

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
    }

    // ------------------------------------------------------------------ listagem

    @Test
    void listaSoAsOrganizacoesVinculadasEElegiveis() throws Exception {
        ComFator f = comFator();
        BrowserClient.Response vazia = f.browser().get(LISTA);
        assertThat(vazia.status()).isEqualTo(200);
        assertThat(vazia.body()).as("recem-confirmada: lista vazia e legitima").isEqualTo("{\"organizations\":[],\"canCreateOrganization\":false}");

        UUID a = organizacaoDa(f.browser(), "Alfa");
        UUID b = organizacaoDa(f.browser(), "Beta");
        UUID suspensa = organizacaoDa(f.browser(), "Gama");
        exec("UPDATE organizations SET status = 2 WHERE organization_id = '" + suspensa + "'");
        contaAntigaComOMesmoEmail(f.email());
        organizacaoDa(comFator().browser(), "De outra pessoa");

        BrowserClient.Response r = f.browser().get(LISTA);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"organizations\":["
                + "{\"organizationId\":\"" + a + "\",\"name\":\"Alfa\",\"roles\":[\"ORG_ADMIN\"]},"
                + "{\"organizationId\":\"" + b + "\",\"name\":\"Beta\",\"roles\":[\"ORG_ADMIN\"]}],"
                + "\"canCreateOrganization\":false}");
    }

    /**
     * Chave propria da criacao (P2) DESLIGADA com a entrada geral ligada (contexto desta classe): POST /api/v1/organizations
     * responde 404 mesmo com sessao, CSRF e step-up validos; nada criado. E canCreateOrganization e falso mesmo para quem a
     * funcao de consulta da R5 permitiria.
     */
    @Test
    void criacaoComAChavePropriaDesligadaE404EIndicacaoFalsa() throws Exception {
        ComFator f = comFator();
        exec("UPDATE identity_sessions SET mfa_step_up_at = clock_timestamp() WHERE session_token_hash = '"
                + Tokens.sha256Hex(f.browser().cookies().get("__Host-nekkora_id")) + "'");
        assertThat(value("SELECT identity_org_creation_allowed('" + f.browser().cookies().get("__Host-nekkora_id") + "')"))
                .as("a funcao de consulta permitiria").isEqualTo("t");
        BrowserClient.Response r = f.browser().post("/api/v1/organizations", "{\"name\":\"Chave desligada\"}");
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.body()).isEqualTo("{\"error\":\"not_found\"}");
        assertThat(count("SELECT count(*) FROM identity_users WHERE identity_id = '" + f.identity() + "'")).as("nada criado").isZero();
        assertThat(f.browser().get(LISTA).body()).endsWith("\"canCreateOrganization\":false}");
    }

    // ------------------------------------------------------------------ troca

    @Test
    void trocaEmiteSessaoDeOrganizacaoVinculadaQueServeAsRotasDaOrganizacao() throws Exception {
        ComFator f = comFator();
        UUID org = organizacaoDa(f.browser(), "Alfa");
        String idSessao = sessaoDeIdentidade(f.browser());

        long t0 = System.nanoTime();
        BrowserClient.Response r = trocar(f.browser(), org);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.field("status")).isEqualTo("sessao_criada");
        assertThat(r.field("csrfToken")).hasSize(43);
        assertThat(ms).as("piso de tempo tambem no sucesso").isGreaterThanOrEqualTo(280);
        String c = cookie(r, "__Host-nekkora_session");
        assertThat(c).as("cookie da sessao de organizacao").contains("Path=/").contains("Secure").contains("HttpOnly").contains("SameSite=Lax");
        assertThat(r.body()).as("token so no cookie").doesNotContain(f.browser().cookies().get("__Host-nekkora_session"));

        String hash = Tokens.sha256Hex(f.browser().cookies().get("__Host-nekkora_session"));
        assertThat(value("SELECT (s.identity_session_id = '" + idSessao + "') || '|' || (s.mfa_verified_at = i.mfa_verified_at) || '|' || (s.expires_at = i.expires_at)"
                + " FROM user_sessions s JOIN identity_sessions i ON i.identity_session_id = s.identity_session_id WHERE s.session_token_hash = '" + hash + "'"))
                .as("ligada a sessao de identidade, MFA herdado, prazo limitado ao da sessao de identidade").isEqualTo("true|true|true");
        assertThat(value("SELECT count(*) FROM identity_events WHERE identity_id = '" + f.identity() + "' AND event_type = 7 AND target_organization_id = '" + org + "'"))
                .isEqualTo("1");

        BrowserClient.Response me = f.browser().get("/api/v1/o/" + org + "/me");
        assertThat(me.status()).as("rota da organizacao com a sessao emitida").isEqualTo(200);
        assertThat(me.body()).contains(f.email().toLowerCase());
    }

    @Test
    void semAcessoEUniformeParaQualquerCausa() throws Exception {
        ComFator f = comFator();
        UUID suspensa = organizacaoDa(f.browser(), "Suspensa");
        exec("UPDATE organizations SET status = 2 WHERE organization_id = '" + suspensa + "'");
        UUID antiga = contaAntigaComOMesmoEmail(f.email());
        UUID alheia = organizacaoDa(comFator().browser(), "Alheia");
        UUID inexistente = UUID.randomUUID();
        long eventosAntes = count("SELECT count(*) FROM identity_events WHERE identity_id = '" + f.identity() + "' AND event_type = 8");

        for (UUID alvo : List.of(inexistente, alheia, antiga, suspensa)) {
            long t0 = System.nanoTime();
            BrowserClient.Response r = trocar(f.browser(), alvo);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(r.status()).as("alvo " + alvo).isEqualTo(200);
            assertThat(r.body()).as("alvo " + alvo).isEqualTo(SEM_ACESSO);
            assertThat(cookie(r, "__Host-nekkora_session")).as("sem cookie").isNull();
            assertThat(ms).as("piso de tempo").isGreaterThanOrEqualTo(280);
            assertThat(count("SELECT count(*) FROM user_sessions WHERE organization_id = '" + alvo + "'")).as("nenhuma sessao em " + alvo).isZero();
        }
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + f.identity() + "' AND event_type = 8 AND target_organization_id IS NULL"))
                .as("recusas registradas, sem o alvo pedido").isEqualTo(eventosAntes + 4);
    }

    @Test
    void logoutDaIdentidadeDerrubaASessaoDeOrganizacaoDerivadaSemEscritaNaOrganizacao() throws Exception {
        ComFator f = comFator();
        UUID org = organizacaoDa(f.browser(), "Alfa");
        String csrfIdentidade = f.browser().csrf;
        assertThat(trocar(f.browser(), org).field("status")).isEqualTo("sessao_criada");
        String hash = Tokens.sha256Hex(f.browser().cookies().get("__Host-nekkora_session"));
        assertThat(f.browser().get("/api/v1/o/" + org + "/me").status()).isEqualTo(200);

        f.browser().csrf = csrfIdentidade;
        assertThat(f.browser().post("/api/v1/auth/logout", null).status()).isEqualTo(204);

        assertThat(f.browser().get("/api/v1/o/" + org + "/me").status()).as("cai na requisicao seguinte (DC-17.4)").isEqualTo(401);
        assertThat(value("SELECT revoked_at IS NULL FROM user_sessions WHERE session_token_hash = '" + hash + "'"))
                .as("sem escrita na organizacao: a linha continua sem revogacao; quem recusa e a revalidacao").isEqualTo("t");
    }

    @Test
    void sessaoDeOrganizacaoDoLoginPorOrganizacaoContinuaComoAntes() throws Exception {
        Org o = newOrganization();
        TestUser u = internalUser(o, true, 2);
        BrowserClient n = browser();
        firstLogin(n, o.id(), u);
        assertThat(n.get("/api/v1/o/" + o.id() + "/me").status()).as("controle: ramo 1, sem sessao de identidade").isEqualTo(200);
        assertThat(value("SELECT count(*) FROM user_sessions WHERE user_id = '" + u.id() + "' AND identity_session_id IS NULL AND revoked_at IS NULL"))
                .isEqualTo("1");
    }

    // ------------------------------------------------------------------ sessao restrita, ausente, CSRF, corpo

    @Test
    void sessaoRestritaARecuperacaoERecusadaNasDuasRotas() throws Exception {
        ComFator f = comFator();
        UUID org = organizacaoDa(f.browser(), "Alfa");
        BrowserClient b = client();
        assertThat(login(b, f.email(), SENHA).field("status")).isEqualTo("mfa_required");
        BrowserClient.Response v = b.post("/api/v1/auth/mfa/verify", "{\"recoveryCode\":\"" + f.codes().get(0) + "\"}");
        assertThat(v.status()).isEqualTo(200);
        b.csrf = v.field("csrfToken");
        assertThat(value("SELECT count(*) FROM identity_sessions WHERE identity_id = '" + f.identity() + "' AND recovery_used_at IS NOT NULL AND revoked_at IS NULL"))
                .as("sessao restrita").isEqualTo("1");

        assertThat(b.get(LISTA).status()).isEqualTo(403);
        BrowserClient.Response r = trocar(b, org);
        assertThat(r.status()).isEqualTo(403);
        assertThat(cookie(r, "__Host-nekkora_session")).isNull();
        assertThat(count("SELECT count(*) FROM user_sessions WHERE organization_id = '" + org + "'")).isZero();
    }

    @Test
    void semSessaoDeIdentidadeCsrfErradoECorpoInvalido() throws Exception {
        ComFator f = comFator();
        UUID org = organizacaoDa(f.browser(), "Alfa");

        BrowserClient anonimo = client();
        assertThat(anonimo.get(LISTA).body()).isEqualTo(UNAUTH);
        assertThat(anonimo.post(TROCA, "{\"organizationId\":\"" + org + "\"}").status()).isEqualTo(401);

        // a sessao de ORGANIZACAO nao autentica as rotas da identidade (os dois cookies nunca valem na mesma rota)
        String csrf = f.browser().csrf;
        assertThat(trocar(f.browser(), org).field("status")).isEqualTo("sessao_criada");
        BrowserClient soOrganizacao = browser();
        soOrganizacao.cookies().put("__Host-nekkora_session", f.browser().cookies().get("__Host-nekkora_session"));
        assertThat(soOrganizacao.get(LISTA).status()).isEqualTo(401);

        f.browser().csrf = "x".repeat(43);
        assertThat(trocar(f.browser(), org).status()).as("CSRF da sessao de identidade").isEqualTo(403);
        f.browser().csrf = null;
        assertThat(trocar(f.browser(), org).status()).isEqualTo(403);
        f.browser().csrf = csrf;
        assertThat(f.browser().post(TROCA, "{\"organizationId\":\"nao-e-uuid\"}").status()).isEqualTo(400);
        assertThat(f.browser().post(TROCA, "{\"organizationId\":\"1-1-1-1-1\"}").status()).as("UUID nao canonico").isEqualTo(400);
        assertThat(f.browser().post(TROCA, "{}").status()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM user_sessions WHERE organization_id = '" + org + "'")).as("so a da troca valida").isEqualTo(1);
    }

    // ------------------------------------------------------------------ mudanca entre a resolucao e a emissao

    @Test
    void organizacaoSuspensaDuranteAEsperaDaEmissaoERecusada() throws Exception {
        ComFator f = comFator();
        UUID org = organizacaoDa(f.browser(), "Alfa");
        BrowserClient.Response r = comOrganizacaoTravada(org, () -> postLongo(f.browser(), TROCA, "{\"organizationId\":\"" + org + "\"}"),
                c -> sql(c, "UPDATE organizations SET status = 2 WHERE organization_id = '" + org + "'"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).as("a candidata da resolucao inicial nao autoriza: a emissao reconfere depois da espera").isEqualTo(SEM_ACESSO);
        assertThat(count("SELECT count(*) FROM user_sessions WHERE organization_id = '" + org + "'")).isZero();
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + f.identity() + "' AND event_type = 8")).isEqualTo(1);
    }

    /**
     * Revisao 2: com a identidade e a sessao de identidade travadas pela emissao (ordem global da 017), a revogacao nao
     * passa mais "por baixo" da espera - o logout real espera a emissao. Confirmada a emissao, o logout revoga e a sessao
     * de organizacao emitida cai na requisicao seguinte (DC-17.4). (Na revisao 1 este teste revogava a sessao como
     * superusuario durante a espera; com a ordem nova essa escrita esperaria a emissao, que espera a organizacao travada
     * pelo proprio teste.)
     */
    @Test
    void logoutDuranteAEsperaDaEmissaoEsperaAEmissaoEDerrubaASessaoEmitida() throws Exception {
        ComFator f = comFator();
        UUID org = organizacaoDa(f.browser(), "Alfa");
        String idSessao = sessaoDeIdentidade(f.browser());
        ExecutorService ex = Executors.newSingleThreadExecutor();
        BrowserClient.Response[] logout = new BrowserClient.Response[1];
        BrowserClient.Response r;
        try {
            r = comOrganizacaoTravada(org, () -> postLongo(f.browser(), TROCA, "{\"organizationId\":\"" + org + "\"}"), c -> {
                Future<BrowserClient.Response> l = ex.submit(() -> postLongo(f.browser(), "/api/v1/auth/logout", ""));
                assertThat(platformWaitingOnLock(2)).as("o logout espera a emissao").isTrue();
                assertThat(l.isDone()).isFalse();
                c.commit();
                logout[0] = l.get();
            });
        } finally {
            ex.shutdownNow();
        }
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.field("status")).as("a emissao confirmada antes do logout").isEqualTo("sessao_criada");
        assertThat(logout[0].status()).isEqualTo(204);
        assertThat(value("SELECT revoked_at IS NOT NULL FROM identity_sessions WHERE identity_session_id = '" + idSessao + "'")).isEqualTo("t");
        assertThat(value("SELECT count(*) FILTER (WHERE event_type = 8) || '|' || count(*) FILTER (WHERE event_type = 7) FROM identity_events WHERE identity_id = '"
                + f.identity() + "'")).as("emissao registrada, nenhuma recusa").isEqualTo("0|1");
        String emitida = cookie(r, "__Host-nekkora_session");
        assertThat(emitida).isNotNull();
        String token = emitida.substring("__Host-nekkora_session=".length(), emitida.indexOf(';'));
        assertThat(value("SELECT revoked_at IS NULL FROM user_sessions WHERE session_token_hash = '" + Tokens.sha256Hex(token) + "'"))
                .as("a sessao emitida existe, sem escrita na organizacao").isEqualTo("t");
        BrowserClient n = browser();
        n.cookies().put("__Host-nekkora_session", token);
        assertThat(n.get("/api/v1/o/" + org + "/me").status()).as("a sessao emitida cai na requisicao seguinte (DC-17.4)").isEqualTo(401);
    }

    /**
     * Ordem dos locks da porta (desenho §13.5, requisito 1): a organizacao e travada ANTES da revogacao das sessoes
     * excedentes. T1 faz o que a suspensao faz - trava a organizacao e depois revoga as sessoes da conta. Com a ordem
     * certa, a troca espera so pela organizacao, nao segura nenhuma sessao, e T1 revoga sem esperar. Sem o lock previo, a
     * troca revogaria a sessao excedente (segurando a linha) e depois esperaria a organizacao: impasse com T1.
     */
    @Test
    void trocaTravaAOrganizacaoAntesDasSessoesDaConta() throws Exception {
        ComFator f = comFator();
        UUID org = organizacaoDa(f.browser(), "Alfa");
        String csrf = f.browser().csrf;
        for (int i = 0; i < 10; i++) {
            f.browser().csrf = csrf;
            assertThat(trocar(f.browser(), org).field("status")).isEqualTo("sessao_criada");
        }
        String conta = value("SELECT user_id FROM user_sessions WHERE organization_id = '" + org + "' LIMIT 1");
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + conta + "' AND revoked_at IS NULL")).as("limite por conta atingido").isEqualTo(10);
        f.browser().csrf = csrf;
        int[] revogadas = new int[1];
        long[] esperaT1 = new long[1];
        BrowserClient.Response r = comOrganizacaoTravada(org, () -> postLongo(f.browser(), TROCA, "{\"organizationId\":\"" + org + "\"}"), c -> {
            sql(c, "SET LOCAL lock_timeout = '10s'");
            long t0 = System.nanoTime();
            try (PreparedStatement ps = c.prepareStatement("UPDATE user_sessions SET revoked_at = clock_timestamp() WHERE user_id = '" + conta
                    + "' AND revoked_at IS NULL")) {
                revogadas[0] = ps.executeUpdate();
            }
            esperaT1[0] = (System.nanoTime() - t0) / 1_000_000;
        });
        assertThat(revogadas[0]).as("T1 revogou as 10 sem impasse").isEqualTo(10);
        assertThat(esperaT1[0]).as("T1 nao esperou a troca").isLessThan(500);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.field("status")).isEqualTo("sessao_criada");
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + conta + "' AND revoked_at IS NULL")).isEqualTo(1);
    }

    /**
     * Teto de sessoes sob concorrencia (revisao 1): conta com 9 sessoes e teto de 10. A conta fica travada por outra
     * transacao ate as DUAS trocas estarem esperando; liberada, elas seguem uma depois da outra. A politica de limite tem
     * de rodar DEPOIS dos locks: a segunda troca recalcula e revoga a mais antiga. Desde a revisao 2 a primeira troca
     * espera a conta segurando a identidade, e a segunda espera a identidade (ordem global da 017).
     */
    @Test
    void duasTrocasConcorrentesComNoveSessoesRespeitamOTeto() throws Exception {
        ComFator f = comFator();
        UUID org = organizacaoDa(f.browser(), "Alfa");
        String csrf = f.browser().csrf;
        for (int i = 0; i < 9; i++) {
            f.browser().csrf = csrf;
            assertThat(trocar(f.browser(), org).field("status")).isEqualTo("sessao_criada");
        }
        f.browser().csrf = csrf;
        String conta = value("SELECT user_id FROM user_sessions WHERE organization_id = '" + org + "' LIMIT 1");
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + conta + "' AND revoked_at IS NULL")).isEqualTo(9);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try (Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            sql(c, "SELECT 1 FROM users WHERE user_id = '" + conta + "' FOR UPDATE");
            Future<BrowserClient.Response> a = ex.submit(() -> postLongo(f.browser(), TROCA, "{\"organizationId\":\"" + org + "\"}"));
            Future<BrowserClient.Response> b = ex.submit(() -> postLongo(f.browser(), TROCA, "{\"organizationId\":\"" + org + "\"}"));
            assertThat(platformWaitingOnLock(2)).as("as duas trocas esperam (a conta; a identidade)").isTrue();
            c.commit();
            assertThat(a.get().field("status")).isEqualTo("sessao_criada");
            assertThat(b.get().field("status")).isEqualTo("sessao_criada");
        } finally {
            ex.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + conta + "' AND revoked_at IS NULL"))
                .as("teto de 10 sessoes ativas por conta").isEqualTo(10);
    }

    /**
     * Emissao e evento na mesma transacao (revisao 1). Falha injetada so na gravacao do evento de emissao desta identidade
     * (gatilho temporario de teste, removido no fim): a troca falha e NADA fica feito - nem sessao nova, nem revogacao das
     * excedentes pelo limite.
     */
    @Test
    void falhaAoGravarOEventoDeEmissaoDesfazAEmissao() throws Exception {
        ComFator f = comFator();
        UUID org = organizacaoDa(f.browser(), "Alfa");
        String csrf = f.browser().csrf;
        for (int i = 0; i < 10; i++) {
            f.browser().csrf = csrf;
            assertThat(trocar(f.browser(), org).field("status")).isEqualTo("sessao_criada");
        }
        f.browser().csrf = csrf;
        String antes = value("SELECT string_agg(session_id::text, ',' ORDER BY session_id) FROM user_sessions WHERE organization_id = '" + org
                + "' AND revoked_at IS NULL");
        String gatilho = "teste_falha_evento_" + f.identity().toString().replace("-", "");
        exec("CREATE FUNCTION public." + gatilho + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'falha injetada'; END $$");
        exec("CREATE TRIGGER " + gatilho + " BEFORE INSERT ON identity_events FOR EACH ROW WHEN (NEW.identity_id = '" + f.identity()
                + "' AND NEW.event_type = 7) EXECUTE FUNCTION public." + gatilho + "()");
        BrowserClient.Response r;
        try {
            r = trocar(f.browser(), org);
        } finally {
            exec("DROP TRIGGER " + gatilho + " ON identity_events");
            exec("DROP FUNCTION public." + gatilho + "()");
        }
        assertThat(r.status()).as("a troca falha").isEqualTo(500);
        assertThat(cookie(r, "__Host-nekkora_session")).isNull();
        assertThat(count("SELECT count(*) FROM user_sessions WHERE organization_id = '" + org + "'")).as("nenhuma sessao nova").isEqualTo(10);
        assertThat(value("SELECT string_agg(session_id::text, ',' ORDER BY session_id) FROM user_sessions WHERE organization_id = '" + org
                + "' AND revoked_at IS NULL")).as("nenhuma revogacao pelo limite").isEqualTo(antes);
    }

    // ------------------------------------------------------------------ ordem global dos locks (revisao 2)

    /**
     * Troca x vinculacao (revisao 2; cenario da responsavel). A conta fica travada por outra transacao; a troca chega a ela
     * e espera. So DEPOIS a vinculacao ({@code identity_link_account}, chamada real como nekkora_platform, com OUTRA sessao
     * da mesma identidade) comeca: trava identidade e sessao e pede a organizacao FOR UPDATE. Liberada a conta, a troca
     * grava o evento, cuja chave estrangeira pede a identidade. Sem a identidade travada pela troca ANTES da organizacao, as
     * duas formam ciclo e o banco aborta uma delas (40P01).
     */
    @Test
    void trocaEVinculacaoComOutraSessaoDaMesmaIdentidadeNaoFormamCiclo() throws Exception {
        ComFator f = comFator();
        UUID org = organizacaoDa(f.browser(), "Alfa");
        BrowserClient outra = client();
        assertThat(login(outra, f.email(), SENHA).field("status")).isEqualTo("mfa_required");
        BrowserClient.Response v = verifyTotp(outra, f.identity());
        assertThat(v.status()).as("segunda sessao da mesma identidade").isEqualTo(200);
        trocaComVinculacaoConcorrente(f, org, outra.cookies().get("__Host-nekkora_id"));
    }

    /**
     * Como acima, com a vinculacao usando a MESMA sessao de identidade: o ciclo passaria pela chave estrangeira de
     * user_sessions.identity_session_id (a linha da sessao, travada pela vinculacao), ja no INSERT da sessao.
     */
    @Test
    void trocaEVinculacaoComAMesmaSessaoNaoFormamCiclo() throws Exception {
        ComFator f = comFator();
        UUID org = organizacaoDa(f.browser(), "Alfa");
        trocaComVinculacaoConcorrente(f, org, f.browser().cookies().get("__Host-nekkora_id"));
    }

    /**
     * Troca x logout (revisao 2). O logout revoga a sessao de identidade e grava o evento, cuja chave estrangeira pede a
     * identidade. Um gatilho temporario de teste segura o logout ENTRE as duas escritas (advisory lock da transacao de
     * teste); a troca comeca nesse intervalo e trava identidade -> sessao. Com o logout fora da ordem (sessao antes da
     * identidade): ciclo. Na ordem: a troca espera o logout e encontra a sessao revogada.
     */
    @Test
    void logoutConcorrenteComATrocaNaoFormaCiclo() throws Exception {
        ComFator f = comFator();
        UUID org = organizacaoDa(f.browser(), "Alfa");
        String idSessao = sessaoDeIdentidade(f.browser());
        long chave = Math.abs(f.identity().getMostSignificantBits() % 1_000_000_007L);
        String gatilho = "teste_pausa_logout_" + f.identity().toString().replace("-", "");
        exec("CREATE FUNCTION public." + gatilho + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(" + chave
                + "); RETURN NULL; END $$");
        exec("CREATE TRIGGER " + gatilho + " AFTER UPDATE ON identity_sessions FOR EACH ROW WHEN (NEW.identity_session_id = '" + idSessao
                + "' AND OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL) EXECUTE FUNCTION public." + gatilho + "()");
        ExecutorService ex = Executors.newFixedThreadPool(2);
        BrowserClient.Response logout;
        BrowserClient.Response troca;
        try (Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            sql(c, "SELECT pg_advisory_xact_lock(" + chave + ")");
            Future<BrowserClient.Response> l = ex.submit(() -> postLongo(f.browser(), "/api/v1/auth/logout", ""));
            assertThat(platformWaitingOnLock(1)).as("o logout revogou e espera entre as duas escritas").isTrue();
            Future<BrowserClient.Response> t = ex.submit(() -> postLongo(f.browser(), TROCA, "{\"organizationId\":\"" + org + "\"}"));
            assertThat(platformWaitingOnLock(2)).as("a troca espera").isTrue();
            c.commit();
            logout = l.get();
            troca = t.get();
        } finally {
            ex.shutdownNow();
            exec("DROP TRIGGER " + gatilho + " ON identity_sessions");
            exec("DROP FUNCTION public." + gatilho + "()");
        }
        assertThat(logout.status()).as("logout sem impasse").isEqualTo(204);
        assertThat(troca.status()).as("troca sem impasse").isEqualTo(200);
        assertThat(troca.body()).as("a troca encontra a sessao ja revogada").isEqualTo(SEM_ACESSO);
        assertThat(value("SELECT revoked_at IS NOT NULL FROM identity_sessions WHERE identity_session_id = '" + idSessao + "'")).isEqualTo("t");
        assertThat(count("SELECT count(*) FROM user_sessions WHERE organization_id = '" + org + "'")).isZero();
    }

    /** Conta travada; a troca espera a conta; a vinculacao comeca e espera; liberada a conta, as duas terminam sem 40P01. */
    private void trocaComVinculacaoConcorrente(ComFator f, UUID org, String tokenDaVinculacao) throws Exception {
        String conta = value("SELECT u.user_id FROM users u JOIN identity_users iu ON iu.user_id = u.user_id WHERE u.organization_id = '" + org
                + "' AND iu.identity_id = '" + f.identity() + "'");
        ExecutorService ex = Executors.newFixedThreadPool(2);
        BrowserClient.Response troca;
        Object vinculacao;
        try (Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            sql(c, "SELECT 1 FROM users WHERE user_id = '" + conta + "' FOR UPDATE");
            Future<BrowserClient.Response> t = ex.submit(() -> postLongo(f.browser(), TROCA, "{\"organizationId\":\"" + org + "\"}"));
            assertThat(platformWaitingOnLock(1)).as("a troca espera a conta").isTrue();
            Future<Object> l = ex.submit(() -> vincular(tokenDaVinculacao, org));
            assertThat(platformWaitingOnLock(2)).as("a vinculacao comeca e espera").isTrue();
            c.commit();
            troca = t.get();
            vinculacao = l.get();
        } finally {
            ex.shutdownNow();
        }
        assertThat(vinculacao).as("vinculacao sem impasse (desafio inexistente: recusa sem efeito)").isEqualTo(Boolean.FALSE);
        assertThat(troca.status()).as("troca sem impasse").isEqualTo(200);
        assertThat(troca.field("status")).isEqualTo("sessao_criada");
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + f.identity() + "' AND event_type = 7")).isEqualTo(1);
    }

    /** {@code identity_link_account} como nekkora_platform, READ COMMITTED; devolve o resultado ou o SQLSTATE do erro. */
    private static Object vincular(String sessionToken, UUID org) throws Exception {
        try (Connection c = DB.connectAs("nekkora_platform")) {
            c.setAutoCommit(false);
            sql(c, "SET LOCAL statement_timeout = '60s'");
            try (PreparedStatement ps = c.prepareStatement("SELECT identity_link_account(?, ?, ?)")) {
                ps.setString(1, sessionToken);
                ps.setString(2, Tokens.newToken());
                ps.setObject(3, org);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    boolean r = rs.getBoolean(1);
                    c.commit();
                    return r;
                }
            } catch (java.sql.SQLException e) {
                c.rollback();
                return "SQLSTATE " + e.getSQLState();
            }
        }
    }

    // ------------------------------------------------------------------ apoio

    private BrowserClient.Response trocar(BrowserClient b, UUID org) {
        return b.post(TROCA, "{\"organizationId\":\"" + org + "\"}");
    }

    private static String sessaoDeIdentidade(BrowserClient b) throws Exception {
        return value("SELECT identity_session_id FROM identity_sessions WHERE session_token_hash = '" + Tokens.sha256Hex(b.cookies().get("__Host-nekkora_id")) + "'");
    }

    /** Preparacao: step-up gravado na sessao de identidade e organizacao criada pela funcao da 017 com o token dela. */
    private static UUID organizacaoDa(BrowserClient b, String nome) throws Exception {
        String token = b.cookies().get("__Host-nekkora_id");
        exec("UPDATE identity_sessions SET mfa_step_up_at = clock_timestamp() WHERE session_token_hash = '" + Tokens.sha256Hex(token) + "'");
        String org = value("SELECT identity_create_organization('" + token + "', '" + nome + "', NULL)");
        assertThat(org).as("organizacao criada pela funcao").isNotNull();
        return UUID.fromString(org);
    }

    /** Conta antiga, NAO vinculada, com o mesmo e-mail da identidade, numa organizacao propria. Devolve a organizacao. */
    private static UUID contaAntigaComOMesmoEmail(String email) {
        Org o = newOrganization();
        UUID u = UUID.randomUUID();
        DB.asSuperuserIn(o.id(), "INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('"
                + u + "', '" + o.id() + "', 1, '" + email + "', now(), 2, 'Pessoa')",
                "INSERT INTO organization_memberships (organization_id, user_id, account_type, status) VALUES ('" + o.id() + "', '" + u + "', 1, 2)");
        return o.id();
    }

    /** Trava a organizacao (FOR UPDATE), dispara a acao, espera a Platform esperar o lock, roda {@code durante} e confirma. */
    private BrowserClient.Response comOrganizacaoTravada(UUID org, Callable<BrowserClient.Response> acao, SqlDurante durante) throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM organizations WHERE organization_id = '" + org + "' FOR UPDATE")) {
                assertThat(ps.executeQuery().next()).as("organizacao travada").isTrue();
            }
            Future<BrowserClient.Response> f = ex.submit(acao);
            assertThat(platformWaitingOnLock()).as("a emissao espera o lock da organizacao").isTrue();
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
                .header("Content-Type", "application/json").header("Origin", b.origin).header("X-CSRF-Token", b.csrf);
        if (b.forwardedIp != null) {
            rb.header("X-Forwarded-For", b.forwardedIp);
        }
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
}
