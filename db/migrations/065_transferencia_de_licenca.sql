-- Nekkora Platform - transferencia de licenca para outra automacao, pelo operador da Nekkora, a pedido de um administrador
-- (PLATFORM-LICENCIAMENTO-001, E4; decisao D6). Contrato: docs/platform-licenciamento-001/t06-transferencia.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 061 a 064. Numero 065 conferido em 19/11 contra a dev (86591bb; a maior era 060)
-- e os PRs abertos desta missao (061 a 064). Numeracao contigua: se a ordem de integracao mudar, e renumerada sem mudar o
-- conteudo.
--
-- *** ELEVACAO: cria uma funcao SECURITY DEFINER do dono nekkora_licensing_owner (063) e da a esse dono LEITURA de poucas
-- colunas de sete tabelas de outras areas. Exige a janela de SUPERUSER do migrador. SO LABORATORIO E TESTES ate autorizacao
-- especifica: NAO esta em ops/homologacao/implantacao/elevacao.txt. ***
--
-- O QUE FAZ
--   1. license_op_transfer(operador, organizacao, licenca, automacao de destino, solicitante, referencia do chamado,
--      verificacao da identidade, justificativa): na MESMA transacao encerra o vinculo com a automacao de origem (motivo 1,
--      TRANSFERIDA) e abre o vinculo com a de destino. Nao ha instante com as duas atendidas nem com nenhuma das duas usando a
--      mesma licenca. Os periodos da licenca NAO sao tocados: o fim do ciclo e preservado.
--   2. Recusa enquanto a automacao de ORIGEM tiver, ou ainda puder ter, trabalho em andamento em qualquer fluxo (execucao da
--      Platform, execucao ou sessao de monitoramento do Agent anterior, pedido ao Agent anterior ainda pendente, tentativa
--      remota nao terminada - inclusive a oferta vencida que o servidor ainda nao encerrou - e tentativa com OCUPACAO INCERTA,
--      058/060, sem confirmacao da maquina nem declaracao de um administrador): precisa terminar, ter o cancelamento
--      confirmado ou ser reconciliada antes.
--   3. Roda sob a trava EXCLUSIVA do modelo da organizacao: nenhum inicio de trabalho (064) corre contra a transferencia.
--   4. Trilha: ato 5 com origem, destino, solicitante (o vinculo do administrador), responsavel (o operador), justificativa,
--      instante e, em detail, a referencia do chamado e como a identidade do solicitante foi verificada.
--   5. O dono le, e so le, as colunas de que a regra precisa: situacao da automacao e o andamento dos seis registros de
--      trabalho (o sexto: o tipo do fato informado sobre os processos de uma tentativa). Nada de conteudo, parametros, registros, resultados, pessoas ou sessoes.
--
-- O QUE NAO FAZ
--   - nao cria central de chamados: o pedido chega pelo atendimento externo (D6);
--   - nao prova, pelo banco, que o administrador pediu: quem atesta e o operador, e o atestado fica gravado. A aplicacao
--     confere que o solicitante administra a organizacao NAQUELE instante; o banco exige que ele seja um vinculo dela;
--   - nao interrompe nem cancela trabalho: recusa a transferencia enquanto houver;
--   - nao estende, encurta nem renova a licenca.
--
-- DADOS EXISTENTES: nenhuma linha e alterada.
--
-- REVERSAO (manual, em janela de elevacao):
--   BEGIN;
--   DROP FUNCTION public.license_op_transfer(uuid, uuid, uuid, uuid, uuid, text, text, text);
--   DROP FUNCTION public.license_work_in_progress(uuid, uuid);
--   -- e retirar de nekkora_licensing_owner a politica licensing_owner_read e as leituras por coluna das sete tabelas
--   COMMIT;

BEGIN;

-- === 1. Pre-condicao ===
DO $$
BEGIN
    IF to_regprocedure('public.license_gate(uuid, uuid)') IS NULL OR to_regrole('nekkora_licensing_owner') IS NULL THEN
        RAISE EXCEPTION '065 exige a 063 e a 064 aplicadas';
    END IF;
    IF to_regprocedure('public.license_op_transfer(uuid, uuid, uuid, uuid, uuid, text, text, text)') IS NOT NULL THEN
        RAISE EXCEPTION '065: license_op_transfer ja existe';
    END IF;
END;
$$;

-- === 2. O dono le SO o que a regra precisa (colunas), em qualquer organizacao ===
GRANT SELECT (organization_id, workspace_id, automation_id, status) ON public.automations TO nekkora_licensing_owner;
GRANT SELECT (organization_id, automation_id, status) ON public.executions TO nekkora_licensing_owner;
GRANT SELECT (organization_id, automation_id, supervisor_state) ON public.agent_runs TO nekkora_licensing_owner;
GRANT SELECT (organization_id, automation_id, state, valid_until) ON public.run_requests TO nekkora_licensing_owner;
GRANT SELECT (organization_id, remote_execution_id, automation_id) ON public.remote_executions TO nekkora_licensing_owner;
GRANT SELECT (organization_id, remote_execution_id, attempt_id, state) ON public.remote_attempts TO nekkora_licensing_owner;
GRANT SELECT (attempt_id, kind) ON public.remote_attempt_process_reports TO nekkora_licensing_owner;
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['automations', 'executions', 'agent_runs', 'run_requests', 'remote_executions', 'remote_attempts',
                             'remote_attempt_process_reports'] LOOP
        EXECUTE format('CREATE POLICY licensing_owner_read ON public.%I AS PERMISSIVE FOR SELECT TO nekkora_licensing_owner USING (true)', t);
    END LOOP;
END;
$$;

-- === 3. Trabalho em andamento de uma automacao, em qualquer fluxo ===
CREATE FUNCTION public.license_work_in_progress(p_org UUID, p_automation UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM public.executions e WHERE e.organization_id = p_org AND e.automation_id = p_automation AND e.status IN (1, 2, 3))
        OR EXISTS (SELECT 1 FROM public.agent_runs r WHERE r.organization_id = p_org AND r.automation_id = p_automation AND r.supervisor_state IN (1, 2, 3))
        -- pedido ao Agent anterior: pedido ou entregue ainda no prazo, ou aceito (a execucao pode nascer a qualquer momento)
        OR EXISTS (SELECT 1 FROM public.run_requests q WHERE q.organization_id = p_org AND q.automation_id = p_automation
                      AND (q.state = 3 OR (q.state IN (1, 2) AND q.valid_until > clock_timestamp())))
        -- execucao remota sem tentativa (nao deveria existir: a solicitacao nasce com a primeira) ou com tentativa NAO TERMINADA.
        -- A oferta vencida que ninguem encerrou ainda conta: a maquina pode confirmar o recebimento ate o servidor encerra-la
        OR EXISTS (SELECT 1 FROM public.remote_executions x
                    WHERE x.organization_id = p_org AND x.automation_id = p_automation
                      AND (NOT EXISTS (SELECT 1 FROM public.remote_attempts t WHERE t.organization_id = x.organization_id
                                          AND t.remote_execution_id = x.remote_execution_id)
                           OR EXISTS (SELECT 1 FROM public.remote_attempts t WHERE t.organization_id = x.organization_id
                                         AND t.remote_execution_id = x.remote_execution_id AND t.state BETWEEN 1 AND 6)))
        -- OCUPACAO INCERTA (058, 060): tentativa terminada sem confirmacao (13) ou com processo ainda vivo / sem como saber
        -- (fatos 4 e 5), enquanto nem a maquina (1) nem a declaracao de um administrador (2) confirmarem que nao ha processo
        OR EXISTS (SELECT 1 FROM public.remote_executions x
                     JOIN public.remote_attempts t ON t.organization_id = x.organization_id AND t.remote_execution_id = x.remote_execution_id
                    WHERE x.organization_id = p_org AND x.automation_id = p_automation
                      AND (t.state = 13 OR EXISTS (SELECT 1 FROM public.remote_attempt_process_reports p WHERE p.attempt_id = t.attempt_id AND p.kind IN (4, 5)))
                      AND NOT EXISTS (SELECT 1 FROM public.remote_attempt_process_reports p WHERE p.attempt_id = t.attempt_id AND p.kind IN (1, 2)));
$$;
COMMENT ON FUNCTION public.license_work_in_progress(uuid, uuid) IS
    'A automacao tem, ou ainda pode ter, trabalho em andamento em algum fluxo (065): execucao da Platform, execucao ou monitoramento do '
    'Agent anterior, pedido pendente ao Agent anterior, tentativa remota nao terminada ou com ocupacao incerta (058, 060). Uso interno da '
    'transferencia.';

-- === 4. A transferencia ===
CREATE FUNCTION public.license_op_transfer(p_operator UUID, p_org UUID, p_license UUID, p_to_automation UUID, p_requester UUID,
                                           p_ticket TEXT, p_identity_check TEXT, p_justification TEXT)
RETURNS UUID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_ended TIMESTAMPTZ;
    v_from UUID;
    v_to_workspace UUID;
    v_to_status SMALLINT;
    v_act UUID;
BEGIN
    PERFORM public.license_op_require(p_operator, p_org, p_justification);
    IF p_license IS NULL OR p_to_automation IS NULL OR p_requester IS NULL
       OR p_ticket IS NULL OR length(btrim(p_ticket)) NOT BETWEEN 3 AND 200
       OR p_identity_check IS NULL OR length(btrim(p_identity_check)) NOT BETWEEN 10 AND 500 THEN
        RAISE EXCEPTION 'ato recusado: INVALID' USING ERRCODE = '22023';
    END IF;
    -- trava EXCLUSIVA do modelo: nenhum inicio de trabalho (064) e nenhum vinculo corre contra a transferencia
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('organization_license_model:' || p_org::text, 0));
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('organization_license:' || p_license::text, 0));
    SELECT l.ended_at INTO v_ended FROM public.organization_licenses l WHERE l.organization_id = p_org AND l.license_id = p_license;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'ato recusado: LICENSE_NOT_FOUND' USING ERRCODE = '23514';
    END IF;
    IF v_ended IS NOT NULL THEN
        RAISE EXCEPTION 'ato recusado: LICENSE_ENDED' USING ERRCODE = '23514';
    END IF;
    SELECT g.automation_id INTO v_from FROM public.organization_license_assignments g
     WHERE g.organization_id = p_org AND g.license_id = p_license AND g.released_at IS NULL;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'ato recusado: LICENSE_NOT_ASSIGNED' USING ERRCODE = '23514';
    END IF;
    IF v_from = p_to_automation THEN
        RAISE EXCEPTION 'ato recusado: SAME_AUTOMATION' USING ERRCODE = '23514';
    END IF;
    SELECT a.workspace_id, a.status INTO v_to_workspace, v_to_status FROM public.automations a
     WHERE a.organization_id = p_org AND a.automation_id = p_to_automation;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'ato recusado: AUTOMATION_NOT_FOUND' USING ERRCODE = '23514';
    END IF;
    IF v_to_status = 3 THEN
        RAISE EXCEPTION 'ato recusado: AUTOMATION_ARCHIVED' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM public.organization_license_assignments g
                WHERE g.organization_id = p_org AND g.automation_id = p_to_automation AND g.released_at IS NULL) THEN
        RAISE EXCEPTION 'ato recusado: AUTOMATION_ALREADY_LICENSED' USING ERRCODE = '23514';
    END IF;
    -- a origem nao pode ter nada em andamento: termina, ou tem o cancelamento confirmado, antes
    IF public.license_work_in_progress(p_org, v_from) THEN
        RAISE EXCEPTION 'ato recusado: WORK_IN_PROGRESS' USING ERRCODE = '23514';
    END IF;
    UPDATE public.organization_license_assignments g SET released_at = clock_timestamp(), release_reason = 1
     WHERE g.organization_id = p_org AND g.license_id = p_license AND g.released_at IS NULL;
    -- o vinculo novo e aberto pelo ATO do operador: sem "vinculado por" (quem responde esta no ato 5); a licenca precisa estar
    -- vigente (o gatilho do vinculo confere) - licenca vencida nao e transferida, e renovada antes
    INSERT INTO public.organization_license_assignments (organization_id, license_id, workspace_id, automation_id)
    VALUES (p_org, p_license, v_to_workspace, p_to_automation);
    INSERT INTO public.organization_license_acts AS a (organization_id, act, license_id, from_automation_id, to_automation_id, requester_membership_id,
                                                      operator_user_id, justification, detail)
    VALUES (p_org, 5, p_license, v_from, p_to_automation, p_requester, p_operator, btrim(p_justification),
            jsonb_build_object('ticket', btrim(p_ticket), 'identityCheck', btrim(p_identity_check)))
    RETURNING a.act_id INTO v_act;
    RETURN v_act;
END;
$$;
COMMENT ON FUNCTION public.license_op_transfer(uuid, uuid, uuid, uuid, uuid, text, text, text) IS
    'Transfere a licenca para outra automacao da mesma organizacao (065, D6): encerra o vinculo de origem e abre o de destino na mesma '
    'transacao, preserva os periodos, recusa com trabalho em andamento na origem e grava o ato 5 com solicitante, chamado e verificacao.';

-- === 5. Dono e privilegios ===
ALTER FUNCTION public.license_work_in_progress(uuid, uuid) OWNER TO nekkora_licensing_owner;
ALTER FUNCTION public.license_op_transfer(uuid, uuid, uuid, uuid, uuid, text, text, text) OWNER TO nekkora_licensing_owner;
REVOKE ALL ON FUNCTION public.license_work_in_progress(uuid, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.license_op_transfer(uuid, uuid, uuid, uuid, uuid, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.license_op_transfer(uuid, uuid, uuid, uuid, uuid, text, text, text) TO nekkora_platform;

-- === 6. Invariantes ===
DO $$
DECLARE
    t TEXT;
BEGIN
    IF pg_get_userbyid((SELECT proowner FROM pg_proc WHERE oid = 'public.license_op_transfer(uuid, uuid, uuid, uuid, uuid, text, text, text)'::regprocedure))
           <> 'nekkora_licensing_owner'
       OR has_function_privilege('public', 'public.license_op_transfer(uuid, uuid, uuid, uuid, uuid, text, text, text)', 'EXECUTE')
       OR has_function_privilege('nekkora_platform', 'public.license_work_in_progress(uuid, uuid)', 'EXECUTE') THEN
        RAISE EXCEPTION '065: funcoes fora do padrao';
    END IF;
    -- o dono le por COLUNA; nao tem leitura da tabela inteira, nem escrita, em nenhuma das seis
    FOREACH t IN ARRAY ARRAY['automations', 'executions', 'agent_runs', 'run_requests', 'remote_executions', 'remote_attempts',
                             'remote_attempt_process_reports'] LOOP
        IF has_table_privilege('nekkora_licensing_owner', 'public.' || t, 'SELECT')
           OR has_table_privilege('nekkora_licensing_owner', 'public.' || t, 'INSERT, UPDATE, DELETE') THEN
            RAISE EXCEPTION '065: o dono das licencas alcanca % alem das colunas previstas', t;
        END IF;
    END LOOP;
    IF has_column_privilege('nekkora_licensing_owner', 'public.automations', 'name', 'SELECT') THEN
        RAISE EXCEPTION '065: o dono das licencas le o nome das automacoes';
    END IF;
    FOREACH t IN ARRAY ARRAY['organizations', 'users', 'organization_memberships', 'user_sessions', 'local_run_authorizations'] LOOP
        IF has_table_privilege('nekkora_licensing_owner', 'public.' || t, 'SELECT, INSERT, UPDATE, DELETE') THEN
            RAISE EXCEPTION '065: o dono das licencas alcanca %', t;
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_licensing_owner'
                  AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolcanlogin OR rolreplication)) THEN
        RAISE EXCEPTION '065: nekkora_licensing_owner com atributo indevido';
    END IF;
END;
$$;

COMMIT;
