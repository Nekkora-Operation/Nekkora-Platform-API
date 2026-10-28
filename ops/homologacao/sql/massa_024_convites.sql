-- Massa DECLARADA para a homologacao dirigida da 024 (testes/janela_023_024.sh), no modelo da 023, ANTES da 024. Executada
-- como admin_cluster, SO em laboratorio ensaio-*. Dados sinteticos (dominios .test); identificadores fixos com o prefixo
-- c0240000 para a conferencia (sql/conferencia_024_convites.sql). Uma organizacao com:
--   ADM  ORG_ADMIN pelo provisionamento real (emissor autorizado durante toda a massa);
--   EMI  ORG_ADMIN que PERDE o papel antes da 024: os 2 convites abertos dele ficam inaceitaveis (023) -> a 024 os fecha;
--   REG  ORG_ADMIN que perde E RECUPERA o papel antes da 024: o convite aberto dele volta a ser aceitavel pela regra da 023
--        e a 024 NAO o fecha (ela olha a autoridade no momento da aplicacao; limite declarado no relatorio);
--   convites: aberto valido (ADM), 2 abertos de EMI, aberto de REG, aceito (MEM) e revogado (motivo 1);
--   acessos ja concedidos: papel de workspace de MEM e concessao externa ATIVA da conta de cliente (nao podem mudar).
BEGIN;
SELECT set_config('nekkora.organization_id', 'c0240000-0000-4000-8000-000000000001', true);
INSERT INTO organizations (organization_id, name, status) VALUES ('c0240000-0000-4000-8000-000000000001', 'Org homologacao 024', 1);
INSERT INTO clients (client_id, organization_id, name, status) VALUES ('c0240000-0000-4000-8000-000000000020', 'c0240000-0000-4000-8000-000000000001', 'Cliente 024', 1);
INSERT INTO workspaces (workspace_id, organization_id, client_id, name, status) VALUES
  ('c0240000-0000-4000-8000-000000000030', 'c0240000-0000-4000-8000-000000000001', 'c0240000-0000-4000-8000-000000000020', 'WS cliente 024', 1),
  ('c0240000-0000-4000-8000-000000000031', 'c0240000-0000-4000-8000-000000000001', NULL, 'WS interno 024', 1);
INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES
  ('c0240000-0000-4000-8000-000000000002', 'c0240000-0000-4000-8000-000000000001', 1, 'adm-h024@org.test', now(), 2, 'Admin 024'),
  ('c0240000-0000-4000-8000-000000000004', 'c0240000-0000-4000-8000-000000000001', 1, 'emi-h024@org.test', now(), 2, 'Emissor sem autoridade'),
  ('c0240000-0000-4000-8000-000000000006', 'c0240000-0000-4000-8000-000000000001', 1, 'reg-h024@org.test', now(), 2, 'Emissor que recuperou'),
  ('c0240000-0000-4000-8000-000000000008', 'c0240000-0000-4000-8000-000000000001', 1, 'membro-h024@org.test', now(), 2, 'Membro 024'),
  ('c0240000-0000-4000-8000-000000000010', 'c0240000-0000-4000-8000-000000000001', 2, 'ext-h024@cliente.test', now(), 2, 'Externo 024');
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES
  ('c0240000-0000-4000-8000-000000000003', 'c0240000-0000-4000-8000-000000000001', 'c0240000-0000-4000-8000-000000000002', 1, 2),
  ('c0240000-0000-4000-8000-000000000005', 'c0240000-0000-4000-8000-000000000001', 'c0240000-0000-4000-8000-000000000004', 1, 2),
  ('c0240000-0000-4000-8000-000000000007', 'c0240000-0000-4000-8000-000000000001', 'c0240000-0000-4000-8000-000000000006', 1, 2),
  ('c0240000-0000-4000-8000-000000000009', 'c0240000-0000-4000-8000-000000000001', 'c0240000-0000-4000-8000-000000000008', 1, 2);
INSERT INTO client_memberships (client_membership_id, organization_id, client_id, user_id, account_type, status) VALUES
  ('c0240000-0000-4000-8000-000000000011', 'c0240000-0000-4000-8000-000000000001', 'c0240000-0000-4000-8000-000000000020', 'c0240000-0000-4000-8000-000000000010', 2, 2);
COMMIT;
SET ROLE nekkora_provisioning;
SELECT provision_first_org_admin('c0240000-0000-4000-8000-000000000001', 'c0240000-0000-4000-8000-000000000003',
                                 'c0240000-0000-4000-8000-000000000002', 'homologacao-024', gen_random_uuid()) IS NOT NULL;
RESET ROLE;
BEGIN;
SELECT set_config('nekkora.organization_id', 'c0240000-0000-4000-8000-000000000001', true);
INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id)
SELECT 'c0240000-0000-4000-8000-000000000001', m, role_id, 1, 1, 'c0240000-0000-4000-8000-000000000003' FROM roles,
       unnest(ARRAY['c0240000-0000-4000-8000-000000000005', 'c0240000-0000-4000-8000-000000000007']::uuid[]) m
 WHERE code = 'ORG_ADMIN';
INSERT INTO workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status, assigned_by_membership_id)
SELECT 'c0240000-0000-4000-8000-000000000001', 'c0240000-0000-4000-8000-000000000031', 'c0240000-0000-4000-8000-000000000009', role_id, 2, 1,
       'c0240000-0000-4000-8000-000000000003' FROM roles WHERE code = 'OPERATOR';
INSERT INTO identity_invitations (invitation_id, organization_id, invited_account_type, email_normalized, issued_by, token_hash, expires_at, issued_by_membership_id) VALUES
  ('c0240000-0000-4000-8000-000000000101', 'c0240000-0000-4000-8000-000000000001', 1, 'aberto-adm-h024@org.test', 'c0240000-0000-4000-8000-000000000002', md5('h024a1') || md5('h024a2'), now() + interval '6 days', 'c0240000-0000-4000-8000-000000000003'),
  ('c0240000-0000-4000-8000-000000000102', 'c0240000-0000-4000-8000-000000000001', 1, 'aberto-emi-1-h024@org.test', 'c0240000-0000-4000-8000-000000000004', md5('h024b1') || md5('h024b2'), now() + interval '6 days', 'c0240000-0000-4000-8000-000000000005'),
  ('c0240000-0000-4000-8000-000000000103', 'c0240000-0000-4000-8000-000000000001', 1, 'aberto-emi-2-h024@org.test', 'c0240000-0000-4000-8000-000000000004', md5('h024c1') || md5('h024c2'), now() + interval '6 days', 'c0240000-0000-4000-8000-000000000005'),
  ('c0240000-0000-4000-8000-000000000104', 'c0240000-0000-4000-8000-000000000001', 1, 'aberto-reg-h024@org.test', 'c0240000-0000-4000-8000-000000000006', md5('h024d1') || md5('h024d2'), now() + interval '6 days', 'c0240000-0000-4000-8000-000000000007');
INSERT INTO identity_invitations (invitation_id, organization_id, invited_account_type, email_normalized, issued_by, token_hash, expires_at, issued_by_membership_id, revoked_at, revoke_reason, revoked_by_membership_id) VALUES
  ('c0240000-0000-4000-8000-000000000105', 'c0240000-0000-4000-8000-000000000001', 1, 'revogado-h024@org.test', 'c0240000-0000-4000-8000-000000000002', md5('h024e1') || md5('h024e2'), now() + interval '1 day', 'c0240000-0000-4000-8000-000000000003', now(), 1, 'c0240000-0000-4000-8000-000000000003');
INSERT INTO identity_invitations (invitation_id, organization_id, invited_account_type, email_normalized, issued_by, token_hash, expires_at, accepted_at, accepted_user_id) VALUES
  ('c0240000-0000-4000-8000-000000000106', 'c0240000-0000-4000-8000-000000000001', 1, 'membro-h024@org.test', 'c0240000-0000-4000-8000-000000000002', md5('h024f1') || md5('h024f2'), now() + interval '1 day', now(), 'c0240000-0000-4000-8000-000000000008');
INSERT INTO client_workspace_grants (grant_id, organization_id, client_id, client_membership_id, workspace_id, status, approved_by_membership_id, approved_at, starts_at, next_review_at)
VALUES ('c0240000-0000-4000-8000-000000000201', 'c0240000-0000-4000-8000-000000000001', 'c0240000-0000-4000-8000-000000000020',
        'c0240000-0000-4000-8000-000000000011', 'c0240000-0000-4000-8000-000000000030', 2, 'c0240000-0000-4000-8000-000000000003', now(), now(), now() + interval '30 days');
COMMIT;
-- EMI perde o ORG_ADMIN; REG perde e recupera (atribuicao nova), tudo ANTES da 024
BEGIN;
SELECT set_config('nekkora.organization_id', 'c0240000-0000-4000-8000-000000000001', true);
UPDATE organization_role_assignments SET status = 2, revoked_at = now(), revoked_by_membership_id = 'c0240000-0000-4000-8000-000000000003'
 WHERE organization_id = 'c0240000-0000-4000-8000-000000000001' AND membership_id IN ('c0240000-0000-4000-8000-000000000005', 'c0240000-0000-4000-8000-000000000007') AND status = 1;
INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id)
SELECT 'c0240000-0000-4000-8000-000000000001', 'c0240000-0000-4000-8000-000000000007', role_id, 1, 1, 'c0240000-0000-4000-8000-000000000003' FROM roles WHERE code = 'ORG_ADMIN';
COMMIT;
