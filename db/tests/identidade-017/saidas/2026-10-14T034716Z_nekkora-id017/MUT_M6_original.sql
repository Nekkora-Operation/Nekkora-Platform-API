CREATE OR REPLACE FUNCTION public.identity_login_complete(
    p_challenge_token TEXT, p_metodo TEXT, p_passo_totp BIGINT, p_codigo_hash TEXT,
    p_sessao_hash TEXT, p_csrf TEXT, p_sessao_expira TIMESTAMPTZ)
RETURNS TABLE (o_status TEXT, o_identity_session_id UUID)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_hash TEXT := public.identity_token_hash(p_challenge_token);
    v_identity UUID;
    c RECORD;
    v_cred TEXT;
    v_factor UUID;
    v_last BIGINT;
    v_code UUID;
    v_t TIMESTAMPTZ;
    v_n INTEGER;
    v_sessao UUID;
    v_rec BOOLEAN := p_metodo = 'recuperacao';
BEGIN
    PERFORM public.identity_require_read_committed();
    IF p_metodo IS NULL OR p_metodo NOT IN ('totp', 'recuperacao')
       OR (p_metodo = 'totp' AND (p_passo_totp IS NULL OR p_passo_totp < 0 OR p_codigo_hash IS NOT NULL))
       OR (p_metodo = 'recuperacao' AND (p_passo_totp IS NOT NULL OR p_codigo_hash IS NULL
                                         OR p_codigo_hash OPERATOR(pg_catalog.!~) '^[0-9a-f]{64}$'))
       OR NOT public.identity_valid_session_params(p_sessao_hash, p_csrf, p_sessao_expira) THEN
        RETURN QUERY SELECT 'PARAMETRO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    IF v_hash IS NULL THEN
        RETURN QUERY SELECT 'DESAFIO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    SELECT ch.identity_id INTO v_identity FROM public.identity_auth_challenges ch WHERE ch.token_hash = v_hash;
    IF v_identity IS NULL THEN
        RETURN QUERY SELECT 'DESAFIO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    -- locks, todos antes da hora
    PERFORM 1 FROM public.identities i WHERE i.identity_id = v_identity FOR UPDATE;
    SELECT ch.challenge_id, ch.purpose, ch.credential_hash, ch.pending_password_hash, ch.expires_at, ch.consumed_at, ch.invalidated_at
      INTO c FROM public.identity_auth_challenges ch WHERE ch.token_hash = v_hash AND ch.identity_id = v_identity FOR UPDATE;
    SELECT x.password_hash INTO v_cred FROM public.identity_credentials x WHERE x.identity_id = v_identity FOR UPDATE;
    SELECT f.factor_id, f.last_used_step INTO v_factor, v_last FROM public.identity_mfa_factors f
     WHERE f.identity_id = v_identity AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL FOR UPDATE;
    IF v_rec THEN
        SELECT r.code_id INTO v_code FROM public.identity_mfa_recovery_codes r
         WHERE r.identity_id = v_identity AND r.factor_id = v_factor AND r.code_hash = p_codigo_hash
           AND r.used_at IS NULL AND r.invalidated_at IS NULL FOR UPDATE;
        PERFORM 1 FROM public.identity_sessions x WHERE x.identity_id = v_identity AND x.revoked_at IS NULL FOR UPDATE;
        PERFORM 1 FROM public.identity_auth_challenges x WHERE x.identity_id = v_identity AND x.challenge_id <> c.challenge_id
           AND x.consumed_at IS NULL AND x.invalidated_at IS NULL FOR UPDATE;
    END IF;
    v_t := pg_catalog.clock_timestamp();

    -- 1. desafio: so finalidade 1, vivo, no prazo; identidade ACTIVE e verificada
    IF c.challenge_id IS NULL OR c.purpose <> 1 OR c.consumed_at IS NOT NULL OR c.invalidated_at IS NOT NULL OR c.expires_at <= v_t
       OR NOT EXISTS (SELECT 1 FROM public.identities i WHERE i.identity_id = v_identity AND i.status = 2 AND i.email_verified_at IS NOT NULL) THEN
        RETURN QUERY SELECT 'DESAFIO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    -- 2. credencial vigente, SEMPRE (com ou sem re-hash pendente). Hash divergente nao prova equivalencia: obsoleta.
    IF v_cred IS DISTINCT FROM c.credential_hash THEN
        UPDATE public.identity_auth_challenges SET invalidated_at = v_t, credential_hash = NULL, pending_password_hash = NULL
         WHERE challenge_id = c.challenge_id;
        RETURN QUERY SELECT 'SENHA_OBSOLETA'::TEXT, NULL::UUID; RETURN;
    END IF;
    -- 3. fator: passo TOTP novo (anti-replay) ou codigo de recuperacao vivo do fator confirmado
    IF v_factor IS NULL OR (NOT v_rec AND v_last IS NOT NULL AND p_passo_totp <= v_last) OR (v_rec AND v_code IS NULL) THEN
        RETURN QUERY SELECT 'FATOR_RECUSADO'::TEXT, NULL::UUID; RETURN;
    END IF;
    IF p_sessao_expira <= v_t THEN
        RETURN QUERY SELECT 'PARAMETRO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;

    -- efeitos: primeiro o consumo condicionado; depois re-hash, fator/codigo, sessao e (recuperacao) T0, no mesmo comando
    UPDATE public.identity_auth_challenges SET consumed_at = v_t, credential_hash = NULL, pending_password_hash = NULL
     WHERE challenge_id = c.challenge_id AND consumed_at IS NULL AND invalidated_at IS NULL AND expires_at > v_t;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    IF v_n <> 1 THEN
        RETURN QUERY SELECT 'DESAFIO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    IF c.pending_password_hash IS NOT NULL THEN
        UPDATE public.identity_credentials SET password_hash = c.pending_password_hash
         WHERE identity_id = v_identity AND password_hash = c.credential_hash;
    END IF;
    INSERT INTO public.identity_sessions (identity_id, session_token_hash, expires_at, mfa_verified_at, recovery_used_at, csrf_token)
    VALUES (v_identity, p_sessao_hash, p_sessao_expira, v_t, CASE WHEN v_rec THEN v_t END, p_csrf)
    RETURNING identity_session_id INTO v_sessao;
    IF NOT v_rec THEN
        UPDATE public.identity_mfa_factors SET last_used_step = p_passo_totp WHERE factor_id = v_factor;
        INSERT INTO public.identity_events (identity_id, event_type, correlation_id) VALUES (v_identity, 4, pg_catalog.gen_random_uuid());
    ELSE
        -- T0 (DC-17.13): codigo consumido apontando a sessao restrita; demais sessoes revogadas; demais desafios invalidados.
        -- Os codigos restantes e o fator antigo continuam (troca deliberada, ate T1).
        UPDATE public.identity_mfa_recovery_codes SET used_at = v_t, used_in_identity_session_id = v_sessao WHERE code_id = v_code;
        UPDATE public.identity_sessions SET revoked_at = greatest(v_t, created_at)
         WHERE identity_id = v_identity AND identity_session_id <> v_sessao AND revoked_at IS NULL;
        UPDATE public.identity_auth_challenges SET invalidated_at = v_t, credential_hash = NULL, pending_password_hash = NULL
         WHERE identity_id = v_identity AND challenge_id <> c.challenge_id AND consumed_at IS NULL AND invalidated_at IS NULL;
        INSERT INTO public.identity_events (identity_id, event_type, correlation_id) VALUES (v_identity, 5, pg_catalog.gen_random_uuid());
    END IF;
    RETURN QUERY SELECT 'SESSAO_CRIADA'::TEXT, v_sessao;
END;
$$;
