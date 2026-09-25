-- 03 - papel: nekkora (somente leitura). Efeito real do 02, visto sem RLS.
\pset pager off
SELECT organization_id, name, legal_name, status FROM organizations ORDER BY 1;
SELECT organization_id, resource_code, revision_number, limit_mode, limit_value, effective_from IS NOT NULL AS vigencia_atribuida
FROM organization_resource_limit_revisions ORDER BY 1, 2, 3;
SELECT organization_id, category, revision_number, retention_mode, retention_days FROM organization_retention_policy_revisions ORDER BY 1, 2;
SELECT count(*) AS organizacao_c_tentativas_rejeitadas FROM organizations WHERE organization_id = 'cccccccc-0000-0000-0000-00000000000c';
