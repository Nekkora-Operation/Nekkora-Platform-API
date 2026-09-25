-- 07 - papel: nekkora_ingestion (sucessor de nekkora_app). Uma conexao.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_ingestion', 'papel nekkora_ingestion');

\echo === G01 ingestao valida em A e em B, cada uma no proprio contexto; fn_ctx restaurado pela barreira
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT pg_temp.afirma(coalesce(current_setting('nekkora.fn_organization_id', true), '') = '', 'fn_ctx vazio antes');
INSERT INTO execution_events (event_id, organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, received_at, payload, fingerprint)
VALUES ('aaaaaaaa-0000-0000-00ee-000000000001', 'aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000a-000000000001',
        'aaaaaaaa-0000-0000-0005-000000000001', 1, 1, now(), '2000-01-01 00:00:00+00', '{"schema_version":1,"pid":4242}', repeat('a1', 32));
SELECT pg_temp.afirma(coalesce(current_setting('nekkora.fn_organization_id', true), '') = '', 'fn_ctx restaurado apos a barreira');
SELECT pg_temp.afirma((SELECT received_at FROM execution_events WHERE event_id = 'aaaaaaaa-0000-0000-00ee-000000000001') > '2026-01-01', 'received_at atribuido pelo servidor (valor do chamador ignorado)');
INSERT INTO logs (log_id, organization_id, workspace_id, agent_id, worker_agent_assignment_id, execution_id, channel, occurred_at, payload, fingerprint)
VALUES ('aaaaaaaa-0000-0000-00ff-000000000001', 'aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-0005-000000000001',
        'aaaaaaaa-0000-0000-0006-000000000001', 'aaaaaaaa-0000-0000-000a-000000000001', 2, now(), '{"schema_version":1,"message":"ola A"}', repeat('f1', 32));
SELECT pg_temp.afirma((SELECT count(*) FROM execution_events) = 1 AND (SELECT count(*) FROM logs) = 1, 'A ve 1 evento e 1 log');
COMMIT;
BEGIN;
SET LOCAL nekkora.organization_id = 'bbbbbbbb-0000-0000-0000-00000000000b';
INSERT INTO execution_events (event_id, organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
VALUES ('bbbbbbbb-0000-0000-00ee-000000000001', 'bbbbbbbb-0000-0000-0000-00000000000b', 'bbbbbbbb-0000-0000-0003-000000000001', 'bbbbbbbb-0000-0000-000a-000000000001',
        'bbbbbbbb-0000-0000-0005-000000000001', 1, 1, now(), '{"schema_version":1,"pid":5151}', repeat('b1', 32)),
       ('bbbbbbbb-0000-0000-00ee-000000000002', 'bbbbbbbb-0000-0000-0000-00000000000b', 'bbbbbbbb-0000-0000-0003-000000000001', 'bbbbbbbb-0000-0000-000a-000000000001',
        'bbbbbbbb-0000-0000-0005-000000000001', 2, 2, now(), '{"schema_version":1,"exit_code":0}', repeat('b2', 32));
SELECT pg_temp.afirma((SELECT count(*) FROM execution_events) = 2 AND NOT EXISTS (SELECT 1 FROM execution_events WHERE organization_id <> 'bbbbbbbb-0000-0000-0000-00000000000b'), 'B ve so os 2 eventos de B');
COMMIT;

\echo === G02 sem contexto: rejeitado (a trigger de origem nao enxerga a execucao sob RLS)
SELECT pg_temp.espera_erro($q$INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
  VALUES ('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000a-000000000001',
          'aaaaaaaa-0000-0000-0005-000000000001', 50, 1, now(), '{"schema_version":1,"pid":1}', 'x')$q$, 'P0001', 'execucao sem alocacao nao pode receber evento');

\echo === G03 contexto A, evento declarado de B (execucao de B): rejeitado
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT pg_temp.espera_erro($q$INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
  VALUES ('bbbbbbbb-0000-0000-0000-00000000000b', 'bbbbbbbb-0000-0000-0003-000000000001', 'bbbbbbbb-0000-0000-000a-000000000001',
          'bbbbbbbb-0000-0000-0005-000000000001', 51, 1, now(), '{"schema_version":1,"pid":1}', 'x')$q$, 'P0001', 'execucao sem alocacao nao pode receber evento');
ROLLBACK;

\echo === G04 barreira chamada direto com organizacao divergente do contexto / sem contexto: erro nomeado 42501
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT pg_temp.espera_erro($q$SELECT assign_received_at_and_lock_barrier('bbbbbbbb-0000-0000-0000-00000000000b', 5)$q$, '42501', 'contexto organizacional ausente ou divergente na ingestao');
ROLLBACK;
SELECT pg_temp.espera_erro($q$SELECT assign_received_at_and_lock_barrier('aaaaaaaa-0000-0000-0000-00000000000a', 5)$q$, '42501', 'contexto=<ausente>');

\echo === G05 contexto de funcao com valor anterior nao vazio: preservado apos erro e apos sucesso
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SET LOCAL nekkora.fn_organization_id = '11111111-2222-3333-4444-555555555555';
-- payload invalido: a barreira roda (trigger BEFORE) e a CHECK falha depois
SELECT pg_temp.espera_erro($q$INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
  VALUES ('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000a-000000000001',
          'aaaaaaaa-0000-0000-0005-000000000001', 60, 1, now(), '{"schema_version":1}', 'x')$q$, '23514', 'execution_events_check');
SELECT pg_temp.afirma(current_setting('nekkora.fn_organization_id', true) = '11111111-2222-3333-4444-555555555555', 'fn_ctx anterior preservado apos erro');
INSERT INTO execution_events (event_id, organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
VALUES ('aaaaaaaa-0000-0000-00ee-000000000002', 'aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000a-000000000001',
        'aaaaaaaa-0000-0000-0005-000000000001', 2, 2, now(), '{"schema_version":1,"exit_code":0}', repeat('a2', 32));
SELECT pg_temp.afirma(current_setting('nekkora.fn_organization_id', true) = '11111111-2222-3333-4444-555555555555', 'fn_ctx anterior (nao vazio) devolvido pela barreira apos sucesso');
COMMIT;

\echo === G06 operacoes nao concedidas a ingestao
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT pg_temp.espera_erro($q$UPDATE execution_events SET fingerprint = 'x'$q$, '42501', 'permission denied for table execution_events');
SELECT pg_temp.espera_erro($q$DELETE FROM logs$q$, '42501', 'permission denied for table logs');
SELECT pg_temp.espera_erro($q$TRUNCATE execution_events$q$, '42501', 'permission denied for table execution_events');
SELECT pg_temp.espera_erro($q$SELECT count(*) FROM organization_usage_daily$q$, '42501', 'permission denied for table organization_usage_daily');
SELECT pg_temp.espera_erro($q$UPDATE organization_usage_daily SET records_count = 999$q$, '42501', 'permission denied for table organization_usage_daily');
SELECT pg_temp.espera_erro($q$SELECT count(*) FROM workspaces$q$, '42501', 'permission denied for table workspaces');
ROLLBACK;
SELECT pg_temp.espera_erro($q$SELECT * FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, current_date)$q$, '42501', 'permission denied for function reconsolidate_usage_day');
SELECT pg_temp.espera_erro($q$SELECT close_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, current_date - 1)$q$, '42501', 'permission denied for function close_usage_day');
SELECT 'ingestao: todas as assercoes passaram' AS resultado;
