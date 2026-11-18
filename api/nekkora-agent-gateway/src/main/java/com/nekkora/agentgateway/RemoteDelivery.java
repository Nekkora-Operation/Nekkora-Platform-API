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
            "DISK_PRESSURE", "CLIENT_INCOMPATIBLE");

    /** A tentativa entregue. {@code token} aparece SO aqui; o servidor guarda o hash. */
    public record Offer(UUID attemptId, UUID remoteExecutionId, int attemptNumber, UUID automationId, UUID automationVersionId, String packageFingerprint,
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

    /** {@code leaseSeconds}: prazo da comunicacao, renovado a cada escrita aceita (ponto de partida de laboratorio: 90 s). */
    public RemoteDelivery(OrgTransactions tx, MachineAuth auth, int leaseSeconds, com.nekkora.core.artifact.ArtifactStore packages) {
        this.packages = packages;
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
            return j.sql("""
                    SELECT f.sha256, f.size_bytes
                      FROM public.remote_executions x
                      JOIN public.automation_artifacts f ON f.organization_id = x.organization_id AND f.automation_version_id = x.automation_version_id
                                                        AND f.fingerprint = x.package_fingerprint AND f.fingerprint_rule = x.package_fingerprint_rule
                     WHERE x.organization_id = ? AND x.remote_execution_id = ?
                     ORDER BY f.received_at DESC LIMIT 1
                    """).param(m.organization()).param(a.execution()).query((rs, n) -> new Key(rs.getString(1), rs.getLong(2))).optional()
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
                       x.effective_dependencies::text, t.python_environment_id
                  FROM public.remote_attempts t
                  JOIN public.remote_executions x ON x.organization_id = t.organization_id AND x.remote_execution_id = t.remote_execution_id
                 WHERE t.organization_id = ? AND t.attempt_id = ?
                """).param(m.organization()).param(attemptId).query((rs, n) -> {
            List<String> deps = new ArrayList<>();
            JSON.readTree(rs.getString(7)).forEach(d -> deps.add(d.asString()));
            return new Offer(attemptId, rs.getObject(1, UUID.class), rs.getInt(2), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5),
                    rs.getString(6), List.copyOf(deps), rs.getString(8), token, epoch, leaseSeconds);
        }).single();
    }
}
