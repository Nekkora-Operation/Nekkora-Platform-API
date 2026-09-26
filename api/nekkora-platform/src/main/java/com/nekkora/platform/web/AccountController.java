package com.nekkora.platform.web;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;
import com.nekkora.core.web.ApiException;
import com.nekkora.platform.admin.AdminService;
import com.nekkora.platform.session.ValidSession;

/** {@code /me} e rotas internas do primeiro corte (contrato 8.7, §5.3). */
@RestController
public class AccountController {

    private final OrgTransactions tx;
    private final AdminService admin;

    public AccountController(OrgTransactions tx, AdminService admin) {
        this.tx = tx;
        this.admin = admin;
    }

    /** Allowlist: tipo de conta, nome, e-mail e organizacao. */
    @GetMapping("/api/v1/o/{org}/me")
    public Map<String, Object> me(@PathVariable UUID org, HttpServletRequest req) {
        ValidSession s = SessionFilter.principal(req);
        return tx.inOrganization(org, Mode.READ_ONLY, j -> j.sql("SELECT display_name, email FROM public.users WHERE user_id = ?")
                .param(s.userId())
                .query((rs, n) -> Map.<String, Object>of("accountType", s.isInternal() ? "internal" : "external",
                        "displayName", rs.getString("display_name"), "email", rs.getString("email"), "organizationId", org.toString()))
                .optional().orElseThrow(() -> ApiException.unauthenticated("conta")));
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

    @PostMapping("/api/v1/o/{org}/users/{user}/mfa/reset")
    public ResponseEntity<Void> resetMfa(@PathVariable UUID org, @PathVariable UUID user, HttpServletRequest req) {
        admin.resetMfa(org, SessionFilter.principal(req), SessionFilter.token(req), user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/o/{org}/client-grants/{grant}/revoke")
    public ResponseEntity<Void> revokeGrant(@PathVariable UUID org, @PathVariable UUID grant, HttpServletRequest req) {
        admin.revokeGrant(org, SessionFilter.principal(req), SessionFilter.token(req), grant);
        return ResponseEntity.noContent().build();
    }
}
