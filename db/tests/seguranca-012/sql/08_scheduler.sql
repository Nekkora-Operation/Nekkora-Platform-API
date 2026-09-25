-- 08 - papel: nekkora_scheduler. Materializacao e processamento pelos pontos de entrada autorizados.
\pset pager off
SELECT current_user AS papel;

\echo === S01 sem acesso direto: tabelas, implementacao interna (2 parametros), suspender/retomar
SELECT count(*) FROM automation_schedule_occurrences;
SELECT count(*) FROM executions;
SELECT * FROM process_schedule_occurrence(gen_random_uuid(), true);
SELECT set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-000b-000000000001', 2::smallint, 'aaaaaaaa-0000-0000-0002-000000000001');

\echo === S02 materializacao idempotente (sequencial): mesmo (schedule, scheduled_for) -> mesmo occurrence_id
-- passos positivos: qualquer erro aqui aborta a suite (nao pode virar "resultado" silencioso)
\set ON_ERROR_STOP on
-- clock_timestamp() sem truncar: truncado ao segundo, pode cair antes do resumed_at da retomada do 06/I12
SELECT clock_timestamp() AS ts1 \gset
SELECT create_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', :'ts1') AS occ1 \gset
SELECT :'occ1' AS occ1,
       create_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', :'ts1') = :'occ1'::uuid AS segunda_chamada_mesmo_id,
       current_setting('nekkora.fn_organization_id', true) AS fn_ctx_apos;
\set ON_ERROR_STOP off

\echo === S03 referencias cruzadas: rejeitadas com erro nomeado
SELECT create_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', 'bbbbbbbb-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', :'ts1');
SELECT create_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'bbbbbbbb-0000-0000-000b-000000000001', :'ts1');
SELECT create_schedule_occurrence('bbbbbbbb-0000-0000-0000-00000000000b', 'bbbbbbbb-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', :'ts1');
SELECT create_schedule_occurrence(NULL, 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', :'ts1');
\echo === S04 regra de negocio da trigger inalterada: materializacao antecipada rejeitada
SELECT create_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', now() + interval '1 hour');

\echo === S05 processamento pelo ponto de entrada de 3 parametros
\set ON_ERROR_STOP on
SELECT * FROM process_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', :'occ1', true);
\echo === reprocessamento devolve a mesma decisao
SELECT * FROM process_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', :'occ1', true);
SELECT current_setting('nekkora.fn_organization_id', true) AS fn_ctx_apos;
\set ON_ERROR_STOP off
\echo === S06 organizacao divergente da ocorrencia / ocorrencia inexistente: rejeitado
SELECT * FROM process_schedule_occurrence('bbbbbbbb-0000-0000-0000-00000000000b', :'occ1', true);
SELECT * FROM process_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', gen_random_uuid(), true);
