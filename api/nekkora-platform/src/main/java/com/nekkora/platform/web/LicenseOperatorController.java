package com.nekkora.platform.web;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.web.ApiException;
import com.nekkora.platform.admin.LicenseOperatorService;

/**
 * PLATFORM-LICENCIAMENTO-001 (D5) - rotas do OPERADOR DA NEKKORA. {@code org} e a organizacao interna da Nekkora (a da sessao do
 * operador); {@code target} e a organizacao do cliente. Sessao + CSRF nas escritas (filtros comuns). Quem pode e o que cada ato
 * confere estao em {@link LicenseOperatorService}. Fora da organizacao interna configurada, tudo aqui responde 404.
 */
@RestController
public class LicenseOperatorController {

    public record GrantRequest(Integer count, String startsAt, String endsAt, String justification) {
    }

    public record RenewRequest(String startsAt, String endsAt, String justification) {
    }

    public record EndRequest(String justification) {
    }

    /** {@code confirmPreview}: obrigatorio para ligar; o {@code previewToken} da previa conferida (o conjunto, nao a quantidade). */
    public record ModelRequest(String model, String confirmPreview, String justification) {
    }

    private static final String BASE = "/api/v1/o/{org}/license-operations/organizations/{target}";

    private final LicenseOperatorService service;

    public LicenseOperatorController(LicenseOperatorService service) {
        this.service = service;
    }

    public record CreateOrganizationRequest(String responsibleEmail, String name, String legalName, String justification) {
    }

    public record ReconcileRequest(String justification, String verification) {
    }

    /** A sessao e de um operador? (a tela usa para mostrar a area do operador). 404 fora da organizacao interna; 403 sem cadastro. */
    @GetMapping("/api/v1/o/{org}/license-operations")
    public Map<String, Object> status(@PathVariable UUID org, HttpServletRequest req) {
        return service.status(org, SessionFilter.principal(req), SessionFilter.token(req));
    }

    /** 068: criacao excepcional de organizacao, com a pessoa responsavel indicada pelo operador. */
    @PostMapping("/api/v1/o/{org}/license-operations/organizations")
    public ResponseEntity<Map<String, Object>> createOrganization(@PathVariable UUID org, @RequestBody(required = false) CreateOrganizationRequest body,
                                                                  HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return ResponseEntity.status(201).body(service.createOrganization(org, SessionFilter.principal(req), SessionFilter.token(req),
                new LicenseOperatorService.ExceptionalCreation(body.responsibleEmail(), body.name(), body.legalName(), body.justification())));
    }

    /** A organizacao alvo vista pelo operador: modelo, licencas e automacoes. */
    @GetMapping(BASE)
    public Map<String, Object> overview(@PathVariable UUID org, @PathVariable UUID target, HttpServletRequest req) {
        return service.overview(org, SessionFilter.principal(req), SessionFilter.token(req), target);
    }

    /** 068: sessoes do Agent anterior sem desfecho confiavel, que ainda seguram transferencia ou vaga. */
    @GetMapping(BASE + "/previous-agent-sessions")
    public Map<String, Object> unresolvedSessions(@PathVariable UUID org, @PathVariable UUID target, HttpServletRequest req) {
        return service.unresolvedSessions(org, SessionFilter.principal(req), SessionFilter.token(req), target);
    }

    /** 068: reconciliacao de uma sessao pelo suporte. Nao altera a sessao. */
    @PostMapping(BASE + "/previous-agent-sessions/{sessionId}/reconciliation")
    public Map<String, Object> reconcile(@PathVariable UUID org, @PathVariable UUID target, @PathVariable UUID sessionId,
                                         @RequestBody(required = false) ReconcileRequest body, HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return service.reconcile(org, SessionFilter.principal(req), SessionFilter.token(req), target, sessionId, body.justification(),
                body.verification());
    }

    /** Previa: o que ligar a exigencia faria agora nesta organizacao. Nao altera nada. */
    @GetMapping(BASE + "/enforcement-preview")
    public Map<String, Object> preview(@PathVariable UUID org, @PathVariable UUID target, HttpServletRequest req) {
        return service.preview(org, SessionFilter.principal(req), SessionFilter.token(req), target);
    }

    @PostMapping(BASE + "/licenses")
    public Map<String, Object> grant(@PathVariable UUID org, @PathVariable UUID target, @RequestBody(required = false) GrantRequest body,
                                     HttpServletRequest req) {
        if (body == null || body.count() == null) {
            throw ApiException.invalid("corpo");
        }
        List<Map<String, Object>> items = service.grant(org, SessionFilter.principal(req), SessionFilter.token(req), target,
                new LicenseOperatorService.Grant(body.count(), instant(body.startsAt()), instant(body.endsAt()), body.justification()));
        return Map.of("items", items);
    }

    @PostMapping(BASE + "/licenses/{licenseId}/renewal")
    public Map<String, Object> renew(@PathVariable UUID org, @PathVariable UUID target, @PathVariable UUID licenseId,
                                     @RequestBody(required = false) RenewRequest body, HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return service.renew(org, SessionFilter.principal(req), SessionFilter.token(req), target, licenseId,
                new LicenseOperatorService.Renewal(instant(body.startsAt()), instant(body.endsAt()), body.justification()));
    }

    @PostMapping(BASE + "/licenses/{licenseId}/end")
    public ResponseEntity<Void> end(@PathVariable UUID org, @PathVariable UUID target, @PathVariable UUID licenseId,
                                    @RequestBody(required = false) EndRequest body, HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        service.end(org, SessionFilter.principal(req), SessionFilter.token(req), target, licenseId, body.justification());
        return ResponseEntity.noContent().build();
    }

    public record TransferRequest(String toAutomationId, String requesterEmail, String ticketReference, String identityVerification,
                                  String justification) {
    }

    @PostMapping(BASE + "/licenses/{licenseId}/transfer")
    public Map<String, Object> transfer(@PathVariable UUID org, @PathVariable UUID target, @PathVariable UUID licenseId,
                                        @RequestBody(required = false) TransferRequest body, HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        UUID to;
        try {
            to = body.toAutomationId() == null ? null : UUID.fromString(body.toAutomationId());
        } catch (IllegalArgumentException e) {
            to = null;
        }
        return service.transfer(org, SessionFilter.principal(req), SessionFilter.token(req), target, licenseId,
                new LicenseOperatorService.Transfer(to, body.requesterEmail(), body.ticketReference(), body.identityVerification(), body.justification()));
    }

    @PostMapping(BASE + "/enforcement")
    public Map<String, Object> model(@PathVariable UUID org, @PathVariable UUID target, @RequestBody(required = false) ModelRequest body,
                                     HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return service.setModel(org, SessionFilter.principal(req), SessionFilter.token(req), target, body.model(), body.confirmPreview(),
                body.justification());
    }

    /** Data ISO-8601 COM fuso; ausente -> nulo (o servico decide se e obrigatoria); invalida -> 400. */
    private static OffsetDateTime instant(String s) {
        if (s == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException e) {
            throw ApiException.invalid("data");
        }
    }
}
