-- Pre-voo da janela de elevacao da 023 (missao de consolidacao e acessos, 2026-10-27). PROPOSTA: so e executado depois
-- que a responsavel autorizar a linha da 023 em implantacao/elevacao.txt (ou na lista de um laboratorio) e o "atualizar"
-- passar a aceitar 022 -> 023. SOMENTE LEITURA; executado por implantacao/implantar.sh como admin_cluster no banco nekkora,
-- com a trava da implantacao obtida, depois do sha256 e do 0|0|0 e ANTES de conceder SUPERUSER. Mesmo formato dos
-- pre-voos 016-022: uma linha "item|OK|detalhe" ou "item|FALHA|detalhe" por conferencia e, por ultimo, PREFLIGHT_OK somente
-- se TODAS passarem (senao PREFLIGHT_FALHOU).
-- Comuns: PostgreSQL 16; historico exatamente 001-022 com sucesso, sem a 023 nem posteriores e sem falha; migrador sem
-- atributo elevado nem ACL de parametro; migrador dono do banco.
-- Especificas: as condicoes da sec. 1 da 023 (022 aplicada; objetos dos convites ausentes; nenhum par de convites abertos
-- para o mesmo e-mail na mesma organizacao, que a 023 recusaria); o dono das funcoes de identidade
-- (nekkora_identity_owner) sem login e sem participacao (a 023 lhe transfere o aceite); a restricao de aprovacao da 003 que
-- a 023 troca e a de canal de identity_users que ela confere, com a definicao esperada.
WITH c(ordem, item, ok, detalhe) AS (
    SELECT 1, 'servidor_versao_maior_16',
           current_setting('server_version_num')::int BETWEEN 160000 AND 169999, current_setting('server_version')
    UNION ALL
    SELECT 2, 'historico_001_022_com_sucesso_sem_023_nem_posteriores_sem_falha',
           (SELECT count(DISTINCT version) FROM public.flyway_schema_history WHERE success AND version ~ '^0[0-9][0-9]$' AND version <= '022') = 22
           AND NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version > '022')
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
    SELECT 5, 'marca_da_022_presente', to_regclass('ops_activation.organization_states') IS NOT NULL, '-'
    UNION ALL
    SELECT 6, 'objetos_dos_convites_ausentes',
           to_regclass('public.access_events') IS NULL
           AND to_regprocedure('public.identity_accept_invitation(text,text,uuid)') IS NULL
           AND NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                           WHERE n.nspname = 'public' AND p.proname LIKE 'access\_%'),
           '-'
    UNION ALL
    SELECT 7, 'sem_convites_abertos_duplicados',
           NOT EXISTS (SELECT 1 FROM public.identity_invitations WHERE accepted_at IS NULL AND revoked_at IS NULL
                       GROUP BY organization_id, email_normalized HAVING count(*) > 1),
           (SELECT count(*) || ' convites abertos' FROM public.identity_invitations WHERE accepted_at IS NULL AND revoked_at IS NULL)
    UNION ALL
    SELECT 8, 'dono_das_funcoes_de_identidade_sem_login_nem_participacao',
           EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_identity_owner' AND NOT rolcanlogin AND NOT rolsuper)
           AND NOT EXISTS (SELECT 1 FROM pg_auth_members am JOIN pg_roles g ON g.oid IN (am.roleid, am.member) WHERE g.rolname = 'nekkora_identity_owner'),
           '-'
    UNION ALL
    SELECT 9, 'restricoes_que_a_023_confere_com_a_definicao_esperada',
           (SELECT count(*) FROM pg_constraint WHERE conrelid = 'public.client_workspace_grants'::regclass AND contype = 'c'
              AND pg_get_constraintdef(oid) = 'CHECK (((status = 1) OR (approved_at IS NOT NULL)))') = 1
           AND (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'identity_users_link_channel_check'
                  AND conrelid = 'public.identity_users'::regclass)
               = 'CHECK ((link_channel = ANY (ARRAY[''cadastro_web''::text, ''vinculacao''::text])))',
           '-'
)
SELECT linha FROM (
    SELECT ordem, item || '|' || CASE WHEN coalesce(ok, false) THEN 'OK' ELSE 'FALHA' END || '|' || coalesce(detalhe, '-') AS linha FROM c
    UNION ALL
    SELECT 99, CASE WHEN bool_and(coalesce(ok, false)) AND count(*) = 9 THEN 'PREFLIGHT_OK' ELSE 'PREFLIGHT_FALHOU' END FROM c
) x ORDER BY ordem;
