\pset pager off
SELECT current_user AS papel, clock_timestamp();
SELECT close_usage_day('11111111-1111-1111-1111-111111111111', 5, '2026-09-24');
SELECT close_usage_day('11111111-1111-1111-1111-111111111111', 4, '2026-09-24');
-- fechar de novo: rejeitado
SELECT close_usage_day('11111111-1111-1111-1111-111111111111', 5, '2026-09-24');
-- hoje (UTC, calculado na hora) continua nao fechavel
SELECT close_usage_day('11111111-1111-1111-1111-111111111111', 5, (clock_timestamp() AT TIME ZONE 'UTC')::date);
