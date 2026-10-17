-- Grupo L, preparacao - papel: nekkora_platform. Estado com cada prova possivel, ANTES do instantaneo.
\pset pager off
SELECT pg_temp.nova_conta(:'o1', 'l1@ex.test');
SELECT pg_temp.nova_conta(:'o1', 'm@ex.test');
SELECT pg_temp.nova_conta(:'o3', 'o3x@ex.test');
SELECT pg_temp.nova_identidade(e) FROM unnest(ARRAY['l1@ex.test', 'l2@ex.test', 'l3@ex.test', 'm@ex.test', 'o3x@ex.test']) e;
SELECT pg_temp.nova_identidade('l4@ex.test', false);
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('l1@ex.test'), 'L/rest', true, true);   -- R3: primeiro (T0 revoga as anteriores)
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('l1@ex.test'), 'L/sem-step');
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('l1@ex.test'), 'L/step-velho');
UPDATE identity_sessions SET mfa_step_up_at = now() - interval '10 minutes' WHERE identity_session_id = pg_temp.sessao_id('L/step-velho');
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('l1@ex.test'), 'L/step', true);
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('l1@ex.test'), 'L/revogada', true);
UPDATE identity_sessions SET revoked_at = now() WHERE identity_session_id = pg_temp.sessao_id('L/revogada');
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('m@ex.test'), 'L/m', true);
SELECT pg_temp.nova_sessao_id(pg_temp.identidade('o3x@ex.test'), 'L/o3x', true);
SELECT pg_temp.afirma(pg_temp.vincula(pg_temp.identidade('m@ex.test'), 'L/m', pg_temp.conta('m@ex.test', :'o1'), :'o1', 'L/vinculo-m'), 'L prep: m vinculada');
BEGIN;
SELECT pg_temp.ctx(:'o1');
UPDATE organization_memberships SET status = 3 WHERE user_id = pg_temp.conta('m@ex.test', :'o1');
COMMIT;
SELECT pg_temp.desafio_vinculo(pg_temp.identidade('l1@ex.test'), pg_temp.conta('l1@ex.test', :'o1'), :'o1', 'L/desafio');
SELECT pg_temp.desafio_vinculo(pg_temp.identidade('c18@ex.test'), pg_temp.conta('c18@ex.test', :'o1'), :'o1', 'L/outro-par');
SELECT pg_temp.desafio_vinculo(pg_temp.identidade('l1@ex.test'), pg_temp.conta('l1@ex.test', :'o1'), :'o1', 'L/revogado');
UPDATE identity_link_challenges SET revoked_at = now(), account_credential_hash = NULL WHERE token_hash = pg_temp.h('L/revogado');
SELECT pg_temp.desafio_vinculo(pg_temp.identidade('o3x@ex.test'), pg_temp.conta('o3x@ex.test', :'o3'), :'o3', 'L/o3x');
SELECT pg_temp.desafio_cadastro(pg_temp.identidade('l4@ex.test'), 'L/cad4');
SELECT identity_mfa_enroll_pending(pg_temp.tok('L/cad4'), NULL, decode(repeat('44', 36), 'hex'), decode(repeat('04', 12), 'hex'), 'k1');
SELECT pg_temp.desafio_cadastro(pg_temp.identidade('l4@ex.test'), 'L/cad-consumido');
-- (R3) o consumo deste desafio e feito na preparacao de superusuario: a Platform nao consome desafio diretamente
SELECT pg_temp.desafio(pg_temp.identidade('l1@ex.test'), 'L/login1', 1::SMALLINT);   -- recusas de identity_login_complete
SELECT 'grupo L preparacao: ok' AS resultado;
