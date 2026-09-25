-- Resumo final - papel: nekkora (somente leitura).
\pset pager off
SELECT a.organization_id, a.resource_code, a.usage_date, a.status, a.records_count, a.bytes_count,
       CASE a.resource_code
         WHEN 5 THEN (SELECT count(*) || '/' || coalesce(sum(octet_length(payload::text)), 0) FROM execution_events e WHERE e.organization_id = a.organization_id AND (e.received_at AT TIME ZONE 'UTC')::date = a.usage_date)
         WHEN 4 THEN (SELECT count(*) || '/' || coalesce(sum(octet_length(payload::text)), 0) FROM logs l WHERE l.organization_id = a.organization_id AND (l.received_at AT TIME ZONE 'UTC')::date = a.usage_date)
       END AS persistidos_registros_bytes
FROM organization_usage_daily a ORDER BY 1, 3, 2;
SELECT execution_id, origin, status, status_reason FROM executions ORDER BY created_at;
SELECT (SELECT count(*) FROM organization_purge_runs) AS purge_runs,
       (SELECT proacl::text FROM pg_proc WHERE proname = 'purge_expired_records') AS acl_purge,
       (SELECT count(*) FILTER (WHERE tgenabled = 'O') || '/' || count(*) FROM pg_trigger WHERE NOT tgisinternal) AS triggers_habilitadas;
