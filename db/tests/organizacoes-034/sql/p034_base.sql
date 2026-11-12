-- Massa comum da suite 034 (preparacao como administrador do cluster, NAO evidencia): gatilhos e chaves estrangeiras
-- desligados SO durante a carga (session_replication_role = replica). As restricoes CHECK continuam valendo.
--   identidade 1 (Ana) e identidade 2 (Bia)
--   org 1 "Padaria Central": criada por Ana (vinculo cadastro_web + evento cadastro_web)  -> autoria de Ana
--   org 2 "Padaria Central": criada por Bia (mesmo nome, outra criadora)                  -> autoria de Bia
--   org 3 "padaria  central": provisionada pela operacao (evento operacao, sem cadastro)    -> sem autoria
--   org 4 "Construcao & Cia": vinculo cadastro_web de Ana SEM o evento de cadastro          -> ambigua, sem autoria
--   Bia participa da org 1 por convite (vinculacao): nao conta como criadora
\set ON_ERROR_STOP on
SET session_replication_role = replica;
INSERT INTO identities (identity_id, email, display_name, email_verified_at, status) VALUES ('00000034-0000-4000-9001-000000000001', 'ana@exemplo.test', 'Ana', now(), 2), ('00000034-0000-4000-9001-000000000002', 'bia@exemplo.test', 'Bia', now(), 2);
INSERT INTO organizations (organization_id, name, status, created_at) VALUES ('00000034-0000-4000-9002-000000000001', 'Padaria Central', 1, timestamptz '2026-11-01 10:00:00+00');
INSERT INTO users (user_id, organization_id, account_type, display_name, email, email_verified_at, status) VALUES ('00000034-0000-4000-9003-000000000001', '00000034-0000-4000-9002-000000000001', 1, 'Pessoa', 'ana@exemplo.test', now(), 2);
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('00000034-0000-4000-9004-000000000001', '00000034-0000-4000-9002-000000000001', '00000034-0000-4000-9003-000000000001', 1, 2);
INSERT INTO identity_users (identity_id, user_id, link_channel) VALUES ('00000034-0000-4000-9001-000000000001', '00000034-0000-4000-9003-000000000001', 'cadastro_web');
INSERT INTO identity_provisioning_events (event_id, organization_id, assignment_id, provisioned_by_user_id, channel, correlation_id) VALUES ('00000034-0000-4000-9006-000000000001', '00000034-0000-4000-9002-000000000001', '00000034-0000-4000-9005-000000000001', '00000034-0000-4000-9003-000000000001', 'cadastro_web', gen_random_uuid());
INSERT INTO organizations (organization_id, name, status, created_at) VALUES ('00000034-0000-4000-9002-000000000002', 'Padaria Central', 1, timestamptz '2026-11-01 11:00:00+00');
INSERT INTO users (user_id, organization_id, account_type, display_name, email, email_verified_at, status) VALUES ('00000034-0000-4000-9003-000000000002', '00000034-0000-4000-9002-000000000002', 1, 'Pessoa', 'bia@exemplo.test', now(), 2);
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('00000034-0000-4000-9004-000000000002', '00000034-0000-4000-9002-000000000002', '00000034-0000-4000-9003-000000000002', 1, 2);
INSERT INTO identity_users (identity_id, user_id, link_channel) VALUES ('00000034-0000-4000-9001-000000000002', '00000034-0000-4000-9003-000000000002', 'cadastro_web');
INSERT INTO identity_provisioning_events (event_id, organization_id, assignment_id, provisioned_by_user_id, channel, correlation_id) VALUES ('00000034-0000-4000-9006-000000000002', '00000034-0000-4000-9002-000000000002', '00000034-0000-4000-9005-000000000002', '00000034-0000-4000-9003-000000000002', 'cadastro_web', gen_random_uuid());
INSERT INTO organizations (organization_id, name, status, created_at) VALUES ('00000034-0000-4000-9002-000000000003', 'padaria  central', 1, timestamptz '2026-11-01 12:00:00+00');
INSERT INTO users (user_id, organization_id, account_type, display_name, email, email_verified_at, status) VALUES ('00000034-0000-4000-9003-000000000003', '00000034-0000-4000-9002-000000000003', 1, 'Pessoa', 'operacao@exemplo.test', now(), 2);
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('00000034-0000-4000-9004-000000000003', '00000034-0000-4000-9002-000000000003', '00000034-0000-4000-9003-000000000003', 1, 2);
INSERT INTO identity_provisioning_events (event_id, organization_id, assignment_id, provisioned_by_user_id, channel, correlation_id) VALUES ('00000034-0000-4000-9006-000000000003', '00000034-0000-4000-9002-000000000003', '00000034-0000-4000-9005-000000000003', '00000034-0000-4000-9003-000000000003', 'operacao', gen_random_uuid());
INSERT INTO organizations (organization_id, name, status, created_at) VALUES ('00000034-0000-4000-9002-000000000004', 'Construção & Cia', 1, timestamptz '2026-11-01 13:00:00+00');
INSERT INTO users (user_id, organization_id, account_type, display_name, email, email_verified_at, status) VALUES ('00000034-0000-4000-9003-000000000004', '00000034-0000-4000-9002-000000000004', 1, 'Pessoa', 'ana@exemplo.test', now(), 2);
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('00000034-0000-4000-9004-000000000004', '00000034-0000-4000-9002-000000000004', '00000034-0000-4000-9003-000000000004', 1, 2);
INSERT INTO identity_users (identity_id, user_id, link_channel) VALUES ('00000034-0000-4000-9001-000000000001', '00000034-0000-4000-9003-000000000004', 'cadastro_web');
INSERT INTO users (user_id, organization_id, account_type, display_name, email, email_verified_at, status) VALUES ('00000034-0000-4000-9003-000000000005', '00000034-0000-4000-9002-000000000001', 1, 'Pessoa', 'bia@exemplo.test', now(), 2);
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('00000034-0000-4000-9004-000000000005', '00000034-0000-4000-9002-000000000001', '00000034-0000-4000-9003-000000000005', 1, 2);
INSERT INTO identity_users (identity_id, user_id, link_channel) VALUES ('00000034-0000-4000-9001-000000000002', '00000034-0000-4000-9003-000000000005', 'vinculacao');
SET session_replication_role = origin;
