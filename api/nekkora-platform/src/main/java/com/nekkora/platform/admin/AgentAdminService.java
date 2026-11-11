package com.nekkora.platform.admin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.db.DbErrors;
import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContext;
import com.nekkora.platform.access.WorkspaceAccess;
import com.nekkora.platform.admin.AccessActions.Failure;
import com.nekkora.platform.session.ValidSession;

/**
 * Atos das pessoas no canal do Agent (contrato Platform-Agent 1.0, sec. 4 e 6): codigo de pareamento e de rotacao,
 * cancelamento, revogacao do Agent, autorizacao do Worker no workspace e autorizacao de execucao local. Cada ato: transacao
 * da organizacao, ator e permissao relidos dentro dela, step-up recente nos que concedem acesso a uma maquina, auditoria em
 * {@code agent_admin_events} na mesma transacao (sem codigo nem credencial). Os gatilhos da 025 reconferem a autoridade.
 */
public final class AgentAdminService {

    private static final Logger LOG = LoggerFactory.getLogger(AgentAdminService.class);
    /** Regra da impressao do pacote aceita nesta versao (contrato, sec. 6.4). */
    static final String FINGERPRINT_RULE = "nekkora-agent/package-fingerprint/1";
    /** Regra propria do pacote Python externo (AGENT-PYTHON-001); a /1 dos pacotes Studio nao muda. */
    static final String EXTERNAL_FINGERPRINT_RULE = "nekkora-agent/external-python-fingerprint/1";

    /** Origem explicita da autorizacao e do registro (030): {@code source_kind} 1, 2 e 3. */
    public static String sourceKind(short code) {
        return switch (code) {
            case 1 -> "STUDIO_PACKAGE";
            case 2 -> "EXTERNAL_PACKAGE";
            case 3 -> "EXTERNAL_MONITORED";
            default -> throw new IllegalStateException("source_kind " + code);
        };
    }

    static final Pattern NAME = Pattern.compile("[^\\p{Cntrl}]{1,80}");

    private final AccessActions actions;
    private final WorkspaceAccess access;

    public AgentAdminService(AccessActions actions, WorkspaceAccess access) {
        this.actions = actions;
        this.access = access;
    }

    /** Codigo emitido: o valor {@code code} so aparece nesta resposta. */
    public record CodeIssued(UUID enrollmentId, String code, OffsetDateTime expiresAt, UUID workerId, UUID agentId) {
    }

    // ------------------------------------------------------------------ pareamento e rotacao

    /** Worker criado sem Agent (PLATFORM-AGENT-V2-002, T07). */
    public record WorkerCreated(UUID workerId, String name, String status) {
    }

    /**
     * T07: cria um Worker ATIVO sem Agent, sem codigo e sem execucao. Exige worker.manage. A capacidade (WORKERS_REGISTERED)
     * continua decidida pelo gatilho da 010, com a organizacao travada; o limite atingido (inclusive zero, antes da ativacao)
     * vira 409 conflict + worker_limit_reached, sem gravar nada. Auditado (ato 7, WORKER_CRIADO).
     */
    public WorkerCreated createWorker(UUID org, ValidSession actor, String token, String name) {
        String n = name == null ? null : name.strip();
        if (!validName(n)) {
            throw ApiException.invalid("nome");
        }
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            actions.requirePermission(j, org, actor, "worker.manage");
            actions.lockOrganizationAndActor(j, org, actor.userId());
            UUID worker;
            try {
                worker = j.sql("INSERT INTO public.workers (organization_id, name, status) VALUES (?, ?, 1) RETURNING worker_id")
                        .param(org).param(n).query(UUID.class).single();
            } catch (RuntimeException e) {
                if (DbErrors.workerCapacityRefused(e)) {
                    LOG.info("evento=worker.limite_atingido correlacao={}", RequestContext.correlation());
                    throw new ApiException(ApiError.WORKER_LIMIT_REACHED, "limite_workers");
                }
                throw e;
            }
            audit(j, org, 7, actor.membershipId(), null, worker, null, null, null);
            LOG.info("evento=worker.criado worker={} correlacao={}", worker, RequestContext.correlation());
            return new WorkerCreated(worker, n, "ACTIVE");
        });
    }

    public CodeIssued createPairing(UUID org, ValidSession actor, String token, UUID workerId, String workerName, String agentName, Integer minutes) {
        int ttl = ttl(minutes);
        if ((workerId == null) == (workerName == null) || !validName(agentName) || workerName != null && !validName(workerName)) {
            throw ApiException.invalid("pareamento");
        }
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            actions.requirePermission(j, org, actor, "agent.manage");
            actions.requirePermission(j, org, actor, "agent_credential.manage");
            actions.requirePermission(j, org, actor, "worker_agent.assign");
            if (workerName != null) {
                actions.requirePermission(j, org, actor, "worker.manage");
            }
            actions.requireStepUp(j, actor);
            actions.lockOrganizationAndActor(j, org, actor.userId());
            UUID worker = workerId;
            if (worker == null) {
                // a 010 trava a organizacao e confere WORKERS_REGISTERED (zero ate a ativacao da 022)
                worker = j.sql("INSERT INTO public.workers (organization_id, name, status) VALUES (?, ?, 1) RETURNING worker_id")
                        .param(org).param(workerName).query(UUID.class).single();
                audit(j, org, 7, actor.membershipId(), null, worker, null, null, null);
            } else {
                Short status = j.sql("SELECT status FROM public.workers WHERE organization_id = ? AND worker_id = ?").param(org).param(worker)
                        .query(Short.class).optional().orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "worker"));
                if (status != 1) {
                    throw AccessActions.refuse(Failure.CONFLICT, "worker_nao_ativo");
                }
            }
            if (j.sql("SELECT EXISTS (SELECT 1 FROM public.worker_agent_assignments WHERE organization_id = ? AND worker_id = ? AND status = 1)")
                    .param(org).param(worker).query(Boolean.class).single()) {
                throw AccessActions.refuse(Failure.CONFLICT, "worker_com_agent_ativo");
            }
            CodeIssued c = issue(j, org, actor, (short) 1, worker, null, agentName, ttl);
            audit(j, org, 1, actor.membershipId(), null, worker, null, c.enrollmentId(), null);
            LOG.info("evento=agente.codigo_pareamento worker={} correlacao={}", worker, RequestContext.correlation());
            return c;
        });
    }

    public CodeIssued createRotation(UUID org, ValidSession actor, String token, UUID agentId, Integer minutes) {
        int ttl = ttl(minutes);
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            actions.requirePermission(j, org, actor, "agent_credential.manage");
            actions.requireStepUp(j, actor);
            actions.lockOrganizationAndActor(j, org, actor.userId());
            UUID worker = j.sql("""
                    SELECT wa.worker_id FROM public.agents a
                      JOIN public.worker_agent_assignments wa ON wa.organization_id = a.organization_id AND wa.agent_id = a.agent_id AND wa.status = 1
                     WHERE a.organization_id = ? AND a.agent_id = ? AND a.status = 2 FOR UPDATE OF a
                    """).param(org).param(agentId).query(UUID.class).optional()
                    .orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "agent"));
            CodeIssued c = issue(j, org, actor, (short) 2, worker, agentId, null, ttl);
            audit(j, org, 4, actor.membershipId(), agentId, worker, null, c.enrollmentId(), null);
            return c;
        });
    }

    private static CodeIssued issue(JdbcClient j, UUID org, ValidSession actor, short purpose, UUID worker, UUID agent, String agentName, int ttl) {
        UUID enrollment = UUID.randomUUID();
        String code = "nkp1." + org + "." + enrollment + "." + Tokens.newToken();
        OffsetDateTime expires = j.sql("""
                INSERT INTO public.agent_enrollments (enrollment_id, organization_id, purpose, worker_id, agent_id, agent_name, code_hash,
                    created_by_membership_id, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp() + make_interval(mins => ?)) RETURNING expires_at
                """).param(enrollment).param(org).param(purpose).param(worker).param(agent).param(agentName).param(Tokens.sha256Hex(code))
                .param(actor.membershipId()).param(ttl).query(OffsetDateTime.class).single();
        return new CodeIssued(enrollment, code, expires, worker, agent);
    }

    public void cancelCode(UUID org, ValidSession actor, String token, UUID enrollmentId) {
        actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            actions.requirePermission(j, org, actor, "agent_credential.manage");
            int n = j.sql("UPDATE public.agent_enrollments SET cancelled_at = clock_timestamp(), cancelled_by_membership_id = ? "
                            + "WHERE organization_id = ? AND enrollment_id = ? AND consumed_at IS NULL AND cancelled_at IS NULL")
                    .param(actor.membershipId()).param(org).param(enrollmentId).update();
            if (n == 0) {
                boolean exists = j.sql("SELECT EXISTS (SELECT 1 FROM public.agent_enrollments WHERE organization_id = ? AND enrollment_id = ?)")
                        .param(org).param(enrollmentId).query(Boolean.class).single();
                throw AccessActions.refuse(exists ? Failure.CONFLICT : Failure.TARGET_NOT_FOUND, "codigo");
            }
            audit(j, org, 2, actor.membershipId(), null, null, null, enrollmentId, null);
            return null;
        });
    }

    /**
     * Revogacao do Agent: credencial revogada (motivo 3 ou 4), associacao com o Worker encerrada, codigos de rotacao abertos
     * cancelados e o Agent REVOKED, numa transacao. A credencial e atualizada primeiro: espera os atos da maquina em curso
     * (que a travam FOR SHARE) e, depois do commit, nenhum ato novo da maquina passa.
     */
    public void revokeAgent(UUID org, ValidSession actor, String token, UUID agentId, String reason) {
        short code = switch (reason == null ? "" : reason) {
            case "COMPROMISED" -> (short) 3;
            case "ADMINISTRATIVE" -> (short) 4;
            default -> throw ApiException.invalid("motivo");
        };
        actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            actions.requirePermission(j, org, actor, "agent.manage");
            actions.requireStepUp(j, actor);
            actions.lockOrganizationAndActor(j, org, actor.userId());
            Short status = j.sql("SELECT status FROM public.agents WHERE organization_id = ? AND agent_id = ? FOR UPDATE").param(org).param(agentId)
                    .query(Short.class).optional().orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "agent"));
            if (status == 4) {
                throw AccessActions.refuse(Failure.CONFLICT, "agent_ja_revogado");
            }
            List<UUID> credentials = j.sql("UPDATE public.agent_credentials SET revoked_at = clock_timestamp(), revoked_by_membership_id = ?, "
                            + "revocation_reason = ? WHERE organization_id = ? AND agent_id = ? AND revoked_at IS NULL RETURNING credential_id")
                    .param(actor.membershipId()).param(code).param(org).param(agentId).query(UUID.class).list();
            UUID worker = j.sql("UPDATE public.worker_agent_assignments SET status = 2, ended_at = clock_timestamp(), ended_by_membership_id = ? "
                            + "WHERE organization_id = ? AND agent_id = ? AND status = 1 RETURNING worker_id")
                    .param(actor.membershipId()).param(org).param(agentId).query(UUID.class).optional().orElse(null);
            j.sql("UPDATE public.agent_enrollments SET cancelled_at = clock_timestamp(), cancelled_by_membership_id = ? "
                            + "WHERE organization_id = ? AND agent_id = ? AND consumed_at IS NULL AND cancelled_at IS NULL")
                    .param(actor.membershipId()).param(org).param(agentId).update();
            j.sql("UPDATE public.agents SET status = 4 WHERE organization_id = ? AND agent_id = ?").param(org).param(agentId).update();
            audit(j, org, 6, actor.membershipId(), agentId, worker, null, null, credentials.isEmpty() ? null : credentials.get(0),
                    code == 3 ? "COMPROMISED" : "ADMINISTRATIVE");
            LOG.info("evento=agente.revogado agente={} correlacao={}", agentId, RequestContext.correlation());
            return null;
        });
    }

    // ------------------------------------------------------------------ Worker no workspace

    public record WorkerWorkspace(UUID assignmentId, UUID workerId, UUID workspaceId, boolean created) {
    }

    public WorkerWorkspace authorizeWorker(UUID org, ValidSession actor, String token, UUID workerId, UUID workspaceId) {
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            actions.requirePermission(j, org, actor, "worker_workspace.authorize");
            actions.requireStepUp(j, actor);
            actions.lockOrganizationAndActor(j, org, actor.userId());
            Short worker = j.sql("SELECT status FROM public.workers WHERE organization_id = ? AND worker_id = ?").param(org).param(workerId)
                    .query(Short.class).optional().orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "worker"));
            Short ws = j.sql("SELECT status FROM public.workspaces WHERE organization_id = ? AND workspace_id = ?").param(org).param(workspaceId)
                    .query(Short.class).optional().orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "workspace"));
            if (worker != 1 || ws != 1) {
                throw AccessActions.refuse(Failure.CONFLICT, "worker_ou_workspace_nao_ativo");
            }
            UUID existing = j.sql("SELECT assignment_id FROM public.worker_workspace_assignments WHERE organization_id = ? AND worker_id = ? "
                            + "AND workspace_id = ? AND status = 1").param(org).param(workerId).param(workspaceId).query(UUID.class).optional().orElse(null);
            if (existing != null) {
                return new WorkerWorkspace(existing, workerId, workspaceId, false);
            }
            UUID id = j.sql("INSERT INTO public.worker_workspace_assignments (organization_id, worker_id, workspace_id, status, authorized_by_membership_id) "
                    + "VALUES (?, ?, ?, 1, ?) RETURNING assignment_id").param(org).param(workerId).param(workspaceId).param(actor.membershipId())
                    .query(UUID.class).single();
            audit(j, org, 8, actor.membershipId(), null, workerId, workspaceId, null, null);
            return new WorkerWorkspace(id, workerId, workspaceId, true);
        });
    }

    public void revokeWorker(UUID org, ValidSession actor, String token, UUID workerId, UUID workspaceId) {
        actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            actions.requirePermission(j, org, actor, "worker_workspace.authorize");
            int n = j.sql("UPDATE public.worker_workspace_assignments SET status = 2, revoked_at = clock_timestamp(), revoked_by_membership_id = ? "
                            + "WHERE organization_id = ? AND worker_id = ? AND workspace_id = ? AND status = 1")
                    .param(actor.membershipId()).param(org).param(workerId).param(workspaceId).update();
            if (n == 0) {
                throw AccessActions.refuse(Failure.TARGET_NOT_FOUND, "worker_workspace");
            }
            audit(j, org, 9, actor.membershipId(), null, workerId, workspaceId, null, null);
            return null;
        });
    }

    // ------------------------------------------------------------------ autorizacao de execucao local

    /**
     * Autorizacao local. {@code sourceKind}: STUDIO_PACKAGE, EXTERNAL_PACKAGE ou EXTERNAL_MONITORED (030). No monitoramento,
     * {@code automationVersionId}, {@code packageFingerprint} e {@code packageFingerprintRule} sao nulos: a Platform nao conhece codigo.
     */
    public record LocalAuthorization(UUID authorizationId, UUID workspaceId, UUID workerId, UUID automationId, UUID automationVersionId,
                                     String packageFingerprint, OffsetDateTime grantedAt, OffsetDateTime validUntil, OffsetDateTime revokedAt,
                                     String sourceKind, String packageFingerprintRule) {
    }

    private record Version(UUID automationId, short status, String rule, String packageFingerprint) {
    }

    private static final String AUTHORIZATION_COLUMNS = "authorization_id, workspace_id, worker_id, automation_id, automation_version_id, package_fingerprint, "
            + "granted_at, valid_until, revoked_at, source_kind, package_fingerprint_rule";

    public LocalAuthorization authorizeLocalRun(UUID org, ValidSession actor, String token, UUID workspaceId, UUID workerId, UUID versionId,
                                                OffsetDateTime validUntil) {
        // a mesma faixa do gatilho da 025 ((agora, agora + 366 dias]): fora dela, 400 em vez do 23514 do banco
        if (validUntil == null || !validUntil.isAfter(OffsetDateTime.now()) || validUntil.isAfter(OffsetDateTime.now().plusDays(366))) {
            throw ApiException.invalid("validade");
        }
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            access.requireWorkspace(j, org, actor.membershipId(), workspaceId, "automation.execute");
            actions.requireStepUp(j, actor);
            actions.lockOrganizationAndActor(j, org, actor.userId());
            // a impressao do pacote declarada na versao, com a regra (contrato, sec. 6.4); nunca o sha256 dos bytes do artefato
            // a regra declarada na versao decide a origem: /1 = pacote Studio, external-python-fingerprint/1 = pacote externo (030)
            Version v = j.sql("SELECT automation_id, status, command_json -> 'agent_package' ->> 'fingerprint_rule', "
                            + "command_json -> 'agent_package' ->> 'fingerprint' FROM public.automation_versions "
                            + "WHERE organization_id = ? AND workspace_id = ? AND automation_version_id = ?").param(org).param(workspaceId)
                    .param(versionId).query((rs, n) -> new Version(rs.getObject(1, UUID.class), rs.getShort(2), rs.getString(3), rs.getString(4))).optional()
                    .orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "versao"));
            boolean knownRule = FINGERPRINT_RULE.equals(v.rule()) || EXTERNAL_FINGERPRINT_RULE.equals(v.rule());
            if (v.status() != 2 || !knownRule || v.packageFingerprint() == null || !v.packageFingerprint().matches("[0-9a-f]{64}")) {
                throw AccessActions.refuse(Failure.CONFLICT, "versao_nao_publicada_ou_sem_impressao_do_pacote");
            }
            short kind = FINGERPRINT_RULE.equals(v.rule()) ? (short) 1 : (short) 2;
            // automacao e workspace ativos (o gatilho da 025 tambem confere; aqui a recusa vira 409 em vez de 500)
            if (!Boolean.TRUE.equals(j.sql("SELECT a.status = 1 AND w.status = 1 FROM public.automations a JOIN public.workspaces w "
                    + "ON w.organization_id = a.organization_id AND w.workspace_id = a.workspace_id WHERE a.organization_id = ? AND a.automation_id = ?")
                    .param(org).param(v.automationId()).query(Boolean.class).optional().orElse(false))) {
                throw AccessActions.refuse(Failure.CONFLICT, "automacao_ou_workspace_inativo");
            }
            UUID assignment = j.sql("SELECT assignment_id FROM public.worker_workspace_assignments WHERE organization_id = ? AND worker_id = ? "
                            + "AND workspace_id = ? AND status = 1").param(org).param(workerId).param(workspaceId).query(UUID.class).optional()
                    .orElseThrow(() -> AccessActions.refuse(Failure.CONFLICT, "worker_nao_autorizado_no_workspace"));
            LocalAuthorization a = j.sql("""
                    INSERT INTO public.local_run_authorizations (organization_id, workspace_id, worker_id, worker_workspace_assignment_id, automation_id,
                        automation_version_id, package_fingerprint, package_fingerprint_rule, source_kind, granted_by_membership_id, valid_until)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING """ + " " + AUTHORIZATION_COLUMNS).param(org).param(workspaceId).param(workerId).param(assignment).param(v.automationId()).param(versionId)
                    .param(v.packageFingerprint()).param(v.rule()).param(kind).param(actor.membershipId()).param(validUntil)
                    .query((rs, n) -> authorization(rs)).single();
            audit(j, org, 10, actor.membershipId(), null, workerId, workspaceId, null, null, a.authorizationId(), null);
            return a;
        });
    }

    /**
     * Autorizacao de MONITORAMENTO de Python externo (030, source_kind 3): a automacao cadastrada, o Worker e a validade. Sem
     * versao e sem impressao: nada de codigo, caminho ou argumento chega a Platform. Mesma autoridade (automation.execute no
     * workspace + step-up) e mesmas recusas da autorizacao de pacote.
     */
    public LocalAuthorization authorizeMonitored(UUID org, ValidSession actor, String token, UUID workspaceId, UUID workerId, UUID automationId,
                                                 OffsetDateTime validUntil) {
        if (validUntil == null || !validUntil.isAfter(OffsetDateTime.now()) || validUntil.isAfter(OffsetDateTime.now().plusDays(366))) {
            throw ApiException.invalid("validade");
        }
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            access.requireWorkspace(j, org, actor.membershipId(), workspaceId, "automation.execute");
            actions.requireStepUp(j, actor);
            actions.lockOrganizationAndActor(j, org, actor.userId());
            Boolean active = j.sql("SELECT a.status = 1 AND w.status = 1 FROM public.automations a JOIN public.workspaces w "
                            + "ON w.organization_id = a.organization_id AND w.workspace_id = a.workspace_id "
                            + "WHERE a.organization_id = ? AND a.workspace_id = ? AND a.automation_id = ?")
                    .param(org).param(workspaceId).param(automationId).query(Boolean.class).optional()
                    .orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "automacao"));
            if (!active) {
                throw AccessActions.refuse(Failure.CONFLICT, "automacao_ou_workspace_inativo");
            }
            UUID assignment = j.sql("SELECT assignment_id FROM public.worker_workspace_assignments WHERE organization_id = ? AND worker_id = ? "
                            + "AND workspace_id = ? AND status = 1").param(org).param(workerId).param(workspaceId).query(UUID.class).optional()
                    .orElseThrow(() -> AccessActions.refuse(Failure.CONFLICT, "worker_nao_autorizado_no_workspace"));
            LocalAuthorization a = j.sql("""
                    INSERT INTO public.local_run_authorizations (organization_id, workspace_id, worker_id, worker_workspace_assignment_id, automation_id,
                        automation_version_id, package_fingerprint, package_fingerprint_rule, source_kind, granted_by_membership_id, valid_until)
                    VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL, 3, ?, ?)
                    RETURNING """ + " " + AUTHORIZATION_COLUMNS).param(org).param(workspaceId).param(workerId).param(assignment).param(automationId)
                    .param(actor.membershipId()).param(validUntil).query((rs, n) -> authorization(rs)).single();
            audit(j, org, 10, actor.membershipId(), null, workerId, workspaceId, null, null, a.authorizationId(), null);
            return a;
        });
    }

    public void revokeLocalRun(UUID org, ValidSession actor, String token, UUID workspaceId, UUID authorizationId) {
        actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            access.requireWorkspace(j, org, actor.membershipId(), workspaceId, "automation.execute");
            int n = j.sql("UPDATE public.local_run_authorizations SET revoked_at = clock_timestamp(), revoked_by_membership_id = ? "
                            + "WHERE organization_id = ? AND workspace_id = ? AND authorization_id = ? AND revoked_at IS NULL")
                    .param(actor.membershipId()).param(org).param(workspaceId).param(authorizationId).update();
            if (n == 0) {
                throw AccessActions.refuse(Failure.TARGET_NOT_FOUND, "autorizacao");
            }
            audit(j, org, 11, actor.membershipId(), null, null, workspaceId, null, null, authorizationId, null);
            return null;
        });
    }

    public List<LocalAuthorization> listLocalRuns(UUID org, ValidSession actor, String token, UUID workspaceId) {
        return actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            access.requireWorkspace(j, org, actor.membershipId(), workspaceId, "automation.read");
            return j.sql("""
                    SELECT """ + " " + AUTHORIZATION_COLUMNS + """

                      FROM public.local_run_authorizations WHERE organization_id = ? AND workspace_id = ? ORDER BY granted_at, authorization_id
                    """).param(org).param(workspaceId).query((rs, n) -> authorization(rs)).list();
        });
    }

    private static LocalAuthorization authorization(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LocalAuthorization(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                rs.getObject(5, UUID.class), rs.getString(6), rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class),
                rs.getObject(9, OffsetDateTime.class), sourceKind(rs.getShort(10)), rs.getString(11));
    }

    // ------------------------------------------------------------------ leitura das maquinas

    /** Agent: identidade, Worker, versao informada, presenca e validade da credencial; nunca o hash. */
    public record AgentView(UUID agentId, String name, UUID installationId, String agentVersion, String status, UUID workerId,
                            OffsetDateTime registeredAt, OffsetDateTime lastHeartbeatAt, String connectivity, String contractVersion,
                            List<String> capabilities, OffsetDateTime credentialExpiresAt) {
    }

    /**
     * Comunicacao (PLATFORM-AGENT-V2-003, T11): CONNECTED so com o Agent ATIVO, vinculado (associacao ativa ao Worker) e com
     * ultimo contato AUTENTICADO (heartbeat aceito pelo gateway, relogio do servidor) nos ultimos 180 s. O Agent bate a cada 60 s
     * (contrato Platform-Agent, sec. 5): a tolerancia cobre duas batidas perdidas. Revogado, suspenso ou sem associacao nunca
     * aparece CONNECTED, mesmo com um sinal recente: um sinal antigo nao reativa vinculo. Sem nenhum contato
     * (lastHeartbeatAt nulo) e DISCONNECTED: nunca "conectado" antes do primeiro contato valido. A expiracao so muda o que a
     * leitura mostra: nao revoga, nao libera o Worker, nao retira workspaces, nao encerra execucoes.
     */
    static final int CONNECTED_SECONDS = 180;

    public List<AgentView> listAgents(UUID org, ValidSession actor, String token) {
        return actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            actions.requirePermission(j, org, actor, "agent.manage");
            return j.sql("""
                    SELECT a.agent_id, a.name, a.installation_id, a.agent_version, a.status, wa.worker_id, a.registered_at, a.last_heartbeat_at,
                           a.status = 2 AND wa.worker_id IS NOT NULL AND a.last_heartbeat_at > clock_timestamp() - make_interval(secs => ?), p.contract_version, p.capabilities, c.expires_at
                      FROM public.agents a
                      LEFT JOIN public.worker_agent_assignments wa ON wa.organization_id = a.organization_id AND wa.agent_id = a.agent_id AND wa.status = 1
                      LEFT JOIN public.agent_presence p ON p.agent_id = a.agent_id
                      LEFT JOIN public.agent_credentials c ON c.organization_id = a.organization_id AND c.agent_id = a.agent_id AND c.revoked_at IS NULL
                     WHERE a.organization_id = ? ORDER BY a.registered_at, a.agent_id
                    """).param(CONNECTED_SECONDS).param(org).query((rs, n) -> {
                java.sql.Array caps = rs.getArray(11);
                return new AgentView(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4),
                        switch (rs.getShort(5)) { case 1 -> "PENDING"; case 2 -> "ACTIVE"; case 3 -> "SUSPENDED"; default -> "REVOKED"; },
                        rs.getObject(6, UUID.class), rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class),
                        Boolean.TRUE.equals(rs.getObject(9, Boolean.class)) ? "CONNECTED" : "DISCONNECTED", rs.getString(10), caps == null ? List.of() : List.of((String[]) caps.getArray()),
                        rs.getObject(12, OffsetDateTime.class));
            }).list();
        });
    }

    /**
     * Worker com a maquina vinculada (T07, campos aditivos): situacao do Worker, maquina (Agent da associacao ativa; null =
     * sem Agent) e comunicacao (ultimo sinal; CONNECTED/DISCONNECTED com a mesma janela da lista de Agents). Ausencia de
     * sinal nao muda nada no servidor: nao revoga, nao libera o Worker, nao retira workspaces, nao encerra execucoes.
     */
    public record WorkerView(UUID workerId, String name, String status, UUID agentId, List<UUID> workspaceIds, String agentName,
                             String agentStatus, String agentVersion, UUID installationId, OffsetDateTime lastHeartbeatAt, String connectivity,
                             OffsetDateTime credentialExpiresAt, OffsetDateTime createdAt) {
    }

    public List<WorkerView> listWorkers(UUID org, ValidSession actor, String token) {
        return actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            actions.requirePermission(j, org, actor, "worker.manage");
            return j.sql("""
                    SELECT w.worker_id, w.name, w.status, wa.agent_id,
                           coalesce((SELECT array_agg(x.workspace_id ORDER BY x.workspace_id) FROM public.worker_workspace_assignments x
                                      WHERE x.organization_id = w.organization_id AND x.worker_id = w.worker_id AND x.status = 1), '{}'),
                           a.name, a.status, a.agent_version, a.installation_id, a.last_heartbeat_at,
                           a.status = 2 AND a.last_heartbeat_at > clock_timestamp() - make_interval(secs => ?), c.expires_at, w.created_at
                      FROM public.workers w
                      LEFT JOIN public.worker_agent_assignments wa ON wa.organization_id = w.organization_id AND wa.worker_id = w.worker_id AND wa.status = 1
                      LEFT JOIN public.agents a ON a.organization_id = wa.organization_id AND a.agent_id = wa.agent_id
                      LEFT JOIN public.agent_credentials c ON c.organization_id = a.organization_id AND c.agent_id = a.agent_id AND c.revoked_at IS NULL
                     WHERE w.organization_id = ? ORDER BY w.created_at, w.worker_id
                    """).param(CONNECTED_SECONDS).param(org).query((rs, n) -> {
                UUID agent = rs.getObject(4, UUID.class);
                return new WorkerView(rs.getObject(1, UUID.class), rs.getString(2),
                        switch (rs.getShort(3)) { case 1 -> "ACTIVE"; case 2 -> "SUSPENDED"; default -> "RETIRED"; }, agent,
                        List.of((UUID[]) rs.getArray(5).getArray()), rs.getString(6),
                        agent == null ? null : switch (rs.getShort(7)) { case 1 -> "PENDING"; case 2 -> "ACTIVE"; case 3 -> "SUSPENDED"; default -> "REVOKED"; },
                        rs.getString(8), rs.getObject(9, UUID.class), rs.getObject(10, OffsetDateTime.class),
                        agent == null ? null : Boolean.TRUE.equals(rs.getObject(11, Boolean.class)) ? "CONNECTED" : "DISCONNECTED", rs.getObject(12, OffsetDateTime.class),
                        rs.getObject(13, OffsetDateTime.class));
            }).list();
        });
    }

    // ------------------------------------------------------------------ apoio

    private static int ttl(Integer minutes) {
        int m = minutes == null ? 15 : minutes;
        if (m < 5 || m > 60) {
            throw ApiException.invalid("validade_codigo");
        }
        return m;
    }

    private static boolean validName(String s) {
        return s != null && s.equals(s.strip()) && NAME.matcher(s).matches();
    }

    private static void audit(JdbcClient j, UUID org, int act, UUID actor, UUID agent, UUID worker, UUID workspace, UUID enrollment, UUID credential) {
        audit(j, org, act, actor, agent, worker, workspace, enrollment, credential, null, null);
    }

    private static void audit(JdbcClient j, UUID org, int act, UUID actor, UUID agent, UUID worker, UUID workspace, UUID enrollment, UUID credential,
                              String reason) {
        audit(j, org, act, actor, agent, worker, workspace, enrollment, credential, null, reason);
    }

    private static void audit(JdbcClient j, UUID org, int act, UUID actor, UUID agent, UUID worker, UUID workspace, UUID enrollment, UUID credential,
                              UUID authorization, String reason) {
        j.sql("""
                INSERT INTO public.agent_admin_events (organization_id, act, actor_membership_id, agent_id, worker_id, workspace_id, enrollment_id,
                    credential_id, authorization_id, reason_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).param(org).param((short) act).param(actor).param(agent).param(worker).param(workspace).param(enrollment).param(credential)
                .param(authorization).param(reason).update();
    }
}
