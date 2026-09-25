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

