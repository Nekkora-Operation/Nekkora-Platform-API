-- Nekkora Platform - "Desvincular esta maquina" tambem na Platform (PLATFORM-AGENT-V2-004, T13). Protocolo, estados e
-- resposta perdida: docs/platform-agent-v2-004/t13-desvinculo-remoto.md. Contrato: api/openapi/nekkora-agent-v1.yaml
-- (POST /agent/api/v1/link/unlink).
--
-- CANDIDATA ate a aprovacao do PR. Numero 035 conferido em 12/11 contra a dev (2c1c050; a maior era 034). As ferramentas
-- do laboratorio e da homologacao exigem numeracao contigua: se outra migration ocupar 035 antes, esta e renumerada sem
-- mudar o conteudo.
--
-- PRECEDENCIA: a decisao de 12/11 (Nicolly) substitui a limitacao do T06/T11 ("a maquina nao tem rota para se
-- desvincular"). A maquina passa a poder encerrar SO O PROPRIO VINCULO: o Agent da credencial autenticada, a associacao
-- ativa dele e as credenciais dele, na organizacao da credencial. Nenhum outro poder administrativo.
--
-- AUTORIA SEM PESSOA. Ate aqui, toda revogacao de credencial e todo encerramento de associacao exigiam uma pertinencia
-- (CHECK da 004). O desvinculo pela maquina nao tem pessoa e NAO finge uma: o encerramento passa a ter exatamente uma
-- autoria, pessoa (revoked_by_membership_id / ended_by_membership_id) OU a propria maquina (revoked_by_agent_id /
-- ended_by_agent_id, sempre igual ao agent_id da linha). A credencial ganha o motivo 5 (DESVINCULO PELA MAQUINA), que so
-- existe com a maquina como autora. A auditoria ganha o ato 14, com actor_agent_id e sem actor_membership_id.
--
-- SEM ELEVACAO. Nenhuma funcao SECURITY DEFINER: os gatilhos sao SECURITY INVOKER e rodam com o papel de quem grava, como
-- os da 025/030/033. A ingestao (nekkora_ingestion) recebe tres privilegios de coluna novos, cada um RESTRITO POR GATILHO:
--   * agents.status: so 2 (ACTIVE) -> 4 (REVOKED);
--   * worker_agent_assignments (status, ended_at, ended_by_agent_id): so ativa -> encerrada, com a propria maquina autora;
--   * agent_credentials.revoked_by_agent_id: so com o motivo 5 e a propria maquina.
-- Os tres exigem, para QUALQUER papel, uma linha de agent_unlink_operations deste Agent INSERIDA NA MESMA TRANSACAO
-- (xmin da linha = transacao atual). A invariante da 025 ("a ingestao nao muda o status do Agent nem encerra associacao")
-- passa a ser: so pelo desvinculo da propria maquina, registrado na mesma transacao. Fora disso, o banco recusa.
--
-- UMA OPERACAO POR AGENT. agent_unlink_operations e unica por agent_id (o vinculo so termina uma vez) e por operation_id.
-- Um Agent novo (novo pareamento do mesmo Worker) tem outro agent_id: uma operacao antiga nunca o alcanca.
--
-- PEDIDOS DE EXECUCAO. run_requests.decision_reason ganha AGENT_UNLINKED (REFUSED pela maquina ao se desvincular). As
-- transicoes continuam as da 030 (1/2 -> 4 ou 7; 3 -> 5), feitas pela ingestao como ja eram.
--
-- NADA EXISTENTE MUDA DE SIGNIFICADO: as linhas atuais nao tem autoria de maquina (colunas novas nulas) e continuam
-- validas pelas regras novas (a precondicao confere). Revogacao administrativa, rotacao e pareamento seguem iguais.

BEGIN;

DO $$
BEGIN
    IF to_regclass('public.ix_local_run_authorizations_vigente') IS NULL OR to_regclass('public.organization_handles') IS NULL THEN
        RAISE EXCEPTION '035: exige 033 e 034';
    END IF;
    IF to_regclass('public.agent_unlink_operations') IS NOT NULL THEN
        RAISE EXCEPTION '035: ja aplicada';
    END IF;
END;
$$;

-- === 1. Operacao de desvinculo pela maquina ===
CREATE TABLE public.agent_unlink_operations (
    operation_id                UUID NOT NULL,
    organization_id             UUID NOT NULL,
    agent_id                    UUID NOT NULL,
    worker_id                   UUID NOT NULL,
    assignment_id               UUID NOT NULL,
    credential_id               UUID NOT NULL,
    unlinked_at                 TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    run_requests_closed         INTEGER NOT NULL CHECK (run_requests_closed BETWEEN 0 AND 10000),
    run_requests_kept_accepted  INTEGER NOT NULL CHECK (run_requests_kept_accepted BETWEEN 0 AND 50),
    PRIMARY KEY (organization_id, operation_id),
    UNIQUE (organization_id, agent_id),
    FOREIGN KEY (organization_id, agent_id) REFERENCES public.agents (organization_id, agent_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, worker_id) REFERENCES public.workers (organization_id, worker_id) ON DELETE RESTRICT
);
COMMENT ON TABLE public.agent_unlink_operations IS
    'desvinculo pedido pela propria maquina (035, T13): uma por Agent; autoriza, na mesma transacao, encerrar o vinculo dele';

CREATE FUNCTION public.agent_unlink_operation_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.unlinked_at := clock_timestamp();
    -- a operacao so nasce para o vinculo ATIVO que ela descreve (Agent ativo, associacao ativa dele com aquele Worker,
    -- credencial dele ainda nao revogada)
    IF NOT EXISTS (SELECT 1 FROM public.agents a
                     JOIN public.worker_agent_assignments wa ON wa.organization_id = a.organization_id AND wa.agent_id = a.agent_id
                     JOIN public.agent_credentials c ON c.organization_id = a.organization_id AND c.agent_id = a.agent_id
                    WHERE a.organization_id = NEW.organization_id AND a.agent_id = NEW.agent_id AND a.status = 2
                      AND wa.assignment_id = NEW.assignment_id AND wa.worker_id = NEW.worker_id AND wa.status = 1
                      AND c.credential_id = NEW.credential_id AND c.revoked_at IS NULL) THEN
        RAISE EXCEPTION 'desvinculo so para o vinculo ativo da propria credencial' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_agent_unlink_operations_on_insert BEFORE INSERT ON public.agent_unlink_operations
    FOR EACH ROW EXECUTE FUNCTION public.agent_unlink_operation_on_insert();
CREATE TRIGGER trg_agent_unlink_operations_prevent_update BEFORE UPDATE ON public.agent_unlink_operations
    FOR EACH ROW EXECUTE FUNCTION public.prevent_update();
CREATE TRIGGER trg_agent_unlink_operations_prevent_delete BEFORE DELETE ON public.agent_unlink_operations
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_agent_unlink_operations_prevent_truncate BEFORE TRUNCATE ON public.agent_unlink_operations
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- "inserida nesta transacao": xmin da linha = id da transacao atual (32 bits). O gateway nao usa subtransacoes.
CREATE FUNCTION public.agent_unlink_in_this_transaction(p_org UUID, p_agent UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM public.agent_unlink_operations o
                    WHERE o.organization_id = p_org AND o.agent_id = p_agent
                      AND (o.xmin::text)::bigint = (pg_current_xact_id()::text::bigint % 4294967296));
$$;

-- === 2. Autoria da maquina no encerramento ===
ALTER TABLE public.agent_credentials ADD COLUMN revoked_by_agent_id UUID;
ALTER TABLE public.worker_agent_assignments ADD COLUMN ended_by_agent_id UUID;

-- as CHECKs da 004 sem nome ("pessoa obrigatoria") sao trocadas pelas de autoria unica; achadas pela definicao
DO $$
DECLARE
    v_c RECORD;
    v_n INTEGER := 0;
BEGIN
    FOR v_c IN
        SELECT conrelid::regclass AS t, conname FROM pg_constraint
         WHERE contype = 'c'
           AND ((conrelid = 'public.agent_credentials'::regclass
                 AND (pg_get_constraintdef(oid) ~ 'revoked_at IS NULL\) = \(revoked_by_membership_id IS NULL'
                      OR pg_get_constraintdef(oid) ~ 'revocation_reason = ANY'))
             OR (conrelid = 'public.worker_agent_assignments'::regclass
                 AND pg_get_constraintdef(oid) ~ 'ended_at IS NULL\) = \(ended_by_membership_id IS NULL'))
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', v_c.t, v_c.conname);
        v_n := v_n + 1;
    END LOOP;
    IF v_n <> 3 THEN
        RAISE EXCEPTION '035: esperadas 3 CHECKs da 004 a substituir, achadas %', v_n;
    END IF;
END;
$$;

ALTER TABLE public.agent_credentials
    ADD CONSTRAINT agent_credentials_revocation_reason_check CHECK (revocation_reason IN (1, 2, 3, 4, 5)),
    ADD CONSTRAINT agent_credentials_revocation_author CHECK (
        CASE WHEN revoked_at IS NULL THEN revoked_by_membership_id IS NULL AND revoked_by_agent_id IS NULL
             ELSE num_nonnulls(revoked_by_membership_id, revoked_by_agent_id) = 1 END),
    ADD CONSTRAINT agent_credentials_revoked_by_self CHECK (revoked_by_agent_id IS NULL OR revoked_by_agent_id = agent_id),
    ADD CONSTRAINT agent_credentials_reason_machine CHECK ((revocation_reason = 5) = (revoked_by_agent_id IS NOT NULL));
COMMENT ON COLUMN public.agent_credentials.revocation_reason IS
    '1=ROTATION, 2=EXPIRED_REPLACEMENT, 3=COMPROMISED, 4=ADMINISTRATIVE, 5=MACHINE_UNLINK (a propria maquina, 035)';

ALTER TABLE public.worker_agent_assignments
    ADD CONSTRAINT worker_agent_assignments_end_author CHECK (
        CASE WHEN ended_at IS NULL THEN ended_by_membership_id IS NULL AND ended_by_agent_id IS NULL
             ELSE num_nonnulls(ended_by_membership_id, ended_by_agent_id) = 1 END),
    ADD CONSTRAINT worker_agent_assignments_ended_by_self CHECK (ended_by_agent_id IS NULL OR ended_by_agent_id = agent_id);

-- === 3. Gatilhos de guarda (SECURITY INVOKER) ===

-- Agent: a ingestao so muda o status de 2 para 4, e so com a operacao desta transacao. Para os demais papeis nada muda.
CREATE FUNCTION public.agent_status_unlink_guard() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status AND current_user = 'nekkora_ingestion' THEN
        IF NOT (OLD.status = 2 AND NEW.status = 4 AND public.agent_unlink_in_this_transaction(OLD.organization_id, OLD.agent_id)) THEN
            RAISE EXCEPTION 'a maquina so encerra o proprio vinculo, pelo desvinculo registrado' USING ERRCODE = '42501';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_agents_unlink_guard BEFORE UPDATE OF status ON public.agents
    FOR EACH ROW EXECUTE FUNCTION public.agent_status_unlink_guard();

-- Associacao: autoria da maquina so pelo desvinculo desta transacao; a ingestao so encerra assim.
CREATE FUNCTION public.worker_agent_assignment_unlink_guard() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.ended_by_agent_id IS NOT NULL AND OLD.ended_by_agent_id IS NULL
       AND NOT public.agent_unlink_in_this_transaction(OLD.organization_id, OLD.agent_id) THEN
        RAISE EXCEPTION 'encerramento pela maquina so pelo desvinculo registrado' USING ERRCODE = '42501';
    END IF;
    IF current_user = 'nekkora_ingestion' AND NEW.ended_by_agent_id IS NULL THEN
        RAISE EXCEPTION 'a maquina so encerra o proprio vinculo, pelo desvinculo registrado' USING ERRCODE = '42501';
    END IF;
    IF NEW.ended_by_agent_id IS NOT NULL THEN
        NEW.ended_at := clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_worker_agent_assignments_unlink_guard BEFORE UPDATE ON public.worker_agent_assignments
    FOR EACH ROW EXECUTE FUNCTION public.worker_agent_assignment_unlink_guard();

-- Credencial: motivo 5 / autoria da maquina so pelo desvinculo desta transacao.
CREATE FUNCTION public.agent_credential_unlink_guard() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF (NEW.revoked_by_agent_id IS NOT NULL OR NEW.revocation_reason = 5) AND OLD.revoked_at IS NULL
       AND NOT public.agent_unlink_in_this_transaction(OLD.organization_id, OLD.agent_id) THEN
        RAISE EXCEPTION 'revogacao pela maquina so pelo desvinculo registrado' USING ERRCODE = '42501';
    END IF;
    IF NEW.revoked_by_agent_id IS NOT NULL THEN
        NEW.revoked_at := clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_agent_credentials_unlink_guard BEFORE UPDATE ON public.agent_credentials
    FOR EACH ROW EXECUTE FUNCTION public.agent_credential_unlink_guard();

-- === 4. Pedidos de execucao e auditoria ===
DO $$
DECLARE
    v_name TEXT;
BEGIN
    SELECT conname INTO v_name FROM pg_constraint
     WHERE conrelid = 'public.run_requests'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) ~ 'LAUNCH_NOT_ATTEMPTED';
    IF v_name IS NULL THEN RAISE EXCEPTION '035: CHECK de decision_reason da 030 nao encontrada'; END IF;
    EXECUTE format('ALTER TABLE public.run_requests DROP CONSTRAINT %I', v_name);
END;
$$;
ALTER TABLE public.run_requests ADD CONSTRAINT run_requests_decision_reason_check CHECK (decision_reason IN (
    'PACKAGE_NOT_PRESENT', 'PACKAGE_NOT_APPROVED', 'AUTHORIZATION_UNUSABLE', 'ENVIRONMENT_INCOMPATIBLE', 'BUSY', 'EXPIRED',
    'LAUNCH_NOT_ATTEMPTED', 'AGENT_UNLINKED'));
-- AGENT_UNLINKED so como recusa (o vinculo terminou antes de a maquina decidir)
ALTER TABLE public.run_requests ADD CONSTRAINT run_requests_unlinked_refusal CHECK (decision_reason IS DISTINCT FROM 'AGENT_UNLINKED' OR state = 4);

ALTER TABLE public.agent_admin_events DROP CONSTRAINT agent_admin_events_act_check;
ALTER TABLE public.agent_admin_events ADD CONSTRAINT agent_admin_events_act_check CHECK (act BETWEEN 1 AND 14);
-- ato 14 (desvinculado pela maquina): autoria so da propria maquina, sem pessoa
ALTER TABLE public.agent_admin_events ADD CONSTRAINT agent_admin_events_machine_unlink CHECK (
    act <> 14 OR (actor_membership_id IS NULL AND actor_agent_id IS NOT NULL AND actor_agent_id = agent_id AND worker_id IS NOT NULL));
COMMENT ON COLUMN public.agent_admin_events.act IS
    '1..13 (025/030); 14=AGENT_DESVINCULADO_PELA_MAQUINA (035: actor_agent_id = agent_id, sem pessoa)';

-- === 5. RLS (padrao da 025/030) ===
DO $$
DECLARE
    ctx_req CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.organization_id'', true), '''')::uuid';
BEGIN
    ALTER TABLE public.agent_unlink_operations ENABLE ROW LEVEL SECURITY;
    ALTER TABLE public.agent_unlink_operations FORCE ROW LEVEL SECURITY;
    EXECUTE format('CREATE POLICY tenant_request_context ON public.agent_unlink_operations AS PERMISSIVE FOR ALL TO nekkora_platform, nekkora_ingestion USING (%s) WITH CHECK (%s)',
                   ctx_req, ctx_req);
END;
$$;

-- === 6. Privilegios ===
REVOKE ALL ON public.agent_unlink_operations FROM PUBLIC;
REVOKE ALL ON FUNCTION public.agent_unlink_operation_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.agent_unlink_in_this_transaction(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.agent_status_unlink_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.worker_agent_assignment_unlink_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.agent_credential_unlink_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.agent_unlink_in_this_transaction(UUID, UUID) TO nekkora_ingestion, nekkora_platform;
GRANT SELECT, INSERT ON public.agent_unlink_operations TO nekkora_ingestion;
GRANT SELECT ON public.agent_unlink_operations TO nekkora_platform;
GRANT UPDATE (status) ON public.agents TO nekkora_ingestion;
GRANT UPDATE (status, ended_at, ended_by_agent_id) ON public.worker_agent_assignments TO nekkora_ingestion;
GRANT UPDATE (revoked_by_agent_id) ON public.agent_credentials TO nekkora_ingestion;

-- === 7. Invariantes ===
DO $$
DECLARE
    v_bad TEXT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = 'public.agent_unlink_operations'::regclass AND c.relrowsecurity AND c.relforcerowsecurity) THEN
        RAISE EXCEPTION '035: RLS ausente em agent_unlink_operations';
    END IF;
    IF (SELECT string_agg(p.polname || ':' || r.rolname, ',' ORDER BY r.rolname) FROM pg_policy p, unnest(p.polroles) x
          JOIN pg_roles r ON r.oid = x WHERE p.polrelid = 'public.agent_unlink_operations'::regclass)
       IS DISTINCT FROM 'tenant_request_context:nekkora_ingestion,tenant_request_context:nekkora_platform' THEN
        RAISE EXCEPTION '035: politicas divergentes em agent_unlink_operations';
    END IF;
    SELECT string_agg(r.rolname, ',') INTO v_bad FROM pg_roles r
     WHERE NOT r.rolsuper AND r.rolname IN ('nekkora_platform', 'nekkora_ingestion')
       AND (has_table_privilege(r.oid, 'public.agent_unlink_operations', 'UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
            OR has_any_column_privilege(r.oid, 'public.agent_unlink_operations', 'UPDATE'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '035: alteracao possivel em agent_unlink_operations: %', v_bad; END IF;
    IF has_table_privilege('nekkora_platform', 'public.agent_unlink_operations', 'INSERT') THEN
        RAISE EXCEPTION '035: a Platform nao registra desvinculo pela maquina';
    END IF;
    -- a ingestao: so as colunas novas do desvinculo, nada alem (a lista da 025 continua valendo)
    IF has_table_privilege('nekkora_ingestion', 'public.agents', 'UPDATE')
       OR has_table_privilege('nekkora_ingestion', 'public.worker_agent_assignments', 'UPDATE')
       OR has_column_privilege('nekkora_ingestion', 'public.worker_agent_assignments', 'ended_by_membership_id', 'UPDATE')
       OR has_column_privilege('nekkora_ingestion', 'public.agents', 'organization_id', 'UPDATE')
       OR has_column_privilege('nekkora_ingestion', 'public.agents', 'name', 'UPDATE')
       OR has_table_privilege('nekkora_ingestion', 'public.workers', 'INSERT, UPDATE')
       OR has_table_privilege('nekkora_ingestion', 'public.worker_workspace_assignments', 'INSERT, UPDATE')
       OR has_table_privilege('nekkora_ingestion', 'public.local_run_authorizations', 'INSERT, UPDATE')
       OR has_column_privilege('nekkora_ingestion', 'public.agent_credentials', 'expires_at', 'UPDATE') THEN
        RAISE EXCEPTION '035: nekkora_ingestion com privilegio alem do desvinculo da propria maquina';
    END IF;
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname IN ('agent_unlink_operation_on_insert', 'agent_unlink_in_this_transaction',
                                                  'agent_status_unlink_guard', 'worker_agent_assignment_unlink_guard',
                                                  'agent_credential_unlink_guard')
       AND (p.prosecdef OR NOT ('search_path=pg_catalog, pg_temp' = ANY (coalesce(p.proconfig, '{}')))
            OR has_function_privilege('public', p.oid, 'EXECUTE'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '035: funcao do desvinculo fora do padrao: %', v_bad; END IF;
    SELECT string_agg(t || '.' || g, ', ') INTO v_bad
      FROM (VALUES ('public.agents', 'trg_agents_unlink_guard'), ('public.worker_agent_assignments', 'trg_worker_agent_assignments_unlink_guard'),
                   ('public.agent_credentials', 'trg_agent_credentials_unlink_guard'),
                   ('public.agent_unlink_operations', 'trg_agent_unlink_operations_on_insert'),
                   ('public.agent_unlink_operations', 'trg_agent_unlink_operations_prevent_update')) v(t, g)
     WHERE NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = t::regclass AND tgname = g AND NOT tgisinternal AND tgenabled = 'O');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '035: gatilho ausente ou desligado: %', v_bad; END IF;
    -- linhas existentes continuam validas pelas regras novas (validacao das CHECKs ja feita no ADD CONSTRAINT)
END;
$$;

COMMIT;
