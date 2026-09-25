-- 11a - papel: nekkora. PREPARACAO ARTIFICIAL DE DADOS HISTORICOS, so na base isolada de teste.
-- Mesmo procedimento aprovado na regressao da renomeacao: uma transacao desabilita apenas as duas
-- triggers que atribuem received_at, insere registros com received_at em D = 2026-09-10 (A: 3
-- eventos, B: 2 eventos), antecipa as constraints adiadas, reabilita as triggers e so entao comita.
-- Objetivo: testar close_usage_day sob RLS com registros de DUAS organizacoes no mesmo dia (risco de
-- fechamento zerado ou misturado por filtragem silenciosa - preparacao de seguranca, secao 4.1).
\pset pager off
\set ON_ERROR_STOP on
SELECT current_user AS papel, current_setting('cluster_name') AS cluster_name;
DO $$
BEGIN
    IF current_setting('cluster_name') IS DISTINCT FROM 'nekkora-regressao-isolada' THEN
        RAISE EXCEPTION 'recusado: preparacao historica so roda na base isolada de teste (cluster_name=%)', current_setting('cluster_name');
    END IF;
END $$;
BEGIN;
ALTER TABLE execution_events DISABLE TRIGGER trg_execution_events_enforce_agent_origin;
INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, received_at, payload, fingerprint) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0003-000000000001','aaaaaaaa-0000-0000-000a-000000000001','aaaaaaaa-0000-0000-0005-000000000001',101,1,'2026-09-10 08:00:00+00','2026-09-10 08:00:01+00','{"schema_version":1,"pid":101}',repeat('h1',32)),
 ('aaaaaaaa-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0003-000000000001','aaaaaaaa-0000-0000-000a-000000000001','aaaaaaaa-0000-0000-0005-000000000001',102,3,'2026-09-10 12:00:00+00','2026-09-10 12:00:01+00','{"schema_version":1,"exit_code":2,"error_summary":"falha A"}',repeat('h2',32)),
 ('aaaaaaaa-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0003-000000000001','aaaaaaaa-0000-0000-000a-000000000001','aaaaaaaa-0000-0000-0005-000000000001',103,2,'2026-09-10 23:59:59+00','2026-09-10 23:59:59.9+00','{"schema_version":1,"exit_code":0}',repeat('h3',32)),
 ('bbbbbbbb-0000-0000-0000-00000000000b','bbbbbbbb-0000-0000-0003-000000000001','bbbbbbbb-0000-0000-000a-000000000001','bbbbbbbb-0000-0000-0005-000000000001',101,1,'2026-09-10 09:00:00+00','2026-09-10 09:00:01+00','{"schema_version":1,"pid":202}',repeat('i1',32)),
 ('bbbbbbbb-0000-0000-0000-00000000000b','bbbbbbbb-0000-0000-0003-000000000001','bbbbbbbb-0000-0000-000a-000000000001','bbbbbbbb-0000-0000-0005-000000000001',102,2,'2026-09-10 10:00:00+00','2026-09-10 10:00:01+00','{"schema_version":1,"exit_code":0}',repeat('i2',32));
SET CONSTRAINTS ALL IMMEDIATE;
ALTER TABLE execution_events ENABLE TRIGGER trg_execution_events_enforce_agent_origin;
COMMIT;
SELECT tgenabled, count(*) AS triggers FROM pg_trigger WHERE NOT tgisinternal GROUP BY 1;
SELECT organization_id, count(*), sum(octet_length(payload::text)) AS bytes FROM execution_events
WHERE (received_at AT TIME ZONE 'UTC')::date = '2026-09-10' GROUP BY 1 ORDER BY 1;
