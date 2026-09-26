-- observador - papel: nekkora (somente leitura de pg_stat_activity). Copia do observador da suite 012 (inalterada la).
-- Durante a espera de uma sessao concorrente:
-- Afirma que ha uma sessao de :bloqueado esperando lock e bloqueada por uma sessao de :bloqueador.
\pset pager off
SELECT clock_timestamp() AS c_observacao, pid, usename, state, wait_event_type, wait_event, left(query, 60) AS query
FROM pg_stat_activity WHERE usename LIKE 'nekkora\_%' AND pid <> pg_backend_pid() AND state <> 'idle' ORDER BY backend_start;
SELECT a.pid AS bloqueado, a.usename, pg_blocking_pids(a.pid) AS bloqueado_por
FROM pg_stat_activity a WHERE a.usename LIKE 'nekkora\_%' AND cardinality(pg_blocking_pids(a.pid)) > 0;
SELECT pg_temp.afirma(EXISTS (
    SELECT 1 FROM pg_stat_activity a
    JOIN pg_stat_activity b ON b.pid = ANY(pg_blocking_pids(a.pid))
    WHERE a.usename = :'bloqueado' AND a.wait_event_type = 'Lock' AND b.usename = :'bloqueador'),
  format('sessao de %s em espera de lock, bloqueada por sessao de %s', :'bloqueado', :'bloqueador'));
