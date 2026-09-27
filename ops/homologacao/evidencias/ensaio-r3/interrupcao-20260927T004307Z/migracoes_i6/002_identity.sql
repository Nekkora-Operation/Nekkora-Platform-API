-- Nekkora Platform - identidade: contas (internas/externas), credenciais,
-- dominios de organizacao, vinculos e estruturas de autenticacao.
-- Depende de 001_initial.sql (organizations, clients, set_updated_at()).
--
-- Codigos de status (SMALLINT, explicitos e permanentes - nao reordenar nem reutilizar):
--   users.account_type                    : 1=INTERNAL, 2=CLIENT
--   users.status                          : 1=PENDING, 2=ACTIVE, 3=SUSPENDED, 4=CLOSED
--   organization_memberships.status       : 1=PENDING, 2=ACTIVE, 3=SUSPENDED, 4=REVOKED
--   client_memberships.status             : 1=PENDING, 2=ACTIVE, 3=SUSPENDED, 4=REVOKED
--   organization_domains.status           : 1=PENDING, 2=VERIFIED, 3=DISABLED
--
-- Conta ativa, isoladamente, nao concede acesso: exige vinculo valido (membership),
-- e-mail verificado e aprovacao continuam sendo condicoes distintas.

CREATE TABLE users (
    user_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations (organization_id) ON DELETE RESTRICT,
    account_type      SMALLINT NOT NULL CHECK (account_type IN (1, 2)),
    display_name      TEXT NOT NULL CHECK (btrim(display_name) <> ''),
    email             TEXT NOT NULL
        CHECK (btrim(email) <> '')
        CHECK (email = btrim(email))
        CHECK (email !~ '\s'),
    email_normalized  TEXT GENERATED ALWAYS AS (lower(email)) STORED,
    email_verified_at TIMESTAMPTZ,
    status            SMALLINT NOT NULL CHECK (status IN (1, 2, 3, 4)),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- um so contexto de conta por e-mail dentro da organizacao (limitacao assumida na v1)
    UNIQUE (organization_id, email_normalized),
    -- alvo das FKs compostas com discriminador (organization_memberships / client_memberships)
    UNIQUE (organization_id, user_id, account_type),
    -- alvo da FK composta de user_sessions
    UNIQUE (organization_id, user_id)
);

COMMENT ON COLUMN users.account_type IS '1=INTERNAL, 2=CLIENT';
COMMENT ON COLUMN users.status IS '1=PENDING, 2=ACTIVE, 3=SUSPENDED, 4=CLOSED';
COMMENT ON COLUMN users.email_normalized IS 'lower(email), gerada - garante que nao diverge de email por gravacao manual';

-- organization_id e account_type sao imutaveis apos a criacao (conta externa nao se torna interna por edicao comum).
CREATE FUNCTION prevent_identity_reassignment() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.organization_id <> OLD.organization_id OR NEW.account_type <> OLD.account_type THEN
        RAISE EXCEPTION 'organization_id e account_type sao imutaveis (user_id=%)', OLD.user_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_prevent_identity_reassignment
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION prevent_identity_reassignment();

CREATE TRIGGER trg_users_set_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE user_credentials (
    user_id             UUID PRIMARY KEY REFERENCES users (user_id) ON DELETE RESTRICT,
    password_hash       TEXT NOT NULL CHECK (btrim(password_hash) <> ''),
    password_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN user_credentials.password_hash IS 'hash completo (algoritmo, parametros e salt inclusos) - nunca a senha em texto claro nem recuperavel';

CREATE TRIGGER trg_user_credentials_set_updated_at
    BEFORE UPDATE ON user_credentials
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE organization_domains (
    domain_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations (organization_id) ON DELETE RESTRICT,
    domain_normalized TEXT NOT NULL
        CHECK (btrim(domain_normalized) <> '')
        CHECK (domain_normalized = lower(btrim(domain_normalized)))
        CHECK (domain_normalized !~ '\s'),
    status            SMALLINT NOT NULL CHECK (status IN (1, 2, 3)),
    verified_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- escopo por organizacao, nao global: o mesmo dominio pode ser usado por organizacoes
    -- distintas do mesmo grupo empresarial.
    UNIQUE (organization_id, domain_normalized)
);

COMMENT ON COLUMN organization_domains.status IS '1=PENDING, 2=VERIFIED, 3=DISABLED';

CREATE TRIGGER trg_organization_domains_set_updated_at
    BEFORE UPDATE ON organization_domains
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE organization_memberships (
    membership_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    user_id         UUID NOT NULL,
    account_type    SMALLINT NOT NULL CHECK (account_type = 1),
    status          SMALLINT NOT NULL CHECK (status IN (1, 2, 3, 4)),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- no maximo um vinculo interno por conta (a FK com discriminador nao garante isso isoladamente)
    UNIQUE (user_id),
    -- garante organizacao correta e account_type=1 (conta INTERNAL) quando o vinculo existe
    FOREIGN KEY (organization_id, user_id, account_type)
        REFERENCES users (organization_id, user_id, account_type) ON DELETE RESTRICT
);

COMMENT ON COLUMN organization_memberships.status IS '1=PENDING, 2=ACTIVE, 3=SUSPENDED, 4=REVOKED';

CREATE TRIGGER trg_organization_memberships_set_updated_at
    BEFORE UPDATE ON organization_memberships
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE client_memberships (
    client_membership_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id      UUID NOT NULL,
    client_id            UUID NOT NULL,
    user_id               UUID NOT NULL,
    account_type         SMALLINT NOT NULL CHECK (account_type = 2),
    status               SMALLINT NOT NULL CHECK (status IN (1, 2, 3, 4)),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- cada conta externa pertence a um unico cliente (um cliente pode ter varias contas externas)
    UNIQUE (user_id),
    -- garante organizacao correta e account_type=2 (conta CLIENT) quando o vinculo existe
    FOREIGN KEY (organization_id, user_id, account_type)
        REFERENCES users (organization_id, user_id, account_type) ON DELETE RESTRICT,
    -- cliente vinculado deve pertencer a mesma organizacao (mesmo padrao de workspaces em 001_initial.sql)
    FOREIGN KEY (organization_id, client_id)
        REFERENCES clients (organization_id, client_id) ON DELETE RESTRICT
);

COMMENT ON COLUMN client_memberships.status IS '1=PENDING, 2=ACTIVE, 3=SUSPENDED, 4=REVOKED';

CREATE INDEX ix_client_memberships_organization_id_client_id ON client_memberships (organization_id, client_id);
CREATE INDEX ix_client_memberships_client_id ON client_memberships (client_id);

CREATE TRIGGER trg_client_memberships_set_updated_at
    BEFORE UPDATE ON client_memberships
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- === Autenticacao: convites, verificacao de e-mail, recuperacao de senha, sessoes ===
-- Estado modelado por timestamps de transicao (sem status SMALLINT redundante).
-- Aceite/consumo devem ser feitos por um UNICO UPDATE ... WHERE <ainda pendente> RETURNING ...
-- (protecao contra concorrencia via MVCC do Postgres, nao por leitura-depois-escrita na aplicacao).
-- Valores brutos de token nunca sao persistidos - somente o hash.

CREATE TABLE identity_invitations (
    invitation_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id      UUID NOT NULL REFERENCES organizations (organization_id) ON DELETE RESTRICT,
    invited_account_type SMALLINT NOT NULL CHECK (invited_account_type IN (1, 2)),
    client_id            UUID,
    email_normalized     TEXT NOT NULL
        CHECK (btrim(email_normalized) <> '')
        CHECK (email_normalized = lower(btrim(email_normalized)))
        CHECK (email_normalized !~ '\s'),
    issued_by             UUID NOT NULL REFERENCES users (user_id) ON DELETE RESTRICT,
    token_hash            TEXT NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at            TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    accepted_at           TIMESTAMPTZ,
    accepted_user_id      UUID REFERENCES users (user_id) ON DELETE RESTRICT,
    revoked_at            TIMESTAMPTZ,
    UNIQUE (token_hash),
    -- convite interno exige cliente nulo; convite externo exige cliente valido da mesma organizacao
    FOREIGN KEY (organization_id, client_id)
        REFERENCES clients (organization_id, client_id) ON DELETE RESTRICT,
    CHECK (
        (invited_account_type = 1 AND client_id IS NULL)
        OR (invited_account_type = 2 AND client_id IS NOT NULL)
    ),
    CHECK (accepted_at IS NULL OR revoked_at IS NULL),
    CHECK (accepted_at IS NULL OR accepted_user_id IS NOT NULL),
    CHECK (accepted_at IS NULL OR accepted_at >= created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

COMMENT ON COLUMN identity_invitations.invited_account_type IS '1=INTERNAL, 2=CLIENT';

CREATE INDEX ix_identity_invitations_organization_id ON identity_invitations (organization_id);

CREATE TABLE email_verification_tokens (
    verification_token_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID NOT NULL REFERENCES users (user_id) ON DELETE RESTRICT,
    target_email           TEXT NOT NULL
        CHECK (btrim(target_email) <> '')
        CHECK (target_email = btrim(target_email))
        CHECK (target_email !~ '\s'),
    token_hash             TEXT NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at             TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    consumed_at            TIMESTAMPTZ,
    revoked_at             TIMESTAMPTZ,
    UNIQUE (token_hash),
    CHECK (consumed_at IS NULL OR revoked_at IS NULL),
    CHECK (consumed_at IS NULL OR consumed_at >= created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX ix_email_verification_tokens_user_id ON email_verification_tokens (user_id);

CREATE TABLE password_reset_tokens (
    password_reset_token_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID NOT NULL REFERENCES users (user_id) ON DELETE RESTRICT,
    token_hash               TEXT NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at               TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    consumed_at              TIMESTAMPTZ,
    revoked_at               TIMESTAMPTZ,
    UNIQUE (token_hash),
    CHECK (consumed_at IS NULL OR revoked_at IS NULL),
    CHECK (consumed_at IS NULL OR consumed_at >= created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX ix_password_reset_tokens_user_id ON password_reset_tokens (user_id);

CREATE TABLE user_sessions (
    session_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id      UUID NOT NULL,
    user_id               UUID NOT NULL,
    session_token_hash   TEXT NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at           TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    last_seen_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at           TIMESTAMPTZ,
    UNIQUE (session_token_hash),
    -- organizacao da sessao deve ser a mesma da conta (users.organization_id e imutavel)
    FOREIGN KEY (organization_id, user_id)
        REFERENCES users (organization_id, user_id) ON DELETE RESTRICT,
    CHECK (last_seen_at >= created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX ix_user_sessions_organization_id_user_id ON user_sessions (organization_id, user_id);
