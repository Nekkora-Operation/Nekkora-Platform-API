-- Pre-voo da janela de elevacao da 029 (segundo fator opcional, PLATFORM-LOCAL-002). PROPOSTA: so e executado depois que a
-- responsavel autorizar a linha da 029 (lista revisada ou de laboratorio). SOMENTE LEITURA; executado por
-- implantacao/implantar.sh como admin_cluster no banco nekkora, com a trava da implantacao obtida, depois do sha256 e do
-- 0|0|0 e ANTES de conceder SUPERUSER. Mesmo formato dos pre-voos 016-027: "item|OK|detalhe" ou "item|FALHA|detalhe" por
-- conferencia e, por ultimo, PREFLIGHT_OK somente se TODAS passarem (senao PREFLIGHT_FALHOU).
-- Comuns: PostgreSQL 16; historico exatamente 001-028 com sucesso, sem a 029 nem posteriores e sem falha; migrador sem
-- atributo elevado nem ACL de parametro; migrador dono do banco.
-- Especificas: as condicoes da sec. 1 da 029 (028 aplicada; funcoes novas da 029 ausentes; CHECK do evento na forma da
-- 020); o dono que recebe e mantem funcoes (nekkora_identity_owner) sem login e sem participacao; as seis funcoes que a 029
-- redefine com o dono esperado (CREATE OR REPLACE preserva o dono) e as quatro funcoes de gatilho redefinidas, do migrador.
WITH c(ordem, item, ok, detalhe) AS (
    SELECT 1, 'servidor_versao_maior_16',
           current_setting('server_version_num')::int BETWEEN 160000 AND 169999, current_setting('server_version')
    UNION ALL
    SELECT 2, 'historico_001_028_com_sucesso_sem_029_nem_posteriores_sem_falha',
           (SELECT count(DISTINCT version) FROM public.flyway_schema_history WHERE success AND version ~ '^0[0-9][0-9]$' AND version <= '028') = 28
           AND NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version > '028')
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
    SELECT 5, 'precondicao_da_029_028_aplicada_funcoes_novas_ausentes_check_da_020',
           to_regclass('public.automation_artifacts') IS NOT NULL
           AND to_regprocedure('public.identity_password_reset(text, text)') IS NOT NULL
           AND to_regprocedure('public.identity_login_password(text, text, text, timestamp with time zone)') IS NULL
           AND to_regprocedure('public.identity_password_reconfirm(text, text, uuid, uuid)') IS NULL
           AND to_regprocedure('public.identity_never_had_factor(uuid)') IS NULL
           AND NOT EXISTS (SELECT 1 FROM pg_proc WHERE pronamespace = 'public'::regnamespace AND proname = 'identity_mfa_activate')
           AND (SELECT pg_get_constraintdef(k.oid) FROM pg_constraint k
                 WHERE k.conrelid = 'public.identity_events'::regclass AND k.conname = 'identity_events_event_type_check')
               = 'CHECK (((event_type >= 1) AND (event_type <= 17)))',
           '-'
    UNION ALL
    SELECT 6, 'dono_das_funcoes_sem_login_nem_participacao',
           (SELECT count(*) FROM pg_roles WHERE rolname = 'nekkora_identity_owner' AND NOT rolcanlogin AND NOT rolsuper) = 1
           AND NOT EXISTS (SELECT 1 FROM pg_auth_members am JOIN pg_roles g ON g.oid IN (am.roleid, am.member) WHERE g.rolname = 'nekkora_identity_owner'),
           '-'
    UNION ALL
    SELECT 7, 'funcoes_redefinidas_com_o_dono_esperado',
           (SELECT count(*) FROM pg_proc WHERE pronamespace = 'public'::regnamespace
             AND proname IN ('identity_session_resolve', 'identity_session_lookup', 'identity_session_proof',
                             'identity_org_creation_allowed', 'identity_org_session_allowed', 'identity_mfa_enroll_pending')
             AND pg_get_userbyid(proowner) = 'nekkora_identity_owner') = 6
           AND (SELECT count(*) FROM pg_proc WHERE pronamespace = 'public'::regnamespace
             AND proname IN ('enforce_identity_session_insert', 'prevent_identity_session_change',
                             'enforce_user_session_mfa_on_insert', 'prevent_user_session_reactivation')
             AND pg_get_userbyid(proowner) = 'migrador_nekkora' AND NOT prosecdef) = 4,
           (SELECT string_agg(proname || ':' || pg_get_userbyid(proowner), ' ' ORDER BY proname) FROM pg_proc
             WHERE pronamespace = 'public'::regnamespace
               AND proname IN ('identity_session_resolve', 'identity_session_lookup', 'identity_session_proof',
                               'identity_org_creation_allowed', 'identity_org_session_allowed', 'identity_mfa_enroll_pending',
                               'enforce_identity_session_insert', 'prevent_identity_session_change',
                               'enforce_user_session_mfa_on_insert', 'prevent_user_session_reactivation'))
)
SELECT linha FROM (
    SELECT ordem, item || '|' || CASE WHEN coalesce(ok, false) THEN 'OK' ELSE 'FALHA' END || '|' || coalesce(detalhe, '-') AS linha FROM c
    UNION ALL
    SELECT 99, CASE WHEN bool_and(coalesce(ok, false)) AND count(*) = 7 THEN 'PREFLIGHT_OK' ELSE 'PREFLIGHT_FALHOU' END FROM c
) x ORDER BY ordem;
