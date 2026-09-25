-- T03 - papel: nekkora (somente inspecao de catalogo).
\pset pager off
SELECT rolname, rolsuper, rolcanlogin, rolinherit, rolcreaterole, rolcreatedb, rolbypassrls
FROM pg_roles WHERE rolname !~ '^pg_' ORDER BY 1;
SELECT r.rolname AS papel, m.rolname AS membro_de
FROM pg_auth_members am JOIN pg_roles r ON r.oid = am.member JOIN pg_roles m ON m.oid = am.roleid
WHERE r.rolname !~ '^pg_';
SELECT pg_get_userbyid(relowner) AS owner, relkind, count(*)
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE nspname = 'public' GROUP BY 1, 2 ORDER BY 1, 2;
SELECT relname, pg_get_userbyid(relowner) AS owner, relacl
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE nspname = 'public' AND relkind = 'r' AND (relacl IS NOT NULL OR pg_get_userbyid(relowner) <> 'nekkora') ORDER BY 1;
SELECT attrelid::regclass AS tabela, attname, attacl
FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE nspname = 'public' AND attacl IS NOT NULL ORDER BY 1, 2;
SELECT p.oid::regprocedure AS funcao, pg_get_userbyid(proowner) AS owner, prosecdef, proconfig, proacl
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE nspname = 'public' AND (prosecdef OR proacl IS NOT NULL OR pg_get_userbyid(proowner) <> 'nekkora');
SELECT nspname, pg_get_userbyid(nspowner), nspacl FROM pg_namespace WHERE nspname = 'public';
SELECT p.proname,
       concat_ws(' ',
         CASE WHEN prosrc ~* 'current_user' THEN 'current_user' END,
         CASE WHEN prosrc ~* 'session_user' THEN 'session_user' END,
         CASE WHEN prosrc ~* 'set\s+role' THEN 'SET ROLE' END,
         CASE WHEN prosrc ~* 'nekkora[._]' THEN 'nekkora-literal' END) AS referencias
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE nspname = 'public' AND prosrc ~* '(current_user|session_user|set\s+role|nekkora[._])' ORDER BY 1;
