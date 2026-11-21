-- Nekkora Platform - PLATFORM-FLUXO-002: convite de Cliente para acompanhar UMA automacao, concessao por automacao (e maquina),
-- aceite com a identidade global (fechamento da D-EXT) e Portal ampliado para a area do cliente.
-- Contrato: api/openapi/nekkora-api-v1.yaml e docs/platform-fluxo-002/decisoes.md ("Clientes e acessos").
--
-- CANDIDATA ate a aprovacao do PR. Numero 070 conferido em 21/11/2026 contra a dev (cbfd44a; a maior era 069). As ferramentas
-- do laboratorio e da homologacao exigem numeracao contigua: se outra migration ocupar 070 antes, esta e renumerada sem mudar o
-- conteudo. Cria funcoes de outros donos (nekkora_identity_owner, nekkora_portal_owner): exige a janela de SUPERUSER do migrador,
-- como a 067. SO LABORATORIO E TESTES ate autorizacao especifica (nao esta em ops/homologacao/implantacao/elevacao.txt).
--
-- DECISOES DA TITULAR (21/11/2026), aplicadas aqui sem alterar o texto das migrations anteriores (017, 023 e 024 preservadas; as
-- funcoes que mudam sao substituidas por CREATE OR REPLACE, como a 024 fez):
--   D-EXT (fechada): o cliente entra com a IDENTIDADE Nekkora (mesmo e-mail verificado do convite, MFA previsto). O aceite cria
--     ou reutiliza a conta de cliente (users.account_type = 2) e o vinculo de cliente (client_memberships) SO naquela organizacao,
--     ligados a identidade (identity_users, canal 'convite'). Sem senha implicita. Identidade que ja e membro interno da
--     organizacao: recusa (MEMBRO_INTERNO). Conta da organizacao com o mesmo e-mail e nao ligada a esta identidade (as contas de
--     cliente antigas): recusa (CONTA_EXISTENTE) - nunca e ligada por coincidencia de e-mail. Ser membro interno de OUTRA
--     organizacao nao impede.
--   D-GRANT-1 (excecao): o convite de Cliente restrito ao acompanhamento de UMA automacao e aprovado por quem convida
--     (administradora: org.manage e external_access.manage, com a confirmacao reforcada da aplicacao). O acesso so comeca no
--     aceite, que reconfere identidade, convite, autoridade de quem convidou e escopo. A concessao nasce ATIVA com base 2
--     (CONVITE_DE_CLIENTE) somente pela funcao de aceite; qualquer outra concessao (inclusive compartilhar outra automacao depois)
--     nasce PENDENTE com base 1 e so vale com a aprovacao de OUTRA pessoa. Ampliar permissoes de uma concessao existente e
--     proibido no banco (so reduzir); ampliar e pedir outra concessao, que substitui a anterior na aprovacao.
--   Escopo por automacao: qualquer ambiente de trabalho ativo da organizacao, sem exigir que pertenca ao cliente. As concessoes
--     por ambiente inteiro (client_workspace_grants, 003/023/024) nao mudam, nem a regra de pertencimento delas.
--
-- O QUE MUDA
--   1. client_automation_grants (nova): concessao de leitura de uma automacao a um vinculo de cliente, opcionalmente restrita a
--      uma maquina, com permissoes explicitas de registros e de resultados. Estados 1=PENDENTE 2=ATIVA 4=REVOGADA (sem suspensao:
--      suspende-se o vinculo). Base 1=SEGUNDA_PESSOA (D-GRANT-1) 2=CONVITE_DE_CLIENTE (excecao). Gatilho confere autoridade,
--      segunda pessoa, origem imutavel, so reducao de permissoes e transicoes.
--   2. identity_invitations: colunas automation_id, worker_id, can_read_logs, can_download_results (so no convite de cliente).
--      O gatilho de insercao da 023 passa a aceitar invited_account_type = 2 com cliente ativo, automacao ativa num ambiente
--      ativo, maquina (se houver) ativa e do ambiente, emissor com org.manage e external_access.manage e destinataria que nao e
--      conta interna da organizacao. O de atualizacao inclui as colunas novas na origem imutavel e usa a autoridade de convite
--      de cliente no fechamento por perda de autoridade (motivo 4).
--   3. Aceite: identity_accept_invitation passa a tentar o convite de cliente quando nao ha convite interno com o token.
--      Resultados novos: MEMBRO_INTERNO, CLIENTE_DIFERENTE, CONCESSAO_EXISTENTE, ESCOPO_INDISPONIVEL.
--   4. access_events: tipos 15..24 e colunas de escopo (cliente, vinculo de cliente, automacao, maquina, concessao por
--      automacao, permissoes) e grant_exception (uso da excecao: convite emitido/reenviado e concessao aplicada no aceite).
--         15=CLIENTE_CONCESSAO_SOLICITADA 16=CLIENTE_CONCESSAO_APROVADA 17=CLIENTE_CONCESSAO_APLICADA_NO_ACEITE
--         18=CLIENTE_CONCESSAO_REDUZIDA 19=CLIENTE_CONCESSAO_REVOGADA 20=VINCULO_CLIENTE_SUSPENSO 21=VINCULO_CLIENTE_REATIVADO
--         22=VINCULO_CLIENTE_RETIRADO 23=CLIENTE_CADASTRADO 24=CLIENTE_ALTERADO
--   5. Portal (8.2 preservado: processo e identidade de banco proprios): funcoes SECURITY DEFINER do nekkora_portal_owner que
--      autenticam pela SESSAO DE IDENTIDADE (cookie __Host-nekkora_id, conferida por identity_session_lookup) e, a cada chamada,
--      reconferem conta, vinculo de cliente, cliente, organizacao e as concessoes vigentes. Nenhuma sessao de organizacao e
--      emitida para a conta de cliente: as rotas da Platform continuam fechadas para ela. As funcoes antigas da 013 ficam
--      intactas (contas de cliente antigas).
--
-- DADOS EXISTENTES: nenhuma linha muda. Contas de cliente antigas continuam como estao (sem ligacao a identidade); concessoes por
-- ambiente pendentes NAO sao aprovadas; convites internos seguem iguais.
--
-- REVERSAO (manual; so antes de qualquer convite de cliente ou concessao por automacao): recriar as funcoes da 023/024 com o
-- corpo original, DROP das funcoes portal_client_*, DROP TABLE client_automation_grants, DROP das colunas novas de
-- identity_invitations e access_events e recriar access_events_event_type_check com 1..14.
--
-- Atomica: uma transacao.

BEGIN;

-- === 1. Pre-condicoes: 069 aplicada; objetos novos ausentes ===
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'public.local_run_authorizations'::regclass AND attname = 'revocation_reason') THEN
        RAISE EXCEPTION '070: a 069 nao esta aplicada';
    END IF;
    IF to_regclass('public.client_automation_grants') IS NOT NULL
       OR EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'public.identity_invitations'::regclass AND attname = 'automation_id')
       OR EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'public.access_events'::regclass AND attname = 'automation_grant_id') THEN
        RAISE EXCEPTION '070: objetos da 070 ja existem';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.access_events'::regclass
                    AND conname = 'access_events_event_type_check' AND pg_get_constraintdef(oid) LIKE '%<= 14%') THEN
        RAISE EXCEPTION '070: access_events_event_type_check divergente da 024';
    END IF;
END;
$$;

-- === 2. Autoridade do convite de cliente (consulta do estado atual, SECURITY INVOKER) ===
CREATE FUNCTION public.access_can_invite_client(p_org UUID, p_membership UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT public.access_org_permission(p_org, p_membership, 'org.manage')
       AND public.access_org_permission(p_org, p_membership, 'external_access.manage')
$$;

-- maquina elegivel para restringir uma concessao: ativa e autorizada (ativa) no ambiente da automacao
CREATE FUNCTION public.access_worker_in_workspace(p_org UUID, p_worker UUID, p_workspace UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT EXISTS (SELECT 1 FROM public.worker_workspace_assignments x
                     JOIN public.workers k ON k.organization_id = x.organization_id AND k.worker_id = x.worker_id AND k.status = 1
                    WHERE x.organization_id = p_org AND x.worker_id = p_worker AND x.workspace_id = p_workspace AND x.status = 1)
$$;

-- automacao compartilhavel: ativa, num ambiente ativo da organizacao
CREATE FUNCTION public.access_automation_shareable(p_org UUID, p_automation UUID) RETURNS UUID
LANGUAGE sql STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT a.workspace_id FROM public.automations a
      JOIN public.workspaces w ON w.organization_id = a.organization_id AND w.workspace_id = a.workspace_id AND w.status = 1
     WHERE a.organization_id = p_org AND a.automation_id = p_automation AND a.status = 1
$$;

-- === 3. Concessao por automacao ===
CREATE TABLE public.client_automation_grants (
    grant_id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id            UUID NOT NULL,
    client_id                  UUID NOT NULL,
    client_membership_id       UUID NOT NULL,
    workspace_id               UUID NOT NULL,
    automation_id              UUID NOT NULL,
    worker_id                  UUID,
    can_read_logs              BOOLEAN NOT NULL,
    can_download_results       BOOLEAN NOT NULL,
    status                     SMALLINT NOT NULL CHECK (status IN (1, 2, 4)),
    basis                      SMALLINT NOT NULL CHECK (basis IN (1, 2)),
    invitation_id              UUID REFERENCES public.identity_invitations (invitation_id) ON DELETE RESTRICT,
    requested_by_membership_id UUID NOT NULL,
    approved_by_membership_id  UUID,
    approved_at                TIMESTAMPTZ,
    replaces_grant_id          UUID REFERENCES public.client_automation_grants (grant_id) ON DELETE RESTRICT,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    revoked_at                 TIMESTAMPTZ,
    revoked_by_membership_id   UUID,
    revoke_reason              SMALLINT CHECK (revoke_reason IN (1, 2, 3, 4)),
    UNIQUE (organization_id, grant_id),
    FOREIGN KEY (organization_id, client_id, client_membership_id)
        REFERENCES public.client_memberships (organization_id, client_id, client_membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, workspace_id, automation_id)
        REFERENCES public.automations (organization_id, workspace_id, automation_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, worker_id) REFERENCES public.workers (organization_id, worker_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, requested_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, approved_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, revoked_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK ((approved_at IS NULL) = (approved_by_membership_id IS NULL)),
    CHECK (status <> 2 OR approved_at IS NOT NULL),
    CHECK ((status = 4) = (revoked_at IS NOT NULL)),
    CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL)),
    CHECK (revoked_by_membership_id IS NULL OR revoked_at IS NOT NULL),
    CHECK ((basis = 2) = (invitation_id IS NOT NULL)),
    CHECK (basis = 1 OR (approved_by_membership_id = requested_by_membership_id AND replaces_grant_id IS NULL)),
    CHECK (basis = 2 OR approved_by_membership_id IS NULL OR approved_by_membership_id <> requested_by_membership_id),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

COMMENT ON TABLE public.client_automation_grants IS
    'Concessao de acompanhamento de UMA automacao a um vinculo de cliente (070), lida pelo Portal a cada consulta. Nao da '
    'acesso ao ambiente, as outras automacoes nem as outras atividades da maquina. Sem permissao de execucao, publicacao ou administracao.';
COMMENT ON COLUMN public.client_automation_grants.status IS '1=PENDENTE (sem acesso) 2=ATIVA 4=REVOGADA (congelada)';
COMMENT ON COLUMN public.client_automation_grants.basis IS
    '1=SEGUNDA_PESSOA (D-GRANT-1: aprovada por pessoa diferente de quem pediu) 2=CONVITE_DE_CLIENTE (excecao de 21/11/2026: aplicada no aceite do convite, aprovada por quem convidou)';
COMMENT ON COLUMN public.client_automation_grants.worker_id IS 'maquina a que o acompanhamento se restringe; nulo = todas as maquinas desta automacao';
COMMENT ON COLUMN public.client_automation_grants.revoke_reason IS
    '1=REVOGADA por uma pessoa 2=SUBSTITUIDA por outra aprovada 3=VINCULO_RETIRADO 4=PEDIDO_CANCELADO (pendente que nao chegou a valer)';

-- no maximo uma ATIVA e uma PENDENTE por (vinculo, automacao, maquina); a pendente pode ampliar a ativa e a substitui na aprovacao
CREATE UNIQUE INDEX ux_client_automation_grants_active ON public.client_automation_grants
    (client_membership_id, automation_id, coalesce(worker_id, '00000000-0000-0000-0000-000000000000'::uuid)) WHERE status = 2;
CREATE UNIQUE INDEX ux_client_automation_grants_pending ON public.client_automation_grants
    (client_membership_id, automation_id, coalesce(worker_id, '00000000-0000-0000-0000-000000000000'::uuid)) WHERE status = 1;
CREATE UNIQUE INDEX ux_client_automation_grants_invitation ON public.client_automation_grants (invitation_id) WHERE invitation_id IS NOT NULL;
CREATE UNIQUE INDEX ux_client_automation_grants_replaces ON public.client_automation_grants (replaces_grant_id) WHERE replaces_grant_id IS NOT NULL;
CREATE INDEX ix_client_automation_grants_org ON public.client_automation_grants (organization_id, created_at, grant_id);
CREATE INDEX ix_client_automation_grants_membership ON public.client_automation_grants (organization_id, client_membership_id, status);

CREATE FUNCTION public.client_automation_grant_guard() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_super BOOLEAN := coalesce((SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.rolname = current_user), false);
    i RECORD;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.revoked_at IS NOT NULL OR NEW.status = 4 THEN
            RAISE EXCEPTION 'concessao nasce pendente ou ativa' USING ERRCODE = '23514';
        END IF;
        IF v_super THEN
            RETURN NEW;
        END IF;
        -- escopo vigente: cliente e vinculo ativos, automacao ativa num ambiente ativo, maquina ativa e do ambiente
        IF NOT EXISTS (SELECT 1 FROM public.client_memberships m JOIN public.clients c ON c.organization_id = m.organization_id AND c.client_id = m.client_id
                        WHERE m.organization_id = NEW.organization_id AND m.client_membership_id = NEW.client_membership_id
                          AND m.client_id = NEW.client_id AND m.status = 2 AND c.status = 1)
           OR public.access_automation_shareable(NEW.organization_id, NEW.automation_id) IS DISTINCT FROM NEW.workspace_id
           OR (NEW.worker_id IS NOT NULL AND NOT public.access_worker_in_workspace(NEW.organization_id, NEW.worker_id, NEW.workspace_id)) THEN
            RAISE EXCEPTION 'escopo da concessao indisponivel' USING ERRCODE = '23514';
        END IF;
        IF NEW.basis = 1 THEN
            -- D-GRANT-1: nasce PENDENTE, pedida por quem tem external_access.manage; a aprovacao e de outra pessoa
            IF NEW.status <> 1 OR NEW.approved_at IS NOT NULL
               OR NOT public.access_org_permission(NEW.organization_id, NEW.requested_by_membership_id, 'external_access.manage') THEN
                RAISE EXCEPTION 'concessao por automacao nasce pendente, pedida por quem tem a permissao' USING ERRCODE = '42501';
            END IF;
            IF NEW.replaces_grant_id IS NOT NULL AND NOT EXISTS (
                    SELECT 1 FROM public.client_automation_grants g WHERE g.organization_id = NEW.organization_id AND g.grant_id = NEW.replaces_grant_id
                       AND g.status = 2 AND g.client_membership_id = NEW.client_membership_id AND g.automation_id = NEW.automation_id
                       AND g.worker_id IS NOT DISTINCT FROM NEW.worker_id) THEN
                RAISE EXCEPTION 'substituicao so de concessao ativa do mesmo escopo' USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END IF;
        -- base 2 (excecao): so a funcao de aceite, a partir do convite aceito NESTA transacao, com exatamente o escopo dele
        IF current_user <> 'nekkora_identity_owner' OR NEW.status <> 2 THEN
            RAISE EXCEPTION 'concessao do convite de cliente so pelo aceite' USING ERRCODE = '42501';
        END IF;
        SELECT x.client_id, x.automation_id, x.worker_id, x.can_read_logs, x.can_download_results, x.issued_by_membership_id, x.accepted_at,
               x.accepted_user_id, x.invited_account_type, x.organization_id
          INTO i FROM public.identity_invitations x WHERE x.invitation_id = NEW.invitation_id;
        IF i.invited_account_type IS DISTINCT FROM 2 OR i.organization_id <> NEW.organization_id OR i.accepted_at IS NULL
           OR i.accepted_at <> NEW.approved_at
           OR NOT EXISTS (SELECT 1 FROM public.client_memberships m WHERE m.organization_id = NEW.organization_id
                           AND m.client_membership_id = NEW.client_membership_id AND m.user_id = i.accepted_user_id)
           OR i.client_id <> NEW.client_id OR i.automation_id <> NEW.automation_id OR i.worker_id IS DISTINCT FROM NEW.worker_id
           OR i.can_read_logs <> NEW.can_read_logs OR i.can_download_results <> NEW.can_download_results
           OR i.issued_by_membership_id <> NEW.requested_by_membership_id
           OR NOT public.access_can_invite_client(NEW.organization_id, i.issued_by_membership_id) THEN
            RAISE EXCEPTION 'concessao diverge do convite aceito' USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END IF;

    -- UPDATE
    IF OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'concessao revogada e imutavel (grant_id=%)', OLD.grant_id;
    END IF;
    IF NEW.grant_id <> OLD.grant_id OR NEW.organization_id <> OLD.organization_id OR NEW.client_id <> OLD.client_id
       OR NEW.client_membership_id <> OLD.client_membership_id OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.automation_id <> OLD.automation_id OR NEW.worker_id IS DISTINCT FROM OLD.worker_id OR NEW.basis <> OLD.basis
       OR NEW.invitation_id IS DISTINCT FROM OLD.invitation_id OR NEW.requested_by_membership_id <> OLD.requested_by_membership_id
       OR NEW.replaces_grant_id IS DISTINCT FROM OLD.replaces_grant_id OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'origem da concessao e imutavel (grant_id=%)', OLD.grant_id;
    END IF;
    -- permissoes so diminuem: ampliar e outra concessao, com a aprovacao de outra pessoa
    IF (NEW.can_read_logs AND NOT OLD.can_read_logs) OR (NEW.can_download_results AND NOT OLD.can_download_results) THEN
        RAISE EXCEPTION 'ampliacao de permissao exige nova concessao aprovada (grant_id=%)', OLD.grant_id USING ERRCODE = '42501';
    END IF;
    IF (OLD.approved_at IS NOT NULL) AND (NEW.approved_at IS DISTINCT FROM OLD.approved_at OR NEW.approved_by_membership_id IS DISTINCT FROM OLD.approved_by_membership_id) THEN
        RAISE EXCEPTION 'aprovacao registrada e imutavel (grant_id=%)', OLD.grant_id;
    END IF;
    IF NOT (NEW.status = OLD.status OR (OLD.status = 1 AND NEW.status IN (2, 4)) OR (OLD.status = 2 AND NEW.status = 4)) THEN
        RAISE EXCEPTION 'transicao de concessao invalida (% -> %)', OLD.status, NEW.status USING ERRCODE = '23514';
    END IF;
    IF OLD.status = 1 AND (NEW.can_read_logs <> OLD.can_read_logs OR NEW.can_download_results <> OLD.can_download_results) THEN
        RAISE EXCEPTION 'pedido pendente nao muda: cancele e peca de novo (grant_id=%)', OLD.grant_id USING ERRCODE = '23514';
    END IF;
    IF v_super THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 1 AND NEW.status = 2 THEN
        -- aprovacao: segunda pessoa com external_access.manage; escopo ainda vigente
        IF NEW.approved_by_membership_id IS NULL OR NEW.approved_by_membership_id = OLD.requested_by_membership_id
           OR NOT public.access_org_permission(NEW.organization_id, NEW.approved_by_membership_id, 'external_access.manage') THEN
            RAISE EXCEPTION 'aprovacao exige outra pessoa com a permissao' USING ERRCODE = '42501';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM public.client_memberships m JOIN public.clients c ON c.organization_id = m.organization_id AND c.client_id = m.client_id
                        WHERE m.organization_id = NEW.organization_id AND m.client_membership_id = NEW.client_membership_id AND m.status = 2 AND c.status = 1)
           OR public.access_automation_shareable(NEW.organization_id, NEW.automation_id) IS DISTINCT FROM NEW.workspace_id
           OR (NEW.worker_id IS NOT NULL AND NOT public.access_worker_in_workspace(NEW.organization_id, NEW.worker_id, NEW.workspace_id)) THEN
            RAISE EXCEPTION 'escopo da concessao indisponivel' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.status = 4 AND OLD.status <> 4 THEN
        IF NEW.revoked_by_membership_id IS NULL
           OR NOT (public.access_org_permission(NEW.organization_id, NEW.revoked_by_membership_id, 'external_access.manage')
                   OR (NEW.revoke_reason = 3 AND public.access_org_permission(NEW.organization_id, NEW.revoked_by_membership_id, 'org.manage'))) THEN
            RAISE EXCEPTION 'revogacao de concessao sem autoridade' USING ERRCODE = '42501';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_client_automation_grants_guard BEFORE INSERT OR UPDATE ON public.client_automation_grants
    FOR EACH ROW EXECUTE FUNCTION public.client_automation_grant_guard();
CREATE TRIGGER trg_client_automation_grants_set_updated_at BEFORE UPDATE ON public.client_automation_grants
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_client_automation_grants_prevent_delete BEFORE DELETE ON public.client_automation_grants
    FOR EACH ROW EXECUTE FUNCTION prevent_delete();
CREATE TRIGGER trg_client_automation_grants_prevent_truncate BEFORE TRUNCATE ON public.client_automation_grants
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_truncate();

ALTER TABLE public.client_automation_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.client_automation_grants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_request_context ON public.client_automation_grants AS PERMISSIVE FOR ALL TO nekkora_platform
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid);
CREATE POLICY identity_function_context ON public.client_automation_grants AS PERMISSIVE FOR ALL TO nekkora_identity_owner
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.fn_organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(pg_catalog.current_setting('nekkora.fn_organization_id', true), '')::uuid);
-- a Platform pede, aprova, reduz e revoga; nunca cria a concessao da excecao (o gatilho recusa) nem apaga
GRANT SELECT, INSERT ON public.client_automation_grants TO nekkora_platform;
GRANT UPDATE (status, approved_by_membership_id, approved_at, can_read_logs, can_download_results, revoked_at, revoked_by_membership_id, revoke_reason)
    ON public.client_automation_grants TO nekkora_platform;

-- === 4. Convite de cliente ===
ALTER TABLE public.identity_invitations
    ADD COLUMN automation_id        UUID REFERENCES public.automations (automation_id) ON DELETE RESTRICT,
    ADD COLUMN worker_id            UUID,
    ADD COLUMN can_read_logs        BOOLEAN,
    ADD COLUMN can_download_results BOOLEAN,
    ADD CONSTRAINT identity_invitations_worker_fkey FOREIGN KEY (organization_id, worker_id)
        REFERENCES public.workers (organization_id, worker_id) ON DELETE RESTRICT,
    -- escopo do acompanhamento: so no convite de cliente; maquina so com automacao; as duas permissoes sempre explicitas
    ADD CONSTRAINT identity_invitations_client_scope_check CHECK (
        (automation_id IS NULL OR invited_account_type = 2)
        AND (worker_id IS NULL OR automation_id IS NOT NULL)
        AND ((automation_id IS NULL) = (can_read_logs IS NULL))
        AND ((automation_id IS NULL) = (can_download_results IS NULL)));
COMMENT ON COLUMN public.identity_invitations.automation_id IS '070: automacao que o convite de cliente compartilha (o ambiente e o dela)';
COMMENT ON COLUMN public.identity_invitations.worker_id IS '070: maquina a que o acompanhamento se restringe; nulo = todas as maquinas desta automacao';

CREATE OR REPLACE FUNCTION public.access_invitation_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_workspace UUID;
BEGIN
    IF coalesce((SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.rolname = current_user), false) THEN
        RETURN NEW;
    END IF;
    IF NEW.accepted_at IS NOT NULL OR NEW.revoked_at IS NOT NULL OR NEW.accepted_user_id IS NOT NULL OR NEW.accepted_identity_id IS NOT NULL THEN
        RAISE EXCEPTION 'convite nasce aberto' USING ERRCODE = '23514';
    END IF;
    IF NEW.expires_at > NEW.created_at + interval '7 days' THEN
        RAISE EXCEPTION 'prazo do convite acima de 7 dias' USING ERRCODE = '23514';
    END IF;
    IF NEW.issued_by_membership_id IS NULL
       OR NOT EXISTS (SELECT 1 FROM public.organization_memberships m WHERE m.organization_id = NEW.organization_id
                       AND m.membership_id = NEW.issued_by_membership_id AND m.user_id = NEW.issued_by) THEN
        RAISE EXCEPTION 'emissor sem autoridade para o convite' USING ERRCODE = '42501';
    END IF;
    IF NEW.invited_account_type = 1 THEN
        IF NOT public.access_can_invite(NEW.organization_id, NEW.issued_by_membership_id, NEW.role_id, NEW.workspace_id) THEN
            RAISE EXCEPTION 'emissor sem autoridade para o convite' USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END IF;
    -- 070 (D-EXT fechada): convite de Cliente para acompanhar UMA automacao, emitido por administradora
    IF NEW.automation_id IS NULL THEN
        RAISE EXCEPTION 'convite de cliente exige a automacao compartilhada' USING ERRCODE = '23514';
    END IF;
    IF NOT public.access_can_invite_client(NEW.organization_id, NEW.issued_by_membership_id) THEN
        RAISE EXCEPTION 'emissor sem autoridade para o convite de cliente' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.clients c WHERE c.organization_id = NEW.organization_id AND c.client_id = NEW.client_id AND c.status = 1) THEN
        RAISE EXCEPTION 'cliente indisponivel' USING ERRCODE = '23514';
    END IF;
    v_workspace := public.access_automation_shareable(NEW.organization_id, NEW.automation_id);
    IF v_workspace IS NULL OR (NEW.worker_id IS NOT NULL AND NOT public.access_worker_in_workspace(NEW.organization_id, NEW.worker_id, v_workspace)) THEN
        RAISE EXCEPTION 'escopo do convite indisponivel' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM public.users u WHERE u.organization_id = NEW.organization_id AND u.email_normalized = NEW.email_normalized
                AND u.account_type = 1) THEN
        RAISE EXCEPTION 'destinataria e conta interna da organizacao' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- corpo da 024 com as colunas da 070 na origem imutavel e a autoridade do convite de cliente no motivo 4
CREATE OR REPLACE FUNCTION public.access_invitation_on_update() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF OLD.accepted_at IS NOT NULL OR OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'convite aceito ou revogado e imutavel (invitation_id=%)', OLD.invitation_id;
    END IF;
    IF NEW.invitation_id <> OLD.invitation_id OR NEW.organization_id <> OLD.organization_id
       OR NEW.invited_account_type <> OLD.invited_account_type OR NEW.client_id IS DISTINCT FROM OLD.client_id
       OR NEW.email_normalized <> OLD.email_normalized OR NEW.issued_by <> OLD.issued_by OR NEW.token_hash <> OLD.token_hash
       OR NEW.created_at <> OLD.created_at OR NEW.expires_at <> OLD.expires_at
       OR NEW.issued_by_membership_id IS DISTINCT FROM OLD.issued_by_membership_id OR NEW.role_id IS DISTINCT FROM OLD.role_id
       OR NEW.role_scope IS DISTINCT FROM OLD.role_scope OR NEW.workspace_id IS DISTINCT FROM OLD.workspace_id
       OR NEW.replaces_invitation_id IS DISTINCT FROM OLD.replaces_invitation_id
       OR NEW.automation_id IS DISTINCT FROM OLD.automation_id OR NEW.worker_id IS DISTINCT FROM OLD.worker_id
       OR NEW.can_read_logs IS DISTINCT FROM OLD.can_read_logs OR NEW.can_download_results IS DISTINCT FROM OLD.can_download_results THEN
        RAISE EXCEPTION 'origem do convite e imutavel (invitation_id=%)', OLD.invitation_id;
    END IF;
    IF NEW.accepted_at IS NULL AND NEW.revoked_at IS NULL THEN
        RAISE EXCEPTION 'convite aberto so muda para aceito ou revogado (invitation_id=%)', OLD.invitation_id;
    END IF;
    IF coalesce((SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.rolname = current_user), false) THEN
        RETURN NEW;
    END IF;
    IF NEW.accepted_at IS NOT NULL THEN
        IF current_user <> 'nekkora_identity_owner' OR NEW.accepted_identity_id IS NULL THEN
            RAISE EXCEPTION 'aceite de convite so pela funcao de aceite' USING ERRCODE = '42501';
        END IF;
    ELSIF NEW.revoke_reason = 4 THEN
        IF NEW.revoked_by_membership_id IS NOT NULL
           OR (NEW.invited_account_type = 1 AND public.access_can_invite(NEW.organization_id, NEW.issued_by_membership_id, NEW.role_id, NEW.workspace_id))
           OR (NEW.invited_account_type = 2 AND public.access_can_invite_client(NEW.organization_id, NEW.issued_by_membership_id)) THEN
            RAISE EXCEPTION 'emissor do convite ainda tem autoridade' USING ERRCODE = '42501';
        END IF;
    ELSIF NEW.revoked_by_membership_id IS NULL OR NEW.revoke_reason IS NULL
          OR NOT public.access_org_permission(NEW.organization_id, NEW.revoked_by_membership_id, 'org.manage') THEN
        RAISE EXCEPTION 'revogacao de convite sem autoridade' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

-- === 5. Auditoria: tipos 15..24 e colunas de escopo ===
ALTER TABLE public.access_events
    ADD COLUMN client_id            UUID,
    ADD COLUMN client_membership_id UUID,
    ADD COLUMN automation_id        UUID REFERENCES public.automations (automation_id) ON DELETE RESTRICT,
    ADD COLUMN worker_id            UUID,
    ADD COLUMN automation_grant_id  UUID,
    ADD COLUMN can_read_logs        BOOLEAN,
    ADD COLUMN can_download_results BOOLEAN,
    ADD COLUMN grant_exception      BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT access_events_client_fkey FOREIGN KEY (organization_id, client_id)
        REFERENCES public.clients (organization_id, client_id) ON DELETE RESTRICT,
    ADD CONSTRAINT access_events_client_membership_fkey FOREIGN KEY (client_membership_id)
        REFERENCES public.client_memberships (client_membership_id) ON DELETE RESTRICT,
    ADD CONSTRAINT access_events_worker_fkey FOREIGN KEY (organization_id, worker_id)
        REFERENCES public.workers (organization_id, worker_id) ON DELETE RESTRICT,
    ADD CONSTRAINT access_events_automation_grant_fkey FOREIGN KEY (organization_id, automation_grant_id)
        REFERENCES public.client_automation_grants (organization_id, grant_id) ON DELETE RESTRICT;
ALTER TABLE public.access_events DROP CONSTRAINT access_events_event_type_check;
ALTER TABLE public.access_events
    ADD CONSTRAINT access_events_event_type_check CHECK (event_type BETWEEN 1 AND 24),
    ADD CONSTRAINT access_events_automation_grant_check CHECK (event_type < 15 OR event_type > 19 OR automation_grant_id IS NOT NULL),
    ADD CONSTRAINT access_events_client_membership_check CHECK (event_type < 20 OR event_type > 22 OR client_membership_id IS NOT NULL),
    ADD CONSTRAINT access_events_client_check CHECK (event_type < 23 OR client_id IS NOT NULL),
    ADD CONSTRAINT access_events_exception_check CHECK (NOT grant_exception OR event_type IN (1, 3, 17));
COMMENT ON COLUMN public.access_events.event_type IS
    '1..13: ver cabecalho da 023; 14=CONCESSAO_RENOVACAO_SOLICITADA (024); 15..24: ver cabecalho da 070';
COMMENT ON COLUMN public.access_events.grant_exception IS
    '070: o ato usou a excecao a D-GRANT-1 do convite de Cliente (emissao, reenvio, concessao aplicada no aceite)';

GRANT SELECT (automation_id, worker_id, can_read_logs, can_download_results, client_id, invited_account_type, accepted_user_id, issued_by)
    ON public.identity_invitations TO nekkora_identity_owner;

-- === 6. Aceite do convite de cliente (dono das funcoes de identidade) ===
-- Resultado: OK_CLIENTE | INVALIDO | MEMBRO_INTERNO | CLIENTE_DIFERENTE | CONTA_EXISTENTE | CONTA_INDISPONIVEL | CONCESSAO_EXISTENTE |
-- ESCOPO_INDISPONIVEL (os seis ultimos so depois de provada a destinataria). Nada e aplicado fora do OK.
CREATE FUNCTION public.identity_accept_client_invitation_impl(p_session_token TEXT, p_token TEXT, p_organization_id UUID)
RETURNS TEXT
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_hash TEXT := public.identity_token_hash(p_token);
    s RECORD;
    i RECORD;
    c RECORD;
    v_linked RECORD;
    v_cm RECORD;
    v_same_email UUID;
    v_workspace UUID;
    v_t TIMESTAMPTZ;
    v_user UUID;
    v_cmid UUID;
    v_grant UUID;
    v_n INTEGER;
BEGIN
    PERFORM public.identity_require_read_committed();
    IF v_hash IS NULL OR p_organization_id IS NULL THEN
        RETURN 'INVALIDO';
    END IF;
    SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
    IF s.o_identity_id IS NULL THEN
        RETURN 'INVALIDO';
    END IF;
    SELECT x.email, x.email_normalized, x.display_name, x.email_verified_at INTO i FROM public.identities x
     WHERE x.identity_id = s.o_identity_id AND x.status = 2 AND x.email_verified_at IS NOT NULL FOR UPDATE;
    IF i.email IS NULL THEN
        RETURN 'INVALIDO';
    END IF;
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', '', true);
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    PERFORM 1 FROM public.organizations o WHERE o.organization_id = p_organization_id AND o.status = 1 FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'INVALIDO';
    END IF;
    SELECT x.invitation_id, x.email_normalized, x.expires_at, x.issued_by_membership_id, x.issued_by, x.client_id, x.automation_id,
           x.worker_id, x.can_read_logs, x.can_download_results
      INTO c
      FROM public.identity_invitations x
     WHERE x.organization_id = p_organization_id AND x.token_hash = v_hash AND x.invited_account_type = 2
       AND x.accepted_at IS NULL AND x.revoked_at IS NULL
       FOR UPDATE;
    IF c.invitation_id IS NULL THEN
        RETURN 'INVALIDO';
    END IF;
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', s.o_identity_id::text, true);
    SELECT u.user_id, u.status, u.account_type INTO v_linked
      FROM public.identity_users iu JOIN public.users u ON u.user_id = iu.user_id
     WHERE iu.identity_id = s.o_identity_id AND u.organization_id = p_organization_id
       FOR UPDATE OF u;
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', '', true);
    SELECT u.user_id INTO v_same_email FROM public.users u
     WHERE u.organization_id = p_organization_id AND u.email_normalized = c.email_normalized FOR UPDATE;
    IF v_linked.user_id IS NOT NULL AND v_linked.account_type = 2 THEN
        SELECT m.client_membership_id, m.client_id, m.status INTO v_cm FROM public.client_memberships m
         WHERE m.organization_id = p_organization_id AND m.user_id = v_linked.user_id FOR UPDATE;
    END IF;

    v_t := pg_catalog.clock_timestamp();
    IF NOT public.identity_session_proof(s.o_session_id, v_t, 'step_up')
       OR c.expires_at <= v_t
       OR c.email_normalized <> i.email_normalized
       OR c.issued_by_membership_id IS NULL
       OR NOT public.access_can_invite_client(p_organization_id, c.issued_by_membership_id) THEN
        RETURN 'INVALIDO';
    END IF;
    IF v_linked.user_id IS NOT NULL AND v_linked.account_type = 1 THEN
        RETURN 'MEMBRO_INTERNO';   -- nao reduz nem combina privilegios existentes
    END IF;
    -- escopo reconferido agora: cliente ativo, automacao ativa num ambiente ativo, maquina ativa e do ambiente
    v_workspace := public.access_automation_shareable(p_organization_id, c.automation_id);
    IF NOT EXISTS (SELECT 1 FROM public.clients x WHERE x.organization_id = p_organization_id AND x.client_id = c.client_id AND x.status = 1)
       OR v_workspace IS NULL
       OR (c.worker_id IS NOT NULL AND NOT public.access_worker_in_workspace(p_organization_id, c.worker_id, v_workspace)) THEN
        RETURN 'ESCOPO_INDISPONIVEL';
    END IF;

    IF v_linked.user_id IS NOT NULL THEN
        IF v_cm.client_membership_id IS NULL OR v_linked.status <> 2 THEN
            RETURN 'CONTA_INDISPONIVEL';
        END IF;
        IF v_cm.client_id <> c.client_id THEN
            RETURN 'CLIENTE_DIFERENTE';
        END IF;
        IF v_cm.status = 4 THEN
            UPDATE public.client_memberships SET status = 2 WHERE client_membership_id = v_cm.client_membership_id AND status = 4;
        ELSIF v_cm.status <> 2 THEN
            RETURN 'CONTA_INDISPONIVEL';   -- vinculo suspenso continua suspenso: quem reativa e a organizacao
        END IF;
        IF EXISTS (SELECT 1 FROM public.client_automation_grants g WHERE g.organization_id = p_organization_id
                    AND g.client_membership_id = v_cm.client_membership_id AND g.automation_id = c.automation_id
                    AND g.worker_id IS NOT DISTINCT FROM c.worker_id AND g.status IN (1, 2)) THEN
            RAISE EXCEPTION USING ERRCODE = 'NK409', MESSAGE = 'CONCESSAO_EXISTENTE';
        END IF;
        v_user := v_linked.user_id;
        v_cmid := v_cm.client_membership_id;
    ELSIF v_same_email IS NOT NULL THEN
        RETURN 'CONTA_EXISTENTE';   -- conta antiga da organizacao: nunca ligada por coincidencia de e-mail
    ELSE
        INSERT INTO public.users (organization_id, account_type, display_name, email, email_verified_at, status)
        VALUES (p_organization_id, 2, i.display_name, i.email, i.email_verified_at, 2)
        RETURNING user_id INTO v_user;
        INSERT INTO public.client_memberships (organization_id, client_id, user_id, account_type, status)
        VALUES (p_organization_id, c.client_id, v_user, 2, 2)
        RETURNING client_membership_id INTO v_cmid;
        INSERT INTO public.identity_users (identity_id, user_id, link_channel) VALUES (s.o_identity_id, v_user, 'convite');
    END IF;

    UPDATE public.identity_invitations SET accepted_at = v_t, accepted_user_id = v_user, accepted_identity_id = s.o_identity_id
     WHERE invitation_id = c.invitation_id AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > v_t;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    IF v_n <> 1 THEN
        RAISE EXCEPTION 'convite mudou sob o lock (invitation_id=%)', c.invitation_id;
    END IF;
    INSERT INTO public.client_automation_grants (organization_id, client_id, client_membership_id, workspace_id, automation_id, worker_id,
                                                 can_read_logs, can_download_results, status, basis, invitation_id, requested_by_membership_id,
                                                 approved_by_membership_id, approved_at)
    VALUES (p_organization_id, c.client_id, v_cmid, v_workspace, c.automation_id, c.worker_id, c.can_read_logs, c.can_download_results,
            2, 2, c.invitation_id, c.issued_by_membership_id, c.issued_by_membership_id, v_t)
    RETURNING grant_id INTO v_grant;
    INSERT INTO public.access_events (organization_id, event_type, actor_identity_id, target_user_id, invitation_id, client_id,
                                      client_membership_id, automation_id, worker_id, automation_grant_id, can_read_logs, can_download_results,
                                      workspace_id, correlation_id, created_at)
    VALUES (p_organization_id, 4, s.o_identity_id, v_user, c.invitation_id, c.client_id, v_cmid, c.automation_id, c.worker_id, v_grant,
            c.can_read_logs, c.can_download_results, v_workspace, pg_catalog.gen_random_uuid(), v_t);
    INSERT INTO public.access_events (organization_id, event_type, actor_membership_id, actor_user_id, target_user_id, invitation_id, client_id,
                                      client_membership_id, automation_id, worker_id, automation_grant_id, can_read_logs, can_download_results,
                                      workspace_id, grant_exception, correlation_id, created_at)
    VALUES (p_organization_id, 17, c.issued_by_membership_id, c.issued_by, v_user, c.invitation_id, c.client_id, v_cmid, c.automation_id,
            c.worker_id, v_grant, c.can_read_logs, c.can_download_results, v_workspace, true, pg_catalog.gen_random_uuid(), v_t);
    RETURN 'OK_CLIENTE';
END;
$$;

-- o envelope da 023: primeiro o convite interno (corpo da 023 intacto); sem convite interno com o token, o de cliente
CREATE OR REPLACE FUNCTION public.identity_accept_invitation(p_session_token TEXT, p_token TEXT, p_organization_id UUID)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    v_r TEXT;
BEGIN
    BEGIN
        v_r := public.identity_accept_invitation_impl(p_session_token, p_token, p_organization_id);
        IF v_r = 'INVALIDO' THEN
            BEGIN
                v_r := public.identity_accept_client_invitation_impl(p_session_token, p_token, p_organization_id);
            EXCEPTION WHEN SQLSTATE 'NK409' THEN
                v_r := 'CONCESSAO_EXISTENTE';   -- a readmissao do vinculo (se houve) e desfeita junto: nada aplicado
            END;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
    RETURN v_r;
END;
$$;

ALTER FUNCTION public.identity_accept_client_invitation_impl(TEXT, TEXT, UUID) OWNER TO nekkora_identity_owner;
REVOKE ALL ON FUNCTION public.identity_accept_client_invitation_impl(TEXT, TEXT, UUID) FROM PUBLIC;

-- privilegios do aceite de cliente (dono das funcoes de identidade), sob a RLS do contexto da funcao
GRANT SELECT (client_id, organization_id, status) ON public.clients TO nekkora_identity_owner;
GRANT SELECT (client_membership_id, client_id), INSERT (organization_id, client_id, user_id, account_type, status), UPDATE (status)
    ON public.client_memberships TO nekkora_identity_owner;
GRANT SELECT (automation_id, workspace_id, status) ON public.automations TO nekkora_identity_owner;
GRANT SELECT (organization_id, workspace_id, status) ON public.workspaces TO nekkora_identity_owner;
GRANT SELECT (organization_id, worker_id, status) ON public.workers TO nekkora_identity_owner;
GRANT SELECT (organization_id, worker_id, workspace_id, status) ON public.worker_workspace_assignments TO nekkora_identity_owner;
GRANT SELECT, INSERT ON public.client_automation_grants TO nekkora_identity_owner;
DO $$
DECLARE
    t TEXT;
    ctx_fn CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.fn_organization_id'', true), '''')::uuid';
BEGIN
    FOREACH t IN ARRAY ARRAY['clients', 'client_memberships', 'automations', 'workspaces', 'workers', 'worker_workspace_assignments'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                       WHERE n.nspname = 'public' AND c.relname = t AND c.relrowsecurity AND c.relforcerowsecurity) THEN
            RAISE EXCEPTION '070: tabela % sem RLS ENABLE+FORCE', t;
        END IF;
        EXECUTE format('CREATE POLICY identity_client_accept ON public.%I AS PERMISSIVE FOR ALL TO nekkora_identity_owner USING (%s) WITH CHECK (%s)',
                       t, ctx_fn, ctx_fn);
    END LOOP;
END;
$$;

-- === 7. Portal: area do cliente pela identidade (SECURITY DEFINER do nekkora_portal_owner) ===
-- Leituras do owner do portal: so colunas; RLS pela organizacao alegada (fn_organization_id) ou, nas tabelas da identidade e
-- na lista de organizacoes, pela identidade resolvida pela propria funcao (fn_identity_id).
GRANT EXECUTE ON FUNCTION public.identity_session_lookup(TEXT) TO nekkora_portal_owner;
GRANT SELECT (identity_id, status) ON public.identities TO nekkora_portal_owner;
GRANT SELECT (identity_id, user_id) ON public.identity_users TO nekkora_portal_owner;
GRANT SELECT (name) ON public.organizations TO nekkora_portal_owner;
GRANT SELECT (name) ON public.clients TO nekkora_portal_owner;
GRANT SELECT (status) ON public.automations TO nekkora_portal_owner;
GRANT SELECT (worker_id) ON public.executions TO nekkora_portal_owner;
GRANT SELECT (grant_id, organization_id, client_id, client_membership_id, workspace_id, automation_id, worker_id, can_read_logs,
              can_download_results, status) ON public.client_automation_grants TO nekkora_portal_owner;
GRANT SELECT (worker_id, organization_id, name, status) ON public.workers TO nekkora_portal_owner;
GRANT SELECT (agent_run_id, organization_id, workspace_id, automation_id, worker_id, registered_at, agent_started_at, agent_ended_at,
              supervisor_state, supervisor_code, platform_execution_id) ON public.agent_runs TO nekkora_portal_owner;
GRANT SELECT (run_request_id, organization_id, workspace_id, automation_id, worker_id, requested_at, state, agent_run_id, valid_until)
    ON public.run_requests TO nekkora_portal_owner;
GRANT SELECT (remote_execution_id, organization_id, workspace_id, automation_id, requested_at) ON public.remote_executions TO nekkora_portal_owner;
GRANT SELECT (attempt_id, organization_id, remote_execution_id, attempt_number, worker_id, state, offer_valid_until, started_at, ended_at)
    ON public.remote_attempts TO nekkora_portal_owner;
GRANT SELECT (organization_id, attempt_id, sequence, captured_at, channel, truncated, message, redacted_at)
    ON public.remote_attempt_log_lines TO nekkora_portal_owner;
GRANT SELECT (organization_id, attempt_id, lines, dropped_lines, closed_at) ON public.remote_attempt_log_closures TO nekkora_portal_owner;
GRANT SELECT (organization_id, attempt_id, result_number, file_name, size_bytes, sha256, received_at, removed_at)
    ON public.remote_attempt_results TO nekkora_portal_owner;
GRANT SELECT (organization_id, attempt_id, sent, skipped_by_limit, closed_at) ON public.remote_attempt_result_closures TO nekkora_portal_owner;

DO $$
DECLARE
    t TEXT;
    ctx_fn CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.fn_organization_id'', true), '''')::uuid';
    ctx_id CONSTANT TEXT := 'NULLIF(pg_catalog.current_setting(''nekkora.fn_identity_id'', true), '''')::uuid';
BEGIN
    FOREACH t IN ARRAY ARRAY['client_automation_grants', 'workers', 'agent_runs', 'run_requests', 'remote_executions', 'remote_attempts',
                             'remote_attempt_log_lines', 'remote_attempt_log_closures', 'remote_attempt_results', 'remote_attempt_result_closures'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                       WHERE n.nspname = 'public' AND c.relname = t AND c.relrowsecurity AND c.relforcerowsecurity) THEN
            RAISE EXCEPTION '070: tabela % sem RLS ENABLE+FORCE', t;
        END IF;
        EXECUTE format('CREATE POLICY portal_function_context ON public.%I AS PERMISSIVE FOR SELECT TO nekkora_portal_owner USING (%s)', t, ctx_fn);
    END LOOP;
    -- identidade: so a linha da identidade resolvida e as contas ligadas a ela
    EXECUTE format('CREATE POLICY portal_identity_context ON public.identities AS PERMISSIVE FOR SELECT TO nekkora_portal_owner USING (identity_id = %s)', ctx_id);
    EXECUTE format('CREATE POLICY portal_identity_context ON public.identity_users AS PERMISSIVE FOR SELECT TO nekkora_portal_owner USING (identity_id = %s)', ctx_id);
    -- lista de organizacoes em que a identidade e cliente (sem organizacao alegada)
    EXECUTE format('CREATE POLICY portal_identity_context ON public.users AS PERMISSIVE FOR SELECT TO nekkora_portal_owner '
                   'USING (account_type = 2 AND EXISTS (SELECT 1 FROM public.identity_users iu WHERE iu.user_id = users.user_id AND iu.identity_id = %s))', ctx_id);
    EXECUTE format('CREATE POLICY portal_identity_context ON public.client_memberships AS PERMISSIVE FOR SELECT TO nekkora_portal_owner '
                   'USING (EXISTS (SELECT 1 FROM public.identity_users iu WHERE iu.user_id = client_memberships.user_id AND iu.identity_id = %s))', ctx_id);
    EXECUTE format('CREATE POLICY portal_identity_context ON public.clients AS PERMISSIVE FOR SELECT TO nekkora_portal_owner '
                   'USING (EXISTS (SELECT 1 FROM public.client_memberships m JOIN public.identity_users iu ON iu.user_id = m.user_id '
                   'WHERE m.organization_id = clients.organization_id AND m.client_id = clients.client_id AND iu.identity_id = %s))', ctx_id);
    EXECUTE format('CREATE POLICY portal_identity_context ON public.organizations AS PERMISSIVE FOR SELECT TO nekkora_portal_owner '
                   'USING (EXISTS (SELECT 1 FROM public.users u JOIN public.identity_users iu ON iu.user_id = u.user_id '
                   'WHERE u.organization_id = organizations.organization_id AND u.account_type = 2 AND iu.identity_id = %s))', ctx_id);
    EXECUTE format('CREATE POLICY portal_identity_context ON public.client_automation_grants AS PERMISSIVE FOR SELECT TO nekkora_portal_owner '
                   'USING (EXISTS (SELECT 1 FROM public.client_memberships m JOIN public.identity_users iu ON iu.user_id = m.user_id '
                   'WHERE m.client_membership_id = client_automation_grants.client_membership_id AND iu.identity_id = %s))', ctx_id);
END;
$$;

-- Resolve a identidade pela sessao de identidade (o lookup da 029 ja aplica MFA, prazo e revogacao) e, se houver organizacao,
-- a conta de cliente ligada a ela ali. NK401: sessao invalida. NK403: sem vinculo de cliente vigente nesta organizacao.
CREATE FUNCTION public.portal_client_resolve(p_organization_id UUID, p_identity_token TEXT,
    OUT o_identity_id UUID, OUT o_user_id UUID, OUT o_client_membership_id UUID, OUT o_client_id UUID, OUT o_t TIMESTAMPTZ)
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF p_identity_token IS NULL OR pg_catalog.length(p_identity_token) > 200 THEN
        RAISE EXCEPTION USING ERRCODE = 'NK401', MESSAGE = 'sessao de identidade invalida';
    END IF;
    SELECT l.identity_id INTO o_identity_id FROM public.identity_session_lookup(p_identity_token) l;
    o_t := pg_catalog.clock_timestamp();
    IF o_identity_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'NK401', MESSAGE = 'sessao de identidade invalida';
    END IF;
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', o_identity_id::text, true);
    IF NOT EXISTS (SELECT 1 FROM public.identities i WHERE i.identity_id = o_identity_id AND i.status = 2) THEN
        RAISE EXCEPTION USING ERRCODE = 'NK401', MESSAGE = 'sessao de identidade invalida';
    END IF;
    IF p_organization_id IS NULL THEN
        RETURN;
    END IF;
    SELECT u.user_id, m.client_membership_id, m.client_id INTO o_user_id, o_client_membership_id, o_client_id
      FROM public.identity_users iu
      JOIN public.users u ON u.user_id = iu.user_id
      JOIN public.organizations o ON o.organization_id = u.organization_id
      JOIN public.client_memberships m ON m.user_id = u.user_id AND m.organization_id = u.organization_id
      JOIN public.clients c ON c.client_id = m.client_id AND c.organization_id = m.organization_id
     WHERE iu.identity_id = o_identity_id AND u.organization_id = p_organization_id
       AND u.account_type = 2 AND u.status = 2 AND u.email_verified_at IS NOT NULL
       AND o.status = 1 AND m.status = 2 AND c.status = 1;
    IF o_client_membership_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'NK403', MESSAGE = 'sem vinculo de cliente nesta organizacao';
    END IF;
    -- dali em diante, so a organizacao alegada (e ja conferida)
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', '', true);
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', p_organization_id::text, true);
END;
$$;

-- concessoes vigentes do vinculo (mesmo snapshot do comando que chama): ATIVA, automacao nao arquivada num ambiente ativo
CREATE FUNCTION public.portal_client_scope(p_organization_id UUID, p_client_membership_id UUID)
RETURNS TABLE (automation_id UUID, worker_id UUID, can_read_logs BOOLEAN, can_download_results BOOLEAN)
LANGUAGE sql
STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT g.automation_id, g.worker_id, g.can_read_logs, g.can_download_results
      FROM public.client_automation_grants g
      JOIN public.automations a ON a.organization_id = g.organization_id AND a.automation_id = g.automation_id
           AND a.workspace_id = g.workspace_id AND a.status IN (1, 2)
      JOIN public.workspaces w ON w.organization_id = g.organization_id AND w.workspace_id = g.workspace_id AND w.status = 1
     WHERE g.organization_id = p_organization_id AND g.client_membership_id = p_client_membership_id AND g.status = 2
$$;

-- as execucoes das quatro origens (a mesma regra do acompanhamento da Platform, ActivityReads/ExecutionSummaryReads), so das
-- automacoes e maquinas concedidas. A remota vale pela maquina da ULTIMA tentativa.
CREATE FUNCTION public.portal_client_executions(p_organization_id UUID, p_client_membership_id UUID)
RETURNS TABLE (kind TEXT, id UUID, automation_id UUID, worker_id UUID, requested_at TIMESTAMPTZ, started_at TIMESTAMPTZ,
               ended_at TIMESTAMPTZ, outcome TEXT)
LANGUAGE sql
STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT u.kind, u.id, u.automation_id, u.worker_id, u.requested_at, u.started_at, u.ended_at, u.outcome
      FROM (
        SELECT 'platform' AS kind, e.execution_id AS id, e.automation_id, e.worker_id, e.created_at AS requested_at, e.started_at,
               e.finished_at AS ended_at,
               CASE WHEN e.status IN (1, 2, 3) THEN 'in_progress' WHEN e.status = 4 THEN 'succeeded'
                    WHEN e.status = 5 AND e.status_reason = 3 THEN 'failed' WHEN e.status = 5 THEN 'not_started'
                    WHEN e.status = 6 AND e.status_reason = 6 THEN 'not_started' ELSE 'cancelled' END AS outcome
          FROM public.executions e WHERE e.organization_id = p_organization_id
        UNION ALL
        SELECT 'local_agent', r.agent_run_id, r.automation_id, r.worker_id, r.registered_at, r.agent_started_at, r.agent_ended_at,
               CASE WHEN r.supervisor_state = 4 AND r.supervisor_code = 'AGT-EXT-001' THEN 'no_error_signaled'
                    WHEN r.supervisor_state IN (1, 2, 3) THEN 'in_progress' WHEN r.supervisor_state = 4 THEN 'succeeded'
                    WHEN r.supervisor_state IN (5, 7) THEN 'failed' WHEN r.supervisor_state = 6 THEN 'cancelled'
                    WHEN r.supervisor_state = 8 THEN 'not_started' ELSE 'unconfirmed' END
          FROM public.agent_runs r WHERE r.organization_id = p_organization_id AND r.platform_execution_id IS NULL
        UNION ALL
        SELECT 'local_request', q.run_request_id, q.automation_id, q.worker_id, q.requested_at, NULL::timestamptz, NULL::timestamptz,
               CASE WHEN q.state = 8 THEN 'cancelled' WHEN q.state IN (4, 5, 7) THEN 'not_started'
                    WHEN q.state IN (1, 2) AND q.valid_until <= pg_catalog.clock_timestamp() THEN 'not_started' ELSE 'in_progress' END
          FROM public.run_requests q WHERE q.organization_id = p_organization_id AND q.state <> 6 AND q.agent_run_id IS NULL
        UNION ALL
        SELECT 'remote', x.remote_execution_id, x.automation_id, t.worker_id, x.requested_at, t.started_at, t.ended_at,
               CASE WHEN t.state IS NULL THEN 'in_progress'
                    WHEN t.state IN (1, 2) AND t.offer_valid_until <= pg_catalog.clock_timestamp() THEN 'not_started'
                    WHEN t.state BETWEEN 1 AND 6 THEN 'in_progress' WHEN t.state = 7 THEN 'succeeded'
                    WHEN t.state IN (8, 10) THEN 'failed' WHEN t.state = 9 THEN 'cancelled'
                    WHEN t.state IN (11, 12) THEN 'not_started' ELSE 'unconfirmed' END
          FROM public.remote_executions x
          LEFT JOIN LATERAL (SELECT z.state, z.offer_valid_until, z.worker_id, z.started_at, z.ended_at FROM public.remote_attempts z
                              WHERE z.organization_id = x.organization_id AND z.remote_execution_id = x.remote_execution_id
                              ORDER BY z.attempt_number DESC LIMIT 1) t ON true
         WHERE x.organization_id = p_organization_id
      ) u
     WHERE EXISTS (SELECT 1 FROM public.portal_client_scope(p_organization_id, p_client_membership_id) g
                    WHERE g.automation_id = u.automation_id AND (g.worker_id IS NULL OR g.worker_id = u.worker_id))
$$;

-- envelope comum: guarda e restaura o contexto (padrao da 013)
CREATE FUNCTION public.portal_client_organizations(p_identity_token TEXT)
RETURNS TABLE (organization_id UUID, organization_name TEXT, client_name TEXT, shared_automations BIGINT)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
#variable_conflict use_column
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    s RECORD;
BEGIN
    BEGIN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', '', true);
        SELECT * INTO s FROM public.portal_client_resolve(NULL, p_identity_token);
        RETURN QUERY
        SELECT o.organization_id, o.name, c.name,
               (SELECT pg_catalog.count(DISTINCT g.automation_id) FROM public.client_automation_grants g
                 WHERE g.client_membership_id = m.client_membership_id AND g.status = 2)
          FROM public.identity_users iu
          JOIN public.users u ON u.user_id = iu.user_id
          JOIN public.organizations o ON o.organization_id = u.organization_id
          JOIN public.client_memberships m ON m.user_id = u.user_id AND m.organization_id = u.organization_id
          JOIN public.clients c ON c.client_id = m.client_id AND c.organization_id = m.organization_id
         WHERE iu.identity_id = s.o_identity_id AND u.account_type = 2 AND u.status = 2 AND u.email_verified_at IS NOT NULL
           AND o.status = 1 AND m.status = 2 AND c.status = 1
         ORDER BY o.name, o.organization_id;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
END;
$$;

-- cards: automacoes compartilhadas, com o alcance (maquinas), as permissoes e a ultima execucao visivel
CREATE FUNCTION public.portal_client_automations(p_organization_id UUID, p_identity_token TEXT)
RETURNS TABLE (automation_id UUID, automation_name TEXT, workspace_name TEXT, all_machines BOOLEAN, machines JSONB, can_read_logs BOOLEAN,
               can_download_results BOOLEAN, executions_30d BIGINT, last_requested_at TIMESTAMPTZ, last_outcome TEXT, client_name TEXT)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
#variable_conflict use_column
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    s RECORD;
BEGIN
    BEGIN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(p_organization_id::text, ''), true);
        SELECT * INTO s FROM public.portal_client_resolve(p_organization_id, p_identity_token);
        RETURN QUERY
        WITH sc AS (SELECT * FROM public.portal_client_scope(p_organization_id, s.o_client_membership_id)),
             ex AS (SELECT * FROM public.portal_client_executions(p_organization_id, s.o_client_membership_id))
        SELECT a.automation_id, a.name, w.name,
               bool_or(sc.worker_id IS NULL),
               coalesce(jsonb_agg(jsonb_build_object('workerId', k.worker_id, 'name', k.name) ORDER BY k.name, k.worker_id)
                        FILTER (WHERE k.worker_id IS NOT NULL), '[]'::jsonb),
               bool_or(sc.can_read_logs), bool_or(sc.can_download_results),
               (SELECT pg_catalog.count(*) FROM ex WHERE ex.automation_id = a.automation_id
                   AND ex.requested_at >= pg_catalog.clock_timestamp() - interval '30 days'),
               (SELECT ex.requested_at FROM ex WHERE ex.automation_id = a.automation_id ORDER BY ex.requested_at DESC, ex.id DESC LIMIT 1),
               (SELECT ex.outcome FROM ex WHERE ex.automation_id = a.automation_id ORDER BY ex.requested_at DESC, ex.id DESC LIMIT 1),
               (SELECT c.name FROM public.clients c WHERE c.organization_id = p_organization_id AND c.client_id = s.o_client_id)
          FROM sc
          JOIN public.automations a ON a.organization_id = p_organization_id AND a.automation_id = sc.automation_id
          JOIN public.workspaces w ON w.organization_id = p_organization_id AND w.workspace_id = a.workspace_id
          LEFT JOIN public.workers k ON k.organization_id = p_organization_id AND k.worker_id = sc.worker_id
         GROUP BY a.automation_id, a.name, w.name
         ORDER BY a.name, a.automation_id;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
END;
$$;

-- execucoes de UMA automacao compartilhada, da mais recente, com cursor (instante, id). Automacao fora do escopo: NK404.
CREATE FUNCTION public.portal_client_list_executions(p_organization_id UUID, p_identity_token TEXT, p_automation_id UUID,
    p_after_at TIMESTAMPTZ, p_after_id UUID, p_limit INTEGER)
RETURNS TABLE (kind TEXT, id UUID, worker_id UUID, worker_name TEXT, requested_at TIMESTAMPTZ, started_at TIMESTAMPTZ, ended_at TIMESTAMPTZ,
               outcome TEXT)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
#variable_conflict use_column
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    s RECORD;
BEGIN
    BEGIN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(p_organization_id::text, ''), true);
        SELECT * INTO s FROM public.portal_client_resolve(p_organization_id, p_identity_token);
        IF p_limit IS NULL OR p_limit < 1 OR p_limit > 100 OR (p_after_at IS NULL) <> (p_after_id IS NULL) THEN
            RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'pagina invalida';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM public.portal_client_scope(p_organization_id, s.o_client_membership_id) g WHERE g.automation_id = p_automation_id) THEN
            RAISE EXCEPTION USING ERRCODE = 'NK404', MESSAGE = 'fora do escopo';
        END IF;
        RETURN QUERY
        SELECT ex.kind, ex.id, ex.worker_id, k.name, ex.requested_at, ex.started_at, ex.ended_at, ex.outcome
          FROM public.portal_client_executions(p_organization_id, s.o_client_membership_id) ex
          LEFT JOIN public.workers k ON k.organization_id = p_organization_id AND k.worker_id = ex.worker_id
         WHERE ex.automation_id = p_automation_id
           AND (p_after_at IS NULL OR (ex.requested_at, ex.id) < (p_after_at, p_after_id))
         ORDER BY ex.requested_at DESC, ex.id DESC
         LIMIT p_limit;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
END;
$$;

-- detalhe de UMA execucao no escopo, com as tentativas remotas nas maquinas concedidas e as permissoes que valem para cada uma
CREATE FUNCTION public.portal_client_get_execution(p_organization_id UUID, p_identity_token TEXT, p_kind TEXT, p_id UUID)
RETURNS TABLE (kind TEXT, id UUID, automation_id UUID, automation_name TEXT, worker_id UUID, worker_name TEXT, requested_at TIMESTAMPTZ,
               started_at TIMESTAMPTZ, ended_at TIMESTAMPTZ, outcome TEXT, attempts JSONB)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
#variable_conflict use_column
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    s RECORD;
    v_found INTEGER;
BEGIN
    BEGIN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(p_organization_id::text, ''), true);
        SELECT * INTO s FROM public.portal_client_resolve(p_organization_id, p_identity_token);
        RETURN QUERY
        SELECT ex.kind, ex.id, ex.automation_id, a.name, ex.worker_id, k.name, ex.requested_at, ex.started_at, ex.ended_at, ex.outcome,
               CASE WHEN ex.kind <> 'remote' THEN '[]'::jsonb ELSE coalesce((
                   SELECT jsonb_agg(jsonb_build_object(
                              'attemptId', z.attempt_id, 'number', z.attempt_number, 'workerId', z.worker_id, 'workerName', kz.name,
                              'startedAt', z.started_at, 'endedAt', z.ended_at,
                              'canReadLogs', p.logs, 'canDownloadResults', p.results) ORDER BY z.attempt_number)
                     FROM public.remote_attempts z
                     LEFT JOIN public.workers kz ON kz.organization_id = z.organization_id AND kz.worker_id = z.worker_id
                     CROSS JOIN LATERAL (SELECT bool_or(g.can_read_logs) AS logs, bool_or(g.can_download_results) AS results
                                           FROM public.portal_client_scope(p_organization_id, s.o_client_membership_id) g
                                          WHERE g.automation_id = ex.automation_id AND (g.worker_id IS NULL OR g.worker_id = z.worker_id)) p
                    WHERE z.organization_id = p_organization_id AND z.remote_execution_id = ex.id AND p.logs IS NOT NULL), '[]'::jsonb) END
          FROM public.portal_client_executions(p_organization_id, s.o_client_membership_id) ex
          JOIN public.automations a ON a.organization_id = p_organization_id AND a.automation_id = ex.automation_id
          LEFT JOIN public.workers k ON k.organization_id = p_organization_id AND k.worker_id = ex.worker_id
         WHERE ex.kind = p_kind AND ex.id = p_id;
        GET DIAGNOSTICS v_found = ROW_COUNT;
        IF v_found = 0 THEN
            RAISE EXCEPTION USING ERRCODE = 'NK404', MESSAGE = 'fora do escopo';
        END IF;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
END;
$$;

-- tentativa remota no escopo com a permissao pedida ('logs' ou 'results'); levanta NK404 (fora do escopo) ou NK403 (sem a permissao)
CREATE FUNCTION public.portal_client_attempt(p_organization_id UUID, p_client_membership_id UUID, p_execution_id UUID, p_attempt_id UUID,
    p_permission TEXT) RETURNS VOID
LANGUAGE plpgsql
STABLE
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_logs BOOLEAN;
    v_results BOOLEAN;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.portal_client_executions(p_organization_id, p_client_membership_id) ex
                    WHERE ex.kind = 'remote' AND ex.id = p_execution_id) THEN
        RAISE EXCEPTION USING ERRCODE = 'NK404', MESSAGE = 'fora do escopo';
    END IF;
    SELECT bool_or(g.can_read_logs), bool_or(g.can_download_results) INTO v_logs, v_results
      FROM public.remote_attempts z
      JOIN public.remote_executions x ON x.organization_id = z.organization_id AND x.remote_execution_id = z.remote_execution_id
      JOIN public.portal_client_scope(p_organization_id, p_client_membership_id) g
        ON g.automation_id = x.automation_id AND (g.worker_id IS NULL OR g.worker_id = z.worker_id)
     WHERE z.organization_id = p_organization_id AND z.remote_execution_id = p_execution_id AND z.attempt_id = p_attempt_id;
    IF v_logs IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'NK404', MESSAGE = 'fora do escopo';
    END IF;
    IF (p_permission = 'logs' AND NOT v_logs) OR (p_permission = 'results' AND NOT v_results) THEN
        RAISE EXCEPTION USING ERRCODE = 'NK403', MESSAGE = 'sem a permissao';
    END IF;
END;
$$;

CREATE FUNCTION public.portal_client_list_logs(p_organization_id UUID, p_identity_token TEXT, p_execution_id UUID, p_attempt_id UUID,
    p_after INTEGER, p_limit INTEGER)
RETURNS TABLE (sequence INTEGER, captured_at TIMESTAMPTZ, channel SMALLINT, truncated BOOLEAN, message TEXT, text_removed BOOLEAN,
               total INTEGER, closed_at TIMESTAMPTZ, dropped_lines BIGINT)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
#variable_conflict use_column
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    s RECORD;
BEGIN
    BEGIN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(p_organization_id::text, ''), true);
        SELECT * INTO s FROM public.portal_client_resolve(p_organization_id, p_identity_token);
        IF p_after IS NULL OR p_after < 0 OR p_limit IS NULL OR p_limit < 1 OR p_limit > 500 THEN
            RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'pagina invalida';
        END IF;
        PERFORM public.portal_client_attempt(p_organization_id, s.o_client_membership_id, p_execution_id, p_attempt_id, 'logs');
        RETURN QUERY
        SELECT l.sequence, l.captured_at, l.channel, l.truncated, CASE WHEN l.redacted_at IS NULL THEN l.message ELSE '' END, l.redacted_at IS NOT NULL,
               (SELECT pg_catalog.count(*)::integer FROM public.remote_attempt_log_lines y WHERE y.organization_id = p_organization_id AND y.attempt_id = p_attempt_id),
               c.closed_at, c.dropped_lines
          FROM (SELECT 1) one
          LEFT JOIN public.remote_attempt_log_closures c ON c.organization_id = p_organization_id AND c.attempt_id = p_attempt_id
          LEFT JOIN LATERAL (SELECT x.sequence, x.captured_at, x.channel, x.truncated, x.message, x.redacted_at
                               FROM public.remote_attempt_log_lines x WHERE x.organization_id = p_organization_id AND x.attempt_id = p_attempt_id
                               AND x.sequence > p_after ORDER BY x.sequence LIMIT p_limit) l ON true
         ORDER BY l.sequence;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
END;
$$;

CREATE FUNCTION public.portal_client_list_results(p_organization_id UUID, p_identity_token TEXT, p_execution_id UUID, p_attempt_id UUID)
RETURNS TABLE (result_number SMALLINT, file_name TEXT, size_bytes BIGINT, sha256 TEXT, received_at TIMESTAMPTZ, available BOOLEAN,
               closed_at TIMESTAMPTZ, skipped_by_limit INTEGER)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
#variable_conflict use_column
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    s RECORD;
BEGIN
    BEGIN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(p_organization_id::text, ''), true);
        SELECT * INTO s FROM public.portal_client_resolve(p_organization_id, p_identity_token);
        PERFORM public.portal_client_attempt(p_organization_id, s.o_client_membership_id, p_execution_id, p_attempt_id, 'results');
        RETURN QUERY
        SELECT r.result_number, r.file_name, r.size_bytes, r.sha256, r.received_at, r.removed_at IS NULL, c.closed_at, c.skipped_by_limit
          FROM (SELECT 1) one
          LEFT JOIN public.remote_attempt_result_closures c ON c.organization_id = p_organization_id AND c.attempt_id = p_attempt_id
          LEFT JOIN public.remote_attempt_results r ON r.organization_id = p_organization_id AND r.attempt_id = p_attempt_id
         ORDER BY r.result_number;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
END;
$$;

ALTER FUNCTION public.portal_client_resolve(UUID, TEXT) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_client_scope(UUID, UUID) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_client_executions(UUID, UUID) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_client_attempt(UUID, UUID, UUID, UUID, TEXT) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_client_organizations(TEXT) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_client_automations(UUID, TEXT) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_client_list_executions(UUID, TEXT, UUID, TIMESTAMPTZ, UUID, INTEGER) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_client_get_execution(UUID, TEXT, TEXT, UUID) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_client_list_logs(UUID, TEXT, UUID, UUID, INTEGER, INTEGER) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_client_list_results(UUID, TEXT, UUID, UUID) OWNER TO nekkora_portal_owner;

REVOKE ALL ON FUNCTION public.portal_client_resolve(UUID, TEXT), public.portal_client_scope(UUID, UUID),
    public.portal_client_executions(UUID, UUID), public.portal_client_attempt(UUID, UUID, UUID, UUID, TEXT),
    public.portal_client_organizations(TEXT), public.portal_client_automations(UUID, TEXT),
    public.portal_client_list_executions(UUID, TEXT, UUID, TIMESTAMPTZ, UUID, INTEGER), public.portal_client_get_execution(UUID, TEXT, TEXT, UUID),
    public.portal_client_list_logs(UUID, TEXT, UUID, UUID, INTEGER, INTEGER), public.portal_client_list_results(UUID, TEXT, UUID, UUID),
    public.access_can_invite_client(UUID, UUID), public.access_worker_in_workspace(UUID, UUID, UUID), public.access_automation_shareable(UUID, UUID),
    public.client_automation_grant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.portal_client_organizations(TEXT), public.portal_client_automations(UUID, TEXT),
    public.portal_client_list_executions(UUID, TEXT, UUID, TIMESTAMPTZ, UUID, INTEGER), public.portal_client_get_execution(UUID, TEXT, TEXT, UUID),
    public.portal_client_list_logs(UUID, TEXT, UUID, UUID, INTEGER, INTEGER), public.portal_client_list_results(UUID, TEXT, UUID, UUID)
    TO nekkora_portal;
GRANT EXECUTE ON FUNCTION public.access_can_invite_client(UUID, UUID), public.access_worker_in_workspace(UUID, UUID, UUID),
    public.access_automation_shareable(UUID, UUID) TO nekkora_platform, nekkora_identity_owner;

-- === 8. Invariantes ===
DO $$
DECLARE
    v_bad TEXT;
BEGIN
    IF has_table_privilege('nekkora_platform', 'public.client_automation_grants', 'DELETE, TRUNCATE')
       OR has_column_privilege('nekkora_platform', 'public.client_automation_grants', 'basis', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.client_automation_grants', 'automation_id', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.client_automation_grants', 'worker_id', 'UPDATE') THEN
        RAISE EXCEPTION '070: a Platform nao apaga nem muda a origem da concessao';
    END IF;
    -- o portal continua sem privilegio de tabela; as funcoes novas sao as unicas novas que ele executa
    SELECT string_agg(c.relname, ', ') INTO v_bad
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relkind IN ('r', 'v', 'm', 'p', 'f')
       AND (has_table_privilege('nekkora_portal', c.oid, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
            OR has_any_column_privilege('nekkora_portal', c.oid, 'SELECT, INSERT, UPDATE, REFERENCES'));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION '070: nekkora_portal com privilegio de tabela/coluna: %', v_bad;
    END IF;
    SELECT string_agg(c.relname, ', ') INTO v_bad
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relkind IN ('r', 'v', 'm', 'p', 'f')
       AND has_table_privilege('nekkora_portal_owner', c.oid, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER');
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION '070: nekkora_portal_owner com privilegio de tabela inteira: %', v_bad;
    END IF;
    IF has_any_column_privilege('nekkora_portal_owner', 'public.identity_sessions', 'SELECT')
       OR has_column_privilege('nekkora_portal_owner', 'public.identity_credentials', 'password_hash', 'SELECT') THEN
        RAISE EXCEPTION '070: o portal le a sessao de identidade so pelo lookup';
    END IF;
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname LIKE 'portal\_client\_%' AND p.prosecdef
       AND (p.proconfig IS NULL OR NOT EXISTS (SELECT 1 FROM unnest(p.proconfig) c WHERE c LIKE 'search_path=%')
            OR has_function_privilege('public', p.oid, 'EXECUTE'));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION '070: SECURITY DEFINER sem search_path fixo ou com EXECUTE publico: %', v_bad;
    END IF;
END;
$$;

COMMIT;
