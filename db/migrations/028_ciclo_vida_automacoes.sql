-- Nekkora Platform - ciclo de vida das automacoes (PLATFORM-AUTOMATION-LIFECYCLE-001): artefato imutavel recebido e conferido
-- pela API e auditoria dos atos de cadastro e publicacao. Contrato: docs/relatorios/2026-10-29-automation-lifecycle-contrato.md.
--
-- CANDIDATA ate a aprovacao do relatorio da missao.
--
-- O que muda (tudo ADITIVO; nenhuma tabela anterior muda de forma):
--   * automation_artifacts: o pacote (ZIP do Studio) RECEBIDO pela API, com o sha256 dos BYTES calculado pelo servidor e a
--     impressao nekkora-agent/package-fingerprint/1 calculada pelo servidor sobre o conteudo conferido. Uma linha por
--     artefato aceito, imutavel; so nasce enquanto a versao e DRAFT e por quem tem automation.manage no workspace. Os bytes
--     ficam num armazenamento da API (chave = o proprio sha256, nunca caminho enviado pelo cliente);
--   * automation_admin_events: auditoria dos atos das pessoas (workspace criado, automacao criada, versao criada, rascunho
--     alterado, artefato recebido, versao publicada), so INSERT;
--   * publicacao de versao que referencia um artefato recebido (command_json.artifact.reference = 'nekkora-artifact:<id>'):
--     o gatilho confere, na mesma transacao do 1->2, que o artefato e DESTA versao, que artifact.sha256 e agent_package
--     {fingerprint_rule, fingerprint} sao os do artefato, que automacao e workspace estao ativos e que quem publica tem
--     automation.publish (agent_workspace_permission, a regra unica da 025). Versoes com referencia declarada externa (o
--     desenho anterior, usado por fixtures de teste) nao mudam de regra.
--
-- Papeis: so nekkora_platform (pessoas). Nenhum papel novo, nenhuma funcao SECURITY DEFINER, nenhum dono novo: SEM elevacao.
-- Nada de limite comercial, contador visual ou alarme de consumo (D-EAP2).

-- === 1. Tabelas ===

CREATE TABLE public.automation_artifacts (
    artifact_id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             UUID NOT NULL,
    workspace_id                UUID NOT NULL,
    automation_id               UUID NOT NULL,
    automation_version_id       UUID NOT NULL,
    sha256                      TEXT NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes                  BIGINT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 52428800),
    package_kind                SMALLINT NOT NULL CHECK (package_kind = 1),
    fingerprint_rule            TEXT NOT NULL CHECK (fingerprint_rule = 'nekkora-agent/package-fingerprint/1'),
    fingerprint                 TEXT NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    files_count                 INTEGER NOT NULL CHECK (files_count BETWEEN 1 AND 500),
    manifest_version            TEXT NOT NULL CHECK (manifest_version ~ '^1\.[4-9]\.[0-9]{1,3}$'),
    framework                   TEXT NOT NULL CHECK (framework ~ '^nekkora-rpa(\[excel\])?==[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$'),
    execution_mode              TEXT NOT NULL CHECK (execution_mode IN ('single', 'list', 'tabular')),
    declared_generator          TEXT CHECK (declared_generator ~ '^[A-Za-z0-9][A-Za-z0-9._:/+-]{0,79}$'),
    storage_key                 TEXT NOT NULL,
    received_by_membership_id   UUID NOT NULL,
    received_at                 TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (storage_key = sha256),
    UNIQUE (organization_id, artifact_id),
    UNIQUE (automation_version_id, sha256),
    FOREIGN KEY (organization_id, workspace_id, automation_id, automation_version_id)
        REFERENCES public.automation_versions (organization_id, workspace_id, automation_id, automation_version_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, received_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT
);
CREATE INDEX ix_automation_artifacts_version ON public.automation_artifacts (automation_version_id, received_at);
COMMENT ON TABLE public.automation_artifacts IS 'pacote recebido e conferido pela API (028): sha256 dos bytes e impressao package-fingerprint/1 calculados pelo servidor; imutavel';
COMMENT ON COLUMN public.automation_artifacts.declared_generator IS 'procedencia DECLARADA no manifesto (generator); nao e prova de origem';

CREATE TABLE public.automation_admin_events (
    event_id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         UUID NOT NULL REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    act                     SMALLINT NOT NULL CHECK (act BETWEEN 1 AND 6),
    actor_membership_id     UUID NOT NULL,
    workspace_id            UUID NOT NULL,
    automation_id           UUID,
    automation_version_id   UUID,
    artifact_id             UUID,
    correlation_id          UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (act = 1 OR automation_id IS NOT NULL),
    CHECK (act NOT IN (3, 4, 5, 6) OR automation_version_id IS NOT NULL),
    CHECK (act <> 5 OR artifact_id IS NOT NULL),
    FOREIGN KEY (organization_id, actor_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT
);
CREATE INDEX ix_automation_admin_events_org ON public.automation_admin_events (organization_id, created_at);
COMMENT ON COLUMN public.automation_admin_events.act IS
    '1=WORKSPACE_CREATED, 2=AUTOMATION_CREATED, 3=VERSION_CREATED, 4=DRAFT_UPDATED, 5=ARTIFACT_RECEIVED, 6=VERSION_PUBLISHED';

-- === 2. Gatilhos (SECURITY INVOKER: rodam com o papel de quem grava; search_path fixo, referencias qualificadas) ===

-- Artefato: instante do servidor; so para versao DRAFT de automacao e workspace ativos; autoridade relida aqui.
CREATE FUNCTION public.automation_artifact_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.received_at := clock_timestamp();
    IF NOT EXISTS (SELECT 1 FROM public.automation_versions v
                    WHERE v.organization_id = NEW.organization_id AND v.workspace_id = NEW.workspace_id
                      AND v.automation_id = NEW.automation_id AND v.automation_version_id = NEW.automation_version_id AND v.status = 1) THEN
        RAISE EXCEPTION 'artefato so para versao em rascunho' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.automations a JOIN public.workspaces w
                          ON w.organization_id = a.organization_id AND w.workspace_id = a.workspace_id
                    WHERE a.organization_id = NEW.organization_id AND a.automation_id = NEW.automation_id AND a.status = 1 AND w.status = 1) THEN
        RAISE EXCEPTION 'automacao ou workspace inativo' USING ERRCODE = '23514';
    END IF;
    IF NOT public.agent_workspace_permission(NEW.organization_id, NEW.received_by_membership_id, NEW.workspace_id, 'automation.manage') THEN
        RAISE EXCEPTION 'sem autoridade para registrar o artefato' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

-- Publicacao de versao com artefato recebido: vinculo exato com o artefato, ativos e autoridade de quem publica.
CREATE FUNCTION public.automation_version_publication_check() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_ref TEXT := NEW.command_json -> 'artifact' ->> 'reference';
    v_id  UUID;
BEGIN
    IF v_ref IS NULL OR v_ref NOT LIKE 'nekkora-artifact:%' THEN
        RETURN NEW; -- referencia declarada externa: regra anterior (005/008)
    END IF;
    IF v_ref !~ '^nekkora-artifact:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
        RAISE EXCEPTION 'referencia de artefato malformada' USING ERRCODE = '23514';
    END IF;
    v_id := substr(v_ref, length('nekkora-artifact:') + 1)::uuid;
    IF NOT EXISTS (SELECT 1 FROM public.automation_artifacts x
                    WHERE x.organization_id = NEW.organization_id AND x.artifact_id = v_id
                      AND x.automation_version_id = NEW.automation_version_id
                      AND x.sha256 = NEW.command_json -> 'artifact' ->> 'sha256'
                      AND x.fingerprint_rule = NEW.command_json -> 'agent_package' ->> 'fingerprint_rule'
                      AND x.fingerprint = NEW.command_json -> 'agent_package' ->> 'fingerprint') THEN
        RAISE EXCEPTION 'versao nao corresponde ao artefato recebido' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.automations a JOIN public.workspaces w
                          ON w.organization_id = a.organization_id AND w.workspace_id = a.workspace_id
                    WHERE a.organization_id = NEW.organization_id AND a.automation_id = NEW.automation_id AND a.status = 1 AND w.status = 1) THEN
        RAISE EXCEPTION 'automacao ou workspace inativo' USING ERRCODE = '23514';
    END IF;
    IF NOT public.agent_workspace_permission(NEW.organization_id, NEW.published_by_membership_id, NEW.workspace_id, 'automation.publish') THEN
        RAISE EXCEPTION 'sem autoridade para publicar' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

-- Auditoria: instante do servidor.
CREATE FUNCTION public.automation_admin_event_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_automation_artifacts_on_insert BEFORE INSERT ON public.automation_artifacts
    FOR EACH ROW EXECUTE FUNCTION public.automation_artifact_on_insert();
CREATE TRIGGER trg_automation_versions_publication_check BEFORE UPDATE OF status ON public.automation_versions
    FOR EACH ROW WHEN (OLD.status = 1 AND NEW.status = 2) EXECUTE FUNCTION public.automation_version_publication_check();
CREATE TRIGGER trg_automation_admin_events_on_insert BEFORE INSERT ON public.automation_admin_events
    FOR EACH ROW EXECUTE FUNCTION public.automation_admin_event_on_insert();

-- Nada apagado nem atualizado nas duas tabelas novas.
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['automation_artifacts', 'automation_admin_events'] LOOP
        EXECUTE format('CREATE TRIGGER trg_%s_prevent_delete BEFORE DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.prevent_delete()', t, t);
        EXECUTE format('CREATE TRIGGER trg_%s_prevent_truncate BEFORE TRUNCATE ON public.%I FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate()', t, t);
        EXECUTE format('CREATE TRIGGER trg_%s_prevent_update BEFORE UPDATE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.prevent_update()', t, t);
    END LOOP;
END;
$$;

-- === 3. RLS por organizacao (padrao da 012/025) ===
DO $$
DECLARE
    t TEXT;
    ctx_req CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.organization_id'', true), '''')::uuid';
BEGIN
    FOREACH t IN ARRAY ARRAY['automation_artifacts', 'automation_admin_events'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_request_context ON public.%I AS PERMISSIVE FOR ALL TO nekkora_platform USING (%s) WITH CHECK (%s)',
                       t, ctx_req, ctx_req);
    END LOOP;
END;
$$;

-- === 4. Privilegios ===
REVOKE ALL ON FUNCTION public.automation_artifact_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.automation_version_publication_check() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.automation_admin_event_on_insert() FROM PUBLIC;
GRANT SELECT, INSERT ON public.automation_artifacts, public.automation_admin_events TO nekkora_platform;

-- === 5. Invariantes ===
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname IN ('automation_artifacts', 'automation_admin_events')
                  AND NOT (c.relrowsecurity AND c.relforcerowsecurity)) THEN
        RAISE EXCEPTION '028: RLS ausente nas tabelas novas';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.automation_artifacts', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.automation_artifacts', 'DELETE')
       OR has_table_privilege('nekkora_platform', 'public.automation_admin_events', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.automation_admin_events', 'DELETE') THEN
        RAISE EXCEPTION '028: nekkora_platform nao pode alterar nem apagar artefato ou auditoria';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'public' AND p.proname IN ('automation_artifact_on_insert', 'automation_version_publication_check',
                                                             'automation_admin_event_on_insert') AND p.prosecdef) THEN
        RAISE EXCEPTION '028: nenhuma funcao SECURITY DEFINER';
    END IF;
END;
$$;
