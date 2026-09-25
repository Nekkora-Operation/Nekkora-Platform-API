-- v04 - papel: nekkora (somente leitura). Estado final da base apos a verificacao.
\pset pager off
SELECT o.organization_id, o.name, o.status,
       (SELECT count(*) FROM organization_resource_limit_revisions r WHERE r.organization_id = o.organization_id) AS limites,
       (SELECT count(*) FROM organization_retention_policy_revisions r WHERE r.organization_id = o.organization_id) AS politicas_retencao,
       (SELECT count(*) FROM users u WHERE u.organization_id = o.organization_id) AS usuarios,
       (SELECT count(*) FROM workspaces w WHERE w.organization_id = o.organization_id) AS workspaces,
       (SELECT count(*) FROM organization_role_assignments a WHERE a.organization_id = o.organization_id AND a.assigned_by_membership_id IS NULL) AS bootstrap_org_admin,
       (SELECT count(*) FROM identity_provisioning_events e WHERE e.organization_id = o.organization_id) AS eventos_provisionamento
FROM organizations o ORDER BY o.created_at;
SELECT (SELECT count(*) FROM pg_roles WHERE rolname = 'nekkora_app') AS nekkora_app,
       (SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relkind = 'r' AND relrowsecurity AND relforcerowsecurity) AS tabelas_rls_force,
       (SELECT count(*) FROM pg_policy) AS politicas,
       (SELECT count(*) FILTER (WHERE tgenabled = 'O') || '/' || count(*) FROM pg_trigger WHERE NOT tgisinternal) AS triggers_habilitadas,
       current_setting('cluster_name') AS cluster_name;
