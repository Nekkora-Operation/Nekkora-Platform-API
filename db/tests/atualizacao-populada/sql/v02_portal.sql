-- v02 - papel: nekkora_portal. Depois da atualizacao, pelas funcoes da 013 com parametros de bind (tokens
-- nunca no texto do SQL): a sessao PRE-014 ativa da conta de referencia foi revogada (NK401); a sessao nova
-- enxerga a concessao e as execucoes que ja existiam antes da atualizacao. Recebe -v org, tok_pre, tok_novo.
-- ind recebe :var SEM aspas (com :'var' o valor chegaria com as aspas).
\set ON_ERROR_STOP on
\pset pager off
SELECT current_user = 'nekkora_portal' AS papel_correto \gset
\if :papel_correto
\else
    \echo 'FALHA: papel diferente de nekkora_portal'
    \quit 1
\endif

\set ON_ERROR_STOP off
SELECT count(*) FROM public.portal_list_workspaces($1::uuid, $2) \bind :org :tok_pre \g /dev/null
\echo pre_014_sqlstate=:SQLSTATE
\set ON_ERROR_STOP on
SELECT 'workspaces=' || string_agg(workspace_id::text, ',' ORDER BY workspace_id) FROM public.portal_list_workspaces($1::uuid, $2) \bind :org :tok_novo \g
SELECT 'execucoes_pagina=' || count(*) FROM public.portal_list_executions($1::uuid, $2, NULL, NULL, NULL, NULL, NULL, NULL, 100) \bind :org :tok_novo \g
SELECT 'execucoes_ids=' || md5(string_agg(execution_id::text, ',' ORDER BY execution_id))
FROM public.portal_list_executions($1::uuid, $2, NULL, NULL, NULL, NULL, NULL, NULL, 100) \bind :org :tok_novo \g
