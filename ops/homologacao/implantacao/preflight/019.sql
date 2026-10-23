-- Pre-voo da janela de elevacao da 019 (missao PLATFORM-API-V1-003). SOMENTE LEITURA; executado por implantacao/implantar.sh
-- como admin_cluster no banco nekkora, com a trava da implantacao obtida, depois do sha256 e do 0|0|0 e ANTES de conceder
-- SUPERUSER. Mesmo formato do pre-voo da 016: uma linha "item|OK|detalhe" ou "item|FALHA|detalhe" por conferencia e, por
-- ultimo, PREFLIGHT_OK somente se TODAS passarem (senao PREFLIGHT_FALHOU). Erro de consulta = sem PREFLIGHT_OK = recusa.
-- Comuns: PostgreSQL 16; historico exatamente 001-<anterior> com sucesso, sem a 019 nem posteriores e sem falha; migrador
-- sem atributo elevado nem ACL de parametro; migrador dono do banco (modelo suportado, 016 rev. 5, sec. 19).
-- Especificas: as condicoes da sec. 1 da 019 (marca da 018: SELECT (created_at) do dono operacional; funcao nova ausente;
-- CHECK do evento igual ao da 017) e o dono de identidade sem participacao.
WITH c(ordem, item, ok, detalhe) AS (
    SELECT 1, 'servidor_versao_maior_16',
           current_setting('server_version_num')::int BETWEEN 160000 AND 169999, current_setting('server_version')
    UNION ALL
    SELECT 2, 'historico_001_018_com_sucesso_sem_019_nem_posteriores_sem_falha',
           (SELECT count(DISTINCT version) FROM public.flyway_schema_history WHERE success AND version ~ '^0[0-9][0-9]$' AND version <= '018') = 18
           AND NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version > '018')
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
    SELECT 5, 'marca_da_018_presente',
           to_regprocedure('public.identity_mfa_replace(text, uuid, text[])') IS NOT NULL
           AND has_column_privilege('nekkora_ops_mfa_owner', 'public.user_mfa_factors', 'created_at', 'SELECT'), '-'
    UNION ALL
    SELECT 6, 'identity_recovery_codes_regenerate_ausente',
           to_regprocedure('public.identity_recovery_codes_regenerate(text, text[])') IS NULL, '-'
    UNION ALL
    SELECT 7, 'check_do_evento_igual_ao_da_017',
           (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c WHERE c.conrelid = 'public.identity_events'::regclass
             AND c.conname = 'identity_events_event_type_check') = 'CHECK (((event_type >= 1) AND (event_type <= 14)))',
           coalesce((SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c WHERE c.conrelid = 'public.identity_events'::regclass
             AND c.conname = 'identity_events_event_type_check'), 'ausente')
    UNION ALL
    SELECT 8, 'dono_de_identidade_sem_participacao', NOT EXISTS (SELECT 1 FROM pg_roles g WHERE g.rolname = 'nekkora_identity_owner' AND (g.rolsuper OR g.rolcreaterole OR g.rolcreatedb
                       OR g.rolreplication OR g.rolbypassrls OR g.rolcanlogin))
           AND NOT EXISTS (SELECT 1 FROM pg_auth_members am JOIN pg_roles g ON g.oid IN (am.roleid, am.member) WHERE g.rolname = 'nekkora_identity_owner'), '-'
)
SELECT linha FROM (
    SELECT ordem, item || '|' || CASE WHEN coalesce(ok, false) THEN 'OK' ELSE 'FALHA' END || '|' || coalesce(detalhe, '-') AS linha FROM c
    UNION ALL
    SELECT 99, CASE WHEN bool_and(coalesce(ok, false)) AND count(*) = 8 THEN 'PREFLIGHT_OK' ELSE 'PREFLIGHT_FALHOU' END FROM c
) x ORDER BY ordem;
