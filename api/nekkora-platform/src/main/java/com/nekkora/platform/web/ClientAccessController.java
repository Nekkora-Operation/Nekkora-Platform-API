package com.nekkora.platform.web;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.platform.access.WorkspaceAccess;
import com.nekkora.platform.admin.ClientAccessService;
import com.nekkora.platform.admin.InvitationService;
import com.nekkora.platform.paging.CursorCodec;
import com.nekkora.platform.paging.Paging;
import com.nekkora.platform.session.ValidSession;

/**
 * PLATFORM-FLUXO-002 (070): "Clientes e acessos". Convite de Cliente para acompanhar UMA automacao, cadastro de clientes,
 * concessoes por automacao (D-GRANT-1), vinculo de cliente e as leituras de "Acessos e historico". Sessao da organizacao e CSRF
 * dela; conta externa: 403. A autorizacao dos atos e dos servicos, dentro da transacao; a das leituras e conferida aqui, na
 * transacao da leitura.
 *
 * <p>Permissoes: convite de Cliente, org.manage E external_access.manage (com step-up); cadastro de clientes, org.manage OU
 * external_access.manage; concessoes por automacao, external_access.manage (aprovacao com step-up, por outra pessoa); vinculo de
 * cliente, org.manage. Historico: org.manage ve tudo; so external_access.manage ve os atos de concessao, de convite de cliente, de
 * vinculo de cliente e do cadastro (a pagina de pessoas nao abre a auditoria inteira).
 */
@RestController
public class ClientAccessController {

    static final String ROUTE_GRANTS = "listAccessGrants";
    static final String ROUTE_HISTORY = "listAccessHistory";
    static final String SORT_DESC = "createdAt.desc+id.desc";

    public record ClientInvitationRequest(String email, String clientId, String automationId, String workerId, Boolean canReadLogs,
                                          Boolean canDownloadResults) {
    }

    public record ClientRequest(String name, String status) {
    }

    public record AutomationGrantRequest(String clientMembershipId, String automationId, String workerId, Boolean canReadLogs,
                                         Boolean canDownloadResults) {
    }

    public record ReduceRequest(Boolean canReadLogs, Boolean canDownloadResults) {
    }

    private final InvitationService invitations;
    private final ClientAccessService clients;
    private final OrgTransactions tx;
    private final WorkspaceAccess access;
    private final CursorCodec cursors;
    private final boolean invitationsEnabled;

    public ClientAccessController(InvitationService invitations, ClientAccessService clients, OrgTransactions tx, WorkspaceAccess access,
                                  CursorCodec cursors, @Value("${nekkora.entrada-geral.enabled:false}") boolean entryEnabled,
                                  @Value("${nekkora.convites.enabled:false}") boolean invitationsEnabled) {
        this.invitations = invitations;
        this.clients = clients;
        this.tx = tx;
        this.access = access;
        this.cursors = cursors;
        this.invitationsEnabled = entryEnabled && invitationsEnabled;
    }

    // === Convite de Cliente ===

    @PostMapping("/api/v1/o/{org}/client-invitations")
    public ResponseEntity<InvitationService.ClientCreated> inviteClient(@PathVariable UUID org, @RequestBody(required = false) ClientInvitationRequest body,
                                                                       HttpServletRequest req) {
        if (!invitationsEnabled) {
            throw new ApiException(ApiError.NOT_FOUND, "convites_desligados");
        }
        if (body == null || body.canReadLogs() == null || body.canDownloadResults() == null) {
            throw ApiException.invalid("corpo");
        }
        InvitationService.ClientScope scope = new InvitationService.ClientScope(uuid(body.clientId(), "cliente"), uuid(body.automationId(), "automacao"),
                uuidOrNull(body.workerId(), "maquina"), body.canReadLogs(), body.canDownloadResults());
        return ResponseEntity.status(HttpStatus.CREATED).body(invitations.issueClient(org, SessionFilter.principal(req), SessionFilter.token(req),
                body.email(), scope));
    }

    // === Cadastro de clientes ===

    @PostMapping("/api/v1/o/{org}/clients")
    public ResponseEntity<ClientAccessService.Client> createClient(@PathVariable UUID org, @RequestBody(required = false) ClientRequest body,
                                                                   HttpServletRequest req) {
        if (body == null || body.status() != null) {
            throw ApiException.invalid("corpo");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(clients.createClient(org, SessionFilter.principal(req), SessionFilter.token(req), body.name()));
    }

    @PatchMapping("/api/v1/o/{org}/clients/{client}")
    public ClientAccessService.Client updateClient(@PathVariable UUID org, @PathVariable UUID client, @RequestBody(required = false) ClientRequest body,
                                                   HttpServletRequest req) {
        if (body == null) {
            throw ApiException.invalid("corpo");
        }
        return clients.updateClient(org, SessionFilter.principal(req), SessionFilter.token(req), client, body.name(), body.status());
    }

    @GetMapping("/api/v1/o/{org}/clients/{client}")
    public Map<String, Object> clientDetail(@PathVariable UUID org, @PathVariable UUID client, HttpServletRequest req) {
        ValidSession s = internal(req);
        Map<String, Object> out = tx.inOrganization(org, Mode.READ_ONLY, j -> {
            access.requireAnyOrganizationPermission(j, org, s.membershipId(), "org.manage", "external_access.manage");
            return ClientAccessReads.clientDetail(j, org, client);
        });
        if (out == null) {
            throw new ApiException(ApiError.NOT_FOUND, "cliente");
        }
        return out;
    }

    // === Concessoes por automacao ===

    @GetMapping("/api/v1/o/{org}/client-share-options")
    public Map<String, Object> shareOptions(@PathVariable UUID org, HttpServletRequest req) {
        ValidSession s = internal(req);
        List<Map<String, Object>> items = tx.inOrganization(org, Mode.READ_ONLY, j -> {
            access.requireAnyOrganizationPermission(j, org, s.membershipId(), "external_access.manage");
            return ClientAccessReads.shareOptions(j, org);
        });
        return Map.of("automations", items);
    }

    @PostMapping("/api/v1/o/{org}/client-automation-grants")
    public ResponseEntity<ClientAccessService.AutomationGrant> requestGrant(@PathVariable UUID org, @RequestBody(required = false) AutomationGrantRequest body,
                                                                           HttpServletRequest req) {
        if (body == null || body.canReadLogs() == null || body.canDownloadResults() == null) {
            throw ApiException.invalid("corpo");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(clients.requestGrant(org, SessionFilter.principal(req), SessionFilter.token(req),
                uuid(body.clientMembershipId(), "vinculo"), uuid(body.automationId(), "automacao"), uuidOrNull(body.workerId(), "maquina"),
                body.canReadLogs(), body.canDownloadResults()));
    }

    @PostMapping("/api/v1/o/{org}/client-automation-grants/{grant}/approve")
    public ClientAccessService.AutomationGrant approveGrant(@PathVariable UUID org, @PathVariable UUID grant, HttpServletRequest req) {
        return clients.approveGrant(org, SessionFilter.principal(req), SessionFilter.token(req), grant);
    }

    @PostMapping("/api/v1/o/{org}/client-automation-grants/{grant}/reduce")
    public ClientAccessService.AutomationGrant reduceGrant(@PathVariable UUID org, @PathVariable UUID grant, @RequestBody(required = false) ReduceRequest body,
                                                           HttpServletRequest req) {
        if (body == null || body.canReadLogs() == null || body.canDownloadResults() == null) {
            throw ApiException.invalid("corpo");
        }
        return clients.reduceGrant(org, SessionFilter.principal(req), SessionFilter.token(req), grant, body.canReadLogs(), body.canDownloadResults());
    }

    @PostMapping("/api/v1/o/{org}/client-automation-grants/{grant}/revoke")
    public ResponseEntity<Void> revokeGrant(@PathVariable UUID org, @PathVariable UUID grant, HttpServletRequest req) {
        clients.revokeGrant(org, SessionFilter.principal(req), SessionFilter.token(req), grant);
        return ResponseEntity.noContent().build();
    }

    // === Vinculo de cliente ===

    @PostMapping("/api/v1/o/{org}/client-memberships/{membership}/suspend")
    public ClientAccessService.Membership suspend(@PathVariable UUID org, @PathVariable UUID membership, HttpServletRequest req) {
        return clients.suspend(org, SessionFilter.principal(req), SessionFilter.token(req), membership);
    }

    @PostMapping("/api/v1/o/{org}/client-memberships/{membership}/reactivate")
    public ClientAccessService.Membership reactivate(@PathVariable UUID org, @PathVariable UUID membership, HttpServletRequest req) {
        return clients.reactivate(org, SessionFilter.principal(req), SessionFilter.token(req), membership);
    }

    @PostMapping("/api/v1/o/{org}/client-memberships/{membership}/retire")
    public ClientAccessService.Membership retire(@PathVariable UUID org, @PathVariable UUID membership, HttpServletRequest req) {
        return clients.retire(org, SessionFilter.principal(req), SessionFilter.token(req), membership);
    }

    // === Acessos e historico (leituras) ===

    @GetMapping("/api/v1/o/{org}/access-grants")
    public Map<String, Object> accessGrants(@PathVariable UUID org, @RequestParam(required = false) UUID personId,
                                            @RequestParam(required = false) UUID clientId, @RequestParam(required = false) UUID workspaceId,
                                            @RequestParam(required = false) UUID automationId, @RequestParam(required = false) UUID workerId,
                                            @RequestParam(required = false) List<String> situation, @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to, @RequestParam(required = false) String cursor,
                                            @RequestParam(required = false) Integer limit, HttpServletRequest req) {
        ValidSession s = SessionFilter.principal(req);
        int pageSize = Paging.limit(limit);
        List<String> situations = situation == null ? null : List.copyOf(distinct(situation, ClientAccessReads.GRANT_SITUATIONS, "situation"));
        OffsetDateTime f = instant(from, "from");
        OffsetDateTime t = instant(to, "to");
        period(f, t);
        CursorCodec.Scope scope = new CursorCodec.Scope(ROUTE_GRANTS, org, s.membershipId(), null, CursorCodec.filters("person", text(personId),
                "client", text(clientId), "workspace", text(workspaceId), "automation", text(automationId), "worker", text(workerId),
                "situation", situations == null ? null : String.join(",", situations), "from", from, "to", to), SORT_DESC);
        CursorCodec.Position after = cursor == null ? null : cursors.decode(cursor, scope).expectSize(2);
        internal(req);
        List<Map<String, Object>> rows = tx.inOrganization(org, Mode.READ_ONLY, j -> {
            access.requireAnyOrganizationPermission(j, org, s.membershipId(), "external_access.manage");
            return ClientAccessReads.grants(j, org, new ClientAccessReads.GrantFilter(personId, clientId, workspaceId, automationId, workerId, situations,
                    f, t, after == null ? null : after.instant(0), after == null ? null : after.uuid(1), pageSize));
        });
        return page(rows, pageSize, last -> cursors.encode(scope, List.of(last.get("createdAt"), last.get("grantId"))));
    }

    @GetMapping("/api/v1/o/{org}/access-history")
    public Map<String, Object> accessHistory(@PathVariable UUID org, @RequestParam(required = false) UUID personId,
                                             @RequestParam(required = false) UUID clientId, @RequestParam(required = false) UUID workspaceId,
                                             @RequestParam(required = false) UUID automationId, @RequestParam(required = false) UUID workerId,
                                             @RequestParam(required = false) UUID grantId, @RequestParam(required = false) List<String> category,
                                             @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                             @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
                                             HttpServletRequest req) {
        ValidSession s = SessionFilter.principal(req);
        int pageSize = Paging.limit(limit);
        Set<String> categories = category == null ? null : distinct(category, List.copyOf(ClientAccessReads.HISTORY_CATEGORIES.keySet()), "category");
        OffsetDateTime f = instant(from, "from");
        OffsetDateTime t = instant(to, "to");
        period(f, t);
        CursorCodec.Scope scope = new CursorCodec.Scope(ROUTE_HISTORY, org, s.membershipId(), null, CursorCodec.filters("person", text(personId),
                "client", text(clientId), "workspace", text(workspaceId), "automation", text(automationId), "worker", text(workerId),
                "grant", text(grantId), "category", categories == null ? null : String.join(",", new java.util.TreeSet<>(categories)), "from", from, "to", to),
                SORT_DESC);
        CursorCodec.Position after = cursor == null ? null : cursors.decode(cursor, scope).expectSize(2);
        internal(req);
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = tx.inOrganization(org, Mode.READ_ONLY, j -> {
            boolean all = access.hasOrganizationPermission(j, org, s.membershipId(), "org.manage");
            if (!all) {
                access.requireAnyOrganizationPermission(j, org, s.membershipId(), "external_access.manage");
            }
            out.put("scope", all ? "all" : "client_access");
            List<Integer> types = new ArrayList<>();
            for (Map.Entry<String, List<Integer>> e : ClientAccessReads.HISTORY_CATEGORIES.entrySet()) {
                // so external_access.manage: sem os atos de papel; dos demais, so os que tem cliente (clientOnly, na consulta)
                boolean allowed = all || !"roles".equals(e.getKey());
                if (allowed && (categories == null || categories.contains(e.getKey()))) {
                    types.addAll(e.getValue());
                }
            }
            if (types.isEmpty()) {
                return List.of();
            }
            return ClientAccessReads.history(j, org, new ClientAccessReads.HistoryFilter(personId, clientId, workspaceId, automationId, workerId,
                    grantId, types, !all, f, t, after == null ? null : after.instant(0), after == null ? null : after.uuid(1), pageSize));
        });
        Map<String, Object> page = page(rows, pageSize, last -> cursors.encode(scope, List.of(last.get("createdAt"), last.get("eventId"))));
        page.putAll(out);
        return page;
    }

    // === comum ===

    private static ValidSession internal(HttpServletRequest req) {
        ValidSession s = SessionFilter.principal(req);
        if (!s.isInternal()) {
            throw ApiException.forbidden("conta_externa");
        }
        return s;
    }

    private static Set<String> distinct(List<String> values, List<String> allowed, String rule) {
        if (values.isEmpty() || values.size() > allowed.size()) {
            throw ApiException.invalid(rule);
        }
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (!allowed.contains(v) || !out.add(v)) {
                throw ApiException.invalid(rule);
            }
        }
        return out;
    }

    private static OffsetDateTime instant(String raw, String rule) {
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException e) {
            throw ApiException.invalid(rule);
        }
    }

    private static void period(OffsetDateTime from, OffsetDateTime to) {
        if (from != null && to != null && !to.isAfter(from)) {
            throw ApiException.invalid("periodo");
        }
    }

    private static String text(UUID u) {
        return u == null ? null : u.toString();
    }

    private static UUID uuid(String raw, String rule) {
        UUID u = uuidOrNull(raw, rule);
        if (u == null) {
            throw ApiException.invalid(rule);
        }
        return u;
    }

    private static UUID uuidOrNull(String raw, String rule) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid(rule);
        }
    }

    private static Map<String, Object> page(List<Map<String, Object>> rows, int pageSize, java.util.function.Function<Map<String, Object>, String> cursorOf) {
        String next = rows.size() > pageSize ? cursorOf.apply(rows.get(pageSize - 1)) : null;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", new ArrayList<>(rows.subList(0, Math.min(pageSize, rows.size()))));
        r.put("nextCursor", next);
        return r;
    }
}
