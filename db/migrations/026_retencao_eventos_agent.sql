-- Nekkora Platform - retencao e consumo diario dos eventos do Agent (agent_run_events, 025) pelos mecanismos da 010/011/012:
-- linha-barreira na ingestao, fechamento verificavel do dia, reconsolidacao e expurgo por redacao (nunca DELETE).
-- Contrato: docs/relatorios/2026-10-28-platform-agent-contrato.md, sec. 8.1 e 11. Decisao da titular: D-PA-6 (28/10).
--
-- CANDIDATA ate a aprovacao do relatorio da suite db/tests/retencao-agent-026.
--
-- Codigos novos (SMALLINT/INTEGER, explicitos e permanentes - nao reordenar nem reutilizar):
--   organization_usage_daily.resource_code                 : +6=AGENT_EVENT_BYTES_DAILY
--   organization_retention_policy_revisions.category        : +3=AGENT_RUN_EVENTS
--   organization_purge_runs.category                        : +3=AGENT_RUN_EVENTS
--   agent_run_events.purge_category                         : fixo 3 (discriminador da FK com organization_purge_runs)
--
-- Regras centrais:
--   - received_at de agent_run_events passa a ser atribuido SOB A BARREIRA (assign_received_at_and_lock_barrier, recurso 6),
--     por gatilho, como nos eventos e logs da 011/012: um dia fechado nunca recebe evento novo, e o fechamento nunca conta
--     um dia com gravacao em voo. O valor enviado pelo chamador e ignorado.
--   - Expurgo = redacao: envelope, step_id, studio_step_id, error_code e timestamp_utc viram NULL. Ficam para sempre a
--     identidade (agent_run_id + sequence), o content_sha256 (o reenvio de um evento expurgado continua reconhecido como
--     DUPLICATE ou CONFLICT), a classificacao (source, event_code, severity, category) e os instantes observed_utc e
--     received_at. So dias CLOSED, so com politica FINITA da categoria 3 vigente no recebimento, so depois do prazo.
--   - O expurgo NAO mexe em agent_runs: stored_through, settled_through e os demais contadores nunca diminuem. A completude
--     continua a da entrega; o expurgo aparece como dimensao propria (retencao), nunca como perda na origem.
--   - Ausencia de politica da categoria 3 = expurgo nao autorizado (010). O EXECUTE de purge_expired_records continua SEM
--     ser concedido (011): a ativacao do expurgo e decisao operacional propria. Para a categoria 3, a pre-condicao que a 011
--     declarou (identidade e fingerprint estaveis) esta satisfeita pelo content_sha256 canonico da 025.
--   - Lote do expurgo da categoria 3 selecionado UMA vez (CTE materializada). Achado desta suite: a forma da 011
--     ("IN (SELECT ... LIMIT n FOR UPDATE)") pode passar de n sob um plano de laco aninhado. As categorias 1 e 2 mantem o
--     corpo da 012 (sem EXECUTE concedido); a mesma correcao fica recomendada antes de qualquer ativacao (relatorio, D-RET-2).
--   - Nenhum papel, dono, funcao SECURITY DEFINER ou concessao de EXECUTE novos. As tres funcoes de manutencao (close,
--     reconsolidate, purge) ganham o ramo do recurso 6 / categoria 3 com o corpo da 012 inalterado nos outros ramos.
--
-- ELEVACAO: exige a janela de SUPERUSER (como 011-013, 016-020, 022-024). Motivo medido (suite, caso B0): ALTER TABLE em
-- organization_usage_daily e organization_purge_runs (dono nekkora_retention_tables_owner, 011) e CREATE OR REPLACE de
-- close_usage_day, reconsolidate_usage_day e purge_expired_records (dono nekkora_retention_owner, 011/012). O migrador sem
-- atributo nao e dono nem membro desses papeis.
--
-- Depende de 001-025. Atomica: uma transacao.

BEGIN;

-- === 1. Pre-condicao: 025 aplicada; 026 ausente ===
DO $$
BEGIN
    IF to_regclass('public.agent_run_events') IS NULL OR to_regprocedure('public.agent_run_on_insert()') IS NULL THEN
        RAISE EXCEPTION '026 exige a 025 aplicada';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'public.agent_run_events'::regclass AND attname = 'purged_at' AND NOT attisdropped)
       OR to_regprocedure('public.agent_run_event_received_at()') IS NOT NULL THEN
        RAISE EXCEPTION '026: retencao dos eventos do Agent ja aplicada';
    END IF;
END;
$$;

-- === 2. Catalogos: recurso 6 e categoria 3 ===
ALTER TABLE public.organization_usage_daily DROP CONSTRAINT organization_usage_daily_resource_code_check;
ALTER TABLE public.organization_usage_daily ADD CONSTRAINT organization_usage_daily_resource_code_check CHECK (resource_code IN (4, 5, 6));
COMMENT ON COLUMN public.organization_usage_daily.resource_code IS '4=LOG_BYTES_DAILY, 5=EVENT_BYTES_DAILY, 6=AGENT_EVENT_BYTES_DAILY (026: eventos do Agent, agent_run_events)';

ALTER TABLE public.organization_retention_policy_revisions DROP CONSTRAINT organization_retention_policy_revisions_category_check;
ALTER TABLE public.organization_retention_policy_revisions ADD CONSTRAINT organization_retention_policy_revisions_category_check
    CHECK (category IN (1, 2, 3));
COMMENT ON COLUMN public.organization_retention_policy_revisions.category IS '1=EXECUTION_EVENTS, 2=LOGS, 3=AGENT_RUN_EVENTS (026)';

ALTER TABLE public.organization_purge_runs DROP CONSTRAINT organization_purge_runs_category_check;
ALTER TABLE public.organization_purge_runs ADD CONSTRAINT organization_purge_runs_category_check CHECK (category IN (1, 2, 3));
COMMENT ON COLUMN public.organization_purge_runs.category IS '1=EXECUTION_EVENTS, 2=LOGS, 3=AGENT_RUN_EVENTS (026) - mesmo catalogo de organization_retention_policy_revisions';

-- === 3. agent_run_events: colunas de expurgo (mesmo desenho de execution_events/logs na 011) ===
ALTER TABLE public.agent_run_events ADD COLUMN purge_category SMALLINT NOT NULL DEFAULT 3 CHECK (purge_category = 3);
ALTER TABLE public.agent_run_events ADD COLUMN purged_at TIMESTAMPTZ;
ALTER TABLE public.agent_run_events ADD COLUMN purged_by_user_id UUID REFERENCES public.users (user_id) ON DELETE RESTRICT;
ALTER TABLE public.agent_run_events ADD COLUMN purge_run_id UUID;
ALTER TABLE public.agent_run_events ADD CONSTRAINT agent_run_events_purge_pair_check
    CHECK ((purged_at IS NULL) = (purged_by_user_id IS NULL) AND (purged_at IS NULL) = (purge_run_id IS NULL));
-- o envelope so falta quando o evento foi expurgado; expurgado, os campos redigidos ficam todos nulos
ALTER TABLE public.agent_run_events ADD CONSTRAINT agent_run_events_envelope_purge_check
    CHECK ((purged_at IS NULL AND envelope IS NOT NULL)
           OR (purged_at IS NOT NULL AND envelope IS NULL AND step_id IS NULL AND studio_step_id IS NULL AND error_code IS NULL
               AND timestamp_utc IS NULL));
ALTER TABLE public.agent_run_events ALTER COLUMN envelope DROP NOT NULL;
ALTER TABLE public.agent_run_events ADD CONSTRAINT agent_run_events_purge_run_fkey
    FOREIGN KEY (purge_run_id, organization_id, purge_category)
    REFERENCES public.organization_purge_runs (purge_run_id, organization_id, category)
    ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
COMMENT ON COLUMN public.agent_run_events.purge_category IS 'fixo 3=AGENT_RUN_EVENTS - discriminador da FK composta com organization_purge_runs';
COMMENT ON COLUMN public.agent_run_events.purged_at IS 'expurgo e sempre UPDATE (redacao), nunca DELETE: envelope e campos de etapa viram NULL; identidade, content_sha256, classificacao e instantes ficam';
COMMENT ON COLUMN public.agent_run_events.received_at IS 'atribuido pelo servidor SOB A BARREIRA do recurso 6 (026, gatilho agent_run_event_received_at); o valor do chamador e ignorado';

-- === 4. received_at sob a barreira (recurso 6), por gatilho ===
-- SECURITY INVOKER: quem insere (nekkora_ingestion) ja tem EXECUTE em assign_received_at_and_lock_barrier (012), que confere
-- o contexto da organizacao do chamador contra a linha. Os campos de expurgo nascem nulos.
CREATE FUNCTION public.agent_run_event_received_at() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.purged_at IS NOT NULL OR NEW.purged_by_user_id IS NOT NULL OR NEW.purge_run_id IS NOT NULL THEN
        RAISE EXCEPTION 'evento do Agent nasce sem expurgo' USING ERRCODE = '23514';
    END IF;
    NEW.received_at := public.assign_received_at_and_lock_barrier(NEW.organization_id, 6);
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_agent_run_events_received_at BEFORE INSERT ON public.agent_run_events
    FOR EACH ROW EXECUTE FUNCTION public.agent_run_event_received_at();
REVOKE ALL ON FUNCTION public.agent_run_event_received_at() FROM PUBLIC;

-- === 5. Redacao: a unica atualizacao aceita em agent_run_events ===
-- enforce_redaction_or_reject (011) ganha o ramo de agent_run_events (recurso 6); os ramos de execution_events e logs sao os da
-- 011, inalterados. O search_path fixado pela 012 (ALTER FUNCTION ... SET) e mantido na redefinicao.
CREATE OR REPLACE FUNCTION public.enforce_redaction_or_reject() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    shape_ok BOOLEAN;
    day_status SMALLINT;
    resource_code_for_check INTEGER;
    rp RECORD;
BEGIN
    IF TG_TABLE_NAME = 'execution_events' THEN
        shape_ok := (
            OLD.purged_at IS NULL AND NEW.purged_at IS NOT NULL
            AND NEW.payload IS NULL
            AND NEW.purge_run_id IS NOT NULL
            AND NEW.purged_by_user_id IS NOT NULL
            AND NEW.event_id = OLD.event_id
            AND NEW.organization_id = OLD.organization_id
            AND NEW.workspace_id = OLD.workspace_id
            AND NEW.execution_id = OLD.execution_id
            AND NEW.agent_id = OLD.agent_id
            AND NEW.sequence = OLD.sequence
            AND NEW.event_type = OLD.event_type
            AND NEW.occurred_at = OLD.occurred_at
            AND NEW.received_at = OLD.received_at
            AND NEW.fingerprint = OLD.fingerprint
            AND NEW.purge_category = OLD.purge_category
        );
        resource_code_for_check := 5;
    ELSIF TG_TABLE_NAME = 'logs' THEN
        shape_ok := (
            OLD.purged_at IS NULL AND NEW.purged_at IS NOT NULL
            AND NEW.payload IS NULL
            AND NEW.purge_run_id IS NOT NULL
            AND NEW.purged_by_user_id IS NOT NULL
            AND NEW.log_id = OLD.log_id
            AND NEW.organization_id = OLD.organization_id
            AND NEW.workspace_id IS NOT DISTINCT FROM OLD.workspace_id
            AND NEW.agent_id = OLD.agent_id
            AND NEW.worker_agent_assignment_id IS NOT DISTINCT FROM OLD.worker_agent_assignment_id
            AND NEW.execution_id IS NOT DISTINCT FROM OLD.execution_id
            AND NEW.channel = OLD.channel
            AND NEW.occurred_at = OLD.occurred_at
            AND NEW.received_at = OLD.received_at
            AND NEW.fingerprint = OLD.fingerprint
            AND NEW.purge_category = OLD.purge_category
        );
        resource_code_for_check := 4;
    ELSIF TG_TABLE_NAME = 'agent_run_events' THEN
        shape_ok := (
            OLD.purged_at IS NULL AND NEW.purged_at IS NOT NULL
            AND NEW.envelope IS NULL AND NEW.step_id IS NULL AND NEW.studio_step_id IS NULL AND NEW.error_code IS NULL
            AND NEW.timestamp_utc IS NULL
            AND NEW.purge_run_id IS NOT NULL
            AND NEW.purged_by_user_id IS NOT NULL
            AND NEW.organization_id = OLD.organization_id
            AND NEW.agent_run_id = OLD.agent_run_id
            AND NEW.sequence = OLD.sequence
            AND NEW.content_sha256 = OLD.content_sha256
            AND NEW.source = OLD.source
            AND NEW.event_code = OLD.event_code
            AND NEW.severity = OLD.severity
            AND NEW.category = OLD.category
            AND NEW.observed_utc = OLD.observed_utc
            AND NEW.received_at = OLD.received_at
            AND NEW.purge_category = OLD.purge_category
        );
        resource_code_for_check := 6;
    ELSE
        RAISE EXCEPTION 'trigger de redacao usada em tabela inesperada (%)', TG_TABLE_NAME;
    END IF;

    IF NOT shape_ok THEN
        RAISE EXCEPTION 'atualizacao nao permitida em % (so a transicao de expurgo e aceita)', TG_TABLE_NAME;
    END IF;

    SELECT status INTO day_status FROM public.organization_usage_daily
    WHERE organization_id = NEW.organization_id AND resource_code = resource_code_for_check
      AND usage_date = (NEW.received_at AT TIME ZONE 'UTC')::date;

    IF day_status IS DISTINCT FROM 2 THEN
        RAISE EXCEPTION 'dia ainda nao fechado, expurgo nao autorizado (received_at=%)', NEW.received_at;
    END IF;

    SELECT * INTO rp FROM public.get_retention_policy_at(NEW.organization_id, NEW.purge_category, NEW.received_at);
    IF NOT FOUND OR rp.retention_mode <> 1 OR clock_timestamp() < NEW.received_at + (rp.retention_days * INTERVAL '1 day') THEN
        RAISE EXCEPTION 'prazo de retencao ainda nao vencido para este registro (received_at=%)', NEW.received_at;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER trg_agent_run_events_prevent_update ON public.agent_run_events;
CREATE TRIGGER trg_agent_run_events_enforce_redaction_or_reject BEFORE UPDATE ON public.agent_run_events
    FOR EACH ROW EXECUTE FUNCTION public.enforce_redaction_or_reject();

-- === 6. RLS e privilegios do dono das funcoes de manutencao ===
-- So nekkora_retention_owner (dono de close/reconsolidate/purge), pelo contexto de funcao da 012. Nenhum outro papel.
CREATE POLICY tenant_function_context ON public.agent_run_events AS PERMISSIVE FOR ALL TO nekkora_retention_owner
    USING (organization_id = nullif(current_setting('nekkora.fn_organization_id', true), '')::uuid)
    WITH CHECK (organization_id = nullif(current_setting('nekkora.fn_organization_id', true), '')::uuid);
GRANT SELECT ON public.agent_run_events TO nekkora_retention_owner;
GRANT UPDATE (envelope, step_id, studio_step_id, error_code, timestamp_utc, purged_at, purged_by_user_id, purge_run_id)
    ON public.agent_run_events TO nekkora_retention_owner;

-- === 7. Funcoes de manutencao: ramo do recurso 6 / categoria 3 (corpo da 012 inalterado nos demais) ===

CREATE OR REPLACE FUNCTION public.close_usage_day(p_organization_id UUID, p_resource_code INTEGER, p_usage_date DATE)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    iso TEXT;
    today DATE;
    computed_records BIGINT;
    computed_bytes BIGINT;
    row_status SMALLINT;
    v_category INTEGER;
    v_prev_fn_org TEXT;
BEGIN
    IF p_organization_id IS NULL THEN
        RAISE EXCEPTION 'organization_id obrigatorio para fechamento' USING ERRCODE = '22004';
    END IF;
    v_prev_fn_org := current_setting('nekkora.fn_organization_id', true);
    PERFORM set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    BEGIN
        v_category := CASE p_resource_code
            WHEN 5 THEN 1
            WHEN 4 THEN 2
            WHEN 6 THEN 3
            ELSE NULL
        END;
        IF v_category IS NULL THEN
            RAISE EXCEPTION 'resource_code invalido para fechamento de consumo diario (%)', p_resource_code;
        END IF;
        PERFORM public.assert_single_category_per_transaction(p_organization_id, v_category);

        iso := current_setting('transaction_isolation');
        IF iso <> 'read committed' THEN
            RAISE EXCEPTION 'fechamento exige transaction_isolation=read committed (atual=%)', iso;
        END IF;

        today := (clock_timestamp() AT TIME ZONE 'UTC')::date;
        IF p_usage_date >= today THEN
            RAISE EXCEPTION 'fechamento so processa dias estritamente passados (usage_date=%, hoje=%)', p_usage_date, today;
        END IF;

        INSERT INTO public.organization_usage_daily (organization_id, resource_code, usage_date, records_count, bytes_count, status, consolidated_at)
        VALUES (p_organization_id, p_resource_code, p_usage_date, 0, 0, 1, NULL)
        ON CONFLICT (organization_id, resource_code, usage_date) DO NOTHING;

        SELECT status INTO row_status FROM public.organization_usage_daily
        WHERE organization_id = p_organization_id AND resource_code = p_resource_code AND usage_date = p_usage_date
        FOR UPDATE;

        IF row_status = 2 THEN
            RAISE EXCEPTION 'dia ja esta fechado (organization_id=%, resource_code=%, usage_date=%)', p_organization_id, p_resource_code, p_usage_date;
        END IF;

        IF p_resource_code = 5 THEN
            SELECT count(*), coalesce(sum(octet_length(payload::text)), 0) INTO computed_records, computed_bytes
            FROM public.execution_events
            WHERE organization_id = p_organization_id AND (received_at AT TIME ZONE 'UTC')::date = p_usage_date;
        ELSIF p_resource_code = 6 THEN
            SELECT count(*), coalesce(sum(octet_length(envelope::text)), 0) INTO computed_records, computed_bytes
            FROM public.agent_run_events
            WHERE organization_id = p_organization_id AND (received_at AT TIME ZONE 'UTC')::date = p_usage_date;
        ELSE
            SELECT count(*), coalesce(sum(octet_length(payload::text)), 0) INTO computed_records, computed_bytes
            FROM public.logs
            WHERE organization_id = p_organization_id AND (received_at AT TIME ZONE 'UTC')::date = p_usage_date;
        END IF;

        UPDATE public.organization_usage_daily
        SET records_count = computed_records, bytes_count = computed_bytes, status = 2, consolidated_at = clock_timestamp()
        WHERE organization_id = p_organization_id AND resource_code = p_resource_code AND usage_date = p_usage_date;
    EXCEPTION WHEN OTHERS THEN
        PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
        RAISE;
    END;
    PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
END;
$$;

CREATE OR REPLACE FUNCTION public.reconsolidate_usage_day(p_organization_id UUID, p_resource_code INTEGER, p_usage_date DATE)
RETURNS TABLE (out_records_count BIGINT, out_bytes_count BIGINT, out_consolidated_at TIMESTAMPTZ)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_category INTEGER;
    v_iso TEXT;
    v_today DATE;
    v_row_status SMALLINT;
    v_records BIGINT;
    v_bytes BIGINT;
    v_at TIMESTAMPTZ;
    v_prev_fn_org TEXT;
BEGIN
    IF p_organization_id IS NULL OR p_resource_code IS NULL OR p_usage_date IS NULL THEN
        RAISE EXCEPTION 'reconsolidacao exige organization_id, resource_code e usage_date' USING ERRCODE = '22004';
    END IF;
    v_prev_fn_org := current_setting('nekkora.fn_organization_id', true);
    PERFORM set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    BEGIN
        v_category := CASE p_resource_code WHEN 5 THEN 1 WHEN 4 THEN 2 WHEN 6 THEN 3 ELSE NULL END;
        IF v_category IS NULL THEN
            RAISE EXCEPTION 'resource_code invalido para reconsolidacao de consumo diario (%)', p_resource_code;
        END IF;
        PERFORM public.assert_single_category_per_transaction(p_organization_id, v_category);

        v_iso := current_setting('transaction_isolation');
        IF v_iso <> 'read committed' THEN
            RAISE EXCEPTION 'reconsolidacao exige transaction_isolation=read committed (atual=%)', v_iso;
        END IF;

        v_today := (clock_timestamp() AT TIME ZONE 'UTC')::date;
        IF p_usage_date > v_today THEN
            RAISE EXCEPTION 'reconsolidacao nao processa dia futuro (usage_date=%, hoje=%)', p_usage_date, v_today;
        END IF;

        INSERT INTO public.organization_usage_daily (organization_id, resource_code, usage_date, records_count, bytes_count, status, consolidated_at)
        VALUES (p_organization_id, p_resource_code, p_usage_date, 0, 0, 1, NULL)
        ON CONFLICT (organization_id, resource_code, usage_date) DO NOTHING;

        SELECT status INTO v_row_status FROM public.organization_usage_daily
        WHERE organization_id = p_organization_id AND resource_code = p_resource_code AND usage_date = p_usage_date
        FOR UPDATE;

        IF v_row_status = 2 THEN
            RAISE EXCEPTION 'dia ja esta fechado, reconsolidacao nao permitida (organization_id=%, resource_code=%, usage_date=%)', p_organization_id, p_resource_code, p_usage_date;
        END IF;

        IF p_resource_code = 5 THEN
            SELECT count(*), coalesce(sum(octet_length(payload::text)), 0) INTO v_records, v_bytes
            FROM public.execution_events
            WHERE organization_id = p_organization_id AND (received_at AT TIME ZONE 'UTC')::date = p_usage_date;
        ELSIF p_resource_code = 6 THEN
            SELECT count(*), coalesce(sum(octet_length(envelope::text)), 0) INTO v_records, v_bytes
            FROM public.agent_run_events
            WHERE organization_id = p_organization_id AND (received_at AT TIME ZONE 'UTC')::date = p_usage_date;
        ELSE
            SELECT count(*), coalesce(sum(octet_length(payload::text)), 0) INTO v_records, v_bytes
            FROM public.logs
            WHERE organization_id = p_organization_id AND (received_at AT TIME ZONE 'UTC')::date = p_usage_date;
        END IF;

        UPDATE public.organization_usage_daily
        SET records_count = v_records, bytes_count = v_bytes, consolidated_at = clock_timestamp()
        WHERE organization_id = p_organization_id AND resource_code = p_resource_code AND usage_date = p_usage_date
        RETURNING consolidated_at INTO v_at;
    EXCEPTION WHEN OTHERS THEN
        PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
        RAISE;
    END;
    PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
    out_records_count := v_records;
    out_bytes_count := v_bytes;
    out_consolidated_at := v_at;
    RETURN NEXT;
END;
$$;

CREATE OR REPLACE FUNCTION public.purge_expired_records(
    p_organization_id UUID,
    p_category INTEGER,
    p_purged_by_user_id UUID,
    p_batch_limit INTEGER
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    run_id UUID;
    affected INTEGER;
    v_prev_fn_org TEXT;
BEGIN
    IF p_organization_id IS NULL THEN
        RAISE EXCEPTION 'organization_id obrigatorio para expurgo' USING ERRCODE = '22004';
    END IF;
    v_prev_fn_org := current_setting('nekkora.fn_organization_id', true);
    PERFORM set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    BEGIN
        PERFORM public.assert_single_category_per_transaction(p_organization_id, p_category);

        IF p_batch_limit IS NULL OR p_batch_limit <= 0 THEN
            RAISE EXCEPTION 'lote de expurgo deve ser positivo (recebido=%)', p_batch_limit;
        END IF;
        IF p_batch_limit > 1000 THEN
            RAISE EXCEPTION 'lote de expurgo excede o teto permitido (recebido=%, teto=1000)', p_batch_limit;
        END IF;

        IF p_category NOT IN (1, 2, 3) THEN
            RAISE EXCEPTION 'categoria invalida (%)', p_category;
        END IF;

        run_id := gen_random_uuid();

        IF p_category = 1 THEN
            UPDATE public.execution_events e
            SET payload = NULL, purged_at = clock_timestamp(), purged_by_user_id = p_purged_by_user_id, purge_run_id = run_id
            WHERE e.event_id IN (
                SELECT ev.event_id
                FROM public.execution_events ev
                JOIN public.organization_usage_daily d
                    ON d.organization_id = ev.organization_id AND d.resource_code = 5
                    AND d.usage_date = (ev.received_at AT TIME ZONE 'UTC')::date
                CROSS JOIN LATERAL public.get_retention_policy_at(ev.organization_id, 1, ev.received_at) rp
                WHERE ev.organization_id = p_organization_id
                  AND ev.purged_at IS NULL
                  AND d.status = 2
                  AND rp.retention_mode = 1
                  AND clock_timestamp() >= ev.received_at + (rp.retention_days * INTERVAL '1 day')
                ORDER BY ev.received_at
                LIMIT p_batch_limit
                FOR UPDATE OF ev
            );
        ELSIF p_category = 3 THEN
            -- eventos do Agent: redacao do envelope e dos campos de etapa; identidade, content_sha256, classificacao e
            -- instantes ficam (reenvio continua reconhecido); agent_runs nao muda (contadores nunca diminuem).
            -- O lote e selecionado UMA vez (CTE materializada): com "IN (SELECT ... LIMIT n FOR UPDATE)" o planejador pode
            -- reexecutar a subconsulta a cada linha externa (laco aninhado, comum sob RLS); a cada reexecucao as linhas ja
            -- alteradas por este comando sao puladas pelo LockRows e outras entram - o lote passaria de n (suite, caso C3).
            WITH alvo AS MATERIALIZED (
                SELECT ev.agent_run_id, ev.sequence
                FROM public.agent_run_events ev
                JOIN public.organization_usage_daily d
                    ON d.organization_id = ev.organization_id AND d.resource_code = 6
                    AND d.usage_date = (ev.received_at AT TIME ZONE 'UTC')::date
                CROSS JOIN LATERAL public.get_retention_policy_at(ev.organization_id, 3, ev.received_at) rp
                WHERE ev.organization_id = p_organization_id
                  AND ev.purged_at IS NULL
                  AND d.status = 2
                  AND rp.retention_mode = 1
                  AND clock_timestamp() >= ev.received_at + (rp.retention_days * INTERVAL '1 day')
                ORDER BY ev.received_at, ev.agent_run_id, ev.sequence
                LIMIT p_batch_limit
                FOR UPDATE OF ev
            )
            UPDATE public.agent_run_events e
            SET envelope = NULL, step_id = NULL, studio_step_id = NULL, error_code = NULL, timestamp_utc = NULL,
                purged_at = clock_timestamp(), purged_by_user_id = p_purged_by_user_id, purge_run_id = run_id
            FROM alvo
            WHERE e.agent_run_id = alvo.agent_run_id AND e.sequence = alvo.sequence;
        ELSE
            UPDATE public.logs l
            SET payload = NULL, purged_at = clock_timestamp(), purged_by_user_id = p_purged_by_user_id, purge_run_id = run_id
            WHERE l.log_id IN (
                SELECT lg.log_id
                FROM public.logs lg
                JOIN public.organization_usage_daily d
                    ON d.organization_id = lg.organization_id AND d.resource_code = 4
                    AND d.usage_date = (lg.received_at AT TIME ZONE 'UTC')::date
                CROSS JOIN LATERAL public.get_retention_policy_at(lg.organization_id, 2, lg.received_at) rp
                WHERE lg.organization_id = p_organization_id
                  AND lg.purged_at IS NULL
                  AND d.status = 2
                  AND rp.retention_mode = 1
                  AND clock_timestamp() >= lg.received_at + (rp.retention_days * INTERVAL '1 day')
                ORDER BY lg.received_at
                LIMIT p_batch_limit
                FOR UPDATE OF lg
            );
        END IF;

        GET DIAGNOSTICS affected = ROW_COUNT;

        INSERT INTO public.organization_purge_runs (purge_run_id, organization_id, category, records_purged, occurred_at, purged_by_user_id)
        VALUES (run_id, p_organization_id, p_category, affected, clock_timestamp(), p_purged_by_user_id);
    EXCEPTION WHEN OTHERS THEN
        PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
        RAISE;
    END;
    PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
    RETURN affected;
END;
$$;

-- === 8. Invariantes ===
DO $$
DECLARE
    v_bad TEXT;
    v_n INTEGER;
BEGIN
    -- 1. donos, SECURITY DEFINER, search_path e EXECUTE das funcoes de manutencao: os da 011/012, sem concessao nova
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p
    WHERE p.oid IN ('public.close_usage_day(uuid,integer,date)'::regprocedure, 'public.reconsolidate_usage_day(uuid,integer,date)'::regprocedure,
                    'public.purge_expired_records(uuid,integer,uuid,integer)'::regprocedure,
                    'public.assign_received_at_and_lock_barrier(uuid,integer)'::regprocedure)
      AND (NOT p.prosecdef OR pg_get_userbyid(p.proowner) <> 'nekkora_retention_owner'
           OR NOT ('search_path=pg_catalog, pg_temp' = ANY (coalesce(p.proconfig, '{}'))));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '026: funcao de manutencao fora do padrao: %', v_bad; END IF;
    SELECT string_agg(r.rolname, ',' ORDER BY r.rolname) INTO v_bad FROM pg_roles r
    WHERE NOT r.rolsuper AND r.rolname <> 'nekkora_retention_owner'
      AND has_function_privilege(r.oid, 'public.purge_expired_records(uuid,integer,uuid,integer)', 'EXECUTE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '026: EXECUTE de purge_expired_records concedido: %', v_bad; END IF;
    SELECT string_agg(r.rolname, ',' ORDER BY r.rolname) INTO v_bad FROM pg_roles r
    WHERE NOT r.rolsuper AND r.rolname <> 'nekkora_retention_owner'
      AND (has_function_privilege(r.oid, 'public.close_usage_day(uuid,integer,date)', 'EXECUTE')
           OR has_function_privilege(r.oid, 'public.reconsolidate_usage_day(uuid,integer,date)', 'EXECUTE'));
    IF v_bad IS DISTINCT FROM 'nekkora_retention_executor' THEN RAISE EXCEPTION '026: EXECUTE de fechamento divergente: %', v_bad; END IF;

    -- 2. gatilho novo: SECURITY INVOKER, search_path fixo, do migrador, sem EXECUTE publico
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad FROM pg_proc p
    WHERE p.oid = 'public.agent_run_event_received_at()'::regprocedure
      AND (p.prosecdef OR NOT ('search_path=pg_catalog, pg_temp' = ANY (coalesce(p.proconfig, '{}')))
           OR pg_get_userbyid(p.proowner) <> 'migrador_nekkora' OR has_function_privilege('public', p.oid, 'EXECUTE'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '026: funcao de gatilho fora do padrao: %', v_bad; END IF;

    -- 3. agent_run_events: politicas exatas; ninguem alem do dono das funcoes atualiza; ninguem apaga
    SELECT string_agg(p.polname || ':' || r.rolname, ',' ORDER BY p.polname, r.rolname) INTO v_bad FROM pg_policy p, unnest(p.polroles) x
    JOIN pg_roles r ON r.oid = x WHERE p.polrelid = 'public.agent_run_events'::regclass;
    IF v_bad IS DISTINCT FROM 'tenant_function_context:nekkora_retention_owner,tenant_request_context:nekkora_ingestion,tenant_request_context:nekkora_platform' THEN
        RAISE EXCEPTION '026: politicas de agent_run_events divergentes: %', v_bad;
    END IF;
    SELECT string_agg(r.rolname, ',' ORDER BY r.rolname) INTO v_bad FROM pg_roles r
    WHERE NOT r.rolsuper AND r.rolname NOT IN ('migrador_nekkora', 'nekkora_retention_owner') AND r.rolname LIKE 'nekkora\_%'
      AND (has_table_privilege(r.oid, 'public.agent_run_events', 'UPDATE, DELETE, TRUNCATE')
           OR has_any_column_privilege(r.oid, 'public.agent_run_events', 'UPDATE'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '026: atualizacao ou exclusao possivel em agent_run_events: %', v_bad; END IF;
    IF has_table_privilege('nekkora_retention_owner', 'public.agent_run_events', 'INSERT, DELETE, TRUNCATE')
       OR has_column_privilege('nekkora_retention_owner', 'public.agent_run_events', 'content_sha256', 'UPDATE')
       OR has_column_privilege('nekkora_retention_owner', 'public.agent_run_events', 'received_at', 'UPDATE')
       OR has_column_privilege('nekkora_retention_owner', 'public.agent_run_events', 'sequence', 'UPDATE')
       OR has_table_privilege('nekkora_retention_owner', 'public.agent_runs', 'SELECT, UPDATE') THEN
        RAISE EXCEPTION '026: nekkora_retention_owner com privilegio alem da redacao';
    END IF;

    -- 4. gatilhos de agent_run_events ligados: exclusao (2), redacao (1) e received_at (1); o prevent_update saiu
    SELECT count(*) INTO v_n FROM pg_trigger WHERE tgrelid = 'public.agent_run_events'::regclass AND NOT tgisinternal AND tgenabled = 'O';
    IF v_n <> 4 OR EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.agent_run_events'::regclass AND tgname = 'trg_agent_run_events_prevent_update') THEN
        RAISE EXCEPTION '026: % gatilhos ligados em agent_run_events (esperados 4, sem prevent_update)', v_n;
    END IF;

    -- 5. nenhum papel novo; nenhum evento nasce expurgado
    IF EXISTS (SELECT 1 FROM public.agent_run_events WHERE purged_at IS NOT NULL) THEN
        RAISE EXCEPTION '026: evento expurgado na aplicacao';
    END IF;
END;
$$;

COMMIT;
