package com.nekkora.platform.admin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.platform.account.AccountRepository;
import com.nekkora.platform.admin.AccessActions.Failure;
import com.nekkora.platform.mail.MailIntents;
import com.nekkora.platform.mail.MailSender;
import com.nekkora.platform.mfa.MfaRepository;
import com.nekkora.platform.session.ValidSession;

import static com.nekkora.platform.admin.AccessActions.refuse;

/**
 * Papeis e vinculo (contrato de 2026-10-27, §5 e §6): consulta, atribuicao, alteracao e revogacao de papel; retirada do
 * vinculo. Autoridade pela regra da 003 (a mesma do gatilho da 023: role.assign_admin_roles para ORG_ADMIN e
 * WORKSPACE_ADMIN; role.assign_operator_viewer organizacional, ou de WORKSPACE_ADMIN no mesmo workspace, para OPERATOR e
 * VIEWER); o ator nunca e o alvo; atribuir e alterar exigem step-up. Ordem: organizacao {@code FOR UPDATE} -> contas do ator
 * e do alvo em ordem crescente -> atribuicao; reconferencias depois dos locks. O ultimo ORG_ADMIN e protegido pela 003 (403
 * {@code invariante}). A permissao e relida do banco a cada requisicao: papel revogado deixa de valer na proxima, sem
 * revogar sessoes; a retirada do vinculo revoga as sessoes da organizacao da conta (as da identidade nao mudam).
 */
public final class RoleAdminService {

    public record Assignment(UUID assignmentId, String role, UUID workspaceId) {
    }

    public record MemberRoles(UUID userId, UUID membershipId, String membershipState, List<Assignment> organizationRoles,
                              List<Assignment> workspaceRoles) {
    }

    private final AccessActions actions;
    private final AccessEvents events;
    private final MfaRepository mfa;
    private final MailIntents mail;

    RoleAdminService(AccessActions actions, AccessEvents events, MfaRepository mfa, MailIntents mail) {
        this.actions = actions;
        this.events = events;
        this.mfa = mfa;
        this.mail = mail;
    }

    public static RoleAdminService create(AccessActions actions, AccessEvents events, MfaRepository mfa, MailIntents mail) {
        return new RoleAdminService(actions, events, mfa, mail);
    }

    /** Papeis ativos de um membro (org.manage). */
    public MemberRoles list(UUID org, ValidSession actor, String actorToken, UUID user) {
        return actions.read(org, j -> {
            actions.requireActor(j, org, actor, actorToken);
            actions.requirePermission(j, org, actor, "org.manage");
            Membership m = membership(j, org, user, false).orElseThrow(() -> refuse(Failure.TARGET_NOT_FOUND, "membro"));
            List<Assignment> orgRoles = j.sql("""
                    SELECT a.assignment_id, r.code FROM public.organization_role_assignments a JOIN public.roles r ON r.role_id = a.role_id
                     WHERE a.organization_id = ? AND a.membership_id = ? AND a.status = 1 ORDER BY r.code, a.assignment_id
                    """).param(org).param(m.id()).query((rs, n) -> new Assignment(rs.getObject(1, UUID.class), rs.getString(2), null)).list();
            List<Assignment> wsRoles = j.sql("""
                    SELECT a.assignment_id, r.code, a.workspace_id FROM public.workspace_role_assignments a JOIN public.roles r ON r.role_id = a.role_id
                     WHERE a.organization_id = ? AND a.membership_id = ? AND a.status = 1 ORDER BY a.workspace_id, r.code, a.assignment_id
                    """).param(org).param(m.id())
                    .query((rs, n) -> new Assignment(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class))).list();
            return new MemberRoles(user, m.id(), state(m.status()), orgRoles, wsRoles);
        });
    }

    /** Atribui um papel. Ja ativo: devolve a atribuicao existente, sem evento nem aviso. */
    public Assignment assign(UUID org, ValidSession actor, String actorToken, UUID user, String role, UUID workspace) {
        Roles.Requested r = Roles.requested(role, workspace);
        if (r.code() == null) {
            throw com.nekkora.core.web.ApiException.invalid("papel");
        }
        return actions.write(org, j -> {
            Membership m = lockTarget(j, org, actor, actorToken, user);
            if (m.status() != 2 || !accountActive(j, org, user)) {
                throw refuse(Failure.CONFLICT, "vinculo_inativo");
            }
            if (r.workspace() != null && !Roles.workspaceActive(j, org, r.workspace())) {
                throw refuse(Failure.TARGET_NOT_FOUND, "workspace");
            }
            UUID roleId = r.roleId(j);
            actions.requireRoleAuthority(j, org, actor, roleId, r.workspace());
            actions.requireStepUp(j, actor);
            Optional<UUID> existing = active(j, org, m.id(), roleId, r.workspace());
            if (existing.isPresent()) {
                return new Assignment(existing.get(), r.code(), r.workspace());
            }
            UUID id = insert(j, org, m.id(), roleId, r, actor.membershipId());
            events.record(j, org, AccessEvents.Event.of(AccessEvents.Type.PAPEL_ATRIBUIDO, actor.membershipId(), actor.userId())
                    .target(user, m.id()).role(roleId, null, r.workspace()).assignment(id), AccessActions.correlation());
            notice(j, org, user, MailSender.Kind.NOTICE_ORG_ACCESS_CHANGED, "papel:" + id);
            return new Assignment(id, r.code(), r.workspace());
        });
    }

    /** Altera o papel no MESMO escopo (revoga a atribuicao e cria a nova numa transacao). Mesmo papel: sem efeito. */
    public Assignment replace(UUID org, ValidSession actor, String actorToken, UUID assignment, String newRole) {
        return actions.write(org, j -> {
            Current cur = current(j, org, assignment).orElseThrow(() -> refuse(Failure.TARGET_NOT_FOUND, "atribuicao"));
            Roles.Requested r = Roles.requested(newRole, cur.workspace());
            if (r.code() == null) {
                throw refuse(Failure.INVALID, "papel");
            }
            lockTarget(j, org, actor, actorToken, cur.user());
            Current locked = lockCurrent(j, org, cur);
            if (locked.status() != 1) {
                throw refuse(Failure.CONFLICT, "atribuicao_revogada");
            }
            UUID newRoleId = r.roleId(j);
            actions.requireRoleAuthority(j, org, actor, locked.roleId(), locked.workspace());
            actions.requireRoleAuthority(j, org, actor, newRoleId, locked.workspace());
            actions.requireStepUp(j, actor);
            if (newRoleId.equals(locked.roleId())) {
                return new Assignment(locked.id(), locked.role(), locked.workspace());
            }
            if (active(j, org, locked.membership(), newRoleId, locked.workspace()).isPresent()) {
                throw refuse(Failure.CONFLICT, "papel_ja_ativo");
            }
            revokeRow(j, org, locked, actor.membershipId());
            UUID id = insert(j, org, locked.membership(), newRoleId, r, actor.membershipId());
            events.record(j, org, AccessEvents.Event.of(AccessEvents.Type.PAPEL_ALTERADO, actor.membershipId(), actor.userId())
                    .target(locked.user(), locked.membership()).role(newRoleId, locked.roleId(), locked.workspace()).assignment(id),
                    AccessActions.correlation());
            notice(j, org, locked.user(), MailSender.Kind.NOTICE_ORG_ACCESS_CHANGED, "papel:" + id);
            return new Assignment(id, r.code(), locked.workspace());
        });
    }

    /** Revoga uma atribuicao. Ja revogada: 204 sem efeito. Ultimo ORG_ADMIN: 403 invariante (003). */
    public void revoke(UUID org, ValidSession actor, String actorToken, UUID assignment) {
        actions.write(org, j -> {
            Current cur = current(j, org, assignment).orElseThrow(() -> refuse(Failure.TARGET_NOT_FOUND, "atribuicao"));
            lockTarget(j, org, actor, actorToken, cur.user());
            Current locked = lockCurrent(j, org, cur);
            actions.requireRoleAuthority(j, org, actor, locked.roleId(), locked.workspace());
            if (locked.status() == 1) {
                revokeRow(j, org, locked, actor.membershipId());
                events.record(j, org, AccessEvents.Event.of(AccessEvents.Type.PAPEL_REVOGADO, actor.membershipId(), actor.userId())
                        .target(locked.user(), locked.membership()).role(locked.roleId(), null, locked.workspace()).assignment(locked.id()),
                        AccessActions.correlation());
                notice(j, org, locked.user(), MailSender.Kind.NOTICE_ORG_ACCESS_CHANGED, "revogado:" + locked.id());
            }
            return null;
        });
    }

    /**
     * Retira o vinculo (org.manage): todas as atribuicoes ativas revogadas, vinculo REVOKED, sessoes da organizacao da conta
     * revogadas e desafios invalidados, na mesma transacao. Ja retirado: 204 sem efeito.
     */
    public void revokeMembership(UUID org, ValidSession actor, String actorToken, UUID user) {
        actions.write(org, j -> {
            Membership m = lockTarget(j, org, actor, actorToken, user);
            actions.requirePermission(j, org, actor, "org.manage");
            Membership locked = membership(j, org, user, true).orElseThrow(() -> refuse(Failure.TARGET_NOT_FOUND, "membro"));
            if (locked.status() == 4) {
                return null;
            }
            j.sql("""
                    UPDATE public.workspace_role_assignments SET status = 2, revoked_at = greatest(clock_timestamp(), created_at), revoked_by_membership_id = ?
                     WHERE organization_id = ? AND membership_id = ? AND status = 1
                    """).param(actor.membershipId()).param(org).param(m.id()).update();
            j.sql("""
                    UPDATE public.organization_role_assignments SET status = 2, revoked_at = greatest(clock_timestamp(), created_at), revoked_by_membership_id = ?
                     WHERE organization_id = ? AND membership_id = ? AND status = 1
                    """).param(actor.membershipId()).param(org).param(m.id()).update();
            j.sql("UPDATE public.organization_memberships SET status = 4 WHERE organization_id = ? AND membership_id = ?").param(org).param(m.id()).update();
            mfa.invalidateForAccount(j, user, null);
            actions.sessions().revokeForAccount(j, user, null);
            events.record(j, org, AccessEvents.Event.of(AccessEvents.Type.VINCULO_RETIRADO, actor.membershipId(), actor.userId()).target(user, m.id()),
                    AccessActions.correlation());
            notice(j, org, user, MailSender.Kind.NOTICE_ORG_MEMBERSHIP_REVOKED, "vinculo:" + m.id() + ":" + AccessActions.correlation());
            return null;
        });
    }

    /** Organizacao -> contas (ator e alvo, crescente) -> reconferencias do ator; o alvo precisa existir e nao ser o ator. */
    private Membership lockTarget(JdbcClient j, UUID org, ValidSession actor, String actorToken, UUID user) {
        if (!actions.lockOrganizationAndAccounts(j, org, actor.userId(), user)) {
            throw refuse(Failure.TARGET_NOT_FOUND, "membro");
        }
        actions.requireActor(j, org, actor, actorToken);
        if (user.equals(actor.userId())) {
            throw refuse(Failure.OWN_ACCOUNT, "proprio");
        }
        return membership(j, org, user, false).orElseThrow(() -> refuse(Failure.TARGET_NOT_FOUND, "membro"));
    }

    private void notice(JdbcClient j, UUID org, UUID user, MailSender.Kind kind, String key) {
        actions.accounts().byId(j, org, user).map(AccountRepository.Account::email)
                .ifPresent(email -> mail.record(j, new MailIntents.Intent(kind, org, email, null, key)));
    }

    private record Membership(UUID id, short status) {
    }

    private static Optional<Membership> membership(JdbcClient j, UUID org, UUID user, boolean lock) {
        return j.sql("SELECT membership_id, status FROM public.organization_memberships WHERE organization_id = ? AND user_id = ?" + (lock ? " FOR UPDATE" : ""))
                .param(org).param(user).query((rs, n) -> new Membership(rs.getObject(1, UUID.class), rs.getShort(2))).optional();
    }

    private static boolean accountActive(JdbcClient j, UUID org, UUID user) {
        return j.sql("SELECT EXISTS (SELECT 1 FROM public.users WHERE organization_id = ? AND user_id = ? AND status = 2)")
                .param(org).param(user).query(Boolean.class).single();
    }

    private static Optional<UUID> active(JdbcClient j, UUID org, UUID membership, UUID role, UUID workspace) {
        if (workspace == null) {
            return j.sql("SELECT assignment_id FROM public.organization_role_assignments WHERE organization_id = ? AND membership_id = ? AND role_id = ? AND status = 1")
                    .param(org).param(membership).param(role).query(UUID.class).optional();
        }
        return j.sql("SELECT assignment_id FROM public.workspace_role_assignments WHERE organization_id = ? AND workspace_id = ? AND membership_id = ? AND role_id = ? AND status = 1")
                .param(org).param(workspace).param(membership).param(role).query(UUID.class).optional();
    }

    private static UUID insert(JdbcClient j, UUID org, UUID membership, UUID role, Roles.Requested r, UUID by) {
        if (r.workspace() == null) {
            return j.sql("""
                    INSERT INTO public.organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id)
                    VALUES (?, ?, ?, 1, 1, ?) RETURNING assignment_id
                    """).param(org).param(membership).param(role).param(by).query(UUID.class).single();
        }
        return j.sql("""
                INSERT INTO public.workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status, assigned_by_membership_id)
                VALUES (?, ?, ?, ?, 2, 1, ?) RETURNING assignment_id
                """).param(org).param(r.workspace()).param(membership).param(role).param(by).query(UUID.class).single();
    }

    /** Atribuicao (de organizacao ou de workspace) com o vinculo e a conta do alvo. */
    private record Current(UUID id, UUID membership, UUID user, UUID roleId, String role, UUID workspace, short status) {
    }

    private static final String CURRENT = """
            SELECT a.assignment_id, a.membership_id, m.user_id, a.role_id, r.code, %s, a.status
              FROM public.%s a JOIN public.organization_memberships m ON m.organization_id = a.organization_id AND m.membership_id = a.membership_id
              JOIN public.roles r ON r.role_id = a.role_id
             WHERE a.organization_id = ? AND a.assignment_id = ?
            """;

    private static Optional<Current> current(JdbcClient j, UUID org, UUID assignment) {
        Optional<Current> c = j.sql(CURRENT.formatted("NULL::uuid", "organization_role_assignments")).param(org).param(assignment)
                .query(RoleAdminService::mapCurrent).optional();
        return c.isPresent() ? c : j.sql(CURRENT.formatted("a.workspace_id", "workspace_role_assignments")).param(org).param(assignment)
                .query(RoleAdminService::mapCurrent).optional();
    }

    private static Current lockCurrent(JdbcClient j, UUID org, Current c) {
        String table = c.workspace() == null ? "organization_role_assignments" : "workspace_role_assignments";
        return j.sql(CURRENT.formatted(c.workspace() == null ? "NULL::uuid" : "a.workspace_id", table) + " FOR UPDATE OF a").param(org).param(c.id())
                .query(RoleAdminService::mapCurrent).single();
    }

    private static Current mapCurrent(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Current(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                rs.getString(5), rs.getObject(6, UUID.class), rs.getShort(7));
    }

    private static void revokeRow(JdbcClient j, UUID org, Current c, UUID by) {
        String table = c.workspace() == null ? "organization_role_assignments" : "workspace_role_assignments";
        j.sql("UPDATE public." + table + " SET status = 2, revoked_at = greatest(clock_timestamp(), created_at), revoked_by_membership_id = ?"
                + " WHERE organization_id = ? AND assignment_id = ? AND status = 1").param(by).param(org).param(c.id()).update();
    }

    private static String state(short status) {
        return switch (status) {
            case 1 -> "PENDENTE";
            case 2 -> "ATIVO";
            case 3 -> "SUSPENSO";
            case 4 -> "RETIRADO";
            default -> throw new IllegalStateException("estado de vinculo fora do catalogo: " + status);
        };
    }
}
