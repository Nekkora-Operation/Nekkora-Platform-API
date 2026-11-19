package com.nekkora.platform.web;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.web.ApiException;
import com.nekkora.platform.admin.LicenseService;

/**
 * PLATFORM-LICENCIAMENTO-001 (D1) - escrita das licencas pelo administrador da organizacao: so o vinculo explicito a uma
 * automacao. Sessao da organizacao + CSRF (filtros comuns), {@code org.manage} e confirmacao reforcada. Conceder, renovar,
 * encerrar, transferir e ligar a exigencia NAO estao aqui: sao atos do operador da Nekkora.
 */
@RestController
public class LicenseController {

    public record AssignRequest(String automationId) {
    }

    private final LicenseService service;

    public LicenseController(LicenseService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/o/{org}/licenses/{licenseId}/assignment")
    public Map<String, Object> assign(@PathVariable UUID org, @PathVariable UUID licenseId, @RequestBody(required = false) AssignRequest body,
                                      HttpServletRequest req) {
        UUID automation;
        try {
            automation = body == null || body.automationId() == null ? null : UUID.fromString(body.automationId());
        } catch (IllegalArgumentException e) {
            automation = null;
        }
        if (automation == null) {
            throw ApiException.invalid("automacao");
        }
        return service.assign(org, SessionFilter.principal(req), SessionFilter.token(req), licenseId, automation);
    }
}
