-- Massa da suite 035 (como administrador do cluster: preparacao, nao evidencia). Uma organizacao com:
--   W1 + A1 ativo (credencial atual e uma anterior revogada por ROTACAO),
--   W2 + A2 revogado pela ADMINISTRACAO (associacao encerrada por pessoa, credencial motivo 4),
--   W3 livre. Outra organizacao com W9 + A9 ativo.
\set ON_ERROR_STOP 1
INSERT INTO organizations (organization_id, name, status) VALUES
    ('00000035-0000-4000-9002-000000000001', 'Org desvinculo', 1),
    ('00000035-0000-4000-9002-000000000009', 'Org vizinha', 1);
INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES
    ('00000035-0000-4000-9003-000000000001', '00000035-0000-4000-9002-000000000001', 1, 'adm1@o35.test', now(), 2, 'Adm'),
    ('00000035-0000-4000-9003-000000000009', '00000035-0000-4000-9002-000000000009', 1, 'adm9@o35.test', now(), 2, 'Adm');
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES
    ('00000035-0000-4000-9004-000000000001', '00000035-0000-4000-9002-000000000001', '00000035-0000-4000-9003-000000000001', 1, 2),
    ('00000035-0000-4000-9004-000000000009', '00000035-0000-4000-9002-000000000009', '00000035-0000-4000-9003-000000000009', 1, 2);
INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, limit_value, provisioned_by_user_id)
SELECT o, code, 1, now(), 2, NULL, u
  FROM (VALUES ('00000035-0000-4000-9002-000000000001'::uuid, '00000035-0000-4000-9003-000000000001'::uuid),
               ('00000035-0000-4000-9002-000000000009'::uuid, '00000035-0000-4000-9003-000000000009'::uuid)) v(o, u), generate_series(1, 5) code;
INSERT INTO workers (worker_id, organization_id, name, status) VALUES
    ('00000035-0000-4000-9005-000000000001', '00000035-0000-4000-9002-000000000001', 'W1', 1),
    ('00000035-0000-4000-9005-000000000002', '00000035-0000-4000-9002-000000000001', 'W2', 1),
    ('00000035-0000-4000-9005-000000000003', '00000035-0000-4000-9002-000000000001', 'W3', 1),
    ('00000035-0000-4000-9005-000000000009', '00000035-0000-4000-9002-000000000009', 'W9', 1);
INSERT INTO agents (agent_id, organization_id, name, agent_version, status) VALUES
    ('00000035-0000-4000-9006-000000000001', '00000035-0000-4000-9002-000000000001', 'A1', '0.1.0', 2),
    ('00000035-0000-4000-9006-000000000002', '00000035-0000-4000-9002-000000000001', 'A2', '0.1.0', 2),
    ('00000035-0000-4000-9006-000000000009', '00000035-0000-4000-9002-000000000009', 'A9', '0.1.0', 2);
INSERT INTO worker_agent_assignments (assignment_id, organization_id, worker_id, agent_id, status, assigned_by_membership_id) VALUES
    ('00000035-0000-4000-9007-000000000001', '00000035-0000-4000-9002-000000000001', '00000035-0000-4000-9005-000000000001', '00000035-0000-4000-9006-000000000001', 1, '00000035-0000-4000-9004-000000000001'),
    ('00000035-0000-4000-9007-000000000002', '00000035-0000-4000-9002-000000000001', '00000035-0000-4000-9005-000000000002', '00000035-0000-4000-9006-000000000002', 1, '00000035-0000-4000-9004-000000000001'),
    ('00000035-0000-4000-9007-000000000009', '00000035-0000-4000-9002-000000000009', '00000035-0000-4000-9005-000000000009', '00000035-0000-4000-9006-000000000009', 1, '00000035-0000-4000-9004-000000000009');
INSERT INTO agent_credentials (credential_id, organization_id, agent_id, credential_hash, issued_by_membership_id, expires_at) VALUES
    ('00000035-0000-4000-9008-000000000010', '00000035-0000-4000-9002-000000000001', '00000035-0000-4000-9006-000000000001', repeat('a', 64), '00000035-0000-4000-9004-000000000001', now() + interval '90 days'),
    ('00000035-0000-4000-9008-000000000002', '00000035-0000-4000-9002-000000000001', '00000035-0000-4000-9006-000000000002', repeat('b', 64), '00000035-0000-4000-9004-000000000001', now() + interval '90 days'),
    ('00000035-0000-4000-9008-000000000009', '00000035-0000-4000-9002-000000000009', '00000035-0000-4000-9006-000000000009', repeat('9', 64), '00000035-0000-4000-9004-000000000009', now() + interval '90 days');
-- rotacao de A1: a anterior revogada (motivo 1, por pessoa) e a nova
UPDATE agent_credentials SET revoked_at = clock_timestamp(), revoked_by_membership_id = '00000035-0000-4000-9004-000000000001', revocation_reason = 1
 WHERE credential_id = '00000035-0000-4000-9008-000000000010';
INSERT INTO agent_credentials (credential_id, organization_id, agent_id, credential_hash, issued_by_membership_id, expires_at) VALUES
    ('00000035-0000-4000-9008-000000000001', '00000035-0000-4000-9002-000000000001', '00000035-0000-4000-9006-000000000001', repeat('c', 64), '00000035-0000-4000-9004-000000000001', now() + interval '90 days');
-- revogacao administrativa de A2 (como a Web: credencial, associacao, status)
UPDATE agent_credentials SET revoked_at = clock_timestamp(), revoked_by_membership_id = '00000035-0000-4000-9004-000000000001', revocation_reason = 4
 WHERE credential_id = '00000035-0000-4000-9008-000000000002';
UPDATE worker_agent_assignments SET status = 2, ended_at = clock_timestamp(), ended_by_membership_id = '00000035-0000-4000-9004-000000000001'
 WHERE assignment_id = '00000035-0000-4000-9007-000000000002';
UPDATE agents SET status = 4 WHERE agent_id = '00000035-0000-4000-9006-000000000002';
INSERT INTO agent_admin_events (organization_id, act, actor_membership_id, agent_id, worker_id, reason_code) VALUES
    ('00000035-0000-4000-9002-000000000001', 6, '00000035-0000-4000-9004-000000000001', '00000035-0000-4000-9006-000000000002', '00000035-0000-4000-9005-000000000002', 'ADMINISTRATIVE');
