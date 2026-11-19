package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PLATFORM-LICENCIAMENTO-001 (migration 067; D7, D9 a D13) - criacao de organizacoes por perfil, pedido e aprovacao com a
 * escolha do responsavel, limite de organizacoes vazias, desativar e reativar, e a chave das organizacoes novas no modelo por
 * licenca. API real (papel nekkora_platform), entrada geral e criacao LIGADAS. Cada recusa confere a resposta E o que ficou
 * gravado. As contas de membro e de Cliente sao massa gravada direto (o convite e o portal tem os proprios testes).
 */
@DirtiesContext
class OrganizationProfilesIT extends IdentityTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CRIAR = "/api/v1/organizations";
    private static final String CRIACAO = "/api/v1/me/organization-creation";
    private static final String PEDIR = "/api/v1/organization-requests";
    private static final String MEUS_PEDIDOS = "/api/v1/me/organization-requests";

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.criacao-organizacao.enabled", () -> "true");
    }

    // ------------------------------------------------------------------ apoio

    private void stepUp(ComFator f) {
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);
    }

    /** A confirmacao reforcada da sessao deixa de valer (preparacao: o gatilho so a deixa avancar; aqui o tempo "passou"). */
    private static void noStepUp(ComFator f) {
        DB.asSuperuser("SET session_replication_role = replica", "UPDATE identity_sessions SET mfa_step_up_at = clock_timestamp() - interval '1 hour', "
                + "password_step_up_at = NULL WHERE session_token_hash = '" + Tokens.sha256Hex(token(f)) + "'");
    }

    private static String token(ComFator f) {
        return f.browser().cookies().get("__Host-nekkora_id");
    }

    /** Criacao pela funcao do banco, com a sessao real e a confirmacao ja feita (fora do limite diario da rota). Nulo = recusada. */
    private static String direct(ComFator f, String name) throws Exception {
        return value("SELECT identity_create_organization('" + token(f) + "', '" + name + "', NULL)");
    }

    private static void conflict(BrowserClient.Response r, String reason) {
        assertThat(r.status()).as(r.body()).isEqualTo(409);
        assertThat(r.body()).isEqualTo("{\"error\":\"conflict\",\"reason\":\"" + reason + "\"}");
    }

    private JsonNode creation(ComFator f) {
        BrowserClient.Response r = f.browser().get(CRIACAO);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private static String summary(JsonNode c) {
        return c.get("profile").asString() + " criar=" + c.get("canCreate").asBoolean() + " pedir=" + c.get("canRequest").asBoolean() + " vazias="
                + c.get("emptyOrganizations").get("used").asLong() + "/" + c.get("emptyOrganizations").get("limit").asLong();
    }

    /** Uma automacao na organizacao: ela deixa de ser vazia. */
    private static void automation(String org) throws Exception {
        String membership = value("SELECT membership_id FROM organization_memberships WHERE organization_id = '" + org + "' LIMIT 1");
        UUID w = AccessFixtures.namedWorkspace(UUID.fromString(org), "Fiscal", 1);
        ProductFixtures.automation(UUID.fromString(org), w, null, "Notas", UUID.fromString(membership));
    }

    /** A identidade ganha uma conta INTERNA ativa na organizacao, sem papel de administrador (massa: o convite tem teste proprio). */
    private static UUID member(ComFator f, String org) throws Exception {
        UUID u = UUID.randomUUID();
        UUID m = UUID.randomUUID();
        DB.asSuperuserIn(UUID.fromString(org),
                "INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('" + u + "', '" + org
                        + "', 1, '" + f.email() + "', now(), 2, 'Pessoa membro')",
                "INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('" + m + "', '" + org + "', '" + u
                        + "', 1, 2)");
        exec("INSERT INTO identity_users (identity_id, user_id, link_channel) VALUES ('" + f.identity() + "', '" + u + "', 'convite')");
        return m;
    }

    /** Sessao de organizacao pela troca, com a confirmacao reforcada marcada (preparacao: a rota de confirmacao tem os proprios testes). */
    private BrowserClient orgSession(ComFator f, String org, boolean stepUp) throws Exception {
        BrowserClient b = client();
        b.cookies().put("__Host-nekkora_id", token(f));
        b.csrf = f.browser().csrf;
        BrowserClient.Response r = b.post("/api/v1/sessions/organization", "{\"organizationId\":\"" + org + "\"}");
        assertThat(r.field("status")).as(r.body()).isEqualTo("sessao_criada");
        b.csrf = r.field("csrfToken");
        if (stepUp) {
            exec("UPDATE user_sessions SET mfa_step_up_at = clock_timestamp() WHERE session_token_hash = '"
                    + Tokens.sha256Hex(b.cookies().get("__Host-nekkora_session")) + "'");
        }
        return b;
    }

    private static String requests(String org) {
        return "/api/v1/o/" + org + "/organization-requests";
    }

    // ------------------------------------------------------------------ perfil e limite de vazias

    /**
     * D9, D13: a recem-cadastrada cria direto e vira administradora e responsavel. O limite de tres organizacoes VAZIAS e
     * conferido pela rota (com o motivo) e pelo banco (a funcao recusa), inclusive com duas criacoes simultaneas. Uma
     * organizacao com automacao deixa de contar.
     */
    @Test
    void primeiraOrganizacaoDiretaELimiteDeTresVaziasPorResponsavel() throws Exception {
        ComFator f = comFator();
        assertThat(summary(creation(f))).as("recem-cadastrada").isEqualTo("FIRST criar=true pedir=false vazias=0/3");
        stepUp(f);
        BrowserClient.Response first = f.browser().post(CRIAR, "{\"name\":\"Primeira\"}");
        assertThat(first.status()).as(first.body()).isEqualTo(201);
        String a = first.field("organizationId");
        assertThat(value("SELECT responsible_identity_id || '|' || source FROM organization_responsibility WHERE organization_id = '" + a + "'"))
                .as("quem criou e a responsavel").isEqualTo(f.identity() + "|1");
        assertThat(value("SELECT count(*) FROM organization_license_enforcement WHERE organization_id = '" + a + "'"))
                .as("D7 desligada: nasce no modelo anterior").isEqualTo("0");
        assertThat(summary(creation(f))).as("agora administra: cria direto").isEqualTo("ADMIN criar=true pedir=false vazias=1/3");
        String b = direct(f, "Segunda");
        String c = direct(f, "Terceira");
        assertThat(b).isNotNull();
        assertThat(c).isNotNull();

        // ---- tres vazias: a rota recusa com o motivo, nada e criado; a funcao do banco recusa tambem
        JsonNode full = creation(f);
        assertThat(summary(full)).isEqualTo("ADMIN criar=false pedir=false vazias=3/3");
        assertThat(full.get("cannotCreateReason").asString()).isEqualTo("empty_organization_limit_reached");
        assertThat(full.get("emptyOrganizations").get("organizationIds").toString()).as("a lista das vazias").contains(a).contains(b).contains(c);
        assertThat(f.browser().get("/api/v1/me/organizations").body()).endsWith("\"canCreateOrganization\":false}");
        conflict(f.browser().post(CRIAR, "{\"name\":\"Quarta\"}"), "empty_organization_limit_reached");
        assertThat(direct(f, "Quarta")).as("o banco recusa mesmo sem a aplicacao").isNull();
        assertThat(count("SELECT count(*) FROM organizations WHERE name = 'Quarta'")).isZero();
        assertThat(count("SELECT count(*) FROM identity_users WHERE identity_id = '" + f.identity() + "'")).isEqualTo(3);

        // ---- uma delas ganha automacao: deixa de ser vazia e volta a caber UMA
        automation(a);
        assertThat(summary(creation(f))).isEqualTo("ADMIN criar=true pedir=false vazias=2/3");
        // duas criacoes ao mesmo tempo, com uma vaga no limite: exatamente uma nasce
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> racing = new ArrayList<>();
        try {
            for (String name : List.of("Disputa um", "Disputa dois")) {
                racing.add(pool.submit(() -> {
                    go.await();
                    return direct(f, name);
                }));
            }
            go.countDown();
            List<String> made = new ArrayList<>();
            for (Future<String> r : racing) {
                made.add(r.get());
            }
            assertThat(made.stream().filter(java.util.Objects::nonNull).count()).as("criacoes simultaneas nao passam juntas do limite").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(summary(creation(f))).isEqualTo("ADMIN criar=false pedir=false vazias=3/3");
        // o limite e CONFIGURAVEL (procedimento do migrador)
        DB.asSuperuser("UPDATE organization_creation_settings SET empty_organizations_limit = 4, updated_by = 'teste do limite de vazias'");
        try {
            assertThat(summary(creation(f))).isEqualTo("ADMIN criar=true pedir=false vazias=3/4");
        } finally {
            DB.asSuperuser("UPDATE organization_creation_settings SET empty_organizations_limit = 3, updated_by = 'teste do limite de vazias (restaurado)'");
        }
        // o papel do servico nao altera o limite nem a chave da D7, e nao le quem e responsavel
        for (String sql : List.of("UPDATE organization_creation_settings SET empty_organizations_limit = 99", "UPDATE license_rollout SET new_organizations_licensed = true",
                "SELECT 1 FROM organization_responsibility", "SELECT 1 FROM organization_deactivations")) {
            assertThat(asPlatform(sql)).as(sql).isEqualTo("42501");
        }
    }

    // ------------------------------------------------------------------ Cliente

    /**
     * D9, D11: quem so tem conta do tipo Cliente nao cria nem solicita - pela rota (403 com o motivo) e pelo banco. O perfil e
     * reavaliado a cada tentativa: ao ganhar uma conta interna de administradora em outra organizacao, a mesma pessoa cria.
     */
    @Test
    void clienteNaoCriaNemSolicitaEAAdministracaoDeOutraOrganizacaoContinuaValendo() throws Exception {
        Org supplier = newOrganization();
        TestUser clientAccount = externalUser(supplier, newClient(supplier), null);
        UUID identity = confirmed(clientAccount.email(), SENHA);
        BrowserClient b = client();
        assertThat(login(b, clientAccount.email(), SENHA).field("status")).isEqualTo("authenticated");
        BrowserClient.Response enrolled = enroll(b, identity);
        b.csrf = enrolled.field("csrfToken");
        ComFator f = new ComFator(identity, clientAccount.email(), b, enrolled.listField("recoveryCodes"));

        JsonNode state = creation(f);
        assertThat(summary(state)).isEqualTo("CLIENT criar=false pedir=false vazias=0/3");
        assertThat(state.get("cannotCreateReason").asString()).isEqualTo("organization_creation_client_profile");
        assertThat(f.browser().get("/api/v1/me/organizations").body()).endsWith("\"canCreateOrganization\":false}");
        stepUp(f);
        BrowserClient.Response create = f.browser().post(CRIAR, "{\"name\":\"Do cliente\"}");
        assertThat(create.status()).isEqualTo(403);
        assertThat(create.body()).isEqualTo("{\"error\":\"forbidden\",\"reason\":\"organization_creation_client_profile\"}");
        BrowserClient.Response ask = f.browser().post(PEDIR, "{\"organizationId\":\"" + supplier.id() + "\",\"name\":\"Do cliente\"}");
        assertThat(ask.status()).isEqualTo(403);
        assertThat(ask.body()).isEqualTo("{\"error\":\"forbidden\",\"reason\":\"organization_creation_client_profile\"}");
        // chamada direta ao banco, com a sessao e a confirmacao validas: recusada do mesmo jeito
        assertThat(direct(f, "Do cliente")).isNull();
        assertThat(value("SELECT identity_request_organization('" + token(f) + "', '" + supplier.id() + "', 'Do cliente', 'Do cliente')")).isNull();
        assertThat(count("SELECT count(*) FROM organizations WHERE name = 'Do cliente'") + count("SELECT count(*) FROM organization_creation_requests "
                + "WHERE requester_identity_id = '" + identity + "'")).as("nada criado, nada pedido").isZero();
        // a conta de Cliente SUSPENSA deixa de definir o perfil: sem nenhuma conta ativa, e a primeira organizacao
        DB.asSuperuser("UPDATE client_memberships SET status = 3 WHERE user_id = '" + clientAccount.id() + "'");
        assertThat(summary(creation(f))).isEqualTo("FIRST criar=true pedir=false vazias=0/3");
        DB.asSuperuser("UPDATE client_memberships SET status = 2 WHERE user_id = '" + clientAccount.id() + "'");

        // ---- D11: a mesma pessoa administra OUTRA organizacao (conta interna): cria por ela; ser Cliente em A nao tira isso
        Org other = newOrganization();
        UUID membership = member(f, other.id().toString());
        DB.asSuperuserIn(other.id(), "INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, "
                + "assigned_by_membership_id) SELECT '" + other.id() + "', '" + membership + "', role_id, 1, 1, '" + other.adminMembership()
                + "' FROM roles WHERE code = 'ORG_ADMIN'");
        assertThat(summary(creation(f))).isEqualTo("ADMIN criar=true pedir=false vazias=0/3");
        BrowserClient.Response made = f.browser().post(CRIAR, "{\"name\":\"Da administradora\"}");
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        // ... e o perfil de B nao da nada em A: ela continua sem conta interna em A e nao pede em nome de A
        conflict(f.browser().post(PEDIR, "{\"organizationId\":\"" + supplier.id() + "\",\"name\":\"Em nome de A\"}"), "organization_request_not_available");
    }

    // ------------------------------------------------------------------ pedido e aprovacao

    /**
     * D10, D12: o membro (sem papel de administrador) nao cria; pede, com a confirmacao reforcada, ao administrador de uma
     * organizacao em que e membro. O administrador aprova escolhendo o RESPONSAVEL - ele (padrao) ou o solicitante -; o limite
     * de vazias do escolhido e reconferido; a organizacao nasce na mesma transacao; so o responsavel entra nela.
     */
    @Test
    void membroSolicitaEOAdministradorAprovaEscolhendoOResponsavel() throws Exception {
        ComFator admin = comFator();
        stepUp(admin);
        String home = admin.browser().post(CRIAR, "{\"name\":\"Casa\"}").field("organizationId");
        automation(home);
        ComFator worker = comFator();
        UUID workerMembership = member(worker, home);
        Org elsewhere = newOrganization();

        // ---- o membro nao cria: 409 com o motivo, e o banco recusa
        assertThat(summary(creation(worker))).isEqualTo("MEMBER criar=false pedir=true vazias=0/3");
        stepUp(worker);
        conflict(worker.browser().post(CRIAR, "{\"name\":\"Filial\"}"), "organization_creation_requires_request");
        assertThat(direct(worker, "Filial")).isNull();
        assertThat(count("SELECT count(*) FROM organizations WHERE name = 'Filial'")).isZero();

        // ---- pedido: so na organizacao em que e membro, com a confirmacao reforcada
        conflict(worker.browser().post(PEDIR, "{\"organizationId\":\"" + elsewhere.id() + "\",\"name\":\"Filial\"}"), "organization_request_not_available");
        noStepUp(worker);
        BrowserClient.Response unconfirmed = worker.browser().post(PEDIR, "{\"organizationId\":\"" + home + "\",\"name\":\"Filial\"}");
        assertThat(unconfirmed.status()).isEqualTo(403);
        assertThat(unconfirmed.body()).isEqualTo("{\"error\":\"forbidden\",\"reason\":\"step_up_required\"}");
        stepUp(worker);
        List<String> asked = new ArrayList<>();
        for (String name : List.of("Filial Norte", "Filial Sul", "Filial Leste")) {
            BrowserClient.Response r = worker.browser().post(PEDIR, "{\"organizationId\":\"" + home + "\",\"name\":\"" + name + "\",\"legalName\":\"" + name
                    + " Ltda.\"}");
            assertThat(r.status()).as(r.body()).isEqualTo(201);
            asked.add(r.field("requestId"));
        }
        conflict(worker.browser().post(PEDIR, "{\"organizationId\":\"" + home + "\",\"name\":\"Filial Oeste\"}"), "organization_request_not_available");
        assertThat(count("SELECT count(*) FROM organizations WHERE name LIKE 'Filial%'")).as("pedir nao cria nada").isZero();
        JsonNode mine = JSON.readTree(worker.browser().get(MEUS_PEDIDOS).body()).get("items");
        assertThat(mine.size()).isEqualTo(3);
        assertThat(mine.get(0).get("state").asString() + "|" + mine.get(0).get("organizationName").asString()).isEqualTo("PENDING|Casa");

        // ---- o administrador ve os pedidos; quem nao administra, nao; outra organizacao, nao
        BrowserClient workerInOrg = orgSession(worker, home, true);
        assertThat(workerInOrg.get(requests(home)).status()).as("membro nao le os pedidos").isEqualTo(403);
        assertThat(workerInOrg.post(requests(home) + "/" + asked.get(0) + "/approve", "{\"responsible\":\"REQUESTER\"}").status())
                .as("o solicitante nao aprova o proprio pedido").isEqualTo(403);
        BrowserClient chief = orgSession(admin, home, false);
        JsonNode pending = JSON.readTree(chief.get(requests(home)).body()).get("items");
        assertThat(pending.size()).isEqualTo(3);
        assertThat(pending.toString()).contains(worker.email()).contains("Filial Norte").contains("\"state\":\"PENDING\"");
        assertThat(chief.post(requests(home) + "/" + asked.get(0) + "/approve", "{\"responsible\":\"APPROVER\"}").status())
                .as("aprovar exige confirmacao reforcada").isEqualTo(403);
        chief = orgSession(admin, home, true);
        assertThat(chief.post(requests(home) + "/" + asked.get(0) + "/approve", "{\"responsible\":\"OUTRO\"}").status()).isEqualTo(400);
        assertThat(chief.post(requests(home) + "/" + asked.get(0) + "/approve", "{}").status()).as("a escolha e explicita").isEqualTo(400);
        assertThat(chief.post(requests(elsewhere.id().toString()) + "/" + asked.get(0) + "/approve", "{\"responsible\":\"APPROVER\"}").status())
                .as("pela rota de outra organizacao").isIn(401, 403, 404);

        // ---- (1) aprova ficando ELE responsavel: a organizacao nasce dele; o solicitante nao entra nela
        BrowserClient.Response mineNow = chief.post(requests(home) + "/" + asked.get(0) + "/approve", "{\"responsible\":\"APPROVER\"}");
        assertThat(mineNow.status()).as(mineNow.body()).isEqualTo(200);
        String north = mineNow.field("createdOrganizationId");
        assertThat(value("SELECT o.name || '|' || o.legal_name || '|' || r.responsible_identity_id || '|' || r.source || '|' || r.request_id FROM organizations o "
                + "JOIN organization_responsibility r ON r.organization_id = o.organization_id WHERE o.organization_id = '" + north + "'"))
                .isEqualTo("Filial Norte|Filial Norte Ltda.|" + admin.identity() + "|2|" + asked.get(0));
        assertThat(value("SELECT state || '|' || responsible || '|' || (decided_at IS NOT NULL) || '|' || created_organization_id FROM "
                + "organization_creation_requests WHERE request_id = '" + asked.get(0) + "'")).as("a escolha fica registrada").isEqualTo("2|1|true|" + north);
        assertThat(count("SELECT count(*) FROM users WHERE organization_id = '" + north + "'")).as("so o responsavel tem conta na nova organizacao").isEqualTo(1);
        assertThat(admin.browser().get("/api/v1/me/organizations").body()).contains(north);
        assertThat(worker.browser().get("/api/v1/me/organizations").body()).as("o solicitante nao recebeu acesso").doesNotContain(north);
        JsonNode workerSees = JSON.readTree(worker.browser().get(MEUS_PEDIDOS).body()).get("items");
        assertThat(workerSees.toString()).as("o solicitante ve a decisao, nao a organizacao que nao e dele").contains("\"state\":\"APPROVED\"")
                .contains("\"responsible\":\"APPROVER\"").doesNotContain(north);
        conflict(chief.post(requests(home) + "/" + asked.get(0) + "/approve", "{\"responsible\":\"APPROVER\"}"), "organization_request_not_pending");

        // ---- (2) aprova entregando ao SOLICITANTE: ele vira administrador da nova; quem aprovou nao entra nela
        BrowserClient.Response theirs = chief.post(requests(home) + "/" + asked.get(1) + "/approve", "{\"responsible\":\"REQUESTER\"}");
        assertThat(theirs.status()).as(theirs.body()).isEqualTo(200);
        assertThat(theirs.body()).as("a organizacao nao e de quem aprovou").contains("\"createdOrganizationId\":null");
        String south = value("SELECT created_organization_id FROM organization_creation_requests WHERE request_id = '" + asked.get(1) + "'");
        assertThat(value("SELECT responsible_identity_id FROM organization_responsibility WHERE organization_id = '" + south + "'"))
                .isEqualTo(worker.identity().toString());
        assertThat(worker.browser().get("/api/v1/me/organizations").body()).contains(south).contains("\"roles\":[\"ORG_ADMIN\"]");
        assertThat(admin.browser().get("/api/v1/me/organizations").body()).as("quem aprovou nao recebeu acesso").doesNotContain(south);
        assertThat(summary(creation(worker))).as("agora administra uma organizacao: cria direto, e a vazia conta para ELE")
                .isEqualTo("ADMIN criar=true pedir=false vazias=1/3");

        // ---- (3) o limite de vazias do responsavel escolhido e reconferido NA APROVACAO
        assertThat(direct(admin, "Vazia dois")).isNotNull();
        assertThat(direct(admin, "Vazia tres")).isNotNull();
        assertThat(summary(creation(admin))).isEqualTo("ADMIN criar=false pedir=false vazias=3/3");
        conflict(chief.post(requests(home) + "/" + asked.get(2) + "/approve", "{\"responsible\":\"APPROVER\"}"), "empty_organization_limit_reached");
        assertThat(value("SELECT state FROM organization_creation_requests WHERE request_id = '" + asked.get(2) + "'")).as("continua pendente").isEqualTo("1");
        assertThat(count("SELECT count(*) FROM organizations WHERE name = 'Filial Leste'")).isZero();
        // recusa: exige motivo; fica registrada com quem decidiu
        assertThat(chief.post(requests(home) + "/" + asked.get(2) + "/refuse", "{\"reason\":\"nao\"}").status()).isEqualTo(400);
        BrowserClient.Response refused = chief.post(requests(home) + "/" + asked.get(2) + "/refuse", "{\"reason\":\"a filial leste nao sera aberta este ano\"}");
        assertThat(refused.status()).as(refused.body()).isEqualTo(200);
        assertThat(value("SELECT state || '|' || refusal_reason || '|' || (decided_by_membership_id IS NOT NULL) FROM organization_creation_requests "
                + "WHERE request_id = '" + asked.get(2) + "'")).isEqualTo("3|a filial leste nao sera aberta este ano|true");

        // ---- o banco reconfere quem decide: o papel do servico nao aprova em nome de quem nao administra
        String fresh = value("INSERT INTO organization_creation_requests (organization_id, requester_identity_id, requester_membership_id, name, legal_name) "
                + "VALUES ('" + home + "', '" + worker.identity() + "', '" + workerMembership + "', 'Pelo banco', 'Pelo banco') RETURNING request_id");
        assertThat(valueAsPlatform(home, "SELECT public.identity_decide_organization_request('" + home + "', '" + workerMembership + "', '" + fresh
                + "', true, 2::smallint, NULL)")).isEqualTo("NOT_ADMIN");
        assertThat(asPlatform("UPDATE organization_creation_requests SET state = 2 WHERE request_id = '" + fresh + "'")).isEqualTo("42501");

        // ---- quem aprova com conta SEM identidade geral nao pode ser o responsavel; pode entregar ao solicitante
        Org legacy = newOrganization();
        ComFator guest = comFator();
        member(guest, legacy.id().toString());
        stepUp(guest);
        String legacyRequest = guest.browser().post(PEDIR, "{\"organizationId\":\"" + legacy.id() + "\",\"name\":\"Do convidado\"}").field("requestId");
        BrowserClient local = browser();
        firstLogin(local, legacy.id(), new TestUser(legacy.adminId(), legacy.adminEmail(), PASSWORD.toCharArray()));
        assertThat(local.post("/api/v1/o/" + legacy.id() + "/auth/mfa/step-up", "{\"code\":\"" + code(legacy.adminId()) + "\"}").status()).isEqualTo(204);
        conflict(local.post(requests(legacy.id().toString()) + "/" + legacyRequest + "/approve", "{\"responsible\":\"APPROVER\"}"),
                "organization_request_approver_without_identity");
        assertThat(local.post(requests(legacy.id().toString()) + "/" + legacyRequest + "/approve", "{\"responsible\":\"REQUESTER\"}").status()).isEqualTo(200);
        assertThat(guest.browser().get("/api/v1/me/organizations").body()).contains("Do convidado");
    }

    // ------------------------------------------------------------------ desativar e reativar

    /**
     * D13: o responsavel desativa a organizacao (recusado com trabalho em andamento) e nada e apagado nem liberado - a licenca
     * continua vinculada a mesma automacao. Ele a reativa pela propria lista; o limite de vazias e reconferido; um bloqueio da
     * Nekkora nao e removido por essa acao.
     */
    @Test
    void responsavelDesativaEReativaSemApagarNadaENaoRemoveBloqueioDaNekkora() throws Exception {
        ComFator f = comFator();
        stepUp(f);
        String org = f.browser().post(CRIAR, "{\"name\":\"A desativar\"}").field("organizationId");
        UUID id = UUID.fromString(org);
        UUID m = UUID.fromString(value("SELECT membership_id FROM organization_memberships WHERE organization_id = '" + org + "'"));
        UUID w = AccessFixtures.namedWorkspace(id, "Fiscal", 1);
        UUID a = ProductFixtures.automation(id, w, null, "Notas", m);
        UUID v = ProductFixtures.version(id, w, a, true, m);
        UUID license = LicenseFixtures.license(id, 1, "clock_timestamp() - interval '1 day'", "clock_timestamp() + interval '30 days'");
        LicenseFixtures.assign(id, license, w, a, m);
        String licenses = "SELECT (SELECT count(*) FROM organization_licenses WHERE organization_id = '" + org + "') || '|' || (SELECT count(*) FROM "
                + "organization_license_periods WHERE organization_id = '" + org + "') || '|' || (SELECT automation_id || ':' || coalesce(released_at::text, 'aberto') "
                + "FROM organization_license_assignments WHERE organization_id = '" + org + "')";
        String before = value(licenses);
        String deactivate = "/api/v1/organization-deactivations";
        String reactivate = "/api/v1/organization-reactivations";
        String ref = "{\"organizationId\":\"" + org + "\"}";

        // ---- quem nao e o responsavel nao desativa (nem outro administrador da propria organizacao)
        ComFator other = comFator();
        UUID otherMembership = member(other, org);
        DB.asSuperuserIn(id, "INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id) "
                + "SELECT '" + org + "', '" + otherMembership + "', role_id, 1, 1, '" + m + "' FROM roles WHERE code = 'ORG_ADMIN'");
        stepUp(other);
        conflict(other.browser().post(deactivate, ref), "organization_not_responsible");
        // sem a confirmacao reforcada: 403, nada muda
        noStepUp(f);
        BrowserClient.Response unconfirmed = f.browser().post(deactivate, ref);
        assertThat(unconfirmed.status()).isEqualTo(403);
        assertThat(unconfirmed.body()).isEqualTo("{\"error\":\"forbidden\",\"reason\":\"step_up_required\"}");
        stepUp(f);

        // ---- com trabalho em andamento: recusada
        for (int code = 1; code <= 5; code++) {
            DB.asSuperuser("SET session_replication_role = replica", "DELETE FROM organization_resource_limit_revisions WHERE organization_id = '" + org
                    + "' AND resource_code = " + code, "INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, "
                    + "effective_from, limit_mode, limit_value, provisioned_by_user_id) SELECT '" + org + "', " + code + ", 1, now(), 2, NULL, user_id FROM users "
                    + "WHERE organization_id = '" + org + "' LIMIT 1");
        }
        UUID running = ProductFixtures.execution(id, w, a, v, null, m);
        conflict(f.browser().post(deactivate, ref), "organization_work_in_progress");
        assertThat(value("SELECT status FROM organizations WHERE organization_id = '" + org + "'")).isEqualTo("1");
        ProductFixtures.cancelBeforeDispatch(running, m);

        // ---- desativa: situacao 3, registrada; sai da lista de quem entra; a sessao de organizacao cai; NADA de licenca muda
        BrowserClient inside = orgSession(f, org, false);
        assertThat(inside.get("/api/v1/o/" + org + "/me").status()).isEqualTo(200);
        assertThat(f.browser().post(deactivate, ref).status()).isEqualTo(204);
        assertThat(value("SELECT o.status || '|' || d.deactivated_by_identity_id || '|' || (d.reactivated_at IS NULL) FROM organizations o JOIN "
                + "organization_deactivations d ON d.organization_id = o.organization_id WHERE o.organization_id = '" + org + "'"))
                .isEqualTo("3|" + f.identity() + "|true");
        assertThat(f.browser().get("/api/v1/me/organizations").body()).doesNotContain(org);
        assertThat(inside.get("/api/v1/o/" + org + "/me").status()).as("ninguem continua dentro").isEqualTo(401);
        assertThat(value(licenses)).as("licenca, periodo e vinculo intactos: desativar nao libera licenca").isEqualTo(before);
        assertThat(count("SELECT count(*) FROM executions WHERE organization_id = '" + org + "'")).as("historico preservado").isEqualTo(1);
        conflict(f.browser().post(deactivate, ref), "organization_state_conflict");
        JsonNode mine = creation(f).get("responsibleFor");
        assertThat(mine.toString()).as("aparece para o responsavel, reativavel").contains("\"organizationId\":\"" + org + "\"")
                .contains("\"state\":\"DEACTIVATED\"").contains("\"canReactivate\":true");

        // ---- reativar: so o responsavel; volta ao que era
        conflict(other.browser().post(reactivate, ref), "organization_not_responsible");
        assertThat(f.browser().post(reactivate, ref).status()).isEqualTo(204);
        assertThat(value("SELECT o.status || '|' || (SELECT count(*) FROM organization_deactivations d WHERE d.organization_id = o.organization_id "
                + "AND d.reactivated_at IS NULL) FROM organizations o WHERE o.organization_id = '" + org + "'")).isEqualTo("1|0");
        assertThat(f.browser().get("/api/v1/me/organizations").body()).contains(org);
        assertThat(value(licenses)).isEqualTo(before);
        conflict(f.browser().post(reactivate, ref), "organization_state_conflict");

        // ---- BLOQUEIO DA NEKKORA: a organizacao parada pela operacao (sem desativacao desta acao) nao e reativada pelo responsavel
        DB.asSuperuser("UPDATE organizations SET status = 3 WHERE organization_id = '" + org + "'");
        conflict(f.browser().post(reactivate, ref), "organization_blocked_by_nekkora");
        DB.asSuperuser("UPDATE organizations SET status = 2 WHERE organization_id = '" + org + "'");
        conflict(f.browser().post(reactivate, ref), "organization_blocked_by_nekkora");
        DB.asSuperuser("UPDATE organizations SET status = 1 WHERE organization_id = '" + org + "'");
        // desativada pelo responsavel E DEPOIS bloqueada pela Nekkora (registro do bloqueio): tambem nao
        assertThat(f.browser().post(deactivate, ref).status()).isEqualTo(204);
        DB.asSuperuser("INSERT INTO organization_nekkora_holds (organization_id, reason, registered_by) VALUES ('" + org
                + "', 'bloqueio de ensaio registrado pelo teste', 'procedimento de ensaio do teste')");
        conflict(f.browser().post(reactivate, ref), "organization_blocked_by_nekkora");
        assertThat(creation(f).get("responsibleFor").toString()).contains("\"state\":\"BLOCKED\"").contains("\"canReactivate\":false");
        assertThat(value("SELECT status FROM organizations WHERE organization_id = '" + org + "'")).isEqualTo("3");
        DB.asSuperuser("UPDATE organization_nekkora_holds SET lifted_at = clock_timestamp(), lifted_by = 'procedimento de ensaio do teste' WHERE organization_id = '"
                + org + "'");
        assertThat(f.browser().post(reactivate, ref).status()).as("bloqueio levantado pela Nekkora: a reativacao volta a valer").isEqualTo(204);

        // ---- o limite de vazias e reconferido NA REATIVACAO: desativar uma vazia abre lugar; reativa-la com o limite cheio e recusado
        String e1 = direct(f, "Vazia um");
        String e2 = direct(f, "Vazia dois");
        String e3 = direct(f, "Vazia tres");
        assertThat(e3).isNotNull();
        assertThat(summary(creation(f))).isEqualTo("ADMIN criar=false pedir=false vazias=3/3");
        assertThat(f.browser().post(deactivate, "{\"organizationId\":\"" + e1 + "\"}").status()).isEqualTo(204);
        assertThat(summary(creation(f))).as("a desativada nao conta").isEqualTo("ADMIN criar=true pedir=false vazias=2/3");
        assertThat(direct(f, "Vazia quatro")).isNotNull();
        conflict(f.browser().post(reactivate, "{\"organizationId\":\"" + e1 + "\"}"), "empty_organization_limit_reached");
        assertThat(value("SELECT status FROM organizations WHERE organization_id = '" + e1 + "'")).isEqualTo("3");
        assertThat(f.browser().post(deactivate, "{\"organizationId\":\"" + e2 + "\"}").status()).isEqualTo(204);
        assertThat(f.browser().post(reactivate, "{\"organizationId\":\"" + e1 + "\"}").status()).as("com lugar, reativa").isEqualTo(204);
    }

    // ------------------------------------------------------------------ D7

    /**
     * D7: com a chave ligada (procedimento administrativo), a organizacao criada dali em diante nasce no modelo por licenca,
     * com o ato 8 e quem autorizou a chave na trilha; as que ja existiam nao mudam. Desligada, tudo como antes.
     */
    @Test
    void comAChaveLigadaAOrganizacaoNovaNasceNoModeloPorLicenca() throws Exception {
        ComFator f = comFator();
        stepUp(f);
        String earlier = direct(f, "Antes da chave");
        DB.asSuperuser("UPDATE license_rollout SET new_organizations_licensed = true, updated_by = 'titular, ensaio do teste', updated_at = clock_timestamp()");
        try {
            String born = f.browser().post(CRIAR, "{\"name\":\"Depois da chave\"}").field("organizationId");
            assertThat(value("SELECT e.model || '|' || a.act || '|' || (a.operator_user_id IS NULL) || '|' || (a.detail->>'rolloutAuthorizedBy') FROM "
                    + "organization_license_enforcement e JOIN organization_license_acts a ON a.act_id = e.act_id WHERE e.organization_id = '" + born + "'"))
                    .isEqualTo("2|8|true|titular, ensaio do teste");
            assertThat(value("SELECT public.license_model('" + born + "')")).isEqualTo("2");
            assertThat(count("SELECT count(*) FROM organization_license_enforcement WHERE organization_id = '" + earlier + "'")).as("a existente nao muda").isZero();
            // sem licenca concedida, a organizacao nova nao tem direito a executar; registra maquina sem ativacao (066)
            BrowserClient inside = orgSession(f, born, false);
            JsonNode summary = JSON.readTree(inside.get("/api/v1/o/" + born + "/licenses/summary").body());
            assertThat(summary.get("model").asString() + "|" + summary.get("contracted").asLong()).isEqualTo("licensed|0");
        } finally {
            DB.asSuperuser("UPDATE license_rollout SET new_organizations_licensed = false, updated_by = 'teste (restaurado)', updated_at = clock_timestamp()");
        }
        String after = direct(f, "Chave desligada de novo");
        assertThat(count("SELECT count(*) FROM organization_license_enforcement WHERE organization_id = '" + after + "'")).isZero();
        // o ato 8 nao serve para ligar a exigencia depois: so a primeira linha da organizacao
        assertThat(superuserState("INSERT INTO organization_license_acts (act_id, organization_id, act, detail) VALUES ('"
                + "00000000-0000-4000-8000-000000000008', '" + earlier + "', 8, '{\"rolloutAuthorizedBy\":\"x\"}')",
                "INSERT INTO organization_license_enforcement (organization_id, model, act_id) VALUES ('" + earlier + "', 1, '00000000-0000-4000-8000-000000000008')"))
                .as("ato 8 com o modelo anterior").isEqualTo("23514");
    }

    /** Como superusuario, numa transacao: "OK" ou o SQLSTATE da primeira recusa (nada fica gravado na recusa). */
    private static String superuserState(String... sqls) {
        try (java.sql.Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            try (java.sql.Statement st = c.createStatement()) {
                for (String sql : sqls) {
                    st.execute(sql);
                }
                c.commit();
                return "OK";
            } catch (java.sql.SQLException e) {
                c.rollback();
                return e.getSQLState();
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String asPlatform(String sql) {
        try (java.sql.Connection c = DB.connectAs("nekkora_platform"); java.sql.Statement st = c.createStatement()) {
            st.execute(sql);
            return "OK";
        } catch (java.sql.SQLException e) {
            return e.getSQLState();
        }
    }

    private static String valueAsPlatform(String org, String sql) throws Exception {
        try (java.sql.Connection c = DB.connectAs("nekkora_platform")) {
            c.setAutoCommit(false);
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("SELECT set_config('nekkora.organization_id', '" + org + "', true)");
                try (java.sql.ResultSet rs = st.executeQuery(sql)) {
                    return rs.next() ? rs.getString(1) : null;
                }
            } finally {
                c.rollback();
            }
        }
    }
}
