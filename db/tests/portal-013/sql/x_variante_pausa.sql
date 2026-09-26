-- x_variante_pausa - papel: nekkora (superusuario; instala objeto SO NA BASE DE TESTE). Variante do
-- teste 7(c) do desenho: portal_list_executions_pausa = corpo IDENTICO ao de portal_list_executions
-- instalado (lido do catalogo, nao de uma copia), com UMA linha a mais entre a resolucao e a leitura:
-- pg_advisory_xact_lock numa chave do harness. Mesmo owner, mesmas flags e search_path; EXECUTE so
-- para nekkora_portal. O runner a remove depois (x_variante_remove) e reconfere o catalogo.
\pset pager off
SELECT pg_temp.afirma(current_setting('cluster_name') = 'nekkora-portal-013-isolada', 'base isolada de teste (cluster_name)');
SELECT pg_get_functiondef('public.portal_list_executions(uuid,text,uuid,smallint[],timestamptz,timestamptz,timestamptz,uuid,integer)'::regprocedure) AS def_original \gset
SELECT set_config('teste.def', :'def_original', false) IS NOT NULL AS def_ok;
DO $$
DECLARE
    d TEXT := current_setting('teste.def');
    n INTEGER;
BEGIN
    SELECT count(*) INTO n FROM regexp_matches(d, 'FROM public\.portal_resolve_session\(p_organization_id, p_session_token\) r;', 'g');
    IF n <> 1 THEN RAISE EXCEPTION 'FALHA DE ASSERCAO: ponto de insercao da pausa nao e unico (%)', n; END IF;
    d := replace(d, 'CREATE OR REPLACE FUNCTION public.portal_list_executions(', 'CREATE FUNCTION public.portal_list_executions_pausa(');
    d := regexp_replace(d, '(FROM public\.portal_resolve_session\(p_organization_id, p_session_token\) r;)',
                        E'\\1\n        PERFORM pg_catalog.pg_advisory_xact_lock(820713); -- PAUSA-DE-TESTE');
    EXECUTE d;
END $$;
ALTER FUNCTION public.portal_list_executions_pausa(uuid,text,uuid,smallint[],timestamptz,timestamptz,timestamptz,uuid,integer) OWNER TO nekkora_portal_owner;
REVOKE ALL ON FUNCTION public.portal_list_executions_pausa(uuid,text,uuid,smallint[],timestamptz,timestamptz,timestamptz,uuid,integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.portal_list_executions_pausa(uuid,text,uuid,smallint[],timestamptz,timestamptz,timestamptz,uuid,integer) TO nekkora_portal;
SELECT pg_temp.afirma(
    replace(v.prosrc, E'\n        PERFORM pg_catalog.pg_advisory_xact_lock(820713); -- PAUSA-DE-TESTE', '') = o.prosrc
    AND v.prosrc <> o.prosrc AND v.prosecdef = o.prosecdef AND v.proconfig = o.proconfig AND v.provolatile = o.provolatile
    AND v.proowner = o.proowner,
  'variante de pausa = corpo da funcao instalada + 1 linha (pg_advisory_xact_lock); mesmo owner, SECURITY DEFINER e search_path')
FROM pg_proc v, pg_proc o
WHERE v.oid = 'public.portal_list_executions_pausa(uuid,text,uuid,smallint[],timestamptz,timestamptz,timestamptz,uuid,integer)'::regprocedure
  AND o.oid = 'public.portal_list_executions(uuid,text,uuid,smallint[],timestamptz,timestamptz,timestamptz,uuid,integer)'::regprocedure;
