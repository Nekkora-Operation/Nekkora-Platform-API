-- T09/T10 - papel: nekkora_retention_executor (conexao direta, sessao unica do inicio ao fim).
-- Dias usados: 2026-09-20..22, estritamente passados e sem registros (fecham com 0/0).
\pset pager off
SELECT current_user AS papel, pg_backend_pid() AS pid_da_conexao;
SELECT current_setting('nekkora.retention_txn_category', true) IS NULL AS guc_nunca_tocado_nesta_sessao;

\echo === T09.1 mesmo par org A / categoria eventos, dois dias, mesma transacao: permitido
BEGIN;
SELECT close_usage_day('11111111-1111-1111-1111-111111111111', 5, '2026-09-20');
SELECT current_setting('nekkora.retention_txn_category', true) AS chave_registrada;
SELECT close_usage_day('11111111-1111-1111-1111-111111111111', 5, '2026-09-21');
COMMIT;

\echo === T09.2 mesma org, troca de categoria eventos para logs na mesma transacao: rejeitado
BEGIN;
SELECT close_usage_day('11111111-1111-1111-1111-111111111111', 5, '2026-09-22');
SELECT close_usage_day('11111111-1111-1111-1111-111111111111', 4, '2026-09-22');
ROLLBACK;

\echo === T09.3 mesma categoria, troca de organizacao A para B na mesma transacao: rejeitado
BEGIN;
SELECT close_usage_day('11111111-1111-1111-1111-111111111111', 5, '2026-09-22');
SELECT close_usage_day('bbbbbbbb-1111-1111-1111-111111111111', 5, '2026-09-22');
ROLLBACK;

\echo === T09.4 reset: mesma conexao, transacao posterior com outro par, depois de ROLLBACK
SELECT pg_backend_pid() AS mesmo_pid, current_setting('nekkora.retention_txn_category', true) AS guc_fora_de_transacao;
BEGIN;
SELECT close_usage_day('bbbbbbbb-1111-1111-1111-111111111111', 5, '2026-09-22');
COMMIT;
\echo === T09.5 reset: transacao posterior com outro par, depois de COMMIT
BEGIN;
SELECT close_usage_day('11111111-1111-1111-1111-111111111111', 4, '2026-09-22');
COMMIT;
\echo === T09.6 chamadas em autocommit consecutivas, pares diferentes: cada uma e sua propria transacao
SELECT close_usage_day('11111111-1111-1111-1111-111111111111', 5, '2026-09-22');
SELECT close_usage_day('bbbbbbbb-1111-1111-1111-111111111111', 4, '2026-09-22');

\echo === T10 executor tenta expurgo
SELECT purge_expired_records('11111111-1111-1111-1111-111111111111', 1, '55555555-1111-1111-1111-111111111111', 10);
BEGIN;
SELECT purge_expired_records('11111111-1111-1111-1111-111111111111', 1, '55555555-1111-1111-1111-111111111111', 10);
ROLLBACK;
SET ROLE nekkora_retention_owner;
\echo === executor tambem sem acesso direto as tabelas
UPDATE execution_events SET payload = NULL;
SELECT count(*) FROM organization_usage_daily;
SELECT pg_backend_pid() AS pid_final;
