package com.nekkora.agentgateway;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import com.nekkora.core.testsupport.RealDatabase;
import com.nekkora.core.token.Tokens;

/**
 * Preparacao DECLARADA (como superusuario) de uma organizacao para o canal: conta e vinculo ativos com ORG_ADMIN, limites
 * ilimitados (o INSERT direto do superusuario nao e barrado pela 022), workspace, automacao com versao PUBLICADA cujo
 * artefato e a impressao digital do pacote real da COMPAT-001, Worker autorizado no workspace e autorizacao de execucao
 * local. Os gatilhos da 025 continuam valendo (inclusive a autoridade de quem cria codigo e autorizacao).
 */
final class AgentFixture {

    /** Impressao digital do pacote real gerado pelo Studio e aprovado pelo Agent (registro a1 da COMPAT-001). */
    static final String REAL_FINGERPRINT = "5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d";

    final RealDatabase db;
    final UUID org = UUID.randomUUID();
    final UUID admin = UUID.randomUUID();
    final UUID adminMembership = UUID.randomUUID();
    final UUID workspace = UUID.randomUUID();
    final UUID automation = UUID.randomUUID();
    final UUID version = UUID.randomUUID();

    AgentFixture(RealDatabase db) {
        this.db = db;
        String o = "'" + org + "'";
        db.asSuperuser(
                "INSERT INTO organizations (organization_id, name, status) VALUES (" + o + ", 'Org canal " + org.toString().substring(0, 6) + "', 1)",
                "INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('" + admin + "', " + o
                        + ", 1, 'adm-" + admin.toString().substring(0, 8) + "@o.test', now(), 2, 'Adm')",
                "INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('" + adminMembership + "', " + o
                        + ", '" + admin + "', 1, 2)",
                "INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id) SELECT " + o
                        + ", '" + adminMembership + "', role_id, 1, 1, '" + adminMembership + "' FROM roles WHERE code = 'ORG_ADMIN'",
                "INSERT INTO workspaces (workspace_id, organization_id, name, status) VALUES ('" + workspace + "', " + o + ", 'WS canal', 1)");
        for (int code = 1; code <= 5; code++) {
            db.asSuperuser("INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, "
                    + "limit_value, provisioned_by_user_id) VALUES (" + o + ", " + code + ", 1, now(), 2, NULL, '" + admin + "')");
        }
        db.asSuperuser(
                "INSERT INTO automations (automation_id, organization_id, workspace_id, name, status, created_by_membership_id) VALUES ('" + automation + "', "
                        + o + ", '" + workspace + "', 'Biografias', 1, '" + adminMembership + "')",
                "INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id) "
                        + "VALUES ('" + version + "', " + o + ", '" + workspace + "', '" + automation + "', 1, jsonb_build_object('contract_version',1,'artifact',"
                        + "jsonb_build_object('reference','biografias-python','sha256','" + "ab".repeat(32) + "'),'agent_package',jsonb_build_object('fingerprint_rule','nekkora-agent/package-fingerprint/1','fingerprint','" + REAL_FINGERPRINT + "'),'runtime','python3.12','entrypoint','main.py',"
                        + "'arguments','[]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb), '" + adminMembership + "')",
                "UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '" + adminMembership + "' WHERE automation_version_id = '"
                        + version + "'");
    }

    UUID worker(String name) {
        return UUID.fromString(value("INSERT INTO workers (organization_id, name, status) VALUES ('" + org + "', '" + name + "', 1) RETURNING worker_id"));
    }

    UUID authorizeWorker(UUID worker) {
        return UUID.fromString(value("INSERT INTO worker_workspace_assignments (organization_id, worker_id, workspace_id, status, authorized_by_membership_id) VALUES ('"
                + org + "', '" + worker + "', '" + workspace + "', 1, '" + adminMembership + "') RETURNING assignment_id"));
    }

    UUID localRun(UUID worker, UUID workerWorkspace) {
        return UUID.fromString(value("INSERT INTO local_run_authorizations (organization_id, workspace_id, worker_id, worker_workspace_assignment_id, automation_id, "
                + "automation_version_id, package_fingerprint, granted_by_membership_id, valid_until) VALUES ('" + org + "', '" + workspace + "', '" + worker + "', '"
                + workerWorkspace + "', '" + automation + "', '" + version + "', '" + REAL_FINGERPRINT + "', '" + adminMembership
                + "', now() + interval '30 days') RETURNING authorization_id"));
    }

    /** Codigo de pareamento (o texto so existe aqui; o banco guarda o hash). */
    String pairingCode(UUID worker, String agentName, String ttl) {
        UUID enrollment = UUID.randomUUID();
        String code = MachineToken.enrollment(org, enrollment, Tokens.newToken());
        db.asSuperuser("INSERT INTO agent_enrollments (enrollment_id, organization_id, purpose, worker_id, agent_name, code_hash, created_by_membership_id, expires_at) "
                + "VALUES ('" + enrollment + "', '" + org + "', 1, '" + worker + "', '" + agentName + "', '" + Tokens.sha256Hex(code) + "', '" + adminMembership
                + "', clock_timestamp() + interval '" + ttl + "')");
        return code;
    }

    String rotationCode(UUID agent, UUID worker) {
        UUID enrollment = UUID.randomUUID();
        String code = MachineToken.enrollment(org, enrollment, Tokens.newToken());
        db.asSuperuser("INSERT INTO agent_enrollments (enrollment_id, organization_id, purpose, worker_id, agent_id, code_hash, created_by_membership_id, expires_at) "
                + "VALUES ('" + enrollment + "', '" + org + "', 2, '" + worker + "', '" + agent + "', '" + Tokens.sha256Hex(code) + "', '" + adminMembership
                + "', clock_timestamp() + interval '15 minutes')");
        return code;
    }

    String value(String sql) {
        try (Connection c = db.connectAsSuperuser(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("fixture falhou (sqlstate " + e.getSQLState() + ")", e);
        }
    }

    long count(String sql) {
        String v = value(sql);
        return v == null ? 0 : Long.parseLong(v);
    }
}
