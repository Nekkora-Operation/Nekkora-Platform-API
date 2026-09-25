\pset pager off
SELECT current_user AS papel;
BEGIN; UPDATE organization_usage_daily SET records_count = 999 WHERE organization_id='11111111-1111-1111-1111-111111111111' AND resource_code = 5 AND usage_date = '2026-09-24'; ROLLBACK;
BEGIN; UPDATE organization_usage_daily SET status = 1 WHERE organization_id='11111111-1111-1111-1111-111111111111' AND resource_code = 5 AND usage_date = '2026-09-24'; ROLLBACK;
BEGIN; DELETE FROM organization_usage_daily WHERE usage_date = '2026-09-24'; ROLLBACK;
SELECT a.resource_code, a.status, a.records_count, a.bytes_count, a.consolidated_at,
       CASE a.resource_code
         WHEN 5 THEN (SELECT count(*) || '/' || sum(octet_length(payload::text)) FROM execution_events WHERE organization_id=a.organization_id AND (received_at AT TIME ZONE 'UTC')::date = a.usage_date)
         WHEN 4 THEN (SELECT count(*) || '/' || sum(octet_length(payload::text)) FROM logs WHERE organization_id=a.organization_id AND (received_at AT TIME ZONE 'UTC')::date = a.usage_date)
       END AS persistidos_agora
FROM organization_usage_daily a WHERE a.organization_id='11111111-1111-1111-1111-111111111111' AND a.usage_date = '2026-09-24' ORDER BY 1;
