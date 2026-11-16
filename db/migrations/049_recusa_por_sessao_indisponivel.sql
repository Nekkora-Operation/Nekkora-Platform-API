-- Nekkora Platform - motivo de recusa "sessao indisponivel" na tentativa de execucao remota
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T03). Contrato: docs/platform-client-execucao-remota-001/t03a-pacote-para-a-maquina.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 046. Numero 049 conferido em 15/11 contra a dev e os PRs abertos. As ferramentas
-- do laboratorio e da homologacao exigem numeracao contigua: se outra 049 entrar antes, esta e renumerada sem mudar o conteudo.
--
-- O QUE FAZ
--   Acrescenta SESSION_UNAVAILABLE aos motivos com que uma tentativa pode ficar REFUSED. E o motivo que a MAQUINA da quando nao
--   ha sessao interativa utilizavel no computador (tela do Windows bloqueada, sessao de servico, sem area de trabalho): o
--   aplicativo nao inicia uma automacao nessas condicoes. Antes desta migration nao havia motivo que dissesse isso.
--
-- O QUE NAO FAZ: nao muda estados, transicoes, tentativas existentes, nem quem pode escrever.
--
-- DADOS EXISTENTES: nenhuma linha e alterada (a lista so cresce).
--
-- REVERSAO (manual; so enquanto nenhuma tentativa tiver o motivo novo):
--   BEGIN;
--   ALTER TABLE public.remote_attempts DROP CONSTRAINT remote_attempts_refusal_reason_check;
--   ALTER TABLE public.remote_attempts ADD CONSTRAINT remote_attempts_refusal_reason_check CHECK (refusal_reason IN (
--       'BUSY', 'PAUSED', 'PACKAGE_INVALID', 'ENVIRONMENT_GONE', 'ENVIRONMENT_INCOMPATIBLE', 'DEPENDENCIES_MISSING', 'DISK_PRESSURE',
--       'CLIENT_INCOMPATIBLE', 'AUTHORIZATION_MISSING', 'AUTHORIZATION_REVOKED', 'AUTHORIZATION_EXPIRED', 'WORKER_NOT_AUTHORIZED',
--       'MACHINE_NOT_LINKED', 'MACHINE_NOT_ENABLED', 'MACHINE_INCOMPATIBLE'));
--   COMMIT;
--
-- SEM ELEVACAO: troca de uma CHECK. Nenhum papel novo, nenhuma funcao SECURITY DEFINER.

BEGIN;

DO $$
BEGIN
    IF to_regclass('public.remote_attempts') IS NULL THEN
        RAISE EXCEPTION '049 exige a 046 aplicada';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conrelid = 'public.remote_attempts'::regclass AND c.conname = 'remote_attempts_refusal_reason_check') THEN
        RAISE EXCEPTION '049: a CHECK remote_attempts_refusal_reason_check nao existe com esse nome';
    END IF;
END;
$$;

ALTER TABLE public.remote_attempts DROP CONSTRAINT remote_attempts_refusal_reason_check;
ALTER TABLE public.remote_attempts ADD CONSTRAINT remote_attempts_refusal_reason_check CHECK (refusal_reason IN (
    'BUSY', 'PAUSED', 'PACKAGE_INVALID', 'ENVIRONMENT_GONE', 'ENVIRONMENT_INCOMPATIBLE', 'DEPENDENCIES_MISSING', 'DISK_PRESSURE',
    'CLIENT_INCOMPATIBLE', 'SESSION_UNAVAILABLE', 'AUTHORIZATION_MISSING', 'AUTHORIZATION_REVOKED', 'AUTHORIZATION_EXPIRED',
    'WORKER_NOT_AUTHORIZED', 'MACHINE_NOT_LINKED', 'MACHINE_NOT_ENABLED', 'MACHINE_INCOMPATIBLE'));

COMMIT;
