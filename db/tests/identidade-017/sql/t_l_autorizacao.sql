-- Grupo L - papel: nekkora_platform. Cada funcao publica sem a prova que exige, uma linha por exigencia. Esperado:
-- zero linhas / NULL / false. O runner compara o instantaneo (md5 por tabela de public) antes e depois: SEM efeito.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
CREATE TEMP TABLE l1 AS SELECT pg_temp.conta('l1@ex.test', :'o1') AS conta;
-- identity_resolve_login: so o e-mail normalizado exato; nao lista, nao busca prefixo
SELECT pg_temp.afirma((SELECT count(*) FROM identity_resolve_login(e)) = 0, 'L resolve_login sem resultado: ' || coalesce(e, 'NULL'))
  FROM unnest(ARRAY['naoexiste@ex.test', 'ana', 'ANA@EX.TEST', '%@ex.test', NULL]) e;
SELECT pg_temp.afirma((SELECT count(*) FROM identity_resolve_login('ana@ex.test')) = 1, 'L resolve_login controle positivo');
-- identity_organizations / identity_org_session_target
SELECT pg_temp.afirma((SELECT count(*) FROM identity_organizations(t)) = 0 AND (SELECT count(*) FROM identity_org_session_target(t, :'o1')) = 0,
                      'L sessao sem prova: ' || coalesce(s, 'NULL'))
  FROM (SELECT s, CASE WHEN s IN ('abc', '') THEN s ELSE pg_temp.tok(s) END AS t
          FROM unnest(ARRAY['L/nada', 'abc', '', 'L/revogada', 'sessao/ana-expirada', 'L/rest']) s) x;
SELECT pg_temp.afirma((SELECT count(*) FROM identity_org_session_target(pg_temp.tok('sessao/ana@ex.test'), :'o2')) = 0, 'L alvo: organizacao sem vinculo da identidade');
SELECT pg_temp.afirma((SELECT count(*) FROM identity_org_session_target(pg_temp.tok('L/m'), :'o1')) = 0, 'L alvo: vinculo interno nao ACTIVE');
SELECT pg_temp.afirma((SELECT count(*) FROM identity_org_session_target(pg_temp.tok('sessao/olga@ex.test'), :'o3')) = 0, 'L alvo: organizacao suspensa');
SELECT pg_temp.afirma((SELECT count(*) FROM identity_org_session_target(pg_temp.tok('sessao/ana@ex.test'), :'o1')) = 1, 'L alvo: controle positivo');
-- identity_link_account
SELECT pg_temp.afirma(NOT identity_link_account(pg_temp.tok(s), pg_temp.tok(d), o::uuid), 'L vinculacao recusada: ' || r)
  FROM (VALUES ('L/sem-step', 'L/desafio', :'o1', 'sem step-up'), ('L/step-velho', 'L/desafio', :'o1', 'step-up vencido'),
               ('L/rest', 'L/desafio', :'o1', 'sessao restrita'), ('L/revogada', 'L/desafio', :'o1', 'sessao revogada'),
               ('L/step', 'L/outro-par', :'o1', 'desafio de outro par'), ('L/step', 'vinculo/ana@ex.test', :'o1', 'desafio consumido'),
               ('L/step', 'L/revogado', :'o1', 'desafio revogado'), ('L/step', 'L/expirado', :'o1', 'desafio vencido'),
               ('L/step', 'L/desafio', :'o2', 'organizacao divergente do desafio'), ('L/o3x', 'L/o3x', :'o3', 'organizacao suspensa'),
               ('L/step', 'L/nada', :'o1', 'desafio inexistente')) x(s, d, o, r);
SELECT pg_temp.espera_erro(format('SELECT pg_temp.desafio_vinculo(%L, %L, %L, ''L/email-diferente'')', pg_temp.identidade('l2@ex.test'), (SELECT conta FROM l1), :'o1'),
                           '23514', 'e-mail da conta difere');
-- identity_create_organization
SELECT pg_temp.afirma(identity_create_organization(pg_temp.tok(s), 'Org L', NULL) IS NULL, 'L criacao recusada: ' || s)
  FROM unnest(ARRAY['L/sem-step', 'L/step-velho', 'L/rest', 'L/revogada', 'L/nada', 'sessao/ana-expirada']) s;
-- identity_mfa_enroll_pending
SELECT pg_temp.afirma(identity_mfa_enroll_pending(c, s, decode(repeat('55', 36), 'hex'), decode(repeat('05', 12), 'hex'), 'k1') IS NULL, 'L cadastro de pendente recusado: ' || r)
  FROM (VALUES (pg_temp.tok('L/nada'), NULL, 'desafio inexistente'), (pg_temp.tok('L/cad-consumido'), NULL, 'desafio consumido'),
               (NULL, pg_temp.tok('L/sem-step'), 'sessao sem step-up nem recuperacao'), (NULL, pg_temp.tok('L/step-velho'), 'step-up vencido'),
               (NULL, NULL, 'sem prova'), (pg_temp.tok('L/cad4'), pg_temp.tok('L/step'), 'duas provas ao mesmo tempo')) x(c, s, r);
-- identity_mfa_confirm_first
SELECT pg_temp.como(pg_temp.identidade('l4@ex.test'));
SELECT f AS f_l4 FROM (SELECT factor_id AS f FROM identity_mfa_factors WHERE identity_id = pg_temp.identidade('l4@ex.test') AND disabled_at IS NULL) x \gset
SELECT pg_temp.como(pg_temp.identidade('l3@ex.test'));
SELECT f AS f_l3 FROM (SELECT factor_id AS f FROM identity_mfa_factors WHERE identity_id = pg_temp.identidade('l3@ex.test') AND confirmed_at IS NOT NULL) x \gset
SELECT pg_temp.afirma((SELECT o_status FROM identity_mfa_confirm_first(c, f::uuid, k, 1, pg_temp.h('L/cf'), pg_temp.csrf('L/cf'), now() + interval '1 hour')) <> 'SESSAO_CRIADA', 'L confirmacao do primeiro recusada: ' || r)
  FROM (VALUES (pg_temp.tok('L/cad4'), :'f_l3', pg_temp.codigos('L/a'), 'fator de outra identidade (ja confirmado)'),
               (pg_temp.tok('L/cad-consumido'), :'f_l4', pg_temp.codigos('L/b'), 'desafio consumido'),
               (pg_temp.tok('L/nada'), :'f_l4', pg_temp.codigos('L/c'), 'desafio inexistente'),
               (pg_temp.tok('L/cad4'), :'f_l4', pg_temp.codigos('L/d', 7), 'sete codigos'),
               (pg_temp.tok('L/cad4'), :'f_l4', pg_temp.codigos('L/e', 17), 'dezessete codigos'),
               (pg_temp.tok('L/cad4'), :'f_l4', ARRAY[pg_temp.h('x'), pg_temp.h('x'), pg_temp.h('a'), pg_temp.h('b'), pg_temp.h('c'), pg_temp.h('d'), pg_temp.h('e'), pg_temp.h('f')], 'codigo repetido'),
               (pg_temp.tok('L/cad4'), :'f_l4', ARRAY['nao-hex', pg_temp.h('a'), pg_temp.h('b'), pg_temp.h('c'), pg_temp.h('d'), pg_temp.h('e'), pg_temp.h('f'), pg_temp.h('g')], 'codigo fora do formato')
       ) x(c, f, k, r);
-- identity_mfa_replace (alem do grupo D); contexto de l1 para localizar o proprio fator confirmado
SELECT pg_temp.como(pg_temp.identidade('l1@ex.test'));
SELECT pg_temp.afirma(NOT identity_mfa_replace(pg_temp.tok('L/step'), f::uuid, pg_temp.codigos('L/r')), 'L troca recusada: ' || r)
  FROM (VALUES (:'f_l4', 'fator novo de outra identidade'), (:'f_l3', 'fator novo ja confirmado (de outra identidade)'),
               ((SELECT factor_id::text FROM identity_mfa_factors WHERE identity_id = pg_temp.identidade('l1@ex.test') AND confirmed_at IS NOT NULL), 'fator novo ja confirmado (o proprio)')) x(f, r);
SELECT pg_temp.afirma(NOT identity_mfa_replace(pg_temp.tok('L/step'), :'f_l4', pg_temp.codigos('L/r', 3)), 'L troca recusada: codigos invalidos');
-- R3: identity_login_complete sem a prova que exige (nenhuma destas escreve: so SENHA_OBSOLETA escreve, e ela fica no grupo S)
SELECT pg_temp.como(pg_temp.identidade('l1@ex.test'));
SELECT max(last_used_step) AS passo_l1 FROM identity_mfa_factors WHERE identity_id = pg_temp.identidade('l1@ex.test') AND confirmed_at IS NOT NULL AND disabled_at IS NULL \gset
SELECT pg_temp.afirma((SELECT o_status FROM identity_login_complete(t, m, p::BIGINT, k, pg_temp.h('L/lc'), pg_temp.csrf('L/lc'), now() + interval '1 hour')) = e,
                      'L login_complete recusado: ' || r)
  FROM (VALUES (pg_temp.tok('L/nada'), 'totp', :'passo_l1'::BIGINT + 1, NULL, 'DESAFIO_INVALIDO', 'desafio inexistente'),
               (pg_temp.tok('L/cad4'), 'totp', 99, NULL, 'DESAFIO_INVALIDO', 'finalidade 2 (cadastro) no login'),
               (pg_temp.tok('L/cad-consumido'), 'totp', 99, NULL, 'DESAFIO_INVALIDO', 'desafio consumido'),
               (pg_temp.tok('L/login1'), 'totp', :'passo_l1'::BIGINT, NULL, 'FATOR_RECUSADO', 'passo TOTP repetido'),
               (pg_temp.tok('L/login1'), 'recuperacao', NULL, pg_temp.h('L/codigo-falso'), 'FATOR_RECUSADO', 'codigo de recuperacao inexistente'),
               (pg_temp.tok('L/login1'), 'totp', NULL, NULL, 'PARAMETRO_INVALIDO', 'sem passo'),
               (pg_temp.tok('L/login1'), 'senha', 1, NULL, 'PARAMETRO_INVALIDO', 'metodo invalido')) x(t, m, p, k, e, r);
SELECT pg_temp.afirma((SELECT o_status FROM identity_login_complete(pg_temp.tok('L/login1'), 'totp', 999, NULL, 'nao-hex', pg_temp.csrf('L/lc'), now() + interval '1 hour')) = 'PARAMETRO_INVALIDO'
                  AND (SELECT o_status FROM identity_login_complete(pg_temp.tok('L/login1'), 'totp', 999, NULL, pg_temp.h('L/lc'), pg_temp.csrf('L/lc'), now() + interval '13 hours')) = 'PARAMETRO_INVALIDO',
                      'L login_complete recusado: parametros da sessao');
-- R3: identity_password_change sem a prova que exige
SELECT pg_temp.afirma(NOT identity_password_change(pg_temp.tok(s), e, 'novo-hash'), 'L troca de senha recusada: ' || r)
  FROM (VALUES ('L/sem-step', pg_temp.hash_vigente(pg_temp.identidade('l1@ex.test')), 'sem step-up'),
               ('L/step-velho', pg_temp.hash_vigente(pg_temp.identidade('l1@ex.test')), 'step-up vencido'),
               ('L/rest', pg_temp.hash_vigente(pg_temp.identidade('l1@ex.test')), 'sessao restrita'),
               ('L/revogada', pg_temp.hash_vigente(pg_temp.identidade('l1@ex.test')), 'sessao revogada'),
               ('L/step', 'hash-errado', 'hash esperado divergente'),
               ('L/nada', pg_temp.hash_vigente(pg_temp.identidade('l1@ex.test')), 'sessao inexistente')) x(s, e, r);
SELECT pg_temp.afirma(NOT identity_password_change(pg_temp.tok('L/step'), pg_temp.hash_vigente(pg_temp.identidade('l1@ex.test')), pg_temp.hash_vigente(pg_temp.identidade('l1@ex.test'))),
                      'L troca de senha recusada: senha nova igual a atual');
SELECT 'grupo L: ok' AS resultado;
