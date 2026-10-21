package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.platform.mail.MailSender;

/**
 * Recuo do relogio do banco nos atos de seguranca da entrada por organizacao (missao PLATFORM-IDENTIDADE-002, frente 1).
 * Cada cenario coloca o {@code created_at} de UMA classe de linha a frente do relogio (superusuario, triggers desligadas na
 * conexao do teste, CHECKs valendo) - o estado que um passo para tras de {@code clock_timestamp()} entre duas transacoes
 * produz - e executa o ato que encerra essa linha. O ato precisa concluir com todos os efeitos; o instante terminal
 * gravado e o {@code created_at} (ajustado para a CHECK, nao o instante exato do ato).
 */
class RecuoDoRelogioOrgIT extends PlatformTestBase {

    private static long count(String sql) throws Exception {
        return AuthenticationFlowIT.count(sql);
    }

    private static String value(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); java.sql.Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void aFrente(String... sqls) throws Exception {
        try (Connection s = DB.connectAsSuperuser()) {
            Barreiras.sql(s, "SET session_replication_role = replica");
            for (String q : sqls) {
                Barreiras.sql(s, q);
            }
        }
    }

    private record Alvo(Org org, BrowserClient admin, TestUser user) {
        String base() {
            return "/api/v1/o/" + org.id();
        }
    }

    /** ORG_ADMIN com step-up recente e um membro interno com fator confirmado e codigos de recuperacao. */
    private Alvo alvo() {
        Org o = newOrganization();
        TestUser adm = new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray());
        BrowserClient a = browser();
        firstLogin(a, o.id(), adm);
        assertThat(a.post("/api/v1/o/" + o.id() + "/auth/mfa/step-up", "{\"code\":\"" + code(adm.id()) + "\"}").status()).isEqualTo(204);
        TestUser u = internalUser(o, true, 2);
        firstLogin(browser(), o.id(), u);
        return new Alvo(o, a, u);
    }

    private BrowserClient.Response resetar(Alvo c) throws Exception {
        BrowserClient.Response r = Barreiras.postLongo(port, c.admin(), c.base() + "/users/" + c.user().id() + "/mfa/reset",
                SessionsAndAdministrationIT.RESET_BODY);
        if (r.status() != 204) {
            DiagnosticoPg.imprimir(DB, "reset " + r.status());
        }
        return r;
    }

    private void efeitosDoReset(Alvo c) throws Exception {
        UUID u = c.user().id();
        assertThat(count("SELECT count(*) FROM user_mfa_factors WHERE user_id = '" + u + "' AND disabled_at IS NULL")).as("fator desabilitado").isZero();
        assertThat(count("SELECT count(*) FROM user_mfa_recovery_codes WHERE user_id = '" + u + "' AND used_at IS NULL AND invalidated_at IS NULL"))
                .as("codigos invalidados").isZero();
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + u + "' AND revoked_at IS NULL")).as("sessoes revogadas").isZero();
        assertThat(count("SELECT count(*) FROM mfa_events WHERE user_id = '" + u + "' AND event_type = 3 AND reason_code = 1")).as("evento").isEqualTo(1);
        assertThat(testMail.sent.stream().filter(m -> m.kind() == MailSender.Kind.NOTICE_MFA_RESET && c.user().email().equals(m.recipient())).count())
                .as("aviso ao titular").isEqualTo(1);
    }

    /** Reset administrativo com o FATOR do alvo criado a frente do relogio: desabilitado com disabled_at = created_at. */
    @Test
    void resetDesabilitaFatorCriadoAFrenteDoRelogioDoBanco() throws Exception {
        Alvo c = alvo();
        aFrente("UPDATE user_mfa_factors SET created_at = clock_timestamp() + interval '1 minute', confirmed_at = clock_timestamp() + interval '1 minute'"
                + " WHERE user_id = '" + c.user().id() + "' AND disabled_at IS NULL");
        assertThat(count("SELECT count(*) FROM user_mfa_factors WHERE user_id = '" + c.user().id() + "' AND created_at > clock_timestamp()")).isEqualTo(1);
        assertThat(resetar(c).status()).as("reset com o fator a frente do relogio").isEqualTo(204);
        efeitosDoReset(c);
        assertThat(count("SELECT count(*) FROM user_mfa_factors WHERE user_id = '" + c.user().id() + "' AND disabled_at = created_at"))
                .as("instante ajustado ao created_at").isEqualTo(1);
    }

    /** Reset administrativo com os CODIGOS de recuperacao criados a frente do relogio: invalidados com invalidated_at = created_at. */
    @Test
    void resetInvalidaCodigosCriadosAFrenteDoRelogioDoBanco() throws Exception {
        Alvo c = alvo();
        long codigos = count("SELECT count(*) FROM user_mfa_recovery_codes WHERE user_id = '" + c.user().id() + "' AND used_at IS NULL AND invalidated_at IS NULL");
        assertThat(codigos).as("codigos emitidos no cadastro do fator").isPositive();
        aFrente("UPDATE user_mfa_recovery_codes SET created_at = clock_timestamp() + interval '1 minute' WHERE user_id = '" + c.user().id() + "'");
        assertThat(resetar(c).status()).as("reset com os codigos a frente do relogio").isEqualTo(204);
        efeitosDoReset(c);
        assertThat(count("SELECT count(*) FROM user_mfa_recovery_codes WHERE user_id = '" + c.user().id() + "' AND invalidated_at = created_at"))
                .as("instante ajustado ao created_at").isEqualTo(codigos);
    }

    /** Revogacao de concessao externa criada a frente do relogio: revogada (204), revoked_at = created_at. */
    @Test
    void revogacaoDeConcessaoCriadaAFrenteDoRelogioDoBanco() throws Exception {
        Org o = newOrganization();
        UUID client = newClient(o);
        UUID ws = newWorkspace(o, client);
        externalUser(o, client, ws);
        String grant = value("SELECT grant_id::text FROM client_workspace_grants WHERE organization_id = '" + o.id()
                + "' AND workspace_id = '" + ws + "'");
        aFrente("UPDATE client_workspace_grants SET created_at = clock_timestamp() + interval '1 minute' WHERE grant_id = '" + grant + "'");
        BrowserClient a = browser();
        firstLogin(a, o.id(), new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray()));
        BrowserClient.Response r = a.post("/api/v1/o/" + o.id() + "/client-grants/" + grant + "/revoke", null);
        if (r.status() != 204) {
            DiagnosticoPg.imprimir(DB, "revogacao de concessao " + r.status());
        }
        assertThat(r.status()).as("revogacao com a concessao a frente do relogio").isEqualTo(204);
        assertThat(count("SELECT count(*) FROM client_workspace_grants WHERE grant_id = '" + grant + "' AND status = 4 AND revoked_at = created_at"))
                .as("revogada, instante ajustado ao created_at").isEqualTo(1);
    }

    /**
     * Pedido de recuperacao de senha com o token anterior criado a frente do relogio: o anterior e revogado, o novo emitido
     * e enviado. Antes da correcao a revogacao recusava (23514) e o tratamento da recusa de conta vinculada engolia a falha:
     * 202 sem token novo, sem e-mail e com o token anterior vivo.
     */
    @Test
    void pedidoDeRecuperacaoRevogaTokenCriadoAFrenteDoRelogioDoBanco() throws Exception {
        Org o = newOrganization();
        TestUser u = internalUser(o, true, 2);
        String pedido = "/api/v1/o/" + o.id() + "/auth/password-reset/request";
        String corpo = "{\"email\":\"" + u.email() + "\"}";
        BrowserClient x = browser();
        fetchPreAuthCsrf(x);
        assertThat(x.post(pedido, corpo).status()).isEqualTo(202);
        assertThat(count("SELECT count(*) FROM password_reset_tokens WHERE user_id = '" + u.id() + "' AND revoked_at IS NULL AND consumed_at IS NULL")).isEqualTo(1);
        aFrente("UPDATE password_reset_tokens SET created_at = clock_timestamp() + interval '1 minute', expires_at = expires_at + interval '1 minute'"
                + " WHERE user_id = '" + u.id() + "'");
        BrowserClient y = browser();
        fetchPreAuthCsrf(y);
        BrowserClient.Response r = y.post(pedido, corpo);
        assertThat(r.status()).isEqualTo(202);
        if (count("SELECT count(*) FROM password_reset_tokens WHERE user_id = '" + u.id() + "'") != 2) {
            DiagnosticoPg.imprimir(DB, "pedido de recuperacao");
        }
        assertThat(count("SELECT count(*) FROM password_reset_tokens WHERE user_id = '" + u.id() + "' AND revoked_at = created_at"))
                .as("anterior revogado, instante ajustado ao created_at").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM password_reset_tokens WHERE user_id = '" + u.id() + "' AND revoked_at IS NULL AND consumed_at IS NULL"))
                .as("um token novo vivo").isEqualTo(1);
        assertThat(testMail.sent.stream().filter(m -> m.kind() == MailSender.Kind.PASSWORD_RECOVERY && u.email().equals(m.recipient())).count())
                .as("dois e-mails de recuperacao").isEqualTo(2);
    }
}
