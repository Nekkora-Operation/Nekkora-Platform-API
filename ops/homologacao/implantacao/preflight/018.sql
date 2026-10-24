-- Pre-voo da janela de elevacao da 018 (missao PLATFORM-API-V1-003). SOMENTE LEITURA; executado por implantacao/implantar.sh
-- como admin_cluster no banco nekkora, com a trava da implantacao obtida, depois do sha256 e do 0|0|0 e ANTES de conceder
-- SUPERUSER. Mesmo formato do pre-voo da 016: uma linha "item|OK|detalhe" ou "item|FALHA|detalhe" por conferencia e, por
-- ultimo, PREFLIGHT_OK somente se TODAS passarem (senao PREFLIGHT_FALHOU). Erro de consulta = sem PREFLIGHT_OK = recusa.
-- Comuns: PostgreSQL 16; historico exatamente 001-<anterior> com sucesso, sem a 018 nem posteriores e sem falha; migrador
-- sem atributo elevado nem ACL de parametro; migrador dono do banco (modelo suportado, 016 rev. 5, sec. 19).
-- Especificas: os quatro corpos que a 018 substitui ainda iguais aos da 016/017 (SHA-256 do prosrc com os MESMOS valores
-- fixados na sec. 1 da 018: a divergencia recusa antes de abrir a janela, nao dentro dela); SELECT (created_at) ainda nao
-- concedido ao dono operacional (a 018 o concede); donos sem participacao.
WITH c(ordem, item, ok, detalhe) AS (
    SELECT 1, 'servidor_versao_maior_16',
           current_setting('server_version_num')::int BETWEEN 160000 AND 169999, current_setting('server_version')
    UNION ALL
    SELECT 2, 'historico_001_017_com_sucesso_sem_018_nem_posteriores_sem_falha',
           (SELECT count(DISTINCT version) FROM public.flyway_schema_history WHERE success AND version ~ '^0[0-9][0-9]$' AND version <= '017') = 17
           AND NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version > '017')
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
    SELECT 5, 'quatro_corpos_iguais_aos_da_016_017',
           (SELECT count(*) FROM (VALUES ('public.ops_mfa_execute(uuid)', 'b9380080b550a9b8242bcd25a40138844bd2eef873e14dd697d46b2e3cd06e52'),
                 ('public.ops_operator_revoke(uuid,text)', '6bce978fe948c6517897c3cd339d65515e372aea49a0b762616f009acd8cd5f7'),
                 ('public.ops_operator_disable(uuid)', '89b6c1c718cfce05f01ad7a5516ca49cb428a53ca899e87b2a12a3a3bbfcedf1'),
                 ('public.identity_mfa_replace(text,uuid,text[])', 'e48d60ee4c0ddcd40d73e6c7337793ef850650755a5cacecb697f2ba43f82d9f'))
                 AS e(assinatura, sha256)
             JOIN pg_proc p ON p.oid = to_regprocedure(e.assinatura)
             WHERE encode(sha256(convert_to(p.prosrc, 'UTF8')), 'hex') = e.sha256) = 4,
           (SELECT string_agg(p.proname || '=' || left(encode(sha256(convert_to(p.prosrc, 'UTF8')), 'hex'), 12), ' ' ORDER BY p.proname)
              FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
             WHERE n.nspname = 'public' AND p.proname IN ('ops_mfa_execute', 'ops_operator_revoke', 'ops_operator_disable', 'identity_mfa_replace'))
    UNION ALL
    SELECT 6, 'created_at_ainda_sem_select_do_dono_operacional',
           NOT has_column_privilege('nekkora_ops_mfa_owner', 'public.user_mfa_factors', 'created_at', 'SELECT')
           AND NOT has_column_privilege('nekkora_ops_mfa_owner', 'public.user_mfa_recovery_codes', 'created_at', 'SELECT'), '-'
    UNION ALL
    SELECT 7, 'donos_sem_participacao',
           NOT EXISTS (SELECT 1 FROM pg_roles g WHERE g.rolname = 'nekkora_identity_owner' AND (g.rolsuper OR g.rolcreaterole OR g.rolcreatedb
                       OR g.rolreplication OR g.rolbypassrls OR g.rolcanlogin))
           AND NOT EXISTS (SELECT 1 FROM pg_auth_members am JOIN pg_roles g ON g.oid IN (am.roleid, am.member) WHERE g.rolname = 'nekkora_identity_owner')
           AND NOT EXISTS (SELECT 1 FROM pg_roles g WHERE g.rolname = 'nekkora_ops_mfa_owner' AND (g.rolsuper OR g.rolcreaterole OR g.rolcreatedb
                       OR g.rolreplication OR g.rolbypassrls OR g.rolcanlogin))
           AND NOT EXISTS (SELECT 1 FROM pg_auth_members am JOIN pg_roles g ON g.oid IN (am.roleid, am.member) WHERE g.rolname = 'nekkora_ops_mfa_owner'), '-'
)
SELECT linha FROM (
    SELECT ordem, item || '|' || CASE WHEN coalesce(ok, false) THEN 'OK' ELSE 'FALHA' END || '|' || coalesce(detalhe, '-') AS linha FROM c
    UNION ALL
    SELECT 99, CASE WHEN bool_and(coalesce(ok, false)) AND count(*) = 7 THEN 'PREFLIGHT_OK' ELSE 'PREFLIGHT_FALHOU' END FROM c
) x ORDER BY ordem;
