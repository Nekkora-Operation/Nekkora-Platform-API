package com.nekkora.core.license;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;

/**
 * PLATFORM-LICENCIAMENTO-001 (E3, migration 064) - a conferencia de licenca, num lugar so. Quem decide e o BANCO
 * ({@code license_gate}, {@code license_coverage_end}, {@code license_run_basis}); aqui a resposta vira o erro do contrato
 * antes de a gravacao ser tentada. Os gatilhos da 064 reconferem na gravacao: um caminho que esqueca de chamar esta classe e
 * recusado pelo banco, nao admitido.
 *
 * <p>Organizacao no modelo anterior: tudo aqui responde "nao se aplica" e nada muda.
 */
public final class LicenseGate {

    private static final Logger LOG = LoggerFactory.getLogger(LicenseGate.class);

    private LicenseGate() {
    }

    /** LEGACY | OK | LICENSE_EXPIRED | LICENSE_REQUIRED, sem trava (leituras e telas). */
    public static String state(JdbcClient j, UUID org, UUID automation) {
        return j.sql("SELECT public.license_state(?, ?)").param(org).param(automation).query(String.class).single();
    }

    /** A organizacao esta no modelo por licenca. */
    public static boolean licensedModel(JdbcClient j, UUID org) {
        return j.sql("SELECT public.license_model(?) = 2").param(org).query(Boolean.class).single();
    }

    /**
     * Admite trabalho NOVO da automacao (solicitacao, tentativa, pedido, autorizacao): sob a trava compartilhada do modelo da
     * organizacao. Sem licenca vigente no modelo por licenca: 409 license_required ou license_expired, e nada e gravado.
     */
    public static void requireForNewWork(JdbcClient j, UUID org, UUID automation) {
        String state = j.sql("SELECT public.license_gate(?, ?)").param(org).param(automation).query(String.class).single();
        if ("LICENSE_EXPIRED".equals(state)) {
            LOG.info("evento=acesso.negado regra=LICENCA motivo=license_expired");
            throw new ApiException(ApiError.LICENSE_EXPIRED, "licenca_vencida");
        }
        if ("LICENSE_REQUIRED".equals(state)) {
            LOG.info("evento=acesso.negado regra=LICENCA motivo=license_required");
            throw new ApiException(ApiError.LICENSE_REQUIRED, "sem_licenca");
        }
    }

    /** Ate quando a cobertura de agora segue sem lacuna entre periodos (D3). Nulo: sem cobertura. */
    public static OffsetDateTime coverageEnd(JdbcClient j, UUID org, UUID automation) {
        return j.sql("SELECT public.license_coverage_end(?, ?, clock_timestamp())").param(org).param(automation).query(OffsetDateTime.class).optional()
                .orElse(null);
    }

    /**
     * Registro de execucao ou de sessao de monitoramento pelo Agent anterior (D8). Aceito quando a autorizacao DO SERVIDOR valia
     * no inicio informado e ha licenca vigente naquele inicio, ou a autorizacao e anterior ao modelo por licenca (transicao).
     * Fora disso: 403 com o motivo, e nada e gravado. A base aceita e gravada pelo banco em {@code agent_runs.license_basis}.
     */
    public static void requireRunCovered(JdbcClient j, UUID org, UUID automation, UUID authorization, OffsetDateTime start) {
        Integer basis = j.sql("SELECT public.license_run_basis(?, ?, ?, ?)::int").param(org).param(automation).param(authorization).param(start)
                .query(Integer.class).optional().orElse(null);
        if (basis == null || basis > 0) {
            return;
        }
        ApiError error = basis == -1 ? ApiError.RUN_NOT_COVERED : basis == -3 ? ApiError.RUN_LICENSE_EXPIRED : ApiError.RUN_LICENSE_REQUIRED;
        LOG.info("evento=acesso.negado regra=LICENCA motivo={}", error.reason());
        throw new ApiException(error, String.valueOf(error.reason()));
    }
}
