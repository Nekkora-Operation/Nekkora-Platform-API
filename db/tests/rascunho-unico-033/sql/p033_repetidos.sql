-- Massa adicional da base C (preparacao, NAO evidencia): duas automacoes com MAIS DE UM rascunho, como a rota antiga
-- permitia ("Nova versao" clicada varias vezes): uma so com rascunhos vazios, outra com publicada e dois rascunhos.
-- Depende de p033_base.sql (tabela auxiliar tmp_cmd_033).
\set ON_ERROR_STOP on
SET session_replication_role = replica;
INSERT INTO automations (automation_id, organization_id, workspace_id, name, status, created_by_membership_id) VALUES
    ('00000033-0000-4000-8002-000000000005', '00000033-0000-4000-8000-000000000001', '00000033-0000-4000-8001-000000000001', 'Rascunhos vazios', 1, '00000033-0000-4000-8009-000000000001'),
    ('00000033-0000-4000-8002-000000000006', '00000033-0000-4000-8000-000000000002', '00000033-0000-4000-8001-000000000002', 'Publicada e dois rascunhos', 1, '00000033-0000-4000-8009-000000000002');
INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, version_number, status, command_json,
                                 created_by_membership_id, published_by_membership_id, published_at, created_at)
SELECT v.id::uuid, v.org::uuid, v.ws::uuid, v.aut::uuid, v.n, v.st, CASE WHEN v.pacote THEN t.c ELSE t.vazio END, v.memb::uuid,
       CASE WHEN v.st = 2 THEN v.memb::uuid END, CASE WHEN v.st = 2 THEN timestamptz '2026-11-01 20:00:00+00' END,
       timestamptz '2026-11-01 09:00:00+00' + v.n * interval '1 hour'
  FROM public.tmp_cmd_033 t, (VALUES
    ('00000033-0000-4000-8004-000000000051', '00000033-0000-4000-8000-000000000001', '00000033-0000-4000-8001-000000000001', '00000033-0000-4000-8002-000000000005', 1, 1, false, '00000033-0000-4000-8009-000000000001'),
    ('00000033-0000-4000-8004-000000000052', '00000033-0000-4000-8000-000000000001', '00000033-0000-4000-8001-000000000001', '00000033-0000-4000-8002-000000000005', 2, 1, false, '00000033-0000-4000-8009-000000000001'),
    ('00000033-0000-4000-8004-000000000053', '00000033-0000-4000-8000-000000000001', '00000033-0000-4000-8001-000000000001', '00000033-0000-4000-8002-000000000005', 3, 1, false, '00000033-0000-4000-8009-000000000001'),
    ('00000033-0000-4000-8004-000000000061', '00000033-0000-4000-8000-000000000002', '00000033-0000-4000-8001-000000000002', '00000033-0000-4000-8002-000000000006', 1, 2, true,  '00000033-0000-4000-8009-000000000002'),
    ('00000033-0000-4000-8004-000000000062', '00000033-0000-4000-8000-000000000002', '00000033-0000-4000-8001-000000000002', '00000033-0000-4000-8002-000000000006', 2, 1, true,  '00000033-0000-4000-8009-000000000002'),
    ('00000033-0000-4000-8004-000000000063', '00000033-0000-4000-8000-000000000002', '00000033-0000-4000-8001-000000000002', '00000033-0000-4000-8002-000000000006', 3, 1, false, '00000033-0000-4000-8009-000000000002')
  ) AS v(id, org, ws, aut, n, st, pacote, memb);
SET session_replication_role = origin;
