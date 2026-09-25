-- T05 (parte 1) - papel: nekkora (administrativo; nenhum papel restrito da baseline tem privilegio em 009).
-- Agenda, revisao, ocorrencia 1 processada e reprocessada. A suspensao fica em 09_t05b_suspensao.sql.
\pset pager off
SELECT current_user AS papel;
INSERT INTO automation_schedules (schedule_id, organization_id, workspace_id, automation_id, name, status, created_by_membership_id)
VALUES ('abababab-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','55555555-5555-5555-5555-555555555555','Agenda T05',1,'44444444-4444-4444-4444-444444444444');
-- revision_number/effective_from sao sobrescritos pela trigger de criacao
INSERT INTO automation_schedule_revisions (organization_id, workspace_id, automation_id, schedule_id, revision_number, effective_from, automation_version_id, cron_expression, timezone, late_tolerance_seconds, authorized_by_membership_id, created_by_membership_id)
VALUES ('11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','55555555-5555-5555-5555-555555555555','abababab-0000-0000-0000-000000000001', 1, now(), '66666666-6666-6666-6666-666666666601','* * * * *','America/Sao_Paulo', 3600, '44444444-4444-4444-4444-444444444444','44444444-4444-4444-4444-444444444444');
-- garante scheduled_for (truncado ao segundo) >= effective_from da revisao
SELECT pg_sleep(2);

\echo === ocorrencia 1: materializa e processa
-- revision_id/expires_at sao calculados pela trigger de materializacao
INSERT INTO automation_schedule_occurrences (occurrence_id, organization_id, workspace_id, schedule_id, revision_id, scheduled_for, expires_at, status)
VALUES ('acacacac-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','abababab-0000-0000-0000-000000000001', gen_random_uuid(), date_trunc('second', clock_timestamp()), clock_timestamp(), 1);
SELECT * FROM process_schedule_occurrence('acacacac-0000-0000-0000-000000000001', true);
\echo === reprocessamento: deve devolver a mesma decisao, sem nova execucao
SELECT * FROM process_schedule_occurrence('acacacac-0000-0000-0000-000000000001', true);
SELECT o.status, o.skip_reason, o.execution_id, e.origin, e.status AS exec_status, e.authorized_by_membership_id,
       (SELECT count(*) FROM executions WHERE schedule_occurrence_id = o.occurrence_id) AS execucoes_da_ocorrencia
FROM automation_schedule_occurrences o JOIN executions e ON e.execution_id = o.execution_id
WHERE o.occurrence_id = 'acacacac-0000-0000-0000-000000000001';
