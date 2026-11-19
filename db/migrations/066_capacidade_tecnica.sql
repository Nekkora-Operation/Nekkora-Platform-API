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
--   3. Execucao remota: no modelo por licenca, uma tentativa nova e recusada quando a organizacao ja tem o limite de
--      execucoes em andamento (recebidas pela maquina e nao terminadas) ou de pendentes (na fila ou oferecidas, no prazo).
--      A recusa e de CAPACIDADE (CAPACITY_CONCURRENT_LIMIT, CAPACITY_PENDING_LIMIT), nunca de licenca, e nao consome nem
--      libera licenca nenhuma.
--   4. capacity_state(org): a ocupacao e os limites, para a aplicacao responder e a tela mostrar, separados das licencas.
--
-- O QUE NAO FAZ
--   - nao muda o modelo de nenhuma organizacao; nao ativa nem desativa ninguem; nao apaga a ativacao nem os limites da 010/022
--     (continuam valendo no modelo anterior e ficam guardados, sem efeito, para quem passar ao modelo por licenca);
--   - nao cria limite por organizacao: o valor e um so. Mudar o valor e procedimento administrativo (UPDATE pelo migrador,
--     com updated_by preenchido), nao ato do dia a dia;
--   - nao mexe na execucao da Platform (tabela executions): ela nao tem rota de criacao hoje e segue pelas regras da 010;
--   - nao limita a execucao local privada do Agent Client, que nao passa pela Platform.
--
-- DADOS EXISTENTES: nenhuma linha e alterada. Tres linhas novas na tabela de limites tecnicos.
--
-- REVERSAO (manual; so enquanto nenhuma organizacao estiver no modelo por licenca):
--   BEGIN;
--   DROP TRIGGER trg_remote_attempts_capacity ON public.remote_attempts;
--   DROP FUNCTION public.capacity_remote_attempt_on_insert();
--   -- recriar enforce_worker_registration_capacity() com o corpo da 010
--   DROP FUNCTION public.capacity_state(uuid), public.technical_capacity_limit(integer);
--   DROP TABLE public.technical_capacity_limits;
--   COMMIT;
--
-- SEM ELEVACAO: uma tabela, funcoes e gatilho SECURITY INVOKER do proprio dono, um gatilho da 010 substituido. Nenhum papel
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
-- Nulo em todas as colunas de limite: a organizacao esta no modelo anterior (a capacidade dela e a da 010/022).
CREATE FUNCTION public.capacity_state(p_org UUID)
RETURNS TABLE (model SMALLINT, workers_used BIGINT, workers_limit BIGINT, concurrent_used BIGINT, concurrent_limit BIGINT,
               pending_used BIGINT, pending_limit BIGINT)
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT m.model,
           (SELECT count(*) FROM public.workers w WHERE w.organization_id = p_org AND w.status <> 3),
           CASE WHEN m.model = 2 THEN public.technical_capacity_limit(1) END,
           -- em andamento: a maquina recebeu e ainda nao terminou
           (SELECT count(*) FROM public.remote_attempts t WHERE t.organization_id = p_org AND t.state BETWEEN 3 AND 6),
           CASE WHEN m.model = 2 THEN public.technical_capacity_limit(2) END,
           -- pendentes: na fila ou oferecidas, dentro do prazo da oferta
           (SELECT count(*) FROM public.remote_attempts t WHERE t.organization_id = p_org AND t.state IN (1, 2) AND t.offer_valid_until > clock_timestamp()),
           CASE WHEN m.model = 2 THEN public.technical_capacity_limit(3) END
      FROM (SELECT public.license_model(p_org) AS model) m;
$$;
COMMENT ON FUNCTION public.capacity_state(uuid) IS
    'Ocupacao e limites tecnicos de capacidade da organizacao (066): Workers cadastrados, execucoes remotas em andamento e pendentes. '
    'Limites nulos = organizacao no modelo anterior. Nao olha licencas.';

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

-- === 5. Execucao remota: tentativa nova respeita os limites de simultaneas e de pendentes ===
CREATE FUNCTION public.capacity_remote_attempt_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    c RECORD;
BEGIN
    IF public.license_model(NEW.organization_id) <> 2 THEN
        RETURN NEW;
    END IF;
    -- a contagem e a gravacao sao uma coisa so: quem cria tentativa ja segura a linha da organizacao; o gatilho garante
    PERFORM 1 FROM public.organizations o WHERE o.organization_id = NEW.organization_id FOR UPDATE;
    SELECT * INTO c FROM public.capacity_state(NEW.organization_id);
    IF c.concurrent_used >= c.concurrent_limit THEN
        RAISE EXCEPTION 'tentativa recusada: CAPACITY_CONCURRENT_LIMIT' USING ERRCODE = '23514';
    END IF;
    IF c.pending_used >= c.pending_limit THEN
        RAISE EXCEPTION 'tentativa recusada: CAPACITY_PENDING_LIMIT' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempts_capacity BEFORE INSERT ON public.remote_attempts
    FOR EACH ROW EXECUTE FUNCTION public.capacity_remote_attempt_on_insert();

-- === 6. Privilegios ===
REVOKE ALL ON FUNCTION public.technical_capacity_limit(integer), public.capacity_state(uuid), public.capacity_remote_attempt_on_insert() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.technical_capacity_limit(integer), public.capacity_state(uuid) TO nekkora_platform, nekkora_ingestion;

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
                  AND p.proname IN ('technical_capacity_limit', 'capacity_state', 'capacity_remote_attempt_on_insert', 'enforce_worker_registration_capacity')
                  AND p.prosecdef) THEN
        RAISE EXCEPTION '066: funcao SECURITY DEFINER inesperada';
    END IF;
END;
$$;

COMMIT;
