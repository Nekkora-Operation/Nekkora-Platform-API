package com.nekkora.platform.identity;

import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Organizacoes da identidade e troca por sessao de organizacao (desenho §6.4 e §6.5), SEM ESTADO. As leituras entre
 * organizacoes sao SO das funcoes da 017 ({@code identity_organizations}, {@code identity_org_session_target}), sob a
 * identidade da sessao; a Platform nao le {@code identity_users}. As duas travam a identidade e a sessao de identidade,
 * entao rodam em transacao de escrita. O resto roda com o contexto {@code nekkora.identity_id} ja definido.
 */
public final class IdentityOrgRepository {

    /** identity_events.event_type (017). */
    public static final short EV_ORG_SESSION_ISSUED = 7;
    public static final short EV_ORG_SESSION_DENIED = 8;

    /**
     * {@code activationState}: ciclo de ativacao da 022 ({@code AGUARDANDO_ATIVACAO} | {@code ATIVADA}), independente da
     * situacao administrativa. So indicacao para a interface; a capacidade continua decidida no banco pelos limites.
     */
    /** T10 (034): {@code handle} = identificador legivel e estavel da organizacao (nao e credencial; nulo so antes da 034). */
    public record Organization(UUID organizationId, String name, List<String> roles, String activationState, String handle) {
    }

    public static final String AGUARDANDO_ATIVACAO = "AGUARDANDO_ATIVACAO";
    public static final String ATIVADA = "ATIVADA";

    public record Target(UUID organizationId, UUID userId) {
    }

    /** Marcador de MFA e prazo da sessao de identidade, para a sessao de organizacao derivada. */
    public record SessionClock(OffsetDateTime mfaVerifiedAt, OffsetDateTime expiresAt) {
    }

    /**
     * {@code identity_organizations}: vinculo ACTIVE, conta elegivel, organizacao ativa; zero linhas para sessao restrita. O
     * estado de ativacao vem de {@code org_activation_states} (022), so para as organizacoes que a funcao da 017 devolveu.
     */
    public List<Organization> organizations(JdbcClient j, String sessionToken) {
        record Linha(UUID id, String name, List<String> roles) {
        }
        List<Linha> linhas = j.sql("SELECT organization_id, name, role_codes FROM public.identity_organizations(?)")
                .param(sessionToken)
                .query((rs, n) -> new Linha(rs.getObject(1, UUID.class), rs.getString(2), roles(rs.getArray(3))))
                .list();
        Map<UUID, String> estados = activationStates(j, linhas.stream().map(Linha::id).toList());
        return linhas.stream().map(l -> new Organization(l.id(), l.name(), l.roles(), estados.getOrDefault(l.id(), AGUARDANDO_ATIVACAO), null)).toList();
    }

    /**
     * {@code org_activation_states} (022): estado de ativacao de cada organizacao pedida. Organizacao sem estado (nao deveria
     * existir: a 022 classifica as existentes e o gatilho cria o das novas) fica fora do mapa e e mostrada como aguardando,
     * o lado que nao promete capacidade.
     */
    public static Map<UUID, String> activationStates(JdbcClient j, List<UUID> organizations) {
        if (organizations.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> m = new HashMap<>();
        j.sql("SELECT organization_id, activation_state FROM public.org_activation_states(?::uuid[])")
                .param(organizations.stream().map(UUID::toString).toArray(String[]::new))
                .query((rs, n) -> m.put(rs.getObject(1, UUID.class), rs.getShort(2) == 2 ? ATIVADA : AGUARDANDO_ATIVACAO))
                .list();
        return m;
    }

    /**
     * T10 (034): identificador legivel da organizacao, lido NO CONTEXTO DELA (RLS de organization_handles: a Platform so ve a
     * linha da organizacao do contexto). Chamado so para as organizacoes que identity_organizations devolveu.
     */
    public static Optional<String> handle(JdbcClient j, UUID organization) {
        return j.sql("SELECT handle FROM public.organization_handles WHERE organization_id = ?").param(organization).query(String.class).optional();
    }

    /** {@code identity_org_session_target}: a candidata, ou vazio (sem vinculo, inelegivel, inexistente, sessao restrita). */
    public Optional<Target> target(JdbcClient j, String sessionToken, UUID organizationId) {
        return j.sql("SELECT organization_id, user_id FROM public.identity_org_session_target(?, ?)")
                .param(sessionToken).param(organizationId)
                .query((rs, n) -> new Target(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                .optional();
    }

    public Optional<SessionClock> sessionClock(JdbcClient j, UUID identitySessionId) {
        return j.sql("SELECT mfa_verified_at, expires_at FROM public.identity_sessions WHERE identity_session_id = ? AND revoked_at IS NULL")
                .param(identitySessionId)
                .query((rs, n) -> new SessionClock(rs.getObject(1, OffsetDateTime.class), rs.getObject(2, OffsetDateTime.class)))
                .optional();
    }

    /**
     * {@code identity_create_organization} (DC-17.5; contrato P2): a UNICA escrita da criacao. Reconfere, sob o lock da
     * identidade, sessao nao restrita, step-up de ate 5 min ({@code identity_session_proof}), identidade ACTIVE e
     * verificada e o teto de 10; cria organizacao, conta, vinculo, ORG_ADMIN, {@code identity_users} e o evento 6 no
     * mesmo commit. Vazio = recusa (a funcao nao diz o motivo).
     */
    public Optional<UUID> createOrganization(JdbcClient j, String sessionToken, String name, String legalName) {
        return j.sql("SELECT public.identity_create_organization(?::text, ?::text, ?::text)")
                .param(sessionToken).param(name).param(legalName).query(UUID.class).optional();
    }

    /** 067: o perfil de quem cria e os limites, pela sessao. {@code profile}: FIRST, ADMIN, MEMBER ou CLIENT. */
    public record CreationState(String profile, long organizationsCreated, List<UUID> emptyOrganizations, int emptyLimit, long pendingRequests) {
    }

    /** {@code identity_org_creation_state} (067): vazio para sessao que nao resolve ou restrita. A criacao e o pedido reconferem. */
    public Optional<CreationState> creationState(JdbcClient j, String sessionToken) {
        return j.sql("SELECT profile, organizations_created, empty_organization_ids, empty_limit, pending_requests FROM public.identity_org_creation_state(?)")
                .param(sessionToken)
                .query((rs, n) -> new CreationState(rs.getString(1), rs.getLong(2), uuids(rs.getArray(3)), rs.getInt(4), rs.getLong(5))).optional();
    }

    private static List<UUID> uuids(Array a) throws SQLException {
        if (a == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) a.getArray()).map(o -> o instanceof UUID u ? u : UUID.fromString(String.valueOf(o))).toList();
    }

    /** {@code identity_request_organization} (067): o pedido, ou vazio (a funcao nao diz o motivo). */
    public Optional<UUID> requestOrganization(JdbcClient j, String sessionToken, UUID organization, String name, String legalName) {
        return j.sql("SELECT public.identity_request_organization(?::text, ?, ?::text, ?::text)")
                .param(sessionToken).param(organization).param(name).param(legalName).query(UUID.class).optional();
    }

    public record Request(UUID requestId, UUID organizationId, String organizationName, String name, String legalName, String state,
                          OffsetDateTime requestedAt, OffsetDateTime decidedAt, String responsible, String refusalReason, UUID createdOrganizationId) {
    }

    public static final List<String> REQUEST_STATES = List.of("PENDING", "APPROVED", "REFUSED");
    public static final List<String> RESPONSIBLE = List.of("APPROVER", "REQUESTER");

    /** {@code identity_organization_requests} (067): os pedidos da pessoa da sessao. */
    public List<Request> requests(JdbcClient j, String sessionToken) {
        return j.sql("SELECT request_id, organization_id, organization_name, name, legal_name, state, requested_at, decided_at, responsible, "
                        + "refusal_reason, created_organization_id FROM public.identity_organization_requests(?)").param(sessionToken)
                .query((rs, n) -> new Request(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        REQUEST_STATES.get(rs.getShort(6) - 1), rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class),
                        rs.getObject(9) == null ? null : RESPONSIBLE.get(rs.getShort(9) - 1), rs.getString(10), rs.getObject(11, UUID.class)))
                .list();
    }

    /** As organizacoes de que a pessoa e responsavel (067): ACTIVE, DEACTIVATED (por ela; reativavel) ou BLOCKED (parada por outro caminho). */
    public record Responsible(UUID organizationId, String name, String state, boolean empty, OffsetDateTime deactivatedAt, boolean canReactivate) {
    }

    public List<Responsible> responsibleFor(JdbcClient j, String sessionToken) {
        return j.sql("SELECT organization_id, name, status, empty, deactivated_at, reactivable FROM public.identity_responsible_organizations(?)")
                .param(sessionToken)
                .query((rs, n) -> new Responsible(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getShort(3) == 1 ? "ACTIVE" : rs.getBoolean(6) ? "DEACTIVATED" : "BLOCKED", rs.getBoolean(4),
                        rs.getObject(5, OffsetDateTime.class), rs.getBoolean(6)))
                .list();
    }

    /** {@code identity_deactivate_organization} / {@code identity_reactivate_organization} (067): 'OK', o motivo, ou vazio (sessao sem condicao). */
    public Optional<String> setActive(JdbcClient j, String sessionToken, UUID organization, boolean active) {
        return j.sql("SELECT public." + (active ? "identity_reactivate_organization" : "identity_deactivate_organization") + "(?::text, ?)")
                .param(sessionToken).param(organization).query(String.class).optional();
    }

    /** {@code identity_org_creation_allowed} (R5): SO indicacao para a interface ({@code canCreateOrganization}). */
    public boolean creationAllowed(JdbcClient j, String sessionToken) {
        return j.sql("SELECT public.identity_org_creation_allowed(?)").param(sessionToken).query(Boolean.class).single();
    }

    /** Sessao ainda ativa e no prazo, pelo relogio do banco agora (distingue 401 de 403 depois de uma recusa). */
    public boolean sessionActive(JdbcClient j, UUID identitySessionId) {
        return j.sql("SELECT EXISTS (SELECT 1 FROM public.identity_sessions WHERE identity_session_id = ? AND revoked_at IS NULL AND expires_at > clock_timestamp())")
                .param(identitySessionId).query(Boolean.class).single();
    }

    /** Evento da troca. Organizacao alvo so quando a sessao foi emitida (recusa nao registra o alvo pedido). */
    public void event(JdbcClient j, UUID identity, short type, UUID targetOrganization, UUID correlation) {
        j.sql("INSERT INTO public.identity_events (identity_id, event_type, target_organization_id, correlation_id) VALUES (?, ?, ?, ?)")
                .param(identity).param(type).param(targetOrganization).param(correlation).update();
    }

    private static List<String> roles(Array a) throws SQLException {
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }
}
