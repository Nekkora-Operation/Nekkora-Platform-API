-- Nekkora Platform - reconciliacao de uma tentativa com "resultado nao confirmado": pela MAQUINA, ou DECLARADA por uma pessoa
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, decisoes de 16/11). Contrato: docs/platform-client-execucao-remota-001/reconciliacao-da-tentativa.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 046 e da 053. Numero 058 conferido em 16/11 contra a dev e os PRs abertos.
--
-- DECISOES DA TITULAR
--   - Um novo contato NAO torna a maquina livre. Se ela ainda executa, esta OCUPADA; se nao confirma, a ocupacao e INCERTA.
--   - A repeticao fica BLOQUEADA enquanto nao houver confirmacao de termino e de ausencia de processo ativo - qualquer que seja a
--     causa (comunicacao perdida, revogacao, reinicio do aplicativo). Reconhecer o risco, revogar o vinculo ou esperar um prazo
--     NAO bastam.
--   - Duas reconciliacoes, e so elas:
--       1 = MACHINE  a propria maquina confirma que o processo daquela tentativa nao esta mais em execucao;
--       2 = MANUAL   uma pessoa ORG_ADMIN da propria organizacao DECLARA, com motivo e descricao da verificacao que fez.
--   - A reconciliacao manual libera uma NOVA tentativa da solicitacao; nao executa nada e nao libera a maquina antiga, que so
--     deixa de estar indisponivel quando ELA confirmar. O resultado anterior continua "nao confirmado": nada de sucesso, falha
--     ou codigo de saida e inventado.
--   - Evidencia tardia da maquina fica preservada AO LADO da decisao manual: sao linhas diferentes, e nenhuma e alterada.
--
-- O QUE FAZ
--   remote_attempt_process_reports: o que se sabe do PROCESSO de uma tentativa que terminou como "resultado nao confirmado"
--   (estado 13). No maximo uma linha por tentativa e tipo:
--       1 = ABSENT_BY_MACHINE   a maquina confirmou: nao ha processo desta tentativa em execucao nela
--       2 = MANUAL              declaracao de uma pessoa ORG_ADMIN, com motivo, descricao da verificacao, autor e data
--       3 = RUNNING_BY_MACHINE  a maquina voltou e disse que AINDA executa esta tentativa (a primeira vez em que disse)
--   O banco confere: a tentativa esta no estado 13 e e desta organizacao; a maquina gravada e a da tentativa; os tipos 1 e 3 so
--   pelo canal das maquinas; o tipo 2 so pela Platform, por quem e ORG_ADMIN efetivo da organizacao, com motivo de uma lista
--   fechada e descricao de 20 a 2000 caracteres. Nada e alterado nem apagado.
--
-- O QUE NAO FAZ: nao muda a tentativa (estado, causa, codigo de saida, relato tardio); nao cria tentativa; nao executa nada.
--
-- DADOS EXISTENTES: nenhuma linha e alterada. Tentativas que ja estavam no estado 13 ficam SEM reconciliacao: a repeticao delas
-- e a maquina delas ficam bloqueadas ate uma das duas reconciliacoes - e o comportamento decidido.
--
-- REVERSAO (manual; so enquanto a tabela estiver vazia):
--   BEGIN; DROP TABLE public.remote_attempt_process_reports; DROP FUNCTION public.remote_attempt_process_report_on_insert(); COMMIT;
--
-- SEM ELEVACAO: tabela nova, gatilho SECURITY INVOKER. Nenhum papel novo, nenhuma funcao SECURITY DEFINER.

BEGIN;

DO $$
BEGIN
    IF to_regclass('public.remote_attempts') IS NULL
       OR NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'public.remote_attempts'::regclass AND attname = 'closure_cause' AND NOT attisdropped) THEN
        RAISE EXCEPTION '058 exige a 046 e a 053 aplicadas';
    END IF;
    IF to_regclass('public.remote_attempt_process_reports') IS NOT NULL THEN
        RAISE EXCEPTION '058: remote_attempt_process_reports ja existe';
    END IF;
END;
$$;

CREATE TABLE public.remote_attempt_process_reports (
    attempt_id        UUID NOT NULL,
    kind              SMALLINT NOT NULL CHECK (kind IN (1, 2, 3)),
    organization_id   UUID NOT NULL,
    agent_id          UUID NOT NULL,
    reported_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    reason            TEXT CHECK (reason IN ('MACHINE_OFF_OUT_OF_OPERATION', 'PROCESS_ENDED_BY_ADMINISTRATOR', 'OTHER_VERIFIED')),
    verification      TEXT CHECK (char_length(verification) BETWEEN 20 AND 2000 AND verification !~ '[\x01-\x08\x0b\x0c\x0e-\x1f\x7f]'),
    by_membership_id  UUID,
    PRIMARY KEY (attempt_id, kind),
    FOREIGN KEY (organization_id, attempt_id) REFERENCES public.remote_attempts (organization_id, attempt_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, by_membership_id) REFERENCES public.organization_memberships (organization_id, membership_id) ON DELETE RESTRICT,
    CHECK ((kind = 2) = (reason IS NOT NULL) AND (kind = 2) = (verification IS NOT NULL) AND (kind = 2) = (by_membership_id IS NOT NULL))
);
CREATE INDEX ix_remote_attempt_process_reports_agent ON public.remote_attempt_process_reports (organization_id, agent_id, kind);
COMMENT ON TABLE public.remote_attempt_process_reports IS
    'O que se sabe do processo de uma tentativa com resultado nao confirmado (058): 1 = a maquina confirmou que nao ha processo; '
    '2 = declaracao manual de um ORG_ADMIN, com motivo e verificacao; 3 = a maquina disse que ainda executa. Nunca alterado nem apagado.';
COMMENT ON COLUMN public.remote_attempt_process_reports.reason IS
    'So na declaracao manual: MACHINE_OFF_OUT_OF_OPERATION (computador desligado e mantido fora de operacao), '
    'PROCESS_ENDED_BY_ADMINISTRATOR (processo encerrado por quem administra a maquina), OTHER_VERIFIED (outra verificacao, descrita)';

CREATE FUNCTION public.remote_attempt_process_report_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    t RECORD;
BEGIN
    NEW.reported_at := clock_timestamp();
    SELECT a.state, a.agent_id INTO t FROM public.remote_attempts a WHERE a.organization_id = NEW.organization_id AND a.attempt_id = NEW.attempt_id;
    IF NOT FOUND OR t.state <> 13 THEN
        RAISE EXCEPTION 'so tentativa com resultado nao confirmado tem reconciliacao' USING ERRCODE = '23514';
    END IF;
    -- a maquina e a da tentativa, nao a que quem insere disser
    NEW.agent_id := t.agent_id;
    IF NEW.kind IN (1, 3) THEN
        IF current_user <> 'nekkora_ingestion' THEN
            RAISE EXCEPTION 'so a maquina informa o que ela mesma executa' USING ERRCODE = '42501';
        END IF;
        IF NEW.kind = 3 AND EXISTS (SELECT 1 FROM public.remote_attempt_process_reports r WHERE r.attempt_id = NEW.attempt_id AND r.kind = 1) THEN
            RAISE EXCEPTION 'a maquina ja confirmou que nao ha processo' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF current_user <> 'nekkora_platform' THEN
            RAISE EXCEPTION 'a declaracao manual e de uma pessoa, pela Platform' USING ERRCODE = '42501';
        END IF;
        -- ORG_ADMIN efetivo DESTA organizacao: atribuicao ativa, vinculo ativo, conta ativa
        IF NOT EXISTS (SELECT 1 FROM public.organization_role_assignments a
                         JOIN public.organization_memberships m ON m.organization_id = a.organization_id AND m.membership_id = a.membership_id
                         JOIN public.users u ON u.organization_id = m.organization_id AND u.user_id = m.user_id
                         JOIN public.roles r ON r.role_id = a.role_id
                        WHERE a.organization_id = NEW.organization_id AND a.membership_id = NEW.by_membership_id AND a.status = 1 AND m.status = 2
                          AND u.status = 2 AND r.code = 'ORG_ADMIN') THEN
            RAISE EXCEPTION 'a declaracao manual e restrita a ORG_ADMIN da organizacao' USING ERRCODE = '42501';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempt_process_reports_on_insert BEFORE INSERT ON public.remote_attempt_process_reports
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_process_report_on_insert();
CREATE TRIGGER trg_remote_attempt_process_reports_prevent_update BEFORE UPDATE ON public.remote_attempt_process_reports
    FOR EACH ROW EXECUTE FUNCTION public.prevent_update();
CREATE TRIGGER trg_remote_attempt_process_reports_prevent_delete BEFORE DELETE ON public.remote_attempt_process_reports
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_remote_attempt_process_reports_prevent_truncate BEFORE TRUNCATE ON public.remote_attempt_process_reports
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

ALTER TABLE public.remote_attempt_process_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.remote_attempt_process_reports FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_request_context ON public.remote_attempt_process_reports AS PERMISSIVE FOR ALL TO nekkora_platform, nekkora_ingestion
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid);

REVOKE ALL ON public.remote_attempt_process_reports FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_process_report_on_insert() FROM PUBLIC;
GRANT SELECT, INSERT ON public.remote_attempt_process_reports TO nekkora_platform, nekkora_ingestion;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = 'public.remote_attempt_process_reports'::regclass AND c.relrowsecurity AND c.relforcerowsecurity) THEN
        RAISE EXCEPTION '058: RLS ausente';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.remote_attempt_process_reports', 'UPDATE, DELETE')
       OR has_table_privilege('nekkora_ingestion', 'public.remote_attempt_process_reports', 'UPDATE, DELETE') THEN
        RAISE EXCEPTION '058: privilegio alem do previsto na reconciliacao';
    END IF;
END;
$$;

COMMIT;
