\pset pager off
SELECT 'B' s, current_user, pg_backend_pid(), clock_timestamp() AS b_inicio;
SELECT close_usage_day('11111111-1111-1111-1111-111111111111', 5, '2026-09-23');
SELECT clock_timestamp() AS b_concluido;
