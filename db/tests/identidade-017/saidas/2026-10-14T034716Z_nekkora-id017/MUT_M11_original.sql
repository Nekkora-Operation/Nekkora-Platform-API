CREATE OR REPLACE FUNCTION public.identity_org_creation_allowed(p_session_token TEXT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT coalesce((
        SELECT (SELECT pg_catalog.count(*) FROM public.identity_users iu
                 WHERE iu.identity_id = s.identity_id AND iu.link_channel = 'cadastro_web') < 10
          FROM public.identity_sessions s JOIN public.identities i ON i.identity_id = s.identity_id
         WHERE s.session_token_hash = public.identity_token_hash(p_session_token)
           AND s.revoked_at IS NULL AND s.expires_at > pg_catalog.clock_timestamp() AND s.mfa_verified_at IS NOT NULL
           AND (s.recovery_used_at IS NULL OR s.recovery_cleared_at IS NOT NULL)
           AND i.status = 2 AND i.email_verified_at IS NOT NULL), false)
$$;
