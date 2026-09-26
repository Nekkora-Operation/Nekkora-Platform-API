CREATE OR REPLACE FUNCTION public.portal_resolve_session(p_organization_id uuid, p_session_token text, OUT o_user_id uuid, OUT o_client_membership_id uuid, OUT o_t timestamp with time zone)
 RETURNS record
 LANGUAGE plpgsql
 SET search_path TO 'pg_catalog', 'pg_temp'
AS $function$
DECLARE
    v_hash TEXT;
    v_session_id UUID;
    v_expires_at TIMESTAMPTZ;
    v_last_seen_at TIMESTAMPTZ;
    v_revoked_at TIMESTAMPTZ;
BEGIN
    -- contrato D2: 32 bytes aleatorios em base64url sem padding = 43 caracteres; com 32 bytes, o
    -- ultimo caractere carrega 4 bits e 2 de preenchimento zero (so 16 valores possiveis). O formato
    -- e validado ANTES do hash, e o token nunca e convertido para um tipo cujo erro ecoe o valor.
    IF p_organization_id IS NULL OR p_session_token IS NULL
       OR pg_catalog.length(p_session_token) <> 43
       OR p_session_token OPERATOR(pg_catalog.!~) '^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$' THEN
        RAISE EXCEPTION USING ERRCODE = 'NK401', MESSAGE = 'sessao do portal invalida';
    END IF;
    v_hash := pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(p_session_token, 'UTF8')), 'hex');

    SELECT s.session_id, s.user_id, s.expires_at, s.last_seen_at, s.revoked_at
      INTO v_session_id, o_user_id, v_expires_at, v_last_seen_at, v_revoked_at
      FROM public.user_sessions s
     WHERE s.organization_id = p_organization_id
       AND s.session_token_hash = v_hash
       FOR UPDATE;

    o_t := pg_catalog.clock_timestamp();   -- depois do lock da sessao (secao 11.4)

    IF v_session_id IS NULL
       OR v_revoked_at IS NOT NULL
       OR v_expires_at <= o_t                                   -- prazo absoluto (12 h, gravado no login)
       OR v_last_seen_at <= o_t - interval '15 minutes' THEN    -- inatividade do portal (D7)
        RAISE EXCEPTION USING ERRCODE = 'NK401', MESSAGE = 'sessao do portal invalida';
    END IF;

    SELECT m.client_membership_id
      INTO o_client_membership_id
      FROM public.users u
      JOIN public.organizations o ON o.organization_id = u.organization_id
      JOIN public.client_memberships m ON m.user_id = u.user_id AND m.organization_id = u.organization_id
      JOIN public.clients c ON c.client_id = m.client_id AND c.organization_id = m.organization_id
     WHERE u.user_id = o_user_id
       AND u.organization_id = p_organization_id
       AND u.account_type = 2 AND u.status = 2 AND u.email_verified_at IS NOT NULL
       AND o.status = 1
       AND m.status = 2
       AND c.status = 1;

    IF o_client_membership_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'NK401', MESSAGE = 'sessao do portal invalida';
    END IF;

    -- toque condicional na linha ja travada: nao mexe em expires_at, nao retrocede, nao revive
    UPDATE public.user_sessions
       SET last_seen_at = o_t
     WHERE session_id = v_session_id
       AND organization_id = p_organization_id
       AND last_seen_at <= o_t - interval '1 minute';
END;
$function$

