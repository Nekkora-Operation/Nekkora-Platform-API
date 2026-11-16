-- Nekkora Platform - verificacao de dependencias sob demanda: o intervalo entre pedidos passa a ser configuracao
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, fechamento). Contrato: docs/platform-client-execucao-remota-001/verificacao-sob-demanda.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 054. Numero 056 conferido em 16/11 contra a dev e os PRs abertos.
--
-- DECISAO DA TITULAR (16/11): permissao de executar, um pedido por minuto e dois minutos para responder sao parametros
-- INICIAIS CONFIGURAVEIS; o limite vale no escopo definido (maquina e automacao), sem bloqueio global entre clientes.
--
-- O QUE FAZ: a 054 fixava "60 segundos" dentro do gatilho. O gatilho passa a guardar so um PISO (10 segundos por maquina e
-- automacao); o intervalo que vale e o da aplicacao (NEKKORA_EXECUCAO_REMOTA_VERIFICACAO_INTERVALO_SEGUNDOS, padrao 60, de 10
-- a 3600). O prazo da resposta ja era dado pela aplicacao e conferido pelo banco (ate 10 minutos); agora tambem e configuracao
-- (NEKKORA_EXECUCAO_REMOTA_VERIFICACAO_RESPOSTA_SEGUNDOS, padrao 120, de 30 a 600). Nada mais muda na funcao.
--
-- ESCOPO DO LIMITE (inalterado): organizacao + automacao + maquina. Nao existe limite por organizacao nem global.
--
-- DADOS EXISTENTES: nenhuma linha e alterada.
--
-- REVERSAO (manual): recriar a funcao com o corpo da 054 (troca "10 seconds" por "60 seconds").
--
-- SEM ELEVACAO: substitui uma funcao de gatilho SECURITY INVOKER do proprio dono. Nenhum papel novo, nenhuma funcao SECURITY DEFINER.

BEGIN;

DO $$
BEGIN
    IF to_regprocedure('public.remote_dependency_check_on_insert()') IS NULL THEN
        RAISE EXCEPTION '056 exige a 054 aplicada';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.remote_dependency_check_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.requested_at := clock_timestamp();
    IF NEW.answer_valid_until IS NULL OR NEW.answer_valid_until <= NEW.requested_at OR NEW.answer_valid_until > NEW.requested_at + interval '10 minutes' THEN
        RAISE EXCEPTION 'prazo da resposta fora do permitido' USING ERRCODE = '23514';
    END IF;
    IF NEW.delivered_at IS NOT NULL OR NEW.outcome IS NOT NULL OR NEW.missing IS NOT NULL OR NEW.answered_at IS NOT NULL THEN
        RAISE EXCEPTION 'a verificacao nasce sem entrega e sem resposta' USING ERRCODE = '23514';
    END IF;
    -- quem pede pode mandar esta maquina executar esta automacao (automation.execute no ambiente)
    IF NOT public.agent_workspace_permission(NEW.organization_id, NEW.requested_by_membership_id, NEW.workspace_id, 'automation.execute') THEN
        RAISE EXCEPTION 'sem automation.execute no ambiente' USING ERRCODE = '42501';
    END IF;
    -- a maquina esta habilitada, e o que sera conferido e a ESCOLHA vigente e a revisao VIGENTE das dependencias
    IF NOT EXISTS (SELECT 1 FROM public.agent_remote_enablements e
                     JOIN public.agents a ON a.organization_id = e.organization_id AND a.agent_id = e.agent_id AND a.status = 2
                    WHERE e.organization_id = NEW.organization_id AND e.agent_id = NEW.agent_id) THEN
        RAISE EXCEPTION 'maquina nao habilitada' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.automation_python_selections s
                    WHERE s.organization_id = NEW.organization_id AND s.automation_id = NEW.automation_id AND s.agent_id = NEW.agent_id
                      AND s.workspace_id = NEW.workspace_id AND s.environment_id = NEW.environment_id) THEN
        RAISE EXCEPTION 'o ambiente conferido e o escolhido para esta automacao nesta maquina' USING ERRCODE = '23514';
    END IF;
    IF NEW.requirements_revision IS DISTINCT FROM (SELECT max(r.revision_number) FROM public.automation_runtime_requirements r
                                                    WHERE r.organization_id = NEW.organization_id AND r.automation_id = NEW.automation_id) THEN
        RAISE EXCEPTION 'a verificacao usa a revisao vigente das dependencias' USING ERRCODE = '23514';
    END IF;
    -- piso do banco: nunca mais de um pedido a cada 10 segundos por maquina e automacao. O intervalo que vale (padrao: 60 s) e
    -- configuracao da aplicacao, que confere com a fila parada; o escopo e sempre a maquina e a automacao - o pedido de um
    -- cliente nunca segura o de outro
    IF EXISTS (SELECT 1 FROM public.remote_dependency_checks c
                WHERE c.organization_id = NEW.organization_id AND c.automation_id = NEW.automation_id AND c.agent_id = NEW.agent_id
                  AND c.requested_at > NEW.requested_at - interval '10 seconds') THEN
        RAISE EXCEPTION 'DEPENDENCY_CHECK_TOO_SOON' USING ERRCODE = '23P01';
    END IF;
    RETURN NEW;
END;
$$;

DO $$
BEGIN
    IF position('10 seconds' IN pg_get_functiondef('public.remote_dependency_check_on_insert()'::regprocedure)) = 0
       OR (SELECT prosecdef FROM pg_proc WHERE oid = 'public.remote_dependency_check_on_insert()'::regprocedure) THEN
        RAISE EXCEPTION '056: funcao do gatilho fora do previsto';
    END IF;
END;
$$;

COMMIT;
