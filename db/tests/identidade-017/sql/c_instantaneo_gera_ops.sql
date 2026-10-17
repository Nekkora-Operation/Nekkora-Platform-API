-- Gera o SQL do instantaneo de public E ops (parte D): md5 das linhas de cada tabela, com as colunas ATUAIS. Papel: nekkora.
SELECT 'SELECT ' || quote_literal(n.nspname || '.' || c.relname) || ' || ''|'' || count(*) || ''|'' || coalesce(md5(string_agg(x::text, E''\n'' ORDER BY x::text)), ''-'') FROM (SELECT '
    || string_agg(quote_ident(a.attname), ', ' ORDER BY a.attnum) || ' FROM ' || quote_ident(n.nspname) || '.' || quote_ident(c.relname) || ') x;'
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
WHERE n.nspname IN ('public', 'ops') AND c.relkind = 'r' GROUP BY n.nspname, c.relname ORDER BY n.nspname, c.relname;
