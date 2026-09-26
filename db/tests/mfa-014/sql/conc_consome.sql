-- conc_consome - papel: nekkora_platform. Uma das duas sessoes concorrentes (:sessao = A ou B) que tentam
-- consumir o MESMO desafio, o MESMO passo TOTP e o MESMO codigo de recuperacao, na ordem do contrato
-- (desafio -> fator -> codigo), com as primitivas UPDATE ... WHERE <ainda disponivel> RETURNING.
-- A segura a transacao :espera segundos antes do commit; B chega depois, espera o lock da linha do desafio e,
-- liberada, encontra tudo consumido: 0 linhas em cada primitiva (:esperado = 1 para A, 0 para B).
-- Sem o lock da conta de proposito: prova que as primitivas sao atomicas por si (o lock da conta, usado
-- pela aplicacao, e coordenacao adicional).
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
BEGIN;
SET LOCAL nekkora.organization_id = :'o1';
WITH u AS (UPDATE auth_challenges SET consumed_at = clock_timestamp(), credential_hash = NULL, pending_password_hash = NULL
           WHERE challenge_id = :'ch2' AND consumed_at IS NULL AND invalidated_at IS NULL AND expires_at > clock_timestamp() AND attempts < 5
           RETURNING 1) SELECT count(*) AS n_desafio FROM u \gset
WITH u AS (UPDATE user_mfa_factors SET last_used_step = 200
           WHERE factor_id = :'f_x' AND confirmed_at IS NOT NULL AND disabled_at IS NULL AND (last_used_step IS NULL OR last_used_step < 200)
           RETURNING 1) SELECT count(*) AS n_passo FROM u \gset
WITH u AS (UPDATE user_mfa_recovery_codes SET used_at = clock_timestamp()
           WHERE user_id = :'x' AND code_hash = repeat('d', 64) AND used_at IS NULL AND invalidated_at IS NULL
           RETURNING 1) SELECT count(*) AS n_codigo FROM u \gset
\echo MARCADOR_SESSAO=:sessao desafio=:n_desafio passo=:n_passo codigo=:n_codigo
SELECT pg_sleep(:espera);
COMMIT;
SELECT pg_temp.afirma(:'n_desafio' = :'esperado' AND :'n_passo' = :'esperado' AND :'n_codigo' = :'esperado',
  format('sessao %s: desafio, passo TOTP e codigo consumidos %s vez(es) cada', :'sessao', :'esperado'));
