\pset pager off
SELECT tgenabled, count(*) AS triggers FROM pg_trigger WHERE NOT tgisinternal GROUP BY 1;
SELECT tgrelid::regclass, tgname, tgenabled FROM pg_trigger WHERE tgname IN ('trg_execution_events_enforce_agent_origin','trg_logs_enforce_historical_origin');
-- regras de origem que ficaram desligadas durante a insercao: conferidas por consulta
SELECT 'eventos historicos violando origem' AS verificacao, count(*) FROM execution_events ev
  JOIN executions e ON e.execution_id = ev.execution_id
  LEFT JOIN worker_agent_assignments w ON w.assignment_id = e.worker_agent_assignment_id
 WHERE ev.event_id::text LIKE 'eeeeeeee-202%' AND (w.agent_id IS DISTINCT FROM ev.agent_id OR ev.purged_at IS NOT NULL OR ev.purge_run_id IS NOT NULL)
UNION ALL
SELECT 'logs historicos violando origem', count(*) FROM logs l
  LEFT JOIN executions e ON e.execution_id = l.execution_id
  LEFT JOIN worker_agent_assignments w ON w.assignment_id = l.worker_agent_assignment_id
 WHERE l.log_id::text LIKE 'ffffffff-202%' AND ((l.execution_id IS NOT NULL AND e.worker_agent_assignment_id IS DISTINCT FROM l.worker_agent_assignment_id) OR w.agent_id IS DISTINCT FROM l.agent_id OR l.purged_at IS NOT NULL);
-- esperado para cada dia/recurso, pelo mesmo criterio de close_usage_day
SELECT 5 AS resource_code, (received_at AT TIME ZONE 'UTC')::date AS dia, count(*), sum(octet_length(payload::text)) AS bytes FROM execution_events WHERE organization_id='11111111-1111-1111-1111-111111111111' GROUP BY 2
UNION ALL
SELECT 4, (received_at AT TIME ZONE 'UTC')::date, count(*), sum(octet_length(payload::text)) FROM logs WHERE organization_id='11111111-1111-1111-1111-111111111111' GROUP BY 2 ORDER BY 1,2;
SELECT usage_date, resource_code, status, records_count, bytes_count, consolidated_at FROM organization_usage_daily WHERE usage_date IN ('2026-09-23','2026-09-24') ORDER BY 1,2;
