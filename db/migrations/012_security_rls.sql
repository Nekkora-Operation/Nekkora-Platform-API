-- Nekkora Platform - seguranca do PostgreSQL: papeis por servico, privilegios minimos, funcoes
-- privilegiadas encapsuladas, provisionamento atomico e RLS por organizacao.
-- Depende de 001-011. Nao altera logica de negocio de 001-011: as mudancas em funcoes existentes
-- sao administrativas (search_path, proprietario, EXECUTE) ou ja acordadas nos documentos de
-- desenho (checagem de current_user no bootstrap; contexto de funcao em volta dos corpos da 011).
--
-- Base: relatorios/2026-09-25-contratos-funcoes-rls.md (rev. 3), 2026-09-25-matriz-privilegios-001-011.md
-- (rev. 4), 2026-09-25-preparacao-seguranca-postgres.md.
--
-- Contextos de sessao (contrato de funcoes, secao 1):
--   nekkora.organization_id     - definido pelo backend (nekkora_platform/nekkora_ingestion) com
--                                 SET LOCAL no inicio de cada transacao. Nunca sobrescrito por
--                                 funcao. Ausente/vazio = nenhuma linha visivel, nenhuma escrita.
--   nekkora.fn_organization_id  - definido pela propria funcao privilegiada a partir do parametro
--                                 ja validado; salvo e restaurado por funcao (retorno normal e
--                                 EXCEPTION). Politicas dos proprietarios de funcao usam so este.
--   nekkora.retention_txn_category - guard da 011; transaction-scoped, NUNCA restaurado por funcao.
--
-- RLS nao se aplica a superusuario nem a papel com BYPASSRLS: o papel de migration continua
-- enxergando tudo. Nenhum papel de servico e superusuario, dono de tabela ou BYPASSRLS
-- (verificado no bloco final desta migration).
--
-- Credenciais: os papeis LOGIN sao criados sem senha. Senha/rotacao ficam fora do codigo
-- (preparacao de seguranca, secao 6).
--
-- Atomica: toda a migration roda numa unica transacao (CREATE ROLE/TYPE/POLICY e ALTER DEFAULT
-- PRIVILEGES sao transacionais no PostgreSQL) - uma falha no meio nao deixa estado parcial.

BEGIN;

-- === 1. Privilegios padrao do papel que executa as migrations ===
--
-- Nivel GLOBAL (sem IN SCHEMA), para o papel corrente: funcoes que este papel criar daqui em
-- diante nao ganham EXECUTE para PUBLIC. A forma "IN SCHEMA public" so ACRESCENTA privilegios
-- ao padrao global - nao consegue revogar o que o global concede - por isso nao e usada aqui
-- (usa-la para revogar seria um no-op silencioso). Nao altera objetos ja existentes.

ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- === 2. Papeis ===

DO $$
DECLARE
    r TEXT;
BEGIN
    FOREACH r IN ARRAY ARRAY['nekkora_platform', 'nekkora_ingestion', 'nekkora_scheduler', 'nekkora_provisioning'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('CREATE ROLE %I LOGIN', r);
        END IF;
    END LOOP;
    FOREACH r IN ARRAY ARRAY['nekkora_scheduler_owner', 'nekkora_provisioning_owner'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('CREATE ROLE %I NOLOGIN', r);
        END IF;
    END LOOP;
END;
$$;

-- Atributos explicitos, idempotentes, tambem para os papeis da 011.
ALTER ROLE nekkora_platform              LOGIN   NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE nekkora_ingestion             LOGIN   NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE nekkora_scheduler             LOGIN   NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE nekkora_provisioning          LOGIN   NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE nekkora_retention_executor    LOGIN   NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE nekkora_scheduler_owner       NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE nekkora_provisioning_owner    NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE nekkora_retention_owner       NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE nekkora_retention_tables_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

-- === 3. Tipo de entrada do provisionamento (contrato, secao 5.2) ===

CREATE TYPE resource_limit_input AS (
    resource_code INTEGER,
    limit_mode    SMALLINT,
    limit_value   BIGINT
);

-- === 4. search_path administrativo nas funcoes existentes (contrato, secao 3.1 - principio geral) ===
--
-- Toda funcao SECURITY INVOKER de 001-011 que referencia tabelas sem qualificacao pode ser
-- alcancada de dentro de uma funcao SECURITY DEFINER com search_path restrito (por trigger ou
-- chamada direta) - o defeito que a 011 encontrou em enforce_redaction_or_reject. ALTER FUNCTION
-- ... SET nao reescreve nenhum corpo. Aplicado a todas as funcoes plpgsql invoker de public (e as
-- duas funcoes sql que leem tabelas), exceto assign_received_at_and_lock_barrier, redefinida na
-- secao 6. As funcoes sql puras de validacao (is_valid_*) nao leem tabelas e ficam como estao.
-- pg_catalog primeiro, pg_temp por ultimo; public e seguro no meio porque CREATE em public esta
-- revogado de PUBLIC desde a 011 e nenhum papel de servico/proprietario recebe CREATE nele.

DO $$
DECLARE
    f RECORD;
BEGIN
    FOR f IN
        SELECT p.oid::regprocedure AS sig
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        JOIN pg_language l ON l.oid = p.prolang
        WHERE n.nspname = 'public'
          AND NOT p.prosecdef
          AND p.proconfig IS NULL
          AND (l.lanname = 'plpgsql' OR p.proname IN ('get_effective_resource_limit', 'get_retention_policy_at'))
          AND p.proname <> 'assign_received_at_and_lock_barrier'
    LOOP
        EXECUTE format('ALTER FUNCTION %s SET search_path = pg_catalog, public, pg_temp', f.sig);
    END LOOP;
END;
$$;

-- === 5. Bootstrap de ORG_ADMIN: so o proprietario do provisionamento (matriz, secao 3) ===
--
-- Unica mudanca de logica em funcao existente, ja acordada: responsavel nulo so e aceito quando
-- current_user e nekkora_provisioning_owner - o que so acontece dentro de uma funcao SECURITY
-- DEFINER daquele proprietario (nenhum papel tem membership nele). current_user, nao GUC: o
-- chamador nao consegue produzir esse valor. O restante do corpo e o da 003, inalterado.

CREATE OR REPLACE FUNCTION enforce_org_role_assignment_bootstrap() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF NEW.assigned_by_membership_id IS NULL THEN
        IF current_user <> 'nekkora_provisioning_owner' THEN
            RAISE EXCEPTION 'bootstrap sem responsavel so e permitido via provision_first_org_admin (current_user=%)', current_user
                USING ERRCODE = '42501';
        END IF;
        PERFORM 1 FROM organizations WHERE organization_id = NEW.organization_id FOR UPDATE;
        IF EXISTS (SELECT 1 FROM organization_role_assignments WHERE organization_id = NEW.organization_id) THEN
            RAISE EXCEPTION 'bootstrap sem responsavel só é permitido quando a organizacao ainda nao tem nenhuma atribuicao (organization_id=%)', NEW.organization_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- === 6. Barreira de ingestao encapsulada (matriz, secao 10; contrato, secao 2) ===
--
-- SECURITY DEFINER, proprietario nekkora_retention_owner (ja tem SELECT/INSERT/UPDATE em
-- organization_usage_daily pela 011), search_path restrito, referencias qualificadas.
-- Ordem obrigatoria: (1) conferir o contexto RECEBIDO (nekkora.organization_id do chamador) contra
-- p_organization_id; (2) so depois salvar/estabelecer o contexto INTERNO (fn_organization_id).
-- A logica do laco e a da 011; so o RETURN de dentro do laco virou atribuicao + EXIT, para o
-- contexto poder ser restaurado antes de retornar.

CREATE OR REPLACE FUNCTION assign_received_at_and_lock_barrier(p_organization_id UUID, p_resource_code INTEGER)
RETURNS TIMESTAMPTZ
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    probe TIMESTAMPTZ;
    candidate_day DATE;
    confirmed TIMESTAMPTZ;
    confirmed_day DATE;
    row_status SMALLINT;
    attempts INTEGER := 0;
    v_received_org TEXT;
    v_prev_fn_org TEXT;
    v_result TIMESTAMPTZ;
BEGIN
    -- (1) contexto recebido
    v_received_org := current_setting('nekkora.organization_id', true);
    IF p_organization_id IS NULL OR v_received_org IS NULL OR v_received_org = ''
       OR v_received_org::uuid IS DISTINCT FROM p_organization_id THEN
        RAISE EXCEPTION 'contexto organizacional ausente ou divergente na ingestao (organization_id=%, contexto=%)',
            p_organization_id, coalesce(nullif(v_received_org, ''), '<ausente>')
            USING ERRCODE = '42501';
    END IF;

    -- (2) contexto interno
    v_prev_fn_org := current_setting('nekkora.fn_organization_id', true);
    PERFORM set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    BEGIN
        LOOP
            attempts := attempts + 1;
            IF attempts > 5 THEN
                RAISE EXCEPTION 'nao foi possivel estabilizar a barreira de ingestao apos % tentativas (organization_id=%, resource_code=%)', attempts - 1, p_organization_id, p_resource_code;
            END IF;

            probe := clock_timestamp();
            candidate_day := (probe AT TIME ZONE 'UTC')::date;

            INSERT INTO public.organization_usage_daily (organization_id, resource_code, usage_date, records_count, bytes_count, status, consolidated_at)
            VALUES (p_organization_id, p_resource_code, candidate_day, 0, 0, 1, NULL)
            ON CONFLICT (organization_id, resource_code, usage_date) DO NOTHING;

            SELECT status INTO row_status FROM public.organization_usage_daily
            WHERE organization_id = p_organization_id AND resource_code = p_resource_code AND usage_date = candidate_day
            FOR SHARE;

            confirmed := clock_timestamp();
            confirmed_day := (confirmed AT TIME ZONE 'UTC')::date;

            IF confirmed_day = candidate_day THEN
                IF row_status <> 1 THEN
                    RAISE EXCEPTION 'agregado do dia ja esta fechado, insercao nao pode prosseguir (organization_id=%, resource_code=%, usage_date=%)', p_organization_id, p_resource_code, candidate_day;
                END IF;
                v_result := confirmed;
                EXIT;
            END IF;
            -- dia mudou durante a espera do lock - repete para o novo dia (mantendo o lock supérfluo do antigo)
        END LOOP;
    EXCEPTION WHEN OTHERS THEN
        PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
        RAISE;
    END;
    PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
    RETURN v_result;
END;
$$;

-- === 7. Funcoes de manutencao da 011: contexto de funcao em volta do corpo ja validado ===
--
-- SECURITY DEFINER so eleva privilegio; a politica RLS de nekkora_retention_owner precisa de
-- nekkora.fn_organization_id. O corpo original fica dentro do bloco protegido, sem alteracao de
-- logica (em purge_expired_records, o RETURN final virou atribuicao para permitir a restauracao).
-- nekkora.retention_txn_category continua sendo definido pelo guard e NAO e restaurado.

CREATE OR REPLACE FUNCTION close_usage_day(p_organization_id UUID, p_resource_code INTEGER, p_usage_date DATE)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    iso TEXT;
    today DATE;
    computed_records BIGINT;
    computed_bytes BIGINT;
    row_status SMALLINT;
    v_category INTEGER;
    v_prev_fn_org TEXT;
BEGIN
    IF p_organization_id IS NULL THEN
        RAISE EXCEPTION 'organization_id obrigatorio para fechamento' USING ERRCODE = '22004';
    END IF;
    v_prev_fn_org := current_setting('nekkora.fn_organization_id', true);
    PERFORM set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    BEGIN
        -- === corpo da 011, inalterado ===
        v_category := CASE p_resource_code
            WHEN 5 THEN 1
            WHEN 4 THEN 2
            ELSE NULL
        END;
        IF v_category IS NULL THEN
            RAISE EXCEPTION 'resource_code invalido para fechamento de consumo diario (%)', p_resource_code;
        END IF;
        PERFORM public.assert_single_category_per_transaction(p_organization_id, v_category);

        iso := current_setting('transaction_isolation');
        IF iso <> 'read committed' THEN
            RAISE EXCEPTION 'fechamento exige transaction_isolation=read committed (atual=%)', iso;
        END IF;

        today := (clock_timestamp() AT TIME ZONE 'UTC')::date;
        IF p_usage_date >= today THEN
            RAISE EXCEPTION 'fechamento so processa dias estritamente passados (usage_date=%, hoje=%)', p_usage_date, today;
        END IF;

        INSERT INTO public.organization_usage_daily (organization_id, resource_code, usage_date, records_count, bytes_count, status, consolidated_at)
        VALUES (p_organization_id, p_resource_code, p_usage_date, 0, 0, 1, NULL)
        ON CONFLICT (organization_id, resource_code, usage_date) DO NOTHING;

        SELECT status INTO row_status FROM public.organization_usage_daily
        WHERE organization_id = p_organization_id AND resource_code = p_resource_code AND usage_date = p_usage_date
        FOR UPDATE;

        IF row_status = 2 THEN
            RAISE EXCEPTION 'dia ja esta fechado (organization_id=%, resource_code=%, usage_date=%)', p_organization_id, p_resource_code, p_usage_date;
        END IF;

        IF p_resource_code = 5 THEN
            SELECT count(*), coalesce(sum(octet_length(payload::text)), 0) INTO computed_records, computed_bytes
            FROM public.execution_events
            WHERE organization_id = p_organization_id AND (received_at AT TIME ZONE 'UTC')::date = p_usage_date;
        ELSE
            SELECT count(*), coalesce(sum(octet_length(payload::text)), 0) INTO computed_records, computed_bytes
            FROM public.logs
            WHERE organization_id = p_organization_id AND (received_at AT TIME ZONE 'UTC')::date = p_usage_date;
        END IF;

        UPDATE public.organization_usage_daily
        SET records_count = computed_records, bytes_count = computed_bytes, status = 2, consolidated_at = clock_timestamp()
        WHERE organization_id = p_organization_id AND resource_code = p_resource_code AND usage_date = p_usage_date;
        -- === fim do corpo da 011 ===
    EXCEPTION WHEN OTHERS THEN
        PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
        RAISE;
    END;
    PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
END;
$$;

CREATE OR REPLACE FUNCTION purge_expired_records(
    p_organization_id UUID,
    p_category INTEGER,
    p_purged_by_user_id UUID,
    p_batch_limit INTEGER
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    run_id UUID;
    affected INTEGER;
    v_prev_fn_org TEXT;
BEGIN
    IF p_organization_id IS NULL THEN
        RAISE EXCEPTION 'organization_id obrigatorio para expurgo' USING ERRCODE = '22004';
    END IF;
    v_prev_fn_org := current_setting('nekkora.fn_organization_id', true);
    PERFORM set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    BEGIN
        -- === corpo da 011, inalterado (exceto RETURN final -> atribuicao) ===
        PERFORM public.assert_single_category_per_transaction(p_organization_id, p_category);

        IF p_batch_limit IS NULL OR p_batch_limit <= 0 THEN
            RAISE EXCEPTION 'lote de expurgo deve ser positivo (recebido=%)', p_batch_limit;
        END IF;
        IF p_batch_limit > 1000 THEN
            RAISE EXCEPTION 'lote de expurgo excede o teto permitido (recebido=%, teto=1000)', p_batch_limit;
        END IF;

        IF p_category NOT IN (1, 2) THEN
            RAISE EXCEPTION 'categoria invalida (%)', p_category;
        END IF;

        run_id := gen_random_uuid();

        IF p_category = 1 THEN
            UPDATE public.execution_events e
            SET payload = NULL, purged_at = clock_timestamp(), purged_by_user_id = p_purged_by_user_id, purge_run_id = run_id
            WHERE e.event_id IN (
                SELECT ev.event_id
                FROM public.execution_events ev
                JOIN public.organization_usage_daily d
                    ON d.organization_id = ev.organization_id AND d.resource_code = 5
                    AND d.usage_date = (ev.received_at AT TIME ZONE 'UTC')::date
                CROSS JOIN LATERAL public.get_retention_policy_at(ev.organization_id, 1, ev.received_at) rp
                WHERE ev.organization_id = p_organization_id
                  AND ev.purged_at IS NULL
                  AND d.status = 2
                  AND rp.retention_mode = 1
                  AND clock_timestamp() >= ev.received_at + (rp.retention_days * INTERVAL '1 day')
                ORDER BY ev.received_at
                LIMIT p_batch_limit
                FOR UPDATE OF ev
            );
        ELSE
            UPDATE public.logs l
            SET payload = NULL, purged_at = clock_timestamp(), purged_by_user_id = p_purged_by_user_id, purge_run_id = run_id
            WHERE l.log_id IN (
                SELECT lg.log_id
                FROM public.logs lg
                JOIN public.organization_usage_daily d
                    ON d.organization_id = lg.organization_id AND d.resource_code = 4
                    AND d.usage_date = (lg.received_at AT TIME ZONE 'UTC')::date
                CROSS JOIN LATERAL public.get_retention_policy_at(lg.organization_id, 2, lg.received_at) rp
                WHERE lg.organization_id = p_organization_id
                  AND lg.purged_at IS NULL
                  AND d.status = 2
                  AND rp.retention_mode = 1
                  AND clock_timestamp() >= lg.received_at + (rp.retention_days * INTERVAL '1 day')
                ORDER BY lg.received_at
                LIMIT p_batch_limit
                FOR UPDATE OF lg
            );
        END IF;

        GET DIAGNOSTICS affected = ROW_COUNT;

        INSERT INTO public.organization_purge_runs (purge_run_id, organization_id, category, records_purged, occurred_at, purged_by_user_id)
        VALUES (run_id, p_organization_id, p_category, affected, clock_timestamp(), p_purged_by_user_id);
        -- === fim do corpo da 011 ===
    EXCEPTION WHEN OTHERS THEN
        PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
        RAISE;
    END;
    PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
    RETURN affected;
END;
$$;

-- === 8. Reconsolidacao encapsulada (preparacao, secao 5; contrato, secao 2) ===
--
-- Recebe organizacao, recurso e dia - nunca contadores. Guard de categoria como primeira
-- instrucao do corpo (antes de qualquer lock); READ COMMITTED; FOR UPDATE direto (nunca FOR SHARE
-- seguido de conversao); calculo depois do lock; atualiza records_count, bytes_count e
-- consolidated_at - nunca status. Dia CLOSED e rejeitado explicitamente (e tambem pela trigger
-- prevent_usage_origin_change, inalterada). Dia futuro e rejeitado; hoje e dias passados OPEN sao
-- aceitos.

CREATE FUNCTION reconsolidate_usage_day(p_organization_id UUID, p_resource_code INTEGER, p_usage_date DATE)
RETURNS TABLE (out_records_count BIGINT, out_bytes_count BIGINT, out_consolidated_at TIMESTAMPTZ)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_category INTEGER;
    v_iso TEXT;
    v_today DATE;
    v_row_status SMALLINT;
    v_records BIGINT;
    v_bytes BIGINT;
    v_at TIMESTAMPTZ;
    v_prev_fn_org TEXT;
BEGIN
    IF p_organization_id IS NULL OR p_resource_code IS NULL OR p_usage_date IS NULL THEN
        RAISE EXCEPTION 'reconsolidacao exige organization_id, resource_code e usage_date' USING ERRCODE = '22004';
    END IF;
    v_prev_fn_org := current_setting('nekkora.fn_organization_id', true);
    PERFORM set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    BEGIN
        v_category := CASE p_resource_code WHEN 5 THEN 1 WHEN 4 THEN 2 ELSE NULL END;
        IF v_category IS NULL THEN
            RAISE EXCEPTION 'resource_code invalido para reconsolidacao de consumo diario (%)', p_resource_code;
        END IF;
        PERFORM public.assert_single_category_per_transaction(p_organization_id, v_category);

        v_iso := current_setting('transaction_isolation');
        IF v_iso <> 'read committed' THEN
            RAISE EXCEPTION 'reconsolidacao exige transaction_isolation=read committed (atual=%)', v_iso;
        END IF;

        v_today := (clock_timestamp() AT TIME ZONE 'UTC')::date;
        IF p_usage_date > v_today THEN
            RAISE EXCEPTION 'reconsolidacao nao processa dia futuro (usage_date=%, hoje=%)', p_usage_date, v_today;
        END IF;

        INSERT INTO public.organization_usage_daily (organization_id, resource_code, usage_date, records_count, bytes_count, status, consolidated_at)
        VALUES (p_organization_id, p_resource_code, p_usage_date, 0, 0, 1, NULL)
        ON CONFLICT (organization_id, resource_code, usage_date) DO NOTHING;

        SELECT status INTO v_row_status FROM public.organization_usage_daily
        WHERE organization_id = p_organization_id AND resource_code = p_resource_code AND usage_date = p_usage_date
        FOR UPDATE;

        IF v_row_status = 2 THEN
            RAISE EXCEPTION 'dia ja esta fechado, reconsolidacao nao permitida (organization_id=%, resource_code=%, usage_date=%)', p_organization_id, p_resource_code, p_usage_date;
        END IF;

        IF p_resource_code = 5 THEN
            SELECT count(*), coalesce(sum(octet_length(payload::text)), 0) INTO v_records, v_bytes
            FROM public.execution_events
            WHERE organization_id = p_organization_id AND (received_at AT TIME ZONE 'UTC')::date = p_usage_date;
        ELSE
            SELECT count(*), coalesce(sum(octet_length(payload::text)), 0) INTO v_records, v_bytes
            FROM public.logs
            WHERE organization_id = p_organization_id AND (received_at AT TIME ZONE 'UTC')::date = p_usage_date;
        END IF;

        UPDATE public.organization_usage_daily
        SET records_count = v_records, bytes_count = v_bytes, consolidated_at = clock_timestamp()
        WHERE organization_id = p_organization_id AND resource_code = p_resource_code AND usage_date = p_usage_date
        RETURNING consolidated_at INTO v_at;
    EXCEPTION WHEN OTHERS THEN
        PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
        RAISE;
    END;
    PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
    out_records_count := v_records;
    out_bytes_count := v_bytes;
    out_consolidated_at := v_at;
    RETURN NEXT;
END;
$$;

-- === 9. Agendamento (matriz, secao 9; contrato, secoes 2.1 e 4) ===

-- Implementacao existente (009/010, 2 parametros): corpo inalterado; passa a ser exclusivamente
-- interna - proprietario nekkora_scheduler_owner, sem EXECUTE para nenhum papel LOGIN. O
-- search_path ja foi fixado na secao 4 (pg_catalog, public, pg_temp).
ALTER FUNCTION process_schedule_occurrence(UUID, BOOLEAN) OWNER TO nekkora_scheduler_owner;
REVOKE ALL ON FUNCTION process_schedule_occurrence(UUID, BOOLEAN) FROM PUBLIC;

-- Novo ponto de entrada (3 parametros) - resolve a dependencia circular do contrato, secao 2.1.
CREATE FUNCTION process_schedule_occurrence(p_organization_id UUID, p_occurrence_id UUID, p_authorized BOOLEAN)
RETURNS TABLE (out_status SMALLINT, out_skip_reason SMALLINT, out_execution_id UUID)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_prev_fn_org TEXT;
    v_found_org UUID;
BEGIN
    IF p_organization_id IS NULL OR p_occurrence_id IS NULL OR p_authorized IS NULL THEN
        RAISE EXCEPTION 'processamento exige organization_id, occurrence_id e p_authorized' USING ERRCODE = '22004';
    END IF;
    -- 1-2) salva e estabelece o contexto interno
    v_prev_fn_org := current_setting('nekkora.fn_organization_id', true);
    PERFORM set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    BEGIN
        -- 3) a ocorrencia pertence a esta organizacao? (sob RLS: de outra organizacao = invisivel)
        SELECT o.organization_id INTO v_found_org
        FROM public.automation_schedule_occurrences o WHERE o.occurrence_id = p_occurrence_id;
        IF v_found_org IS DISTINCT FROM p_organization_id THEN
            RAISE EXCEPTION 'ocorrencia inexistente ou de outra organizacao (organization_id=%, occurrence_id=%)', p_organization_id, p_occurrence_id
                USING ERRCODE = '42501';
        END IF;
        -- 4) implementacao existente
        RETURN QUERY SELECT r.out_status, r.out_skip_reason, r.out_execution_id
                     FROM public.process_schedule_occurrence(p_occurrence_id, p_authorized) r;
    EXCEPTION WHEN OTHERS THEN
        PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
        RAISE;
    END;
    -- 5) restaura
    PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
    RETURN;
END;
$$;

-- Materializacao idempotente sob concorrencia (contrato, secao 4): locks organizations ->
-- automation_schedules, e so depois reconsulta por (schedule_id, scheduled_for).
CREATE FUNCTION create_schedule_occurrence(p_organization_id UUID, p_workspace_id UUID, p_schedule_id UUID, p_scheduled_for TIMESTAMPTZ)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_prev_fn_org TEXT;
    v_schedule_ws UUID;
    v_occurrence_id UUID;
BEGIN
    IF p_organization_id IS NULL OR p_workspace_id IS NULL OR p_schedule_id IS NULL OR p_scheduled_for IS NULL THEN
        RAISE EXCEPTION 'materializacao exige organization_id, workspace_id, schedule_id e scheduled_for' USING ERRCODE = '22004';
    END IF;
    v_prev_fn_org := current_setting('nekkora.fn_organization_id', true);
    PERFORM set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    BEGIN
        -- 1) referencias cruzadas
        SELECT s.workspace_id INTO v_schedule_ws
        FROM public.automation_schedules s
        WHERE s.schedule_id = p_schedule_id AND s.organization_id = p_organization_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'agendamento inexistente ou de outra organizacao (organization_id=%, schedule_id=%)', p_organization_id, p_schedule_id
                USING ERRCODE = '42501';
        END IF;
        IF v_schedule_ws IS DISTINCT FROM p_workspace_id THEN
            RAISE EXCEPTION 'agendamento nao pertence ao workspace informado (schedule_id=%, workspace_id=%)', p_schedule_id, p_workspace_id
                USING ERRCODE = '42501';
        END IF;

        -- 2) locks na ordem da base
        PERFORM 1 FROM public.organizations WHERE organization_id = p_organization_id FOR UPDATE;
        PERFORM 1 FROM public.automation_schedules WHERE schedule_id = p_schedule_id FOR UPDATE;

        -- 3) reconsulta so depois dos locks
        SELECT o.occurrence_id INTO v_occurrence_id
        FROM public.automation_schedule_occurrences o
        WHERE o.schedule_id = p_schedule_id AND o.scheduled_for = p_scheduled_for;

        -- 4) insere se ainda nao existe (a trigger materialize_schedule_occurrence calcula o resto)
        IF v_occurrence_id IS NULL THEN
            INSERT INTO public.automation_schedule_occurrences (organization_id, workspace_id, schedule_id, scheduled_for, status)
            VALUES (p_organization_id, p_workspace_id, p_schedule_id, p_scheduled_for, 1)
            RETURNING occurrence_id INTO v_occurrence_id;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
        RAISE;
    END;
    -- 5) restaura
    PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
    RETURN v_occurrence_id;
END;
$$;

-- Suspender/retomar - chamado por nekkora_platform. Alem do contrato comum, confere que
-- p_organization_id coincide com o contexto de requisicao do chamador (mesma disciplina da
-- barreira): sem isso, a funcao SECURITY DEFINER permitiria ao backend agir sobre outra
-- organizacao so trocando o parametro.
CREATE FUNCTION set_schedule_status(p_organization_id UUID, p_schedule_id UUID, p_desired_status SMALLINT, p_membership_id UUID)
RETURNS SMALLINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_received_org TEXT;
    v_prev_fn_org TEXT;
    v_current SMALLINT;
    v_result SMALLINT;
BEGIN
    IF p_organization_id IS NULL OR p_schedule_id IS NULL OR p_desired_status IS NULL OR p_membership_id IS NULL THEN
        RAISE EXCEPTION 'suspender/retomar exige organization_id, schedule_id, status desejado e membership_id' USING ERRCODE = '22004';
    END IF;
    IF p_desired_status NOT IN (1, 2) THEN
        RAISE EXCEPTION 'status desejado invalido (%): 1=ACTIVE, 2=SUSPENDED', p_desired_status USING ERRCODE = '22023';
    END IF;
    v_received_org := current_setting('nekkora.organization_id', true);
    IF v_received_org IS NULL OR v_received_org = '' OR v_received_org::uuid IS DISTINCT FROM p_organization_id THEN
        RAISE EXCEPTION 'contexto organizacional ausente ou divergente (organization_id=%, contexto=%)',
            p_organization_id, coalesce(nullif(v_received_org, ''), '<ausente>') USING ERRCODE = '42501';
    END IF;

    v_prev_fn_org := current_setting('nekkora.fn_organization_id', true);
    PERFORM set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    BEGIN
        SELECT s.status INTO v_current FROM public.automation_schedules s
        WHERE s.schedule_id = p_schedule_id AND s.organization_id = p_organization_id
        FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'agendamento inexistente ou de outra organizacao (organization_id=%, schedule_id=%)', p_organization_id, p_schedule_id
                USING ERRCODE = '42501';
        END IF;

        IF v_current = p_desired_status THEN
            v_result := v_current;
        ELSIF p_desired_status = 2 THEN
            UPDATE public.automation_schedules
            SET status = 2, suspended_at = clock_timestamp(), suspended_by_membership_id = p_membership_id
            WHERE schedule_id = p_schedule_id
            RETURNING status INTO v_result;
        ELSE
            UPDATE public.automation_schedules
            SET status = 1, suspended_at = NULL, suspended_by_membership_id = NULL
            WHERE schedule_id = p_schedule_id
            RETURNING status INTO v_result;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
        RAISE;
    END;
    PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
    RETURN v_result;
END;
$$;

-- === 10. Provisionamento (matriz, secoes 3 e 10; contrato, secoes 2 e 5.2) ===

-- Unico caminho de criacao de organizacao: organizacao + cinco revisoes de limite explicitas, na
-- mesma transacao. Nunca cria politica de retencao.
CREATE FUNCTION provision_organization(
    p_organization_id UUID,
    p_name TEXT,
    p_legal_name TEXT,
    p_provisioned_by_user_id UUID,
    p_resource_limits resource_limit_input[]
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_prev_fn_org TEXT;
    v_codes INTEGER[];
    v_elem public.resource_limit_input;
    v_idx INTEGER := 0;
BEGIN
    IF p_organization_id IS NULL OR p_name IS NULL OR p_provisioned_by_user_id IS NULL THEN
        RAISE EXCEPTION 'provisionamento exige organization_id, name e provisioned_by_user_id' USING ERRCODE = '22004';
    END IF;
    -- validacao explicita do array (contrato, secao 5.2), antes de qualquer INSERT
    IF p_resource_limits IS NULL OR array_ndims(p_resource_limits) IS DISTINCT FROM 1 THEN
        RAISE EXCEPTION 'p_resource_limits deve ser um array unidimensional nao nulo' USING ERRCODE = '22023';
    END IF;
    IF array_length(p_resource_limits, 1) <> 5 THEN
        RAISE EXCEPTION 'p_resource_limits deve ter exatamente 5 elementos (recebido=%)', array_length(p_resource_limits, 1) USING ERRCODE = '22023';
    END IF;
    FOREACH v_elem IN ARRAY p_resource_limits LOOP
        v_idx := v_idx + 1;
        IF v_elem IS NULL OR v_elem.resource_code IS NULL OR v_elem.limit_mode IS NULL THEN
            RAISE EXCEPTION 'elemento % de p_resource_limits sem resource_code/limit_mode', v_idx USING ERRCODE = '22023';
        END IF;
        IF v_elem.limit_mode = 1 THEN
            IF v_elem.limit_value IS NULL OR v_elem.limit_value < 0 THEN
                RAISE EXCEPTION 'elemento % (resource_code=%): FINITE exige limit_value >= 0', v_idx, v_elem.resource_code USING ERRCODE = '22023';
            END IF;
        ELSIF v_elem.limit_mode = 2 THEN
            IF v_elem.limit_value IS NOT NULL THEN
                RAISE EXCEPTION 'elemento % (resource_code=%): UNLIMITED exige limit_value nulo', v_idx, v_elem.resource_code USING ERRCODE = '22023';
            END IF;
        ELSE
            RAISE EXCEPTION 'elemento % (resource_code=%): limit_mode invalido (%)', v_idx, v_elem.resource_code, v_elem.limit_mode USING ERRCODE = '22023';
        END IF;
    END LOOP;
    SELECT array_agg(DISTINCT e.resource_code ORDER BY e.resource_code) INTO v_codes FROM unnest(p_resource_limits) e;
    IF v_codes IS DISTINCT FROM ARRAY[1, 2, 3, 4, 5] THEN
        RAISE EXCEPTION 'p_resource_limits deve cobrir exatamente os resource_code {1,2,3,4,5}, sem repeticao (recebido=%)', v_codes USING ERRCODE = '22023';
    END IF;

    v_prev_fn_org := current_setting('nekkora.fn_organization_id', true);
    PERFORM set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    BEGIN
        INSERT INTO public.organizations (organization_id, name, legal_name, status)
        VALUES (p_organization_id, p_name, p_legal_name, 1);

        -- revision_number/effective_from calculados pela trigger (010), nunca pelo chamador
        INSERT INTO public.organization_resource_limit_revisions (organization_id, resource_code, limit_mode, limit_value, provisioned_by_user_id)
        SELECT p_organization_id, e.resource_code, e.limit_mode, e.limit_value, p_provisioned_by_user_id
        FROM unnest(p_resource_limits) e
        ORDER BY e.resource_code;
    EXCEPTION WHEN OTHERS THEN
        PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
        RAISE;
    END;
    PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
    RETURN p_organization_id;
END;
$$;

-- Primeira atribuicao de ORG_ADMIN (responsavel nulo) + evento de provisionamento, atomicos.
CREATE FUNCTION provision_first_org_admin(
    p_organization_id UUID,
    p_membership_id UUID,
    p_provisioned_by_user_id UUID,
    p_channel TEXT,
    p_correlation_id UUID
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_prev_fn_org TEXT;
    v_role_id UUID;
    v_assignment_id UUID;
BEGIN
    IF p_organization_id IS NULL OR p_membership_id IS NULL OR p_provisioned_by_user_id IS NULL
       OR p_channel IS NULL OR btrim(p_channel) = '' OR p_correlation_id IS NULL THEN
        RAISE EXCEPTION 'bootstrap exige organization_id, membership_id, provisioned_by_user_id, channel e correlation_id' USING ERRCODE = '22004';
    END IF;
    v_prev_fn_org := current_setting('nekkora.fn_organization_id', true);
    PERFORM set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    BEGIN
        PERFORM 1 FROM public.organizations WHERE organization_id = p_organization_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'organizacao inexistente (organization_id=%)', p_organization_id USING ERRCODE = '42501';
        END IF;

        SELECT r.role_id INTO v_role_id FROM public.roles r WHERE r.code = 'ORG_ADMIN' AND r.scope = 1;
        IF v_role_id IS NULL THEN
            RAISE EXCEPTION 'papel ORG_ADMIN nao encontrado no catalogo';
        END IF;

        BEGIN
            INSERT INTO public.organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id)
            VALUES (p_organization_id, p_membership_id, v_role_id, 1, 1, NULL)
            RETURNING assignment_id INTO v_assignment_id;
        EXCEPTION WHEN foreign_key_violation THEN
            RAISE EXCEPTION 'vinculo nao pertence a organizacao informada (organization_id=%, membership_id=%)', p_organization_id, p_membership_id
                USING ERRCODE = '23503';
        END;

        INSERT INTO public.identity_provisioning_events (organization_id, assignment_id, provisioned_by_user_id, channel, correlation_id)
        VALUES (p_organization_id, v_assignment_id, p_provisioned_by_user_id, p_channel, p_correlation_id);
    EXCEPTION WHEN OTHERS THEN
        PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
        RAISE;
    END;
    PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
    RETURN v_assignment_id;
END;
$$;

CREATE FUNCTION provision_resource_limit(
    p_organization_id UUID,
    p_resource_code INTEGER,
    p_limit_mode SMALLINT,
    p_limit_value BIGINT,
    p_provisioned_by_user_id UUID
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_prev_fn_org TEXT;
    v_revision_id UUID;
BEGIN
    IF p_organization_id IS NULL OR p_resource_code IS NULL OR p_limit_mode IS NULL OR p_provisioned_by_user_id IS NULL THEN
        RAISE EXCEPTION 'provisionamento de limite exige organization_id, resource_code, limit_mode e provisioned_by_user_id' USING ERRCODE = '22004';
    END IF;
    IF p_resource_code NOT IN (1, 2, 3, 4, 5) THEN
        RAISE EXCEPTION 'resource_code invalido (%)', p_resource_code USING ERRCODE = '22023';
    END IF;
    IF p_limit_mode = 1 THEN
        IF p_limit_value IS NULL OR p_limit_value < 0 THEN
            RAISE EXCEPTION 'FINITE exige limit_value >= 0 (resource_code=%)', p_resource_code USING ERRCODE = '22023';
        END IF;
    ELSIF p_limit_mode = 2 THEN
        IF p_limit_value IS NOT NULL THEN
            RAISE EXCEPTION 'UNLIMITED exige limit_value nulo (resource_code=%)', p_resource_code USING ERRCODE = '22023';
        END IF;
    ELSE
        RAISE EXCEPTION 'limit_mode invalido (%)', p_limit_mode USING ERRCODE = '22023';
    END IF;
    v_prev_fn_org := current_setting('nekkora.fn_organization_id', true);
    PERFORM set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    BEGIN
        PERFORM 1 FROM public.organizations WHERE organization_id = p_organization_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'organizacao inexistente (organization_id=%)', p_organization_id USING ERRCODE = '42501';
        END IF;
        INSERT INTO public.organization_resource_limit_revisions (organization_id, resource_code, limit_mode, limit_value, provisioned_by_user_id)
        VALUES (p_organization_id, p_resource_code, p_limit_mode, p_limit_value, p_provisioned_by_user_id)
        RETURNING revision_id INTO v_revision_id;
    EXCEPTION WHEN OTHERS THEN
        PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
        RAISE;
    END;
    PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
    RETURN v_revision_id;
END;
$$;

CREATE FUNCTION provision_retention_policy(
    p_organization_id UUID,
    p_category INTEGER,
    p_retention_mode SMALLINT,
    p_retention_days INTEGER,
    p_provisioned_by_user_id UUID
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_prev_fn_org TEXT;
    v_revision_id UUID;
BEGIN
    IF p_organization_id IS NULL OR p_category IS NULL OR p_retention_mode IS NULL OR p_provisioned_by_user_id IS NULL THEN
        RAISE EXCEPTION 'provisionamento de retencao exige organization_id, category, retention_mode e provisioned_by_user_id' USING ERRCODE = '22004';
    END IF;
    IF p_category NOT IN (1, 2) THEN
        RAISE EXCEPTION 'category invalida (%)', p_category USING ERRCODE = '22023';
    END IF;
    IF p_retention_mode = 1 THEN
        IF p_retention_days IS NULL OR p_retention_days <= 0 THEN
            RAISE EXCEPTION 'FINITE exige retention_days > 0 (category=%)', p_category USING ERRCODE = '22023';
        END IF;
    ELSIF p_retention_mode = 2 THEN
        IF p_retention_days IS NOT NULL THEN
            RAISE EXCEPTION 'INDEFINITE exige retention_days nulo (category=%)', p_category USING ERRCODE = '22023';
        END IF;
    ELSE
        RAISE EXCEPTION 'retention_mode invalido (%)', p_retention_mode USING ERRCODE = '22023';
    END IF;
    v_prev_fn_org := current_setting('nekkora.fn_organization_id', true);
    PERFORM set_config('nekkora.fn_organization_id', p_organization_id::text, true);
    BEGIN
        PERFORM 1 FROM public.organizations WHERE organization_id = p_organization_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'organizacao inexistente (organization_id=%)', p_organization_id USING ERRCODE = '42501';
        END IF;
        INSERT INTO public.organization_retention_policy_revisions (organization_id, category, retention_mode, retention_days, provisioned_by_user_id)
        VALUES (p_organization_id, p_category, p_retention_mode, p_retention_days, p_provisioned_by_user_id)
        RETURNING revision_id INTO v_revision_id;
    EXCEPTION WHEN OTHERS THEN
        PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
        RAISE;
    END;
    PERFORM set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
    RETURN v_revision_id;
END;
$$;

-- === 11. Proprietarios e EXECUTE das funcoes privilegiadas ===

ALTER FUNCTION assign_received_at_and_lock_barrier(UUID, INTEGER) OWNER TO nekkora_retention_owner;
ALTER FUNCTION reconsolidate_usage_day(UUID, INTEGER, DATE) OWNER TO nekkora_retention_owner;
ALTER FUNCTION process_schedule_occurrence(UUID, UUID, BOOLEAN) OWNER TO nekkora_scheduler_owner;
ALTER FUNCTION create_schedule_occurrence(UUID, UUID, UUID, TIMESTAMPTZ) OWNER TO nekkora_scheduler_owner;
ALTER FUNCTION set_schedule_status(UUID, UUID, SMALLINT, UUID) OWNER TO nekkora_scheduler_owner;
ALTER FUNCTION provision_organization(UUID, TEXT, TEXT, UUID, resource_limit_input[]) OWNER TO nekkora_provisioning_owner;
ALTER FUNCTION provision_first_org_admin(UUID, UUID, UUID, TEXT, UUID) OWNER TO nekkora_provisioning_owner;
ALTER FUNCTION provision_resource_limit(UUID, INTEGER, SMALLINT, BIGINT, UUID) OWNER TO nekkora_provisioning_owner;
ALTER FUNCTION provision_retention_policy(UUID, INTEGER, SMALLINT, INTEGER, UUID) OWNER TO nekkora_provisioning_owner;
-- close_usage_day / purge_expired_records: proprietario nekkora_retention_owner desde a 011 (CREATE OR REPLACE preserva).

REVOKE ALL ON FUNCTION assign_received_at_and_lock_barrier(UUID, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION reconsolidate_usage_day(UUID, INTEGER, DATE) FROM PUBLIC;
REVOKE ALL ON FUNCTION process_schedule_occurrence(UUID, UUID, BOOLEAN) FROM PUBLIC;
REVOKE ALL ON FUNCTION create_schedule_occurrence(UUID, UUID, UUID, TIMESTAMPTZ) FROM PUBLIC;
REVOKE ALL ON FUNCTION set_schedule_status(UUID, UUID, SMALLINT, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION provision_organization(UUID, TEXT, TEXT, UUID, resource_limit_input[]) FROM PUBLIC;
REVOKE ALL ON FUNCTION provision_first_org_admin(UUID, UUID, UUID, TEXT, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION provision_resource_limit(UUID, INTEGER, SMALLINT, BIGINT, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION provision_retention_policy(UUID, INTEGER, SMALLINT, INTEGER, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION close_usage_day(UUID, INTEGER, DATE) FROM PUBLIC;
REVOKE ALL ON FUNCTION purge_expired_records(UUID, INTEGER, UUID, INTEGER) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION assign_received_at_and_lock_barrier(UUID, INTEGER) TO nekkora_ingestion;
GRANT EXECUTE ON FUNCTION close_usage_day(UUID, INTEGER, DATE) TO nekkora_retention_executor;   -- ja concedido na 011
GRANT EXECUTE ON FUNCTION reconsolidate_usage_day(UUID, INTEGER, DATE) TO nekkora_retention_executor;
GRANT EXECUTE ON FUNCTION process_schedule_occurrence(UUID, UUID, BOOLEAN) TO nekkora_scheduler;
GRANT EXECUTE ON FUNCTION create_schedule_occurrence(UUID, UUID, UUID, TIMESTAMPTZ) TO nekkora_scheduler;
GRANT EXECUTE ON FUNCTION set_schedule_status(UUID, UUID, SMALLINT, UUID) TO nekkora_platform;
GRANT EXECUTE ON FUNCTION provision_organization(UUID, TEXT, TEXT, UUID, resource_limit_input[]) TO nekkora_provisioning;
GRANT EXECUTE ON FUNCTION provision_first_org_admin(UUID, UUID, UUID, TEXT, UUID) TO nekkora_provisioning;
GRANT EXECUTE ON FUNCTION provision_resource_limit(UUID, INTEGER, SMALLINT, BIGINT, UUID) TO nekkora_provisioning;
GRANT EXECUTE ON FUNCTION provision_retention_policy(UUID, INTEGER, SMALLINT, INTEGER, UUID) TO nekkora_provisioning;
-- purge_expired_records: EXECUTE continua sem conceder a nenhum papel (ativacao fora de escopo).

-- === 12. Privilegios de tabela (matriz, secoes 1-10 e 13) ===
--
-- Tabelas protegidas por prevent_update() nao recebem UPDATE de nenhum papel; nenhum papel recebe
-- DELETE/TRUNCATE, exceto o DELETE ja aprovado em automation_version_bindings.

-- nekkora_platform - superficie de produto
GRANT SELECT, UPDATE ON organizations TO nekkora_platform;                         -- sem INSERT (so provision_organization)
GRANT SELECT, INSERT, UPDATE ON clients, workspaces TO nekkora_platform;
GRANT SELECT, INSERT, UPDATE ON users, user_credentials, organization_domains, organization_memberships,
    client_memberships, identity_invitations, email_verification_tokens, password_reset_tokens, user_sessions
    TO nekkora_platform;
GRANT SELECT ON permissions, roles, role_permissions TO nekkora_platform;
GRANT SELECT, INSERT, UPDATE ON organization_role_assignments, workspace_role_assignments TO nekkora_platform;
GRANT SELECT, INSERT, UPDATE ON client_workspace_grants TO nekkora_platform;
GRANT SELECT, INSERT ON client_workspace_grant_reviews, client_workspace_grant_approvals TO nekkora_platform;
GRANT SELECT, INSERT, UPDATE ON workers, agents, worker_workspace_assignments, worker_agent_assignments TO nekkora_platform;
GRANT SELECT, INSERT ON agent_credentials TO nekkora_platform;
GRANT UPDATE (revoked_at, revoked_by_membership_id, revocation_reason) ON agent_credentials TO nekkora_platform;
GRANT SELECT, INSERT, UPDATE ON automations, automation_versions TO nekkora_platform;
GRANT SELECT, INSERT, UPDATE ON executions TO nekkora_platform;
GRANT SELECT ON execution_events, logs TO nekkora_platform;
GRANT SELECT, INSERT, UPDATE ON workspace_parameters, workspace_secrets TO nekkora_platform;
GRANT SELECT, INSERT ON workspace_parameter_values, workspace_configuration_events TO nekkora_platform;
GRANT SELECT, INSERT, UPDATE, DELETE ON automation_version_bindings TO nekkora_platform;
GRANT SELECT, INSERT ON automation_version_parameter_bindings, automation_version_secret_bindings TO nekkora_platform;
GRANT UPDATE (parameter_id, parameter_value_id) ON automation_version_parameter_bindings TO nekkora_platform;
GRANT UPDATE (secret_id) ON automation_version_secret_bindings TO nekkora_platform;
GRANT SELECT, INSERT ON automation_schedules TO nekkora_platform;
GRANT UPDATE (name) ON automation_schedules TO nekkora_platform;                 -- status/suspensao so via set_schedule_status
GRANT SELECT, INSERT ON automation_schedule_revisions TO nekkora_platform;
GRANT SELECT ON automation_schedule_occurrences TO nekkora_platform;              -- sem escrita: so o scheduler materializa/processa
GRANT SELECT ON organization_resource_limit_revisions, organization_retention_policy_revisions TO nekkora_platform;

-- nekkora_ingestion - sucessor de nekkora_app (mesmo escopo da 011, sem organization_usage_daily)
GRANT SELECT, INSERT ON execution_events, logs TO nekkora_ingestion;
GRANT SELECT ON executions, worker_agent_assignments TO nekkora_ingestion;
GRANT SELECT ON organization_retention_policy_revisions TO nekkora_ingestion;

-- nekkora_scheduler_owner - dependencias reais das funcoes de agendamento e das triggers que elas disparam
GRANT SELECT, UPDATE ON organizations, automation_schedules, workspace_secrets TO nekkora_scheduler_owner;
GRANT SELECT, INSERT, UPDATE ON automation_schedule_occurrences TO nekkora_scheduler_owner;
GRANT SELECT ON automation_schedule_revisions, automations, automation_versions, workspaces,
    automation_version_bindings, automation_version_secret_bindings, organization_resource_limit_revisions
    TO nekkora_scheduler_owner;
GRANT SELECT, INSERT ON executions TO nekkora_scheduler_owner;

-- nekkora_provisioning_owner
GRANT SELECT, INSERT, UPDATE ON organizations TO nekkora_provisioning_owner;
GRANT SELECT, INSERT ON organization_resource_limit_revisions, organization_retention_policy_revisions TO nekkora_provisioning_owner;
GRANT SELECT, INSERT ON organization_role_assignments TO nekkora_provisioning_owner;
GRANT INSERT ON identity_provisioning_events TO nekkora_provisioning_owner;
GRANT SELECT ON roles TO nekkora_provisioning_owner;                              -- resolver ORG_ADMIN (nao listado na matriz; necessario)

-- nekkora_retention_owner / nekkora_retention_executor: sem GRANT de tabela novo (011 ja cobre).
-- nekkora_scheduler / nekkora_provisioning / nekkora_retention_executor: nenhum GRANT de tabela.

-- === 13. RLS por organizacao ===
--
-- Tenant direto: toda tabela de public com organization_id (39). Duas politicas permissivas, por
-- grupo de papel - nunca se somam para o mesmo papel:
--   tenant_request_context  - nekkora_platform, nekkora_ingestion: nekkora.organization_id
--   tenant_function_context - proprietarios de funcao: nekkora.fn_organization_id
-- Derivadas (sem organization_id, ligadas por user_id): user_credentials, password_reset_tokens,
-- email_verification_tokens - so nekkora_platform, via a organizacao do usuario (users sob RLS).
-- Catalogo global sem RLS: permissions, roles, role_permissions.
-- Sem contexto (GUC ausente ou vazio): a comparacao com NULL nunca e verdadeira -> nenhuma linha
-- visivel, toda escrita rejeitada por WITH CHECK. UUID malformado: erro de conversao.
-- FORCE: aplica as politicas tambem ao proprietario nao-superusuario (nekkora_retention_tables_owner).

DO $$
DECLARE
    t RECORD;
    n_tenant INTEGER := 0;
    ctx_req CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.organization_id'', true), '''')::uuid';
    ctx_fn  CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.fn_organization_id'', true), '''')::uuid';
BEGIN
    FOR t IN
        SELECT c.relname
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relkind = 'r'
          AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'organization_id' AND NOT a.attisdropped)
        ORDER BY c.relname
    LOOP
        n_tenant := n_tenant + 1;
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t.relname);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t.relname);
        EXECUTE format('CREATE POLICY tenant_request_context ON public.%I AS PERMISSIVE FOR ALL TO nekkora_platform, nekkora_ingestion USING (%s) WITH CHECK (%s)',
                       t.relname, ctx_req, ctx_req);
        EXECUTE format('CREATE POLICY tenant_function_context ON public.%I AS PERMISSIVE FOR ALL TO nekkora_scheduler_owner, nekkora_provisioning_owner, nekkora_retention_owner USING (%s) WITH CHECK (%s)',
                       t.relname, ctx_fn, ctx_fn);
    END LOOP;

    IF n_tenant <> 39 THEN
        RAISE EXCEPTION 'inventario RLS divergente: esperadas 39 tabelas com organization_id, encontradas %', n_tenant;
    END IF;

    FOR t IN SELECT unnest(ARRAY['user_credentials', 'password_reset_tokens', 'email_verification_tokens']) AS relname LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t.relname);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t.relname);
        EXECUTE format('CREATE POLICY tenant_request_context ON public.%I AS PERMISSIVE FOR ALL TO nekkora_platform '
                       'USING (EXISTS (SELECT 1 FROM public.users u WHERE u.user_id = %I.user_id AND u.%s)) '
                       'WITH CHECK (EXISTS (SELECT 1 FROM public.users u WHERE u.user_id = %I.user_id AND u.%s))',
                       t.relname, t.relname, ctx_req, t.relname, ctx_req);
    END LOOP;
END;
$$;

-- === 14. Retirada do papel substituido (matriz, secao 11) ===
--
-- nekkora_app (011) e substituido por nekkora_ingestion. Nao e dono de nada; DROP OWNED remove os
-- privilegios dele neste banco (tabelas e colunas da 011) e DROP ROLE o elimina.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_app') THEN
        EXECUTE 'DROP OWNED BY nekkora_app';
        EXECUTE 'DROP ROLE nekkora_app';
    END IF;
END;
$$;

-- === 15. Invariantes verificados ao final da migration ===

DO $$
DECLARE
    v_bad TEXT;
BEGIN
    -- nenhum papel nekkora_* (exceto o de migration) e super, bypassrls, createrole ou createdb
    SELECT string_agg(rolname, ', ') INTO v_bad FROM pg_roles
    WHERE rolname LIKE 'nekkora\_%' AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel de servico com atributo privilegiado: %', v_bad; END IF;

    -- nenhum papel LOGIN de servico e dono de tabela ou funcao
    SELECT string_agg(DISTINCT pg_get_userbyid(o.owner), ', ') INTO v_bad FROM (
        SELECT relowner AS owner FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
        UNION ALL
        SELECT proowner FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public'
    ) o JOIN pg_roles r ON r.oid = o.owner
    WHERE r.rolname LIKE 'nekkora\_%' AND r.rolcanlogin;
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel LOGIN dono de objeto: %', v_bad; END IF;

    -- nenhuma membership entre papeis nekkora_*
    IF EXISTS (SELECT 1 FROM pg_auth_members am JOIN pg_roles m ON m.oid = am.member WHERE m.rolname LIKE 'nekkora\_%') THEN
        RAISE EXCEPTION 'membership inesperada envolvendo papel nekkora_*';
    END IF;

    -- toda funcao SECURITY DEFINER tem search_path fixo e nenhuma concede EXECUTE a PUBLIC
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.prosecdef
      AND (p.proconfig IS NULL OR NOT EXISTS (SELECT 1 FROM unnest(p.proconfig) c WHERE c LIKE 'search_path=%')
           OR has_function_privilege('public', p.oid, 'EXECUTE'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'SECURITY DEFINER sem search_path fixo ou com EXECUTE publico: %', v_bad; END IF;

    -- CREATE em public continua fora do alcance de PUBLIC
    IF has_schema_privilege('public', 'public', 'CREATE') THEN
        RAISE EXCEPTION 'CREATE em public concedido a PUBLIC';
    END IF;

    -- papel substituido removido
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_app') THEN
        RAISE EXCEPTION 'nekkora_app ainda existe';
    END IF;
END;
$$;

COMMIT;
