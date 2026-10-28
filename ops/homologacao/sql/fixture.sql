-- Fixture SINTETICA da homologacao - papel: admin_cluster (DECLARADO: preparacao de dados, nao evidencia).
-- Duas organizacoes. A: cliente C1 (workspace WS1, concedido ao externo X) e cliente C2 (WS2, sem concessao a X),
-- uma execucao em cada; interno I. B: cliente CB (WSB, concedido ao externo Y). Nenhum dado real.
-- Recebe -v com os ids, e-mails e os hashes PHC (Argon2id) das senhas sinteticas.
\set ON_ERROR_STOP on
BEGIN;
-- organizacoes e administradores (aprovam concessoes e criam automacoes)
INSERT INTO organizations (organization_id, name, status) VALUES (:'org_a', 'Homologacao A', 1), (:'org_b', 'Homologacao B', 1);
INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES
    (:'adm_a', :'org_a', 1, 'adm-a@homologacao.test', now(), 2, 'Adm A'),
    (:'adm_b', :'org_b', 1, 'adm-b@homologacao.test', now(), 2, 'Adm B'),
    (:'int_a', :'org_a', 1, :'email_int_a', now(), 2, 'Interno A'),
    (:'ext_a', :'org_a', 2, :'email_ext_a', now(), 2, 'Externo A'),
    (:'ext_b', :'org_b', 2, :'email_ext_b', now(), 2, 'Externo B');
-- credenciais no contexto da PROPRIA organizacao (desde a 017 a trigger refuse_local_entry_for_linked_account confere
-- nekkora.organization_id; o caminho e o da aplicacao, na transacao, sem desligar protecao)
SELECT set_config('nekkora.organization_id', :'org_a', true) IS NOT NULL AS fx_ctx_a \gset
INSERT INTO user_credentials (user_id, password_hash) VALUES (:'int_a', :'phc_int_a'), (:'ext_a', :'phc_ext_a');
SELECT set_config('nekkora.organization_id', :'org_b', true) IS NOT NULL AS fx_ctx_b \gset
INSERT INTO user_credentials (user_id, password_hash) VALUES (:'ext_b', :'phc_ext_b');
SELECT set_config('nekkora.organization_id', '', true) IS NOT NULL AS fx_ctx_limpo \gset
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES
    (:'adm_a_m', :'org_a', :'adm_a', 1, 2), (:'adm_b_m', :'org_b', :'adm_b', 1, 2);
INSERT INTO organization_memberships (organization_id, user_id, account_type, status) VALUES (:'org_a', :'int_a', 1, 2);
INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, limit_value, provisioned_by_user_id)
SELECT o, c, 1, now(), 2, NULL, a FROM (VALUES (:'org_a'::uuid, :'adm_a'::uuid), (:'org_b'::uuid, :'adm_b'::uuid)) v(o, a), generate_series(1, 5) c;
-- clientes, workspaces, vinculos e concessoes
INSERT INTO clients (client_id, organization_id, name, status) VALUES (:'cli_1', :'org_a', 'C1', 1), (:'cli_2', :'org_a', 'C2', 1), (:'cli_b', :'org_b', 'CB', 1);
INSERT INTO workspaces (workspace_id, organization_id, client_id, name, status) VALUES
    (:'ws_1', :'org_a', :'cli_1', 'WS1', 1), (:'ws_2', :'org_a', :'cli_2', 'WS2', 1), (:'ws_b', :'org_b', :'cli_b', 'WSB', 1);
INSERT INTO client_memberships (client_membership_id, organization_id, client_id, user_id, account_type, status) VALUES
    (:'cm_a', :'org_a', :'cli_1', :'ext_a', 2, 2), (:'cm_b', :'org_b', :'cli_b', :'ext_b', 2, 2);
INSERT INTO client_workspace_grants (organization_id, client_id, client_membership_id, workspace_id, status, approved_by_membership_id, approved_at, starts_at, next_review_at) VALUES
    (:'org_a', :'cli_1', :'cm_a', :'ws_1', 2, :'adm_a_m', now() - interval '1 day', now() - interval '1 day', now() + interval '30 days'),
    (:'org_b', :'cli_b', :'cm_b', :'ws_b', 2, :'adm_b_m', now() - interval '1 day', now() - interval '1 day', now() + interval '30 days');
-- automacoes publicadas e uma execucao em WS1 e outra em WS2
INSERT INTO automations (automation_id, organization_id, workspace_id, name, status, created_by_membership_id) VALUES
    (:'aut_1', :'org_a', :'ws_1', 'Automacao WS1', 1, :'adm_a_m'), (:'aut_2', :'org_a', :'ws_2', 'Automacao WS2', 1, :'adm_a_m');
INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id)
SELECT gen_random_uuid(), :'org_a', w, a, 1, jsonb_build_object('contract_version', 1, 'artifact', jsonb_build_object('reference', 'v1', 'sha256', repeat('a', 64)),
       'runtime', 'python3.11', 'entrypoint', 'main.py', 'arguments', '[]'::jsonb, 'parameters', '{}'::jsonb, 'secret_bindings', '{}'::jsonb), :'adm_a_m'
FROM (VALUES (:'ws_1'::uuid, :'aut_1'::uuid), (:'ws_2'::uuid, :'aut_2'::uuid)) v(w, a);
UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = :'adm_a_m' WHERE organization_id = :'org_a';
INSERT INTO executions (execution_id, organization_id, workspace_id, automation_id, automation_version_id, request_key, request_fingerprint,
                        requested_by_membership_id, command_snapshot, status, origin, created_at)
SELECT e, :'org_a', v.workspace_id, v.automation_id, v.automation_version_id, 'req-' || e, repeat('b', 64), :'adm_a_m', v.command_json, 1, 1, now() - interval '5 minutes'
FROM (VALUES (:'ex_1'::uuid, :'aut_1'::uuid), (:'ex_2'::uuid, :'aut_2'::uuid)) x(e, a) JOIN automation_versions v ON v.automation_id = x.a;
COMMIT;
SELECT 'fixture: organizacoes=' || count(*) FROM organizations WHERE organization_id IN (:'org_a', :'org_b');
