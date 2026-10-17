CREATE OR REPLACE FUNCTION public.identity_mfa_confirm_first(
    p_challenge_token TEXT, p_factor_id UUID, p_code_hashes TEXT[], p_passo_totp BIGINT,
    p_sessao_hash TEXT, p_csrf TEXT, p_sessao_expira TIMESTAMPTZ)
RETURNS TABLE (o_status TEXT, o_identity_session_id UUID)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_identity UUID;
    v_challenge UUID;
    c RECORD;
    v_cred TEXT;
    v_t TIMESTAMPTZ;
    v_n INTEGER;
    v_sessao UUID;
BEGIN
    PERFORM public.identity_require_read_committed();
    IF NOT public.identity_valid_code_hashes(p_code_hashes) OR p_factor_id IS NULL OR p_passo_totp IS NULL OR p_passo_totp < 0
       OR NOT public.identity_valid_session_params(p_sessao_hash, p_csrf, p_sessao_expira) THEN
        RETURN QUERY SELECT 'PARAMETRO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    -- locks: identidade -> desafio (finalidade 2) -> fator -> credencial; SO depois a hora
    SELECT r.o_challenge_id, r.o_identity_id INTO v_challenge, v_identity FROM public.identity_challenge_resolve(p_challenge_token) r;
    IF v_identity IS NULL THEN
        RETURN QUERY SELECT 'DESAFIO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    IF EXISTS (SELECT 1 FROM public.identity_mfa_factors f WHERE f.identity_id = v_identity AND f.confirmed_at IS NOT NULL) THEN
        RETURN QUERY SELECT 'RECUSADO'::TEXT, NULL::UUID; RETURN;   -- nao e o primeiro fator
    END IF;
    PERFORM 1 FROM public.identity_mfa_factors f
     WHERE f.factor_id = p_factor_id AND f.identity_id = v_identity AND f.confirmed_at IS NULL AND f.disabled_at IS NULL
       FOR UPDATE;
    IF NOT FOUND THEN
        RETURN QUERY SELECT 'RECUSADO'::TEXT, NULL::UUID; RETURN;
    END IF;
    SELECT x.password_hash INTO v_cred FROM public.identity_credentials x WHERE x.identity_id = v_identity FOR UPDATE;
    SELECT ch.credential_hash, ch.pending_password_hash INTO c FROM public.identity_auth_challenges ch WHERE ch.challenge_id = v_challenge;
    v_t := pg_catalog.clock_timestamp();
    -- prova de senha obsoleta: o desafio e invalidado e nenhuma sessao nasce, mesmo com o TOTP conferido
    IF v_cred IS DISTINCT FROM c.credential_hash THEN
        UPDATE public.identity_auth_challenges SET invalidated_at = v_t, credential_hash = NULL, pending_password_hash = NULL
         WHERE challenge_id = v_challenge AND consumed_at IS NULL AND invalidated_at IS NULL;
        RETURN QUERY SELECT 'SENHA_OBSOLETA'::TEXT, NULL::UUID; RETURN;
    END IF;
    IF p_sessao_expira <= v_t THEN
        RETURN QUERY SELECT 'PARAMETRO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    -- primeira escrita: o consumo condicionado a validade em v_t; sem ele, nenhum efeito
    UPDATE public.identity_auth_challenges SET consumed_at = v_t, credential_hash = NULL, pending_password_hash = NULL
     WHERE challenge_id = v_challenge AND consumed_at IS NULL AND invalidated_at IS NULL AND expires_at > v_t;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    IF v_n <> 1 THEN
        RETURN QUERY SELECT 'DESAFIO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    IF c.pending_password_hash IS NOT NULL THEN   -- re-hash: comparar e trocar sobre o hash validado
        UPDATE public.identity_credentials SET password_hash = c.pending_password_hash
         WHERE identity_id = v_identity AND password_hash = c.credential_hash;
    END IF;
    UPDATE public.identity_mfa_factors SET confirmed_at = v_t, last_used_step = p_passo_totp WHERE factor_id = p_factor_id;
    INSERT INTO public.identity_mfa_recovery_codes (identity_id, factor_id, code_hash)
    SELECT v_identity, p_factor_id, h FROM pg_catalog.unnest(p_code_hashes) h;
    INSERT INTO public.identity_sessions (identity_id, session_token_hash, expires_at, mfa_verified_at, csrf_token)
    VALUES (v_identity, p_sessao_hash, p_sessao_expira, v_t, p_csrf)
    RETURNING identity_session_id INTO v_sessao;
    INSERT INTO public.identity_events (identity_id, event_type, correlation_id) VALUES (v_identity, 3, pg_catalog.gen_random_uuid());
    RETURN QUERY SELECT 'SESSAO_CRIADA'::TEXT, v_sessao;
END;
$$;
