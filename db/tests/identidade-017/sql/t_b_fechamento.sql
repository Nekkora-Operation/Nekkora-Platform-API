-- Grupo B (secao 4.5, camada 2) - papel: nekkora_platform, SQL direto, sem AuthService e sem lock explicito.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
CREATE TEMP TABLE v AS SELECT pg_temp.conta('ana@ex.test', :'o1') AS ana, pg_temp.conta('c20@ex.test', :'o1') AS bia;
-- B-1..B-7 em conta VINCULADA: recusadas. B-3: a 014 ja recusa reabilitar (trigger anterior na ordem); a nova tambem recusaria.
SELECT pg_temp.espera_erro((pg_temp.escritas((SELECT ana FROM v), 'ana'))[1], '23514', 'conta vinculada');
\! sh /tmp/e.sh B-1
SELECT pg_temp.espera_erro((pg_temp.escritas((SELECT ana FROM v), 'ana'))[2], '23514', 'conta vinculada');
\! sh /tmp/e.sh B-2
SELECT pg_temp.espera_erro((pg_temp.escritas((SELECT ana FROM v), 'ana'))[3], 'P0001', 'desabilitado e imutavel');
\! sh /tmp/e.sh B-3
SELECT pg_temp.espera_erro((pg_temp.escritas((SELECT ana FROM v), 'ana'))[4], '23514', 'conta vinculada');
\! sh /tmp/e.sh B-4
SELECT pg_temp.espera_erro((pg_temp.escritas((SELECT ana FROM v), 'ana'))[5], '23514', 'conta vinculada');
\! sh /tmp/e.sh B-5
SELECT pg_temp.espera_erro((pg_temp.escritas((SELECT ana FROM v), 'ana'))[6], '23514', 'conta vinculada');
\! sh /tmp/e.sh B-6
SELECT pg_temp.espera_erro((pg_temp.escritas((SELECT ana FROM v), 'ana'))[7], '23514', 'conta vinculada');
\! sh /tmp/e.sh B-7
-- B-8: as mesmas escritas em conta NAO vinculada (c20): todas aceitas (B-3 sem efeito: o fator dela esta habilitado;
-- o INSERT de fator novo exige antes desabilitar o atual, pelo indice da 014, como o fluxo de cadastro faz)
BEGIN;
SELECT pg_temp.ctx(:'o1');
UPDATE user_mfa_factors SET disabled_at = now() WHERE user_id = (SELECT bia FROM v) AND disabled_at IS NULL;
DO $$ DECLARE q TEXT; i INTEGER := 0; BEGIN
    FOREACH q IN ARRAY pg_temp.escritas((SELECT bia FROM v), 'bia') LOOP
        i := i + 1;
        IF i <> 3 THEN EXECUTE q; END IF;
    END LOOP;
END $$;
SELECT pg_temp.afirma((SELECT count(*) FROM auth_challenges WHERE user_id = (SELECT bia FROM v)) = 1
    AND (SELECT count(*) FROM user_mfa_factors WHERE user_id = (SELECT bia FROM v) AND disabled_at IS NULL) = 1
    AND (SELECT count(*) FROM user_mfa_recovery_codes WHERE user_id = (SELECT bia FROM v)) = 1
    AND (SELECT count(*) FROM password_reset_tokens WHERE user_id = (SELECT bia FROM v)) = 1
    AND (SELECT count(*) FROM email_verification_tokens WHERE user_id = (SELECT bia FROM v)) = 1
    AND (SELECT password_hash FROM user_credentials WHERE user_id = (SELECT bia FROM v)) = 'novo', 'B-8 conta nao vinculada: sete escritas aceitas');
COMMIT;
\! sh /tmp/e.sh B-8
SELECT 'grupo B: ok' AS resultado;
