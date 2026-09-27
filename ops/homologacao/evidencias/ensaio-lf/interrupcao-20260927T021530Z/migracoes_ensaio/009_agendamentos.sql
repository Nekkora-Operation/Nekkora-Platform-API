-- Nekkora Platform - agendamentos e suas ocorrencias, conectados a execucoes existentes.
-- Depende de 001-008 (toca executions da 006/008 via CREATE OR REPLACE FUNCTION aditivo,
-- e a checagem de secret revogado da 008 e reaproveitada em process_schedule_occurrence).
--
-- Codigos (SMALLINT, explicitos e permanentes - nao reordenar nem reutilizar):
--   automation_schedules.status                   : 1=ACTIVE, 2=SUSPENDED (reversivel)
--   automation_schedule_occurrences.status         : 1=PENDING, 2=EXECUTION_CREATED, 3=SKIPPED
--   automation_schedule_occurrences.skip_reason    : 1=LATE, 2=OVERLAP, 3=SCHEDULE_SUSPENDED,
--                                                     4=DEPENDENCY_UNAVAILABLE, 5=AUTHORIZATION_UNAVAILABLE
--   executions.origin                              : 1=MANUAL, 2=SCHEDULED
--
-- Regras centrais:
--   - Versao executada e fixada por REVISAO do agendamento, nao pela automacao "corrente".
--     Cron/fuso/tolerancia/versao/responsavel vivem em automation_schedule_revisions, imutavel
--     desde a criacao (nunca ha rascunho). Cada ocorrencia usa a revisao vigente no seu proprio
--     scheduled_for (effective_from <= scheduled_for), nao a mais recente no momento em que e
--     processada - uma replica atrasada nao pode materializar usando configuracao errada.
--   - A ocorrencia registra uma DECISAO de disparo (PENDING/EXECUTION_CREATED/SKIPPED), nunca o
--     resultado da execucao - isso continua exclusivamente em executions. Nasce limpa (sem
--     execucao/motivo/decisao) e fica congelada por inteiro assim que decidida.
--   - A decisao e atomica: process_schedule_occurrence() faz tudo sob lock numa unica transacao
--     - idempotente (ocorrencia ja decidida retorna a decisao existente), decide SKIPPED para
--     condicoes de negocio conhecidas, cria a execucao quando elegivel (a trigger AFTER INSERT
--     de executions finaliza a ocorrencia na mesma transacao), e propaga qualquer erro
--     inesperado sem convertê-lo genericamente num SKIPPED.
--   - Instante da decisao e sempre clock_timestamp() capturado DEPOIS dos locks - nunca now(),
--     que refletiria o inicio de uma transacao que pode ter ficado bloqueada ate depois do
--     vencimento. Condicao de validade: scheduled_for <= instante < expires_at.
--   - Um unico ponto de coordenacao por lock: materializar, criar revisao, suspender/retomar e
--     processar uma ocorrencia travam a linha de automation_schedules (FOR UPDATE) antes de agir.
--   - Reciprocidade ocorrencia<->execucao e verificada explicitamente (nao so por FK isolada):
--     a transicao para EXECUTION_CREATED exige que a execucao referenciada aponte de volta para
--     esta ocorrencia, no mesmo contexto.

-- === Novas permissoes no catalogo da 003 (INSERT aditivo) ===

INSERT INTO permissions (code, description) VALUES
    ('automation_schedule.read', 'Consultar agendamentos, revisoes e ocorrencias'),
    ('automation_schedule.manage', 'Criar agendamentos, criar revisoes e suspender/retomar');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r, permissions p
WHERE r.code IN ('ORG_ADMIN', 'WORKSPACE_ADMIN')
  AND p.code IN ('automation_schedule.read', 'automation_schedule.manage');

-- === Gramatica do cron: 5 campos, validacao de forma (nao de limite numerico nem calendario) ===

CREATE FUNCTION is_valid_cron_expression(cron TEXT) RETURNS BOOLEAN
LANGUAGE sql IMMUTABLE AS $$
    WITH fields AS (
        SELECT regexp_split_to_array(btrim(cron), '\s+') AS f
    )
    SELECT (
        array_length(f, 1) = 5
        AND f[1] ~ '^(\*|[0-9]+)(-[0-9]+)?(/[0-9]+)?(,(\*|[0-9]+)(-[0-9]+)?(/[0-9]+)?)*$'
        AND f[2] ~ '^(\*|[0-9]+)(-[0-9]+)?(/[0-9]+)?(,(\*|[0-9]+)(-[0-9]+)?(/[0-9]+)?)*$'
        AND f[3] ~ '^(\*|[0-9]+)(-[0-9]+)?(/[0-9]+)?(,(\*|[0-9]+)(-[0-9]+)?(/[0-9]+)?)*$'
        AND f[4] ~ '^(\*|[0-9]+)(-[0-9]+)?(/[0-9]+)?(,(\*|[0-9]+)(-[0-9]+)?(/[0-9]+)?)*$'
        AND f[5] ~ '^(\*|[0-9]+)(-[0-9]+)?(/[0-9]+)?(,(\*|[0-9]+)(-[0-9]+)?(/[0-9]+)?)*$'
        -- dia do mes (3) e dia da semana (5) nunca restritivos ao mesmo tempo - ambos "*", ou so
        -- um deles, sao aceitos; nao e XOR, nao exige que exatamente um seja "*".
        AND NOT (f[3] <> '*' AND f[5] <> '*')
    ) IS TRUE
    FROM fields;
$$;

-- === automation_schedules ===

CREATE TABLE automation_schedules (
    schedule_id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id            UUID NOT NULL,
    workspace_id                UUID NOT NULL,
    automation_id                UUID NOT NULL,
    name                          TEXT NOT NULL CHECK (btrim(name) <> ''),
    status                         SMALLINT NOT NULL CHECK (status IN (1, 2)),
    created_by_membership_id        UUID NOT NULL,
    created_at                        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                          TIMESTAMPTZ NOT NULL DEFAULT now(),
    suspended_at                          TIMESTAMPTZ,
    suspended_by_membership_id              UUID,
    resumed_at                                TIMESTAMPTZ,

    UNIQUE (organization_id, workspace_id, schedule_id),
    UNIQUE (organization_id, workspace_id, automation_id, schedule_id),
    FOREIGN KEY (organization_id, workspace_id, automation_id)
        REFERENCES automations (organization_id, workspace_id, automation_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, created_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, suspended_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK ((suspended_at IS NULL) = (suspended_by_membership_id IS NULL)),
    CHECK ((status = 2) = (suspended_at IS NOT NULL))
);

COMMENT ON COLUMN automation_schedules.status IS '1=ACTIVE, 2=SUSPENDED - reversivel';
COMMENT ON COLUMN automation_schedules.resumed_at IS 'marco da retomada mais recente - piso para a proxima materializacao, nunca recupera o intervalo suspenso';

-- Origem sempre imutavel; retomada (SUSPENDED->ACTIVE) tem resumed_at atribuido pelo banco,
-- nunca recebido da aplicacao.
CREATE FUNCTION enforce_automation_schedule_transition() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.schedule_id <> OLD.schedule_id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.automation_id <> OLD.automation_id
       OR NEW.created_at <> OLD.created_at
       OR NEW.created_by_membership_id <> OLD.created_by_membership_id THEN
        RAISE EXCEPTION 'origem do agendamento e imutavel (schedule_id=%)', OLD.schedule_id;
    END IF;

    IF OLD.status = 2 AND NEW.status = 1 THEN
        NEW.resumed_at := clock_timestamp();
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_automation_schedules_enforce_transition
    BEFORE UPDATE ON automation_schedules
    FOR EACH ROW
    EXECUTE FUNCTION enforce_automation_schedule_transition();

CREATE TRIGGER trg_automation_schedules_set_updated_at
    BEFORE UPDATE ON automation_schedules
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_automation_schedules_prevent_delete
    BEFORE DELETE ON automation_schedules
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_automation_schedules_prevent_truncate
    BEFORE TRUNCATE ON automation_schedules
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === automation_schedule_revisions - imutavel desde a criacao, com vigencia ===

CREATE TABLE automation_schedule_revisions (
    revision_id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             UUID NOT NULL,
    workspace_id                 UUID NOT NULL,
    automation_id                 UUID NOT NULL,
    schedule_id                     UUID NOT NULL,
    revision_number                   INTEGER NOT NULL CHECK (revision_number > 0),
    effective_from                      TIMESTAMPTZ NOT NULL,
    automation_version_id                 UUID NOT NULL,
    cron_expression                         TEXT NOT NULL CHECK (is_valid_cron_expression(cron_expression) IS TRUE),
    timezone                                   TEXT NOT NULL CHECK (btrim(timezone) <> ''),
    late_tolerance_seconds                       INTEGER NOT NULL CHECK (late_tolerance_seconds > 0),
    authorized_by_membership_id                    UUID NOT NULL,
    created_by_membership_id                         UUID NOT NULL,
    created_at                                         TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (schedule_id, revision_number),
    UNIQUE (schedule_id, revision_id),
    FOREIGN KEY (organization_id, workspace_id, automation_id, schedule_id)
        REFERENCES automation_schedules (organization_id, workspace_id, automation_id, schedule_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, workspace_id, automation_id, automation_version_id)
        REFERENCES automation_versions (organization_id, workspace_id, automation_id, automation_version_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, authorized_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, created_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT
);

COMMENT ON COLUMN automation_schedule_revisions.effective_from IS 'atribuido pelo banco sob lock do agendamento - delimita a vigencia ate a proxima revisao';

CREATE INDEX ix_automation_schedule_revisions_schedule_effective ON automation_schedule_revisions (schedule_id, effective_from);

-- Trava o agendamento, aloca numero e vigencia, valida fuso (contra pg_timezone_names - nao da
-- para fazer isso num CHECK, que nao pode consultar outra relacao) e exige versao PUBLISHED.
CREATE FUNCTION enforce_schedule_revision_creation() RETURNS TRIGGER AS $$
DECLARE
    version_status SMALLINT;
BEGIN
    PERFORM 1 FROM automation_schedules WHERE schedule_id = NEW.schedule_id FOR UPDATE;

    NEW.revision_number := COALESCE(
        (SELECT MAX(revision_number) FROM automation_schedule_revisions WHERE schedule_id = NEW.schedule_id),
        0
    ) + 1;
    NEW.effective_from := clock_timestamp();

    IF NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = NEW.timezone) THEN
        RAISE EXCEPTION 'fuso horario invalido (timezone=%)', NEW.timezone;
    END IF;

    SELECT status INTO version_status FROM automation_versions WHERE automation_version_id = NEW.automation_version_id;
    IF version_status IS DISTINCT FROM 2 THEN
        RAISE EXCEPTION 'versao referenciada pela revisao nao esta publicada (automation_version_id=%)', NEW.automation_version_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_automation_schedule_revisions_enforce_creation
    BEFORE INSERT ON automation_schedule_revisions
    FOR EACH ROW
    EXECUTE FUNCTION enforce_schedule_revision_creation();

CREATE TRIGGER trg_automation_schedule_revisions_prevent_update
    BEFORE UPDATE ON automation_schedule_revisions
    FOR EACH ROW
    EXECUTE FUNCTION prevent_update();

CREATE TRIGGER trg_automation_schedule_revisions_prevent_delete
    BEFORE DELETE ON automation_schedule_revisions
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_automation_schedule_revisions_prevent_truncate
    BEFORE TRUNCATE ON automation_schedule_revisions
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === automation_schedule_occurrences - decisao de disparo, nao o resultado da execucao ===

CREATE TABLE automation_schedule_occurrences (
    occurrence_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id           UUID NOT NULL,
    workspace_id               UUID NOT NULL,
    schedule_id                 UUID NOT NULL,
    revision_id                   UUID NOT NULL,
    scheduled_for                   TIMESTAMPTZ NOT NULL,
    expires_at                        TIMESTAMPTZ NOT NULL,
    status                              SMALLINT NOT NULL CHECK (status IN (1, 2, 3)),
    skip_reason                          SMALLINT CHECK (skip_reason IN (1, 2, 3, 4, 5)),
    execution_id                           UUID,
    decided_at                               TIMESTAMPTZ,
    created_at                                 TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (schedule_id, scheduled_for),
    UNIQUE (organization_id, workspace_id, occurrence_id),
    FOREIGN KEY (organization_id, workspace_id, schedule_id)
        REFERENCES automation_schedules (organization_id, workspace_id, schedule_id) ON DELETE RESTRICT,
    FOREIGN KEY (schedule_id, revision_id)
        REFERENCES automation_schedule_revisions (schedule_id, revision_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, workspace_id, execution_id)
        REFERENCES executions (organization_id, workspace_id, execution_id) ON DELETE RESTRICT,

    CHECK ((status = 2) = (execution_id IS NOT NULL)),
    CHECK ((status = 3) = (skip_reason IS NOT NULL)),
    CHECK ((status = 1) = (decided_at IS NULL)),
    CHECK (expires_at > scheduled_for),
    CHECK (decided_at IS NULL OR decided_at >= scheduled_for)
);

COMMENT ON COLUMN automation_schedule_occurrences.status IS '1=PENDING, 2=EXECUTION_CREATED, 3=SKIPPED - decisao de disparo, resultado fica em executions';
COMMENT ON COLUMN automation_schedule_occurrences.skip_reason IS '1=LATE, 2=OVERLAP, 3=SCHEDULE_SUSPENDED, 4=DEPENDENCY_UNAVAILABLE, 5=AUTHORIZATION_UNAVAILABLE';
COMMENT ON COLUMN automation_schedule_occurrences.revision_id IS 'calculado pelo banco na materializacao - a revisao vigente em scheduled_for, nunca recebida da aplicacao';
COMMENT ON COLUMN automation_schedule_occurrences.expires_at IS 'calculado pelo banco a partir de late_tolerance_seconds da revisao';

-- Materializacao: trava o agendamento (mesmo lock de revisao/suspensao/processamento), rejeita
-- se suspenso, se scheduled_for esta no futuro, ou anterior ao marco de retomada; calcula
-- revisao vigente e expires_at. Nasce sempre PENDING, sem excecao possivel.
CREATE FUNCTION materialize_schedule_occurrence() RETURNS TRIGGER AS $$
DECLARE
    schedule_status SMALLINT;
    schedule_resumed_at TIMESTAMPTZ;
    found_revision_id UUID;
    found_tolerance INTEGER;
    decision_instant TIMESTAMPTZ;
BEGIN
    IF NEW.status <> 1 OR NEW.execution_id IS NOT NULL OR NEW.skip_reason IS NOT NULL OR NEW.decided_at IS NOT NULL THEN
        RAISE EXCEPTION 'ocorrencia deve nascer PENDING, sem decisao (schedule_id=%, scheduled_for=%)', NEW.schedule_id, NEW.scheduled_for;
    END IF;

    SELECT status, resumed_at INTO schedule_status, schedule_resumed_at
    FROM automation_schedules WHERE schedule_id = NEW.schedule_id FOR UPDATE;

    IF schedule_status IS NULL THEN
        RAISE EXCEPTION 'agendamento inexistente (schedule_id=%)', NEW.schedule_id;
    END IF;
    IF schedule_status <> 1 THEN
        RAISE EXCEPTION 'agendamento suspenso nao aceita nova ocorrencia (schedule_id=%)', NEW.schedule_id;
    END IF;

    decision_instant := clock_timestamp();
    IF NEW.scheduled_for > decision_instant THEN
        RAISE EXCEPTION 'materializacao antecipada - scheduled_for no futuro (schedule_id=%, scheduled_for=%)', NEW.schedule_id, NEW.scheduled_for;
    END IF;

    IF schedule_resumed_at IS NOT NULL AND NEW.scheduled_for < schedule_resumed_at THEN
        RAISE EXCEPTION 'scheduled_for anterior ao marco de retomada (schedule_id=%, scheduled_for=%, resumed_at=%)', NEW.schedule_id, NEW.scheduled_for, schedule_resumed_at;
    END IF;

    SELECT revision_id, late_tolerance_seconds INTO found_revision_id, found_tolerance
    FROM automation_schedule_revisions
    WHERE schedule_id = NEW.schedule_id AND effective_from <= NEW.scheduled_for
    ORDER BY effective_from DESC
    LIMIT 1;

    IF found_revision_id IS NULL THEN
        RAISE EXCEPTION 'nenhuma revisao vigente para scheduled_for (schedule_id=%, scheduled_for=%)', NEW.schedule_id, NEW.scheduled_for;
    END IF;

    NEW.revision_id := found_revision_id;
    NEW.expires_at := NEW.scheduled_for + (found_tolerance * INTERVAL '1 second');

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_automation_schedule_occurrences_materialize
    BEFORE INSERT ON automation_schedule_occurrences
    FOR EACH ROW
    EXECUTE FUNCTION materialize_schedule_occurrence();

CREATE TRIGGER trg_automation_schedule_occurrences_prevent_delete
    BEFORE DELETE ON automation_schedule_occurrences
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_automation_schedule_occurrences_prevent_truncate
    BEFORE TRUNCATE ON automation_schedule_occurrences
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === Evolucao aditiva de 006: origem manual vs. agendada em executions ===

ALTER TABLE executions ADD COLUMN origin SMALLINT;
UPDATE executions SET origin = 1;
ALTER TABLE executions ALTER COLUMN origin SET NOT NULL;
ALTER TABLE executions ADD CONSTRAINT executions_origin_check CHECK (origin IN (1, 2));
COMMENT ON COLUMN executions.origin IS '1=MANUAL, 2=SCHEDULED';

ALTER TABLE executions ALTER COLUMN requested_by_membership_id DROP NOT NULL;
ALTER TABLE executions ADD CONSTRAINT executions_manual_requester_check
    CHECK ((origin = 1) = (requested_by_membership_id IS NOT NULL));

ALTER TABLE executions ADD COLUMN authorized_by_membership_id UUID;
ALTER TABLE executions ADD CONSTRAINT executions_scheduled_authorizer_check
    CHECK ((origin = 2) = (authorized_by_membership_id IS NOT NULL));
ALTER TABLE executions ADD CONSTRAINT executions_organization_id_authorized_by_membership_id_fkey
    FOREIGN KEY (organization_id, authorized_by_membership_id)
    REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT;

ALTER TABLE executions ADD COLUMN schedule_occurrence_id UUID;
ALTER TABLE executions ADD CONSTRAINT executions_schedule_occurrence_check
    CHECK ((origin = 2) = (schedule_occurrence_id IS NOT NULL));
ALTER TABLE executions ADD CONSTRAINT executions_organization_id_schedule_occurrence_id_fkey
    FOREIGN KEY (organization_id, workspace_id, schedule_occurrence_id)
    REFERENCES automation_schedule_occurrences (organization_id, workspace_id, occurrence_id) ON DELETE RESTRICT;

-- No maximo uma execucao por ocorrencia - relacao estrutural, nao "consultar e inserir depois".
CREATE UNIQUE INDEX ux_executions_schedule_occurrence
    ON executions (schedule_occurrence_id) WHERE schedule_occurrence_id IS NOT NULL;

-- Finaliza a ocorrencia na MESMA transacao do INSERT da execucao agendada - sem exigir um
-- segundo comando da aplicacao no caminho de sucesso. A trigger de imutabilidade da ocorrencia
-- (mais abaixo) verifica a reciprocidade antes de aceitar esta UPDATE.
CREATE FUNCTION finalize_schedule_occurrence_on_execution_insert() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.origin = 2 THEN
        UPDATE automation_schedule_occurrences
        SET status = 2, execution_id = NEW.execution_id, decided_at = clock_timestamp()
        WHERE occurrence_id = NEW.schedule_occurrence_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_executions_finalize_schedule_occurrence
    AFTER INSERT ON executions
    FOR EACH ROW
    EXECUTE FUNCTION finalize_schedule_occurrence_on_execution_insert();

-- === Imutabilidade da ocorrencia, em duas camadas, e reciprocidade com a execucao ===
--
-- Origem sempre imutavel (mesmo enquanto PENDING); congelamento terminal uma vez decidida.
-- A transicao para EXECUTION_CREATED exige que a execucao referenciada aponte de volta para
-- esta ocorrencia, no mesmo contexto - uma FK isolada em execution_id nao garante isso.
CREATE FUNCTION prevent_occurrence_mutation() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status <> 1 THEN
        RAISE EXCEPTION 'ocorrencia decidida e imutavel (occurrence_id=%)', OLD.occurrence_id;
    END IF;

    IF NEW.occurrence_id <> OLD.occurrence_id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.schedule_id <> OLD.schedule_id
       OR NEW.revision_id <> OLD.revision_id
       OR NEW.scheduled_for <> OLD.scheduled_for
       OR NEW.expires_at <> OLD.expires_at
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'origem da ocorrencia e imutavel (occurrence_id=%)', OLD.occurrence_id;
    END IF;

    IF NEW.status = 2 THEN
        IF NOT EXISTS (
            SELECT 1 FROM executions
            WHERE execution_id = NEW.execution_id
              AND schedule_occurrence_id = NEW.occurrence_id
              AND organization_id = NEW.organization_id
              AND workspace_id = NEW.workspace_id
        ) THEN
            RAISE EXCEPTION 'execucao referenciada nao aponta de volta para esta ocorrencia (occurrence_id=%, execution_id=%)', NEW.occurrence_id, NEW.execution_id;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_automation_schedule_occurrences_prevent_mutation
    BEFORE UPDATE ON automation_schedule_occurrences
    FOR EACH ROW
    EXECUTE FUNCTION prevent_occurrence_mutation();

-- Suspender fecha em lote as PENDING existentes - nenhum trabalho sobrevive para disparar
-- silenciosamente na retomada. Roda depois do UPDATE que suspende (mesma transacao), sobre o
-- lock que a propria UPDATE de automation_schedules ja segura implicitamente.
CREATE FUNCTION close_pending_occurrences_on_suspend() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 1 AND NEW.status = 2 THEN
        UPDATE automation_schedule_occurrences
        SET status = 3, skip_reason = 3, decided_at = clock_timestamp()
        WHERE schedule_id = NEW.schedule_id AND status = 1;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_automation_schedules_close_pending_on_suspend
    AFTER UPDATE ON automation_schedules
    FOR EACH ROW
    EXECUTE FUNCTION close_pending_occurrences_on_suspend();

-- === Evolucao aditiva de 006/008: checagens de criacao e imutabilidade de origem ===
--
-- CREATE OR REPLACE redefine de novo as funcoes ja estendidas por 008 (o corpo completo e
-- reescrito aqui) - tecnica deliberada para nao reabrir 006_executions.sql nem
-- 008_parametros_secrets.sql. As triggers ja existentes continuam apontando para os mesmos
-- nomes de funcao.

CREATE OR REPLACE FUNCTION enforce_execution_creation_prerequisites() RETURNS TRIGGER AS $$
DECLARE
    org_status SMALLINT;
    ws_status SMALLINT;
    automation_status SMALLINT;
    version_status SMALLINT;
    version_command JSONB;
    dep RECORD;
    secret_revoked TIMESTAMPTZ;
    revision_authorized_by UUID;
    revision_automation_version UUID;
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

    -- 009: origem agendada precisa conservar exatamente a autorizacao e a versao registradas
    -- na revisao da ocorrencia - pertencer a mesma organizacao nao basta.
    IF NEW.origin = 2 THEN
        SELECT r.authorized_by_membership_id, r.automation_version_id
        INTO revision_authorized_by, revision_automation_version
        FROM automation_schedule_occurrences o
        JOIN automation_schedule_revisions r ON r.revision_id = o.revision_id
        WHERE o.occurrence_id = NEW.schedule_occurrence_id;

        IF revision_authorized_by IS DISTINCT FROM NEW.authorized_by_membership_id THEN
            RAISE EXCEPTION 'authorized_by_membership_id nao corresponde ao responsavel da revisao (execution_id=%)', NEW.execution_id;
        END IF;
        IF revision_automation_version IS DISTINCT FROM NEW.automation_version_id THEN
            RAISE EXCEPTION 'automation_version_id nao corresponde a revisao da ocorrencia (execution_id=%)', NEW.execution_id;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION prevent_execution_mutation() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status IN (4, 5, 6) THEN
        RAISE EXCEPTION 'execucao em estado terminal e imutavel (execution_id=%)', OLD.execution_id;
    END IF;

    IF NEW.execution_id <> OLD.execution_id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.automation_id <> OLD.automation_id
       OR NEW.automation_version_id <> OLD.automation_version_id
       OR NEW.request_key <> OLD.request_key
       OR NEW.request_fingerprint <> OLD.request_fingerprint
       OR NEW.requested_by_membership_id IS DISTINCT FROM OLD.requested_by_membership_id
       OR NEW.retry_of_execution_id IS DISTINCT FROM OLD.retry_of_execution_id
       OR NEW.command_snapshot <> OLD.command_snapshot
       OR NEW.created_at <> OLD.created_at
       OR NEW.origin <> OLD.origin
       OR NEW.authorized_by_membership_id IS DISTINCT FROM OLD.authorized_by_membership_id
       OR NEW.schedule_occurrence_id IS DISTINCT FROM OLD.schedule_occurrence_id THEN
        RAISE EXCEPTION 'origem da execucao e imutavel (execution_id=%)', OLD.execution_id;
    END IF;

    IF OLD.worker_id IS NOT NULL AND (
        NEW.worker_id <> OLD.worker_id
        OR NEW.worker_agent_assignment_id <> OLD.worker_agent_assignment_id
        OR NEW.worker_workspace_assignment_id <> OLD.worker_workspace_assignment_id
    ) THEN
        RAISE EXCEPTION 'alocacao da execucao e imutavel apos definida (execution_id=%)', OLD.execution_id;
    END IF;

    IF NEW.status <> OLD.status AND NOT (
        (OLD.status = 1 AND NEW.status IN (2, 6))
        OR (OLD.status = 2 AND NEW.status IN (3, 5, 6))
        OR (OLD.status = 3 AND NEW.status IN (4, 5, 6))
    ) THEN
        RAISE EXCEPTION 'transicao de status invalida (execution_id=%, % -> %)', OLD.execution_id, OLD.status, NEW.status;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- === Processamento atomico da ocorrencia ===
--
-- p_authorized: a aplicacao ja verificou, antes de chamar, se authorized_by_membership_id da
-- revisao ainda tem a permissao necessaria - o banco nao avalia RBAC, so recebe o resultado.
--
-- Passos 5-9 sao checagens explicitas e antecipadas; qualquer coisa fora delas (violacao de
-- constraint nao prevista, bug) propaga a excecao normalmente e desfaz a transacao - esta
-- funcao nao tem um EXCEPTION WHEN OTHERS generico que converteria erros inesperados num
-- SKIPPED. As triggers de execucao (acima) continuam protegendo INSERTs diretos.
-- Nomes de saida prefixados (out_...) deliberadamente: RETURNS TABLE declara variaveis
-- implicitas com esses nomes no corpo da funcao, e varias consultas abaixo referenciam colunas
-- chamadas "status" sem qualificador de tabela - sem o prefixo, isso e um erro de "column
-- reference is ambiguous" em tempo de execucao, nao so um estilo de nomenclatura.
CREATE FUNCTION process_schedule_occurrence(p_occurrence_id UUID, p_authorized BOOLEAN)
RETURNS TABLE (out_status SMALLINT, out_skip_reason SMALLINT, out_execution_id UUID) AS $$
DECLARE
    lookup_schedule_id UUID;
    occ RECORD;
    schedule_status SMALLINT;
    decision_instant TIMESTAMPTZ;
    revision RECORD;
    org_status SMALLINT;
    ws_status SMALLINT;
    automation_status SMALLINT;
    version_status SMALLINT;
    command_json_snapshot JSONB;
    new_execution_id UUID;
    dep RECORD;
    secret_revoked TIMESTAMPTZ;
BEGIN
    -- schedule_id de uma ocorrencia e origem imutavel - ler sem lock e seguro, so para saber
    -- qual agendamento travar primeiro.
    SELECT schedule_id INTO lookup_schedule_id FROM automation_schedule_occurrences WHERE occurrence_id = p_occurrence_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'ocorrencia inexistente (occurrence_id=%)', p_occurrence_id;
    END IF;

    -- 1) trava o agendamento PRIMEIRO - mesma ordem (agendamento -> ocorrencia) que a suspensao
    -- usa (UPDATE em automation_schedules, depois o fechamento em lote das PENDING). Travar na
    -- ordem oposta aqui arriscaria deadlock contra uma suspensao concorrente.
    SELECT s.status INTO schedule_status FROM automation_schedules s WHERE s.schedule_id = lookup_schedule_id FOR UPDATE;

    -- 2) trava a ocorrencia; reprocessamento e idempotente por construcao
    SELECT * INTO occ FROM automation_schedule_occurrences WHERE occurrence_id = p_occurrence_id FOR UPDATE;

    IF occ.status <> 1 THEN
        RETURN QUERY SELECT occ.status, occ.skip_reason, occ.execution_id;
        RETURN;
    END IF;

    -- 3) instante da decisao, sempre depois dos locks
    decision_instant := clock_timestamp();

    -- 4) tentativa antecipada e erro do chamador, nao uma decisao de negocio
    IF decision_instant < occ.scheduled_for THEN
        RAISE EXCEPTION 'tentativa de processar ocorrencia antes do horario previsto (occurrence_id=%, scheduled_for=%)', p_occurrence_id, occ.scheduled_for;
    END IF;

    -- 5) agendamento suspenso
    IF schedule_status <> 1 THEN
        UPDATE automation_schedule_occurrences SET status = 3, skip_reason = 3, decided_at = decision_instant
        WHERE occurrence_id = p_occurrence_id;
        RETURN QUERY SELECT 3::SMALLINT, 3::SMALLINT, NULL::UUID;
        RETURN;
    END IF;

    -- 6) vencida
    IF decision_instant >= occ.expires_at THEN
        UPDATE automation_schedule_occurrences SET status = 3, skip_reason = 1, decided_at = decision_instant
        WHERE occurrence_id = p_occurrence_id;
        RETURN QUERY SELECT 3::SMALLINT, 1::SMALLINT, NULL::UUID;
        RETURN;
    END IF;

    -- 7) autorizacao efetiva, decidida pela aplicacao antes de chamar
    IF NOT p_authorized THEN
        UPDATE automation_schedule_occurrences SET status = 3, skip_reason = 5, decided_at = decision_instant
        WHERE occurrence_id = p_occurrence_id;
        RETURN QUERY SELECT 3::SMALLINT, 5::SMALLINT, NULL::UUID;
        RETURN;
    END IF;

    -- 8) sobreposicao - escopada por agendamento, nao por automacao
    IF EXISTS (
        SELECT 1
        FROM automation_schedule_occurrences o
        JOIN executions e ON e.execution_id = o.execution_id
        WHERE o.schedule_id = occ.schedule_id
          AND o.occurrence_id <> p_occurrence_id
          AND e.status IN (1, 2, 3)
    ) THEN
        UPDATE automation_schedule_occurrences SET status = 3, skip_reason = 2, decided_at = decision_instant
        WHERE occurrence_id = p_occurrence_id;
        RETURN QUERY SELECT 3::SMALLINT, 2::SMALLINT, NULL::UUID;
        RETURN;
    END IF;

    -- 9) dependencias - checagem proativa (nao a conversao de uma excecao de INSERT rejeitado)
    SELECT * INTO revision FROM automation_schedule_revisions WHERE revision_id = occ.revision_id;

    SELECT status INTO org_status FROM organizations WHERE organization_id = occ.organization_id;
    SELECT status INTO ws_status FROM workspaces WHERE workspace_id = occ.workspace_id;
    SELECT status INTO automation_status FROM automations WHERE automation_id = revision.automation_id;
    SELECT status, command_json INTO version_status, command_json_snapshot
    FROM automation_versions WHERE automation_version_id = revision.automation_version_id;

    IF org_status IS DISTINCT FROM 1 OR ws_status IS DISTINCT FROM 1
       OR automation_status IS DISTINCT FROM 1 OR version_status IS DISTINCT FROM 2 THEN
        UPDATE automation_schedule_occurrences SET status = 3, skip_reason = 4, decided_at = decision_instant
        WHERE occurrence_id = p_occurrence_id;
        RETURN QUERY SELECT 3::SMALLINT, 4::SMALLINT, NULL::UUID;
        RETURN;
    END IF;

    FOR dep IN
        SELECT sb.secret_id AS dep_id
        FROM automation_version_bindings b
        JOIN automation_version_secret_bindings sb ON sb.binding_id = b.binding_id
        WHERE b.automation_version_id = revision.automation_version_id
        ORDER BY sb.secret_id::text
    LOOP
        SELECT revoked_at INTO secret_revoked FROM workspace_secrets WHERE secret_id = dep.dep_id FOR UPDATE;
        IF secret_revoked IS NOT NULL THEN
            UPDATE automation_schedule_occurrences SET status = 3, skip_reason = 4, decided_at = decision_instant
            WHERE occurrence_id = p_occurrence_id;
            RETURN QUERY SELECT 3::SMALLINT, 4::SMALLINT, NULL::UUID;
            RETURN;
        END IF;
    END LOOP;

    -- 10) elegivel - cria a execucao; a trigger AFTER INSERT finaliza a ocorrencia nesta mesma
    -- transacao (sem segundo comando da aplicacao no caminho de sucesso).
    INSERT INTO executions (
        organization_id, workspace_id, automation_id, automation_version_id,
        request_key, request_fingerprint, origin, authorized_by_membership_id,
        schedule_occurrence_id, command_snapshot, status
    ) VALUES (
        occ.organization_id, occ.workspace_id, revision.automation_id, revision.automation_version_id,
        'schedule:' || p_occurrence_id::text, encode(digest(p_occurrence_id::text, 'sha256'), 'hex'),
        2, revision.authorized_by_membership_id,
        p_occurrence_id, command_json_snapshot, 1
    ) RETURNING executions.execution_id INTO new_execution_id;

    RETURN QUERY SELECT 2::SMALLINT, NULL::SMALLINT, new_execution_id;
END;
$$ LANGUAGE plpgsql;
