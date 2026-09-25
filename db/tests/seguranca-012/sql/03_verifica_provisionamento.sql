-- 03 - papel: nekkora (somente leitura). Efeito real do 02, visto sem RLS.
\pset pager off
SELECT organization_id, name, legal_name, status FROM organizations ORDER BY 1;
SELECT organization_id, resource_code, revision_number, limit_mode, limit_value, effective_from IS NOT NULL AS vigencia_atribuida
FROM organization_resource_limit_revisions ORDER BY 1, 2, 3;
SELECT organization_id, category, revision_number, retention_mode, retention_days FROM organization_retention_policy_revisions ORDER BY 1, 2;
SELECT pg_temp.afirma((SELECT count(*) FROM organizations) = 3, '3 organizacoes: interna, A, B');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM organizations WHERE organization_id = 'cccccccc-0000-0000-0000-00000000000c'), 'organizacao C (tentativas rejeitadas, inclusive P03) nao existe');
SELECT pg_temp.afirma((SELECT array_agg(resource_code ORDER BY resource_code) FROM organization_resource_limit_revisions
                       WHERE organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a' AND revision_number = 1) = ARRAY[1,2,3,4,5]::smallint[], 'A: cinco limites iniciais (revisao 1)');
SELECT pg_temp.afirma((SELECT array_agg(resource_code ORDER BY resource_code) FROM organization_resource_limit_revisions
                       WHERE organization_id = 'bbbbbbbb-0000-0000-0000-00000000000b') = ARRAY[1,2,3,4,5]::smallint[], 'B: cinco limites');
SELECT pg_temp.afirma((SELECT limit_value FROM organization_resource_limit_revisions WHERE organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a'
                       AND resource_code = 2 AND revision_number = 2) = 3, 'A: revisao 2 do limite 2 = 3');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM organization_resource_limit_revisions WHERE effective_from IS NULL), 'vigencia atribuida pelo banco em toda revisao');
SELECT pg_temp.afirma((SELECT count(*) FROM organization_retention_policy_revisions WHERE organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a') = 2, 'A: duas politicas de retencao');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM organization_retention_policy_revisions WHERE organization_id = 'bbbbbbbb-0000-0000-0000-00000000000b'), 'B: nenhuma politica de retencao implicita');
