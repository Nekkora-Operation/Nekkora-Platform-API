-- t09c - papel: nekkora_portal (LOGIN real; chamada por bind). Teste 9(c) do desenho: o papel liga
-- log_parameter_max_length_on_error = -1 (ACEITO: esse parametro nao pode ser travado) e faz uma chamada
-- por bind com um token-marcador em formato valido, mas inexistente -> NK401. O runner confere depois
-- que o marcador NAO aparece no log do servidor. O marcador aparece na saida DESTE cliente (CONTEXT
-- enviado ao processo): e a obrigacao separada do Spring (fixar 0 e registrar so SQLSTATE).
-- Roda tambem contra a mutacao m4 (papel sem log_error_verbosity = terse): la o marcador vaza para o log.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_portal' AND session_user = 'nekkora_portal', 'papel real nekkora_portal (sem SET ROLE)');
SET log_parameter_max_length_on_error = -1;
SELECT pg_temp.afirma(current_setting('log_parameter_max_length_on_error') = '-1', 'SET log_parameter_max_length_on_error = -1 aceito para o papel (nao travavel)');
\set VERBOSITY verbose
\set ON_ERROR_STOP off
SELECT count(*) FROM public.portal_list_workspaces($1::uuid, $2) \bind :o1 :marcador \g
\set ON_ERROR_STOP on
\set VERBOSITY default
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'token-marcador inexistente (bind) com log_parameter_max_length_on_error = -1');
