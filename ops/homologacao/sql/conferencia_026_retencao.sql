-- Conferencia da contabilizacao e do expurgo dos eventos do Agent DEPOIS da 026 (janela_026.sh, fase D), SO em laboratorio
-- ensaio-*, como admin_cluster, sobre a massa de sql/massa_026_canal.sql. D-RET-1: o expurgo fica DESLIGADO fora do
-- laboratorio; aqui a politica e exercitada (EXECUTE de purge_expired_records continua SEM concessao: o admin do cluster chama a
-- funcao do dono). O passado (received_at e vigencia da politica) e escrito com session_replication_role=replica, SO na massa.
-- Saida: linhas chave=valor, conferidas pelo script.
\set ON_ERROR_STOP 1
\set org '00000000-0026-4000-8000-000000000001'
\set run '00000000-0026-4000-8000-00000000000d'

-- 1. contabilizacao: dois eventos novos pela ingestao recebem received_at sob a barreira do recurso 6 (o enviado e ignorado)
BEGIN;
SET LOCAL ROLE nekkora_ingestion;
SELECT set_config('nekkora.organization_id', :'org', true);
INSERT INTO agent_run_events (organization_id, agent_run_id, sequence, content_sha256, envelope, source, event_code, severity, category, step_id, observed_utc, received_at)
  SELECT :'org', :'run', g, encode(sha256(convert_to('lab-026-' || g, 'UTF8')), 'hex'),
         jsonb_build_object('sequence', g, 'event_code', 'STEP_COMPLETED', 'step_id', 'passo_' || g), 1, 'STEP_COMPLETED', 1, 2, 'passo_' || g, now(), '2000-01-01T00:00:00Z'
  FROM generate_series(7, 8) g;
UPDATE agent_runs SET events_stored = 8, stored_through = 8, settled_through = 8, max_sequence = 8 WHERE agent_run_id = :'run';
COMMIT;
SELECT 'barreira_recurso_6_hoje=' || count(*) FROM organization_usage_daily
  WHERE organization_id = :'org' AND resource_code = 6 AND usage_date = (clock_timestamp() AT TIME ZONE 'UTC')::date AND status = 1;
SELECT 'novos_recebidos_hoje=' || count(*) FROM agent_run_events
  WHERE agent_run_id = :'run' AND sequence IN (7, 8) AND received_at > now() - interval '1 hour';

-- 2. fixture do passado: os 6 eventos anteriores a 026 recebidos ha 5 dias; politica finita de 1 dia (categoria 3) vigente desde antes
SELECT ((clock_timestamp() AT TIME ZONE 'UTC')::date - 5)::text AS dia \gset
BEGIN;
SET LOCAL session_replication_role = replica;
UPDATE agent_run_events SET received_at = (:'dia'::date + time '12:00') AT TIME ZONE 'UTC' + sequence * interval '1 second'
  WHERE agent_run_id = :'run' AND sequence BETWEEN 1 AND 6;
INSERT INTO organization_retention_policy_revisions (organization_id, category, revision_number, effective_from, retention_mode, retention_days, provisioned_by_user_id)
  VALUES (:'org', 3, 1, now() - interval '30 days', 1, 1, '00000000-0026-4000-8000-000000000002');
COMMIT;

-- 3. fechamento real do dia pelo papel executor
BEGIN; SET LOCAL ROLE nekkora_retention_executor; SELECT public.close_usage_day(:'org', 6, :'dia'::date); COMMIT;
SELECT 'fechamento=' || records_count || '|' || (bytes_count = (SELECT sum(octet_length(envelope::text)) FROM agent_run_events WHERE agent_run_id = :'run' AND sequence BETWEEN 1 AND 6)) || '|' || status
  FROM organization_usage_daily WHERE organization_id = :'org' AND resource_code = 6 AND usage_date = :'dia'::date;

-- 4. retratos antes do expurgo: identidade + hash + classificacao + instantes; projecao da entrega (ACK)
SELECT md5(string_agg(sequence || ':' || content_sha256 || ':' || event_code || ':' || observed_utc || ':' || received_at, ',' ORDER BY sequence)) AS ident_antes
  FROM agent_run_events WHERE agent_run_id = :'run' \gset
SELECT md5(row(r.*)::text) AS run_antes, stored_through || '|' || settled_through AS ack_antes FROM agent_runs r WHERE agent_run_id = :'run' \gset

-- 5. expurgo da categoria 3 em lotes (4, 100, 100): so os 6 do dia fechado
SELECT public.purge_expired_records(:'org', 3, '00000000-0026-4000-8000-000000000002', 4) AS p1 \gset
SELECT public.purge_expired_records(:'org', 3, '00000000-0026-4000-8000-000000000002', 100) AS p2 \gset
SELECT public.purge_expired_records(:'org', 3, '00000000-0026-4000-8000-000000000002', 100) AS p3 \gset
SELECT 'expurgo_lotes=' || :'p1' || ',' || :'p2' || ',' || :'p3';

-- 6. conferencias
SELECT 'redigidos=' || count(*) FILTER (WHERE purged_at IS NOT NULL AND envelope IS NULL AND step_id IS NULL)
       || '|intactos=' || count(*) FILTER (WHERE purged_at IS NULL AND envelope IS NOT NULL) FROM agent_run_events WHERE agent_run_id = :'run';
SELECT 'identidade_hash_instantes_iguais=' || (md5(string_agg(sequence || ':' || content_sha256 || ':' || event_code || ':' || observed_utc || ':' || received_at, ',' ORDER BY sequence)) = :'ident_antes')
  FROM agent_run_events WHERE agent_run_id = :'run';
SELECT 'agent_runs_igual=' || (md5(row(r.*)::text) = :'run_antes') || '|ack=' || stored_through || '|' || settled_through || '|ack_antes=' || :'ack_antes'
  FROM agent_runs r WHERE agent_run_id = :'run';
-- a mesma regra das consultas da Platform (AgentRunReads): retencao em dimensao propria; a completude nao muda
SELECT 'retencao=' || CASE WHEN count(*) FILTER (WHERE purged_at IS NOT NULL) = 0 THEN 'AVAILABLE'
                           WHEN count(*) FILTER (WHERE purged_at IS NOT NULL) = count(*) THEN 'PURGED' ELSE 'PARTIALLY_PURGED' END
       || '|' || count(*) FILTER (WHERE purged_at IS NOT NULL) FROM agent_run_events WHERE agent_run_id = :'run';
SELECT 'auditoria=' || string_agg(records_purged::text, ',' ORDER BY occurred_at) FROM organization_purge_runs WHERE organization_id = :'org' AND category = 3;
SELECT 'execute_purge_concedido=' || count(*) FROM pg_roles r
  WHERE NOT r.rolsuper AND r.rolname <> 'nekkora_retention_owner' AND has_function_privilege(r.oid, 'public.purge_expired_records(uuid,integer,uuid,integer)', 'EXECUTE');
-- 7. reenvio do mesmo (run, sequence) depois do expurgo nao duplica nem desfaz a redacao
BEGIN;
SET LOCAL ROLE nekkora_ingestion;
SELECT set_config('nekkora.organization_id', :'org', true);
INSERT INTO agent_run_events (organization_id, agent_run_id, sequence, content_sha256, envelope, source, event_code, severity, category, step_id, observed_utc)
  VALUES (:'org', :'run', 2, encode(sha256(convert_to('lab-026-2', 'UTF8')), 'hex'), '{"sequence": 2, "event_code": "STEP_COMPLETED", "step_id": "passo_2"}',
          1, 'STEP_COMPLETED', 1, 2, 'passo_2', now()) ON CONFLICT (agent_run_id, sequence) DO NOTHING;
COMMIT;
SELECT 'reenvio=' || count(*) || '|' || bool_and(purged_at IS NOT NULL) FROM agent_run_events WHERE agent_run_id = :'run' AND sequence = 2;
