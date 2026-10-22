-- Nekkora Platform - recuperacao de senha da identidade por e-mail (missao PLATFORM-API-IDENTIDADE-002, PR 4).
-- Relatorio: docs/relatorios/2026-10-22-identidade-002-recuperacao-senha.md.
--
-- CANDIDATA ate a aprovacao do relatorio da suite db/tests/recuperacao-020.
--
-- O que muda (nenhuma tabela nova, nenhum privilegio de tabela, nenhuma funcao existente):
--   * identity_email_tokens.purpose: 2 = RECUPERACAO_SENHA (reservado na 017). A CHECK (purpose = 1) e substituida por
--     purpose IN (1, 2), com o mesmo nome. Prazo, uso unico e imutabilidade sao os da 017 (CHECKs e trigger da tabela).
--     So o hash do token e gravado; a Platform ja tem INSERT e UPDATE (consumed_at, revoked_at) por coluna, sob a RLS
--     por identidade.
--   * identity_events.event_type: 16 = PASSWORD_RECOVERY_REQUESTED, 17 = PASSWORD_RESET (CHECK BETWEEN 1 AND 17).
--   * funcao nova public.identity_password_reset(p_token, p_new_hash), SECURITY DEFINER, dona nekkora_identity_owner,
--     EXECUTE so para nekkora_platform. A Platform nao tem UPDATE em identity_credentials (017): a troca de senha pelo
--     token precisa ser funcao do dono, como identity_password_change.
--   * privilegios por coluna do DONO (nekkora_identity_owner, NOLOGIN, sem membros) em identity_email_tokens: SELECT
--     (purpose, created_at) e UPDATE (consumed_at, revoked_at). A 017 so lhe deu a leitura de identity_email_token_lookup;
--     sem estes, a funcao falha com 42501 e nada muda (medido). Nenhum papel de LOGIN muda.
--
-- Contrato da funcao (missao 002, item 4, "recuperacao de senha por e-mail"):
--   * token de recuperacao (purpose 2) vivo, no prazo, da identidade ACTIVE com e-mail verificado; consumo e troca da
--     senha na MESMA transacao (um so comando);
--   * locks na ordem global da 017: identidade -> token -> credencial -> demais tokens de recuperacao vivos -> desafios
--     vivos -> sessoes vivas; hora medida DEPOIS dos locks e o prazo do token reconferido com ela;
--   * efeitos: token consumido; senha trocada; DEMAIS tokens de recuperacao vivos revogados; desafios pendentes
--     invalidados; TODAS as sessoes de identidade revogadas; evento 17. Nenhuma sessao criada. MFA PRESERVADO: fator e
--     codigos intocados - a proxima entrada exige o fator ou um codigo de recuperacao;
--   * recusa = false, sem efeito (token desconhecido, de outro proposito, consumido, revogado, vencido; identidade nao
--     ACTIVE ou nao verificada; hash vazio).
-- Instantes: encerramentos (revogacao de tokens e de sessoes) com greatest(instante, created_at), como a 018; o consumo
-- do token e a invalidacao dos desafios usam o instante medido (o consumo e escrita de CONCLUSAO: com o relogio recuado a
-- CHECK consumed_at >= created_at recusa sem efeito parcial e o titular repete; identity_auth_challenges nao tem CHECK de
-- ordem em invalidated_at).
--
-- Elevacao: como a 019 (funcao do dono sem membros). Sem a janela, nada muda (suite db/tests/recuperacao-020, B1).
-- Invariante b da 019 (lista exata de funcoes executaveis pela Platform) e SUBSTITUIDA pela secao 4 desta migration.

BEGIN;

-- === 1. Pre-condicao: 019 aplicada; CHECKs as da 017/019; funcao nova ainda inexistente ===
DO $$
BEGIN
    IF to_regprocedure('public.identity_recovery_codes_regenerate(text, text[])') IS NULL THEN
        RAISE EXCEPTION '020 exige a 019 aplicada';
    END IF;
    IF to_regprocedure('public.identity_password_reset(text, text)') IS NOT NULL THEN
        RAISE EXCEPTION '020: identity_password_reset ja existe';
    END IF;
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.identity_email_tokens'::regclass AND c.conname = 'identity_email_tokens_purpose_check')
       IS DISTINCT FROM 'CHECK ((purpose = 1))' THEN
        RAISE EXCEPTION '020: CHECK identity_email_tokens_purpose_check diferente da 017';
    END IF;
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.identity_events'::regclass AND c.conname = 'identity_events_event_type_check')
       IS DISTINCT FROM 'CHECK (((event_type >= 1) AND (event_type <= 15)))' THEN
        RAISE EXCEPTION '020: CHECK identity_events_event_type_check diferente da 019';
    END IF;
END;
$$;

-- === 2. Proposito 2 e eventos 16 e 17 ===
ALTER TABLE public.identity_email_tokens DROP CONSTRAINT identity_email_tokens_purpose_check;
ALTER TABLE public.identity_email_tokens ADD CONSTRAINT identity_email_tokens_purpose_check CHECK (purpose IN (1, 2));
COMMENT ON COLUMN public.identity_email_tokens.purpose IS '1=CONFIRMACAO_CADASTRO, 2=RECUPERACAO_SENHA (020)';
ALTER TABLE public.identity_events DROP CONSTRAINT identity_events_event_type_check;
ALTER TABLE public.identity_events ADD CONSTRAINT identity_events_event_type_check CHECK (event_type BETWEEN 1 AND 17);
COMMENT ON COLUMN public.identity_events.event_type IS '1=SIGNUP_REQUESTED, 2=SIGNUP_CONFIRMED, 3=MFA_ENROLLED, 4=MFA_VERIFIED, 5=RECOVERY_CODE_USED, '
    '6=ORG_CREATED, 7=ORG_SESSION_ISSUED, 8=ORG_SESSION_DENIED, 9=IDENTITY_LINKED, 10=LOGOUT, 11=MFA_REPLACED, 12=PASSWORD_CHANGED, '
    '13=STEP_UP, 14=ORG_STEP_UP, 15=RECOVERY_CODES_REGENERATED, 16=PASSWORD_RECOVERY_REQUESTED, 17=PASSWORD_RESET';

-- Privilegios do dono, so as colunas que a funcao le e escreve
GRANT SELECT (purpose, created_at), UPDATE (consumed_at, revoked_at) ON public.identity_email_tokens TO nekkora_identity_owner;

-- === 3. Funcao ===
CREATE FUNCTION public.identity_password_reset(p_token TEXT, p_new_hash TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_hash TEXT := public.identity_token_hash(p_token);
    v_identity UUID;
    v_token UUID;
    v_expira TIMESTAMPTZ;
    v_t TIMESTAMPTZ;
BEGIN
    PERFORM public.identity_require_read_committed();
    IF v_hash IS NULL OR p_new_hash IS NULL OR pg_catalog.btrim(p_new_hash) = '' THEN
        RETURN false;
    END IF;
    -- a primeira leitura, sem lock, so descobre a identidade; tudo e reconferido depois do lock dela
    SELECT t.identity_id INTO v_identity FROM public.identity_email_tokens t WHERE t.token_hash = v_hash AND t.purpose = 2;
    IF v_identity IS NULL THEN
        RETURN false;
    END IF;
    -- locks: identidade -> token -> credencial -> demais tokens de recuperacao -> desafios -> sessoes
    PERFORM 1 FROM public.identities i WHERE i.identity_id = v_identity AND i.status = 2 AND i.email_verified_at IS NOT NULL FOR UPDATE;
    IF NOT FOUND THEN
        RETURN false;
    END IF;
    SELECT t.token_id, t.expires_at INTO v_token, v_expira FROM public.identity_email_tokens t
     WHERE t.token_hash = v_hash AND t.identity_id = v_identity AND t.purpose = 2 AND t.consumed_at IS NULL AND t.revoked_at IS NULL
       FOR UPDATE;
    IF v_token IS NULL THEN
        RETURN false;
    END IF;
    PERFORM 1 FROM public.identity_credentials c WHERE c.identity_id = v_identity FOR UPDATE;
    IF NOT FOUND THEN
        RETURN false;
    END IF;
    PERFORM 1 FROM public.identity_email_tokens t
     WHERE t.identity_id = v_identity AND t.purpose = 2 AND t.token_id <> v_token AND t.consumed_at IS NULL AND t.revoked_at IS NULL FOR UPDATE;
    PERFORM 1 FROM public.identity_auth_challenges x
     WHERE x.identity_id = v_identity AND x.consumed_at IS NULL AND x.invalidated_at IS NULL FOR UPDATE;
    PERFORM 1 FROM public.identity_sessions x WHERE x.identity_id = v_identity AND x.revoked_at IS NULL FOR UPDATE;
    -- hora medida depois de TODOS os locks; o prazo do token e reconferido com ela
    v_t := pg_catalog.clock_timestamp();
    IF v_expira <= v_t THEN
        RETURN false;
    END IF;
    UPDATE public.identity_email_tokens SET consumed_at = v_t WHERE token_id = v_token;
    UPDATE public.identity_credentials SET password_hash = p_new_hash, password_changed_at = v_t WHERE identity_id = v_identity;
    UPDATE public.identity_email_tokens SET revoked_at = greatest(v_t, created_at)
     WHERE identity_id = v_identity AND purpose = 2 AND token_id <> v_token AND consumed_at IS NULL AND revoked_at IS NULL;
    UPDATE public.identity_auth_challenges SET invalidated_at = v_t, credential_hash = NULL, pending_password_hash = NULL
     WHERE identity_id = v_identity AND consumed_at IS NULL AND invalidated_at IS NULL;
    UPDATE public.identity_sessions SET revoked_at = greatest(v_t, created_at) WHERE identity_id = v_identity AND revoked_at IS NULL;
    INSERT INTO public.identity_events (identity_id, event_type, correlation_id) VALUES (v_identity, 17, pg_catalog.gen_random_uuid());
    RETURN true;
END;
$$;
ALTER FUNCTION public.identity_password_reset(TEXT, TEXT) OWNER TO nekkora_identity_owner;
REVOKE ALL ON FUNCTION public.identity_password_reset(TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.identity_password_reset(TEXT, TEXT) TO nekkora_platform;
COMMENT ON FUNCTION public.identity_password_reset(TEXT, TEXT) IS
    'Missao 002: recuperacao de senha por e-mail; consumo do token e troca da senha num so comando; todas as sessoes revogadas; MFA preservado; evento 17';

-- === 4. Invariantes ===
DO $$
DECLARE
    v_bad TEXT;
    v_exec_esperado CONSTANT TEXT := 'identity_account_is_linked(uuid,uuid), identity_challenge_lookup(text), '
        'identity_create_organization(text,text,text), identity_email_token_lookup(text), '
        'identity_link_account(text,text,uuid), '
        'identity_login_complete(text,text,bigint,text,text,text,timestamp with time zone), '
        'identity_mfa_confirm_first(text,uuid,text[],bigint,text,text,timestamp with time zone), '
        'identity_mfa_enroll_pending(text,text,bytea,bytea,text), identity_mfa_replace(text,uuid,text[]), '
        'identity_org_creation_allowed(text), '
        'identity_org_session_allowed(uuid,uuid,uuid), identity_org_session_target(text,uuid), identity_organizations(text), '
        'identity_password_change(text,text,text), '
        'identity_password_reset(text,text), '
        'identity_recovery_codes_regenerate(text,text[]), '
        'identity_resolve_login(text), identity_session_lookup(text), identity_signup_state(text)';
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_proc p WHERE p.oid = 'public.identity_password_reset(text, text)'::regprocedure
                   AND pg_get_userbyid(p.proowner) = 'nekkora_identity_owner' AND p.prosecdef AND p.provolatile = 'v'
                   AND p.proconfig = ARRAY['search_path=pg_catalog, pg_temp']) THEN
        RAISE EXCEPTION '020: atributos de identity_password_reset';
    END IF;
    SELECT string_agg(r.rolname, ', ' ORDER BY r.rolname) INTO v_bad FROM pg_roles r
    WHERE has_function_privilege(r.oid, 'public.identity_password_reset(text, text)', 'EXECUTE')
      AND NOT r.rolsuper AND r.rolname NOT IN ('nekkora_identity_owner', 'nekkora_platform');
    IF v_bad IS NOT NULL OR has_function_privilege('public', 'public.identity_password_reset(text, text)', 'EXECUTE') THEN
        RAISE EXCEPTION '020: EXECUTE indevido em identity_password_reset: %', coalesce(v_bad, 'PUBLIC');
    END IF;
    SELECT string_agg(p.oid::regprocedure::text, ', ' ORDER BY p.oid::regprocedure::text) INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND (p.proname LIKE 'identity\_%' OR pg_get_userbyid(p.proowner) = 'nekkora_identity_owner')
      AND has_function_privilege('nekkora_platform', p.oid, 'EXECUTE');
    IF v_bad IS DISTINCT FROM v_exec_esperado THEN
        RAISE EXCEPTION '020: funcoes de identidade executaveis pela Platform divergem: %', v_bad;
    END IF;
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.identity_email_tokens'::regclass AND c.conname = 'identity_email_tokens_purpose_check')
       IS DISTINCT FROM 'CHECK ((purpose = ANY (ARRAY[1, 2])))'
       OR (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.identity_events'::regclass AND c.conname = 'identity_events_event_type_check')
       IS DISTINCT FROM 'CHECK (((event_type >= 1) AND (event_type <= 17)))' THEN
        RAISE EXCEPTION '020: CHECKs do proposito ou do evento';
    END IF;
    -- dono: exatamente as colunas novas em identity_email_tokens, sem INSERT e sem UPDATE de origem do token
    IF NOT (has_column_privilege('nekkora_identity_owner', 'public.identity_email_tokens', 'purpose', 'SELECT')
            AND has_column_privilege('nekkora_identity_owner', 'public.identity_email_tokens', 'created_at', 'SELECT')
            AND has_column_privilege('nekkora_identity_owner', 'public.identity_email_tokens', 'consumed_at', 'UPDATE')
            AND has_column_privilege('nekkora_identity_owner', 'public.identity_email_tokens', 'revoked_at', 'UPDATE'))
       OR has_table_privilege('nekkora_identity_owner', 'public.identity_email_tokens', 'INSERT, DELETE, TRUNCATE')
       OR has_column_privilege('nekkora_identity_owner', 'public.identity_email_tokens', 'token_hash', 'UPDATE')
       OR has_column_privilege('nekkora_identity_owner', 'public.identity_email_tokens', 'expires_at', 'UPDATE')
       OR has_column_privilege('nekkora_identity_owner', 'public.identity_email_tokens', 'identity_id', 'UPDATE') THEN
        RAISE EXCEPTION '020: privilegios do dono em identity_email_tokens divergem';
    END IF;
    -- a Platform continua sem SELECT e sem UPDATE em identity_credentials
    IF has_table_privilege('nekkora_platform', 'public.identity_credentials', 'SELECT, UPDATE, DELETE, TRUNCATE')
       OR has_column_privilege('nekkora_platform', 'public.identity_credentials', 'password_hash', 'SELECT')
       OR has_column_privilege('nekkora_platform', 'public.identity_credentials', 'password_hash', 'UPDATE') THEN
        RAISE EXCEPTION '020: Platform com leitura ou escrita da credencial da identidade';
    END IF;
END;
$$;

COMMIT;
