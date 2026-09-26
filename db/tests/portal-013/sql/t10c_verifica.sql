-- t10c - papel: nekkora (superusuario; so leitura). Depois do t10a/t10b: a concessao de Ra continua
-- revogada; a sessao antiga continua revogada (sem toque); a sessao nova teve toque (atividade valida,
-- mesmo sem nenhum recurso autorizado); last_access_at nunca foi escrito em concessao alguma (opcao A).
\pset pager off
SELECT pg_temp.afirma((SELECT status = 4 AND revoked_at IS NOT NULL FROM client_workspace_grants WHERE grant_id = :'gr_ra'),
  'concessao de Ra continua revogada depois da reativacao da conta');
SELECT pg_temp.afirma((SELECT revoked_at IS NOT NULL FROM user_sessions WHERE session_id = :'s_ra'), 'sessao antiga de Ra continua revogada');
SELECT pg_temp.afirma((SELECT last_seen_at > created_at + interval '4 minutes' FROM user_sessions WHERE session_id = :'s_ra2'),
  'sessao nova de Ra: last_seen_at avancou (sessao valida sem recurso autorizado conta como atividade)');
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM client_workspace_grants WHERE last_access_at IS NOT NULL),
  'last_access_at nulo em todas as concessoes (o portal nunca o escreve - opcao A)');
