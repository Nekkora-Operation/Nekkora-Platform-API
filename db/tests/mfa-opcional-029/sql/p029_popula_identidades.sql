-- Massa de IDENTIDADES para a suite da 029 (base 001-028), so em cluster DESCARTAVEL: superusuario com
-- session_replication_role = replica (triggers desligadas SO nesta carga de fixture; nenhuma regra e conferida aqui).
-- Representa os estados que a atualizacao tem de preservar: identidade com fator confirmado ativo, codigos e sessao;
-- identidade com fator DESABILITADO (reset operacional); identidade confirmada SEM fator (nunca entrou depois do cadastro).
\set ON_ERROR_STOP 1
BEGIN;
SET LOCAL session_replication_role = replica;
INSERT INTO identities (identity_id, email, display_name, email_verified_at, status) VALUES
  ('29000000-0000-4000-8000-000000000001', 'com-fator@p029.test', 'Com fator', now() - interval '3 days', 2),
  ('29000000-0000-4000-8000-000000000002', 'fator-desabilitado@p029.test', 'Reset', now() - interval '3 days', 2),
  ('29000000-0000-4000-8000-000000000003', 'sem-fator@p029.test', 'Sem fator', now() - interval '1 day', 2);
INSERT INTO identity_credentials (identity_id, password_hash)
SELECT identity_id, '$argon2id$v=19$m=19456,t=2,p=1$Zml4dHVyZS1wMDI5$' || md5(identity_id::text) FROM identities WHERE email LIKE '%@p029.test';
INSERT INTO identity_mfa_factors (factor_id, identity_id, factor_type, secret_ciphertext, secret_nonce, key_id, created_at, confirmed_at, disabled_at, last_used_step) VALUES
  ('29000000-0000-4000-8000-0000000000f1', '29000000-0000-4000-8000-000000000001', 1, decode(repeat('ab', 36), 'hex'), decode(repeat('cd', 12), 'hex'), 'p029', now() - interval '3 days', now() - interval '3 days', NULL, 1000),
  ('29000000-0000-4000-8000-0000000000f2', '29000000-0000-4000-8000-000000000002', 1, decode(repeat('ab', 36), 'hex'), decode(repeat('cd', 12), 'hex'), 'p029', now() - interval '3 days', now() - interval '3 days', now() - interval '1 day', 1000);
INSERT INTO identity_mfa_recovery_codes (identity_id, factor_id, code_hash)
SELECT '29000000-0000-4000-8000-000000000001', '29000000-0000-4000-8000-0000000000f1', md5('p029-' || g) || md5('x' || g) FROM generate_series(1, 10) g;
INSERT INTO identity_sessions (identity_id, session_token_hash, expires_at, mfa_verified_at, csrf_token) VALUES
  ('29000000-0000-4000-8000-000000000001', md5('p029-sessao-1') || md5('p029-sessao-1b'), now() + interval '6 hours', now() - interval '1 minute', rpad('p029csrf', 43, 'x'));
COMMIT;
SELECT 'massa 029: identidades=' || (SELECT count(*) FROM identities WHERE email LIKE '%@p029.test')
    || ' fatores_ativos=' || (SELECT count(*) FROM identity_mfa_factors WHERE key_id = 'p029' AND confirmed_at IS NOT NULL AND disabled_at IS NULL)
    || ' codigos=' || (SELECT count(*) FROM identity_mfa_recovery_codes r JOIN identities i USING (identity_id) WHERE i.email LIKE '%@p029.test')
    || ' sessoes=' || (SELECT count(*) FROM identity_sessions s JOIN identities i USING (identity_id) WHERE i.email LIKE '%@p029.test' AND s.revoked_at IS NULL);
