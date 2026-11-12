-- Massa da base C da suite 033: as REPETICOES que impedem a 033 (vigentes a mais nos escopos da massa comum).
--   org 1, Worker 1, pacote v1: mais uma vigente (51)      org 1, Worker 1, monitoramento da automacao 2: mais duas (52, 53)
\set ON_ERROR_STOP on
SET session_replication_role = replica;
INSERT INTO local_run_authorizations (authorization_id, organization_id, workspace_id, worker_id, worker_workspace_assignment_id, automation_id,
    automation_version_id, package_fingerprint, package_fingerprint_rule, source_kind, granted_by_membership_id, granted_at, valid_until,
    revoked_at, revoked_by_membership_id) VALUES
    ('00000033-0000-4000-9007-000000000051', '00000033-0000-4000-9000-000000000001', '00000033-0000-4000-9001-000000000001', '00000033-0000-4000-9005-000000000001', '00000033-0000-4000-9006-000000000001', '00000033-0000-4000-9002-000000000001',
     '00000033-0000-4000-9004-000000000011', repeat('cd', 32), 'nekkora-agent/package-fingerprint/1', 1, '00000033-0000-4000-9009-000000000001', now() - interval '12 hours', now() + interval '60 days', NULL, NULL),
    ('00000033-0000-4000-9007-000000000052', '00000033-0000-4000-9000-000000000001', '00000033-0000-4000-9001-000000000001', '00000033-0000-4000-9005-000000000001', '00000033-0000-4000-9006-000000000001', '00000033-0000-4000-9002-000000000002',
     NULL, NULL, NULL, 3, '00000033-0000-4000-9009-000000000001', now() - interval '12 hours', now() + interval '30 days', NULL, NULL),
    ('00000033-0000-4000-9007-000000000053', '00000033-0000-4000-9000-000000000001', '00000033-0000-4000-9001-000000000001', '00000033-0000-4000-9005-000000000001', '00000033-0000-4000-9006-000000000001', '00000033-0000-4000-9002-000000000002',
     NULL, NULL, NULL, 3, '00000033-0000-4000-9009-000000000001', now() - interval '6 hours', now() + interval '30 days', NULL, NULL);
SET session_replication_role = origin;
