package com.nekkora.platform.admin;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nekkora.core.db.DbErrors;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.platform.access.WorkspaceAccess;
import com.nekkora.platform.admin.AccessActions.Failure;
import com.nekkora.platform.session.ValidSession;

/**
 * PLATFORM-LICENCIAMENTO-001 (D1) - o vinculo EXPLICITO de uma licenca a uma automacao, pelo administrador da organizacao.
 * Modelo nas migrations 061 e 062; contrato em docs/platform-licenciamento-001/t03-vinculo-pelo-administrador.md.
 *
 * <p>Quem vincula: {@code org.manage}, com confirmacao reforcada. O ato roda sob a trava da organizacao, entao duas
 * tentativas simultaneas se enfileiram; alem disso o banco garante por indice unico que uma licenca atende uma automacao e
 * que uma automacao consome uma licenca, e reconfere a permissao de quem vincula. O vinculo nao e desfeito por esta classe:
 * a Platform nao tem privilegio para isso.
 *
 * <p>NADA aqui decide se uma execucao pode comecar: nesta etapa nenhum fluxo confere licenca.
 */
public class LicenseService {

    private static final Logger LOG = LoggerFactory.getLogger(LicenseService.class);
    /** Ato 4 da trilha (061): VINCULADA a uma automacao pelo administrador da organizacao. */
    private static final short ACT_ASSIGNED = 4;
    static final String ONE_AUTOMATION_PER_LICENSE = "ux_organization_license_assignments_license";
    static final String ONE_LICENSE_PER_AUTOMATION = "ux_organization_license_assignments_automation";

    private final AccessActions actions;
    private final WorkspaceAccess access;

    public LicenseService(AccessActions actions, WorkspaceAccess access) {
        this.actions = actions;
        this.access = access;
    }

    public Map<String, Object> assign(UUID org, ValidSession actor, String token, UUID license, UUID automation) {
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            access.requireAnyOrganizationPermission(j, org, actor.membershipId(), "org.manage");
            actions.requireStepUp(j, actor);
            actions.lockOrganizationAndActor(j, org, actor.userId());
            Integer number = j.sql("SELECT license_number FROM public.organization_licenses WHERE organization_id = ? AND license_id = ?")
                    .param(org).param(license).query(Integer.class).optional()
                    .orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "licenca"));
            UUID workspace = j.sql("SELECT workspace_id FROM public.automations WHERE organization_id = ? AND automation_id = ?")
                    .param(org).param(automation).query(UUID.class).optional()
                    .orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "automacao"));
            OffsetDateTime at;
            try {
                at = j.sql("INSERT INTO public.organization_license_assignments (organization_id, license_id, workspace_id, automation_id, "
                                + "assigned_by_membership_id) VALUES (?, ?, ?, ?, ?) RETURNING assigned_at")
                        .param(org).param(license).param(workspace).param(automation).param(actor.membershipId())
                        .query(OffsetDateTime.class).single();
            } catch (RuntimeException e) {
                throw refusal(e);
            }
            j.sql("INSERT INTO public.organization_license_acts (organization_id, act, license_id, to_automation_id, actor_membership_id) "
                    + "VALUES (?, ?, ?, ?, ?)").param(org).param(ACT_ASSIGNED).param(license).param(automation).param(actor.membershipId()).update();
            LOG.info("evento=licenca.vinculada licenca={} automacao={} correlacao={}", license, automation, AccessActions.correlation());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("licenseId", license);
            r.put("number", number);
            r.put("automationId", automation);
            r.put("workspaceId", workspace);
            r.put("assignedAt", at);
            return r;
        });
    }

    /** As recusas do banco (061/062) viram 409 com o motivo do contrato; a transacao e desfeita e nada fica gravado. */
    private static RuntimeException refusal(RuntimeException e) {
        ApiError error = null;
        if (DbErrors.uniqueViolation(e, ONE_AUTOMATION_PER_LICENSE)) {
            error = ApiError.LICENSE_ALREADY_ASSIGNED;
        } else if (DbErrors.uniqueViolation(e, ONE_LICENSE_PER_AUTOMATION)) {
            error = ApiError.AUTOMATION_ALREADY_LICENSED;
        } else if (DbErrors.raisedRefusal(e)) {
            error = mentions(e, "AUTOMATION_ARCHIVED") ? ApiError.LICENSE_AUTOMATION_ARCHIVED
                    : mentions(e, "LICENSE_NOT_VALID") ? ApiError.LICENSE_NOT_VALID : null;
        }
        if (error == null) {
            return e;
        }
        LOG.info("evento=acesso.negado regra=CONFLICT motivo={} correlacao={}", error.reason(), AccessActions.correlation());
        return new ApiException(error, error.reason());
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
