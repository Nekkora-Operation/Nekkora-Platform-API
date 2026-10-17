-- Grupo C' (secao 4.5, camada 3, peca 3) - papel: nekkora_platform. O papel da aplicacao nao contorna o caminho.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
-- C'-1: nenhum privilegio em identity_users
SELECT pg_temp.espera_erro('SELECT 1 FROM identity_users', '42501', 'permission denied');
SELECT pg_temp.espera_erro(format('INSERT INTO identity_users (identity_id, user_id, link_channel) VALUES (%L, %L, ''vinculacao'')',
    pg_temp.identidade('bia@ex.test'), pg_temp.conta('bia@ex.test', :'o1')), '42501', 'permission denied');
SELECT pg_temp.espera_erro('UPDATE identity_users SET link_channel = ''vinculacao''', '42501', 'permission denied');
SELECT pg_temp.espera_erro('DELETE FROM identity_users', '42501', 'permission denied');
SELECT pg_temp.espera_erro('TRUNCATE identity_users', '42501', 'permission denied');
-- C'-2: nao e dono: nao desliga trigger em nenhuma tabela do fechamento
SELECT pg_temp.espera_erro(format('ALTER TABLE %I DISABLE TRIGGER ALL', t), '42501', 'must be owner')
  FROM unnest(ARRAY['auth_challenges', 'user_mfa_factors', 'user_mfa_recovery_codes', 'password_reset_tokens', 'email_verification_tokens',
                    'user_credentials', 'user_sessions', 'identity_users', 'identity_mfa_factors', 'identity_mfa_recovery_codes',
                    'identity_sessions', 'identity_link_challenges']) t;
-- C'-3: nao e superusuario: nao desliga triggers por session_replication_role
SELECT pg_temp.espera_erro('SET session_replication_role = replica', '42501', 'permission denied');
-- C'-4: reabilitar fator local de conta vinculada
SELECT pg_temp.espera_erro(format('SELECT pg_temp.ctx(%L); UPDATE user_mfa_factors SET disabled_at = NULL WHERE user_id = %L', :'o1',
    pg_temp.conta('ana@ex.test', :'o1')), 'P0001', 'desabilitado e imutavel');
-- C'-5: as auxiliares nao escrevem; as internas e a de fechamento nao sao executaveis pela Platform
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM pg_proc WHERE proname IN ('identity_account_is_linked', 'identity_org_session_allowed', 'identity_check_context')
                                   AND prosrc ~* '\m(insert|update|delete|truncate)\M'), 'C''-5 auxiliares sem escrita');
SELECT pg_temp.espera_erro(format('SELECT %s', f), '42501', 'permission denied')
  FROM unnest(ARRAY['identity_link_account_impl(NULL, NULL, NULL)', 'identity_create_organization_impl(NULL, NULL, NULL)',
                    'identity_session_resolve(NULL)', 'identity_check_context(NULL, NULL)', 'identity_require_read_committed()']) f;
-- C'-6: o consumo do desafio de vinculacao so acontece dentro da funcao
SELECT pg_temp.espera_erro('UPDATE identity_link_challenges SET consumed_at = now()', '42501', 'permission denied');
-- C'-7 / H-16: nao assume o papel dono
SELECT pg_temp.espera_erro('SET ROLE nekkora_identity_owner', '42501', 'permission denied');
SELECT 'grupo C'': ok' AS resultado;
\! sh /tmp/e.sh C2
