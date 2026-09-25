-- 99 - papel: nekkora (somente leitura). Estado final da suite.
\pset pager off
SELECT d.organization_id, d.resource_code, d.usage_date, d.status, d.records_count, d.bytes_count,
       CASE d.resource_code
         WHEN 5 THEN (SELECT count(*) || '/' || coalesce(sum(octet_length(payload::text)), 0) FROM execution_events e WHERE e.organization_id = d.organization_id AND (e.received_at AT TIME ZONE 'UTC')::date = d.usage_date)
         ELSE (SELECT count(*) || '/' || coalesce(sum(octet_length(payload::text)), 0) FROM logs e WHERE e.organization_id = d.organization_id AND (e.received_at AT TIME ZONE 'UTC')::date = d.usage_date)
       END AS persistidos_registros_bytes
FROM organization_usage_daily d ORDER BY 1, 3, 2;
SELECT organization_id, origin, status, count(*) FROM executions GROUP BY 1, 2, 3 ORDER BY 1, 2, 3;
SELECT o.organization_id, o.status, o.skip_reason, count(*) AS ocorrencias,
       count(DISTINCT e.execution_id) AS execucoes
FROM automation_schedule_occurrences o LEFT JOIN executions e ON e.schedule_occurrence_id = o.occurrence_id GROUP BY 1, 2, 3 ORDER BY 1, 2;
SELECT (SELECT count(*) FROM organization_purge_runs) AS purge_runs,
       (SELECT count(*) FILTER (WHERE tgenabled = 'O') || '/' || count(*) FROM pg_trigger WHERE NOT tgisinternal) AS triggers_habilitadas,
       (SELECT count(*) FROM pg_roles WHERE rolname = 'nekkora_app') AS nekkora_app;
