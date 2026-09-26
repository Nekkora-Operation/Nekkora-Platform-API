-- conc7b (sessao B) - papel: nekkora_platform. Revoga a sessao x2 enquanto A (portal) a mantem travada:
-- o UPDATE espera o commit de A. Afirma que esperou (> 3 s) e registra o instante do proprio commit.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
SELECT clock_timestamp() AS t0 \gset
BEGIN;
SET LOCAL nekkora.organization_id = :'o1';
UPDATE user_sessions SET revoked_at = clock_timestamp() WHERE session_id = :'s_x2' AND revoked_at IS NULL;
SELECT clock_timestamp() AS t1 \gset
COMMIT;
\echo MARCADOR_B_INICIO=:t0
\echo MARCADOR_B_COMMIT=:t1
SELECT pg_temp.afirma(:'t1'::timestamptz - :'t0'::timestamptz > interval '3 seconds', 'B: a revogacao da sessao esperou o lock da requisicao em curso');
