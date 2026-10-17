-- Nekkora Platform - MFA obrigatorio (TOTP) e endurecimento de user_sessions (decisoes 8.6/8.7/8.10,
-- registro docs/relatorios/2026-09-26-decisoes-8-6-8-7-8-10.md, revisao 4).
--
-- CANDIDATA ate a aprovacao do relatorio de validacao da suite db/tests/mfa-014.
--
-- O que muda:
--   * fator TOTP por conta (segredo cifrado pela aplicacao, AES-256-GCM), codigos de recuperacao (hash),
--     desafios de login (hash do token, credencial validada, hash candidato de re-hash, CSRF do desafio)
--     e auditoria de MFA append-only;
--   * permissao identity.mfa.reset, so para ORG_ADMIN;
--   * user_sessions: marcador mfa_verified_at, step-up e token CSRF da sessao; trigger de INSERT que
--     exige o marcador e um fator confirmado e ativo; trigger de UPDATE que impede reabilitar sessao
--     revogada, alterar a identidade da sessao ou estender o prazo absoluto;
--   * ATIVACAO: todas as sessoes ativas emitidas antes desta migration sao revogadas, na mesma transacao.
--
-- Precisao do contrato (revisao 4): a Platform verifica o segundo fator. O banco exige o marcador e um
-- fator ativo, mas NAO autentica a veracidade do marcador - um chamador com INSERT em user_sessions
-- poderia grava-lo sem validar codigo. A trigger e barreira estrutural, nao prova de MFA.
--
-- Ordem de implantacao: parar as instancias da Platform que criam sessao; aplicar esta migration;
-- subir a Platform com MFA. Uma Platform antiga que tente criar sessao depois disto falha fechada.
--
-- Papeis: so nekkora_platform recebe privilegio nas tabelas novas (sob a RLS tenant_request_context da
-- 012). nekkora_portal e nekkora_portal_owner nao recebem nada: a 013 nao muda e o owner do portal
-- nao ve as colunas novas de user_sessions (privilegios dele sao por coluna).
--
-- Depende de 001-013. Atomica: uma transacao.

BEGIN;

-- === 1. Permissao de reset administrativo de MFA (contrato, secao 3.4) ===

INSERT INTO permissions (code, description) VALUES
    ('identity.mfa.reset', 'Redefinir o segundo fator de OUTRA conta da organizacao (exige step-up recente)');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r, permissions p
WHERE r.code = 'ORG_ADMIN' AND p.code = 'identity.mfa.reset';

-- === 2. Fator TOTP ===
--
-- Um fator ativo (nao desabilitado) por conta: pendente (confirmed_at nulo, substituivel por novo
-- cadastro) ou confirmado. Segredo: 20 bytes cifrados com AES-256-GCM pela aplicacao (36 bytes com a
-- tag), nonce de 12 bytes, key_id da chave; AAD = organization_id || user_id || key_id (aplicacao).
-- Reset/perda: disabled_at (a linha fica para historico). Nunca DELETE.

CREATE TABLE user_mfa_factors (
    factor_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    UUID NOT NULL,
    user_id            UUID NOT NULL,
    factor_type        SMALLINT NOT NULL CHECK (factor_type = 1),
    secret_ciphertext  BYTEA NOT NULL CHECK (octet_length(secret_ciphertext) = 36),
    secret_nonce       BYTEA NOT NULL CHECK (octet_length(secret_nonce) = 12),
    key_id             TEXT NOT NULL CHECK (key_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at       TIMESTAMPTZ,
    disabled_at        TIMESTAMPTZ,
    last_used_step     BIGINT CHECK (last_used_step >= 0),
    UNIQUE (organization_id, user_id, factor_id),
    FOREIGN KEY (organization_id, user_id) REFERENCES users (organization_id, user_id) ON DELETE RESTRICT,
    CHECK (confirmed_at IS NULL OR confirmed_at >= created_at),
    CHECK (disabled_at IS NULL OR disabled_at >= created_at),
    CHECK (last_used_step IS NULL OR confirmed_at IS NOT NULL)
);

COMMENT ON COLUMN user_mfa_factors.factor_type IS '1=TOTP (RFC 6238, SHA-1, 6 digitos, 30 s)';
COMMENT ON COLUMN user_mfa_factors.last_used_step IS 'ultimo passo TOTP aceito - anti-replay: so aumenta';

CREATE UNIQUE INDEX ux_user_mfa_factors_um_ativo ON user_mfa_factors (user_id) WHERE disabled_at IS NULL;

CREATE FUNCTION prevent_mfa_factor_change() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF OLD.disabled_at IS NOT NULL THEN
        RAISE EXCEPTION 'fator de MFA desabilitado e imutavel (factor_id=%)', OLD.factor_id;
    END IF;
    IF NEW.factor_id <> OLD.factor_id OR NEW.organization_id <> OLD.organization_id OR NEW.user_id <> OLD.user_id
       OR NEW.factor_type <> OLD.factor_type OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'origem do fator de MFA e imutavel (factor_id=%)', OLD.factor_id;
    END IF;
    IF OLD.confirmed_at IS NOT NULL AND (
           NEW.confirmed_at IS DISTINCT FROM OLD.confirmed_at
        OR NEW.secret_ciphertext <> OLD.secret_ciphertext OR NEW.secret_nonce <> OLD.secret_nonce OR NEW.key_id <> OLD.key_id) THEN
        RAISE EXCEPTION 'segredo e confirmacao de fator confirmado sao imutaveis (factor_id=%)', OLD.factor_id;
    END IF;
    IF OLD.last_used_step IS NOT NULL AND (NEW.last_used_step IS NULL OR NEW.last_used_step <= OLD.last_used_step)
       AND NEW.last_used_step IS DISTINCT FROM OLD.last_used_step THEN
        RAISE EXCEPTION 'passo TOTP aceito so aumenta (factor_id=%)', OLD.factor_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_user_mfa_factors_prevent_change BEFORE UPDATE ON user_mfa_factors
    FOR EACH ROW EXECUTE FUNCTION prevent_mfa_factor_change();
CREATE TRIGGER trg_user_mfa_factors_prevent_delete BEFORE DELETE ON user_mfa_factors
    FOR EACH ROW EXECUTE FUNCTION prevent_delete();
CREATE TRIGGER trg_user_mfa_factors_prevent_truncate BEFORE TRUNCATE ON user_mfa_factors
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_truncate();

-- === 3. Codigos de recuperacao ===

CREATE TABLE user_mfa_recovery_codes (
    code_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL,
    user_id          UUID NOT NULL,
    factor_id        UUID NOT NULL,
    code_hash        TEXT NOT NULL CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    used_at          TIMESTAMPTZ,
    invalidated_at   TIMESTAMPTZ,
    UNIQUE (factor_id, code_hash),
    FOREIGN KEY (organization_id, user_id, factor_id)
        REFERENCES user_mfa_factors (organization_id, user_id, factor_id) ON DELETE RESTRICT,
    CHECK (used_at IS NULL OR invalidated_at IS NULL),
    CHECK (used_at IS NULL OR used_at >= created_at),
    CHECK (invalidated_at IS NULL OR invalidated_at >= created_at)
);

CREATE INDEX ix_user_mfa_recovery_codes_user_id ON user_mfa_recovery_codes (user_id) WHERE used_at IS NULL AND invalidated_at IS NULL;

CREATE FUNCTION prevent_mfa_recovery_code_change() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF OLD.used_at IS NOT NULL OR OLD.invalidated_at IS NOT NULL THEN
        RAISE EXCEPTION 'codigo de recuperacao usado ou invalidado e imutavel (code_id=%)', OLD.code_id;
    END IF;
    IF NEW.code_id <> OLD.code_id OR NEW.organization_id <> OLD.organization_id OR NEW.user_id <> OLD.user_id
       OR NEW.factor_id <> OLD.factor_id OR NEW.code_hash <> OLD.code_hash OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'origem do codigo de recuperacao e imutavel (code_id=%)', OLD.code_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_user_mfa_recovery_codes_prevent_change BEFORE UPDATE ON user_mfa_recovery_codes
    FOR EACH ROW EXECUTE FUNCTION prevent_mfa_recovery_code_change();
CREATE TRIGGER trg_user_mfa_recovery_codes_prevent_delete BEFORE DELETE ON user_mfa_recovery_codes
    FOR EACH ROW EXECUTE FUNCTION prevent_delete();
CREATE TRIGGER trg_user_mfa_recovery_codes_prevent_truncate BEFORE TRUNCATE ON user_mfa_recovery_codes
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_truncate();

-- === 4. Desafios de login (segundo passo) ===
--
-- token_hash: SHA-256 hex do token do cookie __Host-nekkora_mfa. credential_hash: o password_hash
-- EXATO validado no primeiro passo (gravado em todo desafio; a conclusao reconfere). pending_password_
-- hash: hash candidato de re-hash (opcional). Ambos sao apagados ao consumir ou invalidar o desafio.
-- attempts: codigos errados (no maximo 5). csrf_token: token sincronizador do contexto de desafio.

CREATE TABLE auth_challenges (
    challenge_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id        UUID NOT NULL,
    user_id                UUID NOT NULL,
    token_hash             TEXT NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    purpose                SMALLINT NOT NULL CHECK (purpose IN (1, 2)),
    credential_hash        TEXT CHECK (credential_hash IS NULL OR btrim(credential_hash) <> ''),
    pending_password_hash  TEXT CHECK (pending_password_hash IS NULL OR btrim(pending_password_hash) <> ''),
    csrf_token             TEXT NOT NULL CHECK (csrf_token ~ '^[A-Za-z0-9_-]{43}$'),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at             TIMESTAMPTZ NOT NULL,
    attempts               SMALLINT NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    consumed_at            TIMESTAMPTZ,
    invalidated_at         TIMESTAMPTZ,
    FOREIGN KEY (organization_id, user_id) REFERENCES users (organization_id, user_id) ON DELETE RESTRICT,
    CHECK (expires_at > created_at AND expires_at <= created_at + interval '5 minutes'),
    CHECK (consumed_at IS NULL OR invalidated_at IS NULL),
    -- ativo exige a credencial validada; terminal nao guarda hash algum
    CHECK ((consumed_at IS NULL AND invalidated_at IS NULL) = (credential_hash IS NOT NULL)),
    CHECK (consumed_at IS NULL AND invalidated_at IS NULL OR pending_password_hash IS NULL)
);

COMMENT ON COLUMN auth_challenges.purpose IS '1=MFA_VERIFY, 2=MFA_ENROLL';

CREATE INDEX ix_auth_challenges_user_ativos ON auth_challenges (user_id) WHERE consumed_at IS NULL AND invalidated_at IS NULL;

CREATE FUNCTION prevent_auth_challenge_change() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF OLD.consumed_at IS NOT NULL OR OLD.invalidated_at IS NOT NULL THEN
        RAISE EXCEPTION 'desafio consumido ou invalidado e imutavel (challenge_id=%)', OLD.challenge_id;
    END IF;
    IF NEW.challenge_id <> OLD.challenge_id OR NEW.organization_id <> OLD.organization_id OR NEW.user_id <> OLD.user_id
       OR NEW.token_hash <> OLD.token_hash OR NEW.purpose <> OLD.purpose OR NEW.csrf_token <> OLD.csrf_token
       OR NEW.created_at <> OLD.created_at OR NEW.expires_at <> OLD.expires_at THEN
        RAISE EXCEPTION 'origem do desafio e imutavel (challenge_id=%)', OLD.challenge_id;
    END IF;
    IF NEW.attempts < OLD.attempts THEN
        RAISE EXCEPTION 'tentativas do desafio so aumentam (challenge_id=%)', OLD.challenge_id;
    END IF;
    IF NEW.consumed_at IS NULL AND NEW.invalidated_at IS NULL
       AND (NEW.credential_hash IS DISTINCT FROM OLD.credential_hash OR NEW.pending_password_hash IS DISTINCT FROM OLD.pending_password_hash) THEN
        RAISE EXCEPTION 'hashes do desafio ativo sao imutaveis (challenge_id=%)', OLD.challenge_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_auth_challenges_prevent_change BEFORE UPDATE ON auth_challenges
    FOR EACH ROW EXECUTE FUNCTION prevent_auth_challenge_change();
CREATE TRIGGER trg_auth_challenges_prevent_delete BEFORE DELETE ON auth_challenges
    FOR EACH ROW EXECUTE FUNCTION prevent_delete();
CREATE TRIGGER trg_auth_challenges_prevent_truncate BEFORE TRUNCATE ON auth_challenges
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_truncate();

-- === 5. Auditoria de MFA (append-only) ===

CREATE TABLE mfa_events (
    event_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    user_id         UUID NOT NULL,
    actor_user_id   UUID,
    event_type      SMALLINT NOT NULL CHECK (event_type IN (1, 2, 3, 4, 5, 6)),
    correlation_id  UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, user_id) REFERENCES users (organization_id, user_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, actor_user_id) REFERENCES users (organization_id, user_id) ON DELETE RESTRICT,
    -- reset administrativo exige ator distinto do titular; os demais eventos sao do proprio titular ou da operacao
    CHECK (event_type <> 3 OR (actor_user_id IS NOT NULL AND actor_user_id <> user_id))
);

COMMENT ON COLUMN mfa_events.event_type IS '1=ENROLLED, 2=RECOVERY_CODE_USED, 3=RESET_BY_ADMIN, 4=RESET_BY_OPERATIONS, 5=CHALLENGE_LOCKED, 6=STEP_UP';

CREATE INDEX ix_mfa_events_org_user ON mfa_events (organization_id, user_id);

CREATE TRIGGER trg_mfa_events_prevent_update BEFORE UPDATE ON mfa_events
    FOR EACH ROW EXECUTE FUNCTION prevent_update();
CREATE TRIGGER trg_mfa_events_prevent_delete BEFORE DELETE ON mfa_events
    FOR EACH ROW EXECUTE FUNCTION prevent_delete();
CREATE TRIGGER trg_mfa_events_prevent_truncate BEFORE TRUNCATE ON mfa_events
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_truncate();

-- === 6. user_sessions: marcador de MFA, step-up e CSRF da sessao ===

ALTER TABLE user_sessions ADD COLUMN mfa_verified_at TIMESTAMPTZ;
ALTER TABLE user_sessions ADD COLUMN mfa_step_up_at  TIMESTAMPTZ;
ALTER TABLE user_sessions ADD COLUMN csrf_token      TEXT CHECK (csrf_token IS NULL OR csrf_token ~ '^[A-Za-z0-9_-]{43}$');
ALTER TABLE user_sessions ADD CONSTRAINT user_sessions_step_up_requires_mfa CHECK (mfa_step_up_at IS NULL OR mfa_verified_at IS NOT NULL);

COMMENT ON COLUMN user_sessions.mfa_verified_at IS 'marcador gravado pela Platform depois de verificar o segundo fator - o banco NAO autentica sua veracidade';
COMMENT ON COLUMN user_sessions.mfa_step_up_at IS 'ultimo step-up com TOTP nesta sessao (reset administrativo exige <= 5 min)';
COMMENT ON COLUMN user_sessions.csrf_token IS 'token sincronizador do contexto da sessao; nao e credencial de sessao';

-- ATIVACAO: nenhuma sessao pre-MFA sobrevive. Antes das triggers (o UPDATE de revogacao e permitido de
-- qualquer forma: nulo -> preenchido).
UPDATE user_sessions SET revoked_at = greatest(now(), created_at) WHERE revoked_at IS NULL;

CREATE FUNCTION enforce_user_session_mfa_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF NEW.mfa_verified_at IS NULL OR NEW.csrf_token IS NULL OR NEW.mfa_step_up_at IS NOT NULL OR NEW.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'sessao nova exige marcador de MFA e CSRF, sem step-up nem revogacao'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.user_mfa_factors f
                   WHERE f.organization_id = NEW.organization_id AND f.user_id = NEW.user_id
                     AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL) THEN
        RAISE EXCEPTION 'sessao nova exige fator de MFA confirmado e ativo'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION prevent_user_session_reactivation() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at THEN
        RAISE EXCEPTION 'sessao revogada nao pode ser reabilitada nem ter a revogacao alterada' USING ERRCODE = '23514';
    END IF;
    IF NEW.session_id <> OLD.session_id OR NEW.organization_id <> OLD.organization_id OR NEW.user_id <> OLD.user_id
       OR NEW.session_token_hash <> OLD.session_token_hash OR NEW.created_at <> OLD.created_at
       OR NEW.mfa_verified_at IS DISTINCT FROM OLD.mfa_verified_at OR NEW.csrf_token IS DISTINCT FROM OLD.csrf_token THEN
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
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_user_sessions_enforce_mfa BEFORE INSERT ON user_sessions
    FOR EACH ROW EXECUTE FUNCTION enforce_user_session_mfa_on_insert();
CREATE TRIGGER trg_user_sessions_prevent_reactivation BEFORE UPDATE ON user_sessions
    FOR EACH ROW EXECUTE FUNCTION prevent_user_session_reactivation();

-- funcoes de trigger: sem EXECUTE para PUBLIC (a 012 ja tira o padrao; explicito aqui)
REVOKE ALL ON FUNCTION prevent_mfa_factor_change() FROM PUBLIC;
REVOKE ALL ON FUNCTION prevent_mfa_recovery_code_change() FROM PUBLIC;
REVOKE ALL ON FUNCTION prevent_auth_challenge_change() FROM PUBLIC;
REVOKE ALL ON FUNCTION enforce_user_session_mfa_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION prevent_user_session_reactivation() FROM PUBLIC;

-- === 7. Privilegios e RLS (padrao da 012, secao 13) ===

GRANT SELECT, INSERT, UPDATE ON user_mfa_factors, user_mfa_recovery_codes, auth_challenges TO nekkora_platform;
GRANT SELECT, INSERT ON mfa_events TO nekkora_platform;

DO $$
DECLARE
    t TEXT;
    ctx_req CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.organization_id'', true), '''')::uuid';
BEGIN
    FOREACH t IN ARRAY ARRAY['user_mfa_factors', 'user_mfa_recovery_codes', 'auth_challenges', 'mfa_events'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_request_context ON public.%I AS PERMISSIVE FOR ALL TO nekkora_platform USING (%s) WITH CHECK (%s)',
                       t, ctx_req, ctx_req);
    END LOOP;
END;
$$;

-- === 8. Invariantes verificados ao final ===

DO $$
DECLARE
    v_bad TEXT;
    v_protegidos CONSTANT TEXT[] := ARRAY['nekkora_platform', 'nekkora_ingestion', 'nekkora_scheduler', 'nekkora_provisioning',
        'nekkora_retention_executor', 'nekkora_scheduler_owner', 'nekkora_provisioning_owner', 'nekkora_retention_owner',
        'nekkora_retention_tables_owner', 'nekkora_portal', 'nekkora_portal_owner'];
    v_novas CONSTANT TEXT[] := ARRAY['user_mfa_factors', 'user_mfa_recovery_codes', 'auth_challenges', 'mfa_events'];
BEGIN
    SELECT string_agg(rolname, ', ') INTO v_bad FROM pg_roles
    WHERE rolname = ANY (v_protegidos) AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel protegido com atributo privilegiado: %', v_bad; END IF;

    SELECT string_agg(format('%s -> %s', r.rolname, m.rolname), ', ') INTO v_bad
    FROM pg_auth_members am JOIN pg_roles r ON r.oid = am.roleid JOIN pg_roles m ON m.oid = am.member
    WHERE r.rolname = ANY (v_protegidos) OR m.rolname = ANY (v_protegidos);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'membership envolvendo papel protegido: %', v_bad; END IF;

    SELECT string_agg(p, ', ') INTO v_bad FROM unnest(v_protegidos) AS p
    WHERE has_schema_privilege(p, 'public', 'CREATE') OR has_database_privilege(p, current_database(), 'CREATE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel protegido com CREATE efetivo: %', v_bad; END IF;

    -- tabelas novas: RLS ENABLE + FORCE, exatamente uma politica (platform), sem privilegio do portal
    SELECT string_agg(c.relname, ', ') INTO v_bad
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relname = ANY (v_novas)
      AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity
           OR (SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname) <> 1);
    IF v_bad IS NOT NULL OR (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                             WHERE n.nspname = 'public' AND c.relname = ANY (v_novas)) <> 4 THEN
        RAISE EXCEPTION 'tabelas da 014 sem RLS/politica esperada: %', v_bad;
    END IF;
    SELECT string_agg(format('%s:%s', p, t), ', ') INTO v_bad
    FROM unnest(ARRAY['nekkora_portal', 'nekkora_portal_owner', 'nekkora_ingestion', 'nekkora_scheduler', 'nekkora_provisioning',
                      'nekkora_retention_executor']) AS p, unnest(v_novas) AS t
    WHERE has_table_privilege(p, 'public.' || t, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
       OR has_any_column_privilege(p, 'public.' || t, 'SELECT, INSERT, UPDATE, REFERENCES');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel indevido com privilegio nas tabelas da 014: %', v_bad; END IF;
    SELECT string_agg(t, ', ') INTO v_bad FROM unnest(v_novas) AS t
    WHERE has_table_privilege('nekkora_platform', 'public.' || t, 'DELETE, TRUNCATE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'nekkora_platform com DELETE/TRUNCATE nas tabelas da 014: %', v_bad; END IF;

    -- colunas novas de user_sessions: invisiveis aos papeis do portal
    SELECT string_agg(format('%s:%s', p, col), ', ') INTO v_bad
    FROM unnest(ARRAY['nekkora_portal', 'nekkora_portal_owner']) AS p, unnest(ARRAY['mfa_verified_at', 'mfa_step_up_at', 'csrf_token']) AS col
    WHERE has_column_privilege(p, 'public.user_sessions', col, 'SELECT, INSERT, UPDATE, REFERENCES');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel do portal com acesso a coluna nova de user_sessions: %', v_bad; END IF;

    -- ativacao: nenhuma sessao ativa sem marcador de MFA
    IF EXISTS (SELECT 1 FROM public.user_sessions WHERE revoked_at IS NULL AND mfa_verified_at IS NULL) THEN
        RAISE EXCEPTION 'sessao ativa sem marcador de MFA apos a ativacao';
    END IF;

    -- permissao de reset: exatamente ORG_ADMIN
    IF (SELECT array_agg(r.code ORDER BY r.code) FROM role_permissions rp JOIN roles r ON r.role_id = rp.role_id
        JOIN permissions p ON p.permission_id = rp.permission_id WHERE p.code = 'identity.mfa.reset') IS DISTINCT FROM ARRAY['ORG_ADMIN'] THEN
        RAISE EXCEPTION 'identity.mfa.reset deve pertencer exatamente a ORG_ADMIN';
    END IF;

    -- triggers de user_sessions presentes e habilitadas
    IF (SELECT count(*) FROM pg_trigger WHERE tgrelid = 'public.user_sessions'::regclass AND NOT tgisinternal AND tgenabled = 'O'
        AND tgname IN ('trg_user_sessions_enforce_mfa', 'trg_user_sessions_prevent_reactivation')) <> 2 THEN
        RAISE EXCEPTION 'triggers de user_sessions ausentes ou desabilitadas';
    END IF;

    -- nenhuma SECURITY DEFINER sem search_path ou com EXECUTE publico (inalterado desde a 012/013)
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.prosecdef
      AND (p.proconfig IS NULL OR NOT EXISTS (SELECT 1 FROM unnest(p.proconfig) c WHERE c LIKE 'search_path=%')
           OR has_function_privilege('public', p.oid, 'EXECUTE'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'SECURITY DEFINER sem search_path fixo ou com EXECUTE publico: %', v_bad; END IF;
END;
$$;

COMMIT;
