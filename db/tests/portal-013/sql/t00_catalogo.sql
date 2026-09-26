-- t00 - papel: nekkora (somente inspecao de catalogo). Contrato da 013 conferido EXATAMENTE:
-- atributos e memberships dos papeis novos, configuracoes de papel, USAGE/CREATE, owners, flags,
-- volatilidade, search_path e ACL das seis funcoes, colunas de retorno (allowlist 11.5), privilegios
-- por coluna do owner (diferenca simetrica com a lista do desenho, 4.1), politicas do owner, RLS nas
-- tabelas, ausencia de OFFSET e de EXECUTE do expurgo. Qualquer diferenca aborta.
\pset pager off

\echo === papeis novos
SELECT rolname, rolsuper, rolcanlogin, rolinherit, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls, rolconnlimit
FROM pg_roles WHERE rolname IN ('nekkora_portal', 'nekkora_portal_owner') ORDER BY 1;
SELECT pg_temp.afirma((SELECT count(*) FROM pg_roles WHERE rolname = 'nekkora_portal' AND rolcanlogin
    AND NOT (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls)) = 1, 'nekkora_portal: LOGIN, sem atributo privilegiado');
SELECT pg_temp.afirma((SELECT count(*) FROM pg_roles WHERE rolname = 'nekkora_portal_owner' AND NOT rolcanlogin
    AND NOT (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls)) = 1, 'nekkora_portal_owner: NOLOGIN, sem atributo privilegiado');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM pg_auth_members am JOIN pg_roles r ON r.oid = am.roleid JOIN pg_roles m ON m.oid = am.member
    WHERE r.rolname LIKE 'nekkora%' OR m.rolname LIKE 'nekkora%'), 'nenhuma membership envolvendo qualquer papel nekkora*, em qualquer sentido');

\echo === configuracoes de papel (pg_db_role_setting) - exatamente as sete do desenho + reforco
SELECT pg_get_userbyid(setrole) AS papel, setdatabase, setconfig FROM pg_db_role_setting ORDER BY 1, 2;
SELECT pg_temp.afirma((SELECT array_agg(c ORDER BY c) FROM pg_db_role_setting s, unnest(s.setconfig) c
                       WHERE s.setrole = 'nekkora_portal'::regrole AND s.setdatabase = 0)
    = ARRAY['log_error_verbosity=terse', 'log_min_duration_sample=-1', 'log_min_duration_statement=-1', 'log_min_error_statement=panic',
            'log_parameter_max_length=0', 'log_statement=none', 'log_transaction_sample_rate=0'],
  'nekkora_portal: exatamente as 7 configuracoes de log, globais (setdatabase = 0)');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM pg_db_role_setting WHERE setrole IN ('nekkora_portal'::regrole, 'nekkora_portal_owner'::regrole)
    AND NOT (setrole = 'nekkora_portal'::regrole AND setdatabase = 0)), 'nenhuma outra configuracao (por banco ou do owner) que sobreponha as globais');

\echo === schema e banco
SELECT p.papel, has_schema_privilege(p.papel, 'public', 'USAGE') AS usage, has_schema_privilege(p.papel, 'public', 'CREATE') AS create_public,
       has_database_privilege(p.papel, current_database(), 'CREATE') AS create_banco, has_database_privilege(p.papel, current_database(), 'TEMP') AS temp
FROM unnest(ARRAY['nekkora_portal', 'nekkora_portal_owner']) AS p(papel);
SELECT pg_temp.afirma(has_schema_privilege('nekkora_portal', 'public', 'USAGE') AND has_schema_privilege('nekkora_portal_owner', 'public', 'USAGE')
    AND NOT has_schema_privilege('nekkora_portal', 'public', 'CREATE') AND NOT has_schema_privilege('nekkora_portal_owner', 'public', 'CREATE')
    AND NOT has_database_privilege('nekkora_portal', current_database(), 'CREATE') AND NOT has_database_privilege('nekkora_portal_owner', current_database(), 'CREATE'),
  'USAGE em public verdadeiro e CREATE (schema e banco) falso para os dois papeis novos');
SELECT pg_temp.afirma((SELECT count(*) FROM aclexplode((SELECT nspacl FROM pg_namespace WHERE nspname = 'public')) a
                       WHERE a.grantee IN ('nekkora_portal'::regrole, 'nekkora_portal_owner'::regrole) AND a.privilege_type = 'USAGE' AND NOT a.is_grantable) = 2
                  AND NOT EXISTS (SELECT 1 FROM aclexplode((SELECT nspacl FROM pg_namespace WHERE nspname = 'public')) a
                       WHERE a.grantee IN ('nekkora_portal'::regrole, 'nekkora_portal_owner'::regrole) AND a.privilege_type <> 'USAGE'),
  'USAGE EXPLICITO (nspacl) aos dois papeis, sem GRANT OPTION e sem outro privilegio no schema');

\echo === funcoes do portal
SELECT p.oid::regprocedure AS funcao, pg_get_userbyid(p.proowner) AS owner, p.prosecdef, p.provolatile, p.proconfig, p.proacl,
       pg_get_function_result(p.oid) AS resultado
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname LIKE 'portal\_%' ORDER BY 1::text;
CREATE TEMP TABLE esperado_fn (sig TEXT PRIMARY KEY, secdef BOOLEAN, vol "char", executor BOOLEAN, resultado TEXT);
INSERT INTO esperado_fn VALUES
  ('portal_resolve_session(uuid,text)', false, 'v', false,
   'record'),
  ('portal_authorized_workspaces(uuid,uuid,uuid,timestamp with time zone)', false, 's', false,
   'SETOF uuid'),
  ('portal_list_workspaces(uuid,text)', true, 'v', true,
   'TABLE(workspace_id uuid, name text)'),
  ('portal_list_executions(uuid,text,uuid,smallint[],timestamp with time zone,timestamp with time zone,timestamp with time zone,uuid,integer)', true, 'v', true,
   'TABLE(execution_id uuid, workspace_id uuid, automation_id uuid, automation_name text, created_at timestamp with time zone, started_at timestamp with time zone, finished_at timestamp with time zone, duration interval, status smallint, status_reason smallint)'),
  ('portal_get_execution(uuid,text,uuid)', true, 'v', true,
   'TABLE(execution_id uuid, workspace_id uuid, automation_id uuid, automation_name text, created_at timestamp with time zone, started_at timestamp with time zone, finished_at timestamp with time zone, duration interval, status smallint, status_reason smallint)'),
  ('portal_list_execution_events(uuid,text,uuid)', true, 'v', true,
   'TABLE(event_type smallint, received_at timestamp with time zone)');
CREATE TEMP TABLE instalado_fn AS
SELECT p.oid, p.oid::regprocedure::text AS sig, p.proowner, p.prosecdef, p.provolatile, p.proconfig, p.proacl, p.prosrc, pg_get_function_result(p.oid) AS resultado
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'public' AND (p.proname LIKE 'portal\_%' OR p.proowner = 'nekkora_portal_owner'::regrole);
SELECT pg_temp.afirma((SELECT count(*) FROM instalado_fn) = 6 AND NOT EXISTS (
    SELECT sig FROM instalado_fn EXCEPT SELECT sig FROM esperado_fn), 'exatamente as 6 funcoes portal_* (e nenhuma outra do owner)');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM instalado_fn i JOIN esperado_fn e USING (sig)
    WHERE i.proowner <> 'nekkora_portal_owner'::regrole OR i.prosecdef <> e.secdef OR i.provolatile <> e.vol
       OR i.proconfig IS DISTINCT FROM ARRAY['search_path=pg_catalog, pg_temp'] OR i.resultado <> e.resultado),
  'owner nekkora_portal_owner, SECURITY DEFINER so nas 4 publicas, volatilidade, search_path=pg_catalog, pg_temp e colunas de retorno (allowlist 11.5) exatos');
-- ACL exata: owner (implicito) + EXECUTE ao portal so nas 4 publicas; nada para PUBLIC ou outro papel
SELECT pg_temp.afirma(NOT EXISTS (
    SELECT 1 FROM instalado_fn i JOIN esperado_fn e USING (sig), aclexplode(i.proacl) a
    WHERE NOT ((a.grantee = 'nekkora_portal_owner'::regrole AND a.privilege_type = 'EXECUTE')
            OR (e.executor AND a.grantee = 'nekkora_portal'::regrole AND a.privilege_type = 'EXECUTE' AND NOT a.is_grantable)))
  AND (SELECT count(*) FROM instalado_fn i JOIN esperado_fn e USING (sig), aclexplode(i.proacl) a
       WHERE a.grantee = 'nekkora_portal'::regrole) = 4
  AND NOT EXISTS (SELECT 1 FROM instalado_fn WHERE proacl IS NULL),
  'ACL das funcoes: EXECUTE so do owner e, nas 4 publicas, do nekkora_portal (sem GRANT OPTION); nada para PUBLIC');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM instalado_fn WHERE regexp_replace(prosrc, '--[^\n]*', '', 'g') ~* '\moffset\M'),
  'nenhuma funcao do portal usa OFFSET (codigo sem comentarios)');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM instalado_fn WHERE prosrc ~* 'nekkora\.organization_id'),
  'nenhuma funcao do portal le ou escreve nekkora.organization_id');
SELECT pg_temp.afirma((SELECT prosrc FROM instalado_fn WHERE sig = 'portal_list_executions(uuid,text,uuid,smallint[],timestamp with time zone,timestamp with time zone,timestamp with time zone,uuid,integer)')
    ~ 'c_max_page CONSTANT INTEGER := 100;', 'limite de pagina provisorio = 100 (decisao do responsavel, pendente 8.7)');

\echo === funcoes SECURITY DEFINER executaveis pelo portal e expurgo
SELECT p.oid::regprocedure AS funcao, pg_get_userbyid(p.proowner) AS owner
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'public' AND p.prosecdef AND has_function_privilege('nekkora_portal', p.oid, 'EXECUTE') ORDER BY 1::text;
SELECT pg_temp.afirma((SELECT array_agg(p.proname::text ORDER BY p.proname) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.prosecdef AND has_function_privilege('nekkora_portal', p.oid, 'EXECUTE'))
    = ARRAY['portal_get_execution', 'portal_list_execution_events', 'portal_list_executions', 'portal_list_workspaces'],
  'o portal executa exatamente as 4 funcoes SECURITY DEFINER publicas');
SELECT pg_temp.afirma(NOT has_function_privilege('nekkora_portal', 'public.purge_expired_records(uuid,integer,uuid,integer)', 'EXECUTE')
    AND NOT has_function_privilege('nekkora_portal_owner', 'public.purge_expired_records(uuid,integer,uuid,integer)', 'EXECUTE'),
  'sem EXECUTE do expurgo (purge_expired_records) para os papeis do portal');
\echo funcoes SECURITY INVOKER de public executaveis pelo portal via PUBLIC (001-011; rodam com os privilegios do chamador):
SELECT string_agg(p.oid::regprocedure::text, ', ' ORDER BY p.oid::regprocedure::text) AS invoker_via_public
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'public' AND NOT p.prosecdef AND has_function_privilege('nekkora_portal', p.oid, 'EXECUTE');

\echo === privilegios de tabela/coluna/sequencia do portal (deve ser vazio)
SELECT c.relname, c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind IN ('r', 'v', 'm', 'p', 'f', 'S')
  AND (has_table_privilege('nekkora_portal', c.oid, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
       OR has_any_column_privilege('nekkora_portal', c.oid, 'SELECT, INSERT, UPDATE, REFERENCES'));
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind IN ('r', 'v', 'm', 'p', 'f', 'S')
      AND (has_table_privilege('nekkora_portal', c.oid, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
           OR has_any_column_privilege('nekkora_portal', c.oid, 'SELECT, INSERT, UPDATE, REFERENCES'))),
  'nekkora_portal sem nenhum privilegio de tabela, view, coluna ou sequencia em public (efetivo, inclusive via PUBLIC)');

\echo === privilegios por coluna do owner: diferenca simetrica com o desenho (4.1)
CREATE TEMP TABLE esperado_col (tabela TEXT, coluna TEXT, priv TEXT, PRIMARY KEY (tabela, coluna, priv));
INSERT INTO esperado_col
SELECT t, unnest(cols), 'SELECT' FROM (VALUES
  ('user_sessions', ARRAY['session_id', 'organization_id', 'user_id', 'session_token_hash', 'created_at', 'expires_at', 'last_seen_at', 'revoked_at']),
  ('users', ARRAY['user_id', 'organization_id', 'account_type', 'status', 'email_verified_at']),
  ('organizations', ARRAY['organization_id', 'status']),
  ('clients', ARRAY['client_id', 'organization_id', 'status']),
  ('client_memberships', ARRAY['client_membership_id', 'organization_id', 'client_id', 'user_id', 'status']),
  ('client_workspace_grants', ARRAY['grant_id', 'organization_id', 'client_id', 'client_membership_id', 'workspace_id', 'status',
                                    'approved_at', 'starts_at', 'expires_at', 'next_review_at', 'revoked_at']),
  ('workspaces', ARRAY['workspace_id', 'organization_id', 'client_id', 'name', 'status']),
  ('automations', ARRAY['automation_id', 'organization_id', 'workspace_id', 'name']),
  ('executions', ARRAY['execution_id', 'organization_id', 'workspace_id', 'automation_id', 'created_at', 'started_at', 'finished_at', 'status', 'status_reason']),
  ('execution_events', ARRAY['organization_id', 'workspace_id', 'execution_id', 'sequence', 'event_type', 'received_at'])) AS v(t, cols)
UNION ALL SELECT 'user_sessions', 'last_seen_at', 'UPDATE';
-- instalado: efetivo, por coluna, para os 4 privilegios de coluna, em TODAS as tabelas de public
CREATE TEMP TABLE instalado_col AS
SELECT c.relname::text AS tabela, a.attname::text AS coluna, pr.priv
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
CROSS JOIN unnest(ARRAY['SELECT', 'INSERT', 'UPDATE', 'REFERENCES']) AS pr(priv)
WHERE n.nspname = 'public' AND c.relkind IN ('r', 'v', 'm', 'p', 'f')
  AND has_column_privilege('nekkora_portal_owner', c.oid, a.attnum, pr.priv);
SELECT 'faltando' AS lado, * FROM (SELECT * FROM esperado_col EXCEPT SELECT * FROM instalado_col) f
UNION ALL SELECT 'sobrando', * FROM (SELECT * FROM instalado_col EXCEPT SELECT * FROM esperado_col) s;
SELECT pg_temp.afirma(NOT EXISTS (SELECT * FROM esperado_col EXCEPT SELECT * FROM instalado_col)
                  AND NOT EXISTS (SELECT * FROM instalado_col EXCEPT SELECT * FROM esperado_col),
  format('owner: privilegios de coluna identicos ao desenho (%s colunas SELECT + UPDATE(last_seen_at))', (SELECT count(*) - 1 FROM esperado_col)));
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind IN ('r', 'v', 'm', 'p', 'f', 'S')
      AND CASE WHEN c.relkind = 'S' THEN has_sequence_privilege('nekkora_portal_owner', c.oid, 'USAGE, SELECT, UPDATE')
               ELSE has_table_privilege('nekkora_portal_owner', c.oid, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER') END),
  'owner sem privilegio de tabela inteira (so por coluna), sem INSERT/DELETE/TRUNCATE e sem sequencia');
SELECT pg_temp.afirma(NOT has_column_privilege('nekkora_portal_owner', 'public.execution_events', 'payload', 'SELECT')
    AND NOT has_column_privilege('nekkora_portal_owner', 'public.executions', 'error_summary', 'SELECT')
    AND NOT has_column_privilege('nekkora_portal_owner', 'public.executions', 'exit_code', 'SELECT')
    AND NOT has_column_privilege('nekkora_portal_owner', 'public.executions', 'command_snapshot', 'SELECT')
    AND NOT has_column_privilege('nekkora_portal_owner', 'public.users', 'email', 'SELECT')
    AND NOT has_column_privilege('nekkora_portal_owner', 'public.users', 'display_name', 'SELECT')
    AND NOT has_column_privilege('nekkora_portal_owner', 'public.workspaces', 'description', 'SELECT')
    AND NOT has_any_column_privilege('nekkora_portal_owner', 'public.logs', 'SELECT, INSERT, UPDATE, REFERENCES')
    AND NOT has_any_column_privilege('nekkora_portal_owner', 'public.workspace_secrets', 'SELECT, INSERT, UPDATE, REFERENCES'),
  'owner sem payload, error_summary, exit_code, command_snapshot, e-mail, nome, description, logs e secrets');

\echo === RLS e politicas
SELECT tablename, policyname, cmd, permissive, roles, qual, with_check FROM pg_policies
WHERE 'nekkora_portal_owner' = ANY (roles) OR 'nekkora_portal' = ANY (roles) ORDER BY 1, 2;
SELECT pg_temp.afirma((SELECT count(*) FROM pg_policies WHERE 'nekkora_portal_owner' = ANY (roles)) = 11
    AND (SELECT count(*) FROM pg_policies WHERE 'nekkora_portal_owner' = ANY (roles) AND policyname = 'portal_function_context' AND cmd = 'SELECT'
         AND permissive = 'PERMISSIVE' AND roles = '{nekkora_portal_owner}' AND with_check IS NULL
         AND qual = '(organization_id = (NULLIF(current_setting(''nekkora.fn_organization_id''::text, true), ''''::text))::uuid)'
         AND tablename IN ('user_sessions', 'users', 'organizations', 'clients', 'client_memberships', 'client_workspace_grants',
                           'workspaces', 'automations', 'executions', 'execution_events')) = 10
    AND (SELECT count(*) FROM pg_policies WHERE tablename = 'user_sessions' AND policyname = 'portal_function_context_update' AND cmd = 'UPDATE'
         AND permissive = 'PERMISSIVE' AND roles = '{nekkora_portal_owner}'
         AND qual = '(organization_id = (NULLIF(current_setting(''nekkora.fn_organization_id''::text, true), ''''::text))::uuid)'
         AND with_check = qual) = 1,
  'owner: 10 politicas FOR SELECT portal_function_context + 1 FOR UPDATE em user_sessions (USING = WITH CHECK), so fn_organization_id');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM pg_policies WHERE 'nekkora_portal' = ANY (roles) OR 'public' = ANY (roles)),
  'nenhuma politica para nekkora_portal ou PUBLIC');
SELECT pg_temp.afirma((SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relrowsecurity AND c.relforcerowsecurity) = 42,
  '42 tabelas com RLS ENABLE + FORCE (inalterado desde a 012)');
SELECT pg_temp.afirma((SELECT count(*) FROM pg_policies WHERE policyname IN ('tenant_request_context', 'tenant_function_context')) = 81,
  '81 politicas da 012 intactas (contagem)');

\echo === proprietario das tabelas e visoes (o owner do portal nao e dono de nada alem das 6 funcoes)
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM pg_class WHERE relowner IN ('nekkora_portal'::regrole, 'nekkora_portal_owner'::regrole))
    AND NOT EXISTS (SELECT 1 FROM pg_type WHERE typowner IN ('nekkora_portal'::regrole, 'nekkora_portal_owner'::regrole))
    AND NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner IN ('nekkora_portal'::regrole, 'nekkora_portal_owner'::regrole))
    AND NOT EXISTS (SELECT 1 FROM pg_proc WHERE proowner = 'nekkora_portal'::regrole),
  'papeis do portal nao possuem relacao, tipo, schema; o LOGIN nao possui funcao');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind IN ('v', 'm')), 'nenhuma view em public (o portal nao usa views na v1)');
