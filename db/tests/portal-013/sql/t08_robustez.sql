-- t08 - papel: nekkora_portal (LOGIN real). Teste 8 do desenho e grupo "Integridade" da missao:
-- (a) nekkora.fn_organization_id definido pelo chamador como sentinela e restaurado depois da chamada
--     (sucesso, NK401 capturado, 22023 com savepoint); nekkora.organization_id nunca e tocado.
--     Limite: a restauracao no ramo EXCEPTION da funcao nao e observavel isoladamente de fora, porque o
--     rollback da (sub)transacao que recebe o erro tambem desfaz o set_config; o teste prova o efeito
--     observavel (o chamador sempre reencontra o proprio valor).
-- (b) objetos homonimos criados em pg_temp pelo proprio portal (tabelas, funcoes, operador, tipo) nao
--     alteram o resultado: search_path = pg_catalog, pg_temp e referencias qualificadas.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_portal' AND session_user = 'nekkora_portal', 'papel real nekkora_portal (sem SET ROLE)');
:q_ws \bind :o1 :tok_x \gset b_ws_
:q_ex \bind :o1 :tok_x '' '' '' '' '' '' 100 \gset b_ex_
:q_ev \bind :o1 :tok_x :e15 \gset b_ev_

\echo === (a) sentinelas de contexto
SET nekkora.fn_organization_id = 'SENTINELA_FN';
SET nekkora.organization_id = 'SENTINELA_ORG';
:q_ws \bind :o1 :tok_x \gset r_
SELECT pg_temp.afirma(:'r_fp' = :'b_ws_fp', 'chamada com sentinelas: resultado identico');
SELECT pg_temp.afirma(current_setting('nekkora.fn_organization_id') = 'SENTINELA_FN', 'sucesso: fn_organization_id restaurado ao valor do chamador');
SELECT pg_temp.afirma(current_setting('nekkora.organization_id') = 'SENTINELA_ORG', 'sucesso: nekkora.organization_id intacto');
DO $$
BEGIN
    PERFORM * FROM public.portal_list_workspaces('10000000-0000-0000-0000-000000000001', repeat('Q', 42) || 'A');
    RAISE EXCEPTION 'FALHA DE ASSERCAO: token inexistente deveria gerar NK401';
EXCEPTION WHEN SQLSTATE 'NK401' THEN
    NULL;
END $$;
SELECT pg_temp.afirma(current_setting('nekkora.fn_organization_id') = 'SENTINELA_FN', 'NK401 capturado pelo chamador: fn_organization_id restaurado');
SELECT pg_temp.afirma(current_setting('nekkora.organization_id') = 'SENTINELA_ORG', 'NK401: nekkora.organization_id intacto');
BEGIN;
SET LOCAL nekkora.fn_organization_id = 'SENTINELA_TX';
SAVEPOINT antes;
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_x '' '' '' '' '' '' 0 \gset r_
\set st_chamada :SQLSTATE
\set ON_ERROR_STOP on
ROLLBACK TO SAVEPOINT antes;
SELECT pg_temp.afirma(:'st_chamada' = '22023', 'chamada dentro da transacao falhou com 22023'),
       pg_temp.e22023(:'LAST_ERROR_SQLSTATE', :'LAST_ERROR_MESSAGE', 'p_limit = 0 dentro de transacao');
SELECT pg_temp.afirma(current_setting('nekkora.fn_organization_id') = 'SENTINELA_TX', '22023 dentro de transacao: fn_organization_id do chamador preservado');
:q_ws \bind :o1 :tok_x \gset r_
SELECT pg_temp.afirma(:'r_fp' = :'b_ws_fp' AND current_setting('nekkora.fn_organization_id') = 'SENTINELA_TX', 'mesma transacao: nova chamada ok e sentinela SET LOCAL restaurada');
COMMIT;
SELECT pg_temp.afirma(current_setting('nekkora.fn_organization_id') = 'SENTINELA_FN', 'depois do COMMIT: valor de sessao do chamador');
RESET nekkora.fn_organization_id;
RESET nekkora.organization_id;

\echo === (b) sombreamento em pg_temp pelo proprio portal
CREATE TEMP TABLE user_sessions (session_id uuid, organization_id uuid, user_id uuid, session_token_hash text, created_at timestamptz,
    expires_at timestamptz, last_seen_at timestamptz, revoked_at timestamptz);
CREATE TEMP TABLE client_workspace_grants AS SELECT * FROM (VALUES (1)) v(a);
CREATE TEMP TABLE workspaces (workspace_id uuid, organization_id uuid, client_id uuid, name text, status smallint);
CREATE TEMP TABLE executions (execution_id uuid);
CREATE FUNCTION pg_temp.sha256(bytea) RETURNS bytea LANGUAGE sql AS $f$ SELECT '\x00'::bytea $f$;
CREATE FUNCTION pg_temp.encode(bytea, text) RETURNS text LANGUAGE sql AS $f$ SELECT 'sombra' $f$;
CREATE FUNCTION pg_temp.clock_timestamp() RETURNS timestamptz LANGUAGE sql AS $f$ SELECT '2000-01-01'::timestamptz $f$;
CREATE FUNCTION pg_temp.current_setting(text, boolean) RETURNS text LANGUAGE sql AS $f$ SELECT '20000000-0000-0000-0000-000000000002' $f$;
CREATE FUNCTION pg_temp.sempre(text, text) RETURNS boolean LANGUAGE sql AS $f$ SELECT true $f$;
CREATE OPERATOR pg_temp.= (LEFTARG = text, RIGHTARG = text, FUNCTION = pg_temp.sempre);
CREATE TYPE pg_temp.uuid AS (a int);
-- Observacao: o tipo pg_temp.uuid sombreia o "::uuid" da PROPRIA consulta do cliente (no search_path
-- padrao do cliente, pg_temp e pesquisado primeiro para tipos e tabelas). Por isso, nesta secao, o lado
-- do cliente qualifica pg_catalog.uuid; o que se testa e o interior das funcoes.
SELECT md5(coalesce(string_agg(q::text, '|' ORDER BY q.o), '')) AS fp
FROM public.portal_list_workspaces($1::pg_catalog.uuid, $2) WITH ORDINALITY AS q(workspace_id, name, o) \bind :o1 :tok_x \gset r_
SELECT md5(coalesce(string_agg(q::text, '|' ORDER BY q.o), '')) AS fp
FROM public.portal_list_executions($1::pg_catalog.uuid, $2, NULL, NULL, NULL, NULL, NULL, NULL, 100)
     WITH ORDINALITY AS q(execution_id, workspace_id, automation_id, automation_name, created_at, started_at, finished_at, duration, status, status_reason, o)
\bind :o1 :tok_x \gset r2_
SELECT md5(coalesce(string_agg(q::text, '|' ORDER BY q.o), '')) AS fp
FROM public.portal_list_execution_events($1::pg_catalog.uuid, $2, $3::pg_catalog.uuid) WITH ORDINALITY AS q(event_type, received_at, o) \bind :o1 :tok_x :e15 \gset r3_
SELECT pg_temp.afirma(:'r_fp' = :'b_ws_fp' AND :'r2_fp' = :'b_ex_fp' AND :'r3_fp' = :'b_ev_fp',
  'com tabelas, funcoes, operador e tipo homonimos em pg_temp: resultados identicos');
\set ON_ERROR_STOP off
SELECT count(*) FROM public.portal_list_workspaces($1::pg_catalog.uuid, $2) \bind :o1 :tok_xrev \g
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'com sombras em pg_temp: sessao revogada continua NK401');
