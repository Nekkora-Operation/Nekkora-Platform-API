-- Pre-voo da janela de elevacao da 014 (BYPASSRLS; R2b-0, T1). SOMENTE LEITURA (transacao READ ONLY, desfeita no fim);
-- executado por implantacao/implantar.sh como admin_cluster no banco nekkora, com a trava da implantacao obtida, depois do
-- sha256 e do 0|0|0 e ANTES de conceder. Existe porque a linha da 014 VINCULADA a um ambiente
-- ("014 <sha> tipo=bypassrls ambiente=<id>") exige pre-voo oficial; sem este arquivo a elevacao e recusada.
-- Confere que a base esta exatamente no ponto em que a ativacao da 014 tem efeito: 001-013 registradas com sucesso,
-- nada da 014 em diante, objetos da 014 ausentes, user_sessions com RLS forcada (o motivo da janela BYPASSRLS), banco do
-- migrador e migrador sem atributo.
-- Saida: uma linha "item|OK|detalhe" ou "item|FALHA|detalhe" por conferencia e, por ultimo, PREFLIGHT_OK somente se
-- TODAS passarem (senao PREFLIGHT_FALHOU). Erro de consulta = sem PREFLIGHT_OK = recusa (falha fechada).
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '15s';
SET LOCAL lock_timeout = '2s';
WITH c(ordem, item, ok, detalhe) AS (
    SELECT 1, 'servidor_versao_maior_16',
           current_setting('server_version_num')::int BETWEEN 160000 AND 169999, current_setting('server_version')
    UNION ALL
    SELECT 2, 'historico_001_013_com_sucesso_sem_014_em_diante_sem_falha',
           (SELECT count(DISTINCT version) FROM public.flyway_schema_history WHERE success AND version ~ '^0(0[1-9]|1[0-3])$') = 13
           AND NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version IS NOT NULL AND version !~ '^0(0[1-9]|1[0-3])$')
           AND NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE NOT success),
           (SELECT coalesce(string_agg(coalesce(version, '-') || ':' || success, ' ' ORDER BY installed_rank), 'vazio') FROM public.flyway_schema_history)
    UNION ALL
    SELECT 3, 'user_sessions_sem_colunas_da_014',
           to_regclass('public.user_sessions') IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = to_regclass('public.user_sessions') AND NOT attisdropped
                           AND attname IN ('mfa_verified_at', 'mfa_step_up_at', 'csrf_token')),
           coalesce((SELECT string_agg(attname, ',' ORDER BY attname) FROM pg_attribute WHERE attrelid = to_regclass('public.user_sessions')
                     AND NOT attisdropped AND attname IN ('mfa_verified_at', 'mfa_step_up_at', 'csrf_token')), 'nenhuma')
    UNION ALL
    SELECT 4, 'tabelas_e_triggers_da_014_ausentes',
           to_regclass('public.user_mfa_factors') IS NULL AND to_regclass('public.user_mfa_recovery_codes') IS NULL
           AND to_regclass('public.auth_challenges') IS NULL AND to_regclass('public.mfa_events') IS NULL
           AND NOT EXISTS (SELECT 1 FROM pg_trigger WHERE NOT tgisinternal
                           AND tgname IN ('trg_user_sessions_enforce_mfa', 'trg_user_sessions_prevent_reactivation')),
           '-'
    UNION ALL
    SELECT 5, 'user_sessions_rls_habilitada_e_forcada',
           coalesce((SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = to_regclass('public.user_sessions')), false),
           coalesce((SELECT 'habilitada=' || relrowsecurity || ' forcada=' || relforcerowsecurity || ' dono=' || pg_get_userbyid(relowner)
                     FROM pg_class WHERE oid = to_regclass('public.user_sessions')), 'tabela ausente')
    UNION ALL
    SELECT 6, 'banco_do_migrador',
           (SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()) = 'migrador_nekkora',
           (SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database())
    UNION ALL
    SELECT 7, 'migrador_sem_atributo_nem_acl_de_parametro',
           coalesce((SELECT NOT (rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls OR rolreplication)
                     FROM pg_roles WHERE rolname = 'migrador_nekkora'), false)
           AND NOT EXISTS (SELECT 1 FROM pg_parameter_acl WHERE array_to_string(paracl, ',') ~ '(^|,)migrador_nekkora='),
           (SELECT count(*) || ' ACL de parametro no total' FROM pg_parameter_acl)
)
SELECT linha FROM (
    SELECT ordem, item || '|' || CASE WHEN coalesce(ok, false) THEN 'OK' ELSE 'FALHA' END || '|' || coalesce(detalhe, '-') AS linha FROM c
    UNION ALL
    SELECT 99, CASE WHEN bool_and(coalesce(ok, false)) AND count(*) = 7 THEN 'PREFLIGHT_OK' ELSE 'PREFLIGHT_FALHOU' END FROM c
) x ORDER BY ordem;
ROLLBACK;
