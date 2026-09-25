-- 09b - papel: nekkora_scheduler. Processar a ocorrencia fechada pela suspensao: devolve a decisao
-- existente (SKIPPED/SCHEDULE_SUSPENDED), sem criar execucao; materializar NOVA identidade com a
-- agenda suspensa: rejeitado pela trigger.
-- Observacao: materializar a MESMA identidade de uma ocorrencia existente devolve o id existente
-- mesmo com a agenda suspensa - a reconsulta idempotente (contrato, secao 4) vem antes da regra de
-- negocio da trigger. clock_timestamp() sem truncar garante aqui uma identidade nova.
\pset pager off
\set ON_ERROR_STOP on
SELECT current_user AS papel;
SELECT * FROM process_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', :'occ2', true);
\set ON_ERROR_STOP off
SELECT create_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', clock_timestamp());
