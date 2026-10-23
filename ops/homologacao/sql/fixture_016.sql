-- Fixture SINTETICA da 016 na homologacao (desenho P1a, sec. 5.1) - papel: admin_cluster. Organizacao PROPRIA da 016
-- ("Homologacao 016 sintetica <id>"): titular ORG_ADMIN, segundo ORG_ADMIN e um membro (portador do segundo contato).
-- Senhas sinteticas por PHC (Argon2id) calculado pelo runner; os fatores TOTP sao cadastrados DEPOIS pela API (login
-- real pelo gateway), e os contatos pela ferramenta de homologacao (funcao real ops_contact_add). Nenhum dado real.
-- Recebe -v: ambiente, org, adm1, adm2, membro, m_adm1, m_adm2, m_membro, e-mails (email_adm1, email_adm2,
-- email_membro) e phc_adm1, phc_adm2. Controle de transacao: BEGIN/COMMIT explicitos, psql -v ON_ERROR_STOP=1.
\set ON_ERROR_STOP on
BEGIN;
-- contexto da organizacao na propria transacao (o caminho da aplicacao): desde a 017, credencial, fator, codigos, desafios
-- e tokens da conta passam pela trigger refuse_local_entry_for_linked_account, que confere nekkora.organization_id
-- (identity_check_context). Nenhuma protecao e desligada (missao PLATFORM-API-V1-003).
SELECT set_config('nekkora.organization_id', :'org', true) IS NOT NULL AS fx_contexto \gset
INSERT INTO organizations (organization_id, name, status) VALUES (:'org', 'Homologacao 016 sintetica ' || :'ambiente', 1);
INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES
    (:'adm1', :'org', 1, :'email_adm1', now(), 2, 'Titular 016'),
    (:'adm2', :'org', 1, :'email_adm2', now(), 2, 'Outro admin 016'),
    (:'membro', :'org', 1, :'email_membro', now(), 2, 'Membro 016');
INSERT INTO user_credentials (user_id, password_hash) VALUES (:'adm1', :'phc_adm1'), (:'adm2', :'phc_adm2');
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES
    (:'m_adm1', :'org', :'adm1', 1, 2), (:'m_adm2', :'org', :'adm2', 1, 2), (:'m_membro', :'org', :'membro', 1, 2);
-- primeira atribuicao da organizacao sem responsavel: SO pelo caminho autorizado da 012 (provision_first_org_admin,
-- SECURITY DEFINER; a trigger recusa INSERT direto sem responsavel, inclusive do admin_cluster - achado do P1c); a
-- segunda, atribuida pelo primeiro admin
SELECT provision_first_org_admin(:'org', :'m_adm1', :'adm1', 'homologacao-016', gen_random_uuid()) IS NOT NULL AS fx_bootstrap \gset
INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id)
SELECT :'org', :'m_adm2', role_id, 1, 1, :'m_adm1' FROM roles WHERE code = 'ORG_ADMIN';
COMMIT;
SELECT 'fixture_016: organizacao=' || organization_id || ' usuarios=' || (SELECT count(*) FROM users u WHERE u.organization_id = o.organization_id)
FROM organizations o WHERE organization_id = :'org';
