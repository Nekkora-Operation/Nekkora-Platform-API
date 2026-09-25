-- conc1 A - papel: nekkora_scheduler. Materializa (schedule A, :ts) e segura a transacao aberta.
\pset pager off
SELECT 'A' AS s, current_user, pg_backend_pid(), clock_timestamp() AS a_inicio;
BEGIN;
SELECT create_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', :'ts') AS occ_a, clock_timestamp() AS a_criou;
SELECT pg_sleep(8);
SELECT clock_timestamp() AS a_antes_commit;
COMMIT;
SELECT clock_timestamp() AS a_depois_commit;
