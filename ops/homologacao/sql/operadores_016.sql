-- Operadores da HOMOLOGACAO da 016 (desenho P1a, sec. 6) - papel: admin_cluster, fazendo o papel do DBA FORA da
-- migration: papeis op_hml_* LOGIN sem senha (so certificado, pg_hba "cert map=ops"), membros SO de nekkora_ops_mfa, e o
-- bootstrap do primeiro operador (CADASTRAR). Os demais sao cadastrados e habilitados pelas funcoes de cadastro, com o
-- certificado do CADASTRAR (ferramenta de homologacao, job "operacao"). Emissor e sujeito do IdP SIMULADO.
-- Controle de transacao: BEGIN/COMMIT explicitos; o runner usa psql -v ON_ERROR_STOP=1 (para no primeiro erro).
\set ON_ERROR_STOP on
SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_ops_mfa') AS tem_016 \gset
\if :tem_016
\else
SELECT 'ERRO: a 016 nao esta aplicada neste ambiente' AS motivo, 1 / 0;
\endif
BEGIN;
CREATE ROLE op_hml_cad LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE op_hml_ver LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE op_hml_apr LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE op_hml_exe LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT nekkora_ops_mfa TO op_hml_cad, op_hml_ver, op_hml_apr, op_hml_exe;
SELECT 'bootstrap=' || public.ops_bootstrap_operator('op_hml_cad', 'https://idp.nekkora.test', 'sub-op_hml_cad');
COMMIT;
