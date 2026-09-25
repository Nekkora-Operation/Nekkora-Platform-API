-- conc2 verificacao - papel: nekkora. Agregado = persistidos.
\pset pager off
SELECT d.records_count, d.bytes_count, d.consolidated_at,
       (SELECT count(*) || '/' || sum(octet_length(payload::text)) FROM execution_events e
        WHERE e.organization_id = d.organization_id AND (e.received_at AT TIME ZONE 'UTC')::date = d.usage_date) AS persistidos
FROM organization_usage_daily d
WHERE d.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a' AND d.resource_code = 5 AND d.usage_date = (clock_timestamp() AT TIME ZONE 'UTC')::date;
SELECT pg_temp.afirma((SELECT d.records_count = (SELECT count(*) FROM execution_events e WHERE e.organization_id = d.organization_id AND (e.received_at AT TIME ZONE 'UTC')::date = d.usage_date)
                          AND d.bytes_count = (SELECT sum(octet_length(payload::text)) FROM execution_events e WHERE e.organization_id = d.organization_id AND (e.received_at AT TIME ZONE 'UTC')::date = d.usage_date)
                       FROM organization_usage_daily d
                       WHERE d.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a' AND d.resource_code = 5 AND d.usage_date = (clock_timestamp() AT TIME ZONE 'UTC')::date),
  'agregado de hoje = registros persistidos');
