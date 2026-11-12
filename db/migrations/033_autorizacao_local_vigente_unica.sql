-- Nekkora Platform - no maximo UMA autorizacao local VIGENTE por escopo (PLATFORM-AGENT-V2-003, T12). Contrato:
-- api/openapi/nekkora-api-v1.yaml (POST /api/v1/o/{org}/workspaces/{workspaceId}/local-run-authorizations: 409 conflict +
-- reason local_run_authorization_exists) e docs/platform-agent-v2-003/t12-autorizacao-vigente-unica.md.
--
-- CANDIDATA ate a aprovacao do PR. Numero 033 conferido em 11/11 contra a dev (df92772) e as branches remotas (a maior era 032).
-- As ferramentas do laboratorio e da homologacao exigem numeracao contigua: se outra migration ocupar 033 antes, esta e
-- renumerada sem mudar o conteudo.
--
-- ESCOPO (a chave do dominio, conferida no diagnostico da T12):
--   * pacote (source_kind 1 STUDIO_PACKAGE e 2 EXTERNAL_PACKAGE): organizacao + autorizacao do Worker no workspace
--     (worker_workspace_assignment_id, que fixa Worker E workspace) + VERSAO da automacao (a versao fixa o pacote e a regra);
--   * monitoramento (source_kind 3 EXTERNAL_MONITORED): organizacao + autorizacao do Worker no workspace + AUTOMACAO.
--   Outros Workers, outras versoes, outras automacoes e outros workspaces continuam podendo ser autorizados: escopos distintos.
--   Pacote e monitoramento da mesma automacao tambem sao escopos distintos (origens diferentes, como ja eram).
--   Se o Worker perder a autorizacao no workspace e a receber de novo, a nova autorizacao do Worker (outro assignment_id) e
--   outro escopo: as autorizacoes antigas deixam de chegar ao Agent (o gateway exige a autorizacao do Worker ATIVA, 025).
--
-- VIGENTE: revoked_at IS NULL e valid_until > horario do servidor (o mesmo criterio do gateway, 025/030). Uma autorizacao
-- cujo valid_until e EXATAMENTE o instante da nova ja esta vencida (a fronteira e aberta, como na entrega ao Agent).
--
-- POR QUE GATILHO E NAO INDICE: "vigente" depende do relogio (valid_until > agora), e um indice unico parcial nao pode usar
-- clock_timestamp(); uma restricao de exclusao por intervalo exigiria a extensao btree_gist, que a Platform nao usa. O
-- gatilho de insercao (o mesmo da 030, agora estendido) trava o ESCOPO com pg_advisory_xact_lock e so entao procura uma
-- autorizacao vigente. Em READ COMMITTED (OrgTransactions), a consulta depois da trava ve o que a transacao concorrente
-- confirmou: duas abas, duas pessoas ou chamadas diretas nunca gravam duas. A trava e por escopo (colisao de hash so
-- serializa mais, nunca libera), dura ate o fim da transacao e nao bloqueia escopos diferentes.
-- A recusa sai com SQLSTATE 23505 e o nome ux_local_run_authorizations_vigente (USING CONSTRAINT): a API a reconhece pelo
-- nome (DbErrors.uniqueViolation) e responde 409 conflict + reason local_run_authorization_exists. Nada e gravado.
--
-- NADA E ESTENDIDO, SUBSTITUIDO OU REVOGADO para aceitar a repeticao. Depois de revogada ou vencida, uma nova autorizacao
-- para o mesmo escopo e aceita; as anteriores ficam no historico (025 ja proibe apagar e so permite revogar).
--
-- DADOS EXISTENTES: se algum escopo ja tiver MAIS DE UMA autorizacao vigente, esta migration PARA antes de qualquer alteracao
-- e lista os registros (organizacao, workspace, Worker, escopo, autorizacoes com concessao e validade). Nada e revogado
-- automaticamente: a decisao e humana (revogar pela Web, "Revogar", as que nao devem ficar; o historico e preservado).
-- Diagnostico somente leitura: db/diagnosticos/033_autorizacoes_vigentes_repetidas.sql.
--
-- Papeis: nenhum papel novo, nenhuma funcao SECURITY DEFINER, nenhum dono novo, nenhum privilegio novo: SEM elevacao (como
-- 025, 028, 030, 031 e 032). pg_advisory_xact_lock e hashtextextended sao funcoes de catalogo executaveis por PUBLIC.

BEGIN;

-- === 0. Pre-condicoes ===
ALTER TABLE public.local_run_authorizations NO FORCE ROW LEVEL SECURITY;
DO $$
DECLARE
    v_repetidas TEXT;
BEGIN
    IF to_regclass('public.ux_automation_versions_one_draft') IS NULL THEN
        RAISE EXCEPTION '033: exige 032';
    END IF;
    IF to_regclass('public.ix_local_run_authorizations_vigente') IS NOT NULL THEN
        RAISE EXCEPTION '033: ja aplicada';
    END IF;
    SELECT string_agg(format('organizacao %s, workspace %s, Worker %s, %s: %s', organization_id, workspace_id, worker_id, escopo, autorizacoes),
                      E'\n' ORDER BY organization_id, workspace_id, worker_id, escopo)
      INTO v_repetidas
      FROM (SELECT organization_id, workspace_id, worker_id,
                   CASE WHEN source_kind = 3 THEN 'monitoramento da automacao ' || automation_id
                        ELSE 'pacote da versao ' || automation_version_id END AS escopo,
                   string_agg(format('%s (concedida em %s, vale ate %s)', authorization_id, granted_at, valid_until), ', '
                              ORDER BY granted_at, authorization_id) AS autorizacoes
              FROM public.local_run_authorizations
             WHERE revoked_at IS NULL AND valid_until > clock_timestamp()
             GROUP BY organization_id, workspace_id, worker_id, worker_workspace_assignment_id,
                      CASE WHEN source_kind = 3 THEN 'monitoramento da automacao ' || automation_id
                           ELSE 'pacote da versao ' || automation_version_id END
            HAVING count(*) > 1) r;
    IF v_repetidas IS NOT NULL THEN
        RAISE EXCEPTION '033: ha escopos com mais de uma autorizacao local vigente; nada foi alterado. Revogue pela Web as que nao devem ficar e aplique de novo'
            USING DETAIL = v_repetidas,
                  HINT = 'diagnostico: db/diagnosticos/033_autorizacoes_vigentes_repetidas.sql';
    END IF;
END;
$$;
ALTER TABLE public.local_run_authorizations FORCE ROW LEVEL SECURITY;

-- === 1. Busca da vigente por escopo (so as nao revogadas: as revogadas nunca voltam, 025) ===
CREATE INDEX ix_local_run_authorizations_vigente ON public.local_run_authorizations
    (organization_id, worker_workspace_assignment_id, automation_id, automation_version_id) WHERE revoked_at IS NULL;
COMMENT ON INDEX public.ix_local_run_authorizations_vigente IS
    'busca da autorizacao vigente do escopo no gatilho de insercao (033); revogadas fora do indice';

-- === 2. Gatilho de insercao: as regras da 030 + uma vigente por escopo ===
CREATE OR REPLACE FUNCTION public.local_run_authorization_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    NEW.granted_at := clock_timestamp();
    IF NEW.valid_until <= NEW.granted_at OR NEW.valid_until > NEW.granted_at + interval '366 days' THEN
        RAISE EXCEPTION 'validade da autorizacao fora de (0, 366 dias]' USING ERRCODE = '23514';
    END IF;
    IF NEW.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'autorizacao nasce ativa' USING ERRCODE = '23514';
    END IF;
    IF NOT public.agent_workspace_permission(NEW.organization_id, NEW.granted_by_membership_id, NEW.workspace_id, 'automation.execute') THEN
        RAISE EXCEPTION 'sem automation.execute no workspace' USING ERRCODE = '42501';
    END IF;
    IF NEW.source_kind IN (1, 2) THEN
        IF NOT EXISTS (SELECT 1 FROM public.automation_versions v
                        WHERE v.organization_id = NEW.organization_id AND v.automation_version_id = NEW.automation_version_id
                          AND v.automation_id = NEW.automation_id AND v.workspace_id = NEW.workspace_id
                          AND v.status = 2
                          AND (v.command_json -> 'agent_package' ->> 'fingerprint_rule') = NEW.package_fingerprint_rule
                          AND (v.command_json -> 'agent_package' ->> 'fingerprint') = NEW.package_fingerprint) THEN
            RAISE EXCEPTION 'versao nao publicada ou pacote diferente da impressao declarada na versao' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.automation_version_id IS NOT NULL OR NEW.package_fingerprint IS NOT NULL OR NEW.package_fingerprint_rule IS NOT NULL THEN
        RAISE EXCEPTION 'monitoramento externo nao tem versao nem impressao' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.automations a JOIN public.workspaces w ON w.organization_id = a.organization_id AND w.workspace_id = a.workspace_id
                    WHERE a.organization_id = NEW.organization_id AND a.automation_id = NEW.automation_id AND a.workspace_id = NEW.workspace_id
                      AND a.status = 1 AND w.status = 1) THEN
        RAISE EXCEPTION 'automacao ou workspace nao ativo' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.worker_workspace_assignments x JOIN public.workers wk ON wk.organization_id = x.organization_id AND wk.worker_id = x.worker_id
                    WHERE x.organization_id = NEW.organization_id AND x.assignment_id = NEW.worker_workspace_assignment_id
                      AND x.worker_id = NEW.worker_id AND x.workspace_id = NEW.workspace_id
                      AND x.status = 1 AND wk.status = 1) THEN
        RAISE EXCEPTION 'worker nao autorizado no workspace ou nao ativo' USING ERRCODE = '23514';
    END IF;
    -- 033: uma vigente por escopo. A trava do escopo vem ANTES da busca: a busca seguinte (READ COMMITTED) ve o que uma
    -- insercao concorrente do mesmo escopo confirmou enquanto esta esperava.
    PERFORM pg_advisory_xact_lock(hashtextextended(format('nekkora.local_run_authorization|%s|%s|%s', NEW.organization_id,
        NEW.worker_workspace_assignment_id,
        CASE WHEN NEW.source_kind = 3 THEN 'monitoramento|' || NEW.automation_id ELSE 'pacote|' || NEW.automation_version_id END), 33));
    NEW.granted_at := clock_timestamp();
    IF EXISTS (SELECT 1 FROM public.local_run_authorizations z
                WHERE z.organization_id = NEW.organization_id
                  AND z.worker_workspace_assignment_id = NEW.worker_workspace_assignment_id
                  AND z.automation_id = NEW.automation_id
                  AND z.revoked_at IS NULL
                  AND z.valid_until > NEW.granted_at
                  AND CASE WHEN NEW.source_kind = 3 THEN z.source_kind = 3
                           ELSE z.source_kind IN (1, 2) AND z.automation_version_id = NEW.automation_version_id END) THEN
        RAISE EXCEPTION 'ja existe autorizacao local vigente para este escopo' USING ERRCODE = '23505',
            CONSTRAINT = 'ux_local_run_authorizations_vigente';
    END IF;
    IF NEW.valid_until <= NEW.granted_at THEN
        RAISE EXCEPTION 'validade da autorizacao fora de (0, 366 dias]' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- === 3. Invariantes ===
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                    WHERE c.relname = 'ix_local_run_authorizations_vigente' AND i.indisvalid AND i.indpred IS NOT NULL) THEN
        RAISE EXCEPTION '033: indice parcial da vigente ausente ou invalido';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_local_run_authorizations_on_insert'
                    AND tgrelid = 'public.local_run_authorizations'::regclass AND NOT tgisinternal AND tgenabled = 'O') THEN
        RAISE EXCEPTION '033: gatilho de insercao ausente ou desligado';
    END IF;
    IF position('ux_local_run_authorizations_vigente' IN pg_get_functiondef('public.local_run_authorization_on_insert()'::regprocedure)) = 0 THEN
        RAISE EXCEPTION '033: gatilho de insercao sem a regra da vigente';
    END IF;
    IF (SELECT prosecdef FROM pg_proc WHERE oid = 'public.local_run_authorization_on_insert()'::regprocedure) THEN
        RAISE EXCEPTION '033: gatilho de insercao nao pode ser SECURITY DEFINER';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = 'public.local_run_authorizations'::regclass AND relrowsecurity AND relforcerowsecurity) THEN
        RAISE EXCEPTION '033: RLS forcada de local_run_authorizations nao foi religada';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.local_run_authorizations', 'DELETE')
       OR has_table_privilege('nekkora_platform', 'public.local_run_authorizations', 'TRUNCATE')
       OR has_any_column_privilege('nekkora_ingestion', 'public.local_run_authorizations', 'INSERT, UPDATE') THEN
        RAISE EXCEPTION '033: privilegios mudaram';
    END IF;
END;
$$;

COMMIT;
