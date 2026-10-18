package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;
import com.nekkora.platform.mail.MailSender;

/**
 * Entrada local de conta VINCULADA (contrato P3, "o que entra junto"; desenho §4.5, camada 1, e DC-17.12): o endpoint de
 * credencial por organizacao, a recuperacao de senha e a troca de senha nao servem a conta vinculada, e respondem como
 * para qualquer outra conta, sem revelar o vinculo. Cobre os dois canais de vinculo da 017: {@code cadastro_web} (conta
 * criada por {@code identity_create_organization}, sem credencial local) e {@code vinculacao} (conta antiga, com a
 * credencial local mantida inerte pela DC-17.12). A vinculacao aqui e feita pela funcao da 017, direto no banco: a rota
 * tem testes proprios.
 */
class LinkedAccountLocalEntryIT extends IdentityTestBase {

    private static final String RECUPERACAO = "/auth/password-reset/request";

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
    }

    /**
     * Conta criada pelo cadastro (canal cadastro_web): o pedido de recuperacao responde 202 vazio, como para conta
     * inexistente e para conta comum, sem emitir token nem e-mail. Controle: a conta comum recebe token e e-mail.
     */
    @Test
    void recuperacaoDeContaDoCadastroE202SemTokenNemEmail() throws Exception {
        ComFator f = comFator();
        UUID org = organizacaoDa(f.browser(), "Org do cadastro");
        UUID conta = contaDaIdentidade(f.identity(), org);

        BrowserClient.Response r = recuperacao(org, f.email());
        assertThat(r.status()).as("conta vinculada: 202 identico").isEqualTo(202);
        assertThat(r.body()).isEmpty();
        assertThat(count("SELECT count(*) FROM password_reset_tokens WHERE user_id = '" + conta + "'")).as("nenhum token").isZero();
        assertThat(mailsDe(f.email(), MailSender.Kind.PASSWORD_RECOVERY)).as("nenhum e-mail").isZero();

        BrowserClient.Response inexistente = recuperacao(org, email());
        assertThat(inexistente.status()).isEqualTo(202);
        assertThat(inexistente.body()).isEmpty();

        TestUser comum = internalUser(new Org(org, null, null, null), true, 2);
        BrowserClient.Response controle = recuperacao(org, comum.email());
        assertThat(controle.status()).as("controle: conta comum").isEqualTo(202);
        assertThat(count("SELECT count(*) FROM password_reset_tokens WHERE user_id = '" + comum.id() + "'")).isEqualTo(1);
        assertThat(mailsDe(comum.email(), MailSender.Kind.PASSWORD_RECOVERY)).isEqualTo(1);
    }

    /**
     * Conta antiga vinculada (canal vinculacao): a senha local continua no banco (DC-17.12), mas o login por organizacao
     * recusa com o 401 generico de sempre, sem criar desafio; a recuperacao responde 202 sem token nem e-mail. Controle:
     * antes do vinculo, a mesma senha abre o desafio.
     */
    @Test
    void contaAntigaVinculadaNaoEntraPelaPortaLocal() throws Exception {
        Org o = newOrganization();
        TestUser antiga = internalUser(o, true, 2);
        firstLogin(browser(), o.id(), antiga);
        BrowserClient antes = browser();
        assertThat(passwordLogin(antes, o.id(), antiga).field("status")).as("controle: antes do vinculo").isEqualTo("mfa_required");

        UUID identidade = identidadeAtiva(antiga.email(), SENHA);
        BrowserClient id = sessaoPelaEntradaGeral(antiga.email(), identidade);
        vincularPeloBanco(id.cookies().get("__Host-nekkora_id"), o.id(), antiga.id());
        assertThat(count("SELECT count(*) FROM user_credentials WHERE user_id = '" + antiga.id() + "'")).as("credencial local mantida, inerte").isEqualTo(1);
        long desafios = count("SELECT count(*) FROM auth_challenges WHERE user_id = '" + antiga.id() + "'");

        BrowserClient depois = browser();
        BrowserClient.Response login = passwordLogin(depois, o.id(), antiga);
        assertThat(login.status()).as("login por organizacao de conta vinculada").isEqualTo(401);
        assertThat(login.body()).isEqualTo(UNAUTH);
        assertThat(count("SELECT count(*) FROM auth_challenges WHERE user_id = '" + antiga.id() + "'")).as("nenhum desafio novo").isEqualTo(desafios);

        BrowserClient.Response errada = passwordLogin(browser(), o.id(), new TestUser(antiga.id(), antiga.email(), "outra-senha-longa-9".toCharArray()));
        assertThat(errada.status()).as("senha errada: mesma resposta").isEqualTo(401);
        assertThat(errada.body()).isEqualTo(UNAUTH);

        BrowserClient.Response r = recuperacao(o.id(), antiga.email());
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.body()).isEmpty();
        assertThat(count("SELECT count(*) FROM password_reset_tokens WHERE user_id = '" + antiga.id() + "' AND revoked_at IS NULL AND consumed_at IS NULL"))
                .as("nenhum token vivo").isZero();
        assertThat(mailsDe(antiga.email(), MailSender.Kind.PASSWORD_RECOVERY)).as("nenhum e-mail").isZero();
    }

    /** Chave da vinculacao DESLIGADA (contexto desta classe): as duas etapas respondem 404, mesmo com sessao e CSRF validos. */
    @Test
    void vinculacaoComAChaveDesligadaE404() throws Exception {
        ComFator f = comFator();
        UUID org = organizacaoDa(f.browser(), "Org sem vinculacao");
        for (String rota : new String[] {"/api/v1/links/start", "/api/v1/links/confirm"}) {
            BrowserClient.Response r = f.browser().post(rota, "{\"organizationId\":\"" + org + "\",\"password\":\"x\",\"code\":\"123456\"}");
            assertThat(r.status()).as(rota).isEqualTo(404);
            assertThat(r.body()).isEqualTo("{\"error\":\"not_found\"}");
        }
        assertThat(count("SELECT count(*) FROM identity_link_challenges WHERE identity_id = '" + f.identity() + "'")).isZero();
    }

    // ------------------------------------------------------------------ apoio

    private BrowserClient.Response recuperacao(UUID org, String email) {
        return client().post("/api/v1/o/" + org + RECUPERACAO, "{\"email\":" + q(email) + "}");
    }

    private long mailsDe(String email, MailSender.Kind kind) {
        return mails(email).stream().filter(m -> m.kind() == kind).count();
    }

    /** Organizacao criada pela funcao da 017 (canal cadastro_web), com step-up gravado na sessao de identidade. */
    static UUID organizacaoDa(BrowserClient b, String nome) throws Exception {
        String token = b.cookies().get("__Host-nekkora_id");
        exec("UPDATE identity_sessions SET mfa_step_up_at = clock_timestamp() WHERE session_token_hash = '" + Tokens.sha256Hex(token) + "'");
        String org = value("SELECT identity_create_organization('" + token + "', '" + nome + "', NULL)");
        assertThat(org).as("organizacao criada pela funcao").isNotNull();
        return UUID.fromString(org);
    }

    static UUID contaDaIdentidade(UUID identity, UUID org) throws Exception {
        return UUID.fromString(value("SELECT u.user_id FROM users u JOIN identity_users iu ON iu.user_id = u.user_id WHERE u.organization_id = '" + org
                + "' AND iu.identity_id = '" + identity + "'"));
    }

    /**
     * Vinculacao pela funcao da 017 (canal vinculacao), como superusuario, com o contexto da organizacao: desafio de
     * vinculacao com a credencial vigente da conta, step-up na sessao de identidade e {@code identity_link_account}.
     */
    static void vincularPeloBanco(String identitySessionToken, UUID org, UUID user) throws Exception {
        String link = Tokens.newToken();
        try (Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT set_config('nekkora.organization_id', ?, true)")) {
                ps.setString(1, org.toString());
                ps.execute();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO identity_link_challenges (identity_id, user_id, token_hash, account_credential_hash, expires_at)
                    SELECT s.identity_id, ?, ?, (SELECT password_hash FROM user_credentials WHERE user_id = ?), now() + interval '5 minutes'
                      FROM identity_sessions s WHERE s.session_token_hash = ?
                    """)) {
                ps.setObject(1, user);
                ps.setString(2, Tokens.sha256Hex(link));
                ps.setObject(3, user);
                ps.setString(4, Tokens.sha256Hex(identitySessionToken));
                assertThat(ps.executeUpdate()).as("desafio de vinculacao").isEqualTo(1);
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE identity_sessions SET mfa_step_up_at = clock_timestamp() WHERE session_token_hash = ?")) {
                ps.setString(1, Tokens.sha256Hex(identitySessionToken));
                ps.executeUpdate();
            }
            c.commit();
        }
        try (Connection c = DB.connectAsSuperuser(); PreparedStatement ps = c.prepareStatement("SELECT identity_link_account(?, ?, ?)")) {
            ps.setString(1, identitySessionToken);
            ps.setString(2, link);
            ps.setObject(3, org);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next() && rs.getBoolean(1)).as("vinculo criado pela funcao da 017").isTrue();
            }
        }
    }
}
