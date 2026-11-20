package com.nekkora.platform.admin;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.license.LicenseGate;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.platform.access.WorkspaceAccess;
import com.nekkora.platform.admin.AccessActions.Failure;
import com.nekkora.platform.admin.AgentAdminService.LocalAuthorization;
import com.nekkora.platform.session.ValidSession;

/**
 * PLATFORM-FLUXO-002 - ATIVACAO UNIFICADA da automacao: um passo, por quem administra a organizacao ({@code org.manage}, com
 * confirmacao reforcada), que (1) vincula uma licenca disponivel a automacao, se ela ainda nao tem, e (2) emite, para os
 * Workers ESCOLHIDOS, as autorizacoes de execucao da versao publicada atual e de monitoramento. Nada aqui e regra nova de
 * direito: compoe os atos que ja existem (vinculo 062, autorizacoes 025/030/033/064) e o banco reconfere cada um.
 *
 * <p>Regras da titular (decisoes de 20/11/2026, docs/platform-fluxo-002/decisoes.md):
 * <ul>
 * <li>uma licenca cobre execucao e monitoramento da automacao, qualquer que seja o numero de Workers; o Worker nao consome
 * licenca; acrescentar Worker ou aceitar versao nova nao consome outra;</li>
 * <li>nenhuma maquina e marcada implicitamente: so os Workers informados; uma versao nova exige aceite explicito (a licenca
 * nao autoriza versoes futuras); o monitoramento ja autorizado continua valendo, sem autorizar executar a versao nova;</li>
 * <li>sem Worker ou sem versao publicada, a licenca e vinculada e a ativacao fica "configuracao pendente";</li>
 * <li>cada autorizacao e um ato separado: se uma falhar, as demais ficam, e a resposta diz exatamente o que ficou pendente;
 * repetir conclui sem consumir outra licenca e sem duplicar (a vigente do escopo e reaproveitada);</li>
 * <li>renovar autorizacoes depois da renovacao da licenca: as mesmas maquinas e a versao ja aceita, nunca as revogadas por
 * uma pessoa, nunca maquinas novas (069).</li>
 * </ul>
 *
 * <p>No modelo anterior (a organizacao ainda nao exige licenca) a ativacao tambem funciona: vincula uma licenca disponivel se
 * houver, sem exigir, e as autorizacoes usam a validade informada (como hoje), porque nao ha cobertura de licenca.
 */
public class AutomationActivationService {

    private static final Logger LOG = LoggerFactory.getLogger(AutomationActivationService.class);
    private static final short ACT_ASSIGNED = 4;
    /** Folga para considerar uma autorizacao "ate o fim da cobertura" (a validade e gravada com a precisao do banco). */
    private static final long RENEWAL_SLACK_MINUTES = 60;

    private final AccessActions actions;
    private final WorkspaceAccess access;

    public AutomationActivationService(AccessActions actions, WorkspaceAccess access) {
        this.actions = actions;
        this.access = access;
    }

    // ================================================================================================== leituras

    /** Todas as ativacoes: automacoes com licenca vinculada ou com autorizacoes da ativacao, e a conta das licencas. */
    public Map<String, Object> list(UUID org, ValidSession actor, String token) {
        return actions.read(org, j -> {
            requireManager(j, org, actor, token);
            List<UUID> automations = j.sql("""
                    SELECT a.automation_id FROM public.automations a
                     WHERE a.organization_id = ?
                       AND (EXISTS (SELECT 1 FROM public.organization_license_assignments g
                                     WHERE g.organization_id = a.organization_id AND g.automation_id = a.automation_id AND g.released_at IS NULL)
                            OR EXISTS (SELECT 1 FROM public.local_run_authorizations z
                                        WHERE z.organization_id = a.organization_id AND z.automation_id = a.automation_id
                                          AND (z.revoked_at IS NULL OR z.revocation_reason = 2)))
                     ORDER BY a.name, a.automation_id
                    """).param(org).query(UUID.class).list();
            List<Map<String, Object>> items = new ArrayList<>();
            for (UUID a : automations) {
                items.add(describe(j, org, a));
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("model", LicenseGate.licensedModel(j, org) ? "licensed" : "legacy");
            r.put("licenses", licenseCount(j, org));
            r.put("items", items);
            r.put("asOf", OffsetDateTime.now());
            return r;
        });
    }

    /** Uma ativacao (a mesma forma de cada item da lista). */
    public Map<String, Object> get(UUID org, ValidSession actor, String token, UUID automation) {
        return actions.read(org, j -> {
            requireManager(j, org, actor, token);
            requireAutomationExists(j, org, automation);
            return describe(j, org, automation);
        });
    }

    /**
     * Preparacao do passo "Ativar automacao" num ambiente de trabalho: as automacoes (com o estado de cada uma), os Workers
     * autorizados no ambiente e a licenca que seria usada. Nada e reservado: a conferencia vale de novo na confirmacao.
     */
    public Map<String, Object> options(UUID org, ValidSession actor, String token, UUID workspace) {
        return actions.read(org, j -> {
            requireManager(j, org, actor, token);
            String wsName = j.sql("SELECT name FROM public.workspaces WHERE organization_id = ? AND workspace_id = ? AND status = 1")
                    .param(org).param(workspace).query(String.class).optional()
                    .orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "ambiente"));
            List<Map<String, Object>> automations = new ArrayList<>();
            for (UUID a : j.sql("SELECT automation_id FROM public.automations WHERE organization_id = ? AND workspace_id = ? AND status = 1 "
                    + "ORDER BY name, automation_id").param(org).param(workspace).query(UUID.class).list()) {
                Map<String, Object> d = describe(j, org, a);
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("automationId", a);
                o.put("name", d.get("name"));
                o.put("licensed", d.get("license") != null);
                o.put("state", d.get("state"));
                o.put("currentVersion", d.get("currentVersion"));
                o.put("workerIds", ((List<?>) d.get("workers")).stream().map(w -> ((Map<?, ?>) w).get("workerId")).toList());
                automations.add(o);
            }
            List<Map<String, Object>> workers = j.sql("""
                    SELECT w.worker_id, w.name FROM public.worker_workspace_assignments x
                      JOIN public.workers w ON w.organization_id = x.organization_id AND w.worker_id = x.worker_id AND w.status = 1
                     WHERE x.organization_id = ? AND x.workspace_id = ? AND x.status = 1 ORDER BY w.name, w.worker_id
                    """).param(org).param(workspace).query((rs, n) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("workerId", rs.getObject(1, UUID.class));
                        m.put("name", rs.getString(2));
                        return m;
                    }).list();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("workspaceId", workspace);
            r.put("workspaceName", wsName);
            r.put("model", LicenseGate.licensedModel(j, org) ? "licensed" : "legacy");
            r.put("licenses", licenseCount(j, org));
            r.put("nextLicense", nextLicense(j, org));
            r.put("automations", automations);
            r.put("workers", workers);
            return r;
        });
    }

    // ================================================================================================== atos

    /**
     * Ativar (ou concluir/gerenciar) a ativacao: vincula uma licenca se a automacao ainda nao tem e emite, para cada Worker
     * informado, a autorizacao de execucao da versao publicada atual (se houver) e a de monitoramento. Idempotente: o que ja
     * esta vigente e reaproveitado e nenhuma licenca e consumida de novo. {@code validUntil}: so no modelo anterior.
     */
    public Map<String, Object> activate(UUID org, ValidSession actor, String token, UUID automation, List<UUID> workerIds, OffsetDateTime validUntil) {
        Set<UUID> workers = new LinkedHashSet<>(workerIds == null ? List.of() : workerIds);
        if (workers.size() > 50) {
            throw ApiException.invalid("workers");
        }
        // 1. a licenca (um ato, sob a trava da organizacao): ou ja esta vinculada, ou vincula a disponivel de menor numero
        Map<String, Object> license = actions.write(org, j -> {
            requireManager(j, org, actor, token);
            actions.requireStepUp(j, actor);
            actions.lockOrganizationAndActor(j, org, actor.userId());
            UUID workspace = activeAutomationWorkspace(j, org, automation);
            boolean licensed = LicenseGate.licensedModel(j, org);
            if (!licensed && !workers.isEmpty()) {
                requireLegacyValidity(validUntil);
            }
            // os Workers informados sao do ambiente da automacao, ativos e autorizados nele (antes de consumir licenca)
            for (UUID w : workers) {
                if (!Boolean.TRUE.equals(j.sql("SELECT EXISTS (SELECT 1 FROM public.worker_workspace_assignments x JOIN public.workers k "
                        + "ON k.organization_id = x.organization_id AND k.worker_id = x.worker_id AND k.status = 1 "
                        + "WHERE x.organization_id = ? AND x.worker_id = ? AND x.workspace_id = ? AND x.status = 1)")
                        .param(org).param(w).param(workspace).query(Boolean.class).single())) {
                    throw new ApiException(ApiError.ACTIVATION_WORKER_NOT_IN_WORKSPACE, "worker_fora_do_ambiente");
                }
            }
            Map<String, Object> current = assignment(j, org, automation);
            if (current != null) {
                current.put("consumedNow", false);
                return current;
            }
            Map<String, Object> next = nextLicense(j, org);
            if (next == null) {
                if (licensed) {
                    LOG.info("evento=acesso.negado regra=CONFLICT motivo=license_unavailable correlacao={}", AccessActions.correlation());
                    throw new ApiException(ApiError.LICENSE_UNAVAILABLE, "sem_licenca_disponivel");
                }
                return null; // modelo anterior: a licenca nao e exigida
            }
            UUID licenseId = (UUID) next.get("licenseId");
            try {
                j.sql("INSERT INTO public.organization_license_assignments (organization_id, license_id, workspace_id, automation_id, assigned_by_membership_id) "
                        + "VALUES (?, ?, ?, ?, ?)").param(org).param(licenseId).param(workspace).param(automation).param(actor.membershipId()).update();
            } catch (RuntimeException e) {
                throw LicenseService.refusalOf(e);
            }
            j.sql("INSERT INTO public.organization_license_acts (organization_id, act, license_id, to_automation_id, actor_membership_id) VALUES (?, ?, ?, ?, ?)")
                    .param(org).param(ACT_ASSIGNED).param(licenseId).param(automation).param(actor.membershipId()).update();
            LOG.info("evento=ativacao.licenca_vinculada licenca={} automacao={} correlacao={}", licenseId, automation, AccessActions.correlation());
            Map<String, Object> m = assignment(j, org, automation);
            m.put("consumedNow", true);
            return m;
        });
        // 2. as autorizacoes, uma por ato: o que falhar fica pendente, sem desfazer o resto
        UUID version = actions.read(org, j -> currentVersionId(j, org, automation));
        List<Map<String, Object>> results = new ArrayList<>();
        for (UUID w : workers) {
            if (version != null) {
                results.add(issue(org, actor, token, automation, w, "execution", version, validUntil));
            }
            results.add(issue(org, actor, token, automation, w, "monitoring", null, validUntil));
        }
        return outcome(org, actor, token, automation, license, results);
    }

    /** Aceitar uma versao publicada (nova) nos Workers da ativacao: so execucao daquele pacote; nao consome licenca. */
    public Map<String, Object> acceptVersion(UUID org, ValidSession actor, String token, UUID automation, UUID version, OffsetDateTime validUntil) {
        List<UUID> workers = actions.write(org, j -> {
            requireManager(j, org, actor, token);
            actions.requireStepUp(j, actor);
            activeAutomationWorkspace(j, org, automation);
            Boolean ok = j.sql("SELECT EXISTS (SELECT 1 FROM public.automation_versions WHERE organization_id = ? AND automation_id = ? "
                    + "AND automation_version_id = ? AND status = 2)").param(org).param(automation).param(version).query(Boolean.class).single();
            if (!Boolean.TRUE.equals(ok)) {
                throw AccessActions.refuse(Failure.CONFLICT, "versao_nao_publicada");
            }
            if (!LicenseGate.licensedModel(j, org)) {
                requireLegacyValidity(validUntil);
            }
            List<UUID> ws = activationWorkers(j, org, automation);
            if (ws.isEmpty()) {
                throw new ApiException(ApiError.ACTIVATION_WITHOUT_WORKERS, "ativacao_sem_workers");
            }
            return ws;
        });
        List<Map<String, Object>> results = new ArrayList<>();
        for (UUID w : workers) {
            results.add(issue(org, actor, token, automation, w, "execution", version, validUntil));
        }
        return outcome(org, actor, token, automation, null, results);
    }

    /**
     * Renovar autorizacoes depois da renovacao da licenca (069): para cada Worker da ativacao, a autorizacao de execucao da
     * versao JA aceita e a de monitoramento, quando terminam antes do fim da cobertura atual. Nunca as revogadas por uma
     * pessoa, nunca maquinas novas, nunca a versao mais nova. Repetir nao duplica.
     */
    public Map<String, Object> renew(UUID org, ValidSession actor, String token, UUID automation) {
        List<LocalAuthorization> due = actions.write(org, j -> {
            requireManager(j, org, actor, token);
            actions.requireStepUp(j, actor);
            activeAutomationWorkspace(j, org, automation);
            if (!LicenseGate.licensedModel(j, org)) {
                throw new ApiException(ApiError.ACTIVATION_RENEWAL_NOT_APPLICABLE, "modelo_anterior");
            }
            LicenseGate.requireForNewWork(j, org, automation);
            return renewable(j, org, automation);
        });
        List<Map<String, Object>> results = new ArrayList<>();
        for (LocalAuthorization prev : due) {
            String kind = "EXTERNAL_MONITORED".equals(prev.sourceKind()) ? "monitoring" : "execution";
            Map<String, Object> r = item(prev.workerId(), kind, prev.automationVersionId());
            try {
                LocalAuthorization a = actions.write(org, j -> {
                    requireManager(j, org, actor, token);
                    actions.requireStepUp(j, actor);
                    actions.lockOrganizationAndActor(j, org, actor.userId());
                    // relida sob a trava: outra aba pode ter renovado agora
                    LocalAuthorization fresh = latest(j, org, prev.authorizationId());
                    if (fresh == null || !renewable(j, org, automation).stream().anyMatch(x -> x.authorizationId().equals(prev.authorizationId()))) {
                        return null;
                    }
                    return AgentAdminService.renewAuthorization(j, org, actor.membershipId(), fresh);
                });
                if (a == null) {
                    r.put("outcome", "already");
                } else {
                    r.put("outcome", "issued");
                    r.put("authorizationId", a.authorizationId());
                    r.put("validUntil", a.validUntil());
                }
            } catch (RuntimeException e) {
                failure(r, e);
            }
            results.add(r);
        }
        return outcome(org, actor, token, automation, null, results);
    }

    // ================================================================================================== internos

    private void requireManager(JdbcClient j, UUID org, ValidSession actor, String token) {
        actions.requireActor(j, org, actor, token);
        access.requireAnyOrganizationPermission(j, org, actor.membershipId(), "org.manage");
    }

    private static void requireLegacyValidity(OffsetDateTime validUntil) {
        if (validUntil == null || !validUntil.isAfter(OffsetDateTime.now()) || validUntil.isAfter(OffsetDateTime.now().plusDays(366))) {
            throw ApiException.invalid("validade");
        }
    }

    private static void requireAutomationExists(JdbcClient j, UUID org, UUID automation) {
        if (!Boolean.TRUE.equals(j.sql("SELECT EXISTS (SELECT 1 FROM public.automations WHERE organization_id = ? AND automation_id = ?)")
                .param(org).param(automation).query(Boolean.class).single())) {
            throw AccessActions.refuse(Failure.TARGET_NOT_FOUND, "automacao");
        }
    }

    /** O ambiente da automacao ATIVA em ambiente ATIVO; outra organizacao: 404; arquivada, pausada ou ambiente inativo: 409. */
    private static UUID activeAutomationWorkspace(JdbcClient j, UUID org, UUID automation) {
        record Row(UUID workspace, boolean active) {
        }
        Row row = j.sql("SELECT a.workspace_id, a.status = 1 AND w.status = 1 FROM public.automations a JOIN public.workspaces w "
                        + "ON w.organization_id = a.organization_id AND w.workspace_id = a.workspace_id WHERE a.organization_id = ? AND a.automation_id = ?")
                .param(org).param(automation).query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getBoolean(2))).optional()
                .orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "automacao"));
        if (!row.active()) {
            throw new ApiException(ApiError.ACTIVATION_AUTOMATION_INACTIVE, "automacao_ou_ambiente_inativo");
        }
        return row.workspace();
    }

    /** Um ato de autorizacao (execucao de uma versao ou monitoramento) para um Worker, na transacao propria. */
    private Map<String, Object> issue(UUID org, ValidSession actor, String token, UUID automation, UUID worker, String kind, UUID version,
                                      OffsetDateTime validUntil) {
        Map<String, Object> r = item(worker, kind, version);
        try {
            LocalAuthorization a = actions.write(org, j -> {
                requireManager(j, org, actor, token);
                actions.requireStepUp(j, actor);
                actions.lockOrganizationAndActor(j, org, actor.userId());
                UUID workspace = activeAutomationWorkspace(j, org, automation);
                LocalAuthorization existing = vigente(j, org, automation, worker, kind, version);
                if (existing != null) {
                    return null;
                }
                OffsetDateTime until = LicenseGate.licensedModel(j, org) ? null : validUntil;
                return "execution".equals(kind) ? AgentAdminService.grantVersion(j, org, actor.membershipId(), workspace, worker, version, until)
                        : AgentAdminService.grantMonitoring(j, org, actor.membershipId(), workspace, worker, automation, until);
            });
            if (a == null) {
                r.put("outcome", "already");
            } else {
                r.put("outcome", "issued");
                r.put("authorizationId", a.authorizationId());
                r.put("validUntil", a.validUntil());
            }
        } catch (RuntimeException e) {
            failure(r, e);
        }
        return r;
    }

    private static Map<String, Object> item(UUID worker, String kind, UUID version) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("workerId", worker);
        r.put("kind", kind);
        r.put("automationVersionId", version);
        return r;
    }

    /** O motivo de um ato que falhou, para a tela dizer exatamente o que ficou pendente (nunca a mensagem interna). */
    private static void failure(Map<String, Object> r, RuntimeException e) {
        r.put("outcome", "failed");
        String reason;
        if (e instanceof ApiException ae) {
            reason = ae.apiError().reason() == null ? ae.apiError().code() : ae.apiError().reason();
        } else if (e instanceof AccessActions.Refusal rf) {
            reason = rf.failure.name().toLowerCase();
        } else {
            LOG.warn("evento=ativacao.autorizacao_falhou tipo={} correlacao={}", e.getClass().getSimpleName(), AccessActions.correlation());
            reason = "unexpected";
        }
        r.put("reason", reason);
    }

    private Map<String, Object> outcome(UUID org, ValidSession actor, String token, UUID automation, Map<String, Object> license,
                                        List<Map<String, Object>> results) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (license != null) {
            r.put("licenseConsumedNow", license.get("consumedNow"));
        }
        r.put("results", results);
        r.put("activation", actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            return describe(j, org, automation);
        }));
        return r;
    }

    /** A conta das licencas (a mesma regra da conta de "Licencas e capacidade"): contratadas vigentes, utilizadas e disponiveis. */
    private static Map<String, Object> licenseCount(JdbcClient j, UUID org) {
        return j.sql("""
                SELECT count(*) FILTER (WHERE v.valid) AS contracted,
                       count(*) FILTER (WHERE v.valid AND v.assigned) AS used,
                       count(*) FILTER (WHERE v.valid AND NOT v.assigned) AS available
                  FROM (SELECT EXISTS (SELECT 1 FROM public.organization_license_periods p WHERE p.organization_id = l.organization_id
                                          AND p.license_id = l.license_id AND p.starts_at <= clock_timestamp() AND p.ends_at > clock_timestamp()) AS valid,
                               EXISTS (SELECT 1 FROM public.organization_license_assignments g
                                        WHERE g.organization_id = l.organization_id AND g.license_id = l.license_id AND g.released_at IS NULL) AS assigned
                          FROM public.organization_licenses l WHERE l.organization_id = ? AND l.ended_at IS NULL) v
                """).param(org).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("contracted", rs.getLong("contracted"));
                    m.put("used", rs.getLong("used"));
                    m.put("available", rs.getLong("available"));
                    return m;
                }).single();
    }

    /** A licenca vigente, nao encerrada e sem vinculo, de menor numero; nula se nao ha. */
    private static Map<String, Object> nextLicense(JdbcClient j, UUID org) {
        return j.sql("""
                SELECT l.license_id, l.license_number,
                       (SELECT max(p.ends_at) FROM public.organization_license_periods p WHERE p.organization_id = l.organization_id AND p.license_id = l.license_id)
                  FROM public.organization_licenses l
                 WHERE l.organization_id = ? AND l.ended_at IS NULL
                   AND EXISTS (SELECT 1 FROM public.organization_license_periods p WHERE p.organization_id = l.organization_id AND p.license_id = l.license_id
                                  AND p.starts_at <= clock_timestamp() AND p.ends_at > clock_timestamp())
                   AND NOT EXISTS (SELECT 1 FROM public.organization_license_assignments g
                                    WHERE g.organization_id = l.organization_id AND g.license_id = l.license_id AND g.released_at IS NULL)
                 ORDER BY l.license_number LIMIT 1
                """).param(org).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("licenseId", rs.getObject(1, UUID.class));
                    m.put("number", rs.getInt(2));
                    m.put("validUntil", rs.getObject(3, OffsetDateTime.class));
                    return m;
                }).optional().orElse(null);
    }

    /** A licenca vinculada a automacao (aberta), com a situacao e o fim da cobertura sem lacuna; nula se nao ha. */
    private static Map<String, Object> assignment(JdbcClient j, UUID org, UUID automation) {
        return j.sql("""
                SELECT l.license_id, l.license_number, g.assigned_at,
                       (SELECT max(p.ends_at) FROM public.organization_license_periods p WHERE p.organization_id = l.organization_id AND p.license_id = l.license_id)
                  FROM public.organization_license_assignments g
                  JOIN public.organization_licenses l ON l.organization_id = g.organization_id AND l.license_id = g.license_id
                 WHERE g.organization_id = ? AND g.automation_id = ? AND g.released_at IS NULL
                """).param(org).param(automation).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("licenseId", rs.getObject(1, UUID.class));
                    m.put("number", rs.getInt(2));
                    m.put("assignedAt", rs.getObject(3, OffsetDateTime.class));
                    m.put("lastPeriodEndsAt", rs.getObject(4, OffsetDateTime.class));
                    return m;
                }).optional().orElse(null);
    }

    /** A versao publicada mais recente da automacao com impressao de pacote conhecida; nula se nao ha. */
    private static UUID currentVersionId(JdbcClient j, UUID org, UUID automation) {
        return j.sql("""
                SELECT automation_version_id FROM public.automation_versions
                 WHERE organization_id = ? AND automation_id = ? AND status = 2
                   AND command_json -> 'agent_package' ->> 'fingerprint_rule' IN (?, ?)
                   AND command_json -> 'agent_package' ->> 'fingerprint' ~ '^[0-9a-f]{64}$'
                 ORDER BY version_number DESC LIMIT 1
                """).param(org).param(automation).param(AgentAdminService.FINGERPRINT_RULE).param(AgentAdminService.EXTERNAL_FINGERPRINT_RULE)
                .query(UUID.class).optional().orElse(null);
    }

    private static Map<String, Object> version(JdbcClient j, UUID org, UUID version) {
        if (version == null) {
            return null;
        }
        return j.sql("SELECT automation_version_id, version_number, published_at FROM public.automation_versions WHERE organization_id = ? AND automation_version_id = ?")
                .param(org).param(version).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("versionId", rs.getObject(1, UUID.class));
                    m.put("number", rs.getInt(2));
                    m.put("publishedAt", rs.getObject(3, OffsetDateTime.class));
                    return m;
                }).optional().orElse(null);
    }

    private static final String COLUMNS = "authorization_id, workspace_id, worker_id, automation_id, automation_version_id, package_fingerprint, "
            + "granted_at, valid_until, revoked_at, source_kind, package_fingerprint_rule, revocation_reason";

    private static final String Z_COLUMNS = "z.authorization_id, z.workspace_id, z.worker_id, z.automation_id, z.automation_version_id, "
            + "z.package_fingerprint, z.granted_at, z.valid_until, z.revoked_at, z.source_kind, z.package_fingerprint_rule, z.revocation_reason";

    private record Auth(LocalAuthorization a, Short revocationReason) {
    }

    /** Revogada sem ser por substituicao (motivo 1, ou sem motivo gravado): decisao humana, fora da ativacao e da renovacao. */
    private static boolean manual(Auth a) {
        return a.a().revokedAt() != null && !Short.valueOf((short) 2).equals(a.revocationReason());
    }

    private static Auth row(java.sql.ResultSet rs) throws java.sql.SQLException {
        short kind = rs.getShort("source_kind");
        LocalAuthorization a = new LocalAuthorization(rs.getObject("authorization_id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getObject("worker_id", UUID.class), rs.getObject("automation_id", UUID.class), rs.getObject("automation_version_id", UUID.class),
                rs.getString("package_fingerprint"), rs.getObject("granted_at", OffsetDateTime.class), rs.getObject("valid_until", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class), kind == 1 ? "STUDIO_PACKAGE" : kind == 2 ? "EXTERNAL_PACKAGE" : "EXTERNAL_MONITORED",
                rs.getString("package_fingerprint_rule"));
        short reason = rs.getShort("revocation_reason");
        return new Auth(a, rs.wasNull() ? null : reason);
    }

    private static LocalAuthorization latest(JdbcClient j, UUID org, UUID authorization) {
        return j.sql("SELECT " + COLUMNS + " FROM public.local_run_authorizations WHERE organization_id = ? AND authorization_id = ?")
                .param(org).param(authorization).query((rs, n) -> row(rs).a()).optional().orElse(null);
    }

    /** A autorizacao VIGENTE do escopo (Worker ativo no ambiente da automacao), ou nula. */
    private static LocalAuthorization vigente(JdbcClient j, UUID org, UUID automation, UUID worker, String kind, UUID version) {
        String scope = "execution".equals(kind) ? "z.source_kind IN (1, 2) AND z.automation_version_id = ?" : "z.source_kind = 3 AND ?::uuid IS NULL";
        return j.sql("SELECT " + Z_COLUMNS + " FROM public.local_run_authorizations z "
                        + "JOIN public.worker_workspace_assignments x ON x.organization_id = z.organization_id AND x.assignment_id = z.worker_workspace_assignment_id "
                        + "AND x.status = 1 WHERE z.organization_id = ? AND z.automation_id = ? AND z.worker_id = ? AND " + scope
                        + " AND z.revoked_at IS NULL AND z.valid_until > clock_timestamp() LIMIT 1")
                .param(org).param(automation).param(worker).param(version).query((rs, n) -> row(rs).a()).optional().orElse(null);
    }

    /**
     * As autorizacoes da ativacao: por Worker (com a autorizacao no ambiente ATIVA), a mais recente de execucao e a mais recente
     * de monitoramento da automacao. Uma revogada por uma pessoa tira aquele escopo da ativacao.
     */
    private static List<Auth> current(JdbcClient j, UUID org, UUID automation) {
        return j.sql("""
                SELECT DISTINCT ON (z.worker_id, z.source_kind = 3) %s
                  FROM public.local_run_authorizations z
                  JOIN public.worker_workspace_assignments x ON x.organization_id = z.organization_id AND x.assignment_id = z.worker_workspace_assignment_id
                       AND x.status = 1
                  JOIN public.workers k ON k.organization_id = z.organization_id AND k.worker_id = z.worker_id AND k.status = 1
                 WHERE z.organization_id = ? AND z.automation_id = ?
                 ORDER BY z.worker_id, z.source_kind = 3, z.granted_at DESC, z.authorization_id DESC
                """.formatted(Z_COLUMNS)).param(org).param(automation).query((rs, n) -> row(rs)).list();
    }

    private static List<UUID> activationWorkers(JdbcClient j, UUID org, UUID automation) {
        List<UUID> ws = new ArrayList<>();
        for (Auth a : current(j, org, automation)) {
            if (!manual(a) && !ws.contains(a.a().workerId())) {
                ws.add(a.a().workerId());
            }
        }
        return ws;
    }

    /** Alvo da validade: o fim da cobertura sem lacuna, limitado aos 366 dias da 025. Nulo: sem cobertura. */
    private static OffsetDateTime target(JdbcClient j, UUID org, UUID automation) {
        OffsetDateTime end = LicenseGate.coverageEnd(j, org, automation);
        if (end == null) {
            return null;
        }
        OffsetDateTime ceiling = OffsetDateTime.now().plusDays(366).minusMinutes(5);
        return end.isAfter(ceiling) ? ceiling : end;
    }

    /** As autorizacoes da ativacao que terminam antes do alvo e podem ser renovadas (nunca revogadas por pessoa). */
    private static List<LocalAuthorization> renewable(JdbcClient j, UUID org, UUID automation) {
        OffsetDateTime t = target(j, org, automation);
        List<LocalAuthorization> due = new ArrayList<>();
        if (t == null) {
            return due;
        }
        for (Auth a : current(j, org, automation)) {
            boolean manual = manual(a);
            boolean replaced = Short.valueOf((short) 2).equals(a.revocationReason());
            if (manual || replaced) {
                continue; // revogada por pessoa: decisao humana; substituida: a sucessora e a corrente
            }
            if (a.a().validUntil().isBefore(t.minusMinutes(RENEWAL_SLACK_MINUTES))) {
                due.add(a.a());
            }
        }
        return due;
    }

    /** A forma de uma ativacao para a tela. */
    private static Map<String, Object> describe(JdbcClient j, UUID org, UUID automation) {
        record A(String name, String status, UUID workspace, String workspaceName) {
        }
        A a = j.sql("SELECT a.name, CASE a.status WHEN 1 THEN 'active' WHEN 2 THEN 'suspended' ELSE 'archived' END, a.workspace_id, w.name "
                        + "FROM public.automations a JOIN public.workspaces w ON w.organization_id = a.organization_id AND w.workspace_id = a.workspace_id "
                        + "WHERE a.organization_id = ? AND a.automation_id = ?").param(org).param(automation)
                .query((rs, n) -> new A(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4))).single();
        boolean licensedModel = LicenseGate.licensedModel(j, org);
        String licenseState = LicenseGate.state(j, org, automation);
        Map<String, Object> license = assignment(j, org, automation);
        if (license != null) {
            license.put("state", licenseState);
            license.put("coverageUntil", LicenseGate.coverageEnd(j, org, automation));
        }
        UUID currentVersion = currentVersionId(j, org, automation);
        List<Auth> auths = current(j, org, automation);
        OffsetDateTime now = OffsetDateTime.now();
        Map<UUID, Map<String, Object>> byWorker = new LinkedHashMap<>();
        UUID accepted = null;
        OffsetDateTime acceptedAt = null;
        for (Auth x : auths) {
            if (manual(x)) {
                continue;
            }
            LocalAuthorization la = x.a();
            Map<String, Object> w = byWorker.computeIfAbsent(la.workerId(), id -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("workerId", id);
                m.put("name", j.sql("SELECT name FROM public.workers WHERE organization_id = ? AND worker_id = ?").param(org).param(id).query(String.class).single());
                m.put("execution", null);
                m.put("monitoring", null);
                return m;
            });
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("authorizationId", la.authorizationId());
            d.put("automationVersionId", la.automationVersionId());
            d.put("validUntil", la.validUntil());
            d.put("status", la.revokedAt() != null ? "replaced" : la.validUntil().isAfter(now) ? "active" : "expired");
            boolean monitoring = "EXTERNAL_MONITORED".equals(la.sourceKind());
            w.put(monitoring ? "monitoring" : "execution", d);
            if (!monitoring && (acceptedAt == null || la.grantedAt().isAfter(acceptedAt))) {
                accepted = la.automationVersionId();
                acceptedAt = la.grantedAt();
            }
        }
        List<Map<String, Object>> workers = new ArrayList<>(byWorker.values());
        List<String> pending = new ArrayList<>();
        if (licensedModel && license == null) {
            pending.add("LICENSE_REQUIRED");
        } else if (licensedModel && "LICENSE_EXPIRED".equals(licenseState)) {
            pending.add("LICENSE_EXPIRED");
        }
        if (currentVersion == null) {
            pending.add("NO_PUBLISHED_VERSION");
        }
        if (workers.isEmpty()) {
            pending.add("NO_WORKER");
        }
        for (Map<String, Object> w : workers) {
            Map<?, ?> e = (Map<?, ?>) w.get("execution");
            Map<?, ?> m = (Map<?, ?>) w.get("monitoring");
            if (currentVersion != null && (e == null || !"active".equals(e.get("status")))) {
                if (!pending.contains("WORKER_WITHOUT_EXECUTION")) {
                    pending.add("WORKER_WITHOUT_EXECUTION");
                }
            }
            if (m == null || !"active".equals(m.get("status"))) {
                if (!pending.contains("WORKER_WITHOUT_MONITORING")) {
                    pending.add("WORKER_WITHOUT_MONITORING");
                }
            }
        }
        if (currentVersion != null && accepted != null && !currentVersion.equals(accepted)) {
            pending.add("NEW_VERSION_AVAILABLE");
        }
        boolean renewal = licensedModel && "OK".equals(licenseState) && !renewable(j, org, automation).isEmpty();
        if (renewal) {
            pending.add("RENEWAL_AVAILABLE");
        }
        String state;
        if (pending.contains("LICENSE_REQUIRED") || pending.contains("LICENSE_EXPIRED")) {
            state = pending.contains("LICENSE_EXPIRED") ? "LICENSE_EXPIRED" : "LICENSE_REQUIRED";
        } else if (renewal) {
            state = "RENEWAL_AVAILABLE";
        } else if (pending.contains("NO_PUBLISHED_VERSION") || pending.contains("NO_WORKER") || pending.contains("WORKER_WITHOUT_EXECUTION")
                || pending.contains("WORKER_WITHOUT_MONITORING")) {
            state = "CONFIGURATION_PENDING";
        } else {
            state = "ACTIVE";
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("automationId", automation);
        r.put("name", a.name());
        r.put("automationStatus", a.status());
        r.put("workspaceId", a.workspace());
        r.put("workspaceName", a.workspaceName());
        r.put("state", state);
        r.put("pending", pending);
        r.put("license", license);
        r.put("currentVersion", version(j, org, currentVersion));
        r.put("acceptedVersion", version(j, org, accepted));
        r.put("workers", workers);
        return r;
    }
}
