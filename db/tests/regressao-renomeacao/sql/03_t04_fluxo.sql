-- T04 - fluxo principal (papel: nekkora, administrativo - nenhum papel restrito da baseline
-- tem privilegio sobre estas tabelas; separacao de privilegios pertence a migration de seguranca).
\pset pager off
\set ON_ERROR_STOP on
SELECT current_user AS papel_t04;

-- Organizacao interna (autor do provisionamento operacional)
INSERT INTO organizations (organization_id, name, status) VALUES ('99999999-1111-1111-1111-111111111111','Nekkora Internal',1);
INSERT INTO users (user_id, organization_id, account_type, email, status, display_name)
VALUES ('55555555-1111-1111-1111-111111111111','99999999-1111-1111-1111-111111111111',1,'staff@nekkora.internal',1,'Nekkora Staff');

-- Organizacao cliente + cinco limites explicitos, mesma transacao (bootstrap descrito na 010)
BEGIN;
INSERT INTO organizations (organization_id, name, status) VALUES ('11111111-1111-1111-1111-111111111111','Acme Corp',1);
INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, limit_mode, limit_value, provisioned_by_user_id) VALUES
  ('11111111-1111-1111-1111-111111111111', 1, 1, 3,    '55555555-1111-1111-1111-111111111111'),  -- WORKERS_REGISTERED   FINITE 3
  ('11111111-1111-1111-1111-111111111111', 2, 1, 2,    '55555555-1111-1111-1111-111111111111'),  -- CONCURRENT_EXECUTIONS FINITE 2
  ('11111111-1111-1111-1111-111111111111', 3, 1, 5,    '55555555-1111-1111-1111-111111111111'),  -- PENDING_EXECUTIONS   FINITE 5
  ('11111111-1111-1111-1111-111111111111', 4, 2, NULL, '55555555-1111-1111-1111-111111111111'),  -- LOG_BYTES_DAILY      UNLIMITED
  ('11111111-1111-1111-1111-111111111111', 5, 2, NULL, '55555555-1111-1111-1111-111111111111');  -- EVENT_BYTES_DAILY    UNLIMITED
COMMIT;

-- Identidade e vinculo
INSERT INTO users (user_id, organization_id, account_type, email, status, display_name)
VALUES ('33333333-3333-3333-3333-333333333333','11111111-1111-1111-1111-111111111111',1,'admin@acme.com',1,'Admin Acme');
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status)
VALUES ('44444444-4444-4444-4444-444444444444','11111111-1111-1111-1111-111111111111','33333333-3333-3333-3333-333333333333',1,1);

-- Workspace
INSERT INTO workspaces (workspace_id, organization_id, name, status) VALUES ('22222222-2222-2222-2222-222222222222','11111111-1111-1111-1111-111111111111','WS Acme',1);

-- GUC renomeado da 008 (nekkora.actor_membership_id): escritor + leitor (trigger de auditoria)
BEGIN;
SET LOCAL nekkora.actor_membership_id = '44444444-4444-4444-4444-444444444444';
INSERT INTO workspace_parameters (parameter_id, organization_id, workspace_id, name, status, created_by_membership_id)
VALUES ('77777777-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','T04_PARAM',1,'44444444-4444-4444-4444-444444444444');
COMMIT;
SELECT event_type, performed_by_membership_id FROM workspace_configuration_events WHERE parameter_id = '77777777-0000-0000-0000-000000000001';

-- Worker / Agent / associacoes
INSERT INTO workers (worker_id, organization_id, name, status) VALUES ('cccccccc-cccc-cccc-cccc-ccccccccccc1','11111111-1111-1111-1111-111111111111','Worker 1',1);
INSERT INTO agents (agent_id, organization_id, name, agent_version, status) VALUES ('cccccccc-cccc-cccc-cccc-ccccccccccc2','11111111-1111-1111-1111-111111111111','Agent 1','1.0.0',2);
INSERT INTO worker_agent_assignments (assignment_id, organization_id, worker_id, agent_id, status, assigned_by_membership_id)
VALUES ('cccccccc-cccc-cccc-cccc-ccccccccccc3','11111111-1111-1111-1111-111111111111','cccccccc-cccc-cccc-cccc-ccccccccccc1','cccccccc-cccc-cccc-cccc-ccccccccccc2',1,'44444444-4444-4444-4444-444444444444');
INSERT INTO worker_workspace_assignments (assignment_id, organization_id, worker_id, workspace_id, status, authorized_by_membership_id)
VALUES ('cccccccc-cccc-cccc-cccc-ccccccccccc4','11111111-1111-1111-1111-111111111111','cccccccc-cccc-cccc-cccc-ccccccccccc1','22222222-2222-2222-2222-222222222222',1,'44444444-4444-4444-4444-444444444444');

-- Automacao + versao publicada
INSERT INTO automations (automation_id, organization_id, workspace_id, name, status, created_by_membership_id)
VALUES ('55555555-5555-5555-5555-555555555555','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','Automacao 1',1,'44444444-4444-4444-4444-444444444444');
INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id)
VALUES ('66666666-6666-6666-6666-666666666601','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','55555555-5555-5555-5555-555555555555',1,
jsonb_build_object('contract_version',1,'artifact',jsonb_build_object('reference','v1','sha256',repeat('a',64)),'runtime','python3.11','entrypoint','main.py','arguments','[]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb),
'44444444-4444-4444-4444-444444444444');
UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '44444444-4444-4444-4444-444444444444'
WHERE automation_version_id = '66666666-6666-6666-6666-666666666601';

-- Execucao manual: criar (PENDING) -> reservar (STARTING) -> iniciar (RUNNING) -> finalizar (SUCCEEDED)
INSERT INTO executions (execution_id, organization_id, workspace_id, automation_id, automation_version_id, request_key, request_fingerprint, requested_by_membership_id, command_snapshot, status, origin)
SELECT 'dddddddd-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','55555555-5555-5555-5555-555555555555','66666666-6666-6666-6666-666666666601','req-t04',repeat('a',64),'44444444-4444-4444-4444-444444444444', command_json, 1, 1
FROM automation_versions WHERE automation_version_id = '66666666-6666-6666-6666-666666666601';

UPDATE executions SET status = 2, worker_id = 'cccccccc-cccc-cccc-cccc-ccccccccccc1', worker_agent_assignment_id = 'cccccccc-cccc-cccc-cccc-ccccccccccc3', worker_workspace_assignment_id = 'cccccccc-cccc-cccc-cccc-ccccccccccc4', reserved_at = clock_timestamp()
WHERE execution_id = 'dddddddd-0000-0000-0000-000000000001';

UPDATE executions SET status = 3, dispatched_at = clock_timestamp(), started_at = clock_timestamp(), pid = 4242
WHERE execution_id = 'dddddddd-0000-0000-0000-000000000001';

SELECT execution_id, status, origin, worker_id IS NOT NULL AS alocada, reserved_at <= started_at AS ordem_ok FROM executions WHERE execution_id = 'dddddddd-0000-0000-0000-000000000001';
SELECT 't04 setup ok (execucao em RUNNING)' AS marker;
