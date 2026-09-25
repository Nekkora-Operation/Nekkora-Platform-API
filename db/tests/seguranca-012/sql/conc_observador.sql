-- observador - papel: nekkora (somente leitura de pg_stat_activity), durante a espera de B.
\pset pager off
SELECT clock_timestamp() AS c_observacao, pid, usename, state, wait_event_type, wait_event, left(query, 60) AS query
FROM pg_stat_activity WHERE usename LIKE 'nekkora\_%' AND pid <> pg_backend_pid() AND state <> 'idle' ORDER BY backend_start;
SELECT a.pid AS bloqueado, a.usename, pg_blocking_pids(a.pid) AS bloqueado_por
FROM pg_stat_activity a WHERE a.usename LIKE 'nekkora\_%' AND cardinality(pg_blocking_pids(a.pid)) > 0;
