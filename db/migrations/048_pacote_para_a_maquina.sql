-- Nekkora Platform - o pacote que a maquina pode baixar fica fixado NA TENTATIVA
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T03, parte a). Contrato: docs/platform-client-execucao-remota-001/t03a-pacote-para-a-maquina.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 028 (artefatos), da 036 (papel do canal da maquina) e da 046 (tentativas).
-- Numero 048 conferido em 16/11 contra a dev e os PRs abertos. As ferramentas do laboratorio e da homologacao exigem numeracao
-- contigua: se outra 048 entrar antes, esta e renumerada sem mudar o conteudo.
--
-- ESTA E A VERSAO RESTRITA, aprovada pela titular em 16/11. A primeira redacao desta migration dava ao canal da maquina a
-- leitura de sha256 e size_bytes de automation_artifacts, limitada so pela organizacao. Aquela redacao NAO foi aplicada em
-- nenhum ambiente real (so no laboratorio isolado, que e recriado) e foi substituida por esta. Aqui o canal da maquina NAO
-- recebe privilegio nenhum em automation_artifacts.
--
-- O QUE FAZ
--   1. remote_attempt_packages: UMA linha por tentativa, com o sha256 e o tamanho do pacote que aquela tentativa pode baixar,
--      a maquina da tentativa e a organizacao. A linha nasce na MESMA transacao que cria a tentativa, por um gatilho de
--      remote_attempts; os valores NAO sao informados por quem insere: o gatilho da propria tabela os le da tentativa (maquina)
--      e do artefato da versao e da impressao fixadas na solicitacao (sha256, tamanho). Sem artefato registrado para a versao
--      fixada, a tentativa NAO e criada.
--   2. Politica de leitura para o canal da maquina (nekkora_ingestion): mesma organizacao, E a maquina da transacao
--      (nekkora.agent_id) e a maquina da tentativa, E a tentativa esta em preparacao (RECEIVED = 3 ou PREPARING = 4).
--      Sem nekkora.agent_id na transacao, nenhuma linha e visivel.
--
-- DE ONDE VEM nekkora.agent_id
--   O gateway o grava na transacao DEPOIS de conferir a credencial da maquina (segredo, nao revogada, nao vencida, Agent ativo
--   e associado a um Worker), com o agent_id que esta na linha da credencial no banco. Nunca vem de cabecalho, corpo ou caminho
--   da requisicao.
--
-- O QUE O BANCO PASSA A LIMITAR (antes: so o codigo do gateway)
--   maquina  - so a maquina da tentativa le a linha;
--   versao   - so existe linha para versao com tentativa criada, e a criacao da tentativa ja exige (046) autorizacao vigente
--              da versao para o Worker da maquina de destino;
--   periodo  - so entre o recebimento e o inicio. Antes de receber, depois de iniciar e depois de terminar: nada.
--
-- O QUE CONTINUA SO NO GATEWAY: o token e a epoca da atribuicao, a renovacao do prazo e a conferencia dos bytes pelo hash.
--
-- O QUE NAO GARANTE: o papel do canal e um so para todas as maquinas; quem controla o processo do gateway escolhe o valor de
-- nekkora.agent_id, como ja escolhe o de nekkora.organization_id. Isto protege contra consulta errada ou fora do fluxo, nao
-- contra um gateway comprometido. As demais tabelas lidas pela maquina continuam limitadas pela organizacao.
--
-- MUDANCA DE COMPORTAMENTO: versao publicada sem pacote registrado deixa de ter tentativa criada (antes a maquina recebia a
-- tentativa e recusava no download).
--
-- DADOS EXISTENTES: nenhuma linha e alterada. Tentativas que ja existam ficam sem linha de pacote: nao baixam mais (nenhum
-- ambiente real tem tentativas; a 046 ainda nao foi aplicada fora do laboratorio).
--
-- REVERSAO (manual; so enquanto nenhuma tentativa em preparacao depender dela):
--   BEGIN;
--   DROP TRIGGER trg_remote_attempts_package ON public.remote_attempts;
--   DROP FUNCTION public.remote_attempt_fix_package();
--   DROP TABLE public.remote_attempt_packages;
--   DROP FUNCTION public.remote_attempt_package_on_insert();
--   COMMIT;
--
-- SEM ELEVACAO: tabela nova, gatilhos SECURITY INVOKER, uma politica. Nenhum papel novo, nenhuma funcao SECURITY DEFINER,
-- nenhum privilegio novo em tabela existente.

BEGIN;

DO $$
BEGIN
    IF to_regclass('public.remote_attempts') IS NULL THEN
        RAISE EXCEPTION '048 exige a 046 aplicada';
    END IF;
    IF to_regclass('public.remote_attempt_packages') IS NOT NULL THEN
        RAISE EXCEPTION '048: remote_attempt_packages ja existe';
    END IF;
    IF has_column_privilege('nekkora_ingestion', 'public.automation_artifacts', 'sha256', 'SELECT') THEN
        RAISE EXCEPTION '048: o canal da maquina ja le automation_artifacts.sha256 (redacao antiga aplicada?). Revogar antes.';
    END IF;
END;
$$;

-- === 1. O pacote da tentativa ===
CREATE TABLE public.remote_attempt_packages (
    attempt_id       UUID PRIMARY KEY,
    organization_id  UUID NOT NULL,
    agent_id         UUID NOT NULL,
    sha256           TEXT NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes       BIGINT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 52428800),
    fixed_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (organization_id, attempt_id) REFERENCES public.remote_attempts (organization_id, attempt_id) ON DELETE RESTRICT
);
COMMENT ON TABLE public.remote_attempt_packages IS
    'O pacote que UMA tentativa de execucao remota pode baixar (048): sha256 e tamanho do artefato da versao e da impressao fixadas '
    'na solicitacao, e a maquina da tentativa. Preenchida pelo banco quando a tentativa nasce. A maquina so le a propria linha, e so '
    'enquanto a tentativa esta em preparacao.';

-- Quem insere informa so a tentativa e a organizacao; maquina, sha256 e tamanho sao lidos aqui, das fontes.
CREATE FUNCTION public.remote_attempt_package_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_agent UUID;
    v_execution UUID;
    v_state SMALLINT;
BEGIN
    NEW.fixed_at := clock_timestamp();
    SELECT t.agent_id, t.remote_execution_id, t.state INTO v_agent, v_execution, v_state
      FROM public.remote_attempts t WHERE t.organization_id = NEW.organization_id AND t.attempt_id = NEW.attempt_id;
    IF v_agent IS NULL OR v_state <> 1 THEN
        RAISE EXCEPTION 'o pacote e fixado quando a tentativa nasce' USING ERRCODE = '23514';
    END IF;
    NEW.agent_id := v_agent;
    NEW.sha256 := NULL;
    NEW.size_bytes := NULL;
    SELECT f.sha256, f.size_bytes INTO NEW.sha256, NEW.size_bytes
      FROM public.remote_executions x
      JOIN public.automation_artifacts f ON f.organization_id = x.organization_id AND f.automation_version_id = x.automation_version_id
                                        AND f.fingerprint = x.package_fingerprint AND f.fingerprint_rule = x.package_fingerprint_rule
     WHERE x.organization_id = NEW.organization_id AND x.remote_execution_id = v_execution
     ORDER BY f.received_at DESC LIMIT 1;
    IF NEW.sha256 IS NULL THEN
        RAISE EXCEPTION 'tentativa recusada: PACKAGE_NOT_REGISTERED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempt_packages_on_insert BEFORE INSERT ON public.remote_attempt_packages
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_package_on_insert();
CREATE TRIGGER trg_remote_attempt_packages_prevent_update BEFORE UPDATE ON public.remote_attempt_packages
    FOR EACH ROW EXECUTE FUNCTION public.prevent_update();
CREATE TRIGGER trg_remote_attempt_packages_prevent_delete BEFORE DELETE ON public.remote_attempt_packages
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_remote_attempt_packages_prevent_truncate BEFORE TRUNCATE ON public.remote_attempt_packages
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- Toda tentativa nasce com o pacote fixado, na mesma transacao. Sem pacote registrado, a tentativa nao nasce.
CREATE FUNCTION public.remote_attempt_fix_package() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    INSERT INTO public.remote_attempt_packages (attempt_id, organization_id) VALUES (NEW.attempt_id, NEW.organization_id);
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_remote_attempts_package AFTER INSERT ON public.remote_attempts
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_fix_package();

-- === 2. RLS ===
ALTER TABLE public.remote_attempt_packages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.remote_attempt_packages FORCE ROW LEVEL SECURITY;
-- Pessoas (Platform): a organizacao da requisicao. E quem cria a tentativa, logo quem faz a linha nascer.
CREATE POLICY tenant_request_context ON public.remote_attempt_packages AS PERMISSIVE FOR ALL TO nekkora_platform
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid);
-- Maquina: a propria linha, e so durante a preparacao.
CREATE POLICY machine_attempt_package_read ON public.remote_attempt_packages AS PERMISSIVE FOR SELECT TO nekkora_ingestion
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid
           AND agent_id = NULLIF(pg_catalog.current_setting('nekkora.agent_id', true), '')::uuid
           AND EXISTS (SELECT 1 FROM public.remote_attempts t
                        WHERE t.organization_id = remote_attempt_packages.organization_id AND t.attempt_id = remote_attempt_packages.attempt_id
                          AND t.agent_id = remote_attempt_packages.agent_id AND t.state IN (3, 4)));

-- === 3. Privilegios ===
REVOKE ALL ON public.remote_attempt_packages FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_package_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_fix_package() FROM PUBLIC;
GRANT SELECT ON public.remote_attempt_packages TO nekkora_platform;
GRANT INSERT (attempt_id, organization_id) ON public.remote_attempt_packages TO nekkora_platform;
GRANT SELECT ON public.remote_attempt_packages TO nekkora_ingestion;

-- === 4. Invariantes ===
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = 'public.remote_attempt_packages'::regclass AND c.relrowsecurity AND c.relforcerowsecurity) THEN
        RAISE EXCEPTION '048: remote_attempt_packages sem RLS forcado';
    END IF;
    IF has_table_privilege('nekkora_ingestion', 'public.remote_attempt_packages', 'INSERT, UPDATE, DELETE, TRUNCATE')
       OR has_table_privilege('nekkora_platform', 'public.remote_attempt_packages', 'UPDATE, DELETE, TRUNCATE') THEN
        RAISE EXCEPTION '048: privilegio alem do previsto em remote_attempt_packages';
    END IF;
    -- o canal da maquina continua sem a chave do armazenamento em automation_artifacts (fronteira da 036, mantida)
    IF has_table_privilege('nekkora_ingestion', 'public.automation_artifacts', 'SELECT')
       OR has_column_privilege('nekkora_ingestion', 'public.automation_artifacts', 'sha256', 'SELECT')
       OR has_column_privilege('nekkora_ingestion', 'public.automation_artifacts', 'size_bytes', 'SELECT')
       OR has_column_privilege('nekkora_ingestion', 'public.automation_artifacts', 'storage_key', 'SELECT') THEN
        RAISE EXCEPTION '048: o canal da maquina nao pode ler a chave do armazenamento em automation_artifacts';
    END IF;
END;
$$;

COMMIT;
