-- v03 - papel: nekkora_provisioning. Primeira atribuicao de ORG_ADMIN da organizacao V.
-- Variaveis: org_v, mem_v, staff.
\pset pager off
SELECT current_user AS papel;
\set ON_ERROR_STOP on
SELECT provision_first_org_admin(:'org_v', :'mem_v', :'staff', 'instalacao-limpa', gen_random_uuid()) IS NOT NULL AS org_admin_atribuido;
\set ON_ERROR_STOP off
\echo === segundo bootstrap: recusado
SELECT provision_first_org_admin(:'org_v', :'mem_v', :'staff', 'instalacao-limpa', gen_random_uuid());
