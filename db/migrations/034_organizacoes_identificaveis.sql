-- Nekkora Platform - organizacoes identificaveis e nome unico entre as organizacoes criadas pela mesma identidade pelo cadastro
-- (PLATFORM-AGENT-V2-003, T10). Contrato: api/openapi/nekkora-api-v1.yaml (POST /api/v1/organizations: 409 conflict + reason
-- organization_name_taken; GET /api/v1/me/organizations: handle) e docs/platform-agent-v2-003/t10-organizacoes-identificaveis.md.
--
-- CANDIDATA ate a aprovacao do PR. Numero 034 conferido em 11/11 contra a dev (df92772) e as branches remotas (a maior era 032;
-- a 033 e a da T12, PR anterior a este). As ferramentas exigem numeracao contigua: se a ordem de integracao mudar, esta e
-- renumerada sem mudar o conteudo.
--
-- REGRA APROVADA (11/11): o nome e unico entre as organizacoes CRIADAS PELA MESMA IDENTIDADE PELO CADASTRO. E AUTORIA, nao
-- propriedade: nao concede permissao nenhuma. Participacoes por convite (inclusive como ORG_ADMIN) nao entram na comparacao;
-- organizacoes provisionadas pela operacao ficam fora da restricao (sem autoria registrada), mas recebem identificador.
--
-- 1. AUTORIA (public.organization_authorship): organizacao -> identidade criadora (identities.identity_id, o identificador
--    PERSISTENTE da identidade; nunca e-mail nem sessao), com a chave do nome. Gravada pelo gatilho do vinculo de cadastro
--    (identity_users com link_channel = 'cadastro_web', que identity_create_organization_impl da 017 insere SO para a conta
--    que acabou de criar, na mesma transacao da organizacao). Imutavel: so a chave do nome acompanha uma mudanca de nome.
-- 2. NOME UNICO: indice unico (creator_identity_id, md5(name_key)). name_key = public.organization_name_key(nome):
--    espacos (inclusive os separadores Unicode) reduzidos a um, sem espacos nas pontas, sem distinguir maiusculas e minusculas (lower
--    trata as letras acentuadas: conferido abaixo). Acentos e pontuacao NAO sao removidos (mudaria a regra). O nome de
--    apresentacao fica como foi digitado. Criacoes simultaneas: a 017 ja trava a identidade (FOR UPDATE) e o indice unico
--    garante; a recusa e SQLSTATE 23505 com o nome ux_organization_authorship_creator_name -> API 409 organization_name_taken.
-- 3. IDENTIFICADOR LEGIVEL (public.organization_handles): unico, estavel (renomear nao muda), gerado do nome na criacao
--    (letras sem acento, minusculas, digitos e hifens; ate 40 caracteres; "organizacao" se nada sobrar) com sufixo -2, -3...
--    em colisao. Nao e credencial e nao concede acesso: nenhuma rota aceita o identificador no lugar do ID. Gerado pelo
--    gatilho de insercao em organizations (cadastro e provisionamento), sob trava por candidato.
--
-- RLS (ENABLE + FORCE) nas duas tabelas novas: a Platform le e atualiza SO no contexto da organizacao (nekkora.organization_id,
-- o padrao da 012); os papeis que CRIAM organizacoes (nekkora_identity_owner, cadastro; nekkora_provisioning_owner, operacao)
-- veem todas as linhas, como precisam para alocar identificadores unicos e gravar a autoria.
--
-- DADOS EXISTENTES:
--   * identificador: atribuido a TODAS as organizacoes, na ordem de criacao (created_at, organization_id). IDs, contas,
--     vinculos, workspaces e historico nao mudam.
--   * autoria: preenchida SO com evidencia inequivoca, duas provas independentes que concordam: exatamente UM vinculo
--     'cadastro_web' de conta da organizacao E o evento de provisionamento 'cadastro_web' (003/017) dessa mesma conta. Nunca
--     deduzida de quem e administradora hoje. Organizacao sem as duas provas fica SEM autoria (fora da restricao) e aparece
--     no diagnostico como caso para decisao.
--   * se a mesma identidade ja criou duas organizacoes com o mesmo nome normalizado, esta migration PARA antes de qualquer
--     alteracao e lista os casos. Nada e excluido, fundido nem renomeado automaticamente: a decisao e humana.
--     Diagnostico somente leitura: db/diagnosticos/034_organizacoes_por_criadora.sql.
--
-- Papeis: nenhum papel novo, nenhuma funcao SECURITY DEFINER, nenhum dono novo: SEM elevacao. Privilegios novos so nas
-- tabelas novas. A 017 (funcoes de identidade) NAO e alterada.

BEGIN;

-- === 0. Chave do nome (antes da pre-condicao, que a usa) ===
-- espacos: os do locale ([:space:]) e os separadores Unicode que a API ja recusa nas pontas (U+00A0, U+2000-U+200A, U+202F,
-- U+205F, U+3000): qualquer sequencia vira um espaco; depois, sem espacos nas pontas e minusculas
CREATE FUNCTION public.organization_name_key(p_name TEXT) RETURNS TEXT
LANGUAGE sql IMMUTABLE STRICT SET search_path = pg_catalog, pg_temp AS $$
    SELECT lower(btrim(regexp_replace(p_name, '[[:space:]' || U&'\00A0\2000-\200A\202F\205F\3000' || ']+', ' ', 'g')))
$$;
COMMENT ON FUNCTION public.organization_name_key(TEXT) IS
    'chave do nome da organizacao (034): espacos (inclusive os separadores Unicode) reduzidos a um, sem espacos nas pontas, minusculas; acentos e pontuacao preservados';

-- === 0. Pre-condicoes ===
-- leitura de todas as organizacoes: as tabelas lidas com RLS FORCADA ficam sem a forca SO dentro desta transacao, e cada uma
-- volta exatamente ao estado anterior (registrado aqui) antes do COMMIT
CREATE TEMP TABLE tmp_034_forcadas ON COMMIT DROP AS
    SELECT c.oid::regclass AS tabela FROM pg_class c
     WHERE c.oid IN ('public.organizations'::regclass, 'public.users'::regclass, 'public.identity_users'::regclass,
                     'public.identity_provisioning_events'::regclass)
       AND c.relforcerowsecurity;
DO $$
DECLARE
    t REGCLASS;
BEGIN
    FOR t IN SELECT tabela FROM tmp_034_forcadas LOOP
        EXECUTE format('ALTER TABLE %s NO FORCE ROW LEVEL SECURITY', t);
    END LOOP;
END;
$$;
DO $$
DECLARE
    v_repetidas TEXT;
BEGIN
    IF to_regclass('public.ix_local_run_authorizations_vigente') IS NULL THEN
        RAISE EXCEPTION '034: exige 033';
    END IF;
    IF to_regclass('public.organization_authorship') IS NOT NULL OR to_regclass('public.organization_handles') IS NOT NULL THEN
        RAISE EXCEPTION '034: ja aplicada';
    END IF;
    IF lower(U&'\00C1\00C7\00C3O \00C4') <> U&'\00E1\00E7\00E3o \00E4' THEN
        RAISE EXCEPTION '034: lower() nao trata letras acentuadas neste banco (locale); a regra de nome nao pode ser garantida';
    END IF;
    SELECT string_agg(format('identidade %s, nome "%s": %s', identity_id, chave, orgs), E'\n' ORDER BY identity_id, chave)
      INTO v_repetidas
      FROM (SELECT e.identity_id, public.organization_name_key(o.name) AS chave,
                   string_agg(format('%s ("%s", criada em %s)', o.organization_id, o.name, o.created_at), ', ' ORDER BY o.created_at, o.organization_id) AS orgs
              FROM (SELECT iu.identity_id, u.organization_id
                      FROM public.identity_users iu JOIN public.users u ON u.user_id = iu.user_id
                     WHERE iu.link_channel = 'cadastro_web'
                       AND (SELECT count(*) FROM public.identity_users iu2 JOIN public.users u2 ON u2.user_id = iu2.user_id
                             WHERE iu2.link_channel = 'cadastro_web' AND u2.organization_id = u.organization_id) = 1
                       AND EXISTS (SELECT 1 FROM public.identity_provisioning_events p
                                    WHERE p.organization_id = u.organization_id AND p.channel = 'cadastro_web' AND p.provisioned_by_user_id = u.user_id)) e
              JOIN public.organizations o ON o.organization_id = e.organization_id
             GROUP BY e.identity_id, public.organization_name_key(o.name)
            HAVING count(*) > 1) r;
    IF v_repetidas IS NOT NULL THEN
        RAISE EXCEPTION '034: ha identidades que criaram mais de uma organizacao com o mesmo nome; nada foi alterado. Decida (renomear ou encerrar uma delas) e aplique de novo'
            USING DETAIL = v_repetidas,
                  HINT = 'diagnostico: db/diagnosticos/034_organizacoes_por_criadora.sql';
    END IF;
END;
$$;

-- === 1. Base do identificador ===
CREATE FUNCTION public.organization_handle_base(p_name TEXT) RETURNS TEXT
LANGUAGE sql IMMUTABLE STRICT SET search_path = pg_catalog, pg_temp AS $$
    SELECT coalesce(nullif(btrim(left(btrim(regexp_replace(lower(translate(p_name,
               U&'\00C1\00C0\00C2\00C3\00C4\00C5\00E1\00E0\00E2\00E3\00E4\00E5\00C9\00C8\00CA\00CB\00E9\00E8\00EA\00EB\00CD\00CC\00CE\00CF\00ED\00EC\00EE\00EF\00D3\00D2\00D4\00D5\00D6\00F3\00F2\00F4\00F5\00F6\00DA\00D9\00DB\00DC\00FA\00F9\00FB\00FC\00C7\00E7\00D1\00F1\00DD\00FD\00FF',
               'AAAAAAaaaaaaEEEEeeeeIIIIiiiiOOOOOoooooUUUUuuuuCcNnYyy')), '[^a-z0-9]+', '-', 'g'), '-'), 40), '-'), ''), 'organizacao')
$$;
COMMENT ON FUNCTION public.organization_handle_base(TEXT) IS
    'base do identificador legivel (034): letras sem acento, minusculas, digitos e hifens, ate 40 caracteres; "organizacao" se vazio';

-- === 2. Identificador legivel ===
CREATE TABLE public.organization_handles (
    organization_id UUID PRIMARY KEY REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    handle          TEXT NOT NULL CHECK (handle ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(handle) <= 48),
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ux_organization_handles_handle UNIQUE (handle)
);
COMMENT ON TABLE public.organization_handles IS
    'identificador legivel e estavel da organizacao (034): unico, gerado na criacao, nao muda com o nome; nao e credencial nem concede acesso';

-- alocacao: o primeiro candidato livre (base, base-2, base-3...), cada um sob a sua trava (duas criacoes nunca escolhem o mesmo)
CREATE FUNCTION public.organization_handle_allocate(p_org UUID, p_name TEXT) RETURNS TEXT
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_base TEXT := public.organization_handle_base(p_name);
    v_try TEXT;
    n INT := 1;
BEGIN
    LOOP
        v_try := CASE WHEN n = 1 THEN v_base ELSE v_base || '-' || n END;
        PERFORM pg_advisory_xact_lock(hashtextextended('nekkora.organization_handle|' || v_try, 34));
        IF NOT EXISTS (SELECT 1 FROM public.organization_handles h WHERE h.handle = v_try) THEN
            INSERT INTO public.organization_handles (organization_id, handle) VALUES (p_org, v_try);
            RETURN v_try;
        END IF;
        n := n + 1;
    END LOOP;
END;
$$;

CREATE FUNCTION public.organization_on_insert_handle() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM public.organization_handle_allocate(NEW.organization_id, NEW.name);
    RETURN NULL;
END;
$$;

-- === 3. Autoria e nome unico por identidade criadora ===
CREATE TABLE public.organization_authorship (
    organization_id     UUID PRIMARY KEY REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    creator_identity_id UUID NOT NULL REFERENCES public.identities (identity_id) ON DELETE RESTRICT,
    name_key            TEXT NOT NULL CHECK (name_key <> ''),
    evidence            TEXT NOT NULL CHECK (evidence IN ('cadastro_web', 'migracao_034')),
    recorded_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX ux_organization_authorship_creator_name ON public.organization_authorship (creator_identity_id, md5(name_key));
COMMENT ON TABLE public.organization_authorship IS
    'AUTORIA da organizacao (034): a identidade que a criou pelo cadastro. Nao e propriedade e nao concede permissao. Base do nome unico entre as organizacoes da mesma criadora';

-- o vinculo de cadastro e a prova da criacao (017: so a conta que identity_create_organization_impl acabou de inserir)
CREATE FUNCTION public.organization_authorship_on_cadastro() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    INSERT INTO public.organization_authorship (organization_id, creator_identity_id, name_key, evidence)
    SELECT u.organization_id, NEW.identity_id, public.organization_name_key(o.name), 'cadastro_web'
      FROM public.users u JOIN public.organizations o ON o.organization_id = u.organization_id
     WHERE u.user_id = NEW.user_id;
    RETURN NULL;
END;
$$;

-- se o nome mudar (nao ha rota para isso hoje), a chave acompanha e a regra continua valendo
CREATE FUNCTION public.organization_authorship_on_rename() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.name IS DISTINCT FROM OLD.name THEN
        UPDATE public.organization_authorship SET name_key = public.organization_name_key(NEW.name) WHERE organization_id = NEW.organization_id;
    END IF;
    RETURN NULL;
END;
$$;

-- autoria imutavel: so a chave do nome muda (pelo gatilho acima)
CREATE FUNCTION public.organization_authorship_protect() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.organization_id IS DISTINCT FROM OLD.organization_id OR NEW.creator_identity_id IS DISTINCT FROM OLD.creator_identity_id
       OR NEW.evidence IS DISTINCT FROM OLD.evidence OR NEW.recorded_at IS DISTINCT FROM OLD.recorded_at THEN
        RAISE EXCEPTION 'autoria da organizacao e imutavel' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- === 4. Dados existentes (como dono, com a RLS desligada so nesta transacao) ===
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN SELECT organization_id, name FROM public.organizations ORDER BY created_at, organization_id LOOP
        PERFORM public.organization_handle_allocate(r.organization_id, r.name);
    END LOOP;
END;
$$;
INSERT INTO public.organization_authorship (organization_id, creator_identity_id, name_key, evidence)
SELECT u.organization_id, iu.identity_id, public.organization_name_key(o.name), 'migracao_034'
  FROM public.identity_users iu
  JOIN public.users u ON u.user_id = iu.user_id
  JOIN public.organizations o ON o.organization_id = u.organization_id
 WHERE iu.link_channel = 'cadastro_web'
   AND (SELECT count(*) FROM public.identity_users iu2 JOIN public.users u2 ON u2.user_id = iu2.user_id
         WHERE iu2.link_channel = 'cadastro_web' AND u2.organization_id = u.organization_id) = 1
   AND EXISTS (SELECT 1 FROM public.identity_provisioning_events p
                WHERE p.organization_id = u.organization_id AND p.channel = 'cadastro_web' AND p.provisioned_by_user_id = u.user_id);

-- === 5. Gatilhos (SECURITY INVOKER, search_path fixo) ===
CREATE TRIGGER trg_organizations_handle AFTER INSERT ON public.organizations FOR EACH ROW EXECUTE FUNCTION public.organization_on_insert_handle();
CREATE TRIGGER trg_organizations_authorship_rename AFTER UPDATE OF name ON public.organizations FOR EACH ROW
    EXECUTE FUNCTION public.organization_authorship_on_rename();
CREATE TRIGGER trg_identity_users_authorship AFTER INSERT ON public.identity_users FOR EACH ROW
    WHEN (NEW.link_channel = 'cadastro_web') EXECUTE FUNCTION public.organization_authorship_on_cadastro();
CREATE TRIGGER trg_organization_handles_prevent_update BEFORE UPDATE ON public.organization_handles FOR EACH ROW EXECUTE FUNCTION public.prevent_update();
CREATE TRIGGER trg_organization_handles_prevent_delete BEFORE DELETE ON public.organization_handles FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_organization_handles_prevent_truncate BEFORE TRUNCATE ON public.organization_handles FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();
CREATE TRIGGER trg_organization_authorship_protect BEFORE UPDATE ON public.organization_authorship FOR EACH ROW EXECUTE FUNCTION public.organization_authorship_protect();
CREATE TRIGGER trg_organization_authorship_prevent_delete BEFORE DELETE ON public.organization_authorship FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_organization_authorship_prevent_truncate BEFORE TRUNCATE ON public.organization_authorship FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 6. RLS e privilegios ===
ALTER TABLE public.organization_handles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.organization_handles FORCE ROW LEVEL SECURITY;
ALTER TABLE public.organization_authorship ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.organization_authorship FORCE ROW LEVEL SECURITY;
CREATE POLICY organization_handles_contexto ON public.organization_handles FOR SELECT TO nekkora_platform
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid);
CREATE POLICY organization_handles_alocacao ON public.organization_handles FOR ALL TO nekkora_identity_owner, nekkora_provisioning_owner
    USING (true) WITH CHECK (true);
CREATE POLICY organization_authorship_contexto ON public.organization_authorship FOR ALL TO nekkora_platform, nekkora_provisioning_owner
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid);
CREATE POLICY organization_authorship_cadastro ON public.organization_authorship FOR INSERT TO nekkora_identity_owner WITH CHECK (true);

REVOKE ALL ON public.organization_handles, public.organization_authorship FROM PUBLIC;
GRANT SELECT ON public.organization_handles TO nekkora_platform;
GRANT SELECT, INSERT ON public.organization_handles TO nekkora_identity_owner, nekkora_provisioning_owner;
GRANT INSERT ON public.organization_authorship TO nekkora_identity_owner;
GRANT SELECT (organization_id), UPDATE (name_key) ON public.organization_authorship TO nekkora_platform, nekkora_provisioning_owner;

-- funcoes chamadas pelos gatilhos, com o papel de quem grava (SECURITY INVOKER): EXECUTE so para quem precisa
REVOKE ALL ON FUNCTION public.organization_name_key(TEXT), public.organization_handle_base(TEXT), public.organization_handle_allocate(UUID, TEXT),
    public.organization_on_insert_handle(), public.organization_authorship_on_cadastro(), public.organization_authorship_on_rename(),
    public.organization_authorship_protect() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.organization_handle_base(TEXT), public.organization_handle_allocate(UUID, TEXT)
    TO nekkora_identity_owner, nekkora_provisioning_owner;
GRANT EXECUTE ON FUNCTION public.organization_name_key(TEXT) TO nekkora_identity_owner, nekkora_provisioning_owner, nekkora_platform;

DO $$
DECLARE
    t REGCLASS;
BEGIN
    FOR t IN SELECT tabela FROM tmp_034_forcadas LOOP
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', t);
    END LOOP;
END;
$$;

-- === 7. Invariantes ===
DO $$
DECLARE
    f TEXT;
BEGIN
    IF (SELECT count(*) FROM public.organizations) <> (SELECT count(*) FROM public.organization_handles) THEN
        RAISE EXCEPTION '034: organizacao sem identificador';
    END IF;
    FOREACH f IN ARRAY ARRAY['organization_name_key(text)', 'organization_handle_base(text)', 'organization_handle_allocate(uuid,text)',
                             'organization_on_insert_handle()', 'organization_authorship_on_cadastro()', 'organization_authorship_on_rename()',
                             'organization_authorship_protect()'] LOOP
        IF (SELECT prosecdef FROM pg_proc WHERE oid = ('public.' || f)::regprocedure) THEN
            RAISE EXCEPTION '034: % nao pode ser SECURITY DEFINER', f;
        END IF;
    END LOOP;
    IF public.organization_name_key(U&'  Minha   Empresa\00A0 ') <> 'minha empresa' OR public.organization_name_key('A  B') <> public.organization_name_key(' a b ') THEN
        RAISE EXCEPTION '034: normalizacao do nome';
    END IF;
    IF public.organization_handle_base(U&'Constru\00E7\00E3o & Cia  ') <> 'construcao-cia' OR public.organization_handle_base('***') <> 'organizacao' THEN
        RAISE EXCEPTION '034: base do identificador';
    END IF;
    IF EXISTS (SELECT 1 FROM tmp_034_forcadas f JOIN pg_class c ON c.oid = f.tabela WHERE NOT c.relforcerowsecurity)
       OR NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = 'public.organization_handles'::regclass AND relrowsecurity AND relforcerowsecurity)
       OR NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = 'public.organization_authorship'::regclass AND relrowsecurity AND relforcerowsecurity) THEN
        RAISE EXCEPTION '034: RLS forcada ausente';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.organization_handles', 'INSERT, UPDATE, DELETE')
       OR has_table_privilege('nekkora_platform', 'public.organization_authorship', 'INSERT, DELETE')
       OR has_table_privilege('nekkora_identity_owner', 'public.organization_authorship', 'UPDATE, DELETE') THEN
        RAISE EXCEPTION '034: privilegios alem do necessario';
    END IF;
END;
$$;

COMMIT;
