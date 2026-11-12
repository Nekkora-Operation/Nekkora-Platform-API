-- Massa comum da suite 033 (preparacao como administrador do cluster, NAO evidencia): gatilhos e chaves estrangeiras
-- desligados SO durante a carga (session_replication_role = replica), para montar Workers, autorizacoes do Worker no
-- workspace e autorizacoes locais sem reconstituir contas e papeis reais. As restricoes CHECK continuam valendo.
--   organizacao 1, Worker 1 no workspace 1: pacote da versao 1 VIGENTE (11) + REVOGADA (12) + VENCIDA (13) (historico)
--     + monitoramento da automacao 2 VIGENTE (21); Worker 2: pacote da versao 1 VIGENTE (31, outro escopo)
--   organizacao 2: pacote VIGENTE com os mesmos nomes (41, outro escopo)
\set ON_ERROR_STOP on
SET session_replication_role = replica;
INSERT INTO organizations (organization_id, name, status) VALUES ('00000033-0000-4000-9000-000000000001', 'Exemplo 1', 1), ('00000033-0000-4000-9000-000000000002', 'Exemplo 2', 1);
INSERT INTO workspaces (workspace_id, organization_id, name, status) VALUES ('00000033-0000-4000-9001-000000000001', '00000033-0000-4000-9000-000000000001', 'Financeiro', 1), ('00000033-0000-4000-9001-000000000002', '00000033-0000-4000-9000-000000000002', 'Financeiro', 1);
INSERT INTO automations (automation_id, organization_id, workspace_id, name, status, created_by_membership_id) VALUES
    ('00000033-0000-4000-9002-000000000001', '00000033-0000-4000-9000-000000000001', '00000033-0000-4000-9001-000000000001', 'Pacote', 1, '00000033-0000-4000-9009-000000000001'),
    ('00000033-0000-4000-9002-000000000002', '00000033-0000-4000-9000-000000000001', '00000033-0000-4000-9001-000000000001', 'Monitorada', 1, '00000033-0000-4000-9009-000000000001'),
    ('00000033-0000-4000-9002-000000000003', '00000033-0000-4000-9000-000000000002', '00000033-0000-4000-9001-000000000002', 'Pacote', 1, '00000033-0000-4000-9009-000000000002');
INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, version_number, status, command_json,
                                 created_by_membership_id, published_by_membership_id, published_at, created_at)
SELECT v.id::uuid, v.org::uuid, v.ws::uuid, v.aut::uuid, 1, 2,
       jsonb_build_object('contract_version', 1, 'artifact', jsonb_build_object('reference', 'nekkora-artifact:00000033-0000-4000-9003-000000000001',
           'sha256', repeat('ab', 32)), 'agent_package', jsonb_build_object('fingerprint_rule', 'nekkora-agent/package-fingerprint/1', 'fingerprint', repeat('cd', 32)),
           'runtime', 'python3.12', 'entrypoint', 'main.py', 'arguments', '[]'::jsonb, 'parameters', '{}'::jsonb, 'secret_bindings', '{}'::jsonb),
       v.memb::uuid, v.memb::uuid, timestamptz '2026-11-01 20:00:00+00', timestamptz '2026-11-01 09:00:00+00'
  FROM (VALUES ('00000033-0000-4000-9004-000000000011', '00000033-0000-4000-9000-000000000001', '00000033-0000-4000-9001-000000000001', '00000033-0000-4000-9002-000000000001', '00000033-0000-4000-9009-000000000001'), ('00000033-0000-4000-9004-000000000031', '00000033-0000-4000-9000-000000000002', '00000033-0000-4000-9001-000000000002', '00000033-0000-4000-9002-000000000003', '00000033-0000-4000-9009-000000000002')) AS v(id, org, ws, aut, memb);
INSERT INTO workers (worker_id, organization_id, name, status) VALUES ('00000033-0000-4000-9005-000000000001', '00000033-0000-4000-9000-000000000001', 'Worker 1', 1), ('00000033-0000-4000-9005-000000000002', '00000033-0000-4000-9000-000000000001', 'Worker 2', 1), ('00000033-0000-4000-9005-000000000003', '00000033-0000-4000-9000-000000000002', 'Worker 1', 1);
INSERT INTO worker_workspace_assignments (assignment_id, organization_id, worker_id, workspace_id, status, authorized_by_membership_id) VALUES
    ('00000033-0000-4000-9006-000000000001', '00000033-0000-4000-9000-000000000001', '00000033-0000-4000-9005-000000000001', '00000033-0000-4000-9001-000000000001', 1, '00000033-0000-4000-9009-000000000001'),
    ('00000033-0000-4000-9006-000000000002', '00000033-0000-4000-9000-000000000001', '00000033-0000-4000-9005-000000000002', '00000033-0000-4000-9001-000000000001', 1, '00000033-0000-4000-9009-000000000001'),
    ('00000033-0000-4000-9006-000000000003', '00000033-0000-4000-9000-000000000002', '00000033-0000-4000-9005-000000000003', '00000033-0000-4000-9001-000000000002', 1, '00000033-0000-4000-9009-000000000002');
INSERT INTO local_run_authorizations (authorization_id, organization_id, workspace_id, worker_id, worker_workspace_assignment_id, automation_id,
    automation_version_id, package_fingerprint, package_fingerprint_rule, source_kind, granted_by_membership_id, granted_at, valid_until,
    revoked_at, revoked_by_membership_id) VALUES
    ('00000033-0000-4000-9007-000000000011', '00000033-0000-4000-9000-000000000001', '00000033-0000-4000-9001-000000000001', '00000033-0000-4000-9005-000000000001', '00000033-0000-4000-9006-000000000001', '00000033-0000-4000-9002-000000000001',
     '00000033-0000-4000-9004-000000000011', repeat('cd', 32), 'nekkora-agent/package-fingerprint/1', 1, '00000033-0000-4000-9009-000000000001', now() - interval '1 day', now() + interval '30 days', NULL, NULL),
    ('00000033-0000-4000-9007-000000000012', '00000033-0000-4000-9000-000000000001', '00000033-0000-4000-9001-000000000001', '00000033-0000-4000-9005-000000000001', '00000033-0000-4000-9006-000000000001', '00000033-0000-4000-9002-000000000001',
     '00000033-0000-4000-9004-000000000011', repeat('cd', 32), 'nekkora-agent/package-fingerprint/1', 1, '00000033-0000-4000-9009-000000000001', now() - interval '3 days', now() + interval '30 days', now() - interval '2 days', '00000033-0000-4000-9009-000000000001'),
    ('00000033-0000-4000-9007-000000000013', '00000033-0000-4000-9000-000000000001', '00000033-0000-4000-9001-000000000001', '00000033-0000-4000-9005-000000000001', '00000033-0000-4000-9006-000000000001', '00000033-0000-4000-9002-000000000001',
     '00000033-0000-4000-9004-000000000011', repeat('cd', 32), 'nekkora-agent/package-fingerprint/1', 1, '00000033-0000-4000-9009-000000000001', now() - interval '40 days', now() - interval '10 days', NULL, NULL),
    ('00000033-0000-4000-9007-000000000021', '00000033-0000-4000-9000-000000000001', '00000033-0000-4000-9001-000000000001', '00000033-0000-4000-9005-000000000001', '00000033-0000-4000-9006-000000000001', '00000033-0000-4000-9002-000000000002',
     NULL, NULL, NULL, 3, '00000033-0000-4000-9009-000000000001', now() - interval '1 day', now() + interval '30 days', NULL, NULL),
    ('00000033-0000-4000-9007-000000000031', '00000033-0000-4000-9000-000000000001', '00000033-0000-4000-9001-000000000001', '00000033-0000-4000-9005-000000000002', '00000033-0000-4000-9006-000000000002', '00000033-0000-4000-9002-000000000001',
     '00000033-0000-4000-9004-000000000011', repeat('cd', 32), 'nekkora-agent/package-fingerprint/1', 1, '00000033-0000-4000-9009-000000000001', now() - interval '1 day', now() + interval '30 days', NULL, NULL),
    ('00000033-0000-4000-9007-000000000041', '00000033-0000-4000-9000-000000000002', '00000033-0000-4000-9001-000000000002', '00000033-0000-4000-9005-000000000003', '00000033-0000-4000-9006-000000000003', '00000033-0000-4000-9002-000000000003',
     '00000033-0000-4000-9004-000000000031', repeat('cd', 32), 'nekkora-agent/package-fingerprint/1', 1, '00000033-0000-4000-9009-000000000002', now() - interval '1 day', now() + interval '30 days', NULL, NULL);
SET session_replication_role = origin;
