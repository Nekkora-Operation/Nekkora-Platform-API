-- Nekkora Platform - parametros nao sensiveis e referencias de secrets por workspace,
-- vinculados a versoes de automacao. Depende de 001-006 (toca automation_versions da 005
-- e a criacao/reserva de execucao da 006, por CREATE OR REPLACE FUNCTION aditivo - ver nota
-- no final deste arquivo).
--
-- Codigos (SMALLINT, explicitos e permanentes - nao reordenar nem reutilizar):
--   workspace_parameters.status              : 1=ACTIVE, 2=ARCHIVED (definitivo, sem reversao)
--   workspace_secrets.revocation_reason      : 1=REFERENCE_REPLACED, 2=COMPROMISED, 3=ADMINISTRATIVE
--   automation_version_bindings.binding_kind : 1=PARAMETER, 2=SECRET
--   workspace_configuration_events.event_type:
--     1=PARAMETER_CREATED, 2=PARAMETER_DESCRIPTION_CHANGED, 3=PARAMETER_VALUE_SET,
--     4=PARAMETER_ARCHIVED, 5=SECRET_CREATED, 6=SECRET_DESCRIPTION_CHANGED, 7=SECRET_REVOKED,
--     8=BINDING_CREATED, 9=BINDING_UPDATED, 10=BINDING_REMOVED
--
-- Regras centrais:
--   - Parametro: definicao (workspace_parameters) e valor (workspace_parameter_values,
--     append-only) sao tabelas separadas, para que uma versao publicada fixe um
--     parameter_value_id imutavel - nunca um campo que o cadastro compartilhado altere depois.
--   - Secret: a tabela so guarda a referencia logica (provider_key/provider_reference). O
--     valor em si nunca e gravado aqui, no command_json/command_snapshot ou em logs -
--     resolucao e responsabilidade de uma API autorizada, fora deste schema.
--   - Correspondencia exata com o comando: ao publicar, command_json.parameters/
--     secret_bindings devem corresponder biunivocamente aos vinculos da versao
--     (is_command_bindings_consistent). Vinculo-base sem extensao e checado a parte,
--     sem depender de um JOIN que poderia escondê-lo.
--   - Locks: alocar numero de versao (005) trava a linha de automations; editar vinculos
--     e publicar (aqui) travam a linha de automation_versions - sao coordenacoes distintas.
--     Publicacao trava as dependencias (workspace_parameters/workspace_secrets) referenciadas
--     em ordem fixa por tipo e depois por UUID, para nao disputar lock em ordem diferente de
--     um arquivamento/revogacao concorrente.
--   - Ator de auditoria: toda trigger aqui que grava em workspace_configuration_events le
--     current_setting('nekkora.actor_membership_id'). A aplicacao deve executar
--     SET LOCAL nekkora.actor_membership_id = '<uuid-do-membership>' no inicio de qualquer
--     transacao que crie, edite ou remova parametro/valor/secret/vinculo - inclusive DELETE
--     de vinculo em rascunho, que nao tem NEW nem coluna propria de "quem apagou". Sem isso
--     definido, current_setting() lanca erro (fail-closed, nunca um evento anonimo).

-- === Novas permissoes no catalogo da 003 (INSERT aditivo) ===

INSERT INTO permissions (code, description) VALUES
    ('workspace_parameter.read', 'Consultar definicoes e valores historicos de parametros do workspace'),
    ('workspace_parameter.manage', 'Cadastrar parametros, definir valores e arquivar'),
    ('workspace_secret.read', 'Consultar metadados de secrets do workspace (nunca o valor)'),
    ('workspace_secret.manage', 'Cadastrar e revogar referencias de secrets');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r, permissions p
WHERE r.code IN ('ORG_ADMIN', 'WORKSPACE_ADMIN')
  AND p.code IN ('workspace_parameter.read', 'workspace_parameter.manage',
                 'workspace_secret.read', 'workspace_secret.manage');

-- === Evolucao aditiva de 005: publicacao so pode nascer DRAFT ===
--
-- Sem isto, um INSERT direto com status=PUBLISHED contornaria a checagem de
-- correspondencia de vinculos definida mais abaixo, que so roda na transicao de UPDATE.

CREATE FUNCTION enforce_automation_version_draft_creation() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status <> 1 THEN
        RAISE EXCEPTION 'versao de automacao deve nascer DRAFT (automation_version_id=%)', NEW.automation_version_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_automation_versions_enforce_draft_creation
    BEFORE INSERT ON automation_versions
    FOR EACH ROW
    EXECUTE FUNCTION enforce_automation_version_draft_creation();

-- === workspace_parameters ===

CREATE TABLE workspace_parameters (
    parameter_id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id            UUID NOT NULL,
    workspace_id                UUID NOT NULL,
    name                        TEXT NOT NULL CHECK (name ~ '^[A-Z_][A-Z0-9_]{0,127}$'),
    description                  TEXT CHECK (description IS NULL OR btrim(description) <> ''),
    status                        SMALLINT NOT NULL CHECK (status IN (1, 2)),
    created_by_membership_id      UUID NOT NULL,
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at                     TIMESTAMPTZ,
    archived_by_membership_id       UUID,

    UNIQUE (organization_id, workspace_id, name),
    UNIQUE (organization_id, workspace_id, parameter_id),
    FOREIGN KEY (organization_id, workspace_id) REFERENCES workspaces (organization_id, workspace_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, created_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, archived_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK ((archived_at IS NULL) = (archived_by_membership_id IS NULL)),
    CHECK ((status = 2) = (archived_at IS NOT NULL)),
    CHECK (archived_at IS NULL OR archived_at >= created_at)
);

COMMENT ON COLUMN workspace_parameters.status IS '1=ACTIVE, 2=ARCHIVED - definitivo; nome permanece reservado, novo cadastro exige outro nome';

-- Arquivado (OLD.archived_at preenchido) congela a linha inteira - sem isto, limpar
-- archived_at/archived_by reabriria um parametro arquivado (mesma classe de bug corrigida
-- em 003/004). Enquanto ACTIVE, so a origem (inclusive o nome) e imutavel.
CREATE FUNCTION prevent_workspace_parameter_change() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.archived_at IS NOT NULL THEN
        RAISE EXCEPTION 'parametro arquivado e imutavel (parameter_id=%)', OLD.parameter_id;
    END IF;
    IF NEW.parameter_id <> OLD.parameter_id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.name <> OLD.name
       OR NEW.created_at <> OLD.created_at
       OR NEW.created_by_membership_id <> OLD.created_by_membership_id THEN
        RAISE EXCEPTION 'origem do parametro e imutavel (parameter_id=%)', OLD.parameter_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_workspace_parameters_prevent_change
    BEFORE UPDATE ON workspace_parameters
    FOR EACH ROW
    EXECUTE FUNCTION prevent_workspace_parameter_change();

CREATE TRIGGER trg_workspace_parameters_set_updated_at
    BEFORE UPDATE ON workspace_parameters
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_workspace_parameters_prevent_delete
    BEFORE DELETE ON workspace_parameters
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_workspace_parameters_prevent_truncate
    BEFORE TRUNCATE ON workspace_parameters
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === workspace_parameter_values (historico append-only) ===

CREATE TABLE workspace_parameter_values (
    parameter_value_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         UUID NOT NULL,
    workspace_id             UUID NOT NULL,
    parameter_id              UUID NOT NULL,
    value                      JSONB NOT NULL CHECK (jsonb_typeof(value) IN ('string', 'number', 'boolean')),
    value_number                BIGINT NOT NULL CHECK (value_number > 0),
    set_by_membership_id          UUID NOT NULL,
    set_at                          TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (parameter_id, value_number),
    UNIQUE (organization_id, workspace_id, parameter_id, parameter_value_id),
    FOREIGN KEY (organization_id, workspace_id, parameter_id)
        REFERENCES workspace_parameters (organization_id, workspace_id, parameter_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, set_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK (octet_length(value::text) <= 16384)
);

-- Numero e responsabilidade do banco, alocado sob lock na linha do parametro (mesmo padrao
-- de allocate_automation_version_number, 005) - marcador explicito de "ultima linha", sem
-- depender de ordem de insercao. Uma transacao desfeita pode deixar um numero disponivel de
-- novo para a proxima insercao committed - isso e esperado, nao uma promessa de nao-reuso.
CREATE FUNCTION allocate_workspace_parameter_value_number() RETURNS TRIGGER AS $$
DECLARE
    parameter_status SMALLINT;
BEGIN
    SELECT status INTO parameter_status FROM workspace_parameters WHERE parameter_id = NEW.parameter_id FOR UPDATE;
    IF parameter_status IS NULL THEN
        RAISE EXCEPTION 'parametro inexistente (parameter_id=%)', NEW.parameter_id;
    END IF;
    IF parameter_status <> 1 THEN
        RAISE EXCEPTION 'parametro arquivado nao aceita novo valor (parameter_id=%)', NEW.parameter_id;
    END IF;
    NEW.value_number := COALESCE(
        (SELECT MAX(value_number) FROM workspace_parameter_values WHERE parameter_id = NEW.parameter_id),
        0
    ) + 1;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_workspace_parameter_values_allocate_number
    BEFORE INSERT ON workspace_parameter_values
    FOR EACH ROW
    EXECUTE FUNCTION allocate_workspace_parameter_value_number();

CREATE TRIGGER trg_workspace_parameter_values_prevent_update
    BEFORE UPDATE ON workspace_parameter_values
    FOR EACH ROW
    EXECUTE FUNCTION prevent_update();

CREATE TRIGGER trg_workspace_parameter_values_prevent_delete
    BEFORE DELETE ON workspace_parameter_values
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_workspace_parameter_values_prevent_truncate
    BEFORE TRUNCATE ON workspace_parameter_values
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === workspace_secrets ===

CREATE TABLE workspace_secrets (
    secret_id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             UUID NOT NULL,
    workspace_id                 UUID NOT NULL,
    name                           TEXT NOT NULL CHECK (name ~ '^[A-Z_][A-Z0-9_]{0,127}$'),
    provider_key                    TEXT NOT NULL CHECK (btrim(provider_key) <> ''),
    provider_reference                TEXT NOT NULL CHECK (btrim(provider_reference) <> ''),
    description                        TEXT CHECK (description IS NULL OR btrim(description) <> ''),
    created_by_membership_id             UUID NOT NULL,
    created_at                             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                               TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at                                 TIMESTAMPTZ,
    revoked_by_membership_id                     UUID,
    revocation_reason                              SMALLINT CHECK (revocation_reason IN (1, 2, 3)),

    UNIQUE (organization_id, workspace_id, secret_id),
    FOREIGN KEY (organization_id, workspace_id) REFERENCES workspaces (organization_id, workspace_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, created_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, revoked_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK ((revoked_at IS NULL) = (revoked_by_membership_id IS NULL)),
    CHECK ((revoked_at IS NULL) = (revocation_reason IS NULL)),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

COMMENT ON COLUMN workspace_secrets.revocation_reason IS '1=REFERENCE_REPLACED, 2=COMPROMISED, 3=ADMINISTRATIVE';
COMMENT ON COLUMN workspace_secrets.provider_reference IS 'ponteiro opaco no provedor externo - o banco nao interpreta formato nem guarda o valor do secret';

-- Nome reutilizavel apos revogacao (mesmo padrao de agent_credentials, 004) - reidentificacao
-- nunca e implicita: vinculos antigos guardam secret_id, nunca resolvem de novo pelo nome.
CREATE UNIQUE INDEX ux_workspace_secrets_active_name
    ON workspace_secrets (organization_id, workspace_id, name) WHERE revoked_at IS NULL;

-- Revogado (OLD.revoked_at preenchido) congela a linha inteira. provider_key/provider_reference
-- sao imutaveis mesmo antes da revogacao - rotacao no provedor nunca toca esta tabela; se a
-- referencia em si precisar migrar, o caminho e revogar e recriar com o mesmo nome.
CREATE FUNCTION prevent_workspace_secret_change() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'secret revogado e imutavel (secret_id=%)', OLD.secret_id;
    END IF;
    IF NEW.secret_id <> OLD.secret_id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.name <> OLD.name
       OR NEW.provider_key <> OLD.provider_key
       OR NEW.provider_reference <> OLD.provider_reference
       OR NEW.created_at <> OLD.created_at
       OR NEW.created_by_membership_id <> OLD.created_by_membership_id THEN
        RAISE EXCEPTION 'origem do secret e imutavel (secret_id=%)', OLD.secret_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_workspace_secrets_prevent_change
    BEFORE UPDATE ON workspace_secrets
    FOR EACH ROW
    EXECUTE FUNCTION prevent_workspace_secret_change();

CREATE TRIGGER trg_workspace_secrets_set_updated_at
    BEFORE UPDATE ON workspace_secrets
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_workspace_secrets_prevent_delete
    BEFORE DELETE ON workspace_secrets
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_workspace_secrets_prevent_truncate
    BEFORE TRUNCATE ON workspace_secrets
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === workspace_configuration_events (auditoria, append-only) ===
--
-- binding_id NAO tem FK: vinculos em rascunho podem ser excluidos, e o evento precisa
-- sobreviver a remocao guardando o identificador historico. As demais referencias
-- (parametro, valor, secret, versao) sao permanentes (nunca excluidas) e continuam com FK.

CREATE TABLE workspace_configuration_events (
    event_id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id               UUID NOT NULL,
    workspace_id                   UUID NOT NULL,
    event_type                      SMALLINT NOT NULL CHECK (event_type BETWEEN 1 AND 10),
    performed_by_membership_id        UUID NOT NULL,
    occurred_at                         TIMESTAMPTZ NOT NULL DEFAULT now(),
    parameter_id                          UUID,
    parameter_value_id                      UUID,
    secret_id                                 UUID,
    automation_version_id                       UUID,
    binding_id                                    UUID,
    detail                                          JSONB,

    FOREIGN KEY (organization_id, workspace_id) REFERENCES workspaces (organization_id, workspace_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, performed_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, workspace_id, parameter_id)
        REFERENCES workspace_parameters (organization_id, workspace_id, parameter_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, workspace_id, parameter_id, parameter_value_id)
        REFERENCES workspace_parameter_values (organization_id, workspace_id, parameter_id, parameter_value_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, workspace_id, secret_id)
        REFERENCES workspace_secrets (organization_id, workspace_id, secret_id) ON DELETE RESTRICT,
    FOREIGN KEY (automation_version_id) REFERENCES automation_versions (automation_version_id) ON DELETE RESTRICT,

    CHECK ((
        CASE event_type
            WHEN 1 THEN parameter_id IS NOT NULL AND parameter_value_id IS NULL AND secret_id IS NULL AND automation_version_id IS NULL AND binding_id IS NULL
            WHEN 2 THEN parameter_id IS NOT NULL AND parameter_value_id IS NULL AND secret_id IS NULL AND automation_version_id IS NULL AND binding_id IS NULL
            WHEN 3 THEN parameter_id IS NOT NULL AND parameter_value_id IS NOT NULL AND secret_id IS NULL AND automation_version_id IS NULL AND binding_id IS NULL
            WHEN 4 THEN parameter_id IS NOT NULL AND parameter_value_id IS NULL AND secret_id IS NULL AND automation_version_id IS NULL AND binding_id IS NULL
            WHEN 5 THEN secret_id IS NOT NULL AND parameter_id IS NULL AND parameter_value_id IS NULL AND automation_version_id IS NULL AND binding_id IS NULL
            WHEN 6 THEN secret_id IS NOT NULL AND parameter_id IS NULL AND parameter_value_id IS NULL AND automation_version_id IS NULL AND binding_id IS NULL
            WHEN 7 THEN secret_id IS NOT NULL AND parameter_id IS NULL AND parameter_value_id IS NULL AND automation_version_id IS NULL AND binding_id IS NULL
            WHEN 8 THEN automation_version_id IS NOT NULL AND binding_id IS NOT NULL AND ((parameter_id IS NOT NULL AND secret_id IS NULL) OR (parameter_id IS NULL AND secret_id IS NOT NULL))
            WHEN 9 THEN automation_version_id IS NOT NULL AND binding_id IS NOT NULL AND ((parameter_id IS NOT NULL AND secret_id IS NULL) OR (parameter_id IS NULL AND secret_id IS NOT NULL))
            -- 10 (BINDING_REMOVED) tambem aceita parameter_id/secret_id ambos nulos: um vinculo-base
            -- pode ser excluido sem nunca ter tido extensao (ex.: limpeza apos falha na criacao em
            -- duas etapas) - a auditoria da remocao nao pode exigir um destino que nunca existiu.
            WHEN 10 THEN automation_version_id IS NOT NULL AND binding_id IS NOT NULL AND NOT (parameter_id IS NOT NULL AND secret_id IS NOT NULL)
            ELSE FALSE
        END
    ) IS TRUE)
);

COMMENT ON COLUMN workspace_configuration_events.event_type IS
    '1=PARAMETER_CREATED, 2=PARAMETER_DESCRIPTION_CHANGED, 3=PARAMETER_VALUE_SET, 4=PARAMETER_ARCHIVED, 5=SECRET_CREATED, 6=SECRET_DESCRIPTION_CHANGED, 7=SECRET_REVOKED, 8=BINDING_CREATED, 9=BINDING_UPDATED, 10=BINDING_REMOVED';
COMMENT ON COLUMN workspace_configuration_events.binding_id IS 'sem FK deliberadamente - vinculo pode ter sido excluido em rascunho; identificador historico';
COMMENT ON COLUMN workspace_configuration_events.detail IS 'metadados pequenos e nao sensiveis (ex.: codigo do motivo de revogacao) - nunca o value do parametro nem dado de secret';

CREATE TRIGGER trg_workspace_configuration_events_prevent_update
    BEFORE UPDATE ON workspace_configuration_events
    FOR EACH ROW
    EXECUTE FUNCTION prevent_update();

CREATE TRIGGER trg_workspace_configuration_events_prevent_delete
    BEFORE DELETE ON workspace_configuration_events
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_workspace_configuration_events_prevent_truncate
    BEFORE TRUNCATE ON workspace_configuration_events
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

CREATE INDEX ix_workspace_configuration_events_automation_version_id
    ON workspace_configuration_events (automation_version_id) WHERE automation_version_id IS NOT NULL;

CREATE INDEX ix_workspace_configuration_events_secret_id
    ON workspace_configuration_events (secret_id) WHERE secret_id IS NOT NULL;

-- === Auditoria de workspace_parameters / workspace_parameter_values / workspace_secrets ===

CREATE FUNCTION audit_workspace_parameter_insert() RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO workspace_configuration_events
        (organization_id, workspace_id, event_type, performed_by_membership_id, parameter_id)
    VALUES
        (NEW.organization_id, NEW.workspace_id, 1, current_setting('nekkora.actor_membership_id')::uuid, NEW.parameter_id);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_workspace_parameters_audit_insert
    AFTER INSERT ON workspace_parameters
    FOR EACH ROW
    EXECUTE FUNCTION audit_workspace_parameter_insert();

CREATE FUNCTION audit_workspace_parameter_update() RETURNS TRIGGER AS $$
DECLARE
    actor UUID := current_setting('nekkora.actor_membership_id')::uuid;
BEGIN
    IF NEW.description IS DISTINCT FROM OLD.description THEN
        INSERT INTO workspace_configuration_events
            (organization_id, workspace_id, event_type, performed_by_membership_id, parameter_id)
        VALUES (NEW.organization_id, NEW.workspace_id, 2, actor, NEW.parameter_id);
    END IF;
    IF NEW.archived_at IS NOT NULL AND OLD.archived_at IS NULL THEN
        INSERT INTO workspace_configuration_events
            (organization_id, workspace_id, event_type, performed_by_membership_id, parameter_id)
        VALUES (NEW.organization_id, NEW.workspace_id, 4, actor, NEW.parameter_id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_workspace_parameters_audit_update
    AFTER UPDATE ON workspace_parameters
    FOR EACH ROW
    EXECUTE FUNCTION audit_workspace_parameter_update();

CREATE FUNCTION audit_workspace_parameter_value_insert() RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO workspace_configuration_events
        (organization_id, workspace_id, event_type, performed_by_membership_id, parameter_id, parameter_value_id)
    VALUES
        (NEW.organization_id, NEW.workspace_id, 3, current_setting('nekkora.actor_membership_id')::uuid, NEW.parameter_id, NEW.parameter_value_id);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_workspace_parameter_values_audit_insert
    AFTER INSERT ON workspace_parameter_values
    FOR EACH ROW
    EXECUTE FUNCTION audit_workspace_parameter_value_insert();

CREATE FUNCTION audit_workspace_secret_insert() RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO workspace_configuration_events
        (organization_id, workspace_id, event_type, performed_by_membership_id, secret_id)
    VALUES
        (NEW.organization_id, NEW.workspace_id, 5, current_setting('nekkora.actor_membership_id')::uuid, NEW.secret_id);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_workspace_secrets_audit_insert
    AFTER INSERT ON workspace_secrets
    FOR EACH ROW
    EXECUTE FUNCTION audit_workspace_secret_insert();

CREATE FUNCTION audit_workspace_secret_update() RETURNS TRIGGER AS $$
DECLARE
    actor UUID := current_setting('nekkora.actor_membership_id')::uuid;
BEGIN
    IF NEW.description IS DISTINCT FROM OLD.description THEN
        INSERT INTO workspace_configuration_events
            (organization_id, workspace_id, event_type, performed_by_membership_id, secret_id)
        VALUES (NEW.organization_id, NEW.workspace_id, 6, actor, NEW.secret_id);
    END IF;
    IF NEW.revoked_at IS NOT NULL AND OLD.revoked_at IS NULL THEN
        INSERT INTO workspace_configuration_events
            (organization_id, workspace_id, event_type, performed_by_membership_id, secret_id, detail)
        VALUES (NEW.organization_id, NEW.workspace_id, 7, actor, NEW.secret_id, jsonb_build_object('revocation_reason', NEW.revocation_reason));
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_workspace_secrets_audit_update
    AFTER UPDATE ON workspace_secrets
    FOR EACH ROW
    EXECUTE FUNCTION audit_workspace_secret_update();

-- === Vinculo com a versao: tabela compartilhada + duas extensoes discriminadas ===
--
-- Mesmo padrao de (role_id, scope) da 003: uma tabela-base com o nome do vinculo (unico por
-- versao, nos dois tipos ao mesmo tempo) e duas extensoes, cada uma restrita ao seu
-- binding_kind pela FK composta. PK da extensao = binding_id (nao surrogate), garantindo no
-- maximo uma extensao por vinculo.

CREATE TABLE automation_version_bindings (
    binding_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id          UUID NOT NULL,
    workspace_id              UUID NOT NULL,
    automation_id              UUID NOT NULL,
    automation_version_id        UUID NOT NULL,
    binding_kind                  SMALLINT NOT NULL CHECK (binding_kind IN (1, 2)),
    binding_name                    TEXT NOT NULL CHECK (binding_name ~ '^[A-Z_][A-Z0-9_]{0,127}$'),
    created_at                        TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (automation_version_id, binding_name),
    UNIQUE (binding_id, binding_kind, organization_id, workspace_id, automation_version_id),
    FOREIGN KEY (organization_id, workspace_id, automation_id, automation_version_id)
        REFERENCES automation_versions (organization_id, workspace_id, automation_id, automation_version_id) ON DELETE RESTRICT
);

COMMENT ON COLUMN automation_version_bindings.binding_kind IS '1=PARAMETER, 2=SECRET - discriminador da extensao correspondente';
COMMENT ON COLUMN automation_version_bindings.binding_name IS 'chave em command_json.parameters ou command_json.secret_bindings - unica por versao entre os dois tipos';

-- Trava a versao (FOR UPDATE) antes de qualquer mutacao de vinculo - so aceita enquanto
-- DRAFT, e coordena com a publicacao (que trava a mesma linha). Contexto (organizacao,
-- workspace, automacao, versao, tipo) e imutavel: trocar de tipo exige excluir e recriar.
CREATE FUNCTION assert_automation_version_is_draft(p_automation_version_id UUID) RETURNS VOID AS $$
DECLARE
    v_status SMALLINT;
BEGIN
    SELECT status INTO v_status FROM automation_versions WHERE automation_version_id = p_automation_version_id FOR UPDATE;
    IF v_status IS NULL THEN
        RAISE EXCEPTION 'versao de automacao inexistente (automation_version_id=%)', p_automation_version_id;
    END IF;
    IF v_status <> 1 THEN
        RAISE EXCEPTION 'vinculos so podem ser alterados enquanto a versao esta em rascunho (automation_version_id=%)', p_automation_version_id;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION enforce_binding_mutation_in_draft() RETURNS TRIGGER AS $$
BEGIN
    PERFORM assert_automation_version_is_draft(COALESCE(NEW.automation_version_id, OLD.automation_version_id));

    IF TG_OP = 'UPDATE' THEN
        IF NEW.binding_id <> OLD.binding_id
           OR NEW.binding_kind <> OLD.binding_kind
           OR NEW.organization_id <> OLD.organization_id
           OR NEW.workspace_id <> OLD.workspace_id
           OR NEW.automation_id <> OLD.automation_id
           OR NEW.automation_version_id <> OLD.automation_version_id THEN
            RAISE EXCEPTION 'contexto do vinculo e imutavel - exclua e recrie (binding_id=%)', OLD.binding_id;
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_automation_version_bindings_enforce_draft
    BEFORE INSERT OR UPDATE OR DELETE ON automation_version_bindings
    FOR EACH ROW
    EXECUTE FUNCTION enforce_binding_mutation_in_draft();

-- Captura o conteudo do vinculo (ainda presente - roda ANTES da CASCADE remover a extensao)
-- para o evento BINDING_REMOVED sobreviver a exclusao.
CREATE FUNCTION audit_automation_version_binding_delete() RETURNS TRIGGER AS $$
DECLARE
    actor UUID := current_setting('nekkora.actor_membership_id')::uuid;
    v_parameter_id UUID;
    v_parameter_value_id UUID;
    v_secret_id UUID;
BEGIN
    IF OLD.binding_kind = 1 THEN
        SELECT parameter_id, parameter_value_id INTO v_parameter_id, v_parameter_value_id
            FROM automation_version_parameter_bindings WHERE binding_id = OLD.binding_id;
    ELSE
        SELECT secret_id INTO v_secret_id
            FROM automation_version_secret_bindings WHERE binding_id = OLD.binding_id;
    END IF;

    INSERT INTO workspace_configuration_events
        (organization_id, workspace_id, event_type, performed_by_membership_id,
         automation_version_id, binding_id, parameter_id, parameter_value_id, secret_id)
    VALUES
        (OLD.organization_id, OLD.workspace_id, 10, actor,
         OLD.automation_version_id, OLD.binding_id, v_parameter_id, v_parameter_value_id, v_secret_id);

    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

-- Criada agora, mas so anexada apos as duas extensoes existirem (a funcao ja pode ser
-- definida antes: corpo plpgsql nao e resolvido contra objetos ate a primeira execucao).

CREATE TABLE automation_version_parameter_bindings (
    binding_id              UUID PRIMARY KEY,
    binding_kind             SMALLINT NOT NULL CHECK (binding_kind = 1),
    organization_id           UUID NOT NULL,
    workspace_id               UUID NOT NULL,
    automation_version_id       UUID NOT NULL,
    parameter_id                  UUID NOT NULL,
    parameter_value_id              UUID NOT NULL,

    FOREIGN KEY (binding_id, binding_kind, organization_id, workspace_id, automation_version_id)
        REFERENCES automation_version_bindings (binding_id, binding_kind, organization_id, workspace_id, automation_version_id)
        ON DELETE CASCADE,
    FOREIGN KEY (organization_id, workspace_id, parameter_id, parameter_value_id)
        REFERENCES workspace_parameter_values (organization_id, workspace_id, parameter_id, parameter_value_id) ON DELETE RESTRICT,
    UNIQUE (automation_version_id, parameter_id)
);

CREATE INDEX ix_automation_version_parameter_bindings_parameter_id ON automation_version_parameter_bindings (parameter_id);

CREATE TABLE automation_version_secret_bindings (
    binding_id              UUID PRIMARY KEY,
    binding_kind             SMALLINT NOT NULL CHECK (binding_kind = 2),
    organization_id           UUID NOT NULL,
    workspace_id               UUID NOT NULL,
    automation_version_id       UUID NOT NULL,
    secret_id                     UUID NOT NULL,

    FOREIGN KEY (binding_id, binding_kind, organization_id, workspace_id, automation_version_id)
        REFERENCES automation_version_bindings (binding_id, binding_kind, organization_id, workspace_id, automation_version_id)
        ON DELETE CASCADE,
    FOREIGN KEY (organization_id, workspace_id, secret_id)
        REFERENCES workspace_secrets (organization_id, workspace_id, secret_id) ON DELETE RESTRICT,
    UNIQUE (automation_version_id, secret_id)
);

CREATE INDEX ix_automation_version_secret_bindings_secret_id ON automation_version_secret_bindings (secret_id);

CREATE TRIGGER trg_automation_version_bindings_audit_delete
    BEFORE DELETE ON automation_version_bindings
    FOR EACH ROW
    EXECUTE FUNCTION audit_automation_version_binding_delete();

-- Mesmas validacoes de dependencia na criacao e na edicao do destino: trava (FOR UPDATE) e
-- exige elegibilidade (parametro ACTIVE / secret nao revogado) sempre que parameter_id,
-- parameter_value_id ou secret_id mudam - inclusive em UPDATE, nao so em INSERT.

CREATE FUNCTION enforce_automation_version_parameter_binding() RETURNS TRIGGER AS $$
DECLARE
    v_param_status SMALLINT;
BEGIN
    PERFORM assert_automation_version_is_draft(COALESCE(NEW.automation_version_id, OLD.automation_version_id));

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF NEW.binding_id <> OLD.binding_id
           OR NEW.binding_kind <> OLD.binding_kind
           OR NEW.organization_id <> OLD.organization_id
           OR NEW.workspace_id <> OLD.workspace_id
           OR NEW.automation_version_id <> OLD.automation_version_id THEN
            RAISE EXCEPTION 'contexto do vinculo e imutavel - exclua e recrie (binding_id=%)', OLD.binding_id;
        END IF;
    END IF;

    IF TG_OP = 'INSERT'
       OR NEW.parameter_id IS DISTINCT FROM OLD.parameter_id
       OR NEW.parameter_value_id IS DISTINCT FROM OLD.parameter_value_id THEN
        SELECT status INTO v_param_status FROM workspace_parameters WHERE parameter_id = NEW.parameter_id FOR UPDATE;
        IF v_param_status IS DISTINCT FROM 1 THEN
            RAISE EXCEPTION 'parametro nao esta ativo para vinculo (parameter_id=%)', NEW.parameter_id;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_automation_version_parameter_bindings_enforce
    BEFORE INSERT OR UPDATE OR DELETE ON automation_version_parameter_bindings
    FOR EACH ROW
    EXECUTE FUNCTION enforce_automation_version_parameter_binding();

CREATE FUNCTION enforce_automation_version_secret_binding() RETURNS TRIGGER AS $$
DECLARE
    v_secret_revoked TIMESTAMPTZ;
    v_found BOOLEAN;
BEGIN
    PERFORM assert_automation_version_is_draft(COALESCE(NEW.automation_version_id, OLD.automation_version_id));

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF NEW.binding_id <> OLD.binding_id
           OR NEW.binding_kind <> OLD.binding_kind
           OR NEW.organization_id <> OLD.organization_id
           OR NEW.workspace_id <> OLD.workspace_id
           OR NEW.automation_version_id <> OLD.automation_version_id THEN
            RAISE EXCEPTION 'contexto do vinculo e imutavel - exclua e recrie (binding_id=%)', OLD.binding_id;
        END IF;
    END IF;

    IF TG_OP = 'INSERT' OR NEW.secret_id IS DISTINCT FROM OLD.secret_id THEN
        SELECT TRUE, revoked_at INTO v_found, v_secret_revoked FROM workspace_secrets WHERE secret_id = NEW.secret_id FOR UPDATE;
        IF NOT COALESCE(v_found, FALSE) OR v_secret_revoked IS NOT NULL THEN
            RAISE EXCEPTION 'secret revogado nao pode ser vinculado (secret_id=%)', NEW.secret_id;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_automation_version_secret_bindings_enforce
    BEFORE INSERT OR UPDATE OR DELETE ON automation_version_secret_bindings
    FOR EACH ROW
    EXECUTE FUNCTION enforce_automation_version_secret_binding();

CREATE FUNCTION audit_automation_version_parameter_binding() RETURNS TRIGGER AS $$
DECLARE
    actor UUID := current_setting('nekkora.actor_membership_id')::uuid;
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO workspace_configuration_events
            (organization_id, workspace_id, event_type, performed_by_membership_id,
             automation_version_id, binding_id, parameter_id, parameter_value_id)
        VALUES
            (NEW.organization_id, NEW.workspace_id, 8, actor,
             NEW.automation_version_id, NEW.binding_id, NEW.parameter_id, NEW.parameter_value_id);
    ELSIF TG_OP = 'UPDATE' AND (NEW.parameter_id IS DISTINCT FROM OLD.parameter_id
                             OR NEW.parameter_value_id IS DISTINCT FROM OLD.parameter_value_id) THEN
        INSERT INTO workspace_configuration_events
            (organization_id, workspace_id, event_type, performed_by_membership_id,
             automation_version_id, binding_id, parameter_id, parameter_value_id)
        VALUES
            (NEW.organization_id, NEW.workspace_id, 9, actor,
             NEW.automation_version_id, NEW.binding_id, NEW.parameter_id, NEW.parameter_value_id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_automation_version_parameter_bindings_audit
    AFTER INSERT OR UPDATE ON automation_version_parameter_bindings
    FOR EACH ROW
    EXECUTE FUNCTION audit_automation_version_parameter_binding();

CREATE FUNCTION audit_automation_version_secret_binding() RETURNS TRIGGER AS $$
DECLARE
    actor UUID := current_setting('nekkora.actor_membership_id')::uuid;
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO workspace_configuration_events
            (organization_id, workspace_id, event_type, performed_by_membership_id,
             automation_version_id, binding_id, secret_id)
        VALUES
            (NEW.organization_id, NEW.workspace_id, 8, actor,
             NEW.automation_version_id, NEW.binding_id, NEW.secret_id);
    ELSIF TG_OP = 'UPDATE' AND NEW.secret_id IS DISTINCT FROM OLD.secret_id THEN
        INSERT INTO workspace_configuration_events
            (organization_id, workspace_id, event_type, performed_by_membership_id,
             automation_version_id, binding_id, secret_id)
        VALUES
            (NEW.organization_id, NEW.workspace_id, 9, actor,
             NEW.automation_version_id, NEW.binding_id, NEW.secret_id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_automation_version_secret_bindings_audit
    AFTER INSERT OR UPDATE ON automation_version_secret_bindings
    FOR EACH ROW
    EXECUTE FUNCTION audit_automation_version_secret_binding();

-- === Correspondencia exata entre vinculos e command_json, na publicacao ===

-- Vinculo-base sem extensao e checado com NOT EXISTS direto contra cada tabela-base -
-- nunca apenas pelo resultado de um JOIN, que poderia escondê-lo do lado errado.
CREATE FUNCTION is_command_bindings_consistent(command_json JSONB, p_automation_version_id UUID) RETURNS BOOLEAN AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM automation_version_bindings b
        WHERE b.automation_version_id = p_automation_version_id AND b.binding_kind = 1
          AND NOT EXISTS (SELECT 1 FROM automation_version_parameter_bindings pb WHERE pb.binding_id = b.binding_id)
    ) THEN
        RETURN FALSE;
    END IF;

    IF EXISTS (
        SELECT 1 FROM automation_version_bindings b
        WHERE b.automation_version_id = p_automation_version_id AND b.binding_kind = 2
          AND NOT EXISTS (SELECT 1 FROM automation_version_secret_bindings sb WHERE sb.binding_id = b.binding_id)
    ) THEN
        RETURN FALSE;
    END IF;

    -- parameters: cada chave de command_json tem vinculo com valor identico, e cada
    -- vinculo-parametro tem chave correspondente - nos dois sentidos.
    IF EXISTS (
        SELECT 1 FROM jsonb_each(command_json -> 'parameters') AS kv(key, value)
        LEFT JOIN automation_version_bindings b
            ON b.automation_version_id = p_automation_version_id AND b.binding_name = kv.key AND b.binding_kind = 1
        LEFT JOIN automation_version_parameter_bindings pb ON pb.binding_id = b.binding_id
        LEFT JOIN workspace_parameter_values pv ON pv.parameter_value_id = pb.parameter_value_id
        WHERE b.binding_id IS NULL OR pv.value IS DISTINCT FROM kv.value
    ) THEN
        RETURN FALSE;
    END IF;

    IF EXISTS (
        SELECT 1 FROM automation_version_bindings b
        JOIN automation_version_parameter_bindings pb ON pb.binding_id = b.binding_id
        WHERE b.automation_version_id = p_automation_version_id AND b.binding_kind = 1
          AND NOT (command_json -> 'parameters' ? b.binding_name)
    ) THEN
        RETURN FALSE;
    END IF;

    -- secret_bindings: chave -> secret_id como texto UUID (nunca provider_reference)
    IF EXISTS (
        SELECT 1 FROM jsonb_each_text(command_json -> 'secret_bindings') AS kv(key, value)
        LEFT JOIN automation_version_bindings b
            ON b.automation_version_id = p_automation_version_id AND b.binding_name = kv.key AND b.binding_kind = 2
        LEFT JOIN automation_version_secret_bindings sb ON sb.binding_id = b.binding_id
        WHERE b.binding_id IS NULL OR sb.secret_id::text IS DISTINCT FROM kv.value
    ) THEN
        RETURN FALSE;
    END IF;

    IF EXISTS (
        SELECT 1 FROM automation_version_bindings b
        JOIN automation_version_secret_bindings sb ON sb.binding_id = b.binding_id
        WHERE b.automation_version_id = p_automation_version_id AND b.binding_kind = 2
          AND NOT (command_json -> 'secret_bindings' ? b.binding_name)
    ) THEN
        RETURN FALSE;
    END IF;

    RETURN TRUE;
END;
$$ LANGUAGE plpgsql STABLE;

-- Na transicao DRAFT->PUBLISHED: trava as dependencias referenciadas em ordem fixa (tipo,
-- depois UUID) antes de validar, para nao disputar lock em ordem diferente de um
-- arquivamento/revogacao concorrente. Se a publicacao vence a corrida (dependencia ainda
-- elegivel no momento do lock), a linha e aceita; arquivamento/revogacao posterior continua
-- permitido normalmente (efeito assimetrico ja definido - nao ha bloqueio retroativo).
CREATE FUNCTION enforce_publication_bindings_consistency() RETURNS TRIGGER AS $$
DECLARE
    dep RECORD;
    param_status SMALLINT;
    secret_revoked TIMESTAMPTZ;
BEGIN
    IF NOT (OLD.status = 1 AND NEW.status = 2) THEN
        RETURN NEW;
    END IF;

    FOR dep IN
        SELECT kind, dep_id FROM (
            SELECT 1 AS kind, pb.parameter_id AS dep_id
            FROM automation_version_bindings b
            JOIN automation_version_parameter_bindings pb ON pb.binding_id = b.binding_id
            WHERE b.automation_version_id = NEW.automation_version_id
            UNION
            SELECT 2 AS kind, sb.secret_id AS dep_id
            FROM automation_version_bindings b
            JOIN automation_version_secret_bindings sb ON sb.binding_id = b.binding_id
            WHERE b.automation_version_id = NEW.automation_version_id
        ) deps
        ORDER BY kind, dep_id::text
    LOOP
        IF dep.kind = 1 THEN
            SELECT status INTO param_status FROM workspace_parameters WHERE parameter_id = dep.dep_id FOR UPDATE;
            IF param_status IS DISTINCT FROM 1 THEN
                RAISE EXCEPTION 'parametro vinculado nao esta ativo para publicacao (parameter_id=%)', dep.dep_id;
            END IF;
        ELSE
            SELECT revoked_at INTO secret_revoked FROM workspace_secrets WHERE secret_id = dep.dep_id FOR UPDATE;
            IF secret_revoked IS NOT NULL THEN
                RAISE EXCEPTION 'secret vinculado esta revogado para publicacao (secret_id=%)', dep.dep_id;
            END IF;
        END IF;
    END LOOP;

    IF NOT is_command_bindings_consistent(NEW.command_json, NEW.automation_version_id) THEN
        RAISE EXCEPTION 'vinculos da versao nao correspondem exatamente ao command_json (automation_version_id=%)', NEW.automation_version_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_automation_versions_enforce_publication_bindings
    BEFORE UPDATE ON automation_versions
    FOR EACH ROW
    EXECUTE FUNCTION enforce_publication_bindings_consistency();

-- === Evolucao aditiva de 006: secret revogado bloqueia criacao e reserva de execucao ===
--
-- CREATE OR REPLACE redefine as duas funcoes de 006 (o corpo completo e reescrito aqui,
-- nao apenas a parte nova) - tecnica deliberada para nao reabrir 006_executions.sql. As
-- triggers ja existentes continuam apontando para o mesmo nome de funcao, sem recriacao.
-- Parametro arquivado NAO entra nesta checagem (nao bloqueia execucao ja publicada - a
-- suspensao da automacao e o mecanismo para isso, ver relatorio de preparacao).

CREATE OR REPLACE FUNCTION enforce_execution_creation_prerequisites() RETURNS TRIGGER AS $$
DECLARE
    org_status SMALLINT;
    ws_status SMALLINT;
    automation_status SMALLINT;
    version_status SMALLINT;
    version_command JSONB;
    dep RECORD;
    secret_revoked TIMESTAMPTZ;
BEGIN
    IF NEW.status <> 1 THEN
        RAISE EXCEPTION 'execucao deve nascer PENDING (execution_id=%)', NEW.execution_id;
    END IF;

    IF NEW.worker_id IS NOT NULL
       OR NEW.worker_agent_assignment_id IS NOT NULL
       OR NEW.worker_workspace_assignment_id IS NOT NULL
       OR NEW.reserved_at IS NOT NULL
       OR NEW.dispatched_at IS NOT NULL
       OR NEW.started_at IS NOT NULL
       OR NEW.finished_at IS NOT NULL
       OR NEW.exit_code IS NOT NULL
       OR NEW.pid IS NOT NULL
       OR NEW.status_reason IS NOT NULL
       OR NEW.error_summary IS NOT NULL
       OR NEW.cancel_requested_at IS NOT NULL
       OR NEW.cancel_requested_by_membership_id IS NOT NULL THEN
        RAISE EXCEPTION 'execucao nao pode nascer com alocacao ou dados de ciclo de vida preenchidos (execution_id=%)', NEW.execution_id;
    END IF;

    SELECT status INTO org_status FROM organizations WHERE organization_id = NEW.organization_id;
    IF org_status <> 1 THEN
        RAISE EXCEPTION 'organizacao nao esta ativa (execution_id=%)', NEW.execution_id;
    END IF;

    SELECT status INTO ws_status FROM workspaces WHERE workspace_id = NEW.workspace_id;
    IF ws_status <> 1 THEN
        RAISE EXCEPTION 'workspace nao esta ativo (execution_id=%)', NEW.execution_id;
    END IF;

    SELECT status INTO automation_status FROM automations WHERE automation_id = NEW.automation_id;
    IF automation_status <> 1 THEN
        RAISE EXCEPTION 'automacao nao esta ativa (execution_id=%)', NEW.execution_id;
    END IF;

    SELECT status, command_json INTO version_status, version_command
    FROM automation_versions WHERE automation_version_id = NEW.automation_version_id;
    IF version_status <> 2 THEN
        RAISE EXCEPTION 'versao da automacao nao esta publicada (execution_id=%)', NEW.execution_id;
    END IF;

    IF NEW.command_snapshot <> version_command THEN
        RAISE EXCEPTION 'command_snapshot nao corresponde ao comando da versao referenciada (execution_id=%)', NEW.execution_id;
    END IF;

    -- 008: secret revogado bloqueia tambem a criacao (nao so a reserva) - nenhuma nova
    -- solicitacao para uma versao cujo secret vinculado ja foi revogado.
    FOR dep IN
        SELECT sb.secret_id AS dep_id
        FROM automation_version_bindings b
        JOIN automation_version_secret_bindings sb ON sb.binding_id = b.binding_id
        WHERE b.automation_version_id = NEW.automation_version_id
        ORDER BY sb.secret_id::text
    LOOP
        SELECT revoked_at INTO secret_revoked FROM workspace_secrets WHERE secret_id = dep.dep_id FOR UPDATE;
        IF secret_revoked IS NOT NULL THEN
            RAISE EXCEPTION 'secret vinculado a versao esta revogado (execution_id=%, secret_id=%)', NEW.execution_id, dep.dep_id;
        END IF;
    END LOOP;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION enforce_execution_reservation_prerequisites() RETURNS TRIGGER AS $$
DECLARE
    org_status SMALLINT;
    ws_status SMALLINT;
    automation_status SMALLINT;
    worker_status SMALLINT;
    agent_status SMALLINT;
    waa_status SMALLINT;
    waa_ended TIMESTAMPTZ;
    waa_agent_id UUID;
    wwa_status SMALLINT;
    dep RECORD;
    secret_revoked TIMESTAMPTZ;
BEGIN
    IF NOT (OLD.status = 1 AND NEW.status = 2) THEN
        RETURN NEW;
    END IF;

    SELECT status INTO org_status FROM organizations WHERE organization_id = NEW.organization_id FOR UPDATE;
    IF org_status <> 1 THEN
        RAISE EXCEPTION 'organizacao nao esta ativa para reserva (execution_id=%)', NEW.execution_id;
    END IF;

    SELECT status INTO ws_status FROM workspaces WHERE workspace_id = NEW.workspace_id FOR UPDATE;
    IF ws_status <> 1 THEN
        RAISE EXCEPTION 'workspace nao esta ativo para reserva (execution_id=%)', NEW.execution_id;
    END IF;

    SELECT status INTO automation_status FROM automations WHERE automation_id = NEW.automation_id FOR UPDATE;
    IF automation_status <> 1 THEN
        RAISE EXCEPTION 'automacao nao esta ativa para reserva (execution_id=%)', NEW.execution_id;
    END IF;

    SELECT status INTO worker_status FROM workers WHERE worker_id = NEW.worker_id FOR UPDATE;
    IF worker_status <> 1 THEN
        RAISE EXCEPTION 'worker nao esta ativo para reserva (execution_id=%)', NEW.execution_id;
    END IF;

    SELECT status, ended_at, agent_id INTO waa_status, waa_ended, waa_agent_id
    FROM worker_agent_assignments
    WHERE assignment_id = NEW.worker_agent_assignment_id
    FOR UPDATE;

    IF waa_status IS NULL OR waa_status <> 1 OR waa_ended IS NOT NULL THEN
        RAISE EXCEPTION 'associacao worker-agent nao esta ativa para reserva (execution_id=%)', NEW.execution_id;
    END IF;

    SELECT status INTO agent_status FROM agents WHERE agent_id = waa_agent_id FOR UPDATE;
    IF agent_status <> 2 THEN
        RAISE EXCEPTION 'agent nao esta ativo para reserva (execution_id=%)', NEW.execution_id;
    END IF;

    SELECT status INTO wwa_status
    FROM worker_workspace_assignments
    WHERE assignment_id = NEW.worker_workspace_assignment_id
    FOR UPDATE;

    IF wwa_status IS NULL OR wwa_status <> 1 THEN
        RAISE EXCEPTION 'autorizacao worker-workspace nao esta ativa para reserva (execution_id=%)', NEW.execution_id;
    END IF;

    -- 008: mesma checagem de secret revogado, tambem na reserva - tabelas disjuntas das
    -- ja travadas acima, sem risco novo de deadlock nesta ordem fixa.
    FOR dep IN
        SELECT sb.secret_id AS dep_id
        FROM automation_version_bindings b
        JOIN automation_version_secret_bindings sb ON sb.binding_id = b.binding_id
        WHERE b.automation_version_id = NEW.automation_version_id
        ORDER BY sb.secret_id::text
    LOOP
        SELECT revoked_at INTO secret_revoked FROM workspace_secrets WHERE secret_id = dep.dep_id FOR UPDATE;
        IF secret_revoked IS NOT NULL THEN
            RAISE EXCEPTION 'secret vinculado a versao esta revogado (execution_id=%, secret_id=%)', NEW.execution_id, dep.dep_id;
        END IF;
    END LOOP;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
