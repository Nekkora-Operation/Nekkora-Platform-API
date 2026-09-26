-- conc7c (sessao A) - papel: nekkora_portal (LOGIN real; bind). Chama a variante de pausa com a sessao
-- de K (O4): a resolucao passa, a chamada para no ponto de pausa; enquanto isso o nekkora_platform
-- confirma a mudanca de estado do caso; liberada, a leitura (que reconfere tudo no proprio comando)
-- deve devolver :esperado linhas (0 nos casos de suspensao; 1 no controle sem mudanca).
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_portal' AND session_user = 'nekkora_portal', 'papel real nekkora_portal (sem SET ROLE)');
SELECT count(*) AS n, coalesce(string_agg(q.execution_id::text, ',' ORDER BY q.o), '') AS ids
FROM public.portal_list_executions_pausa($1::uuid, $2, NULL, NULL, NULL, NULL, NULL, NULL, 100)
     WITH ORDINALITY AS q(execution_id, workspace_id, automation_id, automation_name, created_at, started_at, finished_at, duration, status, status_reason, o)
\bind :o4 :tok_k \gset r_
SELECT pg_temp.ok(:'SQLSTATE', 'caso ' || :'caso' || ': chamada liberada sem erro (a resolucao ja tinha passado)');
SELECT pg_temp.afirma(:'r_n' = :'esperado', format('caso %s: leitura apos a mudanca confirmada entre resolucao e leitura = %s linha(s)', :'caso', :'esperado'));
