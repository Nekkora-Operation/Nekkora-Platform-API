package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

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
 * local); a abertura caia em SEM_FATOR_CONFIRMADO.
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
