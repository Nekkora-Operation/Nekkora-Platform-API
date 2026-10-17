-- === 15. Invariantes verificados ao final da migration ===

DO $$
DECLARE
    v_bad TEXT;
BEGIN
    -- nenhum papel nekkora_* (exceto o de migration) e super, bypassrls, createrole ou createdb
    SELECT string_agg(rolname, ', ') INTO v_bad FROM pg_roles
    WHERE rolname LIKE 'nekkora\_%' AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel de servico com atributo privilegiado: %', v_bad; END IF;

    -- nenhum papel LOGIN de servico e dono de tabela ou funcao
    SELECT string_agg(DISTINCT pg_get_userbyid(o.owner), ', ') INTO v_bad FROM (
        SELECT relowner AS owner FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
        UNION ALL
        SELECT proowner FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public'
    ) o JOIN pg_roles r ON r.oid = o.owner
    WHERE r.rolname LIKE 'nekkora\_%' AND r.rolcanlogin;
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel LOGIN dono de objeto: %', v_bad; END IF;

    -- nenhuma membership envolvendo um papel protegido (servico ou proprietario), em QUALQUER
    -- sentido e com QUALQUER outro papel: concedido a alguem ou recebendo de alguem. Uma membership
    -- somaria politicas permissivas (OR) de grupos distintos ou daria privilegios de proprietario.
    SELECT string_agg(format('%s -> %s', r.rolname, m.rolname), ', ') INTO v_bad
    FROM pg_auth_members am
    JOIN pg_roles r ON r.oid = am.roleid
    JOIN pg_roles m ON m.oid = am.member
    WHERE r.rolname IN ('nekkora_platform', 'nekkora_ingestion', 'nekkora_scheduler', 'nekkora_provisioning',
                        'nekkora_retention_executor', 'nekkora_scheduler_owner', 'nekkora_provisioning_owner',
                        'nekkora_retention_owner', 'nekkora_retention_tables_owner')
       OR m.rolname IN ('nekkora_platform', 'nekkora_ingestion', 'nekkora_scheduler', 'nekkora_provisioning',
                        'nekkora_retention_executor', 'nekkora_scheduler_owner', 'nekkora_provisioning_owner',
                        'nekkora_retention_owner', 'nekkora_retention_tables_owner');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'membership envolvendo papel protegido (concedido -> membro): %', v_bad; END IF;

    -- nenhum papel protegido com CREATE EFETIVO (direto, herdado ou via PUBLIC) no schema public ou
    -- no banco: CREATE em public reabriria o sombreamento de objetos nas funcoes com public no
    -- search_path; CREATE no banco permitiria criar schemas.
    SELECT string_agg(p.papel, ', ') INTO v_bad
    FROM unnest(ARRAY['nekkora_platform', 'nekkora_ingestion', 'nekkora_scheduler', 'nekkora_provisioning',
                      'nekkora_retention_executor', 'nekkora_scheduler_owner', 'nekkora_provisioning_owner',
                      'nekkora_retention_owner', 'nekkora_retention_tables_owner']) AS p(papel)
    WHERE has_schema_privilege(p.papel, 'public', 'CREATE') OR has_database_privilege(p.papel, current_database(), 'CREATE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel protegido com CREATE efetivo em public ou no banco: %', v_bad; END IF;

    -- toda funcao SECURITY DEFINER tem search_path fixo e nenhuma concede EXECUTE a PUBLIC
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.prosecdef
      AND (p.proconfig IS NULL OR NOT EXISTS (SELECT 1 FROM unnest(p.proconfig) c WHERE c LIKE 'search_path=%')
           OR has_function_privilege('public', p.oid, 'EXECUTE'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'SECURITY DEFINER sem search_path fixo ou com EXECUTE publico: %', v_bad; END IF;

    -- CREATE em public continua fora do alcance de PUBLIC
    IF has_schema_privilege('public', 'public', 'CREATE') THEN
        RAISE EXCEPTION 'CREATE em public concedido a PUBLIC';
    END IF;

    -- papel substituido removido
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_app') THEN
        RAISE EXCEPTION 'nekkora_app ainda existe';
    END IF;
END;
$$;

-- === 8. Invariantes verificados ao final (padrao da secao 15 da 012, estendido aos papeis novos) ===

DO $$
DECLARE
    v_bad TEXT;
    v_protegidos CONSTANT TEXT[] := ARRAY['nekkora_platform', 'nekkora_ingestion', 'nekkora_scheduler', 'nekkora_provisioning',
        'nekkora_retention_executor', 'nekkora_scheduler_owner', 'nekkora_provisioning_owner', 'nekkora_retention_owner',
        'nekkora_retention_tables_owner', 'nekkora_portal', 'nekkora_portal_owner'];
    v_cfg TEXT[];
BEGIN
    -- atributos
    SELECT string_agg(rolname, ', ') INTO v_bad FROM pg_roles
    WHERE rolname = ANY (v_protegidos) AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel protegido com atributo privilegiado: %', v_bad; END IF;
    IF NOT (SELECT rolcanlogin FROM pg_roles WHERE rolname = 'nekkora_portal')
       OR (SELECT rolcanlogin FROM pg_roles WHERE rolname = 'nekkora_portal_owner') THEN
        RAISE EXCEPTION 'nekkora_portal deve ser LOGIN e nekkora_portal_owner NOLOGIN';
    END IF;

    -- nenhum papel LOGIN de servico e dono de tabela ou funcao
    SELECT string_agg(DISTINCT pg_get_userbyid(o.owner), ', ') INTO v_bad FROM (
        SELECT relowner AS owner FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
        UNION ALL
        SELECT proowner FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public'
    ) o JOIN pg_roles r ON r.oid = o.owner
    WHERE r.rolname LIKE 'nekkora\_%' AND r.rolcanlogin;
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel LOGIN dono de objeto: %', v_bad; END IF;

    -- membership em qualquer sentido
    SELECT string_agg(format('%s -> %s', r.rolname, m.rolname), ', ') INTO v_bad
    FROM pg_auth_members am JOIN pg_roles r ON r.oid = am.roleid JOIN pg_roles m ON m.oid = am.member
    WHERE r.rolname = ANY (v_protegidos) OR m.rolname = ANY (v_protegidos);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'membership envolvendo papel protegido (concedido -> membro): %', v_bad; END IF;

    -- CREATE efetivo
    SELECT string_agg(p, ', ') INTO v_bad FROM unnest(v_protegidos) AS p
    WHERE has_schema_privilege(p, 'public', 'CREATE') OR has_database_privilege(p, current_database(), 'CREATE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel protegido com CREATE efetivo em public ou no banco: %', v_bad; END IF;
    IF NOT has_schema_privilege('nekkora_portal', 'public', 'USAGE') OR NOT has_schema_privilege('nekkora_portal_owner', 'public', 'USAGE') THEN
        RAISE EXCEPTION 'papeis do portal sem USAGE em public';
    END IF;

    -- SECURITY DEFINER com search_path fixo e sem EXECUTE publico
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.prosecdef
      AND (p.proconfig IS NULL OR NOT EXISTS (SELECT 1 FROM unnest(p.proconfig) c WHERE c LIKE 'search_path=%')
           OR has_function_privilege('public', p.oid, 'EXECUTE'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'SECURITY DEFINER sem search_path fixo ou com EXECUTE publico: %', v_bad; END IF;

    -- o papel do portal nao tem nenhum privilegio de tabela/coluna/sequencia em public
    SELECT string_agg(c.relname, ', ') INTO v_bad
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind IN ('r', 'v', 'm', 'p', 'f')
      AND (has_table_privilege('nekkora_portal', c.oid, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
           OR has_any_column_privilege('nekkora_portal', c.oid, 'SELECT, INSERT, UPDATE, REFERENCES'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'nekkora_portal com privilegio de tabela/coluna: %', v_bad; END IF;
    SELECT string_agg(c.relname, ', ') INTO v_bad
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'
      -- CASE: a ordem de avaliacao do AND nao e garantida, e has_sequence_privilege falha em nao-sequencia
      AND CASE WHEN c.relkind = 'S' THEN has_sequence_privilege('nekkora_portal', c.oid, 'USAGE, SELECT, UPDATE') ELSE false END;
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'nekkora_portal com privilegio de sequencia: %', v_bad; END IF;

    -- o owner nao tem privilegio de tabela inteira (so por coluna), nem INSERT/DELETE/TRUNCATE
    SELECT string_agg(c.relname, ', ') INTO v_bad
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind IN ('r', 'v', 'm', 'p', 'f')
      AND has_table_privilege('nekkora_portal_owner', c.oid, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'nekkora_portal_owner com privilegio de tabela inteira: %', v_bad; END IF;

    -- entre as funcoes SECURITY DEFINER, o portal executa exatamente as quatro publicas
    SELECT string_agg(p.oid::regprocedure::text, ', ' ORDER BY p.oid::regprocedure::text) INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.prosecdef AND has_function_privilege('nekkora_portal', p.oid, 'EXECUTE');
    IF v_bad IS DISTINCT FROM 'portal_get_execution(uuid,text,uuid), portal_list_execution_events(uuid,text,uuid), '
        'portal_list_executions(uuid,text,uuid,smallint[],timestamp with time zone,timestamp with time zone,timestamp with time zone,uuid,integer), '
        'portal_list_workspaces(uuid,text)' THEN
        RAISE EXCEPTION 'funcoes SECURITY DEFINER executaveis pelo portal divergem do esperado: %', v_bad;
    END IF;
    IF has_function_privilege('nekkora_portal', 'public.portal_resolve_session(uuid,text)', 'EXECUTE')
       OR has_function_privilege('nekkora_portal', 'public.portal_authorized_workspaces(uuid,uuid,uuid,timestamptz)', 'EXECUTE') THEN
        RAISE EXCEPTION 'funcao interna do portal executavel pelo nekkora_portal';
    END IF;

    -- configuracoes de log fixadas no papel
    SELECT s.setconfig INTO v_cfg FROM pg_db_role_setting s
    WHERE s.setrole = 'nekkora_portal'::regrole AND s.setdatabase = 0;
    IF v_cfg IS NULL OR NOT (v_cfg @> ARRAY['log_error_verbosity=terse', 'log_min_error_statement=panic', 'log_parameter_max_length=0',
                                           'log_statement=none', 'log_min_duration_statement=-1', 'log_min_duration_sample=-1',
                                           'log_transaction_sample_rate=0']) THEN
        RAISE EXCEPTION 'configuracoes de log do nekkora_portal ausentes ou divergentes: %', v_cfg;
    END IF;
END;
$$;

-- === 8. Invariantes verificados ao final ===

DO $$
DECLARE
    v_bad TEXT;
    v_protegidos CONSTANT TEXT[] := ARRAY['nekkora_platform', 'nekkora_ingestion', 'nekkora_scheduler', 'nekkora_provisioning',
        'nekkora_retention_executor', 'nekkora_scheduler_owner', 'nekkora_provisioning_owner', 'nekkora_retention_owner',
        'nekkora_retention_tables_owner', 'nekkora_portal', 'nekkora_portal_owner'];
    v_novas CONSTANT TEXT[] := ARRAY['user_mfa_factors', 'user_mfa_recovery_codes', 'auth_challenges', 'mfa_events'];
BEGIN
    SELECT string_agg(rolname, ', ') INTO v_bad FROM pg_roles
    WHERE rolname = ANY (v_protegidos) AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel protegido com atributo privilegiado: %', v_bad; END IF;

    SELECT string_agg(format('%s -> %s', r.rolname, m.rolname), ', ') INTO v_bad
    FROM pg_auth_members am JOIN pg_roles r ON r.oid = am.roleid JOIN pg_roles m ON m.oid = am.member
    WHERE r.rolname = ANY (v_protegidos) OR m.rolname = ANY (v_protegidos);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'membership envolvendo papel protegido: %', v_bad; END IF;

    SELECT string_agg(p, ', ') INTO v_bad FROM unnest(v_protegidos) AS p
    WHERE has_schema_privilege(p, 'public', 'CREATE') OR has_database_privilege(p, current_database(), 'CREATE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel protegido com CREATE efetivo: %', v_bad; END IF;

    -- tabelas novas: RLS ENABLE + FORCE, exatamente uma politica (platform), sem privilegio do portal
    SELECT string_agg(c.relname, ', ') INTO v_bad
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relname = ANY (v_novas)
      AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity
           OR (SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname AND p.policyname NOT IN ('ops_function_context', 'identity_function_context')) <> 1);
    IF v_bad IS NOT NULL OR (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                             WHERE n.nspname = 'public' AND c.relname = ANY (v_novas)) <> 4 THEN
        RAISE EXCEPTION 'tabelas da 014 sem RLS/politica esperada: %', v_bad;
    END IF;
    SELECT string_agg(format('%s:%s', p, t), ', ') INTO v_bad
    FROM unnest(ARRAY['nekkora_portal', 'nekkora_portal_owner', 'nekkora_ingestion', 'nekkora_scheduler', 'nekkora_provisioning',
                      'nekkora_retention_executor']) AS p, unnest(v_novas) AS t
    WHERE has_table_privilege(p, 'public.' || t, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
       OR has_any_column_privilege(p, 'public.' || t, 'SELECT, INSERT, UPDATE, REFERENCES');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel indevido com privilegio nas tabelas da 014: %', v_bad; END IF;
    SELECT string_agg(t, ', ') INTO v_bad FROM unnest(v_novas) AS t
    WHERE has_table_privilege('nekkora_platform', 'public.' || t, 'DELETE, TRUNCATE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'nekkora_platform com DELETE/TRUNCATE nas tabelas da 014: %', v_bad; END IF;

    -- colunas novas de user_sessions: invisiveis aos papeis do portal
    SELECT string_agg(format('%s:%s', p, col), ', ') INTO v_bad
    FROM unnest(ARRAY['nekkora_portal', 'nekkora_portal_owner']) AS p, unnest(ARRAY['mfa_verified_at', 'mfa_step_up_at', 'csrf_token']) AS col
    WHERE has_column_privilege(p, 'public.user_sessions', col, 'SELECT, INSERT, UPDATE, REFERENCES');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel do portal com acesso a coluna nova de user_sessions: %', v_bad; END IF;

    -- ativacao: nenhuma sessao ativa sem marcador de MFA
    IF EXISTS (SELECT 1 FROM public.user_sessions WHERE revoked_at IS NULL AND mfa_verified_at IS NULL) THEN
        RAISE EXCEPTION 'sessao ativa sem marcador de MFA apos a ativacao';
    END IF;

    -- permissao de reset: exatamente ORG_ADMIN
    IF (SELECT array_agg(r.code ORDER BY r.code) FROM role_permissions rp JOIN roles r ON r.role_id = rp.role_id
        JOIN permissions p ON p.permission_id = rp.permission_id WHERE p.code = 'identity.mfa.reset') IS DISTINCT FROM ARRAY['ORG_ADMIN'] THEN
        RAISE EXCEPTION 'identity.mfa.reset deve pertencer exatamente a ORG_ADMIN';
    END IF;

    -- triggers de user_sessions presentes e habilitadas
    IF (SELECT count(*) FROM pg_trigger WHERE tgrelid = 'public.user_sessions'::regclass AND NOT tgisinternal AND tgenabled = 'O'
        AND tgname IN ('trg_user_sessions_enforce_mfa', 'trg_user_sessions_prevent_reactivation')) <> 2 THEN
        RAISE EXCEPTION 'triggers de user_sessions ausentes ou desabilitadas';
    END IF;

    -- nenhuma SECURITY DEFINER sem search_path ou com EXECUTE publico (inalterado desde a 012/013)
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.prosecdef
      AND (p.proconfig IS NULL OR NOT EXISTS (SELECT 1 FROM unnest(p.proconfig) c WHERE c LIKE 'search_path=%')
           OR has_function_privilege('public', p.oid, 'EXECUTE'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'SECURITY DEFINER sem search_path fixo ou com EXECUTE publico: %', v_bad; END IF;
END;
$$;

-- === 19. Invariantes verificados ao final ===

DO $$
DECLARE
    v_bad TEXT;
    v_ops_roles CONSTANT TEXT[] := ARRAY['nekkora_ops_mfa_owner', 'nekkora_ops_mfa', 'nekkora_ops_ca', 'nekkora_ops_intake'];
    v_servico CONSTANT TEXT[] := ARRAY['nekkora_platform', 'nekkora_ingestion', 'nekkora_scheduler', 'nekkora_provisioning',
        'nekkora_retention_executor', 'nekkora_portal', 'nekkora_ops_ca', 'nekkora_ops_mfa', 'nekkora_ops_intake'];
    v_esperado CONSTANT TEXT[] := ARRAY[
        'ops_account_proof_issue=nekkora_platform', 'ops_bootstrap_operator=', 'ops_ca_resolve_operator=nekkora_ops_ca',
        'ops_contact_add=nekkora_ops_mfa', 'ops_contact_change_channel=nekkora_ops_mfa', 'ops_contact_link_code_issue=nekkora_ops_mfa',
        'ops_contact_remove=nekkora_ops_mfa', 'ops_intake_key_activate=nekkora_ops_intake', 'ops_intake_register=nekkora_ops_intake',
        'ops_link_contact=nekkora_ops_mfa', 'ops_link_platform_account=nekkora_ops_mfa',
        'ops_link_remove=nekkora_ops_mfa', 'ops_mfa_callback_register=nekkora_ops_mfa', 'ops_mfa_cancel=nekkora_ops_mfa',
        'ops_mfa_execute=nekkora_ops_mfa',
        'ops_mfa_final_approve=nekkora_ops_mfa', 'ops_mfa_open=nekkora_ops_mfa', 'ops_mfa_preapprove=nekkora_ops_mfa',
        'ops_mfa_record_failure=nekkora_ops_mfa', 'ops_mfa_resend_notice=nekkora_ops_mfa', 'ops_mfa_status=nekkora_ops_mfa',
        'ops_mfa_verify=nekkora_ops_mfa', 'ops_notice_attempt_state=nekkora_platform', 'ops_notice_claim=nekkora_platform',
        'ops_notice_confirm=nekkora_platform', 'ops_notice_contest=nekkora_platform',
        'ops_notice_delivery_result=nekkora_platform', 'ops_notice_issue=nekkora_platform', 'ops_notice_pending=nekkora_platform',
        'ops_notice_recover=nekkora_platform', 'ops_notice_stalled=nekkora_platform', 'ops_operator_create=nekkora_ops_mfa',
        'ops_operator_disable=nekkora_ops_mfa', 'ops_operator_grant=nekkora_ops_mfa', 'ops_operator_revoke=nekkora_ops_mfa',
        'ops_register_certificate=nekkora_ops_ca', 'ops_revoke_certificate=nekkora_ops_ca'];
    v_real TEXT[];
BEGIN
    -- atributos e memberships dos papeis novos
    SELECT string_agg(rolname, ', ') INTO v_bad FROM pg_roles
    WHERE rolname = ANY (v_ops_roles) AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication
        OR (rolname IN ('nekkora_ops_mfa_owner', 'nekkora_ops_mfa') AND rolcanlogin)
        OR (rolname IN ('nekkora_ops_ca', 'nekkora_ops_intake') AND NOT rolcanlogin));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel operacional com atributo indevido: %', v_bad; END IF;
    SELECT string_agg(format('%s -> %s', r.rolname, m.rolname), ', ') INTO v_bad
    FROM pg_auth_members am JOIN pg_roles r ON r.oid = am.roleid JOIN pg_roles m ON m.oid = am.member
    WHERE (r.rolname = ANY (v_ops_roles) OR m.rolname = ANY (v_ops_roles))
      AND NOT (r.rolname = 'nekkora_ops_mfa' AND m.rolname ~ '^op_[a-z0-9_]{2,40}$' AND NOT am.admin_option);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'membership indevida envolvendo papel operacional: %', v_bad; END IF;
    SELECT string_agg(p, ', ') INTO v_bad FROM unnest(v_ops_roles) AS p
    WHERE has_schema_privilege(p, 'public', 'CREATE') OR has_database_privilege(p, current_database(), 'CREATE')
       OR has_schema_privilege(p, 'ops', 'CREATE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel operacional com CREATE efetivo: %', v_bad; END IF;

    -- schema ops: USAGE so do proprietario operacional; nenhum papel de servico toca tabela de ops
    SELECT string_agg(r.rolname, ', ') INTO v_bad FROM pg_roles r
    WHERE r.rolname LIKE 'nekkora\_%' AND r.rolname <> 'nekkora_ops_mfa_owner' AND NOT r.rolsuper
      AND has_schema_privilege(r.oid, 'ops', 'USAGE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'USAGE indevido no schema ops: %', v_bad; END IF;
    SELECT string_agg(format('%s:%s', p, c.relname), ', ') INTO v_bad
    FROM unnest(v_servico || ARRAY['nekkora_portal_owner', 'nekkora_scheduler_owner', 'nekkora_provisioning_owner',
                                   'nekkora_retention_owner', 'nekkora_retention_tables_owner']) AS p,
         pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'ops' AND c.relkind = 'r'
      AND (has_table_privilege(p, c.oid, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
           OR has_any_column_privilege(p, c.oid, 'SELECT, INSERT, UPDATE, REFERENCES'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel de servico com privilegio em tabela de ops: %', v_bad; END IF;
    SELECT string_agg(c.relname, ', ') INTO v_bad FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'ops' AND c.relkind = 'r' AND has_table_privilege('nekkora_ops_mfa_owner', c.oid, 'DELETE, TRUNCATE, REFERENCES, TRIGGER');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'proprietario operacional com DELETE/TRUNCATE em ops: %', v_bad; END IF;
    IF (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'ops' AND c.relkind = 'r') <> 21 THEN
        RAISE EXCEPTION 'inventario de tabelas de ops divergente';
    END IF;

    -- matriz de EXECUTE (publico = ops_*): exatamente os chamadores previstos (fora dono e superusuarios)
    SELECT array_agg(x ORDER BY x COLLATE "C") INTO v_real FROM (
        SELECT p.proname || '=' || coalesce((SELECT string_agg(r.rolname, ',' ORDER BY r.rolname COLLATE "C") FROM pg_roles r
                 WHERE NOT r.rolsuper AND r.rolname <> 'nekkora_ops_mfa_owner' AND r.rolname !~ '^op_'
                   AND has_function_privilege(r.oid, p.oid, 'EXECUTE')), '') AS x
        FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public' AND p.proname LIKE 'ops\_%') s;
    IF v_real IS DISTINCT FROM v_esperado THEN
        RAISE EXCEPTION 'matriz de EXECUTE divergente: %', v_real;
    END IF;
    -- funcoes e objetos de ops (P4-R1 rev. 2, proposta A; substitui "funcoes internas: nenhum papel alem do dono").
    -- Ordem: identidade de implantacao, DONOS por categoria, ACL explicita EXATA e, por ultimo, EXECUTE EFETIVO.
    -- A identidade de implantacao e DECLARADA aqui (modelo suportado, ver cabecalho), nunca derivada do catalogo que
    -- esta sendo validado; o dono do banco so e CONFERIDO contra ela (IMPLANTACAO.md: o migrador e dono do banco).
    -- Categorias: membros de sslinfo e gatilhos prevent_* -> identidade de implantacao (sec. 2 e 18: nao reatribuidos);
    -- demais funcoes de ops -> nekkora_ops_mfa_owner (sec. 18). Concessao extra prevista: EXECUTE de nekkora_ops_mfa_owner
    -- nos membros de sslinfo (sec. 2). Nada e aceito por ser "o dono": o dono so e aceito depois de conferido.
    DECLARE
        v_identidade_impl CONSTANT TEXT := 'migrador_nekkora';      -- MODELO SUPORTADO (cabecalho, revisao 5)
        v_dono_banco TEXT := (SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database());
        v_gatilhos CONSTANT TEXT[] := ARRAY['prevent_certificate_change', 'prevent_contact_change', 'prevent_entry_change',
            'prevent_key_state_change', 'prevent_link_change', 'prevent_notice_change', 'prevent_notice_token_change',
            'prevent_operator_change', 'prevent_permission_change', 'prevent_proof_change', 'prevent_request_change'];
    BEGIN
        -- (a1) a identidade declarada existe e nao e papel operacional, de servico, proprietario de funcoes, backup ou operador
        IF to_regrole(v_identidade_impl) IS NULL OR v_identidade_impl = ANY (v_ops_roles || v_servico || ARRAY[
               'nekkora_portal_owner', 'nekkora_scheduler_owner', 'nekkora_provisioning_owner', 'nekkora_retention_owner',
               'nekkora_retention_tables_owner', 'backup_nekkora']) OR v_identidade_impl ~ '^op_' THEN
            RAISE EXCEPTION 'identidade de implantacao declarada invalida ou inexistente: %', v_identidade_impl;
        END IF;
        -- (a2) exigencia do contrato operacional: o dono do banco E a identidade declarada (qualquer outro papel reprova,
        --      inclusive um fora das listas acima e um superusuario)
        IF v_dono_banco IS DISTINCT FROM v_identidade_impl THEN
            RAISE EXCEPTION 'dono do banco (%) diferente da identidade de implantacao declarada (%): modelo nao suportado',
                v_dono_banco, v_identidade_impl;
        END IF;
        -- (b) donos fora de funcoes: schema ops, extensao sslinfo (em ops) e seus membros (em ops), relacoes de ops
        WITH nao_funcao AS (
            SELECT 'schema ops'::text AS objeto, pg_get_userbyid(nspowner) AS dono, true AS local_ok
            FROM pg_namespace WHERE nspname = 'ops'
            UNION ALL
            SELECT 'extensao sslinfo', pg_get_userbyid(e.extowner), n.nspname = 'ops'
            FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'sslinfo'
            UNION ALL
            SELECT 'membro de sslinfo ' || p.oid::regprocedure::text, pg_get_userbyid(p.proowner), n.nspname = 'ops'
            FROM pg_depend d JOIN pg_extension e ON e.oid = d.refobjid JOIN pg_proc p ON p.oid = d.objid
                 JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE e.extname = 'sslinfo' AND d.classid = 'pg_proc'::regclass AND d.refclassid = 'pg_extension'::regclass
              AND d.deptype = 'e'
            UNION ALL
            SELECT c.oid::regclass::text, pg_get_userbyid(c.relowner), true
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'ops'
        )
        SELECT string_agg(format('%s[dono %s%s]', objeto, coalesce(dono, '?'), CASE WHEN local_ok THEN '' ELSE ', fora de ops' END),
                          ', ' ORDER BY objeto COLLATE "C") INTO v_bad
        FROM nao_funcao WHERE dono IS DISTINCT FROM v_identidade_impl OR NOT local_ok;
        IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'dono divergente em ops: %', v_bad; END IF;
        -- (c) funcoes de ops: categoria, dono esperado e ACL explicita exata (concessionario, privilegio, concedente)
        WITH obj AS (
            SELECT p.oid::regprocedure::text AS assinatura, p.proname::text AS nome, pg_get_userbyid(p.proowner) AS dono,
                   p.proacl AS acl, p.prorettype = 'trigger'::regtype AS ret_gatilho, p.prosecdef AS secdef,
                   (SELECT e.extname::text FROM pg_depend d JOIN pg_extension e ON e.oid = d.refobjid
                    WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.refclassid = 'pg_extension'::regclass
                      AND d.deptype = 'e') AS ext
            FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'ops'
        ), cat AS (
            SELECT o.*, CASE
                WHEN o.ext = 'sslinfo' THEN 'sslinfo'
                WHEN o.ext IS NOT NULL THEN NULL
                WHEN o.nome = ANY (v_gatilhos) AND o.ret_gatilho AND NOT o.secdef THEN 'gatilho'
                WHEN o.nome LIKE 'prevent\_%' OR o.nome LIKE 'ssl\_%' OR o.ret_gatilho OR o.secdef THEN NULL
                ELSE 'interna' END AS categoria
            FROM obj o
        ), esp AS (
            SELECT c.*,
                   CASE WHEN c.categoria = 'interna' THEN 'nekkora_ops_mfa_owner'
                        WHEN c.categoria IN ('sslinfo', 'gatilho') THEN v_identidade_impl END AS dono_esp,
                   CASE WHEN c.categoria = 'sslinfo' THEN ARRAY['nekkora_ops_mfa_owner'] ELSE ARRAY[]::TEXT[] END AS extras
            FROM cat c
        ), acl AS (
            SELECT e.*,
                   (SELECT coalesce(array_agg(t.s ORDER BY t.s COLLATE "C"), '{}') FROM (
                        SELECT CASE WHEN x.grantee = 0 THEN 'PUBLIC' ELSE pg_get_userbyid(x.grantee) END || '=' || x.privilege_type
                               || CASE WHEN x.is_grantable THEN '*' ELSE '' END || '/' || pg_get_userbyid(x.grantor) AS s
                        FROM aclexplode(coalesce(e.acl, acldefault('f', to_regrole(e.dono)::oid))) x) t) AS acl_real,
                   (SELECT coalesce(array_agg(t.s ORDER BY t.s COLLATE "C"), '{}') FROM (
                        SELECT g || '=EXECUTE/' || e.dono_esp AS s FROM unnest(ARRAY[e.dono_esp] || e.extras) g) t) AS acl_esp
            FROM esp e
        )
        SELECT string_agg(format('%s[%s]', v.assinatura, v.motivo), ', ' ORDER BY v.assinatura COLLATE "C") INTO v_bad FROM (
            SELECT assinatura, 'categoria nao prevista' AS motivo FROM acl WHERE categoria IS NULL
            UNION ALL
            SELECT assinatura, format('dono %s, esperado %s', dono, dono_esp) FROM acl
            WHERE categoria IS NOT NULL AND dono IS DISTINCT FROM dono_esp
            UNION ALL
            SELECT assinatura, format('ACL %s, esperada %s', acl_real, acl_esp) FROM acl
            WHERE categoria IS NOT NULL AND dono = dono_esp AND acl_real IS DISTINCT FROM acl_esp
            UNION ALL
            SELECT '(gatilhos prevent_*)', format('%s de %s', count(*) FILTER (WHERE categoria = 'gatilho'), cardinality(v_gatilhos))
            FROM acl HAVING count(*) FILTER (WHERE categoria = 'gatilho') <> cardinality(v_gatilhos)
        ) v;
        IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'funcao de ops fora da matriz de donos e ACL: %', v_bad; END IF;
        -- (d) EXECUTE efetivo (inclui heranca por participacao): so o dono (ja conferido em c) e, nos membros de sslinfo,
        -- nekkora_ops_mfa_owner; superusuarios fora (tem tudo). Servicos, operadores, PUBLIC e membros de donos reprovam.
        SELECT string_agg(p.oid::regprocedure::text || ':' || r.rolname, ', ') INTO v_bad
        FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace, pg_roles r
        WHERE n.nspname = 'ops' AND NOT r.rolsuper AND r.oid <> p.proowner
          AND NOT (r.rolname = 'nekkora_ops_mfa_owner' AND EXISTS (
                SELECT 1 FROM pg_depend d JOIN pg_extension e ON e.oid = d.refobjid
                WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.refclassid = 'pg_extension'::regclass
                  AND d.deptype = 'e' AND e.extname = 'sslinfo'))
          AND has_function_privilege(r.oid, p.oid, 'EXECUTE');
        IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'EXECUTE indevido em funcao de ops: %', v_bad; END IF;
    END;
    -- publicas: SECURITY DEFINER, dono operacional, search_path restrito
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.proname LIKE 'ops\_%'
      AND (NOT p.prosecdef OR pg_get_userbyid(p.proowner) <> 'nekkora_ops_mfa_owner'
           OR NOT ('search_path=pg_catalog, pg_temp' = ANY (coalesce(p.proconfig, '{}'))));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'funcao operacional fora do padrao: %', v_bad; END IF;
    -- publicas: a guarda de isolamento e a PRIMEIRA instrucao (plpgsql: logo depois do BEGIN; sql: primeiro comando)
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace JOIN pg_language l ON l.oid = p.prolang
    WHERE n.nspname = 'public' AND p.proname LIKE 'ops\_%'
      AND NOT CASE l.lanname
                WHEN 'plpgsql' THEN substring(p.prosrc FROM '\mBEGIN\M.*') ~ '^BEGIN\s+PERFORM ops\.exigir_read_committed\(\);'
                WHEN 'sql' THEN p.prosrc ~ '^\s*SELECT ops\.exigir_read_committed\(\);'
                ELSE false END;
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'funcao operacional sem a guarda de isolamento como primeira instrucao: %', v_bad; END IF;

    -- RLS: politica do proprietario operacional nas 9 tabelas de tenant, sem alterar as existentes
    IF (SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND policyname = 'ops_function_context'
          AND roles = ARRAY['nekkora_ops_mfa_owner']::name[]) <> 9 THEN
        RAISE EXCEPTION 'politicas ops_function_context divergentes';
    END IF;
    SELECT string_agg(c.relname, ', ') INTO v_bad FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname IN ('organizations', 'users', 'organization_memberships',
          'organization_role_assignments', 'user_mfa_factors', 'user_mfa_recovery_codes', 'auth_challenges', 'user_sessions', 'mfa_events')
      AND NOT (c.relrowsecurity AND c.relforcerowsecurity);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'tabela sem RLS forcada: %', v_bad; END IF;
    -- o proprietario operacional nao altera tabelas de tenant alem das colunas do reset
    IF has_table_privilege('nekkora_ops_mfa_owner', 'public.users', 'INSERT, DELETE, TRUNCATE')
       OR has_column_privilege('nekkora_ops_mfa_owner', 'public.users', 'status', 'UPDATE')
       OR has_table_privilege('nekkora_ops_mfa_owner', 'public.user_credentials', 'SELECT, UPDATE')
       OR has_any_column_privilege('nekkora_ops_mfa_owner', 'public.user_credentials', 'SELECT, UPDATE')
       OR has_any_column_privilege('nekkora_ops_mfa_owner', 'public.organization_role_assignments', 'UPDATE, INSERT')
       OR has_any_column_privilege('nekkora_ops_mfa_owner', 'public.organization_memberships', 'UPDATE, INSERT')
       OR has_table_privilege('nekkora_ops_mfa_owner', 'public.mfa_events', 'UPDATE, DELETE, TRUNCATE') THEN
        RAISE EXCEPTION 'proprietario operacional com privilegio alem da matriz';
    END IF;

    -- mfa_events: vinculo composto, UNIQUE, CHECKs; Platform sem INSERT em ops_request_id
    IF (SELECT count(*) FROM pg_constraint WHERE conrelid = 'public.mfa_events'::regclass AND conname IN
          ('mfa_events_ops_request_id_key', 'mfa_events_ops_request_fk', 'mfa_events_ops_request_tipo_4', 'mfa_events_tipo_4_sem_ator',
           'mfa_events_reason_code_so_admin', 'mfa_events_reset_admin_exige_motivo')) <> 6 THEN
        RAISE EXCEPTION 'restricoes de mfa_events ausentes';
    END IF;
    IF (SELECT convalidated FROM pg_constraint WHERE conrelid = 'public.mfa_events'::regclass AND conname = 'mfa_events_reset_admin_exige_motivo') THEN
        RAISE EXCEPTION 'motivo do reset deve ser NOT VALID (preserva o historico)';
    END IF;
    IF has_column_privilege('nekkora_platform', 'public.mfa_events', 'ops_request_id', 'INSERT, UPDATE')
       OR NOT has_column_privilege('nekkora_platform', 'public.mfa_events', 'reason_code', 'INSERT') THEN
        RAISE EXCEPTION 'privilegio da Platform em mfa_events divergente';
    END IF;

    -- I-3/I-4 (retirada PONTUAL): ops_registry_events sem FK para organizations; ops_mfa_recovery_events mantem a sua
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'ops.ops_registry_events'::regclass AND contype = 'f'
               AND confrelid = 'public.organizations'::regclass)
       OR NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'ops.ops_mfa_recovery_events'::regclass AND contype = 'f'
                      AND confrelid = 'public.organizations'::regclass) THEN
        RAISE EXCEPTION 'FKs de auditoria para organizations fora da decisao I-3/I-4';
    END IF;

    -- sslinfo no schema ops, sem EXECUTE publico
    IF NOT EXISTS (SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'sslinfo' AND n.nspname = 'ops') THEN
        RAISE EXCEPTION 'sslinfo fora do schema ops';
    END IF;

    -- controles anteriores: SECURITY DEFINER com search_path e sem EXECUTE publico (012-015)
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname IN ('public', 'ops') AND p.prosecdef
      AND (p.proconfig IS NULL OR NOT EXISTS (SELECT 1 FROM unnest(p.proconfig) c WHERE c LIKE 'search_path=%')
           OR has_function_privilege('public', p.oid, 'EXECUTE'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'SECURITY DEFINER sem search_path fixo ou com EXECUTE publico: %', v_bad; END IF;
END;
$$;

-- === 12. Invariantes verificados ao final (secao 4.3) ===

DO $$
DECLARE
    v_bad TEXT;
    v_n INTEGER;
    v_protegidos CONSTANT TEXT[] := ARRAY['nekkora_platform', 'nekkora_ingestion', 'nekkora_scheduler', 'nekkora_provisioning',
        'nekkora_retention_executor', 'nekkora_scheduler_owner', 'nekkora_provisioning_owner', 'nekkora_retention_owner',
        'nekkora_retention_tables_owner', 'nekkora_portal', 'nekkora_portal_owner', 'nekkora_identity_owner'];
    v_novas CONSTANT TEXT[] := ARRAY['identities', 'identity_credentials', 'identity_users', 'identity_mfa_factors',
        'identity_mfa_recovery_codes', 'identity_auth_challenges', 'identity_sessions', 'identity_link_challenges',
        'identity_email_tokens', 'identity_events'];
    v_exec_esperado CONSTANT TEXT := 'identity_account_is_linked(uuid,uuid), identity_challenge_lookup(text), '
        'identity_create_organization(text,text,text), identity_email_token_lookup(text), '
        'identity_link_account(text,text,uuid), '
        'identity_login_complete(text,text,bigint,text,text,text,timestamp with time zone), '
        'identity_mfa_confirm_first(text,uuid,text[],bigint,text,text,timestamp with time zone), '
        'identity_mfa_enroll_pending(text,text,bytea,bytea,text), identity_mfa_replace(text,uuid,text[]), '
        'identity_org_creation_allowed(text), '
        'identity_org_session_allowed(uuid,uuid,uuid), identity_org_session_target(text,uuid), identity_organizations(text), '
        'identity_password_change(text,text,text), '
        'identity_resolve_login(text), identity_session_lookup(text), identity_signup_state(text)';
BEGIN
    -- 1. contagem de tabelas de tenant inalterada: 39 da 012 + 4 da 014 = 43
    SELECT count(*) INTO v_n FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind = 'r'
      AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'organization_id' AND NOT a.attisdropped);
    IF v_n <> 43 THEN RAISE EXCEPTION 'invariante 1: % tabelas com organization_id (esperadas 43)', v_n; END IF;

    -- 2. SECURITY DEFINER com search_path fixo e sem EXECUTE publico
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.prosecdef
      AND (p.proconfig IS NULL OR NOT EXISTS (SELECT 1 FROM unnest(p.proconfig) c WHERE c LIKE 'search_path=%')
           OR has_function_privilege('public', p.oid, 'EXECUTE'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'invariante 2: SECURITY DEFINER fora do padrao: %', v_bad; END IF;

    -- 3. funcoes de identidade executaveis pelo nekkora_platform: exatamente as oito publicas, as duas auxiliares e as
    --    quatro de consulta por chave exata (R2) e a consulta de criacao (R5)
    SELECT string_agg(p.oid::regprocedure::text, ', ' ORDER BY p.oid::regprocedure::text) INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND (p.proname LIKE 'identity\_%' OR pg_get_userbyid(p.proowner) = 'nekkora_identity_owner')
      AND has_function_privilege('nekkora_platform', p.oid, 'EXECUTE');
    IF v_bad IS DISTINCT FROM v_exec_esperado THEN
        RAISE EXCEPTION 'invariante 3: funcoes de identidade executaveis pela Platform divergem: %', v_bad;
    END IF;

    -- 4. sem UPDATE (disabled_at) da Platform no fator de identidade (I-1)
    IF has_column_privilege('nekkora_platform', 'public.identity_mfa_factors', 'disabled_at', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.identity_mfa_factors', 'confirmed_at', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.identity_mfa_factors', 'INSERT') THEN
        RAISE EXCEPTION 'invariante 4: Platform com escrita de estado no fator de identidade';
    END IF;

    -- 5. triggers de recusa e de user_sessions habilitadas
    SELECT count(*) INTO v_n FROM pg_trigger t
    WHERE NOT t.tgisinternal AND t.tgenabled = 'O'
      AND (t.tgname, t.tgrelid) IN (('trg_auth_challenges_refuse_linked', 'public.auth_challenges'::regclass),
                                    ('trg_user_mfa_factors_refuse_linked', 'public.user_mfa_factors'::regclass),
                                    ('trg_user_mfa_recovery_codes_refuse_linked', 'public.user_mfa_recovery_codes'::regclass),
                                    ('trg_password_reset_tokens_refuse_linked', 'public.password_reset_tokens'::regclass),
                                    ('trg_email_verification_tokens_refuse_linked', 'public.email_verification_tokens'::regclass),
                                    ('trg_user_credentials_refuse_linked', 'public.user_credentials'::regclass),
                                    ('trg_user_sessions_enforce_mfa', 'public.user_sessions'::regclass),
                                    ('trg_user_sessions_prevent_reactivation', 'public.user_sessions'::regclass),
                                    ('trg_identity_users_fechamento', 'public.identity_users'::regclass),
                                    ('trg_password_reset_tokens_prevent_change', 'public.password_reset_tokens'::regclass),
                                    ('trg_email_verification_tokens_prevent_change', 'public.email_verification_tokens'::regclass),
                                    ('trg_user_credentials_prevent_reassignment', 'public.user_credentials'::regclass));
    IF v_n <> 12 THEN RAISE EXCEPTION 'invariante 5: triggers de fechamento ausentes ou desabilitadas (%)', v_n; END IF;

    -- 6. E-1, E-2, E-3
    IF EXISTS (SELECT 1 FROM user_sessions s JOIN identity_users iu ON iu.user_id = s.user_id
               WHERE s.revoked_at IS NULL AND s.identity_session_id IS NULL)
       OR EXISTS (SELECT 1 FROM user_sessions s JOIN identity_sessions x ON x.identity_session_id = s.identity_session_id
                  WHERE NOT EXISTS (SELECT 1 FROM identity_users iu WHERE iu.user_id = s.user_id AND iu.identity_id = x.identity_id))
       OR EXISTS (SELECT 1 FROM user_mfa_factors f JOIN identity_users iu ON iu.user_id = f.user_id WHERE f.disabled_at IS NULL) THEN
        RAISE EXCEPTION 'invariante 6: E-1, E-2 ou E-3 violado';
    END IF;

    -- 7. Platform: nada em identity_users; sem escrita nas marcas de recuperacao; matriz exata no fator e nos codigos
    IF has_table_privilege('nekkora_platform', 'public.identity_users', 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
       OR has_any_column_privilege('nekkora_platform', 'public.identity_users', 'SELECT, INSERT, UPDATE, REFERENCES')
       OR has_column_privilege('nekkora_platform', 'public.identity_sessions', 'recovery_used_at', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.identity_sessions', 'recovery_cleared_at', 'INSERT, UPDATE')
       OR has_any_column_privilege('nekkora_platform', 'public.identity_mfa_factors', 'INSERT')
       OR has_any_column_privilege('nekkora_platform', 'public.identity_mfa_recovery_codes', 'INSERT')
       OR has_column_privilege('nekkora_platform', 'public.identity_mfa_recovery_codes', 'invalidated_at', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.identity_link_challenges', 'consumed_at', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.identity_sessions', 'INSERT')
       OR has_any_column_privilege('nekkora_platform', 'public.identity_sessions', 'INSERT')
       OR has_column_privilege('nekkora_platform', 'public.identity_auth_challenges', 'consumed_at', 'UPDATE')
       OR has_any_column_privilege('nekkora_platform', 'public.identity_mfa_recovery_codes', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.identity_credentials', 'SELECT, UPDATE')
       OR has_any_column_privilege('nekkora_platform', 'public.identity_credentials', 'SELECT, UPDATE') THEN
        RAISE EXCEPTION 'invariante 7: privilegio da Platform alem da matriz';
    END IF;

    -- 10 (R2). RLS por identidade: ENABLE + FORCE nas dez tabelas; Platform com exatamente identity_request_context nas
    --     nove (nenhuma em identity_users); dono com identity_function_scope; nenhuma outra politica
    SELECT string_agg(c.relname, ', ') INTO v_bad
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relname = ANY (v_novas) AND NOT (c.relrowsecurity AND c.relforcerowsecurity);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'invariante 10: tabela de identidade sem RLS forcada: %', v_bad; END IF;
    SELECT string_agg(format('%s:%s:%s', p.tablename, p.policyname, p.roles), ', ') INTO v_bad
    FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = ANY (v_novas)
      AND NOT ((p.policyname = 'identity_request_context' AND p.roles = ARRAY['nekkora_platform']::name[] AND p.tablename <> 'identity_users')
            OR (p.policyname = 'identity_function_scope' AND p.roles = ARRAY['nekkora_identity_owner']::name[]));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'invariante 10: politica inesperada em tabela de identidade: %', v_bad; END IF;
    IF (SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = ANY (v_novas) AND policyname = 'identity_request_context') <> 9
       OR (SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = ANY (v_novas) AND policyname = 'identity_function_scope') <> 10 THEN
        RAISE EXCEPTION 'invariante 10: contagem de politicas de identidade divergente';
    END IF;
    SELECT string_agg(t, ', ') INTO v_bad FROM unnest(v_novas) AS t
    WHERE has_table_privilege('nekkora_platform', 'public.' || t, 'DELETE, TRUNCATE, TRIGGER, REFERENCES');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'invariante 7: Platform com DELETE/TRUNCATE em tabela nova: %', v_bad; END IF;

    -- 8. donos: nenhum papel LOGIN de servico e dono; Platform sem atributos
    SELECT string_agg(DISTINCT pg_get_userbyid(o.owner), ', ') INTO v_bad FROM (
        SELECT relowner AS owner FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
        UNION ALL
        SELECT proowner FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public'
    ) o JOIN pg_roles r ON r.oid = o.owner
    WHERE r.rolname LIKE 'nekkora\_%' AND r.rolcanlogin;
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'invariante 8: papel LOGIN dono de objeto: %', v_bad; END IF;
    SELECT string_agg(rolname, ', ') INTO v_bad FROM pg_roles
    WHERE rolname = ANY (v_protegidos) AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'invariante 8: papel protegido com atributo privilegiado: %', v_bad; END IF;
    IF (SELECT rolcanlogin FROM pg_roles WHERE rolname = 'nekkora_identity_owner') THEN
        RAISE EXCEPTION 'invariante 8: nekkora_identity_owner deve ser NOLOGIN';
    END IF;

    -- 9. nenhuma membership envolvendo o dono de identidade; triggers de imutabilidade do fator e dos codigos
    SELECT string_agg(format('%s -> %s', r.rolname, m.rolname), ', ') INTO v_bad
    FROM pg_auth_members am JOIN pg_roles r ON r.oid = am.roleid JOIN pg_roles m ON m.oid = am.member
    WHERE r.rolname = 'nekkora_identity_owner' OR m.rolname = 'nekkora_identity_owner';
    IF v_bad IS NOT NULL OR pg_has_role('nekkora_platform', 'nekkora_identity_owner', 'MEMBER') THEN
        RAISE EXCEPTION 'invariante 9: membership envolvendo nekkora_identity_owner: %', v_bad;
    END IF;
    IF (SELECT count(*) FROM pg_trigger t WHERE NOT t.tgisinternal AND t.tgenabled = 'O'
          AND t.tgname IN ('trg_identity_mfa_factors_protect', 'trg_identity_mfa_recovery_codes_protect',
                           'trg_identity_mfa_recovery_codes_sessao_restrita')) <> 3 THEN
        RAISE EXCEPTION 'invariante 9: triggers de imutabilidade do fator ausentes ou desabilitadas';
    END IF;

    -- complementares: CREATE efetivo; nenhuma tabela nova com organization_id
    SELECT string_agg(p, ', ') INTO v_bad FROM unnest(v_protegidos) AS p
    WHERE has_schema_privilege(p, 'public', 'CREATE') OR has_database_privilege(p, current_database(), 'CREATE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel protegido com CREATE efetivo: %', v_bad; END IF;
    SELECT string_agg(t, ', ') INTO v_bad FROM unnest(v_novas) AS t
    WHERE EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = ('public.' || t)::regclass AND a.attname = 'organization_id');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'tabela nova com organization_id: %', v_bad; END IF;
END;
$$;

