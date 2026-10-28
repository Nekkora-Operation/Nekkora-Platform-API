-- Pre-voo da janela de elevacao da 024 (missao de consolidacao e acessos, 2026-10-27). PROPOSTA: so e executado depois
-- que a responsavel autorizar a linha da 024 em implantacao/elevacao.txt (ou na lista de um laboratorio) e o "atualizar"
-- passar a aceitar 023 -> 024. SOMENTE LEITURA; executado por implantacao/implantar.sh como admin_cluster no banco nekkora,
-- com a trava da implantacao obtida, depois do sha256 e do 0|0|0 e ANTES de conceder SUPERUSER. Mesmo formato dos
-- pre-voos 016-023: uma linha "item|OK|detalhe" ou "item|FALHA|detalhe" por conferencia e, por ultimo, PREFLIGHT_OK somente
-- se TODAS passarem (senao PREFLIGHT_FALHOU).
-- Comuns: PostgreSQL 16; historico exatamente 001-023 com sucesso, sem a 024 nem posteriores e sem falha; migrador sem
-- atributo elevado nem ACL de parametro; migrador dono do banco.
-- Especificas: as condicoes da sec. 1 da 024 (023 aplicada; objetos da 024 ausentes); as quatro restricoes que a 024 troca,
-- com a definicao da 023, instalada ou restaurada de dump (a 024 recusaria outra); os donos que recebem funcoes (nekkora_identity_owner e
-- nekkora_ops_mfa_owner) sem login e sem participacao; as funcoes da 016/018 que a 024 substitui com o dono esperado;
-- quantos convites abertos a 024 fechara na aplicacao (informativo: sempre OK; e a unica mudanca de dados).
WITH c(ordem, item, ok, detalhe) AS (
    SELECT 1, 'servidor_versao_maior_16',
           current_setting('server_version_num')::int BETWEEN 160000 AND 169999, current_setting('server_version')
    UNION ALL
    SELECT 2, 'historico_001_023_com_sucesso_sem_024_nem_posteriores_sem_falha',
           (SELECT count(DISTINCT version) FROM public.flyway_schema_history WHERE success AND version ~ '^0[0-9][0-9]$' AND version <= '023') = 23
           AND NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version > '023')
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
    SELECT 5, 'marca_da_023_presente',
           to_regclass('public.access_events') IS NOT NULL AND to_regprocedure('public.identity_accept_invitation(text,text,uuid)') IS NOT NULL
           AND to_regprocedure('public.access_can_invite(uuid,uuid,uuid,uuid)') IS NOT NULL,
           '-'
    UNION ALL
    SELECT 6, 'objetos_da_024_ausentes',
           to_regprocedure('public.identity_user_is_linked(uuid)') IS NULL
           AND to_regprocedure('public.identity_invitations_close_unauthorized(uuid,uuid[])') IS NULL
           AND NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = to_regclass('public.client_workspace_grants')
                           AND attname IN ('requested_by_membership_id', 'renewal_requested_by_membership_id') AND NOT attisdropped)
           AND NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = to_regclass('public.client_workspace_grant_approvals')
                           AND attname = 'requested_by_membership_id' AND NOT attisdropped),
           '-'
    UNION ALL
    SELECT 7, 'restricoes_trocadas_com_a_definicao_da_023',
           (SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass('public.identity_invitations') AND contype = 'c'
              AND pg_get_constraintdef(oid) = 'CHECK ((revoke_reason = ANY (ARRAY[1, 2, 3])))') = 1
           AND (SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass('public.access_events') AND contype = 'c'
                  AND pg_get_constraintdef(oid) IN ('CHECK (((event_type >= 1) AND (event_type <= 13)))',
                                                    'CHECK ((((event_type < 9) OR (event_type > 13)) OR (grant_id IS NOT NULL)))',
                                                    'CHECK (((event_type < 9) OR (event_type > 13) OR (grant_id IS NOT NULL)))')) = 2
           AND (SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass('public.access_events') AND contype = 'c'
                  AND pg_get_constraintdef(oid) IN ('CHECK ((((event_type < 1) OR (event_type > 4)) OR (invitation_id IS NOT NULL)))',
                                                    'CHECK (((event_type < 1) OR (event_type > 4) OR (invitation_id IS NOT NULL)))')) = 1
           AND (SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass('public.access_events') AND contype = 'c'
                  AND pg_get_constraintdef(oid) LIKE '%13%') = 2,
           '-'
    UNION ALL
    SELECT 8, 'donos_que_recebem_funcoes_sem_login_nem_participacao',
           (SELECT count(*) FROM pg_roles WHERE rolname IN ('nekkora_identity_owner', 'nekkora_ops_mfa_owner') AND NOT rolcanlogin AND NOT rolsuper) = 2
           AND NOT EXISTS (SELECT 1 FROM pg_auth_members am JOIN pg_roles g ON g.oid IN (am.roleid, am.member)
                           WHERE g.rolname IN ('nekkora_identity_owner', 'nekkora_ops_mfa_owner')),
           '-'
    UNION ALL
    SELECT 9, 'funcoes_substituidas_da_016_018_com_o_dono_esperado',
           (SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE oid = to_regprocedure('ops.alvo_inelegivel(uuid,uuid)')) = 'nekkora_ops_mfa_owner'
           AND (SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE oid = to_regprocedure('public.ops_mfa_execute(uuid)')) = 'nekkora_ops_mfa_owner',
           '-'
    UNION ALL
    SELECT 10, 'convites_abertos_que_a_024_fechara',
           true,
           -- a consulta vai como texto (query_to_xml) para nao ser resolvida sem a 023: sem ela, o item 5 ja falha
           CASE WHEN to_regprocedure('public.access_can_invite(uuid,uuid,uuid,uuid)') IS NULL THEN 'sem a 023'
                ELSE (xpath('/row/n/text()', query_to_xml('SELECT count(*) AS n FROM public.identity_invitations i
                       WHERE i.accepted_at IS NULL AND i.revoked_at IS NULL AND i.issued_by_membership_id IS NOT NULL
                         AND NOT public.access_can_invite(i.organization_id, i.issued_by_membership_id, i.role_id, i.workspace_id)',
                       false, true, '')))[1]::text
                     || ' convite(s) aberto(s) de emissor sem autoridade (fechados com o motivo 4 na aplicacao)' END
)
SELECT linha FROM (
    SELECT ordem, item || '|' || CASE WHEN coalesce(ok, false) THEN 'OK' ELSE 'FALHA' END || '|' || coalesce(detalhe, '-') AS linha FROM c
    UNION ALL
    SELECT 99, CASE WHEN bool_and(coalesce(ok, false)) AND count(*) = 10 THEN 'PREFLIGHT_OK' ELSE 'PREFLIGHT_FALHOU' END FROM c
) x ORDER BY ordem;
