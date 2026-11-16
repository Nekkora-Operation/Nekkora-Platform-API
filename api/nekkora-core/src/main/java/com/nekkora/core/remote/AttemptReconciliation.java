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

    private AttemptReconciliation() {
    }

    private static final String HAS = "EXISTS (SELECT 1 FROM public.remote_attempt_process_reports p WHERE p.attempt_id = %s.attempt_id AND p.kind %s)";

    /** A tentativa {@code alias} (de remote_attempts) esta SEM reconciliacao: nao confirmada, e ninguem confirmou o processo. */
    public static String unresolved(String alias) {
        return "(" + alias + ".state = 13 AND NOT " + HAS.formatted(alias, "IN (1, 2)") + ")";
    }

    /**
     * A maquina {@code agent} (alias de agents) tem tentativa nao confirmada cujo processo ELA ainda nao confirmou ausente.
     * {@code running}: so as que ela disse ainda executar (ocupada); senao, todas (ocupacao incerta ou ocupada).
     */
    public static String machineHolds(String agent, boolean running) {
        return "EXISTS (SELECT 1 FROM public.remote_attempts u WHERE u.organization_id = " + agent + ".organization_id AND u.agent_id = " + agent
                + ".agent_id AND u.state = 13 AND NOT " + HAS.formatted("u", "= 1") + (running ? " AND " + HAS.formatted("u", "= 3") : "") + ")";
    }

    /** Grava um fato informado pela MAQUINA (1 ou 3), uma vez. Depois de "ausente", "ainda executa" nao e mais gravado. */
    public static int byMachine(JdbcClient j, UUID org, UUID attemptId, short kind) {
        if (kind != ABSENT_BY_MACHINE && kind != RUNNING_BY_MACHINE) {
            throw new IllegalArgumentException("tipo");
        }
        return j.sql("INSERT INTO public.remote_attempt_process_reports (attempt_id, kind, organization_id, agent_id) "
                        + "SELECT t.attempt_id, ?, t.organization_id, t.agent_id FROM public.remote_attempts t WHERE t.organization_id = ? AND t.attempt_id = ? "
                        + "AND t.state = 13 AND NOT EXISTS (SELECT 1 FROM public.remote_attempt_process_reports p WHERE p.attempt_id = t.attempt_id "
                        + "AND p.kind IN (1, ?)) ON CONFLICT (attempt_id, kind) DO NOTHING")
                .param(kind).param(org).param(attemptId).param(kind).update();
    }
}
