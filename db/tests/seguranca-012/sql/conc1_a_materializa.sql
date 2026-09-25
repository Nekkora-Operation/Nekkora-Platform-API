-- conc1 A - papel: nekkora_scheduler. Materializa (schedule A, :ts) e segura a transacao aberta.
-- Gancho de teste do runner: com -v forcar_falha_a=1, A falha DEPOIS do commit (B segue normal).
\pset pager off
SELECT 'A' AS s, current_user, pg_backend_pid(), clock_timestamp() AS a_inicio;
BEGIN;
SELECT create_schedule_occurrence('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000b-000000000001', :'ts') AS occ_a \gset
SELECT :'occ_a' AS occ_a, clock_timestamp() AS a_criou;
SELECT pg_sleep(8);
SELECT clock_timestamp() AS a_antes_commit;
COMMIT;
SELECT clock_timestamp() AS a_depois_commit;
\echo MARCADOR_OCC_A=:occ_a
\if :{?forcar_falha_a}
SELECT pg_temp.afirma(false, 'falha forcada na sessao A (teste do runner)');
\endif
