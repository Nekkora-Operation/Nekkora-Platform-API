-- 11c - papel: nekkora, assumindo nekkora_retention_owner via SET ROLE (so o superusuario consegue;
-- nenhum papel de servico tem membership nele). INFORMATIVO: mostra por que as funcoes de manutencao
-- precisam estabelecer nekkora.fn_organization_id - sem ele, a politica filtra tudo em silencio (0,
-- sem erro), e um fechamento contaria zero registros num dia que tem dados.
\pset pager off
BEGIN;
SET LOCAL ROLE nekkora_retention_owner;
SELECT current_user AS papel_efetivo, current_setting('nekkora.fn_organization_id', true) AS fn_ctx,
       count(*) AS eventos_de_2026_09_10_visiveis_sem_contexto
FROM execution_events WHERE (received_at AT TIME ZONE 'UTC')::date = '2026-09-10';
SET LOCAL nekkora.fn_organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
SELECT count(*) AS eventos_visiveis_com_contexto_a
FROM execution_events WHERE (received_at AT TIME ZONE 'UTC')::date = '2026-09-10';
ROLLBACK;
