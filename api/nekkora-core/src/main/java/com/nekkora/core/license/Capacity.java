package com.nekkora.core.license;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;

/**
 * Capacidade TECNICA das organizacoes do modelo por licenca (migration 066, decisao D2). Separada das licencas: atingir um
 * limite daqui nunca consome nem libera licenca, e a recusa tem motivo proprio.
 *
 * <p>A fila tem limite de pendentes: trabalho novo com a fila cheia e recusado ({@link #requireRoomInQueue}). O limite de
 * execucoes SIMULTANEAS nao recusa trabalho novo: a vaga e reservada quando o trabalho e autorizado a iniciar (na entrega a
 * maquina, pelo canal das maquinas), e sem vaga o trabalho espera na fila. O banco reconfere tudo por gatilho.
 */
public final class Capacity {

    private static final Logger LOG = LoggerFactory.getLogger(Capacity.class);

    private Capacity() {
    }

    /** Fila cheia: 409 capacity_pending_limit_reached. Modelo anterior (limite nulo): nada conferido. */
    public static void requireRoomInQueue(JdbcClient j, UUID org) {
        // a mesma trava dos gatilhos, antes de contar: duas solicitacoes simultaneas nao passam juntas do limite
        j.sql("SELECT public.capacity_lock(?)").param(org).query((rs, n) -> 0).single();
        boolean full = j.sql("SELECT pending_limit IS NOT NULL AND pending_used >= pending_limit FROM public.capacity_state(?)").param(org)
                .query(Boolean.class).single();
        if (full) {
            LOG.info("evento=acesso.negado regra=CAPACIDADE motivo={}", ApiError.CAPACITY_PENDING_LIMIT_REACHED.reason());
            throw new ApiException(ApiError.CAPACITY_PENDING_LIMIT_REACHED, String.valueOf(ApiError.CAPACITY_PENDING_LIMIT_REACHED.reason()));
        }
    }
}
