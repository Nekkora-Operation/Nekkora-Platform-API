-- T05 (parte 2) - papel: nekkora. Ocorrencia pendente + suspensao. A pausa separa scheduled_for da
-- ocorrencia 1 (UNIQUE (schedule_id, scheduled_for) - na execucao original, a ausencia dela causou erro de fixture).
\pset pager off
SELECT current_user AS papel;
SELECT status, resumed_at FROM automation_schedules WHERE schedule_id = 'abababab-0000-0000-0000-000000000001';
SELECT pg_sleep(1.2);
\echo === ocorrencia 2 fica PENDING
INSERT INTO automation_schedule_occurrences (occurrence_id, organization_id, workspace_id, schedule_id, revision_id, scheduled_for, expires_at, status)
VALUES ('acacacac-0000-0000-0000-000000000002','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','abababab-0000-0000-0000-000000000001', gen_random_uuid(), date_trunc('second', clock_timestamp()), clock_timestamp(), 1);
SELECT occurrence_id, status FROM automation_schedule_occurrences WHERE occurrence_id = 'acacacac-0000-0000-0000-000000000002';
\echo === suspensao com a ocorrencia pendente
UPDATE automation_schedules SET status = 2, suspended_at = clock_timestamp(), suspended_by_membership_id = '44444444-4444-4444-4444-444444444444'
WHERE schedule_id = 'abababab-0000-0000-0000-000000000001';
SELECT occurrence_id, status, skip_reason, execution_id, decided_at IS NOT NULL AS decidida FROM automation_schedule_occurrences WHERE occurrence_id = 'acacacac-0000-0000-0000-000000000002';
\echo === processar a pendente apos suspensao: devolve SKIPPED/SCHEDULE_SUSPENDED, sem execucao
SELECT * FROM process_schedule_occurrence('acacacac-0000-0000-0000-000000000002', true);
\echo === retomada nao ressuscita a ocorrencia suspensa
UPDATE automation_schedules SET status = 1, suspended_at = NULL, suspended_by_membership_id = NULL WHERE schedule_id = 'abababab-0000-0000-0000-000000000001';
SELECT * FROM process_schedule_occurrence('acacacac-0000-0000-0000-000000000002', true);
\echo === ocorrencia decidida e imutavel
UPDATE automation_schedule_occurrences SET status = 1, skip_reason = NULL, decided_at = NULL WHERE occurrence_id = 'acacacac-0000-0000-0000-000000000002';
SELECT count(*) AS execucoes_agendadas, count(DISTINCT schedule_occurrence_id) AS ocorrencias_distintas FROM executions WHERE origin = 2;
