-- Pre-voo da janela de elevacao da 016 (P3). SOMENTE LEITURA; executado por implantacao/implantar.sh como admin_cluster
-- no banco nekkora, com a trava da implantacao obtida, depois do sha256 e do 0|0|0 e ANTES de conceder SUPERUSER.
-- E tambem o criterio de "016 NAO aplicada" da reconciliacao (decisao P3, sec. 6): a retomada so eleva se ele passar.
-- Saida: uma linha "item|OK|detalhe" ou "item|FALHA|detalhe" por conferencia e, por ultimo, PREFLIGHT_OK somente se
-- TODAS passarem (senao PREFLIGHT_FALHOU). Erro de consulta = sem PREFLIGHT_OK = recusa (falha fechada).
WITH papeis(r) AS (SELECT unnest(ARRAY['nekkora_ops_mfa_owner', 'nekkora_ops_mfa', 'nekkora_ops_ca', 'nekkora_ops_intake'])),
c(ordem, item, ok, detalhe) AS (
    SELECT 1, 'servidor_versao_maior_16',
           current_setting('server_version_num')::int BETWEEN 160000 AND 169999, current_setting('server_version')
    UNION ALL
    SELECT 2, 'sslinfo_disponivel_nao_confiavel_nao_instalada',
           EXISTS (SELECT 1 FROM pg_available_extension_versions WHERE name = 'sslinfo' AND NOT trusted AND superuser)
           AND NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'sslinfo'),
           coalesce((SELECT string_agg(version || ' trusted=' || trusted || ' superuser=' || superuser || ' instalada=' || installed, '; ')
                     FROM pg_available_extension_versions WHERE name = 'sslinfo'), 'indisponivel')
    UNION ALL
    SELECT 3, 'schema_ops_ausente', NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'ops'), '-'
    UNION ALL
    SELECT 4, 'mfa_events_sem_ops_request_id',
           NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'mfa_events'
                       AND column_name = 'ops_request_id'), '-'
    UNION ALL
    SELECT 5, 'historico_001_015_com_sucesso_sem_016_sem_falha',
           (SELECT count(DISTINCT version) FROM public.flyway_schema_history WHERE success AND version ~ '^0(0[1-9]|1[0-5])$') = 15
           AND NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version = '016')
           AND NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE NOT success),
           (SELECT coalesce(string_agg(coalesce(version, '-') || ':' || success, ' ' ORDER BY installed_rank), 'vazio') FROM public.flyway_schema_history)
    UNION ALL
    SELECT 6, 'migrador_sem_atributo_nem_acl_de_parametro',
           coalesce((SELECT NOT (rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls OR rolreplication)
                     FROM pg_roles WHERE rolname = 'migrador_nekkora'), false)
           AND NOT EXISTS (SELECT 1 FROM pg_parameter_acl WHERE array_to_string(paracl, ',') ~ '(^|,)migrador_nekkora='),
           (SELECT count(*) || ' ACL de parametro no total' FROM pg_parameter_acl)
    UNION ALL
    SELECT 7, 'papeis_016_ausentes_ou_compativeis_sem_participacao',
           NOT EXISTS (SELECT 1 FROM pg_roles g JOIN papeis p ON p.r = g.rolname
                       WHERE g.rolsuper OR g.rolcreaterole OR g.rolcreatedb OR g.rolreplication OR g.rolbypassrls
                          OR (g.rolname IN ('nekkora_ops_mfa_owner', 'nekkora_ops_mfa') AND g.rolcanlogin)
                          OR (g.rolname IN ('nekkora_ops_ca', 'nekkora_ops_intake') AND NOT g.rolcanlogin))
           AND NOT EXISTS (SELECT 1 FROM pg_auth_members am JOIN pg_roles g ON g.oid IN (am.roleid, am.member) JOIN papeis p ON p.r = g.rolname),
           coalesce((SELECT string_agg(g.rolname, ',' ORDER BY g.rolname) FROM pg_roles g JOIN papeis p ON p.r = g.rolname), 'nenhum pre-existente')
)
SELECT linha FROM (
    SELECT ordem, item || '|' || CASE WHEN coalesce(ok, false) THEN 'OK' ELSE 'FALHA' END || '|' || coalesce(detalhe, '-') AS linha FROM c
    UNION ALL
    SELECT 99, CASE WHEN bool_and(coalesce(ok, false)) AND count(*) = 7 THEN 'PREFLIGHT_OK' ELSE 'PREFLIGHT_FALHOU' END FROM c
) x ORDER BY ordem;
