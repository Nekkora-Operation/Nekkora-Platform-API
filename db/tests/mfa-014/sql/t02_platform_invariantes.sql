-- t02 - papel: nekkora_platform (LOGIN real, sob RLS). O que a 014 impoe no banco ao papel que tem
-- privilegio amplo em user_sessions: sessao nova exige marcador + fator ativo; sessao revogada nao volta;
-- identidade/marcador/CSRF imutaveis; prazo absoluto nao se estende; step-up so avanca; fator, codigo,
-- desafio e evento seguem as regras de imutabilidade; sem contexto, nada visivel.
-- Precisao (contrato, revisao 4): estes testes provam barreiras ESTRUTURAIS. Nao provam que o MFA ocorreu -
-- o marcador pode ser gravado por quem tem INSERT; isso fica com a aplicacao.
-- Roda tambem contra mutacoes (triggers removidas) e PRECISA falhar nelas.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform' AND session_user = 'nekkora_platform', 'papel real nekkora_platform');

\echo === sem contexto: nenhuma linha das tabelas novas
SELECT pg_temp.afirma((SELECT count(*) FROM user_mfa_factors) + (SELECT count(*) FROM auth_challenges) + (SELECT count(*) FROM mfa_events) = 0,
  'sem nekkora.organization_id: nada visivel nas tabelas novas');
SET nekkora.organization_id = :'o2';
SELECT pg_temp.afirma((SELECT count(*) FROM user_mfa_factors) = 1 AND (SELECT count(*) FROM user_mfa_factors WHERE user_id = :'x') = 0,
  'contexto O2: so o fator de W; o de X (O1) invisivel');
SET nekkora.organization_id = :'o1';

\echo === INSERT em user_sessions
SELECT pg_temp.espera_erro(format($q$INSERT INTO user_sessions (organization_id, user_id, session_token_hash, expires_at, csrf_token)
    VALUES (%L, %L, repeat('1', 64), now() + interval '1 hour', repeat('A', 43))$q$, :'o1', :'x'), '23514', 'marcador de MFA');
SELECT pg_temp.espera_erro(format($q$INSERT INTO user_sessions (organization_id, user_id, session_token_hash, expires_at, mfa_verified_at)
    VALUES (%L, %L, repeat('2', 64), now() + interval '1 hour', now())$q$, :'o1', :'x'), '23514', 'marcador de MFA e CSRF');
SELECT pg_temp.espera_erro(format($q$INSERT INTO user_sessions (organization_id, user_id, session_token_hash, expires_at, mfa_verified_at, csrf_token)
    VALUES (%L, %L, repeat('3', 64), now() + interval '1 hour', now(), repeat('A', 43))$q$, :'o1', :'y'), '23514', 'fator de MFA confirmado e ativo');
SELECT pg_temp.espera_erro(format($q$INSERT INTO user_sessions (organization_id, user_id, session_token_hash, expires_at, mfa_verified_at, csrf_token, revoked_at)
    VALUES (%L, %L, repeat('4', 64), now() + interval '1 hour', now(), repeat('A', 43), now())$q$, :'o1', :'x'), '23514', 'sem step-up nem revogacao');
SELECT pg_temp.espera_erro(format($q$INSERT INTO user_sessions (organization_id, user_id, session_token_hash, expires_at, mfa_verified_at, csrf_token, mfa_step_up_at)
    VALUES (%L, %L, repeat('5', 64), now() + interval '1 hour', now(), repeat('A', 43), now())$q$, :'o1', :'x'), '23514', 'sem step-up nem revogacao');
BEGIN;
INSERT INTO user_sessions (organization_id, user_id, session_token_hash, expires_at, mfa_verified_at, csrf_token)
VALUES (:'o1', :'x', repeat('6', 64), now() + interval '1 hour', now(), repeat('B', 43)) RETURNING session_id AS s_nova \gset
SELECT pg_temp.afirma(true, 'sessao com marcador, CSRF e fator confirmado: aceita');
\echo === UPDATE em user_sessions (na sessao recem-criada)
SELECT pg_temp.espera_erro(format('UPDATE user_sessions SET expires_at = expires_at + interval ''1 minute'' WHERE session_id = %L', :'s_nova'), '23514', 'prazo absoluto');
SELECT pg_temp.espera_erro(format('UPDATE user_sessions SET session_token_hash = repeat(''7'', 64) WHERE session_id = %L', :'s_nova'), '23514', 'imutaveis');
SELECT pg_temp.espera_erro(format('UPDATE user_sessions SET mfa_verified_at = now() - interval ''1 day'' WHERE session_id = %L', :'s_nova'), '23514', 'imutaveis');
SELECT pg_temp.espera_erro(format('UPDATE user_sessions SET csrf_token = repeat(''C'', 43) WHERE session_id = %L', :'s_nova'), '23514', 'imutaveis');
UPDATE user_sessions SET expires_at = expires_at - interval '1 minute', last_seen_at = clock_timestamp() WHERE session_id = :'s_nova';
UPDATE user_sessions SET mfa_step_up_at = clock_timestamp() WHERE session_id = :'s_nova';
SELECT pg_temp.espera_erro(format('UPDATE user_sessions SET mfa_step_up_at = mfa_step_up_at - interval ''1 minute'' WHERE session_id = %L', :'s_nova'), '23514', 'step-up so avanca');
SELECT pg_temp.espera_erro(format('UPDATE user_sessions SET mfa_step_up_at = NULL WHERE session_id = %L', :'s_nova'), '23514', 'step-up so avanca');
UPDATE user_sessions SET revoked_at = clock_timestamp() WHERE session_id = :'s_nova';
SELECT pg_temp.espera_erro(format('UPDATE user_sessions SET revoked_at = NULL WHERE session_id = %L', :'s_nova'), '23514', 'nao pode ser reabilitada');
SELECT pg_temp.espera_erro(format('UPDATE user_sessions SET revoked_at = revoked_at + interval ''1 second'' WHERE session_id = %L', :'s_nova'), '23514', 'nao pode ser reabilitada');
SELECT pg_temp.espera_erro(format('UPDATE user_sessions SET mfa_step_up_at = clock_timestamp() WHERE session_id = %L', :'s_nova'), '23514', 'sessao nao revogada');
SELECT pg_temp.afirma((SELECT revoked_at IS NOT NULL FROM user_sessions WHERE session_id = :'s_nova'), 'sessao revogada permanece revogada');
ROLLBACK;
SELECT pg_temp.espera_erro(format('DELETE FROM user_sessions WHERE session_id = %L', :'s_x'), '42501', 'permission denied');

\echo === fator TOTP
BEGIN;
UPDATE user_mfa_factors SET last_used_step = coalesce(last_used_step, 0) + 100 WHERE factor_id = :'f_x';
-- gravar o MESMO passo e um no-op que a trigger nao distingue de 'coluna nao alterada'; o anti-replay e o
-- WHERE last_used_step < passo da primitiva (testado na concorrencia). Aqui: diminuir e anular sao rejeitados.
SELECT pg_temp.espera_erro(format('UPDATE user_mfa_factors SET last_used_step = NULL WHERE factor_id = %L', :'f_x'), 'P0001', 'passo TOTP aceito so aumenta');
SELECT pg_temp.espera_erro(format('UPDATE user_mfa_factors SET last_used_step = last_used_step - 1 WHERE factor_id = %L', :'f_x'), 'P0001', 'passo TOTP aceito so aumenta');
UPDATE user_mfa_factors SET last_used_step = last_used_step + 1 WHERE factor_id = :'f_x';
SELECT pg_temp.espera_erro(format('UPDATE user_mfa_factors SET secret_ciphertext = decode(repeat(''00'', 36), ''hex'') WHERE factor_id = %L', :'f_x'), 'P0001', 'segredo e confirmacao');
SELECT pg_temp.espera_erro(format('UPDATE user_mfa_factors SET confirmed_at = NULL WHERE factor_id = %L', :'f_x'), 'P0001', 'segredo e confirmacao');
SELECT pg_temp.espera_erro(format('UPDATE user_mfa_factors SET user_id = %L WHERE factor_id = %L', :'y', :'f_x'), 'P0001', 'origem do fator');
UPDATE user_mfa_factors SET disabled_at = clock_timestamp() WHERE factor_id = :'f_x';
SELECT pg_temp.espera_erro(format('UPDATE user_mfa_factors SET disabled_at = NULL WHERE factor_id = %L', :'f_x'), 'P0001', 'desabilitado e imutavel');
SELECT pg_temp.espera_erro(format($q$INSERT INTO user_sessions (organization_id, user_id, session_token_hash, expires_at, mfa_verified_at, csrf_token)
    VALUES (%L, %L, repeat('8', 64), now() + interval '1 hour', now(), repeat('A', 43))$q$, :'o1', :'x'), '23514', 'fator de MFA confirmado e ativo');
ROLLBACK;
SELECT pg_temp.espera_erro(format($q$INSERT INTO user_mfa_factors (organization_id, user_id, factor_type, secret_ciphertext, secret_nonce, key_id)
    VALUES (%L, %L, 1, decode(repeat('00', 36), 'hex'), decode(repeat('00', 12), 'hex'), 'k1')$q$, :'o1', :'x'), '23505', 'ux_user_mfa_factors_um_ativo');
SELECT pg_temp.espera_erro(format('DELETE FROM user_mfa_factors WHERE factor_id = %L', :'f_x'), '42501', 'permission denied');

\echo === desafio
BEGIN;
INSERT INTO auth_challenges (challenge_id, organization_id, user_id, token_hash, purpose, credential_hash, csrf_token, expires_at)
VALUES (:'ch1', :'o1', :'x', repeat('a', 64), 1, '$argon2id$exemplo', repeat('D', 43), now() + interval '5 minutes');
SELECT pg_temp.espera_erro(format($q$INSERT INTO auth_challenges (organization_id, user_id, token_hash, purpose, credential_hash, csrf_token, expires_at)
    VALUES (%L, %L, repeat('b', 64), 1, 'h', repeat('D', 43), now() + interval '6 minutes')$q$, :'o1', :'x'), '23514', 'auth_challenges_check');
SELECT pg_temp.espera_erro(format($q$INSERT INTO auth_challenges (organization_id, user_id, token_hash, purpose, csrf_token, expires_at)
    VALUES (%L, %L, repeat('c', 64), 1, repeat('D', 43), now() + interval '5 minutes')$q$, :'o1', :'x'), '23514', 'auth_challenges_check');
UPDATE auth_challenges SET attempts = attempts + 1 WHERE challenge_id = :'ch1';
SELECT pg_temp.espera_erro(format('UPDATE auth_challenges SET attempts = 0 WHERE challenge_id = %L', :'ch1'), 'P0001', 'so aumentam');
SELECT pg_temp.espera_erro(format('UPDATE auth_challenges SET attempts = 6 WHERE challenge_id = %L', :'ch1'), '23514', 'auth_challenges_attempts_check');
SELECT pg_temp.espera_erro(format('UPDATE auth_challenges SET credential_hash = ''outro'' WHERE challenge_id = %L', :'ch1'), 'P0001', 'hashes do desafio ativo');
SELECT pg_temp.espera_erro(format('UPDATE auth_challenges SET consumed_at = clock_timestamp() WHERE challenge_id = %L', :'ch1'), '23514', 'auth_challenges_check');
UPDATE auth_challenges SET consumed_at = clock_timestamp(), credential_hash = NULL, pending_password_hash = NULL WHERE challenge_id = :'ch1';
SELECT pg_temp.espera_erro(format('UPDATE auth_challenges SET attempts = attempts + 1 WHERE challenge_id = %L', :'ch1'), 'P0001', 'consumido ou invalidado e imutavel');
ROLLBACK;

\echo === codigos de recuperacao e auditoria
BEGIN;
INSERT INTO user_mfa_recovery_codes (code_id, organization_id, user_id, factor_id, code_hash) VALUES (:'rc1', :'o1', :'x', :'f_x', repeat('e', 64));
SELECT pg_temp.espera_erro(format($q$INSERT INTO user_mfa_recovery_codes (organization_id, user_id, factor_id, code_hash) VALUES (%L, %L, %L, repeat('f', 64))$q$,
    :'o1', :'y', :'f_x'), '23503', 'user_mfa_recovery_codes');
UPDATE user_mfa_recovery_codes SET used_at = clock_timestamp() WHERE code_id = :'rc1';
SELECT pg_temp.espera_erro(format('UPDATE user_mfa_recovery_codes SET used_at = NULL WHERE code_id = %L', :'rc1'), 'P0001', 'usado ou invalidado e imutavel');
INSERT INTO mfa_events (organization_id, user_id, actor_user_id, event_type, correlation_id) VALUES (:'o1', :'x', :'a1', 3, gen_random_uuid());
SELECT pg_temp.espera_erro(format($q$INSERT INTO mfa_events (organization_id, user_id, actor_user_id, event_type, correlation_id) VALUES (%L, %L, %L, 3, gen_random_uuid())$q$,
    :'o1', :'a1', :'a1'), '23514', 'mfa_events_check');
SELECT pg_temp.espera_erro('UPDATE mfa_events SET event_type = 1', '42501', 'permission denied');  -- sem UPDATE: o privilegio barra antes da trigger append-only
ROLLBACK;
SELECT pg_temp.espera_erro('DELETE FROM mfa_events', '42501', 'permission denied');
SELECT pg_temp.espera_erro('TRUNCATE auth_challenges', '42501', 'permission denied');
