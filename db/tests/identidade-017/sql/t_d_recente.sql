-- Grupo D (DC-17.13) - papel: nekkora_platform. Prova de autenticacao recente lida da linha da sessao; T0 e T1.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
CREATE FUNCTION pg_temp.secret(p TEXT) RETURNS BYTEA LANGUAGE sql AS $$ SELECT substring(sha256(convert_to(p, 'UTF8')) || sha256(convert_to(p || '2', 'UTF8')) FROM 1 FOR 36) $$;
-- fator pendente criado por uma sessao auxiliar valida (step-up agora): o cenario isola a sessao em teste
CREATE FUNCTION pg_temp.pendente(p_identity UUID, p_seed TEXT) RETURNS UUID LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_temp.nova_sessao_id(p_identity, p_seed || '/aux', true);
    RETURN identity_mfa_enroll_pending(NULL, pg_temp.tok(p_seed || '/aux'), pg_temp.secret(p_seed), decode(repeat('03', 12), 'hex'), 'k1');
END;
$$;
CREATE FUNCTION pg_temp.estado_fator(p_identity UUID) RETURNS TEXT LANGUAGE sql AS $$
    SELECT pg_temp.como(p_identity);
    SELECT count(*) FILTER (WHERE confirmed_at IS NOT NULL AND disabled_at IS NULL) || 'c/'
        || count(*) FILTER (WHERE confirmed_at IS NULL AND disabled_at IS NULL) || 'p/'
        || (SELECT count(*) FROM identity_mfa_recovery_codes r WHERE r.identity_id = p_identity AND r.used_at IS NULL AND r.invalidated_at IS NULL) || 'v'
      FROM identity_mfa_factors WHERE identity_id = p_identity $$;

-- identidades d1..d9 criadas na f02; a sessao D-2 (recuperacao de 20 min atras) vem da f03

-- D-1: recovery_used_at dentro de 15 min -> permitido (e a propria sessao restrita cria o pendente)
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('d1@ex.test'), 'D-1', false, true);
SELECT pg_temp.afirma(identity_mfa_replace(pg_temp.tok('D-1'),
    identity_mfa_enroll_pending(NULL, pg_temp.tok('D-1'), pg_temp.secret('D-1'), decode(repeat('03', 12), 'hex'), 'k1'), pg_temp.codigos('D-1/novos')), 'D-1 permitido');
SELECT pg_temp.afirma(pg_temp.estado_fator(pg_temp.identidade('d1@ex.test')) = '1c/0p/8v', 'D-1 estado final 1 confirmado, 8 codigos novos');
\! sh /tmp/e.sh D-1
-- D-2: recovery_used_at fora de 15 min (retroativo e aceito: so encurta a janela) -> recusado
SELECT pg_temp.afirma(identity_mfa_enroll_pending(NULL, pg_temp.tok('D-2'), pg_temp.secret('D-2'), decode(repeat('03', 12), 'hex'), 'k1') IS NULL, 'D-2 sessao vencida nao cadastra pendente');
SELECT pg_temp.afirma(NOT identity_mfa_replace(pg_temp.tok('D-2'), pg_temp.pendente(pg_temp.identidade('d2@ex.test'), 'D-2'), pg_temp.codigos('D-2/n')), 'D-2 recusado');
SELECT pg_temp.afirma(pg_temp.estado_fator(pg_temp.identidade('d2@ex.test')) = '1c/1p/8v', 'D-2 sem efeito');
\! sh /tmp/e.sh D-2
-- D-3: sem step-up e sem recuperacao -> recusado
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('d3@ex.test'), 'D-3');
SELECT pg_temp.afirma(NOT identity_mfa_replace(pg_temp.tok('D-3'), pg_temp.pendente(pg_temp.identidade('d3@ex.test'), 'D-3'), pg_temp.codigos('D-3/n')), 'D-3 recusado');
SELECT pg_temp.afirma(pg_temp.estado_fator(pg_temp.identidade('d3@ex.test')) = '1c/1p/8v', 'D-3 sem efeito');
\! sh /tmp/e.sh D-3
-- D-4: step-up dentro de 5 min -> permitido
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('d4@ex.test'), 'D-4', true);
SELECT pg_temp.afirma(identity_mfa_replace(pg_temp.tok('D-4'), pg_temp.pendente(pg_temp.identidade('d4@ex.test'), 'D-4'), pg_temp.codigos('D-4/n')), 'D-4 permitido');
SELECT pg_temp.afirma(pg_temp.estado_fator(pg_temp.identidade('d4@ex.test')) = '1c/0p/8v', 'D-4 estado final');
\! sh /tmp/e.sh D-4
-- D-5: step-up fora de 5 min -> recusado
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('d5@ex.test'), 'D-5');
UPDATE identity_sessions SET mfa_step_up_at = now() - interval '10 minutes' WHERE identity_session_id = pg_temp.sessao_id('D-5');
SELECT pg_temp.afirma(NOT identity_mfa_replace(pg_temp.tok('D-5'), pg_temp.pendente(pg_temp.identidade('d5@ex.test'), 'D-5'), pg_temp.codigos('D-5/n')), 'D-5 recusado');
SELECT pg_temp.afirma(pg_temp.estado_fator(pg_temp.identidade('d5@ex.test')) = '1c/1p/8v', 'D-5 sem efeito');
\! sh /tmp/e.sh D-5
-- D-6: sessao revogada com step-up valido -> recusado
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('d6@ex.test'), 'D-6', true);
UPDATE identity_sessions SET revoked_at = now() WHERE identity_session_id = pg_temp.sessao_id('D-6');
SELECT pg_temp.afirma(NOT identity_mfa_replace(pg_temp.tok('D-6'), pg_temp.pendente(pg_temp.identidade('d6@ex.test'), 'D-6'), pg_temp.codigos('D-6/n')), 'D-6 recusado');
SELECT pg_temp.afirma(pg_temp.estado_fator(pg_temp.identidade('d6@ex.test')) = '1c/1p/8v', 'D-6 sem efeito');
\! sh /tmp/e.sh D-6
-- D-7: T0 (R3: dentro de identity_login_complete, no modo recuperacao): a sessao restrita nasce consumindo um codigo; as
-- demais sessoes de identidade sao revogadas e os demais desafios invalidados. Codigos restantes e fator antigo CONTINUAM.
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('d7@ex.test'), 'D-7/outra');
SELECT pg_temp.desafio(pg_temp.identidade('d7@ex.test'), 'D-7/pendente', 1::SMALLINT);
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('d7@ex.test'), 'D-7', false, true);
SELECT pg_temp.afirma(pg_temp.estado_fator(pg_temp.identidade('d7@ex.test')) = '1c/0p/7v', 'D-7 em T0: fator antigo e 7 codigos restantes preservados');
SELECT pg_temp.afirma((SELECT count(*) FROM identity_sessions WHERE identity_id = pg_temp.identidade('d7@ex.test') AND revoked_at IS NULL) = 1,
                      'D-7 em T0: so a sessao restrita ativa');
SELECT pg_temp.afirma((SELECT invalidated_at IS NOT NULL AND credential_hash IS NULL FROM identity_auth_challenges WHERE token_hash = pg_temp.h('D-7/pendente')),
                      'D-7 em T0: desafio pendente invalidado, hashes limpos');
SELECT pg_temp.afirma((SELECT used_in_identity_session_id = pg_temp.sessao_id('D-7') FROM identity_mfa_recovery_codes
                        WHERE identity_id = pg_temp.identidade('d7@ex.test') AND used_at IS NOT NULL), 'D-7 em T0: codigo aponta a sessao restrita');
\! sh /tmp/e.sh D-7
-- D-8: T1 (identity_mfa_replace pela sessao restrita): antigo desabilitado, TODOS os antigos invalidados, novos emitidos,
-- restricao levantada, demais sessoes revogadas
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('d7@ex.test'), 'D-8/outra');
SELECT pg_temp.afirma(identity_mfa_replace(pg_temp.tok('D-7'),
    identity_mfa_enroll_pending(NULL, pg_temp.tok('D-7'), pg_temp.secret('D-8'), decode(repeat('03', 12), 'hex'), 'k1'), pg_temp.codigos('D-8/novos', 10)), 'D-8 T1 concluido');
SELECT pg_temp.afirma(pg_temp.estado_fator(pg_temp.identidade('d7@ex.test')) = '1c/0p/10v', 'D-8 so os 10 codigos novos vivos');
SELECT pg_temp.afirma((SELECT count(*) FROM identity_mfa_factors WHERE identity_id = pg_temp.identidade('d7@ex.test') AND disabled_at IS NOT NULL AND confirmed_at IS NOT NULL) = 1,
                      'D-8 fator antigo desabilitado');
SELECT pg_temp.afirma((SELECT recovery_cleared_at IS NOT NULL FROM identity_sessions WHERE identity_session_id = pg_temp.sessao_id('D-7')), 'D-8 restricao levantada');
SELECT pg_temp.afirma((SELECT revoked_at IS NOT NULL FROM identity_sessions WHERE identity_session_id = pg_temp.sessao_id('D-8/outra')), 'D-8 demais sessoes revogadas');
\! sh /tmp/e.sh D-8
-- D-9: marcas no futuro estenderiam a janela -> recusadas
-- R3: a Platform nem insere sessao (so funcao); a recusa da marca futura pela trigger esta em t_s_superusuario.sql
SELECT pg_temp.espera_erro(format('INSERT INTO identity_sessions (identity_id, session_token_hash, expires_at, mfa_verified_at, recovery_used_at, csrf_token) VALUES (%L, %L, now() + interval ''1 hour'', now(), now() + interval ''1 year'', %L)',
    pg_temp.identidade('d9@ex.test'), pg_temp.h('D-9'), pg_temp.csrf('D-9')), '42501', 'permission denied');
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('d9@ex.test'), 'D-9b');
SELECT pg_temp.espera_erro(format('UPDATE identity_sessions SET mfa_step_up_at = now() + interval ''1 year'' WHERE identity_session_id = %L', pg_temp.sessao_id('D-9b')), '23514', 'no futuro');
\! sh /tmp/e.sh D-9
SELECT 'grupo D: ok' AS resultado;
