-- t09a - papel: nekkora_portal (LOGIN real). Teste 9(a)(b) do desenho e grupo "Logs" da missao:
-- (a) SHOW confere as configuracoes fixadas no papel; (b) SET, SET LOCAL, RESET, set_config() de cada
-- uma -> 42501; ALTER ROLE (global e IN DATABASE), ALTER DATABASE e LOAD de auto_explain -> negados.
-- ALTER ROLE ... RESET ALL do proprio papel e executado e o SQLSTATE registrado; o t09b (superusuario)
-- confere que as configuracoes continuam todas no catalogo, qualquer que tenha sido a resposta.
-- A tentativa por PGOPTIONS na conexao e feita pelo runner (a conexao precisa falhar).
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_portal' AND session_user = 'nekkora_portal', 'papel real nekkora_portal (sem SET ROLE)');

\echo === (a) valores efetivos na sessao do papel
SELECT name, setting, source FROM pg_settings WHERE name IN ('log_error_verbosity', 'log_min_error_statement', 'log_parameter_max_length',
  'log_statement', 'log_min_duration_statement', 'log_min_duration_sample', 'log_transaction_sample_rate', 'log_parameter_max_length_on_error') ORDER BY 1;
SELECT pg_temp.afirma(current_setting('log_error_verbosity') = 'terse', 'log_error_verbosity = terse'),
       pg_temp.afirma(current_setting('log_min_error_statement') = 'panic', 'log_min_error_statement = panic'),
       pg_temp.afirma(current_setting('log_parameter_max_length') = '0', 'log_parameter_max_length = 0'),
       pg_temp.afirma(current_setting('log_statement') = 'none', 'log_statement = none'),
       pg_temp.afirma(current_setting('log_min_duration_statement') = '-1', 'log_min_duration_statement = -1'),
       pg_temp.afirma(current_setting('log_min_duration_sample') = '-1', 'log_min_duration_sample = -1'),
       pg_temp.afirma(current_setting('log_transaction_sample_rate') = '0', 'log_transaction_sample_rate = 0');
SELECT pg_temp.afirma((SELECT count(*) FROM pg_settings WHERE name IN ('log_error_verbosity', 'log_min_error_statement', 'log_parameter_max_length',
    'log_statement', 'log_min_duration_statement', 'log_min_duration_sample', 'log_transaction_sample_rate') AND source = 'user') = 7,
  'as 7 vem da configuracao do papel (source = user)');

\echo === (b) o papel nao consegue desfazer nenhuma
CREATE TEMP TABLE alvo (p TEXT, v TEXT);
INSERT INTO alvo VALUES ('log_error_verbosity', 'verbose'), ('log_min_error_statement', 'error'), ('log_parameter_max_length', '-1'),
  ('log_statement', 'all'), ('log_min_duration_statement', '0'), ('log_min_duration_sample', '0'), ('log_transaction_sample_rate', '1');
SELECT pg_temp.afirma(count(*) = 7, 'SET negado nas 7')
FROM alvo, LATERAL pg_temp.espera_erro(format('SET %s = %L', p, v), '42501', 'permission denied to set parameter') e;
SELECT pg_temp.afirma(count(*) = 7, 'SET LOCAL negado nas 7')
FROM alvo, LATERAL pg_temp.espera_erro(format('SET LOCAL %s = %L', p, v), '42501', 'permission denied to set parameter') e;
SELECT pg_temp.afirma(count(*) = 7, 'RESET negado nas 7')
FROM alvo, LATERAL pg_temp.espera_erro(format('RESET %s', p), '42501', 'permission denied to set parameter') e;
SELECT pg_temp.afirma(count(*) = 7, 'set_config() negado nas 7')
FROM alvo, LATERAL pg_temp.espera_erro(format('SELECT set_config(%L, %L, false)', p, v), '42501', 'permission denied to set parameter') e;
SELECT pg_temp.afirma(count(*) = 7, 'ALTER ROLE nekkora_portal SET negado nas 7')
FROM alvo, LATERAL pg_temp.espera_erro(format('ALTER ROLE nekkora_portal SET %s = %L', p, v), '42501', 'permission denied to set parameter') e;
SELECT pg_temp.afirma(count(*) = 7, 'ALTER ROLE nekkora_portal RESET negado nas 7')
FROM alvo, LATERAL pg_temp.espera_erro(format('ALTER ROLE nekkora_portal RESET %s', p), '42501', 'permission denied to set parameter') e;
SELECT pg_temp.afirma(count(*) = 7, 'ALTER ROLE nekkora_portal IN DATABASE nekkora SET negado nas 7 (sobreporia a global)')
FROM alvo, LATERAL pg_temp.espera_erro(format('ALTER ROLE nekkora_portal IN DATABASE nekkora SET %s = %L', p, v), '42501', 'permission denied to set parameter') e;
SELECT pg_temp.espera_erro('ALTER DATABASE nekkora SET log_error_verbosity = verbose', '42501', 'must be owner of database'),
       pg_temp.espera_erro('ALTER ROLE nekkora_portal SET session_preload_libraries = ''auto_explain''', '42501', 'permission denied to set parameter'),
       pg_temp.espera_erro('LOAD ''auto_explain''', '42501', 'access to library "auto_explain" is not allowed');
\echo ALTER ROLE nekkora_portal RESET ALL (resposta registrada; o efeito e conferido no catalogo pelo t09b):
\set ON_ERROR_STOP off
ALTER ROLE nekkora_portal RESET ALL;
\set ON_ERROR_STOP on
\echo MARCADOR_RESET_ALL_SQLSTATE=:SQLSTATE
