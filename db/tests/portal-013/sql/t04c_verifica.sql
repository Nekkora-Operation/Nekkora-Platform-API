-- t04c_verifica - papel: nekkora (superusuario; so leitura). Recebe os instantes das chamadas do t04c.
\pset pager off
SELECT session_id, last_seen_at, expires_at FROM user_sessions WHERE session_id IN (:'s_xtoq', :'s_xfut', :'s_xp') ORDER BY 1;
SELECT pg_temp.afirma((SELECT last_seen_at FROM user_sessions WHERE session_id = :'s_xtoq') BETWEEN :'t1a'::timestamptz AND :'t1b'::timestamptz,
  'xtoq: last_seen_at = instante da 1a chamada (toque apos > 1 min)');
SELECT pg_temp.afirma((SELECT last_seen_at FROM user_sessions WHERE session_id = :'s_xtoq') < :'t2a'::timestamptz,
  'xtoq: a 2a chamada (< 1 min depois) NAO escreveu - no maximo 1 escrita por minuto');
SELECT pg_temp.afirma((SELECT last_seen_at FROM user_sessions WHERE session_id = :'s_xfut') = :'ancora'::timestamptz + interval '30 seconds',
  'xfut: last_seen_at no futuro nao retrocedeu');
SELECT pg_temp.afirma((SELECT last_seen_at FROM user_sessions WHERE session_id = :'s_xp') = :'ancora'::timestamptz - interval '2 minutes',
  'xp: 22023 nao tocou last_seen_at (transacao inteira desfeita)');
SELECT pg_temp.afirma((SELECT count(*) FROM user_sessions WHERE session_id IN (:'s_xtoq', :'s_xfut', :'s_xp') AND expires_at = created_at + interval '12 hours') = 3,
  'expires_at inalterado nas tres');
