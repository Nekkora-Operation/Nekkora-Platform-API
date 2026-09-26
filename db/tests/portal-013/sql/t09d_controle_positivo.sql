-- t09d - papel: nekkora (superusuario). CONTROLE POSITIVO do teste 9(d): mesma chamada por bind, com
-- outro marcador, numa sessao com log_error_verbosity = default, log_min_error_statement = error e
-- log_parameter_max_length_on_error = -1. O marcador PRECISA aparecer no log do servidor: prova que a
-- busca do runner enxergaria um vazamento.
\pset pager off
SET log_error_verbosity = default;
SET log_min_error_statement = error;
SET log_parameter_max_length_on_error = -1;
\set ON_ERROR_STOP off
SELECT count(*) FROM public.portal_list_workspaces($1::uuid, $2) \bind :o1 :marcador \g
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'controle positivo (superusuario, log verboso)');
