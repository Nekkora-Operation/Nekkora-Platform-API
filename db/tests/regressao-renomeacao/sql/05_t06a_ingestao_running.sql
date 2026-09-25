\pset pager off
SELECT current_user AS papel, clock_timestamp() AS t;
-- evento PROCESS_STARTED (seq 1) e log STDOUT, com id fornecido pela origem (padrao da baseline 007)
INSERT INTO execution_events (event_id, organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
VALUES ('eeeeeeee-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','dddddddd-0000-0000-0000-000000000001','cccccccc-cccc-cccc-cccc-ccccccccccc2',1,1, now(), jsonb_build_object('schema_version',1,'pid',4242), repeat('e1',32))
ON CONFLICT DO NOTHING;
INSERT INTO logs (log_id, organization_id, workspace_id, agent_id, worker_agent_assignment_id, execution_id, channel, occurred_at, payload, fingerprint)
VALUES ('ffffffff-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','cccccccc-cccc-cccc-cccc-ccccccccccc2','cccccccc-cccc-cccc-cccc-ccccccccccc3','dddddddd-0000-0000-0000-000000000001',2, now(), jsonb_build_object('schema_version',1,'message','ola nekkora'), repeat('f1',32))
ON CONFLICT DO NOTHING;
-- received_at enviado pelo chamador e ignorado (atribuido pelo servidor)
INSERT INTO logs (log_id, organization_id, agent_id, worker_agent_assignment_id, channel, occurred_at, received_at, payload, fingerprint)
VALUES ('ffffffff-0000-0000-0000-000000000002','11111111-1111-1111-1111-111111111111','cccccccc-cccc-cccc-cccc-ccccccccccc2','cccccccc-cccc-cccc-cccc-ccccccccccc3',1, now(), '2000-01-01 00:00:00+00', jsonb_build_object('schema_version',1,'message','agent up','level',2), repeat('f2',32))
ON CONFLICT DO NOTHING;
SELECT log_id, received_at > '2026-01-01' AS received_at_do_servidor FROM logs WHERE log_id = 'ffffffff-0000-0000-0000-000000000002';
