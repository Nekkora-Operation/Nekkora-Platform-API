package com.nekkora.platform.admin;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nekkora.core.artifact.ResultPurge;
import com.nekkora.core.db.OrgTransactions;

/**
 * Retencao, no servidor, dos registros e dos arquivos de resultado das execucoes remotas (057). O unico componente que recebe o
 * {@link ResultPurge}: mais ninguem remove bytes de resultado.
 *
 * <p>O prazo (em dias) conta do FIM DA ENTREGA de cada tentativa e tipo - a maquina fechou o envio, avisou que perdeu o que
 * guardava, ou o vinculo sob o qual a tentativa rodou foi encerrado. Entrega aberta nao vence. Cada ciclo, por organizacao:
 *
 * <ol>
 * <li><b>registra o que venceu</b>: uma linha por tentativa e tipo. O banco confere o fato e calcula as datas e as contagens;</li>
 * <li><b>remove o conteudo</b>: o texto das linhas e apagado (a linha fica); os bytes de cada arquivo sao removidos do
 * armazenamento e o arquivo e marcado como removido (o nome, o tamanho e o SHA-256 ficam);</li>
 * <li><b>confirma</b>: so quando nao sobra conteudo (o banco confere).</li>
 * </ol>
 *
 * <p><b>Repetir e seguro.</b> Um vencimento registrado e nao confirmado e retomado no ciclo seguinte. A remocao dos bytes nao e
 * transacional com o banco: se a marca nao for gravada depois de os bytes sairem, o ciclo seguinte acha os bytes ausentes (ja e
 * "removido") e grava a marca. Os bytes sao guardados por organizacao e SHA-256: enquanto outro resultado ainda presente usar os
 * mesmos bytes, eles ficam; a conferencia e a remocao acontecem sob a mesma trava que o recebimento de um resultado usa.
 *
 * <p>Nunca toca na solicitacao, na tentativa, nos fechamentos, nos avisos de perda nem na trilha: historico e auditoria ficam.
 */
public final class RemoteRetention {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteRetention.class);
    static final int BATCH = 50;

    private final OrgTransactions tx;
    private final ResultPurge purge;
    private final int days;
    private final int stalledDays;

    /** {@code days}: de 1 a 3650 (o banco guarda o mesmo piso e o mesmo teto). O teto sem progresso fica igual ao prazo. */
    public RemoteRetention(OrgTransactions tx, ResultPurge purge, int days) {
        this(tx, purge, days, days);
    }

    /**
     * {@code stalledDays} (059): o TETO da entrega que nao se conclui - dias sem progresso de entrega (termino da tentativa ou
     * ultimo item recebido), so para tentativa terminada e nao incerta. A presenca da maquina nao renova. Vencido, o conteudo sai
     * como nos outros casos e o registro diz "entrega incompleta" (NO_PROGRESS): nenhum recebimento e indicado.
     */
    public RemoteRetention(OrgTransactions tx, ResultPurge purge, int days, int stalledDays) {
        this.tx = tx;
        this.purge = purge;
        this.days = Math.max(1, Math.min(days, 3650));
        this.stalledDays = Math.max(1, Math.min(stalledDays, 3650));
    }

    public int stalledDays() {
        return stalledDays;
    }

    public int days() {
        return days;
    }

    /** O que um ciclo fez. {@code pending}: vencimentos que continuam sem confirmacao (serao retomados). */
    public record Cycle(int organizations, int expired, int linesRedacted, int filesRemoved, int confirmed, int pending, int failed) {
    }

    private record Open(UUID attempt, short kind) {
    }

    private record File(short number, String sha) {
    }

    public Cycle cycle() {
        List<UUID> orgs = tx.withoutContext(j -> j.sql("SELECT organization_id FROM public.remote_execution_organizations ORDER BY organization_id")
                .query(UUID.class).list());
        int[] n = new int[6];
        for (UUID org : orgs) {
            try {
                organization(org, n);
            } catch (RuntimeException e) {
                n[5]++;
                LOG.warn("evento=execucao_remota.retencao_falhou org={} causa={}", org, e.getClass().getSimpleName());
            }
        }
        if (n[0] + n[1] + n[2] + n[3] + n[5] > 0) {
            LOG.info("evento=execucao_remota.retencao vencidos={} linhas_apagadas={} arquivos_removidos={} confirmados={} pendentes={} falhas={}", n[0], n[1],
                    n[2], n[3], n[4], n[5]);
        }
        return new Cycle(orgs.size(), n[0], n[1], n[2], n[3], n[4], n[5]);
    }

    private void organization(UUID org, int[] n) {
        // 1. registra o que venceu (o banco reconfere o fato e o prazo; quem perde a corrida para outra instancia nao grava nada)
        for (short kind = 1; kind <= 2; kind++) {
            short k = kind;
            String content = k == 1 ? "public.remote_attempt_log_lines c WHERE c.attempt_id = t.attempt_id AND c.redacted_at IS NULL"
                    : "public.remote_attempt_results c WHERE c.attempt_id = t.attempt_id AND c.removed_at IS NULL";
            n[0] += tx.inOrganization(org, OrgTransactions.Mode.READ_WRITE, j -> j.sql("""
                    INSERT INTO public.remote_attempt_content_expirations (attempt_id, kind, organization_id, delivery_ended_at, delivery_end,
                        retention_days, items, bytes)
                    SELECT d.attempt_id, ?, ?, d.ended_at, d.how, CASE WHEN d.how = 4 THEN ? ELSE ? END, 0, 0
                      FROM (SELECT t.attempt_id, e.ended_at, e.how
                              FROM public.remote_attempts t, LATERAL public.remote_attempt_delivery_ended_at(t.organization_id, t.attempt_id, ?::smallint) e
                             WHERE t.organization_id = ? AND t.started_at IS NOT NULL
                               AND EXISTS (SELECT 1 FROM %s)
                               AND NOT EXISTS (SELECT 1 FROM public.remote_attempt_content_expirations x WHERE x.attempt_id = t.attempt_id AND x.kind = ?)) d
                     WHERE d.ended_at IS NOT NULL AND d.ended_at + make_interval(days => CASE WHEN d.how = 4 THEN ? ELSE ? END) <= clock_timestamp()
                     ORDER BY d.ended_at LIMIT ?
                    ON CONFLICT (attempt_id, kind) DO NOTHING
                    """.formatted(content)).param(k).param(org).param(stalledDays).param(days).param(k).param(org).param(k).param(stalledDays).param(days).param(BATCH).update());
        }
        // 2 e 3. remove o conteudo do que esta registrado e ainda nao confirmado (inclusive o que ficou de um ciclo anterior)
        List<Open> open = tx.inOrganization(org, OrgTransactions.Mode.READ_ONLY, j -> j.sql("SELECT attempt_id, kind FROM "
                        + "public.remote_attempt_content_expirations WHERE organization_id = ? AND confirmed_at IS NULL ORDER BY expired_at LIMIT ?")
                .param(org).param(BATCH).query((rs, i) -> new Open(rs.getObject(1, UUID.class), rs.getShort(2))).list());
        for (Open o : open) {
            boolean clean;
            if (o.kind() == 1) {
                n[1] += tx.inOrganization(org, OrgTransactions.Mode.READ_WRITE, j -> j.sql("UPDATE public.remote_attempt_log_lines SET message = '', "
                        + "redacted_at = clock_timestamp() WHERE organization_id = ? AND attempt_id = ? AND redacted_at IS NULL").param(org).param(o.attempt())
                        .update());
                clean = true;
            } else {
                clean = files(org, o.attempt(), n);
            }
            if (clean && tx.inOrganization(org, OrgTransactions.Mode.READ_WRITE, j -> j.sql("UPDATE public.remote_attempt_content_expirations "
                    + "SET confirmed_at = clock_timestamp() WHERE organization_id = ? AND attempt_id = ? AND kind = ? AND confirmed_at IS NULL").param(org)
                    .param(o.attempt()).param(o.kind()).update()) > 0) {
                n[3]++;
            } else if (!clean) {
                n[4]++;
            }
        }
    }

    /** Remove os bytes de cada arquivo ainda presente e grava a marca. Falso: algum ficou para o ciclo seguinte. */
    private boolean files(UUID org, UUID attempt, int[] n) {
        List<File> files = tx.inOrganization(org, OrgTransactions.Mode.READ_ONLY, j -> j.sql("SELECT result_number, sha256 FROM "
                        + "public.remote_attempt_results WHERE organization_id = ? AND attempt_id = ? AND removed_at IS NULL ORDER BY result_number").param(org)
                .param(attempt).query((rs, i) -> new File(rs.getShort(1), rs.getString(2))).list());
        boolean all = true;
        for (File f : files) {
            try {
                n[2] += tx.inOrganization(org, OrgTransactions.Mode.READ_WRITE, j -> {
                    // a mesma trava do recebimento de um resultado com estes bytes: ninguem grava a linha entre a conferencia e a remocao
                    j.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 57))").param(org + "|" + f.sha()).query(rs -> {
                    });
                    boolean shared = j.sql("SELECT count(*) FROM public.remote_attempt_results WHERE organization_id = ? AND sha256 = ? AND removed_at IS NULL "
                                    + "AND NOT (attempt_id = ? AND result_number = ?)").param(org).param(f.sha()).param(attempt).param(f.number())
                            .query(Long.class).single() > 0;
                    if (!shared) {
                        try {
                            purge.remove(org, f.sha());
                        } catch (IOException e) {
                            throw new IllegalStateException("bytes nao removidos", e);
                        }
                    }
                    return j.sql("UPDATE public.remote_attempt_results SET removed_at = clock_timestamp() WHERE organization_id = ? AND attempt_id = ? "
                            + "AND result_number = ? AND removed_at IS NULL").param(org).param(attempt).param(f.number()).update();
                });
            } catch (RuntimeException e) {
                all = false;
                LOG.warn("evento=execucao_remota.retencao_arquivo_pendente tentativa={} numero={} causa={}", attempt, f.number(), e.getClass().getSimpleName());
            }
        }
        return all;
    }
}
