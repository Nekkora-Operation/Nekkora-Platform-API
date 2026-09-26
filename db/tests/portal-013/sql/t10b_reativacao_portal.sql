-- t10b - papel: nekkora_portal (LOGIN real; chamadas por bind). Depois do t10a: a sessao antiga de Ra
-- (revogada na suspensao) continua NK401; a sessao nova (conta reativada) e valida, mas nao ve nada:
-- a concessao revogada nao volta com a reativacao.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_portal' AND session_user = 'nekkora_portal', 'papel real nekkora_portal (sem SET ROLE)');
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_ra \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'Ra: sessao revogada na suspensao continua invalida depois da reativacao');
:q_ws \bind :o1 :tok_ra2 \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'Ra: sessao nova valida'), pg_temp.afirma(:'r_n' = '0', 'Ra reativada: list_workspaces -> 0 (concessao revogada nao volta)');
:q_ex \bind :o1 :tok_ra2 '' '' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'Ra reativada: list_executions -> 0');
:q_get \bind :o1 :tok_ra2 :e11 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'Ra reativada: get_execution(e11, WS1) -> 0');
