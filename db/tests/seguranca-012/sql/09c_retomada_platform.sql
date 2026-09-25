-- 09c - papel: nekkora_platform. Retoma o agendamento A; a ocorrencia suspensa continua SKIPPED.
\pset pager off
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT pg_temp.afirma(set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-000b-000000000001', 1::smallint, 'aaaaaaaa-0000-0000-0002-000000000001') = 1, 'agenda retomada');
SELECT pg_temp.afirma((SELECT status = 3 AND skip_reason = 3 FROM automation_schedule_occurrences WHERE occurrence_id = :'occ2'), 'retomada nao ressuscita a ocorrencia suspensa');
SELECT pg_temp.afirma((SELECT resumed_at IS NOT NULL FROM automation_schedules WHERE schedule_id = 'aaaaaaaa-0000-0000-000b-000000000001'), 'resumed_at atribuido');
COMMIT;
