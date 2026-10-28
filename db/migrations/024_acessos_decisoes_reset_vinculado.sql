-- Nekkora Platform - DECISOES DE ACESSO aceitas pela responsavel (2026-10-27) e RECUSA EXPLICITA do reset operacional de
-- conta vinculada. Contrato: docs/relatorios/2026-10-27-acessos-convites-papeis-concessoes-contrato.md (secoes 4.5, 7, 9 e 10).
--
-- CANDIDATA ate a aprovacao das provas (ITs AccessInvitationsIT, GrantLifecycleIT e ResetContaVinculadaIT).
--
-- O que muda:
--   * D-CONV-4: o convite cujo emissor PERDE a autoridade (papel revogado, vinculo retirado ou suspenso, conta suspensa) e
--     REVOGADO no mesmo ato, com o motivo novo 4=EMISSOR_SEM_AUTORIDADE. Ate a 023 ele so ficava inaceitavel enquanto o
--     emissor estivesse sem autoridade: recuperar o papel depois o fazia valer de novo. Agora o convite antigo nunca volta;
--     outro convite tem de ser emitido. A reconferencia no aceite (023) continua. Os convites abertos que ja estao nesse
--     estado quando a 024 e aplicada sao fechados na aplicacao (unica mudanca de dados);
--   * D-GRANT-1: segunda pessoa na concessao externa. A concessao grava quem a SOLICITOU (requested_by_membership_id, imutavel)
--     e a aprovacao INITIAL (PENDENTE -> ATIVA) exige aprovador DIFERENTE do solicitante. Concessao pendente sem solicitante
--     (anterior a 024, so por provisionamento ou fixture) nao e aprovada: revogue e crie de novo;
--   * D-GRANT-1a (regra de renovacao e KEEP, decisao da responsavel): a aprovacao fica VINCULADA aos valores solicitados.
--     A concessao pendente guarda os valores pedidos (inicio, proxima revisao, prazo), imutaveis ate a decisao; a aprovacao
--     INITIAL aplica exatamente esses valores. Toda extensao de uma concessao aprovada (proxima revisao mais tarde, prazo
--     mais tarde ou removido, inicio antecipado, SUSPENSA -> ATIVA) exige um PEDIDO de renovacao gravado na concessao e a
--     aprovacao RENEWAL de OUTRA pessoa, com exatamente os valores pedidos; qualquer mudanca de validade descarta o pedido
--     pendente (a aprovacao anterior nao serve para valores novos). KEEP com uma pessoa so quando nao estende nada (mesma
--     data ou data mais cedo). Reducao, suspensao e revogacao: uma pessoa com a permissao, auditada. O escopo e as
--     permissoes da concessao nao mudam por nenhum ato (origem imutavel desde a 003; pacote fixo de leitura do Portal);
--   * Reset operacional (016) de conta VINCULADA a uma identidade global: recusa declarada, motivo CONTA_VINCULADA, em toda
--     etapa que confere a elegibilidade do alvo (abrir, verificar, pre-aprovar, aprovar) e na execucao ANTES da conferencia
--     do fator. Ate aqui a recusa dependia de efeito: a vinculacao exige o fator local desabilitado (017, E-3), e o alvo caia
--     em SEM_FATOR_CONFIRMADO ou FATOR_DIVERGENTE. O dono da 016 NAO ganha privilegio de tabela: consulta a ligacao por uma
--     funcao do dono da 017 que devolve so um booleano para a conta dada (EXECUTE so para o dono da 016).
--
-- ELEVACAO: exige SUPERUSER na janela (ALTER FUNCTION ... OWNER TO nekkora_identity_owner e CREATE OR REPLACE de funcoes do
-- dono da 016). Depende de 001-023. Atomica: uma transacao.

BEGIN;

-- === 1. Pre-condicao: 023 aplicada; objetos novos ausentes ===
DO $$
BEGIN
    IF to_regclass('public.access_events') IS NULL
       OR to_regprocedure('public.identity_accept_invitation(text,text,uuid)') IS NULL THEN
        RAISE EXCEPTION '024 exige a 023 aplicada';
    END IF;
    IF to_regprocedure('public.identity_user_is_linked(uuid)') IS NOT NULL
       OR to_regprocedure('public.identity_invitations_close_unauthorized(uuid,uuid[])') IS NOT NULL
       OR EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'public.client_workspace_grants'::regclass
                   AND attname IN ('requested_by_membership_id', 'renewal_requested_by_membership_id') AND NOT attisdropped)
       OR EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'public.client_workspace_grant_approvals'::regclass
                   AND attname = 'requested_by_membership_id' AND NOT attisdropped) THEN
        RAISE EXCEPTION '024: objetos ja existem';
    END IF;
END;
$$;

-- === 2. D-CONV-4: convite de emissor sem autoridade e revogado, nunca ressuscita ===

-- motivo 4 no catalogo da revogacao
DO $$
DECLARE
    v_con TEXT;
BEGIN
    SELECT string_agg(conname, ',') INTO v_con FROM pg_constraint
     WHERE conrelid = 'public.identity_invitations'::regclass AND contype = 'c'
       AND pg_get_constraintdef(oid) = 'CHECK ((revoke_reason = ANY (ARRAY[1, 2, 3])))';
    IF v_con IS NULL OR v_con LIKE '%,%' THEN
        RAISE EXCEPTION '024: restricao do motivo de revogacao divergente da 023: %', v_con;
    END IF;
    EXECUTE format('ALTER TABLE public.identity_invitations DROP CONSTRAINT %I', v_con);
END;
$$;
ALTER TABLE public.identity_invitations ADD CONSTRAINT identity_invitations_revoke_reason_check
    CHECK (revoke_reason IN (1, 2, 3, 4));
COMMENT ON COLUMN public.identity_invitations.revoke_reason IS
    '1=REVOGADO, 2=REENVIADO (substituido por outro convite), 3=EXPIRADO (fechado ao emitir outro), 4=EMISSOR_SEM_AUTORIDADE (024)';

-- Fecha os convites abertos dos vinculos dados cujo emissor nao tem mais a autoridade do convite. SECURITY DEFINER do dono
-- da 017 (politica identity_function_context: enxerga so a organizacao dada), para nao depender do contexto de quem
-- dispara. So fecha o que ja e inaceitavel; nunca abre nem altera outro convite.
CREATE FUNCTION public.identity_invitations_close_unauthorized(p_org UUID, p_memberships UUID[])
RETURNS INTEGER
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
DECLARE
    v_n INTEGER;
BEGIN
    PERFORM set_config('nekkora.fn_organization_id', p_org::text, true);
    UPDATE public.identity_invitations i
       SET revoked_at = greatest(clock_timestamp(), i.created_at), revoke_reason = 4
     WHERE i.organization_id = p_org AND i.issued_by_membership_id = ANY (p_memberships)
       AND i.accepted_at IS NULL AND i.revoked_at IS NULL
       AND NOT public.access_can_invite(i.organization_id, i.issued_by_membership_id, i.role_id, i.workspace_id);
    GET DIAGNOSTICS v_n = ROW_COUNT;
    RETURN v_n;
END;
$$;

-- gatilho (SECURITY INVOKER, do migrador): roda depois de cada perda de autoridade possivel do emissor
CREATE FUNCTION public.access_issuer_authority_lost() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF TG_TABLE_NAME = 'users' THEN
        PERFORM public.identity_invitations_close_unauthorized(NEW.organization_id,
            ARRAY(SELECT m.membership_id FROM public.organization_memberships m
                   WHERE m.organization_id = NEW.organization_id AND m.user_id = NEW.user_id));
    ELSE
        PERFORM public.identity_invitations_close_unauthorized(NEW.organization_id, ARRAY[NEW.membership_id]);
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_org_role_assignments_issuer_authority AFTER UPDATE OF status ON public.organization_role_assignments
    FOR EACH ROW WHEN (OLD.status = 1 AND NEW.status <> 1) EXECUTE FUNCTION public.access_issuer_authority_lost();
CREATE TRIGGER trg_workspace_role_assignments_issuer_authority AFTER UPDATE OF status ON public.workspace_role_assignments
    FOR EACH ROW WHEN (OLD.status = 1 AND NEW.status <> 1) EXECUTE FUNCTION public.access_issuer_authority_lost();
CREATE TRIGGER trg_organization_memberships_issuer_authority AFTER UPDATE OF status ON public.organization_memberships
    FOR EACH ROW WHEN (OLD.status = 2 AND NEW.status <> 2) EXECUTE FUNCTION public.access_issuer_authority_lost();
CREATE TRIGGER trg_users_issuer_authority AFTER UPDATE OF status ON public.users
    FOR EACH ROW WHEN (OLD.status = 2 AND NEW.status <> 2) EXECUTE FUNCTION public.access_issuer_authority_lost();

-- revogacao com motivo 4: sem revogador, aceita so quando o emissor de fato nao tem a autoridade (023, corpo inalterado no resto)
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
    ELSIF NEW.revoke_reason = 4 THEN
        -- 024: fechamento por perda de autoridade do emissor; nenhum revogador, e so vale se o emissor estiver sem autoridade
        IF NEW.revoked_by_membership_id IS NOT NULL
           OR public.access_can_invite(NEW.organization_id, NEW.issued_by_membership_id, NEW.role_id, NEW.workspace_id) THEN
            RAISE EXCEPTION 'emissor do convite ainda tem autoridade' USING ERRCODE = '42501';
        END IF;
    ELSIF NEW.revoked_by_membership_id IS NULL OR NEW.revoke_reason IS NULL
          OR NOT public.access_org_permission(NEW.organization_id, NEW.revoked_by_membership_id, 'org.manage') THEN
        RAISE EXCEPTION 'revogacao de convite sem autoridade' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

GRANT SELECT (created_at), UPDATE (revoked_at, revoke_reason) ON public.identity_invitations TO nekkora_identity_owner;

-- convites abertos que JA estao inaceitaveis na aplicacao (emissor sem a autoridade do convite): fechados agora, com o motivo
-- 4, para que nao voltem a valer se o emissor recuperar a autoridade. Unica mudanca de dados da 024. Convites do modelo da
-- 002 (sem emissor vinculado) ficam como estao: a 023 ja os recusa no aceite.
DO $$
DECLARE
    v_n INTEGER;
BEGIN
    UPDATE public.identity_invitations i
       SET revoked_at = greatest(clock_timestamp(), i.created_at), revoke_reason = 4
     WHERE i.accepted_at IS NULL AND i.revoked_at IS NULL AND i.issued_by_membership_id IS NOT NULL
       AND NOT public.access_can_invite(i.organization_id, i.issued_by_membership_id, i.role_id, i.workspace_id);
    GET DIAGNOSTICS v_n = ROW_COUNT;
    RAISE NOTICE '024: convites abertos de emissor sem autoridade fechados na aplicacao: %', v_n;
END;
$$;

-- === 3. D-GRANT-1: segunda pessoa na aprovacao da concessao externa ===
ALTER TABLE public.client_workspace_grants
    ADD COLUMN requested_by_membership_id UUID,
    ADD CONSTRAINT client_workspace_grants_requester_fkey FOREIGN KEY (organization_id, requested_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT;
COMMENT ON COLUMN public.client_workspace_grants.requested_by_membership_id IS
    'vinculo de quem solicitou (024): imutavel; a aprovacao INITIAL exige outra pessoa. Nulo so em linha anterior a 024';

-- D-GRANT-1a: pedido de renovacao pendente, na propria concessao (um por vez; um pedido novo substitui o anterior). A
-- historia fica nas aprovacoes RENEWAL (com o solicitante) e em access_events (pedido = evento 14).
ALTER TABLE public.client_workspace_grants
    ADD COLUMN renewal_requested_by_membership_id UUID,
    ADD COLUMN renewal_requested_at TIMESTAMPTZ,
    ADD COLUMN renewal_next_review_at TIMESTAMPTZ,
    ADD COLUMN renewal_expires_at TIMESTAMPTZ,
    ADD COLUMN renewal_justification TEXT,
    ADD CONSTRAINT client_workspace_grants_renewal_requester_fkey FOREIGN KEY (organization_id, renewal_requested_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    ADD CONSTRAINT client_workspace_grants_renewal_check CHECK (
        (renewal_requested_by_membership_id IS NULL) = (renewal_requested_at IS NULL)
        AND (renewal_requested_by_membership_id IS NULL) = (renewal_next_review_at IS NULL)
        AND (renewal_requested_by_membership_id IS NULL) = (renewal_justification IS NULL)
        AND (renewal_requested_by_membership_id IS NOT NULL OR renewal_expires_at IS NULL)
        AND (renewal_justification IS NULL OR btrim(renewal_justification) <> '')
        AND (renewal_expires_at IS NULL OR renewal_expires_at > starts_at));
COMMENT ON COLUMN public.client_workspace_grants.renewal_requested_by_membership_id IS
    'pedido de renovacao pendente (024): quem pediu; a aprovacao RENEWAL exige outra pessoa e exatamente os valores renewal_*';
COMMENT ON COLUMN public.client_workspace_grants.renewal_expires_at IS 'prazo pedido na renovacao; nulo = sem prazo (so com pedido)';

ALTER TABLE public.client_workspace_grant_approvals
    ADD COLUMN requested_by_membership_id UUID,
    ADD CONSTRAINT client_workspace_grant_approvals_requester_fkey FOREIGN KEY (organization_id, requested_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT;
COMMENT ON COLUMN public.client_workspace_grant_approvals.requested_by_membership_id IS
    'quem pediu o que foi aprovado (024): o solicitante da concessao (INITIAL) ou do pedido de renovacao (RENEWAL); nulo antes da 024';

-- criacao pela aplicacao: nasce PENDENTE, sem aprovacao nem revogacao, com solicitante que tem external_access.manage
-- D-GRANT-1a: os valores pedidos de uma pendente sao imutaveis; pedido de renovacao so por quem tem a permissao, numa
-- concessao aprovada, sem mudar a validade no mesmo ato; qualquer mudanca de validade descarta o pedido pendente (para
-- todos os papeis: e o que impede aprovar um pedido feito sobre valores que ja mudaram)
CREATE FUNCTION public.access_grant_request_guard() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_validade BOOLEAN;
    v_pedido BOOLEAN;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        v_validade := (NEW.status, NEW.starts_at, NEW.expires_at, NEW.next_review_at, NEW.approved_by_membership_id, NEW.approved_at)
                      IS DISTINCT FROM (OLD.status, OLD.starts_at, OLD.expires_at, OLD.next_review_at, OLD.approved_by_membership_id, OLD.approved_at);
        v_pedido := NEW.renewal_requested_by_membership_id IS NOT NULL
                    AND (NEW.renewal_requested_by_membership_id, NEW.renewal_requested_at, NEW.renewal_next_review_at, NEW.renewal_expires_at,
                         NEW.renewal_justification)
                        IS DISTINCT FROM (OLD.renewal_requested_by_membership_id, OLD.renewal_requested_at, OLD.renewal_next_review_at,
                                          OLD.renewal_expires_at, OLD.renewal_justification);
        IF v_validade THEN
            IF v_pedido AND NOT coalesce((SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.rolname = current_user), false) THEN
                RAISE EXCEPTION 'pedido de renovacao nao muda a validade no mesmo ato (grant_id=%)', OLD.grant_id USING ERRCODE = '42501';
            END IF;
            NEW.renewal_requested_by_membership_id := NULL;
            NEW.renewal_requested_at := NULL;
            NEW.renewal_next_review_at := NULL;
            NEW.renewal_expires_at := NULL;
            NEW.renewal_justification := NULL;
        END IF;
    END IF;
    IF coalesce((SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.rolname = current_user), false) THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.requested_by_membership_id IS DISTINCT FROM OLD.requested_by_membership_id THEN
            RAISE EXCEPTION 'solicitante da concessao e imutavel (grant_id=%)', OLD.grant_id USING ERRCODE = '42501';
        END IF;
        IF OLD.status = 1 AND (NEW.starts_at, NEW.expires_at, NEW.next_review_at) IS DISTINCT FROM (OLD.starts_at, OLD.expires_at, OLD.next_review_at) THEN
            RAISE EXCEPTION 'valores solicitados da concessao sao imutaveis ate a decisao (grant_id=%)', OLD.grant_id USING ERRCODE = '42501';
        END IF;
        IF v_pedido AND (OLD.status NOT IN (2, 3) OR NEW.renewal_next_review_at <= clock_timestamp()
                         OR NOT public.access_org_permission(NEW.organization_id, NEW.renewal_requested_by_membership_id, 'external_access.manage')) THEN
            RAISE EXCEPTION 'pedido de renovacao sem autoridade ou fora do estado (grant_id=%)', OLD.grant_id USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.status <> 1 OR NEW.approved_at IS NOT NULL OR NEW.approved_by_membership_id IS NOT NULL OR NEW.revoked_at IS NOT NULL
       OR NEW.renewal_requested_by_membership_id IS NOT NULL THEN
        RAISE EXCEPTION 'concessao nasce pendente, sem aprovacao' USING ERRCODE = '42501';
    END IF;
    IF NEW.next_review_at IS NULL OR NEW.next_review_at <= clock_timestamp() THEN
        RAISE EXCEPTION 'concessao nasce com a proxima revisao pedida, no futuro' USING ERRCODE = '42501';
    END IF;
    IF NEW.requested_by_membership_id IS NULL
       OR NOT public.access_org_permission(NEW.organization_id, NEW.requested_by_membership_id, 'external_access.manage') THEN
        RAISE EXCEPTION 'solicitacao de concessao externa sem autoridade' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_client_workspace_grants_request BEFORE INSERT OR UPDATE ON public.client_workspace_grants
    FOR EACH ROW EXECUTE FUNCTION public.access_grant_request_guard();

-- autoridade (023) + separacao (D-GRANT-1) + aprovacao vinculada aos valores pedidos (D-GRANT-1a)
CREATE OR REPLACE FUNCTION public.access_grant_guard() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_actor UUID;
    v_requester UUID;
    v_initial BOOLEAN := false;
    v_renewal BOOLEAN := false;
    v_extends BOOLEAN := false;
    g RECORD;
BEGIN
    IF TG_TABLE_NAME = 'client_workspace_grant_approvals' THEN
        v_actor := NEW.approved_by_membership_id;
    ELSIF TG_TABLE_NAME = 'client_workspace_grant_reviews' THEN
        v_actor := NEW.reviewed_by_membership_id;
    ELSIF NEW.status = 4 AND OLD.status <> 4 THEN
        v_actor := NEW.revoked_by_membership_id;
    ELSIF OLD.status = 1 THEN
        IF NEW.status = 2 OR NEW.approved_by_membership_id IS DISTINCT FROM OLD.approved_by_membership_id THEN
            v_actor := NEW.approved_by_membership_id;   -- aprovacao INITIAL (valores: imutaveis na pendente, guarda da solicitacao)
            v_initial := true;
            v_requester := NEW.requested_by_membership_id;
        ELSE
            RETURN NEW;
        END IF;
    ELSE
        -- concessao ja aprovada: renovacao = aprovador ou instante de aprovacao novos; extensao = qualquer ganho de validade
        v_renewal := NEW.approved_by_membership_id IS DISTINCT FROM OLD.approved_by_membership_id
                     OR NEW.approved_at IS DISTINCT FROM OLD.approved_at;
        v_extends := (NEW.status = 2 AND OLD.status <> 2)
                     OR (NEW.next_review_at IS NOT NULL AND (OLD.next_review_at IS NULL OR NEW.next_review_at > OLD.next_review_at))
                     OR (OLD.expires_at IS NOT NULL AND (NEW.expires_at IS NULL OR NEW.expires_at > OLD.expires_at))
                     OR NEW.starts_at < OLD.starts_at;
        IF NOT (v_renewal OR v_extends) THEN
            -- ultimo acesso pelo Portal; suspensao e KEEP que nao estende (mesma data ou mais cedo): sem coluna de autor na
            -- concessao, o autor e o da linha de revisao gravada na mesma transacao, conferida por este gatilho nas revisoes
            RETURN NEW;
        END IF;
        v_actor := NEW.approved_by_membership_id;
    END IF;
    IF coalesce((SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.rolname = current_user), false) THEN
        RETURN NEW;
    END IF;
    IF v_actor IS NULL OR NOT public.access_org_permission(NEW.organization_id, v_actor, 'external_access.manage') THEN
        RAISE EXCEPTION 'ato sobre concessao externa sem autoridade' USING ERRCODE = '42501';
    END IF;
    IF v_initial AND (v_requester IS NULL OR v_requester = v_actor) THEN
        RAISE EXCEPTION 'aprovacao de concessao externa exige pessoa diferente do solicitante' USING ERRCODE = '42501';
    END IF;
    IF TG_TABLE_NAME = 'client_workspace_grant_approvals' THEN
        SELECT * INTO g FROM public.client_workspace_grants x WHERE x.organization_id = NEW.organization_id AND x.grant_id = NEW.grant_id;
        IF NEW.approval_type = 1 THEN
            -- INITIAL: outra pessoa, exatamente os valores pedidos na concessao pendente
            IF g.status IS DISTINCT FROM 1::smallint OR g.requested_by_membership_id IS NULL OR g.requested_by_membership_id = v_actor
               OR NEW.requested_by_membership_id IS DISTINCT FROM g.requested_by_membership_id
               OR NEW.new_starts_at IS DISTINCT FROM g.starts_at OR NEW.new_expires_at IS DISTINCT FROM g.expires_at
               OR NEW.new_next_review_at IS DISTINCT FROM g.next_review_at THEN
                RAISE EXCEPTION 'aprovacao inicial exige pessoa diferente do solicitante e exatamente os valores pedidos' USING ERRCODE = '42501';
            END IF;
        ELSE
            -- RENEWAL: pedido pendente de outra pessoa, exatamente os valores pedidos, sobre os valores atuais
            IF g.status NOT IN (2, 3) OR g.renewal_requested_by_membership_id IS NULL OR g.renewal_requested_by_membership_id = v_actor
               OR NEW.requested_by_membership_id IS DISTINCT FROM g.renewal_requested_by_membership_id
               OR NEW.new_next_review_at IS DISTINCT FROM g.renewal_next_review_at OR NEW.new_expires_at IS DISTINCT FROM g.renewal_expires_at
               OR NEW.new_starts_at IS DISTINCT FROM g.starts_at OR NEW.previous_starts_at IS DISTINCT FROM g.starts_at
               OR NEW.previous_expires_at IS DISTINCT FROM g.expires_at OR NEW.previous_next_review_at IS DISTINCT FROM g.next_review_at
               OR NEW.new_next_review_at <= clock_timestamp() THEN
                RAISE EXCEPTION 'renovacao exige pedido pendente de outra pessoa e exatamente os valores pedidos' USING ERRCODE = '42501';
            END IF;
        END IF;
    ELSIF TG_TABLE_NAME = 'client_workspace_grants' AND (v_renewal OR v_extends) THEN
        -- extensao ou renovacao: so consumindo o pedido pendente, com a aprovacao RENEWAL gravada no mesmo instante
        IF NOT v_renewal OR OLD.renewal_requested_by_membership_id IS NULL OR v_actor = OLD.renewal_requested_by_membership_id
           OR NEW.status <> 2 OR NEW.starts_at <> OLD.starts_at
           OR NEW.next_review_at IS DISTINCT FROM OLD.renewal_next_review_at OR NEW.expires_at IS DISTINCT FROM OLD.renewal_expires_at
           OR NEW.next_review_at <= clock_timestamp()
           OR NOT EXISTS (SELECT 1 FROM public.client_workspace_grant_approvals a
                          WHERE a.organization_id = NEW.organization_id AND a.grant_id = NEW.grant_id AND a.approval_type = 2
                            AND a.approved_by_membership_id = v_actor AND a.approved_at = NEW.approved_at
                            AND a.requested_by_membership_id = OLD.renewal_requested_by_membership_id
                            AND a.new_next_review_at = NEW.next_review_at AND a.new_expires_at IS NOT DISTINCT FROM NEW.expires_at
                            AND a.previous_next_review_at IS NOT DISTINCT FROM OLD.next_review_at
                            AND a.previous_expires_at IS NOT DISTINCT FROM OLD.expires_at) THEN
            RAISE EXCEPTION 'extensao da concessao exige pedido de renovacao e aprovacao de outra pessoa, com os valores pedidos'
                USING ERRCODE = '42501';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- evento 14 = CONCESSAO_RENOVACAO_SOLICITADA (codigos permanentes da 023; 1..13 inalterados).
-- As restricoes "NOT BETWEEN ... OR ..." da 023 nao sobrevivem identicas a um dump e restauracao: o PostgreSQL guarda o
-- OR aninhado e a restauracao o rele achatado (achado da R015 na homologacao da 024). Por isso a 024 aceita as DUAS formas
-- de cada uma (instalada pela 023 ou restaurada de um dump dela) e recria as duas restricoes com OR na forma achatada,
-- que volta igual de uma restauracao: a de concessao (com o codigo 14) e a de convite (mesmo nome, mesma regra).
DO $$
DECLARE
    v_con RECORD;
    v_n INTEGER := 0;
    v_conv TEXT;
BEGIN
    FOR v_con IN SELECT conname, pg_get_constraintdef(oid) AS def FROM pg_constraint
                 WHERE conrelid = 'public.access_events'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%13%' LOOP
        IF v_con.def NOT IN ('CHECK (((event_type >= 1) AND (event_type <= 13)))',
                             'CHECK ((((event_type < 9) OR (event_type > 13)) OR (grant_id IS NOT NULL)))',
                             'CHECK (((event_type < 9) OR (event_type > 13) OR (grant_id IS NOT NULL)))') THEN
            RAISE EXCEPTION '024: restricao de access_events divergente da 023: % %', v_con.conname, v_con.def;
        END IF;
        EXECUTE format('ALTER TABLE public.access_events DROP CONSTRAINT %I', v_con.conname);
        v_n := v_n + 1;
    END LOOP;
    IF v_n <> 2 THEN
        RAISE EXCEPTION '024: esperadas 2 restricoes de access_events com o codigo 13, encontradas %', v_n;
    END IF;
    SELECT string_agg(conname, ',') INTO v_conv FROM pg_constraint
     WHERE conrelid = 'public.access_events'::regclass AND contype = 'c'
       AND pg_get_constraintdef(oid) IN ('CHECK ((((event_type < 1) OR (event_type > 4)) OR (invitation_id IS NOT NULL)))',
                                         'CHECK (((event_type < 1) OR (event_type > 4) OR (invitation_id IS NOT NULL)))');
    IF v_conv IS NULL OR v_conv LIKE '%,%' THEN
        RAISE EXCEPTION '024: restricao de convite de access_events divergente da 023: %', v_conv;
    END IF;
    EXECUTE format('ALTER TABLE public.access_events DROP CONSTRAINT %I', v_conv);
    EXECUTE format('ALTER TABLE public.access_events ADD CONSTRAINT %I CHECK (event_type < 1 OR event_type > 4 OR invitation_id IS NOT NULL)', v_conv);
END;
$$;
ALTER TABLE public.access_events
    ADD CONSTRAINT access_events_event_type_check CHECK (event_type BETWEEN 1 AND 14),
    ADD CONSTRAINT access_events_grant_check CHECK (event_type < 9 OR event_type > 14 OR grant_id IS NOT NULL);
COMMENT ON COLUMN public.access_events.event_type IS '1..13: ver cabecalho da 023; 14=CONCESSAO_RENOVACAO_SOLICITADA (024)';

-- === 4. Reset operacional (016) de conta vinculada: recusa declarada ===

-- a conta dada esta ligada a uma identidade global? Chave exata, resposta booleana (padrao das consultas da 017).
CREATE FUNCTION public.identity_user_is_linked(p_user UUID)
RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT EXISTS (SELECT 1 FROM public.identity_users iu WHERE iu.user_id = p_user)
$$;

-- elegibilidade do alvo (016), com a conta vinculada recusada antes do fator; corpo da 016 inalterado no resto
CREATE OR REPLACE FUNCTION ops.alvo_inelegivel(p_org UUID, p_target UUID) RETURNS TEXT
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT CASE
        WHEN NOT EXISTS (SELECT 1 FROM public.organizations o WHERE o.organization_id = p_org AND o.status = 1) THEN 'ORGANIZACAO_INELEGIVEL'
        WHEN NOT EXISTS (SELECT 1 FROM public.users u WHERE u.organization_id = p_org AND u.user_id = p_target
                         AND u.status = 2 AND u.account_type = 1) THEN 'CONTA_INELEGIVEL'
        WHEN public.identity_user_is_linked(p_target) THEN 'CONTA_VINCULADA'
        WHEN NOT EXISTS (SELECT 1 FROM public.organization_memberships m WHERE m.organization_id = p_org AND m.user_id = p_target
                         AND m.status = 2) THEN 'VINCULO_INTERNO_INATIVO'
        WHEN NOT EXISTS (SELECT 1 FROM public.organization_role_assignments a
                         JOIN public.organization_memberships m ON m.organization_id = a.organization_id AND m.membership_id = a.membership_id
                         JOIN public.roles r ON r.role_id = a.role_id
                         WHERE a.organization_id = p_org AND m.user_id = p_target AND a.status = 1 AND m.status = 2
                           AND r.code = 'ORG_ADMIN') THEN 'NAO_ORG_ADMIN'
        WHEN NOT EXISTS (SELECT 1 FROM public.user_mfa_factors f WHERE f.organization_id = p_org AND f.user_id = p_target
                         AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL) THEN 'SEM_FATOR_CONFIRMADO'
    END
$$;

-- execucao (corpo da 018): unica mudanca, a conta vinculada recusada ANTES da conferencia do fator (sem isso, a recusa
-- dependia do efeito da vinculacao sobre o fator local: FATOR_DIVERGENTE)
CREATE OR REPLACE FUNCTION public.ops_mfa_execute(p_request UUID)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT, r_solicitacao UUID, r_estado TEXT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_org UUID;
    v_target UUID;
    r ops.ops_mfa_recovery_requests%ROWTYPE;
    v_agora TIMESTAMPTZ;
    v_aut RECORD;
    v_motivo TEXT;
    v_fator_ativo RECORD;
    v_n INTEGER;
    v_ok UUID;
    v_demais UUID[];
    v_travadas UUID[];
BEGIN
    PERFORM ops.exigir_read_committed();
    SELECT q.organization_id, q.target_user_id INTO v_org, v_target FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = p_request;
    IF NOT FOUND THEN
        SELECT * INTO v_aut FROM ops.autorizar(v_op, 3::smallint, clock_timestamp());
        PERFORM ops.registrar(NULL, NULL, NULL, 'EXECUTAR', 'RECUSA', 'SOLICITACAO_INEXISTENTE', v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, 'SOLICITACAO_INEXISTENTE'::text, p_request, NULL::text;
        RETURN;
    END IF;
    -- 1. locks: organizacao -> contas (alvo e destinatarios dos avisos, crescentes) -> solicitacao -> participantes
    PERFORM set_config('nekkora.fn_organization_id', v_org::text, true);
    PERFORM 1 FROM public.organizations WHERE organization_id = v_org FOR UPDATE;
    v_demais := ARRAY(SELECT ops.demais_admins(v_org, v_target));
    v_travadas := ops.travar_contas(v_org, v_target, v_demais);
    SELECT * INTO r FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = p_request FOR UPDATE;
    PERFORM ops.travar_operadores(ARRAY[v_op, r.verified_by, r.preapproved_by, r.final_approved_by]);
    -- 2. instante do banco depois dos locks
    v_agora := clock_timestamp();

    -- 3. reconferencias do ato (recusa sem encerrar: outro executor pode agir)
    SELECT * INTO v_aut FROM ops.autorizar(v_op, 3::smallint, v_agora);
    v_motivo := v_aut.o_motivo;
    IF v_motivo IS NULL AND r.state = 5 THEN v_motivo := 'JA_EXECUTADA'; END IF;
    IF v_motivo IS NULL AND r.state <> 4 THEN v_motivo := 'ESTADO_INVALIDO'; END IF;
    IF v_motivo IS NULL AND (v_op = r.preapproved_by OR v_op = r.final_approved_by) THEN v_motivo := 'EXECUTOR_IGUAL_APROVADOR'; END IF;
    IF v_motivo IS NULL AND ops.tem_vinculo(v_op, v_org) THEN v_motivo := 'VINCULO_IMPEDITIVO'; END IF;
    IF v_motivo IS NULL AND ops.contas_em_mudanca(v_org, v_target, v_travadas) THEN v_motivo := 'CONTAS_EM_MUDANCA'; END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar(p_request, v_org, v_target, 'EXECUTAR', 'RECUSA', v_motivo, v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, p_request, ops.estado_nome(r.state);
        RETURN;
    END IF;
    -- prazo antes da condicao de aviso: vencida, a solicitacao EXPIRA mesmo que um reenvio tenha alterado a condicao
    IF ops.vencida(r.state, r.opened_at, r.t_total, r.final_approved_at, v_agora) THEN
        PERFORM ops.encerrar(p_request, 8::smallint, 'PRAZO_ENCERRADO', v_op, v_agora);
        PERFORM ops.registrar(p_request, v_org, v_target, 'EXECUTAR', 'RECUSA', 'PRAZO_ENCERRADO', v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, 'PRAZO_ENCERRADO'::text, p_request, 'EXPIRADA'::text;
        RETURN;
    END IF;
    -- item 10: qualquer tentativa criada depois da aprovacao (reenvio a qualquer destinatario, inclusive o titular), ou
    -- versao registrada que deixou de estar aceita, impede executar - recusa do ato, sem reiniciar a confirmacao nem
    -- ampliar prazos (a solicitacao continua APROVADA ate executar ou vencer)
    IF r.notice_seq > r.notice_seq_at_approval
       OR EXISTS (SELECT 1 FROM ops.ops_mfa_approval_attempts a JOIN ops.ops_mfa_notices n ON n.notice_id = a.notice_id
                  WHERE a.request_id = p_request AND n.state <> 4) THEN
        PERFORM ops.registrar(p_request, v_org, v_target, 'EXECUTAR', 'RECUSA', 'CONDICAO_DE_AVISO_ALTERADA', v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, 'CONDICAO_DE_AVISO_ALTERADA'::text, p_request, ops.estado_nome(r.state);
        RETURN;
    END IF;
    -- reconferencias da solicitacao (recusa encerra: RECUSADA na execucao)
    v_motivo := ops.participante_invalido(r.verified_by, 1::smallint, v_org, v_agora);
    IF v_motivo IS NULL THEN v_motivo := ops.participante_invalido(r.preapproved_by, 2::smallint, v_org, v_agora); END IF;
    IF v_motivo IS NULL THEN v_motivo := ops.participante_invalido(r.final_approved_by, 2::smallint, v_org, v_agora); END IF;
    -- 024: conta vinculada a uma identidade global nunca passa pelo reset operacional (regra declarada, antes do fator)
    IF v_motivo IS NULL AND public.identity_user_is_linked(v_target) THEN v_motivo := 'CONTA_VINCULADA'; END IF;
    -- fator exatamente o aprovado, confirmado e ativo (antes da elegibilidade geral: reset concorrente = FATOR_DIVERGENTE)
    IF v_motivo IS NULL THEN
        SELECT f.factor_id, f.confirmed_at INTO v_fator_ativo FROM public.user_mfa_factors f
        WHERE f.organization_id = v_org AND f.user_id = v_target AND f.disabled_at IS NULL;
        IF v_fator_ativo.factor_id IS DISTINCT FROM r.approved_factor_id OR v_fator_ativo.confirmed_at IS NULL THEN
            v_motivo := 'FATOR_DIVERGENTE';
        END IF;
    END IF;
    IF v_motivo IS NULL THEN v_motivo := ops.declaracao_invalida(p_request, v_org, v_target); END IF;
    IF v_motivo IS NULL THEN v_motivo := ops.alvo_inelegivel(v_org, v_target); END IF;
    IF v_motivo IS NULL THEN v_motivo := ops.limite_executado(v_org, v_target, v_agora); END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.encerrar(p_request, 6::smallint, v_motivo, v_op, v_agora);
        PERFORM ops.registrar(p_request, v_org, v_target, 'EXECUTAR', 'RECUSA', v_motivo, v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, p_request, 'RECUSADA'::text;
        RETURN;
    END IF;

    -- 4. efeitos (os do reset administrativo). 018: instante terminal = greatest(instante, created_at) nos codigos, no fator e
    --    nas sessoes - com o relogio do banco recuado, o created_at (ajustado para a CHECK; nao e o instante exato).
    --    executed_at, o registro e os avisos continuam com v_agora.
    UPDATE public.auth_challenges SET invalidated_at = v_agora, credential_hash = NULL, pending_password_hash = NULL
     WHERE organization_id = v_org AND user_id = v_target AND consumed_at IS NULL AND invalidated_at IS NULL;
    UPDATE public.user_mfa_recovery_codes SET invalidated_at = greatest(v_agora, created_at)
     WHERE organization_id = v_org AND user_id = v_target AND used_at IS NULL AND invalidated_at IS NULL;
    UPDATE public.user_mfa_factors SET disabled_at = greatest(v_agora, created_at)
     WHERE organization_id = v_org AND user_id = v_target AND disabled_at IS NULL;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    IF v_n <> 1 THEN
        RAISE EXCEPTION 'execucao: fator aprovado nao desabilitado' USING ERRCODE = 'P0001';
    END IF;
    UPDATE public.user_sessions SET revoked_at = greatest(v_agora, created_at)
     WHERE organization_id = v_org AND user_id = v_target AND revoked_at IS NULL;
    INSERT INTO public.mfa_events (organization_id, user_id, event_type, correlation_id, ops_request_id)
    VALUES (v_org, v_target, 4, gen_random_uuid(), p_request);
    -- avisos de execucao ANTES de marcar EXECUTADA (a sequencia da solicitacao nao muda em estado terminal)
    PERFORM ops.criar_avisos(p_request, v_org, v_target, 3::smallint, v_demais);
    UPDATE ops.ops_mfa_recovery_requests SET state = 5, executed_at = v_agora, executed_by = v_op
     WHERE request_id = p_request AND state = 4
    RETURNING request_id INTO v_ok;
    IF v_ok IS NULL THEN
        RAISE EXCEPTION 'execucao: transicao para EXECUTADA falhou' USING ERRCODE = 'P0001';
    END IF;
    PERFORM ops.registrar(p_request, v_org, v_target, 'EXECUTAR', 'OK', NULL, v_op, v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text, p_request, 'EXECUTADA'::text;
END;
$$;

-- === 5. Donos e EXECUTE ===
ALTER FUNCTION public.identity_invitations_close_unauthorized(UUID, UUID[]) OWNER TO nekkora_identity_owner;
ALTER FUNCTION public.identity_user_is_linked(UUID) OWNER TO nekkora_identity_owner;
REVOKE ALL ON FUNCTION public.identity_invitations_close_unauthorized(UUID, UUID[]) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.identity_user_is_linked(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.access_issuer_authority_lost() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.access_grant_request_guard() FROM PUBLIC;
-- quem perde a autoridade de um emissor: so a Platform escreve essas transicoes (o dono da 017 so readmite: REVOKED -> ACTIVE)
GRANT EXECUTE ON FUNCTION public.identity_invitations_close_unauthorized(UUID, UUID[]) TO nekkora_platform;
GRANT EXECUTE ON FUNCTION public.identity_user_is_linked(UUID) TO nekkora_ops_mfa_owner;

-- === 6. Invariantes ===
DO $$
DECLARE
    v_bad TEXT;
BEGIN
    -- funcoes novas do dono da 017: SECURITY DEFINER, search_path fixo; EXECUTE exatamente o previsto
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad FROM pg_proc p
    WHERE p.oid IN ('public.identity_invitations_close_unauthorized(uuid,uuid[])'::regprocedure, 'public.identity_user_is_linked(uuid)'::regprocedure)
      AND (NOT p.prosecdef OR pg_get_userbyid(p.proowner) <> 'nekkora_identity_owner'
           OR NOT ('search_path=pg_catalog, pg_temp' = ANY (coalesce(p.proconfig, '{}'))));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '024: funcao de identidade fora do padrao: %', v_bad; END IF;
    SELECT string_agg(r.rolname, ',' ORDER BY r.rolname) INTO v_bad FROM pg_roles r
    WHERE NOT r.rolsuper AND r.rolname <> 'nekkora_identity_owner'
      AND has_function_privilege(r.oid, 'public.identity_invitations_close_unauthorized(uuid,uuid[])', 'EXECUTE');
    IF v_bad IS DISTINCT FROM 'nekkora_platform' THEN RAISE EXCEPTION '024: EXECUTE do fechamento divergente: %', v_bad; END IF;
    SELECT string_agg(r.rolname, ',' ORDER BY r.rolname) INTO v_bad FROM pg_roles r
    WHERE NOT r.rolsuper AND r.rolname <> 'nekkora_identity_owner'
      AND has_function_privilege(r.oid, 'public.identity_user_is_linked(uuid)', 'EXECUTE');
    IF v_bad IS DISTINCT FROM 'nekkora_ops_mfa_owner' THEN RAISE EXCEPTION '024: EXECUTE da consulta de ligacao divergente: %', v_bad; END IF;
    -- funcoes de autoridade (023 e 024): SECURITY INVOKER, search_path fixo, do migrador
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.proname LIKE 'access\_%'
      AND (p.prosecdef OR pg_get_userbyid(p.proowner) <> 'migrador_nekkora'
           OR NOT ('search_path=pg_catalog, pg_temp' = ANY (coalesce(p.proconfig, '{}'))));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '024: funcao de autoridade fora do padrao: %', v_bad; END IF;
    -- funcoes substituidas da 016/018 continuam do dono da 016
    IF pg_get_userbyid((SELECT proowner FROM pg_proc WHERE oid = 'ops.alvo_inelegivel(uuid,uuid)'::regprocedure)) <> 'nekkora_ops_mfa_owner'
       OR pg_get_userbyid((SELECT proowner FROM pg_proc WHERE oid = 'public.ops_mfa_execute(uuid)'::regprocedure)) <> 'nekkora_ops_mfa_owner' THEN
        RAISE EXCEPTION '024: dono das funcoes da 016 alterado';
    END IF;
    -- o dono da 016 nao ganhou privilegio de tabela de identidade
    IF has_any_column_privilege('nekkora_ops_mfa_owner', 'public.identity_users', 'SELECT, INSERT, UPDATE') THEN
        RAISE EXCEPTION '024: dono da 016 com privilegio em identity_users';
    END IF;
    -- D-GRANT-1a: pedido de renovacao e solicitante da aprovacao presentes; evento 14 no catalogo
    IF (SELECT count(*) FROM pg_attribute WHERE attrelid = 'public.client_workspace_grants'::regclass AND NOT attisdropped
          AND attname IN ('requested_by_membership_id', 'renewal_requested_by_membership_id', 'renewal_requested_at',
                          'renewal_next_review_at', 'renewal_expires_at', 'renewal_justification')) <> 6
       OR NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'public.client_workspace_grant_approvals'::regclass
                      AND attname = 'requested_by_membership_id' AND NOT attisdropped)
       OR NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.access_events'::regclass
                      AND conname = 'access_events_event_type_check' AND pg_get_constraintdef(oid) LIKE '%<= 14%') THEN
        RAISE EXCEPTION '024: colunas ou restricoes da regra de renovacao ausentes';
    END IF;
    -- restricoes com OR de access_events na forma que volta igual de uma restauracao (achado da R015)
    IF (SELECT count(*) FROM pg_constraint WHERE conrelid = 'public.access_events'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) IN ('CHECK (((event_type < 9) OR (event_type > 14) OR (grant_id IS NOT NULL)))',
                                            'CHECK (((event_type < 1) OR (event_type > 4) OR (invitation_id IS NOT NULL)))')) <> 2 THEN
        RAISE EXCEPTION '024: restricoes de access_events fora da forma restauravel';
    END IF;
    -- gatilhos ligados
    IF (SELECT count(*) FROM pg_trigger WHERE tgname IN ('trg_org_role_assignments_issuer_authority',
            'trg_workspace_role_assignments_issuer_authority', 'trg_organization_memberships_issuer_authority', 'trg_users_issuer_authority',
            'trg_client_workspace_grants_request')
          AND NOT tgisinternal AND tgenabled = 'O') <> 5 THEN
        RAISE EXCEPTION '024: gatilhos ausentes ou desligados';
    END IF;
END;
$$;

COMMIT;
