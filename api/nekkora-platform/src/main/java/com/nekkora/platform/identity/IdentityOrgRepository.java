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
    public record Organization(UUID organizationId, String name, List<String> roles, String activationState) {
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
        return linhas.stream().map(l -> new Organization(l.id(), l.name(), l.roles(), estados.getOrDefault(l.id(), AGUARDANDO_ATIVACAO))).toList();
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
