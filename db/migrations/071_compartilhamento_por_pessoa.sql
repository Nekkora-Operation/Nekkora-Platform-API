-- Nekkora Platform - PLATFORM-FLUXO-002 (continuacao): compartilhamento por pessoa e por automacao; area do cliente so com
-- dashboard de execucao e arquivos de retorno; registros tecnicos (logs) deixam de ser compartilhaveis.
-- Contrato: api/openapi/nekkora-api-v1.yaml e docs/platform-fluxo-002/decisoes.md ("Compartilhamento por pessoa").
--
-- CANDIDATA ate a aprovacao do PR (vem junto com a 070, no mesmo PR). Cria/substitui funcoes de outros donos
-- (nekkora_identity_owner, nekkora_portal_owner): exige a janela de SUPERUSER do migrador, como a 067 e a 070. SO LABORATORIO E
-- TESTES ate autorizacao especifica.
--
-- DECISOES DA TITULAR (21/11/2026), sem alterar o texto da 070:
--   * O cliente ve so dashboards de execucao (indicadores e estado em linguagem simples) e arquivos de retorno autorizados.
--     Registros tecnicos (logs), codigo, pacotes, configuracoes, maquinas e acoes de execucao ficam fora - tambem no Portal e nos
--     downloads (as funcoes de logs do Portal sao removidas e o owner do Portal perde a leitura das tabelas de logs).
--   * Concessoes existentes com logs (so no laboratorio: a 070 nunca saiu dele; as concessoes antigas por ambiente nunca tiveram
--     logs): esta migration RETIRA a permissao das concessoes abertas (pendentes e ativas) e dos convites abertos, registra um
--     evento por item (25 = CLIENTE_REGISTROS_TECNICOS_RETIRADOS, ato do sistema, sem pessoa) e preserva os eventos antigos
--     (que continuam dizendo can_read_logs = true). Concessoes revogadas e convites fechados nao mudam (historico).
--     Daqui em diante o banco recusa logs em concessao aberta e em convite aberto.
--   * Conteudos por compartilhamento: dashboard de execucao (can_view_dashboard) e arquivos de retorno (can_download_results),
--     ao menos um. Relatorios personalizados NAO existem ainda (proxima etapa).
--   * Compartilhar por membro: nova permissao de workspace client_share.request, so do WORKSPACE_ADMIN, valida nas automacoes dos
--     ambientes em que ele a tem e so para conteudos que ele mesmo pode ver (dashboard: automation.read; arquivos: logs.read - a
--     mesma regra da rota interna dos resultados). O pedido (client_share_requests) nasce PENDENTE e so vira convite com a
--     aprovacao de OUTRA pessoa com org.manage e external_access.manage (D-GRANT-1, aprovacao ANTES do convite); quem aprova
--     emite o convite. A excecao da administradora (070) NAO se estende ao membro: o convite que nasce de um pedido aprovado nao
--     e marcado como excecao. O aceite reconfere a autoridade de quem emitiu e a permissao de quem pediu.
--   * Cliente interno sem etapa: o convite pede nome e e-mail; o cliente interno e criado pela aplicacao (ou reutilizado) no
--     envio/aprovacao. recipient_name guarda o nome informado (preenche o primeiro acesso: nome e e-mail nao sao pedidos de novo).
--
-- O QUE MUDA
--   1. permissions: client_share.request (WORKSPACE_ADMIN). Funcoes access_workspace_permission e access_share_request_allowed.
--   2. client_automation_grants: can_view_dashboard (existentes = true); logs so em concessao revogada; ao menos um conteudo.
--      O gatilho (substituido) trata o dashboard como as outras permissoes (so diminui; o aceite copia do convite).
--   3. identity_invitations: can_view_dashboard, recipient_name, share_request_id; logs so em convite fechado. Gatilhos de
--      insercao e atualizacao substituidos (a origem imutavel inclui as colunas novas).
--   4. client_share_requests (nova) com gatilho de autoridade, segunda pessoa, escopo e transicoes.
--   5. access_events: tipos 25..29 (25 = REGISTROS_TECNICOS_RETIRADOS, 26..29 = COMPARTILHAMENTO_SOLICITADO/APROVADO/RECUSADO/
--      CANCELADO), colunas can_view_dashboard e share_request_id; o evento 25 e o unico sem pessoa.
--   6. Aceite (identity_accept_client_invitation_impl substituida): copia o dashboard; excecao marcada so sem pedido aprovado.
--   7. identity_invitation_preview: o primeiro acesso le nome e e-mail do convite (so convite aberto, pelo token).
--   8. Portal: portal_client_scope/executions/automations/list_executions substituidas (sem maquina nem ambiente; dashboard por
--      permissao); arquivos de retorno por portal_client_list_files/portal_client_get_file; resumo por portal_client_summary.
--      Removidas: portal_client_get_execution, portal_client_attempt, portal_client_list_logs, portal_client_list_results.
--
-- REVERSAO (manual): nao ha volta para logs (decisao da titular). Para desfazer o resto antes de qualquer pedido: DROP das funcoes
-- novas, DROP TABLE client_share_requests, recriar as funcoes da 070 e DROP das colunas novas.
--
-- Atomica: uma transacao.

BEGIN;

-- === 1. Pre-condicoes ===
DO $$
BEGIN
    IF to_regclass('public.client_automation_grants') IS NULL THEN
        RAISE EXCEPTION '071: a 070 nao esta aplicada';
    END IF;
    IF to_regclass('public.client_share_requests') IS NOT NULL
       OR EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'public.client_automation_grants'::regclass AND attname = 'can_view_dashboard') THEN
        RAISE EXCEPTION '071: objetos da 071 ja existem';
    END IF;
END;
$$;

-- === 2. Catalogo e autoridade ===
INSERT INTO public.permissions (code, description) VALUES
    ('client_share.request', 'Pedir o compartilhamento de uma automacao com pessoa externa (vale com a aprovacao de outra pessoa)');
INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id FROM public.roles r, public.permissions p
 WHERE r.code = 'WORKSPACE_ADMIN' AND p.code = 'client_share.request';

-- permissao num ambiente: papel de organizacao com o codigo ou papel do proprio ambiente com o codigo (vinculo efetivo)
CREATE FUNCTION public.access_workspace_permission(p_org UUID, p_membership UUID, p_workspace UUID, p_permission TEXT) RETURNS BOOLEAN
LANGUAGE sql STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT public.access_membership_effective(p_org, p_membership)
       AND (EXISTS (SELECT 1 FROM public.organization_role_assignments a
                      JOIN public.role_permissions rp ON rp.role_id = a.role_id
                      JOIN public.permissions p ON p.permission_id = rp.permission_id
                     WHERE a.organization_id = p_org AND a.membership_id = p_membership AND a.status = 1 AND p.code = p_permission)
            OR EXISTS (SELECT 1 FROM public.workspace_role_assignments w
                         JOIN public.role_permissions rp ON rp.role_id = w.role_id
                         JOIN public.permissions p ON p.permission_id = rp.permission_id
                        WHERE w.organization_id = p_org AND w.workspace_id = p_workspace AND w.membership_id = p_membership
                          AND w.status = 1 AND p.code = p_permission))
$$;

-- pedido de compartilhamento: client_share.request no ambiente e so conteudos que a propria pessoa pode ver
CREATE FUNCTION public.access_share_request_allowed(p_org UUID, p_membership UUID, p_workspace UUID, p_dashboard BOOLEAN, p_files BOOLEAN)
RETURNS BOOLEAN
LANGUAGE sql STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT EXISTS (SELECT 1 FROM public.workspace_role_assignments w
                     JOIN public.role_permissions rp ON rp.role_id = w.role_id
                     JOIN public.permissions p ON p.permission_id = rp.permission_id
                    WHERE w.organization_id = p_org AND w.workspace_id = p_workspace AND w.membership_id = p_membership
                      AND w.status = 1 AND p.code = 'client_share.request')
       AND public.access_membership_effective(p_org, p_membership)
       AND (NOT p_dashboard OR public.access_workspace_permission(p_org, p_membership, p_workspace, 'automation.read'))
       AND (NOT p_files OR public.access_workspace_permission(p_org, p_membership, p_workspace, 'logs.read'))
$$;

-- === 3. Auditoria: tipos 25..29, dashboard e pedido ===
ALTER TABLE public.access_events
    ADD COLUMN can_view_dashboard BOOLEAN,
    ADD COLUMN share_request_id   UUID;
DO $$
DECLARE
    v_name TEXT;
BEGIN
    -- a regra "uma pessoa como autora" (023) passa a admitir o evento 25, ato do sistema nesta migration
    SELECT conname INTO v_name FROM pg_constraint
     WHERE conrelid = 'public.access_events'::regclass AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%actor_membership_id IS NOT NULL%actor_identity_id IS NOT NULL%'
       AND pg_get_constraintdef(oid) NOT LIKE '%event_type%';
    IF v_name IS NULL THEN
        RAISE EXCEPTION '071: regra de autoria de access_events divergente da 023';
    END IF;
    EXECUTE format('ALTER TABLE public.access_events DROP CONSTRAINT %I', v_name);
END;
$$;
ALTER TABLE public.access_events DROP CONSTRAINT access_events_event_type_check;
ALTER TABLE public.access_events DROP CONSTRAINT access_events_client_check;
ALTER TABLE public.access_events
    ADD CONSTRAINT access_events_actor_check CHECK (CASE WHEN event_type = 25
        THEN actor_membership_id IS NULL AND actor_user_id IS NULL AND actor_identity_id IS NULL
        ELSE (actor_membership_id IS NOT NULL AND actor_user_id IS NOT NULL) <> (actor_identity_id IS NOT NULL) END),
    ADD CONSTRAINT access_events_event_type_check CHECK (event_type BETWEEN 1 AND 29),
    ADD CONSTRAINT access_events_client_check CHECK (event_type NOT IN (23, 24, 25) OR client_id IS NOT NULL),
    ADD CONSTRAINT access_events_system_check CHECK (event_type <> 25 OR automation_grant_id IS NOT NULL OR invitation_id IS NOT NULL),
    ADD CONSTRAINT access_events_share_request_check CHECK (event_type NOT BETWEEN 26 AND 29 OR share_request_id IS NOT NULL);
COMMENT ON COLUMN public.access_events.event_type IS
    '1..13: ver cabecalho da 023; 14 (024); 15..24 (070); 25=CLIENTE_REGISTROS_TECNICOS_RETIRADOS (071, sistema) '
    '26=COMPARTILHAMENTO_SOLICITADO 27=COMPARTILHAMENTO_APROVADO 28=COMPARTILHAMENTO_RECUSADO 29=COMPARTILHAMENTO_CANCELADO';
COMMENT ON COLUMN public.access_events.can_view_dashboard IS '071: o escopo do ato inclui o dashboard de execucao';

-- === 4. Concessao por automacao: dashboard e logs retirados ===
ALTER TABLE public.client_automation_grants ADD COLUMN can_view_dashboard BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE public.client_automation_grants ALTER COLUMN can_view_dashboard DROP DEFAULT;
COMMENT ON COLUMN public.client_automation_grants.can_view_dashboard IS
    '071: dashboard de execucao (indicadores e estado em linguagem simples). As concessoes anteriores, que mostravam as execucoes, ficam com true';
COMMENT ON COLUMN public.client_automation_grants.can_read_logs IS
    '070; desde a 071 so pode ser true em concessao REVOGADA (historico): registros tecnicos nao sao compartilhaveis';

ALTER TABLE public.identity_invitations
    ADD COLUMN can_view_dashboard BOOLEAN,
    ADD COLUMN recipient_name     TEXT CHECK (recipient_name IS NULL OR (btrim(recipient_name) <> '' AND pg_catalog.length(recipient_name) <= 200)),
    ADD COLUMN share_request_id   UUID;

-- retirada dos logs: itens abertos, com um evento do sistema por item (os gatilhos de origem imutavel ficam desligados SO aqui)
ALTER TABLE public.client_automation_grants DISABLE TRIGGER trg_client_automation_grants_guard;
ALTER TABLE public.identity_invitations DISABLE TRIGGER trg_identity_invitations_on_update;
-- os convites de cliente anteriores (inclusive fechados) mostravam as execucoes: dashboard = true (preenchimento da coluna nova)
UPDATE public.identity_invitations SET can_view_dashboard = true WHERE automation_id IS NOT NULL;
DO $$
DECLARE
    g RECORD;
    i RECORD;
    v_corr UUID := pg_catalog.gen_random_uuid();
BEGIN
    FOR g IN UPDATE public.client_automation_grants SET can_read_logs = false WHERE can_read_logs AND status IN (1, 2)
             RETURNING grant_id, organization_id, client_id, client_membership_id, workspace_id, automation_id, worker_id, can_download_results,
                       can_view_dashboard LOOP
        INSERT INTO public.access_events (organization_id, event_type, client_id, client_membership_id, workspace_id, automation_id, worker_id,
                                          automation_grant_id, can_read_logs, can_download_results, can_view_dashboard, correlation_id)
        VALUES (g.organization_id, 25, g.client_id, g.client_membership_id, g.workspace_id, g.automation_id, g.worker_id, g.grant_id, false,
                g.can_download_results, g.can_view_dashboard, v_corr);
    END LOOP;
    FOR i IN UPDATE public.identity_invitations SET can_read_logs = false
              WHERE can_read_logs AND accepted_at IS NULL AND revoked_at IS NULL
             RETURNING invitation_id, organization_id, client_id, automation_id, worker_id, can_download_results, can_view_dashboard LOOP
        INSERT INTO public.access_events (organization_id, event_type, invitation_id, client_id, automation_id, worker_id, can_read_logs,
                                          can_download_results, can_view_dashboard, correlation_id)
        VALUES (i.organization_id, 25, i.invitation_id, i.client_id, i.automation_id, i.worker_id, false, i.can_download_results,
                i.can_view_dashboard, v_corr);
    END LOOP;
END;
$$;
ALTER TABLE public.client_automation_grants ENABLE TRIGGER trg_client_automation_grants_guard;
ALTER TABLE public.identity_invitations ENABLE TRIGGER trg_identity_invitations_on_update;

ALTER TABLE public.client_automation_grants
    ADD CONSTRAINT client_automation_grants_no_logs_check CHECK (status = 4 OR NOT can_read_logs),
    ADD CONSTRAINT client_automation_grants_content_check CHECK (status = 4 OR can_view_dashboard OR can_download_results);
ALTER TABLE public.identity_invitations
    ADD CONSTRAINT identity_invitations_no_logs_check CHECK (accepted_at IS NOT NULL OR revoked_at IS NOT NULL OR can_read_logs IS NOT TRUE),
    ADD CONSTRAINT identity_invitations_dashboard_check CHECK ((automation_id IS NULL) = (can_view_dashboard IS NULL)),
    ADD CONSTRAINT identity_invitations_recipient_check CHECK (recipient_name IS NULL OR invited_account_type = 2);
COMMENT ON COLUMN public.identity_invitations.recipient_name IS '071: nome informado por quem convidou (preenche o primeiro acesso)';
COMMENT ON COLUMN public.identity_invitations.share_request_id IS '071: pedido de compartilhamento aprovado que originou o convite (sem ele: excecao da 070)';

-- === 5. Pedido de compartilhamento (membro com client_share.request; aprovacao de outra pessoa ANTES do convite) ===
CREATE TABLE public.client_share_requests (
    share_request_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id            UUID NOT NULL,
    workspace_id               UUID NOT NULL,
    automation_id              UUID NOT NULL,
    worker_id                  UUID,
    recipient_name             TEXT NOT NULL CHECK (btrim(recipient_name) <> '' AND pg_catalog.length(recipient_name) <= 200),
    email_normalized           TEXT NOT NULL CHECK (btrim(email_normalized) <> '' AND email_normalized = lower(btrim(email_normalized))
                                                    AND email_normalized !~ '\s'),
    can_view_dashboard         BOOLEAN NOT NULL,
    can_download_results       BOOLEAN NOT NULL,
    status                     SMALLINT NOT NULL CHECK (status IN (1, 2, 3, 4)),
    requested_by_membership_id UUID NOT NULL,
    requested_by_user_id       UUID NOT NULL REFERENCES public.users (user_id) ON DELETE RESTRICT,
    decided_by_membership_id   UUID,
    decided_at                 TIMESTAMPTZ,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (organization_id, share_request_id),
    FOREIGN KEY (organization_id, workspace_id, automation_id)
        REFERENCES public.automations (organization_id, workspace_id, automation_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, worker_id) REFERENCES public.workers (organization_id, worker_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, requested_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, decided_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK (can_view_dashboard OR can_download_results),
    CHECK ((status = 1) = (decided_at IS NULL)),
    CHECK ((decided_at IS NULL) = (decided_by_membership_id IS NULL)),
    CHECK (status = 4 OR decided_by_membership_id IS NULL OR decided_by_membership_id <> requested_by_membership_id),
    CHECK (decided_at IS NULL OR decided_at >= created_at)
);
COMMENT ON TABLE public.client_share_requests IS
    '071: pedido de um membro (client_share.request) para compartilhar UMA automacao com uma pessoa externa. So vira convite com a '
    'aprovacao de outra pessoa (org.manage e external_access.manage), que emite o convite. Nao concede nada por si.';
COMMENT ON COLUMN public.client_share_requests.status IS '1=PENDENTE 2=APROVADO (convite emitido por quem aprovou) 3=RECUSADO 4=CANCELADO (por quem pediu)';
CREATE UNIQUE INDEX ux_client_share_requests_pending ON public.client_share_requests
    (organization_id, email_normalized, automation_id, coalesce(worker_id, '00000000-0000-0000-0000-000000000000'::uuid)) WHERE status = 1;
CREATE INDEX ix_client_share_requests_org ON public.client_share_requests (organization_id, status, created_at, share_request_id);

ALTER TABLE public.identity_invitations
    ADD CONSTRAINT identity_invitations_share_request_fkey FOREIGN KEY (organization_id, share_request_id)
        REFERENCES public.client_share_requests (organization_id, share_request_id) ON DELETE RESTRICT,
    ADD CONSTRAINT identity_invitations_share_request_check CHECK (share_request_id IS NULL OR invited_account_type = 2);
ALTER TABLE public.access_events
    ADD CONSTRAINT access_events_share_request_fkey FOREIGN KEY (organization_id, share_request_id)
        REFERENCES public.client_share_requests (organization_id, share_request_id) ON DELETE RESTRICT;

CREATE FUNCTION public.client_share_request_guard() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 1 OR NEW.decided_at IS NOT NULL THEN
            RAISE EXCEPTION 'pedido de compartilhamento nasce pendente' USING ERRCODE = '23514';
        END IF;
        IF coalesce((SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.rolname = current_user), false) THEN
            RETURN NEW;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM public.organization_memberships m WHERE m.organization_id = NEW.organization_id
                        AND m.membership_id = NEW.requested_by_membership_id AND m.user_id = NEW.requested_by_user_id)
           OR NOT public.access_share_request_allowed(NEW.organization_id, NEW.requested_by_membership_id, NEW.workspace_id,
                                                      NEW.can_view_dashboard, NEW.can_download_results) THEN
            RAISE EXCEPTION 'pedido de compartilhamento sem autoridade' USING ERRCODE = '42501';
        END IF;
        IF public.access_automation_shareable(NEW.organization_id, NEW.automation_id) IS DISTINCT FROM NEW.workspace_id
           OR (NEW.worker_id IS NOT NULL AND NOT public.access_worker_in_workspace(NEW.organization_id, NEW.worker_id, NEW.workspace_id)) THEN
            RAISE EXCEPTION 'escopo do pedido indisponivel' USING ERRCODE = '23514';
        END IF;
        IF EXISTS (SELECT 1 FROM public.users u WHERE u.organization_id = NEW.organization_id AND u.email_normalized = NEW.email_normalized
                    AND u.account_type = 1) THEN
            RAISE EXCEPTION 'destinataria e conta interna da organizacao' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 1 THEN
        RAISE EXCEPTION 'pedido decidido e imutavel (share_request_id=%)', OLD.share_request_id;
    END IF;
    IF NEW.share_request_id <> OLD.share_request_id OR NEW.organization_id <> OLD.organization_id OR NEW.workspace_id <> OLD.workspace_id
       OR NEW.automation_id <> OLD.automation_id OR NEW.worker_id IS DISTINCT FROM OLD.worker_id OR NEW.recipient_name <> OLD.recipient_name
       OR NEW.email_normalized <> OLD.email_normalized OR NEW.can_view_dashboard <> OLD.can_view_dashboard
       OR NEW.can_download_results <> OLD.can_download_results OR NEW.requested_by_membership_id <> OLD.requested_by_membership_id
       OR NEW.requested_by_user_id <> OLD.requested_by_user_id OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'origem do pedido e imutavel (share_request_id=%)', OLD.share_request_id;
    END IF;
    IF NEW.status = 1 THEN
        RAISE EXCEPTION 'pedido pendente so muda para aprovado, recusado ou cancelado' USING ERRCODE = '23514';
    END IF;
    IF coalesce((SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.rolname = current_user), false) THEN
        RETURN NEW;
    END IF;
    IF NEW.status = 4 THEN
        -- cancelar: quem pediu
        IF NEW.decided_by_membership_id IS DISTINCT FROM OLD.requested_by_membership_id THEN
            RAISE EXCEPTION 'so quem pediu cancela o pedido' USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END IF;
    -- aprovar ou recusar: OUTRA pessoa com org.manage e external_access.manage
    IF NEW.decided_by_membership_id = OLD.requested_by_membership_id
       OR NOT public.access_can_invite_client(NEW.organization_id, NEW.decided_by_membership_id) THEN
        RAISE EXCEPTION 'decisao do pedido exige outra pessoa com a permissao' USING ERRCODE = '42501';
    END IF;
    IF NEW.status = 2 AND (
           NOT public.access_share_request_allowed(NEW.organization_id, NEW.requested_by_membership_id, NEW.workspace_id,
                                                   NEW.can_view_dashboard, NEW.can_download_results)
           OR public.access_automation_shareable(NEW.organization_id, NEW.automation_id) IS DISTINCT FROM NEW.workspace_id
           OR (NEW.worker_id IS NOT NULL AND NOT public.access_worker_in_workspace(NEW.organization_id, NEW.worker_id, NEW.workspace_id))) THEN
        RAISE EXCEPTION 'escopo do pedido indisponivel' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_client_share_requests_guard BEFORE INSERT OR UPDATE ON public.client_share_requests
    FOR EACH ROW EXECUTE FUNCTION public.client_share_request_guard();
CREATE TRIGGER trg_client_share_requests_set_updated_at BEFORE UPDATE ON public.client_share_requests
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_client_share_requests_prevent_delete BEFORE DELETE ON public.client_share_requests
    FOR EACH ROW EXECUTE FUNCTION prevent_delete();
CREATE TRIGGER trg_client_share_requests_prevent_truncate BEFORE TRUNCATE ON public.client_share_requests
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_truncate();
ALTER TABLE public.client_share_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.client_share_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_request_context ON public.client_share_requests AS PERMISSIVE FOR ALL TO nekkora_platform
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid);
CREATE POLICY identity_function_context ON public.client_share_requests AS PERMISSIVE FOR SELECT TO nekkora_identity_owner
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.fn_organization_id', true), '')::uuid);
GRANT SELECT, INSERT ON public.client_share_requests TO nekkora_platform;
GRANT UPDATE (status, decided_by_membership_id, decided_at) ON public.client_share_requests TO nekkora_platform;
GRANT SELECT (share_request_id, organization_id, workspace_id, automation_id, worker_id, can_view_dashboard, can_download_results, status,
              requested_by_membership_id) ON public.client_share_requests TO nekkora_identity_owner;

-- === 6. Gatilhos substituidos ===
-- concessao: corpo da 070 com o dashboard (so diminui; o aceite copia do convite)
CREATE OR REPLACE FUNCTION public.client_automation_grant_guard() RETURNS TRIGGER
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
        IF NOT EXISTS (SELECT 1 FROM public.client_memberships m JOIN public.clients c ON c.organization_id = m.organization_id AND c.client_id = m.client_id
                        WHERE m.organization_id = NEW.organization_id AND m.client_membership_id = NEW.client_membership_id
                          AND m.client_id = NEW.client_id AND m.status = 2 AND c.status = 1)
           OR public.access_automation_shareable(NEW.organization_id, NEW.automation_id) IS DISTINCT FROM NEW.workspace_id
           OR (NEW.worker_id IS NOT NULL AND NOT public.access_worker_in_workspace(NEW.organization_id, NEW.worker_id, NEW.workspace_id)) THEN
            RAISE EXCEPTION 'escopo da concessao indisponivel' USING ERRCODE = '23514';
        END IF;
        IF NEW.basis = 1 THEN
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
        IF current_user <> 'nekkora_identity_owner' OR NEW.status <> 2 THEN
            RAISE EXCEPTION 'concessao do convite de cliente so pelo aceite' USING ERRCODE = '42501';
        END IF;
        SELECT x.client_id, x.automation_id, x.worker_id, x.can_read_logs, x.can_download_results, x.can_view_dashboard, x.issued_by_membership_id,
               x.accepted_at, x.accepted_user_id, x.invited_account_type, x.organization_id
          INTO i FROM public.identity_invitations x WHERE x.invitation_id = NEW.invitation_id;
        IF i.invited_account_type IS DISTINCT FROM 2 OR i.organization_id <> NEW.organization_id OR i.accepted_at IS NULL
           OR i.accepted_at <> NEW.approved_at
           OR NOT EXISTS (SELECT 1 FROM public.client_memberships m WHERE m.organization_id = NEW.organization_id
                           AND m.client_membership_id = NEW.client_membership_id AND m.user_id = i.accepted_user_id)
           OR i.client_id <> NEW.client_id OR i.automation_id <> NEW.automation_id OR i.worker_id IS DISTINCT FROM NEW.worker_id
           OR i.can_read_logs <> NEW.can_read_logs OR i.can_download_results <> NEW.can_download_results
           OR i.can_view_dashboard <> NEW.can_view_dashboard
           OR i.issued_by_membership_id <> NEW.requested_by_membership_id
           OR NOT public.access_can_invite_client(NEW.organization_id, i.issued_by_membership_id) THEN
            RAISE EXCEPTION 'concessao diverge do convite aceito' USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END IF;

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
    IF (NEW.can_read_logs AND NOT OLD.can_read_logs) OR (NEW.can_download_results AND NOT OLD.can_download_results)
       OR (NEW.can_view_dashboard AND NOT OLD.can_view_dashboard) THEN
        RAISE EXCEPTION 'ampliacao de permissao exige nova concessao aprovada (grant_id=%)', OLD.grant_id USING ERRCODE = '42501';
    END IF;
    IF (OLD.approved_at IS NOT NULL) AND (NEW.approved_at IS DISTINCT FROM OLD.approved_at OR NEW.approved_by_membership_id IS DISTINCT FROM OLD.approved_by_membership_id) THEN
        RAISE EXCEPTION 'aprovacao registrada e imutavel (grant_id=%)', OLD.grant_id;
    END IF;
    IF NOT (NEW.status = OLD.status OR (OLD.status = 1 AND NEW.status IN (2, 4)) OR (OLD.status = 2 AND NEW.status = 4)) THEN
        RAISE EXCEPTION 'transicao de concessao invalida (% -> %)', OLD.status, NEW.status USING ERRCODE = '23514';
    END IF;
    IF OLD.status = 1 AND (NEW.can_read_logs <> OLD.can_read_logs OR NEW.can_download_results <> OLD.can_download_results
                           OR NEW.can_view_dashboard <> OLD.can_view_dashboard) THEN
        RAISE EXCEPTION 'pedido pendente nao muda: cancele e peca de novo (grant_id=%)', OLD.grant_id USING ERRCODE = '23514';
    END IF;
    IF v_super THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 1 AND NEW.status = 2 THEN
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
GRANT UPDATE (can_view_dashboard) ON public.client_automation_grants TO nekkora_platform;

-- convite: corpo da 070 com nome, dashboard e o caminho do pedido aprovado
CREATE OR REPLACE FUNCTION public.access_invitation_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_workspace UUID;
    r RECORD;
BEGIN
    IF coalesce((SELECT r0.rolsuper FROM pg_catalog.pg_roles r0 WHERE r0.rolname = current_user), false) THEN
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
    IF NEW.automation_id IS NULL OR NEW.recipient_name IS NULL THEN
        RAISE EXCEPTION 'convite de cliente exige a automacao compartilhada e o nome da pessoa' USING ERRCODE = '23514';
    END IF;
    IF NEW.can_read_logs THEN
        RAISE EXCEPTION 'registros tecnicos nao sao compartilhaveis' USING ERRCODE = '23514';
    END IF;
    IF NOT (NEW.can_view_dashboard OR NEW.can_download_results) THEN
        RAISE EXCEPTION 'convite de cliente sem conteudo compartilhado' USING ERRCODE = '23514';
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
    IF NEW.share_request_id IS NOT NULL THEN
        -- convite de pedido aprovado: quem emite e quem aprovou; o escopo e o do pedido, sem ampliar
        SELECT * INTO r FROM public.client_share_requests q
         WHERE q.organization_id = NEW.organization_id AND q.share_request_id = NEW.share_request_id;
        IF r.status IS DISTINCT FROM 2 OR r.decided_by_membership_id <> NEW.issued_by_membership_id
           OR r.automation_id <> NEW.automation_id OR r.worker_id IS DISTINCT FROM NEW.worker_id
           OR r.email_normalized <> NEW.email_normalized OR r.can_view_dashboard <> NEW.can_view_dashboard
           OR r.can_download_results <> NEW.can_download_results THEN
            RAISE EXCEPTION 'convite diverge do pedido aprovado' USING ERRCODE = '42501';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

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
       OR NEW.can_read_logs IS DISTINCT FROM OLD.can_read_logs OR NEW.can_download_results IS DISTINCT FROM OLD.can_download_results
       OR NEW.can_view_dashboard IS DISTINCT FROM OLD.can_view_dashboard OR NEW.recipient_name IS DISTINCT FROM OLD.recipient_name
       OR NEW.share_request_id IS DISTINCT FROM OLD.share_request_id THEN
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

GRANT SELECT (can_view_dashboard, recipient_name, share_request_id) ON public.identity_invitations TO nekkora_identity_owner;

-- === 7. Aceite do convite de cliente (corpo da 070 com o dashboard, a excecao so sem pedido e a permissao de quem pediu) ===
CREATE OR REPLACE FUNCTION public.identity_accept_client_invitation_impl(p_session_token TEXT, p_token TEXT, p_organization_id UUID)
RETURNS TEXT
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_hash TEXT := public.identity_token_hash(p_token);
    s RECORD;
    i RECORD;
    c RECORD;
    v_req_status SMALLINT;
    v_req_workspace UUID;
    v_req_by UUID;
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
           x.worker_id, x.can_read_logs, x.can_download_results, x.can_view_dashboard, x.share_request_id
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
    IF c.share_request_id IS NOT NULL THEN
        SELECT r.status, r.workspace_id, r.requested_by_membership_id INTO v_req_status, v_req_workspace, v_req_by FROM public.client_share_requests r
         WHERE r.organization_id = p_organization_id AND r.share_request_id = c.share_request_id;
    END IF;

    v_t := pg_catalog.clock_timestamp();
    IF NOT public.identity_session_proof(s.o_session_id, v_t, 'step_up')
       OR c.expires_at <= v_t
       OR c.email_normalized <> i.email_normalized
       OR c.issued_by_membership_id IS NULL
       OR NOT public.access_can_invite_client(p_organization_id, c.issued_by_membership_id)
       OR c.can_read_logs THEN
        RETURN 'INVALIDO';
    END IF;
    IF v_linked.user_id IS NOT NULL AND v_linked.account_type = 1 THEN
        RETURN 'MEMBRO_INTERNO';
    END IF;
    v_workspace := public.access_automation_shareable(p_organization_id, c.automation_id);
    IF NOT EXISTS (SELECT 1 FROM public.clients x WHERE x.organization_id = p_organization_id AND x.client_id = c.client_id AND x.status = 1)
       OR v_workspace IS NULL
       OR (c.worker_id IS NOT NULL AND NOT public.access_worker_in_workspace(p_organization_id, c.worker_id, v_workspace))
       -- pedido de membro: continua aprovado e quem pediu ainda pode compartilhar aquele conteudo naquele ambiente
       OR (c.share_request_id IS NOT NULL AND (v_req_status IS DISTINCT FROM 2 OR v_req_workspace IS DISTINCT FROM v_workspace
           OR NOT public.access_share_request_allowed(p_organization_id, v_req_by, v_workspace, c.can_view_dashboard,
                                                      c.can_download_results))) THEN
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
            RETURN 'CONTA_INDISPONIVEL';
        END IF;
        IF EXISTS (SELECT 1 FROM public.client_automation_grants g WHERE g.organization_id = p_organization_id
                    AND g.client_membership_id = v_cm.client_membership_id AND g.automation_id = c.automation_id
                    AND g.worker_id IS NOT DISTINCT FROM c.worker_id AND g.status IN (1, 2)) THEN
            RAISE EXCEPTION USING ERRCODE = 'NK409', MESSAGE = 'CONCESSAO_EXISTENTE';
        END IF;
        v_user := v_linked.user_id;
        v_cmid := v_cm.client_membership_id;
    ELSIF v_same_email IS NOT NULL THEN
        RETURN 'CONTA_EXISTENTE';
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
                                                 can_read_logs, can_download_results, can_view_dashboard, status, basis, invitation_id,
                                                 requested_by_membership_id, approved_by_membership_id, approved_at)
    VALUES (p_organization_id, c.client_id, v_cmid, v_workspace, c.automation_id, c.worker_id, false, c.can_download_results,
            c.can_view_dashboard, 2, 2, c.invitation_id, c.issued_by_membership_id, c.issued_by_membership_id, v_t)
    RETURNING grant_id INTO v_grant;
    INSERT INTO public.access_events (organization_id, event_type, actor_identity_id, target_user_id, invitation_id, client_id,
                                      client_membership_id, automation_id, worker_id, automation_grant_id, can_read_logs, can_download_results,
                                      can_view_dashboard, share_request_id, workspace_id, correlation_id, created_at)
    VALUES (p_organization_id, 4, s.o_identity_id, v_user, c.invitation_id, c.client_id, v_cmid, c.automation_id, c.worker_id, v_grant,
            false, c.can_download_results, c.can_view_dashboard, c.share_request_id, v_workspace, pg_catalog.gen_random_uuid(), v_t);
    INSERT INTO public.access_events (organization_id, event_type, actor_membership_id, actor_user_id, target_user_id, invitation_id, client_id,
                                      client_membership_id, automation_id, worker_id, automation_grant_id, can_read_logs, can_download_results,
                                      can_view_dashboard, share_request_id, workspace_id, grant_exception, correlation_id, created_at)
    VALUES (p_organization_id, 17, c.issued_by_membership_id, c.issued_by, v_user, c.invitation_id, c.client_id, v_cmid, c.automation_id,
            c.worker_id, v_grant, false, c.can_download_results, c.can_view_dashboard, c.share_request_id, v_workspace,
            c.share_request_id IS NULL, pg_catalog.gen_random_uuid(), v_t);
    RETURN 'OK_CLIENTE';
END;
$$;
GRANT EXECUTE ON FUNCTION public.access_workspace_permission(UUID, UUID, UUID, TEXT), public.access_share_request_allowed(UUID, UUID, UUID, BOOLEAN, BOOLEAN)
    TO nekkora_platform, nekkora_identity_owner;
REVOKE ALL ON FUNCTION public.access_workspace_permission(UUID, UUID, UUID, TEXT), public.access_share_request_allowed(UUID, UUID, UUID, BOOLEAN, BOOLEAN),
    public.client_share_request_guard() FROM PUBLIC;

-- === 8. Primeiro acesso pelo convite: nome e e-mail do convite aberto (so com o token; nada de conta ou de escopo) ===
CREATE FUNCTION public.identity_invitation_preview(p_token TEXT, p_organization_id UUID)
RETURNS TABLE (email TEXT, recipient_name TEXT, organization_name TEXT, audience TEXT)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
#variable_conflict use_column
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_hash TEXT := public.identity_token_hash(p_token);
BEGIN
    IF v_hash IS NULL OR p_organization_id IS NULL THEN
        RETURN;
    END IF;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    RETURN QUERY
    SELECT x.email_normalized, x.recipient_name, o.name, CASE WHEN x.invited_account_type = 2 THEN 'client' ELSE 'internal' END
      FROM public.identity_invitations x
      JOIN public.organizations o ON o.organization_id = x.organization_id AND o.status = 1
     WHERE x.organization_id = p_organization_id AND x.token_hash = v_hash
       AND x.accepted_at IS NULL AND x.revoked_at IS NULL AND x.expires_at > pg_catalog.clock_timestamp();
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
END;
$$;
ALTER FUNCTION public.identity_invitation_preview(TEXT, UUID) OWNER TO nekkora_identity_owner;
REVOKE ALL ON FUNCTION public.identity_invitation_preview(TEXT, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.identity_invitation_preview(TEXT, UUID) TO nekkora_platform;

-- === 9. Portal: so dashboard e arquivos de retorno ===
DROP FUNCTION public.portal_client_list_logs(UUID, TEXT, UUID, UUID, INTEGER, INTEGER);
DROP FUNCTION public.portal_client_list_results(UUID, TEXT, UUID, UUID);
DROP FUNCTION public.portal_client_attempt(UUID, UUID, UUID, UUID, TEXT);
DROP FUNCTION public.portal_client_get_execution(UUID, TEXT, TEXT, UUID);
DROP FUNCTION public.portal_client_list_executions(UUID, TEXT, UUID, TIMESTAMPTZ, UUID, INTEGER);
DROP FUNCTION public.portal_client_automations(UUID, TEXT);
DROP FUNCTION public.portal_client_executions(UUID, UUID);
DROP FUNCTION public.portal_client_scope(UUID, UUID);
-- o owner do Portal deixa de ler registros tecnicos (o banco nega, nao so a tela)
DROP POLICY portal_function_context ON public.remote_attempt_log_lines;
DROP POLICY portal_function_context ON public.remote_attempt_log_closures;
REVOKE ALL ON public.remote_attempt_log_lines, public.remote_attempt_log_closures FROM nekkora_portal_owner;
GRANT SELECT (can_view_dashboard) ON public.client_automation_grants TO nekkora_portal_owner;

CREATE FUNCTION public.portal_client_scope(p_organization_id UUID, p_client_membership_id UUID)
RETURNS TABLE (automation_id UUID, worker_id UUID, can_view_dashboard BOOLEAN, can_download_results BOOLEAN)
LANGUAGE sql
STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT g.automation_id, g.worker_id, g.can_view_dashboard, g.can_download_results
      FROM public.client_automation_grants g
      JOIN public.automations a ON a.organization_id = g.organization_id AND a.automation_id = g.automation_id
           AND a.workspace_id = g.workspace_id AND a.status IN (1, 2)
      JOIN public.workspaces w ON w.organization_id = g.organization_id AND w.workspace_id = g.workspace_id AND w.status = 1
     WHERE g.organization_id = p_organization_id AND g.client_membership_id = p_client_membership_id AND g.status = 2
$$;

-- execucoes do DASHBOARD: so das automacoes (e maquinas) concedidas COM dashboard. A remota vale pela maquina da ultima tentativa.
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
                    WHERE g.can_view_dashboard AND g.automation_id = u.automation_id AND (g.worker_id IS NULL OR g.worker_id = u.worker_id))
$$;

-- arquivos de RETORNO: resultados das tentativas remotas nas automacoes (e maquinas da tentativa) concedidas COM arquivos
CREATE FUNCTION public.portal_client_files(p_organization_id UUID, p_client_membership_id UUID)
RETURNS TABLE (attempt_id UUID, result_number SMALLINT, automation_id UUID, execution_id UUID, execution_requested_at TIMESTAMPTZ,
               file_name TEXT, size_bytes BIGINT, sha256 TEXT, received_at TIMESTAMPTZ, available BOOLEAN)
LANGUAGE sql
STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT r.attempt_id, r.result_number, x.automation_id, x.remote_execution_id, x.requested_at, r.file_name, r.size_bytes, r.sha256,
           r.received_at, r.removed_at IS NULL
      FROM public.remote_attempt_results r
      JOIN public.remote_attempts z ON z.organization_id = r.organization_id AND z.attempt_id = r.attempt_id
      JOIN public.remote_executions x ON x.organization_id = z.organization_id AND x.remote_execution_id = z.remote_execution_id
     WHERE r.organization_id = p_organization_id
       AND EXISTS (SELECT 1 FROM public.portal_client_scope(p_organization_id, p_client_membership_id) g
                    WHERE g.can_download_results AND g.automation_id = x.automation_id AND (g.worker_id IS NULL OR g.worker_id = z.worker_id))
$$;

-- cards: automacoes compartilhadas, os conteudos e, SO com dashboard, os indicadores dos ultimos 30 dias (nada de maquina ou ambiente)
CREATE FUNCTION public.portal_client_automations(p_organization_id UUID, p_identity_token TEXT)
RETURNS TABLE (automation_id UUID, automation_name TEXT, can_view_dashboard BOOLEAN, can_download_results BOOLEAN, executions_30d BIGINT,
               succeeded_30d BIGINT, failed_30d BIGINT, in_progress BIGINT, last_requested_at TIMESTAMPTZ, last_outcome TEXT,
               files_available BIGINT, client_name TEXT)
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
        WITH sc AS (SELECT g.automation_id, bool_or(g.can_view_dashboard) AS dash, bool_or(g.can_download_results) AS files
                      FROM public.portal_client_scope(p_organization_id, s.o_client_membership_id) g GROUP BY g.automation_id),
             ex AS (SELECT * FROM public.portal_client_executions(p_organization_id, s.o_client_membership_id)),
             fl AS (SELECT * FROM public.portal_client_files(p_organization_id, s.o_client_membership_id))
        SELECT a.automation_id, a.name, sc.dash, sc.files,
               CASE WHEN sc.dash THEN (SELECT pg_catalog.count(*) FROM ex WHERE ex.automation_id = a.automation_id
                   AND ex.requested_at >= pg_catalog.clock_timestamp() - interval '30 days') END,
               CASE WHEN sc.dash THEN (SELECT pg_catalog.count(*) FROM ex WHERE ex.automation_id = a.automation_id AND ex.outcome = 'succeeded'
                   AND ex.requested_at >= pg_catalog.clock_timestamp() - interval '30 days') END,
               CASE WHEN sc.dash THEN (SELECT pg_catalog.count(*) FROM ex WHERE ex.automation_id = a.automation_id AND ex.outcome = 'failed'
                   AND ex.requested_at >= pg_catalog.clock_timestamp() - interval '30 days') END,
               CASE WHEN sc.dash THEN (SELECT pg_catalog.count(*) FROM ex WHERE ex.automation_id = a.automation_id AND ex.outcome = 'in_progress') END,
               CASE WHEN sc.dash THEN (SELECT ex.requested_at FROM ex WHERE ex.automation_id = a.automation_id ORDER BY ex.requested_at DESC, ex.id DESC LIMIT 1) END,
               CASE WHEN sc.dash THEN (SELECT ex.outcome FROM ex WHERE ex.automation_id = a.automation_id ORDER BY ex.requested_at DESC, ex.id DESC LIMIT 1) END,
               CASE WHEN sc.files THEN (SELECT pg_catalog.count(*) FROM fl WHERE fl.automation_id = a.automation_id AND fl.available) END,
               (SELECT c.name FROM public.clients c WHERE c.organization_id = p_organization_id AND c.client_id = s.o_client_id)
          FROM sc
          JOIN public.automations a ON a.organization_id = p_organization_id AND a.automation_id = sc.automation_id
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

-- automacao no escopo com o conteudo pedido ('dashboard' ou 'files'); NK404 (nao compartilhada) ou NK403 (sem aquele conteudo)
CREATE FUNCTION public.portal_client_require(p_organization_id UUID, p_client_membership_id UUID, p_automation_id UUID, p_content TEXT)
RETURNS VOID
LANGUAGE plpgsql
STABLE
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_dash BOOLEAN;
    v_files BOOLEAN;
BEGIN
    SELECT bool_or(g.can_view_dashboard), bool_or(g.can_download_results) INTO v_dash, v_files
      FROM public.portal_client_scope(p_organization_id, p_client_membership_id) g WHERE g.automation_id = p_automation_id;
    IF v_dash IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'NK404', MESSAGE = 'fora do escopo';
    END IF;
    IF (p_content = 'dashboard' AND NOT v_dash) OR (p_content = 'files' AND NOT v_files) THEN
        RAISE EXCEPTION USING ERRCODE = 'NK403', MESSAGE = 'sem o conteudo';
    END IF;
END;
$$;

-- resumo do periodo [de, ate) de UMA automacao, com dashboard: contagem por estado (mesmo escopo de automacao e maquina)
CREATE FUNCTION public.portal_client_summary(p_organization_id UUID, p_identity_token TEXT, p_automation_id UUID, p_from TIMESTAMPTZ,
    p_to TIMESTAMPTZ)
RETURNS TABLE (total BIGINT, succeeded BIGINT, failed BIGINT, in_progress BIGINT, not_started BIGINT, cancelled BIGINT, unconfirmed BIGINT,
               no_error_signaled BIGINT,
               last_requested_at TIMESTAMPTZ, last_outcome TEXT)
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
        IF p_from IS NULL OR p_to IS NULL OR p_to <= p_from OR p_to - p_from > interval '400 days' THEN
            RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'periodo invalido';
        END IF;
        PERFORM public.portal_client_require(p_organization_id, s.o_client_membership_id, p_automation_id, 'dashboard');
        RETURN QUERY
        WITH ex AS (SELECT * FROM public.portal_client_executions(p_organization_id, s.o_client_membership_id) e
                     WHERE e.automation_id = p_automation_id)
        SELECT pg_catalog.count(*) FILTER (WHERE ex.requested_at >= p_from AND ex.requested_at < p_to),
               pg_catalog.count(*) FILTER (WHERE ex.requested_at >= p_from AND ex.requested_at < p_to AND ex.outcome = 'succeeded'),
               pg_catalog.count(*) FILTER (WHERE ex.requested_at >= p_from AND ex.requested_at < p_to AND ex.outcome = 'failed'),
               pg_catalog.count(*) FILTER (WHERE ex.requested_at >= p_from AND ex.requested_at < p_to AND ex.outcome = 'in_progress'),
               pg_catalog.count(*) FILTER (WHERE ex.requested_at >= p_from AND ex.requested_at < p_to AND ex.outcome = 'not_started'),
               pg_catalog.count(*) FILTER (WHERE ex.requested_at >= p_from AND ex.requested_at < p_to AND ex.outcome = 'cancelled'),
               -- resultado desconhecido e "terminou sem sinal de erro" (codigo 0 sem confirmacao de sucesso) ficam separados: nunca contam como concluidas
               pg_catalog.count(*) FILTER (WHERE ex.requested_at >= p_from AND ex.requested_at < p_to AND ex.outcome = 'unconfirmed'),
               pg_catalog.count(*) FILTER (WHERE ex.requested_at >= p_from AND ex.requested_at < p_to AND ex.outcome = 'no_error_signaled'),
               (SELECT e2.requested_at FROM ex e2 ORDER BY e2.requested_at DESC, e2.id DESC LIMIT 1),
               (SELECT e2.outcome FROM ex e2 ORDER BY e2.requested_at DESC, e2.id DESC LIMIT 1)
          FROM ex;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
END;
$$;

-- execucoes de UMA automacao com dashboard, no periodo, da mais recente; o estado em linguagem simples e decidido na tela.
-- files: quantos arquivos de retorno disponiveis a pessoa pode baixar daquela execucao (nulo sem a permissao de arquivos)
CREATE FUNCTION public.portal_client_list_executions(p_organization_id UUID, p_identity_token TEXT, p_automation_id UUID,
    p_from TIMESTAMPTZ, p_to TIMESTAMPTZ, p_after_at TIMESTAMPTZ, p_after_id UUID, p_limit INTEGER)
RETURNS TABLE (kind TEXT, id UUID, requested_at TIMESTAMPTZ, started_at TIMESTAMPTZ, ended_at TIMESTAMPTZ, outcome TEXT, files BIGINT)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
#variable_conflict use_column
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    s RECORD;
    v_files BOOLEAN;
BEGIN
    BEGIN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(p_organization_id::text, ''), true);
        SELECT * INTO s FROM public.portal_client_resolve(p_organization_id, p_identity_token);
        IF p_limit IS NULL OR p_limit < 1 OR p_limit > 101 OR (p_after_at IS NULL) <> (p_after_id IS NULL)
           OR p_from IS NULL OR p_to IS NULL OR p_to <= p_from OR p_to - p_from > interval '400 days' THEN
            RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'pagina invalida';
        END IF;
        PERFORM public.portal_client_require(p_organization_id, s.o_client_membership_id, p_automation_id, 'dashboard');
        SELECT bool_or(g.can_download_results) INTO v_files FROM public.portal_client_scope(p_organization_id, s.o_client_membership_id) g
         WHERE g.automation_id = p_automation_id;
        RETURN QUERY
        SELECT ex.kind, ex.id, ex.requested_at, ex.started_at, ex.ended_at, ex.outcome,
               CASE WHEN v_files AND ex.kind = 'remote' THEN (SELECT pg_catalog.count(*) FROM public.portal_client_files(p_organization_id, s.o_client_membership_id) f
                                                               WHERE f.execution_id = ex.id AND f.available)
                    WHEN v_files THEN 0::bigint END
          FROM public.portal_client_executions(p_organization_id, s.o_client_membership_id) ex
         WHERE ex.automation_id = p_automation_id AND ex.requested_at >= p_from AND ex.requested_at < p_to
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

-- arquivos de retorno no escopo (todas as automacoes com arquivos ou uma delas), por periodo da execucao e busca no nome; da execucao
-- mais recente. Cursor (instante da execucao, tentativa, numero).
CREATE FUNCTION public.portal_client_list_files(p_organization_id UUID, p_identity_token TEXT, p_automation_id UUID, p_from TIMESTAMPTZ,
    p_to TIMESTAMPTZ, p_query TEXT, p_after_at TIMESTAMPTZ, p_after_attempt UUID, p_after_number INTEGER, p_limit INTEGER)
RETURNS TABLE (attempt_id UUID, result_number SMALLINT, automation_id UUID, automation_name TEXT, execution_requested_at TIMESTAMPTZ,
               file_name TEXT, size_bytes BIGINT, received_at TIMESTAMPTZ, available BOOLEAN)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
#variable_conflict use_column
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    s RECORD;
    v_like TEXT;
BEGIN
    BEGIN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(p_organization_id::text, ''), true);
        SELECT * INTO s FROM public.portal_client_resolve(p_organization_id, p_identity_token);
        IF p_limit IS NULL OR p_limit < 1 OR p_limit > 101 OR (p_after_at IS NULL) <> (p_after_attempt IS NULL)
           OR (p_after_at IS NULL) <> (p_after_number IS NULL)
           OR p_from IS NULL OR p_to IS NULL OR p_to <= p_from OR p_to - p_from > interval '400 days'
           OR pg_catalog.length(coalesce(p_query, '')) > 100 THEN
            RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'pagina invalida';
        END IF;
        IF p_automation_id IS NOT NULL THEN
            PERFORM public.portal_client_require(p_organization_id, s.o_client_membership_id, p_automation_id, 'files');
        END IF;
        v_like := CASE WHEN coalesce(p_query, '') = '' THEN NULL
                       ELSE '%' || pg_catalog.replace(pg_catalog.replace(pg_catalog.replace(pg_catalog.lower(p_query), '\', '\\'), '%', '\%'), '_', '\_') || '%' END;
        RETURN QUERY
        SELECT f.attempt_id, f.result_number, f.automation_id, a.name, f.execution_requested_at, f.file_name, f.size_bytes, f.received_at, f.available
          FROM public.portal_client_files(p_organization_id, s.o_client_membership_id) f
          JOIN public.automations a ON a.organization_id = p_organization_id AND a.automation_id = f.automation_id
         WHERE (p_automation_id IS NULL OR f.automation_id = p_automation_id)
           AND f.execution_requested_at >= p_from AND f.execution_requested_at < p_to
           AND (v_like IS NULL OR pg_catalog.lower(f.file_name) LIKE v_like)
           AND (p_after_at IS NULL OR (f.execution_requested_at, f.attempt_id, f.result_number::integer) < (p_after_at, p_after_attempt, p_after_number))
         ORDER BY f.execution_requested_at DESC, f.attempt_id DESC, f.result_number DESC
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

-- UM arquivo de retorno para baixar/ver: so no escopo de arquivos (fora dele: NK403 se a automacao e compartilhada so com dashboard,
-- NK404 nos demais casos)
CREATE FUNCTION public.portal_client_get_file(p_organization_id UUID, p_identity_token TEXT, p_attempt_id UUID, p_number INTEGER)
RETURNS TABLE (file_name TEXT, sha256 TEXT, available BOOLEAN)
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
    v_automation UUID;
BEGIN
    BEGIN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(p_organization_id::text, ''), true);
        SELECT * INTO s FROM public.portal_client_resolve(p_organization_id, p_identity_token);
        RETURN QUERY
        SELECT f.file_name, f.sha256, f.available FROM public.portal_client_files(p_organization_id, s.o_client_membership_id) f
         WHERE f.attempt_id = p_attempt_id AND f.result_number = p_number;
        GET DIAGNOSTICS v_found = ROW_COUNT;
        IF v_found = 0 THEN
            SELECT x.automation_id INTO v_automation FROM public.remote_attempts z
              JOIN public.remote_executions x ON x.organization_id = z.organization_id AND x.remote_execution_id = z.remote_execution_id
             WHERE z.organization_id = p_organization_id AND z.attempt_id = p_attempt_id;
            IF v_automation IS NOT NULL AND EXISTS (SELECT 1 FROM public.portal_client_scope(p_organization_id, s.o_client_membership_id) g
                                                     WHERE g.automation_id = v_automation AND NOT g.can_download_results) THEN
                RAISE EXCEPTION USING ERRCODE = 'NK403', MESSAGE = 'sem o conteudo';
            END IF;
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

ALTER FUNCTION public.portal_client_scope(UUID, UUID) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_client_executions(UUID, UUID) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_client_files(UUID, UUID) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_client_require(UUID, UUID, UUID, TEXT) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_client_automations(UUID, TEXT) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_client_summary(UUID, TEXT, UUID, TIMESTAMPTZ, TIMESTAMPTZ) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_client_list_executions(UUID, TEXT, UUID, TIMESTAMPTZ, TIMESTAMPTZ, TIMESTAMPTZ, UUID, INTEGER) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_client_list_files(UUID, TEXT, UUID, TIMESTAMPTZ, TIMESTAMPTZ, TEXT, TIMESTAMPTZ, UUID, INTEGER, INTEGER) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_client_get_file(UUID, TEXT, UUID, INTEGER) OWNER TO nekkora_portal_owner;
REVOKE ALL ON FUNCTION public.portal_client_scope(UUID, UUID), public.portal_client_executions(UUID, UUID), public.portal_client_files(UUID, UUID),
    public.portal_client_require(UUID, UUID, UUID, TEXT), public.portal_client_automations(UUID, TEXT),
    public.portal_client_summary(UUID, TEXT, UUID, TIMESTAMPTZ, TIMESTAMPTZ),
    public.portal_client_list_executions(UUID, TEXT, UUID, TIMESTAMPTZ, TIMESTAMPTZ, TIMESTAMPTZ, UUID, INTEGER),
    public.portal_client_list_files(UUID, TEXT, UUID, TIMESTAMPTZ, TIMESTAMPTZ, TEXT, TIMESTAMPTZ, UUID, INTEGER, INTEGER),
    public.portal_client_get_file(UUID, TEXT, UUID, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.portal_client_automations(UUID, TEXT), public.portal_client_summary(UUID, TEXT, UUID, TIMESTAMPTZ, TIMESTAMPTZ),
    public.portal_client_list_executions(UUID, TEXT, UUID, TIMESTAMPTZ, TIMESTAMPTZ, TIMESTAMPTZ, UUID, INTEGER),
    public.portal_client_list_files(UUID, TEXT, UUID, TIMESTAMPTZ, TIMESTAMPTZ, TEXT, TIMESTAMPTZ, UUID, INTEGER, INTEGER),
    public.portal_client_get_file(UUID, TEXT, UUID, INTEGER) TO nekkora_portal;

-- === 10. Invariantes ===
DO $$
DECLARE
    v_bad TEXT;
BEGIN
    IF EXISTS (SELECT 1 FROM public.client_automation_grants WHERE status IN (1, 2) AND can_read_logs)
       OR EXISTS (SELECT 1 FROM public.identity_invitations WHERE accepted_at IS NULL AND revoked_at IS NULL AND can_read_logs) THEN
        RAISE EXCEPTION '071: sobrou permissao de logs em item aberto';
    END IF;
    IF has_any_column_privilege('nekkora_portal_owner', 'public.remote_attempt_log_lines', 'SELECT')
       OR has_any_column_privilege('nekkora_portal_owner', 'public.remote_attempt_log_closures', 'SELECT') THEN
        RAISE EXCEPTION '071: o owner do Portal ainda le registros tecnicos';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'public' AND p.proname IN ('portal_client_list_logs', 'portal_client_list_results', 'portal_client_attempt',
                                                              'portal_client_get_execution')) THEN
        RAISE EXCEPTION '071: funcao de detalhe tecnico do Portal ainda existe';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.client_share_requests', 'DELETE, TRUNCATE')
       OR has_column_privilege('nekkora_platform', 'public.client_share_requests', 'automation_id', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.client_share_requests', 'email_normalized', 'UPDATE') THEN
        RAISE EXCEPTION '071: a Platform nao apaga nem muda a origem do pedido';
    END IF;
    SELECT string_agg(c.relname, ', ') INTO v_bad
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relkind IN ('r', 'v', 'm', 'p', 'f')
       AND (has_table_privilege('nekkora_portal', c.oid, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
            OR has_any_column_privilege('nekkora_portal', c.oid, 'SELECT, INSERT, UPDATE, REFERENCES'));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION '071: nekkora_portal com privilegio de tabela/coluna: %', v_bad;
    END IF;
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND (p.proname LIKE 'portal\_client\_%' OR p.proname = 'identity_invitation_preview') AND p.prosecdef
       AND (p.proconfig IS NULL OR NOT EXISTS (SELECT 1 FROM unnest(p.proconfig) c WHERE c LIKE 'search_path=%')
            OR has_function_privilege('public', p.oid, 'EXECUTE'));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION '071: SECURITY DEFINER sem search_path fixo ou com EXECUTE publico: %', v_bad;
    END IF;
END;
$$;

COMMIT;
