package com.nekkora.platform.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.web.ApiException;
import com.nekkora.platform.admin.ManagedEnvironmentService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * AMBIENTE-PYTHON-001 (076): o ambiente Python por execucao de uma versao numa maquina, lado das pessoas. Sessao da organizacao
 * + CSRF nas escritas (filtros comuns). Ler exige automation.read; pedir a resolucao, aceitar e revogar exigem automation.manage
 * (aceitar e revogar, com confirmacao reforcada). Chave desligada: 404 managed_environment_unavailable.
 */
@RestController
public class ManagedEnvironmentController {

    private static final String BASE = "/api/v1/o/{org}/automations/{automation}/remote-machines/{agent}/managed-environment";

    public record ResolutionRequest(String versionId) {
    }

    public record AuthorizationRequest(String versionId, String resolutionId, String lockSha256, List<String> checks, Boolean acknowledge) {
    }

    private final ManagedEnvironmentService service;

    public ManagedEnvironmentController(ManagedEnvironmentService service) {
        this.service = service;
    }

    @GetMapping(BASE)
    public ResponseEntity<ManagedEnvironmentService.View> view(@PathVariable UUID org, @PathVariable UUID automation, @PathVariable UUID agent,
                                                               @RequestParam(required = false) UUID versionId, HttpServletRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.view(org, SessionFilter.principal(req), SessionFilter.token(req), automation, agent, versionId));
    }

    /** Pede a maquina que resolva a lista completa (D1) da lista efetiva da versao. A resposta chega depois, na leitura. */
    @PostMapping(BASE + "/resolutions")
    public ResponseEntity<ManagedEnvironmentService.View> resolve(@PathVariable UUID org, @PathVariable UUID automation, @PathVariable UUID agent,
                                                                  @RequestBody(required = false) ResolutionRequest body, HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.requestResolution(org, SessionFilter.principal(req), SessionFilter.token(req), automation, agent, uuid(body.versionId())));
    }

    /** O aceite explicito do lock resolvido, com os navegadores a conferir (D5). Confirmacao reforcada. */
    @PostMapping(BASE + "/authorizations")
    public ResponseEntity<ManagedEnvironmentService.View> authorize(@PathVariable UUID org, @PathVariable UUID automation, @PathVariable UUID agent,
                                                                    @RequestBody(required = false) AuthorizationRequest body, HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.authorize(org, SessionFilter.principal(req), SessionFilter.token(req), automation, agent, uuid(body.versionId()),
                        uuid(body.resolutionId()), body.lockSha256(), body.checks(), Boolean.TRUE.equals(body.acknowledge())));
    }

    /** Volta ao Python preparado (revoga o aceite vigente). Confirmacao reforcada. */
    @DeleteMapping(BASE + "/authorizations/current")
    public ResponseEntity<Void> revoke(@PathVariable UUID org, @PathVariable UUID automation, @PathVariable UUID agent,
                                       @RequestParam(required = false) UUID versionId, HttpServletRequest req) {
        service.revoke(org, SessionFilter.principal(req), SessionFilter.token(req), automation, agent, versionId);
        return ResponseEntity.noContent().build();
    }

    private static UUID uuid(String v) {
        if (v == null) {
            return null;
        }
        try {
            UUID u = UUID.fromString(v);
            if (!u.toString().equals(v.toLowerCase(java.util.Locale.ROOT))) {
                throw ApiException.invalid("uuid");
            }
            return u;
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("uuid");
        }
    }
}
