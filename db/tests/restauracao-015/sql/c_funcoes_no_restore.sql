-- c_funcoes_no_restore - papel: nekkora (somente leitura de catalogo). Lista as funcoes do schema public que o
-- PostgreSQL EXECUTA durante uma restauracao logica (pg_restore): as chamadas em CHECK de tabela ou de dominio,
-- em expressoes e predicados de indice e em colunas geradas, mais tudo o que elas chamam (fechamento
-- transitivo por pg_depend nao cobre corpo de funcao, entao o fechamento e feito pelo texto do corpo contra os
-- nomes das funcoes do schema). Para cada uma: linguagem, configuracao (search_path) e se o corpo chama outra
-- funcao do schema SEM qualificar. Uso: psql -At -v ON_ERROR_STOP=1.
\set ON_ERROR_STOP on
WITH RECURSIVE
fn AS (
    SELECT p.oid, p.proname, p.prosrc, p.proconfig, l.lanname
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace JOIN pg_language l ON l.oid = p.prolang
    WHERE n.nspname = 'public'
),
expr AS (
    SELECT pg_get_constraintdef(c.oid) AS txt FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
    WHERE n.nspname = 'public' AND c.contype = 'c'
    UNION ALL
    SELECT pg_get_indexdef(i.indexrelid) FROM pg_index i JOIN pg_class t ON t.oid = i.indrelid JOIN pg_namespace n ON n.oid = t.relnamespace
    WHERE n.nspname = 'public' AND (i.indexprs IS NOT NULL OR i.indpred IS NOT NULL)
    UNION ALL
    SELECT pg_get_expr(d.adbin, d.adrelid) FROM pg_attrdef d JOIN pg_attribute a ON a.attrelid = d.adrelid AND a.attnum = d.adnum
    JOIN pg_class t ON t.oid = d.adrelid JOIN pg_namespace n ON n.oid = t.relnamespace
    WHERE n.nspname = 'public' AND a.attgenerated <> ''
),
raiz AS (
    SELECT DISTINCT f.oid FROM fn f, expr e WHERE e.txt ~ ('(^|[^A-Za-z0-9_.])(public\.)?' || f.proname || '\(')
),
fecho(oid) AS (
    SELECT oid FROM raiz
    UNION
    SELECT g.oid FROM fecho x JOIN fn f ON f.oid = x.oid JOIN fn g ON f.prosrc ~ ('(^|[^A-Za-z0-9_.])(public\.)?' || g.proname || '\(') AND g.oid <> f.oid
)
SELECT format('%s|lang=%s|config=%s|chama_sem_schema=%s', f.oid::regprocedure, f.lanname, coalesce(array_to_string(f.proconfig, ';'), '-'),
              coalesce((SELECT string_agg(g.proname, ',' ORDER BY g.proname) FROM fn g
                        WHERE g.oid <> f.oid AND f.prosrc ~ ('(^|[^A-Za-z0-9_.])' || g.proname || '\(')), '-'))
FROM fecho x JOIN fn f ON f.oid = x.oid
ORDER BY 1;
