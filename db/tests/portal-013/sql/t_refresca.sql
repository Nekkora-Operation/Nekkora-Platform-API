-- t_refresca - papel: nekkora (superusuario; PREPARACAO de fixture). Antes de cada bloco de chamadas,
-- recoloca a ultima atividade das sessoes VALIDAS usadas nos testes em ancora - 2 min, para que a janela
-- de 15 min nao dependa da duracao da suite. Nao toca sessoes revogadas nem as dos cenarios de sessao.
\pset pager off
SELECT pg_temp.afirma(current_setting('cluster_name') = 'nekkora-portal-013-isolada', 'base isolada de teste (cluster_name)');
UPDATE user_sessions SET last_seen_at = :'ancora'::timestamptz - interval '2 minutes'
WHERE revoked_at IS NULL
  AND session_id IN (:'s_x', :'s_y', :'s_z', :'s_w', :'s_k', :'s_rv', :'s_x2', :'s_x3', :'s_xrev', :'s_xtoq', :'s_xfut', :'s_xp',
                     :'s_gp', :'s_gs', :'s_gr', :'s_ge', :'s_gf', :'s_gv', :'s_gw', :'s_ra2');
