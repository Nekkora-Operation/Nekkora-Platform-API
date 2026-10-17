CREATE OR REPLACE FUNCTION public.identity_challenge_resolve(p_challenge_token TEXT, OUT o_challenge_id UUID, OUT o_identity_id UUID)
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_hash TEXT := public.identity_token_hash(p_challenge_token);
    v_identity UUID;
BEGIN
    IF v_hash IS NULL THEN
        RETURN;
    END IF;
    SELECT ch.identity_id INTO v_identity FROM public.identity_auth_challenges ch WHERE ch.token_hash = v_hash;
    IF v_identity IS NULL THEN
        RETURN;
    END IF;
    PERFORM 1 FROM public.identities i WHERE i.identity_id = v_identity AND i.status = 2 FOR UPDATE;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    SELECT ch.challenge_id, ch.identity_id INTO o_challenge_id, o_identity_id
      FROM public.identity_auth_challenges ch
     WHERE ch.token_hash = v_hash AND ch.identity_id = v_identity AND ch.purpose = 2
       AND ch.consumed_at IS NULL AND ch.invalidated_at IS NULL
       AND ch.expires_at > pg_catalog.clock_timestamp()
       FOR UPDATE;
END;
$$;
