-- Massa DECLARADA para a homologacao dirigida da 027 (janela_027.sh), SO em laboratorio ensaio-*, como admin_cluster ANTES
-- da 027 (base em 026, com a massa de sql/massa_026_canal.sql). Na organizacao 00000000-0026-4000-8000-000000000001: 5
-- execucoes pelas regras da 006 (pendente -> reservada -> em execucao -> concluida), cada uma com um evento (categoria 1), e 5
-- logs do canal AGENT (categoria 2), gravados com o contexto da organizacao (barreiras dos recursos 5 e 4). Depois, o passado:
-- recebidos ha 4 dias (session_replication_role=replica SO para escrever received_at e a vigencia da politica), politica finita
-- de 1 dia nas categorias 1 e 2 e o fechamento REAL dos dois dias pelo papel executor. Nada aqui e dado real.
\set ON_ERROR_STOP 1
\set org '00000000-0026-4000-8000-000000000001'
BEGIN;
SELECT set_config('nekkora.organization_id', :'org', true);
DO $massa$
DECLARE
    e UUID;
BEGIN
    FOR k IN 1..5 LOOP
        e := ('00000000-0027-4000-8000-0000000000' || lpad(k::text, 2, '0'))::uuid;
        INSERT INTO executions (execution_id, organization_id, workspace_id, automation_id, automation_version_id, request_key, request_fingerprint,
                                requested_by_membership_id, command_snapshot, status, origin)
        SELECT e, '00000000-0026-4000-8000-000000000001', '00000000-0026-4000-8000-000000000004', '00000000-0026-4000-8000-000000000005',
               '00000000-0026-4000-8000-000000000006', 'req-lab-027-' || k, repeat('b', 64), '00000000-0026-4000-8000-000000000003', v.command_json, 1, 1
        FROM automation_versions v WHERE v.automation_version_id = '00000000-0026-4000-8000-000000000006';
        UPDATE executions SET status = 2, worker_id = '00000000-0026-4000-8000-000000000007', worker_agent_assignment_id = '00000000-0026-4000-8000-000000000009',
                              worker_workspace_assignment_id = '00000000-0026-4000-8000-00000000000a', reserved_at = clock_timestamp() WHERE execution_id = e;
        UPDATE executions SET status = 3, dispatched_at = clock_timestamp(), started_at = clock_timestamp() WHERE execution_id = e;
        INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
        VALUES ('00000000-0026-4000-8000-000000000001', '00000000-0026-4000-8000-000000000004', e, '00000000-0026-4000-8000-000000000008', 1, 1, now(),
                '{"schema_version":1,"pid":7}', encode(sha256(convert_to(e::text || '1', 'UTF8')), 'hex'));
        UPDATE executions SET status = 4, status_reason = 1, finished_at = clock_timestamp() + interval '1 second', exit_code = 0 WHERE execution_id = e;
    END LOOP;
END
$massa$;
INSERT INTO logs (organization_id, agent_id, channel, occurred_at, payload, fingerprint)
  SELECT :'org', '00000000-0026-4000-8000-000000000008', 1, now(), jsonb_build_object('schema_version', 1, 'message', 'lab 027 ' || g, 'level', 2), 'lab-027-' || g
  FROM generate_series(1, 5) g;
COMMIT;

SELECT ((clock_timestamp() AT TIME ZONE 'UTC')::date - 4)::text AS dia \gset
BEGIN;
SET LOCAL session_replication_role = replica;
UPDATE execution_events SET received_at = (:'dia'::date + time '10:00') AT TIME ZONE 'UTC' + (substr(execution_id::text, 35)::int * interval '1 second')
  WHERE organization_id = :'org' AND execution_id::text LIKE '00000000-0027-%';
UPDATE logs SET received_at = (:'dia'::date + time '11:00') AT TIME ZONE 'UTC' + (substr(fingerprint, 9)::int * interval '1 second')
  WHERE organization_id = :'org' AND fingerprint LIKE 'lab-027-%';
INSERT INTO organization_retention_policy_revisions (organization_id, category, revision_number, effective_from, retention_mode, retention_days, provisioned_by_user_id)
  SELECT :'org', c, coalesce((SELECT max(revision_number) FROM organization_retention_policy_revisions WHERE organization_id = :'org' AND category = c), 0) + 1,
         now() - interval '30 days', 1, 1, '00000000-0026-4000-8000-000000000002' FROM generate_series(1, 2) c;
COMMIT;
BEGIN; SET LOCAL ROLE nekkora_retention_executor; SELECT public.close_usage_day(:'org', 5, :'dia'::date); COMMIT;
BEGIN; SET LOCAL ROLE nekkora_retention_executor; SELECT public.close_usage_day(:'org', 4, :'dia'::date); COMMIT;
SELECT 'elegiveis=' || (SELECT count(*) FROM execution_events WHERE organization_id = :'org' AND (received_at AT TIME ZONE 'UTC')::date = :'dia'::date AND purged_at IS NULL)
       || '|' || (SELECT count(*) FROM logs WHERE organization_id = :'org' AND (received_at AT TIME ZONE 'UTC')::date = :'dia'::date AND purged_at IS NULL);
SELECT 'dias_fechados=' || string_agg(resource_code || ':' || records_count || ':' || status, ',' ORDER BY resource_code)
  FROM organization_usage_daily WHERE organization_id = :'org' AND usage_date = :'dia'::date AND resource_code IN (4, 5);
