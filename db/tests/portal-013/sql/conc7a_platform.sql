-- conc7a (sessao B) - papel: nekkora_platform. Revoga a concessao de Rv enquanto A (portal) mantem uma
-- transacao aberta que ja leu com ela. Afirma que a revogacao NAO esperou (< 2 s) e registra o instante
-- do commit para o runner comparar com as leituras de A.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
SELECT clock_timestamp() AS t0 \gset
BEGIN;
SET LOCAL nekkora.organization_id = :'o1';
UPDATE client_workspace_grants SET status = 4, revoked_at = clock_timestamp(), revoked_by_membership_id = :'adm1_memb' WHERE grant_id = :'gr_rv';
COMMIT;
SELECT clock_timestamp() AS t1 \gset
\echo MARCADOR_B_COMMIT=:t1
SELECT pg_temp.afirma(:'t1'::timestamptz - :'t0'::timestamptz < interval '2 seconds',
  'B: a revogacao da concessao nao esperou a transacao do portal (sem lock de concessao)');
