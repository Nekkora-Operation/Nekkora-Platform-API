-- Nekkora Platform - eventos estruturados de execucao e logs tecnicos do Agent.
-- Depende de 001-006.
--
-- Codigos de status (SMALLINT, explicitos e permanentes - nao reordenar nem reutilizar):
--   execution_events.event_type : 1=PROCESS_STARTED, 2=PROCESS_SUCCEEDED, 3=PROCESS_FAILED,
--                                  4=PROCESS_START_FAILED, 5=PROCESS_CANCELLED
--   logs.channel                : 1=AGENT, 2=STDOUT, 3=STDERR
--   payload.level (so canal AGENT) : 1=DEBUG, 2=INFO, 3=WARN, 4=ERROR
--
-- Escopo restrito ao ciclo de vida do processo - detalhes de etapas da automacao ficam
-- para evolucao explicita futura. Persistir um evento/log nao reabre execucao terminal
-- nem altera seu estado automaticamente - isso e responsabilidade de uma futura ingestao,
-- que preserva as transicoes ja validadas na 006.

-- === Pre-requisito: octet_length(payload::text) pressupoe banco em UTF-8 ===

DO $$
BEGIN
    IF current_setting('server_encoding') <> 'UTF8' THEN
        RAISE EXCEPTION 'esta migration pressupoe banco em UTF8 (encoding atual: %)', current_setting('server_encoding');
    END IF;
END $$;

-- === Evolucao aditiva de 006 (nova UNIQUE, sem editar 006_executions.sql) ===

ALTER TABLE executions
    ADD CONSTRAINT executions_org_workspace_execution_key
    UNIQUE (organization_id, workspace_id, execution_id);

-- === Validacao estrutural dos payloads ===
--
-- Mesmo cuidado do contrato de command_json (005/006):
--   1) toda funcao encerra com "... IS TRUE" - CHECK tambem aceita resultado NULL, so
--      rejeita FALSE; sem o IS TRUE, ausencia/null passariam despercebidos.
--   2) nenhum cast (::numeric, ::boolean) roda sem estar dentro de um CASE que so o
--      alcanca depois de confirmar jsonb_typeof - AND/OR nao garantem ordem de avaliacao
--      no Postgres, entao um cast fora de um CASE pode lancar erro mesmo quando o ramo
--      "logico" que o precede already retornaria FALSE.
--   3) "chave desconhecida" usa jsonb - text[] (remove as chaves permitidas; sobrou algo
--      = chave desconhecida), em vez de enumerar exaustivamente combinacoes invalidas.

CREATE FUNCTION is_valid_event_payload(p_event_type SMALLINT, payload JSONB) RETURNS BOOLEAN
LANGUAGE sql IMMUTABLE AS $$
    SELECT (
        (payload ? 'schema_version')
        AND (payload -> 'schema_version') = to_jsonb(1)
        AND CASE p_event_type
            WHEN 1 THEN -- PROCESS_STARTED: pid inteiro positivo
                (payload - ARRAY['schema_version', 'pid']) = '{}'::jsonb
                AND (payload ? 'pid')
                AND jsonb_typeof(payload -> 'pid') = 'number'
                AND CASE WHEN jsonb_typeof(payload -> 'pid') = 'number' THEN
                        (payload ->> 'pid')::numeric > 0
                        AND (payload ->> 'pid')::numeric = trunc((payload ->> 'pid')::numeric)
                    ELSE FALSE END
            WHEN 2 THEN -- PROCESS_SUCCEEDED: exit_code inteiro, sem process_started
                (payload - ARRAY['schema_version', 'exit_code']) = '{}'::jsonb
                AND (payload ? 'exit_code')
                AND jsonb_typeof(payload -> 'exit_code') = 'number'
                AND CASE WHEN jsonb_typeof(payload -> 'exit_code') = 'number' THEN
                        (payload ->> 'exit_code')::numeric = trunc((payload ->> 'exit_code')::numeric)
                    ELSE FALSE END
            WHEN 3 THEN -- PROCESS_FAILED: exit_code inteiro obrigatorio, error_summary opcional; sem process_started
                (payload - ARRAY['schema_version', 'exit_code', 'error_summary']) = '{}'::jsonb
                AND (payload ? 'exit_code')
                AND jsonb_typeof(payload -> 'exit_code') = 'number'
                AND CASE WHEN jsonb_typeof(payload -> 'exit_code') = 'number' THEN
                        (payload ->> 'exit_code')::numeric = trunc((payload ->> 'exit_code')::numeric)
                    ELSE FALSE END
                AND (NOT (payload ? 'error_summary') OR jsonb_typeof(payload -> 'error_summary') = 'string')
            WHEN 4 THEN -- PROCESS_START_FAILED: error_code string nao vazia obrigatorio, error_summary opcional
                (payload - ARRAY['schema_version', 'error_code', 'error_summary']) = '{}'::jsonb
                AND (payload ? 'error_code')
                AND jsonb_typeof(payload -> 'error_code') = 'string'
                AND length(btrim(payload ->> 'error_code')) > 0
                AND (NOT (payload ? 'error_summary') OR jsonb_typeof(payload -> 'error_summary') = 'string')
            WHEN 5 THEN -- PROCESS_CANCELLED: process_started obrigatorio; exit_code opcional SO quando process_started=true
                (payload - ARRAY['schema_version', 'process_started', 'exit_code']) = '{}'::jsonb
                AND (payload ? 'process_started')
                AND jsonb_typeof(payload -> 'process_started') = 'boolean'
                AND (
                    (payload -> 'process_started') = 'true'::jsonb
                    OR NOT (payload ? 'exit_code')
                )
                AND (
                    NOT (payload ? 'exit_code')
                    OR (
                        jsonb_typeof(payload -> 'exit_code') = 'number'
                        AND CASE WHEN jsonb_typeof(payload -> 'exit_code') = 'number' THEN
                                (payload ->> 'exit_code')::numeric = trunc((payload ->> 'exit_code')::numeric)
                            ELSE FALSE END
                    )
                )
            ELSE FALSE
        END
    ) IS TRUE;
$$;

CREATE FUNCTION is_valid_log_payload(p_channel SMALLINT, payload JSONB) RETURNS BOOLEAN
LANGUAGE sql IMMUTABLE AS $$
    SELECT (
        (payload ? 'schema_version')
        AND (payload -> 'schema_version') = to_jsonb(1)
        AND (payload ? 'message')
        AND jsonb_typeof(payload -> 'message') = 'string'
        AND CASE WHEN p_channel = 1 THEN -- AGENT: level obrigatorio
                (payload - ARRAY['schema_version', 'message', 'level']) = '{}'::jsonb
                AND (payload ? 'level')
                AND jsonb_typeof(payload -> 'level') = 'number'
                AND CASE WHEN jsonb_typeof(payload -> 'level') = 'number' THEN
                        (payload ->> 'level')::numeric IN (1, 2, 3, 4)
                    ELSE FALSE END
            ELSE -- STDOUT/STDERR: level ausente (nao so nulo)
                (payload - ARRAY['schema_version', 'message']) = '{}'::jsonb
                AND NOT (payload ? 'level')
            END
    ) IS TRUE;
$$;

-- === execution_events ===

CREATE TABLE execution_events (
    event_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    workspace_id    UUID NOT NULL,
    execution_id    UUID NOT NULL,
    agent_id        UUID NOT NULL,
    sequence        BIGINT NOT NULL CHECK (sequence > 0),
    event_type      SMALLINT NOT NULL CHECK (event_type IN (1, 2, 3, 4, 5)),
    occurred_at     TIMESTAMPTZ NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    payload         JSONB NOT NULL,
    fingerprint     TEXT NOT NULL CHECK (btrim(fingerprint) <> ''),
    UNIQUE (execution_id, sequence),
    FOREIGN KEY (organization_id, workspace_id, execution_id)
        REFERENCES executions (organization_id, workspace_id, execution_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, agent_id) REFERENCES agents (organization_id, agent_id) ON DELETE RESTRICT,
    CHECK (octet_length(payload::text) <= 16384),
    CHECK (payload ->> 'error_summary' IS NULL OR octet_length(payload ->> 'error_summary') <= 2048),
    CHECK (is_valid_event_payload(event_type, payload) IS TRUE)
);

COMMENT ON COLUMN execution_events.event_type IS '1=PROCESS_STARTED, 2=PROCESS_SUCCEEDED, 3=PROCESS_FAILED, 4=PROCESS_START_FAILED, 5=PROCESS_CANCELLED';

CREATE INDEX ix_execution_events_execution_id ON execution_events (execution_id);

-- Execucao sem alocacao nao pode receber evento; agent deve corresponder a alocacao
-- historica registrada na execucao (nao a associacao atualmente ativa no Worker).
CREATE FUNCTION enforce_execution_event_agent_origin() RETURNS TRIGGER AS $$
DECLARE
    exec_waa_id UUID;
    waa_agent_id UUID;
BEGIN
    SELECT worker_agent_assignment_id INTO exec_waa_id FROM executions WHERE execution_id = NEW.execution_id;
    IF exec_waa_id IS NULL THEN
        RAISE EXCEPTION 'execucao sem alocacao nao pode receber evento (execution_id=%)', NEW.execution_id;
    END IF;

    SELECT agent_id INTO waa_agent_id FROM worker_agent_assignments WHERE assignment_id = exec_waa_id;
    IF waa_agent_id IS DISTINCT FROM NEW.agent_id THEN
        RAISE EXCEPTION 'agent nao corresponde a alocacao historica da execucao (event_id=%)', NEW.event_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_execution_events_enforce_agent_origin
    BEFORE INSERT ON execution_events
    FOR EACH ROW
    EXECUTE FUNCTION enforce_execution_event_agent_origin();

CREATE TRIGGER trg_execution_events_prevent_update
    BEFORE UPDATE ON execution_events
    FOR EACH ROW
    EXECUTE FUNCTION prevent_update();

CREATE TRIGGER trg_execution_events_prevent_delete
    BEFORE DELETE ON execution_events
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_execution_events_prevent_truncate
    BEFORE TRUNCATE ON execution_events
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === logs ===

CREATE TABLE logs (
    log_id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             UUID NOT NULL,
    workspace_id                UUID,
    agent_id                    UUID NOT NULL,
    worker_agent_assignment_id  UUID,
    execution_id                UUID,
    channel                     SMALLINT NOT NULL CHECK (channel IN (1, 2, 3)),
    occurred_at                 TIMESTAMPTZ NOT NULL,
    received_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    payload                     JSONB NOT NULL,
    fingerprint                 TEXT NOT NULL CHECK (btrim(fingerprint) <> ''),
    FOREIGN KEY (organization_id, agent_id) REFERENCES agents (organization_id, agent_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, workspace_id, execution_id)
        REFERENCES executions (organization_id, workspace_id, execution_id) ON DELETE RESTRICT,
    FOREIGN KEY (worker_agent_assignment_id) REFERENCES worker_agent_assignments (assignment_id) ON DELETE RESTRICT,
    CHECK (channel = 1 OR execution_id IS NOT NULL),
    CHECK ((execution_id IS NULL) = (workspace_id IS NULL)),
    CHECK (execution_id IS NULL OR worker_agent_assignment_id IS NOT NULL),
    CHECK (octet_length(payload::text) <= 65536),
    CHECK (is_valid_log_payload(channel, payload) IS TRUE)
);

COMMENT ON COLUMN logs.channel IS '1=AGENT, 2=STDOUT, 3=STDERR';

CREATE INDEX ix_logs_execution_id ON logs (execution_id) WHERE execution_id IS NOT NULL;
CREATE INDEX ix_logs_agent_id ON logs (agent_id);

-- Com execucao: worker_agent_assignment_id ja e exigido pelo CHECK acima, e deve ser
-- exatamente o registrado na execucao (nao so uma associacao qualquer do mesmo agent).
-- Sem execucao: uma associacao informada ainda precisa pertencer ao agent indicado.
-- Em nenhum dos dois casos a associacao precisa continuar ativa - so a identidade importa.
CREATE FUNCTION enforce_log_historical_origin() RETURNS TRIGGER AS $$
DECLARE
    exec_waa_id UUID;
    waa_agent_id UUID;
BEGIN
    IF NEW.execution_id IS NOT NULL THEN
        SELECT worker_agent_assignment_id INTO exec_waa_id FROM executions WHERE execution_id = NEW.execution_id;
        IF exec_waa_id IS NULL THEN
            RAISE EXCEPTION 'execucao sem alocacao nao pode receber log vinculado (execution_id=%)', NEW.execution_id;
        END IF;
        IF NEW.worker_agent_assignment_id IS DISTINCT FROM exec_waa_id THEN
            RAISE EXCEPTION 'associacao informada diverge da registrada na execucao (execution_id=%)', NEW.execution_id;
        END IF;
    END IF;

    IF NEW.worker_agent_assignment_id IS NOT NULL THEN
        SELECT agent_id INTO waa_agent_id FROM worker_agent_assignments WHERE assignment_id = NEW.worker_agent_assignment_id;
        IF waa_agent_id IS DISTINCT FROM NEW.agent_id THEN
            RAISE EXCEPTION 'agent nao corresponde a associacao informada (log_id=%)', NEW.log_id;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_logs_enforce_historical_origin
    BEFORE INSERT ON logs
    FOR EACH ROW
    EXECUTE FUNCTION enforce_log_historical_origin();

CREATE TRIGGER trg_logs_prevent_update
    BEFORE UPDATE ON logs
    FOR EACH ROW
    EXECUTE FUNCTION prevent_update();

CREATE TRIGGER trg_logs_prevent_delete
    BEFORE DELETE ON logs
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_logs_prevent_truncate
    BEFORE TRUNCATE ON logs
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();
