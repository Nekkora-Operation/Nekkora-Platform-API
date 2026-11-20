-- Nekkora Platform - PLATFORM-FLUXO-002: renovacao das autorizacoes da ativacao da automacao, sem estender nem apagar nada.
-- Contrato: api/openapi/nekkora-api-v1.yaml (POST /api/v1/o/{org}/automation-activations/{automationId}/renewal) e
-- docs/platform-fluxo-002/decisoes.md.
--
-- CANDIDATA ate a aprovacao do PR. Numero 069 conferido em 20/11/2026 contra a dev (4f518bc; a maior era 068). As
-- ferramentas do laboratorio e da homologacao exigem numeracao contigua: se outra migration ocupar 069 antes, esta e
-- renumerada sem mudar o conteudo.
--
-- POR QUE: uma autorizacao local e imutavel (025) e nunca e estendida (D3, 064); no maximo uma VIGENTE por escopo (033).
-- Depois de a licenca ser renovada sem lacuna, a autorizacao da maquina ainda vigente termina no fim ANTIGO da cobertura e
-- impede emitir outra para o mesmo escopo. "Renovar autorizacoes" (decisao da titular, 20/11/2026) emite uma autorizacao
-- NOVA ate o fim da cobertura atual e, se a anterior ainda vigora, a revoga NA MESMA TRANSACAO, marcada como SUBSTITUIDA
-- pela renovacao. O historico fica inteiro: a anterior continua existindo, revogada, com o motivo; a nova aponta para ela.
-- Uma execucao iniciada sob a anterior continua aceita (064, D8: a autorizacao precisa valer no INICIO).
--
-- O QUE MUDA
--   1. local_run_authorizations.revocation_reason: NULL enquanto nao revogada; 1 = revogada por uma pessoa (o que existia);
--      2 = substituida por uma renovacao. As revogadas existentes recebem 1 (eram todas manuais). Uma revogada SEM motivo
--      gravado (so possivel contornando o gatilho) conta como revogada por pessoa: nunca e renovada.
--   2. local_run_authorizations.renewal_of_authorization_id: na autorizacao NOVA, a que ela renova. A anterior tem de ser do
--      MESMO escopo (organizacao, autorizacao do Worker no workspace, automacao, versao, origem e pacote), emitida antes, e
--      estar vencida sem revogacao ou substituida (motivo 2). Revogada por uma pessoa NUNCA e renovada (a decisao humana
--      vale). Cada autorizacao e renovada no maximo uma vez (indice unico): repetir o clique nao duplica.
--   3. O gatilho de atualizacao da 025 passa a aceitar o motivo junto da revogacao (e so ele); a revogacao continua unica.
--
-- O QUE NAO MUDA: as regras de emissao (025/030/033/064) valem para a nova como para qualquer outra; nada e estendido; o
-- canal das maquinas continua lendo as mesmas colunas (as novas nao sao lidas por ele).
--
-- DADOS EXISTENTES: as revogadas recebem revocation_reason = 1. Nenhuma outra linha muda.
--
-- REVERSAO (manual; so enquanto nenhuma renovacao tiver sido feita, ou aceitando perder o motivo 2 e o vinculo):
--   BEGIN;
--   DROP TRIGGER trg_local_run_authorizations_renewal ON public.local_run_authorizations;
--   DROP FUNCTION public.local_run_authorization_renewal_on_insert();
--   -- recriar public.local_run_authorization_on_update() com o corpo da 025
--   REVOKE UPDATE (revocation_reason) ON public.local_run_authorizations FROM nekkora_platform;
--   ALTER TABLE public.local_run_authorizations DROP COLUMN renewal_of_authorization_id, DROP COLUMN revocation_reason;
--   COMMIT;
--
-- SEM ELEVACAO: duas colunas, um indice, uma funcao e um gatilho SECURITY INVOKER do proprio dono, um privilegio de coluna
-- para um papel existente. Nenhum papel novo, nenhuma funcao SECURITY DEFINER.

BEGIN;

-- === 0. Pre-condicoes ===
DO $$
BEGIN
    IF to_regprocedure('public.license_run_basis(uuid, uuid, uuid, timestamptz)') IS NULL
       OR to_regclass('public.ix_local_run_authorizations_vigente') IS NULL THEN
        RAISE EXCEPTION '069 exige a 033 e a 064 aplicadas';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'local_run_authorizations'
                AND column_name = 'revocation_reason') THEN
        RAISE EXCEPTION '069: ja aplicada';
    END IF;
END;
$$;

-- === 1. Colunas ===
ALTER TABLE public.local_run_authorizations
    ADD COLUMN revocation_reason SMALLINT CHECK (revocation_reason IN (1, 2)),
    ADD COLUMN renewal_of_authorization_id UUID;
ALTER TABLE public.local_run_authorizations NO FORCE ROW LEVEL SECURITY;
ALTER TABLE public.local_run_authorizations DISABLE TRIGGER trg_local_run_authorizations_on_update;
UPDATE public.local_run_authorizations SET revocation_reason = 1 WHERE revoked_at IS NOT NULL;
ALTER TABLE public.local_run_authorizations ENABLE TRIGGER trg_local_run_authorizations_on_update;
ALTER TABLE public.local_run_authorizations FORCE ROW LEVEL SECURITY;
ALTER TABLE public.local_run_authorizations ADD CONSTRAINT fk_local_run_authorizations_renewal_of
    FOREIGN KEY (renewal_of_authorization_id) REFERENCES public.local_run_authorizations (authorization_id);
CREATE UNIQUE INDEX ux_local_run_authorizations_renewal_of ON public.local_run_authorizations (renewal_of_authorization_id)
    WHERE renewal_of_authorization_id IS NOT NULL;
COMMENT ON COLUMN public.local_run_authorizations.revocation_reason IS
    '069: NULL = nao revogada; 1 = revogada por uma pessoa; 2 = substituida por uma renovacao (renewal_of na nova)';
COMMENT ON COLUMN public.local_run_authorizations.renewal_of_authorization_id IS
    '069: a autorizacao do MESMO escopo que esta renova (vencida sem revogacao ou substituida); no maximo uma renovacao por autorizacao';

-- === 2. Revogacao: o motivo acompanha a revogacao (a 025 so aceitava revoked_at e quem revogou) ===
CREATE OR REPLACE FUNCTION public.local_run_authorization_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'autorizacao revogada e imutavel (authorization_id=%)', OLD.authorization_id USING ERRCODE = '23514';
    END IF;
    IF (to_jsonb(NEW) - 'revoked_at' - 'revoked_by_membership_id' - 'revocation_reason')
           <> (to_jsonb(OLD) - 'revoked_at' - 'revoked_by_membership_id' - 'revocation_reason')
       OR NEW.revoked_at IS NULL THEN
        RAISE EXCEPTION 'unica mudanca da autorizacao: revogacao' USING ERRCODE = '23514';
    END IF;
    NEW.revoked_at := clock_timestamp();
    -- (069) sem motivo informado, e a revogacao por uma pessoa, como sempre foi
    NEW.revocation_reason := coalesce(NEW.revocation_reason, 1);
    IF NOT public.agent_workspace_permission(NEW.organization_id, NEW.revoked_by_membership_id, NEW.workspace_id, 'automation.execute') THEN
        RAISE EXCEPTION 'sem automation.execute no workspace' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

-- === 3. A autorizacao nova que renova outra: mesmo escopo, anterior vencida ou substituida, nunca revogada por pessoa ===
CREATE FUNCTION public.local_run_authorization_renewal_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    p public.local_run_authorizations%ROWTYPE;
BEGIN
    IF NEW.renewal_of_authorization_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT * INTO p FROM public.local_run_authorizations z
     WHERE z.organization_id = NEW.organization_id AND z.authorization_id = NEW.renewal_of_authorization_id;
    IF NOT FOUND
       OR p.worker_workspace_assignment_id <> NEW.worker_workspace_assignment_id OR p.worker_id <> NEW.worker_id
       OR p.workspace_id <> NEW.workspace_id OR p.automation_id <> NEW.automation_id OR p.source_kind <> NEW.source_kind
       OR p.automation_version_id IS DISTINCT FROM NEW.automation_version_id OR p.package_fingerprint IS DISTINCT FROM NEW.package_fingerprint
       OR p.package_fingerprint_rule IS DISTINCT FROM NEW.package_fingerprint_rule THEN
        RAISE EXCEPTION 'RENEWAL_SCOPE: a renovacao e do mesmo escopo da autorizacao anterior' USING ERRCODE = '23514';
    END IF;
    -- revogada sem ser por substituicao (motivo 1, ou sem motivo gravado) e decisao humana: nunca e renovada
    IF p.revoked_at IS NOT NULL AND p.revocation_reason IS DISTINCT FROM 2 THEN
        RAISE EXCEPTION 'RENEWAL_REVOKED: autorizacao revogada por uma pessoa nao e renovada' USING ERRCODE = '23514';
    END IF;
    IF p.revoked_at IS NULL AND p.valid_until > clock_timestamp() THEN
        RAISE EXCEPTION 'RENEWAL_STILL_VALID: a anterior ainda vigora; substitua-a na mesma transacao' USING ERRCODE = '23514';
    END IF;
    IF NEW.valid_until <= p.valid_until THEN
        RAISE EXCEPTION 'RENEWAL_NOT_LONGER: a renovacao vai alem da anterior' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_local_run_authorizations_renewal BEFORE INSERT ON public.local_run_authorizations
    FOR EACH ROW EXECUTE FUNCTION public.local_run_authorization_renewal_on_insert();

-- === 4. Privilegio: a Platform grava o motivo junto da revogacao (a nova coluna da insercao ja e coberta pelo INSERT) ===
GRANT UPDATE (revocation_reason) ON public.local_run_authorizations TO nekkora_platform;

-- === 5. Invariantes ===
DO $$
BEGIN
    IF has_column_privilege('nekkora_platform', 'public.local_run_authorizations', 'renewal_of_authorization_id', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.local_run_authorizations', 'valid_until', 'UPDATE') THEN
        RAISE EXCEPTION '069: a Platform nao altera o vinculo de renovacao nem a validade';
    END IF;
END;
$$;

COMMIT;
