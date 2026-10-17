-- f04 - papel: nekkora_platform. Vinculo de paula (identidade SEM fator, sessao da f03) pelo caminho legitimo, para A-9.
\pset pager off
SELECT pg_temp.afirma(pg_temp.vincula(pg_temp.identidade('paula@ex.test'), 'sessao/paula@ex.test', pg_temp.conta('paula@ex.test', :'o1'), :'o1',
                      'vinculo/paula@ex.test'), 'fixture: paula vinculada em O1');
\! sh /tmp/e.sh f04
SELECT 'fixture f04: ok' AS resultado;
