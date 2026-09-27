-- Nekkora Platform - portal externo (decisao 8.2, alternativa 2): identidade de banco propria para o
-- portal, sem privilegio de tabela ou view. O papel nekkora_portal so tem USAGE em public e EXECUTE em
-- quatro funcoes SECURITY DEFINER (owner nekkora_portal_owner, NOLOGIN, com SELECT por coluna). Cada
-- funcao recebe o TOKEN BRUTO da sessao, calcula o hash no banco e deriva conta, vinculo, cliente e
-- concessoes a partir das linhas encontradas; IDs recebidos do chamador so filtram (AND), nunca
-- escolhem escopo. Migration aditiva: nao altera objeto, politica, privilegio ou papel de 001-012.
--
-- CANDIDATA ate a aprovacao do relatorio de validacao da suite db/tests/portal-013.
--
-- Base: docs/relatorios/2026-09-25-desenho-portal-externo-8-2.md (revisao 2, D1-D7) e
-- 2026-09-25-preparacao-autorizacao-api-nekkora.md (secoes 11.4 e 11.5). Depende de 001-012.
--
-- Contextos:
--   nekkora.fn_organization_id - definido pela funcao publica a partir de p_organization_id (a
--       alegacao da rota), salvo e restaurado (retorno normal e EXCEPTION), padrao da 012. So escolhe
--       ONDE procurar a sessao; a sessao precisa existir naquela organizacao com aquele hash. Nenhum
--       GUC definido pelo chamador e lido como escopo.
--   nekkora.organization_id    - nunca lido nem escrito por estas funcoes.
--
-- Snapshot: a resolucao da sessao e a leitura sao comandos distintos em READ COMMITTED. O comando de
-- leitura reconfere conta, vinculo, organizacao, cliente, concessao e workspace chamando
-- portal_authorized_workspaces, que e STABLE: funcao STABLE usa o snapshot do comando que a chama, entao
-- a reconferencia e os dados sao avaliados no MESMO snapshot. A sessao nao e reconferida na leitura: a
-- linha fica travada (FOR UPDATE) desde a resolucao ate o fim da transacao.
--
-- Tamanho maximo de pagina (portal_list_executions): 100, PROVISORIO. A decisao final e da 8.7; o valor
-- provisorio foi fixado pelo responsavel em 2026-09-26 (o desenho nao documenta valor).
--
-- Atomica: uma transacao.

BEGIN;

-- === 1. Papeis ===

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_portal') THEN
        CREATE ROLE nekkora_portal LOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_portal_owner') THEN
        CREATE ROLE nekkora_portal_owner NOLOGIN;
    END IF;
END;
$$;

ALTER ROLE nekkora_portal       LOGIN   NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE nekkora_portal_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

-- === 2. Configuracoes de log do papel do portal (desenho, secao 4.5) ===
--
-- O token bruto chega como parametro de bind. log_parameter_max_length_on_error e alteravel pelo
-- proprio usuario e nao pode ser travado; por isso os parametros que decidem O QUE vai para o log do
-- servidor sao fixados aqui. Todos sao de superusuario: o papel nao consegue SET/RESET.
--   log_error_verbosity = terse       -> sem DETAIL/HINT/QUERY/CONTEXT (os valores de bind anexados
--                                         ao erro ficam no CONTEXT)
--   log_min_error_statement = panic   -> sem a linha STATEMENT do comando que falhou
--   log_parameter_max_length = 0      -> logs de comando/duracao sem valores de bind
--   log_statement = none, log_min_duration_statement = -1 -> nenhum log de comando para este papel
--   log_min_duration_sample = -1, log_transaction_sample_rate = 0 -> reforco (fora da lista do desenho):
--                                         fecha os dois outros caminhos de log de comando por amostragem
-- Exige que a identidade da migration possa definir parametros de superusuario (C4).

ALTER ROLE nekkora_portal SET log_error_verbosity = 'terse';
ALTER ROLE nekkora_portal SET log_min_error_statement = 'panic';
ALTER ROLE nekkora_portal SET log_parameter_max_length = 0;
ALTER ROLE nekkora_portal SET log_statement = 'none';
ALTER ROLE nekkora_portal SET log_min_duration_statement = -1;
ALTER ROLE nekkora_portal SET log_min_duration_sample = -1;
ALTER ROLE nekkora_portal SET log_transaction_sample_rate = 0;

-- === 3. Schema e privilegios de coluna do owner (desenho, secao 4.1) ===
--
-- USAGE explicito (hoje tambem chega por PUBLIC): so permite localizar objetos de public; cada acesso
-- continua exigindo o privilegio do proprio objeto. Nenhum CREATE.
-- O owner le so as colunas da cadeia sessao -> concessao e da allowlist do 11.5: payload, error_summary,
-- exit_code, command_snapshot, description, e-mail, nome de pessoa, logs etc. sao negados pelo banco.

GRANT USAGE ON SCHEMA public TO nekkora_portal, nekkora_portal_owner;

GRANT SELECT (session_id, organization_id, user_id, session_token_hash, created_at, expires_at, last_seen_at, revoked_at)
    ON public.user_sessions TO nekkora_portal_owner;
GRANT UPDATE (last_seen_at) ON public.user_sessions TO nekkora_portal_owner;   -- toque 8.5; tambem habilita FOR UPDATE
GRANT SELECT (user_id, organization_id, account_type, status, email_verified_at) ON public.users TO nekkora_portal_owner;
GRANT SELECT (organization_id, status) ON public.organizations TO nekkora_portal_owner;
GRANT SELECT (client_id, organization_id, status) ON public.clients TO nekkora_portal_owner;
GRANT SELECT (client_membership_id, organization_id, client_id, user_id, status) ON public.client_memberships TO nekkora_portal_owner;
GRANT SELECT (grant_id, organization_id, client_id, client_membership_id, workspace_id, status, approved_at,
              starts_at, expires_at, next_review_at, revoked_at)
    ON public.client_workspace_grants TO nekkora_portal_owner;
GRANT SELECT (workspace_id, organization_id, client_id, name, status) ON public.workspaces TO nekkora_portal_owner;
GRANT SELECT (automation_id, organization_id, workspace_id, name) ON public.automations TO nekkora_portal_owner;
GRANT SELECT (execution_id, organization_id, workspace_id, automation_id, created_at, started_at, finished_at, status, status_reason)
    ON public.executions TO nekkora_portal_owner;
GRANT SELECT (organization_id, workspace_id, execution_id, sequence, event_type, received_at)
    ON public.execution_events TO nekkora_portal_owner;

-- === 4. RLS do owner do portal (desenho, secao 4.2) ===
--
-- Politicas proprias, sem alterar as da 012. So limitam a organizacao alegada (fn_organization_id,
-- definido pela funcao); cliente, vinculo e concessao sao isolados pelas juncoes com a sessao.
-- SELECT ... FOR UPDATE e o toque em last_seen_at exigem politica de UPDATE em user_sessions; como o
-- nome de politica e unico por tabela, a de UPDATE chama-se portal_function_context_update.

DO $$
DECLARE
    t TEXT;
    ctx_fn CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.fn_organization_id'', true), '''')::uuid';
BEGIN
    FOREACH t IN ARRAY ARRAY['user_sessions', 'users', 'organizations', 'clients', 'client_memberships',
                             'client_workspace_grants', 'workspaces', 'automations', 'executions', 'execution_events'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                       WHERE n.nspname = 'public' AND c.relname = t AND c.relrowsecurity AND c.relforcerowsecurity) THEN
            RAISE EXCEPTION 'tabela % sem RLS ENABLE+FORCE (a 012 deveria ter aplicado)', t;
        END IF;
        EXECUTE format('CREATE POLICY portal_function_context ON public.%I AS PERMISSIVE FOR SELECT TO nekkora_portal_owner USING (%s)',
                       t, ctx_fn);
    END LOOP;
    EXECUTE format('CREATE POLICY portal_function_context_update ON public.user_sessions AS PERMISSIVE FOR UPDATE TO nekkora_portal_owner USING (%s) WITH CHECK (%s)',
                   ctx_fn, ctx_fn);
END;
$$;

-- === 5. Funcoes internas (sem EXECUTE para ninguem alem do owner) ===
--
-- SECURITY INVOKER: so rodam dentro das funcoes publicas, quando current_user ja e o owner. Chamadas
-- por qualquer outro papel (se algum dia recebesse EXECUTE) rodariam com os privilegios dele, sem
-- acesso as tabelas.

-- Resolve a sessao a partir do token bruto (desenho, secao 4.3, passos 1-8). Levanta NK401 (mensagem
-- fixa, sem DETAIL) em qualquer falha; nunca inclui token, hash ou ids em mensagem. Toca last_seen_at
-- (<= 1/min, secao 11.4) so depois de validar sessao, conta, organizacao, vinculo e cliente: sessao
-- valida = atividade, mesmo que o recurso pedido depois nao esteja autorizado.
CREATE FUNCTION public.portal_resolve_session(
    p_organization_id UUID, p_session_token TEXT,
    OUT o_user_id UUID, OUT o_client_membership_id UUID, OUT o_t TIMESTAMPTZ)
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_hash TEXT;
    v_session_id UUID;
    v_expires_at TIMESTAMPTZ;
    v_last_seen_at TIMESTAMPTZ;
    v_revoked_at TIMESTAMPTZ;
BEGIN
    -- contrato D2: 32 bytes aleatorios em base64url sem padding = 43 caracteres; com 32 bytes, o
    -- ultimo caractere carrega 4 bits e 2 de preenchimento zero (so 16 valores possiveis). O formato
    -- e validado ANTES do hash, e o token nunca e convertido para um tipo cujo erro ecoe o valor.
    IF p_organization_id IS NULL OR p_session_token IS NULL
       OR pg_catalog.length(p_session_token) <> 43
       OR p_session_token OPERATOR(pg_catalog.!~) '^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$' THEN
        RAISE EXCEPTION USING ERRCODE = 'NK401', MESSAGE = 'sessao do portal invalida';
    END IF;
    v_hash := pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(p_session_token, 'UTF8')), 'hex');

    SELECT s.session_id, s.user_id, s.expires_at, s.last_seen_at, s.revoked_at
      INTO v_session_id, o_user_id, v_expires_at, v_last_seen_at, v_revoked_at
      FROM public.user_sessions s
     WHERE s.organization_id = p_organization_id
       AND s.session_token_hash = v_hash
       FOR UPDATE;

    o_t := pg_catalog.clock_timestamp();   -- depois do lock da sessao (secao 11.4)

    IF v_session_id IS NULL
       OR v_revoked_at IS NOT NULL
       OR v_expires_at <= o_t                                   -- prazo absoluto (12 h, gravado no login)
       OR v_last_seen_at <= o_t - interval '15 minutes' THEN    -- inatividade do portal (D7)
        RAISE EXCEPTION USING ERRCODE = 'NK401', MESSAGE = 'sessao do portal invalida';
    END IF;

    SELECT m.client_membership_id
      INTO o_client_membership_id
      FROM public.users u
      JOIN public.organizations o ON o.organization_id = u.organization_id
      JOIN public.client_memberships m ON m.user_id = u.user_id AND m.organization_id = u.organization_id
      JOIN public.clients c ON c.client_id = m.client_id AND c.organization_id = m.organization_id
     WHERE u.user_id = o_user_id
       AND u.organization_id = p_organization_id
       AND u.account_type = 2 AND u.status = 2 AND u.email_verified_at IS NOT NULL
       AND o.status = 1
       AND m.status = 2
       AND c.status = 1;

    IF o_client_membership_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = 'NK401', MESSAGE = 'sessao do portal invalida';
    END IF;

    -- toque condicional na linha ja travada: nao mexe em expires_at, nao retrocede, nao revive
    UPDATE public.user_sessions
       SET last_seen_at = o_t
     WHERE session_id = v_session_id
       AND organization_id = p_organization_id
       AND last_seen_at <= o_t - interval '1 minute';
END;
$$;

-- Workspaces que o vinculo derivado pode ler no instante p_t. STABLE: avaliada no snapshot do comando
-- de leitura que a chama. Reconfere conta, organizacao, vinculo, cliente, concessao do PROPRIO vinculo
-- e workspace ativo do cliente (desenho, secao 4.3; D5).
CREATE FUNCTION public.portal_authorized_workspaces(
    p_organization_id UUID, p_user_id UUID, p_client_membership_id UUID, p_t TIMESTAMPTZ)
RETURNS SETOF UUID
LANGUAGE sql
STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT g.workspace_id
      FROM public.users u
      JOIN public.organizations o ON o.organization_id = u.organization_id
      JOIN public.client_memberships m ON m.user_id = u.user_id AND m.organization_id = u.organization_id
      JOIN public.clients c ON c.client_id = m.client_id AND c.organization_id = m.organization_id
      JOIN public.client_workspace_grants g
        ON g.client_membership_id = m.client_membership_id
       AND g.client_id = c.client_id
       AND g.organization_id = m.organization_id
      JOIN public.workspaces w
        ON w.workspace_id = g.workspace_id
       AND w.client_id = c.client_id
       AND w.organization_id = g.organization_id
     WHERE u.user_id = p_user_id
       AND u.organization_id = p_organization_id
       AND u.account_type = 2 AND u.status = 2 AND u.email_verified_at IS NOT NULL
       AND o.status = 1
       AND m.client_membership_id = p_client_membership_id AND m.status = 2
       AND c.status = 1
       AND g.status = 2 AND g.approved_at IS NOT NULL AND g.starts_at <= p_t
       AND (g.expires_at IS NULL OR g.expires_at > p_t)
       AND g.next_review_at > p_t
       AND w.status = 1
$$;

-- === 6. Funcoes publicas do portal (EXECUTE so para nekkora_portal) ===
--
-- Padrao: salvar fn_organization_id, definir a partir de p_organization_id, resolver a sessao, ler com
-- a reconferencia no mesmo comando, restaurar (retorno normal e EXCEPTION).
-- Devolvem os CODIGOS de status, status_reason e event_type; a API mapeia para os rotulos do 11.5.
-- Erros: NK401 (sessao invalida, mensagem fixa); 22023 (parametro invalido, mensagem fixa); recurso
-- fora do escopo ou inexistente = 0 linhas.

CREATE FUNCTION public.portal_list_workspaces(p_organization_id UUID, p_session_token TEXT)
RETURNS TABLE (workspace_id UUID, name TEXT)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
#variable_conflict use_column
DECLARE
    v_prev_fn_org TEXT;
    s RECORD;
BEGIN
    v_prev_fn_org := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(p_organization_id::text, ''), true);
    BEGIN
        SELECT r.o_user_id, r.o_client_membership_id, r.o_t INTO s
          FROM public.portal_resolve_session(p_organization_id, p_session_token) r;

        RETURN QUERY
        SELECT w.workspace_id, w.name
          FROM public.workspaces w
         WHERE w.organization_id = p_organization_id
           AND w.workspace_id IN (SELECT a FROM public.portal_authorized_workspaces(p_organization_id, s.o_user_id, s.o_client_membership_id, s.o_t) a)
         ORDER BY w.name, w.workspace_id;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
END;
$$;

CREATE FUNCTION public.portal_list_executions(
    p_organization_id UUID, p_session_token TEXT,
    p_workspace_id UUID, p_status SMALLINT[], p_from TIMESTAMPTZ, p_to TIMESTAMPTZ,
    p_after_created_at TIMESTAMPTZ, p_after_execution_id UUID, p_limit INTEGER)
RETURNS TABLE (execution_id UUID, workspace_id UUID, automation_id UUID, automation_name TEXT,
               created_at TIMESTAMPTZ, started_at TIMESTAMPTZ, finished_at TIMESTAMPTZ, duration INTERVAL,
               status SMALLINT, status_reason SMALLINT)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
#variable_conflict use_column
DECLARE
    -- tamanho maximo de pagina: PROVISORIO ate a decisao 8.7
    c_max_page CONSTANT INTEGER := 100;
    v_prev_fn_org TEXT;
    s RECORD;
BEGIN
    v_prev_fn_org := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(p_organization_id::text, ''), true);
    BEGIN
        SELECT r.o_user_id, r.o_client_membership_id, r.o_t INTO s
          FROM public.portal_resolve_session(p_organization_id, p_session_token) r;

        IF p_limit IS NULL OR p_limit < 1 OR p_limit > c_max_page
           OR (p_after_created_at IS NULL) <> (p_after_execution_id IS NULL) THEN
            RAISE EXCEPTION USING ERRCODE = '22023', MESSAGE = 'parametro de consulta do portal invalido';
        END IF;

        -- keyset (created_at DESC, execution_id DESC), sem OFFSET; filtros e cursor so restringem
        RETURN QUERY
        SELECT e.execution_id, e.workspace_id, e.automation_id, a.name,
               e.created_at, e.started_at, e.finished_at,
               CASE WHEN e.started_at IS NOT NULL AND e.finished_at IS NOT NULL THEN e.finished_at - e.started_at END,
               e.status, e.status_reason
          FROM public.executions e
          JOIN public.automations a
            ON a.automation_id = e.automation_id AND a.workspace_id = e.workspace_id AND a.organization_id = e.organization_id
         WHERE e.organization_id = p_organization_id
           AND e.workspace_id IN (SELECT w FROM public.portal_authorized_workspaces(p_organization_id, s.o_user_id, s.o_client_membership_id, s.o_t) w)
           AND (p_workspace_id IS NULL OR e.workspace_id = p_workspace_id)
           AND (p_status IS NULL OR e.status = ANY (p_status))
           AND (p_from IS NULL OR e.created_at >= p_from)
           AND (p_to IS NULL OR e.created_at < p_to)
           AND (p_after_created_at IS NULL OR (e.created_at, e.execution_id) < (p_after_created_at, p_after_execution_id))
         ORDER BY e.created_at DESC, e.execution_id DESC
         LIMIT p_limit;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
END;
$$;

CREATE FUNCTION public.portal_get_execution(p_organization_id UUID, p_session_token TEXT, p_execution_id UUID)
RETURNS TABLE (execution_id UUID, workspace_id UUID, automation_id UUID, automation_name TEXT,
               created_at TIMESTAMPTZ, started_at TIMESTAMPTZ, finished_at TIMESTAMPTZ, duration INTERVAL,
               status SMALLINT, status_reason SMALLINT)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
#variable_conflict use_column
DECLARE
    v_prev_fn_org TEXT;
    s RECORD;
BEGIN
    v_prev_fn_org := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(p_organization_id::text, ''), true);
    BEGIN
        SELECT r.o_user_id, r.o_client_membership_id, r.o_t INTO s
          FROM public.portal_resolve_session(p_organization_id, p_session_token) r;

        RETURN QUERY
        SELECT e.execution_id, e.workspace_id, e.automation_id, a.name,
               e.created_at, e.started_at, e.finished_at,
               CASE WHEN e.started_at IS NOT NULL AND e.finished_at IS NOT NULL THEN e.finished_at - e.started_at END,
               e.status, e.status_reason
          FROM public.executions e
          JOIN public.automations a
            ON a.automation_id = e.automation_id AND a.workspace_id = e.workspace_id AND a.organization_id = e.organization_id
         WHERE e.organization_id = p_organization_id
           AND e.execution_id = p_execution_id
           AND e.workspace_id IN (SELECT w FROM public.portal_authorized_workspaces(p_organization_id, s.o_user_id, s.o_client_membership_id, s.o_t) w);
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
END;
$$;

CREATE FUNCTION public.portal_list_execution_events(p_organization_id UUID, p_session_token TEXT, p_execution_id UUID)
RETURNS TABLE (event_type SMALLINT, received_at TIMESTAMPTZ)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
#variable_conflict use_column
DECLARE
    v_prev_fn_org TEXT;
    s RECORD;
BEGIN
    v_prev_fn_org := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(p_organization_id::text, ''), true);
    BEGIN
        SELECT r.o_user_id, r.o_client_membership_id, r.o_t INTO s
          FROM public.portal_resolve_session(p_organization_id, p_session_token) r;

        -- o predicado de concessao vale para a EXECUCAO dona do evento
        RETURN QUERY
        SELECT ev.event_type, ev.received_at
          FROM public.execution_events ev
          JOIN public.executions e
            ON e.execution_id = ev.execution_id AND e.workspace_id = ev.workspace_id AND e.organization_id = ev.organization_id
         WHERE ev.organization_id = p_organization_id
           AND ev.execution_id = p_execution_id
           AND e.workspace_id IN (SELECT w FROM public.portal_authorized_workspaces(p_organization_id, s.o_user_id, s.o_client_membership_id, s.o_t) w)
         ORDER BY ev.sequence;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_fn_org, ''), true);
END;
$$;

-- === 7. Proprietario e EXECUTE ===

ALTER FUNCTION public.portal_resolve_session(UUID, TEXT) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_authorized_workspaces(UUID, UUID, UUID, TIMESTAMPTZ) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_list_workspaces(UUID, TEXT) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_list_executions(UUID, TEXT, UUID, SMALLINT[], TIMESTAMPTZ, TIMESTAMPTZ, TIMESTAMPTZ, UUID, INTEGER) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_get_execution(UUID, TEXT, UUID) OWNER TO nekkora_portal_owner;
ALTER FUNCTION public.portal_list_execution_events(UUID, TEXT, UUID) OWNER TO nekkora_portal_owner;

REVOKE ALL ON FUNCTION public.portal_resolve_session(UUID, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.portal_authorized_workspaces(UUID, UUID, UUID, TIMESTAMPTZ) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.portal_list_workspaces(UUID, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.portal_list_executions(UUID, TEXT, UUID, SMALLINT[], TIMESTAMPTZ, TIMESTAMPTZ, TIMESTAMPTZ, UUID, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.portal_get_execution(UUID, TEXT, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.portal_list_execution_events(UUID, TEXT, UUID) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.portal_list_workspaces(UUID, TEXT) TO nekkora_portal;
GRANT EXECUTE ON FUNCTION public.portal_list_executions(UUID, TEXT, UUID, SMALLINT[], TIMESTAMPTZ, TIMESTAMPTZ, TIMESTAMPTZ, UUID, INTEGER) TO nekkora_portal;
GRANT EXECUTE ON FUNCTION public.portal_get_execution(UUID, TEXT, UUID) TO nekkora_portal;
GRANT EXECUTE ON FUNCTION public.portal_list_execution_events(UUID, TEXT, UUID) TO nekkora_portal;

-- === 8. Invariantes verificados ao final (padrao da secao 15 da 012, estendido aos papeis novos) ===

DO $$
DECLARE
    v_bad TEXT;
    v_protegidos CONSTANT TEXT[] := ARRAY['nekkora_platform', 'nekkora_ingestion', 'nekkora_scheduler', 'nekkora_provisioning',
        'nekkora_retention_executor', 'nekkora_scheduler_owner', 'nekkora_provisioning_owner', 'nekkora_retention_owner',
        'nekkora_retention_tables_owner', 'nekkora_portal', 'nekkora_portal_owner'];
    v_cfg TEXT[];
BEGIN
    -- atributos
    SELECT string_agg(rolname, ', ') INTO v_bad FROM pg_roles
    WHERE rolname = ANY (v_protegidos) AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel protegido com atributo privilegiado: %', v_bad; END IF;
    IF NOT (SELECT rolcanlogin FROM pg_roles WHERE rolname = 'nekkora_portal')
       OR (SELECT rolcanlogin FROM pg_roles WHERE rolname = 'nekkora_portal_owner') THEN
        RAISE EXCEPTION 'nekkora_portal deve ser LOGIN e nekkora_portal_owner NOLOGIN';
    END IF;

    -- nenhum papel LOGIN de servico e dono de tabela ou funcao
    SELECT string_agg(DISTINCT pg_get_userbyid(o.owner), ', ') INTO v_bad FROM (
        SELECT relowner AS owner FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
        UNION ALL
        SELECT proowner FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public'
    ) o JOIN pg_roles r ON r.oid = o.owner
    WHERE r.rolname LIKE 'nekkora\_%' AND r.rolcanlogin;
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel LOGIN dono de objeto: %', v_bad; END IF;

    -- membership em qualquer sentido
    SELECT string_agg(format('%s -> %s', r.rolname, m.rolname), ', ') INTO v_bad
    FROM pg_auth_members am JOIN pg_roles r ON r.oid = am.roleid JOIN pg_roles m ON m.oid = am.member
    WHERE r.rolname = ANY (v_protegidos) OR m.rolname = ANY (v_protegidos);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'membership envolvendo papel protegido (concedido -> membro): %', v_bad; END IF;

    -- CREATE efetivo
    SELECT string_agg(p, ', ') INTO v_bad FROM unnest(v_protegidos) AS p
    WHERE has_schema_privilege(p, 'public', 'CREATE') OR has_database_privilege(p, current_database(), 'CREATE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel protegido com CREATE efetivo em public ou no banco: %', v_bad; END IF;
    IF NOT has_schema_privilege('nekkora_portal', 'public', 'USAGE') OR NOT has_schema_privilege('nekkora_portal_owner', 'public', 'USAGE') THEN
        RAISE EXCEPTION 'papeis do portal sem USAGE em public';
    END IF;

    -- SECURITY DEFINER com search_path fixo e sem EXECUTE publico
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.prosecdef
      AND (p.proconfig IS NULL OR NOT EXISTS (SELECT 1 FROM unnest(p.proconfig) c WHERE c LIKE 'search_path=%')
           OR has_function_privilege('public', p.oid, 'EXECUTE'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'SECURITY DEFINER sem search_path fixo ou com EXECUTE publico: %', v_bad; END IF;

    -- o papel do portal nao tem nenhum privilegio de tabela/coluna/sequencia em public
    SELECT string_agg(c.relname, ', ') INTO v_bad
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind IN ('r', 'v', 'm', 'p', 'f')
      AND (has_table_privilege('nekkora_portal', c.oid, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
           OR has_any_column_privilege('nekkora_portal', c.oid, 'SELECT, INSERT, UPDATE, REFERENCES'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'nekkora_portal com privilegio de tabela/coluna: %', v_bad; END IF;
    SELECT string_agg(c.relname, ', ') INTO v_bad
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'
      -- CASE: a ordem de avaliacao do AND nao e garantida, e has_sequence_privilege falha em nao-sequencia
      AND CASE WHEN c.relkind = 'S' THEN has_sequence_privilege('nekkora_portal', c.oid, 'USAGE, SELECT, UPDATE') ELSE false END;
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'nekkora_portal com privilegio de sequencia: %', v_bad; END IF;

    -- o owner nao tem privilegio de tabela inteira (so por coluna), nem INSERT/DELETE/TRUNCATE
    SELECT string_agg(c.relname, ', ') INTO v_bad
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind IN ('r', 'v', 'm', 'p', 'f')
      AND has_table_privilege('nekkora_portal_owner', c.oid, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'nekkora_portal_owner com privilegio de tabela inteira: %', v_bad; END IF;

    -- entre as funcoes SECURITY DEFINER, o portal executa exatamente as quatro publicas
    SELECT string_agg(p.oid::regprocedure::text, ', ' ORDER BY p.oid::regprocedure::text) INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.prosecdef AND has_function_privilege('nekkora_portal', p.oid, 'EXECUTE');
    IF v_bad IS DISTINCT FROM 'portal_get_execution(uuid,text,uuid), portal_list_execution_events(uuid,text,uuid), '
        'portal_list_executions(uuid,text,uuid,smallint[],timestamp with time zone,timestamp with time zone,timestamp with time zone,uuid,integer), '
        'portal_list_workspaces(uuid,text)' THEN
        RAISE EXCEPTION 'funcoes SECURITY DEFINER executaveis pelo portal divergem do esperado: %', v_bad;
    END IF;
    IF has_function_privilege('nekkora_portal', 'public.portal_resolve_session(uuid,text)', 'EXECUTE')
       OR has_function_privilege('nekkora_portal', 'public.portal_authorized_workspaces(uuid,uuid,uuid,timestamptz)', 'EXECUTE') THEN
        RAISE EXCEPTION 'funcao interna do portal executavel pelo nekkora_portal';
    END IF;

    -- configuracoes de log fixadas no papel
    SELECT s.setconfig INTO v_cfg FROM pg_db_role_setting s
    WHERE s.setrole = 'nekkora_portal'::regrole AND s.setdatabase = 0;
    IF v_cfg IS NULL OR NOT (v_cfg @> ARRAY['log_error_verbosity=terse', 'log_min_error_statement=panic', 'log_parameter_max_length=0',
                                           'log_statement=none', 'log_min_duration_statement=-1', 'log_min_duration_sample=-1',
                                           'log_transaction_sample_rate=0']) THEN
        RAISE EXCEPTION 'configuracoes de log do nekkora_portal ausentes ou divergentes: %', v_cfg;
    END IF;
END;
$$;

COMMIT;
