package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;

/**
 * Criacao de organizacao (P2 do contrato de 2026-10-13), com as chaves da entrada geral E da criacao LIGADAS. Papel real
 * nekkora_platform, 017 R5, Redis real. Cada recusa confere a resposta E o estado persistido (superusuario, sem RLS):
 * organizacoes, contas, vinculos, papeis, {@code identity_users}, eventos 6 e a reserva de R11 no Redis.
 */
// contexto proprio (propriedades da classe): fechado ao fim dela, para nao manter um pool a mais aberto contra o limite
// de conexoes do PostgreSQL de teste, compartilhado pela suite inteira
@DirtiesContext
@Import(ConfirmacaoPerdida.class)
class OrganizationCreationIT extends IdentityTestBase {

    private static final String CRIAR = "/api/v1/organizations";
    private static final String LISTA = "/api/v1/me/organizations";
    private static final String FORBIDDEN = "{\"error\":\"forbidden\"}";

    @Autowired
    StringRedisTemplate redis;

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.criacao-organizacao.enabled", () -> "true");
    }

    // ------------------------------------------------------------------ caminho feliz

    /**
     * Step-up REAL pela rota do P1 e criacao: 201 com o id; organizacao ACTIVE com nome e razao social; conta interna
     * ACTIVE com o e-mail verificado da identidade; vinculo ACTIVE; ORG_ADMIN ativo; {@code identity_users} pelo canal
     * {@code cadastro_web}; evento de provisionamento e evento 6 com a organizacao alvo. A organizacao aparece na lista
     * com ORG_ADMIN e a troca da a sessao de organizacao.
     */
    @Test
    void criaComStepUpOrgAdminEventosETrocaPelaSessaoDeOrganizacao() throws Exception {
        ComFator f = comFator();
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);

        BrowserClient.Response r = criar(f.browser(), "Clínica Ágata", "Ágata Serviços Ltda.");
        assertThat(r.status()).isEqualTo(201);
        UUID org = UUID.fromString(r.field("organizationId"));
        assertThat(r.body()).isEqualTo("{\"organizationId\":\"" + org + "\"}");

        assertThat(value("SELECT name || '|' || legal_name || '|' || status FROM organizations WHERE organization_id = '" + org + "'"))
                .isEqualTo("Clínica Ágata|Ágata Serviços Ltda.|1");
        String conta = value("SELECT u.user_id FROM users u JOIN identity_users iu ON iu.user_id = u.user_id WHERE iu.identity_id = '" + f.identity()
                + "' AND u.organization_id = '" + org + "' AND iu.link_channel = 'cadastro_web'");
        assertThat(conta).as("vinculo cadastro_web com a conta criada pela funcao").isNotNull();
        assertThat(value("SELECT account_type || '|' || status || '|' || (email = (SELECT email FROM identities WHERE identity_id = '" + f.identity()
                + "')) || '|' || (email_verified_at IS NOT NULL) FROM users WHERE user_id = '" + conta + "'")).isEqualTo("1|2|true|true");
        assertThat(value("SELECT status FROM organization_memberships WHERE user_id = '" + conta + "'")).isEqualTo("2");
        assertThat(count("SELECT count(*) FROM organization_role_assignments a JOIN organization_memberships m ON m.membership_id = a.membership_id"
                + " JOIN roles r ON r.role_id = a.role_id WHERE m.user_id = '" + conta + "' AND r.code = 'ORG_ADMIN' AND a.status = 1"))
                .as("ORG_ADMIN ativo").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM identity_provisioning_events WHERE organization_id = '" + org + "' AND channel = 'cadastro_web'"))
                .as("evento de provisionamento").isEqualTo(1);
        assertThat(eventos6(f.identity())).as("evento 6 (ORG_CREATED), uma vez").isEqualTo(1);
        assertThat(value("SELECT target_organization_id FROM identity_events WHERE identity_id = '" + f.identity() + "' AND event_type = 6"))
                .isEqualTo(org.toString());
        assertThat(reservasR11(f.identity())).as("a criacao conta em R11").isEqualTo(1);

        BrowserClient.Response lista = f.browser().get(LISTA);
        assertThat(lista.status()).isEqualTo(200);
        assertThat(lista.body()).isEqualTo("{\"organizations\":[{\"organizationId\":\"" + org + "\",\"name\":\"Clínica Ágata\",\"roles\":[\"ORG_ADMIN\"],\"activationState\":\"AGUARDANDO_ATIVACAO\"}],"
                + "\"canCreateOrganization\":true}");
        BrowserClient.Response troca = f.browser().post("/api/v1/sessions/organization", "{\"organizationId\":\"" + org + "\"}");
        assertThat(troca.status()).isEqualTo(200);
        assertThat(troca.field("status")).isEqualTo("sessao_criada");
    }

    // ------------------------------------------------------------------ recusas da funcao

    /**
     * Sem step-up: a funcao recusa (403 generico), nada e criado e a reserva de R11 e devolvida. canCreateOrganization
     * continua verdadeiro: o step-up nao entra no calculo (e pedido na hora).
     */
    @Test
    void semStepUpRecusa403SemEfeitoEDevolveAReservaDeR11() throws Exception {
        ComFator f = comFator();
        assertThat(f.browser().get(LISTA).body()).as("indicacao: pode criar").endsWith("\"canCreateOrganization\":true}");
        BrowserClient.Response r = criar(f.browser(), "Sem step-up", null);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body()).isEqualTo(FORBIDDEN);
        assertNadaCriado(f.identity());
        assertThat(reservasR11(f.identity())).as("recusa da funcao nao conta em R11").isZero();
    }

    /**
     * Teto total de 10 DENTRO da funcao, sob o lock da identidade: com 9 organizacoes do cadastro, dois pedidos simultaneos
     * com step-up passam pelo R11 (3 por dia) e chegam a funcao; exatamente um cria (201) e o outro e recusado (403).
     * canCreateOrganization, verdadeiro com 9, fica falso com 10. A reserva do recusado e devolvida.
     */
    @Test
    void tetoDeDezDentroDaFuncaoSobConcorrencia() throws Exception {
        ComFator f = comFator();
        String token = f.browser().cookies().get("__Host-nekkora_id");
        stepUpDireto(f.browser());
        for (int i = 1; i <= 9; i++) {
            assertThat(value("SELECT identity_create_organization('" + token + "', 'Previa " + i + "', NULL)")).as("preparacao " + i).isNotNull();
        }
        assertThat(f.browser().get(LISTA).body()).as("9: ainda pode").endsWith("\"canCreateOrganization\":true}");

        List<BrowserClient.Response> rs = simultaneos(f.browser(), 2, "Disputa");
        assertThat(rs.stream().map(BrowserClient.Response::status).sorted().toList()).as("um cria, o outro bate no teto").containsExactly(201, 403);
        assertThat(count("SELECT count(*) FROM identity_users WHERE identity_id = '" + f.identity() + "' AND link_channel = 'cadastro_web'")).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM organizations WHERE name = 'Disputa' AND organization_id IN (SELECT target_organization_id FROM identity_events WHERE identity_id = '"
                + f.identity() + "' AND event_type = 6)")).isEqualTo(1);
        assertThat(eventos6(f.identity())).isEqualTo(10);
        assertThat(reservasR11(f.identity())).as("so a criacao conta; a recusa devolveu a reserva").isEqualTo(1);
        assertThat(f.browser().get(LISTA).body()).as("10: indicacao falsa").endsWith("\"canCreateOrganization\":false}");
        BrowserClient.Response mais = criar(f.browser(), "Onze", null);
        assertThat(mais.status()).as("a indicacao nao autoriza nem recusa: a funcao recusa").isEqualTo(403);
    }

    // ------------------------------------------------------------------ R11

    /**
     * R11: 3 criacoes por identidade a cada 24 h. A quarta, com step-up valido: 429 com Retry-After, sem chegar a funcao
     * (nada criado). Outra identidade nao e afetada.
     */
    @Test
    void r11TresCriacoesPorIdentidadeAQuartaE429() throws Exception {
        ComFator f = comFator();
        stepUpDireto(f.browser());
        for (int i = 1; i <= 3; i++) {
            assertThat(criar(f.browser(), "R11 " + i, null).status()).as("criacao " + i).isEqualTo(201);
        }
        BrowserClient.Response quarta = criar(f.browser(), "R11 4", null);
        assertThat(quarta.status()).isEqualTo(429);
        assertThat(quarta.headers().firstValue("Retry-After")).as("Retry-After").isPresent();
        assertThat(eventos6(f.identity())).as("a quarta nao chegou a funcao").isEqualTo(3);
        assertThat(count("SELECT count(*) FROM organizations WHERE name = 'R11 4'")).isZero();

        ComFator outra = comFator();
        stepUpDireto(outra.browser());
        assertThat(criar(outra.browser(), "Outra identidade", null).status()).as("R11 e por identidade").isEqualTo(201);
    }

    /** Reserva atomica: seis pedidos simultaneos da mesma identidade, com step-up: exatamente 3 criam e 3 recebem 429. */
    @Test
    void r11ReservaAtomicaSobConcorrencia() throws Exception {
        ComFator f = comFator();
        stepUpDireto(f.browser());
        List<BrowserClient.Response> rs = simultaneos(f.browser(), 6, "Rajada");
        assertThat(rs.stream().map(BrowserClient.Response::status).sorted().toList()).containsExactly(201, 201, 201, 429, 429, 429);
        assertThat(eventos6(f.identity())).isEqualTo(3);
        assertThat(reservasR11(f.identity())).isEqualTo(3);
    }

    // ------------------------------------------------------------------ corpo, sessao, CSRF

    /**
     * Corpo: name obrigatorio; name e legalName com 1 a 120 PONTOS DE CODIGO, sem espaco nas pontas, sem controle.
     * Fora disso: 400, nada criado, R11 intocado. Limite contado em pontos de codigo: 120 emojis (240 unidades UTF-16)
     * passam; 121 nao.
     */
    @Test
    void corpoForaDaRegraE400EOLimiteEContadoEmPontosDeCodigo() throws Exception {
        ComFator f = comFator();
        stepUpDireto(f.browser());
        String emoji = new String(Character.toChars(0x1F600));
        for (String corpo : new String[] {null, "{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\" Nome\"}", "{\"name\":\"Nome \"}",
                "{\"name\":\"\\u00a0Nome\"}", "{\"name\":\"Nome\\u2003\"}", "{\"name\":\"\\u3000\"}", "{\"name\":\"No\\u0007me\"}", "{\"name\":\"No\\u0000me\"}", "{\"name\":\"" + "a".repeat(121) + "\"}",
                "{\"name\":\"" + emoji.repeat(121) + "\"}", "{\"name\":\"Ok\",\"legalName\":\"\"}", "{\"name\":\"Ok\",\"legalName\":\" Ltda\"}",
                "{\"name\":\"Ok\",\"legalName\":\"L\\ntda\"}", "{\"name\":\"\\ud83d\"}"}) {
            assertThat(f.browser().post(CRIAR, corpo).status()).as(String.valueOf(corpo)).isEqualTo(400);
        }
        assertNadaCriado(f.identity());
        assertThat(reservasR11(f.identity())).isZero();
        BrowserClient.Response limite = criar(f.browser(), emoji.repeat(120), "b".repeat(120));
        assertThat(limite.status()).as("120 pontos de codigo").isEqualTo(201);
        assertThat(value("SELECT char_length(name) || '|' || char_length(legal_name) FROM organizations WHERE organization_id = '"
                + limite.field("organizationId") + "'")).isEqualTo("120|120");
    }

    /** Sessao restrita a recuperacao (DC-17.13): 403 na criacao e na lista; nada criado, R11 intocado. */
    @Test
    void sessaoRestritaRecebe403() throws Exception {
        ComFator f = comFator();
        BrowserClient b = client();
        login(b, f.email(), SENHA);
        BrowserClient.Response v = b.post("/api/v1/auth/mfa/verify", "{\"recoveryCode\":\"" + f.codes().get(0) + "\"}");
        assertThat(v.status()).isEqualTo(200);
        b.csrf = v.field("csrfToken");
        BrowserClient.Response r = criar(b, "Restrita", null);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body()).isEqualTo(FORBIDDEN);
        assertThat(b.get(LISTA).status()).as("lista (e a indicacao) tambem recusada").isEqualTo(403);
        assertNadaCriado(f.identity());
        assertThat(reservasR11(f.identity())).isZero();
    }

    /** Sem sessao, sessao encerrada e sessao vencida: 401 na criacao e na lista, nada criado. */
    @Test
    void semSessaoEncerradaOuVencidaE401() throws Exception {
        assertThat(client().post(CRIAR, "{\"name\":\"Sem sessao\"}").status()).isEqualTo(401);

        ComFator f = comFator();
        stepUpDireto(f.browser());
        String token = f.browser().cookies().get("__Host-nekkora_id");
        String csrf = f.browser().csrf;
        assertThat(f.browser().post("/api/v1/auth/logout", null).status()).isEqualTo(204);
        f.browser().cookies().put("__Host-nekkora_id", token);
        f.browser().csrf = csrf;
        assertThat(criar(f.browser(), "Encerrada", null).status()).as("sessao encerrada").isEqualTo(401);
        assertThat(f.browser().get(LISTA).status()).isEqualTo(401);

        ComFator g = comFator();
        stepUpDireto(g.browser());
        exec("UPDATE identity_sessions SET expires_at = clock_timestamp() WHERE identity_id = '" + g.identity() + "'");
        assertThat(criar(g.browser(), "Vencida", null).status()).as("sessao vencida").isEqualTo(401);
        assertThat(g.browser().get(LISTA).status()).isEqualTo(401);
        assertNadaCriado(f.identity());
        assertNadaCriado(g.identity());
    }

    /** CSRF da sessao de identidade e Origin exigidos: 403 generico, nada criado, R11 intocado. Controle: 201. */
    @Test
    void csrfDaSessaoDeIdentidadeEOrigemSaoExigidos() throws Exception {
        ComFator f = comFator();
        stepUpDireto(f.browser());
        String csrf = f.browser().csrf;
        f.browser().csrf = null;
        assertThat(criar(f.browser(), "Sem CSRF", null).status()).isEqualTo(403);
        f.browser().csrf = Tokens.newToken();
        assertThat(criar(f.browser(), "CSRF de outro contexto", null).status()).isEqualTo(403);
        f.browser().csrf = csrf;
        String origem = f.browser().origin;
        f.browser().origin = "https://outra.exemplo.test";
        try {
            assertThat(criar(f.browser(), "Outra origem", null).status()).isEqualTo(403);
        } finally {
            f.browser().origin = origem;
        }
        assertNadaCriado(f.identity());
        assertThat(reservasR11(f.identity())).isZero();
        assertThat(criar(f.browser(), "Controle", null).status()).isEqualTo(201);
    }

    // ------------------------------------------------------------------ esperas e atomicidade

    /**
     * Sessao vence ENQUANTO a funcao espera o lock da identidade (outra transacao a segura, sem alterar): a funcao
     * reconfere sob os locks e recusa; a aplicacao ve a sessao vencida e responde 401. Nada criado, reserva devolvida.
     * Controle: liberada antes do vencimento, 201.
     */
    @Test
    void sessaoVencidaDuranteAEsperaPelaIdentidadeE401SemEfeito() throws Exception {
        ComFator f = comFator();
        stepUpDireto(f.browser());
        BrowserClient.Response r = esperaComVencimento(f, true);
        assertThat(r.status()).isEqualTo(401);
        assertNadaCriado(f.identity());
        assertThat(reservasR11(f.identity())).isZero();

        ComFator g = comFator();
        stepUpDireto(g.browser());
        assertThat(esperaComVencimento(g, false).status()).as("controle: liberada antes do vencimento").isEqualTo(201);
        assertThat(eventos6(g.identity())).isEqualTo(1);
    }

    /**
     * Commit REAL e confirmacao perdida (revisao 1 do P2): a criacao e confirmada no banco, mas a aplicacao recebe falha no
     * commit. Resultado desconhecido para a aplicacao: 500 e a reserva de R11 MANTIDA. Por outra conexao, organizacao,
     * vinculo e evento 6 existem. O controle (falha ANTES do commit, rollback integral, reserva devolvida) e
     * {@link #falhaAoGravarOEventoDesfazTudo}.
     */
    @Test
    void confirmacaoPerdidaDepoisDoCommitMantemAReserva() throws Exception {
        ComFator f = comFator();
        stepUpDireto(f.browser());
        int antes = ConfirmacaoPerdida.INJETADAS.get();
        ConfirmacaoPerdida.ARMADA.set(true);
        BrowserClient.Response r;
        try {
            r = criar(f.browser(), "Confirmacao perdida", null);
        } finally {
            ConfirmacaoPerdida.ARMADA.set(false);
        }
        assertThat(ConfirmacaoPerdida.INJETADAS.get()).as("a falha veio depois de um commit real").isEqualTo(antes + 1);
        assertThat(r.status()).as("a aplicacao nao sabe o resultado").isEqualTo(500);
        assertThat(count("SELECT count(*) FROM organizations WHERE name = 'Confirmacao perdida'")).as("organizacao confirmada no banco").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM identity_users WHERE identity_id = '" + f.identity() + "' AND link_channel = 'cadastro_web'")).isEqualTo(1);
        assertThat(eventos6(f.identity())).as("evento 6 confirmado").isEqualTo(1);
        assertThat(reservasR11(f.identity())).as("resultado desconhecido: reserva mantida").isEqualTo(1);
    }

    /** Falha ao gravar o evento 6: a funcao inteira e desfeita (mesma transacao); 500, nada criado, reserva devolvida. */
    @Test
    void falhaAoGravarOEventoDesfazTudo() throws Exception {
        ComFator f = comFator();
        stepUpDireto(f.browser());
        String gatilho = "teste_falha_criacao_" + f.identity().toString().replace("-", "");
        exec("CREATE FUNCTION public." + gatilho + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'falha injetada'; END $$");
        exec("CREATE TRIGGER " + gatilho + " BEFORE INSERT ON identity_events FOR EACH ROW WHEN (NEW.identity_id = '" + f.identity()
                + "' AND NEW.event_type = 6) EXECUTE FUNCTION public." + gatilho + "()");
        BrowserClient.Response r;
        try {
            r = criar(f.browser(), "Falha no evento", null);
        } finally {
            exec("DROP TRIGGER " + gatilho + " ON identity_events");
            exec("DROP FUNCTION public." + gatilho + "()");
        }
        assertThat(r.status()).isEqualTo(500);
        assertNadaCriado(f.identity());
        assertThat(count("SELECT count(*) FROM organizations WHERE name = 'Falha no evento'")).as("organizacao desfeita").isZero();
        assertThat(reservasR11(f.identity())).isZero();
    }

    // ------------------------------------------------------------------ apoio

    private static BrowserClient.Response criar(BrowserClient b, String nome, String razao) {
        return b.post(CRIAR, corpo(nome, razao));
    }

    private static String corpo(String nome, String razao) {
        return "{\"name\":" + json(nome) + (razao == null ? "" : ",\"legalName\":" + json(razao)) + "}";
    }

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Preparacao: step-up gravado direto na sessao (como a rota do P1 o grava), para os testes que nao tratam do step-up. */
    private static void stepUpDireto(BrowserClient b) throws Exception {
        exec("UPDATE identity_sessions SET mfa_step_up_at = clock_timestamp() WHERE session_token_hash = '"
                + Tokens.sha256Hex(b.cookies().get("__Host-nekkora_id")) + "'");
    }

    private static long eventos6(UUID identity) throws Exception {
        return count("SELECT count(*) FROM identity_events WHERE identity_id = '" + identity + "' AND event_type = 6");
    }

    private long reservasR11(UUID identity) {
        Long n = redis.opsForZSet().zCard("nk:rl:r11:" + identity);
        return n == null ? 0 : n;
    }

    private static void assertNadaCriado(UUID identity) throws Exception {
        assertThat(count("SELECT count(*) FROM identity_users WHERE identity_id = '" + identity + "'")).as("nenhum vinculo").isZero();
        assertThat(eventos6(identity)).as("nenhum evento 6").isZero();
    }

    /** {@code n} POSTs de criacao disparados juntos, com os cabecalhos do navegador (sem tocar no estado dele). */
    private List<BrowserClient.Response> simultaneos(BrowserClient b, int n, String nome) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(n);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<BrowserClient.Response>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                fs.add(ex.submit(() -> {
                    largada.await();
                    return cru(b, CRIAR, corpo(nome, null));
                }));
            }
            largada.countDown();
            List<BrowserClient.Response> rs = new ArrayList<>();
            for (Future<BrowserClient.Response> f : fs) {
                rs.add(f.get());
            }
            return rs;
        } finally {
            ex.shutdownNow();
        }
    }

    private static final int PRAZO_CURTO_S = 5;

    /** Prazo da sessao encurtado; identidade travada por outra transacao sem alteracao; libera depois (ou antes) do vencimento. */
    private BrowserClient.Response esperaComVencimento(ComFator f, boolean vencer) throws Exception {
        String sessao = value("SELECT identity_session_id FROM identity_sessions WHERE session_token_hash = '"
                + Tokens.sha256Hex(f.browser().cookies().get("__Host-nekkora_id")) + "'");
        exec("UPDATE identity_sessions SET expires_at = clock_timestamp() + interval '" + PRAZO_CURTO_S + " seconds' WHERE identity_session_id = '" + sessao + "'");
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM identities WHERE identity_id = '" + f.identity() + "' FOR UPDATE")) {
                assertThat(ps.executeQuery().next()).isTrue();
            }
            Future<BrowserClient.Response> r = ex.submit((Callable<BrowserClient.Response>) () -> cru(f.browser(), CRIAR, corpo("Espera " + vencer, null)));
            assertThat(platformWaitingOnLock()).as("a criacao espera a identidade").isTrue();
            if (vencer) {
                while (!"t".equals(value("SELECT expires_at < clock_timestamp() - interval '300 milliseconds' FROM identity_sessions WHERE identity_session_id = '" + sessao + "'"))) {
                    pause(200);
                }
            } else {
                assertThat(value("SELECT expires_at > clock_timestamp() + interval '2 seconds' FROM identity_sessions WHERE identity_session_id = '" + sessao + "'"))
                        .as("controle liberado antes do vencimento").isEqualTo("t");
            }
            c.commit();
            return r.get();
        } finally {
            ex.shutdownNow();
        }
    }

    /** POST com os mesmos cabecalhos do BrowserClient (cookies, Origin, CSRF, IP), prazo de 120 s, sem alterar o navegador. */
    private BrowserClient.Response cru(BrowserClient b, String path, String json) throws Exception {
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
