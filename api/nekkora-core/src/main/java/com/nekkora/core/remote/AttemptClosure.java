package com.nekkora.core.remote;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Encerramento de uma tentativa de execucao remota PELO SERVIDOR (053), quando a maquina nao vai mais (ou nao pode mais)
 * informar o desfecho. Um lugar so, usado pelo canal das maquinas e pelo das pessoas, sempre dentro da transacao de quem chama.
 *
 * <p>O servidor grava so o que SABE. Tentativa que ele nunca autorizou a iniciar (da fila ate a preparacao): REFUSED, "nao
 * iniciou", com o motivo. Tentativa autorizada a iniciar ou em execucao: ENDED_UNKNOWN - nunca sucesso, falha nem
 * cancelamento. O banco confere o fato (vinculo encerrado, ou prazo da comunicacao vencido) antes de aceitar a causa.
 */
public final class AttemptClosure {

    public static final String MACHINE_UNLINKED = "MACHINE_UNLINKED";
    public static final String MACHINE_REVOKED = "MACHINE_REVOKED";
    public static final String COMMUNICATION_LOST = "COMMUNICATION_LOST";

    /** Espera padrao depois do prazo da comunicacao vencido (minutos): o dobro do tempo maximo de uma tentativa. */
    public static final int DEFAULT_LOST_MINUTES = 120;

    private AttemptClosure() {
    }

    /** Entre 1 minuto e 30 dias. */
    public static int lostMinutes(int configured) {
        return Math.max(1, Math.min(configured, 30 * 24 * 60));
    }

    private static final String SET = """
            UPDATE public.remote_attempts t
               SET state = CASE WHEN t.state <= 4 THEN 11 ELSE 13 END,
                   refusal_reason = CASE WHEN t.state <= 4 THEN %s END,
                   closure_cause = %s
             WHERE t.organization_id = ?
            """;

    private static final String NOT_LINKED = """
            NOT EXISTS (SELECT 1 FROM public.agents a
                          JOIN public.worker_agent_assignments wa ON wa.organization_id = a.organization_id AND wa.agent_id = a.agent_id
                                                                  AND wa.worker_id = t.worker_id AND wa.status = 1
                         WHERE a.organization_id = t.organization_id AND a.agent_id = t.agent_id AND a.status = 2)
            """;

    /**
     * O vinculo DESTA maquina acabou de ser encerrado nesta transacao (depois de a associacao e o Agent mudarem): toda tentativa
     * nao terminada dela e encerrada. {@code cause}: {@link #MACHINE_UNLINKED} ou {@link #MACHINE_REVOKED}.
     */
    public static int linkEnded(JdbcClient j, UUID org, UUID agentId, String cause) {
        if (!MACHINE_UNLINKED.equals(cause) && !MACHINE_REVOKED.equals(cause)) {
            throw new IllegalArgumentException("causa");
        }
        return j.sql(SET.formatted("'MACHINE_NOT_LINKED'", "?") + " AND t.agent_id = ? AND t.state BETWEEN 1 AND 6")
                .param(cause).param(org).param(agentId).update();
    }

    /**
     * Tentativas nao terminadas cuja maquina NAO esta mais vinculada (o encerramento do vinculo aconteceu antes da 053, ou por
     * um caminho que nao passou por {@link #linkEnded}). A causa vem de como o vinculo acabou: ha registro do desvinculo pela
     * propria maquina, ou foi revogada por quem administra. {@code executionId} e {@code agentId}: filtros opcionais.
     */
    public static int orphans(JdbcClient j, UUID org, UUID executionId, UUID agentId) {
        String cause = "CASE WHEN EXISTS (SELECT 1 FROM public.agent_unlink_operations u WHERE u.organization_id = t.organization_id "
                + "AND u.agent_id = t.agent_id) THEN 'MACHINE_UNLINKED' ELSE 'MACHINE_REVOKED' END";
        return j.sql(SET.formatted("'MACHINE_NOT_LINKED'", cause) + " AND t.state BETWEEN 1 AND 6 AND (?::uuid IS NULL OR t.remote_execution_id = ?) "
                        + "AND (?::uuid IS NULL OR t.agent_id = ?) AND " + NOT_LINKED)
                .param(org).param(executionId).param(executionId).param(agentId).param(agentId).update();
    }

    /**
     * Tentativas recebidas pela maquina cujo prazo da comunicacao venceu ha mais de {@code minutes}: nao ha evidencia de como
     * terminaram (ou se iniciaram). Filtros opcionais: a solicitacao, a maquina, a tentativa.
     */
    public static int lost(JdbcClient j, UUID org, UUID executionId, UUID agentId, UUID attemptId, int minutes) {
        return j.sql(SET.formatted("'COMMUNICATION_LOST'", "'COMMUNICATION_LOST'") + " AND t.state BETWEEN 3 AND 6 AND t.lease_expires_at IS NOT NULL "
                        + "AND t.lease_expires_at <= clock_timestamp() - make_interval(mins => ?) AND (?::uuid IS NULL OR t.remote_execution_id = ?) "
                        + "AND (?::uuid IS NULL OR t.agent_id = ?) AND (?::uuid IS NULL OR t.attempt_id = ?)")
                .param(org).param(lostMinutes(minutes)).param(executionId).param(executionId).param(agentId).param(agentId).param(attemptId).param(attemptId)
                .update();
    }

    /** Ha, na organizacao (ou so nestas solicitacoes), alguma tentativa que {@link #orphans} ou {@link #lost} encerrariam? Leitura. */
    public static boolean pending(JdbcClient j, UUID org, UUID[] executions, int minutes) {
        return j.sql("SELECT EXISTS (SELECT 1 FROM public.remote_attempts t WHERE t.organization_id = ? AND t.state BETWEEN 1 AND 6 "
                        + "AND (?::uuid[] IS NULL OR t.remote_execution_id = ANY (?)) AND ((t.state >= 3 AND t.lease_expires_at IS NOT NULL "
                        + "AND t.lease_expires_at <= clock_timestamp() - make_interval(mins => ?)) OR " + NOT_LINKED + "))")
                .param(org).param(executions).param(executions).param(lostMinutes(minutes)).query(Boolean.class).single();
    }

    /** QUEUED/OFFERED que a maquina nao recebeu dentro do prazo da oferta viram EXPIRED. Da organizacao inteira. */
    public static int expiredOffers(JdbcClient j, UUID org) {
        return j.sql("UPDATE public.remote_attempts SET state = 12 WHERE organization_id = ? AND state IN (1, 2) AND offer_valid_until <= clock_timestamp()")
                .param(org).update();
    }
}
