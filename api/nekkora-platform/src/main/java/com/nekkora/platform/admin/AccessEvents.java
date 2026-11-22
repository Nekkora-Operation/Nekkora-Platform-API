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
        CONCESSAO_REVISADA(12), CONCESSAO_REVOGADA(13), CONCESSAO_RENOVACAO_SOLICITADA(14),
        // 070: concessao por automacao, vinculo de cliente e cadastro de clientes
        CLIENTE_CONCESSAO_SOLICITADA(15), CLIENTE_CONCESSAO_APROVADA(16), CLIENTE_CONCESSAO_APLICADA_NO_ACEITE(17), CLIENTE_CONCESSAO_REDUZIDA(18),
        CLIENTE_CONCESSAO_REVOGADA(19), VINCULO_CLIENTE_SUSPENSO(20), VINCULO_CLIENTE_REATIVADO(21), VINCULO_CLIENTE_RETIRADO(22),
        CLIENTE_CADASTRADO(23), CLIENTE_ALTERADO(24),
        // 071: registros tecnicos retirados (ato do sistema) e pedidos de compartilhamento de membro
        CLIENTE_REGISTROS_TECNICOS_RETIRADOS(25), COMPARTILHAMENTO_SOLICITADO(26), COMPARTILHAMENTO_APROVADO(27), COMPARTILHAMENTO_RECUSADO(28),
        COMPARTILHAMENTO_CANCELADO(29);

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

    /**
     * Ato sobre cliente (070/071): o escopo vai em colunas proprias (cliente, vinculo de cliente, automacao, maquina, concessao por
     * automacao, conteudos, pedido de compartilhamento). {@code exception}: o ato usou a excecao a D-GRANT-1 do convite de Cliente.
     * Registros tecnicos nao sao mais compartilhaveis (071): com escopo, o evento grava {@code can_read_logs = false}.
     */
    public record ClientEvent(Type type, UUID actorMembership, UUID actorUser, UUID targetUser, UUID workspace, UUID invitation, UUID client,
                              UUID clientMembership, UUID automation, UUID worker, UUID automationGrant, Boolean canViewDashboard,
                              Boolean canDownloadResults, UUID shareRequest, boolean exception) {

        public static ClientEvent of(Type type, UUID actorMembership, UUID actorUser, UUID client) {
            return new ClientEvent(type, actorMembership, actorUser, null, null, null, client, null, null, null, null, null, null, null, false);
        }

        public ClientEvent target(UUID user, UUID clientMembership) {
            return new ClientEvent(type, actorMembership, actorUser, user, workspace, invitation, client, clientMembership, automation, worker,
                    automationGrant, canViewDashboard, canDownloadResults, shareRequest, exception);
        }

        public ClientEvent scope(UUID ws, UUID a, UUID w, Boolean dashboard, Boolean results) {
            return new ClientEvent(type, actorMembership, actorUser, targetUser, ws, invitation, client, clientMembership, a, w, automationGrant,
                    dashboard, results, shareRequest, exception);
        }

        public ClientEvent invitation(UUID i, boolean usesException) {
            return new ClientEvent(type, actorMembership, actorUser, targetUser, workspace, i, client, clientMembership, automation, worker,
                    automationGrant, canViewDashboard, canDownloadResults, shareRequest, usesException);
        }

        public ClientEvent grant(UUID g) {
            return new ClientEvent(type, actorMembership, actorUser, targetUser, workspace, invitation, client, clientMembership, automation, worker, g,
                    canViewDashboard, canDownloadResults, shareRequest, exception);
        }

        public ClientEvent shareRequest(UUID r) {
            return new ClientEvent(type, actorMembership, actorUser, targetUser, workspace, invitation, client, clientMembership, automation, worker,
                    automationGrant, canViewDashboard, canDownloadResults, r, exception);
        }
    }

    public void record(JdbcClient j, UUID org, ClientEvent e, UUID correlation) {
        Boolean logs = e.canViewDashboard() == null && e.canDownloadResults() == null ? null : Boolean.FALSE;
        j.sql("""
                INSERT INTO public.access_events (organization_id, event_type, actor_membership_id, actor_user_id, target_user_id, workspace_id,
                    invitation_id, client_id, client_membership_id, automation_id, worker_id, automation_grant_id, can_read_logs,
                    can_download_results, can_view_dashboard, share_request_id, grant_exception, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).param(org).param(e.type().code()).param(e.actorMembership()).param(e.actorUser()).param(e.targetUser()).param(e.workspace())
                .param(e.invitation()).param(e.client()).param(e.clientMembership()).param(e.automation()).param(e.worker()).param(e.automationGrant())
                .param(logs).param(e.canDownloadResults()).param(e.canViewDashboard()).param(e.shareRequest()).param(e.exception()).param(correlation)
                .update();
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
