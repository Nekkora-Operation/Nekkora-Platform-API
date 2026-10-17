-- Grupo S, defesas de trigger (R3) - papel: nekkora (superusuario), DECLARADO. A aplicacao nao alcanca mais estas escritas;
-- as triggers continuam como defesa contra erro nas proprias funcoes do dono.
\pset pager off
SELECT pg_temp.afirma(current_setting('is_superuser') = 'on', 'sessao de superusuario');
-- marca de recuperacao no futuro, mesmo pelo dono (que e quem insere sessoes desde a R3)
SET ROLE nekkora_identity_owner;
SELECT pg_temp.espera_erro(format('INSERT INTO identity_sessions (identity_id, session_token_hash, expires_at, mfa_verified_at, recovery_used_at, csrf_token) VALUES (%L, %L, now() + interval ''1 hour'', now(), now() + interval ''1 year'', %L)',
    pg_temp.identidade('d9@ex.test'), pg_temp.h('S-sup/futuro'), pg_temp.csrf('S-sup/futuro')), '23514', 'no futuro');
RESET ROLE;
-- codigo consumido apontando sessao NAO restrita: recusado no commit pela restricao adiada (antes G-8)
CREATE FUNCTION pg_temp.consumo_indevido() RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    UPDATE identity_mfa_recovery_codes SET used_at = now(), used_in_identity_session_id = pg_temp.sessao_id('G/comum')
     WHERE code_id = (SELECT code_id FROM identity_mfa_recovery_codes WHERE identity_id = pg_temp.identidade('gabi@ex.test')
                       AND used_at IS NULL AND invalidated_at IS NULL ORDER BY code_hash LIMIT 1);
    SET CONSTRAINTS ALL IMMEDIATE;
END;
$$;
SELECT pg_temp.espera_erro('SELECT pg_temp.consumo_indevido()', '23514', 'sem sessao restrita');
SELECT 'grupo S (superusuario): ok' AS resultado;
