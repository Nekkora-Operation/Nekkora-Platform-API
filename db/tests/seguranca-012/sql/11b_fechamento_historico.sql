-- 11b - papel: nekkora_retention_executor. Fecha D = 2026-09-10 para A e para B, em transacoes
-- separadas. O resultado (A 3/135, B 2/70 - nunca 0 nem 5) e afirmado pelo 11c, que le sem RLS.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_retention_executor', 'papel nekkora_retention_executor');
SELECT close_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, '2026-09-10');
SELECT close_usage_day('bbbbbbbb-0000-0000-0000-00000000000b', 5, '2026-09-10');
SELECT pg_temp.afirma(coalesce(current_setting('nekkora.fn_organization_id', true), '') = '', 'fn_ctx restaurado');
