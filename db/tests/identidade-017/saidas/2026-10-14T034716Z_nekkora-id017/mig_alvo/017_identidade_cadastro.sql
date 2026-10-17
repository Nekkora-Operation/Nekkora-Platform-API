-- Nekkora Platform - camada de identidade (cadastro web e entrada geral, Missao 1).
-- Desenho: docs/relatorios/2026-10-11-cadastro-entrada-geral-desenho.md (secoes 3.3.1, 4, 5, DC-17.5, DC-17.9 a DC-17.13).
--
-- CANDIDATA ate a aprovacao do relatorio da suite db/tests/identidade-017.
--
-- O que muda:
--   * tabelas de identidade SEM organization_id (a contagem de tabelas de tenant, 43 desde a 014, nao muda);
--   * papel nekkora_identity_owner (NOLOGIN), dono das funcoes SECURITY DEFINER de identidade;
--   * user_sessions.identity_session_id e o contrato de dois ramos da trigger de INSERT (secao 4.4);
--   * fechamento da entrada local de conta vinculada: triggers de recusa que tomam o lock da conta ANTES de
--     conferir o vinculo (secao 4.5, camadas 2 e 3);
--   * fator de identidade: escrita de estado so pelas funcoes, imutabilidade para qualquer papel (secao 3.3.1);
--   * sessao de identidade restrita a recuperacao, recusada no banco (DC-17.13).
--
-- Contextos (padrao da 012/013):
--   nekkora.organization_id  - contexto da aplicacao; as auxiliares de leitura o CONFEREM (nunca escrevem).
--   nekkora.fn_organization_id, nekkora.fn_identity_id - definidos pelas funcoes do dono a partir do que elas mesmas
--       resolveram (sessao, desafio, organizacao recem-criada); salvos e restaurados pelas funcoes publicas.
--
-- Ordem de lock (secao 4.5 e grupo K): identidade -> sessao de identidade -> organizacao -> conta. Nenhum caminho
-- toma lock de identidade depois de lock de sessao, organizacao ou conta.
--
-- Mudanca de regra de 012 (registrada aqui, sem alterar a 012): a 012 declara provision_organization como "unico caminho
-- de criacao de organizacao". A partir desta migration ha um SEGUNDO caminho, identity_create_organization (DC-17.5),
-- que insere a organizacao diretamente e reaproveita provision_resource_limit e provision_first_org_admin da 012, cuja
-- trigger de bootstrap continua intacta.
--
-- R5 (2026-10-14, decisao da responsavel; contratos de step-up, criacao e vinculacao): identity_events aceita 13
-- (STEP_UP, step-up da identidade) e 14 (ORG_STEP_UP, step-up por organizacao em sessao vinculada); e a funcao de
-- consulta identity_org_creation_allowed, SO indicacao para a interface (canCreateOrganization): a criacao continua
-- reconferindo sessao, step-up, identidade e teto dentro de identity_create_organization. Nenhum privilegio de tabela,
-- trigger ou funcao existente muda.
--
-- R4 (2026-10-13, decisao da responsavel): a emissao da sessao de organizacao da conta VINCULADA (ramo 2 da secao 4.4)
-- exige, no banco e no instante do INSERT, a elegibilidade da conta (account_type 1, status ACTIVE, e-mail verificado), da
-- organizacao (status ACTIVE) e do vinculo interno (organization_memberships ACTIVE) - as mesmas condicoes que a resolucao
-- inicial (identity_org_session_target) le, mas que antes so ela lia. Ordenacao com a desativacao: a trigger de INSERT
-- trava a organizacao FOR SHARE ANTES da conta (ordem global organizacao -> conta). Quem desativa conta ou vinculo trava
-- a organizacao FOR UPDATE antes (disciplina da 003; AdminService.suspend) ou, no minimo, ao fim do comando (trigger de
-- presenca de ORG_ADMIN da 003); quem desativa a organizacao escreve a propria linha. Os dois lados se serializam na
-- organizacao: desativacao confirmada antes -> a reconferencia, feita depois dos locks, recusa; emissao confirmada antes ->
-- a desativacao espera e encontra a sessao ja criada. A linha do vinculo NAO e travada pela emissao (quem a desativa a
-- segura enquanto espera a organizacao; travar formaria ciclo). Ramo 1 (conta nao vinculada) inalterado: sem lock novo e
-- sem condicao nova.
-- R4b (2026-10-13, revisao da responsavel): em REPEATABLE READ ou SERIALIZABLE o lock NAO atualiza o snapshot - a
-- organizacao apenas travada (nao alterada) por quem desativou o vinculo nao provoca 40001, e a reconferencia leria o
-- vinculo antigo. Por isso a emissao vinculada EXIGE READ COMMITTED no proprio banco (25N01, o mesmo codigo de
-- identity_require_read_committed). Ramo 1 inalterado: aceito em qualquer isolamento, como antes.
--
-- R3 (2026-10-11): identity_login_complete (finalidade 1: consumo, credencial vigente, re-hash, fator ou codigo e sessao
-- num so comando), identity_mfa_confirm_first com os mesmos contratos (finalidade 2), identity_password_change (invalida
-- tambem os desafios pendentes). A sessao de identidade so nasce por funcao; a Platform perde o consumo direto do desafio e
-- o uso direto do codigo de recuperacao.
--
-- R2 (2026-10-11): RLS por identidade (alternativa A), funcoes de consulta por chave exata, identity_credentials sem
-- SELECT da aplicacao; provas reconferidas com a hora medida DEPOIS de todos os locks, e consumo como primeira escrita.
--
-- R1 (2026-10-11, revisao da responsavel): excecao de estado terminal estrita; imutabilidade de password_reset_tokens,
-- email_verification_tokens e user_credentials.user_id; desafio de cadastro reconferido depois do lock da identidade e
-- consumido por identity_mfa_confirm_first.
--
-- Elevacao: exige SUPERUSER na janela da versao (cria papel, transfere donos, concede EXECUTE em funcoes da 012 de
-- outro dono). Depende de 001-015; independente da 016. Atomica: uma transacao.

BEGIN;

-- === 1. Papel ===

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_identity_owner') THEN
        CREATE ROLE nekkora_identity_owner NOLOGIN;
    END IF;
END;
$$;

ALTER ROLE nekkora_identity_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA public TO nekkora_identity_owner;

-- === 2. Tabelas de identidade (nenhuma com organization_id) ===
--
-- Codigos: identities.status 1=PENDING, 2=ACTIVE, 3=SUSPENDED, 4=CLOSED; identity_users.link_channel
-- 'cadastro_web' (DC-17.5) | 'vinculacao' (DC-17.11); identity_auth_challenges.purpose 1=MFA_VERIFY, 2=MFA_ENROLL;
-- identity_email_tokens.purpose 1=CONFIRMACAO_CADASTRO (2 reservado).

CREATE TABLE identities (
    identity_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email             TEXT NOT NULL
        CHECK (btrim(email) <> '')
        CHECK (email = btrim(email))
        CHECK (email !~ '\s'),
    email_normalized  TEXT GENERATED ALWAYS AS (lower(email)) STORED,
    display_name      TEXT NOT NULL CHECK (btrim(display_name) <> ''),
    email_verified_at TIMESTAMPTZ,
    status            SMALLINT NOT NULL CHECK (status IN (1, 2, 3, 4)),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (email_normalized),
    CHECK (status <> 2 OR email_verified_at IS NOT NULL)
);

COMMENT ON COLUMN identities.status IS '1=PENDING, 2=ACTIVE, 3=SUSPENDED, 4=CLOSED';

CREATE TABLE identity_credentials (
    identity_id         UUID PRIMARY KEY REFERENCES identities (identity_id) ON DELETE RESTRICT,
    password_hash       TEXT NOT NULL CHECK (btrim(password_hash) <> ''),
    password_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE identity_users (
    identity_id  UUID NOT NULL REFERENCES identities (identity_id) ON DELETE RESTRICT,
    user_id      UUID NOT NULL REFERENCES users (user_id) ON DELETE RESTRICT,
    linked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    link_channel TEXT NOT NULL CHECK (link_channel IN ('cadastro_web', 'vinculacao')),
    PRIMARY KEY (identity_id, user_id),
    UNIQUE (user_id)
);

CREATE TABLE identity_mfa_factors (
    factor_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    identity_id        UUID NOT NULL REFERENCES identities (identity_id) ON DELETE RESTRICT,
    factor_type        SMALLINT NOT NULL CHECK (factor_type = 1),
    secret_ciphertext  BYTEA NOT NULL CHECK (octet_length(secret_ciphertext) = 36),
    secret_nonce       BYTEA NOT NULL CHECK (octet_length(secret_nonce) = 12),
    key_id             TEXT NOT NULL CHECK (key_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at       TIMESTAMPTZ,
    disabled_at        TIMESTAMPTZ,
    last_used_step     BIGINT CHECK (last_used_step >= 0),
    UNIQUE (identity_id, factor_id),
    CHECK (confirmed_at IS NULL OR confirmed_at >= created_at),
    CHECK (disabled_at IS NULL OR disabled_at >= created_at),
    CHECK (last_used_step IS NULL OR confirmed_at IS NOT NULL)
);

COMMENT ON COLUMN identity_mfa_factors.secret_ciphertext IS 'AES-256-GCM pela aplicacao; AAD = identity_id || key_id (DC-17.3, sem organizacao)';

-- DC-17.13 (correcao): no maximo um confirmado habilitado e no maximo um pendente habilitado
CREATE UNIQUE INDEX ux_identity_mfa_factors_confirmado ON identity_mfa_factors (identity_id)
    WHERE disabled_at IS NULL AND confirmed_at IS NOT NULL;
CREATE UNIQUE INDEX ux_identity_mfa_factors_pendente ON identity_mfa_factors (identity_id)
    WHERE disabled_at IS NULL AND confirmed_at IS NULL;

CREATE TABLE identity_auth_challenges (
    challenge_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    identity_id            UUID NOT NULL REFERENCES identities (identity_id) ON DELETE RESTRICT,
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
    CHECK (expires_at > created_at AND expires_at <= created_at + interval '5 minutes'),
    CHECK (consumed_at IS NULL OR invalidated_at IS NULL),
    CHECK ((consumed_at IS NULL AND invalidated_at IS NULL) = (credential_hash IS NOT NULL)),
    CHECK (consumed_at IS NULL AND invalidated_at IS NULL OR pending_password_hash IS NULL)
);

COMMENT ON COLUMN identity_auth_challenges.purpose IS '1=MFA_VERIFY, 2=MFA_ENROLL';

CREATE INDEX ix_identity_auth_challenges_ativos ON identity_auth_challenges (identity_id)
    WHERE consumed_at IS NULL AND invalidated_at IS NULL;

CREATE TABLE identity_sessions (
    identity_session_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    identity_id         UUID NOT NULL REFERENCES identities (identity_id) ON DELETE RESTRICT,
    session_token_hash  TEXT NOT NULL UNIQUE CHECK (session_token_hash ~ '^[0-9a-f]{64}$'),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at          TIMESTAMPTZ,
    mfa_verified_at     TIMESTAMPTZ NOT NULL,
    mfa_step_up_at      TIMESTAMPTZ,
    recovery_used_at    TIMESTAMPTZ,
    recovery_cleared_at TIMESTAMPTZ,
    csrf_token          TEXT NOT NULL CHECK (csrf_token ~ '^[A-Za-z0-9_-]{43}$'),
    CHECK (expires_at > created_at AND expires_at <= created_at + interval '12 hours'),
    CHECK (last_seen_at >= created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CHECK (recovery_cleared_at IS NULL OR (recovery_used_at IS NOT NULL AND recovery_cleared_at >= recovery_used_at))
);

COMMENT ON COLUMN identity_sessions.recovery_used_at IS 'sessao nascida de senha + codigo de recuperacao (DC-17.13): restrita enquanto recovery_cleared_at for nulo';
COMMENT ON COLUMN identity_sessions.recovery_cleared_at IS 'T1: so identity_mfa_replace escreve';

CREATE INDEX ix_identity_sessions_identity ON identity_sessions (identity_id) WHERE revoked_at IS NULL;

CREATE TABLE identity_mfa_recovery_codes (
    code_id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    identity_id                 UUID NOT NULL,
    factor_id                   UUID NOT NULL,
    code_hash                   TEXT NOT NULL CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    used_at                     TIMESTAMPTZ,
    used_in_identity_session_id UUID REFERENCES identity_sessions (identity_session_id) ON DELETE RESTRICT,
    invalidated_at              TIMESTAMPTZ,
    UNIQUE (factor_id, code_hash),
    FOREIGN KEY (identity_id, factor_id) REFERENCES identity_mfa_factors (identity_id, factor_id) ON DELETE RESTRICT,
    CHECK ((used_at IS NULL) = (used_in_identity_session_id IS NULL)),
    CHECK (used_at IS NULL OR invalidated_at IS NULL),
    CHECK (used_at IS NULL OR used_at >= created_at),
    CHECK (invalidated_at IS NULL OR invalidated_at >= created_at)
);

CREATE INDEX ix_identity_mfa_recovery_codes_vivos ON identity_mfa_recovery_codes (identity_id)
    WHERE used_at IS NULL AND invalidated_at IS NULL;

CREATE TABLE identity_link_challenges (
    challenge_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    identity_id             UUID NOT NULL REFERENCES identities (identity_id) ON DELETE RESTRICT,
    user_id                 UUID NOT NULL REFERENCES users (user_id) ON DELETE RESTRICT,
    token_hash              TEXT NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    account_credential_hash TEXT CHECK (account_credential_hash IS NULL OR btrim(account_credential_hash) <> ''),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at              TIMESTAMPTZ NOT NULL,
    attempts                SMALLINT NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    consumed_at             TIMESTAMPTZ,
    revoked_at              TIMESTAMPTZ,
    CHECK (expires_at > created_at AND expires_at <= created_at + interval '5 minutes'),
    CHECK (consumed_at IS NULL OR revoked_at IS NULL),
    CHECK ((consumed_at IS NULL AND revoked_at IS NULL) = (account_credential_hash IS NOT NULL))
);

CREATE TABLE identity_email_tokens (
    token_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    purpose      SMALLINT NOT NULL CHECK (purpose = 1),
    identity_id  UUID NOT NULL REFERENCES identities (identity_id) ON DELETE RESTRICT,
    target_email TEXT NOT NULL CHECK (btrim(target_email) <> '') CHECK (target_email = btrim(target_email)) CHECK (target_email !~ '\s'),
    token_hash   TEXT NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    CHECK (expires_at > created_at AND expires_at <= created_at + interval '24 hours'),
    CHECK (consumed_at IS NULL OR revoked_at IS NULL),
    CHECK (consumed_at IS NULL OR consumed_at >= created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

COMMENT ON COLUMN identity_email_tokens.purpose IS '1=CONFIRMACAO_CADASTRO (2 reservado para recuperacao de senha)';

-- target_organization_id, e nao organization_id: a tabela nao e de tenant (secao 4.3, invariante 1)
CREATE TABLE identity_events (
    event_id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    identity_id            UUID NOT NULL REFERENCES identities (identity_id) ON DELETE RESTRICT,
    event_type             SMALLINT NOT NULL CHECK (event_type BETWEEN 1 AND 14),
    target_organization_id UUID REFERENCES organizations (organization_id) ON DELETE RESTRICT,
    correlation_id         UUID NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN identity_events.event_type IS '1=SIGNUP_REQUESTED, 2=SIGNUP_CONFIRMED, 3=MFA_ENROLLED, 4=MFA_VERIFIED, 5=RECOVERY_CODE_USED, '
    '6=ORG_CREATED, 7=ORG_SESSION_ISSUED, 8=ORG_SESSION_DENIED, 9=IDENTITY_LINKED, 10=LOGOUT, 11=MFA_REPLACED, 12=PASSWORD_CHANGED, '
    '13=STEP_UP, 14=ORG_STEP_UP';

CREATE INDEX ix_identity_events_identity ON identity_events (identity_id);

-- === 3. Imutabilidade das tabelas de identidade ===

CREATE TRIGGER trg_identities_set_updated_at BEFORE UPDATE ON identities FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_identity_credentials_set_updated_at BEFORE UPDATE ON identity_credentials FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE FUNCTION prevent_identity_change() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    -- e-mail imutavel: a igualdade de e-mail da vinculacao (DC-17.11, guarda 1) nao pode ser fabricada por troca
    IF NEW.identity_id <> OLD.identity_id OR NEW.email <> OLD.email OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'identidade e e-mail da identidade sao imutaveis' USING ERRCODE = '23514';
    END IF;
    IF OLD.email_verified_at IS NOT NULL AND NEW.email_verified_at IS DISTINCT FROM OLD.email_verified_at THEN
        RAISE EXCEPTION 'verificacao de e-mail da identidade e imutavel' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_identities_prevent_change BEFORE UPDATE ON identities FOR EACH ROW EXECUTE FUNCTION prevent_identity_change();

CREATE FUNCTION prevent_identity_credential_change() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF NEW.identity_id <> OLD.identity_id OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'origem da credencial de identidade e imutavel' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_identity_credentials_prevent_change BEFORE UPDATE ON identity_credentials
    FOR EACH ROW EXECUTE FUNCTION prevent_identity_credential_change();

-- identity_users: append-only (desvincular nao existe nesta missao, DC-17.12)
CREATE TRIGGER trg_identity_users_prevent_update BEFORE UPDATE ON identity_users FOR EACH ROW EXECUTE FUNCTION prevent_update();
CREATE TRIGGER trg_identity_users_prevent_delete BEFORE DELETE ON identity_users FOR EACH ROW EXECUTE FUNCTION prevent_delete();
CREATE TRIGGER trg_identity_users_prevent_truncate BEFORE TRUNCATE ON identity_users FOR EACH STATEMENT EXECUTE FUNCTION prevent_truncate();

-- identity_events: append-only
CREATE TRIGGER trg_identity_events_prevent_update BEFORE UPDATE ON identity_events FOR EACH ROW EXECUTE FUNCTION prevent_update();
CREATE TRIGGER trg_identity_events_prevent_delete BEFORE DELETE ON identity_events FOR EACH ROW EXECUTE FUNCTION prevent_delete();
CREATE TRIGGER trg_identity_events_prevent_truncate BEFORE TRUNCATE ON identity_events FOR EACH STATEMENT EXECUTE FUNCTION prevent_truncate();

-- Fator de identidade (secao 3.3.1, "Imutabilidade do fator"): vale para QUALQUER papel, inclusive o dono das funcoes.
-- current_user, nao GUC: dentro de SECURITY DEFINER e o dono; nenhum papel e membro dele (invariante 9).
CREATE FUNCTION protect_identity_mfa_factor() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'fator de identidade nao e apagado (factor_id=%)', OLD.factor_id USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF current_user <> 'nekkora_identity_owner' THEN
            RAISE EXCEPTION 'fator de identidade so nasce por identity_mfa_enroll_pending' USING ERRCODE = '42501';
        END IF;
        IF NEW.confirmed_at IS NOT NULL OR NEW.disabled_at IS NOT NULL OR NEW.last_used_step IS NOT NULL THEN
            RAISE EXCEPTION 'fator de identidade nasce pendente e habilitado' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.factor_id <> OLD.factor_id OR NEW.identity_id <> OLD.identity_id OR NEW.factor_type <> OLD.factor_type
       OR NEW.secret_ciphertext <> OLD.secret_ciphertext OR NEW.secret_nonce <> OLD.secret_nonce
       OR NEW.key_id <> OLD.key_id OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'campos protegidos do fator de identidade sao imutaveis (factor_id=%)', OLD.factor_id USING ERRCODE = '23514';
    END IF;
    IF NEW.confirmed_at IS DISTINCT FROM OLD.confirmed_at THEN
        IF OLD.confirmed_at IS NOT NULL OR NEW.confirmed_at IS NULL THEN
            RAISE EXCEPTION 'confirmacao do fator de identidade e irreversivel (factor_id=%)', OLD.factor_id USING ERRCODE = '23514';
        END IF;
        IF OLD.disabled_at IS NOT NULL THEN
            RAISE EXCEPTION 'fator de identidade desabilitado nao e confirmado (factor_id=%)', OLD.factor_id USING ERRCODE = '23514';
        END IF;
        IF current_user <> 'nekkora_identity_owner' THEN
            RAISE EXCEPTION 'confirmacao de fator de identidade so pelas funcoes autorizadas' USING ERRCODE = '42501';
        END IF;
    END IF;
    IF NEW.disabled_at IS DISTINCT FROM OLD.disabled_at THEN
        IF OLD.disabled_at IS NOT NULL OR NEW.disabled_at IS NULL THEN
            RAISE EXCEPTION 'fator de identidade desabilitado nao volta (factor_id=%)', OLD.factor_id USING ERRCODE = '23514';
        END IF;
        IF current_user <> 'nekkora_identity_owner' THEN
            RAISE EXCEPTION 'desabilitar fator de identidade so pelas funcoes autorizadas' USING ERRCODE = '42501';
        END IF;
    END IF;
    IF NEW.last_used_step IS DISTINCT FROM OLD.last_used_step
       AND (NEW.last_used_step IS NULL OR (OLD.last_used_step IS NOT NULL AND NEW.last_used_step <= OLD.last_used_step)) THEN
        RAISE EXCEPTION 'passo TOTP aceito so aumenta (factor_id=%)', OLD.factor_id USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_identity_mfa_factors_protect BEFORE INSERT OR UPDATE OR DELETE ON identity_mfa_factors
    FOR EACH ROW EXECUTE FUNCTION protect_identity_mfa_factor();
CREATE TRIGGER trg_identity_mfa_factors_prevent_truncate BEFORE TRUNCATE ON identity_mfa_factors
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_truncate();

CREATE FUNCTION protect_identity_recovery_code() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'codigo de recuperacao de identidade nao e apagado' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF current_user <> 'nekkora_identity_owner' THEN
            RAISE EXCEPTION 'codigo de recuperacao de identidade so nasce pelas funcoes de fator' USING ERRCODE = '42501';
        END IF;
        IF NEW.used_at IS NOT NULL OR NEW.invalidated_at IS NOT NULL THEN
            RAISE EXCEPTION 'codigo de recuperacao nasce vivo' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.used_at IS NOT NULL OR OLD.invalidated_at IS NOT NULL THEN
        RAISE EXCEPTION 'codigo de recuperacao usado ou invalidado e imutavel (code_id=%)', OLD.code_id USING ERRCODE = '23514';
    END IF;
    IF NEW.code_id <> OLD.code_id OR NEW.identity_id <> OLD.identity_id OR NEW.factor_id <> OLD.factor_id
       OR NEW.code_hash <> OLD.code_hash OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'origem do codigo de recuperacao e imutavel (code_id=%)', OLD.code_id USING ERRCODE = '23514';
    END IF;
    IF NEW.invalidated_at IS NOT NULL AND current_user <> 'nekkora_identity_owner' THEN
        RAISE EXCEPTION 'invalidar codigo de recuperacao de identidade so pelas funcoes de fator' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_identity_mfa_recovery_codes_protect BEFORE INSERT OR UPDATE OR DELETE ON identity_mfa_recovery_codes
    FOR EACH ROW EXECUTE FUNCTION protect_identity_recovery_code();
CREATE TRIGGER trg_identity_mfa_recovery_codes_prevent_truncate BEFORE TRUNCATE ON identity_mfa_recovery_codes
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_truncate();

-- DC-17.13: consumir codigo exige que a sessao apontada tenha nascido restrita (conferido no commit)
CREATE FUNCTION check_identity_recovery_code_session() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.identity_sessions s
                   WHERE s.identity_session_id = NEW.used_in_identity_session_id
                     AND s.identity_id = NEW.identity_id
                     AND s.recovery_used_at IS NOT NULL) THEN
        RAISE EXCEPTION 'codigo de recuperacao consumido sem sessao restrita da mesma identidade' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_identity_mfa_recovery_codes_sessao_restrita
    AFTER UPDATE ON identity_mfa_recovery_codes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.used_at IS NOT NULL AND OLD.used_at IS NULL)
    EXECUTE FUNCTION check_identity_recovery_code_session();

CREATE FUNCTION prevent_identity_auth_challenge_change() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF OLD.consumed_at IS NOT NULL OR OLD.invalidated_at IS NOT NULL THEN
        RAISE EXCEPTION 'desafio de identidade consumido ou invalidado e imutavel' USING ERRCODE = '23514';
    END IF;
    IF NEW.challenge_id <> OLD.challenge_id OR NEW.identity_id <> OLD.identity_id OR NEW.token_hash <> OLD.token_hash
       OR NEW.purpose <> OLD.purpose OR NEW.csrf_token <> OLD.csrf_token OR NEW.created_at <> OLD.created_at
       OR NEW.expires_at <> OLD.expires_at THEN
        RAISE EXCEPTION 'origem do desafio de identidade e imutavel' USING ERRCODE = '23514';
    END IF;
    IF NEW.attempts < OLD.attempts THEN
        RAISE EXCEPTION 'tentativas do desafio so aumentam' USING ERRCODE = '23514';
    END IF;
    IF NEW.consumed_at IS NULL AND NEW.invalidated_at IS NULL
       AND (NEW.credential_hash IS DISTINCT FROM OLD.credential_hash OR NEW.pending_password_hash IS DISTINCT FROM OLD.pending_password_hash) THEN
        RAISE EXCEPTION 'hashes do desafio ativo sao imutaveis' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_identity_auth_challenges_prevent_change BEFORE UPDATE ON identity_auth_challenges
    FOR EACH ROW EXECUTE FUNCTION prevent_identity_auth_challenge_change();
CREATE TRIGGER trg_identity_auth_challenges_prevent_delete BEFORE DELETE ON identity_auth_challenges FOR EACH ROW EXECUTE FUNCTION prevent_delete();
CREATE TRIGGER trg_identity_auth_challenges_prevent_truncate BEFORE TRUNCATE ON identity_auth_challenges FOR EACH STATEMENT EXECUTE FUNCTION prevent_truncate();

CREATE FUNCTION prevent_identity_link_challenge_change() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF OLD.consumed_at IS NOT NULL OR OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'desafio de vinculacao consumido ou revogado e imutavel' USING ERRCODE = '23514';
    END IF;
    IF NEW.challenge_id <> OLD.challenge_id OR NEW.identity_id <> OLD.identity_id OR NEW.user_id <> OLD.user_id
       OR NEW.token_hash <> OLD.token_hash OR NEW.created_at <> OLD.created_at OR NEW.expires_at <> OLD.expires_at THEN
        RAISE EXCEPTION 'origem do desafio de vinculacao e imutavel (par exato)' USING ERRCODE = '23514';
    END IF;
    IF NEW.attempts < OLD.attempts THEN
        RAISE EXCEPTION 'tentativas do desafio de vinculacao so aumentam' USING ERRCODE = '23514';
    END IF;
    IF NEW.consumed_at IS NULL AND NEW.revoked_at IS NULL AND NEW.account_credential_hash IS DISTINCT FROM OLD.account_credential_hash THEN
        RAISE EXCEPTION 'credencial do desafio de vinculacao ativo e imutavel' USING ERRCODE = '23514';
    END IF;
    IF NEW.consumed_at IS NOT NULL AND current_user <> 'nekkora_identity_owner' THEN
        RAISE EXCEPTION 'desafio de vinculacao so e consumido por identity_link_account' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_identity_link_challenges_prevent_change BEFORE UPDATE ON identity_link_challenges
    FOR EACH ROW EXECUTE FUNCTION prevent_identity_link_challenge_change();
CREATE TRIGGER trg_identity_link_challenges_prevent_delete BEFORE DELETE ON identity_link_challenges FOR EACH ROW EXECUTE FUNCTION prevent_delete();
CREATE TRIGGER trg_identity_link_challenges_prevent_truncate BEFORE TRUNCATE ON identity_link_challenges FOR EACH STATEMENT EXECUTE FUNCTION prevent_truncate();

CREATE FUNCTION prevent_identity_email_token_change() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF OLD.consumed_at IS NOT NULL OR OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'token de e-mail consumido ou revogado e imutavel' USING ERRCODE = '23514';
    END IF;
    IF NEW.token_id <> OLD.token_id OR NEW.purpose <> OLD.purpose OR NEW.identity_id <> OLD.identity_id
       OR NEW.target_email <> OLD.target_email OR NEW.token_hash <> OLD.token_hash
       OR NEW.created_at <> OLD.created_at OR NEW.expires_at <> OLD.expires_at THEN
        RAISE EXCEPTION 'origem do token de e-mail e imutavel' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_identity_email_tokens_prevent_change BEFORE UPDATE ON identity_email_tokens
    FOR EACH ROW EXECUTE FUNCTION prevent_identity_email_token_change();
CREATE TRIGGER trg_identity_email_tokens_prevent_delete BEFORE DELETE ON identity_email_tokens FOR EACH ROW EXECUTE FUNCTION prevent_delete();
CREATE TRIGGER trg_identity_email_tokens_prevent_truncate BEFORE TRUNCATE ON identity_email_tokens FOR EACH STATEMENT EXECUTE FUNCTION prevent_truncate();

-- Sessao de identidade: so existe apos MFA; sem reativacao; prazo nao extensivel; marcas de recuperacao (DC-17.13)
CREATE FUNCTION enforce_identity_session_insert() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF NEW.revoked_at IS NOT NULL OR NEW.mfa_step_up_at IS NOT NULL OR NEW.recovery_cleared_at IS NOT NULL THEN
        RAISE EXCEPTION 'sessao de identidade nova nasce sem revogacao, sem step-up e sem levantamento de restricao'
            USING ERRCODE = '23514';
    END IF;
    -- marcas que abrem janela (DC-17.13) nunca no futuro: estenderiam a janela; retroativas so a encurtam
    IF NEW.recovery_used_at > pg_catalog.clock_timestamp() OR NEW.mfa_verified_at > pg_catalog.clock_timestamp() THEN
        RAISE EXCEPTION 'marca de recuperacao ou de MFA no futuro' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION prevent_identity_session_change() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at THEN
        RAISE EXCEPTION 'sessao de identidade revogada nao pode ser reabilitada' USING ERRCODE = '23514';
    END IF;
    IF NEW.identity_session_id <> OLD.identity_session_id OR NEW.identity_id <> OLD.identity_id
       OR NEW.session_token_hash <> OLD.session_token_hash OR NEW.created_at <> OLD.created_at
       OR NEW.mfa_verified_at <> OLD.mfa_verified_at OR NEW.csrf_token <> OLD.csrf_token
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
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_identity_sessions_enforce_insert BEFORE INSERT ON identity_sessions FOR EACH ROW EXECUTE FUNCTION enforce_identity_session_insert();
CREATE TRIGGER trg_identity_sessions_prevent_change BEFORE UPDATE ON identity_sessions FOR EACH ROW EXECUTE FUNCTION prevent_identity_session_change();
CREATE TRIGGER trg_identity_sessions_prevent_delete BEFORE DELETE ON identity_sessions FOR EACH ROW EXECUTE FUNCTION prevent_delete();
CREATE TRIGGER trg_identity_sessions_prevent_truncate BEFORE TRUNCATE ON identity_sessions FOR EACH STATEMENT EXECUTE FUNCTION prevent_truncate();

-- === 4. user_sessions: sessao de identidade de origem ===

ALTER TABLE user_sessions ADD COLUMN identity_session_id UUID REFERENCES identity_sessions (identity_session_id) ON DELETE RESTRICT;
COMMENT ON COLUMN user_sessions.identity_session_id IS 'sessao de identidade que originou esta sessao (troca, secao 6.5); nula no login por organizacao. Imutavel.';
CREATE INDEX ix_user_sessions_identity_session ON user_sessions (identity_session_id) WHERE identity_session_id IS NOT NULL;

-- identity_session_id passa a ser imutavel; o resto do corpo e o da 014, inalterado
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
    RETURN NEW;
END;
$$;

-- === 5. Privilegios e RLS do dono das funcoes de identidade ===
--
-- Por coluna, no padrao da 013/016. O UPDATE (updated_at) em organizations, users e identities existe porque
-- SELECT ... FOR UPDATE exige privilegio de UPDATE em alguma coluna, e porque a vinculacao grava uma versao nova
-- da linha da conta (secao 4.5, camada 3, peca 4).

GRANT SELECT (identity_id, email, email_normalized, display_name, email_verified_at, status), UPDATE (updated_at)
    ON identities TO nekkora_identity_owner;
GRANT SELECT (identity_id, password_hash), UPDATE (password_hash, password_changed_at)   -- R3: re-hash e troca de senha
    ON identity_credentials TO nekkora_identity_owner;
GRANT SELECT, INSERT ON identity_users TO nekkora_identity_owner;
GRANT SELECT, INSERT, UPDATE (confirmed_at, disabled_at, last_used_step) ON identity_mfa_factors TO nekkora_identity_owner;
GRANT SELECT, INSERT, UPDATE (invalidated_at, used_at, used_in_identity_session_id) ON identity_mfa_recovery_codes TO nekkora_identity_owner;
GRANT SELECT (challenge_id, identity_id, token_hash, purpose, expires_at, consumed_at, invalidated_at, credential_hash, pending_password_hash),
      UPDATE (consumed_at, invalidated_at, credential_hash, pending_password_hash)
    ON identity_auth_challenges TO nekkora_identity_owner;   -- R1: travar e consumir o desafio sob o lock da identidade
GRANT SELECT, UPDATE (revoked_at, recovery_cleared_at),
      INSERT (identity_id, session_token_hash, expires_at, mfa_verified_at, recovery_used_at, csrf_token)   -- R3: a sessao so nasce por funcao
    ON identity_sessions TO nekkora_identity_owner;
GRANT SELECT, UPDATE (consumed_at, account_credential_hash) ON identity_link_challenges TO nekkora_identity_owner;
GRANT INSERT ON identity_events TO nekkora_identity_owner;
GRANT SELECT (token_id, identity_id, token_hash, expires_at, consumed_at, revoked_at) ON identity_email_tokens TO nekkora_identity_owner;   -- R2: identity_email_token_lookup

GRANT SELECT (organization_id, name, status), INSERT (organization_id, name, legal_name, status), UPDATE (updated_at)
    ON organizations TO nekkora_identity_owner;
GRANT SELECT (organization_id, user_id, account_type, status, email, email_normalized, email_verified_at),
      INSERT (organization_id, account_type, display_name, email, email_verified_at, status), UPDATE (updated_at)
    ON users TO nekkora_identity_owner;
GRANT SELECT (organization_id, membership_id, user_id, status), INSERT (organization_id, user_id, account_type, status)
    ON organization_memberships TO nekkora_identity_owner;
GRANT SELECT (organization_id, membership_id, role_id, status) ON organization_role_assignments TO nekkora_identity_owner;
GRANT SELECT (role_id, code) ON roles TO nekkora_identity_owner;
GRANT SELECT (user_id, password_hash) ON user_credentials TO nekkora_identity_owner;
GRANT SELECT (session_id, organization_id, user_id, created_at, revoked_at, identity_session_id), UPDATE (revoked_at)
    ON user_sessions TO nekkora_identity_owner;
GRANT SELECT (organization_id, user_id, consumed_at, invalidated_at), UPDATE (invalidated_at, credential_hash, pending_password_hash)
    ON auth_challenges TO nekkora_identity_owner;
GRANT SELECT (organization_id, user_id, created_at, confirmed_at, disabled_at), UPDATE (disabled_at) ON user_mfa_factors TO nekkora_identity_owner;
GRANT SELECT (organization_id, user_id, created_at, used_at, invalidated_at), UPDATE (invalidated_at) ON user_mfa_recovery_codes TO nekkora_identity_owner;
GRANT SELECT (user_id, created_at, consumed_at, revoked_at), UPDATE (revoked_at) ON password_reset_tokens TO nekkora_identity_owner;
GRANT SELECT (user_id, created_at, consumed_at, revoked_at), UPDATE (revoked_at) ON email_verification_tokens TO nekkora_identity_owner;

-- RLS: politica propria do dono (nome identity_function_context), sem alterar as existentes.
--   Escrita: so sob nekkora.fn_organization_id (organizacao que a propria funcao resolveu ou criou).
--   Leitura entre organizacoes: so as contas da identidade em nekkora.fn_identity_id (secao 5).
DO $$
DECLARE
    t TEXT;
    ctx_fn CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.fn_organization_id'', true), '''')::uuid';
    ctx_id CONSTANT TEXT := 'NULLIF(pg_catalog.current_setting(''nekkora.fn_identity_id'', true), '''')::uuid';
BEGIN
    FOREACH t IN ARRAY ARRAY['organizations', 'users', 'organization_memberships', 'organization_role_assignments', 'user_sessions',
                             'auth_challenges', 'user_mfa_factors', 'user_mfa_recovery_codes'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                       WHERE n.nspname = 'public' AND c.relname = t AND c.relrowsecurity AND c.relforcerowsecurity) THEN
            RAISE EXCEPTION 'tabela % sem RLS ENABLE+FORCE', t;
        END IF;
    END LOOP;

    EXECUTE format('CREATE POLICY identity_function_context ON public.users AS PERMISSIVE FOR ALL TO nekkora_identity_owner '
                   'USING (%s OR EXISTS (SELECT 1 FROM public.identity_users iu WHERE iu.user_id = users.user_id AND iu.identity_id = %s)) '
                   'WITH CHECK (%s)', ctx_fn, ctx_id, ctx_fn);
    EXECUTE format('CREATE POLICY identity_function_context ON public.organizations AS PERMISSIVE FOR ALL TO nekkora_identity_owner '
                   'USING (%s OR EXISTS (SELECT 1 FROM public.identity_users iu JOIN public.users u ON u.user_id = iu.user_id '
                   'WHERE u.organization_id = organizations.organization_id AND iu.identity_id = %s)) WITH CHECK (%s)', ctx_fn, ctx_id, ctx_fn);
    EXECUTE format('CREATE POLICY identity_function_context ON public.organization_memberships AS PERMISSIVE FOR ALL TO nekkora_identity_owner '
                   'USING (%s OR EXISTS (SELECT 1 FROM public.identity_users iu WHERE iu.user_id = organization_memberships.user_id '
                   'AND iu.identity_id = %s)) WITH CHECK (%s)', ctx_fn, ctx_id, ctx_fn);
    EXECUTE format('CREATE POLICY identity_function_context ON public.organization_role_assignments AS PERMISSIVE FOR ALL TO nekkora_identity_owner '
                   'USING (%s OR EXISTS (SELECT 1 FROM public.identity_users iu JOIN public.organization_memberships m ON m.user_id = iu.user_id '
                   'WHERE m.organization_id = organization_role_assignments.organization_id AND m.membership_id = organization_role_assignments.membership_id '
                   'AND iu.identity_id = %s)) WITH CHECK (%s)', ctx_fn, ctx_id, ctx_fn);
    FOREACH t IN ARRAY ARRAY['user_sessions', 'auth_challenges', 'user_mfa_factors', 'user_mfa_recovery_codes'] LOOP
        EXECUTE format('CREATE POLICY identity_function_context ON public.%I AS PERMISSIVE FOR ALL TO nekkora_identity_owner USING (%s) WITH CHECK (%s)',
                       t, ctx_fn, ctx_fn);
    END LOOP;
    -- derivadas (sem organization_id): pela organizacao da conta, no padrao da 012
    FOREACH t IN ARRAY ARRAY['user_credentials', 'password_reset_tokens', 'email_verification_tokens'] LOOP
        EXECUTE format('CREATE POLICY identity_function_context ON public.%I AS PERMISSIVE FOR ALL TO nekkora_identity_owner '
                       'USING (EXISTS (SELECT 1 FROM public.users u WHERE u.user_id = %I.user_id AND u.%s)) '
                       'WITH CHECK (EXISTS (SELECT 1 FROM public.users u WHERE u.user_id = %I.user_id AND u.%s))',
                       t, t, ctx_fn, t, ctx_fn);
    END LOOP;
END;
$$;

-- DC-17.5: a criacao de organizacao reaproveita o limite e o bootstrap de ORG_ADMIN da 012, sem afrouxar a trigger de
-- bootstrap (current_user = nekkora_provisioning_owner continua sendo o unico que grava responsavel nulo).
GRANT EXECUTE ON FUNCTION provision_resource_limit(UUID, INTEGER, SMALLINT, BIGINT, UUID) TO nekkora_identity_owner;
GRANT EXECUTE ON FUNCTION provision_first_org_admin(UUID, UUID, UUID, TEXT, UUID) TO nekkora_identity_owner;

-- === 6. Funcoes internas do dono (SECURITY INVOKER, sem EXECUTE para ninguem alem do dono) ===

CREATE FUNCTION public.identity_require_read_committed() RETURNS VOID
LANGUAGE plpgsql VOLATILE
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF pg_catalog.current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'funcao de identidade exige transaction_isolation=read committed (atual=%)',
            pg_catalog.current_setting('transaction_isolation') USING ERRCODE = '25N01';
    END IF;
END;
$$;

CREATE FUNCTION public.identity_token_hash(p_token TEXT) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    -- contrato D2 (013): 32 bytes em base64url sem padding; formato validado antes do hash
    IF p_token IS NULL OR pg_catalog.length(p_token) <> 43
       OR p_token OPERATOR(pg_catalog.!~) '^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$' THEN
        RETURN NULL;
    END IF;
    RETURN pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(p_token, 'UTF8')), 'hex');
END;
$$;

-- Resolve a sessao de identidade e TRAVA, nesta ordem, a identidade e depois a sessao (ordem global: identidade ->
-- sessao de identidade -> organizacao -> conta; grupo K). Travar a sessao antes da identidade formaria ciclo com
-- identity_mfa_replace, que revoga as DEMAIS sessoes depois de travar a identidade. Sessao invalida -> todas as saidas
-- nulas (zero efeito, sem excecao). A validacao e feita na leitura feita JA sob os dois locks.
CREATE FUNCTION public.identity_session_resolve(
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
    IF s.identity_session_id IS NULL OR s.revoked_at IS NOT NULL OR s.expires_at <= o_t OR s.mfa_verified_at IS NULL THEN
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

-- R1: desafio de cadastro (purpose 2) resolvido na ordem identidade -> desafio. A primeira leitura, sem lock, so descobre
-- a identidade; a prova e RECONFERIDA depois do lock da identidade, com o horario atual, e a linha do desafio fica travada
-- ate o fim da transacao (uma invalidacao concorrente pela aplicacao espera, em vez de passar por baixo). Desafio que
-- expirou ou foi invalidado durante a espera -> nulo, zero efeito.
CREATE FUNCTION public.identity_challenge_resolve(p_challenge_token TEXT, OUT o_challenge_id UUID, OUT o_identity_id UUID)
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

-- Autorizacao de substituir fator (DC-17.13): step-up <= 5 min, ou marca de recuperacao <= 15 min ainda nao levantada
CREATE FUNCTION public.identity_session_can_replace_factor(
    p_step_up_at TIMESTAMPTZ, p_recovery_used_at TIMESTAMPTZ, p_restrita BOOLEAN, p_t TIMESTAMPTZ) RETURNS BOOLEAN
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT coalesce(p_step_up_at > p_t - interval '5 minutes', false)
        OR coalesce(p_restrita AND p_recovery_used_at > p_t - interval '15 minutes', false)
$$;

-- R2: a prova da sessao e RECONFERIDA no instante p_t, medido DEPOIS de todos os locks da funcao chamadora (a linha da
-- sessao ja esta travada por identity_session_resolve). Conferir antes de uma espera nao basta. p_modo: 'step_up' (sessao
-- nao restrita, step-up dentro de 5 min) ou 'troca' (DC-17.13: step-up dentro de 5 min ou recuperacao dentro de 15 min).
CREATE FUNCTION public.identity_session_proof(p_session_id UUID, p_t TIMESTAMPTZ, p_modo TEXT) RETURNS BOOLEAN
LANGUAGE sql
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT coalesce((
        SELECT s.revoked_at IS NULL AND s.expires_at > p_t AND s.mfa_verified_at IS NOT NULL AND i.status = 2
           AND CASE p_modo
                 WHEN 'step_up' THEN (s.recovery_used_at IS NULL OR s.recovery_cleared_at IS NOT NULL)
                                     AND coalesce(s.mfa_step_up_at > p_t - interval '5 minutes', false)
                 WHEN 'troca' THEN public.identity_session_can_replace_factor(s.mfa_step_up_at, s.recovery_used_at,
                                     s.recovery_used_at IS NOT NULL AND s.recovery_cleared_at IS NULL, p_t)
                 ELSE false END
          FROM public.identity_sessions s JOIN public.identities i ON i.identity_id = s.identity_id
         WHERE s.identity_session_id = p_session_id), false)
$$;

-- R3: parametros de uma sessao nova (o token bruto nunca chega ao banco: so o hash)
CREATE FUNCTION public.identity_valid_session_params(p_sessao_hash TEXT, p_csrf TEXT, p_sessao_expira TIMESTAMPTZ) RETURNS BOOLEAN
LANGUAGE sql
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT coalesce(p_sessao_hash OPERATOR(pg_catalog.~) '^[0-9a-f]{64}$' AND p_csrf OPERATOR(pg_catalog.~) '^[A-Za-z0-9_-]{43}$'
                    AND p_sessao_expira <= pg_catalog.now() + interval '12 hours', false)
$$;

CREATE FUNCTION public.identity_valid_code_hashes(p_code_hashes TEXT[]) RETURNS BOOLEAN
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT p_code_hashes IS NOT NULL
       AND pg_catalog.array_ndims(p_code_hashes) = 1
       AND pg_catalog.cardinality(p_code_hashes) BETWEEN 8 AND 16
       AND NOT EXISTS (SELECT 1 FROM pg_catalog.unnest(p_code_hashes) h WHERE h IS NULL OR h OPERATOR(pg_catalog.!~) '^[0-9a-f]{64}$')
       AND (SELECT pg_catalog.count(DISTINCT h) FROM pg_catalog.unnest(p_code_hashes) h) = pg_catalog.cardinality(p_code_hashes)
$$;

-- === 7. Funcoes publicas (SECURITY DEFINER, dono nekkora_identity_owner, EXECUTE so para nekkora_platform) ===
--
-- Padrao: salvam os dois GUCs de funcao, executam, restauram (retorno normal e EXCEPTION). Falha de autorizacao =
-- zero linhas / NULL / false, SEM efeito. Guarda de READ COMMITTED nas que escrevem.

CREATE FUNCTION public.identity_resolve_login(p_email_normalized TEXT)
RETURNS TABLE (identity_id UUID, status SMALLINT, email_verified_at TIMESTAMPTZ, password_hash TEXT, tem_fator_confirmado BOOLEAN)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT i.identity_id, i.status, i.email_verified_at, c.password_hash,
           EXISTS (SELECT 1 FROM public.identity_mfa_factors f
                   WHERE f.identity_id = i.identity_id AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL)
      FROM public.identities i
      JOIN public.identity_credentials c ON c.identity_id = i.identity_id
     WHERE p_email_normalized IS NOT NULL
       AND i.email_normalized = p_email_normalized
$$;

CREATE FUNCTION public.identity_organizations(p_session_token TEXT)
RETURNS TABLE (organization_id UUID, name TEXT, role_codes TEXT[])
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
#variable_conflict use_column
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    s RECORD;
BEGIN
    BEGIN
        SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
        IF s.o_identity_id IS NOT NULL AND NOT s.o_restrita THEN
            PERFORM pg_catalog.set_config('nekkora.fn_organization_id', '', true);
            PERFORM pg_catalog.set_config('nekkora.fn_identity_id', s.o_identity_id::text, true);
            RETURN QUERY
            SELECT o.organization_id, o.name,
                   ARRAY(SELECT r.code FROM public.organization_role_assignments a JOIN public.roles r ON r.role_id = a.role_id
                          WHERE a.organization_id = m.organization_id AND a.membership_id = m.membership_id AND a.status = 1
                          ORDER BY r.code)::TEXT[]
              FROM public.identity_users iu
              JOIN public.users u ON u.user_id = iu.user_id
              JOIN public.organizations o ON o.organization_id = u.organization_id
              JOIN public.organization_memberships m ON m.user_id = u.user_id AND m.organization_id = u.organization_id
             WHERE iu.identity_id = s.o_identity_id
               AND u.account_type = 1 AND u.status = 2 AND u.email_verified_at IS NOT NULL
               AND o.status = 1 AND m.status = 2
             ORDER BY o.name, o.organization_id;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
END;
$$;

CREATE FUNCTION public.identity_org_session_target(p_session_token TEXT, p_organization_id UUID)
RETURNS TABLE (organization_id UUID, user_id UUID)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
#variable_conflict use_column
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    s RECORD;
BEGIN
    BEGIN
        SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
        IF s.o_identity_id IS NOT NULL AND NOT s.o_restrita AND p_organization_id IS NOT NULL THEN
            PERFORM pg_catalog.set_config('nekkora.fn_organization_id', '', true);
            PERFORM pg_catalog.set_config('nekkora.fn_identity_id', s.o_identity_id::text, true);
            RETURN QUERY
            SELECT u.organization_id, u.user_id
              FROM public.identity_users iu
              JOIN public.users u ON u.user_id = iu.user_id
              JOIN public.organizations o ON o.organization_id = u.organization_id
              JOIN public.organization_memberships m ON m.user_id = u.user_id AND m.organization_id = u.organization_id
             WHERE iu.identity_id = s.o_identity_id
               AND u.organization_id = p_organization_id
               AND u.account_type = 1 AND u.status = 2 AND u.email_verified_at IS NOT NULL
               AND o.status = 1 AND m.status = 2;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
END;
$$;

-- R5: canCreateOrganization (contrato P2). SO INDICACAO para a interface: verdadeiro se a sessao esta ativa, no prazo,
-- com MFA e NAO restrita, a identidade ACTIVE e verificada e ha menos de 10 vinculos 'cadastro_web' (o teto R11 de
-- identity_create_organization). Nao exige step-up (pedido na hora) e nao autoriza nada: a criacao reconfere tudo sob o
-- lock da identidade. So leitura, sem lock; token invalido, nulo ou desconhecido -> falso.
CREATE FUNCTION public.identity_org_creation_allowed(p_session_token TEXT)
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
           AND s.revoked_at IS NULL AND s.expires_at > pg_catalog.clock_timestamp() AND s.mfa_verified_at IS NOT NULL
           AND (s.recovery_used_at IS NULL OR s.recovery_cleared_at IS NOT NULL)
           AND i.status = 2 AND i.email_verified_at IS NOT NULL), false)
$$;

-- DC-17.11 / DC-17.12 / secao 4.5 camada 3. Ordem: identidade -> organizacao -> conta. Conferencias sob os locks;
-- efeitos (versao nova da conta, consumo, fechamento, vinculo) so depois de todas passarem.
CREATE FUNCTION public.identity_link_account_impl(p_session_token TEXT, p_link_token TEXT, p_organization_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    s RECORD;
    c RECORD;
    u RECORD;
    v_hash TEXT := public.identity_token_hash(p_link_token);
    v_identity_email TEXT;
    v_t TIMESTAMPTZ;
    v_n INTEGER;
    v_corr UUID := pg_catalog.gen_random_uuid();
BEGIN
    PERFORM public.identity_require_read_committed();
    IF v_hash IS NULL OR p_organization_id IS NULL THEN
        RETURN false;
    END IF;
    -- locks, na ordem global: identidade -> sessao -> organizacao -> desafio -> conta -> linhas do fechamento
    SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
    IF s.o_identity_id IS NULL THEN
        RETURN false;
    END IF;
    SELECT i.email_normalized INTO v_identity_email FROM public.identities i
     WHERE i.identity_id = s.o_identity_id AND i.status = 2 AND i.email_verified_at IS NOT NULL FOR UPDATE;
    IF v_identity_email IS NULL THEN
        RETURN false;
    END IF;
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', '', true);
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    PERFORM 1 FROM public.organizations o WHERE o.organization_id = p_organization_id AND o.status = 1 FOR UPDATE;
    IF NOT FOUND THEN
        RETURN false;
    END IF;
    SELECT x.challenge_id, x.user_id INTO c
      FROM public.identity_link_challenges x
     WHERE x.token_hash = v_hash AND x.identity_id = s.o_identity_id
       AND x.consumed_at IS NULL AND x.revoked_at IS NULL
       FOR UPDATE;
    IF c.challenge_id IS NULL THEN
        RETURN false;
    END IF;
    SELECT x.user_id, x.account_type, x.status, x.email_normalized, x.email_verified_at INTO u
      FROM public.users x WHERE x.user_id = c.user_id AND x.organization_id = p_organization_id FOR UPDATE;
    IF u.user_id IS NULL THEN
        RETURN false;
    END IF;
    -- R2: as linhas que o fechamento (DC-17.12) altera sao travadas AGORA, antes de medir a hora: nenhuma espera depois
    PERFORM 1 FROM public.user_sessions x WHERE x.organization_id = p_organization_id AND x.user_id = u.user_id AND x.revoked_at IS NULL FOR UPDATE;
    PERFORM 1 FROM public.auth_challenges x WHERE x.organization_id = p_organization_id AND x.user_id = u.user_id
       AND x.consumed_at IS NULL AND x.invalidated_at IS NULL FOR UPDATE;
    PERFORM 1 FROM public.password_reset_tokens x WHERE x.user_id = u.user_id AND x.consumed_at IS NULL AND x.revoked_at IS NULL FOR UPDATE;
    PERFORM 1 FROM public.email_verification_tokens x WHERE x.user_id = u.user_id AND x.consumed_at IS NULL AND x.revoked_at IS NULL FOR UPDATE;
    PERFORM 1 FROM public.user_mfa_recovery_codes x WHERE x.organization_id = p_organization_id AND x.user_id = u.user_id
       AND x.used_at IS NULL AND x.invalidated_at IS NULL FOR UPDATE;
    PERFORM 1 FROM public.user_mfa_factors x WHERE x.organization_id = p_organization_id AND x.user_id = u.user_id
       AND x.disabled_at IS NULL FOR UPDATE;

    -- R2: hora medida DEPOIS de todos os locks; provas reconferidas com ela
    v_t := pg_catalog.clock_timestamp();
    IF NOT public.identity_session_proof(s.o_session_id, v_t, 'step_up')
       OR u.account_type <> 1 OR u.status <> 2 OR u.email_verified_at IS NULL
       OR u.email_normalized <> v_identity_email
       OR NOT EXISTS (SELECT 1 FROM public.organization_memberships m
                      WHERE m.user_id = u.user_id AND m.organization_id = p_organization_id AND m.status = 2)
       OR EXISTS (SELECT 1 FROM public.identity_users iu WHERE iu.user_id = u.user_id) THEN
        RETURN false;
    END IF;
    -- primeira escrita: o consumo, condicionado a validade em v_t e a credencial ainda vigente; sem consumo, sem efeito
    UPDATE public.identity_link_challenges SET consumed_at = v_t, account_credential_hash = NULL
     WHERE challenge_id = c.challenge_id AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > v_t
       AND account_credential_hash = (SELECT uc.password_hash FROM public.user_credentials uc WHERE uc.user_id = u.user_id);
    GET DIAGNOSTICS v_n = ROW_COUNT;
    IF v_n <> 1 THEN
        RETURN false;
    END IF;

    UPDATE public.users SET updated_at = v_t WHERE user_id = u.user_id AND organization_id = p_organization_id;   -- versao nova (peca 4)
    -- DC-17.12, na mesma transacao (linhas ja travadas)
    UPDATE public.user_sessions SET revoked_at = greatest(v_t, created_at)
     WHERE organization_id = p_organization_id AND user_id = u.user_id AND revoked_at IS NULL;
    UPDATE public.auth_challenges SET invalidated_at = v_t, credential_hash = NULL, pending_password_hash = NULL
     WHERE organization_id = p_organization_id AND user_id = u.user_id AND consumed_at IS NULL AND invalidated_at IS NULL;
    UPDATE public.password_reset_tokens SET revoked_at = greatest(v_t, created_at)
     WHERE user_id = u.user_id AND consumed_at IS NULL AND revoked_at IS NULL;
    UPDATE public.email_verification_tokens SET revoked_at = greatest(v_t, created_at)
     WHERE user_id = u.user_id AND consumed_at IS NULL AND revoked_at IS NULL;
    UPDATE public.user_mfa_recovery_codes SET invalidated_at = greatest(v_t, created_at)
     WHERE organization_id = p_organization_id AND user_id = u.user_id AND used_at IS NULL AND invalidated_at IS NULL;
    UPDATE public.user_mfa_factors SET disabled_at = greatest(v_t, created_at)
     WHERE organization_id = p_organization_id AND user_id = u.user_id AND disabled_at IS NULL;
    INSERT INTO public.identity_users (identity_id, user_id, link_channel) VALUES (s.o_identity_id, u.user_id, 'vinculacao');
    INSERT INTO public.identity_events (identity_id, event_type, target_organization_id, correlation_id)
    VALUES (s.o_identity_id, 9, p_organization_id, v_corr);
    RETURN true;
END;
$$;

CREATE FUNCTION public.identity_link_account(p_session_token TEXT, p_link_token TEXT, p_organization_id UUID)
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
        v_r := public.identity_link_account_impl(p_session_token, p_link_token, p_organization_id);
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

-- DC-17.5: cria e vincula, SEM receber user_id nem organization_id. Limites: os do bootstrap da organizacao interna
-- (1-3 FINITE 0, 4-5 UNLIMITED), provisorios ate decisao de produto.
CREATE FUNCTION public.identity_create_organization_impl(p_session_token TEXT, p_name TEXT, p_legal_name TEXT)
RETURNS UUID
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    s RECORD;
    i RECORD;
    v_org UUID := pg_catalog.gen_random_uuid();
    v_user UUID;
    v_membership UUID;
    v_corr UUID := pg_catalog.gen_random_uuid();
BEGIN
    PERFORM public.identity_require_read_committed();
    SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
    IF s.o_identity_id IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT x.email, x.display_name, x.email_verified_at INTO i FROM public.identities x
     WHERE x.identity_id = s.o_identity_id AND x.status = 2 AND x.email_verified_at IS NOT NULL FOR UPDATE;
    IF i.email IS NULL THEN
        RETURN NULL;
    END IF;
    -- teto total (R11), sob o lock da identidade
    IF (SELECT pg_catalog.count(*) FROM public.identity_users iu
         WHERE iu.identity_id = s.o_identity_id AND iu.link_channel = 'cadastro_web') >= 10 THEN
        RETURN NULL;
    END IF;
    -- R2: prova reconferida com a hora medida depois dos locks (identidade e sessao)
    IF NOT public.identity_session_proof(s.o_session_id, pg_catalog.clock_timestamp(), 'step_up') THEN
        RETURN NULL;
    END IF;

    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', '', true);
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', v_org::text, true);
    INSERT INTO public.organizations (organization_id, name, legal_name, status) VALUES (v_org, p_name, p_legal_name, 1);
    INSERT INTO public.users (organization_id, account_type, display_name, email, email_verified_at, status)
    VALUES (v_org, 1, i.display_name, i.email, i.email_verified_at, 2)
    RETURNING user_id INTO v_user;
    INSERT INTO public.organization_memberships (organization_id, user_id, account_type, status)
    VALUES (v_org, v_user, 1, 2)
    RETURNING membership_id INTO v_membership;
    PERFORM public.provision_resource_limit(v_org, 1, 1::SMALLINT, 0, v_user);
    PERFORM public.provision_resource_limit(v_org, 2, 1::SMALLINT, 0, v_user);
    PERFORM public.provision_resource_limit(v_org, 3, 1::SMALLINT, 0, v_user);
    PERFORM public.provision_resource_limit(v_org, 4, 2::SMALLINT, NULL, v_user);
    PERFORM public.provision_resource_limit(v_org, 5, 2::SMALLINT, NULL, v_user);
    PERFORM public.provision_first_org_admin(v_org, v_membership, v_user, 'cadastro_web', v_corr);
    -- o vinculo so pode apontar a conta que ESTA funcao inseriu
    INSERT INTO public.identity_users (identity_id, user_id, link_channel) VALUES (s.o_identity_id, v_user, 'cadastro_web');
    INSERT INTO public.identity_events (identity_id, event_type, target_organization_id, correlation_id)
    VALUES (s.o_identity_id, 6, v_org, v_corr);
    RETURN v_org;
END;
$$;

CREATE FUNCTION public.identity_create_organization(p_session_token TEXT, p_name TEXT, p_legal_name TEXT)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    v_r UUID;
BEGIN
    BEGIN
        v_r := public.identity_create_organization_impl(p_session_token, p_name, p_legal_name);
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

-- Fator: unico caminho que insere. Desafio de cadastro (identidade que nunca confirmou fator) OU sessao autorizada
-- pela DC-17.13. Descarta (desabilita) o pendente anterior.
CREATE FUNCTION public.identity_mfa_enroll_pending(
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
    ELSIF NOT public.identity_session_proof(s.o_session_id, v_t, 'troca') THEN
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

CREATE FUNCTION public.identity_mfa_confirm_first(
    p_challenge_token TEXT, p_factor_id UUID, p_code_hashes TEXT[], p_passo_totp BIGINT,
    p_sessao_hash TEXT, p_csrf TEXT, p_sessao_expira TIMESTAMPTZ)
RETURNS TABLE (o_status TEXT, o_identity_session_id UUID)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_identity UUID;
    v_challenge UUID;
    c RECORD;
    v_cred TEXT;
    v_t TIMESTAMPTZ;
    v_n INTEGER;
    v_sessao UUID;
BEGIN
    PERFORM public.identity_require_read_committed();
    IF NOT public.identity_valid_code_hashes(p_code_hashes) OR p_factor_id IS NULL OR p_passo_totp IS NULL OR p_passo_totp < 0
       OR NOT public.identity_valid_session_params(p_sessao_hash, p_csrf, p_sessao_expira) THEN
        RETURN QUERY SELECT 'PARAMETRO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    -- locks: identidade -> desafio (finalidade 2) -> fator -> credencial; SO depois a hora
    SELECT r.o_challenge_id, r.o_identity_id INTO v_challenge, v_identity FROM public.identity_challenge_resolve(p_challenge_token) r;
    IF v_identity IS NULL THEN
        RETURN QUERY SELECT 'DESAFIO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    IF EXISTS (SELECT 1 FROM public.identity_mfa_factors f WHERE f.identity_id = v_identity AND f.confirmed_at IS NOT NULL) THEN
        RETURN QUERY SELECT 'RECUSADO'::TEXT, NULL::UUID; RETURN;   -- nao e o primeiro fator
    END IF;
    PERFORM 1 FROM public.identity_mfa_factors f
     WHERE f.factor_id = p_factor_id AND f.identity_id = v_identity AND f.confirmed_at IS NULL AND f.disabled_at IS NULL
       FOR UPDATE;
    IF NOT FOUND THEN
        RETURN QUERY SELECT 'RECUSADO'::TEXT, NULL::UUID; RETURN;
    END IF;
    SELECT x.password_hash INTO v_cred FROM public.identity_credentials x WHERE x.identity_id = v_identity FOR UPDATE;
    SELECT ch.credential_hash, ch.pending_password_hash INTO c FROM public.identity_auth_challenges ch WHERE ch.challenge_id = v_challenge;
    v_t := pg_catalog.clock_timestamp();
    -- prova de senha obsoleta: o desafio e invalidado e nenhuma sessao nasce, mesmo com o TOTP conferido
    IF v_cred IS DISTINCT FROM c.credential_hash THEN
        UPDATE public.identity_auth_challenges SET invalidated_at = v_t, credential_hash = NULL, pending_password_hash = NULL
         WHERE challenge_id = v_challenge AND consumed_at IS NULL AND invalidated_at IS NULL;
        RETURN QUERY SELECT 'SENHA_OBSOLETA'::TEXT, NULL::UUID; RETURN;
    END IF;
    IF p_sessao_expira <= v_t THEN
        RETURN QUERY SELECT 'PARAMETRO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    -- primeira escrita: o consumo condicionado a validade em v_t; sem ele, nenhum efeito
    UPDATE public.identity_auth_challenges SET consumed_at = v_t, credential_hash = NULL, pending_password_hash = NULL
     WHERE challenge_id = v_challenge AND consumed_at IS NULL AND invalidated_at IS NULL AND expires_at > v_t;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    IF v_n <> 1 THEN
        RETURN QUERY SELECT 'DESAFIO_INVALIDO'::TEXT, NULL::UUID; RETURN;
    END IF;
    IF c.pending_password_hash IS NOT NULL THEN   -- re-hash: comparar e trocar sobre o hash validado
        UPDATE public.identity_credentials SET password_hash = c.pending_password_hash
         WHERE identity_id = v_identity AND password_hash = c.credential_hash;
    END IF;
    UPDATE public.identity_mfa_factors SET confirmed_at = v_t, last_used_step = p_passo_totp WHERE factor_id = p_factor_id;
    INSERT INTO public.identity_mfa_recovery_codes (identity_id, factor_id, code_hash)
    SELECT v_identity, p_factor_id, h FROM pg_catalog.unnest(p_code_hashes) h;
    INSERT INTO public.identity_sessions (identity_id, session_token_hash, expires_at, mfa_verified_at, csrf_token)
    VALUES (v_identity, p_sessao_hash, p_sessao_expira, v_t, p_csrf)
    RETURNING identity_session_id INTO v_sessao;
    INSERT INTO public.identity_events (identity_id, event_type, correlation_id) VALUES (v_identity, 3, pg_catalog.gen_random_uuid());
    RETURN QUERY SELECT 'SESSAO_CRIADA'::TEXT, v_sessao;
END;
$$;

-- R3: conclusao do login (finalidade 1): consumo do desafio, credencial vigente, re-hash, uso do fator ou do codigo de
-- recuperacao e CRIACAO DA SESSAO num so comando. Locks: identidade -> desafio -> credencial -> fator -> (recuperacao:
-- codigo, demais sessoes, demais desafios); depois a hora. Status: SESSAO_CRIADA | DESAFIO_INVALIDO | SENHA_OBSOLETA |
-- FATOR_RECUSADO | PARAMETRO_INVALIDO. So SENHA_OBSOLETA escreve fora do sucesso: invalida o desafio.
CREATE FUNCTION public.identity_login_complete(
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

-- R3: troca de senha. Locks: identidade -> sessao -> credencial -> desafios de autenticacao pendentes -> demais sessoes;
-- depois a hora. Primeira escrita: comparar e trocar sobre o hash esperado. No mesmo comando: invalida TODOS os desafios
-- pendentes (um desafio anterior a troca nao completa o login), revoga as demais sessoes, grava o evento. Nunca devolve hash.
CREATE FUNCTION public.identity_password_change(p_session_token TEXT, p_expected_hash TEXT, p_new_hash TEXT)
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

-- T1 (DC-17.13), inteiro e atomico.
CREATE FUNCTION public.identity_mfa_replace(p_session_token TEXT, p_new_factor_id UUID, p_code_hashes TEXT[])
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    s RECORD;
    v_old UUID;
    v_t TIMESTAMPTZ;
BEGIN
    PERFORM public.identity_require_read_committed();
    IF NOT public.identity_valid_code_hashes(p_code_hashes) OR p_new_factor_id IS NULL THEN
        RETURN false;
    END IF;
    -- locks: identidade -> sessao -> fator antigo -> fator novo -> codigos vivos -> demais sessoes
    SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
    IF s.o_identity_id IS NULL THEN
        RETURN false;
    END IF;
    SELECT f.factor_id INTO v_old FROM public.identity_mfa_factors f
     WHERE f.identity_id = s.o_identity_id AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL FOR UPDATE;
    IF v_old IS NULL THEN
        RETURN false;
    END IF;
    PERFORM 1 FROM public.identity_mfa_factors f
     WHERE f.factor_id = p_new_factor_id AND f.identity_id = s.o_identity_id AND f.confirmed_at IS NULL AND f.disabled_at IS NULL
       FOR UPDATE;
    IF NOT FOUND THEN
        RETURN false;
    END IF;
    PERFORM 1 FROM public.identity_mfa_recovery_codes r
     WHERE r.identity_id = s.o_identity_id AND r.used_at IS NULL AND r.invalidated_at IS NULL FOR UPDATE;
    PERFORM 1 FROM public.identity_sessions x
     WHERE x.identity_id = s.o_identity_id AND x.identity_session_id <> s.o_session_id AND x.revoked_at IS NULL FOR UPDATE;
    -- R2: hora medida depois de TODOS os locks; a autorizacao (DC-17.13) e reconferida com ela
    v_t := pg_catalog.clock_timestamp();
    IF NOT public.identity_session_proof(s.o_session_id, v_t, 'troca') THEN
        RETURN false;
    END IF;
    UPDATE public.identity_mfa_factors SET disabled_at = v_t WHERE factor_id = v_old;
    UPDATE public.identity_mfa_factors SET confirmed_at = v_t WHERE factor_id = p_new_factor_id;
    UPDATE public.identity_mfa_recovery_codes SET invalidated_at = greatest(v_t, created_at)
     WHERE identity_id = s.o_identity_id AND used_at IS NULL AND invalidated_at IS NULL;
    INSERT INTO public.identity_mfa_recovery_codes (identity_id, factor_id, code_hash)
    SELECT s.o_identity_id, p_new_factor_id, h FROM pg_catalog.unnest(p_code_hashes) h;
    IF s.o_restrita THEN
        UPDATE public.identity_sessions SET recovery_cleared_at = v_t WHERE identity_session_id = s.o_session_id;
    END IF;
    UPDATE public.identity_sessions SET revoked_at = greatest(v_t, created_at)
     WHERE identity_id = s.o_identity_id AND identity_session_id <> s.o_session_id AND revoked_at IS NULL;
    INSERT INTO public.identity_events (identity_id, event_type, correlation_id) VALUES (s.o_identity_id, 11, pg_catalog.gen_random_uuid());
    RETURN true;
END;
$$;

-- === 8. Auxiliares de leitura usadas pelas triggers (SECURITY DEFINER, sem escrita, EXECUTE so para nekkora_platform) ===
--
-- Conferem o contexto da organizacao ANTES de responder (padrao de record_usage_ingestion, 012): contexto ausente ou
-- divergente, ou conta fora da organizacao do contexto -> 42501. Nao existe resposta para conta de outra organizacao.
-- VOLATILE: chamadas depois do lock, cada comando ve o que foi confirmado ate ali.

CREATE FUNCTION public.identity_check_context(p_organization_id UUID, p_user_id UUID) RETURNS VOID
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_ctx TEXT := pg_catalog.current_setting('nekkora.organization_id', true);
BEGIN
    IF p_organization_id IS NULL OR p_user_id IS NULL OR v_ctx IS NULL OR v_ctx = ''
       OR v_ctx::uuid IS DISTINCT FROM p_organization_id THEN
        RAISE EXCEPTION 'contexto organizacional ausente ou divergente' USING ERRCODE = '42501';
    END IF;
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', '', true);
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    IF NOT EXISTS (SELECT 1 FROM public.users u WHERE u.user_id = p_user_id AND u.organization_id = p_organization_id) THEN
        RAISE EXCEPTION 'conta fora do contexto organizacional' USING ERRCODE = '42501';
    END IF;
END;
$$;

CREATE FUNCTION public.identity_account_is_linked(p_organization_id UUID, p_user_id UUID) RETURNS BOOLEAN
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
        v_r := EXISTS (SELECT 1 FROM public.identity_users iu WHERE iu.user_id = p_user_id);
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

-- Secao 4.4: dois ramos mutuamente exclusivos.
CREATE FUNCTION public.identity_org_session_allowed(p_organization_id UUID, p_user_id UUID, p_identity_session_id UUID)
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
            -- ramo 2: conta vinculada - a sessao de identidade e a prova, desta identidade, ativa, com MFA, NAO restrita
            v_r := p_identity_session_id IS NOT NULL
               AND EXISTS (SELECT 1 FROM public.identity_users iu
                             JOIN public.identity_sessions s ON s.identity_id = iu.identity_id
                             JOIN public.identities i ON i.identity_id = iu.identity_id
                            WHERE iu.user_id = p_user_id
                              AND s.identity_session_id = p_identity_session_id
                              AND s.revoked_at IS NULL
                              AND s.expires_at > pg_catalog.clock_timestamp()
                              AND s.mfa_verified_at IS NOT NULL
                              AND (s.recovery_used_at IS NULL OR s.recovery_cleared_at IS NOT NULL)
                              AND i.status = 2)
               AND EXISTS (SELECT 1 FROM public.identity_mfa_factors g
                             JOIN public.identity_users iu2 ON iu2.identity_id = g.identity_id
                            WHERE iu2.user_id = p_user_id AND g.confirmed_at IS NOT NULL AND g.disabled_at IS NULL)
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

-- === 9. Triggers de fechamento (secao 4.5, camadas 2 e 3) e de nascimento da sessao (secao 4.4) ===
--
-- Lock da conta ANTES de conferir o vinculo, sob a RLS do papel que escreve (falha fechada se a linha nao aparece).
--
-- Excecao terminal (R1, estrita): um UPDATE que leva a linha de vivo a terminal (uma coluna de TG_ARGV[0] passa de nulo
-- a preenchida) nao reabre entrada e nao e conferido contra o vinculo - e o caminho da propria vinculacao (DC-17.12) e
-- do reset operacional da 016. Mas nesse UPDATE so podem mudar: as colunas terminais que estavam nulas e as colunas de
-- limpeza de TG_ARGV[1], e estas SO PARA NULO. Qualquer outra coluna alterada junto e RECUSADA (23514), em qualquer
-- conta, vinculada ou nao. Fora da excecao, todo UPDATE passa pelo lock e pela conferencia do vinculo.
-- user_credentials: TG_ARGV[0] = 'senha' (sem transicao terminal; conferido quando a senha muda; user_id e imutavel
-- por trigger propria).

CREATE FUNCTION refuse_local_entry_for_linked_account() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_org UUID;
    v_old JSONB;
    v_new JSONB;
    v_term TEXT[];
    v_limpa TEXT[];
    v_terminal BOOLEAN := false;
    v_bad TEXT;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF TG_ARGV[0] = 'senha' THEN
            IF NEW.password_hash = OLD.password_hash AND NEW.password_changed_at = OLD.password_changed_at THEN
                RETURN NEW;
            END IF;
        ELSE
            v_old := pg_catalog.to_jsonb(OLD);
            v_new := pg_catalog.to_jsonb(NEW);
            v_term := pg_catalog.string_to_array(TG_ARGV[0], ',');
            v_limpa := coalesce(pg_catalog.string_to_array(NULLIF(TG_ARGV[1], ''), ','), ARRAY[]::TEXT[]);
            SELECT coalesce(bool_or(v_old ->> c IS NULL AND v_new ->> c IS NOT NULL), false) INTO v_terminal
              FROM pg_catalog.unnest(v_term) c;
            IF v_terminal THEN
                SELECT string_agg(k, ', ' ORDER BY k) INTO v_bad
                  FROM pg_catalog.jsonb_object_keys(v_new) k
                 WHERE (v_new -> k) IS DISTINCT FROM (v_old -> k)
                   AND NOT (k = ANY (v_term) AND v_old ->> k IS NULL)
                   AND NOT (k = ANY (v_limpa) AND v_new -> k = 'null'::jsonb);
                IF v_bad IS NOT NULL THEN
                    RAISE EXCEPTION 'transicao terminal em % so altera as colunas terminais e a limpeza prevista (alteradas junto: %)',
                        TG_TABLE_NAME, v_bad USING ERRCODE = '23514';
                END IF;
                RETURN NEW;
            END IF;
        END IF;
    END IF;
    SELECT u.organization_id INTO v_org FROM public.users u WHERE u.user_id = NEW.user_id FOR UPDATE;
    IF v_org IS NULL THEN
        RAISE EXCEPTION 'conta nao encontrada no contexto ao escrever em %', TG_TABLE_NAME USING ERRCODE = '23514';
    END IF;
    IF public.identity_account_is_linked(v_org, NEW.user_id) THEN
        RAISE EXCEPTION 'conta vinculada a identidade nao autentica nem cadastra fator por credencial local (%)', TG_TABLE_NAME
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- (colunas terminais, colunas de limpeza que so podem ir a nulo)
CREATE TRIGGER trg_auth_challenges_refuse_linked BEFORE INSERT OR UPDATE ON auth_challenges
    FOR EACH ROW EXECUTE FUNCTION refuse_local_entry_for_linked_account('consumed_at,invalidated_at', 'credential_hash,pending_password_hash');
CREATE TRIGGER trg_user_mfa_factors_refuse_linked BEFORE INSERT OR UPDATE ON user_mfa_factors
    FOR EACH ROW EXECUTE FUNCTION refuse_local_entry_for_linked_account('disabled_at', '');
CREATE TRIGGER trg_user_mfa_recovery_codes_refuse_linked BEFORE INSERT OR UPDATE ON user_mfa_recovery_codes
    FOR EACH ROW EXECUTE FUNCTION refuse_local_entry_for_linked_account('used_at,invalidated_at', '');
CREATE TRIGGER trg_password_reset_tokens_refuse_linked BEFORE INSERT OR UPDATE ON password_reset_tokens
    FOR EACH ROW EXECUTE FUNCTION refuse_local_entry_for_linked_account('consumed_at,revoked_at', '');
CREATE TRIGGER trg_email_verification_tokens_refuse_linked BEFORE INSERT OR UPDATE ON email_verification_tokens
    FOR EACH ROW EXECUTE FUNCTION refuse_local_entry_for_linked_account('consumed_at,revoked_at', '');
CREATE TRIGGER trg_user_credentials_refuse_linked BEFORE INSERT OR UPDATE ON user_credentials
    FOR EACH ROW EXECUTE FUNCTION refuse_local_entry_for_linked_account('senha');

-- R1: imutabilidade das tabelas da 002 que nao tinham trigger de imutabilidade. Vale para TODA conta.
--   tokens: so consumed_at e revoked_at mudam, de nulo para preenchido, uma vez; dono, hash, prazo, e-mail alvo e
--           criacao sao imutaveis; token consumido ou revogado nao muda mais.
--   user_credentials: user_id e created_at imutaveis (a credencial nao muda de conta).
CREATE FUNCTION prevent_local_token_change() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF OLD.consumed_at IS NOT NULL OR OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'token de % consumido ou revogado e imutavel', TG_TABLE_NAME USING ERRCODE = '23514';
    END IF;
    IF (pg_catalog.to_jsonb(NEW) - 'consumed_at' - 'revoked_at') IS DISTINCT FROM (pg_catalog.to_jsonb(OLD) - 'consumed_at' - 'revoked_at') THEN
        RAISE EXCEPTION 'dono, hash, prazo e origem do token de % sao imutaveis', TG_TABLE_NAME USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_password_reset_tokens_prevent_change BEFORE UPDATE ON password_reset_tokens
    FOR EACH ROW EXECUTE FUNCTION prevent_local_token_change();
CREATE TRIGGER trg_email_verification_tokens_prevent_change BEFORE UPDATE ON email_verification_tokens
    FOR EACH ROW EXECUTE FUNCTION prevent_local_token_change();

CREATE FUNCTION prevent_user_credential_reassignment() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF NEW.user_id <> OLD.user_id OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'credencial local nao muda de conta (user_id e created_at imutaveis)' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_user_credentials_prevent_reassignment BEFORE UPDATE ON user_credentials
    FOR EACH ROW EXECUTE FUNCTION prevent_user_credential_reassignment();

-- Secao 4.4: substitui a da 014. Mesmas exigencias de marcador, CSRF, step-up e revogacao; depois o lock da conta e
-- os dois ramos (conferidos pela auxiliar, com o contexto).
CREATE OR REPLACE FUNCTION enforce_user_session_mfa_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF NEW.mfa_verified_at IS NULL OR NEW.csrf_token IS NULL OR NEW.mfa_step_up_at IS NOT NULL OR NEW.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'sessao nova exige marcador de MFA e CSRF, sem step-up nem revogacao'
            USING ERRCODE = '23514';
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

-- Desafio de vinculacao: so para conta visivel no contexto, nao vinculada, com o mesmo e-mail da identidade.
CREATE FUNCTION enforce_identity_link_challenge_insert() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_org UUID;
    v_email TEXT;
BEGIN
    IF NEW.consumed_at IS NOT NULL OR NEW.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'desafio de vinculacao nasce vivo' USING ERRCODE = '23514';
    END IF;
    SELECT u.organization_id, u.email_normalized INTO v_org, v_email FROM public.users u WHERE u.user_id = NEW.user_id FOR UPDATE;
    IF v_org IS NULL THEN
        RAISE EXCEPTION 'conta nao encontrada no contexto' USING ERRCODE = '23514';
    END IF;
    IF public.identity_account_is_linked(v_org, NEW.user_id) THEN
        RAISE EXCEPTION 'conta ja vinculada' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.identities i WHERE i.identity_id = NEW.identity_id AND i.email_normalized = v_email) THEN
        RAISE EXCEPTION 'e-mail da conta difere do da identidade' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_identity_link_challenges_enforce_insert BEFORE INSERT ON identity_link_challenges
    FOR EACH ROW EXECUTE FUNCTION enforce_identity_link_challenge_insert();

-- Cinto ao commit (secao 4.5): conta recem-vinculada sem nada vivo da entrada local. SECURITY DEFINER do dono: no
-- commit o GUC das funcoes ja foi restaurado; o proprio gatilho define fn_identity_id/fn_organization_id.
CREATE FUNCTION public.check_identity_link_closure() RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_org UUID;
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    v_bad TEXT;
BEGIN
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', NEW.identity_id::text, true);
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', '', true);
    SELECT u.organization_id INTO v_org FROM public.users u WHERE u.user_id = NEW.user_id;
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', '', true);
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_org::text, ''), true);
    SELECT CASE
        WHEN v_org IS NULL THEN 'conta invisivel'
        WHEN EXISTS (SELECT 1 FROM public.user_sessions x WHERE x.organization_id = v_org AND x.user_id = NEW.user_id
                       AND x.revoked_at IS NULL AND x.identity_session_id IS NULL) THEN 'E-1: sessao ativa pela porta antiga'
        WHEN EXISTS (SELECT 1 FROM public.user_mfa_factors x WHERE x.organization_id = v_org AND x.user_id = NEW.user_id
                       AND x.disabled_at IS NULL) THEN 'E-3: fator local habilitado'
        WHEN EXISTS (SELECT 1 FROM public.auth_challenges x WHERE x.organization_id = v_org AND x.user_id = NEW.user_id
                       AND x.consumed_at IS NULL AND x.invalidated_at IS NULL) THEN 'desafio local ativo'
        WHEN EXISTS (SELECT 1 FROM public.user_mfa_recovery_codes x WHERE x.organization_id = v_org AND x.user_id = NEW.user_id
                       AND x.used_at IS NULL AND x.invalidated_at IS NULL) THEN 'codigo local vivo'
        WHEN EXISTS (SELECT 1 FROM public.password_reset_tokens x WHERE x.user_id = NEW.user_id
                       AND x.consumed_at IS NULL AND x.revoked_at IS NULL) THEN 'token de recuperacao pendente'
        WHEN EXISTS (SELECT 1 FROM public.email_verification_tokens x WHERE x.user_id = NEW.user_id
                       AND x.consumed_at IS NULL AND x.revoked_at IS NULL) THEN 'token de verificacao pendente'
        END INTO v_bad;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'vinculacao com entrada local viva: %', v_bad USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_identity_users_fechamento
    AFTER INSERT ON identity_users
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.check_identity_link_closure();

-- === 10. Donos e EXECUTE ===

ALTER FUNCTION public.identity_require_read_committed() OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_token_hash(TEXT) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_session_resolve(TEXT) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_challenge_resolve(TEXT) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_session_can_replace_factor(TIMESTAMPTZ, TIMESTAMPTZ, BOOLEAN, TIMESTAMPTZ) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_valid_code_hashes(TEXT[]) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_session_proof(UUID, TIMESTAMPTZ, TEXT) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_check_context(UUID, UUID) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_link_account_impl(TEXT, TEXT, UUID) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_create_organization_impl(TEXT, TEXT, TEXT) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_resolve_login(TEXT) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_organizations(TEXT) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_org_session_target(TEXT, UUID) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_org_creation_allowed(TEXT) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_link_account(TEXT, TEXT, UUID) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_create_organization(TEXT, TEXT, TEXT) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_mfa_enroll_pending(TEXT, TEXT, BYTEA, BYTEA, TEXT) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_mfa_confirm_first(TEXT, UUID, TEXT[], BIGINT, TEXT, TEXT, TIMESTAMPTZ) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_login_complete(TEXT, TEXT, BIGINT, TEXT, TEXT, TEXT, TIMESTAMPTZ) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_password_change(TEXT, TEXT, TEXT) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_valid_session_params(TEXT, TEXT, TIMESTAMPTZ) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_mfa_replace(TEXT, UUID, TEXT[]) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_account_is_linked(UUID, UUID) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_org_session_allowed(UUID, UUID, UUID) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.check_identity_link_closure() OWNER TO nekkora_identity_owner;

REVOKE ALL ON FUNCTION public.identity_require_read_committed() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_token_hash(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_session_resolve(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_challenge_resolve(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_session_can_replace_factor(TIMESTAMPTZ, TIMESTAMPTZ, BOOLEAN, TIMESTAMPTZ) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_valid_code_hashes(TEXT[]) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_session_proof(UUID, TIMESTAMPTZ, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_check_context(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_link_account_impl(TEXT, TEXT, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_create_organization_impl(TEXT, TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_resolve_login(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_organizations(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_org_session_target(TEXT, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_org_creation_allowed(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_link_account(TEXT, TEXT, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_create_organization(TEXT, TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_mfa_enroll_pending(TEXT, TEXT, BYTEA, BYTEA, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_mfa_confirm_first(TEXT, UUID, TEXT[], BIGINT, TEXT, TEXT, TIMESTAMPTZ) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_login_complete(TEXT, TEXT, BIGINT, TEXT, TEXT, TEXT, TIMESTAMPTZ) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_password_change(TEXT, TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_valid_session_params(TEXT, TEXT, TIMESTAMPTZ) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_mfa_replace(TEXT, UUID, TEXT[]) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_account_is_linked(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_org_session_allowed(UUID, UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.check_identity_link_closure() FROM PUBLIC;
REVOKE ALL ON FUNCTION prevent_identity_change() FROM PUBLIC;
REVOKE ALL ON FUNCTION prevent_identity_credential_change() FROM PUBLIC;
REVOKE ALL ON FUNCTION protect_identity_mfa_factor() FROM PUBLIC;
REVOKE ALL ON FUNCTION protect_identity_recovery_code() FROM PUBLIC;
REVOKE ALL ON FUNCTION check_identity_recovery_code_session() FROM PUBLIC;
REVOKE ALL ON FUNCTION prevent_identity_auth_challenge_change() FROM PUBLIC;
REVOKE ALL ON FUNCTION prevent_identity_link_challenge_change() FROM PUBLIC;
REVOKE ALL ON FUNCTION prevent_identity_email_token_change() FROM PUBLIC;
REVOKE ALL ON FUNCTION enforce_identity_session_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION prevent_identity_session_change() FROM PUBLIC;
REVOKE ALL ON FUNCTION refuse_local_entry_for_linked_account() FROM PUBLIC;
REVOKE ALL ON FUNCTION enforce_identity_link_challenge_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION prevent_local_token_change() FROM PUBLIC;
REVOKE ALL ON FUNCTION prevent_user_credential_reassignment() FROM PUBLIC;

-- oito publicas + duas auxiliares + a consulta de criacao (R5)
GRANT EXECUTE ON FUNCTION public.identity_resolve_login(TEXT) TO nekkora_platform;
GRANT EXECUTE ON FUNCTION public.identity_organizations(TEXT) TO nekkora_platform;
GRANT EXECUTE ON FUNCTION public.identity_org_session_target(TEXT, UUID) TO nekkora_platform;
GRANT EXECUTE ON FUNCTION public.identity_org_creation_allowed(TEXT) TO nekkora_platform;
GRANT EXECUTE ON FUNCTION public.identity_link_account(TEXT, TEXT, UUID) TO nekkora_platform;
GRANT EXECUTE ON FUNCTION public.identity_create_organization(TEXT, TEXT, TEXT) TO nekkora_platform;
GRANT EXECUTE ON FUNCTION public.identity_mfa_enroll_pending(TEXT, TEXT, BYTEA, BYTEA, TEXT) TO nekkora_platform;
GRANT EXECUTE ON FUNCTION public.identity_mfa_confirm_first(TEXT, UUID, TEXT[], BIGINT, TEXT, TEXT, TIMESTAMPTZ) TO nekkora_platform;
GRANT EXECUTE ON FUNCTION public.identity_login_complete(TEXT, TEXT, BIGINT, TEXT, TEXT, TEXT, TIMESTAMPTZ) TO nekkora_platform;
GRANT EXECUTE ON FUNCTION public.identity_password_change(TEXT, TEXT, TEXT) TO nekkora_platform;
GRANT EXECUTE ON FUNCTION public.identity_mfa_replace(TEXT, UUID, TEXT[]) TO nekkora_platform;
GRANT EXECUTE ON FUNCTION public.identity_account_is_linked(UUID, UUID) TO nekkora_platform;
GRANT EXECUTE ON FUNCTION public.identity_org_session_allowed(UUID, UUID, UUID) TO nekkora_platform;

-- === 11. Privilegios da aplicacao nas tabelas de identidade (secao 3.3.1 e grupo M) ===
--
-- Nenhum privilegio em identity_users. Fator e codigos: so leitura, passo TOTP e consumo do codigo. Nenhum DELETE ou
-- TRUNCATE em nenhuma tabela nova.

-- R2 (alternativa A): toda leitura e escrita da aplicacao nestas tabelas passa pela RLS por identidade (secao 12a).
-- identities.identity_id e gerado pela aplicacao e definido como contexto ANTES do INSERT (WITH CHECK).
GRANT SELECT, INSERT (identity_id, email, display_name, status), UPDATE (display_name, status, email_verified_at) ON identities TO nekkora_platform;
-- identity_credentials: SEM SELECT e SEM UPDATE. O hash so sai por identity_resolve_login (contrato da autenticacao);
-- troca de senha e re-hash de identidade ficam para funcao propria, em missao posterior.
GRANT INSERT (identity_id, password_hash) ON identity_credentials TO nekkora_platform;
GRANT SELECT, UPDATE (last_used_step) ON identity_mfa_factors TO nekkora_platform;
GRANT SELECT ON identity_mfa_recovery_codes TO nekkora_platform;   -- R3: o uso do codigo e da identity_login_complete
GRANT SELECT, INSERT (identity_id, token_hash, purpose, credential_hash, pending_password_hash, csrf_token, expires_at),
      UPDATE (attempts, invalidated_at, credential_hash, pending_password_hash)   -- R3: consumo so por funcao
    ON identity_auth_challenges TO nekkora_platform;
GRANT SELECT, UPDATE (last_seen_at, revoked_at, mfa_step_up_at)   -- R3: sem INSERT; a sessao so nasce por funcao
    ON identity_sessions TO nekkora_platform;
GRANT SELECT, INSERT (identity_id, user_id, token_hash, account_credential_hash, expires_at),
      UPDATE (attempts, revoked_at, account_credential_hash)
    ON identity_link_challenges TO nekkora_platform;
GRANT SELECT, INSERT (purpose, identity_id, target_email, token_hash, expires_at), UPDATE (consumed_at, revoked_at)
    ON identity_email_tokens TO nekkora_platform;
GRANT INSERT (identity_id, event_type, target_organization_id, correlation_id) ON identity_events TO nekkora_platform;

-- === 12a. RLS por identidade (R2, alternativa A aprovada pela responsavel) ===
--
-- Contexto: nekkora.identity_id, definido pela APLICACAO (SET LOCAL) depois de autenticar ou de resolver uma chave
-- exata pelas funcoes de consulta abaixo. A RLS limita o acesso ao contexto; ela NAO prova que a aplicacao autenticou
-- aquela pessoa - mesma natureza da RLS por organizacao da 012.
--   nekkora_platform: identity_request_context, FOR ALL, USING e WITH CHECK identity_id = contexto, em todas as tabelas
--       de identidade exceto identity_users (sem privilegio algum). Sem contexto: nenhuma linha, nenhuma escrita.
--   nekkora_identity_owner: identity_function_scope, FOR ALL, sem filtro. O dono so e alcancavel por dentro das funcoes
--       SECURITY DEFINER (NOLOGIN, sem membros, invariante 9); a fronteira dele e o codigo das funcoes, que recebem
--       chave exata (token, e-mail normalizado) ou sessao/desafio ja validados.

DO $$
DECLARE
    t TEXT;
    ctx_id CONSTANT TEXT := 'identity_id = NULLIF(pg_catalog.current_setting(''nekkora.identity_id'', true), '''')::uuid';
BEGIN
    FOREACH t IN ARRAY ARRAY['identities', 'identity_credentials', 'identity_users', 'identity_mfa_factors', 'identity_mfa_recovery_codes',
                             'identity_auth_challenges', 'identity_sessions', 'identity_link_challenges', 'identity_email_tokens',
                             'identity_events'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY identity_function_scope ON public.%I AS PERMISSIVE FOR ALL TO nekkora_identity_owner USING (true) WITH CHECK (true)', t);
        IF t <> 'identity_users' THEN
            EXECUTE format('CREATE POLICY identity_request_context ON public.%I AS PERMISSIVE FOR ALL TO nekkora_platform USING (%s) WITH CHECK (%s)',
                           t, ctx_id, ctx_id);
        END IF;
    END LOOP;
END;
$$;

-- Funcoes de consulta anteriores a autenticacao (R2). Cada uma recebe a CHAVE EXATA (e-mail normalizado ou token bruto,
-- com o hash calculado no banco) e devolve so os campos necessarios para a aplicacao definir o contexto: nenhuma lista,
-- prefixo, curinga ou contagem. Ressalva: nao impedem sondagem repetida por um papel de banco comprometido, que pode
-- testar chaves uma a uma; limitam a forma da resposta, nao o numero de perguntas.

-- cadastro (secao 6.1): o e-mail ja tem identidade, e em que estado (para criar, reenviar ou avisar)
CREATE FUNCTION public.identity_signup_state(p_email_normalized TEXT)
RETURNS TABLE (identity_id UUID, status SMALLINT)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT i.identity_id, i.status FROM public.identities i
     WHERE p_email_normalized IS NOT NULL AND i.email_normalized = p_email_normalized
$$;

-- desafio de identidade pelo token do cookie __Host-nekkora_id_mfa: so se vivo e no prazo
CREATE FUNCTION public.identity_challenge_lookup(p_token TEXT)
RETURNS TABLE (challenge_id UUID, identity_id UUID)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT ch.challenge_id, ch.identity_id FROM public.identity_auth_challenges ch
     WHERE ch.token_hash = public.identity_token_hash(p_token)
       AND ch.consumed_at IS NULL AND ch.invalidated_at IS NULL AND ch.expires_at > pg_catalog.clock_timestamp()
$$;

-- sessao de identidade pelo token do cookie __Host-nekkora_id (IdentityFilter): so se ativa e no prazo
CREATE FUNCTION public.identity_session_lookup(p_token TEXT)
RETURNS TABLE (identity_session_id UUID, identity_id UUID)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT s.identity_session_id, s.identity_id FROM public.identity_sessions s
     WHERE s.session_token_hash = public.identity_token_hash(p_token)
       AND s.revoked_at IS NULL AND s.expires_at > pg_catalog.clock_timestamp()
$$;

-- token de confirmacao de cadastro (secao 6.2): so se vivo e no prazo
CREATE FUNCTION public.identity_email_token_lookup(p_token TEXT)
RETURNS TABLE (token_id UUID, identity_id UUID)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT t.token_id, t.identity_id FROM public.identity_email_tokens t
     WHERE t.token_hash = public.identity_token_hash(p_token)
       AND t.consumed_at IS NULL AND t.revoked_at IS NULL AND t.expires_at > pg_catalog.clock_timestamp()
$$;

ALTER FUNCTION public.identity_signup_state(TEXT) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_challenge_lookup(TEXT) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_session_lookup(TEXT) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_email_token_lookup(TEXT) OWNER TO nekkora_identity_owner;
REVOKE ALL ON FUNCTION public.identity_signup_state(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_challenge_lookup(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_session_lookup(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_email_token_lookup(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.identity_signup_state(TEXT) TO nekkora_platform;
GRANT EXECUTE ON FUNCTION public.identity_challenge_lookup(TEXT) TO nekkora_platform;
GRANT EXECUTE ON FUNCTION public.identity_session_lookup(TEXT) TO nekkora_platform;
GRANT EXECUTE ON FUNCTION public.identity_email_token_lookup(TEXT) TO nekkora_platform;

-- a conferencia adiada do codigo de recuperacao roda no commit, quando o contexto pode ja ser outro: passa a ser do dono
ALTER FUNCTION check_identity_recovery_code_session() SECURITY DEFINER;
ALTER FUNCTION check_identity_recovery_code_session() OWNER TO nekkora_identity_owner;

-- === 12. Invariantes verificados ao final (secao 4.3) ===

DO $$
DECLARE
    v_bad TEXT;
    v_n INTEGER;
    v_protegidos CONSTANT TEXT[] := ARRAY['nekkora_platform', 'nekkora_ingestion', 'nekkora_scheduler', 'nekkora_provisioning',
        'nekkora_retention_executor', 'nekkora_scheduler_owner', 'nekkora_provisioning_owner', 'nekkora_retention_owner',
        'nekkora_retention_tables_owner', 'nekkora_portal', 'nekkora_portal_owner', 'nekkora_identity_owner'];
    v_novas CONSTANT TEXT[] := ARRAY['identities', 'identity_credentials', 'identity_users', 'identity_mfa_factors',
        'identity_mfa_recovery_codes', 'identity_auth_challenges', 'identity_sessions', 'identity_link_challenges',
        'identity_email_tokens', 'identity_events'];
    v_exec_esperado CONSTANT TEXT := 'identity_account_is_linked(uuid,uuid), identity_challenge_lookup(text), '
        'identity_create_organization(text,text,text), identity_email_token_lookup(text), '
        'identity_link_account(text,text,uuid), '
        'identity_login_complete(text,text,bigint,text,text,text,timestamp with time zone), '
        'identity_mfa_confirm_first(text,uuid,text[],bigint,text,text,timestamp with time zone), '
        'identity_mfa_enroll_pending(text,text,bytea,bytea,text), identity_mfa_replace(text,uuid,text[]), '
        'identity_org_creation_allowed(text), '
        'identity_org_session_allowed(uuid,uuid,uuid), identity_org_session_target(text,uuid), identity_organizations(text), '
        'identity_password_change(text,text,text), '
        'identity_resolve_login(text), identity_session_lookup(text), identity_signup_state(text)';
BEGIN
    -- 1. contagem de tabelas de tenant inalterada: 39 da 012 + 4 da 014 = 43
    SELECT count(*) INTO v_n FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind = 'r'
      AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'organization_id' AND NOT a.attisdropped);
    IF v_n <> 43 THEN RAISE EXCEPTION 'invariante 1: % tabelas com organization_id (esperadas 43)', v_n; END IF;

    -- 2. SECURITY DEFINER com search_path fixo e sem EXECUTE publico
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.prosecdef
      AND (p.proconfig IS NULL OR NOT EXISTS (SELECT 1 FROM unnest(p.proconfig) c WHERE c LIKE 'search_path=%')
           OR has_function_privilege('public', p.oid, 'EXECUTE'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'invariante 2: SECURITY DEFINER fora do padrao: %', v_bad; END IF;

    -- 3. funcoes de identidade executaveis pelo nekkora_platform: exatamente as oito publicas, as duas auxiliares e as
    --    quatro de consulta por chave exata (R2) e a consulta de criacao (R5)
    SELECT string_agg(p.oid::regprocedure::text, ', ' ORDER BY p.oid::regprocedure::text) INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND (p.proname LIKE 'identity\_%' OR pg_get_userbyid(p.proowner) = 'nekkora_identity_owner')
      AND has_function_privilege('nekkora_platform', p.oid, 'EXECUTE');
    IF v_bad IS DISTINCT FROM v_exec_esperado THEN
        RAISE EXCEPTION 'invariante 3: funcoes de identidade executaveis pela Platform divergem: %', v_bad;
    END IF;

    -- 4. sem UPDATE (disabled_at) da Platform no fator de identidade (I-1)
    IF has_column_privilege('nekkora_platform', 'public.identity_mfa_factors', 'disabled_at', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.identity_mfa_factors', 'confirmed_at', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.identity_mfa_factors', 'INSERT') THEN
        RAISE EXCEPTION 'invariante 4: Platform com escrita de estado no fator de identidade';
    END IF;

    -- 5. triggers de recusa e de user_sessions habilitadas
    SELECT count(*) INTO v_n FROM pg_trigger t
    WHERE NOT t.tgisinternal AND t.tgenabled = 'O'
      AND (t.tgname, t.tgrelid) IN (('trg_auth_challenges_refuse_linked', 'public.auth_challenges'::regclass),
                                    ('trg_user_mfa_factors_refuse_linked', 'public.user_mfa_factors'::regclass),
                                    ('trg_user_mfa_recovery_codes_refuse_linked', 'public.user_mfa_recovery_codes'::regclass),
                                    ('trg_password_reset_tokens_refuse_linked', 'public.password_reset_tokens'::regclass),
                                    ('trg_email_verification_tokens_refuse_linked', 'public.email_verification_tokens'::regclass),
                                    ('trg_user_credentials_refuse_linked', 'public.user_credentials'::regclass),
                                    ('trg_user_sessions_enforce_mfa', 'public.user_sessions'::regclass),
                                    ('trg_user_sessions_prevent_reactivation', 'public.user_sessions'::regclass),
                                    ('trg_identity_users_fechamento', 'public.identity_users'::regclass),
                                    ('trg_password_reset_tokens_prevent_change', 'public.password_reset_tokens'::regclass),
                                    ('trg_email_verification_tokens_prevent_change', 'public.email_verification_tokens'::regclass),
                                    ('trg_user_credentials_prevent_reassignment', 'public.user_credentials'::regclass));
    IF v_n <> 12 THEN RAISE EXCEPTION 'invariante 5: triggers de fechamento ausentes ou desabilitadas (%)', v_n; END IF;

    -- 6. E-1, E-2, E-3
    IF EXISTS (SELECT 1 FROM user_sessions s JOIN identity_users iu ON iu.user_id = s.user_id
               WHERE s.revoked_at IS NULL AND s.identity_session_id IS NULL)
       OR EXISTS (SELECT 1 FROM user_sessions s JOIN identity_sessions x ON x.identity_session_id = s.identity_session_id
                  WHERE NOT EXISTS (SELECT 1 FROM identity_users iu WHERE iu.user_id = s.user_id AND iu.identity_id = x.identity_id))
       OR EXISTS (SELECT 1 FROM user_mfa_factors f JOIN identity_users iu ON iu.user_id = f.user_id WHERE f.disabled_at IS NULL) THEN
        RAISE EXCEPTION 'invariante 6: E-1, E-2 ou E-3 violado';
    END IF;

    -- 7. Platform: nada em identity_users; sem escrita nas marcas de recuperacao; matriz exata no fator e nos codigos
    IF has_table_privilege('nekkora_platform', 'public.identity_users', 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
       OR has_any_column_privilege('nekkora_platform', 'public.identity_users', 'SELECT, INSERT, UPDATE, REFERENCES')
       OR has_column_privilege('nekkora_platform', 'public.identity_sessions', 'recovery_used_at', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.identity_sessions', 'recovery_cleared_at', 'INSERT, UPDATE')
       OR has_any_column_privilege('nekkora_platform', 'public.identity_mfa_factors', 'INSERT')
       OR has_any_column_privilege('nekkora_platform', 'public.identity_mfa_recovery_codes', 'INSERT')
       OR has_column_privilege('nekkora_platform', 'public.identity_mfa_recovery_codes', 'invalidated_at', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.identity_link_challenges', 'consumed_at', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.identity_sessions', 'INSERT')
       OR has_any_column_privilege('nekkora_platform', 'public.identity_sessions', 'INSERT')
       OR has_column_privilege('nekkora_platform', 'public.identity_auth_challenges', 'consumed_at', 'UPDATE')
       OR has_any_column_privilege('nekkora_platform', 'public.identity_mfa_recovery_codes', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.identity_credentials', 'SELECT, UPDATE')
       OR has_any_column_privilege('nekkora_platform', 'public.identity_credentials', 'SELECT, UPDATE') THEN
        RAISE EXCEPTION 'invariante 7: privilegio da Platform alem da matriz';
    END IF;

    -- 10 (R2). RLS por identidade: ENABLE + FORCE nas dez tabelas; Platform com exatamente identity_request_context nas
    --     nove (nenhuma em identity_users); dono com identity_function_scope; nenhuma outra politica
    SELECT string_agg(c.relname, ', ') INTO v_bad
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relname = ANY (v_novas) AND NOT (c.relrowsecurity AND c.relforcerowsecurity);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'invariante 10: tabela de identidade sem RLS forcada: %', v_bad; END IF;
    SELECT string_agg(format('%s:%s:%s', p.tablename, p.policyname, p.roles), ', ') INTO v_bad
    FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = ANY (v_novas)
      AND NOT ((p.policyname = 'identity_request_context' AND p.roles = ARRAY['nekkora_platform']::name[] AND p.tablename <> 'identity_users')
            OR (p.policyname = 'identity_function_scope' AND p.roles = ARRAY['nekkora_identity_owner']::name[]));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'invariante 10: politica inesperada em tabela de identidade: %', v_bad; END IF;
    IF (SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = ANY (v_novas) AND policyname = 'identity_request_context') <> 9
       OR (SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = ANY (v_novas) AND policyname = 'identity_function_scope') <> 10 THEN
        RAISE EXCEPTION 'invariante 10: contagem de politicas de identidade divergente';
    END IF;
    SELECT string_agg(t, ', ') INTO v_bad FROM unnest(v_novas) AS t
    WHERE has_table_privilege('nekkora_platform', 'public.' || t, 'DELETE, TRUNCATE, TRIGGER, REFERENCES');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'invariante 7: Platform com DELETE/TRUNCATE em tabela nova: %', v_bad; END IF;

    -- 8. donos: nenhum papel LOGIN de servico e dono; Platform sem atributos
    SELECT string_agg(DISTINCT pg_get_userbyid(o.owner), ', ') INTO v_bad FROM (
        SELECT relowner AS owner FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
        UNION ALL
        SELECT proowner FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public'
    ) o JOIN pg_roles r ON r.oid = o.owner
    WHERE r.rolname LIKE 'nekkora\_%' AND r.rolcanlogin;
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'invariante 8: papel LOGIN dono de objeto: %', v_bad; END IF;
    SELECT string_agg(rolname, ', ') INTO v_bad FROM pg_roles
    WHERE rolname = ANY (v_protegidos) AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'invariante 8: papel protegido com atributo privilegiado: %', v_bad; END IF;
    IF (SELECT rolcanlogin FROM pg_roles WHERE rolname = 'nekkora_identity_owner') THEN
        RAISE EXCEPTION 'invariante 8: nekkora_identity_owner deve ser NOLOGIN';
    END IF;

    -- 9. nenhuma membership envolvendo o dono de identidade; triggers de imutabilidade do fator e dos codigos
    SELECT string_agg(format('%s -> %s', r.rolname, m.rolname), ', ') INTO v_bad
    FROM pg_auth_members am JOIN pg_roles r ON r.oid = am.roleid JOIN pg_roles m ON m.oid = am.member
    WHERE r.rolname = 'nekkora_identity_owner' OR m.rolname = 'nekkora_identity_owner';
    IF v_bad IS NOT NULL OR pg_has_role('nekkora_platform', 'nekkora_identity_owner', 'MEMBER') THEN
        RAISE EXCEPTION 'invariante 9: membership envolvendo nekkora_identity_owner: %', v_bad;
    END IF;
    IF (SELECT count(*) FROM pg_trigger t WHERE NOT t.tgisinternal AND t.tgenabled = 'O'
          AND t.tgname IN ('trg_identity_mfa_factors_protect', 'trg_identity_mfa_recovery_codes_protect',
                           'trg_identity_mfa_recovery_codes_sessao_restrita')) <> 3 THEN
        RAISE EXCEPTION 'invariante 9: triggers de imutabilidade do fator ausentes ou desabilitadas';
    END IF;

    -- complementares: CREATE efetivo; nenhuma tabela nova com organization_id
    SELECT string_agg(p, ', ') INTO v_bad FROM unnest(v_protegidos) AS p
    WHERE has_schema_privilege(p, 'public', 'CREATE') OR has_database_privilege(p, current_database(), 'CREATE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel protegido com CREATE efetivo: %', v_bad; END IF;
    SELECT string_agg(t, ', ') INTO v_bad FROM unnest(v_novas) AS t
    WHERE EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = ('public.' || t)::regclass AND a.attname = 'organization_id');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'tabela nova com organization_id: %', v_bad; END IF;
END;
$$;

COMMIT;
