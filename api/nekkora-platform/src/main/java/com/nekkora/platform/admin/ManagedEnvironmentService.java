package com.nekkora.platform.admin;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.remote.ExternalPrerequisites;
import com.nekkora.core.remote.ManagedLock;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.platform.access.WorkspaceAccess;
import com.nekkora.platform.admin.AccessActions.Failure;
import com.nekkora.platform.session.ValidSession;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * AMBIENTE-PYTHON-001 (076) - o ambiente Python POR EXECUCAO, lado das pessoas. Contrato: docs/ambiente-python-001/CONTRATO.md.
 *
 * <p>O Python preparado continua sendo o padrao. Para uma versao rodar num ambiente temporario numa maquina:
 * <ol>
 * <li>a pessoa pede que a MAQUINA resolva a lista completa (D1) da lista efetiva DAQUELA versao, para a INSTALACAO Python
 * escolhida (D6), so com wheels (D2) e no PyPI publico (D3);</li>
 * <li>ve o lock (nome, versao, sha256, arquivo), as dependencias indiretas e os preparos externos (D5), e ACEITA, com a
 * confirmacao reforcada, aquele lock naquela maquina - escolhendo quais navegadores do Playwright a maquina deve conferir;</li>
 * <li>a solicitacao MANAGED so e aceita com o aceite VIGENTE e ainda correspondente (mesma revisao das dependencias, mesmo
 * Python base); lock, revisao ou Python diferentes pedem aceite novo.</li>
 * </ol>
 * Com a chave NEKKORA_AMBIENTE_GERENCIADO desligada, tudo aqui responde 404 managed_environment_unavailable.
 */
public class ManagedEnvironmentService {

    private static final Logger LOG = LoggerFactory.getLogger(ManagedEnvironmentService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final int ACT_AUTHORIZED = 13;
    static final int ACT_REVOKED = 14;
    static final String CAPABILITY = "MANAGED_ENV_1_0";
    static final List<String> OUTCOMES = List.of("RESOLVED", "INCOMPATIBLE", "ENVIRONMENT_GONE", "NOT_RESOLVED");
    static final int RESOLUTION_ANSWER_SECONDS = 600;

    public record BasePython(String environmentId, String pythonVersion, String kind, boolean inInventory) {
    }

    public record Requirements(int revision, List<String> dependencies, boolean resolved) {
    }

    /** {@code declared}: o item esta na lista efetiva; falso = dependencia indireta, resolvida pela maquina. */
    public record LockItem(String name, String version, String sha256, String fileName, boolean declared) {
    }

    public record Failure2(String requirement, String phase, String summary) {
    }

    /**
     * {@code state}: PENDING (aguardando a maquina, no prazo), ANSWERED ou UNANSWERED (o prazo venceu: nada foi decidido).
     * {@code current}: a resolucao ainda e desta versao, da revisao vigente e do Python escolhido agora.
     */
    public record Resolution(UUID resolutionId, UUID automationVersionId, String state, String outcome, OffsetDateTime requestedAt,
                             OffsetDateTime answerValidUntil, OffsetDateTime answeredAt, String pythonVersion, String pipVersion, List<LockItem> lock,
                             String lockSha256, Failure2 failure, boolean current) {
    }

    public record Prerequisite(String code, String packageName, List<String> checks) {
    }

    public record Change(String name, String from, String to) {
    }

    public record Diff(List<String> added, List<String> removed, List<Change> changed) {
    }

    /** {@code status}: CURRENT ou STALE (com os motivos). {@code diff}: so em STALE, contra o lock da resolucao corrente. */
    public record Authorization(UUID authorizationId, String status, List<String> staleReasons, String lockSha256, int packages, List<String> checks,
                                OffsetDateTime grantedAt, int requirementsRevision, Diff diff) {
    }

    public record View(boolean available, boolean machineCompatible, boolean baseEligible, BasePython basePython, Requirements requirements,
                       Resolution resolution, List<Prerequisite> externalPrerequisites, Authorization authorization) {
    }

    /** Por maquina, na lista de candidatas da versao: o que a tela precisa para a coluna "Ambiente". */
    public record CandidateEnvironment(boolean compatible, String authorized) {
    }

    private final AccessActions actions;
    private final WorkspaceAccess access;
    private final boolean enabled;
    private final int answerSeconds;

    /** {@code enabled}: NEKKORA_AMBIENTE_GERENCIADO E a execucao remota ligadas. {@code answerSeconds}: prazo da resolucao (60 a 1800). */
    public ManagedEnvironmentService(AccessActions actions, WorkspaceAccess access, boolean enabled, int answerSeconds) {
        this.actions = actions;
        this.access = access;
        this.enabled = enabled;
        this.answerSeconds = Math.max(60, Math.min(answerSeconds, 1800));
    }

    public boolean enabled() {
        return enabled;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ApiException(ApiError.MANAGED_ENVIRONMENT_UNAVAILABLE, "ambiente_gerenciado_desligado");
        }
    }

    // ------------------------------------------------------------------ leitura

    public View view(UUID org, ValidSession actor, String token, UUID automation, UUID agent, UUID versionId) {
        requireEnabled();
        if (versionId == null) {
            throw ApiException.invalid("versao");
        }
        return actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            UUID workspace = access.requireAutomation(j, org, actor.membershipId(), automation, "automation.read");
            requireMachine(j, org, workspace, agent);
            requirePublished(j, org, automation, versionId);
            return view(j, org, automation, agent, versionId);
        });
    }

    private static View view(JdbcClient j, UUID org, UUID automation, UUID agent, UUID versionId) {
        BasePython base = base(j, org, automation, agent);
        RuntimeRequirementsService.View r = RuntimeRequirementsService.view(j, org, automation, versionId);
        Requirements req = r.declared() == null ? null : new Requirements(r.declared().revision(), r.effective().dependencies(), r.effective().resolved());
        Resolution res = latest(j, org, automation, agent, versionId, base, req);
        Authorization auth = authorization(j, org, automation, agent, versionId, base, req, res);
        List<String> names = new ArrayList<>();
        if (res != null && res.lock() != null) {
            res.lock().forEach(i -> names.add(i.name()));
        } else if (req != null) {
            req.dependencies().forEach(d -> names.add(d.split("[\\[=]", 2)[0]));
        }
        List<Prerequisite> pre = ExternalPrerequisites.of(names).stream().map(p -> new Prerequisite(p.code(), p.packageName(), p.checks())).toList();
        return new View(true, compatible(j, agent), base != null && base.inInventory() && "INSTALLATION".equals(base.kind()), base, req, res, pre, auth);
    }

    static boolean compatible(JdbcClient j, UUID agent) {
        return Boolean.TRUE.equals(j.sql("SELECT coalesce(p.capabilities @> ARRAY['" + CAPABILITY + "']::text[], false) FROM public.agent_presence p "
                + "WHERE p.agent_id = ?").param(agent).query(Boolean.class).optional().orElse(false));
    }

    private static BasePython base(JdbcClient j, UUID org, UUID automation, UUID agent) {
        return j.sql("""
                SELECT s.environment_id, coalesce(e.python_version, s.python_version), e.kind, e.agent_id IS NOT NULL
                  FROM public.automation_python_selections s
                  LEFT JOIN public.agent_python_environments e ON e.agent_id = s.agent_id AND e.environment_id = s.environment_id
                 WHERE s.organization_id = ? AND s.automation_id = ? AND s.agent_id = ?
                """).param(org).param(automation).param(agent).query((rs, n) -> new BasePython(rs.getString(1), rs.getString(2),
                rs.getObject(3) == null ? null : rs.getShort(3) == 1 ? "INSTALLATION" : "VIRTUAL_ENVIRONMENT", rs.getBoolean(4))).optional().orElse(null);
    }

    private static List<LockItem> lockItems(String json, List<String> declared) {
        if (json == null) {
            return null;
        }
        List<String> names = ManagedLock.declaredNames(declared);
        List<LockItem> out = new ArrayList<>();
        JSON.readTree(json).forEach(i -> out.add(new LockItem(i.get("name").asString(), i.get("version").asString(), i.get("sha256").asString(),
                i.get("fileName").asString(), names.contains(ManagedLock.normalize(i.get("name").asString())))));
        return List.copyOf(out);
    }

    private static List<String> strings(String json) {
        List<String> out = new ArrayList<>();
        JSON.readTree(json).forEach(d -> out.add(d.asString()));
        return List.copyOf(out);
    }

    /** A resolucao mais recente desta automacao nesta maquina (de qualquer versao; {@code current} diz se ainda vale). */
    private static Resolution latest(JdbcClient j, UUID org, UUID automation, UUID agent, UUID versionId, BasePython base, Requirements req) {
        return j.sql("""
                SELECT resolution_id, automation_version_id, outcome, requested_at, answer_valid_until, answered_at, python_version, pip_version, lock::text,
                       lock_sha256, failure_requirement, failure_phase, failure_summary, answer_valid_until > clock_timestamp(), environment_id,
                       requirements_revision, dependencies::text
                  FROM public.managed_environment_resolutions
                 WHERE organization_id = ? AND automation_id = ? AND agent_id = ? ORDER BY requested_at DESC, resolution_id LIMIT 1
                """).param(org).param(automation).param(agent).query((rs, n) -> {
            Short outcome = rs.getObject(3) == null ? null : rs.getShort(3);
            String state = outcome != null ? "ANSWERED" : rs.getBoolean(14) ? "PENDING" : "UNANSWERED";
            List<String> asked = strings(rs.getString(17));
            boolean current = rs.getObject(2, UUID.class).equals(versionId) && base != null && rs.getString(15).equals(base.environmentId())
                    && req != null && rs.getInt(16) == req.revision() && asked.equals(req.dependencies());
            Failure2 f = rs.getString(12) == null ? null : new Failure2(rs.getString(11), rs.getString(12), rs.getString(13));
            return new Resolution(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), state, outcome == null ? null : OUTCOMES.get(outcome - 1),
                    rs.getObject(4, OffsetDateTime.class), rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class), rs.getString(7),
                    rs.getString(8), lockItems(rs.getString(9), asked), rs.getString(10), f, current);
        }).optional().orElse(null);
    }

    private record Granted(UUID id, String environment, int revision, String lockSha, String checks, OffsetDateTime at, String lock) {
    }

    private static Granted granted(JdbcClient j, UUID org, UUID agent, UUID versionId) {
        return j.sql("""
                SELECT g.authorization_id, g.environment_id, g.requirements_revision, g.lock_sha256, g.checks::text, g.granted_at, r.lock::text
                  FROM public.managed_environment_authorizations g
                  JOIN public.managed_environment_resolutions r ON r.organization_id = g.organization_id AND r.resolution_id = g.resolution_id
                 WHERE g.organization_id = ? AND g.agent_id = ? AND g.automation_version_id = ? AND g.revoked_at IS NULL
                """).param(org).param(agent).param(versionId).query((rs, n) -> new Granted(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class), rs.getString(7))).optional().orElse(null);
    }

    private static List<String> staleReasons(Granted g, BasePython base, Requirements req) {
        List<String> out = new ArrayList<>();
        if (req == null || g.revision() != req.revision()) {
            out.add("REQUIREMENTS_CHANGED");
        }
        if (base == null || !g.environment().equals(base.environmentId()) || !base.inInventory() || !"INSTALLATION".equals(base.kind())) {
            out.add("PYTHON_CHANGED");
        }
        return List.copyOf(out);
    }

    private static Authorization authorization(JdbcClient j, UUID org, UUID automation, UUID agent, UUID versionId, BasePython base, Requirements req,
                                               Resolution res) {
        Granted g = granted(j, org, agent, versionId);
        if (g == null) {
            return null;
        }
        List<String> reasons = staleReasons(g, base, req);
        Diff diff = null;
        if (!reasons.isEmpty() && res != null && res.current() && "RESOLVED".equals(res.outcome())) {
            diff = diff(lockItems(g.lock(), List.of()), res.lock());
        }
        return new Authorization(g.id(), reasons.isEmpty() ? "CURRENT" : "STALE", reasons, g.lockSha(), JSON.readTree(g.lock()).size(), strings(g.checks()),
                g.at(), g.revision(), diff);
    }

    static Diff diff(List<LockItem> before, List<LockItem> after) {
        Map<String, String> a = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        before.forEach(i -> a.put(ManagedLock.normalize(i.name()), i.version()));
        Map<String, String> b = new LinkedHashMap<>();
        after.forEach(i -> {
            b.put(ManagedLock.normalize(i.name()), i.version());
            names.put(ManagedLock.normalize(i.name()), i.name());
        });
        before.forEach(i -> names.putIfAbsent(ManagedLock.normalize(i.name()), i.name()));
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<Change> changed = new ArrayList<>();
        b.forEach((n, v) -> {
            if (!a.containsKey(n)) {
                added.add(names.get(n) + "==" + v);
            } else if (!a.get(n).equals(v)) {
                changed.add(new Change(names.get(n), a.get(n), v));
            }
        });
        a.forEach((n, v) -> {
            if (!b.containsKey(n)) {
                removed.add(names.get(n) + "==" + v);
            }
        });
        return new Diff(List.copyOf(added), List.copyOf(removed), List.copyOf(changed));
    }

    /** Para a lista de candidatas: a maquina e compativel? Ha aceite (vigente e ainda correspondente, ou desatualizado)? */
    static CandidateEnvironment candidate(JdbcClient j, UUID org, UUID automation, UUID agent, UUID versionId) {
        Granted g = granted(j, org, agent, versionId);
        String authorized = "NONE";
        if (g != null) {
            RuntimeRequirementsService.View r = RuntimeRequirementsService.view(j, org, automation, versionId);
            Requirements req = r.declared() == null ? null : new Requirements(r.declared().revision(), r.effective().dependencies(), r.effective().resolved());
            authorized = staleReasons(g, base(j, org, automation, agent), req).isEmpty() ? "CURRENT" : "STALE";
        }
        return new CandidateEnvironment(compatible(j, agent), authorized);
    }

    // ------------------------------------------------------------------ pedir a resolucao

    public View requestResolution(UUID org, ValidSession actor, String token, UUID automation, UUID agent, UUID versionId) {
        requireEnabled();
        if (versionId == null) {
            throw ApiException.invalid("versao");
        }
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            UUID workspace = access.requireAutomation(j, org, actor.membershipId(), automation, "automation.manage");
            requireMachine(j, org, workspace, agent);
            requirePublished(j, org, automation, versionId);
            if (!compatible(j, agent)) {
                throw new ApiException(ApiError.MANAGED_ENVIRONMENT_MACHINE_INCOMPATIBLE, "maquina_sem_ambiente_gerenciado");
            }
            BasePython base = requireBase(j, org, automation, agent);
            RuntimeRequirementsService.View r = RuntimeRequirementsService.view(j, org, automation, versionId);
            if (r.declared() == null) {
                throw new ApiException(ApiError.REMOTE_REQUIREMENTS_NOT_DECLARED, "dependencias_nao_declaradas");
            }
            if (!r.effective().resolved()) {
                throw new ApiException(ApiError.REMOTE_REQUIREMENTS_CONFLICT, "dependencias_em_conflito");
            }
            // um pedido em aberto por automacao e maquina (o gatilho reconfere)
            j.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 76))").param(agent + "|" + automation).query(rs -> {
            });
            if (j.sql("SELECT count(*) FROM public.managed_environment_resolutions WHERE organization_id = ? AND automation_id = ? AND agent_id = ? "
                    + "AND outcome IS NULL AND answer_valid_until > clock_timestamp()").param(org).param(automation).param(agent).query(Long.class).single() > 0) {
                throw new ApiException(ApiError.MANAGED_ENVIRONMENT_RESOLUTION_PENDING, "resolucao_em_aberto");
            }
            ArrayNode frozen = JSON.createArrayNode();
            r.effective().dependencies().forEach(frozen::add);
            try {
                j.sql("""
                        INSERT INTO public.managed_environment_resolutions (organization_id, workspace_id, automation_id, automation_version_id, agent_id,
                            environment_id, requirements_revision, dependencies, requested_by_membership_id, answer_valid_until)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, clock_timestamp() + make_interval(secs => ?))
                        """).param(org).param(workspace).param(automation).param(versionId).param(agent).param(base.environmentId())
                        .param(r.declared().revision()).param(frozen.toString()).param(actor.membershipId()).param(answerSeconds).update();
            } catch (DataAccessException e) {
                throw new ApiException(ApiError.CONFLICT, "condicoes_mudaram");
            }
            LOG.info("evento=ambiente_gerenciado.resolucao_pedida automacao={} versao={} maquina={} correlacao={}", automation, versionId, agent,
                    AccessActions.correlation());
            return view(j, org, automation, agent, versionId);
        });
    }

    // ------------------------------------------------------------------ aceitar e revogar

    public View authorize(UUID org, ValidSession actor, String token, UUID automation, UUID agent, UUID versionId, UUID resolutionId, String lockSha256,
                          List<String> checks, boolean acknowledge) {
        requireEnabled();
        if (versionId == null || resolutionId == null || lockSha256 == null || !lockSha256.matches("[0-9a-f]{64}") || checks == null || checks.size() > 8
                || !ExternalPrerequisites.CHECKS.containsAll(checks) || checks.stream().distinct().count() != checks.size() || !acknowledge) {
            throw ApiException.invalid("aceite");
        }
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            UUID workspace = access.requireAutomation(j, org, actor.membershipId(), automation, "automation.manage");
            actions.requireStepUp(j, actor);
            actions.lockOrganizationAndActor(j, org, actor.userId());
            UUID worker = requireMachine(j, org, workspace, agent);
            requirePublished(j, org, automation, versionId);
            if (!compatible(j, agent)) {
                throw new ApiException(ApiError.MANAGED_ENVIRONMENT_MACHINE_INCOMPATIBLE, "maquina_sem_ambiente_gerenciado");
            }
            BasePython base = requireBase(j, org, automation, agent);
            RuntimeRequirementsService.View r = RuntimeRequirementsService.view(j, org, automation, versionId);
            Requirements req = r.declared() == null ? null : new Requirements(r.declared().revision(), r.effective().dependencies(), r.effective().resolved());
            Resolution res = latest(j, org, automation, agent, versionId, base, req);
            if (res == null || !res.resolutionId().equals(resolutionId) || !res.current() || !"RESOLVED".equals(res.outcome())) {
                throw new ApiException(ApiError.MANAGED_ENVIRONMENT_RESOLUTION_NOT_CURRENT, "resolucao_nao_corrente");
            }
            if (!lockSha256.equals(res.lockSha256())) {
                throw new ApiException(ApiError.MANAGED_ENVIRONMENT_LOCK_CHANGED, "lock_diferente");
            }
            List<String> lockNames = res.lock().stream().map(LockItem::name).toList();
            boolean browsers = checks.stream().anyMatch(c -> c.startsWith("PLAYWRIGHT_"));
            if (ExternalPrerequisites.needsBrowserChoice(lockNames) != browsers) {
                // com playwright, ao menos um navegador a conferir; sem ele, nenhuma verificacao de navegador
                throw ApiException.invalid("verificacoes");
            }
            ArrayNode c = JSON.createArrayNode();
            checks.forEach(c::add);
            j.sql("UPDATE public.managed_environment_authorizations SET revoked_at = clock_timestamp(), revoked_by_membership_id = ?, revocation_reason = 'REPLACED' "
                    + "WHERE organization_id = ? AND agent_id = ? AND automation_version_id = ? AND revoked_at IS NULL").param(actor.membershipId()).param(org)
                    .param(agent).param(versionId).update();
            UUID id;
            try {
                id = j.sql("""
                        INSERT INTO public.managed_environment_authorizations (organization_id, workspace_id, automation_id, automation_version_id, agent_id,
                            worker_id, environment_id, requirements_revision, resolution_id, lock_sha256, checks, granted_by_membership_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?) RETURNING authorization_id
                        """).param(org).param(workspace).param(automation).param(versionId).param(agent).param(worker).param(base.environmentId())
                        .param(req.revision()).param(resolutionId).param(lockSha256).param(c.toString()).param(actor.membershipId()).query(UUID.class).single();
            } catch (DataAccessException e) {
                throw new ApiException(ApiError.CONFLICT, "condicoes_mudaram");
            }
            audit(j, org, actor, workspace, automation, ACT_AUTHORIZED);
            LOG.info("evento=ambiente_gerenciado.aceito aceite={} versao={} maquina={} pacotes={} correlacao={}", id, versionId, agent, res.lock().size(),
                    AccessActions.correlation());
            return view(j, org, automation, agent, versionId);
        });
    }

    /** Volta ao Python preparado: revoga o aceite vigente desta versao nesta maquina. Sem aceite vigente: 404. */
    public View revoke(UUID org, ValidSession actor, String token, UUID automation, UUID agent, UUID versionId) {
        requireEnabled();
        if (versionId == null) {
            throw ApiException.invalid("versao");
        }
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            UUID workspace = access.requireAutomation(j, org, actor.membershipId(), automation, "automation.manage");
            actions.requireStepUp(j, actor);
            requireMachine(j, org, workspace, agent);
            int n = j.sql("UPDATE public.managed_environment_authorizations SET revoked_at = clock_timestamp(), revoked_by_membership_id = ?, "
                    + "revocation_reason = 'BACK_TO_PREPARED' WHERE organization_id = ? AND automation_id = ? AND agent_id = ? AND automation_version_id = ? "
                    + "AND revoked_at IS NULL").param(actor.membershipId()).param(org).param(automation).param(agent).param(versionId).update();
            if (n == 0) {
                throw AccessActions.refuse(Failure.TARGET_NOT_FOUND, "aceite");
            }
            audit(j, org, actor, workspace, automation, ACT_REVOKED);
            LOG.info("evento=ambiente_gerenciado.revogado versao={} maquina={} correlacao={}", versionId, agent, AccessActions.correlation());
            return view(j, org, automation, agent, versionId);
        });
    }

    private static void audit(JdbcClient j, UUID org, ValidSession actor, UUID workspace, UUID automation, int act) {
        j.sql("INSERT INTO public.automation_admin_events (organization_id, act, actor_membership_id, workspace_id, automation_id, correlation_id) "
                + "VALUES (?, ?, ?, ?, ?, ?)").param(org).param((short) act).param(actor.membershipId()).param(workspace).param(automation)
                .param(AccessActions.correlation()).update();
    }

    // ------------------------------------------------------------------ apoio (tambem para a solicitacao MANAGED)

    /** A maquina habilitada, com o Worker autorizado no ambiente da automacao; devolve o Worker. Fora disso: 404. */
    static UUID requireMachine(JdbcClient j, UUID org, UUID workspace, UUID agent) {
        return j.sql("""
                SELECT wa.worker_id FROM public.agents a
                  JOIN public.agent_remote_enablements r ON r.organization_id = a.organization_id AND r.agent_id = a.agent_id
                  JOIN public.worker_agent_assignments wa ON wa.organization_id = a.organization_id AND wa.agent_id = a.agent_id AND wa.status = 1
                  JOIN public.worker_workspace_assignments ww ON ww.organization_id = wa.organization_id AND ww.worker_id = wa.worker_id
                                                              AND ww.workspace_id = ? AND ww.status = 1
                 WHERE a.organization_id = ? AND a.agent_id = ? AND a.status = 2
                """).param(workspace).param(org).param(agent).query(UUID.class).optional()
                .orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "maquina"));
    }

    private static void requirePublished(JdbcClient j, UUID org, UUID automation, UUID versionId) {
        if (j.sql("SELECT count(*) FROM public.automation_versions WHERE organization_id = ? AND automation_id = ? AND automation_version_id = ? AND status = 2")
                .param(org).param(automation).param(versionId).query(Long.class).single() == 0) {
            throw AccessActions.refuse(Failure.TARGET_NOT_FOUND, "versao_publicada");
        }
    }

    /** O Python escolhido, no inventario, e uma INSTALACAO (D6). */
    private static BasePython requireBase(JdbcClient j, UUID org, UUID automation, UUID agent) {
        BasePython base = base(j, org, automation, agent);
        if (base == null) {
            throw new ApiException(ApiError.REMOTE_PYTHON_NOT_SELECTED, "python");
        }
        if (!base.inInventory()) {
            throw new ApiException(ApiError.REMOTE_PYTHON_NOT_IN_INVENTORY, "python");
        }
        if (!"INSTALLATION".equals(base.kind())) {
            throw new ApiException(ApiError.MANAGED_ENVIRONMENT_BASE_NOT_INSTALLATION, "python_base_e_ambiente_virtual");
        }
        return base;
    }

    /** O que a solicitacao MANAGED congela na tentativa: o aceite vigente e o lock dele. */
    record Snapshot(UUID authorizationId, String lock, String lockSha256, String checks) {
    }

    /**
     * Para a solicitacao (ou a nova tentativa) MANAGED, na MESMA transacao: a chave ligada, a maquina compativel, e o aceite VIGENTE
     * desta versao nesta maquina ainda correspondente (mesma revisao das dependencias e mesmo Python base, instalacao no
     * inventario). Sem aceite: managed_environment_not_authorized; desatualizado: managed_environment_changed.
     */
    Snapshot requireForRequest(JdbcClient j, UUID org, UUID automation, UUID versionId, UUID agent) {
        requireEnabled();
        if (!compatible(j, agent)) {
            throw new ApiException(ApiError.MANAGED_ENVIRONMENT_MACHINE_INCOMPATIBLE, "maquina_sem_ambiente_gerenciado");
        }
        Granted g = granted(j, org, agent, versionId);
        if (g == null) {
            throw new ApiException(ApiError.MANAGED_ENVIRONMENT_NOT_AUTHORIZED, "sem_aceite_do_ambiente");
        }
        RuntimeRequirementsService.View r = RuntimeRequirementsService.view(j, org, automation, versionId);
        Requirements req = r.declared() == null ? null : new Requirements(r.declared().revision(), r.effective().dependencies(), r.effective().resolved());
        if (!staleReasons(g, base(j, org, automation, agent), req).isEmpty()) {
            throw new ApiException(ApiError.MANAGED_ENVIRONMENT_CHANGED, "aceite_desatualizado");
        }
        return new Snapshot(g.id(), g.lock(), g.lockSha(), g.checks());
    }

    /** Grava o retrato do aceite na tentativa recem-criada (o gatilho da 076 reconfere: vigente, mesma versao e maquina, mesmo lock). */
    static void snapshot(JdbcClient j, UUID org, UUID attemptId, Snapshot s) {
        j.sql("INSERT INTO public.remote_attempt_environments (attempt_id, organization_id, authorization_id, lock, lock_sha256, checks) "
                + "VALUES (?, ?, ?, ?::jsonb, ?, ?::jsonb)").param(attemptId).param(org).param(s.authorizationId()).param(s.lock()).param(s.lockSha256())
                .param(s.checks()).update();
    }

    // ------------------------------------------------------------------ o que aconteceu na tentativa

    public record Stage(String stage, OffsetDateTime at) {
    }

    public record Installed(String name, String version, String sha256) {
    }

    public record Report(String outcome, String pythonVersion, String pipVersion, List<Installed> installed, Failure2 failure, Integer createMs,
                         Integer installMs, boolean logAvailable, OffsetDateTime logExpiredAt, OffsetDateTime reportedAt) {
    }

    public record AttemptEnvironment(String lockSha256, int packages, List<String> checks, List<Stage> stages, Report report) {
    }

    static final List<String> STAGES = List.of("ENV_CREATING", "DEPS_INSTALLING");
    static final List<String> REPORT_OUTCOMES = List.of("READY", "FAILED", "INTERRUPTED");

    /** O ambiente de uma tentativa MANAGED (nulo no Python preparado). */
    static AttemptEnvironment attemptEnvironment(JdbcClient j, UUID org, UUID attemptId) {
        record Snap(String sha, int packages, String checks) {
        }
        Snap s = j.sql("SELECT lock_sha256, jsonb_array_length(lock), checks::text FROM public.remote_attempt_environments WHERE organization_id = ? AND attempt_id = ?")
                .param(org).param(attemptId).query((rs, n) -> new Snap(rs.getString(1), rs.getInt(2), rs.getString(3))).optional().orElse(null);
        if (s == null) {
            return null;
        }
        List<Stage> stages = j.sql("SELECT stage, reached_at FROM public.remote_attempt_environment_stages WHERE organization_id = ? AND attempt_id = ? ORDER BY stage")
                .param(org).param(attemptId).query((rs, n) -> new Stage(STAGES.get(rs.getShort(1) - 1), rs.getObject(2, OffsetDateTime.class))).list();
        Report report = j.sql("""
                SELECT outcome, python_version, pip_version, installed::text, failure_requirement, failure_phase, failure_summary, create_ms, install_ms,
                       preparation_log IS NOT NULL, log_expired_at, reported_at
                  FROM public.remote_attempt_environment_reports WHERE organization_id = ? AND attempt_id = ?
                """).param(org).param(attemptId).query((rs, n) -> {
            List<Installed> installed = new ArrayList<>();
            JSON.readTree(rs.getString(4)).forEach(i -> installed.add(new Installed(i.get("name").asString(), i.get("version").asString(),
                    i.get("sha256").asString())));
            return new Report(REPORT_OUTCOMES.get(rs.getShort(1) - 1), rs.getString(2), rs.getString(3), List.copyOf(installed),
                    rs.getString(6) == null ? null : new Failure2(rs.getString(5), rs.getString(6), rs.getString(7)), (Integer) rs.getObject(8),
                    (Integer) rs.getObject(9), rs.getBoolean(10), rs.getObject(11, OffsetDateTime.class), rs.getObject(12, OffsetDateTime.class));
        }).optional().orElse(null);
        return new AttemptEnvironment(s.sha(), s.packages(), strings(s.checks()), List.copyOf(stages), report);
    }

    /** O registro do preparo (ja sem segredos). Vencido pelos 30 dias: 410. Sem relatorio ou sem registro: 404. */
    static String preparationLog(JdbcClient j, UUID org, UUID attemptId) {
        record Log(String text, boolean expired) {
        }
        Log l = j.sql("SELECT preparation_log, log_expired_at IS NOT NULL FROM public.remote_attempt_environment_reports WHERE organization_id = ? AND attempt_id = ?")
                .param(org).param(attemptId).query((rs, n) -> new Log(rs.getString(1), rs.getBoolean(2))).optional()
                .orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "registro_do_preparo"));
        if (l.expired()) {
            throw new ApiException(ApiError.MANAGED_ENVIRONMENT_LOG_EXPIRED, "registro_do_preparo_removido");
        }
        if (l.text() == null) {
            throw AccessActions.refuse(Failure.TARGET_NOT_FOUND, "registro_do_preparo");
        }
        return l.text();
    }
}
