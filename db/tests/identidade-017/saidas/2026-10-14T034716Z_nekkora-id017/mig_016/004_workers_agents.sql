-- Nekkora Platform - capacidade de execucao: Workers (recurso logico),
-- Agents (instalacao autenticada), suas associacoes e credenciais.
-- Depende de 001_initial.sql, 002_identity.sql e 003_access_control.sql.
--
-- Codigos de status (SMALLINT, explicitos e permanentes - nao reordenar nem reutilizar):
--   workers.status                       : 1=ACTIVE, 2=SUSPENDED, 3=RETIRED
--   agents.status                        : 1=PENDING, 2=ACTIVE, 3=SUSPENDED, 4=REVOKED
--   worker_agent_assignments.status      : 1=ACTIVE, 2=ENDED
--   worker_workspace_assignments.status  : 1=ACTIVE, 2=REVOKED
--   agent_credentials.revocation_reason  : 1=ROTATION, 2=EXPIRED_REPLACEMENT, 3=COMPROMISED, 4=ADMINISTRATIVE
--
-- Status e administrativo. Conectividade vem do heartbeat, ocupacao vem das
-- execucoes (bloco futuro) - nao se misturam OFFLINE/BUSY no mesmo campo.
-- Nenhuma acao aqui prova que um processo ja iniciado na maquina parou;
-- protecao de execucao unica e restricao de troca durante execucao pertencem
-- ao bloco de executions.

-- === Novas permissoes no catalogo da 003 (INSERT aditivo, sem alterar 003_access_control.sql) ===

INSERT INTO permissions (code, description) VALUES
    ('worker.manage', 'Cadastrar, suspender e encerrar Workers'),
    ('agent.manage', 'Cadastrar, ativar, suspender e revogar Agents'),
    ('agent_credential.manage', 'Emitir, rotacionar e revogar credenciais de Agent'),
    ('worker_agent.assign', 'Associar e desassociar Worker e Agent'),
    ('worker_workspace.authorize', 'Autorizar e revogar Worker em workspaces');

-- Exclusivas de ORG_ADMIN: papeis de workspace nao administram maquinas compartilhadas.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r, permissions p
WHERE r.code = 'ORG_ADMIN'
  AND p.code IN ('worker.manage', 'agent.manage', 'agent_credential.manage', 'worker_agent.assign', 'worker_workspace.authorize');

-- === Workers ===

CREATE TABLE workers (
    worker_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations (organization_id) ON DELETE RESTRICT,
    name            TEXT NOT NULL CHECK (btrim(name) <> ''),
    description     TEXT CHECK (description IS NULL OR btrim(description) <> ''),
    status          SMALLINT NOT NULL CHECK (status IN (1, 2, 3)),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, worker_id)
);

COMMENT ON COLUMN workers.status IS '1=ACTIVE, 2=SUSPENDED, 3=RETIRED';

-- Protege organization_id, o proprio worker_id (PK) e created_at - uma PK garante
-- unicidade, mas nao torna o valor imutavel por si so.
CREATE FUNCTION prevent_worker_origin_change() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.worker_id <> OLD.worker_id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'origem do worker e imutavel (worker_id=%)', OLD.worker_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_workers_prevent_origin_change
    BEFORE UPDATE ON workers
    FOR EACH ROW
    EXECUTE FUNCTION prevent_worker_origin_change();

CREATE TRIGGER trg_workers_set_updated_at
    BEFORE UPDATE ON workers
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- === Agents ===

CREATE TABLE agents (
    agent_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations (organization_id) ON DELETE RESTRICT,
    name              TEXT NOT NULL CHECK (btrim(name) <> ''),
    installation_id   UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    agent_version     TEXT NOT NULL CHECK (btrim(agent_version) <> ''),
    status            SMALLINT NOT NULL CHECK (status IN (1, 2, 3, 4)),
    registered_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_heartbeat_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, agent_id)
);

COMMENT ON COLUMN agents.status IS '1=PENDING, 2=ACTIVE, 3=SUSPENDED, 4=REVOKED';
COMMENT ON COLUMN agents.installation_id IS 'unicidade global - identifica a instalacao, nao e segredo nem autorizacao; reinstalar gera um agents novo, nao atualiza este';

-- Protege organization_id, agent_id (PK), installation_id e registered_at, tudo
-- numa unica funcao dedicada (nao compartilhada com workers, que nao tem installation_id).
CREATE FUNCTION prevent_agent_origin_change() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.agent_id <> OLD.agent_id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.installation_id <> OLD.installation_id
       OR NEW.registered_at <> OLD.registered_at THEN
        RAISE EXCEPTION 'origem do agent e imutavel (agent_id=%)', OLD.agent_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_agents_prevent_origin_change
    BEFORE UPDATE ON agents
    FOR EACH ROW
    EXECUTE FUNCTION prevent_agent_origin_change();

CREATE TRIGGER trg_agents_set_updated_at
    BEFORE UPDATE ON agents
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- === Associacao Worker-Agent ===

CREATE TABLE worker_agent_assignments (
    assignment_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id           UUID NOT NULL,
    worker_id                 UUID NOT NULL,
    agent_id                  UUID NOT NULL,
    status                    SMALLINT NOT NULL CHECK (status IN (1, 2)),
    assigned_by_membership_id UUID NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at                  TIMESTAMPTZ,
    ended_by_membership_id    UUID,
    FOREIGN KEY (organization_id, worker_id) REFERENCES workers (organization_id, worker_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, agent_id) REFERENCES agents (organization_id, agent_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, assigned_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, ended_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK ((ended_at IS NULL) = (ended_by_membership_id IS NULL)),
    CHECK ((status = 2) = (ended_at IS NOT NULL)),
    CHECK (ended_at IS NULL OR ended_at >= created_at)
);

COMMENT ON COLUMN worker_agent_assignments.status IS '1=ACTIVE, 2=ENDED';

-- no maximo um Agent ativo por Worker, e um Worker ativo por Agent
CREATE UNIQUE INDEX ux_worker_agent_assignments_active_worker
    ON worker_agent_assignments (organization_id, worker_id) WHERE status = 1;
CREATE UNIQUE INDEX ux_worker_agent_assignments_active_agent
    ON worker_agent_assignments (organization_id, agent_id) WHERE status = 1;

-- Uma vez encerrada (OLD.ended_at preenchido), a linha fica totalmente congelada -
-- nenhum UPDATE e aceito, nem mesmo limpar os proprios campos de encerramento (o
-- que reabriria a associacao). Enquanto ainda ativa, so a transicao para encerrada
-- e permitida; os campos de origem (inclusive assignment_id e created_at) sao imutaveis.
CREATE FUNCTION prevent_worker_agent_assignment_origin_change() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.ended_at IS NOT NULL THEN
        RAISE EXCEPTION 'associacao encerrada e imutavel (assignment_id=%)', OLD.assignment_id;
    END IF;
    IF NEW.assignment_id <> OLD.assignment_id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.worker_id <> OLD.worker_id
       OR NEW.agent_id <> OLD.agent_id
       OR NEW.assigned_by_membership_id <> OLD.assigned_by_membership_id
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'origem da associacao worker-agent e imutavel (assignment_id=%)', OLD.assignment_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_worker_agent_assignments_prevent_origin_change
    BEFORE UPDATE ON worker_agent_assignments
    FOR EACH ROW
    EXECUTE FUNCTION prevent_worker_agent_assignment_origin_change();

CREATE TRIGGER trg_worker_agent_assignments_set_updated_at
    BEFORE UPDATE ON worker_agent_assignments
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_worker_agent_assignments_prevent_delete
    BEFORE DELETE ON worker_agent_assignments
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_worker_agent_assignments_prevent_truncate
    BEFORE TRUNCATE ON worker_agent_assignments
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === Autorizacao Worker-workspace ===

CREATE TABLE worker_workspace_assignments (
    assignment_id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id              UUID NOT NULL,
    worker_id                    UUID NOT NULL,
    workspace_id                 UUID NOT NULL,
    status                       SMALLINT NOT NULL CHECK (status IN (1, 2)),
    authorized_by_membership_id  UUID NOT NULL,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at                   TIMESTAMPTZ,
    revoked_by_membership_id     UUID,
    FOREIGN KEY (organization_id, worker_id) REFERENCES workers (organization_id, worker_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, workspace_id) REFERENCES workspaces (organization_id, workspace_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, authorized_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, revoked_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK ((revoked_at IS NULL) = (revoked_by_membership_id IS NULL)),
    CHECK ((status = 2) = (revoked_at IS NOT NULL)),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

COMMENT ON COLUMN worker_workspace_assignments.status IS '1=ACTIVE, 2=REVOKED';

CREATE UNIQUE INDEX ux_worker_workspace_assignments_active
    ON worker_workspace_assignments (organization_id, worker_id, workspace_id) WHERE status = 1;

-- Mesmo principio de worker_agent_assignments: uma vez revogada, congelada por inteiro.
CREATE FUNCTION prevent_worker_workspace_assignment_origin_change() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'autorizacao revogada e imutavel (assignment_id=%)', OLD.assignment_id;
    END IF;
    IF NEW.assignment_id <> OLD.assignment_id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.worker_id <> OLD.worker_id
       OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.authorized_by_membership_id <> OLD.authorized_by_membership_id
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'origem da autorizacao worker-workspace e imutavel (assignment_id=%)', OLD.assignment_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_worker_workspace_assignments_prevent_origin_change
    BEFORE UPDATE ON worker_workspace_assignments
    FOR EACH ROW
    EXECUTE FUNCTION prevent_worker_workspace_assignment_origin_change();

CREATE TRIGGER trg_worker_workspace_assignments_set_updated_at
    BEFORE UPDATE ON worker_workspace_assignments
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_worker_workspace_assignments_prevent_delete
    BEFORE DELETE ON worker_workspace_assignments
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_worker_workspace_assignments_prevent_truncate
    BEFORE TRUNCATE ON worker_workspace_assignments
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === Credenciais do Agent ===
--
-- Formato do hash: SHA-256 em hexadecimal minusculo (64 caracteres), calculado
-- sobre o segredo aleatorio de alta entropia gerado na emissao. Distinto do hash
-- de senha de usuario (que usa algoritmo adaptativo por lidar com baixa entropia
-- humana) - aqui o segredo e aleatorio e de alta entropia, hash rapido basta.

CREATE TABLE agent_credentials (
    credential_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id          UUID NOT NULL,
    agent_id                 UUID NOT NULL,
    credential_hash          TEXT NOT NULL CHECK (credential_hash ~ '^[0-9a-f]{64}$'),
    issued_by_membership_id  UUID NOT NULL,
    issued_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at               TIMESTAMPTZ NOT NULL CHECK (expires_at > issued_at),
    revoked_at               TIMESTAMPTZ,
    revoked_by_membership_id UUID,
    revocation_reason        SMALLINT CHECK (revocation_reason IN (1, 2, 3, 4)),
    FOREIGN KEY (organization_id, agent_id) REFERENCES agents (organization_id, agent_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, issued_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, revoked_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK ((revoked_at IS NULL) = (revoked_by_membership_id IS NULL)),
    CHECK ((revoked_at IS NULL) = (revocation_reason IS NULL)),
    CHECK (revoked_at IS NULL OR revoked_at >= issued_at)
);

COMMENT ON COLUMN agent_credentials.credential_hash IS 'SHA-256 hex (64 chars) do segredo aleatorio - valor bruto nunca persistido';
COMMENT ON COLUMN agent_credentials.revocation_reason IS '1=ROTATION, 2=EXPIRED_REPLACEMENT, 3=COMPROMISED, 4=ADMINISTRATIVE - obrigatorio quando revoked_at preenchido, nunca preenchido por expiracao passiva';

-- uma credencial nao revogada por Agent - expiracao natural NAO libera esta unicidade
-- (indice parcial exige predicado imutavel; expires_at > now() nao pode compor esta condicao)
CREATE UNIQUE INDEX ux_agent_credentials_not_revoked
    ON agent_credentials (agent_id) WHERE revoked_at IS NULL;

-- Uma vez revogada (OLD.revoked_at preenchido), a linha fica totalmente congelada -
-- sem isto, limpar revoked_at/revoked_by/revocation_reason reabriria uma credencial
-- comprometida. Enquanto nao revogada, so a origem (inclusive credential_id) e imutavel.
CREATE FUNCTION prevent_agent_credential_origin_change() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'credencial revogada e imutavel (credential_id=%)', OLD.credential_id;
    END IF;
    IF NEW.credential_id <> OLD.credential_id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.agent_id <> OLD.agent_id
       OR NEW.credential_hash <> OLD.credential_hash
       OR NEW.issued_by_membership_id <> OLD.issued_by_membership_id
       OR NEW.issued_at <> OLD.issued_at
       OR NEW.expires_at <> OLD.expires_at THEN
        RAISE EXCEPTION 'origem da credencial e imutavel (credential_id=%)', OLD.credential_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_agent_credentials_prevent_origin_change
    BEFORE UPDATE ON agent_credentials
    FOR EACH ROW
    EXECUTE FUNCTION prevent_agent_credential_origin_change();

CREATE TRIGGER trg_agent_credentials_prevent_delete
    BEFORE DELETE ON agent_credentials
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_agent_credentials_prevent_truncate
    BEFORE TRUNCATE ON agent_credentials
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

CREATE INDEX ix_agent_credentials_agent_id ON agent_credentials (agent_id);
