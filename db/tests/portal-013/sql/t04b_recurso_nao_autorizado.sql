-- t04b - papel: nekkora_portal (LOGIN real; chamadas por bind). Teste 4(b) do desenho: sessao, conta,
-- vinculo, cliente e organizacao validos, mas o recurso nao esta autorizado -> 0 linhas nas 4 funcoes,
-- sem erro. O t04_verifica confere que last_seen_at dessas sessoes AVANCOU (atividade valida, 11.4) e
-- o snapshot de estado confere que client_workspace_grants ficou identica.
-- Gerado por laco; editar o gerador em vez dos casos um a um.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_portal' AND session_user = 'nekkora_portal', 'papel real nekkora_portal (sem SET ROLE)');

\echo === concessao pendente
:q_ws \bind :o1 :tok_gp \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao pendente: list_workspaces'), pg_temp.afirma(:'r_n' = '0', 'concessao pendente: list_workspaces -> 0 linhas');
:q_ex \bind :o1 :tok_gp '' '' '' '' '' '' 100 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao pendente: list_executions'), pg_temp.afirma(:'r_n' = '0', 'concessao pendente: list_executions -> 0 linhas');
:q_get \bind :o1 :tok_gp :e12 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao pendente: get_execution'), pg_temp.afirma(:'r_n' = '0', 'concessao pendente: get_execution(e12) -> 0 linhas');
:q_ev \bind :o1 :tok_gp :e15 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao pendente: list_execution_events'), pg_temp.afirma(:'r_n' = '0', 'concessao pendente: eventos(e15) -> 0 linhas');

\echo === concessao suspensa
:q_ws \bind :o1 :tok_gs \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao suspensa: list_workspaces'), pg_temp.afirma(:'r_n' = '0', 'concessao suspensa: list_workspaces -> 0 linhas');
:q_ex \bind :o1 :tok_gs '' '' '' '' '' '' 100 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao suspensa: list_executions'), pg_temp.afirma(:'r_n' = '0', 'concessao suspensa: list_executions -> 0 linhas');
:q_get \bind :o1 :tok_gs :e12 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao suspensa: get_execution'), pg_temp.afirma(:'r_n' = '0', 'concessao suspensa: get_execution(e12) -> 0 linhas');
:q_ev \bind :o1 :tok_gs :e15 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao suspensa: list_execution_events'), pg_temp.afirma(:'r_n' = '0', 'concessao suspensa: eventos(e15) -> 0 linhas');

\echo === concessao revogada
:q_ws \bind :o1 :tok_gr \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao revogada: list_workspaces'), pg_temp.afirma(:'r_n' = '0', 'concessao revogada: list_workspaces -> 0 linhas');
:q_ex \bind :o1 :tok_gr '' '' '' '' '' '' 100 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao revogada: list_executions'), pg_temp.afirma(:'r_n' = '0', 'concessao revogada: list_executions -> 0 linhas');
:q_get \bind :o1 :tok_gr :e12 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao revogada: get_execution'), pg_temp.afirma(:'r_n' = '0', 'concessao revogada: get_execution(e12) -> 0 linhas');
:q_ev \bind :o1 :tok_gr :e15 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao revogada: list_execution_events'), pg_temp.afirma(:'r_n' = '0', 'concessao revogada: eventos(e15) -> 0 linhas');

\echo === concessao expirada
:q_ws \bind :o1 :tok_ge \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao expirada: list_workspaces'), pg_temp.afirma(:'r_n' = '0', 'concessao expirada: list_workspaces -> 0 linhas');
:q_ex \bind :o1 :tok_ge '' '' '' '' '' '' 100 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao expirada: list_executions'), pg_temp.afirma(:'r_n' = '0', 'concessao expirada: list_executions -> 0 linhas');
:q_get \bind :o1 :tok_ge :e12 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao expirada: get_execution'), pg_temp.afirma(:'r_n' = '0', 'concessao expirada: get_execution(e12) -> 0 linhas');
:q_ev \bind :o1 :tok_ge :e15 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao expirada: list_execution_events'), pg_temp.afirma(:'r_n' = '0', 'concessao expirada: eventos(e15) -> 0 linhas');

\echo === concessao antes de starts_at
:q_ws \bind :o1 :tok_gf \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao antes de starts_at: list_workspaces'), pg_temp.afirma(:'r_n' = '0', 'concessao antes de starts_at: list_workspaces -> 0 linhas');
:q_ex \bind :o1 :tok_gf '' '' '' '' '' '' 100 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao antes de starts_at: list_executions'), pg_temp.afirma(:'r_n' = '0', 'concessao antes de starts_at: list_executions -> 0 linhas');
:q_get \bind :o1 :tok_gf :e12 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao antes de starts_at: get_execution'), pg_temp.afirma(:'r_n' = '0', 'concessao antes de starts_at: get_execution(e12) -> 0 linhas');
:q_ev \bind :o1 :tok_gf :e15 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao antes de starts_at: list_execution_events'), pg_temp.afirma(:'r_n' = '0', 'concessao antes de starts_at: eventos(e15) -> 0 linhas');

\echo === revisao vencida (next_review_at)
:q_ws \bind :o1 :tok_gv \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'revisao vencida (next_review_at): list_workspaces'), pg_temp.afirma(:'r_n' = '0', 'revisao vencida (next_review_at): list_workspaces -> 0 linhas');
:q_ex \bind :o1 :tok_gv '' '' '' '' '' '' 100 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'revisao vencida (next_review_at): list_executions'), pg_temp.afirma(:'r_n' = '0', 'revisao vencida (next_review_at): list_executions -> 0 linhas');
:q_get \bind :o1 :tok_gv :e12 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'revisao vencida (next_review_at): get_execution'), pg_temp.afirma(:'r_n' = '0', 'revisao vencida (next_review_at): get_execution(e12) -> 0 linhas');
:q_ev \bind :o1 :tok_gv :e15 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'revisao vencida (next_review_at): list_execution_events'), pg_temp.afirma(:'r_n' = '0', 'revisao vencida (next_review_at): eventos(e15) -> 0 linhas');

\echo === concessao para workspace inativo (WS4)
:q_ws \bind :o1 :tok_gw \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao para workspace inativo (WS4): list_workspaces'), pg_temp.afirma(:'r_n' = '0', 'concessao para workspace inativo (WS4): list_workspaces -> 0 linhas');
:q_ex \bind :o1 :tok_gw '' '' '' '' '' '' 100 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao para workspace inativo (WS4): list_executions'), pg_temp.afirma(:'r_n' = '0', 'concessao para workspace inativo (WS4): list_executions -> 0 linhas');
:q_get \bind :o1 :tok_gw :e12 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao para workspace inativo (WS4): get_execution'), pg_temp.afirma(:'r_n' = '0', 'concessao para workspace inativo (WS4): get_execution(e12) -> 0 linhas');
:q_ev \bind :o1 :tok_gw :e15 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'concessao para workspace inativo (WS4): list_execution_events'), pg_temp.afirma(:'r_n' = '0', 'concessao para workspace inativo (WS4): eventos(e15) -> 0 linhas');

\echo === WS4: a execucao existe (e51), o workspace e do mesmo cliente, mas esta inativo
:q_get \bind :o1 :tok_gw :e51 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'Gw: get_execution(e51, WS4 inativo) -> 0 linhas');
:q_ex \bind :o1 :tok_gw :ws4 '' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'Gw: p_workspace_id = WS4 inativo -> 0 linhas');
