CREATE OR REPLACE FUNCTION public.portal_authorized_workspaces(p_organization_id uuid, p_user_id uuid, p_client_membership_id uuid, p_t timestamp with time zone)
 RETURNS SETOF uuid
 LANGUAGE sql
 STABLE
 SET search_path TO 'pg_catalog', 'pg_temp'
AS $function$
    SELECT g.workspace_id
      FROM public.users u
      JOIN public.organizations o ON o.organization_id = u.organization_id
      JOIN public.client_memberships m ON m.user_id = u.user_id AND m.organization_id = u.organization_id
      JOIN public.clients c ON c.client_id = m.client_id AND c.organization_id = m.organization_id
      JOIN public.client_workspace_grants g
        ON g.client_membership_id = m.client_membership_id
       AND g.client_id = c.client_id
       AND g.organization_id = m.organization_id
      JOIN public.workspaces w
        ON w.workspace_id = g.workspace_id
       AND w.client_id = c.client_id
       AND w.organization_id = g.organization_id
     WHERE u.user_id = p_user_id
       AND u.organization_id = p_organization_id
       AND u.account_type = 2 AND u.status = 2 AND u.email_verified_at IS NOT NULL
       AND o.status = 1
       AND m.client_membership_id = p_client_membership_id AND m.status = 2
       AND c.status = 1
       AND g.status = 2 AND g.approved_at IS NOT NULL AND g.starts_at <= p_t
       AND (g.expires_at IS NULL OR g.expires_at > p_t)
       AND g.next_review_at > p_t
       AND w.status = 1
$function$

