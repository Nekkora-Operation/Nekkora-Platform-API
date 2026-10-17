-- Grupo L, preparacao - papel: nekkora (superusuario), DECLARADO: desafio de vinculacao ja vencido (created_at no
-- passado, coluna fora do privilegio da Platform) e desafio de cadastro ja consumido (R3: a Platform nao consome desafio).
\pset pager off
INSERT INTO identity_link_challenges (identity_id, user_id, token_hash, account_credential_hash, created_at, expires_at)
VALUES (pg_temp.identidade('l1@ex.test'), pg_temp.conta('l1@ex.test', :'o1'), pg_temp.h('L/expirado'),
        (SELECT password_hash FROM user_credentials WHERE user_id = pg_temp.conta('l1@ex.test', :'o1')), now() - interval '10 minutes', now() - interval '6 minutes');
UPDATE identity_auth_challenges SET consumed_at = now(), credential_hash = NULL WHERE token_hash = pg_temp.h('L/cad-consumido');
SELECT 'grupo L preparacao (superusuario): ok' AS resultado;
