-- f02 - papel: nekkora_platform. Fixture do portal pelos fluxos de produto, sob RLS (SET LOCAL do
-- contexto em toda transacao). Recebe do run.sh os tokens de sessao (-v tok_*); so o hash SHA-256
-- (contrato D2) e gravado. Nenhum token e impresso.
-- Correcao em relacao a fixture preexistente (db/tests/portal-8-2): cada Worker so pode ter UMA
-- execucao STARTING/RUNNING (ux_executions_active_worker, 006); as execucoes sao iniciadas e
-- encerradas em sequencia, e so e12 fica RUNNING.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');

-- hashes dos tokens (tabela temporaria desta sessao; nunca impressa)
CREATE TEMP TABLE tk (chave TEXT PRIMARY KEY, h TEXT NOT NULL);
INSERT INTO tk (chave, h)
SELECT k, encode(sha256(convert_to(v, 'UTF8')), 'hex') FROM (VALUES
    ('x', :'tok_x'), ('y', :'tok_y'), ('z', :'tok_z'), ('gp', :'tok_gp'), ('gs', :'tok_gs'), ('gr', :'tok_gr'),
    ('ge', :'tok_ge'), ('gf', :'tok_gf'), ('gv', :'tok_gv'), ('gw', :'tok_gw'), ('rv', :'tok_rv'), ('us', :'tok_us'),
    ('un', :'tok_un'), ('ms', :'tok_ms'), ('q', :'tok_q'), ('ra', :'tok_ra'),
    ('x2', :'tok_x2'), ('x3', :'tok_x3'), ('xrev', :'tok_xrev'), ('xexp', :'tok_xexp'), ('xina', :'tok_xina'),
    ('xb14', :'tok_xb14'), ('xb15', :'tok_xb15'), ('xtoq', :'tok_xtoq'), ('xfut', :'tok_xfut'), ('xp', :'tok_xp'),
    ('adm', :'tok_adm'), ('w', :'tok_w'), ('r', :'tok_r'), ('k', :'tok_k')) AS t(k, v);
SELECT pg_temp.afirma((SELECT count(*) FROM tk) = 30 AND (SELECT count(DISTINCT h) FROM tk) = 30, '30 tokens distintos recebidos');

-- instante-base (segundo inteiro) para created_at explicitos, inclusive o empate e14/e16/e17
SELECT date_trunc('second', now()) AS base \gset

-- helpers desta sessao (pg_temp): usam os privilegios do nekkora_platform
CREATE FUNCTION pg_temp.equipe(p_org UUID, p_pref TEXT) RETURNS VOID LANGUAGE plpgsql AS $f$
BEGIN
    INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name)
    VALUES ((p_pref || '-0000-0001-0000-000000000001')::uuid, p_org, 1, 'admin@' || p_pref || '.test', now(), 2, 'Admin ' || p_pref);
    INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status)
    VALUES ((p_pref || '-0000-0002-0000-000000000001')::uuid, p_org, (p_pref || '-0000-0001-0000-000000000001')::uuid, 1, 2);
END $f$;

CREATE FUNCTION pg_temp.sessao(p_org UUID, p_sess UUID, p_user UUID, p_tok TEXT, p_created TIMESTAMPTZ,
                               p_last_seen TIMESTAMPTZ, p_revoked TIMESTAMPTZ) RETURNS VOID LANGUAGE plpgsql AS $f$
BEGIN
    INSERT INTO user_sessions (session_id, organization_id, user_id, session_token_hash, created_at, expires_at, last_seen_at, revoked_at)
    VALUES (p_sess, p_org, p_user, (SELECT h FROM tk WHERE chave = p_tok), p_created, p_created + interval '12 hours', p_last_seen, p_revoked);
END $f$;

-- conta externa NN do cliente p_cli (nome com marcador de pessoa), vinculo, concessao para p_ws e
-- sessao principal (criada ha 10 min, prazo absoluto de 12 h)
CREATE FUNCTION pg_temp.externo(p_org UUID, p_pref TEXT, p_nn TEXT, p_cli UUID, p_ws UUID, p_tok TEXT,
    p_user_status SMALLINT DEFAULT 2, p_verificado BOOLEAN DEFAULT true, p_memb_status SMALLINT DEFAULT 2,
    p_grant_status SMALLINT DEFAULT 2, p_starts TIMESTAMPTZ DEFAULT now() - interval '1 day',
    p_expires TIMESTAMPTZ DEFAULT NULL, p_review TIMESTAMPTZ DEFAULT now() + interval '30 days') RETURNS VOID LANGUAGE plpgsql AS $f$
DECLARE
    v_user UUID := (p_pref || '-0000-0001-0000-000000000' || p_nn)::uuid;
    v_memb UUID := (p_pref || '-0000-00c2-0000-000000000' || p_nn)::uuid;
    v_adm  UUID := (p_pref || '-0000-0002-0000-000000000001')::uuid;
BEGIN
    INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name)
    VALUES (v_user, p_org, 2, 'marcador.email.' || p_nn || '@cliente-' || p_pref || '.test',
            CASE WHEN p_verificado THEN now() END, p_user_status, 'MARCADOR_NOME_PESSOA ' || p_nn);
    INSERT INTO client_memberships (client_membership_id, organization_id, client_id, user_id, account_type, status)
    VALUES (v_memb, p_org, p_cli, v_user, 2, p_memb_status);
    INSERT INTO client_workspace_grants (grant_id, organization_id, client_id, client_membership_id, workspace_id, status,
        approved_by_membership_id, approved_at, starts_at, expires_at, next_review_at, created_at, revoked_at, revoked_by_membership_id)
    VALUES ((p_pref || '-0000-00c3-0000-000000000' || p_nn)::uuid, p_org, p_cli, v_memb, p_ws, p_grant_status,
            CASE WHEN p_grant_status <> 1 THEN v_adm END, CASE WHEN p_grant_status <> 1 THEN now() - interval '1 day' END,
            p_starts, p_expires, CASE WHEN p_grant_status <> 1 THEN p_review END, now() - interval '2 days',
            CASE WHEN p_grant_status = 4 THEN now() - interval '1 hour' END, CASE WHEN p_grant_status = 4 THEN v_adm END);
    PERFORM pg_temp.sessao(p_org, (p_pref || '-0000-00c4-0000-000000000' || p_nn)::uuid, v_user, p_tok,
                           now() - interval '10 minutes', now() - interval '5 minutes', NULL);
END $f$;

-- automacao publicada (descricao e argumento com marcadores)
CREATE FUNCTION pg_temp.automacao(p_org UUID, p_pref TEXT, p_ws UUID, p_n INTEGER) RETURNS VOID LANGUAGE plpgsql AS $f$
DECLARE
    v_adm UUID := (p_pref || '-0000-0002-0000-000000000001')::uuid;
    v_aut UUID := (p_pref || '-0000-0008-0000-00000000000' || p_n)::uuid;
    v_ver UUID := (p_pref || '-0000-0009-0000-00000000000' || p_n)::uuid;
BEGIN
    INSERT INTO automations (automation_id, organization_id, workspace_id, name, description, status, created_by_membership_id)
    VALUES (v_aut, p_org, p_ws, 'Automacao ' || p_n, 'MARCADOR_DESCRICAO_AUTOMACAO', 1, v_adm);
    INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id)
    VALUES (v_ver, p_org, p_ws, v_aut, 1,
      jsonb_build_object('contract_version',1,'artifact',jsonb_build_object('reference','v1','sha256',repeat('a',64)),'runtime','python3.11',
                         'entrypoint','main.py','arguments','["MARCADOR_ARGUMENTO"]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb), v_adm);
    UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = v_adm WHERE automation_version_id = v_ver;
END $f$;

-- execucao PENDING com created_at explicito
CREATE FUNCTION pg_temp.execucao(p_org UUID, p_pref TEXT, p_ws UUID, p_n INTEGER, p_exec UUID, p_created TIMESTAMPTZ) RETURNS VOID LANGUAGE plpgsql AS $f$
DECLARE
    v_aut UUID := (p_pref || '-0000-0008-0000-00000000000' || p_n)::uuid;
    v_ver UUID := (p_pref || '-0000-0009-0000-00000000000' || p_n)::uuid;
BEGIN
    INSERT INTO executions (execution_id, organization_id, workspace_id, automation_id, automation_version_id, request_key,
                            request_fingerprint, requested_by_membership_id, command_snapshot, status, origin, created_at)
    SELECT p_exec, p_org, p_ws, v_aut, v_ver, 'req-' || p_exec, repeat('b', 64), (p_pref || '-0000-0002-0000-000000000001')::uuid,
           command_json, 1, 1, p_created
    FROM automation_versions WHERE automation_version_id = v_ver;
END $f$;

-- PENDING -> STARTING (reserva no Worker unico da organizacao) -> RUNNING
CREATE FUNCTION pg_temp.inicia(p_pref TEXT, p_exec UUID, p_wwa INTEGER) RETURNS VOID LANGUAGE plpgsql AS $f$
BEGIN
    UPDATE executions SET status = 2, worker_id = (p_pref || '-0000-0004-0000-000000000001')::uuid,
           worker_agent_assignment_id = (p_pref || '-0000-0006-0000-000000000001')::uuid,
           worker_workspace_assignment_id = (p_pref || '-0000-0007-0000-00000000000' || p_wwa)::uuid, reserved_at = clock_timestamp()
    WHERE execution_id = p_exec;
    UPDATE executions SET status = 3, dispatched_at = clock_timestamp(), started_at = clock_timestamp(), pid = 4242 WHERE execution_id = p_exec;
END $f$;

-- RUNNING -> terminal (libera o Worker para a proxima)
CREATE FUNCTION pg_temp.termina(p_exec UUID, p_status SMALLINT, p_reason SMALLINT, p_exit INTEGER, p_erro TEXT) RETURNS VOID LANGUAGE plpgsql AS $f$
BEGIN
    UPDATE executions SET status = p_status, status_reason = p_reason, finished_at = clock_timestamp() + interval '1 second',
           exit_code = p_exit, error_summary = p_erro
    WHERE execution_id = p_exec;
END $f$;

\echo === O1: equipe, clientes, workspaces
BEGIN;
SET LOCAL nekkora.organization_id = :'o1';
SELECT pg_temp.equipe(:'o1', '10000000');
INSERT INTO clients (client_id, organization_id, name, status) VALUES
    (:'c1', :'o1', 'Cliente C1', 1), (:'c2', :'o1', 'Cliente C2', 1), (:'c4', :'o1', 'Cliente C4', 1);
INSERT INTO workspaces (workspace_id, organization_id, client_id, name, description, status) VALUES
    (:'ws1', :'o1', :'c1', 'WS1', 'MARCADOR_DESCRICAO_WORKSPACE', 1),
    (:'ws2', :'o1', :'c1', 'WS2', NULL, 1),
    (:'ws3', :'o1', :'c2', 'WS3', NULL, 1),
    (:'wsi', :'o1', NULL,  'WSi interno', NULL, 1),
    (:'ws4', :'o1', :'c1', 'WS4', NULL, 1),
    (:'ws6', :'o1', :'c4', 'WS6', NULL, 1);
COMMIT;

\echo === O1: contas externas, vinculos, concessoes, sessoes principais
BEGIN;
SET LOCAL nekkora.organization_id = :'o1';
SELECT pg_temp.externo(:'o1', '10000000', '101', :'c1', :'ws1', 'x');
SELECT pg_temp.externo(:'o1', '10000000', '102', :'c1', :'ws2', 'y');
SELECT pg_temp.externo(:'o1', '10000000', '103', :'c2', :'ws3', 'z');
-- estados da concessao (a sessao, a conta e o vinculo sao validos)
SELECT pg_temp.externo(:'o1', '10000000', '104', :'c1', :'ws1', 'gp', p_grant_status => 1::smallint);
SELECT pg_temp.externo(:'o1', '10000000', '105', :'c1', :'ws1', 'gs', p_grant_status => 3::smallint);
SELECT pg_temp.externo(:'o1', '10000000', '106', :'c1', :'ws1', 'gr', p_grant_status => 4::smallint);
SELECT pg_temp.externo(:'o1', '10000000', '107', :'c1', :'ws1', 'ge', p_starts => now() - interval '3 days', p_expires => now() - interval '1 hour');
SELECT pg_temp.externo(:'o1', '10000000', '108', :'c1', :'ws1', 'gf', p_starts => now() + interval '1 day');
SELECT pg_temp.externo(:'o1', '10000000', '109', :'c1', :'ws1', 'gv', p_review => now() - interval '1 hour');
SELECT pg_temp.externo(:'o1', '10000000', '110', :'c1', :'ws4', 'gw');
SELECT pg_temp.externo(:'o1', '10000000', '111', :'c1', :'ws1', 'rv');
-- estados da conta/vinculo/cliente (a sessao em si esta valida)
SELECT pg_temp.externo(:'o1', '10000000', '112', :'c1', :'ws1', 'us', p_user_status => 3::smallint);
SELECT pg_temp.externo(:'o1', '10000000', '113', :'c1', :'ws1', 'un', p_verificado => false);
SELECT pg_temp.externo(:'o1', '10000000', '114', :'c1', :'ws1', 'ms', p_memb_status => 3::smallint);
SELECT pg_temp.externo(:'o1', '10000000', '115', :'c4', :'ws6', 'q');
SELECT pg_temp.externo(:'o1', '10000000', '116', :'c1', :'ws1', 'ra');
-- sessoes adicionais de X (os instantes de cada cenario de sessao sao ajustados antes do teste) e da conta interna
SELECT pg_temp.sessao(:'o1', :'s_x2',   :'x', 'x2',   now() - interval '10 minutes', now() - interval '5 minutes', NULL);
SELECT pg_temp.sessao(:'o1', :'s_x3',   :'x', 'x3',   now() - interval '10 minutes', now() - interval '5 minutes', NULL);
SELECT pg_temp.sessao(:'o1', :'s_xrev', :'x', 'xrev', now() - interval '10 minutes', now() - interval '5 minutes', now() - interval '1 minute');
SELECT pg_temp.sessao(:'o1', :'s_xexp', :'x', 'xexp', now() - interval '13 hours',   now() - interval '5 minutes', NULL);
SELECT pg_temp.sessao(:'o1', :'s_xina', :'x', 'xina', now() - interval '30 minutes', now() - interval '5 minutes', NULL);
SELECT pg_temp.sessao(:'o1', :'s_xb14', :'x', 'xb14', now() - interval '30 minutes', now() - interval '5 minutes', NULL);
SELECT pg_temp.sessao(:'o1', :'s_xb15', :'x', 'xb15', now() - interval '30 minutes', now() - interval '5 minutes', NULL);
SELECT pg_temp.sessao(:'o1', :'s_xtoq', :'x', 'xtoq', now() - interval '30 minutes', now() - interval '5 minutes', NULL);
SELECT pg_temp.sessao(:'o1', :'s_xfut', :'x', 'xfut', now() - interval '30 minutes', now() - interval '5 minutes', NULL);
SELECT pg_temp.sessao(:'o1', :'s_xp',   :'x', 'xp',   now() - interval '30 minutes', now() - interval '5 minutes', NULL);
SELECT pg_temp.sessao(:'o1', :'adm1_sess', :'adm1', 'adm', now() - interval '10 minutes', now() - interval '5 minutes', NULL);
COMMIT;

\echo === O1: Worker, Agent, associacoes (WS1, WS2, WS3, WSi)
BEGIN;
SET LOCAL nekkora.organization_id = :'o1';
INSERT INTO workers (worker_id, organization_id, name, status) VALUES ('10000000-0000-0004-0000-000000000001', :'o1', 'Worker O1', 1);
INSERT INTO agents (agent_id, organization_id, name, agent_version, status) VALUES ('10000000-0000-0005-0000-000000000001', :'o1', 'Agent O1', '1.0.0', 2);
INSERT INTO worker_agent_assignments (assignment_id, organization_id, worker_id, agent_id, status, assigned_by_membership_id)
VALUES ('10000000-0000-0006-0000-000000000001', :'o1', '10000000-0000-0004-0000-000000000001', '10000000-0000-0005-0000-000000000001', 1, :'adm1_memb');
INSERT INTO worker_workspace_assignments (assignment_id, organization_id, worker_id, workspace_id, status, authorized_by_membership_id)
SELECT ('10000000-0000-0007-0000-00000000000' || n)::uuid, :'o1', '10000000-0000-0004-0000-000000000001', ws, 1, :'adm1_memb'
FROM (VALUES (1, :'ws1'::uuid), (2, :'ws2'::uuid), (3, :'ws3'::uuid), (4, :'wsi'::uuid)) AS v(n, ws);
COMMIT;

\echo === O1: automacoes e execucoes (e14, e16, e17 com created_at identico)
BEGIN;
SET LOCAL nekkora.organization_id = :'o1';
SELECT pg_temp.automacao(:'o1', '10000000', :'ws1', 1), pg_temp.automacao(:'o1', '10000000', :'ws2', 2),
       pg_temp.automacao(:'o1', '10000000', :'ws3', 3), pg_temp.automacao(:'o1', '10000000', :'wsi', 4),
       pg_temp.automacao(:'o1', '10000000', :'ws4', 5), pg_temp.automacao(:'o1', '10000000', :'ws6', 6);
SELECT pg_temp.execucao(:'o1', '10000000', :'ws1', 1, :'e11', :'base'::timestamptz - interval '50 minutes'),
       pg_temp.execucao(:'o1', '10000000', :'ws1', 1, :'e12', :'base'::timestamptz - interval '40 minutes'),
       pg_temp.execucao(:'o1', '10000000', :'ws1', 1, :'e13', :'base'::timestamptz - interval '30 minutes'),
       pg_temp.execucao(:'o1', '10000000', :'ws1', 1, :'e14', :'base'::timestamptz - interval '20 minutes'),
       pg_temp.execucao(:'o1', '10000000', :'ws1', 1, :'e16', :'base'::timestamptz - interval '20 minutes'),
       pg_temp.execucao(:'o1', '10000000', :'ws1', 1, :'e17', :'base'::timestamptz - interval '20 minutes'),
       pg_temp.execucao(:'o1', '10000000', :'ws1', 1, :'e15', :'base'::timestamptz - interval '10 minutes'),
       pg_temp.execucao(:'o1', '10000000', :'ws2', 2, :'e21', :'base'::timestamptz - interval '45 minutes'),
       pg_temp.execucao(:'o1', '10000000', :'ws2', 2, :'e22', :'base'::timestamptz - interval '15 minutes'),
       pg_temp.execucao(:'o1', '10000000', :'ws3', 3, :'e31', :'base'::timestamptz - interval '25 minutes'),
       pg_temp.execucao(:'o1', '10000000', :'wsi', 4, :'e41', :'base'::timestamptz - interval '25 minutes'),
       pg_temp.execucao(:'o1', '10000000', :'ws4', 5, :'e51', :'base'::timestamptz - interval '25 minutes'),
       pg_temp.execucao(:'o1', '10000000', :'ws6', 6, :'e61', :'base'::timestamptz - interval '25 minutes');
-- uma execucao ativa por Worker de cada vez: inicia e encerra em sequencia; e12 fica RUNNING
SELECT pg_temp.inicia('10000000', :'e13', 1);
SELECT pg_temp.termina(:'e13', 5::smallint, 3::smallint, 97, 'MARCADOR_ERROR_SUMMARY_EXECUCAO');
SELECT pg_temp.inicia('10000000', :'e15', 1);
SELECT pg_temp.termina(:'e15', 4::smallint, 1::smallint, 0, NULL);
SELECT pg_temp.inicia('10000000', :'e21', 2);
SELECT pg_temp.termina(:'e21', 4::smallint, 1::smallint, 0, NULL);
SELECT pg_temp.inicia('10000000', :'e31', 3);
SELECT pg_temp.termina(:'e31', 4::smallint, 1::smallint, 0, NULL);
SELECT pg_temp.inicia('10000000', :'e12', 1);
COMMIT;

\echo === O2: equipe, cliente C3, conta W (WS7), execucao
BEGIN;
SET LOCAL nekkora.organization_id = :'o2';
SELECT pg_temp.equipe(:'o2', '20000000');
INSERT INTO clients (client_id, organization_id, name, status) VALUES ('20000000-0000-00c1-0000-000000000001', :'o2', 'Cliente C3', 1);
INSERT INTO workspaces (workspace_id, organization_id, client_id, name, status) VALUES (:'ws7', :'o2', '20000000-0000-00c1-0000-000000000001', 'WS7', 1);
SELECT pg_temp.externo(:'o2', '20000000', '101', '20000000-0000-00c1-0000-000000000001', :'ws7', 'w');
SELECT pg_temp.automacao(:'o2', '20000000', :'ws7', 1);
SELECT pg_temp.execucao(:'o2', '20000000', :'ws7', 1, :'e71', :'base'::timestamptz - interval '5 minutes');
COMMIT;

\echo === O3: equipe, cliente C5, conta R (WS8), execucao (organizacao suspensa em seguida)
BEGIN;
SET LOCAL nekkora.organization_id = :'o3';
SELECT pg_temp.equipe(:'o3', '30000000');
INSERT INTO clients (client_id, organization_id, name, status) VALUES ('30000000-0000-00c1-0000-000000000001', :'o3', 'Cliente C5', 1);
INSERT INTO workspaces (workspace_id, organization_id, client_id, name, status) VALUES (:'ws8', :'o3', '30000000-0000-00c1-0000-000000000001', 'WS8', 1);
SELECT pg_temp.externo(:'o3', '30000000', '101', '30000000-0000-00c1-0000-000000000001', :'ws8', 'r');
SELECT pg_temp.automacao(:'o3', '30000000', :'ws8', 1);
SELECT pg_temp.execucao(:'o3', '30000000', :'ws8', 1, :'e81', :'base'::timestamptz - interval '5 minutes');
COMMIT;

\echo === O4: equipe, cliente C7, conta K (WS9), execucao (concorrencia 7c)
BEGIN;
SET LOCAL nekkora.organization_id = :'o4';
SELECT pg_temp.equipe(:'o4', '40000000');
INSERT INTO clients (client_id, organization_id, name, status) VALUES (:'c7', :'o4', 'Cliente C7', 1);
INSERT INTO workspaces (workspace_id, organization_id, client_id, name, status) VALUES (:'ws9', :'o4', :'c7', 'WS9', 1);
SELECT pg_temp.externo(:'o4', '40000000', '101', :'c7', :'ws9', 'k');
SELECT pg_temp.automacao(:'o4', '40000000', :'ws9', 1);
SELECT pg_temp.execucao(:'o4', '40000000', :'ws9', 1, :'e91', :'base'::timestamptz - interval '5 minutes');
COMMIT;

\echo === estados finais da fixture: WS4 e C4 suspensos (O1), O3 suspensa
BEGIN;
SET LOCAL nekkora.organization_id = :'o1';
UPDATE workspaces SET status = 2 WHERE workspace_id = :'ws4';
UPDATE clients SET status = 2 WHERE client_id = :'c4';
COMMIT;
BEGIN;
SET LOCAL nekkora.organization_id = :'o3';
UPDATE organizations SET status = 2 WHERE organization_id = :'o3';
COMMIT;

SELECT 'fixture platform: ok' AS resultado;
