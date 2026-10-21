package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.nekkora.ops.cli.ExecutorAtos.Classe;
import com.nekkora.ops.cli.ExecutorAtos.Resultado;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Recuo do relogio do banco na EXECUCAO da recuperacao emergencial (missao PLATFORM-IDENTIDADE-002, frente 1; funcao
 * operacional da 016, substituida pela 018). Os codigos, o fator ou a sessao do titular ficam com {@code created_at} a
 * frente do relogio - o estado que um passo para tras de {@code clock_timestamp()} entre duas transacoes produz - e a
 * execucao, depois de todas as reconferencias, precisa concluir com os efeitos do reset. O instante terminal gravado e o
 * {@code created_at} (ajustado para a CHECK, nao o instante exato da execucao); executed_at continua o instante medido.
 */
class RecuoDoRelogioIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);

    private static String aFrente(String linhas, UUID org, UUID user) {
        String onde = " WHERE organization_id = '" + org + "' AND user_id = '" + user + "'";
        return switch (linhas) {
            case "codigos" -> "UPDATE user_mfa_recovery_codes SET created_at = clock_timestamp() + interval '1 minute'" + onde
                    + " AND used_at IS NULL AND invalidated_at IS NULL";
            case "fator" -> "UPDATE user_mfa_factors SET created_at = clock_timestamp() + interval '1 minute', confirmed_at = clock_timestamp() + interval '1 minute'"
                    + onde + " AND disabled_at IS NULL";
            case "sessoes" -> "UPDATE user_sessions SET created_at = clock_timestamp() + interval '1 minute', last_seen_at = clock_timestamp() + interval '1 minute'"
                    + onde + " AND revoked_at IS NULL";
            default -> throw new IllegalArgumentException(linhas);
        };
    }

    private static String terminal(String linhas) {
        return switch (linhas) {
            case "codigos" -> "SELECT count(*) FROM user_mfa_recovery_codes WHERE organization_id = ? AND user_id = ? AND invalidated_at = created_at";
            case "fator" -> "SELECT count(*) FROM user_mfa_factors WHERE organization_id = ? AND user_id = ? AND disabled_at = created_at";
            default -> "SELECT count(*) FROM user_sessions WHERE organization_id = ? AND user_id = ? AND revoked_at = created_at";
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"codigos", "fator", "sessoes"})
    void execucaoConcluiComLinhasDoTitularAFrenteDoRelogioDoBanco(String linhas) {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        String antes = C.acesso(al.org().id(), al.titular());
        assertThat(antes).isEqualTo("1|2|1|1");
        DB.semTriggers(aFrente(linhas, al.org().id(), al.titular()));
        long aFrente = DB.valor(Long.class, terminal(linhas).replace("invalidated_at = created_at", "created_at > clock_timestamp()")
                .replace("disabled_at = created_at", "created_at > clock_timestamp()").replace("revoked_at = created_at", "created_at > clock_timestamp()"),
                al.org().id(), al.titular());
        assertThat(aFrente).as("linhas a frente do relogio").isPositive();

        Resultado r = eq.e().executar(s);
        if (r.classe() != Classe.OK) {
            imprimirDiagnostico(r);
        }
        assertThat(r.classe()).as("execucao: %s", r).isEqualTo(Classe.OK);
        assertThat(r.estado()).isEqualTo("EXECUTADA");
        assertThat(C.acesso(al.org().id(), al.titular())).as("efeitos do reset").isEqualTo("0|0|0|0");
        assertThat(C.resets(s)).isEqualTo(1);
        assertThat(DB.valor(Long.class, terminal(linhas), al.org().id(), al.titular())).as("instante ajustado ao created_at").isEqualTo(aFrente);
        String col = terminal(linhas).replaceAll(".* AND ([a-z_]+) = created_at$", "$1");
        assertThat(DB.valor(Long.class, terminal(linhas).replace(col + " = created_at", col + " > (SELECT executed_at FROM ops.ops_mfa_recovery_requests"
                + " WHERE request_id = '" + s + "')"), al.org().id(), al.titular())).as("executed_at e o instante medido; so o terminal da linha e ajustado")
                .isEqualTo(aFrente);
    }

    /** Controle negativo: com as mesmas linhas a frente do relogio, a execucao de solicitacao nao aprovada continua recusada, sem efeito. */
    @ParameterizedTest
    @ValueSource(strings = {"codigos", "fator", "sessoes"})
    void execucaoNaoAprovadaContinuaRecusadaComLinhasAFrenteDoRelogio(String linhas) {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.confirmada(eq, al);
        DB.semTriggers(aFrente(linhas, al.org().id(), al.titular()));
        Resultado r = eq.e().executar(s);
        assertThat(r.classe()).isEqualTo(Classe.RECUSA);
        assertThat(r.motivo()).isEqualTo("ESTADO_INVALIDO");
        assertThat(C.acesso(al.org().id(), al.titular())).as("nada muda").isEqualTo("1|2|1|1");
        assertThat(C.resets(s)).isZero();
    }

    // ------------------------------------------------------------------ habilitacoes de operador (cadastro operacional)

    private static String habilitacaoAFrente(OpsBanco.Operador o) {
        return "UPDATE ops.ops_operator_permissions SET granted_at = clock_timestamp() + interval '1 minute' WHERE operator_id = '" + o.id()
                + "' AND revoked_at IS NULL";
    }

    /** Retirada de habilitacao concedida a frente do relogio: OK, revoked_at = granted_at (ajustado). */
    @org.junit.jupiter.api.Test
    void retiradaDeHabilitacaoConcedidaAFrenteDoRelogioDoBanco() {
        OpsBanco.Operador o = DB.operador("rel", "VERIFICAR", "CONSULTAR");
        DB.semTriggers(habilitacaoAFrente(o));
        assertThat(DB.cadastro("SELECT r_resultado FROM public.ops_operator_revoke(?, 'VERIFICAR')", String.class, o.id())).isEqualTo("OK");
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_operator_permissions WHERE operator_id = ? AND act = 1 AND revoked_at = granted_at",
                o.id())).as("retirada, instante ajustado").isEqualTo(1);
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_operator_permissions WHERE operator_id = ? AND revoked_at IS NULL", o.id()))
                .as("so CONSULTAR continua").isEqualTo(1);
    }

    /** Desativacao de operador com habilitacoes concedidas a frente do relogio: OK, todas retiradas. */
    @org.junit.jupiter.api.Test
    void desativacaoDeOperadorComHabilitacoesAFrenteDoRelogioDoBanco() {
        OpsBanco.Operador o = DB.operador("rel", "VERIFICAR", "CONSULTAR");
        DB.semTriggers(habilitacaoAFrente(o));
        assertThat(DB.cadastro("SELECT r_resultado FROM public.ops_operator_disable(?)", String.class, o.id())).isEqualTo("OK");
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_operator_permissions WHERE operator_id = ? AND revoked_at IS NULL", o.id()))
                .as("nenhuma habilitacao ativa").isZero();
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_operators WHERE operator_id = ? AND status = 2", o.id())).isEqualTo(1);
    }

    /** Controle negativo: retirar habilitacao que o operador nao tem continua recusado, com as demais a frente do relogio. */
    @org.junit.jupiter.api.Test
    void retiradaSemHabilitacaoContinuaRecusadaComLinhasAFrenteDoRelogio() {
        OpsBanco.Operador o = DB.operador("rel", "CONSULTAR");
        DB.semTriggers(habilitacaoAFrente(o));
        assertThat(DB.cadastro("SELECT r_motivo FROM public.ops_operator_revoke(?, 'EXECUTAR')", String.class, o.id())).isEqualTo("NAO_HABILITADO");
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_operator_permissions WHERE operator_id = ? AND revoked_at IS NULL", o.id()))
                .isEqualTo(1);
    }

    private static void imprimirDiagnostico(Resultado r) {
        System.out.println("diagnostico execucao: classe=" + r.classe() + " motivo=" + r.motivo() + " sqlstate=" + r.sqlstate());
        DB.logDoServidor().lines().filter(l -> l.matches(".*(ERRO|ERROR|CONTEXT|STATEMENT).*")).filter(l -> !l.matches(".*parameters?:.*"))
                .forEach(l -> System.out.println("diagnostico pg: " + l));
    }
}
