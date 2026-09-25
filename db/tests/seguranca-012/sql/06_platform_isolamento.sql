-- 06 - papel: nekkora_platform, UMA conexao do inicio ao fim (reutilizacao de conexao e parte do teste).
\pset pager off
SELECT current_user AS papel, pg_backend_pid() AS pid;

\echo === I01 contexto A: so linhas de A; ids de B invisiveis
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT (SELECT count(*) FROM workspaces) AS ws, (SELECT count(*) FROM executions) AS exec, (SELECT count(*) FROM users) AS users,
       (SELECT count(*) FROM workspaces WHERE workspace_id = 'bbbbbbbb-0000-0000-0003-000000000001') AS ws_b_por_id,
       (SELECT count(*) FROM organizations WHERE organization_id = 'bbbbbbbb-0000-0000-0000-00000000000b') AS org_b_por_id;
\echo === I02 UPDATE em linha de B com contexto A: 0 linhas (USING filtra, sem erro)
UPDATE workspaces SET name = 'invadido' WHERE workspace_id = 'bbbbbbbb-0000-0000-0003-000000000001';
UPDATE users SET display_name = 'invadido' WHERE organization_id = 'bbbbbbbb-0000-0000-0000-00000000000b';
UPDATE user_credentials SET password_hash = 'invadido' WHERE user_id = 'bbbbbbbb-0000-0000-0001-000000000001';
COMMIT;

\echo === I03 INSERT com organization_id de B no contexto A: rejeitado por WITH CHECK
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
INSERT INTO workspaces (organization_id, name, status) VALUES ('bbbbbbbb-0000-0000-0000-00000000000b', 'WS forjado', 1);
ROLLBACK;
\echo === I04 UPDATE movendo linha de A para B: rejeitado por WITH CHECK
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
UPDATE clients SET name = name WHERE false;  -- no-op para manter a transacao valida antes do teste
UPDATE workspaces SET organization_id = 'bbbbbbbb-0000-0000-0000-00000000000b' WHERE workspace_id = 'aaaaaaaa-0000-0000-0003-000000000001';
ROLLBACK;

\echo === I05 sem contexto: nenhuma linha, nenhuma escrita
SELECT current_setting('nekkora.organization_id', true) AS contexto_atual;
SELECT (SELECT count(*) FROM workspaces) AS ws, (SELECT count(*) FROM organizations) AS orgs, (SELECT count(*) FROM user_credentials) AS cred;
INSERT INTO workspaces (organization_id, name, status) VALUES ('aaaaaaaa-0000-0000-0000-00000000000a', 'sem contexto', 1);
\echo === I06 contexto vazio
BEGIN;
SET LOCAL nekkora.organization_id = '';
SELECT count(*) AS ws_contexto_vazio FROM workspaces;
ROLLBACK;
\echo === I07 contexto malformado: erro de conversao, nada retornado
BEGIN;
SET LOCAL nekkora.organization_id = 'nao-e-um-uuid';
SELECT count(*) FROM workspaces;
ROLLBACK;

\echo === I08 reutilizacao de conexao: SET LOCAL nao sobrevive ao fim da transacao
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT pg_backend_pid() AS pid, count(*) AS ws_txn1 FROM workspaces;
COMMIT;
SELECT pg_backend_pid() AS mesmo_pid, current_setting('nekkora.organization_id', true) AS contexto_apos_commit, count(*) AS ws_txn2 FROM workspaces;
BEGIN;
SET LOCAL nekkora.organization_id = 'bbbbbbbb-0000-0000-0000-00000000000b';
SELECT count(*) AS ws_txn3_contexto_b, min(organization_id::text) AS org_vista FROM workspaces;
ROLLBACK;
SELECT current_setting('nekkora.organization_id', true) AS contexto_apos_rollback, count(*) AS ws_txn4 FROM workspaces;

\echo === I09 uso indevido (informativo): SET de sessao, sem LOCAL, vaza para a transacao seguinte
SET nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT count(*) AS ws_com_set_de_sessao_na_txn_seguinte FROM workspaces;
RESET nekkora.organization_id;
SELECT count(*) AS ws_apos_reset FROM workspaces;

\echo === I10 falsificar nekkora.fn_organization_id nao afeta a politica do platform
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SET LOCAL nekkora.fn_organization_id = 'bbbbbbbb-0000-0000-0000-00000000000b';
SELECT count(*) AS ws, min(organization_id::text) AS org_vista FROM workspaces;
ROLLBACK;
BEGIN;
SET LOCAL nekkora.fn_organization_id = 'bbbbbbbb-0000-0000-0000-00000000000b';
SELECT count(*) AS ws_so_com_fn_ctx FROM workspaces;
ROLLBACK;

\echo === I11 operacoes nao concedidas ao platform
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
INSERT INTO organizations (organization_id, name, status) VALUES ('aaaaaaaa-0000-0000-0000-00000000000a', 'x', 1);
ROLLBACK;
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
UPDATE automation_schedules SET status = 2 WHERE schedule_id = 'aaaaaaaa-0000-0000-000b-000000000001';
ROLLBACK;
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
INSERT INTO automation_schedule_occurrences (organization_id, workspace_id, schedule_id, scheduled_for, status)
VALUES ('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', now(), 1);
ROLLBACK;
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT count(*) FROM organization_usage_daily;
ROLLBACK;
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
DELETE FROM executions;
ROLLBACK;
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
VALUES ('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000a-000000000001', 'aaaaaaaa-0000-0000-0005-000000000001', 99, 1, now(), '{"schema_version":1,"pid":1}', 'x');
ROLLBACK;
SELECT create_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', now());
SELECT * FROM process_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', gen_random_uuid(), true);
SELECT * FROM process_schedule_occurrence(gen_random_uuid(), true);
SELECT * FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, current_date);
SELECT assign_received_at_and_lock_barrier('aaaaaaaa-0000-0000-0000-00000000000a', 5);
SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'x', NULL, '99999999-0000-0000-0000-0000000000aa', NULL);
CREATE TABLE public.intrusa (id int);
CREATE TEMP TABLE organizations (organization_id uuid);   -- objeto temporario com nome de tabela real

\echo === I12 set_schedule_status: contexto obrigatorio e coincidente
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-000b-000000000001', 2::smallint, 'aaaaaaaa-0000-0000-0002-000000000001') AS suspenso;
SELECT set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-000b-000000000001', 2::smallint, 'aaaaaaaa-0000-0000-0002-000000000001') AS idempotente;
SELECT set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-000b-000000000001', 1::smallint, 'aaaaaaaa-0000-0000-0002-000000000001') AS retomado;
SELECT current_setting('nekkora.fn_organization_id', true) AS fn_ctx_apos_retorno;
COMMIT;
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT set_schedule_status('bbbbbbbb-0000-0000-0000-00000000000b', 'bbbbbbbb-0000-0000-000b-000000000001', 2::smallint, 'bbbbbbbb-0000-0000-0002-000000000001');
ROLLBACK;
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', 'bbbbbbbb-0000-0000-000b-000000000001', 2::smallint, 'aaaaaaaa-0000-0000-0002-000000000001');
ROLLBACK;
SELECT set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-000b-000000000001', 2::smallint, 'aaaaaaaa-0000-0000-0002-000000000001');
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-000b-000000000001', 3::smallint, 'aaaaaaaa-0000-0000-0002-000000000001');
ROLLBACK;
BEGIN; SET LOCAL nekkora.organization_id = 'bbbbbbbb-0000-0000-0000-00000000000b';
SELECT status AS agenda_b_intacta FROM automation_schedules;
ROLLBACK;

\echo === I13 objeto temporario sombreando tabela real nao desvia as triggers (search_path fixo, pg_temp por ultimo)
-- a sessao ja tem uma TEMP TABLE organizations (I11); aqui ela recebe A como CLOSED (status 3).
INSERT INTO pg_temp.organizations VALUES ('aaaaaaaa-0000-0000-0000-00000000000a');
ALTER TABLE pg_temp.organizations ADD COLUMN status SMALLINT DEFAULT 3;
SELECT organization_id, status AS status_na_tabela_temporaria FROM pg_temp.organizations;
BEGIN; SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
-- consulta nao qualificada DA PROPRIA SESSAO resolve para a temporaria (pg_temp vem antes, por padrao)
SELECT status AS consulta_nao_qualificada_da_sessao FROM organizations;
-- a trigger enforce_execution_creation_prerequisites le "organizations" sem qualificar: com o search_path
-- fixado pela 012 ela le public.organizations (ACTIVE) e a criacao segue
INSERT INTO executions (execution_id, organization_id, workspace_id, automation_id, automation_version_id, request_key, request_fingerprint, requested_by_membership_id, command_snapshot, status, origin)
SELECT 'aaaaaaaa-0000-0000-000a-0000000000f1', 'aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-0008-000000000001',
       automation_version_id, 'req-temp', repeat('b', 64), 'aaaaaaaa-0000-0000-0002-000000000001', command_json, 1, 1
FROM automation_versions WHERE automation_version_id = 'aaaaaaaa-0000-0000-0009-000000000001';
UPDATE executions SET status = 6, status_reason = 4, finished_at = clock_timestamp() WHERE execution_id = 'aaaaaaaa-0000-0000-000a-0000000000f1';
COMMIT;
DROP TABLE pg_temp.organizations;
