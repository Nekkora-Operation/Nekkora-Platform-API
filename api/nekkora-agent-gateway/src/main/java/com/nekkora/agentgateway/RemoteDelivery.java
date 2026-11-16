package com.nekkora.agentgateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nekkora.agentgateway.MachineAuth.Machine;
import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;
import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContext;

import tools.jackson.databind.json.JsonMapper;

/**
 * Entrega de uma tentativa de execucao remota a maquina (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T02).
 *
 * <p>A maquina PUXA: {@link #next} entrega no maximo uma coisa (a tentativa que e dela, ou o aviso de que o cancelamento foi
 * pedido). A entrega da um TOKEN e uma EPOCA; toda escrita seguinte ({@link #ack}, {@link #state}) leva os dois. Uma nova
 * entrega troca o token e soma a epoca: quem ficou com os anteriores recebe 409 e nao altera nada - um consumidor obsoleto
 * nao aceita trabalho nem sobrescreve o estado.
 *
 * <p>As condicoes da solicitacao (decisao da titular, 15/11) sao REVALIDADAS aqui, pelo banco
 * ({@code remote_attempt_blocker}, 046), antes de ENTREGAR e antes de INICIAR: Worker ainda autorizado no ambiente, maquina
 * ainda habilitada e compativel, autorizacao da versao ainda vigente para este Worker. Se algo deixou de valer durante a
 * espera ou a preparacao, a tentativa NAO inicia: fica REFUSED com o motivo, e a resposta diz a maquina para nao prosseguir.
 *
 * <p>Entregar nao prova recebimento; receber nao prova inicio. O prazo (lease) vencido so quer dizer comunicacao perdida:
 * nao muda o estado nem encerra a tentativa. Nao ha troca automatica de maquina.
 */
public final class RemoteDelivery {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteDelivery.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<String> STATES = List.of("QUEUED", "OFFERED", "RECEIVED", "PREPARING", "STARTING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED",
            "TIMED_OUT", "REFUSED", "EXPIRED", "ENDED_UNKNOWN");
    /** Os estados que a MAQUINA pode informar. */
    static final Set<String> REPORTABLE = Set.of("PREPARING", "STARTING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "REFUSED", "ENDED_UNKNOWN");
    /** Os motivos de recusa que a MAQUINA pode dar (os de autorizacao e de vinculo sao do servidor). */
    static final Set<String> MACHINE_REASONS = Set.of("BUSY", "PAUSED", "PACKAGE_INVALID", "ENVIRONMENT_GONE", "ENVIRONMENT_INCOMPATIBLE", "DEPENDENCIES_MISSING",
            "DISK_PRESSURE", "CLIENT_INCOMPATIBLE", "SESSION_UNAVAILABLE");

    /** A tentativa entregue. {@code token} aparece SO aqui; o servidor guarda o hash. */
    public record Offer(UUID attemptId, UUID remoteExecutionId, int attemptNumber, UUID workspaceId, UUID automationId, UUID automationVersionId,
                        String packageFingerprint,
                        String packageFingerprintRule, List<String> dependencies, String pythonEnvironmentId, String token, int epoch, int leaseSeconds) {
    }

    /** A tentativa cuja solicitacao teve o cancelamento pedido: a maquina para o que estiver fazendo e informa CANCELLED. */
    public record CancelNotice(UUID attemptId) {
    }

    /** Nada a fazer: os dois campos ausentes. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Next(Offer attempt, CancelNotice cancel) {
    }

    /**
     * {@code proceed}: {@code false} quando a maquina NAO deve seguir (o servidor recusou o inicio, ou a tentativa ja terminou).
     * {@code refusalReason}: so em REFUSED. {@code cancelRequested}: a pessoa pediu o cancelamento.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Status(UUID attemptId, String state, boolean proceed, String refusalReason, boolean cancelRequested, int leaseSeconds) {
    }

    private record Row(UUID attemptId, UUID execution, short state, String reason, String tokenHash, int epoch, UUID worker, UUID authorization,
                       boolean cancelRequested, boolean offerExpired) {
    }

    private final OrgTransactions tx;
    private final MachineAuth auth;
    private final int leaseSeconds;
    private final com.nekkora.core.artifact.ArtifactStore packages;
    private final com.nekkora.core.artifact.ResultStore results;
    /** Limites dos resultados de uma tentativa (pontos de partida de laboratorio; os mesmos da 050). */
    static final int MAX_RESULTS = 50;
    static final long MAX_RESULT_BYTES = 20L * 1024 * 1024;
    static final long MAX_RESULTS_TOTAL_BYTES = 50L * 1024 * 1024;
    private static final java.util.regex.Pattern RESULT_NAME_BAD = java.util.regex.Pattern.compile(
            "[\\x01-\\x1f\\x7f\\\\]|(^|/)\\.\\.?(/|$)|^/|/$|//|^[A-Za-z]:");
    private static final java.util.regex.Pattern SHA256 = java.util.regex.Pattern.compile("^[0-9a-f]{64}$");

    /** {@code leaseSeconds}: prazo da comunicacao, renovado a cada escrita aceita (ponto de partida de laboratorio: 90 s). */
    public RemoteDelivery(OrgTransactions tx, MachineAuth auth, int leaseSeconds, com.nekkora.core.artifact.ArtifactStore packages,
                          com.nekkora.core.artifact.ResultStore results) {
        this.packages = packages;
        this.results = results;
        this.tx = tx;
        this.auth = auth;
        this.leaseSeconds = Math.max(30, Math.min(leaseSeconds, 3600));
    }

    /**
     * O que ha para esta maquina. Maquina nao habilitada: 409. Pausada: nada e entregue (a tentativa espera ate o prazo da
     * oferta). Entregar de novo a mesma tentativa ainda nao recebida (resposta perdida) troca o token e soma a epoca.
     */
    public Next next(MachineToken t) {
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = auth.authenticate(j, t);
            Short receiving = j.sql("SELECT receiving FROM public.agent_remote_enablements WHERE organization_id = ? AND agent_id = ?")
                    .param(m.organization()).param(m.agentId()).query(Short.class).optional().orElse(null);
            if (receiving == null) {
                throw new ApiException(ApiError.REMOTE_EXECUTION_NOT_ENABLED, "maquina_nao_habilitada");
            }
            Row a = active(j, m).orElse(null);
            if (a == null) {
                return new Next(null, null);
            }
            if (a.state() <= 2) {
                if (a.offerExpired()) {
                    end(j, m, a, 12, null);
                    return new Next(null, null);
                }
                if (a.cancelRequested()) {
                    end(j, m, a, 9, null);
                    return new Next(null, null);
                }
                String why = blocker(j, m, a);
                if (why != null) {
                    end(j, m, a, 11, why);
                    LOG.info("evento=execucao_remota.nao_entregue tentativa={} motivo={} correlacao={}", a.attemptId(), why, RequestContext.correlation());
                    return new Next(null, null);
                }
                if (receiving != 1) {
                    return new Next(null, null);
                }
                String token = Tokens.newToken();
                int epoch = a.epoch() + 1;
                j.sql("UPDATE public.remote_attempts SET state = 2, token_hash = ?, epoch = ?, lease_expires_at = clock_timestamp() + make_interval(secs => ?) "
                        + "WHERE organization_id = ? AND attempt_id = ?").param(Tokens.sha256Hex(token)).param(epoch).param(leaseSeconds)
                        .param(m.organization()).param(a.attemptId()).update();
                LOG.info("evento=execucao_remota.entregue tentativa={} epoca={} correlacao={}", a.attemptId(), epoch, RequestContext.correlation());
                return new Next(offer(j, m, a.attemptId(), token, epoch), null);
            }
            // ja recebida por esta maquina: nada novo a entregar; so o aviso de cancelamento, se houver
            return new Next(null, a.cancelRequested() ? new CancelNotice(a.attemptId()) : null);
        });
    }

    /** A maquina confirma que RECEBEU a tentativa (depois de grava-la no proprio diario). Idempotente com o mesmo token e epoca. */
    public Status ack(MachineToken t, UUID attemptId, String token, Integer epoch) {
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = auth.authenticate(j, t);
            Row a = owned(j, m, attemptId, token, epoch);
            if (a.state() == 2) {
                move(j, m, a, 3, null, null);
            } else if (a.state() != 3) {
                return status(j, m, attemptId, false);
            }
            return status(j, m, attemptId, true);
        });
    }

    /**
     * A maquina informa o estado e renova o prazo. Antes de STARTING, o servidor REVALIDA: com o cancelamento pedido, a
     * tentativa fica CANCELLED; com alguma condicao da solicitacao perdida (autorizacao revogada ou vencida, Worker fora do
     * ambiente, maquina desabilitada), fica REFUSED com o motivo. Nos dois casos a resposta traz {@code proceed: false} e a
     * maquina NAO inicia. Repetir o estado atual so renova o prazo.
     */
    public Status state(MachineToken t, UUID attemptId, String token, Integer epoch, String state, String refusalReason, Integer exitCode) {
        if (state == null || !REPORTABLE.contains(state) || ("REFUSED".equals(state)) != (refusalReason != null)
                || (refusalReason != null && !MACHINE_REASONS.contains(refusalReason))
                || (exitCode != null && !Set.of("SUCCEEDED", "FAILED", "TIMED_OUT").contains(state))) {
            throw ApiException.invalid("estado");
        }
        short wanted = (short) (STATES.indexOf(state) + 1);
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = auth.authenticate(j, t);
            Row a = owned(j, m, attemptId, token, epoch);
            if (a.state() >= 7) {
                // ja terminada (pelo servidor ou por esta maquina): nada muda; a maquina nao segue
                return status(j, m, attemptId, false);
            }
            if (a.state() == wanted) {
                renew(j, m, attemptId);
                return status(j, m, attemptId, true);
            }
            if (!allowed(a.state(), wanted)) {
                throw new ApiException(ApiError.REMOTE_ATTEMPT_TRANSITION, "transicao");
            }
            if (wanted == 5) {
                // imediatamente antes de iniciar: cancelamento pedido ou condicao perdida => nao inicia
                if (a.cancelRequested()) {
                    end(j, m, a, 9, null);
                    return status(j, m, attemptId, false);
                }
                String why = blocker(j, m, a);
                if (why != null) {
                    end(j, m, a, 11, why);
                    LOG.info("evento=execucao_remota.inicio_recusado tentativa={} motivo={} correlacao={}", attemptId, why, RequestContext.correlation());
                    return status(j, m, attemptId, false);
                }
            }
            move(j, m, a, wanted, refusalReason, exitCode);
            return status(j, m, attemptId, wanted < 7);
        });
    }

    /** Os bytes do pacote da tentativa, ja conferidos pelo SHA-256 com que foram guardados. */
    public record Pack(String sha256, long sizeBytes, byte[] bytes) {
    }

    /**
     * O pacote da versao FIXADA na solicitacao, so para a maquina da tentativa, com o token e a epoca vigentes, e so entre o
     * recebimento e o inicio (RECEIVED ou PREPARING). O pacote e o da impressao congelada na solicitacao: outro pacote da mesma
     * versao nao serve. Os bytes sao relidos e conferidos pelo hash a cada download; ausentes ou corrompidos: 409
     * remote_package_unavailable (a maquina recusa a tentativa com PACKAGE_INVALID). Baixar renova o prazo da comunicacao.
     */
    public Pack pack(MachineToken t, UUID attemptId, String token, Integer epoch) {
        record Key(String sha256, long sizeBytes) {
        }
        Key k = tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = auth.authenticate(j, t);
            Row a = owned(j, m, attemptId, token, epoch);
            if (a.state() != 3 && a.state() != 4) {
                throw new ApiException(ApiError.REMOTE_ATTEMPT_TRANSITION, "pacote_fora_da_preparacao");
            }
            renew(j, m, attemptId);
            // 048: o banco so mostra esta linha a maquina autenticada nesta transacao, e so com a tentativa em preparacao
            return j.sql("SELECT p.sha256, p.size_bytes FROM public.remote_attempt_packages p WHERE p.organization_id = ? AND p.attempt_id = ?")
                    .param(m.organization()).param(attemptId).query((rs, n) -> new Key(rs.getString(1), rs.getLong(2))).optional()
                    .orElseThrow(() -> new ApiException(ApiError.REMOTE_PACKAGE_UNAVAILABLE, "pacote_sem_registro"));
        });
        if (!packages.available()) {
            throw new ApiException(ApiError.SERVICE_UNAVAILABLE, "armazenamento_de_pacotes");
        }
        try {
            byte[] bytes = packages.get(t.organization(), k.sha256())
                    .orElseThrow(() -> new ApiException(ApiError.REMOTE_PACKAGE_UNAVAILABLE, "pacote_ausente_no_armazenamento"));
            if (bytes.length != k.sizeBytes()) {
                throw new ApiException(ApiError.REMOTE_PACKAGE_UNAVAILABLE, "pacote_com_outro_tamanho");
            }
            LOG.info("evento=execucao_remota.pacote_entregue tentativa={} bytes={} correlacao={}", attemptId, bytes.length, RequestContext.correlation());
            return new Pack(k.sha256(), k.sizeBytes(), bytes);
        } catch (com.nekkora.core.artifact.ArtifactStore.Corrupted e) {
            throw new ApiException(ApiError.REMOTE_PACKAGE_UNAVAILABLE, "pacote_corrompido");
        } catch (java.io.IOException e) {
            throw new ApiException(ApiError.SERVICE_UNAVAILABLE, "armazenamento_de_pacotes");
        }
    }

    // ------------------------------------------------------------------ resultados (T03b)

    /** {@code created}: {@code false} quando o mesmo arquivo ja tinha chegado com este numero (repeticao de um envio). */
    public record StoredResult(int number, String fileName, long sizeBytes, String sha256, boolean created) {
    }

    public record ResultsClosure(int sent, int skippedByLimit) {
    }

    /**
     * A maquina envia UM arquivo de resultado da tentativa que ela executou. So de tentativa que INICIOU; com o token e a epoca
     * vigentes (continuam valendo depois do termino); numero de 1 a 50; nome relativo a pasta de saida; ate 20 MiB por arquivo
     * e 50 MiB no total. O servidor confere o SHA-256 declarado contra os bytes que chegaram e so entao grava: primeiro os
     * bytes (duraveis, enderecados pelo hash), depois o metadado. Repetir o MESMO arquivo no mesmo numero nao muda nada
     * (resposta perdida); outro arquivo no mesmo numero, ou qualquer envio depois do fechamento: 409 remote_result_conflict.
     * Passar do total: 409 remote_result_limit, e nada e gravado. Enviar resultado nao muda o estado da tentativa.
     */
    public StoredResult storeResult(MachineToken t, UUID attemptId, String token, Integer epoch, int number, String fileName, String sha256, byte[] data) {
        if (number < 1 || number > MAX_RESULTS || fileName == null || fileName.isEmpty() || fileName.length() > 200
                || RESULT_NAME_BAD.matcher(fileName).find() || sha256 == null || !SHA256.matcher(sha256).matches() || data == null
                || data.length > MAX_RESULT_BYTES || !sha256.equals(sha256Hex(data))) {
            throw ApiException.invalid("resultado");
        }
        if (!results.available()) {
            throw new ApiException(ApiError.SERVICE_UNAVAILABLE, "armazenamento_de_resultados");
        }
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = auth.authenticate(j, t);
            Row a = owned(j, m, attemptId, token, epoch);
            requireStarted(j, m, a);
            record Existing(String name, String sha) {
            }
            Existing e = j.sql("SELECT file_name, sha256 FROM public.remote_attempt_results WHERE organization_id = ? AND attempt_id = ? AND result_number = ?")
                    .param(m.organization()).param(attemptId).param((short) number).query((rs, n) -> new Existing(rs.getString(1), rs.getString(2)))
                    .optional().orElse(null);
            if (e != null) {
                if (e.name().equals(fileName) && e.sha().equals(sha256)) {
                    return new StoredResult(number, fileName, data.length, sha256, false);
                }
                throw new ApiException(ApiError.REMOTE_RESULT_CONFLICT, "numero_com_outro_arquivo");
            }
            if (closure(j, m, attemptId) != null
                    || j.sql("SELECT count(*) FROM public.remote_attempt_results WHERE organization_id = ? AND attempt_id = ? AND file_name = ?")
                            .param(m.organization()).param(attemptId).param(fileName).query(Long.class).single() > 0) {
                throw new ApiException(ApiError.REMOTE_RESULT_CONFLICT, "envio_fechado_ou_nome_repetido");
            }
            long total = j.sql("SELECT coalesce(sum(size_bytes), 0) FROM public.remote_attempt_results WHERE organization_id = ? AND attempt_id = ?")
                    .param(m.organization()).param(attemptId).query(Long.class).single();
            if (total + data.length > MAX_RESULTS_TOTAL_BYTES) {
                throw new ApiException(ApiError.REMOTE_RESULT_LIMIT, "total_de_resultados");
            }
            try {
                results.put(m.organization(), sha256, data);
            } catch (java.io.IOException x) {
                throw new ApiException(ApiError.SERVICE_UNAVAILABLE, "armazenamento_de_resultados");
            }
            j.sql("INSERT INTO public.remote_attempt_results (attempt_id, result_number, organization_id, file_name, size_bytes, sha256) VALUES (?, ?, ?, ?, ?, ?)")
                    .param(attemptId).param((short) number).param(m.organization()).param(fileName).param((long) data.length).param(sha256).update();
            LOG.info("evento=execucao_remota.resultado_recebido tentativa={} numero={} bytes={} correlacao={}", attemptId, number, data.length,
                    RequestContext.correlation());
            return new StoredResult(number, fileName, data.length, sha256, true);
        });
    }

    /**
     * A maquina fecha o envio: {@code sent} arquivos enviados (tem de ser o que o servidor recebeu - um envio perdido aparece
     * aqui) e {@code skippedByLimit} que ela deixou de enviar por limite. So depois que a tentativa que iniciou TERMINOU. Repetir
     * o mesmo fechamento nao muda nada; outro, 409. Fechar nao muda o estado da tentativa.
     */
    public ResultsClosure closeResults(MachineToken t, UUID attemptId, String token, Integer epoch, Integer sent, Integer skippedByLimit) {
        if (sent == null || skippedByLimit == null || sent < 0 || sent > MAX_RESULTS || skippedByLimit < 0 || skippedByLimit > 1_000_000) {
            throw ApiException.invalid("fechamento");
        }
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = auth.authenticate(j, t);
            Row a = owned(j, m, attemptId, token, epoch);
            requireStarted(j, m, a);
            ResultsClosure existing = closure(j, m, attemptId);
            if (existing != null) {
                if (existing.sent() == sent && existing.skippedByLimit() == skippedByLimit) {
                    return existing;
                }
                throw new ApiException(ApiError.REMOTE_RESULT_CONFLICT, "fechamento_diferente");
            }
            long received = j.sql("SELECT count(*) FROM public.remote_attempt_results WHERE organization_id = ? AND attempt_id = ?")
                    .param(m.organization()).param(attemptId).query(Long.class).single();
            if (a.state() < 7 || received != sent) {
                throw new ApiException(a.state() < 7 ? ApiError.REMOTE_ATTEMPT_TRANSITION : ApiError.REMOTE_RESULT_CONFLICT, "fechamento_fora_de_hora_ou_contagem");
            }
            j.sql("INSERT INTO public.remote_attempt_result_closures (attempt_id, organization_id, sent, skipped_by_limit) VALUES (?, ?, ?, ?)")
                    .param(attemptId).param(m.organization()).param(sent.shortValue()).param(skippedByLimit).update();
            LOG.info("evento=execucao_remota.resultados_fechados tentativa={} enviados={} fora_do_limite={} correlacao={}", attemptId, sent, skippedByLimit,
                    RequestContext.correlation());
            return new ResultsClosure(sent, skippedByLimit);
        });
    }

    private static String sha256Hex(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ T05: registros (saida do processo)

    static final int MAX_LOG_LINES = 50_000;
    static final long MAX_LOG_BYTES = 20L * 1024 * 1024;
    static final int MAX_LOG_LINE_BYTES = 8 * 1024;
    static final int MAX_LOG_BATCH_LINES = 200;
    static final long MAX_LOG_BATCH_BYTES = 256L * 1024;
    private static final List<String> LOG_CHANNELS = List.of("STDOUT", "STDERR", "EXECUTOR");
    private static final java.util.regex.Pattern LOG_TEXT_BAD = java.util.regex.Pattern.compile("[\\x00-\\x08\\x0a-\\x1f\\x7f]");

    /** Uma linha capturada pela maquina. {@code capturedAt}: instante ISO-8601 com fuso. */
    public record LogLine(int sequence, String capturedAt, String channel, boolean truncated, String message) {
    }

    /** {@code lastSequence}: a ultima linha que a Platform tem desta tentativa depois deste lote. */
    public record LogsAccepted(int received, int stored, int lastSequence) {
    }

    public record LogsClosure(int lines, long droppedLines) {
    }

    /**
     * Um lote de linhas da saida do processo, de uma tentativa que INICIOU e cujo envio nao foi fechado. A sequencia e continua:
     * o lote comeca, no maximo, na linha seguinte a ultima guardada. Linha ja guardada com o mesmo conteudo e ignorada (lote
     * repetido depois de resposta perdida); com outro conteudo, 409 remote_log_conflict. Buraco na sequencia: 409
     * remote_log_conflict. Passar de 50.000 linhas ou 20 MiB: 409 remote_log_limit (a maquina conta o excedente como descartado).
     * O texto passa pela remocao de segredos antes de ser gravado. Com a tentativa em andamento, o lote renova o prazo da
     * comunicacao. Nao muda o estado da tentativa.
     */
    public LogsAccepted storeLogs(MachineToken t, UUID attemptId, String token, Integer epoch, List<LogLine> lines) {
        if (lines == null || lines.isEmpty() || lines.size() > MAX_LOG_BATCH_LINES) {
            throw ApiException.invalid("lote");
        }
        long batchBytes = 0;
        List<java.time.OffsetDateTime> instants = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            LogLine l = lines.get(i);
            if (l == null || l.sequence() < 1 || (i > 0 && l.sequence() != lines.get(i - 1).sequence() + 1)
                    || l.channel() == null || !LOG_CHANNELS.contains(l.channel()) || l.message() == null || LOG_TEXT_BAD.matcher(l.message()).find()
                    || l.message().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_LOG_LINE_BYTES || l.capturedAt() == null) {
                throw ApiException.invalid("linha");
            }
            try {
                instants.add(java.time.OffsetDateTime.parse(l.capturedAt()));
            } catch (java.time.DateTimeException e) {
                throw ApiException.invalid("instante");
            }
            batchBytes += l.message().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        if (batchBytes > MAX_LOG_BATCH_BYTES) {
            throw ApiException.invalid("lote");
        }
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = auth.authenticate(j, t);
            Row a = owned(j, m, attemptId, token, epoch);
            requireStarted(j, m, a);
            if (logsClosure(j, m, attemptId) != null) {
                throw new ApiException(ApiError.REMOTE_LOG_CONFLICT, "envio_fechado");
            }
            record Have(int last, long bytes) {
            }
            Have have = j.sql("SELECT coalesce(max(sequence), 0), coalesce(sum(octet_length(message)), 0) FROM public.remote_attempt_log_lines "
                            + "WHERE organization_id = ? AND attempt_id = ?").param(m.organization()).param(attemptId)
                    .query((rs, n) -> new Have(rs.getInt(1), rs.getLong(2))).single();
            if (lines.get(0).sequence() > have.last() + 1) {
                throw new ApiException(ApiError.REMOTE_LOG_CONFLICT, "buraco_na_sequencia");
            }
            int stored = 0;
            long total = have.bytes();
            for (int i = 0; i < lines.size(); i++) {
                LogLine l = lines.get(i);
                String text = LogRedaction.redact(l.message(), token);
                short channel = (short) (LOG_CHANNELS.indexOf(l.channel()) + 1);
                if (l.sequence() <= have.last()) {
                    // lote repetido: a linha guardada tem de ser a mesma
                    boolean same = j.sql("SELECT channel = ? AND truncated = ? AND (message = ? OR redacted_at IS NOT NULL) FROM public.remote_attempt_log_lines "
                                    + "WHERE organization_id = ? AND attempt_id = ? AND sequence = ?").param(channel).param(l.truncated()).param(text)
                            .param(m.organization()).param(attemptId).param(l.sequence()).query(Boolean.class).single();
                    if (!same) {
                        throw new ApiException(ApiError.REMOTE_LOG_CONFLICT, "linha_com_outro_conteudo");
                    }
                    continue;
                }
                total += text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (l.sequence() > MAX_LOG_LINES || total > MAX_LOG_BYTES) {
                    throw new ApiException(ApiError.REMOTE_LOG_LIMIT, "total_de_registros");
                }
                j.sql("INSERT INTO public.remote_attempt_log_lines (attempt_id, sequence, organization_id, captured_at, channel, truncated, message) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)").param(attemptId).param(l.sequence()).param(m.organization()).param(instants.get(i)).param(channel)
                        .param(l.truncated()).param(text).update();
                stored++;
            }
            if (a.state() < 7) {
                renew(j, m, attemptId);
            }
            int last = Math.max(have.last(), lines.get(lines.size() - 1).sequence());
            LOG.info("evento=execucao_remota.registros_recebidos tentativa={} linhas={} ultima={} correlacao={}", attemptId, stored, last,
                    RequestContext.correlation());
            return new LogsAccepted(lines.size(), stored, last);
        });
    }

    /**
     * Fecha o envio dos registros de uma tentativa que iniciou e TERMINOU: quantas linhas a maquina enviou e quantas descartou
     * por limite. Repetir o mesmo fechamento devolve o mesmo; outro fechamento, ou contagem que nao bate com o que chegou: 409
     * remote_log_conflict. Nao muda o estado da tentativa.
     */
    public LogsClosure closeLogs(MachineToken t, UUID attemptId, String token, Integer epoch, Integer sent, Long dropped) {
        if (sent == null || dropped == null || sent < 0 || sent > MAX_LOG_LINES || dropped < 0 || dropped > 1_000_000_000_000L) {
            throw ApiException.invalid("fechamento");
        }
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = auth.authenticate(j, t);
            Row a = owned(j, m, attemptId, token, epoch);
            requireStarted(j, m, a);
            LogsClosure existing = logsClosure(j, m, attemptId);
            if (existing != null) {
                if (existing.lines() == sent && existing.droppedLines() == dropped) {
                    return existing;
                }
                throw new ApiException(ApiError.REMOTE_LOG_CONFLICT, "fechamento_diferente");
            }
            long received = j.sql("SELECT count(*) FROM public.remote_attempt_log_lines WHERE organization_id = ? AND attempt_id = ?")
                    .param(m.organization()).param(attemptId).query(Long.class).single();
            if (a.state() < 7 || received != sent) {
                throw new ApiException(a.state() < 7 ? ApiError.REMOTE_ATTEMPT_TRANSITION : ApiError.REMOTE_LOG_CONFLICT, "fechamento_fora_de_hora_ou_contagem");
            }
            j.sql("INSERT INTO public.remote_attempt_log_closures (attempt_id, organization_id, lines, dropped_lines) VALUES (?, ?, ?, ?)")
                    .param(attemptId).param(m.organization()).param(sent).param(dropped).update();
            LOG.info("evento=execucao_remota.registros_fechados tentativa={} linhas={} descartadas={} correlacao={}", attemptId, sent, dropped,
                    RequestContext.correlation());
            return new LogsClosure(sent, dropped);
        });
    }

    /** O aviso gravado: o tipo, quanto havia na maquina e quando o aviso chegou. */
    public record RetentionLoss(String kind, int localCount, java.time.OffsetDateTime reportedAt) {
    }

    static final List<String> LOSS_KINDS = List.of("LOGS", "RESULTS");

    /**
     * A maquina avisa que REMOVEU, pelo prazo de guarda local, o que guardava de uma tentativa sem a confirmacao de recebimento:
     * a fila de registros (LOGS) ou a pasta de saida (RESULTS). {@code localCount}: quanto havia nela - linhas capturadas, ou
     * arquivos na pasta. So de tentativa que iniciou e terminou, com o token e a epoca da atribuicao. Um aviso por tentativa e
     * por tipo: repetir devolve o que ja estava gravado (resposta perdida), mesmo com outro numero. Nao muda a tentativa, o
     * resultado, as linhas, os arquivos nem o fechamento.
     */
    public RetentionLoss reportRetentionLoss(MachineToken t, UUID attemptId, String token, Integer epoch, String kind, Integer localCount) {
        int code = kind == null ? -1 : LOSS_KINDS.indexOf(kind);
        if (code < 0 || localCount == null || localCount < 0 || localCount > 1_000_000) {
            throw ApiException.invalid("perda");
        }
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = auth.authenticate(j, t);
            Row a = owned(j, m, attemptId, token, epoch);
            requireStarted(j, m, a);
            if (a.state() < 7) {
                throw new ApiException(ApiError.REMOTE_ATTEMPT_TRANSITION, "perda_antes_do_termino");
            }
            int created = j.sql("INSERT INTO public.remote_attempt_retention_losses (attempt_id, kind, organization_id, local_count) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT (attempt_id, kind) DO NOTHING").param(attemptId).param((short) (code + 1)).param(m.organization()).param(localCount).update();
            if (created == 1) {
                LOG.info("evento=execucao_remota.perda_por_prazo_de_guarda tentativa={} tipo={} havia={} correlacao={}", attemptId, kind, localCount,
                        RequestContext.correlation());
            }
            return j.sql("SELECT local_count, reported_at FROM public.remote_attempt_retention_losses WHERE organization_id = ? AND attempt_id = ? AND kind = ?")
                    .param(m.organization()).param(attemptId).param((short) (code + 1))
                    .query((rs, n) -> new RetentionLoss(kind, rs.getInt(1), rs.getObject(2, java.time.OffsetDateTime.class))).single();
        });
    }

    private static LogsClosure logsClosure(JdbcClient j, Machine m, UUID attemptId) {
        return j.sql("SELECT lines, dropped_lines FROM public.remote_attempt_log_closures WHERE organization_id = ? AND attempt_id = ?")
                .param(m.organization()).param(attemptId).query((rs, n) -> new LogsClosure(rs.getInt(1), rs.getLong(2))).optional().orElse(null);
    }

    private static void requireStarted(JdbcClient j, Machine m, Row a) {
        boolean started = j.sql("SELECT started_at IS NOT NULL FROM public.remote_attempts WHERE organization_id = ? AND attempt_id = ?")
                .param(m.organization()).param(a.attemptId()).query(Boolean.class).single();
        if (!started) {
            throw new ApiException(ApiError.REMOTE_ATTEMPT_TRANSITION, "tentativa_que_nao_iniciou");
        }
    }

    private static ResultsClosure closure(JdbcClient j, Machine m, UUID attemptId) {
        return j.sql("SELECT sent, skipped_by_limit FROM public.remote_attempt_result_closures WHERE organization_id = ? AND attempt_id = ?")
                .param(m.organization()).param(attemptId).query((rs, n) -> new ResultsClosure(rs.getInt(1), rs.getInt(2))).optional().orElse(null);
    }

    // ------------------------------------------------------------------ apoio

    private static boolean allowed(short from, short to) {
        return switch (from) {
            case 3 -> to == 4 || to == 9 || to == 11 || to == 13;
            case 4 -> to == 5 || to == 9 || to == 11 || to == 13;
            case 5 -> to == 6 || to == 8 || to == 9 || to == 11 || to == 13;
            case 6 -> to == 7 || to == 8 || to == 9 || to == 10 || to == 13;
            default -> false;
        };
    }

    private static final String SELECT = """
            SELECT t.attempt_id, t.remote_execution_id, t.state, t.refusal_reason, t.token_hash, t.epoch, t.worker_id, t.authorization_id,
                   x.cancel_requested_at IS NOT NULL, t.offer_valid_until <= clock_timestamp()
              FROM public.remote_attempts t
              JOIN public.remote_executions x ON x.organization_id = t.organization_id AND x.remote_execution_id = t.remote_execution_id
            """;

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getShort(3), rs.getString(4), rs.getString(5), rs.getInt(6),
                rs.getObject(7, UUID.class), rs.getObject(8, UUID.class), rs.getBoolean(9), rs.getBoolean(10));
    }

    /** A tentativa nao terminada desta maquina (no maximo uma: indice da 046), travada para esta transacao. */
    private static java.util.Optional<Row> active(JdbcClient j, Machine m) {
        return j.sql(SELECT + " WHERE t.organization_id = ? AND t.agent_id = ? AND t.state BETWEEN 1 AND 6 FOR UPDATE OF t")
                .param(m.organization()).param(m.agentId()).query((rs, n) -> row(rs)).optional();
    }

    /**
     * A tentativa DESTA maquina, com o token e a epoca vigentes. De outra maquina, ou inexistente: 404. Token ou epoca que nao
     * sao os vigentes: 409 remote_attempt_stale, e nada e alterado.
     */
    private static Row owned(JdbcClient j, Machine m, UUID attemptId, String token, Integer epoch) {
        if (attemptId == null || token == null || epoch == null || !Tokens.isValidFormat(token)) {
            throw ApiException.invalid("atribuicao");
        }
        Row a = j.sql(SELECT + " WHERE t.organization_id = ? AND t.attempt_id = ? AND t.agent_id = ? FOR UPDATE OF t")
                .param(m.organization()).param(attemptId).param(m.agentId()).query((rs, n) -> row(rs)).optional()
                .orElseThrow(() -> new ApiException(ApiError.NOT_FOUND, "tentativa"));
        if (a.tokenHash() == null || a.epoch() != epoch || !java.security.MessageDigest.isEqual(
                a.tokenHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII), Tokens.sha256Hex(token).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new ApiException(ApiError.REMOTE_ATTEMPT_STALE, "token_ou_epoca");
        }
        return a;
    }

    private static String blocker(JdbcClient j, Machine m, Row a) {
        return j.sql("SELECT public.remote_attempt_blocker(?, ?, ?, ?, ?)").param(m.organization()).param(a.execution()).param(m.agentId()).param(a.worker())
                .param(a.authorization()).query(String.class).optional().orElse(null);
    }

    private void move(JdbcClient j, Machine m, Row a, int to, String reason, Integer exitCode) {
        j.sql("UPDATE public.remote_attempts SET state = ?, refusal_reason = ?, exit_code = ?, lease_expires_at = clock_timestamp() + make_interval(secs => ?) "
                + "WHERE organization_id = ? AND attempt_id = ?").param((short) to).param(reason).param(exitCode).param(leaseSeconds).param(m.organization())
                .param(a.attemptId()).update();
        LOG.info("evento=execucao_remota.estado tentativa={} estado={} correlacao={}", a.attemptId(), STATES.get(to - 1), RequestContext.correlation());
    }

    /** Encerramento decidido pelo SERVIDOR (cancelada antes de receber, recusada na revalidacao, oferta vencida). */
    private static void end(JdbcClient j, Machine m, Row a, int to, String reason) {
        j.sql("UPDATE public.remote_attempts SET state = ?, refusal_reason = ? WHERE organization_id = ? AND attempt_id = ?").param((short) to).param(reason)
                .param(m.organization()).param(a.attemptId()).update();
    }

    private void renew(JdbcClient j, Machine m, UUID attemptId) {
        j.sql("UPDATE public.remote_attempts SET lease_expires_at = clock_timestamp() + make_interval(secs => ?) WHERE organization_id = ? AND attempt_id = ?")
                .param(leaseSeconds).param(m.organization()).param(attemptId).update();
    }

    private Status status(JdbcClient j, Machine m, UUID attemptId, boolean proceed) {
        return j.sql(SELECT + " WHERE t.organization_id = ? AND t.attempt_id = ?").param(m.organization()).param(attemptId).query((rs, n) -> row(rs)).optional()
                .map(a -> new Status(attemptId, STATES.get(a.state() - 1), proceed && a.state() < 7, a.reason(), a.cancelRequested(), leaseSeconds)).orElseThrow();
    }

    private Offer offer(JdbcClient j, Machine m, UUID attemptId, String token, int epoch) {
        return j.sql("""
                SELECT t.remote_execution_id, t.attempt_number, x.automation_id, x.automation_version_id, x.package_fingerprint, x.package_fingerprint_rule,
                       x.effective_dependencies::text, t.python_environment_id, x.workspace_id
                  FROM public.remote_attempts t
                  JOIN public.remote_executions x ON x.organization_id = t.organization_id AND x.remote_execution_id = t.remote_execution_id
                 WHERE t.organization_id = ? AND t.attempt_id = ?
                """).param(m.organization()).param(attemptId).query((rs, n) -> {
            List<String> deps = new ArrayList<>();
            JSON.readTree(rs.getString(7)).forEach(d -> deps.add(d.asString()));
            return new Offer(attemptId, rs.getObject(1, UUID.class), rs.getInt(2), rs.getObject(9, UUID.class), rs.getObject(3, UUID.class),
                    rs.getObject(4, UUID.class), rs.getString(5),
                    rs.getString(6), List.copyOf(deps), rs.getString(8), token, epoch, leaseSeconds);
        }).single();
    }
}
