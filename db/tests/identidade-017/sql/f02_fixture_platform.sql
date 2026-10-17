-- f02 - papel: nekkora_platform. Contas, identidades e vinculos pelos caminhos reais (RLS, privilegios por coluna,
-- triggers e funcoes da 017). Cada vinculo passa por identity_link_account com sessao com step-up e desafio de
-- vinculacao: a fixture ja exercita o caminho legitimo de DC-17.11/DC-17.12.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');

-- O1: contas
SELECT pg_temp.nova_conta(:'o1', 'ana@ex.test');
SELECT pg_temp.nova_conta(:'o1', 'bia@ex.test');
SELECT pg_temp.nova_conta(:'o1', 'bruno@ex.test', false);
SELECT pg_temp.nova_conta(:'o1', 'xavier@ex.test');
SELECT pg_temp.nova_conta(:'o1', 'paula@ex.test');
SELECT pg_temp.nova_conta(:'o1', 'gabi@ex.test');
SELECT pg_temp.nova_conta(:'o1', 'ivo@ex.test');
SELECT pg_temp.nova_conta(:'o1', 'c' || i || '@ex.test') FROM generate_series(1, 20) i;
-- O2: mesmo e-mail de ana (nao vinculada) e jose (vinculada); O3: conta de organizacao que sera suspensa
SELECT pg_temp.nova_conta(:'o2', 'ana@ex.test');
SELECT pg_temp.nova_conta(:'o2', 'jose@ex.test');
SELECT pg_temp.nova_conta(:'o3', 'olga@ex.test');

-- identidades (primeiro fator pelo caminho legitimo, H-1), exceto paula (sem fator, para A-9)
SELECT pg_temp.nova_identidade(e) FROM unnest(ARRAY['ana@ex.test', 'xavier@ex.test', 'gabi@ex.test', 'ivo@ex.test', 'jose@ex.test',
    'olga@ex.test']) e;
SELECT pg_temp.nova_identidade('paula@ex.test', false);
SELECT pg_temp.nova_identidade('c' || i || '@ex.test') FROM generate_series(1, 20) i;
SELECT pg_temp.nova_identidade('d' || i || '@ex.test') FROM generate_series(1, 9) i;   -- grupo D (d2 recebe sessao retroativa na f03)

-- sessoes com step-up e vinculos de fixture
SELECT pg_temp.nova_sessao_id(pg_temp.identidade(e), 'sessao/' || e, true)
  FROM unnest(ARRAY['ana@ex.test', 'xavier@ex.test', 'gabi@ex.test', 'ivo@ex.test', 'jose@ex.test', 'olga@ex.test']) e;
-- paula nao tem fator: nenhuma funcao lhe da sessao (R3). A sessao dela vem da f03 (superusuario, declarada) e o vinculo, da f04.
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('c' || i || '@ex.test'), 'sessao/c' || i || '@ex.test', true) FROM generate_series(1, 20) i;

SELECT pg_temp.afirma(pg_temp.vincula(pg_temp.identidade(e), 'sessao/' || e, pg_temp.conta(e, :'o1'), :'o1', 'vinculo/' || e),
                      'fixture: ' || e || ' vinculada em O1')
  FROM unnest(ARRAY['ana@ex.test', 'xavier@ex.test', 'gabi@ex.test']) e;
SELECT pg_temp.afirma(pg_temp.vincula(pg_temp.identidade('jose@ex.test'), 'sessao/jose@ex.test', pg_temp.conta('jose@ex.test', :'o2'), :'o2',
                      'vinculo/jose@ex.test'), 'fixture: jose vinculada em O2');
SELECT pg_temp.afirma(pg_temp.vincula(pg_temp.identidade('olga@ex.test'), 'sessao/olga@ex.test', pg_temp.conta('olga@ex.test', :'o3'), :'o3',
                      'vinculo/olga@ex.test'), 'fixture: olga vinculada em O3');
SELECT 'fixture platform: ok' AS resultado;
\! sh /tmp/e.sh f02
