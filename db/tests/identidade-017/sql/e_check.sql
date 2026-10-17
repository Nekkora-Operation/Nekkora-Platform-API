-- e_check.sql - papel: nekkora (superusuario, so leitura). Invariantes de estado da secao 4.4 (E-1, E-2, E-3) e a
-- conclusao do grupo C ("nenhuma entrada local viva em conta vinculada"), sobre TODO o estado confirmado.
-- Saida: uma linha e1|e2|e3|viva; o e.sh trata qualquer coisa diferente de 0|0|0|0 como falha.
SELECT (SELECT count(*) FROM user_sessions s JOIN identity_users iu ON iu.user_id = s.user_id
         WHERE s.revoked_at IS NULL AND s.identity_session_id IS NULL)
    || '|' || (SELECT count(*) FROM user_sessions s JOIN identity_sessions x ON x.identity_session_id = s.identity_session_id
         WHERE NOT EXISTS (SELECT 1 FROM identity_users iu WHERE iu.user_id = s.user_id AND iu.identity_id = x.identity_id))
    || '|' || (SELECT count(*) FROM user_mfa_factors f JOIN identity_users iu ON iu.user_id = f.user_id WHERE f.disabled_at IS NULL)
    || '|' || ((SELECT count(*) FROM auth_challenges c JOIN identity_users iu ON iu.user_id = c.user_id
                 WHERE c.consumed_at IS NULL AND c.invalidated_at IS NULL)
             + (SELECT count(*) FROM user_mfa_recovery_codes c JOIN identity_users iu ON iu.user_id = c.user_id
                 WHERE c.used_at IS NULL AND c.invalidated_at IS NULL)
             + (SELECT count(*) FROM password_reset_tokens t JOIN identity_users iu ON iu.user_id = t.user_id
                 WHERE t.consumed_at IS NULL AND t.revoked_at IS NULL)
             + (SELECT count(*) FROM email_verification_tokens t JOIN identity_users iu ON iu.user_id = t.user_id
                 WHERE t.consumed_at IS NULL AND t.revoked_at IS NULL)
             + (SELECT count(*) FROM user_credentials uc JOIN identity_users iu ON iu.user_id = uc.user_id
                 WHERE iu.link_channel = 'cadastro_web'));
