package com.nekkora.platform.web;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.web.ApiException;
import com.nekkora.platform.admin.OrganizationRequestService;

/**
 * PLATFORM-LICENCIAMENTO-001 (067) - pedidos de criacao de organizacao feitos a esta organizacao: lista e decisao, para quem a
 * administra ({@code org.manage}; decidir exige confirmacao reforcada). Sessao da organizacao + CSRF (filtros comuns).
 */
@RestController
public class OrganizationRequestController {

    public record ApproveRequest(String responsible) {
    }

    public record RefuseRequest(String reason) {
    }

    private final OrganizationRequestService service;

    public OrganizationRequestController(OrganizationRequestService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/o/{org}/organization-requests")
    public Map<String, Object> list(@PathVariable UUID org, HttpServletRequest req) {
        return service.list(org, SessionFilter.principal(req), SessionFilter.token(req));
    }

    @PostMapping("/api/v1/o/{org}/organization-requests/{requestId}/approve")
    public Map<String, Object> approve(@PathVariable UUID org, @PathVariable UUID requestId, @RequestBody(required = false) ApproveRequest body,
                                       HttpServletRequest req) {
        if (body == null || body.responsible() == null) {
            throw ApiException.invalid("responsavel");
        }
        return service.decide(org, SessionFilter.principal(req), SessionFilter.token(req), requestId, true, body.responsible(), null);
    }

    @PostMapping("/api/v1/o/{org}/organization-requests/{requestId}/refuse")
    public Map<String, Object> refuse(@PathVariable UUID org, @PathVariable UUID requestId, @RequestBody(required = false) RefuseRequest body,
                                      HttpServletRequest req) {
        if (body == null || body.reason() == null) {
            throw ApiException.invalid("motivo");
        }
        return service.decide(org, SessionFilter.principal(req), SessionFilter.token(req), requestId, false, null, body.reason());
    }
}
