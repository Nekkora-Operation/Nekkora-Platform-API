\pset pager off
SELECT current_user AS papel;
UPDATE executions SET status = 4, status_reason = 1, exit_code = 0, finished_at = clock_timestamp()
WHERE execution_id = 'dddddddd-0000-0000-0000-000000000001';
SELECT status, status_reason, exit_code, finished_at >= started_at AS ordem_ok FROM executions WHERE execution_id = 'dddddddd-0000-0000-0000-000000000001';
-- controles: terminal congelado; transicao invalida em execucao nova
UPDATE executions SET status = 3 WHERE execution_id = 'dddddddd-0000-0000-0000-000000000001';
UPDATE executions SET error_summary = 'x' WHERE execution_id = 'dddddddd-0000-0000-0000-000000000001';
INSERT INTO executions (execution_id, organization_id, workspace_id, automation_id, automation_version_id, request_key, request_fingerprint, requested_by_membership_id, command_snapshot, status, origin)
SELECT 'dddddddd-0000-0000-0000-000000000002','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','55555555-5555-5555-5555-555555555555','66666666-6666-6666-6666-666666666601','req-t04-ctl',repeat('b',64),'44444444-4444-4444-4444-444444444444', command_json, 1, 1
FROM automation_versions WHERE automation_version_id = '66666666-6666-6666-6666-666666666601';
-- PENDING->RUNNING invalido
UPDATE executions SET status = 3 WHERE execution_id = 'dddddddd-0000-0000-0000-000000000002';
-- cancela antes do despacho (PENDING->CANCELLED valido)
UPDATE executions SET status = 6, status_reason = 4, finished_at = clock_timestamp() WHERE execution_id = 'dddddddd-0000-0000-0000-000000000002';
-- encerra a associacao Worker-Agent (execucao ja terminal, entao nao ha bloqueio)
UPDATE worker_agent_assignments SET status = 2, ended_at = clock_timestamp(), ended_by_membership_id = '44444444-4444-4444-4444-444444444444'
WHERE assignment_id = 'cccccccc-cccc-cccc-cccc-ccccccccccc3';
SELECT assignment_id, status, ended_at IS NOT NULL AS encerrada FROM worker_agent_assignments;
SELECT execution_id, status, status_reason FROM executions ORDER BY 1;
