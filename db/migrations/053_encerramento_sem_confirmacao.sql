-- Nekkora Platform - a tentativa de execucao remota nunca fica "em execucao" para sempre
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, fechamento). Contrato: docs/platform-client-execucao-remota-001/encerramento-sem-confirmacao.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 046 (tentativas) e da 049 (motivos de recusa). Numero 053 conferido em 16/11
-- contra a dev e os PRs abertos.
--
-- O PROBLEMA: uma tentativa recebida pela maquina so mudava de estado quando a PROPRIA maquina informava. Se o vinculo da
-- maquina fosse encerrado (pela maquina ou por quem administra) ou se ela nunca mais se comunicasse, a tentativa ficava
-- "Em execucao" para sempre, ocupando a solicitacao.
--
-- O QUE FAZ
--   1. remote_attempts.closure_cause: POR QUE o SERVIDOR encerrou uma tentativa que a maquina ja tinha recebido:
--        MACHINE_UNLINKED    a maquina encerrou o proprio vinculo
--        MACHINE_REVOKED     quem administra revogou a maquina
--        COMMUNICATION_LOST  a maquina deixou de se comunicar por mais que o prazo configurado
--      Nulo: o desfecho foi informado pela maquina (ou a tentativa terminou antes de ser recebida).
--   2. O desfecho de um encerramento pelo servidor diz so o que o servidor SABE:
--        - tentativa que o servidor nunca autorizou a iniciar (estados 1 a 4): REFUSED, com o motivo MACHINE_NOT_LINKED ou
--          COMMUNICATION_LOST (motivo novo). O inicio depende da resposta do servidor ao pedido STARTING; sem ela, nao iniciou.
--        - tentativa autorizada a iniciar ou em execucao (5 e 6): ENDED_UNKNOWN. Nunca sucesso, falha nem cancelamento.
--   3. O banco CONFERE o fato antes de aceitar a causa: vinculo encerrado = a maquina nao esta mais ativa e associada ao
--      Worker da tentativa; comunicacao perdida = o prazo da comunicacao (lease) venceu. O tamanho da espera depois do
--      vencimento e configuracao da aplicacao.
--   4. Relato tardio: se a maquina voltar depois de um encerramento por COMMUNICATION_LOST e informar como o processo
--      terminou, isso e guardado AO LADO (late_state, late_exit_code, late_reported_at). O estado da tentativa NAO muda: uma
--      nova tentativa pode ja ter sido pedida. Uma vez so.
--   5. As pessoas (nekkora_platform) passam a poder gravar ESSES encerramentos, e so eles, alem do que ja podiam (encerrar o
--      que a maquina ainda nao recebeu).
--
-- O QUE NAO FAZ: nao troca de maquina, nao cria tentativa nova, nao cancela processo nenhum (isso e do aplicativo da maquina);
-- nao altera registros nem resultados ja recebidos, que continuam aceitos depois do encerramento, com o token da atribuicao.
--
-- DADOS EXISTENTES: nenhuma linha e alterada por esta migration. As colunas novas nascem nulas. Tentativas que ja estejam
-- paradas (maquina desvinculada ou sem comunicacao) sao encerradas pela aplicacao, na primeira leitura, pelas mesmas regras.
--
-- REVERSAO (manual; so enquanto nenhuma tentativa tiver closure_cause nem motivo COMMUNICATION_LOST):
--   BEGIN;
--   ALTER TABLE public.remote_attempts DROP CONSTRAINT remote_attempts_closure_check;
--   ALTER TABLE public.remote_attempts DROP CONSTRAINT remote_attempts_late_check;
--   ALTER TABLE public.remote_attempts DROP COLUMN closure_cause, DROP COLUMN late_state, DROP COLUMN late_exit_code, DROP COLUMN late_reported_at;
--   ALTER TABLE public.remote_attempts DROP CONSTRAINT remote_attempts_refusal_reason_check;
--   ALTER TABLE public.remote_attempts ADD CONSTRAINT remote_attempts_refusal_reason_check CHECK (refusal_reason IN (
--       'BUSY', 'PAUSED', 'PACKAGE_INVALID', 'ENVIRONMENT_GONE', 'ENVIRONMENT_INCOMPATIBLE', 'DEPENDENCIES_MISSING', 'DISK_PRESSURE',
--       'CLIENT_INCOMPATIBLE', 'SESSION_UNAVAILABLE', 'AUTHORIZATION_MISSING', 'AUTHORIZATION_REVOKED', 'AUTHORIZATION_EXPIRED',
--       'WORKER_NOT_AUTHORIZED', 'MACHINE_NOT_LINKED', 'MACHINE_NOT_ENABLED', 'MACHINE_INCOMPATIBLE'));
--   -- e recriar public.remote_attempt_on_update() com o corpo da 046
--   REVOKE UPDATE (refusal_reason, closure_cause) ON public.remote_attempts FROM nekkora_platform;
--   COMMIT;
--
-- SEM ELEVACAO: colunas e constraints em tabela do dono das migrations desta missao, funcao de gatilho SECURITY INVOKER
-- substituida. Nenhum papel novo, nenhuma funcao SECURITY DEFINER.

BEGIN;

-- === 1. Pre-condicao ===
DO $$
BEGIN
    IF to_regclass('public.remote_attempts') IS NULL
       OR NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conrelid = 'public.remote_attempts'::regclass AND c.conname = 'remote_attempts_refusal_reason_check') THEN
        RAISE EXCEPTION '053 exige a 046 e a 049 aplicadas';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'remote_attempts' AND column_name = 'closure_cause') THEN
        RAISE EXCEPTION '053: remote_attempts.closure_cause ja existe';
    END IF;
END;
$$;

-- === 2. Colunas e regras ===
ALTER TABLE public.remote_attempts
    ADD COLUMN closure_cause    TEXT CHECK (closure_cause IN ('MACHINE_UNLINKED', 'MACHINE_REVOKED', 'COMMUNICATION_LOST')),
    ADD COLUMN late_state       SMALLINT CHECK (late_state IN (7, 8, 9, 10, 13)),
    ADD COLUMN late_exit_code   INTEGER,
    ADD COLUMN late_reported_at TIMESTAMPTZ;

ALTER TABLE public.remote_attempts DROP CONSTRAINT remote_attempts_refusal_reason_check;
ALTER TABLE public.remote_attempts ADD CONSTRAINT remote_attempts_refusal_reason_check CHECK (refusal_reason IN (
    'BUSY', 'PAUSED', 'PACKAGE_INVALID', 'ENVIRONMENT_GONE', 'ENVIRONMENT_INCOMPATIBLE', 'DEPENDENCIES_MISSING', 'DISK_PRESSURE',
    'CLIENT_INCOMPATIBLE', 'SESSION_UNAVAILABLE', 'AUTHORIZATION_MISSING', 'AUTHORIZATION_REVOKED', 'AUTHORIZATION_EXPIRED',
    'WORKER_NOT_AUTHORIZED', 'MACHINE_NOT_LINKED', 'MACHINE_NOT_ENABLED', 'MACHINE_INCOMPATIBLE', 'COMMUNICATION_LOST'));

-- o servidor so encerra como "nao iniciou" (11) ou "sem confirmacao" (13); o motivo do 11 e o da causa
ALTER TABLE public.remote_attempts ADD CONSTRAINT remote_attempts_closure_check CHECK (
    closure_cause IS NULL
    OR state = 13
    OR (state = 11 AND refusal_reason = CASE WHEN closure_cause = 'COMMUNICATION_LOST' THEN 'COMMUNICATION_LOST' ELSE 'MACHINE_NOT_LINKED' END));
-- COMMUNICATION_LOST como motivo de recusa so existe com a causa (a maquina nao o informa)
ALTER TABLE public.remote_attempts ADD CONSTRAINT remote_attempts_lost_reason_check CHECK (
    refusal_reason IS DISTINCT FROM 'COMMUNICATION_LOST' OR closure_cause = 'COMMUNICATION_LOST');
ALTER TABLE public.remote_attempts ADD CONSTRAINT remote_attempts_late_check CHECK (
    (late_state IS NULL) = (late_reported_at IS NULL)
    AND (late_state IS NULL OR (state = 13 AND closure_cause = 'COMMUNICATION_LOST'))
    AND (late_exit_code IS NULL OR late_state IN (7, 8, 10)));

COMMENT ON COLUMN public.remote_attempts.closure_cause IS
    'Por que o SERVIDOR encerrou a tentativa (053): MACHINE_UNLINKED, MACHINE_REVOKED ou COMMUNICATION_LOST. Nulo: o desfecho veio da maquina.';
COMMENT ON COLUMN public.remote_attempts.late_state IS
    'O que a maquina informou DEPOIS de um encerramento por COMMUNICATION_LOST (053). Fica ao lado: o estado da tentativa nao muda.';

-- === 3. Transicoes ===
CREATE OR REPLACE FUNCTION public.remote_attempt_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_linked BOOLEAN;
BEGIN
    IF NEW.attempt_id <> OLD.attempt_id OR NEW.organization_id <> OLD.organization_id OR NEW.remote_execution_id <> OLD.remote_execution_id
       OR NEW.attempt_number <> OLD.attempt_number OR NEW.agent_id <> OLD.agent_id OR NEW.worker_id <> OLD.worker_id
       OR NEW.authorization_id <> OLD.authorization_id OR NEW.created_by_membership_id <> OLD.created_by_membership_id
       OR NEW.created_at <> OLD.created_at OR NEW.offer_valid_until <> OLD.offer_valid_until THEN
        RAISE EXCEPTION 'a identidade da tentativa nao muda' USING ERRCODE = '42501';
    END IF;
    IF OLD.state >= 7 THEN
        -- a unica mudanca numa tentativa terminada: o relato tardio, uma vez, depois de um encerramento por comunicacao perdida
        IF OLD.state = 13 AND OLD.closure_cause = 'COMMUNICATION_LOST' AND OLD.late_state IS NULL AND NEW.late_state IS NOT NULL
           AND current_user <> 'nekkora_platform'
           AND (to_jsonb(NEW) - 'late_state' - 'late_exit_code' - 'late_reported_at') = (to_jsonb(OLD) - 'late_state' - 'late_exit_code' - 'late_reported_at') THEN
            NEW.late_reported_at := clock_timestamp();
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'tentativa terminada nao muda' USING ERRCODE = '23514';
    END IF;
    IF NEW.late_state IS NOT NULL OR NEW.late_exit_code IS NOT NULL OR NEW.late_reported_at IS NOT NULL THEN
        RAISE EXCEPTION 'relato tardio so depois de um encerramento por comunicacao perdida' USING ERRCODE = '23514';
    END IF;
    IF NEW.epoch < OLD.epoch THEN
        RAISE EXCEPTION 'a epoca da atribuicao so cresce' USING ERRCODE = '23514';
    END IF;
    IF NEW.state = OLD.state AND NEW.closure_cause IS NOT NULL THEN
        RAISE EXCEPTION 'a causa do encerramento so e gravada no encerramento' USING ERRCODE = '23514';
    END IF;
    IF NEW.state <> OLD.state THEN
        IF NEW.closure_cause IS NOT NULL THEN
            -- ENCERRAMENTO PELO SERVIDOR: so "nao iniciou" (do que o servidor nunca autorizou a iniciar) ou "sem confirmacao"
            IF NOT ((OLD.state IN (1, 2, 3, 4) AND NEW.state = 11) OR (OLD.state IN (5, 6) AND NEW.state = 13)) THEN
                RAISE EXCEPTION 'encerramento pelo servidor nao permitido: % -> %', OLD.state, NEW.state USING ERRCODE = '23514';
            END IF;
            IF NEW.exit_code IS NOT NULL THEN
                RAISE EXCEPTION 'encerramento pelo servidor nao tem codigo de saida' USING ERRCODE = '23514';
            END IF;
            IF NEW.closure_cause = 'COMMUNICATION_LOST' THEN
                IF OLD.state < 3 OR OLD.lease_expires_at IS NULL OR OLD.lease_expires_at > clock_timestamp() THEN
                    RAISE EXCEPTION 'comunicacao perdida exige tentativa recebida e prazo da comunicacao vencido' USING ERRCODE = '23514';
                END IF;
            ELSE
                SELECT EXISTS (SELECT 1 FROM public.agents a
                                 JOIN public.worker_agent_assignments wa ON wa.organization_id = a.organization_id AND wa.agent_id = a.agent_id
                                                                         AND wa.worker_id = OLD.worker_id AND wa.status = 1
                                WHERE a.organization_id = OLD.organization_id AND a.agent_id = OLD.agent_id AND a.status = 2) INTO v_linked;
                IF v_linked THEN
                    RAISE EXCEPTION 'a maquina da tentativa continua vinculada' USING ERRCODE = '23514';
                END IF;
            END IF;
        ELSE
            IF NOT (
                   (OLD.state = 1 AND NEW.state IN (2, 9, 11, 12))
                OR (OLD.state = 2 AND NEW.state IN (3, 9, 11, 12))
                OR (OLD.state = 3 AND NEW.state IN (4, 9, 11, 13))
                OR (OLD.state = 4 AND NEW.state IN (5, 9, 11, 13))
                OR (OLD.state = 5 AND NEW.state IN (6, 8, 9, 11, 13))
                OR (OLD.state = 6 AND NEW.state IN (7, 8, 9, 10, 13))) THEN
                RAISE EXCEPTION 'transicao de tentativa nao permitida: % -> %', OLD.state, NEW.state USING ERRCODE = '23514';
            END IF;
            -- as pessoas (nekkora_platform) so encerram o que a maquina ainda nao recebeu; o resto e da maquina
            IF current_user = 'nekkora_platform' AND NOT (OLD.state IN (1, 2) AND NEW.state IN (9, 12)) THEN
                RAISE EXCEPTION 'so a maquina muda o estado de uma tentativa recebida' USING ERRCODE = '42501';
            END IF;
        END IF;
        NEW.state_changed_at := clock_timestamp();
        IF NEW.state = 2 THEN
            NEW.offered_at := clock_timestamp();
        ELSIF NEW.state = 3 THEN
            NEW.received_at := clock_timestamp();
        ELSIF NEW.state = 6 THEN
            NEW.started_at := clock_timestamp();
        END IF;
        IF NEW.state >= 7 THEN
            NEW.ended_at := clock_timestamp();
            NEW.lease_expires_at := NULL;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- === 4. Privilegios ===
GRANT UPDATE (refusal_reason, closure_cause) ON public.remote_attempts TO nekkora_platform;
GRANT UPDATE (closure_cause, late_state, late_exit_code, late_reported_at) ON public.remote_attempts TO nekkora_ingestion;

-- === 5. Invariantes ===
DO $$
BEGIN
    IF has_column_privilege('nekkora_platform', 'public.remote_attempts', 'late_state', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.remote_attempts', 'exit_code', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.remote_attempts', 'token_hash', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.remote_attempts', 'DELETE')
       OR has_table_privilege('nekkora_ingestion', 'public.remote_attempts', 'DELETE')
       OR has_table_privilege('nekkora_ingestion', 'public.remote_attempts', 'INSERT') THEN
        RAISE EXCEPTION '053: privilegio alem do previsto no encerramento sem confirmacao';
    END IF;
END;
$$;

COMMIT;
