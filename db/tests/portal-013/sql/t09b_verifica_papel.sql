-- t09b - papel: nekkora (superusuario; so leitura). Depois do t09a: as 7 configuracoes continuam no
-- catalogo, globais, e nenhuma configuracao por banco apareceu para o papel.
\pset pager off
SELECT pg_get_userbyid(setrole) AS papel, setdatabase, setconfig FROM pg_db_role_setting WHERE setrole = 'nekkora_portal'::regrole;
SELECT pg_temp.afirma((SELECT array_agg(c ORDER BY c) FROM pg_db_role_setting s, unnest(s.setconfig) c
                       WHERE s.setrole = 'nekkora_portal'::regrole AND s.setdatabase = 0)
    = ARRAY['log_error_verbosity=terse', 'log_min_duration_sample=-1', 'log_min_duration_statement=-1', 'log_min_error_statement=panic',
            'log_parameter_max_length=0', 'log_statement=none', 'log_transaction_sample_rate=0']
  AND (SELECT count(*) FROM pg_db_role_setting WHERE setrole = 'nekkora_portal'::regrole) = 1,
  'depois das tentativas do papel (inclusive RESET ALL): as 7 configuracoes intactas, nenhuma por banco');
