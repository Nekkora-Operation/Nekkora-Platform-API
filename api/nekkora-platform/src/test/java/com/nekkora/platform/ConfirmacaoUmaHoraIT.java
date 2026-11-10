package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;

/**
 * PLATFORM-V2-001, T01 — confirmacao de identidade por uma hora. Uma confirmacao aceita (TOTP, ou a senha na sessao sem MFA)
 * abre uma janela FIXA de 60 minutos para as acoes sensiveis comuns DAQUELA sessao de organizacao; usar acoes nao estende;
 * sem confirmacao valida, 403 step_up_required e nada feito. Sessao, organizacao e permissao continuam conferidas a cada ato.
 * Prova propria (reset administrativo de MFA) continua exigindo os ultimos 5 minutos.
 * <p>
 * Relogio controlado: o instante da confirmacao e recuado no banco (superusuario, preparacao), como nas suites de identidade.
 * "Confirmar as 10h; nova acao as 10h59 sem codigo; as 11h nova prova" = confirmacao recuada 59 min (aceita) e 60 min (recusa).
 * Acao sensivel comum representativa: emitir convite (023), um por e-mail, contavel no banco.
 */
@DirtiesContext
class ConfirmacaoUmaHoraIT extends IdentityTestBase {

    private static final String STEP_UP_REQUIRED = "{\"error\":\"forbidden\",\"reason\":\"step_up_required\"}";
    private static final String FORBIDDEN = "{\"error\":\"forbidden\"}";

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.criacao-organizacao.enabled", () -> "true");
        r.add("nekkora.convites.enabled", () -> "true");
    }

    // ------------------------------------------------------------------ apoio

    record OrgSessao(UUID org, BrowserClient b, UUID identity) {
    }

    /** Identidade com MFA, organizacao criada por ela e sessao de organizacao (vinculada) pela troca. */
    private OrgSessao comMfa() throws Exception {
        ComFator f = comFator();
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);
        BrowserClient.Response o = f.browser().post("/api/v1/organizations", "{\"name\":" + q("Org " + UUID.randomUUID().toString().substring(0, 6)) + "}");
        assertThat(o.status()).as(o.body()).isEqualTo(201);
        UUID org = UUID.fromString(o.field("organizationId"));
        trocar(f.browser(), org);
        return new OrgSessao(org, f.browser(), f.identity());
    }

    private static void trocar(BrowserClient b, UUID org) {
        BrowserClient.Response t = b.post("/api/v1/sessions/organization", "{\"organizationId\":\"" + org + "\"}");
        assertThat(t.field("status")).as(t.body()).isEqualTo("sessao_criada");
        b.csrf = t.field("csrfToken");
    }

    private static String sessao(BrowserClient b) {
        return Tokens.sha256Hex(b.cookies().get("__Host-nekkora_session"));
    }

    private void confirmar(OrgSessao s) {
        BrowserClient.Response r = s.b().post("/api/v1/o/" + s.org() + "/auth/mfa/step-up", "{\"code\":\"" + code(s.identity()) + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(204);
    }

    /** Recuo do relogio: a confirmacao desta sessao passa a ter acontecido ha {@code minutos} (e {@code segundos}). */
    private static void confirmadaHa(BrowserClient b, String coluna, int minutos, int segundos) throws Exception {
        exec("SET session_replication_role = replica; UPDATE user_sessions SET " + coluna + " = clock_timestamp() - interval '" + minutos + " minutes "
                + segundos + " seconds' WHERE session_token_hash = '" + sessao(b) + "'; SET session_replication_role = origin");
    }

    private static BrowserClient.Response convidar(BrowserClient b, UUID org) {
        return b.post("/api/v1/o/" + org + "/invitations", "{\"email\":" + q(email()) + "}");
    }

    private static long convites(UUID org) throws Exception {
        return count("SELECT count(*) FROM identity_invitations WHERE organization_id = '" + org + "'");
    }

    // ------------------------------------------------------------------ 1. janela fixa

    @Test
    void confirmacaoAbreJanelaFixaDeUmaHoraQueAsAcoesNaoEstendem() throws Exception {
        OrgSessao s = comMfa();
        confirmar(s);
        String instante = value("SELECT mfa_step_up_at FROM user_sessions WHERE session_token_hash = '" + sessao(s.b()) + "'");

        // 10h00: duas acoes sensiveis seguidas, sem codigo na segunda
        assertThat(convidar(s.b(), s.org()).status()).isEqualTo(201);
        assertThat(convidar(s.b(), s.org()).status()).isEqualTo(201);
        assertThat(value("SELECT mfa_step_up_at FROM user_sessions WHERE session_token_hash = '" + sessao(s.b()) + "'"))
                .as("usar acoes nao estende a janela").isEqualTo(instante);

        // 10h59: ainda dentro da janela, sem pedir codigo
        confirmadaHa(s.b(), "mfa_step_up_at", 59, 0);
        BrowserClient.Response dentro = convidar(s.b(), s.org());
        assertThat(dentro.status()).as(dentro.body()).isEqualTo(201);

        // 11h00: so a acao sensivel pede nova prova; a leitura continua normal
        confirmadaHa(s.b(), "mfa_step_up_at", 60, 0);
        BrowserClient.Response vencida = convidar(s.b(), s.org());
        assertThat(vencida.status()).isEqualTo(403);
        assertThat(vencida.body()).isEqualTo(STEP_UP_REQUIRED);
        assertThat(convites(s.org())).as("a recusa nao criou nada").isEqualTo(3);
        assertThat(s.b().get("/api/v1/o/" + s.org() + "/me").status()).isEqualTo(200);

        // nova confirmacao: nova janela
        confirmar(s);
        assertThat(convidar(s.b(), s.org()).status()).isEqualTo(201);
        assertThat(convites(s.org())).isEqualTo(4);
    }

    @Test
    void codigoAceitoNaoEReaproveitado() throws Exception {
        OrgSessao s = comMfa();
        String c = code(s.identity());
        assertThat(s.b().post("/api/v1/o/" + s.org() + "/auth/mfa/step-up", "{\"code\":\"" + c + "\"}").status()).isEqualTo(204);
        BrowserClient.Response repetido = s.b().post("/api/v1/o/" + s.org() + "/auth/mfa/step-up", "{\"code\":\"" + c + "\"}");
        assertThat(repetido.status()).isEqualTo(403);
        assertThat(repetido.body()).isEqualTo(FORBIDDEN);
        // nenhuma coluna guarda o codigo: so instantes
        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'user_sessions' AND column_name ~ '(code|codigo|totp)'"))
                .isZero();
    }

    // ------------------------------------------------------------------ 2. a prova e da sessao

    @Test
    void semConfirmacaoNaSessaoOAtoDiretoPelaApiERecusadoSemEfeito() throws Exception {
        OrgSessao s = comMfa();
        BrowserClient.Response r = convidar(s.b(), s.org());
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body()).isEqualTo(STEP_UP_REQUIRED);
        assertThat(convites(s.org())).isZero();
    }

    @Test
    void logoutENovaSessaoNaoHerdamAConfirmacao() throws Exception {
        OrgSessao s = comMfa();
        confirmar(s);
        assertThat(convidar(s.b(), s.org()).status()).isEqualTo(201);
        assertThat(s.b().post("/api/v1/o/" + s.org() + "/auth/logout", null).status()).isIn(200, 204);
        assertThat(convidar(s.b(), s.org()).status()).as("depois do logout").isEqualTo(401);
        // nova sessao de organizacao da mesma identidade (a troca usa o CSRF da sessao de identidade): sem confirmacao propria
        BrowserClient.Response id = s.b().get("/api/v1/me/session");
        assertThat(id.status()).as("a sessao de identidade continua").isEqualTo(200);
        s.b().csrf = id.field("csrfToken");
        trocar(s.b(), s.org());
        BrowserClient.Response nova = convidar(s.b(), s.org());
        assertThat(nova.status()).isEqualTo(403);
        assertThat(nova.body()).isEqualTo(STEP_UP_REQUIRED);
        assertThat(convites(s.org())).isEqualTo(1);
    }

    @Test
    void revogacaoDasSessoesDerrubaAConfirmacao() throws Exception {
        OrgSessao s = comMfa();
        confirmar(s);
        assertThat(s.b().post("/api/v1/o/" + s.org() + "/auth/sessions/revoke-all", null).status()).isIn(200, 204);
        assertThat(convidar(s.b(), s.org()).status()).isEqualTo(401);
        assertThat(convites(s.org())).isZero();
    }

    @Test
    void permissaoRetiradaDentroDaHoraERecusadaMesmoComConfirmacaoValida() throws Exception {
        Org org = newOrganization();
        TestUser bruno = internalUser(org, true, 2);
        UUID m = AccessFixtures.membershipOf(org.id(), bruno.id());
        UUID papel = AccessFixtures.assignOrgRole(org.id(), m, "ORG_ADMIN", org.adminMembership());
        BrowserClient b = browser();
        firstLogin(b, org.id(), bruno);
        assertThat(b.post("/api/v1/o/" + org.id() + "/auth/mfa/step-up", "{\"code\":\"" + code(bruno.id()) + "\"}").status()).isEqualTo(204);
        assertThat(convidar(b, org.id()).status()).isEqualTo(201);
        AccessFixtures.revokeOrgAssignment(papel, org.adminMembership());
        BrowserClient.Response r = convidar(b, org.id());
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body()).as("falta permissao, nao confirmacao").isEqualTo(FORBIDDEN);
        assertThat(convites(org.id())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 3. prova propria

    @Test
    void resetAdministrativoDeMfaContinuaExigindoOsUltimosCincoMinutos() throws Exception {
        Org org = newOrganization();
        BrowserClient ana = browser();
        firstLogin(ana, org.id(), new TestUser(org.adminId(), org.adminEmail(), PASSWORD.toCharArray()));
        TestUser caio = internalUser(org, true, 2);
        AccessFixtures.mfaFactor(org.id(), caio.id(), true, false);
        assertThat(ana.post("/api/v1/o/" + org.id() + "/auth/mfa/step-up", "{\"code\":\"" + code(org.adminId()) + "\"}").status()).isEqualTo(204);
        confirmadaHa(ana, "mfa_step_up_at", 6, 0);
        String reset = "/api/v1/o/" + org.id() + "/users/" + caio.id() + "/mfa/reset";
        BrowserClient.Response r = ana.post(reset, "{\"reason\":\"SUSPEITA_COMPROMETIMENTO\"}");
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body()).isEqualTo(STEP_UP_REQUIRED);
        assertThat(value("SELECT disabled_at IS NULL FROM user_mfa_factors WHERE user_id = '" + caio.id() + "'")).isEqualTo("t");
        // a mesma confirmacao ainda vale para as acoes comuns
        assertThat(convidar(ana, org.id()).status()).isEqualTo(201);
        // confirmacao fresca: o reset passa
        assertThat(ana.post("/api/v1/o/" + org.id() + "/auth/mfa/step-up", "{\"code\":\"" + code(org.adminId()) + "\"}").status()).isEqualTo(204);
        assertThat(ana.post(reset, "{\"reason\":\"SUSPEITA_COMPROMETIMENTO\"}").status()).isEqualTo(204);
    }

    // ------------------------------------------------------------------ 4. senha sem MFA; senha recusada com MFA

    @Test
    void senhaNaSessaoSemMfaAbreAMesmaJanelaFixa() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        BrowserClient b = client();
        assertThat(login(b, e, SENHA).field("status")).isEqualTo("authenticated");
        assertThat(b.post("/api/v1/auth/password-confirm", "{\"password\":" + q(SENHA) + "}").status()).isEqualTo(204);
        BrowserClient.Response o = b.post("/api/v1/organizations", "{\"name\":" + q("Sem MFA " + id.toString().substring(0, 6)) + "}");
        assertThat(o.status()).as(o.body()).isEqualTo(201);
        UUID org = UUID.fromString(o.field("organizationId"));
        trocar(b, org);
        assertThat(convidar(b, org).body()).isEqualTo(STEP_UP_REQUIRED);
        assertThat(b.post("/api/v1/o/" + org + "/auth/password-confirm", "{\"password\":" + q(SENHA) + "}").status()).isEqualTo(204);
        assertThat(convidar(b, org).status()).isEqualTo(201);
        confirmadaHa(b, "password_step_up_at", 59, 0);
        assertThat(convidar(b, org).status()).isEqualTo(201);
        confirmadaHa(b, "password_step_up_at", 60, 0);
        BrowserClient.Response vencida = convidar(b, org);
        assertThat(vencida.status()).isEqualTo(403);
        assertThat(vencida.body()).isEqualTo(STEP_UP_REQUIRED);
        assertThat(convites(org)).isEqualTo(2);
    }

    @Test
    void contaComMfaNaoConfirmaComSenha() throws Exception {
        OrgSessao s = comMfa();
        assertThat(s.b().post("/api/v1/o/" + s.org() + "/auth/password-confirm", "{\"password\":" + q(SENHA) + "}").status()).isEqualTo(403);
        assertThat(value("SELECT password_step_up_at IS NULL FROM user_sessions WHERE session_token_hash = '" + sessao(s.b()) + "'")).isEqualTo("t");
        assertThat(convidar(s.b(), s.org()).body()).isEqualTo(STEP_UP_REQUIRED);
        assertThat(convites(s.org())).isZero();
    }

    // ------------------------------------------------------------------ 5. sessao restrita continua restrita

    @Test
    void sessaoRestritaNaoViraPlenaNemComConfirmacaoAnteriorValida() throws Exception {
        ComFator f = comFator();
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);
        BrowserClient.Response o = f.browser().post("/api/v1/organizations", "{\"name\":" + q("Restrita " + UUID.randomUUID().toString().substring(0, 6)) + "}");
        UUID org = UUID.fromString(o.field("organizationId"));
        trocar(f.browser(), org);
        assertThat(f.browser().post("/api/v1/o/" + org + "/auth/mfa/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);

        // outra entrada da mesma pessoa, por codigo de recuperacao: sessao de identidade RESTRITA
        BrowserClient r = client();
        login(r, f.email(), SENHA);
        BrowserClient.Response v = r.post("/api/v1/auth/mfa/verify", "{\"recoveryCode\":\"" + f.codes().get(0) + "\"}");
        assertThat(v.status()).isEqualTo(200);
        r.csrf = v.field("csrfToken");
        BrowserClient.Response troca = r.post("/api/v1/sessions/organization", "{\"organizationId\":\"" + org + "\"}");
        assertThat(troca.status()).as("restrita nao abre sessao de organizacao").isNotEqualTo(200);
        assertThat(r.post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(403);
        assertThat(convidar(r, org).status()).isIn(401, 403);
        assertThat(convites(org)).isZero();
    }
}
