-- Grupo J (secao 5) - papel: nekkora_platform. Auxiliares falham fechado fora do contexto; nenhuma resposta distingue
-- conta vinculada de nao vinculada em outra organizacao.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
CREATE TEMP TABLE v AS SELECT pg_temp.conta('ana@ex.test', :'o1') AS ana1, pg_temp.conta('bia@ex.test', :'o1') AS bia1,
    pg_temp.conta('ana@ex.test', :'o2') AS ana2_nao_vinc, pg_temp.conta('jose@ex.test', :'o2') AS jose2_vinc;
-- J-1: sem contexto
SELECT pg_temp.espera_erro(format('SELECT identity_account_is_linked(%L, %L)', :'o1', (SELECT ana1 FROM v)), '42501', 'contexto organizacional');
-- J-2: contexto O1, parametro O2
SELECT pg_temp.espera_erro(format('SELECT pg_temp.ctx(%L); SELECT identity_account_is_linked(%L, %L)', :'o1', :'o2', (SELECT jose2_vinc FROM v)), '42501', 'contexto organizacional');
-- J-3: contexto e parametro O1, conta de O2 - vinculada e nao vinculada: a MESMA falha
SELECT pg_temp.espera_erro(format('SELECT pg_temp.ctx(%L); SELECT identity_account_is_linked(%L, %L)', :'o1', :'o1', c), '42501', 'conta fora do contexto')
  FROM (SELECT ana2_nao_vinc AS c FROM v UNION ALL SELECT jose2_vinc FROM v) x;
-- J-4: a auxiliar do nascimento de sessao, nos mesmos tres casos
SELECT pg_temp.espera_erro(format('SELECT identity_org_session_allowed(%L, %L, NULL)', :'o1', (SELECT ana1 FROM v)), '42501', 'contexto organizacional');
SELECT pg_temp.espera_erro(format('SELECT pg_temp.ctx(%L); SELECT identity_org_session_allowed(%L, %L, NULL)', :'o1', :'o2', (SELECT jose2_vinc FROM v)), '42501', 'contexto organizacional');
SELECT pg_temp.espera_erro(format('SELECT pg_temp.ctx(%L); SELECT identity_org_session_allowed(%L, %L, %L)', :'o1', :'o1', c, pg_temp.sessao_id('sessao/jose@ex.test')), '42501', 'conta fora do contexto')
  FROM (SELECT ana2_nao_vinc AS c FROM v UNION ALL SELECT jose2_vinc FROM v) x;
-- J-5: escrita em tabela do fechamento para conta de outra organizacao: recusada ja no lock (linha invisivel)
SELECT pg_temp.espera_erro(format('SELECT pg_temp.ctx(%L); INSERT INTO auth_challenges (organization_id, user_id, token_hash, purpose, credential_hash, csrf_token, expires_at) VALUES (%L, %L, %L, 1, ''h'', %L, now() + interval ''4 minutes'')',
    :'o1', :'o1', (SELECT jose2_vinc FROM v), pg_temp.h('J-5'), pg_temp.csrf('J-5')), '23514', 'conta nao encontrada');
SELECT pg_temp.espera_erro(format('SELECT pg_temp.ctx(%L); INSERT INTO password_reset_tokens (user_id, token_hash, expires_at) VALUES (%L, %L, now() + interval ''30 minutes'')',
    :'o1', (SELECT ana2_nao_vinc FROM v), pg_temp.h('J-5b')), '23514', 'conta nao encontrada');
-- J-6: no contexto certo, a resposta correta
BEGIN;
SELECT pg_temp.ctx(:'o1');
SELECT pg_temp.afirma(identity_account_is_linked(:'o1', (SELECT ana1 FROM v)) AND NOT identity_account_is_linked(:'o1', (SELECT bia1 FROM v)), 'J-6 ana vinculada, bia nao');
COMMIT;
\! sh /tmp/e.sh J
SELECT 'grupo J: ok' AS resultado;
