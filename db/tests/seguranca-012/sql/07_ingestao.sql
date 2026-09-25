-- 07 - papel: nekkora_ingestion (sucessor de nekkora_app). Uma conexao.
\pset pager off
SELECT current_user AS papel, pg_backend_pid() AS pid;

\echo === G01 ingestao valida em A e em B, cada uma no proprio contexto
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT current_setting('nekkora.fn_organization_id', true) AS fn_ctx_antes;
INSERT INTO execution_events (event_id, organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
VALUES ('aaaaaaaa-0000-0000-00ee-000000000001', 'aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000a-000000000001',
        'aaaaaaaa-0000-0000-0005-000000000001', 1, 1, now(), '{"schema_version":1,"pid":4242}', repeat('a1', 32));
SELECT current_setting('nekkora.fn_organization_id', true) AS fn_ctx_apos_barreira_restaurado;
INSERT INTO logs (log_id, organization_id, workspace_id, agent_id, worker_agent_assignment_id, execution_id, channel, occurred_at, payload, fingerprint)
VALUES ('aaaaaaaa-0000-0000-00ff-000000000001', 'aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-0005-000000000001',
        'aaaaaaaa-0000-0000-0006-000000000001', 'aaaaaaaa-0000-0000-000a-000000000001', 2, now(), '{"schema_version":1,"message":"ola A"}', repeat('f1', 32));
SELECT count(*) AS eventos_visiveis_a, (SELECT count(*) FROM logs) AS logs_visiveis_a FROM execution_events;
COMMIT;
BEGIN;
SET LOCAL nekkora.organization_id = 'bbbbbbbb-0000-0000-0000-00000000000b';
INSERT INTO execution_events (event_id, organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
VALUES ('bbbbbbbb-0000-0000-00ee-000000000001', 'bbbbbbbb-0000-0000-0000-00000000000b', 'bbbbbbbb-0000-0000-0003-000000000001', 'bbbbbbbb-0000-0000-000a-000000000001',
        'bbbbbbbb-0000-0000-0005-000000000001', 1, 1, now(), '{"schema_version":1,"pid":5151}', repeat('b1', 32)),
       ('bbbbbbbb-0000-0000-00ee-000000000002', 'bbbbbbbb-0000-0000-0000-00000000000b', 'bbbbbbbb-0000-0000-0003-000000000001', 'bbbbbbbb-0000-0000-000a-000000000001',
        'bbbbbbbb-0000-0000-0005-000000000001', 2, 2, now(), '{"schema_version":1,"exit_code":0}', repeat('b2', 32));
SELECT count(*) AS eventos_visiveis_b, min(organization_id::text) AS org_vista FROM execution_events;
COMMIT;
\set ON_ERROR_STOP off

\echo === G02 sem contexto: rejeitado (a trigger de origem nao enxerga a execucao sob RLS)
INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
VALUES ('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000a-000000000001',
        'aaaaaaaa-0000-0000-0005-000000000001', 50, 1, now(), '{"schema_version":1,"pid":1}', 'x');

\echo === G03 contexto A, evento declarado de B (execucao de B): rejeitado
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
VALUES ('bbbbbbbb-0000-0000-0000-00000000000b', 'bbbbbbbb-0000-0000-0003-000000000001', 'bbbbbbbb-0000-0000-000a-000000000001',
        'bbbbbbbb-0000-0000-0005-000000000001', 51, 1, now(), '{"schema_version":1,"pid":1}', 'x');
ROLLBACK;

\echo === G04 barreira chamada direto com organizacao divergente do contexto: erro nomeado (42501)
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT assign_received_at_and_lock_barrier('bbbbbbbb-0000-0000-0000-00000000000b', 5);
ROLLBACK;
SELECT assign_received_at_and_lock_barrier('aaaaaaaa-0000-0000-0000-00000000000a', 5);

\echo === G05 restauracao apos erro dentro da barreira: fn_ctx volta ao valor anterior
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SET LOCAL nekkora.fn_organization_id = '11111111-2222-3333-4444-555555555555';
SAVEPOINT s1;
-- evento com payload invalido: a barreira roda (trigger BEFORE) e a CHECK falha depois; o erro aborta o savepoint
INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
VALUES ('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000a-000000000001',
        'aaaaaaaa-0000-0000-0005-000000000001', 60, 1, now(), '{"schema_version":1}', 'x');
ROLLBACK TO SAVEPOINT s1;
SELECT current_setting('nekkora.fn_organization_id', true) AS fn_ctx_apos_erro;
\set ON_ERROR_STOP on
-- caminho de sucesso com valor anterior nao vazio: a barreira salva e devolve exatamente esse valor
INSERT INTO execution_events (event_id, organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
VALUES ('aaaaaaaa-0000-0000-00ee-000000000002', 'aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000a-000000000001',
        'aaaaaaaa-0000-0000-0005-000000000001', 2, 2, now(), '{"schema_version":1,"exit_code":0}', repeat('a2', 32));
SELECT current_setting('nekkora.fn_organization_id', true) AS fn_ctx_apos_sucesso;
COMMIT;
\set ON_ERROR_STOP off

\echo === G06 operacoes nao concedidas a ingestao
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
UPDATE execution_events SET fingerprint = 'x';
ROLLBACK;
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
DELETE FROM logs;
ROLLBACK;
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
TRUNCATE execution_events;
ROLLBACK;
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT count(*) FROM organization_usage_daily;
ROLLBACK;
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
UPDATE organization_usage_daily SET records_count = 999;
ROLLBACK;
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT count(*) FROM workspaces;
ROLLBACK;
SELECT * FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, current_date);
SELECT close_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, current_date - 1);
