-- Grupo I (DC-17.5) - papel: nekkora_platform, SQL direto. A criacao nao anexa conta existente.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
CREATE TEMP TABLE v AS SELECT pg_temp.conta('ivo@ex.test', :'o1') AS ivo_antiga, pg_temp.identidade('ivo@ex.test') AS idi;
SELECT pg_temp.step_up('sessao/ivo@ex.test');

-- I-1 (pedido pela responsavel): na MESMA transacao, a conta antiga de mesmo e-mail ganha versao nova (xmin desta
-- transacao); nenhum caminho a anexa sem a prova dupla
BEGIN;
SELECT pg_temp.ctx(:'o1');
UPDATE users SET display_name = display_name WHERE user_id = (SELECT ivo_antiga FROM v);
SELECT pg_temp.afirma((SELECT xmin::text::bigint FROM users WHERE user_id = (SELECT ivo_antiga FROM v)) = txid_current() % (2^32)::bigint,
                      'I-1 conta antiga com xmin desta transacao');
SELECT pg_temp.espera_erro(format('INSERT INTO identity_users (identity_id, user_id, link_channel) VALUES (%L, %L, ''cadastro_web'')',
    (SELECT idi FROM v), (SELECT ivo_antiga FROM v)), '42501', 'permission denied');
SELECT pg_temp.afirma(NOT identity_link_account(pg_temp.tok('sessao/ivo@ex.test'), pg_temp.tok('I-1/sem-desafio'), :'o1'), 'I-1 vinculacao sem prova recusada');
SELECT identity_create_organization(pg_temp.tok('sessao/ivo@ex.test'), 'Org do Ivo', NULL) AS org_ivo \gset
SELECT pg_temp.afirma(:'org_ivo' IS NOT NULL AND :'org_ivo'::uuid <> :'o1'::uuid, 'I-1 criacao cria organizacao NOVA');
SELECT pg_temp.afirma(NOT identity_account_is_linked(:'o1', (SELECT ivo_antiga FROM v)), 'I-1 conta antiga continua NAO vinculada');
COMMIT;
SELECT pg_temp.afirma(ARRAY(SELECT organization_id FROM identity_organizations(pg_temp.tok('sessao/ivo@ex.test')))::text = ARRAY[:'org_ivo'::uuid]::text,
                      'I-1 a identidade alcanca so a organizacao nova');
\! sh /tmp/e.sh I-1
-- I-2: exatamente uma conta, um vinculo interno, um ORG_ADMIN; a conta e a criada, de nenhuma outra organizacao
BEGIN;
SELECT pg_temp.ctx(:'org_ivo');
SELECT pg_temp.afirma((SELECT count(*) FROM users) = 1 AND (SELECT count(*) FROM organization_memberships WHERE status = 2) = 1
    AND (SELECT count(*) FROM organization_role_assignments a JOIN roles r ON r.role_id = a.role_id WHERE r.code = 'ORG_ADMIN' AND a.status = 1 AND a.assigned_by_membership_id IS NULL) = 1
    AND (SELECT count(*) FROM organization_resource_limit_revisions) = 5
    AND (SELECT user_id FROM users) = (SELECT user_id FROM identity_org_session_target(pg_temp.tok('sessao/ivo@ex.test'), :'org_ivo'))
    AND (SELECT user_id FROM users) <> (SELECT ivo_antiga FROM v)
    AND (SELECT email_verified_at IS NOT NULL AND status = 2 FROM users), 'I-2 conta criada e vinculada, ORG_ADMIN, limites');
-- I-3: a conta criada nasce sob a DC-17.9: sem credencial e sem fator local
SELECT pg_temp.espera_erro(format('INSERT INTO user_credentials (user_id, password_hash) VALUES (%L, ''h'')', (SELECT user_id FROM users)), '23514', 'conta vinculada');
SELECT pg_temp.espera_erro(format('INSERT INTO user_mfa_factors (organization_id, user_id, factor_type, secret_ciphertext, secret_nonce, key_id) VALUES (%L, %L, 1, decode(repeat(''ee'', 36), ''hex''), decode(repeat(''09'', 12), ''hex''), ''k1'')',
    :'org_ivo', (SELECT user_id FROM users)), '23514', 'conta vinculada');
SELECT pg_temp.espera_erro(format('INSERT INTO auth_challenges (organization_id, user_id, token_hash, purpose, credential_hash, csrf_token, expires_at) VALUES (%L, %L, %L, 1, ''h'', %L, now() + interval ''4 minutes'')',
    :'org_ivo', (SELECT user_id FROM users), pg_temp.h('I-3'), pg_temp.csrf('I-3')), '23514', 'conta vinculada');
COMMIT;
\! sh /tmp/e.sh I-3
-- I-4: sem step-up; identidade nao ACTIVE (PENDING, sem e-mail verificado) -> zero efeito
SELECT pg_temp.nova_sessao_id((SELECT idi FROM v), 'I-4/sem-step-up');
SELECT pg_temp.afirma(identity_create_organization(pg_temp.tok('I-4/sem-step-up'), 'Org I-4', NULL) IS NULL, 'I-4 sem step-up recusado');
SELECT pg_temp.como(gen_random_uuid()) AS pend \gset
INSERT INTO identities (identity_id, email, display_name, status) VALUES (:'pend', 'pendente@ex.test', 'Pendente', 1);
INSERT INTO identity_credentials (identity_id, password_hash) VALUES (:'pend', '$argon2id$id$pendente');
-- R3: a sessao so nasce pelo login, que recusa identidade PENDING; logo ela nem chega a criar organizacao
SELECT pg_temp.afirma((SELECT o_status FROM pg_temp.login(:'pend', 'I-4/pendente') AS x(o_status TEXT, o_identity_session_id UUID)) = 'DESAFIO_INVALIDO',
                      'I-4 identidade PENDING nao obtem sessao');
SELECT pg_temp.afirma((SELECT count(*) FROM identity_organizations(pg_temp.tok('sessao/ivo@ex.test'))) = 1, 'I-4 sem efeito');
\! sh /tmp/e.sh I-4
-- I-5: teto de 10 por identidade, conferido DENTRO da funcao (independente do Redis)
SELECT pg_temp.step_up('sessao/c19@ex.test');
SELECT pg_temp.afirma(identity_create_organization(pg_temp.tok('sessao/c19@ex.test'), 'Org c19 #' || i, NULL) IS NOT NULL, 'I-5 criacao ' || i)
  FROM generate_series(1, 10) i;
SELECT pg_temp.afirma(identity_create_organization(pg_temp.tok('sessao/c19@ex.test'), 'Org c19 #11', NULL) IS NULL, 'I-5 11a criacao recusada');
SELECT pg_temp.afirma((SELECT count(*) FROM identity_organizations(pg_temp.tok('sessao/c19@ex.test'))) = 10, 'I-5 dez organizacoes');
\! sh /tmp/e.sh I-5
-- I-6: a assinatura antiga nao existe
SELECT pg_temp.afirma(to_regprocedure('identity_attach_created_account(text,uuid,uuid)') IS NULL
                  AND NOT EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'identity_attach_created_account'), 'I-6 identity_attach_created_account ausente');
SELECT 'grupo I: ok' AS resultado;
