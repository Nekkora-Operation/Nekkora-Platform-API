-- 10 - papel: nekkora_retention_executor. Uma conexao.
-- Neste ponto da suite: hoje (UTC) ha eventos de A (2) e de B (2) e 1 log de A; nenhum dia anterior tem registros.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_retention_executor', 'papel nekkora_retention_executor');
SELECT pg_backend_pid() AS pid \gset
SELECT (clock_timestamp() AT TIME ZONE 'UTC')::date AS hoje \gset

\echo === R01 reconsolidacao sob RLS conta so a organizacao do parametro (B tem eventos no mesmo dia)
SELECT pg_temp.afirma((SELECT out_records_count = 2 AND out_bytes_count = 71 FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, :'hoje')), 'A eventos hoje = 2 / 71 bytes');
SELECT pg_temp.afirma((SELECT out_records_count = 2 AND out_bytes_count = 71 FROM reconsolidate_usage_day('bbbbbbbb-0000-0000-0000-00000000000b', 5, :'hoje')), 'B eventos hoje = 2 / 71 bytes');
SELECT pg_temp.afirma((SELECT out_records_count = 1 AND out_bytes_count = 41 FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 4, :'hoje')), 'A logs hoje = 1 / 41 bytes');

\echo === R02 guard + contexto de funcao na mesma transacao (os dois GUCs sao independentes)
BEGIN;
SELECT pg_temp.afirma((SELECT out_records_count = 2 FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, :'hoje')), 'reconsolidacao A/eventos');
SELECT pg_temp.afirma(coalesce(current_setting('nekkora.fn_organization_id', true), '') = ''
                      AND current_setting('nekkora.retention_txn_category', true) = 'aaaaaaaa-0000-0000-0000-00000000000a:1', 'fn_ctx restaurado; guard intacto A:1');
-- mesmo par (A, eventos), outra funcao: permitido - o guard ainda ve a chamada anterior
SELECT close_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, '2026-09-01');
SELECT pg_temp.afirma(coalesce(current_setting('nekkora.fn_organization_id', true), '') = ''
                      AND current_setting('nekkora.retention_txn_category', true) = 'aaaaaaaa-0000-0000-0000-00000000000a:1', 'apos close_usage_day: fn_ctx restaurado; guard intacto');
SELECT pg_temp.espera_erro(format($q$SELECT * FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 4, %L)$q$, :'hoje'), 'P0001', 'tentativa atual=aaaaaaaa-0000-0000-0000-00000000000a:2');
SELECT pg_temp.espera_erro(format($q$SELECT * FROM reconsolidate_usage_day('bbbbbbbb-0000-0000-0000-00000000000b', 5, %L)$q$, :'hoje'), 'P0001', 'tentativa atual=bbbbbbbb-0000-0000-0000-00000000000b:1');
COMMIT;
\echo === R03 reset: mesma conexao, transacao seguinte com outro par
SELECT pg_temp.afirma(pg_backend_pid() = :pid AND coalesce(current_setting('nekkora.retention_txn_category', true), '') = '', 'mesma conexao; guard vazio fora de transacao');
BEGIN;
SELECT pg_temp.afirma((SELECT out_records_count = 0 FROM reconsolidate_usage_day('bbbbbbbb-0000-0000-0000-00000000000b', 4, :'hoje')), 'novo par (B, logs) aceito na transacao seguinte');
COMMIT;

\echo === R04 contexto de funcao: valor anterior nao vazio preservado no sucesso e no erro
BEGIN;
SET LOCAL nekkora.fn_organization_id = '11111111-2222-3333-4444-555555555555';
SELECT close_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 4, '2026-09-01');
SELECT pg_temp.afirma(current_setting('nekkora.fn_organization_id', true) = '11111111-2222-3333-4444-555555555555', 'valor anterior devolvido apos sucesso');
SELECT pg_temp.espera_erro($q$SELECT close_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 4, '2026-09-01')$q$, 'P0001', 'dia ja esta fechado');
SELECT pg_temp.afirma(current_setting('nekkora.fn_organization_id', true) = '11111111-2222-3333-4444-555555555555', 'valor anterior preservado apos erro');
COMMIT;

\echo === R05 regras da reconsolidacao
SELECT pg_temp.espera_erro(format($q$SELECT * FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, %L::date + 1)$q$, :'hoje'), 'P0001', 'nao processa dia futuro');
SELECT pg_temp.espera_erro($q$SELECT * FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, '2026-09-01')$q$, 'P0001', 'dia ja esta fechado, reconsolidacao nao permitida');
SELECT pg_temp.espera_erro(format($q$SELECT * FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 3, %L)$q$, :'hoje'), 'P0001', 'resource_code invalido');
SELECT pg_temp.espera_erro(format($q$SELECT * FROM reconsolidate_usage_day(NULL, 5, %L)$q$, :'hoje'), '22004', 'reconsolidacao exige');
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT pg_temp.espera_erro(format($q$SELECT * FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, %L)$q$, :'hoje'), 'P0001', 'exige transaction_isolation=read committed');
ROLLBACK;

\echo === R06 nao concedido ao executor
SELECT pg_temp.espera_erro($q$SELECT purge_expired_records('aaaaaaaa-0000-0000-0000-00000000000a', 1, '99999999-0000-0000-0000-0000000000aa', 10)$q$, '42501', 'permission denied for function purge_expired_records');
SELECT pg_temp.espera_erro($q$SELECT count(*) FROM organization_usage_daily$q$, '42501', 'permission denied for table organization_usage_daily');
SELECT pg_temp.espera_erro($q$SELECT count(*) FROM execution_events$q$, '42501', 'permission denied for table execution_events');
SELECT pg_temp.espera_erro($q$SELECT assign_received_at_and_lock_barrier('aaaaaaaa-0000-0000-0000-00000000000a', 5)$q$, '42501', 'permission denied for function assign_received_at_and_lock_barrier');
SELECT pg_temp.espera_erro($q$SELECT provision_resource_limit('aaaaaaaa-0000-0000-0000-00000000000a', 1, 2::smallint, NULL, '99999999-0000-0000-0000-0000000000aa')$q$, '42501', 'permission denied for function provision_resource_limit');
SELECT 'manutencao: todas as assercoes passaram' AS resultado;
