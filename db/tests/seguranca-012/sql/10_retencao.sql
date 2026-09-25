-- 10 - papel: nekkora_retention_executor. Uma conexao.
-- Hoje (UTC) ha eventos de A e de B; nenhum dia anterior tem registros neste ponto da suite.
\pset pager off
SELECT current_user AS papel, pg_backend_pid() AS pid, (clock_timestamp() AT TIME ZONE 'UTC')::date AS hoje_utc;

\echo === R01 reconsolidacao sob RLS conta so a organizacao do parametro (B tem eventos no mesmo dia)
\set ON_ERROR_STOP on
SELECT 'A' AS org, * FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, (clock_timestamp() AT TIME ZONE 'UTC')::date);
SELECT 'B' AS org, * FROM reconsolidate_usage_day('bbbbbbbb-0000-0000-0000-00000000000b', 5, (clock_timestamp() AT TIME ZONE 'UTC')::date);
SELECT 'A logs' AS org, * FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 4, (clock_timestamp() AT TIME ZONE 'UTC')::date);

\echo === R02 guard + contexto de funcao na mesma transacao
BEGIN;
SELECT out_records_count FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, (clock_timestamp() AT TIME ZONE 'UTC')::date);
SELECT current_setting('nekkora.fn_organization_id', true) AS fn_ctx_restaurado, current_setting('nekkora.retention_txn_category', true) AS guard_intacto;
-- mesmo par (A, eventos), outra funcao: permitido - o guard ainda ve a chamada anterior, apesar do fn_ctx restaurado
SELECT close_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, '2026-09-01');
SELECT current_setting('nekkora.fn_organization_id', true) AS fn_ctx_restaurado, current_setting('nekkora.retention_txn_category', true) AS guard_intacto;
-- troca de categoria na mesma transacao: rejeitada
\set ON_ERROR_STOP off
SAVEPOINT s1;
SELECT * FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 4, (clock_timestamp() AT TIME ZONE 'UTC')::date);
ROLLBACK TO SAVEPOINT s1;
-- troca de organizacao na mesma transacao: rejeitada
SAVEPOINT s2;
SELECT * FROM reconsolidate_usage_day('bbbbbbbb-0000-0000-0000-00000000000b', 5, (clock_timestamp() AT TIME ZONE 'UTC')::date);
ROLLBACK TO SAVEPOINT s2;
COMMIT;
\echo === R03 reset: mesma conexao, transacao seguinte com outro par
\set ON_ERROR_STOP on
SELECT pg_backend_pid() AS mesmo_pid, current_setting('nekkora.retention_txn_category', true) AS guard_fora_de_txn;
BEGIN;
SELECT out_records_count FROM reconsolidate_usage_day('bbbbbbbb-0000-0000-0000-00000000000b', 4, (clock_timestamp() AT TIME ZONE 'UTC')::date);
COMMIT;

\echo === R04 contexto de funcao: valor anterior nao vazio preservado no sucesso e no erro (salvar/restaurar por funcao)
BEGIN;
SET LOCAL nekkora.fn_organization_id = '11111111-2222-3333-4444-555555555555';
SELECT close_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 4, '2026-09-01');
SELECT current_setting('nekkora.fn_organization_id', true) AS fn_ctx_apos_sucesso;
\set ON_ERROR_STOP off
SAVEPOINT s3;
SELECT close_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 4, '2026-09-01');   -- ja fechado: erro
ROLLBACK TO SAVEPOINT s3;
SELECT current_setting('nekkora.fn_organization_id', true) AS fn_ctx_apos_erro;
COMMIT;

\echo === R05 regras da reconsolidacao
SELECT * FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, (clock_timestamp() AT TIME ZONE 'UTC')::date + 1);   -- futuro
SELECT * FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, '2026-09-01');                                         -- fechado
SELECT * FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 3, (clock_timestamp() AT TIME ZONE 'UTC')::date);        -- recurso invalido
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT * FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, (clock_timestamp() AT TIME ZONE 'UTC')::date);
ROLLBACK;
SELECT * FROM reconsolidate_usage_day(NULL, 5, (clock_timestamp() AT TIME ZONE 'UTC')::date);

\echo === R06 nao concedido ao executor
SELECT purge_expired_records('aaaaaaaa-0000-0000-0000-00000000000a', 1, '99999999-0000-0000-0000-0000000000aa', 10);
SELECT count(*) FROM organization_usage_daily;
SELECT count(*) FROM execution_events;
SELECT assign_received_at_and_lock_barrier('aaaaaaaa-0000-0000-0000-00000000000a', 5);
SELECT provision_resource_limit('aaaaaaaa-0000-0000-0000-00000000000a', 1, 2::smallint, NULL, '99999999-0000-0000-0000-0000000000aa');
