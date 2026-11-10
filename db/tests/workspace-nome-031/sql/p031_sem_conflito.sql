-- Massa da suite 031, base B (sem conflito): o mesmo nome em organizacoes diferentes, arquivado com nome proprio, acentos.
-- Como administrador do cluster (preparacao, nao evidencia). 7 workspaces.
\set ON_ERROR_STOP on
INSERT INTO organizations (organization_id, name, status) VALUES
    ('00000031-0000-4000-8000-000000000001', 'Exemplo 1', 1),
    ('00000031-0000-4000-8000-000000000002', 'Exemplo 2', 1);
INSERT INTO workspaces (workspace_id, organization_id, name, status) VALUES
    ('00000031-0000-4000-8001-000000000001', '00000031-0000-4000-8000-000000000001', 'Financeiro', 1),
    ('00000031-0000-4000-8001-000000000002', '00000031-0000-4000-8000-000000000001', 'Compras', 1),
    ('00000031-0000-4000-8001-000000000003', '00000031-0000-4000-8000-000000000001', 'Ação Fiscal', 1),
    ('00000031-0000-4000-8001-000000000004', '00000031-0000-4000-8000-000000000001', 'Antigo', 3),
    ('00000031-0000-4000-8001-000000000005', '00000031-0000-4000-8000-000000000002', 'Financeiro', 1),
    ('00000031-0000-4000-8001-000000000006', '00000031-0000-4000-8000-000000000002', 'COMPRAS', 2),
    ('00000031-0000-4000-8001-000000000007', '00000031-0000-4000-8000-000000000002', 'Acao Fiscal', 1);
