-- t06 - papel: nekkora_portal (LOGIN real; chamadas por bind). Teste 6 do desenho e grupo "Allowlist e
-- paginacao" da missao. (a) As linhas devolvidas (impressas aqui por completo) nao contem nenhum
-- marcador da fixture (payload, error_summary, exit_code, descricoes, e-mail, nome de pessoa, argumento
-- do comando, logs); o runner tambem varre esta saida. As colunas exatas de cada funcao sao conferidas
-- no catalogo (t00). (b) Keyset estavel com created_at empatado, sem duplicata nem lacuna; limite de
-- pagina; filtros so restringem.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_portal' AND session_user = 'nekkora_portal', 'papel real nekkora_portal (sem SET ROLE)');

\echo === (a) conteudo completo devolvido a X (inclusive e13, que tem error_summary, exit_code e payload-marcador na base)
SELECT * FROM public.portal_list_workspaces($1::uuid, $2) \bind :o1 :tok_x \g
SELECT * FROM public.portal_list_executions($1::uuid, $2, NULL, NULL, NULL, NULL, NULL, NULL, 100) \bind :o1 :tok_x \g
SELECT * FROM public.portal_get_execution($1::uuid, $2, $3::uuid) \bind :o1 :tok_x :e13 \g
SELECT * FROM public.portal_list_execution_events($1::uuid, $2, $3::uuid) \bind :o1 :tok_x :e13 \g
SELECT count(*) AS n, bool_and(q::text !~* 'marcador|@|MARCADOR_ARGUMENTO') AS limpo
FROM (SELECT * FROM public.portal_list_executions($1::uuid, $2, NULL, NULL, NULL, NULL, NULL, NULL, 100)) q \bind :o1 :tok_x \gset a_
SELECT pg_temp.afirma(:'a_n' = '7' AND :'a_limpo' = 't', 'list_executions de X: nenhuma linha contem marcador, e-mail ou argumento');
SELECT count(*) AS n, bool_and(q::text !~* 'marcador' AND q::text ~ '^\([1-5],"[0-9: .+-]+"\)$') AS limpo
FROM (SELECT * FROM public.portal_list_execution_events($1::uuid, $2, $3::uuid)) q \bind :o1 :tok_x :e13 \gset a_
SELECT pg_temp.afirma(:'a_n' = '2' AND :'a_limpo' = 't', 'eventos de e13 (FAILED): so event_type e received_at, sem payload/exit_code/error_summary');
SELECT q.status, q.status_reason FROM public.portal_get_execution($1::uuid, $2, $3::uuid) q \bind :o1 :tok_x :e13 \gset a_
SELECT pg_temp.afirma(:'a_status' = '5' AND :'a_status_reason' = '3', 'e13: codigos FAILED/PROCESS_FAILED (a API mapeia para rotulos)');

\echo === (b) paginacao keyset de X com limite 2: e15 | e17 e16 (empate) | e14 e13 | e12 e11
:q_ex \bind :o1 :tok_x '' '' '' '' '' '' 2 \gset p1_
:q_ex \bind :o1 :tok_x '' '' '' '' :p1_lc :p1_li 2 \gset p2_
:q_ex \bind :o1 :tok_x '' '' '' '' :p2_lc :p2_li 2 \gset p3_
:q_ex \bind :o1 :tok_x '' '' '' '' :p3_lc :p3_li 2 \gset p4_
:q_ex \bind :o1 :tok_x '' '' '' '' :p4_lc :p4_li 2 \gset p5_
SELECT :'p1_ids' AS p1, :'p2_ids' AS p2, :'p3_ids' AS p3, :'p4_ids' AS p4, :'p5_n' AS p5_n;
SELECT pg_temp.afirma(:'p1_ids' = concat_ws(',', :'e15', :'e17') AND :'p2_ids' = concat_ws(',', :'e16', :'e14')
                  AND :'p3_ids' = concat_ws(',', :'e13', :'e12') AND :'p4_ids' = :'e11' AND :'p5_n' = '0',
  'paginas de 2: [e15,e17] [e16,e14] [e13,e12] [e11] [] - empate de created_at atravessa a pagina sem perder nem repetir');
SELECT pg_temp.afirma(concat_ws(',', :'p1_ids', :'p2_ids', :'p3_ids', :'p4_ids') = concat_ws(',', :'e15', :'e17', :'e16', :'e14', :'e13', :'e12', :'e11'),
  'concatenacao das paginas = listagem completa, mesma ordem');
\echo repeticao da pagina 2 com o mesmo cursor: estavel
:q_ex \bind :o1 :tok_x '' '' '' '' :p1_lc :p1_li 2 \gset p2b_
SELECT pg_temp.afirma(:'p2b_fp' = :'p2_fp', 'mesmo cursor, mesma pagina (estavel)');

\echo === limite de pagina (maximo provisorio 100, 8.7)
:q_ex \bind :o1 :tok_x '' '' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '7', 'p_limit = 100 aceito');
:q_ex \bind :o1 :tok_x '' '' '' '' '' '' 1 \gset r_
SELECT pg_temp.afirma(:'r_n' = '1' AND :'r_ids' = :'e15', 'p_limit = 1 aceito');
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_x '' '' '' '' '' '' 101 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.e22023(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'p_limit = 101');
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_x '' '' '' '' '' '' 0 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.e22023(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'p_limit = 0');
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_x '' '' '' '' '' '' -5 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.e22023(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'p_limit negativo');
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_x '' '' '' '' '' '' '' \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.e22023(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'p_limit NULL');
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_x '' '' '' '' '' :e15 10 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.e22023(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'cursor incompleto (so execution_id)');

\echo === filtros so restringem o conjunto autorizado
:q_ex \bind :o1 :tok_x '' '{4}' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_ids' = :'e15', 'p_status = {SUCCEEDED}: so e15');
:q_ex \bind :o1 :tok_x '' '{1}' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_ids' = concat_ws(',', :'e17', :'e16', :'e14', :'e11'), 'p_status = {PENDING}: e17, e16, e14, e11');
:q_ex \bind :o1 :tok_x '' '{1,2,3,4,5,6}' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '7', 'p_status com todos os codigos: continua so WS1 (7)');
:q_ex \bind :o1 :tok_x '' '' :p1_lc '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_ids' = concat_ws(',', :'e15', :'e17', :'e16', :'e14'), 'p_from = created_at do grupo empatado (inclusivo): e15, e17, e16, e14');
:q_ex \bind :o1 :tok_x '' '' '' :p1_lc '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_ids' = concat_ws(',', :'e13', :'e12', :'e11'), 'p_to = created_at do grupo empatado (exclusivo): e13, e12, e11');
:q_ex \bind :o1 :tok_x :ws2 '{1,2,3,4,5,6}' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'p_workspace_id = WS2 com todos os status: 0');
