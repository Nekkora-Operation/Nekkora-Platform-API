-- Nekkora Platform - licencas de execucao e monitoracao por organizacao: MODELO E LEITURA (PLATFORM-LICENCIAMENTO-001, E1).
-- Contrato: docs/platform-licenciamento-001/t00-inventario-e-contrato.md, t01-decisoes-e-esclarecimentos.md e
-- t02-modelo-e-leituras.md; api/openapi/nekkora-api-v1.yaml (GET /api/v1/o/{org}/licenses/summary e /licenses).
--
-- CANDIDATA ate a aprovacao do PR. Numero 061 conferido em 19/11 contra a dev (86591bb; a maior era 060) e os PRs abertos
-- (nenhum traz migration). As ferramentas do laboratorio e da homologacao exigem numeracao contigua: se outra 061 entrar
-- antes, esta e renumerada sem mudar o conteudo.
--
-- O QUE FAZ (tudo novo; nenhuma tabela existente e alterada)
--   1. organization_licenses: UMA LINHA POR LICENCA da organizacao (nao um contador). Numero sequencial por organizacao.
--      Pode ser ENCERRADA (ended_at), uma vez; nada mais muda.
--   2. organization_license_periods: os PERIODOS de cada licenca. O primeiro e a concessao (kind 1); cada renovacao (kind 2)
--      acrescenta um periodo que comeca no fim do anterior ou depois. Os periodos anteriores ficam (D4). So acrescimo.
--   3. organization_license_assignments: o VINCULO licenca -> automacao. No maximo um vinculo aberto por licenca e no maximo
--      uma licenca com vinculo aberto por automacao (indices unicos parciais): duas ativacoes simultaneas nunca consomem a
--      mesma licenca e a mesma automacao nunca consome duas. O vinculo so se abre numa licenca VIGENTE e nao encerrada. Ele
--      NAO se desfaz quando a automacao e suspensa, renomeada ou arquivada, nem quando o periodo vence: renovar mantem o
--      vinculo. So um ato (transferencia ou encerramento da licenca) o encerra (released_at), uma vez.
--   4. organization_license_acts: a trilha dos atos (so acrescimo): conceder, renovar, encerrar, vincular, transferir, ligar
--      e desligar a exigencia. Guarda quem, o que, justificativa e instante.
--   5. organization_license_enforcement: o MODELO DE HABILITACAO de cada organizacao - 1=ANTERIOR (ativacao e limites de
--      hoje), 2=POR_LICENCA. Organizacao SEM linha esta no modelo ANTERIOR.
--
-- VIGENTE (a mesma definicao em toda leitura): a licenca nao esta encerrada e tem um periodo com starts_at <= agora < ends_at.
--
-- O QUE NAO FAZ
--   * NAO CONFERE NADA: nenhum caminho de execucao ou de monitoracao le estas tabelas. A exigencia de licenca nao existe
--     ainda; a tabela do modelo nasce vazia (todas as organizacoes no modelo ANTERIOR).
--   * NAO CRIA LICENCA, periodo, vinculo nem ato: as cinco tabelas nascem vazias.
--   * NAO RETIRA nem altera a ativacao (022), os limites (010) nem as autorizacoes (025/033/042).
--   * NAO DA ESCRITA A NINGUEM: a Platform recebe so SELECT. O ato do administrador (vincular, D1) e os atos do operador da
--     Nekkora (conceder, renovar, encerrar, transferir, ligar a exigencia) entram nas etapas seguintes; os do operador, por
--     funcoes com dono privilegiado, numa migration COM elevacao (t01, secao 5).
--   * NAO GUARDA PRECO: a referencia comercial fica na documentacao.
--
-- DADOS EXISTENTES: nenhuma linha existente e lida ou alterada.
--
-- REVERSAO (manual; so enquanto as cinco tabelas estiverem vazias - depois do primeiro ato a trilha e so de acrescimo e a
-- reversao passa a ser decisao humana):
--   BEGIN;
--   DROP TABLE public.organization_license_enforcement, public.organization_license_acts, public.organization_license_assignments,
--              public.organization_license_periods, public.organization_licenses;
--   DROP FUNCTION public.organization_license_on_insert(), public.organization_license_on_update(),
--                 public.organization_license_period_on_insert(), public.organization_license_assignment_on_insert(),
--                 public.organization_license_assignment_on_update(), public.organization_license_enforcement_on_write();
--   COMMIT;
--
-- SEM ELEVACAO: tabelas novas e gatilhos SECURITY INVOKER. Nenhum papel novo, nenhuma funcao SECURITY DEFINER.

BEGIN;

-- === 1. Pre-condicao ===
DO $$
BEGIN
    IF to_regclass('public.remote_attempt_process_reports') IS NULL THEN
        RAISE EXCEPTION '061 exige a 060 aplicada';
    END IF;
    IF to_regclass('public.organization_licenses') IS NOT NULL THEN
        RAISE EXCEPTION '061: organization_licenses ja existe';
    END IF;
END;
$$;

-- === 2. Licenca ===
CREATE TABLE public.organization_licenses (
    license_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    license_number   INTEGER NOT NULL CHECK (license_number > 0),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    ended_at         TIMESTAMPTZ,
    UNIQUE (organization_id, license_id),
    UNIQUE (organization_id, license_number),
    CHECK (ended_at IS NULL OR ended_at >= created_at)
);
COMMENT ON TABLE public.organization_licenses IS
    'Licenca de execucao e monitoracao de UMA automacao, da organizacao (061). Uma linha por licenca. A validade esta nos periodos '
    '(organization_license_periods); a automacao atendida, no vinculo (organization_license_assignments).';
COMMENT ON COLUMN public.organization_licenses.license_number IS 'sequencial por organizacao, atribuido na insercao; so para apresentacao';
COMMENT ON COLUMN public.organization_licenses.ended_at IS 'encerrada por ato do operador; uma vez, nao volta';

-- O numero e o instante vem do servidor, sob trava da organizacao (a mesma trava das admissoes, 010/022).
CREATE FUNCTION public.organization_license_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM 1 FROM public.organizations o WHERE o.organization_id = NEW.organization_id FOR UPDATE;
    NEW.created_at := clock_timestamp();
    NEW.ended_at := NULL;
    SELECT coalesce(max(l.license_number), 0) + 1 INTO NEW.license_number
      FROM public.organization_licenses l WHERE l.organization_id = NEW.organization_id;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_organization_licenses_on_insert BEFORE INSERT ON public.organization_licenses
    FOR EACH ROW EXECUTE FUNCTION public.organization_license_on_insert();

CREATE FUNCTION public.organization_license_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.license_id <> OLD.license_id OR NEW.organization_id <> OLD.organization_id OR NEW.license_number <> OLD.license_number
       OR NEW.created_at <> OLD.created_at OR OLD.ended_at IS NOT NULL OR NEW.ended_at IS NULL THEN
        RAISE EXCEPTION 'a licenca so muda para ser encerrada, uma vez' USING ERRCODE = '42501';
    END IF;
    NEW.ended_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_organization_licenses_on_update BEFORE UPDATE ON public.organization_licenses
    FOR EACH ROW EXECUTE FUNCTION public.organization_license_on_update();
CREATE TRIGGER trg_organization_licenses_prevent_delete BEFORE DELETE ON public.organization_licenses
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_organization_licenses_prevent_truncate BEFORE TRUNCATE ON public.organization_licenses
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 3. Periodos (concessao e renovacoes) ===
CREATE TABLE public.organization_license_periods (
    period_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL,
    license_id       UUID NOT NULL,
    period_number    INTEGER NOT NULL CHECK (period_number > 0),
    kind             SMALLINT NOT NULL CHECK (kind IN (1, 2)),
    starts_at        TIMESTAMPTZ NOT NULL,
    ends_at          TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (license_id, period_number),
    UNIQUE (organization_id, period_id),
    FOREIGN KEY (organization_id, license_id) REFERENCES public.organization_licenses (organization_id, license_id) ON DELETE RESTRICT,
    CHECK (ends_at > starts_at),
    CHECK ((kind = 1) = (period_number = 1))
);
CREATE INDEX ix_organization_license_periods_validity ON public.organization_license_periods (organization_id, license_id, ends_at);
COMMENT ON TABLE public.organization_license_periods IS
    'Periodos de validade de cada licenca (061): kind 1=CONCESSAO (o primeiro), 2=RENOVACAO. So acrescimo: renovar acrescenta um '
    'periodo e os anteriores ficam. [starts_at, ends_at): o fim e exclusivo.';

-- Numerado em sequencia sob trava da licenca; nao se sobrepoe ao anterior; licenca encerrada nao renova.
CREATE FUNCTION public.organization_license_period_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_ended TIMESTAMPTZ;
    v_last RECORD;
BEGIN
    SELECT l.ended_at INTO v_ended FROM public.organization_licenses l
     WHERE l.organization_id = NEW.organization_id AND l.license_id = NEW.license_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'licenca inexistente nesta organizacao' USING ERRCODE = '23503';
    END IF;
    IF v_ended IS NOT NULL THEN
        RAISE EXCEPTION 'licenca encerrada nao recebe periodo' USING ERRCODE = '23514';
    END IF;
    SELECT p.period_number, p.ends_at INTO v_last FROM public.organization_license_periods p
     WHERE p.license_id = NEW.license_id ORDER BY p.period_number DESC LIMIT 1;
    NEW.created_at := clock_timestamp();
    NEW.period_number := coalesce(v_last.period_number, 0) + 1;
    NEW.kind := CASE WHEN NEW.period_number = 1 THEN 1 ELSE 2 END;
    IF v_last.ends_at IS NOT NULL AND NEW.starts_at < v_last.ends_at THEN
        RAISE EXCEPTION 'o periodo novo nao pode comecar antes do fim do anterior' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_organization_license_periods_on_insert BEFORE INSERT ON public.organization_license_periods
    FOR EACH ROW EXECUTE FUNCTION public.organization_license_period_on_insert();
CREATE TRIGGER trg_organization_license_periods_prevent_update BEFORE UPDATE ON public.organization_license_periods
    FOR EACH ROW EXECUTE FUNCTION public.prevent_update();
CREATE TRIGGER trg_organization_license_periods_prevent_delete BEFORE DELETE ON public.organization_license_periods
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_organization_license_periods_prevent_truncate BEFORE TRUNCATE ON public.organization_license_periods
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 4. Vinculo licenca -> automacao ===
CREATE TABLE public.organization_license_assignments (
    assignment_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id            UUID NOT NULL,
    license_id                 UUID NOT NULL,
    workspace_id               UUID NOT NULL,
    automation_id              UUID NOT NULL,
    assigned_at                TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    assigned_by_membership_id  UUID,
    released_at                TIMESTAMPTZ,
    release_reason             SMALLINT CHECK (release_reason IN (1, 2)),
    UNIQUE (organization_id, assignment_id),
    FOREIGN KEY (organization_id, license_id) REFERENCES public.organization_licenses (organization_id, license_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, workspace_id, automation_id)
        REFERENCES public.automations (organization_id, workspace_id, automation_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, assigned_by_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK ((released_at IS NULL) = (release_reason IS NULL)),
    CHECK (released_at IS NULL OR released_at >= assigned_at)
);
-- o coracao da regra: uma licenca atende UMA automacao por vez, e uma automacao consome UMA licenca
CREATE UNIQUE INDEX ux_organization_license_assignments_license ON public.organization_license_assignments (license_id)
    WHERE released_at IS NULL;
CREATE UNIQUE INDEX ux_organization_license_assignments_automation ON public.organization_license_assignments (organization_id, automation_id)
    WHERE released_at IS NULL;
CREATE INDEX ix_organization_license_assignments_history ON public.organization_license_assignments (organization_id, license_id, assigned_at);
COMMENT ON TABLE public.organization_license_assignments IS
    'Vinculo licenca -> automacao (061). Aberto (released_at nulo) enquanto a licenca atende a automacao: no maximo um por licenca e um '
    'por automacao. Nao se desfaz por suspensao, renomeacao ou arquivamento da automacao nem pelo vencimento do periodo. '
    'release_reason: 1=TRANSFERIDA para outra automacao, 2=LICENCA_ENCERRADA.';
COMMENT ON COLUMN public.organization_license_assignments.assigned_by_membership_id IS
    'o administrador da organizacao que vinculou (D1); nulo quando o vinculo nasce de uma transferencia feita pelo operador';

-- O vinculo so se abre numa licenca VIGENTE e nao encerrada, no instante do servidor, sob trava da licenca.
CREATE FUNCTION public.organization_license_assignment_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_ended TIMESTAMPTZ;
    v_now TIMESTAMPTZ;
BEGIN
    SELECT l.ended_at INTO v_ended FROM public.organization_licenses l
     WHERE l.organization_id = NEW.organization_id AND l.license_id = NEW.license_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'licenca inexistente nesta organizacao' USING ERRCODE = '23503';
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
CREATE TRIGGER trg_organization_license_assignments_on_insert BEFORE INSERT ON public.organization_license_assignments
    FOR EACH ROW EXECUTE FUNCTION public.organization_license_assignment_on_insert();

CREATE FUNCTION public.organization_license_assignment_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.assignment_id <> OLD.assignment_id OR NEW.organization_id <> OLD.organization_id OR NEW.license_id <> OLD.license_id
       OR NEW.workspace_id <> OLD.workspace_id OR NEW.automation_id <> OLD.automation_id OR NEW.assigned_at <> OLD.assigned_at
       OR NEW.assigned_by_membership_id IS DISTINCT FROM OLD.assigned_by_membership_id
       OR OLD.released_at IS NOT NULL OR NEW.released_at IS NULL OR NEW.release_reason IS NULL THEN
        RAISE EXCEPTION 'o vinculo so muda para ser encerrado, uma vez' USING ERRCODE = '42501';
    END IF;
    NEW.released_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_organization_license_assignments_on_update BEFORE UPDATE ON public.organization_license_assignments
    FOR EACH ROW EXECUTE FUNCTION public.organization_license_assignment_on_update();
CREATE TRIGGER trg_organization_license_assignments_prevent_delete BEFORE DELETE ON public.organization_license_assignments
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_organization_license_assignments_prevent_truncate BEFORE TRUNCATE ON public.organization_license_assignments
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 5. Trilha dos atos ===
CREATE TABLE public.organization_license_acts (
    act_id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id          UUID NOT NULL REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    act                      SMALLINT NOT NULL CHECK (act BETWEEN 1 AND 7),
    license_id               UUID,
    period_id                UUID,
    from_automation_id       UUID,
    to_automation_id         UUID,
    actor_membership_id      UUID,
    operator_user_id         UUID REFERENCES public.users (user_id) ON DELETE RESTRICT,
    requester_membership_id  UUID,
    justification            TEXT CHECK (justification IS NULL OR (length(btrim(justification)) BETWEEN 10 AND 1000)),
    detail                   JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(detail) = 'object' AND pg_column_size(detail) <= 16384),
    occurred_at              TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (organization_id, act_id),
    FOREIGN KEY (organization_id, license_id) REFERENCES public.organization_licenses (organization_id, license_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, period_id) REFERENCES public.organization_license_periods (organization_id, period_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, actor_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, requester_membership_id)
        REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    -- quem fez: o administrador da propria organizacao (so o ato 4) OU o operador da Nekkora (os demais), nunca os dois
    CHECK ((act = 4) = (actor_membership_id IS NOT NULL)),
    CHECK ((act = 4) = (operator_user_id IS NULL)),
    -- o operador sempre justifica
    CHECK (act = 4 OR justification IS NOT NULL),
    -- o que cada ato referencia
    CHECK (act NOT IN (1, 2) OR (license_id IS NOT NULL AND period_id IS NOT NULL)),
    CHECK (act <> 3 OR license_id IS NOT NULL),
    CHECK (act <> 4 OR (license_id IS NOT NULL AND to_automation_id IS NOT NULL AND from_automation_id IS NULL)),
    CHECK (act <> 5 OR (license_id IS NOT NULL AND from_automation_id IS NOT NULL AND to_automation_id IS NOT NULL
                        AND from_automation_id <> to_automation_id AND requester_membership_id IS NOT NULL)),
    CHECK (act NOT IN (6, 7) OR license_id IS NULL)
);
CREATE INDEX ix_organization_license_acts_time ON public.organization_license_acts (organization_id, occurred_at, act_id);
COMMENT ON TABLE public.organization_license_acts IS
    'Trilha dos atos de licenca (061), so acrescimo. act: 1=CONCEDIDA, 2=RENOVADA, 3=ENCERRADA, 4=VINCULADA a uma automacao pelo '
    'administrador da organizacao, 5=TRANSFERIDA para outra automacao, 6=EXIGENCIA_LIGADA, 7=EXIGENCIA_DESLIGADA. O ato 4 e do '
    'administrador (actor_membership_id); os demais, do operador da Nekkora (operator_user_id, conta de OUTRA organizacao), com '
    'justificativa. requester_membership_id: o administrador que pediu a transferencia.';
CREATE TRIGGER trg_organization_license_acts_prevent_update BEFORE UPDATE ON public.organization_license_acts
    FOR EACH ROW EXECUTE FUNCTION public.prevent_update();
CREATE TRIGGER trg_organization_license_acts_prevent_delete BEFORE DELETE ON public.organization_license_acts
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_organization_license_acts_prevent_truncate BEFORE TRUNCATE ON public.organization_license_acts
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 6. Modelo de habilitacao da organizacao ===
CREATE TABLE public.organization_license_enforcement (
    organization_id  UUID PRIMARY KEY REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    model            SMALLINT NOT NULL CHECK (model IN (1, 2)),
    since            TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    act_id           UUID NOT NULL,
    FOREIGN KEY (organization_id, act_id) REFERENCES public.organization_license_acts (organization_id, act_id) ON DELETE RESTRICT
);
COMMENT ON TABLE public.organization_license_enforcement IS
    'Modelo de habilitacao da organizacao (061): 1=ANTERIOR (ativacao e limites da 010/022), 2=POR_LICENCA (executar e monitorar '
    'exige licenca vigente vinculada a automacao). Organizacao SEM linha esta no modelo ANTERIOR. So muda por ato (6 liga, 7 desliga), '
    'que fica referenciado. Na 061 nada le esta tabela para decidir execucao.';

-- O modelo so muda acompanhado do ato correspondente, da mesma organizacao.
CREATE FUNCTION public.organization_license_enforcement_on_write() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_act SMALLINT;
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.organization_id <> OLD.organization_id OR NEW.act_id = OLD.act_id OR NEW.model = OLD.model) THEN
        RAISE EXCEPTION 'o modelo so muda por um ato novo que o inverta' USING ERRCODE = '42501';
    END IF;
    SELECT a.act INTO v_act FROM public.organization_license_acts a
     WHERE a.organization_id = NEW.organization_id AND a.act_id = NEW.act_id;
    IF v_act IS DISTINCT FROM (CASE NEW.model WHEN 2 THEN 6 ELSE 7 END)::smallint THEN
        RAISE EXCEPTION 'o ato informado nao e o que liga ou desliga a exigencia' USING ERRCODE = '23514';
    END IF;
    NEW.since := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_organization_license_enforcement_on_write BEFORE INSERT OR UPDATE ON public.organization_license_enforcement
    FOR EACH ROW EXECUTE FUNCTION public.organization_license_enforcement_on_write();
CREATE TRIGGER trg_organization_license_enforcement_prevent_delete BEFORE DELETE ON public.organization_license_enforcement
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_organization_license_enforcement_prevent_truncate BEFORE TRUNCATE ON public.organization_license_enforcement
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 7. RLS (padrao da 025/030/035/042/047): a Platform so ve as linhas da organizacao do contexto ===
DO $$
DECLARE
    ctx_req CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.organization_id'', true), '''')::uuid';
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['organization_licenses', 'organization_license_periods', 'organization_license_assignments',
                             'organization_license_acts', 'organization_license_enforcement'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_request_context ON public.%I AS PERMISSIVE FOR SELECT TO nekkora_platform USING (%s)', t, ctx_req);
    END LOOP;
END;
$$;

-- === 8. Privilegios: a Platform SO LE. Ninguem mais recebe nada ===
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['organization_licenses', 'organization_license_periods', 'organization_license_assignments',
                             'organization_license_acts', 'organization_license_enforcement'] LOOP
        EXECUTE format('REVOKE ALL ON public.%I FROM PUBLIC', t);
        EXECUTE format('GRANT SELECT ON public.%I TO nekkora_platform', t);
    END LOOP;
END;
$$;
REVOKE ALL ON FUNCTION public.organization_license_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.organization_license_on_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.organization_license_period_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.organization_license_assignment_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.organization_license_assignment_on_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.organization_license_enforcement_on_write() FROM PUBLIC;

-- === 9. Invariantes ===
DO $$
DECLARE
    t TEXT;
    r TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['organization_licenses', 'organization_license_periods', 'organization_license_assignments',
                             'organization_license_acts', 'organization_license_enforcement'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = ('public.' || t)::regclass AND c.relrowsecurity AND c.relforcerowsecurity) THEN
            RAISE EXCEPTION '061: RLS ausente em %', t;
        END IF;
        IF EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = ('public.' || t)::regclass AND c.reltuples > 0)
           OR (SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = t) <> 1 THEN
            RAISE EXCEPTION '061: % fora do previsto (linhas ou politicas)', t;
        END IF;
        IF has_table_privilege('nekkora_platform', 'public.' || t, 'INSERT') OR has_table_privilege('nekkora_platform', 'public.' || t, 'UPDATE')
           OR has_table_privilege('nekkora_platform', 'public.' || t, 'DELETE') OR NOT has_table_privilege('nekkora_platform', 'public.' || t, 'SELECT') THEN
            RAISE EXCEPTION '061: a Platform deve ter so SELECT em %', t;
        END IF;
        FOREACH r IN ARRAY ARRAY['nekkora_ingestion', 'nekkora_portal', 'nekkora_provisioning'] LOOP
            IF to_regrole(r) IS NOT NULL AND has_table_privilege(r, 'public.' || t, 'SELECT, INSERT, UPDATE, DELETE') THEN
                RAISE EXCEPTION '061: % com privilegio indevido em %', r, t;
            END IF;
        END LOOP;
    END LOOP;
    -- nenhuma funcao desta migration e SECURITY DEFINER
    IF EXISTS (SELECT 1 FROM pg_proc p WHERE p.pronamespace = 'public'::regnamespace AND p.proname LIKE 'organization\_license\_%' AND p.prosecdef) THEN
        RAISE EXCEPTION '061: funcao SECURITY DEFINER inesperada';
    END IF;
    -- as cinco tabelas nascem vazias: nenhuma licenca, nenhum modelo ligado
    IF EXISTS (SELECT 1 FROM public.organization_licenses) OR EXISTS (SELECT 1 FROM public.organization_license_enforcement) THEN
        RAISE EXCEPTION '061: tabela de licenca com linha';
    END IF;
END;
$$;

COMMIT;
