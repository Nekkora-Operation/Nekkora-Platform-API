package com.nekkora.platform.web;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.web.ApiException;
import com.nekkora.platform.admin.AutomationActivationService;
import com.nekkora.platform.session.ValidSession;

/**
 * PLATFORM-FLUXO-002 - ativacao unificada da automacao ("Licencas e automacoes"). So conta interna com {@code org.manage};
 * os atos exigem CSRF (filtros comuns) e confirmacao reforcada. As rotas antigas de autorizacao local continuam existindo,
 * por compatibilidade (Agent anterior e integracoes), mas a Web deixa de oferece-las como etapa separada.
 */
@RestController
public class AutomationActivationController {

    public record ActivateRequest(String automationId, List<String> workerIds, String validUntil) {
    }

    public record VersionRequest(String automationVersionId, String validUntil) {
    }

    private final AutomationActivationService service;

    public AutomationActivationController(AutomationActivationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/o/{org}/automation-activations")
    public Map<String, Object> list(@PathVariable UUID org, HttpServletRequest req) {
        return service.list(org, internal(SessionFilter.principal(req)), SessionFilter.token(req));
    }

    @GetMapping("/api/v1/o/{org}/automation-activations/options")
    public Map<String, Object> options(@PathVariable UUID org, @RequestParam(name = "workspaceId", required = false) String workspaceId,
                                       HttpServletRequest req) {
        return service.options(org, internal(SessionFilter.principal(req)), SessionFilter.token(req), uuid(workspaceId, "ambiente"));
    }

    @GetMapping("/api/v1/o/{org}/automation-activations/{automationId}")
    public Map<String, Object> get(@PathVariable UUID org, @PathVariable UUID automationId, HttpServletRequest req) {
        return service.get(org, internal(SessionFilter.principal(req)), SessionFilter.token(req), automationId);
    }

    @PostMapping("/api/v1/o/{org}/automation-activations")
    public Map<String, Object> activate(@PathVariable UUID org, @RequestBody(required = false) ActivateRequest body, HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        List<UUID> workers = new ArrayList<>();
        if (body.workerIds() != null) {
            for (String w : body.workerIds()) {
                workers.add(uuid(w, "worker"));
            }
        }
        return service.activate(org, internal(SessionFilter.principal(req)), SessionFilter.token(req), uuid(body.automationId(), "automacao"), workers,
                instant(body.validUntil()));
    }

    @PostMapping("/api/v1/o/{org}/automation-activations/{automationId}/version")
    public Map<String, Object> acceptVersion(@PathVariable UUID org, @PathVariable UUID automationId, @RequestBody(required = false) VersionRequest body,
                                             HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return service.acceptVersion(org, internal(SessionFilter.principal(req)), SessionFilter.token(req), automationId,
                uuid(body.automationVersionId(), "versao"), instant(body.validUntil()));
    }

    @PostMapping("/api/v1/o/{org}/automation-activations/{automationId}/renewal")
    public Map<String, Object> renew(@PathVariable UUID org, @PathVariable UUID automationId, HttpServletRequest req) {
        return service.renew(org, internal(SessionFilter.principal(req)), SessionFilter.token(req), automationId);
    }

    private static UUID uuid(String v, String field) {
        try {
            if (v != null) {
                return UUID.fromString(v);
            }
        } catch (IllegalArgumentException e) {
            // cai na recusa abaixo
        }
        throw ApiException.invalid(field);
    }

    private static OffsetDateTime instant(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(v);
        } catch (DateTimeParseException e) {
            throw ApiException.invalid("validade");
        }
    }

    private static ValidSession internal(ValidSession s) {
        if (!s.isInternal()) {
            throw ApiException.forbidden("conta_externa");
        }
        return s;
    }
}
