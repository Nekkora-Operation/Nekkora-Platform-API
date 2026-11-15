-- Nekkora Platform - auditoria da renomeacao de automacao pelo aplicativo desktop (AGENT-CLIENT-NAVEGACAO-001, T01). Contrato:
-- docs/agent-client-navegacao-001/t01-contexto-da-pessoa.md e api/openapi/nekkora-api-v1.yaml
-- (PATCH /api/v1/desktop/organizations/{org}/automations/{automation}).
--
-- CANDIDATA ate a aprovacao do PR. Numero 038 conferido em 13/11 contra a dev (1fe35f5; a maior era 037). As ferramentas do
-- laboratorio e da homologacao exigem numeracao contigua: se outra migration ocupar 038 antes, esta e renumerada sem mudar o
-- conteudo.
--
-- O QUE FAZ: so amplia o CHECK de automation_admin_events.act, de 1-7 para 1-8, com um ato novo:
--   8 = AUTOMATION_RENAMED - a pessoa, com automation.manage no workspace, trocou o nome da automacao.
-- O ato 8 e de uma automacao: automation_id obrigatorio (CHECK automation_admin_events_automation_required, da 031, que ja
-- exige automacao em todo ato fora de 1 e 7) e versao e artefato NULOS (CHECK novo), porque renomear nao toca versao,
-- artefato, manifesto nem impressao. O evento nao guarda o nome antigo nem o novo: so organizacao, ato, vinculo de quem fez,
-- workspace, automacao e correlacao, como os demais.
--
-- O QUE NAO FAZ: nenhuma tabela, coluna, funcao, gatilho, papel, dono, politica ou privilegio novo. A plataforma ja tem
-- UPDATE em automations (012) e INSERT em automation_admin_events (028); updated_at continua pelo gatilho da 005. A sessao
-- do aplicativo desktop continua no Redis (037): nada dela e gravado nesta tabela.
--
-- DADOS EXISTENTES: nenhuma linha e alterada; todo ato gravado ate aqui esta entre 1 e 7 e continua valido.
-- Diagnostico somente leitura, para rodar antes (como migrador ou superusuario; a tabela tem RLS forcada):
--   SELECT pg_get_constraintdef(oid) FROM pg_constraint
--    WHERE conrelid = 'public.automation_admin_events'::regclass AND conname = 'automation_admin_events_act_check';
--   -- esperado: CHECK (((act >= 1) AND (act <= 7)))
--
-- REVERSAO (manual, so enquanto nao houver ato 8 gravado; a tabela e append-only, entao depois disso a reversao exige decisao
-- humana sobre as linhas e nao e oferecida aqui):
--   BEGIN;
--   ALTER TABLE public.automation_admin_events DROP CONSTRAINT automation_admin_events_rename_automation;
--   ALTER TABLE public.automation_admin_events DROP CONSTRAINT automation_admin_events_act_check;
--   ALTER TABLE public.automation_admin_events ADD CONSTRAINT automation_admin_events_act_check CHECK (act BETWEEN 1 AND 7);
--   COMMIT;
--   (o ADD CONSTRAINT falha, sem alterar nada, se ja existir ato 8.) A API anterior nao grava o ato 8.
--
-- SEM ELEVACAO: so ALTER TABLE de constraints e um COMMENT, pelo dono da tabela (como a 037 e o trecho de auditoria da 031).
-- Nenhum papel novo, nenhuma funcao SECURITY DEFINER, nenhum dono novo, nenhum privilegio novo.

BEGIN;

-- === 1. Pre-condicao: 037 aplicada; o CHECK do ato e o da 031 ===
DO $$
BEGIN
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.identity_events'::regclass AND c.conname = 'identity_events_event_type_check')
       IS DISTINCT FROM 'CHECK (((event_type >= 1) AND (event_type <= 23)))' THEN
        RAISE EXCEPTION '038 exige a 037 aplicada';
    END IF;
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.automation_admin_events'::regclass AND c.conname = 'automation_admin_events_act_check')
       IS DISTINCT FROM 'CHECK (((act >= 1) AND (act <= 7)))' THEN
        RAISE EXCEPTION '038: CHECK automation_admin_events_act_check diferente da 031';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint c
                    WHERE c.conrelid = 'public.automation_admin_events'::regclass AND c.conname = 'automation_admin_events_automation_required') THEN
        RAISE EXCEPTION '038: CHECK automation_admin_events_automation_required (031) ausente';
    END IF;
END;
$$;

-- === 2. Ato 8 ===
ALTER TABLE public.automation_admin_events DROP CONSTRAINT automation_admin_events_act_check;
ALTER TABLE public.automation_admin_events ADD CONSTRAINT automation_admin_events_act_check CHECK (act BETWEEN 1 AND 8);
ALTER TABLE public.automation_admin_events ADD CONSTRAINT automation_admin_events_rename_automation
    CHECK (act <> 8 OR (automation_id IS NOT NULL AND automation_version_id IS NULL AND artifact_id IS NULL));
COMMENT ON COLUMN public.automation_admin_events.act IS
    '1=WORKSPACE_CREATED, 2=AUTOMATION_CREATED, 3=VERSION_CREATED, 4=DRAFT_UPDATED, 5=ARTIFACT_RECEIVED, 6=VERSION_PUBLISHED, 7=WORKSPACE_RENAMED, '
    '8=AUTOMATION_RENAMED';

-- === 3. Invariantes: o CHECK novo; nada mais mudou na tabela (continua append-only, com RLS forcada, sem privilegio novo) ===
DO $$
BEGIN
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.automation_admin_events'::regclass AND c.conname = 'automation_admin_events_act_check')
       IS DISTINCT FROM 'CHECK (((act >= 1) AND (act <= 8)))' THEN
        RAISE EXCEPTION '038: CHECK novo diferente do esperado';
    END IF;
    IF NOT (SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = 'public.automation_admin_events'::regclass) THEN
        RAISE EXCEPTION '038: automation_admin_events sem RLS forcada';
    END IF;
    IF (SELECT count(*) FROM pg_trigger WHERE tgrelid = 'public.automation_admin_events'::regclass AND NOT tgisinternal
          AND tgname IN ('trg_automation_admin_events_prevent_update', 'trg_automation_admin_events_prevent_delete',
                         'trg_automation_admin_events_prevent_truncate')) <> 3 THEN
        RAISE EXCEPTION '038: automation_admin_events deixou de ser append-only';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.automation_admin_events', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.automation_admin_events', 'DELETE')
       OR has_table_privilege('nekkora_platform', 'public.automation_admin_events', 'TRUNCATE') THEN
        RAISE EXCEPTION '038: a plataforma ganhou escrita alem do INSERT em automation_admin_events';
    END IF;
END;
$$;

COMMIT;
