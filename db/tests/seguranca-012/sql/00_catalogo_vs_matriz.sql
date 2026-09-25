-- 00 - papel: nekkora (somente inspecao de catalogo).
-- Confronta o catalogo instalado com a matriz aprovada (matriz de privilegios rev. 5, secao 13, e
-- contrato de funcoes rev. 4, secao 2): diferenca simetrica entre o esperado e o instalado para
-- privilegios de tabela, de coluna e de funcao (qualquer beneficiario), atributos e memberships dos
-- papeis protegidos, CREATE efetivo, RLS e politicas. Cada verificacao e uma assercao que aborta.
\pset pager off

-- Papeis protegidos: servicos LOGIN e proprietarios NOLOGIN. O papel de migrations (superusuario)
-- fica de fora por definicao.
CREATE TEMP TABLE papeis_protegidos (papel TEXT PRIMARY KEY, login BOOLEAN);
INSERT INTO papeis_protegidos VALUES
  ('nekkora_platform', true), ('nekkora_ingestion', true), ('nekkora_scheduler', true),
  ('nekkora_provisioning', true), ('nekkora_retention_executor', true),
  ('nekkora_scheduler_owner', false), ('nekkora_provisioning_owner', false),
  ('nekkora_retention_owner', false), ('nekkora_retention_tables_owner', false);

\echo === papeis
SELECT rolname, rolsuper, rolcanlogin, rolinherit, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls
FROM pg_roles WHERE rolname LIKE 'nekkora%' ORDER BY 1;
SELECT pg_temp.afirma((SELECT count(*) FROM pg_roles r JOIN papeis_protegidos p ON p.papel = r.rolname) = 9, 'os 9 papeis protegidos existem');
SELECT pg_temp.afirma(NOT EXISTS (
    SELECT 1 FROM pg_roles r JOIN papeis_protegidos p ON p.papel = r.rolname
    WHERE r.rolsuper OR r.rolbypassrls OR r.rolcreaterole OR r.rolcreatedb OR r.rolreplication OR r.rolcanlogin <> p.login),
  'papeis protegidos sem atributo privilegiado e com LOGIN/NOLOGIN esperado');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_app'), 'nekkora_app removido');

\echo === memberships envolvendo papeis protegidos, nos dois sentidos (deve ser vazio)
SELECT r.rolname AS papel_concedido, m.rolname AS membro
FROM pg_auth_members am JOIN pg_roles r ON r.oid = am.roleid JOIN pg_roles m ON m.oid = am.member
WHERE r.rolname IN (SELECT papel FROM papeis_protegidos) OR m.rolname IN (SELECT papel FROM papeis_protegidos);
SELECT pg_temp.afirma(NOT EXISTS (
    SELECT 1 FROM pg_auth_members am JOIN pg_roles r ON r.oid = am.roleid JOIN pg_roles m ON m.oid = am.member
    WHERE r.rolname IN (SELECT papel FROM papeis_protegidos) OR m.rolname IN (SELECT papel FROM papeis_protegidos)),
  'nenhuma membership envolvendo papel protegido, em qualquer sentido e com qualquer outro papel');

\echo === CREATE efetivo dos papeis protegidos (deve ser vazio)
SELECT p.papel, has_schema_privilege(p.papel, 'public', 'CREATE') AS create_em_public,
       has_database_privilege(p.papel, current_database(), 'CREATE') AS create_no_banco
FROM papeis_protegidos p
WHERE has_schema_privilege(p.papel, 'public', 'CREATE') OR has_database_privilege(p.papel, current_database(), 'CREATE');
SELECT pg_temp.afirma(NOT EXISTS (
    SELECT 1 FROM papeis_protegidos p
    WHERE has_schema_privilege(p.papel, 'public', 'CREATE') OR has_database_privilege(p.papel, current_database(), 'CREATE')),
  'nenhum papel protegido com CREATE efetivo no schema public ou no banco');
SELECT pg_temp.afirma(NOT has_schema_privilege('public', 'public', 'CREATE'), 'CREATE em public fora de PUBLIC');

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

-- Qualquer beneficiario conta (PUBLIC ou papel de qualquer nome), exceto o proprietario da tabela
-- (privilegio implicito, nao concessao): uma concessao a um papel fora do prefixo nekkora_ tambem aparece.
CREATE TEMP TABLE instalado_tabela AS
SELECT coalesce(g.rolname, 'PUBLIC') AS papel, c.relname AS tabela, a.privilege_type AS priv
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
CROSS JOIN LATERAL aclexplode(c.relacl) a
LEFT JOIN pg_roles g ON g.oid = a.grantee
WHERE n.nspname = 'public' AND c.relkind = 'r'
  AND a.grantee <> c.relowner;

\echo === tabela: esperado e nao instalado (deve ser vazio)
SELECT * FROM esperado_tabela EXCEPT SELECT * FROM instalado_tabela ORDER BY 1, 2, 3;
\echo === tabela: instalado e nao esperado (deve ser vazio)
SELECT * FROM instalado_tabela EXCEPT SELECT * FROM esperado_tabela ORDER BY 1, 2, 3;
SELECT pg_temp.afirma((SELECT count(*) FROM (SELECT * FROM esperado_tabela EXCEPT SELECT * FROM instalado_tabela) d) = 0, 'privilegios de tabela esperados todos instalados');
SELECT pg_temp.afirma((SELECT count(*) FROM (SELECT * FROM instalado_tabela EXCEPT SELECT * FROM esperado_tabela) d) = 0, 'nenhum privilegio de tabela alem da matriz (qualquer beneficiario)');
SELECT pg_temp.afirma((SELECT count(*) FROM esperado_tabela) = 145, 'matriz de tabela com 145 privilegios');

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
SELECT coalesce(g.rolname, 'PUBLIC') AS papel, c.relname AS tabela, at.attname::text AS coluna, a.privilege_type AS priv
FROM pg_attribute at
JOIN pg_class c ON c.oid = at.attrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
CROSS JOIN LATERAL aclexplode(at.attacl) a
LEFT JOIN pg_roles g ON g.oid = a.grantee
WHERE n.nspname = 'public' AND c.relkind = 'r' AND at.attacl IS NOT NULL;

\echo === coluna: esperado e nao instalado (deve ser vazio)
SELECT * FROM esperado_coluna EXCEPT SELECT * FROM instalado_coluna ORDER BY 1, 2, 3;
\echo === coluna: instalado e nao esperado (deve ser vazio)
SELECT * FROM instalado_coluna EXCEPT SELECT * FROM esperado_coluna ORDER BY 1, 2, 3;
SELECT pg_temp.afirma((SELECT count(*) FROM (SELECT * FROM esperado_coluna EXCEPT SELECT * FROM instalado_coluna) d) = 0, 'privilegios de coluna esperados todos instalados');
SELECT pg_temp.afirma((SELECT count(*) FROM (SELECT * FROM instalado_coluna EXCEPT SELECT * FROM esperado_coluna) d) = 0, 'nenhum privilegio de coluna alem da matriz (qualquer beneficiario)');

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
WHERE n.nspname = 'public'
  AND (p.prosecdef
       OR pg_get_userbyid(p.proowner) <> current_user
       -- qualquer EXECUTE explicito para alguem que nao seja o dono nem PUBLIC (ex.: GRANT a um servico
       -- numa funcao invoker da baseline) tambem entra na comparacao
       OR EXISTS (SELECT 1 FROM aclexplode(p.proacl) a WHERE a.grantee <> p.proowner AND a.grantee <> 0));

\echo === funcao: esperado e nao instalado (deve ser vazio)
SELECT * FROM esperado_funcao EXCEPT SELECT * FROM instalado_funcao ORDER BY 1;
\echo === funcao: instalado e nao esperado (deve ser vazio)
SELECT * FROM instalado_funcao EXCEPT SELECT * FROM esperado_funcao ORDER BY 1;
SELECT pg_temp.afirma((SELECT count(*) FROM (SELECT * FROM esperado_funcao EXCEPT SELECT * FROM instalado_funcao) d) = 0, 'funcoes privilegiadas esperadas todas instaladas (owner, secdef, EXECUTE)');
SELECT pg_temp.afirma((SELECT count(*) FROM (SELECT * FROM instalado_funcao EXCEPT SELECT * FROM esperado_funcao) d) = 0, 'nenhuma funcao privilegiada ou EXECUTE explicito alem da matriz');
SELECT pg_temp.afirma(NOT EXISTS (
    SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.prosecdef
      AND (has_function_privilege('public', p.oid, 'EXECUTE')
           OR NOT EXISTS (SELECT 1 FROM unnest(p.proconfig) c WHERE c = 'search_path=pg_catalog, pg_temp'))),
  'toda SECURITY DEFINER com search_path = pg_catalog, pg_temp e sem EXECUTE para PUBLIC');
\echo === funcoes privilegiadas: search_path e EXECUTE de PUBLIC
SELECT p.oid::regprocedure AS funcao, p.proconfig, has_function_privilege('public', p.oid, 'EXECUTE') AS execute_public
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'public' AND (p.prosecdef OR pg_get_userbyid(p.proowner) <> current_user) ORDER BY 1;

\echo === funcoes invoker de public sem search_path fixo (so as sql puras de validacao, sem acesso a tabela)
SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'public' AND p.proconfig IS NULL AND p.prokind = 'f'
  AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = p.oid AND d.deptype = 'e')   -- exclui funcoes de extensao (pgcrypto)
ORDER BY 1;
SELECT pg_temp.afirma((
    SELECT string_agg(p.proname, ',' ORDER BY p.proname) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.proconfig IS NULL AND p.prokind = 'f'
      AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = p.oid AND d.deptype = 'e'))
  = 'is_valid_command_contract,is_valid_cron_expression,is_valid_event_payload,is_valid_log_payload,is_valid_relative_entrypoint',
  'sem search_path fixo so as 5 funcoes sql puras de validacao');

\echo === RLS: tabelas de public por situacao
SELECT CASE WHEN c.relrowsecurity AND c.relforcerowsecurity THEN 'RLS enable+force'
            WHEN c.relrowsecurity THEN 'RLS enable (sem force)' ELSE 'sem RLS' END AS situacao,
       count(*) AS tabelas,
       string_agg(c.relname, ', ' ORDER BY c.relname) FILTER (WHERE NOT c.relrowsecurity) AS sem_rls
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'r' GROUP BY 1;

-- Classificacao esperada: direta = tem organization_id; derivada = as 3 ligadas a users por user_id;
-- catalogo = as 3 restantes, sem RLS.
CREATE TEMP TABLE classe_tabela AS
SELECT c.oid AS relid, c.relname,
       CASE WHEN EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'organization_id' AND NOT a.attisdropped) THEN 'direta'
            WHEN c.relname IN ('user_credentials', 'password_reset_tokens', 'email_verification_tokens') THEN 'derivada'
            ELSE 'catalogo' END AS classe,
       c.relrowsecurity AS rls, c.relforcerowsecurity AS force
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'r';
SELECT pg_temp.afirma((SELECT count(*) FROM classe_tabela WHERE classe = 'direta') = 39, '39 tabelas diretas');
SELECT pg_temp.afirma((SELECT string_agg(relname, ',' ORDER BY relname) FROM classe_tabela WHERE classe = 'catalogo') = 'permissions,role_permissions,roles', 'catalogo global = permissions, role_permissions, roles');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM classe_tabela WHERE classe <> 'catalogo' AND NOT (rls AND force)), 'RLS enable+force em toda tabela direta e derivada');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM classe_tabela WHERE classe = 'catalogo' AND (rls OR force)), 'catalogo global sem RLS');

\echo === RLS: politicas por nome e papeis
SELECT polname, array_to_string(ARRAY(SELECT rolname FROM pg_roles WHERE oid = ANY(polroles) ORDER BY 1), ',') AS papeis,
       polcmd, polpermissive, count(*) AS tabelas
FROM pg_policy GROUP BY 1, 2, 3, 4 ORDER BY 1, 2;

-- Conjunto esperado de politicas, com papeis, comando, tipo e expressoes exatas (USING = WITH CHECK).
CREATE TEMP TABLE esperado_politica AS
SELECT t.relname, x.polname, x.papeis, x.expr
FROM classe_tabela t
CROSS JOIN LATERAL (VALUES
    ('tenant_request_context', 'nekkora_ingestion,nekkora_platform',
     '(organization_id = (NULLIF(current_setting(''nekkora.organization_id''::text, true), ''''::text))::uuid)'),
    ('tenant_function_context', 'nekkora_provisioning_owner,nekkora_retention_owner,nekkora_scheduler_owner',
     '(organization_id = (NULLIF(current_setting(''nekkora.fn_organization_id''::text, true), ''''::text))::uuid)')
) x(polname, papeis, expr)
WHERE t.classe = 'direta'
UNION ALL
SELECT t.relname, 'tenant_request_context', 'nekkora_platform',
       format('(EXISTS ( SELECT 1%s   FROM users u%s  WHERE ((u.user_id = %s.user_id) AND (u.organization_id = (NULLIF(current_setting(''nekkora.organization_id''::text, true), ''''::text))::uuid))))',
              E'\n', E'\n', t.relname)
FROM classe_tabela t WHERE t.classe = 'derivada';

CREATE TEMP TABLE instalado_politica AS
SELECT c.relname, p.polname,
       array_to_string(ARRAY(SELECT rolname FROM pg_roles WHERE oid = ANY(p.polroles) ORDER BY 1), ',') AS papeis,
       pg_get_expr(p.polqual, p.polrelid) AS expr,
       pg_get_expr(p.polwithcheck, p.polrelid) AS expr_check, p.polcmd, p.polpermissive
FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid;

\echo === politicas: esperado e nao instalado / instalado e nao esperado (devem ser vazios)
SELECT * FROM esperado_politica EXCEPT SELECT relname, polname, papeis, expr FROM instalado_politica ORDER BY 1, 2;
SELECT relname, polname, papeis, expr FROM instalado_politica EXCEPT SELECT * FROM esperado_politica ORDER BY 1, 2;
SELECT pg_temp.afirma((SELECT count(*) FROM esperado_politica) = 81, '81 politicas esperadas (39 x 2 + 3)');
SELECT pg_temp.afirma((SELECT count(*) FROM (SELECT * FROM esperado_politica EXCEPT SELECT relname, polname, papeis, expr FROM instalado_politica) d) = 0,
  'toda politica esperada instalada com os papeis e a expressao exatos');
SELECT pg_temp.afirma((SELECT count(*) FROM (SELECT relname, polname, papeis, expr FROM instalado_politica EXCEPT SELECT * FROM esperado_politica) d) = 0,
  'nenhuma politica alem das esperadas');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM instalado_politica WHERE expr_check IS DISTINCT FROM expr OR polcmd <> '*' OR NOT polpermissive),
  'toda politica FOR ALL, permissiva, com WITH CHECK igual ao USING');
SELECT pg_temp.afirma(NOT EXISTS (
    SELECT 1 FROM instalado_politica ip
    JOIN pg_policy p ON p.polname = ip.polname JOIN pg_class c ON c.oid = p.polrelid AND c.relname = ip.relname
    WHERE 0 = ANY(p.polroles)),
  'nenhuma politica para PUBLIC');

\echo === exemplo de politica instalada (workspaces, user_credentials)
SELECT polname, pg_get_expr(polqual, polrelid) AS using_expr, pg_get_expr(polwithcheck, polrelid) AS check_expr
FROM pg_policy WHERE polrelid = 'public.workspaces'::regclass ORDER BY 1;
SELECT polname, pg_get_expr(polqual, polrelid) AS using_expr FROM pg_policy WHERE polrelid = 'public.user_credentials'::regclass;

\echo === schema public e privilegios padrao
SELECT nspname, nspacl FROM pg_namespace WHERE nspname = 'public';
SELECT pg_get_userbyid(defaclrole) AS papel, defaclnamespace, defaclobjtype, defaclacl FROM pg_default_acl;
SELECT pg_temp.afirma(EXISTS (
    SELECT 1 FROM pg_default_acl WHERE defaclrole = (SELECT oid FROM pg_roles WHERE rolname = current_user)
      AND defaclnamespace = 0 AND defaclobjtype = 'f'
      AND NOT EXISTS (SELECT 1 FROM aclexplode(defaclacl) a WHERE a.grantee = 0)),
  'privilegio padrao global do executor de migrations sem EXECUTE para PUBLIC em funcoes');
SELECT 'catalogo x matriz: todas as assercoes passaram' AS resultado;
