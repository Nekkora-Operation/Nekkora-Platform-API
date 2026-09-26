-- f02 - papel: nekkora_platform (fluxo de produto sob RLS). O1: A1 (interno, sera ORG_ADMIN), I2
-- (interno), cliente C1, WS1, X (externo, concessao em WS1, fator confirmado, sessao com MFA), Y (externo,
-- sem fator). O2: W (externo com fator). Os segredos TOTP sao bytes sinteticos de tamanho valido (a cifra e
-- da aplicacao; aqui so se testa o banco). Tokens de sessao chegam por -v (so o hash e gravado).
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');

BEGIN;
SET LOCAL nekkora.organization_id = :'o1';
INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES
    (:'a1', :'o1', 1, 'a1@o1.test', now(), 2, 'Admin A1'),
    (:'i2', :'o1', 1, 'i2@o1.test', now(), 2, 'Interno I2');
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES
    (:'a1_memb', :'o1', :'a1', 1, 2), (:'i2_memb', :'o1', :'i2', 1, 2);
INSERT INTO clients (client_id, organization_id, name, status) VALUES (:'c1', :'o1', 'Cliente C1', 1);
INSERT INTO workspaces (workspace_id, organization_id, client_id, name, status) VALUES (:'ws1', :'o1', :'c1', 'WS1', 1);
INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES
    (:'x', :'o1', 2, 'x@cliente.test', now(), 2, 'Externo X'),
    (:'y', :'o1', 2, 'y@cliente.test', now(), 2, 'Externo Y');
INSERT INTO client_memberships (client_membership_id, organization_id, client_id, user_id, account_type, status) VALUES
    (:'cm_x', :'o1', :'c1', :'x', 2, 2), (:'cm_y', :'o1', :'c1', :'y', 2, 2);
INSERT INTO client_workspace_grants (grant_id, organization_id, client_id, client_membership_id, workspace_id, status,
    approved_by_membership_id, approved_at, starts_at, next_review_at)
VALUES (:'gr_x', :'o1', :'c1', :'cm_x', :'ws1', 2, :'a1_memb', now() - interval '1 day', now() - interval '1 day', now() + interval '30 days');
INSERT INTO user_mfa_factors (factor_id, organization_id, user_id, factor_type, secret_ciphertext, secret_nonce, key_id, created_at, confirmed_at) VALUES
    (:'f_x',  :'o1', :'x',  1, decode(repeat('ab', 36), 'hex'), decode(repeat('01', 12), 'hex'), 'k1', now() - interval '1 day', now() - interval '1 day'),
    (:'f_a1', :'o1', :'a1', 1, decode(repeat('cd', 36), 'hex'), decode(repeat('02', 12), 'hex'), 'k1', now() - interval '1 day', now() - interval '1 day');
INSERT INTO user_sessions (session_id, organization_id, user_id, session_token_hash, created_at, expires_at, last_seen_at, mfa_verified_at, csrf_token)
VALUES (:'s_x', :'o1', :'x', encode(sha256(convert_to(:'tok_x', 'UTF8')), 'hex'), now() - interval '5 minutes', now() + interval '11 hours 55 minutes',
        now() - interval '2 minutes', now() - interval '5 minutes', :'csrf_x'),
       (:'s_a1', :'o1', :'a1', encode(sha256(convert_to(:'tok_a1', 'UTF8')), 'hex'), now() - interval '5 minutes', now() + interval '11 hours 55 minutes',
        now() - interval '2 minutes', now() - interval '5 minutes', :'csrf_a1');
COMMIT;

BEGIN;
SET LOCAL nekkora.organization_id = :'o2';
INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES
    ('20000000-0000-0001-0000-000000000001', :'o2', 1, 'adm@o2.test', now(), 2, 'Admin O2'),
    (:'w', :'o2', 2, 'w@cliente.test', now(), 2, 'Externo W');
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status)
VALUES ('20000000-0000-0002-0000-000000000001', :'o2', '20000000-0000-0001-0000-000000000001', 1, 2);
INSERT INTO clients (client_id, organization_id, name, status) VALUES ('20000000-0000-00c1-0000-000000000001', :'o2', 'Cliente C3', 1);
INSERT INTO client_memberships (client_membership_id, organization_id, client_id, user_id, account_type, status)
VALUES ('20000000-0000-00c2-0000-000000000101', :'o2', '20000000-0000-00c1-0000-000000000001', :'w', 2, 2);
INSERT INTO user_mfa_factors (factor_id, organization_id, user_id, factor_type, secret_ciphertext, secret_nonce, key_id, created_at, confirmed_at)
VALUES (:'f_w', :'o2', :'w', 1, decode(repeat('ef', 36), 'hex'), decode(repeat('03', 12), 'hex'), 'k1', now() - interval '1 day', now() - interval '1 day');
COMMIT;
SELECT 'fixture platform: ok' AS resultado;
