-- conc7b-reverso (sessao B) - papel: nekkora_portal. Chamada com x3 enquanto a revogacao (nao
-- confirmada) segura a linha: espera (> 3 s) e, depois do commit da revogacao, responde NK401.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_portal' AND session_user = 'nekkora_portal', 'papel real nekkora_portal (sem SET ROLE)');
SELECT clock_timestamp() AS t0 \gset
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_x3 \gset r_
\set ON_ERROR_STOP on
\set st_chamada :SQLSTATE
SELECT clock_timestamp() AS t1 \gset
\echo MARCADOR_PORTAL_FIM=:t1
SELECT pg_temp.afirma(:'st_chamada' = 'NK401' AND :'LAST_ERROR_MESSAGE' = 'sessao do portal invalida', 'portal: revogacao confirmada durante a espera -> NK401'),
       pg_temp.afirma(:'t1'::timestamptz - :'t0'::timestamptz > interval '3 seconds', 'portal: a chamada esperou o lock da revogacao em curso');
