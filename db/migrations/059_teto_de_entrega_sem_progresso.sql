-- Nekkora Platform - teto para a entrega de registros e resultados que nao se conclui
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, decisao de 16/11). Contrato: docs/platform-client-execucao-remota-001/retencao-no-servidor.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 057 e da 058. Numero 059 conferido em 16/11 contra a dev e os PRs abertos.
--
-- DECISAO DA TITULAR: a entrega que nao se conclui tem teto - 30 dias configuraveis SEM PROGRESSO DE ENTREGA, e so quando NAO
-- houver execucao ativa nem incerta. A presenca da maquina NAO renova o prazo. Ao vencer: registrar ENTREGA INCOMPLETA e aplicar
-- a retencao, SEM indicar recebimento.
--
-- O QUE FAZ
--   1. remote_attempt_delivery_ended_at ganha um quarto fim da entrega:
--        4 = NO_PROGRESS  a tentativa terminou, nao esta incerta, e a entrega daquele tipo nao foi fechada nem encerrada: o fim e o
--                         ULTIMO PROGRESSO - o termino da tentativa ou a ultima linha (ou arquivo) recebida, o que for mais recente.
--      "Incerta" = resultado nao confirmado sem reconciliacao (058): nesse caso continua nulo e NADA vence.
--      Os tres fins anteriores nao mudam e tem precedencia. A presenca da maquina nao entra na conta.
--   2. remote_attempt_content_expirations aceita delivery_end = 4. O prazo aplicado e o da aplicacao (o teto), gravado na linha.
--      O registro diz "entrega incompleta": nao ha fechamento da maquina, e a leitura nao mostra nenhum.
--
-- O QUE NAO FAZ: nao fecha o envio (remote_attempt_*_closures continua sem linha); nao altera a tentativa.
--
-- DADOS EXISTENTES: nenhuma linha e alterada.
--
-- REVERSAO (manual; so sem linha com delivery_end = 4): recriar a funcao com o corpo da 057 e a CHECK com (1, 2, 3).
--
-- SEM ELEVACAO: substitui uma funcao e uma CHECK do proprio dono. Nenhum papel novo, nenhuma funcao SECURITY DEFINER.

BEGIN;

DO $$
BEGIN
    IF to_regprocedure('public.remote_attempt_delivery_ended_at(uuid, uuid, smallint)') IS NULL OR to_regclass('public.remote_attempt_process_reports') IS NULL THEN
        RAISE EXCEPTION '059 exige a 057 e a 058 aplicadas';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.remote_attempt_delivery_ended_at(p_organization UUID, p_attempt UUID, p_kind SMALLINT,
                                                                   OUT ended_at TIMESTAMPTZ, OUT how SMALLINT)
LANGUAGE plpgsql STABLE SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    t RECORD;
    link_end TIMESTAMPTZ;
    last_progress TIMESTAMPTZ;
BEGIN
    -- 1. a maquina fechou o envio: entrega concluida
    IF p_kind = 1 THEN
        SELECT c.closed_at INTO ended_at FROM public.remote_attempt_log_closures c WHERE c.organization_id = p_organization AND c.attempt_id = p_attempt;
    ELSIF p_kind = 2 THEN
        SELECT c.closed_at INTO ended_at FROM public.remote_attempt_result_closures c WHERE c.organization_id = p_organization AND c.attempt_id = p_attempt;
    ELSE
        RETURN;
    END IF;
    IF ended_at IS NOT NULL THEN
        how := 1;
        RETURN;
    END IF;
    -- 2. a maquina avisou que perdeu o que guardava (052): nao ha mais o que entregar
    SELECT l.reported_at INTO ended_at FROM public.remote_attempt_retention_losses l
     WHERE l.organization_id = p_organization AND l.attempt_id = p_attempt AND l.kind = p_kind;
    IF ended_at IS NOT NULL THEN
        how := 2;
        RETURN;
    END IF;
    SELECT a.ended_at, a.created_at, a.agent_id, a.worker_id, a.state INTO t FROM public.remote_attempts a
     WHERE a.organization_id = p_organization AND a.attempt_id = p_attempt AND a.state >= 7;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    -- 3. a tentativa terminou e o vinculo sob o qual ela rodou foi encerrado: a maquina nao tem mais como enviar
    SELECT wa.ended_at INTO link_end FROM public.worker_agent_assignments wa
     WHERE wa.organization_id = p_organization AND wa.agent_id = t.agent_id AND wa.worker_id = t.worker_id AND wa.created_at <= t.created_at
     ORDER BY wa.created_at DESC LIMIT 1;
    IF link_end IS NULL THEN
        SELECT g.updated_at INTO link_end FROM public.agents g WHERE g.organization_id = p_organization AND g.agent_id = t.agent_id AND g.status = 4;
    END IF;
    IF link_end IS NOT NULL THEN
        ended_at := greatest(link_end, t.ended_at);
        how := 3;
        RETURN;
    END IF;
    -- 4. sem progresso de entrega (059). Execucao incerta (nao confirmada e sem reconciliacao): nada vence
    IF t.state = 13 AND NOT EXISTS (SELECT 1 FROM public.remote_attempt_process_reports p WHERE p.attempt_id = p_attempt AND p.kind IN (1, 2)) THEN
        RETURN;
    END IF;
    IF p_kind = 1 THEN
        SELECT max(l.received_at) INTO last_progress FROM public.remote_attempt_log_lines l WHERE l.organization_id = p_organization AND l.attempt_id = p_attempt;
    ELSE
        SELECT max(r.received_at) INTO last_progress FROM public.remote_attempt_results r WHERE r.organization_id = p_organization AND r.attempt_id = p_attempt;
    END IF;
    -- o ultimo progresso: o termino da tentativa ou o ultimo item recebido. A presenca da maquina nao conta
    ended_at := greatest(t.ended_at, coalesce(last_progress, t.ended_at));
    how := 4;
END;
$$;
COMMENT ON FUNCTION public.remote_attempt_delivery_ended_at(UUID, UUID, SMALLINT) IS
    'Quando a entrega dos registros (1) ou dos resultados (2) de uma tentativa terminou, e como: 1=CLOSED, 2=LOSS_NOTICE, 3=LINK_ENDED, '
    '4=NO_PROGRESS (ultimo progresso de uma entrega que nao se concluiu; 059). Nulo: tentativa em andamento ou incerta - nada vence.';

ALTER TABLE public.remote_attempt_content_expirations DROP CONSTRAINT remote_attempt_content_expirations_delivery_end_check;
ALTER TABLE public.remote_attempt_content_expirations ADD CONSTRAINT remote_attempt_content_expirations_delivery_end_check CHECK (delivery_end IN (1, 2, 3, 4));
COMMENT ON COLUMN public.remote_attempt_content_expirations.delivery_end IS
    '1=CLOSED (entrega concluida), 2=LOSS_NOTICE, 3=LINK_ENDED (encerramento explicito), 4=NO_PROGRESS (entrega INCOMPLETA: venceu o teto sem progresso; 059)';

DO $$
BEGIN
    IF position('NO_PROGRESS' IN obj_description('public.remote_attempt_delivery_ended_at(uuid, uuid, smallint)'::regprocedure)) = 0
       OR (SELECT prosecdef FROM pg_proc WHERE oid = 'public.remote_attempt_delivery_ended_at(uuid, uuid, smallint)'::regprocedure) THEN
        RAISE EXCEPTION '059: funcao fora do previsto';
    END IF;
END;
$$;

COMMIT;
