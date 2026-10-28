package com.nekkora.platform.web;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.platform.admin.GrantAdminService;
import com.nekkora.platform.admin.InvitationService;
import com.nekkora.platform.admin.RoleAdminService;

/**
 * Administracao de acesso da organizacao (contrato de 2026-10-27): convites, papeis, retirada de vinculo e concessoes (a
 * leitura da auditoria fica em {@link AdminReadController}). Sessao da organizacao e CSRF dela (cadeia comum de {@code /api/v1/o/{org}}); a autorizacao e toda dos
 * servicos, dentro da transacao do ato. As rotas de convite seguem a chave {@code nekkora.convites.enabled} (so com a
 * entrada geral ligada: sem ela nao ha como aceitar); desligada, 404.
 */
@RestController
public class AccessAdminController {

    public record InvitationRequest(String email, String role, String workspaceId) {
    }

    public record RoleRequest(String role, String workspaceId) {
    }

    public record GrantRequest(String clientMembershipId, String workspaceId, String startsAt, String expiresAt, String nextReviewAt) {
    }

    public record ApprovalRequest(String nextReviewAt, String expiresAt, String justification) {
    }

    public record ReviewRequest(String decision, String notes, String nextReviewAt) {
    }

    private final InvitationService invitations;
    private final RoleAdminService roles;
    private final GrantAdminService grants;
    private final boolean invitationsEnabled;

    public AccessAdminController(InvitationService invitations, RoleAdminService roles, GrantAdminService grants,
                                 @Value("${nekkora.entrada-geral.enabled:false}") boolean entryEnabled,
                                 @Value("${nekkora.convites.enabled:false}") boolean invitationsEnabled) {
        this.invitations = invitations;
        this.roles = roles;
        this.grants = grants;
        this.invitationsEnabled = entryEnabled && invitationsEnabled;
    }

    // === Convites ===

    @PostMapping("/api/v1/o/{org}/invitations")
    public ResponseEntity<InvitationService.Created> issue(@PathVariable UUID org, @RequestBody(required = false) InvitationRequest body,
                                                           HttpServletRequest req) {
        requireInvitations();
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(invitations.issue(org, SessionFilter.principal(req), SessionFilter.token(req),
                body.email(), body.role(), uuidOrNull(body.workspaceId(), "workspace")));
    }

    @GetMapping("/api/v1/o/{org}/invitations")
    public Map<String, List<InvitationService.Item>> listInvitations(@PathVariable UUID org, @RequestParam(required = false) String state,
                                                                     HttpServletRequest req) {
        requireInvitations();
        boolean pendingOnly = switch (state == null ? "pending" : state) {
            case "pending" -> true;
            case "all" -> false;
            default -> throw ApiException.invalid("state");
        };
        return Map.of("items", invitations.list(org, SessionFilter.principal(req), SessionFilter.token(req), pendingOnly));
    }

    @PostMapping("/api/v1/o/{org}/invitations/{invitation}/revoke")
    public ResponseEntity<Void> revokeInvitation(@PathVariable UUID org, @PathVariable UUID invitation, HttpServletRequest req) {
        requireInvitations();
        invitations.revoke(org, SessionFilter.principal(req), SessionFilter.token(req), invitation);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/o/{org}/invitations/{invitation}/resend")
    public ResponseEntity<InvitationService.Created> resend(@PathVariable UUID org, @PathVariable UUID invitation, HttpServletRequest req) {
        requireInvitations();
        return ResponseEntity.status(HttpStatus.CREATED).body(invitations.resend(org, SessionFilter.principal(req), SessionFilter.token(req), invitation));
    }

    // === Papeis e vinculo ===

    @GetMapping("/api/v1/o/{org}/users/{user}/roles")
    public RoleAdminService.MemberRoles memberRoles(@PathVariable UUID org, @PathVariable UUID user, HttpServletRequest req) {
        return roles.list(org, SessionFilter.principal(req), SessionFilter.token(req), user);
    }

    @PostMapping("/api/v1/o/{org}/users/{user}/roles")
    public RoleAdminService.Assignment assign(@PathVariable UUID org, @PathVariable UUID user, @RequestBody(required = false) RoleRequest body,
                                              HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return roles.assign(org, SessionFilter.principal(req), SessionFilter.token(req), user, body.role(), uuidOrNull(body.workspaceId(), "workspace"));
    }

    @PostMapping("/api/v1/o/{org}/role-assignments/{assignment}/replace")
    public RoleAdminService.Assignment replace(@PathVariable UUID org, @PathVariable UUID assignment, @RequestBody(required = false) RoleRequest body,
                                               HttpServletRequest req) {
        if (body == null || body.workspaceId() != null) {
            throw ApiException.invalid("corpo");
        }
        return roles.replace(org, SessionFilter.principal(req), SessionFilter.token(req), assignment, body.role());
    }

    @PostMapping("/api/v1/o/{org}/role-assignments/{assignment}/revoke")
    public ResponseEntity<Void> revokeRole(@PathVariable UUID org, @PathVariable UUID assignment, HttpServletRequest req) {
        roles.revoke(org, SessionFilter.principal(req), SessionFilter.token(req), assignment);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/o/{org}/users/{user}/membership/revoke")
    public ResponseEntity<Void> revokeMembership(@PathVariable UUID org, @PathVariable UUID user, HttpServletRequest req) {
        roles.revokeMembership(org, SessionFilter.principal(req), SessionFilter.token(req), user);
        return ResponseEntity.noContent().build();
    }

    // === Concessoes (a revogacao direta continua em AccountController, rota da GAP-01) ===

    @PostMapping("/api/v1/o/{org}/client-grants")
    public ResponseEntity<GrantAdminService.Grant> createGrant(@PathVariable UUID org, @RequestBody(required = false) GrantRequest body,
                                                               HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(grants.create(org, SessionFilter.principal(req), SessionFilter.token(req),
                uuid(body.clientMembershipId(), "vinculo_cliente"), uuid(body.workspaceId(), "workspace"), instantOrNull(body.startsAt(), "inicio"),
                instantOrNull(body.expiresAt(), "prazo"), instant(body.nextReviewAt(), "proxima_revisao")));
    }

    @PostMapping("/api/v1/o/{org}/client-grants/{grant}/approve")
    public GrantAdminService.Grant approve(@PathVariable UUID org, @PathVariable UUID grant, @RequestBody(required = false) ApprovalRequest body,
                                           HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return grants.approve(org, SessionFilter.principal(req), SessionFilter.token(req), grant, instant(body.nextReviewAt(), "proxima_revisao"),
                instantOrNull(body.expiresAt(), "prazo"), body.justification());
    }

    /** Pedido de renovacao (024): nao muda o acesso; a aprovacao e de outra pessoa ({@code /renew/approve}). */
    @PostMapping("/api/v1/o/{org}/client-grants/{grant}/renew")
    public ResponseEntity<GrantAdminService.Grant> renew(@PathVariable UUID org, @PathVariable UUID grant, @RequestBody(required = false) ApprovalRequest body,
                                                         HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(grants.requestRenewal(org, SessionFilter.principal(req), SessionFilter.token(req), grant,
                instant(body.nextReviewAt(), "proxima_revisao"), instantOrNull(body.expiresAt(), "prazo"), body.justification()));
    }

    @PostMapping("/api/v1/o/{org}/client-grants/{grant}/renew/approve")
    public GrantAdminService.Grant approveRenewal(@PathVariable UUID org, @PathVariable UUID grant, @RequestBody(required = false) ApprovalRequest body,
                                                  HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return grants.approveRenewal(org, SessionFilter.principal(req), SessionFilter.token(req), grant, instant(body.nextReviewAt(), "proxima_revisao"),
                instantOrNull(body.expiresAt(), "prazo"));
    }

    @PostMapping("/api/v1/o/{org}/client-grants/{grant}/review")
    public GrantAdminService.Grant review(@PathVariable UUID org, @PathVariable UUID grant, @RequestBody(required = false) ReviewRequest body,
                                          HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        GrantAdminService.Decision d = GrantAdminService.Decision.parse(body.decision()).orElseThrow(() -> ApiException.invalid("decisao"));
        return grants.review(org, SessionFilter.principal(req), SessionFilter.token(req), grant, d, body.notes(),
                instantOrNull(body.nextReviewAt(), "proxima_revisao"));
    }

    private void requireInvitations() {
        if (!invitationsEnabled) {
            throw new ApiException(ApiError.NOT_FOUND, "convites_desligados");
        }
    }

    private static UUID uuid(String raw, String field) {
        UUID u = uuidOrNull(raw, field);
        if (u == null) {
            throw ApiException.invalid(field);
        }
        return u;
    }

    private static UUID uuidOrNull(String raw, String field) {
        if (raw == null) {
            return null;
        }
        if (!raw.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
            throw ApiException.invalid(field);
        }
        return UUID.fromString(raw);
    }

    private static OffsetDateTime instant(String raw, String field) {
        OffsetDateTime t = instantOrNull(raw, field);
        if (t == null) {
            throw ApiException.invalid(field);
        }
        return t;
    }

    /** Instante RFC 3339 com fuso explicito (ex.: 2026-11-30T12:00:00Z). */
    private static OffsetDateTime instantOrNull(String raw, String field) {
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException e) {
            throw ApiException.invalid(field);
        }
    }
}
