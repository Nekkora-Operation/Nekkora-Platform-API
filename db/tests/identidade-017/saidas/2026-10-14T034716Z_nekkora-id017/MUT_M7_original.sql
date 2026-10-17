CREATE OR REPLACE FUNCTION public.identity_password_change(p_session_token TEXT, p_expected_hash TEXT, p_new_hash TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    s RECORD;
    v_t TIMESTAMPTZ;
    v_n INTEGER;
BEGIN
    PERFORM public.identity_require_read_committed();
    IF p_expected_hash IS NULL OR p_new_hash IS NULL OR pg_catalog.btrim(p_new_hash) = '' OR p_new_hash = p_expected_hash THEN
        RETURN false;
    END IF;
    SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
    IF s.o_identity_id IS NULL THEN
        RETURN false;
    END IF;
    PERFORM 1 FROM public.identity_credentials x WHERE x.identity_id = s.o_identity_id FOR UPDATE;
    PERFORM 1 FROM public.identity_auth_challenges x WHERE x.identity_id = s.o_identity_id
       AND x.consumed_at IS NULL AND x.invalidated_at IS NULL FOR UPDATE;
    PERFORM 1 FROM public.identity_sessions x WHERE x.identity_id = s.o_identity_id AND x.identity_session_id <> s.o_session_id
       AND x.revoked_at IS NULL FOR UPDATE;
    v_t := pg_catalog.clock_timestamp();
    IF NOT public.identity_session_proof(s.o_session_id, v_t, 'step_up') THEN
        RETURN false;
    END IF;
    UPDATE public.identity_credentials SET password_hash = p_new_hash, password_changed_at = v_t
     WHERE identity_id = s.o_identity_id AND password_hash = p_expected_hash;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    IF v_n <> 1 THEN
        RETURN false;
    END IF;
    UPDATE public.identity_auth_challenges SET invalidated_at = v_t, credential_hash = NULL, pending_password_hash = NULL
     WHERE identity_id = s.o_identity_id AND consumed_at IS NULL AND invalidated_at IS NULL;
    UPDATE public.identity_sessions SET revoked_at = greatest(v_t, created_at)
     WHERE identity_id = s.o_identity_id AND identity_session_id <> s.o_session_id AND revoked_at IS NULL;
    INSERT INTO public.identity_events (identity_id, event_type, correlation_id) VALUES (s.o_identity_id, 12, pg_catalog.gen_random_uuid());
    RETURN true;
END;
$$;
