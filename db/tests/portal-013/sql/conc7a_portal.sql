-- conc7a (sessao A) - papel: nekkora_portal. Teste 7(a): revogacao de concessao concorrente com uma
-- transacao do portal ja aberta. A le (7 execucoes de WS1, conta Rv), segura a transacao; B (platform)
-- revoga a concessao e faz commit SEM esperar A (a consulta nao trava concessoes); na MESMA transacao
-- (READ COMMITTED, snapshot novo por comando) a proxima leitura de A ja retorna 0; e numa nova tambem.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_portal' AND session_user = 'nekkora_portal', 'papel real nekkora_portal (sem SET ROLE)');
BEGIN;
:q_ex \bind :o1 :tok_rv '' '' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '7', 'A: 1a leitura com a concessao ativa = 7 execucoes');
SELECT clock_timestamp() AS t_leitura1 \gset
\echo MARCADOR_A_LEITURA1=:t_leitura1
SELECT pg_sleep(6);
SELECT clock_timestamp() AS t_leitura2 \gset
\echo MARCADOR_A_LEITURA2=:t_leitura2
:q_ex \bind :o1 :tok_rv '' '' '' '' '' '' 100 \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'A: 2a leitura NA MESMA transacao, depois do commit da revogacao = 0');
COMMIT;
:q_ws \bind :o1 :tok_rv \gset r_
SELECT pg_temp.afirma(:'r_n' = '0', 'A: nova transacao depois da revogacao: list_workspaces = 0 (sessao valida, sem concessao)');
