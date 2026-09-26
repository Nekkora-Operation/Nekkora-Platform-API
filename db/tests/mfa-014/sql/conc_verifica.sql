-- conc_verifica - papel: nekkora (so leitura). Depois das duas sessoes: exatamente um consumo de cada.
\pset pager off
SELECT pg_temp.afirma((SELECT consumed_at IS NOT NULL AND credential_hash IS NULL FROM auth_challenges WHERE challenge_id = :'ch2'), 'desafio consumido uma vez');
SELECT pg_temp.afirma((SELECT last_used_step FROM user_mfa_factors WHERE factor_id = :'f_x') = 200, 'passo TOTP 200 aceito uma vez');
SELECT pg_temp.afirma((SELECT count(*) FROM user_mfa_recovery_codes WHERE user_id = :'x' AND code_hash = repeat('d', 64) AND used_at IS NOT NULL) = 1,
  'codigo de recuperacao usado uma vez');
