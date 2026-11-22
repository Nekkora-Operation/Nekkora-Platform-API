-- Nekkora Platform - escolha do Python de uma automacao numa maquina habilitada para execucao remota
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01c, parte 3). Contrato: docs/platform-client-execucao-remota-001/t01c-escolha-do-python.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 044 (inventario), da 045 (trilha da automacao) e da 046 (tentativas). Numero 047
-- conferido em 15/11 contra a dev e os PRs abertos. As ferramentas do laboratorio e da homologacao exigem numeracao contigua:
-- se outra 047 entrar antes, esta e renumerada sem mudar o conteudo.
--
-- O QUE FAZ
--   1. automation_python_selections: para UMA automacao NUMA maquina, qual ambiente Python do inventario dela sera usado.
--      Guarda so o identificador opaco e a versao que o inventario dizia no momento. Uma escolha por automacao e maquina; trocar
--      substitui. A escolha de uma maquina nao vale para outra.
--   2. remote_attempts.python_environment_id: o retrato da escolha no momento em que a tentativa nasce. Sem escolha, a
--      tentativa nao nasce. O retrato nao muda depois.
--   3. Ato 11 na trilha da automacao: "Python escolhido para uma maquina".
--
-- O QUE NAO GUARDA: caminho, nome de pasta, lista de pacotes. A Platform nunca envia um caminho a maquina: envia o identificador
-- que a propria maquina publicou.
--
-- O QUE NAO FAZ: nao verifica se as dependencias estao instaladas (quem confere e a maquina, antes de iniciar); nao instala
-- nada; nao escolhe sozinha um ambiente, nem troca por outro se o escolhido sumir.
--
-- DADOS EXISTENTES: as tentativas que ja existem ficam com python_environment_id nulo (nenhuma delas iniciou: na 046 nao havia
-- execucao). Nenhuma outra linha e alterada.
--
-- REVERSAO (manual; so enquanto nenhuma tentativa tiver o retrato preenchido e a trilha nao tiver ato 11):
--   BEGIN;
--   DROP TRIGGER trg_remote_attempts_python_on_insert ON public.remote_attempts;
--   DROP TRIGGER trg_remote_attempts_python_on_update ON public.remote_attempts;
--   DROP FUNCTION public.remote_attempt_python_on_insert();
--   DROP FUNCTION public.remote_attempt_python_on_update();
--   ALTER TABLE public.remote_attempts DROP COLUMN python_environment_id;
--   DROP TABLE public.automation_python_selections;
--   DROP FUNCTION public.automation_python_selection_on_insert();
--   ALTER TABLE public.automation_admin_events DROP CONSTRAINT automation_admin_events_act_check;
--   ALTER TABLE public.automation_admin_events ADD CONSTRAINT automation_admin_events_act_check CHECK (act BETWEEN 1 AND 10);
--   COMMIT;
--
-- SEM ELEVACAO: tabela nova, coluna nova e gatilhos SECURITY INVOKER. Nenhum papel novo, nenhuma funcao SECURITY DEFINER.

BEGIN;

-- === 1. Pre-condicao ===
DO $$
BEGIN
    IF to_regclass('public.agent_python_environments') IS NULL OR to_regclass('public.remote_attempts') IS NULL
       OR to_regclass('public.automation_runtime_requirements') IS NULL THEN
        RAISE EXCEPTION '047 exige a 044, a 045 e a 046 aplicadas';
    END IF;
    IF to_regclass('public.automation_python_selections') IS NOT NULL THEN
        RAISE EXCEPTION '047: automation_python_selections ja existe';
    END IF;
END;
$$;

-- === 2. Escolha ===
CREATE TABLE public.automation_python_selections (
    automation_id              UUID NOT NULL,
    agent_id                   UUID NOT NULL,
    organization_id            UUID NOT NULL,
    workspace_id               UUID NOT NULL,
    environment_id             TEXT NOT NULL CHECK (environment_id ~ '^[A-Za-z0-9_-]{43}$'),
    python_version             TEXT NOT NULL CHECK (python_version ~ '^3\.[0-9]{1,2}\.[0-9]{1,3}([abc]|rc)?[0-9]{0,2}$'),
    selected_by_membership_id  UUID NOT NULL,
    selected_at                TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (automation_id, agent_id),
    FOREIGN KEY (organization_id, workspace_id, automation_id) REFERENCES public.automations (organization_id, workspace_id, automation_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, agent_id) REFERENCES public.agents (organization_id, agent_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, selected_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT
);
COMMENT ON TABLE public.automation_python_selections IS
    'Ambiente Python escolhido para uma automacao numa maquina habilitada (047). So o identificador opaco que a maquina publicou e a '
    'versao que o inventario dizia. Vale so para aquela maquina; trocar substitui a linha.';
COMMENT ON COLUMN public.automation_python_selections.python_version IS
    'A versao que o inventario da maquina dizia no momento da escolha. Para exibir; quem confere o ambiente antes de iniciar e a maquina.';

-- Quem escolhe tem automation.manage no ambiente; o ambiente escolhido esta no inventario ATUAL da maquina; a maquina esta
-- habilitada e o Worker dela esta autorizado no ambiente da automacao.
CREATE FUNCTION public.automation_python_selection_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_version TEXT;
BEGIN
    NEW.selected_at := clock_timestamp();
    IF NOT public.agent_workspace_permission(NEW.organization_id, NEW.selected_by_membership_id, NEW.workspace_id, 'automation.manage') THEN
        RAISE EXCEPTION 'sem automation.manage no ambiente da automacao' USING ERRCODE = '42501';
    END IF;
    SELECT e.python_version INTO v_version FROM public.agent_python_environments e
     WHERE e.organization_id = NEW.organization_id AND e.agent_id = NEW.agent_id AND e.environment_id = NEW.environment_id;
    IF v_version IS NULL THEN
        RAISE EXCEPTION 'o ambiente escolhido nao esta no inventario da maquina' USING ERRCODE = '23514';
    END IF;
    NEW.python_version := v_version;
    IF NOT EXISTS (SELECT 1 FROM public.agents a
                     JOIN public.agent_remote_enablements r ON r.organization_id = a.organization_id AND r.agent_id = a.agent_id
                     JOIN public.worker_agent_assignments wa ON wa.organization_id = a.organization_id AND wa.agent_id = a.agent_id AND wa.status = 1
                     JOIN public.worker_workspace_assignments ww ON ww.organization_id = wa.organization_id AND ww.worker_id = wa.worker_id
                                                                 AND ww.workspace_id = NEW.workspace_id AND ww.status = 1
                    WHERE a.organization_id = NEW.organization_id AND a.agent_id = NEW.agent_id AND a.status = 2) THEN
        RAISE EXCEPTION 'a maquina nao esta habilitada ou o Worker dela nao esta autorizado no ambiente' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_automation_python_selections_on_insert BEFORE INSERT ON public.automation_python_selections
    FOR EACH ROW EXECUTE FUNCTION public.automation_python_selection_on_insert();
CREATE TRIGGER trg_automation_python_selections_prevent_update BEFORE UPDATE ON public.automation_python_selections
    FOR EACH ROW EXECUTE FUNCTION public.prevent_update();
CREATE TRIGGER trg_automation_python_selections_prevent_truncate BEFORE TRUNCATE ON public.automation_python_selections
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 3. O retrato da escolha na tentativa ===
ALTER TABLE public.remote_attempts ADD COLUMN python_environment_id TEXT CHECK (python_environment_id ~ '^[A-Za-z0-9_-]{43}$');
COMMENT ON COLUMN public.remote_attempts.python_environment_id IS
    'Retrato do ambiente Python escolhido para a automacao NESTA maquina quando a tentativa nasceu (047). Identificador opaco da maquina; '
    'nulo so nas tentativas anteriores a 047. A maquina confere o ambiente antes de iniciar.';

-- Sem escolha, a tentativa nao nasce. O retrato vem da escolha, nunca de quem insere.
CREATE FUNCTION public.remote_attempt_python_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    SELECT s.environment_id INTO NEW.python_environment_id
      FROM public.remote_executions x
      JOIN public.automation_python_selections s ON s.organization_id = x.organization_id AND s.automation_id = x.automation_id AND s.agent_id = NEW.agent_id
     WHERE x.organization_id = NEW.organization_id AND x.remote_execution_id = NEW.remote_execution_id;
    IF NEW.python_environment_id IS NULL THEN
        RAISE EXCEPTION 'tentativa recusada: PYTHON_NOT_SELECTED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempts_python_on_insert BEFORE INSERT ON public.remote_attempts
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_python_on_insert();

CREATE FUNCTION public.remote_attempt_python_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.python_environment_id IS DISTINCT FROM OLD.python_environment_id THEN
        RAISE EXCEPTION 'o ambiente Python da tentativa nao muda' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempts_python_on_update BEFORE UPDATE ON public.remote_attempts
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_python_on_update();

-- === 4. Trilha da automacao: ato 11 ===
ALTER TABLE public.automation_admin_events DROP CONSTRAINT automation_admin_events_act_check;
ALTER TABLE public.automation_admin_events ADD CONSTRAINT automation_admin_events_act_check CHECK (act BETWEEN 1 AND 11);
COMMENT ON COLUMN public.automation_admin_events.act IS
    '1..9 conforme as migrations anteriores; 10=dependencias de execucao declaradas (045); 11=Python escolhido para uma maquina (047)';

-- === 5. RLS (padrao da 025/030/035/042) ===
DO $$
DECLARE
    ctx_req CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.organization_id'', true), '''')::uuid';
BEGIN
    ALTER TABLE public.automation_python_selections ENABLE ROW LEVEL SECURITY;
    ALTER TABLE public.automation_python_selections FORCE ROW LEVEL SECURITY;
    EXECUTE format('CREATE POLICY tenant_request_context ON public.automation_python_selections AS PERMISSIVE FOR ALL TO nekkora_platform, nekkora_ingestion USING (%s) WITH CHECK (%s)',
                   ctx_req, ctx_req);
END;
$$;

-- === 6. Privilegios: as pessoas escolhem (apagam e inserem); a maquina nao le nem escreve a escolha (recebe o retrato na oferta) ===
REVOKE ALL ON public.automation_python_selections FROM PUBLIC;
REVOKE ALL ON FUNCTION public.automation_python_selection_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_python_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_python_on_update() FROM PUBLIC;
GRANT SELECT, INSERT, DELETE ON public.automation_python_selections TO nekkora_platform;

-- === 7. Invariantes ===
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = 'public.automation_python_selections'::regclass AND c.relrowsecurity AND c.relforcerowsecurity) THEN
        RAISE EXCEPTION '047: RLS ausente em automation_python_selections';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.automation_python_selections', 'UPDATE')
       OR has_table_privilege('nekkora_ingestion', 'public.automation_python_selections', 'SELECT')
       OR has_table_privilege('nekkora_ingestion', 'public.automation_python_selections', 'INSERT') THEN
        RAISE EXCEPTION '047: privilegio alem do previsto na escolha do Python';
    END IF;
END;
$$;

COMMIT;
