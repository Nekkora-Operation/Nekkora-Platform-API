-- Nekkora Platform - renovacao da credencial de uma maquina habilitada para execucao remota, pela propria maquina
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01b). Contrato: docs/platform-client-execucao-remota-001/t01b-continuidade-da-credencial.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 042 (PR da T01a). Numero 043 conferido em 15/11 contra a dev e os PRs
-- abertos. As ferramentas do laboratorio e da homologacao exigem numeracao contigua: se outra 043 entrar antes, esta e
-- renumerada sem mudar o conteudo.
--
-- O PROBLEMA: a credencial da maquina vale 90 dias e, ate aqui, so era trocada com um codigo de rotacao criado por uma
-- pessoa. Uma maquina habilitada para execucao remota precisa continuar valendo sem que alguem refaca a habilitacao a
-- cada 90 dias - e sem que o token de uma pessoa vire credencial permanente.
--
-- O QUE FAZ
--   1. agent_credential_renewals: o registro de uma renovacao. So nasce para uma maquina HABILITADA (042), ativa e
--      associada, a partir da credencial atual dela, ainda nao revogada e ainda NAO VENCIDA. Uma por credencial de origem.
--   2. agent_credentials: motivo de revogacao 6 = MACHINE_RENEWAL, de autoria da propria maquina. So e aceito na transacao
--      que registrou a renovacao DAQUELA credencial. A regra antiga continua: uma so credencial nao revogada por Agent
--      (indice da 004) - por isso a troca e uma transacao so: registra a renovacao, revoga a anterior, emite a nova.
--   3. Na transacao de uma renovacao, a credencial nova so nasce com o hash registrado e com o MESMO emissor da anterior
--      (a pessoa que autorizou o vinculo): a renovacao herda a autoridade, nao cria outra. No fim da transacao, a
--      renovacao registrada tem de ter sido cumprida por inteiro (anterior revogada com o motivo 6, nova emitida).
--   4. agent_admin_events: ato 18 (credencial renovada pela maquina).
--
-- O QUE NAO FAZ: nao renova credencial vencida nem revogada (essas nunca voltam a valer: a saida e habilitar de novo); nao
-- muda a rotacao por codigo (motivo 1), o desvinculo (motivo 5) nem a revogacao por pessoas; nao renova a credencial de um
-- Agent que nao foi habilitado para execucao remota - o Agent anterior continua exatamente como esta.
--
-- DADOS EXISTENTES: nenhuma linha e alterada. A tabela nova nasce vazia. As CHECKs so ampliam o que e aceito.
-- Diagnostico somente leitura, para rodar antes (como migrador ou superusuario):
--   SELECT revocation_reason, count(*) FROM public.agent_credentials GROUP BY 1 ORDER BY 1;   -- esperado: so 1..5 ou nulo
--   SELECT pg_get_constraintdef(oid) FROM pg_constraint
--    WHERE conrelid = 'public.agent_credentials'::regclass AND conname IN ('agent_credentials_revocation_reason_check', 'agent_credentials_reason_machine');
--
-- REVERSAO (manual, so enquanto nao houver renovacao registrada; a auditoria e append-only):
--   BEGIN;
--   DROP TRIGGER trg_agent_credentials_renewal_insert ON public.agent_credentials;
--   DROP FUNCTION public.agent_credential_renewal_insert_guard();
--   DROP TABLE public.agent_credential_renewals;   -- leva junto os gatilhos dela
--   DROP FUNCTION public.agent_credential_renewal_on_insert();
--   DROP FUNCTION public.agent_credential_renewal_fulfilled();
--   -- agent_credential_unlink_guard(): restaurar o corpo da 035 (CREATE OR REPLACE com o texto de la)
--   DROP FUNCTION public.agent_credential_renewal_in_this_transaction(uuid, uuid);
--   ALTER TABLE public.agent_credentials DROP CONSTRAINT agent_credentials_reason_machine;
--   ALTER TABLE public.agent_credentials ADD CONSTRAINT agent_credentials_reason_machine CHECK ((revocation_reason = 5) = (revoked_by_agent_id IS NOT NULL));
--   ALTER TABLE public.agent_credentials DROP CONSTRAINT agent_credentials_revocation_reason_check;
--   ALTER TABLE public.agent_credentials ADD CONSTRAINT agent_credentials_revocation_reason_check CHECK (revocation_reason IN (1, 2, 3, 4, 5));
--   ALTER TABLE public.agent_admin_events DROP CONSTRAINT agent_admin_events_remote_machine;
--   ALTER TABLE public.agent_admin_events ADD CONSTRAINT agent_admin_events_remote_machine CHECK (
--       act NOT IN (15, 16, 17) OR (actor_membership_id IS NULL AND actor_agent_id IS NOT NULL AND actor_agent_id = agent_id AND worker_id IS NOT NULL));
--   ALTER TABLE public.agent_admin_events DROP CONSTRAINT agent_admin_events_act_check;
--   ALTER TABLE public.agent_admin_events ADD CONSTRAINT agent_admin_events_act_check CHECK (act BETWEEN 1 AND 17);
--   COMMIT;
--
-- SEM ELEVACAO: tabela nova; CHECKs e funcao de gatilho em objetos do dono das migrations do canal (004, 035, 042);
-- gatilhos SECURITY INVOKER. Nenhum papel novo, nenhuma funcao SECURITY DEFINER, nenhum dono novo.

BEGIN;

-- === 1. Pre-condicao: 042 aplicada; as CHECKs sao as da 035 e da 042 ===
DO $$
BEGIN
    IF to_regclass('public.agent_remote_enablements') IS NULL THEN
        RAISE EXCEPTION '043 exige a 042 aplicada';
    END IF;
    IF to_regclass('public.agent_credential_renewals') IS NOT NULL THEN
        RAISE EXCEPTION '043: agent_credential_renewals ja existe';
    END IF;
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.agent_admin_events'::regclass AND c.conname = 'agent_admin_events_act_check')
       IS DISTINCT FROM 'CHECK (((act >= 1) AND (act <= 17)))' THEN
        RAISE EXCEPTION '043: CHECK agent_admin_events_act_check diferente do da 042';
    END IF;
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.agent_credentials'::regclass AND c.conname = 'agent_credentials_reason_machine')
       IS DISTINCT FROM 'CHECK (((revocation_reason = 5) = (revoked_by_agent_id IS NOT NULL)))' THEN
        RAISE EXCEPTION '043: CHECK agent_credentials_reason_machine diferente do da 035';
    END IF;
END;
$$;

-- === 2. Registro da renovacao ===
CREATE TABLE public.agent_credential_renewals (
    renewal_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL,
    agent_id            UUID NOT NULL,
    from_credential_id  UUID NOT NULL UNIQUE REFERENCES public.agent_credentials (credential_id) ON DELETE RESTRICT,
    to_credential_hash  TEXT NOT NULL CHECK (to_credential_hash ~ '^[0-9a-f]{64}$'),
    renewed_at          TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (organization_id, renewal_id),
    FOREIGN KEY (organization_id, agent_id) REFERENCES public.agents (organization_id, agent_id) ON DELETE RESTRICT
);
COMMENT ON TABLE public.agent_credential_renewals IS
    'Renovacao da credencial pedida pela propria maquina habilitada para execucao remota (043). Uma por credencial de origem; '
    'autoriza, na mesma transacao, revogar a anterior (motivo 6) e emitir a nova com o hash registrado.';
COMMENT ON COLUMN public.agent_credential_renewals.to_credential_hash IS
    'SHA-256 hex do segredo novo, gerado pela maquina. O valor bruto nunca e persistido.';

CREATE FUNCTION public.agent_credential_renewal_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.renewed_at := clock_timestamp();
    -- so para a maquina HABILITADA, ativa e associada, a partir da credencial ATUAL dela: nao revogada e nao vencida
    IF NOT EXISTS (SELECT 1 FROM public.agents a
                     JOIN public.agent_remote_enablements r ON r.organization_id = a.organization_id AND r.agent_id = a.agent_id
                     JOIN public.worker_agent_assignments wa ON wa.organization_id = a.organization_id AND wa.agent_id = a.agent_id AND wa.status = 1
                     JOIN public.agent_credentials c ON c.organization_id = a.organization_id AND c.agent_id = a.agent_id
                    WHERE a.organization_id = NEW.organization_id AND a.agent_id = NEW.agent_id AND a.status = 2
                      AND c.credential_id = NEW.from_credential_id AND c.revoked_at IS NULL AND c.expires_at > clock_timestamp()
                      AND c.credential_hash <> NEW.to_credential_hash) THEN
        RAISE EXCEPTION 'renovacao so para a credencial valida de uma maquina habilitada' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_agent_credential_renewals_on_insert BEFORE INSERT ON public.agent_credential_renewals
    FOR EACH ROW EXECUTE FUNCTION public.agent_credential_renewal_on_insert();
CREATE TRIGGER trg_agent_credential_renewals_prevent_update BEFORE UPDATE ON public.agent_credential_renewals
    FOR EACH ROW EXECUTE FUNCTION public.prevent_update();
CREATE TRIGGER trg_agent_credential_renewals_prevent_delete BEFORE DELETE ON public.agent_credential_renewals
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_agent_credential_renewals_prevent_truncate BEFORE TRUNCATE ON public.agent_credential_renewals
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- "registrada nesta transacao": xmin da linha = id da transacao atual (32 bits), como no desvinculo (035).
CREATE FUNCTION public.agent_credential_renewal_in_this_transaction(p_org UUID, p_from_credential UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM public.agent_credential_renewals n
                    WHERE n.organization_id = p_org AND n.from_credential_id = p_from_credential
                      AND (n.xmin::text)::bigint = (pg_current_xact_id()::text::bigint % 4294967296));
$$;

-- No fim da transacao, a renovacao registrada foi cumprida por inteiro: a anterior revogada pela maquina (motivo 6) e a
-- nova, com o hash registrado, emitida e valendo. Uma renovacao pela metade desfaz a transacao.
CREATE FUNCTION public.agent_credential_renewal_fulfilled() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.agent_credentials c
                    WHERE c.organization_id = NEW.organization_id AND c.credential_id = NEW.from_credential_id
                      AND c.revocation_reason = 6 AND c.revoked_by_agent_id = NEW.agent_id)
       OR NOT EXISTS (SELECT 1 FROM public.agent_credentials c
                       WHERE c.organization_id = NEW.organization_id AND c.agent_id = NEW.agent_id
                         AND c.credential_hash = NEW.to_credential_hash AND c.revoked_at IS NULL) THEN
        RAISE EXCEPTION 'renovacao registrada sem a troca completa da credencial' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_agent_credential_renewals_fulfilled AFTER INSERT ON public.agent_credential_renewals
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.agent_credential_renewal_fulfilled();

-- === 3. Credencial: motivo 6, autoria da maquina so pela renovacao desta transacao ===
ALTER TABLE public.agent_credentials DROP CONSTRAINT agent_credentials_revocation_reason_check;
ALTER TABLE public.agent_credentials ADD CONSTRAINT agent_credentials_revocation_reason_check CHECK (revocation_reason IN (1, 2, 3, 4, 5, 6));
ALTER TABLE public.agent_credentials DROP CONSTRAINT agent_credentials_reason_machine;
ALTER TABLE public.agent_credentials ADD CONSTRAINT agent_credentials_reason_machine
    CHECK ((revocation_reason IN (5, 6)) = (revoked_by_agent_id IS NOT NULL));
COMMENT ON COLUMN public.agent_credentials.revocation_reason IS
    '1=ROTATION, 2=EXPIRED_REPLACEMENT, 3=COMPROMISED, 4=ADMINISTRATIVE, 5=MACHINE_UNLINK (a propria maquina, 035), '
    '6=MACHINE_RENEWAL (a propria maquina habilitada para execucao remota, substituida na mesma transacao; 043)';

-- O gatilho da 035 passa a conhecer os dois atos da maquina: 5 pelo desvinculo, 6 pela renovacao; qualquer outra revogacao
-- com autoria da maquina continua recusada.
CREATE OR REPLACE FUNCTION public.agent_credential_unlink_guard() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF (NEW.revoked_by_agent_id IS NOT NULL OR NEW.revocation_reason IN (5, 6)) AND OLD.revoked_at IS NULL THEN
        IF NEW.revocation_reason = 6 THEN
            IF NOT public.agent_credential_renewal_in_this_transaction(OLD.organization_id, OLD.credential_id) THEN
                RAISE EXCEPTION 'revogacao por renovacao so pela renovacao registrada desta credencial' USING ERRCODE = '42501';
            END IF;
        ELSIF NOT public.agent_unlink_in_this_transaction(OLD.organization_id, OLD.agent_id) THEN
            RAISE EXCEPTION 'revogacao pela maquina so pelo desvinculo registrado' USING ERRCODE = '42501';
        END IF;
    END IF;
    IF NEW.revoked_by_agent_id IS NOT NULL THEN
        NEW.revoked_at := clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;

-- Numa transacao que registrou uma renovacao para o Agent, a credencial nova e a DA renovacao: o hash registrado e o mesmo
-- emissor da anterior. Fora de uma renovacao, a emissao continua como sempre (resgate de codigo).
CREATE FUNCTION public.agent_credential_renewal_insert_guard() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_hash   TEXT;
    v_issuer UUID;
BEGIN
    SELECT n.to_credential_hash, c.issued_by_membership_id INTO v_hash, v_issuer
      FROM public.agent_credential_renewals n
      JOIN public.agent_credentials c ON c.credential_id = n.from_credential_id
     WHERE n.organization_id = NEW.organization_id AND n.agent_id = NEW.agent_id
       AND (n.xmin::text)::bigint = (pg_current_xact_id()::text::bigint % 4294967296);
    IF FOUND AND (NEW.credential_hash <> v_hash OR NEW.issued_by_membership_id <> v_issuer) THEN
        RAISE EXCEPTION 'na renovacao, a credencial nova e a registrada e herda o emissor da anterior' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_agent_credentials_renewal_insert BEFORE INSERT ON public.agent_credentials
    FOR EACH ROW EXECUTE FUNCTION public.agent_credential_renewal_insert_guard();

-- === 4. Auditoria: ato 18 (autoria da propria maquina) ===
ALTER TABLE public.agent_admin_events DROP CONSTRAINT agent_admin_events_act_check;
ALTER TABLE public.agent_admin_events ADD CONSTRAINT agent_admin_events_act_check CHECK (act BETWEEN 1 AND 18);
ALTER TABLE public.agent_admin_events DROP CONSTRAINT agent_admin_events_remote_machine;
ALTER TABLE public.agent_admin_events ADD CONSTRAINT agent_admin_events_remote_machine CHECK (
    act NOT IN (15, 16, 17, 18) OR (actor_membership_id IS NULL AND actor_agent_id IS NOT NULL AND actor_agent_id = agent_id AND worker_id IS NOT NULL));
COMMENT ON COLUMN public.agent_admin_events.act IS
    '1..13 (025/030); 14=AGENT_DESVINCULADO_PELA_MAQUINA (035); 15=EXECUCAO_REMOTA_HABILITADA; 16=RECEBIMENTO_PAUSADO; '
    '17=RECEBIMENTO_RETOMADO (042); 18=CREDENCIAL_RENOVADA_PELA_MAQUINA (043: credential_id e a credencial NOVA)';

-- === 5. RLS (padrao da 025/030/035/042) ===
DO $$
DECLARE
    ctx_req CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.organization_id'', true), '''')::uuid';
BEGIN
    ALTER TABLE public.agent_credential_renewals ENABLE ROW LEVEL SECURITY;
    ALTER TABLE public.agent_credential_renewals FORCE ROW LEVEL SECURITY;
    EXECUTE format('CREATE POLICY tenant_request_context ON public.agent_credential_renewals AS PERMISSIVE FOR ALL TO nekkora_platform, nekkora_ingestion USING (%s) WITH CHECK (%s)',
                   ctx_req, ctx_req);
END;
$$;

-- === 6. Privilegios: a maquina registra a renovacao; as pessoas so leem. Ninguem altera nem apaga. ===
REVOKE ALL ON public.agent_credential_renewals FROM PUBLIC;
REVOKE ALL ON FUNCTION public.agent_credential_renewal_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.agent_credential_renewal_fulfilled() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.agent_credential_renewal_insert_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.agent_credential_renewal_in_this_transaction(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.agent_credential_renewal_in_this_transaction(uuid, uuid) TO nekkora_ingestion, nekkora_platform;
GRANT SELECT ON public.agent_credential_renewals TO nekkora_platform;
GRANT SELECT, INSERT ON public.agent_credential_renewals TO nekkora_ingestion;

-- === 7. Invariantes ===
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = 'public.agent_credential_renewals'::regclass AND c.relrowsecurity AND c.relforcerowsecurity) THEN
        RAISE EXCEPTION '043: RLS ausente em agent_credential_renewals';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.agent_credential_renewals', 'INSERT')
       OR has_table_privilege('nekkora_platform', 'public.agent_credential_renewals', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.agent_credential_renewals', 'DELETE')
       OR has_table_privilege('nekkora_ingestion', 'public.agent_credential_renewals', 'UPDATE')
       OR has_table_privilege('nekkora_ingestion', 'public.agent_credential_renewals', 'DELETE')
       OR has_column_privilege('nekkora_ingestion', 'public.agent_credentials', 'expires_at', 'UPDATE')
       OR has_column_privilege('nekkora_ingestion', 'public.agent_credentials', 'credential_hash', 'UPDATE') THEN
        RAISE EXCEPTION '043: privilegio alem do previsto na renovacao da credencial';
    END IF;
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.agent_admin_events'::regclass AND c.conname = 'agent_admin_events_act_check')
       IS DISTINCT FROM 'CHECK (((act >= 1) AND (act <= 18)))' THEN
        RAISE EXCEPTION '043: CHECK novo do ato diferente do esperado';
    END IF;
    IF EXISTS (SELECT 1 FROM public.agent_credential_renewals) OR EXISTS (SELECT 1 FROM public.agent_credentials WHERE revocation_reason = 6) THEN
        RAISE EXCEPTION '043: nenhuma renovacao pode existir antes da migration';
    END IF;
END;
$$;

COMMIT;
