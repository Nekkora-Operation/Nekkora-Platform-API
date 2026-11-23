-- Nekkora Platform - a organizacao FORMALMENTE DESIGNADA como interna da Nekkora deixa de contar no limite de organizacoes vazias
-- (NEKKORA-LICENCIAMENTO-002, decisao da titular de 22/11/2026, item 3, e autorizacao de preparo/teste da 074 em 23/11).
-- Documento: docs/licenciamento-002/074-075-organizacao-interna-e-bloqueio.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 073 (a designacao formal). Numero 074 conferido em 23/11 contra a branch da 073.
--
-- *** COM ELEVACAO TEMPORARIA (SUPERUSER so durante a aplicacao desta versao; retirada e conferencia 0|0|0 depois, tambem em
-- *** caso de falha: db/bootstrap/aplicar_como_migrador.sh). Motivo: substitui o corpo de identity_empty_organizations(uuid),
-- *** funcao do dono nekkora_identity_owner (067); o migrador nao e membro desse papel. A elevacao em PRODUCAO exige
-- *** autorizacao propria (NEKKORA_AUTORIZA_ELEVACAO=1 no migrar.sh); a de 23/11 vale so para laboratorio.
--
-- O QUE FAZ
--   1. O dono da identidade passa a LER, da designacao (073), so organization_id e withdrawn_at. Nada mais: nem quem designou,
--      nem chamado, nem motivo; nenhuma escrita.
--   2. identity_empty_organizations(uuid) deixa de contar a organizacao com designacao VIGENTE (withdrawn_at nulo). A excecao
--      depende SO da designacao, feita pelo procedimento privilegiado e auditado (073: quem, chamado, motivo, quando): nunca do
--      nome da organizacao nem de opcao disponivel ao usuario.
--   3. Retirada a designacao (073: quem, chamado, motivo, quando), a organizacao VOLTA a contar no ato. Se com isso a pessoa
--      responsavel passar do limite, a 067 ja recusa NOVAS criacoes, aprovacoes de pedido e reativacoes (EMPTY_LIMIT); nenhuma
--      organizacao e apagada, encerrada ou desativada.
--
-- O QUE NAO FAZ: nao muda o limite (3), nao muda quem e responsavel, nao cria designacao, nao da nada a Platform.
--
-- AUDITORIA: a designacao e a retirada ficam na tabela da 073 (so acrescimo; retirada uma vez). O que a 074 acrescenta e so o
-- efeito na contagem, calculado na hora: nao ha estado novo a auditar.
--
-- REVERSAO (manual, COM elevacao): recriar identity_empty_organizations(uuid) com o corpo da 067 (dono nekkora_identity_owner) e
--   DROP POLICY identity_owner_read ON public.nekkora_internal_organization_designations;
--   REVOKE SELECT (organization_id, withdrawn_at) ON public.nekkora_internal_organization_designations FROM nekkora_identity_owner;

BEGIN;

-- === 1. Pre-condicao ===
DO $$
BEGIN
    IF to_regclass('public.nekkora_internal_organization_designations') IS NULL THEN
        RAISE EXCEPTION '074 exige a 073 aplicada';
    END IF;
    IF (SELECT pg_get_userbyid(p.proowner) FROM pg_proc p WHERE p.oid = 'public.identity_empty_organizations(uuid)'::regprocedure)
       <> 'nekkora_identity_owner' THEN
        RAISE EXCEPTION '074: identity_empty_organizations nao e do dono nekkora_identity_owner';
    END IF;
    IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
        RAISE EXCEPTION '074 exige a janela de elevacao (SUPERUSER so nesta versao): o papel % nao esta elevado', current_user;
    END IF;
    IF (SELECT pg_get_userbyid(c.relowner) FROM pg_class c WHERE c.oid = 'public.nekkora_internal_organization_designations'::regclass)
       <> current_user THEN
        RAISE EXCEPTION '074: a designacao nao e do papel %', current_user;
    END IF;
END;
$$;

-- === 2. O dono da identidade le so organizacao e retirada da designacao ===
GRANT SELECT (organization_id, withdrawn_at) ON public.nekkora_internal_organization_designations TO nekkora_identity_owner;
CREATE POLICY identity_owner_read ON public.nekkora_internal_organization_designations AS PERMISSIVE FOR SELECT
    TO nekkora_identity_owner USING (true);

-- === 3. A contagem de vazias exclui a organizacao com designacao vigente ===
CREATE OR REPLACE FUNCTION public.identity_empty_organizations(p_identity UUID) RETURNS UUID[]
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT coalesce(pg_catalog.array_agg(o.organization_id ORDER BY o.organization_id), ARRAY[]::uuid[])
      FROM public.organization_responsibility r
      JOIN public.organizations o ON o.organization_id = r.organization_id AND o.status = 1
     WHERE r.responsible_identity_id = p_identity
       AND NOT EXISTS (SELECT 1 FROM public.automations a WHERE a.organization_id = o.organization_id)
       -- (074) a organizacao DESIGNADA (vigente) como interna da Nekkora nao conta; retirada a designacao, volta a contar
       AND NOT EXISTS (SELECT 1 FROM public.nekkora_internal_organization_designations d
                        WHERE d.organization_id = o.organization_id AND d.withdrawn_at IS NULL);
$$;
ALTER FUNCTION public.identity_empty_organizations(uuid) OWNER TO nekkora_identity_owner;
REVOKE ALL ON FUNCTION public.identity_empty_organizations(uuid) FROM PUBLIC;
COMMENT ON FUNCTION public.identity_empty_organizations(uuid) IS
    'Organizacoes ATIVAS e VAZIAS (sem automacao) de que a identidade e responsavel (067); desde a 074 sem a organizacao com '
    'designacao vigente de interna da Nekkora (073). Exige nekkora.fn_identity_id = p_identity.';

-- === 4. Invariantes ===
DO $$
DECLARE
    p RECORD;
    r TEXT;
BEGIN
    SELECT pr.prosecdef, pr.proconfig, pg_get_userbyid(pr.proowner) AS owner, pr.oid INTO p FROM pg_proc pr
     WHERE pr.oid = 'public.identity_empty_organizations(uuid)'::regprocedure;
    IF p.owner <> 'nekkora_identity_owner' OR p.prosecdef OR NOT coalesce(p.proconfig @> ARRAY['search_path=pg_catalog, pg_temp'], false)
       OR has_function_privilege('public', p.oid, 'EXECUTE') OR has_function_privilege('nekkora_platform', p.oid, 'EXECUTE') THEN
        RAISE EXCEPTION '074: identity_empty_organizations com dono, SECURITY DEFINER, search_path ou EXECUTE fora do previsto';
    END IF;
    -- o dono da identidade so le as duas colunas; nada de quem designou, chamado ou motivo; nenhuma escrita
    IF has_table_privilege('nekkora_identity_owner', 'public.nekkora_internal_organization_designations', 'SELECT, INSERT, UPDATE, DELETE')
       OR has_column_privilege('nekkora_identity_owner', 'public.nekkora_internal_organization_designations', 'designated_by', 'SELECT')
       OR has_column_privilege('nekkora_identity_owner', 'public.nekkora_internal_organization_designations', 'designation_reason', 'SELECT')
       OR has_column_privilege('nekkora_identity_owner', 'public.nekkora_internal_organization_designations', 'designation_ticket', 'SELECT')
       OR NOT has_column_privilege('nekkora_identity_owner', 'public.nekkora_internal_organization_designations', 'withdrawn_at', 'SELECT') THEN
        RAISE EXCEPTION '074: leitura da designacao pelo dono da identidade fora do previsto';
    END IF;
    -- os servicos da aplicacao continuam sem nada na designacao
    FOREACH r IN ARRAY ARRAY['nekkora_platform', 'nekkora_portal', 'nekkora_provisioning', 'nekkora_ingestion'] LOOP
        CONTINUE WHEN to_regrole(r) IS NULL;
        IF has_any_column_privilege(r, 'public.nekkora_internal_organization_designations', 'SELECT, INSERT, UPDATE') THEN
            RAISE EXCEPTION '074: o papel % alcanca a designacao', r;
        END IF;
    END LOOP;
END;
$$;

COMMIT;
