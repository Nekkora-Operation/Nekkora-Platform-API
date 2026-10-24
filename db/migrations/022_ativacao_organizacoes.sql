-- Nekkora Platform - capacidade inicial de execucao e ATIVACAO das organizacoes (missao PLATFORM-API-V1-003, item 5).
-- Proposta de origem: docs/relatorios/2026-10-21-identidade-002-base-e-plano.md, sec. 5 (opcoes 1+2+3). Relatorio:
-- docs/relatorios/2026-10-23-api-v1-003-ativacao-organizacoes.md.
--
-- CANDIDATA ate a aprovacao do relatorio da suite db/tests/ativacao-022.
--
-- O que muda:
--   * ciclo de ATIVACAO separado da situacao administrativa (organizations.status 1/2/3 nao muda de significado):
--     ops_activation.organization_states.state 1=AGUARDANDO_ATIVACAO, 2=ATIVADA. Toda organizacao criada depois desta
--     migration nasce AGUARDANDO_ATIVACAO (gatilho em organizations, qualquer caminho de criacao). Uma organizacao
--     aguardando ativacao, uma suspensa e uma desativada continuam distinguiveis: (status, state) sao independentes;
--   * capacidade inicial de execucao ZERO como REGRA: enquanto a organizacao nao esta ATIVADA, nenhuma revisao de limite
--     dos recursos 1-3 (WORKERS_REGISTERED, CONCURRENT_EXECUTIONS, PENDING_EXECUTIONS) pode ser diferente de FINITE 0
--     (gatilho em organization_resource_limit_revisions). So o INSERT feito DIRETAMENTE por superusuario (current_user
--     superusuario: administracao do cluster, fixtures declaradas de teste) nao e barrado; qualquer caminho por funcao de
--     provisionamento ou de identidade e barrado, inclusive quando chamado de uma sessao de superusuario;
--   * ATIVACAO por ato operacional autorizado e auditado: public.org_activation_activate, chamada SO por operadores op_*
--     membros do grupo nekkora_org_activation (participacao concedida pelo DBA, como nekkora_ops_mfa), autenticados pelo
--     certificado de cliente registrado (ops.autorizar da 016, lido pelo servidor). Recebe os limites EXPLICITOS (nenhum
--     valor comercial padrao no banco) e o motivo; aplica estado, limites e auditoria NUMA transacao;
--   * MUDANCA DE CAPACIDADE DEPOIS DA ATIVACAO (decisao da responsavel na revisao da missao, D-ATV-2): com a organizacao
--     ATIVADA, nenhuma revisao de limite dos recursos 1-5 e aceita fora de um ato autorizado e auditado. O ato e
--     public.org_activation_revise, com os mesmos controles da ativacao (grupo, certificado, responsavel provado, limites
--     explicitos, motivo) e auditoria na mesma tabela. O provisionamento direto (provision_resource_limit pelos papeis de
--     provisionamento e de identidade) deixa de alcancar organizacao ativada;
--   * indicacao para a API: public.org_activation_states (Platform), so o estado, sem os valores dos limites.
--
-- Por que fora de "ops" e sem o prefixo ops_: a sec. 19 da 016 e reexecutada em toda base (db/bootstrap/
-- aplicar_como_migrador.sh) e fecha o inventario do schema ops (21 tabelas) e a matriz EXATA de public.ops_*. A ativacao
-- tem schema proprio (ops_activation, mesmo modelo: sem USAGE para papel de LOGIN, acesso so pelas funcoes) e funcoes
-- public.org_activation_*, com matriz propria conferida na sec. 9. O dono das funcoes e nekkora_ops_mfa_owner: e o UNICO
-- papel com USAGE em ops (sec. 19 da 016), onde vivem o cadastro dos operadores, os certificados e ops.autorizar.
--
-- Responsavel: o operador da sessao (session_user, certificado) E uma conta da Platform PROVADA por ele
-- (ops.ops_operator_platform_accounts, vinculo por prova emitida na sessao da propria conta, 016 sec. 4), informada no ato.
-- Ela e a autoria das revisoes de limite (organization_resource_limit_revisions.provisioned_by_user_id, "autoria do
-- provisionamento operacional da Nekkora", 010). A conta tem de ser de OUTRA organizacao: vinculo com a organizacao alvo
-- e impeditivo (ops.tem_vinculo), como nos atos da 016. Nenhum ORG_ADMIN do cliente tem caminho para a ativacao.
--
-- Repeticao e concorrencia: o ato trava a organizacao (FOR UPDATE) e o estado antes de qualquer decisao, e mede a hora
-- depois dos locks. Ja ATIVADA -> RECUSA JA_ATIVADA, sem efeito (a mudanca posterior de capacidade e a revisao). Duas
-- ativacoes concorrentes: a segunda espera a primeira e recebe JA_ATIVADA. Revisao: mesmos locks; os limites pedidos iguais
-- aos do ultimo ato OK auditado (os vigentes, pelo gatilho) -> RECUSA SEM_MUDANCA, sem efeito (duas revisoes iguais e
-- concorrentes: a segunda espera e recebe SEM_MUDANCA; repetir uma revisao de resultado incerto e seguro). Ordem global:
-- organizacao -> estado de ativacao -> operador (a da 016: organizacao -> ... -> operadores).
--
-- Ato em curso: so o ato (dono das funcoes) grava em ops_activation.open_acts a organizacao e a transacao corrente
-- (pg_current_xact_id) antes das revisoes de limite e apaga a linha antes de terminar; o gatilho dos limites so aceita
-- revisao de organizacao ativada se essa linha existir na MESMA transacao (org_activation_act_open). Nada fica gravado:
-- a linha nasce e morre na transacao do ato (invariante: tabela vazia). Um GUC de sessao nao serviria: qualquer papel
-- pode defini-lo.
--
-- Organizacoes existentes (classificacao unica, origem 2): ATIVADA se algum dos recursos 1-3 ja tem revisao vigente
-- diferente de FINITE 0 (capacidade ja provisionada por caminho operacional anterior); senao AGUARDANDO_ATIVACAO.
--
-- ELEVACAO: exige SUPERUSER na janela (CREATE ROLE; ALTER FUNCTION ... OWNER TO nekkora_ops_mfa_owner, papel sem membros;
-- GRANT EXECUTE em provision_resource_limit, do dono nekkora_provisioning_owner; SET de nekkora.fn_organization_id na
-- configuracao das funcoes). Depende de 001-021. Atomica: uma transacao.

BEGIN;

-- === 1. Pre-condicao: 021 aplicada; objetos novos ausentes ===
DO $$
BEGIN
    IF to_regclass('public.mail_outbox') IS NULL THEN
        RAISE EXCEPTION '022 exige a 021 aplicada';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'ops_activation')
       OR to_regprocedure('public.org_activation_activate(uuid,uuid,text,bigint,bigint,bigint,bigint,bigint)') IS NOT NULL
       OR to_regprocedure('public.org_activation_revise(uuid,uuid,text,bigint,bigint,bigint,bigint,bigint)') IS NOT NULL
       OR to_regprocedure('public.org_activation_states(uuid[])') IS NOT NULL THEN
        RAISE EXCEPTION '022: objetos da ativacao ja existem';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_org_activation'
                AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls OR rolcanlogin)) THEN
        RAISE EXCEPTION '022: papel nekkora_org_activation pre-existente com atributo indevido';
    END IF;
END;
$$;

-- === 2. Papel: grupo dos operadores habilitados a ativar (participacao pelo DBA, so op_*) ===
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_org_activation') THEN
        CREATE ROLE nekkora_org_activation NOLOGIN;
    END IF;
END;
$$;
ALTER ROLE nekkora_org_activation NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

-- === 3. Schema operacional da ativacao ===
CREATE SCHEMA ops_activation;
REVOKE ALL ON SCHEMA ops_activation FROM PUBLIC;
GRANT USAGE ON SCHEMA ops_activation TO nekkora_ops_mfa_owner;
COMMENT ON SCHEMA ops_activation IS 'ativacao das organizacoes (022): so nekkora_ops_mfa_owner tem USAGE; acesso so pelas funcoes public.org_activation_*';

-- state: 1=AGUARDANDO_ATIVACAO, 2=ATIVADA. origin: 1=CRIADA_DEPOIS_DA_022 (nasce aguardando), 2=CLASSIFICADA_PELA_022.
CREATE TABLE ops_activation.organization_states (
    organization_id     UUID PRIMARY KEY REFERENCES public.organizations (organization_id) ON DELETE RESTRICT,
    state               SMALLINT NOT NULL CHECK (state IN (1, 2)),
    origin              SMALLINT NOT NULL CHECK (origin IN (1, 2)),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    activated_at        TIMESTAMPTZ,
    activation_event_id UUID,
    CHECK ((state = 2) = (activated_at IS NOT NULL)),
    CHECK (activation_event_id IS NULL OR state = 2),
    CHECK (origin = 2 OR state = 1 OR activation_event_id IS NOT NULL)
);
COMMENT ON COLUMN ops_activation.organization_states.state IS '1=AGUARDANDO_ATIVACAO, 2=ATIVADA (independente de organizations.status)';
COMMENT ON COLUMN ops_activation.organization_states.origin IS '1=criada depois da 022 (nasce aguardando), 2=classificada pela 022';

-- Auditoria append-only de cada tentativa (OK ou RECUSA) dos dois atos: act 1=ATIVACAO, 2=REVISAO_DE_CAPACIDADE. Sem FK
-- para organizations: a organizacao pode nao existir (recusa ORGANIZACAO_INEXISTENTE) e o identificador historico permanece.
CREATE TABLE ops_activation.activation_events (
    event_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    act                 SMALLINT NOT NULL CHECK (act IN (1, 2)),
    organization_id     UUID,
    result              TEXT NOT NULL CHECK (result IN ('OK', 'RECUSA')),
    reason_code         TEXT CHECK (reason_code ~ '^[A-Z_]{2,40}$'),
    session_role        TEXT NOT NULL DEFAULT session_user,
    operator_id         UUID REFERENCES ops.ops_operators (operator_id),
    cert_issuer_dn      TEXT,
    cert_serial         NUMERIC(60, 0),
    responsible_user_id UUID,
    motive              TEXT CHECK (motive IS NULL OR char_length(motive) <= 500),
    workers_registered  BIGINT,
    concurrent_executions BIGINT,
    pending_executions  BIGINT,
    log_bytes_daily_alert BIGINT,
    event_bytes_daily_alert BIGINT,
    limit_revision_ids  UUID[],
    created_at          TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((result = 'OK') = (reason_code IS NULL)),
    CHECK (result <> 'OK' OR (organization_id IS NOT NULL AND operator_id IS NOT NULL AND responsible_user_id IS NOT NULL
                              AND motive IS NOT NULL AND workers_registered >= 1 AND concurrent_executions >= 1
                              AND pending_executions >= 1 AND cardinality(limit_revision_ids) = 5))
);
CREATE INDEX ix_activation_events_org ON ops_activation.activation_events (organization_id);
COMMENT ON COLUMN ops_activation.activation_events.act IS '1=ATIVACAO (org_activation_activate), 2=REVISAO_DE_CAPACIDADE (org_activation_revise)';

-- Ato em curso (ver cabecalho): linha da transacao do ato; nunca confirmada (o ato a apaga antes de terminar).
CREATE TABLE ops_activation.open_acts (
    organization_id UUID NOT NULL,
    txid            XID8 NOT NULL,
    PRIMARY KEY (organization_id, txid)
);

-- === 4. Imutabilidade ===
CREATE TRIGGER trg_activation_events_prevent_update BEFORE UPDATE ON ops_activation.activation_events
    FOR EACH ROW EXECUTE FUNCTION public.prevent_update();
CREATE TRIGGER trg_activation_events_prevent_delete BEFORE DELETE ON ops_activation.activation_events
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_activation_events_prevent_truncate BEFORE TRUNCATE ON ops_activation.activation_events
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- Estado: so a transicao 1 -> 2, so pelo ato de ativacao (current_user = dono das funcoes), com instante e evento;
-- origem, criacao e organizacao imutaveis; nao volta; nao e apagado.
CREATE FUNCTION ops_activation.protect_state() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'estado de ativacao nao e apagado (organization_id=%)', OLD.organization_id USING ERRCODE = '23514';
    END IF;
    IF NEW.organization_id <> OLD.organization_id OR NEW.origin <> OLD.origin OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'campos de origem do estado de ativacao sao imutaveis (organization_id=%)', OLD.organization_id USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 2 THEN
        RAISE EXCEPTION 'organizacao ja ativada nao muda de estado (organization_id=%)', OLD.organization_id USING ERRCODE = '23514';
    END IF;
    IF NEW.state <> 2 OR NEW.activated_at IS NULL OR NEW.activation_event_id IS NULL THEN
        RAISE EXCEPTION 'unica transicao: AGUARDANDO_ATIVACAO -> ATIVADA, com instante e evento' USING ERRCODE = '23514';
    END IF;
    IF current_user <> 'nekkora_ops_mfa_owner' THEN
        RAISE EXCEPTION 'ativacao so pelo ato autorizado (org_activation_activate)' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_organization_states_protect BEFORE UPDATE OR DELETE ON ops_activation.organization_states
    FOR EACH ROW EXECUTE FUNCTION ops_activation.protect_state();
CREATE TRIGGER trg_organization_states_prevent_truncate BEFORE TRUNCATE ON ops_activation.organization_states
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 5. Classificacao das organizacoes existentes (origem 2) ===
INSERT INTO ops_activation.organization_states (organization_id, state, origin, activated_at)
SELECT o.organization_id, x.state, 2, CASE WHEN x.state = 2 THEN clock_timestamp() END
FROM public.organizations o
CROSS JOIN LATERAL (
    SELECT CASE WHEN EXISTS (
        SELECT 1 FROM generate_series(1, 3) c, LATERAL public.get_effective_resource_limit(o.organization_id, c) l
        WHERE NOT (l.limit_mode = 1 AND l.limit_value = 0)) THEN 2::smallint ELSE 1::smallint END AS state
) x;

-- === 6. Toda organizacao nova nasce AGUARDANDO_ATIVACAO (qualquer caminho de criacao) ===
CREATE FUNCTION ops_activation.on_organization_insert() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    INSERT INTO ops_activation.organization_states (organization_id, state, origin) VALUES (NEW.organization_id, 1, 1);
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_organizations_activation_state AFTER INSERT ON public.organizations
    FOR EACH ROW EXECUTE FUNCTION ops_activation.on_organization_insert();

-- Estado de uma organizacao, para o gatilho abaixo (que roda com o papel de quem insere a revisao). EXECUTE so dos papeis
-- que gravam revisoes de limite por funcao: provisionamento (012), identidade (017) e o dono das funcoes (022). Outro papel
-- que tente gravar capacidade falha fechado (sem EXECUTE).
CREATE FUNCTION public.org_activation_state_of(p_org UUID) RETURNS SMALLINT
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT s.state FROM ops_activation.organization_states s WHERE s.organization_id = p_org
$$;

-- Ato de ativacao ou de revisao EM CURSO nesta transacao para a organizacao (linha de open_acts gravada pelo ato).
CREATE FUNCTION public.org_activation_act_open(p_org UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM ops_activation.open_acts a WHERE a.organization_id = p_org AND a.txid = pg_current_xact_id())
$$;

-- Limites por estado. SECURITY INVOKER de proposito: current_user e o papel que insere (dentro de provision_resource_limit,
-- o dono do provisionamento). So o INSERT direto do superusuario passa.
--   * nao ATIVADA: capacidade de execucao (recursos 1-3) so FINITE 0;
--   * ATIVADA: nenhuma revisao (recursos 1-5) fora do ato autorizado em curso (ativacao ou revisao).
CREATE FUNCTION ops_activation.guard_execution_capacity() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_state SMALLINT;
BEGIN
    IF coalesce((SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.rolname = current_user), false) THEN
        RETURN NEW;
    END IF;
    v_state := public.org_activation_state_of(NEW.organization_id);
    IF v_state = 2 THEN
        IF NOT public.org_activation_act_open(NEW.organization_id) THEN
            RAISE EXCEPTION 'organizacao ativada: limite (recurso %) so pela revisao autorizada e auditada (org_activation_revise)', NEW.resource_code
                USING ERRCODE = '42501';
        END IF;
    ELSIF NEW.resource_code IN (1, 2, 3) AND NOT (NEW.limit_mode = 1 AND NEW.limit_value = 0) THEN
        RAISE EXCEPTION 'organizacao aguardando ativacao: capacidade de execucao (recurso %) so pela ativacao autorizada', NEW.resource_code
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_org_resource_limit_revisions_activation_guard BEFORE INSERT ON public.organization_resource_limit_revisions
    FOR EACH ROW EXECUTE FUNCTION ops_activation.guard_execution_capacity();

-- === 7. Privilegios do dono das funcoes ===
GRANT SELECT, INSERT, UPDATE ON ops_activation.organization_states TO nekkora_ops_mfa_owner;
GRANT SELECT, INSERT ON ops_activation.activation_events TO nekkora_ops_mfa_owner;
GRANT SELECT, INSERT, DELETE ON ops_activation.open_acts TO nekkora_ops_mfa_owner;
GRANT EXECUTE ON FUNCTION public.provision_resource_limit(UUID, INTEGER, SMALLINT, BIGINT, UUID) TO nekkora_ops_mfa_owner;

-- === 8. Funcoes ===

-- Ato de ativacao. Chamador: nekkora_org_activation (op_*). Recusas registradas (RECUSA) e devolvidas, sem efeito.
CREATE FUNCTION public.org_activation_activate(p_org UUID, p_responsible UUID, p_reason TEXT, p_workers BIGINT,
                                               p_concurrent BIGINT, p_pending BIGINT, p_log_alert BIGINT, p_event_alert BIGINT)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT, r_evento UUID)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_status SMALLINT;
    v_state SMALLINT;
    v_agora TIMESTAMPTZ;
    v_aut RECORD;
    v_motivo TEXT;
    v_evento UUID := gen_random_uuid();
    v_rev UUID[] := '{}';
BEGIN
    PERFORM ops.exigir_read_committed();
    -- locks, na ordem: organizacao -> estado de ativacao -> operador
    PERFORM set_config('nekkora.fn_organization_id', coalesce(p_org::text, ''), true);
    SELECT o.status INTO v_status FROM public.organizations o WHERE o.organization_id = p_org FOR UPDATE;
    SELECT s.state INTO v_state FROM ops_activation.organization_states s WHERE s.organization_id = p_org FOR UPDATE;
    PERFORM ops.travar_operadores(ARRAY[v_op]);
    -- hora DEPOIS dos locks; autorizacao e decisoes com ela
    v_agora := clock_timestamp();
    SELECT * INTO v_aut FROM ops.autorizar(v_op, NULL, v_agora);
    v_motivo := v_aut.o_motivo;
    IF v_motivo IS NULL AND v_status IS NULL THEN v_motivo := 'ORGANIZACAO_INEXISTENTE'; END IF;
    IF v_motivo IS NULL AND ops.tem_vinculo(v_op, p_org) THEN v_motivo := 'VINCULO_IMPEDITIVO'; END IF;
    IF v_motivo IS NULL AND (p_responsible IS NULL OR NOT EXISTS (
            SELECT 1 FROM ops.ops_operator_platform_accounts l
            WHERE l.operator_id = v_op AND l.user_id = p_responsible AND l.removed_at IS NULL AND l.organization_id <> p_org)) THEN
        v_motivo := 'RESPONSAVEL_NAO_VINCULADO';
    END IF;
    IF v_motivo IS NULL AND (p_reason IS NULL OR btrim(p_reason) = '' OR char_length(p_reason) > 500 OR p_reason ~ '[[:cntrl:]]'
                             OR p_workers IS NULL OR p_workers < 1 OR p_concurrent IS NULL OR p_concurrent < 1
                             OR p_pending IS NULL OR p_pending < 1 OR p_log_alert < 1 OR p_event_alert < 1) THEN
        v_motivo := 'PARAMETRO_INVALIDO';
    END IF;
    IF v_motivo IS NULL AND v_status <> 1 THEN v_motivo := 'ORGANIZACAO_NAO_ATIVA'; END IF;
    IF v_motivo IS NULL AND v_state IS DISTINCT FROM 1 THEN
        v_motivo := CASE WHEN v_state = 2 THEN 'JA_ATIVADA' ELSE 'ESTADO_AUSENTE' END;
    END IF;
    IF v_motivo IS NOT NULL THEN
        INSERT INTO ops_activation.activation_events (event_id, act, organization_id, result, reason_code, operator_id, cert_issuer_dn,
            cert_serial, responsible_user_id, motive, workers_registered, concurrent_executions, pending_executions,
            log_bytes_daily_alert, event_bytes_daily_alert, created_at)
        VALUES (v_evento, 1, p_org, 'RECUSA', v_motivo,
                (SELECT o.operator_id FROM ops.ops_operators o WHERE o.operator_id = v_op), v_aut.o_emissor, v_aut.o_serial,
                p_responsible, left(p_reason, 500), p_workers, p_concurrent, p_pending, p_log_alert, p_event_alert, v_agora);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, v_evento;
        RETURN;
    END IF;
    -- efeitos, na mesma transacao: estado (primeiro: o gatilho dos limites o le), limites explicitos, auditoria
    UPDATE ops_activation.organization_states SET state = 2, activated_at = v_agora, activation_event_id = v_evento
     WHERE organization_id = p_org AND state = 1;
    INSERT INTO ops_activation.open_acts (organization_id, txid) VALUES (p_org, pg_current_xact_id());
    v_rev := ARRAY[
        public.provision_resource_limit(p_org, 1, 1::smallint, p_workers, p_responsible),
        public.provision_resource_limit(p_org, 2, 1::smallint, p_concurrent, p_responsible),
        public.provision_resource_limit(p_org, 3, 1::smallint, p_pending, p_responsible),
        public.provision_resource_limit(p_org, 4, CASE WHEN p_log_alert IS NULL THEN 2 ELSE 1 END::smallint, p_log_alert, p_responsible),
        public.provision_resource_limit(p_org, 5, CASE WHEN p_event_alert IS NULL THEN 2 ELSE 1 END::smallint, p_event_alert, p_responsible)];
    DELETE FROM ops_activation.open_acts WHERE organization_id = p_org AND txid = pg_current_xact_id();
    INSERT INTO ops_activation.activation_events (event_id, act, organization_id, result, operator_id, cert_issuer_dn, cert_serial,
        responsible_user_id, motive, workers_registered, concurrent_executions, pending_executions, log_bytes_daily_alert,
        event_bytes_daily_alert, limit_revision_ids, created_at)
    VALUES (v_evento, 1, p_org, 'OK', v_op, v_aut.o_emissor, v_aut.o_serial, p_responsible, p_reason, p_workers, p_concurrent,
            p_pending, p_log_alert, p_event_alert, v_rev, v_agora);
    RETURN QUERY SELECT 'OK'::text, NULL::text, v_evento;
END;
$$;

-- Revisao de capacidade de organizacao ATIVADA (D-ATV-2). Chamador: nekkora_org_activation (op_*). Mesmos controles e
-- auditoria da ativacao (act = 2); os cinco limites sao sempre informados (estado completo, sem valor padrao).
CREATE FUNCTION public.org_activation_revise(p_org UUID, p_responsible UUID, p_reason TEXT, p_workers BIGINT,
                                             p_concurrent BIGINT, p_pending BIGINT, p_log_alert BIGINT, p_event_alert BIGINT)
RETURNS TABLE (r_resultado TEXT, r_motivo TEXT, r_evento UUID)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp SET nekkora.fn_organization_id = ''
AS $$
DECLARE
    v_op UUID := ops.operador_da_sessao();
    v_status SMALLINT;
    v_state SMALLINT;
    v_agora TIMESTAMPTZ;
    v_aut RECORD;
    v_motivo TEXT;
    v_evento UUID := gen_random_uuid();
    v_rev UUID[] := '{}';
BEGIN
    PERFORM ops.exigir_read_committed();
    -- locks, na ordem: organizacao -> estado de ativacao -> operador
    PERFORM set_config('nekkora.fn_organization_id', coalesce(p_org::text, ''), true);
    SELECT o.status INTO v_status FROM public.organizations o WHERE o.organization_id = p_org FOR UPDATE;
    SELECT s.state INTO v_state FROM ops_activation.organization_states s WHERE s.organization_id = p_org FOR UPDATE;
    PERFORM ops.travar_operadores(ARRAY[v_op]);
    v_agora := clock_timestamp();
    SELECT * INTO v_aut FROM ops.autorizar(v_op, NULL, v_agora);
    v_motivo := v_aut.o_motivo;
    IF v_motivo IS NULL AND v_status IS NULL THEN v_motivo := 'ORGANIZACAO_INEXISTENTE'; END IF;
    IF v_motivo IS NULL AND ops.tem_vinculo(v_op, p_org) THEN v_motivo := 'VINCULO_IMPEDITIVO'; END IF;
    IF v_motivo IS NULL AND (p_responsible IS NULL OR NOT EXISTS (
            SELECT 1 FROM ops.ops_operator_platform_accounts l
            WHERE l.operator_id = v_op AND l.user_id = p_responsible AND l.removed_at IS NULL AND l.organization_id <> p_org)) THEN
        v_motivo := 'RESPONSAVEL_NAO_VINCULADO';
    END IF;
    IF v_motivo IS NULL AND (p_reason IS NULL OR btrim(p_reason) = '' OR char_length(p_reason) > 500 OR p_reason ~ '[[:cntrl:]]'
                             OR p_workers IS NULL OR p_workers < 1 OR p_concurrent IS NULL OR p_concurrent < 1
                             OR p_pending IS NULL OR p_pending < 1 OR p_log_alert < 1 OR p_event_alert < 1) THEN
        v_motivo := 'PARAMETRO_INVALIDO';
    END IF;
    IF v_motivo IS NULL AND v_status <> 1 THEN v_motivo := 'ORGANIZACAO_NAO_ATIVA'; END IF;
    IF v_motivo IS NULL AND v_state IS DISTINCT FROM 2 THEN
        v_motivo := CASE WHEN v_state = 1 THEN 'NAO_ATIVADA' ELSE 'ESTADO_AUSENTE' END;
    END IF;
    -- SEM_MUDANCA: os limites pedidos sao os do ULTIMO ato OK auditado (ativacao ou revisao). Com a organizacao ativada, o
    -- gatilho dos limites so aceita revisao dentro desses atos, entao eles sao os vigentes; organizacao classificada como
    -- ATIVADA pela 022 (sem ato) nao tem referencia e a primeira revisao aplica. Leitura DEPOIS dos locks (READ COMMITTED:
    -- ve o ato concorrente ja confirmado). Sem ler organization_resource_limit_revisions (o dono das funcoes nao tem acesso).
    IF v_motivo IS NULL AND EXISTS (
            SELECT 1 FROM (SELECT e.workers_registered, e.concurrent_executions, e.pending_executions, e.log_bytes_daily_alert,
                                  e.event_bytes_daily_alert
                           FROM ops_activation.activation_events e
                           WHERE e.organization_id = p_org AND e.result = 'OK'
                           ORDER BY e.created_at DESC, e.event_id DESC LIMIT 1) u
            WHERE u.workers_registered = p_workers AND u.concurrent_executions = p_concurrent AND u.pending_executions = p_pending
              AND u.log_bytes_daily_alert IS NOT DISTINCT FROM p_log_alert AND u.event_bytes_daily_alert IS NOT DISTINCT FROM p_event_alert) THEN
        v_motivo := 'SEM_MUDANCA';
    END IF;
    IF v_motivo IS NOT NULL THEN
        INSERT INTO ops_activation.activation_events (event_id, act, organization_id, result, reason_code, operator_id, cert_issuer_dn,
            cert_serial, responsible_user_id, motive, workers_registered, concurrent_executions, pending_executions,
            log_bytes_daily_alert, event_bytes_daily_alert, created_at)
        VALUES (v_evento, 2, p_org, 'RECUSA', v_motivo,
                (SELECT o.operator_id FROM ops.ops_operators o WHERE o.operator_id = v_op), v_aut.o_emissor, v_aut.o_serial,
                p_responsible, left(p_reason, 500), p_workers, p_concurrent, p_pending, p_log_alert, p_event_alert, v_agora);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, v_evento;
        RETURN;
    END IF;
    INSERT INTO ops_activation.open_acts (organization_id, txid) VALUES (p_org, pg_current_xact_id());
    v_rev := ARRAY[
        public.provision_resource_limit(p_org, 1, 1::smallint, p_workers, p_responsible),
        public.provision_resource_limit(p_org, 2, 1::smallint, p_concurrent, p_responsible),
        public.provision_resource_limit(p_org, 3, 1::smallint, p_pending, p_responsible),
        public.provision_resource_limit(p_org, 4, CASE WHEN p_log_alert IS NULL THEN 2 ELSE 1 END::smallint, p_log_alert, p_responsible),
        public.provision_resource_limit(p_org, 5, CASE WHEN p_event_alert IS NULL THEN 2 ELSE 1 END::smallint, p_event_alert, p_responsible)];
    DELETE FROM ops_activation.open_acts WHERE organization_id = p_org AND txid = pg_current_xact_id();
    INSERT INTO ops_activation.activation_events (event_id, act, organization_id, result, operator_id, cert_issuer_dn, cert_serial,
        responsible_user_id, motive, workers_registered, concurrent_executions, pending_executions, log_bytes_daily_alert,
        event_bytes_daily_alert, limit_revision_ids, created_at)
    VALUES (v_evento, 2, p_org, 'OK', v_op, v_aut.o_emissor, v_aut.o_serial, p_responsible, p_reason, p_workers, p_concurrent,
            p_pending, p_log_alert, p_event_alert, v_rev, v_agora);
    RETURN QUERY SELECT 'OK'::text, NULL::text, v_evento;
END;
$$;

-- Indicacao para a API (Platform): so o estado de cada organizacao pedida, sem limites. Organizacao sem estado nao volta.
CREATE FUNCTION public.org_activation_states(p_orgs UUID[])
RETURNS TABLE (organization_id UUID, activation_state SMALLINT)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT s.organization_id, s.state FROM ops_activation.organization_states s
    WHERE s.organization_id = ANY (coalesce(p_orgs, '{}'::uuid[]))
$$;

-- === 9. Donos e EXECUTE ===
ALTER FUNCTION public.org_activation_activate(UUID, UUID, TEXT, BIGINT, BIGINT, BIGINT, BIGINT, BIGINT) OWNER TO nekkora_ops_mfa_owner;
ALTER FUNCTION public.org_activation_revise(UUID, UUID, TEXT, BIGINT, BIGINT, BIGINT, BIGINT, BIGINT) OWNER TO nekkora_ops_mfa_owner;
ALTER FUNCTION public.org_activation_states(UUID[]) OWNER TO nekkora_ops_mfa_owner;
ALTER FUNCTION public.org_activation_act_open(UUID) OWNER TO nekkora_ops_mfa_owner;
ALTER FUNCTION public.org_activation_state_of(UUID) OWNER TO nekkora_ops_mfa_owner;
ALTER FUNCTION ops_activation.on_organization_insert() OWNER TO nekkora_ops_mfa_owner;
REVOKE ALL ON FUNCTION public.org_activation_activate(UUID, UUID, TEXT, BIGINT, BIGINT, BIGINT, BIGINT, BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.org_activation_revise(UUID, UUID, TEXT, BIGINT, BIGINT, BIGINT, BIGINT, BIGINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.org_activation_states(UUID[]) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.org_activation_act_open(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.org_activation_state_of(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION ops_activation.on_organization_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION ops_activation.guard_execution_capacity() FROM PUBLIC;
REVOKE ALL ON FUNCTION ops_activation.protect_state() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.org_activation_activate(UUID, UUID, TEXT, BIGINT, BIGINT, BIGINT, BIGINT, BIGINT) TO nekkora_org_activation;
GRANT EXECUTE ON FUNCTION public.org_activation_revise(UUID, UUID, TEXT, BIGINT, BIGINT, BIGINT, BIGINT, BIGINT) TO nekkora_org_activation;
GRANT EXECUTE ON FUNCTION public.org_activation_states(UUID[]) TO nekkora_platform;
GRANT EXECUTE ON FUNCTION public.org_activation_state_of(UUID) TO nekkora_provisioning_owner, nekkora_identity_owner;
GRANT EXECUTE ON FUNCTION public.org_activation_act_open(UUID) TO nekkora_provisioning_owner, nekkora_identity_owner;

-- === 10. Invariantes ===
DO $$
DECLARE
    -- identidade de backup logico DECLARADA (IMPLANTACAO.md sec. 1; a mesma da 016 sec. 19 e da 021 sec. 5)
    v_backup CONSTANT TEXT := 'backup_nekkora';
    v_bad TEXT;
    v_real TEXT[];
BEGIN
    -- grupo: sem atributos; participacao so de op_*, sem ADMIN OPTION
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_org_activation'
                AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls OR rolcanlogin)) THEN
        RAISE EXCEPTION '022: nekkora_org_activation com atributo indevido';
    END IF;
    SELECT string_agg(format('%s -> %s', r.rolname, m.rolname), ', ') INTO v_bad
    FROM pg_auth_members am JOIN pg_roles r ON r.oid = am.roleid JOIN pg_roles m ON m.oid = am.member
    WHERE (r.rolname = 'nekkora_org_activation' AND NOT (m.rolname ~ '^op_[a-z0-9_]{2,40}$' AND NOT am.admin_option))
       OR m.rolname = 'nekkora_org_activation';
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '022: participacao indevida envolvendo nekkora_org_activation: %', v_bad; END IF;
    -- ACESSO EFETIVO ao schema e as tabelas de ops_activation (o mesmo criterio da revisao 3 da 021): has_*_privilege
    -- conta concessao direta, PUBLIC e qualquer heranca, inclusive a de papeis predefinidos. Recusado: papel nao
    -- superusuario que participa (MEMBER) de algum papel com acesso, fora o dono de cada objeto e o dono das funcoes. Unica
    -- excecao: a identidade de backup declarada, so por pg_read_all_data e so leitura (sem USAGE/CREATE proprios no schema).
    WITH alvo AS (
        SELECT c.oid, c.relowner FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'ops_activation' AND c.relkind = 'r'
    ), com_acesso AS (
        SELECT g.oid, a.relowner FROM pg_roles g, alvo a
        WHERE has_table_privilege(g.oid, a.oid, 'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
           OR has_any_column_privilege(g.oid, a.oid, 'SELECT, INSERT, UPDATE, REFERENCES')
        UNION
        SELECT g.oid, n.nspowner FROM pg_roles g, pg_namespace n
        WHERE n.nspname = 'ops_activation' AND has_schema_privilege(g.oid, n.oid, 'USAGE, CREATE')
    ), alcance AS (
        SELECT r.oid, r.rolname, array_agg(DISTINCT g.oid) AS fontes
        FROM pg_roles r JOIN com_acesso g ON pg_has_role(r.oid, g.oid, 'MEMBER')
        WHERE NOT r.rolsuper AND r.rolname !~ '^pg_' AND r.oid <> g.relowner
          AND r.rolname NOT IN ('migrador_nekkora', 'nekkora_ops_mfa_owner')
        GROUP BY r.oid, r.rolname
    )
    SELECT string_agg(x.rolname, ', ' ORDER BY x.rolname) INTO v_bad FROM alcance x
    WHERE NOT (x.rolname = v_backup
               AND x.fontes <@ ARRAY[x.oid, 'pg_read_all_data'::regrole::oid]
               AND NOT EXISTS (SELECT 1 FROM alvo a WHERE has_table_privilege(x.oid, a.oid, 'INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
                                                      OR has_any_column_privilege(x.oid, a.oid, 'INSERT, UPDATE, REFERENCES'))
               AND NOT has_schema_privilege(x.oid, 'ops_activation', 'CREATE'));
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '022: papel com acesso efetivo a ops_activation alem do dono das funcoes: %', v_bad; END IF;
    -- Concessoes conferidas tambem pela ACL (nomeia PUBLIC e o privilegio). Participacao no dono das funcoes e conferida
    -- pela sec. 19 da 016 (nenhum membro).
    -- schema: USAGE so do dono das funcoes; nenhuma outra concessao, inclusive a PUBLIC
    SELECT string_agg(DISTINCT CASE WHEN x.grantee = 0 THEN 'PUBLIC' ELSE pg_get_userbyid(x.grantee) END || ':' || x.privilege_type, ', ') INTO v_bad
    FROM pg_namespace n, aclexplode(coalesce(n.nspacl, acldefault('n', n.nspowner))) x
    WHERE n.nspname = 'ops_activation' AND x.grantee <> n.nspowner
      AND NOT (x.grantee = 'nekkora_ops_mfa_owner'::regrole AND x.privilege_type = 'USAGE');
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '022: concessao indevida no schema ops_activation: %', v_bad; END IF;
    -- tabelas: o dono das funcoes sem DELETE/TRUNCATE; eventos sem UPDATE; ninguem mais (fora dono da tabela e superusuarios)
    IF has_table_privilege('nekkora_ops_mfa_owner', 'ops_activation.organization_states', 'DELETE, TRUNCATE, REFERENCES, TRIGGER')
       OR has_table_privilege('nekkora_ops_mfa_owner', 'ops_activation.activation_events', 'UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')
       OR has_table_privilege('nekkora_ops_mfa_owner', 'ops_activation.open_acts', 'UPDATE, TRUNCATE, REFERENCES, TRIGGER') THEN
        RAISE EXCEPTION '022: privilegio do dono das funcoes alem do previsto';
    END IF;
    SELECT string_agg(DISTINCT format('%s:%s', CASE WHEN g.grantee = 0 THEN 'PUBLIC' ELSE pg_get_userbyid(g.grantee) END, g.relname), ', ') INTO v_bad
    FROM (SELECT c.relname, c.relowner, (aclexplode(c.relacl)).grantee FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
           WHERE n.nspname = 'ops_activation' AND c.relkind = 'r' AND c.relacl IS NOT NULL
          UNION ALL
          SELECT c.relname, c.relowner, (aclexplode(a.attacl)).grantee FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_attribute a ON a.attrelid = c.oid
           WHERE n.nspname = 'ops_activation' AND c.relkind = 'r' AND a.attacl IS NOT NULL AND NOT a.attisdropped) g
    WHERE g.grantee NOT IN (g.relowner, 'nekkora_ops_mfa_owner'::regrole);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '022: concessao em tabela de ops_activation alem do dono das funcoes: %', v_bad; END IF;
    IF (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'ops_activation' AND c.relkind = 'r') <> 3 THEN
        RAISE EXCEPTION '022: inventario de tabelas de ops_activation divergente';
    END IF;
    -- funcoes da ativacao: search_path restrito em todas; SECURITY DEFINER do dono operacional nas que leem ou gravam o
    -- schema; os dois gatilhos de verificacao (estado protegido e capacidade) sao SECURITY INVOKER, do migrador
    SELECT string_agg(p.oid::regprocedure::text, ', ') INTO v_bad
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE (n.nspname = 'public' AND p.proname LIKE 'org\_activation\_%' OR n.nspname = 'ops_activation')
      AND (NOT ('search_path=pg_catalog, pg_temp' = ANY (coalesce(p.proconfig, '{}')))
           OR CASE WHEN p.proname IN ('protect_state', 'guard_execution_capacity')
                   THEN p.prosecdef OR pg_get_userbyid(p.proowner) <> 'migrador_nekkora'
                   ELSE NOT p.prosecdef OR pg_get_userbyid(p.proowner) <> 'nekkora_ops_mfa_owner' END);
    IF v_bad IS NOT NULL THEN RAISE EXCEPTION '022: funcao da ativacao fora do padrao: %', v_bad; END IF;
    SELECT array_agg(x ORDER BY x COLLATE "C") INTO v_real FROM (
        SELECT n.nspname || '.' || p.proname || '=' || coalesce((SELECT string_agg(r.rolname, ',' ORDER BY r.rolname COLLATE "C") FROM pg_roles r
                 WHERE NOT r.rolsuper AND r.oid <> p.proowner AND r.rolname !~ '^op_' AND has_function_privilege(r.oid, p.oid, 'EXECUTE')), '') AS x
        FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE (n.nspname = 'public' AND p.proname LIKE 'org\_activation\_%') OR n.nspname = 'ops_activation') s;
    IF v_real IS DISTINCT FROM ARRAY['ops_activation.guard_execution_capacity=', 'ops_activation.on_organization_insert=',
                                     'ops_activation.protect_state=', 'public.org_activation_act_open=nekkora_identity_owner,nekkora_provisioning_owner',
                                     'public.org_activation_activate=nekkora_org_activation',
                                     'public.org_activation_revise=nekkora_org_activation',
                                     'public.org_activation_state_of=nekkora_identity_owner,nekkora_provisioning_owner',
                                     'public.org_activation_states=nekkora_platform'] THEN
        RAISE EXCEPTION '022: matriz de EXECUTE da ativacao divergente: %', v_real;
    END IF;
    -- provision_resource_limit: EXECUTE exatamente de provisionamento, identidade (017) e dono operacional (022)
    SELECT string_agg(r.rolname, ',' ORDER BY r.rolname COLLATE "C") INTO v_bad FROM pg_roles r
    WHERE NOT r.rolsuper AND r.rolname <> 'nekkora_provisioning_owner'
      AND has_function_privilege(r.oid, 'public.provision_resource_limit(uuid,integer,smallint,bigint,uuid)', 'EXECUTE');
    IF v_bad IS DISTINCT FROM 'nekkora_identity_owner,nekkora_ops_mfa_owner,nekkora_provisioning' THEN
        RAISE EXCEPTION '022: EXECUTE de provision_resource_limit divergente: %', v_bad;
    END IF;
    -- gatilhos ligados e cada organizacao com exatamente um estado
    IF (SELECT count(*) FROM pg_trigger WHERE tgname IN ('trg_organizations_activation_state', 'trg_org_resource_limit_revisions_activation_guard',
            'trg_organization_states_protect', 'trg_organization_states_prevent_truncate', 'trg_activation_events_prevent_update',
            'trg_activation_events_prevent_delete', 'trg_activation_events_prevent_truncate') AND NOT tgisinternal AND tgenabled = 'O') <> 7 THEN
        RAISE EXCEPTION '022: gatilhos da ativacao ausentes ou desligados';
    END IF;
    IF EXISTS (SELECT 1 FROM ops_activation.open_acts) THEN
        RAISE EXCEPTION '022: ato de capacidade em curso registrado fora de um ato';
    END IF;
    IF EXISTS (SELECT 1 FROM public.organizations o LEFT JOIN ops_activation.organization_states s USING (organization_id)
               WHERE s.organization_id IS NULL) THEN
        RAISE EXCEPTION '022: organizacao sem estado de ativacao';
    END IF;
    -- regra da capacidade zero: nenhuma organizacao aguardando ativacao com capacidade de execucao vigente
    IF EXISTS (SELECT 1 FROM ops_activation.organization_states s, generate_series(1, 3) c,
                     LATERAL public.get_effective_resource_limit(s.organization_id, c) l
               WHERE s.state = 1 AND NOT (l.limit_mode = 1 AND l.limit_value = 0)) THEN
        RAISE EXCEPTION '022: organizacao aguardando ativacao com capacidade de execucao';
    END IF;
END;
$$;

COMMIT;
