-- conc7b (sessao A) - papel: nekkora_portal. Teste 7(b): a chamada valida trava a linha da sessao (FOR
-- UPDATE) ate o fim da transacao; a revogacao concorrente (B, platform) espera. A le com a sessao x2,
-- segura a transacao por 8 s e faz commit; o instante do commit vai para o runner.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_portal' AND session_user = 'nekkora_portal', 'papel real nekkora_portal (sem SET ROLE)');
BEGIN;
:q_ex \bind :o1 :tok_x2 '' '' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '7', 'A: requisicao validada (7 execucoes) e linha da sessao travada');
SELECT pg_sleep(8);
:q_ws \bind :o1 :tok_x2 \gset r_
SELECT pg_temp.afirma(:'r_n' = '1', 'A: ainda dentro da mesma transacao, a sessao segue valida (a revogacao esta esperando)');
SELECT clock_timestamp() AS t_commit \gset
COMMIT;
\echo MARCADOR_A_COMMIT=:t_commit
