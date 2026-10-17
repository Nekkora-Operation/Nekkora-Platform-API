CREATE OR REPLACE FUNCTION public.identity_org_session_allowed(p_organization_id UUID, p_user_id UUID, p_identity_session_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    v_r BOOLEAN;
BEGIN
    BEGIN
        PERFORM public.identity_check_context(p_organization_id, p_user_id);
        IF NOT EXISTS (SELECT 1 FROM public.identity_users iu WHERE iu.user_id = p_user_id) THEN
            -- ramo 1: conta NAO vinculada - a regra da 014, intacta, e sem sessao de identidade
            v_r := p_identity_session_id IS NULL
               AND EXISTS (SELECT 1 FROM public.user_mfa_factors f
                            WHERE f.organization_id = p_organization_id AND f.user_id = p_user_id
                              AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL);
        ELSE
            -- ramo 2: conta vinculada - a sessao de identidade e a prova, desta identidade, ativa, com MFA, NAO restrita
            v_r := p_identity_session_id IS NOT NULL
               AND EXISTS (SELECT 1 FROM public.identity_users iu
                             JOIN public.identity_sessions s ON s.identity_id = iu.identity_id
                             JOIN public.identities i ON i.identity_id = iu.identity_id
                            WHERE iu.user_id = p_user_id
                              AND s.identity_session_id = p_identity_session_id
                              AND s.revoked_at IS NULL
                              AND s.expires_at > pg_catalog.clock_timestamp()
                              AND s.mfa_verified_at IS NOT NULL
                              AND (s.recovery_used_at IS NULL OR s.recovery_cleared_at IS NOT NULL)
                              AND i.status = 2)
               AND EXISTS (SELECT 1 FROM public.identity_mfa_factors g
                             JOIN public.identity_users iu2 ON iu2.identity_id = g.identity_id
                            WHERE iu2.user_id = p_user_id AND g.confirmed_at IS NOT NULL AND g.disabled_at IS NULL)
               -- R4: elegibilidade da conta, da organizacao e do vinculo interno, lida DEPOIS dos locks da trigger
               -- (lock compartilhado da organizacao, depois lock exclusivo da conta); cada comando le o estado confirmado mais recente
               AND EXISTS (SELECT 1 FROM public.users u
                             JOIN public.organizations o ON o.organization_id = u.organization_id
                             JOIN public.organization_memberships m ON m.user_id = u.user_id AND m.organization_id = u.organization_id
                            WHERE u.user_id = p_user_id AND u.organization_id = p_organization_id
                              AND u.account_type = 1 AND u.status = 2 AND u.email_verified_at IS NOT NULL
                              AND o.status = 1 AND m.status = 2);
        END IF;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
    RETURN coalesce(v_r, false);
END;
$$;
