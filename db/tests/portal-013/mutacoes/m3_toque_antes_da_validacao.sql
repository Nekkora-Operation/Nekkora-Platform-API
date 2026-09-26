-- MUTACAO m3 (teste do runner; NUNCA numa base real) - toque em last_seen_at ANTES da validacao da
-- sessao: a sessao inativa e "revivida" pelo proprio toque e passa. O t04a PRECISA falhar (sessao
-- inativa deixa de dar NK401).
DO $$
DECLARE
    d TEXT := pg_get_functiondef('public.portal_resolve_session(uuid,text)'::regprocedure);
    ancora CONSTANT TEXT := E'    o_t := pg_catalog.clock_timestamp();   -- depois do lock da sessao (secao 11.4)\n';
BEGIN
    IF (length(d) - length(replace(d, ancora, ''))) / length(ancora) <> 1 THEN
        RAISE EXCEPTION 'mutacao m3: ancora nao unica';
    END IF;
    d := replace(d, ancora, ancora || E'    UPDATE public.user_sessions SET last_seen_at = o_t WHERE session_id = v_session_id; v_last_seen_at := o_t; -- MUTACAO m3\n');
    EXECUTE d;
END $$;
SELECT 'mutacao m3 aplicada' AS resultado, position('MUTACAO m3' IN prosrc) > 0 AS presente
FROM pg_proc WHERE oid = 'public.portal_resolve_session(uuid,text)'::regprocedure;
