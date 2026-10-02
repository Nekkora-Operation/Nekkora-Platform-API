-- Estado da semeadura pre-014 (R2b-0, T3). SOMENTE LEITURA (READ ONLY + ROLLBACK, com limites de duracao), por uma
-- identidade que veja TODAS as linhas (admin_cluster; na base descartavel, o superusuario do cluster). Usado por
-- testes/semente_014.sh. Saida: linhas "chave=valor", so contagens, digests e o resultado de comparacoes de horario;
-- nenhum token, identificador ou dado pessoal. Funciona antes e depois da 014 (colunas novas lidas por to_jsonb).
-- Variaveis psql: inicio e fim (janela registrada da 014, ISO 8601; '-' quando nao se aplica).
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '15s';
SET LOCAL lock_timeout = '2s';
SET LOCAL idle_in_transaction_session_timeout = '30s';
SELECT 'identidade_ve_todas_as_linhas=' || (current_setting('is_superuser') = 'on'
        OR (SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user))::int;
SELECT 'colunas_014=' || count(*) FROM pg_attribute
 WHERE attrelid = 'public.user_sessions'::regclass AND attname = 'mfa_verified_at' AND NOT attisdropped;
WITH esperado AS (
    SELECT g.grupo, encode(sha256(convert_to('r2b-semente-014|' || g.grupo || '|' || n, 'UTF8')), 'hex') AS h
    FROM (VALUES ('V'), ('E'), ('R')) AS g(grupo) CROSS JOIN generate_series(1, 6) AS n
), s AS (
    SELECT e.grupo, e.h, u.*, to_jsonb(u) AS j
    FROM esperado e LEFT JOIN public.user_sessions u ON u.session_token_hash = e.h
)
SELECT linha FROM (
    SELECT grupo, 1 AS k, 'grupo_' || grupo || '_qtde=' || count(session_id) AS linha FROM s GROUP BY grupo
    UNION ALL SELECT grupo, 2, 'grupo_' || grupo || '_nao_revogadas=' || count(*) FILTER (WHERE session_id IS NOT NULL AND revoked_at IS NULL) FROM s GROUP BY grupo
    UNION ALL SELECT grupo, 3, 'grupo_' || grupo || '_expiradas=' || count(*) FILTER (WHERE expires_at <= now()) FROM s GROUP BY grupo
    UNION ALL SELECT grupo, 4, 'grupo_' || grupo || '_digest_preservadas=' || md5(string_agg(session_id || '|' || organization_id || '|' || user_id
              || '|' || session_token_hash || '|' || created_at || '|' || expires_at || '|' || last_seen_at, ',' ORDER BY h)) FROM s GROUP BY grupo
    UNION ALL SELECT grupo, 5, 'grupo_' || grupo || '_digest_revoked_at=' || md5(string_agg(coalesce(revoked_at::text, '-'), ',' ORDER BY h)) FROM s GROUP BY grupo
    UNION ALL SELECT grupo, 6, 'grupo_' || grupo || '_colunas_novas_nao_nulas=' || count(*) FILTER (WHERE (j ->> 'mfa_verified_at') IS NOT NULL
              OR (j ->> 'mfa_step_up_at') IS NOT NULL OR (j ->> 'csrf_token') IS NOT NULL) FROM s GROUP BY grupo
    UNION ALL SELECT 'X', 7, 've_instantes_distintos=' || count(DISTINCT revoked_at) FROM s WHERE grupo IN ('V', 'E')
    UNION ALL SELECT 'X', 8, 've_revogadas_na_janela=' || CASE WHEN :'inicio' = '-' OR :'fim' = '-' THEN '-'
              ELSE (count(*) FILTER (WHERE revoked_at BETWEEN nullif(:'inicio', '-')::timestamptz AND nullif(:'fim', '-')::timestamptz))::text END
              FROM s WHERE grupo IN ('V', 'E')
) x ORDER BY grupo, k;
SELECT 'organizacao_e_usuarios_digest=' || md5(
           coalesce((SELECT string_agg(o.organization_id || '|' || o.name || '|' || o.status || '|' || o.created_at || '|' || o.updated_at, ',')
              FROM public.organizations o WHERE o.name = 'r2b-semente-014'), '-')
           || '#' || coalesce((SELECT string_agg(u.user_id || '|' || u.email || '|' || u.status || '|' || u.updated_at, ',' ORDER BY u.email)
              FROM public.users u JOIN public.organizations o USING (organization_id) WHERE o.name = 'r2b-semente-014'), '-'));
SELECT 'sessoes_total=' || count(*) FROM public.user_sessions;
SELECT 'sessoes_fora_da_semente=' || count(*) FROM public.user_sessions
 WHERE session_token_hash NOT IN (SELECT encode(sha256(convert_to('r2b-semente-014|' || g || '|' || n, 'UTF8')), 'hex')
                                  FROM unnest(ARRAY['V', 'E', 'R']) AS g CROSS JOIN generate_series(1, 6) AS n);
SELECT 'nao_revogadas_sem_marcador_na_base=' || count(*) FROM public.user_sessions u
 WHERE u.revoked_at IS NULL AND (to_jsonb(u) ->> 'mfa_verified_at') IS NULL;
ROLLBACK;
