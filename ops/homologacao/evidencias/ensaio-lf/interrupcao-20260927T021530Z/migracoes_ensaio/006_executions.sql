-- Nekkora Platform - execucoes: tentativas de executar uma versao publicada de
-- automacao, dentro de um workspace, usando a capacidade de um Worker/Agent.
-- Depende de 001-005.
--
-- Codigos de status (SMALLINT, explicitos e permanentes - nao reordenar nem reutilizar):
--   executions.status        : 1=PENDING, 2=STARTING, 3=RUNNING, 4=SUCCEEDED, 5=FAILED, 6=CANCELLED
--   executions.status_reason : 1=PROCESS_COMPLETED, 2=START_FAILED, 3=PROCESS_FAILED,
--                              4=CANCELLED_BEFORE_DISPATCH, 5=CANCEL_CONFIRMED, 6=QUEUE_DEADLINE_EXCEEDED
--
-- Transicoes: PENDING->STARTING,CANCELLED; STARTING->RUNNING,FAILED,CANCELLED;
-- RUNNING->SUCCEEDED,FAILED,CANCELLED. Terminais (SUCCEEDED,FAILED,CANCELLED) sao
-- congelados por inteiro. Ausencia de heartbeat NAO encerra execucao nem libera Worker;
-- pedido de cancelamento NAO libera capacidade por si so.

-- === Evolucao aditiva de 004 (novas UNIQUE, sem editar 004_workers_agents.sql) ===

ALTER TABLE worker_agent_assignments
    ADD CONSTRAINT worker_agent_assignments_org_worker_assignment_key
    UNIQUE (organization_id, worker_id, assignment_id);

ALTER TABLE worker_workspace_assignments
    ADD CONSTRAINT worker_workspace_assignments_org_worker_ws_assignment_key
    UNIQUE (organization_id, worker_id, workspace_id, assignment_id);

-- === Executions ===

CREATE TABLE executions (
    execution_id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id                    UUID NOT NULL,
    workspace_id                       UUID NOT NULL,
    automation_id                      UUID NOT NULL,
    automation_version_id              UUID NOT NULL,
    request_key                        TEXT NOT NULL CHECK (btrim(request_key) <> ''),
    request_fingerprint                TEXT NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    requested_by_membership_id         UUID NOT NULL,
    retry_of_execution_id              UUID,
    command_snapshot                   JSONB NOT NULL CHECK (is_valid_command_contract(command_snapshot) IS TRUE),
    worker_id                          UUID,
    worker_agent_assignment_id         UUID,
    worker_workspace_assignment_id     UUID,
    status                             SMALLINT NOT NULL CHECK (status IN (1, 2, 3, 4, 5, 6)),
    status_reason                      SMALLINT CHECK (status_reason IN (1, 2, 3, 4, 5, 6)),
    error_summary                      TEXT,
    pid                                INTEGER,
    created_at                         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                         TIMESTAMPTZ NOT NULL DEFAULT now(),
    reserved_at                        TIMESTAMPTZ,
    dispatched_at                      TIMESTAMPTZ,
    started_at                         TIMESTAMPTZ,
    finished_at                        TIMESTAMPTZ,
    exit_code                          INTEGER,
    cancel_requested_at                TIMESTAMPTZ,
    cancel_requested_by_membership_id  UUID,

    UNIQUE (organization_id, workspace_id, request_key),
    UNIQUE (organization_id, workspace_id, automation_id, execution_id),

    FOREIGN KEY (organization_id, workspace_id)
        REFERENCES workspaces (organization_id, workspace_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, workspace_id, automation_id)
        REFERENCES automations (organization_id, workspace_id, automation_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, workspace_id, automation_id, automation_version_id)
        REFERENCES automation_versions (organization_id, workspace_id, automation_id, automation_version_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, requested_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, cancel_requested_by_membership_id)
        REFERENCES organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, workspace_id, automation_id, retry_of_execution_id)
        REFERENCES executions (organization_id, workspace_id, automation_id, execution_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, worker_id, worker_agent_assignment_id)
        REFERENCES worker_agent_assignments (organization_id, worker_id, assignment_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, worker_id, workspace_id, worker_workspace_assignment_id)
        REFERENCES worker_workspace_assignments (organization_id, worker_id, workspace_id, assignment_id) ON DELETE RESTRICT,

    CHECK (retry_of_execution_id IS NULL OR retry_of_execution_id <> execution_id),
    CHECK ((worker_id IS NULL) = (worker_agent_assignment_id IS NULL)),
    CHECK ((worker_id IS NULL) = (worker_workspace_assignment_id IS NULL)),
    CHECK ((worker_id IS NULL) = (reserved_at IS NULL)),
    CHECK (status <> 1 OR worker_id IS NULL),
    CHECK ((status IN (4, 5, 6)) = (finished_at IS NOT NULL)),
    CHECK ((status IN (4, 5, 6)) = (status_reason IS NOT NULL)),
    CHECK (
        status_reason IS NULL
        OR (status_reason = 1 AND status = 4)
        OR (status_reason IN (2, 3) AND status = 5)
        OR (status_reason IN (4, 5, 6) AND status = 6)
    ),
    CHECK ((cancel_requested_at IS NULL) = (cancel_requested_by_membership_id IS NULL)),
    CHECK (started_at IS NULL OR reserved_at IS NULL OR started_at >= reserved_at),
    CHECK (dispatched_at IS NULL OR reserved_at IS NULL OR dispatched_at >= reserved_at),
    CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at),
    CHECK (finished_at IS NULL OR finished_at >= created_at)
);

COMMENT ON COLUMN executions.status IS '1=PENDING, 2=STARTING, 3=RUNNING, 4=SUCCEEDED, 5=FAILED, 6=CANCELLED';
COMMENT ON COLUMN executions.status_reason IS '1=PROCESS_COMPLETED, 2=START_FAILED, 3=PROCESS_FAILED, 4=CANCELLED_BEFORE_DISPATCH, 5=CANCEL_CONFIRMED, 6=QUEUE_DEADLINE_EXCEEDED';
COMMENT ON COLUMN executions.reserved_at IS 'entrar em STARTING registra a reserva - nao comprova entrega do comando';
COMMENT ON COLUMN executions.dispatched_at IS 'horario em que o servidor iniciou a tentativa de envio - nao e confirmacao de recebimento';

-- No maximo uma execucao STARTING/RUNNING por Worker.
CREATE UNIQUE INDEX ux_executions_active_worker
    ON executions (organization_id, worker_id) WHERE status IN (2, 3);

CREATE INDEX ix_executions_automation_id ON executions (automation_id);
CREATE INDEX ix_executions_worker_id ON executions (worker_id) WHERE worker_id IS NOT NULL;

-- Checagens antes de aceitar a solicitacao (INSERT): organizacao/workspace/automacao
-- habilitados e versao efetivamente publicada. Repetido na reserva (trigger seguinte)
-- porque o estado pode mudar entre a criacao e o despacho.
CREATE FUNCTION enforce_execution_creation_prerequisites() RETURNS TRIGGER AS $$
DECLARE
    org_status SMALLINT;
    ws_status SMALLINT;
    automation_status SMALLINT;
    version_status SMALLINT;
    version_command JSONB;
BEGIN
    -- Execucao nasce PENDING, sem nenhum dado de ciclo de vida - impede insercao
    -- direta em STARTING/RUNNING/terminal e alocacao parcial ja na criacao.
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

    -- is_valid_command_contract() so comprova formato; sem isto, um snapshot valido
    -- mas diferente do comando publicado passaria despercebido. Como a v1 nao permite
    -- parametros por execucao, a correspondencia exata e exigida na criacao.
    IF NEW.command_snapshot <> version_command THEN
        RAISE EXCEPTION 'command_snapshot nao corresponde ao comando da versao referenciada (execution_id=%)', NEW.execution_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_executions_enforce_creation_prerequisites
    BEFORE INSERT ON executions
    FOR EACH ROW
    EXECUTE FUNCTION enforce_execution_creation_prerequisites();

-- Checagens no momento da reserva (PENDING -> STARTING): organizacao, workspace,
-- automacao, Worker e Agent habilitados, associacao Worker-Agent ativa e autorizacao
-- Worker-workspace ativa. Ordem fixa de aquisicao de lock (FOR UPDATE), seguida por
-- todo o caminho de reserva: organizations -> workspaces -> automations -> workers ->
-- worker_agent_assignments -> agents -> worker_workspace_assignments. Nenhum outro
-- caminho de escrita precisa de mais de um desses locks ao mesmo tempo, entao essa
-- ordem unica evita deadlock. Quem obtem cada lock primeiro decide o resultado daquela
-- linha; o outro le o estado ja committed.
--
-- Consequencia aceita, sem redesenho nesta rodada: o lock exclusivo em organizations
-- serializa TODAS as reservas daquela organizacao, inclusive para Workers diferentes.
-- Por isso a transacao de reserva precisa ser curta, sem chamadas de rede dentro dela.
CREATE FUNCTION enforce_execution_reservation_prerequisites() RETURNS TRIGGER AS $$
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

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_executions_enforce_reservation_prerequisites
    BEFORE UPDATE ON executions
    FOR EACH ROW
    EXECUTE FUNCTION enforce_execution_reservation_prerequisites();

-- Congelamento terminal + imutabilidade de origem + escrita unica da alocacao +
-- validacao de transicao, tudo num so trigger para nao depender de ordem entre
-- triggers (licao da correcao de 003/004).
CREATE FUNCTION prevent_execution_mutation() RETURNS TRIGGER AS $$
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
       OR NEW.requested_by_membership_id <> OLD.requested_by_membership_id
       OR NEW.retry_of_execution_id IS DISTINCT FROM OLD.retry_of_execution_id
       OR NEW.command_snapshot <> OLD.command_snapshot
       OR NEW.created_at <> OLD.created_at THEN
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

CREATE TRIGGER trg_executions_prevent_mutation
    BEFORE UPDATE ON executions
    FOR EACH ROW
    EXECUTE FUNCTION prevent_execution_mutation();

CREATE TRIGGER trg_executions_set_updated_at
    BEFORE UPDATE ON executions
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_executions_prevent_delete
    BEFORE DELETE ON executions
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_executions_prevent_truncate
    BEFORE TRUNCATE ON executions
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === Dependencia da 004 que nasce aqui: nao trocar Agent com execucao ativa ===
--
-- Worker-Agent: encerrar a associacao (ended_at) e bloqueado enquanto houver execucao
-- STARTING/RUNNING referenciando-a. Worker-workspace NAO e bloqueada (revogacao sempre
-- sucede) - decisao explicita, ver relatorio de preparacao.

CREATE FUNCTION prevent_worker_agent_end_during_execution() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.ended_at IS NULL AND NEW.ended_at IS NOT NULL THEN
        IF EXISTS (
            SELECT 1 FROM executions
            WHERE worker_agent_assignment_id = OLD.assignment_id
              AND status IN (2, 3)
        ) THEN
            RAISE EXCEPTION 'associacao worker-agent com execucao ativa nao pode ser encerrada (assignment_id=%)', OLD.assignment_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_worker_agent_assignments_prevent_end_during_execution
    BEFORE UPDATE ON worker_agent_assignments
    FOR EACH ROW
    EXECUTE FUNCTION prevent_worker_agent_end_during_execution();
