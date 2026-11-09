-- Nekkora Platform - Python externo e pedido de execucao (AGENT-PYTHON-001). Contrato: Nekkora-Agent
-- docs/agent-python-001/README.md (sec. 2 a 4) e docs/relatorios/2026-11-03-agent-python-contrato.md.
--
-- CANDIDATA ate a aprovacao do relatorio da missao. Numero 030 conferido em 03/11 contra todas as branches remotas (nenhuma
-- tinha 03x); se outra migration entrar antes, esta e renumerada sem mudar o conteudo.
--
-- O que muda (ADITIVO para os dados existentes: toda linha anterior fica com source_kind = 1 / origin = 1, e as regras dela
-- continuam as mesmas):
--   1. ORIGEM EXPLICITA da autorizacao local e do registro (source_kind):
--        1 = STUDIO_PACKAGE     pacote do formato Studio, impressao nekkora-agent/package-fingerprint/1 (025, inalterada);
--        2 = EXTERNAL_PACKAGE   pacote Python externo, impressao nekkora-agent/external-python-fingerprint/1 (regra propria);
--        3 = EXTERNAL_MONITORED Python do cliente disparado pelo agendador dele: SEM versao e SEM impressao. A Platform nao
--                               conhece codigo, caminho nem argumento; so a automacao cadastrada, o Worker e a validade.
--      Nenhuma impressao e inventada para o monitoramento.
--   2. ARTEFATO externo (automation_artifacts.package_kind = 2): descritor nekkora-external.json, regra externa, versao de
--      Python e dependencias declaradas; framework/modo do Studio ficam nulos.
--   3. PEDIDO DE EXECUCAO (run_requests): solicitacao duravel de uma pessoa para UMA organizacao, workspace, autorizacao
--      (Worker + versao + impressao) e Agent, com validade. O Agent puxa, decide e, se aceitar, registra a execucao com o
--      run_request_id (agent_runs.origin = 2). Entregar ou aceitar nao prova inicio; inicio e desfecho vem do supervisor.
--   4. agent_presence: capacidades EXTERNAL_PYTHON_1_0 e RUN_REQUEST_1_0. agent_admin_events: atos 12 e 13 (pedido criado,
--      pedido revogado).
--
-- Papeis: nekkora_platform (pessoas) e nekkora_ingestion (maquina). Nenhum papel novo, nenhuma funcao SECURITY DEFINER,
-- nenhum dono novo: SEM elevacao (como 025 e 028).

-- === 0. Pre-condicao ===
DO $$
BEGIN
    IF to_regclass('public.local_run_authorizations') IS NULL OR to_regclass('public.automation_artifacts') IS NULL THEN
        RAISE EXCEPTION '030: exige 025 e 028';
    END IF;
    IF to_regclass('public.run_requests') IS NOT NULL THEN
        RAISE EXCEPTION '030: ja aplicada';
    END IF;
END;
$$;

-- === 1. Origem explicita na autorizacao local ===
ALTER TABLE public.local_run_authorizations
    ADD COLUMN source_kind SMALLINT NOT NULL DEFAULT 1 CHECK (source_kind IN (1, 2, 3));
ALTER TABLE public.local_run_authorizations ALTER COLUMN automation_version_id DROP NOT NULL;
ALTER TABLE public.local_run_authorizations ALTER COLUMN package_fingerprint DROP NOT NULL;
ALTER TABLE public.local_run_authorizations ALTER COLUMN package_fingerprint_rule DROP NOT NULL;
ALTER TABLE public.local_run_authorizations DROP CONSTRAINT local_run_authorizations_package_fingerprint_rule_check;
ALTER TABLE public.local_run_authorizations ADD CONSTRAINT local_run_authorizations_source_kind_package CHECK (
       (source_kind = 1 AND automation_version_id IS NOT NULL AND package_fingerprint IS NOT NULL
            AND package_fingerprint_rule = 'nekkora-agent/package-fingerprint/1')
    OR (source_kind = 2 AND automation_version_id IS NOT NULL AND package_fingerprint IS NOT NULL
            AND package_fingerprint_rule = 'nekkora-agent/external-python-fingerprint/1')
    OR (source_kind = 3 AND automation_version_id IS NULL AND package_fingerprint IS NULL AND package_fingerprint_rule IS NULL));
-- sem versao (monitoramento) a FK composta com a versao nao confere a automacao: esta confere
ALTER TABLE public.local_run_authorizations ADD CONSTRAINT local_run_authorizations_automation_fk
    FOREIGN KEY (organization_id, workspace_id, automation_id) REFERENCES public.automations (organization_id, workspace_id, automation_id)
    ON DELETE RESTRICT;
COMMENT ON COLUMN public.local_run_authorizations.source_kind IS
    '1=STUDIO_PACKAGE (package-fingerprint/1), 2=EXTERNAL_PACKAGE (external-python-fingerprint/1), 3=EXTERNAL_MONITORED (sem versao nem impressao)';

-- === 2. Origem explicita e pedido no registro ===
ALTER TABLE public.agent_runs
    ADD COLUMN source_kind SMALLINT NOT NULL DEFAULT 1 CHECK (source_kind IN (1, 2, 3)),
    ADD COLUMN run_request_id UUID;
ALTER TABLE public.agent_runs ALTER COLUMN automation_version_id DROP NOT NULL;
ALTER TABLE public.agent_runs ALTER COLUMN package_fingerprint DROP NOT NULL;
ALTER TABLE public.agent_runs DROP CONSTRAINT agent_runs_origin_check;
ALTER TABLE public.agent_runs ADD CONSTRAINT agent_runs_origin_check CHECK (origin IN (1, 2));
ALTER TABLE public.agent_runs ADD CONSTRAINT agent_runs_source_kind_package CHECK (
    (source_kind = 3) = (automation_version_id IS NULL) AND (source_kind = 3) = (package_fingerprint IS NULL));
ALTER TABLE public.agent_runs ADD CONSTRAINT agent_runs_origin_request CHECK (
    (origin = 2) = (run_request_id IS NOT NULL) AND (origin <> 2 OR (platform_execution_id IS NULL AND source_kind IN (1, 2))));
ALTER TABLE public.agent_runs ADD CONSTRAINT agent_runs_automation_fk
    FOREIGN KEY (organization_id, workspace_id, automation_id) REFERENCES public.automations (organization_id, workspace_id, automation_id)
    ON DELETE RESTRICT;
CREATE UNIQUE INDEX ux_agent_runs_run_request ON public.agent_runs (organization_id, run_request_id) WHERE run_request_id IS NOT NULL;
COMMENT ON COLUMN public.agent_runs.origin IS '1=LOCAL_AUTHORIZED (iniciada na maquina), 2=PLATFORM_REQUEST (pedido de execucao aceito pela maquina)';
COMMENT ON COLUMN public.agent_runs.source_kind IS 'copiado da autorizacao: 1=STUDIO_PACKAGE, 2=EXTERNAL_PACKAGE, 3=EXTERNAL_MONITORED';

-- === 3. Artefato externo ===
ALTER TABLE public.automation_artifacts DROP CONSTRAINT automation_artifacts_package_kind_check;
ALTER TABLE public.automation_artifacts DROP CONSTRAINT automation_artifacts_fingerprint_rule_check;
ALTER TABLE public.automation_artifacts DROP CONSTRAINT automation_artifacts_manifest_version_check;
ALTER TABLE public.automation_artifacts ALTER COLUMN framework DROP NOT NULL;
ALTER TABLE public.automation_artifacts ALTER COLUMN execution_mode DROP NOT NULL;
ALTER TABLE public.automation_artifacts
    ADD COLUMN python_requirement TEXT CHECK (python_requirement ~ '^(>=|==|~=)[0-9]{1,2}\.[0-9]{1,2}(\.\*)?(,<[0-9]{1,2}\.[0-9]{1,2})?$'),
    ADD COLUMN dependencies JSONB CHECK (dependencies IS NULL OR (jsonb_typeof(dependencies) = 'array' AND jsonb_array_length(dependencies) <= 200
                                                                   AND octet_length(dependencies::text) <= 16384)),
    ADD COLUMN package_name TEXT CHECK (package_name ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$'),
    ADD COLUMN package_version TEXT CHECK (package_version ~ '^[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,6}$');
ALTER TABLE public.automation_artifacts ADD CONSTRAINT automation_artifacts_kind_rule CHECK (
       (package_kind = 1 AND fingerprint_rule = 'nekkora-agent/package-fingerprint/1' AND manifest_version ~ '^1\.[4-9]\.[0-9]{1,3}$'
            AND framework IS NOT NULL AND execution_mode IS NOT NULL
            AND python_requirement IS NULL AND dependencies IS NULL AND package_name IS NULL AND package_version IS NULL)
    OR (package_kind = 2 AND fingerprint_rule = 'nekkora-agent/external-python-fingerprint/1' AND manifest_version ~ '^1\.0\.[0-9]{1,3}$'
            AND framework IS NULL AND execution_mode IS NULL
            AND python_requirement IS NOT NULL AND dependencies IS NOT NULL AND package_name IS NOT NULL AND package_version IS NOT NULL));
COMMENT ON COLUMN public.automation_artifacts.package_kind IS '1=STUDIO_PYTHON (manifest.json), 2=EXTERNAL_PYTHON (nekkora-external.json)';

-- === 4. Pedido de execucao ===
CREATE TABLE public.run_requests (
    run_request_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id            UUID NOT NULL,
    workspace_id               UUID NOT NULL,
    automation_id              UUID NOT NULL,
    automation_version_id      UUID NOT NULL,
    authorization_id           UUID NOT NULL,
    worker_id                  UUID NOT NULL,
    agent_id                   UUID NOT NULL,
    source_kind                SMALLINT NOT NULL CHECK (source_kind IN (1, 2)),
    package_fingerprint        TEXT NOT NULL CHECK (package_fingerprint ~ '^[0-9a-f]{64}$'),
    package_fingerprint_rule   TEXT NOT NULL CHECK (package_fingerprint_rule IN ('nekkora-agent/package-fingerprint/1',
                                                                                 'nekkora-agent/external-python-fingerprint/1')),
    request_key                UUID NOT NULL,
    requested_by_membership_id UUID NOT NULL,
    requested_at               TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    valid_until                TIMESTAMPTZ NOT NULL,
    state                      SMALLINT NOT NULL DEFAULT 1 CHECK (state BETWEEN 1 AND 8),
    delivered_at               TIMESTAMPTZ,
    deliveries                 INTEGER NOT NULL DEFAULT 0 CHECK (deliveries >= 0),
    decided_at                 TIMESTAMPTZ,
    decision_reason            TEXT CHECK (decision_reason IN ('PACKAGE_NOT_PRESENT', 'PACKAGE_NOT_APPROVED', 'AUTHORIZATION_UNUSABLE',
                                                               'ENVIRONMENT_INCOMPATIBLE', 'BUSY', 'EXPIRED', 'LAUNCH_NOT_ATTEMPTED')),
    agent_execution_id         TEXT CHECK (agent_execution_id ~ '^ex-[0-9a-f]{32}$'),
    not_started_at             TIMESTAMPTZ,
    linked_at                  TIMESTAMPTZ,
    agent_run_id               UUID,
    expired_at                 TIMESTAMPTZ,
    revoked_at                 TIMESTAMPTZ,
    revoked_by_membership_id   UUID,
    UNIQUE (organization_id, run_request_id),
    UNIQUE (organization_id, request_key),
    FOREIGN KEY (organization_id, authorization_id) REFERENCES public.local_run_authorizations (organization_id, authorization_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, workspace_id, automation_id, automation_version_id)
        REFERENCES public.automation_versions (organization_id, workspace_id, automation_id, automation_version_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, agent_id) REFERENCES public.agents (organization_id, agent_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, agent_run_id) REFERENCES public.agent_runs (organization_id, agent_run_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, requested_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, revoked_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK (valid_until >= requested_at + interval '60 seconds' AND valid_until <= requested_at + interval '24 hours'),
    CHECK ((revoked_at IS NULL) = (revoked_by_membership_id IS NULL)),
    CHECK (state NOT IN (2, 3, 4, 5, 6) OR delivered_at IS NOT NULL),
    CHECK (state <> 1 OR (delivered_at IS NULL AND decided_at IS NULL)),
    CHECK (state NOT IN (3, 4, 5, 6) OR decided_at IS NOT NULL),
    CHECK (state NOT IN (3, 5, 6) OR agent_execution_id IS NOT NULL),
    CHECK (state <> 4 OR (decision_reason IS NOT NULL AND agent_execution_id IS NULL)),
    CHECK ((state = 5) = (not_started_at IS NOT NULL)),
    CHECK ((state = 6) = (linked_at IS NOT NULL AND agent_run_id IS NOT NULL)),
    CHECK ((state = 7) = (expired_at IS NOT NULL)),
    CHECK ((state = 8) = (revoked_at IS NOT NULL))
);
CREATE INDEX ix_run_requests_agent_pending ON public.run_requests (organization_id, agent_id, state) WHERE state IN (1, 2, 3);
CREATE INDEX ix_run_requests_workspace ON public.run_requests (organization_id, workspace_id, requested_at, run_request_id);
COMMENT ON TABLE public.run_requests IS 'pedido de execucao (030): uma pessoa pede; a maquina puxa, decide e registra a execucao. Entregar ou aceitar nao prova inicio';
COMMENT ON COLUMN public.run_requests.state IS
    '1=REQUESTED, 2=DELIVERED, 3=ACCEPTED, 4=REFUSED, 5=NOT_STARTED (aceito; processo comprovadamente nao criado), 6=RUN_LINKED, 7=EXPIRED, 8=REVOKED';

-- === 5. Presenca e auditoria ===
ALTER TABLE public.agent_presence DROP CONSTRAINT agent_presence_capabilities_check;
ALTER TABLE public.agent_presence ADD CONSTRAINT agent_presence_capabilities_check CHECK (cardinality(capabilities) <= 8
    AND capabilities <@ ARRAY['RUN_LOCAL', 'OBS_1_0', 'SUPERVISOR_REPORT_1_0', 'EXTERNAL_PYTHON_1_0', 'RUN_REQUEST_1_0']::text[]);
ALTER TABLE public.agent_admin_events DROP CONSTRAINT agent_admin_events_act_check;
ALTER TABLE public.agent_admin_events ADD CONSTRAINT agent_admin_events_act_check CHECK (act BETWEEN 1 AND 13);
ALTER TABLE public.agent_admin_events ADD COLUMN run_request_id UUID;
ALTER TABLE public.agent_admin_events ADD CONSTRAINT agent_admin_events_run_request CHECK (act NOT IN (12, 13) OR run_request_id IS NOT NULL);

-- === 6. Gatilhos (SECURITY INVOKER, search_path fixo) ===

-- Autorizacao local: as regras da 025 por origem. Monitoramento: sem versao, automacao e workspace ativos, Worker autorizado.
CREATE OR REPLACE FUNCTION public.local_run_authorization_on_insert() RETURNS TRIGGER
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
    IF NEW.source_kind IN (1, 2) THEN
        IF NOT EXISTS (SELECT 1 FROM public.automation_versions v
                        WHERE v.organization_id = NEW.organization_id AND v.automation_version_id = NEW.automation_version_id
                          AND v.automation_id = NEW.automation_id AND v.workspace_id = NEW.workspace_id
                          AND v.status = 2
                          AND (v.command_json -> 'agent_package' ->> 'fingerprint_rule') = NEW.package_fingerprint_rule
                          AND (v.command_json -> 'agent_package' ->> 'fingerprint') = NEW.package_fingerprint) THEN
            RAISE EXCEPTION 'versao nao publicada ou pacote diferente da impressao declarada na versao' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.automation_version_id IS NOT NULL OR NEW.package_fingerprint IS NOT NULL OR NEW.package_fingerprint_rule IS NOT NULL THEN
        RAISE EXCEPTION 'monitoramento externo nao tem versao nem impressao' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.automations a JOIN public.workspaces w ON w.organization_id = a.organization_id AND w.workspace_id = a.workspace_id
                    WHERE a.organization_id = NEW.organization_id AND a.automation_id = NEW.automation_id AND a.workspace_id = NEW.workspace_id
                      AND a.status = 1 AND w.status = 1) THEN
        RAISE EXCEPTION 'automacao ou workspace nao ativo' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.worker_workspace_assignments x JOIN public.workers wk ON wk.organization_id = x.organization_id AND wk.worker_id = x.worker_id
                    WHERE x.organization_id = NEW.organization_id AND x.assignment_id = NEW.worker_workspace_assignment_id
                      AND x.worker_id = NEW.worker_id AND x.workspace_id = NEW.workspace_id
                      AND x.status = 1 AND wk.status = 1) THEN
        RAISE EXCEPTION 'worker nao autorizado no workspace ou nao ativo' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- Registro: igual a autorizacao em origem, versao e pacote (IS NOT DISTINCT FROM: monitoramento sem versao nem impressao);
-- pedido (origin = 2): ACEITO por ESTE Agent, com a mesma autorizacao e o mesmo agent_execution_id.
CREATE OR REPLACE FUNCTION public.agent_run_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.registered_at := clock_timestamp();
    NEW.last_report_at := NEW.registered_at;
    IF NOT EXISTS (SELECT 1 FROM public.local_run_authorizations z
                    WHERE z.organization_id = NEW.organization_id AND z.authorization_id = NEW.authorization_id
                      AND z.workspace_id = NEW.workspace_id AND z.automation_id = NEW.automation_id
                      AND z.automation_version_id IS NOT DISTINCT FROM NEW.automation_version_id AND z.worker_id = NEW.worker_id
                      AND z.package_fingerprint IS NOT DISTINCT FROM NEW.package_fingerprint AND z.source_kind = NEW.source_kind) THEN
        RAISE EXCEPTION 'registro diverge da autorizacao' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.worker_agent_assignments a
                    WHERE a.organization_id = NEW.organization_id AND a.assignment_id = NEW.worker_agent_assignment_id
                      AND a.worker_id = NEW.worker_id AND a.agent_id = NEW.agent_id) THEN
        RAISE EXCEPTION 'agent nao associado ao worker da autorizacao' USING ERRCODE = '23514';
    END IF;
    IF NEW.origin = 2 AND NOT EXISTS (SELECT 1 FROM public.run_requests q
                    WHERE q.organization_id = NEW.organization_id AND q.run_request_id = NEW.run_request_id
                      AND q.agent_id = NEW.agent_id AND q.authorization_id = NEW.authorization_id
                      AND q.agent_execution_id = NEW.agent_execution_id AND q.state = 3) THEN
        RAISE EXCEPTION 'pedido nao aceito por este agent com esta execucao' USING ERRCODE = '23514';
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

-- Registro: a 025 com IS DISTINCT FROM nas colunas agora anulaveis (com "<>", NULL -> valor passaria) e as colunas novas.
CREATE OR REPLACE FUNCTION public.agent_run_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.agent_run_id <> OLD.agent_run_id OR NEW.organization_id <> OLD.organization_id OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.automation_id <> OLD.automation_id OR NEW.automation_version_id IS DISTINCT FROM OLD.automation_version_id
       OR NEW.authorization_id <> OLD.authorization_id OR NEW.agent_id <> OLD.agent_id OR NEW.worker_id <> OLD.worker_id
       OR NEW.worker_agent_assignment_id <> OLD.worker_agent_assignment_id OR NEW.agent_execution_id <> OLD.agent_execution_id
       OR NEW.origin <> OLD.origin OR NEW.platform_execution_id IS DISTINCT FROM OLD.platform_execution_id
       OR NEW.package_fingerprint IS DISTINCT FROM OLD.package_fingerprint OR NEW.registered_at <> OLD.registered_at
       OR NEW.authorization_state_at_registration <> OLD.authorization_state_at_registration
       OR NEW.source_kind <> OLD.source_kind OR NEW.run_request_id IS DISTINCT FROM OLD.run_request_id THEN
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

-- Pedido: nasce REQUESTED, com instante do servidor, para autorizacao ATIVA de pacote, com os mesmos destino e pacote da
-- autorizacao, Agent ativo e associado ao Worker, automacao e workspace ativos, e quem pede com automation.execute.
CREATE FUNCTION public.run_request_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_span INTERVAL;
BEGIN
    -- duracao pedida em segundos inteiros (os dois instantes vem de clock_timestamp() avaliados um depois do outro)
    v_span := make_interval(secs => round(extract(epoch FROM NEW.valid_until - NEW.requested_at)));
    NEW.requested_at := clock_timestamp();
    NEW.valid_until := NEW.requested_at + v_span; -- a validade pedida e uma duracao: conta do instante do servidor
    IF NEW.state <> 1 OR NEW.delivered_at IS NOT NULL OR NEW.deliveries <> 0 OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL
       OR NEW.agent_execution_id IS NOT NULL OR NEW.not_started_at IS NOT NULL OR NEW.linked_at IS NOT NULL OR NEW.agent_run_id IS NOT NULL
       OR NEW.expired_at IS NOT NULL OR NEW.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'pedido nasce registrado e sem decisao' USING ERRCODE = '23514';
    END IF;
    IF NOT public.agent_workspace_permission(NEW.organization_id, NEW.requested_by_membership_id, NEW.workspace_id, 'automation.execute') THEN
        RAISE EXCEPTION 'sem automation.execute no workspace' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.local_run_authorizations z
                     JOIN public.worker_workspace_assignments x ON x.organization_id = z.organization_id
                                                                AND x.assignment_id = z.worker_workspace_assignment_id AND x.status = 1
                    WHERE z.organization_id = NEW.organization_id AND z.authorization_id = NEW.authorization_id
                      AND z.revoked_at IS NULL AND z.valid_until > NEW.requested_at AND z.source_kind IN (1, 2)
                      AND z.source_kind = NEW.source_kind AND z.workspace_id = NEW.workspace_id AND z.automation_id = NEW.automation_id
                      AND z.automation_version_id = NEW.automation_version_id AND z.worker_id = NEW.worker_id
                      AND z.package_fingerprint = NEW.package_fingerprint AND z.package_fingerprint_rule = NEW.package_fingerprint_rule) THEN
        RAISE EXCEPTION 'autorizacao inativa, de monitoramento ou divergente do pedido' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.worker_agent_assignments a JOIN public.agents g ON g.organization_id = a.organization_id AND g.agent_id = a.agent_id
                    WHERE a.organization_id = NEW.organization_id AND a.worker_id = NEW.worker_id AND a.agent_id = NEW.agent_id
                      AND a.status = 1 AND g.status = 2) THEN -- agents.status 2 = ACTIVE (004)
        RAISE EXCEPTION 'agent inativo ou nao associado ao worker da autorizacao' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.automations a JOIN public.workspaces w ON w.organization_id = a.organization_id AND w.workspace_id = a.workspace_id
                    WHERE a.organization_id = NEW.organization_id AND a.automation_id = NEW.automation_id AND a.status = 1 AND w.status = 1) THEN
        RAISE EXCEPTION 'automacao ou workspace nao ativo' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- Pedido: identidade imutavel; so as transicoes do contrato, com o instante do servidor; revogacao so antes da decisao e por
-- quem tem automation.execute; terminal congelado.
CREATE FUNCTION public.run_request_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_now TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF (to_jsonb(NEW) - ARRAY['state', 'delivered_at', 'deliveries', 'decided_at', 'decision_reason', 'agent_execution_id', 'not_started_at',
                              'linked_at', 'agent_run_id', 'expired_at', 'revoked_at', 'revoked_by_membership_id'])
       <> (to_jsonb(OLD) - ARRAY['state', 'delivered_at', 'deliveries', 'decided_at', 'decision_reason', 'agent_execution_id', 'not_started_at',
                                 'linked_at', 'agent_run_id', 'expired_at', 'revoked_at', 'revoked_by_membership_id']) THEN
        RAISE EXCEPTION 'identidade do pedido e imutavel (run_request_id=%)', OLD.run_request_id USING ERRCODE = '23514';
    END IF;
    IF NEW.deliveries < OLD.deliveries OR (OLD.delivered_at IS NOT NULL AND NEW.delivered_at IS DISTINCT FROM OLD.delivered_at) THEN
        RAISE EXCEPTION 'entrega do pedido so avanca' USING ERRCODE = '23514';
    END IF;
    IF OLD.state IN (4, 5, 6, 7, 8) THEN
        IF NEW.state <> OLD.state OR NEW.decided_at IS DISTINCT FROM OLD.decided_at OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
           OR NEW.agent_execution_id IS DISTINCT FROM OLD.agent_execution_id OR NEW.not_started_at IS DISTINCT FROM OLD.not_started_at
           OR NEW.linked_at IS DISTINCT FROM OLD.linked_at OR NEW.agent_run_id IS DISTINCT FROM OLD.agent_run_id
           OR NEW.expired_at IS DISTINCT FROM OLD.expired_at OR NEW.revoked_at IS DISTINCT FROM OLD.revoked_at THEN
            RAISE EXCEPTION 'pedido terminal e imutavel (run_request_id=%)', OLD.run_request_id USING ERRCODE = '23514';
        END IF;
        RETURN NEW; -- so a contagem de leituras repetidas pode mudar
    END IF;
    IF OLD.state = 3 AND (NEW.agent_execution_id IS DISTINCT FROM OLD.agent_execution_id OR NEW.decided_at IS DISTINCT FROM OLD.decided_at) THEN
        RAISE EXCEPTION 'decisao do pedido e imutavel' USING ERRCODE = '23514';
    END IF;
    IF NEW.state <> OLD.state THEN
        IF NOT ((OLD.state = 1 AND NEW.state IN (2, 3, 4, 7, 8)) OR (OLD.state = 2 AND NEW.state IN (3, 4, 7, 8))
                OR (OLD.state = 3 AND NEW.state IN (5, 6))) THEN
            RAISE EXCEPTION 'transicao do pedido nao permitida (% -> %)', OLD.state, NEW.state USING ERRCODE = '23514';
        END IF;
        IF NEW.state = 2 THEN NEW.delivered_at := v_now; END IF;
        IF NEW.state IN (3, 4) THEN
            IF v_now >= OLD.valid_until THEN
                RAISE EXCEPTION 'pedido vencido nao recebe decisao' USING ERRCODE = '23514';
            END IF;
            NEW.decided_at := v_now;
            IF NEW.delivered_at IS NULL THEN NEW.delivered_at := v_now; END IF;
        END IF;
        IF NEW.state = 5 THEN NEW.not_started_at := v_now; END IF;
        IF NEW.state = 6 THEN
            NEW.linked_at := v_now;
            IF NOT EXISTS (SELECT 1 FROM public.agent_runs r WHERE r.organization_id = NEW.organization_id AND r.agent_run_id = NEW.agent_run_id
                              AND r.run_request_id = NEW.run_request_id AND r.agent_execution_id = NEW.agent_execution_id) THEN
                RAISE EXCEPTION 'execucao ligada nao e deste pedido' USING ERRCODE = '23514';
            END IF;
        END IF;
        IF NEW.state = 7 THEN
            IF v_now < OLD.valid_until THEN
                RAISE EXCEPTION 'pedido ainda no prazo' USING ERRCODE = '23514';
            END IF;
            NEW.expired_at := v_now;
        END IF;
        IF NEW.state = 8 THEN
            NEW.revoked_at := v_now;
            IF NEW.revoked_by_membership_id IS NULL
               OR NOT public.agent_workspace_permission(NEW.organization_id, NEW.revoked_by_membership_id, NEW.workspace_id, 'automation.execute') THEN
                RAISE EXCEPTION 'sem automation.execute no workspace' USING ERRCODE = '42501';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_run_requests_on_insert BEFORE INSERT ON public.run_requests FOR EACH ROW EXECUTE FUNCTION public.run_request_on_insert();
CREATE TRIGGER trg_run_requests_on_update BEFORE UPDATE ON public.run_requests FOR EACH ROW EXECUTE FUNCTION public.run_request_on_update();
CREATE TRIGGER trg_run_requests_prevent_delete BEFORE DELETE ON public.run_requests FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_run_requests_prevent_truncate BEFORE TRUNCATE ON public.run_requests FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 7. RLS por organizacao (padrao da 012/025) ===
DO $$
DECLARE
    ctx_req CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.organization_id'', true), '''')::uuid';
BEGIN
    ALTER TABLE public.run_requests ENABLE ROW LEVEL SECURITY;
    ALTER TABLE public.run_requests FORCE ROW LEVEL SECURITY;
    EXECUTE format('CREATE POLICY tenant_request_context ON public.run_requests AS PERMISSIVE FOR ALL TO nekkora_platform, nekkora_ingestion USING (%s) WITH CHECK (%s)',
                   ctx_req, ctx_req);
END;
$$;

-- === 8. Privilegios ===
REVOKE ALL ON FUNCTION public.run_request_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.run_request_on_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.local_run_authorization_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.agent_run_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.agent_run_on_update() FROM PUBLIC;
GRANT SELECT, INSERT ON public.run_requests TO nekkora_platform;
GRANT UPDATE (state, expired_at, revoked_at, revoked_by_membership_id) ON public.run_requests TO nekkora_platform;
GRANT SELECT ON public.run_requests TO nekkora_ingestion;
GRANT UPDATE (state, delivered_at, deliveries, decided_at, decision_reason, agent_execution_id, not_started_at, linked_at, agent_run_id, expired_at)
    ON public.run_requests TO nekkora_ingestion;

-- === 9. Invariantes ===
DO $$
DECLARE
    v_bad TEXT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = 'public.run_requests'::regclass AND c.relrowsecurity AND c.relforcerowsecurity) THEN
        RAISE EXCEPTION '030: RLS ausente em run_requests';
    END IF;
    IF (SELECT string_agg(p.polname || ':' || r.rolname, ',' ORDER BY r.rolname) FROM pg_policy p, unnest(p.polroles) x
          JOIN pg_roles r ON r.oid = x WHERE p.polrelid = 'public.run_requests'::regclass)
       IS DISTINCT FROM 'tenant_request_context:nekkora_ingestion,tenant_request_context:nekkora_platform' THEN
        RAISE EXCEPTION '030: politicas divergentes em run_requests';
    END IF;
    SELECT string_agg(r.rolname, ',') INTO v_bad FROM pg_roles r
    WHERE NOT r.rolsuper AND r.rolname IN ('nekkora_platform', 'nekkora_ingestion')
      AND has_table_privilege(r.oid, 'public.run_requests', 'DELETE, TRUNCATE, REFERENCES, TRIGGER');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '030: exclusao possivel em run_requests: %', v_bad; END IF;
    IF has_table_privilege('nekkora_ingestion', 'public.run_requests', 'INSERT')
       OR has_column_privilege('nekkora_ingestion', 'public.run_requests', 'revoked_at', 'UPDATE')
       OR has_column_privilege('nekkora_ingestion', 'public.run_requests', 'valid_until', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.run_requests', 'agent_execution_id', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.run_requests', 'agent_run_id', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.run_requests', 'valid_until', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.agent_runs', 'INSERT, UPDATE')
       OR has_table_privilege('nekkora_ingestion', 'public.local_run_authorizations', 'INSERT, UPDATE') THEN
        RAISE EXCEPTION '030: privilegio alem do papel em run_requests, agent_runs ou local_run_authorizations';
    END IF;
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.proname IN ('run_request_on_insert', 'run_request_on_update', 'local_run_authorization_on_insert',
                                                 'agent_run_on_insert', 'agent_run_on_update')
      AND (p.prosecdef OR NOT ('search_path=pg_catalog, pg_temp' = ANY (coalesce(p.proconfig, '{}')))
           OR pg_get_userbyid(p.proowner) <> 'migrador_nekkora' OR has_function_privilege('public', p.oid, 'EXECUTE'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '030: funcao fora do padrao: %', v_bad; END IF;
    IF EXISTS (SELECT 1 FROM public.local_run_authorizations WHERE source_kind <> 1)
       OR EXISTS (SELECT 1 FROM public.agent_runs WHERE source_kind <> 1 OR origin <> 1) THEN
        RAISE EXCEPTION '030: linhas anteriores precisam ficar com a origem de antes';
    END IF;
END;
$$;
