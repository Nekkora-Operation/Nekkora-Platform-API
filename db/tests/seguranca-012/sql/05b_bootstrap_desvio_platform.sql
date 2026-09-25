-- 05b - papel: nekkora_platform. Tentativas de bootstrap fora do caminho autorizado.
\pset pager off
SELECT current_user AS papel;
\echo === B06 INSERT direto com responsavel nulo, no proprio contexto: rejeitado pela trigger (current_user)
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id)
SELECT 'aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0002-000000000001', role_id, 1, 1, NULL FROM roles WHERE code = 'ORG_ADMIN';
ROLLBACK;
\echo === B07 funcao de bootstrap: sem EXECUTE para o platform
SELECT provision_first_org_admin('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0002-000000000001',
       '99999999-0000-0000-0000-0000000000aa', 'suporte', gen_random_uuid());
\echo === B08 platform le as proprias atribuicoes (antes do bootstrap: 0) e nao le eventos de provisionamento (sem GRANT)
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT count(*) AS atribuicoes_visiveis_a FROM organization_role_assignments;
SELECT count(*) AS eventos_provisionamento_legiveis FROM identity_provisioning_events;
ROLLBACK;
