-- Nekkora Platform - schema base (nova arquitetura, separada da POC)
--
-- Codigos de status (SMALLINT, explicitos e permanentes - nao reordenar nem reutilizar):
--   organizations.status : 1=ACTIVE, 2=SUSPENDED, 3=CLOSED
--   clients.status       : 1=ACTIVE, 2=SUSPENDED, 3=ARCHIVED
--   workspaces.status    : 1=ACTIVE, 2=SUSPENDED, 3=ARCHIVED

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE organizations (
    organization_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL CHECK (btrim(name) <> ''),
    legal_name      TEXT CHECK (legal_name IS NULL OR btrim(legal_name) <> ''),
    status          SMALLINT NOT NULL CHECK (status IN (1, 2, 3)),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN organizations.status IS '1=ACTIVE, 2=SUSPENDED, 3=CLOSED';

CREATE TABLE clients (
    client_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations (organization_id) ON DELETE RESTRICT,
    name            TEXT NOT NULL CHECK (btrim(name) <> ''),
    status          SMALLINT NOT NULL CHECK (status IN (1, 2, 3)),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, client_id)
);

COMMENT ON COLUMN clients.status IS '1=ACTIVE, 2=SUSPENDED, 3=ARCHIVED';

CREATE TABLE workspaces (
    workspace_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations (organization_id) ON DELETE RESTRICT,
    client_id       UUID,
    name            TEXT NOT NULL CHECK (btrim(name) <> ''),
    description     TEXT CHECK (description IS NULL OR btrim(description) <> ''),
    status          SMALLINT NOT NULL CHECK (status IN (1, 2, 3)),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, client_id)
        REFERENCES clients (organization_id, client_id) ON DELETE RESTRICT
);

COMMENT ON COLUMN workspaces.status IS '1=ACTIVE, 2=SUSPENDED, 3=ARCHIVED';
COMMENT ON COLUMN workspaces.client_id IS 'NULL para workspace interno (sem cliente)';

-- FK composta nao cria indice automatico no lado referenciador; necessario para o RESTRICT
-- e para consultas "workspaces da org X" / "workspaces do cliente Y na org X".
CREATE INDEX ix_workspaces_organization_id_client_id ON workspaces (organization_id, client_id);

-- Consulta "workspaces do cliente Y" sem o organization_id em mao.
-- Uso provisorio: nao autoriza consultas de aplicacao sem escopo por organization_id (tenant).
-- As consultas da aplicacao devem continuar delimitadas pelo tenant; reavaliar necessidade deste indice depois.
CREATE INDEX ix_workspaces_client_id ON workspaces (client_id) WHERE client_id IS NOT NULL;

COMMENT ON INDEX ix_workspaces_client_id IS
    'Provisorio - nao autoriza consultas sem escopo por organization_id (tenant). Reavaliar necessidade.';

-- updated_at e mantido pelo banco (nao pela aplicacao), para cobrir tambem
-- alteracoes feitas por scripts fora da camada de aplicacao.
CREATE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_organizations_set_updated_at
    BEFORE UPDATE ON organizations
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_clients_set_updated_at
    BEFORE UPDATE ON clients
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_workspaces_set_updated_at
    BEFORE UPDATE ON workspaces
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();
