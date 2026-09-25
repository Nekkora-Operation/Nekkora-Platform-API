-- conc1 verificacao - papel: nekkora.
\pset pager off
SELECT count(*) AS linhas_para_a_identidade, min(occurrence_id::text) AS occurrence_id
FROM automation_schedule_occurrences WHERE schedule_id = 'aaaaaaaa-0000-0000-000b-000000000001' AND scheduled_for = :'ts';
