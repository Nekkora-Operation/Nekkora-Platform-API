-- s_estado - papel: nekkora (superusuario; so leitura). Impressao digital do CONTEUDO de todas as
-- tabelas de public (uma linha por tabela: nome|linhas|md5 das linhas ordenadas). Em user_sessions a
-- coluna last_seen_at fica de fora: e a UNICA escrita admitida no caminho do portal (desenho, 4.3). O
-- runner compara duas impressoes (antes/depois de um bloco so de chamadas do portal): qualquer diferenca
-- - inclusive em client_workspace_grants.last_access_at/updated_at - e falha.
-- Uso: psql -At -v ON_ERROR_STOP=1 (sem o prefixo de assercoes).
\set ON_ERROR_STOP on
SELECT format('SELECT %L || ''|'' || count(*) || ''|'' || coalesce(md5(string_agg(x, ''|'' ORDER BY x)), '''') FROM (SELECT %s AS x FROM public.%I t) s',
              c.relname,
              CASE WHEN c.relname = 'user_sessions' THEN '(to_jsonb(t) - ''last_seen_at'')::text' ELSE 't::text' END,
              c.relname)
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'r'
ORDER BY c.relname \gexec
