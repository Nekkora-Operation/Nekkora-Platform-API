-- 11b - papel: nekkora_retention_executor. Fecha D = 2026-09-10 para A e para B, em transacoes
-- separadas. Esperado: cada agregado conta so a propria organizacao (A 3, B 2), nunca 0 nem 5.
\pset pager off
\set ON_ERROR_STOP on
SELECT current_user AS papel;
SELECT close_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, '2026-09-10');
SELECT close_usage_day('bbbbbbbb-0000-0000-0000-00000000000b', 5, '2026-09-10');
