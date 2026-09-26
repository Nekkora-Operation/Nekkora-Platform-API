-- t03 - papel: nekkora_portal (LOGIN real; chamadas por bind). Teste 3 do desenho, gate decisivo:
-- troca de contexto. Como o proprio papel do portal, define nekkora.organization_id,
-- nekkora.fn_organization_id e GUCs arbitrarios de escopo (cliente, vinculo, usuario, workspace,
-- concessao) apontando para OUTRA organizacao, OUTRO cliente e OUTRO vinculo, em nivel de sessao e com
-- SET LOCAL. Em cada passo, as 4 funcoes com o token de X devolvem resultado IDENTICO (md5 das linhas)
-- ao obtido sem GUC, e SELECT direto continua negado (42501).
-- Este mesmo script roda contra variantes deliberadamente vulneraveis (mutacoes m1a/m1b do runner) e
-- PRECISA falhar nelas.
-- Gerado por laco (8 passos identicos em estrutura); editar o gerador em vez dos passos um a um.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_portal' AND session_user = 'nekkora_portal', 'papel real nekkora_portal (sem SET ROLE)');

\echo === baseline sem nenhum GUC nekkora.*
SELECT pg_temp.afirma(coalesce(current_setting('nekkora.organization_id', true), '') = ''
                  AND coalesce(current_setting('nekkora.fn_organization_id', true), '') = '', 'baseline sem GUC');
:q_ws \bind :o1 :tok_x \gset b_ws_
:q_ex \bind :o1 :tok_x '' '' '' '' '' '' 100 \gset b_ex_
:q_get \bind :o1 :tok_x :e12 \gset b_get_
:q_ev \bind :o1 :tok_x :e15 \gset b_ev_
SELECT pg_temp.afirma(:'b_ws_n' = '1' AND :'b_ex_n' = '7' AND :'b_get_n' = '1' AND :'b_ev_n' = '2', 'baseline de X: 1 workspace, 7 execucoes, 1 detalhe, 2 eventos');

\echo === passo 1: nekkora.organization_id = O2 (outra organizacao)
SET nekkora.organization_id = :'o2';
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_x \gset r_ws_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 1: list_workspaces sem erro');
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_x '' '' '' '' '' '' 100 \gset r_ex_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 1: list_executions sem erro');
\set ON_ERROR_STOP off
:q_get \bind :o1 :tok_x :e12 \gset r_get_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 1: get_execution sem erro');
\set ON_ERROR_STOP off
:q_ev \bind :o1 :tok_x :e15 \gset r_ev_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 1: list_execution_events sem erro');
SELECT pg_temp.afirma(:'r_ws_fp' = :'b_ws_fp', 'passo 1: list_workspaces identico ao baseline'),
       pg_temp.afirma(:'r_ex_fp' = :'b_ex_fp', 'passo 1: list_executions identico ao baseline'),
       pg_temp.afirma(:'r_get_fp' = :'b_get_fp', 'passo 1: get_execution identico ao baseline'),
       pg_temp.afirma(:'r_ev_fp' = :'b_ev_fp', 'passo 1: list_execution_events identico ao baseline');
SELECT pg_temp.espera_erro('SELECT count(*) FROM public.executions', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT count(*) FROM public.client_workspace_grants', '42501', 'permission denied');

\echo === passo 2: nekkora.organization_id = O1
SET nekkora.organization_id = :'o1';
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_x \gset r_ws_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 2: list_workspaces sem erro');
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_x '' '' '' '' '' '' 100 \gset r_ex_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 2: list_executions sem erro');
\set ON_ERROR_STOP off
:q_get \bind :o1 :tok_x :e12 \gset r_get_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 2: get_execution sem erro');
\set ON_ERROR_STOP off
:q_ev \bind :o1 :tok_x :e15 \gset r_ev_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 2: list_execution_events sem erro');
SELECT pg_temp.afirma(:'r_ws_fp' = :'b_ws_fp', 'passo 2: list_workspaces identico ao baseline'),
       pg_temp.afirma(:'r_ex_fp' = :'b_ex_fp', 'passo 2: list_executions identico ao baseline'),
       pg_temp.afirma(:'r_get_fp' = :'b_get_fp', 'passo 2: get_execution identico ao baseline'),
       pg_temp.afirma(:'r_ev_fp' = :'b_ev_fp', 'passo 2: list_execution_events identico ao baseline');
SELECT pg_temp.espera_erro('SELECT count(*) FROM public.executions', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT count(*) FROM public.client_workspace_grants', '42501', 'permission denied');

\echo === passo 3: nekkora.fn_organization_id = O2 (contexto interno das funcoes)
SET nekkora.fn_organization_id = :'o2';
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_x \gset r_ws_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 3: list_workspaces sem erro');
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_x '' '' '' '' '' '' 100 \gset r_ex_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 3: list_executions sem erro');
\set ON_ERROR_STOP off
:q_get \bind :o1 :tok_x :e12 \gset r_get_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 3: get_execution sem erro');
\set ON_ERROR_STOP off
:q_ev \bind :o1 :tok_x :e15 \gset r_ev_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 3: list_execution_events sem erro');
SELECT pg_temp.afirma(:'r_ws_fp' = :'b_ws_fp', 'passo 3: list_workspaces identico ao baseline'),
       pg_temp.afirma(:'r_ex_fp' = :'b_ex_fp', 'passo 3: list_executions identico ao baseline'),
       pg_temp.afirma(:'r_get_fp' = :'b_get_fp', 'passo 3: get_execution identico ao baseline'),
       pg_temp.afirma(:'r_ev_fp' = :'b_ev_fp', 'passo 3: list_execution_events identico ao baseline');
SELECT pg_temp.espera_erro('SELECT count(*) FROM public.executions', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT count(*) FROM public.client_workspace_grants', '42501', 'permission denied');

\echo === passo 4: nekkora.fn_organization_id = O1
SET nekkora.fn_organization_id = :'o1';
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_x \gset r_ws_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 4: list_workspaces sem erro');
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_x '' '' '' '' '' '' 100 \gset r_ex_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 4: list_executions sem erro');
\set ON_ERROR_STOP off
:q_get \bind :o1 :tok_x :e12 \gset r_get_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 4: get_execution sem erro');
\set ON_ERROR_STOP off
:q_ev \bind :o1 :tok_x :e15 \gset r_ev_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 4: list_execution_events sem erro');
SELECT pg_temp.afirma(:'r_ws_fp' = :'b_ws_fp', 'passo 4: list_workspaces identico ao baseline'),
       pg_temp.afirma(:'r_ex_fp' = :'b_ex_fp', 'passo 4: list_executions identico ao baseline'),
       pg_temp.afirma(:'r_get_fp' = :'b_get_fp', 'passo 4: get_execution identico ao baseline'),
       pg_temp.afirma(:'r_ev_fp' = :'b_ev_fp', 'passo 4: list_execution_events identico ao baseline');
SELECT pg_temp.espera_erro('SELECT count(*) FROM public.executions', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT count(*) FROM public.client_workspace_grants', '42501', 'permission denied');

\echo === passo 5: nekkora.fn_organization_id = valor nao-uuid
SET nekkora.fn_organization_id = 'nao-e-uuid';
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_x \gset r_ws_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 5: list_workspaces sem erro');
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_x '' '' '' '' '' '' 100 \gset r_ex_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 5: list_executions sem erro');
\set ON_ERROR_STOP off
:q_get \bind :o1 :tok_x :e12 \gset r_get_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 5: get_execution sem erro');
\set ON_ERROR_STOP off
:q_ev \bind :o1 :tok_x :e15 \gset r_ev_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 5: list_execution_events sem erro');
SELECT pg_temp.afirma(:'r_ws_fp' = :'b_ws_fp', 'passo 5: list_workspaces identico ao baseline'),
       pg_temp.afirma(:'r_ex_fp' = :'b_ex_fp', 'passo 5: list_executions identico ao baseline'),
       pg_temp.afirma(:'r_get_fp' = :'b_get_fp', 'passo 5: get_execution identico ao baseline'),
       pg_temp.afirma(:'r_ev_fp' = :'b_ev_fp', 'passo 5: list_execution_events identico ao baseline');
SELECT pg_temp.espera_erro('SELECT count(*) FROM public.executions', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT count(*) FROM public.client_workspace_grants', '42501', 'permission denied');

\echo === passo 6: GUCs de escopo apontando para Y/C2/WS2/concessao de Y (mesma organizacao)
SET nekkora.fn_organization_id = :'o1';
SET nekkora.user_id = :'y';
SET nekkora.client_id = :'c2';
SET nekkora.client_membership_id = :'cm_y';
SET nekkora.workspace_id = :'ws2';
SET nekkora.grant_id = :'gr_y';
SET nekkora.session_id = :'s_y';
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_x \gset r_ws_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 6: list_workspaces sem erro');
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_x '' '' '' '' '' '' 100 \gset r_ex_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 6: list_executions sem erro');
\set ON_ERROR_STOP off
:q_get \bind :o1 :tok_x :e12 \gset r_get_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 6: get_execution sem erro');
\set ON_ERROR_STOP off
:q_ev \bind :o1 :tok_x :e15 \gset r_ev_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 6: list_execution_events sem erro');
SELECT pg_temp.afirma(:'r_ws_fp' = :'b_ws_fp', 'passo 6: list_workspaces identico ao baseline'),
       pg_temp.afirma(:'r_ex_fp' = :'b_ex_fp', 'passo 6: list_executions identico ao baseline'),
       pg_temp.afirma(:'r_get_fp' = :'b_get_fp', 'passo 6: get_execution identico ao baseline'),
       pg_temp.afirma(:'r_ev_fp' = :'b_ev_fp', 'passo 6: list_execution_events identico ao baseline');
SELECT pg_temp.espera_erro('SELECT count(*) FROM public.executions', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT count(*) FROM public.client_workspace_grants', '42501', 'permission denied');

\echo === passo 7: todos os GUCs apontando para O2 e W
SET nekkora.organization_id = :'o2';
SET nekkora.fn_organization_id = :'o2';
SET nekkora.user_id = :'w';
SET nekkora.workspace_id = :'ws7';
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_x \gset r_ws_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 7: list_workspaces sem erro');
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_x '' '' '' '' '' '' 100 \gset r_ex_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 7: list_executions sem erro');
\set ON_ERROR_STOP off
:q_get \bind :o1 :tok_x :e12 \gset r_get_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 7: get_execution sem erro');
\set ON_ERROR_STOP off
:q_ev \bind :o1 :tok_x :e15 \gset r_ev_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 7: list_execution_events sem erro');
SELECT pg_temp.afirma(:'r_ws_fp' = :'b_ws_fp', 'passo 7: list_workspaces identico ao baseline'),
       pg_temp.afirma(:'r_ex_fp' = :'b_ex_fp', 'passo 7: list_executions identico ao baseline'),
       pg_temp.afirma(:'r_get_fp' = :'b_get_fp', 'passo 7: get_execution identico ao baseline'),
       pg_temp.afirma(:'r_ev_fp' = :'b_ev_fp', 'passo 7: list_execution_events identico ao baseline');
SELECT pg_temp.espera_erro('SELECT count(*) FROM public.executions', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT count(*) FROM public.client_workspace_grants', '42501', 'permission denied');

\echo === passo 8: SET LOCAL dentro de transacao (X -> Y), chamada na mesma transacao
BEGIN;
SET LOCAL nekkora.fn_organization_id = :'o1';
SET LOCAL nekkora.user_id = :'y';
SET LOCAL nekkora.client_membership_id = :'cm_y';
SET LOCAL nekkora.workspace_id = :'ws2';
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_x \gset r_ws_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 8: list_workspaces sem erro');
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_x '' '' '' '' '' '' 100 \gset r_ex_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 8: list_executions sem erro');
\set ON_ERROR_STOP off
:q_get \bind :o1 :tok_x :e12 \gset r_get_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 8: get_execution sem erro');
\set ON_ERROR_STOP off
:q_ev \bind :o1 :tok_x :e15 \gset r_ev_
\set ON_ERROR_STOP on
SELECT pg_temp.ok(:'SQLSTATE', 'passo 8: list_execution_events sem erro');
SELECT pg_temp.afirma(:'r_ws_fp' = :'b_ws_fp', 'passo 8: list_workspaces identico ao baseline'),
       pg_temp.afirma(:'r_ex_fp' = :'b_ex_fp', 'passo 8: list_executions identico ao baseline'),
       pg_temp.afirma(:'r_get_fp' = :'b_get_fp', 'passo 8: get_execution identico ao baseline'),
       pg_temp.afirma(:'r_ev_fp' = :'b_ev_fp', 'passo 8: list_execution_events identico ao baseline');
SELECT pg_temp.espera_erro('SELECT count(*) FROM public.executions', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT count(*) FROM public.client_workspace_grants', '42501', 'permission denied');
COMMIT;

\echo === Y com GUCs apontando para X: continua vendo so WS2
SET nekkora.user_id = :'x';
SET nekkora.client_membership_id = :'cm_x';
SET nekkora.workspace_id = :'ws1';
:q_ws \bind :o1 :tok_y \gset r_
SELECT pg_temp.afirma(:'r_n' = '1' AND :'r_ids' = :'ws2', 'Y com GUCs de X: portal_list_workspaces = {WS2}');
:q_ex \bind :o1 :tok_y :ws1 '' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'Y com GUCs de X e p_workspace_id = WS1: 0 linhas');

\echo === o que a sessao do portal enxerga dos GUCs depois das chamadas (nekkora.organization_id nunca e tocado)
SELECT g AS guc, current_setting(g, true) AS valor FROM unnest(ARRAY['nekkora.organization_id', 'nekkora.fn_organization_id', 'nekkora.user_id',
    'nekkora.client_id', 'nekkora.client_membership_id', 'nekkora.workspace_id', 'nekkora.grant_id', 'nekkora.session_id']) AS g;
SELECT pg_temp.afirma(current_setting('nekkora.organization_id') = :'o2', 'nekkora.organization_id definido pelo chamador permanece intacto');
