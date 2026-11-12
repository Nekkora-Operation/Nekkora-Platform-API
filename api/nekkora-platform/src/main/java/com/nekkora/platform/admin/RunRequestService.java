package com.nekkora.platform.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContext;
import com.nekkora.platform.access.WorkspaceAccess;
import com.nekkora.platform.admin.AccessActions.Failure;
import com.nekkora.platform.session.ValidSession;

/**
 * Pedido de execucao (AGENT-PYTHON-001, 030): uma pessoa pede que UMA versao autorizada rode em UM Agent. O pedido e duravel
 * e auditavel; a maquina o puxa pelo gateway, decide e registra a execucao. A Platform nao envia comando, caminho, argumento
 * nem segredo: so identificadores. Entregar ou aceitar nao prova inicio; inicio e desfecho vem do supervisor da execucao
 * ligada ({@code agent_runs.run_request_id}).
 * <p>
 * Estados: REQUESTED, DELIVERED, ACCEPTED, REFUSED, NOT_STARTED, RUN_LINKED, EXPIRED, REVOKED. Um pedido REQUESTED ou
 * DELIVERED com o prazo vencido e lido como EXPIRED (o gateway grava o vencimento quando a maquina volta). Maquina
 * desconectada: o pedido continua aguardando ate ela voltar ou o prazo vencer; nunca "em execucao" por inferencia.
 */
public final class RunRequestService {

    private static final Logger LOG = LoggerFactory.getLogger(RunRequestService.class);
    static final String[] STATES = {null, "REQUESTED", "DELIVERED", "ACCEPTED", "REFUSED", "NOT_STARTED", "RUN_LINKED", "EXPIRED", "REVOKED"};
    static final String[] RUN_STATES = {null, "READY", "STARTING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "BLOCKED", "OUTCOME_UNKNOWN"};
    static final int MIN_SECONDS = 60;
    static final int MAX_SECONDS = 24 * 3600;
    static final int DEFAULT_SECONDS = 15 * 60;
    static final int CONNECTED_SECONDS = 180;

    private final AccessActions actions;
    private final WorkspaceAccess access;

    public RunRequestService(AccessActions actions, WorkspaceAccess access) {
        this.actions = actions;
        this.access = access;
    }

    private record Grant(UUID automationId, UUID versionId, UUID workerId, short sourceKind, String fingerprint, String rule, boolean active) {
    }

    /**
     * Cria o pedido. Exige automation.execute no workspace + step-up; autorizacao ATIVA de pacote (nao de monitoramento); Agent
     * ativo associado ao Worker da autorizacao. {@code requestKey} (UUID gerado pelo navegador) torna a repeticao idempotente:
     * a mesma chave devolve o mesmo pedido, sem segundo ato.
     */
    public Map<String, Object> create(UUID org, ValidSession actor, String token, UUID workspaceId, UUID authorizationId, UUID agentId,
                                      Integer validForSeconds, UUID requestKey) {
        int seconds = validForSeconds == null ? DEFAULT_SECONDS : validForSeconds;
        if (authorizationId == null || agentId == null || requestKey == null || seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
            throw ApiException.invalid("pedido");
        }
        try {
            return createOnce(org, actor, token, workspaceId, authorizationId, agentId, seconds, requestKey);
        } catch (DuplicateKeyException race) {
            // dois envios concorrentes com a mesma chave: o segundo le o primeiro
            return createOnce(org, actor, token, workspaceId, authorizationId, agentId, seconds, requestKey);
        }
    }

    private Map<String, Object> createOnce(UUID org, ValidSession actor, String token, UUID workspaceId, UUID authorizationId, UUID agentId, int seconds,
                                           UUID requestKey) {
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            access.requireWorkspace(j, org, actor.membershipId(), workspaceId, "automation.execute");
            actions.requireStepUp(j, actor);
            actions.lockOrganizationAndActor(j, org, actor.userId());
            UUID existing = j.sql("SELECT run_request_id FROM public.run_requests WHERE organization_id = ? AND request_key = ?").param(org).param(requestKey)
                    .query(UUID.class).optional().orElse(null);
            if (existing != null) {
                Map<String, Object> same = view(j, org, existing);
                if (!workspaceId.equals(same.get("workspaceId")) || !authorizationId.equals(same.get("authorizationId")) || !agentId.equals(same.get("agentId"))) {
                    throw AccessActions.refuse(Failure.CONFLICT, "chave_do_pedido_com_outro_conteudo");
                }
                same.put("created", false);
                return same;
            }
            Grant g = j.sql("""
                    SELECT z.automation_id, z.automation_version_id, z.worker_id, z.source_kind, z.package_fingerprint, z.package_fingerprint_rule,
                           z.revoked_at IS NULL AND z.valid_until > clock_timestamp() AND x.status = 1
                      FROM public.local_run_authorizations z
                      JOIN public.worker_workspace_assignments x ON x.organization_id = z.organization_id AND x.assignment_id = z.worker_workspace_assignment_id
                     WHERE z.organization_id = ? AND z.workspace_id = ? AND z.authorization_id = ?
                    """).param(org).param(workspaceId).param(authorizationId)
                    .query((rs, n) -> new Grant(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getShort(4),
                            rs.getString(5), rs.getString(6), rs.getBoolean(7)))
                    .optional().orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "autorizacao"));
            if (g.sourceKind() == 3) {
                throw AccessActions.refuse(Failure.CONFLICT, "monitoramento_nao_se_dispara_pela_platform");
            }
            if (!g.active()) {
                throw AccessActions.refuse(Failure.CONFLICT, "autorizacao_inativa");
            }
            Boolean agentOk = j.sql("""
                    SELECT g.status = 2 FROM public.worker_agent_assignments a JOIN public.agents g ON g.organization_id = a.organization_id AND g.agent_id = a.agent_id
                     WHERE a.organization_id = ? AND a.worker_id = ? AND a.agent_id = ? AND a.status = 1
                    """).param(org).param(g.workerId()).param(agentId).query(Boolean.class).optional().orElse(null);
            if (agentOk == null) {
                throw AccessActions.refuse(Failure.CONFLICT, "agent_nao_associado_ao_worker");
            }
            if (!agentOk) {
                throw AccessActions.refuse(Failure.CONFLICT, "agent_inativo");
            }
            if (!Boolean.TRUE.equals(j.sql("SELECT a.status = 1 AND w.status = 1 FROM public.automations a JOIN public.workspaces w "
                    + "ON w.organization_id = a.organization_id AND w.workspace_id = a.workspace_id WHERE a.organization_id = ? AND a.automation_id = ?")
                    .param(org).param(g.automationId()).query(Boolean.class).optional().orElse(false))) {
                throw AccessActions.refuse(Failure.CONFLICT, "automacao_ou_workspace_inativo");
            }
            UUID id = j.sql("""
                    INSERT INTO public.run_requests (organization_id, workspace_id, automation_id, automation_version_id, authorization_id, worker_id, agent_id,
                        source_kind, package_fingerprint, package_fingerprint_rule, request_key, requested_by_membership_id, valid_until)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp() + make_interval(secs => ?)) RETURNING run_request_id
                    """).param(org).param(workspaceId).param(g.automationId()).param(g.versionId()).param(authorizationId).param(g.workerId()).param(agentId)
                    .param(g.sourceKind()).param(g.fingerprint()).param(g.rule()).param(requestKey).param(actor.membershipId()).param(seconds)
                    .query(UUID.class).single();
            audit(j, org, 12, actor.membershipId(), agentId, g.workerId(), workspaceId, authorizationId, id);
            LOG.info("evento=pedido_execucao.criado pedido={} correlacao={}", id, RequestContext.correlation());
            Map<String, Object> v = view(j, org, id);
            v.put("created", true);
            return v;
        });
    }

    /** Revogacao: so antes da decisao da maquina (REQUESTED/DELIVERED no prazo). Depois dela, 409: cancelar fica fora. */
    public Map<String, Object> revoke(UUID org, ValidSession actor, String token, UUID runRequestId) {
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            UUID ws = workspaceOf(j, org, runRequestId);
            access.requireWorkspace(j, org, actor.membershipId(), ws, "automation.execute");
            actions.requireStepUp(j, actor);
            int n = j.sql("UPDATE public.run_requests SET state = 8, revoked_by_membership_id = ? WHERE organization_id = ? AND run_request_id = ? "
                    + "AND state IN (1, 2) AND valid_until > clock_timestamp()").param(actor.membershipId()).param(org).param(runRequestId).update();
            if (n == 0) {
                throw AccessActions.refuse(Failure.CONFLICT, "pedido_ja_decidido_ou_vencido");
            }
            Map<String, Object> v = view(j, org, runRequestId);
            audit(j, org, 13, actor.membershipId(), (UUID) v.get("agentId"), (UUID) v.get("workerId"), ws, (UUID) v.get("authorizationId"), runRequestId);
            return v;
        });
    }

    public Map<String, Object> get(UUID org, ValidSession actor, String token, UUID runRequestId) {
        return actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            UUID ws = workspaceOf(j, org, runRequestId);
            access.requireWorkspace(j, org, actor.membershipId(), ws, "automation.read");
            return view(j, org, runRequestId);
        });
    }

    /** Os pedidos mais recentes do workspace (ate {@code limit}, 1 a 100). */
    public List<Map<String, Object>> list(UUID org, ValidSession actor, String token, UUID workspaceId, int limit) {
        return actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            access.requireWorkspace(j, org, actor.membershipId(), workspaceId, "automation.read");
            return j.sql(VIEW + " WHERE q.organization_id = ? AND q.workspace_id = ? ORDER BY q.requested_at DESC, q.run_request_id DESC LIMIT ?")
                    .param(CONNECTED_SECONDS).param(org).param(workspaceId).param(limit).query((rs, n) -> row(rs)).list();
        });
    }

    private static UUID workspaceOf(JdbcClient j, UUID org, UUID runRequestId) {
        return j.sql("SELECT workspace_id FROM public.run_requests WHERE organization_id = ? AND run_request_id = ?").param(org).param(runRequestId)
                .query(UUID.class).optional().orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "pedido"));
    }

    static final String VIEW = """
            SELECT q.run_request_id, q.workspace_id, q.automation_id, q.automation_version_id, v.version_number, au.name AS automation_name,
                   q.authorization_id, q.worker_id, q.agent_id, g.name AS agent_name, q.source_kind, q.package_fingerprint, q.package_fingerprint_rule,
                   q.requested_at, q.valid_until, q.state, q.delivered_at, q.deliveries, q.decided_at, q.decision_reason, q.agent_execution_id,
                   q.not_started_at, q.linked_at, q.agent_run_id, q.expired_at, q.revoked_at, clock_timestamp() >= q.valid_until AS past_due,
                   g.last_heartbeat_at, coalesce(g.status = 2 AND g.last_heartbeat_at > clock_timestamp() - make_interval(secs => ?), false) AS connected,
                   r.supervisor_state, r.supervisor_code, r.exit_code, r.agent_started_at, r.agent_ended_at, r.registered_at AS run_registered_at
              FROM public.run_requests q
              JOIN public.automation_versions v ON v.organization_id = q.organization_id AND v.automation_version_id = q.automation_version_id
              JOIN public.automations au ON au.organization_id = q.organization_id AND au.automation_id = q.automation_id
              JOIN public.agents g ON g.organization_id = q.organization_id AND g.agent_id = q.agent_id
              LEFT JOIN public.agent_runs r ON r.organization_id = q.organization_id AND r.agent_run_id = q.agent_run_id
            """;

    private static Map<String, Object> view(JdbcClient j, UUID org, UUID runRequestId) {
        return j.sql(VIEW + " WHERE q.organization_id = ? AND q.run_request_id = ?").param(CONNECTED_SECONDS).param(org).param(runRequestId)
                .query((rs, n) -> row(rs)).single();
    }

    private static Map<String, Object> row(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        short stored = rs.getShort("state");
        boolean pastDue = rs.getBoolean("past_due");
        // REQUESTED/DELIVERED vencido e lido como EXPIRED (o gateway grava quando a maquina volta)
        String state = (stored == 1 || stored == 2) && pastDue ? "EXPIRED" : STATES[stored];
        m.put("runRequestId", rs.getObject("run_request_id", UUID.class));
        m.put("workspaceId", rs.getObject("workspace_id", UUID.class));
        m.put("automationId", rs.getObject("automation_id", UUID.class));
        m.put("automationName", rs.getString("automation_name"));
        m.put("automationVersionId", rs.getObject("automation_version_id", UUID.class));
        m.put("versionNumber", rs.getInt("version_number"));
        m.put("authorizationId", rs.getObject("authorization_id", UUID.class));
        m.put("workerId", rs.getObject("worker_id", UUID.class));
        m.put("agentId", rs.getObject("agent_id", UUID.class));
        m.put("agentName", rs.getString("agent_name"));
        m.put("sourceKind", AgentAdminService.sourceKind(rs.getShort("source_kind")));
        m.put("packageFingerprint", rs.getString("package_fingerprint"));
        m.put("packageFingerprintRule", rs.getString("package_fingerprint_rule"));
        m.put("requestedAt", rs.getObject("requested_at", OffsetDateTime.class));
        m.put("validUntil", rs.getObject("valid_until", OffsetDateTime.class));
        m.put("state", state);
        m.put("deliveredAt", rs.getObject("delivered_at", OffsetDateTime.class));
        m.put("deliveries", rs.getInt("deliveries"));
        m.put("decidedAt", rs.getObject("decided_at", OffsetDateTime.class));
        m.put("decisionReason", rs.getString("decision_reason"));
        m.put("agentExecutionId", rs.getString("agent_execution_id"));
        m.put("notStartedAt", rs.getObject("not_started_at", OffsetDateTime.class));
        m.put("linkedAt", rs.getObject("linked_at", OffsetDateTime.class));
        m.put("expiredAt", rs.getObject("expired_at", OffsetDateTime.class));
        m.put("revokedAt", rs.getObject("revoked_at", OffsetDateTime.class));
        Map<String, Object> machine = new LinkedHashMap<>();
        machine.put("connectivity", rs.getBoolean("connected") ? "CONNECTED" : "DISCONNECTED");
        machine.put("lastHeartbeatAt", rs.getObject("last_heartbeat_at", OffsetDateTime.class));
        m.put("machine", machine);
        UUID runId = rs.getObject("agent_run_id", UUID.class);
        if (runId == null) {
            m.put("run", null);
        } else {
            short s = rs.getShort("supervisor_state");
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("runId", runId);
            run.put("registeredAt", rs.getObject("run_registered_at", OffsetDateTime.class));
            run.put("state", RUN_STATES[s]);
            run.put("code", rs.getString("supervisor_code"));
            run.put("exitCode", rs.getObject("exit_code"));
            // inicio so com o instante do supervisor (RUNNING); registro sem ele nao prova que o processo comecou
            run.put("startedAt", rs.getObject("agent_started_at", OffsetDateTime.class));
            run.put("endedAt", rs.getObject("agent_ended_at", OffsetDateTime.class));
            run.put("process", switch (s) { case 1 -> "NOT_STARTED"; case 2 -> "STARTING"; case 3 -> "RUNNING"; default -> "ENDED"; });
            run.put("outcome", s < 4 ? "PENDING" : s == 9 ? "UNKNOWN" : "KNOWN");
            m.put("run", run);
        }
        return m;
    }

    private static void audit(JdbcClient j, UUID org, int act, UUID actor, UUID agent, UUID worker, UUID workspace, UUID authorization, UUID request) {
        j.sql("""
                INSERT INTO public.agent_admin_events (organization_id, act, actor_membership_id, agent_id, worker_id, workspace_id, authorization_id,
                    run_request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """).param(org).param((short) act).param(actor).param(agent).param(worker).param(workspace).param(authorization).param(request).update();
    }
}
