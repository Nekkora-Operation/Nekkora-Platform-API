-- Nekkora Platform - criacao de organizacoes por perfil, pedido e aprovacao, responsavel, limite de organizacoes vazias,
-- desativar e reativar, e organizacoes novas nascendo no modelo por licenca
-- (PLATFORM-LICENCIAMENTO-001, E7; decisoes D7, D9, D10, D11, D12 e D13). Contrato: docs/platform-licenciamento-001/t08-organizacoes.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 061 a 066. Numero 067 conferido em 19/11 contra a dev (86591bb; a maior era 060)
-- e os PRs abertos desta missao (061 a 066). Numeracao contigua: se a ordem de integracao mudar, e renumerada sem mudar o
-- conteudo.
--
-- *** ELEVACAO: substitui e cria funcoes do dono nekkora_identity_owner (017) e uma do dono nekkora_licensing_owner (063), e da
-- *** a esses donos leituras e uma escrita novas (abaixo). Exige a janela de SUPERUSER do migrador. SO LABORATORIO E TESTES ate
-- *** autorizacao especifica: NAO esta em ops/homologacao/implantacao/elevacao.txt.
--
-- O QUE FAZ
--   1. PERFIL de quem cria, decidido no servidor a cada tentativa, pelas contas gravadas:
--        ADMIN   tem conta interna ativa com papel de administrador em alguma organizacao ativa: cria direto;
--        MEMBER  tem conta interna ativa e em nenhuma e administradora: NAO cria; solicita ao administrador de uma organizacao
--                em que e membro;
--        CLIENT  nao tem conta interna ativa e ha conta do tipo Cliente ativa com o MESMO e-mail verificado: nao cria nem solicita;
--        FIRST   nenhuma das anteriores (recem-cadastrada, ou sem conta ativa): cria direto.
--      Ser Cliente em A nao tira a administracao de B (cai em ADMIN ou MEMBER pela conta interna).
--   2. RESPONSAVEL pela organizacao (organization_responsibility): a identidade que a criou ou que a aprovacao escolheu. As
--      organizacoes existentes recebem o autor registrado na 034 (organization_authorship), quando ha.
--   3. LIMITE DE VAZIAS: tres organizacoes ativas sem nenhuma automacao por responsavel (configuravel). Conferido na criacao,
--      na aprovacao e na reativacao, sob a trava da identidade responsavel. O TETO FIXO de 10 organizacoes criadas (017) e RETIRADO (decisao da
--      titular): nao ha limite fixo de quantas organizacoes uma pessoa cria; o que limita e o limite configuravel de vazias.
--   4. PEDIDO E APROVACAO (organization_creation_requests): o membro pede, com a confirmacao reforcada da sessao; um administrador
--      da organizacao em que ele pediu aprova ou recusa. Na aprovacao ele ESCOLHE o responsavel - ele mesmo (padrao) ou o
--      solicitante -, o limite do escolhido e reconferido e a organizacao nasce na MESMA transacao. So o responsavel recebe
--      acesso (administrador da nova organizacao); o outro nao recebe nada.
--   5. DESATIVAR (organization_deactivations): o responsavel, que ainda administra a organizacao, a desativa (situacao 3). Recusado
--      com trabalho em andamento. Nada e apagado: historico, licencas, periodos, vinculos e entregas ficam como estao, e a
--      licenca continua vinculada a mesma automacao. REATIVAR: o responsavel, pela lista de organizacoes dele; o limite de
--      vazias e reconferido; so desfaz a desativacao feita por esta acao.
--   6. BLOQUEIO DA NEKKORA (organization_nekkora_holds): registro administrativo (pelo migrador). Com bloqueio aberto, a
--      reativacao e recusada. A reativacao tambem so vale para a situacao 3 que tem desativacao aberta feita por esta acao.
--   7. D7 - organizacoes NOVAS no modelo por licenca: chave license_rollout, DESLIGADA. Ligada (procedimento administrativo, com
--      quem autorizou), toda organizacao criada dali em diante nasce no modelo por licenca, com o ato 8 na trilha. Nao ha data.
--
-- O QUE NAO FAZ
--   - nao muda o modelo de nenhuma organizacao existente; nao liga a chave da D7; nao concede licenca;
--   - nao cria a excecao do administrador da Nekkora para criar organizacao em nome de alguem (sem definicao);
--   - nao apaga, encerra nem libera nada na desativacao; nao desativa organizacao de quem nao e o responsavel;
--   - nao reconhece como Cliente quem usa OUTRO e-mail: para o sistema e outra pessoa (limite declarado no contrato).
--
-- LEITURAS E ESCRITA NOVAS DOS DONOS
--   nekkora_identity_owner: le contas do tipo Cliente (organizacao, situacao, e-mail normalizado) e os vinculos de cliente
--   (situacao), para reconhecer o perfil; le a existencia de automacoes (so a organizacao) das organizacoes de que a identidade
--   em contexto e responsavel; ALTERA organizations.status, so na organizacao em contexto da funcao.
--   nekkora_licensing_owner: nenhuma leitura nova (usa as da 065); grava o ato 8 e o modelo da organizacao recem-criada.
--
-- DADOS EXISTENTES: nenhuma linha existente e alterada. organization_responsibility recebe uma linha por organizacao com autor
-- registrado na 034. Organizacoes acima do limite de vazias continuam como estao: so criacoes novas sao recusadas.
--
-- REVERSAO (manual, em janela de elevacao; so sem pedidos, desativacoes e organizacoes nascidas pelo ato 8):
--   recriar identity_create_organization_impl e identity_org_creation_allowed com o corpo da 017 (o teto de 10 volta); DROP das funcoes novas; DROP das seis tabelas novas;
--   retirar as politicas identity_client_probe / identity_responsibility_context e os privilegios desta migration; devolver as
--   CHECKs de organization_license_acts e o gatilho de organization_license_enforcement ao texto da 061.

BEGIN;

-- === 1. Pre-condicao ===
DO $$
BEGIN
    IF to_regprocedure('public.capacity_reserve(uuid)') IS NULL OR to_regprocedure('public.license_work_in_progress(uuid, uuid)') IS NULL
       OR to_regclass('public.organization_authorship') IS NULL THEN
        RAISE EXCEPTION '067 exige a 034 e a 061 a 066 aplicadas';
    END IF;
    IF to_regclass('public.organization_responsibility') IS NOT NULL THEN
        RAISE EXCEPTION '067: organization_responsibility ja existe';
    END IF;
END;
$$;

-- === 2. Configuracao (so o migrador escreve) ===
CREATE TABLE public.organization_creation_settings (
    singleton                  BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    empty_organizations_limit  INTEGER NOT NULL CHECK (empty_organizations_limit BETWEEN 1 AND 1000),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_by                 TEXT NOT NULL CHECK (length(btrim(updated_by)) BETWEEN 3 AND 200)
);
COMMENT ON TABLE public.organization_creation_settings IS
    'Limite de organizacoes VAZIAS (ativas, sem nenhuma automacao) por responsavel (067, D13). Configuravel por procedimento '
    'administrativo; updated_by: quem autorizou.';
INSERT INTO public.organization_creation_settings (empty_organizations_limit, updated_by) VALUES (3, 'migration 067 (decisao D13 de 19/11/2026)');

CREATE TABLE public.license_rollout (
    singleton                   BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    new_organizations_licensed  BOOLEAN NOT NULL DEFAULT false,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_by                  TEXT NOT NULL CHECK (length(btrim(updated_by)) BETWEEN 3 AND 200)
);
COMMENT ON TABLE public.license_rollout IS
    'Ativacao controlada (067, D7): com new_organizations_licensed ligada, toda organizacao criada dali em diante nasce no modelo por '
    'licenca. Nasce DESLIGADA. Ligar e procedimento administrativo, depois de fluxos e interface validados; updated_by: quem autorizou.';
INSERT INTO public.license_rollout (new_organizations_licensed, updated_by) VALUES (false, 'migration 067 (decisao D7: nasce desligada)');

-- === 3. Responsavel, pedidos, desativacoes e bloqueios ===
CREATE TABLE public.organization_responsibility (
    organization_id          UUID PRIMARY KEY REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    responsible_identity_id  UUID NOT NULL REFERENCES public.identities (identity_id) ON DELETE RESTRICT,
    source                   SMALLINT NOT NULL CHECK (source IN (1, 2, 3)),
    request_id               UUID,
    recorded_at              TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((source = 2) = (request_id IS NOT NULL))
);
CREATE INDEX ix_organization_responsibility_identity ON public.organization_responsibility (responsible_identity_id);
COMMENT ON TABLE public.organization_responsibility IS
    'Responsavel pela organizacao (067): a identidade de quem e contado o limite de organizacoes vazias e quem pode desativa-la e '
    'reativa-la. source: 1=criou direto, 2=escolhida na aprovacao de um pedido, 3=autor registrado na 034 (organizacao anterior a 067). '
    'Nao e propriedade e, sozinha, nao concede permissao.';

CREATE TABLE public.organization_creation_requests (
    request_id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id           UUID NOT NULL REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    requester_identity_id     UUID NOT NULL REFERENCES public.identities (identity_id) ON DELETE RESTRICT,
    requester_membership_id   UUID NOT NULL,
    name                      TEXT NOT NULL CHECK (char_length(name) BETWEEN 1 AND 120),
    legal_name                TEXT NOT NULL CHECK (char_length(legal_name) BETWEEN 1 AND 120),
    state                     SMALLINT NOT NULL DEFAULT 1 CHECK (state IN (1, 2, 3)),
    requested_at              TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    decided_at                TIMESTAMPTZ,
    decided_by_membership_id  UUID,
    responsible               SMALLINT CHECK (responsible IN (1, 2)),
    refusal_reason            TEXT CHECK (refusal_reason IS NULL OR length(btrim(refusal_reason)) BETWEEN 10 AND 500),
    created_organization_id   UUID REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, requester_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, decided_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK ((state = 1) = (decided_at IS NULL) AND (state = 1) = (decided_by_membership_id IS NULL)),
    CHECK ((state = 2) = (responsible IS NOT NULL) AND (state = 2) = (created_organization_id IS NOT NULL)),
    CHECK ((state = 3) = (refusal_reason IS NOT NULL))
);
CREATE INDEX ix_organization_creation_requests_org ON public.organization_creation_requests (organization_id, state, requested_at);
CREATE INDEX ix_organization_creation_requests_requester ON public.organization_creation_requests (requester_identity_id, requested_at);
COMMENT ON TABLE public.organization_creation_requests IS
    'Pedido de criacao de organizacao (067, D9 a D12): organization_id e a organizacao EM QUE o membro pediu, cujo administrador decide. '
    'state: 1=PENDENTE, 2=APROVADO (a organizacao nasceu na mesma transacao), 3=RECUSADO. responsible: 1=o administrador que aprovou, '
    '2=o solicitante. So o responsavel recebe acesso a nova organizacao.';
COMMENT ON COLUMN public.organization_creation_requests.responsible IS '1=APPROVER, 2=REQUESTER';

CREATE TABLE public.organization_deactivations (
    deactivation_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             UUID NOT NULL REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    deactivated_by_identity_id  UUID NOT NULL REFERENCES public.identities (identity_id) ON DELETE RESTRICT,
    deactivated_at              TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    reactivated_by_identity_id  UUID REFERENCES public.identities (identity_id) ON DELETE RESTRICT,
    reactivated_at              TIMESTAMPTZ,
    CHECK ((reactivated_at IS NULL) = (reactivated_by_identity_id IS NULL))
);
CREATE UNIQUE INDEX ux_organization_deactivations_open ON public.organization_deactivations (organization_id) WHERE reactivated_at IS NULL;
COMMENT ON TABLE public.organization_deactivations IS
    'Desativacao de uma organizacao pelo RESPONSAVEL (067, D13): a organizacao vai a situacao 3 e nada mais muda. Linha aberta = '
    'desativada por esta acao; so ela pode ser desfeita pela reativacao. Um bloqueio da Nekkora nao tem linha aqui.';

CREATE TABLE public.organization_nekkora_holds (
    hold_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    reason           TEXT NOT NULL CHECK (length(btrim(reason)) BETWEEN 10 AND 1000),
    registered_by    TEXT NOT NULL CHECK (length(btrim(registered_by)) BETWEEN 3 AND 200),
    registered_at    TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lifted_by        TEXT CHECK (lifted_by IS NULL OR length(btrim(lifted_by)) BETWEEN 3 AND 200),
    lifted_at        TIMESTAMPTZ,
    CHECK ((lifted_at IS NULL) = (lifted_by IS NULL))
);
CREATE UNIQUE INDEX ux_organization_nekkora_holds_open ON public.organization_nekkora_holds (organization_id) WHERE lifted_at IS NULL;
COMMENT ON TABLE public.organization_nekkora_holds IS
    'Bloqueio de uma organizacao pela NEKKORA (067): registro administrativo, escrito so pelo migrador, junto da mudanca de situacao '
    'feita pela operacao. Com bloqueio aberto, a reativacao pelo responsavel e recusada.';

-- so de acrescimo onde cabe: nada e apagado
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['organization_creation_settings', 'license_rollout', 'organization_responsibility', 'organization_creation_requests',
                             'organization_deactivations', 'organization_nekkora_holds'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('REVOKE ALL ON public.%I FROM PUBLIC', t);
        EXECUTE format('CREATE TRIGGER trg_%s_prevent_delete BEFORE DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.prevent_delete()', t, t);
        EXECUTE format('CREATE TRIGGER trg_%s_prevent_truncate BEFORE TRUNCATE ON public.%I FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate()', t, t);
    END LOOP;
END;
$$;

-- o dono da identidade: so pelas funcoes dele
GRANT SELECT ON public.organization_creation_settings, public.organization_nekkora_holds TO nekkora_identity_owner;
GRANT SELECT, INSERT ON public.organization_responsibility TO nekkora_identity_owner;
GRANT SELECT, INSERT, UPDATE (state, decided_at, decided_by_membership_id, responsible, refusal_reason, created_organization_id)
    ON public.organization_creation_requests TO nekkora_identity_owner;
GRANT SELECT, INSERT, UPDATE (reactivated_by_identity_id, reactivated_at) ON public.organization_deactivations TO nekkora_identity_owner;
CREATE POLICY identity_owner_all ON public.organization_creation_settings AS PERMISSIVE FOR SELECT TO nekkora_identity_owner USING (true);
CREATE POLICY identity_owner_all ON public.organization_nekkora_holds AS PERMISSIVE FOR SELECT TO nekkora_identity_owner USING (true);
CREATE POLICY identity_owner_all ON public.organization_responsibility AS PERMISSIVE FOR ALL TO nekkora_identity_owner USING (true) WITH CHECK (true);
CREATE POLICY identity_owner_all ON public.organization_creation_requests AS PERMISSIVE FOR ALL TO nekkora_identity_owner USING (true) WITH CHECK (true);
CREATE POLICY identity_owner_all ON public.organization_deactivations AS PERMISSIVE FOR ALL TO nekkora_identity_owner USING (true) WITH CHECK (true);
-- a Platform le os pedidos da organizacao em contexto (a lista do administrador) e a chave da D7 (para mostrar); nao escreve
GRANT SELECT ON public.organization_creation_requests, public.license_rollout, public.organization_creation_settings TO nekkora_platform;
CREATE POLICY tenant_request_context ON public.organization_creation_requests AS PERMISSIVE FOR SELECT TO nekkora_platform
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid);
CREATE POLICY platform_read ON public.license_rollout AS PERMISSIVE FOR SELECT TO nekkora_platform USING (true);
CREATE POLICY platform_read ON public.organization_creation_settings AS PERMISSIVE FOR SELECT TO nekkora_platform USING (true);
GRANT SELECT ON public.license_rollout TO nekkora_licensing_owner;
CREATE POLICY licensing_owner_read ON public.license_rollout AS PERMISSIVE FOR SELECT TO nekkora_licensing_owner USING (true);

-- leituras novas do dono da identidade em tabelas existentes
GRANT SELECT (organization_id, user_id, status) ON public.client_memberships TO nekkora_identity_owner;
CREATE POLICY identity_client_probe ON public.client_memberships AS PERMISSIVE FOR SELECT TO nekkora_identity_owner USING (true);
CREATE POLICY identity_client_probe ON public.users AS PERMISSIVE FOR SELECT TO nekkora_identity_owner USING (account_type = 2);
GRANT SELECT (organization_id) ON public.automations TO nekkora_identity_owner;
CREATE POLICY identity_responsibility_context ON public.automations AS PERMISSIVE FOR SELECT TO nekkora_identity_owner
    USING (EXISTS (SELECT 1 FROM public.organization_responsibility r WHERE r.organization_id = automations.organization_id
                      AND r.responsible_identity_id = NULLIF(pg_catalog.current_setting('nekkora.fn_identity_id', true), '')::uuid));
CREATE POLICY identity_responsibility_context ON public.organizations AS PERMISSIVE FOR SELECT TO nekkora_identity_owner
    USING (EXISTS (SELECT 1 FROM public.organization_responsibility r WHERE r.organization_id = organizations.organization_id
                      AND r.responsible_identity_id = NULLIF(pg_catalog.current_setting('nekkora.fn_identity_id', true), '')::uuid));
-- a unica escrita nova: a situacao da organizacao, so na organizacao em contexto da funcao (politica identity_function_context, 017)
GRANT UPDATE (status) ON public.organizations TO nekkora_identity_owner;

-- as organizacoes existentes: o autor registrado na 034 e o responsavel
INSERT INTO public.organization_responsibility (organization_id, responsible_identity_id, source)
SELECT a.organization_id, a.creator_identity_id, 3 FROM public.organization_authorship a;

-- === 4. D7: o ato 8 (a organizacao NASCEU no modelo por licenca) ===
ALTER TABLE public.organization_license_acts DROP CONSTRAINT organization_license_acts_act_check;
ALTER TABLE public.organization_license_acts ADD CONSTRAINT organization_license_acts_act_check CHECK (act BETWEEN 1 AND 8);
DO $$
DECLARE
    c RECORD;
BEGIN
    -- as tres regras de "quem fez" da 061 passam a conhecer o ato 8 (sem operador: e a criacao que o grava, pela chave da D7)
    FOR c IN SELECT conname, pg_get_constraintdef(oid) AS def FROM pg_constraint
              WHERE conrelid = 'public.organization_license_acts'::regclass AND contype = 'c'
                AND (pg_get_constraintdef(oid) LIKE '%operator_user_id IS NULL%' OR pg_get_constraintdef(oid) LIKE '%justification IS NOT NULL%'
                     OR pg_get_constraintdef(oid) LIKE '%ARRAY[6, 7]%') LOOP
        EXECUTE format('ALTER TABLE public.organization_license_acts DROP CONSTRAINT %I', c.conname);
    END LOOP;
END;
$$;
ALTER TABLE public.organization_license_acts
    ADD CONSTRAINT organization_license_acts_who_067_check CHECK ((act IN (4, 8)) = (operator_user_id IS NULL)),
    ADD CONSTRAINT organization_license_acts_justified_067_check CHECK (act IN (4, 8) OR justification IS NOT NULL),
    ADD CONSTRAINT organization_license_acts_model_067_check CHECK (act NOT IN (6, 7, 8) OR license_id IS NULL),
    ADD CONSTRAINT organization_license_acts_born_067_check CHECK (act <> 8 OR (detail ? 'rolloutAuthorizedBy' AND actor_membership_id IS NULL));
COMMENT ON TABLE public.organization_license_acts IS
    'Trilha dos atos de licenca (061; 067 acrescenta o 8), so acrescimo. act: 1=CONCEDIDA, 2=RENOVADA, 3=ENCERRADA, 4=VINCULADA a uma '
    'automacao pelo administrador da organizacao, 5=TRANSFERIDA para outra automacao, 6=EXIGENCIA_LIGADA, 7=EXIGENCIA_DESLIGADA, '
    '8=NASCEU_NO_MODELO_POR_LICENCA (organizacao criada com a chave da D7 ligada; sem operador: detail.rolloutAuthorizedBy diz quem '
    'autorizou a chave). O ato 4 e do administrador (actor_membership_id); 1, 2, 3, 5, 6 e 7, do operador da Nekkora, com justificativa.';

CREATE OR REPLACE FUNCTION public.organization_license_enforcement_on_write() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_act SMALLINT;
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.organization_id <> OLD.organization_id OR NEW.act_id = OLD.act_id OR NEW.model = OLD.model) THEN
        RAISE EXCEPTION 'o modelo so muda por um ato novo que o inverta' USING ERRCODE = '42501';
    END IF;
    SELECT a.act INTO v_act FROM public.organization_license_acts a
     WHERE a.organization_id = NEW.organization_id AND a.act_id = NEW.act_id;
    -- 067: o ato 8 so vale na PRIMEIRA linha da organizacao (ela nasce no modelo); ligar depois continua sendo o ato 6
    IF NOT (v_act IS NOT DISTINCT FROM (CASE NEW.model WHEN 2 THEN 6 ELSE 7 END)::smallint OR (TG_OP = 'INSERT' AND NEW.model = 2 AND v_act IS NOT DISTINCT FROM 8)) THEN
        RAISE EXCEPTION 'o ato informado nao e o que liga ou desliga a exigencia' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- a organizacao recem-criada nasce no modelo por licenca, se a chave estiver ligada. Devolve se nasceu.
CREATE FUNCTION public.license_born_licensed(p_org UUID) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    r RECORD;
    v_act UUID := pg_catalog.gen_random_uuid();
BEGIN
    SELECT x.new_organizations_licensed, x.updated_by, x.updated_at INTO r FROM public.license_rollout x;
    IF NOT coalesce(r.new_organizations_licensed, false) THEN
        RETURN false;
    END IF;
    IF EXISTS (SELECT 1 FROM public.organization_license_enforcement e WHERE e.organization_id = p_org) THEN
        RAISE EXCEPTION 'a organizacao ja tem modelo registrado' USING ERRCODE = '23514';
    END IF;
    INSERT INTO public.organization_license_acts (act_id, organization_id, act, detail)
    VALUES (v_act, p_org, 8, pg_catalog.jsonb_build_object('rolloutAuthorizedBy', r.updated_by, 'rolloutSince', r.updated_at));
    INSERT INTO public.organization_license_enforcement (organization_id, model, act_id) VALUES (p_org, 2, v_act);
    RETURN true;
END;
$$;
COMMENT ON FUNCTION public.license_born_licensed(uuid) IS
    'D7 (067): com a chave license_rollout ligada, registra que a organizacao recem-criada nasce no modelo por licenca (ato 8). Chamada '
    'so pela criacao de organizacao.';

-- trabalho em andamento em QUALQUER automacao da organizacao (a mesma regra da transferencia, 065, sem o filtro da automacao)
CREATE FUNCTION public.license_organization_busy(p_org UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM public.automations a WHERE a.organization_id = p_org AND public.license_work_in_progress(p_org, a.automation_id));
$$;
COMMENT ON FUNCTION public.license_organization_busy(uuid) IS
    'A organizacao tem, ou ainda pode ter, trabalho em andamento em alguma automacao (067; regra da 065). Usada pela desativacao.';

ALTER FUNCTION public.license_born_licensed(uuid) OWNER TO nekkora_licensing_owner;
ALTER FUNCTION public.license_organization_busy(uuid) OWNER TO nekkora_licensing_owner;
REVOKE ALL ON FUNCTION public.license_born_licensed(uuid), public.license_organization_busy(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.license_born_licensed(uuid), public.license_organization_busy(uuid) TO nekkora_identity_owner;

-- === 5. Funcoes internas do dono da identidade (SECURITY INVOKER; sem EXECUTE para mais ninguem) ===

-- O perfil de quem cria. Exige nekkora.fn_identity_id = p_identity (leitura das contas da propria identidade).
CREATE FUNCTION public.identity_org_profile(p_identity UUID) RETURNS TEXT
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM public.identity_users iu
                       JOIN public.users u ON u.user_id = iu.user_id
                       JOIN public.organizations o ON o.organization_id = u.organization_id
                       JOIN public.organization_memberships m ON m.user_id = u.user_id AND m.organization_id = u.organization_id
                       JOIN public.organization_role_assignments a ON a.organization_id = m.organization_id AND a.membership_id = m.membership_id AND a.status = 1
                       JOIN public.roles r ON r.role_id = a.role_id AND r.code = 'ORG_ADMIN'
                      WHERE iu.identity_id = p_identity AND u.account_type = 1 AND u.status = 2 AND u.email_verified_at IS NOT NULL
                        AND o.status = 1 AND m.status = 2) THEN 'ADMIN'
        WHEN EXISTS (SELECT 1 FROM public.identity_users iu
                       JOIN public.users u ON u.user_id = iu.user_id
                       JOIN public.organizations o ON o.organization_id = u.organization_id
                       JOIN public.organization_memberships m ON m.user_id = u.user_id AND m.organization_id = u.organization_id
                      WHERE iu.identity_id = p_identity AND u.account_type = 1 AND u.status = 2 AND u.email_verified_at IS NOT NULL
                        AND o.status = 1 AND m.status = 2) THEN 'MEMBER'
        WHEN EXISTS (SELECT 1 FROM public.identities i
                       JOIN public.users u ON u.email_normalized = i.email_normalized AND u.account_type = 2 AND u.status = 2
                       JOIN public.client_memberships c ON c.user_id = u.user_id AND c.organization_id = u.organization_id AND c.status = 2
                      WHERE i.identity_id = p_identity) THEN 'CLIENT'
        ELSE 'FIRST' END;
$$;

-- Organizacoes ATIVAS e VAZIAS (sem nenhuma automacao) de que a identidade e responsavel. Exige nekkora.fn_identity_id = p_identity.
CREATE FUNCTION public.identity_empty_organizations(p_identity UUID) RETURNS UUID[]
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT coalesce(pg_catalog.array_agg(o.organization_id ORDER BY o.organization_id), ARRAY[]::uuid[])
      FROM public.organization_responsibility r
      JOIN public.organizations o ON o.organization_id = r.organization_id AND o.status = 1
     WHERE r.responsible_identity_id = p_identity
       AND NOT EXISTS (SELECT 1 FROM public.automations a WHERE a.organization_id = o.organization_id);
$$;

-- Por que a identidade NAO pode ser responsavel por mais uma organizacao agora (nulo = pode). Sob a trava da identidade.
CREATE FUNCTION public.identity_org_limit_reason(p_identity UUID) RETURNS TEXT
LANGUAGE plpgsql STABLE SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    -- o teto fixo de 10 organizacoes criadas (017) foi retirado: so o limite configuravel de vazias segura
    IF pg_catalog.cardinality(public.identity_empty_organizations(p_identity))
       >= (SELECT s.empty_organizations_limit FROM public.organization_creation_settings s) THEN
        RETURN 'EMPTY_LIMIT';
    END IF;
    RETURN NULL;
END;
$$;

-- Provisiona a organizacao para a identidade RESPONSAVEL (o corpo da criacao da 017), registra o responsavel e a D7.
-- Quem chama ja conferiu tudo e segura a linha da identidade.
CREATE FUNCTION public.identity_provision_organization(p_identity UUID, p_name TEXT, p_legal_name TEXT, p_source SMALLINT, p_request UUID)
RETURNS UUID LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    i RECORD;
    v_org UUID := pg_catalog.gen_random_uuid();
    v_user UUID;
    v_membership UUID;
    v_corr UUID := pg_catalog.gen_random_uuid();
BEGIN
    SELECT x.email, x.display_name, x.email_verified_at INTO i FROM public.identities x
     WHERE x.identity_id = p_identity AND x.status = 2 AND x.email_verified_at IS NOT NULL;
    IF i.email IS NULL THEN
        RETURN NULL;
    END IF;
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', '', true);
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', v_org::text, true);
    INSERT INTO public.organizations (organization_id, name, legal_name, status) VALUES (v_org, p_name, p_legal_name, 1);
    INSERT INTO public.users (organization_id, account_type, display_name, email, email_verified_at, status)
    VALUES (v_org, 1, i.display_name, i.email, i.email_verified_at, 2) RETURNING user_id INTO v_user;
    INSERT INTO public.organization_memberships (organization_id, user_id, account_type, status)
    VALUES (v_org, v_user, 1, 2) RETURNING membership_id INTO v_membership;
    PERFORM public.provision_resource_limit(v_org, 1, 1::SMALLINT, 0, v_user);
    PERFORM public.provision_resource_limit(v_org, 2, 1::SMALLINT, 0, v_user);
    PERFORM public.provision_resource_limit(v_org, 3, 1::SMALLINT, 0, v_user);
    PERFORM public.provision_resource_limit(v_org, 4, 2::SMALLINT, NULL, v_user);
    PERFORM public.provision_resource_limit(v_org, 5, 2::SMALLINT, NULL, v_user);
    PERFORM public.provision_first_org_admin(v_org, v_membership, v_user, 'cadastro_web', v_corr);
    INSERT INTO public.identity_users (identity_id, user_id, link_channel) VALUES (p_identity, v_user, 'cadastro_web');
    INSERT INTO public.identity_events (identity_id, event_type, target_organization_id, correlation_id)
    VALUES (p_identity, 6, v_org, v_corr);
    INSERT INTO public.organization_responsibility (organization_id, responsible_identity_id, source, request_id)
    VALUES (v_org, p_identity, p_source, p_request);
    PERFORM public.license_born_licensed(v_org);
    RETURN v_org;
END;
$$;

-- === 6. Criacao direta: so FIRST e ADMIN, dentro do limite de vazias (substitui o corpo da 017; mesma assinatura e dono) ===
CREATE OR REPLACE FUNCTION public.identity_create_organization_impl(p_session_token TEXT, p_name TEXT, p_legal_name TEXT)
RETURNS UUID LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    s RECORD;
    v_ok UUID;
BEGIN
    PERFORM public.identity_require_read_committed();
    SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
    IF s.o_identity_id IS NULL THEN
        RETURN NULL;
    END IF;
    -- a trava da identidade: o limite de vazias e a criacao sao uma coisa so
    SELECT x.identity_id INTO v_ok FROM public.identities x
     WHERE x.identity_id = s.o_identity_id AND x.status = 2 AND x.email_verified_at IS NOT NULL FOR UPDATE;
    IF v_ok IS NULL THEN
        RETURN NULL;
    END IF;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', '', true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', s.o_identity_id::text, true);
    -- 067: o perfil decide (D9, D11): membro solicita, Cliente nao cria
    IF public.identity_org_profile(s.o_identity_id) NOT IN ('FIRST', 'ADMIN') THEN
        RETURN NULL;
    END IF;
    IF public.identity_org_limit_reason(s.o_identity_id) IS NOT NULL THEN
        RETURN NULL;
    END IF;
    IF NOT public.identity_session_proof(s.o_session_id, pg_catalog.clock_timestamp(), 'step_up') THEN
        RETURN NULL;
    END IF;
    RETURN public.identity_provision_organization(s.o_identity_id, p_name, p_legal_name, 1::SMALLINT, NULL);
END;
$$;

-- === 7. Funcoes publicas (SECURITY DEFINER, dono nekkora_identity_owner, EXECUTE so da Platform) ===

-- canCreateOrganization (017, R5), SEM o teto fixo de 10: verdadeiro se a sessao esta ativa, no prazo, com MFA e nao restrita, e
-- a identidade esta ACTIVE e verificada. So indicacao para a interface; o perfil e o limite de vazias sao conferidos pela
-- aplicacao (identity_org_creation_state) e, de novo, pela criacao. Mesma assinatura, mesmo dono e mesmos privilegios da 017.
CREATE OR REPLACE FUNCTION public.identity_org_creation_allowed(p_session_token TEXT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1
          FROM public.identity_sessions s JOIN public.identities i ON i.identity_id = s.identity_id
         WHERE s.session_token_hash = public.identity_token_hash(p_session_token)
           AND s.revoked_at IS NULL AND s.expires_at > pg_catalog.clock_timestamp() AND s.mfa_verified_at IS NOT NULL
           AND (s.recovery_used_at IS NULL OR s.recovery_cleared_at IS NOT NULL)
           AND i.status = 2 AND i.email_verified_at IS NOT NULL)
$$;

-- O estado da criacao para a pessoa da sessao: so indicacao para a interface e para a aplicacao escolher o motivo da recusa.
-- A criacao e o pedido reconferem tudo.
CREATE FUNCTION public.identity_org_creation_state(p_session_token TEXT)
RETURNS TABLE (profile TEXT, organizations_created BIGINT, empty_organization_ids UUID[], empty_limit INTEGER, pending_requests BIGINT)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
#variable_conflict use_column
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    s RECORD;
BEGIN
    BEGIN
        SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
        IF s.o_identity_id IS NOT NULL AND NOT s.o_restrita THEN
            PERFORM pg_catalog.set_config('nekkora.fn_organization_id', '', true);
            PERFORM pg_catalog.set_config('nekkora.fn_identity_id', s.o_identity_id::text, true);
            RETURN QUERY
            SELECT public.identity_org_profile(s.o_identity_id),
                   (SELECT pg_catalog.count(*) FROM public.identity_users iu WHERE iu.identity_id = s.o_identity_id AND iu.link_channel = 'cadastro_web'),
                   public.identity_empty_organizations(s.o_identity_id),
                   (SELECT c.empty_organizations_limit FROM public.organization_creation_settings c),
                   (SELECT pg_catalog.count(*) FROM public.organization_creation_requests q WHERE q.requester_identity_id = s.o_identity_id AND q.state = 1);
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

-- O membro pede a criacao ao administrador da organizacao p_org, em que e membro interno ativo. Devolve o pedido, ou nulo.
CREATE FUNCTION public.identity_request_organization(p_session_token TEXT, p_org UUID, p_name TEXT, p_legal_name TEXT)
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    s RECORD;
    v_ok UUID;
    v_membership UUID;
    v_r UUID;
BEGIN
    BEGIN
        PERFORM public.identity_require_read_committed();
        SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
        IF s.o_identity_id IS NOT NULL AND NOT s.o_restrita THEN
            SELECT x.identity_id INTO v_ok FROM public.identities x
             WHERE x.identity_id = s.o_identity_id AND x.status = 2 AND x.email_verified_at IS NOT NULL FOR UPDATE;
            PERFORM pg_catalog.set_config('nekkora.fn_organization_id', '', true);
            PERFORM pg_catalog.set_config('nekkora.fn_identity_id', s.o_identity_id::text, true);
            -- a conta interna ativa da pessoa NESTA organizacao (D11: o perfil e o da organizacao em que ela age)
            SELECT m.membership_id INTO v_membership
              FROM public.identity_users iu
              JOIN public.users u ON u.user_id = iu.user_id
              JOIN public.organizations o ON o.organization_id = u.organization_id
              JOIN public.organization_memberships m ON m.user_id = u.user_id AND m.organization_id = u.organization_id
             WHERE iu.identity_id = s.o_identity_id AND u.organization_id = p_org AND u.account_type = 1 AND u.status = 2
               AND u.email_verified_at IS NOT NULL AND o.status = 1 AND m.status = 2;
            IF v_ok IS NOT NULL AND v_membership IS NOT NULL
               AND public.identity_org_profile(s.o_identity_id) = 'MEMBER'
               AND (SELECT pg_catalog.count(*) FROM public.organization_creation_requests q
                     WHERE q.requester_identity_id = s.o_identity_id AND q.state = 1) < 3
               AND public.identity_session_proof(s.o_session_id, pg_catalog.clock_timestamp(), 'step_up') THEN
                INSERT INTO public.organization_creation_requests (organization_id, requester_identity_id, requester_membership_id, name, legal_name)
                VALUES (p_org, s.o_identity_id, v_membership, p_name, p_legal_name) RETURNING request_id INTO v_r;
            END IF;
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

-- Os pedidos da pessoa da sessao.
CREATE FUNCTION public.identity_organization_requests(p_session_token TEXT)
RETURNS TABLE (request_id UUID, organization_id UUID, organization_name TEXT, name TEXT, legal_name TEXT, state SMALLINT, requested_at TIMESTAMPTZ,
               decided_at TIMESTAMPTZ, responsible SMALLINT, refusal_reason TEXT, created_organization_id UUID)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
#variable_conflict use_column
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    s RECORD;
BEGIN
    BEGIN
        SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
        IF s.o_identity_id IS NOT NULL AND NOT s.o_restrita THEN
            PERFORM pg_catalog.set_config('nekkora.fn_organization_id', '', true);
            PERFORM pg_catalog.set_config('nekkora.fn_identity_id', s.o_identity_id::text, true);
            RETURN QUERY
            SELECT q.request_id, q.organization_id, (SELECT o.name FROM public.organizations o WHERE o.organization_id = q.organization_id),
                   q.name, q.legal_name, q.state, q.requested_at, q.decided_at, q.responsible, q.refusal_reason,
                   -- a organizacao criada so aparece para quem ficou responsavel por ela
                   CASE WHEN q.responsible = 2 THEN q.created_organization_id END
              FROM public.organization_creation_requests q
             WHERE q.requester_identity_id = s.o_identity_id
             ORDER BY q.requested_at DESC, q.request_id LIMIT 50;
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

-- A decisao do administrador da organizacao em que o pedido foi feito. A aplicacao ja conferiu a sessao, a permissao e a
-- confirmacao reforcada de p_membership; o banco reconfere que ele administra p_org AGORA e faz o resto.
-- Devolve 'APPROVED:<organizacao>', 'REFUSED' ou o motivo da recusa do ato.
CREATE FUNCTION public.identity_decide_organization_request(p_org UUID, p_membership UUID, p_request UUID, p_approve BOOLEAN, p_responsible SMALLINT,
                                                            p_reason TEXT)
RETURNS TEXT LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    q RECORD;
    v_user UUID;
    v_identity UUID;
    v_ok UUID;
    v_why TEXT;
    v_new UUID;
    v_out TEXT;
BEGIN
    BEGIN
        PERFORM public.identity_require_read_committed();
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', '', true);
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', p_org::text, true);
        SELECT * INTO q FROM public.organization_creation_requests x WHERE x.request_id = p_request AND x.organization_id = p_org FOR UPDATE;
        -- quem decide administra a organizacao do pedido, agora
        SELECT u.user_id INTO v_user
          FROM public.organization_memberships m
          JOIN public.users u ON u.user_id = m.user_id AND u.organization_id = m.organization_id
          JOIN public.organizations o ON o.organization_id = m.organization_id
         WHERE m.organization_id = p_org AND m.membership_id = p_membership AND m.status = 2 AND u.status = 2 AND u.account_type = 1 AND o.status = 1
           AND EXISTS (SELECT 1 FROM public.organization_role_assignments a JOIN public.roles r ON r.role_id = a.role_id AND r.code = 'ORG_ADMIN'
                        WHERE a.organization_id = p_org AND a.membership_id = p_membership AND a.status = 1);
        IF q.request_id IS NULL THEN
            v_out := 'NOT_FOUND';
        ELSIF v_user IS NULL THEN
            v_out := 'NOT_ADMIN';
        ELSIF q.state <> 1 THEN
            v_out := 'NOT_PENDING';
        ELSIF q.requester_membership_id = p_membership THEN
            v_out := 'OWN_REQUEST';
        ELSIF NOT p_approve THEN
            IF p_reason IS NULL OR pg_catalog.length(pg_catalog.btrim(p_reason)) NOT BETWEEN 10 AND 500 THEN
                v_out := 'REASON_REQUIRED';
            ELSE
                UPDATE public.organization_creation_requests SET state = 3, decided_at = pg_catalog.clock_timestamp(), decided_by_membership_id = p_membership,
                       refusal_reason = pg_catalog.btrim(p_reason) WHERE request_id = p_request;
                v_out := 'REFUSED';
            END IF;
        ELSIF p_responsible IS NULL OR p_responsible NOT IN (1, 2) THEN
            v_out := 'RESPONSIBLE_REQUIRED';
        ELSE
            -- o responsavel escolhido: quem aprova (a identidade da conta dele) ou o solicitante
            IF p_responsible = 1 THEN
                SELECT iu.identity_id INTO v_identity FROM public.identity_users iu WHERE iu.user_id = v_user;
            ELSE
                v_identity := q.requester_identity_id;
                -- o solicitante ainda e membro interno ativo da organizacao em que pediu
                IF NOT EXISTS (SELECT 1 FROM public.organization_memberships m JOIN public.users u ON u.user_id = m.user_id
                                WHERE m.organization_id = p_org AND m.membership_id = q.requester_membership_id AND m.status = 2 AND u.status = 2) THEN
                    v_out := 'REQUESTER_NOT_ELIGIBLE';
                END IF;
            END IF;
            IF v_out IS NULL AND v_identity IS NULL THEN
                v_out := 'APPROVER_WITHOUT_IDENTITY';
            END IF;
            IF v_out IS NULL THEN
                -- a trava do responsavel: limite e criacao sao uma coisa so (aprovacoes e criacoes simultaneas nao passam juntas)
                SELECT x.identity_id INTO v_ok FROM public.identities x
                 WHERE x.identity_id = v_identity AND x.status = 2 AND x.email_verified_at IS NOT NULL FOR UPDATE;
                IF v_ok IS NULL THEN
                    v_out := 'RESPONSIBLE_NOT_ELIGIBLE';
                ELSE
                    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', '', true);
                    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', v_identity::text, true);
                    v_why := public.identity_org_limit_reason(v_identity);
                    IF v_why IS NOT NULL THEN
                        v_out := v_why;
                    ELSE
                        v_new := public.identity_provision_organization(v_identity, q.name, q.legal_name, 2::SMALLINT, p_request);
                        IF v_new IS NULL THEN
                            v_out := 'RESPONSIBLE_NOT_ELIGIBLE';
                        ELSE
                            PERFORM pg_catalog.set_config('nekkora.fn_identity_id', '', true);
                            PERFORM pg_catalog.set_config('nekkora.fn_organization_id', p_org::text, true);
                            UPDATE public.organization_creation_requests SET state = 2, decided_at = pg_catalog.clock_timestamp(),
                                   decided_by_membership_id = p_membership, responsible = p_responsible, created_organization_id = v_new
                             WHERE request_id = p_request;
                            v_out := 'APPROVED:' || v_new::text;
                        END IF;
                    END IF;
                END IF;
            END IF;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
    RETURN v_out;
END;
$$;

-- Desativar: o responsavel, que ainda administra a organizacao. 'OK' ou o motivo. Nulo: sessao sem condicao (ou sem a confirmacao).
CREATE FUNCTION public.identity_deactivate_organization(p_session_token TEXT, p_org UUID)
RETURNS TEXT LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    s RECORD;
    v_status SMALLINT;
    v_out TEXT;
BEGIN
    BEGIN
        PERFORM public.identity_require_read_committed();
        SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
        IF s.o_identity_id IS NOT NULL AND NOT s.o_restrita THEN
            PERFORM pg_catalog.set_config('nekkora.fn_organization_id', p_org::text, true);
            PERFORM pg_catalog.set_config('nekkora.fn_identity_id', s.o_identity_id::text, true);
            IF NOT EXISTS (SELECT 1 FROM public.organization_responsibility r
                            WHERE r.organization_id = p_org AND r.responsible_identity_id = s.o_identity_id) THEN
                v_out := 'NOT_RESPONSIBLE';
            ELSE
                -- R4 (017): quem desativa trava a organizacao; nenhuma sessao nova nasce contra esta decisao
                SELECT o.status INTO v_status FROM public.organizations o WHERE o.organization_id = p_org FOR UPDATE;
                IF v_status IS DISTINCT FROM 1 THEN
                    v_out := 'NOT_ACTIVE';
                ELSIF NOT EXISTS (SELECT 1 FROM public.identity_users iu
                                    JOIN public.users u ON u.user_id = iu.user_id
                                    JOIN public.organization_memberships m ON m.user_id = u.user_id AND m.organization_id = u.organization_id
                                    JOIN public.organization_role_assignments a ON a.organization_id = m.organization_id AND a.membership_id = m.membership_id
                                         AND a.status = 1
                                    JOIN public.roles r ON r.role_id = a.role_id AND r.code = 'ORG_ADMIN'
                                   WHERE iu.identity_id = s.o_identity_id AND u.organization_id = p_org AND u.account_type = 1 AND u.status = 2
                                     AND m.status = 2) THEN
                    v_out := 'NOT_ADMIN';
                ELSIF NOT public.identity_session_proof(s.o_session_id, pg_catalog.clock_timestamp(), 'step_up') THEN
                    v_out := NULL;
                ELSIF public.license_organization_busy(p_org) THEN
                    v_out := 'WORK_IN_PROGRESS';
                ELSE
                    UPDATE public.organizations SET status = 3 WHERE organization_id = p_org;
                    INSERT INTO public.organization_deactivations (organization_id, deactivated_by_identity_id) VALUES (p_org, s.o_identity_id);
                    v_out := 'OK';
                END IF;
            END IF;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
    RETURN v_out;
END;
$$;

-- Reativar: o responsavel desfaz a desativacao feita por esta acao. O limite de vazias e reconferido. Um bloqueio da Nekkora
-- (situacao que nao veio desta acao, ou bloqueio registrado) NAO e removido.
CREATE FUNCTION public.identity_reactivate_organization(p_session_token TEXT, p_org UUID)
RETURNS TEXT LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    s RECORD;
    v_ok UUID;
    v_status SMALLINT;
    v_open UUID;
    v_out TEXT;
BEGIN
    BEGIN
        PERFORM public.identity_require_read_committed();
        SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
        IF s.o_identity_id IS NOT NULL AND NOT s.o_restrita THEN
            -- a trava da identidade: a conta das vazias e a reativacao sao uma coisa so
            SELECT x.identity_id INTO v_ok FROM public.identities x
             WHERE x.identity_id = s.o_identity_id AND x.status = 2 AND x.email_verified_at IS NOT NULL FOR UPDATE;
            PERFORM pg_catalog.set_config('nekkora.fn_organization_id', p_org::text, true);
            PERFORM pg_catalog.set_config('nekkora.fn_identity_id', s.o_identity_id::text, true);
            IF v_ok IS NULL OR NOT EXISTS (SELECT 1 FROM public.organization_responsibility r
                                            WHERE r.organization_id = p_org AND r.responsible_identity_id = s.o_identity_id) THEN
                v_out := 'NOT_RESPONSIBLE';
            ELSE
                SELECT o.status INTO v_status FROM public.organizations o WHERE o.organization_id = p_org FOR UPDATE;
                SELECT d.deactivation_id INTO v_open FROM public.organization_deactivations d
                 WHERE d.organization_id = p_org AND d.reactivated_at IS NULL FOR UPDATE;
                IF EXISTS (SELECT 1 FROM public.organization_nekkora_holds h WHERE h.organization_id = p_org AND h.lifted_at IS NULL) THEN
                    v_out := 'BLOCKED_BY_NEKKORA';
                ELSIF v_open IS NULL OR v_status IS DISTINCT FROM 3 THEN
                    -- ativa, ou parada por outro caminho (a operacao da Nekkora): nao e desta acao
                    v_out := CASE WHEN v_status = 1 THEN 'NOT_DEACTIVATED' ELSE 'BLOCKED_BY_NEKKORA' END;
                ELSIF NOT public.identity_session_proof(s.o_session_id, pg_catalog.clock_timestamp(), 'step_up') THEN
                    v_out := NULL;
                ELSIF NOT EXISTS (SELECT 1 FROM public.automations a WHERE a.organization_id = p_org)
                      AND pg_catalog.cardinality(public.identity_empty_organizations(s.o_identity_id))
                          >= (SELECT c.empty_organizations_limit FROM public.organization_creation_settings c) THEN
                    v_out := 'EMPTY_LIMIT';
                ELSE
                    UPDATE public.organizations SET status = 1 WHERE organization_id = p_org;
                    UPDATE public.organization_deactivations SET reactivated_at = pg_catalog.clock_timestamp(), reactivated_by_identity_id = s.o_identity_id
                     WHERE deactivation_id = v_open;
                    v_out := 'OK';
                END IF;
            END IF;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
    RETURN v_out;
END;
$$;

-- O que a pessoa da sessao pode desativar ou reativar: as organizacoes de que e responsavel, com a situacao.
CREATE FUNCTION public.identity_responsible_organizations(p_session_token TEXT)
RETURNS TABLE (organization_id UUID, name TEXT, status SMALLINT, empty BOOLEAN, deactivated_at TIMESTAMPTZ, reactivable BOOLEAN)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
#variable_conflict use_column
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    s RECORD;
BEGIN
    BEGIN
        SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
        IF s.o_identity_id IS NOT NULL AND NOT s.o_restrita THEN
            PERFORM pg_catalog.set_config('nekkora.fn_organization_id', '', true);
            PERFORM pg_catalog.set_config('nekkora.fn_identity_id', s.o_identity_id::text, true);
            RETURN QUERY
            SELECT o.organization_id, o.name, o.status,
                   NOT EXISTS (SELECT 1 FROM public.automations a WHERE a.organization_id = o.organization_id),
                   d.deactivated_at,
                   o.status = 3 AND d.deactivation_id IS NOT NULL
                       AND NOT EXISTS (SELECT 1 FROM public.organization_nekkora_holds h WHERE h.organization_id = o.organization_id AND h.lifted_at IS NULL)
              FROM public.organization_responsibility r
              JOIN public.organizations o ON o.organization_id = r.organization_id
              LEFT JOIN public.organization_deactivations d ON d.organization_id = o.organization_id AND d.reactivated_at IS NULL
             WHERE r.responsible_identity_id = s.o_identity_id
             ORDER BY o.name, o.organization_id;
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

-- === 8. Dono e privilegios ===
DO $$
DECLARE
    f TEXT;
    internal CONSTANT TEXT[] := ARRAY['identity_org_profile(uuid)', 'identity_empty_organizations(uuid)', 'identity_org_limit_reason(uuid)',
                                      'identity_provision_organization(uuid, text, text, smallint, uuid)'];
    exposed CONSTANT TEXT[] := ARRAY['identity_org_creation_state(text)', 'identity_request_organization(text, uuid, text, text)',
                                     'identity_organization_requests(text)',
                                     'identity_decide_organization_request(uuid, uuid, uuid, boolean, smallint, text)',
                                     'identity_deactivate_organization(text, uuid)', 'identity_reactivate_organization(text, uuid)',
                                     'identity_responsible_organizations(text)'];
BEGIN
    FOREACH f IN ARRAY internal || exposed LOOP
        EXECUTE format('ALTER FUNCTION public.%s OWNER TO nekkora_identity_owner', f);
        EXECUTE format('REVOKE ALL ON FUNCTION public.%s FROM PUBLIC', f);
    END LOOP;
    FOREACH f IN ARRAY exposed LOOP
        EXECUTE format('GRANT EXECUTE ON FUNCTION public.%s TO nekkora_platform', f);
    END LOOP;
END;
$$;

-- === 9. Invariantes ===
DO $$
DECLARE
    p RECORD;
BEGIN
    IF (SELECT new_organizations_licensed FROM public.license_rollout) THEN
        RAISE EXCEPTION '067: a chave da D7 nasceu ligada';
    END IF;
    IF (SELECT empty_organizations_limit FROM public.organization_creation_settings) <> 3 THEN
        RAISE EXCEPTION '067: limite de vazias fora do decidido (3)';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.license_rollout', 'INSERT, UPDATE, DELETE')
       OR has_table_privilege('nekkora_platform', 'public.organization_creation_settings', 'INSERT, UPDATE, DELETE')
       OR has_table_privilege('nekkora_platform', 'public.organization_creation_requests', 'INSERT, UPDATE, DELETE')
       OR has_table_privilege('nekkora_platform', 'public.organization_responsibility', 'SELECT, INSERT, UPDATE, DELETE')
       OR has_table_privilege('nekkora_platform', 'public.organization_deactivations', 'SELECT, INSERT, UPDATE, DELETE')
       OR has_table_privilege('nekkora_platform', 'public.organization_nekkora_holds', 'SELECT, INSERT, UPDATE, DELETE')
       OR has_table_privilege('nekkora_identity_owner', 'public.organization_nekkora_holds', 'INSERT, UPDATE, DELETE')
       OR has_table_privilege('nekkora_identity_owner', 'public.license_rollout', 'SELECT, INSERT, UPDATE, DELETE') THEN
        RAISE EXCEPTION '067: privilegio de tabela alem do previsto';
    END IF;
    FOR p IN SELECT pr.oid, pr.proname, pr.prosecdef, pr.proconfig, pg_get_userbyid(pr.proowner) AS owner FROM pg_proc pr
              WHERE pr.pronamespace = 'public'::regnamespace
                AND pr.proname IN ('identity_org_profile', 'identity_empty_organizations', 'identity_org_limit_reason', 'identity_provision_organization',
                                   'identity_org_creation_state', 'identity_request_organization', 'identity_organization_requests',
                                   'identity_decide_organization_request', 'identity_deactivate_organization', 'identity_reactivate_organization',
                                   'identity_responsible_organizations', 'identity_create_organization_impl', 'license_born_licensed',
                                   'license_organization_busy') LOOP
        IF p.owner <> (CASE WHEN p.proname LIKE 'license_%' THEN 'nekkora_licensing_owner' ELSE 'nekkora_identity_owner' END)
           OR NOT coalesce(p.proconfig @> ARRAY['search_path=pg_catalog, pg_temp'], false) OR has_function_privilege('public', p.oid, 'EXECUTE') THEN
            RAISE EXCEPTION '067: funcao % com dono, search_path ou EXECUTE publico fora do previsto', p.proname;
        END IF;
        IF p.proname IN ('identity_org_profile', 'identity_empty_organizations', 'identity_org_limit_reason', 'identity_provision_organization',
                         'identity_create_organization_impl')
           AND (p.prosecdef OR has_function_privilege('nekkora_platform', p.oid, 'EXECUTE')) THEN
            RAISE EXCEPTION '067: funcao interna % exposta', p.proname;
        END IF;
    END LOOP;
    IF has_function_privilege('nekkora_platform', 'public.license_born_licensed(uuid)', 'EXECUTE')
       OR has_function_privilege('nekkora_platform', 'public.license_organization_busy(uuid)', 'EXECUTE') THEN
        RAISE EXCEPTION '067: funcao do dono das licencas exposta a Platform';
    END IF;
    IF EXISTS (SELECT 1 FROM public.organization_license_acts WHERE act = 8)
       OR EXISTS (SELECT 1 FROM public.organization_authorship a WHERE NOT EXISTS (SELECT 1 FROM public.organization_responsibility r
                                                                                    WHERE r.organization_id = a.organization_id)) THEN
        RAISE EXCEPTION '067: estado inicial inesperado';
    END IF;
END;
$$;

COMMIT;
