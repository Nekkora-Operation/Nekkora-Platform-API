package com.nekkora.platform.admin;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.db.DbErrors;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.platform.admin.AccessActions.Failure;
import com.nekkora.platform.session.ValidSession;

/**
 * PLATFORM-LICENCIAMENTO-001 (D5) - atos do OPERADOR DA NEKKORA sobre as licencas de OUTRA organizacao: conceder, renovar,
 * encerrar e ligar ou desligar a exigencia de licenca, com a previa. Migration 063; contrato em
 * docs/platform-licenciamento-001/t04-atos-do-operador.md.
 *
 * <p>Quem e operador: sessao da ORGANIZACAO INTERNA da Nekkora (a da configuracao; sem ela, todas as rotas respondem 404),
 * conta interna, COM segundo fator, inscrita no cadastro de operadores (conferido no banco). Cada ato exige confirmacao
 * reforcada recente - nesta sessao sempre por codigo do autenticador, porque sessao sem segundo fator nao opera.
 *
 * <p>Quem grava e o banco, pelas funcoes {@code license_op_*} (063), que reconferem o cadastro do operador e que ele nao e da
 * organizacao alvo. A Platform nao tem escrita direta em licencas, periodos nem no modelo de habilitacao.
 *
 * <p>NADA aqui faz um caminho de execucao conferir licenca: ligar a exigencia grava o MODELO da organizacao; a conferencia
 * nos fluxos e a etapa seguinte da missao.
 */
public class LicenseOperatorService {

    private static final Logger LOG = LoggerFactory.getLogger(LicenseOperatorService.class);
    /** Janela de "em uso" da previa: houve execucao ou monitoramento nos ultimos N dias. */
    static final int RECENT_USE_DAYS = 30;

    public record Grant(int count, OffsetDateTime startsAt, OffsetDateTime endsAt, String justification) {
    }

    public record Renewal(OffsetDateTime startsAt, OffsetDateTime endsAt, String justification) {
    }

    private final AccessActions actions;
    private final UUID operatorOrganization;

    /** {@code operatorOrganization}: a organizacao interna da Nekkora; nulo = operacao de licencas desligada nesta instalacao. */
    public LicenseOperatorService(AccessActions actions, UUID operatorOrganization) {
        this.actions = actions;
        this.operatorOrganization = operatorOrganization;
    }

    // ------------------------------------------------------------------ atos

    public List<Map<String, Object>> grant(UUID org, ValidSession actor, String token, UUID target, Grant g) {
        if (g == null || g.count() < 1 || g.count() > 100 || g.startsAt() == null || g.endsAt() == null || !g.endsAt().isAfter(g.startsAt())) {
            throw ApiException.invalid("concessao");
        }
        return act(org, actor, token, target, g.justification(), j -> {
            List<Map<String, Object>> items = j.sql("SELECT license_id, license_number, act_id FROM public.license_op_grant(?, ?, ?, ?, ?, ?)")
                    .param(actor.userId()).param(target).param(g.count()).param(g.startsAt()).param(g.endsAt()).param(g.justification())
                    .query((rs, n) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("licenseId", rs.getObject(1, UUID.class));
                        m.put("number", rs.getInt(2));
                        return m;
                    }).list();
            LOG.info("evento=licenca.concedida organizacao={} quantidade={} correlacao={}", target, items.size(), AccessActions.correlation());
            return items;
        });
    }

    public Map<String, Object> renew(UUID org, ValidSession actor, String token, UUID target, UUID license, Renewal r) {
        if (r == null || r.endsAt() == null) {
            throw ApiException.invalid("renovacao");
        }
        return act(org, actor, token, target, r.justification(), j -> {
            UUID period = j.sql("SELECT public.license_op_renew(?, ?, ?, ?, ?, ?)").param(actor.userId()).param(target).param(license)
                    .param(r.startsAt()).param(r.endsAt()).param(r.justification()).query(UUID.class).single();
            LOG.info("evento=licenca.renovada organizacao={} licenca={} correlacao={}", target, license, AccessActions.correlation());
            return Map.<String, Object>of("licenseId", license, "periodId", period);
        });
    }

    public void end(UUID org, ValidSession actor, String token, UUID target, UUID license, String justification) {
        act(org, actor, token, target, justification, j -> {
            j.sql("SELECT public.license_op_end(?, ?, ?, ?)").param(actor.userId()).param(target).param(license).param(justification)
                    .query((rs, n) -> 0).list();
            LOG.info("evento=licenca.encerrada organizacao={} licenca={} correlacao={}", target, license, AccessActions.correlation());
            return 0;
        });
    }

    /**
     * Liga ({@code licensed}) ou desliga ({@code legacy}) a exigencia. Para LIGAR, quem opera confirma quantas automacoes em uso
     * ficariam sem licenca pelo resumo do conjunto ({@code confirmPreview}); a previa e refeita NA MESMA TRANSACAO do ato, sob
     * a trava exclusiva do modelo da organizacao, e se o CONJUNTO mudou - mesmo com a mesma quantidade - o ato e
     * recusado ({@code license_preview_changed}) e nada e gravado. O retrato da previa vai para a trilha.
     */
    public Map<String, Object> setModel(UUID org, ValidSession actor, String token, UUID target, String model, String confirmPreview,
                                        String justification) {
        if (!"licensed".equals(model) && !"legacy".equals(model)) {
            throw ApiException.invalid("modelo");
        }
        boolean on = "licensed".equals(model);
        if (on && (confirmPreview == null || confirmPreview.isBlank())) {
            throw ApiException.invalid("confirmacao_da_previa");
        }
        return act(org, actor, token, target, justification, j -> {
            // trava EXCLUSIVA do modelo da organizacao antes de refazer a previa: vinculo, concessao, renovacao, encerramento e
            // (na etapa da conferencia) cada inicio de trabalho pegam a mesma trava em modo compartilhado. O que ja confirmou
            // aparece na previa refeita; o que ainda nao confirmou espera este ato terminar.
            // a linha da organizacao primeiro, como todo ato que admite trabalho (lockOrganizationAndActor): mesma ordem de
            // travas dos dois lados, sem espera cruzada
            j.sql("SELECT 1 FROM public.organizations WHERE organization_id = ? FOR UPDATE").param(target).query(Integer.class).optional();
            j.sql("SELECT pg_advisory_xact_lock(hashtextextended('organization_license_model:' || ?, 0))").param(target.toString())
                    .query((rs, n) -> 0).list();
            Map<String, Object> preview = previewIn(j, target);
            int blocked = ((List<?>) preview.get("blockedAutomations")).size();
            if (on && !confirmPreview.equals(preview.get("previewToken"))) {
                LOG.info("evento=acesso.negado regra=CONFLICT motivo=license_preview_changed correlacao={}", AccessActions.correlation());
                throw new ApiException(ApiError.LICENSE_PREVIEW_CHANGED, "previa_mudou");
            }
            String detail = "{\"previewToken\":\"" + preview.get("previewToken") + "\",\"blockedAutomationIds\":" + ids(preview, "blockedAutomations")
                    + ",\"licensedAutomationIds\":" + ids(preview, "licensedAutomations") + ",\"blockedAutomations\":" + blocked + ",\"offlineAuthorizations\":" + preview.get("offlineAuthorizations")
                    + ",\"workInProgress\":" + preview.get("workInProgress") + ",\"contracted\":" + preview.get("contracted") + ",\"used\":"
                    + preview.get("used") + "}";
            j.sql("SELECT public.license_op_set_model(?, ?, ?::smallint, ?, ?::jsonb)").param(actor.userId()).param(target).param(on ? 2 : 1)
                    .param(justification).param(detail).query(UUID.class).single();
            LOG.info("evento=licenca.exigencia organizacao={} modelo={} impedidas={} correlacao={}", target, model, blocked, AccessActions.correlation());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("organizationId", target);
            r.put("model", model);
            r.put("blockedAutomations", blocked);
            return r;
        });
    }

    private static String ids(Map<String, Object> preview, String key) {
        StringBuilder b = new StringBuilder("[");
        for (Object o : (List<?>) preview.get(key)) {
            b.append(b.length() > 1 ? "," : "").append('"').append(((Map<?, ?>) o).get("automationId")).append('"');
        }
        return b.append(']').toString();
    }

    // ------------------------------------------------------------------ leituras do operador

    /** O que ligar a exigencia faria AGORA nesta organizacao, sem alterar nada. */
    public Map<String, Object> preview(UUID org, ValidSession actor, String token, UUID target) {
        return actions.read(org, j -> {
            requireOperator(j, org, actor, token, target, false);
            return previewIn(j, target);
        });
    }

    // ------------------------------------------------------------------ comum

    private interface Act<T> {
        T run(JdbcClient j);
    }

    private <T> T act(UUID org, ValidSession actor, String token, UUID target, String justification, Act<T> body) {
        if (justification == null || justification.strip().length() < 10 || justification.strip().length() > 1000) {
            throw ApiException.invalid("justificativa");
        }
        return actions.write(org, j -> {
            requireOperator(j, org, actor, token, target, true);
            try {
                return body.run(j);
            } catch (ApiException e) {
                throw e;
            } catch (RuntimeException e) {
                throw refusal(e);
            }
        });
    }

    /**
     * Sessao valida da organizacao interna configurada, conta interna com segundo fator, no cadastro de operadores; e a
     * organizacao alvo existe e NAO e a do operador. Qualquer falha de "quem" responde como rota inexistente ou proibida,
     * sem dizer qual condicao faltou.
     */
    private void requireOperator(JdbcClient j, UUID org, ValidSession actor, String token, UUID target, boolean stepUp) {
        if (operatorOrganization == null || !operatorOrganization.equals(org)) {
            throw AccessActions.refuse(Failure.TARGET_NOT_FOUND, "operacao_de_licencas_fora_da_organizacao_interna");
        }
        actions.requireActor(j, org, actor, token);
        if (actor.withoutSecondFactor()
                || !Boolean.TRUE.equals(j.sql("SELECT public.license_op_is_operator(?)").param(actor.userId()).query(Boolean.class).single())) {
            throw AccessActions.refuse(Failure.PERMISSION, "nao_e_operador_de_licencas");
        }
        if (target == null || target.equals(org)) {
            throw AccessActions.refuse(Failure.TARGET_NOT_FOUND, "organizacao_alvo");
        }
        if (stepUp) {
            // conferida ANTES de a transacao passar a ler a organizacao alvo: a sessao do operador e da organizacao interna
            actions.requireStepUp(j, actor);
        }
        // a partir daqui a transacao le a organizacao ALVO (o isolamento do banco e pelo contexto; as funcoes do operador nao dependem dele)
        j.sql("SELECT set_config('nekkora.organization_id', ?, true)").param(target.toString()).query(String.class).single();
        if (j.sql("SELECT count(*) FROM public.organizations WHERE organization_id = ?").param(target).query(Long.class).single() == 0) {
            throw AccessActions.refuse(Failure.TARGET_NOT_FOUND, "organizacao_alvo");
        }
    }

    /** A previa, lida no contexto da organizacao alvo (ja definido por {@link #requireOperator}). */
    private static Map<String, Object> previewIn(JdbcClient j, UUID target) {
        OffsetDateTime now = j.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
        List<Map<String, Object>> blocked = new ArrayList<>();
        List<Map<String, Object>> licensed = new ArrayList<>();
        long[] offline = {0};
        OffsetDateTime[] offlineUntil = {null};
        long[] inProgress = {0};
        j.sql("""
                SELECT a.automation_id, a.name, a.status, w.name AS workspace_name,
                       (SELECT g.license_id FROM public.organization_license_assignments g
                                 JOIN public.organization_licenses l ON l.organization_id = g.organization_id AND l.license_id = g.license_id
                                WHERE g.organization_id = a.organization_id AND g.automation_id = a.automation_id AND g.released_at IS NULL
                                  AND l.ended_at IS NULL
                                  AND EXISTS (SELECT 1 FROM public.organization_license_periods p
                                               WHERE p.organization_id = l.organization_id AND p.license_id = l.license_id
                                                 AND p.starts_at <= :now AND p.ends_at > :now)) AS covering_license,
                       (SELECT count(*) FROM public.local_run_authorizations z
                         WHERE z.organization_id = a.organization_id AND z.automation_id = a.automation_id AND z.revoked_at IS NULL
                           AND z.valid_until > :now) AS valid_authorizations,
                       (SELECT max(z.valid_until) FROM public.local_run_authorizations z
                         WHERE z.organization_id = a.organization_id AND z.automation_id = a.automation_id AND z.revoked_at IS NULL
                           AND z.valid_until > :now) AS authorized_until,
                       (EXISTS (SELECT 1 FROM public.remote_executions x WHERE x.organization_id = a.organization_id
                                   AND x.automation_id = a.automation_id AND x.requested_at > :since)
                        OR EXISTS (SELECT 1 FROM public.agent_runs r WHERE r.organization_id = a.organization_id
                                      AND r.automation_id = a.automation_id AND r.registered_at > :since)
                        OR EXISTS (SELECT 1 FROM public.executions e WHERE e.organization_id = a.organization_id
                                      AND e.automation_id = a.automation_id AND e.created_at > :since)) AS recent_use,
                       (EXISTS (SELECT 1 FROM public.executions e WHERE e.organization_id = a.organization_id
                                   AND e.automation_id = a.automation_id AND e.status IN (1, 2, 3))
                        OR EXISTS (SELECT 1 FROM public.agent_runs r WHERE r.organization_id = a.organization_id
                                      AND r.automation_id = a.automation_id AND r.supervisor_state IN (1, 2, 3))
                        OR EXISTS (SELECT 1 FROM public.remote_executions x
                                     LEFT JOIN LATERAL (SELECT t.state, t.offer_valid_until FROM public.remote_attempts t
                                                         WHERE t.organization_id = x.organization_id AND t.remote_execution_id = x.remote_execution_id
                                                         ORDER BY t.attempt_number DESC LIMIT 1) t ON true
                                    WHERE x.organization_id = a.organization_id AND x.automation_id = a.automation_id
                                      AND (t.state IS NULL OR t.state BETWEEN 3 AND 6 OR (t.state IN (1, 2) AND t.offer_valid_until > :now)))) AS in_progress
                  FROM public.automations a
                  JOIN public.workspaces w ON w.organization_id = a.organization_id AND w.workspace_id = a.workspace_id
                 WHERE a.organization_id = :org
                 ORDER BY a.name, a.automation_id
                """).param("org", target).param("now", now).param("since", now.minusDays(RECENT_USE_DAYS)).query((rs, n) -> {
            long authorizations = rs.getLong("valid_authorizations");
            boolean recent = rs.getBoolean("recent_use");
            boolean running = rs.getBoolean("in_progress");
            UUID coveringLicense = rs.getObject("covering_license", UUID.class);
            boolean hasLicense = coveringLicense != null;
            if (running) {
                inProgress[0]++;
            }
            if (authorizations == 0 && !recent && !running && !hasLicense) {
                return 0; // sem uso e sem licenca: ligar a exigencia nao muda nada para ela
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("automationId", rs.getObject("automation_id", UUID.class));
            m.put("name", rs.getString("name"));
            m.put("workspaceName", rs.getString("workspace_name"));
            List<String> reasons = new ArrayList<>();
            if (authorizations > 0) {
                reasons.add("validAuthorization");
            }
            if (recent) {
                reasons.add("recentUse");
            }
            if (running) {
                reasons.add("workInProgress");
            }
            m.put("inUseBecause", reasons);
            OffsetDateTime until = rs.getObject("authorized_until", OffsetDateTime.class);
            m.put("authorizationsValidUntil", until);
            if (hasLicense) {
                m.put("licenseId", coveringLicense);
                licensed.add(m);
            } else {
                blocked.add(m);
                offline[0] += authorizations;
                if (until != null && (offlineUntil[0] == null || until.isAfter(offlineUntil[0]))) {
                    offlineUntil[0] = until;
                }
            }
            return 0;
        }).list();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("organizationId", target);
        j.sql("SELECT model, since FROM public.organization_license_enforcement WHERE organization_id = ?").param(target)
                .query((rs, n) -> {
                    r.put("model", rs.getInt(1) == 2 ? "licensed" : "legacy");
                    r.put("modelSince", rs.getObject(2, OffsetDateTime.class));
                    return 0;
                }).optional();
        r.putIfAbsent("model", "legacy");
        r.putIfAbsent("modelSince", null);
        j.sql("""
                SELECT count(*) AS contracted,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM public.organization_license_assignments g
                                                       WHERE g.organization_id = l.organization_id AND g.license_id = l.license_id AND g.released_at IS NULL)) AS used
                  FROM public.organization_licenses l
                 WHERE l.organization_id = :org AND l.ended_at IS NULL
                   AND EXISTS (SELECT 1 FROM public.organization_license_periods p
                                WHERE p.organization_id = l.organization_id AND p.license_id = l.license_id AND p.starts_at <= :now AND p.ends_at > :now)
                """).param("org", target).param("now", now).query((rs, n) -> {
            r.put("contracted", rs.getLong(1));
            r.put("used", rs.getLong(2));
            r.put("available", rs.getLong(1) - rs.getLong(2));
            return 0;
        }).single();
        // ficariam impedidas de INICIAR trabalho novo: em uso e sem licenca vigente
        r.put("blockedAutomations", blocked);
        r.put("licensedAutomations", licensed);
        // LIMITACAO: autorizacoes ja entregues a maquinas do Agent anterior valem sem conexao ate a validade; ligar a exigencia
        // nao bloqueia de imediato uma maquina desconectada
        r.put("offlineAuthorizations", offline[0]);
        r.put("offlineAuthorizationsValidUntil", offlineUntil[0]);
        // trabalho em andamento NAO e interrompido ao ligar
        r.put("workInProgress", inProgress[0]);
        r.put("previewToken", token(String.valueOf(r.get("model")), blocked, licensed));
        r.put("asOf", now);
        return r;
    }

    /**
     * Resumo do CONJUNTO apresentado na previa: o modelo atual, a identidade de cada automacao que ficaria impedida e a de cada
     * automacao coberta com a licenca que a cobre. Mesma quantidade com automacoes ou coberturas diferentes da outro resumo.
     */
    public static String token(String model, List<Map<String, Object>> blocked, List<Map<String, Object>> licensed) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> m : blocked) {
            lines.add("B:" + m.get("automationId"));
        }
        for (Map<String, Object> m : licensed) {
            lines.add("L:" + m.get("automationId") + ":" + m.get("licenseId"));
        }
        java.util.Collections.sort(lines);
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("SHA-256");
            d.update(("modelo:" + model + ";").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (String l : lines) {
                d.update((l + ";").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return java.util.HexFormat.of().formatHex(d.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** As recusas das funcoes da 063 (23514 sem restricao) viram 409 com o motivo do contrato; o resto propaga. */
    private static RuntimeException refusal(RuntimeException e) {
        if (!DbErrors.raisedRefusal(e)) {
            return e;
        }
        ApiError error = mentions(e, "LICENSE_NOT_FOUND") ? ApiError.NOT_FOUND
                : mentions(e, "LICENSE_ENDED") ? ApiError.LICENSE_ENDED
                : mentions(e, "PERIOD_OVERLAPS") ? ApiError.LICENSE_PERIOD_OVERLAPS
                : mentions(e, "PERIOD_ALREADY_ENDED") ? ApiError.LICENSE_PERIOD_ALREADY_ENDED
                : mentions(e, "MODEL_UNCHANGED") ? ApiError.LICENSE_MODEL_UNCHANGED : null;
        if (error == null) {
            return e;
        }
        LOG.info("evento=acesso.negado regra=CONFLICT motivo={} correlacao={}", error.reason(), AccessActions.correlation());
        return new ApiException(error, String.valueOf(error.reason()));
    }

    private static boolean mentions(Throwable t, String marker) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().contains(marker)) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }
}
