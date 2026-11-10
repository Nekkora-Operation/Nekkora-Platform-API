-- Massa comum da suite 033 (preparacao como administrador do cluster, NAO evidencia): gatilhos e chaves estrangeiras
-- desligados SO durante a carga (session_replication_role = replica), para montar versoes publicadas e rascunhos sem
-- reconstituir contas, vinculos e publicacoes reais. As restricoes CHECK continuam valendo.
--   automacao 1: v1 publicada + v2 rascunho com pacote      automacao 2: v1 rascunho LEGADO sem pacote
--   automacao 3: v1 e v2 publicadas (sem rascunho)          automacao 4 (outra organizacao): v1 rascunho com pacote
\set ON_ERROR_STOP on
SET session_replication_role = replica;
INSERT INTO organizations (organization_id, name, status) VALUES
    ('00000033-0000-4000-8000-000000000001', 'Exemplo 1', 1),
    ('00000033-0000-4000-8000-000000000002', 'Exemplo 2', 1);
INSERT INTO workspaces (workspace_id, organization_id, name, status) VALUES
    ('00000033-0000-4000-8001-000000000001', '00000033-0000-4000-8000-000000000001', 'Financeiro', 1),
    ('00000033-0000-4000-8001-000000000002', '00000033-0000-4000-8000-000000000002', 'Financeiro', 1);
INSERT INTO automations (automation_id, organization_id, workspace_id, name, status, created_by_membership_id) VALUES
    ('00000033-0000-4000-8002-000000000001', '00000033-0000-4000-8000-000000000001', '00000033-0000-4000-8001-000000000001', 'Publicada com rascunho', 1, '00000033-0000-4000-8009-000000000001'),
    ('00000033-0000-4000-8002-000000000002', '00000033-0000-4000-8000-000000000001', '00000033-0000-4000-8001-000000000001', 'Legado sem pacote', 1, '00000033-0000-4000-8009-000000000001'),
    ('00000033-0000-4000-8002-000000000003', '00000033-0000-4000-8000-000000000001', '00000033-0000-4000-8001-000000000001', 'So publicadas', 1, '00000033-0000-4000-8009-000000000001'),
    ('00000033-0000-4000-8002-000000000004', '00000033-0000-4000-8000-000000000002', '00000033-0000-4000-8001-000000000002', 'Outra organizacao', 1, '00000033-0000-4000-8009-000000000002');
CREATE TABLE public.tmp_cmd_033 AS SELECT
    jsonb_build_object('contract_version', 1, 'artifact', jsonb_build_object('reference', 'nekkora-artifact:00000033-0000-4000-8003-000000000001',
        'sha256', repeat('ab', 32)), 'agent_package', jsonb_build_object('fingerprint_rule', 'nekkora-agent/package-fingerprint/1', 'fingerprint', repeat('cd', 32)),
        'runtime', 'python3.12', 'entrypoint', 'main.py', 'arguments', '[]'::jsonb, 'parameters', '{}'::jsonb, 'secret_bindings', '{}'::jsonb) AS c,
    jsonb_build_object('contract_version', 1, 'runtime', 'python3.12', 'entrypoint', 'main.py', 'arguments', '[]'::jsonb, 'parameters', '{}'::jsonb,
        'secret_bindings', '{}'::jsonb) AS vazio;
INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, version_number, status, command_json,
                                 created_by_membership_id, published_by_membership_id, published_at, created_at)
SELECT v.id::uuid, v.org::uuid, v.ws::uuid, v.aut::uuid, v.n, v.st, CASE WHEN v.pacote THEN t.c ELSE t.vazio END, v.memb::uuid,
       CASE WHEN v.st = 2 THEN v.memb::uuid END, CASE WHEN v.st = 2 THEN timestamptz '2026-11-01 20:00:00+00' END,
       timestamptz '2026-11-01 09:00:00+00' + v.n * interval '1 hour'
  FROM public.tmp_cmd_033 t, (VALUES
    ('00000033-0000-4000-8004-000000000011', '00000033-0000-4000-8000-000000000001', '00000033-0000-4000-8001-000000000001', '00000033-0000-4000-8002-000000000001', 1, 2, true,  '00000033-0000-4000-8009-000000000001'),
    ('00000033-0000-4000-8004-000000000012', '00000033-0000-4000-8000-000000000001', '00000033-0000-4000-8001-000000000001', '00000033-0000-4000-8002-000000000001', 2, 1, true,  '00000033-0000-4000-8009-000000000001'),
    ('00000033-0000-4000-8004-000000000021', '00000033-0000-4000-8000-000000000001', '00000033-0000-4000-8001-000000000001', '00000033-0000-4000-8002-000000000002', 1, 1, false, '00000033-0000-4000-8009-000000000001'),
    ('00000033-0000-4000-8004-000000000031', '00000033-0000-4000-8000-000000000001', '00000033-0000-4000-8001-000000000001', '00000033-0000-4000-8002-000000000003', 1, 2, true,  '00000033-0000-4000-8009-000000000001'),
    ('00000033-0000-4000-8004-000000000032', '00000033-0000-4000-8000-000000000001', '00000033-0000-4000-8001-000000000001', '00000033-0000-4000-8002-000000000003', 2, 2, true,  '00000033-0000-4000-8009-000000000001'),
    ('00000033-0000-4000-8004-000000000041', '00000033-0000-4000-8000-000000000002', '00000033-0000-4000-8001-000000000002', '00000033-0000-4000-8002-000000000004', 1, 1, true,  '00000033-0000-4000-8009-000000000002')
  ) AS v(id, org, ws, aut, n, st, pacote, memb);
SET session_replication_role = origin;
