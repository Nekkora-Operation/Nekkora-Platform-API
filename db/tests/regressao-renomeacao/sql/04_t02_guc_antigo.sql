\pset pager off
SELECT current_user AS papel;
BEGIN;
SET LOCAL radiant.actor_membership_id = '44444444-4444-4444-4444-444444444444';
INSERT INTO workspace_parameters (organization_id, workspace_id, name, status, created_by_membership_id)
VALUES ('11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','T02_GUC_ANTIGO',1,'44444444-4444-4444-4444-444444444444');
ROLLBACK;
SELECT count(*) AS parametros_t02 FROM workspace_parameters WHERE name = 'T02_GUC_ANTIGO';
