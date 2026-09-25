-- 06 - papel: nekkora_platform, UMA conexao do inicio ao fim (reutilizacao de conexao e parte do teste).
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
SELECT pg_backend_pid() AS pid \gset

\echo === I01 contexto A: so linhas de A; ids de B invisiveis
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT pg_temp.afirma((SELECT count(*) FROM workspaces) = 1 AND (SELECT count(*) FROM executions) = 1 AND (SELECT count(*) FROM users) = 1, 'A ve 1 workspace, 1 execucao, 1 usuario');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM workspaces WHERE workspace_id = 'bbbbbbbb-0000-0000-0003-000000000001'), 'workspace de B invisivel por id');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM organizations WHERE organization_id = 'bbbbbbbb-0000-0000-0000-00000000000b'), 'organizacao B invisivel por id');
\echo === I02 UPDATE em linha de B com contexto A: 0 linhas (USING filtra, sem erro)
WITH u AS (UPDATE workspaces SET name = 'invadido' WHERE workspace_id = 'bbbbbbbb-0000-0000-0003-000000000001' RETURNING 1)
SELECT pg_temp.afirma((SELECT count(*) FROM u) = 0, 'UPDATE em workspace de B: 0 linhas');
WITH u AS (UPDATE users SET display_name = 'invadido' WHERE organization_id = 'bbbbbbbb-0000-0000-0000-00000000000b' RETURNING 1)
SELECT pg_temp.afirma((SELECT count(*) FROM u) = 0, 'UPDATE em usuarios de B: 0 linhas');
WITH u AS (UPDATE user_credentials SET password_hash = 'invadido' WHERE user_id = 'bbbbbbbb-0000-0000-0001-000000000001' RETURNING 1)
SELECT pg_temp.afirma((SELECT count(*) FROM u) = 0, 'UPDATE na credencial de B (derivada): 0 linhas');
COMMIT;

\echo === I03/I04 escrita para B no contexto A: rejeitada por WITH CHECK
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT pg_temp.espera_erro($q$INSERT INTO workspaces (organization_id, name, status) VALUES ('bbbbbbbb-0000-0000-0000-00000000000b', 'WS forjado', 1)$q$,
  '42501', 'new row violates row-level security policy for table "workspaces"');
SELECT pg_temp.espera_erro($q$UPDATE workspaces SET organization_id = 'bbbbbbbb-0000-0000-0000-00000000000b' WHERE workspace_id = 'aaaaaaaa-0000-0000-0003-000000000001'$q$,
  '42501', 'new row violates row-level security policy for table "workspaces"');
ROLLBACK;

\echo === I05 sem contexto: nenhuma linha, nenhuma escrita
SELECT pg_temp.afirma(coalesce(current_setting('nekkora.organization_id', true), '') = '', 'sem contexto definido');
SELECT pg_temp.afirma((SELECT count(*) FROM workspaces) = 0 AND (SELECT count(*) FROM organizations) = 0 AND (SELECT count(*) FROM user_credentials) = 0, 'sem contexto: 0 linhas');
SELECT pg_temp.espera_erro($q$INSERT INTO workspaces (organization_id, name, status) VALUES ('aaaaaaaa-0000-0000-0000-00000000000a', 'sem contexto', 1)$q$,
  '42501', 'new row violates row-level security policy');
\echo === I06 contexto vazio
BEGIN;
SET LOCAL nekkora.organization_id = '';
SELECT pg_temp.afirma((SELECT count(*) FROM workspaces) = 0, 'contexto vazio: 0 linhas');
ROLLBACK;
\echo === I07 contexto malformado: erro de conversao
BEGIN;
SET LOCAL nekkora.organization_id = 'nao-e-um-uuid';
SELECT pg_temp.espera_erro($q$SELECT count(*) FROM workspaces$q$, '22P02', 'invalid input syntax for type uuid');
ROLLBACK;

\echo === I08 reutilizacao de conexao: SET LOCAL nao sobrevive ao fim da transacao
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT pg_temp.afirma((SELECT count(*) FROM workspaces) = 1, 'txn1 contexto A: 1 linha');
COMMIT;
SELECT pg_temp.afirma(pg_backend_pid() = :pid, 'mesma conexao');
SELECT pg_temp.afirma(coalesce(current_setting('nekkora.organization_id', true), '') = '' AND (SELECT count(*) FROM workspaces) = 0, 'txn2 apos COMMIT: contexto vazio, 0 linhas');
BEGIN;
SET LOCAL nekkora.organization_id = 'bbbbbbbb-0000-0000-0000-00000000000b';
SELECT pg_temp.afirma((SELECT count(*) FROM workspaces) = 1 AND (SELECT organization_id FROM workspaces) = 'bbbbbbbb-0000-0000-0000-00000000000b', 'txn3 contexto B: so B');
ROLLBACK;
SELECT pg_temp.afirma(coalesce(current_setting('nekkora.organization_id', true), '') = '' AND (SELECT count(*) FROM workspaces) = 0, 'txn4 apos ROLLBACK: contexto vazio, 0 linhas');

\echo === I09 uso indevido (informativo, mas afirmado): SET de sessao, sem LOCAL, vaza para a transacao seguinte
SET nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT pg_temp.afirma((SELECT count(*) FROM workspaces) = 1, 'SET de sessao vaza para a transacao seguinte (risco documentado)');
RESET nekkora.organization_id;
SELECT pg_temp.afirma((SELECT count(*) FROM workspaces) = 0, 'apos RESET: 0 linhas');

\echo === I10 falsificar nekkora.fn_organization_id nao afeta a politica do platform
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SET LOCAL nekkora.fn_organization_id = 'bbbbbbbb-0000-0000-0000-00000000000b';
SELECT pg_temp.afirma((SELECT count(*) FROM workspaces) = 1 AND (SELECT organization_id FROM workspaces) = 'aaaaaaaa-0000-0000-0000-00000000000a', 'fn_ctx=B ignorado: ve so A');
ROLLBACK;
BEGIN;
SET LOCAL nekkora.fn_organization_id = 'bbbbbbbb-0000-0000-0000-00000000000b';
SELECT pg_temp.afirma((SELECT count(*) FROM workspaces) = 0, 'so fn_ctx, sem contexto de requisicao: 0 linhas');
ROLLBACK;

\echo === I11 operacoes nao concedidas ao platform
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT pg_temp.espera_erro($q$INSERT INTO organizations (organization_id, name, status) VALUES ('aaaaaaaa-0000-0000-0000-00000000000a', 'x', 1)$q$, '42501', 'permission denied for table organizations');
SELECT pg_temp.espera_erro($q$UPDATE automation_schedules SET status = 2 WHERE schedule_id = 'aaaaaaaa-0000-0000-000b-000000000001'$q$, '42501', 'permission denied for table automation_schedules');
SELECT pg_temp.espera_erro($q$INSERT INTO automation_schedule_occurrences (organization_id, workspace_id, schedule_id, scheduled_for, status)
  VALUES ('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', now(), 1)$q$, '42501', 'permission denied for table automation_schedule_occurrences');
SELECT pg_temp.espera_erro($q$SELECT count(*) FROM organization_usage_daily$q$, '42501', 'permission denied for table organization_usage_daily');
SELECT pg_temp.espera_erro($q$DELETE FROM executions$q$, '42501', 'permission denied for table executions');
SELECT pg_temp.espera_erro($q$INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
  VALUES ('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000a-000000000001', 'aaaaaaaa-0000-0000-0005-000000000001', 99, 1, now(), '{"schema_version":1,"pid":1}', 'x')$q$,
  '42501', 'permission denied for table execution_events');
ROLLBACK;
SELECT pg_temp.espera_erro($q$SELECT create_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', now())$q$, '42501', 'permission denied for function create_schedule_occurrence');
SELECT pg_temp.espera_erro($q$SELECT * FROM process_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', gen_random_uuid(), true)$q$, '42501', 'permission denied for function process_schedule_occurrence');
SELECT pg_temp.espera_erro($q$SELECT * FROM process_schedule_occurrence(gen_random_uuid(), true)$q$, '42501', 'permission denied for function process_schedule_occurrence');
SELECT pg_temp.espera_erro($q$SELECT * FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, current_date)$q$, '42501', 'permission denied for function reconsolidate_usage_day');
SELECT pg_temp.espera_erro($q$SELECT assign_received_at_and_lock_barrier('aaaaaaaa-0000-0000-0000-00000000000a', 5)$q$, '42501', 'permission denied for function assign_received_at_and_lock_barrier');
SELECT pg_temp.espera_erro($q$SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'x', NULL, '99999999-0000-0000-0000-0000000000aa', NULL)$q$, '42501', 'permission denied for function provision_organization');
SELECT pg_temp.espera_erro($q$CREATE TABLE public.intrusa (id int)$q$, '42501', 'permission denied for schema public');

\echo === I12 set_schedule_status: contexto obrigatorio e coincidente, verificado antes de alterar a agenda
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT pg_temp.afirma(set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-000b-000000000001', 2::smallint, 'aaaaaaaa-0000-0000-0002-000000000001') = 2, 'suspenso');
SELECT pg_temp.afirma(set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-000b-000000000001', 2::smallint, 'aaaaaaaa-0000-0000-0002-000000000001') = 2, 'idempotente');
SELECT pg_temp.afirma(set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-000b-000000000001', 1::smallint, 'aaaaaaaa-0000-0000-0002-000000000001') = 1, 'retomado');
SELECT pg_temp.afirma(coalesce(current_setting('nekkora.fn_organization_id', true), '') = '', 'fn_organization_id restaurado apos retorno');
COMMIT;
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT pg_temp.espera_erro($q$SELECT set_schedule_status('bbbbbbbb-0000-0000-0000-00000000000b', 'bbbbbbbb-0000-0000-000b-000000000001', 2::smallint, 'bbbbbbbb-0000-0000-0002-000000000001')$q$,
  '42501', 'contexto organizacional ausente ou divergente');
SELECT pg_temp.espera_erro($q$SELECT set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', 'bbbbbbbb-0000-0000-000b-000000000001', 2::smallint, 'aaaaaaaa-0000-0000-0002-000000000001')$q$,
  '42501', 'agendamento inexistente ou de outra organizacao');
SELECT pg_temp.espera_erro($q$SELECT set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-000b-000000000001', 3::smallint, 'aaaaaaaa-0000-0000-0002-000000000001')$q$,
  '22023', 'status desejado invalido');
ROLLBACK;
SELECT pg_temp.espera_erro($q$SELECT set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-000b-000000000001', 2::smallint, 'aaaaaaaa-0000-0000-0002-000000000001')$q$,
  '42501', 'contexto=<ausente>');
BEGIN; SET LOCAL nekkora.organization_id = 'bbbbbbbb-0000-0000-0000-00000000000b';
SELECT pg_temp.afirma((SELECT status FROM automation_schedules) = 1 AND (SELECT suspended_at FROM automation_schedules) IS NULL, 'agenda de B intacta');
ROLLBACK;
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT pg_temp.afirma((SELECT status FROM automation_schedules) = 1, 'agenda de A ativa apos as tentativas rejeitadas (nenhuma alterou a agenda)');
ROLLBACK;

\echo === I13 objeto temporario sombreando tabela real nao desvia as triggers (search_path fixo, pg_temp por ultimo)
CREATE TEMP TABLE organizations (organization_id uuid, status smallint);
INSERT INTO pg_temp.organizations VALUES ('aaaaaaaa-0000-0000-0000-00000000000a', 3);
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
-- consulta nao qualificada DA PROPRIA SESSAO resolve para a temporaria (pg_temp vem antes, por padrao)
SELECT pg_temp.afirma((SELECT status FROM organizations) = 3, 'consulta nao qualificada da sessao le a temporaria (CLOSED)');
-- a trigger de criacao de execucao le "organizations" sem qualificar: com search_path fixo, le public (ACTIVE)
INSERT INTO executions (execution_id, organization_id, workspace_id, automation_id, automation_version_id, request_key, request_fingerprint, requested_by_membership_id, command_snapshot, status, origin)
SELECT 'aaaaaaaa-0000-0000-000a-0000000000f1', 'aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-0008-000000000001',
       automation_version_id, 'req-temp', repeat('b', 64), 'aaaaaaaa-0000-0000-0002-000000000001', command_json, 1, 1
FROM automation_versions WHERE automation_version_id = 'aaaaaaaa-0000-0000-0009-000000000001';
SELECT pg_temp.afirma(EXISTS (SELECT 1 FROM public.executions WHERE execution_id = 'aaaaaaaa-0000-0000-000a-0000000000f1'), 'execucao criada: a trigger nao foi desviada pela temporaria');
UPDATE executions SET status = 6, status_reason = 4, finished_at = clock_timestamp() WHERE execution_id = 'aaaaaaaa-0000-0000-000a-0000000000f1';
COMMIT;
DROP TABLE pg_temp.organizations;
SELECT 'platform: todas as assercoes passaram' AS resultado;
