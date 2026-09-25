-- conc1 verificacao - papel: nekkora. Uma unica linha para a identidade, e e a devolvida as duas sessoes.
\pset pager off
SELECT count(*) AS linhas_para_a_identidade, min(occurrence_id::text) AS occurrence_id
FROM automation_schedule_occurrences WHERE schedule_id = 'aaaaaaaa-0000-0000-000b-000000000001' AND scheduled_for = :'ts';
SELECT pg_temp.afirma((SELECT count(*) FROM automation_schedule_occurrences WHERE schedule_id = 'aaaaaaaa-0000-0000-000b-000000000001' AND scheduled_for = :'ts') = 1,
  'uma unica ocorrencia para a identidade');
SELECT pg_temp.afirma((SELECT occurrence_id FROM automation_schedule_occurrences WHERE schedule_id = 'aaaaaaaa-0000-0000-000b-000000000001' AND scheduled_for = :'ts') = :'occ_a'::uuid
                      AND :'occ_a' = :'occ_b', 'A e B receberam o mesmo occurrence_id, igual ao da linha');
