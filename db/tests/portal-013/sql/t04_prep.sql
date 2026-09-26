-- t04_prep - papel: nekkora (superusuario; PREPARACAO de fixture, nao e evidencia de isolamento).
-- Ajusta os instantes das sessoes dos cenarios 4(a)/4(b) em relacao a uma ancora (clock do banco,
-- capturada pelo runner e repassada tambem ao t04_verifica), para que as janelas de 15 min e 12 h
-- sejam exatas e reproduziveis. So altera user_sessions das sessoes listadas.
\pset pager off
SELECT pg_temp.afirma(current_setting('cluster_name') = 'nekkora-portal-013-isolada', 'base isolada de teste (cluster_name)');
BEGIN;
-- sessoes validas: ultima atividade ha 2 min (dentro da janela; toque devido, pois > 1 min)
UPDATE user_sessions SET last_seen_at = :'ancora'::timestamptz - interval '2 minutes'
WHERE session_id IN (:'s_x', :'s_y', :'s_z', :'s_gp', :'s_gs', :'s_gr', :'s_ge', :'s_gf', :'s_gv', :'s_gw',
                     :'s_us', :'s_un', :'s_ms', :'s_q', :'s_r', :'adm1_sess', :'s_xrev', :'s_w', :'s_k');
-- inatividade: 16 min (fora), 15 min e 5 s (fora, limite), 14 min (dentro, limite)
UPDATE user_sessions SET last_seen_at = :'ancora'::timestamptz - interval '16 minutes' WHERE session_id = :'s_xina';
UPDATE user_sessions SET last_seen_at = :'ancora'::timestamptz - interval '15 minutes 5 seconds' WHERE session_id = :'s_xb15';
UPDATE user_sessions SET last_seen_at = :'ancora'::timestamptz - interval '14 minutes' WHERE session_id = :'s_xb14';
-- prazo absoluto: a fixture ja a criou ha 13 h (expires_at = created_at + 12 h, vencido); atividade recente.
-- So last_seen_at e ajustado aqui: o snapshot de estado admite diferenca apenas nessa coluna.
UPDATE user_sessions SET last_seen_at = :'ancora'::timestamptz - interval '2 minutes' WHERE session_id = :'s_xexp';
COMMIT;
SELECT session_id, created_at, expires_at, last_seen_at, revoked_at FROM user_sessions
WHERE session_id IN (:'s_xina', :'s_xb15', :'s_xb14', :'s_xexp', :'s_xrev') ORDER BY session_id;
