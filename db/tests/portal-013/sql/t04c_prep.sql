-- t04c_prep - papel: nekkora (superusuario; PREPARACAO de fixture). Instantes das sessoes do teste de
-- toque em relacao a ancora: xtoq ha 61 s (toque devido), xfut 30 s NO FUTURO (relogio de quem gravou
-- adiantado: o toque nao pode retroceder), xp ha 2 min (usada com parametro invalido: 22023 nao toca).
\pset pager off
SELECT pg_temp.afirma(current_setting('cluster_name') = 'nekkora-portal-013-isolada', 'base isolada de teste (cluster_name)');
BEGIN;
UPDATE user_sessions SET last_seen_at = :'ancora'::timestamptz - interval '61 seconds' WHERE session_id = :'s_xtoq';
UPDATE user_sessions SET last_seen_at = :'ancora'::timestamptz + interval '30 seconds' WHERE session_id = :'s_xfut';
UPDATE user_sessions SET last_seen_at = :'ancora'::timestamptz - interval '2 minutes' WHERE session_id = :'s_xp';
COMMIT;
