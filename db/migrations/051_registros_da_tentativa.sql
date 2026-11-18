-- Nekkora Platform - registros (saida do processo) de uma tentativa de execucao remota
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T05). Contrato: docs/platform-client-execucao-remota-001/t05-registros.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 046. Numero 051 conferido em 15/11 contra a dev e os PRs abertos. As ferramentas
-- do laboratorio e da homologacao exigem numeracao contigua: se outra 051 entrar antes, esta e renumerada sem mudar o conteudo.
--
-- O QUE FAZ
--   1. remote_attempt_log_lines: as linhas que o processo da automacao escreveu na saida padrao e na saida de erro numa
--      tentativa que INICIOU, na ordem em que o aplicativo da maquina as capturou: sequencia (1 a 50.000), instante da
--      captura, canal (1=STDOUT, 2=STDERR, 3=EXECUTOR - aviso do proprio aplicativo), nivel (0=UNKNOWN; nenhum outro e
--      gravado nesta versao), se a linha foi cortada no limite e o texto.
--   2. remote_attempt_log_closures: o fechamento do envio - quantas linhas a maquina enviou e quantas DESCARTOU por limite.
--      Sem fechamento, o envio ainda nao terminou (ou nao comecou).
--
-- LIMITES (pontos de partida de laboratorio, repetidos na aplicacao, que pode ser configurada para MENOS):
--   8 KiB por linha; 50.000 linhas e 20 MiB por tentativa.
--
-- O QUE NAO FAZ: nao muda a tentativa (linha que falta, cortada ou descartada nao altera o resultado do robo); nao apaga
-- nada (a retencao e uma tarefa propria: ela so pode APAGAR O TEXTO de uma linha, uma vez, e o registro de que a linha
-- existiu fica). Nao guarda linha de execucao iniciada no proprio computador: so existe linha de tentativa remota.
--
-- DADOS EXISTENTES: nenhuma linha e alterada. As tabelas novas nascem vazias.
--
-- REVERSAO (manual; so enquanto as tabelas estiverem vazias):
--   BEGIN;
--   DROP TABLE public.remote_attempt_log_closures;
--   DROP TABLE public.remote_attempt_log_lines;
--   DROP FUNCTION public.remote_attempt_log_line_on_insert();
--   DROP FUNCTION public.remote_attempt_log_lines_after_insert();
--   DROP FUNCTION public.remote_attempt_log_line_on_update();
--   DROP FUNCTION public.remote_attempt_log_closure_on_insert();
--   COMMIT;
--
-- SEM ELEVACAO: tabelas novas e gatilhos SECURITY INVOKER. Nenhum papel novo, nenhuma funcao SECURITY DEFINER.

BEGIN;

DO $$
BEGIN
    IF to_regclass('public.remote_attempts') IS NULL THEN
        RAISE EXCEPTION '051 exige a 046 aplicada';
    END IF;
    IF to_regclass('public.remote_attempt_log_lines') IS NOT NULL THEN
        RAISE EXCEPTION '051: remote_attempt_log_lines ja existe';
    END IF;
END;
$$;

-- === 1. Linhas ===
CREATE TABLE public.remote_attempt_log_lines (
    attempt_id       UUID NOT NULL,
    sequence         INTEGER NOT NULL CHECK (sequence BETWEEN 1 AND 50000),
    organization_id  UUID NOT NULL,
    captured_at      TIMESTAMPTZ NOT NULL,
    channel          SMALLINT NOT NULL CHECK (channel BETWEEN 1 AND 3),
    level            SMALLINT NOT NULL DEFAULT 0 CHECK (level = 0),
    truncated        BOOLEAN NOT NULL DEFAULT false,
    message          TEXT NOT NULL CHECK (octet_length(message) <= 8192 AND message !~ '[\x01-\x08\x0a-\x1f\x7f]'),
    received_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    redacted_at      TIMESTAMPTZ,
    PRIMARY KEY (attempt_id, sequence),
    FOREIGN KEY (organization_id, attempt_id) REFERENCES public.remote_attempts (organization_id, attempt_id) ON DELETE RESTRICT,
    CHECK (redacted_at IS NULL OR (redacted_at >= received_at AND message = ''))
);
COMMENT ON TABLE public.remote_attempt_log_lines IS
    'Linhas da saida do processo de uma tentativa remota que iniciou (051), na ordem da captura pela maquina. redacted_at: o texto foi '
    'apagado pela retencao; o registro de que a linha existiu fica.';
COMMENT ON COLUMN public.remote_attempt_log_lines.channel IS '1=STDOUT, 2=STDERR, 3=EXECUTOR (aviso do aplicativo da maquina, nao do robo)';
COMMENT ON COLUMN public.remote_attempt_log_lines.level IS '0=UNKNOWN. STDERR e um canal, nao um nivel; nada e classificado pelo texto';
COMMENT ON COLUMN public.remote_attempt_log_lines.truncated IS 'A linha passava de 8 KiB e foi cortada pela maquina';

-- So de tentativa que INICIOU; nada depois do fechamento.
CREATE FUNCTION public.remote_attempt_log_line_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.received_at := clock_timestamp();
    IF NEW.redacted_at IS NOT NULL THEN
        RAISE EXCEPTION 'a linha nasce com o texto' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.remote_attempts t
                    WHERE t.organization_id = NEW.organization_id AND t.attempt_id = NEW.attempt_id AND t.started_at IS NOT NULL) THEN
        RAISE EXCEPTION 'registro so de tentativa que iniciou' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM public.remote_attempt_log_closures c WHERE c.attempt_id = NEW.attempt_id) THEN
        RAISE EXCEPTION 'o envio de registros desta tentativa ja foi fechado' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempt_log_lines_on_insert BEFORE INSERT ON public.remote_attempt_log_lines
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_log_line_on_insert();

-- Uma vez por comando: a sequencia e continua (sem buraco) e o total nao passa de 20 MiB por tentativa.
CREATE FUNCTION public.remote_attempt_log_lines_after_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    bad UUID;
BEGIN
    SELECT l.attempt_id INTO bad
      FROM public.remote_attempt_log_lines l
     WHERE l.attempt_id IN (SELECT DISTINCT n.attempt_id FROM novas n)
     GROUP BY l.attempt_id
    HAVING max(l.sequence) <> count(*) OR sum(octet_length(l.message)) > 20971520
     LIMIT 1;
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION 'registros da tentativa: sequencia com buraco ou mais de 20 MiB' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_remote_attempt_log_lines_after_insert AFTER INSERT ON public.remote_attempt_log_lines
    REFERENCING NEW TABLE AS novas FOR EACH STATEMENT EXECUTE FUNCTION public.remote_attempt_log_lines_after_insert();

-- A unica mudanca permitida: apagar o texto (retencao), uma vez.
CREATE FUNCTION public.remote_attempt_log_line_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.attempt_id <> OLD.attempt_id OR NEW.sequence <> OLD.sequence OR NEW.organization_id <> OLD.organization_id
       OR NEW.captured_at <> OLD.captured_at OR NEW.channel <> OLD.channel OR NEW.level <> OLD.level OR NEW.truncated <> OLD.truncated
       OR NEW.received_at <> OLD.received_at OR OLD.redacted_at IS NOT NULL OR NEW.redacted_at IS NULL OR NEW.message <> '' THEN
        RAISE EXCEPTION 'a linha so muda para ter o texto apagado pela retencao' USING ERRCODE = '42501';
    END IF;
    NEW.redacted_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempt_log_lines_on_update BEFORE UPDATE ON public.remote_attempt_log_lines
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_log_line_on_update();
CREATE TRIGGER trg_remote_attempt_log_lines_prevent_truncate BEFORE TRUNCATE ON public.remote_attempt_log_lines
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 2. Fechamento do envio ===
CREATE TABLE public.remote_attempt_log_closures (
    attempt_id        UUID PRIMARY KEY,
    organization_id   UUID NOT NULL,
    lines             INTEGER NOT NULL CHECK (lines BETWEEN 0 AND 50000),
    dropped_lines     BIGINT NOT NULL CHECK (dropped_lines BETWEEN 0 AND 1000000000000),
    closed_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (organization_id, attempt_id) REFERENCES public.remote_attempts (organization_id, attempt_id) ON DELETE RESTRICT
);
COMMENT ON TABLE public.remote_attempt_log_closures IS
    'Fechamento do envio de registros de uma tentativa (051): quantas linhas a maquina enviou e quantas descartou por limite. Sem linha '
    'aqui, o envio nao terminou. Nao altera o resultado da tentativa.';

CREATE FUNCTION public.remote_attempt_log_closure_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.closed_at := clock_timestamp();
    IF NOT EXISTS (SELECT 1 FROM public.remote_attempts t
                    WHERE t.organization_id = NEW.organization_id AND t.attempt_id = NEW.attempt_id AND t.started_at IS NOT NULL AND t.state >= 7) THEN
        RAISE EXCEPTION 'o envio so fecha depois que a tentativa que iniciou terminou' USING ERRCODE = '23514';
    END IF;
    IF NEW.lines <> (SELECT count(*) FROM public.remote_attempt_log_lines l WHERE l.attempt_id = NEW.attempt_id) THEN
        RAISE EXCEPTION 'o fechamento diz quantas linhas chegaram' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempt_log_closures_on_insert BEFORE INSERT ON public.remote_attempt_log_closures
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_log_closure_on_insert();
CREATE TRIGGER trg_remote_attempt_log_closures_prevent_update BEFORE UPDATE ON public.remote_attempt_log_closures
    FOR EACH ROW EXECUTE FUNCTION public.prevent_update();
CREATE TRIGGER trg_remote_attempt_log_closures_prevent_truncate BEFORE TRUNCATE ON public.remote_attempt_log_closures
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 3. RLS (padrao da 025/030/035/042) ===
DO $$
DECLARE
    ctx_req CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.organization_id'', true), '''')::uuid';
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['remote_attempt_log_lines', 'remote_attempt_log_closures'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_request_context ON public.%I AS PERMISSIVE FOR ALL TO nekkora_platform, nekkora_ingestion USING (%s) WITH CHECK (%s)',
                       t, ctx_req, ctx_req);
    END LOOP;
END;
$$;

-- === 4. Privilegios: a maquina envia e fecha; as pessoas so leem ===
REVOKE ALL ON public.remote_attempt_log_lines, public.remote_attempt_log_closures FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_log_line_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_log_lines_after_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_log_line_on_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_log_closure_on_insert() FROM PUBLIC;
GRANT SELECT ON public.remote_attempt_log_lines, public.remote_attempt_log_closures TO nekkora_platform;
GRANT SELECT, INSERT ON public.remote_attempt_log_lines, public.remote_attempt_log_closures TO nekkora_ingestion;

-- === 5. Invariantes ===
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['public.remote_attempt_log_lines', 'public.remote_attempt_log_closures'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = t::regclass AND c.relrowsecurity AND c.relforcerowsecurity) THEN
            RAISE EXCEPTION '051: RLS ausente em %', t;
        END IF;
        IF has_table_privilege('nekkora_platform', t, 'INSERT, UPDATE, DELETE') OR has_table_privilege('nekkora_ingestion', t, 'UPDATE, DELETE') THEN
            RAISE EXCEPTION '051: privilegio alem do previsto em %', t;
        END IF;
    END LOOP;
END;
$$;

COMMIT;
