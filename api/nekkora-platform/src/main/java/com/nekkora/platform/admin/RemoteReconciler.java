package com.nekkora.platform.admin;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.remote.AttemptClosure;

/**
 * Reconciliacao PERIODICA das tentativas de execucao remota (055): o estado nao depende de a maquina voltar nem de alguem
 * abrir a tela. Um ciclo agendado dentro da propria Platform (o mesmo mecanismo do despacho de e-mail), sem servico novo.
 *
 * <p>Cada ciclo le a lista das organizacoes que ja tiveram tentativa (unica leitura sem contexto; so identificadores) e entra em
 * cada uma, numa transacao propria, aplicando as MESMAS regras da leitura e do canal das maquinas ({@link AttemptClosure}): oferta
 * vencida vira EXPIRED; tentativa de maquina que nao esta mais vinculada, ou recebida e sem comunicacao alem da espera, e
 * encerrada sem desfecho inventado. O banco confere o fato antes de aceitar.
 *
 * <p>Repetir e seguro: cada regra so alcanca tentativa ainda nao terminada. Com mais de uma instancia da Platform, a segunda
 * simplesmente nao acha o que encerrar. A falha numa organizacao nao interrompe as outras.
 */
public final class RemoteReconciler {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteReconciler.class);

    private final OrgTransactions tx;
    private final int lostMinutes;

    public RemoteReconciler(OrgTransactions tx, int lostMinutes) {
        this.tx = tx;
        this.lostMinutes = AttemptClosure.lostMinutes(lostMinutes);
    }

    /** O que um ciclo encerrou. */
    public record Cycle(int organizations, int expired, int unlinked, int lost, int failed) {
    }

    public Cycle cycle() {
        List<UUID> orgs = tx.withoutContext(j -> j.sql("SELECT organization_id FROM public.remote_execution_organizations ORDER BY organization_id")
                .query(UUID.class).list());
        int expired = 0;
        int unlinked = 0;
        int lost = 0;
        int failed = 0;
        for (UUID org : orgs) {
            try {
                int[] n = tx.inOrganization(org, OrgTransactions.Mode.READ_WRITE, j -> new int[] {AttemptClosure.expiredOffers(j, org),
                        AttemptClosure.orphans(j, org, null, null), AttemptClosure.lost(j, org, null, null, null, lostMinutes)});
                expired += n[0];
                unlinked += n[1];
                lost += n[2];
            } catch (RuntimeException e) {
                failed++;
                LOG.warn("evento=execucao_remota.reconciliacao_falhou org={} causa={}", org, e.getClass().getSimpleName());
            }
        }
        if (expired + unlinked + lost > 0) {
            LOG.info("evento=execucao_remota.reconciliacao_periodica oferta_vencida={} maquina_desvinculada={} sem_comunicacao={}", expired, unlinked, lost);
        }
        return new Cycle(orgs.size(), expired, unlinked, lost, failed);
    }
}
