package com.nekkora.agentgateway;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiException;

/**
 * Autenticacao da maquina DENTRO da transacao do ato (contrato, sec. 4.4). A credencial e travada FOR SHARE: uma
 * revogacao confirmada antes faz o ato ser recusado; uma revogacao concorrente espera o ato terminar. Toda falha e o mesmo
 * 401, sem dizer o que faltou.
 */
public final class MachineAuth {

    /** Maquina autenticada: so identificadores conferidos no servidor. */
    public record Machine(UUID organization, UUID agentId, UUID workerId, UUID credentialId, UUID assignmentId, OffsetDateTime credentialExpiresAt) {
    }

    private record Row(UUID credentialId, UUID agentId, String hash, boolean active, boolean notExpired, short agentStatus, UUID assignmentId,
                       UUID workerId, OffsetDateTime expiresAt) {
    }

    private static final String SQL = """
            SELECT c.credential_id, c.agent_id, c.credential_hash, c.revoked_at IS NULL AS active, c.expires_at > clock_timestamp() AS not_expired,
                   a.status AS agent_status, wa.assignment_id, wa.worker_id, c.expires_at
              FROM public.agent_credentials c
              JOIN public.agents a ON a.organization_id = c.organization_id AND a.agent_id = c.agent_id
              LEFT JOIN public.worker_agent_assignments wa ON wa.organization_id = c.organization_id AND wa.agent_id = c.agent_id AND wa.status = 1
             WHERE c.organization_id = ? AND c.credential_id = ?
               FOR SHARE OF c
            """;

    /** Credencial valida (nao revogada, nao vencida), Agent ativo e associado a um Worker. */
    public Machine authenticate(JdbcClient j, MachineToken t) {
        Row r = load(j, t);
        if (!r.active() || !r.notExpired() || r.agentStatus() != 2 || r.assignmentId() == null) {
            throw ApiException.unauthenticated("credencial");
        }
        return new Machine(t.organization(), r.agentId(), r.workerId(), r.credentialId(), r.assignmentId(), r.expiresAt());
    }

    /** Rotacao: a credencial atual do Agent, mesmo vencida, desde que nao revogada (contrato, sec. 4.3). */
    public Machine authenticateForRotation(JdbcClient j, MachineToken t) {
        Row r = load(j, t);
        if (!r.active() || r.agentStatus() != 2 || r.assignmentId() == null) {
            throw ApiException.unauthenticated("credencial");
        }
        return new Machine(t.organization(), r.agentId(), r.workerId(), r.credentialId(), r.assignmentId(), r.expiresAt());
    }

    private static Row load(JdbcClient j, MachineToken t) {
        if (!MachineToken.CREDENTIAL.equals(t.prefix())) {
            throw ApiException.unauthenticated("formato");
        }
        Row r = j.sql(SQL).param(t.organization()).param(t.id())
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5),
                        rs.getShort(6), rs.getObject(7, UUID.class), rs.getObject(8, UUID.class), rs.getObject(9, OffsetDateTime.class)))
                .optional().orElse(null);
        if (r == null || !Tokens.constantTimeEquals(r.hash(), Tokens.sha256Hex(t.secret()))) {
            throw ApiException.unauthenticated("credencial");
        }
        return r;
    }
}
