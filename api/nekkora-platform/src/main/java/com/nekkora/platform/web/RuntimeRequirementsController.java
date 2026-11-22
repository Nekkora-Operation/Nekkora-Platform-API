package com.nekkora.platform.web;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.web.ApiException;
import com.nekkora.platform.admin.RuntimeRequirementsService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Dependencias de execucao declaradas para uma automacao (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01c parte 2), lado das
 * pessoas. Sessao da organizacao + CSRF nas escritas (filtros comuns). Ler exige automation.read; analisar um texto e
 * declarar exigem automation.manage. Nenhuma rota aceita credencial de maquina.
 */
@RestController
public class RuntimeRequirementsController {

    public record AnalysisRequest(String text) {
    }

    /** {@code text}: so com source FILE ou TEXT. {@code expectedRevision}: a revisao vista (ausente = nenhuma ainda). */
    public record DeclareRequest(String source, String text, Integer expectedRevision) {
    }

    private final RuntimeRequirementsService service;

    public RuntimeRequirementsController(RuntimeRequirementsService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/o/{org}/automations/{automation}/runtime-requirements")
    public RuntimeRequirementsService.View get(@PathVariable UUID org, @PathVariable UUID automation, HttpServletRequest req) {
        return service.get(org, SessionFilter.principal(req), SessionFilter.token(req), automation);
    }

    /** Leitura estatica de um texto, sem gravar: a tela mostra as linhas antes de a pessoa declarar. */
    @PostMapping("/api/v1/o/{org}/automations/{automation}/runtime-requirements/analysis")
    public ResponseEntity<RuntimeRequirementsService.Analysis> analyze(@PathVariable UUID org, @PathVariable UUID automation,
                                                                       @RequestBody(required = false) AnalysisRequest body, HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.analyze(org, SessionFilter.principal(req), SessionFilter.token(req), automation, body.text()));
    }

    @PutMapping("/api/v1/o/{org}/automations/{automation}/runtime-requirements")
    public ResponseEntity<RuntimeRequirementsService.View> declare(@PathVariable UUID org, @PathVariable UUID automation,
                                                                   @RequestBody(required = false) DeclareRequest body, HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(
                service.declare(org, SessionFilter.principal(req), SessionFilter.token(req), automation, body.source(), body.text(), body.expectedRevision()));
    }
}
