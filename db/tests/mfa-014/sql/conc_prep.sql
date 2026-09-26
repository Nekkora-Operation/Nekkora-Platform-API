-- conc_prep - papel: nekkora_platform. Cria (commit) o desafio ch2 e o codigo de recuperacao rc2 de X
-- que as duas sessoes concorrentes vao tentar consumir.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
BEGIN;
SET LOCAL nekkora.organization_id = :'o1';
INSERT INTO auth_challenges (challenge_id, organization_id, user_id, token_hash, purpose, credential_hash, csrf_token, expires_at)
VALUES (:'ch2', :'o1', :'x', repeat('9', 64), 1, '$argon2id$exemplo', repeat('E', 43), now() + interval '5 minutes');
INSERT INTO user_mfa_recovery_codes (organization_id, user_id, factor_id, code_hash) VALUES (:'o1', :'x', :'f_x', repeat('d', 64));
COMMIT;
