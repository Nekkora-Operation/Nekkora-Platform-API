package com.nekkora.platform.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContextFilter;
import com.nekkora.platform.identity.IdentityOrganizationsService;
import com.nekkora.platform.identity.OrgSessionExchange;

/**
 * Organizacoes da identidade e troca por sessao de organizacao (Missao 1, desenho §6.4 e §6.5), fora de
 * {@code /api/v1/o/{org}}, com a sessao de identidade do cookie {@code __Host-nekkora_id} (resolvida pelo
 * {@link IdentityFilter}). A sessao de organizacao emitida vai so no cookie {@code __Host-nekkora_session}. Mesma chave da
 * entrada geral ({@code nekkora.entrada-geral.enabled}). A criacao de organizacao (contrato P2) tem chave PROPRIA,
 * {@code nekkora.criacao-organizacao.enabled}, e so abre com as duas ligadas. Sem acesso direto ao banco.
 */
@RestController
public class IdentityOrganizationsController {

    public static final String MY_ORGANIZATIONS = "/api/v1/me/organizations";
    public static final String ORG_SESSION = "/api/v1/sessions/organization";
    public static final String ORGANIZATIONS = "/api/v1/organizations";
    /** Contrato P2: 1 a 120 pontos de codigo, sem espaco nas pontas, sem caractere de controle. */
    static final int NAME_MAX_CODE_POINTS = 120;

    private static final Pattern UUID_CANONICAL = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    public record ExchangeRequest(String organizationId) {
    }

    public record CreateRequest(String name, String legalName) {
    }

    private final IdentityOrganizationsService organizations;
    private final OrgSessionExchange exchange;
    private final boolean enabled;
    private final boolean creationEnabled;

    public IdentityOrganizationsController(IdentityOrganizationsService organizations, OrgSessionExchange exchange,
                                           @Value("${nekkora.entrada-geral.enabled:false}") boolean enabled,
                                           @Value("${nekkora.criacao-organizacao.enabled:false}") boolean creationEnabled) {
        this.organizations = organizations;
        this.exchange = exchange;
        this.enabled = enabled;
        this.creationEnabled = creationEnabled;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ApiException(ApiError.NOT_FOUND, "entrada_geral_desligada", null);
        }
    }

    @GetMapping(MY_ORGANIZATIONS)
    public Map<String, Object> list(HttpServletRequest req) {
        requireEnabled();
        IdentityOrganizationsService.Listing l = organizations.list(IdentityFilter.principalOrNull(req), IdentityFilter.token(req),
                RequestContextFilter.ip(req));
        List<Map<String, Object>> items = l.organizations().stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("organizationId", o.organizationId());
            m.put("name", o.name());
            m.put("roles", List.copyOf(o.roles()));
            return m;
        }).toList();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("organizations", items);
        r.put("canCreateOrganization", l.canCreateOrganization());
        return r;
    }

    @PostMapping(ORGANIZATIONS)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> create(@RequestBody(required = false) CreateRequest body, HttpServletRequest req) {
        if (!enabled || !creationEnabled) {
            throw new ApiException(ApiError.NOT_FOUND, "criacao_organizacao_desligada", null);
        }
        if (body == null || !validName(body.name()) || (body.legalName() != null && !validName(body.legalName()))) {
            throw ApiException.invalid("nome");
        }
        UUID org = organizations.create(IdentityFilter.principalOrNull(req), IdentityFilter.token(req), body.name(), body.legalName(),
                RequestContextFilter.ip(req));
        return Map.of("organizationId", org.toString());
    }

    /**
     * 1 a 120 pontos de codigo; sem espaco nas pontas (branco Java OU separador Unicode, como U+00A0, que
     * {@code String.strip} nao remove); sem controle nem surrogate isolado.
     */
    static boolean validName(String s) {
        if (s == null || s.isEmpty() || space(s.codePointAt(0)) || space(s.codePointBefore(s.length()))) {
            return false;
        }
        int n = s.codePointCount(0, s.length());
        return n <= NAME_MAX_CODE_POINTS && s.codePoints().noneMatch(c -> Character.getType(c) == Character.CONTROL || Character.getType(c) == Character.SURROGATE);
    }

    private static boolean space(int c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    @PostMapping(ORG_SESSION)
    public Map<String, String> exchange(@RequestBody(required = false) ExchangeRequest body, HttpServletRequest req, HttpServletResponse res) {
        requireEnabled();
        String raw = body == null ? null : body.organizationId();
        if (raw != null && !UUID_CANONICAL.matcher(raw).matches()) {
            throw ApiException.invalid("organizacao");
        }
        OrgSessionExchange.Result r = exchange.exchange(IdentityFilter.principalOrNull(req), IdentityFilter.token(req),
                raw == null ? null : UUID.fromString(raw), RequestContextFilter.ip(req));
        if (!r.issued()) {
            return Map.of("status", r.status());
        }
        Cookies.session(res, r.token(), r.expiresAt());
        return Map.of("status", r.status(), "csrfToken", r.csrfToken());
    }
}
