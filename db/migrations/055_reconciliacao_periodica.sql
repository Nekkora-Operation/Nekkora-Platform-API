-- Nekkora Platform - indice das organizacoes com execucao remota, para a reconciliacao PERIODICA do servidor
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, fechamento). Contrato: docs/platform-client-execucao-remota-001/reconciliacao-periodica.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 046 (tentativas) e da 053 (encerramento pelo servidor). Numero 055 conferido em
-- 16/11 contra a dev e os PRs abertos.
--
-- O PROBLEMA: a 053 encerra a tentativa que nao tem mais quem a conclua quando a maquina volta ou quando uma pessoa consulta.
-- Sem nenhum dos dois, a linha continuava "em execucao" no banco. O estado nao pode depender de alguem abrir a tela.
--
-- A tarefa periodica da aplicacao (o mesmo mecanismo do despacho de e-mail: um ciclo agendado dentro da propria Platform, sem
-- servico novo) precisa saber EM QUAIS organizacoes olhar. As tabelas da execucao remota so sao visiveis com o contexto de uma
-- organizacao, e a aplicacao nao tem como listar organizacoes sem contexto.
--
-- O QUE FAZ
--   1. remote_execution_organizations: uma linha por organizacao que ja teve alguma tentativa de execucao remota. So o
--      identificador da organizacao e o instante da primeira tentativa. Nada de solicitacao, maquina, pessoa ou conteudo.
--   2. Um gatilho em remote_attempts grava a linha na primeira tentativa da organizacao (mesma transacao, mesmo papel).
--   3. As organizacoes que JA tem tentativas sao gravadas aqui (o dono le as proprias tabelas, dentro desta transacao, como
--      nas 031 a 034).
--   4. A Platform (nekkora_platform) le a lista SEM contexto de organizacao - e a unica leitura sem contexto desta missao, e
--      ela so devolve identificadores de organizacao. Tudo o mais continua exigindo o contexto: a tarefa entra em cada
--      organizacao, uma por vez, e aplica as mesmas regras da 053 (AttemptClosure).
--
-- O QUE NAO FAZ: nao encerra tentativa nenhuma (quem encerra e a aplicacao, pelas regras da 053, que o banco confere); nao da a
-- ninguem leitura de tentativas de outra organizacao; o canal das maquinas (nekkora_ingestion) nao le esta tabela.
--
-- DADOS EXISTENTES: nenhuma linha existente e alterada.
--
-- REVERSAO (manual):
--   BEGIN;
--   DROP TRIGGER trg_remote_attempts_mark_organization ON public.remote_attempts;
--   DROP FUNCTION public.remote_attempt_mark_organization();
--   DROP TABLE public.remote_execution_organizations;
--   COMMIT;
--
-- SEM ELEVACAO: tabela nova, gatilho SECURITY INVOKER. Nenhum papel novo, nenhuma funcao SECURITY DEFINER.

BEGIN;

-- === 1. Pre-condicao ===
DO $$
BEGIN
    IF to_regclass('public.remote_attempts') IS NULL
       OR NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'public.remote_attempts'::regclass AND attname = 'closure_cause' AND NOT attisdropped) THEN
        RAISE EXCEPTION '055 exige a 046 e a 053 aplicadas';
    END IF;
    IF to_regclass('public.remote_execution_organizations') IS NOT NULL THEN
        RAISE EXCEPTION '055: remote_execution_organizations ja existe';
    END IF;
END;
$$;

-- === 2. Tabela ===
CREATE TABLE public.remote_execution_organizations (
    organization_id   UUID PRIMARY KEY,
    first_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE public.remote_execution_organizations IS
    'Organizacoes que ja tiveram tentativa de execucao remota (055). Serve so para a reconciliacao periodica saber onde olhar: '
    'a Platform le esta lista sem contexto e entra em cada organizacao, uma por vez. So identificadores de organizacao.';

-- === 3. Regras ===
CREATE FUNCTION public.remote_attempt_mark_organization() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    INSERT INTO public.remote_execution_organizations (organization_id) VALUES (NEW.organization_id) ON CONFLICT (organization_id) DO NOTHING;
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_remote_attempts_mark_organization AFTER INSERT ON public.remote_attempts
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_mark_organization();

CREATE TRIGGER trg_remote_execution_organizations_prevent_update BEFORE UPDATE ON public.remote_execution_organizations
    FOR EACH ROW EXECUTE FUNCTION public.prevent_update();
CREATE TRIGGER trg_remote_execution_organizations_prevent_delete BEFORE DELETE ON public.remote_execution_organizations
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_remote_execution_organizations_prevent_truncate BEFORE TRUNCATE ON public.remote_execution_organizations
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 4. Organizacoes que ja tem tentativas ===
-- O dono le as proprias tabelas so dentro desta transacao (a tabela fica travada ate o COMMIT; ninguem ve o intervalo).
ALTER TABLE public.remote_attempts NO FORCE ROW LEVEL SECURITY;
INSERT INTO public.remote_execution_organizations (organization_id, first_attempt_at)
SELECT t.organization_id, min(t.created_at) FROM public.remote_attempts t GROUP BY t.organization_id;
ALTER TABLE public.remote_attempts FORCE ROW LEVEL SECURITY;

-- === 5. RLS ===
ALTER TABLE public.remote_execution_organizations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.remote_execution_organizations FORCE ROW LEVEL SECURITY;
-- gravar: so a propria organizacao, com contexto (e o gatilho, na transacao de quem cria a tentativa)
CREATE POLICY tenant_request_context ON public.remote_execution_organizations AS PERMISSIVE FOR INSERT TO nekkora_platform
    WITH CHECK (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid);
-- ler: a lista inteira, so para a Platform (a tarefa periodica roda sem contexto). So identificadores de organizacao.
CREATE POLICY reconciliation_index_read ON public.remote_execution_organizations AS PERMISSIVE FOR SELECT TO nekkora_platform
    USING (true);

-- === 6. Privilegios ===
REVOKE ALL ON public.remote_execution_organizations FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_mark_organization() FROM PUBLIC;
GRANT SELECT, INSERT ON public.remote_execution_organizations TO nekkora_platform;

-- === 7. Invariantes ===
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = 'public.remote_execution_organizations'::regclass AND c.relrowsecurity AND c.relforcerowsecurity)
       OR NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = 'public.remote_attempts'::regclass AND c.relrowsecurity AND c.relforcerowsecurity) THEN
        RAISE EXCEPTION '055: RLS ausente';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.remote_execution_organizations', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.remote_execution_organizations', 'DELETE')
       OR has_table_privilege('nekkora_ingestion', 'public.remote_execution_organizations', 'SELECT')
       OR has_table_privilege('nekkora_ingestion', 'public.remote_execution_organizations', 'INSERT') THEN
        RAISE EXCEPTION '055: privilegio alem do previsto no indice da reconciliacao';
    END IF;
END;
$$;

COMMIT;
