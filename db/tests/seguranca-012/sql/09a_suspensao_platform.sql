-- 09a - papel: nekkora_platform. Suspende o agendamento A com uma ocorrencia PENDING (criada pelo
-- scheduler logo antes, id recebido em :occ2). close_pending_occurrences_on_suspend roda como
-- nekkora_scheduler_owner, dentro de set_schedule_status, sob RLS com o contexto de funcao.
\pset pager off
\set ON_ERROR_STOP on
SELECT current_user AS papel;
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT status AS ocorrencia_antes FROM automation_schedule_occurrences WHERE occurrence_id = :'occ2';
SELECT set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-000b-000000000001', 2::smallint, 'aaaaaaaa-0000-0000-0002-000000000001') AS agenda;
SELECT status AS ocorrencia_depois, skip_reason, execution_id FROM automation_schedule_occurrences WHERE occurrence_id = :'occ2';
COMMIT;
