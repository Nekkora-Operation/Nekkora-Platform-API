-- Nekkora Platform - fila persistente de e-mail transacional (missao PLATFORM-API-IDENTIDADE-002, PR 5). Relatorio:
-- docs/relatorios/2026-10-22-identidade-002-email-real.md. Desenho aprovado: 2026-10-11-email-transacional-m365-desenho.md.
--
-- CANDIDATA ate a aprovacao do relatorio da suite db/tests/correio-021.
--
-- O que muda: UMA tabela nova, public.mail_outbox, e a trigger que a protege. Nenhuma tabela, funcao, privilegio ou
-- politica existente muda. Nao e tabela de tenant: nao tem organization_id (a invariante 1 da 017 continua em 43); a
-- organizacao, quando existe, so serve para montar o link e fica em target_organization_id, sem chave estrangeira.
--
-- Contrato (desenho DC-M2.x §6, com a persistencia exigida pela missao 002):
--   * um trabalho por mensagem; o destinatario e o token (quando ha) vao CIFRADOS juntos em payload_ciphertext
--     (AES-GCM da aplicacao, chave nekkora.keys, AAD = job_id); em claro, so o HMAC do destinatario (observabilidade e
--     deduplicacao, nunca o endereco);
--   * estados: 1 PENDENTE, 2 ENVIANDO (com prazo de posse), 3 ACEITO (aceite do provedor, NAO entrega), 4 RECUSADO
--     (definitivo), 5 INCERTO (pode ter saido: nunca reenviado automaticamente), 6 EXPIRADO (prazo da mensagem vencido
--     antes do envio);
--   * terminal (3 a 6): o payload cifrado e APAGADO no mesmo UPDATE; a linha guarda so metadados;
--   * tentativas no maximo 5; prazo da mensagem de no maximo 7 dias;
--   * INTENCAO DURAVEL: o trabalho e gravado NA MESMA TRANSACAO do ato de negocio (cadastro, troca de senha, reset...):
--     ou os dois confirmam, ou nenhum. O envio continua fora de transacao (despacho);
--   * DEDUPLICACAO PERSISTENTE: event_key e a chave estavel do evento (tipo + fato de negocio unico: hash do token, fator
--     novo, sessao criada...), UNIQUE: enquanto a linha existir, o mesmo evento nao vira dois trabalhos. A garantia NAO e
--     permanente: depois da limpeza da retencao (30 dias apos encerrado) a chave pode ser inserida de novo. Nao promete
--     entrega exatamente uma vez;
--   * notice_ref: tentativa do aviso da recuperacao emergencial (016), para devolver o desfecho assincrono a ela;
--   * RETENCAO: trabalho terminal pode ser apagado SO 30 dias depois de encerrado (limpeza controlada, em lotes, pelo
--     despacho); antes disso, ou fora de estado terminal, DELETE e recusado.
-- A trigger recusa mudar a origem do trabalho (id, tipo, HMAC, chave do evento, referencia, correlacao, criacao, prazo),
-- voltar de estado terminal, reduzir tentativas, ou apagar fora da retencao.
--
-- Sem janela de elevacao: a tabela e a trigger pertencem ao migrador (dono do banco), como as demais tabelas de public.
--
-- Revisao 2 (missao PLATFORM-API-V1-003; revisao 1 = sha256 2aeaf4b9..., nunca aplicada em base persistente): a invariante
-- 1 da sec. 5 contava como "acesso" o SELECT HERDADO de pg_read_all_data. A topologia de implantacao tem a identidade de
-- backup logico backup_nekkora membro de pg_read_all_data (ops/homologacao/IMPLANTACAO.md, sec. 1), e a 021 era recusada
-- na implantacao do ensaio-api-v1-002. A invariante passa a conferir as CONCESSOES da tabela e das colunas (ACL) e a
-- participacao nos papeis que as recebem (dono e Platform), sem contar papeis predefinidos do PostgreSQL. Nada mais muda:
-- tabela, trigger, privilegios e as demais invariantes sao os da revisao 1 (suite db/tests/correio-021, caso C).

BEGIN;

-- === 1. Pre-condicao ===
DO $$
BEGIN
    IF to_regprocedure('public.identity_password_reset(text, text)') IS NULL THEN
        RAISE EXCEPTION '021 exige a 020 aplicada';
    END IF;
    IF to_regclass('public.mail_outbox') IS NOT NULL THEN
        RAISE EXCEPTION '021: mail_outbox ja existe';
    END IF;
END;
$$;

-- === 2. Tabela ===
CREATE TABLE public.mail_outbox (
    job_id                 UUID PRIMARY KEY,
    kind                   TEXT NOT NULL CHECK (kind ~ '^[A-Z][A-Z_]{2,63}$'),
    target_organization_id UUID,
    recipient_hmac         TEXT NOT NULL CHECK (recipient_hmac ~ '^[A-Za-z0-9_-]{1,32}[.][0-9a-f]{32}$'),   -- IdentityHmac.global: id da chave + HMAC
    event_key              TEXT NOT NULL UNIQUE CHECK (event_key ~ '^[A-Z][A-Z_]{2,63}:[A-Za-z0-9._:-]{8,160}$'),
    notice_ref             UUID,
    payload_ciphertext     BYTEA,
    payload_nonce          BYTEA,
    key_id                 TEXT,
    state                  SMALLINT NOT NULL CHECK (state BETWEEN 1 AND 6),
    attempts               SMALLINT NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    next_attempt_at        TIMESTAMPTZ NOT NULL,
    lease_until            TIMESTAMPTZ,
    deadline_at            TIMESTAMPTZ NOT NULL,
    last_status            INTEGER CHECK (last_status IS NULL OR last_status BETWEEN 100 AND 599),
    correlation_id         UUID NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at            TIMESTAMPTZ,
    CHECK ((state IN (1, 2)) = (payload_ciphertext IS NOT NULL AND payload_nonce IS NOT NULL AND key_id IS NOT NULL)),
    CHECK (payload_ciphertext IS NOT NULL OR (payload_nonce IS NULL AND key_id IS NULL)),
    CHECK ((state IN (1, 2)) = (finished_at IS NULL)),
    CHECK ((state = 2) = (lease_until IS NOT NULL)),
    CHECK (deadline_at > created_at AND deadline_at <= created_at + interval '7 days'),
    CHECK ((notice_ref IS NOT NULL) = (kind IN ('NOTICE_MFA_EMERGENCY_HOLDER', 'NOTICE_MFA_EMERGENCY_ADMIN', 'NOTICE_MFA_EMERGENCY_EXECUTED')))
);
COMMENT ON TABLE public.mail_outbox IS 'Missao 002 (021): fila persistente de e-mail; destinatario e token cifrados; payload apagado no estado terminal';
COMMENT ON COLUMN public.mail_outbox.state IS '1=PENDENTE, 2=ENVIANDO, 3=ACEITO (aceite do provedor, nao entrega), 4=RECUSADO, 5=INCERTO, 6=EXPIRADO';
COMMENT ON COLUMN public.mail_outbox.target_organization_id IS 'so para o link (rotas por organizacao); sem FK: nao e tabela de tenant';
CREATE INDEX ix_mail_outbox_pendentes ON public.mail_outbox (next_attempt_at) WHERE state = 1;
CREATE INDEX ix_mail_outbox_enviando ON public.mail_outbox (lease_until) WHERE state = 2;
CREATE INDEX ix_mail_outbox_encerrados ON public.mail_outbox (finished_at) WHERE state >= 3;

-- === 3. Protecao ===
CREATE FUNCTION public.protect_mail_outbox() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- retencao: so trabalho terminal, 30 dias depois de encerrado (so metadados restam nele)
        IF OLD.state IN (1, 2) OR OLD.finished_at > clock_timestamp() - interval '30 days' THEN
            RAISE EXCEPTION 'trabalho de e-mail so e apagado terminal e 30 dias depois de encerrado' USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 1 OR NEW.attempts <> 0 OR NEW.lease_until IS NOT NULL OR NEW.last_status IS NOT NULL THEN
            RAISE EXCEPTION 'trabalho de e-mail nasce PENDENTE, sem tentativa' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.state NOT IN (1, 2) THEN
        RAISE EXCEPTION 'trabalho de e-mail terminal e imutavel' USING ERRCODE = '23514';
    END IF;
    IF NEW.job_id <> OLD.job_id OR NEW.kind <> OLD.kind OR NEW.recipient_hmac <> OLD.recipient_hmac
       OR NEW.target_organization_id IS DISTINCT FROM OLD.target_organization_id OR NEW.correlation_id <> OLD.correlation_id
       OR NEW.event_key <> OLD.event_key OR NEW.notice_ref IS DISTINCT FROM OLD.notice_ref
       OR NEW.created_at <> OLD.created_at OR NEW.deadline_at <> OLD.deadline_at THEN
        RAISE EXCEPTION 'origem do trabalho de e-mail e imutavel' USING ERRCODE = '23514';
    END IF;
    IF NEW.attempts < OLD.attempts THEN
        RAISE EXCEPTION 'tentativas do trabalho de e-mail so aumentam' USING ERRCODE = '23514';
    END IF;
    -- o payload so pode ser APAGADO (nunca trocado): ou fica igual, ou vai a nulo junto com o estado terminal
    IF NEW.payload_ciphertext IS NOT NULL AND (NEW.payload_ciphertext <> OLD.payload_ciphertext OR NEW.payload_nonce <> OLD.payload_nonce
                                               OR NEW.key_id <> OLD.key_id) THEN
        RAISE EXCEPTION 'payload do trabalho de e-mail nao e trocado' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.protect_mail_outbox() FROM PUBLIC;
CREATE TRIGGER trg_mail_outbox_protect BEFORE INSERT OR UPDATE OR DELETE ON public.mail_outbox FOR EACH ROW EXECUTE FUNCTION public.protect_mail_outbox();
CREATE TRIGGER trg_mail_outbox_prevent_truncate BEFORE TRUNCATE ON public.mail_outbox FOR EACH STATEMENT EXECUTE FUNCTION prevent_truncate();

-- === 4. Privilegios: so a Platform; DELETE so dentro da retencao (trigger) ===
REVOKE ALL ON public.mail_outbox FROM PUBLIC;
GRANT SELECT, DELETE, INSERT (job_id, kind, target_organization_id, recipient_hmac, event_key, notice_ref, payload_ciphertext, payload_nonce,
                      key_id, state, next_attempt_at, deadline_at, correlation_id),
      UPDATE (payload_ciphertext, payload_nonce, key_id, state, attempts, next_attempt_at, lease_until, last_status, finished_at)
    ON public.mail_outbox TO nekkora_platform;

-- === 5. Invariantes ===
DO $$
DECLARE
    v_bad TEXT;
BEGIN
    -- Acesso que esta migration controla: (a) CONCESSAO na tabela ou em qualquer coluna (ACL) a quem nao e o dono nem a
    -- Platform, inclusive PUBLIC; (b) participacao (MEMBER: herda ou pode SET ROLE) no dono ou na Platform, de papel que nao
    -- e superusuario. Papeis predefinidos do PostgreSQL (pg_read_all_data, pg_write_all_data...) concedem por participacao,
    -- sem entrada na ACL: ficam de fora daqui, e quem participa deles e decisao da topologia de implantacao (o backup logico,
    -- IMPLANTACAO.md sec. 1), nao desta migration. Revisao 1 usava has_table_privilege, que conta essa heranca.
    WITH alvo AS (
        SELECT c.oid, c.relowner FROM pg_class c WHERE c.oid = 'public.mail_outbox'::regclass
    ), concessoes AS (
        SELECT x.grantee FROM alvo a JOIN pg_class c ON c.oid = a.oid, aclexplode(c.relacl) x WHERE c.relacl IS NOT NULL
        UNION
        SELECT x.grantee FROM alvo a JOIN pg_attribute t ON t.attrelid = a.oid, aclexplode(t.attacl) x
        WHERE t.attacl IS NOT NULL AND NOT t.attisdropped
    ), indevidos AS (
        SELECT CASE WHEN g.grantee = 0 THEN 'PUBLIC' ELSE pg_get_userbyid(g.grantee) END AS nome
        FROM concessoes g, alvo a WHERE g.grantee NOT IN (a.relowner, 'nekkora_platform'::regrole)
        UNION
        SELECT r.rolname FROM pg_roles r, alvo a
        WHERE NOT r.rolsuper AND r.oid NOT IN (a.relowner, 'nekkora_platform'::regrole)
          AND (pg_has_role(r.oid, a.relowner, 'MEMBER') OR pg_has_role(r.oid, 'nekkora_platform'::regrole, 'MEMBER'))
    )
    SELECT string_agg(nome, ', ' ORDER BY nome) INTO v_bad FROM indevidos;
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION '021: papel com acesso a mail_outbox alem da Platform: %', v_bad;
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.mail_outbox', 'TRUNCATE, REFERENCES, TRIGGER')
       OR has_column_privilege('nekkora_platform', 'public.mail_outbox', 'event_key', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.mail_outbox', 'kind', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.mail_outbox', 'recipient_hmac', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.mail_outbox', 'deadline_at', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.mail_outbox', 'created_at', 'INSERT') THEN
        RAISE EXCEPTION '021: privilegios da Platform em mail_outbox alem do contrato';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'public.mail_outbox'::regclass AND attname = 'organization_id' AND NOT attisdropped) THEN
        RAISE EXCEPTION '021: mail_outbox nao pode ser tabela de tenant';
    END IF;
    IF (SELECT count(*) FROM pg_trigger WHERE tgrelid = 'public.mail_outbox'::regclass AND NOT tgisinternal AND tgenabled = 'O') <> 2 THEN
        RAISE EXCEPTION '021: triggers de protecao de mail_outbox ausentes ou desabilitadas';
    END IF;
END;
$$;

COMMIT;
