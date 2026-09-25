-- conc2 B - papel: nekkora_retention_executor. Reconsolida A/eventos/hoje enquanto a insercao de A
-- ainda nao foi confirmada: FOR UPDATE espera o FOR SHARE; o calculo, feito depois do lock, deve
-- incluir o evento confirmado por A (2 eventos de antes + 1 = 3).
\pset pager off
SELECT 'B' AS s, current_user, pg_backend_pid(), clock_timestamp() AS b_inicio;
SELECT out_records_count AS rec, out_bytes_count AS bytes, clock_timestamp() AS b_concluiu
FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, (clock_timestamp() AT TIME ZONE 'UTC')::date) \gset
SELECT :rec AS records, :bytes AS bytes, :'b_concluiu' AS b_concluiu;
SELECT pg_temp.afirma(:rec = 3 AND :bytes = 135, 'reconsolidacao inclui o evento confirmado por A: 3 / 135');
