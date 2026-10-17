-- Grupo G (DC-17.13; secao 4.4 propriedade 4) - papel: nekkora_platform, SQL direto.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
CREATE TEMP TABLE v AS SELECT pg_temp.conta('gabi@ex.test', :'o1') AS gabi, pg_temp.identidade('gabi@ex.test') AS idg;
SELECT pg_temp.nova_sessao_id((SELECT idg FROM v), 'G/restrita', false, true);   -- T0 revoga as demais sessoes de gabi
SELECT pg_temp.nova_sessao_id((SELECT idg FROM v), 'G/comum');                     -- controle positivo, criada depois
SELECT pg_temp.afirma(EXISTS (SELECT 1 FROM identity_mfa_factors WHERE identity_id = (SELECT idg FROM v) AND confirmed_at IS NOT NULL AND disabled_at IS NULL),
                      'G: fator antigo da identidade confirmado e habilitado (entre T0 e T1)');
-- G-1: sessao restrita -> sessao de organizacao recusada, mesmo com fator confirmado
SELECT pg_temp.espera_erro(format('SELECT pg_temp.sessao_org(%L, %L, %L, ''G-1'')', :'o1', (SELECT gabi FROM v), pg_temp.sessao_id('G/restrita')), '23514', 'fator de MFA confirmado');
\! sh /tmp/e.sh G-1
-- G-3: step-up numa sessao restrita nao levanta a restricao
UPDATE identity_sessions SET mfa_step_up_at = now() WHERE identity_session_id = pg_temp.sessao_id('G/restrita');
SELECT pg_temp.espera_erro(format('SELECT pg_temp.sessao_org(%L, %L, %L, ''G-3'')', :'o1', (SELECT gabi FROM v), pg_temp.sessao_id('G/restrita')), '23514', 'fator de MFA confirmado');
\! sh /tmp/e.sh G-3
-- G-4: listagem e alvo da troca: zero linhas para a restrita (controle positivo com a sessao comum)
SELECT pg_temp.afirma((SELECT count(*) FROM identity_organizations(pg_temp.tok('G/restrita'))) = 0, 'G-4 listagem vazia para a restrita');
SELECT pg_temp.afirma((SELECT count(*) FROM identity_org_session_target(pg_temp.tok('G/restrita'), :'o1')) = 0, 'G-4 alvo vazio para a restrita');
SELECT pg_temp.afirma((SELECT count(*) FROM identity_organizations(pg_temp.tok('G/comum'))) = 1, 'G-4 controle: sessao comum lista O1');
\! sh /tmp/e.sh G-4
-- G-5: criacao de organizacao recusada, sem nenhuma linha (a listagem da sessao comum nao muda)
SELECT pg_temp.afirma(identity_create_organization(pg_temp.tok('G/restrita'), 'Org G-5', NULL) IS NULL, 'G-5 recusado');
SELECT pg_temp.afirma((SELECT count(*) FROM identity_organizations(pg_temp.tok('G/comum'))) = 1, 'G-5 sem organizacao nova');
\! sh /tmp/e.sh G-5
-- G-6: marcas de recuperacao fora do alcance da aplicacao
SELECT pg_temp.espera_erro(format('UPDATE identity_sessions SET recovery_cleared_at = now() WHERE identity_session_id = %L', pg_temp.sessao_id('G/restrita')), '42501', 'permission denied');
SELECT pg_temp.espera_erro(format('UPDATE identity_sessions SET recovery_used_at = NULL WHERE identity_session_id = %L', pg_temp.sessao_id('G/restrita')), '42501', 'permission denied');
-- G-7: confirmar direto o fator novo enquanto o antigo esta habilitado -> privilegio (42501)
SELECT identity_mfa_enroll_pending(NULL, pg_temp.tok('G/restrita'), decode(repeat('9a', 36), 'hex'), decode(repeat('04', 12), 'hex'), 'k1') AS g_novo \gset
SELECT pg_temp.espera_erro(format('UPDATE identity_mfa_factors SET confirmed_at = now() WHERE factor_id = %L', :'g_novo'), '42501', 'permission denied');
\! sh /tmp/e.sh G-7
-- G-8 (R3): a aplicacao nem consome codigo diretamente (so identity_login_complete); a restricao adiada que exige sessao
-- restrita continua como defesa e e provada em t_s_superusuario.sql
SELECT pg_temp.como(pg_temp.identidade('gabi@ex.test'));
SELECT pg_temp.espera_erro(format('UPDATE identity_mfa_recovery_codes SET used_at = now(), used_in_identity_session_id = %L WHERE identity_id = %L',
    pg_temp.sessao_id('G/comum'), pg_temp.identidade('gabi@ex.test')), '42501', 'permission denied');
\! sh /tmp/e.sh G-8
-- G-2: depois de T1 (identity_mfa_replace pela sessao restrita), a sessao de organizacao nasce
SELECT pg_temp.afirma(identity_mfa_replace(pg_temp.tok('G/restrita'), :'g_novo', pg_temp.codigos('G-2/novos')), 'G-2 T1 concluido');
SELECT pg_temp.afirma(pg_temp.sessao_org(:'o1', (SELECT gabi FROM v), pg_temp.sessao_id('G/restrita'), 'G-2') IS NOT NULL, 'G-2 sessao de organizacao aceita');
\! sh /tmp/e.sh G-2
SELECT 'grupo G: ok' AS resultado;
