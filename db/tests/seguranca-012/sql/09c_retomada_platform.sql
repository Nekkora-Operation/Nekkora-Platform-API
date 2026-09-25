-- 09c - papel: nekkora_platform. Retoma o agendamento A; a ocorrencia suspensa continua SKIPPED.
\pset pager off
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-000b-000000000001', 1::smallint, 'aaaaaaaa-0000-0000-0002-000000000001') AS agenda;
SELECT status AS ocorrencia_apos_retomada, skip_reason FROM automation_schedule_occurrences WHERE occurrence_id = :'occ2';
SELECT resumed_at IS NOT NULL AS resumed_at_atribuido FROM automation_schedules WHERE schedule_id = 'aaaaaaaa-0000-0000-000b-000000000001';
COMMIT;
