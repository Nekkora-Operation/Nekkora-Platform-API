-- Nekkora Platform - cadastro de automacoes e versoes publicadas do comando.
-- Depende de 001_initial.sql, 002_identity.sql e 003_access_control.sql.
--
-- Codigos de status (SMALLINT, explicitos e permanentes - nao reordenar nem reutilizar):
--   automations.status         : 1=ACTIVE, 2=SUSPENDED, 3=ARCHIVED (administrativo, reversivel)
--   automation_versions.status : 1=DRAFT, 2=PUBLISHED (DRAFT->PUBLISHED e terminal, uma via)
--
-- automations.status e reversivel (ex.: ARCHIVED -> ACTIVE), por acao explicita de quem
-- possui automation.manage, sem disparar execucoes automaticamente. Diferente de
-- automation_versions: uma vez publicada, a linha fica congelada por inteiro.

-- === Novas permissoes no catalogo da 003 (INSERT aditivo, sem alterar 003_access_control.sql) ===

INSERT INTO permissions (code, description) VALUES
    ('automation.manage', 'Cadastrar, editar rascunho, suspender e arquivar automacoes'),
    ('automation.publish', 'Publicar versao de automacao');

-- ORG_ADMIN (organizacao) e WORKSPACE_ADMIN (proprio workspace), respeitando seus escopos.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r, permissions p
WHERE r.code IN ('ORG_ADMIN', 'WORKSPACE_ADMIN')
  AND p.code IN ('automation.manage', 'automation.publish');

-- === Validacao estrutural do contrato de comando (command_json) ===
--
-- Usada apenas quando a versao esta sendo publicada (DRAFT pode ficar incompleto).
-- Sem validacao semantica (existencia do artefato, runtime suportado, resolucao de
-- secret_bindings) - isso fica para a aplicacao e para blocos futuros (ex.: secrets).
--
-- Cuidado deliberado com duas armadilhas do Postgres:
--   1) CHECK tambem passa quando a expressao resulta em NULL (nao so quando e FALSE) -
--      por isso a funcao encerra com "... IS TRUE", nunca com a expressao boolean nua.
--   2) jsonb_array_elements() lanca ERRO em tempo de execucao se o valor nao for array
--      (diferente de ->/->>, que sao tolerantes e retornam NULL) - por isso essa checagem
--      fica dentro de um CASE que só a invoca depois de confirmar o tipo.

CREATE FUNCTION is_valid_relative_entrypoint(entrypoint TEXT) RETURNS BOOLEAN
LANGUAGE sql IMMUTABLE AS $$
    SELECT (
        entrypoint IS NOT NULL
        AND length(btrim(entrypoint)) > 0
        AND entrypoint !~ '\\'
        AND entrypoint !~ '^[A-Za-z]:'
        AND left(entrypoint, 1) <> '/'
        AND NOT EXISTS (
            SELECT 1 FROM unnest(string_to_array(entrypoint, '/')) AS seg
            WHERE seg = '.' OR seg = '..'
        )
    ) IS TRUE;
$$;

CREATE FUNCTION is_valid_command_contract(command_json JSONB) RETURNS BOOLEAN
LANGUAGE sql IMMUTABLE AS $$
    SELECT (
        (command_json ? 'contract_version')
        AND (command_json ? 'artifact')
        AND (command_json ? 'runtime')
        AND (command_json ? 'entrypoint')
        AND (command_json ? 'arguments')
        AND (command_json ? 'parameters')
        AND (command_json ? 'secret_bindings')
        AND (command_json -> 'contract_version') = to_jsonb(1)
        AND jsonb_typeof(command_json -> 'artifact') = 'object'
        AND jsonb_typeof(command_json -> 'artifact' -> 'reference') = 'string'
        AND length(btrim(command_json -> 'artifact' ->> 'reference')) > 0
        AND jsonb_typeof(command_json -> 'artifact' -> 'sha256') = 'string'
        AND (command_json -> 'artifact' ->> 'sha256') ~ '^[0-9a-f]{64}$'
        AND jsonb_typeof(command_json -> 'runtime') = 'string'
        AND length(btrim(command_json ->> 'runtime')) > 0
        AND jsonb_typeof(command_json -> 'entrypoint') = 'string'
        AND length(btrim(command_json ->> 'entrypoint')) > 0
        AND is_valid_relative_entrypoint(command_json ->> 'entrypoint')
        AND jsonb_typeof(command_json -> 'arguments') = 'array'
        AND CASE WHEN jsonb_typeof(command_json -> 'arguments') = 'array' THEN
                NOT EXISTS (
                    SELECT 1 FROM jsonb_array_elements(command_json -> 'arguments') AS elem
                    WHERE jsonb_typeof(elem) <> 'string'
                )
            ELSE FALSE
            END
        AND jsonb_typeof(command_json -> 'parameters') = 'object'
        AND jsonb_typeof(command_json -> 'secret_bindings') = 'object'
    ) IS TRUE;
$$;

-- === Automations ===

CREATE TABLE automations (
    automation_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id          UUID NOT NULL REFERENCES organizations (organization_id) ON DELETE RESTRICT,
    workspace_id              UUID NOT NULL,
    name                      TEXT NOT NULL CHECK (btrim(name) <> ''),
    description                TEXT CHECK (description IS NULL OR btrim(description) <> ''),
    status                     SMALLINT NOT NULL CHECK (status IN (1, 2, 3)),
    created_by_membership_id   UUID NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, workspace_id, automation_id),
    FOREIGN KEY (organization_id, workspace_id) REFERENCES workspaces (organization_id, workspace_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, created_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT
);

COMMENT ON COLUMN automations.status IS '1=ACTIVE, 2=SUSPENDED, 3=ARCHIVED - reversivel, sem disparo automatico de execucoes';

CREATE FUNCTION prevent_automation_origin_change() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.automation_id <> OLD.automation_id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.created_at <> OLD.created_at
       OR NEW.created_by_membership_id <> OLD.created_by_membership_id THEN
        RAISE EXCEPTION 'origem da automacao e imutavel (automation_id=%)', OLD.automation_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_automations_prevent_origin_change
    BEFORE UPDATE ON automations
    FOR EACH ROW
    EXECUTE FUNCTION prevent_automation_origin_change();

CREATE TRIGGER trg_automations_set_updated_at
    BEFORE UPDATE ON automations
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_automations_prevent_delete
    BEFORE DELETE ON automations
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_automations_prevent_truncate
    BEFORE TRUNCATE ON automations
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === Automation versions ===

CREATE TABLE automation_versions (
    automation_version_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id            UUID NOT NULL,
    workspace_id                UUID NOT NULL,
    automation_id                UUID NOT NULL,
    version_number                INTEGER NOT NULL CHECK (version_number > 0),
    status                        SMALLINT NOT NULL CHECK (status IN (1, 2)),
    command_json                  JSONB NOT NULL CHECK (jsonb_typeof(command_json) = 'object'),
    created_by_membership_id      UUID NOT NULL,
    published_by_membership_id    UUID,
    published_at                   TIMESTAMPTZ,
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (automation_id, version_number),
    UNIQUE (organization_id, workspace_id, automation_id, automation_version_id),
    FOREIGN KEY (organization_id, workspace_id, automation_id)
        REFERENCES automations (organization_id, workspace_id, automation_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, created_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, published_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK ((published_at IS NULL) = (published_by_membership_id IS NULL)),
    CHECK ((status = 2) = (published_at IS NOT NULL)),
    CHECK (published_at IS NULL OR published_at >= created_at),
    CHECK (status <> 2 OR is_valid_command_contract(command_json) IS TRUE)
);

COMMENT ON COLUMN automation_versions.status IS '1=DRAFT, 2=PUBLISHED - transicao unica, terminal';
COMMENT ON COLUMN automation_versions.command_json IS 'contrato v1: contract_version, artifact.reference, artifact.sha256, runtime, entrypoint, arguments, parameters, secret_bindings';

-- Alocacao do numero de versao e responsabilidade do banco, nao da aplicacao: trava a
-- linha da automacao e calcula o proximo numero, para que duas criacoes concorrentes
-- sempre terminem com numeros distintos (nenhuma falha por corrida).
CREATE FUNCTION allocate_automation_version_number() RETURNS TRIGGER AS $$
BEGIN
    PERFORM 1 FROM automations WHERE automation_id = NEW.automation_id FOR UPDATE;
    NEW.version_number := COALESCE(
        (SELECT MAX(version_number) FROM automation_versions WHERE automation_id = NEW.automation_id),
        0
    ) + 1;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_automation_versions_allocate_number
    BEFORE INSERT ON automation_versions
    FOR EACH ROW
    EXECUTE FUNCTION allocate_automation_version_number();

-- Uma vez publicada (OLD.status = 2), a linha fica totalmente congelada - nenhum UPDATE
-- e aceito, nem mesmo dos proprios campos de publicacao. Enquanto DRAFT, so a origem
-- (inclusive automation_version_id, version_number e created_at) e imutavel; comando e
-- status continuam editaveis ate a publicacao.
CREATE FUNCTION prevent_automation_version_change() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 2 THEN
        RAISE EXCEPTION 'versao publicada e imutavel (automation_version_id=%)', OLD.automation_version_id;
    END IF;
    IF NEW.automation_version_id <> OLD.automation_version_id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.automation_id <> OLD.automation_id
       OR NEW.version_number <> OLD.version_number
       OR NEW.created_at <> OLD.created_at
       OR NEW.created_by_membership_id <> OLD.created_by_membership_id THEN
        RAISE EXCEPTION 'origem da versao e imutavel (automation_version_id=%)', OLD.automation_version_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_automation_versions_prevent_origin_change
    BEFORE UPDATE ON automation_versions
    FOR EACH ROW
    EXECUTE FUNCTION prevent_automation_version_change();

CREATE TRIGGER trg_automation_versions_set_updated_at
    BEFORE UPDATE ON automation_versions
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_automation_versions_prevent_delete
    BEFORE DELETE ON automation_versions
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_automation_versions_prevent_truncate
    BEFORE TRUNCATE ON automation_versions
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

CREATE INDEX ix_automation_versions_automation_id ON automation_versions (automation_id);
