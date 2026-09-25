-- 08 - papel: nekkora_scheduler. Materializacao e processamento pelos pontos de entrada autorizados.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_scheduler', 'papel nekkora_scheduler');

\echo === S01 sem acesso direto: tabelas, implementacao interna (2 parametros), suspender/retomar
SELECT pg_temp.espera_erro($q$SELECT count(*) FROM automation_schedule_occurrences$q$, '42501', 'permission denied for table automation_schedule_occurrences');
SELECT pg_temp.espera_erro($q$SELECT count(*) FROM executions$q$, '42501', 'permission denied for table executions');
SELECT pg_temp.espera_erro($q$SELECT * FROM process_schedule_occurrence(gen_random_uuid(), true)$q$, '42501', 'permission denied for function process_schedule_occurrence');
SELECT pg_temp.espera_erro($q$SELECT set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-000b-000000000001', 2::smallint, 'aaaaaaaa-0000-0000-0002-000000000001')$q$,
  '42501', 'permission denied for function set_schedule_status');

\echo === S02 materializacao idempotente (sequencial): mesmo (schedule, scheduled_for) -> mesmo occurrence_id
-- clock_timestamp() sem truncar: truncado ao segundo, pode cair antes do resumed_at da retomada do 06/I12
SELECT clock_timestamp() AS ts1 \gset
SELECT create_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', :'ts1') AS occ1 \gset
SELECT pg_temp.afirma(create_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', :'ts1') = :'occ1'::uuid,
  'segunda chamada devolve o mesmo occurrence_id');
SELECT pg_temp.afirma(coalesce(current_setting('nekkora.fn_organization_id', true), '') = '', 'fn_ctx restaurado');

\echo === S03 referencias cruzadas: rejeitadas com erro nomeado
SELECT pg_temp.espera_erro(format($q$SELECT create_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', 'bbbbbbbb-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', %L)$q$, :'ts1'),
  '42501', 'agendamento nao pertence ao workspace informado');
SELECT pg_temp.espera_erro(format($q$SELECT create_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'bbbbbbbb-0000-0000-000b-000000000001', %L)$q$, :'ts1'),
  '42501', 'agendamento inexistente ou de outra organizacao');
SELECT pg_temp.espera_erro(format($q$SELECT create_schedule_occurrence('bbbbbbbb-0000-0000-0000-00000000000b', 'bbbbbbbb-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', %L)$q$, :'ts1'),
  '42501', 'agendamento inexistente ou de outra organizacao');
SELECT pg_temp.espera_erro(format($q$SELECT create_schedule_occurrence(NULL, 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', %L)$q$, :'ts1'),
  '22004', 'materializacao exige');
\echo === S04 regra de negocio da trigger inalterada: materializacao antecipada rejeitada
SELECT pg_temp.espera_erro($q$SELECT create_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', now() + interval '1 hour')$q$,
  'P0001', 'materializacao antecipada');

\echo === S05 processamento pelo ponto de entrada de 3 parametros; reprocessamento devolve a mesma decisao
SELECT out_status AS s1, out_skip_reason IS NULL AS sem_motivo1, out_execution_id AS e1
FROM process_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', :'occ1', true) \gset
SELECT pg_temp.afirma(:s1 = 2 AND :'sem_motivo1'::boolean AND :'e1' <> '', 'processamento: EXECUTION_CREATED com execucao');
SELECT pg_temp.afirma((SELECT out_status = 2 AND out_execution_id = :'e1'::uuid FROM process_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', :'occ1', true)),
  'reprocessamento: mesma decisao e mesma execucao');
SELECT pg_temp.afirma(coalesce(current_setting('nekkora.fn_organization_id', true), '') = '', 'fn_ctx restaurado');

\echo === S06 organizacao divergente da ocorrencia / ocorrencia inexistente: rejeitado
SELECT pg_temp.espera_erro(format($q$SELECT * FROM process_schedule_occurrence('bbbbbbbb-0000-0000-0000-00000000000b', %L, true)$q$, :'occ1'),
  '42501', 'ocorrencia inexistente ou de outra organizacao');
SELECT pg_temp.espera_erro($q$SELECT * FROM process_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', gen_random_uuid(), true)$q$,
  '42501', 'ocorrencia inexistente ou de outra organizacao');
SELECT 'scheduler: todas as assercoes passaram' AS resultado;
