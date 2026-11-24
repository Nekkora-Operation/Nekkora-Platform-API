package com.nekkora.platform.admin;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;

/**
 * AMBIENTE-PYTHON-001 (D7): o registro do PREPARO do ambiente por execucao vale 30 dias contados do relatorio da maquina -
 * comece ou nao a automacao (o preparo que falhou e deixou a tentativa "nao iniciada" segue a MESMA regra). Vencido, o texto
 * e apagado (a linha fica, com {@code log_expired_at}); o resumo estruturado da falha (dependencia, fase, resumo curto) e o que
 * foi instalado continuam. O banco confere o prazo: o gatilho da 076 recusa apagar antes dos 30 dias.
 *
 * <p>Independente da retencao de registros e resultados da 057 (que e opcional e conta do fim da entrega): esta vale sempre que
 * o recurso esta ligado. Repetir e seguro.
 */
public final class ManagedEnvironmentRetention {

    private static final Logger LOG = LoggerFactory.getLogger(ManagedEnvironmentRetention.class);
    static final int DAYS = 30;

    private final OrgTransactions tx;

    public ManagedEnvironmentRetention(OrgTransactions tx) {
        this.tx = tx;
    }

    /** Quantos registros de preparo foram apagados neste ciclo. */
    public int cycle() {
        List<UUID> orgs = tx.withoutContext(j -> j.sql("SELECT organization_id FROM public.remote_execution_organizations ORDER BY organization_id")
                .query(UUID.class).list());
        int removed = 0;
        for (UUID org : orgs) {
            removed += tx.inOrganization(org, Mode.READ_WRITE, j -> j.sql("UPDATE public.remote_attempt_environment_reports SET preparation_log = NULL "
                    + "WHERE organization_id = ? AND preparation_log IS NOT NULL AND reported_at <= clock_timestamp() - make_interval(days => ?)")
                    .param(org).param(DAYS).update());
        }
        if (removed > 0) {
            LOG.info("evento=ambiente_gerenciado.registros_do_preparo_removidos quantidade={}", removed);
        }
        return removed;
    }
}
