-- f03 - papel: nekkora_provisioning. A1 vira ORG_ADMIN de O1 pelo caminho autorizado da 012.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_provisioning', 'papel nekkora_provisioning');
SELECT pg_temp.afirma(provision_first_org_admin(:'o1', :'a1_memb', '99999999-0000-0000-0000-0000000000aa', 'suporte', gen_random_uuid()) IS NOT NULL,
  'A1 ORG_ADMIN de O1');
