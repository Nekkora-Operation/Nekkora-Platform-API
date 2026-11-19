-- Nekkora Platform - atos do OPERADOR DA NEKKORA sobre as licencas das organizacoes: conceder, renovar, encerrar e ligar ou
-- desligar a exigencia de licenca (PLATFORM-LICENCIAMENTO-001, D5). Contrato:
-- docs/platform-licenciamento-001/t04-atos-do-operador.md e api/openapi/nekkora-api-v1.yaml (rotas
-- /api/v1/o/{org}/license-operations/...).
--
-- CANDIDATA ate a aprovacao do PR. Depende da 061 e da 062. Numero 063 conferido em 19/11 contra a dev (86591bb; a maior era
-- 060) e os PRs abertos (061 e 062 sao desta missao). Numeracao contigua: se a ordem mudar, e renumerada sem mudar o conteudo.
--
-- *** ELEVACAO: cria um papel (nekkora_licensing_owner) e funcoes SECURITY DEFINER desse dono. Exige a janela de SUPERUSER do
-- *** migrador (db/bootstrap/aplicar_como_migrador.sh, padrao com 063). AUTORIZADA SO EM LABORATORIO. A janela em homologacao
-- *** e producao (ops/homologacao/implantacao/elevacao.txt) NAO faz parte desta migration nem deste PR e nao foi pedida.
--
-- POR QUE PRECISA DE ELEVACAO NA INSTALACAO E NAO NO USO: o operador age a partir da organizacao interna da Nekkora e grava
-- licencas de OUTRA organizacao. A Platform nao tem (e continua sem ter) INSERT nem UPDATE em licencas, periodos e no modelo
-- de habilitacao: um defeito em qualquer rota dela nao concede licenca nem liga a exigencia. Quem grava sao CINCO funcoes
-- especificas, do dono nekkora_licensing_owner, que reconferem DENTRO DO BANCO que o operador informado esta no cadastro de
-- operadores, ativo, e pertence a OUTRA organizacao que nao a alvo. Criar funcao com esse dono exige a elevacao UMA vez;
-- depois, cada concessao e uma chamada comum da aplicacao, sem elevacao.
--
-- O QUE O BANCO RECONFERE E O QUE NAO: reconfere o cadastro do operador, que ele nao e da organizacao alvo, a justificativa e
-- as regras de cada ato. NAO reconfere a sessao nem o codigo do autenticador do operador: isso e da aplicacao (sessao valida,
-- segundo fator e confirmacao reforcada por codigo, a cada ato).
--
-- O QUE FAZ
--   1. Papel nekkora_licensing_owner (NOLOGIN, sem atributos), com acesso SO as cinco tabelas de licenca e ao cadastro novo.
--   2. license_operators: o cadastro dos operadores de licenca. Uma linha por conta (da organizacao interna da Nekkora), com
--      quem a incluiu. So o migrador escreve (procedimento administrativo, nao e ato do dia a dia). Nasce VAZIO.
--   3. Funcoes (EXECUTE so para nekkora_platform):
--        license_op_is_operator(operador)                      -> o operador esta no cadastro e ativo?
--        license_op_grant(operador, org, n, inicio, fim, just) -> concede n licencas com o periodo [inicio, fim); ato 1 cada
--        license_op_renew(operador, org, licenca, inicio, fim, just) -> acrescenta um periodo; mantem o vinculo; ato 2
--        license_op_end(operador, org, licenca, just)          -> encerra a licenca e o vinculo aberto dela; ato 3
--        license_op_set_model(operador, org, modelo, just, detalhe) -> liga (2) ou desliga (1) a exigencia; ato 6 ou 7,
--                                                                   com o retrato da previa em detalhe
--   4. O numero da licenca (061) passa a ser atribuido sob trava por identificador da organizacao, em vez de trava de linha
--      em organizations (o dono novo nao tem, nem deve ter, privilegio nessa tabela).
--
-- O QUE NAO FAZ: nao cadastra operador; nao concede licenca; nao liga a exigencia em organizacao nenhuma; nao cria a
-- transferencia (etapa propria); nao faz nenhum caminho de execucao ler licenca.
--
-- DADOS EXISTENTES: nenhuma linha e alterada.
--
-- REVERSAO (manual, em janela de elevacao; so enquanto nao houver ato do operador gravado):
--   BEGIN;
--   DROP FUNCTION public.license_op_set_model(uuid, uuid, smallint, text, jsonb), public.license_op_end(uuid, uuid, uuid, text),
--                 public.license_op_renew(uuid, uuid, uuid, timestamptz, timestamptz, text),
--                 public.license_op_grant(uuid, uuid, integer, timestamptz, timestamptz, text),
--                 public.license_op_is_operator(uuid), public.license_op_require(uuid, uuid, text);
--   DROP TABLE public.license_operators;
--   -- recriar organization_license_on_insert() como na 061; remover as politicas licensing_owner das cinco tabelas
--   DROP OWNED BY nekkora_licensing_owner; DROP ROLE nekkora_licensing_owner;
--   COMMIT;

BEGIN;

-- === 1. Pre-condicao ===
DO $$
BEGIN
    IF NOT has_table_privilege('nekkora_platform', 'public.organization_license_assignments', 'INSERT') THEN
        RAISE EXCEPTION '063 exige a 062 aplicada';
    END IF;
    IF to_regclass('public.license_operators') IS NOT NULL THEN
        RAISE EXCEPTION '063: license_operators ja existe';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_licensing_owner'
                  AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls OR rolreplication OR rolcanlogin)) THEN
        RAISE EXCEPTION '063: papel nekkora_licensing_owner pre-existente com atributo indevido';
    END IF;
END;
$$;

-- === 2. Dono das funcoes ===
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_licensing_owner') THEN
        CREATE ROLE nekkora_licensing_owner NOLOGIN;
    END IF;
END;
$$;
ALTER ROLE nekkora_licensing_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

-- === 3. Cadastro de operadores ===
CREATE TABLE public.license_operators (
    operator_user_id  UUID PRIMARY KEY,
    organization_id   UUID NOT NULL,
    registered_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    registered_by     TEXT NOT NULL CHECK (length(btrim(registered_by)) BETWEEN 3 AND 200),
    revoked_at        TIMESTAMPTZ,
    FOREIGN KEY (organization_id, operator_user_id) REFERENCES public.users (organization_id, user_id) ON DELETE RESTRICT,
    CHECK (revoked_at IS NULL OR revoked_at >= registered_at)
);
COMMENT ON TABLE public.license_operators IS
    'Operadores de licenca (063): contas da organizacao interna da Nekkora autorizadas a conceder, renovar e encerrar licencas e a '
    'ligar a exigencia nas organizacoes dos clientes. So o migrador escreve (inclusao e revogacao sao procedimento administrativo). '
    'registered_by: quem autorizou a inclusao (texto livre do procedimento, nunca segredo).';
ALTER TABLE public.license_operators ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.license_operators FORCE ROW LEVEL SECURITY;
CREATE POLICY licensing_owner ON public.license_operators AS PERMISSIVE FOR SELECT TO nekkora_licensing_owner USING (true);
REVOKE ALL ON public.license_operators FROM PUBLIC;
GRANT SELECT ON public.license_operators TO nekkora_licensing_owner;

-- === 4. O dono alcanca SO as cinco tabelas de licenca ===
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['organization_licenses', 'organization_license_periods', 'organization_license_assignments',
                             'organization_license_acts', 'organization_license_enforcement'] LOOP
        EXECUTE format('CREATE POLICY licensing_owner ON public.%I AS PERMISSIVE FOR ALL TO nekkora_licensing_owner USING (true) WITH CHECK (true)', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE ON public.%I TO nekkora_licensing_owner', t);
    END LOOP;
END;
$$;

-- === 5. Numero da licenca sob trava por identificador (substitui a trava de linha em organizations da 061) ===
CREATE OR REPLACE FUNCTION public.organization_license_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('organization_license_number:' || NEW.organization_id::text, 0));
    NEW.created_at := clock_timestamp();
    NEW.ended_at := NULL;
    SELECT coalesce(max(l.license_number), 0) + 1 INTO NEW.license_number
      FROM public.organization_licenses l WHERE l.organization_id = NEW.organization_id;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.organization_license_on_insert() FROM PUBLIC;

-- === 6. Funcoes do operador ===

-- Conferencia comum (interna: so o dono executa). O operador esta no cadastro, ativo, e NAO e da organizacao alvo.
CREATE FUNCTION public.license_op_require(p_operator UUID, p_org UUID, p_justification TEXT) RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_operator_org UUID;
BEGIN
    IF p_operator IS NULL OR p_org IS NULL THEN
        RAISE EXCEPTION 'ato recusado: INVALID' USING ERRCODE = '22023';
    END IF;
    SELECT o.organization_id INTO v_operator_org FROM public.license_operators o
     WHERE o.operator_user_id = p_operator AND o.revoked_at IS NULL;
    IF v_operator_org IS NULL THEN
        RAISE EXCEPTION 'ato recusado: OPERATOR_NOT_AUTHORIZED' USING ERRCODE = '42501';
    END IF;
    IF v_operator_org = p_org THEN
        RAISE EXCEPTION 'ato recusado: OPERATOR_OF_TARGET_ORGANIZATION' USING ERRCODE = '42501';
    END IF;
    IF p_justification IS NULL OR length(btrim(p_justification)) NOT BETWEEN 10 AND 1000 THEN
        RAISE EXCEPTION 'ato recusado: JUSTIFICATION_REQUIRED' USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION public.license_op_is_operator(p_operator UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM public.license_operators o WHERE o.operator_user_id = p_operator AND o.revoked_at IS NULL)
$$;

-- Concede p_count licencas, todas com o periodo [p_starts, p_ends). Nenhuma nasce vinculada: vincular e ato do administrador.
CREATE FUNCTION public.license_op_grant(p_operator UUID, p_org UUID, p_count INTEGER, p_starts TIMESTAMPTZ, p_ends TIMESTAMPTZ,
                                        p_justification TEXT)
RETURNS TABLE (license_id UUID, license_number INTEGER, act_id UUID)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
#variable_conflict use_variable
DECLARE
    v_license UUID;
    v_number INTEGER;
    v_period UUID;
    v_act UUID;
BEGIN
    PERFORM public.license_op_require(p_operator, p_org, p_justification);
    IF p_count IS NULL OR p_count < 1 OR p_count > 100 OR p_starts IS NULL OR p_ends IS NULL OR p_ends <= p_starts THEN
        RAISE EXCEPTION 'ato recusado: INVALID' USING ERRCODE = '22023';
    END IF;
    IF p_ends <= clock_timestamp() THEN
        RAISE EXCEPTION 'ato recusado: PERIOD_ALREADY_ENDED' USING ERRCODE = '23514';
    END IF;
    FOR i IN 1..p_count LOOP
        INSERT INTO public.organization_licenses AS l (organization_id, license_number) VALUES (p_org, 1)
        RETURNING l.license_id, l.license_number INTO v_license, v_number;
        INSERT INTO public.organization_license_periods AS p (organization_id, license_id, period_number, kind, starts_at, ends_at)
        VALUES (p_org, v_license, 1, 1, p_starts, p_ends) RETURNING p.period_id INTO v_period;
        INSERT INTO public.organization_license_acts AS a (organization_id, act, license_id, period_id, operator_user_id, justification)
        VALUES (p_org, 1, v_license, v_period, p_operator, btrim(p_justification)) RETURNING a.act_id INTO v_act;
        license_id := v_license;
        license_number := v_number;
        act_id := v_act;
        RETURN NEXT;
    END LOOP;
END;
$$;

-- Renova: acrescenta um periodo. p_starts nulo = comeca no fim do ultimo periodo. O vinculo com a automacao nao e tocado.
CREATE FUNCTION public.license_op_renew(p_operator UUID, p_org UUID, p_license UUID, p_starts TIMESTAMPTZ, p_ends TIMESTAMPTZ,
                                        p_justification TEXT)
RETURNS UUID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_ended TIMESTAMPTZ;
    v_last TIMESTAMPTZ;
    v_starts TIMESTAMPTZ;
    v_period UUID;
BEGIN
    PERFORM public.license_op_require(p_operator, p_org, p_justification);
    IF p_license IS NULL OR p_ends IS NULL THEN
        RAISE EXCEPTION 'ato recusado: INVALID' USING ERRCODE = '22023';
    END IF;
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('organization_license:' || p_license::text, 0));
    SELECT l.ended_at INTO v_ended FROM public.organization_licenses l WHERE l.organization_id = p_org AND l.license_id = p_license;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'ato recusado: LICENSE_NOT_FOUND' USING ERRCODE = '23514';
    END IF;
    IF v_ended IS NOT NULL THEN
        RAISE EXCEPTION 'ato recusado: LICENSE_ENDED' USING ERRCODE = '23514';
    END IF;
    SELECT max(p.ends_at) INTO v_last FROM public.organization_license_periods p WHERE p.organization_id = p_org AND p.license_id = p_license;
    v_starts := coalesce(p_starts, v_last);
    IF v_starts < v_last THEN
        RAISE EXCEPTION 'ato recusado: PERIOD_OVERLAPS' USING ERRCODE = '23514';
    END IF;
    IF p_ends <= v_starts THEN
        RAISE EXCEPTION 'ato recusado: INVALID' USING ERRCODE = '22023';
    END IF;
    IF p_ends <= clock_timestamp() THEN
        RAISE EXCEPTION 'ato recusado: PERIOD_ALREADY_ENDED' USING ERRCODE = '23514';
    END IF;
    INSERT INTO public.organization_license_periods AS p (organization_id, license_id, period_number, kind, starts_at, ends_at)
    VALUES (p_org, p_license, 1, 2, v_starts, p_ends) RETURNING p.period_id INTO v_period;
    INSERT INTO public.organization_license_acts (organization_id, act, license_id, period_id, operator_user_id, justification)
    VALUES (p_org, 2, p_license, v_period, p_operator, btrim(p_justification));
    RETURN v_period;
END;
$$;

-- Encerra a licenca e o vinculo aberto dela. Nao interrompe nada: o que foi admitido na validade segue pelas regras de entrega.
CREATE FUNCTION public.license_op_end(p_operator UUID, p_org UUID, p_license UUID, p_justification TEXT)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_ended TIMESTAMPTZ;
    v_from UUID;
BEGIN
    PERFORM public.license_op_require(p_operator, p_org, p_justification);
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('organization_license:' || p_license::text, 0));
    SELECT l.ended_at INTO v_ended FROM public.organization_licenses l WHERE l.organization_id = p_org AND l.license_id = p_license;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'ato recusado: LICENSE_NOT_FOUND' USING ERRCODE = '23514';
    END IF;
    IF v_ended IS NOT NULL THEN
        RAISE EXCEPTION 'ato recusado: LICENSE_ENDED' USING ERRCODE = '23514';
    END IF;
    UPDATE public.organization_license_assignments g SET released_at = clock_timestamp(), release_reason = 2
     WHERE g.organization_id = p_org AND g.license_id = p_license AND g.released_at IS NULL
    RETURNING g.automation_id INTO v_from;
    UPDATE public.organization_licenses l SET ended_at = clock_timestamp() WHERE l.organization_id = p_org AND l.license_id = p_license;
    INSERT INTO public.organization_license_acts (organization_id, act, license_id, from_automation_id, operator_user_id, justification)
    VALUES (p_org, 3, p_license, v_from, p_operator, btrim(p_justification));
END;
$$;

-- Liga (2) ou desliga (1) a exigencia de licenca da organizacao. p_detail: o retrato da previa que o operador confirmou.
CREATE FUNCTION public.license_op_set_model(p_operator UUID, p_org UUID, p_model SMALLINT, p_justification TEXT, p_detail JSONB)
RETURNS UUID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_current SMALLINT;
    v_act UUID;
BEGIN
    PERFORM public.license_op_require(p_operator, p_org, p_justification);
    IF p_model IS NULL OR p_model NOT IN (1, 2) THEN
        RAISE EXCEPTION 'ato recusado: INVALID' USING ERRCODE = '22023';
    END IF;
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('organization_license_model:' || p_org::text, 0));
    SELECT e.model INTO v_current FROM public.organization_license_enforcement e WHERE e.organization_id = p_org;
    IF coalesce(v_current, 1::smallint) = p_model THEN
        RAISE EXCEPTION 'ato recusado: MODEL_UNCHANGED' USING ERRCODE = '23514';
    END IF;
    INSERT INTO public.organization_license_acts AS a (organization_id, act, operator_user_id, justification, detail)
    VALUES (p_org, CASE p_model WHEN 2 THEN 6 ELSE 7 END, p_operator, btrim(p_justification), coalesce(p_detail, '{}'::jsonb))
    RETURNING a.act_id INTO v_act;
    IF v_current IS NULL THEN
        INSERT INTO public.organization_license_enforcement (organization_id, model, act_id) VALUES (p_org, p_model, v_act);
    ELSE
        UPDATE public.organization_license_enforcement e SET model = p_model, act_id = v_act WHERE e.organization_id = p_org;
    END IF;
    RETURN v_act;
END;
$$;

-- === 7. Dono e privilegios ===
DO $$
DECLARE
    f TEXT;
BEGIN
    FOREACH f IN ARRAY ARRAY['public.license_op_require(uuid, uuid, text)', 'public.license_op_is_operator(uuid)',
                             'public.license_op_grant(uuid, uuid, integer, timestamptz, timestamptz, text)',
                             'public.license_op_renew(uuid, uuid, uuid, timestamptz, timestamptz, text)',
                             'public.license_op_end(uuid, uuid, uuid, text)',
                             'public.license_op_set_model(uuid, uuid, smallint, text, jsonb)'] LOOP
        EXECUTE format('ALTER FUNCTION %s OWNER TO nekkora_licensing_owner', f);
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', f);
        IF f NOT LIKE '%license_op_require%' THEN
            EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO nekkora_platform', f);
        END IF;
    END LOOP;
END;
$$;

-- === 8. Invariantes ===
DO $$
DECLARE
    p RECORD;
    t TEXT;
    v_bad TEXT;
BEGIN
    -- as seis funcoes: do dono, SECURITY DEFINER, search_path fixo, sem EXECUTE publico; a interna nao e da Platform
    FOR p IN SELECT q.oid, q.proname, q.proowner, q.prosecdef, q.proconfig FROM pg_proc q
              WHERE q.pronamespace = 'public'::regnamespace AND q.proname LIKE 'license\_op\_%' LOOP
        IF pg_get_userbyid(p.proowner) <> 'nekkora_licensing_owner' OR NOT p.prosecdef
           OR NOT coalesce(p.proconfig @> ARRAY['search_path=pg_catalog, pg_temp'], false) OR has_function_privilege('public', p.oid, 'EXECUTE') THEN
            RAISE EXCEPTION '063: funcao % fora do padrao', p.proname;
        END IF;
        IF (p.proname = 'license_op_require') = has_function_privilege('nekkora_platform', p.oid, 'EXECUTE') THEN
            RAISE EXCEPTION '063: EXECUTE da Platform fora do previsto em %', p.proname;
        END IF;
    END LOOP;
    IF (SELECT count(*) FROM pg_proc q WHERE q.pronamespace = 'public'::regnamespace AND q.proname LIKE 'license\_op\_%') <> 6 THEN
        RAISE EXCEPTION '063: esperadas 6 funcoes license_op_*';
    END IF;
    SELECT string_agg(r || ':' || q.proname, ', ') INTO v_bad
      FROM pg_proc q, unnest(ARRAY['nekkora_portal', 'nekkora_ingestion', 'nekkora_provisioning', 'nekkora_agent_gateway']) r
     WHERE q.pronamespace = 'public'::regnamespace AND q.proname LIKE 'license\_op\_%' AND to_regrole(r) IS NOT NULL
       AND has_function_privilege(r, q.oid, 'EXECUTE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '063: EXECUTE indevido: %', v_bad; END IF;
    -- a Platform continua sem conceder, renovar, encerrar ou ligar a exigencia direto nas tabelas, e nao le o cadastro
    IF has_table_privilege('nekkora_platform', 'public.organization_licenses', 'INSERT, UPDATE, DELETE')
       OR has_table_privilege('nekkora_platform', 'public.organization_license_periods', 'INSERT, UPDATE, DELETE')
       OR has_table_privilege('nekkora_platform', 'public.organization_license_enforcement', 'INSERT, UPDATE, DELETE')
       OR has_table_privilege('nekkora_platform', 'public.license_operators', 'SELECT, INSERT, UPDATE, DELETE') THEN
        RAISE EXCEPTION '063: a Platform ganhou escrita direta em licencas ou acesso ao cadastro de operadores';
    END IF;
    -- o dono novo: sem atributos, sem alcance fora das tabelas de licenca
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_licensing_owner'
                  AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls OR rolreplication OR rolcanlogin)) THEN
        RAISE EXCEPTION '063: nekkora_licensing_owner com atributo indevido';
    END IF;
    FOREACH t IN ARRAY ARRAY['organizations', 'users', 'automations', 'organization_memberships', 'user_sessions', 'executions',
                             'remote_executions', 'local_run_authorizations'] LOOP
        IF has_table_privilege('nekkora_licensing_owner', 'public.' || t, 'SELECT, INSERT, UPDATE, DELETE') THEN
            RAISE EXCEPTION '063: nekkora_licensing_owner alcanca %', t;
        END IF;
    END LOOP;
    IF has_table_privilege('nekkora_licensing_owner', 'public.organization_licenses', 'DELETE')
       OR has_table_privilege('nekkora_licensing_owner', 'public.organization_license_acts', 'DELETE') THEN
        RAISE EXCEPTION '063: o dono nao apaga';
    END IF;
    -- nasce sem operador, sem licenca concedida por esta migration e sem exigencia ligada
    IF EXISTS (SELECT 1 FROM public.license_operators) THEN
        RAISE EXCEPTION '063: cadastro de operadores com linha';
    END IF;
END;
$$;

COMMIT;
