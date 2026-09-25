-- v01 - papel: nekkora_provisioning. Caminho aprovado de criacao de organizacao numa base recem-instalada.
-- Variaveis: staff (usuario staff do bootstrap), org_v (organizacao de verificacao).
\pset pager off
SELECT current_user AS papel;
\echo === acesso direto negado
INSERT INTO organizations (organization_id, name, status) VALUES (:'org_v', 'Direta', 1);
\echo === provision_organization com cinco limites explicitos
\set ON_ERROR_STOP on
SELECT provision_organization(:'org_v', 'Organizacao de Verificacao', NULL, :'staff',
       ARRAY[(1,1,2),(2,1,1),(3,1,3),(4,2,NULL),(5,2,NULL)]::resource_limit_input[]) AS organizacao_criada;
\set ON_ERROR_STOP off
\echo === array invalido rejeitado antes de qualquer INSERT
SELECT provision_organization(gen_random_uuid(), 'Invalida', NULL, :'staff',
       ARRAY[(1,1,2),(2,1,1),(3,1,3),(4,2,NULL)]::resource_limit_input[]);
