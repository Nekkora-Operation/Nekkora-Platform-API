-- Suite 031, base C: uma DECISAO HUMANA possivel, so para provar o caminho depois da recusa (a 031 nunca faz isso sozinha):
-- renomear os excedentes de cada repeticao, mantendo o mais antigo com o nome original. Nada e apagado nem fundido.
\set ON_ERROR_STOP on
UPDATE workspaces SET name = 'Testando (2)' WHERE workspace_id = '00000031-0000-4000-8002-000000000002';
UPDATE workspaces SET name = 'Relatorios (arquivado)' WHERE workspace_id = '00000031-0000-4000-8002-000000000005';
UPDATE workspaces SET name = 'Relatorios (suspenso)' WHERE workspace_id = '00000031-0000-4000-8002-000000000006';
SELECT 'renomeados=' || count(*) FROM workspaces WHERE name LIKE '% (%)';
