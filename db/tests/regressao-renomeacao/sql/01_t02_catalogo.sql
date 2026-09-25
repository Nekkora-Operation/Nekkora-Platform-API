-- T02 - papel: nekkora. Busca case-insensitive do nome antigo nas definicoes SQL instaladas.
-- (arquivos do repositorio e objetos Docker sao varridos pelo run.sh)
\pset pager off
SELECT 'pg_proc' src, count(*) FROM pg_proc WHERE prosrc ILIKE '%radiant%' OR proname ILIKE '%radiant%'
UNION ALL SELECT 'pg_proc.proconfig', count(*) FROM pg_proc WHERE array_to_string(proconfig, ',') ILIKE '%radiant%'
UNION ALL SELECT 'pg_description', count(*) FROM pg_description WHERE description ILIKE '%radiant%'
UNION ALL SELECT 'pg_roles', count(*) FROM pg_roles WHERE rolname ILIKE '%radiant%'
UNION ALL SELECT 'pg_database', count(*) FROM pg_database WHERE datname ILIKE '%radiant%'
UNION ALL SELECT 'pg_class', count(*) FROM pg_class WHERE relname ILIKE '%radiant%'
UNION ALL SELECT 'pg_constraint', count(*) FROM pg_constraint WHERE conname ILIKE '%radiant%' OR pg_get_constraintdef(oid) ILIKE '%radiant%'
UNION ALL SELECT 'pg_trigger', count(*) FROM pg_trigger WHERE tgname ILIKE '%radiant%'
UNION ALL SELECT 'pg_namespace', count(*) FROM pg_namespace WHERE nspname ILIKE '%radiant%'
UNION ALL SELECT 'pg_db_role_setting', count(*) FROM pg_db_role_setting WHERE array_to_string(setconfig, ',') ILIKE '%radiant%'
UNION ALL SELECT 'pg_policy (RLS, esperado 0 antes da migration de seguranca)', count(*) FROM pg_policy;
