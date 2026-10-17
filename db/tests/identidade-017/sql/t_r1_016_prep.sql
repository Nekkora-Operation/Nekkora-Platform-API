-- Controle positivo da 016 (R1), preparacao - papel: nekkora_platform. Conta NAO vinculada com estado vivo: desafio,
-- codigos, fator confirmado e sessao - exatamente o que o reset operacional da 016 desfaz.
\pset pager off
SELECT pg_temp.nova_conta(:'o1', 'r016@ex.test');
BEGIN;
SELECT pg_temp.ctx(:'o1');
INSERT INTO auth_challenges (organization_id, user_id, token_hash, purpose, credential_hash, csrf_token, expires_at)
VALUES (:'o1', pg_temp.conta('r016@ex.test', :'o1'), pg_temp.h('R016/ch'), 1, 'h', pg_temp.csrf('R016/ch'), now() + interval '4 minutes');
INSERT INTO user_mfa_recovery_codes (organization_id, user_id, factor_id, code_hash)
SELECT f.organization_id, f.user_id, f.factor_id, pg_temp.h('R016/rc' || i) FROM user_mfa_factors f, generate_series(1, 3) i
 WHERE f.user_id = pg_temp.conta('r016@ex.test', :'o1');
COMMIT;
SELECT pg_temp.sessao_org(:'o1', pg_temp.conta('r016@ex.test', :'o1'), NULL, 'R016/sessao');
SELECT 'controle 016 preparacao: ok' AS resultado;
