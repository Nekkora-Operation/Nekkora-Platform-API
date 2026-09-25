-- 04 - papel: nekkora_platform. Fluxos de produto sob RLS, parametrizado por organizacao.
-- Variaveis definidas pelo run.sh antes deste arquivo: org (uuid da organizacao) e p (prefixo de
-- 8 caracteres dos ids desta organizacao). Toda transacao abre com SET LOCAL do contexto.
\pset pager off
\set ON_ERROR_STOP on
SELECT current_user AS papel, :'org' AS organizacao;

\echo === identidade, credencial, workspace
BEGIN;
SET LOCAL nekkora.organization_id = :'org';
INSERT INTO users (user_id, organization_id, account_type, email, status, display_name)
VALUES ((:'p' || '-0000-0000-0001-000000000001')::uuid, :'org', 1, 'admin@' || :'p' || '.test', 2, 'Admin ' || :'p');
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status)
VALUES ((:'p' || '-0000-0000-0002-000000000001')::uuid, :'org', (:'p' || '-0000-0000-0001-000000000001')::uuid, 1, 2);
INSERT INTO user_credentials (user_id, password_hash)
VALUES ((:'p' || '-0000-0000-0001-000000000001')::uuid, 'hash-de-teste-' || :'p');
INSERT INTO workspaces (workspace_id, organization_id, name, status)
VALUES ((:'p' || '-0000-0000-0003-000000000001')::uuid, :'org', 'WS ' || :'p', 1);
COMMIT;

\echo === parametro e secret (auditoria da 008 via nekkora.actor_membership_id)
BEGIN;
SET LOCAL nekkora.organization_id = :'org';
SELECT set_config('nekkora.actor_membership_id', :'p' || '-0000-0000-0002-000000000001', true) IS NOT NULL AS actor_definido;
INSERT INTO workspace_parameters (parameter_id, organization_id, workspace_id, name, status, created_by_membership_id)
VALUES ((:'p' || '-0000-0000-000c-000000000001')::uuid, :'org', (:'p' || '-0000-0000-0003-000000000001')::uuid, 'PARAM_' || upper(:'p'), 1, (:'p' || '-0000-0000-0002-000000000001')::uuid);
INSERT INTO workspace_secrets (secret_id, organization_id, workspace_id, name, provider_key, provider_reference, created_by_membership_id)
VALUES ((:'p' || '-0000-0000-000d-000000000001')::uuid, :'org', (:'p' || '-0000-0000-0003-000000000001')::uuid, 'SECRET_' || upper(:'p'), 'vault', 'ref/' || :'p', (:'p' || '-0000-0000-0002-000000000001')::uuid);
COMMIT;

\echo === Worker (capacidade 010), Agent, associacoes
BEGIN;
SET LOCAL nekkora.organization_id = :'org';
INSERT INTO workers (worker_id, organization_id, name, status) VALUES ((:'p' || '-0000-0000-0004-000000000001')::uuid, :'org', 'Worker ' || :'p', 1);
INSERT INTO agents (agent_id, organization_id, name, agent_version, status) VALUES ((:'p' || '-0000-0000-0005-000000000001')::uuid, :'org', 'Agent ' || :'p', '1.0.0', 2);
INSERT INTO worker_agent_assignments (assignment_id, organization_id, worker_id, agent_id, status, assigned_by_membership_id)
VALUES ((:'p' || '-0000-0000-0006-000000000001')::uuid, :'org', (:'p' || '-0000-0000-0004-000000000001')::uuid, (:'p' || '-0000-0000-0005-000000000001')::uuid, 1, (:'p' || '-0000-0000-0002-000000000001')::uuid);
INSERT INTO worker_workspace_assignments (assignment_id, organization_id, worker_id, workspace_id, status, authorized_by_membership_id)
VALUES ((:'p' || '-0000-0000-0007-000000000001')::uuid, :'org', (:'p' || '-0000-0000-0004-000000000001')::uuid, (:'p' || '-0000-0000-0003-000000000001')::uuid, 1, (:'p' || '-0000-0000-0002-000000000001')::uuid);
COMMIT;

\echo === automacao e versao publicada
BEGIN;
SET LOCAL nekkora.organization_id = :'org';
INSERT INTO automations (automation_id, organization_id, workspace_id, name, status, created_by_membership_id)
VALUES ((:'p' || '-0000-0000-0008-000000000001')::uuid, :'org', (:'p' || '-0000-0000-0003-000000000001')::uuid, 'Automacao ' || :'p', 1, (:'p' || '-0000-0000-0002-000000000001')::uuid);
INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id)
VALUES ((:'p' || '-0000-0000-0009-000000000001')::uuid, :'org', (:'p' || '-0000-0000-0003-000000000001')::uuid, (:'p' || '-0000-0000-0008-000000000001')::uuid, 1,
  jsonb_build_object('contract_version',1,'artifact',jsonb_build_object('reference','v1','sha256',repeat('a',64)),'runtime','python3.11','entrypoint','main.py','arguments','[]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb),
  (:'p' || '-0000-0000-0002-000000000001')::uuid);
UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = (:'p' || '-0000-0000-0002-000000000001')::uuid
WHERE automation_version_id = (:'p' || '-0000-0000-0009-000000000001')::uuid;
COMMIT;

\echo === execucao manual: criar -> reservar -> iniciar (fica RUNNING para a ingestao)
BEGIN;
SET LOCAL nekkora.organization_id = :'org';
INSERT INTO executions (execution_id, organization_id, workspace_id, automation_id, automation_version_id, request_key, request_fingerprint, requested_by_membership_id, command_snapshot, status, origin)
SELECT (:'p' || '-0000-0000-000a-000000000001')::uuid, :'org', (:'p' || '-0000-0000-0003-000000000001')::uuid, (:'p' || '-0000-0000-0008-000000000001')::uuid,
       automation_version_id, 'req-1', repeat('a', 64), (:'p' || '-0000-0000-0002-000000000001')::uuid, command_json, 1, 1
FROM automation_versions WHERE automation_version_id = (:'p' || '-0000-0000-0009-000000000001')::uuid;
UPDATE executions SET status = 2, worker_id = (:'p' || '-0000-0000-0004-000000000001')::uuid,
       worker_agent_assignment_id = (:'p' || '-0000-0000-0006-000000000001')::uuid,
       worker_workspace_assignment_id = (:'p' || '-0000-0000-0007-000000000001')::uuid, reserved_at = clock_timestamp()
WHERE execution_id = (:'p' || '-0000-0000-000a-000000000001')::uuid;
UPDATE executions SET status = 3, dispatched_at = clock_timestamp(), started_at = clock_timestamp(), pid = 4242
WHERE execution_id = (:'p' || '-0000-0000-000a-000000000001')::uuid;
COMMIT;

\echo === agendamento e revisao (processamento fica com o scheduler)
BEGIN;
SET LOCAL nekkora.organization_id = :'org';
INSERT INTO automation_schedules (schedule_id, organization_id, workspace_id, automation_id, name, status, created_by_membership_id)
VALUES ((:'p' || '-0000-0000-000b-000000000001')::uuid, :'org', (:'p' || '-0000-0000-0003-000000000001')::uuid, (:'p' || '-0000-0000-0008-000000000001')::uuid, 'Agenda ' || :'p', 1, (:'p' || '-0000-0000-0002-000000000001')::uuid);
INSERT INTO automation_schedule_revisions (organization_id, workspace_id, automation_id, schedule_id, revision_number, effective_from, automation_version_id, cron_expression, timezone, late_tolerance_seconds, authorized_by_membership_id, created_by_membership_id)
VALUES (:'org', (:'p' || '-0000-0000-0003-000000000001')::uuid, (:'p' || '-0000-0000-0008-000000000001')::uuid, (:'p' || '-0000-0000-000b-000000000001')::uuid,
        1, now(), (:'p' || '-0000-0000-0009-000000000001')::uuid, '* * * * *', 'America/Sao_Paulo', 3600, (:'p' || '-0000-0000-0002-000000000001')::uuid, (:'p' || '-0000-0000-0002-000000000001')::uuid);
UPDATE automation_schedules SET name = 'Agenda ' || :'p' || ' (renomeada)' WHERE schedule_id = (:'p' || '-0000-0000-000b-000000000001')::uuid;
COMMIT;

\echo === visao do proprio contexto
BEGIN;
SET LOCAL nekkora.organization_id = :'org';
SELECT (SELECT count(*) FROM organizations) AS orgs_visiveis, (SELECT count(*) FROM users) AS users_visiveis,
       (SELECT count(*) FROM user_credentials) AS credenciais_visiveis, (SELECT count(*) FROM workspaces) AS workspaces_visiveis,
       (SELECT count(*) FROM executions) AS execucoes_visiveis, (SELECT count(*) FROM workspace_configuration_events) AS eventos_auditoria_visiveis,
       (SELECT count(*) FROM organization_resource_limit_revisions) AS limites_visiveis;
SELECT DISTINCT organization_id AS unica_org_visivel FROM executions;
SELECT pg_temp.afirma((SELECT count(*) FROM organizations) = 1 AND (SELECT organization_id FROM organizations) = :'org'::uuid, 'contexto ve so a propria organizacao');
SELECT pg_temp.afirma((SELECT count(*) FROM users) = 1 AND (SELECT count(*) FROM user_credentials) = 1
                      AND (SELECT count(*) FROM workspaces) = 1 AND (SELECT count(*) FROM executions) = 1, 'contexto ve 1 usuario, 1 credencial, 1 workspace, 1 execucao');
SELECT pg_temp.afirma((SELECT count(*) FROM workspace_configuration_events) = 2
                      AND (SELECT count(DISTINCT performed_by_membership_id) FROM workspace_configuration_events) = 1, 'auditoria 008: 2 eventos com o ator definido');
SELECT pg_temp.afirma((SELECT status FROM executions) = 3, 'execucao em RUNNING');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM organization_resource_limit_revisions WHERE organization_id <> :'org'::uuid)
                      AND (SELECT count(DISTINCT resource_code) FROM organization_resource_limit_revisions) = 5, 'limites visiveis so da propria organizacao, 5 recursos');
COMMIT;
