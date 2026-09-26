-- t04_verifica - papel: nekkora (superusuario; so leitura). Depois de t04a/t04b: sessoes invalidas com
-- last_seen_at EXATAMENTE como preparado (nenhum toque, nada revivido); sessoes validas do 4(b) com
-- toque (last_seen_at > ancora); expires_at nunca alterado pelo portal.
\pset pager off
SELECT session_id, created_at, expires_at, last_seen_at, revoked_at, last_seen_at > :'ancora'::timestamptz AS tocada
FROM user_sessions ORDER BY session_id;
SELECT pg_temp.afirma((SELECT count(*) FROM user_sessions WHERE session_id IN (:'s_xrev', :'s_xexp', :'s_us', :'s_un', :'s_ms', :'s_q', :'s_r', :'adm1_sess')
                       AND last_seen_at = :'ancora'::timestamptz - interval '2 minutes') = 8,
  'NK401 (revogada, 12 h vencida, conta suspensa, e-mail nao verificado, vinculo suspenso, cliente suspenso, organizacao suspensa, conta interna): last_seen_at inalterado');
SELECT pg_temp.afirma((SELECT last_seen_at FROM user_sessions WHERE session_id = :'s_xina') = :'ancora'::timestamptz - interval '16 minutes',
  'sessao inativa (16 min): last_seen_at inalterado, nao revivida');
SELECT pg_temp.afirma((SELECT last_seen_at FROM user_sessions WHERE session_id = :'s_xb15') = :'ancora'::timestamptz - interval '15 minutes 5 seconds',
  'sessao inativa no limite (15 min 5 s): last_seen_at inalterado');
SELECT pg_temp.afirma((SELECT expires_at = created_at + interval '12 hours' AND expires_at < :'ancora'::timestamptz FROM user_sessions WHERE session_id = :'s_xexp'),
  'sessao de 12 h vencida: expires_at inalterado (created_at + 12 h, no passado; nunca renovado)');
SELECT pg_temp.afirma((SELECT count(*) FROM user_sessions WHERE session_id IN (:'s_gp', :'s_gs', :'s_gr', :'s_ge', :'s_gf', :'s_gv', :'s_gw', :'s_xb14')
                       AND last_seen_at > :'ancora'::timestamptz AND last_seen_at <= clock_timestamp()) = 8,
  '4(b) e limite de 14 min: sessao valida com recurso nao autorizado TEVE last_seen_at avancado (atividade valida)');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM user_sessions WHERE expires_at <> created_at + interval '12 hours'),
  'expires_at = created_at + 12 h em todas as sessoes (o toque nunca estende o prazo absoluto)');
