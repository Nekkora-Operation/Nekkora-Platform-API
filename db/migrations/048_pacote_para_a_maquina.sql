-- Nekkora Platform - a maquina le o endereco do pacote da tentativa que recebeu
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T03, parte a). Contrato: docs/platform-client-execucao-remota-001/t03a-pacote-para-a-maquina.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 036 (leitura do catalogo pela maquina) e da 046 (tentativas). Numero 048
-- conferido em 15/11 contra a dev e os PRs abertos. As ferramentas do laboratorio e da homologacao exigem numeracao contigua:
-- se outra 048 entrar antes, esta e renumerada sem mudar o conteudo.
--
-- O QUE FAZ
--   Da ao papel do canal da maquina (nekkora_ingestion) a leitura de DUAS colunas a mais de automation_artifacts: sha256 e
--   size_bytes. Com elas o gateway acha, no armazenamento, os bytes do pacote da versao fixada numa tentativa e os entrega a
--   maquina que a recebeu.
--
-- POR QUE MUDA UMA FRONTEIRA DA 036
--   A 036 deixou a maquina ler so metadados do catalogo, de proposito SEM a chave do armazenamento (o sha256 dos bytes). O
--   download do pacote pela maquina precisa dessa chave do lado do servidor. A leitura continua limitada a organizacao da
--   credencial (politica machine_catalog_read, da 036) e a colunas nomeadas; o gateway so a usa para a tentativa da propria
--   maquina, com o token e a epoca vigentes. O sha256 dos bytes nao e segredo: e o que a maquina confere depois de baixar.
--
-- O QUE NAO FAZ: nao da leitura da tabela inteira, nem de command_json, nem de quem recebeu o pacote; nao da escrita nenhuma.
--
-- DADOS EXISTENTES: nenhuma linha e alterada.
--
-- REVERSAO (manual):
--   REVOKE SELECT (sha256, size_bytes) ON public.automation_artifacts FROM nekkora_ingestion;
--
-- SEM ELEVACAO: so um GRANT de coluna. Nenhum papel novo, nenhuma funcao SECURITY DEFINER, nenhuma politica nova.

BEGIN;

DO $$
BEGIN
    IF to_regclass('public.remote_attempts') IS NULL THEN
        RAISE EXCEPTION '048 exige a 046 aplicada';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = 'public.automation_artifacts'::regclass AND p.polname = 'machine_catalog_read') THEN
        RAISE EXCEPTION '048 exige a politica machine_catalog_read da 036';
    END IF;
END;
$$;

GRANT SELECT (sha256, size_bytes) ON public.automation_artifacts TO nekkora_ingestion;

-- Invariantes: continua sem leitura da tabela inteira, sem escrita, e sem as colunas que nao sao do canal.
DO $$
BEGIN
    IF has_table_privilege('nekkora_ingestion', 'public.automation_artifacts', 'SELECT')
       OR has_table_privilege('nekkora_ingestion', 'public.automation_artifacts', 'INSERT, UPDATE, DELETE, TRUNCATE')
       OR has_column_privilege('nekkora_ingestion', 'public.automation_artifacts', 'received_by_membership_id', 'SELECT')
       OR has_column_privilege('nekkora_ingestion', 'public.automation_artifacts', 'storage_key', 'SELECT') THEN
        RAISE EXCEPTION '048: privilegio alem do previsto em automation_artifacts';
    END IF;
    IF NOT has_column_privilege('nekkora_ingestion', 'public.automation_artifacts', 'sha256', 'SELECT') THEN
        RAISE EXCEPTION '048: leitura do sha256 nao concedida';
    END IF;
END;
$$;

COMMIT;
