package com.nekkora.core.remote;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reconciliacao de uma tentativa que terminou como "resultado nao confirmado" (estado 13) - migration 058. Um lugar so para as
 * regras, usado pelo canal das maquinas e pelo das pessoas.
 *
 * <p>Tres fatos sobre o PROCESSO da tentativa, um por linha, nunca alterados:
 * <ul>
 * <li>{@link #ABSENT_BY_MACHINE}: a maquina confirmou que nao ha processo daquela tentativa em execucao nela;</li>
 * <li>{@link #MANUAL}: um ORG_ADMIN da organizacao declarou, com motivo e descricao da verificacao;</li>
 * <li>{@link #RUNNING_BY_MACHINE}: a maquina voltou e disse que ainda executa.</li>
 * </ul>
 *
 * <p>Regras: a REPETICAO de uma solicitacao fica bloqueada enquanto a ultima tentativa dela nao tiver uma das duas
 * reconciliacoes (maquina ou manual). A MAQUINA so deixa de estar indisponivel quando ELA confirmar - a declaracao manual
 * libera uma nova tentativa, nao a maquina antiga. Um novo contato, o fim do vinculo ou a passagem do tempo nao reconciliam nada.
 */
public final class AttemptReconciliation {

    public static final short ABSENT_BY_MACHINE = 1;
    public static final short MANUAL = 2;
    public static final short RUNNING_BY_MACHINE = 3;
    /** 060: o processo principal terminou e ha processo da tentativa vivo ("finalizando"). */
    public static final short ACTIVE_BY_MACHINE = 4;
    /** 060: a maquina nao tem como dizer se ha processo da tentativa vivo. */
    public static final short UNCERTAIN_BY_MACHINE = 5;

    private AttemptReconciliation() {
    }

    private static final String HAS = "EXISTS (SELECT 1 FROM public.remote_attempt_process_reports p WHERE p.attempt_id = %s.attempt_id AND p.kind %s)";

    /** A tentativa {@code alias} tem processo PENDENTE de confirmacao: nao confirmada (13), ou terminada com o fato 4 ou 5. */
    private static String pending(String alias) {
        return "(" + alias + ".state = 13 OR " + HAS.formatted(alias, "IN (4, 5)") + ")";
    }

    /**
     * A tentativa {@code alias} (de remote_attempts) BLOQUEIA a repeticao: tem processo pendente e ninguem o confirmou ausente
     * - nem a maquina (1), nem a declaracao de um ORG_ADMIN (2).
     */
    public static String unresolved(String alias) {
        return "(" + pending(alias) + " AND NOT " + HAS.formatted(alias, "IN (1, 2)") + ")";
    }

    /** O que a maquina segura: qualquer coisa, so "ainda executa", so "finalizando", ou ocupacao incerta. */
    public enum Hold {
        ANY, RUNNING, FINISHING, UNCERTAIN
    }

    /**
     * A maquina {@code agent} (alias de agents) tem tentativa com processo pendente que ELA ainda nao confirmou ausente. So a
     * confirmacao dela (1) libera: tempo, novo contato, fim do vinculo e a declaracao manual nao. Sem o fato 1 vale o mais grave:
     * incerta (5, ou nenhum relato) sobre finalizando (4) sobre ainda executa (3).
     */
    public static String machineHolds(String agent, Hold hold) {
        String five = HAS.formatted("u", "= 5");
        String more = switch (hold) {
            case ANY -> "";
            case RUNNING -> " AND u.state = 13 AND " + HAS.formatted("u", "= 3") + " AND NOT " + HAS.formatted("u", "IN (4, 5)");
            case FINISHING -> " AND " + HAS.formatted("u", "= 4") + " AND NOT " + five;
            case UNCERTAIN -> " AND (" + five + " OR NOT " + HAS.formatted("u", "IN (3, 4)") + ")";
        };
        return "EXISTS (SELECT 1 FROM public.remote_attempts u WHERE u.organization_id = " + agent + ".organization_id AND u.agent_id = " + agent
                + ".agent_id AND " + pending("u") + " AND NOT " + HAS.formatted("u", "= 1") + more + ")";
    }

    /**
     * Grava um fato informado pela MAQUINA (1, 3, 4 ou 5), uma vez por tipo. Depois de "ausente" nada mais e gravado. "Ainda
     * executa" (3) so numa tentativa nao confirmada.
     */
    public static int byMachine(JdbcClient j, UUID org, UUID attemptId, short kind) {
        if (kind != ABSENT_BY_MACHINE && kind != RUNNING_BY_MACHINE && kind != ACTIVE_BY_MACHINE && kind != UNCERTAIN_BY_MACHINE) {
            throw new IllegalArgumentException("tipo");
        }
        return j.sql("INSERT INTO public.remote_attempt_process_reports (attempt_id, kind, organization_id, agent_id) "
                        + "SELECT t.attempt_id, ?, t.organization_id, t.agent_id FROM public.remote_attempts t WHERE t.organization_id = ? AND t.attempt_id = ? "
                        + "AND t.state IN (7, 8, 9, 10, 13) AND (? <> 3 OR t.state = 13) AND NOT EXISTS (SELECT 1 FROM public.remote_attempt_process_reports p "
                        + "WHERE p.attempt_id = t.attempt_id AND p.kind IN (1, ?)) ON CONFLICT (attempt_id, kind) DO NOTHING")
                .param(kind).param(org).param(attemptId).param(kind).param(kind).update();
    }

    /**
     * O fato a gravar quando a maquina informa {@code wanted} (o estado, 1 a 13) para uma tentativa que esta em {@code state},
     * dizendo dos processos {@code tree} (ABSENT, ACTIVE, UNCERTAIN ou nulo - o aplicativo antigo nao diz nada). 0: nada.
     */
    public static short fact(short state, short wanted, String tree) {
        boolean ended = wanted >= 7 && wanted <= 10 || wanted == 13;
        if (!ended) {
            return wanted == 6 && state == 13 ? RUNNING_BY_MACHINE : 0;
        }
        if ("ABSENT".equals(tree)) {
            return ABSENT_BY_MACHINE;
        }
        if ("ACTIVE".equals(tree)) {
            return ACTIVE_BY_MACHINE;
        }
        if ("UNCERTAIN".equals(tree)) {
            return UNCERTAIN_BY_MACHINE;
        }
        // sem dizer nada: numa tentativa nao confirmada, informar o fim sem confirmar os processos e nao saber; numa execucao
        // normal, e o aplicativo antigo - nada muda
        return state == 13 ? UNCERTAIN_BY_MACHINE : 0;
    }
}
