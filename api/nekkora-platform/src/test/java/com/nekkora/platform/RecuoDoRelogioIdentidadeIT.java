package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;

/**
 * Recuo do relogio do banco nos atos de seguranca da identidade (missao PLATFORM-IDENTIDADE-002, frente 1). Mesmo metodo
 * de {@link RecuoDoRelogioOrgIT}: UMA classe de linha com {@code created_at} (ou a marca de recuperacao) a frente do
 * relogio, e o ato que a encerra precisa concluir com todos os efeitos.
 *
 * <p>A troca de fator (T1, DC-17.13) ainda nao tem rota: e exercida pela funcao da 017/018 como a Platform a chamara,
 * {@code identity_mfa_enroll_pending} pela sessao e {@code identity_mfa_replace}, com o papel {@code nekkora_platform}.
 */
class RecuoDoRelogioIdentidadeIT extends IdentityTestBase {

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
    }

    private static void aFrente(String... sqls) throws Exception {
        try (Connection s = DB.connectAsSuperuser()) {
            Barreiras.sql(s, "SET session_replication_role = replica");
            for (String q : sqls) {
                Barreiras.sql(s, q);
            }
        }
    }

    /** Reenvio da confirmacao com o token vivo criado a frente do relogio: o anterior e revogado e o novo enviado. */
    @Test
    void reenvioRevogaTokenCriadoAFrenteDoRelogioDoBanco() throws Exception {
        String e = email();
        assertThat(client().post("/api/v1/signup", "{\"email\":" + q(e) + "}").status()).isEqualTo(202);
        String id = value("SELECT identity_id FROM identities WHERE email_normalized = lower('" + e + "')");
        aFrente("UPDATE identity_email_tokens SET created_at = clock_timestamp() + interval '1 minute', expires_at = expires_at + interval '1 minute'"
                + " WHERE identity_id = '" + id + "'");
        BrowserClient.Response r = client().post("/api/v1/signup/confirmation/resend", "{\"email\":" + q(e) + "}");
        assertThat(r.status()).isEqualTo(202);
        List<SentMessage> m = mails(e);
        if (m.size() != 2) {
            DiagnosticoPg.imprimir(DB, "reenvio");
        }
        assertThat(count("SELECT count(*) FROM identity_email_tokens WHERE identity_id = '" + id + "' AND revoked_at = created_at"))
                .as("anterior revogado, instante ajustado ao created_at").isEqualTo(1);
        assertThat(m).as("confirmacao reenviada").hasSize(2);
        assertThat(client().post("/api/v1/signup/confirm", "{\"token\":" + q(m.get(1).token()) + ",\"password\":" + q(SENHA)
                + ",\"displayName\":\"Pessoa\"}").status()).as("o token novo confirma").isEqualTo(204);
    }

    // ------------------------------------------------------------------ troca de fator (T1)

    private record Troca(UUID identity, String sessao, String fatorNovo) {
    }

    /** Sessao com step-up recente (ou restrita, pelo codigo de recuperacao) e um fator novo pendente cadastrado por ela. */
    private Troca troca(boolean restrita) throws Exception {
        ComFator f = comFator();
        BrowserClient b = f.browser();
        if (restrita) {
            b = client();
            login(b, f.email(), SENHA);
            BrowserClient.Response r = b.post("/api/v1/auth/mfa/verify", "{\"recoveryCode\":\"" + f.codes().get(0) + "\"}");
            assertThat(r.status()).isEqualTo(200);
        } else {
            assertThat(b.post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);
        }
        String sessao = b.cookies().get("__Host-nekkora_id");
        String novo;
        try (Connection c = DB.connectAs("nekkora_platform");
             PreparedStatement ps = c.prepareStatement("SELECT public.identity_mfa_enroll_pending(NULL, ?, ?, ?, 'k1')")) {
            ps.setString(1, sessao);
            ps.setBytes(2, Tokens.newToken().substring(0, 36).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            ps.setBytes(3, Tokens.newToken().substring(0, 12).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                novo = rs.getString(1);
            }
        }
        assertThat(novo).as("fator novo pendente").isNotNull();
        return new Troca(f.identity(), sessao, novo);
    }

    /** {@code identity_mfa_replace} como nekkora_platform: true/false, ou o SQLSTATE da falha. */
    private static String substituir(Troca t) throws Exception {
        try (Connection c = DB.connectAs("nekkora_platform")) {
            String[] hashes = IntStream.range(0, 10).mapToObj(i -> Tokens.sha256Hex(Tokens.newToken())).toArray(String[]::new);
            Array a = c.createArrayOf("text", hashes);
            try (PreparedStatement ps = c.prepareStatement("SELECT public.identity_mfa_replace(?, ?::uuid, ?)")) {
                ps.setString(1, t.sessao());
                ps.setString(2, t.fatorNovo());
                ps.setArray(3, a);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return String.valueOf(rs.getBoolean(1));
                }
            } catch (java.sql.SQLException e) {
                DiagnosticoPg.imprimir(DB, "troca de fator");
                return "SQLSTATE " + e.getSQLState();
            }
        }
    }

    private static void efeitosDaTroca(Troca t) throws Exception {
        String i = "'" + t.identity() + "'";
        assertThat(value("SELECT factor_id::text FROM identity_mfa_factors WHERE identity_id = " + i + " AND confirmed_at IS NOT NULL AND disabled_at IS NULL"))
                .as("o fator novo e o unico ativo").isEqualTo(t.fatorNovo());
        assertThat(count("SELECT count(*) FROM identity_mfa_recovery_codes WHERE identity_id = " + i + " AND used_at IS NULL AND invalidated_at IS NULL"
                + " AND factor_id = '" + t.fatorNovo() + "'")).as("codigos novos").isEqualTo(10);
        assertThat(count("SELECT count(*) FROM identity_mfa_recovery_codes WHERE identity_id = " + i + " AND used_at IS NULL AND invalidated_at IS NULL"
                + " AND factor_id <> '" + t.fatorNovo() + "'")).as("codigos antigos invalidados").isZero();
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = " + i + " AND event_type = 11")).as("evento da troca").isEqualTo(1);
        assertThat(value("SELECT (recovery_used_at IS NULL OR recovery_cleared_at IS NOT NULL)::text FROM identity_sessions WHERE session_token_hash = '"
                + Tokens.sha256Hex(t.sessao()) + "'")).as("sessao liberada").isEqualTo("true");
    }

    /** Fator ANTIGO criado a frente do relogio: desabilitado com disabled_at = created_at. */
    @Test
    void trocaDesabilitaFatorAntigoCriadoAFrenteDoRelogioDoBanco() throws Exception {
        Troca t = troca(false);
        aFrente("UPDATE identity_mfa_factors SET created_at = clock_timestamp() + interval '1 minute', confirmed_at = clock_timestamp() + interval '1 minute'"
                + " WHERE identity_id = '" + t.identity() + "' AND confirmed_at IS NOT NULL AND disabled_at IS NULL");
        assertThat(substituir(t)).as("troca com o fator antigo a frente do relogio").isEqualTo("true");
        efeitosDaTroca(t);
        assertThat(count("SELECT count(*) FROM identity_mfa_factors WHERE identity_id = '" + t.identity() + "' AND confirmed_at IS NOT NULL"
                + " AND disabled_at = created_at")).as("instante ajustado").isEqualTo(1);
    }

    /** Fator NOVO (pendente) criado a frente do relogio: confirmado com confirmed_at = created_at. */
    @Test
    void trocaConfirmaFatorNovoCriadoAFrenteDoRelogioDoBanco() throws Exception {
        Troca t = troca(false);
        aFrente("UPDATE identity_mfa_factors SET created_at = clock_timestamp() + interval '1 minute' WHERE factor_id = '" + t.fatorNovo() + "'");
        assertThat(substituir(t)).as("troca com o fator novo a frente do relogio").isEqualTo("true");
        efeitosDaTroca(t);
        assertThat(count("SELECT count(*) FROM identity_mfa_factors WHERE factor_id = '" + t.fatorNovo() + "' AND confirmed_at = created_at"))
                .as("instante ajustado").isEqualTo(1);
    }

    /**
     * Sessao RESTRITA (codigo de recuperacao) com a marca de recuperacao a frente do relogio: a troca a libera com
     * recovery_cleared_at = recovery_used_at. Sem isso, a unica saida da sessao restrita falhava.
     */
    @Test
    void trocaLiberaSessaoRestritaComMarcaAFrenteDoRelogioDoBanco() throws Exception {
        Troca t = troca(true);
        String h = Tokens.sha256Hex(t.sessao());
        aFrente("UPDATE identity_sessions SET created_at = clock_timestamp() + interval '1 minute', last_seen_at = clock_timestamp() + interval '1 minute',"
                + " mfa_verified_at = clock_timestamp() + interval '1 minute', recovery_used_at = clock_timestamp() + interval '1 minute'"
                + " WHERE session_token_hash = '" + h + "'");
        assertThat(substituir(t)).as("troca na sessao restrita com a marca a frente do relogio").isEqualTo("true");
        efeitosDaTroca(t);
        assertThat(count("SELECT count(*) FROM identity_sessions WHERE session_token_hash = '" + h + "' AND recovery_cleared_at = recovery_used_at"))
                .as("instante ajustado").isEqualTo(1);
    }

    /** Controle negativo: com as mesmas linhas a frente do relogio, a troca SEM step-up recente continua recusada e nada muda. */
    @Test
    void trocaSemProvaContinuaRecusadaComLinhasAFrenteDoRelogio() throws Exception {
        Troca t = troca(false);
        aFrente("UPDATE identity_sessions SET mfa_step_up_at = clock_timestamp() - interval '6 minutes' WHERE session_token_hash = '"
                + Tokens.sha256Hex(t.sessao()) + "'",
                "UPDATE identity_mfa_factors SET created_at = clock_timestamp() + interval '1 minute', confirmed_at = clock_timestamp() + interval '1 minute'"
                + " WHERE identity_id = '" + t.identity() + "' AND confirmed_at IS NOT NULL AND disabled_at IS NULL");
        assertThat(substituir(t)).as("sem step-up recente").isEqualTo("false");
        assertThat(count("SELECT count(*) FROM identity_mfa_factors WHERE identity_id = '" + t.identity() + "' AND confirmed_at IS NOT NULL AND disabled_at IS NULL"
                + " AND factor_id <> '" + t.fatorNovo() + "'")).as("o fator antigo continua ativo").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + t.identity() + "' AND event_type = 11")).isZero();
    }
}
