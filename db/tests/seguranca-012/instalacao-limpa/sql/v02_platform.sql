-- v02 - papel: nekkora_platform, uma conexao. Fluxo permitido na organizacao de verificacao e
-- isolamento frente a organizacao interna. Variaveis: org_int, org_v, user_v, mem_v, ws_v.
\pset pager off
SELECT current_user AS papel, pg_backend_pid() AS pid;
\echo === fluxo permitido: usuario, vinculo, credencial e workspace no contexto da organizacao V
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL nekkora.organization_id = :'org_v';
INSERT INTO users (user_id, organization_id, account_type, email, status, display_name)
VALUES (:'user_v', :'org_v', 1, 'admin@verificacao.local', 2, 'Admin Verificacao');
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status)
VALUES (:'mem_v', :'org_v', :'user_v', 1, 2);
INSERT INTO user_credentials (user_id, password_hash) VALUES (:'user_v', 'hash-de-verificacao');
INSERT INTO workspaces (workspace_id, organization_id, name, status) VALUES (:'ws_v', :'org_v', 'WS Verificacao', 1);
SELECT (SELECT count(*) FROM organizations) AS orgs, (SELECT count(*) FROM users) AS users,
       (SELECT count(*) FROM user_credentials) AS credenciais, (SELECT count(*) FROM workspaces) AS workspaces,
       (SELECT min(organization_id::text) FROM organizations) AS org_vista;
COMMIT;
\set ON_ERROR_STOP off
\echo === isolamento: contexto da organizacao interna nao ve nem altera V
BEGIN;
SET LOCAL nekkora.organization_id = :'org_int';
SELECT (SELECT count(*) FROM organizations) AS orgs, (SELECT min(organization_id::text) FROM organizations) AS org_vista,
       (SELECT count(*) FROM users) AS users_da_interna, (SELECT count(*) FROM workspaces WHERE workspace_id = :'ws_v') AS ws_v_visivel,
       (SELECT count(*) FROM user_credentials WHERE user_id = :'user_v') AS credencial_v_visivel;
UPDATE workspaces SET name = 'invadido' WHERE workspace_id = :'ws_v';
COMMIT;
BEGIN;
SET LOCAL nekkora.organization_id = :'org_int';
INSERT INTO workspaces (organization_id, name, status) VALUES (:'org_v', 'forjado', 1);
ROLLBACK;
\echo === sem contexto (mesma conexao, transacao seguinte)
SELECT pg_backend_pid() AS mesmo_pid, current_setting('nekkora.organization_id', true) AS contexto,
       (SELECT count(*) FROM organizations) AS orgs, (SELECT count(*) FROM workspaces) AS workspaces;
INSERT INTO workspaces (organization_id, name, status) VALUES (:'org_v', 'sem contexto', 1);
\echo === operacoes nao concedidas ao platform
BEGIN; SET LOCAL nekkora.organization_id = :'org_v';
INSERT INTO organizations (organization_id, name, status) VALUES (gen_random_uuid(), 'x', 1);
ROLLBACK;
SELECT provision_first_org_admin(:'org_v', :'mem_v', :'user_v', 'x', gen_random_uuid());
\echo === bootstrap de ORG_ADMIN fora da funcao: rejeitado pela trigger
BEGIN; SET LOCAL nekkora.organization_id = :'org_v';
INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id)
SELECT :'org_v', :'mem_v', role_id, 1, 1, NULL FROM roles WHERE code = 'ORG_ADMIN';
ROLLBACK;
