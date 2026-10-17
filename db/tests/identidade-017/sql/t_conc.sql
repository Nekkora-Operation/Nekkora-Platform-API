-- t_conc - uma das duas transacoes de um cenario de concorrencia (grupos C e K). Papel: nekkora_platform (o real),
-- SQL direto, sem AuthService e sem AccountRepository.lock. Variaveis (-v): rotulo, atraso, iso, acao, segura.
-- A acao contem as proprias assercoes (pg_temp.afirma / pg_temp.espera_erro); qualquer desvio derruba o script.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
SELECT pg_sleep(:atraso);
BEGIN ISOLATION LEVEL :iso;
:acao
SELECT pg_sleep(:segura);
COMMIT;
SELECT :'rotulo' || ': ok' AS resultado;
