-- 02 - papel: nekkora_provisioning. Provisionamento atomico da organizacao + cinco limites.
-- Toda rejeicao e verificada por SQLSTATE e trecho da mensagem; qualquer desvio aborta.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_provisioning', 'papel nekkora_provisioning');

\echo === P01 acesso direto a tabela: negado (so EXECUTE nas funcoes)
SELECT pg_temp.espera_erro($q$INSERT INTO organizations (organization_id, name, status) VALUES ('cccccccc-0000-0000-0000-00000000000c', 'Direta', 1)$q$, '42501', 'permission denied for table organizations');
SELECT pg_temp.espera_erro($q$SELECT count(*) FROM organizations$q$, '42501', 'permission denied for table organizations');
SELECT pg_temp.espera_erro($q$INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, limit_mode, limit_value, provisioned_by_user_id)
  VALUES ('99999999-0000-0000-0000-000000000099', 1, 2, NULL, '99999999-0000-0000-0000-0000000000aa')$q$, '42501', 'permission denied for table organization_resource_limit_revisions');

\echo === P02 validacao do array - cada caso rejeitado antes de qualquer INSERT
SELECT pg_temp.espera_erro($q$SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,1,3),(2,1,2),(3,1,5),(4,2,NULL)]::resource_limit_input[])$q$, '22023', 'exatamente 5 elementos');
SELECT pg_temp.espera_erro($q$SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,1,3),(2,1,2),(3,1,5),(4,2,NULL),(4,2,NULL)]::resource_limit_input[])$q$, '22023', 'sem repeticao');
SELECT pg_temp.espera_erro($q$SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,1,NULL),(2,1,2),(3,1,5),(4,2,NULL),(5,2,NULL)]::resource_limit_input[])$q$, '22023', 'FINITE exige limit_value >= 0');
SELECT pg_temp.espera_erro($q$SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,1,-1),(2,1,2),(3,1,5),(4,2,NULL),(5,2,NULL)]::resource_limit_input[])$q$, '22023', 'FINITE exige limit_value >= 0');
SELECT pg_temp.espera_erro($q$SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,1,3),(2,1,2),(3,1,5),(4,2,10),(5,2,NULL)]::resource_limit_input[])$q$, '22023', 'UNLIMITED exige limit_value nulo');
SELECT pg_temp.espera_erro($q$SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,3,3),(2,1,2),(3,1,5),(4,2,NULL),(5,2,NULL)]::resource_limit_input[])$q$, '22023', 'limit_mode invalido');
SELECT pg_temp.espera_erro($q$SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(NULL,1,3),(2,1,2),(3,1,5),(4,2,NULL),(5,2,NULL)]::resource_limit_input[])$q$, '22023', 'sem resource_code/limit_mode');
SELECT pg_temp.espera_erro($q$SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, '99999999-0000-0000-0000-0000000000aa', NULL)$q$,
  '22023', 'array unidimensional nao nulo');
SELECT pg_temp.espera_erro($q$SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[[(1,1,3)::resource_limit_input,(2,1,2)::resource_limit_input]])$q$, '22023', 'array unidimensional nao nulo');

\echo === P03 atomicidade: responsavel inexistente - a organizacao ja inserida e desfeita junto (conferido no 03)
SELECT pg_temp.espera_erro($q$SELECT provision_organization('cccccccc-0000-0000-0000-00000000000c', 'Org C', NULL, 'dddddddd-0000-0000-0000-00000000dead',
  ARRAY[(1,1,3),(2,1,2),(3,1,5),(4,2,NULL),(5,2,NULL)]::resource_limit_input[])$q$, '23503', 'provisioned_by_user_id_fkey');

\echo === P04 caminho valido: organizacoes A e B, cinco limites explicitos cada (ordem dos elementos irrelevante)
SELECT pg_temp.afirma(provision_organization('aaaaaaaa-0000-0000-0000-00000000000a', 'Org A', 'Org A Ltda', '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(5,2,NULL),(1,1,3),(2,1,2),(3,1,5),(4,2,NULL)]::resource_limit_input[]) = 'aaaaaaaa-0000-0000-0000-00000000000a', 'provision_organization A devolve o id');
SELECT pg_temp.afirma(provision_organization('bbbbbbbb-0000-0000-0000-00000000000b', 'Org B', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,1,2),(2,1,1),(3,1,3),(4,1,1000000),(5,2,NULL)]::resource_limit_input[]) = 'bbbbbbbb-0000-0000-0000-00000000000b', 'provision_organization B devolve o id');

\echo === P05 organizacao duplicada: rejeitada
SELECT pg_temp.espera_erro($q$SELECT provision_organization('aaaaaaaa-0000-0000-0000-00000000000a', 'Org A de novo', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,1,3),(2,1,2),(3,1,5),(4,2,NULL),(5,2,NULL)]::resource_limit_input[])$q$, '23505', 'organizations_pkey');

\echo === P06 limite e retencao avulsos
SELECT pg_temp.afirma(provision_resource_limit('aaaaaaaa-0000-0000-0000-00000000000a', 2, 1::smallint, 3, '99999999-0000-0000-0000-0000000000aa') IS NOT NULL, 'nova revisao de limite');
SELECT pg_temp.afirma(provision_retention_policy('aaaaaaaa-0000-0000-0000-00000000000a', 1, 1::smallint, 30, '99999999-0000-0000-0000-0000000000aa') IS NOT NULL, 'politica de eventos de A');
SELECT pg_temp.afirma(provision_retention_policy('aaaaaaaa-0000-0000-0000-00000000000a', 2, 2::smallint, NULL, '99999999-0000-0000-0000-0000000000aa') IS NOT NULL, 'politica de logs de A');
SELECT pg_temp.espera_erro($q$SELECT provision_retention_policy('aaaaaaaa-0000-0000-0000-00000000000a', 1, 1::smallint, 0, '99999999-0000-0000-0000-0000000000aa')$q$, '22023', 'FINITE exige retention_days > 0');
SELECT pg_temp.espera_erro($q$SELECT provision_retention_policy('aaaaaaaa-0000-0000-0000-00000000000a', 3, 1::smallint, 10, '99999999-0000-0000-0000-0000000000aa')$q$, '22023', 'category invalida');
SELECT pg_temp.espera_erro($q$SELECT provision_resource_limit('aaaaaaaa-0000-0000-0000-00000000000a', 6, 2::smallint, NULL, '99999999-0000-0000-0000-0000000000aa')$q$, '22023', 'resource_code invalido');
SELECT pg_temp.espera_erro($q$SELECT provision_resource_limit('eeeeeeee-0000-0000-0000-00000000000e', 1, 2::smallint, NULL, '99999999-0000-0000-0000-0000000000aa')$q$, '42501', 'organizacao inexistente');

\echo === P07 funcoes de outros servicos: sem EXECUTE; contexto de funcao restaurado
SELECT pg_temp.espera_erro($q$SELECT set_schedule_status('aaaaaaaa-0000-0000-0000-00000000000a', gen_random_uuid(), 2::smallint, gen_random_uuid())$q$, '42501', 'permission denied for function set_schedule_status');
SELECT pg_temp.espera_erro($q$SELECT close_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, '2026-01-01')$q$, '42501', 'permission denied for function close_usage_day');
SELECT pg_temp.afirma(coalesce(current_setting('nekkora.fn_organization_id', true), '') = '', 'fn_organization_id vazio apos as chamadas');
