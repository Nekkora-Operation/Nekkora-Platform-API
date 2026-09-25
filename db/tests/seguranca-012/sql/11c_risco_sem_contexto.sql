-- 11c - papel: nekkora. (1) Afirma o resultado do 11b, lido sem RLS. (2) Assumindo
-- nekkora_retention_owner via SET ROLE (so o superusuario consegue; nenhum papel de servico tem
-- membership nele): sem nekkora.fn_organization_id a politica filtra tudo em silencio (0, sem erro) -
-- e o motivo de toda funcao de manutencao estabelecer o contexto de funcao.
\pset pager off
SELECT organization_id, status, records_count, bytes_count FROM organization_usage_daily WHERE usage_date = '2026-09-10' ORDER BY 1;
SELECT pg_temp.afirma((SELECT status = 2 AND records_count = 3 AND bytes_count = 135 FROM organization_usage_daily
                       WHERE organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a' AND resource_code = 5 AND usage_date = '2026-09-10'), 'fechamento A em D = 3/135, CLOSED');
SELECT pg_temp.afirma((SELECT status = 2 AND records_count = 2 AND bytes_count = 70 FROM organization_usage_daily
                       WHERE organization_id = 'bbbbbbbb-0000-0000-0000-00000000000b' AND resource_code = 5 AND usage_date = '2026-09-10'), 'fechamento B em D = 2/70, CLOSED');
-- As contagens sao feitas sob o papel e afirmadas depois, de volta ao superusuario: o helper
-- pg_temp.afirma criado por ele nao tem EXECUTE para PUBLIC (privilegio padrao da propria 012).
BEGIN;
SET LOCAL ROLE nekkora_retention_owner;
SELECT current_user AS papel_efetivo, count(*) AS sem_contexto FROM execution_events WHERE (received_at AT TIME ZONE 'UTC')::date = '2026-09-10' \gset
SET LOCAL nekkora.fn_organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT count(*) AS com_contexto_a FROM execution_events WHERE (received_at AT TIME ZONE 'UTC')::date = '2026-09-10' \gset
ROLLBACK;
SELECT pg_temp.afirma(:'papel_efetivo' = 'nekkora_retention_owner' AND :sem_contexto = 0, 'owner sem contexto de funcao: 0 linhas visiveis num dia com 5 (filtragem silenciosa)');
SELECT pg_temp.afirma(:com_contexto_a = 3, 'owner com contexto A: 3 linhas');
