\pset pager off
SELECT current_user AS papel;
UPDATE organization_usage_daily SET records_count = 999 WHERE organization_id='11111111-1111-1111-1111-111111111111' AND resource_code = 5 AND usage_date = '2026-09-24';
UPDATE organization_usage_daily SET consolidated_at = now() WHERE organization_id='11111111-1111-1111-1111-111111111111' AND resource_code = 4 AND usage_date = '2026-09-24';
-- ingestao nova nao cai em D: o servidor carimba hoje
INSERT INTO execution_events (event_id, organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, received_at, payload, fingerprint)
VALUES ('eeeeeeee-0000-0000-0000-000000000009','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','dddddddd-0000-0000-0000-000000000003','cccccccc-cccc-cccc-cccc-ccccccccccc2',9,2,'2026-09-24 13:00:00+00','2026-09-24 13:00:00+00', jsonb_build_object('schema_version',1,'exit_code',0), repeat('a9',32));
SELECT (received_at AT TIME ZONE 'UTC')::date AS dia_atribuido FROM execution_events WHERE event_id = 'eeeeeeee-0000-0000-0000-000000000009';
