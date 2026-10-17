-- Grupo A (secao 4.4) - papel: nekkora_platform, SQL direto (INSERT cru em user_sessions). E-1..E-3 depois de cada cenario.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
-- R2: a sessao expirada so e localizavel com o contexto da identidade dona
SELECT pg_temp.como(pg_temp.identidade('ana@ex.test'));
CREATE TEMP TABLE v AS SELECT pg_temp.conta('ana@ex.test', :'o1') AS ana, pg_temp.conta('bia@ex.test', :'o1') AS bia,
    pg_temp.conta('bruno@ex.test', :'o1') AS bruno, pg_temp.conta('paula@ex.test', :'o1') AS paula,
    pg_temp.sessao_id('sessao/ana@ex.test') AS s_ana, pg_temp.sessao_id('sessao/xavier@ex.test') AS s_xavier,
    pg_temp.sessao_id('sessao/paula@ex.test') AS s_paula, pg_temp.sessao_id('sessao/ana-expirada') AS s_ana_exp;
SELECT pg_temp.afirma((SELECT s_ana_exp IS NOT NULL FROM v), 'sessao expirada da fixture f03 presente');

-- A-1: nao vinculada, fator da conta confirmado, identity_session_id nulo -> aceito
SELECT pg_temp.afirma(pg_temp.sessao_org(:'o1', (SELECT bia FROM v), NULL, 'A-1') IS NOT NULL, 'A-1 aceito');
\! sh /tmp/e.sh A-1
-- A-2: nao vinculada, sem fator da conta -> recusado (regra da 014)
SELECT pg_temp.espera_erro(format('SELECT pg_temp.sessao_org(%L, %L, NULL, ''A-2'')', :'o1', (SELECT bruno FROM v)), '23514', 'fator de MFA confirmado');
\! sh /tmp/e.sh A-2
-- A-3: nao vinculada, identity_session_id preenchido -> recusado (exclusividade dos ramos)
SELECT pg_temp.espera_erro(format('SELECT pg_temp.sessao_org(%L, %L, %L, ''A-3'')', :'o1', (SELECT bia FROM v), (SELECT s_ana FROM v)), '23514', 'fator de MFA confirmado');
\! sh /tmp/e.sh A-3
-- A-4: vinculada, sessao da identidade ligada, ativa, com MFA; fator da identidade confirmado -> aceito
SELECT pg_temp.afirma(pg_temp.sessao_org(:'o1', (SELECT ana FROM v), (SELECT s_ana FROM v), 'A-4') IS NOT NULL, 'A-4 aceito');
\! sh /tmp/e.sh A-4
-- A-5: vinculada, identity_session_id nulo -> recusado
SELECT pg_temp.espera_erro(format('SELECT pg_temp.sessao_org(%L, %L, NULL, ''A-5'')', :'o1', (SELECT ana FROM v)), '23514', 'fator de MFA confirmado');
\! sh /tmp/e.sh A-5
-- A-6: vinculada, sessao de OUTRA identidade -> recusado (o caso que um OR solto deixaria passar)
SELECT pg_temp.espera_erro(format('SELECT pg_temp.sessao_org(%L, %L, %L, ''A-6'')', :'o1', (SELECT ana FROM v), (SELECT s_xavier FROM v)), '23514', 'fator de MFA confirmado');
\! sh /tmp/e.sh A-6
-- A-7: vinculada, sessao de identidade revogada -> recusado
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('ana@ex.test'), 'A-7/sessao');
UPDATE identity_sessions SET revoked_at = now() WHERE identity_session_id = pg_temp.sessao_id('A-7/sessao');
SELECT pg_temp.espera_erro(format('SELECT pg_temp.sessao_org(%L, %L, %L, ''A-7'')', :'o1', (SELECT ana FROM v), pg_temp.sessao_id('A-7/sessao')), '23514', 'fator de MFA confirmado');
\! sh /tmp/e.sh A-7
-- A-8: vinculada, sessao de identidade expirada -> recusado
SELECT pg_temp.espera_erro(format('SELECT pg_temp.sessao_org(%L, %L, %L, ''A-8'')', :'o1', (SELECT ana FROM v), (SELECT s_ana_exp FROM v)), '23514', 'fator de MFA confirmado');
\! sh /tmp/e.sh A-8
-- A-9: vinculada, identidade sem fator confirmado -> recusado
SELECT pg_temp.espera_erro(format('SELECT pg_temp.sessao_org(%L, %L, %L, ''A-9'')', :'o1', (SELECT paula FROM v), (SELECT s_paula FROM v)), '23514', 'fator de MFA confirmado');
\! sh /tmp/e.sh A-9
-- A-10: a vinculacao deixou o fator da conta desabilitado (E-3); reabilitar e recusado (B-3)
BEGIN;
SELECT pg_temp.ctx(:'o1');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM user_mfa_factors WHERE user_id = (SELECT ana FROM v) AND disabled_at IS NULL)
                      AND EXISTS (SELECT 1 FROM user_mfa_factors WHERE user_id = (SELECT ana FROM v)), 'A-10 fator da conta vinculada desabilitado, linha mantida');
COMMIT;
\! sh /tmp/e.sh A-10
-- A-x: sem contexto de organizacao a sessao nao nasce: o lock da trigger nao ve a conta e recusa antes da RLS
SELECT pg_temp.espera_erro(format('INSERT INTO user_sessions (organization_id, user_id, session_token_hash, expires_at, mfa_verified_at, csrf_token) VALUES (%L, %L, %L, now() + interval ''1 hour'', now(), %L)',
    :'o1', (SELECT bia FROM v), pg_temp.h('A-x'), pg_temp.csrf('A-x')), '23514', 'conta visivel no contexto');
SELECT 'grupo A: ok' AS resultado;
