package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.cli.ExecutorAtos.Classe;
import com.nekkora.ops.cli.ExecutorAtos.Resultado;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Grupo Estados e prazos (contrato 5, 6, D2, D4, D6, D11). Os prazos NAO sao encurtados: as fixtures temporais
 * deslocam para tras os instantes gravados (superusuario, so na base de teste), simulando a passagem do tempo.
 */
class EstadosEPrazosIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);

    @Test
    void aprovacaoFinalSoAPartirDe24hDaConfirmacao() {
        Cenarios.Equipe eq = C.equipe();
        UUID s = C.confirmada(eq, C.alvo());
        DB.recuarSolicitacao(s, Duration.ofHours(23).plusMinutes(59));
        assertThat(eq.a().aprovar(s).motivo()).isEqualTo("ESPERA_NAO_CUMPRIDA");
        DB.recuarSolicitacao(s, Duration.ofMinutes(2));
        Cenarios.exigirOk(eq.a().aprovar(s), "aprovar depois de 24 h");
    }

    @Test
    void execucaoDepoisDoPrazoTotalExpira() {
        Cenarios.Equipe eq = C.equipe();
        UUID s = C.aprovada(eq, C.alvo());
        DB.recuarSolicitacao(s, Duration.ofHours(48));
        Resultado r = eq.e().executar(s);
        assertThat(r.classe()).isEqualTo(Classe.RECUSA);
        assertThat(r.motivo()).isEqualTo("PRAZO_ENCERRADO");
        assertThat(C.estado(s)).isEqualTo("EXPIRADA");
        assertThat(C.resets(s)).isZero();
    }

    @Test
    void janelaDeExecucaoEOMenorEntrePrazoTotalEAprovacaoMais60Min() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.confirmada(eq, al);
        // confirmacao tardia: aprovacao final quando faltam 30 min para T_total
        DB.recuarSolicitacao(s, Duration.ofHours(71).plusMinutes(30));
        Cenarios.exigirOk(eq.a().aprovar(s), "aprovar perto do prazo total");
        DB.recuarSolicitacao(s, Duration.ofMinutes(31));
        Resultado r = eq.e().executar(s);
        assertThat(r.motivo()).as("T_total vence antes de aprovacao + 60 min").isEqualTo("PRAZO_ENCERRADO");
        assertThat(C.estado(s)).isEqualTo("EXPIRADA");

        // o outro lado: aprovacao + 60 min vence antes de T_total
        Cenarios.Equipe eq2 = C.equipe();
        UUID s2 = C.aprovada(eq2, C.alvo());
        DB.recuarSolicitacao(s2, Duration.ofMinutes(61));
        assertThat(eq2.e().executar(s2).motivo()).isEqualTo("PRAZO_ENCERRADO");
        assertThat(C.resets(s)).isZero();
        assertThat(C.resets(s2)).isZero();
    }

    @Test
    void naoVerificadaExpiraEm24h() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aberta(eq, al);
        DB.recuarSolicitacao(s, Duration.ofHours(24));
        Resultado r = eq.v().verificar(s, true, al.segundoContato(), true);
        assertThat(r.motivo()).isEqualTo("PRAZO_ENCERRADO");
        assertThat(C.estado(s)).isEqualTo("EXPIRADA");
    }

    @Test
    void pedidosNaoVerificadosNaoBloqueiamOVerificadoELimiteEPorOrigem() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID atacante = DB.contatoElegivel(al.org(), null);
        for (int i = 0; i < 3; i++) {
            Cenarios.exigirOk(eq.v().abrir(al.org().id(), al.titular(), atacante), "abrir " + i);
        }
        Resultado quarto = eq.v().abrir(al.org().id(), al.titular(), atacante);
        assertThat(quarto.motivo()).isEqualTo("LIMITE_ORIGEM");
        // o pedido legitimo abre e verifica; os nao verificados sao encerrados como substituidos
        UUID s = C.verificada(eq, al);
        assertThat(C.estado(s)).isEqualTo("VERIFICADA");
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_requests WHERE target_user_id = ? AND state = 7 "
                + "AND close_reason = 'SUBSTITUIDA'", al.titular())).isEqualTo(3);
    }

    @Test
    void exclusividadePorAlvoComecaEmVerificada() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID a = C.verificada(eq, al);
        UUID outroContato = DB.contatoElegivel(al.org(), null);
        Cenarios.exigirOk(eq.v().abrir(al.org().id(), al.titular(), outroContato), "abrir B");
        UUID b = DB.valor(UUID.class, "SELECT request_id FROM ops.ops_mfa_recovery_requests WHERE target_user_id = ? AND state = 1", al.titular());
        assertThat(eq.v().verificar(b, true, al.segundoContato(), true).motivo()).isEqualTo("SOLICITACAO_VERIFICADA_ABERTA");
        assertThat(C.estado(b)).isEqualTo("SOLICITADA");
        Cenarios.exigirOk(eq.v().cancelar(a, "SOLICITANTE_DESISTIU"), "cancelar A");
        Cenarios.exigirOk(eq.v().verificar(b, true, al.segundoContato(), true), "verificar B");
    }

    /** Executa o fluxo inteiro para o alvo {@code titular} com um contato proprio dele (fixture de limite). */
    private UUID executar(Cenarios.Equipe eq, OpsBanco.Org org, UUID titular, UUID segundo) {
        UUID contato = DB.contatoElegivel(org, titular);
        Cenarios.Alvo al = new Cenarios.Alvo(org, titular, null, contato, segundo);
        UUID s = C.aprovada(eq, al);
        Cenarios.exigirOk(eq.e().executar(s), "executar");
        return s;
    }

    @Test
    void limitesExecutadosPorAlvoEPorOrganizacao() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s1 = executar(eq, al.org(), al.titular(), al.segundoContato());
        // o titular recadastra o fator; nova recuperacao antes de 7 dias e recusada
        DB.fatorConfirmado(al.org().id(), al.titular());
        UUID s2 = C.aberta(eq, al);
        Resultado r = eq.v().verificar(s2, true, al.segundoContato(), true);
        assertThat(r.motivo()).isEqualTo("LIMITE_ALVO");
        assertThat(C.estado(s2)).isEqualTo("RECUSADA");
        DB.recuarSolicitacao(s1, Duration.ofDays(7).plusMinutes(1));
        Cenarios.exigirOk(eq.v().verificar(C.aberta(eq, al), true, al.segundoContato(), true), "depois de 7 dias");

        // tres executadas na organizacao em 30 dias: a quarta e recusada
        Cenarios.Equipe eq2 = C.equipe();
        Cenarios.Alvo org = C.alvo();
        executar(eq2, org.org(), org.titular(), org.segundoContato());
        executar(eq2, org.org(), DB.outroAdmin(org.org()), org.segundoContato());
        executar(eq2, org.org(), DB.outroAdmin(org.org()), org.segundoContato());
        UUID quarto = DB.outroAdmin(org.org());
        UUID contato = DB.contatoElegivel(org.org(), quarto);
        Cenarios.Alvo al4 = new Cenarios.Alvo(org.org(), quarto, null, contato, org.segundoContato());
        UUID s4 = C.aberta(eq2, al4);
        assertThat(eq2.v().verificar(s4, true, org.segundoContato(), true).motivo()).isEqualTo("LIMITE_ORGANIZACAO");
    }

    @Test
    void contatosCanalRecenteEOrganizacaoSemDoisContatosElegiveis() {
        Cenarios.Equipe eq = C.equipe();
        OpsBanco.Org o = DB.organizacao();
        UUID novo = DB.contatoNovo(o, o.admin());
        assertThat(eq.v().abrir(o.id(), o.admin(), novo).motivo()).isEqualTo("CONTATOS_INSUFICIENTES");
        UUID velho = DB.contatoElegivel(o, null);
        assertThat(eq.v().abrir(o.id(), o.admin(), novo).motivo()).as("so um elegivel").isEqualTo("CONTATOS_INSUFICIENTES");
        UUID outro = DB.contatoElegivel(o, null);
        assertThat(eq.v().abrir(o.id(), o.admin(), novo).motivo()).as("solicitante com canal recente").isEqualTo("CANAL_RECENTE");
        // alteracao de canal (confirmada pelo anterior e por outro contato) torna o canal recente de novo; um terceiro
        // contato elegivel mantem a organizacao com dois elegiveis, para isolar a regra do canal
        DB.contatoElegivel(o, null);
        assertThat(DB.cadastro("SELECT r_resultado FROM public.ops_contact_change_channel(?, 2::smallint, 'novo@contato.test', true, ?, true)",
                String.class, velho, outro)).isEqualTo("OK");
        assertThat(eq.v().abrir(o.id(), o.admin(), velho).motivo()).isEqualTo("CANAL_RECENTE");
        // alteracao sem as duas confirmacoes e recusada
        assertThat(DB.cadastro("SELECT r_motivo FROM public.ops_contact_change_channel(?, 2::smallint, 'x@contato.test', true, ?, false)",
                String.class, outro, velho)).isEqualTo("CONFIRMACAO_AUSENTE");
        assertThat(DB.cadastro("SELECT r_motivo FROM public.ops_contact_remove(?, true, ?, true)", String.class, outro, velho))
                .as("o outro contato precisa ser elegivel").isEqualTo("OUTRO_CONTATO_INELEGIVEL");
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.org_authorized_contact_events WHERE contact_id = ? AND kind = 2", velho)).isEqualTo(1);
    }

    @Test
    void estadosSoAvancamEAtosForaDeOrdemSaoRecusados() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aberta(eq, al);
        assertThat(eq.e().executar(s).motivo()).isEqualTo("ESTADO_INVALIDO");
        assertThat(eq.a().aprovar(s).motivo()).isEqualTo("ESTADO_INVALIDO");
        Cenarios.exigirOk(eq.v().verificar(s, true, al.segundoContato(), true), "verificar");
        assertThat(eq.v().verificar(s, true, al.segundoContato(), true).motivo()).isEqualTo("ESTADO_INVALIDO");
        Cenarios.exigirOk(eq.a().preAprovar(s), "pre-aprovar");
        C.confirmar(al.org().id(), C.emitirAvisos(al.org().id()).get(0).confirmar());
        DB.recuarSolicitacao(s, Cenarios.ESPERA);
        Cenarios.exigirOk(eq.a().aprovar(s), "aprovar");
        Cenarios.exigirOk(eq.e().executar(s), "executar");
        Resultado deNovo = eq.e().executar(s);
        assertThat(deNovo.motivo()).isEqualTo("JA_EXECUTADA");
        assertThat(C.resets(s)).isEqualTo(1);
        assertThat(eq.v().cancelar(s, "OUTRO").motivo()).isEqualTo("ESTADO_INVALIDO");
    }

    @Test
    void alvoInelegivel() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID membro = DB.membro(al.org());
        UUID contatoMembro = DB.contatoElegivel(al.org(), membro);
        assertThat(eq.v().abrir(al.org().id(), membro, contatoMembro).motivo()).isEqualTo("NAO_ORG_ADMIN");
        // outro ORG_ADMIN sem fator confirmado (so pendente): nao ha emergencia, o login leva ao cadastro
        UUID semFator = DB.outroAdmin(al.org());
        DB.sql("UPDATE user_mfa_factors SET disabled_at = now() WHERE user_id = '" + semFator + "' AND disabled_at IS NULL");
        assertThat(eq.v().abrir(al.org().id(), semFator, DB.contatoElegivel(al.org(), semFator)).motivo()).isEqualTo("SEM_FATOR_CONFIRMADO");

        // alvo que deixa de ser elegivel entre a aprovacao e a execucao
        UUID s = C.aprovada(eq, al);
        DB.sql("BEGIN; SELECT 1 FROM organizations WHERE organization_id = '" + al.org().id() + "' FOR UPDATE; "
                + "UPDATE users SET status = 3 WHERE user_id = '" + al.titular() + "'; COMMIT");
        Resultado r = eq.e().executar(s);
        assertThat(r.motivo()).isEqualTo("CONTA_INELEGIVEL");
        assertThat(C.estado(s)).isEqualTo("RECUSADA");
        assertThat(C.resets(s)).isZero();

        // organizacao suspensa
        Cenarios.Alvo al2 = C.alvo();
        DB.sql("UPDATE organizations SET status = 2 WHERE organization_id = '" + al2.org().id() + "'");
        assertThat(eq.v().abrir(al2.org().id(), al2.titular(), al2.contatoTitular()).motivo()).isEqualTo("ORGANIZACAO_INELEGIVEL");
    }
}
