-- f01 - papel: nekkora_provisioning. Quatro organizacoes da fixture, com os cinco limites explicitos
-- (ilimitados: nenhum cenario do portal depende de limite).
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_provisioning', 'papel nekkora_provisioning');
SELECT pg_temp.afirma(provision_organization(:'o1', 'Org O1', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,2,NULL),(2,2,NULL),(3,2,NULL),(4,2,NULL),(5,2,NULL)]::resource_limit_input[]) = :'o1'::uuid, 'O1 provisionada');
SELECT pg_temp.afirma(provision_organization(:'o2', 'Org O2', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,2,NULL),(2,2,NULL),(3,2,NULL),(4,2,NULL),(5,2,NULL)]::resource_limit_input[]) = :'o2'::uuid, 'O2 provisionada');
SELECT pg_temp.afirma(provision_organization(:'o3', 'Org O3', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,2,NULL),(2,2,NULL),(3,2,NULL),(4,2,NULL),(5,2,NULL)]::resource_limit_input[]) = :'o3'::uuid, 'O3 provisionada');
SELECT pg_temp.afirma(provision_organization(:'o4', 'Org O4', NULL, '99999999-0000-0000-0000-0000000000aa',
  ARRAY[(1,2,NULL),(2,2,NULL),(3,2,NULL),(4,2,NULL),(5,2,NULL)]::resource_limit_input[]) = :'o4'::uuid, 'O4 provisionada');
