package com.nekkora.agentgateway;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.agentgateway.MachineAuth.Machine;
import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContext;

/**
 * Ingestao do envelope OBS 1.0.0 (contrato, sec. 8). Um lote = uma transacao: autenticacao, trava da execucao (serializa os
 * lotes da mesma execucao e o calculo do ACK), insercao idempotente por (execucao, sequence), conferencia do conteudo
 * (DUPLICATE x CONFLICT), declaracoes de ausencia e projecao da entrega. O ACK so existe depois do commit: o controlador
 * so responde 200 quando esta transacao confirmou. Falha antes disso = 5xx sem ACK; o Agent reenvia os MESMOS fatos.
 */
public final class EventIngestion {

    private static final Logger LOG = LoggerFactory.getLogger(EventIngestion.class);
    public static final int MAX_EVENTS = 500;
    public static final int MAX_MISSING_RANGES = 100;
    public static final long MAX_EVENTS_PER_RUN = 25_000;
    static final int MAX_REFUSALS_RECORDED = 20;

    public enum ItemStatus { STORED, DUPLICATE, CONFLICT, INVALID }

    public record ItemResult(Long sequence, ItemStatus status, String reason) {
    }

    public record Range(long from, long to) {
    }

    public record MissingResult(long from, long to, ItemStatus status, String reason) {
    }

    public record Ack(UUID runId, long storedThrough, long settledThrough, List<ItemResult> results, List<MissingResult> missing) {
    }

    private final OrgTransactions tx;
    private final MachineAuth auth;
    private final JdbcTemplate jdbc;

    public EventIngestion(OrgTransactions tx, MachineAuth auth, JdbcTemplate jdbc) {
        this.tx = tx;
        this.auth = auth;
        this.jdbc = jdbc;
    }

    private record Run(UUID id, long storedThrough, long settledThrough, long maxSequence, long eventsStored, String frameworkId) {
    }

    /** {@code afterAuth}: chamado com a maquina ja autenticada, antes de qualquer escrita (limite por organizacao). */
    public Ack ingest(MachineToken t, String agentExecutionId, List<ObsEnvelope.Result> items, List<Range> missing,
                      java.util.function.Consumer<Machine> afterAuth) {
        if (items.size() > MAX_EVENTS || missing.size() > MAX_MISSING_RANGES) {
            throw ApiException.invalid("lote_grande");
        }
        for (Range r : missing) {
            if (r.from() < 1 || r.to() < r.from() || r.to() > ObsEnvelope.MAX_SEQUENCE) {
                throw ApiException.invalid("ausencia");
            }
        }
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = auth.authenticate(j, t);
            afterAuth.accept(m);
            Run run = j.sql("""
                    SELECT agent_run_id, stored_through, settled_through, max_sequence, events_stored, framework_execution_id
                      FROM public.agent_runs WHERE organization_id = ? AND agent_id = ? AND agent_execution_id = ? FOR UPDATE
                    """).param(m.organization()).param(m.agentId()).param(agentExecutionId)
                    .query((rs, n) -> new Run(rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getString(6)))
                    .optional().orElseThrow(() -> new ApiException(ApiError.NOT_FOUND, "execucao"));
            return apply(j, m, run, items, missing);
        });
    }

    private Ack apply(JdbcClient j, Machine m, Run run, List<ObsEnvelope.Result> items, List<Range> missing) {
        List<ItemResult> results = new ArrayList<>(items.size());
        // dentro do lote: a primeira ocorrencia de cada sequence vale; repeticao igual = DUPLICATE, diferente = CONFLICT
        Map<Long, ObsEnvelope.Valid> candidates = new LinkedHashMap<>();
        Map<Integer, Long> positionOfSequence = new HashMap<>();
        int invalid = 0;
        List<ItemResult> refusalsToRecord = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ObsEnvelope.Result r = items.get(i);
            if (r.invalid() != null) {
                results.add(new ItemResult(r.invalid().sequence(), ItemStatus.INVALID, r.invalid().reason()));
                invalid++;
                continue;
            }
            ObsEnvelope.Valid v = r.valid();
            ObsEnvelope.Valid first = candidates.get(v.sequence());
            if (first == null) {
                candidates.put(v.sequence(), v);
                positionOfSequence.put(i, v.sequence());
                results.add(null); // decidido depois da insercao
            } else {
                boolean same = first.sha256().equals(v.sha256());
                results.add(new ItemResult(v.sequence(), same ? ItemStatus.DUPLICATE : ItemStatus.CONFLICT, same ? null : "conflito_no_lote"));
                if (!same) {
                    refusalsToRecord.add(new ItemResult(v.sequence(), ItemStatus.CONFLICT, "sequence_conflict_in_batch"));
                }
            }
        }
        if (run.eventsStored() + candidates.size() > MAX_EVENTS_PER_RUN) {
            throw new ApiException(ApiError.CONFLICT, "limite_por_execucao");
        }
        // sequences ja declaradas ausentes nao recebem evento depois (a declaracao e um fato do Agent)
        List<Range> declared = j.sql("SELECT seq_from, seq_to FROM public.agent_run_missing WHERE agent_run_id = ? ORDER BY seq_from")
                .param(run.id()).query((rs, n) -> new Range(rs.getLong(1), rs.getLong(2))).list();

        List<ObsEnvelope.Valid> toInsert = new ArrayList<>();
        Map<Long, ItemResult> decided = new HashMap<>();
        for (ObsEnvelope.Valid v : candidates.values()) {
            if (covered(declared, v.sequence())) {
                decided.put(v.sequence(), new ItemResult(v.sequence(), ItemStatus.CONFLICT, "declarada_ausente"));
                refusalsToRecord.add(new ItemResult(v.sequence(), ItemStatus.CONFLICT, "event_after_declared_missing"));
            } else {
                toInsert.add(v);
            }
        }
        int[] counts = toInsert.isEmpty() ? new int[0] : jdbc.batchUpdate("""
                INSERT INTO public.agent_run_events (organization_id, agent_run_id, sequence, content_sha256, envelope, source, event_code, severity,
                    category, step_id, studio_step_id, error_code, timestamp_utc, observed_utc)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (agent_run_id, sequence) DO NOTHING
                """, toInsert, toInsert.size(), (ps, v) -> {
            ps.setObject(1, m.organization());
            ps.setObject(2, run.id());
            ps.setLong(3, v.sequence());
            ps.setString(4, v.sha256());
            ps.setString(5, v.canonical());
            ps.setShort(6, v.source());
            ps.setString(7, v.eventCode());
            ps.setShort(8, v.severity());
            ps.setShort(9, v.category());
            ps.setString(10, v.stepId());
            ps.setObject(11, v.studioStepId());
            ps.setString(12, v.errorCode());
            ps.setObject(13, v.timestampUtc() == null ? null : v.timestampUtc().atOffset(ZoneOffset.UTC));
            ps.setObject(14, v.observedUtc().atOffset(ZoneOffset.UTC));
        })[0];
        List<ObsEnvelope.Valid> stored = new ArrayList<>();
        List<ObsEnvelope.Valid> notInserted = new ArrayList<>();
        for (int i = 0; i < toInsert.size(); i++) {
            // pgjdbc devolve a contagem por linha (1 inserida, 0 ja existia); SUCCESS_NO_INFO nao ocorre sem reWriteBatchedInserts
            (counts[i] == 1 ? stored : notInserted).add(toInsert.get(i));
        }
        if (!notInserted.isEmpty()) {
            Map<Long, String> existing = new HashMap<>();
            j.sql("SELECT sequence, content_sha256 FROM public.agent_run_events WHERE agent_run_id = ? AND sequence = ANY (?)")
                    .param(run.id()).param(notInserted.stream().map(ObsEnvelope.Valid::sequence).toArray(Long[]::new))
                    .query((rs, n) -> existing.put(rs.getLong(1), rs.getString(2))).list();
            for (ObsEnvelope.Valid v : notInserted) {
                boolean same = v.sha256().equals(existing.get(v.sequence()));
                decided.put(v.sequence(), new ItemResult(v.sequence(), same ? ItemStatus.DUPLICATE : ItemStatus.CONFLICT, same ? null : "conteudo_diferente"));
                if (!same) {
                    refusalsToRecord.add(new ItemResult(v.sequence(), ItemStatus.CONFLICT, "sequence_conflict"));
                    j.sql("INSERT INTO public.agent_ingestion_refusals (organization_id, agent_id, agent_run_id, kind, sequence, reason, existing_sha256, "
                                    + "offered_sha256) VALUES (?, ?, ?, 1, ?, 'sequence_conflict', ?, ?)")
                            .param(m.organization()).param(m.agentId()).param(run.id()).param(v.sequence()).param(existing.get(v.sequence()))
                            .param(v.sha256()).update();
                }
            }
        }
        for (ObsEnvelope.Valid v : stored) {
            decided.put(v.sequence(), new ItemResult(v.sequence(), ItemStatus.STORED, null));
        }
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) == null) {
                results.set(i, decided.get(positionOfSequence.get(i)));
            }
        }

        // declaracoes de ausencia: nunca sobre evento gravado; a mesma declaracao repetida e DUPLICATE
        List<MissingResult> missingResults = new ArrayList<>();
        long declaredCount = 0;
        long maxDeclared = 0;
        for (Range r : missing) {
            boolean storedInside = j.sql("SELECT EXISTS (SELECT 1 FROM public.agent_run_events WHERE agent_run_id = ? AND sequence BETWEEN ? AND ?)")
                    .param(run.id()).param(r.from()).param(r.to()).query(Boolean.class).single();
            Range same = declared.stream().filter(d -> d.from() == r.from() && d.to() == r.to()).findFirst().orElse(null);
            boolean overlaps = declared.stream().anyMatch(d -> d.from() <= r.to() && r.from() <= d.to());
            if (same != null) {
                missingResults.add(new MissingResult(r.from(), r.to(), ItemStatus.DUPLICATE, null));
            } else if (storedInside || overlaps) {
                missingResults.add(new MissingResult(r.from(), r.to(), ItemStatus.CONFLICT, storedInside ? "evento_gravado" : "sobreposicao"));
                j.sql("INSERT INTO public.agent_ingestion_refusals (organization_id, agent_id, agent_run_id, kind, sequence, reason) VALUES (?, ?, ?, 2, ?, ?)")
                        .param(m.organization()).param(m.agentId()).param(run.id()).param(r.from())
                        .param(storedInside ? "missing_over_stored_event" : "missing_overlap").update();
            } else {
                j.sql("INSERT INTO public.agent_run_missing (organization_id, agent_run_id, seq_from, seq_to) VALUES (?, ?, ?, ?)")
                        .param(m.organization()).param(run.id()).param(r.from()).param(r.to()).update();
                declared.add(r);
                declaredCount += r.to() - r.from() + 1;
                maxDeclared = Math.max(maxDeclared, r.to());
                missingResults.add(new MissingResult(r.from(), r.to(), ItemStatus.STORED, null));
            }
        }

        // recusas registradas (com teto por lote; a contagem total fica no registro)
        int recorded = 0;
        for (ItemResult x : refusalsToRecord) {
            if (recorded >= MAX_REFUSALS_RECORDED || "sequence_conflict".equals(x.reason())) {
                continue;
            }
            j.sql("INSERT INTO public.agent_ingestion_refusals (organization_id, agent_id, agent_run_id, kind, sequence, reason) VALUES (?, ?, ?, ?, ?, ?)")
                    .param(m.organization()).param(m.agentId()).param(run.id()).param(x.reason().startsWith("event_after") ? (short) 2 : (short) 1)
                    .param(x.sequence()).param(x.reason()).update();
            recorded++;
        }
        for (ItemResult x : results) {
            if (x.status() == ItemStatus.INVALID && recorded < MAX_REFUSALS_RECORDED) {
                j.sql("INSERT INTO public.agent_ingestion_refusals (organization_id, agent_id, agent_run_id, kind, sequence, reason) VALUES (?, ?, ?, 3, ?, ?)")
                        .param(m.organization()).param(m.agentId()).param(run.id()).param(x.sequence()).param(x.reason()).update();
                recorded++;
            }
        }

        // correlacao com o Framework: a primeira vista fica; divergencia e registrada, nunca sobrescreve
        String fw = run.frameworkId();
        boolean fwConflict = false;
        for (ObsEnvelope.Valid v : stored.stream().sorted(java.util.Comparator.comparingLong(ObsEnvelope.Valid::sequence)).toList()) {
            if (v.frameworkExecutionId() == null) {
                continue;
            }
            if (fw == null) {
                fw = v.frameworkExecutionId();
            } else if (!fw.equals(v.frameworkExecutionId())) {
                fwConflict = true;
            }
        }
        if (fwConflict) {
            j.sql("INSERT INTO public.agent_ingestion_refusals (organization_id, agent_id, agent_run_id, kind, reason) VALUES (?, ?, ?, 5, 'framework_execution_conflict')")
                    .param(m.organization()).param(m.agentId()).param(run.id()).update();
        }

        // projecao da entrega
        long maxSeq = Math.max(run.maxSequence(), Math.max(maxDeclared, stored.stream().mapToLong(ObsEnvelope.Valid::sequence).max().orElse(0)));
        long storedThrough = advanceStored(j, run.id(), run.storedThrough());
        long settledThrough = advanceSettled(j, run.id(), Math.max(run.settledThrough(), storedThrough), declared);
        boolean lostAtSource = stored.stream().anyMatch(v -> "EVENT_GAP".equals(v.eventCode()) && ("AGT-OBS-005".equals(v.errorCode()) || "AGT-OBS-006".equals(v.errorCode()))
                || "SOURCE_REPLACED".equals(v.eventCode()) || "SOURCE_UNSAFE".equals(v.eventCode()));
        boolean lostLocal = declaredCount > 0 || stored.stream().anyMatch(v -> "EVENT_GAP".equals(v.eventCode()) && "AGT-OBS-009".equals(v.errorCode()));
        boolean purgedLocal = stored.stream().anyMatch(v -> "HISTORY_PURGED".equals(v.eventCode()));
        long agentInvalid = stored.stream().filter(v -> "EVENT_INVALID".equals(v.eventCode())).count();
        Long completed = stored.stream().filter(v -> "COLLECTION_COMPLETED".equals(v.eventCode())).map(ObsEnvelope.Valid::sequence).min(Long::compare).orElse(null);
        long frameworkEvents = stored.stream().filter(v -> v.source() == 2).count();
        j.sql("""
                UPDATE public.agent_runs SET events_stored = events_stored + ?, stored_through = ?, settled_through = ?, max_sequence = ?,
                       declared_missing = declared_missing + ?, lost_at_source = lost_at_source OR ?, lost_local = lost_local OR ?,
                       purged_local = purged_local OR ?, invalid_refused = invalid_refused + ?, framework_events = framework_events + ?,
                       collection_completed_sequence = coalesce(collection_completed_sequence, ?), framework_execution_id = coalesce(framework_execution_id, ?),
                       last_event_at = clock_timestamp()
                 WHERE organization_id = ? AND agent_run_id = ?
                """).param((long) stored.size()).param(storedThrough).param(settledThrough).param(maxSeq).param(declaredCount).param(lostAtSource)
                .param(lostLocal).param(purgedLocal).param(invalid + agentInvalid).param(frameworkEvents).param(completed).param(fw)
                .param(m.organization()).param(run.id()).update();
        if (results.stream().anyMatch(x -> x.status() == ItemStatus.CONFLICT)) {
            LOG.info("evento=agente.eventos_conflitantes execucao={} correlacao={}", run.id(), RequestContext.correlation());
        }
        return new Ack(run.id(), storedThrough, settledThrough, results, missingResults);
    }

    private static boolean covered(List<Range> declared, long seq) {
        return declared.stream().anyMatch(d -> d.from() <= seq && seq <= d.to());
    }

    /** Maior k tal que 1..k estao gravados, a partir do valor atual (as linhas depois dele, em ordem, sem buraco). */
    static long advanceStored(JdbcClient j, UUID run, long from) {
        // coalesce: max() sem linhas e nulo, e o single() do Spring recusa valor nulo
        Long v = j.sql("""
                SELECT coalesce(max(sequence), ?) FROM (
                    SELECT sequence, sequence - row_number() OVER (ORDER BY sequence) AS grp
                      FROM public.agent_run_events WHERE agent_run_id = ? AND sequence > ?) s
                 WHERE grp = ?
                """).param(from).param(run).param(from).param(from).query(Long.class).single();
        return v;
    }

    /** Maior k tal que cada numero 1..k esta gravado OU declarado ausente. */
    static long advanceSettled(JdbcClient j, UUID run, long from, List<Range> declared) {
        long settled = from;
        while (true) {
            long before = settled;
            settled = advanceStored(j, run, settled);
            for (Range d : declared) {
                if (d.from() <= settled + 1 && d.to() > settled) {
                    settled = d.to();
                }
            }
            if (settled == before) {
                return settled;
            }
        }
    }
}
