-- Pre-voo da janela de elevacao da 026 (retencao e consumo diario dos eventos do Agent, missao Platform-Agent de 2026-10-28).
-- PROPOSTA: so e executado depois que a responsavel autorizar a linha da 026 em implantacao/elevacao.txt (ou na lista de um
-- laboratorio). SOMENTE LEITURA; executado por implantacao/implantar.sh como admin_cluster no banco nekkora, com a trava da
-- implantacao obtida, depois do sha256 e do 0|0|0 e ANTES de conceder SUPERUSER. Mesmo formato dos pre-voos 016-024: uma
-- linha "item|OK|detalhe" ou "item|FALHA|detalhe" por conferencia e, por ultimo, PREFLIGHT_OK somente se TODAS passarem
-- (senao PREFLIGHT_FALHOU).
-- Comuns: PostgreSQL 16; historico exatamente 001-025 com sucesso, sem a 026 nem posteriores e sem falha; migrador sem
-- atributo elevado nem ACL de parametro; migrador dono do banco.
-- Especificas: a sec. 1 da 026 (025 aplicada; objetos da 026 ausentes); as tres restricoes de catalogo que a 026 troca, com a
-- definicao da 010/011 (instalada ou restaurada de dump); o gatilho prevent_update de agent_run_events que ela troca; os donos
-- das tabelas e das funcoes de manutencao que ela altera (nekkora_retention_tables_owner e nekkora_retention_owner), sem login e
-- sem participacao; quantos eventos do Agent ja existem (informativo: sempre OK; a 026 nao muda nenhum dado).
WITH c(ordem, item, ok, detalhe) AS (
    SELECT 1, 'servidor_versao_maior_16',
           current_setting('server_version_num')::int BETWEEN 160000 AND 169999, current_setting('server_version')
    UNION ALL
    SELECT 2, 'historico_001_025_com_sucesso_sem_026_nem_posteriores_sem_falha',
           (SELECT count(DISTINCT version) FROM public.flyway_schema_history WHERE success AND version ~ '^0[0-9][0-9]$' AND version <= '025') = 25
           AND NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version > '025')
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
    SELECT 5, 'marca_da_025_presente',
           to_regclass('public.agent_run_events') IS NOT NULL AND to_regprocedure('public.agent_run_on_insert()') IS NOT NULL,
           '-'
    UNION ALL
    SELECT 6, 'objetos_da_026_ausentes',
           NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = to_regclass('public.agent_run_events') AND attname = 'purged_at' AND NOT attisdropped)
           AND to_regprocedure('public.agent_run_event_received_at()') IS NULL,
           '-'
    UNION ALL
    SELECT 7, 'restricoes_trocadas_com_a_definicao_da_010_011',
           (SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass('public.organization_usage_daily')
              AND conname = 'organization_usage_daily_resource_code_check' AND pg_get_constraintdef(oid) = 'CHECK ((resource_code = ANY (ARRAY[4, 5])))') = 1
           AND (SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass('public.organization_retention_policy_revisions')
              AND conname = 'organization_retention_policy_revisions_category_check' AND pg_get_constraintdef(oid) = 'CHECK ((category = ANY (ARRAY[1, 2])))') = 1
           AND (SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass('public.organization_purge_runs')
              AND conname = 'organization_purge_runs_category_check' AND pg_get_constraintdef(oid) = 'CHECK ((category = ANY (ARRAY[1, 2])))') = 1,
           '-'
    UNION ALL
    SELECT 8, 'gatilho_prevent_update_de_agent_run_events_presente',
           EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = to_regclass('public.agent_run_events') AND tgname = 'trg_agent_run_events_prevent_update'
                   AND NOT tgisinternal AND tgenabled = 'O'),
           '-'
    UNION ALL
    SELECT 9, 'donos_das_tabelas_e_funcoes_de_manutencao_sem_login_nem_participacao',
           (SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = to_regclass('public.organization_usage_daily')) = 'nekkora_retention_tables_owner'
           AND (SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = to_regclass('public.organization_purge_runs')) = 'nekkora_retention_tables_owner'
           AND (SELECT count(*) FROM pg_proc WHERE oid IN (to_regprocedure('public.close_usage_day(uuid,integer,date)'),
                    to_regprocedure('public.reconsolidate_usage_day(uuid,integer,date)'), to_regprocedure('public.purge_expired_records(uuid,integer,uuid,integer)'))
                  AND pg_get_userbyid(proowner) = 'nekkora_retention_owner' AND prosecdef) = 3
           AND (SELECT count(*) FROM pg_roles WHERE rolname IN ('nekkora_retention_owner', 'nekkora_retention_tables_owner') AND NOT rolcanlogin AND NOT rolsuper) = 2
           AND NOT EXISTS (SELECT 1 FROM pg_auth_members am JOIN pg_roles g ON g.oid IN (am.roleid, am.member)
                           WHERE g.rolname IN ('nekkora_retention_owner', 'nekkora_retention_tables_owner')),
           '-'
    UNION ALL
    SELECT 10, 'eventos_do_agent_existentes',
           true,
           CASE WHEN to_regclass('public.agent_run_events') IS NULL THEN 'sem a 025'
                ELSE (xpath('/row/n/text()', query_to_xml('SELECT count(*) AS n FROM public.agent_run_events', false, true, '')))[1]::text
                     || ' evento(s) do Agent (nenhum muda na aplicacao; nenhum nasce expurgado)' END
)
SELECT linha FROM (
    SELECT ordem, item || '|' || CASE WHEN coalesce(ok, false) THEN 'OK' ELSE 'FALHA' END || '|' || coalesce(detalhe, '-') AS linha FROM c
    UNION ALL
    SELECT 99, CASE WHEN bool_and(coalesce(ok, false)) AND count(*) = 10 THEN 'PREFLIGHT_OK' ELSE 'PREFLIGHT_FALHOU' END FROM c
) x ORDER BY ordem;
