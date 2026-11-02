package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;
import com.nekkora.platform.auth.crypto.Base32;
import com.nekkora.platform.mail.MailSender;

/**
 * 029 - segundo fator OPCIONAL (missao PLATFORM-LOCAL-002, item 2; politica da titular de 2026-11-02). Os quatro aceites
 * pedidos, cada um conferindo a resposta E o estado persistido (superusuario, sem RLS):
 * <ol>
 * <li>conta nova sem MFA: confirma o e-mail, entra so com a senha (sem desafio, sem QR) e chega a aplicacao; a acao
 * sensivel exige a senha reconfirmada;</li>
 * <li>ativacao voluntaria posterior: senha reconfirmada -> QR -> TOTP; todas as sessoes anteriores revogadas; o login
 * seguinte pede o codigo;</li>
 * <li>conta ja protegida continua pedindo o codigo: a senha nunca substitui o TOTP (login, step-up, criacao, organizacao);</li>
 * <li>acao sensivel sem a confirmacao exigida: 403, sem efeito.</li>
 * </ol>
 * A recuperacao de senha que preserva o fator ja e coberta por IdentityPasswordRecoveryIT (identidade com MFA); aqui, o
 * caso da identidade que ATIVOU o MFA pela sessao.
 */
@DirtiesContext
class MfaOpcionalIT extends IdentityTestBase {

    private static final String FORBIDDEN = "{\"error\":\"forbidden\"}";

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.criacao-organizacao.enabled", () -> "true");
        r.add("nekkora.convites.enabled", () -> "true");
        r.add("nekkora.recuperacao-senha.enabled", () -> "true");
    }

    // ------------------------------------------------------------------ 1. conta nova sem MFA

    @Test
    void contaNovaEntraSoComASenhaSemDesafioESemQr() throws Exception {
        String e = email();
        UUID id = confirmed(e, SENHA);
        BrowserClient b = client();
        BrowserClient.Response r = login(b, e, SENHA);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.field("status")).isEqualTo("authenticated");
        assertThat(r.field("csrfToken")).hasSize(43);
        assertThat(cookie(r, "__Host-nekkora_id")).as("sessao direto no passo 1").contains("Secure").contains("HttpOnly").contains("SameSite=Lax");
        assertThat(cookie(r, "__Host-nekkora_id_mfa")).as("nenhum desafio: o cookie, se vier, so expira").satisfiesAnyOf(
                c -> assertThat(c).isNull(), c -> assertThat(c).contains("Max-Age=0"));
        assertThat(value("SELECT (mfa_verified_at IS NULL) || '|' || (password_step_up_at IS NULL) FROM identity_sessions WHERE identity_id = '" + id
                + "' AND revoked_at IS NULL")).as("sessao so de senha").isEqualTo("true|true");
        assertThat(value("SELECT count(*) FROM identity_auth_challenges WHERE identity_id = '" + id + "' AND consumed_at IS NULL"))
                .as("desafio consumido na mesma transacao").isEqualTo("0");
        assertThat(eventos(id, 18)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM identity_mfa_factors WHERE identity_id = '" + id + "'")).as("nenhum fator criado").isZero();

        BrowserClient.Response s = b.get("/api/v1/me/session");
        assertThat(s.status()).isEqualTo(200);
        assertThat(s.body()).contains("\"mfaEnabled\":false").contains("\"stepUpMethod\":\"password\"").contains("\"restricted\":false");
        BrowserClient.Response orgs = b.get("/api/v1/me/organizations");
        assertThat(orgs.status()).isEqualTo(200);
        assertThat(orgs.body()).as("lista vazia e a criacao indicada").isEqualTo("{\"organizations\":[],\"canCreateOrganization\":true}");
    }

    @Test
    void criarOrganizacaoSemMfaExigeASenhaReconfirmada() throws Exception {
        SemFator p = semFator();
        // 4. sem a confirmacao exigida: 403, nada criado
        BrowserClient.Response sem = criar(p.browser(), "Sem confirmacao");
        assertThat(sem.status()).isEqualTo(403);
        assertThat(sem.body()).isEqualTo(FORBIDDEN);
        assertThat(count("SELECT count(*) FROM identity_users WHERE identity_id = '" + p.identity() + "'")).isZero();
        // senha errada: 403, sem marca, sem evento
        BrowserClient.Response errada = p.browser().post("/api/v1/auth/password-confirm", "{\"password\":\"outra-senha-qualquer\"}");
        assertThat(errada.status()).isEqualTo(403);
        assertThat(marcaSenha(p.browser())).isEqualTo("f");
        assertThat(eventos(p.identity(), 19)).isZero();
        // senha certa: 204, marca e evento 19; a criacao passa
        assertThat(reconfirmar(p.browser()).status()).isEqualTo(204);
        assertThat(marcaSenha(p.browser())).isEqualTo("t");
        assertThat(eventos(p.identity(), 19)).isEqualTo(1);
        BrowserClient.Response ok = criar(p.browser(), "Primeira organizacao");
        assertThat(ok.status()).as(ok.body()).isEqualTo(201);
        UUID org = UUID.fromString(ok.field("organizationId"));
        assertThat(p.browser().get("/api/v1/me/organizations").body()).contains(org.toString()).contains("AGUARDANDO_ATIVACAO");
    }

    @Test
    void naOrganizacaoSemMfaAAcaoSensivelPedeASenhaENaoOCodigo() throws Exception {
        SemFator p = semFator();
        UUID org = organizacao(p);
        BrowserClient b = p.browser();
        BrowserClient.Response troca = b.post("/api/v1/sessions/organization", "{\"organizationId\":\"" + org + "\"}");
        assertThat(troca.field("status")).isEqualTo("sessao_criada");
        b.csrf = troca.field("csrfToken");
        String orgSessao = Tokens.sha256Hex(b.cookies().get("__Host-nekkora_session"));
        assertThat(value("SELECT (mfa_verified_at IS NULL) || '|' || (identity_session_id IS NOT NULL) FROM user_sessions WHERE session_token_hash = '"
                + orgSessao + "'")).as("sessao vinculada sem MFA, herdado da sessao de identidade").isEqualTo("true|true");
        BrowserClient.Response me = b.get("/api/v1/o/" + org + "/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body()).contains("\"stepUpMethod\":\"password\"");

        // convite (acao sensivel com step-up): sem a senha reconfirmada NESTA sessao de organizacao, 403 sem efeito
        String convidado = email();
        assertThat(convidar(b, org, convidado).status()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM identity_invitations WHERE organization_id = '" + org + "'")).isZero();
        // a reconfirmacao da sessao de IDENTIDADE nao vale na de organizacao (a prova e da sessao)
        // TOTP nao existe para esta identidade: o step-up por codigo e recusado
        assertThat(b.post("/api/v1/o/" + org + "/auth/mfa/step-up", "{\"code\":\"123456\"}").status()).isEqualTo(403);
        BrowserClient.Response pc = b.post("/api/v1/o/" + org + "/auth/password-confirm", "{\"password\":" + q(SENHA) + "}");
        assertThat(pc.status()).as(pc.body()).isEqualTo(204);
        assertThat(value("SELECT password_step_up_at IS NOT NULL FROM user_sessions WHERE session_token_hash = '" + orgSessao + "'")).isEqualTo("t");
        assertThat(value("SELECT count(*) FROM identity_events WHERE identity_id = '" + p.identity() + "' AND event_type = 19 AND target_organization_id = '"
                + org + "'")).isEqualTo("1");
        BrowserClient.Response c = convidar(b, org, convidado);
        assertThat(c.status()).as(c.body()).isEqualTo(201);

        // a segunda pessoa, tambem sem MFA, aceita o convite com a senha reconfirmada
        SemFator segunda = semFatorCom(convidado);
        String token = ultimoConvite(convidado);
        BrowserClient.Response semSenha = segunda.browser().post("/api/v1/invitations/accept", "{\"organizationId\":\"" + org + "\",\"token\":" + q(token) + "}");
        // contrato do aceite (023): qualquer recusa, inclusive sem a prova recente, e o 404 unico, sem efeito
        assertThat(semSenha.status()).as("aceite sem a senha reconfirmada").isEqualTo(404);
        assertThat(segunda.browser().get("/api/v1/me/organizations").body()).doesNotContain(org.toString());
        assertThat(reconfirmar(segunda.browser()).status()).isEqualTo(204);
        BrowserClient.Response aceite = segunda.browser().post("/api/v1/invitations/accept", "{\"organizationId\":\"" + org + "\",\"token\":" + q(token) + "}");
        assertThat(aceite.status()).as(aceite.body()).isEqualTo(200);
        assertThat(segunda.browser().get("/api/v1/me/organizations").body()).contains(org.toString());
    }

    // ------------------------------------------------------------------ 2. ativacao voluntaria posterior

    @Test
    void ativacaoVoluntariaRevogaAsSessoesSemMfaEOLoginSeguintePedeOCodigo() throws Exception {
        SemFator p = semFator();
        BrowserClient outra = client();
        assertThat(login(outra, p.email(), SENHA).field("status")).isEqualTo("authenticated");
        UUID org = organizacao(p);
        BrowserClient b = p.browser();
        String idAntiga = b.cookies().get("__Host-nekkora_id");

        // sem a senha reconfirmada NESTA sessao: o inicio e recusado (a outra sessao nunca reconfirmou)
        assertThat(outra.post("/api/v1/auth/mfa/activate/start", null).status()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM identity_mfa_factors WHERE identity_id = '" + p.identity() + "'")).isZero();

        assertThat(reconfirmar(b).status()).isEqualTo(204);
        BrowserClient.Response start = b.post("/api/v1/auth/mfa/activate/start", null);
        assertThat(start.status()).isEqualTo(200);
        assertThat(start.field("otpauthUri")).startsWith("otpauth://totp/");
        String segredo = URI.create(start.field("otpauthUri")).getRawQuery().replaceAll(".*secret=([A-Z2-7]+).*", "$1");
        SECRETS.put(p.identity(), Base32.decode(segredo));
        // codigo errado: 403, fator continua pendente
        assertThat(b.post("/api/v1/auth/mfa/activate/confirm", "{\"code\":\"000000\"}").status()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM identity_mfa_factors WHERE identity_id = '" + p.identity() + "' AND confirmed_at IS NOT NULL")).isZero();

        BrowserClient.Response ok = b.post("/api/v1/auth/mfa/activate/confirm", "{\"code\":\"" + code(p.identity()) + "\"}");
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        List<String> codigos = ok.listField("recoveryCodes");
        assertThat(codigos).hasSizeGreaterThanOrEqualTo(8);
        assertThat(cookie(ok, "__Host-nekkora_id")).as("sessao nova no cookie").isNotNull();
        assertThat(b.cookies().get("__Host-nekkora_id")).isNotEqualTo(idAntiga);
        b.csrf = ok.field("csrfToken");
        assertThat(eventos(p.identity(), 20)).isEqualTo(1);
        assertThat(value("SELECT count(*) FROM identity_sessions WHERE identity_id = '" + p.identity() + "' AND revoked_at IS NULL")).isEqualTo("1");
        assertThat(value("SELECT mfa_verified_at IS NOT NULL FROM identity_sessions WHERE identity_id = '" + p.identity() + "' AND revoked_at IS NULL"))
                .isEqualTo("t");
        assertThat(outra.get("/api/v1/me/session").status()).as("a outra sessao sem MFA caiu").isEqualTo(401);
        BrowserClient.Response s = b.get("/api/v1/me/session");
        assertThat(s.body()).contains("\"mfaEnabled\":true").contains("\"stepUpMethod\":\"totp\"");

        // agora a senha nao substitui mais o codigo
        assertThat(reconfirmar(b).status()).isEqualTo(403);
        BrowserClient nova = client();
        assertThat(login(nova, p.email(), SENHA).field("status")).isEqualTo("mfa_required");
        assertThat(verifyTotp(nova, p.identity()).status()).isEqualTo(200);
        assertThat(nova.get("/api/v1/me/organizations").body()).as("dados preservados").contains(org.toString());
    }

    // ------------------------------------------------------------------ 3. conta ja protegida

    @Test
    void contaComMfaContinuaPedindoOCodigoEASenhaNuncaSubstitui() throws Exception {
        ComFator f = comFator();
        BrowserClient b = client();
        BrowserClient.Response l = login(b, f.email(), SENHA);
        assertThat(l.field("status")).isEqualTo("mfa_required");
        assertThat(cookie(l, "__Host-nekkora_id")).as("nenhuma sessao sem o codigo").isNull();
        assertThat(b.get("/api/v1/me/session").status()).isEqualTo(401);
        assertThat(verifyTotp(b, f.identity()).status()).isEqualTo(200);
        assertThat(b.get("/api/v1/me/session").body()).contains("\"mfaEnabled\":true").contains("\"stepUpMethod\":\"totp\"");

        // reconfirmacao da senha: recusada para quem tem fator; criacao sem step-up TOTP continua 403
        long reconfirmacoes = eventos(f.identity(), 19); // a da propria ativacao (comFator ativa pela sessao)
        BrowserClient.Response pc = reconfirmar(f.browser());
        assertThat(pc.status()).isEqualTo(403);
        assertThat(marcaSenha(f.browser())).isEqualTo("f");
        assertThat(eventos(f.identity(), 19)).as("nenhuma reconfirmacao nova").isEqualTo(reconfirmacoes);
        assertThat(criar(f.browser(), "Sem codigo").status()).isEqualTo(403);
        assertThat(f.browser().post("/api/v1/auth/mfa/activate/start", null).status()).as("ativacao so para quem nao tem fator").isEqualTo(403);
        // com o TOTP, como antes
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);
        BrowserClient.Response ok = criar(f.browser(), "Com codigo");
        assertThat(ok.status()).isEqualTo(201);
        UUID org = UUID.fromString(ok.field("organizationId"));

        // na organizacao: sessao com MFA; a rota de senha e recusada e o convite continua exigindo o TOTP
        BrowserClient.Response troca = f.browser().post("/api/v1/sessions/organization", "{\"organizationId\":\"" + org + "\"}");
        f.browser().csrf = troca.field("csrfToken");
        assertThat(f.browser().get("/api/v1/o/" + org + "/me").body()).contains("\"stepUpMethod\":\"totp\"");
        assertThat(f.browser().post("/api/v1/o/" + org + "/auth/password-confirm", "{\"password\":" + q(SENHA) + "}").status()).isEqualTo(403);
        assertThat(convidar(f.browser(), org, email()).status()).isEqualTo(403);
    }

    /** O banco recusa, por qualquer papel que nao o dono, a marca de senha e a sessao sem MFA para quem tem fator. */
    @Test
    void bancoRecusaAMarcaDeSenhaForaDaFuncaoESessaoSemMfaDeQuemTemFator() throws Exception {
        ComFator f = comFator();
        String hash = Tokens.sha256Hex(f.browser().cookies().get("__Host-nekkora_id"));
        // superusuario NAO e o dono: a trigger recusa (42501)
        assertThat(execErro("UPDATE identity_sessions SET password_step_up_at = clock_timestamp() WHERE session_token_hash = '" + hash + "'"))
                .contains("reconfirmacao da senha so por identity_password_reconfirm");
        assertThat(execErro("INSERT INTO identity_sessions (identity_id, session_token_hash, expires_at, mfa_verified_at, csrf_token) VALUES ('"
                + f.identity() + "', '" + "a".repeat(64) + "', now() + interval '1 hour', NULL, '" + "b".repeat(43) + "')"))
                .contains("sessao sem segundo fator para identidade com fator confirmado");
    }

    // ------------------------------------------------------------------ recuperacao de senha preserva o fator ativado

    @Test
    void recuperacaoDeSenhaNaoRemoveOFatorAtivadoPelaSessao() throws Exception {
        SemFator p = semFator();
        BrowserClient.Response a = enroll(p.browser(), p.identity());
        assertThat(a.status()).isEqualTo(200);
        String fatorAntes = value("SELECT factor_id || '|' || (confirmed_at IS NOT NULL) || '|' || (disabled_at IS NULL) FROM identity_mfa_factors WHERE identity_id = '"
                + p.identity() + "' AND confirmed_at IS NOT NULL");
        BrowserClient pre = client();
        assertThat(pre.post("/api/v1/auth/password-reset/request", "{\"email\":" + q(p.email()) + "}").status()).isEqualTo(202);
        idle();
        String token = mails(p.email()).stream().filter(m -> m.kind() == MailSender.Kind.PASSWORD_RECOVERY).reduce((x, y) -> y).map(SentMessage::token).orElseThrow();
        String nova = "senha-nova-da-identidade-2";
        assertThat(client().post("/api/v1/auth/password-reset/confirm", "{\"token\":" + q(token) + ",\"newPassword\":" + q(nova) + "}").status()).isEqualTo(204);
        assertThat(value("SELECT factor_id || '|' || (confirmed_at IS NOT NULL) || '|' || (disabled_at IS NULL) FROM identity_mfa_factors WHERE identity_id = '"
                + p.identity() + "' AND confirmed_at IS NOT NULL")).as("fator intocado").isEqualTo(fatorAntes);
        BrowserClient b = client();
        assertThat(login(b, p.email(), nova).field("status")).as("a entrada seguinte exige o fator").isEqualTo("mfa_required");
    }

    // ------------------------------------------------------------------ apoio

    private record SemFator(UUID identity, String email, BrowserClient browser) {
    }

    private SemFator semFator() throws Exception {
        return semFatorCom(email());
    }

    private SemFator semFatorCom(String e) throws Exception {
        UUID id = confirmed(e, SENHA);
        BrowserClient b = client();
        assertThat(login(b, e, SENHA).field("status")).isEqualTo("authenticated");
        return new SemFator(id, e, b);
    }

    private UUID organizacao(SemFator p) {
        assertThat(reconfirmar(p.browser()).status()).isEqualTo(204);
        BrowserClient.Response r = criar(p.browser(), "Org " + UUID.randomUUID().toString().substring(0, 6));
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return UUID.fromString(r.field("organizationId"));
    }

    private BrowserClient.Response reconfirmar(BrowserClient b) {
        return b.post("/api/v1/auth/password-confirm", "{\"password\":" + q(SENHA) + "}");
    }

    private static BrowserClient.Response criar(BrowserClient b, String nome) {
        return b.post("/api/v1/organizations", "{\"name\":" + q(nome) + "}");
    }

    private static BrowserClient.Response convidar(BrowserClient b, UUID org, String email) {
        return b.post("/api/v1/o/" + org + "/invitations", "{\"email\":" + q(email) + "}");
    }

    private String ultimoConvite(String email) {
        idle();
        List<SentMessage> m = mails(email).stream().filter(x -> x.kind() == MailSender.Kind.ORG_INVITATION).toList();
        assertThat(m).as("convite enviado a " + email).isNotEmpty();
        return m.get(m.size() - 1).token();
    }

    private static String marcaSenha(BrowserClient b) throws Exception {
        return value("SELECT password_step_up_at IS NOT NULL FROM identity_sessions WHERE session_token_hash = '"
                + Tokens.sha256Hex(b.cookies().get("__Host-nekkora_id")) + "'");
    }

    private static long eventos(UUID identity, int tipo) throws Exception {
        return count("SELECT count(*) FROM identity_events WHERE identity_id = '" + identity + "' AND event_type = " + tipo);
    }

    private static String execErro(String sql) {
        try {
            exec(sql);
            return "";
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }
}
