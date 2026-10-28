-- Nekkora Platform - canal Platform-Agent 1.0: pareamento e credencial de maquina, autorizacao de execucao local, registro
-- da execucao local, relatorios do supervisor e ingestao do envelope nekkora-agent/observability 1.0.0.
-- Contrato: docs/relatorios/2026-10-28-platform-agent-contrato.md. Inventario: 2026-10-28-platform-agent-inventario.md.
--
-- CANDIDATA ate a aprovacao do relatorio da suite db/tests/canal-agent-025.
--
-- O que muda (tudo ADITIVO; nenhuma tabela anterior muda de forma, so recebe concessoes):
--   * agent_enrollments: codigo de USO UNICO (so o SHA-256 e guardado) criado por uma pessoa (pareamento de um Agent novo a
--     um Worker, ou rotacao da credencial de um Agent existente) e resgatado pela maquina. A autoridade de quem criou e
--     conferida na criacao E de novo no resgate (gatilho, mesma regra da 023: access_org_permission);
--   * agent_presence: capacidades e versao de contrato informadas no heartbeat (o instante fica em agents.last_heartbeat_at);
--   * local_run_authorizations: autorizacao de execucao local vinculada a organizacao, workspace, Worker AUTORIZADO no
--     workspace, versao PUBLICADA e pacote imutavel. O pacote e identificado pela impressao digital do Agent com regra
--     explicita (package_fingerprint_rule = 'nekkora-agent/package-fingerprint/1', conteudo normalizado), declarada na versao
--     em command_json.agent_package {fingerprint_rule, fingerprint}. NAO e command_json.artifact.sha256 (bytes do artefato
--     distribuido): as duas medidas nao se confundem (contrato, sec. 6.4);
--   * agent_runs: registro da execucao local (identidade local agent_execution_id unica por Agent; nunca uma linha de
--     executions), com o estado do SUPERVISOR (vocabulario do Agent, inclusive TIMED_OUT, BLOCKED e OUTCOME_UNKNOWN) e a
--     projecao da entrega dos eventos (stored_through/settled_through);
--   * agent_run_reports: todo relatorio do supervisor recebido (aplicado, inalterado, atrasado ou em conflito), so INSERT;
--   * agent_run_events: envelopes validados (identidade agent_run_id + sequence; conteudo canonico com SHA-256), imutaveis;
--   * agent_run_missing: intervalos de sequence que o Agent declarou que NUNCA vai entregar (perda na origem); nao contam
--     como recebidos;
--   * agent_ingestion_refusals: recusas da ingestao (sequence com conteudo diferente, declaracao sobre evento ja gravado,
--     evento invalido, relatorio contraditorio, correlacao divergente), so INSERT;
--   * agent_admin_events: auditoria dos atos (pessoa ou maquina), sem credencial nem codigo, so INSERT.
--
-- Papeis: nekkora_platform (pessoas: cria codigos e autorizacoes, consulta) e nekkora_ingestion (maquina: resgate, heartbeat,
-- registro, relatorios e eventos). Nenhum papel novo, nenhuma funcao SECURITY DEFINER, nenhum dono novo: SEM elevacao.
-- A organizacao da maquina vem da credencial conferida sob a RLS (contrato, sec. 4.4).
--
-- Codigos (SMALLINT, explicitos e permanentes - nao reordenar nem reutilizar):
--   agent_enrollments.purpose                     : 1=PAREAMENTO, 2=ROTACAO
--   agent_runs.origin                             : 1=LOCAL_AUTHORIZED (2=PLATFORM_DISPATCH reservado)
--   agent_runs.supervisor_state / reports.state   : 1=READY, 2=STARTING, 3=RUNNING, 4=SUCCEEDED, 5=FAILED, 6=CANCELLED,
--                                                   7=TIMED_OUT, 8=BLOCKED, 9=OUTCOME_UNKNOWN (terminais: 4-9)
--   agent_runs.authorization_state_at_registration : 1=ACTIVE, 2=REVOKED, 3=EXPIRED
--   agent_run_reports.outcome                     : 1=APPLIED, 2=UNCHANGED, 3=STALE, 4=CONFLICT
--   agent_run_events.source                       : 1=AGENT, 2=FRAMEWORK, 3=COLLECTOR
--   agent_run_events.severity                     : 1=INFO, 2=WARNING, 3=ERROR
--   agent_run_events.category                     : 1=LIFECYCLE, 2=ACTIVITY, 3=BUSINESS, 4=FAILURE, 5=TELEMETRY
--   agent_ingestion_refusals.kind                 : 1=SEQUENCE_CONFLICT, 2=MISSING_CONFLICT, 3=INVALID_EVENT,
--                                                   4=SUPERVISOR_CONFLICT, 5=FRAMEWORK_ID_CONFLICT
--   agent_admin_events.act                        : 1=CODIGO_PAREAMENTO_CRIADO, 2=CODIGO_CANCELADO, 3=PAREAMENTO_RESGATADO,
--                                                   4=CODIGO_ROTACAO_CRIADO, 5=CREDENCIAL_ROTACIONADA, 6=AGENT_REVOGADO,
--                                                   7=WORKER_CRIADO, 8=WORKER_AUTORIZADO_NO_WORKSPACE,
--                                                   9=WORKER_REVOGADO_NO_WORKSPACE, 10=EXECUCAO_LOCAL_AUTORIZADA,
--                                                   11=AUTORIZACAO_LOCAL_REVOGADA
--
-- Limites declarados: o expurgo por politica de retencao (010/011) e a consolidacao diaria de bytes (close_usage_day) NAO
-- cobrem agent_run_events nesta versao (pendencia explicita do contrato, sec. 11).
--
-- Depende de 001-024 (usa access_org_permission e access_membership_effective da 023). Atomica: uma transacao.

BEGIN;

-- === 1. Pre-condicao: 024 aplicada; objetos novos ausentes ===
DO $$
BEGIN
    IF to_regprocedure('public.access_issuer_authority_lost()') IS NULL
       OR to_regprocedure('public.access_org_permission(uuid,uuid,text)') IS NULL THEN
        RAISE EXCEPTION '025 exige a 024 aplicada';
    END IF;
    IF to_regclass('public.agent_enrollments') IS NOT NULL OR to_regclass('public.agent_presence') IS NOT NULL
       OR to_regclass('public.local_run_authorizations') IS NOT NULL OR to_regclass('public.agent_runs') IS NOT NULL
       OR to_regclass('public.agent_run_reports') IS NOT NULL OR to_regclass('public.agent_run_events') IS NOT NULL
       OR to_regclass('public.agent_run_missing') IS NOT NULL OR to_regclass('public.agent_ingestion_refusals') IS NOT NULL
       OR to_regclass('public.agent_admin_events') IS NOT NULL THEN
        RAISE EXCEPTION '025: objetos do canal do Agent ja existem';
    END IF;
END;
$$;

-- === 2. Autoridade por workspace (regra unica da API: papel de organizacao vale em todos; papel de workspace no proprio) ===
CREATE FUNCTION public.agent_workspace_permission(p_org UUID, p_membership UUID, p_workspace UUID, p_permission TEXT) RETURNS BOOLEAN
LANGUAGE sql STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT public.access_org_permission(p_org, p_membership, p_permission)
        OR (public.access_membership_effective(p_org, p_membership)
            AND EXISTS (SELECT 1 FROM public.workspace_role_assignments w
                          JOIN public.role_permissions rp ON rp.role_id = w.role_id
                          JOIN public.permissions p ON p.permission_id = rp.permission_id
                         WHERE w.organization_id = p_org AND w.workspace_id = p_workspace AND w.membership_id = p_membership
                           AND w.status = 1 AND p.code = p_permission))
$$;

-- Transicoes do supervisor (as do Agent, exec/ExecutionState.java): terminal nao sai; SUCCEEDED so a partir de RUNNING.
CREATE FUNCTION public.agent_run_transition_allowed(p_from SMALLINT, p_to SMALLINT) RETURNS BOOLEAN
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT CASE p_from
        WHEN 1 THEN p_to IN (2, 8, 6)
        WHEN 2 THEN p_to IN (3, 8, 5, 6, 9)
        WHEN 3 THEN p_to IN (4, 5, 6, 7, 9)
        ELSE false END
$$;

-- === 3. Tabelas ===

CREATE TABLE public.agent_enrollments (
    enrollment_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id            UUID NOT NULL,
    purpose                    SMALLINT NOT NULL CHECK (purpose IN (1, 2)),
    worker_id                  UUID NOT NULL,
    agent_id                   UUID,
    agent_name                 TEXT CHECK (agent_name IS NULL OR (char_length(agent_name) BETWEEN 1 AND 80
                                                                  AND btrim(agent_name) = agent_name AND agent_name !~ '[[:cntrl:]]')),
    code_hash                  TEXT NOT NULL UNIQUE CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    created_by_membership_id   UUID NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at                 TIMESTAMPTZ NOT NULL,
    consumed_at                TIMESTAMPTZ,
    consumed_credential_id     UUID REFERENCES public.agent_credentials (credential_id) ON DELETE RESTRICT,
    cancelled_at               TIMESTAMPTZ,
    cancelled_by_membership_id UUID,
    UNIQUE (organization_id, enrollment_id),
    FOREIGN KEY (organization_id, worker_id) REFERENCES public.workers (organization_id, worker_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, agent_id) REFERENCES public.agents (organization_id, agent_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, created_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, cancelled_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK (expires_at > created_at AND expires_at <= created_at + interval '60 minutes'),
    CHECK ((purpose = 1) = (agent_name IS NOT NULL)),
    CHECK (purpose = 1 OR agent_id IS NOT NULL),
    CHECK (purpose = 2 OR consumed_at IS NOT NULL OR agent_id IS NULL),
    CHECK ((consumed_at IS NULL) = (consumed_credential_id IS NULL)),
    CHECK ((cancelled_at IS NULL) = (cancelled_by_membership_id IS NULL)),
    CHECK (consumed_at IS NULL OR cancelled_at IS NULL),
    CHECK (consumed_at IS NULL OR consumed_at <= expires_at)
);
COMMENT ON COLUMN public.agent_enrollments.purpose IS '1=PAREAMENTO (Agent novo no Worker), 2=ROTACAO (credencial nova do Agent existente)';
COMMENT ON COLUMN public.agent_enrollments.code_hash IS 'SHA-256 hex do codigo nkp1 inteiro - o codigo nunca e persistido';
CREATE INDEX ix_agent_enrollments_worker ON public.agent_enrollments (organization_id, worker_id);

CREATE TABLE public.agent_presence (
    agent_id          UUID PRIMARY KEY,
    organization_id   UUID NOT NULL,
    contract_version  TEXT NOT NULL CHECK (contract_version ~ '^1\.[0-9]{1,3}$'),
    capabilities      TEXT[] NOT NULL CHECK (cardinality(capabilities) <= 8
                                             AND capabilities <@ ARRAY['RUN_LOCAL', 'OBS_1_0', 'SUPERVISOR_REPORT_1_0']::text[]),
    reported_at       TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (organization_id, agent_id) REFERENCES public.agents (organization_id, agent_id) ON DELETE RESTRICT
);

CREATE TABLE public.local_run_authorizations (
    authorization_id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id                UUID NOT NULL,
    workspace_id                   UUID NOT NULL,
    worker_id                      UUID NOT NULL,
    worker_workspace_assignment_id UUID NOT NULL,
    automation_id                  UUID NOT NULL,
    automation_version_id          UUID NOT NULL,
    package_fingerprint            TEXT NOT NULL CHECK (package_fingerprint ~ '^[0-9a-f]{64}$'),
    package_fingerprint_rule       TEXT NOT NULL DEFAULT 'nekkora-agent/package-fingerprint/1'
                                       CHECK (package_fingerprint_rule IN ('nekkora-agent/package-fingerprint/1')),
    granted_by_membership_id       UUID NOT NULL,
    granted_at                     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    valid_until                    TIMESTAMPTZ NOT NULL,
    revoked_at                     TIMESTAMPTZ,
    revoked_by_membership_id       UUID,
    UNIQUE (organization_id, authorization_id),
    FOREIGN KEY (organization_id, workspace_id, automation_id, automation_version_id)
        REFERENCES public.automation_versions (organization_id, workspace_id, automation_id, automation_version_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, worker_id, workspace_id, worker_workspace_assignment_id)
        REFERENCES public.worker_workspace_assignments (organization_id, worker_id, workspace_id, assignment_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, granted_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, revoked_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK (valid_until > granted_at AND valid_until <= granted_at + interval '366 days'),
    CHECK ((revoked_at IS NULL) = (revoked_by_membership_id IS NULL)),
    CHECK (revoked_at IS NULL OR revoked_at >= granted_at)
);
CREATE INDEX ix_local_run_authorizations_worker ON public.local_run_authorizations (organization_id, worker_id);
COMMENT ON COLUMN public.local_run_authorizations.package_fingerprint IS 'impressao do pacote pela regra package_fingerprint_rule (hex, sem o prefixo sha256:); nao e o sha256 dos bytes do artefato';

CREATE TABLE public.agent_runs (
    agent_run_id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id                     UUID NOT NULL,
    workspace_id                        UUID NOT NULL,
    automation_id                       UUID NOT NULL,
    automation_version_id               UUID NOT NULL,
    authorization_id                    UUID NOT NULL,
    agent_id                            UUID NOT NULL,
    worker_id                           UUID NOT NULL,
    worker_agent_assignment_id          UUID NOT NULL,
    agent_execution_id                  TEXT NOT NULL CHECK (agent_execution_id ~ '^ex-[0-9a-f]{32}$'),
    origin                              SMALLINT NOT NULL DEFAULT 1 CHECK (origin IN (1)),
    platform_execution_id               UUID,
    package_fingerprint                 TEXT NOT NULL CHECK (package_fingerprint ~ '^[0-9a-f]{64}$'),
    framework_execution_id              TEXT CHECK (framework_execution_id ~ '^ex-[0-9a-f]{8,64}$'),
    authorization_state_at_registration SMALLINT NOT NULL CHECK (authorization_state_at_registration IN (1, 2, 3)),
    registered_at                       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    -- supervisor (relogio do Agent guardado como informado; nunca ordena nada)
    supervisor_state                    SMALLINT NOT NULL CHECK (supervisor_state BETWEEN 1 AND 9),
    supervisor_code                     TEXT CHECK (supervisor_code ~ '^AGT-[A-Z]{2,5}-[0-9]{3}$'),
    exit_code                           INTEGER,
    supervisor_counts                   JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(supervisor_counts) = 'object'
                                                                                  AND octet_length(supervisor_counts::text) <= 1024),
    agent_created_at                    TIMESTAMPTZ,
    agent_started_at                    TIMESTAMPTZ,
    agent_ended_at                      TIMESTAMPTZ,
    adopted                             BOOLEAN NOT NULL DEFAULT false,
    suspensions                         INTEGER NOT NULL DEFAULT 0 CHECK (suspensions >= 0),
    locks                               INTEGER NOT NULL DEFAULT 0 CHECK (locks >= 0),
    supervisor_reports                  INTEGER NOT NULL DEFAULT 1 CHECK (supervisor_reports >= 1),
    supervisor_conflicts                INTEGER NOT NULL DEFAULT 0 CHECK (supervisor_conflicts >= 0),
    last_report_at                      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    -- entrega dos eventos (projecao mantida pela ingestao, na transacao do lote)
    events_stored                       BIGINT NOT NULL DEFAULT 0 CHECK (events_stored >= 0),
    stored_through                      BIGINT NOT NULL DEFAULT 0 CHECK (stored_through >= 0),
    settled_through                     BIGINT NOT NULL DEFAULT 0 CHECK (settled_through >= stored_through),
    max_sequence                        BIGINT NOT NULL DEFAULT 0 CHECK (max_sequence >= settled_through),
    declared_missing                    BIGINT NOT NULL DEFAULT 0 CHECK (declared_missing >= 0),
    collection_completed_sequence       BIGINT,
    lost_at_source                      BOOLEAN NOT NULL DEFAULT false,
    lost_local                          BOOLEAN NOT NULL DEFAULT false,
    purged_local                        BOOLEAN NOT NULL DEFAULT false,
    invalid_refused                     BIGINT NOT NULL DEFAULT 0 CHECK (invalid_refused >= 0),
    framework_events                    BIGINT NOT NULL DEFAULT 0 CHECK (framework_events >= 0),
    last_event_at                       TIMESTAMPTZ,
    UNIQUE (agent_id, agent_execution_id),
    UNIQUE (organization_id, agent_run_id),
    FOREIGN KEY (organization_id, authorization_id) REFERENCES public.local_run_authorizations (organization_id, authorization_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, workspace_id, automation_id, automation_version_id)
        REFERENCES public.automation_versions (organization_id, workspace_id, automation_id, automation_version_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, agent_id) REFERENCES public.agents (organization_id, agent_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, worker_id, worker_agent_assignment_id)
        REFERENCES public.worker_agent_assignments (organization_id, worker_id, assignment_id) ON DELETE RESTRICT,
    CHECK (origin <> 1 OR platform_execution_id IS NULL),
    CHECK (supervisor_state >= 4 OR supervisor_code IS NULL)
);
COMMENT ON COLUMN public.agent_runs.supervisor_state IS '1=READY, 2=STARTING, 3=RUNNING, 4=SUCCEEDED, 5=FAILED, 6=CANCELLED, 7=TIMED_OUT, 8=BLOCKED, 9=OUTCOME_UNKNOWN';
COMMENT ON COLUMN public.agent_runs.authorization_state_at_registration IS '1=ACTIVE, 2=REVOKED, 3=EXPIRED - a revogacao nao alcanca maquina desconectada; o fato e aceito e marcado';
CREATE INDEX ix_agent_runs_registered ON public.agent_runs (organization_id, registered_at, agent_run_id);
CREATE INDEX ix_agent_runs_workspace ON public.agent_runs (organization_id, workspace_id, registered_at);

CREATE TABLE public.agent_run_reports (
    report_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL,
    agent_run_id      UUID NOT NULL,
    received_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    outcome           SMALLINT NOT NULL CHECK (outcome IN (1, 2, 3, 4)),
    state             SMALLINT NOT NULL CHECK (state BETWEEN 1 AND 9),
    code              TEXT CHECK (code ~ '^AGT-[A-Z]{2,5}-[0-9]{3}$'),
    exit_code         INTEGER,
    counts            JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(counts) = 'object' AND octet_length(counts::text) <= 1024),
    agent_created_at  TIMESTAMPTZ,
    agent_started_at  TIMESTAMPTZ,
    agent_ended_at    TIMESTAMPTZ,
    adopted           BOOLEAN NOT NULL DEFAULT false,
    suspensions       INTEGER NOT NULL DEFAULT 0,
    locks             INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (organization_id, agent_run_id) REFERENCES public.agent_runs (organization_id, agent_run_id) ON DELETE RESTRICT
);
COMMENT ON COLUMN public.agent_run_reports.outcome IS '1=APPLIED, 2=UNCHANGED, 3=STALE (estado anterior ao atual), 4=CONFLICT (contradicao: nada muda)';
CREATE INDEX ix_agent_run_reports_run ON public.agent_run_reports (agent_run_id, received_at);

CREATE TABLE public.agent_run_events (
    organization_id  UUID NOT NULL,
    agent_run_id     UUID NOT NULL,
    sequence         BIGINT NOT NULL CHECK (sequence BETWEEN 1 AND 1000000),
    content_sha256   TEXT NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    envelope         JSONB NOT NULL CHECK (jsonb_typeof(envelope) = 'object' AND octet_length(envelope::text) <= 4096),
    source           SMALLINT NOT NULL CHECK (source IN (1, 2, 3)),
    event_code       TEXT NOT NULL CHECK (event_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    severity         SMALLINT NOT NULL CHECK (severity IN (1, 2, 3)),
    category         SMALLINT NOT NULL CHECK (category IN (1, 2, 3, 4, 5)),
    step_id          TEXT,
    studio_step_id   UUID,
    error_code       TEXT,
    timestamp_utc    TIMESTAMPTZ,
    observed_utc     TIMESTAMPTZ NOT NULL,
    received_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (agent_run_id, sequence),
    FOREIGN KEY (organization_id, agent_run_id) REFERENCES public.agent_runs (organization_id, agent_run_id) ON DELETE RESTRICT,
    CHECK ((envelope ->> 'sequence') = sequence::text AND (envelope ->> 'event_code') = event_code)
);

CREATE TABLE public.agent_run_missing (
    organization_id  UUID NOT NULL,
    agent_run_id     UUID NOT NULL,
    seq_from         BIGINT NOT NULL CHECK (seq_from >= 1),
    seq_to           BIGINT NOT NULL CHECK (seq_to >= seq_from AND seq_to <= 1000000),
    declared_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (agent_run_id, seq_from),
    FOREIGN KEY (organization_id, agent_run_id) REFERENCES public.agent_runs (organization_id, agent_run_id) ON DELETE RESTRICT
);

CREATE TABLE public.agent_ingestion_refusals (
    refusal_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL,
    agent_id         UUID NOT NULL,
    agent_run_id     UUID,
    kind             SMALLINT NOT NULL CHECK (kind IN (1, 2, 3, 4, 5)),
    sequence         BIGINT,
    reason           TEXT NOT NULL CHECK (reason ~ '^[a-z][a-z0-9_]{0,39}$'),
    existing_sha256  TEXT CHECK (existing_sha256 ~ '^[0-9a-f]{64}$'),
    offered_sha256   TEXT CHECK (offered_sha256 ~ '^[0-9a-f]{64}$'),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (organization_id, agent_id) REFERENCES public.agents (organization_id, agent_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, agent_run_id) REFERENCES public.agent_runs (organization_id, agent_run_id) ON DELETE RESTRICT
);
CREATE INDEX ix_agent_ingestion_refusals_run ON public.agent_ingestion_refusals (agent_run_id);

CREATE TABLE public.agent_admin_events (
    event_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    act                 SMALLINT NOT NULL CHECK (act BETWEEN 1 AND 11),
    actor_membership_id UUID,
    actor_agent_id      UUID,
    agent_id            UUID,
    worker_id           UUID,
    workspace_id        UUID,
    enrollment_id       UUID,
    authorization_id    UUID,
    credential_id       UUID,
    reason_code         TEXT CHECK (reason_code ~ '^[A-Z][A-Z_]{1,39}$'),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (actor_membership_id IS NOT NULL OR actor_agent_id IS NOT NULL)
);
CREATE INDEX ix_agent_admin_events_org ON public.agent_admin_events (organization_id, created_at);

-- === 4. Gatilhos (SECURITY INVOKER: rodam com o papel de quem grava; search_path fixo, referencias qualificadas) ===

-- Codigo: criacao por pessoa com autoridade (relida aqui), instante do servidor, nada consumido nem cancelado.
CREATE FUNCTION public.agent_enrollment_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.created_at := clock_timestamp();
    IF NEW.expires_at <= NEW.created_at OR NEW.expires_at > NEW.created_at + interval '60 minutes' THEN
        RAISE EXCEPTION 'validade do codigo fora de (0, 60 min]' USING ERRCODE = '23514';
    END IF;
    IF NEW.consumed_at IS NOT NULL OR NEW.cancelled_at IS NOT NULL OR (NEW.purpose = 1 AND NEW.agent_id IS NOT NULL) THEN
        RAISE EXCEPTION 'codigo nasce aberto' USING ERRCODE = '23514';
    END IF;
    IF NOT public.access_org_permission(NEW.organization_id, NEW.created_by_membership_id, 'agent_credential.manage')
       OR (NEW.purpose = 1 AND NOT (public.access_org_permission(NEW.organization_id, NEW.created_by_membership_id, 'agent.manage')
                                    AND public.access_org_permission(NEW.organization_id, NEW.created_by_membership_id, 'worker_agent.assign'))) THEN
        RAISE EXCEPTION 'sem autoridade para criar o codigo' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.workers w WHERE w.organization_id = NEW.organization_id AND w.worker_id = NEW.worker_id AND w.status = 1) THEN
        RAISE EXCEPTION 'worker inexistente ou nao ativo' USING ERRCODE = '23514';
    END IF;
    IF NEW.purpose = 2 AND NOT EXISTS (SELECT 1 FROM public.worker_agent_assignments a
                                        WHERE a.organization_id = NEW.organization_id AND a.agent_id = NEW.agent_id
                                          AND a.worker_id = NEW.worker_id AND a.status = 1) THEN
        RAISE EXCEPTION 'rotacao: agent sem associacao ativa com o worker' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- Codigo: so consumo (uma vez, dentro da validade, autoridade de quem criou RELIDA no resgate) ou cancelamento (uma vez,
-- por pessoa com agent_credential.manage). Origem imutavel; consumido ou cancelado nao muda mais.
CREATE FUNCTION public.agent_enrollment_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.consumed_at IS NOT NULL OR OLD.cancelled_at IS NOT NULL THEN
        RAISE EXCEPTION 'codigo consumido ou cancelado e imutavel (enrollment_id=%)', OLD.enrollment_id USING ERRCODE = '23514';
    END IF;
    IF NEW.enrollment_id <> OLD.enrollment_id OR NEW.organization_id <> OLD.organization_id OR NEW.purpose <> OLD.purpose
       OR NEW.worker_id <> OLD.worker_id OR NEW.agent_name IS DISTINCT FROM OLD.agent_name OR NEW.code_hash <> OLD.code_hash
       OR NEW.created_by_membership_id <> OLD.created_by_membership_id OR NEW.created_at <> OLD.created_at
       OR NEW.expires_at <> OLD.expires_at OR (OLD.purpose = 2 AND NEW.agent_id IS DISTINCT FROM OLD.agent_id) THEN
        RAISE EXCEPTION 'origem do codigo e imutavel (enrollment_id=%)', OLD.enrollment_id USING ERRCODE = '23514';
    END IF;
    IF NEW.consumed_at IS NOT NULL THEN
        NEW.consumed_at := clock_timestamp();
        IF NEW.consumed_at > NEW.expires_at THEN
            RAISE EXCEPTION 'codigo vencido' USING ERRCODE = '23514';
        END IF;
        IF NEW.agent_id IS NULL THEN
            RAISE EXCEPTION 'consumo sem agent' USING ERRCODE = '23514';
        END IF;
        IF NOT public.access_org_permission(NEW.organization_id, NEW.created_by_membership_id, 'agent_credential.manage')
           OR (NEW.purpose = 1 AND NOT (public.access_org_permission(NEW.organization_id, NEW.created_by_membership_id, 'agent.manage')
                                        AND public.access_org_permission(NEW.organization_id, NEW.created_by_membership_id, 'worker_agent.assign'))) THEN
            RAISE EXCEPTION 'quem criou o codigo perdeu a autoridade' USING ERRCODE = '42501';
        END IF;
    ELSIF NEW.cancelled_at IS NOT NULL THEN
        IF NEW.agent_id IS DISTINCT FROM OLD.agent_id THEN
            RAISE EXCEPTION 'cancelamento nao muda o agent' USING ERRCODE = '23514';
        END IF;
        NEW.cancelled_at := clock_timestamp();
        IF NOT public.access_org_permission(NEW.organization_id, NEW.cancelled_by_membership_id, 'agent_credential.manage') THEN
            RAISE EXCEPTION 'sem autoridade para cancelar o codigo' USING ERRCODE = '42501';
        END IF;
    ELSE
        RAISE EXCEPTION 'unicas mudancas: consumo ou cancelamento' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- Autorizacao local: quem concede tem automation.execute no workspace; versao PUBLICADA com o artefato igual ao pacote;
-- Worker ativo e autorizado no workspace; automacao e workspace ativos.
CREATE FUNCTION public.local_run_authorization_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.granted_at := clock_timestamp();
    IF NEW.valid_until <= NEW.granted_at OR NEW.valid_until > NEW.granted_at + interval '366 days' THEN
        RAISE EXCEPTION 'validade da autorizacao fora de (0, 366 dias]' USING ERRCODE = '23514';
    END IF;
    IF NEW.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'autorizacao nasce ativa' USING ERRCODE = '23514';
    END IF;
    IF NOT public.agent_workspace_permission(NEW.organization_id, NEW.granted_by_membership_id, NEW.workspace_id, 'automation.execute') THEN
        RAISE EXCEPTION 'sem automation.execute no workspace' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.automation_versions v
                    WHERE v.organization_id = NEW.organization_id AND v.automation_version_id = NEW.automation_version_id
                      AND v.status = 2
                      AND (v.command_json -> 'agent_package' ->> 'fingerprint_rule') = NEW.package_fingerprint_rule
                      AND (v.command_json -> 'agent_package' ->> 'fingerprint') = NEW.package_fingerprint) THEN
        RAISE EXCEPTION 'versao nao publicada ou pacote diferente da impressao declarada na versao' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.automations a JOIN public.workspaces w ON w.organization_id = a.organization_id AND w.workspace_id = a.workspace_id
                    WHERE a.organization_id = NEW.organization_id AND a.automation_id = NEW.automation_id AND a.status = 1 AND w.status = 1) THEN
        RAISE EXCEPTION 'automacao ou workspace nao ativo' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.worker_workspace_assignments x JOIN public.workers wk ON wk.organization_id = x.organization_id AND wk.worker_id = x.worker_id
                    WHERE x.organization_id = NEW.organization_id AND x.assignment_id = NEW.worker_workspace_assignment_id
                      AND x.status = 1 AND wk.status = 1) THEN
        RAISE EXCEPTION 'worker nao autorizado no workspace ou nao ativo' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- Autorizacao local: so a revogacao (uma vez, por pessoa com automation.execute no workspace).
CREATE FUNCTION public.local_run_authorization_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'autorizacao revogada e imutavel (authorization_id=%)', OLD.authorization_id USING ERRCODE = '23514';
    END IF;
    IF (to_jsonb(NEW) - 'revoked_at' - 'revoked_by_membership_id') <> (to_jsonb(OLD) - 'revoked_at' - 'revoked_by_membership_id')
       OR NEW.revoked_at IS NULL THEN
        RAISE EXCEPTION 'unica mudanca da autorizacao: revogacao' USING ERRCODE = '23514';
    END IF;
    NEW.revoked_at := clock_timestamp();
    IF NOT public.agent_workspace_permission(NEW.organization_id, NEW.revoked_by_membership_id, NEW.workspace_id, 'automation.execute') THEN
        RAISE EXCEPTION 'sem automation.execute no workspace' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

-- Registro: a autorizacao e da MESMA organizacao, workspace, automacao, versao, Worker e pacote; o Agent esta associado ao
-- Worker pela associacao informada; o instante e do servidor; nada de entrega de eventos no nascimento.
CREATE FUNCTION public.agent_run_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.registered_at := clock_timestamp();
    NEW.last_report_at := NEW.registered_at;
    IF NOT EXISTS (SELECT 1 FROM public.local_run_authorizations z
                    WHERE z.organization_id = NEW.organization_id AND z.authorization_id = NEW.authorization_id
                      AND z.workspace_id = NEW.workspace_id AND z.automation_id = NEW.automation_id
                      AND z.automation_version_id = NEW.automation_version_id AND z.worker_id = NEW.worker_id
                      AND z.package_fingerprint = NEW.package_fingerprint) THEN
        RAISE EXCEPTION 'registro diverge da autorizacao' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.worker_agent_assignments a
                    WHERE a.organization_id = NEW.organization_id AND a.assignment_id = NEW.worker_agent_assignment_id
                      AND a.worker_id = NEW.worker_id AND a.agent_id = NEW.agent_id) THEN
        RAISE EXCEPTION 'agent nao associado ao worker da autorizacao' USING ERRCODE = '23514';
    END IF;
    IF NEW.events_stored <> 0 OR NEW.stored_through <> 0 OR NEW.settled_through <> 0 OR NEW.max_sequence <> 0
       OR NEW.declared_missing <> 0 OR NEW.collection_completed_sequence IS NOT NULL OR NEW.framework_execution_id IS NOT NULL
       OR NEW.supervisor_reports <> 1 OR NEW.supervisor_conflicts <> 0 OR NEW.framework_events <> 0 OR NEW.invalid_refused <> 0
       OR NEW.lost_at_source OR NEW.lost_local OR NEW.purged_local THEN
        RAISE EXCEPTION 'registro nasce sem entrega de eventos' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- Registro: identidade imutavel; desfecho do supervisor so por transicao permitida e congelado no terminal; contadores so
-- crescem; correlacao com o Framework so e definida uma vez.
CREATE FUNCTION public.agent_run_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.agent_run_id <> OLD.agent_run_id OR NEW.organization_id <> OLD.organization_id OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.automation_id <> OLD.automation_id OR NEW.automation_version_id <> OLD.automation_version_id
       OR NEW.authorization_id <> OLD.authorization_id OR NEW.agent_id <> OLD.agent_id OR NEW.worker_id <> OLD.worker_id
       OR NEW.worker_agent_assignment_id <> OLD.worker_agent_assignment_id OR NEW.agent_execution_id <> OLD.agent_execution_id
       OR NEW.origin <> OLD.origin OR NEW.platform_execution_id IS DISTINCT FROM OLD.platform_execution_id
       OR NEW.package_fingerprint <> OLD.package_fingerprint OR NEW.registered_at <> OLD.registered_at
       OR NEW.authorization_state_at_registration <> OLD.authorization_state_at_registration THEN
        RAISE EXCEPTION 'identidade do registro e imutavel (agent_run_id=%)', OLD.agent_run_id USING ERRCODE = '23514';
    END IF;
    IF OLD.supervisor_state >= 4 THEN
        IF NEW.supervisor_state <> OLD.supervisor_state OR NEW.supervisor_code IS DISTINCT FROM OLD.supervisor_code
           OR NEW.exit_code IS DISTINCT FROM OLD.exit_code OR NEW.supervisor_counts <> OLD.supervisor_counts
           OR NEW.agent_created_at IS DISTINCT FROM OLD.agent_created_at OR NEW.agent_started_at IS DISTINCT FROM OLD.agent_started_at
           OR NEW.agent_ended_at IS DISTINCT FROM OLD.agent_ended_at OR NEW.adopted <> OLD.adopted
           OR NEW.suspensions <> OLD.suspensions OR NEW.locks <> OLD.locks THEN
            RAISE EXCEPTION 'desfecho terminal do supervisor e imutavel (agent_run_id=%)', OLD.agent_run_id USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.supervisor_state <> OLD.supervisor_state AND NOT public.agent_run_transition_allowed(OLD.supervisor_state, NEW.supervisor_state) THEN
        RAISE EXCEPTION 'transicao do supervisor nao permitida (% -> %)', OLD.supervisor_state, NEW.supervisor_state USING ERRCODE = '23514';
    END IF;
    IF NEW.events_stored < OLD.events_stored OR NEW.stored_through < OLD.stored_through OR NEW.settled_through < OLD.settled_through
       OR NEW.max_sequence < OLD.max_sequence OR NEW.declared_missing < OLD.declared_missing
       OR NEW.supervisor_reports < OLD.supervisor_reports OR NEW.supervisor_conflicts < OLD.supervisor_conflicts
       OR NEW.framework_events < OLD.framework_events OR NEW.invalid_refused < OLD.invalid_refused
       OR (OLD.lost_at_source AND NOT NEW.lost_at_source) OR (OLD.lost_local AND NOT NEW.lost_local)
       OR (OLD.purged_local AND NOT NEW.purged_local)
       OR (OLD.collection_completed_sequence IS NOT NULL AND NEW.collection_completed_sequence IS DISTINCT FROM OLD.collection_completed_sequence)
       OR (OLD.framework_execution_id IS NOT NULL AND NEW.framework_execution_id IS DISTINCT FROM OLD.framework_execution_id)
       OR NEW.last_report_at < OLD.last_report_at THEN
        RAISE EXCEPTION 'entrega e contadores do registro so avancam (agent_run_id=%)', OLD.agent_run_id USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_agent_enrollments_on_insert BEFORE INSERT ON public.agent_enrollments
    FOR EACH ROW EXECUTE FUNCTION public.agent_enrollment_on_insert();
CREATE TRIGGER trg_agent_enrollments_on_update BEFORE UPDATE ON public.agent_enrollments
    FOR EACH ROW EXECUTE FUNCTION public.agent_enrollment_on_update();
CREATE TRIGGER trg_local_run_authorizations_on_insert BEFORE INSERT ON public.local_run_authorizations
    FOR EACH ROW EXECUTE FUNCTION public.local_run_authorization_on_insert();
CREATE TRIGGER trg_local_run_authorizations_on_update BEFORE UPDATE ON public.local_run_authorizations
    FOR EACH ROW EXECUTE FUNCTION public.local_run_authorization_on_update();
CREATE TRIGGER trg_agent_runs_on_insert BEFORE INSERT ON public.agent_runs
    FOR EACH ROW EXECUTE FUNCTION public.agent_run_on_insert();
CREATE TRIGGER trg_agent_runs_on_update BEFORE UPDATE ON public.agent_runs
    FOR EACH ROW EXECUTE FUNCTION public.agent_run_on_update();

-- Historicos: nada apagado; os de auditoria nunca atualizados.
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['agent_enrollments', 'agent_presence', 'local_run_authorizations', 'agent_runs', 'agent_run_reports',
                             'agent_run_events', 'agent_run_missing', 'agent_ingestion_refusals', 'agent_admin_events'] LOOP
        EXECUTE format('CREATE TRIGGER trg_%s_prevent_delete BEFORE DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.prevent_delete()', t, t);
        EXECUTE format('CREATE TRIGGER trg_%s_prevent_truncate BEFORE TRUNCATE ON public.%I FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate()', t, t);
    END LOOP;
    FOREACH t IN ARRAY ARRAY['agent_run_reports', 'agent_run_events', 'agent_run_missing', 'agent_ingestion_refusals', 'agent_admin_events'] LOOP
        EXECUTE format('CREATE TRIGGER trg_%s_prevent_update BEFORE UPDATE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.prevent_update()', t, t);
    END LOOP;
END;
$$;

-- === 5. RLS por organizacao (padrao da 012: ENABLE + FORCE e a politica de contexto da requisicao) ===
DO $$
DECLARE
    t TEXT;
    ctx_req CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.organization_id'', true), '''')::uuid';
BEGIN
    FOREACH t IN ARRAY ARRAY['agent_enrollments', 'agent_presence', 'local_run_authorizations', 'agent_runs', 'agent_run_reports',
                             'agent_run_events', 'agent_run_missing', 'agent_ingestion_refusals', 'agent_admin_events'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_request_context ON public.%I AS PERMISSIVE FOR ALL TO nekkora_platform, nekkora_ingestion USING (%s) WITH CHECK (%s)',
                       t, ctx_req, ctx_req);
    END LOOP;
END;
$$;

-- === 6. Privilegios ===
REVOKE ALL ON FUNCTION public.agent_workspace_permission(UUID, UUID, UUID, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.agent_run_transition_allowed(SMALLINT, SMALLINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.agent_enrollment_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.agent_enrollment_on_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.local_run_authorization_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.local_run_authorization_on_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.agent_run_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.agent_run_on_update() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.agent_workspace_permission(UUID, UUID, UUID, TEXT) TO nekkora_platform;
GRANT EXECUTE ON FUNCTION public.agent_run_transition_allowed(SMALLINT, SMALLINT) TO nekkora_ingestion;

-- nekkora_platform (pessoas): codigos, autorizacoes, auditoria; leitura do canal. Nunca grava execucao, relatorio ou evento.
GRANT SELECT, INSERT ON public.agent_enrollments TO nekkora_platform;
GRANT UPDATE (cancelled_at, cancelled_by_membership_id) ON public.agent_enrollments TO nekkora_platform;
GRANT SELECT, INSERT ON public.local_run_authorizations TO nekkora_platform;
GRANT UPDATE (revoked_at, revoked_by_membership_id) ON public.local_run_authorizations TO nekkora_platform;
GRANT SELECT ON public.agent_presence, public.agent_runs, public.agent_run_reports, public.agent_run_events, public.agent_run_missing,
    public.agent_ingestion_refusals TO nekkora_platform;
GRANT SELECT, INSERT ON public.agent_admin_events TO nekkora_platform;

-- nekkora_ingestion (maquina): resgate, presenca, registro, relatorios, eventos. Leitura minima dos vinculos para reconferir
-- autoridade (as funcoes da 023, so colunas de estado) e as associacoes da maquina.
GRANT SELECT ON public.agent_enrollments TO nekkora_ingestion;
GRANT UPDATE (consumed_at, consumed_credential_id, agent_id) ON public.agent_enrollments TO nekkora_ingestion;
GRANT SELECT, INSERT ON public.agents TO nekkora_ingestion;
GRANT UPDATE (last_heartbeat_at, agent_version) ON public.agents TO nekkora_ingestion;
GRANT SELECT, INSERT ON public.agent_credentials TO nekkora_ingestion;
GRANT UPDATE (revoked_at, revoked_by_membership_id, revocation_reason) ON public.agent_credentials TO nekkora_ingestion;
GRANT INSERT ON public.worker_agent_assignments TO nekkora_ingestion;
GRANT SELECT ON public.workers, public.worker_workspace_assignments, public.local_run_authorizations TO nekkora_ingestion;
GRANT SELECT (organization_id, status) ON public.organizations TO nekkora_ingestion;
GRANT SELECT, INSERT, UPDATE ON public.agent_presence TO nekkora_ingestion;
GRANT SELECT, INSERT, UPDATE ON public.agent_runs TO nekkora_ingestion;
GRANT SELECT, INSERT ON public.agent_run_reports, public.agent_run_events, public.agent_run_missing, public.agent_ingestion_refusals,
    public.agent_admin_events TO nekkora_ingestion;
GRANT EXECUTE ON FUNCTION public.access_membership_effective(UUID, UUID), public.access_org_permission(UUID, UUID, TEXT) TO nekkora_ingestion;
GRANT SELECT (organization_id, membership_id, user_id, status) ON public.organization_memberships TO nekkora_ingestion;
GRANT SELECT (organization_id, user_id, status) ON public.users TO nekkora_ingestion;
GRANT SELECT (organization_id, membership_id, role_id, status) ON public.organization_role_assignments TO nekkora_ingestion;
GRANT SELECT ON public.role_permissions, public.permissions TO nekkora_ingestion;

-- === 7. Invariantes ===
DO $$
DECLARE
    v_n INTEGER;
    v_bad TEXT;
    v_novas CONSTANT TEXT[] := ARRAY['agent_enrollments', 'agent_presence', 'local_run_authorizations', 'agent_runs', 'agent_run_reports',
                                     'agent_run_events', 'agent_run_missing', 'agent_ingestion_refusals', 'agent_admin_events'];
    v_auditoria CONSTANT TEXT[] := ARRAY['agent_run_reports', 'agent_run_events', 'agent_run_missing', 'agent_ingestion_refusals',
                                         'agent_admin_events'];
    v_servico CONSTANT TEXT[] := ARRAY['nekkora_portal', 'nekkora_portal_owner', 'nekkora_scheduler', 'nekkora_provisioning',
                                       'nekkora_retention_executor', 'nekkora_scheduler_owner', 'nekkora_provisioning_owner',
                                       'nekkora_retention_owner', 'nekkora_retention_tables_owner', 'nekkora_identity_owner',
                                       'nekkora_ops_mfa_owner'];
BEGIN
    -- 1. tabelas de tenant: 44 (023) + 9 desta = 53; cada nova com RLS ENABLE+FORCE e so a politica de contexto da requisicao
    SELECT count(*) INTO v_n FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind = 'r'
      AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'organization_id' AND NOT a.attisdropped);
    IF v_n <> 53 THEN RAISE EXCEPTION '025: % tabelas com organization_id (esperadas 53: 44 + 9 do canal)', v_n; END IF;
    SELECT string_agg(t, ', ') INTO v_bad FROM unnest(v_novas) t
    WHERE NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = ('public.' || t)::regclass AND c.relrowsecurity AND c.relforcerowsecurity)
       OR (SELECT string_agg(p.polname || ':' || r.rolname, ',' ORDER BY r.rolname) FROM pg_policy p, unnest(p.polroles) x
             JOIN pg_roles r ON r.oid = x WHERE p.polrelid = ('public.' || t)::regclass)
          IS DISTINCT FROM 'tenant_request_context:nekkora_ingestion,tenant_request_context:nekkora_platform';
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '025: RLS ou politicas divergentes: %', v_bad; END IF;

    -- 2. ninguem apaga nada; auditoria sem UPDATE; papeis de outros servicos sem acesso as tabelas novas
    SELECT string_agg(r.rolname || ':' || t, ', ') INTO v_bad FROM pg_roles r, unnest(v_novas) t
    WHERE NOT r.rolsuper AND r.rolname IN ('nekkora_platform', 'nekkora_ingestion')
      AND has_table_privilege(r.oid, 'public.' || t, 'DELETE, TRUNCATE, REFERENCES, TRIGGER');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '025: exclusao possivel nas tabelas do canal: %', v_bad; END IF;
    SELECT string_agg(r.rolname || ':' || t, ', ') INTO v_bad FROM pg_roles r, unnest(v_auditoria) t
    WHERE r.rolname IN ('nekkora_platform', 'nekkora_ingestion') AND has_table_privilege(r.oid, 'public.' || t, 'UPDATE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '025: atualizacao possivel em historico do canal: %', v_bad; END IF;
    SELECT string_agg(p || ':' || t, ', ') INTO v_bad FROM unnest(v_servico) p, unnest(v_novas) t
    WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname = p)
      AND (has_table_privilege(p, 'public.' || t, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
           OR has_any_column_privilege(p, 'public.' || t, 'SELECT, INSERT, UPDATE, REFERENCES'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '025: papel de outro servico com acesso ao canal: %', v_bad; END IF;

    -- 3. separacao das escritas: a Platform nao grava execucao, relatorio, evento, ausencia, recusa nem presenca; a
    --    ingestao nao cria codigo nem autorizacao, e so consome codigo
    SELECT string_agg(t, ', ') INTO v_bad FROM unnest(ARRAY['agent_runs', 'agent_run_reports', 'agent_run_events', 'agent_run_missing',
                                                           'agent_ingestion_refusals', 'agent_presence']) t
    WHERE has_table_privilege('nekkora_platform', 'public.' || t, 'INSERT, UPDATE')
       OR has_any_column_privilege('nekkora_platform', 'public.' || t, 'INSERT, UPDATE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '025: nekkora_platform grava fatos da maquina: %', v_bad; END IF;
    IF has_table_privilege('nekkora_ingestion', 'public.agent_enrollments', 'INSERT')
       OR has_table_privilege('nekkora_ingestion', 'public.local_run_authorizations', 'INSERT, UPDATE')
       OR has_any_column_privilege('nekkora_ingestion', 'public.local_run_authorizations', 'INSERT, UPDATE')
       OR has_column_privilege('nekkora_ingestion', 'public.agent_enrollments', 'cancelled_at', 'UPDATE')
       OR has_column_privilege('nekkora_ingestion', 'public.agent_enrollments', 'expires_at', 'UPDATE')
       OR has_table_privilege('nekkora_ingestion', 'public.agents', 'UPDATE')
       OR has_column_privilege('nekkora_ingestion', 'public.agents', 'status', 'UPDATE')
       OR has_table_privilege('nekkora_ingestion', 'public.worker_agent_assignments', 'UPDATE')
       OR has_table_privilege('nekkora_ingestion', 'public.workers', 'INSERT, UPDATE')
       OR has_table_privilege('nekkora_ingestion', 'public.executions', 'INSERT, UPDATE')
       OR has_table_privilege('nekkora_ingestion', 'public.organization_memberships', 'SELECT')
       OR has_column_privilege('nekkora_ingestion', 'public.users', 'email', 'SELECT')
       OR has_column_privilege('nekkora_ingestion', 'public.agent_credentials', 'expires_at', 'UPDATE') THEN
        RAISE EXCEPTION '025: nekkora_ingestion com privilegio alem do canal';
    END IF;

    -- 4. funcoes novas: SECURITY INVOKER, search_path fixo, do migrador; EXECUTE exato
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.proname IN ('agent_workspace_permission', 'agent_run_transition_allowed', 'agent_enrollment_on_insert',
                                                 'agent_enrollment_on_update', 'local_run_authorization_on_insert',
                                                 'local_run_authorization_on_update', 'agent_run_on_insert', 'agent_run_on_update')
      AND (p.prosecdef OR NOT ('search_path=pg_catalog, pg_temp' = ANY (coalesce(p.proconfig, '{}')))
           OR pg_get_userbyid(p.proowner) <> 'migrador_nekkora' OR has_function_privilege('public', p.oid, 'EXECUTE'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '025: funcao do canal fora do padrao: %', v_bad; END IF;
    SELECT string_agg(r.rolname, ',' ORDER BY r.rolname) INTO v_bad FROM pg_roles r
    WHERE NOT r.rolsuper AND r.rolname <> 'migrador_nekkora'
      AND has_function_privilege(r.oid, 'public.agent_workspace_permission(uuid,uuid,uuid,text)', 'EXECUTE');
    IF v_bad IS DISTINCT FROM 'nekkora_platform' THEN RAISE EXCEPTION '025: EXECUTE de agent_workspace_permission divergente: %', v_bad; END IF;
    SELECT string_agg(r.rolname, ',' ORDER BY r.rolname) INTO v_bad FROM pg_roles r
    WHERE NOT r.rolsuper AND r.rolname NOT IN ('migrador_nekkora', 'nekkora_identity_owner', 'nekkora_platform')
      AND has_function_privilege(r.oid, 'public.access_org_permission(uuid,uuid,text)', 'EXECUTE');
    IF v_bad IS DISTINCT FROM 'nekkora_ingestion' THEN RAISE EXCEPTION '025: EXECUTE de access_org_permission divergente: %', v_bad; END IF;

    -- 5. gatilhos ligados: 6 de regra + 9x2 de exclusao + 5 de atualizacao
    SELECT count(*) INTO v_n FROM pg_trigger tg JOIN pg_class c ON c.oid = tg.tgrelid
    WHERE c.relname = ANY (v_novas) AND NOT tg.tgisinternal AND tg.tgenabled = 'O';
    IF v_n <> 29 THEN RAISE EXCEPTION '025: % gatilhos do canal ligados (esperados 29)', v_n; END IF;

    -- 6. nenhuma linha nasce com a migration
    IF EXISTS (SELECT 1 FROM public.agent_enrollments) OR EXISTS (SELECT 1 FROM public.agent_runs)
       OR EXISTS (SELECT 1 FROM public.local_run_authorizations) THEN
        RAISE EXCEPTION '025: tabelas do canal nao nascem vazias';
    END IF;
END;
$$;

COMMIT;
