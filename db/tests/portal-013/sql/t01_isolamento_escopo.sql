-- t01 - papel: nekkora_portal (LOGIN real, conexao direta; chamadas por bind). Testes 1 e 2 do desenho
-- (secao 7) e grupos "Isolamento" e "Escopo por vinculo" da missao:
--   mesmo cliente, vinculos distintos (X: WS1; Y: WS2); clientes distintos (Z, C2); outra organizacao
--   (W em O2, K em O4); organizacao alegada errada; IDs validos de outra concessao em p_workspace_id,
--   p_execution_id e no cursor so restringem (0 linhas), nunca ampliam nem revelam existencia.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_portal' AND session_user = 'nekkora_portal', 'papel real nekkora_portal (sem SET ROLE)');

\echo === X (C1, concessao so em WS1)
:q_ws \bind :o1 :tok_x \gset r_
SELECT pg_temp.afirma(:'r_n' = '1' AND :'r_ids' = :'ws1', 'X: portal_list_workspaces = {WS1}');
:q_ex \bind :o1 :tok_x '' '' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '7' AND :'r_ids' = concat_ws(',', :'e15', :'e17', :'e16', :'e14', :'e13', :'e12', :'e11'),
  'X: execucoes = as 7 de WS1, em created_at DESC, execution_id DESC (empate e17 > e16 > e14)');
:q_ex \bind :o1 :tok_x :ws2 '' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'X: p_workspace_id = WS2 (mesmo cliente, outro vinculo) -> 0 linhas');
:q_ex \bind :o1 :tok_x :ws3 '' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'X: p_workspace_id = WS3 (outro cliente) -> 0 linhas');
:q_ex \bind :o1 :tok_x :wsi '' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'X: p_workspace_id = WSi (interno) -> 0 linhas');
:q_ex \bind :o1 :tok_x :ws7 '' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'X: p_workspace_id = WS7 (outra organizacao) -> 0 linhas');
:q_ex \bind :o1 :tok_x :ws1 '' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '7', 'X: p_workspace_id = WS1 (concedido) -> as 7');
\echo cursor apontando para execucao de WS2 (e22): so pagina, continua sob a concessao de X
:q_ex \bind :o1 :tok_y '' '' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_ids' = concat_ws(',', :'e22', :'e21'), 'Y: execucoes = e22, e21 (WS2)');
-- o cursor de Y (created_at de e22 obtido pela propria listagem de Y) usado por X
:q_ex \bind :o1 :tok_y '' '' '' '' '' '' 1 \gset r_
\set c22 :r_lc
:q_ex \bind :o1 :tok_x '' '' '' '' :c22 :e22 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '6' AND :'r_ids' = concat_ws(',', :'e17', :'e16', :'e14', :'e13', :'e12', :'e11'),
  'X com cursor (created_at, id) de e22 (WS2): so execucoes de WS1 anteriores ao cursor');
\echo detalhe e eventos de execucoes fora da concessao de X: 0 linhas (sem revelar existencia)
:q_get \bind :o1 :tok_x :e21 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'X: portal_get_execution(e21, WS2) -> 0 linhas');
:q_get \bind :o1 :tok_x :e31 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'X: portal_get_execution(e31, WS3/C2) -> 0 linhas');
:q_get \bind :o1 :tok_x :e41 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'X: portal_get_execution(e41, WSi) -> 0 linhas');
:q_get \bind :o1 :tok_x :e51 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'X: portal_get_execution(e51, WS4 de C1, sem concessao de X) -> 0 linhas');
:q_get \bind :o1 :tok_x :e71 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'X: portal_get_execution(e71, O2) -> 0 linhas');
:q_get \bind :o1 :tok_x 10000000-0000-000a-0000-0000000000ff \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'X: portal_get_execution(id inexistente) -> 0 linhas (mesma resposta que fora do escopo)');
:q_get \bind :o1 :tok_x :e12 \gset r_
SELECT pg_temp.afirma(:'r_n' = '1' AND :'r_ids' = :'e12', 'X: portal_get_execution(e12, WS1) -> 1 linha');
:q_ev \bind :o1 :tok_x :e21 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'X: eventos de e21 (WS2) -> 0 linhas');
:q_ev \bind :o1 :tok_x :e31 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'X: eventos de e31 (WS3/C2) -> 0 linhas');
:q_ev \bind :o1 :tok_x :e15 \gset r_
SELECT pg_temp.afirma(:'r_n' = '2' AND :'r_ids' = '1,2', 'X: eventos de e15 = PROCESS_STARTED, PROCESS_SUCCEEDED (por sequence)');

\echo === Y (C1, concessao so em WS2): simetrico
:q_ws \bind :o1 :tok_y \gset r_
SELECT pg_temp.afirma(:'r_n' = '1' AND :'r_ids' = :'ws2', 'Y: portal_list_workspaces = {WS2}');
:q_ex \bind :o1 :tok_y :ws1 '' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'Y: p_workspace_id = WS1 -> 0 linhas');
:q_get \bind :o1 :tok_y :e11 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'Y: portal_get_execution(e11, WS1) -> 0 linhas');
:q_ev \bind :o1 :tok_y :e15 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'Y: eventos de e15 (WS1) -> 0 linhas');
:q_ev \bind :o1 :tok_y :e21 \gset r_
SELECT pg_temp.afirma(:'r_n' = '2' AND :'r_ids' = '1,2', 'Y: eventos de e21 (WS2) -> 2');

\echo === Z (C2, WS3): nada de C1
:q_ws \bind :o1 :tok_z \gset r_
SELECT pg_temp.afirma(:'r_n' = '1' AND :'r_ids' = :'ws3', 'Z: portal_list_workspaces = {WS3}');
:q_ex \bind :o1 :tok_z '' '' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '1' AND :'r_ids' = :'e31', 'Z: execucoes = {e31}');
:q_ex \bind :o1 :tok_z :ws1 '' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'Z: p_workspace_id = WS1 (C1) -> 0');
:q_get \bind :o1 :tok_z :e11 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'Z: portal_get_execution(e11, C1) -> 0');
:q_ev \bind :o1 :tok_z :e13 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'Z: eventos de e13 (C1) -> 0');

\echo === outras organizacoes
:q_ws \bind :o2 :tok_w \gset r_
SELECT pg_temp.afirma(:'r_n' = '1' AND :'r_ids' = :'ws7', 'W (O2): portal_list_workspaces = {WS7}');
:q_ex \bind :o2 :tok_w '' '' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '1' AND :'r_ids' = :'e71', 'W (O2): execucoes = {e71}');
:q_get \bind :o2 :tok_w :e11 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'W (O2): portal_get_execution(e11, O1) -> 0');
:q_ws \bind :o4 :tok_k \gset r_
SELECT pg_temp.afirma(:'r_n' = '1' AND :'r_ids' = :'ws9', 'K (O4): portal_list_workspaces = {WS9}');

\echo === organizacao alegada errada: a sessao nao e encontrada -> NK401 (as 4 funcoes)
\set ON_ERROR_STOP off
:q_ws \bind :o2 :tok_x \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'token de X com p_organization_id = O2 (list_workspaces)');
\set ON_ERROR_STOP off
:q_ex \bind :o2 :tok_x '' '' '' '' '' '' 100 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'token de X com O2 (list_executions)');
\set ON_ERROR_STOP off
:q_get \bind :o2 :tok_x :e71 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'token de X com O2 e id de execucao de O2 (get_execution)');
\set ON_ERROR_STOP off
:q_ev \bind :o2 :tok_x :e71 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'token de X com O2 (list_execution_events)');
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_w \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'token de W (O2) com p_organization_id = O1');
\set ON_ERROR_STOP off
:q_ws \bind :org_inexistente :tok_x \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'token de X com organizacao inexistente');
\set ON_ERROR_STOP off
SELECT count(*) AS n FROM public.portal_list_workspaces(NULLIF($1, '')::uuid, $2) \bind '' :tok_x \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'token de X com p_organization_id NULL');
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_k \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'token de K (O4) com O1');
