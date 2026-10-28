-- Pre-voo da janela de elevacao da 017 (missao PLATFORM-API-V1-003). SOMENTE LEITURA; executado por implantacao/implantar.sh
-- como admin_cluster no banco nekkora, com a trava da implantacao obtida, depois do sha256 e do 0|0|0 e ANTES de conceder
-- SUPERUSER. Mesmo formato do pre-voo da 016: uma linha "item|OK|detalhe" ou "item|FALHA|detalhe" por conferencia e, por
-- ultimo, PREFLIGHT_OK somente se TODAS passarem (senao PREFLIGHT_FALHOU). Erro de consulta = sem PREFLIGHT_OK = recusa.
-- Comuns: PostgreSQL 16; historico exatamente 001-<anterior> com sucesso, sem a 017 nem posteriores e sem falha; migrador
-- sem atributo elevado nem ACL de parametro; migrador dono do banco (modelo suportado, 016 rev. 5, sec. 19).
-- Especificas: identidade ainda inexistente (papel novo ausente ou compativel e sem participacao; tabelas de identidade e
-- coluna de vinculo de sessao ausentes); schema ops da 016 presente (o historico ja exige a 016).
WITH c(ordem, item, ok, detalhe) AS (
    SELECT 1, 'servidor_versao_maior_16',
           current_setting('server_version_num')::int BETWEEN 160000 AND 169999, current_setting('server_version')
    UNION ALL
    SELECT 2, 'historico_001_016_com_sucesso_sem_017_nem_posteriores_sem_falha',
           (SELECT count(DISTINCT version) FROM public.flyway_schema_history WHERE success AND version ~ '^0[0-9][0-9]$' AND version <= '016') = 16
           AND NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version > '016')
           AND NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE NOT success),
           (SELECT coalesce(string_agg(coalesce(version, '-') || ':' || success, ' ' ORDER BY installed_rank), 'vazio') FROM public.flyway_schema_history)
    UNION ALL
    SELECT 3, 'migrador_sem_atributo_nem_acl_de_parametro',
           coalesce((SELECT NOT (rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls OR rolreplication)
                     FROM pg_roles WHERE rolname = 'migrador_nekkora'), false)
           AND NOT EXISTS (SELECT 1 FROM pg_parameter_acl WHERE array_to_string(paracl, ',') ~ '(^|,)migrador_nekkora='),
           (SELECT count(*) || ' ACL de parametro no total' FROM pg_parameter_acl)
    UNION ALL
    SELECT 4, 'migrador_dono_do_banco',
           (SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()) = 'migrador_nekkora',
           (SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database())
    UNION ALL
    SELECT 5, 'papel_nekkora_identity_owner_ausente_ou_compativel_sem_participacao',
           NOT EXISTS (SELECT 1 FROM pg_roles g WHERE g.rolname = 'nekkora_identity_owner' AND (g.rolsuper OR g.rolcreaterole OR g.rolcreatedb
                       OR g.rolreplication OR g.rolbypassrls OR g.rolcanlogin))
           AND NOT EXISTS (SELECT 1 FROM pg_auth_members am JOIN pg_roles g ON g.oid IN (am.roleid, am.member) WHERE g.rolname = 'nekkora_identity_owner'),
           coalesce((SELECT 'pre-existente' FROM pg_roles WHERE rolname = 'nekkora_identity_owner'), 'ausente')
    UNION ALL
    SELECT 6, 'tabelas_de_identidade_ausentes',
           NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
                       AND c.relname IN ('identities', 'identity_credentials', 'identity_users', 'identity_mfa_factors',
                                         'identity_auth_challenges', 'identity_sessions', 'identity_mfa_recovery_codes',
                                         'identity_link_challenges', 'identity_email_tokens', 'identity_events')),
           '-'
    UNION ALL
    SELECT 7, 'user_sessions_sem_identity_session_id',
           NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'user_sessions'
                       AND column_name = 'identity_session_id'), '-'
    UNION ALL
    SELECT 8, 'schema_ops_presente', EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'ops'), '-'
)
SELECT linha FROM (
    SELECT ordem, item || '|' || CASE WHEN coalesce(ok, false) THEN 'OK' ELSE 'FALHA' END || '|' || coalesce(detalhe, '-') AS linha FROM c
    UNION ALL
    SELECT 99, CASE WHEN bool_and(coalesce(ok, false)) AND count(*) = 8 THEN 'PREFLIGHT_OK' ELSE 'PREFLIGHT_FALHOU' END FROM c
) x ORDER BY ordem;
