-- Nekkora Platform - lote do expurgo das categorias 1 (execution_events) e 2 (logs) respeitado sob qualquer plano (D-RET-2).
--
-- CANDIDATA ate a aprovacao do relatorio da suite db/tests/lote-expurgo-027. Decisao da titular (28/10): corrigir em PR
-- proprio, com reproducao dirigida e controles de limite do lote; o expurgo continua DESLIGADO fora do laboratorio (D-RET-1:
-- nenhum EXECUTE de purge_expired_records e concedido aqui).
--
-- Defeito (achado da suite da 026, caso C3): a selecao "WHERE id IN (SELECT ... ORDER BY ... LIMIT n FOR UPDATE)" da 011/012
-- pode expurgar MAIS de n registros. Quando o planejador escolhe um laco aninhado (comum sob a RLS, que derruba a estimativa
-- da tabela externa), a subconsulta e reexecutada a cada linha externa; a cada reexecucao o LockRows pula as linhas que este
-- mesmo comando ja alterou e devolve outras, e a uniao das reexecucoes passa de n. O teto de 1000 por chamada deixa de valer.
--
-- Correcao: o lote e selecionado UMA vez (CTE MATERIALIZED com o mesmo filtro, a mesma ordem, o mesmo LIMIT e o mesmo FOR
-- UPDATE) e o UPDATE junta por chave. Mesma regra de elegibilidade (dia fechado, politica finita vigente no recebimento,
-- prazo vencido), mesma redacao, mesma auditoria. A categoria 3 ja tem essa forma desde a 026 e nao muda.
--
-- ELEVACAO: exige a janela de SUPERUSER: CREATE OR REPLACE de purge_expired_records, cujo dono e nekkora_retention_owner
-- (011/012). Nenhum papel, dono, funcao, concessao ou objeto novo. Nenhum dado muda na aplicacao.
--
-- Depende de 001-026. Atomica: uma transacao.

BEGIN;

-- === 1. Pre-condicao: 026 aplicada; 027 ausente ===
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = to_regclass('public.agent_run_events') AND attname = 'purged_at' AND NOT attisdropped) THEN
        RAISE EXCEPTION '027 exige a 026 aplicada';
    END IF;
    IF (SELECT count(*) FROM regexp_matches(pg_get_functiondef('public.purge_expired_records(uuid,integer,uuid,integer)'::regprocedure),
                                            'AS MATERIALIZED', 'g')) <> 1 THEN
        RAISE EXCEPTION '027: lote do expurgo das categorias 1 e 2 ja corrigido (ou funcao fora da forma da 026)';
    END IF;
END;
$$;

-- === 2. purge_expired_records: as tres categorias com o lote selecionado uma vez ===
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
            -- lote selecionado UMA vez (CTE materializada); ver o cabecalho desta migration
            WITH alvo AS MATERIALIZED (
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
            )
            UPDATE public.execution_events e
            SET payload = NULL, purged_at = clock_timestamp(), purged_by_user_id = p_purged_by_user_id, purge_run_id = run_id
            FROM alvo
            WHERE e.event_id = alvo.event_id;
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
            WITH alvo AS MATERIALIZED (
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
            )
            UPDATE public.logs l
            SET payload = NULL, purged_at = clock_timestamp(), purged_by_user_id = p_purged_by_user_id, purge_run_id = run_id
            FROM alvo
            WHERE l.log_id = alvo.log_id;
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


-- === 3. Invariantes ===
DO $$
DECLARE
    v_bad TEXT;
BEGIN
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad FROM pg_proc p
    WHERE p.oid = 'public.purge_expired_records(uuid,integer,uuid,integer)'::regprocedure
      AND (NOT p.prosecdef OR pg_get_userbyid(p.proowner) <> 'nekkora_retention_owner'
           OR NOT ('search_path=pg_catalog, pg_temp' = ANY (coalesce(p.proconfig, '{}'))));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '027: purge_expired_records fora do padrao: %', v_bad; END IF;
    SELECT string_agg(r.rolname, ',' ORDER BY r.rolname) INTO v_bad FROM pg_roles r
    WHERE NOT r.rolsuper AND r.rolname <> 'nekkora_retention_owner'
      AND has_function_privilege(r.oid, 'public.purge_expired_records(uuid,integer,uuid,integer)', 'EXECUTE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '027: EXECUTE de purge_expired_records concedido: %', v_bad; END IF;
    IF (SELECT count(*) FROM regexp_matches(pg_get_functiondef('public.purge_expired_records(uuid,integer,uuid,integer)'::regprocedure),
                                            'AS MATERIALIZED', 'g')) <> 3 THEN
        RAISE EXCEPTION '027: as tres categorias devem selecionar o lote uma vez';
    END IF;
END;
$$;

COMMIT;
