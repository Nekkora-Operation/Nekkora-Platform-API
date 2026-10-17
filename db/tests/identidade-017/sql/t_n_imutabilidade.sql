-- Grupo N (R1) - papel: nekkora_platform, SQL direto. A excecao de estado terminal libera SO a transicao terminal e a
-- limpeza prevista; campos protegidos sao RECUSADOS (nao encaminhados a conferencia de vinculo), em conta nao vinculada
-- tambem. Controles positivos: as escritas legitimas no formato do Java atual continuam aceitas.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
SELECT pg_temp.nova_conta(:'o1', 'n1@ex.test');
SELECT pg_temp.nova_conta(:'o1', 'n2@ex.test');
-- conta vinculada SEM credencial local (nasce pela criacao de organizacao): alvo do movimento de credencial
SELECT pg_temp.nova_identidade('n3@ex.test');
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('n3@ex.test'), 'N/n3', true);
SELECT identity_create_organization(pg_temp.tok('N/n3'), 'Org N3', NULL) AS org_n3 \gset
SELECT user_id AS n3_criada FROM identity_org_session_target(pg_temp.tok('N/n3'), :'org_n3') \gset
CREATE TEMP TABLE v AS SELECT pg_temp.conta('n1@ex.test', :'o1') AS n1, pg_temp.conta('n2@ex.test', :'o1') AS n2;

-- estado vivo de n1: tokens, desafio, fator pendente (o confirmado e desabilitado antes, como o cadastro faz), codigo
BEGIN;
SELECT pg_temp.ctx(:'o1');
INSERT INTO password_reset_tokens (user_id, token_hash, expires_at) SELECT n1, pg_temp.h('N/prt' || i), now() + interval '30 minutes' FROM v, generate_series(1, 6) i;
INSERT INTO email_verification_tokens (user_id, target_email, token_hash, expires_at) SELECT n1, 'n1@ex.test', pg_temp.h('N/evt' || i), now() + interval '1 day' FROM v, generate_series(1, 4) i;
INSERT INTO auth_challenges (organization_id, user_id, token_hash, purpose, credential_hash, csrf_token, expires_at)
SELECT :'o1', n1, pg_temp.h('N/ch' || i), 1, 'h', pg_temp.csrf('N/ch' || i), now() + interval '4 minutes' FROM v, generate_series(1, 6) i;
INSERT INTO user_mfa_recovery_codes (organization_id, user_id, factor_id, code_hash)
SELECT f.organization_id, f.user_id, f.factor_id, pg_temp.h('N/rc' || i) FROM user_mfa_factors f, v, generate_series(1, 3) i WHERE f.user_id = v.n1;
UPDATE user_mfa_factors SET disabled_at = now() WHERE user_id = (SELECT n1 FROM v) AND disabled_at IS NULL;
INSERT INTO user_mfa_factors (organization_id, user_id, factor_type, secret_ciphertext, secret_nonce, key_id)
SELECT :'o1', n1, 1, decode(repeat('ee', 36), 'hex'), decode(repeat('09', 12), 'hex'), 'k1' FROM v;
COMMIT;
CREATE FUNCTION pg_temp.q(p TEXT) RETURNS TEXT LANGUAGE sql AS $$ SELECT format('SELECT pg_temp.ctx(%L); %s', '17000000-0000-0000-0000-000000000001', p) $$;

-- N-1..N-7: tokens - dono, hash, prazo e e-mail alvo imutaveis tambem junto com consumo/revogacao; terminal nao muda
SELECT pg_temp.espera_erro(pg_temp.q(format('UPDATE password_reset_tokens SET revoked_at = now(), user_id = %L WHERE token_hash = %L', (SELECT n2 FROM v), pg_temp.h('N/prt1'))), '23514', 'sao imutaveis');
SELECT pg_temp.espera_erro(pg_temp.q(format('UPDATE password_reset_tokens SET consumed_at = now(), token_hash = %L WHERE token_hash = %L', pg_temp.h('N/novo'), pg_temp.h('N/prt1'))), '23514', 'sao imutaveis');
SELECT pg_temp.espera_erro(pg_temp.q(format('UPDATE password_reset_tokens SET revoked_at = now(), expires_at = expires_at + interval ''1 day'' WHERE token_hash = %L', pg_temp.h('N/prt1'))), '23514', 'sao imutaveis');
SELECT pg_temp.espera_erro(pg_temp.q(format('UPDATE email_verification_tokens SET consumed_at = now(), target_email = ''outro@ex.test'' WHERE token_hash = %L', pg_temp.h('N/evt1'))), '23514', 'sao imutaveis');
SELECT pg_temp.espera_erro(pg_temp.q(format('UPDATE email_verification_tokens SET consumed_at = now(), user_id = %L WHERE token_hash = %L', (SELECT n2 FROM v), pg_temp.h('N/evt1'))), '23514', 'sao imutaveis');
SELECT pg_temp.espera_erro(pg_temp.q(format('UPDATE password_reset_tokens SET user_id = %L WHERE token_hash = %L', (SELECT n2 FROM v), pg_temp.h('N/prt1'))), '23514', 'sao imutaveis');
SELECT pg_temp.executa(pg_temp.q(format('UPDATE password_reset_tokens SET revoked_at = now() WHERE token_hash = %L', pg_temp.h('N/prt6'))));
SELECT pg_temp.espera_erro(pg_temp.q(format('UPDATE password_reset_tokens SET revoked_at = NULL WHERE token_hash = %L', pg_temp.h('N/prt6'))), '23514', 'consumido ou revogado e imutavel');
SELECT pg_temp.espera_erro(pg_temp.q(format('UPDATE password_reset_tokens SET consumed_at = now() WHERE token_hash = %L', pg_temp.h('N/prt6'))), '23514', 'consumido ou revogado e imutavel');
-- N-8/N-9: a credencial nao muda de conta - nem para conta vinculada sem credencial, nem para outra nao vinculada
SELECT pg_temp.espera_erro(pg_temp.q(format('UPDATE user_credentials SET user_id = %L WHERE user_id = %L', :'n3_criada', (SELECT n1 FROM v))), '23514', 'nao muda de conta');
SELECT pg_temp.espera_erro(pg_temp.q(format('UPDATE user_credentials SET user_id = %L WHERE user_id = %L', (SELECT n2 FROM v), (SELECT n1 FROM v))), '23514', 'nao muda de conta');
-- N-10..N-13: transicao terminal com outra coluna junto -> recusada (a excecao nao vira porta)
SELECT pg_temp.espera_erro(pg_temp.q(format('UPDATE auth_challenges SET invalidated_at = now(), credential_hash = NULL, attempts = attempts + 1 WHERE token_hash = %L', pg_temp.h('N/ch1'))), '23514', 'alteradas junto: attempts');
SELECT pg_temp.espera_erro(pg_temp.q(format('UPDATE auth_challenges SET invalidated_at = now(), credential_hash = ''outro'' WHERE token_hash = %L', pg_temp.h('N/ch1'))), '23514', 'alteradas junto: credential_hash');
SELECT pg_temp.espera_erro(pg_temp.q(format('UPDATE user_mfa_factors SET disabled_at = now(), key_id = ''k9'' WHERE user_id = %L AND disabled_at IS NULL', (SELECT n1 FROM v))), '23514', 'alteradas junto: key_id');
SELECT pg_temp.espera_erro(pg_temp.q(format('UPDATE user_mfa_factors SET disabled_at = now(), secret_ciphertext = decode(repeat(''00'', 36), ''hex'') WHERE user_id = %L AND disabled_at IS NULL', (SELECT n1 FROM v))), '23514', 'alteradas junto: secret_ciphertext');
\! sh /tmp/e.sh N-negativos

-- N-P: controles positivos, no formato exato do Java atual (AuthService, MfaRepository, AccountRepository)
BEGIN;
SELECT pg_temp.ctx(:'o1');
UPDATE password_reset_tokens SET revoked_at = clock_timestamp() WHERE token_hash = pg_temp.h('N/prt2') AND consumed_at IS NULL AND revoked_at IS NULL;
UPDATE password_reset_tokens SET consumed_at = clock_timestamp() WHERE token_hash = pg_temp.h('N/prt3') AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > clock_timestamp();
UPDATE email_verification_tokens SET consumed_at = clock_timestamp() WHERE token_hash = pg_temp.h('N/evt2') AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > clock_timestamp();
UPDATE auth_challenges SET attempts = attempts + 1 WHERE token_hash = pg_temp.h('N/ch2') AND consumed_at IS NULL AND invalidated_at IS NULL;
UPDATE auth_challenges SET consumed_at = clock_timestamp(), credential_hash = NULL, pending_password_hash = NULL WHERE token_hash = pg_temp.h('N/ch2');
UPDATE auth_challenges SET invalidated_at = clock_timestamp(), credential_hash = NULL, pending_password_hash = NULL WHERE token_hash = pg_temp.h('N/ch3');
UPDATE user_mfa_factors SET secret_ciphertext = decode(repeat('ef', 36), 'hex'), secret_nonce = decode(repeat('0a', 12), 'hex'), key_id = 'k2'
 WHERE user_id = (SELECT n1 FROM v) AND disabled_at IS NULL AND confirmed_at IS NULL;
UPDATE user_mfa_factors SET confirmed_at = clock_timestamp(), last_used_step = 7 WHERE user_id = (SELECT n1 FROM v) AND disabled_at IS NULL AND confirmed_at IS NULL;
UPDATE user_mfa_factors SET last_used_step = 8 WHERE user_id = (SELECT n1 FROM v) AND disabled_at IS NULL AND confirmed_at IS NOT NULL;
UPDATE user_mfa_recovery_codes SET used_at = clock_timestamp() WHERE code_hash = pg_temp.h('N/rc1');
UPDATE user_mfa_recovery_codes SET invalidated_at = clock_timestamp() WHERE code_hash = pg_temp.h('N/rc2');
UPDATE user_credentials SET password_hash = '$argon2id$conta$n1-nova', password_changed_at = clock_timestamp() WHERE user_id = (SELECT n1 FROM v);
SELECT pg_temp.afirma((SELECT count(*) FROM password_reset_tokens WHERE user_id = (SELECT n1 FROM v) AND (revoked_at IS NOT NULL OR consumed_at IS NOT NULL)) = 3
    AND (SELECT consumed_at IS NOT NULL FROM email_verification_tokens WHERE token_hash = pg_temp.h('N/evt2'))
    AND (SELECT consumed_at IS NOT NULL AND attempts = 1 AND credential_hash IS NULL FROM auth_challenges WHERE token_hash = pg_temp.h('N/ch2'))
    AND (SELECT invalidated_at IS NOT NULL FROM auth_challenges WHERE token_hash = pg_temp.h('N/ch3'))
    AND (SELECT last_used_step = 8 FROM user_mfa_factors WHERE user_id = (SELECT n1 FROM v) AND disabled_at IS NULL)
    AND (SELECT count(*) FROM user_mfa_recovery_codes WHERE user_id = (SELECT n1 FROM v) AND (used_at IS NOT NULL OR invalidated_at IS NOT NULL)) = 2
    AND (SELECT password_hash FROM user_credentials WHERE user_id = (SELECT n1 FROM v)) = '$argon2id$conta$n1-nova', 'N-P escritas legitimas do Java atual aceitas');
COMMIT;
\! sh /tmp/e.sh N-positivos
-- N-V: a vinculacao (DC-17.12) continua fechando a entrada de uma conta com estado vivo, sob a regra estrita
SELECT pg_temp.nova_identidade('n2@ex.test');
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('n2@ex.test'), 'N/n2', true);
BEGIN;
SELECT pg_temp.ctx(:'o1');
INSERT INTO auth_challenges (organization_id, user_id, token_hash, purpose, credential_hash, csrf_token, expires_at)
SELECT :'o1', n2, pg_temp.h('N/n2ch'), 1, 'h', pg_temp.csrf('N/n2ch'), now() + interval '4 minutes' FROM v;
INSERT INTO password_reset_tokens (user_id, token_hash, expires_at) SELECT n2, pg_temp.h('N/n2prt'), now() + interval '30 minutes' FROM v;
INSERT INTO email_verification_tokens (user_id, target_email, token_hash, expires_at) SELECT n2, 'n2@ex.test', pg_temp.h('N/n2evt'), now() + interval '1 day' FROM v;
INSERT INTO user_mfa_recovery_codes (organization_id, user_id, factor_id, code_hash)
SELECT f.organization_id, f.user_id, f.factor_id, pg_temp.h('N/n2rc') FROM user_mfa_factors f, v WHERE f.user_id = v.n2;
COMMIT;
SELECT pg_temp.sessao_org(:'o1', (SELECT n2 FROM v), NULL, 'N/n2sessao');
SELECT pg_temp.afirma(pg_temp.vincula(pg_temp.identidade('n2@ex.test'), 'N/n2', (SELECT n2 FROM v), :'o1', 'N/n2vinculo'), 'N-V vinculacao com estado vivo concluida');
\! sh /tmp/e.sh N-V
SELECT 'grupo N: ok' AS resultado;
