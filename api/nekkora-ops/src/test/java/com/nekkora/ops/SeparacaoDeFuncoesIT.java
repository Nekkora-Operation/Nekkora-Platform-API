package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.cli.ExecutorAtos;
import com.nekkora.ops.cli.ExecutorAtos.Classe;
import com.nekkora.ops.cli.ExecutorAtos.Resultado;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Grupo Separacao de funcoes (contrato 4, 7 e 10 - autoaprovacao): aprovador distinto do verificador e do executor;
 * verificador PODE ser o executor; vinculos impeditivos de TODOS os participantes; habilitacao por ato; cadastro sem
 * auto-habilitacao. Toda recusa registrada, nunca um reset.
 */
class SeparacaoDeFuncoesIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);

    @Test
    void aprovadorIgualAoVerificadorERecusadoNaPreENaAprovacaoFinal() {
        OpsBanco.Operador va = DB.operador("va", "VERIFICAR", "APROVAR");
        OpsBanco.Operador a = DB.operador("apr", "APROVAR");
        ExecutorAtos cliVa = DB.cli(va);
        Cenarios.Alvo al = C.alvo();
        Resultado ab = cliVa.abrir(al.org().id(), al.titular(), al.contatoTitular());
        UUID s = DB.valor(UUID.class, "SELECT request_id FROM ops.ops_mfa_recovery_requests WHERE target_user_id = ?", al.titular());
        Cenarios.exigirOk(ab, "abrir");
        Cenarios.exigirOk(cliVa.verificar(s, true, al.segundoContato(), true), "verificar");
        Resultado r = cliVa.preAprovar(s);
        assertThat(r.classe()).isEqualTo(Classe.RECUSA);
        assertThat(r.motivo()).isEqualTo("APROVADOR_IGUAL_VERIFICADOR");
        Cenarios.exigirOk(DB.cli(a).preAprovar(s), "pre-aprovar por outro");
        Cenarios.Tokens t = C.emitirAvisos(al.org().id()).get(0);
        assertThat(C.confirmar(al.org().id(), t.confirmar())).isTrue();
        DB.recuarSolicitacao(s, Cenarios.ESPERA);
        assertThat(cliVa.aprovar(s).motivo()).isEqualTo("APROVADOR_IGUAL_VERIFICADOR");
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_events WHERE request_id = ? AND reason = 'APROVADOR_IGUAL_VERIFICADOR'", s))
                .isEqualTo(2);
    }

    @Test
    void executorIgualAoAprovadorERecusadoSemReset() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        DB.habilitar(eq.aprovador(), "EXECUTAR");
        Resultado r = eq.a().executar(s);
        assertThat(r.classe()).isEqualTo(Classe.RECUSA);
        assertThat(r.motivo()).isEqualTo("EXECUTOR_IGUAL_APROVADOR");
        assertThat(C.resets(s)).isZero();
        assertThat(C.estado(s)).isEqualTo("APROVADA");
        Cenarios.exigirOk(eq.e().executar(s), "executor distinto");
    }

    @Test
    void verificadorPodeSerOExecutor() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        DB.habilitar(eq.verificador(), "EXECUTAR");
        Resultado r = eq.v().executar(s);
        assertThat(r.classe()).isEqualTo(Classe.OK);
        assertThat(DB.valor(UUID.class, "SELECT executed_by FROM ops.ops_mfa_recovery_requests WHERE request_id = ?", s))
                .isEqualTo(eq.verificador().id());
    }

    @Test
    void participanteComContaVinculadaNaOrganizacaoAlvoImpedeAExecucao() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        // o verificador controla uma conta (sem papel) na organizacao alvo e a vincula depois da verificacao
        UUID contaDoVerificador = DB.membro(al.org());
        assertThat(C.vincularConta(eq.verificador(), C.provaDeConta(al.org().id(), contaDoVerificador))).isEqualTo("OK");
        Resultado r = eq.e().executar(s);
        assertThat(r.classe()).isEqualTo(Classe.RECUSA);
        assertThat(r.motivo()).isEqualTo("VINCULO_IMPEDITIVO");
        assertThat(C.estado(s)).as("recusa na execucao encerra").isEqualTo("RECUSADA");
        assertThat(C.resets(s)).isZero();
        assertThat(C.acesso(al.org().id(), al.titular())).isEqualTo("1|2|1|1");
    }

    @Test
    void operadorVinculadoAContatoDaOrganizacaoNaoAtua() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.verificada(eq, al);
        assertThat(C.vincularContato(eq.aprovador(), C.provaDeContato(al.segundoContato()))).isEqualTo("OK");
        Resultado r = eq.a().preAprovar(s);
        assertThat(r.classe()).isEqualTo(Classe.RECUSA);
        assertThat(r.motivo()).isEqualTo("VINCULO_IMPEDITIVO");
        assertThat(C.estado(s)).isEqualTo("VERIFICADA");
    }

    @Test
    void provaDeVinculoTemUsoUnicoEOEmissorNaoConsomeAPropria() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        String prova = C.provaDeConta(al.org().id(), DB.membro(al.org()));
        assertThat(C.vincularConta(eq.verificador(), prova)).isEqualTo("OK");
        assertThat(C.vincularConta(eq.aprovador(), prova)).as("prova usada").isEqualTo("RECUSA");
        assertThat(C.vincularConta(eq.aprovador(), OpsBanco.tokenBruto())).as("prova inexistente").isEqualTo("RECUSA");
    }

    @Test
    void semHabilitacaoParaOAtoERecusado() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.verificada(eq, al);
        Resultado r = eq.e().preAprovar(s);
        assertThat(r.classe()).isEqualTo(Classe.RECUSA);
        assertThat(r.motivo()).isEqualTo("SEM_HABILITACAO");
        assertThat(eq.v().aprovar(s).motivo()).isEqualTo("SEM_HABILITACAO");
    }

    @Test
    void cadastroSoComCadastrarESemAutoHabilitacao() {
        OpsBanco.Operador x = DB.operador("x", "VERIFICAR");
        assertThat(C.comoOperador(x, "SELECT r_motivo FROM public.ops_operator_grant(?, 'APROVAR')", x.id())).isEqualTo("SEM_HABILITACAO");
        OpsBanco.Operador cad = DB.operador("cad", "CADASTRAR");
        assertThat(C.comoOperador(cad, "SELECT r_motivo FROM public.ops_operator_grant(?, 'APROVAR')", cad.id())).isEqualTo("AUTO_HABILITACAO");
        assertThat(C.comoOperador(cad, "SELECT r_motivo FROM public.ops_operator_disable(?)", cad.id())).isEqualTo("AUTO_DESATIVACAO");
        assertThat(C.comoOperador(cad, "SELECT r_resultado FROM public.ops_operator_grant(?, 'APROVAR')", x.id())).isEqualTo("OK");
        assertThat(DB.valor(UUID.class, "SELECT granted_by FROM ops.ops_operator_permissions WHERE operator_id = ? AND act = 2", x.id()))
                .isEqualTo(cad.id());
    }
}
