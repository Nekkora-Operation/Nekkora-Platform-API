-- Preparacao T08/concorrencia (1/2) - papel: nekkora. Caminho normal, triggers ativas.
\pset pager off
\set ON_ERROR_STOP on
SELECT current_user AS papel;
INSERT INTO worker_agent_assignments (assignment_id, organization_id, worker_id, agent_id, status, assigned_by_membership_id)
VALUES ('cccccccc-cccc-cccc-cccc-ccccccccccc5','11111111-1111-1111-1111-111111111111','cccccccc-cccc-cccc-cccc-ccccccccccc1','cccccccc-cccc-cccc-cccc-ccccccccccc2',1,'44444444-4444-4444-4444-444444444444');
INSERT INTO executions (execution_id, organization_id, workspace_id, automation_id, automation_version_id, request_key, request_fingerprint, requested_by_membership_id, command_snapshot, status, origin)
SELECT 'dddddddd-0000-0000-0000-000000000003','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','55555555-5555-5555-5555-555555555555','66666666-6666-6666-6666-666666666601','req-hist',repeat('c',64),'44444444-4444-4444-4444-444444444444', command_json, 1, 1
FROM automation_versions WHERE automation_version_id = '66666666-6666-6666-6666-666666666601';
UPDATE executions SET status = 2, worker_id = 'cccccccc-cccc-cccc-cccc-ccccccccccc1', worker_agent_assignment_id = 'cccccccc-cccc-cccc-cccc-ccccccccccc5', worker_workspace_assignment_id = 'cccccccc-cccc-cccc-cccc-ccccccccccc4', reserved_at = clock_timestamp()
WHERE execution_id = 'dddddddd-0000-0000-0000-000000000003';
UPDATE executions SET status = 3, dispatched_at = clock_timestamp(), started_at = clock_timestamp(), pid = 5151 WHERE execution_id = 'dddddddd-0000-0000-0000-000000000003';
UPDATE executions SET status = 4, status_reason = 1, exit_code = 0, finished_at = clock_timestamp() WHERE execution_id = 'dddddddd-0000-0000-0000-000000000003';
SELECT execution_id, status, worker_agent_assignment_id FROM executions WHERE execution_id = 'dddddddd-0000-0000-0000-000000000003';
