-- Nekkora Platform - segundo fator OPCIONAL na entrada geral (missao PLATFORM-LOCAL-002, item 2). Relatorio:
-- docs/relatorios/2026-11-02-mfa-opcional.md.
--
-- CANDIDATA ate a aprovacao do relatorio e da suite db/tests/mfa-opcional-029.
--
-- Politica aprovada pela titular em 2026-11-02 (resposta registrada no relatorio): sem MFA, a pessoa entra com senha; toda
-- acao sensivel que hoje exige step-up por TOTP passa a aceitar, SO para quem NUNCA confirmou fator, a reconfirmacao da
-- senha feita ha ate 5 minutos na mesma sessao. Quem tem (ou ja teve) fator confirmado continua exatamente como antes: o
-- TOTP e exigido no login e nas acoes sensiveis, e a senha NUNCA substitui o codigo.
--
-- O que muda:
--   * identity_sessions.mfa_verified_at deixa de ser NOT NULL. Nulo = sessao nascida SO da senha, permitida apenas para
--     identidade que nunca confirmou fator (identity_never_had_factor). Coluna nova password_step_up_at (reconfirmacao da
--     senha, so em sessao sem MFA). CHECKs: reconfirmacao so sem MFA; marca de recuperacao so com MFA.
--   * user_sessions.password_step_up_at: a mesma marca na sessao de organizacao VINCULADA nascida de sessao sem MFA.
--   * identity_events: 18 = LOGIN_WITHOUT_SECOND_FACTOR, 19 = PASSWORD_RECONFIRMED (com a organizacao, se for numa sessao de
--     organizacao), 20 = MFA_ACTIVATED (ativacao voluntaria pelas configuracoes).
--   * funcoes novas (dono nekkora_identity_owner, EXECUTE so para nekkora_platform):
--       identity_login_password      - conclui o login de quem nunca confirmou fator (desafio de finalidade 2), sem QR;
--       identity_password_reconfirm  - grava a reconfirmacao da senha (identidade ou sessao de organizacao vinculada);
--       identity_mfa_activate        - confirma o primeiro fator a partir da sessao sem MFA, grava os codigos, revoga TODAS
--                                      as sessoes da identidade e cria uma sessao nova com MFA.
--     auxiliar identity_never_had_factor (sem EXECUTE para ninguem alem do dono).
--   * funcoes redefinidas (mesmo dono, mesma assinatura): identity_session_resolve, identity_session_lookup,
--     identity_session_proof (modo 'step_up' aceita o ramo da senha; modo novo 'ativacao'), identity_org_creation_allowed,
--     identity_org_session_allowed, identity_mfa_enroll_pending (sessao sem MFA cadastra o pendente com 'ativacao').
--   * triggers redefinidas: enforce_identity_session_insert, prevent_identity_session_change (o marcador de MFA passa a
--     ser comparado com IS DISTINCT FROM, ja que pode ser nulo), enforce_user_session_mfa_on_insert,
--     prevent_user_session_reactivation.
--
-- O que NAO muda: nenhum fator e desabilitado, nenhum codigo e invalidado, nenhuma sessao e revogada pela migration
-- (conferido no fim, secao 9). A troca de fator (identity_mfa_replace) e a regeneracao de codigos seguem exigindo MFA.
-- Identidade cujo fator foi desabilitado pelo reset operacional da 016 conta como "ja teve fator": continua no fluxo de
-- cadastro do fator no login (mfa_enrollment_required), sem sessao so de senha.
--
-- Elevacao: redefine funcoes do dono nekkora_identity_owner e cria funcoes dele (como a 018 e a 019): janela de SUPERUSER
-- do migrador (db/bootstrap/aplicar_como_migrador.sh, padrao com 029).

BEGIN;

-- === 1. Pre-condicao: 028 aplicada; CHECK do evento e a da 020; funcoes novas inexistentes; retrato dos fatores ===
DO $$
BEGIN
    IF to_regclass('public.automation_artifacts') IS NULL
       OR to_regprocedure('public.identity_password_reset(text, text)') IS NULL THEN
        RAISE EXCEPTION '029 exige a 028 aplicada';
    END IF;
    IF to_regprocedure('public.identity_login_password(text, text, text, timestamp with time zone)') IS NOT NULL
       OR to_regprocedure('public.identity_password_reconfirm(text, text, uuid, uuid)') IS NOT NULL
       OR to_regprocedure('public.identity_never_had_factor(uuid)') IS NOT NULL THEN
        RAISE EXCEPTION '029: funcoes da 029 ja existem';
    END IF;
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.identity_events'::regclass AND c.conname = 'identity_events_event_type_check')
       IS DISTINCT FROM 'CHECK (((event_type >= 1) AND (event_type <= 17)))' THEN
        RAISE EXCEPTION '029: CHECK identity_events_event_type_check diferente da 020';
    END IF;
END;
$$;

-- retrato do que a migration NAO pode tocar (secao 9 compara): fatores confirmados ativos, codigos vivos, sessoes ativas
CREATE TEMPORARY TABLE n029_antes ON COMMIT DROP AS
SELECT (SELECT count(*) FROM public.identity_mfa_factors WHERE confirmed_at IS NOT NULL AND disabled_at IS NULL) AS fatores,
       (SELECT count(*) FROM public.identity_mfa_recovery_codes WHERE used_at IS NULL AND invalidated_at IS NULL) AS codigos,
       (SELECT count(*) FROM public.identity_sessions WHERE revoked_at IS NULL) AS sessoes_identidade,
       (SELECT count(*) FROM public.user_sessions WHERE revoked_at IS NULL) AS sessoes_organizacao,
       (SELECT count(*) FROM public.user_mfa_factors WHERE confirmed_at IS NOT NULL AND disabled_at IS NULL) AS fatores_conta;

-- === 2. Eventos 18-20 ===
ALTER TABLE public.identity_events DROP CONSTRAINT identity_events_event_type_check;
ALTER TABLE public.identity_events ADD CONSTRAINT identity_events_event_type_check CHECK (event_type BETWEEN 1 AND 20);
COMMENT ON COLUMN public.identity_events.event_type IS '1=SIGNUP_REQUESTED, 2=SIGNUP_CONFIRMED, 3=MFA_ENROLLED, 4=MFA_VERIFIED, 5=RECOVERY_CODE_USED, '
    '6=ORG_CREATED, 7=ORG_SESSION_ISSUED, 8=ORG_SESSION_DENIED, 9=IDENTITY_LINKED, 10=LOGOUT, 11=MFA_REPLACED, 12=PASSWORD_CHANGED, '
    '13=STEP_UP, 14=ORG_STEP_UP, 15=RECOVERY_CODES_REGENERATED, 16=PASSWORD_RECOVERY_REQUESTED, 17=PASSWORD_RESET, '
    '18=LOGIN_WITHOUT_SECOND_FACTOR, 19=PASSWORD_RECONFIRMED, 20=MFA_ACTIVATED';

-- === 3. Colunas e CHECKs ===
ALTER TABLE public.identity_sessions ALTER COLUMN mfa_verified_at DROP NOT NULL;
ALTER TABLE public.identity_sessions ADD COLUMN password_step_up_at TIMESTAMPTZ;
ALTER TABLE public.identity_sessions ADD CONSTRAINT identity_sessions_password_step_up_without_mfa
    CHECK (password_step_up_at IS NULL OR mfa_verified_at IS NULL);
ALTER TABLE public.identity_sessions ADD CONSTRAINT identity_sessions_recovery_requires_mfa
    CHECK (recovery_used_at IS NULL OR mfa_verified_at IS NOT NULL);
COMMENT ON COLUMN public.identity_sessions.mfa_verified_at IS
    'instante da verificacao do segundo fator; NULO = sessao so de senha, permitida apenas a identidade que nunca confirmou fator (029)';
COMMENT ON COLUMN public.identity_sessions.password_step_up_at IS
    '029: ultima reconfirmacao da senha nesta sessao sem MFA (substitui o step-up por TOTP por 5 min); so identity_password_reconfirm escreve';

ALTER TABLE public.user_sessions ADD COLUMN password_step_up_at TIMESTAMPTZ;
ALTER TABLE public.user_sessions ADD CONSTRAINT user_sessions_password_step_up_without_mfa
    CHECK (password_step_up_at IS NULL OR (mfa_verified_at IS NULL AND identity_session_id IS NOT NULL));
COMMENT ON COLUMN public.user_sessions.password_step_up_at IS
    '029: reconfirmacao da senha na sessao de organizacao vinculada sem MFA; so identity_password_reconfirm escreve';

-- === 4. Auxiliar: a identidade nunca confirmou fator (inclusive os desabilitados pelo reset ou pela troca) ===
CREATE FUNCTION public.identity_never_had_factor(p_identity_id UUID) RETURNS BOOLEAN
LANGUAGE sql
STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT p_identity_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM public.identity_mfa_factors f WHERE f.identity_id = p_identity_id AND f.confirmed_at IS NOT NULL)
$$;

-- === 5. Triggers ===

CREATE OR REPLACE FUNCTION enforce_identity_session_insert() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF NEW.revoked_at IS NOT NULL OR NEW.mfa_step_up_at IS NOT NULL OR NEW.recovery_cleared_at IS NOT NULL
       OR NEW.password_step_up_at IS NOT NULL THEN
        RAISE EXCEPTION 'sessao de identidade nova nasce sem revogacao, sem step-up, sem reconfirmacao e sem levantamento de restricao'
            USING ERRCODE = '23514';
    END IF;
    -- marcas que abrem janela (DC-17.13) nunca no futuro: estenderiam a janela; retroativas so a encurtam
    IF NEW.recovery_used_at > pg_catalog.clock_timestamp() OR NEW.mfa_verified_at > pg_catalog.clock_timestamp() THEN
        RAISE EXCEPTION 'marca de recuperacao ou de MFA no futuro' USING ERRCODE = '23514';
    END IF;
    -- 029: sessao so de senha apenas para identidade que nunca confirmou fator (a sessao so nasce por funcao do dono)
    IF NEW.mfa_verified_at IS NULL AND NOT public.identity_never_had_factor(NEW.identity_id) THEN
        RAISE EXCEPTION 'sessao sem segundo fator para identidade com fator confirmado' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION prevent_identity_session_change() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at THEN
        RAISE EXCEPTION 'sessao de identidade revogada nao pode ser reabilitada' USING ERRCODE = '23514';
    END IF;
    -- 029: o marcador de MFA pode ser nulo; comparacao com IS DISTINCT FROM
    IF NEW.identity_session_id <> OLD.identity_session_id OR NEW.identity_id <> OLD.identity_id
       OR NEW.session_token_hash <> OLD.session_token_hash OR NEW.created_at <> OLD.created_at
       OR NEW.mfa_verified_at IS DISTINCT FROM OLD.mfa_verified_at OR NEW.csrf_token <> OLD.csrf_token
       OR NEW.recovery_used_at IS DISTINCT FROM OLD.recovery_used_at THEN
        RAISE EXCEPTION 'identidade, marcadores e CSRF da sessao de identidade sao imutaveis' USING ERRCODE = '23514';
    END IF;
    IF NEW.expires_at > OLD.expires_at THEN
        RAISE EXCEPTION 'prazo absoluto da sessao de identidade nao pode ser estendido' USING ERRCODE = '23514';
    END IF;
    IF NEW.mfa_step_up_at IS DISTINCT FROM OLD.mfa_step_up_at
       AND (NEW.mfa_step_up_at IS NULL OR (OLD.mfa_step_up_at IS NOT NULL AND NEW.mfa_step_up_at < OLD.mfa_step_up_at)
            OR NEW.revoked_at IS NOT NULL) THEN
        RAISE EXCEPTION 'step-up so avanca, e so em sessao nao revogada' USING ERRCODE = '23514';
    END IF;
    IF NEW.mfa_step_up_at IS DISTINCT FROM OLD.mfa_step_up_at AND NEW.mfa_step_up_at > pg_catalog.clock_timestamp() THEN
        RAISE EXCEPTION 'step-up no futuro estenderia a janela' USING ERRCODE = '23514';
    END IF;
    IF NEW.recovery_cleared_at IS DISTINCT FROM OLD.recovery_cleared_at THEN
        IF OLD.recovery_cleared_at IS NOT NULL OR NEW.recovery_cleared_at IS NULL OR current_user <> 'nekkora_identity_owner' THEN
            RAISE EXCEPTION 'restricao de recuperacao so e levantada por identity_mfa_replace, uma vez' USING ERRCODE = '42501';
        END IF;
    END IF;
    -- 029: reconfirmacao da senha so pelo dono (identity_password_reconfirm), so avanca, nunca no futuro, so em sessao
    -- ativa sem MFA de identidade que nunca confirmou fator
    IF NEW.password_step_up_at IS DISTINCT FROM OLD.password_step_up_at THEN
        IF current_user <> 'nekkora_identity_owner' THEN
            RAISE EXCEPTION 'reconfirmacao da senha so por identity_password_reconfirm' USING ERRCODE = '42501';
        END IF;
        IF NEW.password_step_up_at IS NULL OR (OLD.password_step_up_at IS NOT NULL AND NEW.password_step_up_at < OLD.password_step_up_at)
           OR NEW.password_step_up_at > pg_catalog.clock_timestamp() OR NEW.revoked_at IS NOT NULL
           OR NEW.mfa_verified_at IS NOT NULL OR NOT public.identity_never_had_factor(NEW.identity_id) THEN
            RAISE EXCEPTION 'reconfirmacao da senha so avanca, no presente, em sessao ativa sem MFA de identidade sem fator'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION enforce_user_session_mfa_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF NEW.csrf_token IS NULL OR NEW.mfa_step_up_at IS NOT NULL OR NEW.revoked_at IS NOT NULL OR NEW.password_step_up_at IS NOT NULL THEN
        RAISE EXCEPTION 'sessao nova exige CSRF, sem step-up, sem reconfirmacao e sem revogacao'
            USING ERRCODE = '23514';
    END IF;
    -- 029: sem marcador de MFA SO na sessao vinculada, e o marcador acompanha o da sessao de identidade de origem (nulo
    -- com nulo, preenchido com preenchido). Leitura sob a RLS de quem insere: linha invisivel = recusa (falha fechada)
    IF NEW.identity_session_id IS NULL THEN
        IF NEW.mfa_verified_at IS NULL THEN
            RAISE EXCEPTION 'sessao nova exige marcador de MFA e CSRF, sem step-up nem revogacao'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NOT EXISTS (SELECT 1 FROM public.identity_sessions x
                       WHERE x.identity_session_id = NEW.identity_session_id
                         AND (x.mfa_verified_at IS NULL) = (NEW.mfa_verified_at IS NULL)) THEN
        RAISE EXCEPTION 'marcador de MFA da sessao vinculada diverge da sessao de identidade de origem' USING ERRCODE = '23514';
    END IF;
    -- R4: ramo da conta vinculada (identity_session_id preenchido): a organizacao FOR SHARE ANTES da conta, na ordem
    -- global organizacao -> conta. Serializa a emissao com a desativacao da conta, do vinculo e da organizacao (cabecalho,
    -- R4). Ramo 1 (identity_session_id nulo): nenhum lock novo.
    IF NEW.identity_session_id IS NOT NULL THEN
        -- R4b: a reconferencia depois dos locks so le o estado confirmado mais recente em READ COMMITTED
        IF pg_catalog.current_setting('transaction_isolation') <> 'read committed' THEN
            RAISE EXCEPTION 'sessao de organizacao vinculada exige transaction_isolation=read committed (atual=%)',
                pg_catalog.current_setting('transaction_isolation') USING ERRCODE = '25N01';
        END IF;
        PERFORM 1 FROM public.organizations o WHERE o.organization_id = NEW.organization_id FOR SHARE;
    END IF;
    PERFORM 1 FROM public.users u WHERE u.organization_id = NEW.organization_id AND u.user_id = NEW.user_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'sessao nova exige conta visivel no contexto' USING ERRCODE = '23514';
    END IF;
    IF NOT public.identity_org_session_allowed(NEW.organization_id, NEW.user_id, NEW.identity_session_id) THEN
        RAISE EXCEPTION 'sessao nova exige fator de MFA confirmado e ativo'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION prevent_user_session_reactivation() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at THEN
        RAISE EXCEPTION 'sessao revogada nao pode ser reabilitada nem ter a revogacao alterada' USING ERRCODE = '23514';
    END IF;
    IF NEW.session_id <> OLD.session_id OR NEW.organization_id <> OLD.organization_id OR NEW.user_id <> OLD.user_id
       OR NEW.session_token_hash <> OLD.session_token_hash OR NEW.created_at <> OLD.created_at
       OR NEW.mfa_verified_at IS DISTINCT FROM OLD.mfa_verified_at OR NEW.csrf_token IS DISTINCT FROM OLD.csrf_token
       OR NEW.identity_session_id IS DISTINCT FROM OLD.identity_session_id THEN
        RAISE EXCEPTION 'identidade, marcador de MFA e CSRF da sessao sao imutaveis' USING ERRCODE = '23514';
    END IF;
    IF NEW.expires_at > OLD.expires_at THEN
        RAISE EXCEPTION 'prazo absoluto da sessao nao pode ser estendido' USING ERRCODE = '23514';
    END IF;
    IF NEW.mfa_step_up_at IS DISTINCT FROM OLD.mfa_step_up_at
       AND (NEW.mfa_step_up_at IS NULL OR (OLD.mfa_step_up_at IS NOT NULL AND NEW.mfa_step_up_at < OLD.mfa_step_up_at)
            OR NEW.revoked_at IS NOT NULL) THEN
        RAISE EXCEPTION 'step-up so avanca, e so em sessao nao revogada' USING ERRCODE = '23514';
    END IF;
    -- 029: reconfirmacao da senha so pelo dono, so avanca, nunca no futuro, so em sessao ativa (a CHECK exige sem MFA e vinculada)
    IF NEW.password_step_up_at IS DISTINCT FROM OLD.password_step_up_at THEN
        IF current_user <> 'nekkora_identity_owner' THEN
            RAISE EXCEPTION 'reconfirmacao da senha so por identity_password_reconfirm' USING ERRCODE = '42501';
        END IF;
        IF NEW.password_step_up_at IS NULL OR (OLD.password_step_up_at IS NOT NULL AND NEW.password_step_up_at < OLD.password_step_up_at)
           OR NEW.password_step_up_at > pg_catalog.clock_timestamp() OR NEW.revoked_at IS NOT NULL THEN
            RAISE EXCEPTION 'reconfirmacao da senha so avanca, no presente, em sessao ativa' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- === 6. Funcoes redefinidas (mesmo dono e assinatura) ===

-- Resolve e TRAVA identidade -> sessao (017). 029: sessao sem MFA vale so enquanto a identidade nunca confirmou fator
-- (a ativacao revoga todas, mas a regra fica aqui tambem).
CREATE OR REPLACE FUNCTION public.identity_session_resolve(
    p_session_token TEXT,
    OUT o_session_id UUID, OUT o_identity_id UUID, OUT o_step_up_at TIMESTAMPTZ,
    OUT o_recovery_used_at TIMESTAMPTZ, OUT o_restrita BOOLEAN, OUT o_t TIMESTAMPTZ)
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_hash TEXT := public.identity_token_hash(p_session_token);
    v_identity UUID;
    s RECORD;
BEGIN
    IF v_hash IS NULL THEN
        RETURN;
    END IF;
    SELECT x.identity_id INTO v_identity FROM public.identity_sessions x WHERE x.session_token_hash = v_hash;
    IF v_identity IS NULL THEN
        RETURN;
    END IF;
    PERFORM 1 FROM public.identities i WHERE i.identity_id = v_identity FOR UPDATE;
    SELECT x.identity_session_id, x.identity_id, x.expires_at, x.revoked_at, x.mfa_verified_at, x.mfa_step_up_at,
           x.recovery_used_at, x.recovery_cleared_at
      INTO s
      FROM public.identity_sessions x
     WHERE x.session_token_hash = v_hash
       FOR UPDATE;
    o_t := pg_catalog.clock_timestamp();
    IF s.identity_session_id IS NULL OR s.revoked_at IS NOT NULL OR s.expires_at <= o_t
       OR (s.mfa_verified_at IS NULL AND NOT public.identity_never_had_factor(s.identity_id)) THEN
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.identities i WHERE i.identity_id = s.identity_id AND i.status = 2) THEN
        RETURN;
    END IF;
    o_session_id := s.identity_session_id;
    o_identity_id := s.identity_id;
    o_step_up_at := s.mfa_step_up_at;
    o_recovery_used_at := s.recovery_used_at;
    o_restrita := s.recovery_used_at IS NOT NULL AND s.recovery_cleared_at IS NULL;
END;
$$;

-- sessao de identidade pelo token do cookie (IdentityFilter). 029: a sessao sem MFA de identidade que ja confirmou fator
-- deixa de aparecer
CREATE OR REPLACE FUNCTION public.identity_session_lookup(p_token TEXT)
RETURNS TABLE (identity_session_id UUID, identity_id UUID)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT s.identity_session_id, s.identity_id FROM public.identity_sessions s
     WHERE s.session_token_hash = public.identity_token_hash(p_token)
       AND s.revoked_at IS NULL AND s.expires_at > pg_catalog.clock_timestamp()
       AND (s.mfa_verified_at IS NOT NULL OR public.identity_never_had_factor(s.identity_id))
$$;

-- R2 (017): prova reconferida no instante p_t, depois dos locks da chamadora. Modos:
--   'step_up'  - sessao com MFA nao restrita com step-up TOTP de ate 5 min (017, inalterado), OU (029) sessao sem MFA de
--                identidade que nunca confirmou fator, com reconfirmacao da senha de ate 5 min;
--   'troca'    - inalterado: so sessao com MFA (DC-17.13);
--   'ativacao' - 029: so o ramo da senha (sessao sem MFA, identidade sem fator, reconfirmacao de ate 5 min).
CREATE OR REPLACE FUNCTION public.identity_session_proof(p_session_id UUID, p_t TIMESTAMPTZ, p_modo TEXT) RETURNS BOOLEAN
LANGUAGE sql
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT coalesce((
        SELECT s.revoked_at IS NULL AND s.expires_at > p_t AND i.status = 2
           AND CASE
                 WHEN s.mfa_verified_at IS NOT NULL THEN
                     CASE p_modo
                       WHEN 'step_up' THEN (s.recovery_used_at IS NULL OR s.recovery_cleared_at IS NOT NULL)
                                           AND coalesce(s.mfa_step_up_at > p_t - interval '5 minutes', false)
                       WHEN 'troca' THEN public.identity_session_can_replace_factor(s.mfa_step_up_at, s.recovery_used_at,
                                           s.recovery_used_at IS NOT NULL AND s.recovery_cleared_at IS NULL, p_t)
                       ELSE false END
                 ELSE
                     p_modo IN ('step_up', 'ativacao')
                     AND public.identity_never_had_factor(s.identity_id)
                     AND coalesce(s.password_step_up_at > p_t - interval '5 minutes', false)
               END
          FROM public.identity_sessions s JOIN public.identities i ON i.identity_id = s.identity_id
         WHERE s.identity_session_id = p_session_id), false)
$$;

-- R5 (017): so indicacao para a interface. 029: tambem a sessao sem MFA de identidade que nunca confirmou fator
CREATE OR REPLACE FUNCTION public.identity_org_creation_allowed(p_session_token TEXT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT coalesce((
        SELECT (SELECT pg_catalog.count(*) FROM public.identity_users iu
                 WHERE iu.identity_id = s.identity_id AND iu.link_channel = 'cadastro_web') < 10
          FROM public.identity_sessions s JOIN public.identities i ON i.identity_id = s.identity_id
         WHERE s.session_token_hash = public.identity_token_hash(p_session_token)
           AND s.revoked_at IS NULL AND s.expires_at > pg_catalog.clock_timestamp()
           AND (s.mfa_verified_at IS NOT NULL OR public.identity_never_had_factor(s.identity_id))
           AND (s.recovery_used_at IS NULL OR s.recovery_cleared_at IS NOT NULL)
           AND i.status = 2 AND i.email_verified_at IS NOT NULL), false)
$$;

CREATE OR REPLACE FUNCTION public.identity_org_session_allowed(p_organization_id UUID, p_user_id UUID, p_identity_session_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    v_r BOOLEAN;
BEGIN
    BEGIN
        PERFORM public.identity_check_context(p_organization_id, p_user_id);
        IF NOT EXISTS (SELECT 1 FROM public.identity_users iu WHERE iu.user_id = p_user_id) THEN
            -- ramo 1: conta NAO vinculada - a regra da 014, intacta, e sem sessao de identidade
            v_r := p_identity_session_id IS NULL
               AND EXISTS (SELECT 1 FROM public.user_mfa_factors f
                            WHERE f.organization_id = p_organization_id AND f.user_id = p_user_id
                              AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL);
        ELSE
            -- ramo 2: conta vinculada - a sessao de identidade e a prova, desta identidade, ativa, NAO restrita, e:
            --   com MFA e com o fator da identidade confirmado e ativo (017), OU
            --   029: sem MFA, de identidade que nunca confirmou fator
            v_r := p_identity_session_id IS NOT NULL
               AND EXISTS (SELECT 1 FROM public.identity_users iu
                             JOIN public.identity_sessions s ON s.identity_id = iu.identity_id
                             JOIN public.identities i ON i.identity_id = iu.identity_id
                            WHERE iu.user_id = p_user_id
                              AND s.identity_session_id = p_identity_session_id
                              AND s.revoked_at IS NULL
                              AND s.expires_at > pg_catalog.clock_timestamp()
                              AND (s.recovery_used_at IS NULL OR s.recovery_cleared_at IS NOT NULL)
                              AND i.status = 2
                              AND CASE WHEN s.mfa_verified_at IS NOT NULL THEN
                                       EXISTS (SELECT 1 FROM public.identity_mfa_factors g
                                                WHERE g.identity_id = iu.identity_id AND g.confirmed_at IS NOT NULL AND g.disabled_at IS NULL)
                                   ELSE public.identity_never_had_factor(iu.identity_id) END)
               -- R4: elegibilidade da conta, da organizacao e do vinculo interno, lida DEPOIS dos locks da trigger
               -- (lock compartilhado da organizacao, depois lock exclusivo da conta); cada comando le o estado confirmado mais recente
               AND EXISTS (SELECT 1 FROM public.users u
                             JOIN public.organizations o ON o.organization_id = u.organization_id
                             JOIN public.organization_memberships m ON m.user_id = u.user_id AND m.organization_id = u.organization_id
                            WHERE u.user_id = p_user_id AND u.organization_id = p_organization_id
                              AND u.account_type = 1 AND u.status = 2 AND u.email_verified_at IS NOT NULL
                              AND o.status = 1 AND m.status = 2);
        END IF;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
    RETURN coalesce(v_r, false);
END;
$$;

-- Fator pendente (017). 029: pela SESSAO, alem da troca autorizada (DC-17.13, 'troca'), a sessao sem MFA de identidade
-- que nunca confirmou fator, com a senha reconfirmada ha ate 5 min ('ativacao'). Pelo desafio: inalterado.
CREATE OR REPLACE FUNCTION public.identity_mfa_enroll_pending(
    p_challenge_token TEXT, p_session_token TEXT, p_secret_ciphertext BYTEA, p_secret_nonce BYTEA, p_key_id TEXT)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_identity UUID;
    v_challenge UUID;
    s RECORD;
    v_t TIMESTAMPTZ;
    v_factor UUID;
BEGIN
    PERFORM public.identity_require_read_committed();
    IF (p_challenge_token IS NULL) = (p_session_token IS NULL) THEN
        RETURN NULL;
    END IF;
    -- locks: identidade -> desafio ou sessao (pelas funcoes de resolucao)
    IF p_challenge_token IS NOT NULL THEN
        SELECT r.o_challenge_id, r.o_identity_id INTO v_challenge, v_identity FROM public.identity_challenge_resolve(p_challenge_token) r;
    ELSE
        SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
        v_identity := s.o_identity_id;
    END IF;
    IF v_identity IS NULL THEN
        RETURN NULL;
    END IF;
    -- pelo desafio de cadastro, so quem nunca confirmou fator (depois disso, so troca autorizada)
    IF p_challenge_token IS NOT NULL
       AND EXISTS (SELECT 1 FROM public.identity_mfa_factors f WHERE f.identity_id = v_identity AND f.confirmed_at IS NOT NULL) THEN
        RETURN NULL;
    END IF;
    -- R2: trava o pendente anterior (que sera descartado) ANTES de medir a hora
    PERFORM 1 FROM public.identity_mfa_factors f
     WHERE f.identity_id = v_identity AND f.confirmed_at IS NULL AND f.disabled_at IS NULL FOR UPDATE;
    v_t := pg_catalog.clock_timestamp();
    IF p_challenge_token IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM public.identity_auth_challenges ch
                        WHERE ch.challenge_id = v_challenge AND ch.consumed_at IS NULL AND ch.invalidated_at IS NULL
                          AND ch.expires_at > v_t) THEN
            RETURN NULL;
        END IF;
    ELSIF NOT (public.identity_session_proof(s.o_session_id, v_t, 'troca')
               OR public.identity_session_proof(s.o_session_id, v_t, 'ativacao')) THEN
        RETURN NULL;
    END IF;
    UPDATE public.identity_mfa_factors SET disabled_at = greatest(v_t, created_at)
     WHERE identity_id = v_identity AND confirmed_at IS NULL AND disabled_at IS NULL;
    INSERT INTO public.identity_mfa_factors (identity_id, factor_type, secret_ciphertext, secret_nonce, key_id)
    VALUES (v_identity, 1, p_secret_ciphertext, p_secret_nonce, p_key_id)
    RETURNING factor_id INTO v_factor;
    RETURN v_factor;
END;
$$;

-- === 7. Funcoes novas ===

-- Login sem segundo fator: conclui o desafio de FINALIDADE 2 (emitido so para quem nao tem fator confirmado ativo) de
-- identidade que NUNCA confirmou fator. Locks: identidade -> desafio -> credencial -> fatores; depois a hora. Status:
-- SESSAO_CRIADA | DESAFIO_INVALIDO | SENHA_OBSOLETA | FATOR_EXIGIDO (ja teve fator: nada escrito, o desafio segue para o
-- cadastro do fator no login, como antes) | PARAMETRO_INVALIDO.
CREATE FUNCTION public.identity_login_password(p_challenge_token TEXT, p_sessao_hash TEXT, p_csrf TEXT, p_sessao_expira TIMESTAMPTZ)
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
    v_t TIMESTAMPTZ;
    v_n INTEGER;
    v_sessao UUID;
BEGIN
    PERFORM public.identity_require_read_committed();
    IF NOT public.identity_valid_session_params(p_sessao_hash, p_csrf, p_sessao_expira) THEN
        RETURN QUERY SELECT 'PARAMETRO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    IF v_hash IS NULL THEN
        RETURN QUERY SELECT 'DESAFIO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    SELECT ch.identity_id INTO v_identity FROM public.identity_auth_challenges ch WHERE ch.token_hash = v_hash;
    IF v_identity IS NULL THEN
        RETURN QUERY SELECT 'DESAFIO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    PERFORM 1 FROM public.identities i WHERE i.identity_id = v_identity FOR UPDATE;
    SELECT ch.challenge_id, ch.purpose, ch.credential_hash, ch.pending_password_hash, ch.expires_at, ch.consumed_at, ch.invalidated_at
      INTO c FROM public.identity_auth_challenges ch WHERE ch.token_hash = v_hash AND ch.identity_id = v_identity FOR UPDATE;
    SELECT x.password_hash INTO v_cred FROM public.identity_credentials x WHERE x.identity_id = v_identity FOR UPDATE;
    PERFORM 1 FROM public.identity_mfa_factors f WHERE f.identity_id = v_identity FOR UPDATE;
    v_t := pg_catalog.clock_timestamp();

    IF c.challenge_id IS NULL OR c.purpose <> 2 OR c.consumed_at IS NOT NULL OR c.invalidated_at IS NOT NULL OR c.expires_at <= v_t
       OR NOT EXISTS (SELECT 1 FROM public.identities i WHERE i.identity_id = v_identity AND i.status = 2 AND i.email_verified_at IS NOT NULL) THEN
        RETURN QUERY SELECT 'DESAFIO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    IF v_cred IS DISTINCT FROM c.credential_hash THEN
        UPDATE public.identity_auth_challenges SET invalidated_at = v_t, credential_hash = NULL, pending_password_hash = NULL
         WHERE challenge_id = c.challenge_id;
        RETURN QUERY SELECT 'SENHA_OBSOLETA'::TEXT, NULL::UUID; RETURN;
    END IF;
    IF NOT public.identity_never_had_factor(v_identity) THEN
        RETURN QUERY SELECT 'FATOR_EXIGIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    IF p_sessao_expira <= v_t THEN
        RETURN QUERY SELECT 'PARAMETRO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;

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
    INSERT INTO public.identity_sessions (identity_id, session_token_hash, expires_at, mfa_verified_at, csrf_token)
    VALUES (v_identity, p_sessao_hash, p_sessao_expira, NULL, p_csrf)
    RETURNING identity_session_id INTO v_sessao;
    INSERT INTO public.identity_events (identity_id, event_type, correlation_id) VALUES (v_identity, 18, pg_catalog.gen_random_uuid());
    RETURN QUERY SELECT 'SESSAO_CRIADA'::TEXT, v_sessao;
END;
$$;

-- Reconfirmacao da senha (substitui o step-up por TOTP SO para quem nunca confirmou fator). A aplicacao confere o Argon2
-- contra o hash que identity_resolve_login devolveu e passa esse hash: a funcao exige que ele ainda seja a credencial
-- vigente (senha trocada no intervalo: recusa). Sem organizacao: grava na sessao de identidade. Com organizacao: grava na
-- sessao de organizacao VINCULADA indicada, nascida desta sessao de identidade, ativa e sem MFA.
-- Locks: identidade -> sessao de identidade (identity_session_resolve) -> credencial -> [organizacao FOR SHARE -> conta
-- -> sessao de organizacao]; depois a hora. Recusa = false, sem efeito.
CREATE FUNCTION public.identity_password_reconfirm(p_session_token TEXT, p_expected_hash TEXT, p_organization_id UUID, p_org_session_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    s RECORD;
    x RECORD;
    v_cred TEXT;
    v_user UUID;
    o RECORD;
    v_t TIMESTAMPTZ;
    v_r BOOLEAN := false;
BEGIN
    PERFORM public.identity_require_read_committed();
    IF p_expected_hash IS NULL OR (p_organization_id IS NULL) <> (p_org_session_id IS NULL) THEN
        RETURN false;
    END IF;
    BEGIN
        SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
        IF s.o_identity_id IS NOT NULL AND NOT s.o_restrita THEN
            SELECT y.password_hash INTO v_cred FROM public.identity_credentials y WHERE y.identity_id = s.o_identity_id FOR UPDATE;
            IF p_organization_id IS NOT NULL THEN
                PERFORM pg_catalog.set_config('nekkora.fn_identity_id', '', true);
                PERFORM pg_catalog.set_config('nekkora.fn_organization_id', p_organization_id::text, true);
                SELECT u.user_id INTO v_user FROM public.user_sessions u
                 WHERE u.session_id = p_org_session_id AND u.organization_id = p_organization_id;
                PERFORM 1 FROM public.organizations g WHERE g.organization_id = p_organization_id FOR SHARE;
                PERFORM 1 FROM public.users u WHERE u.organization_id = p_organization_id AND u.user_id = v_user FOR UPDATE;
                SELECT u.session_id, u.identity_session_id, u.revoked_at, u.expires_at, u.mfa_verified_at INTO o
                  FROM public.user_sessions u
                 WHERE u.session_id = p_org_session_id AND u.organization_id = p_organization_id FOR UPDATE;
            END IF;
            v_t := pg_catalog.clock_timestamp();
            SELECT y.mfa_verified_at, y.revoked_at, y.expires_at INTO x FROM public.identity_sessions y
             WHERE y.identity_session_id = s.o_session_id;
            IF x.mfa_verified_at IS NULL AND x.revoked_at IS NULL AND x.expires_at > v_t
               AND public.identity_never_had_factor(s.o_identity_id)
               AND v_cred IS NOT NULL AND v_cred = p_expected_hash THEN
                IF p_organization_id IS NULL THEN
                    UPDATE public.identity_sessions SET password_step_up_at = v_t
                     WHERE identity_session_id = s.o_session_id AND revoked_at IS NULL;
                    v_r := FOUND;
                ELSIF o.session_id IS NOT NULL AND o.identity_session_id = s.o_session_id AND o.revoked_at IS NULL
                      AND o.expires_at > v_t AND o.mfa_verified_at IS NULL THEN
                    UPDATE public.user_sessions SET password_step_up_at = v_t
                     WHERE session_id = o.session_id AND organization_id = p_organization_id AND revoked_at IS NULL;
                    v_r := FOUND;
                END IF;
                IF v_r THEN
                    INSERT INTO public.identity_events (identity_id, event_type, target_organization_id, correlation_id)
                    VALUES (s.o_identity_id, 19, p_organization_id, pg_catalog.gen_random_uuid());
                END IF;
            END IF;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
    RETURN v_r;
END;
$$;

-- Ativacao voluntaria do primeiro fator pela sessao sem MFA. A aplicacao confere o TOTP do pendente (criado por
-- identity_mfa_enroll_pending com a prova 'ativacao') e passa o passo aceito. Exige a senha reconfirmada ha ate 15 min
-- nesta sessao (tempo de ler o QR). Locks: identidade -> sessao -> fator pendente -> demais sessoes ativas; depois a hora.
-- Efeitos, num so comando: fator confirmado (com o passo, anti-replay), codigos gravados, TODAS as sessoes da identidade
-- revogadas (inclusive esta: as de organizacao nascidas delas deixam de valer pela DC-17.4), sessao nova COM MFA, evento
-- 20. Status: SESSAO_CRIADA | SESSAO_INVALIDA | RECUSADO | PARAMETRO_INVALIDO.
CREATE FUNCTION public.identity_mfa_activate(
    p_session_token TEXT, p_factor_id UUID, p_code_hashes TEXT[], p_passo_totp BIGINT,
    p_sessao_hash TEXT, p_csrf TEXT, p_sessao_expira TIMESTAMPTZ)
RETURNS TABLE (o_status TEXT, o_identity_session_id UUID)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    s RECORD;
    x RECORD;
    v_t TIMESTAMPTZ;
    v_sessao UUID;
BEGIN
    PERFORM public.identity_require_read_committed();
    IF NOT public.identity_valid_code_hashes(p_code_hashes) OR p_factor_id IS NULL OR p_passo_totp IS NULL OR p_passo_totp < 0
       OR NOT public.identity_valid_session_params(p_sessao_hash, p_csrf, p_sessao_expira) THEN
        RETURN QUERY SELECT 'PARAMETRO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
    IF s.o_identity_id IS NULL OR s.o_restrita THEN
        RETURN QUERY SELECT 'SESSAO_INVALIDA'::TEXT, NULL::UUID; RETURN;
    END IF;
    PERFORM 1 FROM public.identity_mfa_factors f
     WHERE f.factor_id = p_factor_id AND f.identity_id = s.o_identity_id AND f.confirmed_at IS NULL AND f.disabled_at IS NULL
       FOR UPDATE;
    IF NOT FOUND THEN
        RETURN QUERY SELECT 'RECUSADO'::TEXT, NULL::UUID; RETURN;
    END IF;
    PERFORM 1 FROM public.identity_sessions y
     WHERE y.identity_id = s.o_identity_id AND y.identity_session_id <> s.o_session_id AND y.revoked_at IS NULL FOR UPDATE;
    v_t := pg_catalog.clock_timestamp();
    SELECT y.mfa_verified_at, y.password_step_up_at, y.revoked_at, y.expires_at INTO x FROM public.identity_sessions y
     WHERE y.identity_session_id = s.o_session_id;
    IF x.mfa_verified_at IS NOT NULL OR x.revoked_at IS NOT NULL OR x.expires_at <= v_t
       OR NOT public.identity_never_had_factor(s.o_identity_id)
       OR NOT coalesce(x.password_step_up_at > v_t - interval '15 minutes', false) THEN
        RETURN QUERY SELECT 'RECUSADO'::TEXT, NULL::UUID; RETURN;
    END IF;
    IF p_sessao_expira <= v_t THEN
        RETURN QUERY SELECT 'PARAMETRO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    UPDATE public.identity_mfa_factors SET confirmed_at = greatest(v_t, created_at), last_used_step = p_passo_totp
     WHERE factor_id = p_factor_id;
    INSERT INTO public.identity_mfa_recovery_codes (identity_id, factor_id, code_hash)
    SELECT s.o_identity_id, p_factor_id, h FROM pg_catalog.unnest(p_code_hashes) h;
    UPDATE public.identity_sessions SET revoked_at = greatest(v_t, created_at)
     WHERE identity_id = s.o_identity_id AND revoked_at IS NULL;
    INSERT INTO public.identity_sessions (identity_id, session_token_hash, expires_at, mfa_verified_at, csrf_token)
    VALUES (s.o_identity_id, p_sessao_hash, p_sessao_expira, v_t, p_csrf)
    RETURNING identity_session_id INTO v_sessao;
    INSERT INTO public.identity_events (identity_id, event_type, correlation_id) VALUES (s.o_identity_id, 20, pg_catalog.gen_random_uuid());
    RETURN QUERY SELECT 'SESSAO_CRIADA'::TEXT, v_sessao;
END;
$$;

-- === 8. Donos e privilegios ===
ALTER FUNCTION public.identity_never_had_factor(UUID) OWNER TO nekkora_identity_owner;
REVOKE ALL ON FUNCTION public.identity_never_had_factor(UUID) FROM PUBLIC;
ALTER FUNCTION public.identity_login_password(TEXT, TEXT, TEXT, TIMESTAMPTZ) OWNER TO nekkora_identity_owner;
REVOKE ALL ON FUNCTION public.identity_login_password(TEXT, TEXT, TEXT, TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.identity_login_password(TEXT, TEXT, TEXT, TIMESTAMPTZ) TO nekkora_platform;
ALTER FUNCTION public.identity_password_reconfirm(TEXT, TEXT, UUID, UUID) OWNER TO nekkora_identity_owner;
REVOKE ALL ON FUNCTION public.identity_password_reconfirm(TEXT, TEXT, UUID, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.identity_password_reconfirm(TEXT, TEXT, UUID, UUID) TO nekkora_platform;
ALTER FUNCTION public.identity_mfa_activate(TEXT, UUID, TEXT[], BIGINT, TEXT, TEXT, TIMESTAMPTZ) OWNER TO nekkora_identity_owner;
REVOKE ALL ON FUNCTION public.identity_mfa_activate(TEXT, UUID, TEXT[], BIGINT, TEXT, TEXT, TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.identity_mfa_activate(TEXT, UUID, TEXT[], BIGINT, TEXT, TEXT, TIMESTAMPTZ) TO nekkora_platform;

-- o dono escreve a reconfirmacao (e le o que a funcao confere); a Platform so le a marca
GRANT SELECT (password_step_up_at), UPDATE (password_step_up_at) ON public.identity_sessions TO nekkora_identity_owner;
GRANT SELECT (expires_at, mfa_verified_at, password_step_up_at), UPDATE (password_step_up_at) ON public.user_sessions TO nekkora_identity_owner;
GRANT SELECT (password_step_up_at) ON public.identity_sessions TO nekkora_platform;
GRANT SELECT (password_step_up_at) ON public.user_sessions TO nekkora_platform;

-- === 9. Invariantes ===
DO $$
DECLARE
    a RECORD;
    v_bad TEXT;
BEGIN
    SELECT * INTO a FROM n029_antes;
    IF a.fatores <> (SELECT count(*) FROM public.identity_mfa_factors WHERE confirmed_at IS NOT NULL AND disabled_at IS NULL)
       OR a.codigos <> (SELECT count(*) FROM public.identity_mfa_recovery_codes WHERE used_at IS NULL AND invalidated_at IS NULL)
       OR a.sessoes_identidade <> (SELECT count(*) FROM public.identity_sessions WHERE revoked_at IS NULL)
       OR a.sessoes_organizacao <> (SELECT count(*) FROM public.user_sessions WHERE revoked_at IS NULL)
       OR a.fatores_conta <> (SELECT count(*) FROM public.user_mfa_factors WHERE confirmed_at IS NOT NULL AND disabled_at IS NULL) THEN
        RAISE EXCEPTION '029 invariante 1: a migration alterou fatores, codigos ou sessoes';
    END IF;
    -- 2. nenhuma sessao ativa sem MFA de identidade que ja confirmou fator; nenhuma marca nova preenchida
    IF EXISTS (SELECT 1 FROM public.identity_sessions s WHERE s.revoked_at IS NULL AND s.mfa_verified_at IS NULL
                AND NOT public.identity_never_had_factor(s.identity_id))
       OR EXISTS (SELECT 1 FROM public.identity_sessions WHERE password_step_up_at IS NOT NULL)
       OR EXISTS (SELECT 1 FROM public.user_sessions WHERE password_step_up_at IS NOT NULL) THEN
        RAISE EXCEPTION '029 invariante 2: sessao sem MFA indevida ou marca de reconfirmacao preexistente';
    END IF;
    -- 3. funcoes novas: dono, SECURITY DEFINER com search_path fixo, sem EXECUTE publico; so a Platform executa as tres
    --    publicas; a auxiliar nao e executavel pela Platform
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname IN ('identity_login_password', 'identity_password_reconfirm', 'identity_mfa_activate')
       AND (pg_get_userbyid(p.proowner) <> 'nekkora_identity_owner' OR NOT p.prosecdef
            OR NOT coalesce(p.proconfig @> ARRAY['search_path=pg_catalog, pg_temp'], false)
            OR has_function_privilege('public', p.oid, 'EXECUTE') OR NOT has_function_privilege('nekkora_platform', p.oid, 'EXECUTE')
            OR has_function_privilege('nekkora_portal', p.oid, 'EXECUTE'));
    IF v_bad IS NOT NULL OR (SELECT count(*) FROM pg_proc WHERE proname IN ('identity_login_password', 'identity_password_reconfirm', 'identity_mfa_activate')) <> 3 THEN
        RAISE EXCEPTION '029 invariante 3: funcoes novas fora do padrao: %', v_bad;
    END IF;
    IF has_function_privilege('nekkora_platform', 'public.identity_never_had_factor(uuid)', 'EXECUTE')
       OR has_function_privilege('public', 'public.identity_never_had_factor(uuid)', 'EXECUTE')
       OR pg_get_userbyid((SELECT proowner FROM pg_proc WHERE oid = 'public.identity_never_had_factor(uuid)'::regprocedure)) <> 'nekkora_identity_owner' THEN
        RAISE EXCEPTION '029 invariante 3: auxiliar identity_never_had_factor fora do padrao';
    END IF;
    -- 4. so o dono escreve a reconfirmacao; portal sem acesso a coluna nova de user_sessions
    SELECT string_agg(format('%s:%s', r, t), ', ') INTO v_bad
      FROM unnest(ARRAY['nekkora_platform', 'nekkora_portal', 'nekkora_portal_owner', 'nekkora_ops_mfa_owner', 'nekkora_provisioning',
                        'nekkora_ingestion']) r, unnest(ARRAY['identity_sessions', 'user_sessions']) t
     WHERE to_regrole(r) IS NOT NULL AND has_column_privilege(r, 'public.' || t, 'password_step_up_at', 'UPDATE, INSERT')
       -- a Platform tem UPDATE de TABELA em user_sessions desde a 002 (logout, toque, step-up); para ela a escrita da coluna
       -- nova e recusada pela trigger prevent_user_session_reactivation (42501, so o dono), conferido na suite da 029
       AND NOT (r = 'nekkora_platform' AND t = 'user_sessions'
                AND has_table_privilege('nekkora_platform', 'public.user_sessions', 'UPDATE'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '029 invariante 4: escrita indevida da reconfirmacao: %', v_bad; END IF;
    IF has_column_privilege('nekkora_portal', 'public.user_sessions', 'password_step_up_at', 'SELECT, INSERT, UPDATE, REFERENCES')
       OR has_column_privilege('nekkora_portal_owner', 'public.user_sessions', 'password_step_up_at', 'SELECT, INSERT, UPDATE, REFERENCES') THEN
        RAISE EXCEPTION '029 invariante 4: portal com acesso a user_sessions.password_step_up_at';
    END IF;
    -- 5. triggers de sessao habilitadas
    IF (SELECT count(*) FROM pg_trigger t WHERE NOT t.tgisinternal AND t.tgenabled = 'O'
         AND (t.tgname, t.tgrelid) IN (('trg_identity_sessions_enforce_insert', 'public.identity_sessions'::regclass),
                                       ('trg_identity_sessions_prevent_change', 'public.identity_sessions'::regclass),
                                       ('trg_user_sessions_enforce_mfa', 'public.user_sessions'::regclass),
                                       ('trg_user_sessions_prevent_reactivation', 'public.user_sessions'::regclass))) <> 4 THEN
        RAISE EXCEPTION '029 invariante 5: triggers de sessao ausentes ou desabilitadas';
    END IF;
    -- 6. funcoes redefinidas continuam do dono
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad FROM pg_proc p
     WHERE p.oid IN ('public.identity_session_resolve(text)'::regprocedure, 'public.identity_session_lookup(text)'::regprocedure,
                     'public.identity_session_proof(uuid, timestamp with time zone, text)'::regprocedure,
                     'public.identity_org_creation_allowed(text)'::regprocedure,
                     'public.identity_org_session_allowed(uuid, uuid, uuid)'::regprocedure,
                     'public.identity_mfa_enroll_pending(text, text, bytea, bytea, text)'::regprocedure)
       AND pg_get_userbyid(p.proowner) <> 'nekkora_identity_owner';
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '029 invariante 6: funcao redefinida mudou de dono: %', v_bad; END IF;
END;
$$;

COMMIT;
