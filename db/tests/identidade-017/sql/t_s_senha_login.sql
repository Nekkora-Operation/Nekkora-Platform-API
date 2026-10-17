-- Grupo S (R3) - papel: nekkora_platform, SQL direto. Conclusao do login (identity_login_complete) e confirmacao inicial:
-- finalidade, credencial vigente, re-hash, uso do fator/codigo e sessao num so comando; troca de senha.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
SELECT pg_temp.nova_identidade('s' || i || '@ex.test') FROM generate_series(1, 9) i;
CREATE FUNCTION pg_temp.passo(p_id UUID) RETURNS BIGINT LANGUAGE sql AS $$
    SELECT pg_temp.como(p_id); SELECT max(last_used_step) FROM identity_mfa_factors WHERE identity_id = p_id AND confirmed_at IS NOT NULL AND disabled_at IS NULL $$;
CREATE FUNCTION pg_temp.lc(p_tok TEXT, p_passo BIGINT, p_seed TEXT) RETURNS TEXT LANGUAGE sql AS $$
    SELECT o_status FROM identity_login_complete(p_tok, 'totp', p_passo, NULL, pg_temp.h(p_seed), pg_temp.csrf(p_seed), now() + interval '1 hour') $$;
CREATE FUNCTION pg_temp.ativas(p_id UUID) RETURNS BIGINT LANGUAGE sql AS $$
    SELECT pg_temp.como(p_id); SELECT count(*) FROM identity_sessions WHERE identity_id = p_id AND revoked_at IS NULL $$;
CREATE TEMP TABLE v AS SELECT pg_temp.identidade('s1@ex.test') AS s1, pg_temp.identidade('s2@ex.test') AS s2, pg_temp.identidade('s3@ex.test') AS s3,
    pg_temp.identidade('s4@ex.test') AS s4, pg_temp.identidade('s5@ex.test') AS s5, pg_temp.identidade('s8@ex.test') AS s8,
    pg_temp.identidade('s9@ex.test') AS s9;

-- S-1: login TOTP - desafio consumido (hashes limpos), passo avancado, sessao criada nao restrita, sem step-up
SELECT pg_temp.desafio((SELECT s1 FROM v), 'S-1', 1::SMALLINT);
SELECT pg_temp.passo((SELECT s1 FROM v)) AS p1 \gset
SELECT pg_temp.afirma(pg_temp.lc(pg_temp.tok('S-1'), :'p1'::BIGINT + 1, 'S-1/s') = 'SESSAO_CRIADA', 'S-1 sessao criada');
SELECT pg_temp.afirma((SELECT consumed_at IS NOT NULL AND credential_hash IS NULL FROM identity_auth_challenges WHERE token_hash = pg_temp.h('S-1'))
    AND pg_temp.passo((SELECT s1 FROM v)) = :'p1'::BIGINT + 1
    AND (SELECT recovery_used_at IS NULL AND mfa_step_up_at IS NULL AND mfa_verified_at IS NOT NULL FROM identity_sessions WHERE session_token_hash = pg_temp.h('S-1/s')),
    'S-1 efeitos: consumo, passo, sessao comum');
-- S-2: passo repetido -> FATOR_RECUSADO, sem efeito (o desafio continua vivo)
SELECT pg_temp.desafio((SELECT s1 FROM v), 'S-2', 1::SMALLINT);
SELECT pg_temp.afirma(pg_temp.lc(pg_temp.tok('S-2'), pg_temp.passo((SELECT s1 FROM v)), 'S-2/s') = 'FATOR_RECUSADO'
    AND (SELECT consumed_at IS NULL AND invalidated_at IS NULL FROM identity_auth_challenges WHERE token_hash = pg_temp.h('S-2'))
    AND (SELECT count(*) FROM identity_sessions WHERE session_token_hash = pg_temp.h('S-2/s')) = 0, 'S-2 passo repetido sem efeito');
-- S-3: finalidade - desafio de login (1) na confirmacao inicial e desafio de cadastro (2) no login
SELECT pg_temp.nova_identidade('s3b@ex.test', false) AS s3b \gset
SELECT pg_temp.desafio(:'s3b', 'S-3/cad', 2::SMALLINT);
SELECT identity_mfa_enroll_pending(pg_temp.tok('S-3/cad'), NULL, decode(repeat('5a', 36), 'hex'), decode(repeat('05', 12), 'hex'), 'k1') AS s3f \gset
SELECT pg_temp.desafio(:'s3b', 'S-3/login', 1::SMALLINT);
SELECT pg_temp.afirma((SELECT o_status FROM identity_mfa_confirm_first(pg_temp.tok('S-3/login'), :'s3f', pg_temp.codigos('S-3'), 1, pg_temp.h('S-3/s'), pg_temp.csrf('S-3/s'), now() + interval '1 hour')) = 'DESAFIO_INVALIDO'
    AND pg_temp.lc(pg_temp.tok('S-3/cad'), 1, 'S-3/s2') = 'DESAFIO_INVALIDO', 'S-3 cada funcao so consome a sua finalidade');
SELECT pg_temp.afirma((SELECT o_status FROM identity_mfa_confirm_first(pg_temp.tok('S-3/cad'), :'s3f', pg_temp.codigos('S-3'), 1, pg_temp.h('S-3/s3'), pg_temp.csrf('S-3/s3'), now() + interval '1 hour')) = 'SESSAO_CRIADA',
    'S-3 controle: o desafio de cadastro continuava vivo e a confirmacao inicial cria a sessao');
-- S-4: re-hash - credencial vigente + pendente -> troca, sessao criada
SELECT pg_temp.desafio((SELECT s4 FROM v), 'S-4', 1::SMALLINT, '$argon2id$v=19$m=65536$rehash-s4');
SELECT pg_temp.afirma(pg_temp.lc(pg_temp.tok('S-4'), pg_temp.passo((SELECT s4 FROM v)) + 1, 'S-4/s') = 'SESSAO_CRIADA'
    AND pg_temp.hash_vigente((SELECT s4 FROM v)) = '$argon2id$v=19$m=65536$rehash-s4', 'S-4 re-hash aplicado com a credencial vigente');
-- S-5: re-hash concorrente da mesma senha / prova obsoleta - A e B validados contra o mesmo hash; A conclui com re-hash;
-- B (com ou sem pendente) vira SENHA_OBSOLETA: desafio invalidado, hashes limpos, NENHUMA sessao, mesmo com o TOTP certo
SELECT pg_temp.desafio((SELECT s5 FROM v), 'S-5/a', 1::SMALLINT, '$argon2id$rehash-a');
SELECT pg_temp.desafio((SELECT s5 FROM v), 'S-5/b', 1::SMALLINT, '$argon2id$rehash-b');
SELECT pg_temp.desafio((SELECT s5 FROM v), 'S-5/c', 1::SMALLINT);
SELECT pg_temp.ativas((SELECT s5 FROM v)) AS s5_ativas \gset
SELECT pg_temp.afirma(pg_temp.lc(pg_temp.tok('S-5/a'), pg_temp.passo((SELECT s5 FROM v)) + 1, 'S-5/sa') = 'SESSAO_CRIADA', 'S-5 A conclui com re-hash');
SELECT pg_temp.afirma(pg_temp.lc(pg_temp.tok('S-5/b'), pg_temp.passo((SELECT s5 FROM v)) + 1, 'S-5/sb') = 'SENHA_OBSOLETA', 'S-5 B (re-hash concorrente) obsoleto');
SELECT pg_temp.afirma(pg_temp.lc(pg_temp.tok('S-5/c'), pg_temp.passo((SELECT s5 FROM v)) + 1, 'S-5/sc') = 'SENHA_OBSOLETA', 'S-5 C (sem pendente) obsoleto: a credencial e conferida sempre');
SELECT pg_temp.afirma((SELECT bool_and(invalidated_at IS NOT NULL AND credential_hash IS NULL AND pending_password_hash IS NULL) FROM identity_auth_challenges
                        WHERE token_hash IN (pg_temp.h('S-5/b'), pg_temp.h('S-5/c')))
    AND pg_temp.ativas((SELECT s5 FROM v)) = :'s5_ativas'::BIGINT + 1
    AND pg_temp.hash_vigente((SELECT s5 FROM v)) = '$argon2id$rehash-a', 'S-5 so A gerou sessao; B e C invalidados; credencial e a de A');
\! sh /tmp/e.sh S-5
-- S-6: recuperacao - sessao restrita, codigo usado apontando para ela; codigo ja usado nao serve de novo
SELECT pg_temp.nova_sessao_id((SELECT s2 FROM v), 'S-6', false, true) AS s6 \gset
SELECT pg_temp.afirma((SELECT recovery_used_at IS NOT NULL AND recovery_cleared_at IS NULL FROM identity_sessions WHERE identity_session_id = :'s6')
    AND (SELECT count(*) FROM identity_mfa_recovery_codes WHERE identity_id = (SELECT s2 FROM v) AND used_in_identity_session_id = :'s6') = 1, 'S-6 sessao restrita e codigo apontando');
SELECT pg_temp.desafio((SELECT s2 FROM v), 'S-6/reuso', 1::SMALLINT);
SELECT pg_temp.afirma((SELECT o_status FROM identity_login_complete(pg_temp.tok('S-6/reuso'), 'recuperacao', NULL,
        (SELECT code_hash FROM identity_mfa_recovery_codes WHERE used_in_identity_session_id = :'s6'), pg_temp.h('S-6/r'), pg_temp.csrf('S-6/r'), now() + interval '1 hour')) = 'FATOR_RECUSADO',
    'S-6 codigo ja usado recusado');
-- S-8: atomicidade - erro na criacao da sessao (hash de sessao repetido) desfaz TUDO: desafio vivo, passo intacto
SELECT pg_temp.desafio((SELECT s8 FROM v), 'S-8', 1::SMALLINT, '$argon2id$rehash-s8');
SELECT pg_temp.passo((SELECT s8 FROM v)) AS p8 \gset
SELECT pg_temp.espera_erro(format('SELECT * FROM identity_login_complete(%L, ''totp'', %s, NULL, %L, %L, now() + interval ''1 hour'')',
    pg_temp.tok('S-8'), :'p8'::BIGINT + 1, pg_temp.h('primeira/' || (SELECT s8 FROM v)), pg_temp.csrf('S-8')), '23505', 'session_token_hash');
SELECT pg_temp.afirma((SELECT consumed_at IS NULL AND credential_hash IS NOT NULL AND pending_password_hash IS NOT NULL FROM identity_auth_challenges WHERE token_hash = pg_temp.h('S-8'))
    AND pg_temp.passo((SELECT s8 FROM v)) = :'p8'::BIGINT AND pg_temp.hash_vigente((SELECT s8 FROM v)) = '$argon2id$id$s8@ex.test', 'S-8 rollback integral');
-- S-9: troca de senha - credencial nova; desafios pendentes invalidados (hashes limpos) e incapazes de concluir; demais sessoes revogadas
SELECT pg_temp.nova_sessao_id((SELECT s9 FROM v), 'S-9/a', true);
SELECT pg_temp.nova_sessao_id((SELECT s9 FROM v), 'S-9/b');
SELECT pg_temp.desafio((SELECT s9 FROM v), 'S-9/pendente', 1::SMALLINT, '$argon2id$rehash-s9');
SELECT pg_temp.afirma(identity_password_change(pg_temp.tok('S-9/a'), pg_temp.hash_vigente((SELECT s9 FROM v)), '$argon2id$s9-nova'), 'S-9 troca concluida');
SELECT pg_temp.afirma(pg_temp.hash_vigente((SELECT s9 FROM v)) = '$argon2id$s9-nova'
    AND (SELECT invalidated_at IS NOT NULL AND credential_hash IS NULL AND pending_password_hash IS NULL FROM identity_auth_challenges WHERE token_hash = pg_temp.h('S-9/pendente'))
    AND (SELECT revoked_at IS NOT NULL FROM identity_sessions WHERE session_token_hash = pg_temp.h('S-9/b'))
    AND (SELECT revoked_at IS NULL FROM identity_sessions WHERE session_token_hash = pg_temp.h('S-9/a')), 'S-9 efeitos da troca');
SELECT pg_temp.afirma(pg_temp.lc(pg_temp.tok('S-9/pendente'), pg_temp.passo((SELECT s9 FROM v)) + 1, 'S-9/s') = 'DESAFIO_INVALIDO', 'S-9 desafio anterior a troca nao conclui o login');
-- S-11: privilegios - a aplicacao nao cria sessao, nao consome desafio e nao usa codigo diretamente
SELECT pg_temp.espera_erro(format('UPDATE identity_auth_challenges SET consumed_at = now(), credential_hash = NULL WHERE token_hash = %L', pg_temp.h('S-2')), '42501', 'permission denied');
SELECT pg_temp.espera_erro('UPDATE identity_mfa_recovery_codes SET used_at = now()', '42501', 'permission denied');
SELECT pg_temp.espera_erro(format('INSERT INTO identity_sessions (identity_id, session_token_hash, expires_at, mfa_verified_at, csrf_token) VALUES (%L, %L, now() + interval ''1 hour'', now(), %L)',
    (SELECT s1 FROM v), pg_temp.h('S-11'), pg_temp.csrf('S-11')), '42501', 'permission denied');
\! sh /tmp/e.sh S
SELECT 'grupo S: ok' AS resultado;
