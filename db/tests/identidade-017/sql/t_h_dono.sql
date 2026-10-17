-- Grupo H, parte do dono - papel: nekkora (superusuario), DECLARADO. A imutabilidade vale para QUALQUER papel:
-- como nekkora_identity_owner (SET ROLE pelo superusuario) e como o proprio superusuario (que ignora privilegios mas nao
-- triggers). Prova que um erro numa funcao do dono tambem falha fechado.
\pset pager off
SELECT pg_temp.afirma(current_setting('is_superuser') = 'on', 'sessao de superusuario');
CREATE TEMP TABLE v AS SELECT f.factor_id AS confirmado,
    (SELECT factor_id FROM identity_mfa_factors WHERE identity_id = f.identity_id AND confirmed_at IS NULL AND disabled_at IS NULL) AS pendente,
    (SELECT factor_id FROM identity_mfa_factors WHERE identity_id = pg_temp.identidade('d4@ex.test') AND disabled_at IS NOT NULL LIMIT 1) AS desabilitado
  FROM identity_mfa_factors f WHERE f.identity_id = pg_temp.identidade('h1@ex.test') AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL;
GRANT SELECT ON v TO nekkora_identity_owner;
SELECT pg_temp.afirma((SELECT confirmado IS NOT NULL AND pendente IS NOT NULL AND desabilitado IS NOT NULL FROM v), 'H dono: fixture com confirmado, pendente e desabilitado');
SET ROLE nekkora_identity_owner;
-- H-5: confirmacao nao volta a pendente nem para o dono
SELECT pg_temp.espera_erro(format('UPDATE identity_mfa_factors SET confirmed_at = NULL WHERE factor_id = %L', (SELECT confirmado FROM v)), '23514', 'irreversivel');
-- H-6: confirmado -> outro valor; desabilitado -> nulo
SELECT pg_temp.espera_erro(format('UPDATE identity_mfa_factors SET confirmed_at = confirmed_at + interval ''1 second'' WHERE factor_id = %L', (SELECT confirmado FROM v)), '23514', 'irreversivel');
SELECT pg_temp.espera_erro(format('UPDATE identity_mfa_factors SET disabled_at = NULL WHERE factor_id = %L', (SELECT desabilitado FROM v)), '23514', 'nao volta');
-- H-12 (dono): confirmar o pendente com o antigo habilitado -> indice unico
SELECT pg_temp.espera_erro(format('UPDATE identity_mfa_factors SET confirmed_at = now() WHERE factor_id = %L', (SELECT pendente FROM v)), '23505', 'ux_identity_mfa_factors_confirmado');
-- H-7 (dono): sem privilegio nas colunas protegidas
SELECT pg_temp.espera_erro(format('UPDATE identity_mfa_factors SET key_id = ''k2'' WHERE factor_id = %L', (SELECT confirmado FROM v)), '42501', 'permission denied');
-- H-9 (dono): sem DELETE
SELECT pg_temp.espera_erro(format('DELETE FROM identity_mfa_factors WHERE factor_id = %L', (SELECT confirmado FROM v)), '42501', 'permission denied');
RESET ROLE;
-- H-7 (superusuario): a trigger recusa cada coluna protegida
SELECT pg_temp.espera_erro(format('UPDATE identity_mfa_factors SET %s WHERE factor_id = %L', c, (SELECT confirmado FROM v)), '23514', 'imutaveis')
  FROM unnest(ARRAY['identity_id = (SELECT identity_id FROM identities WHERE email = ''ana@ex.test'')',
                    'secret_ciphertext = decode(repeat(''00'', 36), ''hex'')', 'secret_nonce = decode(repeat(''00'', 12), ''hex'')',
                    'key_id = ''k2''', 'created_at = created_at - interval ''1 day''', 'factor_type = factor_type']) c
 WHERE c <> 'factor_type = factor_type';
-- H-9 (superusuario): DELETE recusado pela trigger; INSERT fora do dono recusado
SELECT pg_temp.espera_erro(format('DELETE FROM identity_mfa_factors WHERE factor_id = %L', (SELECT confirmado FROM v)), '23514', 'nao e apagado');
SELECT pg_temp.espera_erro(format('INSERT INTO identity_mfa_factors (identity_id, factor_type, secret_ciphertext, secret_nonce, key_id) VALUES (%L, 1, decode(repeat(''aa'', 36), ''hex''), decode(repeat(''01'', 12), ''hex''), ''k1'')',
    pg_temp.identidade('h1@ex.test')), '42501', 'so nasce');
SELECT pg_temp.espera_erro(format('UPDATE identity_mfa_factors SET confirmed_at = now() WHERE factor_id = %L', (SELECT pendente FROM v)), '42501', 'so pelas funcoes');
-- H-15 (superusuario): codigos
SELECT pg_temp.espera_erro(format('UPDATE identity_mfa_recovery_codes SET code_hash = %L WHERE identity_id = %L AND used_at IS NULL AND invalidated_at IS NULL',
    pg_temp.h('x'), pg_temp.identidade('h1@ex.test')), '23514', 'imutavel');
SELECT pg_temp.espera_erro(format('UPDATE identity_mfa_recovery_codes SET invalidated_at = NULL WHERE identity_id = %L AND invalidated_at IS NOT NULL',
    pg_temp.identidade('d7@ex.test')), '23514', 'imutavel');
SELECT pg_temp.espera_erro(format('DELETE FROM identity_mfa_recovery_codes WHERE identity_id = %L', pg_temp.identidade('h1@ex.test')), '23514', 'nao e apagado');
SELECT 'grupo H (dono e superusuario): ok' AS resultado;
