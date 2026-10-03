package com.nekkora.platform;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.nekkora.core.testsupport.RealDatabase;

/**
 * Massa de papeis e atribuicoes para os testes da GAP-02/03/06 (preparacao como superusuario, DECLARADA: nao e
 * evidencia; o que e testado passa pela API ou pelo componente com o papel nekkora_platform).
 */
final class AccessFixtures {

    private static final RealDatabase DB = RealDatabase.shared();

    private AccessFixtures() {
    }

    static String value(String sql) {
        try (Connection c = DB.connectAsSuperuser(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new IllegalStateException("consulta da massa falhou", e);
        }
    }

    static UUID membershipOf(UUID org, UUID user) {
        return UUID.fromString(value("SELECT membership_id FROM organization_memberships WHERE organization_id = '" + org + "' AND user_id = '" + user + "'"));
    }

    static UUID clientMembershipOf(UUID org, UUID user) {
        return UUID.fromString(value("SELECT client_membership_id FROM client_memberships WHERE organization_id = '" + org + "' AND user_id = '" + user + "'"));
    }

    static UUID assignWorkspaceRole(UUID org, UUID membership, UUID workspace, String role, UUID assignedBy) {
        return UUID.fromString(value("INSERT INTO workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status, "
                + "assigned_by_membership_id) SELECT '" + org + "', '" + workspace + "', '" + membership + "', role_id, 2, 1, '" + assignedBy
                + "' FROM roles WHERE code = '" + role + "' RETURNING assignment_id"));
    }

    static UUID assignOrgRole(UUID org, UUID membership, String role, UUID assignedBy) {
        return UUID.fromString(value("INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, "
                + "assigned_by_membership_id) SELECT '" + org + "', '" + membership + "', role_id, 1, 1, '" + assignedBy
                + "' FROM roles WHERE code = '" + role + "' RETURNING assignment_id"));
    }

    static void revokeWorkspaceAssignment(UUID assignment, UUID revokedBy) {
        DB.asSuperuser("UPDATE workspace_role_assignments SET status = 2, revoked_at = now(), revoked_by_membership_id = '" + revokedBy
                + "' WHERE assignment_id = '" + assignment + "'");
    }

    static void revokeOrgAssignment(UUID assignment, UUID revokedBy) {
        DB.asSuperuser("UPDATE organization_role_assignments SET status = 2, revoked_at = now(), revoked_by_membership_id = '" + revokedBy
                + "' WHERE assignment_id = '" + assignment + "'");
    }

    /**
     * IDs EXATOS dos papeis fora do catalogo criados pelos testes nesta JVM. A base de teste e uma por JVM
     * (RealDatabase.shared), entao este registro cobre exatamente os papeis-fixture dessa base; qualquer outro papel
     * fora do catalogo reprova o PermissionCatalogIT.
     */
    static final Set<UUID> ROLES_CREATED_BY_TESTS = ConcurrentHashMap.newKeySet();

    record TestRole(UUID id, String code) {
    }

    /** Papel fora do catalogo (escopo 1 = organizacao, 2 = workspace) com uma permissao; registrado pelo ID. */
    static TestRole roleOutsideCatalog(int scope, String permission) {
        String code = "FIXTURE_FORA_DO_CATALOGO_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        UUID id = UUID.fromString(value("INSERT INTO roles (code, scope, description) VALUES ('" + code + "', " + scope
                + ", 'papel fora do catalogo (fixture de teste)') RETURNING role_id"));
        ROLES_CREATED_BY_TESTS.add(id);
        DB.asSuperuser("INSERT INTO role_permissions (role_id, permission_id) SELECT '" + id + "', permission_id FROM permissions WHERE code = '"
                + permission + "'");
        return new TestRole(id, code);
    }

    /** Cliente com id e nome escolhidos (nome passado por parametro: aceita controles, aspas e Unicode). */
    static UUID namedClient(UUID org, UUID id, String name, int status) {
        try (Connection c = DB.connectAsSuperuser();
             PreparedStatement ps = c.prepareStatement("INSERT INTO clients (client_id, organization_id, name, status) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, org);
            ps.setString(3, name);
            ps.setInt(4, status);
            ps.executeUpdate();
            return id;
        } catch (Exception e) {
            throw new IllegalStateException("cliente da massa falhou", e);
        }
    }

    static void renameClient(UUID client, String name) {
        try (Connection c = DB.connectAsSuperuser(); PreparedStatement ps = c.prepareStatement("UPDATE clients SET name = ? WHERE client_id = ?")) {
            ps.setString(1, name);
            ps.setObject(2, client);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("renomear cliente falhou", e);
        }
    }

    /** Fator TOTP de massa (segredo de zeros: nunca usado para autenticar). */
    static void mfaFactor(UUID org, UUID user, boolean confirmed, boolean disabled) {
        DB.asSuperuser("INSERT INTO user_mfa_factors (organization_id, user_id, factor_type, secret_ciphertext, secret_nonce, key_id, confirmed_at, "
                + "disabled_at) VALUES ('" + org + "', '" + user + "', 1, decode(repeat('00', 36), 'hex'), decode(repeat('00', 12), 'hex'), 'k1', "
                + (confirmed ? "now()" : "NULL") + ", " + (disabled ? "now()" : "NULL") + ")");
    }

    static UUID namedWorkspace(UUID org, String name, int status) {
        UUID w = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO workspaces (workspace_id, organization_id, client_id, name, status) VALUES ('" + w + "', '" + org
                + "', NULL, '" + name.replace("'", "''") + "', " + status + ")");
        return w;
    }
}
