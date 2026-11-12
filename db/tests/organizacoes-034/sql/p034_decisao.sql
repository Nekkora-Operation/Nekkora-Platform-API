-- SIMULACAO da decisao humana da base C, so para provar o caminho depois da recusa. NAO e procedimento automatico nem
-- recomendacao: a pessoa responsavel decidiu dar outro nome a org 6. Nada e excluido nem fundido.
\set ON_ERROR_STOP on
UPDATE organizations SET name = 'Padaria Central Filial' WHERE organization_id = '00000034-0000-4000-9002-000000000006';
SELECT 'decisao simulada: org 6 renomeada para Padaria Central Filial';
