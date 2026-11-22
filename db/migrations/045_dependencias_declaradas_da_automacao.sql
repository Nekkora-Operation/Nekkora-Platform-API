-- Nekkora Platform - dependencias de execucao declaradas na Platform para uma automacao
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01c, parte 2). Contrato: docs/platform-client-execucao-remota-001/t01c-dependencias-declaradas.md.
--
-- CANDIDATA ate a aprovacao do PR. Vem depois da 044 na numeracao; nao depende das tabelas da execucao remota (so das
-- automacoes, 005/028/038). Numero 045 conferido em 15/11 contra a dev e os PRs abertos. As ferramentas do laboratorio e da
-- homologacao exigem numeracao contigua: se outra 045 entrar antes, esta e renumerada sem mudar o conteudo.
--
-- O QUE FAZ
--   1. automation_runtime_requirements: as REVISOES do que uma pessoa declarou sobre as dependencias de uma automacao. Cada
--      revisao diz a origem (so as do pacote; texto digitado; arquivo requirements.txt enviado; "sem dependencias externas",
--      confirmado), a lista de dependencias exatas ACRESCENTADAS (nome==versao), o hash dessa lista, quem declarou e quando.
--      Append-only: declarar de novo cria a revisao seguinte; nada e reescrito.
--   2. automation_admin_events: ato 10 (dependencias declaradas), na mesma trilha das automacoes.
--
-- O QUE NAO FAZ: nao muda o pacote publicado nem a versao (o manifesto continua imutavel); nao instala nada; nao verifica
-- nenhuma maquina; nao autoriza execucao. Gerar pacote e executar localmente continuam exatamente como estao.
--
-- DADOS EXISTENTES: nenhuma linha e alterada. A tabela nova nasce vazia: automacao sem revisao e "ainda nao declarado".
--
-- REVERSAO (manual, so enquanto nao houver ato 10 gravado; a trilha e append-only):
--   BEGIN;
--   DROP TABLE public.automation_runtime_requirements;
--   DROP FUNCTION public.automation_runtime_requirement_on_insert();
--   ALTER TABLE public.automation_admin_events DROP CONSTRAINT automation_admin_events_requirements_declared;
--   ALTER TABLE public.automation_admin_events DROP CONSTRAINT automation_admin_events_act_check;
--   ALTER TABLE public.automation_admin_events ADD CONSTRAINT automation_admin_events_act_check CHECK (act BETWEEN 1 AND 9);
--   COMMIT;
--
-- SEM ELEVACAO: tabela nova, gatilho SECURITY INVOKER, CHECKs na trilha do dono das migrations das automacoes (028/031/038).
-- Nenhum papel novo, nenhuma funcao SECURITY DEFINER, nenhum dono novo.

BEGIN;

-- === 1. Pre-condicao: o CHECK do ato e o da 040 (ato 9 = download de pacote) ===
DO $$
BEGIN
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.automation_admin_events'::regclass AND c.conname = 'automation_admin_events_act_check')
       IS DISTINCT FROM 'CHECK (((act >= 1) AND (act <= 9)))' THEN
        RAISE EXCEPTION '045: CHECK automation_admin_events_act_check diferente do da 040';
    END IF;
    IF to_regclass('public.automation_runtime_requirements') IS NOT NULL THEN
        RAISE EXCEPTION '045: automation_runtime_requirements ja existe';
    END IF;
END;
$$;

-- === 2. Revisoes ===
CREATE TABLE public.automation_runtime_requirements (
    automation_id               UUID NOT NULL,
    revision_number             INTEGER NOT NULL CHECK (revision_number >= 1),
    organization_id             UUID NOT NULL,
    workspace_id                UUID NOT NULL,
    source                      SMALLINT NOT NULL CHECK (source IN (1, 2, 3, 4)),
    dependencies                JSONB NOT NULL CHECK (jsonb_typeof(dependencies) = 'array' AND jsonb_array_length(dependencies) <= 200
                                                      AND octet_length(dependencies::text) <= 16384),
    content_sha256              TEXT NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    declared_by_membership_id   UUID NOT NULL,
    declared_at                 TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (automation_id, revision_number),
    FOREIGN KEY (organization_id, workspace_id, automation_id)
        REFERENCES public.automations (organization_id, workspace_id, automation_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, declared_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    -- "so as do pacote" e "sem dependencias externas" nao acrescentam nada; texto e arquivo podem acrescentar
    CHECK (source IN (2, 3) OR jsonb_array_length(dependencies) = 0)
);
COMMENT ON TABLE public.automation_runtime_requirements IS
    'Revisoes das dependencias de execucao DECLARADAS na Platform para uma automacao (045). Append-only. O efetivo de uma execucao e '
    'o manifesto do pacote publicado mais estes acrescimos; o manifesto nunca e enfraquecido.';
COMMENT ON COLUMN public.automation_runtime_requirements.source IS
    '1=PACKAGE (so as do pacote), 2=FILE (requirements.txt enviado), 3=TEXT (digitado), 4=NONE_CONFIRMED (sem dependencias externas, confirmado por uma pessoa)';
COMMENT ON COLUMN public.automation_runtime_requirements.dependencies IS
    'Acrescimos: nome[extras]==versao, lidos estaticamente, sem repeticao. Nenhuma versao e inventada.';
COMMENT ON COLUMN public.automation_runtime_requirements.content_sha256 IS
    'SHA-256 da origem e da lista (uma por linha): identifica a revisao que uma solicitacao de execucao vai congelar.';

-- Quem declara tem automation.manage no ambiente da automacao; a revisao e a seguinte; a automacao e desse ambiente.
CREATE FUNCTION public.automation_runtime_requirement_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.declared_at := clock_timestamp();
    IF NOT public.agent_workspace_permission(NEW.organization_id, NEW.declared_by_membership_id, NEW.workspace_id, 'automation.manage') THEN
        RAISE EXCEPTION 'sem automation.manage no ambiente da automacao' USING ERRCODE = '42501';
    END IF;
    IF NEW.revision_number <> coalesce((SELECT max(r.revision_number) FROM public.automation_runtime_requirements r
                                         WHERE r.automation_id = NEW.automation_id), 0) + 1 THEN
        RAISE EXCEPTION 'a revisao das dependencias e sempre a seguinte' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_catalog.jsonb_array_elements(NEW.dependencies) d
                WHERE pg_catalog.jsonb_typeof(d) <> 'string'
                   OR (d #>> '{}') !~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,79}(\[[A-Za-z0-9,._-]{1,80}\])?==[0-9][0-9A-Za-z.+!-]{0,40}$') THEN
        RAISE EXCEPTION 'dependencia declarada fora do formato nome==versao' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_automation_runtime_requirements_on_insert BEFORE INSERT ON public.automation_runtime_requirements
    FOR EACH ROW EXECUTE FUNCTION public.automation_runtime_requirement_on_insert();
CREATE TRIGGER trg_automation_runtime_requirements_prevent_update BEFORE UPDATE ON public.automation_runtime_requirements
    FOR EACH ROW EXECUTE FUNCTION public.prevent_update();
CREATE TRIGGER trg_automation_runtime_requirements_prevent_delete BEFORE DELETE ON public.automation_runtime_requirements
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_automation_runtime_requirements_prevent_truncate BEFORE TRUNCATE ON public.automation_runtime_requirements
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 3. Trilha: ato 10 ===
ALTER TABLE public.automation_admin_events DROP CONSTRAINT automation_admin_events_act_check;
ALTER TABLE public.automation_admin_events ADD CONSTRAINT automation_admin_events_act_check CHECK (act BETWEEN 1 AND 10);
ALTER TABLE public.automation_admin_events ADD CONSTRAINT automation_admin_events_requirements_declared
    CHECK (act <> 10 OR (automation_id IS NOT NULL AND automation_version_id IS NULL AND artifact_id IS NULL));
COMMENT ON COLUMN public.automation_admin_events.act IS
    '1=WORKSPACE_CREATED, 2=AUTOMATION_CREATED, 3=VERSION_CREATED, 4=DRAFT_UPDATED, 5=ARTIFACT_RECEIVED, 6=VERSION_PUBLISHED, 7=WORKSPACE_RENAMED, '
    '8=AUTOMATION_RENAMED, 9=PACKAGE_DOWNLOADED, 10=RUNTIME_REQUIREMENTS_DECLARED (045)';

-- === 4. RLS (padrao das automacoes) ===
DO $$
DECLARE
    ctx_req CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.organization_id'', true), '''')::uuid';
BEGIN
    ALTER TABLE public.automation_runtime_requirements ENABLE ROW LEVEL SECURITY;
    ALTER TABLE public.automation_runtime_requirements FORCE ROW LEVEL SECURITY;
    EXECUTE format('CREATE POLICY tenant_request_context ON public.automation_runtime_requirements AS PERMISSIVE FOR ALL TO nekkora_platform USING (%s) WITH CHECK (%s)',
                   ctx_req, ctx_req);
END;
$$;

-- === 5. Privilegios: so as pessoas (nekkora_platform) leem e declaram. A maquina ainda nao le (a entrega fica para a T02). ===
REVOKE ALL ON public.automation_runtime_requirements FROM PUBLIC;
REVOKE ALL ON FUNCTION public.automation_runtime_requirement_on_insert() FROM PUBLIC;
GRANT SELECT, INSERT ON public.automation_runtime_requirements TO nekkora_platform;

-- === 6. Invariantes ===
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = 'public.automation_runtime_requirements'::regclass AND c.relrowsecurity AND c.relforcerowsecurity) THEN
        RAISE EXCEPTION '045: RLS ausente em automation_runtime_requirements';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.automation_runtime_requirements', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.automation_runtime_requirements', 'DELETE')
       OR has_table_privilege('nekkora_ingestion', 'public.automation_runtime_requirements', 'SELECT')
       OR has_table_privilege('nekkora_ingestion', 'public.automation_runtime_requirements', 'INSERT') THEN
        RAISE EXCEPTION '045: privilegio alem do previsto nas dependencias declaradas';
    END IF;
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.automation_admin_events'::regclass AND c.conname = 'automation_admin_events_act_check')
       IS DISTINCT FROM 'CHECK (((act >= 1) AND (act <= 10)))' THEN
        RAISE EXCEPTION '045: CHECK novo do ato diferente do esperado';
    END IF;
END;
$$;

COMMIT;
