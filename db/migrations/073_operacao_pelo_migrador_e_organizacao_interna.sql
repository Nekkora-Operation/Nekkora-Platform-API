-- Nekkora Platform - procedimento administrativo da OPERACAO DE LICENCAS pelo MIGRADOR, sem elevacao (NEKKORA-LICENCIAMENTO-002,
-- decisoes da titular de 22/11/2026, itens 2, 3 e 5). Procedimento: licenciamento-002/operador/PROCEDIMENTO-OPERADOR.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 072. Numero 073 conferido em 22/11 contra a dev (e9b2864; a maior era 072) e os
-- PRs abertos (API #120 nao tem migration).
--
-- *** SEM ELEVACAO. Roda como o migrador comum (migrador_nekkora, dono do banco e das tabelas tocadas), sem SUPERUSER e sem
-- *** BYPASSRLS: so cria tabela, politicas e gatilhos em tabelas do PROPRIO migrador. A pre-condicao recusa rodar elevada.
--
-- POR QUE EXISTE: license_operators, license_rollout e organization_nekkora_holds (063/067) tem RLS FORCADA e nenhuma politica
-- para o migrador: hoje so o administrador do cluster (superusuario) as escreve. A titular escolheu (22/11, opcao B) dar ao
-- migrador SO o acesso necessario, por politica propria, em vez de usar o superusuario no procedimento.
--
-- O QUE FAZ
--   1. Designacao FORMAL da organizacao interna da Nekkora (nekkora_internal_organization_designations): quem designou, chamado,
--      motivo e quando; retirada com os mesmos campos. Uma designacao vigente por vez. Nunca pelo nome da organizacao nem por
--      opcao do usuario: so o migrador escreve (procedimento operador.sh), e a aplicacao NAO le nem escreve esta tabela.
--   2. license_operators: historico de cadastros. A chave passa a ser o cadastro (registration_id); no maximo UM cadastro vigente
--      por conta. Recadastrar uma conta revogada = NOVO cadastro explicito (chamado e justificativa obrigatorios); a revogacao
--      anterior fica preservada e nada e restaurado. Revogar exige quem, chamado e motivo, uma vez so. Um cadastro novo so vale
--      na organizacao DESIGNADA vigente (coerencia com NEKKORA_LICENCAS_ORGANIZACAO_OPERADORA: ver o procedimento).
--   3. organization_nekkora_holds ganha lift_reason: levantar exige quem e motivo; so o levantamento, uma vez.
--   4. Politicas so para o migrador (TO CURRENT_USER na aplicacao desta migration):
--        escrita (SELECT, INSERT, UPDATE): license_operators, a designacao, license_rollout (SELECT, UPDATE),
--                                          organization_nekkora_holds;
--        leitura RESTRITA a organizacao em conferencia (configuracao de sessao nekkora.operador_org), para as conferencias do
--        procedimento (conta interna ativa, segundo fator confirmado, organizacao sem uso): organizations, users,
--        organization_memberships, client_memberships, identity_invitations, automations, workers, organization_license_acts,
--        organization_responsibility, user_mfa_factors, identity_users, identity_mfa_factors.
--      Gatilhos limitam o que o migrador consegue mudar: so a retirada/revogacao/levantamento, uma vez; nada e apagado.
--
-- O QUE NAO FAZ (e por que; ver licenciamento-002/api-073/docs/licenciamento-002/073-operacao-pelo-migrador.md):
--   - nao tira a organizacao interna do limite de vazias: a contagem e da funcao identity_empty_organizations, do dono
--     nekkora_identity_owner (067); muda-la exige a janela de SUPERUSER. Proposta separada (074, com elevacao) no documento.
--   - nao da efeito ao bloqueio da Nekkora nos fluxos: exige que a Platform e o canal das maquinas leiam o bloqueio, uma
--     permissao NOVA aos servicos da aplicacao, que a decisao de 22/11 nao autoriza nesta migration. Proposta no documento.
--   - nenhuma permissao nova a nekkora_platform, nekkora_portal, nekkora_provisioning, nekkora_ingestion ou aos donos.
--   - nenhum BYPASSRLS, nenhum papel novo, nenhuma funcao SECURITY DEFINER.
--
-- DADOS EXISTENTES: as linhas de license_operators (producao: nenhuma) ganham registration_id; os campos novos ficam nulos nelas.
--
-- REVERSAO (manual, sem elevacao, so enquanto nao houver designacao, recadastro nem revogacao gravados por ela):
--   BEGIN;
--   DROP TABLE public.nekkora_internal_organization_designations;
--   -- remover as politicas migrator_* e operator_check das tabelas listadas e os gatilhos trg_073_*
--   ALTER TABLE public.license_operators DROP CONSTRAINT license_operators_pkey;
--   DROP INDEX public.ux_license_operators_active;
--   ALTER TABLE public.license_operators ADD PRIMARY KEY (operator_user_id),
--         DROP COLUMN registration_id, DROP COLUMN registration_ticket, DROP COLUMN registration_justification,
--         DROP COLUMN revoked_by, DROP COLUMN revocation_ticket, DROP COLUMN revocation_reason;
--   ALTER TABLE public.organization_nekkora_holds DROP COLUMN lift_reason;
--   DROP FUNCTION public.license_operator_on_insert(), public.license_operator_on_update(),
--                 public.internal_org_designation_on_insert(), public.internal_org_designation_on_update(),
--                 public.nekkora_hold_on_update();
--   COMMIT;

BEGIN;

-- === 1. Pre-condicao: 072 aplicada, migrador comum, dono das tabelas ===
DO $$
DECLARE
    t TEXT;
BEGIN
    IF to_regprocedure('public.identity_empty_organizations(uuid)') IS NULL OR to_regclass('public.license_operators') IS NULL
       OR to_regclass('public.organization_nekkora_holds') IS NULL OR to_regclass('public.license_rollout') IS NULL THEN
        RAISE EXCEPTION '073 exige a 063 e a 067 aplicadas';
    END IF;
    IF to_regclass('public.nekkora_internal_organization_designations') IS NOT NULL THEN
        RAISE EXCEPTION '073: nekkora_internal_organization_designations ja existe';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND (rolsuper OR rolbypassrls OR rolcreaterole)) THEN
        RAISE EXCEPTION '073 roda SEM elevacao: o papel % esta com SUPERUSER, BYPASSRLS ou CREATEROLE', current_user;
    END IF;
    FOREACH t IN ARRAY ARRAY['license_operators', 'license_rollout', 'organization_nekkora_holds', 'organizations', 'users',
                             'organization_memberships', 'client_memberships', 'identity_invitations', 'automations', 'workers',
                             'organization_license_acts', 'organization_responsibility', 'user_mfa_factors', 'identity_users',
                             'identity_mfa_factors'] LOOP
        IF (SELECT pg_get_userbyid(c.relowner) FROM pg_class c WHERE c.oid = ('public.' || t)::regclass) <> current_user THEN
            RAISE EXCEPTION '073: a tabela % nao e do papel % (a politica exigiria elevacao)', t, current_user;
        END IF;
    END LOOP;
END;
$$;

-- === 2. Designacao formal da organizacao interna ===
CREATE TABLE public.nekkora_internal_organization_designations (
    designation_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    designated_by       TEXT NOT NULL CHECK (length(btrim(designated_by)) BETWEEN 3 AND 200),
    designation_ticket  TEXT NOT NULL CHECK (length(btrim(designation_ticket)) BETWEEN 3 AND 200),
    designation_reason  TEXT NOT NULL CHECK (length(btrim(designation_reason)) BETWEEN 10 AND 1000),
    designated_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    withdrawn_by        TEXT CHECK (withdrawn_by IS NULL OR length(btrim(withdrawn_by)) BETWEEN 3 AND 200),
    withdrawal_ticket   TEXT CHECK (withdrawal_ticket IS NULL OR length(btrim(withdrawal_ticket)) BETWEEN 3 AND 200),
    withdrawal_reason   TEXT CHECK (withdrawal_reason IS NULL OR length(btrim(withdrawal_reason)) BETWEEN 10 AND 1000),
    withdrawn_at        TIMESTAMPTZ,
    CHECK ((withdrawn_at IS NULL) = (withdrawn_by IS NULL) AND (withdrawn_at IS NULL) = (withdrawal_ticket IS NULL)
           AND (withdrawn_at IS NULL) = (withdrawal_reason IS NULL)),
    CHECK (withdrawn_at IS NULL OR withdrawn_at >= designated_at)
);
-- uma designacao vigente por vez, no ambiente inteiro
CREATE UNIQUE INDEX ux_nekkora_internal_organization_current ON public.nekkora_internal_organization_designations ((true))
    WHERE withdrawn_at IS NULL;
COMMENT ON TABLE public.nekkora_internal_organization_designations IS
    'Organizacao interna da Nekkora (073): designacao formal e auditada (quem, chamado, motivo, quando; retirada idem), feita so '
    'pelo procedimento administrativo (migrador). Uma vigente por vez. A aplicacao nao le nem escreve. Nunca e inferida do nome.';

CREATE FUNCTION public.internal_org_designation_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.designated_at := clock_timestamp();
    IF NEW.withdrawn_at IS NOT NULL OR NEW.withdrawn_by IS NOT NULL THEN
        RAISE EXCEPTION 'designacao recusada: nasce vigente' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.organizations o WHERE o.organization_id = NEW.organization_id AND o.status = 1) THEN
        RAISE EXCEPTION 'designacao recusada: a organizacao nao existe ou nao esta ativa' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM public.organization_nekkora_holds h WHERE h.organization_id = NEW.organization_id AND h.lifted_at IS NULL) THEN
        RAISE EXCEPTION 'designacao recusada: a organizacao esta bloqueada pela Nekkora' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.internal_org_designation_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    -- so a retirada, uma vez; o resto e imutavel
    IF OLD.withdrawn_at IS NOT NULL OR NEW.withdrawn_at IS NULL
       OR (NEW.designation_id, NEW.organization_id, NEW.designated_by, NEW.designation_ticket, NEW.designation_reason, NEW.designated_at)
          IS DISTINCT FROM (OLD.designation_id, OLD.organization_id, OLD.designated_by, OLD.designation_ticket, OLD.designation_reason, OLD.designated_at) THEN
        RAISE EXCEPTION 'designacao: so a retirada, uma vez' USING ERRCODE = '23514';
    END IF;
    NEW.withdrawn_at := clock_timestamp();
    -- operadores cadastrados nela tem de ser revogados antes (a Platform deixaria de aceita-los de qualquer forma)
    IF EXISTS (SELECT 1 FROM public.license_operators l WHERE l.organization_id = OLD.organization_id AND l.revoked_at IS NULL) THEN
        RAISE EXCEPTION 'retirada recusada: ha operador vigente cadastrado na organizacao interna (revogue antes)' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_073_internal_org_designation_insert BEFORE INSERT ON public.nekkora_internal_organization_designations
    FOR EACH ROW EXECUTE FUNCTION public.internal_org_designation_on_insert();
CREATE TRIGGER trg_073_internal_org_designation_update BEFORE UPDATE ON public.nekkora_internal_organization_designations
    FOR EACH ROW EXECUTE FUNCTION public.internal_org_designation_on_update();
CREATE TRIGGER trg_073_internal_org_designation_no_delete BEFORE DELETE ON public.nekkora_internal_organization_designations
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_073_internal_org_designation_no_truncate BEFORE TRUNCATE ON public.nekkora_internal_organization_designations
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();
ALTER TABLE public.nekkora_internal_organization_designations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nekkora_internal_organization_designations FORCE ROW LEVEL SECURITY;
REVOKE ALL ON public.nekkora_internal_organization_designations FROM PUBLIC;

-- === 3. Historico de cadastros de operadores (recadastro explicito; revogacao preservada) ===
ALTER TABLE public.license_operators
    ADD COLUMN registration_id            UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN registration_ticket        TEXT CHECK (registration_ticket IS NULL OR length(btrim(registration_ticket)) BETWEEN 3 AND 200),
    ADD COLUMN registration_justification TEXT CHECK (registration_justification IS NULL
                                                      OR length(btrim(registration_justification)) BETWEEN 10 AND 1000),
    ADD COLUMN revoked_by                 TEXT CHECK (revoked_by IS NULL OR length(btrim(revoked_by)) BETWEEN 3 AND 200),
    ADD COLUMN revocation_ticket          TEXT CHECK (revocation_ticket IS NULL OR length(btrim(revocation_ticket)) BETWEEN 3 AND 200),
    ADD COLUMN revocation_reason          TEXT CHECK (revocation_reason IS NULL OR length(btrim(revocation_reason)) BETWEEN 10 AND 1000);
ALTER TABLE public.license_operators DROP CONSTRAINT license_operators_pkey;
ALTER TABLE public.license_operators ADD PRIMARY KEY (registration_id);
-- as funcoes do operador (063/068) buscam por conta com revoked_at nulo: continua havendo no maximo uma linha
CREATE UNIQUE INDEX ux_license_operators_active ON public.license_operators (operator_user_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_license_operators_user ON public.license_operators (operator_user_id, registered_at);
COMMENT ON TABLE public.license_operators IS
    'Operadores de licenca (063; historico desde a 073): cada linha e um CADASTRO de uma conta da organizacao interna designada. '
    'No maximo um vigente por conta. Recadastro = linha nova, com chamado e justificativa; a revogacao anterior fica. So o '
    'migrador escreve. registered_by/revoked_by: quem autorizou (texto do procedimento, nunca segredo).';

CREATE FUNCTION public.license_operator_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.registered_at := clock_timestamp();
    IF NEW.revoked_at IS NOT NULL OR NEW.revoked_by IS NOT NULL OR NEW.revocation_ticket IS NOT NULL OR NEW.revocation_reason IS NOT NULL THEN
        RAISE EXCEPTION 'cadastro recusado: nasce vigente' USING ERRCODE = '23514';
    END IF;
    IF NEW.registration_ticket IS NULL OR NEW.registration_justification IS NULL THEN
        RAISE EXCEPTION 'cadastro recusado: chamado e justificativa sao obrigatorios' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.nekkora_internal_organization_designations d
                    WHERE d.organization_id = NEW.organization_id AND d.withdrawn_at IS NULL) THEN
        RAISE EXCEPTION 'cadastro recusado: a conta nao e da organizacao interna designada' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.license_operator_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    -- so a revogacao, uma vez, com quem, chamado e motivo; nada volta a valer (recadastro e linha nova)
    IF OLD.revoked_at IS NOT NULL OR NEW.revoked_at IS NULL
       OR NEW.revoked_by IS NULL OR NEW.revocation_ticket IS NULL OR NEW.revocation_reason IS NULL
       OR (NEW.registration_id, NEW.operator_user_id, NEW.organization_id, NEW.registered_at, NEW.registered_by,
           NEW.registration_ticket, NEW.registration_justification)
          IS DISTINCT FROM (OLD.registration_id, OLD.operator_user_id, OLD.organization_id, OLD.registered_at, OLD.registered_by,
                            OLD.registration_ticket, OLD.registration_justification) THEN
        RAISE EXCEPTION 'cadastro de operador: so a revogacao, uma vez, com quem, chamado e motivo' USING ERRCODE = '23514';
    END IF;
    NEW.revoked_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_073_license_operator_insert BEFORE INSERT ON public.license_operators
    FOR EACH ROW EXECUTE FUNCTION public.license_operator_on_insert();
CREATE TRIGGER trg_073_license_operator_update BEFORE UPDATE ON public.license_operators
    FOR EACH ROW EXECUTE FUNCTION public.license_operator_on_update();
CREATE TRIGGER trg_073_license_operator_no_delete BEFORE DELETE ON public.license_operators
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_073_license_operator_no_truncate BEFORE TRUNCATE ON public.license_operators
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 4. Bloqueio da Nekkora: o levantamento registra o motivo; so ele, uma vez ===
ALTER TABLE public.organization_nekkora_holds
    ADD COLUMN lift_reason TEXT CHECK (lift_reason IS NULL OR length(btrim(lift_reason)) BETWEEN 10 AND 1000);
CREATE FUNCTION public.nekkora_hold_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.lifted_at IS NOT NULL OR NEW.lifted_at IS NULL OR NEW.lift_reason IS NULL
       OR (NEW.hold_id, NEW.organization_id, NEW.reason, NEW.registered_by, NEW.registered_at)
          IS DISTINCT FROM (OLD.hold_id, OLD.organization_id, OLD.reason, OLD.registered_by, OLD.registered_at) THEN
        RAISE EXCEPTION 'bloqueio da Nekkora: so o levantamento, uma vez, com quem e motivo' USING ERRCODE = '23514';
    END IF;
    NEW.lifted_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_073_nekkora_hold_update BEFORE UPDATE ON public.organization_nekkora_holds
    FOR EACH ROW EXECUTE FUNCTION public.nekkora_hold_on_update();

-- === 5. Politicas do migrador ===
-- escrita, so nas tabelas do procedimento (a RLS forcada vale tambem para o dono; sem politica ele nao ve nem grava)
CREATE POLICY migrator_procedure ON public.license_operators AS PERMISSIVE FOR SELECT TO CURRENT_USER USING (true);
CREATE POLICY migrator_procedure_insert ON public.license_operators AS PERMISSIVE FOR INSERT TO CURRENT_USER WITH CHECK (true);
CREATE POLICY migrator_procedure_update ON public.license_operators AS PERMISSIVE FOR UPDATE TO CURRENT_USER USING (true) WITH CHECK (true);
CREATE POLICY migrator_procedure ON public.nekkora_internal_organization_designations AS PERMISSIVE FOR SELECT TO CURRENT_USER USING (true);
CREATE POLICY migrator_procedure_insert ON public.nekkora_internal_organization_designations AS PERMISSIVE FOR INSERT TO CURRENT_USER WITH CHECK (true);
CREATE POLICY migrator_procedure_update ON public.nekkora_internal_organization_designations AS PERMISSIVE FOR UPDATE TO CURRENT_USER USING (true) WITH CHECK (true);
CREATE POLICY migrator_procedure ON public.license_rollout AS PERMISSIVE FOR SELECT TO CURRENT_USER USING (true);
CREATE POLICY migrator_procedure_update ON public.license_rollout AS PERMISSIVE FOR UPDATE TO CURRENT_USER USING (true) WITH CHECK (true);
CREATE POLICY migrator_procedure ON public.organization_nekkora_holds AS PERMISSIVE FOR SELECT TO CURRENT_USER USING (true);
CREATE POLICY migrator_procedure_insert ON public.organization_nekkora_holds AS PERMISSIVE FOR INSERT TO CURRENT_USER WITH CHECK (true);
CREATE POLICY migrator_procedure_update ON public.organization_nekkora_holds AS PERMISSIVE FOR UPDATE TO CURRENT_USER USING (true) WITH CHECK (true);

-- leitura das conferencias, restrita a UMA organizacao, a que o procedimento declara em nekkora.operador_org (sem ela: nada)
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['organizations', 'users', 'organization_memberships', 'client_memberships', 'identity_invitations',
                             'automations', 'workers', 'organization_license_acts', 'organization_responsibility', 'user_mfa_factors'] LOOP
        EXECUTE format('CREATE POLICY operator_check ON public.%I AS PERMISSIVE FOR SELECT TO CURRENT_USER '
                       'USING (organization_id = nullif(current_setting(''nekkora.operador_org'', true), '''')::uuid)', t);
    END LOOP;
END;
$$;
CREATE POLICY operator_check ON public.identity_users AS PERMISSIVE FOR SELECT TO CURRENT_USER
    USING (user_id IN (SELECT u.user_id FROM public.users u));                       -- users ja restrita a organizacao
CREATE POLICY operator_check ON public.identity_mfa_factors AS PERMISSIVE FOR SELECT TO CURRENT_USER
    USING (identity_id IN (SELECT iu.identity_id FROM public.identity_users iu));    -- idem

-- === 6. Conferencia final ===
DO $$
DECLARE
    r TEXT;
    t TEXT;
BEGIN
    -- nenhum servico da aplicacao alcanca as tabelas do procedimento (nem ganhou nada aqui)
    FOREACH r IN ARRAY ARRAY['nekkora_platform', 'nekkora_portal', 'nekkora_provisioning', 'nekkora_ingestion'] LOOP
        CONTINUE WHEN to_regrole(r) IS NULL;
        IF has_table_privilege(r, 'public.nekkora_internal_organization_designations', 'SELECT, INSERT, UPDATE, DELETE')
           OR has_table_privilege(r, 'public.license_operators', 'SELECT, INSERT, UPDATE, DELETE')
           OR has_table_privilege(r, 'public.organization_nekkora_holds', 'SELECT, INSERT, UPDATE, DELETE')
           OR has_table_privilege(r, 'public.license_rollout', 'INSERT, UPDATE, DELETE') THEN
            RAISE EXCEPTION '073: o papel % alcanca uma tabela do procedimento do operador', r;
        END IF;
    END LOOP;
    -- as politicas novas sao so do migrador
    SELECT string_agg(p.tablename || '.' || p.policyname, ', ') INTO t FROM pg_policies p
     WHERE p.policyname IN ('migrator_procedure', 'migrator_procedure_insert', 'migrator_procedure_update', 'operator_check')
       AND p.roles <> ARRAY[current_user::name];
    IF t IS NOT NULL THEN RAISE EXCEPTION '073: politica fora do migrador: %', t; END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb)) THEN
        RAISE EXCEPTION '073: o migrador terminou com atributo elevado';
    END IF;
    IF EXISTS (SELECT 1 FROM public.nekkora_internal_organization_designations) THEN
        RAISE EXCEPTION '073: nasce sem designacao';
    END IF;
END;
$$;

COMMIT;
