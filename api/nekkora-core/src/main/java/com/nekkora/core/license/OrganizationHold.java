package com.nekkora.core.license;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;

/**
 * NEKKORA-LICENCIAMENTO-002 (migration 075) - o BLOQUEIO DA NEKKORA, num lugar so. Quem decide e o banco
 * ({@code organization_held}: ha bloqueio nao levantado); os servicos so enxergam a organizacao do contexto e so as colunas
 * organization_id e lifted_at. Os gatilhos da 075 reconferem na gravacao: um caminho que esqueca de chamar esta classe e
 * recusado pelo banco (e o {@code GenericErrorHandler} responde a mesma recusa), nao admitido.
 *
 * <p>Bloqueada: trabalho e operacoes NOVOS sao recusados com 403 {@code organization_held}; o que ja comecou termina e
 * entrega; a leitura continua; so o suporte da Nekkora levanta (procedimento do operador).
 */
public final class OrganizationHold {

    private static final Logger LOG = LoggerFactory.getLogger(OrganizationHold.class);

    private OrganizationHold() {
    }

    /** A organizacao do contexto esta bloqueada pela Nekkora. Exige o contexto {@code nekkora.organization_id} = org. */
    public static boolean held(JdbcClient j, UUID org) {
        return Boolean.TRUE.equals(j.sql("SELECT public.organization_held(?)").param(org).query(Boolean.class).single());
    }

    /** Recusa a operacao nova se a organizacao estiver bloqueada: 403 organization_held, e nada e gravado. */
    public static void requireNotHeld(JdbcClient j, UUID org) {
        if (held(j, org)) {
            LOG.info("evento=acesso.negado regra=BLOQUEIO_NEKKORA motivo=organization_held");
            throw new ApiException(ApiError.ORGANIZATION_HELD, "bloqueio_nekkora");
        }
    }
}
