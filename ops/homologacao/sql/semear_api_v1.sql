-- Semeadura DECLARADA das jornadas da API (missao PLATFORM-API-V1-003, item 7) - papel: admin_cluster. Prepara SO o que a
-- API v1 ainda nao cria: uma organizacao "legada" com DUAS contas locais antigas (antes da entrada geral), para a jornada de
-- vinculacao (a titular da identidade prova a conta antiga) e para a de reducao de acesso (o membro e suspenso pela API).
-- Nao existe rota de convite/criacao de conta de organizacao (lacuna do inventario), por isso este passo e no banco e esta
-- DECLARADO como preparacao, nunca como jornada. Tudo o mais acontece pela API.
--
-- Caminho correto, sem desligar protecao: organizacao e contas no contexto da propria organizacao (nekkora.organization_id,
-- local a transacao: a trigger refuse_local_entry_for_linked_account da 017 o confere na credencial); primeira atribuicao de
-- ORG_ADMIN SO por provision_first_org_admin (a trigger da 012 recusa INSERT direto sem responsavel); nenhum fator de MFA
-- semeado (o primeiro fator e cadastrado pela API, no primeiro acesso). Senhas sinteticas por PHC (PhcTool real).
--
-- Recebe -v: org, nome, adm, membro, m_adm, m_membro, email_adm, email_membro, phc_adm, phc_membro.
\set ON_ERROR_STOP on
BEGIN;
SELECT set_config('nekkora.organization_id', :'org', true) IS NOT NULL AS sa_contexto \gset
INSERT INTO organizations (organization_id, name, status) VALUES (:'org', :'nome', 1);
INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES
    (:'adm', :'org', 1, :'email_adm', now(), 2, 'Administradora legada'),
    (:'membro', :'org', 1, :'email_membro', now(), 2, 'Membro legado');
INSERT INTO user_credentials (user_id, password_hash) VALUES (:'adm', :'phc_adm'), (:'membro', :'phc_membro');
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES
    (:'m_adm', :'org', :'adm', 1, 2), (:'m_membro', :'org', :'membro', 1, 2);
SELECT provision_first_org_admin(:'org', :'m_adm', :'adm', 'homologacao-api-v1', gen_random_uuid()) IS NOT NULL AS sa_bootstrap \gset
-- conferencia ANTES do COMMIT: uma recusa aqui desfaz tudo
DO $$
BEGIN
    IF (SELECT count(*) FROM users WHERE organization_id = current_setting('nekkora.organization_id')::uuid) <> 2
       OR (SELECT count(*) FROM user_credentials c JOIN users u USING (user_id)
            WHERE u.organization_id = current_setting('nekkora.organization_id')::uuid) <> 2
       OR (SELECT count(*) FROM organization_role_assignments WHERE organization_id = current_setting('nekkora.organization_id')::uuid) <> 1
       OR EXISTS (SELECT 1 FROM user_mfa_factors WHERE organization_id = current_setting('nekkora.organization_id')::uuid) THEN
        RAISE EXCEPTION 'SEMEADURA_API_V1_RECUSADA: conferencia antes do COMMIT';
    END IF;
END $$;
COMMIT;
SELECT 'SEMEADURA_API_V1_OK';
