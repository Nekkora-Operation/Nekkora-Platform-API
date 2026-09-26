-- t03 - papel: nekkora_portal (LOGIN real; chamadas por bind). A 013 continua funcionando com a 014:
-- sessao com MFA (criada pela Platform) le WS1, o toque de last_seen_at passa pela trigger de UPDATE da
-- 014, sessao revogada -> NK401; o portal continua sem acesso direto as tabelas novas e as colunas novas.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_portal' AND session_user = 'nekkora_portal', 'papel real nekkora_portal');
SELECT count(*) AS n, coalesce(string_agg(workspace_id::text, ','), '') AS ids FROM public.portal_list_workspaces($1::uuid, $2) \bind :o1 :tok_x \gset r_
SELECT pg_temp.afirma(:'r_n' = '1' AND :'r_ids' = :'ws1', 'X (sessao com MFA): portal_list_workspaces = {WS1}');
SELECT count(*) AS n FROM public.portal_list_executions($1::uuid, $2, NULL, NULL, NULL, NULL, NULL, NULL, 100) \bind :o1 :tok_x \gset r_
SELECT pg_temp.afirma(:'SQLSTATE' = '00000', 'portal_list_executions sem erro com a 014');
SELECT pg_temp.afirma(count(*) = 4, 'acesso direto negado as 4 tabelas novas')
FROM unnest(ARRAY['user_mfa_factors', 'user_mfa_recovery_codes', 'auth_challenges', 'mfa_events']) t,
     LATERAL pg_temp.espera_erro(format('SELECT 1 FROM public.%I LIMIT 1', t), '42501', 'permission denied') e;
SELECT pg_temp.espera_erro('SELECT csrf_token FROM public.user_sessions', '42501', 'permission denied');
