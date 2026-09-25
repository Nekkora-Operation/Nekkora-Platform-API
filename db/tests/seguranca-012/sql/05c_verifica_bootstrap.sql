-- 05c - papel: nekkora (somente leitura). Uma atribuicao de bootstrap e um evento por organizacao:
-- a tentativa B02 (evento invalido) nao deixou atribuicao orfa.
\pset pager off
SELECT a.organization_id, a.assigned_by_membership_id IS NULL AS bootstrap, r.code AS papel,
       (SELECT count(*) FROM identity_provisioning_events e WHERE e.assignment_id = a.assignment_id) AS eventos
FROM organization_role_assignments a JOIN roles r ON r.role_id = a.role_id ORDER BY 1;
