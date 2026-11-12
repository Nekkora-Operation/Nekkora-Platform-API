-- C: os gatilhos da 035 com o papel REAL da maquina (SET ROLE nekkora_ingestion: RLS e privilegios dela). Cada recusa
-- aparece como "ERROR: ..." na saida (ON_ERROR_STOP desligado de proposito); as linhas "C?|..." sao conferidas pelo run.sh.
-- Nada fica gravado (ROLLBACK no fim). Os casos com SAVEPOINT nao dependem do xmin; o desvinculo completo (C7) roda sem
-- SAVEPOINT aberto, como o gateway.
\set ON_ERROR_STOP 0
\set VERBOSITY terse
BEGIN;
SET ROLE nekkora_ingestion;
SELECT set_config('nekkora.organization_id', '00000035-0000-4000-9002-000000000001', true);
\echo C1
SAVEPOINT c1;
UPDATE agents SET status = 4 WHERE agent_id = '00000035-0000-4000-9006-000000000001';
ROLLBACK TO SAVEPOINT c1;
RELEASE SAVEPOINT c1;
\echo C2
SAVEPOINT c2;
UPDATE worker_agent_assignments SET status = 2, ended_at = clock_timestamp(), ended_by_agent_id = agent_id WHERE assignment_id = '00000035-0000-4000-9007-000000000001';
ROLLBACK TO SAVEPOINT c2;
RELEASE SAVEPOINT c2;
\echo C3
SAVEPOINT c3;
UPDATE agent_credentials SET revoked_at = clock_timestamp(), revoked_by_agent_id = agent_id, revocation_reason = 5 WHERE credential_id = '00000035-0000-4000-9008-000000000001';
ROLLBACK TO SAVEPOINT c3;
RELEASE SAVEPOINT c3;
\echo C4
SAVEPOINT c4;
UPDATE worker_agent_assignments SET status = 2, ended_at = clock_timestamp(), ended_by_membership_id = '00000035-0000-4000-9004-000000000001' WHERE assignment_id = '00000035-0000-4000-9007-000000000001';
ROLLBACK TO SAVEPOINT c4;
RELEASE SAVEPOINT c4;
\echo C5
SAVEPOINT c5;
INSERT INTO agent_unlink_operations (operation_id, organization_id, agent_id, worker_id, assignment_id, credential_id, run_requests_closed, run_requests_kept_accepted)
    VALUES (gen_random_uuid(), '00000035-0000-4000-9002-000000000001', '00000035-0000-4000-9006-000000000002', '00000035-0000-4000-9005-000000000002',
            '00000035-0000-4000-9007-000000000002', '00000035-0000-4000-9008-000000000002', 0, 0);
ROLLBACK TO SAVEPOINT c5;
RELEASE SAVEPOINT c5;
SELECT 'C6|' || count(*) FROM agents WHERE agent_id = '00000035-0000-4000-9006-000000000009';
\echo C7
INSERT INTO agent_unlink_operations (operation_id, organization_id, agent_id, worker_id, assignment_id, credential_id, run_requests_closed, run_requests_kept_accepted)
    VALUES ('00000035-0000-4000-9009-000000000001', '00000035-0000-4000-9002-000000000001', '00000035-0000-4000-9006-000000000001', '00000035-0000-4000-9005-000000000001',
            '00000035-0000-4000-9007-000000000001', '00000035-0000-4000-9008-000000000001', 0, 0);
UPDATE agent_credentials SET revoked_at = clock_timestamp(), revoked_by_agent_id = agent_id, revocation_reason = 5 WHERE agent_id = '00000035-0000-4000-9006-000000000001' AND revoked_at IS NULL;
UPDATE worker_agent_assignments SET status = 2, ended_at = clock_timestamp(), ended_by_agent_id = agent_id WHERE assignment_id = '00000035-0000-4000-9007-000000000001';
UPDATE agents SET status = 4 WHERE agent_id = '00000035-0000-4000-9006-000000000001';
INSERT INTO agent_admin_events (organization_id, act, actor_agent_id, agent_id, worker_id, reason_code) VALUES
    ('00000035-0000-4000-9002-000000000001', 14, '00000035-0000-4000-9006-000000000001', '00000035-0000-4000-9006-000000000001', '00000035-0000-4000-9005-000000000001', 'MACHINE_UNLINK');
SELECT 'C7|' || a.status || '|' || wa.status || '|' || (wa.ended_by_agent_id = a.agent_id) || '|'
       || (SELECT count(*) FROM agent_credentials c WHERE c.agent_id = a.agent_id AND c.revoked_at IS NULL)
  FROM agents a JOIN worker_agent_assignments wa ON wa.agent_id = a.agent_id WHERE a.agent_id = '00000035-0000-4000-9006-000000000001';
\echo C8
SAVEPOINT c8;
UPDATE agent_unlink_operations SET run_requests_closed = 1;
ROLLBACK TO SAVEPOINT c8;
RELEASE SAVEPOINT c8;
\echo C9
SAVEPOINT c9;
DELETE FROM agent_unlink_operations;
ROLLBACK TO SAVEPOINT c9;
RELEASE SAVEPOINT c9;
\echo C10
SAVEPOINT c10;
INSERT INTO agent_admin_events (organization_id, act, actor_membership_id, actor_agent_id, agent_id, worker_id) VALUES
    ('00000035-0000-4000-9002-000000000001', 14, '00000035-0000-4000-9004-000000000001', '00000035-0000-4000-9006-000000000001',
     '00000035-0000-4000-9006-000000000001', '00000035-0000-4000-9005-000000000001');
ROLLBACK TO SAVEPOINT c10;
RELEASE SAVEPOINT c10;
\echo FIM
ROLLBACK;
