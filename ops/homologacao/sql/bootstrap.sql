-- Bootstrap da homologacao - papel: admin_cluster (administrador do cluster), pelo socket local do container.
-- Separa: (1) administracao do cluster (este script e a elevacao temporaria); (2) identidade de implantacao
-- migrador_nekkora (dona do banco, NAO superusuario); (3) identidade de backup backup_nekkora (so leitura, BYPASSRLS,
-- porque as tabelas tem FORCE ROW LEVEL SECURITY e o pg_dump nao pode ser filtrado por RLS). Os papeis dos servicos
-- nascem nas migrations 011-013; as senhas deles sao definidas depois da implantacao (senhas_servicos.sql).
-- Recebe -v verif_migrador e -v verif_backup: VERIFICADORES SCRAM, nunca a senha em texto claro.
-- Os nomes NAO seguem o padrao nekkora_*: o invariante da 012 recusa qualquer papel nekkora_* com atributo privilegiado
-- (a elevacao temporaria do migrador e o BYPASSRLS do backup cairiam nele).
\set ON_ERROR_STOP on
SELECT current_user = 'admin_cluster' AND (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) AS ok \gset
\if :ok
\else
    \echo 'FALHA: bootstrap exige o administrador do cluster'
    \quit 1
\endif

CREATE ROLE migrador_nekkora LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB NOREPLICATION NOBYPASSRLS PASSWORD :'verif_migrador';
CREATE DATABASE nekkora OWNER migrador_nekkora;
CREATE ROLE backup_nekkora LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB NOREPLICATION BYPASSRLS PASSWORD :'verif_backup';
GRANT pg_read_all_data TO backup_nekkora;
