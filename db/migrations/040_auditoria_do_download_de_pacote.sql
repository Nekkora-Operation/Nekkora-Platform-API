-- Nekkora Platform - auditoria do download do pacote de uma versao publicada pelo aplicativo desktop
-- (AGENT-CLIENT-PACOTES-001, T03). Contrato: docs/agent-client-pacotes-001/t03-download-do-pacote.md e
-- api/openapi/nekkora-api-v1.yaml (GET /api/v1/desktop/organizations/{org}/automations/{automation}/versions/{version}/package).
--
-- CANDIDATA ate a aprovacao do PR. Numero 040 conferido em 14/11 contra a dev (12d320c; a maior era 038) e os PRs abertos
-- (API #61, que traz a 039). ESTE PR NASCE DO #61: a 040 exige a 039. As ferramentas do laboratorio e da homologacao exigem
-- numeracao contigua: se a ordem de integracao mudar, esta migration e renumerada sem mudar o conteudo.
--
-- O QUE FAZ: so amplia o CHECK de automation_admin_events.act, de 1-8 para 1-9, com o ato novo
--   9 = PACKAGE_DOWNLOADED - a pessoa (pelo aplicativo desktop, com o escopo de pacotes) recebeu a autorizacao para baixar o
--       pacote de uma versao PUBLICADA. A linha leva quem (actor_membership_id), o workspace, a automacao, a versao e o
--       artefato, o instante e a correlacao - como os demais atos. Nao leva token, URL, hash do conteudo nem conteudo.
-- e um CHECK que obriga o ato 9 a dizer a automacao, a versao e o artefato.
--
-- O QUE NAO FAZ: nenhuma tabela, coluna, funcao, papel, dono, politica ou privilegio novo; nenhuma permissao nova no catalogo
-- de papeis (o download usa automation.execute ou automation.manage, que ja existem). A plataforma grava o ato com o INSERT
-- que a 028 ja concedeu a nekkora_platform. O download pela Web (GET /o/{org}/automation-versions/{v}/artifact/content, 028)
-- nao muda e continua sem este ato.
--
-- DADOS EXISTENTES: nenhuma linha e alterada; todo ato gravado ate aqui esta entre 1 e 8 e continua valido.
-- Diagnostico somente leitura, para rodar antes (como migrador ou superusuario; a tabela tem RLS forcada):
--   SELECT pg_get_constraintdef(oid) FROM pg_constraint
--    WHERE conrelid = 'public.automation_admin_events'::regclass AND conname = 'automation_admin_events_act_check';
--   -- esperado: CHECK (((act >= 1) AND (act <= 8)))
--
-- REVERSAO (manual, so enquanto nao houver ato 9 gravado; a tabela e append-only, entao depois disso a reversao exige decisao
-- humana sobre as linhas e nao e oferecida aqui):
--   BEGIN;
--   ALTER TABLE public.automation_admin_events DROP CONSTRAINT automation_admin_events_package_download;
--   ALTER TABLE public.automation_admin_events DROP CONSTRAINT automation_admin_events_act_check;
--   ALTER TABLE public.automation_admin_events ADD CONSTRAINT automation_admin_events_act_check CHECK (act BETWEEN 1 AND 8);
--   COMMIT;
--   (o ADD CONSTRAINT falha, sem alterar nada, se ja existir ato 9.) A API anterior nao grava o ato 9.
--
-- SEM ELEVACAO: so ALTER TABLE de constraints e um COMMENT, pelo dono da tabela (como a 037 e a 038).
-- Nenhum papel novo, nenhuma funcao SECURITY DEFINER, nenhum dono novo, nenhum privilegio novo.

BEGIN;

-- === 1. Pre-condicao: 039 aplicada; o CHECK do ato e o da 038 ===
DO $$
BEGIN
    IF to_regprocedure('public.identity_session_organizations(uuid, uuid, integer)') IS NULL THEN
        RAISE EXCEPTION '040 exige a 039 aplicada';
    END IF;
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.automation_admin_events'::regclass AND c.conname = 'automation_admin_events_act_check')
       IS DISTINCT FROM 'CHECK (((act >= 1) AND (act <= 8)))' THEN
        RAISE EXCEPTION '040: CHECK automation_admin_events_act_check diferente da 038';
    END IF;
END;
$$;

-- === 2. Ato 9 ===
ALTER TABLE public.automation_admin_events DROP CONSTRAINT automation_admin_events_act_check;
ALTER TABLE public.automation_admin_events ADD CONSTRAINT automation_admin_events_act_check CHECK (act BETWEEN 1 AND 9);
ALTER TABLE public.automation_admin_events ADD CONSTRAINT automation_admin_events_package_download
    CHECK (act <> 9 OR (automation_id IS NOT NULL AND automation_version_id IS NOT NULL AND artifact_id IS NOT NULL));
COMMENT ON COLUMN public.automation_admin_events.act IS
    '1=WORKSPACE_CREATED, 2=AUTOMATION_CREATED, 3=VERSION_CREATED, 4=DRAFT_UPDATED, 5=ARTIFACT_RECEIVED, 6=VERSION_PUBLISHED, 7=WORKSPACE_RENAMED, '
    '8=AUTOMATION_RENAMED, 9=PACKAGE_DOWNLOADED';

-- === 3. Invariantes: o CHECK novo; nada mais mudou na tabela (continua append-only, com RLS forcada, sem privilegio novo) ===
DO $$
BEGIN
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.automation_admin_events'::regclass AND c.conname = 'automation_admin_events_act_check')
       IS DISTINCT FROM 'CHECK (((act >= 1) AND (act <= 9)))' THEN
        RAISE EXCEPTION '040: CHECK novo diferente do esperado';
    END IF;
    IF NOT (SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = 'public.automation_admin_events'::regclass) THEN
        RAISE EXCEPTION '040: automation_admin_events sem RLS forcada';
    END IF;
    IF (SELECT count(*) FROM pg_trigger WHERE tgrelid = 'public.automation_admin_events'::regclass AND NOT tgisinternal
          AND tgname IN ('trg_automation_admin_events_prevent_update', 'trg_automation_admin_events_prevent_delete',
                         'trg_automation_admin_events_prevent_truncate')) <> 3 THEN
        RAISE EXCEPTION '040: automation_admin_events deixou de ser append-only';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.automation_admin_events', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.automation_admin_events', 'DELETE')
       OR has_table_privilege('nekkora_platform', 'public.automation_admin_events', 'TRUNCATE') THEN
        RAISE EXCEPTION '040: a plataforma ganhou escrita alem do INSERT em automation_admin_events';
    END IF;
END;
$$;

COMMIT;
