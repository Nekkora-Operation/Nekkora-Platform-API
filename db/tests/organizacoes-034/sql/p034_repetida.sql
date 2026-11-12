-- Massa da base C da suite 034: a REPETICAO que impede a 034.
--   org 6 "PADARIA   central": tambem criada por Ana pelo cadastro (mesmo nome normalizado da org 1)
\set ON_ERROR_STOP on
SET session_replication_role = replica;
INSERT INTO organizations (organization_id, name, status, created_at) VALUES ('00000034-0000-4000-9002-000000000006', 'PADARIA   central', 1, timestamptz '2026-11-02 09:00:00+00');
INSERT INTO users (user_id, organization_id, account_type, display_name, email, email_verified_at, status) VALUES ('00000034-0000-4000-9003-000000000006', '00000034-0000-4000-9002-000000000006', 1, 'Pessoa', 'ana@exemplo.test', now(), 2);
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('00000034-0000-4000-9004-000000000006', '00000034-0000-4000-9002-000000000006', '00000034-0000-4000-9003-000000000006', 1, 2);
INSERT INTO identity_users (identity_id, user_id, link_channel) VALUES ('00000034-0000-4000-9001-000000000001', '00000034-0000-4000-9003-000000000006', 'cadastro_web');
INSERT INTO identity_provisioning_events (event_id, organization_id, assignment_id, provisioned_by_user_id, channel, correlation_id) VALUES ('00000034-0000-4000-9006-000000000006', '00000034-0000-4000-9002-000000000006', '00000034-0000-4000-9005-000000000006', '00000034-0000-4000-9003-000000000006', 'cadastro_web', gen_random_uuid());
SET session_replication_role = origin;
