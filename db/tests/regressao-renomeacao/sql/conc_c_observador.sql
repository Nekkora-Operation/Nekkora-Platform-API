\pset pager off
SELECT clock_timestamp() AS c_observacao, pid, usename, state, wait_event_type, wait_event, left(query, 70) AS query
FROM pg_stat_activity WHERE usename IN ('nekkora_app','nekkora_retention_executor') ORDER BY usename;
SELECT a.usename AS bloqueado, pg_blocking_pids(a.pid) AS bloqueado_por, (SELECT usename FROM pg_stat_activity b WHERE b.pid = ANY(pg_blocking_pids(a.pid)) LIMIT 1) AS papel_bloqueador
FROM pg_stat_activity a WHERE a.usename = 'nekkora_retention_executor';
SELECT status, records_count, bytes_count FROM organization_usage_daily WHERE organization_id='11111111-1111-1111-1111-111111111111' AND resource_code=5 AND usage_date='2026-09-23';
