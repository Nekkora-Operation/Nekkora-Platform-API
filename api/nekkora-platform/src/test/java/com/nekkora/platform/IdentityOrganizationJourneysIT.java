package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;

/**
 * Jornadas de organizacao pela API (missao PLATFORM-API-IDENTIDADE-002, item 2), so com as rotas publicas e o papel real
 * nekkora_platform: as demonstracoes que os testes existentes nao faziam por inteiro. Complementa OrganizationCreationIT,
 * IdentityOrgSessionIT, AccountLinkIT e LinkedOrgStepUpIT (mapa no relatorio da missao).
 * <ul>
 * <li>a conta criada pela identidade nao tem credencial local: a entrada local da organizacao a recusa;</li>
 * <li>suspensao da conta, retirada (revogacao) do vinculo e suspensao da organizacao tiram a organizacao da lista, recusam a
 * troca e derrubam a sessao de organizacao ja emitida na requisicao seguinte; a sessao de identidade continua.</li>
 * </ul>
 */
class IdentityOrganizationJourneysIT extends IdentityTestBase {

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.criacao-organizacao.enabled", () -> "true");
    }

    /** Criada pela identidade: ORG_ADMIN, vinculo ACTIVE, conta do canal de cadastro e NENHUMA credencial local. */
    @Test
    void contaCriadaPelaIdentidadeNaoTemCredencialLocal() throws Exception {
        ComFator f = comFator();
        UUID org = criar(f, "Org sem credencial local");
        String conta = value("SELECT iu.user_id FROM identity_users iu JOIN users u ON u.user_id = iu.user_id WHERE iu.identity_id = '" + f.identity()
                + "' AND u.organization_id = '" + org + "'");
        assertThat(conta).isNotNull();
        assertThat(value("SELECT link_channel FROM identity_users WHERE user_id = '" + conta + "'")).isEqualTo("cadastro_web");
        assertThat(count("SELECT count(*) FROM user_credentials WHERE user_id = '" + conta + "'")).as("sem credencial local").isZero();
        BrowserClient local = client();
        BrowserClient.Response l = local.post("/api/v1/o/" + org + "/auth/login", "{\"email\":" + q(f.email()) + ",\"password\":" + q(SENHA) + "}");
        assertThat(l.status()).as("a senha da identidade nao abre a entrada local").isEqualTo(401);
        assertThat(l.body()).isEqualTo(UNAUTH);
        assertThat(f.browser().get("/api/v1/me/organizations").body()).contains(org.toString()).contains("ORG_ADMIN");
    }

    @Test
    void suspensaoDaContaDerrubaAcessoMasNaoAIdentidade() throws Exception {
        ComFator f = comFator();
        UUID org = criar(f, "Org conta suspensa");
        BrowserClient s = sessaoDaOrganizacao(f, org);
        segundoAdmin(f, org);
        DB.asSuperuserIn(org, "UPDATE users SET status = 3 WHERE user_id = (SELECT iu.user_id FROM identity_users iu JOIN users u ON u.user_id = iu.user_id"
                + " WHERE iu.identity_id = '" + f.identity() + "' AND u.organization_id = '" + org + "')");
        assertSemAcesso(f, s, org);
    }

    @Test
    void retiradaDoVinculoDerrubaAcesso() throws Exception {
        ComFator f = comFator();
        UUID org = criar(f, "Org vinculo retirado");
        BrowserClient s = sessaoDaOrganizacao(f, org);
        segundoAdmin(f, org);
        DB.asSuperuserIn(org, "UPDATE organization_memberships SET status = 4 WHERE organization_id = '" + org + "' AND user_id = (SELECT iu.user_id"
                + " FROM identity_users iu JOIN users u ON u.user_id = iu.user_id WHERE iu.identity_id = '" + f.identity() + "' AND u.organization_id = '" + org + "')");
        assertSemAcesso(f, s, org);
    }

    @Test
    void suspensaoDaOrganizacaoDerrubaAcesso() throws Exception {
        ComFator f = comFator();
        UUID org = criar(f, "Org suspensa depois");
        BrowserClient s = sessaoDaOrganizacao(f, org);
        DB.asSuperuser("UPDATE organizations SET status = 2 WHERE organization_id = '" + org + "'");
        assertSemAcesso(f, s, org);
    }

    // ------------------------------------------------------------------ apoio

    /**
     * Fixture declarada: um segundo ORG_ADMIN ativo, atribuido pelo vinculo da conta da identidade. Sem ele, a 003 recusa
     * suspender a conta ou retirar o vinculo do unico ORG_ADMIN ("ficaria sem ORG_ADMIN efetivo") - regra que continua valendo.
     */
    private static void segundoAdmin(ComFator f, UUID org) throws Exception {
        String membro = value("SELECT m.membership_id FROM organization_memberships m JOIN identity_users iu ON iu.user_id = m.user_id"
                + " WHERE iu.identity_id = '" + f.identity() + "' AND m.organization_id = '" + org + "'");
        UUID u = UUID.randomUUID();
        UUID m = UUID.randomUUID();
        DB.asSuperuserIn(org,
                "INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('" + u + "', '" + org
                        + "', 1, 'adm2-" + u.toString().substring(0, 8) + "@o.test', now(), 2, 'Segundo admin')",
                "INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('" + m + "', '" + org + "', '" + u
                        + "', 1, 2)",
                "INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id) SELECT '" + org
                        + "', '" + m + "', role_id, 1, 1, '" + membro + "' FROM roles WHERE code = 'ORG_ADMIN'");
    }

    private UUID criar(ComFator f, String nome) {
        assertThat(f.browser().get("/api/v1/me/organizations").body()).contains("\"canCreateOrganization\":true");
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);
        BrowserClient.Response r = f.browser().post("/api/v1/organizations", "{\"name\":" + q(nome) + "}");
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return UUID.fromString(r.field("organizationId"));
    }

    /** Sessao de organizacao pela troca, num navegador com o cookie da identidade; confere que ela serve a organizacao. */
    private BrowserClient sessaoDaOrganizacao(ComFator f, UUID org) {
        BrowserClient b = client();
        b.cookies().put("__Host-nekkora_id", f.browser().cookies().get("__Host-nekkora_id"));
        b.csrf = f.browser().csrf;
        BrowserClient.Response r = b.post("/api/v1/sessions/organization", "{\"organizationId\":\"" + org + "\"}");
        assertThat(r.field("status")).isEqualTo("sessao_criada");
        b.csrf = r.field("csrfToken");
        assertThat(b.get("/api/v1/o/" + org + "/me").status()).as("sessao de organizacao emitida").isEqualTo(200);
        return b;
    }

    /** Fora da lista, troca recusada (resposta uniforme), sessao ja emitida derrubada; a identidade segue entrando. */
    private void assertSemAcesso(ComFator f, BrowserClient s, UUID org) {
        assertThat(f.browser().get("/api/v1/me/organizations").body()).as("fora da lista").doesNotContain(org.toString());
        BrowserClient t = client();
        t.cookies().put("__Host-nekkora_id", f.browser().cookies().get("__Host-nekkora_id"));
        t.csrf = f.browser().csrf;
        BrowserClient.Response r = t.post("/api/v1/sessions/organization", "{\"organizationId\":\"" + org + "\"}");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.field("status")).as("troca recusada").isNotEqualTo("sessao_criada");
        assertThat(cookie(r, "__Host-nekkora_session")).isNull();
        assertThat(s.get("/api/v1/o/" + org + "/me").status()).as("sessao ja emitida cai na requisicao seguinte").isEqualTo(401);
        assertThat(f.browser().get("/api/v1/me/organizations").status()).as("a sessao de identidade continua").isEqualTo(200);
    }
}
