package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.cli.ExecutorAtos.Classe;
import com.nekkora.ops.cli.ExecutorAtos.Resultado;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Reset operacional (016) de conta VINCULADA a uma identidade global (024): recusa declarada, motivo CONTA_VINCULADA, na
 * abertura e na execucao, sem tocar o fator, os codigos, os desafios e as sessoes da conta, o fator e as sessoes da
 * identidade, nem criar aviso ou evento de reset. Controle positivo: a mesma solicitacao numa conta NAO vinculada executa.
 *
 * Fixture declarada (superusuario): a vinculacao e montada direto no banco, no estado que a vinculacao real deixa (017):
 * entrada local fechada (fator desabilitado, codigos e desafios invalidados, sessoes da porta antiga revogadas), ligacao
 * identidade-conta, fator confirmado e sessao da identidade, e uma sessao da organizacao nascida da identidade (ramo 2 da
 * 4.4). Sem a 024 a execucao tambem era recusada, mas so por efeito (FATOR_DIVERGENTE: a vinculacao desabilita o fator
 * local); a abertura caia em SEM_FATOR_CONFIRMADO. As duas corridas com a vinculacao usam a funcao REAL da 017.
 */
class ResetContaVinculadaIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);

    private record Vinculo(UUID identidade, UUID sessaoIdentidade) {
    }

    @Test
    void aberturaDeContaVinculadaRecusadaSemEfeito() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID org = al.org().id();
        Vinculo v = vincular(al.org(), al.titular());
        String acessoAntes = C.acesso(org, al.titular());
        assertThat(acessoAntes).as("conta vinculada: so a sessao nascida da identidade").isEqualTo("0|0|0|1");
        String identidadeAntes = identidade(v);
        String contaAntes = C.identidade(org, al.titular());

        Resultado r = C.abrirPorRetorno(eq.v(), org, al.titular(), al.contatoTitular());
        assertThat(r.classe()).as("%s", r).isEqualTo(Classe.RECUSA);
        assertThat(r.motivo()).isEqualTo("CONTA_VINCULADA");

        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_requests WHERE organization_id = ?", org)).isZero();
        assertThat(avisos(org)).isZero();
        assertThat(C.acesso(org, al.titular())).isEqualTo(acessoAntes);
        assertThat(identidade(v)).isEqualTo(identidadeAntes);
        assertThat(C.identidade(org, al.titular())).isEqualTo(contaAntes);
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM public.mfa_events WHERE organization_id = ? AND user_id = ? AND event_type = 4",
                org, al.titular())).isZero();
    }

    /**
     * Solicitacao APROVADA numa conta ainda nao vinculada; a conta e vinculada depois (fixture declarada) e a execucao e
     * recusada com CONTA_VINCULADA (RECUSADA), antes da conferencia do fator. Nada muda na conta nem na identidade; nenhum
     * aviso novo, nenhum evento de reset.
     */
    @Test
    void execucaoDeContaVinculadaDepoisDaAprovacaoRecusadaSemEfeito() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID org = al.org().id();
        UUID s = C.aprovada(eq, al);
        Vinculo v = vincular(al.org(), al.titular());
        String acessoAntes = C.acesso(org, al.titular());
        assertThat(acessoAntes).isEqualTo("0|0|0|1");
        String identidadeAntes = identidade(v);
        String contaAntes = C.identidade(org, al.titular());
        long avisosAntes = avisos(org);

        Resultado r = eq.e().executar(s);
        assertThat(r.classe()).as("%s", r).isEqualTo(Classe.RECUSA);
        assertThat(r.motivo()).isEqualTo("CONTA_VINCULADA");
        assertThat(C.estado(s)).isEqualTo("RECUSADA");
        assertThat(C.encerramento(s)).isEqualTo("CONTA_VINCULADA");

        assertThat(C.resets(s)).as("nenhum evento de reset").isZero();
        assertThat(avisos(org)).as("nenhum aviso novo").isEqualTo(avisosAntes);
        assertThat(C.acesso(org, al.titular())).as("fator, codigos, desafios e sessoes da conta").isEqualTo(acessoAntes);
        assertThat(identidade(v)).as("fator e sessoes da identidade").isEqualTo(identidadeAntes);
        assertThat(C.identidade(org, al.titular())).isEqualTo(contaAntes);
    }

    /** Controle positivo: a mesma sequencia numa conta NAO vinculada executa (efeitos do reset e um evento). */
    @Test
    void contaNaoVinculadaExecuta() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        Resultado r = eq.e().executar(s);
        assertThat(r.classe()).as("%s", r).isEqualTo(Classe.OK);
        assertThat(r.estado()).isEqualTo("EXECUTADA");
        assertThat(C.acesso(al.org().id(), al.titular())).isEqualTo("0|0|0|0");
        assertThat(C.resets(s)).isEqualTo(1);
    }

    /**
     * Corrida vinculacao x execucao, VINCULACAO PRIMEIRO, com a funcao real da 017 ({@code identity_link_account}, papel
     * nekkora_platform): a vinculacao trava a organizacao e fica com a transacao aberta; a execucao espera o lock (conferido
     * em pg_stat_activity) e, depois do commit, e recusada com CONTA_VINCULADA (nao FATOR_DIVERGENTE, embora a vinculacao
     * tenha desabilitado o fator aprovado). Nenhum efeito do reset: estado da conta = o que a vinculacao deixou.
     */
    @Test
    void corridaVinculacaoPrimeiroRecusaAExecucao() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID org = al.org().id();
        UUID s = C.aprovada(eq, al);
        VinculacaoReal vr = prepararVinculacaoReal(al.org(), al.titular());
        try (Connection plat = DB.comoServico("nekkora_platform")) {
            plat.setAutoCommit(false);
            assertThat(vincular(plat, vr, org)).as("vinculacao real").isTrue();
            CompletableFuture<Resultado> exe = CompletableFuture.supplyAsync(() -> eq.e().executar(s));
            CertificadosIT.esperarBloqueado(eq.executor().papel());
            assertThat(exe).isNotDone();
            plat.commit();
            Resultado r = exe.get(60, TimeUnit.SECONDS);
            assertThat(r.classe()).as("%s", r).isEqualTo(Classe.RECUSA);
            assertThat(r.motivo()).isEqualTo("CONTA_VINCULADA");
        }
        assertThat(C.estado(s)).isEqualTo("RECUSADA");
        assertThat(C.encerramento(s)).isEqualTo("CONTA_VINCULADA");
        assertThat(C.resets(s)).as("nenhum evento de reset").isZero();
        assertThat(ligada(al.titular())).isTrue();
        // o que a vinculacao deixou (entrada local fechada), sem nada do reset
        assertThat(C.acesso(org, al.titular())).isEqualTo("0|0|0|0");
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM identity_sessions WHERE identity_id = ? AND revoked_at IS NULL", vr.identidade()))
                .as("sessao da identidade intacta").isEqualTo(1L);
    }

    /**
     * Corrida vinculacao x execucao, EXECUCAO PRIMEIRO: a execucao trava a organizacao, executa (a conta ainda nao esta
     * vinculada) e fica com a transacao aberta; a vinculacao real espera o lock e so prossegue depois do commit. Serial: o
     * reset vale sobre a conta nao vinculada e a vinculacao vem depois, sobre a entrada local ja fechada pelo reset. Uma
     * abertura nova sobre a conta agora vinculada e recusada com CONTA_VINCULADA.
     */
    @Test
    void corridaExecucaoPrimeiroVinculacaoEsperaOCommit() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID org = al.org().id();
        UUID s = C.aprovada(eq, al);
        VinculacaoReal vr = prepararVinculacaoReal(al.org(), al.titular());
        try (ConcorrenciaIT.Aberta exe = new ConcorrenciaIT.Aberta(eq.executor())) {
            assertThat(exe.chamar("ops_mfa_execute", s)).containsExactly("OK", null);
            CompletableFuture<Boolean> vinculo = CompletableFuture.supplyAsync(() -> {
                try (Connection plat = DB.comoServico("nekkora_platform")) {
                    return vincular(plat, vr, org);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            CertificadosIT.esperarBloqueado("nekkora_platform");
            assertThat(vinculo).isNotDone();
            assertThat(ligada(al.titular())).isFalse();
            exe.commit();
            assertThat(vinculo.get(60, TimeUnit.SECONDS)).as("vinculacao depois do commit do reset").isTrue();
        }
        assertThat(C.estado(s)).isEqualTo("EXECUTADA");
        assertThat(C.resets(s)).isEqualTo(1);
        assertThat(ligada(al.titular())).isTrue();
        assertThat(DB.valor(Boolean.class, "SELECT r.executed_at < (SELECT iu.linked_at FROM identity_users iu WHERE iu.user_id = ?)"
                + " FROM ops.ops_mfa_recovery_requests r WHERE r.request_id = ?", al.titular(), s)).as("reset antes da vinculacao").isTrue();
        Resultado nova = C.abrirPorRetorno(eq.v(), org, al.titular(), al.contatoTitular());
        assertThat(nova.classe()).as("%s", nova).isEqualTo(Classe.RECUSA);
        assertThat(nova.motivo()).isEqualTo("CONTA_VINCULADA");
    }

    private record VinculacaoReal(UUID identidade, String sessao, String desafio) {
    }

    private static final SecureRandom RND = new SecureRandom();

    /** Token no formato D2 (32 bytes em base64url sem padding). */
    private static String token() {
        byte[] b = new byte[32];
        RND.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /**
     * Pre-condicoes da vinculacao REAL (fixture declarada do superusuario; o consumo e os efeitos sao da funcao da 017):
     * identidade com o e-mail da conta, fator confirmado, sessao da identidade com step-up recente e desafio de vinculacao
     * ativo com a credencial vigente da conta.
     */
    private static VinculacaoReal prepararVinculacaoReal(OpsBanco.Org o, UUID user) {
        UUID identidade = UUID.randomUUID();
        String sessao = token();
        String desafio = token();
        String email = DB.valor(String.class, "SELECT email FROM users WHERE user_id = ?", user);
        DB.sqlNa(o.id(),
                "INSERT INTO identities (identity_id, email, display_name, email_verified_at, status) VALUES ('" + identidade + "', '" + email
                        + "', 'Identidade sintetica', now(), 2)",
                "SET LOCAL ROLE nekkora_identity_owner",
                "INSERT INTO identity_mfa_factors (identity_id, factor_type, secret_ciphertext, secret_nonce, key_id) VALUES ('"
                        + identidade + "', 1, gen_random_bytes(36), gen_random_bytes(12), 'k1')",
                "UPDATE identity_mfa_factors SET confirmed_at = clock_timestamp() WHERE identity_id = '" + identidade + "'",
                "RESET ROLE",
                "INSERT INTO identity_sessions (identity_id, session_token_hash, expires_at, mfa_verified_at, csrf_token) VALUES ('" + identidade
                        + "', '" + OpsBanco.sha256(sessao) + "', now() + interval '1 hour', now(), '" + "i".repeat(43) + "')",
                "UPDATE identity_sessions SET mfa_step_up_at = clock_timestamp() WHERE identity_id = '" + identidade + "'",
                "INSERT INTO identity_link_challenges (identity_id, user_id, token_hash, account_credential_hash, expires_at) SELECT '" + identidade
                        + "', '" + user + "', '" + OpsBanco.sha256(desafio) + "', password_hash, now() + interval '4 minutes' FROM user_credentials"
                        + " WHERE user_id = '" + user + "'");
        return new VinculacaoReal(identidade, sessao, desafio);
    }

    private static boolean vincular(Connection plat, VinculacaoReal vr, UUID org) throws SQLException {
        try (PreparedStatement ps = plat.prepareStatement("SELECT public.identity_link_account(?, ?, ?)")) {
            ps.setString(1, vr.sessao());
            ps.setString(2, vr.desafio());
            ps.setObject(3, org);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static boolean ligada(UUID user) {
        return DB.valor(Boolean.class, "SELECT EXISTS (SELECT 1 FROM identity_users WHERE user_id = ?)", user);
    }

    private static long avisos(UUID org) {
        return DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_notices WHERE organization_id = ?", org);
    }

    /** Fator e sessoes da identidade (nada pode mudar com a recusa). */
    private static String identidade(Vinculo v) {
        return DB.valor(String.class, "SELECT concat_ws('|', "
                + "(SELECT string_agg(factor_id::text || coalesce(disabled_at::text, '-'), ',') FROM identity_mfa_factors WHERE identity_id = ?), "
                + "(SELECT string_agg(identity_session_id::text || coalesce(revoked_at::text, '-'), ',') FROM identity_sessions WHERE identity_id = ?), "
                + "(SELECT string_agg(s.session_id::text || coalesce(s.revoked_at::text, '-'), ',') FROM user_sessions s WHERE s.identity_session_id = ?))",
                v.identidade(), v.identidade(), v.sessaoIdentidade());
    }

    /** Vinculacao no estado que a 017 deixa (fixture declarada; ver o comentario da classe). */
    private static Vinculo vincular(OpsBanco.Org o, UUID user) {
        UUID identidade = UUID.randomUUID();
        UUID sessao = UUID.randomUUID();
        String alvo = "organization_id = '" + o.id() + "' AND user_id = '" + user + "'";
        DB.sqlNa(o.id(),
                "UPDATE user_sessions SET revoked_at = greatest(now(), created_at) WHERE " + alvo + " AND revoked_at IS NULL",
                "UPDATE auth_challenges SET invalidated_at = now(), credential_hash = NULL, pending_password_hash = NULL WHERE " + alvo + " AND consumed_at IS NULL AND invalidated_at IS NULL",
                "UPDATE user_mfa_recovery_codes SET invalidated_at = greatest(now(), created_at) WHERE " + alvo + " AND used_at IS NULL AND invalidated_at IS NULL",
                "UPDATE user_mfa_factors SET disabled_at = greatest(now(), created_at) WHERE " + alvo + " AND disabled_at IS NULL",
                "INSERT INTO identities (identity_id, email, display_name, email_verified_at, status) VALUES ('" + identidade + "', 'id-"
                        + identidade.toString().substring(0, 8) + "@identidade.test', 'Identidade sintetica', now(), 2)",
                // o fator da identidade so nasce e so e confirmado pelo dono da 017 (as funcoes de fator rodam com ele)
                "SET LOCAL ROLE nekkora_identity_owner",
                "INSERT INTO identity_mfa_factors (identity_id, factor_type, secret_ciphertext, secret_nonce, key_id) VALUES ('"
                        + identidade + "', 1, gen_random_bytes(36), gen_random_bytes(12), 'k1')",
                "UPDATE identity_mfa_factors SET confirmed_at = clock_timestamp() WHERE identity_id = '" + identidade + "'",
                "RESET ROLE",
                "INSERT INTO identity_sessions (identity_session_id, identity_id, session_token_hash, expires_at, mfa_verified_at, csrf_token) VALUES ('"
                        + sessao + "', '" + identidade + "', encode(gen_random_bytes(32), 'hex'), now() + interval '1 hour', now(), '" + "i".repeat(43) + "')",
                "INSERT INTO identity_users (identity_id, user_id, link_channel) VALUES ('" + identidade + "', '" + user + "', 'vinculacao')");
        DB.sqlNa(o.id(), "INSERT INTO user_sessions (organization_id, user_id, session_token_hash, expires_at, mfa_verified_at, csrf_token, identity_session_id)"
                + " VALUES ('" + o.id() + "', '" + user + "', encode(gen_random_bytes(32), 'hex'), now() + interval '1 hour', now(), '" + "s".repeat(43)
                + "', '" + sessao + "')");
        return new Vinculo(identidade, sessao);
    }
}
