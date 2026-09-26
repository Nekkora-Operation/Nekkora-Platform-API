-- f04 - papel: nekkora (superusuario, sem RLS; so leitura). Confere a fixture antes dos testes.
\pset pager off
SELECT pg_temp.afirma(current_setting('cluster_name') = 'nekkora-portal-013-isolada', 'base isolada de teste (cluster_name)');
SELECT pg_temp.afirma((SELECT count(*) FROM users WHERE account_type = 2) = 19, '19 contas externas (16 em O1, W, R, K)');
SELECT pg_temp.afirma((SELECT count(*) FROM user_sessions) = 30, '30 sessoes (19 principais externas, 10 extras de X, 1 interna)');
SELECT pg_temp.afirma((SELECT count(*) FROM client_workspace_grants) = 19, '19 concessoes');
SELECT pg_temp.afirma((SELECT count(*) FROM executions) = 16, '16 execucoes (13 em O1, 1 em O2, O3 e O4)');
SELECT pg_temp.afirma((SELECT count(*) FROM execution_events) = 8 AND (SELECT count(*) FROM logs) = 3, '8 eventos e 3 logs');
SELECT pg_temp.afirma((SELECT string_agg(status::text, ',' ORDER BY execution_id) FROM executions
                       WHERE execution_id IN (:'e11', :'e12', :'e13', :'e14', :'e15', :'e16', :'e17')) = '1,3,5,1,4,1,1',
                      'WS1: 11 PENDING, 12 RUNNING, 13 FAILED, 14 PENDING, 15 SUCCEEDED, 16/17 PENDING');
SELECT pg_temp.afirma((SELECT count(DISTINCT created_at) FROM executions WHERE execution_id IN (:'e14', :'e16', :'e17')) = 1,
                      'e14, e16 e e17 com created_at identico (empate para o keyset)');
SELECT pg_temp.afirma((SELECT status FROM workspaces WHERE workspace_id = :'ws4') = 2
                  AND (SELECT status FROM clients WHERE client_id = :'c4') = 2
                  AND (SELECT status FROM organizations WHERE organization_id = :'o3') = 2, 'WS4, C4 e O3 suspensos');
SELECT pg_temp.afirma((SELECT count(*) FROM user_sessions WHERE session_token_hash ~ '^[0-9a-f]{64}$') = 30,
                      'hashes no contrato D2 (SHA-256 hex minusculo)');
SELECT pg_temp.afirma((SELECT received_at FROM execution_events WHERE execution_id = :'e15' AND sequence = 2)
                    < (SELECT received_at FROM execution_events WHERE execution_id = :'e15' AND sequence = 1),
                      'e15: received_at invertido em relacao a sequence');
SELECT pg_temp.afirma((SELECT count(*) FROM executions WHERE error_summary = 'MARCADOR_ERROR_SUMMARY_EXECUCAO' AND exit_code = 97) = 1,
                      'marcadores de error_summary/exit_code presentes na base');
