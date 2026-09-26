-- _portal.inc.sql - prefixado pelo run.sh a todo script, depois de _asserts.inc.sql e _ids.inc.sql.
--
-- Chamadas do portal: sempre pelo protocolo estendido com parametros de bind (\bind ... \gset do
-- psql 16), como o pgJDBC faz. O token vai SEMPRE como parametro ($2), nunca no texto do SQL.
-- \bind nao envia NULL: os parametros opcionais vao como '' e viram NULL com NULLIF no texto do
-- comando (lado do cliente, como um PreparedStatement com setNull).
--
-- Cada template agrega o resultado numa unica linha, lida com \gset r_:
--   r_n   - numero de linhas devolvidas
--   r_ids - ids na ordem devolvida pela funcao (WITH ORDINALITY)
--   r_fp  - md5 de todas as linhas completas, na ordem (impressao digital do resultado)
--   r_lc / r_li - created_at (ISO 8601, sem espacos: vai como argumento de bind) e execution_id da ultima linha (cursor keyset), so em q_ex
-- Chamada que deve falhar: ON_ERROR_STOP off so em volta dela e verificacao do SQLSTATE/mensagem.
-- Se a chamada falha, \gset NAO altera r_*; por isso toda chamada bem-sucedida e seguida de
-- pg_temp.ok(:'SQLSTATE', ...) quando o valor anterior de r_* poderia mascarar o resultado.

CREATE FUNCTION pg_temp.ok(p_sqlstate TEXT, p_caso TEXT) RETURNS TEXT LANGUAGE plpgsql AS $f$
BEGIN
    IF p_sqlstate IS DISTINCT FROM '00000' THEN
        RAISE EXCEPTION 'FALHA DE ASSERCAO: chamada deveria ter sucesso (SQLSTATE %): %', p_sqlstate, p_caso;
    END IF;
    RETURN 'ok: ' || p_caso;
END;
$f$;

-- NK401 com a mensagem fixa do desenho (secao 4.5), sem distinguir a causa
CREATE FUNCTION pg_temp.nk401(p_sqlstate TEXT, p_msg TEXT, p_caso TEXT) RETURNS TEXT LANGUAGE plpgsql AS $f$
BEGIN
    IF p_sqlstate IS DISTINCT FROM 'NK401' THEN
        RAISE EXCEPTION 'FALHA DE ASSERCAO: esperado NK401, obtido SQLSTATE %: %', p_sqlstate, p_caso;
    END IF;
    IF p_msg IS DISTINCT FROM 'sessao do portal invalida' THEN
        RAISE EXCEPTION 'FALHA DE ASSERCAO: NK401 com mensagem diferente da fixa: %', p_caso;
    END IF;
    RETURN 'ok: NK401 - ' || p_caso;
END;
$f$;

CREATE FUNCTION pg_temp.e22023(p_sqlstate TEXT, p_msg TEXT, p_caso TEXT) RETURNS TEXT LANGUAGE plpgsql AS $f$
BEGIN
    IF p_sqlstate IS DISTINCT FROM '22023' OR p_msg IS DISTINCT FROM 'parametro de consulta do portal invalido' THEN
        RAISE EXCEPTION 'FALHA DE ASSERCAO: esperado 22023 com mensagem fixa, obtido %: %', p_sqlstate, p_caso;
    END IF;
    RETURN 'ok: 22023 - ' || p_caso;
END;
$f$;

\set q_ws 'SELECT count(*) AS n, coalesce(string_agg(q.workspace_id::text, '','' ORDER BY q.o), '''') AS ids, md5(coalesce(string_agg(q::text, ''|'' ORDER BY q.o), '''')) AS fp FROM public.portal_list_workspaces($1::uuid, NULLIF($2, '''')) WITH ORDINALITY AS q(workspace_id, name, o)'

\set q_ex 'SELECT count(*) AS n, coalesce(string_agg(q.execution_id::text, '','' ORDER BY q.o), '''') AS ids, md5(coalesce(string_agg(q::text, ''|'' ORDER BY q.o), '''')) AS fp, (array_agg(to_json(q.created_at) #>> ''{}'' ORDER BY q.o DESC))[1] AS lc, (array_agg(q.execution_id::text ORDER BY q.o DESC))[1] AS li FROM public.portal_list_executions($1::uuid, NULLIF($2, ''''), NULLIF($3, '''')::uuid, NULLIF($4, '''')::smallint[], NULLIF($5, '''')::timestamptz, NULLIF($6, '''')::timestamptz, NULLIF($7, '''')::timestamptz, NULLIF($8, '''')::uuid, NULLIF($9, '''')::integer) WITH ORDINALITY AS q(execution_id, workspace_id, automation_id, automation_name, created_at, started_at, finished_at, duration, status, status_reason, o)'

\set q_get 'SELECT count(*) AS n, coalesce(string_agg(q.execution_id::text, '','' ORDER BY q.o), '''') AS ids, md5(coalesce(string_agg(q::text, ''|'' ORDER BY q.o), '''')) AS fp FROM public.portal_get_execution($1::uuid, NULLIF($2, ''''), NULLIF($3, '''')::uuid) WITH ORDINALITY AS q(execution_id, workspace_id, automation_id, automation_name, created_at, started_at, finished_at, duration, status, status_reason, o)'

\set q_ev 'SELECT count(*) AS n, coalesce(string_agg(q.event_type::text, '','' ORDER BY q.o), '''') AS ids, md5(coalesce(string_agg(q::text, ''|'' ORDER BY q.o), '''')) AS fp FROM public.portal_list_execution_events($1::uuid, NULLIF($2, ''''), NULLIF($3, '''')::uuid) WITH ORDINALITY AS q(event_type, received_at, o)'
