package com.nekkora.ops.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.cli.ExecutorAtos.Classe;
import com.nekkora.ops.cli.ExecutorAtos.Limites;
import com.nekkora.ops.cli.ExecutorAtos.ResultadoAtivacao;

/**
 * Linha de saida de ATIVAR-ORGANIZACAO e REVISAR-CAPACIDADE (022): identificador do ato (evento) e os cinco limites que o ato
 * OK aplicou; recusa, falha e resultado desconhecido nao mostram limite aplicado. Sem banco.
 */
class OpsCliSaidaCapacidadeTest {

    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EVENTO = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void okMostraOAtoEOsCincoLimitesAplicados() {
        ResultadoAtivacao r = new ResultadoAtivacao(ORG, Classe.OK, null, EVENTO, null, new Limites(5, 4, 50, 1_073_741_824L, null));
        assertThat(OpsCli.saidaCapacidade(r)).isEqualTo("organizacao=" + ORG + " resultado=OK evento=" + EVENTO + " motivo=-"
                + " limites_aplicados=trabalhadores=5,simultaneas=4,pendentes=50,alerta_logs=1073741824,alerta_eventos=-");
    }

    @Test
    void recusaMostraOAtoAuditadoESemLimiteAplicado() {
        ResultadoAtivacao r = new ResultadoAtivacao(ORG, Classe.RECUSA, "SEM_MUDANCA", EVENTO, null, null);
        assertThat(OpsCli.saidaCapacidade(r)).isEqualTo("organizacao=" + ORG + " resultado=RECUSA evento=" + EVENTO
                + " motivo=SEM_MUDANCA limites_aplicados=-");
    }

    @Test
    void desconhecidoNaoAfirmaEventoNemLimite() {
        ResultadoAtivacao r = new ResultadoAtivacao(ORG, Classe.DESCONHECIDO, "RESULTADO_DESCONHECIDO", null, null, null);
        assertThat(OpsCli.saidaCapacidade(r)).isEqualTo("organizacao=" + ORG + " resultado=DESCONHECIDO evento=-"
                + " motivo=RESULTADO_DESCONHECIDO limites_aplicados=-");
    }
}
