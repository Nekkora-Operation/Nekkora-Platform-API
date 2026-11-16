package com.nekkora.platform.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import com.nekkora.platform.admin.RemoteExecutionService;
import com.nekkora.platform.paging.Paging;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Solicitacao de execucao remota (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T02), lado das pessoas. Sessao da organizacao + CSRF
 * nas escritas (filtros comuns). Solicitar, repetir em outra maquina e cancelar exigem automation.execute + step-up; ler
 * exige automation.read. Nenhuma rota aceita credencial de maquina.
 */
@RestController
public class RemoteExecutionController {

    /** {@code requestKey}: UUID gerado pelo navegador para a repeticao idempotente (resposta perdida). */
    public record CreateRequest(String automationVersionId, String agentId, String requestKey) {
    }

    public record RetryRequest(String agentId) {
    }

    private final RemoteExecutionService service;

    public RemoteExecutionController(RemoteExecutionService service) {
        this.service = service;
    }

    /** As maquinas habilitadas do ambiente para uma versao, com o que falta em cada uma para solicitar. */
    @GetMapping("/api/v1/o/{org}/workspaces/{workspace}/remote-executions/candidates")
    public Map<String, List<RemoteExecutionService.Candidate>> candidates(@PathVariable UUID org, @PathVariable UUID workspace,
                                                                          @RequestParam(name = "automationVersion", required = false) String version,
                                                                          HttpServletRequest req) {
        UUID versionId = uuid(version);
        if (versionId == null) {
            throw ApiException.invalid("versao");
        }
        return Map.of("items", service.candidates(org, SessionFilter.principal(req), SessionFilter.token(req), workspace, versionId));
    }

    @PostMapping("/api/v1/o/{org}/workspaces/{workspace}/remote-executions")
    public ResponseEntity<RemoteExecutionService.Execution> request(@PathVariable UUID org, @PathVariable UUID workspace,
                                                                    @RequestBody(required = false) CreateRequest body, HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        RemoteExecutionService.Created c = service.request(org, SessionFilter.principal(req), SessionFilter.token(req), workspace,
                uuid(body.automationVersionId()), uuid(body.agentId()), uuid(body.requestKey()));
        return ResponseEntity.status(c.created() ? HttpStatus.CREATED : HttpStatus.OK).cacheControl(CacheControl.noStore()).body(c.execution());
    }

    @GetMapping("/api/v1/o/{org}/workspaces/{workspace}/remote-executions")
    public Map<String, List<RemoteExecutionService.Execution>> list(@PathVariable UUID org, @PathVariable UUID workspace,
                                                                    @RequestParam(required = false) Integer limit, HttpServletRequest req) {
        return Map.of("items", service.list(org, SessionFilter.principal(req), SessionFilter.token(req), workspace, Paging.limit(limit)));
    }

    @GetMapping("/api/v1/o/{org}/remote-executions/{execution}")
    public ResponseEntity<RemoteExecutionService.Execution> get(@PathVariable UUID org, @PathVariable UUID execution, HttpServletRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.get(org, SessionFilter.principal(req), SessionFilter.token(req), execution));
    }

    @PostMapping("/api/v1/o/{org}/remote-executions/{execution}/cancel")
    public ResponseEntity<RemoteExecutionService.Execution> cancel(@PathVariable UUID org, @PathVariable UUID execution, HttpServletRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.cancel(org, SessionFilter.principal(req), SessionFilter.token(req), execution));
    }

    /** "Executar novamente em outra maquina": so depois que a tentativa anterior terminou. */
    @PostMapping("/api/v1/o/{org}/remote-executions/{execution}/attempts")
    public ResponseEntity<RemoteExecutionService.Execution> retry(@PathVariable UUID org, @PathVariable UUID execution,
                                                                  @RequestBody(required = false) RetryRequest body, HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.retry(org, SessionFilter.principal(req), SessionFilter.token(req), execution, uuid(body.agentId())));
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
