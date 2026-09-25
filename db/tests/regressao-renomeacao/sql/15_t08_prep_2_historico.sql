-- Preparacao T08/concorrencia (2/2) - PREPARACAO ARTIFICIAL DE DADOS HISTORICOS.
-- Papel: nekkora (administrativo), SOMENTE na base isolada nekkora-regressao.
-- D  = 2026-09-24 (ontem, UTC) -> T08
-- D2 = 2026-09-23             -> concorrencia
-- Uma unica transacao: linhas-barreira OPEN (trigger de nascimento ativa), desabilita apenas as
-- duas triggers BEFORE INSERT que atribuem received_at, insere com received_at historico,
-- reabilita as triggers e so entao faz COMMIT. Nenhuma migration e alterada.
\pset pager off
\set ON_ERROR_STOP on
SELECT current_user AS papel, current_setting('cluster_name') AS cluster_name;
-- TRAVA: este script desabilita triggers de producao dentro da propria transacao. So roda num
-- servidor iniciado pelo run.sh (compose.regressao.yml define cluster_name=nekkora-regressao-isolada).
-- Na base principal cluster_name e vazio -> RAISE -> ON_ERROR_STOP aborta antes de qualquer escrita.
DO $$
BEGIN
    IF current_setting('cluster_name') IS DISTINCT FROM 'nekkora-regressao-isolada' THEN
        RAISE EXCEPTION 'recusado: preparacao historica so roda na base isolada de regressao (cluster_name=%)', current_setting('cluster_name');
    END IF;
END $$;
BEGIN;
INSERT INTO organization_usage_daily (organization_id, resource_code, usage_date, records_count, bytes_count, status, consolidated_at) VALUES
  ('11111111-1111-1111-1111-111111111111', 5, '2026-09-24', 0, 0, 1, NULL),
  ('11111111-1111-1111-1111-111111111111', 4, '2026-09-24', 0, 0, 1, NULL),
  ('11111111-1111-1111-1111-111111111111', 5, '2026-09-23', 0, 0, 1, NULL),
  ('11111111-1111-1111-1111-111111111111', 4, '2026-09-23', 0, 0, 1, NULL);

ALTER TABLE execution_events DISABLE TRIGGER trg_execution_events_enforce_agent_origin;
ALTER TABLE logs DISABLE TRIGGER trg_logs_enforce_historical_origin;

-- D2 = 2026-09-23: 2 eventos + 1 log
INSERT INTO execution_events (event_id, organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, received_at, payload, fingerprint) VALUES
 ('eeeeeeee-2023-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','dddddddd-0000-0000-0000-000000000003','cccccccc-cccc-cccc-cccc-ccccccccccc2',1,1,'2026-09-23 10:00:00+00','2026-09-23 10:00:01+00', jsonb_build_object('schema_version',1,'pid',5151), repeat('a1',32)),
 ('eeeeeeee-2023-0000-0000-000000000002','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','dddddddd-0000-0000-0000-000000000003','cccccccc-cccc-cccc-cccc-ccccccccccc2',2,3,'2026-09-23 23:59:58+00','2026-09-23 23:59:59.5+00', jsonb_build_object('schema_version',1,'exit_code',1,'error_summary','falha simulada em D2'), repeat('a2',32));
INSERT INTO logs (log_id, organization_id, workspace_id, agent_id, worker_agent_assignment_id, execution_id, channel, occurred_at, received_at, payload, fingerprint) VALUES
 ('ffffffff-2023-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','cccccccc-cccc-cccc-cccc-ccccccccccc2','cccccccc-cccc-cccc-cccc-ccccccccccc5','dddddddd-0000-0000-0000-000000000003',2,'2026-09-23 10:00:02+00','2026-09-23 10:00:03+00', jsonb_build_object('schema_version',1,'message','stdout em D2'), repeat('b1',32));

-- D = 2026-09-24: 3 eventos + 2 logs (inclui limites do dia: 00:00:00 e 23:59:59.999999)
INSERT INTO execution_events (event_id, organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, received_at, payload, fingerprint) VALUES
 ('eeeeeeee-2024-0000-0000-000000000003','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','dddddddd-0000-0000-0000-000000000003','cccccccc-cccc-cccc-cccc-ccccccccccc2',3,1,'2026-09-23 23:59:59+00','2026-09-24 00:00:00+00', jsonb_build_object('schema_version',1,'pid',5152), repeat('a3',32)),
 ('eeeeeeee-2024-0000-0000-000000000004','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','dddddddd-0000-0000-0000-000000000003','cccccccc-cccc-cccc-cccc-ccccccccccc2',4,4,'2026-09-24 12:00:00+00','2026-09-24 12:00:01+00', jsonb_build_object('schema_version',1,'error_code','E_START','error_summary','ação não iniciou'), repeat('a4',32)),
 ('eeeeeeee-2024-0000-0000-000000000005','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','dddddddd-0000-0000-0000-000000000003','cccccccc-cccc-cccc-cccc-ccccccccccc2',5,2,'2026-09-24 23:59:59+00','2026-09-24 23:59:59.999999+00', jsonb_build_object('schema_version',1,'exit_code',0), repeat('a5',32));
INSERT INTO logs (log_id, organization_id, workspace_id, agent_id, worker_agent_assignment_id, execution_id, channel, occurred_at, received_at, payload, fingerprint) VALUES
 ('ffffffff-2024-0000-0000-000000000002','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','cccccccc-cccc-cccc-cccc-ccccccccccc2','cccccccc-cccc-cccc-cccc-ccccccccccc5','dddddddd-0000-0000-0000-000000000003',3,'2026-09-24 12:00:02+00','2026-09-24 12:00:03+00', jsonb_build_object('schema_version',1,'message','stderr em D — acentuação multibyte'), repeat('b2',32)),
 ('ffffffff-2024-0000-0000-000000000003','11111111-1111-1111-1111-111111111111',NULL,'cccccccc-cccc-cccc-cccc-ccccccccccc2','cccccccc-cccc-cccc-cccc-ccccccccccc5',NULL,1,'2026-09-24 08:00:00+00','2026-09-24 08:00:00+00', jsonb_build_object('schema_version',1,'message','agent heartbeat D','level',2), repeat('b3',32));

-- A FK de expurgo (011) e DEFERRABLE INITIALLY DEFERRED: sem isto o ENABLE TRIGGER falha com
-- "pending trigger events" (1a tentativa, desfeita por inteiro). Antecipa as checagens, nao as dispensa.
SET CONSTRAINTS ALL IMMEDIATE;
ALTER TABLE execution_events ENABLE TRIGGER trg_execution_events_enforce_agent_origin;
ALTER TABLE logs ENABLE TRIGGER trg_logs_enforce_historical_origin;
COMMIT;
SELECT 'preparacao historica committed' AS marker, clock_timestamp();
