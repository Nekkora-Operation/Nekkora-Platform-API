-- Grupo Q (R4) - papel: nekkora_platform. Contas do grupo, pelos caminhos legitimos da fixture:
--   O1: qv1..qv21 VINCULADAS (identidade com fator, sessao de identidade com step-up, vinculacao pela funcao);
--       qn1, qn2 NAO vinculadas, com fator local (ramo 1).
--   O4: qo1..qo3 VINCULADAS; qn4 NAO vinculada.
\pset pager off
\set o4 '17000000-0000-0000-0000-000000000004'
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
SELECT pg_temp.nova_conta(:'o1', 'qv' || i || '@ex.test') FROM generate_series(1, 21) i;
SELECT pg_temp.nova_conta(:'o1', e) FROM unnest(ARRAY['qn1@ex.test', 'qn2@ex.test']) e;
SELECT pg_temp.nova_conta(:'o4', e) FROM unnest(ARRAY['qo1@ex.test', 'qo2@ex.test', 'qo3@ex.test', 'qn4@ex.test']) e;
CREATE TEMP TABLE q_vinc (email TEXT, org UUID);
INSERT INTO q_vinc SELECT 'qv' || i || '@ex.test', :'o1' FROM generate_series(1, 21) i;
INSERT INTO q_vinc SELECT 'qo' || i || '@ex.test', :'o4' FROM generate_series(1, 3) i;
SELECT pg_temp.nova_identidade(email) FROM q_vinc;
SELECT pg_temp.nova_sessao_id(pg_temp.identidade(email), 'sessao/' || email, true) FROM q_vinc;
SELECT pg_temp.afirma(pg_temp.vincula(pg_temp.identidade(email), 'sessao/' || email, pg_temp.conta(email, org), org, 'vinculo/' || email),
                      'fixture Q: ' || email || ' vinculada') FROM q_vinc;
\! sh /tmp/e.sh Q-prep
SELECT 'grupo Q, preparacao: ok' AS resultado;
