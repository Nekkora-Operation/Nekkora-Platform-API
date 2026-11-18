-- Nekkora Platform - verificacao, sob demanda, das dependencias de uma automacao no Python escolhido de uma maquina
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, plano T00 secao 5.9). Contrato: docs/platform-client-execucao-remota-001/verificacao-sob-demanda.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 042 (habilitacao), da 044 (inventario), da 045 (dependencias declaradas) e da 047
-- (escolha do Python). Numero 054 conferido em 16/11 contra a dev e os PRs abertos.
--
-- O QUE FAZ
--   remote_dependency_checks: um PEDIDO de verificacao, feito por uma pessoa, e a RESPOSTA da maquina. O pedido congela o que
--   sera conferido: o ambiente Python escolhido naquele momento (identificador opaco) e a lista efetiva das dependencias, com a
--   revisao. A maquina confere SO isso, naquele ambiente, e responde uma vez:
--     1 = SATISFIED         todas presentes, na versao exata
--     2 = MISSING           faltam (ou estao em outra versao) as que vao em missing
--     3 = ENVIRONMENT_GONE  o ambiente escolhido nao existe mais na maquina
--     4 = NOT_VERIFIED      a maquina nao conseguiu conferir (o Python nao respondeu, por exemplo)
--   Sem resposta dentro do prazo (2 minutos), o pedido fica SEM RESPOSTA: a tela diz "nao foi possivel verificar", nunca
--   "incompativel".
--
-- LIMITE: no maximo um pedido por minuto por maquina e automacao (o gatilho recusa o segundo). A verificacao faz a maquina
-- trabalhar; o limite impede que ela seja usada para ocupa-la.
--
-- O QUE NAO GUARDA: caminho, nome de pasta, lista dos pacotes instalados. So o que FALTA do que foi perguntado.
--
-- O QUE NAO FAZ: nao instala nada, nao escolhe ambiente, nao muda a escolha do Python nem as condicoes da solicitacao. A
-- conferencia que VALE para executar continua sendo a da maquina, imediatamente antes de iniciar: esta aqui e so informacao.
--
-- DADOS EXISTENTES: nenhuma linha e alterada. A tabela nasce vazia.
--
-- REVERSAO (manual):
--   BEGIN;
--   DROP TABLE public.remote_dependency_checks;
--   DROP FUNCTION public.remote_dependency_check_on_insert();
--   DROP FUNCTION public.remote_dependency_check_on_update();
--   COMMIT;
--
-- SEM ELEVACAO: tabela nova, gatilhos SECURITY INVOKER. Nenhum papel novo, nenhuma funcao SECURITY DEFINER.

BEGIN;

-- === 1. Pre-condicao ===
DO $$
BEGIN
    IF to_regclass('public.automation_python_selections') IS NULL OR to_regclass('public.automation_runtime_requirements') IS NULL
       OR to_regclass('public.agent_remote_enablements') IS NULL THEN
        RAISE EXCEPTION '054 exige a 042, a 045 e a 047 aplicadas';
    END IF;
    IF to_regclass('public.remote_dependency_checks') IS NOT NULL THEN
        RAISE EXCEPTION '054: remote_dependency_checks ja existe';
    END IF;
END;
$$;

-- === 2. Tabela ===
CREATE TABLE public.remote_dependency_checks (
    check_id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             UUID NOT NULL,
    workspace_id                UUID NOT NULL,
    automation_id               UUID NOT NULL,
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
    missing                     JSONB CHECK (missing IS NULL OR (jsonb_typeof(missing) = 'array' AND jsonb_array_length(missing) BETWEEN 1 AND 400
                                                                 AND octet_length(missing::text) <= 32768)),
    answered_at                 TIMESTAMPTZ,
    UNIQUE (organization_id, check_id),
    FOREIGN KEY (organization_id, agent_id) REFERENCES public.agents (organization_id, agent_id) ON DELETE RESTRICT,
    FOREIGN KEY (automation_id, requirements_revision)
        REFERENCES public.automation_runtime_requirements (automation_id, revision_number) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, requested_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK ((outcome IS NULL) = (answered_at IS NULL)),
    CHECK ((outcome = 2) = (missing IS NOT NULL)),
    CHECK (answer_valid_until > requested_at AND answer_valid_until <= requested_at + interval '10 minutes')
);
CREATE INDEX ix_remote_dependency_checks_latest ON public.remote_dependency_checks (organization_id, automation_id, agent_id, requested_at DESC);
CREATE INDEX ix_remote_dependency_checks_open ON public.remote_dependency_checks (agent_id, requested_at) WHERE outcome IS NULL;
COMMENT ON TABLE public.remote_dependency_checks IS
    'Verificacao sob demanda (054): uma pessoa pede, a maquina confere so as dependencias daquela automacao no Python escolhido e '
    'responde uma vez. Sem resposta no prazo: nao foi possivel verificar. E informacao; a conferencia que vale e a de antes de iniciar.';
COMMENT ON COLUMN public.remote_dependency_checks.outcome IS '1=SATISFIED, 2=MISSING, 3=ENVIRONMENT_GONE, 4=NOT_VERIFIED; nulo = sem resposta (ainda, ou para sempre)';

-- === 3. Regras ===
CREATE FUNCTION public.remote_dependency_check_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.requested_at := clock_timestamp();
    IF NEW.answer_valid_until IS NULL OR NEW.answer_valid_until <= NEW.requested_at OR NEW.answer_valid_until > NEW.requested_at + interval '10 minutes' THEN
        RAISE EXCEPTION 'prazo da resposta fora do permitido' USING ERRCODE = '23514';
    END IF;
    IF NEW.delivered_at IS NOT NULL OR NEW.outcome IS NOT NULL OR NEW.missing IS NOT NULL OR NEW.answered_at IS NOT NULL THEN
        RAISE EXCEPTION 'a verificacao nasce sem entrega e sem resposta' USING ERRCODE = '23514';
    END IF;
    -- quem pede pode mandar esta maquina executar esta automacao (automation.execute no ambiente)
    IF NOT public.agent_workspace_permission(NEW.organization_id, NEW.requested_by_membership_id, NEW.workspace_id, 'automation.execute') THEN
        RAISE EXCEPTION 'sem automation.execute no ambiente' USING ERRCODE = '42501';
    END IF;
    -- a maquina esta habilitada, e o que sera conferido e a ESCOLHA vigente e a revisao VIGENTE das dependencias
    IF NOT EXISTS (SELECT 1 FROM public.agent_remote_enablements e
                     JOIN public.agents a ON a.organization_id = e.organization_id AND a.agent_id = e.agent_id AND a.status = 2
                    WHERE e.organization_id = NEW.organization_id AND e.agent_id = NEW.agent_id) THEN
        RAISE EXCEPTION 'maquina nao habilitada' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.automation_python_selections s
                    WHERE s.organization_id = NEW.organization_id AND s.automation_id = NEW.automation_id AND s.agent_id = NEW.agent_id
                      AND s.workspace_id = NEW.workspace_id AND s.environment_id = NEW.environment_id) THEN
        RAISE EXCEPTION 'o ambiente conferido e o escolhido para esta automacao nesta maquina' USING ERRCODE = '23514';
    END IF;
    IF NEW.requirements_revision IS DISTINCT FROM (SELECT max(r.revision_number) FROM public.automation_runtime_requirements r
                                                    WHERE r.organization_id = NEW.organization_id AND r.automation_id = NEW.automation_id) THEN
        RAISE EXCEPTION 'a verificacao usa a revisao vigente das dependencias' USING ERRCODE = '23514';
    END IF;
    -- limite: uma por minuto por maquina e automacao
    IF EXISTS (SELECT 1 FROM public.remote_dependency_checks c
                WHERE c.organization_id = NEW.organization_id AND c.automation_id = NEW.automation_id AND c.agent_id = NEW.agent_id
                  AND c.requested_at > NEW.requested_at - interval '60 seconds') THEN
        RAISE EXCEPTION 'DEPENDENCY_CHECK_TOO_SOON' USING ERRCODE = '23P01';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_dependency_checks_on_insert BEFORE INSERT ON public.remote_dependency_checks
    FOR EACH ROW EXECUTE FUNCTION public.remote_dependency_check_on_insert();

-- Depois de criado: a entrega e marcada uma vez; a resposta e gravada uma vez, dentro do prazo. O pedido nao muda.
CREATE FUNCTION public.remote_dependency_check_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF (to_jsonb(NEW) - 'delivered_at' - 'outcome' - 'missing' - 'answered_at') IS DISTINCT FROM (to_jsonb(OLD) - 'delivered_at' - 'outcome' - 'missing' - 'answered_at') THEN
        RAISE EXCEPTION 'o pedido de verificacao nao muda' USING ERRCODE = '42501';
    END IF;
    IF OLD.outcome IS NOT NULL THEN
        RAISE EXCEPTION 'verificacao ja respondida' USING ERRCODE = '23514';
    END IF;
    IF OLD.delivered_at IS NOT NULL AND NEW.delivered_at IS DISTINCT FROM OLD.delivered_at THEN
        NEW.delivered_at := OLD.delivered_at;
    ELSIF NEW.delivered_at IS NOT NULL AND OLD.delivered_at IS NULL THEN
        NEW.delivered_at := clock_timestamp();
    END IF;
    IF NEW.outcome IS NOT NULL THEN
        IF OLD.answer_valid_until <= clock_timestamp() THEN
            RAISE EXCEPTION 'o prazo da resposta venceu' USING ERRCODE = '23514';
        END IF;
        NEW.answered_at := clock_timestamp();
        IF NEW.delivered_at IS NULL THEN
            NEW.delivered_at := NEW.answered_at;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_dependency_checks_on_update BEFORE UPDATE ON public.remote_dependency_checks
    FOR EACH ROW EXECUTE FUNCTION public.remote_dependency_check_on_update();
CREATE TRIGGER trg_remote_dependency_checks_prevent_delete BEFORE DELETE ON public.remote_dependency_checks
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_remote_dependency_checks_prevent_truncate BEFORE TRUNCATE ON public.remote_dependency_checks
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 4. RLS ===
ALTER TABLE public.remote_dependency_checks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.remote_dependency_checks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_request_context ON public.remote_dependency_checks AS PERMISSIVE FOR ALL TO nekkora_platform, nekkora_ingestion
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid);

-- === 5. Privilegios ===
-- Pessoas: pedem e leem. Maquina: le o que e dela e responde. Ninguem apaga.
REVOKE ALL ON public.remote_dependency_checks FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_dependency_check_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_dependency_check_on_update() FROM PUBLIC;
GRANT SELECT, INSERT ON public.remote_dependency_checks TO nekkora_platform;
GRANT SELECT ON public.remote_dependency_checks TO nekkora_ingestion;
GRANT UPDATE (delivered_at, outcome, missing, answered_at) ON public.remote_dependency_checks TO nekkora_ingestion;

-- === 6. Invariantes ===
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = 'public.remote_dependency_checks'::regclass AND c.relrowsecurity AND c.relforcerowsecurity) THEN
        RAISE EXCEPTION '054: RLS ausente';
    END IF;
    IF has_table_privilege('nekkora_ingestion', 'public.remote_dependency_checks', 'INSERT')
       OR has_table_privilege('nekkora_ingestion', 'public.remote_dependency_checks', 'DELETE')
       OR has_table_privilege('nekkora_platform', 'public.remote_dependency_checks', 'DELETE')
       OR has_table_privilege('nekkora_platform', 'public.remote_dependency_checks', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.remote_dependency_checks', 'outcome', 'UPDATE')
       OR has_column_privilege('nekkora_ingestion', 'public.remote_dependency_checks', 'dependencies', 'UPDATE') THEN
        RAISE EXCEPTION '054: privilegio alem do previsto na verificacao sob demanda';
    END IF;
END;
$$;

COMMIT;
