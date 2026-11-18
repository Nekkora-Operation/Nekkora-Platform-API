-- Nekkora Platform - a maquina avisa que REMOVEU, pelo prazo de guarda local, resultados ou registros que nao chegaram
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T04/T05). Contrato: docs/platform-client-execucao-remota-001/t05-registros.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 046. Numero 052 conferido em 16/11 contra a dev e os PRs abertos. As ferramentas
-- do laboratorio e da homologacao exigem numeracao contigua: se outra 052 entrar antes, esta e renumerada sem mudar o conteudo.
--
-- POR QUE
--   O aplicativo da maquina guarda, por um prazo, a pasta de saida e a fila de registros cujo recebimento a Platform nao
--   confirmou. Vencido o prazo, ele remove. Sem este aviso, a Platform nao distingue "nada mais a receber" de "havia mais e
--   foi removido da maquina": a lacuna ficaria invisivel.
--
-- O QUE FAZ
--   remote_attempt_retention_losses: no maximo UMA linha por tentativa e por tipo (1 = registros, 2 = resultados). Guarda
--   quanto havia NA MAQUINA quando ela removeu (linhas capturadas, ou arquivos na pasta de saida) e quando o aviso chegou.
--   Nao diz quanto se perdeu: isso e a diferenca para o que a Platform tem, e a Platform sabe o que tem.
--
-- O QUE NAO FAZ: nao muda a tentativa nem o resultado dela; nao muda nem apaga linha, arquivo ou fechamento; nao e a
-- retencao do SERVIDOR (que continua pendente e e outra tarefa).
--
-- DADOS EXISTENTES: nenhuma linha e alterada. A tabela nova nasce vazia.
--
-- REVERSAO (manual; so enquanto a tabela estiver vazia):
--   BEGIN;
--   DROP TABLE public.remote_attempt_retention_losses;
--   DROP FUNCTION public.remote_attempt_retention_loss_on_insert();
--   COMMIT;
--
-- SEM ELEVACAO: tabela nova e gatilho SECURITY INVOKER. Nenhum papel novo, nenhuma funcao SECURITY DEFINER.

BEGIN;

DO $$
BEGIN
    IF to_regclass('public.remote_attempts') IS NULL THEN
        RAISE EXCEPTION '052 exige a 046 aplicada';
    END IF;
    IF to_regclass('public.remote_attempt_retention_losses') IS NOT NULL THEN
        RAISE EXCEPTION '052: remote_attempt_retention_losses ja existe';
    END IF;
END;
$$;

CREATE TABLE public.remote_attempt_retention_losses (
    attempt_id       UUID NOT NULL,
    kind             SMALLINT NOT NULL CHECK (kind IN (1, 2)),
    organization_id  UUID NOT NULL,
    local_count      INTEGER NOT NULL CHECK (local_count BETWEEN 0 AND 1000000),
    reported_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (attempt_id, kind),
    FOREIGN KEY (organization_id, attempt_id) REFERENCES public.remote_attempts (organization_id, attempt_id) ON DELETE RESTRICT
);
COMMENT ON TABLE public.remote_attempt_retention_losses IS
    'Aviso da maquina (052): ela removeu, pelo prazo de guarda local, registros (kind 1) ou arquivos de resultado (kind 2) de uma '
    'tentativa sem a confirmacao de recebimento da Platform. local_count: quanto havia na maquina (linhas capturadas ou arquivos na '
    'pasta de saida). Nao altera o resultado da tentativa.';

-- So de tentativa que iniciou e terminou: antes disso nao ha o que guardar nem o que remover.
CREATE FUNCTION public.remote_attempt_retention_loss_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.reported_at := clock_timestamp();
    IF NOT EXISTS (SELECT 1 FROM public.remote_attempts t
                    WHERE t.organization_id = NEW.organization_id AND t.attempt_id = NEW.attempt_id AND t.started_at IS NOT NULL AND t.state >= 7) THEN
        RAISE EXCEPTION 'a perda por prazo de guarda so e avisada de tentativa que iniciou e terminou' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempt_retention_losses_on_insert BEFORE INSERT ON public.remote_attempt_retention_losses
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_retention_loss_on_insert();
CREATE TRIGGER trg_remote_attempt_retention_losses_prevent_update BEFORE UPDATE ON public.remote_attempt_retention_losses
    FOR EACH ROW EXECUTE FUNCTION public.prevent_update();
CREATE TRIGGER trg_remote_attempt_retention_losses_prevent_delete BEFORE DELETE ON public.remote_attempt_retention_losses
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_remote_attempt_retention_losses_prevent_truncate BEFORE TRUNCATE ON public.remote_attempt_retention_losses
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

ALTER TABLE public.remote_attempt_retention_losses ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.remote_attempt_retention_losses FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_request_context ON public.remote_attempt_retention_losses AS PERMISSIVE FOR ALL TO nekkora_platform, nekkora_ingestion
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid);

-- A maquina avisa; as pessoas so leem. Ninguem muda nem apaga.
REVOKE ALL ON public.remote_attempt_retention_losses FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_retention_loss_on_insert() FROM PUBLIC;
GRANT SELECT ON public.remote_attempt_retention_losses TO nekkora_platform;
GRANT SELECT, INSERT ON public.remote_attempt_retention_losses TO nekkora_ingestion;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = 'public.remote_attempt_retention_losses'::regclass AND c.relrowsecurity AND c.relforcerowsecurity) THEN
        RAISE EXCEPTION '052: RLS ausente em remote_attempt_retention_losses';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.remote_attempt_retention_losses', 'INSERT, UPDATE, DELETE')
       OR has_table_privilege('nekkora_ingestion', 'public.remote_attempt_retention_losses', 'UPDATE, DELETE') THEN
        RAISE EXCEPTION '052: privilegio alem do previsto em remote_attempt_retention_losses';
    END IF;
END;
$$;

COMMIT;
