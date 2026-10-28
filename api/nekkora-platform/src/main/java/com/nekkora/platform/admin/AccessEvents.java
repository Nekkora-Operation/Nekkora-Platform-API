package com.nekkora.platform.admin;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Auditoria dos atos de acesso ({@code access_events}, 023): uma linha por ato com efeito, gravada na MESMA transacao do
 * ato e com o cliente dela (RLS da organizacao). Imutavel no banco. Recusa nao grava (so log com {@code regra=}).
 */
public final class AccessEvents {

    /** Codigos permanentes de {@code access_events.event_type} (cabecalho da 023; 14 da 024). */
    public enum Type {
        CONVITE_EMITIDO(1), CONVITE_REVOGADO(2), CONVITE_REENVIADO(3), CONVITE_ACEITO(4), PAPEL_ATRIBUIDO(5), PAPEL_REVOGADO(6),
        PAPEL_ALTERADO(7), VINCULO_RETIRADO(8), CONCESSAO_CRIADA(9), CONCESSAO_APROVADA(10), CONCESSAO_RENOVADA(11),
        CONCESSAO_REVISADA(12), CONCESSAO_REVOGADA(13), CONCESSAO_RENOVACAO_SOLICITADA(14);

        private final short code;

        Type(int code) {
            this.code = (short) code;
        }

        public short code() {
            return code;
        }

        public static Type of(int code) {
            for (Type t : values()) {
                if (t.code == code) {
                    return t;
                }
            }
            throw new IllegalArgumentException("tipo de evento de acesso fora do catalogo: " + code);
        }
    }

    /** Ato de administracao (autor = vinculo da organizacao). Campos nulos quando nao se aplicam ao tipo. */
    public record Event(Type type, UUID actorMembership, UUID actorUser, UUID targetUser, UUID targetMembership, UUID role,
                        UUID previousRole, UUID workspace, UUID invitation, UUID assignment, UUID grant, Short decision) {

        public static Event of(Type type, UUID actorMembership, UUID actorUser) {
            return new Event(type, actorMembership, actorUser, null, null, null, null, null, null, null, null, null);
        }

        public Event target(UUID user, UUID membership) {
            return new Event(type, actorMembership, actorUser, user, membership, role, previousRole, workspace, invitation, assignment, grant, decision);
        }

        public Event role(UUID r, UUID previous, UUID ws) {
            return new Event(type, actorMembership, actorUser, targetUser, targetMembership, r, previous, ws, invitation, assignment, grant, decision);
        }

        public Event invitation(UUID i) {
            return new Event(type, actorMembership, actorUser, targetUser, targetMembership, role, previousRole, workspace, i, assignment, grant, decision);
        }

        public Event assignment(UUID a) {
            return new Event(type, actorMembership, actorUser, targetUser, targetMembership, role, previousRole, workspace, invitation, a, grant, decision);
        }

        public Event grant(UUID g, Short d) {
            return new Event(type, actorMembership, actorUser, targetUser, targetMembership, role, previousRole, workspace, invitation, assignment, g, d);
        }
    }

    public void record(JdbcClient j, UUID org, Event e, UUID correlation) {
        j.sql("""
                INSERT INTO public.access_events (organization_id, event_type, actor_membership_id, actor_user_id, target_user_id,
                    target_membership_id, role_id, previous_role_id, workspace_id, invitation_id, assignment_id, grant_id, decision, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).param(org).param(e.type().code()).param(e.actorMembership()).param(e.actorUser()).param(e.targetUser())
                .param(e.targetMembership()).param(e.role()).param(e.previousRole()).param(e.workspace()).param(e.invitation())
                .param(e.assignment()).param(e.grant()).param(e.decision()).param(correlation).update();
    }
}
