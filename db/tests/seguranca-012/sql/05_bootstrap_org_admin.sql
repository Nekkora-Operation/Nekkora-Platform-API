-- 05 - papel: nekkora_provisioning. provision_first_org_admin: caminho autorizado e rejeicoes.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_provisioning', 'papel nekkora_provisioning');

\echo === B01 vinculo de outra organizacao (membership de B para a organizacao A): erro nomeado
SELECT pg_temp.espera_erro($q$SELECT provision_first_org_admin('aaaaaaaa-0000-0000-0000-00000000000a', 'bbbbbbbb-0000-0000-0002-000000000001',
  '99999999-0000-0000-0000-0000000000aa', 'suporte', gen_random_uuid())$q$, '23503', 'vinculo nao pertence a organizacao informada');

\echo === B02 atomicidade: evento de provisionamento falha (responsavel inexistente) - atribuicao desfeita junto
SELECT pg_temp.espera_erro($q$SELECT provision_first_org_admin('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0002-000000000001',
  'dddddddd-0000-0000-0000-00000000dead', 'suporte', gen_random_uuid())$q$, '23503', 'identity_provisioning_events_provisioned_by_user_id_fkey');

\echo === B03 canal vazio / parametro ausente
SELECT pg_temp.espera_erro($q$SELECT provision_first_org_admin('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0002-000000000001',
  '99999999-0000-0000-0000-0000000000aa', '  ', gen_random_uuid())$q$, '22004', 'bootstrap exige');
SELECT pg_temp.espera_erro($q$SELECT provision_first_org_admin('aaaaaaaa-0000-0000-0000-00000000000a', NULL,
  '99999999-0000-0000-0000-0000000000aa', 'suporte', gen_random_uuid())$q$, '22004', 'bootstrap exige');

\echo === B04 caminho autorizado, A e B (B04 so passa se B02 nao deixou atribuicao orfa em A)
SELECT pg_temp.afirma(provision_first_org_admin('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0002-000000000001',
       '99999999-0000-0000-0000-0000000000aa', 'suporte', 'aaaaaaaa-1111-1111-1111-111111111111') IS NOT NULL, 'bootstrap A');
SELECT pg_temp.afirma(provision_first_org_admin('bbbbbbbb-0000-0000-0000-00000000000b', 'bbbbbbbb-0000-0000-0002-000000000001',
       '99999999-0000-0000-0000-0000000000aa', 'suporte', 'bbbbbbbb-1111-1111-1111-111111111111') IS NOT NULL, 'bootstrap B');

\echo === B05 segundo bootstrap na mesma organizacao: rejeitado
SELECT pg_temp.espera_erro($q$SELECT provision_first_org_admin('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0002-000000000001',
  '99999999-0000-0000-0000-0000000000aa', 'suporte', gen_random_uuid())$q$, 'P0001', 'ainda nao tem nenhuma atribuicao');
SELECT pg_temp.afirma(coalesce(current_setting('nekkora.fn_organization_id', true), '') = '', 'fn_organization_id vazio apos as chamadas');
