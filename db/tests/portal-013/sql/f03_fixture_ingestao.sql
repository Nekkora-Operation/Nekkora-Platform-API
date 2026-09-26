-- f03 - papel: nekkora_ingestion. Eventos e logs das execucoes alocadas de O1, pela barreira real
-- (received_at atribuido pelo servidor). O evento de falha de e13 leva um error_summary-marcador no
-- payload e os logs levam mensagens-marcador: nada disso pode chegar ao portal.
-- Em e15 o evento de sequence 2 e gravado ANTES do de sequence 1 (received_at invertido), para provar
-- que o portal ordena por sequence, nao por received_at.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_ingestion', 'papel nekkora_ingestion');
\set ag '10000000-0000-0005-0000-000000000001'
\set waa '10000000-0000-0006-0000-000000000001'
BEGIN;
SET LOCAL nekkora.organization_id = :'o1';
INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
VALUES (:'o1', :'ws1', :'e12', :'ag', 1, 1, now(), '{"schema_version":1,"pid":4242}', repeat('c1', 32)),
       (:'o1', :'ws1', :'e13', :'ag', 1, 1, now(), '{"schema_version":1,"pid":4242}', repeat('c2', 32)),
       (:'o1', :'ws1', :'e13', :'ag', 2, 3, now(),
        '{"schema_version":1,"exit_code":97,"error_summary":"MARCADOR_PAYLOAD_ERRO"}', repeat('c3', 32)),
       (:'o1', :'ws1', :'e15', :'ag', 2, 2, now(), '{"schema_version":1,"exit_code":0}', repeat('c5', 32));
COMMIT;
BEGIN;
SET LOCAL nekkora.organization_id = :'o1';
INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
VALUES (:'o1', :'ws1', :'e15', :'ag', 1, 1, now(), '{"schema_version":1,"pid":4242}', repeat('c4', 32)),
       (:'o1', :'ws2', :'e21', :'ag', 1, 1, now(), '{"schema_version":1,"pid":4242}', repeat('c6', 32)),
       (:'o1', :'ws2', :'e21', :'ag', 2, 2, now(), '{"schema_version":1,"exit_code":0}', repeat('c8', 32)),
       (:'o1', :'ws3', :'e31', :'ag', 1, 1, now(), '{"schema_version":1,"pid":4242}', repeat('c7', 32));
INSERT INTO logs (organization_id, workspace_id, agent_id, worker_agent_assignment_id, execution_id, channel, occurred_at, payload, fingerprint)
VALUES (:'o1', :'ws1', :'ag', :'waa', :'e13', 3, now(), '{"schema_version":1,"message":"MARCADOR_LOG_STDERR"}', repeat('d1', 32)),
       (:'o1', :'ws1', :'ag', :'waa', :'e13', 2, now(), '{"schema_version":1,"message":"MARCADOR_LOG_STDOUT"}', repeat('d2', 32)),
       (:'o1', NULL,   :'ag', :'waa', NULL,   1, now(), '{"schema_version":1,"message":"MARCADOR_LOG_AGENT","level":4}', repeat('d3', 32));
SELECT pg_temp.afirma((SELECT count(*) FROM execution_events) = 8, '8 eventos em O1');
SELECT pg_temp.afirma((SELECT count(*) FROM logs) = 3, '3 logs em O1');
COMMIT;
