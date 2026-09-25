-- T07 - papel: nekkora_app (conectado diretamente, nunca via superusuario)
\pset pager off
SELECT current_user AS papel, session_user;
\echo === insercao valida continua funcionando
INSERT INTO execution_events (event_id, organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
VALUES ('eeeeeeee-0000-0000-0000-000000000007','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','dddddddd-0000-0000-0000-000000000001','cccccccc-cccc-cccc-cccc-ccccccccccc2',7,5, now(), jsonb_build_object('schema_version',1,'process_started',false), repeat('e7',32));
INSERT INTO logs (log_id, organization_id, agent_id, channel, occurred_at, payload, fingerprint)
VALUES ('ffffffff-0000-0000-0000-000000000007','11111111-1111-1111-1111-111111111111','cccccccc-cccc-cccc-cccc-ccccccccccc2',1, now(), jsonb_build_object('schema_version',1,'message','t07','level',1), repeat('f7',32));
\echo === execution_events: UPDATE / DELETE / TRUNCATE
UPDATE execution_events SET fingerprint = repeat('99',32) WHERE sequence = 1;
DELETE FROM execution_events WHERE sequence = 1;
TRUNCATE execution_events;
\echo === logs: UPDATE / DELETE / TRUNCATE
UPDATE logs SET payload = NULL WHERE log_id = 'ffffffff-0000-0000-0000-000000000001';
DELETE FROM logs WHERE log_id = 'ffffffff-0000-0000-0000-000000000001';
TRUNCATE logs;
\echo === INSERT ja expurgado (payload NULL + campos de expurgo)
INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint, purged_at, purged_by_user_id, purge_run_id)
VALUES ('11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','dddddddd-0000-0000-0000-000000000001','cccccccc-cccc-cccc-cccc-ccccccccccc2',8,1, now(), NULL, repeat('e8',32), now(), '55555555-1111-1111-1111-111111111111', gen_random_uuid());
\echo === organization_usage_daily
UPDATE organization_usage_daily SET status = 2 WHERE resource_code = 5;
INSERT INTO organization_usage_daily (organization_id, resource_code, usage_date, records_count, bytes_count, status, consolidated_at)
VALUES ('11111111-1111-1111-1111-111111111111', 5, '2026-09-01', 0, 0, 2, now());
DELETE FROM organization_usage_daily;
\echo === outras tabelas da baseline sem GRANT
UPDATE executions SET error_summary = 'x' WHERE execution_id = 'dddddddd-0000-0000-0000-000000000002';
SELECT count(*) FROM organizations;
\echo === escalonamento
CREATE TABLE public.tentativa_intrusa (id INT);
SET ROLE nekkora_retention_owner;
SET ROLE nekkora;
SELECT close_usage_day('11111111-1111-1111-1111-111111111111', 5, '2026-09-24');
SELECT purge_expired_records('11111111-1111-1111-1111-111111111111', 1, '55555555-1111-1111-1111-111111111111', 10);
\echo === contagens finais
SELECT (SELECT count(*) FROM execution_events) AS eventos, (SELECT count(*) FROM logs) AS logs;
