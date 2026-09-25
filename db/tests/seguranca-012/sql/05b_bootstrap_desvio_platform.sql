-- 05b - papel: nekkora_platform. Tentativas de bootstrap fora do caminho autorizado, ANTES do
-- bootstrap (organizacao sem atribuicoes), para isolar a checagem de current_user.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
\echo === B06 INSERT direto com responsavel nulo, no proprio contexto: rejeitado pela trigger (current_user)
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT pg_temp.afirma((SELECT count(*) FROM organization_role_assignments) = 0, 'A ainda sem atribuicoes (isola a checagem de current_user)');
SELECT pg_temp.espera_erro($q$INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id)
  SELECT 'aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0002-000000000001', role_id, 1, 1, NULL FROM roles WHERE code = 'ORG_ADMIN'$q$,
  '42501', 'so e permitido via provision_first_org_admin (current_user=nekkora_platform)');
ROLLBACK;
\echo === B07 funcao de bootstrap: sem EXECUTE para o platform
SELECT pg_temp.espera_erro($q$SELECT provision_first_org_admin('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0002-000000000001',
  '99999999-0000-0000-0000-0000000000aa', 'suporte', gen_random_uuid())$q$, '42501', 'permission denied for function provision_first_org_admin');
\echo === B08 platform nao le eventos de provisionamento (sem GRANT)
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT pg_temp.espera_erro($q$SELECT count(*) FROM identity_provisioning_events$q$, '42501', 'permission denied for table identity_provisioning_events');
ROLLBACK;
