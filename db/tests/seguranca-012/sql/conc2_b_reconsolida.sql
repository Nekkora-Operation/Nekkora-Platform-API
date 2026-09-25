-- conc2 B - papel: nekkora_retention_executor. Reconsolida A/eventos/hoje enquanto a insercao de A
-- ainda nao foi confirmada: FOR UPDATE espera o FOR SHARE; o calculo, feito depois do lock, deve
-- incluir o evento confirmado por A.
\pset pager off
SELECT 'B' AS s, current_user, pg_backend_pid(), clock_timestamp() AS b_inicio;
SELECT *, clock_timestamp() AS b_concluiu FROM reconsolidate_usage_day('aaaaaaaa-0000-0000-0000-00000000000a', 5, (clock_timestamp() AT TIME ZONE 'UTC')::date);
