package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.cli.ExecutorAtos.Classe;
import com.nekkora.ops.cli.ExecutorAtos.Origem;
import com.nekkora.ops.cli.ExecutorAtos.Resultado;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Caminho feliz no banco, com os papeis reais: pedido -> verificacao -> pre-aprovacao -> aviso (Platform) -> confirmacao
 * do titular -> espera de 24 h (fixture temporal) -> aprovacao final -> execucao. Efeitos do reset e nada mais.
 */
class FluxoOperacionalIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);

    @Test
    void fluxoCompletoResetaSoOSegundoFatorEOutroAdminEAvisado() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID outroAdmin = DB.outroAdmin(al.org());
        String identidadeAntes = C.identidade(al.org().id(), al.titular());
        String acessoAntes = C.acesso(al.org().id(), al.titular());
        assertThat(acessoAntes).isEqualTo("1|2|1|1");

        UUID s = C.preAprovada(eq, al);
        assertThat(C.estado(s)).isEqualTo("PRE_APROVADA");
        assertThat(DB.valor(UUID.class, "SELECT approved_factor_id FROM ops.ops_mfa_recovery_requests WHERE request_id = ?", s)).isEqualTo(al.fator());

        List<Cenarios.Tokens> avisos = C.emitirAvisos(al.org().id());
        assertThat(avisos).hasSize(2);
        Cenarios.Tokens titular = avisos.stream().filter(t -> t.destinatario().equals(al.titular())).findFirst().orElseThrow();
        Cenarios.Tokens admin = avisos.stream().filter(t -> t.destinatario().equals(outroAdmin)).findFirst().orElseThrow();
        assertThat(titular.temConfirmar()).isTrue();
        assertThat(titular.temContestar()).isTrue();
        assertThat(admin.temConfirmar()).as("o outro administrador so contesta").isFalse();
        assertThat(admin.temContestar()).isTrue();

        // aprovacao final antes da confirmacao: recusada
        assertThat(eq.a().aprovar(s).motivo()).isEqualTo("AVISO_NAO_CONFIRMADO");
        assertThat(C.confirmar(al.org().id(), titular.confirmar())).isTrue();
        // antes das 24 h: recusada
        assertThat(eq.a().aprovar(s).motivo()).isEqualTo("ESPERA_NAO_CUMPRIDA");
        DB.recuarSolicitacao(s, Cenarios.ESPERA);
        Cenarios.exigirOk(eq.a().aprovar(s), "aprovar");

        assertThat(C.acesso(al.org().id(), al.titular())).as("nada muda antes da execucao").isEqualTo(acessoAntes);
        Resultado r = eq.e().executar(s);
        assertThat(r.classe()).isEqualTo(Classe.OK);
        assertThat(r.origem()).isEqualTo(Origem.BANCO);
        assertThat(r.estado()).isEqualTo("EXECUTADA");

        // efeitos: fator desabilitado, codigos e desafios invalidados, sessoes revogadas; um evento tipo 4 vinculado
        assertThat(C.acesso(al.org().id(), al.titular())).isEqualTo("0|0|0|0");
        assertThat(C.resets(s)).isEqualTo(1);
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM mfa_events WHERE user_id = ? AND event_type = 4 AND actor_user_id IS NULL",
                al.titular())).isEqualTo(1);
        // senha, papeis, vinculos, concessoes, status e e-mail identicos
        assertThat(C.identidade(al.org().id(), al.titular())).isEqualTo(identidadeAntes);
        // aviso de execucao ao titular e ao outro administrador (informativos, sem token)
        List<Cenarios.Tokens> execucao = C.emitirAvisos(al.org().id());
        assertThat(execucao).hasSize(2).allSatisfy(t -> {
            assertThat(t.temConfirmar()).isFalse();
            assertThat(t.temContestar()).isFalse();
        });
        // a contestacao depois da execucao nao tem efeito
        assertThat(C.contestar(al.org().id(), admin.contestar())).isFalse();
        assertThat(C.estado(s)).isEqualTo("EXECUTADA");
    }
}
