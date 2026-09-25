\pset pager off
SELECT 'A' s, current_user, pg_backend_pid(), clock_timestamp() AS a_inicio;
BEGIN;
SELECT status, records_count, bytes_count, clock_timestamp() AS a_lock_obtido FROM organization_usage_daily
WHERE organization_id = '11111111-1111-1111-1111-111111111111' AND resource_code = 5 AND usage_date = '2026-09-23' FOR SHARE;
SELECT pg_sleep(12);
SELECT clock_timestamp() AS a_antes_commit;
COMMIT;
SELECT clock_timestamp() AS a_depois_commit;
