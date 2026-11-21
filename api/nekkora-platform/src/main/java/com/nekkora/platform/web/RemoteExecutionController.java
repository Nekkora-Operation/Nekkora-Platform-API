package com.nekkora.platform.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
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
    public record CreateRequest(String automationVersionId, String agentId, String requestKey, Boolean authorizeVersion,
                                String authorizationValidUntil) {
    }

    public record RetryRequest(String agentId, Boolean authorizeVersion, String authorizationValidUntil) {
    }

    /**
     * S2: {@code authorizeVersion: true} e o aceite EXPLICITO da versao naquela maquina, no mesmo passo. Ausente ou falso: nada
     * e autorizado e a falta de autorizacao continua recusando. A validade so e lida com o aceite.
     */
    private static RemoteExecutionService.VersionAcceptance acceptance(Boolean authorizeVersion, String validUntil) {
        if (!Boolean.TRUE.equals(authorizeVersion)) {
            return null;
        }
        java.time.OffsetDateTime until = instant(validUntil);
        if (until != null && !until.isAfter(java.time.OffsetDateTime.now())) {
            throw ApiException.invalid("validade");
        }
        return new RemoteExecutionService.VersionAcceptance(until);
    }

    public record PythonSelection(String environmentId) {
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

    /** Escolhe o ambiente Python de uma automacao numa maquina habilitada do ambiente dela (do inventario que a maquina publicou). */
    @org.springframework.web.bind.annotation.PutMapping("/api/v1/o/{org}/automations/{automation}/remote-machines/{agent}/python-selection")
    public RemoteExecutionService.PythonChoice selectPython(@PathVariable UUID org, @PathVariable UUID automation, @PathVariable UUID agent,
                                                            @RequestBody(required = false) PythonSelection body, HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return service.selectPython(org, SessionFilter.principal(req), SessionFilter.token(req), automation, agent, body.environmentId());
    }

    /** 054: pede a maquina que confira agora as dependencias desta automacao no Python escolhido para ela. A resposta vem em candidates. */
    @PostMapping("/api/v1/o/{org}/automations/{automation}/remote-machines/{agent}/dependency-check")
    public ResponseEntity<RemoteExecutionService.DependencyCheck> checkDependencies(@PathVariable UUID org, @PathVariable UUID automation,
                                                                                   @PathVariable UUID agent, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.checkDependencies(org, SessionFilter.principal(req), SessionFilter.token(req), automation, agent));
    }

    @PostMapping("/api/v1/o/{org}/workspaces/{workspace}/remote-executions")
    public ResponseEntity<RemoteExecutionService.Execution> request(@PathVariable UUID org, @PathVariable UUID workspace,
                                                                    @RequestBody(required = false) CreateRequest body, HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        RemoteExecutionService.Created c = service.request(org, SessionFilter.principal(req), SessionFilter.token(req), workspace,
                uuid(body.automationVersionId()), uuid(body.agentId()), uuid(body.requestKey()),
                acceptance(body.authorizeVersion(), body.authorizationValidUntil()));
        return ResponseEntity.status(c.created() ? HttpStatus.CREATED : HttpStatus.OK).cacheControl(CacheControl.noStore()).body(c.execution());
    }

    @GetMapping("/api/v1/o/{org}/workspaces/{workspace}/remote-executions")
    public Map<String, List<RemoteExecutionService.Execution>> list(@PathVariable UUID org, @PathVariable UUID workspace,
                                                                    @RequestParam(required = false) Integer limit, HttpServletRequest req) {
        return Map.of("items", service.list(org, SessionFilter.principal(req), SessionFilter.token(req), workspace, Paging.limit(limit)));
    }

    /**
     * As solicitacoes dos ambientes visiveis para a pessoa (telas Execucoes e Supervisao). Sem cursor: as {@code limit} mais
     * recentes do periodo; periodo invertido ou instante sem fuso: 400.
     */
    @GetMapping("/api/v1/o/{org}/remote-executions")
    public ResponseEntity<Map<String, List<RemoteExecutionService.Execution>>> listVisible(@PathVariable UUID org,
            @RequestParam(required = false) UUID workspaceId, @RequestParam(required = false) UUID automationId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Integer limit, HttpServletRequest req) {
        java.time.OffsetDateTime fromTime = instant(from);
        java.time.OffsetDateTime toTime = instant(to);
        if (fromTime != null && toTime != null && toTime.isBefore(fromTime)) {
            throw ApiException.invalid("periodo");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("items", service.listVisible(org, SessionFilter.principal(req),
                SessionFilter.token(req), workspaceId, automationId, fromTime, toTime, Paging.limit(limit))));
    }

    private static java.time.OffsetDateTime instant(String s) {
        if (s == null) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(s);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.invalid("instante");
        }
    }

    @GetMapping("/api/v1/o/{org}/remote-executions/{execution}")
    public ResponseEntity<RemoteExecutionService.Execution> get(@PathVariable UUID org, @PathVariable UUID execution, HttpServletRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.get(org, SessionFilter.principal(req), SessionFilter.token(req), execution));
    }

    /** T05: as linhas da saida do processo de uma tentativa, paginadas pela sequencia, e o fechamento do envio (logs.read). */
    @GetMapping("/api/v1/o/{org}/remote-executions/{execution}/attempts/{attempt}/logs")
    public ResponseEntity<RemoteExecutionService.Logs> logs(@PathVariable UUID org, @PathVariable UUID execution, @PathVariable UUID attempt,
                                                            @RequestParam(required = false) Integer after, @RequestParam(required = false) Integer limit,
                                                            HttpServletRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.logs(org, SessionFilter.principal(req), SessionFilter.token(req), execution, attempt, after, limit));
    }

    /** T03b: os arquivos de resultado de uma tentativa e o fechamento do envio (logs.read). */
    @GetMapping("/api/v1/o/{org}/remote-executions/{execution}/attempts/{attempt}/results")
    public ResponseEntity<RemoteExecutionService.Results> results(@PathVariable UUID org, @PathVariable UUID execution, @PathVariable UUID attempt,
                                                                  HttpServletRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.results(org, SessionFilter.principal(req), SessionFilter.token(req), execution, attempt));
    }

    /** T03b: os bytes de um arquivo de resultado, como anexo; nunca interpretado pelo navegador. */
    @GetMapping("/api/v1/o/{org}/remote-executions/{execution}/attempts/{attempt}/results/{number}/content")
    public ResponseEntity<byte[]> resultContent(@PathVariable UUID org, @PathVariable UUID execution, @PathVariable UUID attempt, @PathVariable int number,
                                                HttpServletRequest req) {
        RemoteExecutionService.ResultContent c = service.resultContent(org, SessionFilter.principal(req), SessionFilter.token(req), execution, attempt, number);
        String base = c.fileName().substring(c.fileName().lastIndexOf('/') + 1);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", ContentDisposition.attachment().filename(base, java.nio.charset.StandardCharsets.UTF_8)
                        .build().toString())
                .header("X-Content-Type-Options", "nosniff").header("Nekkora-Result-Sha256", c.sha256()).body(c.bytes());
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
                .body(service.retry(org, SessionFilter.principal(req), SessionFilter.token(req), execution, uuid(body.agentId()),
                        acceptance(body.authorizeVersion(), body.authorizationValidUntil())));
    }

    public record ReconciliationRequest(String reason, String verification, Boolean acknowledgePartialEffects) {
    }

    /** 058: reconciliacao DECLARADA por um ORG_ADMIN, com confirmacao reforcada. Nao executa nada; libera uma nova tentativa. */
    @PostMapping("/api/v1/o/{org}/remote-executions/{execution}/attempts/{attempt}/reconciliation")
    public ResponseEntity<RemoteExecutionService.Execution> reconcile(@PathVariable UUID org, @PathVariable UUID execution, @PathVariable UUID attempt,
                                                                      @RequestBody(required = false) ReconciliationRequest body, HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.reconcileManually(org, SessionFilter.principal(req), SessionFilter.token(req), execution, attempt, body.reason(),
                        body.verification(), Boolean.TRUE.equals(body.acknowledgePartialEffects())));
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
