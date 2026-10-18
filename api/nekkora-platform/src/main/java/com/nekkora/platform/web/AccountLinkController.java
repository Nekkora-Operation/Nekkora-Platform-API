package com.nekkora.platform.web;

import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContextFilter;
import com.nekkora.platform.identity.AccountLinkService;
import com.nekkora.platform.identity.IdentityLinkRepository;

/**
 * Vinculacao de conta antiga (contrato P3), fora de {@code /api/v1/o/{org}}, com a sessao de identidade do cookie
 * {@code __Host-nekkora_id} (resolvida pelo {@link IdentityFilter}) e o CSRF dela. O desafio entre as etapas vai so no
 * cookie {@code __Host-nekkora_link} (SameSite=Strict, 5 min). Chave propria, {@code nekkora.vinculacao.enabled}, que so
 * abre com a da entrada geral tambem ligada ({@link IdentityRoutesGate} e as mesmas chaves aqui). Sem acesso direto ao
 * banco.
 */
@RestController
public class AccountLinkController {

    public static final String START = "/api/v1/links/start";
    public static final String CONFIRM = "/api/v1/links/confirm";

    private static final Pattern UUID_CANONICAL = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Exatamente um de {@code code} (TOTP da conta) e {@code recoveryCode} (codigo de recuperacao da conta). */
    public record StartRequest(String organizationId, char[] password, String code, String recoveryCode) {
    }

    public record ConfirmRequest(String organizationId) {
    }

    private final AccountLinkService links;
    private final boolean enabled;

    public AccountLinkController(AccountLinkService links, @Value("${nekkora.entrada-geral.enabled:false}") boolean entryEnabled,
                                 @Value("${nekkora.vinculacao.enabled:false}") boolean linkEnabled) {
        this.links = links;
        this.enabled = entryEnabled && linkEnabled;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ApiException(ApiError.NOT_FOUND, "vinculacao_desligada", null);
        }
    }

    /** Etapa 1: 204 + cookie do desafio. Qualquer falha da prova: 401 unico. */
    @PostMapping(START)
    public ResponseEntity<Void> start(@RequestBody(required = false) StartRequest body, HttpServletRequest req, HttpServletResponse res) {
        char[] password = body == null ? null : body.password();
        try {
            requireEnabled();
            if (body == null || password == null || (body.code() == null) == (body.recoveryCode() == null)) {
                throw ApiException.invalid("corpo");
            }
            UUID org = organization(body.organizationId());
            String token = links.start(IdentityFilter.principalOrNull(req), IdentityFilter.token(req), org, password, body.code(), body.recoveryCode(),
                    RequestContextFilter.ip(req));
            Cookies.link(res, token, IdentityLinkRepository.CHALLENGE_VALIDITY);
            return ResponseEntity.noContent().build();
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    /** Etapa 2: 204 vinculada; 403 recusa. Nos dois casos (e em qualquer erro depois da validacao do corpo) o cookie do desafio expira. */
    @PostMapping(CONFIRM)
    public ResponseEntity<Void> confirm(@RequestBody(required = false) ConfirmRequest body, HttpServletRequest req, HttpServletResponse res) {
        requireEnabled();
        UUID org = organization(body == null ? null : body.organizationId());
        String linkToken = Cookies.read(req, Cookies.LINK);
        try {
            links.confirm(IdentityFilter.principalOrNull(req), IdentityFilter.token(req), linkToken, org, RequestContextFilter.ip(req));
        } finally {
            Cookies.expire(res, Cookies.LINK, "Strict");
        }
        return ResponseEntity.noContent().build();
    }

    private static UUID organization(String raw) {
        if (raw == null || !UUID_CANONICAL.matcher(raw).matches()) {
            throw ApiException.invalid("organizacao");
        }
        return UUID.fromString(raw);
    }
}
