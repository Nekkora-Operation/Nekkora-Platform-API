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

    /**
     * Situacao da credencial para o desvinculo pela maquina (PLATFORM-AGENT-V2-004, T13). So depois de o segredo conferir.
     * {@code revocationReason} nulo = nao revogada. {@code assignmentId}/{@code workerId}: a associacao ATIVA, se houver.
     */
    public record UnlinkView(UUID organization, UUID agentId, UUID credentialId, short agentStatus, Short revocationReason, UUID assignmentId,
                             UUID workerId) {
        public boolean revoked() {
            return revocationReason != null;
        }
    }

    /**
     * Autenticacao do desvinculo: a posse do segredo da credencial, MESMO revogada ou vencida (a classificacao e do chamador:
     * so a repeticao da propria operacao ou a resposta "ja encerrado" aceitam uma credencial revogada; as demais rotas usam
     * {@link #authenticate}). Travas na ordem da revogacao administrativa (Agent, depois credencial): sem impasse com ela, e
     * um ato em curso da maquina (credencial FOR SHARE) termina antes.
     */
    public UnlinkView authenticateForUnlink(JdbcClient j, MachineToken t) {
        Row r = load(j, t, SQL_NO_LOCK); // confere o segredo SEM trava: a primeira trava e a do Agent (ordem da revogacao)
        short status = j.sql("SELECT status FROM public.agents WHERE organization_id = ? AND agent_id = ? FOR UPDATE")
                .param(t.organization()).param(r.agentId()).query(Short.class).single();
        // 0 = nao revogada (o motivo nulo vira 0 aqui: single() nao aceita um valor nulo)
        short code = j.sql("SELECT coalesce(revocation_reason, 0) FROM public.agent_credentials WHERE organization_id = ? AND credential_id = ? FOR UPDATE")
                .param(t.organization()).param(r.credentialId()).query(Short.class).single();
        Short reason = code == 0 ? null : code;
        record Active(UUID assignment, UUID worker) {
        }
        Active a = j.sql("SELECT assignment_id, worker_id FROM public.worker_agent_assignments WHERE organization_id = ? AND agent_id = ? AND status = 1")
                .param(t.organization()).param(r.agentId())
                .query((rs, n) -> new Active(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).optional().orElse(new Active(null, null));
        return new UnlinkView(t.organization(), r.agentId(), r.credentialId(), status, reason, a.assignment(), a.worker());
    }

    private static Row load(JdbcClient j, MachineToken t) {
        return load(j, t, SQL);
    }

    /** A mesma leitura sem FOR SHARE (desvinculo: trava o Agent antes da credencial). */
    private static final String SQL_NO_LOCK = SQL.replace("FOR SHARE OF c", "");

    private static Row load(JdbcClient j, MachineToken t, String sql) {
        if (!MachineToken.CREDENTIAL.equals(t.prefix())) {
            throw ApiException.unauthenticated("formato");
        }
        Row r = j.sql(sql).param(t.organization()).param(t.id())
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5),
                        rs.getShort(6), rs.getObject(7, UUID.class), rs.getObject(8, UUID.class), rs.getObject(9, OffsetDateTime.class)))
                .optional().orElse(null);
        if (r == null || !Tokens.constantTimeEquals(r.hash(), Tokens.sha256Hex(t.secret()))) {
            throw ApiException.unauthenticated("credencial");
        }
        return r;
    }
}
