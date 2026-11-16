-- Nekkora Platform - inventario de Python de uma maquina habilitada para execucao remota
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01c, parte 1). Contrato: docs/platform-client-execucao-remota-001/t01c-inventario-de-python.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 042 (T01a); vem depois da 043 (T01b) na numeracao. Numero 044 conferido em
-- 15/11 contra a dev e os PRs abertos. As ferramentas do laboratorio e da homologacao exigem numeracao contigua: se outra
-- 044 entrar antes, esta e renumerada sem mudar o conteudo.
--
-- O QUE FAZ
--   agent_python_environments: o que a maquina habilitada DISSE ter de Python, no ultimo inventario que publicou. Por
--   ambiente: um identificador OPACO (derivado na maquina; a Platform nao consegue voltar dele a um caminho), a versao do
--   Python, o tipo (instalacao ou ambiente virtual) e quando a maquina o observou. E um retrato, nao um historico: cada
--   publicacao substitui a anterior da mesma maquina.
--
-- O QUE NAO GUARDA: caminho, nome de usuario, nome de pasta, lista de pacotes instalados, variavel de ambiente. As CHECKs
-- so deixam passar o identificador opaco e uma versao no formato do Python.
--
-- O QUE NAO FAZ: nao escolhe ambiente para automacao nenhuma, nao verifica dependencias e nao autoriza execucao (ficam para
-- as proximas partes da T01c e para a T02). Nao muda nada do Agent anterior.
--
-- DADOS EXISTENTES: nenhuma linha e alterada. A tabela nova nasce vazia.
--
-- REVERSAO (manual; o inventario e so um retrato, pode ser apagado e a maquina o publica de novo):
--   BEGIN;
--   DROP TABLE public.agent_python_environments;
--   DROP FUNCTION public.agent_python_environment_on_insert();
--   COMMIT;
--
-- SEM ELEVACAO: tabela nova e gatilho SECURITY INVOKER. Nenhum papel novo, nenhuma funcao SECURITY DEFINER, nenhum dono novo.

BEGIN;

-- === 1. Pre-condicao: 042 aplicada ===
DO $$
BEGIN
    IF to_regclass('public.agent_remote_enablements') IS NULL THEN
        RAISE EXCEPTION '044 exige a 042 aplicada';
    END IF;
    IF to_regclass('public.agent_python_environments') IS NOT NULL THEN
        RAISE EXCEPTION '044: agent_python_environments ja existe';
    END IF;
END;
$$;

-- === 2. Inventario ===
CREATE TABLE public.agent_python_environments (
    agent_id         UUID NOT NULL,
    environment_id   TEXT NOT NULL CHECK (environment_id ~ '^[A-Za-z0-9_-]{43}$'),
    organization_id  UUID NOT NULL,
    python_version   TEXT NOT NULL CHECK (python_version ~ '^3\.[0-9]{1,2}\.[0-9]{1,3}([abc]|rc)?[0-9]{0,2}$'),
    kind             SMALLINT NOT NULL CHECK (kind IN (1, 2)),
    observed_at      TIMESTAMPTZ NOT NULL,
    reported_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (agent_id, environment_id),
    FOREIGN KEY (organization_id, agent_id) REFERENCES public.agents (organization_id, agent_id) ON DELETE RESTRICT,
    CHECK (observed_at <= reported_at + interval '5 minutes')
);
COMMENT ON TABLE public.agent_python_environments IS
    'Retrato do Python que a maquina habilitada para execucao remota disse ter (044). Substituido a cada publicacao. Sem caminhos '
    'e sem lista de pacotes: so identificador opaco, versao, tipo e instante da observacao.';
COMMENT ON COLUMN public.agent_python_environments.environment_id IS
    'Identificador opaco, derivado NA MAQUINA (43 caracteres base64url). Estavel para o mesmo ambiente na mesma maquina; nao e um caminho '
    'nem permite chegar a um.';
COMMENT ON COLUMN public.agent_python_environments.kind IS '1=INSTALLATION (instalacao do Python), 2=VIRTUAL_ENVIRONMENT';
COMMENT ON COLUMN public.agent_python_environments.observed_at IS 'Quando a maquina sondou o ambiente (relogio dela); reported_at e o do servidor.';

-- So a maquina HABILITADA e ativa publica inventario, e no maximo 32 ambientes.
CREATE FUNCTION public.agent_python_environment_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.reported_at := clock_timestamp();
    IF NOT EXISTS (SELECT 1 FROM public.agents a
                     JOIN public.agent_remote_enablements r ON r.organization_id = a.organization_id AND r.agent_id = a.agent_id
                    WHERE a.organization_id = NEW.organization_id AND a.agent_id = NEW.agent_id AND a.status = 2) THEN
        RAISE EXCEPTION 'inventario so de maquina habilitada para execucao remota' USING ERRCODE = '42501';
    END IF;
    IF (SELECT count(*) FROM public.agent_python_environments e WHERE e.agent_id = NEW.agent_id) >= 32 THEN
        RAISE EXCEPTION 'inventario limitado a 32 ambientes por maquina' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_agent_python_environments_on_insert BEFORE INSERT ON public.agent_python_environments
    FOR EACH ROW EXECUTE FUNCTION public.agent_python_environment_on_insert();
CREATE TRIGGER trg_agent_python_environments_prevent_update BEFORE UPDATE ON public.agent_python_environments
    FOR EACH ROW EXECUTE FUNCTION public.prevent_update();
CREATE TRIGGER trg_agent_python_environments_prevent_truncate BEFORE TRUNCATE ON public.agent_python_environments
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 3. RLS (padrao da 025/030/035/042) ===
DO $$
DECLARE
    ctx_req CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.organization_id'', true), '''')::uuid';
BEGIN
    ALTER TABLE public.agent_python_environments ENABLE ROW LEVEL SECURITY;
    ALTER TABLE public.agent_python_environments FORCE ROW LEVEL SECURITY;
    EXECUTE format('CREATE POLICY tenant_request_context ON public.agent_python_environments AS PERMISSIVE FOR ALL TO nekkora_platform, nekkora_ingestion USING (%s) WITH CHECK (%s)',
                   ctx_req, ctx_req);
END;
$$;

-- === 4. Privilegios: a maquina substitui o proprio retrato (apaga e insere); as pessoas so leem ===
REVOKE ALL ON public.agent_python_environments FROM PUBLIC;
REVOKE ALL ON FUNCTION public.agent_python_environment_on_insert() FROM PUBLIC;
GRANT SELECT ON public.agent_python_environments TO nekkora_platform;
GRANT SELECT, INSERT, DELETE ON public.agent_python_environments TO nekkora_ingestion;

-- === 5. Invariantes ===
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = 'public.agent_python_environments'::regclass AND c.relrowsecurity AND c.relforcerowsecurity) THEN
        RAISE EXCEPTION '044: RLS ausente em agent_python_environments';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.agent_python_environments', 'INSERT')
       OR has_table_privilege('nekkora_platform', 'public.agent_python_environments', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.agent_python_environments', 'DELETE')
       OR has_table_privilege('nekkora_ingestion', 'public.agent_python_environments', 'UPDATE') THEN
        RAISE EXCEPTION '044: privilegio alem do previsto no inventario';
    END IF;
END;
$$;

COMMIT;
