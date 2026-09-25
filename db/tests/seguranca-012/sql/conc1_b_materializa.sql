-- conc1 B - papel: nekkora_scheduler. Mesma identidade (schedule A, :ts), concorrente com A.
\pset pager off
SELECT 'B' AS s, current_user, pg_backend_pid(), clock_timestamp() AS b_inicio;
SELECT create_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', :'ts') AS occ_b, clock_timestamp() AS b_concluiu;
