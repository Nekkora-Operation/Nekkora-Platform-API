-- Nekkora Platform - habilitacao de uma maquina para execucoes solicitadas pela Platform
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01a). Contrato: docs/platform-client-execucao-remota-001/t00-plano-de-contratos.md
-- (secoes 4, 5.1 e 10) e docs/platform-client-execucao-remota-001/t01a-habilitacao-e-elegibilidade.md.
--
-- CANDIDATA ate a aprovacao do PR. Numero 042 conferido em 15/11 contra a dev (6db8519; a maior era 041) e os PRs abertos
-- (nenhum traz migration). As ferramentas do laboratorio e da homologacao exigem numeracao contigua: se outra 042 entrar
-- antes, esta e renumerada sem mudar o conteudo.
--
-- O QUE FAZ
--   1. agent_enrollments.remote_execution: o codigo de PAREAMENTO pode ser criado MARCADO para execucao remota. A marca e
--      escolhida por quem cria o codigo (com as permissoes e a confirmacao reforcada que o pareamento ja exige) e nao muda.
--   2. agent_remote_enablements: uma linha por Agent habilitado. So nasce no RESGATE de um codigo marcado, na mesma
--      transacao (o gatilho confere), e guarda QUEM habilitou (a pessoa que criou o codigo) - distinta da maquina e de quem
--      vier a solicitar execucoes. Guarda tambem se a maquina esta recebendo (1) ou pausada (2); so a propria maquina muda
--      isso.
--   3. agent_presence: quatro capacidades novas no batimento (REMOTE_RUN_1_0, REMOTE_LOGS_1_0, REMOTE_RESULTS_1_0,
--      PY_INVENTORY_1_0) e dois sinais da maquina (ocupada, pressao de disco). Maquina sem REMOTE_RUN_1_0 nunca e apta.
--   4. agent_admin_events: atos 15 (execucao remota habilitada), 16 (recebimento pausado) e 17 (recebimento retomado),
--      todos de autoria da propria maquina.
--
-- O QUE NAO FAZ: nao cria solicitacao, tentativa, log nem resultado (ficam para as migrations seguintes da missao); nao muda
-- o fluxo do Agent anterior (pedidos da 030, execucoes da 025); nao cria papel, dono nem permissao no catalogo de papeis;
-- nao habilita nenhuma maquina existente - todo codigo ja criado e todo Agent ja pareado continuam SEM execucao remota.
--
-- DADOS EXISTENTES: a coluna nova nasce false em todos os codigos; a tabela nova nasce vazia; os sinais novos nascem false.
-- O CHECK de capacidades so amplia o que e aceito. Nenhuma linha existente e alterada alem do valor padrao das colunas.
-- Diagnostico somente leitura, para rodar antes (como migrador ou superusuario; as tabelas tem RLS forcada):
--   SELECT count(*) FILTER (WHERE consumed_at IS NULL AND cancelled_at IS NULL AND expires_at > clock_timestamp()) AS codigos_abertos,
--          count(*) AS codigos FROM public.agent_enrollments;
--   SELECT pg_get_constraintdef(oid) FROM pg_constraint
--    WHERE conrelid = 'public.agent_presence'::regclass AND conname = 'agent_presence_capabilities_check';
--   SELECT pg_get_constraintdef(oid) FROM pg_constraint
--    WHERE conrelid = 'public.agent_admin_events'::regclass AND conname = 'agent_admin_events_act_check';
--   -- esperado: CHECK (((act >= 1) AND (act <= 14)))
--
-- REVERSAO (manual, so enquanto nao houver maquina habilitada nem ato 15-17 gravado; a auditoria e append-only, entao depois
-- disso a reversao exige decisao humana sobre as linhas e nao e oferecida aqui):
--   BEGIN;
--   DROP TABLE public.agent_remote_enablements;
--   DROP FUNCTION public.agent_remote_enablement_on_insert();
--   DROP FUNCTION public.agent_remote_enablement_on_update();
--   ALTER TABLE public.agent_admin_events DROP CONSTRAINT agent_admin_events_remote_machine;
--   ALTER TABLE public.agent_admin_events DROP CONSTRAINT agent_admin_events_act_check;
--   ALTER TABLE public.agent_admin_events ADD CONSTRAINT agent_admin_events_act_check CHECK (act BETWEEN 1 AND 14);
--   ALTER TABLE public.agent_presence DROP CONSTRAINT agent_presence_capabilities_check;
--   ALTER TABLE public.agent_presence ADD CONSTRAINT agent_presence_capabilities_check CHECK (cardinality(capabilities) <= 8
--       AND capabilities <@ ARRAY['RUN_LOCAL', 'OBS_1_0', 'SUPERVISOR_REPORT_1_0', 'EXTERNAL_PYTHON_1_0', 'RUN_REQUEST_1_0']::text[]);
--   ALTER TABLE public.agent_presence DROP COLUMN remote_busy, DROP COLUMN disk_pressure;
--   ALTER TABLE public.agent_enrollments DROP COLUMN remote_execution;
--   COMMIT;
--   (os ADD CONSTRAINT falham, sem alterar nada, se ja existir ato 15-17 ou capacidade nova gravada.) A API anterior nao
--   cria codigo marcado nem aceita as capacidades novas.
--
-- SEM ELEVACAO: tabela nova, colunas e constraints em tabelas do dono das migrations do canal (025, 030, 035), gatilhos
-- SECURITY INVOKER. Nenhum papel novo, nenhuma funcao SECURITY DEFINER, nenhum dono novo.

BEGIN;

-- === 1. Pre-condicao: 041 aplicada; os CHECKs sao os da 030 e da 035 ===
DO $$
BEGIN
    IF (SELECT position('1\.1\.' IN pg_get_constraintdef(c.oid)) FROM pg_constraint c
         WHERE c.conrelid = 'public.automation_artifacts'::regclass AND c.conname = 'automation_artifacts_kind_rule') IS NOT DISTINCT FROM 0 THEN
        RAISE EXCEPTION '042 exige a 041 aplicada';
    END IF;
    IF to_regclass('public.agent_remote_enablements') IS NOT NULL THEN
        RAISE EXCEPTION '042: agent_remote_enablements ja existe';
    END IF;
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.agent_admin_events'::regclass AND c.conname = 'agent_admin_events_act_check')
       IS DISTINCT FROM 'CHECK (((act >= 1) AND (act <= 14)))' THEN
        RAISE EXCEPTION '042: CHECK agent_admin_events_act_check diferente do da 035';
    END IF;
    IF (SELECT position('RUN_REQUEST_1_0' IN pg_get_constraintdef(c.oid)) = 0 OR position('REMOTE_RUN_1_0' IN pg_get_constraintdef(c.oid)) > 0
          FROM pg_constraint c
         WHERE c.conrelid = 'public.agent_presence'::regclass AND c.conname = 'agent_presence_capabilities_check') IS NOT FALSE THEN
        RAISE EXCEPTION '042: CHECK agent_presence_capabilities_check diferente do da 030';
    END IF;
END;
$$;

-- === 2. Codigo de pareamento marcado ===
-- So o pareamento (1) pode ser marcado: uma rotacao troca a credencial de um Agent que ja existe e nao muda o que ele pode.
ALTER TABLE public.agent_enrollments
    ADD COLUMN remote_execution BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT agent_enrollments_remote_only_pairing CHECK (NOT remote_execution OR purpose = 1);
COMMENT ON COLUMN public.agent_enrollments.remote_execution IS
    'Codigo de pareamento criado para habilitar a maquina a receber execucoes solicitadas pela Platform. Escolhido na criacao; imutavel.';

-- === 3. Habilitacao ===
CREATE TABLE public.agent_remote_enablements (
    agent_id                  UUID PRIMARY KEY,
    organization_id           UUID NOT NULL,
    worker_id                 UUID NOT NULL,
    enrollment_id             UUID NOT NULL UNIQUE,
    enabled_by_membership_id  UUID NOT NULL,
    enabled_at                TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    receiving                 SMALLINT NOT NULL DEFAULT 1 CHECK (receiving IN (1, 2)),
    receiving_changed_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (organization_id, agent_id),
    FOREIGN KEY (organization_id, agent_id) REFERENCES public.agents (organization_id, agent_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, worker_id) REFERENCES public.workers (organization_id, worker_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, enrollment_id) REFERENCES public.agent_enrollments (organization_id, enrollment_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, enabled_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT
);
COMMENT ON TABLE public.agent_remote_enablements IS
    'Agent habilitado a receber execucoes solicitadas pela Platform. Vale enquanto o Agent estiver ativo (status 2) e associado; '
    'nao e uma autorizacao de versao (essa continua em local_run_authorizations) nem do ambiente (worker_workspace_assignments).';
COMMENT ON COLUMN public.agent_remote_enablements.enabled_by_membership_id IS
    'QUEM HABILITOU: a pessoa que criou o codigo marcado. Nao e quem solicita execucoes nem a maquina.';
COMMENT ON COLUMN public.agent_remote_enablements.receiving IS
    '1=ENABLED (recebe novos trabalhos), 2=PAUSED (nao recebe novos; pausar nao cancela o que esta em andamento)';

-- A linha so nasce do resgate, NESTA transacao, de um codigo de pareamento MARCADO, para o Agent e o Worker desse codigo.
CREATE FUNCTION public.agent_remote_enablement_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.agent_enrollments e
                    WHERE e.organization_id = NEW.organization_id AND e.enrollment_id = NEW.enrollment_id
                      AND e.purpose = 1 AND e.remote_execution AND e.cancelled_at IS NULL
                      AND e.consumed_at IS NOT NULL AND e.consumed_at >= pg_catalog.transaction_timestamp()
                      AND e.agent_id = NEW.agent_id AND e.worker_id = NEW.worker_id
                      AND e.created_by_membership_id = NEW.enabled_by_membership_id) THEN
        RAISE EXCEPTION 'habilitacao remota so pelo resgate, nesta transacao, de um codigo de pareamento marcado' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.worker_agent_assignments a
                    WHERE a.organization_id = NEW.organization_id AND a.agent_id = NEW.agent_id AND a.worker_id = NEW.worker_id AND a.status = 1) THEN
        RAISE EXCEPTION 'habilitacao remota exige o Agent associado ao Worker do codigo' USING ERRCODE = '42501';
    END IF;
    NEW.enabled_at := clock_timestamp();
    NEW.receiving := 1;
    NEW.receiving_changed_at := NEW.enabled_at;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_agent_remote_enablements_on_insert BEFORE INSERT ON public.agent_remote_enablements
    FOR EACH ROW EXECUTE FUNCTION public.agent_remote_enablement_on_insert();

-- Depois de criada, so o recebimento muda (pausar e retomar); o resto e imutavel.
CREATE FUNCTION public.agent_remote_enablement_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.agent_id <> OLD.agent_id OR NEW.organization_id <> OLD.organization_id OR NEW.worker_id <> OLD.worker_id
       OR NEW.enrollment_id <> OLD.enrollment_id OR NEW.enabled_by_membership_id <> OLD.enabled_by_membership_id
       OR NEW.enabled_at <> OLD.enabled_at THEN
        RAISE EXCEPTION 'a habilitacao remota so muda o recebimento' USING ERRCODE = '42501';
    END IF;
    IF NEW.receiving IS DISTINCT FROM OLD.receiving THEN
        NEW.receiving_changed_at := clock_timestamp();
    ELSE
        NEW.receiving_changed_at := OLD.receiving_changed_at;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_agent_remote_enablements_on_update BEFORE UPDATE ON public.agent_remote_enablements
    FOR EACH ROW EXECUTE FUNCTION public.agent_remote_enablement_on_update();

-- === 4. Presenca: capacidades novas e sinais da maquina ===
ALTER TABLE public.agent_presence DROP CONSTRAINT agent_presence_capabilities_check;
ALTER TABLE public.agent_presence ADD CONSTRAINT agent_presence_capabilities_check CHECK (cardinality(capabilities) <= 12
    AND capabilities <@ ARRAY['RUN_LOCAL', 'OBS_1_0', 'SUPERVISOR_REPORT_1_0', 'EXTERNAL_PYTHON_1_0', 'RUN_REQUEST_1_0',
                              'REMOTE_RUN_1_0', 'REMOTE_LOGS_1_0', 'REMOTE_RESULTS_1_0', 'PY_INVENTORY_1_0']::text[]);
ALTER TABLE public.agent_presence
    ADD COLUMN remote_busy BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN disk_pressure BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN public.agent_presence.remote_busy IS
    'A maquina disse, no ultimo batimento, que ja esta executando (local ou remota). Indicativo: a reserva e decidida pela solicitacao.';
COMMENT ON COLUMN public.agent_presence.disk_pressure IS
    'A maquina disse, no ultimo batimento, que esta sem espaco para aceitar trabalho novo.';

-- === 5. Auditoria: atos 15, 16 e 17 (autoria da propria maquina) ===
ALTER TABLE public.agent_admin_events DROP CONSTRAINT agent_admin_events_act_check;
ALTER TABLE public.agent_admin_events ADD CONSTRAINT agent_admin_events_act_check CHECK (act BETWEEN 1 AND 17);
ALTER TABLE public.agent_admin_events ADD CONSTRAINT agent_admin_events_remote_machine CHECK (
    act NOT IN (15, 16, 17) OR (actor_membership_id IS NULL AND actor_agent_id IS NOT NULL AND actor_agent_id = agent_id AND worker_id IS NOT NULL));
COMMENT ON COLUMN public.agent_admin_events.act IS
    '1..13 (025/030); 14=AGENT_DESVINCULADO_PELA_MAQUINA (035); 15=EXECUCAO_REMOTA_HABILITADA (042: no resgate do codigo marcado; '
    'enrollment_id diz o codigo, e o codigo diz quem habilitou); 16=RECEBIMENTO_PAUSADO; 17=RECEBIMENTO_RETOMADO (042, pela maquina)';

-- === 6. RLS (padrao da 025/030/035) ===
DO $$
DECLARE
    ctx_req CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.organization_id'', true), '''')::uuid';
BEGIN
    ALTER TABLE public.agent_remote_enablements ENABLE ROW LEVEL SECURITY;
    ALTER TABLE public.agent_remote_enablements FORCE ROW LEVEL SECURITY;
    EXECUTE format('CREATE POLICY tenant_request_context ON public.agent_remote_enablements AS PERMISSIVE FOR ALL TO nekkora_platform, nekkora_ingestion USING (%s) WITH CHECK (%s)',
                   ctx_req, ctx_req);
END;
$$;

-- === 7. Privilegios ===
-- A Platform (pessoas) escolhe a marca ao criar o codigo e so LE a habilitacao; a ingestao (maquina) cria a habilitacao no
-- resgate e muda so o recebimento. Ninguem apaga.
REVOKE ALL ON public.agent_remote_enablements FROM PUBLIC;
REVOKE ALL ON FUNCTION public.agent_remote_enablement_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.agent_remote_enablement_on_update() FROM PUBLIC;
GRANT SELECT ON public.agent_remote_enablements TO nekkora_platform;
GRANT SELECT, INSERT ON public.agent_remote_enablements TO nekkora_ingestion;
GRANT UPDATE (receiving) ON public.agent_remote_enablements TO nekkora_ingestion;

-- === 8. Invariantes ===
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = 'public.agent_remote_enablements'::regclass AND c.relrowsecurity AND c.relforcerowsecurity) THEN
        RAISE EXCEPTION '042: RLS ausente em agent_remote_enablements';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.agent_remote_enablements', 'INSERT')
       OR has_table_privilege('nekkora_platform', 'public.agent_remote_enablements', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.agent_remote_enablements', 'DELETE')
       OR has_table_privilege('nekkora_ingestion', 'public.agent_remote_enablements', 'DELETE')
       OR has_column_privilege('nekkora_ingestion', 'public.agent_remote_enablements', 'enabled_by_membership_id', 'UPDATE')
       OR has_column_privilege('nekkora_ingestion', 'public.agent_enrollments', 'remote_execution', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.agent_enrollments', 'remote_execution', 'UPDATE') THEN
        RAISE EXCEPTION '042: privilegio alem do previsto na habilitacao remota';
    END IF;
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.agent_admin_events'::regclass AND c.conname = 'agent_admin_events_act_check')
       IS DISTINCT FROM 'CHECK (((act >= 1) AND (act <= 17)))' THEN
        RAISE EXCEPTION '042: CHECK novo do ato diferente do esperado';
    END IF;
    IF EXISTS (SELECT 1 FROM public.agent_remote_enablements) OR EXISTS (SELECT 1 FROM public.agent_enrollments WHERE remote_execution) THEN
        RAISE EXCEPTION '042: nenhuma maquina pode nascer habilitada';
    END IF;
END;
$$;

COMMIT;
