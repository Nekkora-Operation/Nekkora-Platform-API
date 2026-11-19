-- Nekkora Platform - criacao excepcional de organizacao pelo operador da Nekkora e reconciliacao, pelo suporte, das sessoes do
-- Agent anterior sem desfecho confiavel (PLATFORM-LICENCIAMENTO-001, quinta rodada).
-- Contrato: docs/platform-licenciamento-001/t09-excecao-e-reconciliacao.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 061 a 067. Numero 068 conferido em 19/11 contra a dev e os PRs abertos desta
-- missao (061 a 067). Numeracao contigua: se a ordem de integracao mudar, e renumerada sem mudar o conteudo.
--
-- *** ELEVACAO: cria funcoes dos donos nekkora_identity_owner (017) e nekkora_licensing_owner (063), substitui uma funcao do
-- *** dono de licencas (065) e da a esses donos leituras e uma escrita novas (abaixo). Exige a janela de SUPERUSER do migrador.
-- *** SO LABORATORIO E TESTES ate autorizacao especifica: NAO esta em ops/homologacao/implantacao/elevacao.txt.
--
-- O QUE FAZ
--   1. CRIACAO EXCEPCIONAL. O operador de licencas da Nekkora cria uma organizacao indicando EXPLICITAMENTE a pessoa responsavel,
--      pelo e-mail verificado dela. A pessoa indicada recebe a administracao da nova organizacao e passa a ser a responsavel
--      (origem 4). O operador NAO recebe conta, papel nem acesso. organization_exceptional_creations guarda responsavel, operador,
--      organizacao do operador e justificativa. A excecao dispensa o perfil (um membro pode ser indicado) e os limites de criacao
--      e de vazias (e para isso que ela existe); a organizacao criada CONTA nos limites do responsavel dali em diante.
--      O Cliente continua sem poder ser indicado (D9).
--   2. RESULTADO DESCONHECIDO SEGURA A TRANSFERENCIA. Sessao do Agent anterior em OUTCOME_UNKNOWN (9) nao comprova que a execucao
--      terminou: license_work_in_progress passa a conta-la como trabalho possivel na origem, ate a reconciliacao.
--   3. RECONCILIACAO PELO SUPORTE (agent_run_reconciliations, so acrescimo). O operador registra, para uma sessao sem desfecho
--      confiavel (resultado desconhecido, ou pronta/iniciando/em execucao sem noticia ha pelo menos uma hora), a justificativa e a
--      VERIFICACAO REALIZADA. A sessao NAO muda: o estado continua o que a maquina informou (desconhecido continua desconhecido),
--      nenhum desfecho e gravado e nada declara a maquina livre. O efeito e so este: a sessao reconciliada deixa de segurar a
--      transferencia e a vaga de execucao.
--      Se a maquina voltar a informar DEPOIS da reconciliacao (last_report_at posterior), a reconciliacao deixa de valer para
--      aquela sessao: ela volta a contar, e pede nova reconciliacao.
--   4. A vaga NAO e liberada pelo tempo decorrido: a hora sem noticia so permite PEDIR a reconciliacao; quem libera e o ato.
--
-- O QUE NAO FAZ
--   - nao encerra, nao altera e nao apaga sessao nenhuma; nao muda o que a tela mostra como estado da sessao;
--   - nao faz o resultado desconhecido ocupar vaga de execucao (so segura a transferencia): a sessao ja terminou para a maquina;
--   - nao da ao operador leitura de eventos, registros ou resultados da organizacao alvo: a lista traz so identificador, automacao,
--     estado e instantes;
--   - nao muda a criacao por perfil da 067.
--
-- LEITURAS E ESCRITA NOVAS DOS DONOS
--   nekkora_licensing_owner: le de agent_runs tambem agent_run_id, worker_id, registered_at e last_report_at, e o nome das
--   automacoes; escreve agent_run_reconciliations.
--   nekkora_identity_owner: executa license_op_operator_organization (o operador esta no cadastro? de qual organizacao?); escreve
--   organization_exceptional_creations.
--   nekkora_platform e nekkora_ingestion: leem agent_run_reconciliations da organizacao em contexto (a contagem de vagas roda com
--   o papel de quem chama).
--
-- DADOS EXISTENTES: nenhuma linha existente e alterada. Sessoes ja em resultado desconhecido passam a segurar a transferencia
-- da automacao delas ate serem reconciliadas.
--
-- REVERSAO (manual, em janela de elevacao; so sem criacoes excepcionais e sem reconciliacoes):
--   recriar license_work_in_progress com o corpo da 065 e capacity_state com o corpo da 066; DROP das funcoes novas; DROP das
--   duas tabelas; devolver a CHECK de organization_responsibility.source a (1, 2, 3); retirar os privilegios desta migration.

BEGIN;

-- === 1. Pre-condicao ===
DO $$
BEGIN
    IF to_regclass('public.organization_responsibility') IS NULL OR to_regprocedure('public.license_work_in_progress(uuid, uuid)') IS NULL
       OR to_regprocedure('public.capacity_state(uuid)') IS NULL THEN
        RAISE EXCEPTION '068 exige a 065, a 066 e a 067 aplicadas';
    END IF;
    IF to_regclass('public.agent_run_reconciliations') IS NOT NULL OR to_regclass('public.organization_exceptional_creations') IS NOT NULL THEN
        RAISE EXCEPTION '068: tabelas ja existem';
    END IF;
END;
$$;

-- === 2. Criacao excepcional ===
ALTER TABLE public.organization_responsibility DROP CONSTRAINT organization_responsibility_source_check;
ALTER TABLE public.organization_responsibility ADD CONSTRAINT organization_responsibility_source_check CHECK (source IN (1, 2, 3, 4));
COMMENT ON TABLE public.organization_responsibility IS
    'Responsavel pela organizacao (067): a identidade de quem e contado o limite de organizacoes vazias e quem pode desativa-la e '
    'reativa-la. source: 1=criou direto, 2=escolhida na aprovacao de um pedido, 3=autor registrado na 034 (organizacao anterior a 067), '
    '4=indicada pelo operador da Nekkora na criacao excepcional (068). Nao e propriedade e, sozinha, nao concede permissao.';

CREATE TABLE public.organization_exceptional_creations (
    organization_id           UUID PRIMARY KEY REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    responsible_identity_id   UUID NOT NULL REFERENCES public.identities (identity_id) ON DELETE RESTRICT,
    operator_user_id          UUID NOT NULL REFERENCES public.users (user_id) ON DELETE RESTRICT,
    operator_organization_id  UUID NOT NULL REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    justification             TEXT NOT NULL CHECK (length(btrim(justification)) BETWEEN 10 AND 1000),
    created_at                TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (operator_organization_id <> organization_id)
);
COMMENT ON TABLE public.organization_exceptional_creations IS
    'Criacao excepcional de organizacao pelo operador da Nekkora (068), so acrescimo: a pessoa responsavel indicada, o operador, a '
    'organizacao do operador e a justificativa. O operador nao recebe acesso a organizacao criada.';

-- === 3. Reconciliacao das sessoes do Agent anterior ===
CREATE TABLE public.agent_run_reconciliations (
    reconciliation_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id        UUID NOT NULL REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    agent_run_id           UUID NOT NULL REFERENCES public.agent_runs (agent_run_id) ON DELETE RESTRICT,
    state_at               SMALLINT NOT NULL CHECK (state_at IN (1, 2, 3, 9)),
    last_report_at_seen    TIMESTAMPTZ NOT NULL,
    operator_user_id       UUID NOT NULL REFERENCES public.users (user_id) ON DELETE RESTRICT,
    justification          TEXT NOT NULL CHECK (length(btrim(justification)) BETWEEN 10 AND 1000),
    verification           TEXT NOT NULL CHECK (length(btrim(verification)) BETWEEN 10 AND 1000),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_agent_run_reconciliations_run ON public.agent_run_reconciliations (agent_run_id, created_at);
COMMENT ON TABLE public.agent_run_reconciliations IS
    'Reconciliacao, pelo suporte da Nekkora, de uma sessao do Agent anterior sem desfecho confiavel (068), so acrescimo. Guarda o '
    'estado que a sessao tinha (state_at: 9=resultado desconhecido; 1, 2, 3=sem encerramento), o operador, a justificativa e a '
    'verificacao realizada. NAO altera a sessao, nao grava desfecho e nao declara a maquina livre: a sessao reconciliada so deixa de '
    'segurar a transferencia de licenca e a vaga de execucao. Vale enquanto a maquina nao informar nada depois dela.';

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['organization_exceptional_creations', 'agent_run_reconciliations'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('REVOKE ALL ON public.%I FROM PUBLIC', t);
        EXECUTE format('CREATE TRIGGER trg_%s_prevent_update BEFORE UPDATE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.prevent_update()', t, t);
        EXECUTE format('CREATE TRIGGER trg_%s_prevent_delete BEFORE DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.prevent_delete()', t, t);
        EXECUTE format('CREATE TRIGGER trg_%s_prevent_truncate BEFORE TRUNCATE ON public.%I FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate()', t, t);
    END LOOP;
END;
$$;

GRANT SELECT, INSERT ON public.organization_exceptional_creations TO nekkora_identity_owner;
CREATE POLICY identity_owner_all ON public.organization_exceptional_creations AS PERMISSIVE FOR ALL TO nekkora_identity_owner
    USING (true) WITH CHECK (true);

GRANT SELECT, INSERT ON public.agent_run_reconciliations TO nekkora_licensing_owner;
CREATE POLICY licensing_owner_all ON public.agent_run_reconciliations AS PERMISSIVE FOR ALL TO nekkora_licensing_owner
    USING (true) WITH CHECK (true);
-- a contagem de vagas (066) roda com o papel de quem chama: os dois servicos leem as reconciliacoes da organizacao em contexto
GRANT SELECT ON public.agent_run_reconciliations TO nekkora_platform, nekkora_ingestion;
CREATE POLICY tenant_request_context ON public.agent_run_reconciliations AS PERMISSIVE FOR SELECT TO nekkora_platform, nekkora_ingestion
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid);

GRANT SELECT (agent_run_id, worker_id, registered_at, last_report_at) ON public.agent_runs TO nekkora_licensing_owner;
GRANT SELECT (name) ON public.automations TO nekkora_licensing_owner;

-- === 4. A sessao ainda segura transferencia e vaga? ===
-- Reconciliada = ha reconciliacao registrada DEPOIS da ultima noticia da maquina sobre a sessao.
CREATE FUNCTION public.agent_run_reconciled(p_run UUID, p_last_report TIMESTAMPTZ) RETURNS BOOLEAN
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM public.agent_run_reconciliations c WHERE c.agent_run_id = p_run AND c.created_at >= p_last_report);
$$;
REVOKE ALL ON FUNCTION public.agent_run_reconciled(uuid, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.agent_run_reconciled(uuid, timestamptz) TO nekkora_platform, nekkora_ingestion, nekkora_licensing_owner;

-- Trabalho em andamento de uma automacao (065), agora com o resultado desconhecido do Agent anterior e sem as sessoes reconciliadas.
CREATE OR REPLACE FUNCTION public.license_work_in_progress(p_org UUID, p_automation UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM public.executions e WHERE e.organization_id = p_org AND e.automation_id = p_automation AND e.status IN (1, 2, 3))
        -- Agent anterior: sessao pronta, iniciando ou em execucao, e a de RESULTADO DESCONHECIDO (9), que nao comprova o termino.
        -- So a reconciliacao pelo suporte (068), posterior a ultima noticia da maquina, tira a sessao daqui
        OR EXISTS (SELECT 1 FROM public.agent_runs r WHERE r.organization_id = p_org AND r.automation_id = p_automation
                      AND r.supervisor_state IN (1, 2, 3, 9) AND NOT public.agent_run_reconciled(r.agent_run_id, r.last_report_at))
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

-- Ocupacao e limites (066), sem as sessoes do Agent anterior reconciliadas. O restante do corpo e o da 066.
CREATE OR REPLACE FUNCTION public.capacity_state(p_org UUID)
RETURNS TABLE (model SMALLINT, workers_used BIGINT, workers_limit BIGINT, concurrent_used BIGINT, concurrent_limit BIGINT,
               pending_used BIGINT, pending_limit BIGINT, concurrent_remote BIGINT, concurrent_uncertain BIGINT,
               concurrent_platform BIGINT, concurrent_previous_agent BIGINT)
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    WITH u AS (
        SELECT
            -- execucao remota: entregue a maquina (a oferta so deixa de ocupar quando alguem a encerra) ate em execucao
            (SELECT count(*) FROM public.remote_attempts t WHERE t.organization_id = p_org AND t.state BETWEEN 2 AND 6) AS remote,
            -- ocupacao incerta (058, 060): ainda pode haver processo, e ninguem confirmou que nao ha
            (SELECT count(*) FROM public.remote_attempts t WHERE t.organization_id = p_org AND t.state IN (7, 8, 9, 10, 13)
                AND (t.state = 13 OR EXISTS (SELECT 1 FROM public.remote_attempt_process_reports r WHERE r.attempt_id = t.attempt_id AND r.kind IN (4, 5)))
                AND NOT EXISTS (SELECT 1 FROM public.remote_attempt_process_reports r WHERE r.attempt_id = t.attempt_id AND r.kind IN (1, 2))) AS uncertain,
            (SELECT count(*) FROM public.executions e WHERE e.organization_id = p_org AND e.status IN (2, 3)) AS platform,
            -- Agent anterior: pedido entregue (no prazo) ou aceito, e sessao pronta, iniciando ou em execucao que o suporte NAO
            -- reconciliou depois da ultima noticia da maquina (068). O tempo decorrido, sozinho, nunca libera a vaga
            (SELECT count(*) FROM public.run_requests q WHERE q.organization_id = p_org
                AND (q.state = 3 OR (q.state = 2 AND q.valid_until > clock_timestamp())))
            + (SELECT count(*) FROM public.agent_runs r WHERE r.organization_id = p_org AND r.supervisor_state IN (1, 2, 3)
                  AND NOT public.agent_run_reconciled(r.agent_run_id, r.last_report_at)) AS previous_agent,
            -- pendentes: na fila, ainda nao entregues, dentro do prazo
            (SELECT count(*) FROM public.remote_attempts t WHERE t.organization_id = p_org AND t.state = 1 AND t.offer_valid_until > clock_timestamp())
            + (SELECT count(*) FROM public.run_requests q WHERE q.organization_id = p_org AND q.state = 1 AND q.valid_until > clock_timestamp())
            + (SELECT count(*) FROM public.executions e WHERE e.organization_id = p_org AND e.status = 1) AS pending
    )
    SELECT m.model,
           (SELECT count(*) FROM public.workers w WHERE w.organization_id = p_org AND w.status <> 3),
           CASE WHEN m.model = 2 THEN public.technical_capacity_limit(1) END,
           u.remote + u.uncertain + u.platform + u.previous_agent,
           CASE WHEN m.model = 2 THEN public.technical_capacity_limit(2) END,
           u.pending,
           CASE WHEN m.model = 2 THEN public.technical_capacity_limit(3) END,
           u.remote, u.uncertain, u.platform, u.previous_agent
      FROM (SELECT public.license_model(p_org) AS model) m, u;
$$;

-- === 5. Funcoes do operador (dono de licencas) ===

-- A organizacao do operador ativo (nulo: nao e operador). Para o dono da identidade conferir quem cria por excecao.
CREATE FUNCTION public.license_op_operator_organization(p_operator UUID) RETURNS UUID
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT o.organization_id FROM public.license_operators o WHERE o.operator_user_id = p_operator AND o.revoked_at IS NULL
$$;

-- Sessoes do Agent anterior da organizacao alvo que ainda seguram transferencia ou vaga. So identificadores, estado e instantes.
-- reconcilable: resultado desconhecido, ou sem encerramento e sem noticia ha pelo menos uma hora.
CREATE FUNCTION public.license_op_unresolved_agent_runs(p_operator UUID, p_org UUID)
RETURNS TABLE (agent_run_id UUID, automation_id UUID, automation_name TEXT, worker_id UUID, state SMALLINT, registered_at TIMESTAMPTZ,
               last_report_at TIMESTAMPTZ, reconcilable BOOLEAN, holds_slot BOOLEAN)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_operator_org UUID := public.license_op_operator_organization(p_operator);
BEGIN
    IF v_operator_org IS NULL OR p_org IS NULL OR v_operator_org = p_org THEN
        RAISE EXCEPTION 'ato recusado: OPERATOR_NOT_AUTHORIZED' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
    SELECT r.agent_run_id, r.automation_id, a.name, r.worker_id, r.supervisor_state, r.registered_at, r.last_report_at,
           r.supervisor_state = 9 OR r.last_report_at <= pg_catalog.clock_timestamp() - INTERVAL '1 hour',
           r.supervisor_state IN (1, 2, 3)
      FROM public.agent_runs r
      JOIN public.automations a ON a.organization_id = r.organization_id AND a.automation_id = r.automation_id
     WHERE r.organization_id = p_org AND r.supervisor_state IN (1, 2, 3, 9)
       AND NOT public.agent_run_reconciled(r.agent_run_id, r.last_report_at)
     ORDER BY r.last_report_at, r.agent_run_id
     LIMIT 200;
END;
$$;

-- Reconcilia UMA sessao. Nao altera a sessao. Devolve o identificador da reconciliacao.
CREATE FUNCTION public.license_op_reconcile_agent_run(p_operator UUID, p_org UUID, p_run UUID, p_justification TEXT, p_verification TEXT)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    r RECORD;
    v_id UUID;
BEGIN
    PERFORM public.license_op_require(p_operator, p_org, p_justification);
    IF p_verification IS NULL OR pg_catalog.length(pg_catalog.btrim(p_verification)) NOT BETWEEN 10 AND 1000 THEN
        RAISE EXCEPTION 'ato recusado: VERIFICATION_REQUIRED' USING ERRCODE = '23514';
    END IF;
    -- a mesma trava da capacidade (066): reserva de vaga e reconciliacao nao se cruzam
    PERFORM public.capacity_lock(p_org);
    SELECT x.agent_run_id, x.supervisor_state, x.last_report_at INTO r FROM public.agent_runs x
     WHERE x.organization_id = p_org AND x.agent_run_id = p_run;
    IF r.agent_run_id IS NULL THEN
        RAISE EXCEPTION 'ato recusado: RUN_NOT_FOUND' USING ERRCODE = '23514';
    END IF;
    IF r.supervisor_state NOT IN (1, 2, 3, 9) THEN
        RAISE EXCEPTION 'ato recusado: RUN_HAS_OUTCOME' USING ERRCODE = '23514';
    END IF;
    IF public.agent_run_reconciled(r.agent_run_id, r.last_report_at) THEN
        RAISE EXCEPTION 'ato recusado: RUN_ALREADY_RECONCILED' USING ERRCODE = '23514';
    END IF;
    -- sem encerramento: so depois de uma hora sem noticia da maquina. A hora nao libera nada; so permite o ato
    IF r.supervisor_state IN (1, 2, 3) AND r.last_report_at > pg_catalog.clock_timestamp() - INTERVAL '1 hour' THEN
        RAISE EXCEPTION 'ato recusado: RUN_RECENTLY_REPORTED' USING ERRCODE = '23514';
    END IF;
    INSERT INTO public.agent_run_reconciliations (organization_id, agent_run_id, state_at, last_report_at_seen, operator_user_id,
                                                  justification, verification)
    VALUES (p_org, r.agent_run_id, r.supervisor_state, r.last_report_at, p_operator, pg_catalog.btrim(p_justification),
            pg_catalog.btrim(p_verification))
    RETURNING reconciliation_id INTO v_id;
    RETURN v_id;
END;
$$;

DO $$
DECLARE
    f TEXT;
BEGIN
    FOREACH f IN ARRAY ARRAY['public.license_op_operator_organization(uuid)', 'public.license_op_unresolved_agent_runs(uuid, uuid)',
                             'public.license_op_reconcile_agent_run(uuid, uuid, uuid, text, text)'] LOOP
        EXECUTE format('ALTER FUNCTION %s OWNER TO nekkora_licensing_owner', f);
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', f);
    END LOOP;
END;
$$;
GRANT EXECUTE ON FUNCTION public.license_op_unresolved_agent_runs(uuid, uuid), public.license_op_reconcile_agent_run(uuid, uuid, uuid, text, text)
    TO nekkora_platform;
GRANT EXECUTE ON FUNCTION public.license_op_operator_organization(uuid) TO nekkora_identity_owner;
GRANT EXECUTE ON FUNCTION public.capacity_lock(uuid) TO nekkora_licensing_owner;

-- === 6. Criacao excepcional (dono da identidade) ===
-- Devolve 'CREATED:<organizacao>' ou o motivo da recusa. Nao recebe sessao: quem chama (a Platform) ja conferiu a sessao, o
-- segundo fator e a confirmacao reforcada do operador; aqui o banco reconfere que ele esta no cadastro de operadores.
CREATE FUNCTION public.identity_create_organization_by_operator(p_operator UUID, p_responsible_email TEXT, p_name TEXT, p_legal_name TEXT,
                                                                p_justification TEXT)
RETURNS TEXT LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    v_operator_org UUID;
    v_identity UUID;
    v_new UUID;
    v_out TEXT;
BEGIN
    BEGIN
        PERFORM public.identity_require_read_committed();
        v_operator_org := public.license_op_operator_organization(p_operator);
        IF v_operator_org IS NULL THEN
            v_out := 'OPERATOR_NOT_AUTHORIZED';
        ELSIF p_justification IS NULL OR pg_catalog.length(pg_catalog.btrim(p_justification)) NOT BETWEEN 10 AND 1000 THEN
            v_out := 'JUSTIFICATION_REQUIRED';
        ELSIF p_name IS NULL OR pg_catalog.char_length(pg_catalog.btrim(p_name)) NOT BETWEEN 1 AND 120
              OR p_legal_name IS NULL OR pg_catalog.char_length(pg_catalog.btrim(p_legal_name)) NOT BETWEEN 1 AND 120 THEN
            v_out := 'INVALID_NAME';
        ELSE
            -- a pessoa indicada: identidade ativa, com e-mail verificado. Sob a trava dela (a mesma das criacoes e aprovacoes)
            SELECT x.identity_id INTO v_identity FROM public.identities x
             WHERE p_responsible_email IS NOT NULL AND x.email_normalized = pg_catalog.lower(pg_catalog.btrim(p_responsible_email))
               AND x.status = 2 AND x.email_verified_at IS NOT NULL FOR UPDATE;
            IF v_identity IS NULL THEN
                v_out := 'RESPONSIBLE_NOT_FOUND';
            ELSE
                PERFORM pg_catalog.set_config('nekkora.fn_organization_id', '', true);
                PERFORM pg_catalog.set_config('nekkora.fn_identity_id', v_identity::text, true);
                IF public.identity_org_profile(v_identity) = 'CLIENT' THEN
                    v_out := 'RESPONSIBLE_IS_CLIENT';
                ELSE
                    v_new := public.identity_provision_organization(v_identity, pg_catalog.btrim(p_name), pg_catalog.btrim(p_legal_name),
                                                                    4::SMALLINT, NULL);
                    IF v_new IS NULL THEN
                        v_out := 'RESPONSIBLE_NOT_FOUND';
                    ELSE
                        INSERT INTO public.organization_exceptional_creations (organization_id, responsible_identity_id, operator_user_id,
                                                                               operator_organization_id, justification)
                        VALUES (v_new, v_identity, p_operator, v_operator_org, pg_catalog.btrim(p_justification));
                        v_out := 'CREATED:' || v_new::text;
                    END IF;
                END IF;
            END IF;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
    RETURN v_out;
END;
$$;
ALTER FUNCTION public.identity_create_organization_by_operator(uuid, text, text, text, text) OWNER TO nekkora_identity_owner;
REVOKE ALL ON FUNCTION public.identity_create_organization_by_operator(uuid, text, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.identity_create_organization_by_operator(uuid, text, text, text, text) TO nekkora_platform;

-- === 7. Invariantes ===
DO $$
BEGIN
    IF has_table_privilege('nekkora_platform', 'public.agent_run_reconciliations', 'INSERT, UPDATE, DELETE')
       OR has_table_privilege('nekkora_ingestion', 'public.agent_run_reconciliations', 'INSERT, UPDATE, DELETE')
       OR has_table_privilege('nekkora_platform', 'public.organization_exceptional_creations', 'SELECT, INSERT, UPDATE, DELETE') THEN
        RAISE EXCEPTION '068: os servicos nao podem escrever reconciliacoes nem ler ou escrever criacoes excepcionais';
    END IF;
    IF has_function_privilege('nekkora_ingestion', 'public.license_op_reconcile_agent_run(uuid, uuid, uuid, text, text)', 'EXECUTE')
       OR has_function_privilege('nekkora_ingestion', 'public.identity_create_organization_by_operator(uuid, text, text, text, text)', 'EXECUTE') THEN
        RAISE EXCEPTION '068: o canal das maquinas nao executa atos do operador';
    END IF;
    IF NOT (SELECT prosecdef FROM pg_proc WHERE oid = 'public.license_work_in_progress(uuid, uuid)'::regprocedure) THEN
        RAISE EXCEPTION '068: license_work_in_progress deixou de ser do dono de licencas';
    END IF;
END;
$$;

COMMIT;
