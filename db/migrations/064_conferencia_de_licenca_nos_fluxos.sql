-- Nekkora Platform - a licenca passa a ser CONFERIDA pelo servidor nos fluxos de execucao e de monitoracao
-- (PLATFORM-LICENCIAMENTO-001, E3; decisoes D3, D8, S1). Contrato: docs/platform-licenciamento-001/t05-conferencia-nos-fluxos.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 061, 062 e 063. Numero 064 conferido em 19/11 contra a dev (86591bb; a maior era
-- 060) e os PRs abertos desta missao (061, 062, 063). Numeracao contigua: se a ordem de integracao mudar, e renumerada sem
-- mudar o conteudo.
--
-- SO VALE PARA ORGANIZACAO NO MODELO POR LICENCA (organization_license_enforcement.model = 2). Nenhuma organizacao esta nesse
-- modelo ao aplicar esta migration: ligar e ato do operador, por organizacao, com previa (063). No modelo anterior (sem linha
-- ou model = 1) todas as funcoes abaixo respondem "nao se aplica" e nada muda.
--
-- O QUE FAZ
--   1. Leituras de licenca, num lugar so (SECURITY INVOKER; quem chama so ve a organizacao do proprio contexto):
--        license_model(org)                      1 = anterior, 2 = por licenca
--        license_covered_at(org, automacao, t)   havia licenca vigente vinculada a automacao no instante t
--        license_coverage_end(org, automacao, t) ate quando a cobertura segue SEM LACUNA a partir de t (nulo = sem cobertura)
--        license_state(org, automacao)           LEGACY | OK | LICENSE_EXPIRED | LICENSE_REQUIRED
--        license_gate(org, automacao)            o mesmo, sob a trava compartilhada do modelo (o inicio de trabalho espera o
--                                                ato de ligar a exigencia terminar, e vice-versa)
--        license_run_basis(org, automacao, autorizacao, inicio)  a base de um registro do Agent anterior (item 4)
--   2. Trabalho NOVO exige licenca vigente (gatilhos; o banco reconfere, nao confia na aplicacao):
--        - solicitacao de execucao remota e cada tentativa nova (remote_attempt_blocker, que vale tambem na oferta e
--          imediatamente antes de iniciar);
--        - pedido de execucao ao Agent anterior;
--        - autorizacao de execucao local ou de monitoramento: so nasce com licenca vigente, e a validade dela nunca passa do
--          fim da cobertura sem lacuna (D3). Uma renovacao que comeca depois de um intervalo NAO estende a autorizacao.
--   3. S1: no modelo por licenca, a tentativa remota deixa de ser recusada por AUTHORIZATION_EXPIRED. A autorizacao da versao
--      para o Worker continua exigida e a revogacao continua valendo; o prazo comercial e o da licenca.
--   4. D8: o registro de execucao ou de sessao de monitoramento pelo Agent anterior (agent_runs) e aceito quando a autorizacao
--      usada VALIA NO INICIO informado e ha uma destas bases, gravada em agent_runs.license_basis:
--        1 = licenca vigente no inicio e ainda vigente no registro;
--        2 = licenca vigente no inicio; os dados chegaram depois do vencimento;
--        3 = TRANSICAO: autorizacao emitida antes de a organizacao passar ao modelo por licenca, dentro da validade ja emitida.
--      Fora disso o registro e recusado. Organizacao, automacao, versao, Worker e autorizacao vem das linhas do servidor (a
--      autorizacao e lida pela chave; a maquina so informa o instante do inicio - ver LIMITE).
--   5. O canal das maquinas (nekkora_ingestion) passa a LER licencas, periodos, vinculos e o modelo da organizacao do contexto.
--   6. Dois motivos novos de recusa da tentativa remota: LICENSE_REQUIRED e LICENSE_EXPIRED.
--
-- O QUE NAO FAZ
--   - nao liga a exigencia em nenhuma organizacao; nao cria licenca nem vinculo;
--   - nao interrompe trabalho ja admitido: eventos, registros, resultados e reconciliacao das execucoes admitidas na validade
--     nao reconferem licenca;
--   - nao altera nem revoga autorizacao existente; nao muda a ativacao nem os limites (etapa propria);
--   - nao bloqueia de imediato uma maquina desconectada: a autorizacao ja entregue vale sem conexao ate a validade.
--
-- LIMITE (D8): quando a execucao aconteceu sem conexao, o instante do inicio e o que a maquina informa. O servidor o limita
-- ao intervalo [emissao da autorizacao, agora] e exige que a autorizacao valesse nele, mas nao tem como provar o relogio da
-- maquina. A base 2 e a base 3 dependem desse instante.
--
-- DADOS EXISTENTES: agent_runs ganha a coluna license_basis, nula em todas as linhas existentes (modelo anterior).
--
-- REVERSAO (manual; so enquanto nenhuma organizacao estiver no modelo por licenca):
--   BEGIN;
--   DROP TRIGGER trg_remote_executions_license ON public.remote_executions;
--   DROP TRIGGER trg_run_requests_license ON public.run_requests;
--   DROP TRIGGER trg_local_run_authorizations_license ON public.local_run_authorizations;
--   DROP TRIGGER trg_agent_runs_license ON public.agent_runs;
--   DROP FUNCTION public.license_work_on_insert(), public.license_authorization_on_insert(), public.license_agent_run_on_insert();
--   -- recriar remote_attempt_blocker() com o corpo da 046 e a CHECK de refusal_reason com a lista da 053
--   ALTER TABLE public.agent_runs DROP COLUMN license_basis;
--   DROP FUNCTION public.license_run_basis(uuid, uuid, uuid, timestamptz), public.license_gate(uuid, uuid), public.license_state(uuid, uuid),
--                 public.license_coverage_end(uuid, uuid, timestamptz), public.license_covered_at(uuid, uuid, timestamptz), public.license_model(uuid);
--   -- e retirar de nekkora_ingestion a leitura e a politica tenant_ingestion_context das quatro tabelas
--   COMMIT;
--
-- SEM ELEVACAO: funcoes e gatilhos SECURITY INVOKER do proprio dono, uma coluna, uma CHECK trocada, leitura para um papel
-- existente. Nenhum papel novo, nenhuma funcao SECURITY DEFINER.

BEGIN;

-- === 1. Pre-condicao ===
DO $$
BEGIN
    IF to_regclass('public.organization_license_enforcement') IS NULL OR to_regprocedure('public.license_op_set_model(uuid, uuid, smallint, text, jsonb)') IS NULL THEN
        RAISE EXCEPTION '064 exige a 061, a 062 e a 063 aplicadas';
    END IF;
    IF to_regprocedure('public.remote_attempt_blocker(uuid, uuid, uuid, uuid, uuid)') IS NULL OR to_regclass('public.run_requests') IS NULL THEN
        RAISE EXCEPTION '064 exige a 030 e a 046 aplicadas';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conrelid = 'public.remote_attempts'::regclass AND c.conname = 'remote_attempts_refusal_reason_check') THEN
        RAISE EXCEPTION '064: a CHECK remote_attempts_refusal_reason_check nao existe com esse nome';
    END IF;
    IF EXISTS (SELECT 1 FROM public.organization_license_enforcement WHERE model = 2) THEN
        RAISE EXCEPTION '064: ha organizacao no modelo por licenca antes de a conferencia existir';
    END IF;
END;
$$;

-- === 2. O canal das maquinas le as licencas da organizacao do contexto ===
DO $$
DECLARE
    ctx_req CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.organization_id'', true), '''')::uuid';
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['organization_licenses', 'organization_license_periods', 'organization_license_assignments',
                             'organization_license_enforcement'] LOOP
        EXECUTE format('CREATE POLICY tenant_ingestion_context ON public.%I AS PERMISSIVE FOR SELECT TO nekkora_ingestion USING (%s)', t, ctx_req);
        EXECUTE format('GRANT SELECT ON public.%I TO nekkora_ingestion', t);
    END LOOP;
END;
$$;

-- === 3. Leituras ===
CREATE FUNCTION public.license_model(p_org UUID) RETURNS SMALLINT
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT coalesce((SELECT e.model FROM public.organization_license_enforcement e WHERE e.organization_id = p_org), 1::smallint);
$$;
COMMENT ON FUNCTION public.license_model(uuid) IS 'Modelo da organizacao (064): 1 = anterior (sem linha tambem), 2 = por licenca.';

-- Havia, no instante p_at, um vinculo aberto da automacao com uma licenca nao encerrada e com periodo cobrindo o instante.
CREATE FUNCTION public.license_covered_at(p_org UUID, p_automation UUID, p_at TIMESTAMPTZ) RETURNS BOOLEAN
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.organization_license_assignments g
          JOIN public.organization_licenses l ON l.organization_id = g.organization_id AND l.license_id = g.license_id
         WHERE g.organization_id = p_org AND g.automation_id = p_automation
           AND g.assigned_at <= p_at AND (g.released_at IS NULL OR g.released_at > p_at)
           AND (l.ended_at IS NULL OR l.ended_at > p_at)
           AND EXISTS (SELECT 1 FROM public.organization_license_periods p
                        WHERE p.organization_id = l.organization_id AND p.license_id = l.license_id AND p.starts_at <= p_at AND p.ends_at > p_at));
$$;
COMMENT ON FUNCTION public.license_covered_at(uuid, uuid, timestamptz) IS
    'A automacao tinha licenca vigente vinculada no instante dado (064). Olha o vinculo, o encerramento e o periodo NAQUELE instante.';

-- Ate quando a cobertura de AGORA segue sem lacuna: o fim do periodo que cobre p_at, estendido pelos periodos seguintes que
-- comecam exatamente onde o anterior termina. Um periodo que comeca depois de um intervalo NAO entra (D3).
CREATE FUNCTION public.license_coverage_end(p_org UUID, p_automation UUID, p_at TIMESTAMPTZ) RETURNS TIMESTAMPTZ
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    WITH RECURSIVE chain AS (
        SELECT p.license_id, p.period_number, p.ends_at
          FROM public.organization_license_assignments g
          JOIN public.organization_licenses l ON l.organization_id = g.organization_id AND l.license_id = g.license_id AND l.ended_at IS NULL
          JOIN public.organization_license_periods p ON p.organization_id = l.organization_id AND p.license_id = l.license_id
         WHERE g.organization_id = p_org AND g.automation_id = p_automation AND g.released_at IS NULL
           AND p.starts_at <= p_at AND p.ends_at > p_at
        UNION ALL
        SELECT n.license_id, n.period_number, n.ends_at
          FROM chain c
          JOIN public.organization_license_periods n ON n.organization_id = p_org AND n.license_id = c.license_id
                                                    AND n.period_number = c.period_number + 1 AND n.starts_at <= c.ends_at
    )
    SELECT max(ends_at) FROM chain;
$$;
COMMENT ON FUNCTION public.license_coverage_end(uuid, uuid, timestamptz) IS
    'Fim da cobertura continua da automacao a partir do instante dado (064, D3): nunca atravessa uma lacuna entre periodos. Nulo: sem cobertura.';

CREATE FUNCTION public.license_state(p_org UUID, p_automation UUID) RETURNS TEXT
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT CASE
        WHEN public.license_model(p_org) <> 2 THEN 'LEGACY'
        WHEN public.license_covered_at(p_org, p_automation, clock_timestamp()) THEN 'OK'
        -- ha licenca vinculada, mas fora de periodo: vencida, a espera de renovacao
        WHEN EXISTS (SELECT 1 FROM public.organization_license_assignments g
                      WHERE g.organization_id = p_org AND g.automation_id = p_automation AND g.released_at IS NULL) THEN 'LICENSE_EXPIRED'
        ELSE 'LICENSE_REQUIRED' END;
$$;
COMMENT ON FUNCTION public.license_state(uuid, uuid) IS
    'O direito da automacao a trabalho novo (064): LEGACY (a organizacao nao exige licenca), OK, LICENSE_EXPIRED ou LICENSE_REQUIRED.';

-- O mesmo, para ADMITIR trabalho: sob a trava compartilhada do modelo da organizacao. Ligar a exigencia (063) pega a mesma
-- trava em modo exclusivo, entao um inicio de trabalho nunca corre contra a previa que o operador esta confirmando.
CREATE FUNCTION public.license_gate(p_org UUID, p_automation UUID) RETURNS TEXT
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM pg_catalog.pg_advisory_xact_lock_shared(pg_catalog.hashtextextended('organization_license_model:' || p_org::text, 0));
    RETURN public.license_state(p_org, p_automation);
END;
$$;
COMMENT ON FUNCTION public.license_gate(uuid, uuid) IS
    'license_state() sob a trava compartilhada do modelo da organizacao (064): usada por quem admite trabalho novo.';

-- A base de um registro do Agent anterior. Nulo: a organizacao nao exige licenca. 1, 2, 3: aceito (ver cabecalho). Negativo:
-- recusado (-1 a autorizacao nao valia no inicio; -2 sem licenca; -3 licenca vencida).
CREATE FUNCTION public.license_run_basis(p_org UUID, p_automation UUID, p_authorization UUID, p_start TIMESTAMPTZ) RETURNS SMALLINT
LANGUAGE plpgsql STABLE SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_now CONSTANT TIMESTAMPTZ := clock_timestamp();
    v_since TIMESTAMPTZ;
    v_granted TIMESTAMPTZ;
    v_until TIMESTAMPTZ;
    v_revoked TIMESTAMPTZ;
    v_start TIMESTAMPTZ;
BEGIN
    SELECT e.since INTO v_since FROM public.organization_license_enforcement e WHERE e.organization_id = p_org AND e.model = 2;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    -- a autorizacao e a do SERVIDOR: lida pela chave, da mesma organizacao e da mesma automacao
    SELECT z.granted_at, z.valid_until, z.revoked_at INTO v_granted, v_until, v_revoked FROM public.local_run_authorizations z
     WHERE z.organization_id = p_org AND z.authorization_id = p_authorization AND z.automation_id = p_automation;
    IF NOT FOUND THEN
        RETURN -1;
    END IF;
    -- o inicio informado pela maquina, limitado ao que o servidor sabe: nao antes da emissao da autorizacao, nao depois de agora
    v_start := least(greatest(coalesce(p_start, v_now), v_granted), v_now);
    IF v_start >= v_until OR (v_revoked IS NOT NULL AND v_revoked <= v_start) THEN
        RETURN -1;
    END IF;
    IF public.license_covered_at(p_org, p_automation, v_start) THEN
        RETURN CASE WHEN public.license_covered_at(p_org, p_automation, v_now) THEN 1 ELSE 2 END;
    END IF;
    -- TRANSICAO: autorizacao emitida antes de a organizacao passar ao modelo por licenca; vale so ate a validade ja emitida
    IF v_granted < v_since THEN
        RETURN 3;
    END IF;
    RETURN CASE WHEN EXISTS (SELECT 1 FROM public.organization_license_assignments g
                              WHERE g.organization_id = p_org AND g.automation_id = p_automation AND g.released_at IS NULL) THEN -3 ELSE -2 END;
END;
$$;
COMMENT ON FUNCTION public.license_run_basis(uuid, uuid, uuid, timestamptz) IS
    'Base de um registro do Agent anterior no modelo por licenca (064, D8): 1 licenca vigente; 2 vigente no inicio, dados depois do '
    'vencimento; 3 transicao (autorizacao anterior ao modelo, dentro da validade emitida); negativo = recusado; nulo = modelo anterior.';

-- === 4. A revalidacao da tentativa remota passa a conferir a licenca; no modelo por licenca o prazo da autorizacao nao recusa (S1) ===
ALTER TABLE public.remote_attempts DROP CONSTRAINT remote_attempts_refusal_reason_check;
ALTER TABLE public.remote_attempts ADD CONSTRAINT remote_attempts_refusal_reason_check CHECK (refusal_reason IN (
    'BUSY', 'PAUSED', 'PACKAGE_INVALID', 'ENVIRONMENT_GONE', 'ENVIRONMENT_INCOMPATIBLE', 'DEPENDENCIES_MISSING', 'DISK_PRESSURE',
    'CLIENT_INCOMPATIBLE', 'SESSION_UNAVAILABLE', 'AUTHORIZATION_MISSING', 'AUTHORIZATION_REVOKED', 'AUTHORIZATION_EXPIRED',
    'WORKER_NOT_AUTHORIZED', 'MACHINE_NOT_LINKED', 'MACHINE_NOT_ENABLED', 'MACHINE_INCOMPATIBLE', 'COMMUNICATION_LOST', 'LICENSE_REQUIRED',
    'LICENSE_EXPIRED'));

CREATE OR REPLACE FUNCTION public.remote_attempt_blocker(p_org UUID, p_execution UUID, p_agent UUID, p_worker UUID, p_authorization UUID) RETURNS TEXT
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT CASE
        WHEN x.remote_execution_id IS NULL THEN 'EXECUTION_NOT_FOUND'
        -- (064) o direito ao servico: no modelo por licenca, a automacao precisa de licenca vigente NESTE instante
        WHEN public.license_state(p_org, x.automation_id) IN ('LICENSE_REQUIRED', 'LICENSE_EXPIRED') THEN public.license_state(p_org, x.automation_id)
        -- (d) autorizacao da MESMA versao e do MESMO pacote, para ESTE Worker, de execucao (nunca de monitoramento)
        WHEN la.authorization_id IS NULL OR la.worker_id <> p_worker OR la.source_kind NOT IN (1, 2)
             OR la.automation_version_id IS DISTINCT FROM x.automation_version_id OR la.workspace_id <> x.workspace_id
             OR la.package_fingerprint IS DISTINCT FROM x.package_fingerprint
             OR la.package_fingerprint_rule IS DISTINCT FROM x.package_fingerprint_rule THEN 'AUTHORIZATION_MISSING'
        WHEN la.revoked_at IS NOT NULL THEN 'AUTHORIZATION_REVOKED'
        -- (064, S1) o prazo da autorizacao so recusa no modelo anterior; no modelo por licenca o prazo e o da licenca
        WHEN la.valid_until <= clock_timestamp() AND public.license_model(p_org) <> 2 THEN 'AUTHORIZATION_EXPIRED'
        -- (b) Worker ativo e ainda autorizado no ambiente
        WHEN NOT EXISTS (SELECT 1 FROM public.worker_workspace_assignments ww
                           JOIN public.workers wk ON wk.organization_id = ww.organization_id AND wk.worker_id = ww.worker_id AND wk.status = 1
                          WHERE ww.organization_id = p_org AND ww.assignment_id = la.worker_workspace_assignment_id AND ww.status = 1) THEN 'WORKER_NOT_AUTHORIZED'
        -- (c) maquina ativa, associada a este Worker, habilitada
        WHEN NOT EXISTS (SELECT 1 FROM public.agents a
                           JOIN public.worker_agent_assignments wa ON wa.organization_id = a.organization_id AND wa.agent_id = a.agent_id
                                                                   AND wa.worker_id = p_worker AND wa.status = 1
                          WHERE a.organization_id = p_org AND a.agent_id = p_agent AND a.status = 2) THEN 'MACHINE_NOT_LINKED'
        WHEN en.agent_id IS NULL THEN 'MACHINE_NOT_ENABLED'
        WHEN NOT coalesce(pr.capabilities @> ARRAY['REMOTE_RUN_1_0']::text[], false) THEN 'MACHINE_INCOMPATIBLE'
        ELSE NULL END
      FROM (SELECT 1) one
      LEFT JOIN public.remote_executions x ON x.organization_id = p_org AND x.remote_execution_id = p_execution
      LEFT JOIN public.local_run_authorizations la ON la.organization_id = p_org AND la.authorization_id = p_authorization
      LEFT JOIN public.agent_remote_enablements en ON en.organization_id = p_org AND en.agent_id = p_agent
      LEFT JOIN public.agent_presence pr ON pr.agent_id = p_agent;
$$;
COMMENT ON FUNCTION public.remote_attempt_blocker(uuid, uuid, uuid, uuid, uuid) IS
    'Revalidacao das condicoes de uma tentativa (046; 064 acrescenta a licenca e, no modelo por licenca, deixa de recusar pelo prazo da '
    'autorizacao): nulo = pode; senao o motivo. Usada na criacao, na oferta e antes de iniciar. Nao olha a pausa nem o cancelamento.';

-- === 5. Trabalho novo: solicitacao remota e pedido ao Agent anterior ===
CREATE FUNCTION public.license_work_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_state TEXT;
BEGIN
    v_state := public.license_gate(NEW.organization_id, NEW.automation_id);
    IF v_state NOT IN ('LEGACY', 'OK') THEN
        RAISE EXCEPTION 'trabalho recusado: %', v_state USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_executions_license BEFORE INSERT ON public.remote_executions
    FOR EACH ROW EXECUTE FUNCTION public.license_work_on_insert();
CREATE TRIGGER trg_run_requests_license BEFORE INSERT ON public.run_requests
    FOR EACH ROW EXECUTE FUNCTION public.license_work_on_insert();

-- === 6. Autorizacao nova: so com licenca vigente, e nunca alem do fim da cobertura sem lacuna (D3) ===
CREATE FUNCTION public.license_authorization_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_state TEXT;
    v_end TIMESTAMPTZ;
BEGIN
    v_state := public.license_gate(NEW.organization_id, NEW.automation_id);
    IF v_state = 'LEGACY' THEN
        RETURN NEW;
    END IF;
    IF v_state <> 'OK' THEN
        RAISE EXCEPTION 'autorizacao recusada: %', v_state USING ERRCODE = '23514';
    END IF;
    v_end := public.license_coverage_end(NEW.organization_id, NEW.automation_id, clock_timestamp());
    IF v_end IS NULL OR NEW.valid_until > v_end THEN
        RAISE EXCEPTION 'autorizacao recusada: AUTHORIZATION_EXCEEDS_LICENSE' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_local_run_authorizations_license BEFORE INSERT ON public.local_run_authorizations
    FOR EACH ROW EXECUTE FUNCTION public.license_authorization_on_insert();

-- === 7. Registro do Agent anterior (D8): a base e calculada e gravada pelo banco ===
ALTER TABLE public.agent_runs ADD COLUMN license_basis SMALLINT CHECK (license_basis IN (1, 2, 3));
COMMENT ON COLUMN public.agent_runs.license_basis IS
    'Base de licenca do registro (064, D8), calculada pelo banco: nulo = organizacao no modelo anterior; 1 = licenca vigente; 2 = licenca '
    'vigente no inicio, dados entregues depois do vencimento; 3 = transicao (autorizacao emitida antes do modelo por licenca).';

CREATE FUNCTION public.license_agent_run_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_basis SMALLINT;
BEGIN
    PERFORM pg_catalog.pg_advisory_xact_lock_shared(pg_catalog.hashtextextended('organization_license_model:' || NEW.organization_id::text, 0));
    v_basis := public.license_run_basis(NEW.organization_id, NEW.automation_id, NEW.authorization_id,
                                        coalesce(NEW.agent_started_at, NEW.agent_created_at));
    IF v_basis < 0 THEN
        RAISE EXCEPTION 'registro recusado: %', CASE v_basis WHEN -1 THEN 'RUN_NOT_COVERED' WHEN -3 THEN 'LICENSE_EXPIRED' ELSE 'LICENSE_REQUIRED' END
            USING ERRCODE = '23514';
    END IF;
    NEW.license_basis := v_basis;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_agent_runs_license BEFORE INSERT ON public.agent_runs
    FOR EACH ROW EXECUTE FUNCTION public.license_agent_run_on_insert();

-- === 8. Privilegios ===
REVOKE ALL ON FUNCTION public.license_model(uuid), public.license_covered_at(uuid, uuid, timestamptz),
    public.license_coverage_end(uuid, uuid, timestamptz), public.license_state(uuid, uuid), public.license_gate(uuid, uuid),
    public.license_run_basis(uuid, uuid, uuid, timestamptz), public.license_work_on_insert(), public.license_authorization_on_insert(),
    public.license_agent_run_on_insert() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.license_model(uuid), public.license_covered_at(uuid, uuid, timestamptz),
    public.license_coverage_end(uuid, uuid, timestamptz), public.license_state(uuid, uuid), public.license_gate(uuid, uuid),
    public.license_run_basis(uuid, uuid, uuid, timestamptz), public.license_work_on_insert(), public.license_authorization_on_insert(),
    public.license_agent_run_on_insert() TO nekkora_platform, nekkora_ingestion;

-- === 9. Invariantes ===
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['organization_licenses', 'organization_license_periods', 'organization_license_assignments',
                             'organization_license_acts', 'organization_license_enforcement'] LOOP
        IF has_table_privilege('nekkora_ingestion', 'public.' || t, 'INSERT') OR has_table_privilege('nekkora_ingestion', 'public.' || t, 'UPDATE')
           OR has_table_privilege('nekkora_ingestion', 'public.' || t, 'DELETE') THEN
            RAISE EXCEPTION '064: o canal das maquinas nao escreve em %', t;
        END IF;
    END LOOP;
    IF has_table_privilege('nekkora_ingestion', 'public.organization_license_acts', 'SELECT') THEN
        RAISE EXCEPTION '064: o canal das maquinas nao le a trilha de atos';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_proc p WHERE p.pronamespace = 'public'::regnamespace
                  AND p.proname IN ('license_model', 'license_covered_at', 'license_coverage_end', 'license_state', 'license_gate', 'license_run_basis',
                                    'license_work_on_insert', 'license_authorization_on_insert', 'license_agent_run_on_insert', 'remote_attempt_blocker')
                  AND p.prosecdef) THEN
        RAISE EXCEPTION '064: funcao SECURITY DEFINER inesperada';
    END IF;
    IF EXISTS (SELECT 1 FROM public.agent_runs WHERE license_basis IS NOT NULL) THEN
        RAISE EXCEPTION '064: nenhuma execucao existente ganha base de licenca';
    END IF;
    -- a lista de motivos de recusa so CRESCE: todos os da 053 continuam, mais os dois da licenca
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c WHERE c.conrelid = 'public.remote_attempts'::regclass
                AND c.conname = 'remote_attempts_refusal_reason_check') NOT LIKE ALL (ARRAY['%COMMUNICATION_LOST%', '%SESSION_UNAVAILABLE%',
                '%MACHINE_INCOMPATIBLE%', '%LICENSE_REQUIRED%', '%LICENSE_EXPIRED%']) THEN
        RAISE EXCEPTION '064: a lista de motivos de recusa perdeu algum motivo';
    END IF;
    IF (SELECT count(*) FROM pg_trigger g WHERE NOT g.tgisinternal AND g.tgname IN ('trg_remote_executions_license', 'trg_run_requests_license',
                                                'trg_local_run_authorizations_license', 'trg_agent_runs_license')) <> 4 THEN
        RAISE EXCEPTION '064: gatilhos de conferencia fora do previsto';
    END IF;
END;
$$;

COMMIT;
