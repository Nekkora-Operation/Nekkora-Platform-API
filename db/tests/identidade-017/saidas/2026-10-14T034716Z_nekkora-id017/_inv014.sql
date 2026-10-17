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

