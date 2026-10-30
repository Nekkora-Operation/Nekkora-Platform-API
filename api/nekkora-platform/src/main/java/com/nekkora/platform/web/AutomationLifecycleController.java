package com.nekkora.platform.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.web.ApiException;
import com.nekkora.platform.admin.AutomationLifecycleService;
import com.nekkora.platform.artifact.PackageInspector;

/**
 * Ciclo de vida das automacoes (PLATFORM-AUTOMATION-LIFECYCLE-001), lado das PESSOAS: sessao da organizacao e CSRF dela
 * (cadeia comum de {@code /api/v1/o/{org}}). Nenhuma rota de maquina; nada aqui executa, autoriza execucao ou fala com o Agent.
 * O artefato chega como corpo {@code application/zip} (ate 50 MiB); recusa de pacote: 422 com um motivo de vocabulario fechado.
 */
@RestController
public class AutomationLifecycleController {

    public record WorkspaceRequest(String name, String description) {
    }

    public record AutomationRequest(String name) {
    }

    public record VersionRequest(String runtime, List<String> arguments) {
    }

    public record PublishRequest(String expectedArtifactSha256, String expectedPackageFingerprint) {
    }

    private final AutomationLifecycleService service;

    public AutomationLifecycleController(AutomationLifecycleService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/o/{org}/workspaces")
    public ResponseEntity<AutomationLifecycleService.Workspace> createWorkspace(@PathVariable UUID org, @RequestBody(required = false) WorkspaceRequest body,
                                                                                HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createWorkspace(org, SessionFilter.principal(req), SessionFilter.token(req), body.name(), body.description()));
    }

    @PostMapping("/api/v1/o/{org}/workspaces/{workspaceId}/automations")
    public ResponseEntity<AutomationLifecycleService.Automation> createAutomation(@PathVariable UUID org, @PathVariable("workspaceId") UUID workspace,
                                                                                  @RequestBody(required = false) AutomationRequest body, HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createAutomation(org, SessionFilter.principal(req), SessionFilter.token(req), workspace, body.name()));
    }

    @PostMapping("/api/v1/o/{org}/automations/{automationId}/versions")
    public ResponseEntity<AutomationLifecycleService.Version> createVersion(@PathVariable UUID org, @PathVariable("automationId") UUID automation,
                                                                            @RequestBody(required = false) VersionRequest body, HttpServletRequest req) {
        VersionRequest b = body == null ? new VersionRequest(null, null) : body;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createVersion(org, SessionFilter.principal(req), SessionFilter.token(req), automation, b.runtime(), b.arguments()));
    }

    @GetMapping("/api/v1/o/{org}/automation-versions/{version}")
    public AutomationLifecycleService.Version version(@PathVariable UUID org, @PathVariable UUID version, HttpServletRequest req) {
        return service.getVersion(org, SessionFilter.principal(req), SessionFilter.token(req), version);
    }

    @PatchMapping("/api/v1/o/{org}/automation-versions/{version}")
    public AutomationLifecycleService.Version updateDraft(@PathVariable UUID org, @PathVariable UUID version, @RequestBody(required = false) VersionRequest body,
                                                          HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return service.updateDraft(org, SessionFilter.principal(req), SessionFilter.token(req), version, body.runtime(), body.arguments());
    }

    @PutMapping(path = "/api/v1/o/{org}/automation-versions/{version}/artifact", consumes = "application/zip")
    public ResponseEntity<?> attachArtifact(@PathVariable UUID org, @PathVariable UUID version, HttpServletRequest req) {
        byte[] zip = body(req);
        try {
            return ResponseEntity.ok(service.attachArtifact(org, SessionFilter.principal(req), SessionFilter.token(req), version, zip));
        } catch (PackageInspector.Rejected e) {
            return ResponseEntity.status(422).body(rejected(e.reason()));
        }
    }

    @GetMapping("/api/v1/o/{org}/automation-versions/{version}/artifact/content")
    public ResponseEntity<byte[]> artifactContent(@PathVariable UUID org, @PathVariable UUID version, HttpServletRequest req) {
        AutomationLifecycleService.Content c = service.artifactContent(org, SessionFilter.principal(req), SessionFilter.token(req), version);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + c.sha256() + ".zip\"")
                .header("X-Artifact-Sha256", c.sha256()).body(c.bytes());
    }

    @PostMapping("/api/v1/o/{org}/automation-versions/{version}/publish")
    public AutomationLifecycleService.Version publish(@PathVariable UUID org, @PathVariable UUID version, @RequestBody(required = false) PublishRequest body,
                                                      HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return service.publish(org, SessionFilter.principal(req), SessionFilter.token(req), version, body.expectedArtifactSha256(),
                body.expectedPackageFingerprint());
    }

    /** {"error":"package_rejected","reason":"<motivo>"} nesta ordem (o error sempre primeiro, como nas demais respostas). */
    private static Map<String, String> rejected(String reason) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("error", "package_rejected");
        m.put("reason", reason);
        return m;
    }

    /** Corpo com teto: nunca mais que o limite do pacote mais um byte em memoria. */
    private static byte[] body(HttpServletRequest req) {
        long declared = req.getContentLengthLong();
        if (declared > PackageInspector.MAX_ZIP_BYTES) {
            throw ApiException.invalid("tamanho");
        }
        try (InputStream in = req.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(0, Math.min(declared, 1 << 20)));
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > PackageInspector.MAX_ZIP_BYTES) {
                    throw ApiException.invalid("tamanho");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw ApiException.invalid("corpo");
        }
    }
}
