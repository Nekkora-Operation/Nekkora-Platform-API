-- Nekkora Platform - regeneracao independente dos codigos de recuperacao da identidade (missao PLATFORM-API-IDENTIDADE-002,
-- PR 3). Relatorio: docs/relatorios/2026-10-22-identidade-002-credenciais.md.
--
-- CANDIDATA ate a aprovacao do relatorio da suite db/tests/codigos-019.
--
-- O que muda (nenhuma tabela nova, nenhum privilegio de tabela, nenhuma funcao existente):
--   * identity_events.event_type: 15 = RECOVERY_CODES_REGENERATED. A CHECK da 017 (BETWEEN 1 AND 14) e substituida por
--     BETWEEN 1 AND 15, com o mesmo nome; o comentario da coluna ganha o codigo novo.
--   * funcao nova public.identity_recovery_codes_regenerate(p_session_token, p_code_hashes), SECURITY DEFINER, dona
--     nekkora_identity_owner, EXECUTE so para nekkora_platform. Os codigos da identidade so nascem e so sao invalidados
--     pelo dono (trigger protect_identity_recovery_code da 017): a regeneracao precisa ser funcao dele.
--
-- Contrato da funcao (missao 002, item 4):
--   * so sessao COMUM com step-up recente: prova 'step_up' da 017 (nao restrita, step-up de ate 5 min), reconferida no
--     instante medido DEPOIS de todos os locks. O step-up da identidade so aceita TOTP (P1), entao "step-up recente por
--     TOTP" e exatamente essa prova. Sessao restrita: recusa (ela so sai por identity_mfa_replace);
--   * locks na ordem global da 017: identidade -> sessao (identity_session_resolve) -> fator confirmado -> codigos vivos;
--   * substituicao ATOMICA do conjunto: os vivos invalidados com greatest(instante, created_at) (o mesmo ajuste de
--     encerramento da 018; o valor gravado e o limite inferior aceito pela CHECK, nao o instante exato) e o conjunto novo
--     (8 a 16 hashes distintos, identity_valid_code_hashes) gravado para o fator confirmado vigente, no mesmo comando;
--   * evento 15 na mesma transacao;
--   * recusa = false, sem efeito (sessao invalida, sem prova, sem fator confirmado, hashes invalidos).
-- Os codigos em claro nunca chegam ao banco (so os hashes); a aplicacao os mostra uma unica vez.
--
-- Elevacao: a funcao pertence a nekkora_identity_owner, papel sem membership de ninguem (invariante 9 da 017); ALTER
-- FUNCTION ... OWNER TO exige ser membro do papel novo. A 019 precisa da janela de SUPERUSER do migrador, como a 017 e a
-- 018 (db/bootstrap/aplicar_como_migrador.sh). Sem ela, nada muda (suite db/tests/codigos-019, B1).
--
-- Invariante 3 da 017 (lista exata das funcoes de identidade executaveis pela Platform) e SUBSTITUIDA pela da secao 4
-- desta migration, que e a lista da 017 mais a funcao nova. As suites que rodam a secao 12 da 017 sobre 019+ a adaptam
-- (db/tests/codigos-019/run.sh), como a 014 adaptada na suite da 017.

BEGIN;

-- === 1. Pre-condicao: 018 aplicada; CHECK do evento e a da 017; funcao nova ainda inexistente ===
DO $$
BEGIN
    IF to_regprocedure('public.identity_mfa_replace(text, uuid, text[])') IS NULL
       OR NOT has_column_privilege('nekkora_ops_mfa_owner', 'public.user_mfa_factors', 'created_at', 'SELECT') THEN
        RAISE EXCEPTION '019 exige a 018 aplicada';
    END IF;
    IF to_regprocedure('public.identity_recovery_codes_regenerate(text, text[])') IS NOT NULL THEN
        RAISE EXCEPTION '019: identity_recovery_codes_regenerate ja existe';
    END IF;
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.identity_events'::regclass AND c.conname = 'identity_events_event_type_check')
       IS DISTINCT FROM 'CHECK (((event_type >= 1) AND (event_type <= 14)))' THEN
        RAISE EXCEPTION '019: CHECK identity_events_event_type_check diferente da 017';
    END IF;
END;
$$;

-- === 2. Evento 15 ===
ALTER TABLE public.identity_events DROP CONSTRAINT identity_events_event_type_check;
ALTER TABLE public.identity_events ADD CONSTRAINT identity_events_event_type_check CHECK (event_type BETWEEN 1 AND 15);
COMMENT ON COLUMN public.identity_events.event_type IS '1=SIGNUP_REQUESTED, 2=SIGNUP_CONFIRMED, 3=MFA_ENROLLED, 4=MFA_VERIFIED, 5=RECOVERY_CODE_USED, '
    '6=ORG_CREATED, 7=ORG_SESSION_ISSUED, 8=ORG_SESSION_DENIED, 9=IDENTITY_LINKED, 10=LOGOUT, 11=MFA_REPLACED, 12=PASSWORD_CHANGED, '
    '13=STEP_UP, 14=ORG_STEP_UP, 15=RECOVERY_CODES_REGENERATED';

-- === 3. Funcao ===
CREATE FUNCTION public.identity_recovery_codes_regenerate(p_session_token TEXT, p_code_hashes TEXT[])
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    s RECORD;
    v_factor UUID;
    v_t TIMESTAMPTZ;
BEGIN
    PERFORM public.identity_require_read_committed();
    IF NOT public.identity_valid_code_hashes(p_code_hashes) THEN
        RETURN false;
    END IF;
    -- locks: identidade -> sessao (identity_session_resolve) -> fator confirmado -> codigos vivos
    SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
    IF s.o_identity_id IS NULL THEN
        RETURN false;
    END IF;
    SELECT f.factor_id INTO v_factor FROM public.identity_mfa_factors f
     WHERE f.identity_id = s.o_identity_id AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL FOR UPDATE;
    IF v_factor IS NULL THEN
        RETURN false;
    END IF;
    PERFORM 1 FROM public.identity_mfa_recovery_codes r
     WHERE r.identity_id = s.o_identity_id AND r.used_at IS NULL AND r.invalidated_at IS NULL FOR UPDATE;
    -- hora medida depois de TODOS os locks; a prova (sessao comum, step-up de ate 5 min) e reconferida com ela
    v_t := pg_catalog.clock_timestamp();
    IF NOT public.identity_session_proof(s.o_session_id, v_t, 'step_up') THEN
        RETURN false;
    END IF;
    UPDATE public.identity_mfa_recovery_codes SET invalidated_at = greatest(v_t, created_at)
     WHERE identity_id = s.o_identity_id AND used_at IS NULL AND invalidated_at IS NULL;
    INSERT INTO public.identity_mfa_recovery_codes (identity_id, factor_id, code_hash)
    SELECT s.o_identity_id, v_factor, h FROM pg_catalog.unnest(p_code_hashes) h;
    INSERT INTO public.identity_events (identity_id, event_type, correlation_id) VALUES (s.o_identity_id, 15, pg_catalog.gen_random_uuid());
    RETURN true;
END;
$$;
ALTER FUNCTION public.identity_recovery_codes_regenerate(TEXT, TEXT[]) OWNER TO nekkora_identity_owner;
REVOKE ALL ON FUNCTION public.identity_recovery_codes_regenerate(TEXT, TEXT[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.identity_recovery_codes_regenerate(TEXT, TEXT[]) TO nekkora_platform;
COMMENT ON FUNCTION public.identity_recovery_codes_regenerate(TEXT, TEXT[]) IS
    'Missao 002: regeneracao independente dos codigos de recuperacao; sessao comum com step-up de ate 5 min; evento 15';

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
        'identity_recovery_codes_regenerate(text,text[]), '
        'identity_resolve_login(text), identity_session_lookup(text), identity_signup_state(text)';
BEGIN
    -- a. funcao nova: dona, SECURITY DEFINER, search_path fixo, volatil, EXECUTE so do dono e da Platform
    IF NOT EXISTS (SELECT 1 FROM pg_proc p WHERE p.oid = 'public.identity_recovery_codes_regenerate(text, text[])'::regprocedure
                   AND pg_get_userbyid(p.proowner) = 'nekkora_identity_owner' AND p.prosecdef AND p.provolatile = 'v'
                   AND p.proconfig = ARRAY['search_path=pg_catalog, pg_temp']) THEN
        RAISE EXCEPTION '019: atributos de identity_recovery_codes_regenerate';
    END IF;
    SELECT string_agg(r.rolname, ', ' ORDER BY r.rolname) INTO v_bad FROM pg_roles r
    WHERE has_function_privilege(r.oid, 'public.identity_recovery_codes_regenerate(text, text[])', 'EXECUTE')
      AND NOT r.rolsuper AND r.rolname NOT IN ('nekkora_identity_owner', 'nekkora_platform');
    IF v_bad IS NOT NULL OR has_function_privilege('public', 'public.identity_recovery_codes_regenerate(text, text[])', 'EXECUTE') THEN
        RAISE EXCEPTION '019: EXECUTE indevido em identity_recovery_codes_regenerate: %', coalesce(v_bad, 'PUBLIC');
    END IF;
    -- b. lista exata das funcoes de identidade executaveis pela Platform (substitui a invariante 3 da 017)
    SELECT string_agg(p.oid::regprocedure::text, ', ' ORDER BY p.oid::regprocedure::text) INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND (p.proname LIKE 'identity\_%' OR pg_get_userbyid(p.proowner) = 'nekkora_identity_owner')
      AND has_function_privilege('nekkora_platform', p.oid, 'EXECUTE');
    IF v_bad IS DISTINCT FROM v_exec_esperado THEN
        RAISE EXCEPTION '019: funcoes de identidade executaveis pela Platform divergem: %', v_bad;
    END IF;
    -- c. CHECK do evento
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.identity_events'::regclass AND c.conname = 'identity_events_event_type_check')
       IS DISTINCT FROM 'CHECK (((event_type >= 1) AND (event_type <= 15)))' THEN
        RAISE EXCEPTION '019: CHECK do evento';
    END IF;
    -- d. a Platform continua sem escrever codigos (INSERT/UPDATE) e sem privilegio no dono
    IF has_table_privilege('nekkora_platform', 'public.identity_mfa_recovery_codes', 'INSERT, UPDATE, DELETE, TRUNCATE')
       OR pg_has_role('nekkora_platform', 'nekkora_identity_owner', 'MEMBER') THEN
        RAISE EXCEPTION '019: Platform com escrita nos codigos ou membro do dono';
    END IF;
END;
$$;

COMMIT;
