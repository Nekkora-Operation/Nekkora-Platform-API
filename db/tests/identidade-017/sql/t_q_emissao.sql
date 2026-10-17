-- Grupo Q (R4) - emissao da sessao de organizacao da conta vinculada: elegibilidade da conta, da organizacao e do vinculo
-- interno conferida NO BANCO no INSERT, depois de mudancas ocorridas DEPOIS da resolucao inicial. Papel: nekkora_platform,
-- SQL direto. Cada passo e um comando/transacao proprio (autocommit), na ordem em que a porta os executaria:
--   resolucao inicial (identity_org_session_target) -> [mudanca confirmada por outra transacao] -> INSERT da sessao.
-- As desativacoes seguem a disciplina da 003 e de AdminService.suspend: organizacao FOR UPDATE antes da escrita.
-- A recusa usa a mensagem uniforme da trigger (23514), a mesma de qualquer outra causa do ramo 2.
\pset pager off
\set o4 '17000000-0000-0000-0000-000000000004'
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');

CREATE FUNCTION pg_temp.q_alvo(p_email TEXT, p_org UUID) RETURNS BOOLEAN LANGUAGE sql AS
$$ SELECT EXISTS (SELECT 1 FROM identity_org_session_target(pg_temp.tok('sessao/' || p_email), p_org)) $$;
CREATE FUNCTION pg_temp.q_emite_sql(p_email TEXT, p_org UUID, p_seed TEXT) RETURNS TEXT LANGUAGE sql AS
$$ SELECT format('SELECT pg_temp.sessao_org(%L, %L, %L, %L)', p_org, pg_temp.conta(p_email, p_org), pg_temp.sessao_id('sessao/' || p_email), p_seed) $$;
CREATE FUNCTION pg_temp.q_emite(p_email TEXT, p_org UUID, p_seed TEXT) RETURNS UUID LANGUAGE sql AS
$$ SELECT pg_temp.sessao_org(p_org, pg_temp.conta(p_email, p_org), pg_temp.sessao_id('sessao/' || p_email), p_seed) $$;
CREATE FUNCTION pg_temp.q_recusa(p_email TEXT, p_org UUID, p_seed TEXT) RETURNS TEXT LANGUAGE sql AS
$$ SELECT pg_temp.espera_erro(pg_temp.q_emite_sql(p_email, p_org, p_seed), '23514', 'fator de MFA confirmado') $$;
-- mudancas de estado, cada uma numa transacao propria, com a organizacao travada antes
CREATE FUNCTION pg_temp.q_trava_org(p_org UUID) RETURNS VOID LANGUAGE sql AS
$$ SELECT pg_temp.ctx(p_org); SELECT 1 FROM organizations WHERE organization_id = p_org FOR UPDATE $$;

-- Q-0: controle positivo - vinculada, elegivel: a resolucao encontra o alvo e a emissao e aceita
SELECT pg_temp.afirma(pg_temp.q_alvo('qv1@ex.test', :'o1'), 'Q-0 resolucao inicial encontra o alvo');
SELECT pg_temp.afirma(pg_temp.q_emite('qv1@ex.test', :'o1', 'Q-0') IS NOT NULL, 'Q-0 emissao aceita');
\! sh /tmp/e.sh Q-0

-- Q-1: conta SUSPENSA depois da resolucao -> recusada; reativada -> aceita
SELECT pg_temp.afirma(pg_temp.q_alvo('qv2@ex.test', :'o1'), 'Q-1 resolucao inicial encontra o alvo');
BEGIN; SELECT pg_temp.q_trava_org(:'o1');
UPDATE users SET status = 3 WHERE user_id = pg_temp.conta('qv2@ex.test', :'o1');
COMMIT;
SELECT pg_temp.q_recusa('qv2@ex.test', :'o1', 'Q-1');
BEGIN; SELECT pg_temp.q_trava_org(:'o1');
UPDATE users SET status = 2 WHERE user_id = pg_temp.conta('qv2@ex.test', :'o1');
COMMIT;
SELECT pg_temp.afirma(pg_temp.q_emite('qv2@ex.test', :'o1', 'Q-1/reativada') IS NOT NULL, 'Q-1 controle: reativada, emissao aceita');
\! sh /tmp/e.sh Q-1

-- Q-2: e-mail da conta deixa de estar verificado depois da resolucao -> recusada; verificado de novo -> aceita
SELECT pg_temp.afirma(pg_temp.q_alvo('qv3@ex.test', :'o1'), 'Q-2 resolucao inicial encontra o alvo');
BEGIN; SELECT pg_temp.q_trava_org(:'o1');
UPDATE users SET email_verified_at = NULL WHERE user_id = pg_temp.conta('qv3@ex.test', :'o1');
COMMIT;
SELECT pg_temp.q_recusa('qv3@ex.test', :'o1', 'Q-2');
BEGIN; SELECT pg_temp.q_trava_org(:'o1');
UPDATE users SET email_verified_at = now() WHERE user_id = pg_temp.conta('qv3@ex.test', :'o1');
COMMIT;
SELECT pg_temp.afirma(pg_temp.q_emite('qv3@ex.test', :'o1', 'Q-2/verificada') IS NOT NULL, 'Q-2 controle: verificada, emissao aceita');
\! sh /tmp/e.sh Q-2

-- Q-3: vinculo interno (organization_memberships) desativado depois da resolucao -> recusada; reativado -> aceita
SELECT pg_temp.afirma(pg_temp.q_alvo('qv4@ex.test', :'o1'), 'Q-3 resolucao inicial encontra o alvo');
BEGIN; SELECT pg_temp.q_trava_org(:'o1');
UPDATE organization_memberships SET status = 3 WHERE user_id = pg_temp.conta('qv4@ex.test', :'o1');
COMMIT;
SELECT pg_temp.q_recusa('qv4@ex.test', :'o1', 'Q-3');
BEGIN; SELECT pg_temp.q_trava_org(:'o1');
UPDATE organization_memberships SET status = 2 WHERE user_id = pg_temp.conta('qv4@ex.test', :'o1');
COMMIT;
SELECT pg_temp.afirma(pg_temp.q_emite('qv4@ex.test', :'o1', 'Q-3/reativado') IS NOT NULL, 'Q-3 controle: vinculo reativado, emissao aceita');
\! sh /tmp/e.sh Q-3

-- Q-4: ORGANIZACAO desativada depois da resolucao (O4) -> recusada; reativada -> aceita
SELECT pg_temp.afirma(pg_temp.q_alvo('qo1@ex.test', :'o4'), 'Q-4 resolucao inicial encontra o alvo');
BEGIN; SELECT pg_temp.ctx(:'o4');
UPDATE organizations SET status = 2 WHERE organization_id = :'o4';
COMMIT;
SELECT pg_temp.q_recusa('qo1@ex.test', :'o4', 'Q-4');
BEGIN; SELECT pg_temp.ctx(:'o4');
UPDATE organizations SET status = 1 WHERE organization_id = :'o4';
COMMIT;
SELECT pg_temp.afirma(pg_temp.q_emite('qo1@ex.test', :'o4', 'Q-4/reativada') IS NOT NULL, 'Q-4 controle: organizacao reativada, emissao aceita');
\! sh /tmp/e.sh Q-4

-- Q-5: RAMO 1 INALTERADO - conta NAO vinculada com fator local: a R4 nao acrescenta condicao. Com a conta suspensa, o
-- vinculo interno inativo ou a organizacao inativa, o INSERT continua aceito pelo banco, como antes da R4 (a elegibilidade
-- dessas contas continua sendo conferida pela aplicacao, AuthService/AccountRepository).
BEGIN; SELECT pg_temp.q_trava_org(:'o1');
UPDATE users SET status = 3 WHERE user_id = pg_temp.conta('qn1@ex.test', :'o1');
COMMIT;
SELECT pg_temp.afirma(pg_temp.sessao_org(:'o1', pg_temp.conta('qn1@ex.test', :'o1'), NULL, 'Q-5/suspensa') IS NOT NULL, 'Q-5 ramo 1: conta suspensa, INSERT aceito como antes');
BEGIN; SELECT pg_temp.q_trava_org(:'o1');
UPDATE users SET status = 2 WHERE user_id = pg_temp.conta('qn1@ex.test', :'o1');
UPDATE organization_memberships SET status = 3 WHERE user_id = pg_temp.conta('qn1@ex.test', :'o1');
COMMIT;
SELECT pg_temp.afirma(pg_temp.sessao_org(:'o1', pg_temp.conta('qn1@ex.test', :'o1'), NULL, 'Q-5/vinculo') IS NOT NULL, 'Q-5 ramo 1: vinculo inativo, INSERT aceito como antes');
BEGIN; SELECT pg_temp.q_trava_org(:'o1');
UPDATE organization_memberships SET status = 2 WHERE user_id = pg_temp.conta('qn1@ex.test', :'o1');
COMMIT;
BEGIN; SELECT pg_temp.ctx(:'o4'); UPDATE organizations SET status = 2 WHERE organization_id = :'o4'; COMMIT;
SELECT pg_temp.afirma(pg_temp.sessao_org(:'o4', pg_temp.conta('qn4@ex.test', :'o4'), NULL, 'Q-5/org') IS NOT NULL, 'Q-5 ramo 1: organizacao inativa, INSERT aceito como antes');
BEGIN; SELECT pg_temp.ctx(:'o4'); UPDATE organizations SET status = 1 WHERE organization_id = :'o4'; COMMIT;
-- e continua recusando o que ja recusava: sem fator local (A-2) e com identity_session_id preenchido (A-3) seguem no grupo A
\! sh /tmp/e.sh Q-5
SELECT 'grupo Q, sequencial: ok' AS resultado;
