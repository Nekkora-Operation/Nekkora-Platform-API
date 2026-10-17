package com.nekkora.platform.web;

import java.util.Arrays;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContextFilter;
import com.nekkora.platform.identity.SignupService;

/**
 * Cadastro web (Missao 1, desenho §6.1), fora de {@code /api/v1/o/{org}}: CSRF de pre-autenticacao e Origin
 * ({@link CsrfFilter}), sem sessao. Pedido ({@code {email}}) e reenvio: 202 sem corpo, sempre a mesma resposta. A senha e
 * o nome so entram na confirmacao ({@code {token, password, displayName}}): o pedido que os traz e recusado (400), para
 * que nenhum cliente suponha ter escolhido a credencial antes da prova de posse do e-mail. Confirmacao: 204, sem
 * cookie de sessao. Desligado por padrao ({@code nekkora.cadastro.enabled=false}): {@link IdentityRoutesGate} responde 404 antes
 * de qualquer outra camada. Nenhum acesso direto ao banco: tudo passa pelo {@link SignupService}.
 */
@RestController
public class SignupController {

    public static final String SIGNUP = "/api/v1/signup";
    public static final String CONFIRM = "/api/v1/signup/confirm";
    public static final String RESEND = "/api/v1/signup/confirmation/resend";

    /** {@code password} e {@code displayName} existem aqui so para serem RECUSADOS: pertencem a confirmacao. */
    public record SignupRequest(String email, char[] password, String displayName) {
    }

    public record ResendRequest(String email) {
    }

    public record ConfirmRequest(String token, char[] password, String displayName) {
    }

    private final SignupService signups;
    private final boolean enabled;

    public SignupController(SignupService signups, @org.springframework.beans.factory.annotation.Value("${nekkora.cadastro.enabled:false}") boolean enabled) {
        this.signups = signups;
        this.enabled = enabled;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ApiException(ApiError.NOT_FOUND, "cadastro_desligado", null);
        }
    }

    @PostMapping(SIGNUP)
    public ResponseEntity<Void> signup(@RequestBody(required = false) SignupRequest body, HttpServletRequest req) {
        char[] password = body == null ? null : body.password();
        try {
            requireEnabled();
            if (password != null || (body != null && body.displayName() != null)) {
                throw ApiException.invalid("campo_da_confirmacao");
            }
            signups.signup(body == null ? null : body.email(), RequestContextFilter.ip(req));
            return ResponseEntity.accepted().build();
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    @PostMapping(RESEND)
    public ResponseEntity<Void> resend(@RequestBody(required = false) ResendRequest body, HttpServletRequest req) {
        requireEnabled();
        signups.resend(body == null ? null : body.email(), RequestContextFilter.ip(req));
        return ResponseEntity.accepted().build();
    }

    @PostMapping(CONFIRM)
    public ResponseEntity<Void> confirm(@RequestBody(required = false) ConfirmRequest body, HttpServletRequest req) {
        char[] password = body == null ? null : body.password();
        try {
            requireEnabled();
            signups.confirm(body == null ? null : body.token(), password, body == null ? null : body.displayName(), RequestContextFilter.ip(req));
            return ResponseEntity.noContent().build();
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }
}
