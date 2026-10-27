package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;
import com.nekkora.platform.mail.MailSender;

/**
 * Convites (contrato de 2026-10-27, §4; 023), com as chaves do cadastro, da entrada geral e dos convites LIGADAS, o papel
 * real nekkora_platform e o PostgreSQL real. A administracao usa a sessao de organizacao de um ORG_ADMIN (login, MFA e
 * step-up reais); a destinataria usa a sessao de identidade (cadastro, fator e step-up reais). Cada recusa confere a
 * resposta E o estado persistido (superusuario, sem RLS): convite, contas, vinculos, papeis, {@code identity_users} e
 * {@code access_events}. Estados preparados como superusuario sao DECLARADOS (convite vencido, organizacao suspensa,
 * autoridade do emissor retirada); nenhum deles finge espera real.
 */
// contexto proprio (propriedades da classe): fechado ao fim dela, como nas demais classes com chaves proprias
@DirtiesContext
class AccessInvitationsIT extends IdentityTestBase {

    private static final String NOT_FOUND = "{\"error\":\"not_found\"}";
    private static final String CONFLICT = "{\"error\":\"conflict\"}";

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.convites.enabled", () -> "true");
    }

    /** ORG_ADMIN com sessao de organizacao e step-up recente. */
    private record Admin(Org org, TestUser user, BrowserClient browser) {
    }

    private Admin admin() {
        Org o = newOrganization();
        TestUser u = new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray());
        BrowserClient b = browser();
        firstLogin(b, o.id(), u);
        stepUp(b, o.id(), u.id());
        return new Admin(o, u, b);
    }

    private void stepUp(BrowserClient b, UUID org, UUID user) {
        assertThat(b.post("/api/v1/o/" + org + "/auth/mfa/step-up", "{\"code\":\"" + code(user) + "\"}").status()).isEqualTo(204);
    }

    private BrowserClient.Response invite(Admin a, String email, String role, UUID workspace) {
        return a.browser().post("/api/v1/o/" + a.org().id() + "/invitations", "{\"email\":" + q(email)
                + (role == null ? "" : ",\"role\":" + q(role)) + (workspace == null ? "" : ",\"workspaceId\":\"" + workspace + "\"") + "}");
    }

    private String lastInvitationToken(String email) {
        List<SentMessage> m = mails(email).stream().filter(x -> x.kind() == MailSender.Kind.ORG_INVITATION).toList();
        assertThat(m).as("convite enviado a " + email).isNotEmpty();
        return m.get(m.size() - 1).token();
    }

    private BrowserClient.Response accept(ComFator f, UUID org, String token) {
        return f.browser().post("/api/v1/invitations/accept", "{\"organizationId\":\"" + org + "\",\"token\":" + q(token) + "}");
    }

    private void identityStepUp(ComFator f) {
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);
    }

    private static long accounts(UUID org, String email) throws Exception {
        return count("SELECT count(*) FROM users WHERE organization_id = '" + org + "' AND email_normalized = lower('" + email + "')");
    }

    private static long events(UUID org, int type) throws Exception {
        return count("SELECT count(*) FROM access_events WHERE organization_id = '" + org + "' AND event_type = " + type);
    }

    // ------------------------------------------------------------------ caminho feliz

    /**
     * Emissao com papel de workspace e aceite: 201 sem token na resposta; o token so vai no e-mail (fila), o banco guarda o
     * hash; o aceite pela identidade com step-up cria conta interna ativa com o e-mail verificado da identidade, vinculo
     * ativo, o papel atribuido PELO EMISSOR e a ligacao pelo canal {@code convite}; convite consumido com a identidade;
     * auditoria 1 e 4. A organizacao aparece na lista da identidade e a troca da a sessao. Repetir o aceite: 404, sem efeito.
     */
    @Test
    void emiteEAceitaComPapelDeWorkspaceEContaNovaLigadaPeloConvite() throws Exception {
        Admin a = admin();
        UUID ws = newWorkspace(a.org(), null);
        ComFator f = comFator();

        BrowserClient.Response r = invite(a, f.email().toUpperCase(), "VIEWER", ws);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        UUID inv = UUID.fromString(r.field("invitationId"));
        assertThat(r.field("email")).as("e-mail normalizado").isEqualTo(f.email().toLowerCase());
        assertThat(r.body()).doesNotContain("token");
        String token = lastInvitationToken(f.email());
        assertThat(value("SELECT token_hash FROM identity_invitations WHERE invitation_id = '" + inv + "'")).isEqualTo(Tokens.sha256Hex(token))
                .isNotEqualTo(token);
        assertThat(value("SELECT (expires_at - created_at)::text FROM identity_invitations WHERE invitation_id = '" + inv + "'")).isEqualTo("7 days");
        assertThat(events(a.org().id(), 1)).isEqualTo(1);

        identityStepUp(f);
        BrowserClient.Response ok = accept(f, a.org().id(), token);
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo("{\"organizationId\":\"" + a.org().id() + "\"}");

        String conta = value("SELECT u.user_id FROM users u JOIN identity_users iu ON iu.user_id = u.user_id WHERE iu.identity_id = '" + f.identity()
                + "' AND u.organization_id = '" + a.org().id() + "' AND iu.link_channel = 'convite'");
        assertThat(conta).as("ligacao pelo canal convite").isNotNull();
        assertThat(value("SELECT account_type || '|' || status || '|' || (email_verified_at IS NOT NULL) FROM users WHERE user_id = '" + conta + "'"))
                .isEqualTo("1|2|true");
        assertThat(value("SELECT status FROM organization_memberships WHERE user_id = '" + conta + "'")).isEqualTo("2");
        assertThat(value("SELECT r.code || '|' || (a.assigned_by_membership_id = '" + a.org().adminMembership() + "') FROM workspace_role_assignments a"
                + " JOIN organization_memberships m ON m.membership_id = a.membership_id JOIN roles r ON r.role_id = a.role_id"
                + " WHERE m.user_id = '" + conta + "' AND a.workspace_id = '" + ws + "' AND a.status = 1")).isEqualTo("VIEWER|true");
        assertThat(value("SELECT (accepted_at IS NOT NULL) || '|' || accepted_user_id || '|' || accepted_identity_id FROM identity_invitations WHERE invitation_id = '"
                + inv + "'")).isEqualTo("true|" + conta + "|" + f.identity());
        assertThat(events(a.org().id(), 4)).isEqualTo(1);
        assertThat(value("SELECT actor_identity_id || '|' || target_user_id FROM access_events WHERE organization_id = '" + a.org().id() + "' AND event_type = 4"))
                .isEqualTo(f.identity() + "|" + conta);

        assertThat(f.browser().get("/api/v1/me/organizations").body()).contains(a.org().id().toString());
        assertThat(f.browser().post("/api/v1/sessions/organization", "{\"organizationId\":\"" + a.org().id() + "\"}").field("status")).isEqualTo("sessao_criada");

        BrowserClient.Response de_novo = accept(f, a.org().id(), token);
        assertThat(de_novo.status()).isEqualTo(404);
        assertThat(de_novo.body()).isEqualTo(NOT_FOUND);
        assertThat(events(a.org().id(), 4)).isEqualTo(1);
    }

    /** ORG_ADMIN pelo convite: atribuicao organizacional ativa, com o emissor como responsavel. */
    @Test
    void conviteDeOrgAdmin() throws Exception {
        Admin a = admin();
        ComFator f = comFator();
        assertThat(invite(a, f.email(), "ORG_ADMIN", null).status()).isEqualTo(201);
        identityStepUp(f);
        assertThat(accept(f, a.org().id(), lastInvitationToken(f.email())).status()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM organization_role_assignments a JOIN organization_memberships m ON m.membership_id = a.membership_id"
                + " JOIN identity_users iu ON iu.user_id = m.user_id JOIN roles r ON r.role_id = a.role_id WHERE iu.identity_id = '" + f.identity()
                + "' AND r.code = 'ORG_ADMIN' AND a.status = 1 AND a.assigned_by_membership_id = '" + a.org().adminMembership() + "'")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ recusas do aceite

    /**
     * Sem step-up, outra identidade (e-mail diferente), token adulterado e organizacao errada: 404 unico, nenhum efeito (nem
     * conta, nem ligacao, nem evento 4) e o convite continua pendente.
     */
    @Test
    void aceiteRecusadoSemStepUpPorOutraIdentidadeOuTokenErradoSemEfeito() throws Exception {
        Admin a = admin();
        ComFator f = comFator();
        ComFator outra = comFator();
        assertThat(invite(a, f.email(), null, null).status()).isEqualTo(201);
        String token = lastInvitationToken(f.email());

        assertThat(accept(f, a.org().id(), token).body()).as("sem step-up").isEqualTo(NOT_FOUND);
        identityStepUp(outra);
        assertThat(accept(outra, a.org().id(), token).body()).as("outra identidade").isEqualTo(NOT_FOUND);
        identityStepUp(f);
        assertThat(accept(f, a.org().id(), Tokens.newToken()).body()).as("token de outro convite").isEqualTo(NOT_FOUND);
        assertThat(accept(f, newOrganization().id(), token).body()).as("token procurado so na organizacao informada").isEqualTo(NOT_FOUND);
        assertThat(accept(f, a.org().id(), "curto").status()).as("forma invalida").isEqualTo(404);

        assertThat(accounts(a.org().id(), f.email())).isZero();
        assertThat(accounts(a.org().id(), outra.email())).isZero();
        assertThat(events(a.org().id(), 4)).isZero();
        assertThat(value("SELECT accepted_at IS NULL AND revoked_at IS NULL FROM identity_invitations WHERE organization_id = '" + a.org().id() + "'"))
                .isEqualTo("t");
        assertThat(accept(f, a.org().id(), token).status()).as("o convite continua valendo para a destinataria").isEqualTo(200);
    }

    /**
     * Convite vencido (fixture declarada: inserido ja vencido pelo superusuario), revogado, de organizacao suspensa e de
     * emissor que perdeu a autoridade: 404 unico, nenhum efeito. A organizacao reativada volta a aceitar o pendente.
     */
    @Test
    void vencidoRevogadoOrganizacaoSuspensaEEmissorSemAutoridadeRecusam() throws Exception {
        Admin a = admin();
        UUID org = a.org().id();
        ComFator f = comFator();
        identityStepUp(f);

        String vencido = Tokens.newToken();
        exec("INSERT INTO identity_invitations (organization_id, invited_account_type, email_normalized, issued_by, token_hash, created_at, expires_at,"
                + " issued_by_membership_id) VALUES ('" + org + "', 1, lower('" + f.email() + "'), '" + a.org().adminId() + "', '" + Tokens.sha256Hex(vencido)
                + "', now() - interval '8 days', now() - interval '1 day', '" + a.org().adminMembership() + "')");
        assertThat(accept(f, org, vencido).body()).as("vencido").isEqualTo(NOT_FOUND);

        // um novo convite fecha o vencido (motivo EXPIRADO) e e o unico aberto
        assertThat(invite(a, f.email(), null, null).status()).isEqualTo(201);
        assertThat(value("SELECT revoke_reason FROM identity_invitations WHERE token_hash = '" + Tokens.sha256Hex(vencido) + "'")).isEqualTo("3");
        String token = lastInvitationToken(f.email());

        exec("UPDATE organizations SET status = 2 WHERE organization_id = '" + org + "'");
        assertThat(accept(f, org, token).body()).as("organizacao suspensa").isEqualTo(NOT_FOUND);
        exec("UPDATE organizations SET status = 1 WHERE organization_id = '" + org + "'");

        // emissor sem autoridade: um segundo ORG_ADMIN emite e perde o papel (fixture declarada) antes do aceite
        Admin b = segundoAdmin(a);
        ComFator g = comFator();
        assertThat(invite(b, g.email(), null, null).status()).isEqualTo(201);
        String tokenB = lastInvitationToken(g.email());
        exec("UPDATE organization_role_assignments SET status = 2, revoked_at = now(), revoked_by_membership_id = '" + a.org().adminMembership()
                + "' WHERE membership_id = (SELECT membership_id FROM organization_memberships WHERE user_id = '" + b.user().id() + "') AND status = 1");
        identityStepUp(g);
        assertThat(accept(g, org, tokenB).body()).as("emissor sem autoridade no aceite").isEqualTo(NOT_FOUND);
        assertThat(accounts(org, g.email())).isZero();

        String revogado = token;
        assertThat(a.browser().post("/api/v1/o/" + org + "/invitations/" + invitationOf(revogado) + "/revoke", null).status()).isEqualTo(204);
        assertThat(accept(f, org, revogado).body()).as("revogado").isEqualTo(NOT_FOUND);
        assertThat(accounts(org, f.email())).isZero();
        assertThat(events(org, 4)).isZero();
    }

    /**
     * Sem vinculacao automatica por e-mail: conta legada da organizacao com o e-mail da identidade -> 409 account_exists, sem
     * ligacao nem conta nova; a emissao para esse e-mail ja e recusada (409). Ja membro (conta ligada e ativa) -> 409
     * already_member.
     */
    @Test
    void contaLegadaComMesmoEmailNuncaELigadaEJaMembroRecusa() throws Exception {
        Admin a = admin();
        UUID org = a.org().id();
        ComFator f = comFator();
        identityStepUp(f);
        assertThat(invite(a, f.email(), null, null).status()).isEqualTo(201);
        String token = lastInvitationToken(f.email());
        // conta legada criada DEPOIS da emissao (fixture declarada), com o mesmo e-mail
        UUID legado = UUID.randomUUID();
        DB.asSuperuserIn(org, "INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('" + legado
                + "', '" + org + "', 1, '" + f.email() + "', now(), 2, 'Legado')",
                "INSERT INTO organization_memberships (organization_id, user_id, account_type, status) VALUES ('" + org + "', '" + legado + "', 1, 2)");
        BrowserClient.Response r = accept(f, org, token);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).isEqualTo("{\"error\":\"account_exists\"}");
        assertThat(count("SELECT count(*) FROM identity_users WHERE user_id = '" + legado + "'")).as("legado nao ligado").isZero();
        assertThat(accounts(org, f.email())).isEqualTo(1);
        assertThat(invite(a, "outro-" + f.email(), null, null).status()).isEqualTo(201);
        a.browser().post("/api/v1/o/" + org + "/invitations/" + invitationOf(token) + "/revoke", null);
        BrowserClient.Response dup = invite(a, f.email(), null, null);
        assertThat(dup.status()).as("emissao para e-mail ja cadastrado").isEqualTo(409);
        assertThat(dup.body()).isEqualTo(CONFLICT);

        Admin b = admin();
        ComFator g = comFator();
        identityStepUp(g);
        assertThat(invite(b, g.email(), null, null).status()).isEqualTo(201);
        assertThat(accept(g, b.org().id(), lastInvitationToken(g.email())).status()).isEqualTo(200);
        // segundo convite para quem ja e membro: a emissao recusa (409); um convite feito antes do aceite e recusado no aceite
        assertThat(invite(b, g.email(), null, null).status()).isEqualTo(409);
        String t2 = Tokens.newToken();
        exec("INSERT INTO identity_invitations (organization_id, invited_account_type, email_normalized, issued_by, token_hash, expires_at, issued_by_membership_id)"
                + " VALUES ('" + b.org().id() + "', 1, lower('" + g.email() + "'), '" + b.org().adminId() + "', '" + Tokens.sha256Hex(t2) + "', now() + interval '1 day', '"
                + b.org().adminMembership() + "')");
        BrowserClient.Response membro = accept(g, b.org().id(), t2);
        assertThat(membro.status()).isEqualTo(409);
        assertThat(membro.body()).isEqualTo("{\"error\":\"already_member\"}");
    }

    // ------------------------------------------------------------------ emissao, reenvio, consulta

    /**
     * Emissao exige step-up e org.manage; papel e escopo validados (400); workspace de outra organizacao (404); convite
     * pendente para o mesmo e-mail (409, mesmo com caixa diferente). Nenhuma recusa grava convite, evento ou e-mail.
     */
    @Test
    void emissaoExigeStepUpPermissaoEscopoCorretoESemDuplicado() throws Exception {
        Org o = newOrganization();
        TestUser adm = new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray());
        BrowserClient b = browser();
        firstLogin(b, o.id(), adm);
        String route = "/api/v1/o/" + o.id() + "/invitations";
        assertThat(b.post(route, "{\"email\":\"x1@exemplo.test\"}").status()).as("sem step-up").isEqualTo(403);
        stepUp(b, o.id(), adm.id());
        assertThat(b.post(route, "{\"email\":\"x1@exemplo.test\",\"role\":\"VIEWER\"}").status()).as("papel de workspace sem workspace").isEqualTo(400);
        assertThat(b.post(route, "{\"email\":\"x1@exemplo.test\",\"role\":\"ORG_ADMIN\",\"workspaceId\":\"" + UUID.randomUUID() + "\"}").status())
                .isEqualTo(400);
        assertThat(b.post(route, "{\"email\":\"x1@exemplo.test\",\"role\":\"SUPER\"}").status()).isEqualTo(400);
        assertThat(b.post(route, "{\"email\":\"sem-arroba\"}").status()).isEqualTo(400);
        UUID wsAlheio = newWorkspace(newOrganization(), null);
        assertThat(b.post(route, "{\"email\":\"x1@exemplo.test\",\"role\":\"VIEWER\",\"workspaceId\":\"" + wsAlheio + "\"}").status()).isEqualTo(404);
        assertThat(b.post(route, "{\"email\":\"x1@exemplo.test\"}").status()).isEqualTo(201);
        BrowserClient.Response dup = b.post(route, "{\"email\":\"X1@Exemplo.test\"}");
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.body()).isEqualTo(CONFLICT);
        assertThat(count("SELECT count(*) FROM identity_invitations WHERE organization_id = '" + o.id() + "'")).isEqualTo(1);

        TestUser comum = internalUser(o, true, 2);
        BrowserClient c = browser();
        firstLogin(c, o.id(), comum);
        stepUp(c, o.id(), comum.id());
        assertThat(c.post(route, "{\"email\":\"x2@exemplo.test\"}").status()).as("sem org.manage").isEqualTo(403);
        assertThat(count("SELECT count(*) FROM identity_invitations WHERE organization_id = '" + o.id() + "'")).isEqualTo(1);
        assertThat(events(o.id(), 1)).isEqualTo(1);
    }

    /**
     * Reenvio: o convite anterior fica REVOGADO com motivo REENVIADO e o token dele deixa de valer; o novo aponta para ele e
     * e aceito. Reenviar o ja substituido ou o aceito: 409. Revogar e idempotente; revogar aceito: 409. A consulta lista
     * estados sem token nem hash.
     */
    @Test
    void reenvioInvalidaOTokenAnteriorERevogacaoEConsulta() throws Exception {
        Admin a = admin();
        UUID org = a.org().id();
        ComFator f = comFator();
        identityStepUp(f);
        UUID primeiro = UUID.fromString(invite(a, f.email(), null, null).field("invitationId"));
        String tokenAntigo = lastInvitationToken(f.email());
        BrowserClient.Response re = a.browser().post("/api/v1/o/" + org + "/invitations/" + primeiro + "/resend", null);
        assertThat(re.status()).isEqualTo(201);
        UUID segundo = UUID.fromString(re.field("invitationId"));
        String tokenNovo = lastInvitationToken(f.email());
        assertThat(tokenNovo).isNotEqualTo(tokenAntigo);
        assertThat(value("SELECT revoke_reason FROM identity_invitations WHERE invitation_id = '" + primeiro + "'")).isEqualTo("2");
        assertThat(value("SELECT replaces_invitation_id FROM identity_invitations WHERE invitation_id = '" + segundo + "'")).isEqualTo(primeiro.toString());
        assertThat(a.browser().post("/api/v1/o/" + org + "/invitations/" + primeiro + "/resend", null).status()).as("ja substituido").isEqualTo(409);

        BrowserClient.Response lista = a.browser().get("/api/v1/o/" + org + "/invitations?state=all");
        assertThat(lista.status()).isEqualTo(200);
        assertThat(lista.body()).contains("\"state\":\"REVOGADO\"").contains("\"revokeReason\":\"REENVIADO\"").contains("\"state\":\"PENDENTE\"")
                .doesNotContain(tokenNovo).doesNotContain(Tokens.sha256Hex(tokenNovo)).doesNotContain("token");
        assertThat(a.browser().get("/api/v1/o/" + org + "/invitations").body()).as("pendentes: so o novo, que aponta para o anterior")
                .doesNotContain("\"invitationId\":\"" + primeiro + "\"").contains("\"invitationId\":\"" + segundo + "\"")
                .contains("\"replacesInvitationId\":\"" + primeiro + "\"");

        assertThat(accept(f, org, tokenAntigo).body()).as("token anterior").isEqualTo(NOT_FOUND);
        assertThat(accept(f, org, tokenNovo).status()).isEqualTo(200);
        assertThat(a.browser().post("/api/v1/o/" + org + "/invitations/" + segundo + "/revoke", null).status()).as("revogar aceito").isEqualTo(409);
        stepUp(a.browser(), org, a.user().id());
        assertThat(a.browser().post("/api/v1/o/" + org + "/invitations/" + segundo + "/resend", null).status()).as("reenviar aceito").isEqualTo(409);

        UUID outro = UUID.fromString(invite(a, "rev-" + f.email(), null, null).field("invitationId"));
        assertThat(a.browser().post("/api/v1/o/" + org + "/invitations/" + outro + "/revoke", null).status()).isEqualTo(204);
        assertThat(a.browser().post("/api/v1/o/" + org + "/invitations/" + outro + "/revoke", null).status()).as("idempotente").isEqualTo(204);
        assertThat(count("SELECT count(*) FROM access_events WHERE invitation_id = '" + outro + "' AND event_type = 2")).isEqualTo(1);
        assertThat(a.browser().post("/api/v1/o/" + org + "/invitations/" + UUID.randomUUID() + "/revoke", null).status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ retirada, readmissao e a identidade intacta

    /**
     * Retirada do vinculo de quem entrou pelo convite: sessoes da organizacao caem, papeis revogados, evento 8 e aviso; a
     * identidade (sessao, fator, codigos) nao muda e a organizacao some da lista dela. Novo convite readmite: vinculo volta a
     * ativo, papel novo do convite, os antigos continuam revogados, a mesma conta e a mesma ligacao.
     */
    @Test
    void retiradaDoVinculoPreservaAIdentidadeEReadmissaoPorConvite() throws Exception {
        Admin a = admin();
        UUID org = a.org().id();
        UUID ws = newWorkspace(a.org(), null);
        ComFator f = comFator();
        identityStepUp(f);
        invite(a, f.email(), "OPERATOR", ws);
        assertThat(accept(f, org, lastInvitationToken(f.email())).status()).isEqualTo(200);
        UUID conta = UUID.fromString(value("SELECT u.user_id FROM users u JOIN identity_users iu ON iu.user_id = u.user_id WHERE iu.identity_id = '"
                + f.identity() + "' AND u.organization_id = '" + org + "'"));
        BrowserClient s = client();
        s.cookies().put("__Host-nekkora_id", f.browser().cookies().get("__Host-nekkora_id"));
        s.csrf = f.browser().csrf;
        BrowserClient.Response troca = s.post("/api/v1/sessions/organization", "{\"organizationId\":\"" + org + "\"}");
        s.csrf = troca.field("csrfToken");
        assertThat(s.get("/api/v1/o/" + org + "/me").status()).isEqualTo(200);
        String fatorAntes = value("SELECT string_agg(factor_id::text || coalesce(disabled_at::text, '-'), ',') FROM identity_mfa_factors WHERE identity_id = '"
                + f.identity() + "'");
        long sessoesAntes = sessoesAtivas(f.identity());

        assertThat(a.browser().post("/api/v1/o/" + org + "/users/" + conta + "/membership/revoke", null).status()).isEqualTo(204);
        assertThat(s.get("/api/v1/o/" + org + "/me").status()).as("sessao da organizacao cai").isEqualTo(401);
        assertThat(value("SELECT status FROM organization_memberships WHERE user_id = '" + conta + "'")).isEqualTo("4");
        assertThat(count("SELECT count(*) FROM workspace_role_assignments a JOIN organization_memberships m ON m.membership_id = a.membership_id"
                + " WHERE m.user_id = '" + conta + "' AND a.status = 1")).isZero();
        assertThat(events(org, 8)).isEqualTo(1);
        assertThat(mails(f.email()).stream().anyMatch(m -> m.kind() == MailSender.Kind.NOTICE_ORG_MEMBERSHIP_REVOKED)).isTrue();
        assertThat(value("SELECT string_agg(factor_id::text || coalesce(disabled_at::text, '-'), ',') FROM identity_mfa_factors WHERE identity_id = '"
                + f.identity() + "'")).as("fator da identidade intacto").isEqualTo(fatorAntes);
        assertThat(sessoesAtivas(f.identity())).as("sessoes da identidade intactas").isEqualTo(sessoesAntes);
        assertThat(f.browser().get("/api/v1/me/organizations").body()).doesNotContain(org.toString());
        assertThat(a.browser().post("/api/v1/o/" + org + "/users/" + conta + "/membership/revoke", null).status()).as("repeticao").isEqualTo(204);
        assertThat(events(org, 8)).isEqualTo(1);

        stepUp(a.browser(), org, a.user().id());
        assertThat(invite(a, f.email(), "VIEWER", ws).status()).as("readmissao: emissao aceita").isEqualTo(201);
        identityStepUp(f);
        assertThat(accept(f, org, lastInvitationToken(f.email())).status()).isEqualTo(200);
        assertThat(value("SELECT status FROM organization_memberships WHERE user_id = '" + conta + "'")).isEqualTo("2");
        assertThat(value("SELECT string_agg(r.code || ':' || a.status, ',' ORDER BY a.created_at) FROM workspace_role_assignments a"
                + " JOIN organization_memberships m ON m.membership_id = a.membership_id JOIN roles r ON r.role_id = a.role_id WHERE m.user_id = '" + conta + "'"))
                .as("antigo revogado, novo ativo").isEqualTo("OPERATOR:2,VIEWER:1");
        assertThat(count("SELECT count(*) FROM identity_users WHERE identity_id = '" + f.identity() + "'")).as("mesma ligacao").isEqualTo(1);
        assertThat(accounts(org, f.email())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ concorrencia

    /** Dois aceites simultaneos do mesmo convite: exatamente um 200; uma conta, uma ligacao, um evento 4. */
    @Test
    void doisAceitesSimultaneosUmSoEfeito() throws Exception {
        Admin a = admin();
        ComFator f = comFator();
        identityStepUp(f);
        invite(a, f.email(), null, null);
        String token = lastInvitationToken(f.email());
        CountDownLatch largada = new CountDownLatch(1);
        List<CompletableFuture<Integer>> rs = java.util.stream.IntStream.range(0, 2).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
            try {
                largada.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return accept(f, a.org().id(), token).status();
        })).toList();
        largada.countDown();
        List<Integer> st = rs.stream().map(c -> c.orTimeout(60, TimeUnit.SECONDS).join()).sorted().toList();
        assertThat(st).containsExactly(200, 404);
        assertThat(accounts(a.org().id(), f.email())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM identity_users WHERE identity_id = '" + f.identity() + "'")).isEqualTo(1);
        assertThat(events(a.org().id(), 4)).isEqualTo(1);
    }

    /**
     * Aceite enquanto a organizacao e suspensa: uma transacao do superusuario trava a organizacao e a suspende; o aceite
     * fica esperando o lock (observado em pg_stat_activity); com o commit da suspensao, o aceite reconfere sob o lock e
     * recusa (404), sem conta nem evento.
     */
    @Test
    void aceiteEsperaASuspensaoDaOrganizacaoEDepoisRecusa() throws Exception {
        Admin a = admin();
        UUID org = a.org().id();
        ComFator f = comFator();
        identityStepUp(f);
        invite(a, f.email(), null, null);
        String token = lastInvitationToken(f.email());
        try (Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT 1 FROM organizations WHERE organization_id = '" + org + "' FOR UPDATE");
                st.execute("UPDATE organizations SET status = 2 WHERE organization_id = '" + org + "'");
            }
            CompletableFuture<BrowserClient.Response> aceite = CompletableFuture.supplyAsync(() -> accept(f, org, token));
            assertThat(platformWaitingOnLock()).as("o aceite espera o lock da organizacao").isTrue();
            c.commit();
            BrowserClient.Response r = aceite.orTimeout(60, TimeUnit.SECONDS).join();
            assertThat(r.status()).isEqualTo(404);
        }
        assertThat(accounts(org, f.email())).isZero();
        assertThat(events(org, 4)).isZero();
        exec("UPDATE organizations SET status = 1 WHERE organization_id = '" + org + "'");
    }

    /**
     * Aceite concorrente com a revogacao: a revogacao (superusuario, como a rota faria) trava a organizacao e o convite;
     * o aceite espera; com o commit, o aceite ve o convite revogado e recusa. Estado final: revogado, sem conta.
     */
    @Test
    void aceiteConcorrenteComRevogacaoVeORevogado() throws Exception {
        Admin a = admin();
        UUID org = a.org().id();
        ComFator f = comFator();
        identityStepUp(f);
        UUID inv = UUID.fromString(invite(a, f.email(), null, null).field("invitationId"));
        String token = lastInvitationToken(f.email());
        try (Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT 1 FROM organizations WHERE organization_id = '" + org + "' FOR UPDATE");
                st.execute("UPDATE identity_invitations SET revoked_at = now(), revoke_reason = 1, revoked_by_membership_id = '" + a.org().adminMembership()
                        + "' WHERE invitation_id = '" + inv + "'");
            }
            CompletableFuture<BrowserClient.Response> aceite = CompletableFuture.supplyAsync(() -> accept(f, org, token));
            assertThat(platformWaitingOnLock()).isTrue();
            c.commit();
            assertThat(aceite.orTimeout(60, TimeUnit.SECONDS).join().status()).isEqualTo(404);
        }
        assertThat(value("SELECT (revoked_at IS NOT NULL) || '|' || (accepted_at IS NULL) FROM identity_invitations WHERE invitation_id = '" + inv + "'"))
                .isEqualTo("true|true");
        assertThat(accounts(org, f.email())).isZero();
    }

    // ------------------------------------------------------------------ garantias do banco (023), pelo papel real

    /**
     * A Platform, direto no banco: emitir convite por vinculo sem org.manage, com prazo acima de 7 dias ou ja aceito ->
     * recusado; aceitar pela Platform (sem a funcao) -> recusado; convite aceito e congelado; sem DELETE; auditoria sem
     * UPDATE nem DELETE.
     */
    @Test
    void garantiasDoBancoPeloPapelDaPlatform() throws Exception {
        Admin a = admin();
        UUID org = a.org().id();
        TestUser comum = internalUser(a.org(), true, 2);
        String comumM = value("SELECT membership_id FROM organization_memberships WHERE user_id = '" + comum.id() + "'");
        assertThat(platformSql(org, "INSERT INTO identity_invitations (organization_id, invited_account_type, email_normalized, issued_by, token_hash, expires_at,"
                + " issued_by_membership_id) VALUES ('" + org + "', 1, 'g1@exemplo.test', '" + comum.id() + "', '" + Tokens.sha256Hex(Tokens.newToken())
                + "', now() + interval '1 day', '" + comumM + "')")).as("emissor sem org.manage").isEqualTo("42501");
        assertThat(platformSql(org, "INSERT INTO identity_invitations (organization_id, invited_account_type, email_normalized, issued_by, token_hash, expires_at,"
                + " issued_by_membership_id) VALUES ('" + org + "', 1, 'g2@exemplo.test', '" + a.org().adminId() + "', '" + Tokens.sha256Hex(Tokens.newToken())
                + "', now() + interval '8 days', '" + a.org().adminMembership() + "')")).as("prazo acima de 7 dias").isEqualTo("23514");
        ComFator f = comFator();
        identityStepUp(f);
        UUID inv = UUID.fromString(invite(a, f.email(), null, null).field("invitationId"));
        assertThat(platformSql(org, "UPDATE identity_invitations SET accepted_at = now(), accepted_user_id = '" + comum.id() + "', accepted_identity_id = '"
                + f.identity() + "' WHERE invitation_id = '" + inv + "'")).as("aceite fora da funcao").isEqualTo("42501");
        accept(f, org, lastInvitationToken(f.email()));
        assertThat(platformSql(org, "UPDATE identity_invitations SET revoked_at = now(), revoke_reason = 1, revoked_by_membership_id = '"
                + a.org().adminMembership() + "' WHERE invitation_id = '" + inv + "'")).as("aceito congelado").isEqualTo("P0001");
        assertThat(platformSql(org, "DELETE FROM identity_invitations WHERE invitation_id = '" + inv + "'")).isNotEqualTo("OK");
        assertThat(platformSql(org, "UPDATE access_events SET event_type = 2 WHERE organization_id = '" + org + "'")).isEqualTo("42501");
        assertThat(platformSql(org, "DELETE FROM access_events WHERE organization_id = '" + org + "'")).isEqualTo("42501");
        assertThat(platformSql(org, "INSERT INTO identity_users (identity_id, user_id, link_channel) VALUES ('" + f.identity() + "', '" + comum.id()
                + "', 'convite')")).as("Platform nao liga identidade").isEqualTo("42501");
    }

    /** Executa como nekkora_platform com o contexto da organizacao; devolve "OK" ou o SQLSTATE da recusa. */
    static String platformSql(UUID org, String sql) {
        try (Connection c = DB.connectAs("nekkora_platform")) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT set_config('nekkora.organization_id', '" + org + "', true)");
                st.execute(sql);
                c.commit();
                return "OK";
            } catch (SQLException e) {
                c.rollback();
                return e.getSQLState();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Admin segundoAdmin(Admin a) throws Exception {
        UUID u = UUID.randomUUID();
        UUID m = UUID.randomUUID();
        String email = "adm2-" + u.toString().substring(0, 8) + "@o.test";
        DB.asSuperuserIn(a.org().id(),
                "INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('" + u + "', '" + a.org().id()
                        + "', 1, '" + email + "', now(), 2, 'Segundo admin')",
                "INSERT INTO user_credentials (user_id, password_hash) VALUES ('" + u + "', '" + HASHER.generate(PASSWORD.toCharArray()) + "')",
                "INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('" + m + "', '" + a.org().id()
                        + "', '" + u + "', 1, 2)",
                "INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id) SELECT '"
                        + a.org().id() + "', '" + m + "', role_id, 1, 1, '" + a.org().adminMembership() + "' FROM roles WHERE code = 'ORG_ADMIN'");
        TestUser t = new TestUser(u, email, PASSWORD.toCharArray());
        BrowserClient b = browser();
        firstLogin(b, a.org().id(), t);
        stepUp(b, a.org().id(), u);
        return new Admin(new Org(a.org().id(), u, m, email), t, b);
    }

    private static String invitationOf(String token) throws Exception {
        return value("SELECT invitation_id FROM identity_invitations WHERE token_hash = '" + Tokens.sha256Hex(token) + "'");
    }
}
