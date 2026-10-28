package com.nekkora.agentgateway;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.agentgateway.MachineAuth.Machine;
import com.nekkora.agentgateway.SupervisorReport.Outcome;
import com.nekkora.agentgateway.SupervisorReport.State;
import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;
import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContext;

import tools.jackson.databind.json.JsonMapper;

/**
 * Atos da maquina (contrato, sec. 4 a 7): resgate do codigo (pareamento ou rotacao), heartbeat, autorizacoes do Worker,
 * registro idempotente da execucao local e relatorio do supervisor. Cada ato e UMA transacao da organizacao da credencial
 * (ou do codigo), com a autenticacao e as releituras dentro dela.
 */
public final class GatewayService {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayService.class);
    static final Pattern AGENT_VERSION = Pattern.compile("[0-9A-Za-z.+-]{1,40}");
    static final Pattern EXECUTION = Pattern.compile("ex-[0-9a-f]{32}");
    static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    static final List<String> CAPABILITIES = List.of("RUN_LOCAL", "OBS_1_0", "SUPERVISOR_REPORT_1_0");
    static final JsonMapper JSON = JsonMapper.builder().build();

    private final OrgTransactions tx;
    private final MachineAuth auth;
    private final int credentialDays;

    public GatewayService(OrgTransactions tx, MachineAuth auth, int credentialDays) {
        this.tx = tx;
        this.auth = auth;
        this.credentialDays = credentialDays;
    }

    // ------------------------------------------------------------------ resgate

    public record Redeemed(UUID organizationId, UUID agentId, UUID installationId, UUID workerId, UUID credentialId, OffsetDateTime credentialExpiresAt,
                           String contractVersion) {
    }

    private record Enrollment(UUID id, short purpose, UUID workerId, UUID agentId, String agentName, String codeHash, UUID createdBy,
                              boolean expired, boolean consumed, boolean cancelled, UUID consumedCredential) {
    }

    /**
     * Resgate do codigo {@code code} com o segredo da credencial gerado pela maquina. Idempotente pelo par (codigo, segredo):
     * a resposta perdida nao tranca a maquina para fora. Rotacao exige a credencial atual ({@code current}).
     */
    public Redeemed redeem(MachineToken code, String credentialSecret, String agentVersion, Optional<MachineToken> current) {
        if (!Tokens.isValidFormat(credentialSecret) || agentVersion == null || !AGENT_VERSION.matcher(agentVersion).matches()) {
            throw ApiException.invalid("resgate");
        }
        String secretHash = Tokens.sha256Hex(credentialSecret);
        return tx.inOrganization(code.organization(), Mode.READ_WRITE, j -> {
            Enrollment e = j.sql("""
                    SELECT enrollment_id, purpose, worker_id, agent_id, agent_name, code_hash, created_by_membership_id,
                           expires_at <= clock_timestamp(), consumed_at IS NOT NULL, cancelled_at IS NOT NULL, consumed_credential_id
                      FROM public.agent_enrollments WHERE organization_id = ? AND enrollment_id = ? FOR UPDATE
                    """).param(code.organization()).param(code.id())
                    .query((rs, n) -> new Enrollment(rs.getObject(1, UUID.class), rs.getShort(2), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                            rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getBoolean(8), rs.getBoolean(9), rs.getBoolean(10),
                            rs.getObject(11, UUID.class)))
                    .optional().orElse(null);
            if (e == null || !Tokens.constantTimeEquals(e.codeHash(), Tokens.sha256Hex(code.raw()))) {
                throw ApiException.unauthenticated("codigo");
            }
            if (e.consumed()) {
                // repeticao: so o MESMO segredo devolve o mesmo resultado
                String hash = j.sql("SELECT credential_hash FROM public.agent_credentials WHERE organization_id = ? AND credential_id = ?")
                        .param(code.organization()).param(e.consumedCredential()).query(String.class).single();
                if (!Tokens.constantTimeEquals(hash, secretHash)) {
                    throw ApiException.unauthenticated("codigo_consumido");
                }
                return identity(j, code.organization(), e.consumedCredential());
            }
            if (e.cancelled() || e.expired()) {
                throw ApiException.unauthenticated("codigo_invalido");
            }
            Short orgStatus = j.sql("SELECT status FROM public.organizations WHERE organization_id = ?").param(code.organization())
                    .query(Short.class).optional().orElse(null);
            if (orgStatus == null || orgStatus != 1) {
                throw ApiException.unauthenticated("organizacao");
            }
            return e.purpose() == 1 ? pair(j, code.organization(), e, secretHash, agentVersion) : rotate(j, code.organization(), e, secretHash, current);
        });
    }

    private Redeemed pair(JdbcClient j, UUID org, Enrollment e, String secretHash, String agentVersion) {
        Short worker = j.sql("SELECT status FROM public.workers WHERE organization_id = ? AND worker_id = ?").param(org).param(e.workerId())
                .query(Short.class).optional().orElse(null);
        boolean occupied = j.sql("SELECT EXISTS (SELECT 1 FROM public.worker_agent_assignments WHERE organization_id = ? AND worker_id = ? AND status = 1)")
                .param(org).param(e.workerId()).query(Boolean.class).single();
        if (worker == null || worker != 1 || occupied) {
            throw new ApiException(ApiError.CONFLICT, "worker_indisponivel");
        }
        UUID agentId = j.sql("INSERT INTO public.agents (organization_id, name, agent_version, status) VALUES (?, ?, ?, 2) RETURNING agent_id")
                .param(org).param(e.agentName()).param(agentVersion).query(UUID.class).single();
        try {
            j.sql("INSERT INTO public.worker_agent_assignments (organization_id, worker_id, agent_id, status, assigned_by_membership_id) VALUES (?, ?, ?, 1, ?)")
                    .param(org).param(e.workerId()).param(agentId).param(e.createdBy()).update();
        } catch (DuplicateKeyException race) {
            // outro resgate associou o Worker nesta janela (indice de associacao ativa unica da 004)
            throw new ApiException(ApiError.CONFLICT, "worker_indisponivel");
        }
        UUID credentialId = issueCredential(j, org, agentId, secretHash, e.createdBy());
        consume(j, org, e.id(), credentialId, agentId);
        audit(j, org, 3, agentId, e.workerId(), e.id(), credentialId);
        LOG.info("evento=agente.pareado agente={} worker={} correlacao={}", agentId, e.workerId(), RequestContext.correlation());
        return identity(j, org, credentialId);
    }

    private Redeemed rotate(JdbcClient j, UUID org, Enrollment e, String secretHash, Optional<MachineToken> current) {
        Machine m = auth.authenticateForRotation(j, current.orElseThrow(() -> ApiException.unauthenticated("rotacao_sem_credencial")));
        if (!m.agentId().equals(e.agentId()) || !m.organization().equals(org)) {
            throw ApiException.unauthenticated("rotacao_outro_agent");
        }
        j.sql("UPDATE public.agent_credentials SET revoked_at = clock_timestamp(), revoked_by_membership_id = ?, revocation_reason = 1 "
                + "WHERE organization_id = ? AND credential_id = ? AND revoked_at IS NULL")
                .param(e.createdBy()).param(org).param(m.credentialId()).update();
        UUID credentialId = issueCredential(j, org, m.agentId(), secretHash, e.createdBy());
        consume(j, org, e.id(), credentialId, m.agentId());
        audit(j, org, 5, m.agentId(), m.workerId(), e.id(), credentialId);
        LOG.info("evento=agente.credencial_rotacionada agente={} correlacao={}", m.agentId(), RequestContext.correlation());
        return identity(j, org, credentialId);
    }

    private UUID issueCredential(JdbcClient j, UUID org, UUID agentId, String secretHash, UUID issuedBy) {
        return j.sql("INSERT INTO public.agent_credentials (organization_id, agent_id, credential_hash, issued_by_membership_id, issued_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, clock_timestamp(), clock_timestamp() + make_interval(days => ?)) RETURNING credential_id")
                .param(org).param(agentId).param(secretHash).param(issuedBy).param(credentialDays).query(UUID.class).single();
    }

    private static void consume(JdbcClient j, UUID org, UUID enrollment, UUID credential, UUID agent) {
        j.sql("UPDATE public.agent_enrollments SET consumed_at = clock_timestamp(), consumed_credential_id = ?, agent_id = ? "
                + "WHERE organization_id = ? AND enrollment_id = ?").param(credential).param(agent).param(org).param(enrollment).update();
    }

    private static void audit(JdbcClient j, UUID org, int act, UUID agentId, UUID workerId, UUID enrollment, UUID credential) {
        j.sql("INSERT INTO public.agent_admin_events (organization_id, act, actor_agent_id, agent_id, worker_id, enrollment_id, credential_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)").param(org).param((short) act).param(agentId).param(agentId).param(workerId).param(enrollment)
                .param(credential).update();
    }

    private static Redeemed identity(JdbcClient j, UUID org, UUID credentialId) {
        return j.sql("""
                SELECT a.agent_id, a.installation_id, wa.worker_id, c.credential_id, c.expires_at
                  FROM public.agent_credentials c
                  JOIN public.agents a ON a.organization_id = c.organization_id AND a.agent_id = c.agent_id
                  LEFT JOIN public.worker_agent_assignments wa ON wa.organization_id = c.organization_id AND wa.agent_id = c.agent_id AND wa.status = 1
                 WHERE c.organization_id = ? AND c.credential_id = ?
                """).param(org).param(credentialId)
                .query((rs, n) -> new Redeemed(org, rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getObject(5, OffsetDateTime.class), "1.0"))
                .single();
    }

    // ------------------------------------------------------------------ presenca e autorizacoes

    public record Presence(OffsetDateTime serverTime, OffsetDateTime credentialExpiresAt, String contractVersion) {
    }

    public Presence heartbeat(MachineToken t, String agentVersion, String contractVersion, List<String> capabilities) {
        if (agentVersion == null || !AGENT_VERSION.matcher(agentVersion).matches() || contractVersion == null
                || !contractVersion.matches("1\\.[0-9]{1,3}") || capabilities == null || capabilities.size() > 8
                || !CAPABILITIES.containsAll(capabilities)) {
            throw ApiException.invalid("heartbeat");
        }
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = auth.authenticate(j, t);
            OffsetDateTime now = j.sql("UPDATE public.agents SET last_heartbeat_at = clock_timestamp(), agent_version = ? "
                            + "WHERE organization_id = ? AND agent_id = ? RETURNING last_heartbeat_at")
                    .param(agentVersion).param(m.organization()).param(m.agentId()).query(OffsetDateTime.class).single();
            j.sql("""
                    INSERT INTO public.agent_presence (agent_id, organization_id, contract_version, capabilities, reported_at)
                    VALUES (?, ?, ?, ?::text[], clock_timestamp())
                    ON CONFLICT (agent_id) DO UPDATE SET contract_version = EXCLUDED.contract_version, capabilities = EXCLUDED.capabilities,
                                                         reported_at = EXCLUDED.reported_at
                    """).param(m.agentId()).param(m.organization()).param(contractVersion)
                    .param("{" + String.join(",", capabilities.stream().distinct().sorted().toList()) + "}").update();
            return new Presence(now, m.credentialExpiresAt(), "1.0");
        });
    }

    public record Authorization(UUID authorizationId, UUID workspaceId, UUID automationId, UUID automationVersionId, String packageFingerprint,
                                OffsetDateTime validUntil) {
    }

    /** Autorizacoes ATIVAS do Worker da maquina (nao revogadas, no prazo, com o Worker ainda autorizado no workspace). */
    public List<Authorization> authorizations(MachineToken t) {
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = auth.authenticate(j, t);
            return j.sql("""
                    SELECT z.authorization_id, z.workspace_id, z.automation_id, z.automation_version_id, z.package_fingerprint, z.valid_until
                      FROM public.local_run_authorizations z
                      JOIN public.worker_workspace_assignments x ON x.organization_id = z.organization_id
                                                                 AND x.assignment_id = z.worker_workspace_assignment_id AND x.status = 1
                     WHERE z.organization_id = ? AND z.worker_id = ? AND z.revoked_at IS NULL AND z.valid_until > clock_timestamp()
                     ORDER BY z.granted_at, z.authorization_id
                    """).param(m.organization()).param(m.workerId())
                    .query((rs, n) -> new Authorization(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                            rs.getObject(4, UUID.class), rs.getString(5), rs.getObject(6, OffsetDateTime.class)))
                    .list();
        });
    }

    // ------------------------------------------------------------------ registro e relatorio do supervisor

    public record Registered(UUID runId, boolean created, String state, String reportOutcome) {
    }

    private record RunRow(UUID runId, UUID authorizationId, String fingerprint, SupervisorReport report) {
    }

    private record AuthRow(UUID workspaceId, UUID automationId, UUID versionId, UUID workerId, String fingerprint, boolean revoked, boolean expired) {
    }

    /** Registro idempotente por (Agent, agent_execution_id). O mesmo id com outra autorizacao ou pacote: 409. */
    public Registered register(MachineToken t, String agentExecutionId, UUID authorizationId, String packageFingerprint, SupervisorReport report) {
        if (agentExecutionId == null || !EXECUTION.matcher(agentExecutionId).matches() || authorizationId == null || packageFingerprint == null
                || !SHA.matcher(packageFingerprint).matches()) {
            throw ApiException.invalid("registro");
        }
        try {
            return registerOnce(t, agentExecutionId, authorizationId, packageFingerprint, report);
        } catch (DuplicateKeyException race) {
            // dois registros concorrentes do mesmo agent_execution_id: o segundo ve o primeiro e segue o caminho idempotente
            return registerOnce(t, agentExecutionId, authorizationId, packageFingerprint, report);
        }
    }

    private Registered registerOnce(MachineToken t, String agentExecutionId, UUID authorizationId, String packageFingerprint, SupervisorReport report) {
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = auth.authenticate(j, t);
            RunRow existing = lockRun(j, m, agentExecutionId);
            if (existing != null) {
                if (!existing.authorizationId().equals(authorizationId) || !existing.fingerprint().equals(packageFingerprint)) {
                    throw new ApiException(ApiError.CONFLICT, "registro_divergente");
                }
                Outcome o = applyReport(j, m, existing, report);
                State now = o == Outcome.APPLIED ? report.state() : existing.report().state();
                return new Registered(existing.runId(), false, now.name(), o.name());
            }
            AuthRow z = j.sql("""
                    SELECT z.workspace_id, z.automation_id, z.automation_version_id, z.worker_id, z.package_fingerprint,
                           z.revoked_at IS NOT NULL OR x.status <> 1, z.valid_until <= clock_timestamp()
                      FROM public.local_run_authorizations z
                      JOIN public.worker_workspace_assignments x ON x.organization_id = z.organization_id AND x.assignment_id = z.worker_workspace_assignment_id
                     WHERE z.organization_id = ? AND z.authorization_id = ?
                    """).param(m.organization()).param(authorizationId)
                    .query((rs, n) -> new AuthRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                            rs.getObject(4, UUID.class), rs.getString(5), rs.getBoolean(6), rs.getBoolean(7)))
                    .optional().orElse(null);
            if (z == null || !z.workerId().equals(m.workerId())) {
                throw ApiException.forbidden("autorizacao");
            }
            if (!z.fingerprint().equals(packageFingerprint)) {
                throw ApiException.forbidden("pacote");
            }
            short authState = z.revoked() ? (short) 2 : z.expired() ? (short) 3 : (short) 1;
            UUID runId = j.sql("""
                    INSERT INTO public.agent_runs (organization_id, workspace_id, automation_id, automation_version_id, authorization_id, agent_id, worker_id,
                        worker_agent_assignment_id, agent_execution_id, package_fingerprint, authorization_state_at_registration, supervisor_state,
                        supervisor_code, exit_code, supervisor_counts, agent_created_at, agent_started_at, agent_ended_at, adopted, suspensions, locks)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?) RETURNING agent_run_id
                    """).param(m.organization()).param(z.workspaceId()).param(z.automationId()).param(z.versionId()).param(authorizationId)
                    .param(m.agentId()).param(m.workerId()).param(m.assignmentId()).param(agentExecutionId).param(packageFingerprint).param(authState)
                    .param(report.state().code()).param(report.code()).param(report.exitCode()).param(countsJson(report)).param(ts(report.created()))
                    .param(ts(report.started())).param(ts(report.ended())).param(report.adopted()).param(report.suspensions()).param(report.locks())
                    .query(UUID.class).single();
            insertReport(j, m.organization(), runId, Outcome.APPLIED, report);
            if (authState != 1) {
                LOG.info("evento=agente.registro_autorizacao_inativa execucao={} estado_autorizacao={} correlacao={}", runId, authState,
                        RequestContext.correlation());
            }
            return new Registered(runId, true, report.state().name(), Outcome.APPLIED.name());
        });
    }

    public record Reported(UUID runId, String outcome, String state) {
    }

    /** Relatorio do supervisor de uma execucao registrada. CONFLICT e registrado (commit) e devolvido como 409 pelo controlador. */
    public Reported report(MachineToken t, String agentExecutionId, SupervisorReport report) {
        if (agentExecutionId == null || !EXECUTION.matcher(agentExecutionId).matches()) {
            throw ApiException.invalid("execucao");
        }
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = auth.authenticate(j, t);
            RunRow run = lockRun(j, m, agentExecutionId);
            if (run == null) {
                throw new ApiException(ApiError.NOT_FOUND, "execucao");
            }
            Outcome o = applyReport(j, m, run, report);
            State now = o == Outcome.APPLIED ? report.state() : run.report().state();
            return new Reported(run.runId(), o.name(), now.name());
        });
    }

    private static RunRow lockRun(JdbcClient j, Machine m, String agentExecutionId) {
        return j.sql("""
                SELECT agent_run_id, authorization_id, package_fingerprint, supervisor_state, supervisor_code, exit_code, supervisor_counts::text,
                       agent_created_at, agent_started_at, agent_ended_at, adopted, suspensions, locks
                  FROM public.agent_runs WHERE organization_id = ? AND agent_id = ? AND agent_execution_id = ? FOR UPDATE
                """).param(m.organization()).param(m.agentId()).param(agentExecutionId)
                .query((rs, n) -> new RunRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        new SupervisorReport(State.of(rs.getShort(4)), rs.getString(5), (Integer) rs.getObject(6), counts(rs.getString(7)),
                                instant(rs.getObject(8, OffsetDateTime.class)), instant(rs.getObject(9, OffsetDateTime.class)),
                                instant(rs.getObject(10, OffsetDateTime.class)), rs.getBoolean(11), rs.getInt(12),
                                rs.getInt(13))))
                .optional().orElse(null);
    }

    private static Outcome applyReport(JdbcClient j, Machine m, RunRow run, SupervisorReport next) {
        Outcome o = SupervisorReport.decide(run.report(), next);
        if (o == Outcome.APPLIED) {
            j.sql("""
                    UPDATE public.agent_runs SET supervisor_state = ?, supervisor_code = ?, exit_code = ?, supervisor_counts = ?::jsonb, agent_created_at = ?,
                           agent_started_at = ?, agent_ended_at = ?, adopted = ?, suspensions = ?, locks = ?, supervisor_reports = supervisor_reports + 1,
                           last_report_at = clock_timestamp()
                     WHERE organization_id = ? AND agent_run_id = ?
                    """).param(next.state().code()).param(next.code()).param(next.exitCode()).param(countsJson(next)).param(ts(next.created()))
                    .param(ts(next.started())).param(ts(next.ended())).param(next.adopted()).param(next.suspensions()).param(next.locks())
                    .param(m.organization()).param(run.runId()).update();
        } else {
            j.sql("UPDATE public.agent_runs SET supervisor_reports = supervisor_reports + 1, supervisor_conflicts = supervisor_conflicts + ?, "
                            + "last_report_at = clock_timestamp() WHERE organization_id = ? AND agent_run_id = ?")
                    .param(o == Outcome.CONFLICT ? 1 : 0).param(m.organization()).param(run.runId()).update();
        }
        if (o == Outcome.CONFLICT) {
            j.sql("INSERT INTO public.agent_ingestion_refusals (organization_id, agent_id, agent_run_id, kind, reason) VALUES (?, ?, ?, 4, ?)")
                    .param(m.organization()).param(m.agentId()).param(run.runId()).param(run.report().state().terminal() ? "terminal_contradicted"
                            : "transition_not_allowed").update();
            LOG.info("evento=agente.relatorio_conflitante execucao={} atual={} recebido={} correlacao={}", run.runId(), run.report().state(),
                    next.state(), RequestContext.correlation());
        }
        insertReport(j, m.organization(), run.runId(), o, next);
        return o;
    }

    private static void insertReport(JdbcClient j, UUID org, UUID runId, Outcome o, SupervisorReport r) {
        j.sql("""
                INSERT INTO public.agent_run_reports (organization_id, agent_run_id, outcome, state, code, exit_code, counts, agent_created_at,
                    agent_started_at, agent_ended_at, adopted, suspensions, locks)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                """).param(org).param(runId).param(o.code()).param(r.state().code()).param(r.code()).param(r.exitCode()).param(countsJson(r))
                .param(ts(r.created())).param(ts(r.started())).param(ts(r.ended())).param(r.adopted()).param(r.suspensions()).param(r.locks()).update();
    }

    static String countsJson(SupervisorReport r) {
        return JSON.writeValueAsString(new java.util.TreeMap<>(r.counts()));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Long> counts(String json) {
        Map<String, Object> raw = JSON.readValue(json, Map.class);
        java.util.TreeMap<String, Long> out = new java.util.TreeMap<>();
        raw.forEach((k, v) -> out.put(k, ((Number) v).longValue()));
        return Map.copyOf(out);
    }

    static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(java.time.ZoneOffset.UTC);
    }

    static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
