-- Nekkora Platform - o BLOQUEIO DA NEKKORA passa a ter efeito real nos fluxos (NEKKORA-LICENCIAMENTO-002, decisao da titular de
-- 22/11/2026, item 4, e autorizacao da leitura minima em 23/11). Documento: docs/licenciamento-002/074-075-organizacao-interna-e-bloqueio.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 073. Numero 075 conferido em 23/11 contra a branch da 073/074.
--
-- *** SEM ELEVACAO. Roda como o migrador comum: so tabelas, funcoes e gatilhos do PROPRIO migrador (025, 042, 046, 064, 067,
-- *** 073). A pre-condicao recusa rodar elevada e confere o dono de cada objeto tocado.
--
-- O QUE FAZ
--   1. LEITURA MINIMA (decisao de 23/11): nekkora_platform e nekkora_ingestion leem, de organization_nekkora_holds, SO as
--      colunas organization_id e lifted_at, e SO da organizacao em contexto (nekkora.organization_id). Nada de motivo, quem
--      bloqueou, quem levantou, motivo do levantamento, chamados ou cadastros de operadores; nenhuma escrita. Portal e
--      provisionamento: nada.
--   2. organization_held(org): a organizacao esta bloqueada = existe ao menos um bloqueio com lifted_at nulo (com historico,
--      vale o bloqueio ainda nao levantado).
--   3. TRABALHO NOVO recusado (gatilhos BEFORE; o banco reconfere, nao confia na aplicacao), motivo ORGANIZATION_HELD:
--        - solicitacao de execucao remota nova (remote_executions) e pedido novo ao Agent anterior (run_requests);
--        - autorizacao nova de execucao ou de monitoramento (local_run_authorizations), Agent anterior e remoto;
--        - codigo de pareamento novo (agent_enrollments INSERT) e RESGATE de codigo emitido antes do bloqueio (UPDATE que
--          consome); habilitacao nova de execucao remota (agent_remote_enablements);
--        - tentativa remota: remote_attempt_blocker responde ORGANIZATION_HELD na criacao, na oferta e antes de iniciar - a
--          tentativa ainda nao iniciada nao e lancada (vai a recusada com esse motivo).
--   4. O QUE JA COMECOU continua (nenhum gatilho novo nessas tabelas): tentativa ja iniciada termina, envia registros e
--      resultados e fecha a entrega; o Agent anterior registra execucoes e sessoes de monitoramento feitas com autorizacao
--      valida (agent_runs: entrega tardia); cancelar, revogar e reconciliar continuam. Os dados ficam preservados.
--   5. A organizacao interna designada (073) nao pode ser bloqueada por aqui (o bloqueio dela tiraria o operador do ar);
--      retire a designacao antes.
--
-- O QUE NAO FAZ: nao muda organizations.status (o bloqueio e separado da desativacao voluntaria, 067); nao revoga sessao,
-- credencial de maquina nem autorizacao ja emitida; nao da ao administrador da organizacao nenhuma forma de levantar o
-- bloqueio (so o migrador, pelo procedimento, 073).
--
-- LIMITE (Agent anterior, documentado): uma autorizacao local ja entregue vale SEM CONEXAO ate a validade; o servidor nao
-- impede na hora uma execucao offline ja autorizada, e o registro dela, quando chega, e aceito como trabalho autorizado antes
-- do bloqueio. Na proxima conexao a maquina nao recebe autorizacao nova.
--
-- REVERSAO (manual, sem elevacao):
--   BEGIN;
--   DROP TRIGGER trg_075_remote_executions_hold ON public.remote_executions; DROP TRIGGER trg_075_run_requests_hold ON public.run_requests;
--   DROP TRIGGER trg_075_local_run_authorizations_hold ON public.local_run_authorizations;
--   DROP TRIGGER trg_075_agent_enrollments_hold ON public.agent_enrollments; DROP TRIGGER trg_075_agent_enrollments_redeem_hold ON public.agent_enrollments;
--   DROP TRIGGER trg_075_agent_remote_enablements_hold ON public.agent_remote_enablements;
--   DROP TRIGGER trg_075_nekkora_hold_insert ON public.organization_nekkora_holds;
--   -- recriar remote_attempt_blocker() com o corpo da 064 e a CHECK de refusal_reason com a lista da 064 (so sem linha ORGANIZATION_HELD)
--   DROP FUNCTION public.organization_hold_on_new_work(), public.organization_hold_on_redeem(), public.nekkora_hold_on_insert(),
--                 public.organization_held(uuid);
--   DROP POLICY tenant_hold_context ON public.organization_nekkora_holds;
--   REVOKE SELECT (organization_id, lifted_at) ON public.organization_nekkora_holds FROM nekkora_platform, nekkora_ingestion;
--   COMMIT;

BEGIN;

-- === 1. Pre-condicao ===
DO $$
DECLARE
    t TEXT;
BEGIN
    IF to_regclass('public.nekkora_internal_organization_designations') IS NULL
       OR NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'organization_nekkora_holds'
                       AND column_name = 'lift_reason') THEN
        RAISE EXCEPTION '075 exige a 073 aplicada';
    END IF;
    IF to_regprocedure('public.organization_held(uuid)') IS NOT NULL THEN
        RAISE EXCEPTION '075: organization_held ja existe';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND (rolsuper OR rolbypassrls OR rolcreaterole)) THEN
        RAISE EXCEPTION '075 roda SEM elevacao: o papel % esta com SUPERUSER, BYPASSRLS ou CREATEROLE', current_user;
    END IF;
    FOREACH t IN ARRAY ARRAY['organization_nekkora_holds', 'remote_executions', 'run_requests', 'local_run_authorizations', 'agent_enrollments',
                             'agent_remote_enablements', 'remote_attempts', 'nekkora_internal_organization_designations'] LOOP
        IF (SELECT pg_get_userbyid(c.relowner) FROM pg_class c WHERE c.oid = ('public.' || t)::regclass) <> current_user THEN
            RAISE EXCEPTION '075: a tabela % nao e do papel % (exigiria elevacao)', t, current_user;
        END IF;
    END LOOP;
    IF (SELECT pg_get_userbyid(p.proowner) FROM pg_proc p WHERE p.oid = 'public.remote_attempt_blocker(uuid, uuid, uuid, uuid, uuid)'::regprocedure)
       <> current_user THEN
        RAISE EXCEPTION '075: remote_attempt_blocker nao e do papel % (exigiria elevacao)', current_user;
    END IF;
END;
$$;

-- === 2. Leitura minima: duas colunas, so da organizacao em contexto ===
GRANT SELECT (organization_id, lifted_at) ON public.organization_nekkora_holds TO nekkora_platform, nekkora_ingestion;
CREATE POLICY tenant_hold_context ON public.organization_nekkora_holds AS PERMISSIVE FOR SELECT TO nekkora_platform, nekkora_ingestion
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid);

CREATE FUNCTION public.organization_held(p_org UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM public.organization_nekkora_holds h WHERE h.organization_id = p_org AND h.lifted_at IS NULL);
$$;
COMMENT ON FUNCTION public.organization_held(uuid) IS
    'A organizacao esta bloqueada pela Nekkora (075): ha bloqueio nao levantado. SECURITY INVOKER: os servicos so enxergam a '
    'organizacao do contexto (politica tenant_hold_context) e so as colunas organization_id e lifted_at.';

-- === 3. Trabalho novo recusado ===
CREATE FUNCTION public.organization_hold_on_new_work() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF public.organization_held(NEW.organization_id) THEN
        RAISE EXCEPTION 'organizacao bloqueada: ORGANIZATION_HELD' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_075_remote_executions_hold BEFORE INSERT ON public.remote_executions
    FOR EACH ROW EXECUTE FUNCTION public.organization_hold_on_new_work();
CREATE TRIGGER trg_075_run_requests_hold BEFORE INSERT ON public.run_requests
    FOR EACH ROW EXECUTE FUNCTION public.organization_hold_on_new_work();
CREATE TRIGGER trg_075_local_run_authorizations_hold BEFORE INSERT ON public.local_run_authorizations
    FOR EACH ROW EXECUTE FUNCTION public.organization_hold_on_new_work();
CREATE TRIGGER trg_075_agent_enrollments_hold BEFORE INSERT ON public.agent_enrollments
    FOR EACH ROW EXECUTE FUNCTION public.organization_hold_on_new_work();
CREATE TRIGGER trg_075_agent_remote_enablements_hold BEFORE INSERT ON public.agent_remote_enablements
    FOR EACH ROW EXECUTE FUNCTION public.organization_hold_on_new_work();

-- resgate de um codigo emitido antes do bloqueio: o consumo e recusado (o cancelamento continua)
CREATE FUNCTION public.organization_hold_on_redeem() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.consumed_at IS NULL AND NEW.consumed_at IS NOT NULL AND public.organization_held(NEW.organization_id) THEN
        RAISE EXCEPTION 'organizacao bloqueada: ORGANIZATION_HELD' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_075_agent_enrollments_redeem_hold BEFORE UPDATE ON public.agent_enrollments
    FOR EACH ROW EXECUTE FUNCTION public.organization_hold_on_redeem();

-- === 4. Tentativa remota: a revalidacao (criacao, oferta, antes de iniciar) recusa com ORGANIZATION_HELD ===
ALTER TABLE public.remote_attempts DROP CONSTRAINT remote_attempts_refusal_reason_check;
ALTER TABLE public.remote_attempts ADD CONSTRAINT remote_attempts_refusal_reason_check CHECK (refusal_reason IN (
    'BUSY', 'PAUSED', 'PACKAGE_INVALID', 'ENVIRONMENT_GONE', 'ENVIRONMENT_INCOMPATIBLE', 'DEPENDENCIES_MISSING', 'DISK_PRESSURE',
    'CLIENT_INCOMPATIBLE', 'SESSION_UNAVAILABLE', 'AUTHORIZATION_MISSING', 'AUTHORIZATION_REVOKED', 'AUTHORIZATION_EXPIRED',
    'WORKER_NOT_AUTHORIZED', 'MACHINE_NOT_LINKED', 'MACHINE_NOT_ENABLED', 'MACHINE_INCOMPATIBLE', 'COMMUNICATION_LOST', 'LICENSE_REQUIRED',
    'LICENSE_EXPIRED', 'ORGANIZATION_HELD'));

-- corpo da 064 (a definicao vigente), com o bloqueio como PRIMEIRA condicao depois da existencia
CREATE OR REPLACE FUNCTION public.remote_attempt_blocker(p_org UUID, p_execution UUID, p_agent UUID, p_worker UUID, p_authorization UUID) RETURNS TEXT
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT CASE
        WHEN x.remote_execution_id IS NULL THEN 'EXECUTION_NOT_FOUND'
        -- (075) organizacao bloqueada pela Nekkora: nada novo e lancado
        WHEN public.organization_held(p_org) THEN 'ORGANIZATION_HELD'
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
    'Revalidacao das condicoes de uma tentativa (046; 064 acrescenta a licenca; 075 o bloqueio da Nekkora): nulo = pode; senao o '
    'motivo. Usada na criacao, na oferta e antes de iniciar. Nao olha a pausa nem o cancelamento.';

-- === 5. A organizacao interna designada nao e bloqueada por aqui ===
CREATE FUNCTION public.nekkora_hold_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.nekkora_internal_organization_designations d
                WHERE d.organization_id = NEW.organization_id AND d.withdrawn_at IS NULL) THEN
        RAISE EXCEPTION 'bloqueio recusado: a organizacao e a interna designada da Nekkora (retire a designacao antes)' USING ERRCODE = '23514';
    END IF;
    IF NEW.lifted_at IS NOT NULL OR NEW.lifted_by IS NOT NULL OR NEW.lift_reason IS NOT NULL THEN
        RAISE EXCEPTION 'bloqueio recusado: nasce aberto' USING ERRCODE = '23514';
    END IF;
    NEW.registered_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_075_nekkora_hold_insert BEFORE INSERT ON public.organization_nekkora_holds
    FOR EACH ROW EXECUTE FUNCTION public.nekkora_hold_on_insert();

-- === 6. Privilegios ===
REVOKE ALL ON FUNCTION public.organization_held(uuid), public.organization_hold_on_new_work(), public.organization_hold_on_redeem(),
    public.nekkora_hold_on_insert() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.organization_held(uuid), public.organization_hold_on_new_work(), public.organization_hold_on_redeem()
    TO nekkora_platform, nekkora_ingestion;

-- === 7. Invariantes ===
DO $$
DECLARE
    r TEXT;
    c TEXT;
BEGIN
    -- leitura: so as duas colunas, so platform e ingestion; nenhuma escrita
    FOREACH r IN ARRAY ARRAY['nekkora_platform', 'nekkora_ingestion'] LOOP
        IF has_table_privilege(r, 'public.organization_nekkora_holds', 'SELECT, INSERT, UPDATE, DELETE')
           OR NOT has_column_privilege(r, 'public.organization_nekkora_holds', 'organization_id', 'SELECT')
           OR NOT has_column_privilege(r, 'public.organization_nekkora_holds', 'lifted_at', 'SELECT') THEN
            RAISE EXCEPTION '075: leitura do bloqueio por % fora do previsto', r;
        END IF;
        FOREACH c IN ARRAY ARRAY['hold_id', 'reason', 'registered_by', 'registered_at', 'lifted_by', 'lift_reason'] LOOP
            IF has_column_privilege(r, 'public.organization_nekkora_holds', c, 'SELECT') THEN
                RAISE EXCEPTION '075: % le a coluna % do bloqueio', r, c;
            END IF;
        END LOOP;
        IF has_any_column_privilege(r, 'public.organization_nekkora_holds', 'INSERT, UPDATE')
           OR has_any_column_privilege(r, 'public.nekkora_internal_organization_designations', 'SELECT, INSERT, UPDATE')
           OR has_any_column_privilege(r, 'public.license_operators', 'SELECT, INSERT, UPDATE') THEN
            RAISE EXCEPTION '075: % ganhou escrita no bloqueio ou acesso a designacao ou aos operadores', r;
        END IF;
    END LOOP;
    FOREACH r IN ARRAY ARRAY['nekkora_portal', 'nekkora_provisioning'] LOOP
        CONTINUE WHEN to_regrole(r) IS NULL;
        IF has_any_column_privilege(r, 'public.organization_nekkora_holds', 'SELECT, INSERT, UPDATE') THEN
            RAISE EXCEPTION '075: o papel % alcanca o bloqueio', r;
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_proc p WHERE p.pronamespace = 'public'::regnamespace AND p.prosecdef
                  AND p.proname IN ('organization_held', 'organization_hold_on_new_work', 'organization_hold_on_redeem', 'nekkora_hold_on_insert',
                                    'remote_attempt_blocker')) THEN
        RAISE EXCEPTION '075: funcao SECURITY DEFINER inesperada';
    END IF;
    IF NOT ((SELECT pg_get_constraintdef(c2.oid) FROM pg_constraint c2 WHERE c2.conrelid = 'public.remote_attempts'::regclass
                AND c2.conname = 'remote_attempts_refusal_reason_check') LIKE ALL (ARRAY['%COMMUNICATION_LOST%', '%SESSION_UNAVAILABLE%',
                '%MACHINE_INCOMPATIBLE%', '%LICENSE_REQUIRED%', '%LICENSE_EXPIRED%', '%ORGANIZATION_HELD%'])) THEN
        RAISE EXCEPTION '075: a lista de motivos de recusa perdeu algum motivo';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb)) THEN
        RAISE EXCEPTION '075: o migrador terminou com atributo elevado';
    END IF;
END;
$$;

COMMIT;
