package com.nekkora.platform;

import java.util.UUID;

import com.nekkora.core.testsupport.RealDatabase;

/**
 * Massa da GAP-06 (preparacao como superusuario, DECLARADA: nao e evidencia). Segue as regras do banco: execucao nasce
 * PENDING com o comando da versao publicada (006), transicoes validas, reserva com Worker/Agent/autorizacoes ativos,
 * eventos so de execucao alocada e com o agent da alocacao (007), execucao agendada pelo fluxo real da 009
 * (agenda -> revisao -> ocorrencia -> process_schedule_occurrence). Mesmo padrao de massa do PortalIT.
 */
final class ProductFixtures {

    private static final RealDatabase DB = RealDatabase.shared();
    static final String COMMAND = "jsonb_build_object('contract_version',1,'artifact',jsonb_build_object('reference','v1','sha256',repeat('a',64)),"
            + "'runtime','python3.11','entrypoint','main.py','arguments','[]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb)";

    private ProductFixtures() {
    }

    /** Os cinco limites da 010 ilimitados: sem eles a 006/010 recusa criar execucao. */
    static void unlimited(UUID org, UUID provisionedByUser) {
        for (int code = 1; code <= 5; code++) {
            DB.asSuperuser("INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, "
                    + "limit_mode, limit_value, provisioned_by_user_id) VALUES ('" + org + "', " + code + ", 1, now(), 2, NULL, '" + provisionedByUser + "')");
        }
    }

    static UUID automation(UUID org, UUID workspace, UUID id, String name, UUID createdBy) {
        UUID a = id == null ? UUID.randomUUID() : id;
        try (var c = DB.connectAsSuperuser(); var ps = c.prepareStatement("INSERT INTO automations (automation_id, organization_id, workspace_id, name, "
                + "description, status, created_by_membership_id) VALUES (?, ?, ?, ?, NULL, 1, ?)")) {
            ps.setObject(1, a);
            ps.setObject(2, org);
            ps.setObject(3, workspace);
            ps.setString(4, name);
            ps.setObject(5, createdBy);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("automacao da massa falhou", e);
        }
        return a;
    }

    /** Versao (numero alocado pela trigger da 005); publicada se {@code publish}. */
    static UUID version(UUID org, UUID workspace, UUID automation, boolean publish, UUID by) {
        UUID v = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, "
                + "created_by_membership_id) VALUES ('" + v + "', '" + org + "', '" + workspace + "', '" + automation + "', 1, " + COMMAND + ", '" + by + "')");
        if (publish) {
            DB.asSuperuser("UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '" + by
                    + "' WHERE automation_version_id = '" + v + "'");
        }
        return v;
    }

    /** Execucao MANUAL nascida PENDING; {@code createdAt} e uma expressao SQL (ex.: '2026-01-01T00:00:00.000001Z' entre aspas). */
    static UUID execution(UUID org, UUID workspace, UUID automation, UUID version, String createdAt, UUID requester) {
        return execution(org, workspace, automation, version, createdAt, requester, null);
    }

    static UUID execution(UUID org, UUID workspace, UUID automation, UUID version, String createdAt, UUID requester, UUID retryOf) {
        return executionWithId(UUID.randomUUID(), org, workspace, automation, version, createdAt, requester, retryOf);
    }

    static UUID executionWithId(UUID e, UUID org, UUID workspace, UUID automation, UUID version, String createdAt, UUID requester, UUID retryOf) {
        DB.asSuperuser("INSERT INTO executions (execution_id, organization_id, workspace_id, automation_id, automation_version_id, request_key, "
                + "request_fingerprint, requested_by_membership_id, retry_of_execution_id, command_snapshot, status, origin, created_at) SELECT '" + e + "', '"
                + org + "', '" + workspace + "', '" + automation + "', v.automation_version_id, 'req-" + e + "', repeat('b', 64), '" + requester + "', "
                + (retryOf == null ? "NULL" : "'" + retryOf + "'") + ", v.command_json, 1, 1, " + (createdAt == null ? "now()" : createdAt)
                + " FROM automation_versions v WHERE v.automation_version_id = '" + version + "'");
        return e;
    }

    record Runtime(UUID worker, UUID agent, UUID workerAgent, UUID workerWorkspace) {
    }

    /** Worker + Agent + alocacao + autorizacao no workspace, proprios de uma execucao (um Worker so tem uma execucao ativa). */
    static Runtime runtime(UUID org, UUID workspace, UUID by) {
        Runtime r = new Runtime(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        DB.asSuperuser(
                "INSERT INTO workers (worker_id, organization_id, name, status) VALUES ('" + r.worker() + "', '" + org + "', 'W-" + r.worker() + "', 1)",
                "INSERT INTO agents (agent_id, organization_id, name, agent_version, status) VALUES ('" + r.agent() + "', '" + org + "', 'A-" + r.agent()
                        + "', '1.0.0', 2)",
                "INSERT INTO worker_agent_assignments (assignment_id, organization_id, worker_id, agent_id, status, assigned_by_membership_id) VALUES ('"
                        + r.workerAgent() + "', '" + org + "', '" + r.worker() + "', '" + r.agent() + "', 1, '" + by + "')",
                "INSERT INTO worker_workspace_assignments (assignment_id, organization_id, worker_id, workspace_id, status, authorized_by_membership_id) VALUES ('"
                        + r.workerWorkspace() + "', '" + org + "', '" + r.worker() + "', '" + workspace + "', 1, '" + by + "')");
        return r;
    }

    /** PENDING -> STARTING -> RUNNING, com started_at = reserved_at + 1 s. */
    static Runtime start(UUID org, UUID workspace, UUID execution, UUID by) {
        Runtime r = runtime(org, workspace, by);
        DB.asSuperuser(
                "UPDATE executions SET status = 2, worker_id = '" + r.worker() + "', worker_agent_assignment_id = '" + r.workerAgent()
                        + "', worker_workspace_assignment_id = '" + r.workerWorkspace() + "', reserved_at = clock_timestamp() WHERE execution_id = '" + execution + "'",
                "UPDATE executions SET status = 3, dispatched_at = reserved_at, started_at = reserved_at + interval '1 second' WHERE execution_id = '"
                        + execution + "'");
        return r;
    }

    /** RUNNING -> SUCCEEDED com finished_at = started_at + {@code duration} (intervalo SQL, ex.: '1.234567 seconds'). */
    static void succeed(UUID execution, String duration) {
        DB.asSuperuser("UPDATE executions SET status = 4, status_reason = 1, exit_code = 0, finished_at = started_at + interval '" + duration
                + "' WHERE execution_id = '" + execution + "'");
    }

    /** PENDING -> CANCELLED antes do despacho (motivo 4), sem started_at. */
    static void cancelBeforeDispatch(UUID execution, UUID by) {
        DB.asSuperuser("UPDATE executions SET status = 6, status_reason = 4, cancel_requested_at = clock_timestamp(), cancel_requested_by_membership_id = '"
                + by + "', finished_at = greatest(clock_timestamp(), created_at) WHERE execution_id = '" + execution + "'");
    }

    /** Evento da execucao alocada (agent da alocacao), com payload valido para o tipo (007). */
    static void event(UUID org, UUID workspace, UUID execution, Runtime r, long sequence, int type) {
        String payload = switch (type) {
            case 1 -> "{\"schema_version\":1,\"pid\":7}";
            case 2 -> "{\"schema_version\":1,\"exit_code\":0}";
            default -> throw new IllegalArgumentException("tipo de evento da massa: " + type);
        };
        DB.asSuperuser("BEGIN; SET LOCAL nekkora.organization_id = '" + org + "'; INSERT INTO execution_events (organization_id, workspace_id, execution_id, "
                + "agent_id, sequence, event_type, occurred_at, payload, fingerprint) VALUES ('" + org + "', '" + workspace + "', '" + execution + "', '"
                + r.agent() + "', " + sequence + ", " + type + ", now(), '" + payload + "', md5(random()::text) || md5(random()::text)); COMMIT");
    }

    /** Execucao AGENDADA pelo fluxo real da 009 (requested_by nulo, authorized_by preenchido). Devolve o id da execucao. */
    static UUID scheduledExecution(UUID org, UUID workspace, UUID automation, UUID version, UUID by) {
        UUID schedule = UUID.randomUUID();
        UUID occurrence = UUID.randomUUID();
        DB.asSuperuser(
                "INSERT INTO automation_schedules (schedule_id, organization_id, workspace_id, automation_id, name, status, created_by_membership_id) VALUES ('"
                        + schedule + "', '" + org + "', '" + workspace + "', '" + automation + "', 'Agenda de teste', 1, '" + by + "')",
                "INSERT INTO automation_schedule_revisions (organization_id, workspace_id, automation_id, schedule_id, revision_number, effective_from, "
                        + "automation_version_id, cron_expression, timezone, late_tolerance_seconds, authorized_by_membership_id, created_by_membership_id) VALUES ('"
                        + org + "', '" + workspace + "', '" + automation + "', '" + schedule + "', 1, now(), '" + version + "', '* * * * *', 'UTC', 3600, '" + by
                        + "', '" + by + "')",
                // scheduled_for (truncado ao segundo) precisa ser >= effective_from da revisao
                "SELECT pg_sleep(1.2)",
                "INSERT INTO automation_schedule_occurrences (occurrence_id, organization_id, workspace_id, schedule_id, revision_id, scheduled_for, expires_at, "
                        + "status) VALUES ('" + occurrence + "', '" + org + "', '" + workspace + "', '" + schedule + "', gen_random_uuid(), "
                        + "date_trunc('second', clock_timestamp()), clock_timestamp(), 1)",
                "SELECT * FROM process_schedule_occurrence('" + occurrence + "', true)");
        return UUID.fromString(AccessFixtures.value("SELECT execution_id FROM executions WHERE schedule_occurrence_id = '" + occurrence + "'"));
    }
}
