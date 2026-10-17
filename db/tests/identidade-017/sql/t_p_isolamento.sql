-- Grupo P (R2, alternativa A) - papel: nekkora_platform. RLS por identidade em leitura E escrita, inclusive nas tabelas
-- filhas; identity_credentials sem SELECT; funcoes de consulta so por chave exata. A RLS limita por CONTEXTO, que e
-- definido pela aplicacao: nao prova autenticacao.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
-- duas identidades com linhas em TODAS as tabelas filhas
SELECT pg_temp.nova_identidade('pa@ex.test') AS pa \gset
SELECT pg_temp.nova_identidade('pb@ex.test') AS pb \gset
SELECT pg_temp.nova_conta(:'o1', 'pa@ex.test');
CREATE FUNCTION pg_temp.povoa(p_id UUID, p_e TEXT) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_temp.nova_sessao_id(p_id, 'P/' || p_e || '/sessao', true);
    PERFORM pg_temp.desafio_cadastro(p_id, 'P/' || p_e || '/desafio');
    PERFORM pg_temp.como(p_id);
    INSERT INTO identity_email_tokens (purpose, identity_id, target_email, token_hash, expires_at)
    VALUES (1, p_id, p_e, pg_temp.h('P/' || p_e || '/email'), now() + interval '1 day');
    INSERT INTO identity_events (identity_id, event_type, correlation_id) VALUES (p_id, 4, gen_random_uuid());
END;
$$;
SELECT pg_temp.povoa(:'pa', 'pa@ex.test');
SELECT pg_temp.povoa(:'pb', 'pb@ex.test');
SELECT pg_temp.desafio_vinculo(:'pa', pg_temp.conta('pa@ex.test', :'o1'), :'o1', 'P/pa/vinculo');
-- linhas visiveis por tabela, com o contexto corrente
CREATE FUNCTION pg_temp.visiveis(p_id UUID) RETURNS TEXT LANGUAGE sql AS $$
    SELECT (SELECT count(*) FROM identities WHERE identity_id = p_id) || '/' || (SELECT count(*) FROM identity_mfa_factors WHERE identity_id = p_id)
        || '/' || (SELECT count(*) FROM identity_mfa_recovery_codes WHERE identity_id = p_id) || '/' || (SELECT count(*) FROM identity_auth_challenges WHERE identity_id = p_id)
        || '/' || (SELECT count(*) FROM identity_sessions WHERE identity_id = p_id) || '/' || (SELECT count(*) FROM identity_email_tokens WHERE identity_id = p_id)
        || '/' || (SELECT count(*) FROM identity_link_challenges WHERE identity_id = p_id) $$;
CREATE FUNCTION pg_temp.total() RETURNS BIGINT LANGUAGE sql AS $$
    SELECT (SELECT count(*) FROM identities) + (SELECT count(*) FROM identity_mfa_factors) + (SELECT count(*) FROM identity_mfa_recovery_codes)
         + (SELECT count(*) FROM identity_auth_challenges) + (SELECT count(*) FROM identity_sessions) + (SELECT count(*) FROM identity_email_tokens)
         + (SELECT count(*) FROM identity_link_challenges) $$;
CREATE FUNCTION pg_temp.linhas(p_sql TEXT) RETURNS INTEGER LANGUAGE plpgsql AS $$ DECLARE n INTEGER; BEGIN EXECUTE p_sql; GET DIAGNOSTICS n = ROW_COUNT; RETURN n; END $$;

-- P-1: sem contexto - nenhuma leitura, nenhuma escrita
SELECT pg_temp.como(NULL);
SELECT pg_temp.afirma(pg_temp.total() = 0, 'P-1 sem contexto: nenhuma linha em nenhuma tabela de identidade');
SELECT pg_temp.afirma(pg_temp.linhas(format('UPDATE identities SET display_name = ''x'' WHERE identity_id = %L', :'pa')) = 0
                  AND pg_temp.linhas(format('UPDATE identity_sessions SET revoked_at = now() WHERE identity_id = %L', :'pa')) = 0, 'P-1 sem contexto: UPDATE nao alcanca nada');
SELECT pg_temp.espera_erro(format('INSERT INTO identity_events (identity_id, event_type, correlation_id) VALUES (%L, 4, gen_random_uuid())', :'pa'), '42501', 'row-level security');
SELECT pg_temp.espera_erro(format('INSERT INTO identities (identity_id, email, display_name, status) VALUES (%L, ''p1@ex.test'', ''x'', 1)', gen_random_uuid()), '42501', 'row-level security');
-- P-2 / P-3: contexto de A - so as linhas de A, em todas as tabelas filhas; escrita em B nao alcanca nem insere
SELECT pg_temp.como(:'pa');
SELECT pg_temp.afirma(pg_temp.visiveis(:'pa') !~ '(^|/)0(/|$)', 'P-2 contexto A: linhas de A em todas as tabelas (' || pg_temp.visiveis(:'pa') || ')');
SELECT pg_temp.afirma(pg_temp.visiveis(:'pb') = '0/0/0/0/0/0/0', 'P-3 contexto A: nenhuma linha de B, em nenhuma tabela filha');
SELECT pg_temp.afirma(pg_temp.linhas(format('UPDATE identities SET display_name = ''x'' WHERE identity_id = %L', :'pb')) = 0
                  AND pg_temp.linhas(format('UPDATE identity_sessions SET revoked_at = now() WHERE identity_id = %L', :'pb')) = 0
                  AND pg_temp.linhas(format('UPDATE identity_auth_challenges SET attempts = attempts + 1 WHERE identity_id = %L', :'pb')) = 0
                  AND pg_temp.linhas(format('UPDATE identity_mfa_factors SET last_used_step = 1 WHERE identity_id = %L', :'pb')) = 0
                  AND pg_temp.linhas(format('UPDATE identity_email_tokens SET revoked_at = now() WHERE identity_id = %L', :'pb')) = 0,
                  'P-3 contexto A: UPDATE em linhas de B afeta zero linhas, em cada tabela com UPDATE (codigos: sem UPDATE desde a R3, S-11)');
SELECT pg_temp.espera_erro(format('INSERT INTO identity_events (identity_id, event_type, correlation_id) VALUES (%L, 4, gen_random_uuid())', :'pb'), '42501', 'row-level security');
SELECT pg_temp.espera_erro(format('INSERT INTO identity_sessions (identity_id, session_token_hash, expires_at, mfa_verified_at, csrf_token) VALUES (%L, %L, now() + interval ''1 hour'', now(), %L)',
    :'pb', pg_temp.h('P/intrusa'), pg_temp.csrf('P/intrusa')), '42501', 'permission denied');   -- R3: sem INSERT de sessao
SELECT pg_temp.espera_erro(format('INSERT INTO identity_auth_challenges (identity_id, token_hash, purpose, credential_hash, csrf_token, expires_at) VALUES (%L, %L, 2, ''h'', %L, now() + interval ''1 minute'')',
    :'pb', pg_temp.h('P/intruso'), pg_temp.csrf('P/intruso')), '42501', 'row-level security');
SELECT pg_temp.espera_erro(format('INSERT INTO identity_email_tokens (purpose, identity_id, target_email, token_hash, expires_at) VALUES (1, %L, ''pb@ex.test'', %L, now() + interval ''1 hour'')',
    :'pb', pg_temp.h('P/intruso-email')), '42501', 'row-level security');
-- P-4: a MESMA conexao trocando de identidade nao carrega linhas da anterior; contexto local nao vaza para a proxima transacao
SELECT pg_temp.como(:'pb');
SELECT pg_temp.afirma(pg_temp.visiveis(:'pb') ~ '^[1-9][0-9]*/[1-9][0-9]*/[1-9][0-9]*/[1-9][0-9]*/[1-9][0-9]*/[1-9][0-9]*/0$' AND pg_temp.visiveis(:'pa') = '0/0/0/0/0/0/0', 'P-4 troca A -> B: so B');
SELECT pg_temp.como(:'pa');
SELECT pg_temp.afirma(pg_temp.visiveis(:'pa') !~ '(^|/)0(/|$)' AND pg_temp.visiveis(:'pb') = '0/0/0/0/0/0/0', 'P-4 troca B -> A: so A');
RESET nekkora.identity_id;
BEGIN;
SET LOCAL nekkora.identity_id = :'pb';
SELECT pg_temp.afirma(pg_temp.visiveis(:'pb') ~ '^[1-9][0-9]*/[1-9][0-9]*/[1-9][0-9]*/[1-9][0-9]*/[1-9][0-9]*/[1-9][0-9]*/0$', 'P-4 SET LOCAL B dentro da transacao');
COMMIT;
SELECT pg_temp.afirma(pg_temp.total() = 0, 'P-4 depois do COMMIT: o contexto local nao vaza para a proxima transacao');
-- P-5: identity_credentials sem SELECT e sem UPDATE, mesmo com contexto; INSERT so na propria identidade
SELECT pg_temp.como(:'pa');
SELECT pg_temp.espera_erro('SELECT 1 FROM identity_credentials', '42501', 'permission denied');
SELECT pg_temp.espera_erro(format('SELECT password_hash FROM identity_credentials WHERE identity_id = %L', :'pa'), '42501', 'permission denied');
SELECT pg_temp.espera_erro('UPDATE identity_credentials SET password_hash = ''x''', '42501', 'permission denied');
SELECT pg_temp.espera_erro(format('INSERT INTO identity_credentials (identity_id, password_hash) VALUES (%L, ''x'')', :'pb'), '42501', 'row-level security');
SELECT pg_temp.afirma((SELECT password_hash FROM identity_resolve_login('pa@ex.test')) = '$argon2id$id$pa@ex.test', 'P-5 o hash sai so pelo contrato de autenticacao');
-- P-6: identity_users continua sem privilegio, com qualquer contexto
SELECT pg_temp.espera_erro('SELECT 1 FROM identity_users', '42501', 'permission denied');
-- P-7: funcoes de consulta - chave exata, so os campos necessarios
SELECT pg_temp.afirma((SELECT count(*) FROM identity_signup_state(e)) = 0, 'P-7 signup_state sem resultado: ' || coalesce(e, 'NULL'))
  FROM unnest(ARRAY['pa', 'PA@EX.TEST', '%@ex.test', 'pa@ex.%', '', NULL]) e;
SELECT pg_temp.afirma((SELECT count(*) FROM identity_signup_state('pa@ex.test')) = 1, 'P-7 signup_state exato');
SELECT pg_temp.afirma((SELECT count(*) FROM identity_session_lookup(t)) = 0 AND (SELECT count(*) FROM identity_challenge_lookup(t)) = 0
                  AND (SELECT count(*) FROM identity_email_token_lookup(t)) = 0, 'P-7 consulta por token sem resultado: ' || coalesce(t, 'NULL'))
  FROM unnest(ARRAY['abc', '', NULL, '%', pg_temp.tok('P/nada'), pg_temp.h('P/pa@ex.test/sessao')]) t;
SELECT pg_temp.afirma((SELECT identity_id FROM identity_session_lookup(pg_temp.tok('P/pa@ex.test/sessao'))) = :'pa'
                  AND (SELECT identity_id FROM identity_challenge_lookup(pg_temp.tok('P/pa@ex.test/desafio'))) = :'pa'
                  AND (SELECT identity_id FROM identity_email_token_lookup(pg_temp.tok('P/pa@ex.test/email'))) = :'pa', 'P-7 consultas exatas por token');
UPDATE identity_sessions SET revoked_at = now() WHERE session_token_hash = pg_temp.h('P/pa@ex.test/sessao');
UPDATE identity_email_tokens SET consumed_at = now() WHERE token_hash = pg_temp.h('P/pa@ex.test/email');
UPDATE identity_auth_challenges SET invalidated_at = now(), credential_hash = NULL WHERE token_hash = pg_temp.h('P/pa@ex.test/desafio');
SELECT pg_temp.afirma((SELECT count(*) FROM identity_session_lookup(pg_temp.tok('P/pa@ex.test/sessao'))) = 0
                  AND (SELECT count(*) FROM identity_challenge_lookup(pg_temp.tok('P/pa@ex.test/desafio'))) = 0
                  AND (SELECT count(*) FROM identity_email_token_lookup(pg_temp.tok('P/pa@ex.test/email'))) = 0, 'P-7 revogada, invalidado e consumido: nada');
-- forma das funcoes: so os campos necessarios (catalogo)
SELECT pg_temp.afirma((SELECT string_agg(p.proname || '(' || pg_get_function_result(p.oid) || ')', '; ' ORDER BY p.proname)
                         FROM pg_proc p WHERE p.proname IN ('identity_signup_state', 'identity_session_lookup', 'identity_challenge_lookup', 'identity_email_token_lookup'))
    = 'identity_challenge_lookup(TABLE(challenge_id uuid, identity_id uuid)); identity_email_token_lookup(TABLE(token_id uuid, identity_id uuid)); '
      'identity_session_lookup(TABLE(identity_session_id uuid, identity_id uuid)); identity_signup_state(TABLE(identity_id uuid, status smallint))',
    'P-7 formato exato das quatro funcoes de consulta');
\! sh /tmp/e.sh P
SELECT 'grupo P: ok' AS resultado;
