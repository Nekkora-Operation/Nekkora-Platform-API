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

    public record OrganizationRequest(String organizationId, String name, String legalName) {
    }

    public static final String ORGANIZATION_REQUESTS = "/api/v1/organization-requests";
    public static final String MY_ORGANIZATION_REQUESTS = "/api/v1/me/organization-requests";
    public static final String DEACTIVATIONS = "/api/v1/organization-deactivations";
    public static final String REACTIVATIONS = "/api/v1/organization-reactivations";

    public record OrganizationRef(String organizationId) {
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
            m.put("activationState", o.activationState());
            m.put("handle", o.handle());
            return m;
        }).toList();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("organizations", items);
        r.put("canCreateOrganization", l.canCreateOrganization());
        return r;
    }

    public static final String MY_ORGANIZATION_CREATION = "/api/v1/me/organization-creation";

    /**
     * 067: o perfil de quem cria (FIRST, ADMIN, MEMBER, CLIENT), os limites e as organizacoes de que a pessoa e responsavel.
     * So indicacao para a interface: o servidor decide em cada ato.
     */
    @GetMapping(MY_ORGANIZATION_CREATION)
    public Map<String, Object> creation(HttpServletRequest req) {
        requireCreation();
        IdentityOrganizationsService.Creation l = organizations.creation(IdentityFilter.principalOrNull(req), IdentityFilter.token(req),
                RequestContextFilter.ip(req));
        var c = l.state();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("profile", c.profile());
        r.put("canCreate", IdentityOrganizationsService.creationReason(c) == null);
        r.put("cannotCreateReason", IdentityOrganizationsService.creationReason(c));
        r.put("canRequest", "MEMBER".equals(c.profile()) && c.pendingRequests() < 3);
        r.put("organizationsCreated", usage(c.organizationsCreated(), IdentityOrganizationsService.ORGANIZATIONS_PER_IDENTITY));
        Map<String, Object> empty = usage(c.emptyOrganizations().size(), c.emptyLimit());
        empty.put("organizationIds", c.emptyOrganizations());
        r.put("emptyOrganizations", empty);
        r.put("pendingRequests", usage(c.pendingRequests(), 3));
        r.put("responsibleFor", l.responsibleFor().stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("organizationId", o.organizationId());
            m.put("name", o.name());
            m.put("state", o.state());
            m.put("empty", o.empty());
            m.put("deactivatedAt", o.deactivatedAt());
            m.put("canReactivate", o.canReactivate());
            return m;
        }).toList());
        return r;
    }

    private static Map<String, Object> usage(long used, long limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("used", used);
        m.put("limit", limit);
        return m;
    }

    private void requireCreation() {
        if (!enabled || !creationEnabled) {
            throw new ApiException(ApiError.NOT_FOUND, "criacao_organizacao_desligada", null);
        }
    }

    private static UUID uuid(String raw, String what) {
        if (raw == null || !UUID_CANONICAL.matcher(raw).matches()) {
            throw ApiException.invalid(what);
        }
        return UUID.fromString(raw);
    }

    /** 067: o membro pede a criacao ao administrador da organizacao {@code organizationId}, em que e membro. */
    @PostMapping(ORGANIZATION_REQUESTS)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> request(@RequestBody(required = false) OrganizationRequest body, HttpServletRequest req) {
        requireCreation();
        if (body == null || !validName(body.name()) || (body.legalName() != null && !validName(body.legalName()))) {
            throw ApiException.invalid("nome");
        }
        UUID id = organizations.request(IdentityFilter.principalOrNull(req), IdentityFilter.token(req), uuid(body.organizationId(), "organizacao"),
                body.name(), body.legalName() == null ? body.name() : body.legalName(), RequestContextFilter.ip(req));
        return Map.of("requestId", id.toString());
    }

    @GetMapping(MY_ORGANIZATION_REQUESTS)
    public Map<String, Object> requests(HttpServletRequest req) {
        requireCreation();
        List<Map<String, Object>> items = organizations.requests(IdentityFilter.principalOrNull(req), IdentityFilter.token(req), RequestContextFilter.ip(req))
                .stream().map(q -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("requestId", q.requestId());
                    m.put("organizationId", q.organizationId());
                    m.put("organizationName", q.organizationName());
                    m.put("name", q.name());
                    m.put("legalName", q.legalName());
                    m.put("state", q.state());
                    m.put("requestedAt", q.requestedAt());
                    m.put("decidedAt", q.decidedAt());
                    m.put("responsible", q.responsible());
                    m.put("refusalReason", q.refusalReason());
                    m.put("createdOrganizationId", q.createdOrganizationId());
                    return m;
                }).toList();
        return Map.of("items", items);
    }

    /** 067 (D13): o responsavel desativa a organizacao. Nada e apagado nem liberado. */
    @PostMapping(DEACTIVATIONS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@RequestBody(required = false) OrganizationRef body, HttpServletRequest req) {
        requireCreation();
        organizations.setActive(IdentityFilter.principalOrNull(req), IdentityFilter.token(req), uuid(body == null ? null : body.organizationId(), "organizacao"),
                false, RequestContextFilter.ip(req));
    }

    /** 067 (D13): o responsavel reativa a organizacao que ele desativou; o limite de vazias e reconferido. */
    @PostMapping(REACTIVATIONS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reactivate(@RequestBody(required = false) OrganizationRef body, HttpServletRequest req) {
        requireCreation();
        organizations.setActive(IdentityFilter.principalOrNull(req), IdentityFilter.token(req), uuid(body == null ? null : body.organizationId(), "organizacao"),
                true, RequestContextFilter.ip(req));
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
