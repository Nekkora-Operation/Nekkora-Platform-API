-- 09a - papel: nekkora_platform. Suspende o agendamento A com uma ocorrencia PENDING (criada pelo
-- scheduler logo antes, id recebido em :occ2). close_pending_occurrences_on_suspend roda como
-- nekkora_scheduler_owner, dentro de set_schedule_status, sob RLS com o contexto de funcao.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT pg_temp.afirma((SELECT status FROM automation_schedule_occurrences WHERE occurrence_id = :'occ2') = 1, 'ocorrencia pendente antes');
SELECT pg_temp.afirma(set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-000b-000000000001', 2::smallint, 'aaaaaaaa-0000-0000-0002-000000000001') = 2, 'agenda suspensa');
SELECT pg_temp.afirma((SELECT status = 3 AND skip_reason = 3 AND execution_id IS NULL FROM automation_schedule_occurrences WHERE occurrence_id = :'occ2'),
  'pendente fechada como SKIPPED/SCHEDULE_SUSPENDED, sem execucao');
COMMIT;
