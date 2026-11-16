-- Nekkora Platform - o que a maquina sabe dos PROCESSOS de uma tentativa que terminou
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, decisoes de 16/11). Contrato: docs/platform-client-execucao-remota-001/processos-da-tentativa.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 058 e da 059. Numero 060 conferido em 16/11 contra a dev e os PRs abertos.
--
-- DECISAO DA TITULAR: terminar o processo PRINCIPAL nao libera a maquina enquanto houver descendente da tentativa ativo - tambem
-- nas execucoes NORMAIS (concluida, falhou, cancelada, tempo esgotado), nao so nas nao confirmadas. O codigo de saida do
-- principal fica registrado; a ocupacao, a repeticao e a limpeza esperam. Se a maquina perder a capacidade de identificar os
-- processos, a ocupacao passa a INCERTA. Tempo, novo contato e a declaracao de reconciliacao da tentativa nao liberam a maquina.
--
-- O QUE FAZ
--   1. remote_attempt_process_reports ganha dois fatos informados pela MAQUINA, um por linha, nunca alterados:
--        4 = ACTIVE     o processo principal terminou e ha processo da tentativa ainda vivo ("finalizando");
--        5 = UNCERTAIN  a maquina nao tem como dizer se ha processo da tentativa vivo.
--      Os fatos 1 (ausente), 4 e 5 passam a valer para QUALQUER tentativa que a maquina terminou (estados 7, 8, 9, 10 e 13);
--      o 3 ("ainda executa") continua so no estado 13. A declaracao manual (2) vale para a tentativa nao confirmada (13) e
--      para a terminada que tem o fato 4 ou 5 sem o 1.
--      Leitura: sem o fato 1, vale o mais grave que houver - 5 (incerta) sobre 4 (finalizando) sobre 3 (ainda executa).
--   2. remote_attempt_delivery_ended_at: o teto por falta de progresso (059) nao vence enquanto a execucao estiver ATIVA ou
--      INCERTA - agora tambem a tentativa terminada com o fato 4 ou 5 e sem 1 nem 2. Os outros tres fins nao mudam: o
--      encerramento explicito do vinculo continua iniciando o prazo de retencao mesmo com o resultado desconhecido (decisao de
--      16/11) - o que nao confirma o termino do processo, nao libera a maquina e nao vira "entregue".
--
-- O QUE NAO FAZ: nao muda a tentativa; nao cria tentativa; nao executa nada; nao altera linha existente.
--
-- DADOS EXISTENTES: nenhuma linha e alterada. Tentativas terminadas antes desta migration nao tem os fatos 4 ou 5: nada muda nelas.
--
-- REVERSAO (manual; so sem linha com kind 4 ou 5): recriar o gatilho e a funcao com os corpos da 058 e da 059 e a CHECK com (1, 2, 3).
--
-- SEM ELEVACAO: substitui funcoes e uma CHECK do proprio dono. Nenhum papel novo, nenhuma funcao SECURITY DEFINER.

BEGIN;

DO $$
BEGIN
    IF to_regclass('public.remote_attempt_process_reports') IS NULL
       OR position('NO_PROGRESS' IN coalesce(obj_description('public.remote_attempt_delivery_ended_at(uuid, uuid, smallint)'::regprocedure), '')) = 0 THEN
        RAISE EXCEPTION '060 exige a 058 e a 059 aplicadas';
    END IF;
END;
$$;

ALTER TABLE public.remote_attempt_process_reports DROP CONSTRAINT remote_attempt_process_reports_kind_check;
ALTER TABLE public.remote_attempt_process_reports ADD CONSTRAINT remote_attempt_process_reports_kind_check CHECK (kind IN (1, 2, 3, 4, 5));
COMMENT ON TABLE public.remote_attempt_process_reports IS
    'O que se sabe dos processos de uma tentativa terminada (058, 060): 1 = a maquina confirmou que nao ha processo; 2 = declaracao '
    'manual de um ORG_ADMIN, com motivo e verificacao; 3 = a maquina disse que ainda executa; 4 = o processo principal terminou e '
    'ha processo da tentativa vivo (finalizando); 5 = a maquina nao tem como saber. Nunca alterado nem apagado.';

CREATE OR REPLACE FUNCTION public.remote_attempt_process_report_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    t RECORD;
    absent BOOLEAN;
    pending BOOLEAN;
BEGIN
    NEW.reported_at := clock_timestamp();
    SELECT a.state, a.agent_id INTO t FROM public.remote_attempts a WHERE a.organization_id = NEW.organization_id AND a.attempt_id = NEW.attempt_id;
    IF NOT FOUND OR t.state NOT IN (7, 8, 9, 10, 13) THEN
        RAISE EXCEPTION 'so tentativa que a maquina terminou tem relato de processos' USING ERRCODE = '23514';
    END IF;
    absent := EXISTS (SELECT 1 FROM public.remote_attempt_process_reports r WHERE r.attempt_id = NEW.attempt_id AND r.kind = 1);
    pending := EXISTS (SELECT 1 FROM public.remote_attempt_process_reports r WHERE r.attempt_id = NEW.attempt_id AND r.kind IN (4, 5));
    -- a maquina e a da tentativa, nao a que quem insere disser
    NEW.agent_id := t.agent_id;
    IF NEW.kind IN (1, 3, 4, 5) THEN
        IF current_user <> 'nekkora_ingestion' THEN
            RAISE EXCEPTION 'so a maquina informa o que ela mesma executa' USING ERRCODE = '42501';
        END IF;
        IF NEW.kind = 3 AND t.state <> 13 THEN
            RAISE EXCEPTION '"ainda executa" so vale para tentativa com resultado nao confirmado' USING ERRCODE = '23514';
        END IF;
        IF NEW.kind IN (3, 4, 5) AND absent THEN
            RAISE EXCEPTION 'a maquina ja confirmou que nao ha processo' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF current_user <> 'nekkora_platform' THEN
            RAISE EXCEPTION 'a declaracao manual e de uma pessoa, pela Platform' USING ERRCODE = '42501';
        END IF;
        -- so ha o que declarar numa tentativa nao confirmada, ou numa terminada cujos processos a maquina nao deu por ausentes
        IF t.state <> 13 AND NOT (pending AND NOT absent) THEN
            RAISE EXCEPTION 'a tentativa nao tem processo pendente de confirmacao' USING ERRCODE = '23514';
        END IF;
        -- ORG_ADMIN efetivo DESTA organizacao: atribuicao ativa, vinculo ativo, conta ativa
        IF NOT EXISTS (SELECT 1 FROM public.organization_role_assignments a
                         JOIN public.organization_memberships m ON m.organization_id = a.organization_id AND m.membership_id = a.membership_id
                         JOIN public.users u ON u.organization_id = m.organization_id AND u.user_id = m.user_id
                         JOIN public.roles r ON r.role_id = a.role_id
                        WHERE a.organization_id = NEW.organization_id AND a.membership_id = NEW.by_membership_id AND a.status = 1 AND m.status = 2
                          AND u.status = 2 AND r.code = 'ORG_ADMIN') THEN
            RAISE EXCEPTION 'a declaracao manual e restrita a ORG_ADMIN da organizacao' USING ERRCODE = '42501';
        END IF;
    END IF;
    RETURN NEW;
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
    -- 3. a tentativa terminou e o vinculo sob o qual ela rodou foi encerrado: a maquina nao tem mais como enviar. Vale mesmo com
    --    o resultado desconhecido ou com processos pendentes: e o encerramento EXPLICITO que inicia o prazo
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
    -- 4. sem progresso de entrega (059). Execucao ATIVA ou INCERTA - nao confirmada, ou com processos da tentativa vivos ou sem
    --    como saber (060) - e sem confirmacao da maquina nem declaracao: nada vence
    IF (t.state = 13 OR EXISTS (SELECT 1 FROM public.remote_attempt_process_reports p WHERE p.attempt_id = p_attempt AND p.kind IN (4, 5)))
       AND NOT EXISTS (SELECT 1 FROM public.remote_attempt_process_reports p WHERE p.attempt_id = p_attempt AND p.kind IN (1, 2)) THEN
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
    '4=NO_PROGRESS (ultimo progresso de uma entrega que nao se concluiu; 059). Nulo: tentativa em andamento, ou ATIVA/INCERTA (060) '
    'sem encerramento explicito do vinculo - nada vence.';

DO $$
BEGIN
    IF (SELECT prosecdef FROM pg_proc WHERE oid = 'public.remote_attempt_delivery_ended_at(uuid, uuid, smallint)'::regprocedure)
       OR (SELECT prosecdef FROM pg_proc WHERE oid = 'public.remote_attempt_process_report_on_insert()'::regprocedure)
       OR has_table_privilege('nekkora_platform', 'public.remote_attempt_process_reports', 'UPDATE, DELETE')
       OR has_table_privilege('nekkora_ingestion', 'public.remote_attempt_process_reports', 'UPDATE, DELETE') THEN
        RAISE EXCEPTION '060: funcao ou privilegio fora do previsto';
    END IF;
END;
$$;

COMMIT;
