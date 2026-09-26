-- t05 - papel: nekkora_portal (LOGIN real). Teste 5 do desenho e grupo "Privilegio minimo" da missao:
-- inventario COMPLETO (nao amostra) das relacoes de public: SELECT, INSERT, UPDATE, DELETE e TRUNCATE ->
-- 42501 em todas; EXECUTE nas funcoes internas do portal e em TODAS as SECURITY DEFINER da 012 ->
-- 42501; CREATE em public/banco, membership, SET ROLE, SET SESSION AUTHORIZATION e alteracao de
-- atributos -> negados. O USAGE em public esta concedido e nao abre nada disso.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_portal' AND session_user = 'nekkora_portal', 'papel real nekkora_portal (sem SET ROLE)');
SELECT pg_temp.afirma(has_schema_privilege('public', 'USAGE'), 'USAGE em public efetivo (as negativas abaixo valem com ele)');

\echo === inventario de relacoes de public
CREATE TEMP TABLE inv AS
SELECT c.relname::text AS rel, c.relkind::text AS kind,
       (SELECT a.attname::text FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
         AND a.attgenerated = '' ORDER BY a.attnum LIMIT 1) AS col1
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S');
SELECT kind, count(*) FROM inv GROUP BY kind ORDER BY 1;
SELECT pg_temp.afirma((SELECT count(*) FROM inv WHERE kind = 'r') = 45, '45 tabelas em public (inventario completo)');

\echo === SELECT / INSERT / UPDATE / DELETE / TRUNCATE em cada tabela -> 42501
SELECT pg_temp.afirma(count(*) = (SELECT count(*) FROM inv WHERE kind IN ('r', 'p')), format('SELECT negado nas %s tabelas', count(*)))
FROM inv, LATERAL pg_temp.espera_erro(format('SELECT 1 FROM public.%I LIMIT 1', rel), '42501', 'permission denied') e WHERE kind IN ('r', 'p');
SELECT pg_temp.afirma(count(*) = (SELECT count(*) FROM inv WHERE kind IN ('r', 'p')), format('INSERT negado nas %s tabelas', count(*)))
FROM inv, LATERAL pg_temp.espera_erro(format('INSERT INTO public.%I DEFAULT VALUES', rel), '42501', 'permission denied') e WHERE kind IN ('r', 'p');
SELECT pg_temp.afirma(count(*) = (SELECT count(*) FROM inv WHERE kind IN ('r', 'p')), format('UPDATE negado nas %s tabelas', count(*)))
FROM inv, LATERAL pg_temp.espera_erro(format('UPDATE public.%I SET %I = %I', rel, col1, col1), '42501', 'permission denied') e WHERE kind IN ('r', 'p');
SELECT pg_temp.afirma(count(*) = (SELECT count(*) FROM inv WHERE kind IN ('r', 'p')), format('DELETE negado nas %s tabelas', count(*)))
FROM inv, LATERAL pg_temp.espera_erro(format('DELETE FROM public.%I', rel), '42501', 'permission denied') e WHERE kind IN ('r', 'p');
SELECT pg_temp.afirma(count(*) = (SELECT count(*) FROM inv WHERE kind IN ('r', 'p')), format('TRUNCATE negado nas %s tabelas', count(*)))
FROM inv, LATERAL pg_temp.espera_erro(format('TRUNCATE public.%I', rel), '42501', 'permission denied') e WHERE kind IN ('r', 'p');
SELECT pg_temp.afirma(count(*) = (SELECT count(*) FROM inv WHERE kind IN ('v', 'm')), format('SELECT negado nas %s views', count(*)))
FROM inv, LATERAL pg_temp.espera_erro(format('SELECT 1 FROM public.%I LIMIT 1', rel), '42501', 'permission denied') e WHERE kind IN ('v', 'm');
SELECT pg_temp.afirma(count(*) = (SELECT count(*) FROM inv WHERE kind = 'S'), format('nextval negado nas %s sequencias', count(*)))
FROM inv, LATERAL pg_temp.espera_erro(format('SELECT nextval(%L)', 'public.' || rel), '42501', 'permission denied') e WHERE kind = 'S';
-- COPY nao roda dentro de PL/pgSQL: executado direto, com o SQLSTATE conferido em seguida
\set ON_ERROR_STOP off
COPY public.user_sessions TO STDOUT;
\set ON_ERROR_STOP on
SELECT pg_temp.afirma(:'SQLSTATE' = '42501', 'COPY public.user_sessions TO STDOUT negado (42501)');
SELECT pg_temp.espera_erro('SELECT session_token_hash FROM public.user_sessions', '42501', 'permission denied'),
       pg_temp.espera_erro('SELECT payload FROM public.execution_events', '42501', 'permission denied');

\echo === EXECUTE: funcoes internas do portal e todas as SECURITY DEFINER fora das 4 publicas -> 42501
CREATE TEMP TABLE fn_priv AS
SELECT p.oid::regprocedure::text AS sig,
       format('SELECT public.%I(%s)', p.proname,
              coalesce((SELECT string_agg(format('NULL::%s', format_type(t, NULL)), ', ' ORDER BY i)
                        FROM unnest(p.proargtypes::oid[]) WITH ORDINALITY AS a(t, i)), '')) AS chamada
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'public'
  AND (p.prosecdef OR p.proname IN ('portal_resolve_session', 'portal_authorized_workspaces'))
  AND p.proname NOT IN ('portal_list_workspaces', 'portal_list_executions', 'portal_get_execution', 'portal_list_execution_events');
SELECT sig FROM fn_priv ORDER BY 1;
SELECT pg_temp.afirma(count(*) = (SELECT count(*) FROM fn_priv) AND count(*) = 13, format('EXECUTE negado em %s funcoes (2 internas do portal + as 11 SECURITY DEFINER da 011/012, inclusive o expurgo)', count(*)))
FROM fn_priv, LATERAL pg_temp.espera_erro(chamada, '42501', 'permission denied for function') e;
SELECT pg_temp.afirma(EXISTS (SELECT 1 FROM fn_priv WHERE sig LIKE 'purge_expired_records(%'), 'o expurgo esta entre as negadas');

\echo === CREATE, membership e elevacao de papel
SELECT pg_temp.espera_erro('CREATE TABLE public.portal_intruso (a int)', '42501', 'permission denied for schema public'),
       pg_temp.espera_erro('CREATE FUNCTION public.portal_intrusa() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$', '42501', 'permission denied for schema public'),
       pg_temp.espera_erro('CREATE VIEW public.portal_intrusa AS SELECT 1', '42501', 'permission denied for schema public'),
       pg_temp.espera_erro('CREATE SCHEMA portal_intruso', '42501', 'permission denied for database');
SELECT pg_temp.afirma(count(*) = 11, 'SET ROLE negado para os 11 papeis de servico/proprietarios (inclusive nekkora_portal_owner) e o de migration')
FROM unnest(ARRAY['nekkora_portal_owner', 'nekkora_platform', 'nekkora_ingestion', 'nekkora_scheduler', 'nekkora_provisioning',
                  'nekkora_retention_executor', 'nekkora_scheduler_owner', 'nekkora_provisioning_owner', 'nekkora_retention_owner',
                  'nekkora_retention_tables_owner', 'nekkora']) AS r(papel),
     LATERAL pg_temp.espera_erro(format('SET ROLE %I', papel), '42501', 'permission denied to set role') e;
SELECT pg_temp.espera_erro('SET SESSION AUTHORIZATION nekkora', '42501', 'permission denied to set session authorization'),
       pg_temp.espera_erro('GRANT nekkora_portal_owner TO nekkora_portal', '42501', 'permission denied to grant role'),
       pg_temp.espera_erro('GRANT nekkora_portal TO nekkora_platform', '42501', 'permission denied to grant role'),
       pg_temp.espera_erro('ALTER ROLE nekkora_portal SUPERUSER', '42501', 'permission denied'),
       pg_temp.espera_erro('ALTER ROLE nekkora_portal BYPASSRLS', '42501', 'permission denied'),
       pg_temp.espera_erro('ALTER ROLE nekkora_portal CREATEROLE', '42501', 'permission denied'),
       pg_temp.espera_erro('CREATE ROLE portal_intruso', '42501', 'permission denied to create role'),
       pg_temp.espera_erro('GRANT EXECUTE ON FUNCTION public.portal_resolve_session(uuid, text) TO nekkora_portal', '42501', 'permission denied'),
       pg_temp.espera_erro('ALTER FUNCTION public.portal_list_workspaces(uuid, text) OWNER TO nekkora_portal', '42501', 'must be owner');
SELECT pg_temp.afirma(NOT pg_has_role('nekkora_portal', 'nekkora_portal_owner', 'MEMBER') AND NOT pg_has_role('nekkora_portal', 'nekkora_platform', 'MEMBER'),
  'nekkora_portal nao e membro de nenhum papel privilegiado');
