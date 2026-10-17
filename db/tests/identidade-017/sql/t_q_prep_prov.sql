-- Grupo Q (R4) - papel: nekkora_provisioning. Organizacao O4, dedicada aos cenarios que DESATIVAM a organizacao (as demais
-- continuam ativas para os outros grupos), pelo caminho da 012.
\pset pager off
\set o4 '17000000-0000-0000-0000-000000000004'
SELECT pg_temp.afirma(current_user = 'nekkora_provisioning', 'papel nekkora_provisioning');
SELECT pg_temp.afirma(provision_organization(:'o4', 'Org O4 (grupo Q)', NULL, :'staff',
  ARRAY[(1,2,NULL),(2,2,NULL),(3,2,NULL),(4,2,NULL),(5,2,NULL)]::resource_limit_input[]) = :'o4'::uuid, 'O4 provisionada');
SELECT 'grupo Q, provisionamento: ok' AS resultado;
