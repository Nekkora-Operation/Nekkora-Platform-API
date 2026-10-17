-- Gera o SQL do instantaneo: md5 das linhas de cada tabela de public (todas as colunas atuais). Papel: nekkora.
SELECT 'SELECT ' || quote_literal(c.relname) || ' || ''|'' || count(*) || ''|'' || coalesce(md5(string_agg(x::text, E''\n'' ORDER BY x::text)), ''-'') FROM (SELECT '
    || string_agg(quote_ident(a.attname), ', ' ORDER BY a.attnum) || ' FROM public.' || quote_ident(c.relname) || ') x;'
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
WHERE n.nspname = 'public' AND c.relkind = 'r' GROUP BY c.relname ORDER BY c.relname;
