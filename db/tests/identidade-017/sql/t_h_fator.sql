-- Grupo H (secao 3.3.1) - papel: nekkora_platform, SQL direto. A parte "como dono / superusuario" esta em t_h_dono.sql.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
-- H-1: primeiro fator pelo caminho legitimo
SELECT pg_temp.nova_identidade('h1@ex.test');
SELECT pg_temp.afirma((SELECT count(*) FROM identity_mfa_factors WHERE identity_id = pg_temp.identidade('h1@ex.test') AND confirmed_at IS NOT NULL) = 1
                  AND (SELECT count(*) FROM identity_mfa_recovery_codes WHERE identity_id = pg_temp.identidade('h1@ex.test')) = 8, 'H-1 um confirmado, oito codigos');
-- H-2: INSERT direto, pendente ou confirmado
SELECT pg_temp.espera_erro(format('INSERT INTO identity_mfa_factors (identity_id, factor_type, secret_ciphertext, secret_nonce, key_id) VALUES (%L, 1, decode(repeat(''aa'', 36), ''hex''), decode(repeat(''01'', 12), ''hex''), ''k1'')',
    pg_temp.identidade('h1@ex.test')), '42501', 'permission denied');
SELECT pg_temp.espera_erro(format('INSERT INTO identity_mfa_factors (identity_id, factor_type, secret_ciphertext, secret_nonce, key_id, confirmed_at) VALUES (%L, 1, decode(repeat(''aa'', 36), ''hex''), decode(repeat(''01'', 12), ''hex''), ''k1'', now())',
    pg_temp.identidade('h1@ex.test')), '42501', 'permission denied');
-- H-3: o primeiro fator tambem nao e confirmado por escrita direta
SELECT pg_temp.nova_identidade('h3@ex.test', false);
SELECT identity_mfa_enroll_pending(pg_temp.desafio_cadastro(pg_temp.identidade('h3@ex.test'), 'H-3'), NULL, decode(repeat('aa', 36), 'hex'), decode(repeat('01', 12), 'hex'), 'k1') AS h3 \gset
SELECT pg_temp.espera_erro(format('UPDATE identity_mfa_factors SET confirmed_at = now() WHERE factor_id = %L', :'h3'), '42501', 'permission denied');
-- R2: os cenarios seguintes leem e escrevem linhas de h1 diretamente: contexto da identidade h1
SELECT pg_temp.como(pg_temp.identidade('h1@ex.test'));
-- H-4: anular a confirmacao
SELECT pg_temp.espera_erro(format('UPDATE identity_mfa_factors SET confirmed_at = NULL WHERE identity_id = %L', pg_temp.identidade('h1@ex.test')), '42501', 'permission denied');
-- H-8: passo TOTP so aumenta
UPDATE identity_mfa_factors SET last_used_step = 100 WHERE identity_id = pg_temp.identidade('h1@ex.test') AND confirmed_at IS NOT NULL;
SELECT pg_temp.espera_erro(format('UPDATE identity_mfa_factors SET last_used_step = 50 WHERE identity_id = %L AND confirmed_at IS NOT NULL', pg_temp.identidade('h1@ex.test')), '23514', 'so aumenta');
-- H-9: DELETE
SELECT pg_temp.espera_erro(format('DELETE FROM identity_mfa_factors WHERE identity_id = %L', pg_temp.identidade('h1@ex.test')), '42501', 'permission denied');
-- H-10: identidade com fator confirmado: o desafio de cadastro nao cria pendente, e confirm_first recusa um pendente
SELECT pg_temp.afirma(identity_mfa_enroll_pending(pg_temp.desafio_cadastro(pg_temp.identidade('h1@ex.test'), 'H-10'), NULL,
    decode(repeat('aa', 36), 'hex'), decode(repeat('01', 12), 'hex'), 'k1') IS NULL, 'H-10 desafio de cadastro nao cria pendente para quem ja confirmou');
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('h1@ex.test'), 'H-10/sessao', true);
SELECT identity_mfa_enroll_pending(NULL, pg_temp.tok('H-10/sessao'), decode(repeat('bb', 36), 'hex'), decode(repeat('01', 12), 'hex'), 'k1') AS h10 \gset
SELECT pg_temp.afirma((SELECT o_status FROM identity_mfa_confirm_first(pg_temp.tok('H-10'), :'h10', pg_temp.codigos('H-10'), 1, pg_temp.h('H-10/s'), pg_temp.csrf('H-10/s'), now() + interval '1 hour')) = 'RECUSADO',
                      'H-10 confirm_first recusa: nao e o primeiro');
-- H-11: identidade cujo unico confirmado foi substituido (d4, grupo D): a condicao "nunca teve" e estavel
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('d4@ex.test'), 'H-11/sessao', true);
SELECT identity_mfa_enroll_pending(NULL, pg_temp.tok('H-11/sessao'), decode(repeat('cc', 36), 'hex'), decode(repeat('01', 12), 'hex'), 'k1') AS h11 \gset
SELECT pg_temp.afirma((SELECT o_status FROM identity_mfa_confirm_first(pg_temp.desafio_cadastro(pg_temp.identidade('d4@ex.test'), 'H-11'), :'h11', pg_temp.codigos('H-11'), 1,
                         pg_temp.h('H-11/s'), pg_temp.csrf('H-11/s'), now() + interval '1 hour')) = 'RECUSADO', 'H-11 confirm_first recusa depois de uma troca');
-- H-12: a sequencia da responsavel - anular o antigo e confirmar outro - falha nos dois passos
SELECT pg_temp.espera_erro(format('UPDATE identity_mfa_factors SET confirmed_at = NULL WHERE identity_id = %L AND confirmed_at IS NOT NULL AND disabled_at IS NULL',
    pg_temp.identidade('h1@ex.test')), '42501', 'permission denied');
SELECT pg_temp.espera_erro(format('UPDATE identity_mfa_factors SET confirmed_at = now() WHERE factor_id = %L', :'h10'), '42501', 'permission denied');
-- H-14: codigos: nenhum INSERT direto
SELECT pg_temp.espera_erro(format('INSERT INTO identity_mfa_recovery_codes (identity_id, factor_id, code_hash) SELECT identity_id, factor_id, %L FROM identity_mfa_factors WHERE identity_id = %L AND confirmed_at IS NOT NULL',
    pg_temp.h('H-14'), pg_temp.identidade('h1@ex.test')), '42501', 'permission denied');
-- H-15: codigo usado nao volta; invalidacao e hash fora do alcance da aplicacao (contexto de d7, dona do codigo usado)
SELECT pg_temp.como(pg_temp.identidade('d7@ex.test'));
SELECT pg_temp.espera_erro(format('UPDATE identity_mfa_recovery_codes SET used_at = NULL, used_in_identity_session_id = NULL WHERE identity_id = %L AND used_at IS NOT NULL',
    pg_temp.identidade('d7@ex.test')), '42501', 'permission denied');   -- R3: sem UPDATE nos codigos; a trigger esta em t_h_dono
SELECT pg_temp.espera_erro(format('UPDATE identity_mfa_recovery_codes SET invalidated_at = NULL WHERE identity_id = %L', pg_temp.identidade('d7@ex.test')), '42501', 'permission denied');
SELECT pg_temp.espera_erro(format('UPDATE identity_mfa_recovery_codes SET code_hash = %L WHERE identity_id = %L', pg_temp.h('x'), pg_temp.identidade('h1@ex.test')), '42501', 'permission denied');
-- H-16: SET ROLE (tambem em C'-7)
SELECT pg_temp.espera_erro('SET ROLE nekkora_identity_owner', '42501', 'permission denied');
\! sh /tmp/e.sh H
SELECT 'grupo H (aplicacao): ok' AS resultado;
