-- p01_popula - papel: nekkora (superusuario), DECLARADO: geracao de massa que simula uma base 001-012 em uso,
-- nao evidencia. Os triggers de 001-012 continuam valendo (superusuario so ignora RLS), entao toda linha passa
-- pelas mesmas regras de estado da aplicacao. Parametros (-v): n_exec (execucoes por organizacao), tok_pre
-- (hash SHA-256 do token de uma sessao ATIVA pre-014 da conta externa de referencia; o token nunca chega aqui).
--
-- Conta de referencia (verificada depois da atualizacao):
--   org      aaaaaaaa-0000-4000-8000-000000000001
--   externo  aaaaaaaa-0000-4000-8000-0000000000e1 (cliente aaaaaaaa-...-0000000000c1, concessao no workspace ...-0000000000a1)
\set ON_ERROR_STOP on
\pset pager off

CREATE FUNCTION pg_temp.phc() RETURNS TEXT LANGUAGE sql AS
$$ SELECT '$argon2id$v=19$m=65536,t=3,p=1$' || rtrim(encode(gen_random_bytes(16), 'base64'), '=') || '$' || rtrim(encode(gen_random_bytes(32), 'base64'), '=') $$;

-- psql nao interpola variaveis dentro de corpo com dolar: os parametros entram por GUC desta sessao
SELECT set_config('ensaio.n_exec', :'n_exec', false), set_config('ensaio.tok_pre', :'tok_pre', false) \gset

DO $popula$
DECLARE
    n_exec  INTEGER := current_setting('ensaio.n_exec')::int;
    o       UUID;
    adm     UUID;
    admm    UUID;
    u       UUID;
    cm      UUID;
    cli     UUID[];
    ws      UUID[];
    aut     UUID[];
    ver     UUID[];
    wwa     UUID[];
    wrk     UUID;
    agt     UUID;
    waa     UUID;
    e       UUID;
    k       INTEGER;
    j       INTEGER;
    w       INTEGER;
    destino INTEGER;
BEGIN
    FOR i IN 1..3 LOOP
        o := CASE i WHEN 1 THEN 'aaaaaaaa-0000-4000-8000-000000000001'::uuid ELSE gen_random_uuid() END;
        PERFORM set_config('nekkora.organization_id', o::text, true);
        INSERT INTO organizations (organization_id, name, status) VALUES (o, 'Organizacao ' || i, 1);

        adm := gen_random_uuid();
        admm := gen_random_uuid();
        INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name)
        VALUES (adm, o, 1, 'admin@org' || i || '.test', now() - interval '90 days', 2, 'Admin ' || i);
        INSERT INTO user_credentials (user_id, password_hash) VALUES (adm, pg_temp.phc());
        INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES (admm, o, adm, 1, 2);
        PERFORM provision_first_org_admin(o, admm, adm, 'ensaio-atualizacao', gen_random_uuid());

        FOR c IN 1..5 LOOP
            INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, limit_value,
                                                              provisioned_by_user_id)
            VALUES (o, c, 1, now() - interval '60 days', 2, NULL, adm);
        END LOOP;

        -- equipe interna
        FOR k IN 1..60 LOOP
            u := gen_random_uuid();
            INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name)
            VALUES (u, o, 1, 'pessoa' || k || '@org' || i || '.test', CASE WHEN k % 10 = 0 THEN NULL ELSE now() - interval '30 days' END,
                    CASE WHEN k % 17 = 0 THEN 3 ELSE 2 END, 'Pessoa ' || k);
            INSERT INTO user_credentials (user_id, password_hash) VALUES (u, pg_temp.phc());
            INSERT INTO organization_memberships (organization_id, user_id, account_type, status) VALUES (o, u, 1, 2);
            -- sessoes pre-014: ativas, revogadas e expiradas
            INSERT INTO user_sessions (organization_id, user_id, session_token_hash, created_at, expires_at, last_seen_at, revoked_at)
            VALUES (o, u, encode(gen_random_bytes(32), 'hex'), now() - interval '1 hour', now() + interval '11 hours', now() - interval '1 minute', NULL),
                   (o, u, encode(gen_random_bytes(32), 'hex'), now() - interval '3 hours', now() + interval '9 hours', now() - interval '2 hours', now() - interval '1 hour'),
                   (o, u, encode(gen_random_bytes(32), 'hex'), now() - interval '20 hours', now() - interval '8 hours', now() - interval '9 hours', NULL);
        END LOOP;

        -- clientes, workspaces (2 por cliente + 2 internos), automacoes publicadas
        cli := ARRAY[]::uuid[];
        ws := ARRAY[]::uuid[];
        FOR k IN 1..8 LOOP
            cli := cli || CASE WHEN i = 1 AND k = 1 THEN 'aaaaaaaa-0000-4000-8000-0000000000c1'::uuid ELSE gen_random_uuid() END;
            INSERT INTO clients (client_id, organization_id, name, status) VALUES (cli[k], o, 'Cliente ' || k, 1);
            FOR j IN 1..2 LOOP
                ws := ws || CASE WHEN i = 1 AND k = 1 AND j = 1 THEN 'aaaaaaaa-0000-4000-8000-0000000000a1'::uuid ELSE gen_random_uuid() END;
                INSERT INTO workspaces (workspace_id, organization_id, client_id, name, status)
                VALUES (ws[array_length(ws, 1)], o, cli[k], 'WS ' || k || '.' || j, 1);
            END LOOP;
        END LOOP;
        FOR j IN 1..2 LOOP
            ws := ws || gen_random_uuid();
            INSERT INTO workspaces (workspace_id, organization_id, client_id, name, status) VALUES (ws[array_length(ws, 1)], o, NULL, 'Interno ' || j, 1);
        END LOOP;

        aut := ARRAY[]::uuid[];
        ver := ARRAY[]::uuid[];
        FOR w IN 1..array_length(ws, 1) LOOP
            aut := aut || gen_random_uuid();
            ver := ver || gen_random_uuid();
            INSERT INTO automations (automation_id, organization_id, workspace_id, name, status, created_by_membership_id)
            VALUES (aut[w], o, ws[w], 'Automacao ' || w, 1, admm);
            INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id)
            VALUES (ver[w], o, ws[w], aut[w], 1,
                    jsonb_build_object('contract_version', 1, 'artifact', jsonb_build_object('reference', 'v1', 'sha256', repeat('a', 64)),
                                       'runtime', 'python3.11', 'entrypoint', 'main.py', 'arguments', '[]'::jsonb, 'parameters', '{}'::jsonb,
                                       'secret_bindings', '{}'::jsonb), admm);
            UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = admm
            WHERE automation_version_id = ver[w];
        END LOOP;

        -- contas externas: vinculo, concessao ativa no primeiro workspace do cliente (algumas pendentes), sessoes
        FOR k IN 1..40 LOOP
            u := CASE WHEN i = 1 AND k = 1 THEN 'aaaaaaaa-0000-4000-8000-0000000000e1'::uuid ELSE gen_random_uuid() END;
            cm := gen_random_uuid();
            j := 1 + (k - 1) % 8;
            INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name)
            VALUES (u, o, 2, 'externo' || k || '@cliente' || j || '-org' || i || '.test', now() - interval '20 days', 2, 'Externo ' || k);
            INSERT INTO user_credentials (user_id, password_hash) VALUES (u, pg_temp.phc());
            INSERT INTO client_memberships (client_membership_id, organization_id, client_id, user_id, account_type, status)
            VALUES (cm, o, cli[j], u, 2, 2);
            IF k % 9 = 0 THEN
                INSERT INTO client_workspace_grants (organization_id, client_id, client_membership_id, workspace_id, status, starts_at)
                VALUES (o, cli[j], cm, ws[2 * j - 1], 1, now());
            ELSE
                INSERT INTO client_workspace_grants (organization_id, client_id, client_membership_id, workspace_id, status, approved_by_membership_id,
                                                     approved_at, starts_at, next_review_at)
                VALUES (o, cli[j], cm, ws[2 * j - 1], 2, admm, now() - interval '10 days', now() - interval '10 days', now() + interval '20 days');
            END IF;
            INSERT INTO user_sessions (organization_id, user_id, session_token_hash, created_at, expires_at, last_seen_at, revoked_at)
            VALUES (o, u, CASE WHEN i = 1 AND k = 1 THEN current_setting('ensaio.tok_pre') ELSE encode(gen_random_bytes(32), 'hex') END,
                    now() - interval '30 minutes', now() + interval '11 hours', now() - interval '1 minute', NULL);
        END LOOP;

        -- Worker/Agent alocados em todos os workspaces
        wrk := gen_random_uuid();
        agt := gen_random_uuid();
        waa := gen_random_uuid();
        INSERT INTO workers (worker_id, organization_id, name, status) VALUES (wrk, o, 'Worker', 1);
        INSERT INTO agents (agent_id, organization_id, name, agent_version, status) VALUES (agt, o, 'Agent', '1.0.0', 2);
        INSERT INTO worker_agent_assignments (assignment_id, organization_id, worker_id, agent_id, status, assigned_by_membership_id)
        VALUES (waa, o, wrk, agt, 1, admm);
        wwa := ARRAY[]::uuid[];
        FOR w IN 1..array_length(ws, 1) LOOP
            wwa := wwa || gen_random_uuid();
            INSERT INTO worker_workspace_assignments (assignment_id, organization_id, worker_id, workspace_id, status, authorized_by_membership_id)
            VALUES (wwa[w], o, wrk, ws[w], 1, admm);
        END LOOP;

        -- execucoes: pendentes, canceladas antes do despacho, concluidas e falhas com eventos (uma ativa por Worker por vez)
        FOR k IN 1..n_exec LOOP
            w := 1 + (k - 1) % array_length(ws, 1);
            e := gen_random_uuid();
            INSERT INTO executions (execution_id, organization_id, workspace_id, automation_id, automation_version_id, request_key, request_fingerprint,
                                    requested_by_membership_id, command_snapshot, status, origin, created_at)
            SELECT e, o, ws[w], aut[w], ver[w], 'req-' || e, repeat('b', 64), admm, v.command_json, 1, 1,
                   now() - make_interval(mins => (n_exec - k) * 7)
            FROM automation_versions v WHERE v.automation_version_id = ver[w];
            destino := k % 10;
            IF destino IN (1, 2) THEN
                UPDATE executions SET status = 6, status_reason = 4, finished_at = clock_timestamp() WHERE execution_id = e;
            ELSIF destino IN (3, 4, 5, 6) THEN
                UPDATE executions SET status = 2, worker_id = wrk, worker_agent_assignment_id = waa, worker_workspace_assignment_id = wwa[w],
                                      reserved_at = clock_timestamp() WHERE execution_id = e;
                UPDATE executions SET status = 3, dispatched_at = clock_timestamp(), started_at = clock_timestamp() WHERE execution_id = e;
                INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
                VALUES (o, ws[w], e, agt, 1, 1, now(), '{"schema_version":1,"pid":7}', encode(sha256(convert_to(e::text || '1', 'UTF8')), 'hex'));
                IF destino = 6 THEN
                    UPDATE executions SET status = 5, status_reason = 3, finished_at = clock_timestamp() + interval '2 seconds', exit_code = 3,
                                          error_summary = 'falha simulada' WHERE execution_id = e;
                    INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
                    VALUES (o, ws[w], e, agt, 2, 3, now(), '{"schema_version":1,"exit_code":3,"error_summary":"falha simulada"}',
                            encode(sha256(convert_to(e::text || '2', 'UTF8')), 'hex'));
                ELSE
                    UPDATE executions SET status = 4, status_reason = 1, finished_at = clock_timestamp() + interval '1 second', exit_code = 0
                    WHERE execution_id = e;
                END IF;
            END IF;
        END LOOP;
    END LOOP;
END
$popula$;

SELECT 'organizacoes=' || (SELECT count(*) FROM organizations) || ' usuarios=' || (SELECT count(*) FROM users)
       || ' sessoes_ativas=' || (SELECT count(*) FROM user_sessions WHERE revoked_at IS NULL AND expires_at > now())
       || ' concessoes=' || (SELECT count(*) FROM client_workspace_grants) || ' execucoes=' || (SELECT count(*) FROM executions)
       || ' eventos=' || (SELECT count(*) FROM execution_events) AS resumo;
