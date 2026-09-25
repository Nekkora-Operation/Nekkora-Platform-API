\pset pager off
SELECT current_user AS papel;
\echo === entrega atrasada: evento PROCESS_SUCCEEDED seq 2 e log STDERR, apos execucao SUCCEEDED e associacao ENDED
INSERT INTO execution_events (event_id, organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
VALUES ('eeeeeeee-0000-0000-0000-000000000002','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','dddddddd-0000-0000-0000-000000000001','cccccccc-cccc-cccc-cccc-ccccccccccc2',2,2, now() - interval '5 seconds', jsonb_build_object('schema_version',1,'exit_code',0), repeat('e2',32))
ON CONFLICT DO NOTHING;
INSERT INTO logs (log_id, organization_id, workspace_id, agent_id, worker_agent_assignment_id, execution_id, channel, occurred_at, payload, fingerprint)
VALUES ('ffffffff-0000-0000-0000-000000000003','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','cccccccc-cccc-cccc-cccc-ccccccccccc2','cccccccc-cccc-cccc-cccc-ccccccccccc3','dddddddd-0000-0000-0000-000000000001',3, now() - interval '5 seconds', jsonb_build_object('schema_version',1,'message','warn tardio'), repeat('f3',32))
ON CONFLICT DO NOTHING;
\echo === reenvio identico, event_id e log_id ja existentes: 0 linhas, depois comparacao com o registro existente
INSERT INTO execution_events (event_id, organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
VALUES ('eeeeeeee-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','dddddddd-0000-0000-0000-000000000001','cccccccc-cccc-cccc-cccc-ccccccccccc2',1,1, now(), jsonb_build_object('schema_version',1,'pid',4242), repeat('e1',32))
ON CONFLICT DO NOTHING;
SELECT event_id, fingerprint = repeat('e1',32) AS fingerprint_igual_ao_reenviado, payload FROM execution_events WHERE event_id = 'eeeeeeee-0000-0000-0000-000000000001';
INSERT INTO logs (log_id, organization_id, workspace_id, agent_id, worker_agent_assignment_id, execution_id, channel, occurred_at, payload, fingerprint)
VALUES ('ffffffff-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','cccccccc-cccc-cccc-cccc-ccccccccccc2','cccccccc-cccc-cccc-cccc-ccccccccccc3','dddddddd-0000-0000-0000-000000000001',2, now(), jsonb_build_object('schema_version',1,'message','ola nekkora'), repeat('f1',32))
ON CONFLICT DO NOTHING;
SELECT log_id, fingerprint = repeat('f1',32) AS fingerprint_igual_ao_reenviado, payload FROM logs WHERE log_id = 'ffffffff-0000-0000-0000-000000000001';
\echo === reenvio divergente, mesmo event_id com fingerprint e payload diferentes: 0 linhas, original preservado
INSERT INTO execution_events (event_id, organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
VALUES ('eeeeeeee-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','dddddddd-0000-0000-0000-000000000001','cccccccc-cccc-cccc-cccc-ccccccccccc2',1,1, now(), jsonb_build_object('schema_version',1,'pid',9999), repeat('99',32))
ON CONFLICT DO NOTHING;
SELECT event_id, fingerprint = repeat('99',32) AS divergente_sobrescreveu, payload FROM execution_events WHERE event_id = 'eeeeeeee-0000-0000-0000-000000000001';
\echo === mesma sequence com outro event_id, conflito da chave natural: 0 linhas
INSERT INTO execution_events (event_id, organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
VALUES ('eeeeeeee-0000-0000-0000-00000000000f','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','dddddddd-0000-0000-0000-000000000001','cccccccc-cccc-cccc-cccc-ccccccccccc2',1,1, now(), jsonb_build_object('schema_version',1,'pid',4242), repeat('e1',32))
ON CONFLICT DO NOTHING;
\echo === origem invalida: agent diferente da alocacao historica
INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
VALUES ('11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','dddddddd-0000-0000-0000-000000000001','cccccccc-cccc-cccc-cccc-ccccccccccc9',3,2, now(), jsonb_build_object('schema_version',1,'exit_code',0), repeat('e3',32));
\echo === estado final
SELECT sequence, event_type, fingerprint, payload FROM execution_events WHERE execution_id = 'dddddddd-0000-0000-0000-000000000001' ORDER BY sequence;
SELECT log_id, channel, worker_agent_assignment_id, payload FROM logs ORDER BY log_id;
SELECT (SELECT count(*) FROM execution_events) AS eventos, (SELECT count(*) FROM logs) AS logs;
