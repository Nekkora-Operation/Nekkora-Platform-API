-- Pre-voo da janela de elevacao da 022 (missao PLATFORM-API-V1-003, revisao). SOMENTE LEITURA; executado por
-- implantacao/implantar.sh como admin_cluster no banco nekkora, com a trava da implantacao obtida, depois do sha256 e do
-- 0|0|0 e ANTES de conceder SUPERUSER. Mesmo formato dos pre-voos 016-020: uma linha "item|OK|detalhe" ou
-- "item|FALHA|detalhe" por conferencia e, por ultimo, PREFLIGHT_OK somente se TODAS passarem (senao PREFLIGHT_FALHOU).
-- Comuns: PostgreSQL 16; historico exatamente 001-021 com sucesso, sem a 022 nem posteriores e sem falha; migrador sem
-- atributo elevado nem ACL de parametro; migrador dono do banco.
-- Especificas: as condicoes da sec. 1 da 022 (021 aplicada; schema, funcoes e grupo da ativacao ausentes ou o grupo sem
-- atributo); o dono das funcoes (nekkora_ops_mfa_owner) sem participacao (a 022 lhe da funcoes e o EXECUTE de
-- provision_resource_limit); provision_resource_limit e get_effective_resource_limit com a assinatura esperada; a identidade
-- de backup declarada, se existir, so com pg_read_all_data (a invariante de acesso efetivo da 022 recusaria outra heranca).
WITH c(ordem, item, ok, detalhe) AS (
    SELECT 1, 'servidor_versao_maior_16',
           current_setting('server_version_num')::int BETWEEN 160000 AND 169999, current_setting('server_version')
    UNION ALL
    SELECT 2, 'historico_001_021_com_sucesso_sem_022_nem_posteriores_sem_falha',
           (SELECT count(DISTINCT version) FROM public.flyway_schema_history WHERE success AND version ~ '^0[0-9][0-9]$' AND version <= '021') = 21
           AND NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version > '021')
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
    SELECT 5, 'marca_da_021_presente', to_regclass('public.mail_outbox') IS NOT NULL, '-'
    UNION ALL
    SELECT 6, 'objetos_da_ativacao_ausentes',
           NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'ops_activation')
           AND NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                           WHERE n.nspname = 'public' AND p.proname LIKE 'org\_activation\_%'),
           '-'
    UNION ALL
    SELECT 7, 'grupo_de_ativacao_ausente_ou_sem_atributo_nem_participacao',
           NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_org_activation'
                        AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls OR rolcanlogin))
           AND NOT EXISTS (SELECT 1 FROM pg_auth_members am JOIN pg_roles g ON g.oid IN (am.roleid, am.member) WHERE g.rolname = 'nekkora_org_activation'),
           coalesce((SELECT 'existe' FROM pg_roles WHERE rolname = 'nekkora_org_activation'), 'ausente')
    UNION ALL
    SELECT 8, 'dono_das_funcoes_operacionais_sem_participacao',
           EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_ops_mfa_owner' AND NOT rolcanlogin AND NOT rolsuper)
           AND NOT EXISTS (SELECT 1 FROM pg_auth_members am JOIN pg_roles g ON g.oid IN (am.roleid, am.member) WHERE g.rolname = 'nekkora_ops_mfa_owner'),
           '-'
    UNION ALL
    SELECT 9, 'funcoes_de_limite_com_assinatura_esperada',
           to_regprocedure('public.provision_resource_limit(uuid, integer, smallint, bigint, uuid)') IS NOT NULL
           AND to_regprocedure('public.get_effective_resource_limit(uuid, integer)') IS NOT NULL
           AND (SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE oid = to_regprocedure('public.provision_resource_limit(uuid, integer, smallint, bigint, uuid)')) = 'nekkora_provisioning_owner',
           '-'
    UNION ALL
    SELECT 10, 'identidade_de_backup_so_com_pg_read_all_data',
           NOT EXISTS (SELECT 1 FROM pg_auth_members am JOIN pg_roles g ON g.oid = am.roleid JOIN pg_roles m ON m.oid = am.member
                       WHERE m.rolname = 'backup_nekkora' AND g.rolname <> 'pg_read_all_data'),
           coalesce((SELECT string_agg(g.rolname, ',' ORDER BY g.rolname) FROM pg_auth_members am JOIN pg_roles g ON g.oid = am.roleid
                     JOIN pg_roles m ON m.oid = am.member WHERE m.rolname = 'backup_nekkora'), 'sem participacao')
)
SELECT linha FROM (
    SELECT ordem, item || '|' || CASE WHEN coalesce(ok, false) THEN 'OK' ELSE 'FALHA' END || '|' || coalesce(detalhe, '-') AS linha FROM c
    UNION ALL
    SELECT 99, CASE WHEN bool_and(coalesce(ok, false)) AND count(*) = 10 THEN 'PREFLIGHT_OK' ELSE 'PREFLIGHT_FALHOU' END FROM c
) x ORDER BY ordem;
