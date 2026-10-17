-- _fixture.inc.sql - prefixado pelo run.sh (depois de _asserts.inc.sql) a todo script da suite da 017.
-- Funcoes em pg_temp, executadas com o papel da sessao: quando a sessao e nekkora_platform, cada escrita passa pela RLS,
-- pelos privilegios por coluna e pelas triggers reais, como a aplicacao passaria. Nada aqui e objeto da base.
--
-- Tokens: deterministicos a partir de uma semente, no formato do contrato D2 (43 caracteres base64url). So o hash vai
-- para o banco. Os ids fixos de organizacao estao em \set abaixo.
\set o0 '99999999-0000-0000-0000-000000000099'
\set staff '99999999-0000-0000-0000-0000000000aa'
\set o1 '17000000-0000-0000-0000-000000000001'
\set o2 '17000000-0000-0000-0000-000000000002'
\set o3 '17000000-0000-0000-0000-000000000003'

CREATE FUNCTION pg_temp.tok(p TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS
$$ SELECT left(translate(encode(sha256(convert_to(p, 'UTF8')), 'base64'), '+/', '-_'), 42) || 'A' $$;
CREATE FUNCTION pg_temp.h(p TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS
$$ SELECT encode(sha256(convert_to(pg_temp.tok(p), 'UTF8')), 'hex') $$;
CREATE FUNCTION pg_temp.csrf(p TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS
$$ SELECT pg_temp.tok('csrf/' || p) $$;
CREATE FUNCTION pg_temp.codigos(p TEXT, n INTEGER DEFAULT 8) RETURNS TEXT[] LANGUAGE sql IMMUTABLE AS
$$ SELECT array_agg(encode(sha256(convert_to(p || '/' || i, 'UTF8')), 'hex') ORDER BY i) FROM generate_series(1, n) i $$;
CREATE FUNCTION pg_temp.ctx(p_org UUID) RETURNS VOID LANGUAGE sql AS
$$ SELECT set_config('nekkora.organization_id', coalesce(p_org::text, ''), true) $$;
-- R2: contexto de identidade (RLS por identidade). Nivel de SESSAO, de proposito: os scripts encadeiam comandos sobre a
-- mesma identidade, e o grupo P prova o que acontece quando a mesma conexao muda de identidade.
CREATE FUNCTION pg_temp.como(p_identity UUID) RETURNS UUID LANGUAGE sql AS
$$ SELECT set_config('nekkora.identity_id', coalesce(p_identity::text, ''), false); SELECT p_identity $$;

-- conta interna ACTIVE, e-mail verificado, vinculo ACTIVE, credencial e (opcional) fator local confirmado
CREATE FUNCTION pg_temp.nova_conta(p_org UUID, p_email TEXT, p_fator BOOLEAN DEFAULT true) RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE v UUID;
BEGIN
    PERFORM pg_temp.ctx(p_org);
    INSERT INTO users (organization_id, account_type, display_name, email, email_verified_at, status)
    VALUES (p_org, 1, 'Conta ' || p_email, p_email, now(), 2) RETURNING user_id INTO v;
    INSERT INTO organization_memberships (organization_id, user_id, account_type, status) VALUES (p_org, v, 1, 2);
    INSERT INTO user_credentials (user_id, password_hash) VALUES (v, '$argon2id$conta$' || p_email);
    IF p_fator THEN
        INSERT INTO user_mfa_factors (organization_id, user_id, factor_type, secret_ciphertext, secret_nonce, key_id, created_at, confirmed_at)
        VALUES (p_org, v, 1, decode(repeat('ab', 36), 'hex'), decode(repeat('01', 12), 'hex'), 'k1', now(), now());
    END IF;
    RETURN v;
END;
$$;

-- R3: o hash vigente da credencial, como a aplicacao o obtem (identity_resolve_login pelo e-mail; sem SELECT direto)
CREATE FUNCTION pg_temp.hash_vigente(p_identity UUID) RETURNS TEXT LANGUAGE plpgsql AS $$
DECLARE e TEXT;
BEGIN
    PERFORM pg_temp.como(p_identity);
    SELECT email_normalized INTO e FROM identities WHERE identity_id = p_identity;
    RETURN (SELECT password_hash FROM identity_resolve_login(e));
END;
$$;

-- desafio de autenticacao: credential_hash = hash vigente (o que o Argon2 da aplicacao teria conferido); finalidade 1 ou 2
CREATE FUNCTION pg_temp.desafio(p_identity UUID, p_seed TEXT, p_finalidade SMALLINT, p_pendente TEXT DEFAULT NULL) RETURNS TEXT LANGUAGE plpgsql AS $$
DECLARE hv TEXT := pg_temp.hash_vigente(p_identity);
BEGIN
    INSERT INTO identity_auth_challenges (identity_id, token_hash, purpose, credential_hash, pending_password_hash, csrf_token, expires_at)
    VALUES (p_identity, pg_temp.h(p_seed), p_finalidade, hv, p_pendente, pg_temp.csrf(p_seed), now() + interval '4 minutes');
    RETURN pg_temp.tok(p_seed);
END;
$$;
CREATE FUNCTION pg_temp.desafio_cadastro(p_identity UUID, p_seed TEXT) RETURNS TEXT LANGUAGE sql AS
$$ SELECT pg_temp.desafio(p_identity, p_seed, 2::SMALLINT) $$;

-- primeiro fator pelo caminho legitimo (H-1): desafio de cadastro -> pendente -> confirm_first, que tambem cria a sessao
CREATE FUNCTION pg_temp.primeiro_fator(p_identity UUID) RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE t TEXT; f UUID; r RECORD;
BEGIN
    t := pg_temp.desafio_cadastro(p_identity, 'cadastro/' || p_identity);
    f := identity_mfa_enroll_pending(t, NULL, decode(repeat('cd', 36), 'hex'), decode(repeat('02', 12), 'hex'), 'k1');
    SELECT * INTO r FROM identity_mfa_confirm_first(t, f, pg_temp.codigos('codigos/' || p_identity), 1,
        pg_temp.h('primeira/' || p_identity), pg_temp.csrf('primeira/' || p_identity), now() + interval '11 hours');
    IF f IS NULL OR r.o_status IS DISTINCT FROM 'SESSAO_CRIADA' THEN
        RAISE EXCEPTION 'FALHA DE ASSERCAO: primeiro fator nao cadastrado (%)', r.o_status;
    END IF;
    RETURN f;
END;
$$;

-- a aplicacao gera o identity_id e o define como contexto ANTES do INSERT (WITH CHECK da RLS)
CREATE FUNCTION pg_temp.nova_identidade(p_email TEXT, p_fator BOOLEAN DEFAULT true) RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE v UUID := pg_temp.como(gen_random_uuid());
BEGIN
    INSERT INTO identities (identity_id, email, display_name, status) VALUES (v, p_email, 'Pessoa ' || p_email, 1);
    UPDATE identities SET email_verified_at = now(), status = 2 WHERE identity_id = v;
    INSERT INTO identity_credentials (identity_id, password_hash) VALUES (v, '$argon2id$id$' || p_email);
    IF p_fator THEN
        PERFORM pg_temp.primeiro_fator(v);
    END IF;
    RETURN v;
END;
$$;

-- R3: login completo pela funcao (desafio de finalidade 1 + identity_login_complete). TOTP: passo = ultimo + 1.
-- Recuperacao: o primeiro codigo vivo (sessao restrita; T0 revoga as demais sessoes da identidade). Devolve o status.
CREATE FUNCTION pg_temp.login(p_identity UUID, p_seed TEXT, p_recuperacao BOOLEAN DEFAULT false,
                              p_expira INTERVAL DEFAULT interval '11 hours', p_pendente TEXT DEFAULT NULL)
RETURNS RECORD LANGUAGE plpgsql AS $$
DECLARE t TEXT; passo BIGINT; codigo TEXT; r RECORD;
BEGIN
    t := pg_temp.desafio(p_identity, 'login/' || p_seed, 1::SMALLINT, p_pendente);
    IF p_recuperacao THEN
        SELECT code_hash INTO codigo FROM identity_mfa_recovery_codes WHERE identity_id = p_identity AND used_at IS NULL AND invalidated_at IS NULL
         ORDER BY code_hash LIMIT 1;
        SELECT * INTO r FROM identity_login_complete(t, 'recuperacao', NULL, codigo, pg_temp.h(p_seed), pg_temp.csrf(p_seed), now() + p_expira);
    ELSE
        SELECT coalesce(max(last_used_step), 0) + 1 INTO passo FROM identity_mfa_factors
         WHERE identity_id = p_identity AND confirmed_at IS NOT NULL AND disabled_at IS NULL;
        SELECT * INTO r FROM identity_login_complete(t, 'totp', passo, NULL, pg_temp.h(p_seed), pg_temp.csrf(p_seed), now() + p_expira);
    END IF;
    RETURN r;
END;
$$;

-- sessao de identidade pelo login real; token = pg_temp.tok(p_seed); step-up pela aplicacao (UPDATE permitido)
CREATE FUNCTION pg_temp.nova_sessao_id(p_identity UUID, p_seed TEXT, p_step_up BOOLEAN DEFAULT false, p_recuperacao BOOLEAN DEFAULT false,
                                       p_expira INTERVAL DEFAULT interval '11 hours')
RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE r RECORD;
BEGIN
    SELECT * INTO r FROM pg_temp.login(p_identity, p_seed, p_recuperacao, p_expira) AS x(o_status TEXT, o_identity_session_id UUID);
    IF r.o_status IS DISTINCT FROM 'SESSAO_CRIADA' THEN
        RAISE EXCEPTION 'FALHA DE ASSERCAO: login de fixture recusado (%)', r.o_status;
    END IF;
    PERFORM pg_temp.como(p_identity);
    IF p_step_up THEN
        UPDATE identity_sessions SET mfa_step_up_at = now() WHERE identity_session_id = r.o_identity_session_id;
    END IF;
    RETURN r.o_identity_session_id;
END;
$$;

-- vinculacao pelo caminho legitimo: desafio de vinculacao (etapa 1, prova da conta antiga pela aplicacao) + funcao
CREATE FUNCTION pg_temp.desafio_vinculo(p_identity UUID, p_user UUID, p_org UUID, p_seed TEXT) RETURNS TEXT LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_temp.ctx(p_org);
    PERFORM pg_temp.como(p_identity);
    INSERT INTO identity_link_challenges (identity_id, user_id, token_hash, account_credential_hash, expires_at)
    VALUES (p_identity, p_user, pg_temp.h(p_seed), (SELECT password_hash FROM user_credentials WHERE user_id = p_user),
            now() + interval '4 minutes');
    RETURN pg_temp.tok(p_seed);
END;
$$;

CREATE FUNCTION pg_temp.vincula(p_identity UUID, p_seed_sessao TEXT, p_user UUID, p_org UUID, p_seed_link TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    RETURN identity_link_account(pg_temp.tok(p_seed_sessao), pg_temp.desafio_vinculo(p_identity, p_user, p_org, p_seed_link), p_org);
END;
$$;

-- sessao de organizacao (INSERT cru em user_sessions, como SessionRepository.create faria)
CREATE FUNCTION pg_temp.sessao_org(p_org UUID, p_user UUID, p_identity_session UUID, p_seed TEXT) RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE v UUID;
BEGIN
    PERFORM pg_temp.ctx(p_org);
    INSERT INTO user_sessions (organization_id, user_id, session_token_hash, expires_at, mfa_verified_at, csrf_token, identity_session_id)
    VALUES (p_org, p_user, pg_temp.h(p_seed), now() + interval '11 hours', now(), pg_temp.csrf(p_seed), p_identity_session)
    RETURNING session_id INTO v;
    RETURN v;
END;
$$;

CREATE FUNCTION pg_temp.conta(p_email TEXT, p_org UUID) RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE v UUID;
BEGIN
    PERFORM pg_temp.ctx(p_org);
    SELECT user_id INTO v FROM users WHERE email_normalized = lower(p_email) AND organization_id = p_org;
    IF v IS NULL THEN RAISE EXCEPTION 'FALHA DE ASSERCAO: conta % ausente em %', p_email, p_org; END IF;
    RETURN v;
END;
$$;

-- R2: a Platform nao le identities sem contexto; o caminho e a funcao de consulta por chave exata
CREATE FUNCTION pg_temp.identidade(p_email TEXT) RETURNS UUID LANGUAGE sql AS
$$ SELECT identity_id FROM identity_signup_state(lower(p_email)) $$;
-- sessao ativa: pela funcao de consulta; revogada ou expirada: so com o contexto da identidade dona ja definido
CREATE FUNCTION pg_temp.sessao_id(p_seed TEXT) RETURNS UUID LANGUAGE sql AS
$$ SELECT coalesce((SELECT identity_session_id FROM identity_session_lookup(pg_temp.tok(p_seed))),
                   (SELECT identity_session_id FROM identity_sessions WHERE session_token_hash = pg_temp.h(p_seed))) $$;
-- step-up agora numa sessao ativa (a identidade vem da propria sessao)
CREATE FUNCTION pg_temp.step_up(p_seed TEXT) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE r RECORD;
BEGIN
    SELECT * INTO r FROM identity_session_lookup(pg_temp.tok(p_seed));
    PERFORM pg_temp.como(r.identity_id);
    UPDATE identity_sessions SET mfa_step_up_at = now() WHERE identity_session_id = r.identity_session_id;
END;
$$;

-- as sete escritas da entrada local (secao 4.5, camada 2) para uma conta de O1; usadas nos grupos B e C
CREATE FUNCTION pg_temp.escritas(p_user UUID, p_rotulo TEXT) RETURNS TEXT[] LANGUAGE sql AS $$
    SELECT ARRAY[
      format($s$SELECT pg_temp.ctx(%L); INSERT INTO auth_challenges (organization_id, user_id, token_hash, purpose, credential_hash, csrf_token, expires_at) VALUES (%L, %L, %L, 1, 'h', %L, now() + interval '4 minutes')$s$,
             '17000000-0000-0000-0000-000000000001', '17000000-0000-0000-0000-000000000001', p_user, pg_temp.h(p_rotulo || '/B-1'), pg_temp.csrf(p_rotulo)),
      format($s$SELECT pg_temp.ctx(%L); INSERT INTO user_mfa_factors (organization_id, user_id, factor_type, secret_ciphertext, secret_nonce, key_id) VALUES (%L, %L, 1, decode(repeat('ee', 36), 'hex'), decode(repeat('09', 12), 'hex'), 'k1')$s$,
             '17000000-0000-0000-0000-000000000001', '17000000-0000-0000-0000-000000000001', p_user),
      format($s$SELECT pg_temp.ctx(%L); UPDATE user_mfa_factors SET disabled_at = NULL WHERE user_id = %L$s$,
             '17000000-0000-0000-0000-000000000001', p_user),
      format($s$SELECT pg_temp.ctx(%L); INSERT INTO user_mfa_recovery_codes (organization_id, user_id, factor_id, code_hash) SELECT organization_id, user_id, factor_id, %L FROM user_mfa_factors WHERE user_id = %L ORDER BY created_at DESC LIMIT 1$s$,
             '17000000-0000-0000-0000-000000000001', pg_temp.h(p_rotulo || '/B-4'), p_user),
      format($s$SELECT pg_temp.ctx(%L); INSERT INTO password_reset_tokens (user_id, token_hash, expires_at) VALUES (%L, %L, now() + interval '30 minutes')$s$,
             '17000000-0000-0000-0000-000000000001', p_user, pg_temp.h(p_rotulo || '/B-5')),
      format($s$SELECT pg_temp.ctx(%L); INSERT INTO email_verification_tokens (user_id, target_email, token_hash, expires_at) VALUES (%L, 'x@ex.test', %L, now() + interval '1 day')$s$,
             '17000000-0000-0000-0000-000000000001', p_user, pg_temp.h(p_rotulo || '/B-6')),
      format($s$SELECT pg_temp.ctx(%L); UPDATE user_credentials SET password_hash = 'novo', password_changed_at = now() WHERE user_id = %L$s$,
             '17000000-0000-0000-0000-000000000001', p_user)]
$$;

-- ajudantes dos cenarios de concorrencia (grupos C e K)
CREATE FUNCTION pg_temp.executa(p_sql TEXT) RETURNS VOID LANGUAGE plpgsql AS $$ BEGIN EXECUTE p_sql; END $$;
CREATE FUNCTION pg_temp.pendente_de(p_email TEXT) RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE v UUID := pg_temp.como(pg_temp.identidade(p_email));
BEGIN
    RETURN (SELECT factor_id FROM identity_mfa_factors WHERE identity_id = v AND confirmed_at IS NULL AND disabled_at IS NULL);
END;
$$;

-- R3: confirmacao inicial pelo caminho real (passo 1, sessao com o hash de p_seed); devolve o status
CREATE FUNCTION pg_temp.cf(p_tok TEXT, p_factor UUID, p_codigos TEXT[], p_seed TEXT) RETURNS TEXT LANGUAGE sql AS $$
    SELECT o_status FROM identity_mfa_confirm_first(p_tok, p_factor, p_codigos, 1, pg_temp.h(p_seed), pg_temp.csrf(p_seed), now() + interval '1 hour') $$;

-- invariantes E: rodam no fim de CADA cenario, numa sessao separada do superusuario (\! sh /tmp/e.sh <rotulo>),
-- porque nekkora_platform nao le identity_users nem outras organizacoes. Ver e_check.sql e e.sh.
