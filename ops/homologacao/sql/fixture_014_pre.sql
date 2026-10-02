-- Semeadura DECLARADA pre-014 (R2b-0, T2): SO ensaio-*, SO com a 013 registrada e a 014 pendente, pelo admin_cluster
-- (superusuario: ve e grava todas as linhas apesar da RLS forcada de user_sessions). Chamada por
-- "homologacao.sh semear-pre014 <id>" (que confere antes a pausa antes-de-elevar@014 do runner e o 0|0|0) e, na base
-- descartavel da R2b-0, diretamente por psql. psql -X -At -v ON_ERROR_STOP=1 -v ambiente=<id> < este arquivo.
--
-- Dados EXCLUSIVAMENTE sinteticos: uma organizacao "r2b-semente-014", 2 usuarios com e-mail em dominio reservado
-- (.invalid) e 18 sessoes em 3 grupos de 6 (criadas ha ~2 h):
--   V  nao revogadas e validas    (expires_at = agora + 1 dia)       -> a ativacao da 014 deve revoga-las
--   E  nao revogadas e expiradas  (expires_at = created_at + 1 h)    -> idem: a 014 usa so revoked_at IS NULL
--   R  ja revogadas (controle)    (revoked_at = created_at + 10 min) -> a 014 nao pode altera-las
-- session_token_hash = sha256 hex de 'r2b-semente-014|<grupo>|<n>': deterministico, para a conferencia
-- (testes/semente_014.sh) identificar o grupo sem exportar valor algum. Nao e credencial de sessao real.
-- Saida (depois do COMMIT, so contagens e digests): linhas SEMENTE|... e, por ultimo, SEMENTE_APLICADA.
\set ON_ERROR_STOP 1
BEGIN;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';
SELECT 'SEMENTE|ambiente=' || set_config('nekkora.semente_ambiente', :'ambiente', true);
DO $$
DECLARE
    v_amb TEXT := current_setting('nekkora.semente_ambiente');
BEGIN
    IF v_amb !~ '^ensaio-[a-z0-9-]+$' THEN
        RAISE EXCEPTION 'semeadura pre-014 so em ambiente ensaio-* (recebido: %)', v_amb;
    END IF;
    IF current_setting('is_superuser') <> 'on' THEN
        RAISE EXCEPTION 'semeadura exige o administrador do cluster (current_user=%)', current_user;
    END IF;
    IF to_regclass('public.flyway_schema_history') IS NULL THEN
        RAISE EXCEPTION 'sem historico Flyway: a semeadura so roda com a 013 registrada';
    END IF;
    IF (SELECT count(DISTINCT version) FROM public.flyway_schema_history WHERE success AND version ~ '^0(0[1-9]|1[0-3])$') <> 13
       OR EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version IS NOT NULL AND version !~ '^0(0[1-9]|1[0-3])$')
       OR EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE NOT success) THEN
        RAISE EXCEPTION 'historico fora do ponto da semeadura (exigido: 001-013 com sucesso, nada da 014 em diante, sem falha): %',
            (SELECT string_agg(coalesce(version, '-') || ':' || success, ' ' ORDER BY installed_rank) FROM public.flyway_schema_history);
    END IF;
    IF EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'public.user_sessions'::regclass AND NOT attisdropped
               AND attname IN ('mfa_verified_at', 'mfa_step_up_at', 'csrf_token')) THEN
        RAISE EXCEPTION 'user_sessions ja tem colunas da 014: semeadura recusada';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'migrador_nekkora'
               AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls OR rolreplication)) THEN
        RAISE EXCEPTION 'migrador_nekkora com atributo elevado: semeadura recusada';
    END IF;
    IF EXISTS (SELECT 1 FROM public.organizations WHERE name = 'r2b-semente-014') THEN
        RAISE EXCEPTION 'semente anterior encontrada (organizacao r2b-semente-014): semeadura recusada, nao se repete';
    END IF;
END $$;

WITH o AS (
    INSERT INTO public.organizations (name, status, created_at, updated_at)
    VALUES ('r2b-semente-014', 1, now() - interval '3 hours', now() - interval '3 hours')
    RETURNING organization_id
)
INSERT INTO public.users (organization_id, account_type, display_name, email, status, created_at, updated_at)
SELECT o.organization_id, 1, 'r2b semente ' || n, 'r2b-semente-' || n || '@semente-014.invalid', 1,
       now() - interval '3 hours', now() - interval '3 hours'
FROM o, generate_series(1, 2) AS n;

INSERT INTO public.user_sessions (organization_id, user_id, session_token_hash, created_at, expires_at, last_seen_at, revoked_at)
SELECT u.organization_id, u.user_id,
       encode(sha256(convert_to('r2b-semente-014|' || g.grupo || '|' || n, 'UTF8')), 'hex'),
       now() - interval '2 hours' - n * interval '1 minute',
       CASE g.grupo WHEN 'E' THEN now() - interval '2 hours' - n * interval '1 minute' + interval '1 hour'
                    ELSE now() + interval '1 day' END,
       now() - interval '2 hours' - n * interval '1 minute' + interval '5 minutes',
       CASE g.grupo WHEN 'R' THEN now() - interval '2 hours' - n * interval '1 minute' + interval '10 minutes' END
FROM (VALUES ('V'), ('E'), ('R')) AS g(grupo)
CROSS JOIN generate_series(1, 6) AS n
JOIN public.users u ON u.email = 'r2b-semente-' || (1 + n % 2) || '@semente-014.invalid'
JOIN public.organizations og ON og.organization_id = u.organization_id AND og.name = 'r2b-semente-014';

DO $$
BEGIN
    IF (SELECT count(*) FROM public.user_sessions s JOIN public.organizations o USING (organization_id) WHERE o.name = 'r2b-semente-014') <> 18 THEN
        RAISE EXCEPTION 'semeadura incompleta: transacao desfeita';
    END IF;
END $$;
COMMIT;

-- manifesto (somente leitura, depois do COMMIT): o mesmo calculo de testes/semente_014.sh
BEGIN TRANSACTION READ ONLY;
WITH esperado AS (
    SELECT g.grupo, encode(sha256(convert_to('r2b-semente-014|' || g.grupo || '|' || n, 'UTF8')), 'hex') AS h
    FROM (VALUES ('V'), ('E'), ('R')) AS g(grupo) CROSS JOIN generate_series(1, 6) AS n
)
SELECT 'SEMENTE|grupo=' || e.grupo || '|qtde=' || count(s.session_id)
       || '|nao_revogadas=' || count(*) FILTER (WHERE s.session_id IS NOT NULL AND s.revoked_at IS NULL)
       || '|expiradas=' || count(*) FILTER (WHERE s.expires_at <= now())
       || '|digest_preservadas=' || md5(string_agg(s.session_id || '|' || s.organization_id || '|' || s.user_id || '|' || s.session_token_hash
              || '|' || s.created_at || '|' || s.expires_at || '|' || s.last_seen_at, ',' ORDER BY e.h))
       || '|digest_revoked_at=' || md5(string_agg(coalesce(s.revoked_at::text, '-'), ',' ORDER BY e.h))
FROM esperado e LEFT JOIN public.user_sessions s ON s.session_token_hash = e.h
GROUP BY e.grupo ORDER BY e.grupo;
SELECT 'SEMENTE|organizacao_e_usuarios|digest=' || md5(
           (SELECT string_agg(o.organization_id || '|' || o.name || '|' || o.status || '|' || o.created_at || '|' || o.updated_at, ',')
              FROM public.organizations o WHERE o.name = 'r2b-semente-014')
           || '#' || (SELECT string_agg(u.user_id || '|' || u.email || '|' || u.status || '|' || u.updated_at, ',' ORDER BY u.email)
              FROM public.users u JOIN public.organizations o USING (organization_id) WHERE o.name = 'r2b-semente-014'));
SELECT 'SEMENTE|sessoes_total_na_base=' || count(*) FROM public.user_sessions;
ROLLBACK;
\echo SEMENTE_APLICADA
