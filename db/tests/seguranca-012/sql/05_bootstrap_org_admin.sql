-- 05 - papeis: nekkora_platform (tentativa de desvio) e nekkora_provisioning (caminho autorizado).
-- Conexao: o run.sh executa as duas partes em conexoes separadas; este arquivo e a parte do
-- provisioning. A parte do platform esta em 05b_bootstrap_desvio_platform.sql.
\pset pager off
SELECT current_user AS papel;

\echo === B01 vinculo de outra organizacao (membership de B para a organizacao A): rejeitado com erro nomeado
SELECT provision_first_org_admin('aaaaaaaa-0000-0000-0000-00000000000a', 'bbbbbbbb-0000-0000-0002-000000000001',
       '99999999-0000-0000-0000-0000000000aa', 'suporte', gen_random_uuid());

\echo === B02 atomicidade: evento de provisionamento falha (responsavel inexistente) - atribuicao desfeita junto
SELECT provision_first_org_admin('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0002-000000000001',
       'dddddddd-0000-0000-0000-00000000dead', 'suporte', gen_random_uuid());

\echo === B03 parametro obrigatorio ausente / canal vazio
SELECT provision_first_org_admin('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0002-000000000001',
       '99999999-0000-0000-0000-0000000000aa', '  ', gen_random_uuid());

\echo === B04 caminho autorizado, A e B
\set ON_ERROR_STOP on
SELECT provision_first_org_admin('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0002-000000000001',
       '99999999-0000-0000-0000-0000000000aa', 'suporte', 'aaaaaaaa-1111-1111-1111-111111111111') IS NOT NULL AS bootstrap_a;
SELECT provision_first_org_admin('bbbbbbbb-0000-0000-0000-00000000000b', 'bbbbbbbb-0000-0000-0002-000000000001',
       '99999999-0000-0000-0000-0000000000aa', 'suporte', 'bbbbbbbb-1111-1111-1111-111111111111') IS NOT NULL AS bootstrap_b;
\set ON_ERROR_STOP off

\echo === B05 segundo bootstrap na mesma organizacao: rejeitado
SELECT provision_first_org_admin('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0002-000000000001',
       '99999999-0000-0000-0000-0000000000aa', 'suporte', gen_random_uuid());
