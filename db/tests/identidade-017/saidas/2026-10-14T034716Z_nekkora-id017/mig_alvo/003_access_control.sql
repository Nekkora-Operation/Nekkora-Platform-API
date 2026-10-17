-- Nekkora Platform - controle de acesso: papeis internos (RBAC), concessoes
-- externas fixas, provisionamento auditado do primeiro ORG_ADMIN e protecao
-- do ultimo administrador. Depende de 001_initial.sql e 002_identity.sql.
--
-- Codigos de status (SMALLINT, explicitos e permanentes - nao reordenar nem reutilizar):
--   roles.scope                              : 1=ORGANIZATION, 2=WORKSPACE
--   organization_role_assignments.status     : 1=ACTIVE, 2=REVOKED
--   workspace_role_assignments.status        : 1=ACTIVE, 2=REVOKED
--   client_workspace_grants.status           : 1=PENDING, 2=ACTIVE, 3=SUSPENDED, 4=REVOKED
--   client_workspace_grant_reviews.decision  : 1=KEEP, 2=SUSPEND, 3=REVOKE
--   client_workspace_grant_approvals.approval_type : 1=INITIAL, 2=RENEWAL
--
-- Acesso externo e um pacote fixo de visualizacao (sem roles/permissions) - o
-- backend enumera explicitamente as acoes e dados permitidos.

-- === Evolucao aditiva de 001/002 (novas UNIQUE, sem editar os arquivos anteriores) ===

ALTER TABLE client_memberships
    ADD CONSTRAINT client_memberships_org_client_membership_key
    UNIQUE (organization_id, client_id, client_membership_id);

ALTER TABLE workspaces
    ADD CONSTRAINT workspaces_org_workspace_key
    UNIQUE (organization_id, workspace_id);

ALTER TABLE workspaces
    ADD CONSTRAINT workspaces_org_client_workspace_key
    UNIQUE (organization_id, client_id, workspace_id);

ALTER TABLE organization_memberships
    ADD CONSTRAINT organization_memberships_org_membership_key
    UNIQUE (organization_id, membership_id);

-- === Funcoes utilitarias de proteção (reaproveitadas por varias tabelas) ===

CREATE FUNCTION prevent_delete() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'exclusao nao permitida em % (historico preservado)', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION prevent_update() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'atualizacao nao permitida em % (registro de auditoria imutavel)', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION prevent_truncate() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'truncate nao permitido em % (historico preservado)', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

-- === Catalogo: permissions, roles, role_permissions ===

CREATE TABLE permissions (
    permission_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code          TEXT NOT NULL UNIQUE CHECK (btrim(code) <> ''),
    description   TEXT NOT NULL CHECK (btrim(description) <> ''),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE roles (
    role_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        TEXT NOT NULL UNIQUE CHECK (btrim(code) <> ''),
    scope       SMALLINT NOT NULL CHECK (scope IN (1, 2)),
    description TEXT NOT NULL CHECK (btrim(description) <> ''),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (role_id, scope)
);

COMMENT ON COLUMN roles.scope IS '1=ORGANIZATION, 2=WORKSPACE';

CREATE TABLE role_permissions (
    role_id       UUID NOT NULL REFERENCES roles (role_id) ON DELETE RESTRICT,
    permission_id UUID NOT NULL REFERENCES permissions (permission_id) ON DELETE RESTRICT,
    PRIMARY KEY (role_id, permission_id)
);

INSERT INTO permissions (code, description) VALUES
    ('org.manage', 'Administrar organizacao, membros e clientes'),
    ('workspace.lifecycle', 'Criar e arquivar workspaces'),
    ('workspace.configure', 'Configurar workspace existente'),
    ('role.assign_admin_roles', 'Atribuir/revogar ORG_ADMIN e WORKSPACE_ADMIN'),
    ('role.assign_operator_viewer', 'Atribuir/revogar OPERATOR e VIEWER'),
    ('automation.read', 'Consultar automacoes e execucoes'),
    ('automation.execute', 'Executar e solicitar cancelamento'),
    ('logs.read', 'Consultar logs operacionais sanitizados'),
    ('external_access.manage', 'Aprovar, renovar, revisar e revogar acesso externo');

INSERT INTO roles (code, scope, description) VALUES
    ('ORG_ADMIN', 1, 'Administra a organizacao e todos os seus workspaces'),
    ('WORKSPACE_ADMIN', 2, 'Administra configuracoes e acessos internos de um workspace'),
    ('OPERATOR', 2, 'Consulta e executa automacoes em um workspace'),
    ('VIEWER', 2, 'Consulta informacoes operacionais permitidas em um workspace');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r, permissions p
WHERE r.code = 'ORG_ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r, permissions p
WHERE r.code = 'WORKSPACE_ADMIN'
  AND p.code IN ('workspace.configure', 'role.assign_operator_viewer', 'automation.read', 'automation.execute', 'logs.read');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r, permissions p
WHERE r.code = 'OPERATOR'
  AND p.code IN ('automation.read', 'automation.execute', 'logs.read');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r, permissions p
WHERE r.code = 'VIEWER'
  AND p.code = 'automation.read';

-- === Atribuicoes internas ===

CREATE TABLE organization_role_assignments (
    assignment_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id           UUID NOT NULL,
    membership_id             UUID NOT NULL,
    role_id                   UUID NOT NULL,
    scope                     SMALLINT NOT NULL CHECK (scope = 1),
    status                    SMALLINT NOT NULL CHECK (status IN (1, 2)),
    assigned_by_membership_id UUID,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at                TIMESTAMPTZ,
    revoked_by_membership_id  UUID,
    UNIQUE (organization_id, assignment_id),
    FOREIGN KEY (organization_id, membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (role_id, scope) REFERENCES roles (role_id, scope) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, assigned_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, revoked_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK ((revoked_at IS NULL) = (revoked_by_membership_id IS NULL)),
    CHECK ((status = 2) = (revoked_at IS NOT NULL)),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

COMMENT ON COLUMN organization_role_assignments.status IS '1=ACTIVE, 2=REVOKED';
COMMENT ON COLUMN organization_role_assignments.scope IS 'fixo 1=ORGANIZATION - discriminador da FK com roles';
COMMENT ON COLUMN organization_role_assignments.assigned_by_membership_id IS 'nulo somente na atribuicao inicial de ORG_ADMIN (bootstrap) - ver identity_provisioning_events';

CREATE UNIQUE INDEX ux_org_role_assignments_active
    ON organization_role_assignments (organization_id, membership_id, role_id) WHERE status = 1;

CREATE UNIQUE INDEX ux_org_role_assignments_bootstrap
    ON organization_role_assignments (organization_id) WHERE assigned_by_membership_id IS NULL;

CREATE FUNCTION enforce_org_role_assignment_bootstrap() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.assigned_by_membership_id IS NULL THEN
        PERFORM 1 FROM organizations WHERE organization_id = NEW.organization_id FOR UPDATE;
        IF EXISTS (SELECT 1 FROM organization_role_assignments WHERE organization_id = NEW.organization_id) THEN
            RAISE EXCEPTION 'bootstrap sem responsavel só é permitido quando a organizacao ainda nao tem nenhuma atribuicao (organization_id=%)', NEW.organization_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_org_role_assignments_bootstrap
    BEFORE INSERT ON organization_role_assignments
    FOR EACH ROW
    EXECUTE FUNCTION enforce_org_role_assignment_bootstrap();

-- Uma vez revogada (OLD.revoked_at preenchido), a linha fica totalmente congelada -
-- nenhum UPDATE e aceito, nem mesmo limpar os proprios campos de revogacao (o que
-- reabriria a atribuicao). Enquanto ainda ativa, so a transicao para revogada e
-- permitida; os campos de origem (inclusive assignment_id e created_at) sao imutaveis.
CREATE FUNCTION prevent_org_role_assignment_change() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'atribuicao revogada e imutavel (assignment_id=%)', OLD.assignment_id;
    END IF;
    IF NEW.assignment_id <> OLD.assignment_id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.membership_id <> OLD.membership_id
       OR NEW.role_id <> OLD.role_id
       OR NEW.assigned_by_membership_id IS DISTINCT FROM OLD.assigned_by_membership_id
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'origem da atribuicao e imutavel (assignment_id=%)', OLD.assignment_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_org_role_assignments_prevent_origin_change
    BEFORE UPDATE ON organization_role_assignments
    FOR EACH ROW
    EXECUTE FUNCTION prevent_org_role_assignment_change();

CREATE TRIGGER trg_org_role_assignments_set_updated_at
    BEFORE UPDATE ON organization_role_assignments
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_org_role_assignments_prevent_delete
    BEFORE DELETE ON organization_role_assignments
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_org_role_assignments_prevent_truncate
    BEFORE TRUNCATE ON organization_role_assignments
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

CREATE TABLE workspace_role_assignments (
    assignment_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id           UUID NOT NULL,
    workspace_id              UUID NOT NULL,
    membership_id             UUID NOT NULL,
    role_id                   UUID NOT NULL,
    scope                     SMALLINT NOT NULL CHECK (scope = 2),
    status                    SMALLINT NOT NULL CHECK (status IN (1, 2)),
    assigned_by_membership_id UUID NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at                TIMESTAMPTZ,
    revoked_by_membership_id  UUID,
    FOREIGN KEY (organization_id, membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (role_id, scope) REFERENCES roles (role_id, scope) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, assigned_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, revoked_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, workspace_id)
        REFERENCES workspaces (organization_id, workspace_id) ON DELETE RESTRICT,
    CHECK ((revoked_at IS NULL) = (revoked_by_membership_id IS NULL)),
    CHECK ((status = 2) = (revoked_at IS NOT NULL)),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

COMMENT ON COLUMN workspace_role_assignments.status IS '1=ACTIVE, 2=REVOKED';
COMMENT ON COLUMN workspace_role_assignments.scope IS 'fixo 2=WORKSPACE - discriminador da FK com roles';

CREATE UNIQUE INDEX ux_workspace_role_assignments_active
    ON workspace_role_assignments (organization_id, workspace_id, membership_id, role_id) WHERE status = 1;

-- Funcao dedicada (nao compartilhada com organization_role_assignments): tambem
-- protege workspace_id, que a tabela de organizacao nao possui.
CREATE FUNCTION prevent_workspace_role_assignment_change() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'atribuicao revogada e imutavel (assignment_id=%)', OLD.assignment_id;
    END IF;
    IF NEW.assignment_id <> OLD.assignment_id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.membership_id <> OLD.membership_id
       OR NEW.role_id <> OLD.role_id
       OR NEW.assigned_by_membership_id <> OLD.assigned_by_membership_id
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'origem da atribuicao e imutavel (assignment_id=%)', OLD.assignment_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_workspace_role_assignments_prevent_origin_change
    BEFORE UPDATE ON workspace_role_assignments
    FOR EACH ROW
    EXECUTE FUNCTION prevent_workspace_role_assignment_change();

CREATE TRIGGER trg_workspace_role_assignments_set_updated_at
    BEFORE UPDATE ON workspace_role_assignments
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_workspace_role_assignments_prevent_delete
    BEFORE DELETE ON workspace_role_assignments
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_workspace_role_assignments_prevent_truncate
    BEFORE TRUNCATE ON workspace_role_assignments
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === Auditoria do provisionamento do primeiro ORG_ADMIN ===

CREATE TABLE identity_provisioning_events (
    event_id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id        UUID NOT NULL REFERENCES organizations (organization_id) ON DELETE RESTRICT,
    assignment_id          UUID NOT NULL UNIQUE,
    provisioned_by_user_id UUID NOT NULL REFERENCES users (user_id) ON DELETE RESTRICT,
    channel                TEXT NOT NULL CHECK (btrim(channel) <> ''),
    correlation_id         UUID NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, assignment_id)
        REFERENCES organization_role_assignments (organization_id, assignment_id) ON DELETE RESTRICT
);

COMMENT ON COLUMN identity_provisioning_events.provisioned_by_user_id IS 'registra autoria (quem executou o fluxo), nao concede autoridade - autoridade vem do fluxo operacional restrito';

CREATE TRIGGER trg_identity_provisioning_events_prevent_update
    BEFORE UPDATE ON identity_provisioning_events
    FOR EACH ROW
    EXECUTE FUNCTION prevent_update();

CREATE TRIGGER trg_identity_provisioning_events_prevent_delete
    BEFORE DELETE ON identity_provisioning_events
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_identity_provisioning_events_prevent_truncate
    BEFORE TRUNCATE ON identity_provisioning_events
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === Concessoes externas ===

CREATE TABLE client_workspace_grants (
    grant_id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id           UUID NOT NULL,
    client_id                 UUID NOT NULL,
    client_membership_id      UUID NOT NULL,
    workspace_id              UUID NOT NULL,
    status                    SMALLINT NOT NULL CHECK (status IN (1, 2, 3, 4)),
    approved_by_membership_id UUID,
    approved_at               TIMESTAMPTZ,
    starts_at                 TIMESTAMPTZ NOT NULL,
    expires_at                TIMESTAMPTZ,
    next_review_at            TIMESTAMPTZ,
    last_access_at            TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at                TIMESTAMPTZ,
    revoked_by_membership_id  UUID,
    UNIQUE (organization_id, grant_id),
    FOREIGN KEY (organization_id, client_id, client_membership_id)
        REFERENCES client_memberships (organization_id, client_id, client_membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, client_id, workspace_id)
        REFERENCES workspaces (organization_id, client_id, workspace_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, approved_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, revoked_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK ((approved_at IS NULL) = (approved_by_membership_id IS NULL)),
    CHECK (status = 1 OR approved_at IS NOT NULL),
    CHECK ((revoked_at IS NULL) = (revoked_by_membership_id IS NULL)),
    CHECK ((status = 4) = (revoked_at IS NOT NULL)),
    CHECK (expires_at IS NULL OR expires_at > starts_at),
    CHECK (status <> 2 OR next_review_at IS NOT NULL),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

COMMENT ON COLUMN client_workspace_grants.status IS '1=PENDING, 2=ACTIVE, 3=SUSPENDED, 4=REVOKED';
COMMENT ON COLUMN client_workspace_grants.next_review_at IS 'obrigatorio quando status=ACTIVE, mesmo sem expires_at';

CREATE UNIQUE INDEX ux_client_workspace_grants_not_revoked
    ON client_workspace_grants (client_membership_id, workspace_id) WHERE status <> 4;

CREATE INDEX ix_client_workspace_grants_organization_id_client_id
    ON client_workspace_grants (organization_id, client_id);

-- Faltava nesta tabela: sem isto, revogar e depois limpar revoked_at/revoked_by
-- (reabrindo a concessao) passava pelos CHECKs sem impedimento. Uma vez revogada,
-- a linha fica congelada; enquanto nao revogada, so a origem e imutavel - starts_at/
-- expires_at/next_review_at/approved_* continuam mutaveis (renovacao/aprovacao).
CREATE FUNCTION prevent_client_workspace_grant_change() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'concessao revogada e imutavel (grant_id=%)', OLD.grant_id;
    END IF;
    IF NEW.grant_id <> OLD.grant_id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.client_id <> OLD.client_id
       OR NEW.client_membership_id <> OLD.client_membership_id
       OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'origem da concessao e imutavel (grant_id=%)', OLD.grant_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_client_workspace_grants_prevent_origin_change
    BEFORE UPDATE ON client_workspace_grants
    FOR EACH ROW
    EXECUTE FUNCTION prevent_client_workspace_grant_change();

CREATE TRIGGER trg_client_workspace_grants_set_updated_at
    BEFORE UPDATE ON client_workspace_grants
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_client_workspace_grants_prevent_delete
    BEFORE DELETE ON client_workspace_grants
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_client_workspace_grants_prevent_truncate
    BEFORE TRUNCATE ON client_workspace_grants
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === Revisao periodica (checkpoint de governanca) ===

CREATE TABLE client_workspace_grant_reviews (
    review_id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id           UUID NOT NULL,
    grant_id                  UUID NOT NULL,
    reviewed_by_membership_id UUID NOT NULL,
    decision                  SMALLINT NOT NULL CHECK (decision IN (1, 2, 3)),
    notes                     TEXT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, grant_id)
        REFERENCES client_workspace_grants (organization_id, grant_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, reviewed_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT
);

COMMENT ON COLUMN client_workspace_grant_reviews.decision IS '1=KEEP, 2=SUSPEND, 3=REVOKE';

CREATE INDEX ix_client_workspace_grant_reviews_grant_id ON client_workspace_grant_reviews (grant_id);

CREATE TRIGGER trg_client_workspace_grant_reviews_prevent_update
    BEFORE UPDATE ON client_workspace_grant_reviews
    FOR EACH ROW
    EXECUTE FUNCTION prevent_update();

CREATE TRIGGER trg_client_workspace_grant_reviews_prevent_delete
    BEFORE DELETE ON client_workspace_grant_reviews
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_client_workspace_grant_reviews_prevent_truncate
    BEFORE TRUNCATE ON client_workspace_grant_reviews
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === Historico de aprovacao/renovacao (evento de autorizacao, distinto de revisao) ===

CREATE TABLE client_workspace_grant_approvals (
    approval_id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id           UUID NOT NULL,
    grant_id                  UUID NOT NULL,
    approval_type             SMALLINT NOT NULL CHECK (approval_type IN (1, 2)),
    approved_by_membership_id UUID NOT NULL,
    approved_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    previous_starts_at        TIMESTAMPTZ,
    new_starts_at             TIMESTAMPTZ NOT NULL,
    previous_expires_at       TIMESTAMPTZ,
    new_expires_at            TIMESTAMPTZ,
    previous_next_review_at   TIMESTAMPTZ,
    new_next_review_at        TIMESTAMPTZ,
    justification             TEXT,
    FOREIGN KEY (organization_id, grant_id)
        REFERENCES client_workspace_grants (organization_id, grant_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, approved_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT
);

COMMENT ON COLUMN client_workspace_grant_approvals.approval_type IS '1=INITIAL, 2=RENEWAL';

CREATE INDEX ix_client_workspace_grant_approvals_grant_id ON client_workspace_grant_approvals (grant_id);

CREATE TRIGGER trg_client_workspace_grant_approvals_prevent_update
    BEFORE UPDATE ON client_workspace_grant_approvals
    FOR EACH ROW
    EXECUTE FUNCTION prevent_update();

CREATE TRIGGER trg_client_workspace_grant_approvals_prevent_delete
    BEFORE DELETE ON client_workspace_grant_approvals
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_client_workspace_grant_approvals_prevent_truncate
    BEFORE TRUNCATE ON client_workspace_grant_approvals
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === Protecao do ultimo ORG_ADMIN efetivo ===
--
-- Disciplina exigida do fluxo de escrita: toda operacao que revoga papel, suspende
-- vinculo ou suspende/encerra conta deve bloquear a linha de organizations daquela
-- organizacao (SELECT 1 FROM organizations WHERE organization_id = X FOR UPDATE) como
-- PRIMEIRA instrucao da transacao, antes de qualquer UPDATE nas tres tabelas abaixo.
-- Os triggers a seguir sao um reforco complementar (protegem mesmo se essa disciplina
-- falhar), nao a garantia de ordem de bloqueio em si.

CREATE FUNCTION enforce_org_admin_presence() RETURNS TRIGGER AS $$
DECLARE
    affected_org_id UUID;
BEGIN
    FOR affected_org_id IN
        SELECT organization_id FROM old_rows
        UNION
        SELECT organization_id FROM new_rows
    LOOP
        PERFORM 1 FROM organizations WHERE organization_id = affected_org_id FOR UPDATE;

        IF EXISTS (SELECT 1 FROM organization_role_assignments WHERE organization_id = affected_org_id)
           AND NOT EXISTS (
               SELECT 1
               FROM organization_role_assignments ora
               JOIN organization_memberships om
                 ON om.organization_id = ora.organization_id AND om.membership_id = ora.membership_id
               JOIN users u
                 ON u.organization_id = ora.organization_id AND u.user_id = om.user_id
               JOIN roles r ON r.role_id = ora.role_id
               WHERE ora.organization_id = affected_org_id
                 AND r.code = 'ORG_ADMIN'
                 AND ora.status = 1
                 AND om.status = 2
                 AND u.status = 2
           )
        THEN
            RAISE EXCEPTION 'organizacao % ficaria sem ORG_ADMIN efetivo', affected_org_id;
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_org_role_assignments_admin_presence
    AFTER UPDATE ON organization_role_assignments
    REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows
    FOR EACH STATEMENT
    EXECUTE FUNCTION enforce_org_admin_presence();

CREATE TRIGGER trg_organization_memberships_admin_presence
    AFTER UPDATE ON organization_memberships
    REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows
    FOR EACH STATEMENT
    EXECUTE FUNCTION enforce_org_admin_presence();

CREATE TRIGGER trg_users_admin_presence
    AFTER UPDATE ON users
    REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows
    FOR EACH STATEMENT
    EXECUTE FUNCTION enforce_org_admin_presence();
