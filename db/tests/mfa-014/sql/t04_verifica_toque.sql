-- t04 - papel: nekkora (so leitura). Depois do t03: o toque da 013 passou pela trigger de UPDATE da 014
-- (last_seen_at avancou em s_x) sem alterar marcador, CSRF, prazo nem revogacao.
\pset pager off
SELECT pg_temp.afirma((SELECT last_seen_at > now() - interval '1 minute' AND revoked_at IS NULL AND mfa_verified_at IS NOT NULL AND csrf_token = :'csrf_x'
                       FROM user_sessions WHERE session_id = :'s_x'), 's_x: toque aplicado pela funcao do portal; marcador, CSRF e revogacao intactos');
