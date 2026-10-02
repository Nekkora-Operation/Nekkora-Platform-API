-- Senhas dos papeis de SERVICO (criados pelas migrations 012/013 sem senha) - papel: admin_cluster.
-- Recebe -v verif_platform e -v verif_portal: VERIFICADORES SCRAM. Nenhum outro atributo muda: os invariantes da
-- 012-014 continuam valendo (conferidos em H03).
-- Com a 016 aplicada (ambientes ensaio-* com a 016; desenho P1a, sec. 6): tambem -v verif_ops_ca e -v verif_ops_intake,
-- para os papeis de servico da CA e da entrada (criados pela 016 sem senha). Sem a 016, o bloco e ignorado.
\set ON_ERROR_STOP on
ALTER ROLE nekkora_platform PASSWORD :'verif_platform';
ALTER ROLE nekkora_portal PASSWORD :'verif_portal';
SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_ops_ca') AS tem_016 \gset
\if :tem_016
ALTER ROLE nekkora_ops_ca PASSWORD :'verif_ops_ca';
ALTER ROLE nekkora_ops_intake PASSWORD :'verif_ops_intake';
\endif
