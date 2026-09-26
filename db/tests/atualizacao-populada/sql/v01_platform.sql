-- v01 - papel: nekkora_platform (RLS, contexto por transacao). Depois da atualizacao 013/014 na copia:
--   (a) a 014 esta ativa: sessao sem marcador de MFA e reativacao de sessao revogada pre-014 sao recusadas;
--   (b) a conta externa PRE-EXISTENTE cadastra o fator e recebe sessao nova com o marcador (caminho da Platform).
-- Recebe -v org, ext, hash_novo (SHA-256 do token novo; o token nunca chega aqui), csrf.
\set ON_ERROR_STOP on
\pset pager off
SELECT current_user = 'nekkora_platform' AS papel_correto \gset
\if :papel_correto
\else
    \echo 'FALHA: papel diferente de nekkora_platform'
    \quit 1
\endif

\set ON_ERROR_STOP off
BEGIN;
SELECT set_config('nekkora.organization_id', :'org', true);
INSERT INTO user_sessions (organization_id, user_id, session_token_hash, created_at, expires_at, last_seen_at, csrf_token)
VALUES (:'org', :'ext', encode(sha256(gen_random_uuid()::text::bytea), 'hex'), now(), now() + interval '12 hours', now(),
        rtrim(translate(encode(gen_random_bytes(32), 'base64'), '+/', '-_'), '='));  -- CSRF descartavel: o comando FALHA e o texto vai ao log
\echo sem_marcador_sqlstate=:SQLSTATE
\echo sem_marcador_msg=:LAST_ERROR_MESSAGE
ROLLBACK;
BEGIN;
SELECT set_config('nekkora.organization_id', :'org', true);
UPDATE user_sessions SET revoked_at = NULL WHERE user_id = :'ext' AND revoked_at IS NOT NULL;
\echo reativacao_sqlstate=:SQLSTATE
\echo reativacao_msg=:LAST_ERROR_MESSAGE
ROLLBACK;
\set ON_ERROR_STOP on

BEGIN;
SELECT set_config('nekkora.organization_id', :'org', true) \g /dev/null
INSERT INTO user_mfa_factors (organization_id, user_id, factor_type, secret_ciphertext, secret_nonce, key_id, confirmed_at)
VALUES (:'org', :'ext', 1, decode(repeat('ab', 36), 'hex'), decode(repeat('01', 12), 'hex'), 'k1', now());
INSERT INTO user_sessions (organization_id, user_id, session_token_hash, created_at, expires_at, last_seen_at, mfa_verified_at, csrf_token)
VALUES (:'org', :'ext', :'hash_novo', now(), now() + interval '12 hours', now(), now(), :'csrf');
COMMIT;
\echo sessao_nova=ok
