-- Nekkora Platform - solicitacao de execucao remota e entrega confiavel a maquina
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T02). Contrato: docs/platform-client-execucao-remota-001/t02-solicitacao-e-entrega.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 042 (habilitacao), da 045 (dependencias declaradas) e das autorizacoes de
-- execucao (025/030). Numero 046 conferido em 15/11 contra a dev e os PRs abertos; se outra 046 entrar antes, esta e
-- renumerada sem mudar o conteudo.
--
-- DECISAO DA TITULAR (15/11): para ACEITAR uma solicitacao, exigir
--   (a) pessoa com automation.execute no ambiente;
--   (b) Worker autorizado no ambiente;
--   (c) maquina habilitada, nao pausada e compativel (declara REMOTE_RUN_1_0);
--   (d) autorizacao VIGENTE da versao publicada para AQUELE Worker (a de execucao local, 025/030; nunca a de monitoramento).
-- As mesmas condicoes sao REVALIDADAS antes de iniciar: se a autorizacao expirar ou for revogada durante a espera ou a
-- preparacao, a tentativa NAO inicia e o motivo fica gravado. Na repeticao em outra maquina, vale a autorizacao da MESMA
-- versao para o Worker de DESTINO; a autorizacao nunca e transferida. Habilitar a maquina nao contorna nenhuma dessas regras.
--
-- O QUE FAZ
--   1. remote_executions: a solicitacao de uma pessoa. Fixa a versao publicada, a impressao do pacote e a REVISAO das
--      dependencias (com a lista efetiva congelada); guarda quem pediu e, se houver, quem pediu o cancelamento.
--   2. remote_attempts: cada tentativa de executar a solicitacao numa maquina. Guarda a autorizacao usada (do Worker daquela
--      maquina), o estado, o motivo de uma recusa e a entrega: token da atribuicao (so o hash), epoca e prazo (lease).
--      No maximo UMA tentativa nao terminada por maquina e por solicitacao (indices unicos parciais): a reserva e atomica.
--   3. remote_attempt_blocker(): a revalidacao, num lugar so. Diz por que uma tentativa nao pode comecar agora (ou nulo).
--
-- ESTADOS DA TENTATIVA
--   1=QUEUED 2=OFFERED 3=RECEIVED 4=PREPARING 5=STARTING 6=RUNNING
--   7=SUCCEEDED 8=FAILED 9=CANCELLED 10=TIMED_OUT 11=REFUSED 12=EXPIRED 13=ENDED_UNKNOWN   (7..13 sao terminais)
--
-- O QUE NAO FAZ: nao entrega o pacote, nao guarda logs nem resultados (T03 e T05); nao decide failover (nao ha troca
-- automatica de maquina); nao muda o pedido de execucao do Agent anterior (run_requests, 030) nem as autorizacoes.
--
-- DADOS EXISTENTES: nenhuma linha e alterada. As tabelas novas nascem vazias.
--
-- REVERSAO (manual; so enquanto as tabelas estiverem vazias):
--   BEGIN;
--   DROP TABLE public.remote_attempts;
--   DROP TABLE public.remote_executions;
--   DROP FUNCTION public.remote_attempt_on_insert();
--   DROP FUNCTION public.remote_attempt_on_update();
--   DROP FUNCTION public.remote_execution_on_insert();
--   DROP FUNCTION public.remote_execution_on_update();
--   DROP FUNCTION public.remote_attempt_blocker(uuid, uuid, uuid, uuid, uuid);
--   COMMIT;
--
-- SEM ELEVACAO: tabelas novas, gatilhos e funcoes SECURITY INVOKER. Nenhum papel novo, nenhuma funcao SECURITY DEFINER.

BEGIN;

-- === 1. Pre-condicao ===
DO $$
BEGIN
    IF to_regclass('public.agent_remote_enablements') IS NULL OR to_regclass('public.automation_runtime_requirements') IS NULL
       OR to_regclass('public.local_run_authorizations') IS NULL THEN
        RAISE EXCEPTION '046 exige a 042 e a 045 aplicadas';
    END IF;
    IF to_regclass('public.remote_executions') IS NOT NULL THEN
        RAISE EXCEPTION '046: remote_executions ja existe';
    END IF;
END;
$$;

-- === 2. Solicitacao ===
CREATE TABLE public.remote_executions (
    remote_execution_id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id                    UUID NOT NULL,
    workspace_id                       UUID NOT NULL,
    automation_id                      UUID NOT NULL,
    automation_version_id              UUID NOT NULL,
    package_fingerprint                TEXT NOT NULL CHECK (package_fingerprint ~ '^[0-9a-f]{64}$'),
    package_fingerprint_rule           TEXT NOT NULL CHECK (package_fingerprint_rule IN ('nekkora-agent/package-fingerprint/1',
                                                                                         'nekkora-agent/external-python-fingerprint/1')),
    requirements_revision              INTEGER NOT NULL,
    effective_dependencies             JSONB NOT NULL CHECK (jsonb_typeof(effective_dependencies) = 'array' AND jsonb_array_length(effective_dependencies) <= 400
                                                             AND octet_length(effective_dependencies::text) <= 32768),
    request_key                        UUID NOT NULL,
    requested_by_membership_id         UUID NOT NULL,
    requested_at                       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    cancel_requested_at                TIMESTAMPTZ,
    cancel_requested_by_membership_id  UUID,
    UNIQUE (organization_id, remote_execution_id),
    UNIQUE (organization_id, request_key),
    FOREIGN KEY (organization_id, workspace_id, automation_id, automation_version_id)
        REFERENCES public.automation_versions (organization_id, workspace_id, automation_id, automation_version_id) ON DELETE RESTRICT,
    FOREIGN KEY (automation_id, requirements_revision)
        REFERENCES public.automation_runtime_requirements (automation_id, revision_number) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, requested_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, cancel_requested_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK ((cancel_requested_at IS NULL) = (cancel_requested_by_membership_id IS NULL))
);
CREATE INDEX ix_remote_executions_workspace ON public.remote_executions (organization_id, workspace_id, requested_at, remote_execution_id);
COMMENT ON TABLE public.remote_executions IS
    'Solicitacao de execucao remota (046): uma pessoa pede que uma versao publicada rode numa maquina habilitada. Fixa a versao, a '
    'impressao do pacote e a revisao das dependencias. Solicitar nao prova inicio: o que aconteceu esta nas tentativas.';
COMMENT ON COLUMN public.remote_executions.effective_dependencies IS
    'Retrato do efetivo (manifesto + acrescimos declarados) no momento da solicitacao. Novas tentativas usam o mesmo retrato.';

CREATE FUNCTION public.remote_execution_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.requested_at := clock_timestamp();
    IF NEW.cancel_requested_at IS NOT NULL THEN
        RAISE EXCEPTION 'a solicitacao nasce sem pedido de cancelamento' USING ERRCODE = '23514';
    END IF;
    -- (a) quem pede tem automation.execute no ambiente
    IF NOT public.agent_workspace_permission(NEW.organization_id, NEW.requested_by_membership_id, NEW.workspace_id, 'automation.execute') THEN
        RAISE EXCEPTION 'sem automation.execute no ambiente' USING ERRCODE = '42501';
    END IF;
    -- a versao e PUBLICADA e o pacote e o dela; automacao e ambiente ativos
    IF NOT EXISTS (SELECT 1 FROM public.automation_versions v
                     JOIN public.automations a ON a.organization_id = v.organization_id AND a.automation_id = v.automation_id AND a.status = 1
                     JOIN public.workspaces w ON w.organization_id = v.organization_id AND w.workspace_id = v.workspace_id AND w.status = 1
                    WHERE v.organization_id = NEW.organization_id AND v.automation_version_id = NEW.automation_version_id
                      AND v.automation_id = NEW.automation_id AND v.workspace_id = NEW.workspace_id AND v.status = 2
                      AND (v.command_json -> 'agent_package' ->> 'fingerprint_rule') = NEW.package_fingerprint_rule
                      AND (v.command_json -> 'agent_package' ->> 'fingerprint') = NEW.package_fingerprint) THEN
        RAISE EXCEPTION 'versao nao publicada, pacote diferente do publicado, ou automacao/ambiente inativo' USING ERRCODE = '23514';
    END IF;
    -- a revisao das dependencias e a VIGENTE da automacao
    IF NEW.requirements_revision IS DISTINCT FROM (SELECT max(r.revision_number) FROM public.automation_runtime_requirements r
                                                    WHERE r.organization_id = NEW.organization_id AND r.automation_id = NEW.automation_id) THEN
        RAISE EXCEPTION 'a solicitacao fixa a revisao vigente das dependencias' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_executions_on_insert BEFORE INSERT ON public.remote_executions
    FOR EACH ROW EXECUTE FUNCTION public.remote_execution_on_insert();

-- Depois de criada, so o pedido de cancelamento muda, uma vez, por quem tem automation.execute no ambiente.
CREATE FUNCTION public.remote_execution_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF (to_jsonb(NEW) - 'cancel_requested_at' - 'cancel_requested_by_membership_id')
       IS DISTINCT FROM (to_jsonb(OLD) - 'cancel_requested_at' - 'cancel_requested_by_membership_id') THEN
        RAISE EXCEPTION 'a solicitacao so muda o pedido de cancelamento' USING ERRCODE = '42501';
    END IF;
    IF OLD.cancel_requested_at IS NOT NULL THEN
        RAISE EXCEPTION 'o cancelamento ja foi pedido' USING ERRCODE = '23514';
    END IF;
    IF NEW.cancel_requested_by_membership_id IS NULL
       OR NOT public.agent_workspace_permission(NEW.organization_id, NEW.cancel_requested_by_membership_id, NEW.workspace_id, 'automation.execute') THEN
        RAISE EXCEPTION 'sem automation.execute no ambiente' USING ERRCODE = '42501';
    END IF;
    NEW.cancel_requested_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_executions_on_update BEFORE UPDATE ON public.remote_executions
    FOR EACH ROW EXECUTE FUNCTION public.remote_execution_on_update();
CREATE TRIGGER trg_remote_executions_prevent_delete BEFORE DELETE ON public.remote_executions
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_remote_executions_prevent_truncate BEFORE TRUNCATE ON public.remote_executions
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 3. A revalidacao, num lugar so ===
-- Por que uma tentativa desta solicitacao, nesta maquina, com esta autorizacao, NAO pode comecar agora. Nulo: pode.
-- A ordem e a do que a pessoa consegue resolver primeiro. Vale na criacao (gatilho), na oferta e imediatamente antes de iniciar.
CREATE FUNCTION public.remote_attempt_blocker(p_org UUID, p_execution UUID, p_agent UUID, p_worker UUID, p_authorization UUID) RETURNS TEXT
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT CASE
        WHEN x.remote_execution_id IS NULL THEN 'EXECUTION_NOT_FOUND'
        -- (d) autorizacao da MESMA versao e do MESMO pacote, para ESTE Worker, de execucao (nunca de monitoramento)
        WHEN la.authorization_id IS NULL OR la.worker_id <> p_worker OR la.source_kind NOT IN (1, 2)
             OR la.automation_version_id IS DISTINCT FROM x.automation_version_id OR la.workspace_id <> x.workspace_id
             OR la.package_fingerprint IS DISTINCT FROM x.package_fingerprint
             OR la.package_fingerprint_rule IS DISTINCT FROM x.package_fingerprint_rule THEN 'AUTHORIZATION_MISSING'
        WHEN la.revoked_at IS NOT NULL THEN 'AUTHORIZATION_REVOKED'
        WHEN la.valid_until <= clock_timestamp() THEN 'AUTHORIZATION_EXPIRED'
        -- (b) Worker ativo e ainda autorizado no ambiente
        WHEN NOT EXISTS (SELECT 1 FROM public.worker_workspace_assignments ww
                           JOIN public.workers wk ON wk.organization_id = ww.organization_id AND wk.worker_id = ww.worker_id AND wk.status = 1
                          WHERE ww.organization_id = p_org AND ww.assignment_id = la.worker_workspace_assignment_id AND ww.status = 1) THEN 'WORKER_NOT_AUTHORIZED'
        -- (c) maquina ativa, associada a este Worker, habilitada
        WHEN NOT EXISTS (SELECT 1 FROM public.agents a
                           JOIN public.worker_agent_assignments wa ON wa.organization_id = a.organization_id AND wa.agent_id = a.agent_id
                                                                   AND wa.worker_id = p_worker AND wa.status = 1
                          WHERE a.organization_id = p_org AND a.agent_id = p_agent AND a.status = 2) THEN 'MACHINE_NOT_LINKED'
        WHEN en.agent_id IS NULL THEN 'MACHINE_NOT_ENABLED'
        WHEN NOT coalesce(pr.capabilities @> ARRAY['REMOTE_RUN_1_0']::text[], false) THEN 'MACHINE_INCOMPATIBLE'
        ELSE NULL END
      FROM (SELECT 1) one
      LEFT JOIN public.remote_executions x ON x.organization_id = p_org AND x.remote_execution_id = p_execution
      LEFT JOIN public.local_run_authorizations la ON la.organization_id = p_org AND la.authorization_id = p_authorization
      LEFT JOIN public.agent_remote_enablements en ON en.organization_id = p_org AND en.agent_id = p_agent
      LEFT JOIN public.agent_presence pr ON pr.agent_id = p_agent;
$$;
COMMENT ON FUNCTION public.remote_attempt_blocker(uuid, uuid, uuid, uuid, uuid) IS
    'Revalidacao das condicoes de uma tentativa (046): nulo = pode; senao o motivo. Usada na criacao, na oferta e antes de iniciar. '
    'Nao olha a pausa nem o cancelamento (sao da entrega).';

-- === 4. Tentativa ===
CREATE TABLE public.remote_attempts (
    attempt_id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             UUID NOT NULL,
    remote_execution_id         UUID NOT NULL,
    attempt_number              INTEGER NOT NULL CHECK (attempt_number >= 1),
    agent_id                    UUID NOT NULL,
    worker_id                   UUID NOT NULL,
    authorization_id            UUID NOT NULL,
    created_by_membership_id    UUID NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    offer_valid_until           TIMESTAMPTZ NOT NULL,
    state                       SMALLINT NOT NULL DEFAULT 1 CHECK (state BETWEEN 1 AND 13),
    state_changed_at            TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    refusal_reason              TEXT CHECK (refusal_reason IN ('BUSY', 'PAUSED', 'PACKAGE_INVALID', 'ENVIRONMENT_GONE', 'ENVIRONMENT_INCOMPATIBLE',
                                                              'DEPENDENCIES_MISSING', 'DISK_PRESSURE', 'CLIENT_INCOMPATIBLE', 'AUTHORIZATION_MISSING',
                                                              'AUTHORIZATION_REVOKED', 'AUTHORIZATION_EXPIRED', 'WORKER_NOT_AUTHORIZED', 'MACHINE_NOT_LINKED',
                                                              'MACHINE_NOT_ENABLED', 'MACHINE_INCOMPATIBLE')),
    exit_code                   INTEGER,
    token_hash                  TEXT CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    epoch                       INTEGER NOT NULL DEFAULT 0 CHECK (epoch >= 0),
    lease_expires_at            TIMESTAMPTZ,
    offered_at                  TIMESTAMPTZ,
    received_at                 TIMESTAMPTZ,
    started_at                  TIMESTAMPTZ,
    ended_at                    TIMESTAMPTZ,
    UNIQUE (organization_id, attempt_id),
    UNIQUE (remote_execution_id, attempt_number),
    FOREIGN KEY (organization_id, remote_execution_id) REFERENCES public.remote_executions (organization_id, remote_execution_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, agent_id) REFERENCES public.agents (organization_id, agent_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, worker_id) REFERENCES public.workers (organization_id, worker_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, authorization_id) REFERENCES public.local_run_authorizations (organization_id, authorization_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, created_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK ((state = 11) = (refusal_reason IS NOT NULL)),
    CHECK (exit_code IS NULL OR state IN (7, 8, 10)),
    CHECK ((state >= 7) = (ended_at IS NOT NULL)),
    CHECK (state = 1 OR token_hash IS NOT NULL OR state IN (9, 11, 12)),
    CHECK (offer_valid_until > created_at AND offer_valid_until <= created_at + interval '24 hours')
);
-- A reserva: no maximo uma tentativa NAO terminada por maquina, e uma por solicitacao.
CREATE UNIQUE INDEX ux_remote_attempts_agent_active ON public.remote_attempts (agent_id) WHERE state BETWEEN 1 AND 6;
CREATE UNIQUE INDEX ux_remote_attempts_execution_active ON public.remote_attempts (remote_execution_id) WHERE state BETWEEN 1 AND 6;
CREATE INDEX ix_remote_attempts_execution ON public.remote_attempts (organization_id, remote_execution_id, attempt_number);
COMMENT ON TABLE public.remote_attempts IS
    'Tentativa de executar uma solicitacao numa maquina (046). Uma nao terminada por maquina e por solicitacao. Entregar nao prova '
    'recebimento; receber nao prova inicio; o estado so avanca com o token e a epoca da atribuicao vigente.';
COMMENT ON COLUMN public.remote_attempts.state IS
    '1=QUEUED, 2=OFFERED, 3=RECEIVED, 4=PREPARING, 5=STARTING, 6=RUNNING, 7=SUCCEEDED, 8=FAILED, 9=CANCELLED, 10=TIMED_OUT, 11=REFUSED, '
    '12=EXPIRED, 13=ENDED_UNKNOWN (7..13 terminais)';
COMMENT ON COLUMN public.remote_attempts.token_hash IS
    'SHA-256 do token da atribuicao, entregue a maquina na oferta. Cada nova oferta troca o token e soma a epoca: quem tem o anterior nao escreve mais.';
COMMENT ON COLUMN public.remote_attempts.lease_expires_at IS
    'Prazo da comunicacao. Vencido, a comunicacao esta PERDIDA; isso NAO muda o estado nem encerra a tentativa.';

CREATE FUNCTION public.remote_attempt_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_why TEXT;
BEGIN
    NEW.created_at := clock_timestamp();
    NEW.state_changed_at := NEW.created_at;
    IF NEW.state <> 1 OR NEW.token_hash IS NOT NULL OR NEW.epoch <> 0 OR NEW.lease_expires_at IS NOT NULL OR NEW.offered_at IS NOT NULL
       OR NEW.received_at IS NOT NULL OR NEW.started_at IS NOT NULL OR NEW.ended_at IS NOT NULL OR NEW.exit_code IS NOT NULL THEN
        RAISE EXCEPTION 'a tentativa nasce na fila, sem entrega' USING ERRCODE = '23514';
    END IF;
    IF NEW.attempt_number <> coalesce((SELECT max(t.attempt_number) FROM public.remote_attempts t WHERE t.remote_execution_id = NEW.remote_execution_id), 0) + 1 THEN
        RAISE EXCEPTION 'a tentativa e sempre a seguinte' USING ERRCODE = '23514';
    END IF;
    -- (a) quem cria a tentativa tem automation.execute no ambiente da solicitacao
    IF NOT EXISTS (SELECT 1 FROM public.remote_executions x
                    WHERE x.organization_id = NEW.organization_id AND x.remote_execution_id = NEW.remote_execution_id
                      AND x.cancel_requested_at IS NULL
                      AND public.agent_workspace_permission(x.organization_id, NEW.created_by_membership_id, x.workspace_id, 'automation.execute')) THEN
        RAISE EXCEPTION 'sem automation.execute no ambiente, ou solicitacao com cancelamento pedido' USING ERRCODE = '42501';
    END IF;
    -- (b), (c) e (d): as mesmas condicoes da revalidacao
    v_why := public.remote_attempt_blocker(NEW.organization_id, NEW.remote_execution_id, NEW.agent_id, NEW.worker_id, NEW.authorization_id);
    IF v_why IS NOT NULL THEN
        RAISE EXCEPTION 'tentativa recusada: %', v_why USING ERRCODE = '23514';
    END IF;
    -- (c) nao pausada: a maquina que pausou o recebimento nao recebe trabalho novo
    IF (SELECT receiving FROM public.agent_remote_enablements e WHERE e.organization_id = NEW.organization_id AND e.agent_id = NEW.agent_id) <> 1 THEN
        RAISE EXCEPTION 'tentativa recusada: MACHINE_PAUSED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempts_on_insert BEFORE INSERT ON public.remote_attempts
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_on_insert();

-- Transicoes. A identidade da tentativa nao muda; um estado terminal nao sai; RUNNING so vem de STARTING.
CREATE FUNCTION public.remote_attempt_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.attempt_id <> OLD.attempt_id OR NEW.organization_id <> OLD.organization_id OR NEW.remote_execution_id <> OLD.remote_execution_id
       OR NEW.attempt_number <> OLD.attempt_number OR NEW.agent_id <> OLD.agent_id OR NEW.worker_id <> OLD.worker_id
       OR NEW.authorization_id <> OLD.authorization_id OR NEW.created_by_membership_id <> OLD.created_by_membership_id
       OR NEW.created_at <> OLD.created_at OR NEW.offer_valid_until <> OLD.offer_valid_until THEN
        RAISE EXCEPTION 'a identidade da tentativa nao muda' USING ERRCODE = '42501';
    END IF;
    IF OLD.state >= 7 THEN
        RAISE EXCEPTION 'tentativa terminada nao muda' USING ERRCODE = '23514';
    END IF;
    IF NEW.epoch < OLD.epoch THEN
        RAISE EXCEPTION 'a epoca da atribuicao so cresce' USING ERRCODE = '23514';
    END IF;
    IF NEW.state <> OLD.state THEN
        IF NOT (
               (OLD.state = 1 AND NEW.state IN (2, 9, 11, 12))
            OR (OLD.state = 2 AND NEW.state IN (3, 9, 11, 12))
            OR (OLD.state = 3 AND NEW.state IN (4, 9, 11, 13))
            OR (OLD.state = 4 AND NEW.state IN (5, 9, 11, 13))
            OR (OLD.state = 5 AND NEW.state IN (6, 8, 9, 11, 13))
            OR (OLD.state = 6 AND NEW.state IN (7, 8, 9, 10, 13))) THEN
            RAISE EXCEPTION 'transicao de tentativa nao permitida: % -> %', OLD.state, NEW.state USING ERRCODE = '23514';
        END IF;
        -- as pessoas (nekkora_platform) so encerram o que a maquina ainda nao recebeu; o resto e da maquina
        IF current_user = 'nekkora_platform' AND NOT (OLD.state IN (1, 2) AND NEW.state IN (9, 12)) THEN
            RAISE EXCEPTION 'so a maquina muda o estado de uma tentativa recebida' USING ERRCODE = '42501';
        END IF;
        NEW.state_changed_at := clock_timestamp();
        IF NEW.state = 2 THEN
            NEW.offered_at := clock_timestamp();
        ELSIF NEW.state = 3 THEN
            NEW.received_at := clock_timestamp();
        ELSIF NEW.state = 6 THEN
            NEW.started_at := clock_timestamp();
        END IF;
        IF NEW.state >= 7 THEN
            NEW.ended_at := clock_timestamp();
            NEW.lease_expires_at := NULL;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempts_on_update BEFORE UPDATE ON public.remote_attempts
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_on_update();
CREATE TRIGGER trg_remote_attempts_prevent_delete BEFORE DELETE ON public.remote_attempts
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_remote_attempts_prevent_truncate BEFORE TRUNCATE ON public.remote_attempts
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 5. RLS ===
DO $$
DECLARE
    ctx_req CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.organization_id'', true), '''')::uuid';
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['remote_executions', 'remote_attempts'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_request_context ON public.%I AS PERMISSIVE FOR ALL TO nekkora_platform, nekkora_ingestion USING (%s) WITH CHECK (%s)',
                       t, ctx_req, ctx_req);
    END LOOP;
END;
$$;

-- === 6. Privilegios ===
-- Pessoas: criam a solicitacao e a tentativa, pedem o cancelamento e encerram o que ainda nao foi recebido.
-- Maquina: le a solicitacao e a propria tentativa e conduz a entrega e o estado. Ninguem apaga.
REVOKE ALL ON public.remote_executions, public.remote_attempts FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_execution_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_execution_on_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_on_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_blocker(uuid, uuid, uuid, uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.remote_attempt_blocker(uuid, uuid, uuid, uuid, uuid) TO nekkora_platform, nekkora_ingestion;
GRANT SELECT, INSERT ON public.remote_executions TO nekkora_platform;
GRANT UPDATE (cancel_requested_at, cancel_requested_by_membership_id) ON public.remote_executions TO nekkora_platform;
GRANT SELECT, INSERT ON public.remote_attempts TO nekkora_platform;
GRANT UPDATE (state, state_changed_at, ended_at, lease_expires_at) ON public.remote_attempts TO nekkora_platform;
GRANT SELECT ON public.remote_executions TO nekkora_ingestion;
GRANT SELECT ON public.remote_attempts TO nekkora_ingestion;
GRANT UPDATE (state, state_changed_at, refusal_reason, exit_code, token_hash, epoch, lease_expires_at, offered_at, received_at, started_at, ended_at)
    ON public.remote_attempts TO nekkora_ingestion;
-- as dependencias chegam a maquina pelo retrato congelado na solicitacao: ela continua sem ler automation_runtime_requirements (045)

-- === 7. Invariantes ===
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = 'public.remote_executions'::regclass AND c.relrowsecurity AND c.relforcerowsecurity)
       OR NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = 'public.remote_attempts'::regclass AND c.relrowsecurity AND c.relforcerowsecurity) THEN
        RAISE EXCEPTION '046: RLS ausente';
    END IF;
    IF has_table_privilege('nekkora_ingestion', 'public.remote_executions', 'INSERT')
       OR has_table_privilege('nekkora_ingestion', 'public.remote_executions', 'UPDATE')
       OR has_table_privilege('nekkora_ingestion', 'public.remote_attempts', 'INSERT')
       OR has_table_privilege('nekkora_ingestion', 'public.remote_attempts', 'DELETE')
       OR has_table_privilege('nekkora_platform', 'public.remote_attempts', 'DELETE')
       OR has_table_privilege('nekkora_platform', 'public.remote_executions', 'DELETE')
       OR has_column_privilege('nekkora_platform', 'public.remote_attempts', 'token_hash', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.remote_attempts', 'exit_code', 'UPDATE')
       OR has_column_privilege('nekkora_ingestion', 'public.remote_attempts', 'authorization_id', 'UPDATE') THEN
        RAISE EXCEPTION '046: privilegio alem do previsto na solicitacao remota';
    END IF;
END;
$$;

COMMIT;
