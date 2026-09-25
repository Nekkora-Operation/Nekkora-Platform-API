-- 00 - papel: nekkora (somente inspecao de catalogo).
-- Confronta o catalogo instalado com a matriz aprovada (matriz de privilegios rev. 4, secao 13, e
-- contrato de funcoes rev. 3, secao 2): diferenca simetrica entre o esperado e o instalado para
-- privilegios de tabela, de coluna e de funcao dos papeis nekkora_*. Esperado: 0 linhas em cada.
\pset pager off

\echo === papeis
SELECT rolname, rolsuper, rolcanlogin, rolinherit, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls
FROM pg_roles WHERE rolname LIKE 'nekkora%' ORDER BY 1;
SELECT count(*) AS memberships_entre_papeis FROM pg_auth_members am JOIN pg_roles m ON m.oid = am.member WHERE m.rolname LIKE 'nekkora%';
SELECT count(*) AS nekkora_app_existe FROM pg_roles WHERE rolname = 'nekkora_app';

CREATE TEMP TABLE esperado_tabela (papel TEXT, tabela TEXT, priv TEXT);
INSERT INTO esperado_tabela
SELECT p, t, v FROM (VALUES
  -- nekkora_platform
  ('nekkora_platform', 'organizations', 'SELECT,UPDATE'),
  ('nekkora_platform', 'clients', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'workspaces', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'users', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'user_credentials', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'organization_domains', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'organization_memberships', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'client_memberships', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'identity_invitations', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'email_verification_tokens', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'password_reset_tokens', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'user_sessions', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'permissions', 'SELECT'),
  ('nekkora_platform', 'roles', 'SELECT'),
  ('nekkora_platform', 'role_permissions', 'SELECT'),
  ('nekkora_platform', 'organization_role_assignments', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'workspace_role_assignments', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'client_workspace_grants', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'client_workspace_grant_reviews', 'SELECT,INSERT'),
  ('nekkora_platform', 'client_workspace_grant_approvals', 'SELECT,INSERT'),
  ('nekkora_platform', 'workers', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'agents', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'worker_workspace_assignments', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'worker_agent_assignments', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'agent_credentials', 'SELECT,INSERT'),
  ('nekkora_platform', 'automations', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'automation_versions', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'executions', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'execution_events', 'SELECT'),
  ('nekkora_platform', 'logs', 'SELECT'),
  ('nekkora_platform', 'workspace_parameters', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'workspace_secrets', 'SELECT,INSERT,UPDATE'),
  ('nekkora_platform', 'workspace_parameter_values', 'SELECT,INSERT'),
  ('nekkora_platform', 'workspace_configuration_events', 'SELECT,INSERT'),
  ('nekkora_platform', 'automation_version_bindings', 'SELECT,INSERT,UPDATE,DELETE'),
  ('nekkora_platform', 'automation_version_parameter_bindings', 'SELECT,INSERT'),
  ('nekkora_platform', 'automation_version_secret_bindings', 'SELECT,INSERT'),
  ('nekkora_platform', 'automation_schedules', 'SELECT,INSERT'),
  ('nekkora_platform', 'automation_schedule_revisions', 'SELECT,INSERT'),
  ('nekkora_platform', 'automation_schedule_occurrences', 'SELECT'),
  ('nekkora_platform', 'organization_resource_limit_revisions', 'SELECT'),
  ('nekkora_platform', 'organization_retention_policy_revisions', 'SELECT'),
  -- nekkora_ingestion
  ('nekkora_ingestion', 'execution_events', 'SELECT,INSERT'),
  ('nekkora_ingestion', 'logs', 'SELECT,INSERT'),
  ('nekkora_ingestion', 'executions', 'SELECT'),
  ('nekkora_ingestion', 'worker_agent_assignments', 'SELECT'),
  ('nekkora_ingestion', 'organization_retention_policy_revisions', 'SELECT'),
  -- nekkora_scheduler_owner
  ('nekkora_scheduler_owner', 'organizations', 'SELECT,UPDATE'),
  ('nekkora_scheduler_owner', 'automation_schedules', 'SELECT,UPDATE'),
  ('nekkora_scheduler_owner', 'workspace_secrets', 'SELECT,UPDATE'),
  ('nekkora_scheduler_owner', 'automation_schedule_occurrences', 'SELECT,INSERT,UPDATE'),
  ('nekkora_scheduler_owner', 'automation_schedule_revisions', 'SELECT'),
  ('nekkora_scheduler_owner', 'automations', 'SELECT'),
  ('nekkora_scheduler_owner', 'automation_versions', 'SELECT'),
  ('nekkora_scheduler_owner', 'workspaces', 'SELECT'),
  ('nekkora_scheduler_owner', 'automation_version_bindings', 'SELECT'),
  ('nekkora_scheduler_owner', 'automation_version_secret_bindings', 'SELECT'),
  ('nekkora_scheduler_owner', 'organization_resource_limit_revisions', 'SELECT'),
  ('nekkora_scheduler_owner', 'executions', 'SELECT,INSERT'),
  -- nekkora_provisioning_owner
  ('nekkora_provisioning_owner', 'organizations', 'SELECT,INSERT,UPDATE'),
  ('nekkora_provisioning_owner', 'organization_resource_limit_revisions', 'SELECT,INSERT'),
  ('nekkora_provisioning_owner', 'organization_retention_policy_revisions', 'SELECT,INSERT'),
  ('nekkora_provisioning_owner', 'organization_role_assignments', 'SELECT,INSERT'),
  ('nekkora_provisioning_owner', 'identity_provisioning_events', 'INSERT'),
  ('nekkora_provisioning_owner', 'roles', 'SELECT'),
  -- nekkora_retention_owner (011, inalterado)
  ('nekkora_retention_owner', 'execution_events', 'SELECT'),
  ('nekkora_retention_owner', 'logs', 'SELECT'),
  ('nekkora_retention_owner', 'organization_usage_daily', 'SELECT,INSERT,UPDATE'),
  ('nekkora_retention_owner', 'organization_purge_runs', 'SELECT,INSERT'),
  ('nekkora_retention_owner', 'organization_retention_policy_revisions', 'SELECT')
) x(p, t, privs), unnest(string_to_array(privs, ',')) v;

CREATE TEMP TABLE instalado_tabela AS
SELECT g.rolname AS papel, c.relname AS tabela, a.privilege_type AS priv
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
CROSS JOIN LATERAL aclexplode(c.relacl) a
JOIN pg_roles g ON g.oid = a.grantee
WHERE n.nspname = 'public' AND c.relkind = 'r'
  AND g.rolname LIKE 'nekkora\_%'
  AND g.oid <> c.relowner;          -- privilegio implicito do proprietario nao e concessao

\echo === tabela: esperado e nao instalado (deve ser vazio)
SELECT * FROM esperado_tabela EXCEPT SELECT * FROM instalado_tabela ORDER BY 1, 2, 3;
\echo === tabela: instalado e nao esperado (deve ser vazio)
SELECT * FROM instalado_tabela EXCEPT SELECT * FROM esperado_tabela ORDER BY 1, 2, 3;

CREATE TEMP TABLE esperado_coluna (papel TEXT, tabela TEXT, coluna TEXT, priv TEXT);
INSERT INTO esperado_coluna VALUES
  ('nekkora_platform', 'agent_credentials', 'revoked_at', 'UPDATE'),
  ('nekkora_platform', 'agent_credentials', 'revoked_by_membership_id', 'UPDATE'),
  ('nekkora_platform', 'agent_credentials', 'revocation_reason', 'UPDATE'),
  ('nekkora_platform', 'automation_version_parameter_bindings', 'parameter_id', 'UPDATE'),
  ('nekkora_platform', 'automation_version_parameter_bindings', 'parameter_value_id', 'UPDATE'),
  ('nekkora_platform', 'automation_version_secret_bindings', 'secret_id', 'UPDATE'),
  ('nekkora_platform', 'automation_schedules', 'name', 'UPDATE'),
  ('nekkora_retention_owner', 'execution_events', 'payload', 'UPDATE'),
  ('nekkora_retention_owner', 'execution_events', 'purged_at', 'UPDATE'),
  ('nekkora_retention_owner', 'execution_events', 'purged_by_user_id', 'UPDATE'),
  ('nekkora_retention_owner', 'execution_events', 'purge_run_id', 'UPDATE'),
  ('nekkora_retention_owner', 'logs', 'payload', 'UPDATE'),
  ('nekkora_retention_owner', 'logs', 'purged_at', 'UPDATE'),
  ('nekkora_retention_owner', 'logs', 'purged_by_user_id', 'UPDATE'),
  ('nekkora_retention_owner', 'logs', 'purge_run_id', 'UPDATE');

CREATE TEMP TABLE instalado_coluna AS
SELECT g.rolname AS papel, c.relname AS tabela, at.attname::text AS coluna, a.privilege_type AS priv
FROM pg_attribute at
JOIN pg_class c ON c.oid = at.attrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
CROSS JOIN LATERAL aclexplode(at.attacl) a
JOIN pg_roles g ON g.oid = a.grantee
WHERE n.nspname = 'public' AND at.attacl IS NOT NULL AND g.rolname LIKE 'nekkora\_%';

\echo === coluna: esperado e nao instalado (deve ser vazio)
SELECT * FROM esperado_coluna EXCEPT SELECT * FROM instalado_coluna ORDER BY 1, 2, 3;
\echo === coluna: instalado e nao esperado (deve ser vazio)
SELECT * FROM instalado_coluna EXCEPT SELECT * FROM esperado_coluna ORDER BY 1, 2, 3;

CREATE TEMP TABLE esperado_funcao (funcao TEXT, owner TEXT, secdef BOOLEAN, execute_para TEXT);
INSERT INTO esperado_funcao VALUES
  ('assign_received_at_and_lock_barrier(uuid,integer)', 'nekkora_retention_owner', true, 'nekkora_ingestion'),
  ('close_usage_day(uuid,integer,date)', 'nekkora_retention_owner', true, 'nekkora_retention_executor'),
  ('purge_expired_records(uuid,integer,uuid,integer)', 'nekkora_retention_owner', true, ''),
  ('reconsolidate_usage_day(uuid,integer,date)', 'nekkora_retention_owner', true, 'nekkora_retention_executor'),
  ('process_schedule_occurrence(uuid,boolean)', 'nekkora_scheduler_owner', false, ''),
  ('process_schedule_occurrence(uuid,uuid,boolean)', 'nekkora_scheduler_owner', true, 'nekkora_scheduler'),
  ('create_schedule_occurrence(uuid,uuid,uuid,timestamp with time zone)', 'nekkora_scheduler_owner', true, 'nekkora_scheduler'),
  ('set_schedule_status(uuid,uuid,smallint,uuid)', 'nekkora_scheduler_owner', true, 'nekkora_platform'),
  ('provision_organization(uuid,text,text,uuid,resource_limit_input[])', 'nekkora_provisioning_owner', true, 'nekkora_provisioning'),
  ('provision_first_org_admin(uuid,uuid,uuid,text,uuid)', 'nekkora_provisioning_owner', true, 'nekkora_provisioning'),
  ('provision_resource_limit(uuid,integer,smallint,bigint,uuid)', 'nekkora_provisioning_owner', true, 'nekkora_provisioning'),
  ('provision_retention_policy(uuid,integer,smallint,integer,uuid)', 'nekkora_provisioning_owner', true, 'nekkora_provisioning');

CREATE TEMP TABLE instalado_funcao AS
SELECT regexp_replace(p.oid::regprocedure::text, ', ', ',', 'g') AS funcao,
       pg_get_userbyid(p.proowner) AS owner,
       p.prosecdef AS secdef,
       coalesce((SELECT string_agg(g.rolname, ',' ORDER BY g.rolname)
                 FROM aclexplode(p.proacl) a JOIN pg_roles g ON g.oid = a.grantee
                 WHERE a.privilege_type = 'EXECUTE' AND a.grantee <> p.proowner), '') AS execute_para
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'public' AND (p.prosecdef OR pg_get_userbyid(p.proowner) <> current_user);

\echo === funcao: esperado e nao instalado (deve ser vazio)
SELECT * FROM esperado_funcao EXCEPT SELECT * FROM instalado_funcao ORDER BY 1;
\echo === funcao: instalado e nao esperado (deve ser vazio)
SELECT * FROM instalado_funcao EXCEPT SELECT * FROM esperado_funcao ORDER BY 1;
\echo === funcoes privilegiadas: search_path e EXECUTE de PUBLIC
SELECT p.oid::regprocedure AS funcao, p.proconfig, has_function_privilege('public', p.oid, 'EXECUTE') AS execute_public
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'public' AND (p.prosecdef OR pg_get_userbyid(p.proowner) <> current_user) ORDER BY 1;

\echo === funcoes invoker de public sem search_path fixo (so as sql puras de validacao, sem acesso a tabela)
SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'public' AND p.proconfig IS NULL AND p.prokind = 'f'
  AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = p.oid AND d.deptype = 'e')   -- exclui funcoes de extensao (pgcrypto)
ORDER BY 1;

\echo === RLS: tabelas de public por situacao
SELECT CASE WHEN c.relrowsecurity AND c.relforcerowsecurity THEN 'RLS enable+force'
            WHEN c.relrowsecurity THEN 'RLS enable (sem force)' ELSE 'sem RLS' END AS situacao,
       count(*) AS tabelas,
       string_agg(c.relname, ', ' ORDER BY c.relname) FILTER (WHERE NOT c.relrowsecurity) AS sem_rls
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'r' GROUP BY 1;
\echo === RLS: politicas por nome e papeis (tenant diretas: 39 x 2; derivadas: 3 x 1)
SELECT polname, array_to_string(ARRAY(SELECT rolname FROM pg_roles WHERE oid = ANY(polroles) ORDER BY 1), ',') AS papeis,
       polcmd, polpermissive, count(*) AS tabelas
FROM pg_policy GROUP BY 1, 2, 3, 4 ORDER BY 1, 2;
\echo === RLS: tabela com organization_id sem as duas politicas (deve ser vazio)
SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'r'
  AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'organization_id')
  AND (SELECT count(*) FROM pg_policy p WHERE p.polrelid = c.oid) <> 2;
\echo === exemplo de politica instalada (workspaces)
SELECT polname, pg_get_expr(polqual, polrelid) AS using_expr, pg_get_expr(polwithcheck, polrelid) AS check_expr
FROM pg_policy WHERE polrelid = 'public.workspaces'::regclass ORDER BY 1;
SELECT polname, pg_get_expr(polqual, polrelid) AS using_expr FROM pg_policy WHERE polrelid = 'public.user_credentials'::regclass;

\echo === schema public e privilegios padrao
SELECT nspname, nspacl FROM pg_namespace WHERE nspname = 'public';
SELECT pg_get_userbyid(defaclrole) AS papel, defaclnamespace, defaclobjtype, defaclacl FROM pg_default_acl;
