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
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PLATFORM-AGENT-V2-003, T10 (034): nome unico entre as organizacoes CRIADAS PELA MESMA IDENTIDADE pelo cadastro (autoria,
 * nao propriedade) e identificador legivel, unico e estavel. Papel real nekkora_platform, funcoes reais da 017, Redis real.
 * A massa de convite (conta, vinculo e papel numa organizacao de outra criadora) e DECLARADA, como superusuario.
 */
@DirtiesContext
@Import(ConfirmacaoPerdida.class)
class OrganizacaoNomeUnicoIT extends IdentityTestBase {

    private static final String CRIAR = "/api/v1/organizations";
    private static final String LISTA = "/api/v1/me/organizations";
    private static final String MOTIVO = "\"reason\":\"organization_name_taken\"";
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.criacao-organizacao.enabled", () -> "true");
    }

    private ComFator pronta() throws Exception {
        ComFator f = comFator();
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);
        return f;
    }

    private static BrowserClient.Response criar(BrowserClient b, String nome) {
        return b.post(CRIAR, "{\"name\":" + JSON.valueToTree(nome) + "}");
    }

    private static long criadas(UUID identity) throws Exception {
        return count("SELECT count(*) FROM identity_users WHERE identity_id = '" + identity + "' AND link_channel = 'cadastro_web'");
    }

    private static String handle(String org) throws Exception {
        return value("SELECT handle FROM organization_handles WHERE organization_id = '" + org + "'");
    }

    private static JsonNode lista(BrowserClient b) throws Exception {
        BrowserClient.Response r = b.get(LISTA);
        assertThat(r.status()).isEqualTo(200);
        return JSON.readTree(r.body()).get("organizations");
    }

    // ------------------------------------------------------------------ mesma criadora

    /** Exato, maiusculas/minusculas e espacos internos: 409 sem criar nada. Acentos distinguem (regra nao mudada). */
    @Test
    void mesmaCriadoraNaoRepeteONomeAposNormalizacao() throws Exception {
        ComFator f = pronta();
        BrowserClient.Response a = criar(f.browser(), "Clínica Ágata");
        assertThat(a.status()).as(a.body()).isEqualTo(201);
        for (String repetido : List.of("Clínica Ágata", "clínica ágata", "CLÍNICA ÁGATA", "Clínica   Ágata", "Clínica Ágata")) {
            BrowserClient.Response r = criar(f.browser(), repetido);
            assertThat(r.status()).as(repetido).isEqualTo(409);
            assertThat(r.body()).isEqualTo("{\"error\":\"conflict\"," + MOTIVO + "}");
        }
        // espacos nas pontas continuam 400 (contrato P2), sem criar
        assertThat(criar(f.browser(), " Clínica Ágata").status()).isEqualTo(400);
        assertThat(criadas(f.identity())).as("so a primeira").isEqualTo(1);
        // acento e outro nome (a regra nao remove acentos)
        BrowserClient.Response semAcento = criar(f.browser(), "Clinica Agata");
        assertThat(semAcento.status()).as(semAcento.body()).isEqualTo(201);
        assertThat(criadas(f.identity())).isEqualTo(2);
        // o nome de apresentacao fica como foi digitado; a autoria e da identidade, pelo identificador persistente
        assertThat(value("SELECT name FROM organizations WHERE organization_id = '" + a.field("organizationId") + "'")).isEqualTo("Clínica Ágata");
        assertThat(value("SELECT creator_identity_id || '|' || evidence FROM organization_authorship WHERE organization_id = '" + a.field("organizationId") + "'"))
                .isEqualTo(f.identity() + "|cadastro_web");
    }

    /** Duas criacoes simultaneas da mesma identidade com o mesmo nome: uma organizacao, uma recusa 409. */
    @Test
    void criacoesSimultaneasDaMesmaCriadoraGeramUmaSo() throws Exception {
        ComFator f = pronta();
        ExecutorService ex = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);
        List<Integer> status = new ArrayList<>();
        try {
            List<Future<BrowserClient.Response>> fs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                fs.add(ex.submit(() -> {
                    largada.await();
                    return cru(f.browser(), "{\"name\":\"Estudio Paralelo\"}");
                }));
            }
            largada.countDown();
            for (Future<BrowserClient.Response> x : fs) {
                BrowserClient.Response r = x.get();
                status.add(r.status());
                if (r.status() == 409) {
                    assertThat(r.body()).contains(MOTIVO).doesNotContain("organizationId");
                }
            }
        } finally {
            ex.shutdownNow();
        }
        assertThat(status).containsExactlyInAnyOrder(201, 409);
        assertThat(criadas(f.identity())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ criadoras diferentes e convite

    /**
     * Criadoras diferentes podem usar o mesmo nome; quem e ORG_ADMIN por convite de uma homonima cria a sua. Na lista dela,
     * as duas aparecem com o nome, o identificador (diferente) e o papel real; cada uma abre a organizacao certa.
     */
    @Test
    void criadorasDiferentesEConvidadaDistingueEAbreCadaUma() throws Exception {
        ComFator ana = pronta();
        BrowserClient.Response daAna = criar(ana.browser(), "Padaria Central");
        assertThat(daAna.status()).isEqualTo(201);
        String orgAna = daAna.field("organizationId");

        ComFator bia = pronta();
        // Bia entra na organizacao da Ana por convite (massa declarada: conta, vinculo ACTIVE, ORG_ADMIN e o vinculo de identidade)
        String conta = value("INSERT INTO users (organization_id, account_type, display_name, email, email_verified_at, status) VALUES ('" + orgAna
                + "', 1, 'Bia', '" + bia.email() + "', now(), 2) RETURNING user_id");
        String membro = value("INSERT INTO organization_memberships (organization_id, user_id, account_type, status) VALUES ('" + orgAna + "', '" + conta
                + "', 1, 2) RETURNING membership_id");
        String adminAna = value("SELECT m.membership_id FROM organization_memberships m JOIN identity_users iu ON iu.user_id = m.user_id WHERE iu.identity_id = '"
                + ana.identity() + "' AND m.organization_id = '" + orgAna + "'");
        exec("INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id) SELECT '" + orgAna
                + "', '" + membro + "', role_id, 1, 1, '" + adminAna + "' FROM roles WHERE code = 'ORG_ADMIN'");
        exec("INSERT INTO identity_users (identity_id, user_id, link_channel) VALUES ('" + bia.identity() + "', '" + conta + "', 'vinculacao')");

        // a participacao por convite (mesmo como ORG_ADMIN) nao entra na comparacao: Bia cria a sua com o mesmo nome
        BrowserClient.Response daBia = criar(bia.browser(), "Padaria Central");
        assertThat(daBia.status()).as(daBia.body()).isEqualTo(201);
        String orgBia = daBia.field("organizationId");
        assertThat(handle(orgAna)).isNotEqualTo(handle(orgBia));
        assertThat(handle(orgBia)).as("colisao resolvida com sufixo").matches(handle(orgAna).replaceAll("-\\d+$", "") + "-\\d+");

        JsonNode itens = lista(bia.browser());
        assertThat(itens).hasSize(2);
        for (JsonNode o : itens) {
            assertThat(o.get("name").asString()).isEqualTo("Padaria Central");
            assertThat(o.get("handle").asString()).isEqualTo(handle(o.get("organizationId").asString()));
            assertThat(o.get("roles").toString()).isEqualTo("[\"ORG_ADMIN\"]");
        }
        // a lista nao expoe e-mail nem dado da outra pessoa
        assertThat(bia.browser().get(LISTA).body()).doesNotContain(ana.email()).doesNotContain(ana.identity().toString());
        // cada uma abre a organizacao certa (pelo ID, nunca pelo identificador)
        for (String org : List.of(orgAna, orgBia)) {
            BrowserClient.Response troca = bia.browser().post("/api/v1/sessions/organization", "{\"organizationId\":\"" + org + "\"}");
            assertThat(troca.status()).as(troca.body()).isEqualTo(200);
        }
        // autoria so da criadora: a convidada nao aparece como autora da organizacao da Ana
        assertThat(value("SELECT creator_identity_id FROM organization_authorship WHERE organization_id = '" + orgAna + "'")).isEqualTo(ana.identity().toString());
    }

    // ------------------------------------------------------------------ identificador

    /**
     * Identificador estavel: a mudanca de nome (nao ha rota; aqui direto no banco, como o papel da Platform poderia) nao o
     * altera, e a regra de nome continua valendo na mudanca. Ele nao e credencial: usado no lugar do ID, nada e aceito.
     */
    @Test
    void identificadorEstavelENaoDaAcesso() throws Exception {
        ComFator f = pronta();
        String a = criar(f.browser(), "Oficina Norte").field("organizationId");
        String b = criar(f.browser(), "Oficina Sul").field("organizationId");
        String h = handle(a);
        assertThat(h).matches("oficina-norte(-\\d+)?");
        exec("UPDATE organizations SET name = 'Oficina Leste' WHERE organization_id = '" + a + "'");
        assertThat(handle(a)).as("renomear nao muda o identificador").isEqualTo(h);
        assertThat(value("SELECT name_key FROM organization_authorship WHERE organization_id = '" + a + "'")).isEqualTo("oficina leste");
        // renomear para o nome de outra organizacao da mesma criadora: o banco recusa
        assertThat(sqlState("UPDATE organizations SET name = 'oficina  sul' WHERE organization_id = '" + a + "'")).isEqualTo("23505");
        assertThat(sqlState("UPDATE organization_handles SET handle = 'outro' WHERE organization_id = '" + a + "'")).as("identificador imutavel").isNotEqualTo("OK");
        // o identificador no lugar do ID: recusado (as rotas so aceitam o ID)
        BrowserClient.Response troca = f.browser().post("/api/v1/sessions/organization", "{\"organizationId\":\"" + h + "\"}");
        assertThat(troca.status()).isEqualTo(400);
        assertThat(f.browser().get("/api/v1/o/" + h + "/me").status()).isIn(400, 401, 404);
        assertThat(b).isNotNull();
    }

    /** Organizacao provisionada pela operacao: recebe identificador e fica fora da regra de autoria. */
    @Test
    void provisionadaPelaOperacaoTemIdentificadorESemAutoria() throws Exception {
        String org = newOrganizationId();
        assertThat(handle(org)).isNotNull().matches("^[a-z0-9]+(-[a-z0-9]+)*$");
        assertThat(count("SELECT count(*) FROM organization_authorship WHERE organization_id = '" + org + "'")).isZero();
    }

    // ------------------------------------------------------------------ apoio

    private static String newOrganizationId() {
        return PlatformTestBase.newOrganization().id().toString();
    }

    private static String sqlState(String sql) {
        try {
            exec(sql);
            return "OK";
        } catch (Exception e) {
            for (Throwable c = e; c != null; c = c.getCause()) {
                if (c instanceof java.sql.SQLException s && s.getSQLState() != null) {
                    return s.getSQLState();
                }
            }
            return "ERRO";
        }
    }

    private BrowserClient.Response cru(BrowserClient b, String json) throws Exception {
        java.net.http.HttpRequest.Builder rb = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + CRIAR))
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
