-- Semeadura COMPLEMENTAR da validacao administrativa (F6a-V-T) - papel: admin_cluster (o administrador do cluster DO
-- ENSAIO, por socket local, como a semeadura da F1.5). Recebe do orquestrador (lib/semear_f6a.py) a variavel psql
-- plano_b64 = plano JSON em base64 (contas f6av-* com PHC Argon2id, concessoes com grant_id, referencias da base).
-- Nenhuma senha em claro chega aqui. ADITIVA: so INSERT; nenhuma linha existente e alterada.
--
-- UMA transacao (BEGIN ... COMMIT explicitos, psql -v ON_ERROR_STOP=1):
--   * recusa logo no inicio se ja existir qualquer conta f6av-* (pelo e-mail), qualquer user_id ou grant_id do plano,
--     ou se as referencias da base (organizacao A, cliente davila, workspaces, responsavel) nao estiverem como o plano
--     diz (organizacao, cliente e workspaces ATIVOS; workspaces do cliente; responsavel ORG_ADMIN ativo);
--   * grava usuarios ativos e verificados, credenciais (PHC do plano), vinculos (organizacao ou cliente), papel VIEWER
--     no sandbox (internas, atribuido pelo responsavel) e as concessoes ATIVAS da conta externa (aprovadas pelo
--     responsavel), com o grant_id do plano;
--   * confere as contagens esperadas do plano ANTES do COMMIT: qualquer diferenca = excecao = ROLLBACK;
--   * depois do COMMIT, imprime SEMEADURA_F6A_APLICADA e linhas CONFERE (so leitura).
\set ON_ERROR_STOP on
BEGIN;
SELECT set_config('nekkora_semeadura.plano_f6a', convert_from(decode(:'plano_b64', 'base64'), 'UTF8'), true) IS NOT NULL AS plano_ok \gset

CREATE FUNCTION pg_temp.plano() RETURNS jsonb LANGUAGE sql STABLE AS $$ SELECT current_setting('nekkora_semeadura.plano_f6a')::jsonb $$;
CREATE FUNCTION pg_temp.ref(caminho text[]) RETURNS uuid LANGUAGE sql STABLE AS $$ SELECT (pg_temp.plano()->'refs' #>> caminho)::uuid $$;
CREATE FUNCTION pg_temp.conta(chave text) RETURNS jsonb LANGUAGE sql STABLE AS $$
    SELECT c FROM jsonb_array_elements(pg_temp.plano()->'contas') c WHERE c->>'chave' = chave $$;

DO $$
DECLARE
    p jsonb := pg_temp.plano();
    org_id uuid := pg_temp.ref('{org_a}');
    resp_m uuid := pg_temp.ref('{responsavel_membership}');
    cli_id uuid := pg_temp.ref('{clientes,davila}');
    c jsonb; g jsonb; x jsonb; n int; ws_id uuid; conta jsonb;
BEGIN
    -- recusas (nada foi gravado ate aqui)
    IF EXISTS (SELECT 1 FROM users WHERE email LIKE 'f6av-%@demo.nekkora.test') THEN
        RAISE EXCEPTION 'SEMEADURA_F6A_RECUSADA: ja existe conta f6av-* (nao se repete; nada foi gravado)';
    END IF;
    IF EXISTS (SELECT 1 FROM users u JOIN jsonb_array_elements(p->'contas') pc ON u.user_id = (pc->>'user_id')::uuid)
       OR EXISTS (SELECT 1 FROM client_workspace_grants w JOIN jsonb_array_elements(p->'concessoes') pg ON w.grant_id = (pg->>'grant_id')::uuid) THEN
        RAISE EXCEPTION 'SEMEADURA_F6A_RECUSADA: id do plano ja existe no banco (nada foi gravado)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM organizations WHERE organization_id = org_id AND name = 'Nekkora Demo' AND status = 1) THEN
        RAISE EXCEPTION 'SEMEADURA_F6A_RECUSADA: organizacao A da base ausente ou inativa';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM clients WHERE client_id = cli_id AND organization_id = org_id AND status = 1) THEN
        RAISE EXCEPTION 'SEMEADURA_F6A_RECUSADA: cliente davila da base ausente ou inativo';
    END IF;
    SELECT count(*) INTO n FROM workspaces WHERE organization_id = org_id AND status = 1 AND (
        (workspace_id = pg_temp.ref('{workspaces,sandbox}') AND client_id IS NULL)
        OR (workspace_id IN (pg_temp.ref('{workspaces,exp-astral}'), pg_temp.ref('{workspaces,exp-bmp}')) AND client_id = cli_id));
    IF n <> 3 THEN RAISE EXCEPTION 'SEMEADURA_F6A_RECUSADA: workspaces da base fora do esperado (% de 3)', n; END IF;
    IF NOT EXISTS (SELECT 1 FROM organization_memberships m JOIN organization_role_assignments r USING (organization_id, membership_id)
                    JOIN roles ro USING (role_id)
                    WHERE m.membership_id = resp_m AND m.organization_id = org_id AND m.status = 2 AND r.status = 1 AND ro.code = 'ORG_ADMIN') THEN
        RAISE EXCEPTION 'SEMEADURA_F6A_RECUSADA: responsavel da base nao e ORG_ADMIN ativo';
    END IF;

    -- usuarios, credenciais e vinculos
    FOR c IN SELECT * FROM jsonb_array_elements(p->'contas') LOOP
        IF c->>'email' NOT LIKE 'f6av-%@demo.nekkora.test' THEN RAISE EXCEPTION 'conta fora do prefixo f6av-: %', c->>'chave'; END IF;
        INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name)
            VALUES ((c->>'user_id')::uuid, org_id, CASE c->>'tipo' WHEN 'interna' THEN 1 ELSE 2 END, c->>'email', now(), 2, c->>'nome');
        INSERT INTO user_credentials (user_id, password_hash) VALUES ((c->>'user_id')::uuid, c->>'phc');
        IF c->>'tipo' = 'interna' THEN
            INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status)
                VALUES ((c->>'membership_id')::uuid, org_id, (c->>'user_id')::uuid, 1, 2);
            FOR x IN SELECT * FROM jsonb_array_elements(coalesce(c->'papeis'->'ws', '[]'::jsonb)) LOOP
                INSERT INTO workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status, assigned_by_membership_id)
                    SELECT org_id, pg_temp.ref(ARRAY['workspaces', x->>0]), (c->>'membership_id')::uuid, role_id, 2, 1, resp_m
                      FROM roles WHERE code = x->>1;
                GET DIAGNOSTICS n = ROW_COUNT;
                IF n <> 1 THEN RAISE EXCEPTION 'papel de workspace desconhecido: %', x->>1; END IF;
            END LOOP;
        ELSE
            IF c->'papeis'->>'cliente' <> 'davila' THEN RAISE EXCEPTION 'cliente fora do catalogo: %', c->'papeis'->>'cliente'; END IF;
            INSERT INTO client_memberships (client_membership_id, organization_id, client_id, user_id, account_type, status)
                VALUES ((c->>'membership_id')::uuid, org_id, cli_id, (c->>'user_id')::uuid, 2, 2);
        END IF;
    END LOOP;

    -- concessoes ATIVAS da conta externa (aprovadas pelo responsavel), com o grant_id do plano
    FOR g IN SELECT * FROM jsonb_array_elements(p->'concessoes') LOOP
        conta := pg_temp.conta(g->>'conta');
        ws_id := pg_temp.ref(ARRAY['workspaces', g->>'workspace']);
        INSERT INTO client_workspace_grants (grant_id, organization_id, client_id, client_membership_id, workspace_id, status,
               approved_by_membership_id, approved_at, starts_at, next_review_at)
        VALUES ((g->>'grant_id')::uuid, org_id, cli_id, (conta->>'membership_id')::uuid, ws_id, 2, resp_m,
                now() - interval '1 day', now() - interval '1 day', now() + interval '60 days');
    END LOOP;

    -- CONFERENCIA antes do COMMIT: qualquer diferenca do esperado desfaz tudo
    SELECT count(*) INTO n FROM users WHERE email LIKE 'f6av-%@demo.nekkora.test' AND organization_id = org_id AND status = 2;
    IF n <> (p->'esperado'->>'usuarios')::int THEN RAISE EXCEPTION 'conferencia: % usuarios f6av-*', n; END IF;
    SELECT count(*) INTO n FROM organization_memberships m JOIN users u USING (organization_id, user_id)
     WHERE u.email LIKE 'f6av-%@demo.nekkora.test' AND m.status = 2;
    IF n <> (p->'esperado'->>'vinculos_org')::int THEN RAISE EXCEPTION 'conferencia: % vinculos de organizacao', n; END IF;
    SELECT count(*) INTO n FROM client_memberships m JOIN users u USING (organization_id, user_id)
     WHERE u.email LIKE 'f6av-%@demo.nekkora.test' AND m.status = 2 AND m.client_id = cli_id;
    IF n <> (p->'esperado'->>'vinculos_cliente')::int THEN RAISE EXCEPTION 'conferencia: % vinculos de cliente', n; END IF;
    SELECT count(*) INTO n FROM workspace_role_assignments r JOIN organization_memberships m USING (organization_id, membership_id)
      JOIN users u USING (organization_id, user_id) WHERE u.email LIKE 'f6av-%@demo.nekkora.test' AND r.status = 1;
    IF n <> (p->'esperado'->>'papeis_ws')::int THEN RAISE EXCEPTION 'conferencia: % papeis de workspace', n; END IF;
    SELECT count(*) INTO n FROM client_workspace_grants w JOIN jsonb_array_elements(p->'concessoes') pg ON w.grant_id = (pg->>'grant_id')::uuid
     WHERE w.status = 2 AND w.revoked_at IS NULL;
    IF n <> (p->'esperado'->>'concessoes_ativas')::int THEN RAISE EXCEPTION 'conferencia: % concessoes ativas', n; END IF;
END $$;

COMMIT;
SELECT 'SEMEADURA_F6A_APLICADA ' || (SELECT count(*) FROM users WHERE email LIKE 'f6av-%@demo.nekkora.test');
SELECT 'CONFERE contas_f6av=' || string_agg(email || ':' || status, ',' ORDER BY email) FROM users WHERE email LIKE 'f6av-%@demo.nekkora.test';
SELECT 'CONFERE concessoes_f6av=' || string_agg(w.status::text, ',' ORDER BY w.created_at, w.grant_id)
  FROM client_workspace_grants w JOIN client_memberships m USING (organization_id, client_membership_id)
  JOIN users u USING (organization_id, user_id) WHERE u.email LIKE 'f6av-%@demo.nekkora.test';
