-- t01 - papel: nekkora (somente inspecao de catalogo). Contrato da 014 conferido exatamente: tabelas,
-- colunas novas de user_sessions, triggers habilitadas, RLS/politicas, privilegios EXATOS por papel nas
-- tabelas novas (diferenca simetrica), invisibilidade das colunas novas para os papeis do portal,
-- permissao identity.mfa.reset so de ORG_ADMIN, funcoes de trigger sem SECURITY DEFINER e com
-- search_path fixo, e a 013 intacta (as 6 funcoes do portal com o mesmo corpo e ACL).
\pset pager off

\echo === tabelas novas, RLS e politicas
SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity, pg_get_userbyid(c.relowner) AS owner,
       (SELECT string_agg(policyname || ':' || cmd || ':' || roles::text, ', ') FROM pg_policies p WHERE p.tablename = c.relname) AS politicas
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relname IN ('user_mfa_factors', 'user_mfa_recovery_codes', 'auth_challenges', 'mfa_events') ORDER BY 1;
SELECT pg_temp.afirma((SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relname IN ('user_mfa_factors', 'user_mfa_recovery_codes', 'auth_challenges', 'mfa_events')
      AND c.relrowsecurity AND c.relforcerowsecurity) = 4, '4 tabelas novas com RLS ENABLE + FORCE');
SELECT pg_temp.afirma((SELECT count(*) FROM pg_policies WHERE tablename IN ('user_mfa_factors', 'user_mfa_recovery_codes', 'auth_challenges', 'mfa_events')) = 4
    AND (SELECT count(*) FROM pg_policies WHERE tablename IN ('user_mfa_factors', 'user_mfa_recovery_codes', 'auth_challenges', 'mfa_events')
         AND policyname = 'tenant_request_context' AND cmd = 'ALL' AND permissive = 'PERMISSIVE' AND roles = '{nekkora_platform}'
         AND qual = '(organization_id = (NULLIF(current_setting(''nekkora.organization_id''::text, true), ''''::text))::uuid)'
         AND with_check = qual) = 4,
  'uma politica por tabela nova: tenant_request_context FOR ALL so para nekkora_platform, pelo contexto da requisicao');
SELECT pg_temp.afirma((SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relrowsecurity AND c.relforcerowsecurity) = 46, '46 tabelas com RLS (42 da 012 + 4 da 014)');

\echo === privilegios EXATOS de todos os papeis nekkora* nas tabelas novas (diferenca simetrica)
CREATE TEMP TABLE esperado (papel TEXT, tabela TEXT, priv TEXT);
INSERT INTO esperado
SELECT 'nekkora_platform', t, p FROM unnest(ARRAY['user_mfa_factors', 'user_mfa_recovery_codes', 'auth_challenges']) t, unnest(ARRAY['SELECT', 'INSERT', 'UPDATE']) p
UNION ALL SELECT 'nekkora_platform', 'mfa_events', p FROM unnest(ARRAY['SELECT', 'INSERT']) p;
CREATE TEMP TABLE instalado AS
SELECT r.rolname::text AS papel, t AS tabela, p AS priv
FROM pg_roles r, unnest(ARRAY['user_mfa_factors', 'user_mfa_recovery_codes', 'auth_challenges', 'mfa_events']) t,
     unnest(ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER']) p
WHERE r.rolname LIKE 'nekkora\_%' AND has_table_privilege(r.oid, 'public.' || t, p);
SELECT 'faltando' AS lado, * FROM (SELECT * FROM esperado EXCEPT SELECT * FROM instalado) f
UNION ALL SELECT 'sobrando', * FROM (SELECT * FROM instalado EXCEPT SELECT * FROM esperado) s;
SELECT pg_temp.afirma(NOT EXISTS (SELECT * FROM esperado EXCEPT SELECT * FROM instalado) AND NOT EXISTS (SELECT * FROM instalado EXCEPT SELECT * FROM esperado),
  'tabelas novas: so nekkora_platform (SIU; mfa_events SI); nenhum outro papel de servico ou do portal');
SELECT pg_temp.afirma(NOT EXISTS (
    SELECT 1 FROM unnest(ARRAY['nekkora_portal', 'nekkora_portal_owner', 'nekkora_ingestion', 'nekkora_scheduler_owner', 'nekkora_provisioning_owner',
                               'nekkora_retention_owner']) p,
                  unnest(ARRAY['user_mfa_factors', 'user_mfa_recovery_codes', 'auth_challenges', 'mfa_events']) t
    WHERE has_any_column_privilege(p, 'public.' || t, 'SELECT, INSERT, UPDATE, REFERENCES')), 'nenhum privilegio de coluna nas tabelas novas fora da Platform');

\echo === colunas novas de user_sessions
SELECT a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull FROM pg_attribute a
WHERE a.attrelid = 'public.user_sessions'::regclass AND a.attname IN ('mfa_verified_at', 'mfa_step_up_at', 'csrf_token');
SELECT pg_temp.afirma((SELECT count(*) FROM pg_attribute WHERE attrelid = 'public.user_sessions'::regclass AND NOT attisdropped
    AND attname IN ('mfa_verified_at', 'mfa_step_up_at', 'csrf_token') AND NOT attnotnull) = 3, 'user_sessions: 3 colunas novas anulaveis');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM unnest(ARRAY['nekkora_portal', 'nekkora_portal_owner']) p,
    unnest(ARRAY['mfa_verified_at', 'mfa_step_up_at', 'csrf_token']) c
    WHERE has_column_privilege(p, 'public.user_sessions', c, 'SELECT, INSERT, UPDATE, REFERENCES')),
  'papeis do portal nao alcancam mfa_verified_at, mfa_step_up_at nem csrf_token');
SELECT pg_temp.afirma(has_column_privilege('nekkora_portal_owner', 'public.user_sessions', 'last_seen_at', 'UPDATE')
    AND (SELECT count(*) FROM pg_attribute a WHERE a.attrelid = 'public.user_sessions'::regclass AND a.attnum > 0 AND NOT a.attisdropped
         AND has_column_privilege('nekkora_portal_owner', 'public.user_sessions', a.attname, 'SELECT')) = 8,
  'owner do portal: continua com exatamente as 8 colunas de SELECT da 013 em user_sessions e UPDATE(last_seen_at)');

\echo === triggers
SELECT c.relname, t.tgname, t.tgenabled, p.proname, p.prosecdef, p.proconfig
FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_proc p ON p.oid = t.tgfoid
WHERE NOT t.tgisinternal AND c.relname IN ('user_sessions', 'user_mfa_factors', 'user_mfa_recovery_codes', 'auth_challenges', 'mfa_events') ORDER BY 1, 2;
SELECT pg_temp.afirma((SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
    WHERE NOT t.tgisinternal AND t.tgenabled = 'O' AND c.relname IN ('user_mfa_factors', 'user_mfa_recovery_codes', 'auth_challenges', 'mfa_events')) = 12
  AND (SELECT count(*) FROM pg_trigger t WHERE t.tgrelid = 'public.user_sessions'::regclass AND NOT t.tgisinternal AND t.tgenabled = 'O') = 2,
  '12 triggers nas tabelas novas (mudanca/DELETE/TRUNCATE) e 2 em user_sessions, todas habilitadas');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM pg_proc p WHERE p.proname IN ('prevent_mfa_factor_change', 'prevent_mfa_recovery_code_change',
    'prevent_auth_challenge_change', 'enforce_user_session_mfa_on_insert', 'prevent_user_session_reactivation')
    AND (p.prosecdef OR p.proconfig IS DISTINCT FROM ARRAY['search_path=pg_catalog, pg_temp'] OR has_function_privilege('public', p.oid, 'EXECUTE'))),
  'funcoes de trigger da 014: INVOKER, search_path = pg_catalog, pg_temp, sem EXECUTE para PUBLIC');

\echo === permissao identity.mfa.reset
SELECT r.code AS papel, p.code AS permissao FROM role_permissions rp JOIN roles r ON r.role_id = rp.role_id
JOIN permissions p ON p.permission_id = rp.permission_id WHERE p.code = 'identity.mfa.reset';
SELECT pg_temp.afirma((SELECT array_agg(r.code) FROM role_permissions rp JOIN roles r ON r.role_id = rp.role_id
    JOIN permissions p ON p.permission_id = rp.permission_id WHERE p.code = 'identity.mfa.reset') = ARRAY['ORG_ADMIN'],
  'identity.mfa.reset concedida exatamente a ORG_ADMIN');

\echo === 013 intacta
SELECT p.oid::regprocedure, md5(p.prosrc) AS corpo, p.proacl FROM pg_proc p WHERE p.proname LIKE 'portal\_%' ORDER BY 1::text;
SELECT pg_temp.afirma((SELECT count(*) FROM pg_proc WHERE proname LIKE 'portal\_%' AND proowner = 'nekkora_portal_owner'::regrole) = 6,
  'as 6 funcoes do portal continuam com o owner da 013');
