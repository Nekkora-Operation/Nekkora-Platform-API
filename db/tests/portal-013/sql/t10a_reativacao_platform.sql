-- t10a - papel: nekkora_platform (fluxos de produto sob RLS). Grupo "Estados" da missao: reativacao da
-- conta NAO restaura concessao. Conta Ra suspensa (com revogacao das sessoes, 11.4); durante a
-- suspensao a concessao e revogada; a conta e reativada; novo login (sessao ra2, so o hash).
-- A concessao revogada nao pode ser reaberta (trigger da 003).
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
BEGIN;
SET LOCAL nekkora.organization_id = :'o1';
UPDATE users SET status = 3 WHERE user_id = :'ra';
UPDATE user_sessions SET revoked_at = clock_timestamp() WHERE user_id = :'ra' AND revoked_at IS NULL;
COMMIT;
BEGIN;
SET LOCAL nekkora.organization_id = :'o1';
UPDATE client_workspace_grants SET status = 4, revoked_at = clock_timestamp(), revoked_by_membership_id = :'adm1_memb' WHERE grant_id = :'gr_ra';
COMMIT;
BEGIN;
SET LOCAL nekkora.organization_id = :'o1';
UPDATE users SET status = 2 WHERE user_id = :'ra';
INSERT INTO user_sessions (session_id, organization_id, user_id, session_token_hash, created_at, expires_at, last_seen_at, revoked_at)
VALUES (:'s_ra2', :'o1', :'ra', encode(sha256(convert_to(:'tok_ra2', 'UTF8')), 'hex'), now() - interval '5 minutes', now() + interval '11 hours 55 minutes', now() - interval '2 minutes', NULL);
COMMIT;
SET nekkora.organization_id = :'o1';
SELECT pg_temp.espera_erro(format('UPDATE client_workspace_grants SET status = 2, revoked_at = NULL, revoked_by_membership_id = NULL WHERE grant_id = %L', :'gr_ra'),
                           'P0001', 'concessao revogada e imutavel');
SELECT pg_temp.afirma((SELECT status FROM users WHERE user_id = :'ra') = 2
                  AND (SELECT status FROM client_workspace_grants WHERE grant_id = :'gr_ra') = 4, 'Ra reativada; concessao continua revogada');
