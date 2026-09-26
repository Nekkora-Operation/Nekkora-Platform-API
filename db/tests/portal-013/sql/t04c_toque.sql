-- t04c - papel: nekkora_portal (LOGIN real; chamadas por bind). Semantica do toque (11.4, D3, teste 7d):
-- duas chamadas no mesmo minuto geram UMA escrita; mais de 1 min depois da ultima escrita, gera outra;
-- nunca retrocede; parametro invalido (22023) desfaz a transacao inteira, inclusive o toque.
-- Os instantes de cada chamada (relogio do banco) saem como marcadores para o t04c_verifica.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_portal' AND session_user = 'nekkora_portal', 'papel real nekkora_portal (sem SET ROLE)');

\echo === xtoq: ultima escrita ha 61 s -> 1a chamada escreve; 2a chamada logo depois nao escreve
SELECT clock_timestamp() AS t1a \gset
:q_ws \bind :o1 :tok_xtoq \gset r_
SELECT clock_timestamp() AS t1b \gset
SELECT pg_temp.afirma(:'r_n' = '1', 'xtoq: 1a chamada valida');
SELECT pg_sleep(1.5);
SELECT clock_timestamp() AS t2a \gset
:q_ws \bind :o1 :tok_xtoq \gset r_
SELECT pg_temp.afirma(:'r_n' = '1', 'xtoq: 2a chamada valida');
\echo MARCADOR_T1A=:t1a
\echo MARCADOR_T1B=:t1b
\echo MARCADOR_T2A=:t2a

\echo === xfut: last_seen_at no futuro -> chamada valida, sem retrocesso
:q_ws \bind :o1 :tok_xfut \gset r_
SELECT pg_temp.afirma(:'r_n' = '1', 'xfut: chamada valida');

\echo === xp: sessao valida, parametro invalido -> 22023 (mensagem fixa) e nenhum toque
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_xp '' '' '' '' '' '' 0 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.e22023(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'p_limit = 0');
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_xp '' '' '' '' '' '' 101 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.e22023(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'p_limit = 101 (acima do maximo provisorio 100)');
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_xp '' '' '' '' 2026-01-01T00:00:00Z '' 10 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.e22023(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'cursor incompleto (so created_at)');
