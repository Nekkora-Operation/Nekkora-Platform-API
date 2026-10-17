-- m_privilegios - papel: nekkora (so leitura de catalogo). Grupo M: privilegios EFETIVOS (inclui heranca e PUBLIC) de
-- cada papel de servico, do dono de identidade e de PUBLIC, nas tabelas novas, nas tabelas existentes que a 017 toca e
-- nas funcoes de identidade e de provisionamento; e, desde a R2, RLS (ENABLE/FORCE) e politicas dessas tabelas.
-- Uma linha por fato; o runner compara com m_esperado.txt (completo).
\set ON_ERROR_STOP on
WITH papeis(r) AS (VALUES ('nekkora_platform'), ('nekkora_identity_owner'), ('nekkora_portal'), ('nekkora_portal_owner'),
        ('nekkora_ingestion'), ('nekkora_scheduler'), ('nekkora_provisioning'), ('nekkora_provisioning_owner'),
        ('nekkora_scheduler_owner'), ('nekkora_retention_executor'), ('nekkora_retention_owner'), ('public')),
tabelas(t) AS (SELECT unnest(ARRAY['identities', 'identity_credentials', 'identity_users', 'identity_mfa_factors', 'identity_mfa_recovery_codes',
        'identity_auth_challenges', 'identity_sessions', 'identity_link_challenges', 'identity_email_tokens', 'identity_events',
        'users', 'organizations', 'organization_memberships', 'organization_role_assignments', 'roles', 'user_credentials', 'user_sessions',
        'auth_challenges', 'user_mfa_factors', 'user_mfa_recovery_codes', 'password_reset_tokens', 'email_verification_tokens'])),
tp(p) AS (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE'), ('REFERENCES'), ('TRIGGER')),
cp(p) AS (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('REFERENCES'))
SELECT linha FROM (
    SELECT format('tab|%s|%s|%s', r, t, p) AS linha FROM papeis, tabelas, tp
     WHERE has_table_privilege(r, 'public.' || t, p)
    UNION ALL
    SELECT format('col|%s|%s|%s|%s', r, t, a.attname, p) FROM papeis, tabelas, cp, pg_attribute a
     WHERE a.attrelid = ('public.' || t)::regclass AND a.attnum > 0 AND NOT a.attisdropped
       AND has_column_privilege(r, 'public.' || t, a.attname, p) AND NOT has_table_privilege(r, 'public.' || t, p)
    UNION ALL
    SELECT format('fn|%s|%s', r, f.oid::regprocedure) FROM papeis, pg_proc f JOIN pg_namespace n ON n.oid = f.pronamespace
     WHERE n.nspname = 'public' AND (f.proname LIKE 'identity\_%' OR f.proname IN ('check_identity_link_closure', 'provision_resource_limit', 'provision_first_org_admin'))
       AND has_function_privilege(r, f.oid, 'EXECUTE')
    UNION ALL
    SELECT format('rls|%s|enable=%s|force=%s', c.relname, c.relrowsecurity, c.relforcerowsecurity) FROM tabelas JOIN pg_class c ON c.oid = ('public.' || t)::regclass
    UNION ALL
    SELECT format('pol|%s|%s|%s|%s|%s', p.tablename, p.policyname, p.cmd, p.roles, regexp_replace(coalesce(p.qual, '') || ' / ' || coalesce(p.with_check, ''), '\s+', ' ', 'g'))
      FROM pg_policies p JOIN tabelas ON p.tablename = t WHERE p.schemaname = 'public'
    UNION ALL
    SELECT format('membro|%s|nekkora_identity_owner', r) FROM papeis WHERE r <> 'public' AND r <> 'nekkora_identity_owner'
       AND pg_has_role(r, 'nekkora_identity_owner', 'MEMBER')
) x ORDER BY linha COLLATE "C";
