-- Pre-voo da janela de elevacao da 027 (lote do expurgo das categorias 1 e 2, D-RET-2). PROPOSTA: so e executado depois que
-- a responsavel autorizar a linha da 027 (lista revisada ou de laboratorio). SOMENTE LEITURA; executado por
-- implantacao/implantar.sh como admin_cluster no banco nekkora, com a trava da implantacao obtida, depois do sha256 e do
-- 0|0|0 e ANTES de conceder SUPERUSER. Mesmo formato dos pre-voos 016-026: "item|OK|detalhe" ou "item|FALHA|detalhe" por
-- conferencia e, por ultimo, PREFLIGHT_OK somente se TODAS passarem (senao PREFLIGHT_FALHOU).
-- Especificas: a sec. 1 da 027 (026 aplicada; funcao na forma da 026, com um unico lote materializado); dono, SECURITY
-- DEFINER e EXECUTE de purge_expired_records como na 011/012 (nenhuma concessao).
WITH c(ordem, item, ok, detalhe) AS (
    SELECT 1, 'servidor_versao_maior_16',
           current_setting('server_version_num')::int BETWEEN 160000 AND 169999, current_setting('server_version')
    UNION ALL
    SELECT 2, 'historico_001_026_com_sucesso_sem_027_nem_posteriores_sem_falha',
           (SELECT count(DISTINCT version) FROM public.flyway_schema_history WHERE success AND version ~ '^0[0-9][0-9]$' AND version <= '026') = 26
           AND NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version > '026')
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
    SELECT 5, 'funcao_na_forma_da_026',
           EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = to_regclass('public.agent_run_events') AND attname = 'purged_at' AND NOT attisdropped)
           AND (SELECT count(*) FROM regexp_matches(pg_get_functiondef('public.purge_expired_records(uuid,integer,uuid,integer)'::regprocedure),
                                                    'AS MATERIALIZED', 'g')) = 1,
           '-'
    UNION ALL
    SELECT 6, 'purge_expired_records_dono_secdef_sem_execute',
           (SELECT pg_get_userbyid(proowner) = 'nekkora_retention_owner' AND prosecdef FROM pg_proc
             WHERE oid = 'public.purge_expired_records(uuid,integer,uuid,integer)'::regprocedure)
           AND NOT EXISTS (SELECT 1 FROM pg_roles r WHERE NOT r.rolsuper AND r.rolname <> 'nekkora_retention_owner'
                           AND has_function_privilege(r.oid, 'public.purge_expired_records(uuid,integer,uuid,integer)', 'EXECUTE')),
           '-'
    UNION ALL
    SELECT 7, 'dono_das_funcoes_sem_login_nem_participacao',
           (SELECT count(*) FROM pg_roles WHERE rolname = 'nekkora_retention_owner' AND NOT rolcanlogin AND NOT rolsuper) = 1
           AND NOT EXISTS (SELECT 1 FROM pg_auth_members am JOIN pg_roles g ON g.oid IN (am.roleid, am.member) WHERE g.rolname = 'nekkora_retention_owner'),
           '-'
)
SELECT linha FROM (
    SELECT ordem, item || '|' || CASE WHEN coalesce(ok, false) THEN 'OK' ELSE 'FALHA' END || '|' || coalesce(detalhe, '-') AS linha FROM c
    UNION ALL
    SELECT 99, CASE WHEN bool_and(coalesce(ok, false)) AND count(*) = 7 THEN 'PREFLIGHT_OK' ELSE 'PREFLIGHT_FALHOU' END FROM c
) x ORDER BY ordem;
