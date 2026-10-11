-- Semeadura SINTETICA do ensaio-frontend (F1.5, P-A) - papel: admin_cluster (o administrador do cluster DO ENSAIO, por
-- socket local, como a fixture da 016; nao e janela de elevacao do migrador). Recebe do orquestrador
-- (lib/semear_frontend.py) a variavel psql plano_b64 = plano JSON em base64 (ids, contas com PHC Argon2id, estrutura do
-- produto, execucoes e contagens esperadas). Nenhum dado real; nenhuma senha em claro chega aqui.
--
-- UMA transacao (BEGIN ... COMMIT explicitos, psql -v ON_ERROR_STOP=1):
--   * recusa logo no inicio se ja existir organizacao da semeadura (pelo nome ou pelos ids do plano): sem duplicacao;
--   * grava organizacoes, limites (010), clientes, usuarios, credenciais, vinculos, papeis (primeiro ORG_ADMIN SO por
--     provision_first_org_admin, da 012), concessoes do portal, workspaces, automacoes e versoes (numero pela trigger da
--     005), execucoes pelas regras da 006 (nascem PENDING, transicoes validas, reserva com Worker/Agent/autorizacoes
--     ativos), eventos validos para o tipo (007), uma execucao AGENDADA pelo fluxo real da 009 e os estados finais;
--   * confere as contagens esperadas do plano ANTES do COMMIT: qualquer diferenca = excecao = ROLLBACK (banco sem
--     semeadura parcial);
--   * depois do COMMIT, imprime SEMEADURA_FRONTEND_APLICADA <org A> e linhas CONFERE (so leitura).
-- Funcoes auxiliares em pg_temp (somem com a sessao; nada persiste fora das tabelas do produto).
\set ON_ERROR_STOP on
BEGIN;
SELECT set_config('nekkora_semeadura.plano', convert_from(decode(:'plano_b64', 'base64'), 'UTF8'), true) IS NOT NULL AS plano_ok \gset

CREATE FUNCTION pg_temp.plano() RETURNS jsonb LANGUAGE sql STABLE AS $$ SELECT current_setting('nekkora_semeadura.plano')::jsonb $$;
CREATE FUNCTION pg_temp.org(chave text) RETURNS uuid LANGUAGE sql STABLE AS $$
    SELECT (o->>'id')::uuid FROM jsonb_array_elements(pg_temp.plano()->'orgs') o WHERE o->>'chave' = chave $$;
CREATE FUNCTION pg_temp.conta(chave text) RETURNS jsonb LANGUAGE sql STABLE AS $$
    SELECT c FROM jsonb_array_elements(pg_temp.plano()->'contas') c WHERE c->>'chave' = chave $$;
CREATE FUNCTION pg_temp.ws(chave text) RETURNS jsonb LANGUAGE sql STABLE AS $$
    SELECT w FROM jsonb_array_elements(pg_temp.plano()->'workspaces') w WHERE w->>'chave' = chave $$;
CREATE FUNCTION pg_temp.cliente(chave text) RETURNS uuid LANGUAGE sql STABLE AS $$
    SELECT (c->>'id')::uuid FROM jsonb_array_elements(pg_temp.plano()->'clientes') c WHERE c->>'chave' = chave $$;
CREATE FUNCTION pg_temp.auto(chave text) RETURNS jsonb LANGUAGE sql STABLE AS $$
    SELECT a FROM jsonb_array_elements(pg_temp.plano()->'automacoes') a WHERE a->>'chave' = chave $$;
-- membership do primeiro ORG_ADMIN da organizacao (responsavel pelas atribuicoes e aprovacoes da massa)
CREATE FUNCTION pg_temp.responsavel(org_chave text) RETURNS jsonb LANGUAGE sql STABLE AS $$
    SELECT c FROM jsonb_array_elements(pg_temp.plano()->'contas') c
     WHERE c->>'org' = org_chave AND (c->>'primeiro_admin')::boolean $$;
CREATE FUNCTION pg_temp.estado_recurso(e text) RETURNS smallint LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE e WHEN 'ativo' THEN 1 WHEN 'ativa' THEN 1 WHEN 'suspenso' THEN 2 WHEN 'suspensa' THEN 2
                  WHEN 'arquivado' THEN 3 WHEN 'arquivada' THEN 3 END::smallint $$;

-- Worker + Agent + alocacao (+ autorizacao no workspace) de uma organizacao. Execucoes ATIVAS (STARTING/RUNNING) tem
-- runtime proprio (um Worker so tem uma execucao ativa); as terminais reusam o runtime compartilhado da organizacao,
-- uma de cada vez (o indice unico da 006 so vale para status 2 e 3).
CREATE FUNCTION pg_temp.runtime(p_org uuid, p_ws uuid, p_por uuid, p_nome text) RETURNS uuid[] LANGUAGE plpgsql AS $$
DECLARE w uuid := gen_random_uuid(); a uuid := gen_random_uuid(); wa uuid := gen_random_uuid(); ww uuid := gen_random_uuid();
BEGIN
    INSERT INTO workers (worker_id, organization_id, name, status) VALUES (w, p_org, 'Worker ' || p_nome, 1);
    INSERT INTO agents (agent_id, organization_id, name, agent_version, status) VALUES (a, p_org, 'Agent ' || p_nome, '1.0.0', 2);
    INSERT INTO worker_agent_assignments (assignment_id, organization_id, worker_id, agent_id, status, assigned_by_membership_id)
        VALUES (wa, p_org, w, a, 1, p_por);
    INSERT INTO worker_workspace_assignments (assignment_id, organization_id, worker_id, workspace_id, status, authorized_by_membership_id)
        VALUES (ww, p_org, w, p_ws, 1, p_por);
    RETURN ARRAY[w, a, wa, ww];
END $$;

CREATE FUNCTION pg_temp.autoriza_ws(p_org uuid, p_worker uuid, p_ws uuid, p_por uuid) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE ww uuid;
BEGIN
    SELECT assignment_id INTO ww FROM worker_workspace_assignments
     WHERE organization_id = p_org AND worker_id = p_worker AND workspace_id = p_ws AND status = 1;
    IF ww IS NULL THEN
        ww := gen_random_uuid();
        INSERT INTO worker_workspace_assignments (assignment_id, organization_id, worker_id, workspace_id, status, authorized_by_membership_id)
            VALUES (ww, p_org, p_worker, p_ws, 1, p_por);
    END IF;
    RETURN ww;
END $$;

CREATE FUNCTION pg_temp.evento(p_org uuid, p_ws uuid, p_exe uuid, p_agent uuid, p_seq int, p_tipo int, p_em timestamptz, p_payload jsonb)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    PERFORM set_config('nekkora.organization_id', p_org::text, true);
    INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, received_at,
                                  payload, fingerprint)
    VALUES (p_org, p_ws, p_exe, p_agent, p_seq, p_tipo, p_em, p_em + interval '200 milliseconds', p_payload,
            md5(p_exe::text || ':' || p_seq) || md5(random()::text));
END $$;

-- Ciclo de vida de uma execucao ja criada (PENDING) ate o estado pedido. t0 = created_at.
CREATE FUNCTION pg_temp.ciclo(p_org uuid, p_ws uuid, p_exe uuid, p_estado text, p_atraso int, p_dur int, p_por uuid,
                              p_rt uuid[]) RETURNS void LANGUAGE plpgsql AS $$
DECLARE t0 timestamptz; r timestamptz; s timestamptz; f timestamptz; ww uuid;
BEGIN
    SELECT created_at INTO t0 FROM executions WHERE execution_id = p_exe;
    r := t0 + make_interval(secs => p_atraso);
    s := r + interval '1 second';
    f := s + make_interval(secs => p_dur);
    IF p_estado = 'pendente' THEN RETURN; END IF;
    IF p_estado = 'cancelada_antes' THEN
        UPDATE executions SET status = 6, status_reason = 4, cancel_requested_at = r, cancel_requested_by_membership_id = p_por,
               finished_at = r + interval '1 second' WHERE execution_id = p_exe;
        RETURN;
    END IF;
    IF p_estado = 'prazo_fila' THEN
        UPDATE executions SET status = 6, status_reason = 6, finished_at = r WHERE execution_id = p_exe;
        RETURN;
    END IF;
    ww := pg_temp.autoriza_ws(p_org, p_rt[1], p_ws, p_por);
    UPDATE executions SET status = 2, worker_id = p_rt[1], worker_agent_assignment_id = p_rt[3], worker_workspace_assignment_id = ww,
           reserved_at = r WHERE execution_id = p_exe;
    IF p_estado = 'iniciando' THEN RETURN; END IF;
    IF p_estado = 'falha_inicio' THEN
        f := r + make_interval(secs => p_dur);
        UPDATE executions SET status = 5, status_reason = 2, finished_at = f, error_summary = 'Falha ao iniciar (sintética)'
         WHERE execution_id = p_exe;
        PERFORM pg_temp.evento(p_org, p_ws, p_exe, p_rt[2], 1, 4, f, '{"schema_version":1,"error_code":"ARTEFATO_INDISPONIVEL"}');
        RETURN;
    END IF;
    UPDATE executions SET status = 3, dispatched_at = r, started_at = s, pid = 4242 WHERE execution_id = p_exe;
    PERFORM pg_temp.evento(p_org, p_ws, p_exe, p_rt[2], 1, 1, s, '{"schema_version":1,"pid":4242}');
    IF p_estado = 'executando' THEN RETURN; END IF;
    IF p_estado = 'concluida' THEN
        UPDATE executions SET status = 4, status_reason = 1, exit_code = 0, finished_at = f WHERE execution_id = p_exe;
        PERFORM pg_temp.evento(p_org, p_ws, p_exe, p_rt[2], 2, 2, f, '{"schema_version":1,"exit_code":0}');
    ELSIF p_estado = 'falha_processo' THEN
        UPDATE executions SET status = 5, status_reason = 3, exit_code = 1, finished_at = f, error_summary = 'Erro sintético do processo'
         WHERE execution_id = p_exe;
        PERFORM pg_temp.evento(p_org, p_ws, p_exe, p_rt[2], 2, 3, f, '{"schema_version":1,"exit_code":1,"error_summary":"erro sintetico"}');
    ELSIF p_estado = 'cancelada_confirmada' THEN
        UPDATE executions SET cancel_requested_at = f - interval '2 seconds', cancel_requested_by_membership_id = p_por
         WHERE execution_id = p_exe;
        UPDATE executions SET status = 6, status_reason = 5, exit_code = 143, finished_at = f WHERE execution_id = p_exe;
        PERFORM pg_temp.evento(p_org, p_ws, p_exe, p_rt[2], 2, 5, f, '{"schema_version":1,"process_started":true,"exit_code":143}');
    ELSE
        RAISE EXCEPTION 'estado de execucao desconhecido no plano: %', p_estado;
    END IF;
END $$;

DO $$
DECLARE
    p jsonb := pg_temp.plano();
    c jsonb; w jsonb; a jsonb; e jsonb; g jsonb; x jsonb;
    org_id uuid; resp jsonb; resp_m uuid; ws_id uuid; auto_id uuid; ver_id uuid; exe_id uuid; n int; i int;
    rt_comp jsonb := '{}'::jsonb; rt uuid[]; por uuid;
    sched uuid; occ uuid;
BEGIN
    -- recusa ANTES de qualquer escrita: semeadura ja existente (nome ou ids do plano)
    IF EXISTS (SELECT 1 FROM organizations WHERE name IN ('Nekkora Demo', 'Nekkora Demo B')
                  OR organization_id IN (SELECT (o->>'id')::uuid FROM jsonb_array_elements(p->'orgs') o)) THEN
        RAISE EXCEPTION 'SEMEADURA_RECUSADA: ja existe organizacao da semeadura (nao se repete; nada foi gravado)';
    END IF;

    -- organizacoes e clientes
    INSERT INTO organizations (organization_id, name, status)
        SELECT (o->>'id')::uuid, o->>'nome', 1 FROM jsonb_array_elements(p->'orgs') o;
    INSERT INTO clients (client_id, organization_id, name, status)
        SELECT (k->>'id')::uuid, pg_temp.org(k->>'org'), k->>'nome', 1 FROM jsonb_array_elements(p->'clientes') k;

    -- usuarios, credenciais (PHC do plano) e vinculos
    FOR c IN SELECT * FROM jsonb_array_elements(p->'contas') LOOP
        org_id := pg_temp.org(c->>'org');
        INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name)
            VALUES ((c->>'user_id')::uuid, org_id, CASE c->>'tipo' WHEN 'interna' THEN 1 ELSE 2 END, c->>'email', now(), 2, c->>'nome');
        INSERT INTO user_credentials (user_id, password_hash) VALUES ((c->>'user_id')::uuid, c->>'phc');
        IF c->>'tipo' = 'interna' THEN
            INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status)
                VALUES ((c->>'membership_id')::uuid, org_id, (c->>'user_id')::uuid, 1, 2);
        ELSE
            INSERT INTO client_memberships (client_membership_id, organization_id, client_id, user_id, account_type, status)
                VALUES ((c->>'membership_id')::uuid, org_id, pg_temp.cliente(c->'papeis'->>'cliente'), (c->>'user_id')::uuid, 2, 2);
        END IF;
    END LOOP;

    -- primeiro ORG_ADMIN de cada organizacao: SO pelo caminho autorizado da 012
    FOR c IN SELECT * FROM jsonb_array_elements(p->'contas') WHERE (value->>'primeiro_admin')::boolean LOOP
        PERFORM provision_first_org_admin(pg_temp.org(c->>'org'), (c->>'membership_id')::uuid, (c->>'user_id')::uuid,
                                          'semeadura-frontend', gen_random_uuid());
    END LOOP;

    -- limites da 010 ilimitados (sem eles a 006/010 recusa criar execucao)
    FOR c IN SELECT * FROM jsonb_array_elements(p->'contas') WHERE (value->>'primeiro_admin')::boolean LOOP
        FOR i IN 1..5 LOOP
            INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from,
                                                               limit_mode, limit_value, provisioned_by_user_id)
                VALUES (pg_temp.org(c->>'org'), i, 1, now(), 2, NULL, (c->>'user_id')::uuid);
        END LOOP;
    END LOOP;

    -- workspaces (todos ATIVOS durante a massa; estados finais no fim)
    FOR w IN SELECT * FROM jsonb_array_elements(p->'workspaces') LOOP
        INSERT INTO workspaces (workspace_id, organization_id, client_id, name, description, status)
            VALUES ((w->>'id')::uuid, pg_temp.org(w->>'org'), pg_temp.cliente(w->>'cliente'), w->>'nome', w->>'descricao', 1);
    END LOOP;

    -- papeis: organizacao (alem do primeiro admin) e workspace, atribuidos pelo responsavel da organizacao
    FOR c IN SELECT * FROM jsonb_array_elements(p->'contas') WHERE value->>'tipo' = 'interna' LOOP
        org_id := pg_temp.org(c->>'org');
        resp_m := (pg_temp.responsavel(c->>'org')->>'membership_id')::uuid;
        IF NOT (c->>'primeiro_admin')::boolean THEN
            FOR x IN SELECT * FROM jsonb_array_elements(coalesce(c->'papeis'->'org', '[]'::jsonb)) LOOP
                INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id)
                    SELECT org_id, (c->>'membership_id')::uuid, role_id, 1, 1, resp_m FROM roles WHERE code = x#>>'{}';
                GET DIAGNOSTICS n = ROW_COUNT;
                IF n <> 1 THEN RAISE EXCEPTION 'papel de organizacao desconhecido: %', x#>>'{}'; END IF;
            END LOOP;
        END IF;
        FOR x IN SELECT * FROM jsonb_array_elements(coalesce(c->'papeis'->'ws', '[]'::jsonb)) LOOP
            INSERT INTO workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status, assigned_by_membership_id)
                SELECT org_id, (pg_temp.ws(x->>0)->>'id')::uuid, (c->>'membership_id')::uuid, role_id, 2, 1, resp_m FROM roles WHERE code = x->>1;
            GET DIAGNOSTICS n = ROW_COUNT;
            IF n <> 1 THEN RAISE EXCEPTION 'papel de workspace desconhecido: %', x->>1; END IF;
        END LOOP;
    END LOOP;

    -- concessoes do portal (contas externas): ativa, pendente e uma revogada antiga
    FOR c IN SELECT * FROM jsonb_array_elements(p->'contas') WHERE value->>'tipo' = 'externa' LOOP
        org_id := pg_temp.org(c->>'org');
        resp_m := (pg_temp.responsavel(c->>'org')->>'membership_id')::uuid;
        FOR g IN SELECT * FROM jsonb_array_elements(c->'papeis'->'concessoes') LOOP
            ws_id := (pg_temp.ws(g->>0)->>'id')::uuid;
            IF g->>1 = 'ativa' THEN
                INSERT INTO client_workspace_grants (organization_id, client_id, client_membership_id, workspace_id, status,
                       approved_by_membership_id, approved_at, starts_at, next_review_at)
                VALUES (org_id, pg_temp.cliente(c->'papeis'->>'cliente'), (c->>'membership_id')::uuid, ws_id, 2, resp_m,
                        now() - interval '30 days', now() - interval '30 days', now() + interval '60 days');
            ELSIF g->>1 = 'pendente' THEN
                INSERT INTO client_workspace_grants (organization_id, client_id, client_membership_id, workspace_id, status, starts_at)
                VALUES (org_id, pg_temp.cliente(c->'papeis'->>'cliente'), (c->>'membership_id')::uuid, ws_id, 1, now());
            ELSIF g->>1 = 'revogada' THEN
                INSERT INTO client_workspace_grants (organization_id, client_id, client_membership_id, workspace_id, status,
                       approved_by_membership_id, approved_at, starts_at, created_at, revoked_at, revoked_by_membership_id)
                VALUES (org_id, pg_temp.cliente(c->'papeis'->>'cliente'), (c->>'membership_id')::uuid, ws_id, 4, resp_m,
                        now() - interval '90 days', now() - interval '90 days', now() - interval '90 days', now() - interval '45 days', resp_m);
            ELSE
                RAISE EXCEPTION 'estado de concessao desconhecido: %', g->>1;
            END IF;
        END LOOP;
    END LOOP;

    -- automacoes e versoes (numero pela trigger da 005; publicadas na ordem)
    FOR a IN SELECT * FROM jsonb_array_elements(p->'automacoes') LOOP
        w := pg_temp.ws(a->>'workspace');
        org_id := pg_temp.org(w->>'org');
        resp_m := (pg_temp.responsavel(w->>'org')->>'membership_id')::uuid;
        INSERT INTO automations (automation_id, organization_id, workspace_id, name, description, status, created_by_membership_id)
            VALUES ((a->>'id')::uuid, org_id, (w->>'id')::uuid, a->>'nome', a->>'descricao', 1, resp_m);
        FOR x IN SELECT * FROM jsonb_array_elements(a->'versoes') LOOP
            ver_id := gen_random_uuid();
            INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json,
                                             created_by_membership_id)
            VALUES (ver_id, org_id, (w->>'id')::uuid, (a->>'id')::uuid, 1,
                    jsonb_build_object('contract_version', 1, 'artifact', jsonb_build_object('reference', 'v1', 'sha256', repeat('a', 64)),
                                       'runtime', 'python3.11', 'entrypoint', 'main.py', 'arguments', '[]'::jsonb,
                                       'parameters', '{}'::jsonb, 'secret_bindings', '{}'::jsonb), resp_m);
            IF (x#>>'{}')::boolean THEN
                UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = resp_m
                 WHERE automation_version_id = ver_id;
            END IF;
        END LOOP;
    END LOOP;

    -- execucoes do plano (ordem do plano: a falha vem antes da reexecucao que a referencia)
    FOR e IN SELECT * FROM jsonb_array_elements(p->'execucoes') LOOP
        a := pg_temp.auto(e->>'automacao');
        w := pg_temp.ws(a->>'workspace');
        org_id := pg_temp.org(w->>'org');
        resp_m := (pg_temp.responsavel(w->>'org')->>'membership_id')::uuid;
        por := (pg_temp.conta(e->>'solicitante')->>'membership_id')::uuid;
        IF por IS NULL THEN RAISE EXCEPTION 'solicitante desconhecido: %', e->>'solicitante'; END IF;
        SELECT automation_version_id INTO ver_id FROM automation_versions
         WHERE automation_id = (a->>'id')::uuid AND version_number = (e->>'versao')::int AND status = 2;
        IF ver_id IS NULL THEN RAISE EXCEPTION 'versao % nao publicada em %', e->>'versao', a->>'chave'; END IF;
        exe_id := (e->>'id')::uuid;
        INSERT INTO executions (execution_id, organization_id, workspace_id, automation_id, automation_version_id, request_key,
                                request_fingerprint, requested_by_membership_id, retry_of_execution_id, command_snapshot, status, origin, created_at)
        SELECT exe_id, org_id, (w->>'id')::uuid, (a->>'id')::uuid, v.automation_version_id, 'semeadura-' || exe_id, repeat('b', 64), por,
               (e->>'retry_de')::uuid, v.command_json, 1, 1, now() - make_interval(secs => (e->>'criada_s')::int)
          FROM automation_versions v WHERE v.automation_version_id = ver_id;
        IF e->>'estado' IN ('iniciando', 'executando') THEN
            rt := pg_temp.runtime(org_id, (w->>'id')::uuid, resp_m, 'ativo ' || left(exe_id::text, 8));
        ELSIF e->>'estado' IN ('pendente', 'cancelada_antes', 'prazo_fila') THEN
            rt := NULL;
        ELSE
            IF rt_comp ? (w->>'org') THEN
                rt := ARRAY(SELECT jsonb_array_elements_text(rt_comp->(w->>'org')))::uuid[];
            ELSE
                rt := pg_temp.runtime(org_id, (w->>'id')::uuid, resp_m, 'compartilhado ' || (w->>'org'));
                rt_comp := rt_comp || jsonb_build_object(w->>'org', to_jsonb(rt));
            END IF;
        END IF;
        PERFORM pg_temp.ciclo(org_id, (w->>'id')::uuid, exe_id, e->>'estado', (e->>'atraso_s')::int, (e->>'duracao_s')::int, por, rt);
    END LOOP;

    -- execucao AGENDADA pelo fluxo real da 009 (agenda -> revisao -> ocorrencia -> process_schedule_occurrence), concluida
    a := pg_temp.auto('conciliacao');
    w := pg_temp.ws(a->>'workspace');
    org_id := pg_temp.org(w->>'org');
    resp_m := (pg_temp.responsavel(w->>'org')->>'membership_id')::uuid;
    SELECT automation_version_id INTO ver_id FROM automation_versions WHERE automation_id = (a->>'id')::uuid AND status = 2
     ORDER BY version_number DESC LIMIT 1;
    sched := gen_random_uuid(); occ := gen_random_uuid();
    INSERT INTO automation_schedules (schedule_id, organization_id, workspace_id, automation_id, name, status, created_by_membership_id)
        VALUES (sched, org_id, (w->>'id')::uuid, (a->>'id')::uuid, 'Agenda diária (sintética)', 1, resp_m);
    INSERT INTO automation_schedule_revisions (organization_id, workspace_id, automation_id, schedule_id, revision_number, effective_from,
               automation_version_id, cron_expression, timezone, late_tolerance_seconds, authorized_by_membership_id, created_by_membership_id)
        VALUES (org_id, (w->>'id')::uuid, (a->>'id')::uuid, sched, 1, clock_timestamp(), ver_id, '0 6 * * *', 'America/Sao_Paulo', 3600, resp_m, resp_m);
    PERFORM pg_sleep(1.2);   -- scheduled_for (truncado ao segundo) >= effective_from da revisao
    INSERT INTO automation_schedule_occurrences (occurrence_id, organization_id, workspace_id, schedule_id, revision_id, scheduled_for, expires_at, status)
        VALUES (occ, org_id, (w->>'id')::uuid, sched, gen_random_uuid(), date_trunc('second', clock_timestamp()), clock_timestamp(), 1);
    PERFORM * FROM process_schedule_occurrence(occ, true);
    SELECT execution_id INTO exe_id FROM executions WHERE schedule_occurrence_id = occ;
    IF exe_id IS NULL THEN RAISE EXCEPTION 'fluxo da 009 nao criou a execucao agendada'; END IF;
    rt := ARRAY(SELECT jsonb_array_elements_text(rt_comp->(w->>'org')))::uuid[];
    PERFORM pg_temp.ciclo(org_id, (w->>'id')::uuid, exe_id, 'concluida', 1, 30, resp_m, rt);

    -- estados finais (depois das execucoes, que exigem recursos ativos)
    UPDATE workspaces x SET status = pg_temp.estado_recurso(w2->>'estado')
      FROM jsonb_array_elements(p->'workspaces') w2
     WHERE x.workspace_id = (w2->>'id')::uuid AND w2->>'estado' <> 'ativo';
    UPDATE automations x SET status = pg_temp.estado_recurso(a2->>'estado')
      FROM jsonb_array_elements(p->'automacoes') a2
     WHERE x.automation_id = (a2->>'id')::uuid AND a2->>'estado' <> 'ativa';

    -- CONFERENCIA antes do COMMIT: qualquer diferenca do esperado desfaz tudo
    FOR c IN SELECT to_jsonb(t) FROM jsonb_each(p->'esperado'->'por_org') t LOOP
        org_id := pg_temp.org(c->>'key');
        SELECT count(*) INTO n FROM users WHERE organization_id = org_id;
        IF n <> (c->'value'->>'usuarios')::int THEN RAISE EXCEPTION 'conferencia: % usuarios na org %, esperado %', n, c->>'key', c->'value'->>'usuarios'; END IF;
        SELECT count(*) INTO n FROM executions WHERE organization_id = org_id;
        IF n <> (c->'value'->>'execucoes')::int THEN RAISE EXCEPTION 'conferencia: % execucoes na org %, esperado %', n, c->>'key', c->'value'->>'execucoes'; END IF;
    END LOOP;
    FOR c IN SELECT to_jsonb(t) FROM jsonb_each(p->'esperado'->'estados') t LOOP
        SELECT count(*) INTO n FROM executions x
         WHERE x.organization_id IN (pg_temp.org('a'), pg_temp.org('b'))
           AND CASE c->>'key'
                 WHEN 'pendente' THEN x.status = 1 WHEN 'iniciando' THEN x.status = 2 WHEN 'executando' THEN x.status = 3
                 WHEN 'concluida' THEN x.status = 4 WHEN 'falha_inicio' THEN x.status = 5 AND x.status_reason = 2
                 WHEN 'falha_processo' THEN x.status = 5 AND x.status_reason = 3 WHEN 'cancelada_antes' THEN x.status = 6 AND x.status_reason = 4
                 WHEN 'cancelada_confirmada' THEN x.status = 6 AND x.status_reason = 5 WHEN 'prazo_fila' THEN x.status = 6 AND x.status_reason = 6
               END;
        IF n <> (c->'value'#>>'{}')::int THEN RAISE EXCEPTION 'conferencia: % execucoes em %, esperado %', n, c->>'key', c->'value'; END IF;
    END LOOP;
    SELECT count(*) INTO n FROM workspaces WHERE organization_id IN (pg_temp.org('a'), pg_temp.org('b'));
    IF n <> (p->'esperado'->>'workspaces')::int THEN RAISE EXCEPTION 'conferencia: % workspaces', n; END IF;
    SELECT count(*) INTO n FROM automation_versions WHERE organization_id IN (pg_temp.org('a'), pg_temp.org('b'));
    IF n <> (p->'esperado'->>'versoes')::int THEN RAISE EXCEPTION 'conferencia: % versoes', n; END IF;
    SELECT count(*) INTO n FROM executions WHERE organization_id = pg_temp.org('a') AND origin = 2;
    IF n <> 1 THEN RAISE EXCEPTION 'conferencia: % execucoes agendadas, esperado 1', n; END IF;
END $$;

COMMIT;
SELECT 'SEMEADURA_FRONTEND_APLICADA ' || organization_id FROM organizations WHERE name = 'Nekkora Demo';
SELECT 'CONFERE execucoes_org_a=' || count(*) FROM executions x JOIN organizations o USING (organization_id) WHERE o.name = 'Nekkora Demo';
SELECT 'CONFERE eventos=' || count(*) FROM execution_events x JOIN organizations o USING (organization_id) WHERE o.name IN ('Nekkora Demo', 'Nekkora Demo B');
SELECT 'CONFERE concessoes=' || string_agg(s::text, ',' ORDER BY s) FROM (SELECT g.status AS s FROM client_workspace_grants g
       JOIN organizations o USING (organization_id) WHERE o.name = 'Nekkora Demo') t;
