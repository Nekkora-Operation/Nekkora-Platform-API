-- Senhas dos papeis de SERVICO (criados pelas migrations 012/013 sem senha) - papel: admin_cluster.
-- Recebe -v verif_platform e -v verif_portal: VERIFICADORES SCRAM. Nenhum outro atributo muda: os invariantes da
-- 012-014 continuam valendo (conferidos em H03).
\set ON_ERROR_STOP on
ALTER ROLE nekkora_platform PASSWORD :'verif_platform';
ALTER ROLE nekkora_portal PASSWORD :'verif_portal';
