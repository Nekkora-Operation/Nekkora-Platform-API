package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;
import com.nekkora.platform.mail.MailSender;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contexto da pessoa para o aplicativo desktop (AGENT-CLIENT-NAVEGACAO-001, contrato desktop-navigation/1), na Platform real:
 * papel nekkora_platform, PostgreSQL com a 039 e Redis reais. As PESSOAS, as organizacoes e os vinculos nascem pelas rotas
 * reais (cadastro, criacao de organizacao, convite aceito); o "aplicativo" e um cliente SEM Origin e SEM cookies, so com o
 * token Bearer do login desktop. Workspaces, automacoes, versoes, pacotes e papeis de workspace sao preparados no banco como
 * superusuario (DECLARADO: preparacao, nao evidencia), assim como as mudancas de estado dos ensaios de revogacao (papel
 * revogado, vinculo retirado, item arquivado): o teste demonstra a REACAO das rotas a elas. Cada recusa confere o status, o
 * codigo e o que ficou (ou nao ficou) gravado.
 */
class DesktopNavigationIT extends IdentityTestBase {

    private static final String AUTHORIZATIONS = "/api/v1/desktop/authorizations";
    private static final String AUTHORIZATION = "/api/v1/desktop/authorization";
    private static final String APPROVE = "/api/v1/desktop/authorization/approve";
    private static final String TOKEN = "/api/v1/desktop/token";
    private static final String SESSION = "/api/v1/desktop/session";
    private static final String ME = "/api/v1/desktop/me";
    private static final String ORGS = "/api/v1/desktop/organizations";
    private static final String IDENTITY = "identity";
    private static final String NAVIGATION = "identity navigation";
    private static final String PACKAGES = "identity navigation packages";
    private static final java.nio.file.Path STORE;

    static {
        try {
            STORE = java.nio.file.Files.createTempDirectory("nekkora-desktop-artefatos");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    com.nekkora.platform.artifact.ArtifactStore artifacts;
    private static final String EXTERNAL_RULE = "nekkora-agent/external-python-fingerprint/1";
    private static final String FORBIDDEN = "{\"error\":\"forbidden\"}";
    private static final String NOT_FOUND = "{\"error\":\"not_found\"}";
    private static final String INVALID = "{\"error\":\"invalid_request\"}";
    private static final String CONFLICT = "{\"error\":\"conflict\"}";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    StringRedisTemplate redis;

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.desktop-login.enabled", () -> "true");
        r.add("nekkora.criacao-organizacao.enabled", () -> "true");
        r.add("nekkora.convites.enabled", () -> "true");
        r.add("nekkora.artefatos.dir", STORE::toString);
    }

    // ------------------------------------------------------------------ pessoas, organizacoes e convites (rotas reais)

    /** Pessoa sem segundo fator (029), com a sessao de identidade aberta no navegador e o CSRF dela. */
    private record Pessoa(UUID identity, String email, BrowserClient browser, String idCsrf) {
    }

    private Pessoa pessoa() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        BrowserClient b = client();
        assertThat(login(b, e, SENHA).field("status")).isEqualTo("authenticated");
        return new Pessoa(id, e, b, b.csrf);
    }

    private void reconfirmar(Pessoa p) {
        p.browser().csrf = p.idCsrf();
        assertThat(p.browser().post("/api/v1/auth/password-confirm", "{\"password\":" + q(SENHA) + "}").status()).isEqualTo(204);
    }

    /** Organizacao criada pela propria pessoa (canal cadastro_web): ela nasce ORG_ADMIN. */
    private UUID criarOrganizacao(Pessoa p, String nome) {
        reconfirmar(p);
        BrowserClient.Response r = p.browser().post("/api/v1/organizations", "{\"name\":" + q(nome) + "}");
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return UUID.fromString(r.field("organizationId"));
    }

    /** Sessao de organizacao pela troca (cookie proprio no navegador da pessoa); devolve o CSRF dessa sessao. */
    private String sessaoDeOrganizacao(Pessoa p, UUID org) {
        p.browser().csrf = p.idCsrf();
        BrowserClient.Response troca = p.browser().post("/api/v1/sessions/organization", "{\"organizationId\":\"" + org + "\"}");
        assertThat(troca.field("status")).as(troca.body()).isEqualTo("sessao_criada");
        return troca.field("csrfToken");
    }

    /** Convite emitido pela administradora (sessao de organizacao) e aceito pela convidada (sessao de identidade). */
    private void convidarEAceitar(Pessoa admin, String orgCsrf, UUID org, Pessoa convidada, String role, UUID workspace) {
        admin.browser().csrf = orgCsrf;
        // convite e acao sensivel: a senha e reconfirmada NA sessao de organizacao a cada convite (a janela e curta)
        assertThat(admin.browser().post("/api/v1/o/" + org + "/auth/password-confirm", "{\"password\":" + q(SENHA) + "}").status()).isEqualTo(204);
        BrowserClient.Response c = admin.browser().post("/api/v1/o/" + org + "/invitations", "{\"email\":" + q(convidada.email())
                + (role == null ? "" : ",\"role\":" + q(role)) + (workspace == null ? "" : ",\"workspaceId\":\"" + workspace + "\"") + "}");
        admin.browser().csrf = admin.idCsrf();
        assertThat(c.status()).as(c.body()).isEqualTo(201);
        List<SentMessage> m = mails(convidada.email()).stream().filter(x -> x.kind() == MailSender.Kind.ORG_INVITATION).toList();
        assertThat(m).as("convite enviado").isNotEmpty();
        reconfirmar(convidada);
        BrowserClient.Response a = convidada.browser().post("/api/v1/invitations/accept",
                "{\"organizationId\":\"" + org + "\",\"token\":" + q(m.get(m.size() - 1).token()) + "}");
        assertThat(a.status()).as(a.body()).isEqualTo(200);
    }

    private static UUID contaDe(Pessoa p, UUID org) throws Exception {
        return UUID.fromString(value("SELECT iu.user_id FROM identity_users iu JOIN users u ON u.user_id = iu.user_id WHERE iu.identity_id = '"
                + p.identity() + "' AND u.organization_id = '" + org + "'"));
    }

    private static UUID vinculoDe(Pessoa p, UUID org) throws Exception {
        return AccessFixtures.membershipOf(org, contaDe(p, org));
    }

    // ------------------------------------------------------------------ login do aplicativo e chamadas com o token

    private static String param(String url, String name) {
        Matcher m = Pattern.compile("[?&]" + name + "=([^&]*)").matcher(url);
        return m.find() ? java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8) : null;
    }

    /** Aplicativo nativo: sem Origin, sem cookies, sem CSRF; um IP por requisicao (fora do limitador por IP). */
    private BrowserClient app() {
        BrowserClient a = browser();
        a.origin = null;
        return a;
    }

    /** Fluxo inteiro do login desktop com o escopo pedido; devolve a resposta da troca. */
    private BrowserClient.Response trocar(Pessoa p, String scope) {
        String state = Tokens.newToken();
        String verifier = Tokens.newToken();
        String redirect = "http://127.0.0.1:" + (20000 + (int) (Math.random() * 20000)) + "/callback";
        BrowserClient b = p.browser();
        fetchPreAuthCsrf(b);
        BrowserClient.Response st = b.post(AUTHORIZATIONS, "{\"responseType\":\"code\",\"clientId\":\"nekkora-agent-client\",\"redirectUri\":\"" + redirect
                + "\",\"state\":\"" + state + "\",\"codeChallenge\":\"" + DesktopLoginIT.s256(verifier) + "\",\"codeChallengeMethod\":\"S256\",\"scope\":"
                + q(scope) + "}");
        b.csrf = p.idCsrf();
        assertThat(st.status()).as(st.body()).isEqualTo(204);
        BrowserClient.Response pe = b.get(AUTHORIZATION);
        assertThat(pe.status()).isEqualTo(200);
        assertThat(pe.field("scope")).as("a pagina de confirmacao recebe o escopo pedido").isEqualTo(scope);
        assertThat(keys(json(pe))).containsExactly("application", "account", "scope");
        BrowserClient.Response ap = b.post(APPROVE, null);
        assertThat(ap.status()).as(ap.body()).isEqualTo(200);
        String form = "grant_type=authorization_code&code=" + param(ap.field("redirectTo"), "code") + "&redirect_uri="
                + URLEncoder.encode(redirect, StandardCharsets.UTF_8) + "&client_id=nekkora-agent-client&code_verifier=" + verifier;
        BrowserClient.Response t = app().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + TOKEN))
                .POST(HttpRequest.BodyPublishers.ofString(form)).header("Content-Type", "application/x-www-form-urlencoded"));
        assertThat(t.status()).as(t.body()).isEqualTo(200);
        return t;
    }

    private String entrar(Pessoa p) {
        BrowserClient.Response t = trocar(p, NAVIGATION);
        assertThat(t.field("scope")).isEqualTo(NAVIGATION);
        return t.field("access_token");
    }

    private BrowserClient.Response call(BrowserClient a, String method, String path, String authorization, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        b = switch (method) {
            case "GET" -> b.GET();
            default -> b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json");
        };
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return a.send(b);
    }

    private BrowserClient.Response get(String path, String token) {
        return call(app(), "GET", path, token == null ? null : "Bearer " + token, null);
    }

    private BrowserClient.Response rename(UUID org, UUID automation, String token, String body) {
        return call(app(), "PATCH", ORGS + "/" + org + "/automations/" + automation, token == null ? null : "Bearer " + token, body);
    }

    private static String nome(String name) {
        return "{\"name\":" + q(name) + "}";
    }

    private static JsonNode json(BrowserClient.Response r) {
        return JSON.readTree(r.body());
    }

    private static List<String> keys(JsonNode n) {
        return n.properties().stream().map(Map.Entry::getKey).toList();
    }

    private static List<String> field(Iterable<JsonNode> items, String name) {
        List<String> out = new ArrayList<>();
        items.forEach(x -> out.add(x.get(name).isNull() ? null : x.get(name).isNumber() ? String.valueOf(x.get(name).asLong()) : x.get(name).asString()));
        return out;
    }

    /** Lista de organizacoes vazia no contrato desktop-navigation/2. */
    private static final String VAZIO = "{\"items\":[],\"asOf\":\"request\"}";

    private static void assertErro(BrowserClient.Response r, int status, String body, String oQue) {
        assertThat(r.status()).as(oQue + ": " + r.body()).isEqualTo(status);
        assertThat(r.body()).as(oQue).isEqualTo(body);
    }

    private static String ws(UUID org) {
        return ORGS + "/" + org + "/workspaces";
    }

    private static String autos(UUID org, UUID workspace) {
        return ws(org) + "/" + workspace + "/automations";
    }

    private static String versoes(UUID org, UUID automation) {
        return ORGS + "/" + org + "/automations/" + automation + "/versions";
    }

    /** Todas as paginas de uma lista; nenhuma intermediaria incompleta; a ultima com nextCursor nulo. */
    private List<JsonNode> paginas(String path, String token, int limit) {
        List<JsonNode> items = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 100; page++) {
            BrowserClient.Response r = get(path + "?limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor), token);
            assertThat(r.status()).as("pagina " + page + ": " + r.body()).isEqualTo(200);
            assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            JsonNode b = json(r);
            b.get("items").forEach(items::add);
            if (b.get("nextCursor").isNull()) {
                return items;
            }
            assertThat(b.get("items").size()).as("pagina intermediaria cheia").isEqualTo(limit);
            cursor = b.get("nextCursor").asString();
        }
        throw new AssertionError("paginacao nao terminou");
    }

    // ------------------------------------------------------------------ massa do produto (superusuario, declarada)

    private static String hex64() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    private record Versao(UUID id, String fingerprint) {
    }

    /** Versao com pacote recebido (artefato externo, como a Platform grava): rascunho e, se pedido, publicada. */
    private static Versao versaoComPacote(UUID org, UUID workspace, UUID automation, UUID by, boolean publish, String pacote, String versao) {
        return versaoComPacote(org, workspace, automation, by, publish, pacote, versao, hex64(), 1234);
    }

    private static Versao versaoComPacote(UUID org, UUID workspace, UUID automation, UUID by, boolean publish, String pacote, String versao,
                                          String sha, long tamanho) {
        UUID v = UUID.randomUUID();
        UUID artefato = UUID.randomUUID();
        String fp = hex64();
        DB.asSuperuser("INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, "
                        + "created_by_membership_id) VALUES ('" + v + "', '" + org + "', '" + workspace + "', '" + automation + "', 1, "
                        + "jsonb_build_object('contract_version',1,'artifact',jsonb_build_object('reference','nekkora-artifact:" + artefato + "','sha256','" + sha
                        + "'),'agent_package',jsonb_build_object('fingerprint_rule','" + EXTERNAL_RULE + "','fingerprint','" + fp + "'),'runtime','python3.12',"
                        + "'entrypoint','main.py','arguments','[]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb), '" + by + "')",
                "INSERT INTO automation_artifacts (artifact_id, organization_id, workspace_id, automation_id, automation_version_id, sha256, size_bytes, "
                        + "package_kind, fingerprint_rule, fingerprint, files_count, manifest_version, python_requirement, dependencies, package_name, "
                        + "package_version, storage_key, received_by_membership_id) VALUES ('" + artefato + "', '" + org + "', '" + workspace + "', '" + automation
                        + "', '" + v + "', '" + sha + "', " + tamanho + ", 2, '" + EXTERNAL_RULE + "', '" + fp + "', 3, '1.0.0', '>=3.10', '[\"requests==2.31.0\"]'::jsonb, '"
                        + pacote + "', '" + versao + "', '" + sha + "', '" + by + "')");
        if (publish) {
            DB.asSuperuser("UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '" + by
                    + "' WHERE automation_version_id = '" + v + "'");
        }
        return new Versao(v, fp);
    }

    private static String ordemNoBanco(String tabela, String id, String filtro) throws Exception {
        return value("SELECT string_agg(" + id + "::text, ',' ORDER BY left(name, 48) COLLATE \"C\", " + id + ") FROM " + tabela + " WHERE " + filtro);
    }

    /** Impressao de todas as linhas de versoes e artefatos da automacao (para provar que renomear nao as toca). */
    private static String versoesEArtefatos(UUID automation) throws Exception {
        return value("SELECT md5(coalesce((SELECT string_agg(v::text, '|' ORDER BY v.automation_version_id) FROM automation_versions v WHERE v.automation_id = '"
                + automation + "'), '')) || '/' || md5(coalesce((SELECT string_agg(x::text, '|' ORDER BY x.artifact_id) FROM automation_artifacts x"
                + " WHERE x.automation_id = '" + automation + "'), '')) || '/' || (SELECT count(*) FROM automation_versions v WHERE v.automation_id = '"
                + automation + "') || '/' || (SELECT count(*) FROM automation_artifacts x WHERE x.automation_id = '" + automation + "')");
    }

    private static String nomeNoBanco(UUID automation) throws Exception {
        return value("SELECT name FROM automations WHERE automation_id = '" + automation + "'");
    }

    private static long renomeacoes(UUID automation) throws Exception {
        return count("SELECT count(*) FROM automation_admin_events WHERE automation_id = '" + automation + "' AND act = 8");
    }

    // ------------------------------------------------------------------ cenario comum

    /**
     * Ana criou a organizacao A; Bia criou a organizacao B COM O MESMO NOME e convidou Ana como VIEWER de um workspace; Caio
     * nao esta em organizacao nenhuma; Dora criou uma organizacao sem workspace. Montado uma vez; os testes que mudam estado
     * usam pessoas e itens proprios.
     */
    private record Cenario(Pessoa ana, Pessoa bia, Pessoa caio, Pessoa dora, UUID orgA, UUID orgB, UUID orgD, String bCsrf, UUID anaEmA, UUID biaEmB,
                           UUID aAlfa, UUID aVazio, UUID aArquivado, List<UUID> aAtivos, UUID comPacote, UUID semPacote, UUID semVersao, UUID velha,
                           List<Versao> versoesComPacote, UUID bCompartilhado, UUID bReservado, UUID daBia, UUID secreta) {
    }

    private static Cenario cenario;

    private Cenario cenario() throws Exception {
        if (cenario != null) {
            return cenario;
        }
        Pessoa ana = pessoa();
        Pessoa bia = pessoa();
        Pessoa caio = pessoa();
        Pessoa dora = pessoa();
        UUID orgA = criarOrganizacao(ana, "Mesma Organizacao");
        UUID orgB = criarOrganizacao(bia, "Mesma Organizacao");
        UUID orgD = criarOrganizacao(dora, "Organizacao sem workspace");
        UUID anaEmA = vinculoDe(ana, orgA);
        UUID biaEmB = vinculoDe(bia, orgB);

        // A: cinco workspaces ativos (nomes com caixas diferentes, para a ordem "C") e um arquivado
        UUID aAlfa = AccessFixtures.namedWorkspace(orgA, "Alfa", 1);
        UUID aVazio = AccessFixtures.namedWorkspace(orgA, "Vazio", 1);
        List<UUID> aAtivos = new ArrayList<>(List.of(aAlfa, aVazio, AccessFixtures.namedWorkspace(orgA, "beta", 1),
                AccessFixtures.namedWorkspace(orgA, "alfa 2", 1), AccessFixtures.namedWorkspace(orgA, "Zeta", 1)));
        UUID aArquivado = AccessFixtures.namedWorkspace(orgA, "Arquivado", 3);
        // em "Alfa": cinco automacoes ativas e uma arquivada
        UUID comPacote = ProductFixtures.automation(orgA, aAlfa, null, "Com pacote", anaEmA);
        UUID semPacote = ProductFixtures.automation(orgA, aAlfa, null, "robo sem pacote", anaEmA);
        UUID semVersao = ProductFixtures.automation(orgA, aAlfa, null, "Sem versao", anaEmA);
        ProductFixtures.automation(orgA, aAlfa, null, "Robo A", anaEmA);
        ProductFixtures.automation(orgA, aAlfa, null, "robo b", anaEmA);
        UUID velha = ProductFixtures.automation(orgA, aAlfa, null, "Velha", anaEmA);
        ProductFixtures.version(orgA, aAlfa, velha, true, anaEmA);
        exec("UPDATE automations SET status = 3 WHERE automation_id = '" + velha + "'");
        List<Versao> versoes = new ArrayList<>();
        versoes.add(versaoComPacote(orgA, aAlfa, comPacote, anaEmA, true, "relatorio-modular", "1.0.0"));
        versoes.add(versaoComPacote(orgA, aAlfa, comPacote, anaEmA, true, "relatorio-modular", "1.1.0"));
        versoes.add(new Versao(ProductFixtures.version(orgA, aAlfa, comPacote, true, anaEmA), null));
        versoes.add(versaoComPacote(orgA, aAlfa, comPacote, anaEmA, true, "relatorio-modular", "1.3.0"));
        versoes.add(versaoComPacote(orgA, aAlfa, comPacote, anaEmA, false, "relatorio-modular", "1.4.0"));
        ProductFixtures.version(orgA, aAlfa, semPacote, true, anaEmA);

        // B: um workspace que Ana ve (VIEWER pelo convite) e um que ela nao ve
        UUID bCompartilhado = AccessFixtures.namedWorkspace(orgB, "Compartilhado", 1);
        UUID bReservado = AccessFixtures.namedWorkspace(orgB, "Reservado", 1);
        UUID daBia = ProductFixtures.automation(orgB, bCompartilhado, null, "Da Bia", biaEmB);
        ProductFixtures.version(orgB, bCompartilhado, daBia, true, biaEmB);
        UUID secreta = ProductFixtures.automation(orgB, bReservado, null, "Secreta", biaEmB);
        versaoComPacote(orgB, bReservado, secreta, biaEmB, true, "pacote-secreto", "9.9.9");
        String bCsrf = sessaoDeOrganizacao(bia, orgB);
        convidarEAceitar(bia, bCsrf, orgB, ana, "VIEWER", bCompartilhado);

        cenario = new Cenario(ana, bia, caio, dora, orgA, orgB, orgD, bCsrf, anaEmA, biaEmB, aAlfa, aVazio, aArquivado, List.copyOf(aAtivos), comPacote,
                semPacote, semVersao, velha, List.copyOf(versoes), bCompartilhado, bReservado, daBia, secreta);
        return cenario;
    }

    // ------------------------------------------------------------------ pessoa e organizacoes

    /** Duas organizacoes com o MESMO nome (uma por criacao, outra por convite): as duas aparecem, distinguiveis pelo id e pelo identificador. */
    @Test
    void pessoaEOrganizacoesComOMesmoNome() throws Exception {
        Cenario c = cenario();
        String ana = entrar(c.ana());

        BrowserClient.Response me = get(ME, ana);
        assertThat(me.status()).as(me.body()).isEqualTo(200);
        assertThat(me.body()).isEqualTo("{\"email\":\"" + c.ana().email() + "\",\"displayName\":\"Pessoa\"}");
        assertThat(me.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(me.setCookies()).as("rota do aplicativo nao emite cookie").isEmpty();

        BrowserClient.Response r = get(ORGS, ana);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        JsonNode b = json(r);
        assertThat(keys(b)).containsExactly("items", "asOf");
        assertThat(b.get("asOf").asString()).isEqualTo("request");
        List<String> esperado = c.orgA().toString().compareTo(c.orgB().toString()) < 0 ? List.of(c.orgA().toString(), c.orgB().toString())
                : List.of(c.orgB().toString(), c.orgA().toString());
        assertThat(field(b.get("items"), "organizationId")).as("mesmo nome: desempate pelo id").isEqualTo(esperado);
        assertThat(field(b.get("items"), "name")).containsExactly("Mesma Organizacao", "Mesma Organizacao");
        b.get("items").forEach(i -> {
            assertThat(keys(i)).containsExactly("organizationId", "name", "handle", "activationState");
            assertThat(i.get("activationState").asString()).isEqualTo("AGUARDANDO_ATIVACAO");
        });
        assertThat(field(b.get("items"), "handle")).as("o identificador distingue as duas").doesNotHaveDuplicates().doesNotContainNull();
        assertThat(r.body()).as("nenhum papel, conta, vinculo ou e-mail de outra pessoa").doesNotContain("role").doesNotContain("userId")
                .doesNotContain("membership").doesNotContain(c.bia().email());

        // a mesma ordem da lista da Web (identity_organizations: nome, id)
        List<String> web = new ArrayList<>();
        JSON.readTree(c.ana().browser().get("/api/v1/me/organizations").body()).get("organizations").forEach(o -> web.add(o.get("organizationId").asString()));
        assertThat(field(b.get("items"), "organizationId")).isEqualTo(web);

        // Bia so esta na B; Dora so na dela
        assertThat(field(json(get(ORGS, entrar(c.bia()))).get("items"), "organizationId")).containsExactly(c.orgB().toString());
        assertThat(field(json(get(ORGS, entrar(c.dora()))).get("items"), "organizationId")).containsExactly(c.orgD().toString());
    }

    /** Vazio legitimo e 200 com lista vazia, nunca erro: pessoa sem organizacao, organizacao sem workspace, workspace sem automacao. */
    @Test
    void listasLegitimamenteVaziasSao200() throws Exception {
        Cenario c = cenario();
        BrowserClient.Response semOrg = get(ORGS, entrar(c.caio()));
        assertThat(semOrg.status()).isEqualTo(200);
        assertThat(semOrg.body()).isEqualTo(VAZIO);

        BrowserClient.Response semWs = get(ws(c.orgD()), entrar(c.dora()));
        assertThat(semWs.status()).as(semWs.body()).isEqualTo(200);
        assertThat(semWs.body()).isEqualTo("{\"items\":[],\"nextCursor\":null}");

        String ana = entrar(c.ana());
        BrowserClient.Response semAuto = get(autos(c.orgA(), c.aVazio()), ana);
        assertThat(semAuto.status()).as(semAuto.body()).isEqualTo(200);
        assertThat(semAuto.body()).isEqualTo("{\"items\":[],\"nextCursor\":null,\"capabilities\":{\"rename\":true,\"execute\":true,\"download\":false}}");

        // automacao sem versao nenhuma: lista de versoes vazia (a automacao existe e e visivel)
        BrowserClient.Response semVersao = get(versoes(c.orgA(), c.semVersao()), ana);
        assertThat(semVersao.status()).as(semVersao.body()).isEqualTo(200);
        assertThat(semVersao.body()).isEqualTo("{\"items\":[],\"nextCursor\":null}");

        // o contrario do vazio: o que nao existe ou nao e da pessoa e 404, nao lista vazia
        assertErro(get(ws(c.orgD()), ana), 404, NOT_FOUND, "organizacao de outra pessoa");
        assertErro(get(autos(c.orgA(), UUID.randomUUID()), ana), 404, NOT_FOUND, "workspace inexistente");
        assertErro(get(versoes(c.orgA(), UUID.randomUUID()), ana), 404, NOT_FOUND, "automacao inexistente");
    }

    // ------------------------------------------------------------------ ordem e paginacao

    @Test
    void workspacesAtivosOrdenadosEPaginados() throws Exception {
        Cenario c = cenario();
        String ana = entrar(c.ana());
        String ordem = ordemNoBanco("workspaces", "workspace_id", "organization_id = '" + c.orgA() + "' AND status = 1");
        assertThat(ordem.split(",")).hasSize(5);

        for (int limit : new int[] {1, 2, 5, 100}) {
            List<JsonNode> itens = paginas(ws(c.orgA()), ana, limit);
            assertThat(String.join(",", field(itens, "workspaceId"))).as("limit=" + limit + ": ordem estavel (nome em C, id)").isEqualTo(ordem);
        }
        List<JsonNode> itens = paginas(ws(c.orgA()), ana, 2);
        assertThat(field(itens, "name")).containsExactly("Alfa", "Vazio", "Zeta", "alfa 2", "beta");
        assertThat(field(itens, "workspaceId")).as("arquivado nao aparece").doesNotContain(c.aArquivado().toString());
        itens.forEach(i -> assertThat(keys(i)).containsExactly("workspaceId", "name"));

        BrowserClient.Response padrao = get(ws(c.orgA()), ana);
        assertThat(keys(json(padrao))).containsExactly("items", "nextCursor");
        assertThat(json(padrao).get("items").size()).isEqualTo(5);
        assertThat(json(padrao).get("nextCursor").isNull()).isTrue();

        // na B, Ana (VIEWER de um workspace) so ve esse; Bia (ORG_ADMIN) ve os dois
        assertThat(field(paginas(ws(c.orgB()), ana, 1), "workspaceId")).containsExactly(c.bCompartilhado().toString());
        assertThat(field(paginas(ws(c.orgB()), entrar(c.bia()), 1), "name")).containsExactly("Compartilhado", "Reservado");

        for (String ruim : List.of("limit=0", "limit=101", "limit=-1", "limit=abc", "cursor=", "cursor=abc", "cursor=a.b")) {
            assertErro(get(ws(c.orgA()) + "?" + ruim, ana), 400, INVALID, ruim);
        }
    }

    @Test
    void automacoesAtivasComPacoteCapacidadesEPaginacao() throws Exception {
        Cenario c = cenario();
        String ana = entrar(c.ana());
        String path = autos(c.orgA(), c.aAlfa());
        String ordem = ordemNoBanco("automations", "automation_id", "workspace_id = '" + c.aAlfa() + "' AND status = 1");
        assertThat(ordem.split(",")).hasSize(5);
        for (int limit : new int[] {1, 2, 3, 100}) {
            assertThat(String.join(",", field(paginas(path, ana, limit), "automationId"))).as("limit=" + limit).isEqualTo(ordem);
        }
        List<JsonNode> itens = paginas(path, ana, 2);
        assertThat(field(itens, "name")).containsExactly("Com pacote", "Robo A", "Sem versao", "robo b", "robo sem pacote");
        assertThat(field(itens, "automationId")).as("arquivada nao aparece").doesNotContain(c.velha().toString());
        itens.forEach(i -> assertThat(keys(i)).containsExactly("automationId", "name", "latestPublishedVersion"));

        // ultima PUBLICADA (a 4; a 5 e rascunho), com o pacote do artefato dela
        JsonNode comPacote = itens.get(0).get("latestPublishedVersion");
        Versao v4 = c.versoesComPacote().get(3);
        assertThat(keys(comPacote)).containsExactly("automationVersionId", "versionNumber", "publishedAt", "package");
        assertThat(comPacote.get("automationVersionId").asString()).isEqualTo(v4.id().toString());
        assertThat(comPacote.get("versionNumber").asInt()).isEqualTo(4);
        assertThat(comPacote.get("publishedAt").asString()).isNotBlank();
        assertThat(comPacote.get("package").toString())
                .startsWith("{\"name\":\"relatorio-modular\",\"version\":\"1.3.0\",\"fingerprint\":\"" + v4.fingerprint() + "\",\"sha256\":\"")
                .endsWith("\",\"sizeBytes\":1234}");
        assertThat(keys(comPacote.get("package"))).containsExactly("name", "version", "fingerprint", "sha256", "sizeBytes");
        // publicada sem artefato recebido: package nulo; sem versao publicada: latestPublishedVersion nulo
        assertThat(itens.get(4).get("latestPublishedVersion").get("package").isNull()).isTrue();
        assertThat(itens.get(2).get("latestPublishedVersion").isNull()).isTrue();

        BrowserClient.Response pagina = get(path + "?limit=2", ana);
        assertThat(keys(json(pagina))).containsExactly("items", "nextCursor", "capabilities");
        assertThat(json(pagina).get("capabilities").toString()).as("ORG_ADMIN").isEqualTo("{\"rename\":true,\"execute\":true,\"download\":false}");
        assertThat(pagina.body()).as("nada de conteudo, comando, criador ou segredo").doesNotContain("command")
                .doesNotContain("storage").doesNotContain("entrypoint").doesNotContain("membership").doesNotContain("dependencies");

        // VIEWER na B: ve, nao renomeia nem executa
        BrowserClient.Response naB = get(autos(c.orgB(), c.bCompartilhado()), ana);
        assertThat(naB.status()).as(naB.body()).isEqualTo(200);
        assertThat(field(json(naB).get("items"), "automationId")).containsExactly(c.daBia().toString());
        assertThat(json(naB).get("capabilities").toString()).as("VIEWER").isEqualTo("{\"rename\":false,\"execute\":false,\"download\":false}");
    }

    @Test
    void versoesEmOrdemDecrescenteComPacoteEPaginacao() throws Exception {
        Cenario c = cenario();
        String ana = entrar(c.ana());
        String path = versoes(c.orgA(), c.comPacote());
        for (int limit : new int[] {1, 2, 4, 5, 100}) {
            assertThat(field(paginas(path, ana, limit), "versionNumber")).as("limit=" + limit).containsExactly("5", "4", "3", "2", "1");
        }
        List<JsonNode> itens = paginas(path, ana, 2);
        itens.forEach(i -> assertThat(keys(i)).containsExactly("automationVersionId", "versionNumber", "status", "publishedAt", "package"));
        assertThat(field(itens, "status")).containsExactly("draft", "published", "published", "published", "published");
        assertThat(itens.get(0).get("publishedAt").isNull()).as("rascunho").isTrue();
        assertThat(itens.get(1).get("publishedAt").asString()).isNotBlank();
        assertThat(itens.get(0).get("package").toString()).as("o rascunho mostra o pacote que tem")
                .isEqualTo("{\"name\":\"relatorio-modular\",\"version\":\"1.4.0\",\"fingerprint\":\"" + c.versoesComPacote().get(4).fingerprint() + "\",\"sha256\":\""
                        + value("SELECT sha256 FROM automation_artifacts WHERE automation_version_id = '" + c.versoesComPacote().get(4).id() + "'")
                        + "\",\"sizeBytes\":1234}");
        assertThat(itens.get(2).get("package").isNull()).as("versao 3: sem artefato recebido").isTrue();
        assertThat(itens.get(4).get("package").get("version").asString()).isEqualTo("1.0.0");
        for (int i = 0; i < 5; i++) {
            assertThat(itens.get(i).get("automationVersionId").asString()).isEqualTo(c.versoesComPacote().get(4 - i).id().toString());
        }
        BrowserClient.Response primeira = get(path + "?limit=2", ana);
        assertThat(keys(json(primeira))).containsExactly("items", "nextCursor");
        assertThat(primeira.body()).doesNotContain("command").doesNotContain("storage").doesNotContain("membership");

        // mesma visibilidade da rota da Web: automacao ARQUIVADA continua com as versoes legiveis
        assertThat(field(paginas(versoes(c.orgA(), c.velha()), ana, 5), "versionNumber")).containsExactly("1");
    }

    // ------------------------------------------------------------------ cursores de outro contexto

    /** O cursor so serve na rota, organizacao, vinculo e recurso pai que o emitiram: fora disso, 400 (nunca outra pagina). */
    @Test
    void cursorDeOutraOrganizacaoWorkspaceVinculoOuRotaE400() throws Exception {
        Cenario c = cenario();
        String ana = entrar(c.ana());
        String bia = entrar(c.bia());
        String wsA = json(get(ws(c.orgA()) + "?limit=1", ana)).get("nextCursor").asString();
        String wsBdaBia = json(get(ws(c.orgB()) + "?limit=1", bia)).get("nextCursor").asString();
        String autosAlfa = json(get(autos(c.orgA(), c.aAlfa()) + "?limit=1", ana)).get("nextCursor").asString();
        String versoesA = json(get(versoes(c.orgA(), c.comPacote()) + "?limit=1", ana)).get("nextCursor").asString();
        assertThat(List.of(wsA, wsBdaBia, autosAlfa, versoesA)).allSatisfy(x -> assertThat(x).isNotBlank());

        // controle positivo: cada cursor serve no proprio contexto
        assertThat(get(ws(c.orgA()) + "?limit=1&cursor=" + wsA, ana).status()).isEqualTo(200);
        assertThat(get(ws(c.orgB()) + "?limit=1&cursor=" + wsBdaBia, bia).status()).isEqualTo(200);

        assertErro(get(ws(c.orgB()) + "?limit=1&cursor=" + wsA, ana), 400, INVALID, "cursor de outra organizacao");
        assertErro(get(ws(c.orgB()) + "?limit=1&cursor=" + wsBdaBia, ana), 400, INVALID, "cursor de outro vinculo, mesma organizacao");
        assertErro(get(autos(c.orgA(), c.aAlfa()) + "?limit=1&cursor=" + wsA, ana), 400, INVALID, "cursor de workspaces na rota de automacoes");
        assertErro(get(autos(c.orgA(), c.aVazio()) + "?limit=1&cursor=" + autosAlfa, ana), 400, INVALID, "cursor de outro workspace");
        assertErro(get(versoes(c.orgA(), c.semPacote()) + "?limit=1&cursor=" + versoesA, ana), 400, INVALID, "cursor de outra automacao");
        assertErro(get(ws(c.orgA()) + "?limit=1&cursor=" + versoesA, ana), 400, INVALID, "cursor de versoes na rota de workspaces");
        assertErro(get(ws(c.orgA()) + "?limit=1&cursor=" + wsA.substring(0, wsA.length() - 2) + "AA", ana), 400, INVALID, "assinatura adulterada");

        // cursor da rota da WEB (mesma organizacao, mesmo vinculo, mesma consulta) nao serve na rota do aplicativo, nem o contrario
        sessaoDeOrganizacao(c.ana(), c.orgA());
        BrowserClient.Response web = c.ana().browser().get("/api/v1/o/" + c.orgA() + "/workspaces?status=active&limit=1");
        assertThat(web.status()).as(web.body()).isEqualTo(200);
        String daWeb = JSON.readTree(web.body()).get("nextCursor").asString();
        assertErro(get(ws(c.orgA()) + "?limit=1&cursor=" + daWeb, ana), 400, INVALID, "cursor da rota da Web");
        assertThat(c.ana().browser().get("/api/v1/o/" + c.orgA() + "/workspaces?status=active&limit=1&cursor=" + wsA).status())
                .as("cursor do aplicativo na rota da Web").isEqualTo(400);
    }

    // ------------------------------------------------------------------ fora do alcance

    /**
     * Ids trocados direto no caminho: organizacao alheia ou inexistente, workspace sem acesso, automacao de workspace sem
     * acesso, e itens de uma organizacao pedidos pelo caminho da outra. Sempre o mesmo 404, e a escrita sem efeito.
     */
    @Test
    void foraDoAlcanceE404IdenticoESemEfeito() throws Exception {
        Cenario c = cenario();
        String ana = entrar(c.ana());
        UUID inexistente = UUID.randomUUID();

        assertErro(get(ws(inexistente), ana), 404, NOT_FOUND, "organizacao inexistente");
        assertErro(get(ws(c.orgD()), ana), 404, NOT_FOUND, "organizacao da Dora");
        assertErro(get(autos(c.orgD(), c.aAlfa()), ana), 404, NOT_FOUND, "workspace da A pelo caminho da organizacao da Dora");
        assertErro(get(autos(c.orgB(), c.bReservado()), ana), 404, NOT_FOUND, "workspace da B sem papel");
        assertErro(get(autos(c.orgB(), c.aAlfa()), ana), 404, NOT_FOUND, "workspace da A pelo caminho da B");
        assertErro(get(autos(c.orgA(), c.bCompartilhado()), ana), 404, NOT_FOUND, "workspace da B pelo caminho da A");
        assertErro(get(versoes(c.orgB(), c.secreta()), ana), 404, NOT_FOUND, "automacao de workspace sem acesso");
        assertErro(get(versoes(c.orgB(), c.comPacote()), ana), 404, NOT_FOUND, "automacao da A pelo caminho da B");
        assertErro(get(versoes(c.orgA(), c.daBia()), ana), 404, NOT_FOUND, "automacao da B pelo caminho da A");
        assertThat(get(autos(c.orgB(), c.bReservado()), ana).body()).as("nada do workspace sem acesso vaza").doesNotContain("Secreta").doesNotContain("pacote-secreto");

        // escrita: mesmas trocas, 404 e nada muda (nome, auditoria, versoes)
        String antesSecreta = versoesEArtefatos(c.secreta());
        assertErro(rename(c.orgB(), c.secreta(), ana, nome("Tomada")), 404, NOT_FOUND, "renomear automacao de workspace sem acesso");
        assertErro(rename(c.orgB(), c.comPacote(), ana, nome("Tomada")), 404, NOT_FOUND, "renomear automacao da A pelo caminho da B");
        assertErro(rename(c.orgA(), c.daBia(), ana, nome("Tomada")), 404, NOT_FOUND, "renomear automacao da B pelo caminho da A");
        assertErro(rename(c.orgD(), c.comPacote(), ana, nome("Tomada")), 404, NOT_FOUND, "renomear pelo caminho de organizacao alheia");
        assertErro(rename(inexistente, c.comPacote(), ana, nome("Tomada")), 404, NOT_FOUND, "renomear em organizacao inexistente");
        assertErro(rename(c.orgA(), inexistente, ana, nome("Tomada")), 404, NOT_FOUND, "renomear automacao inexistente");
        assertThat(nomeNoBanco(c.secreta())).isEqualTo("Secreta");
        assertThat(nomeNoBanco(c.comPacote())).isEqualTo("Com pacote");
        assertThat(nomeNoBanco(c.daBia())).isEqualTo("Da Bia");
        assertThat(renomeacoes(c.secreta()) + renomeacoes(c.comPacote()) + renomeacoes(c.daBia())).as("nenhuma auditoria de renomeacao").isZero();
        assertThat(versoesEArtefatos(c.secreta())).isEqualTo(antesSecreta);

        // id malformado: 400, sem tocar nada
        assertErro(get(ORGS + "/nao-e-uuid/workspaces", ana), 400, INVALID, "organizacao malformada");
        assertErro(get(ws(c.orgA()) + "/nao-e-uuid/automations", ana), 400, INVALID, "workspace malformado");
    }

    // ------------------------------------------------------------------ renomear

    /**
     * Renomear exige automation.manage no workspace: VIEWER e OPERATOR veem e recebem 403 (nome intacto, sem auditoria);
     * WORKSPACE_ADMIN e ORG_ADMIN renomeiam. So automations.name muda: nenhuma linha de versao ou artefato e tocada. As
     * capacidades da lista acompanham o papel na requisicao seguinte, sem novo login.
     */
    @Test
    void renomearExigeAutomationManageENaoTocaVersoesNemArtefatos() throws Exception {
        Cenario c = cenario();
        Pessoa eva = pessoa();
        convidarEAceitar(c.bia(), c.bCsrf(), c.orgB(), eva, null, null);
        UUID evaEmB = vinculoDe(eva, c.orgB());
        UUID oficina = AccessFixtures.namedWorkspace(c.orgB(), "Oficina " + UUID.randomUUID().toString().substring(0, 6), 1);
        UUID auto = ProductFixtures.automation(c.orgB(), oficina, null, "Nome original", c.biaEmB());
        versaoComPacote(c.orgB(), oficina, auto, c.biaEmB(), true, "pacote-da-oficina", "2.0.0");
        versaoComPacote(c.orgB(), oficina, auto, c.biaEmB(), false, "pacote-da-oficina", "2.1.0");
        String intacto = versoesEArtefatos(auto);
        String eva1 = entrar(eva);

        // sem papel nenhum no workspace: nao ve, 404
        assertErro(get(autos(c.orgB(), oficina), eva1), 404, NOT_FOUND, "sem papel");
        assertErro(rename(c.orgB(), auto, eva1, nome("Sem papel")), 404, NOT_FOUND, "renomear sem papel");

        UUID viewer = AccessFixtures.assignWorkspaceRole(c.orgB(), evaEmB, oficina, "VIEWER", c.biaEmB());
        assertThat(json(get(autos(c.orgB(), oficina), eva1)).get("capabilities").toString()).isEqualTo("{\"rename\":false,\"execute\":false,\"download\":false}");
        assertErro(rename(c.orgB(), auto, eva1, nome("Pelo viewer")), 403, FORBIDDEN, "VIEWER");
        AccessFixtures.revokeWorkspaceAssignment(viewer, c.biaEmB());

        UUID operator = AccessFixtures.assignWorkspaceRole(c.orgB(), evaEmB, oficina, "OPERATOR", c.biaEmB());
        assertThat(json(get(autos(c.orgB(), oficina), eva1)).get("capabilities").toString()).isEqualTo("{\"rename\":false,\"execute\":true,\"download\":false}");
        assertErro(rename(c.orgB(), auto, eva1, nome("Pelo operator")), 403, FORBIDDEN, "OPERATOR");
        assertThat(nomeNoBanco(auto)).as("recusas nao renomeiam").isEqualTo("Nome original");
        assertThat(renomeacoes(auto)).isZero();
        assertThat(versoesEArtefatos(auto)).isEqualTo(intacto);
        AccessFixtures.revokeWorkspaceAssignment(operator, c.biaEmB());

        // WORKSPACE_ADMIN: o MESMO token (sem novo login) passa a poder
        UUID admin = AccessFixtures.assignWorkspaceRole(c.orgB(), evaEmB, oficina, "WORKSPACE_ADMIN", c.biaEmB());
        assertThat(json(get(autos(c.orgB(), oficina), eva1)).get("capabilities").toString()).isEqualTo("{\"rename\":true,\"execute\":true,\"download\":false}");

        // nome invalido: 400 pela regra da criacao, sem efeito
        String longo = "x".repeat(81);
        for (String ruim : new String[] {"{}", "{\"name\":null}", nome(""), nome("   "), nome(longo), "{\"name\":\"com\\u0007controle\"}", "{\"name\":", null}) {
            assertErro(rename(c.orgB(), auto, eva1, ruim), 400, INVALID, "corpo " + ruim);
        }
        assertThat(nomeNoBanco(auto)).isEqualTo("Nome original");
        assertThat(renomeacoes(auto)).isZero();

        String updatedAntes = value("SELECT updated_at::text FROM automations WHERE automation_id = '" + auto + "'");
        BrowserClient.Response ok = rename(c.orgB(), auto, eva1, nome("  Nome novo da Eva  "));
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        assertThat(ok.body()).as("nome sem os espacos das extremidades").isEqualTo("{\"automationId\":\"" + auto + "\",\"name\":\"Nome novo da Eva\"}");
        assertThat(ok.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(nomeNoBanco(auto)).isEqualTo("Nome novo da Eva");
        assertThat(value("SELECT updated_at::text FROM automations WHERE automation_id = '" + auto + "'")).isNotEqualTo(updatedAntes);
        assertThat(value("SELECT actor_membership_id || '|' || workspace_id || '|' || (automation_version_id IS NULL) || '|' || (artifact_id IS NULL)"
                + " FROM automation_admin_events WHERE automation_id = '" + auto + "' AND act = 8")).as("auditoria com o vinculo de quem fez")
                .isEqualTo(evaEmB + "|" + oficina + "|true|true");
        assertThat(versoesEArtefatos(auto)).as("nenhuma versao ou artefato tocado").isEqualTo(intacto);
        assertThat(value("SELECT status || '|' || workspace_id FROM automations WHERE automation_id = '" + auto + "'")).isEqualTo("1|" + oficina);
        assertThat(field(json(get(autos(c.orgB(), oficina), eva1)).get("items"), "name")).containsExactly("Nome novo da Eva");

        // o mesmo nome de novo: 200, sem nova auditoria; nome repetido no workspace e permitido (nao e unico na 005)
        assertThat(rename(c.orgB(), auto, eva1, nome("Nome novo da Eva")).status()).isEqualTo(200);
        assertThat(renomeacoes(auto)).isEqualTo(1);
        UUID gemea = ProductFixtures.automation(c.orgB(), oficina, null, "Gemea", c.biaEmB());
        assertThat(rename(c.orgB(), gemea, eva1, nome("Nome novo da Eva")).status()).isEqualTo(200);

        // ORG_ADMIN (Bia, por criacao) tambem renomeia
        BrowserClient.Response daBia = rename(c.orgB(), auto, entrar(c.bia()), nome("Nome da Bia"));
        assertThat(daBia.status()).as(daBia.body()).isEqualTo(200);
        assertThat(value("SELECT actor_membership_id FROM automation_admin_events WHERE automation_id = '" + auto
                + "' AND act = 8 ORDER BY created_at DESC LIMIT 1")).isEqualTo(c.biaEmB().toString());
        assertThat(renomeacoes(auto)).isEqualTo(2);

        // automacao arquivada, ou workspace arquivado: 409, como nos demais atos do ciclo de vida; nada muda
        exec("UPDATE automations SET status = 3 WHERE automation_id = '" + gemea + "'");
        assertErro(rename(c.orgB(), gemea, eva1, nome("Arquivada")), 409, CONFLICT, "automacao arquivada");
        exec("UPDATE workspaces SET status = 3 WHERE workspace_id = '" + oficina + "'");
        assertErro(rename(c.orgB(), auto, eva1, nome("Workspace arquivado")), 409, CONFLICT, "workspace arquivado");
        assertThat(nomeNoBanco(auto)).isEqualTo("Nome da Bia");
        assertThat(nomeNoBanco(gemea)).isEqualTo("Nome novo da Eva");
        assertThat(renomeacoes(auto)).isEqualTo(2);
        assertThat(versoesEArtefatos(auto)).isEqualTo(intacto);
        exec("UPDATE workspaces SET status = 1 WHERE workspace_id = '" + oficina + "'");

        // papel retirado com a "tela aberta": o mesmo token volta a nao poder (404: nem ve mais)
        AccessFixtures.revokeWorkspaceAssignment(admin, c.biaEmB());
        assertErro(rename(c.orgB(), auto, eva1, nome("Depois da revogacao")), 404, NOT_FOUND, "papel revogado");
        assertThat(nomeNoBanco(auto)).isEqualTo("Nome da Bia");
    }

    // ------------------------------------------------------------------ organizacoes atuais e revogacao com a tela aberta

    /**
     * Contrato desktop-navigation/2 (AGENT-CLIENT-NAVEGACAO-002): a lista de organizacoes e a de AGORA. Organizacao em que a
     * pessoa entrou depois do login (convite aceito ou criada por ela) aparece com o MESMO token, sem novo login, e sem
     * renovar a sessao. Dentro dela tudo e conferido a cada requisicao: papel revogado, vinculo retirado e sessao do
     * navegador encerrada valem na requisicao seguinte.
     */
    @Test
    void organizacaoNovaApareceSemNovoLoginERevogacaoValeNaRequisicaoSeguinte() throws Exception {
        Cenario c = cenario();
        Pessoa fabio = pessoa();
        String token = entrar(fabio);
        assertThat(get(ORGS, token).body()).isEqualTo(VAZIO);
        assertErro(get(ws(c.orgB()), token), 404, NOT_FOUND, "organizacao de que ainda nao participa");
        long prazoAntes = json(get("/api/v1/desktop/session", token)).get("expiresIn").asLong();

        // convite aceito DEPOIS do login do aplicativo: aparece na requisicao seguinte, com o mesmo token
        convidarEAceitar(c.bia(), c.bCsrf(), c.orgB(), fabio, "VIEWER", c.bCompartilhado());
        assertThat(field(json(get(ORGS, token)).get("items"), "organizationId")).as("convite aceito depois do login").containsExactly(c.orgB().toString());

        // organizacao criada pela propria pessoa depois do login: aparece, aguardando ativacao (nada e ativado)
        UUID propria = criarOrganizacao(fabio, "Mesma Organizacao");
        JsonNode lista = json(get(ORGS, token));
        assertThat(field(lista.get("items"), "organizationId")).containsExactlyInAnyOrder(c.orgB().toString(), propria.toString());
        lista.get("items").forEach(i -> assertThat(i.get("activationState").asString()).isEqualTo("AGUARDANDO_ATIVACAO"));
        assertThat(get(ws(propria), token).status()).as("a organizacao nova e navegavel").isEqualTo(200);
        // a mesma lista e a mesma ordem da Web, agora
        List<String> web = new ArrayList<>();
        JSON.readTree(fabio.browser().get("/api/v1/me/organizations").body()).get("organizations").forEach(o -> web.add(o.get("organizationId").asString()));
        assertThat(field(lista.get("items"), "organizationId")).isEqualTo(web);
        // homonimas de outras pessoas (A e a da Dora tem outro dono) nao vazam: nem na lista, nem pelo id
        assertThat(get(ORGS, token).body()).doesNotContain(c.orgA().toString()).doesNotContain(c.orgD().toString());
        assertErro(get(ws(c.orgA()), token), 404, NOT_FOUND, "organizacao homonima de outra pessoa");
        assertErro(get(ws(c.orgD()), token), 404, NOT_FOUND, "organizacao de outra pessoa");
        // quem nao entrou em nada continua sem nada: a leitura atual e por identidade
        assertThat(get(ORGS, entrar(pessoa())).body()).isEqualTo(VAZIO);
        // atualizar nao renova a sessao do aplicativo
        assertThat(json(get("/api/v1/desktop/session", token)).get("expiresIn").asLong()).as("prazo nao cresce").isLessThanOrEqualTo(prazoAntes);
        // a propria nao interessa ao resto do teste
        DB.asSuperuser("UPDATE organizations SET status = 2 WHERE organization_id = '" + propria + "'");
        assertThat(field(json(get(ORGS, token)).get("items"), "organizationId")).containsExactly(c.orgB().toString());

        assertThat(field(json(get(ORGS, token)).get("items"), "organizationId")).containsExactly(c.orgB().toString());
        assertThat(field(json(get(ws(c.orgB()), token)).get("items"), "workspaceId")).containsExactly(c.bCompartilhado().toString());
        assertThat(get(autos(c.orgB(), c.bCompartilhado()), token).status()).isEqualTo(200);
        assertThat(get(versoes(c.orgB(), c.daBia()), token).status()).isEqualTo(200);

        // papel do workspace revogado: o workspace some da lista (200) e o que estava aberto vira 404
        UUID fabioEmB = vinculoDe(fabio, c.orgB());
        String papel = value("SELECT assignment_id FROM workspace_role_assignments WHERE membership_id = '" + fabioEmB + "' AND status = 1");
        AccessFixtures.revokeWorkspaceAssignment(UUID.fromString(papel), c.biaEmB());
        assertThat(get(ws(c.orgB()), token).body()).isEqualTo("{\"items\":[],\"nextCursor\":null}");
        assertErro(get(autos(c.orgB(), c.bCompartilhado()), token), 404, NOT_FOUND, "workspace depois do papel revogado");
        assertErro(get(versoes(c.orgB(), c.daBia()), token), 404, NOT_FOUND, "versoes depois do papel revogado");
        assertThat(field(json(get(ORGS, token)).get("items"), "organizationId")).as("continua membro").containsExactly(c.orgB().toString());

        // papel de volta e depois o VINCULO retirado: a organizacao some da lista e tudo nela e 404
        AccessFixtures.assignWorkspaceRole(c.orgB(), fabioEmB, c.bCompartilhado(), "WORKSPACE_ADMIN", c.biaEmB());
        assertThat(json(get(autos(c.orgB(), c.bCompartilhado()), token)).get("capabilities").toString()).isEqualTo("{\"rename\":true,\"execute\":true,\"download\":false}");
        DB.asSuperuserIn(c.orgB(), "UPDATE organization_memberships SET status = 4 WHERE membership_id = '" + fabioEmB + "'");
        assertThat(get(ORGS, token).body()).as("vinculo retirado").isEqualTo(VAZIO);
        assertErro(get(ws(c.orgB()), token), 404, NOT_FOUND, "workspaces sem vinculo");
        assertErro(get(autos(c.orgB(), c.bCompartilhado()), token), 404, NOT_FOUND, "automacoes sem vinculo");
        assertErro(rename(c.orgB(), c.daBia(), token, nome("Sem vinculo")), 404, NOT_FOUND, "renomear sem vinculo");
        assertThat(nomeNoBanco(c.daBia())).isEqualTo("Da Bia");
        assertThat(renomeacoes(c.daBia())).isZero();
        assertThat(get(ME, token).status()).as("a pessoa continua autenticada").isEqualTo(200);

        // logout no navegador: 401 em tudo, com o mesmo token
        fabio.browser().csrf = fabio.idCsrf();
        assertThat(fabio.browser().post("/api/v1/auth/logout", null).status()).isEqualTo(204);
        for (String rota : List.of(ME, ORGS, ws(c.orgB()), autos(c.orgB(), c.bCompartilhado()), versoes(c.orgB(), c.daBia()))) {
            assertErro(get(rota, token), 401, UNAUTH, "depois do logout: " + rota);
        }
        assertErro(rename(c.orgB(), c.daBia(), token, nome("Depois do logout")), 401, UNAUTH, "renomear depois do logout");
    }

    /** Organizacao suspensa e conta suspensa depois do login: saem da lista e viram 404, sem novo login. */
    @Test
    void organizacaoOuContaSuspensaDepoisDoLoginSomeDaLista() throws Exception {
        Pessoa gil = pessoa();
        UUID org = criarOrganizacao(gil, "Org do Gil " + UUID.randomUUID().toString().substring(0, 6));
        AccessFixtures.namedWorkspace(org, "Unico", 1);
        String token = entrar(gil);
        assertThat(field(json(get(ORGS, token)).get("items"), "organizationId")).containsExactly(org.toString());
        assertThat(json(get(ws(org), token)).get("items").size()).isEqualTo(1);

        DB.asSuperuser("UPDATE organizations SET status = 2 WHERE organization_id = '" + org + "'");
        assertThat(get(ORGS, token).body()).isEqualTo(VAZIO);
        assertErro(get(ws(org), token), 404, NOT_FOUND, "organizacao suspensa");
        DB.asSuperuser("UPDATE organizations SET status = 1 WHERE organization_id = '" + org + "'");
        assertThat(json(get(ws(org), token)).get("items").size()).as("reativada: volta, com o mesmo token").isEqualTo(1);

        // sessao de origem revogada no banco (como a troca de senha faz): 401
        exec("UPDATE identity_sessions SET revoked_at = greatest(clock_timestamp(), created_at) WHERE identity_id = '" + gil.identity()
                + "' AND revoked_at IS NULL");
        assertErro(get(ws(org), token), 401, UNAUTH, "sessao de origem revogada");
        assertErro(get(ORGS, token), 401, UNAUTH, "sessao de origem revogada");
    }

    // ------------------------------------------------------------------ download do pacote (AGENT-CLIENT-PACOTES-001, contrato /3)

    private String entrarComPacotes(Pessoa p) {
        BrowserClient.Response t = trocar(p, PACKAGES);
        assertThat(t.field("scope")).isEqualTo(PACKAGES);
        return t.field("access_token");
    }

    private static String pacote(UUID org, UUID automation, UUID version) {
        return versoes(org, automation) + "/" + version + "/package";
    }

    private static long downloads(UUID version) throws Exception {
        return count("SELECT count(*) FROM automation_admin_events WHERE automation_version_id = '" + version + "' AND act = 9");
    }

    /** Versao publicada com bytes de verdade no armazenamento (texto ASCII: o cliente de teste le o corpo como texto). */
    private Versao versaoComBytes(UUID org, UUID workspace, UUID automation, UUID by, boolean publish, String versao, String conteudo) throws Exception {
        byte[] bytes = conteudo.getBytes(StandardCharsets.US_ASCII);
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        artifacts.put(org, sha, bytes);
        return versaoComPacote(org, workspace, automation, by, publish, "pacote-baixavel", versao, sha, bytes.length);
    }

    /**
     * Baixar o pacote de uma versao PUBLICADA: so com o escopo de pacotes (o de navegacao nao baixa) e so quem executa ou
     * gerencia no workspace (quem so le, nao). Ids trocados, rascunho, versao sem pacote e arquivo ausente tem cada um o seu
     * erro; cada download autorizado e auditado, sem token nem conteudo; nada mais muda.
     */
    @Test
    void baixarPacoteExigeEscopoPermissaoEVersaoPublicada() throws Exception {
        Cenario c = cenario();
        UUID ws = AccessFixtures.namedWorkspace(c.orgB(), "Downloads " + UUID.randomUUID().toString().substring(0, 6), 1);
        UUID auto = ProductFixtures.automation(c.orgB(), ws, null, "Baixavel", c.biaEmB());
        String conteudo = "PK-pacote-de-teste-" + UUID.randomUUID();
        // ordem: as publicadas primeiro; so pode haver um rascunho por automacao (032)
        Versao publicada = versaoComBytes(c.orgB(), ws, auto, c.biaEmB(), true, "1.0.0", conteudo);
        Versao semArquivo = versaoComPacote(c.orgB(), ws, auto, c.biaEmB(), true, "pacote-baixavel", "0.9.0");
        UUID semPacote = ProductFixtures.version(c.orgB(), ws, auto, true, c.biaEmB());
        Versao rascunho = versaoComBytes(c.orgB(), ws, auto, c.biaEmB(), false, "1.1.0", "PK-rascunho-" + UUID.randomUUID());
        String intacto = versoesEArtefatos(auto);

        // administradora (ORG_ADMIN) com o escopo de pacotes: baixa, com os cabecalhos do contrato
        String bia = entrarComPacotes(c.bia());
        BrowserClient.Response r = get(pacote(c.orgB(), auto, publicada.id()), bia);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).as("os bytes guardados, sem alteracao").isEqualTo(conteudo);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).isEqualTo("application/zip");
        assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        String sha = value("SELECT sha256 FROM automation_artifacts WHERE automation_version_id = '" + publicada.id() + "'");
        assertThat(r.headers().firstValue("X-Artifact-Sha256").orElse("")).isEqualTo(sha);
        assertThat(r.headers().firstValue("X-Package-Fingerprint").orElse("")).isEqualTo(publicada.fingerprint());
        assertThat(r.headers().firstValue("Content-Disposition").orElse("")).isEqualTo("attachment; filename=\"" + sha + ".zip\"");
        assertThat(r.headers().firstValue("Content-Length").orElse("")).isEqualTo(String.valueOf(conteudo.length()));
        assertThat(r.setCookies()).isEmpty();
        assertThat(r.headers().firstValue("Location")).as("download autenticado direto: sem redirecionamento").isEmpty();
        // auditado: quem, onde, o que; sem token nem conteudo
        assertThat(downloads(publicada.id())).isEqualTo(1);
        assertThat(value("SELECT actor_membership_id || '|' || workspace_id || '|' || automation_id || '|' || (artifact_id IS NOT NULL) FROM automation_admin_events"
                + " WHERE automation_version_id = '" + publicada.id() + "' AND act = 9"))
                .isEqualTo(c.biaEmB() + "|" + ws + "|" + auto + "|true");
        // a lista diz o tamanho e o sha256 do que sera baixado, e a capacidade de baixar
        JsonNode lista = json(get(versoes(c.orgB(), auto), bia));
        JsonNode item = null;
        for (JsonNode v : lista.get("items")) {
            if (v.get("automationVersionId").asString().equals(publicada.id().toString())) {
                item = v;
            }
        }
        assertThat(item.get("package").get("sha256").asString()).isEqualTo(sha);
        assertThat(item.get("package").get("sizeBytes").asLong()).isEqualTo(conteudo.length());
        assertThat(json(get(autos(c.orgB(), ws), bia)).get("capabilities").toString())
                .isEqualTo("{\"rename\":true,\"execute\":true,\"download\":true}");

        // o MESMO acesso com o escopo de navegacao (token anterior): 403, nada auditado; a capacidade vem falsa
        String biaNavegacao = entrar(c.bia());
        assertErro(get(pacote(c.orgB(), auto, publicada.id()), biaNavegacao), 403, FORBIDDEN, "escopo de navegacao nao baixa");
        assertThat(json(get(autos(c.orgB(), ws), biaNavegacao)).get("capabilities").get("download").asBoolean()).isFalse();
        assertErro(get(pacote(c.orgB(), auto, publicada.id()), trocar(c.bia(), IDENTITY).field("access_token")), 403, FORBIDDEN, "escopo identity");
        assertErro(call(app(), "GET", pacote(c.orgB(), auto, publicada.id()), null, null), 401, UNAUTH, "sem token");

        // quem so LE (VIEWER no workspace): ve a versao e os metadados, e NAO baixa - mesmo com o escopo de pacotes
        Pessoa leitora = pessoa();
        convidarEAceitar(c.bia(), c.bCsrf(), c.orgB(), leitora, "VIEWER", ws);
        String ana = entrarComPacotes(leitora);
        assertThat(get(versoes(c.orgB(), auto), ana).status()).as("ver metadados continua permitido").isEqualTo(200);
        assertThat(json(get(autos(c.orgB(), ws), ana)).get("capabilities").toString())
                .isEqualTo("{\"rename\":false,\"execute\":false,\"download\":false}");
        assertErro(get(pacote(c.orgB(), auto, publicada.id()), ana), 403, FORBIDDEN, "leitora nao baixa");

        // quem EXECUTA (OPERATOR) baixa; quem perde o papel deixa de baixar na requisicao seguinte
        Pessoa op = pessoa();
        convidarEAceitar(c.bia(), c.bCsrf(), c.orgB(), op, "OPERATOR", ws);
        String operador = entrarComPacotes(op);
        assertThat(json(get(autos(c.orgB(), ws), operador)).get("capabilities").toString())
                .isEqualTo("{\"rename\":false,\"execute\":true,\"download\":true}");
        assertThat(get(pacote(c.orgB(), auto, publicada.id()), operador).body()).isEqualTo(conteudo);
        assertThat(downloads(publicada.id())).isEqualTo(2);
        UUID opEmB = vinculoDe(op, c.orgB());
        String papel = value("SELECT assignment_id FROM workspace_role_assignments WHERE membership_id = '" + opEmB + "' AND status = 1");
        AccessFixtures.revokeWorkspaceAssignment(UUID.fromString(papel), c.biaEmB());
        assertErro(get(pacote(c.orgB(), auto, publicada.id()), operador), 404, NOT_FOUND, "papel revogado antes do pedido");

        // ids trocados: organizacao, automacao e versao que nao se pertencem
        assertErro(get(pacote(c.orgA(), auto, publicada.id()), bia), 404, NOT_FOUND, "organizacao de que nao participa");
        assertErro(get(pacote(c.orgB(), c.daBia(), publicada.id()), bia), 404, NOT_FOUND, "versao de OUTRA automacao da mesma organizacao");
        assertErro(get(pacote(c.orgB(), auto, c.versoesComPacote().get(0).id()), bia), 404, NOT_FOUND, "versao de outra organizacao");
        assertErro(get(pacote(c.orgB(), auto, UUID.randomUUID()), bia), 404, NOT_FOUND, "versao inexistente");
        assertErro(get(pacote(c.orgB(), UUID.randomUUID(), publicada.id()), bia), 404, NOT_FOUND, "automacao inexistente");
        assertErro(get(versoes(c.orgB(), auto) + "/nao-e-uuid/package", bia), 400, INVALID, "id malformado");
        // Ana (ORG_ADMIN da A) nao alcanca a automacao da B pelo caminho da A
        assertErro(get(pacote(c.orgA(), auto, publicada.id()), entrarComPacotes(c.ana())), 404, NOT_FOUND, "automacao da B pelo caminho da A");

        // rascunho, versao sem pacote e arquivo ausente: 409, sem bytes
        assertErro(get(pacote(c.orgB(), auto, rascunho.id()), bia), 409, CONFLICT, "rascunho nao e baixado");
        assertErro(get(pacote(c.orgB(), auto, semPacote), bia), 409, CONFLICT, "versao publicada sem pacote");
        assertErro(get(pacote(c.orgB(), auto, semArquivo.id()), bia), 409, CONFLICT, "arquivo ausente no armazenamento");
        // corrompido: os bytes guardados deixam de bater com a chave
        try (var files = java.nio.file.Files.walk(STORE)) {
            for (java.nio.file.Path f : files.filter(java.nio.file.Files::isRegularFile).filter(x -> x.getFileName().toString().startsWith(sha)).toList()) {
                java.nio.file.Files.writeString(f, "adulterado");
            }
        }
        assertErro(get(pacote(c.orgB(), auto, publicada.id()), bia), 409, CONFLICT, "arquivo corrompido no armazenamento");
        artifacts.put(c.orgB(), sha, conteudo.getBytes(StandardCharsets.US_ASCII));

        // automacao arquivada: 409; so download: nenhuma versao, artefato ou nome mudou
        assertThat(downloads(rascunho.id()) + downloads(semPacote)).as("recusa por rascunho ou sem pacote nao audita").isZero();
        assertThat(versoesEArtefatos(auto)).isEqualTo(intacto);
        exec("UPDATE automations SET status = 3 WHERE automation_id = '" + auto + "'");
        assertThat(get(pacote(c.orgB(), auto, publicada.id()), bia).status()).as("arquivada").isIn(404, 409);
    }

    // ------------------------------------------------------------------ escopo

    /** O escopo e o do pedido aprovado, com o texto exato; qualquer outro texto nao abre pedido. */
    @Test
    void escopoDoPedidoETextoExato() throws Exception {
        Pessoa p = pessoa();
        for (String ruim : List.of("navigation", "navigation identity", "identity  navigation", "identity navigation ", " identity navigation",
                "identity,navigation", "Identity Navigation", "identity navigation organizations", "identity organizations", "")) {
            BrowserClient b = client();
            BrowserClient.Response r = b.post(AUTHORIZATIONS, "{\"responseType\":\"code\",\"clientId\":\"nekkora-agent-client\",\"redirectUri\":"
                    + "\"http://127.0.0.1:40000/callback\",\"state\":\"" + Tokens.newToken() + "\",\"codeChallenge\":\"" + DesktopLoginIT.s256(Tokens.newToken())
                    + "\",\"codeChallengeMethod\":\"S256\",\"scope\":" + q(ruim) + "}");
            assertThat(r.status()).as("escopo '" + ruim + "'").isEqualTo(400);
            assertThat(cookie(r, "__Host-nekkora_desk")).as("sem cookie de pedido").isNull();
        }
        BrowserClient.Response t = trocar(p, NAVIGATION);
        assertThat(keys(json(t))).containsExactly("access_token", "token_type", "expires_in", "scope");
        assertThat(t.field("scope")).isEqualTo(NAVIGATION);
        BrowserClient.Response s = get(SESSION, t.field("access_token"));
        assertThat(keys(json(s))).containsExactly("account", "scope", "expiresIn");
        assertThat(s.field("scope")).isEqualTo(NAVIGATION);
        assertThat(s.field("email")).isEqualTo(p.email());
    }

    /**
     * Login com o escopo de sempre ({@code identity}): a sessao funciona exatamente como antes e NENHUMA rota nova abre
     * (403), mesmo para quem e ORG_ADMIN; a escrita nao tem efeito. O escopo nao se amplia depois: a mesma pessoa, com outro
     * login de navegacao em paralelo, nao muda o token antigo.
     */
    @Test
    void tokenDeEscopoIdentityNaoNavega() throws Exception {
        Cenario c = cenario();
        BrowserClient.Response t = trocar(c.ana(), IDENTITY);
        assertThat(t.field("scope")).isEqualTo(IDENTITY);
        assertThat(t.body()).contains("\"expires_in\":900").doesNotContain("refresh_token");
        String so = t.field("access_token");

        BrowserClient.Response s = get(SESSION, so);
        assertThat(s.status()).isEqualTo(200);
        assertThat(s.body()).as("a mesma resposta de antes, byte a byte (so o prazo varia)")
                .matches("\\{\"account\":\\{\"email\":\"" + Pattern.quote(c.ana().email()) + "\"\\},\"scope\":\"identity\",\"expiresIn\":\\d+\\}");

        String comNavegacao = entrar(c.ana());
        for (String rota : List.of(ME, ORGS, ws(c.orgA()), autos(c.orgA(), c.aAlfa()), versoes(c.orgA(), c.comPacote()), ws(UUID.randomUUID()))) {
            assertErro(get(rota, so), 403, FORBIDDEN, "escopo identity em " + rota);
            assertThat(get(rota, so).body()).doesNotContain("items");
        }
        assertErro(rename(c.orgA(), c.semVersao(), so, nome("Sem escopo")), 403, FORBIDDEN, "renomear com escopo identity");
        assertThat(nomeNoBanco(c.semVersao())).isEqualTo("Sem versao");
        assertThat(renomeacoes(c.semVersao())).isZero();
        assertThat(get(SESSION, so).status()).as("a recusa de escopo nao encerra a sessao").isEqualTo(200);
        assertThat(get(ME, comNavegacao).status()).as("o outro login, de navegacao, vale por si").isEqualTo(200);

        // encerrada, e 401 (nao 403)
        assertThat(call(app(), "POST", "/api/v1/desktop/session/revoke", "Bearer " + so, null).status()).isEqualTo(204);
        assertErro(get(ORGS, so), 401, UNAUTH, "sessao encerrada");
    }

    /** O retrato gravado com a sessao nao e autoridade: adulterado para a conta de outra pessoa, nada muda (contrato /2). */
    @Test
    void retratoDaSessaoNaoDecideOAcesso() throws Exception {
        Cenario c = cenario();
        String token = entrar(c.bia());
        String chave = "nk:desk:sess:" + Tokens.sha256Hex(token);
        String[] campos = redis.opsForValue().get(chave).split("\n", -1);
        campos[4] = c.orgD() + ":" + contaDe(c.dora(), c.orgD());
        redis.opsForValue().set(chave, String.join("\n", campos), java.time.Duration.ofSeconds(redis.getExpire(chave)));
        assertThat(field(json(get(ORGS, token)).get("items"), "organizationId")).containsExactly(c.orgB().toString());
        assertErro(get(ws(c.orgD()), token), 404, NOT_FOUND, "retrato adulterado");
        // e vazio: a organizacao da propria pessoa continua acessivel
        campos[4] = "";
        redis.opsForValue().set(chave, String.join("\n", campos), java.time.Duration.ofSeconds(redis.getExpire(chave)));
        assertThat(get(ws(c.orgB()), token).status()).isEqualTo(200);
    }

    /**
     * O que a sessao do desktop guarda no Redis: identidade, sessao de origem, emissao, escopo e os pares (organizacao, conta)
     * - so UUIDs; nunca o token do navegador nem o hash dele. E uma sessao gravada no formato ANTERIOR (tres campos, sem
     * escopo) e lida como {@code identity}: continua valendo em /session e nao abre nenhuma rota nova.
     */
    @Test
    void sessaoGuardaSoIdsESessaoDoFormatoAnteriorEIdentity() throws Exception {
        Cenario c = cenario();
        String token = entrar(c.ana());
        String chave = "nk:desk:sess:" + Tokens.sha256Hex(token);
        String valor = redis.opsForValue().get(chave);
        String uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        assertThat(valor).matches(uuid + "\n" + uuid + "\n\\d+\nidentity navigation\n" + uuid + ":" + uuid + "," + uuid + ":" + uuid);
        String[] campos = valor.split("\n", -1);
        assertThat(campos[0]).isEqualTo(c.ana().identity().toString());
        assertThat(campos[4]).as("as duas organizacoes da Ana, com a conta dela em cada uma")
                .contains(c.orgA() + ":" + contaDe(c.ana(), c.orgA())).contains(c.orgB() + ":" + contaDe(c.ana(), c.orgB()));
        String cookie = c.ana().browser().cookies().get("__Host-nekkora_id");
        assertThat(valor).as("o token do navegador nao e guardado").doesNotContain(cookie).doesNotContain(Tokens.sha256Hex(cookie));

        // login de escopo identity: nenhuma organizacao e resolvida nem guardada
        String so = trocar(c.ana(), IDENTITY).field("access_token");
        assertThat(redis.opsForValue().get("nk:desk:sess:" + Tokens.sha256Hex(so))).matches(uuid + "\n" + uuid + "\n\\d+\nidentity\n");

        // a mesma sessao, regravada como a versao anterior gravava (sem escopo e sem organizacoes), com o prazo que resta
        Long resta = redis.getExpire(chave);
        redis.opsForValue().set(chave, campos[0] + "\n" + campos[1] + "\n" + campos[2], java.time.Duration.ofSeconds(resta));
        BrowserClient.Response s = get(SESSION, token);
        assertThat(s.status()).as(s.body()).isEqualTo(200);
        assertThat(s.field("scope")).isEqualTo(IDENTITY);
        assertErro(get(ME, token), 403, FORBIDDEN, "sessao do formato anterior em /me");
        assertErro(get(ws(c.orgA()), token), 403, FORBIDDEN, "sessao do formato anterior em workspaces");
    }

    // ------------------------------------------------------------------ credenciais nao se misturam

    /**
     * O token do aplicativo, mesmo com o escopo de navegacao e de uma ORG_ADMIN, nao autentica NENHUMA rota fora de
     * /api/v1/desktop/: nem as leituras equivalentes da Web, nem as administrativas, nem como cookie.
     */
    @Test
    void tokenDeNavegacaoNaoAutenticaNenhumaRotaForaDoDesktop() throws Exception {
        Cenario c = cenario();
        String token = entrar(c.ana());
        String o = "/api/v1/o/" + c.orgA();
        for (String rota : List.of("/api/v1/me/session", "/api/v1/me/organizations", o + "/me", o + "/members", o + "/workers", o + "/automations",
                o + "/workspaces", o + "/workspaces/" + c.aAlfa(), o + "/workspaces/" + c.aAlfa() + "/automations", o + "/automations/" + c.comPacote(),
                o + "/automations/" + c.comPacote() + "/versions", o + "/executions", o + "/invitations", o + "/roles", o + "/agents", o + "/me/permissions",
                AUTHORIZATION)) {
            assertThat(get(rota, token).status()).as("GET " + rota).isEqualTo(401);
        }
        for (String rota : List.of("/api/v1/sessions/organization", "/api/v1/auth/password", "/api/v1/invitations/accept", o + "/workers", o + "/workspaces",
                o + "/invitations", o + "/workspaces/" + c.aAlfa() + "/automations", o + "/automations/" + c.comPacote() + "/versions", APPROVE)) {
            assertThat(call(app(), "POST", rota, "Bearer " + token, "{}").status()).as("POST " + rota).isIn(401, 403);
        }
        assertThat(call(app(), "PATCH", o + "/workspaces/" + c.aAlfa(), "Bearer " + token, nome("Pelo token do aplicativo")).status()).isIn(401, 403);
        assertThat(call(app(), "POST", "/api/v1/organizations", "Bearer " + token, nome("Outra")).status()).isIn(401, 403);
        assertThat(value("SELECT name FROM workspaces WHERE workspace_id = '" + c.aAlfa() + "'")).isEqualTo("Alfa");
        // o token como se fosse cookie de sessao (de identidade ou de organizacao) tambem nao vale
        BrowserClient comoCookie = browser();
        comoCookie.cookies().put("__Host-nekkora_id", token);
        comoCookie.cookies().put("__Host-nekkora_session", token);
        assertThat(comoCookie.get("/api/v1/me/session").status()).isEqualTo(401);
        assertThat(comoCookie.get(o + "/workspaces").status()).isEqualTo(401);
        // so as formas e metodos publicados existem sob /desktop/: o resto nao e alcancado pelo token
        assertThat(call(app(), "POST", ws(c.orgA()), "Bearer " + token, "{}").status()).as("POST numa rota so de leitura").isIn(401, 403, 405);
        assertThat(call(app(), "DELETE", ORGS + "/" + c.orgA() + "/automations/" + c.comPacote(), "Bearer " + token, null).status()).isIn(401, 403, 405);
        assertThat(get(ORGS + "/" + c.orgA(), token).status()).as("rota nao publicada").isIn(401, 404);
        assertThat(nomeNoBanco(c.comPacote())).isEqualTo("Com pacote");
    }

    /**
     * As rotas de navegacao so aceitam o token da sessao do desktop: sem token, token desconhecido, credencial de MAQUINA
     * (nka1...) e cookies do navegador (com ou sem CSRF) dao 401, e a escrita nao tem efeito. O contrario (token do aplicativo
     * no canal das maquinas, /agent/api/v1) e outro processo (nekkora-agent-gateway) e nao e exercitado neste modulo.
     */
    @Test
    void rotasDeNavegacaoSoAceitamOTokenDoAplicativo() throws Exception {
        Cenario c = cenario();
        String maquina = "nka1." + c.orgA() + "." + UUID.randomUUID() + "." + Tokens.newToken();
        List<String> rotas = List.of(ME, ORGS, ws(c.orgA()), autos(c.orgA(), c.aAlfa()), versoes(c.orgA(), c.comPacote()));
        for (String rota : rotas) {
            assertErro(get(rota, null), 401, UNAUTH, "sem token: " + rota);
            assertErro(get(rota, Tokens.newToken()), 401, UNAUTH, "token desconhecido: " + rota);
            assertErro(get(rota, maquina), 401, UNAUTH, "credencial de maquina: " + rota);
            assertErro(get(rota, "nka1" + Tokens.newToken().substring(4)), 401, UNAUTH, "prefixo de maquina com 43 caracteres: " + rota);
            assertErro(call(app(), "GET", rota, "Basic " + Tokens.newToken(), null), 401, UNAUTH, "outro esquema: " + rota);
            // cookies da Ana (sessao de identidade e, depois da troca, de organizacao), sem Bearer
            assertErro(c.ana().browser().get(rota), 401, UNAUTH, "so cookies do navegador: " + rota);
        }
        assertErro(rename(c.orgA(), c.semVersao(), null, nome("Sem token")), 401, UNAUTH, "renomear sem token");
        assertErro(rename(c.orgA(), c.semVersao(), maquina, nome("Maquina")), 401, UNAUTH, "renomear com credencial de maquina");
        // navegador com a sessao de identidade E o CSRF dela, na origem publica: continua sem valer (nenhum cookie e lido)
        c.ana().browser().csrf = c.ana().idCsrf();
        BrowserClient.Response peloNavegador = call(c.ana().browser(), "PATCH", ORGS + "/" + c.orgA() + "/automations/" + c.semVersao(), null, nome("Cookie"));
        assertErro(peloNavegador, 401, UNAUTH, "renomear so com cookies e CSRF");
        assertThat(nomeNoBanco(c.semVersao())).isEqualTo("Sem versao");
        assertThat(renomeacoes(c.semVersao())).isZero();
        // e com o token certo, cookies de OUTRA pessoa junto nao mudam quem e a pessoa: vale o token
        String ana = entrar(c.ana());
        BrowserClient comCookieDaBia = browser();
        comCookieDaBia.origin = null;
        comCookieDaBia.cookies().putAll(c.bia().browser().cookies());
        assertThat(call(comCookieDaBia, "GET", ME, "Bearer " + ana, null).field("email")).isEqualTo(c.ana().email());
    }
}
