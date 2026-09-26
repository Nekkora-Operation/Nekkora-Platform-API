CREATE OR REPLACE FUNCTION public.portal_list_workspaces(p_organization_id uuid, p_session_token text)
 RETURNS TABLE(workspace_id uuid, name text)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'pg_temp'
AS $function$
#variable_conflict use_column
DECLARE
    v_prev_fn_org TEXT;
    s RECORD;
BEGIN
    v_prev_fn_org := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(p_organization_id::text, ''), true);
    BEGIN
        SELECT r.o_user_id, r.o_client_membership_id, r.o_t INTO s
          FROM public.portal_resolve_session(p_organization_id, p_session_token) r;

        RETURN QUERY
        SELECT w.workspace_id, w.name
          FROM public.workspaces w
         WHERE w.organization_id = p_organization_id
           AND w.workspace_id IN (SELECT a FROM public.portal_authorized_workspaces(p_organization_id, s.o_user_id, s.o_client_membership_id, s.o_t) a)
         ORDER BY w.name, w.workspace_id;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
END;
$function$

