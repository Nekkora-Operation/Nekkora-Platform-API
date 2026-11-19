-- Nekkora Platform - vinculo explicito da licenca a uma automacao, pelo administrador da organizacao
-- (PLATFORM-LICENCIAMENTO-001, D1). Contrato: docs/platform-licenciamento-001/t03-vinculo-pelo-administrador.md e
-- api/openapi/nekkora-api-v1.yaml (POST /api/v1/o/{org}/licenses/{licenseId}/assignment).
--
-- CANDIDATA ate a aprovacao do PR. Depende da 061. Numero 062 conferido em 19/11 contra a dev (86591bb; a maior era 060) e os
-- PRs abertos (a 061 e a do PR anterior desta missao). Numeracao contigua: se a ordem de integracao mudar, esta e renumerada
-- sem mudar o conteudo.
--
-- O QUE FAZ
--   1. A Platform passa a poder INSERIR em organization_license_assignments e em organization_license_acts, e SO no contexto
--      da organizacao da sessao (politica de insercao). Continua sem UPDATE e sem DELETE: ela abre vinculos, nunca os encerra.
--   2. Na trilha, a Platform so grava o ato 4 (VINCULADA pelo administrador). Os atos do operador (1, 2, 3, 5, 6, 7) continuam
--      fora do alcance dela: sao das funcoes da migration seguinte.
--   3. O gatilho do vinculo passa a conferir, alem da licenca vigente (061):
--        - quem vincula (assigned_by_membership_id) tem org.manage NESTE instante (o banco reconfere; nao confia na aplicacao);
--        - a automacao nao esta arquivada (uma licenca nao e gasta numa automacao que nao executa);
--        - o vinculo feito pela Platform sempre identifica quem vinculou.
--
-- O QUE NAO FAZ: nao cria licenca nem vinculo; nao liga a exigencia (nenhum caminho de execucao le estas tabelas); nao da a
-- Platform nenhum meio de conceder, renovar, encerrar ou transferir licenca, nem de desfazer um vinculo.
--
-- DADOS EXISTENTES: nenhuma linha e alterada.
--
-- REVERSAO (manual):
--   BEGIN;
--   DROP POLICY tenant_admin_assignment ON public.organization_license_assignments;
--   DROP POLICY tenant_admin_act ON public.organization_license_acts;
--   REVOKE INSERT ON public.organization_license_assignments, public.organization_license_acts FROM nekkora_platform;
--   -- e recriar organization_license_assignment_on_insert() como na 061
--   COMMIT;
--
-- SEM ELEVACAO: privilegios e politicas em tabelas da 061 e um gatilho SECURITY INVOKER substituido. Nenhum papel novo,
-- nenhuma funcao SECURITY DEFINER.

BEGIN;

-- === 1. Pre-condicao ===
DO $$
BEGIN
    IF to_regclass('public.organization_license_assignments') IS NULL OR to_regprocedure('public.access_org_permission(uuid, uuid, text)') IS NULL THEN
        RAISE EXCEPTION '062 exige a 061 (e a 023) aplicadas';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.organization_license_assignments', 'INSERT') THEN
        RAISE EXCEPTION '062: a Platform ja insere vinculos';
    END IF;
END;
$$;

-- === 2. O vinculo: licenca vigente (061) + quem vincula administra a organizacao + automacao nao arquivada ===
CREATE OR REPLACE FUNCTION public.organization_license_assignment_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_ended TIMESTAMPTZ;
    v_now TIMESTAMPTZ;
    v_status SMALLINT;
BEGIN
    -- trava a LICENCA pelo identificador (como a 033 trava o escopo): a Platform nao tem UPDATE na tabela de licencas, e uma
    -- trava de linha (FOR UPDATE/SHARE) exigiria esse privilegio. Os atos do operador que encerram ou transferem a licenca
    -- pegam a mesma trava, entao um vinculo nunca corre contra o encerramento.
    -- trava COMPARTILHADA do modelo da organizacao, sempre antes da trava da licenca: ligar a exigencia pega a mesma trava
    -- em modo exclusivo, entao a cobertura conferida na previa nao muda enquanto o ato de ligar esta em curso.
    PERFORM pg_catalog.pg_advisory_xact_lock_shared(pg_catalog.hashtextextended('organization_license_model:' || NEW.organization_id::text, 0));
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('organization_license:' || NEW.license_id::text, 0));
    SELECT l.ended_at INTO v_ended FROM public.organization_licenses l
     WHERE l.organization_id = NEW.organization_id AND l.license_id = NEW.license_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'licenca inexistente nesta organizacao' USING ERRCODE = '23503';
    END IF;
    IF NEW.assigned_by_membership_id IS NULL AND current_user = 'nekkora_platform' THEN
        RAISE EXCEPTION 'o vinculo feito pela Platform identifica quem vinculou' USING ERRCODE = '42501';
    END IF;
    IF NEW.assigned_by_membership_id IS NOT NULL
       AND NOT public.access_org_permission(NEW.organization_id, NEW.assigned_by_membership_id, 'org.manage') THEN
        RAISE EXCEPTION 'quem vincula a licenca precisa administrar a organizacao (org.manage)' USING ERRCODE = '42501';
    END IF;
    SELECT a.status INTO v_status FROM public.automations a
     WHERE a.organization_id = NEW.organization_id AND a.workspace_id = NEW.workspace_id AND a.automation_id = NEW.automation_id;
    IF v_status = 3 THEN
        RAISE EXCEPTION 'vinculo recusado: AUTOMATION_ARCHIVED' USING ERRCODE = '23514';
    END IF;
    v_now := clock_timestamp();
    IF v_ended IS NOT NULL OR NOT EXISTS (SELECT 1 FROM public.organization_license_periods p
                                           WHERE p.organization_id = NEW.organization_id AND p.license_id = NEW.license_id
                                             AND p.starts_at <= v_now AND p.ends_at > v_now) THEN
        RAISE EXCEPTION 'vinculo recusado: LICENSE_NOT_VALID' USING ERRCODE = '23514';
    END IF;
    NEW.assigned_at := v_now;
    NEW.released_at := NULL;
    NEW.release_reason := NULL;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.organization_license_assignment_on_insert() FROM PUBLIC;

-- === 3. Politicas de insercao (a de leitura e a da 061) ===
DO $$
DECLARE
    ctx_req CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.organization_id'', true), '''')::uuid';
BEGIN
    EXECUTE format('CREATE POLICY tenant_admin_assignment ON public.organization_license_assignments AS PERMISSIVE FOR INSERT TO nekkora_platform '
                   'WITH CHECK (%s AND assigned_by_membership_id IS NOT NULL)', ctx_req);
    EXECUTE format('CREATE POLICY tenant_admin_act ON public.organization_license_acts AS PERMISSIVE FOR INSERT TO nekkora_platform '
                   'WITH CHECK (%s AND act = 4)', ctx_req);
END;
$$;

-- === 4. Privilegios ===
GRANT INSERT ON public.organization_license_assignments, public.organization_license_acts TO nekkora_platform;

-- === 5. Invariantes ===
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['organization_licenses', 'organization_license_periods', 'organization_license_assignments',
                             'organization_license_acts', 'organization_license_enforcement'] LOOP
        IF has_table_privilege('nekkora_platform', 'public.' || t, 'UPDATE') OR has_table_privilege('nekkora_platform', 'public.' || t, 'DELETE')
           OR has_table_privilege('nekkora_platform', 'public.' || t, 'TRUNCATE') THEN
            RAISE EXCEPTION '062: a Platform nao altera nem apaga %', t;
        END IF;
    END LOOP;
    IF has_table_privilege('nekkora_platform', 'public.organization_licenses', 'INSERT')
       OR has_table_privilege('nekkora_platform', 'public.organization_license_periods', 'INSERT')
       OR has_table_privilege('nekkora_platform', 'public.organization_license_enforcement', 'INSERT') THEN
        RAISE EXCEPTION '062: a Platform nao concede, nao renova e nao liga a exigencia';
    END IF;
    IF (SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = 'organization_license_assignments') <> 2
       OR (SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = 'organization_license_acts') <> 2 THEN
        RAISE EXCEPTION '062: politicas fora do previsto';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_proc p WHERE p.pronamespace = 'public'::regnamespace AND p.proname LIKE 'organization\_license\_%' AND p.prosecdef) THEN
        RAISE EXCEPTION '062: funcao SECURITY DEFINER inesperada';
    END IF;
END;
$$;

COMMIT;
