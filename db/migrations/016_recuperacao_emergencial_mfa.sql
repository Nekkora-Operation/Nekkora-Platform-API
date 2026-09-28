-- Nekkora Platform - recuperacao emergencial de MFA (break-glass operacional) e motivo do reset administrativo.
--
-- CANDIDATA ate a revisao. Contrato: docs/relatorios/2026-09-27-contrato-recuperacao-emergencial-mfa.md (revisao 3
-- final, commit ca4033b). Matriz de funcoes, chamadores, privilegios e inventario de locks:
-- docs/relatorios/2026-09-27-mfa-emergencia-matriz-016.md. NAO habilitada em producao: dependem de notificacao real
-- validada, CA/IdP operacionais implantados e da decisao D9 (retencao).
--
-- O que muda (aditivo; 001-015 inalteradas):
--   * papeis: nekkora_ops_mfa_owner (NOLOGIN, dono das funcoes), nekkora_ops_mfa (NOLOGIN, grupo dos operadores
--     op_*, criados pelo DBA fora da migration), nekkora_ops_ca (LOGIN, registro de emissao da CA);
--   * schema ops (sem USAGE para nenhum papel de LOGIN): cadastro operacional, habilitacoes, certificados, provas e
--     vinculos impeditivos, contatos autorizados (D2) e historico, solicitacoes, avisos, tokens de aviso (so hash) e
--     auditoria append-only;
--   * extensao sslinfo no schema ops: emissor e serial do certificado lidos pelo SERVIDOR da propria conexao;
--   * mfa_events: ops_request_id (FK composta com a solicitacao, UNIQUE, CHECK com o tipo 4) e reason_code
--     (obrigatorio em RESET_BY_ADMIN novo, NOT VALID - preserva o historico); nekkora_platform perde o INSERT na
--     coluna ops_request_id (nao consegue gravar evento tipo 4);
--   * politica RLS ops_function_context (nekkora.fn_organization_id) para nekkora_ops_mfa_owner nas tabelas de
--     tenant que as funcoes leem ou alteram; privilegios por coluna.
--
-- Autoria: session_user, nunca parametro. Autorizacao por ato: depois dos locks, com v_agora = clock_timestamp()
-- capturado depois deles - certificado lido pelo servidor, registro da CA vigente e nao revogado, operador ativo e
-- habilitado. Prazos do contrato fixos no codigo (72 h, 24 h, 60 min, 15 min, 30 dias, 7 dias); nenhum relogio vem
-- do chamador.
--
-- ELEVACAO: exige superusuario (CREATE ROLE e CREATE EXTENSION sslinfo, extensao nao confiavel), como 011-013.
--
-- Depende de 001-015. Atomica: uma transacao.

BEGIN;

-- === 1. Papeis ===

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_ops_mfa_owner') THEN
        CREATE ROLE nekkora_ops_mfa_owner NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_ops_mfa') THEN
        CREATE ROLE nekkora_ops_mfa NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_ops_ca') THEN
        CREATE ROLE nekkora_ops_ca LOGIN;
    END IF;
END;
$$;

ALTER ROLE nekkora_ops_mfa_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE nekkora_ops_mfa       NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE nekkora_ops_ca        LOGIN   NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

-- === 2. Schema operacional e introspeccao TLS ===

CREATE SCHEMA ops;
REVOKE ALL ON SCHEMA ops FROM PUBLIC;
GRANT USAGE ON SCHEMA ops TO nekkora_ops_mfa_owner;

CREATE EXTENSION sslinfo SCHEMA ops;
DO $$
DECLARE
    f REGPROCEDURE;
BEGIN
    FOR f IN SELECT p.oid::regprocedure FROM pg_proc p JOIN pg_depend d ON d.objid = p.oid AND d.classid = 'pg_proc'::regclass
             JOIN pg_extension e ON e.oid = d.refobjid AND d.refclassid = 'pg_extension'::regclass WHERE e.extname = 'sslinfo' LOOP
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', f);
        EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO nekkora_ops_mfa_owner', f);
    END LOOP;
END;
$$;

-- === 3. Cadastro operacional ===
--
-- Um operador por pessoa (regra ADMINISTRATIVA, contrato secao 4): db_role unico e sujeito do IdP unico.
-- status: 1=ATIVO, 2=DESATIVADO (final). created_by nulo so no bootstrap (um unico registro).

CREATE TABLE ops.ops_operators (
    operator_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    db_role      TEXT NOT NULL UNIQUE CHECK (db_role ~ '^op_[a-z0-9_]{2,40}$'),
    idp_issuer   TEXT NOT NULL CHECK (btrim(idp_issuer) <> '' AND length(idp_issuer) <= 512),
    idp_subject  TEXT NOT NULL CHECK (btrim(idp_subject) <> '' AND length(idp_subject) <= 256),
    status       SMALLINT NOT NULL DEFAULT 1 CHECK (status IN (1, 2)),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_by   UUID REFERENCES ops.ops_operators (operator_id),
    disabled_at  TIMESTAMPTZ,
    disabled_by  UUID REFERENCES ops.ops_operators (operator_id),
    UNIQUE (idp_issuer, idp_subject),
    CHECK ((status = 2) = (disabled_at IS NOT NULL)),
    CHECK ((disabled_at IS NULL) = (disabled_by IS NULL)),
    CHECK (created_by IS NULL OR created_by <> operator_id)
);
CREATE UNIQUE INDEX ux_ops_operators_bootstrap ON ops.ops_operators ((true)) WHERE created_by IS NULL;

-- Habilitacao por ato: 1=VERIFICAR (abre e verifica), 2=APROVAR (pre-aprovacao e aprovacao final), 3=EXECUTAR,
-- 4=CANCELAR, 5=CONSULTAR, 6=CADASTRAR (operadores, habilitacoes, contatos). Historico preservado.
CREATE TABLE ops.ops_operator_permissions (
    permission_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id   UUID NOT NULL REFERENCES ops.ops_operators (operator_id),
    act           SMALLINT NOT NULL CHECK (act BETWEEN 1 AND 6),
    granted_at    TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    granted_by    UUID REFERENCES ops.ops_operators (operator_id),
    revoked_at    TIMESTAMPTZ,
    revoked_by    UUID REFERENCES ops.ops_operators (operator_id),
    CHECK (granted_by IS NULL OR granted_by <> operator_id),
    CHECK ((revoked_at IS NULL) = (revoked_by IS NULL)),
    CHECK (revoked_at IS NULL OR revoked_at >= granted_at)
);
CREATE UNIQUE INDEX ux_ops_operator_permissions_ativa ON ops.ops_operator_permissions (operator_id, act) WHERE revoked_at IS NULL;

-- Registro de emissao: SO a CA grava (nekkora_ops_ca). Validade <= 15 min. revoke_reason: codigo.
CREATE TABLE ops.ops_operator_certificates (
    certificate_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id    UUID NOT NULL REFERENCES ops.ops_operators (operator_id),
    issuer_dn      TEXT NOT NULL CHECK (btrim(issuer_dn) <> '' AND length(issuer_dn) <= 1024),
    serial         NUMERIC(60, 0) NOT NULL CHECK (serial > 0),
    not_before     TIMESTAMPTZ NOT NULL,
    not_after      TIMESTAMPTZ NOT NULL,
    idp_auth_time  TIMESTAMPTZ NOT NULL,
    idp_method     TEXT NOT NULL CHECK (idp_method IN ('phr+mfa', 'phrh+mfa')),  -- acr resistente (EAP) + amr mfa, conferidos pela CA
    registered_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    revoked_at     TIMESTAMPTZ,
    revoke_reason  TEXT CHECK (revoke_reason IN ('CA', 'OPERADOR_DESATIVADO', 'HABILITACAO_RETIRADA')),
    UNIQUE (issuer_dn, serial),
    CHECK (not_after > not_before AND not_after - not_before <= interval '15 minutes'),
    CHECK (registered_at - idp_auth_time <= interval '5 minutes'),
    CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL))
);
CREATE INDEX ix_ops_operator_certificates_operador ON ops.ops_operator_certificates (operator_id) WHERE revoked_at IS NULL;

-- === 4. Vinculos impeditivos (contrato, secao 4) ===
--
-- Conta da Platform: prova emitida pela Platform na sessao autenticada do titular da conta (so o hash do codigo);
-- o vinculo referencia a PROVA (nunca users diretamente): o caminho de vinculo trava o operador e a prova, sem
-- pedir lock implicito em users - evita ciclo com a execucao (que trava users antes de ops_operators).

CREATE TABLE ops.ops_account_link_proofs (
    proof_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    user_id         UUID NOT NULL,
    code_hash       TEXT NOT NULL UNIQUE CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at      TIMESTAMPTZ NOT NULL,
    consumed_at     TIMESTAMPTZ,
    consumed_by     UUID REFERENCES ops.ops_operators (operator_id),
    UNIQUE (proof_id, organization_id, user_id),
    FOREIGN KEY (organization_id, user_id) REFERENCES public.users (organization_id, user_id) ON DELETE RESTRICT,
    CHECK (expires_at > created_at AND expires_at <= created_at + interval '10 minutes'),
    CHECK ((consumed_at IS NULL) = (consumed_by IS NULL))
);

CREATE TABLE ops.ops_operator_platform_accounts (
    link_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id     UUID NOT NULL REFERENCES ops.ops_operators (operator_id),
    proof_id        UUID NOT NULL UNIQUE,
    organization_id UUID NOT NULL,
    user_id         UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    removed_at      TIMESTAMPTZ,
    removed_by      UUID REFERENCES ops.ops_operators (operator_id),
    FOREIGN KEY (proof_id, organization_id, user_id) REFERENCES ops.ops_account_link_proofs (proof_id, organization_id, user_id),
    CHECK ((removed_at IS NULL) = (removed_by IS NULL)),
    CHECK (removed_by IS NULL OR removed_by <> operator_id)
);
CREATE INDEX ix_ops_operator_platform_accounts_org ON ops.ops_operator_platform_accounts (organization_id) WHERE removed_at IS NULL;

-- === 5. Contatos autorizados (D2) ===
--
-- channel_set_at: quando o canal atual foi registrado (inclusao ou alteracao). Elegivel = ativo e canal registrado
-- ha pelo menos 30 dias. status: 1=ATIVO, 2=REMOVIDO (final). channel_type: 1=TELEFONE, 2=EMAIL.

CREATE TABLE ops.org_authorized_contacts (
    contact_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    display_name    TEXT NOT NULL CHECK (btrim(display_name) <> '' AND length(display_name) <= 200),
    channel_type    SMALLINT NOT NULL CHECK (channel_type IN (1, 2)),
    channel_value   TEXT NOT NULL CHECK (btrim(channel_value) <> '' AND length(channel_value) <= 320),
    user_id         UUID,
    status          SMALLINT NOT NULL DEFAULT 1 CHECK (status IN (1, 2)),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    channel_set_at  TIMESTAMPTZ NOT NULL,
    removed_at      TIMESTAMPTZ,
    UNIQUE (organization_id, contact_id),
    FOREIGN KEY (organization_id, user_id) REFERENCES public.users (organization_id, user_id) ON DELETE RESTRICT,
    CHECK (channel_set_at >= created_at),
    CHECK ((status = 2) = (removed_at IS NOT NULL))
);

-- kind: 1=INCLUIDO, 2=CANAL_ALTERADO, 3=REMOVIDO. Nunca guarda o valor do canal.
CREATE TABLE ops.org_authorized_contact_events (
    event_id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id          UUID NOT NULL,
    contact_id               UUID NOT NULL,
    kind                     SMALLINT NOT NULL CHECK (kind IN (1, 2, 3)),
    operator_id              UUID NOT NULL REFERENCES ops.ops_operators (operator_id),
    origin                   TEXT CHECK (origin IN ('CONTRATACAO', 'SIGNATARIO')),
    signatory_confirmed      BOOLEAN,
    previous_channel_confirmed BOOLEAN,
    other_contact_id         UUID,
    other_contact_confirmed  BOOLEAN,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (organization_id, contact_id) REFERENCES ops.org_authorized_contacts (organization_id, contact_id),
    FOREIGN KEY (organization_id, other_contact_id) REFERENCES ops.org_authorized_contacts (organization_id, contact_id),
    CHECK (kind <> 1 OR (origin IS NOT NULL AND signatory_confirmed IS TRUE AND other_contact_id IS NULL)),
    CHECK (kind = 1 OR (origin IS NULL AND previous_channel_confirmed IS TRUE AND other_contact_confirmed IS TRUE
                        AND other_contact_id IS NOT NULL AND other_contact_id <> contact_id))
);

-- Vinculo operador <-> contato: prova emitida por OUTRO operador (codigo entregue pelo canal do contato; so hash).
CREATE TABLE ops.ops_contact_link_proofs (
    proof_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    contact_id      UUID NOT NULL,
    code_hash       TEXT NOT NULL UNIQUE CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    issued_by       UUID NOT NULL REFERENCES ops.ops_operators (operator_id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at      TIMESTAMPTZ NOT NULL,
    consumed_at     TIMESTAMPTZ,
    consumed_by     UUID REFERENCES ops.ops_operators (operator_id),
    UNIQUE (proof_id, organization_id, contact_id),
    FOREIGN KEY (organization_id, contact_id) REFERENCES ops.org_authorized_contacts (organization_id, contact_id),
    CHECK (expires_at > created_at AND expires_at <= created_at + interval '30 minutes'),
    CHECK ((consumed_at IS NULL) = (consumed_by IS NULL)),
    CHECK (consumed_by IS NULL OR consumed_by <> issued_by)
);

CREATE TABLE ops.ops_operator_contact_links (
    link_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id     UUID NOT NULL REFERENCES ops.ops_operators (operator_id),
    proof_id        UUID NOT NULL UNIQUE,
    organization_id UUID NOT NULL,
    contact_id      UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    removed_at      TIMESTAMPTZ,
    removed_by      UUID REFERENCES ops.ops_operators (operator_id),
    FOREIGN KEY (proof_id, organization_id, contact_id) REFERENCES ops.ops_contact_link_proofs (proof_id, organization_id, contact_id),
    CHECK ((removed_at IS NULL) = (removed_by IS NULL)),
    CHECK (removed_by IS NULL OR removed_by <> operator_id)
);
CREATE INDEX ix_ops_operator_contact_links_org ON ops.ops_operator_contact_links (organization_id) WHERE removed_at IS NULL;

-- === 6. Solicitacoes (contrato, secoes 6-8) ===
--
-- state: 1=SOLICITADA, 2=VERIFICADA, 3=PRE_APROVADA, 4=APROVADA, 5=EXECUTADA, 6=RECUSADA, 7=CANCELADA, 8=EXPIRADA.
-- requester_kind: 1=TITULAR (contato autorizado que e a propria conta alvo), 2=CONTATO.

CREATE TABLE ops.ops_mfa_recovery_requests (
    request_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id      UUID NOT NULL,
    target_user_id       UUID NOT NULL,
    requester_contact_id UUID NOT NULL,
    requester_kind       SMALLINT NOT NULL CHECK (requester_kind IN (1, 2)),
    state                SMALLINT NOT NULL DEFAULT 1 CHECK (state BETWEEN 1 AND 8),
    opened_at            TIMESTAMPTZ NOT NULL,
    opened_by            UUID NOT NULL REFERENCES ops.ops_operators (operator_id),
    t_total              TIMESTAMPTZ NOT NULL,
    verified_at          TIMESTAMPTZ,
    verified_by          UUID REFERENCES ops.ops_operators (operator_id),
    callback_ok          BOOLEAN,
    second_contact_id    UUID,
    second_contact_ok    BOOLEAN,
    preapproved_at       TIMESTAMPTZ,
    preapproved_by       UUID REFERENCES ops.ops_operators (operator_id),
    approved_factor_id   UUID,
    notice_confirmed_at  TIMESTAMPTZ,
    final_approved_at    TIMESTAMPTZ,
    final_approved_by    UUID REFERENCES ops.ops_operators (operator_id),
    executed_at          TIMESTAMPTZ,
    executed_by          UUID REFERENCES ops.ops_operators (operator_id),
    closed_at            TIMESTAMPTZ,
    closed_by            UUID REFERENCES ops.ops_operators (operator_id),
    close_reason         TEXT CHECK (close_reason ~ '^[A-Z_]{3,40}$'),
    UNIQUE (request_id, organization_id, target_user_id),
    FOREIGN KEY (organization_id, target_user_id) REFERENCES public.users (organization_id, user_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, target_user_id, approved_factor_id)
        REFERENCES public.user_mfa_factors (organization_id, user_id, factor_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, requester_contact_id) REFERENCES ops.org_authorized_contacts (organization_id, contact_id),
    FOREIGN KEY (organization_id, second_contact_id) REFERENCES ops.org_authorized_contacts (organization_id, contact_id),
    CHECK (t_total = opened_at + interval '72 hours'),
    CHECK (second_contact_id IS NULL OR second_contact_id <> requester_contact_id),
    -- pessoas distintas dentro da solicitacao: aprovador distinto do verificador e do executor
    CHECK (preapproved_by IS NULL OR preapproved_by <> verified_by),
    CHECK (final_approved_by IS NULL OR final_approved_by <> verified_by),
    CHECK (executed_by IS NULL OR (executed_by <> preapproved_by AND executed_by <> final_approved_by)),
    -- coerencia estado x marcos
    CHECK (state NOT IN (2, 3, 4, 5) OR (verified_at IS NOT NULL AND verified_by IS NOT NULL AND callback_ok IS TRUE AND second_contact_ok IS TRUE
                                         AND second_contact_id IS NOT NULL)),
    CHECK (state NOT IN (3, 4, 5) OR (preapproved_at IS NOT NULL AND approved_factor_id IS NOT NULL)),
    CHECK (state NOT IN (4, 5) OR (final_approved_at IS NOT NULL AND notice_confirmed_at IS NOT NULL)),
    CHECK ((state = 5) = (executed_at IS NOT NULL)),
    CHECK ((state IN (6, 7, 8)) = (closed_at IS NOT NULL)),
    CHECK ((closed_at IS NULL) = (close_reason IS NULL)),
    -- prazos (contrato, secao 6), tambem estruturais
    CHECK (verified_at IS NULL OR (verified_at >= opened_at AND verified_at < opened_at + interval '24 hours')),
    CHECK (preapproved_at IS NULL OR (preapproved_at >= verified_at AND preapproved_at < t_total)),
    CHECK (notice_confirmed_at IS NULL OR (notice_confirmed_at >= preapproved_at AND notice_confirmed_at < t_total)),
    CHECK (final_approved_at IS NULL OR (final_approved_at >= notice_confirmed_at + interval '24 hours' AND final_approved_at < t_total)),
    CHECK (executed_at IS NULL OR executed_at < least(t_total, final_approved_at + interval '60 minutes'))
);
-- exclusividade por alvo a partir de VERIFICADA (D6b)
CREATE UNIQUE INDEX ux_ops_mfa_recovery_requests_alvo ON ops.ops_mfa_recovery_requests (target_user_id) WHERE state IN (2, 3, 4);
CREATE INDEX ix_ops_mfa_recovery_requests_origem ON ops.ops_mfa_recovery_requests (requester_contact_id, opened_at);
CREATE INDEX ix_ops_mfa_recovery_requests_executadas ON ops.ops_mfa_recovery_requests (organization_id, executed_at) WHERE state = 5;

-- === 7. Avisos e tokens (contrato, secao 6.1) ===
--
-- kind: 1=PRE_APROVACAO_TITULAR (CONFIRMAR + CONTESTAR), 2=PRE_APROVACAO_ADMIN (CONTESTAR), 3=EXECUCAO_TITULAR,
-- 4=EXECUCAO_ADMIN (informativos). issued_at: a Platform gerou os tokens e vai enviar. discarded: nao emitido
-- (solicitacao ja terminal ao emitir).

CREATE TABLE ops.ops_mfa_notices (
    notice_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id        UUID NOT NULL,
    organization_id   UUID NOT NULL,
    target_user_id    UUID NOT NULL,
    recipient_user_id UUID NOT NULL,
    kind              SMALLINT NOT NULL CHECK (kind IN (1, 2, 3, 4)),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    issued_at         TIMESTAMPTZ,
    discarded         BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (notice_id, request_id, organization_id, target_user_id, recipient_user_id),
    FOREIGN KEY (request_id, organization_id, target_user_id)
        REFERENCES ops.ops_mfa_recovery_requests (request_id, organization_id, target_user_id),
    FOREIGN KEY (organization_id, recipient_user_id) REFERENCES public.users (organization_id, user_id) ON DELETE RESTRICT,
    CHECK ((kind IN (1, 3)) = (recipient_user_id = target_user_id)),
    CHECK (NOT discarded OR issued_at IS NOT NULL)
);
CREATE INDEX ix_ops_mfa_notices_pendentes ON ops.ops_mfa_notices (created_at) WHERE issued_at IS NULL;

-- purpose: 1=CONFIRMAR (so o titular), 2=CONTESTAR. Guarda so o SHA-256 (hex) do token.
CREATE TABLE ops.ops_mfa_notice_tokens (
    token_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notice_id         UUID NOT NULL,
    request_id        UUID NOT NULL,
    organization_id   UUID NOT NULL,
    target_user_id    UUID NOT NULL,
    recipient_user_id UUID NOT NULL,
    purpose           SMALLINT NOT NULL CHECK (purpose IN (1, 2)),
    token_hash        TEXT NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    issued_at         TIMESTAMPTZ NOT NULL,
    valid_until       TIMESTAMPTZ NOT NULL,
    used_at           TIMESTAMPTZ,
    invalidated_at    TIMESTAMPTZ,
    FOREIGN KEY (notice_id, request_id, organization_id, target_user_id, recipient_user_id)
        REFERENCES ops.ops_mfa_notices (notice_id, request_id, organization_id, target_user_id, recipient_user_id),
    CHECK (purpose <> 1 OR recipient_user_id = target_user_id),
    CHECK (valid_until > issued_at),
    CHECK (purpose <> 1 OR valid_until <= issued_at + interval '24 hours'),
    CHECK (used_at IS NULL OR invalidated_at IS NULL)
);
CREATE UNIQUE INDEX ux_ops_mfa_notice_tokens_ativo ON ops.ops_mfa_notice_tokens (request_id, recipient_user_id, purpose)
    WHERE used_at IS NULL AND invalidated_at IS NULL;

-- === 8. Auditoria (append-only) ===
--
-- origin: 1=CONFIRMADO_PELO_BANCO, 2=INFORMADO_PELA_CLI. Sem texto livre: codigos, ids, emissor e serial.

CREATE TABLE ops.ops_mfa_recovery_events (
    event_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id        UUID,
    organization_id   UUID REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    target_user_id    UUID,
    act               TEXT NOT NULL CHECK (act IN ('ABRIR', 'VERIFICAR', 'PRE_APROVAR', 'APROVAR', 'EXECUTAR', 'CANCELAR',
                                                   'CONSULTAR', 'REENVIAR_AVISO', 'AVISO_EMITIDO', 'AVISO_CONFIRMADO',
                                                   'AVISO_CONTESTADO', 'AVISO_INVALIDO', 'FALHA')),
    result            TEXT NOT NULL CHECK (result IN ('OK', 'RECUSA', 'FALHA')),
    reason            TEXT CHECK (reason ~ '^[A-Z_]{2,40}$'),
    session_role      TEXT NOT NULL DEFAULT session_user,
    operator_id       UUID REFERENCES ops.ops_operators (operator_id),
    cert_issuer_dn    TEXT,
    cert_serial       NUMERIC(60, 0),
    recipient_user_id UUID,
    purpose           SMALLINT CHECK (purpose IN (1, 2)),
    origin            SMALLINT NOT NULL DEFAULT 1 CHECK (origin IN (1, 2)),
    failed_act        TEXT CHECK (failed_act IN ('ABRIR', 'VERIFICAR', 'PRE_APROVAR', 'APROVAR', 'EXECUTAR', 'CANCELAR',
                                                 'CONSULTAR', 'REENVIAR_AVISO')),
    sqlstate          TEXT CHECK (sqlstate ~ '^[0-9A-Z]{5}$'),
    stage             TEXT CHECK (stage IN ('CHAMADA', 'COMMIT')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (request_id, organization_id, target_user_id)
        REFERENCES ops.ops_mfa_recovery_requests (request_id, organization_id, target_user_id),
    FOREIGN KEY (organization_id, target_user_id) REFERENCES public.users (organization_id, user_id) ON DELETE RESTRICT,
    CHECK (request_id IS NULL OR (organization_id IS NOT NULL AND target_user_id IS NOT NULL)),
    CHECK (target_user_id IS NULL OR organization_id IS NOT NULL),
    CHECK ((origin = 2) = (act = 'FALHA')),
    CHECK (act <> 'FALHA' OR (result = 'FALHA' AND failed_act IS NOT NULL AND sqlstate IS NOT NULL AND stage IS NOT NULL
                              AND request_id IS NOT NULL)),
    CHECK (act = 'FALHA' OR (failed_act IS NULL AND sqlstate IS NULL AND stage IS NULL)),
    CHECK ((result = 'OK') = (reason IS NULL) OR act = 'FALHA')
);
CREATE INDEX ix_ops_mfa_recovery_events_request ON ops.ops_mfa_recovery_events (request_id);

-- Cadastro, CA e vinculos. actor_kind: 1=OPERADOR, 2=CA, 3=PLATAFORMA, 4=BOOTSTRAP.
CREATE TABLE ops.ops_registry_events (
    event_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind               TEXT NOT NULL CHECK (kind IN ('OPERADOR_CRIADO', 'HABILITACAO_CONCEDIDA', 'HABILITACAO_RETIRADA',
                                                     'OPERADOR_DESATIVADO', 'CERTIFICADO_REGISTRADO', 'CERTIFICADO_REVOGADO',
                                                     'PROVA_CONTA_EMITIDA', 'VINCULO_CONTA', 'PROVA_CONTATO_EMITIDA',
                                                     'VINCULO_CONTATO', 'VINCULO_REMOVIDO', 'CONTATO_INCLUIDO',
                                                     'CONTATO_CANAL_ALTERADO', 'CONTATO_REMOVIDO')),
    result             TEXT NOT NULL CHECK (result IN ('OK', 'RECUSA')),
    reason             TEXT CHECK (reason ~ '^[A-Z_]{2,40}$'),
    actor_kind         SMALLINT NOT NULL CHECK (actor_kind IN (1, 2, 3, 4)),
    session_role       TEXT NOT NULL DEFAULT session_user,
    actor_operator_id  UUID REFERENCES ops.ops_operators (operator_id),
    target_operator_id UUID REFERENCES ops.ops_operators (operator_id),
    act                SMALLINT CHECK (act BETWEEN 1 AND 6),
    certificate_id     UUID REFERENCES ops.ops_operator_certificates (certificate_id),
    organization_id    UUID REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    contact_id         UUID REFERENCES ops.org_authorized_contacts (contact_id),
    cert_issuer_dn     TEXT,
    cert_serial        NUMERIC(60, 0),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((result = 'OK') = (reason IS NULL))
);

-- === 9. Imutabilidade ===

CREATE FUNCTION ops.prevent_operator_change() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.status = 2 THEN RAISE EXCEPTION 'operador desativado e imutavel'; END IF;
    IF NEW.operator_id <> OLD.operator_id OR NEW.db_role <> OLD.db_role OR NEW.idp_issuer <> OLD.idp_issuer
       OR NEW.idp_subject <> OLD.idp_subject OR NEW.created_at <> OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'origem do operador e imutavel';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION ops.prevent_permission_change() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL THEN RAISE EXCEPTION 'habilitacao retirada e imutavel'; END IF;
    IF NEW.permission_id <> OLD.permission_id OR NEW.operator_id <> OLD.operator_id OR NEW.act <> OLD.act
       OR NEW.granted_at <> OLD.granted_at OR NEW.granted_by IS DISTINCT FROM OLD.granted_by THEN
        RAISE EXCEPTION 'origem da habilitacao e imutavel';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION ops.prevent_certificate_change() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.revoked_at IS NOT NULL THEN RAISE EXCEPTION 'certificado revogado e imutavel'; END IF;
    IF NEW.certificate_id <> OLD.certificate_id OR NEW.operator_id <> OLD.operator_id OR NEW.issuer_dn <> OLD.issuer_dn
       OR NEW.serial <> OLD.serial OR NEW.not_before <> OLD.not_before OR NEW.not_after <> OLD.not_after
       OR NEW.idp_auth_time <> OLD.idp_auth_time OR NEW.idp_method <> OLD.idp_method OR NEW.registered_at <> OLD.registered_at THEN
        RAISE EXCEPTION 'registro de emissao e imutavel (so a revogacao e aceita)';
    END IF;
    RETURN NEW;
END;
$$;

-- provas: so o consumo, uma vez
CREATE FUNCTION ops.prevent_proof_change() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.consumed_at IS NOT NULL THEN RAISE EXCEPTION 'prova consumida e imutavel'; END IF;
    IF NEW.proof_id <> OLD.proof_id OR NEW.organization_id <> OLD.organization_id OR NEW.code_hash <> OLD.code_hash
       OR NEW.created_at <> OLD.created_at OR NEW.expires_at <> OLD.expires_at THEN
        RAISE EXCEPTION 'origem da prova e imutavel';
    END IF;
    RETURN NEW;
END;
$$;

-- vinculos: so a remocao, uma vez
CREATE FUNCTION ops.prevent_link_change() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.removed_at IS NOT NULL THEN RAISE EXCEPTION 'vinculo removido e imutavel'; END IF;
    IF NEW.link_id <> OLD.link_id OR NEW.operator_id <> OLD.operator_id OR NEW.proof_id <> OLD.proof_id
       OR NEW.organization_id <> OLD.organization_id OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'origem do vinculo e imutavel';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION ops.prevent_contact_change() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.status = 2 THEN RAISE EXCEPTION 'contato removido e imutavel'; END IF;
    IF NEW.contact_id <> OLD.contact_id OR NEW.organization_id <> OLD.organization_id OR NEW.created_at <> OLD.created_at
       OR NEW.user_id IS DISTINCT FROM OLD.user_id OR NEW.display_name <> OLD.display_name THEN
        RAISE EXCEPTION 'origem do contato e imutavel';
    END IF;
    IF (NEW.channel_type <> OLD.channel_type OR NEW.channel_value <> OLD.channel_value) <> (NEW.channel_set_at > OLD.channel_set_at)
       OR NEW.channel_set_at < OLD.channel_set_at THEN
        RAISE EXCEPTION 'alteracao de canal exige novo channel_set_at (e so ela o avanca)';
    END IF;
    RETURN NEW;
END;
$$;

-- solicitacao: estado so avanca; marcos gravados uma vez; terminal congelada
CREATE FUNCTION ops.prevent_request_change() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.state IN (5, 6, 7, 8) THEN RAISE EXCEPTION 'solicitacao em estado terminal e imutavel'; END IF;
    IF NEW.request_id <> OLD.request_id OR NEW.organization_id <> OLD.organization_id OR NEW.target_user_id <> OLD.target_user_id
       OR NEW.requester_contact_id <> OLD.requester_contact_id OR NEW.requester_kind <> OLD.requester_kind
       OR NEW.opened_at <> OLD.opened_at OR NEW.opened_by <> OLD.opened_by OR NEW.t_total <> OLD.t_total THEN
        RAISE EXCEPTION 'origem da solicitacao e imutavel';
    END IF;
    IF NOT (NEW.state = OLD.state OR NEW.state IN (6, 7, 8) OR (NEW.state = OLD.state + 1 AND NEW.state <= 5)) THEN
        RAISE EXCEPTION 'transicao de estado nao permitida (% -> %)', OLD.state, NEW.state;
    END IF;
    IF (OLD.verified_at IS NOT NULL AND (NEW.verified_at, NEW.verified_by, NEW.callback_ok, NEW.second_contact_id, NEW.second_contact_ok)
            IS DISTINCT FROM (OLD.verified_at, OLD.verified_by, OLD.callback_ok, OLD.second_contact_id, OLD.second_contact_ok))
       OR (OLD.preapproved_at IS NOT NULL AND (NEW.preapproved_at, NEW.preapproved_by, NEW.approved_factor_id)
            IS DISTINCT FROM (OLD.preapproved_at, OLD.preapproved_by, OLD.approved_factor_id))
       OR (OLD.notice_confirmed_at IS NOT NULL AND NEW.notice_confirmed_at IS DISTINCT FROM OLD.notice_confirmed_at)
       OR (OLD.final_approved_at IS NOT NULL AND (NEW.final_approved_at, NEW.final_approved_by)
            IS DISTINCT FROM (OLD.final_approved_at, OLD.final_approved_by)) THEN
        RAISE EXCEPTION 'marco da solicitacao ja gravado e imutavel';
    END IF;
    IF NEW.notice_confirmed_at IS DISTINCT FROM OLD.notice_confirmed_at AND OLD.state <> 3 THEN
        RAISE EXCEPTION 'confirmacao do aviso so em PRE_APROVADA';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION ops.prevent_notice_change() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.issued_at IS NOT NULL THEN RAISE EXCEPTION 'aviso emitido e imutavel'; END IF;
    IF NEW.notice_id <> OLD.notice_id OR NEW.request_id <> OLD.request_id OR NEW.recipient_user_id <> OLD.recipient_user_id
       OR NEW.kind <> OLD.kind OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'origem do aviso e imutavel';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION ops.prevent_notice_token_change() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.used_at IS NOT NULL OR OLD.invalidated_at IS NOT NULL THEN RAISE EXCEPTION 'token usado ou invalidado e imutavel'; END IF;
    IF NEW.token_id <> OLD.token_id OR NEW.notice_id <> OLD.notice_id OR NEW.recipient_user_id <> OLD.recipient_user_id
       OR NEW.purpose <> OLD.purpose OR NEW.token_hash <> OLD.token_hash OR NEW.issued_at <> OLD.issued_at
       OR NEW.valid_until <> OLD.valid_until THEN
        RAISE EXCEPTION 'origem do token e imutavel';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ops_operators_prevent_change BEFORE UPDATE ON ops.ops_operators FOR EACH ROW EXECUTE FUNCTION ops.prevent_operator_change();
CREATE TRIGGER trg_ops_operator_permissions_prevent_change BEFORE UPDATE ON ops.ops_operator_permissions FOR EACH ROW EXECUTE FUNCTION ops.prevent_permission_change();
CREATE TRIGGER trg_ops_operator_certificates_prevent_change BEFORE UPDATE ON ops.ops_operator_certificates FOR EACH ROW EXECUTE FUNCTION ops.prevent_certificate_change();
CREATE TRIGGER trg_ops_account_link_proofs_prevent_change BEFORE UPDATE ON ops.ops_account_link_proofs FOR EACH ROW EXECUTE FUNCTION ops.prevent_proof_change();
CREATE TRIGGER trg_ops_contact_link_proofs_prevent_change BEFORE UPDATE ON ops.ops_contact_link_proofs FOR EACH ROW EXECUTE FUNCTION ops.prevent_proof_change();
CREATE TRIGGER trg_ops_operator_platform_accounts_prevent_change BEFORE UPDATE ON ops.ops_operator_platform_accounts FOR EACH ROW EXECUTE FUNCTION ops.prevent_link_change();
CREATE TRIGGER trg_ops_operator_contact_links_prevent_change BEFORE UPDATE ON ops.ops_operator_contact_links FOR EACH ROW EXECUTE FUNCTION ops.prevent_link_change();
CREATE TRIGGER trg_org_authorized_contacts_prevent_change BEFORE UPDATE ON ops.org_authorized_contacts FOR EACH ROW EXECUTE FUNCTION ops.prevent_contact_change();
CREATE TRIGGER trg_ops_mfa_recovery_requests_prevent_change BEFORE UPDATE ON ops.ops_mfa_recovery_requests FOR EACH ROW EXECUTE FUNCTION ops.prevent_request_change();
CREATE TRIGGER trg_ops_mfa_notices_prevent_change BEFORE UPDATE ON ops.ops_mfa_notices FOR EACH ROW EXECUTE FUNCTION ops.prevent_notice_change();
CREATE TRIGGER trg_ops_mfa_notice_tokens_prevent_change BEFORE UPDATE ON ops.ops_mfa_notice_tokens FOR EACH ROW EXECUTE FUNCTION ops.prevent_notice_token_change();
CREATE TRIGGER trg_org_authorized_contact_events_prevent_update BEFORE UPDATE ON ops.org_authorized_contact_events FOR EACH ROW EXECUTE FUNCTION public.prevent_update();
CREATE TRIGGER trg_ops_mfa_recovery_events_prevent_update BEFORE UPDATE ON ops.ops_mfa_recovery_events FOR EACH ROW EXECUTE FUNCTION public.prevent_update();
CREATE TRIGGER trg_ops_registry_events_prevent_update BEFORE UPDATE ON ops.ops_registry_events FOR EACH ROW EXECUTE FUNCTION public.prevent_update();

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['ops_operators', 'ops_operator_permissions', 'ops_operator_certificates', 'ops_account_link_proofs',
        'ops_operator_platform_accounts', 'org_authorized_contacts', 'org_authorized_contact_events', 'ops_contact_link_proofs',
        'ops_operator_contact_links', 'ops_mfa_recovery_requests', 'ops_mfa_notices', 'ops_mfa_notice_tokens',
        'ops_mfa_recovery_events', 'ops_registry_events'] LOOP
        EXECUTE format('CREATE TRIGGER trg_%s_prevent_delete BEFORE DELETE ON ops.%I FOR EACH ROW EXECUTE FUNCTION public.prevent_delete()', t, t);
        EXECUTE format('CREATE TRIGGER trg_%s_prevent_truncate BEFORE TRUNCATE ON ops.%I FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate()', t, t);
    END LOOP;
END;
$$;

-- === 10. mfa_events: vinculo composto com a solicitacao e motivo do reset administrativo (contrato, secao 8; D5) ===
--
-- reason_code (RESET_BY_ADMIN): 1=DISPOSITIVO_PERDIDO, 2=DISPOSITIVO_TROCADO, 3=SUSPEITA_COMPROMETIMENTO, 4=OUTRO.

ALTER TABLE public.mfa_events ADD COLUMN ops_request_id UUID;
ALTER TABLE public.mfa_events ADD COLUMN reason_code SMALLINT CHECK (reason_code IN (1, 2, 3, 4));
ALTER TABLE public.mfa_events ADD CONSTRAINT mfa_events_ops_request_id_key UNIQUE (ops_request_id);
ALTER TABLE public.mfa_events ADD CONSTRAINT mfa_events_ops_request_fk FOREIGN KEY (ops_request_id, organization_id, user_id)
    REFERENCES ops.ops_mfa_recovery_requests (request_id, organization_id, target_user_id);
ALTER TABLE public.mfa_events ADD CONSTRAINT mfa_events_ops_request_tipo_4 CHECK ((event_type = 4) = (ops_request_id IS NOT NULL));
ALTER TABLE public.mfa_events ADD CONSTRAINT mfa_events_tipo_4_sem_ator CHECK (event_type <> 4 OR actor_user_id IS NULL);
ALTER TABLE public.mfa_events ADD CONSTRAINT mfa_events_reason_code_so_admin CHECK (reason_code IS NULL OR event_type = 3);
ALTER TABLE public.mfa_events ADD CONSTRAINT mfa_events_reset_admin_exige_motivo CHECK (event_type <> 3 OR reason_code IS NOT NULL) NOT VALID;

COMMENT ON COLUMN public.mfa_events.ops_request_id IS 'solicitacao de recuperacao emergencial (so tipo 4); FK composta com organizacao e alvo';
COMMENT ON COLUMN public.mfa_events.reason_code IS 'motivo do RESET_BY_ADMIN: 1=DISPOSITIVO_PERDIDO, 2=DISPOSITIVO_TROCADO, 3=SUSPEITA_COMPROMETIMENTO, 4=OUTRO';

-- A Platform continua gravando eventos, mas nunca o vinculo operacional (logo, nunca o tipo 4).
REVOKE INSERT ON public.mfa_events FROM nekkora_platform;
GRANT INSERT (event_id, organization_id, user_id, actor_user_id, event_type, correlation_id, created_at, reason_code)
    ON public.mfa_events TO nekkora_platform;

-- === 11. Privilegios do proprietario operacional ===

GRANT SELECT, INSERT, UPDATE ON ops.ops_operators, ops.ops_operator_permissions, ops.ops_operator_certificates,
    ops.ops_account_link_proofs, ops.ops_operator_platform_accounts, ops.org_authorized_contacts, ops.ops_contact_link_proofs,
    ops.ops_operator_contact_links, ops.ops_mfa_recovery_requests, ops.ops_mfa_notices, ops.ops_mfa_notice_tokens
    TO nekkora_ops_mfa_owner;
GRANT SELECT, INSERT ON ops.org_authorized_contact_events, ops.ops_mfa_recovery_events, ops.ops_registry_events TO nekkora_ops_mfa_owner;

-- Tabelas de tenant: so as colunas lidas; UPDATE so nas colunas alteradas pelo reset. O UPDATE em updated_at de
-- organizations/users existe apenas porque SELECT ... FOR UPDATE exige privilegio de UPDATE em alguma coluna;
-- nenhuma funcao atualiza essas tabelas.
GRANT SELECT (organization_id, status), UPDATE (updated_at) ON public.organizations TO nekkora_ops_mfa_owner;
GRANT SELECT (organization_id, user_id, account_type, status), UPDATE (updated_at) ON public.users TO nekkora_ops_mfa_owner;
GRANT SELECT (organization_id, membership_id, user_id, status) ON public.organization_memberships TO nekkora_ops_mfa_owner;
GRANT SELECT (organization_id, membership_id, role_id, status) ON public.organization_role_assignments TO nekkora_ops_mfa_owner;
GRANT SELECT (role_id, code) ON public.roles TO nekkora_ops_mfa_owner;
GRANT SELECT (organization_id, user_id, factor_id, confirmed_at, disabled_at), UPDATE (disabled_at) ON public.user_mfa_factors TO nekkora_ops_mfa_owner;
GRANT SELECT (organization_id, user_id, used_at, invalidated_at), UPDATE (invalidated_at) ON public.user_mfa_recovery_codes TO nekkora_ops_mfa_owner;
GRANT SELECT (organization_id, user_id, consumed_at, invalidated_at), UPDATE (invalidated_at, credential_hash, pending_password_hash)
    ON public.auth_challenges TO nekkora_ops_mfa_owner;
GRANT SELECT (organization_id, user_id, revoked_at), UPDATE (revoked_at) ON public.user_sessions TO nekkora_ops_mfa_owner;
GRANT SELECT (organization_id, user_id, event_type, ops_request_id), INSERT (organization_id, user_id, event_type, correlation_id, ops_request_id)
    ON public.mfa_events TO nekkora_ops_mfa_owner;

DO $$
DECLARE
    t TEXT;
    ctx_fn CONSTANT TEXT := 'organization_id = NULLIF(pg_catalog.current_setting(''nekkora.fn_organization_id'', true), '''')::uuid';
BEGIN
    FOREACH t IN ARRAY ARRAY['organizations', 'users', 'organization_memberships', 'organization_role_assignments', 'user_mfa_factors',
                             'user_mfa_recovery_codes', 'auth_challenges', 'user_sessions', 'mfa_events'] LOOP
        EXECUTE format('CREATE POLICY ops_function_context ON public.%I AS PERMISSIVE FOR ALL TO nekkora_ops_mfa_owner USING (%s) WITH CHECK (%s)',
                       t, ctx_fn, ctx_fn);
    END LOOP;
END;
$$;

-- === 12. Funcoes internas (schema ops, SECURITY INVOKER; so chamadas de dentro das funcoes publicas) ===

CREATE FUNCTION ops.estado_nome(p_state SMALLINT) RETURNS TEXT
LANGUAGE sql IMMUTABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT (ARRAY['SOLICITADA', 'VERIFICADA', 'PRE_APROVADA', 'APROVADA', 'EXECUTADA', 'RECUSADA', 'CANCELADA', 'EXPIRADA'])[p_state]
$$;

CREATE FUNCTION ops.ato_codigo(p_ato TEXT) RETURNS SMALLINT
LANGUAGE sql IMMUTABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT CASE p_ato WHEN 'VERIFICAR' THEN 1 WHEN 'APROVAR' THEN 2 WHEN 'EXECUTAR' THEN 3 WHEN 'CANCELAR' THEN 4
                      WHEN 'CONSULTAR' THEN 5 WHEN 'CADASTRAR' THEN 6 END::smallint
$$;

-- Operador da conexao, pela autoria do servidor (session_user). Sem lock.
CREATE FUNCTION ops.operador_da_sessao() RETURNS UUID
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT o.operator_id FROM ops.ops_operators o WHERE o.db_role = session_user::text
$$;

-- Trava operadores em ordem crescente de operator_id: FOR UPDATE nos de p_update, FOR SHARE nos demais.
CREATE FUNCTION ops.travar_operadores(p_share UUID[], p_update UUID[] DEFAULT '{}') RETURNS VOID
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_id UUID;
BEGIN
    FOR v_id IN SELECT DISTINCT x FROM unnest(coalesce(p_share, '{}') || coalesce(p_update, '{}')) AS x WHERE x IS NOT NULL ORDER BY x LOOP
        IF v_id = ANY (coalesce(p_update, '{}')) THEN
            PERFORM 1 FROM ops.ops_operators WHERE operator_id = v_id FOR UPDATE;
        ELSE
            PERFORM 1 FROM ops.ops_operators WHERE operator_id = v_id FOR SHARE;
        END IF;
    END LOOP;
END;
$$;

-- Autorizacao do ato (contrato 3.3), chamada DEPOIS dos locks com o v_agora capturado depois deles. p_ato nulo:
-- basta operador ativo com certificado vigente (vinculos proprios, registro de falha). Motivo nulo = autorizado.
CREATE FUNCTION ops.autorizar(p_operator UUID, p_ato SMALLINT, p_agora TIMESTAMPTZ,
                              OUT o_motivo TEXT, OUT o_emissor TEXT, OUT o_serial NUMERIC)
LANGUAGE plpgsql STABLE SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_status SMALLINT;
    v_cert RECORD;
BEGIN
    IF NOT coalesce(ops.ssl_is_used(), false) OR NOT coalesce(ops.ssl_client_cert_present(), false) THEN
        o_motivo := 'SEM_CERTIFICADO';
        RETURN;
    END IF;
    o_emissor := ops.ssl_issuer_dn();
    o_serial := ops.ssl_client_serial();
    IF ops.ssl_client_dn_field('commonName') IS DISTINCT FROM session_user::text THEN
        o_motivo := 'CERTIFICADO_DIVERGENTE';
        RETURN;
    END IF;
    SELECT o.status INTO v_status FROM ops.ops_operators o WHERE o.operator_id = p_operator AND o.db_role = session_user::text;
    IF NOT FOUND THEN
        o_motivo := 'OPERADOR_DESCONHECIDO';
        RETURN;
    END IF;
    IF v_status <> 1 THEN
        o_motivo := 'OPERADOR_INATIVO';
        RETURN;
    END IF;
    SELECT c.operator_id, c.not_before, c.not_after, c.revoked_at INTO v_cert
    FROM ops.ops_operator_certificates c WHERE c.issuer_dn = o_emissor AND c.serial = o_serial;
    IF NOT FOUND OR v_cert.operator_id <> p_operator THEN
        o_motivo := 'CERTIFICADO_NAO_REGISTRADO';
        RETURN;
    END IF;
    IF v_cert.revoked_at IS NOT NULL THEN
        o_motivo := 'CERTIFICADO_REVOGADO';
        RETURN;
    END IF;
    IF NOT (v_cert.not_before <= p_agora AND p_agora < v_cert.not_after) THEN
        o_motivo := 'CERTIFICADO_FORA_DA_VALIDADE';
        RETURN;
    END IF;
    IF p_ato IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM ops.ops_operator_permissions p
            WHERE p.operator_id = p_operator AND p.act = p_ato AND p.revoked_at IS NULL AND p.granted_at <= p_agora) THEN
        o_motivo := 'SEM_HABILITACAO';
        RETURN;
    END IF;
END;
$$;

-- Participante (contrato 4 e 7): ativo, habilitado para o seu ato em p_agora e sem vinculo impeditivo com a
-- organizacao alvo (conta da Platform ou contato autorizado). Motivo nulo = valido.
CREATE FUNCTION ops.participante_invalido(p_operator UUID, p_ato SMALLINT, p_org UUID, p_agora TIMESTAMPTZ) RETURNS TEXT
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT CASE
        WHEN NOT EXISTS (SELECT 1 FROM ops.ops_operators o WHERE o.operator_id = p_operator AND o.status = 1) THEN 'PARTICIPANTE_INATIVO'
        WHEN NOT EXISTS (SELECT 1 FROM ops.ops_operator_permissions p WHERE p.operator_id = p_operator AND p.act = p_ato
                         AND p.revoked_at IS NULL AND p.granted_at <= p_agora) THEN 'PARTICIPANTE_INABILITADO'
        WHEN EXISTS (SELECT 1 FROM ops.ops_operator_platform_accounts l WHERE l.operator_id = p_operator AND l.removed_at IS NULL
                     AND l.organization_id = p_org) THEN 'VINCULO_IMPEDITIVO'
        WHEN EXISTS (SELECT 1 FROM ops.ops_operator_contact_links l WHERE l.operator_id = p_operator AND l.removed_at IS NULL
                     AND l.organization_id = p_org) THEN 'VINCULO_IMPEDITIVO'
    END
$$;

-- Vinculo impeditivo do proprio ator com a organizacao alvo.
CREATE FUNCTION ops.tem_vinculo(p_operator UUID, p_org UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM ops.ops_operator_platform_accounts l WHERE l.operator_id = p_operator AND l.removed_at IS NULL AND l.organization_id = p_org)
        OR EXISTS (SELECT 1 FROM ops.ops_operator_contact_links l WHERE l.operator_id = p_operator AND l.removed_at IS NULL AND l.organization_id = p_org)
$$;

-- Alvo elegivel (cenario C): organizacao ativa; conta interna ativa; vinculo interno ativo; ORG_ADMIN efetivo;
-- fator confirmado e ativo. Exige o contexto nekkora.fn_organization_id = p_org (RLS). Motivo nulo = elegivel.
CREATE FUNCTION ops.alvo_inelegivel(p_org UUID, p_target UUID) RETURNS TEXT
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT CASE
        WHEN NOT EXISTS (SELECT 1 FROM public.organizations o WHERE o.organization_id = p_org AND o.status = 1) THEN 'ORGANIZACAO_INELEGIVEL'
        WHEN NOT EXISTS (SELECT 1 FROM public.users u WHERE u.organization_id = p_org AND u.user_id = p_target
                         AND u.status = 2 AND u.account_type = 1) THEN 'CONTA_INELEGIVEL'
        WHEN NOT EXISTS (SELECT 1 FROM public.organization_memberships m WHERE m.organization_id = p_org AND m.user_id = p_target
                         AND m.status = 2) THEN 'VINCULO_INTERNO_INATIVO'
        WHEN NOT EXISTS (SELECT 1 FROM public.organization_role_assignments a
                         JOIN public.organization_memberships m ON m.organization_id = a.organization_id AND m.membership_id = a.membership_id
                         JOIN public.roles r ON r.role_id = a.role_id
                         WHERE a.organization_id = p_org AND m.user_id = p_target AND a.status = 1 AND m.status = 2
                           AND r.code = 'ORG_ADMIN') THEN 'NAO_ORG_ADMIN'
        WHEN NOT EXISTS (SELECT 1 FROM public.user_mfa_factors f WHERE f.organization_id = p_org AND f.user_id = p_target
                         AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL) THEN 'SEM_FATOR_CONFIRMADO'
    END
$$;

-- Demais ORG_ADMIN efetivos da organizacao (destinatarios de aviso e contestacao).
CREATE FUNCTION ops.demais_admins(p_org UUID, p_target UUID) RETURNS SETOF UUID
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT DISTINCT m.user_id
    FROM public.organization_role_assignments a
    JOIN public.organization_memberships m ON m.organization_id = a.organization_id AND m.membership_id = a.membership_id
    JOIN public.users u ON u.organization_id = m.organization_id AND u.user_id = m.user_id
    JOIN public.roles r ON r.role_id = a.role_id
    WHERE a.organization_id = p_org AND a.status = 1 AND m.status = 2 AND u.status = 2 AND r.code = 'ORG_ADMIN' AND m.user_id <> p_target
    ORDER BY 1
$$;

-- Contato elegivel (D2): da organizacao, ativo, canal registrado ha >= 30 dias. Motivo nulo = elegivel.
CREATE FUNCTION ops.contato_inelegivel(p_org UUID, p_contact UUID, p_agora TIMESTAMPTZ) RETURNS TEXT
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT CASE
        WHEN c.contact_id IS NULL THEN 'CONTATO_INVALIDO'
        WHEN c.status <> 1 THEN 'CONTATO_INATIVO'
        WHEN c.channel_set_at > p_agora - interval '30 days' THEN 'CANAL_RECENTE'
    END
    FROM (SELECT 1) AS um
    LEFT JOIN ops.org_authorized_contacts c ON c.organization_id = p_org AND c.contact_id = p_contact
$$;

CREATE FUNCTION ops.contatos_elegiveis(p_org UUID, p_agora TIMESTAMPTZ) RETURNS INTEGER
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT count(*)::int FROM ops.org_authorized_contacts c
    WHERE c.organization_id = p_org AND c.status = 1 AND c.channel_set_at <= p_agora - interval '30 days'
$$;

-- Prazos (contrato, secao 6): T_total; nao verificada em 24 h; APROVADA alem de aprovacao_final + 60 min.
CREATE FUNCTION ops.vencida(p_state SMALLINT, p_opened_at TIMESTAMPTZ, p_t_total TIMESTAMPTZ, p_final_at TIMESTAMPTZ,
                            p_agora TIMESTAMPTZ) RETURNS BOOLEAN
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT p_state IN (1, 2, 3, 4) AND (p_agora >= p_t_total
        OR (p_state = 1 AND p_agora >= p_opened_at + interval '24 hours')
        OR (p_state = 4 AND p_agora >= p_final_at + interval '60 minutes'))
$$;

-- Limites executados (D6c), contados sob os locks da organizacao e da conta. Motivo nulo = dentro do limite.
CREATE FUNCTION ops.limite_executado(p_org UUID, p_target UUID, p_agora TIMESTAMPTZ) RETURNS TEXT
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM ops.ops_mfa_recovery_requests r WHERE r.target_user_id = p_target AND r.state = 5
                     AND r.executed_at > p_agora - interval '7 days') THEN 'LIMITE_ALVO'
        WHEN (SELECT count(*) FROM ops.ops_mfa_recovery_requests r WHERE r.organization_id = p_org AND r.state = 5
              AND r.executed_at > p_agora - interval '30 days') >= 3 THEN 'LIMITE_ORGANIZACAO'
    END
$$;

CREATE FUNCTION ops.registrar(p_request UUID, p_org UUID, p_target UUID, p_ato TEXT, p_resultado TEXT, p_motivo TEXT,
                              p_operator UUID, p_emissor TEXT, p_serial NUMERIC,
                              p_recipient UUID DEFAULT NULL, p_purpose SMALLINT DEFAULT NULL) RETURNS VOID
LANGUAGE sql SET search_path = pg_catalog, pg_temp AS $$
    INSERT INTO ops.ops_mfa_recovery_events (request_id, organization_id, target_user_id, act, result, reason, operator_id,
                                             cert_issuer_dn, cert_serial, recipient_user_id, purpose)
    VALUES (p_request, p_org, p_target, p_ato, p_resultado, p_motivo, p_operator, p_emissor, p_serial, p_recipient, p_purpose)
$$;

CREATE FUNCTION ops.registrar_cadastro(p_kind TEXT, p_resultado TEXT, p_motivo TEXT, p_actor_kind SMALLINT, p_actor UUID,
                                       p_target UUID DEFAULT NULL, p_act SMALLINT DEFAULT NULL, p_certificate UUID DEFAULT NULL,
                                       p_org UUID DEFAULT NULL, p_contact UUID DEFAULT NULL,
                                       p_emissor TEXT DEFAULT NULL, p_serial NUMERIC DEFAULT NULL) RETURNS VOID
LANGUAGE sql SET search_path = pg_catalog, pg_temp AS $$
    INSERT INTO ops.ops_registry_events (kind, result, reason, actor_kind, actor_operator_id, target_operator_id, act,
                                         certificate_id, organization_id, contact_id, cert_issuer_dn, cert_serial)
    VALUES (p_kind, p_resultado, p_motivo, p_actor_kind, p_actor, p_target, p_act, p_certificate, p_org, p_contact, p_emissor, p_serial)
$$;

-- Encerra a solicitacao (RECUSADA/CANCELADA/EXPIRADA) e registra. Chamada com a solicitacao travada.
CREATE FUNCTION ops.encerrar(p_request UUID, p_state SMALLINT, p_motivo TEXT, p_operator UUID, p_agora TIMESTAMPTZ) RETURNS VOID
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    UPDATE ops.ops_mfa_recovery_requests
       SET state = p_state, closed_at = p_agora, close_reason = p_motivo, closed_by = p_operator
     WHERE request_id = p_request AND state IN (1, 2, 3, 4);
    IF NOT FOUND THEN
        RAISE EXCEPTION 'encerramento de solicitacao nao terminal falhou' USING ERRCODE = 'P0001';
    END IF;
END;
$$;

-- Emissao dos avisos de um marco (pre-aprovacao ou execucao): titular + demais ORG_ADMIN efetivos.
CREATE FUNCTION ops.criar_avisos(p_request UUID, p_org UUID, p_target UUID, p_kind_titular SMALLINT) RETURNS VOID
LANGUAGE sql SET search_path = pg_catalog, pg_temp AS $$
    INSERT INTO ops.ops_mfa_notices (request_id, organization_id, target_user_id, recipient_user_id, kind)
    SELECT p_request, p_org, p_target, d.u, CASE WHEN d.u = p_target THEN p_kind_titular ELSE (p_kind_titular + 1)::smallint END
    FROM (SELECT p_target AS u UNION ALL SELECT a FROM ops.demais_admins(p_org, p_target) AS a) AS d
$$;

-- Guarda de isolamento (achado da revisao): toda funcao publica ops_* a chama como PRIMEIRA instrucao. Em
-- REPEATABLE READ ou SERIALIZABLE o snapshot e fixado no primeiro comando da transacao (ou no inicio desta chamada) e
-- nao avanca depois dos locks: uma revogacao de certificado ou de habilitacao confirmada depois dele ficaria invisivel
-- as conferencias do ato. Em READ COMMITTED cada comando da funcao le um snapshot novo, tirado depois dos locks.
-- SQLSTATE 25N01 (classe 25, estado de transacao invalido), sem efeito e sem registro (erro = rollback integral).
CREATE FUNCTION ops.exigir_read_committed() RETURNS VOID
LANGUAGE plpgsql VOLATILE SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'funcao operacional exige transaction_isolation=read committed (atual=%)',
            current_setting('transaction_isolation') USING ERRCODE = '25N01';
    END IF;
END;
$$;

-- === 13. Atos sobre a solicitacao (chamador: nekkora_ops_mfa) ===
--
-- Todas: SECURITY DEFINER, dono nekkora_ops_mfa_owner, search_path restrito e contexto interno
-- nekkora.fn_organization_id iniciado vazio pelo SET da propria funcao (restaurado na saida). Retorno:
-- (r_resultado OK|RECUSA, r_motivo, r_solicitacao, r_estado). Falha tecnica = erro (rollback integral).
-- Ordem de locks: organizacao -> conta alvo -> solicitacao -> operadores (crescente, FOR SHARE).

CREATE FUNCTION public.ops_mfa_open(p_org UUID, p_target UUID, p_requester_contact UUID)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT, r_solicitacao UUID, r_estado TEXT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_agora TIMESTAMPTZ;
    v_aut RECORD;
    v_motivo TEXT;
    v_org_ok BOOLEAN;
    v_target UUID;
    v_contato RECORD;
    v_id UUID;
BEGIN
    PERFORM ops.exigir_read_committed();
    PERFORM set_config('nekkora.fn_organization_id', coalesce(p_org::text, ''), true);
    SELECT true INTO v_org_ok FROM public.organizations WHERE organization_id = p_org FOR UPDATE;
    SELECT user_id INTO v_target FROM public.users WHERE organization_id = p_org AND user_id = p_target FOR UPDATE;
    PERFORM ops.travar_operadores(ARRAY[v_op]);
    v_agora := clock_timestamp();
    SELECT * INTO v_aut FROM ops.autorizar(v_op, 1::smallint, v_agora);
    v_motivo := v_aut.o_motivo;
    IF v_motivo IS NULL AND ops.tem_vinculo(v_op, p_org) THEN v_motivo := 'VINCULO_IMPEDITIVO'; END IF;
    IF v_motivo IS NULL AND v_org_ok IS NULL THEN v_motivo := 'ORGANIZACAO_INELEGIVEL'; END IF;
    IF v_motivo IS NULL AND v_target IS NULL THEN v_motivo := 'CONTA_INELEGIVEL'; END IF;
    IF v_motivo IS NULL THEN v_motivo := ops.alvo_inelegivel(p_org, p_target); END IF;
    IF v_motivo IS NULL AND ops.contatos_elegiveis(p_org, v_agora) < 2 THEN v_motivo := 'CONTATOS_INSUFICIENTES'; END IF;
    IF v_motivo IS NULL THEN v_motivo := ops.contato_inelegivel(p_org, p_requester_contact, v_agora); END IF;
    IF v_motivo IS NULL AND (SELECT count(*) FROM ops.ops_mfa_recovery_requests r WHERE r.requester_contact_id = p_requester_contact
                             AND r.opened_at > v_agora - interval '24 hours') >= 3 THEN
        v_motivo := 'LIMITE_ORIGEM';
    END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar(NULL, CASE WHEN v_org_ok THEN p_org END, v_target, 'ABRIR', 'RECUSA', v_motivo, v_op,
                              v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, NULL::uuid, NULL::text;
        RETURN;
    END IF;
    SELECT c.user_id INTO v_contato FROM ops.org_authorized_contacts c WHERE c.organization_id = p_org AND c.contact_id = p_requester_contact;
    INSERT INTO ops.ops_mfa_recovery_requests (organization_id, target_user_id, requester_contact_id, requester_kind, opened_at,
                                               opened_by, t_total)
    VALUES (p_org, p_target, p_requester_contact, CASE WHEN v_contato.user_id = p_target THEN 1 ELSE 2 END, v_agora, v_op,
            v_agora + interval '72 hours')
    RETURNING request_id INTO v_id;
    PERFORM ops.registrar(v_id, p_org, p_target, 'ABRIR', 'OK', NULL, v_op, v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text, v_id, 'SOLICITADA'::text;
END;
$$;

CREATE FUNCTION public.ops_mfa_verify(p_request UUID, p_callback_ok BOOLEAN, p_second_contact UUID, p_second_contact_ok BOOLEAN)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT, r_solicitacao UUID, r_estado TEXT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_org UUID;
    v_target UUID;
    r ops.ops_mfa_recovery_requests%ROWTYPE;
    v_agora TIMESTAMPTZ;
    v_aut RECORD;
    v_motivo TEXT;
    v_outra RECORD;
BEGIN
    PERFORM ops.exigir_read_committed();
    SELECT q.organization_id, q.target_user_id INTO v_org, v_target FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = p_request;
    IF NOT FOUND THEN
        SELECT * INTO v_aut FROM ops.autorizar(v_op, 1::smallint, clock_timestamp());
        PERFORM ops.registrar(NULL, NULL, NULL, 'VERIFICAR', 'RECUSA', 'SOLICITACAO_INEXISTENTE', v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, 'SOLICITACAO_INEXISTENTE'::text, p_request, NULL::text;
        RETURN;
    END IF;
    PERFORM set_config('nekkora.fn_organization_id', v_org::text, true);
    PERFORM 1 FROM public.organizations WHERE organization_id = v_org FOR UPDATE;
    PERFORM 1 FROM public.users WHERE organization_id = v_org AND user_id = v_target FOR UPDATE;
    SELECT * INTO r FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = p_request FOR UPDATE;
    PERFORM ops.travar_operadores(ARRAY[v_op]);
    v_agora := clock_timestamp();

    SELECT * INTO v_aut FROM ops.autorizar(v_op, 1::smallint, v_agora);
    v_motivo := v_aut.o_motivo;
    IF v_motivo IS NULL AND r.state <> 1 THEN v_motivo := 'ESTADO_INVALIDO'; END IF;
    IF v_motivo IS NULL AND ops.tem_vinculo(v_op, v_org) THEN v_motivo := 'VINCULO_IMPEDITIVO'; END IF;
    IF v_motivo IS NULL AND (p_second_contact IS NULL OR p_second_contact = r.requester_contact_id
                             OR p_callback_ok IS NULL OR p_second_contact_ok IS NULL) THEN
        v_motivo := 'PARAMETRO_INVALIDO';
    END IF;
    IF v_motivo IS NULL AND ops.contato_inelegivel(v_org, p_second_contact, v_agora) IS NOT NULL THEN
        v_motivo := 'SEGUNDO_CONTATO_INELEGIVEL';
    END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar(p_request, v_org, v_target, 'VERIFICAR', 'RECUSA', v_motivo, v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, p_request, ops.estado_nome(r.state);
        RETURN;
    END IF;

    -- a partir daqui, recusas encerram a solicitacao
    IF ops.vencida(r.state, r.opened_at, r.t_total, r.final_approved_at, v_agora) THEN
        PERFORM ops.encerrar(p_request, 8::smallint, 'PRAZO_ENCERRADO', v_op, v_agora);
        PERFORM ops.registrar(p_request, v_org, v_target, 'VERIFICAR', 'RECUSA', 'PRAZO_ENCERRADO', v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, 'PRAZO_ENCERRADO'::text, p_request, 'EXPIRADA'::text;
        RETURN;
    END IF;
    v_motivo := ops.alvo_inelegivel(v_org, v_target);
    IF v_motivo IS NULL AND ops.contatos_elegiveis(v_org, v_agora) < 2 THEN v_motivo := 'CONTATOS_INSUFICIENTES'; END IF;
    IF v_motivo IS NULL THEN v_motivo := ops.contato_inelegivel(v_org, r.requester_contact_id, v_agora); END IF;
    IF v_motivo IS NULL AND NOT (p_callback_ok AND p_second_contact_ok) THEN v_motivo := 'EVIDENCIA_NEGATIVA'; END IF;
    IF v_motivo IS NULL THEN v_motivo := ops.limite_executado(v_org, v_target, v_agora); END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.encerrar(p_request, 6::smallint, v_motivo, v_op, v_agora);
        PERFORM ops.registrar(p_request, v_org, v_target, 'VERIFICAR', 'RECUSA', v_motivo, v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, p_request, 'RECUSADA'::text;
        RETURN;
    END IF;

    -- exclusividade por alvo (D6b): encerra antes as vencidas; outra verificada vigente recusa este ato
    FOR v_outra IN SELECT q.request_id, q.state, q.opened_at, q.t_total, q.final_approved_at FROM ops.ops_mfa_recovery_requests q
                   WHERE q.target_user_id = v_target AND q.request_id <> p_request AND q.state IN (1, 2, 3, 4)
                   ORDER BY q.request_id FOR UPDATE LOOP
        IF ops.vencida(v_outra.state, v_outra.opened_at, v_outra.t_total, v_outra.final_approved_at, v_agora) THEN
            PERFORM ops.encerrar(v_outra.request_id, 8::smallint, 'PRAZO_ENCERRADO', v_op, v_agora);
        ELSIF v_outra.state IN (2, 3, 4) THEN
            v_motivo := 'SOLICITACAO_VERIFICADA_ABERTA';
        END IF;
    END LOOP;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar(p_request, v_org, v_target, 'VERIFICAR', 'RECUSA', v_motivo, v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, p_request, 'SOLICITADA'::text;
        RETURN;
    END IF;
    FOR v_outra IN SELECT q.request_id FROM ops.ops_mfa_recovery_requests q
                   WHERE q.target_user_id = v_target AND q.request_id <> p_request AND q.state = 1 ORDER BY q.request_id LOOP
        PERFORM ops.encerrar(v_outra.request_id, 7::smallint, 'SUBSTITUIDA', v_op, v_agora);
    END LOOP;

    UPDATE ops.ops_mfa_recovery_requests
       SET state = 2, verified_at = v_agora, verified_by = v_op, callback_ok = p_callback_ok,
           second_contact_id = p_second_contact, second_contact_ok = p_second_contact_ok
     WHERE request_id = p_request;
    PERFORM ops.registrar(p_request, v_org, v_target, 'VERIFICAR', 'OK', NULL, v_op, v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text, p_request, 'VERIFICADA'::text;
END;
$$;

CREATE FUNCTION public.ops_mfa_preapprove(p_request UUID)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT, r_solicitacao UUID, r_estado TEXT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_org UUID;
    v_target UUID;
    r ops.ops_mfa_recovery_requests%ROWTYPE;
    v_agora TIMESTAMPTZ;
    v_aut RECORD;
    v_motivo TEXT;
    v_fator UUID;
BEGIN
    PERFORM ops.exigir_read_committed();
    SELECT q.organization_id, q.target_user_id INTO v_org, v_target FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = p_request;
    IF NOT FOUND THEN
        SELECT * INTO v_aut FROM ops.autorizar(v_op, 2::smallint, clock_timestamp());
        PERFORM ops.registrar(NULL, NULL, NULL, 'PRE_APROVAR', 'RECUSA', 'SOLICITACAO_INEXISTENTE', v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, 'SOLICITACAO_INEXISTENTE'::text, p_request, NULL::text;
        RETURN;
    END IF;
    PERFORM set_config('nekkora.fn_organization_id', v_org::text, true);
    PERFORM 1 FROM public.organizations WHERE organization_id = v_org FOR UPDATE;
    PERFORM 1 FROM public.users WHERE organization_id = v_org AND user_id = v_target FOR UPDATE;
    SELECT * INTO r FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = p_request FOR UPDATE;
    PERFORM ops.travar_operadores(ARRAY[v_op, r.verified_by]);
    v_agora := clock_timestamp();

    SELECT * INTO v_aut FROM ops.autorizar(v_op, 2::smallint, v_agora);
    v_motivo := v_aut.o_motivo;
    IF v_motivo IS NULL AND r.state <> 2 THEN v_motivo := 'ESTADO_INVALIDO'; END IF;
    IF v_motivo IS NULL AND v_op = r.verified_by THEN v_motivo := 'APROVADOR_IGUAL_VERIFICADOR'; END IF;
    IF v_motivo IS NULL AND ops.tem_vinculo(v_op, v_org) THEN v_motivo := 'VINCULO_IMPEDITIVO'; END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar(p_request, v_org, v_target, 'PRE_APROVAR', 'RECUSA', v_motivo, v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, p_request, ops.estado_nome(r.state);
        RETURN;
    END IF;
    IF ops.vencida(r.state, r.opened_at, r.t_total, r.final_approved_at, v_agora) THEN
        PERFORM ops.encerrar(p_request, 8::smallint, 'PRAZO_ENCERRADO', v_op, v_agora);
        PERFORM ops.registrar(p_request, v_org, v_target, 'PRE_APROVAR', 'RECUSA', 'PRAZO_ENCERRADO', v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, 'PRAZO_ENCERRADO'::text, p_request, 'EXPIRADA'::text;
        RETURN;
    END IF;
    v_motivo := ops.participante_invalido(r.verified_by, 1::smallint, v_org, v_agora);
    IF v_motivo IS NULL THEN v_motivo := ops.alvo_inelegivel(v_org, v_target); END IF;
    IF v_motivo IS NULL THEN v_motivo := ops.limite_executado(v_org, v_target, v_agora); END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.encerrar(p_request, 6::smallint, v_motivo, v_op, v_agora);
        PERFORM ops.registrar(p_request, v_org, v_target, 'PRE_APROVAR', 'RECUSA', v_motivo, v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, p_request, 'RECUSADA'::text;
        RETURN;
    END IF;
    SELECT f.factor_id INTO v_fator FROM public.user_mfa_factors f
    WHERE f.organization_id = v_org AND f.user_id = v_target AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL;
    UPDATE ops.ops_mfa_recovery_requests
       SET state = 3, preapproved_at = v_agora, preapproved_by = v_op, approved_factor_id = v_fator
     WHERE request_id = p_request;
    PERFORM ops.criar_avisos(p_request, v_org, v_target, 1::smallint);
    PERFORM ops.registrar(p_request, v_org, v_target, 'PRE_APROVAR', 'OK', NULL, v_op, v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text, p_request, 'PRE_APROVADA'::text;
END;
$$;

CREATE FUNCTION public.ops_mfa_final_approve(p_request UUID)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT, r_solicitacao UUID, r_estado TEXT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_org UUID;
    v_target UUID;
    r ops.ops_mfa_recovery_requests%ROWTYPE;
    v_agora TIMESTAMPTZ;
    v_aut RECORD;
    v_motivo TEXT;
BEGIN
    PERFORM ops.exigir_read_committed();
    SELECT q.organization_id, q.target_user_id INTO v_org, v_target FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = p_request;
    IF NOT FOUND THEN
        SELECT * INTO v_aut FROM ops.autorizar(v_op, 2::smallint, clock_timestamp());
        PERFORM ops.registrar(NULL, NULL, NULL, 'APROVAR', 'RECUSA', 'SOLICITACAO_INEXISTENTE', v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, 'SOLICITACAO_INEXISTENTE'::text, p_request, NULL::text;
        RETURN;
    END IF;
    PERFORM set_config('nekkora.fn_organization_id', v_org::text, true);
    PERFORM 1 FROM public.organizations WHERE organization_id = v_org FOR UPDATE;
    PERFORM 1 FROM public.users WHERE organization_id = v_org AND user_id = v_target FOR UPDATE;
    SELECT * INTO r FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = p_request FOR UPDATE;
    PERFORM ops.travar_operadores(ARRAY[v_op, r.verified_by, r.preapproved_by]);
    v_agora := clock_timestamp();

    SELECT * INTO v_aut FROM ops.autorizar(v_op, 2::smallint, v_agora);
    v_motivo := v_aut.o_motivo;
    IF v_motivo IS NULL AND r.state <> 3 THEN v_motivo := 'ESTADO_INVALIDO'; END IF;
    IF v_motivo IS NULL AND v_op = r.verified_by THEN v_motivo := 'APROVADOR_IGUAL_VERIFICADOR'; END IF;
    IF v_motivo IS NULL AND ops.tem_vinculo(v_op, v_org) THEN v_motivo := 'VINCULO_IMPEDITIVO'; END IF;
    IF v_motivo IS NULL AND NOT ops.vencida(r.state, r.opened_at, r.t_total, r.final_approved_at, v_agora) THEN
        IF r.notice_confirmed_at IS NULL THEN
            v_motivo := 'AVISO_NAO_CONFIRMADO';
        ELSIF v_agora < r.notice_confirmed_at + interval '24 hours' THEN
            v_motivo := 'ESPERA_NAO_CUMPRIDA';
        END IF;
    END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar(p_request, v_org, v_target, 'APROVAR', 'RECUSA', v_motivo, v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, p_request, ops.estado_nome(r.state);
        RETURN;
    END IF;
    IF ops.vencida(r.state, r.opened_at, r.t_total, r.final_approved_at, v_agora) THEN
        PERFORM ops.encerrar(p_request, 8::smallint, 'PRAZO_ENCERRADO', v_op, v_agora);
        PERFORM ops.registrar(p_request, v_org, v_target, 'APROVAR', 'RECUSA', 'PRAZO_ENCERRADO', v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, 'PRAZO_ENCERRADO'::text, p_request, 'EXPIRADA'::text;
        RETURN;
    END IF;
    v_motivo := ops.participante_invalido(r.verified_by, 1::smallint, v_org, v_agora);
    IF v_motivo IS NULL THEN v_motivo := ops.participante_invalido(r.preapproved_by, 2::smallint, v_org, v_agora); END IF;
    IF v_motivo IS NULL AND NOT EXISTS (SELECT 1 FROM public.user_mfa_factors f WHERE f.organization_id = v_org AND f.user_id = v_target
                                        AND f.factor_id = r.approved_factor_id AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL) THEN
        v_motivo := 'FATOR_DIVERGENTE';
    END IF;
    IF v_motivo IS NULL THEN v_motivo := ops.alvo_inelegivel(v_org, v_target); END IF;
    IF v_motivo IS NULL THEN v_motivo := ops.limite_executado(v_org, v_target, v_agora); END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.encerrar(p_request, 6::smallint, v_motivo, v_op, v_agora);
        PERFORM ops.registrar(p_request, v_org, v_target, 'APROVAR', 'RECUSA', v_motivo, v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, p_request, 'RECUSADA'::text;
        RETURN;
    END IF;
    UPDATE ops.ops_mfa_recovery_requests SET state = 4, final_approved_at = v_agora, final_approved_by = v_op WHERE request_id = p_request;
    PERFORM ops.registrar(p_request, v_org, v_target, 'APROVAR', 'OK', NULL, v_op, v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text, p_request, 'APROVADA'::text;
END;
$$;

-- Execucao (contrato, secao 7). Efeitos iguais aos do reset administrativo; senha, papeis, vinculos, concessoes,
-- status e e-mail nao mudam.
CREATE FUNCTION public.ops_mfa_execute(p_request UUID)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT, r_solicitacao UUID, r_estado TEXT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_org UUID;
    v_target UUID;
    r ops.ops_mfa_recovery_requests%ROWTYPE;
    v_agora TIMESTAMPTZ;
    v_aut RECORD;
    v_motivo TEXT;
    v_fator_ativo RECORD;
    v_n INTEGER;
    v_ok UUID;
BEGIN
    PERFORM ops.exigir_read_committed();
    SELECT q.organization_id, q.target_user_id INTO v_org, v_target FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = p_request;
    IF NOT FOUND THEN
        SELECT * INTO v_aut FROM ops.autorizar(v_op, 3::smallint, clock_timestamp());
        PERFORM ops.registrar(NULL, NULL, NULL, 'EXECUTAR', 'RECUSA', 'SOLICITACAO_INEXISTENTE', v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, 'SOLICITACAO_INEXISTENTE'::text, p_request, NULL::text;
        RETURN;
    END IF;
    -- 1. locks: organizacao -> conta -> solicitacao -> participantes (crescente, FOR SHARE)
    PERFORM set_config('nekkora.fn_organization_id', v_org::text, true);
    PERFORM 1 FROM public.organizations WHERE organization_id = v_org FOR UPDATE;
    PERFORM 1 FROM public.users WHERE organization_id = v_org AND user_id = v_target FOR UPDATE;
    SELECT * INTO r FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = p_request FOR UPDATE;
    PERFORM ops.travar_operadores(ARRAY[v_op, r.verified_by, r.preapproved_by, r.final_approved_by]);
    -- 2. instante do banco depois dos locks
    v_agora := clock_timestamp();

    -- 3. reconferencias do ato (recusa sem encerrar: outro executor pode agir)
    SELECT * INTO v_aut FROM ops.autorizar(v_op, 3::smallint, v_agora);
    v_motivo := v_aut.o_motivo;
    IF v_motivo IS NULL AND r.state = 5 THEN v_motivo := 'JA_EXECUTADA'; END IF;
    IF v_motivo IS NULL AND r.state <> 4 THEN v_motivo := 'ESTADO_INVALIDO'; END IF;
    IF v_motivo IS NULL AND (v_op = r.preapproved_by OR v_op = r.final_approved_by) THEN v_motivo := 'EXECUTOR_IGUAL_APROVADOR'; END IF;
    IF v_motivo IS NULL AND ops.tem_vinculo(v_op, v_org) THEN v_motivo := 'VINCULO_IMPEDITIVO'; END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar(p_request, v_org, v_target, 'EXECUTAR', 'RECUSA', v_motivo, v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, p_request, ops.estado_nome(r.state);
        RETURN;
    END IF;
    IF ops.vencida(r.state, r.opened_at, r.t_total, r.final_approved_at, v_agora) THEN
        PERFORM ops.encerrar(p_request, 8::smallint, 'PRAZO_ENCERRADO', v_op, v_agora);
        PERFORM ops.registrar(p_request, v_org, v_target, 'EXECUTAR', 'RECUSA', 'PRAZO_ENCERRADO', v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, 'PRAZO_ENCERRADO'::text, p_request, 'EXPIRADA'::text;
        RETURN;
    END IF;
    -- reconferencias da solicitacao (recusa encerra: RECUSADA na execucao)
    v_motivo := ops.participante_invalido(r.verified_by, 1::smallint, v_org, v_agora);
    IF v_motivo IS NULL THEN v_motivo := ops.participante_invalido(r.preapproved_by, 2::smallint, v_org, v_agora); END IF;
    IF v_motivo IS NULL THEN v_motivo := ops.participante_invalido(r.final_approved_by, 2::smallint, v_org, v_agora); END IF;
    -- fator exatamente o aprovado, confirmado e ativo (antes da elegibilidade geral: reset concorrente = FATOR_DIVERGENTE)
    IF v_motivo IS NULL THEN
        SELECT f.factor_id, f.confirmed_at INTO v_fator_ativo FROM public.user_mfa_factors f
        WHERE f.organization_id = v_org AND f.user_id = v_target AND f.disabled_at IS NULL;
        IF v_fator_ativo.factor_id IS DISTINCT FROM r.approved_factor_id OR v_fator_ativo.confirmed_at IS NULL THEN
            v_motivo := 'FATOR_DIVERGENTE';
        END IF;
    END IF;
    IF v_motivo IS NULL THEN v_motivo := ops.alvo_inelegivel(v_org, v_target); END IF;
    IF v_motivo IS NULL THEN v_motivo := ops.limite_executado(v_org, v_target, v_agora); END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.encerrar(p_request, 6::smallint, v_motivo, v_op, v_agora);
        PERFORM ops.registrar(p_request, v_org, v_target, 'EXECUTAR', 'RECUSA', v_motivo, v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, p_request, 'RECUSADA'::text;
        RETURN;
    END IF;

    -- 4. efeitos (os do reset administrativo)
    UPDATE public.auth_challenges SET invalidated_at = v_agora, credential_hash = NULL, pending_password_hash = NULL
     WHERE organization_id = v_org AND user_id = v_target AND consumed_at IS NULL AND invalidated_at IS NULL;
    UPDATE public.user_mfa_recovery_codes SET invalidated_at = v_agora
     WHERE organization_id = v_org AND user_id = v_target AND used_at IS NULL AND invalidated_at IS NULL;
    UPDATE public.user_mfa_factors SET disabled_at = v_agora
     WHERE organization_id = v_org AND user_id = v_target AND disabled_at IS NULL;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    IF v_n <> 1 THEN
        RAISE EXCEPTION 'execucao: fator aprovado nao desabilitado' USING ERRCODE = 'P0001';
    END IF;
    UPDATE public.user_sessions SET revoked_at = v_agora
     WHERE organization_id = v_org AND user_id = v_target AND revoked_at IS NULL;
    INSERT INTO public.mfa_events (organization_id, user_id, event_type, correlation_id, ops_request_id)
    VALUES (v_org, v_target, 4, gen_random_uuid(), p_request);
    UPDATE ops.ops_mfa_recovery_requests SET state = 5, executed_at = v_agora, executed_by = v_op
     WHERE request_id = p_request AND state = 4
    RETURNING request_id INTO v_ok;
    IF v_ok IS NULL THEN
        RAISE EXCEPTION 'execucao: transicao para EXECUTADA falhou' USING ERRCODE = 'P0001';
    END IF;
    PERFORM ops.criar_avisos(p_request, v_org, v_target, 3::smallint);
    PERFORM ops.registrar(p_request, v_org, v_target, 'EXECUTAR', 'OK', NULL, v_op, v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text, p_request, 'EXECUTADA'::text;
END;
$$;

CREATE FUNCTION public.ops_mfa_cancel(p_request UUID, p_reason TEXT)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT, r_solicitacao UUID, r_estado TEXT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_org UUID;
    v_target UUID;
    r ops.ops_mfa_recovery_requests%ROWTYPE;
    v_agora TIMESTAMPTZ;
    v_aut RECORD;
    v_motivo TEXT;
BEGIN
    PERFORM ops.exigir_read_committed();
    SELECT q.organization_id, q.target_user_id INTO v_org, v_target FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = p_request;
    IF NOT FOUND THEN
        SELECT * INTO v_aut FROM ops.autorizar(v_op, 4::smallint, clock_timestamp());
        PERFORM ops.registrar(NULL, NULL, NULL, 'CANCELAR', 'RECUSA', 'SOLICITACAO_INEXISTENTE', v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, 'SOLICITACAO_INEXISTENTE'::text, p_request, NULL::text;
        RETURN;
    END IF;
    PERFORM set_config('nekkora.fn_organization_id', v_org::text, true);
    PERFORM 1 FROM public.organizations WHERE organization_id = v_org FOR UPDATE;
    PERFORM 1 FROM public.users WHERE organization_id = v_org AND user_id = v_target FOR UPDATE;
    SELECT * INTO r FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = p_request FOR UPDATE;
    PERFORM ops.travar_operadores(ARRAY[v_op]);
    v_agora := clock_timestamp();
    SELECT * INTO v_aut FROM ops.autorizar(v_op, 4::smallint, v_agora);
    v_motivo := v_aut.o_motivo;
    IF v_motivo IS NULL AND (p_reason IS NULL OR p_reason NOT IN ('SOLICITANTE_DESISTIU', 'SUSPEITA_FRAUDE', 'ERRO_OPERACIONAL',
                                                                  'PRESSAO_URGENCIA', 'OUTRO')) THEN
        v_motivo := 'PARAMETRO_INVALIDO';
    END IF;
    IF v_motivo IS NULL AND r.state NOT IN (1, 2, 3, 4) THEN v_motivo := 'ESTADO_INVALIDO'; END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar(p_request, v_org, v_target, 'CANCELAR', 'RECUSA', v_motivo, v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, p_request, ops.estado_nome(r.state);
        RETURN;
    END IF;
    PERFORM ops.encerrar(p_request, 7::smallint, p_reason, v_op, v_agora);
    PERFORM ops.registrar(p_request, v_org, v_target, 'CANCELAR', 'OK', NULL, v_op, v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text, p_request, 'CANCELADA'::text;
END;
$$;

-- Reenvio do aviso ao titular (so em PRE_APROVADA). Depois da confirmacao, o novo aviso so leva CONTESTAR.
CREATE FUNCTION public.ops_mfa_resend_notice(p_request UUID)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT, r_solicitacao UUID, r_estado TEXT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_org UUID;
    v_target UUID;
    r ops.ops_mfa_recovery_requests%ROWTYPE;
    v_agora TIMESTAMPTZ;
    v_aut RECORD;
    v_motivo TEXT;
BEGIN
    PERFORM ops.exigir_read_committed();
    SELECT q.organization_id, q.target_user_id INTO v_org, v_target FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = p_request;
    IF NOT FOUND THEN
        SELECT * INTO v_aut FROM ops.autorizar(v_op, 1::smallint, clock_timestamp());
        PERFORM ops.registrar(NULL, NULL, NULL, 'REENVIAR_AVISO', 'RECUSA', 'SOLICITACAO_INEXISTENTE', v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, 'SOLICITACAO_INEXISTENTE'::text, p_request, NULL::text;
        RETURN;
    END IF;
    PERFORM set_config('nekkora.fn_organization_id', v_org::text, true);
    PERFORM 1 FROM public.organizations WHERE organization_id = v_org FOR UPDATE;
    PERFORM 1 FROM public.users WHERE organization_id = v_org AND user_id = v_target FOR UPDATE;
    SELECT * INTO r FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = p_request FOR UPDATE;
    PERFORM ops.travar_operadores(ARRAY[v_op]);
    v_agora := clock_timestamp();
    SELECT * INTO v_aut FROM ops.autorizar(v_op, 1::smallint, v_agora);
    v_motivo := v_aut.o_motivo;
    IF v_motivo IS NULL AND r.state <> 3 THEN v_motivo := 'ESTADO_INVALIDO'; END IF;
    IF v_motivo IS NULL AND ops.vencida(r.state, r.opened_at, r.t_total, r.final_approved_at, v_agora) THEN
        PERFORM ops.encerrar(p_request, 8::smallint, 'PRAZO_ENCERRADO', v_op, v_agora);
        r.state := 8;
        v_motivo := 'PRAZO_ENCERRADO';
    END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar(p_request, v_org, v_target, 'REENVIAR_AVISO', 'RECUSA', v_motivo, v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, p_request, ops.estado_nome(r.state);
        RETURN;
    END IF;
    INSERT INTO ops.ops_mfa_notices (request_id, organization_id, target_user_id, recipient_user_id, kind)
    VALUES (p_request, v_org, v_target, v_target, 1);
    PERFORM ops.registrar(p_request, v_org, v_target, 'REENVIAR_AVISO', 'OK', NULL, v_op, v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text, p_request, 'PRE_APROVADA'::text;
END;
$$;

-- Consulta com reconciliacao (contrato, secao 7): espera a MESMA trava da solicitacao, com tempo limite fixo
-- (10 s, restaurado na saida). Sem a trava: TRAVA_NAO_OBTIDA (a CLI mantem "desconhecido"; nada e gravado, pois o
-- registro exigiria a propria trava).
CREATE FUNCTION public.ops_mfa_status(p_request UUID)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT, r_solicitacao UUID, r_estado TEXT, r_reset_registrado BOOLEAN,
               r_executada_em TIMESTAMPTZ, r_prazo_total TIMESTAMPTZ, r_confirmacao_aviso TIMESTAMPTZ,
               r_aprovacao_final TIMESTAMPTZ, r_encerramento TEXT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = '' SET lock_timeout = '10s'
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_org UUID;
    v_target UUID;
    r ops.ops_mfa_recovery_requests%ROWTYPE;
    v_agora TIMESTAMPTZ;
    v_aut RECORD;
    v_reset BOOLEAN;
BEGIN
    PERFORM ops.exigir_read_committed();
    SELECT q.organization_id, q.target_user_id INTO v_org, v_target FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = p_request;
    IF NOT FOUND THEN
        SELECT * INTO v_aut FROM ops.autorizar(v_op, 5::smallint, clock_timestamp());
        PERFORM ops.registrar(NULL, NULL, NULL, 'CONSULTAR', 'RECUSA', 'SOLICITACAO_INEXISTENTE', v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, 'SOLICITACAO_INEXISTENTE'::text, p_request, NULL::text, NULL::boolean,
                            NULL::timestamptz, NULL::timestamptz, NULL::timestamptz, NULL::timestamptz, NULL::text;
        RETURN;
    END IF;
    PERFORM set_config('nekkora.fn_organization_id', v_org::text, true);
    BEGIN
        PERFORM 1 FROM public.organizations WHERE organization_id = v_org FOR UPDATE;
        PERFORM 1 FROM public.users WHERE organization_id = v_org AND user_id = v_target FOR UPDATE;
        SELECT * INTO r FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = p_request FOR UPDATE;
    EXCEPTION WHEN lock_not_available THEN
        RETURN QUERY SELECT 'RECUSA'::text, 'TRAVA_NAO_OBTIDA'::text, p_request, NULL::text, NULL::boolean,
                            NULL::timestamptz, NULL::timestamptz, NULL::timestamptz, NULL::timestamptz, NULL::text;
        RETURN;
    END;
    PERFORM ops.travar_operadores(ARRAY[v_op]);
    v_agora := clock_timestamp();
    SELECT * INTO v_aut FROM ops.autorizar(v_op, 5::smallint, v_agora);
    IF v_aut.o_motivo IS NOT NULL THEN
        PERFORM ops.registrar(p_request, v_org, v_target, 'CONSULTAR', 'RECUSA', v_aut.o_motivo, v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_aut.o_motivo, p_request, NULL::text, NULL::boolean,
                            NULL::timestamptz, NULL::timestamptz, NULL::timestamptz, NULL::timestamptz, NULL::text;
        RETURN;
    END IF;
    v_reset := EXISTS (SELECT 1 FROM public.mfa_events e WHERE e.organization_id = v_org AND e.ops_request_id = p_request);
    PERFORM ops.registrar(p_request, v_org, v_target, 'CONSULTAR', 'OK', NULL, v_op, v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text, p_request, ops.estado_nome(r.state), v_reset, r.executed_at, r.t_total,
                        r.notice_confirmed_at, r.final_approved_at, r.close_reason;
END;
$$;

-- Falha tecnica informada pela CLI (origem INFORMADO_PELA_CLI), em transacao separada. Sem mensagem livre.
-- Ordem: solicitacao (KEY SHARE, pela FK) -> operador (FOR SHARE).
CREATE FUNCTION public.ops_mfa_record_failure(p_request UUID, p_failed_act TEXT, p_sqlstate TEXT, p_stage TEXT)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_req RECORD;
    v_aut RECORD;
BEGIN
    PERFORM ops.exigir_read_committed();
    SELECT q.request_id, q.organization_id, q.target_user_id INTO v_req
    FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = p_request FOR KEY SHARE;
    PERFORM ops.travar_operadores(ARRAY[v_op]);
    SELECT * INTO v_aut FROM ops.autorizar(v_op, NULL, clock_timestamp());
    IF v_aut.o_motivo IS NOT NULL OR v_req.request_id IS NULL
       OR coalesce(p_failed_act, '') NOT IN ('ABRIR', 'VERIFICAR', 'PRE_APROVAR', 'APROVAR', 'EXECUTAR', 'CANCELAR', 'CONSULTAR', 'REENVIAR_AVISO')
       OR coalesce(p_sqlstate, '') !~ '^[0-9A-Z]{5}$' OR coalesce(p_stage, '') NOT IN ('CHAMADA', 'COMMIT') THEN
        RETURN QUERY SELECT 'RECUSA'::text, coalesce(v_aut.o_motivo, CASE WHEN v_req.request_id IS NULL THEN 'SOLICITACAO_INEXISTENTE'
                                                                          ELSE 'PARAMETRO_INVALIDO' END);
        RETURN;
    END IF;
    INSERT INTO ops.ops_mfa_recovery_events (request_id, organization_id, target_user_id, act, result, operator_id, cert_issuer_dn,
                                             cert_serial, origin, failed_act, sqlstate, stage)
    VALUES (v_req.request_id, v_req.organization_id, v_req.target_user_id, 'FALHA', 'FALHA', v_op, v_aut.o_emissor, v_aut.o_serial,
            2, p_failed_act, p_sqlstate, p_stage);
    RETURN QUERY SELECT 'OK'::text, NULL::text;
END;
$$;

-- === 14. Cadastro operacional (chamador: nekkora_ops_mfa; ato CADASTRAR) ===
--
-- Caminhos que alteram operador, habilitacao, vinculo ou certificado travam as linhas de ops_operators como
-- PRIMEIRA instrucao (crescente; alvo FOR UPDATE, ator FOR SHARE) e nao pedem os locks de organizacao, conta ou
-- solicitacao.

CREATE FUNCTION ops.papel_operador_invalido(p_db_role TEXT) RETURNS TEXT
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT CASE
        WHEN p_db_role IS NULL OR p_db_role !~ '^op_[a-z0-9_]{2,40}$' THEN 'PAPEL_INVALIDO'
        WHEN NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles r WHERE r.rolname = p_db_role) THEN 'PAPEL_INEXISTENTE'
        WHEN EXISTS (SELECT 1 FROM pg_catalog.pg_roles r WHERE r.rolname = p_db_role
                     AND (r.rolsuper OR r.rolcreaterole OR r.rolcreatedb OR r.rolreplication OR r.rolbypassrls)) THEN 'PAPEL_PRIVILEGIADO'
        WHEN (SELECT array_agg(g.rolname) FROM pg_catalog.pg_auth_members m JOIN pg_catalog.pg_roles g ON g.oid = m.roleid
              JOIN pg_catalog.pg_roles u ON u.oid = m.member WHERE u.rolname = p_db_role) IS DISTINCT FROM ARRAY['nekkora_ops_mfa']::name[]
            THEN 'PAPEL_MEMBERSHIP_INVALIDA'
        WHEN EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members m JOIN pg_catalog.pg_roles g ON g.oid = m.roleid
                     WHERE g.rolname = p_db_role) THEN 'PAPEL_COM_MEMBROS'
    END
$$;

-- Bootstrap: primeiro operador com CADASTRAR, so com o cadastro vazio. Sem EXECUTE para nenhum papel (superusuario).
CREATE FUNCTION public.ops_bootstrap_operator(p_db_role TEXT, p_idp_issuer TEXT, p_idp_subject TEXT) RETURNS UUID
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_id UUID;
    v_motivo TEXT := ops.papel_operador_invalido(p_db_role);
BEGIN
    PERFORM ops.exigir_read_committed();
    LOCK TABLE ops.ops_operators IN SHARE ROW EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM ops.ops_operators) THEN
        RAISE EXCEPTION 'bootstrap so com o cadastro operacional vazio' USING ERRCODE = '42501';
    END IF;
    IF v_motivo IS NOT NULL THEN
        RAISE EXCEPTION 'papel do operador invalido: %', v_motivo USING ERRCODE = '22023';
    END IF;
    INSERT INTO ops.ops_operators (db_role, idp_issuer, idp_subject) VALUES (p_db_role, p_idp_issuer, p_idp_subject) RETURNING operator_id INTO v_id;
    INSERT INTO ops.ops_operator_permissions (operator_id, act) VALUES (v_id, 6);
    PERFORM ops.registrar_cadastro('OPERADOR_CRIADO', 'OK', NULL, 4::smallint, NULL, v_id);
    PERFORM ops.registrar_cadastro('HABILITACAO_CONCEDIDA', 'OK', NULL, 4::smallint, NULL, v_id, 6::smallint);
    RETURN v_id;
END;
$$;

CREATE FUNCTION public.ops_operator_create(p_db_role TEXT, p_idp_issuer TEXT, p_idp_subject TEXT)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT, r_operador UUID)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_aut RECORD;
    v_motivo TEXT;
    v_id UUID;
BEGIN
    PERFORM ops.exigir_read_committed();
    PERFORM ops.travar_operadores(ARRAY[v_op]);
    SELECT * INTO v_aut FROM ops.autorizar(v_op, 6::smallint, clock_timestamp());
    v_motivo := coalesce(v_aut.o_motivo, ops.papel_operador_invalido(p_db_role));
    IF v_motivo IS NULL AND (p_idp_issuer IS NULL OR btrim(p_idp_issuer) = '' OR p_idp_subject IS NULL OR btrim(p_idp_subject) = '') THEN
        v_motivo := 'PARAMETRO_INVALIDO';
    END IF;
    IF v_motivo IS NULL AND EXISTS (SELECT 1 FROM ops.ops_operators o WHERE o.db_role = p_db_role
                                    OR (o.idp_issuer = p_idp_issuer AND o.idp_subject = p_idp_subject)) THEN
        v_motivo := 'OPERADOR_DUPLICADO';
    END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar_cadastro('OPERADOR_CRIADO', 'RECUSA', v_motivo, 1::smallint, v_op, NULL, NULL, NULL, NULL, NULL,
                                       v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, NULL::uuid;
        RETURN;
    END IF;
    INSERT INTO ops.ops_operators (db_role, idp_issuer, idp_subject, created_by) VALUES (p_db_role, p_idp_issuer, p_idp_subject, v_op)
    RETURNING operator_id INTO v_id;
    PERFORM ops.registrar_cadastro('OPERADOR_CRIADO', 'OK', NULL, 1::smallint, v_op, v_id, NULL, NULL, NULL, NULL, v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text, v_id;
END;
$$;

CREATE FUNCTION public.ops_operator_grant(p_operator UUID, p_act TEXT)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_act SMALLINT := ops.ato_codigo(p_act);
    v_aut RECORD;
    v_motivo TEXT;
BEGIN
    PERFORM ops.exigir_read_committed();
    PERFORM ops.travar_operadores(ARRAY[v_op], ARRAY[p_operator]);
    SELECT * INTO v_aut FROM ops.autorizar(v_op, 6::smallint, clock_timestamp());
    v_motivo := v_aut.o_motivo;
    IF v_motivo IS NULL AND v_act IS NULL THEN v_motivo := 'PARAMETRO_INVALIDO'; END IF;
    IF v_motivo IS NULL AND p_operator = v_op THEN v_motivo := 'AUTO_HABILITACAO'; END IF;
    IF v_motivo IS NULL AND NOT EXISTS (SELECT 1 FROM ops.ops_operators o WHERE o.operator_id = p_operator AND o.status = 1) THEN
        v_motivo := 'OPERADOR_INATIVO';
    END IF;
    IF v_motivo IS NULL AND EXISTS (SELECT 1 FROM ops.ops_operator_permissions p WHERE p.operator_id = p_operator AND p.act = v_act
                                    AND p.revoked_at IS NULL) THEN
        v_motivo := 'JA_HABILITADO';
    END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar_cadastro('HABILITACAO_CONCEDIDA', 'RECUSA', v_motivo, 1::smallint, v_op,
                                       CASE WHEN EXISTS (SELECT 1 FROM ops.ops_operators o WHERE o.operator_id = p_operator) THEN p_operator END,
                                       v_act, NULL, NULL, NULL, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo;
        RETURN;
    END IF;
    INSERT INTO ops.ops_operator_permissions (operator_id, act, granted_by) VALUES (p_operator, v_act, v_op);
    PERFORM ops.registrar_cadastro('HABILITACAO_CONCEDIDA', 'OK', NULL, 1::smallint, v_op, p_operator, v_act, NULL, NULL, NULL,
                                   v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text;
END;
$$;

-- Retirada de habilitacao: tambem revoga os certificados vigentes do operador (contrato 3.2).
CREATE FUNCTION public.ops_operator_revoke(p_operator UUID, p_act TEXT)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_act SMALLINT := ops.ato_codigo(p_act);
    v_agora TIMESTAMPTZ;
    v_aut RECORD;
    v_motivo TEXT;
    v_cert UUID;
BEGIN
    PERFORM ops.exigir_read_committed();
    PERFORM ops.travar_operadores(ARRAY[v_op], ARRAY[p_operator]);
    v_agora := clock_timestamp();
    SELECT * INTO v_aut FROM ops.autorizar(v_op, 6::smallint, v_agora);
    v_motivo := v_aut.o_motivo;
    IF v_motivo IS NULL AND v_act IS NULL THEN v_motivo := 'PARAMETRO_INVALIDO'; END IF;
    IF v_motivo IS NULL AND NOT EXISTS (SELECT 1 FROM ops.ops_operator_permissions p WHERE p.operator_id = p_operator AND p.act = v_act
                                        AND p.revoked_at IS NULL) THEN
        v_motivo := 'NAO_HABILITADO';
    END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar_cadastro('HABILITACAO_RETIRADA', 'RECUSA', v_motivo, 1::smallint, v_op,
                                       CASE WHEN EXISTS (SELECT 1 FROM ops.ops_operators o WHERE o.operator_id = p_operator) THEN p_operator END,
                                       v_act, NULL, NULL, NULL, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo;
        RETURN;
    END IF;
    UPDATE ops.ops_operator_permissions SET revoked_at = v_agora, revoked_by = v_op
     WHERE operator_id = p_operator AND act = v_act AND revoked_at IS NULL;
    FOR v_cert IN UPDATE ops.ops_operator_certificates SET revoked_at = v_agora, revoke_reason = 'HABILITACAO_RETIRADA'
                  WHERE operator_id = p_operator AND revoked_at IS NULL RETURNING certificate_id LOOP
        PERFORM ops.registrar_cadastro('CERTIFICADO_REVOGADO', 'OK', NULL, 1::smallint, v_op, p_operator, NULL, v_cert);
    END LOOP;
    PERFORM ops.registrar_cadastro('HABILITACAO_RETIRADA', 'OK', NULL, 1::smallint, v_op, p_operator, v_act, NULL, NULL, NULL,
                                   v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text;
END;
$$;

CREATE FUNCTION public.ops_operator_disable(p_operator UUID)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_agora TIMESTAMPTZ;
    v_aut RECORD;
    v_motivo TEXT;
    v_cert UUID;
BEGIN
    PERFORM ops.exigir_read_committed();
    PERFORM ops.travar_operadores(ARRAY[v_op], ARRAY[p_operator]);
    v_agora := clock_timestamp();
    SELECT * INTO v_aut FROM ops.autorizar(v_op, 6::smallint, v_agora);
    v_motivo := v_aut.o_motivo;
    IF v_motivo IS NULL AND p_operator = v_op THEN v_motivo := 'AUTO_DESATIVACAO'; END IF;
    IF v_motivo IS NULL AND NOT EXISTS (SELECT 1 FROM ops.ops_operators o WHERE o.operator_id = p_operator AND o.status = 1) THEN
        v_motivo := 'OPERADOR_INATIVO';
    END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar_cadastro('OPERADOR_DESATIVADO', 'RECUSA', v_motivo, 1::smallint, v_op,
                                       CASE WHEN EXISTS (SELECT 1 FROM ops.ops_operators o WHERE o.operator_id = p_operator) THEN p_operator END,
                                       NULL, NULL, NULL, NULL, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo;
        RETURN;
    END IF;
    UPDATE ops.ops_operators SET status = 2, disabled_at = v_agora, disabled_by = v_op WHERE operator_id = p_operator;
    UPDATE ops.ops_operator_permissions SET revoked_at = v_agora, revoked_by = v_op WHERE operator_id = p_operator AND revoked_at IS NULL;
    FOR v_cert IN UPDATE ops.ops_operator_certificates SET revoked_at = v_agora, revoke_reason = 'OPERADOR_DESATIVADO'
                  WHERE operator_id = p_operator AND revoked_at IS NULL RETURNING certificate_id LOOP
        PERFORM ops.registrar_cadastro('CERTIFICADO_REVOGADO', 'OK', NULL, 1::smallint, v_op, p_operator, NULL, v_cert);
    END LOOP;
    PERFORM ops.registrar_cadastro('OPERADOR_DESATIVADO', 'OK', NULL, 1::smallint, v_op, p_operator, NULL, NULL, NULL, NULL,
                                   v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text;
END;
$$;

-- Vinculo do PROPRIO operador a uma conta da Platform que ele controla (codigo exibido na sessao autenticada da
-- conta; a CLI envia so o SHA-256). Lock: o proprio operador (FOR UPDATE) -> prova.
CREATE FUNCTION public.ops_link_platform_account(p_code_hash TEXT)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_agora TIMESTAMPTZ;
    v_aut RECORD;
    v_prova RECORD;
BEGIN
    PERFORM ops.exigir_read_committed();
    PERFORM ops.travar_operadores(NULL, ARRAY[v_op]);
    SELECT p.proof_id, p.organization_id, p.user_id, p.expires_at, p.consumed_at INTO v_prova
    FROM ops.ops_account_link_proofs p WHERE p.code_hash = p_code_hash FOR UPDATE;
    v_agora := clock_timestamp();
    SELECT * INTO v_aut FROM ops.autorizar(v_op, NULL, v_agora);
    IF v_aut.o_motivo IS NOT NULL OR v_prova.proof_id IS NULL OR v_prova.consumed_at IS NOT NULL OR v_agora >= v_prova.expires_at THEN
        PERFORM ops.registrar_cadastro('VINCULO_CONTA', 'RECUSA', coalesce(v_aut.o_motivo, 'PROVA_INVALIDA'), 1::smallint, v_op,
                                       NULL, NULL, NULL, NULL, NULL, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, coalesce(v_aut.o_motivo, 'PROVA_INVALIDA');
        RETURN;
    END IF;
    UPDATE ops.ops_account_link_proofs SET consumed_at = v_agora, consumed_by = v_op WHERE proof_id = v_prova.proof_id;
    INSERT INTO ops.ops_operator_platform_accounts (operator_id, proof_id, organization_id, user_id)
    VALUES (v_op, v_prova.proof_id, v_prova.organization_id, v_prova.user_id);
    PERFORM ops.registrar_cadastro('VINCULO_CONTA', 'OK', NULL, 1::smallint, v_op, v_op, NULL, NULL, v_prova.organization_id, NULL,
                                   v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text;
END;
$$;

-- Prova de vinculo operador <-> contato: emitida por um operador CADASTRAR; o codigo bruto e entregue pelo canal
-- registrado do contato e nunca chega ao banco. Lock: organizacao -> operador (FOR SHARE).
CREATE FUNCTION public.ops_contact_link_code_issue(p_contact UUID, p_code_hash TEXT)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT, r_valido_ate TIMESTAMPTZ)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_org UUID;
    v_agora TIMESTAMPTZ;
    v_aut RECORD;
    v_motivo TEXT;
BEGIN
    PERFORM ops.exigir_read_committed();
    SELECT c.organization_id INTO v_org FROM ops.org_authorized_contacts c WHERE c.contact_id = p_contact;
    PERFORM set_config('nekkora.fn_organization_id', coalesce(v_org::text, ''), true);
    PERFORM 1 FROM public.organizations WHERE organization_id = v_org FOR UPDATE;
    PERFORM 1 FROM ops.org_authorized_contacts c WHERE c.contact_id = p_contact FOR SHARE;
    PERFORM ops.travar_operadores(ARRAY[v_op]);
    v_agora := clock_timestamp();
    SELECT * INTO v_aut FROM ops.autorizar(v_op, 6::smallint, v_agora);
    v_motivo := v_aut.o_motivo;
    IF v_motivo IS NULL AND (v_org IS NULL OR NOT EXISTS (SELECT 1 FROM ops.org_authorized_contacts c WHERE c.contact_id = p_contact AND c.status = 1)) THEN
        v_motivo := 'CONTATO_INVALIDO';
    END IF;
    IF v_motivo IS NULL AND (p_code_hash IS NULL OR p_code_hash !~ '^[0-9a-f]{64}$') THEN v_motivo := 'PARAMETRO_INVALIDO'; END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar_cadastro('PROVA_CONTATO_EMITIDA', 'RECUSA', v_motivo, 1::smallint, v_op, NULL, NULL, NULL, v_org,
                                       CASE WHEN v_org IS NOT NULL THEN p_contact END, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, NULL::timestamptz;
        RETURN;
    END IF;
    INSERT INTO ops.ops_contact_link_proofs (organization_id, contact_id, code_hash, issued_by, created_at, expires_at)
    VALUES (v_org, p_contact, p_code_hash, v_op, v_agora, v_agora + interval '30 minutes');
    PERFORM ops.registrar_cadastro('PROVA_CONTATO_EMITIDA', 'OK', NULL, 1::smallint, v_op, NULL, NULL, NULL, v_org, p_contact,
                                   v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text, v_agora + interval '30 minutes';
END;
$$;

CREATE FUNCTION public.ops_link_contact(p_code_hash TEXT)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_agora TIMESTAMPTZ;
    v_aut RECORD;
    v_prova RECORD;
    v_motivo TEXT;
BEGIN
    PERFORM ops.exigir_read_committed();
    PERFORM ops.travar_operadores(NULL, ARRAY[v_op]);
    SELECT p.proof_id, p.organization_id, p.contact_id, p.issued_by, p.expires_at, p.consumed_at INTO v_prova
    FROM ops.ops_contact_link_proofs p WHERE p.code_hash = p_code_hash FOR UPDATE;
    v_agora := clock_timestamp();
    SELECT * INTO v_aut FROM ops.autorizar(v_op, NULL, v_agora);
    v_motivo := v_aut.o_motivo;
    IF v_motivo IS NULL AND (v_prova.proof_id IS NULL OR v_prova.consumed_at IS NOT NULL OR v_agora >= v_prova.expires_at
                             OR v_prova.issued_by = v_op) THEN
        v_motivo := 'PROVA_INVALIDA';
    END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar_cadastro('VINCULO_CONTATO', 'RECUSA', v_motivo, 1::smallint, v_op, NULL, NULL, NULL, NULL, NULL,
                                       v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo;
        RETURN;
    END IF;
    UPDATE ops.ops_contact_link_proofs SET consumed_at = v_agora, consumed_by = v_op WHERE proof_id = v_prova.proof_id;
    INSERT INTO ops.ops_operator_contact_links (operator_id, proof_id, organization_id, contact_id)
    VALUES (v_op, v_prova.proof_id, v_prova.organization_id, v_prova.contact_id);
    PERFORM ops.registrar_cadastro('VINCULO_CONTATO', 'OK', NULL, 1::smallint, v_op, v_op, NULL, NULL, v_prova.organization_id,
                                   v_prova.contact_id, v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text;
END;
$$;

-- Remocao de vinculo impeditivo: CADASTRAR, por outro operador. p_kind: CONTA | CONTATO.
CREATE FUNCTION public.ops_link_remove(p_kind TEXT, p_link UUID)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_dono UUID;
    v_agora TIMESTAMPTZ;
    v_aut RECORD;
    v_motivo TEXT;
BEGIN
    PERFORM ops.exigir_read_committed();
    IF p_kind = 'CONTA' THEN
        SELECT l.operator_id INTO v_dono FROM ops.ops_operator_platform_accounts l WHERE l.link_id = p_link;
    ELSIF p_kind = 'CONTATO' THEN
        SELECT l.operator_id INTO v_dono FROM ops.ops_operator_contact_links l WHERE l.link_id = p_link;
    END IF;
    PERFORM ops.travar_operadores(ARRAY[v_op], ARRAY[v_dono]);
    v_agora := clock_timestamp();
    SELECT * INTO v_aut FROM ops.autorizar(v_op, 6::smallint, v_agora);
    v_motivo := v_aut.o_motivo;
    IF v_motivo IS NULL AND v_dono IS NULL THEN v_motivo := 'VINCULO_INEXISTENTE'; END IF;
    IF v_motivo IS NULL AND v_dono = v_op THEN v_motivo := 'AUTO_REMOCAO'; END IF;
    IF v_motivo IS NULL THEN
        IF p_kind = 'CONTA' THEN
            UPDATE ops.ops_operator_platform_accounts SET removed_at = v_agora, removed_by = v_op WHERE link_id = p_link AND removed_at IS NULL;
        ELSE
            UPDATE ops.ops_operator_contact_links SET removed_at = v_agora, removed_by = v_op WHERE link_id = p_link AND removed_at IS NULL;
        END IF;
        IF NOT FOUND THEN v_motivo := 'VINCULO_INEXISTENTE'; END IF;
    END IF;
    PERFORM ops.registrar_cadastro('VINCULO_REMOVIDO', CASE WHEN v_motivo IS NULL THEN 'OK' ELSE 'RECUSA' END, v_motivo, 1::smallint,
                                   v_op, v_dono, NULL, NULL, NULL, NULL, v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT CASE WHEN v_motivo IS NULL THEN 'OK' ELSE 'RECUSA' END, v_motivo;
END;
$$;

-- === 15. Contatos autorizados (D2; chamador: nekkora_ops_mfa; ato CADASTRAR). Lock: organizacao -> contatos -> operador.

CREATE FUNCTION public.ops_contact_add(p_org UUID, p_display_name TEXT, p_channel_type SMALLINT, p_channel_value TEXT,
                                       p_user UUID, p_origin TEXT, p_signatory_confirmed BOOLEAN)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT, r_contato UUID)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_org_ok BOOLEAN;
    v_agora TIMESTAMPTZ;
    v_aut RECORD;
    v_motivo TEXT;
    v_id UUID;
BEGIN
    PERFORM ops.exigir_read_committed();
    PERFORM set_config('nekkora.fn_organization_id', coalesce(p_org::text, ''), true);
    SELECT true INTO v_org_ok FROM public.organizations WHERE organization_id = p_org FOR UPDATE;
    PERFORM ops.travar_operadores(ARRAY[v_op]);
    v_agora := clock_timestamp();
    SELECT * INTO v_aut FROM ops.autorizar(v_op, 6::smallint, v_agora);
    v_motivo := v_aut.o_motivo;
    IF v_motivo IS NULL AND v_org_ok IS NULL THEN v_motivo := 'ORGANIZACAO_INVALIDA'; END IF;
    IF v_motivo IS NULL AND (p_origin IS NULL OR p_origin NOT IN ('CONTRATACAO', 'SIGNATARIO') OR coalesce(p_channel_type, 0) NOT IN (1, 2)
                             OR coalesce(btrim(p_channel_value), '') = '' OR coalesce(btrim(p_display_name), '') = '') THEN
        v_motivo := 'PARAMETRO_INVALIDO';
    END IF;
    IF v_motivo IS NULL AND p_signatory_confirmed IS NOT TRUE THEN v_motivo := 'CONFIRMACAO_AUSENTE'; END IF;
    IF v_motivo IS NULL AND p_user IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM public.users u WHERE u.organization_id = p_org AND u.user_id = p_user) THEN
        v_motivo := 'CONTA_INVALIDA';
    END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar_cadastro('CONTATO_INCLUIDO', 'RECUSA', v_motivo, 1::smallint, v_op, NULL, NULL, NULL,
                                       CASE WHEN v_org_ok THEN p_org END, NULL, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, NULL::uuid;
        RETURN;
    END IF;
    INSERT INTO ops.org_authorized_contacts (organization_id, display_name, channel_type, channel_value, user_id, created_at, channel_set_at)
    VALUES (p_org, p_display_name, p_channel_type, p_channel_value, p_user, v_agora, v_agora) RETURNING contact_id INTO v_id;
    INSERT INTO ops.org_authorized_contact_events (organization_id, contact_id, kind, operator_id, origin, signatory_confirmed)
    VALUES (p_org, v_id, 1, v_op, p_origin, true);
    PERFORM ops.registrar_cadastro('CONTATO_INCLUIDO', 'OK', NULL, 1::smallint, v_op, NULL, NULL, NULL, p_org, v_id,
                                   v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text, v_id;
END;
$$;

-- Alteracao de canal ou remocao (D2d): confirmada pelo canal ANTERIOR e por OUTRO contato elegivel.
CREATE FUNCTION ops.contato_alterar(p_contact UUID, p_remover BOOLEAN, p_channel_type SMALLINT, p_channel_value TEXT,
                                    p_previous_confirmed BOOLEAN, p_other_contact UUID, p_other_confirmed BOOLEAN)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT)
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_org UUID;
    v_agora TIMESTAMPTZ;
    v_aut RECORD;
    v_motivo TEXT;
    v_kind TEXT := CASE WHEN p_remover THEN 'CONTATO_REMOVIDO' ELSE 'CONTATO_CANAL_ALTERADO' END;
BEGIN
    SELECT c.organization_id INTO v_org FROM ops.org_authorized_contacts c WHERE c.contact_id = p_contact;
    PERFORM set_config('nekkora.fn_organization_id', coalesce(v_org::text, ''), true);
    PERFORM 1 FROM public.organizations WHERE organization_id = v_org FOR UPDATE;
    PERFORM 1 FROM ops.org_authorized_contacts c WHERE c.organization_id = v_org AND c.contact_id IN (p_contact, p_other_contact)
        ORDER BY c.contact_id FOR UPDATE;
    PERFORM ops.travar_operadores(ARRAY[v_op]);
    v_agora := clock_timestamp();
    SELECT * INTO v_aut FROM ops.autorizar(v_op, 6::smallint, v_agora);
    v_motivo := v_aut.o_motivo;
    IF v_motivo IS NULL AND (v_org IS NULL OR NOT EXISTS (SELECT 1 FROM ops.org_authorized_contacts c WHERE c.contact_id = p_contact AND c.status = 1)) THEN
        v_motivo := 'CONTATO_INVALIDO';
    END IF;
    IF v_motivo IS NULL AND NOT p_remover AND (coalesce(p_channel_type, 0) NOT IN (1, 2) OR coalesce(btrim(p_channel_value), '') = '') THEN
        v_motivo := 'PARAMETRO_INVALIDO';
    END IF;
    IF v_motivo IS NULL AND (p_previous_confirmed IS NOT TRUE OR p_other_confirmed IS NOT TRUE) THEN v_motivo := 'CONFIRMACAO_AUSENTE'; END IF;
    IF v_motivo IS NULL AND (p_other_contact IS NULL OR p_other_contact = p_contact
                             OR ops.contato_inelegivel(v_org, p_other_contact, v_agora) IS NOT NULL) THEN
        v_motivo := 'OUTRO_CONTATO_INELEGIVEL';
    END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar_cadastro(v_kind, 'RECUSA', v_motivo, 1::smallint, v_op, NULL, NULL, NULL, v_org,
                                       CASE WHEN v_org IS NOT NULL THEN p_contact END, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo;
        RETURN;
    END IF;
    IF p_remover THEN
        UPDATE ops.org_authorized_contacts SET status = 2, removed_at = v_agora WHERE contact_id = p_contact;
    ELSE
        UPDATE ops.org_authorized_contacts SET channel_type = p_channel_type, channel_value = p_channel_value, channel_set_at = v_agora
         WHERE contact_id = p_contact;
    END IF;
    INSERT INTO ops.org_authorized_contact_events (organization_id, contact_id, kind, operator_id, previous_channel_confirmed,
                                                   other_contact_id, other_contact_confirmed)
    VALUES (v_org, p_contact, CASE WHEN p_remover THEN 3 ELSE 2 END, v_op, true, p_other_contact, true);
    PERFORM ops.registrar_cadastro(v_kind, 'OK', NULL, 1::smallint, v_op, NULL, NULL, NULL, v_org, p_contact, v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text;
END;
$$;

CREATE FUNCTION public.ops_contact_change_channel(p_contact UUID, p_channel_type SMALLINT, p_channel_value TEXT,
                                                  p_previous_confirmed BOOLEAN, p_other_contact UUID, p_other_confirmed BOOLEAN)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT)
LANGUAGE sql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
    SELECT ops.exigir_read_committed();
    SELECT * FROM ops.contato_alterar(p_contact, false, p_channel_type, p_channel_value, p_previous_confirmed, p_other_contact, p_other_confirmed)
$$;

CREATE FUNCTION public.ops_contact_remove(p_contact UUID, p_previous_confirmed BOOLEAN, p_other_contact UUID, p_other_confirmed BOOLEAN)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT)
LANGUAGE sql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
    SELECT ops.exigir_read_committed();
    SELECT * FROM ops.contato_alterar(p_contact, true, NULL, NULL, p_previous_confirmed, p_other_contact, p_other_confirmed)
$$;

-- === 16. CA operacional (chamador: nekkora_ops_ca) ===

CREATE FUNCTION public.ops_ca_resolve_operator(p_idp_issuer TEXT, p_idp_subject TEXT) RETURNS TEXT
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT ops.exigir_read_committed();
    SELECT o.db_role FROM ops.ops_operators o WHERE o.idp_issuer = p_idp_issuer AND o.idp_subject = p_idp_subject AND o.status = 1
$$;

-- Registro de emissao ANTES da assinatura. A CA nao assina se o registro for recusado. Lock: operador FOR UPDATE.
CREATE FUNCTION public.ops_register_certificate(p_idp_issuer TEXT, p_idp_subject TEXT, p_db_role TEXT, p_issuer_dn TEXT,
                                                p_serial NUMERIC, p_not_before TIMESTAMPTZ, p_not_after TIMESTAMPTZ,
                                                p_idp_auth_time TIMESTAMPTZ, p_idp_method TEXT)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT, r_certificado UUID)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_op UUID;
    v_agora TIMESTAMPTZ;
    v_motivo TEXT;
    v_id UUID;
BEGIN
    PERFORM ops.exigir_read_committed();
    SELECT o.operator_id INTO v_op FROM ops.ops_operators o WHERE o.idp_issuer = p_idp_issuer AND o.idp_subject = p_idp_subject;
    PERFORM ops.travar_operadores(NULL, ARRAY[v_op]);
    v_agora := clock_timestamp();
    IF v_op IS NULL OR NOT EXISTS (SELECT 1 FROM ops.ops_operators o WHERE o.operator_id = v_op AND o.status = 1) THEN
        v_motivo := 'OPERADOR_INATIVO';
    ELSIF (SELECT o.db_role FROM ops.ops_operators o WHERE o.operator_id = v_op) IS DISTINCT FROM p_db_role THEN
        v_motivo := 'SUJEITO_DIVERGENTE';
    ELSIF p_issuer_dn IS NULL OR btrim(p_issuer_dn) = '' OR p_serial IS NULL OR p_serial <= 0 THEN
        v_motivo := 'PARAMETRO_INVALIDO';
    ELSIF p_idp_method IS NULL OR p_idp_method NOT IN ('phr+mfa', 'phrh+mfa') THEN
        v_motivo := 'METODO_NAO_RESISTENTE';
    ELSIF p_idp_auth_time IS NULL OR p_idp_auth_time < v_agora - interval '5 minutes' OR p_idp_auth_time > v_agora + interval '1 minute' THEN
        v_motivo := 'MFA_NAO_RECENTE';
    ELSIF p_not_before IS NULL OR p_not_after IS NULL OR p_not_before < v_agora - interval '2 minutes' OR p_not_before > v_agora + interval '1 minute'
          OR p_not_after <= p_not_before OR p_not_after - p_not_before > interval '15 minutes' THEN
        v_motivo := 'VALIDADE_INVALIDA';
    ELSIF EXISTS (SELECT 1 FROM ops.ops_operator_certificates c WHERE c.issuer_dn = p_issuer_dn AND c.serial = p_serial) THEN
        v_motivo := 'SERIAL_REPETIDO';
    END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar_cadastro('CERTIFICADO_REGISTRADO', 'RECUSA', v_motivo, 2::smallint, NULL, v_op);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, NULL::uuid;
        RETURN;
    END IF;
    INSERT INTO ops.ops_operator_certificates (operator_id, issuer_dn, serial, not_before, not_after, idp_auth_time, idp_method, registered_at)
    VALUES (v_op, p_issuer_dn, p_serial, p_not_before, p_not_after, p_idp_auth_time, p_idp_method, v_agora)
    RETURNING certificate_id INTO v_id;
    PERFORM ops.registrar_cadastro('CERTIFICADO_REGISTRADO', 'OK', NULL, 2::smallint, NULL, v_op, NULL, v_id, NULL, NULL, p_issuer_dn, p_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text, v_id;
END;
$$;

CREATE FUNCTION public.ops_revoke_certificate(p_issuer_dn TEXT, p_serial NUMERIC)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_op UUID;
    v_id UUID;
BEGIN
    PERFORM ops.exigir_read_committed();
    SELECT c.operator_id INTO v_op FROM ops.ops_operator_certificates c WHERE c.issuer_dn = p_issuer_dn AND c.serial = p_serial;
    PERFORM ops.travar_operadores(NULL, ARRAY[v_op]);
    UPDATE ops.ops_operator_certificates SET revoked_at = clock_timestamp(), revoke_reason = 'CA'
     WHERE issuer_dn = p_issuer_dn AND serial = p_serial AND revoked_at IS NULL RETURNING certificate_id INTO v_id;
    IF v_id IS NULL THEN
        PERFORM ops.registrar_cadastro('CERTIFICADO_REVOGADO', 'RECUSA', 'CERTIFICADO_INEXISTENTE', 2::smallint, NULL, v_op);
        RETURN QUERY SELECT 'RECUSA'::text, 'CERTIFICADO_INEXISTENTE'::text;
        RETURN;
    END IF;
    PERFORM ops.registrar_cadastro('CERTIFICADO_REVOGADO', 'OK', NULL, 2::smallint, NULL, v_op, NULL, v_id, NULL, NULL, p_issuer_dn, p_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text;
END;
$$;

-- === 17. Avisos (chamador: nekkora_platform; identidade de servico que emite avisos e consome tokens) ===
--
-- Tokens brutos so na memoria da Platform: o banco recebe o SHA-256 (hex). Operadores nao tem EXECUTE aqui e nao
-- conseguem registrar confirmacao nem contestacao.

-- Prova de controle de conta para vinculo operacional: a Platform chama na sessao autenticada do proprio titular,
-- com o contexto de requisicao da organizacao.
CREATE FUNCTION public.ops_account_proof_issue(p_org UUID, p_user UUID, p_code_hash TEXT) RETURNS TIMESTAMPTZ
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
DECLARE
    v_agora TIMESTAMPTZ := clock_timestamp();
BEGIN
    PERFORM ops.exigir_read_committed();
    IF p_org IS NULL OR NULLIF(current_setting('nekkora.organization_id', true), '')::uuid IS DISTINCT FROM p_org THEN
        RAISE EXCEPTION 'contexto organizacional ausente ou divergente' USING ERRCODE = '42501';
    END IF;
    PERFORM set_config('nekkora.fn_organization_id', p_org::text, true);
    IF NOT EXISTS (SELECT 1 FROM public.users u WHERE u.organization_id = p_org AND u.user_id = p_user AND u.status = 2) THEN
        RAISE EXCEPTION 'conta inexistente ou inativa' USING ERRCODE = '42501';
    END IF;
    INSERT INTO ops.ops_account_link_proofs (organization_id, user_id, code_hash, created_at, expires_at)
    VALUES (p_org, p_user, p_code_hash, v_agora, v_agora + interval '10 minutes');
    PERFORM ops.registrar_cadastro('PROVA_CONTA_EMITIDA', 'OK', NULL, 3::smallint, NULL, NULL, NULL, NULL, p_org);
    RETURN v_agora + interval '10 minutes';
END;
$$;

CREATE FUNCTION public.ops_notice_pending(p_limit INTEGER)
RETURNS TABLE (r_aviso UUID, r_organizacao UUID, r_destinatario UUID, r_tipo SMALLINT)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT ops.exigir_read_committed();
    SELECT n.notice_id, n.organization_id, n.recipient_user_id, n.kind FROM ops.ops_mfa_notices n
    WHERE n.issued_at IS NULL ORDER BY n.created_at, n.notice_id LIMIT least(greatest(coalesce(p_limit, 0), 0), 100)
$$;

-- Emissao: gera os tokens cabiveis (CONFIRMAR so para o titular em PRE_APROVADA ainda nao confirmada; CONTESTAR
-- para os notificados enquanto nao terminal) e invalida os anteriores do mesmo destinatario e finalidade.
-- Retorno: emitido (enviar o e-mail) e quais links incluir. Lock: organizacao -> conta alvo -> solicitacao -> aviso.
CREATE FUNCTION public.ops_notice_issue(p_notice UUID, p_confirm_hash TEXT, p_contest_hash TEXT)
RETURNS TABLE (r_emitido BOOLEAN, r_confirmar BOOLEAN, r_contestar BOOLEAN, r_tipo SMALLINT, r_organizacao UUID,
               r_destinatario UUID, r_confirmar_ate TIMESTAMPTZ, r_contestar_ate TIMESTAMPTZ)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
DECLARE
    n RECORD;
    r ops.ops_mfa_recovery_requests%ROWTYPE;
    v_agora TIMESTAMPTZ;
    v_confirmar BOOLEAN := false;
    v_contestar BOOLEAN := false;
    v_conf_ate TIMESTAMPTZ;
BEGIN
    PERFORM ops.exigir_read_committed();
    IF p_confirm_hash !~ '^[0-9a-f]{64}$' OR p_contest_hash !~ '^[0-9a-f]{64}$' OR p_confirm_hash = p_contest_hash THEN
        RAISE EXCEPTION 'hash de token invalido' USING ERRCODE = '22023';
    END IF;
    SELECT q.notice_id, q.request_id, q.organization_id, q.target_user_id, q.recipient_user_id, q.kind INTO n
    FROM ops.ops_mfa_notices q WHERE q.notice_id = p_notice;
    IF NOT FOUND THEN
        RETURN QUERY SELECT false, false, false, NULL::smallint, NULL::uuid, NULL::uuid, NULL::timestamptz, NULL::timestamptz;
        RETURN;
    END IF;
    PERFORM set_config('nekkora.fn_organization_id', n.organization_id::text, true);
    PERFORM 1 FROM public.organizations WHERE organization_id = n.organization_id FOR UPDATE;
    PERFORM 1 FROM public.users WHERE organization_id = n.organization_id AND user_id = n.target_user_id FOR UPDATE;
    SELECT * INTO r FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = n.request_id FOR UPDATE;
    PERFORM 1 FROM ops.ops_mfa_notices q WHERE q.notice_id = p_notice AND q.issued_at IS NULL FOR UPDATE;
    IF NOT FOUND THEN
        RETURN QUERY SELECT false, false, false, n.kind, n.organization_id, n.recipient_user_id, NULL::timestamptz, NULL::timestamptz;
        RETURN;
    END IF;
    v_agora := clock_timestamp();
    IF n.kind IN (1, 2) AND (r.state NOT IN (1, 2, 3, 4) OR v_agora >= r.t_total) THEN
        UPDATE ops.ops_mfa_notices SET issued_at = v_agora, discarded = true WHERE notice_id = p_notice;
        RETURN QUERY SELECT false, false, false, n.kind, n.organization_id, n.recipient_user_id, NULL::timestamptz, NULL::timestamptz;
        RETURN;
    END IF;
    IF n.kind = 1 AND r.state = 3 AND r.notice_confirmed_at IS NULL THEN
        v_confirmar := true;
        v_conf_ate := least(r.t_total, v_agora + interval '24 hours');
        UPDATE ops.ops_mfa_notice_tokens SET invalidated_at = v_agora
         WHERE request_id = r.request_id AND recipient_user_id = n.recipient_user_id AND purpose = 1 AND used_at IS NULL AND invalidated_at IS NULL;
        INSERT INTO ops.ops_mfa_notice_tokens (notice_id, request_id, organization_id, target_user_id, recipient_user_id, purpose,
                                               token_hash, issued_at, valid_until)
        VALUES (n.notice_id, n.request_id, n.organization_id, n.target_user_id, n.recipient_user_id, 1, p_confirm_hash, v_agora, v_conf_ate);
    END IF;
    IF n.kind IN (1, 2) THEN
        v_contestar := true;
        UPDATE ops.ops_mfa_notice_tokens SET invalidated_at = v_agora
         WHERE request_id = r.request_id AND recipient_user_id = n.recipient_user_id AND purpose = 2 AND used_at IS NULL AND invalidated_at IS NULL;
        INSERT INTO ops.ops_mfa_notice_tokens (notice_id, request_id, organization_id, target_user_id, recipient_user_id, purpose,
                                               token_hash, issued_at, valid_until)
        VALUES (n.notice_id, n.request_id, n.organization_id, n.target_user_id, n.recipient_user_id, 2, p_contest_hash, v_agora, r.t_total);
    END IF;
    UPDATE ops.ops_mfa_notices SET issued_at = v_agora WHERE notice_id = p_notice;
    PERFORM ops.registrar(r.request_id, r.organization_id, r.target_user_id, 'AVISO_EMITIDO', 'OK', NULL, NULL, NULL, NULL, n.recipient_user_id);
    RETURN QUERY SELECT true, v_confirmar, v_contestar, n.kind, n.organization_id, n.recipient_user_id, v_conf_ate,
                        CASE WHEN v_contestar THEN r.t_total END;
END;
$$;

-- Uso de token (confirmacao ou contestacao). Resposta so booleana: a Platform devolve a mesma resposta publica em
-- toda recusa. p_org vem da rota e deve bater com o contexto de requisicao e com o token.
CREATE FUNCTION ops.usar_token(p_org UUID, p_token_hash TEXT, p_purpose SMALLINT) RETURNS BOOLEAN
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    t RECORD;
    r ops.ops_mfa_recovery_requests%ROWTYPE;
    v_agora TIMESTAMPTZ;
    v_motivo TEXT;
    v_ato TEXT := CASE p_purpose WHEN 1 THEN 'AVISO_CONFIRMADO' ELSE 'AVISO_CONTESTADO' END;
BEGIN
    IF p_org IS NULL OR NULLIF(current_setting('nekkora.organization_id', true), '')::uuid IS DISTINCT FROM p_org THEN
        RAISE EXCEPTION 'contexto organizacional ausente ou divergente' USING ERRCODE = '42501';
    END IF;
    IF p_token_hash IS NULL OR p_token_hash !~ '^[0-9a-f]{64}$' THEN
        RETURN false;
    END IF;
    SELECT q.token_id, q.request_id, q.organization_id, q.target_user_id, q.recipient_user_id, q.purpose INTO t
    FROM ops.ops_mfa_notice_tokens q WHERE q.token_hash = p_token_hash;
    IF NOT FOUND THEN
        RETURN false;  -- token desconhecido: nada a atribuir (a Platform registra a categoria)
    END IF;
    IF t.organization_id <> p_org THEN
        RETURN false;  -- token de outra organizacao: mesma resposta, sem tocar a outra organizacao
    END IF;
    PERFORM set_config('nekkora.fn_organization_id', t.organization_id::text, true);
    PERFORM 1 FROM public.organizations WHERE organization_id = t.organization_id FOR UPDATE;
    PERFORM 1 FROM public.users WHERE organization_id = t.organization_id AND user_id = t.target_user_id FOR UPDATE;
    SELECT * INTO r FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = t.request_id FOR UPDATE;
    SELECT q.token_id, q.request_id, q.organization_id, q.target_user_id, q.recipient_user_id, q.purpose, q.valid_until, q.used_at,
           q.invalidated_at INTO t
    FROM ops.ops_mfa_notice_tokens q WHERE q.token_id = t.token_id FOR UPDATE;
    v_agora := clock_timestamp();
    IF t.purpose <> p_purpose THEN
        v_motivo := 'FINALIDADE_DIVERGENTE';
    ELSIF t.used_at IS NOT NULL THEN
        v_motivo := 'TOKEN_USADO';
    ELSIF t.invalidated_at IS NOT NULL THEN
        v_motivo := 'TOKEN_INVALIDADO';
    ELSIF v_agora >= t.valid_until OR v_agora >= r.t_total THEN
        v_motivo := 'TOKEN_VENCIDO';
    ELSIF r.state NOT IN (1, 2, 3, 4) THEN
        v_motivo := 'SOLICITACAO_ENCERRADA';
    ELSIF p_purpose = 1 AND (r.state <> 3 OR r.notice_confirmed_at IS NOT NULL OR t.recipient_user_id <> r.target_user_id) THEN
        v_motivo := 'CONFIRMACAO_INDEVIDA';
    END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar(r.request_id, r.organization_id, r.target_user_id, 'AVISO_INVALIDO', 'RECUSA', v_motivo, NULL, NULL, NULL,
                              t.recipient_user_id, t.purpose);
        RETURN false;
    END IF;
    UPDATE ops.ops_mfa_notice_tokens SET used_at = v_agora WHERE token_id = t.token_id;
    IF p_purpose = 1 THEN
        UPDATE ops.ops_mfa_recovery_requests SET notice_confirmed_at = v_agora WHERE request_id = r.request_id AND notice_confirmed_at IS NULL;
    ELSE
        PERFORM ops.encerrar(r.request_id, 7::smallint, 'CONTESTADA', NULL, v_agora);
    END IF;
    PERFORM ops.registrar(r.request_id, r.organization_id, r.target_user_id, v_ato, 'OK', NULL, NULL, NULL, NULL, t.recipient_user_id, t.purpose);
    RETURN true;
END;
$$;

CREATE FUNCTION public.ops_notice_confirm(p_org UUID, p_token_hash TEXT) RETURNS BOOLEAN
LANGUAGE sql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
    SELECT ops.exigir_read_committed();
    SELECT ops.usar_token(p_org, p_token_hash, 1::smallint) $$;

CREATE FUNCTION public.ops_notice_contest(p_org UUID, p_token_hash TEXT) RETURNS BOOLEAN
LANGUAGE sql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
    SELECT ops.exigir_read_committed();
    SELECT ops.usar_token(p_org, p_token_hash, 2::smallint) $$;

-- === 18. Donos, EXECUTE e matriz ===

DO $$
DECLARE
    f REGPROCEDURE;
BEGIN
    FOR f IN SELECT p.oid::regprocedure FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
             WHERE (n.nspname = 'ops' AND p.proname NOT LIKE 'ssl%' AND p.proname NOT LIKE 'prevent\_%')
                OR (n.nspname = 'public' AND p.proname LIKE 'ops\_%') LOOP
        EXECUTE format('ALTER FUNCTION %s OWNER TO nekkora_ops_mfa_owner', f);
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', f);
    END LOOP;
    FOR f IN SELECT p.oid::regprocedure FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
             WHERE n.nspname = 'ops' AND p.proname LIKE 'prevent\_%' LOOP
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', f);
    END LOOP;
END;
$$;

GRANT EXECUTE ON FUNCTION public.ops_mfa_open(UUID, UUID, UUID), public.ops_mfa_verify(UUID, BOOLEAN, UUID, BOOLEAN),
    public.ops_mfa_preapprove(UUID), public.ops_mfa_final_approve(UUID), public.ops_mfa_execute(UUID),
    public.ops_mfa_cancel(UUID, TEXT), public.ops_mfa_resend_notice(UUID), public.ops_mfa_status(UUID),
    public.ops_mfa_record_failure(UUID, TEXT, TEXT, TEXT), public.ops_operator_create(TEXT, TEXT, TEXT),
    public.ops_operator_grant(UUID, TEXT), public.ops_operator_revoke(UUID, TEXT), public.ops_operator_disable(UUID),
    public.ops_link_platform_account(TEXT), public.ops_contact_link_code_issue(UUID, TEXT), public.ops_link_contact(TEXT),
    public.ops_link_remove(TEXT, UUID), public.ops_contact_add(UUID, TEXT, SMALLINT, TEXT, UUID, TEXT, BOOLEAN),
    public.ops_contact_change_channel(UUID, SMALLINT, TEXT, BOOLEAN, UUID, BOOLEAN),
    public.ops_contact_remove(UUID, BOOLEAN, UUID, BOOLEAN)
    TO nekkora_ops_mfa;
GRANT EXECUTE ON FUNCTION public.ops_ca_resolve_operator(TEXT, TEXT),
    public.ops_register_certificate(TEXT, TEXT, TEXT, TEXT, NUMERIC, TIMESTAMPTZ, TIMESTAMPTZ, TIMESTAMPTZ, TEXT),
    public.ops_revoke_certificate(TEXT, NUMERIC)
    TO nekkora_ops_ca;
GRANT EXECUTE ON FUNCTION public.ops_account_proof_issue(UUID, UUID, TEXT), public.ops_notice_pending(INTEGER),
    public.ops_notice_issue(UUID, TEXT, TEXT), public.ops_notice_confirm(UUID, TEXT), public.ops_notice_contest(UUID, TEXT)
    TO nekkora_platform;

COMMENT ON SCHEMA ops IS 'recuperacao emergencial de MFA (016): so nekkora_ops_mfa_owner tem USAGE; acesso so pelas funcoes public.ops_*';

-- === 19. Invariantes verificados ao final ===

DO $$
DECLARE
    v_bad TEXT;
    v_ops_roles CONSTANT TEXT[] := ARRAY['nekkora_ops_mfa_owner', 'nekkora_ops_mfa', 'nekkora_ops_ca'];
    v_servico CONSTANT TEXT[] := ARRAY['nekkora_platform', 'nekkora_ingestion', 'nekkora_scheduler', 'nekkora_provisioning',
        'nekkora_retention_executor', 'nekkora_portal', 'nekkora_ops_ca', 'nekkora_ops_mfa'];
    v_esperado CONSTANT TEXT[] := ARRAY[
        'ops_account_proof_issue=nekkora_platform', 'ops_bootstrap_operator=', 'ops_ca_resolve_operator=nekkora_ops_ca',
        'ops_contact_add=nekkora_ops_mfa', 'ops_contact_change_channel=nekkora_ops_mfa', 'ops_contact_link_code_issue=nekkora_ops_mfa',
        'ops_contact_remove=nekkora_ops_mfa', 'ops_link_contact=nekkora_ops_mfa', 'ops_link_platform_account=nekkora_ops_mfa',
        'ops_link_remove=nekkora_ops_mfa', 'ops_mfa_cancel=nekkora_ops_mfa', 'ops_mfa_execute=nekkora_ops_mfa',
        'ops_mfa_final_approve=nekkora_ops_mfa', 'ops_mfa_open=nekkora_ops_mfa', 'ops_mfa_preapprove=nekkora_ops_mfa',
        'ops_mfa_record_failure=nekkora_ops_mfa', 'ops_mfa_resend_notice=nekkora_ops_mfa', 'ops_mfa_status=nekkora_ops_mfa',
        'ops_mfa_verify=nekkora_ops_mfa', 'ops_notice_confirm=nekkora_platform', 'ops_notice_contest=nekkora_platform',
        'ops_notice_issue=nekkora_platform', 'ops_notice_pending=nekkora_platform', 'ops_operator_create=nekkora_ops_mfa',
        'ops_operator_disable=nekkora_ops_mfa', 'ops_operator_grant=nekkora_ops_mfa', 'ops_operator_revoke=nekkora_ops_mfa',
        'ops_register_certificate=nekkora_ops_ca', 'ops_revoke_certificate=nekkora_ops_ca'];
    v_real TEXT[];
BEGIN
    -- atributos e memberships dos papeis novos
    SELECT string_agg(rolname, ', ') INTO v_bad FROM pg_roles
    WHERE rolname = ANY (v_ops_roles) AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb OR rolreplication
        OR (rolname IN ('nekkora_ops_mfa_owner', 'nekkora_ops_mfa') AND rolcanlogin) OR (rolname = 'nekkora_ops_ca' AND NOT rolcanlogin));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel operacional com atributo indevido: %', v_bad; END IF;
    SELECT string_agg(format('%s -> %s', r.rolname, m.rolname), ', ') INTO v_bad
    FROM pg_auth_members am JOIN pg_roles r ON r.oid = am.roleid JOIN pg_roles m ON m.oid = am.member
    WHERE (r.rolname = ANY (v_ops_roles) OR m.rolname = ANY (v_ops_roles))
      AND NOT (r.rolname = 'nekkora_ops_mfa' AND m.rolname ~ '^op_[a-z0-9_]{2,40}$' AND NOT am.admin_option);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'membership indevida envolvendo papel operacional: %', v_bad; END IF;
    SELECT string_agg(p, ', ') INTO v_bad FROM unnest(v_ops_roles) AS p
    WHERE has_schema_privilege(p, 'public', 'CREATE') OR has_database_privilege(p, current_database(), 'CREATE')
       OR has_schema_privilege(p, 'ops', 'CREATE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel operacional com CREATE efetivo: %', v_bad; END IF;

    -- schema ops: USAGE so do proprietario operacional; nenhum papel de servico toca tabela de ops
    SELECT string_agg(r.rolname, ', ') INTO v_bad FROM pg_roles r
    WHERE r.rolname LIKE 'nekkora\_%' AND r.rolname <> 'nekkora_ops_mfa_owner' AND NOT r.rolsuper
      AND has_schema_privilege(r.oid, 'ops', 'USAGE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'USAGE indevido no schema ops: %', v_bad; END IF;
    SELECT string_agg(format('%s:%s', p, c.relname), ', ') INTO v_bad
    FROM unnest(v_servico || ARRAY['nekkora_portal_owner', 'nekkora_scheduler_owner', 'nekkora_provisioning_owner',
                                   'nekkora_retention_owner', 'nekkora_retention_tables_owner']) AS p,
         pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'ops' AND c.relkind = 'r'
      AND (has_table_privilege(p, c.oid, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
           OR has_any_column_privilege(p, c.oid, 'SELECT, INSERT, UPDATE, REFERENCES'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'papel de servico com privilegio em tabela de ops: %', v_bad; END IF;
    SELECT string_agg(c.relname, ', ') INTO v_bad FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'ops' AND c.relkind = 'r' AND has_table_privilege('nekkora_ops_mfa_owner', c.oid, 'DELETE, TRUNCATE, REFERENCES, TRIGGER');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'proprietario operacional com DELETE/TRUNCATE em ops: %', v_bad; END IF;
    IF (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'ops' AND c.relkind = 'r') <> 14 THEN
        RAISE EXCEPTION 'inventario de tabelas de ops divergente';
    END IF;

    -- matriz de EXECUTE (publico = ops_*): exatamente os chamadores previstos (fora dono e superusuarios)
    SELECT array_agg(x ORDER BY x COLLATE "C") INTO v_real FROM (
        SELECT p.proname || '=' || coalesce((SELECT string_agg(r.rolname, ',' ORDER BY r.rolname COLLATE "C") FROM pg_roles r
                 WHERE NOT r.rolsuper AND r.rolname <> 'nekkora_ops_mfa_owner' AND r.rolname !~ '^op_'
                   AND has_function_privilege(r.oid, p.oid, 'EXECUTE')), '') AS x
        FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public' AND p.proname LIKE 'ops\_%') s;
    IF v_real IS DISTINCT FROM v_esperado THEN
        RAISE EXCEPTION 'matriz de EXECUTE divergente: %', v_real;
    END IF;
    -- funcoes internas: nenhum papel alem do dono (e superusuarios)
    SELECT string_agg(p.oid::regprocedure::text || ':' || r.rolname, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace, pg_roles r
    WHERE n.nspname = 'ops' AND NOT r.rolsuper AND r.rolname <> 'nekkora_ops_mfa_owner' AND has_function_privilege(r.oid, p.oid, 'EXECUTE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'EXECUTE indevido em funcao interna de ops: %', v_bad; END IF;
    -- publicas: SECURITY DEFINER, dono operacional, search_path restrito
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.proname LIKE 'ops\_%'
      AND (NOT p.prosecdef OR pg_get_userbyid(p.proowner) <> 'nekkora_ops_mfa_owner'
           OR NOT ('search_path=pg_catalog, pg_temp' = ANY (coalesce(p.proconfig, '{}'))));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'funcao operacional fora do padrao: %', v_bad; END IF;
    -- publicas: a guarda de isolamento e a PRIMEIRA instrucao (plpgsql: logo depois do BEGIN; sql: primeiro comando)
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace JOIN pg_language l ON l.oid = p.prolang
    WHERE n.nspname = 'public' AND p.proname LIKE 'ops\_%'
      AND NOT CASE l.lanname
                WHEN 'plpgsql' THEN substring(p.prosrc FROM '\mBEGIN\M.*') ~ '^BEGIN\s+PERFORM ops\.exigir_read_committed\(\);'
                WHEN 'sql' THEN p.prosrc ~ '^\s*SELECT ops\.exigir_read_committed\(\);'
                ELSE false END;
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'funcao operacional sem a guarda de isolamento como primeira instrucao: %', v_bad; END IF;

    -- RLS: politica do proprietario operacional nas 9 tabelas de tenant, sem alterar as existentes
    IF (SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND policyname = 'ops_function_context'
          AND roles = ARRAY['nekkora_ops_mfa_owner']::name[]) <> 9 THEN
        RAISE EXCEPTION 'politicas ops_function_context divergentes';
    END IF;
    SELECT string_agg(c.relname, ', ') INTO v_bad FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname IN ('organizations', 'users', 'organization_memberships',
          'organization_role_assignments', 'user_mfa_factors', 'user_mfa_recovery_codes', 'auth_challenges', 'user_sessions', 'mfa_events')
      AND NOT (c.relrowsecurity AND c.relforcerowsecurity);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'tabela sem RLS forcada: %', v_bad; END IF;
    -- o proprietario operacional nao altera tabelas de tenant alem das colunas do reset
    IF has_table_privilege('nekkora_ops_mfa_owner', 'public.users', 'INSERT, DELETE, TRUNCATE')
       OR has_column_privilege('nekkora_ops_mfa_owner', 'public.users', 'status', 'UPDATE')
       OR has_table_privilege('nekkora_ops_mfa_owner', 'public.user_credentials', 'SELECT, UPDATE')
       OR has_any_column_privilege('nekkora_ops_mfa_owner', 'public.user_credentials', 'SELECT, UPDATE')
       OR has_any_column_privilege('nekkora_ops_mfa_owner', 'public.organization_role_assignments', 'UPDATE, INSERT')
       OR has_any_column_privilege('nekkora_ops_mfa_owner', 'public.organization_memberships', 'UPDATE, INSERT')
       OR has_table_privilege('nekkora_ops_mfa_owner', 'public.mfa_events', 'UPDATE, DELETE, TRUNCATE') THEN
        RAISE EXCEPTION 'proprietario operacional com privilegio alem da matriz';
    END IF;

    -- mfa_events: vinculo composto, UNIQUE, CHECKs; Platform sem INSERT em ops_request_id
    IF (SELECT count(*) FROM pg_constraint WHERE conrelid = 'public.mfa_events'::regclass AND conname IN
          ('mfa_events_ops_request_id_key', 'mfa_events_ops_request_fk', 'mfa_events_ops_request_tipo_4', 'mfa_events_tipo_4_sem_ator',
           'mfa_events_reason_code_so_admin', 'mfa_events_reset_admin_exige_motivo')) <> 6 THEN
        RAISE EXCEPTION 'restricoes de mfa_events ausentes';
    END IF;
    IF (SELECT convalidated FROM pg_constraint WHERE conrelid = 'public.mfa_events'::regclass AND conname = 'mfa_events_reset_admin_exige_motivo') THEN
        RAISE EXCEPTION 'motivo do reset deve ser NOT VALID (preserva o historico)';
    END IF;
    IF has_column_privilege('nekkora_platform', 'public.mfa_events', 'ops_request_id', 'INSERT, UPDATE')
       OR NOT has_column_privilege('nekkora_platform', 'public.mfa_events', 'reason_code', 'INSERT') THEN
        RAISE EXCEPTION 'privilegio da Platform em mfa_events divergente';
    END IF;

    -- sslinfo no schema ops, sem EXECUTE publico
    IF NOT EXISTS (SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'sslinfo' AND n.nspname = 'ops') THEN
        RAISE EXCEPTION 'sslinfo fora do schema ops';
    END IF;

    -- controles anteriores: SECURITY DEFINER com search_path e sem EXECUTE publico (012-015)
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname IN ('public', 'ops') AND p.prosecdef
      AND (p.proconfig IS NULL OR NOT EXISTS (SELECT 1 FROM unnest(p.proconfig) c WHERE c LIKE 'search_path=%')
           OR has_function_privilege('public', p.oid, 'EXECUTE'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION 'SECURITY DEFINER sem search_path fixo ou com EXECUTE publico: %', v_bad; END IF;
END;
$$;

COMMIT;
