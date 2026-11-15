-- Nekkora Platform - resultados de uma tentativa de execucao remota
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T03, parte b). Contrato: docs/platform-client-execucao-remota-001/t03b-resultados.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 046. Numero 050 conferido em 15/11 contra a dev e os PRs abertos. As ferramentas
-- do laboratorio e da homologacao exigem numeracao contigua: se outra 050 entrar antes, esta e renumerada sem mudar o conteudo.
--
-- O QUE FAZ
--   1. remote_attempt_results: os METADADOS de cada arquivo de resultado que a maquina enviou de uma tentativa que INICIOU:
--      numero (1 a 50), nome relativo a pasta de saida, tamanho, SHA-256 e quando chegou. Os bytes ficam no armazenamento de
--      resultados (pasta propria), enderecados pelo SHA-256, por organizacao. removed_at marca a remocao dos bytes pela
--      retencao: o metadado fica.
--   2. remote_attempt_result_closures: o fechamento do envio - quantos arquivos a maquina enviou e quantos deixou de enviar
--      por limite. Sem fechamento, o envio ainda nao terminou (ou nao comecou).
--
--   3. Ato 12 na trilha da automacao: uma pessoa baixou um arquivo de resultado.
--
-- LIMITES (pontos de partida de laboratorio, repetidos na aplicacao, que pode ser configurada para MENOS):
--   50 arquivos por tentativa; 20 MiB por arquivo; 50 MiB no total por tentativa.
--
-- O QUE NAO GUARDA: caminho absoluto, nome de usuario, conteudo do arquivo. O nome e relativo, sem "..", sem barra invertida,
-- sem caractere de controle.
--
-- O QUE NAO FAZ: nao muda a tentativa (o resultado do robo e o estado dela; arquivo que falta ou excede limite nao o altera);
-- nao apaga nada (a retencao e uma tarefa propria).
--
-- DADOS EXISTENTES: nenhuma linha e alterada. As tabelas novas nascem vazias.
--
-- REVERSAO (manual; so enquanto as tabelas estiverem vazias):
--   BEGIN;
--   DROP TABLE public.remote_attempt_result_closures;
--   DROP TABLE public.remote_attempt_results;
--   DROP FUNCTION public.remote_attempt_result_on_insert();
--   DROP FUNCTION public.remote_attempt_result_on_update();
--   DROP FUNCTION public.remote_attempt_result_closure_on_insert();
--   ALTER TABLE public.automation_admin_events DROP CONSTRAINT automation_admin_events_act_check;
--   ALTER TABLE public.automation_admin_events ADD CONSTRAINT automation_admin_events_act_check CHECK (act BETWEEN 1 AND 11);
--   COMMIT;
--
-- SEM ELEVACAO: tabelas novas e gatilhos SECURITY INVOKER. Nenhum papel novo, nenhuma funcao SECURITY DEFINER.

BEGIN;

DO $$
BEGIN
    IF to_regclass('public.remote_attempts') IS NULL THEN
        RAISE EXCEPTION '050 exige a 046 aplicada';
    END IF;
    IF to_regclass('public.remote_attempt_results') IS NOT NULL THEN
        RAISE EXCEPTION '050: remote_attempt_results ja existe';
    END IF;
END;
$$;

-- === 1. Resultados ===
CREATE TABLE public.remote_attempt_results (
    attempt_id       UUID NOT NULL,
    result_number    SMALLINT NOT NULL CHECK (result_number BETWEEN 1 AND 50),
    organization_id  UUID NOT NULL,
    file_name        TEXT NOT NULL CHECK (char_length(file_name) BETWEEN 1 AND 200 AND file_name !~ '[\x01-\x1f\x7f\\]'
                                          AND file_name !~ '(^|/)\.\.?(/|$)' AND file_name !~ '^/' AND file_name !~ '/$' AND file_name !~ '//'
                                          AND file_name !~ '^[A-Za-z]:'),
    size_bytes       BIGINT NOT NULL CHECK (size_bytes BETWEEN 0 AND 20971520),
    sha256           TEXT NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    received_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    removed_at       TIMESTAMPTZ,
    PRIMARY KEY (attempt_id, result_number),
    UNIQUE (attempt_id, file_name),
    FOREIGN KEY (organization_id, attempt_id) REFERENCES public.remote_attempts (organization_id, attempt_id) ON DELETE RESTRICT,
    CHECK (removed_at IS NULL OR removed_at >= received_at)
);
COMMENT ON TABLE public.remote_attempt_results IS
    'Metadados dos arquivos de resultado que a maquina enviou de uma tentativa que iniciou (050). Os bytes ficam no armazenamento de '
    'resultados, pelo SHA-256. removed_at: os bytes foram removidos pela retencao; o metadado fica.';
COMMENT ON COLUMN public.remote_attempt_results.file_name IS
    'Nome RELATIVO a pasta de saida da execucao, com / como separador. Nunca um caminho absoluto.';

-- So de tentativa que INICIOU; no maximo 50 MiB no total; nada depois do fechamento.
CREATE FUNCTION public.remote_attempt_result_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.received_at := clock_timestamp();
    IF NEW.removed_at IS NOT NULL THEN
        RAISE EXCEPTION 'o resultado nasce presente' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.remote_attempts t
                    WHERE t.organization_id = NEW.organization_id AND t.attempt_id = NEW.attempt_id AND t.started_at IS NOT NULL) THEN
        RAISE EXCEPTION 'resultado so de tentativa que iniciou' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM public.remote_attempt_result_closures c WHERE c.attempt_id = NEW.attempt_id) THEN
        RAISE EXCEPTION 'o envio de resultados desta tentativa ja foi fechado' USING ERRCODE = '23514';
    END IF;
    IF coalesce((SELECT sum(r.size_bytes) FROM public.remote_attempt_results r WHERE r.attempt_id = NEW.attempt_id), 0) + NEW.size_bytes > 52428800 THEN
        RAISE EXCEPTION 'resultados limitados a 50 MiB por tentativa' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempt_results_on_insert BEFORE INSERT ON public.remote_attempt_results
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_result_on_insert();

-- A unica mudanca permitida: marcar a remocao dos bytes (uma vez).
CREATE FUNCTION public.remote_attempt_result_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.attempt_id <> OLD.attempt_id OR NEW.result_number <> OLD.result_number OR NEW.organization_id <> OLD.organization_id
       OR NEW.file_name <> OLD.file_name OR NEW.size_bytes <> OLD.size_bytes OR NEW.sha256 <> OLD.sha256 OR NEW.received_at <> OLD.received_at
       OR OLD.removed_at IS NOT NULL OR NEW.removed_at IS NULL THEN
        RAISE EXCEPTION 'o resultado so muda para registrar a remocao dos bytes' USING ERRCODE = '42501';
    END IF;
    NEW.removed_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempt_results_on_update BEFORE UPDATE ON public.remote_attempt_results
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_result_on_update();
CREATE TRIGGER trg_remote_attempt_results_prevent_truncate BEFORE TRUNCATE ON public.remote_attempt_results
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 2. Fechamento do envio ===
CREATE TABLE public.remote_attempt_result_closures (
    attempt_id        UUID PRIMARY KEY,
    organization_id   UUID NOT NULL,
    sent              SMALLINT NOT NULL CHECK (sent BETWEEN 0 AND 50),
    skipped_by_limit  INTEGER NOT NULL CHECK (skipped_by_limit BETWEEN 0 AND 1000000),
    closed_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (organization_id, attempt_id) REFERENCES public.remote_attempts (organization_id, attempt_id) ON DELETE RESTRICT
);
COMMENT ON TABLE public.remote_attempt_result_closures IS
    'Fechamento do envio de resultados de uma tentativa (050): quantos arquivos a maquina enviou e quantos deixou de enviar por limite. '
    'Sem linha aqui, o envio nao terminou. Nao altera o resultado da tentativa.';

CREATE FUNCTION public.remote_attempt_result_closure_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.closed_at := clock_timestamp();
    IF NOT EXISTS (SELECT 1 FROM public.remote_attempts t
                    WHERE t.organization_id = NEW.organization_id AND t.attempt_id = NEW.attempt_id AND t.started_at IS NOT NULL AND t.state >= 7) THEN
        RAISE EXCEPTION 'o envio so fecha depois que a tentativa que iniciou terminou' USING ERRCODE = '23514';
    END IF;
    IF NEW.sent <> (SELECT count(*) FROM public.remote_attempt_results r WHERE r.attempt_id = NEW.attempt_id) THEN
        RAISE EXCEPTION 'o fechamento diz quantos arquivos chegaram' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempt_result_closures_on_insert BEFORE INSERT ON public.remote_attempt_result_closures
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_result_closure_on_insert();
CREATE TRIGGER trg_remote_attempt_result_closures_prevent_update BEFORE UPDATE ON public.remote_attempt_result_closures
    FOR EACH ROW EXECUTE FUNCTION public.prevent_update();
CREATE TRIGGER trg_remote_attempt_result_closures_prevent_truncate BEFORE TRUNCATE ON public.remote_attempt_result_closures
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 2b. Trilha da automacao: ato 12, "resultado de execucao remota baixado por uma pessoa" ===
ALTER TABLE public.automation_admin_events DROP CONSTRAINT automation_admin_events_act_check;
ALTER TABLE public.automation_admin_events ADD CONSTRAINT automation_admin_events_act_check CHECK (act BETWEEN 1 AND 12);
COMMENT ON COLUMN public.automation_admin_events.act IS
    '1..9 conforme as migrations anteriores; 10=dependencias de execucao declaradas (045); 11=Python escolhido para uma maquina (047); '
    '12=resultado de execucao remota baixado (050)';

-- === 3. RLS (padrao da 025/030/035/042) ===
DO $$
DECLARE
    ctx_req CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.organization_id'', true), '''')::uuid';
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['remote_attempt_results', 'remote_attempt_result_closures'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_request_context ON public.%I AS PERMISSIVE FOR ALL TO nekkora_platform, nekkora_ingestion USING (%s) WITH CHECK (%s)',
                       t, ctx_req, ctx_req);
    END LOOP;
END;
$$;

-- === 4. Privilegios: a maquina envia e fecha; as pessoas so leem ===
REVOKE ALL ON public.remote_attempt_results, public.remote_attempt_result_closures FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_result_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_result_on_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_result_closure_on_insert() FROM PUBLIC;
GRANT SELECT ON public.remote_attempt_results, public.remote_attempt_result_closures TO nekkora_platform;
GRANT SELECT, INSERT ON public.remote_attempt_results, public.remote_attempt_result_closures TO nekkora_ingestion;

-- === 5. Invariantes ===
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['public.remote_attempt_results', 'public.remote_attempt_result_closures'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = t::regclass AND c.relrowsecurity AND c.relforcerowsecurity) THEN
            RAISE EXCEPTION '050: RLS ausente em %', t;
        END IF;
        IF has_table_privilege('nekkora_platform', t, 'INSERT, UPDATE, DELETE') OR has_table_privilege('nekkora_ingestion', t, 'UPDATE, DELETE') THEN
            RAISE EXCEPTION '050: privilegio alem do previsto em %', t;
        END IF;
    END LOOP;
END;
$$;

COMMIT;
