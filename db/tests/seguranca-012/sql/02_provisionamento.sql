-- 02 - papel: nekkora_provisioning. Provisionamento atomico da organizacao + cinco limites.
\pset pager off
SELECT current_user AS papel;

\echo === P01 acesso direto a tabela: negado (so EXECUTE nas funcoes)
INSERT INTO organizations (organization_id, name, status) VALUES ('cccccccc-0000-0000-0000-00000000000c', 'Direta', 1);
SELECT count(*) FROM organizations;
INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, limit_mode, limit_value, provisioned_by_user_id)
VALUES ('99999999-0000-0000-0000-000000000099', 1, 2, NULL, '99999999-0000-0000-0000-0000000000aa');

\echo === P02 validacao do array - cada caso rejeitado antes de qualquer INSERT
-- 4 elementos
SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,1,3),(2,1,2),(3,1,5),(4,2,NULL)]::resource_limit_input[]);
-- resource_code repetido (5 elementos, falta o 5)
SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,1,3),(2,1,2),(3,1,5),(4,2,NULL),(4,2,NULL)]::resource_limit_input[]);
-- FINITE sem valor
SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,1,NULL),(2,1,2),(3,1,5),(4,2,NULL),(5,2,NULL)]::resource_limit_input[]);
-- FINITE negativo
SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,1,-1),(2,1,2),(3,1,5),(4,2,NULL),(5,2,NULL)]::resource_limit_input[]);
-- UNLIMITED com valor
SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,1,3),(2,1,2),(3,1,5),(4,2,10),(5,2,NULL)]::resource_limit_input[]);
-- limit_mode invalido
SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,3,3),(2,1,2),(3,1,5),(4,2,NULL),(5,2,NULL)]::resource_limit_input[]);
-- elemento sem resource_code
SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(NULL,1,3),(2,1,2),(3,1,5),(4,2,NULL),(5,2,NULL)]::resource_limit_input[]);
-- array nulo e array bidimensional
SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, '99999999-0000-0000-0000-0000000000aa', NULL);
SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[[(1,1,3)::resource_limit_input,(2,1,2)::resource_limit_input]]);

\echo === P03 atomicidade: responsavel inexistente - organizacao ja inserida precisa ser desfeita junto
SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, 'dddddddd-0000-0000-0000-00000000dead',
  ARRAY[(1,1,3),(2,1,2),(3,1,5),(4,2,NULL),(5,2,NULL)]::resource_limit_input[]);

\echo === P04 caminho valido: organizacoes A e B, cinco limites explicitos cada (ordem dos elementos irrelevante)
\set ON_ERROR_STOP on
SELECT provision_organization('aaaaaaaa-0000-0000-0000-00000000000a', 'Org A', 'Org A Ltda', '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(5,2,NULL),(1,1,3),(2,1,2),(3,1,5),(4,2,NULL)]::resource_limit_input[]) AS org_a;
SELECT provision_organization('bbbbbbbb-0000-0000-0000-00000000000b', 'Org B', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,1,2),(2,1,1),(3,1,3),(4,1,1000000),(5,2,NULL)]::resource_limit_input[]) AS org_b;
\set ON_ERROR_STOP off

\echo === P05 organizacao duplicada: rejeitada
SELECT provision_organization('aaaaaaaa-0000-0000-0000-00000000000a', 'Org A de novo', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,1,3),(2,1,2),(3,1,5),(4,2,NULL),(5,2,NULL)]::resource_limit_input[]);

\echo === P06 limite e retencao avulsos
\set ON_ERROR_STOP on
SELECT provision_resource_limit('aaaaaaaa-0000-0000-0000-00000000000a', 2, 1::smallint, 3, '99999999-0000-0000-0000-0000000000aa') IS NOT NULL AS nova_revisao_limite;
SELECT provision_retention_policy('aaaaaaaa-0000-0000-0000-00000000000a', 1, 1::smallint, 30, '99999999-0000-0000-0000-0000000000aa') IS NOT NULL AS politica_eventos_a;
SELECT provision_retention_policy('aaaaaaaa-0000-0000-0000-00000000000a', 2, 2::smallint, NULL, '99999999-0000-0000-0000-0000000000aa') IS NOT NULL AS politica_logs_a;
\set ON_ERROR_STOP off
SELECT provision_retention_policy('aaaaaaaa-0000-0000-0000-00000000000a', 1, 1::smallint, 0, '99999999-0000-0000-0000-0000000000aa');
SELECT provision_retention_policy('aaaaaaaa-0000-0000-0000-00000000000a', 3, 1::smallint, 10, '99999999-0000-0000-0000-0000000000aa');
SELECT provision_resource_limit('aaaaaaaa-0000-0000-0000-00000000000a', 6, 2::smallint, NULL, '99999999-0000-0000-0000-0000000000aa');
SELECT provision_resource_limit('eeeeeeee-0000-0000-0000-00000000000e', 1, 2::smallint, NULL, '99999999-0000-0000-0000-0000000000aa');

\echo === P07 funcoes de outros servicos: sem EXECUTE
SELECT set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', gen_random_uuid(), 2::smallint, gen_random_uuid());
SELECT close_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, '2026-01-01');
SELECT current_setting('nekkora.fn_organization_id', true) AS fn_ctx_apos_chamadas;
