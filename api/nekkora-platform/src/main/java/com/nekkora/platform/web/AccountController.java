package com.nekkora.platform.web;

import java.util.LinkedHashMap;
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

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;
import com.nekkora.core.web.ApiException;
import com.nekkora.platform.admin.AdminService;
import com.nekkora.platform.admin.GrantAdminService;
import com.nekkora.platform.identity.IdentityOrgRepository;
import com.nekkora.platform.session.ValidSession;

/** {@code /me} e rotas internas do primeiro corte (contrato 8.7, §5.3). */
@RestController
public class AccountController {

    private final OrgTransactions tx;
    private final AdminService admin;
    private final GrantAdminService grants;

    public AccountController(OrgTransactions tx, AdminService admin, GrantAdminService grants) {
        this.tx = tx;
        this.admin = admin;
        this.grants = grants;
    }

    /**
     * Allowlist: tipo de conta, nome, e-mail, organizacao e o estado de ativacao dela (022: AGUARDANDO_ATIVACAO | ATIVADA, so
     * indicacao; independente da situacao administrativa).
     */
    @GetMapping("/api/v1/o/{org}/me")
    public Map<String, Object> me(@PathVariable UUID org, HttpServletRequest req) {
        ValidSession s = SessionFilter.principal(req);
        return tx.inOrganization(org, Mode.READ_ONLY, j -> {
            Map<String, Object> conta = j.sql("SELECT display_name, email FROM public.users WHERE user_id = ?")
                    .param(s.userId())
                    .query((rs, n) -> Map.<String, Object>of("accountType", s.isInternal() ? "internal" : "external",
                            "displayName", rs.getString("display_name"), "email", rs.getString("email"), "organizationId", org.toString()))
                    .optional().orElseThrow(() -> ApiException.unauthenticated("conta"));
            Map<String, Object> r = new LinkedHashMap<>(conta);
            r.put("organizationActivationState", IdentityOrgRepository.activationStates(j, List.of(org))
                    .getOrDefault(org, IdentityOrgRepository.AGUARDANDO_ATIVACAO));
            return r;
        });
    }

    @PostMapping("/api/v1/o/{org}/users/{user}/suspend")
    public ResponseEntity<Void> suspend(@PathVariable UUID org, @PathVariable UUID user, HttpServletRequest req) {
        admin.suspend(org, SessionFilter.principal(req), SessionFilter.token(req), user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/o/{org}/users/{user}/reactivate")
    public ResponseEntity<Void> reactivate(@PathVariable UUID org, @PathVariable UUID user, HttpServletRequest req) {
        admin.reactivate(org, SessionFilter.principal(req), SessionFilter.token(req), user);
        return ResponseEntity.noContent().build();
    }

    /** Corpo do reset administrativo: motivo codificado obrigatorio (016, D5). */
    public record MfaReset(String reason) {
    }

    @PostMapping("/api/v1/o/{org}/users/{user}/mfa/reset")
    public ResponseEntity<Void> resetMfa(@PathVariable UUID org, @PathVariable UUID user, @RequestBody(required = false) MfaReset body,
                                         HttpServletRequest req) {
        AdminService.ResetReason reason = AdminService.ResetReason.parse(body == null ? null : body.reason())
                .orElseThrow(() -> ApiException.invalid("reason"));
        admin.resetMfa(org, SessionFilter.principal(req), SessionFilter.token(req), user, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/o/{org}/client-grants/{grant}/revoke")
    public ResponseEntity<Void> revokeGrant(@PathVariable UUID org, @PathVariable UUID grant, HttpServletRequest req) {
        grants.revoke(org, SessionFilter.principal(req), SessionFilter.token(req), grant);
        return ResponseEntity.noContent().build();
    }
}
