package com.nekkora.platform.web;

import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContextFilter;
import com.nekkora.platform.identity.InvitationAcceptService;

/**
 * Aceite de convite (contrato de 2026-10-27, §4.3), fora de {@code /api/v1/o/{org}}, com a sessao de identidade do cookie
 * {@code __Host-nekkora_id} (resolvida pelo {@link IdentityFilter}) e o CSRF dela. A organizacao vai no corpo e chega a
 * funcao ANTES do token: o hash so e procurado dentro dela. Chave {@code nekkora.convites.enabled}, que so abre com a da
 * entrada geral tambem ligada ({@link IdentityRoutesGate} e as mesmas chaves aqui). Sem acesso direto ao banco.
 */
@RestController
public class InvitationAcceptController {

    public static final String ACCEPT = "/api/v1/invitations/accept";
    /** 071: nome e e-mail do convite aberto para o primeiro acesso (pre-autenticacao; so com o token). */
    public static final String PREVIEW = "/api/v1/invitations/preview";

    private static final Pattern UUID_CANONICAL = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    public record AcceptRequest(String organizationId, String token) {
        @Override
        public String toString() {
            return "AcceptRequest[organizationId=" + organizationId + "]";
        }
    }

    /** {@code audience}: internal (troca para a sessao da organizacao) ou client (070: area do cliente, pela identidade). */
    public record Accepted(String organizationId, String audience) {
    }

    private final InvitationAcceptService invitations;
    private final boolean enabled;

    public InvitationAcceptController(InvitationAcceptService invitations, @Value("${nekkora.entrada-geral.enabled:false}") boolean entryEnabled,
                                      @Value("${nekkora.convites.enabled:false}") boolean invitationsEnabled) {
        this.invitations = invitations;
        this.enabled = entryEnabled && invitationsEnabled;
    }

    /**
     * 071: o primeiro acesso pelo link do convite mostra a organizacao e preenche nome e e-mail (que nao sao pedidos de novo). So
     * convite aberto e no prazo; o resto e 404 unico. Nao confirma a identidade: o cadastro continua confirmando o e-mail pelo codigo,
     * e o aceite exige a identidade com o mesmo e-mail verificado.
     */
    @PostMapping(PREVIEW)
    public InvitationAcceptService.Preview preview(@RequestBody(required = false) AcceptRequest body, HttpServletRequest req) {
        if (!enabled) {
            throw new ApiException(ApiError.NOT_FOUND, "convites_desligados");
        }
        if (body == null || body.organizationId() == null || !UUID_CANONICAL.matcher(body.organizationId()).matches() || body.token() == null) {
            throw ApiException.invalid("corpo");
        }
        return invitations.preview(UUID.fromString(body.organizationId()), body.token(), RequestContextFilter.ip(req));
    }

    /** 200 com a organizacao: a identidade troca de sessao depois pela rota existente ({@code /api/v1/sessions/organization}). */
    @PostMapping(ACCEPT)
    public Accepted accept(@RequestBody(required = false) AcceptRequest body, HttpServletRequest req) {
        if (!enabled) {
            throw new ApiException(ApiError.NOT_FOUND, "convites_desligados");
        }
        if (body == null || body.organizationId() == null || !UUID_CANONICAL.matcher(body.organizationId()).matches() || body.token() == null) {
            throw ApiException.invalid("corpo");
        }
        UUID org = UUID.fromString(body.organizationId());
        String audience = invitations.accept(IdentityFilter.principalOrNull(req), IdentityFilter.token(req), org, body.token(), RequestContextFilter.ip(req));
        return new Accepted(org.toString(), audience);
    }
}
