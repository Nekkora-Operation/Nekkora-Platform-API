-- Nekkora Platform - no maximo um rascunho por automacao (PLATFORM-AGENT-V2-002, T09). Contrato: api/openapi/nekkora-api-v1.yaml
-- (PUT /api/v1/o/{org}/automations/{automationId}/draft/artifact; POST .../versions com 409 draft_exists) e
-- docs/platform-agent-v2-002/t09-rascunho-unico.md.
--
-- CANDIDATA ate a aprovacao do PR. Numero 032 conferido em 10/11 contra as branches remotas (a maior era 031; nenhuma tinha
-- 032). A proposta da T02 da PLATFORM-V2-001 (sessao) citava a 032, mas ela ainda nao tem migration escrita nem decisao:
-- quando for escrita, usa o proximo numero livre. As ferramentas do laboratorio e da homologacao exigem numeracao contigua,
-- por isso esta nao pula numero. Se outra migration ocupar 032 antes, esta e renumerada sem mudar o conteudo.
--
-- O que muda:
--   1. RESTRICAO NO BANCO (indice unico parcial): automation_versions (organization_id, automation_id) WHERE status = 1 (DRAFT).
--      Duas criacoes simultaneas de rascunho na mesma automacao resultam em uma gravacao e um 23505 (a API responde 409);
--      versoes publicadas nao participam (sao quantas forem). Nada mais muda: numeracao (005), imutabilidade da publicada,
--      artefatos (028) e auditoria continuam como estao.
--
-- DADOS EXISTENTES: se alguma automacao ja tiver MAIS DE UM rascunho, esta migration PARA antes de qualquer alteracao e lista
-- os registros (organizacao, automacao, rascunhos com numero, pacote e criacao). Nada e apagado, publicado nem renumerado:
-- a decisao e humana (manter um e publicar ou abandonar os outros por um procedimento aprovado). Rascunhos LEGADOS sem pacote
-- (criados pela rota antiga) nao impedem a migration quando sao o unico rascunho da automacao: viram o rascunho dela, que a
-- Web oferece em "Continuar rascunho". Diagnostico somente leitura, para rodar antes:
-- db/diagnosticos/032_rascunhos_por_automacao.sql.
--
-- Papeis: nenhum papel novo, nenhuma funcao SECURITY DEFINER, nenhum dono novo, nenhum privilegio novo: SEM elevacao (como
-- 025, 028, 030 e 031).

-- Transacao UNICA: a leitura de todas as organizacoes na pre-condicao exige que o dono (migrador) atravesse a RLS FORCADA de
-- automation_versions; ela e desligada so dentro desta transacao e religada antes do COMMIT (conferido nos invariantes).
-- Qualquer falha (inclusive a recusa por rascunhos repetidos) desfaz tudo, a RLS forcada inclusive.
BEGIN;

-- === 0. Pre-condicoes ===
ALTER TABLE public.automation_versions NO FORCE ROW LEVEL SECURITY;
DO $$
DECLARE
    v_repetidos TEXT;
BEGIN
    IF to_regclass('public.automation_artifacts') IS NULL OR to_regclass('public.ux_workspaces_organization_name_key') IS NULL THEN
        RAISE EXCEPTION '032: exige 028 e 031';
    END IF;
    IF to_regclass('public.ux_automation_versions_one_draft') IS NOT NULL THEN
        RAISE EXCEPTION '032: ja aplicada';
    END IF;
    SELECT string_agg(format('organizacao %s, automacao %s: %s', organization_id, automation_id, rascunhos), E'\n' ORDER BY organization_id, automation_id)
      INTO v_repetidos
      FROM (SELECT organization_id, automation_id,
                   string_agg(format('%s (versao %s, %s, criada em %s)', automation_version_id, version_number,
                                     CASE WHEN command_json ? 'artifact' THEN 'com pacote ' || left(command_json->'artifact'->>'sha256', 12)
                                          ELSE 'sem pacote' END, created_at),
                              ', ' ORDER BY version_number) AS rascunhos
              FROM public.automation_versions
             WHERE status = 1
             GROUP BY organization_id, automation_id
            HAVING count(*) > 1) r;
    IF v_repetidos IS NOT NULL THEN
        RAISE EXCEPTION '032: ha automacoes com mais de um rascunho; nada foi alterado. Decida quais rascunhos ficam e aplique de novo'
            USING DETAIL = v_repetidos,
                  HINT = 'diagnostico: db/diagnosticos/032_rascunhos_por_automacao.sql';
    END IF;
END;
$$;

-- === 1. Um rascunho por automacao ===
CREATE UNIQUE INDEX ux_automation_versions_one_draft ON public.automation_versions (organization_id, automation_id) WHERE status = 1;
ALTER TABLE public.automation_versions FORCE ROW LEVEL SECURITY;
COMMENT ON INDEX public.ux_automation_versions_one_draft IS
    'no maximo um rascunho (status 1 = DRAFT) por automacao (032); versoes publicadas nao participam';

-- === 2. Invariantes ===
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                    WHERE c.relname = 'ux_automation_versions_one_draft' AND i.indisunique AND i.indisvalid AND i.indpred IS NOT NULL) THEN
        RAISE EXCEPTION '032: indice unico parcial do rascunho ausente ou invalido';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = 'public.automation_versions'::regclass AND relrowsecurity AND relforcerowsecurity) THEN
        RAISE EXCEPTION '032: RLS forcada de automation_versions nao foi religada';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.automation_versions', 'DELETE')
       OR has_table_privilege('nekkora_platform', 'public.automation_versions', 'TRUNCATE') THEN
        RAISE EXCEPTION '032: privilegios de nekkora_platform mudaram';
    END IF;
END;
$$;

COMMIT;
