-- Massa DECLARADA do canal do Agent para a homologacao dirigida da 026 (janela_026.sh), SO em laboratorio ensaio-*. Executada
-- como admin_cluster ANTES da 026 (base em 025). Identificadores fixos com o prefixo 00000000-0026-4000-8000-: uma organizacao
-- ATIVADA (como as classificadas pela 022; a regra da capacidade zero proibe capacidade em organizacao aguardando ativacao),
-- Worker, Agent, autorizacao local (gravada pelo papel nekkora_platform), registro e 6 eventos (gravados pelo papel
-- nekkora_ingestion, com contexto da organizacao). Nada aqui e dado real; nenhum segredo (o hash da credencial e um marcador).
\set ON_ERROR_STOP 1
BEGIN;
INSERT INTO organizations (organization_id, name, status) VALUES ('00000000-0026-4000-8000-000000000001', 'Org lab 026', 1);
INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES
  ('00000000-0026-4000-8000-000000000002', '00000000-0026-4000-8000-000000000001', 1, 'adm-lab-026@lab.test', now(), 2, 'Admin lab 026');
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES
  ('00000000-0026-4000-8000-000000000003', '00000000-0026-4000-8000-000000000001', '00000000-0026-4000-8000-000000000002', 1, 2);
INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id)
  SELECT '00000000-0026-4000-8000-000000000001', '00000000-0026-4000-8000-000000000003', role_id, 1, 1, '00000000-0026-4000-8000-000000000003'
  FROM roles WHERE code = 'ORG_ADMIN';
INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, limit_value, provisioned_by_user_id)
  SELECT '00000000-0026-4000-8000-000000000001', c, 1, now(), 2, NULL, '00000000-0026-4000-8000-000000000002' FROM generate_series(1, 5) c;
SET LOCAL session_replication_role = replica;
UPDATE ops_activation.organization_states SET state = 2, origin = 2, activated_at = now() WHERE organization_id = '00000000-0026-4000-8000-000000000001';
SET LOCAL session_replication_role = origin;
INSERT INTO workspaces (workspace_id, organization_id, name, status) VALUES ('00000000-0026-4000-8000-000000000004', '00000000-0026-4000-8000-000000000001', 'WS lab', 1);
INSERT INTO automations (automation_id, organization_id, workspace_id, name, status, created_by_membership_id) VALUES
  ('00000000-0026-4000-8000-000000000005', '00000000-0026-4000-8000-000000000001', '00000000-0026-4000-8000-000000000004', 'Biografias', 1, '00000000-0026-4000-8000-000000000003');
INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id) VALUES
  ('00000000-0026-4000-8000-000000000006', '00000000-0026-4000-8000-000000000001', '00000000-0026-4000-8000-000000000004', '00000000-0026-4000-8000-000000000005', 1,
   jsonb_build_object('contract_version', 1, 'artifact', jsonb_build_object('reference', 'p', 'sha256', repeat('ab', 32)),
     'agent_package', jsonb_build_object('fingerprint_rule', 'nekkora-agent/package-fingerprint/1', 'fingerprint', '5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d'),
     'runtime', 'python3.12', 'entrypoint', 'main.py', 'arguments', '[]'::jsonb, 'parameters', '{}'::jsonb, 'secret_bindings', '{}'::jsonb),
   '00000000-0026-4000-8000-000000000003');
UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '00000000-0026-4000-8000-000000000003'
  WHERE automation_version_id = '00000000-0026-4000-8000-000000000006';
INSERT INTO workers (worker_id, organization_id, name, status) VALUES ('00000000-0026-4000-8000-000000000007', '00000000-0026-4000-8000-000000000001', 'W lab', 1);
INSERT INTO agents (agent_id, organization_id, name, agent_version, status) VALUES ('00000000-0026-4000-8000-000000000008', '00000000-0026-4000-8000-000000000001', 'A lab', '0.1.0', 2);
INSERT INTO worker_agent_assignments (assignment_id, organization_id, worker_id, agent_id, status, assigned_by_membership_id) VALUES
  ('00000000-0026-4000-8000-000000000009', '00000000-0026-4000-8000-000000000001', '00000000-0026-4000-8000-000000000007', '00000000-0026-4000-8000-000000000008', 1, '00000000-0026-4000-8000-000000000003');
INSERT INTO worker_workspace_assignments (assignment_id, organization_id, worker_id, workspace_id, status, authorized_by_membership_id) VALUES
  ('00000000-0026-4000-8000-00000000000a', '00000000-0026-4000-8000-000000000001', '00000000-0026-4000-8000-000000000007', '00000000-0026-4000-8000-000000000004', 1, '00000000-0026-4000-8000-000000000003');
INSERT INTO agent_credentials (credential_id, organization_id, agent_id, credential_hash, issued_by_membership_id, expires_at) VALUES
  ('00000000-0026-4000-8000-00000000000b', '00000000-0026-4000-8000-000000000001', '00000000-0026-4000-8000-000000000008', repeat('c', 64), '00000000-0026-4000-8000-000000000003', now() + interval '90 days');
COMMIT;

BEGIN;
SET LOCAL ROLE nekkora_platform;
SELECT set_config('nekkora.organization_id', '00000000-0026-4000-8000-000000000001', true);
INSERT INTO local_run_authorizations (authorization_id, organization_id, workspace_id, worker_id, worker_workspace_assignment_id, automation_id,
    automation_version_id, package_fingerprint, granted_by_membership_id, valid_until) VALUES
  ('00000000-0026-4000-8000-00000000000c', '00000000-0026-4000-8000-000000000001', '00000000-0026-4000-8000-000000000004', '00000000-0026-4000-8000-000000000007',
   '00000000-0026-4000-8000-00000000000a', '00000000-0026-4000-8000-000000000005', '00000000-0026-4000-8000-000000000006',
   '5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d', '00000000-0026-4000-8000-000000000003', now() + interval '30 days');
COMMIT;

BEGIN;
SET LOCAL ROLE nekkora_ingestion;
SELECT set_config('nekkora.organization_id', '00000000-0026-4000-8000-000000000001', true);
INSERT INTO agent_runs (agent_run_id, organization_id, workspace_id, automation_id, automation_version_id, authorization_id, agent_id, worker_id,
    worker_agent_assignment_id, agent_execution_id, package_fingerprint, authorization_state_at_registration, supervisor_state) VALUES
  ('00000000-0026-4000-8000-00000000000d', '00000000-0026-4000-8000-000000000001', '00000000-0026-4000-8000-000000000004', '00000000-0026-4000-8000-000000000005',
   '00000000-0026-4000-8000-000000000006', '00000000-0026-4000-8000-00000000000c', '00000000-0026-4000-8000-000000000008', '00000000-0026-4000-8000-000000000007',
   '00000000-0026-4000-8000-000000000009', 'ex-' || repeat('26', 16), '5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d', 1, 3);
INSERT INTO agent_run_events (organization_id, agent_run_id, sequence, content_sha256, envelope, source, event_code, severity, category, step_id, observed_utc)
  SELECT '00000000-0026-4000-8000-000000000001', '00000000-0026-4000-8000-00000000000d', g, encode(sha256(convert_to('lab-026-' || g, 'UTF8')), 'hex'),
         jsonb_build_object('sequence', g, 'event_code', 'STEP_COMPLETED', 'step_id', 'passo_' || g), 1, 'STEP_COMPLETED', 1, 2, 'passo_' || g, now()
  FROM generate_series(1, 6) g;
UPDATE agent_runs SET events_stored = 6, stored_through = 6, settled_through = 6, max_sequence = 6 WHERE agent_run_id = '00000000-0026-4000-8000-00000000000d';
COMMIT;
SELECT 'massa_026=' || count(*) FROM agent_run_events WHERE agent_run_id = '00000000-0026-4000-8000-00000000000d';
