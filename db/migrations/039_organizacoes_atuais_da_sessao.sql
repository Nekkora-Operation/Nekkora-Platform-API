-- Nekkora Platform - organizacoes ATUAIS da pessoa para o aplicativo desktop (AGENT-CLIENT-NAVEGACAO-002, T01). Contrato:
-- docs/agent-client-navegacao-002/t01-organizacoes-atuais.md e api/openapi/nekkora-api-v1.yaml (/api/v1/desktop/organizations...).
--
-- CANDIDATA ate a aprovacao do PR. Numero 039 conferido em 14/11 contra a dev (12d320c; a maior era 038) e os PRs abertos
-- (nenhum na API). As ferramentas do laboratorio e da homologacao exigem numeracao contigua: se outra migration ocupar 039
-- antes, esta e renumerada sem mudar o conteudo.
--
-- PROBLEMA: a sessao do aplicativo desktop (037/038) so conhece a identidade e o ID da sessao de identidade que a autorizou.
-- As funcoes que resolvem "identidade -> contas e organizacoes" (identity_organizations, identity_org_session_target, 017)
-- exigem o TOKEN da sessao do navegador, que so esta presente na aprovacao e nunca e guardado. A Platform nao tem (e continua
-- sem ter) privilegio algum em identity_users. Por isso a lista de organizacoes do aplicativo era um retrato da aprovacao:
-- organizacao em que a pessoa entrou depois do login so aparecia no login seguinte.
--
-- O QUE FAZ: cria UMA funcao, public.identity_session_organizations(sessao de identidade, organizacao ou NULL, limite), do
-- dono nekkora_identity_owner, SECURITY DEFINER, EXECUTE so para nekkora_platform. Ela devolve, para a identidade que JA
-- esta no contexto da requisicao (nekkora.identity_id), os pares (organizacao, conta) elegiveis AGORA e o nome da organizacao,
-- em ordem de nome e id, SE a sessao informada for dessa identidade e estiver ativa e nao restrita - exatamente a regra do
-- ramo 2 de identity_org_session_allowed (029) e a elegibilidade de identity_organizations (017): conta interna ativa e
-- verificada, organizacao ativa, vinculo ativo.
--   entrada: id da sessao de identidade (nao e segredo de portador: sem o contexto da identidade dona nada volta);
--            organizacao (NULL = todas); limite (1..201).
--   saida:   organization_id, user_id, name. Nenhum papel, e-mail, token, hash, fator ou dado de outra identidade.
--   sem contexto de identidade, com contexto de OUTRA identidade, sessao inexistente, encerrada, vencida ou restrita: nenhuma linha.
--   so leitura: nenhum lock, nenhuma escrita, nenhum evento.
--
-- O QUE NAO FAZ: nenhuma tabela, coluna, papel, politica ou privilegio de tabela novo; nenhuma funcao existente e alterada;
-- a Platform continua sem privilegio em identity_users; nenhuma sessao, fator ou vinculo muda (conferido na secao 4).
-- Organizacao aguardando ativacao continua como esta: aparece na lista (como na Web) e nada e ativado.
--
-- ELEVACAO: cria funcao do dono nekkora_identity_owner (como a 019, a 020, a 023, a 024 e a 029): janela de SUPERUSER do
-- migrador (db/bootstrap/aplicar_como_migrador.sh, padrao com 039). Sem a janela: "must be able to SET ROLE
-- nekkora_identity_owner" (suite db/tests/organizacoes-da-sessao-039, cluster B). A autorizacao da janela em homologacao e
-- producao (ops/homologacao/implantacao/elevacao.txt) NAO faz parte desta migration nem deste PR.
--
-- RETORNO: DROP FUNCTION public.identity_session_organizations(uuid, uuid, integer) (pelo dono ou em janela de elevacao),
-- depois de voltar a API para a revisao anterior. Nenhum dado depende da funcao.

BEGIN;

-- === 1. Pre-condicao: 038 aplicada; funcao nova inexistente; retrato de sessoes e vinculos ===
DO $$
BEGIN
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.automation_admin_events'::regclass AND c.conname = 'automation_admin_events_act_check')
       IS DISTINCT FROM 'CHECK (((act >= 1) AND (act <= 8)))' THEN
        RAISE EXCEPTION '039 exige a 038 aplicada';
    END IF;
    IF to_regprocedure('public.identity_org_session_allowed(uuid, uuid, uuid)') IS NULL
       OR to_regprocedure('public.identity_never_had_factor(uuid)') IS NULL THEN
        RAISE EXCEPTION '039 exige as funcoes da 017 e da 029';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'identity_session_organizations') THEN
        RAISE EXCEPTION '039: identity_session_organizations ja existe';
    END IF;
END;
$$;

CREATE TEMP TABLE n039_antes ON COMMIT DROP AS
SELECT (SELECT count(*) FROM public.identity_sessions WHERE revoked_at IS NULL) AS sessoes,
       (SELECT count(*) FROM public.identity_users) AS vinculos,
       (SELECT count(*) FROM public.organization_memberships) AS memberships;

-- === 2. Funcao ===
CREATE FUNCTION public.identity_session_organizations(p_identity_session_id UUID, p_organization_id UUID, p_limit INTEGER)
RETURNS TABLE (organization_id UUID, user_id UUID, name TEXT)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
#variable_conflict use_column
DECLARE
    v_prev_org TEXT := pg_catalog.current_setting('nekkora.fn_organization_id', true);
    v_prev_id TEXT := pg_catalog.current_setting('nekkora.fn_identity_id', true);
    v_ctx TEXT := pg_catalog.current_setting('nekkora.identity_id', true);
    v_identity UUID;
BEGIN
    IF p_limit IS NULL OR p_limit < 1 OR p_limit > 201 THEN
        RAISE EXCEPTION 'limite invalido' USING ERRCODE = '22023';
    END IF;
    -- sem o contexto da identidade (o da requisicao, definido pela aplicacao depois de autenticar a sessao do aplicativo)
    -- ou sem sessao: nada. O id da sessao sozinho nao abre nada.
    IF p_identity_session_id IS NULL OR v_ctx IS NULL OR v_ctx = '' THEN
        RETURN;
    END IF;
    BEGIN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', '', true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', v_ctx, true);
        -- a sessao e DESTA identidade, ativa, nao restrita; com MFA e fator confirmado e ativo, ou (029) sem MFA de
        -- identidade que nunca confirmou fator: o ramo 2 de identity_org_session_allowed
        SELECT s.identity_id INTO v_identity
          FROM public.identity_sessions s
          JOIN public.identities i ON i.identity_id = s.identity_id
         WHERE s.identity_session_id = p_identity_session_id
           AND s.identity_id = v_ctx::uuid
           AND s.revoked_at IS NULL
           AND s.expires_at > pg_catalog.clock_timestamp()
           AND (s.recovery_used_at IS NULL OR s.recovery_cleared_at IS NOT NULL)
           AND i.status = 2
           AND CASE WHEN s.mfa_verified_at IS NOT NULL THEN
                    EXISTS (SELECT 1 FROM public.identity_mfa_factors g
                             WHERE g.identity_id = s.identity_id AND g.confirmed_at IS NOT NULL AND g.disabled_at IS NULL)
                ELSE public.identity_never_had_factor(s.identity_id) END;
        IF v_identity IS NOT NULL THEN
            RETURN QUERY
            SELECT u.organization_id, u.user_id, o.name
              FROM public.identity_users iu
              JOIN public.users u ON u.user_id = iu.user_id
              JOIN public.organizations o ON o.organization_id = u.organization_id
              JOIN public.organization_memberships m ON m.user_id = u.user_id AND m.organization_id = u.organization_id
             WHERE iu.identity_id = v_identity
               AND (p_organization_id IS NULL OR u.organization_id = p_organization_id)
               AND u.account_type = 1 AND u.status = 2 AND u.email_verified_at IS NOT NULL
               AND o.status = 1 AND m.status = 2
             ORDER BY o.name, o.organization_id
             LIMIT p_limit;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
        PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
        RAISE;
    END;
    PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(v_prev_org, ''), true);
    PERFORM pg_catalog.set_config('nekkora.fn_identity_id', coalesce(v_prev_id, ''), true);
END;
$$;

COMMENT ON FUNCTION public.identity_session_organizations(UUID, UUID, INTEGER) IS
    '039: pares (organizacao, conta) elegiveis AGORA da identidade do contexto, se a sessao de identidade informada for dela, '
    'ativa e nao restrita. So leitura. Uso: rotas /api/v1/desktop/organizations... (escopo identity navigation).';

-- === 3. Dono e privilegios ===
ALTER FUNCTION public.identity_session_organizations(UUID, UUID, INTEGER) OWNER TO nekkora_identity_owner;
REVOKE ALL ON FUNCTION public.identity_session_organizations(UUID, UUID, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.identity_session_organizations(UUID, UUID, INTEGER) TO nekkora_platform;

-- === 4. Invariantes ===
DO $$
DECLARE
    a RECORD;
    p RECORD;
    v_bad TEXT;
BEGIN
    SELECT * INTO a FROM n039_antes;
    IF a.sessoes <> (SELECT count(*) FROM public.identity_sessions WHERE revoked_at IS NULL)
       OR a.vinculos <> (SELECT count(*) FROM public.identity_users)
       OR a.memberships <> (SELECT count(*) FROM public.organization_memberships) THEN
        RAISE EXCEPTION '039 invariante 1: a migration alterou sessoes ou vinculos';
    END IF;
    -- 2. funcao: unica, do dono, SECURITY DEFINER com search_path fixo, sem EXECUTE publico; so a Platform executa
    IF (SELECT count(*) FROM pg_proc WHERE proname = 'identity_session_organizations') <> 1 THEN
        RAISE EXCEPTION '039 invariante 2: funcao ausente ou duplicada';
    END IF;
    SELECT * INTO p FROM pg_proc WHERE oid = 'public.identity_session_organizations(uuid, uuid, integer)'::regprocedure;
    IF pg_get_userbyid(p.proowner) <> 'nekkora_identity_owner' OR NOT p.prosecdef
       OR NOT coalesce(p.proconfig @> ARRAY['search_path=pg_catalog, pg_temp'], false)
       OR has_function_privilege('public', p.oid, 'EXECUTE') OR NOT has_function_privilege('nekkora_platform', p.oid, 'EXECUTE') THEN
        RAISE EXCEPTION '039 invariante 2: funcao fora do padrao';
    END IF;
    SELECT string_agg(r, ', ') INTO v_bad
      FROM unnest(ARRAY['nekkora_portal', 'nekkora_portal_owner', 'nekkora_ops_mfa_owner', 'nekkora_provisioning', 'nekkora_ingestion',
                        'nekkora_agent_gateway']) r
     WHERE to_regrole(r) IS NOT NULL AND has_function_privilege(r, p.oid, 'EXECUTE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '039 invariante 2: EXECUTE indevido: %', v_bad; END IF;
    -- 3. a Platform continua sem nada em identity_users (invariante 7 da 017)
    IF has_table_privilege('nekkora_platform', 'public.identity_users', 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
       OR has_any_column_privilege('nekkora_platform', 'public.identity_users', 'SELECT, INSERT, UPDATE, REFERENCES') THEN
        RAISE EXCEPTION '039 invariante 3: a Platform ganhou privilegio em identity_users';
    END IF;
    -- 4. as funcoes da 017/029 que decidem o acesso nao mudaram de dono
    SELECT string_agg(q.oid::regprocedure::text, ', ') INTO v_bad FROM pg_proc q
     WHERE q.oid IN ('public.identity_org_session_allowed(uuid, uuid, uuid)'::regprocedure,
                     'public.identity_organizations(text)'::regprocedure,
                     'public.identity_org_session_target(text, uuid)'::regprocedure)
       AND pg_get_userbyid(q.proowner) <> 'nekkora_identity_owner';
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '039 invariante 4: funcao existente mudou de dono: %', v_bad; END IF;
END;
$$;

COMMIT;
