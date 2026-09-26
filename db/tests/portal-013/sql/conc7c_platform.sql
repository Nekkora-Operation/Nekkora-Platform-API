-- conc7c (sessao B) - papel: nekkora_platform. Aplica (:acao = suspende) ou desfaz (:acao = restaura) a
-- mudanca de estado do caso :caso em O4, com commit: conta, vinculo, cliente, organizacao, concessao
-- (suspensa, reversivel) ou workspace. Caso "controle": nenhuma mudanca.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
SELECT :'caso' = 'conta' AS c_conta, :'caso' = 'vinculo' AS c_vinculo, :'caso' = 'cliente' AS c_cliente,
       :'caso' = 'org' AS c_org, :'caso' = 'concessao' AS c_concessao, :'caso' = 'workspace' AS c_workspace,
       :'acao' = 'suspende' AS suspende \gset
BEGIN;
SET LOCAL nekkora.organization_id = :'o4';
\if :c_conta
UPDATE users SET status = CASE WHEN :'suspende'::boolean THEN 3 ELSE 2 END WHERE user_id = :'k';
\endif
\if :c_vinculo
UPDATE client_memberships SET status = CASE WHEN :'suspende'::boolean THEN 3 ELSE 2 END WHERE client_membership_id = :'cm_k';
\endif
\if :c_cliente
UPDATE clients SET status = CASE WHEN :'suspende'::boolean THEN 2 ELSE 1 END WHERE client_id = :'c7';
\endif
\if :c_org
UPDATE organizations SET status = CASE WHEN :'suspende'::boolean THEN 2 ELSE 1 END WHERE organization_id = :'o4';
\endif
\if :c_concessao
UPDATE client_workspace_grants SET status = CASE WHEN :'suspende'::boolean THEN 3 ELSE 2 END WHERE grant_id = :'gr_k';
\endif
\if :c_workspace
UPDATE workspaces SET status = CASE WHEN :'suspende'::boolean THEN 2 ELSE 1 END WHERE workspace_id = :'ws9';
\endif
COMMIT;
\echo MARCADOR_PLATFORM_COMMIT caso=:caso acao=:acao
