CREATE OR REPLACE FUNCTION public.identity_link_account_impl(p_session_token TEXT, p_link_token TEXT, p_organization_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    s RECORD;
    c RECORD;
    u RECORD;
    v_hash TEXT := public.identity_token_hash(p_link_token);
    v_identity_email TEXT;
    v_t TIMESTAMPTZ;
    v_n INTEGER;
    v_corr UUID := pg_catalog.gen_random_uuid();
BEGIN
    PERFORM public.identity_require_read_committed();
    IF v_hash IS NULL OR p_organization_id IS NULL THEN
        RETURN false;
    END IF;
    -- locks, na ordem global: identidade -> sessao -> organizacao -> desafio -> conta -> linhas do fechamento
    SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
    IF s.o_identity_id IS NULL THEN
        RETURN false;
    END IF;
    SELECT i.email_normalized INTO v_identity_email FROM public.identities i
     WHERE i.identity_id = s.o_identity_id AND i.status = 2 AND i.email_verified_at IS NOT NULL FOR UPDATE;
    IF v_identity_email IS NULL THEN
        RETURN false;
    END IF;
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', '', true);
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    PERFORM 1 FROM public.organizations o WHERE o.organization_id = p_organization_id AND o.status = 1 FOR UPDATE;
    IF NOT FOUND THEN
        RETURN false;
    END IF;
    SELECT x.challenge_id, x.user_id INTO c
      FROM public.identity_link_challenges x
     WHERE x.token_hash = v_hash AND x.identity_id = s.o_identity_id
       AND x.consumed_at IS NULL AND x.revoked_at IS NULL
       FOR UPDATE;
    IF c.challenge_id IS NULL THEN
        RETURN false;
    END IF;
    SELECT x.user_id, x.account_type, x.status, x.email_normalized, x.email_verified_at INTO u
      FROM public.users x WHERE x.user_id = c.user_id AND x.organization_id = p_organization_id FOR UPDATE;
    IF u.user_id IS NULL THEN
        RETURN false;
    END IF;
    -- R2: as linhas que o fechamento (DC-17.12) altera sao travadas AGORA, antes de medir a hora: nenhuma espera depois
    PERFORM 1 FROM public.user_sessions x WHERE x.organization_id = p_organization_id AND x.user_id = u.user_id AND x.revoked_at IS NULL FOR UPDATE;
    PERFORM 1 FROM public.auth_challenges x WHERE x.organization_id = p_organization_id AND x.user_id = u.user_id
       AND x.consumed_at IS NULL AND x.invalidated_at IS NULL FOR UPDATE;
    PERFORM 1 FROM public.password_reset_tokens x WHERE x.user_id = u.user_id AND x.consumed_at IS NULL AND x.revoked_at IS NULL FOR UPDATE;
    PERFORM 1 FROM public.email_verification_tokens x WHERE x.user_id = u.user_id AND x.consumed_at IS NULL AND x.revoked_at IS NULL FOR UPDATE;
    PERFORM 1 FROM public.user_mfa_recovery_codes x WHERE x.organization_id = p_organization_id AND x.user_id = u.user_id
       AND x.used_at IS NULL AND x.invalidated_at IS NULL FOR UPDATE;
    PERFORM 1 FROM public.user_mfa_factors x WHERE x.organization_id = p_organization_id AND x.user_id = u.user_id
       AND x.disabled_at IS NULL FOR UPDATE;

    -- R2: hora medida DEPOIS de todos os locks; provas reconferidas com ela
    v_t := pg_catalog.clock_timestamp();
    IF NOT public.identity_session_proof(s.o_session_id, v_t, 'step_up')
       OR u.account_type <> 1 OR u.status <> 2 OR u.email_verified_at IS NULL
       OR u.email_normalized <> v_identity_email
       OR NOT EXISTS (SELECT 1 FROM public.organization_memberships m
                      WHERE m.user_id = u.user_id AND m.organization_id = p_organization_id AND m.status = 2)
       OR EXISTS (SELECT 1 FROM public.identity_users iu WHERE iu.user_id = u.user_id) THEN
        RETURN false;
    END IF;
    -- primeira escrita: o consumo, condicionado a validade em v_t e a credencial ainda vigente; sem consumo, sem efeito
    UPDATE public.identity_link_challenges SET consumed_at = v_t, account_credential_hash = NULL
     WHERE challenge_id = c.challenge_id AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > v_t
       AND account_credential_hash = (SELECT uc.password_hash FROM public.user_credentials uc WHERE uc.user_id = u.user_id);
    GET DIAGNOSTICS v_n = ROW_COUNT;
    IF v_n <> 1 THEN
        RETURN false;
    END IF;

    UPDATE public.users SET updated_at = v_t WHERE user_id = u.user_id AND organization_id = p_organization_id;   -- versao nova (peca 4)
    -- DC-17.12, na mesma transacao (linhas ja travadas)
    UPDATE public.user_sessions SET revoked_at = greatest(v_t, created_at)
     WHERE organization_id = p_organization_id AND user_id = u.user_id AND revoked_at IS NULL;
    UPDATE public.auth_challenges SET invalidated_at = v_t, credential_hash = NULL, pending_password_hash = NULL
     WHERE organization_id = p_organization_id AND user_id = u.user_id AND consumed_at IS NULL AND invalidated_at IS NULL;
    UPDATE public.password_reset_tokens SET revoked_at = greatest(v_t, created_at)
     WHERE user_id = u.user_id AND consumed_at IS NULL AND revoked_at IS NULL;
    UPDATE public.email_verification_tokens SET revoked_at = greatest(v_t, created_at)
     WHERE user_id = u.user_id AND consumed_at IS NULL AND revoked_at IS NULL;
    UPDATE public.user_mfa_recovery_codes SET invalidated_at = greatest(v_t, created_at)
     WHERE organization_id = p_organization_id AND user_id = u.user_id AND used_at IS NULL AND invalidated_at IS NULL;
    UPDATE public.user_mfa_factors SET disabled_at = greatest(v_t, created_at)
     WHERE organization_id = p_organization_id AND user_id = u.user_id AND disabled_at IS NULL;
    INSERT INTO public.identity_users (identity_id, user_id, link_channel) VALUES (s.o_identity_id, u.user_id, 'vinculacao');
    INSERT INTO public.identity_events (identity_id, event_type, target_organization_id, correlation_id)
    VALUES (s.o_identity_id, 9, p_organization_id, v_corr);
    RETURN true;
END;
$$;
