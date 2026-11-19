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

import com.nekkora.core.remote.AttemptClosure;
import com.nekkora.core.license.LicenseGate;
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
                            AuthorizationInfo authorization, PythonChoice python, DependencyCheck dependencyCheck) {
    }

    /**
     * A verificacao sob demanda mais recente das dependencias desta automacao nesta maquina (054); nula se nunca houve.
     * {@code state}: PENDING (aguardando a maquina, dentro do prazo), ANSWERED ou UNANSWERED (o prazo venceu sem resposta: nao
     * foi possivel verificar - nunca "incompativel"). {@code outcome}, so em ANSWERED: SATISFIED, MISSING (com {@code missing}),
     * ENVIRONMENT_GONE ou NOT_VERIFIED. {@code current}: o que foi conferido ainda e a escolha do Python e a revisao das
     * dependencias de agora; senao, a resposta e de outra configuracao. E informacao: a conferencia que vale para executar e a
     * da maquina, antes de iniciar.
     */
    public record DependencyCheck(UUID checkId, String environmentId, int requirementsRevision, OffsetDateTime requestedAt, OffsetDateTime answerValidUntil,
                                  String state, String outcome, List<String> missing, OffsetDateTime answeredAt, boolean current) {
    }

    static final List<String> CHECK_OUTCOMES = List.of("SATISFIED", "MISSING", "ENVIRONMENT_GONE", "NOT_VERIFIED");
    /** Quanto tempo a maquina tem para responder a uma verificacao (ponto de partida de laboratorio). */
    /** Parametros INICIAIS da verificacao sob demanda (decisao de 16/11): configuraveis, ver {@link #dependencyCheckLimits}. */
    static final int CHECK_ANSWER_SECONDS = 120;
    static final int CHECK_INTERVAL_SECONDS = 60;
    private int checkAnswerSeconds = CHECK_ANSWER_SECONDS;
    private int checkIntervalSeconds = CHECK_INTERVAL_SECONDS;

    /**
     * Intervalo minimo entre pedidos para a MESMA maquina e automacao (10 a 3600 s; o banco guarda o piso de 10) e prazo da
     * resposta (30 a 600 s). O escopo do limite nao muda: o pedido de um cliente nunca segura o de outro.
     */
    public RemoteExecutionService dependencyCheckLimits(int intervalSeconds, int answerSeconds) {
        this.checkIntervalSeconds = Math.max(10, Math.min(intervalSeconds, 3600));
        this.checkAnswerSeconds = Math.max(30, Math.min(answerSeconds, 600));
        return this;
    }

    private static DependencyCheck latestCheck(JdbcClient j, UUID org, UUID automation, UUID agent) {
        return j.sql("""
                SELECT c.check_id, c.environment_id, c.requirements_revision, c.requested_at, c.answer_valid_until, c.outcome, c.missing::text, c.answered_at,
                       c.answer_valid_until > clock_timestamp(),
                       EXISTS (SELECT 1 FROM public.automation_python_selections s WHERE s.organization_id = c.organization_id
                                  AND s.automation_id = c.automation_id AND s.agent_id = c.agent_id AND s.environment_id = c.environment_id)
                       AND c.requirements_revision = (SELECT max(r.revision_number) FROM public.automation_runtime_requirements r
                                                       WHERE r.organization_id = c.organization_id AND r.automation_id = c.automation_id)
                  FROM public.remote_dependency_checks c
                 WHERE c.organization_id = ? AND c.automation_id = ? AND c.agent_id = ? ORDER BY c.requested_at DESC, c.check_id LIMIT 1
                """).param(org).param(automation).param(agent).query((rs, n) -> {
            Short outcome = rs.getObject(6) == null ? null : rs.getShort(6);
            List<String> missing = new ArrayList<>();
            if (rs.getString(7) != null) {
                JSON.readTree(rs.getString(7)).forEach(d -> missing.add(d.asString()));
            }
            String state = outcome != null ? "ANSWERED" : rs.getBoolean(9) ? "PENDING" : "UNANSWERED";
            return new DependencyCheck(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getObject(4, OffsetDateTime.class),
                    rs.getObject(5, OffsetDateTime.class), state, outcome == null ? null : CHECK_OUTCOMES.get(outcome - 1), List.copyOf(missing),
                    rs.getObject(8, OffsetDateTime.class), rs.getBoolean(10));
        }).optional().orElse(null);
    }

    /**
     * Pede a maquina que confira, AGORA, as dependencias declaradas desta automacao no Python escolhido para ela (054). Exige
     * automation.execute no ambiente da automacao (quem pode mandar executar pode perguntar se vai dar certo). Congela o que sera
     * conferido: a escolha vigente e a lista efetiva vigente. No maximo um pedido por intervalo (padrao: um minuto) por maquina e automacao: o segundo
     * recebe 429 + remote_dependency_check_too_soon e nada e criado. A resposta chega depois (a maquina consulta), em
     * {@code candidates}; sem resposta no prazo (padrao: {@value #CHECK_ANSWER_SECONDS} s), o pedido fica UNANSWERED.
     */
    public DependencyCheck checkDependencies(UUID org, ValidSession actor, String token, UUID automation, UUID agent) {
        if (!enabled) {
            throw new ApiException(ApiError.REMOTE_EXECUTION_UNAVAILABLE, "execucao_remota_desligada");
        }
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            UUID workspace = access.requireAutomation(j, org, actor.membershipId(), automation, "automation.execute");
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
            // um pedido de cada vez para a mesma maquina e automacao (o limite e conferido com a fila parada)
            j.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 54))").param(agent + "|" + automation).query(rs -> {
            });
            String environment = j.sql("SELECT s.environment_id FROM public.automation_python_selections s JOIN public.agent_python_environments e "
                            + "ON e.agent_id = s.agent_id AND e.environment_id = s.environment_id "
                            + "WHERE s.organization_id = ? AND s.automation_id = ? AND s.agent_id = ?").param(org).param(automation).param(agent)
                    .query(String.class).optional().orElse(null);
            if (environment == null) {
                boolean chosen = j.sql("SELECT count(*) FROM public.automation_python_selections WHERE organization_id = ? AND automation_id = ? AND agent_id = ?")
                        .param(org).param(automation).param(agent).query(Long.class).single() > 0;
                throw new ApiException(chosen ? ApiError.REMOTE_PYTHON_NOT_IN_INVENTORY : ApiError.REMOTE_PYTHON_NOT_SELECTED, "python");
            }
            RuntimeRequirementsService.View requirements = RuntimeRequirementsService.view(j, org, automation);
            if (requirements.declared() == null) {
                throw new ApiException(ApiError.REMOTE_REQUIREMENTS_NOT_DECLARED, "dependencias_nao_declaradas");
            }
            if (!requirements.effective().resolved()) {
                throw new ApiException(ApiError.REMOTE_REQUIREMENTS_CONFLICT, "dependencias_em_conflito");
            }
            if (j.sql("SELECT count(*) FROM public.remote_dependency_checks WHERE organization_id = ? AND automation_id = ? AND agent_id = ? "
                    + "AND requested_at > clock_timestamp() - make_interval(secs => ?)").param(org).param(automation).param(agent).param(checkIntervalSeconds)
                    .query(Long.class).single() > 0) {
                throw new ApiException(ApiError.REMOTE_DEPENDENCY_CHECK_TOO_SOON, "verificacao_recente");
            }
            ArrayNode frozen = JSON.createArrayNode();
            requirements.effective().dependencies().forEach(frozen::add);
            j.sql("""
                    INSERT INTO public.remote_dependency_checks (organization_id, workspace_id, automation_id, agent_id, environment_id, requirements_revision,
                        dependencies, requested_by_membership_id, answer_valid_until)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, clock_timestamp() + make_interval(secs => ?))
                    """).param(org).param(workspace).param(automation).param(agent).param(environment).param(requirements.declared().revision())
                    .param(frozen.toString()).param(actor.membershipId()).param(checkAnswerSeconds).update();
            LOG.info("evento=execucao_remota.verificacao_pedida automacao={} maquina={} correlacao={}", automation, agent, AccessActions.correlation());
            return latestCheck(j, org, automation, agent);
        });
    }

    /**
     * O ambiente Python escolhido para a automacao NUMA maquina: o identificador opaco que a maquina publicou e a versao que o
     * inventario dizia. {@code inInventory}: o ambiente ainda esta no inventario atual da maquina. Nunca um caminho.
     */
    public record PythonChoice(String environmentId, String pythonVersion, boolean inInventory, OffsetDateTime selectedAt) {
    }

    static final int ACT_PYTHON_SELECTED = 11;
    private static final java.util.regex.Pattern ENVIRONMENT = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{43}$");

    /**
     * {@code communication}: OK, LOST (o prazo da maquina venceu) ou NONE (ainda sem entrega, ou terminada).
     * {@code closureCause} (053): por que o SERVIDOR encerrou a tentativa - MACHINE_UNLINKED, MACHINE_REVOKED ou
     * COMMUNICATION_LOST; nulo quando o desfecho veio da maquina. {@code lateReport}: o que a maquina informou depois de um
     * encerramento por COMMUNICATION_LOST; o estado da tentativa nao muda por isso.
     */
    public record Attempt(UUID attemptId, int attemptNumber, UUID agentId, String agentName, UUID workerId, String workerName, String state,
                          String refusalReason, String communication, Integer exitCode, OffsetDateTime createdAt, OffsetDateTime offerValidUntil,
                          OffsetDateTime offeredAt, OffsetDateTime receivedAt, OffsetDateTime startedAt, OffsetDateTime endedAt, String closureCause,
                          LateReport lateReport, List<Reconciliation> reconciliations, OffsetDateTime machineStillRunningAt, String processes,
                          OffsetDateTime processesAt) {
        /** Tem processo pendente e ninguem o confirmou ausente (nem a maquina, nem uma declaracao): bloqueia a repeticao. */
        public boolean unresolved() {
            return ("ENDED_UNKNOWN".equals(state) || processes != null) && reconciliations.isEmpty();
        }
    }

    /**
     * 058: como uma tentativa com resultado nao confirmado foi reconciliada. {@code kind}: MACHINE (a maquina confirmou que nao ha
     * processo) ou MANUAL (declaracao de um ORG_ADMIN; so ai ha motivo, verificacao e autor). O estado da tentativa NAO muda.
     */
    public record Reconciliation(String kind, OffsetDateTime at, String reason, String verification, UUID byMembershipId) {
    }

    private static final List<String> MANUAL_REASONS = List.of("MACHINE_OFF_OUT_OF_OPERATION", "PROCESS_ENDED_BY_ADMINISTRATOR", "OTHER_VERIFIED");

    /**
     * Reconciliacao DECLARADA por uma pessoa (058): so ORG_ADMIN da propria organizacao, com confirmacao reforcada, motivo de uma
     * lista fechada, descricao da verificacao feita (20 a 2000 caracteres) e a afirmacao explicita de que sabe que a execucao
     * anterior pode ter causado efeitos parciais. So para tentativa com resultado nao confirmado e ainda sem declaracao. NAO
     * executa nada, NAO muda o resultado da tentativa e NAO libera a maquina antiga: libera uma nova tentativa da solicitacao,
     * sujeita as mesmas autorizacoes de sempre.
     */
    public Execution reconcileManually(UUID org, ValidSession actor, String token, UUID executionId, UUID attemptId, String reason, String verification,
                                       boolean acknowledgePartialEffects) {
        String text = verification == null ? "" : verification.strip();
        if (reason == null || !MANUAL_REASONS.contains(reason) || text.length() < 20 || text.length() > 2000 || !acknowledgePartialEffects) {
            throw ApiException.invalid("reconciliacao");
        }
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            scoped(j, org, actor, executionId, "automation.read");
            if (!j.sql("SELECT EXISTS (SELECT 1 FROM public.organization_role_assignments a JOIN public.roles r ON r.role_id = a.role_id "
                    + "WHERE a.organization_id = ? AND a.membership_id = ? AND a.status = 1 AND r.code = 'ORG_ADMIN')").param(org).param(actor.membershipId())
                    .query(Boolean.class).single()) {
                throw AccessActions.refuse(Failure.PERMISSION, "so_org_admin");
            }
            actions.requireStepUp(j, actor);
            requireAttempt(j, org, executionId, attemptId);
            j.sql("SELECT 1 FROM public.remote_executions WHERE organization_id = ? AND remote_execution_id = ? FOR UPDATE").param(org).param(executionId)
                    .query(Integer.class).single();
            reconcile(j, org, executionId, null);
            Attempt a = execution(j, org, executionId).attempts().stream().filter(x -> x.attemptId().equals(attemptId)).findFirst().orElseThrow();
            if (!"ENDED_UNKNOWN".equals(a.state()) && a.processes() == null) {
                throw new ApiException(ApiError.REMOTE_ATTEMPT_NOT_UNCONFIRMED, "tentativa_sem_processo_pendente");
            }
            if (a.reconciliations().stream().anyMatch(r -> r.kind().equals("MANUAL"))) {
                throw new ApiException(ApiError.REMOTE_ATTEMPT_ALREADY_RECONCILED, "ja_reconciliada");
            }
            j.sql("INSERT INTO public.remote_attempt_process_reports (attempt_id, kind, organization_id, agent_id, reason, verification, by_membership_id) "
                    + "VALUES (?, 2, ?, ?, ?, ?, ?)").param(attemptId).param(org).param(a.agentId()).param(reason).param(text).param(actor.membershipId()).update();
            LOG.info("evento=execucao_remota.reconciliacao_manual solicitacao={} tentativa={} maquina={} motivo={} correlacao={}", executionId, attemptId,
                    a.agentId(), reason, AccessActions.correlation());
            return execution(j, org, executionId);
        });
    }

    /** O que a maquina informou DEPOIS de o servidor encerrar a tentativa por comunicacao perdida. */
    public record LateReport(String state, Integer exitCode, OffsetDateTime reportedAt) {
    }

    /** {@code state}: o da tentativa mais recente. {@code workspaceName} e {@code automationName}: para as listas por organizacao. */
    public record Execution(UUID remoteExecutionId, UUID workspaceId, UUID automationId, UUID automationVersionId, int versionNumber, String state,
                            int requirementsRevision, List<String> dependencies, OffsetDateTime requestedAt, OffsetDateTime cancelRequestedAt,
                            List<Attempt> attempts, String workspaceName, String automationName) {
    }

    public record Created(Execution execution, boolean created) {
    }

    private final AccessActions actions;
    private final WorkspaceAccess access;
    private final boolean enabled;
    private final int offerMinutes;
    private final int lostMinutes;

    /**
     * A ocupacao da maquina {@code a} NAO esta confirmada: a ultima palavra sobre uma tentativa dela foi o encerramento por falta
     * de comunicacao (053), e desde entao ela nao se comunicou nem disse como o processo terminou. O processo pode estar em
     * andamento la. Deixa de valer no primeiro contato da maquina depois do encerramento (ela mesma recusa o que nao puder
     * receber) ou quando ela relata o desfecho.
     */
    /** 058: a maquina {@code a} AINDA EXECUTA uma tentativa que o servidor ja encerrou como nao confirmada (ela voltou e disse). */
    private static final String STILL_RUNNING = com.nekkora.core.remote.AttemptReconciliation.machineHolds("a", com.nekkora.core.remote.AttemptReconciliation.Hold.RUNNING);
    /** 060: o processo principal de uma tentativa da maquina {@code a} terminou e ha processos da automacao ainda ativos nela. */
    private static final String FINISHING = com.nekkora.core.remote.AttemptReconciliation.machineHolds("a", com.nekkora.core.remote.AttemptReconciliation.Hold.FINISHING);
    /**
     * 058, 060: a ocupacao da maquina {@code a} e INCERTA - ha tentativa dela com processo pendente e ela nao sabe (ou nao disse)
     * se ele acabou. Tempo, novo contato e a declaracao manual nao mudam isso (a declaracao libera uma nova tentativa, nao a maquina).
     */
    private static final String UNCONFIRMED = com.nekkora.core.remote.AttemptReconciliation.machineHolds("a", com.nekkora.core.remote.AttemptReconciliation.Hold.UNCERTAIN);
    private final com.nekkora.core.artifact.ResultStore results;

    /**
     * {@code offerMinutes}: quanto tempo a tentativa espera a maquina receber antes de expirar (padrao de laboratorio: 15).
     * {@code lostMinutes}: quanto tempo, depois de o prazo da comunicacao vencer, a tentativa recebida e encerrada como sem
     * confirmacao (053; padrao 120; o mesmo valor do agent-gateway).
     */
    public RemoteExecutionService(AccessActions actions, WorkspaceAccess access, boolean enabled, int offerMinutes, int lostMinutes,
                                  com.nekkora.core.artifact.ResultStore results) {
        this.lostMinutes = AttemptClosure.lostMinutes(lostMinutes);
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
                           la.authorization_id, la.valid_until, la.revoked_at IS NOT NULL,
                           la.valid_until <= clock_timestamp() AND public.license_model(r.organization_id) <> 2,
                           s.environment_id, s.python_version, s.selected_at,
                           EXISTS (SELECT 1 FROM public.agent_python_environments e WHERE e.agent_id = a.agent_id AND e.environment_id = s.environment_id),
                           %s
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
                                          ORDER BY (z.revoked_at IS NULL AND (z.valid_until > clock_timestamp()
                                                    OR public.license_model(z.organization_id) = 2)) DESC, z.granted_at DESC LIMIT 1) la ON true
                      LEFT JOIN public.automation_python_selections s ON s.organization_id = r.organization_id AND s.automation_id = ?
                                                                      AND s.agent_id = a.agent_id
                     WHERE r.organization_id = ? ORDER BY w.name, w.worker_id
                    """.formatted(UNCONFIRMED + ", " + STILL_RUNNING + ", " + FINISHING + ", coalesce(p.remote_busy, false), coalesce(p.disk_pressure, false)")).param(workspaceId).param(workspaceId).param(versionId).param(v.automationId()).param(org).query((rs, n) -> {
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
                // sem a confirmacao da maquina vale o mais grave: incerta sobre finalizando sobre ainda executa
                if (rs.getBoolean(7)) {
                    missing.add("MACHINE_BUSY");
                } else if (rs.getBoolean(16)) {
                    missing.add("MACHINE_UNCONFIRMED");
                } else if (rs.getBoolean(18)) {
                    missing.add("MACHINE_FINISHING");
                } else if (rs.getBoolean(17)) {
                    missing.add("MACHINE_BUSY");
                } else if (rs.getBoolean(19)) {
                    // a propria maquina informou, na presenca, que esta ocupada - sem dizer com o que (pode ser uma execucao
                    // iniciada nela pela pessoa: a Platform nao recebe nada dessa execucao, so este sim/nao)
                    missing.add("MACHINE_OCCUPIED");
                }
                if (rs.getBoolean(20)) {
                    missing.add("MACHINE_DISK_PRESSURE");
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
                        List.copyOf(missing), info, python, null);
            }).list().stream().map(c -> new Candidate(c.agentId(), c.agentName(), c.workerId(), c.workerName(), c.requestable(), c.missing(),
                    c.authorization(), c.python(), latestCheck(j, org, v.automationId(), c.agentId()))).toList();
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
        return request(org, actor, token, workspaceId, versionId, agentId, requestKey, null);
    }

    /**
     * S2: o aceite EXPLICITO da versao nesta maquina, dado no mesmo passo da solicitacao por quem tem as duas permissoes (as
     * duas sao automation.execute no ambiente, com a mesma confirmacao reforcada). So cria a autorizacao da versao para o
     * Worker da maquina se nao houver uma que sirva; o ato fica na trilha como qualquer autorizacao (ato 10, com quem aceitou).
     * Nunca vale para versoes futuras. {@code validUntil}: obrigatorio no modelo anterior; no modelo por licenca, limitado ao
     * fim da cobertura.
     */
    public record VersionAcceptance(OffsetDateTime validUntil) {
    }

    public Created request(UUID org, ValidSession actor, String token, UUID workspaceId, UUID versionId, UUID agentId, UUID requestKey,
                           VersionAcceptance acceptance) {
        if (versionId == null || agentId == null || requestKey == null) {
            throw ApiException.invalid("solicitacao");
        }
        if (!enabled) {
            throw new ApiException(ApiError.REMOTE_EXECUTION_UNAVAILABLE, "execucao_remota_desligada");
        }
        try {
            return requestOnce(org, actor, token, workspaceId, versionId, agentId, requestKey, acceptance);
        } catch (DuplicateKeyException race) {
            // dois envios concorrentes com a mesma chave: o segundo le o primeiro
            return requestOnce(org, actor, token, workspaceId, versionId, agentId, requestKey, acceptance);
        }
    }

    private Created requestOnce(UUID org, ValidSession actor, String token, UUID workspaceId, UUID versionId, UUID agentId, UUID requestKey,
                                VersionAcceptance acceptance) {
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
            // 064: o direito ao servico. No modelo por licenca, a automacao precisa de licenca vigente para trabalho novo
            LicenseGate.requireForNewWork(j, org, v.automationId());
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
            Target target = target(j, org, workspaceId, versionId, agentId, actor.membershipId(), acceptance);
            requireCapacity(j, org);
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
        return retry(org, actor, token, executionId, agentId, null);
    }

    public Execution retry(UUID org, ValidSession actor, String token, UUID executionId, UUID agentId, VersionAcceptance acceptance) {
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
            reconcile(j, org, executionId, null);
            x = execution(j, org, executionId);
            if (x.cancelRequestedAt() != null) {
                throw new ApiException(ApiError.REMOTE_EXECUTION_CANCEL_REQUESTED, "cancelamento_pedido");
            }
            Attempt last = x.attempts().get(x.attempts().size() - 1);
            if (STATES.indexOf(last.state()) < 6) {
                throw new ApiException(ApiError.REMOTE_ATTEMPT_NOT_FINISHED, "tentativa_anterior_nao_terminou");
            }
            // 064: uma tentativa nova e trabalho novo - a licenca e conferida de novo, agora
            LicenseGate.requireForNewWork(j, org, x.automationId());
            // 058: com resultado nao confirmado, a repeticao fica BLOQUEADA ate haver confirmacao de que o processo acabou - pela
            // maquina, ou declarada por um ORG_ADMIN. Reconhecer o risco, revogar a maquina ou esperar nao liberam nada
            if ("ENDED_UNKNOWN".equals(last.state()) && last.reconciliations().isEmpty()) {
                throw new ApiException(ApiError.REMOTE_PREVIOUS_RESULT_UNCONFIRMED, "resultado_anterior_nao_reconciliado");
            }
            // 060: o mesmo quando a tentativa terminou mas os processos dela nao foram confirmados ausentes
            if (last.unresolved()) {
                throw new ApiException(ApiError.REMOTE_PREVIOUS_PROCESSES_PENDING, "processos_da_tentativa_anterior_pendentes");
            }
            // a mesma recusa da solicitacao: uma solicitacao antiga de pacote que nao e externo nao ganha nova tentativa
            requireSupported(j.sql("SELECT package_fingerprint_rule FROM public.remote_executions WHERE organization_id = ? AND remote_execution_id = ?")
                    .param(org).param(executionId).query(String.class).single());
            requireRegistered(j, org, x.automationVersionId(), j.sql("SELECT package_fingerprint FROM public.remote_executions "
                    + "WHERE organization_id = ? AND remote_execution_id = ?").param(org).param(executionId).query(String.class).single(), SUPPORTED_RULE);
            Target target = target(j, org, x.workspaceId(), x.automationVersionId(), agentId, actor.membershipId(), acceptance);
            requireCapacity(j, org);
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
            reconcile(j, org, executionId, null);
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
        boolean stale = actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            scoped(j, org, actor, executionId, "automation.read");
            return AttemptClosure.pending(j, org, new UUID[] {executionId}, lostMinutes);
        });
        if (stale) {
            settle(org, new UUID[] {executionId});
        }
        return actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            return scoped(j, org, actor, executionId, "automation.read");
        });
    }

    /**
     * 053: a leitura nunca mostra "em execucao" uma tentativa que ja nao tem quem a conclua. Quando a consulta acha alguma
     * (maquina desvinculada ou sem comunicacao alem da espera), ela e encerrada aqui, numa transacao propria de escrita, pelas
     * mesmas regras do canal das maquinas; a consulta e refeita em seguida. Nao depende de quem le: o banco confere o fato.
     */
    private void settle(UUID org, UUID[] executions) {
        actions.write(org, j -> {
            for (UUID id : executions) {
                reconcile(j, org, id, null);
            }
            return null;
        });
    }

    /** As solicitacoes mais recentes do ambiente (ate {@code limit}, 1 a 100). */
    public List<Execution> list(UUID org, ValidSession actor, String token, UUID workspaceId, int limit) {
        UUID[] stale = actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            access.requireWorkspace(j, org, actor.membershipId(), workspaceId, "automation.read");
            UUID[] ids = j.sql("SELECT remote_execution_id FROM public.remote_executions WHERE organization_id = ? AND workspace_id = ? "
                    + "ORDER BY requested_at DESC, remote_execution_id DESC LIMIT ?").param(org).param(workspaceId).param(limit).query(UUID.class).list()
                    .toArray(UUID[]::new);
            return ids.length > 0 && AttemptClosure.pending(j, org, ids, lostMinutes) ? ids : null;
        });
        if (stale != null) {
            settle(org, stale);
        }
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

    /**
     * As solicitacoes mais recentes dos ambientes VISIVEIS para a pessoa (automation.read, pela regra unica de
     * {@link WorkspaceAccess}), da mais recente para a mais antiga, ate {@code limit}. Filtros opcionais: um ambiente (sem
     * acesso a ele: lista vazia, nunca a existencia), uma automacao e o periodo da solicitacao ({@code from} inclusivo,
     * {@code to} exclusivo). E a MESMA solicitacao das outras leituras, com as suas tentativas: nada e copiado.
     */
    public List<Execution> listVisible(UUID org, ValidSession actor, String token, UUID workspaceId, UUID automationId, OffsetDateTime from,
                                       OffsetDateTime to, int limit) {
        String sql = "SELECT x.remote_execution_id FROM public.remote_executions x JOIN public.workspaces w ON w.organization_id = x.organization_id "
                + "AND w.workspace_id = x.workspace_id WHERE " + WorkspaceAccess.VISIBLE_WORKSPACE_CONDITION
                + (workspaceId == null ? "" : " AND x.workspace_id = :workspace") + (automationId == null ? "" : " AND x.automation_id = :automation")
                + (from == null ? "" : " AND x.requested_at >= :from") + (to == null ? "" : " AND x.requested_at < :to")
                + " ORDER BY x.requested_at DESC, x.remote_execution_id DESC LIMIT :max";
        java.util.function.Function<JdbcClient, List<UUID>> visible = j -> {
            JdbcClient.StatementSpec q = access.visibleWorkspacesQuery(j, sql, org, actor.membershipId(), "automation.read").param("max", limit);
            if (workspaceId != null) {
                q = q.param("workspace", workspaceId);
            }
            if (automationId != null) {
                q = q.param("automation", automationId);
            }
            if (from != null) {
                q = q.param("from", from);
            }
            if (to != null) {
                q = q.param("to", to);
            }
            return q.query(UUID.class).list();
        };
        UUID[] stale = actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            UUID[] ids = visible.apply(j).toArray(UUID[]::new);
            return ids.length > 0 && AttemptClosure.pending(j, org, ids, lostMinutes) ? ids : null;
        });
        if (stale != null) {
            settle(org, stale);
        }
        return actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            List<Execution> out = new ArrayList<>();
            for (UUID id : visible.apply(j)) {
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

    /**
     * 057: o conteudo desta tentativa venceu pela retencao do SERVIDOR. {@code deliveryEnd}: como a entrega tinha terminado
     * (CLOSED, LOSS_NOTICE ou LINK_ENDED). {@code confirmedAt} nulo: a remocao ainda esta em andamento. {@code items}: quanto havia.
     */
    public record ContentExpiration(OffsetDateTime expiredAt, OffsetDateTime confirmedAt, int retentionDays, OffsetDateTime deliveryEndedAt,
                                    String deliveryEnd, int items) {
    }

    private static final List<String> DELIVERY_ENDS = List.of("CLOSED", "LOSS_NOTICE", "LINK_ENDED", "NO_PROGRESS");

    private static ContentExpiration expiration(JdbcClient j, UUID org, UUID attemptId, int kind) {
        return j.sql("SELECT expired_at, confirmed_at, retention_days, delivery_ended_at, delivery_end, items FROM public.remote_attempt_content_expirations "
                        + "WHERE organization_id = ? AND attempt_id = ? AND kind = ?").param(org).param(attemptId).param((short) kind)
                .query((rs, n) -> new ContentExpiration(rs.getObject(1, OffsetDateTime.class), rs.getObject(2, OffsetDateTime.class), rs.getInt(3),
                        rs.getObject(4, OffsetDateTime.class), DELIVERY_ENDS.get(rs.getShort(5) - 1), rs.getInt(6))).optional().orElse(null);
    }

    public record Results(UUID attemptId, List<ResultFile> items, ResultsClosure closure, RetentionLoss retentionLoss, ContentExpiration expiration) {
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
    /**
     * {@code total} = {@code automationLines} + {@code systemLines}: o que o processo da automacao escreveu (STDOUT e STDERR) e os
     * avisos do proprio aplicativo da maquina (EXECUTOR), que ocupam lugar no limite mas nao sao saida da automacao.
     */
    public record Logs(UUID attemptId, List<LogLine> items, int total, int automationLines, int systemLines, Integer nextAfter, LogsClosure closure,
                       RetentionLoss retentionLoss, ContentExpiration expiration) {
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
            int[] counts = j.sql("SELECT count(*), count(*) FILTER (WHERE channel = 3) FROM public.remote_attempt_log_lines WHERE organization_id = ? "
                    + "AND attempt_id = ?").param(org).param(attemptId).query((rs, n) -> new int[] {rs.getInt(1), rs.getInt(2)}).single();
            int total = counts[0];
            LogsClosure closure = j.sql("SELECT lines, dropped_lines, closed_at FROM public.remote_attempt_log_closures WHERE organization_id = ? "
                            + "AND attempt_id = ?").param(org).param(attemptId)
                    .query((rs, n) -> new LogsClosure(rs.getInt(1), rs.getLong(2), rs.getObject(3, OffsetDateTime.class))).optional().orElse(null);
            Integer next = items.size() == page && items.get(items.size() - 1).sequence() < total ? items.get(items.size() - 1).sequence() : null;
            return new Logs(attemptId, items, total, total - counts[1], counts[1], next, closure, retentionLoss(j, org, attemptId, 1),
                    expiration(j, org, attemptId, 1));
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
            return new Results(attemptId, items, closure, retentionLoss(j, org, attemptId, 2), expiration(j, org, attemptId, 2));
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
        // 064: o direito ao servico vem antes do resto - sem licenca vigente, nada mais adianta resolver
        String license = LicenseGate.state(j, org, v.automationId());
        if (license.startsWith("LICENSE_")) {
            out.add(license);
        }
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
    private Target target(JdbcClient j, UUID org, UUID workspaceId, UUID versionId, UUID agentId, UUID membership, VersionAcceptance acceptance) {
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
        reconcile(j, org, null, agentId);
        if (j.sql("SELECT count(*) FROM public.remote_attempts WHERE organization_id = ? AND agent_id = ? AND state BETWEEN 1 AND 6").param(org).param(agentId)
                .query(Long.class).single() > 0) {
            throw new ApiException(ApiError.REMOTE_MACHINE_BUSY, "maquina_ocupada");
        }
        // 058: resultado nao confirmado NAO e saber que a maquina esta livre. Se ela disse que ainda executa: ocupada. Se nao
        // confirmou que o processo acabou: ocupacao incerta. Um novo contato nao muda nenhuma das duas
        if (j.sql("SELECT " + UNCONFIRMED + " FROM public.agents a WHERE a.organization_id = ? AND a.agent_id = ?").param(org).param(agentId)
                .query(Boolean.class).single()) {
            throw new ApiException(ApiError.REMOTE_MACHINE_UNCONFIRMED, "maquina_sem_confirmacao");
        }
        if (j.sql("SELECT " + FINISHING + " FROM public.agents a WHERE a.organization_id = ? AND a.agent_id = ?").param(org).param(agentId)
                .query(Boolean.class).single()) {
            throw new ApiException(ApiError.REMOTE_MACHINE_FINISHING, "maquina_finalizando");
        }
        if (j.sql("SELECT " + STILL_RUNNING + " FROM public.agents a WHERE a.organization_id = ? AND a.agent_id = ?").param(org).param(agentId)
                .query(Boolean.class).single()) {
            throw new ApiException(ApiError.REMOTE_MACHINE_BUSY, "maquina_ainda_executa");
        }
        record Grant(UUID id, boolean revoked, boolean expired) {
        }
        // 064, S1: no modelo por licenca o prazo da autorizacao nao recusa a execucao remota (o prazo e o da licenca); a
        // autorizacao da versao para o Worker continua exigida e a revogacao continua valendo
        Grant g = j.sql("""
                SELECT z.authorization_id, z.revoked_at IS NOT NULL,
                       z.valid_until <= clock_timestamp() AND public.license_model(z.organization_id) <> 2
                  FROM public.local_run_authorizations z
                 WHERE z.organization_id = ? AND z.workspace_id = ? AND z.worker_id = ? AND z.automation_version_id = ? AND z.source_kind IN (1, 2)
                 ORDER BY (z.revoked_at IS NULL AND (z.valid_until > clock_timestamp() OR public.license_model(z.organization_id) = 2)) DESC,
                          z.granted_at DESC LIMIT 1
                """).param(org).param(workspaceId).param(m.worker()).param(versionId)
                .query((rs, n) -> new Grant(rs.getObject(1, UUID.class), rs.getBoolean(2), rs.getBoolean(3))).optional().orElse(null);
        if (acceptance != null && (g == null || g.revoked() || g.expired())) {
            // S2: aceite explicito da versao nesta maquina, no mesmo passo. E o MESMO ato da rota de autorizacao (mesmas
            // recusas, mesma trilha), feito por quem ja provou a permissao e a confirmacao reforcada nesta transacao
            AgentAdminService.LocalAuthorization accepted = AgentAdminService.grantVersion(j, org, membership, workspaceId, m.worker(), versionId,
                    acceptance.validUntil());
            LOG.info("evento=execucao_remota.versao_aceita_na_solicitacao autorizacao={} correlacao={}", accepted.authorizationId(),
                    AccessActions.correlation());
            g = new Grant(accepted.authorizationId(), false, false);
        }
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

    /**
     * 066, D2: no modelo por licenca, a tentativa nova entra na FILA da organizacao, que tem limite tecnico de pendentes. Fila
     * cheia e recusa de capacidade, com motivo proprio - nunca de licenca, e nao consome nem libera licenca. O limite de
     * execucoes SIMULTANEAS nao recusa a solicitacao: a vaga e reservada na entrega a maquina, e sem vaga a tentativa espera
     * na fila. O gatilho da 066 reconfere na gravacao. Modelo anterior: limite nulo, nada conferido aqui.
     */
    private static void requireCapacity(JdbcClient j, UUID org) {
        com.nekkora.core.license.Capacity.requireRoomInQueue(j, org);
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

    /**
     * O que nao tem mais quem conclua deixa de ocupar a vaga, sem inventar desfecho. QUEUED/OFFERED vencidos viram EXPIRED;
     * tentativa de maquina que nao esta mais vinculada, ou recebida e sem comunicacao alem da espera (053): "nao iniciou" se o
     * servidor nunca autorizou o inicio, senao "sem confirmacao". Da solicitacao, ou da maquina.
     */
    private void reconcile(JdbcClient j, UUID org, UUID executionId, UUID agentId) {
        j.sql("UPDATE public.remote_attempts SET state = 12 WHERE organization_id = ? AND state IN (1, 2) AND offer_valid_until <= clock_timestamp() "
                        + "AND (remote_execution_id = ? OR agent_id = ?)").param(org).param(executionId).param(agentId).update();
        int orphans = AttemptClosure.orphans(j, org, executionId, agentId);
        int lost = AttemptClosure.lost(j, org, executionId, agentId, null, lostMinutes);
        if (orphans + lost > 0) {
            LOG.info("evento=execucao_remota.encerrada_pelo_servidor maquina_desvinculada={} sem_comunicacao={} correlacao={}", orphans, lost,
                    AccessActions.correlation());
        }
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
                       t.exit_code, t.created_at, t.offer_valid_until, t.offered_at, t.received_at, t.started_at, t.ended_at,
                       t.closure_cause, t.late_state, t.late_exit_code, t.late_reported_at
                  FROM public.remote_attempts t
                  JOIN public.agents a ON a.organization_id = t.organization_id AND a.agent_id = t.agent_id
                  JOIN public.workers w ON w.organization_id = t.organization_id AND w.worker_id = t.worker_id
                 WHERE t.organization_id = ? AND t.remote_execution_id = ? ORDER BY t.attempt_number
                """).param(org).param(id).query((rs, n) -> new Attempt(rs.getObject(1, UUID.class), rs.getInt(2), rs.getObject(3, UUID.class), rs.getString(4),
                rs.getObject(5, UUID.class), rs.getString(6), STATES.get(rs.getShort(7) - 1), rs.getString(8), rs.getString(9),
                (Integer) rs.getObject(10), rs.getObject(11, OffsetDateTime.class), rs.getObject(12, OffsetDateTime.class),
                rs.getObject(13, OffsetDateTime.class), rs.getObject(14, OffsetDateTime.class), rs.getObject(15, OffsetDateTime.class),
                rs.getObject(16, OffsetDateTime.class), rs.getString(17),
                rs.getObject(18) == null ? null : new LateReport(STATES.get(rs.getShort(18) - 1), (Integer) rs.getObject(19),
                        rs.getObject(20, OffsetDateTime.class)), new ArrayList<>(), null, null, null)).list();
        // 058: o que se sabe do processo de cada tentativa nao confirmada (reconciliacoes e "ainda executa")
        List<Attempt> known = new ArrayList<>();
        for (Attempt a : attempts) {
            List<Reconciliation> recs = new ArrayList<>();
            // 3 = ainda executa; 4 = finalizando; 5 = a maquina nao sabe
            OffsetDateTime[] said = new OffsetDateTime[6];
            if (STATES.indexOf(a.state()) >= 6) {
                j.sql("SELECT kind, reported_at, reason, verification, by_membership_id FROM public.remote_attempt_process_reports "
                        + "WHERE organization_id = ? AND attempt_id = ? ORDER BY reported_at, kind").param(org).param(a.attemptId()).query(rs -> {
                            short kind = rs.getShort(1);
                            if (kind >= 3 && kind <= 5) {
                                said[kind] = rs.getObject(2, OffsetDateTime.class);
                            } else {
                                recs.add(new Reconciliation(kind == 1 ? "MACHINE" : "MANUAL", rs.getObject(2, OffsetDateTime.class), rs.getString(3),
                                        rs.getString(4), rs.getObject(5, UUID.class)));
                            }
                        });
            }
            // o que a maquina disse deixa de valer quando ELA confirma que nao ha mais processo. Sem isso vale o mais grave:
            // "nao sei" (5) sobre "finalizando" (4) sobre "ainda executa" (3)
            boolean byMachine = recs.stream().anyMatch(r -> r.kind().equals("MACHINE"));
            String processes = byMachine ? null : said[5] != null ? "UNCERTAIN" : said[4] != null ? "ACTIVE" : null;
            OffsetDateTime processesAt = processes == null ? null : said[5] != null ? said[5] : said[4];
            known.add(new Attempt(a.attemptId(), a.attemptNumber(), a.agentId(), a.agentName(), a.workerId(), a.workerName(), a.state(), a.refusalReason(),
                    a.communication(), a.exitCode(), a.createdAt(), a.offerValidUntil(), a.offeredAt(), a.receivedAt(), a.startedAt(), a.endedAt(),
                    a.closureCause(), a.lateReport(), List.copyOf(recs), byMachine || processes != null ? null : said[3], processes, processesAt));
        }
        List<Attempt> resolved = List.copyOf(known);
        return j.sql("""
                SELECT x.remote_execution_id, x.workspace_id, x.automation_id, x.automation_version_id, v.version_number, x.requirements_revision,
                       x.effective_dependencies::text, x.requested_at, x.cancel_requested_at, ws.name, au.name
                  FROM public.remote_executions x
                  JOIN public.automation_versions v ON v.organization_id = x.organization_id AND v.automation_version_id = x.automation_version_id
                  JOIN public.workspaces ws ON ws.organization_id = x.organization_id AND ws.workspace_id = x.workspace_id
                  JOIN public.automations au ON au.organization_id = x.organization_id AND au.automation_id = x.automation_id
                 WHERE x.organization_id = ? AND x.remote_execution_id = ?
                """).param(org).param(id).query((rs, n) -> {
            List<String> deps = new ArrayList<>();
            JSON.readTree(rs.getString(7)).forEach(d -> deps.add(d.asString()));
            return new Execution(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                    rs.getInt(5), attempts.isEmpty() ? "QUEUED" : attempts.get(attempts.size() - 1).state(), rs.getInt(6), List.copyOf(deps),
                    rs.getObject(8, OffsetDateTime.class), rs.getObject(9, OffsetDateTime.class), resolved, rs.getString(10), rs.getString(11));
        }).single();
    }
}
