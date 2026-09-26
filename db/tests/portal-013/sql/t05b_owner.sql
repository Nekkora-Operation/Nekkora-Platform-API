-- t05b - papel declarado: nekkora (superusuario) com SET ROLE nekkora_portal_owner. O owner e NOLOGIN:
-- SET ROLE e a unica forma de agir como ele, e ESTE caso NAO e evidencia de isolamento do papel do
-- portal (so do limite por coluna do owner - teste 6 do desenho). Prova que um erro de programacao
-- dentro das funcoes nao conseguiria ler payload/error_summary/etc. nem escrever fora de last_seen_at.
\pset pager off
SELECT pg_temp.afirma(session_user = 'nekkora', 'sessao do superusuario de teste');
-- os helpers de assercao sao funcoes temporarias desta sessao, criadas pelo superusuario; a 012 tira o
-- EXECUTE padrao de PUBLIC das funcoes que ele cria, entao o owner precisa de EXECUTE so nelas
GRANT EXECUTE ON FUNCTION pg_temp.afirma(boolean, text), pg_temp.espera_erro(text, text, text) TO nekkora_portal_owner;
SET ROLE nekkora_portal_owner;
SELECT pg_temp.afirma(current_user = 'nekkora_portal_owner', 'current_user = nekkora_portal_owner (via SET ROLE, declarado)');
SET nekkora.fn_organization_id = :'o1';

\echo === leitura permitida (controle): colunas da allowlist, sob RLS da organizacao do contexto
SELECT pg_temp.afirma((SELECT count(*) FROM public.executions) = 13, 'owner le as 13 execucoes de O1 (so a organizacao de fn_organization_id)');
SELECT pg_temp.afirma((SELECT count(*) FROM public.user_sessions WHERE organization_id = :'o2') = 0, 'owner nao ve sessoes de O2 com contexto O1 (RLS)');

\echo === colunas e tabelas fora da allowlist -> 42501
SELECT pg_temp.espera_erro('SELECT payload FROM public.execution_events', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT fingerprint FROM public.execution_events', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT occurred_at FROM public.execution_events', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT error_summary FROM public.executions', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT exit_code FROM public.executions', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT command_snapshot FROM public.executions', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT requested_by_membership_id FROM public.executions', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT worker_id FROM public.executions', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT * FROM public.executions', '42501', 'permission denied');
SELECT pg_temp.espera_erro('SELECT email FROM public.users', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT display_name FROM public.users', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT description FROM public.workspaces', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT description FROM public.automations', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT name FROM public.organizations', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT name FROM public.clients', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT last_access_at FROM public.client_workspace_grants', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT 1 FROM public.logs', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT 1 FROM public.workspace_secrets', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT 1 FROM public.user_credentials', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT 1 FROM public.automation_schedules', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT 1 FROM public.workers', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT 1 FROM public.agents', '42501', 'permission denied');

\echo === escrita fora de user_sessions.last_seen_at -> 42501
SELECT pg_temp.espera_erro('UPDATE public.user_sessions SET revoked_at = NULL', '42501', 'permission denied'),
       pg_temp.espera_erro('UPDATE public.user_sessions SET expires_at = expires_at + interval ''1 day''', '42501', 'permission denied'),
       pg_temp.espera_erro('INSERT INTO public.user_sessions (organization_id) VALUES (NULL)', '42501', 'permission denied'),
       pg_temp.espera_erro('DELETE FROM public.user_sessions', '42501', 'permission denied'),
       pg_temp.espera_erro('TRUNCATE public.user_sessions', '42501', 'permission denied'),
       pg_temp.espera_erro('UPDATE public.client_workspace_grants SET last_access_at = now()', '42501', 'permission denied'),
       pg_temp.espera_erro('UPDATE public.client_workspace_grants SET status = 2', '42501', 'permission denied'),
       pg_temp.espera_erro('UPDATE public.users SET status = 2', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT public.purge_expired_records(NULL::uuid, NULL::integer, NULL::uuid, NULL::integer)', '42501', 'permission denied for function');

\echo === RLS de escrita do owner: last_seen_at so na organizacao do contexto (WITH CHECK)
BEGIN;
WITH u AS (UPDATE public.user_sessions SET last_seen_at = last_seen_at WHERE organization_id = :'o2' RETURNING 1)
SELECT count(*) AS n_o2 FROM u \gset
ROLLBACK;
SELECT pg_temp.afirma(:'n_o2' = '0', 'owner com contexto O1 nao alcanca sessoes de O2 nem para toque (0 linhas; desfeito)');
RESET nekkora.fn_organization_id;
SELECT pg_temp.afirma((SELECT count(*) FROM public.user_sessions) = 0, 'owner sem contexto: nenhuma linha visivel (falha fechado)');
RESET ROLE;
SELECT pg_temp.afirma(current_user = 'nekkora', 'RESET ROLE');
