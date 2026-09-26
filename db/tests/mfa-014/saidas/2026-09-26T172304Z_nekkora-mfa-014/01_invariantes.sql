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
           OR (SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname) <> 1);
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

