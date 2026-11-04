package com.nekkora.platform.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.web.ApiException;
import com.nekkora.platform.admin.RunRequestService;
import com.nekkora.platform.paging.Paging;

/**
 * Pedido de execucao (AGENT-PYTHON-001, 030), lado das pessoas. Sessao da organizacao + CSRF nas escritas (filtros comuns);
 * criar e revogar exigem automation.execute + step-up; ler exige automation.read. Nenhuma rota aceita credencial de maquina.
 */
@RestController
public class RunRequestController {

    /** {@code requestKey}: UUID gerado pelo navegador para a repeticao idempotente (resposta perdida). */
    public record CreateRequest(String authorizationId, String agentId, Integer validForSeconds, String requestKey) {
    }

    private final RunRequestService service;

    public RunRequestController(RunRequestService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/o/{org}/workspaces/{workspace}/run-requests")
    public ResponseEntity<Map<String, Object>> create(@PathVariable UUID org, @PathVariable UUID workspace, @RequestBody(required = false) CreateRequest body,
                                                      HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        Map<String, Object> r = service.create(org, SessionFilter.principal(req), SessionFilter.token(req), workspace, uuid(body.authorizationId()),
                uuid(body.agentId()), body.validForSeconds(), uuid(body.requestKey()));
        boolean created = Boolean.TRUE.equals(r.remove("created"));
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).cacheControl(CacheControl.noStore()).body(r);
    }

    @GetMapping("/api/v1/o/{org}/workspaces/{workspace}/run-requests")
    public Map<String, List<Map<String, Object>>> list(@PathVariable UUID org, @PathVariable UUID workspace, @RequestParam(required = false) Integer limit,
                                                      HttpServletRequest req) {
        return Map.of("items", service.list(org, SessionFilter.principal(req), SessionFilter.token(req), workspace, Paging.limit(limit)));
    }

    @GetMapping("/api/v1/o/{org}/run-requests/{runRequest}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable UUID org, @PathVariable UUID runRequest, HttpServletRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(org, SessionFilter.principal(req), SessionFilter.token(req), runRequest));
    }

    @PostMapping("/api/v1/o/{org}/run-requests/{runRequest}/revoke")
    public Map<String, Object> revoke(@PathVariable UUID org, @PathVariable UUID runRequest, HttpServletRequest req) {
        return service.revoke(org, SessionFilter.principal(req), SessionFilter.token(req), runRequest);
    }

    private static UUID uuid(String s) {
        if (s == null || !s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw ApiException.invalid("uuid");
        }
        return UUID.fromString(s);
    }
}
