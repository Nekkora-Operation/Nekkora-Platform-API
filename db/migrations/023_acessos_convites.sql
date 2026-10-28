-- Nekkora Platform - CONVITES, autoridade sobre papeis e concessoes, e AUDITORIA de acesso (missao de consolidacao,
-- acessos e recuperacao operacional, frente B). Contrato: docs/relatorios/2026-10-27-acessos-convites-papeis-concessoes-contrato.md.
--
-- CANDIDATA ate a aprovacao do relatorio da suite db/tests/acessos-023.
--
-- O que muda:
--   * identity_invitations (002, sem uso ate aqui) ganha o modelo do convite: emissor pelo VINCULO, no maximo um papel
--     (ORG_ADMIN, ou papel de workspace com o workspace), motivo da revogacao (1=REVOGADO, 2=REENVIADO, 3=EXPIRADO),
--     cadeia de reenvio e a identidade que aceitou. Um convite aberto por (organizacao, e-mail): indice unico parcial.
--     Prazo de no maximo 7 dias (gatilho na insercao). Linha aberta so muda para aceita ou revogada; aceita ou revogada
--     fica congelada; a origem e imutavel. Sem DELETE nem TRUNCATE;
--   * AUTORIDADE no banco (gatilhos SECURITY INVOKER): emitir convite exige vinculo e conta do emissor ativos, org.manage e
--     a permissao de atribuir o papel do convite; atribuir papel exige quem atribui diferente do alvo (sem autoatribuicao),
--     ativo e com a permissao do papel (role.assign_admin_roles para ORG_ADMIN e WORKSPACE_ADMIN; role.assign_operator_viewer
--     organizacional, ou de WORKSPACE_ADMIN no MESMO workspace, para OPERATOR e VIEWER); revogar papel exige a mesma
--     autoridade de quem revoga; aprovar, renovar, revisar e revogar concessao exige external_access.manage. O superusuario
--     (administracao do cluster, fixtures declaradas de teste) nao e barrado, como na 022. O bootstrap do primeiro
--     ORG_ADMIN (responsavel nulo) continua restrito ao provisionamento (003/012) e nao passa por aqui;
--   * public.access_events: auditoria imutavel dos atos de acesso (tabela de tenant, RLS da 012);
--   * identity_users.link_channel aceita 'convite';
--   * concessao PENDENTE pode ser revogada (cancelada) sem aprovacao: a 003 exigia approved_at em todo estado != PENDENTE e a
--     revogacao de uma pendente violava o CHECK (defeito da rota de revogacao da GAP-01, 500);
--   * public.identity_accept_invitation: o aceite (SECURITY DEFINER, dono nekkora_identity_owner, EXECUTE so da Platform),
--     no padrao da vinculacao (017): identidade -> sessao -> organizacao -> convite -> contas, hora medida DEPOIS dos locks,
--     prova de step-up da identidade, e-mail do convite = e-mail da identidade, organizacao ATIVA e autoridade do emissor
--     reconferida NO ACEITE. Nenhuma vinculacao de conta existente por igualdade de e-mail.
--
-- Ordem global de locks (017): identidade -> sessao de identidade -> organizacao -> conta. O aceite trava a organizacao
-- FOR UPDATE antes do convite e das contas; todo ato que tira autoridade (revogar papel, retirar vinculo, suspender conta)
-- trava a organizacao primeiro (disciplina da 003). Os dois serializam na organizacao.
--
-- ELEVACAO: exige SUPERUSER na janela (ALTER FUNCTION ... OWNER TO nekkora_identity_owner, papel sem membros). Depende de
-- 001-022. Atomica: uma transacao.

BEGIN;

-- === 1. Pre-condicao: 022 aplicada; objetos novos ausentes; nenhum convite aberto duplicado ===
DO $$
BEGIN
    IF to_regclass('ops_activation.organization_states') IS NULL THEN
        RAISE EXCEPTION '023 exige a 022 aplicada';
    END IF;
    IF to_regclass('public.access_events') IS NOT NULL
       OR to_regprocedure('public.identity_accept_invitation(text,text,uuid)') IS NOT NULL
       OR EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'public.identity_invitations'::regclass
                   AND attname = 'issued_by_membership_id' AND NOT attisdropped) THEN
        RAISE EXCEPTION '023: objetos dos convites ja existem';
    END IF;
    IF (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'identity_users_link_channel_check'
         AND conrelid = 'public.identity_users'::regclass) IS DISTINCT FROM
       'CHECK ((link_channel = ANY (ARRAY[''cadastro_web''::text, ''vinculacao''::text])))' THEN
        RAISE EXCEPTION '023: restricao de canal de identity_users divergente da 017';
    END IF;
    IF EXISTS (SELECT 1 FROM public.identity_invitations WHERE accepted_at IS NULL AND revoked_at IS NULL
               GROUP BY organization_id, email_normalized HAVING count(*) > 1) THEN
        RAISE EXCEPTION '023: ha convites abertos duplicados por organizacao e e-mail (revogue os excedentes antes)';
    END IF;
END;
$$;

-- === 2. Autoridade: consultas sobre o estado atual (SECURITY INVOKER; sem EXECUTE para PUBLIC) ===

-- vinculo efetivo: vinculo interno ativo e conta ativa
CREATE FUNCTION public.access_membership_effective(p_org UUID, p_membership UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT EXISTS (SELECT 1 FROM public.organization_memberships m
                     JOIN public.users u ON u.organization_id = m.organization_id AND u.user_id = m.user_id
                    WHERE m.organization_id = p_org AND m.membership_id = p_membership AND m.status = 2 AND u.status = 2)
$$;

-- permissao ORGANIZACIONAL (atribuicao ativa de papel de organizacao) de um vinculo efetivo
CREATE FUNCTION public.access_org_permission(p_org UUID, p_membership UUID, p_permission TEXT) RETURNS BOOLEAN
LANGUAGE sql STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT public.access_membership_effective(p_org, p_membership)
       AND EXISTS (SELECT 1 FROM public.organization_role_assignments a
                     JOIN public.role_permissions rp ON rp.role_id = a.role_id
                     JOIN public.permissions p ON p.permission_id = rp.permission_id
                    WHERE a.organization_id = p_org AND a.membership_id = p_membership AND a.status = 1 AND p.code = p_permission)
$$;

-- autoridade para atribuir ou revogar o papel p_role (no workspace p_workspace, se de workspace)
CREATE FUNCTION public.access_can_manage_role(p_org UUID, p_membership UUID, p_role UUID, p_workspace UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT CASE (SELECT r.code FROM public.roles r WHERE r.role_id = p_role)
        WHEN 'ORG_ADMIN' THEN public.access_org_permission(p_org, p_membership, 'role.assign_admin_roles')
        WHEN 'WORKSPACE_ADMIN' THEN public.access_org_permission(p_org, p_membership, 'role.assign_admin_roles')
        WHEN 'OPERATOR' THEN public.access_org_permission(p_org, p_membership, 'role.assign_operator_viewer')
             OR (p_workspace IS NOT NULL AND public.access_membership_effective(p_org, p_membership)
                 AND EXISTS (SELECT 1 FROM public.workspace_role_assignments w
                               JOIN public.role_permissions rp ON rp.role_id = w.role_id
                               JOIN public.permissions p ON p.permission_id = rp.permission_id
                              WHERE w.organization_id = p_org AND w.workspace_id = p_workspace AND w.membership_id = p_membership
                                AND w.status = 1 AND p.code = 'role.assign_operator_viewer'))
        WHEN 'VIEWER' THEN public.access_org_permission(p_org, p_membership, 'role.assign_operator_viewer')
             OR (p_workspace IS NOT NULL AND public.access_membership_effective(p_org, p_membership)
                 AND EXISTS (SELECT 1 FROM public.workspace_role_assignments w
                               JOIN public.role_permissions rp ON rp.role_id = w.role_id
                               JOIN public.permissions p ON p.permission_id = rp.permission_id
                              WHERE w.organization_id = p_org AND w.workspace_id = p_workspace AND w.membership_id = p_membership
                                AND w.status = 1 AND p.code = 'role.assign_operator_viewer'))
        ELSE false END
$$;

-- emissor de convite: org.manage e, se o convite leva papel, autoridade sobre ele
CREATE FUNCTION public.access_can_invite(p_org UUID, p_membership UUID, p_role UUID, p_workspace UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT public.access_org_permission(p_org, p_membership, 'org.manage')
       AND (p_role IS NULL OR public.access_can_manage_role(p_org, p_membership, p_role, p_workspace))
$$;

-- === 3. Convites ===
ALTER TABLE public.identity_invitations
    ADD COLUMN issued_by_membership_id  UUID,
    ADD COLUMN role_id                  UUID,
    ADD COLUMN role_scope               SMALLINT,
    ADD COLUMN workspace_id             UUID,
    ADD COLUMN revoked_by_membership_id UUID,
    ADD COLUMN revoke_reason            SMALLINT CHECK (revoke_reason IN (1, 2, 3)),
    ADD COLUMN replaces_invitation_id   UUID REFERENCES public.identity_invitations (invitation_id) ON DELETE RESTRICT,
    ADD COLUMN accepted_identity_id     UUID REFERENCES public.identities (identity_id) ON DELETE RESTRICT,
    ADD CONSTRAINT identity_invitations_issuer_fkey FOREIGN KEY (organization_id, issued_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    ADD CONSTRAINT identity_invitations_revoker_fkey FOREIGN KEY (organization_id, revoked_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    ADD CONSTRAINT identity_invitations_role_fkey FOREIGN KEY (role_id, role_scope)
        REFERENCES public.roles (role_id, scope) ON DELETE RESTRICT,
    ADD CONSTRAINT identity_invitations_workspace_fkey FOREIGN KEY (organization_id, workspace_id)
        REFERENCES public.workspaces (organization_id, workspace_id) ON DELETE RESTRICT,
    -- papel: so em convite interno; papel de workspace exige o workspace, papel de organizacao nao aceita
    ADD CONSTRAINT identity_invitations_role_check CHECK ((role_id IS NULL) = (role_scope IS NULL)
        AND (role_id IS NULL OR invited_account_type = 1)
        AND (workspace_id IS NULL OR role_scope = 2)
        AND (role_scope IS DISTINCT FROM 2 OR workspace_id IS NOT NULL)),
    -- campos da revogacao e do aceite so com a transicao correspondente
    ADD CONSTRAINT identity_invitations_revoke_check CHECK (revoked_at IS NOT NULL OR (revoke_reason IS NULL AND revoked_by_membership_id IS NULL)),
    ADD CONSTRAINT identity_invitations_accept_check CHECK (accepted_identity_id IS NULL OR accepted_at IS NOT NULL);

COMMENT ON COLUMN public.identity_invitations.revoke_reason IS '1=REVOGADO, 2=REENVIADO (substituido por outro convite), 3=EXPIRADO (fechado ao emitir outro)';
COMMENT ON COLUMN public.identity_invitations.issued_by_membership_id IS 'vinculo do emissor (autoridade conferida na emissao e de novo no aceite); nulo so em linha anterior a 023';

CREATE UNIQUE INDEX ux_identity_invitations_open ON public.identity_invitations (organization_id, email_normalized)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;
CREATE UNIQUE INDEX ux_identity_invitations_replaces ON public.identity_invitations (replaces_invitation_id)
    WHERE replaces_invitation_id IS NOT NULL;

CREATE FUNCTION public.access_invitation_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF coalesce((SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.rolname = current_user), false) THEN
        RETURN NEW;
    END IF;
    IF NEW.invited_account_type <> 1 THEN
        RAISE EXCEPTION 'convite de conta externa fora do modelo atual (D-EXT)' USING ERRCODE = '42501';
    END IF;
    IF NEW.accepted_at IS NOT NULL OR NEW.revoked_at IS NOT NULL OR NEW.accepted_user_id IS NOT NULL OR NEW.accepted_identity_id IS NOT NULL THEN
        RAISE EXCEPTION 'convite nasce aberto' USING ERRCODE = '23514';
    END IF;
    IF NEW.expires_at > NEW.created_at + interval '7 days' THEN
        RAISE EXCEPTION 'prazo do convite acima de 7 dias' USING ERRCODE = '23514';
    END IF;
    IF NEW.issued_by_membership_id IS NULL
       OR NOT EXISTS (SELECT 1 FROM public.organization_memberships m WHERE m.organization_id = NEW.organization_id
                       AND m.membership_id = NEW.issued_by_membership_id AND m.user_id = NEW.issued_by)
       OR NOT public.access_can_invite(NEW.organization_id, NEW.issued_by_membership_id, NEW.role_id, NEW.workspace_id) THEN
        RAISE EXCEPTION 'emissor sem autoridade para o convite' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.access_invitation_on_update() RETURNS TRIGGER
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
       OR NEW.replaces_invitation_id IS DISTINCT FROM OLD.replaces_invitation_id THEN
        RAISE EXCEPTION 'origem do convite e imutavel (invitation_id=%)', OLD.invitation_id;
    END IF;
    IF NEW.accepted_at IS NULL AND NEW.revoked_at IS NULL THEN
        RAISE EXCEPTION 'convite aberto so muda para aceito ou revogado (invitation_id=%)', OLD.invitation_id;
    END IF;
    IF coalesce((SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.rolname = current_user), false) THEN
        RETURN NEW;
    END IF;
    IF NEW.accepted_at IS NOT NULL THEN
        -- o aceite so existe pela funcao de aceite (dono das funcoes de identidade)
        IF current_user <> 'nekkora_identity_owner' OR NEW.accepted_identity_id IS NULL THEN
            RAISE EXCEPTION 'aceite de convite so pela funcao de aceite' USING ERRCODE = '42501';
        END IF;
    ELSIF NEW.revoked_by_membership_id IS NULL OR NEW.revoke_reason IS NULL
          OR NOT public.access_org_permission(NEW.organization_id, NEW.revoked_by_membership_id, 'org.manage') THEN
        RAISE EXCEPTION 'revogacao de convite sem autoridade' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_identity_invitations_on_insert BEFORE INSERT ON public.identity_invitations
    FOR EACH ROW EXECUTE FUNCTION public.access_invitation_on_insert();
CREATE TRIGGER trg_identity_invitations_on_update BEFORE UPDATE ON public.identity_invitations
    FOR EACH ROW EXECUTE FUNCTION public.access_invitation_on_update();
CREATE TRIGGER trg_identity_invitations_prevent_delete BEFORE DELETE ON public.identity_invitations
    FOR EACH ROW EXECUTE FUNCTION prevent_delete();
CREATE TRIGGER trg_identity_invitations_prevent_truncate BEFORE TRUNCATE ON public.identity_invitations
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_truncate();

-- === 4. Autoridade sobre papeis ===
CREATE FUNCTION public.access_role_assignment_guard() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_workspace UUID;
BEGIN
    IF coalesce((SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.rolname = current_user), false) THEN
        RETURN NEW;
    END IF;
    IF TG_TABLE_NAME = 'workspace_role_assignments' THEN
        v_workspace := NEW.workspace_id;
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.assigned_by_membership_id IS NULL THEN
            RETURN NEW;   -- bootstrap do primeiro ORG_ADMIN: regras da 003/012 (so o provisionamento grava)
        END IF;
        IF NEW.assigned_by_membership_id = NEW.membership_id THEN
            RAISE EXCEPTION 'autoatribuicao de papel recusada' USING ERRCODE = '42501';
        END IF;
        IF NOT public.access_membership_effective(NEW.organization_id, NEW.membership_id) THEN
            RAISE EXCEPTION 'papel so para vinculo ativo de conta ativa' USING ERRCODE = '23514';
        END IF;
        IF NOT public.access_can_manage_role(NEW.organization_id, NEW.assigned_by_membership_id, NEW.role_id, v_workspace) THEN
            RAISE EXCEPTION 'atribuicao de papel sem autoridade' USING ERRCODE = '42501';
        END IF;
    ELSIF OLD.status = 1 AND NEW.status = 2 THEN
        IF NEW.revoked_by_membership_id IS NULL
           OR NOT public.access_can_manage_role(NEW.organization_id, NEW.revoked_by_membership_id, NEW.role_id, v_workspace) THEN
            RAISE EXCEPTION 'revogacao de papel sem autoridade' USING ERRCODE = '42501';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_org_role_assignments_authority BEFORE INSERT OR UPDATE ON public.organization_role_assignments
    FOR EACH ROW EXECUTE FUNCTION public.access_role_assignment_guard();
CREATE TRIGGER trg_workspace_role_assignments_authority BEFORE INSERT OR UPDATE ON public.workspace_role_assignments
    FOR EACH ROW EXECUTE FUNCTION public.access_role_assignment_guard();

-- === 5. Autoridade sobre concessoes ===
CREATE FUNCTION public.access_grant_guard() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_actor UUID;
BEGIN
    IF TG_TABLE_NAME = 'client_workspace_grant_approvals' THEN
        v_actor := NEW.approved_by_membership_id;
    ELSIF TG_TABLE_NAME = 'client_workspace_grant_reviews' THEN
        v_actor := NEW.reviewed_by_membership_id;
    ELSIF NEW.status = 4 AND OLD.status <> 4 THEN
        v_actor := NEW.revoked_by_membership_id;
    ELSIF (NEW.status = 2 AND OLD.status <> 2) OR NEW.approved_by_membership_id IS DISTINCT FROM OLD.approved_by_membership_id THEN
        v_actor := NEW.approved_by_membership_id;   -- aprovacao, renovacao ou reativacao: o aprovador gravado tem de ter a permissao
    ELSE
        -- ultimo acesso pelo Portal; suspensao e extensao de prazo (KEEP) sem coluna de autor na concessao: o autor e o da
        -- linha de revisao gravada na mesma transacao, conferida por este mesmo gatilho na tabela de revisoes
        RETURN NEW;
    END IF;
    IF coalesce((SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.rolname = current_user), false) THEN
        RETURN NEW;
    END IF;
    IF v_actor IS NULL OR NOT public.access_org_permission(NEW.organization_id, v_actor, 'external_access.manage') THEN
        RAISE EXCEPTION 'ato sobre concessao externa sem autoridade' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_client_workspace_grants_authority BEFORE UPDATE ON public.client_workspace_grants
    FOR EACH ROW EXECUTE FUNCTION public.access_grant_guard();
CREATE TRIGGER trg_client_workspace_grant_approvals_authority BEFORE INSERT ON public.client_workspace_grant_approvals
    FOR EACH ROW EXECUTE FUNCTION public.access_grant_guard();
CREATE TRIGGER trg_client_workspace_grant_reviews_authority BEFORE INSERT ON public.client_workspace_grant_reviews
    FOR EACH ROW EXECUTE FUNCTION public.access_grant_guard();

-- Concessao PENDENTE revogada (cancelada antes da aprovacao): a 003 exigia approved_at em todo estado diferente de PENDENTE,
-- entao a revogacao de uma pendente violava o CHECK (a rota da GAP-01 respondia 500) e a pendente bloqueava para sempre uma
-- nova pelo indice de nao revogadas. REVOGADA passa a aceitar approved_at nulo; ATIVA e SUSPENSA continuam exigindo aprovacao.
DO $$
DECLARE
    v_con TEXT;
BEGIN
    SELECT string_agg(conname, ',') INTO v_con FROM pg_constraint
     WHERE conrelid = 'public.client_workspace_grants'::regclass AND contype = 'c'
       AND pg_get_constraintdef(oid) = 'CHECK (((status = 1) OR (approved_at IS NOT NULL)))';
    IF v_con IS NULL OR v_con LIKE '%,%' THEN
        RAISE EXCEPTION '023: restricao de aprovacao de client_workspace_grants divergente da 003: %', v_con;
    END IF;
    EXECUTE format('ALTER TABLE public.client_workspace_grants DROP CONSTRAINT %I', v_con);
END;
$$;
ALTER TABLE public.client_workspace_grants ADD CONSTRAINT client_workspace_grants_approval_check
    CHECK (status IN (1, 4) OR approved_at IS NOT NULL);

-- === 6. Auditoria de acesso ===
-- Codigos de access_events.event_type (permanentes - nao reordenar nem reutilizar):
--   1=CONVITE_EMITIDO 2=CONVITE_REVOGADO 3=CONVITE_REENVIADO 4=CONVITE_ACEITO 5=PAPEL_ATRIBUIDO 6=PAPEL_REVOGADO
--   7=PAPEL_ALTERADO 8=VINCULO_RETIRADO 9=CONCESSAO_CRIADA 10=CONCESSAO_APROVADA 11=CONCESSAO_RENOVADA
--   12=CONCESSAO_REVISADA 13=CONCESSAO_REVOGADA
CREATE TABLE public.access_events (
    event_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id      UUID NOT NULL REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    event_type           SMALLINT NOT NULL CHECK (event_type BETWEEN 1 AND 13),
    actor_membership_id  UUID,
    actor_user_id        UUID REFERENCES public.users (user_id) ON DELETE RESTRICT,
    actor_identity_id    UUID REFERENCES public.identities (identity_id) ON DELETE RESTRICT,
    target_user_id       UUID REFERENCES public.users (user_id) ON DELETE RESTRICT,
    target_membership_id UUID,
    role_id              UUID REFERENCES public.roles (role_id) ON DELETE RESTRICT,
    previous_role_id     UUID REFERENCES public.roles (role_id) ON DELETE RESTRICT,
    workspace_id         UUID,
    invitation_id        UUID REFERENCES public.identity_invitations (invitation_id) ON DELETE RESTRICT,
    assignment_id        UUID,
    grant_id             UUID,
    decision             SMALLINT CHECK (decision IN (1, 2, 3)),
    correlation_id       UUID NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (organization_id, actor_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, target_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, workspace_id) REFERENCES public.workspaces (organization_id, workspace_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, grant_id) REFERENCES public.client_workspace_grants (organization_id, grant_id) ON DELETE RESTRICT,
    -- autoria: um vinculo da organizacao (atos de administracao) ou a identidade (aceite de convite)
    CHECK ((actor_membership_id IS NOT NULL AND actor_user_id IS NOT NULL) <> (actor_identity_id IS NOT NULL)),
    CHECK ((event_type = 4) = (actor_identity_id IS NOT NULL)),
    CHECK ((decision IS NOT NULL) = (event_type = 12)),
    CHECK (event_type NOT BETWEEN 1 AND 4 OR invitation_id IS NOT NULL),
    CHECK (event_type NOT BETWEEN 9 AND 13 OR grant_id IS NOT NULL)
);

COMMENT ON COLUMN public.access_events.event_type IS '1..13: ver cabecalho da 023';
COMMENT ON COLUMN public.access_events.decision IS 'so na revisao de concessao: 1=KEEP, 2=SUSPEND, 3=REVOKE';

CREATE INDEX ix_access_events_organization_created ON public.access_events (organization_id, created_at, event_id);

CREATE TRIGGER trg_access_events_prevent_update BEFORE UPDATE ON public.access_events
    FOR EACH ROW EXECUTE FUNCTION prevent_update();
CREATE TRIGGER trg_access_events_prevent_delete BEFORE DELETE ON public.access_events
    FOR EACH ROW EXECUTE FUNCTION prevent_delete();
CREATE TRIGGER trg_access_events_prevent_truncate BEFORE TRUNCATE ON public.access_events
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_truncate();

ALTER TABLE public.access_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.access_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_request_context ON public.access_events AS PERMISSIVE FOR ALL TO nekkora_platform
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid);
CREATE POLICY identity_function_context ON public.access_events AS PERMISSIVE FOR ALL TO nekkora_identity_owner
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.fn_organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(pg_catalog.current_setting('nekkora.fn_organization_id', true), '')::uuid);

-- === 7. Canal 'convite' na ligacao identidade-conta ===
ALTER TABLE public.identity_users DROP CONSTRAINT identity_users_link_channel_check;
ALTER TABLE public.identity_users ADD CONSTRAINT identity_users_link_channel_check
    CHECK (link_channel IN ('cadastro_web', 'vinculacao', 'convite'));

-- === 8. Privilegios e RLS do aceite (dono das funcoes de identidade) ===
GRANT SELECT (invitation_id, organization_id, invited_account_type, email_normalized, token_hash, expires_at, accepted_at,
              revoked_at, issued_by_membership_id, role_id, role_scope, workspace_id),
      UPDATE (accepted_at, accepted_user_id, accepted_identity_id)
    ON public.identity_invitations TO nekkora_identity_owner;
GRANT SELECT (assignment_id), INSERT (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id)
    ON public.organization_role_assignments TO nekkora_identity_owner;   -- SELECT (assignment_id): o RETURNING do aceite
GRANT SELECT (assignment_id, organization_id, workspace_id, membership_id, role_id, status),
      INSERT (organization_id, workspace_id, membership_id, role_id, scope, status, assigned_by_membership_id)
    ON public.workspace_role_assignments TO nekkora_identity_owner;
GRANT UPDATE (status) ON public.organization_memberships TO nekkora_identity_owner;   -- readmissao (vinculo REVOKED -> ACTIVE)
GRANT SELECT ON public.role_permissions, public.permissions TO nekkora_identity_owner;   -- catalogo global, sem RLS
GRANT INSERT ON public.access_events TO nekkora_identity_owner;
GRANT SELECT, INSERT ON public.access_events TO nekkora_platform;

CREATE POLICY identity_function_context ON public.identity_invitations AS PERMISSIVE FOR ALL TO nekkora_identity_owner
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.fn_organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(pg_catalog.current_setting('nekkora.fn_organization_id', true), '')::uuid);
CREATE POLICY identity_function_context ON public.workspace_role_assignments AS PERMISSIVE FOR ALL TO nekkora_identity_owner
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.fn_organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(pg_catalog.current_setting('nekkora.fn_organization_id', true), '')::uuid);

-- === 9. Aceite do convite ===
-- Resultado: OK | INVALIDO (sem efeito, sem distinguir o motivo) | JA_MEMBRO | CONTA_EXISTENTE | CONTA_INDISPONIVEL
-- (os tres ultimos so depois de provada a destinataria: sessao com step-up e e-mail do convite = e-mail da identidade).
CREATE FUNCTION public.identity_accept_invitation_impl(p_session_token TEXT, p_token TEXT, p_organization_id UUID)
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
    v_same_email UUID;
    v_membership_status SMALLINT;
    v_t TIMESTAMPTZ;
    v_user UUID;
    v_membership UUID;
    v_assignment UUID;
    v_n INTEGER;
BEGIN
    PERFORM public.identity_require_read_committed();
    IF v_hash IS NULL OR p_organization_id IS NULL THEN
        RETURN 'INVALIDO';
    END IF;
    SELECT * INTO s FROM public.identity_session_resolve(p_session_token);   -- trava identidade e sessao
    IF s.o_identity_id IS NULL THEN
        RETURN 'INVALIDO';
    END IF;
    SELECT x.email, x.email_normalized, x.display_name, x.email_verified_at INTO i FROM public.identities x
     WHERE x.identity_id = s.o_identity_id AND x.status = 2 AND x.email_verified_at IS NOT NULL FOR UPDATE;
    IF i.email IS NULL THEN
        RETURN 'INVALIDO';
    END IF;
    -- a organizacao chega ANTES do token: o hash so e procurado dentro dela
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', '', true);
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    PERFORM 1 FROM public.organizations o WHERE o.organization_id = p_organization_id AND o.status = 1 FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'INVALIDO';
    END IF;
    SELECT x.invitation_id, x.email_normalized, x.expires_at, x.issued_by_membership_id, x.role_id, x.role_scope, x.workspace_id
      INTO c
      FROM public.identity_invitations x
     WHERE x.organization_id = p_organization_id AND x.token_hash = v_hash AND x.invited_account_type = 1
       AND x.accepted_at IS NULL AND x.revoked_at IS NULL
       FOR UPDATE;
    IF c.invitation_id IS NULL THEN
        RETURN 'INVALIDO';
    END IF;
    -- contas da organizacao que importam ao caso, travadas agora (ordem: organizacao -> conta)
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', s.o_identity_id::text, true);
    SELECT u.user_id, u.status INTO v_linked
      FROM public.identity_users iu JOIN public.users u ON u.user_id = iu.user_id
     WHERE iu.identity_id = s.o_identity_id AND u.organization_id = p_organization_id
       FOR UPDATE OF u;
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', '', true);
    SELECT u.user_id INTO v_same_email FROM public.users u
     WHERE u.organization_id = p_organization_id AND u.email_normalized = c.email_normalized FOR UPDATE;
    IF v_linked.user_id IS NOT NULL THEN
        SELECT m.status INTO v_membership_status FROM public.organization_memberships m
         WHERE m.organization_id = p_organization_id AND m.user_id = v_linked.user_id FOR UPDATE;
    END IF;

    -- hora medida DEPOIS de todos os locks; provas reconferidas com ela
    v_t := pg_catalog.clock_timestamp();
    IF NOT public.identity_session_proof(s.o_session_id, v_t, 'step_up')
       OR c.expires_at <= v_t
       OR c.email_normalized <> i.email_normalized
       OR c.issued_by_membership_id IS NULL
       OR NOT public.access_can_invite(p_organization_id, c.issued_by_membership_id, c.role_id, c.workspace_id) THEN
        RETURN 'INVALIDO';
    END IF;

    IF v_linked.user_id IS NOT NULL THEN
        IF v_membership_status = 2 THEN
            RETURN 'JA_MEMBRO';
        END IF;
        IF v_membership_status IS DISTINCT FROM 4 OR v_linked.status <> 2 THEN
            RETURN 'CONTA_INDISPONIVEL';
        END IF;
        -- readmissao: vinculo retirado volta a ativo; atribuicoes antigas continuam revogadas
        UPDATE public.organization_memberships SET status = 2
         WHERE organization_id = p_organization_id AND user_id = v_linked.user_id AND status = 4
        RETURNING membership_id INTO v_membership;
        v_user := v_linked.user_id;
    ELSIF v_same_email IS NOT NULL THEN
        RETURN 'CONTA_EXISTENTE';   -- conta da organizacao nao ligada a esta identidade: nunca vinculada por e-mail
    ELSE
        INSERT INTO public.users (organization_id, account_type, display_name, email, email_verified_at, status)
        VALUES (p_organization_id, 1, i.display_name, i.email, i.email_verified_at, 2)
        RETURNING user_id INTO v_user;
        INSERT INTO public.organization_memberships (organization_id, user_id, account_type, status)
        VALUES (p_organization_id, v_user, 1, 2)
        RETURNING membership_id INTO v_membership;
        INSERT INTO public.identity_users (identity_id, user_id, link_channel) VALUES (s.o_identity_id, v_user, 'convite');
    END IF;

    IF c.role_scope = 1 THEN
        INSERT INTO public.organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id)
        VALUES (p_organization_id, v_membership, c.role_id, 1, 1, c.issued_by_membership_id)
        RETURNING assignment_id INTO v_assignment;
    ELSIF c.role_scope = 2 THEN
        INSERT INTO public.workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status, assigned_by_membership_id)
        VALUES (p_organization_id, c.workspace_id, v_membership, c.role_id, 2, 1, c.issued_by_membership_id)
        RETURNING assignment_id INTO v_assignment;
    END IF;

    UPDATE public.identity_invitations SET accepted_at = v_t, accepted_user_id = v_user, accepted_identity_id = s.o_identity_id
     WHERE invitation_id = c.invitation_id AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > v_t;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    IF v_n <> 1 THEN
        RAISE EXCEPTION 'convite mudou sob o lock (invitation_id=%)', c.invitation_id;
    END IF;
    INSERT INTO public.access_events (organization_id, event_type, actor_identity_id, target_user_id, target_membership_id, role_id,
                                      workspace_id, invitation_id, assignment_id, correlation_id, created_at)
    VALUES (p_organization_id, 4, s.o_identity_id, v_user, v_membership, c.role_id, c.workspace_id, c.invitation_id, v_assignment,
            pg_catalog.gen_random_uuid(), v_t);
    RETURN 'OK';
END;
$$;

CREATE FUNCTION public.identity_accept_invitation(p_session_token TEXT, p_token TEXT, p_organization_id UUID)
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

-- === 10. Donos e EXECUTE ===
ALTER FUNCTION public.identity_accept_invitation_impl(TEXT, TEXT, UUID) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_accept_invitation(TEXT, TEXT, UUID) OWNER TO nekkora_identity_owner;
REVOKE ALL ON FUNCTION public.identity_accept_invitation_impl(TEXT, TEXT, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_accept_invitation(TEXT, TEXT, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.identity_accept_invitation(TEXT, TEXT, UUID) TO nekkora_platform;
-- as consultas de autoridade e os gatilhos rodam como quem escreve (Platform, dono das funcoes de identidade)
REVOKE ALL ON FUNCTION public.access_membership_effective(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.access_org_permission(UUID, UUID, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.access_can_manage_role(UUID, UUID, UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.access_can_invite(UUID, UUID, UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.access_invitation_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.access_invitation_on_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.access_role_assignment_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.access_grant_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.access_membership_effective(UUID, UUID), public.access_org_permission(UUID, UUID, TEXT),
    public.access_can_manage_role(UUID, UUID, UUID, UUID), public.access_can_invite(UUID, UUID, UUID, UUID)
    TO nekkora_platform, nekkora_identity_owner;

-- === 11. Invariantes ===
DO $$
DECLARE
    v_n INTEGER;
    v_bad TEXT;
BEGIN
    -- tabelas de tenant: 43 (017) + access_events = 44; a nova com RLS ENABLE+FORCE e exatamente as duas politicas
    SELECT count(*) INTO v_n FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind = 'r'
      AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'organization_id' AND NOT a.attisdropped);
    IF v_n <> 44 THEN RAISE EXCEPTION '023: % tabelas com organization_id (esperadas 44)', v_n; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = 'public.access_events'::regclass AND relrowsecurity AND relforcerowsecurity) THEN
        RAISE EXCEPTION '023: access_events sem RLS ENABLE+FORCE';
    END IF;
    SELECT string_agg(polname || ':' || pg_get_userbyid(r), ',' ORDER BY polname) INTO v_bad
    FROM pg_policy p, unnest(p.polroles) r WHERE p.polrelid = 'public.access_events'::regclass;
    IF v_bad IS DISTINCT FROM 'identity_function_context:nekkora_identity_owner,tenant_request_context:nekkora_platform' THEN
        RAISE EXCEPTION '023: politicas de access_events divergentes: %', v_bad;
    END IF;
    -- auditoria sem alteracao por nenhum papel da aplicacao
    SELECT string_agg(r.rolname, ',') INTO v_bad FROM pg_roles r
    WHERE NOT r.rolsuper AND r.rolname <> 'migrador_nekkora' AND r.rolname !~ '^pg_'
      AND has_table_privilege(r.oid, 'public.access_events', 'UPDATE, DELETE, TRUNCATE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '023: alteracao possivel em access_events: %', v_bad; END IF;
    -- o aceite: SECURITY DEFINER do dono da identidade, search_path fixo, EXECUTE so da Platform; o impl sem EXECUTE alheio
    IF NOT EXISTS (SELECT 1 FROM pg_proc WHERE oid = 'public.identity_accept_invitation(text,text,uuid)'::regprocedure
                    AND prosecdef AND pg_get_userbyid(proowner) = 'nekkora_identity_owner'
                    AND 'search_path=pg_catalog, pg_temp' = ANY (proconfig))
       OR EXISTS (SELECT 1 FROM pg_proc WHERE oid = 'public.identity_accept_invitation_impl(text,text,uuid)'::regprocedure
                   AND (prosecdef OR pg_get_userbyid(proowner) <> 'nekkora_identity_owner')) THEN
        RAISE EXCEPTION '023: funcao de aceite fora do padrao';
    END IF;
    SELECT string_agg(r.rolname, ',' ORDER BY r.rolname) INTO v_bad FROM pg_roles r
    WHERE NOT r.rolsuper AND r.rolname <> 'nekkora_identity_owner'
      AND (has_function_privilege(r.oid, 'public.identity_accept_invitation(text,text,uuid)', 'EXECUTE')
           OR has_function_privilege(r.oid, 'public.identity_accept_invitation_impl(text,text,uuid)', 'EXECUTE'));
    IF v_bad IS DISTINCT FROM 'nekkora_platform' THEN RAISE EXCEPTION '023: EXECUTE do aceite divergente: %', v_bad; END IF;
    -- funcoes de autoridade e gatilhos: SECURITY INVOKER, search_path fixo, do migrador
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.proname LIKE 'access\_%'
      AND (p.prosecdef OR pg_get_userbyid(p.proowner) <> 'migrador_nekkora'
           OR NOT ('search_path=pg_catalog, pg_temp' = ANY (coalesce(p.proconfig, '{}'))));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '023: funcao de autoridade fora do padrao: %', v_bad; END IF;
    -- gatilhos ligados
    IF (SELECT count(*) FROM pg_trigger WHERE tgname IN ('trg_identity_invitations_on_insert', 'trg_identity_invitations_on_update',
            'trg_identity_invitations_prevent_delete', 'trg_identity_invitations_prevent_truncate', 'trg_org_role_assignments_authority',
            'trg_workspace_role_assignments_authority', 'trg_client_workspace_grants_authority',
            'trg_client_workspace_grant_approvals_authority', 'trg_client_workspace_grant_reviews_authority',
            'trg_access_events_prevent_update', 'trg_access_events_prevent_delete', 'trg_access_events_prevent_truncate')
          AND NOT tgisinternal AND tgenabled = 'O') <> 12 THEN
        RAISE EXCEPTION '023: gatilhos de acesso ausentes ou desligados';
    END IF;
    -- a Platform nao grava identity_users nem atribui papel por fora dos gatilhos (nenhum privilegio novo alem do previsto)
    IF has_table_privilege('nekkora_platform', 'public.identity_users', 'INSERT, UPDATE, DELETE') THEN
        RAISE EXCEPTION '023: Platform com escrita em identity_users';
    END IF;
END;
$$;

COMMIT;
