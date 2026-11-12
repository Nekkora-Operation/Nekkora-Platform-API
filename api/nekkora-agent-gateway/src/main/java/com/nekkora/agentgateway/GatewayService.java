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
    static final List<String> CAPABILITIES = List.of("RUN_LOCAL", "OBS_1_0", "SUPERVISOR_REPORT_1_0", "EXTERNAL_PYTHON_1_0", "RUN_REQUEST_1_0");

    /** Origem explicita (030): 1 = STUDIO_PACKAGE, 2 = EXTERNAL_PACKAGE, 3 = EXTERNAL_MONITORED (sem versao nem impressao). */
    static String sourceKind(short code) {
        return switch (code) {
            case 1 -> "STUDIO_PACKAGE";
            case 2 -> "EXTERNAL_PACKAGE";
            case 3 -> "EXTERNAL_MONITORED";
            default -> throw new IllegalStateException("source_kind " + code);
        };
    }
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
    /**
     * Intencao declarada pelo Agent no resgate (cabecalho Nekkora-Agent-Intent, PLATFORM-AGENT-V2-002 T06). {@code LEGACY}:
     * Agent anterior, que nao declara; o proposito vem so do codigo, como antes.
     */
    public enum Intent {
        LEGACY, PAIR, ROTATE;

        public static Intent of(String header) {
            if (header == null) {
                return LEGACY;
            }
            return switch (header) {
                case "pair" -> PAIR;
                case "rotate" -> ROTATE;
                default -> throw ApiException.invalid("intencao");
            };
        }
    }

    /** Compatibilidade: resgate sem intencao declarada (Agent anterior). */
    public Redeemed redeem(MachineToken code, String credentialSecret, String agentVersion, Optional<MachineToken> current) {
        return redeem(code, credentialSecret, agentVersion, current, Intent.LEGACY);
    }

    public Redeemed redeem(MachineToken code, String credentialSecret, String agentVersion, Optional<MachineToken> current, Intent intent) {
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
                // repeticao: so o MESMO segredo devolve o mesmo resultado, e so enquanto a credencial emitida ainda vale
                // (nao revogada, nao vencida, Agent ativo e associado). Nao reativa, nao desfaz rotacao, nao restaura associacao.
                try {
                    auth.authenticate(j, new MachineToken(MachineToken.CREDENTIAL, code.organization(), e.consumedCredential(), credentialSecret, null));
                } catch (ApiException refused) {
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
            // T06: o proposito so e revelado a quem tem um codigo valido (hash, prazo e organizacao ja conferidos acima).
            // Recusa por proposito trocado: nada consumido, nada alterado (o vinculo atual da maquina continua).
            if (e.purpose() == 1) {
                if (intent == Intent.ROTATE) {
                    throw new ApiException(ApiError.PAIRING_CODE_FOR_ROTATION, "codigo_de_pareamento_na_rotacao");
                }
                if (intent == Intent.LEGACY && current.isPresent()) {
                    // Agent anterior em rotacao com codigo de pareamento: antes criava outro Agent (orfao) se o Worker do
                    // codigo estivesse livre; agora e recusado como Worker indisponivel (mesma mensagem que esse Agent ja mostra)
                    throw new ApiException(ApiError.CONFLICT, "pareamento_com_credencial");
                }
                return pair(j, code.organization(), e, secretHash, agentVersion);
            }
            if (intent == Intent.PAIR) {
                throw new ApiException(ApiError.ROTATION_CODE_FOR_PAIRING, "codigo_de_rotacao_no_pareamento");
            }
            return rotate(j, code.organization(), e, secretHash, current);
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

    // ------------------------------------------------------------------ desvinculo pela maquina (PLATFORM-AGENT-V2-004, T13)

    /** Resultado do desvinculo (o mesmo corpo na repeticao da operacao). */
    public record Unlinked(UUID operationId, String outcome, UUID agentId, UUID workerId, OffsetDateTime unlinkedAt, int runRequestsClosed,
                           int runRequestsKeptAccepted) {
    }

    static final int MAX_LAUNCHED = 50;
    static final short MACHINE_UNLINK = 5;
    static final int ACT_MACHINE_UNLINK = 14;

    /**
     * Encerra o vinculo DA CREDENCIAL (docs/platform-agent-v2-004/t13-desvinculo-remoto.md, sec. 3). Autoridade: so a posse do
     * segredo; {@code expectedAgent} so confere que a maquina pede o vinculo que ela pensa ter. Idempotente por
     * {@code operationId}: a repeticao depois do commit (resposta perdida) recebe o mesmo corpo, mesmo com a credencial ja
     * revogada por esta operacao. Vinculo encerrado de outro modo: 409 com o motivo; credencial revogada por rotacao: 401.
     */
    public Unlinked unlink(MachineToken t, UUID operationId, UUID expectedAgent, List<UUID> launched) {
        if (operationId == null || expectedAgent == null || launched == null || launched.size() > MAX_LAUNCHED) {
            throw ApiException.invalid("desvinculo");
        }
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            MachineAuth.UnlinkView v = auth.authenticateForUnlink(j, t);
            if (!v.agentId().equals(expectedAgent)) {
                throw ApiException.invalid("agent");
            }
            if (v.revoked()) {
                short reason = v.revocationReason();
                if (reason == MACHINE_UNLINK) {
                    Unlinked previous = previousUnlink(j, v);
                    if (previous != null && previous.operationId().equals(operationId)) {
                        return previous; // repeticao da mesma operacao: nenhum efeito novo
                    }
                    throw new ApiException(ApiError.LINK_ENDED_BY_MACHINE, "desvinculo_outra_operacao");
                }
                if (reason == 3 || reason == 4) {
                    throw new ApiException(ApiError.LINK_ENDED_BY_ADMINISTRATION, "revogado_pela_administracao");
                }
                throw ApiException.unauthenticated("credencial_substituida"); // rotacao: o vinculo continua com outra credencial
            }
            if (v.agentStatus() != 2 || v.assignmentId() == null) {
                throw ApiException.unauthenticated("credencial"); // nao ocorre: a revogacao administrativa revoga as credenciais
            }
            return end(j, v, operationId, launched);
        });
    }

    private static Unlinked previousUnlink(JdbcClient j, MachineAuth.UnlinkView v) {
        return j.sql("SELECT operation_id, agent_id, worker_id, unlinked_at, run_requests_closed, run_requests_kept_accepted "
                        + "FROM public.agent_unlink_operations WHERE organization_id = ? AND agent_id = ? AND credential_id = ?")
                .param(v.organization()).param(v.agentId()).param(v.credentialId())
                .query((rs, n) -> new Unlinked(rs.getObject(1, UUID.class), "UNLINKED", rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getObject(4, OffsetDateTime.class), rs.getInt(5), rs.getInt(6)))
                .optional().orElse(null);
    }

    private Unlinked end(JdbcClient j, MachineAuth.UnlinkView v, UUID operationId, List<UUID> launched) {
        UUID org = v.organization();
        UUID agent = v.agentId();
        // pedidos ACEITOS que a maquina diz ter INICIADO: ficam aceitos (so os deste Agent contam; os demais sao ignorados)
        UUID[] started = launched.stream().distinct().toArray(UUID[]::new);
        int kept = j.sql("SELECT count(*) FROM public.run_requests WHERE organization_id = ? AND agent_id = ? AND state = 3 AND run_request_id = ANY (?)")
                .param(org).param(agent).param(started).query(Integer.class).single();
        // pedidos abertos: no prazo -> REFUSED (AGENT_UNLINKED); vencidos -> EXPIRED; aceitos nao iniciados -> NOT_STARTED.
        // A folga de 2 s evita recusar um pedido que vence durante a transacao (o gatilho da 030 recusaria); o que vence nela
        // fica como esta e aparece vencido pela validade (ninguem mais o le).
        int refused = j.sql("UPDATE public.run_requests SET state = 4, decision_reason = 'AGENT_UNLINKED' WHERE organization_id = ? AND agent_id = ? "
                + "AND state IN (1, 2) AND valid_until > clock_timestamp() + interval '2 seconds'").param(org).param(agent).update();
        int expired = j.sql("UPDATE public.run_requests SET state = 7 WHERE organization_id = ? AND agent_id = ? AND state IN (1, 2) "
                + "AND valid_until <= clock_timestamp()").param(org).param(agent).update();
        int notStarted = j.sql("UPDATE public.run_requests SET state = 5, decision_reason = 'LAUNCH_NOT_ATTEMPTED' WHERE organization_id = ? AND agent_id = ? "
                + "AND state = 3 AND NOT (run_request_id = ANY (?))").param(org).param(agent).param(started).update();
        OffsetDateTime at = j.sql("""
                INSERT INTO public.agent_unlink_operations (operation_id, organization_id, agent_id, worker_id, assignment_id, credential_id,
                                                            run_requests_closed, run_requests_kept_accepted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING unlinked_at
                """).param(operationId).param(org).param(agent).param(v.workerId()).param(v.assignmentId()).param(v.credentialId())
                .param(refused + expired + notStarted).param(kept).query(OffsetDateTime.class).single();
        j.sql("UPDATE public.agent_credentials SET revoked_at = clock_timestamp(), revoked_by_agent_id = agent_id, revocation_reason = 5 "
                + "WHERE organization_id = ? AND agent_id = ? AND revoked_at IS NULL").param(org).param(agent).update();
        j.sql("UPDATE public.worker_agent_assignments SET status = 2, ended_at = clock_timestamp(), ended_by_agent_id = agent_id "
                + "WHERE organization_id = ? AND assignment_id = ? AND status = 1").param(org).param(v.assignmentId()).update();
        j.sql("UPDATE public.agents SET status = 4 WHERE organization_id = ? AND agent_id = ? AND status = 2").param(org).param(agent).update();
        j.sql("INSERT INTO public.agent_admin_events (organization_id, act, actor_agent_id, agent_id, worker_id, credential_id, reason_code) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'MACHINE_UNLINK')").param(org).param((short) ACT_MACHINE_UNLINK).param(agent).param(agent)
                .param(v.workerId()).param(v.credentialId()).update();
        LOG.info("evento=agente.desvinculado_pela_maquina agente={} pedidos_encerrados={} pedidos_aceitos_mantidos={} correlacao={}", agent,
                refused + expired + notStarted, kept, RequestContext.correlation());
        return new Unlinked(operationId, "UNLINKED", agent, v.workerId(), at, refused + expired + notStarted, kept);
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

    /**
     * Autorizacao ativa do Worker. {@code sourceKind} EXTERNAL_MONITORED: {@code automationVersionId}, {@code packageFingerprint} e
     * {@code packageFingerprintRule} nulos (um Agent anterior recusa o item por nao ter impressao: falha fechada).
     */
    public record Authorization(UUID authorizationId, UUID workspaceId, UUID automationId, UUID automationVersionId, String packageFingerprint,
                                String packageFingerprintRule, OffsetDateTime validUntil, String sourceKind) {
    }

    /** Autorizacoes ATIVAS do Worker da maquina (nao revogadas, no prazo, com o Worker ainda autorizado no workspace). */
    public List<Authorization> authorizations(MachineToken t) {
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = auth.authenticate(j, t);
            return j.sql("""
                    SELECT z.authorization_id, z.workspace_id, z.automation_id, z.automation_version_id, z.package_fingerprint,
                           z.package_fingerprint_rule, z.valid_until, z.source_kind
                      FROM public.local_run_authorizations z
                      JOIN public.worker_workspace_assignments x ON x.organization_id = z.organization_id
                                                                 AND x.assignment_id = z.worker_workspace_assignment_id AND x.status = 1
                     WHERE z.organization_id = ? AND z.worker_id = ? AND z.revoked_at IS NULL AND z.valid_until > clock_timestamp()
                     ORDER BY z.granted_at, z.authorization_id
                    """).param(m.organization()).param(m.workerId())
                    .query((rs, n) -> new Authorization(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                            rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class),
                            sourceKind(rs.getShort(8))))
                    .list();
        });
    }

    // ------------------------------------------------------------------ registro e relatorio do supervisor

    public record Registered(UUID runId, boolean created, String state, String reportOutcome) {
    }

    private record RunRow(UUID runId, UUID authorizationId, String fingerprint, UUID runRequestId, SupervisorReport report) {
    }

    private record AuthRow(UUID workspaceId, UUID automationId, UUID versionId, UUID workerId, String fingerprint, boolean revoked, boolean expired,
                           short sourceKind) {
    }

    /**
     * Registro idempotente por (Agent, agent_execution_id). O mesmo id com outra autorizacao, pacote ou pedido: 409.
     * {@code packageFingerprint} ausente se e somente se a autorizacao for de monitoramento (030); {@code runRequestId}: execucao
     * de um pedido ACEITO por esta maquina com o mesmo {@code agentExecutionId}.
     */
    public Registered register(MachineToken t, String agentExecutionId, UUID authorizationId, String packageFingerprint, SupervisorReport report) {
        return register(t, agentExecutionId, authorizationId, packageFingerprint, null, report);
    }

    public Registered register(MachineToken t, String agentExecutionId, UUID authorizationId, String packageFingerprint, UUID runRequestId,
                               SupervisorReport report) {
        if (agentExecutionId == null || !EXECUTION.matcher(agentExecutionId).matches() || authorizationId == null
                || packageFingerprint != null && !SHA.matcher(packageFingerprint).matches()) {
            throw ApiException.invalid("registro");
        }
        try {
            return registerOnce(t, agentExecutionId, authorizationId, packageFingerprint, runRequestId, report);
        } catch (DuplicateKeyException race) {
            // dois registros concorrentes do mesmo agent_execution_id: o segundo ve o primeiro e segue o caminho idempotente
            return registerOnce(t, agentExecutionId, authorizationId, packageFingerprint, runRequestId, report);
        }
    }

    private Registered registerOnce(MachineToken t, String agentExecutionId, UUID authorizationId, String packageFingerprint, UUID runRequestId,
                                    SupervisorReport report) {
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = auth.authenticate(j, t);
            RunRow existing = lockRun(j, m, agentExecutionId);
            if (existing != null) {
                if (!existing.authorizationId().equals(authorizationId) || !java.util.Objects.equals(existing.fingerprint(), packageFingerprint)
                        || !java.util.Objects.equals(existing.runRequestId(), runRequestId)) {
                    throw new ApiException(ApiError.CONFLICT, "registro_divergente");
                }
                Outcome o = applyReport(j, m, existing, report);
                State now = o == Outcome.APPLIED ? report.state() : existing.report().state();
                return new Registered(existing.runId(), false, now.name(), o.name());
            }
            AuthRow z = j.sql("""
                    SELECT z.workspace_id, z.automation_id, z.automation_version_id, z.worker_id, z.package_fingerprint,
                           z.revoked_at IS NOT NULL OR x.status <> 1, z.valid_until <= clock_timestamp(), z.source_kind
                      FROM public.local_run_authorizations z
                      JOIN public.worker_workspace_assignments x ON x.organization_id = z.organization_id AND x.assignment_id = z.worker_workspace_assignment_id
                     WHERE z.organization_id = ? AND z.authorization_id = ?
                    """).param(m.organization()).param(authorizationId)
                    .query((rs, n) -> new AuthRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                            rs.getObject(4, UUID.class), rs.getString(5), rs.getBoolean(6), rs.getBoolean(7), rs.getShort(8)))
                    .optional().orElse(null);
            if (z == null || !z.workerId().equals(m.workerId())) {
                throw ApiException.forbidden("autorizacao");
            }
            // monitoramento: sem impressao; pacote: a impressao da autorizacao (nunca adivinhada pelo nome)
            if (!java.util.Objects.equals(z.fingerprint(), packageFingerprint)) {
                throw ApiException.forbidden("pacote");
            }
            if (runRequestId != null) {
                Boolean accepted = j.sql("""
                        SELECT state = 3 AND agent_id = ? AND authorization_id = ? AND agent_execution_id = ?
                          FROM public.run_requests WHERE organization_id = ? AND run_request_id = ? FOR UPDATE
                        """).param(m.agentId()).param(authorizationId).param(agentExecutionId).param(m.organization()).param(runRequestId)
                        .query(Boolean.class).optional().orElse(null);
                if (accepted == null) {
                    throw new ApiException(ApiError.NOT_FOUND, "pedido");
                }
                if (!accepted) {
                    throw new ApiException(ApiError.CONFLICT, "pedido_divergente");
                }
            }
            short authState = z.revoked() ? (short) 2 : z.expired() ? (short) 3 : (short) 1;
            UUID runId = j.sql("""
                    INSERT INTO public.agent_runs (organization_id, workspace_id, automation_id, automation_version_id, authorization_id, agent_id, worker_id,
                        worker_agent_assignment_id, agent_execution_id, package_fingerprint, authorization_state_at_registration, supervisor_state,
                        supervisor_code, exit_code, supervisor_counts, agent_created_at, agent_started_at, agent_ended_at, adopted, suspensions, locks,
                        source_kind, origin, run_request_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING agent_run_id
                    """).param(m.organization()).param(z.workspaceId()).param(z.automationId()).param(z.versionId()).param(authorizationId)
                    .param(m.agentId()).param(m.workerId()).param(m.assignmentId()).param(agentExecutionId).param(packageFingerprint).param(authState)
                    .param(report.state().code()).param(report.code()).param(report.exitCode()).param(countsJson(report)).param(ts(report.created()))
                    .param(ts(report.started())).param(ts(report.ended())).param(report.adopted()).param(report.suspensions()).param(report.locks())
                    .param(z.sourceKind()).param(runRequestId == null ? (short) 1 : (short) 2).param(runRequestId)
                    .query(UUID.class).single();
            if (runRequestId != null) {
                j.sql("UPDATE public.run_requests SET state = 6, agent_run_id = ? WHERE organization_id = ? AND run_request_id = ?")
                        .param(runId).param(m.organization()).param(runRequestId).update();
            }
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
                       agent_created_at, agent_started_at, agent_ended_at, adopted, suspensions, locks, run_request_id
                  FROM public.agent_runs WHERE organization_id = ? AND agent_id = ? AND agent_execution_id = ? FOR UPDATE
                """).param(m.organization()).param(m.agentId()).param(agentExecutionId)
                .query((rs, n) -> new RunRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(14, UUID.class),
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

    // ------------------------------------------------------------------ pedido de execucao (030)

    static final List<String> REFUSAL_REASONS = List.of("PACKAGE_NOT_PRESENT", "PACKAGE_NOT_APPROVED", "AUTHORIZATION_UNUSABLE",
            "ENVIRONMENT_INCOMPATIBLE", "BUSY", "EXPIRED");
    static final List<String> REQUEST_STATES = List.of("REQUESTED", "DELIVERED", "ACCEPTED", "REFUSED", "NOT_STARTED", "RUN_LINKED", "EXPIRED",
            "REVOKED");
    static final int MAX_PENDING = 50;

    /**
     * Pedido entregue a maquina: so identificadores, validade e o pacote esperado. Nenhum comando, caminho, argumento ou segredo.
     * {@code state}: REQUESTED/DELIVERED (aguardando decisao) ou ACCEPTED (decisao ja registrada; a maquina reconcilia com o
     * seu registro local).
     */
    public record PendingRequest(UUID runRequestId, UUID authorizationId, UUID workspaceId, UUID automationId, UUID automationVersionId,
                                 String sourceKind, String packageFingerprint, String packageFingerprintRule, OffsetDateTime requestedAt,
                                 OffsetDateTime validUntil, String state, String agentExecutionId) {
    }

    /**
     * Pedidos desta maquina ainda sem desfecho de decisao. Os vencidos viram EXPIRED aqui (instante do servidor); os entregues
     * pela primeira vez viram DELIVERED. Entregar NAO e iniciar.
     */
    public List<PendingRequest> runRequests(MachineToken t) {
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = auth.authenticate(j, t);
            j.sql("UPDATE public.run_requests SET state = 7 WHERE organization_id = ? AND agent_id = ? AND state IN (1, 2) "
                    + "AND valid_until <= clock_timestamp()").param(m.organization()).param(m.agentId()).update();
            List<PendingRequest> items = j.sql("""
                    SELECT run_request_id, authorization_id, workspace_id, automation_id, automation_version_id, source_kind, package_fingerprint,
                           package_fingerprint_rule, requested_at, valid_until, state, agent_execution_id
                      FROM public.run_requests
                     WHERE organization_id = ? AND agent_id = ? AND state IN (1, 2, 3)
                     ORDER BY requested_at, run_request_id LIMIT ?
                    """).param(m.organization()).param(m.agentId()).param(MAX_PENDING)
                    .query((rs, n) -> new PendingRequest(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                            rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), sourceKind(rs.getShort(6)), rs.getString(7), rs.getString(8),
                            rs.getObject(9, OffsetDateTime.class), rs.getObject(10, OffsetDateTime.class),
                            REQUEST_STATES.get(rs.getShort(11) - 1), rs.getString(12)))
                    .list();
            for (PendingRequest p : items) {
                if (!"ACCEPTED".equals(p.state())) {
                    j.sql("UPDATE public.run_requests SET state = 2, deliveries = deliveries + 1 WHERE organization_id = ? AND run_request_id = ?")
                            .param(m.organization()).param(p.runRequestId()).update();
                }
            }
            return items.stream().map(p -> "REQUESTED".equals(p.state())
                    ? new PendingRequest(p.runRequestId(), p.authorizationId(), p.workspaceId(), p.automationId(), p.automationVersionId(), p.sourceKind(),
                            p.packageFingerprint(), p.packageFingerprintRule(), p.requestedAt(), p.validUntil(), "DELIVERED", p.agentExecutionId())
                    : p).toList();
        });
    }

    /**
     * Resultado de uma decisao. {@code outcome}: APPLIED (gravada agora), UNCHANGED (repeticao identica), EXPIRED, REVOKED ou
     * CONFLICT (decisao diferente da registrada); os tres ultimos respondem 409 e nada muda alem do vencimento.
     */
    public record DecisionResult(UUID runRequestId, String outcome, String state, String agentExecutionId) {
    }

    private record RequestRow(short state, UUID agentId, String executionId, String reason, boolean expired) {
    }

    /** Decisao da maquina sobre um pedido seu: ACCEPTED (com o agentExecutionId reservado), REFUSED (motivo) ou NOT_STARTED. */
    public DecisionResult decide(MachineToken t, UUID runRequestId, String decision, String reason, String agentExecutionId) {
        boolean accept = "ACCEPTED".equals(decision);
        boolean refuse = "REFUSED".equals(decision);
        boolean notStarted = "NOT_STARTED".equals(decision);
        if (runRequestId == null || !(accept || refuse || notStarted)
                || accept && (agentExecutionId == null || !EXECUTION.matcher(agentExecutionId).matches() || reason != null)
                || refuse && (reason == null || !REFUSAL_REASONS.contains(reason) || agentExecutionId != null)
                || notStarted && (agentExecutionId == null || !EXECUTION.matcher(agentExecutionId).matches()
                                  || reason != null && !"LAUNCH_NOT_ATTEMPTED".equals(reason))) {
            throw ApiException.invalid("decisao");
        }
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = auth.authenticate(j, t);
            RequestRow q = j.sql("""
                    SELECT state, agent_id, agent_execution_id, decision_reason, valid_until <= clock_timestamp()
                      FROM public.run_requests WHERE organization_id = ? AND run_request_id = ? FOR UPDATE
                    """).param(m.organization()).param(runRequestId)
                    .query((rs, n) -> new RequestRow(rs.getShort(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getBoolean(5)))
                    .optional().orElse(null);
            if (q == null || !q.agentId().equals(m.agentId())) {
                // pedido de outra maquina (ou inexistente): nunca revelado
                throw new ApiException(ApiError.NOT_FOUND, "pedido");
            }
            String now = REQUEST_STATES.get(q.state() - 1);
            if (q.state() == 8) {
                return new DecisionResult(runRequestId, "REVOKED", now, q.executionId());
            }
            if (q.state() == 7) {
                return new DecisionResult(runRequestId, "EXPIRED", now, q.executionId());
            }
            if (accept) {
                if (q.state() == 1 || q.state() == 2) {
                    if (q.expired()) {
                        j.sql("UPDATE public.run_requests SET state = 7 WHERE organization_id = ? AND run_request_id = ?").param(m.organization())
                                .param(runRequestId).update();
                        return new DecisionResult(runRequestId, "EXPIRED", "EXPIRED", null);
                    }
                    j.sql("UPDATE public.run_requests SET state = 3, agent_execution_id = ? WHERE organization_id = ? AND run_request_id = ?")
                            .param(agentExecutionId).param(m.organization()).param(runRequestId).update();
                    LOG.info("evento=agente.pedido_aceito pedido={} correlacao={}", runRequestId, RequestContext.correlation());
                    return new DecisionResult(runRequestId, "APPLIED", "ACCEPTED", agentExecutionId);
                }
                boolean same = (q.state() == 3 || q.state() == 5 || q.state() == 6) && agentExecutionId.equals(q.executionId());
                return new DecisionResult(runRequestId, same ? "UNCHANGED" : "CONFLICT", now, q.executionId());
            }
            if (refuse) {
                if (q.state() == 1 || q.state() == 2) {
                    if (q.expired()) {
                        j.sql("UPDATE public.run_requests SET state = 7 WHERE organization_id = ? AND run_request_id = ?").param(m.organization())
                                .param(runRequestId).update();
                        return new DecisionResult(runRequestId, "EXPIRED", "EXPIRED", null);
                    }
                    j.sql("UPDATE public.run_requests SET state = 4, decision_reason = ? WHERE organization_id = ? AND run_request_id = ?")
                            .param(reason).param(m.organization()).param(runRequestId).update();
                    LOG.info("evento=agente.pedido_recusado pedido={} motivo={} correlacao={}", runRequestId, reason, RequestContext.correlation());
                    return new DecisionResult(runRequestId, "APPLIED", "REFUSED", null);
                }
                boolean same = q.state() == 4 && reason.equals(q.reason());
                return new DecisionResult(runRequestId, same ? "UNCHANGED" : "CONFLICT", now, q.executionId());
            }
            // NOT_STARTED: aceito e o processo comprovadamente nao criado
            if (q.state() == 3 && agentExecutionId.equals(q.executionId())) {
                j.sql("UPDATE public.run_requests SET state = 5, decision_reason = 'LAUNCH_NOT_ATTEMPTED' WHERE organization_id = ? AND run_request_id = ?")
                        .param(m.organization()).param(runRequestId).update();
                return new DecisionResult(runRequestId, "APPLIED", "NOT_STARTED", q.executionId());
            }
            boolean same = q.state() == 5 && agentExecutionId.equals(q.executionId());
            return new DecisionResult(runRequestId, same ? "UNCHANGED" : "CONFLICT", now, q.executionId());
        });
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
