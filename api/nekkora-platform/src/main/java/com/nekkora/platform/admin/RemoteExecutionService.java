package com.nekkora.platform.admin;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.platform.access.WorkspaceAccess;
import com.nekkora.platform.admin.AccessActions.Failure;
import com.nekkora.platform.session.ValidSession;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Solicitacao de execucao remota (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T02), lado das pessoas.
 *
 * <p>Para ACEITAR uma solicitacao (decisao da titular, 15/11), exige-se, e o banco reconfere (046):
 * <ol>
 * <li>pessoa com automation.execute no ambiente (e confirmacao reforcada);</li>
 * <li>Worker autorizado no ambiente;</li>
 * <li>maquina habilitada, nao pausada e compativel;</li>
 * <li>autorizacao VIGENTE da versao publicada para AQUELE Worker.</li>
 * </ol>
 * Alem disso, as dependencias da automacao precisam estar declaradas e sem conflito com o pacote. Cada exigencia que falta e
 * dita por um motivo proprio, para a tela mostrar o que falta: nada e contornado e nenhuma autorizacao e criada ou transferida
 * por aqui. Solicitar nao prova inicio: o que acontece na maquina aparece no estado de cada tentativa.
 */
public class RemoteExecutionService {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteExecutionService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Janela de comunicacao: lease vencido ha mais que isto e a comunicacao esta perdida (nao muda o estado). */
    static final List<String> STATES = List.of("QUEUED", "OFFERED", "RECEIVED", "PREPARING", "STARTING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED",
            "TIMED_OUT", "REFUSED", "EXPIRED", "ENDED_UNKNOWN");

    /** A autorizacao da versao para o Worker da maquina: a vigente, ou a mais recente que deixou de valer (para dizer por que). */
    public record AuthorizationInfo(UUID authorizationId, OffsetDateTime validUntil, String status) {
    }

    /**
     * Uma maquina habilitada do ambiente, para ESTA versao. {@code missing}: o que impede solicitar nela agora (vazio =
     * pode). {@code authorization}: nula se nunca houve autorizacao desta versao para o Worker.
     */
    public record Candidate(UUID agentId, String agentName, UUID workerId, String workerName, boolean requestable, List<String> missing,
                            AuthorizationInfo authorization, PythonChoice python) {
    }

    /**
     * O ambiente Python escolhido para a automacao NUMA maquina: o identificador opaco que a maquina publicou e a versao que o
     * inventario dizia. {@code inInventory}: o ambiente ainda esta no inventario atual da maquina. Nunca um caminho.
     */
    public record PythonChoice(String environmentId, String pythonVersion, boolean inInventory, OffsetDateTime selectedAt) {
    }

    static final int ACT_PYTHON_SELECTED = 11;
    private static final java.util.regex.Pattern ENVIRONMENT = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{43}$");

    /** {@code communication}: OK, LOST (o prazo da maquina venceu; NAO muda o estado) ou NONE (ainda sem entrega, ou terminada). */
    public record Attempt(UUID attemptId, int attemptNumber, UUID agentId, String agentName, UUID workerId, String workerName, String state,
                          String refusalReason, String communication, Integer exitCode, OffsetDateTime createdAt, OffsetDateTime offerValidUntil,
                          OffsetDateTime offeredAt, OffsetDateTime receivedAt, OffsetDateTime startedAt, OffsetDateTime endedAt) {
    }

    /** {@code state}: o da tentativa mais recente. */
    public record Execution(UUID remoteExecutionId, UUID workspaceId, UUID automationId, UUID automationVersionId, int versionNumber, String state,
                            int requirementsRevision, List<String> dependencies, OffsetDateTime requestedAt, OffsetDateTime cancelRequestedAt,
                            List<Attempt> attempts) {
    }

    public record Created(Execution execution, boolean created) {
    }

    private final AccessActions actions;
    private final WorkspaceAccess access;
    private final boolean enabled;
    private final int offerMinutes;
    private final com.nekkora.core.artifact.ResultStore results;

    /** {@code offerMinutes}: quanto tempo a tentativa espera a maquina receber antes de expirar (padrao de laboratorio: 15). */
    public RemoteExecutionService(AccessActions actions, WorkspaceAccess access, boolean enabled, int offerMinutes,
                                  com.nekkora.core.artifact.ResultStore results) {
        this.results = results;
        this.actions = actions;
        this.access = access;
        this.enabled = enabled;
        this.offerMinutes = Math.max(1, Math.min(offerMinutes, 24 * 60));
    }

    // ------------------------------------------------------------------ o que falta, por maquina

    /**
     * As maquinas habilitadas do ambiente, para uma versao: em cada uma, o que impede solicitar agora. Exige automation.read
     * (quem so consulta ve o que falta; a acao de resolver e oferecida pela tela so a quem tem a permissao dela).
     */
    public List<Candidate> candidates(UUID org, ValidSession actor, String token, UUID workspaceId, UUID versionId) {
        return actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            access.requireWorkspace(j, org, actor.membershipId(), workspaceId, "automation.read");
            Version v = version(j, org, workspaceId, versionId);
            List<String> common = commonMissing(j, org, versionId, v);
            return j.sql("""
                    SELECT a.agent_id, a.name, w.worker_id, w.name, r.receiving, coalesce(p.capabilities @> ARRAY['REMOTE_RUN_1_0']::text[], false),
                           EXISTS (SELECT 1 FROM public.remote_attempts t WHERE t.agent_id = a.agent_id AND t.state BETWEEN 1 AND 6
                                      AND NOT (t.state IN (1, 2) AND t.offer_valid_until <= clock_timestamp())),
                           la.authorization_id, la.valid_until, la.revoked_at IS NOT NULL, la.valid_until <= clock_timestamp(),
                           s.environment_id, s.python_version, s.selected_at,
                           EXISTS (SELECT 1 FROM public.agent_python_environments e WHERE e.agent_id = a.agent_id AND e.environment_id = s.environment_id)
                      FROM public.agent_remote_enablements r
                      JOIN public.agents a ON a.organization_id = r.organization_id AND a.agent_id = r.agent_id AND a.status = 2
                      JOIN public.worker_agent_assignments wa ON wa.organization_id = a.organization_id AND wa.agent_id = a.agent_id AND wa.status = 1
                      JOIN public.workers w ON w.organization_id = wa.organization_id AND w.worker_id = wa.worker_id AND w.status = 1
                      JOIN public.worker_workspace_assignments x ON x.organization_id = w.organization_id AND x.worker_id = w.worker_id
                                                                 AND x.workspace_id = ? AND x.status = 1
                      LEFT JOIN public.agent_presence p ON p.agent_id = a.agent_id
                      LEFT JOIN LATERAL (SELECT z.authorization_id, z.valid_until, z.revoked_at FROM public.local_run_authorizations z
                                          WHERE z.organization_id = r.organization_id AND z.workspace_id = ? AND z.worker_id = w.worker_id
                                            AND z.automation_version_id = ? AND z.source_kind IN (1, 2)
                                          ORDER BY (z.revoked_at IS NULL AND z.valid_until > clock_timestamp()) DESC, z.granted_at DESC LIMIT 1) la ON true
                      LEFT JOIN public.automation_python_selections s ON s.organization_id = r.organization_id AND s.automation_id = ?
                                                                      AND s.agent_id = a.agent_id
                     WHERE r.organization_id = ? ORDER BY w.name, w.worker_id
                    """).param(workspaceId).param(workspaceId).param(versionId).param(v.automationId()).param(org).query((rs, n) -> {
                List<String> missing = new ArrayList<>(common);
                UUID authorization = rs.getObject(8, UUID.class);
                AuthorizationInfo info = null;
                if (authorization == null) {
                    missing.add("AUTHORIZATION_MISSING");
                } else {
                    String status = rs.getBoolean(10) ? "REVOKED" : rs.getBoolean(11) ? "EXPIRED" : "VALID";
                    info = new AuthorizationInfo(authorization, rs.getObject(9, OffsetDateTime.class), status);
                    if (!status.equals("VALID")) {
                        missing.add("AUTHORIZATION_" + status);
                    }
                }
                if (rs.getShort(5) != 1) {
                    missing.add("MACHINE_PAUSED");
                }
                if (!rs.getBoolean(6)) {
                    missing.add("MACHINE_INCOMPATIBLE");
                }
                if (rs.getBoolean(7)) {
                    missing.add("MACHINE_BUSY");
                }
                PythonChoice python = null;
                if (rs.getString(12) == null) {
                    missing.add("PYTHON_NOT_SELECTED");
                } else {
                    python = new PythonChoice(rs.getString(12), rs.getString(13), rs.getBoolean(15), rs.getObject(14, OffsetDateTime.class));
                    if (!python.inInventory()) {
                        missing.add("PYTHON_NOT_IN_INVENTORY");
                    }
                }
                return new Candidate(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4), missing.isEmpty(),
                        List.copyOf(missing), info, python);
            }).list();
        });
    }

    // ------------------------------------------------------------------ escolha do Python

    /**
     * Escolhe o ambiente Python de uma automacao NUMA maquina habilitada do ambiente dela (T01c, parte 3). Exige
     * automation.manage. O ambiente tem de estar no inventario ATUAL da maquina: a Platform guarda so o identificador opaco que
     * ela publicou, nunca um caminho. Trocar substitui a escolha; a de uma maquina nao vale para outra. Nao verifica
     * dependencias nem instala nada: quem confere o ambiente, antes de iniciar, e a maquina.
     */
    public PythonChoice selectPython(UUID org, ValidSession actor, String token, UUID automation, UUID agent, String environmentId) {
        if (environmentId == null || !ENVIRONMENT.matcher(environmentId).matches()) {
            throw ApiException.invalid("ambiente");
        }
        if (!enabled) {
            throw new ApiException(ApiError.REMOTE_EXECUTION_UNAVAILABLE, "execucao_remota_desligada");
        }
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            UUID workspace = access.requireAutomation(j, org, actor.membershipId(), automation, "automation.manage");
            actions.lockOrganizationAndActor(j, org, actor.userId());
            // a maquina so existe para esta automacao se estiver habilitada e o Worker dela estiver autorizado no ambiente
            boolean machine = j.sql("""
                    SELECT count(*) FROM public.agents a
                      JOIN public.agent_remote_enablements r ON r.organization_id = a.organization_id AND r.agent_id = a.agent_id
                      JOIN public.worker_agent_assignments wa ON wa.organization_id = a.organization_id AND wa.agent_id = a.agent_id AND wa.status = 1
                      JOIN public.worker_workspace_assignments ww ON ww.organization_id = wa.organization_id AND ww.worker_id = wa.worker_id
                                                                  AND ww.workspace_id = ? AND ww.status = 1
                     WHERE a.organization_id = ? AND a.agent_id = ? AND a.status = 2
                    """).param(workspace).param(org).param(agent).query(Long.class).single() > 0;
            if (!machine) {
                throw AccessActions.refuse(Failure.TARGET_NOT_FOUND, "maquina");
            }
            if (j.sql("SELECT count(*) FROM public.agent_python_environments WHERE organization_id = ? AND agent_id = ? AND environment_id = ?")
                    .param(org).param(agent).param(environmentId).query(Long.class).single() == 0) {
                throw new ApiException(ApiError.REMOTE_PYTHON_NOT_IN_INVENTORY, "python_fora_do_inventario");
            }
            j.sql("DELETE FROM public.automation_python_selections WHERE organization_id = ? AND automation_id = ? AND agent_id = ?")
                    .param(org).param(automation).param(agent).update();
            // a versao e preenchida pelo banco, a partir do inventario
            j.sql("INSERT INTO public.automation_python_selections (automation_id, agent_id, organization_id, workspace_id, environment_id, python_version, "
                    + "selected_by_membership_id) VALUES (?, ?, ?, ?, ?, '3.0.0', ?)").param(automation).param(agent).param(org).param(workspace)
                    .param(environmentId).param(actor.membershipId()).update();
            j.sql("INSERT INTO public.automation_admin_events (organization_id, act, actor_membership_id, workspace_id, automation_id, correlation_id) "
                    + "VALUES (?, ?, ?, ?, ?, ?)").param(org).param((short) ACT_PYTHON_SELECTED).param(actor.membershipId()).param(workspace).param(automation)
                    .param(AccessActions.correlation()).update();
            LOG.info("evento=automacao.python_escolhido automacao={} maquina={} correlacao={}", automation, agent, AccessActions.correlation());
            return j.sql("SELECT environment_id, python_version, selected_at FROM public.automation_python_selections "
                            + "WHERE organization_id = ? AND automation_id = ? AND agent_id = ?").param(org).param(automation).param(agent)
                    .query((rs, n) -> new PythonChoice(rs.getString(1), rs.getString(2), true, rs.getObject(3, OffsetDateTime.class))).single();
        });
    }

    // ------------------------------------------------------------------ solicitar

    /** Solicita a execucao da versao publicada na maquina indicada. {@code requestKey}: repeticao idempotente (resposta perdida). */
    public Created request(UUID org, ValidSession actor, String token, UUID workspaceId, UUID versionId, UUID agentId, UUID requestKey) {
        if (versionId == null || agentId == null || requestKey == null) {
            throw ApiException.invalid("solicitacao");
        }
        if (!enabled) {
            throw new ApiException(ApiError.REMOTE_EXECUTION_UNAVAILABLE, "execucao_remota_desligada");
        }
        try {
            return requestOnce(org, actor, token, workspaceId, versionId, agentId, requestKey);
        } catch (DuplicateKeyException race) {
            // dois envios concorrentes com a mesma chave: o segundo le o primeiro
            return requestOnce(org, actor, token, workspaceId, versionId, agentId, requestKey);
        }
    }

    private Created requestOnce(UUID org, ValidSession actor, String token, UUID workspaceId, UUID versionId, UUID agentId, UUID requestKey) {
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            access.requireWorkspace(j, org, actor.membershipId(), workspaceId, "automation.execute");
            actions.requireStepUp(j, actor);
            actions.lockOrganizationAndActor(j, org, actor.userId());
            UUID existing = j.sql("SELECT remote_execution_id FROM public.remote_executions WHERE organization_id = ? AND request_key = ?")
                    .param(org).param(requestKey).query(UUID.class).optional().orElse(null);
            if (existing != null) {
                Execution same = execution(j, org, existing);
                if (!same.workspaceId().equals(workspaceId) || !same.automationVersionId().equals(versionId)
                        || same.attempts().isEmpty() || !same.attempts().get(0).agentId().equals(agentId)) {
                    throw AccessActions.refuse(Failure.CONFLICT, "chave_da_solicitacao_com_outro_conteudo");
                }
                return new Created(same, false);
            }
            Version v = version(j, org, workspaceId, versionId);
            // antes de qualquer outra exigencia, e antes de gravar: so pacote Python externo e executado remotamente
            requireSupported(v.rule());
            requireRegistered(j, org, versionId, v.fingerprint(), v.rule());
            RuntimeRequirementsService.View requirements = RuntimeRequirementsService.view(j, org, v.automationId());
            if (requirements.declared() == null) {
                throw new ApiException(ApiError.REMOTE_REQUIREMENTS_NOT_DECLARED, "dependencias_nao_declaradas");
            }
            if (!requirements.effective().resolved()) {
                throw new ApiException(ApiError.REMOTE_REQUIREMENTS_CONFLICT, "dependencias_em_conflito");
            }
            ArrayNode frozen = JSON.createArrayNode();
            requirements.effective().dependencies().forEach(frozen::add);
            Target target = target(j, org, workspaceId, versionId, agentId);
            UUID id = j.sql("""
                    INSERT INTO public.remote_executions (organization_id, workspace_id, automation_id, automation_version_id, package_fingerprint,
                        package_fingerprint_rule, requirements_revision, effective_dependencies, request_key, requested_by_membership_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?) RETURNING remote_execution_id
                    """).param(org).param(workspaceId).param(v.automationId()).param(versionId).param(v.fingerprint()).param(v.rule())
                    .param(requirements.declared().revision()).param(frozen.toString()).param(requestKey).param(actor.membershipId()).query(UUID.class).single();
            attempt(j, org, id, 1, target, actor.membershipId());
            LOG.info("evento=execucao_remota.solicitada solicitacao={} correlacao={}", id, AccessActions.correlation());
            return new Created(execution(j, org, id), true);
        });
    }

    /**
     * "Executar novamente em outra maquina": uma tentativa nova da MESMA solicitacao (mesma versao, mesmo pacote, mesmo retrato
     * de dependencias). So depois que a tentativa anterior tiver TERMINADO: enquanto houver uma nao terminada, 409 - nao ha
     * duas maquinas com a mesma solicitacao, e nada e trocado de maquina automaticamente. A autorizacao conferida e a da
     * versao para o Worker de DESTINO; a da maquina anterior nao e transferida.
     */
    public Execution retry(UUID org, ValidSession actor, String token, UUID executionId, UUID agentId) {
        if (agentId == null) {
            throw ApiException.invalid("maquina");
        }
        if (!enabled) {
            throw new ApiException(ApiError.REMOTE_EXECUTION_UNAVAILABLE, "execucao_remota_desligada");
        }
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            Execution x = scoped(j, org, actor, executionId, "automation.execute");
            actions.requireStepUp(j, actor);
            actions.lockOrganizationAndActor(j, org, actor.userId());
            j.sql("SELECT 1 FROM public.remote_executions WHERE organization_id = ? AND remote_execution_id = ? FOR UPDATE").param(org).param(executionId)
                    .query(Integer.class).single();
            expireStale(j, org, executionId, null);
            x = execution(j, org, executionId);
            if (x.cancelRequestedAt() != null) {
                throw new ApiException(ApiError.REMOTE_EXECUTION_CANCEL_REQUESTED, "cancelamento_pedido");
            }
            Attempt last = x.attempts().get(x.attempts().size() - 1);
            if (STATES.indexOf(last.state()) < 6) {
                throw new ApiException(ApiError.REMOTE_ATTEMPT_NOT_FINISHED, "tentativa_anterior_nao_terminou");
            }
            // a mesma recusa da solicitacao: uma solicitacao antiga de pacote que nao e externo nao ganha nova tentativa
            requireSupported(j.sql("SELECT package_fingerprint_rule FROM public.remote_executions WHERE organization_id = ? AND remote_execution_id = ?")
                    .param(org).param(executionId).query(String.class).single());
            requireRegistered(j, org, x.automationVersionId(), j.sql("SELECT package_fingerprint FROM public.remote_executions "
                    + "WHERE organization_id = ? AND remote_execution_id = ?").param(org).param(executionId).query(String.class).single(), SUPPORTED_RULE);
            Target target = target(j, org, x.workspaceId(), x.automationVersionId(), agentId);
            attempt(j, org, executionId, last.attemptNumber() + 1, target, actor.membershipId());
            LOG.info("evento=execucao_remota.nova_tentativa solicitacao={} tentativa={} correlacao={}", executionId, last.attemptNumber() + 1,
                    AccessActions.correlation());
            return execution(j, org, executionId);
        });
    }

    /**
     * Pede o cancelamento. A tentativa que a maquina ainda NAO recebeu e cancelada aqui mesmo; a que ja foi recebida so para
     * quando a maquina obedecer e disser - o pedido fica gravado e a tela mostra "cancelamento pedido" ate la.
     */
    public Execution cancel(UUID org, ValidSession actor, String token, UUID executionId) {
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            Execution x = scoped(j, org, actor, executionId, "automation.execute");
            actions.requireStepUp(j, actor);
            j.sql("SELECT 1 FROM public.remote_executions WHERE organization_id = ? AND remote_execution_id = ? FOR UPDATE").param(org).param(executionId)
                    .query(Integer.class).single();
            x = execution(j, org, executionId);
            Attempt last = x.attempts().get(x.attempts().size() - 1);
            if (STATES.indexOf(last.state()) >= 6) {
                throw new ApiException(ApiError.REMOTE_ATTEMPT_ALREADY_FINISHED, "tentativa_ja_terminou");
            }
            if (x.cancelRequestedAt() == null) {
                j.sql("UPDATE public.remote_executions SET cancel_requested_by_membership_id = ? WHERE organization_id = ? AND remote_execution_id = ?")
                        .param(actor.membershipId()).param(org).param(executionId).update();
            }
            j.sql("UPDATE public.remote_attempts SET state = 9 WHERE organization_id = ? AND remote_execution_id = ? AND state IN (1, 2)")
                    .param(org).param(executionId).update();
            LOG.info("evento=execucao_remota.cancelamento_pedido solicitacao={} correlacao={}", executionId, AccessActions.correlation());
            return execution(j, org, executionId);
        });
    }

    // ------------------------------------------------------------------ consultar

    public Execution get(UUID org, ValidSession actor, String token, UUID executionId) {
        return actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            return scoped(j, org, actor, executionId, "automation.read");
        });
    }

    /** As solicitacoes mais recentes do ambiente (ate {@code limit}, 1 a 100). */
    public List<Execution> list(UUID org, ValidSession actor, String token, UUID workspaceId, int limit) {
        return actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            access.requireWorkspace(j, org, actor.membershipId(), workspaceId, "automation.read");
            List<UUID> ids = j.sql("SELECT remote_execution_id FROM public.remote_executions WHERE organization_id = ? AND workspace_id = ? "
                    + "ORDER BY requested_at DESC, remote_execution_id DESC LIMIT ?").param(org).param(workspaceId).param(limit).query(UUID.class).list();
            List<Execution> out = new ArrayList<>(ids.size());
            for (UUID id : ids) {
                out.add(execution(j, org, id));
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ resultados (T03b)

    /** Um arquivo de resultado. {@code available}: os bytes ainda estao guardados (a retencao pode te-los removido). */
    public record ResultFile(int number, String fileName, long sizeBytes, String sha256, OffsetDateTime receivedAt, boolean available) {
    }

    /** {@code closure} nulo: a maquina ainda nao fechou o envio (pode haver arquivo a caminho, ou ela nao voltou). */
    /**
     * A maquina avisou que removeu, pelo prazo de guarda local, o que guardava sem confirmacao de recebimento. {@code localCount}:
     * quanto havia NELA (linhas capturadas, ou arquivos na pasta de saida) - nao e quanto se perdeu.
     */
    public record RetentionLoss(int localCount, OffsetDateTime reportedAt) {
    }

    private static RetentionLoss retentionLoss(JdbcClient j, UUID org, UUID attemptId, int kind) {
        return j.sql("SELECT local_count, reported_at FROM public.remote_attempt_retention_losses WHERE organization_id = ? AND attempt_id = ? AND kind = ?")
                .param(org).param(attemptId).param((short) kind).query((rs, n) -> new RetentionLoss(rs.getInt(1), rs.getObject(2, OffsetDateTime.class)))
                .optional().orElse(null);
    }

    public record Results(UUID attemptId, List<ResultFile> items, ResultsClosure closure, RetentionLoss retentionLoss) {
    }

    public record ResultsClosure(int sent, int skippedByLimit, OffsetDateTime closedAt) {
    }

    public record ResultContent(String fileName, String sha256, byte[] bytes) {
    }

    static final int ACT_RESULT_DOWNLOADED = 12;

    /** Os arquivos de resultado de uma tentativa e o fechamento do envio. Exige logs.read no ambiente da solicitacao. */
    /** {@code message}: vazio quando {@code textRemoved} (a retencao apagou o texto; a linha existiu). */
    public record LogLine(int sequence, OffsetDateTime capturedAt, String channel, String level, boolean truncated, String message, boolean textRemoved) {
    }

    public record LogsClosure(int lines, long droppedLines, OffsetDateTime closedAt) {
    }

    /** {@code nextAfter}: o valor de {@code after} para a pagina seguinte; nulo quando esta pagina chegou ao fim do que ha agora. */
    public record Logs(UUID attemptId, List<LogLine> items, int total, Integer nextAfter, LogsClosure closure, RetentionLoss retentionLoss) {
    }

    private static final List<String> LOG_CHANNELS = List.of("STDOUT", "STDERR", "EXECUTOR");
    static final int LOG_PAGE_MAX = 500;

    /**
     * As linhas da saida do processo de uma tentativa, na ordem da captura, a partir da sequencia {@code after} (exclusiva), e o
     * fechamento do envio. Exige logs.read no ambiente da solicitacao. So existem linhas de tentativa REMOTA.
     */
    public Logs logs(UUID org, ValidSession actor, String token, UUID executionId, UUID attemptId, Integer after, Integer limit) {
        int from = after == null ? 0 : after;
        int page = limit == null ? LOG_PAGE_MAX : limit;
        if (from < 0 || page < 1 || page > LOG_PAGE_MAX) {
            throw ApiException.invalid("pagina");
        }
        return actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            scoped(j, org, actor, executionId, "logs.read");
            requireAttempt(j, org, executionId, attemptId);
            List<LogLine> items = j.sql("SELECT sequence, captured_at, channel, truncated, message, redacted_at IS NOT NULL FROM public.remote_attempt_log_lines "
                            + "WHERE organization_id = ? AND attempt_id = ? AND sequence > ? ORDER BY sequence LIMIT ?").param(org).param(attemptId).param(from)
                    .param(page).query((rs, n) -> new LogLine(rs.getInt(1), rs.getObject(2, OffsetDateTime.class), LOG_CHANNELS.get(rs.getShort(3) - 1),
                            "UNKNOWN", rs.getBoolean(4), rs.getString(5), rs.getBoolean(6))).list();
            int total = j.sql("SELECT count(*) FROM public.remote_attempt_log_lines WHERE organization_id = ? AND attempt_id = ?").param(org).param(attemptId)
                    .query(Integer.class).single();
            LogsClosure closure = j.sql("SELECT lines, dropped_lines, closed_at FROM public.remote_attempt_log_closures WHERE organization_id = ? "
                            + "AND attempt_id = ?").param(org).param(attemptId)
                    .query((rs, n) -> new LogsClosure(rs.getInt(1), rs.getLong(2), rs.getObject(3, OffsetDateTime.class))).optional().orElse(null);
            Integer next = items.size() == page && items.get(items.size() - 1).sequence() < total ? items.get(items.size() - 1).sequence() : null;
            return new Logs(attemptId, items, total, next, closure, retentionLoss(j, org, attemptId, 1));
        });
    }

    public Results results(UUID org, ValidSession actor, String token, UUID executionId, UUID attemptId) {
        return actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            scoped(j, org, actor, executionId, "logs.read");
            requireAttempt(j, org, executionId, attemptId);
            List<ResultFile> items = j.sql("SELECT result_number, file_name, size_bytes, sha256, received_at, removed_at IS NULL FROM public.remote_attempt_results "
                            + "WHERE organization_id = ? AND attempt_id = ? ORDER BY result_number").param(org).param(attemptId)
                    .query((rs, n) -> new ResultFile(rs.getInt(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getObject(5, OffsetDateTime.class),
                            rs.getBoolean(6))).list();
            ResultsClosure closure = j.sql("SELECT sent, skipped_by_limit, closed_at FROM public.remote_attempt_result_closures WHERE organization_id = ? "
                            + "AND attempt_id = ?").param(org).param(attemptId)
                    .query((rs, n) -> new ResultsClosure(rs.getInt(1), rs.getInt(2), rs.getObject(3, OffsetDateTime.class))).optional().orElse(null);
            return new Results(attemptId, items, closure, retentionLoss(j, org, attemptId, 2));
        });
    }

    /**
     * Os bytes de um arquivo de resultado, relidos e conferidos pelo SHA-256. Exige logs.read. O download fica na trilha da
     * automacao (ato 12). Bytes removidos pela retencao, ausentes ou que nao conferem: 409 remote_result_unavailable.
     */
    public ResultContent resultContent(UUID org, ValidSession actor, String token, UUID executionId, UUID attemptId, int number) {
        record Found(String name, String sha, boolean removed, UUID workspace, UUID automation) {
        }
        Found f = actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            Execution x = scoped(j, org, actor, executionId, "logs.read");
            requireAttempt(j, org, executionId, attemptId);
            Found found = j.sql("SELECT file_name, sha256, removed_at IS NOT NULL FROM public.remote_attempt_results WHERE organization_id = ? AND attempt_id = ? "
                            + "AND result_number = ?").param(org).param(attemptId).param((short) number)
                    .query((rs, n) -> new Found(rs.getString(1), rs.getString(2), rs.getBoolean(3), x.workspaceId(), x.automationId())).optional()
                    .orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "resultado"));
            return found;
        });
        if (f.removed()) {
            throw new ApiException(ApiError.REMOTE_RESULT_UNAVAILABLE, "resultado_removido");
        }
        if (!results.available()) {
            throw new ApiException(ApiError.SERVICE_UNAVAILABLE, "armazenamento_de_resultados");
        }
        try {
            byte[] bytes = results.get(org, f.sha()).orElseThrow(() -> new ApiException(ApiError.REMOTE_RESULT_UNAVAILABLE, "resultado_ausente"));
            // so o download que de fato entrega os bytes vai para a trilha (com a permissao reconferida)
            actions.write(org, j -> {
                actions.requireActor(j, org, actor, token);
                scoped(j, org, actor, executionId, "logs.read");
                j.sql("INSERT INTO public.automation_admin_events (organization_id, act, actor_membership_id, workspace_id, automation_id, correlation_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)").param(org).param((short) ACT_RESULT_DOWNLOADED).param(actor.membershipId()).param(f.workspace())
                        .param(f.automation()).param(AccessActions.correlation()).update();
                return null;
            });
            LOG.info("evento=execucao_remota.resultado_baixado tentativa={} numero={} correlacao={}", attemptId, number, AccessActions.correlation());
            return new ResultContent(f.name(), f.sha(), bytes);
        } catch (com.nekkora.core.artifact.ArtifactStore.Corrupted e) {
            throw new ApiException(ApiError.REMOTE_RESULT_UNAVAILABLE, "resultado_corrompido");
        } catch (java.io.IOException e) {
            throw new ApiException(ApiError.SERVICE_UNAVAILABLE, "armazenamento_de_resultados");
        }
    }

    private static void requireAttempt(JdbcClient j, UUID org, UUID executionId, UUID attemptId) {
        if (j.sql("SELECT count(*) FROM public.remote_attempts WHERE organization_id = ? AND remote_execution_id = ? AND attempt_id = ?")
                .param(org).param(executionId).param(attemptId).query(Long.class).single() == 0) {
            throw AccessActions.refuse(Failure.TARGET_NOT_FOUND, "tentativa");
        }
    }

    // ------------------------------------------------------------------ apoio

    private record Version(UUID automationId, String fingerprint, String rule) {
    }

    private record Target(UUID agentId, UUID workerId, UUID authorizationId) {
    }

    /** A versao PUBLICADA do ambiente; fora disso, 404 (a versao nao existe para esta solicitacao). */
    private static Version version(JdbcClient j, UUID org, UUID workspaceId, UUID versionId) {
        return j.sql("SELECT automation_id, command_json -> 'agent_package' ->> 'fingerprint', command_json -> 'agent_package' ->> 'fingerprint_rule' "
                        + "FROM public.automation_versions WHERE organization_id = ? AND workspace_id = ? AND automation_version_id = ? AND status = 2")
                .param(org).param(workspaceId).param(versionId).query((rs, n) -> new Version(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .optional().orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "versao_publicada"));
    }

    /**
     * A unica regra de pacote que a execucao remota aceita nesta versao do produto: o pacote Python externo. O pacote no formato
     * Studio fica de fora. O aplicativo da maquina faz a mesma conferencia por conta propria.
     */
    static final String SUPPORTED_RULE = AgentAdminService.EXTERNAL_FINGERPRINT_RULE;

    private static void requireSupported(String rule) {
        if (!SUPPORTED_RULE.equals(rule)) {
            throw new ApiException(ApiError.REMOTE_PACKAGE_NOT_SUPPORTED, "pacote_nao_e_python_externo");
        }
    }

    /**
     * O pacote da versao, com a impressao fixada, precisa estar REGISTRADO na Platform: e dele que o banco tira, quando a
     * tentativa nasce, o que a maquina podera baixar (048). Sem registro, a tentativa nao seria criada; a recusa vem antes.
     */
    private static boolean registered(JdbcClient j, UUID org, UUID versionId, String fingerprint, String rule) {
        return j.sql("SELECT EXISTS (SELECT 1 FROM public.automation_artifacts f WHERE f.organization_id = ? AND f.automation_version_id = ? "
                + "AND f.fingerprint = ? AND f.fingerprint_rule = ?)").param(org).param(versionId).param(fingerprint).param(rule).query(Boolean.class).single();
    }

    private static void requireRegistered(JdbcClient j, UUID org, UUID versionId, String fingerprint, String rule) {
        if (!registered(j, org, versionId, fingerprint, rule)) {
            throw new ApiException(ApiError.REMOTE_PACKAGE_NOT_REGISTERED, "pacote_sem_registro");
        }
    }

    /** O que falta e nao depende da maquina: o tipo do pacote da versao, o registro dele e a declaracao das dependencias. */
    private static List<String> commonMissing(JdbcClient j, UUID org, UUID versionId, Version v) {
        List<String> out = new ArrayList<>();
        if (!SUPPORTED_RULE.equals(v.rule())) {
            out.add("PACKAGE_NOT_SUPPORTED");
        } else if (!registered(j, org, versionId, v.fingerprint(), v.rule())) {
            out.add("PACKAGE_NOT_REGISTERED");
        }
        RuntimeRequirementsService.View r = RuntimeRequirementsService.view(j, org, v.automationId());
        if (r.declared() == null) {
            out.add("REQUIREMENTS_NOT_DECLARED");
        } else if (!r.effective().resolved()) {
            out.add("REQUIREMENTS_CONFLICT");
        }
        return List.copyOf(out);
    }

    /**
     * A maquina de destino e a autorizacao da versao para o Worker DELA. Cada exigencia que falta tem o seu motivo; a ordem
     * e a da decisao: Worker no ambiente, maquina (habilitada, nao pausada, compativel, livre), autorizacao da versao.
     */
    private Target target(JdbcClient j, UUID org, UUID workspaceId, UUID versionId, UUID agentId) {
        record Row(UUID worker, boolean workerInWorkspace, boolean enabledMachine, short receiving, boolean compatible) {
        }
        Row m = j.sql("""
                SELECT wa.worker_id,
                       EXISTS (SELECT 1 FROM public.worker_workspace_assignments x JOIN public.workers w ON w.organization_id = x.organization_id
                                  AND w.worker_id = x.worker_id AND w.status = 1
                                WHERE x.organization_id = a.organization_id AND x.worker_id = wa.worker_id AND x.workspace_id = ? AND x.status = 1),
                       r.agent_id IS NOT NULL, coalesce(r.receiving, 0), coalesce(p.capabilities @> ARRAY['REMOTE_RUN_1_0']::text[], false)
                  FROM public.agents a
                  JOIN public.worker_agent_assignments wa ON wa.organization_id = a.organization_id AND wa.agent_id = a.agent_id AND wa.status = 1
                  LEFT JOIN public.agent_remote_enablements r ON r.organization_id = a.organization_id AND r.agent_id = a.agent_id
                  LEFT JOIN public.agent_presence p ON p.agent_id = a.agent_id
                 WHERE a.organization_id = ? AND a.agent_id = ? AND a.status = 2
                """).param(workspaceId).param(org).param(agentId)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getBoolean(2), rs.getBoolean(3), rs.getShort(4), rs.getBoolean(5))).optional()
                .orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "maquina"));
        if (!m.workerInWorkspace()) {
            // maquina de um Worker que nao esta neste ambiente: para quem solicita aqui, ela nao existe
            throw AccessActions.refuse(Failure.TARGET_NOT_FOUND, "worker_fora_do_ambiente");
        }
        if (!m.enabledMachine()) {
            throw new ApiException(ApiError.REMOTE_MACHINE_NOT_ENABLED, "maquina_nao_habilitada");
        }
        if (m.receiving() != 1) {
            throw new ApiException(ApiError.REMOTE_MACHINE_PAUSED, "maquina_pausada");
        }
        if (!m.compatible()) {
            throw new ApiException(ApiError.REMOTE_MACHINE_INCOMPATIBLE, "maquina_incompativel");
        }
        expireStale(j, org, null, agentId);
        if (j.sql("SELECT count(*) FROM public.remote_attempts WHERE organization_id = ? AND agent_id = ? AND state BETWEEN 1 AND 6").param(org).param(agentId)
                .query(Long.class).single() > 0) {
            throw new ApiException(ApiError.REMOTE_MACHINE_BUSY, "maquina_ocupada");
        }
        record Grant(UUID id, boolean revoked, boolean expired) {
        }
        Grant g = j.sql("""
                SELECT z.authorization_id, z.revoked_at IS NOT NULL, z.valid_until <= clock_timestamp() FROM public.local_run_authorizations z
                 WHERE z.organization_id = ? AND z.workspace_id = ? AND z.worker_id = ? AND z.automation_version_id = ? AND z.source_kind IN (1, 2)
                 ORDER BY (z.revoked_at IS NULL AND z.valid_until > clock_timestamp()) DESC, z.granted_at DESC LIMIT 1
                """).param(org).param(workspaceId).param(m.worker()).param(versionId)
                .query((rs, n) -> new Grant(rs.getObject(1, UUID.class), rs.getBoolean(2), rs.getBoolean(3))).optional().orElse(null);
        if (g == null) {
            throw new ApiException(ApiError.REMOTE_AUTHORIZATION_MISSING, "sem_autorizacao_da_versao");
        }
        if (g.revoked()) {
            throw new ApiException(ApiError.REMOTE_AUTHORIZATION_REVOKED, "autorizacao_revogada");
        }
        if (g.expired()) {
            throw new ApiException(ApiError.REMOTE_AUTHORIZATION_EXPIRED, "autorizacao_vencida");
        }
        // o ambiente Python desta automacao NESTA maquina: escolhido por alguem e ainda no inventario dela
        Boolean inInventory = j.sql("""
                SELECT EXISTS (SELECT 1 FROM public.agent_python_environments e WHERE e.agent_id = s.agent_id AND e.environment_id = s.environment_id)
                  FROM public.automation_python_selections s
                  JOIN public.automation_versions v ON v.organization_id = s.organization_id AND v.automation_id = s.automation_id
                 WHERE s.organization_id = ? AND s.agent_id = ? AND v.automation_version_id = ?
                """).param(org).param(agentId).param(versionId).query(Boolean.class).optional().orElse(null);
        if (inInventory == null) {
            throw new ApiException(ApiError.REMOTE_PYTHON_NOT_SELECTED, "python_nao_escolhido");
        }
        if (!inInventory) {
            throw new ApiException(ApiError.REMOTE_PYTHON_NOT_IN_INVENTORY, "python_fora_do_inventario");
        }
        return new Target(agentId, m.worker(), g.id());
    }

    private void attempt(JdbcClient j, UUID org, UUID executionId, int number, Target t, UUID membership) {
        try {
            j.sql("""
                    INSERT INTO public.remote_attempts (organization_id, remote_execution_id, attempt_number, agent_id, worker_id, authorization_id,
                        created_by_membership_id, offer_valid_until)
                    VALUES (?, ?, ?, ?, ?, ?, ?, clock_timestamp() + make_interval(mins => ?))
                    """).param(org).param(executionId).param(number).param(t.agentId()).param(t.workerId()).param(t.authorizationId()).param(membership)
                    .param(offerMinutes).update();
        } catch (DuplicateKeyException busy) {
            // a reserva e atomica: outra solicitacao chegou a esta maquina (ou a esta solicitacao) entre a conferencia e a gravacao
            throw new ApiException(ApiError.REMOTE_MACHINE_BUSY, "maquina_ocupada");
        } catch (DataAccessException e) {
            // o banco reconferiu e recusou (algo mudou entre a conferencia e a gravacao): nada e gravado
            throw new ApiException(ApiError.CONFLICT, "condicoes_mudaram");
        }
    }

    /** Quem a maquina nao recebeu dentro do prazo deixa de ocupar a vaga: QUEUED/OFFERED vencidos viram EXPIRED. */
    private static void expireStale(JdbcClient j, UUID org, UUID executionId, UUID agentId) {
        j.sql("UPDATE public.remote_attempts SET state = 12 WHERE organization_id = ? AND state IN (1, 2) AND offer_valid_until <= clock_timestamp() "
                        + "AND (remote_execution_id = ? OR agent_id = ?)").param(org).param(executionId).param(agentId).update();
    }

    private Execution scoped(JdbcClient j, UUID org, ValidSession actor, UUID executionId, String permission) {
        UUID ws = j.sql("SELECT workspace_id FROM public.remote_executions WHERE organization_id = ? AND remote_execution_id = ?").param(org).param(executionId)
                .query(UUID.class).optional().orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "solicitacao"));
        access.requireWorkspace(j, org, actor.membershipId(), ws, permission);
        return execution(j, org, executionId);
    }

    private static Execution execution(JdbcClient j, UUID org, UUID id) {
        List<Attempt> attempts = j.sql("""
                SELECT t.attempt_id, t.attempt_number, t.agent_id, a.name, t.worker_id, w.name,
                       CASE WHEN t.state IN (1, 2) AND t.offer_valid_until <= clock_timestamp() THEN 12 ELSE t.state END, t.refusal_reason,
                       CASE WHEN t.state BETWEEN 2 AND 6 AND t.lease_expires_at IS NOT NULL
                            THEN CASE WHEN t.lease_expires_at > clock_timestamp() THEN 'OK' ELSE 'LOST' END ELSE 'NONE' END,
                       t.exit_code, t.created_at, t.offer_valid_until, t.offered_at, t.received_at, t.started_at, t.ended_at
                  FROM public.remote_attempts t
                  JOIN public.agents a ON a.organization_id = t.organization_id AND a.agent_id = t.agent_id
                  JOIN public.workers w ON w.organization_id = t.organization_id AND w.worker_id = t.worker_id
                 WHERE t.organization_id = ? AND t.remote_execution_id = ? ORDER BY t.attempt_number
                """).param(org).param(id).query((rs, n) -> new Attempt(rs.getObject(1, UUID.class), rs.getInt(2), rs.getObject(3, UUID.class), rs.getString(4),
                rs.getObject(5, UUID.class), rs.getString(6), STATES.get(rs.getShort(7) - 1), rs.getString(8), rs.getString(9),
                (Integer) rs.getObject(10), rs.getObject(11, OffsetDateTime.class), rs.getObject(12, OffsetDateTime.class),
                rs.getObject(13, OffsetDateTime.class), rs.getObject(14, OffsetDateTime.class), rs.getObject(15, OffsetDateTime.class),
                rs.getObject(16, OffsetDateTime.class))).list();
        return j.sql("""
                SELECT x.remote_execution_id, x.workspace_id, x.automation_id, x.automation_version_id, v.version_number, x.requirements_revision,
                       x.effective_dependencies::text, x.requested_at, x.cancel_requested_at
                  FROM public.remote_executions x
                  JOIN public.automation_versions v ON v.organization_id = x.organization_id AND v.automation_version_id = x.automation_version_id
                 WHERE x.organization_id = ? AND x.remote_execution_id = ?
                """).param(org).param(id).query((rs, n) -> {
            List<String> deps = new ArrayList<>();
            JSON.readTree(rs.getString(7)).forEach(d -> deps.add(d.asString()));
            return new Execution(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                    rs.getInt(5), attempts.isEmpty() ? "QUEUED" : attempts.get(attempts.size() - 1).state(), rs.getInt(6), List.copyOf(deps),
                    rs.getObject(8, OffsetDateTime.class), rs.getObject(9, OffsetDateTime.class), attempts);
        }).single();
    }
}
