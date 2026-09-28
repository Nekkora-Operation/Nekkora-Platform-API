-- p02_popula_mfa - papel: nekkora (superusuario), DECLARADO: massa da era 014/015 sobre a base populada pela p01
-- (depois de 013-015): fatores confirmados, eventos de MFA (inclusive RESET_BY_ADMIN SEM motivo, anteriores a 016)
-- e sessoes com o marcador de MFA. Os triggers da 014 continuam valendo. Nao e evidencia.
\set ON_ERROR_STOP on
\pset pager off

CREATE FUNCTION pg_temp.csrf() RETURNS TEXT LANGUAGE sql AS
$$ SELECT translate(rtrim(encode(gen_random_bytes(32), 'base64'), '='), '+/', '-_') $$;

DO $mfa$
DECLARE
    r    RECORD;
    f    UUID;
    ator UUID;
    n    INTEGER := 0;
BEGIN
    FOR r IN SELECT u.organization_id, u.user_id FROM users u JOIN organization_memberships m ON m.user_id = u.user_id
             WHERE u.status = 2 AND u.account_type = 1 AND m.status = 2 ORDER BY u.user_id LIMIT 60 LOOP
        n := n + 1;
        f := gen_random_uuid();
        INSERT INTO user_mfa_factors (factor_id, organization_id, user_id, factor_type, secret_ciphertext, secret_nonce, key_id, created_at, confirmed_at)
        VALUES (f, r.organization_id, r.user_id, 1, gen_random_bytes(36), gen_random_bytes(12), 'k1', now() - interval '10 days', now() - interval '10 days');
        INSERT INTO user_mfa_recovery_codes (organization_id, user_id, factor_id, code_hash)
        SELECT r.organization_id, r.user_id, f, encode(sha256(gen_random_bytes(32)), 'hex') FROM generate_series(1, 3);
        INSERT INTO mfa_events (organization_id, user_id, event_type, correlation_id) VALUES (r.organization_id, r.user_id, 1, gen_random_uuid());
        INSERT INTO user_sessions (organization_id, user_id, session_token_hash, expires_at, mfa_verified_at, csrf_token)
        VALUES (r.organization_id, r.user_id, encode(sha256(gen_random_bytes(32)), 'hex'), now() + interval '6 hours', now(), pg_temp.csrf());
        IF n % 6 = 0 THEN
            -- reset administrativo historico, sem motivo (anterior a 016): precisa sobreviver a 016 (NOT VALID)
            SELECT u2.user_id INTO ator FROM users u2 WHERE u2.organization_id = r.organization_id AND u2.user_id <> r.user_id
                AND u2.account_type = 1 ORDER BY u2.user_id LIMIT 1;
            INSERT INTO mfa_events (organization_id, user_id, actor_user_id, event_type, correlation_id)
            VALUES (r.organization_id, r.user_id, ator, 3, gen_random_uuid());
        END IF;
        IF n % 5 = 0 THEN
            INSERT INTO mfa_events (organization_id, user_id, event_type, correlation_id) VALUES (r.organization_id, r.user_id, 6, gen_random_uuid());
        END IF;
    END LOOP;
END;
$mfa$;

SELECT format('massa_mfa: fatores=%s codigos=%s eventos=%s resets_admin_sem_motivo=%s sessoes_mfa=%s',
    (SELECT count(*) FROM user_mfa_factors), (SELECT count(*) FROM user_mfa_recovery_codes), (SELECT count(*) FROM mfa_events),
    (SELECT count(*) FROM mfa_events WHERE event_type = 3), (SELECT count(*) FROM user_sessions WHERE mfa_verified_at IS NOT NULL));
