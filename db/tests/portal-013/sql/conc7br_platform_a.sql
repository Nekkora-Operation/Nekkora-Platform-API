-- conc7b-reverso (sessao A) - papel: nekkora_platform. A revogacao chega PRIMEIRO: B revoga a sessao x3
-- e segura a transacao 8 s antes do commit. A chamada do portal (sessao B do runner) espera o lock e,
-- liberada, encontra revoked_at -> NK401.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
BEGIN;
SET LOCAL nekkora.organization_id = :'o1';
UPDATE user_sessions SET revoked_at = clock_timestamp() WHERE session_id = :'s_x3' AND revoked_at IS NULL;
SELECT pg_sleep(8);
SELECT clock_timestamp() AS t_commit \gset
COMMIT;
\echo MARCADOR_REVOGACAO_COMMIT=:t_commit
