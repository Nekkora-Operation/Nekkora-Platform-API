-- Nekkora Platform - PLATFORM-FLUXO-002: o resumo do dashboard do cliente separa "resultado desconhecido" e "terminou sem sinal de
-- erro" (codigo de saida zero sem confirmacao de sucesso). Na 071 os dois saiam somados em "other"; nenhum dos dois nunca contou
-- como concluida, mas o cliente precisa distingui-los (pedido da titular, 21/11/2026).
--
-- CANDIDATA ate a aprovacao do PR (vem junto com a 070 e a 071). Recria uma funcao do nekkora_portal_owner (o tipo de retorno
-- muda): exige a janela de SUPERUSER do migrador, como a 071. SO LABORATORIO E TESTES ate autorizacao especifica.
--
-- O QUE MUDA: portal_client_summary devolve unconfirmed e no_error_signaled no lugar de other. Nenhum dado muda.
-- REVERSAO: recriar a funcao com o corpo da 071.
--
-- Atomica: uma transacao.

BEGIN;

DO $$
BEGIN
    IF to_regclass('public.client_share_requests') IS NULL THEN
        RAISE EXCEPTION '072: a 071 nao esta aplicada';
    END IF;
END;
$$;

DROP FUNCTION public.portal_client_summary(UUID, TEXT, UUID, TIMESTAMPTZ, TIMESTAMPTZ);

CREATE FUNCTION public.portal_client_summary(p_organization_id UUID, p_identity_token TEXT, p_automation_id UUID, p_from TIMESTAMPTZ,
    p_to TIMESTAMPTZ)
RETURNS TABLE (total BIGINT, succeeded BIGINT, failed BIGINT, in_progress BIGINT, not_started BIGINT, cancelled BIGINT, unconfirmed BIGINT,
               no_error_signaled BIGINT,
               last_requested_at TIMESTAMPTZ, last_outcome TEXT)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
#variable_conflict use_column
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    s RECORD;
BEGIN
    BEGIN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(p_organization_id::text, ''), true);
        SELECT * INTO s FROM public.portal_client_resolve(p_organization_id, p_identity_token);
        IF p_from IS NULL OR p_to IS NULL OR p_to <= p_from OR p_to - p_from > interval '400 days' THEN
            RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'periodo invalido';
        END IF;
        PERFORM public.portal_client_require(p_organization_id, s.o_client_membership_id, p_automation_id, 'dashboard');
        RETURN QUERY
        WITH ex AS (SELECT * FROM public.portal_client_executions(p_organization_id, s.o_client_membership_id) e
                     WHERE e.automation_id = p_automation_id)
        SELECT pg_catalog.count(*) FILTER (WHERE ex.requested_at >= p_from AND ex.requested_at < p_to),
               pg_catalog.count(*) FILTER (WHERE ex.requested_at >= p_from AND ex.requested_at < p_to AND ex.outcome = 'succeeded'),
               pg_catalog.count(*) FILTER (WHERE ex.requested_at >= p_from AND ex.requested_at < p_to AND ex.outcome = 'failed'),
               pg_catalog.count(*) FILTER (WHERE ex.requested_at >= p_from AND ex.requested_at < p_to AND ex.outcome = 'in_progress'),
               pg_catalog.count(*) FILTER (WHERE ex.requested_at >= p_from AND ex.requested_at < p_to AND ex.outcome = 'not_started'),
               pg_catalog.count(*) FILTER (WHERE ex.requested_at >= p_from AND ex.requested_at < p_to AND ex.outcome = 'cancelled'),
               -- resultado desconhecido e "terminou sem sinal de erro" (codigo 0 sem confirmacao de sucesso) ficam separados: nunca contam como concluidas
               pg_catalog.count(*) FILTER (WHERE ex.requested_at >= p_from AND ex.requested_at < p_to AND ex.outcome = 'unconfirmed'),
               pg_catalog.count(*) FILTER (WHERE ex.requested_at >= p_from AND ex.requested_at < p_to AND ex.outcome = 'no_error_signaled'),
               (SELECT e2.requested_at FROM ex e2 ORDER BY e2.requested_at DESC, e2.id DESC LIMIT 1),
               (SELECT e2.outcome FROM ex e2 ORDER BY e2.requested_at DESC, e2.id DESC LIMIT 1)
          FROM ex;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
END;
$$;

ALTER FUNCTION public.portal_client_summary(UUID, TEXT, UUID, TIMESTAMPTZ, TIMESTAMPTZ) OWNER TO nekkora_portal_owner;
REVOKE ALL ON FUNCTION public.portal_client_summary(UUID, TEXT, UUID, TIMESTAMPTZ, TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.portal_client_summary(UUID, TEXT, UUID, TIMESTAMPTZ, TIMESTAMPTZ) TO nekkora_portal;

DO $$
BEGIN
    IF has_function_privilege('public', 'public.portal_client_summary(uuid, text, uuid, timestamptz, timestamptz)', 'EXECUTE') THEN
        RAISE EXCEPTION '072: EXECUTE publico no resumo';
    END IF;
END;
$$;

COMMIT;
