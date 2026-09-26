-- conc7b (depois) - papel: nekkora_portal. Depois dos commits de A e B: a proxima chamada com x2 -> NK401.
-- Tambem o sentido inverso (7b-reverso) e conferido aqui para x3.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_portal' AND session_user = 'nekkora_portal', 'papel real nekkora_portal (sem SET ROLE)');
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_x2 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'x2 depois da revogacao concorrente -> NK401');
