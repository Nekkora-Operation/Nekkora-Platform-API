-- Massa da suite 031, base C (com repeticoes): como no laboratorio frontlab-local em 10/11/2026 (dois "Testando" ativos na
-- mesma organizacao), mais variantes de caixa, espacos nas extremidades e situacao noutra organizacao, e um nome unico.
-- Como administrador do cluster (preparacao, nao evidencia).
\set ON_ERROR_STOP on
INSERT INTO organizations (organization_id, name, status) VALUES
    ('00000031-0000-4000-8000-00000000000a', 'Laboratorio', 1),
    ('00000031-0000-4000-8000-00000000000b', 'Outra', 1);
INSERT INTO workspaces (workspace_id, organization_id, name, status, created_at) VALUES
    ('00000031-0000-4000-8002-000000000001', '00000031-0000-4000-8000-00000000000a', 'Testando', 1, now() - interval '2 days'),
    ('00000031-0000-4000-8002-000000000002', '00000031-0000-4000-8000-00000000000a', 'Testando', 1, now() - interval '1 day'),
    ('00000031-0000-4000-8002-000000000003', '00000031-0000-4000-8000-00000000000a', 'Unico', 1, now()),
    ('00000031-0000-4000-8002-000000000004', '00000031-0000-4000-8000-00000000000b', 'Relatorios', 1, now() - interval '3 days'),
    ('00000031-0000-4000-8002-000000000005', '00000031-0000-4000-8000-00000000000b', ' relatorios ', 3, now() - interval '2 days'),
    ('00000031-0000-4000-8002-000000000006', '00000031-0000-4000-8000-00000000000b', 'RELATORIOS', 2, now() - interval '1 day'),
    ('00000031-0000-4000-8002-000000000007', '00000031-0000-4000-8000-00000000000b', 'Testando', 1, now());
