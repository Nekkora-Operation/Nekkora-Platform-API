-- Nekkora Platform - capacidade TECNICA das organizacoes do modelo por licenca, no lugar da ativacao comercial
-- (PLATFORM-LICENCIAMENTO-001, decisao D2). Contrato: docs/platform-licenciamento-001/t07-capacidade-tecnica.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 061 a 065. Numero 066 conferido em 19/11 contra a dev (86591bb; a maior era 060)
-- e os PRs abertos desta missao (061 a 065). Numeracao contigua: se a ordem de integracao mudar, e renumerada sem mudar o
-- conteudo.
--
-- SO VALE PARA ORGANIZACAO NO MODELO POR LICENCA. No modelo anterior, a ativacao (022) e os limites por organizacao (010)
-- continuam exatamente como estao.
--
-- O QUE FAZ
--   1. Tabela technical_capacity_limits: tres limites TECNICOS, iguais para todas as organizacoes do modelo por licenca,
--      configuraveis: 1 = Workers cadastrados (50), 2 = execucoes simultaneas (5), 3 = execucoes pendentes (50). Sao protecao
--      contra abuso e sobrecarga; nao sao contratados e nao tem relacao com a quantidade de licencas.
--   2. Cadastro de Worker: no modelo por licenca, o limite e o tecnico. A organizacao NAO precisa mais ter sido ativada pelo
--      operador para registrar maquina. (Executar e monitorar continuam dependendo de licenca: 064.)
--   3. VAGA de execucao: no modelo por licenca, o limite de simultaneas e UM SO para a organizacao e e compartilhado pelos
--      fluxos de execucao. A vaga e reservada NO MOMENTO EM QUE O TRABALHO E AUTORIZADO A INICIAR, sob uma trava da
--      organizacao (capacity_lock), e fica ocupada ate o trabalho terminar:
--        - execucao remota: quando o servidor ENTREGA a tentativa a maquina (QUEUED -> OFFERED). Sem vaga, a tentativa NAO e
--          entregue e continua na fila (pendente) ate haver vaga ou vencer o prazo da oferta. Com limite 5, a sexta espera;
--        - pedido ao Agent anterior: quando o servidor ENTREGA o pedido a maquina (REQUESTED -> DELIVERED). Sem vaga, o
--          pedido nao e entregue e continua pendente ate haver vaga ou vencer;
--        - execucao da Platform (executions): na reserva (PENDING -> STARTING). Sem vaga, continua pendente.
--      Ocupam vaga: tentativa remota oferecida ate em execucao; tentativa remota com OCUPACAO INCERTA (058, 060: terminou
--      sem confirmacao, ou com processo vivo / sem a maquina saber, enquanto nem a maquina nem a declaracao de um
--      administrador confirmarem que nao ha processo); execucao da Platform iniciando ou em execucao; pedido ao Agent
--      anterior entregue (no prazo) ou aceito; sessao do Agent anterior pronta, iniciando ou em execucao.
--      A sessao que o Agent anterior inicia NA MAQUINA, sem pedido, nao pode ser segurada pelo servidor: o registro e aceito
--      e passa a ocupar vaga (a ocupacao pode passar do limite por isso; enquanto passar, nada novo e entregue).
--   4. FILA: uma tentativa remota nova ou um pedido novo ao Agent anterior e recusado quando a organizacao ja tem o limite
--      de PENDENTES (na fila, ainda nao entregues, no prazo). A recusa e de CAPACIDADE (CAPACITY_PENDING_LIMIT), nunca de
--      licenca, e nao consome nem libera licenca nenhuma. Nao ha mais recusa da solicitacao por simultaneas: ela espera.
--   5. capacity_state(org): a ocupacao (com a composicao por fluxo) e os limites, para a aplicacao responder e a tela mostrar,
--      separados das licencas.
--
-- O QUE NAO FAZ
--   - nao muda o modelo de nenhuma organizacao; nao ativa nem desativa ninguem; nao apaga a ativacao nem os limites da 010/022
--     (continuam valendo no modelo anterior e ficam guardados, sem efeito, para quem passar ao modelo por licenca);
--   - nao cria limite por organizacao: o valor e um so. Mudar o valor e procedimento administrativo (UPDATE pelo migrador,
--     com updated_by preenchido), nao ato do dia a dia;
--   - nao substitui os limites da 010 na execucao da Platform (tabela executions): ela nao tem rota de criacao hoje e continua
--     passando TAMBEM pelas regras da 010; a 066 so acrescenta a ela a vaga compartilhada;
--   - nao segura nem recusa o registro de uma sessao que o Agent anterior ja iniciou na maquina (ver 3);
--   - nao cria fila com ordem garantida: havendo vaga, recebe a maquina que perguntar primeiro;
--   - nao limita a execucao local privada do Agent Client, que nao passa pela Platform.
--
-- DADOS EXISTENTES: nenhuma linha e alterada. Tres linhas novas na tabela de limites tecnicos.
--
-- REVERSAO (manual; so enquanto nenhuma organizacao estiver no modelo por licenca):
--   BEGIN;
--   DROP TRIGGER trg_remote_attempts_capacity ON public.remote_attempts;
--   DROP TRIGGER trg_remote_attempts_capacity_slot ON public.remote_attempts;
--   DROP TRIGGER trg_run_requests_capacity ON public.run_requests;
--   DROP TRIGGER trg_run_requests_capacity_slot ON public.run_requests;
--   DROP TRIGGER trg_executions_zz_capacity_slot ON public.executions;
--   DROP FUNCTION public.capacity_pending_on_insert(), public.capacity_slot_on_update(), public.capacity_reserve(uuid), public.capacity_lock(uuid);
--   -- recriar enforce_worker_registration_capacity() com o corpo da 010
--   DROP FUNCTION public.capacity_state(uuid), public.technical_capacity_limit(integer);
--   DROP TABLE public.technical_capacity_limits;
--   COMMIT;
--
-- SEM ELEVACAO: uma tabela, funcoes e gatilhos SECURITY INVOKER do proprio dono, um gatilho da 010 substituido. Nenhum papel
-- novo, nenhuma funcao SECURITY DEFINER.

BEGIN;

-- === 1. Pre-condicao ===
DO $$
BEGIN
    IF to_regprocedure('public.license_model(uuid)') IS NULL THEN
        RAISE EXCEPTION '066 exige a 064 aplicada';
    END IF;
    IF to_regclass('public.technical_capacity_limits') IS NOT NULL THEN
        RAISE EXCEPTION '066: technical_capacity_limits ja existe';
    END IF;
    IF EXISTS (SELECT 1 FROM public.organization_license_enforcement WHERE model = 2) THEN
        RAISE EXCEPTION '066: ha organizacao no modelo por licenca antes de a capacidade tecnica existir';
    END IF;
END;
$$;

-- === 2. Os limites tecnicos ===
CREATE TABLE public.technical_capacity_limits (
    resource_code  SMALLINT PRIMARY KEY CHECK (resource_code IN (1, 2, 3)),
    limit_value    BIGINT NOT NULL CHECK (limit_value BETWEEN 1 AND 100000),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_by     TEXT NOT NULL CHECK (length(btrim(updated_by)) BETWEEN 3 AND 200)
);
COMMENT ON TABLE public.technical_capacity_limits IS
    'Limites TECNICOS de capacidade das organizacoes do modelo por licenca (066, D2): 1=Workers cadastrados, 2=execucoes simultaneas, '
    '3=execucoes pendentes. Um valor para todas. Nao sao contratados e nao tem relacao com licencas. updated_by: quem autorizou a mudanca.';
INSERT INTO public.technical_capacity_limits (resource_code, limit_value, updated_by) VALUES
    (1, 50, 'migration 066 (decisao D2 de 19/11/2026)'),
    (2, 5, 'migration 066 (decisao D2 de 19/11/2026)'),
    (3, 50, 'migration 066 (decisao D2 de 19/11/2026)');
ALTER TABLE public.technical_capacity_limits ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.technical_capacity_limits FORCE ROW LEVEL SECURITY;
-- configuracao global, sem dado de organizacao: quem executa as regras le as tres linhas; ninguem alem do migrador escreve
CREATE POLICY capacity_read ON public.technical_capacity_limits AS PERMISSIVE FOR SELECT TO nekkora_platform, nekkora_ingestion USING (true);
REVOKE ALL ON public.technical_capacity_limits FROM PUBLIC;
GRANT SELECT ON public.technical_capacity_limits TO nekkora_platform, nekkora_ingestion;

CREATE FUNCTION public.technical_capacity_limit(p_resource_code INTEGER) RETURNS BIGINT
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT l.limit_value FROM public.technical_capacity_limits l WHERE l.resource_code = p_resource_code;
$$;

-- === 3. Ocupacao e limites de uma organizacao do modelo por licenca ===
-- Nenhum privilegio novo: os dois papeis de servico ja leem, no contexto da propria organizacao, as tabelas contadas aqui.
-- Nulo em todas as colunas de limite: a organizacao esta no modelo anterior (a capacidade dela e a da 010/022).
-- concurrent_used = remote + uncertain + platform + previous_agent (a composicao vem ao lado, para a tela explicar).
CREATE FUNCTION public.capacity_state(p_org UUID)
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
            -- Agent anterior: pedido entregue (no prazo) ou aceito, e sessao pronta, iniciando ou em execucao
            (SELECT count(*) FROM public.run_requests q WHERE q.organization_id = p_org
                AND (q.state = 3 OR (q.state = 2 AND q.valid_until > clock_timestamp())))
            + (SELECT count(*) FROM public.agent_runs r WHERE r.organization_id = p_org AND r.supervisor_state IN (1, 2, 3)) AS previous_agent,
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
COMMENT ON FUNCTION public.capacity_state(uuid) IS
    'Ocupacao e limites tecnicos de capacidade da organizacao (066): Workers cadastrados; vagas de execucao ocupadas (execucao remota '
    'entregue ate em execucao, ocupacao incerta, execucao da Platform, Agent anterior) e pendentes (na fila, no prazo). Limites nulos = '
    'organizacao no modelo anterior. Nao olha licencas.';

-- A trava da capacidade da organizacao: toda reserva de vaga e toda entrada na fila passam por ela, em qualquer fluxo.
CREATE FUNCTION public.capacity_lock(p_org UUID) RETURNS VOID
LANGUAGE sql VOLATILE SET search_path = pg_catalog, pg_temp AS $$
    SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('organization_capacity:' || p_org::text, 0));
$$;

-- RESERVA: ha vaga para autorizar mais um inicio? Pega a trava (que vale ate o fim da transacao) e conta. Quem chama faz a
-- transicao que ocupa a vaga NA MESMA transacao; os gatilhos abaixo reconferem. Modelo anterior: sempre verdadeiro.
CREATE FUNCTION public.capacity_reserve(p_org UUID) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    c RECORD;
BEGIN
    IF public.license_model(p_org) <> 2 THEN
        RETURN true;
    END IF;
    PERFORM public.capacity_lock(p_org);
    SELECT s.concurrent_used, s.concurrent_limit INTO c FROM public.capacity_state(p_org) s;
    RETURN c.concurrent_limit IS NOT NULL AND c.concurrent_used < c.concurrent_limit;
END;
$$;
COMMENT ON FUNCTION public.capacity_reserve(uuid) IS
    'Reserva de vaga de execucao (066): sob a trava da capacidade da organizacao, diz se ha vaga para autorizar mais um inicio. A '
    'transicao que ocupa a vaga e feita por quem chama, na mesma transacao.';

-- === 4. Cadastro de Worker: no modelo por licenca, o limite e o tecnico e nao depende da ativacao ===
CREATE OR REPLACE FUNCTION public.enforce_worker_registration_capacity() RETURNS TRIGGER AS $$
DECLARE
    lim RECORD;
    current_count BIGINT;
    technical BIGINT;
BEGIN
    PERFORM 1 FROM public.organizations WHERE organization_id = NEW.organization_id FOR UPDATE;

    -- (066) modelo por licenca: limite tecnico, igual para todas, sem ato de ativacao
    IF public.license_model(NEW.organization_id) = 2 THEN
        technical := public.technical_capacity_limit(1);
        SELECT count(*) INTO current_count FROM public.workers WHERE organization_id = NEW.organization_id AND status <> 3;
        IF technical IS NULL OR current_count >= technical THEN
            RAISE EXCEPTION 'limite de workers cadastrados atingido (organization_id=%, limite=%)', NEW.organization_id, technical;
        END IF;
        RETURN NEW;
    END IF;

    SELECT * INTO lim FROM public.get_effective_resource_limit(NEW.organization_id, 1);
    IF NOT FOUND THEN
        RAISE EXCEPTION 'organizacao sem configuracao de limite para workers (organization_id=%)', NEW.organization_id;
    END IF;

    IF lim.limit_mode = 1 THEN
        SELECT count(*) INTO current_count FROM public.workers WHERE organization_id = NEW.organization_id AND status <> 3;
        IF current_count >= lim.limit_value THEN
            RAISE EXCEPTION 'limite de workers cadastrados atingido (organization_id=%, limite=%)', NEW.organization_id, lim.limit_value;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- === 5. FILA: tentativa remota nova e pedido novo ao Agent anterior respeitam o limite de pendentes ===
CREATE FUNCTION public.capacity_pending_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    c RECORD;
BEGIN
    IF public.license_model(NEW.organization_id) <> 2 THEN
        RETURN NEW;
    END IF;
    PERFORM public.capacity_lock(NEW.organization_id);
    SELECT s.pending_used, s.pending_limit INTO c FROM public.capacity_state(NEW.organization_id) s;
    IF c.pending_limit IS NULL OR c.pending_used >= c.pending_limit THEN
        RAISE EXCEPTION 'trabalho recusado: CAPACITY_PENDING_LIMIT' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempts_capacity BEFORE INSERT ON public.remote_attempts
    FOR EACH ROW EXECUTE FUNCTION public.capacity_pending_on_insert();
CREATE TRIGGER trg_run_requests_capacity BEFORE INSERT ON public.run_requests
    FOR EACH ROW EXECUTE FUNCTION public.capacity_pending_on_insert();

-- === 5b. VAGA: a transicao que autoriza o inicio so acontece com vaga (a aplicacao pergunta antes; o banco garante) ===
CREATE FUNCTION public.capacity_slot_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NOT public.capacity_reserve(NEW.organization_id) THEN
        RAISE EXCEPTION 'inicio nao autorizado: CAPACITY_CONCURRENT_LIMIT (limite de execucoes simultaneas atingido)' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempts_capacity_slot BEFORE UPDATE ON public.remote_attempts
    FOR EACH ROW WHEN (OLD.state = 1 AND NEW.state = 2) EXECUTE FUNCTION public.capacity_slot_on_update();
CREATE TRIGGER trg_run_requests_capacity_slot BEFORE UPDATE ON public.run_requests
    FOR EACH ROW WHEN (OLD.state = 1 AND NEW.state = 2) EXECUTE FUNCTION public.capacity_slot_on_update();
-- "zz": depois do gatilho de reserva da 010, que trava a linha da organizacao. Ordem unica: linha da organizacao, trava da capacidade
CREATE TRIGGER trg_executions_zz_capacity_slot BEFORE UPDATE ON public.executions
    FOR EACH ROW WHEN (OLD.status = 1 AND NEW.status = 2) EXECUTE FUNCTION public.capacity_slot_on_update();

-- === 6. Privilegios ===
REVOKE ALL ON FUNCTION public.technical_capacity_limit(integer), public.capacity_state(uuid), public.capacity_lock(uuid), public.capacity_reserve(uuid),
    public.capacity_pending_on_insert(), public.capacity_slot_on_update() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.technical_capacity_limit(integer), public.capacity_state(uuid), public.capacity_lock(uuid), public.capacity_reserve(uuid)
    TO nekkora_platform, nekkora_ingestion;

-- === 7. Invariantes ===
DO $$
BEGIN
    IF (SELECT string_agg(resource_code || '=' || limit_value, ',' ORDER BY resource_code) FROM public.technical_capacity_limits) <> '1=50,2=5,3=50' THEN
        RAISE EXCEPTION '066: limites tecnicos iniciais fora do decidido (50 Workers, 5 simultaneas, 50 pendentes)';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.technical_capacity_limits', 'INSERT, UPDATE, DELETE')
       OR has_table_privilege('nekkora_ingestion', 'public.technical_capacity_limits', 'INSERT, UPDATE, DELETE') THEN
        RAISE EXCEPTION '066: um papel de servico altera os limites tecnicos';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_proc p WHERE p.pronamespace = 'public'::regnamespace
                  AND p.proname IN ('technical_capacity_limit', 'capacity_state', 'capacity_lock', 'capacity_reserve', 'capacity_pending_on_insert',
                                    'capacity_slot_on_update', 'enforce_worker_registration_capacity')
                  AND p.prosecdef) THEN
        RAISE EXCEPTION '066: funcao SECURITY DEFINER inesperada';
    END IF;
    IF (SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal AND tgname IN ('trg_remote_attempts_capacity', 'trg_remote_attempts_capacity_slot',
            'trg_run_requests_capacity', 'trg_run_requests_capacity_slot', 'trg_executions_zz_capacity_slot')) <> 5 THEN
        RAISE EXCEPTION '066: gatilhos de capacidade incompletos';
    END IF;
END;
$$;

COMMIT;
