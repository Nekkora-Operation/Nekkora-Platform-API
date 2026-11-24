-- Nekkora Platform - ambiente Python por execucao (AMBIENTE-PYTHON-001). Contrato: docs/ambiente-python-001/CONTRATO.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 044 (inventario), da 045 (dependencias declaradas), da 046 (solicitacoes e
-- tentativas), da 047 (escolha do Python) e da 075 (motivos de recusa vigentes). Numero 076 escolhido em 24/11 sobre a #121
-- (073-075); se outra entrar antes, esta e renumerada.
--
-- O QUE FAZ
--   managed_environment_resolutions: a pessoa pede que a MAQUINA resolva a lista completa (com as dependencias indiretas,
--     so wheels, no PyPI publico) da lista efetiva de UMA versao, para o Python base escolhido (uma INSTALACAO). A maquina
--     responde uma vez: 1 RESOLVED (com o lock: nome, versao, sha256, arquivo), 2 INCOMPATIBLE, 3 ENVIRONMENT_GONE,
--     4 NOT_RESOLVED. Um pedido em aberto por automacao e maquina.
--   managed_environment_authorizations: o ACEITE explicito do lock resolvido, preso a versao + maquina + Worker + Python
--     base + revisao das dependencias + lock (sha256) + verificacoes externas escolhidas. Um vigente por versao e maquina;
--     lock diferente exige aceite novo. So insercao e revogacao.
--   remote_executions.environment_mode: 1 PREPARED (o de hoje; padrao de TODAS as linhas existentes), 2 MANAGED.
--   remote_attempt_environments: o retrato do aceite usado numa tentativa MANAGED (lock e verificacoes). Imutavel.
--   remote_attempt_environment_stages: as etapas do preparo informadas pela maquina (1 ENV_CREATING, 2 DEPS_INSTALLING).
--   remote_attempt_environment_reports: o relatorio do preparo (1 READY, 2 FAILED, 3 INTERRUPTED), uma vez por tentativa,
--     com o que foi instalado de fato ou a dependencia e a fase da falha, e o registro do preparo (ja sem segredos).
--   remote_attempts.refusal_reason: + ENVIRONMENT_SETUP_FAILED, DEPENDENCY_INSTALL_FAILED, ENVIRONMENT_SETUP_INTERRUPTED,
--     EXTERNAL_PREREQUISITE_MISSING (motivos da maquina; a automacao NAO iniciou).
--
-- RETENCAO (D7): o registro do preparo (preparation_log) vale 30 dias contados de reported_at, com ou sem inicio da
-- automacao; depois a Platform o apaga (NULL + log_expired_at). O resumo estruturado da falha fica.
--
-- O QUE NAO FAZ: nao instala nada, nao muda execucoes existentes (todas ficam PREPARED), nao muda a escolha do Python, nao
-- aceita indice, URL, Git ou caminho (a declaracao da 045 ja recusa; o lock so tem nome, versao, hash e nome de wheel).
--
-- DADOS EXISTENTES: remote_executions ganha a coluna com padrao 1 (o gatilho da 046 ja impede mudar depois). Tabelas novas
-- nascem vazias.
--
-- REVERSAO (manual):
--   BEGIN;
--   DROP TABLE public.remote_attempt_environment_reports, public.remote_attempt_environment_stages, public.remote_attempt_environments,
--              public.managed_environment_authorizations, public.managed_environment_resolutions;
--   DROP FUNCTION public.managed_env_resolution_on_insert(), public.managed_env_resolution_on_update(),
--                 public.managed_env_authorization_on_insert(), public.managed_env_authorization_on_update(),
--                 public.remote_attempt_environment_on_insert(), public.remote_attempt_environment_stage_on_insert(),
--                 public.remote_attempt_environment_report_on_insert(), public.remote_attempt_environment_report_on_update();
--   ALTER TABLE public.remote_executions DROP COLUMN environment_mode;
--   (restaurar o CHECK de refusal_reason da 075)
--   COMMIT;
--
-- SEM ELEVACAO: tabelas novas, gatilhos SECURITY INVOKER. Nenhum papel novo, nenhuma funcao SECURITY DEFINER.

BEGIN;

-- === 1. Pre-condicao ===
DO $$
BEGIN
    IF to_regclass('public.remote_attempts') IS NULL OR to_regclass('public.automation_python_selections') IS NULL
       OR to_regclass('public.agent_python_environments') IS NULL OR to_regclass('public.organization_nekkora_holds') IS NULL THEN
        RAISE EXCEPTION '076 exige a 044, a 046, a 047 e a 075 aplicadas';
    END IF;
    IF to_regclass('public.managed_environment_resolutions') IS NOT NULL THEN
        RAISE EXCEPTION '076: managed_environment_resolutions ja existe';
    END IF;
END;
$$;

-- === 2. Resolucao (pedido da pessoa, resposta da maquina) ===
CREATE TABLE public.managed_environment_resolutions (
    resolution_id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             UUID NOT NULL,
    workspace_id                UUID NOT NULL,
    automation_id               UUID NOT NULL,
    automation_version_id       UUID NOT NULL,
    agent_id                    UUID NOT NULL,
    environment_id              TEXT NOT NULL CHECK (environment_id ~ '^[A-Za-z0-9_-]{43}$'),
    requirements_revision       INTEGER NOT NULL,
    dependencies                JSONB NOT NULL CHECK (jsonb_typeof(dependencies) = 'array' AND jsonb_array_length(dependencies) <= 400
                                                      AND octet_length(dependencies::text) <= 32768),
    requested_by_membership_id  UUID NOT NULL,
    requested_at                TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    answer_valid_until          TIMESTAMPTZ NOT NULL,
    delivered_at                TIMESTAMPTZ,
    outcome                     SMALLINT CHECK (outcome BETWEEN 1 AND 4),
    python_version              TEXT CHECK (python_version ~ '^3\.[0-9]{1,3}\.[0-9]{1,3}[A-Za-z0-9.+-]{0,20}$'),
    pip_version                 TEXT CHECK (pip_version ~ '^[0-9][0-9A-Za-z.+-]{0,30}$'),
    lock                        JSONB CHECK (lock IS NULL OR (jsonb_typeof(lock) = 'array' AND jsonb_array_length(lock) <= 400
                                                              AND octet_length(lock::text) <= 131072)),
    lock_sha256                 TEXT CHECK (lock_sha256 ~ '^[0-9a-f]{64}$'),
    failure_requirement         TEXT CHECK (char_length(failure_requirement) BETWEEN 1 AND 200),
    failure_phase               TEXT CHECK (failure_phase IN ('NO_COMPATIBLE_WHEEL', 'RESOLUTION_CONFLICT', 'NOT_FOUND', 'INDEX_UNREACHABLE',
                                                              'PIP_UNAVAILABLE', 'TIMEOUT', 'OTHER')),
    failure_summary             TEXT CHECK (char_length(failure_summary) <= 500),
    answered_at                 TIMESTAMPTZ,
    UNIQUE (organization_id, resolution_id),
    FOREIGN KEY (organization_id, workspace_id, automation_id, automation_version_id)
        REFERENCES public.automation_versions (organization_id, workspace_id, automation_id, automation_version_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, agent_id) REFERENCES public.agents (organization_id, agent_id) ON DELETE RESTRICT,
    FOREIGN KEY (automation_id, requirements_revision)
        REFERENCES public.automation_runtime_requirements (automation_id, revision_number) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, requested_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK ((outcome IS NULL) = (answered_at IS NULL)),
    CHECK ((outcome = 1) = (lock IS NOT NULL) AND (lock IS NULL) = (lock_sha256 IS NULL)),
    CHECK (outcome IS NULL OR outcome IN (1, 3) OR failure_phase IS NOT NULL),
    CHECK (outcome IS NOT NULL OR (python_version IS NULL AND pip_version IS NULL AND failure_phase IS NULL AND failure_summary IS NULL
                                   AND failure_requirement IS NULL)),
    CHECK (answer_valid_until > requested_at AND answer_valid_until <= requested_at + interval '30 minutes')
);
CREATE INDEX ix_managed_env_resolutions_latest ON public.managed_environment_resolutions (organization_id, automation_id, agent_id, requested_at DESC);
CREATE INDEX ix_managed_env_resolutions_open ON public.managed_environment_resolutions (agent_id, requested_at) WHERE outcome IS NULL;
COMMENT ON TABLE public.managed_environment_resolutions IS
    'Resolucao da lista completa (076, D1): a maquina resolve, no PyPI publico e so com wheels, a lista efetiva de uma VERSAO para '
    'o Python base escolhido e responde uma vez com o lock (nome, versao, sha256, arquivo). E o que a pessoa aceita.';
COMMENT ON COLUMN public.managed_environment_resolutions.outcome IS '1=RESOLVED, 2=INCOMPATIBLE, 3=ENVIRONMENT_GONE, 4=NOT_RESOLVED; nulo = sem resposta';

CREATE FUNCTION public.managed_env_resolution_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.requested_at := clock_timestamp();
    IF NEW.answer_valid_until IS NULL OR NEW.answer_valid_until <= NEW.requested_at OR NEW.answer_valid_until > NEW.requested_at + interval '30 minutes' THEN
        RAISE EXCEPTION 'prazo da resposta fora do permitido' USING ERRCODE = '23514';
    END IF;
    IF NEW.delivered_at IS NOT NULL OR NEW.outcome IS NOT NULL OR NEW.answered_at IS NOT NULL OR NEW.lock IS NOT NULL THEN
        RAISE EXCEPTION 'a resolucao nasce sem entrega e sem resposta' USING ERRCODE = '23514';
    END IF;
    -- quem pede prepara um aceite: automation.manage no ambiente
    IF NOT public.agent_workspace_permission(NEW.organization_id, NEW.requested_by_membership_id, NEW.workspace_id, 'automation.manage') THEN
        RAISE EXCEPTION 'sem automation.manage no ambiente' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.agent_remote_enablements e
                     JOIN public.agents a ON a.organization_id = e.organization_id AND a.agent_id = e.agent_id AND a.status = 2
                    WHERE e.organization_id = NEW.organization_id AND e.agent_id = NEW.agent_id) THEN
        RAISE EXCEPTION 'maquina nao habilitada' USING ERRCODE = '23514';
    END IF;
    -- o Python base e a ESCOLHA vigente desta automacao nesta maquina e e uma INSTALACAO (D6)
    IF NOT EXISTS (SELECT 1 FROM public.automation_python_selections s
                     JOIN public.agent_python_environments p ON p.agent_id = s.agent_id AND p.environment_id = s.environment_id AND p.kind = 1
                    WHERE s.organization_id = NEW.organization_id AND s.automation_id = NEW.automation_id AND s.agent_id = NEW.agent_id
                      AND s.workspace_id = NEW.workspace_id AND s.environment_id = NEW.environment_id) THEN
        RAISE EXCEPTION 'o Python base e a instalacao escolhida para esta automacao nesta maquina' USING ERRCODE = '23514';
    END IF;
    IF NEW.requirements_revision IS DISTINCT FROM (SELECT max(r.revision_number) FROM public.automation_runtime_requirements r
                                                    WHERE r.organization_id = NEW.organization_id AND r.automation_id = NEW.automation_id) THEN
        RAISE EXCEPTION 'a resolucao usa a revisao vigente das dependencias' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.automation_versions v WHERE v.organization_id = NEW.organization_id
                      AND v.automation_version_id = NEW.automation_version_id AND v.status = 2) THEN
        RAISE EXCEPTION 'so versao publicada' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM public.managed_environment_resolutions c
                WHERE c.organization_id = NEW.organization_id AND c.automation_id = NEW.automation_id AND c.agent_id = NEW.agent_id
                  AND c.outcome IS NULL AND c.answer_valid_until > NEW.requested_at) THEN
        RAISE EXCEPTION 'MANAGED_ENV_RESOLUTION_PENDING' USING ERRCODE = '23P01';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_managed_env_resolutions_on_insert BEFORE INSERT ON public.managed_environment_resolutions
    FOR EACH ROW EXECUTE FUNCTION public.managed_env_resolution_on_insert();

CREATE FUNCTION public.managed_env_resolution_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    answer CONSTANT TEXT[] := ARRAY['delivered_at', 'outcome', 'python_version', 'pip_version', 'lock', 'lock_sha256', 'failure_requirement',
                                     'failure_phase', 'failure_summary', 'answered_at'];
BEGIN
    IF (to_jsonb(NEW) - answer) IS DISTINCT FROM (to_jsonb(OLD) - answer) THEN
        RAISE EXCEPTION 'o pedido de resolucao nao muda' USING ERRCODE = '42501';
    END IF;
    IF OLD.outcome IS NOT NULL THEN
        RAISE EXCEPTION 'resolucao ja respondida' USING ERRCODE = '23514';
    END IF;
    IF OLD.delivered_at IS NOT NULL THEN
        NEW.delivered_at := OLD.delivered_at;
    ELSIF NEW.delivered_at IS NOT NULL THEN
        NEW.delivered_at := clock_timestamp();
    END IF;
    IF NEW.outcome IS NOT NULL THEN
        IF OLD.answer_valid_until <= clock_timestamp() THEN
            RAISE EXCEPTION 'o prazo da resposta venceu' USING ERRCODE = '23514';
        END IF;
        NEW.answered_at := clock_timestamp();
        NEW.delivered_at := coalesce(NEW.delivered_at, NEW.answered_at);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_managed_env_resolutions_on_update BEFORE UPDATE ON public.managed_environment_resolutions
    FOR EACH ROW EXECUTE FUNCTION public.managed_env_resolution_on_update();
CREATE TRIGGER trg_managed_env_resolutions_prevent_delete BEFORE DELETE ON public.managed_environment_resolutions
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_managed_env_resolutions_prevent_truncate BEFORE TRUNCATE ON public.managed_environment_resolutions
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 3. Aceite ===
CREATE TABLE public.managed_environment_authorizations (
    authorization_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             UUID NOT NULL,
    workspace_id                UUID NOT NULL,
    automation_id               UUID NOT NULL,
    automation_version_id       UUID NOT NULL,
    agent_id                    UUID NOT NULL,
    worker_id                   UUID NOT NULL,
    environment_id              TEXT NOT NULL CHECK (environment_id ~ '^[A-Za-z0-9_-]{43}$'),
    requirements_revision       INTEGER NOT NULL,
    resolution_id               UUID NOT NULL,
    lock_sha256                 TEXT NOT NULL CHECK (lock_sha256 ~ '^[0-9a-f]{64}$'),
    checks                      JSONB NOT NULL CHECK (jsonb_typeof(checks) = 'array' AND jsonb_array_length(checks) <= 8),
    granted_by_membership_id    UUID NOT NULL,
    granted_at                  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    revoked_at                  TIMESTAMPTZ,
    revoked_by_membership_id    UUID,
    revocation_reason           TEXT CHECK (revocation_reason IN ('REPLACED', 'BACK_TO_PREPARED')),
    UNIQUE (organization_id, authorization_id),
    FOREIGN KEY (organization_id, resolution_id) REFERENCES public.managed_environment_resolutions (organization_id, resolution_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, agent_id) REFERENCES public.agents (organization_id, agent_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, worker_id) REFERENCES public.workers (organization_id, worker_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, granted_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, revoked_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK ((revoked_at IS NULL) = (revocation_reason IS NULL))
);
CREATE UNIQUE INDEX ux_managed_env_authorizations_current ON public.managed_environment_authorizations (automation_version_id, agent_id)
    WHERE revoked_at IS NULL;
COMMENT ON TABLE public.managed_environment_authorizations IS
    'Aceite do ambiente por execucao (076): versao + maquina + Python base + revisao + lock (sha256) + verificacoes externas. Um '
    'vigente por versao e maquina. Lock, revisao ou Python base diferentes do aceito: a solicitacao MANAGED e recusada.';

CREATE FUNCTION public.managed_env_authorization_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.granted_at := clock_timestamp();
    IF NEW.revoked_at IS NOT NULL OR NEW.revoked_by_membership_id IS NOT NULL OR NEW.revocation_reason IS NOT NULL THEN
        RAISE EXCEPTION 'o aceite nasce vigente' USING ERRCODE = '23514';
    END IF;
    IF NOT public.agent_workspace_permission(NEW.organization_id, NEW.granted_by_membership_id, NEW.workspace_id, 'automation.manage') THEN
        RAISE EXCEPTION 'sem automation.manage no ambiente' USING ERRCODE = '42501';
    END IF;
    -- o aceite e EXATAMENTE o que a maquina resolveu para esta versao, maquina, Python base e revisao
    IF NOT EXISTS (SELECT 1 FROM public.managed_environment_resolutions r
                    WHERE r.organization_id = NEW.organization_id AND r.resolution_id = NEW.resolution_id AND r.outcome = 1
                      AND r.workspace_id = NEW.workspace_id AND r.automation_id = NEW.automation_id
                      AND r.automation_version_id = NEW.automation_version_id AND r.agent_id = NEW.agent_id
                      AND r.environment_id = NEW.environment_id AND r.requirements_revision = NEW.requirements_revision
                      AND r.lock_sha256 = NEW.lock_sha256) THEN
        RAISE EXCEPTION 'o aceite e o da resolucao respondida' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.worker_agent_assignments wa
                    WHERE wa.organization_id = NEW.organization_id AND wa.agent_id = NEW.agent_id AND wa.worker_id = NEW.worker_id AND wa.status = 1) THEN
        RAISE EXCEPTION 'o Worker e o da maquina' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_managed_env_authorizations_on_insert BEFORE INSERT ON public.managed_environment_authorizations
    FOR EACH ROW EXECUTE FUNCTION public.managed_env_authorization_on_insert();

CREATE FUNCTION public.managed_env_authorization_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF (to_jsonb(NEW) - 'revoked_at' - 'revoked_by_membership_id' - 'revocation_reason')
       IS DISTINCT FROM (to_jsonb(OLD) - 'revoked_at' - 'revoked_by_membership_id' - 'revocation_reason') THEN
        RAISE EXCEPTION 'o aceite nao muda; so e revogado' USING ERRCODE = '42501';
    END IF;
    IF OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'aceite ja revogado' USING ERRCODE = '23514';
    END IF;
    IF NEW.revoked_at IS NULL OR NEW.revocation_reason IS NULL THEN
        RAISE EXCEPTION 'revogacao incompleta' USING ERRCODE = '23514';
    END IF;
    NEW.revoked_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_managed_env_authorizations_on_update BEFORE UPDATE ON public.managed_environment_authorizations
    FOR EACH ROW EXECUTE FUNCTION public.managed_env_authorization_on_update();
CREATE TRIGGER trg_managed_env_authorizations_prevent_delete BEFORE DELETE ON public.managed_environment_authorizations
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_managed_env_authorizations_prevent_truncate BEFORE TRUNCATE ON public.managed_environment_authorizations
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 4. Solicitacao: o modo ===
ALTER TABLE public.remote_executions ADD COLUMN environment_mode SMALLINT NOT NULL DEFAULT 1 CHECK (environment_mode IN (1, 2));
COMMENT ON COLUMN public.remote_executions.environment_mode IS '1=PREPARED (Python escolhido, como antes da 076; padrao), 2=MANAGED (ambiente por execucao)';

-- === 5. Tentativa: retrato do aceite, etapas e relatorio ===
CREATE TABLE public.remote_attempt_environments (
    attempt_id                  UUID PRIMARY KEY,
    organization_id             UUID NOT NULL,
    authorization_id            UUID NOT NULL,
    lock                        JSONB NOT NULL CHECK (jsonb_typeof(lock) = 'array' AND jsonb_array_length(lock) <= 400 AND octet_length(lock::text) <= 131072),
    lock_sha256                 TEXT NOT NULL CHECK (lock_sha256 ~ '^[0-9a-f]{64}$'),
    checks                      JSONB NOT NULL CHECK (jsonb_typeof(checks) = 'array' AND jsonb_array_length(checks) <= 8),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (organization_id, attempt_id),
    FOREIGN KEY (organization_id, attempt_id) REFERENCES public.remote_attempts (organization_id, attempt_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, authorization_id)
        REFERENCES public.managed_environment_authorizations (organization_id, authorization_id) ON DELETE RESTRICT
);

CREATE FUNCTION public.remote_attempt_environment_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.created_at := clock_timestamp();
    -- so tentativa recem-criada de solicitacao MANAGED, com o aceite VIGENTE desta versao e desta maquina, e o mesmo lock
    IF NOT EXISTS (SELECT 1 FROM public.remote_attempts t
                     JOIN public.remote_executions x ON x.organization_id = t.organization_id AND x.remote_execution_id = t.remote_execution_id
                     JOIN public.managed_environment_authorizations g ON g.organization_id = t.organization_id AND g.authorization_id = NEW.authorization_id
                                                                     AND g.automation_version_id = x.automation_version_id AND g.agent_id = t.agent_id
                                                                     AND g.revoked_at IS NULL AND g.lock_sha256 = NEW.lock_sha256
                    WHERE t.organization_id = NEW.organization_id AND t.attempt_id = NEW.attempt_id AND t.state = 1 AND x.environment_mode = 2) THEN
        RAISE EXCEPTION 'retrato do ambiente so para tentativa MANAGED nova com o aceite vigente' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempt_environments_on_insert BEFORE INSERT ON public.remote_attempt_environments
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_environment_on_insert();
CREATE TRIGGER trg_remote_attempt_environments_no_update BEFORE UPDATE ON public.remote_attempt_environments
    FOR EACH ROW EXECUTE FUNCTION public.prevent_update();
CREATE TRIGGER trg_remote_attempt_environments_prevent_delete BEFORE DELETE ON public.remote_attempt_environments
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_remote_attempt_environments_prevent_truncate BEFORE TRUNCATE ON public.remote_attempt_environments
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

CREATE TABLE public.remote_attempt_environment_stages (
    attempt_id                  UUID NOT NULL,
    stage                       SMALLINT NOT NULL CHECK (stage IN (1, 2)),
    organization_id             UUID NOT NULL,
    reached_at                  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (attempt_id, stage),
    FOREIGN KEY (organization_id, attempt_id) REFERENCES public.remote_attempt_environments (organization_id, attempt_id) ON DELETE RESTRICT
);
COMMENT ON COLUMN public.remote_attempt_environment_stages.stage IS '1=ENV_CREATING (Preparando ambiente), 2=DEPS_INSTALLING (Instalando dependencias)';

CREATE FUNCTION public.remote_attempt_environment_stage_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.reached_at := clock_timestamp();
    IF NOT EXISTS (SELECT 1 FROM public.remote_attempts t WHERE t.organization_id = NEW.organization_id AND t.attempt_id = NEW.attempt_id AND t.state = 4) THEN
        RAISE EXCEPTION 'etapa do preparo so com a tentativa em PREPARING' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempt_environment_stages_on_insert BEFORE INSERT ON public.remote_attempt_environment_stages
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_environment_stage_on_insert();
CREATE TRIGGER trg_remote_attempt_environment_stages_no_update BEFORE UPDATE ON public.remote_attempt_environment_stages
    FOR EACH ROW EXECUTE FUNCTION public.prevent_update();
CREATE TRIGGER trg_remote_attempt_environment_stages_prevent_delete BEFORE DELETE ON public.remote_attempt_environment_stages
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_remote_attempt_environment_stages_prevent_truncate BEFORE TRUNCATE ON public.remote_attempt_environment_stages
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

CREATE TABLE public.remote_attempt_environment_reports (
    attempt_id                  UUID PRIMARY KEY,
    organization_id             UUID NOT NULL,
    outcome                     SMALLINT NOT NULL CHECK (outcome IN (1, 2, 3)),
    python_version              TEXT CHECK (python_version ~ '^3\.[0-9]{1,3}\.[0-9]{1,3}[A-Za-z0-9.+-]{0,20}$'),
    pip_version                 TEXT CHECK (pip_version ~ '^[0-9][0-9A-Za-z.+-]{0,30}$'),
    installed                   JSONB NOT NULL CHECK (jsonb_typeof(installed) = 'array' AND jsonb_array_length(installed) <= 400
                                                      AND octet_length(installed::text) <= 131072),
    failure_requirement         TEXT CHECK (char_length(failure_requirement) BETWEEN 1 AND 200),
    failure_phase               TEXT CHECK (failure_phase IN ('VENV', 'DOWNLOAD', 'HASH_MISMATCH', 'NO_COMPATIBLE_WHEEL', 'INSTALL', 'VERIFY',
                                                              'PREREQUISITE', 'NO_SPACE', 'TIMEOUT', 'OTHER')),
    failure_summary             TEXT CHECK (char_length(failure_summary) <= 500),
    create_ms                   INTEGER CHECK (create_ms BETWEEN 0 AND 86400000),
    install_ms                  INTEGER CHECK (install_ms BETWEEN 0 AND 86400000),
    preparation_log             TEXT CHECK (octet_length(preparation_log) <= 65536),
    log_expired_at              TIMESTAMPTZ,
    reported_at                 TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (organization_id, attempt_id),
    FOREIGN KEY (organization_id, attempt_id) REFERENCES public.remote_attempt_environments (organization_id, attempt_id) ON DELETE RESTRICT,
    CHECK (outcome <> 2 OR failure_phase IS NOT NULL),
    CHECK (outcome = 2 OR (failure_phase IS NULL AND failure_requirement IS NULL)),
    CHECK (log_expired_at IS NULL OR preparation_log IS NULL)
);
CREATE INDEX ix_remote_attempt_environment_reports_log ON public.remote_attempt_environment_reports (reported_at) WHERE preparation_log IS NOT NULL;
COMMENT ON TABLE public.remote_attempt_environment_reports IS
    'Relatorio do preparo do ambiente por execucao (076): READY (com o que foi instalado de fato), FAILED (dependencia e fase; a '
    'automacao nao iniciou) ou INTERRUPTED (o Client reiniciou durante o preparo). O registro do preparo vale 30 dias (D7).';

CREATE FUNCTION public.remote_attempt_environment_report_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.reported_at := clock_timestamp();
    IF NEW.log_expired_at IS NOT NULL THEN
        RAISE EXCEPTION 'o relatorio nasce com o registro' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.remote_attempts t WHERE t.organization_id = NEW.organization_id AND t.attempt_id = NEW.attempt_id AND t.state >= 4) THEN
        RAISE EXCEPTION 'relatorio do preparo so depois do recebimento' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempt_environment_reports_on_insert BEFORE INSERT ON public.remote_attempt_environment_reports
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_environment_report_on_insert();

-- depois de gravado: so a retencao apaga o registro do preparo (D7)
CREATE FUNCTION public.remote_attempt_environment_report_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF (to_jsonb(NEW) - 'preparation_log' - 'log_expired_at') IS DISTINCT FROM (to_jsonb(OLD) - 'preparation_log' - 'log_expired_at')
       OR OLD.log_expired_at IS NOT NULL OR NEW.preparation_log IS NOT NULL THEN
        RAISE EXCEPTION 'o relatorio do preparo nao muda; so o registro e removido pela retencao' USING ERRCODE = '42501';
    END IF;
    IF OLD.reported_at > clock_timestamp() - interval '30 days' THEN
        RAISE EXCEPTION 'o registro do preparo vale 30 dias' USING ERRCODE = '23514';
    END IF;
    NEW.log_expired_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempt_environment_reports_on_update BEFORE UPDATE ON public.remote_attempt_environment_reports
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_environment_report_on_update();
CREATE TRIGGER trg_remote_attempt_environment_reports_prevent_delete BEFORE DELETE ON public.remote_attempt_environment_reports
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_remote_attempt_environment_reports_prevent_truncate BEFORE TRUNCATE ON public.remote_attempt_environment_reports
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 6b. Presenca: a capacidade nova (o aplicativo so a declara depois de a presenca anunciar o recurso) ===
ALTER TABLE public.agent_presence DROP CONSTRAINT agent_presence_capabilities_check;
ALTER TABLE public.agent_presence ADD CONSTRAINT agent_presence_capabilities_check CHECK (cardinality(capabilities) <= 12
    AND capabilities <@ ARRAY['RUN_LOCAL', 'OBS_1_0', 'SUPERVISOR_REPORT_1_0', 'EXTERNAL_PYTHON_1_0', 'RUN_REQUEST_1_0',
                              'REMOTE_RUN_1_0', 'REMOTE_LOGS_1_0', 'REMOTE_RESULTS_1_0', 'PY_INVENTORY_1_0', 'MANAGED_ENV_1_0']::text[]);

-- === 6a. Trilha da automacao: 13 = ambiente por execucao aceito, 14 = aceite revogado (volta ao Python preparado) ===
ALTER TABLE public.automation_admin_events DROP CONSTRAINT automation_admin_events_act_check;
ALTER TABLE public.automation_admin_events ADD CONSTRAINT automation_admin_events_act_check CHECK (act BETWEEN 1 AND 14);

-- === 6. Motivos de recusa da maquina ===
ALTER TABLE public.remote_attempts DROP CONSTRAINT remote_attempts_refusal_reason_check;
ALTER TABLE public.remote_attempts ADD CONSTRAINT remote_attempts_refusal_reason_check CHECK (refusal_reason IN (
    'BUSY', 'PAUSED', 'PACKAGE_INVALID', 'ENVIRONMENT_GONE', 'ENVIRONMENT_INCOMPATIBLE', 'DEPENDENCIES_MISSING', 'DISK_PRESSURE',
    'CLIENT_INCOMPATIBLE', 'SESSION_UNAVAILABLE', 'AUTHORIZATION_MISSING', 'AUTHORIZATION_REVOKED', 'AUTHORIZATION_EXPIRED',
    'WORKER_NOT_AUTHORIZED', 'MACHINE_NOT_LINKED', 'MACHINE_NOT_ENABLED', 'MACHINE_INCOMPATIBLE', 'COMMUNICATION_LOST', 'LICENSE_REQUIRED',
    'LICENSE_EXPIRED', 'ORGANIZATION_HELD', 'ENVIRONMENT_SETUP_FAILED', 'DEPENDENCY_INSTALL_FAILED', 'ENVIRONMENT_SETUP_INTERRUPTED',
    'EXTERNAL_PREREQUISITE_MISSING'));

-- === 7. RLS ===
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['managed_environment_resolutions', 'managed_environment_authorizations', 'remote_attempt_environments',
                             'remote_attempt_environment_stages', 'remote_attempt_environment_reports'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_request_context ON public.%I AS PERMISSIVE FOR ALL TO nekkora_platform, nekkora_ingestion '
                       'USING (organization_id = NULLIF(pg_catalog.current_setting(''nekkora.organization_id'', true), '''')::uuid) '
                       'WITH CHECK (organization_id = NULLIF(pg_catalog.current_setting(''nekkora.organization_id'', true), '''')::uuid)', t);
        EXECUTE format('REVOKE ALL ON public.%I FROM PUBLIC', t);
    END LOOP;
END;
$$;

-- === 8. Privilegios ===
-- Pessoas (Platform): pedem a resolucao, aceitam e revogam, criam o retrato da tentativa, leem tudo e apagam o registro do
-- preparo vencido. Maquina (gateway): le o que e dela, responde a resolucao, informa etapas e grava o relatorio. Ninguem apaga linha.
REVOKE ALL ON FUNCTION public.managed_env_resolution_on_insert(), public.managed_env_resolution_on_update(),
    public.managed_env_authorization_on_insert(), public.managed_env_authorization_on_update(), public.remote_attempt_environment_on_insert(),
    public.remote_attempt_environment_stage_on_insert(), public.remote_attempt_environment_report_on_insert(),
    public.remote_attempt_environment_report_on_update() FROM PUBLIC;
GRANT SELECT, INSERT ON public.managed_environment_resolutions TO nekkora_platform;
GRANT SELECT ON public.managed_environment_resolutions TO nekkora_ingestion;
GRANT UPDATE (delivered_at, outcome, python_version, pip_version, lock, lock_sha256, failure_requirement, failure_phase, failure_summary, answered_at)
    ON public.managed_environment_resolutions TO nekkora_ingestion;
GRANT SELECT, INSERT ON public.managed_environment_authorizations TO nekkora_platform;
GRANT UPDATE (revoked_at, revoked_by_membership_id, revocation_reason) ON public.managed_environment_authorizations TO nekkora_platform;
GRANT SELECT ON public.managed_environment_authorizations TO nekkora_ingestion;
GRANT SELECT, INSERT ON public.remote_attempt_environments TO nekkora_platform;
GRANT SELECT ON public.remote_attempt_environments TO nekkora_ingestion;
GRANT SELECT ON public.remote_attempt_environment_stages TO nekkora_platform;
GRANT SELECT, INSERT ON public.remote_attempt_environment_stages TO nekkora_ingestion;
GRANT SELECT ON public.remote_attempt_environment_reports TO nekkora_platform;
GRANT UPDATE (preparation_log, log_expired_at) ON public.remote_attempt_environment_reports TO nekkora_platform;
GRANT SELECT, INSERT ON public.remote_attempt_environment_reports TO nekkora_ingestion;

-- === 9. Invariantes ===
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['managed_environment_resolutions', 'managed_environment_authorizations', 'remote_attempt_environments',
                             'remote_attempt_environment_stages', 'remote_attempt_environment_reports'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = ('public.' || t)::regclass AND c.relrowsecurity AND c.relforcerowsecurity) THEN
            RAISE EXCEPTION '076: RLS ausente em %', t;
        END IF;
        IF has_table_privilege('nekkora_platform', 'public.' || t, 'DELETE') OR has_table_privilege('nekkora_ingestion', 'public.' || t, 'DELETE') THEN
            RAISE EXCEPTION '076: DELETE previsto em %', t;
        END IF;
    END LOOP;
    IF has_table_privilege('nekkora_ingestion', 'public.managed_environment_authorizations', 'INSERT')
       OR has_table_privilege('nekkora_ingestion', 'public.managed_environment_resolutions', 'INSERT')
       OR has_table_privilege('nekkora_ingestion', 'public.remote_attempt_environments', 'INSERT')
       OR has_column_privilege('nekkora_platform', 'public.managed_environment_resolutions', 'lock', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.managed_environment_resolutions', 'outcome', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.remote_attempt_environment_reports', 'INSERT')
       OR has_table_privilege('nekkora_platform', 'public.remote_attempt_environment_stages', 'INSERT')
       OR has_column_privilege('nekkora_ingestion', 'public.managed_environment_resolutions', 'dependencies', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.remote_attempt_environment_reports', 'installed', 'UPDATE') THEN
        RAISE EXCEPTION '076: privilegio alem do previsto no ambiente por execucao';
    END IF;
END;
$$;

COMMIT;
