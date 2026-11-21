package com.nekkora.platform.admin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.db.DbErrors;
import com.nekkora.core.web.ApiException;
import com.nekkora.platform.admin.AccessActions.Failure;
import com.nekkora.platform.session.ValidSession;

import static com.nekkora.platform.admin.AccessActions.refuse;

/**
 * Clientes e acessos de cliente (PLATFORM-FLUXO-002, 070), lado da administracao: cadastro de clientes, concessoes por automacao
 * e o vinculo de cliente. Conteudos (071): dashboard de execucao e arquivos de retorno; registros tecnicos nao se compartilham. A concessao do convite de Cliente nasce SO no aceite (excecao a D-GRANT-1); tudo o que passa por aqui
 * segue a D-GRANT-1: o pedido nasce PENDENTE e so vale com a aprovacao de OUTRA pessoa, com step-up. Ampliar permissoes e um
 * pedido novo que substitui a concessao ativa na aprovacao; reduzir e revogar sao de uma pessoa. O banco (gatilho da 070)
 * confere de novo autoridade, segunda pessoa, escopo, so-reducao e transicoes. Auditoria em {@code access_events} (15..24).
 *
 * <p>Ordem de locks: organizacao {@code FOR UPDATE} -> conta do ator {@code FOR KEY SHARE} -> linha alvo.
 */
public final class ClientAccessService {

    public record Client(UUID clientId, String name, String status, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record AutomationGrant(UUID grantId, String status, String basis, UUID clientId, UUID clientMembershipId, UUID workspaceId,
                                  UUID automationId, UUID workerId, boolean canViewDashboard, boolean canDownloadResults, UUID replacesGrantId) {
    }

    public record Membership(UUID clientMembershipId, UUID clientId, UUID userId, String status) {
    }

    private static final int NAME_MAX = 200;

    private final AccessActions actions;
    private final AccessEvents events;

    ClientAccessService(AccessActions actions, AccessEvents events) {
        this.actions = actions;
        this.events = events;
    }

    public static ClientAccessService create(AccessActions actions, AccessEvents events) {
        return new ClientAccessService(actions, events);
    }

    // === Cadastro de clientes (org.manage ou external_access.manage; nunca apaga) ===

    public Client createClient(UUID org, ValidSession actor, String token, String rawName) {
        String name = name(rawName);
        return actions.write(org, j -> {
            authorizeRegistry(j, org, actor, token);
            Client c = j.sql("INSERT INTO public.clients (organization_id, name, status) VALUES (?, ?, 1) RETURNING " + CLIENT_COLUMNS)
                    .param(org).param(name).query(ClientAccessService::client).single();
            events.record(j, org, AccessEvents.ClientEvent.of(AccessEvents.Type.CLIENTE_CADASTRADO, actor.membershipId(), actor.userId(), c.clientId()),
                    AccessActions.correlation());
            return c;
        });
    }

    /** Nome e/ou situacao (ativo, suspenso, arquivado). Suspender ou arquivar barra o Portal; nada e apagado. */
    public Client updateClient(UUID org, ValidSession actor, String token, UUID clientId, String rawName, String rawStatus) {
        String name = rawName == null ? null : name(rawName);
        Short status = rawStatus == null ? null : switch (rawStatus) {
            case "active" -> (short) 1;
            case "suspended" -> (short) 2;
            case "archived" -> (short) 3;
            default -> throw ApiException.invalid("status");
        };
        if (name == null && status == null) {
            throw ApiException.invalid("corpo");
        }
        return actions.write(org, j -> {
            authorizeRegistry(j, org, actor, token);
            Client before = j.sql("SELECT " + CLIENT_COLUMNS + " FROM public.clients WHERE organization_id = ? AND client_id = ? FOR UPDATE")
                    .param(org).param(clientId).query(ClientAccessService::client).optional()
                    .orElseThrow(() -> refuse(Failure.TARGET_NOT_FOUND, "cliente"));
            Client after = j.sql("UPDATE public.clients SET name = coalesce(?, name), status = coalesce(?, status) WHERE organization_id = ? AND client_id = ? "
                            + "RETURNING " + CLIENT_COLUMNS).param(name).param(status).param(org).param(clientId)
                    .query(ClientAccessService::client).single();
            if (!after.name().equals(before.name()) || !after.status().equals(before.status())) {
                events.record(j, org, AccessEvents.ClientEvent.of(AccessEvents.Type.CLIENTE_ALTERADO, actor.membershipId(), actor.userId(), clientId),
                        AccessActions.correlation());
            }
            return after;
        });
    }

    // === Concessoes por automacao (external_access.manage; D-GRANT-1) ===

    /**
     * Pedido de acompanhamento de uma automacao (e maquina) por um vinculo de cliente ativo: nasce PENDENTE, sem acesso. Se ja ha uma
     * ATIVA do mesmo escopo, o pedido so faz sentido para AMPLIAR permissoes (409 {@code concessao_existente} se nao amplia) e a
     * substitui quando aprovado. Ja ha pedido pendente do mesmo escopo: 409 {@code pedido_pendente}.
     */
    public AutomationGrant requestGrant(UUID org, ValidSession actor, String token, UUID clientMembership, UUID automation, UUID worker,
                                        boolean canViewDashboard, boolean canDownloadResults) {
        if (!canViewDashboard && !canDownloadResults) {
            throw ApiException.invalid("conteudo");
        }
        return actions.write(org, j -> {
            authorizeGrants(j, org, actor, token);
            Membership m = membership(j, org, clientMembership, true);
            if (!"active".equals(m.status())) {
                throw refuse(Failure.CONFLICT, "vinculo_indisponivel");
            }
            UUID workspace = InvitationService.requireClientScope(j, org,
                    new InvitationService.ClientScope(m.clientId(), automation, worker, canViewDashboard, canDownloadResults));
            Optional<AutomationGrant> active = j.sql("SELECT " + GRANT_COLUMNS + " FROM public.client_automation_grants WHERE organization_id = ? "
                            + "AND client_membership_id = ? AND automation_id = ? AND worker_id IS NOT DISTINCT FROM ? AND status IN (1, 2) ORDER BY status FOR UPDATE")
                    .param(org).param(clientMembership).param(automation).param(worker).query(ClientAccessService::grant).list().stream()
                    .filter(g -> {
                        if ("PENDENTE".equals(g.status())) {
                            throw refuse(Failure.CONFLICT, "pedido_pendente");
                        }
                        return true;
                    }).findFirst();
            UUID replaces = null;
            if (active.isPresent()) {
                AutomationGrant g = active.get();
                boolean widens = (canViewDashboard && !g.canViewDashboard()) || (canDownloadResults && !g.canDownloadResults());
                if (!widens) {
                    throw refuse(Failure.CONFLICT, "concessao_existente");
                }
                replaces = g.grantId();
            }
            AutomationGrant out;
            try {
                out = j.sql("""
                        INSERT INTO public.client_automation_grants (organization_id, client_id, client_membership_id, workspace_id, automation_id, worker_id,
                            can_read_logs, can_view_dashboard, can_download_results, status, basis, requested_by_membership_id, replaces_grant_id)
                        VALUES (?, ?, ?, ?, ?, ?, false, ?, ?, 1, 1, ?, ?)
                        RETURNING """ + " " + GRANT_COLUMNS).param(org).param(m.clientId()).param(clientMembership).param(workspace).param(automation)
                        .param(worker).param(canViewDashboard).param(canDownloadResults).param(actor.membershipId()).param(replaces)
                        .query(ClientAccessService::grant).single();
            } catch (RuntimeException e) {
                if ("23505".equals(DbErrors.sqlState(e))) {
                    throw refuse(Failure.CONFLICT, "pedido_pendente");
                }
                throw e;
            }
            audit(j, org, actor, AccessEvents.Type.CLIENTE_CONCESSAO_SOLICITADA, out, m.userId());
            return out;
        });
    }

    /** Aprovacao do pedido: OUTRA pessoa (o solicitante: 403), com step-up; substitui a ativa do mesmo escopo, se o pedido a amplia. */
    public AutomationGrant approveGrant(UUID org, ValidSession actor, String token, UUID grant) {
        return actions.write(org, j -> {
            authorizeGrants(j, org, actor, token);
            actions.requireStepUp(j, actor);
            AutomationGrant g = lockGrant(j, org, grant);
            if (!"PENDENTE".equals(g.status())) {
                throw refuse(Failure.CONFLICT, "concessao_nao_pendente");
            }
            UUID requester = j.sql("SELECT requested_by_membership_id FROM public.client_automation_grants WHERE organization_id = ? AND grant_id = ?")
                    .param(org).param(grant).query(UUID.class).single();
            if (requester.equals(actor.membershipId())) {
                throw refuse(Failure.SECOND_PERSON, "aprovador_igual_solicitante");
            }
            Membership m = membership(j, org, g.clientMembershipId(), true);
            if (!"active".equals(m.status())) {
                throw refuse(Failure.CONFLICT, "vinculo_indisponivel");
            }
            InvitationService.requireClientScope(j, org, new InvitationService.ClientScope(g.clientId(), g.automationId(), g.workerId(), false, false));
            if (g.replacesGrantId() != null) {
                AutomationGrant old = lockGrant(j, org, g.replacesGrantId());
                if ("ATIVA".equals(old.status())) {
                    j.sql("UPDATE public.client_automation_grants SET status = 4, revoked_at = greatest(clock_timestamp(), created_at), revoke_reason = 2, "
                            + "revoked_by_membership_id = ? WHERE organization_id = ? AND grant_id = ?").param(actor.membershipId()).param(org)
                            .param(old.grantId()).update();
                    audit(j, org, actor, AccessEvents.Type.CLIENTE_CONCESSAO_REVOGADA, old, m.userId());
                }
            }
            AutomationGrant out = j.sql("UPDATE public.client_automation_grants SET status = 2, approved_by_membership_id = ?, approved_at = clock_timestamp() "
                            + "WHERE organization_id = ? AND grant_id = ? RETURNING " + GRANT_COLUMNS).param(actor.membershipId()).param(org).param(grant)
                    .query(ClientAccessService::grant).single();
            audit(j, org, actor, AccessEvents.Type.CLIENTE_CONCESSAO_APROVADA, out, m.userId());
            return out;
        });
    }

    /** Reducao das permissoes de uma concessao ATIVA (so desligar; ligar e outro pedido): uma pessoa, sem step-up. */
    public AutomationGrant reduceGrant(UUID org, ValidSession actor, String token, UUID grant, boolean canViewDashboard, boolean canDownloadResults) {
        return actions.write(org, j -> {
            authorizeGrants(j, org, actor, token);
            AutomationGrant g = lockGrant(j, org, grant);
            if (!"ATIVA".equals(g.status())) {
                throw refuse(Failure.CONFLICT, "concessao_nao_ativa");
            }
            if ((canViewDashboard && !g.canViewDashboard()) || (canDownloadResults && !g.canDownloadResults())) {
                throw refuse(Failure.CONFLICT, "ampliacao_exige_pedido");
            }
            if (!canViewDashboard && !canDownloadResults) {
                throw ApiException.invalid("conteudo");   // sem conteudo nenhum e revogacao, nao reducao
            }
            if (canViewDashboard == g.canViewDashboard() && canDownloadResults == g.canDownloadResults()) {
                return g;
            }
            AutomationGrant out = j.sql("UPDATE public.client_automation_grants SET can_view_dashboard = ?, can_download_results = ? WHERE organization_id = ? "
                            + "AND grant_id = ? RETURNING " + GRANT_COLUMNS).param(canViewDashboard).param(canDownloadResults).param(org).param(grant)
                    .query(ClientAccessService::grant).single();
            audit(j, org, actor, AccessEvents.Type.CLIENTE_CONCESSAO_REDUZIDA, out, userOf(j, org, g.clientMembershipId()));
            return out;
        });
    }

    /** Revoga a concessao ATIVA ou cancela o pedido PENDENTE; so aquele acesso (os demais vinculos continuam). Idempotente. */
    public void revokeGrant(UUID org, ValidSession actor, String token, UUID grant) {
        actions.write(org, j -> {
            authorizeGrants(j, org, actor, token);
            AutomationGrant g = lockGrant(j, org, grant);
            if ("REVOGADA".equals(g.status())) {
                return null;
            }
            AutomationGrant out = j.sql("UPDATE public.client_automation_grants SET status = 4, revoked_at = greatest(clock_timestamp(), created_at), "
                            + "revoke_reason = ?, revoked_by_membership_id = ? WHERE organization_id = ? AND grant_id = ? RETURNING " + GRANT_COLUMNS)
                    .param((short) ("PENDENTE".equals(g.status()) ? 4 : 1)).param(actor.membershipId()).param(org).param(grant)
                    .query(ClientAccessService::grant).single();
            audit(j, org, actor, AccessEvents.Type.CLIENTE_CONCESSAO_REVOGADA, out, userOf(j, org, g.clientMembershipId()));
            return null;
        });
    }

    // === Vinculo de cliente (org.manage) ===

    /** Suspende: o Portal recusa a proxima consulta (mesmo com a sessao aberta). As concessoes ficam como estao. */
    public Membership suspend(UUID org, ValidSession actor, String token, UUID clientMembership) {
        return transition(org, actor, token, clientMembership, "active", (short) 3, AccessEvents.Type.VINCULO_CLIENTE_SUSPENSO, false);
    }

    /** Reativa o vinculo suspenso (amplia de novo: step-up). As concessoes revogadas continuam revogadas. */
    public Membership reactivate(UUID org, ValidSession actor, String token, UUID clientMembership) {
        return transition(org, actor, token, clientMembership, "suspended", (short) 2, AccessEvents.Type.VINCULO_CLIENTE_REATIVADO, true);
    }

    /**
     * Retira o vinculo de cliente desta organizacao e revoga, no mesmo ato, as concessoes ativas e os pedidos dele (motivo
     * VINCULO_RETIRADO): uma readmissao futura por convite nao reaproveita acesso antigo. A identidade global nao e tocada.
     */
    public Membership retire(UUID org, ValidSession actor, String token, UUID clientMembership) {
        return actions.write(org, j -> {
            authorizeMembership(j, org, actor, token);
            Membership m = membership(j, org, clientMembership, true);
            if ("revoked".equals(m.status())) {
                return m;
            }
            List<AutomationGrant> open = j.sql("SELECT " + GRANT_COLUMNS + " FROM public.client_automation_grants WHERE organization_id = ? "
                    + "AND client_membership_id = ? AND status IN (1, 2) ORDER BY grant_id FOR UPDATE").param(org).param(clientMembership)
                    .query(ClientAccessService::grant).list();
            for (AutomationGrant g : open) {
                AutomationGrant out = j.sql("UPDATE public.client_automation_grants SET status = 4, revoked_at = greatest(clock_timestamp(), created_at), "
                                + "revoke_reason = 3, revoked_by_membership_id = ? WHERE organization_id = ? AND grant_id = ? RETURNING " + GRANT_COLUMNS)
                        .param(actor.membershipId()).param(org).param(g.grantId()).query(ClientAccessService::grant).single();
                audit(j, org, actor, AccessEvents.Type.CLIENTE_CONCESSAO_REVOGADA, out, m.userId());
            }
            Membership out = setMembership(j, org, clientMembership, (short) 4);
            events.record(j, org, AccessEvents.ClientEvent.of(AccessEvents.Type.VINCULO_CLIENTE_RETIRADO, actor.membershipId(), actor.userId(), m.clientId())
                    .target(m.userId(), clientMembership), AccessActions.correlation());
            return out;
        });
    }

    private Membership transition(UUID org, ValidSession actor, String token, UUID clientMembership, String from, short to, AccessEvents.Type type,
                                  boolean stepUp) {
        return actions.write(org, j -> {
            authorizeMembership(j, org, actor, token);
            if (stepUp) {
                actions.requireStepUp(j, actor);
            }
            Membership m = membership(j, org, clientMembership, true);
            if (!from.equals(m.status())) {
                throw refuse(Failure.CONFLICT, "vinculo_estado");
            }
            Membership out = setMembership(j, org, clientMembership, to);
            events.record(j, org, AccessEvents.ClientEvent.of(type, actor.membershipId(), actor.userId(), m.clientId()).target(m.userId(), clientMembership),
                    AccessActions.correlation());
            return out;
        });
    }

    private void authorizeRegistry(JdbcClient j, UUID org, ValidSession actor, String token) {
        actions.lockOrganizationAndActor(j, org, actor.userId());
        actions.requireActor(j, org, actor, token);
        if (!actions.accounts().hasPermission(j, org, actor.userId(), "org.manage")) {
            actions.requirePermission(j, org, actor, "external_access.manage");
        }
    }

    private void authorizeGrants(JdbcClient j, UUID org, ValidSession actor, String token) {
        actions.lockOrganizationAndActor(j, org, actor.userId());
        actions.requireActor(j, org, actor, token);
        actions.requirePermission(j, org, actor, "external_access.manage");
    }

    private void authorizeMembership(JdbcClient j, UUID org, ValidSession actor, String token) {
        actions.lockOrganizationAndActor(j, org, actor.userId());
        actions.requireActor(j, org, actor, token);
        actions.requirePermission(j, org, actor, "org.manage");
    }

    private void audit(JdbcClient j, UUID org, ValidSession actor, AccessEvents.Type type, AutomationGrant g, UUID targetUser) {
        events.record(j, org, AccessEvents.ClientEvent.of(type, actor.membershipId(), actor.userId(), g.clientId())
                .target(targetUser, g.clientMembershipId())
                .scope(g.workspaceId(), g.automationId(), g.workerId(), g.canViewDashboard(), g.canDownloadResults()).grant(g.grantId()), AccessActions.correlation());
    }

    private static String name(String raw) {
        if (raw == null) {
            throw ApiException.invalid("nome");
        }
        String n = raw.strip();
        if (n.isEmpty() || n.length() > NAME_MAX) {
            throw ApiException.invalid("nome");
        }
        return n;
    }

    private static Membership membership(JdbcClient j, UUID org, UUID clientMembership, boolean lock) {
        return j.sql("SELECT m.client_membership_id, m.client_id, m.user_id, m.status FROM public.client_memberships m WHERE m.organization_id = ? "
                        + "AND m.client_membership_id = ?" + (lock ? " FOR UPDATE" : "")).param(org).param(clientMembership)
                .query((rs, n) -> new Membership(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        membershipStatus(rs.getShort(4)))).optional().orElseThrow(() -> refuse(Failure.TARGET_NOT_FOUND, "vinculo_cliente"));
    }

    private static Membership setMembership(JdbcClient j, UUID org, UUID clientMembership, short status) {
        return j.sql("UPDATE public.client_memberships SET status = ? WHERE organization_id = ? AND client_membership_id = ? "
                        + "RETURNING client_membership_id, client_id, user_id, status").param(status).param(org).param(clientMembership)
                .query((rs, n) -> new Membership(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        membershipStatus(rs.getShort(4)))).single();
    }

    private static UUID userOf(JdbcClient j, UUID org, UUID clientMembership) {
        return j.sql("SELECT user_id FROM public.client_memberships WHERE organization_id = ? AND client_membership_id = ?").param(org)
                .param(clientMembership).query(UUID.class).single();
    }

    private static AutomationGrant lockGrant(JdbcClient j, UUID org, UUID grant) {
        return j.sql("SELECT " + GRANT_COLUMNS + " FROM public.client_automation_grants WHERE organization_id = ? AND grant_id = ? FOR UPDATE")
                .param(org).param(grant).query(ClientAccessService::grant).optional().orElseThrow(() -> refuse(Failure.TARGET_NOT_FOUND, "concessao"));
    }

    static final String CLIENT_COLUMNS = "client_id, name, status, created_at, updated_at";
    static final String GRANT_COLUMNS = "grant_id, status, basis, client_id, client_membership_id, workspace_id, automation_id, worker_id, can_view_dashboard,"
            + " can_download_results, replaces_grant_id";

    private static Client client(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Client(rs.getObject(1, UUID.class), rs.getString(2), clientStatus(rs.getShort(3)), rs.getObject(4, OffsetDateTime.class),
                rs.getObject(5, OffsetDateTime.class));
    }

    private static AutomationGrant grant(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new AutomationGrant(rs.getObject(1, UUID.class), grantStatus(rs.getShort(2)), rs.getShort(3) == 1 ? "SEGUNDA_PESSOA" : "CONVITE_DE_CLIENTE",
                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class),
                rs.getObject(8, UUID.class), rs.getBoolean(9), rs.getBoolean(10), rs.getObject(11, UUID.class));
    }

    public static String clientStatus(short s) {
        return switch (s) {
            case 1 -> "active";
            case 2 -> "suspended";
            case 3 -> "archived";
            default -> throw new IllegalStateException("situacao de cliente fora do catalogo: " + s);
        };
    }

    public static String membershipStatus(short s) {
        return switch (s) {
            case 1 -> "pending";
            case 2 -> "active";
            case 3 -> "suspended";
            case 4 -> "revoked";
            default -> throw new IllegalStateException("situacao de vinculo fora do catalogo: " + s);
        };
    }

    public static String grantStatus(short s) {
        return switch (s) {
            case 1 -> "PENDENTE";
            case 2 -> "ATIVA";
            case 4 -> "REVOGADA";
            default -> throw new IllegalStateException("estado de concessao por automacao fora do catalogo: " + s);
        };
    }
}
