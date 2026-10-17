-- f03 - papel: nekkora (superusuario), DECLARADO. So o que nenhum caminho da aplicacao produz de proposito:
--   * sessao de identidade ja expirada (A-8: created_at no passado);
--   * sessao de identidade de quem NAO tem fator (paula, A-9): desde a R3 a sessao so nasce por funcao, que exige fator;
--   * sessao restrita com marca de recuperacao de 20 minutos atras (D-2: janela vencida);
--   * suspensao de O3 (grupo L).
-- O superusuario ignora privilegios, mas as triggers continuam valendo (insercao sem step-up; step-up depois).
\pset pager off
INSERT INTO identity_sessions (identity_id, session_token_hash, created_at, expires_at, last_seen_at, mfa_verified_at, csrf_token)
VALUES (pg_temp.identidade('ana@ex.test'), pg_temp.h('sessao/ana-expirada'), now() - interval '13 hours', now() - interval '1 hour',
        now() - interval '13 hours', now() - interval '13 hours', pg_temp.csrf('sessao/ana-expirada'));
INSERT INTO identity_sessions (identity_id, session_token_hash, expires_at, mfa_verified_at, csrf_token)
VALUES (pg_temp.identidade('paula@ex.test'), pg_temp.h('sessao/paula@ex.test'), now() + interval '11 hours', now(), pg_temp.csrf('sessao/paula@ex.test'));
UPDATE identity_sessions SET mfa_step_up_at = now() WHERE session_token_hash = pg_temp.h('sessao/paula@ex.test');
INSERT INTO identity_sessions (identity_id, session_token_hash, expires_at, mfa_verified_at, recovery_used_at, csrf_token)
VALUES (pg_temp.identidade('d2@ex.test'), pg_temp.h('D-2'), now() + interval '11 hours', now(), now() - interval '20 minutes', pg_temp.csrf('D-2'));
UPDATE organizations SET status = 2 WHERE organization_id = :'o3';
SELECT 'fixture superusuario: ok' AS resultado;
