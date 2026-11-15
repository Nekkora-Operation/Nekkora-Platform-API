-- Nekkora Platform - catalogo de metadados da propria maquina (PLATFORM-AGENT-CATALOGO-001, T01). Contrato:
-- api/openapi/nekkora-agent-v1.yaml (GET /agent/api/v1/catalog e GET /agent/api/v1/catalog/automations/{automationId}) e
-- docs/platform-agent-catalogo-001/t01-catalogo-da-maquina.md.
--
-- CANDIDATA ate a aprovacao do PR. Numero 036 conferido em 13/11 contra a dev (cb4ba7d; a maior era 035) e as branches
-- remotas abertas (nenhum PR da API aberto). As ferramentas do laboratorio e da homologacao exigem numeracao contigua: se outra
-- migration ocupar 036 antes, esta e renumerada sem mudar o conteudo.
--
-- POR QUE MIGRATION: o gateway roda com o papel nekkora_ingestion, que hoje so le as autorizacoes (identificadores) e o
-- estado da organizacao. O catalogo precisa dos NOMES (organizacao, workspace, automacao) e dos metadados das versoes
-- (numero, publicacao, versao declarada do pacote). Sem esta leitura, o Agent continuaria a mostrar so identificadores e a
-- adivinhar versoes pelo historico local.
--
-- SO LEITURA, SO COLUNAS NOMEADAS. Nenhum papel novo, nenhuma funcao, nenhum SECURITY DEFINER, nenhum dono novo, nenhuma
-- escrita: SEM elevacao (como 025, 030 e 035, que tambem concederam privilegios de coluna a ingestao). A RLS da 012
-- (tenant_request_context, que ja inclui nekkora_ingestion) continua valendo nas cinco tabelas: so a organizacao da
-- credencial e visivel. Ficam FORA, de proposito: comando da versao (command_json: entrada, argumentos, referencia do
-- artefato), descricoes, razao social, autoria (pertinencias), chave de armazenamento, sha256 dos bytes, dependencias e o
-- requisito de Python do artefato.
--
-- RLS DOS ARTEFATOS: automation_artifacts (028) so tem politica para nekkora_platform; sem politica, a ingestao veria ZERO
-- linhas (falha fechada). Esta migration cria UMA politica nova, machine_catalog_read, FOR SELECT (nunca escrita), TO
-- nekkora_ingestion, com o mesmo predicado de contexto da requisicao da 012 (so a organizacao da credencial). As outras
-- quatro tabelas ja tem tenant_request_context para a ingestao desde a 012. O filtro do que a maquina pode ver (Worker ativo, autorizacao do Worker no workspace,
-- autorizacao local vigente) e da rota, sobre estas colunas; ver o documento do contrato.

BEGIN;

DO $$
BEGIN
    IF to_regclass('public.agent_unlink_operations') IS NULL THEN
        RAISE EXCEPTION '036: exige 035';
    END IF;
    IF has_column_privilege('nekkora_ingestion', 'public.automation_versions', 'version_number', 'SELECT') THEN
        RAISE EXCEPTION '036: ja aplicada';
    END IF;
END;
$$;

-- === 1. Privilegios (so SELECT de coluna) ===
GRANT SELECT (name) ON public.organizations TO nekkora_ingestion;
GRANT SELECT (organization_id, workspace_id, name, status) ON public.workspaces TO nekkora_ingestion;
GRANT SELECT (organization_id, workspace_id, automation_id, name, status) ON public.automations TO nekkora_ingestion;
GRANT SELECT (organization_id, workspace_id, automation_id, automation_version_id, version_number, status, published_at)
    ON public.automation_versions TO nekkora_ingestion;
GRANT SELECT (organization_id, automation_version_id, package_kind, fingerprint_rule, fingerprint, package_name, package_version, received_at)
    ON public.automation_artifacts TO nekkora_ingestion;

-- === 2. RLS: leitura dos artefatos pela ingestao, so na organizacao da requisicao ===
CREATE POLICY machine_catalog_read ON public.automation_artifacts AS PERMISSIVE FOR SELECT TO nekkora_ingestion
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid);

-- === 3. Invariantes ===
DO $$
DECLARE
    v_bad TEXT;
BEGIN
    -- a RLS forcada da 012/028 continua nas cinco tabelas, com a politica da requisicao para a ingestao
    SELECT string_agg(t, ', ') INTO v_bad
      FROM unnest(ARRAY['public.organizations', 'public.workspaces', 'public.automations', 'public.automation_versions',
                        'public.automation_artifacts']) t
     WHERE NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = t::regclass AND c.relrowsecurity AND c.relforcerowsecurity)
        OR NOT EXISTS (SELECT 1 FROM pg_policy p, unnest(p.polroles) x JOIN pg_roles r ON r.oid = x
                        WHERE p.polrelid = t::regclass AND r.rolname = 'nekkora_ingestion'
                          AND (p.polname = 'tenant_request_context' OR (p.polname = 'machine_catalog_read' AND p.polcmd = 'r')));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '036: RLS ausente para a ingestao em %', v_bad; END IF;
    -- a politica nova: so leitura, so a ingestao, so o contexto da requisicao
    IF (SELECT string_agg(p.polcmd::text || ':' || r.rolname, ',') FROM pg_policy p, unnest(p.polroles) x JOIN pg_roles r ON r.oid = x
         WHERE p.polrelid = 'public.automation_artifacts'::regclass AND p.polname = 'machine_catalog_read') IS DISTINCT FROM 'r:nekkora_ingestion'
       OR NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = 'public.automation_artifacts'::regclass AND p.polname = 'machine_catalog_read'
                         AND p.polpermissive AND p.polwithcheck IS NULL
                         AND pg_get_expr(p.polqual, p.polrelid) LIKE '%organization_id = (NULLIF(current_setting(''nekkora.organization_id''%') THEN
        RAISE EXCEPTION '036: politica machine_catalog_read fora do esperado';
    END IF;

    -- nada alem de leitura: nenhuma escrita, nem de tabela nem de coluna
    SELECT string_agg(t, ', ') INTO v_bad
      FROM unnest(ARRAY['public.organizations', 'public.workspaces', 'public.automations', 'public.automation_versions',
                        'public.automation_artifacts']) t
     WHERE has_table_privilege('nekkora_ingestion', t, 'INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
        OR has_any_column_privilege('nekkora_ingestion', t, 'INSERT, UPDATE, REFERENCES')
        OR has_table_privilege('nekkora_ingestion', t, 'SELECT'); -- leitura da tabela inteira: so colunas nomeadas
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '036: a ingestao tem privilegio alem da leitura de colunas em %', v_bad; END IF;

    -- o que fica fora do catalogo continua ilegivel para a maquina
    IF has_column_privilege('nekkora_ingestion', 'public.automation_versions', 'command_json', 'SELECT')
       OR has_column_privilege('nekkora_ingestion', 'public.automation_versions', 'created_by_membership_id', 'SELECT')
       OR has_column_privilege('nekkora_ingestion', 'public.automation_versions', 'published_by_membership_id', 'SELECT')
       OR has_column_privilege('nekkora_ingestion', 'public.automations', 'description', 'SELECT')
       OR has_column_privilege('nekkora_ingestion', 'public.automations', 'created_by_membership_id', 'SELECT')
       OR has_column_privilege('nekkora_ingestion', 'public.workspaces', 'description', 'SELECT')
       OR has_column_privilege('nekkora_ingestion', 'public.workspaces', 'client_id', 'SELECT')
       OR has_column_privilege('nekkora_ingestion', 'public.organizations', 'legal_name', 'SELECT')
       OR has_column_privilege('nekkora_ingestion', 'public.automation_artifacts', 'storage_key', 'SELECT')
       OR has_column_privilege('nekkora_ingestion', 'public.automation_artifacts', 'sha256', 'SELECT')
       OR has_column_privilege('nekkora_ingestion', 'public.automation_artifacts', 'dependencies', 'SELECT')
       OR has_column_privilege('nekkora_ingestion', 'public.automation_artifacts', 'python_requirement', 'SELECT')
       OR has_column_privilege('nekkora_ingestion', 'public.automation_artifacts', 'received_by_membership_id', 'SELECT') THEN
        RAISE EXCEPTION '036: coluna fora do catalogo legivel pela ingestao';
    END IF;
END;
$$;

COMMIT;
