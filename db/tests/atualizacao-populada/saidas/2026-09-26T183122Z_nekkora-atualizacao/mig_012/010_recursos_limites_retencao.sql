-- Nekkora Platform - recursos contratados, limites efetivos, consumo diario provisorio e
-- politica de retencao (sem expurgo - mecanismo de exclusao e um recorte proprio, futuro).
-- Depende de 001-009 (toca workers/004, executions/006-008-009 e a ocorrencia de
-- agendamento/009 via CREATE OR REPLACE FUNCTION aditivo).
--
-- Codigos (SMALLINT, explicitos e permanentes - nao reordenar nem reutilizar):
--   organization_resource_limit_revisions.resource_code : 1=WORKERS_REGISTERED,
--     2=CONCURRENT_EXECUTIONS, 3=PENDING_EXECUTIONS, 4=LOG_BYTES_DAILY, 5=EVENT_BYTES_DAILY
--   organization_resource_limit_revisions.limit_mode     : 1=FINITE, 2=UNLIMITED
--   organization_retention_policy_revisions.category     : 1=EXECUTION_EVENTS, 2=LOGS
--   organization_retention_policy_revisions.retention_mode : 1=FINITE, 2=INDEFINITE
--   organization_usage_daily.status                      : 1=OPEN (2=CLOSED reservado, ver nota)
--   automation_schedule_occurrences.skip_reason           : +6=CAPACITY_UNAVAILABLE (aditivo a 009)
--
-- Regras centrais:
--   - Limites e politicas sao revisoes imutaveis por (organization_id, resource_code|category),
--     numeradas e com vigencia atribuida sob lock em `organizations` - mesmo padrao de
--     automation_schedule_revisions (009), agora ancorado em `organizations` (que ja existe,
--     nao precisa de tabela de identidade separada).
--   - Ausencia de revisao NAO significa ilimitado - bloqueia a admissao daquele recurso.
--     Bootstrap: criar organizacao e as 5 revisoes iniciais na mesma transacao e
--     responsabilidade da aplicacao (nao ha default comercial silencioso no banco).
--   - O lock em `organizations` serializa ADMISSOES (o que aumenta uso) - nao serializa toda
--     alteracao de contagem. Cancelamento/termino de execucao e aposentadoria de worker reduzem
--     uso sem essa trava, deliberadamente (reduzir nunca ultrapassa limite, e travar toda baixa
--     criaria contencao desnecessaria no caminho mais frequente).
--   - Responsavel pelas revisoes de limite/retencao referencia `users` diretamente (FK simples,
--     nao pareada com organization_id) - mesmo padrao de identity_provisioning_events.
--     provisioned_by_user_id (003): registra autoria, nao concede autoridade. Nenhuma
--     permissao de RBAC e criada aqui - este e um fluxo operacional da Nekkora, fora do
--     controle de acesso do cliente (ORG_ADMIN do cliente nunca recebe essa capacidade).
--   - organization_usage_daily e alimentada por job periodico (nunca trigger por linha em
--     execution_events/logs, que criaria contencao no caminho de ingestao de alto volume).
--     Nasce sempre OPEN; CLOSED fica reservado no catalogo mas bloqueado nesta migration - o
--     protocolo que comprova completude (nenhuma transacao em voo ainda pode gravar naquele
--     dia) e um fechamento tecnico futuro, junto do bloco de expurgo. Ate existir, nenhum
--     agregado autoriza expurgo.

-- === organization_resource_limit_revisions ===

CREATE TABLE organization_resource_limit_revisions (
    revision_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id           UUID NOT NULL REFERENCES organizations (organization_id) ON DELETE RESTRICT,
    resource_code              SMALLINT NOT NULL CHECK (resource_code IN (1, 2, 3, 4, 5)),
    revision_number              INTEGER NOT NULL CHECK (revision_number > 0),
    effective_from                 TIMESTAMPTZ NOT NULL,
    limit_mode                       SMALLINT NOT NULL CHECK (limit_mode IN (1, 2)),
    limit_value                        BIGINT,
    provisioned_by_user_id                UUID NOT NULL REFERENCES users (user_id) ON DELETE RESTRICT,
    created_at                              TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (organization_id, resource_code, revision_number),
    CHECK ((limit_mode = 1) = (limit_value IS NOT NULL)),
    CHECK (limit_mode <> 1 OR limit_value >= 0)
);

COMMENT ON COLUMN organization_resource_limit_revisions.resource_code IS '1=WORKERS_REGISTERED, 2=CONCURRENT_EXECUTIONS, 3=PENDING_EXECUTIONS, 4=LOG_BYTES_DAILY, 5=EVENT_BYTES_DAILY';
COMMENT ON COLUMN organization_resource_limit_revisions.limit_mode IS '1=FINITE (limit_value obrigatorio, >=0 - 0 e finito valido, significa indisponivel), 2=UNLIMITED (limit_value nulo). Para 4/5, FINITE e limiar de alerta, nunca bloqueio de ingestao; UNLIMITED neles e ausencia de limiar, nao ausencia de medicao.';
COMMENT ON COLUMN organization_resource_limit_revisions.provisioned_by_user_id IS 'registra autoria do provisionamento operacional da Nekkora - nao concede autoridade, ver identity_provisioning_events (003)';

CREATE INDEX ix_org_resource_limit_revisions_vigencia ON organization_resource_limit_revisions (organization_id, resource_code, effective_from);

-- Leitura da revisao vigente - NAO trava nada por si so. O chamador e responsavel por ja ter
-- travado `organizations` (FOR UPDATE) antes de chamar, para que a leitura seja segura dentro
-- da mesma admissao atomica. Desempate por revision_number, nao so effective_from - dois
-- clock_timestamp() identicos nao sao impossiveis, so raros.
--
-- p_resource_code e INTEGER, nao SMALLINT: um literal como "1" dentro do corpo plpgsql dos
-- chamadores e tipado INTEGER por padrao, e integer->smallint nao e um cast implicito de
-- resolucao de funcao (so smallint->integer e). Declarar INTEGER aqui evita exigir ::smallint
-- em toda chamada; comparar smallint (coluna) com integer (parametro) no WHERE e implicito.
CREATE FUNCTION get_effective_resource_limit(p_organization_id UUID, p_resource_code INTEGER)
RETURNS TABLE (limit_mode SMALLINT, limit_value BIGINT)
LANGUAGE sql STABLE AS $$
    SELECT limit_mode, limit_value
    FROM organization_resource_limit_revisions
    WHERE organization_id = p_organization_id AND resource_code = p_resource_code
    ORDER BY effective_from DESC, revision_number DESC
    LIMIT 1;
$$;

-- Trava organizations, aloca numero e vigencia - mesmo padrao de enforce_schedule_revision_creation (009).
CREATE FUNCTION enforce_resource_limit_revision_creation() RETURNS TRIGGER AS $$
BEGIN
    PERFORM 1 FROM organizations WHERE organization_id = NEW.organization_id FOR UPDATE;

    NEW.revision_number := COALESCE(
        (SELECT MAX(revision_number) FROM organization_resource_limit_revisions
         WHERE organization_id = NEW.organization_id AND resource_code = NEW.resource_code),
        0
    ) + 1;
    NEW.effective_from := clock_timestamp();

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_org_resource_limit_revisions_enforce_creation
    BEFORE INSERT ON organization_resource_limit_revisions
    FOR EACH ROW
    EXECUTE FUNCTION enforce_resource_limit_revision_creation();

CREATE TRIGGER trg_org_resource_limit_revisions_prevent_update
    BEFORE UPDATE ON organization_resource_limit_revisions
    FOR EACH ROW
    EXECUTE FUNCTION prevent_update();

CREATE TRIGGER trg_org_resource_limit_revisions_prevent_delete
    BEFORE DELETE ON organization_resource_limit_revisions
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_org_resource_limit_revisions_prevent_truncate
    BEFORE TRUNCATE ON organization_resource_limit_revisions
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === organization_retention_policy_revisions ===
--
-- Elegibilidade de expurgo (mecanismo futuro, NAO implementado aqui): um registro recebido em
-- received_at e elegivel quando instante_avaliacao >= received_at + retention_days, usando a
-- politica VIGENTE NO MOMENTO EM QUE O REGISTRO FOI RECEBIDO (nao a vigente na avaliacao) -
-- reduzir o prazo afeta so registros futuros. Aritmetica sempre sobre TIMESTAMPTZ (periodos de
-- 24h reais), nunca convertendo para DATE antes, o que dependeria do fuso da sessao.

CREATE TABLE organization_retention_policy_revisions (
    revision_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id        UUID NOT NULL REFERENCES organizations (organization_id) ON DELETE RESTRICT,
    category                 SMALLINT NOT NULL CHECK (category IN (1, 2)),
    revision_number             INTEGER NOT NULL CHECK (revision_number > 0),
    effective_from                TIMESTAMPTZ NOT NULL,
    retention_mode                  SMALLINT NOT NULL CHECK (retention_mode IN (1, 2)),
    retention_days                    INTEGER,
    provisioned_by_user_id                UUID NOT NULL REFERENCES users (user_id) ON DELETE RESTRICT,
    created_at                              TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (organization_id, category, revision_number),
    CHECK ((retention_mode = 1) = (retention_days IS NOT NULL)),
    CHECK (retention_mode <> 1 OR retention_days > 0)
);

COMMENT ON COLUMN organization_retention_policy_revisions.category IS '1=EXECUTION_EVENTS, 2=LOGS';
COMMENT ON COLUMN organization_retention_policy_revisions.retention_mode IS '1=FINITE (retention_days obrigatorio, >0), 2=INDEFINITE (retention_days nulo) - sem expiracao automatica. Ausencia de politica = expurgo nao autorizado nessa categoria, nao promessa de retencao infinita.';

CREATE INDEX ix_org_retention_policy_revisions_vigencia ON organization_retention_policy_revisions (organization_id, category, effective_from);

CREATE FUNCTION enforce_retention_policy_revision_creation() RETURNS TRIGGER AS $$
BEGIN
    PERFORM 1 FROM organizations WHERE organization_id = NEW.organization_id FOR UPDATE;

    NEW.revision_number := COALESCE(
        (SELECT MAX(revision_number) FROM organization_retention_policy_revisions
         WHERE organization_id = NEW.organization_id AND category = NEW.category),
        0
    ) + 1;
    NEW.effective_from := clock_timestamp();

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_org_retention_policy_revisions_enforce_creation
    BEFORE INSERT ON organization_retention_policy_revisions
    FOR EACH ROW
    EXECUTE FUNCTION enforce_retention_policy_revision_creation();

CREATE TRIGGER trg_org_retention_policy_revisions_prevent_update
    BEFORE UPDATE ON organization_retention_policy_revisions
    FOR EACH ROW
    EXECUTE FUNCTION prevent_update();

CREATE TRIGGER trg_org_retention_policy_revisions_prevent_delete
    BEFORE DELETE ON organization_retention_policy_revisions
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_org_retention_policy_revisions_prevent_truncate
    BEFORE TRUNCATE ON organization_retention_policy_revisions
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === organization_usage_daily - consumo diario provisorio, por job periodico ===

CREATE TABLE organization_usage_daily (
    usage_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id      UUID NOT NULL REFERENCES organizations (organization_id) ON DELETE RESTRICT,
    resource_code          SMALLINT NOT NULL CHECK (resource_code IN (4, 5)),
    usage_date                DATE NOT NULL,
    records_count                BIGINT NOT NULL CHECK (records_count >= 0),
    bytes_count                    BIGINT NOT NULL CHECK (bytes_count >= 0),
    status                            SMALLINT NOT NULL CHECK (status = 1),
    consolidated_at                     TIMESTAMPTZ NOT NULL,

    UNIQUE (organization_id, resource_code, usage_date)
);

COMMENT ON COLUMN organization_usage_daily.resource_code IS '4=LOG_BYTES_DAILY, 5=EVENT_BYTES_DAILY';
COMMENT ON COLUMN organization_usage_daily.usage_date IS 'dia UTC derivado de received_at das linhas de origem - calcular explicitamente via (received_at AT TIME ZONE ''UTC'')::date no job, nunca depender do fuso da sessao';
COMMENT ON COLUMN organization_usage_daily.status IS '1=OPEN (unico valor aceito nesta migration). 2=CLOSED reservado no catalogo, mas bloqueado pelo CHECK ate o protocolo de fechamento (prova de completude apos a virada do dia) ser definido, junto do bloco de expurgo. Ausencia de linha = "nao consolidado", nao "zero uso".';
COMMENT ON TABLE organization_usage_daily IS 'nasce OPEN, recalculavel por completo a cada rodada do job (substitui, nunca soma) enquanto OPEN; permanente mesmo apos expurgo futuro dos dados de origem - torna-se o unico registro historico daquele dia.';

-- Origem imutavel mesmo OPEN (identidade, organizacao, recurso e dia); recalculo altera so os
-- totais e o horario de consolidacao. status so aceita 1 (CHECK da tabela) nesta migration.
CREATE FUNCTION prevent_usage_origin_change() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.usage_id <> OLD.usage_id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.resource_code <> OLD.resource_code
       OR NEW.usage_date <> OLD.usage_date THEN
        RAISE EXCEPTION 'origem do agregado diario e imutavel (usage_id=%)', OLD.usage_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_org_usage_daily_prevent_origin_change
    BEFORE UPDATE ON organization_usage_daily
    FOR EACH ROW
    EXECUTE FUNCTION prevent_usage_origin_change();

CREATE TRIGGER trg_org_usage_daily_prevent_delete
    BEFORE DELETE ON organization_usage_daily
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_org_usage_daily_prevent_truncate
    BEFORE TRUNCATE ON organization_usage_daily
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === Evolucao aditiva de 004: aposentadoria de Worker passa a ser terminal ===
--
-- RETIRED (3) volta a ser ACTIVE/SUSPENDED hoje sem nenhuma restricao - contornaria a checagem
-- de capacidade no INSERT (aposenta um, "des-aposenta", ultrapassa o limite sem passar pela
-- admissao). Correcao: aposentadoria congela a linha inteira, mesmo padrao de todo estado
-- terminal do resto do schema. Suspender continua reversivel (nao libera cota).

CREATE OR REPLACE FUNCTION prevent_worker_origin_change() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 3 THEN
        RAISE EXCEPTION 'worker aposentado e imutavel (worker_id=%)', OLD.worker_id;
    END IF;

    IF NEW.worker_id <> OLD.worker_id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'origem do worker e imutavel (worker_id=%)', OLD.worker_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Cadastro de Worker exige capacidade (WORKERS_REGISTERED) - trava organizations, conta
-- nao-aposentados (suspensos contam), decide sob a mesma revisao vigente.
CREATE FUNCTION enforce_worker_registration_capacity() RETURNS TRIGGER AS $$
DECLARE
    lim RECORD;
    current_count BIGINT;
BEGIN
    PERFORM 1 FROM organizations WHERE organization_id = NEW.organization_id FOR UPDATE;

    SELECT * INTO lim FROM get_effective_resource_limit(NEW.organization_id, 1);
    IF NOT FOUND THEN
        RAISE EXCEPTION 'organizacao sem configuracao de limite para workers (organization_id=%)', NEW.organization_id;
    END IF;

    IF lim.limit_mode = 1 THEN
        SELECT count(*) INTO current_count FROM workers WHERE organization_id = NEW.organization_id AND status <> 3;
        IF current_count >= lim.limit_value THEN
            RAISE EXCEPTION 'limite de workers cadastrados atingido (organization_id=%, limite=%)', NEW.organization_id, lim.limit_value;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_workers_enforce_registration_capacity
    BEFORE INSERT ON workers
    FOR EACH ROW
    EXECUTE FUNCTION enforce_worker_registration_capacity();

-- === Evolucao aditiva de 009: novo motivo de SKIPPED, distinto de OVERLAP/DEPENDENCY_UNAVAILABLE ===

ALTER TABLE automation_schedule_occurrences DROP CONSTRAINT automation_schedule_occurrences_skip_reason_check;
ALTER TABLE automation_schedule_occurrences ADD CONSTRAINT automation_schedule_occurrences_skip_reason_check
    CHECK (skip_reason IN (1, 2, 3, 4, 5, 6));

COMMENT ON COLUMN automation_schedule_occurrences.skip_reason IS '1=LATE, 2=OVERLAP, 3=SCHEDULE_SUSPENDED, 4=DEPENDENCY_UNAVAILABLE, 5=AUTHORIZATION_UNAVAILABLE, 6=CAPACITY_UNAVAILABLE';

-- === Evolucao aditiva de 006/008/009: capacidade na criacao e na reserva de execucao ===
--
-- CREATE OR REPLACE redefine de novo as duas funcoes (corpo completo reescrito) - mesma
-- tecnica aditiva ja usada em 008/009, para nao reabrir os arquivos anteriores.

CREATE OR REPLACE FUNCTION enforce_execution_creation_prerequisites() RETURNS TRIGGER AS $$
DECLARE
    org_status SMALLINT;
    ws_status SMALLINT;
    automation_status SMALLINT;
    version_status SMALLINT;
    version_command JSONB;
    dep RECORD;
    secret_revoked TIMESTAMPTZ;
    revision_authorized_by UUID;
    revision_automation_version UUID;
    limit_rec RECORD;
    pending_count BIGINT;
BEGIN
    IF NEW.status <> 1 THEN
        RAISE EXCEPTION 'execucao deve nascer PENDING (execution_id=%)', NEW.execution_id;
    END IF;

    IF NEW.worker_id IS NOT NULL
       OR NEW.worker_agent_assignment_id IS NOT NULL
       OR NEW.worker_workspace_assignment_id IS NOT NULL
       OR NEW.reserved_at IS NOT NULL
       OR NEW.dispatched_at IS NOT NULL
       OR NEW.started_at IS NOT NULL
       OR NEW.finished_at IS NOT NULL
       OR NEW.exit_code IS NOT NULL
       OR NEW.pid IS NOT NULL
       OR NEW.status_reason IS NOT NULL
       OR NEW.error_summary IS NOT NULL
       OR NEW.cancel_requested_at IS NOT NULL
       OR NEW.cancel_requested_by_membership_id IS NOT NULL THEN
        RAISE EXCEPTION 'execucao nao pode nascer com alocacao ou dados de ciclo de vida preenchidos (execution_id=%)', NEW.execution_id;
    END IF;

    -- 010: organizations passa a ser travado aqui (antecipacao de lock relativo ao que a
    -- funcao ja fazia sobre workspace_secrets desde a 008 - nao e o primeiro lock desta
    -- funcao). Ordem fixa: organizations, depois o laco existente sobre workspace_secrets.
    SELECT status INTO org_status FROM organizations WHERE organization_id = NEW.organization_id FOR UPDATE;
    IF org_status <> 1 THEN
        RAISE EXCEPTION 'organizacao nao esta ativa (execution_id=%)', NEW.execution_id;
    END IF;

    -- 010: fila de pendentes (PENDING_EXECUTIONS) - mesmo lock acima, sem janela de corrida.
    SELECT * INTO limit_rec FROM get_effective_resource_limit(NEW.organization_id, 3);
    IF NOT FOUND THEN
        RAISE EXCEPTION 'organizacao sem configuracao de limite para execucoes pendentes (execution_id=%)', NEW.execution_id;
    END IF;
    IF limit_rec.limit_mode = 1 THEN
        SELECT count(*) INTO pending_count FROM executions WHERE organization_id = NEW.organization_id AND status = 1;
        IF pending_count >= limit_rec.limit_value THEN
            RAISE EXCEPTION 'limite de execucoes pendentes atingido (execution_id=%, limite=%)', NEW.execution_id, limit_rec.limit_value;
        END IF;
    END IF;

    SELECT status INTO ws_status FROM workspaces WHERE workspace_id = NEW.workspace_id;
    IF ws_status <> 1 THEN
        RAISE EXCEPTION 'workspace nao esta ativo (execution_id=%)', NEW.execution_id;
    END IF;

    SELECT status INTO automation_status FROM automations WHERE automation_id = NEW.automation_id;
    IF automation_status <> 1 THEN
        RAISE EXCEPTION 'automacao nao esta ativa (execution_id=%)', NEW.execution_id;
    END IF;

    SELECT status, command_json INTO version_status, version_command
    FROM automation_versions WHERE automation_version_id = NEW.automation_version_id;
    IF version_status <> 2 THEN
        RAISE EXCEPTION 'versao da automacao nao esta publicada (execution_id=%)', NEW.execution_id;
    END IF;

    IF NEW.command_snapshot <> version_command THEN
        RAISE EXCEPTION 'command_snapshot nao corresponde ao comando da versao referenciada (execution_id=%)', NEW.execution_id;
    END IF;

    FOR dep IN
        SELECT sb.secret_id AS dep_id
        FROM automation_version_bindings b
        JOIN automation_version_secret_bindings sb ON sb.binding_id = b.binding_id
        WHERE b.automation_version_id = NEW.automation_version_id
        ORDER BY sb.secret_id::text
    LOOP
        SELECT revoked_at INTO secret_revoked FROM workspace_secrets WHERE secret_id = dep.dep_id FOR UPDATE;
        IF secret_revoked IS NOT NULL THEN
            RAISE EXCEPTION 'secret vinculado a versao esta revogado (execution_id=%, secret_id=%)', NEW.execution_id, dep.dep_id;
        END IF;
    END LOOP;

    IF NEW.origin = 2 THEN
        SELECT r.authorized_by_membership_id, r.automation_version_id
        INTO revision_authorized_by, revision_automation_version
        FROM automation_schedule_occurrences o
        JOIN automation_schedule_revisions r ON r.revision_id = o.revision_id
        WHERE o.occurrence_id = NEW.schedule_occurrence_id;

        IF revision_authorized_by IS DISTINCT FROM NEW.authorized_by_membership_id THEN
            RAISE EXCEPTION 'authorized_by_membership_id nao corresponde ao responsavel da revisao (execution_id=%)', NEW.execution_id;
        END IF;
        IF revision_automation_version IS DISTINCT FROM NEW.automation_version_id THEN
            RAISE EXCEPTION 'automation_version_id nao corresponde a revisao da ocorrencia (execution_id=%)', NEW.execution_id;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION enforce_execution_reservation_prerequisites() RETURNS TRIGGER AS $$
DECLARE
    org_status SMALLINT;
    ws_status SMALLINT;
    automation_status SMALLINT;
    worker_status SMALLINT;
    agent_status SMALLINT;
    waa_status SMALLINT;
    waa_ended TIMESTAMPTZ;
    waa_agent_id UUID;
    wwa_status SMALLINT;
    dep RECORD;
    secret_revoked TIMESTAMPTZ;
    limit_rec RECORD;
    concurrent_count BIGINT;
BEGIN
    IF NOT (OLD.status = 1 AND NEW.status = 2) THEN
        RETURN NEW;
    END IF;

    SELECT status INTO org_status FROM organizations WHERE organization_id = NEW.organization_id FOR UPDATE;
    IF org_status <> 1 THEN
        RAISE EXCEPTION 'organizacao nao esta ativa para reserva (execution_id=%)', NEW.execution_id;
    END IF;

    -- 010: execucoes simultaneas (CONCURRENT_EXECUTIONS) - reaproveita o lock de organizations
    -- acima, que ja era o primeiro desta cadeia desde 006. Sem capacidade, a reserva falha e a
    -- execucao permanece PENDING, aguardando - nao e um SKIPPED de agendamento.
    SELECT * INTO limit_rec FROM get_effective_resource_limit(NEW.organization_id, 2);
    IF NOT FOUND THEN
        RAISE EXCEPTION 'organizacao sem configuracao de limite para execucoes simultaneas (execution_id=%)', NEW.execution_id;
    END IF;
    IF limit_rec.limit_mode = 1 THEN
        SELECT count(*) INTO concurrent_count FROM executions WHERE organization_id = NEW.organization_id AND status IN (2, 3);
        IF concurrent_count >= limit_rec.limit_value THEN
            RAISE EXCEPTION 'limite de execucoes simultaneas atingido (execution_id=%, limite=%)', NEW.execution_id, limit_rec.limit_value;
        END IF;
    END IF;

    SELECT status INTO ws_status FROM workspaces WHERE workspace_id = NEW.workspace_id FOR UPDATE;
    IF ws_status <> 1 THEN
        RAISE EXCEPTION 'workspace nao esta ativo para reserva (execution_id=%)', NEW.execution_id;
    END IF;

    SELECT status INTO automation_status FROM automations WHERE automation_id = NEW.automation_id FOR UPDATE;
    IF automation_status <> 1 THEN
        RAISE EXCEPTION 'automacao nao esta ativa para reserva (execution_id=%)', NEW.execution_id;
    END IF;

    SELECT status INTO worker_status FROM workers WHERE worker_id = NEW.worker_id FOR UPDATE;
    IF worker_status <> 1 THEN
        RAISE EXCEPTION 'worker nao esta ativo para reserva (execution_id=%)', NEW.execution_id;
    END IF;

    SELECT status, ended_at, agent_id INTO waa_status, waa_ended, waa_agent_id
    FROM worker_agent_assignments
    WHERE assignment_id = NEW.worker_agent_assignment_id
    FOR UPDATE;

    IF waa_status IS NULL OR waa_status <> 1 OR waa_ended IS NOT NULL THEN
        RAISE EXCEPTION 'associacao worker-agent nao esta ativa para reserva (execution_id=%)', NEW.execution_id;
    END IF;

    SELECT status INTO agent_status FROM agents WHERE agent_id = waa_agent_id FOR UPDATE;
    IF agent_status <> 2 THEN
        RAISE EXCEPTION 'agent nao esta ativo para reserva (execution_id=%)', NEW.execution_id;
    END IF;

    SELECT status INTO wwa_status
    FROM worker_workspace_assignments
    WHERE assignment_id = NEW.worker_workspace_assignment_id
    FOR UPDATE;

    IF wwa_status IS NULL OR wwa_status <> 1 THEN
        RAISE EXCEPTION 'autorizacao worker-workspace nao esta ativa para reserva (execution_id=%)', NEW.execution_id;
    END IF;

    FOR dep IN
        SELECT sb.secret_id AS dep_id
        FROM automation_version_bindings b
        JOIN automation_version_secret_bindings sb ON sb.binding_id = b.binding_id
        WHERE b.automation_version_id = NEW.automation_version_id
        ORDER BY sb.secret_id::text
    LOOP
        SELECT revoked_at INTO secret_revoked FROM workspace_secrets WHERE secret_id = dep.dep_id FOR UPDATE;
        IF secret_revoked IS NOT NULL THEN
            RAISE EXCEPTION 'secret vinculado a versao esta revogado (execution_id=%, secret_id=%)', NEW.execution_id, dep.dep_id;
        END IF;
    END LOOP;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- === Evolucao aditiva de 009: vigencia deterministica na materializacao ===
--
-- "Maior effective_from" sozinho deixa empate possivel (dois clock_timestamp() identicos nao
-- sao impossiveis, so raros). Acrescenta revision_number como desempate - mesmo ajuste
-- aplicado agora tambem aos limites/politicas desta migration (get_effective_resource_limit).

CREATE OR REPLACE FUNCTION materialize_schedule_occurrence() RETURNS TRIGGER AS $$
DECLARE
    schedule_status SMALLINT;
    schedule_resumed_at TIMESTAMPTZ;
    found_revision_id UUID;
    found_tolerance INTEGER;
    decision_instant TIMESTAMPTZ;
BEGIN
    IF NEW.status <> 1 OR NEW.execution_id IS NOT NULL OR NEW.skip_reason IS NOT NULL OR NEW.decided_at IS NOT NULL THEN
        RAISE EXCEPTION 'ocorrencia deve nascer PENDING, sem decisao (schedule_id=%, scheduled_for=%)', NEW.schedule_id, NEW.scheduled_for;
    END IF;

    SELECT status, resumed_at INTO schedule_status, schedule_resumed_at
    FROM automation_schedules WHERE schedule_id = NEW.schedule_id FOR UPDATE;

    IF schedule_status IS NULL THEN
        RAISE EXCEPTION 'agendamento inexistente (schedule_id=%)', NEW.schedule_id;
    END IF;
    IF schedule_status <> 1 THEN
        RAISE EXCEPTION 'agendamento suspenso nao aceita nova ocorrencia (schedule_id=%)', NEW.schedule_id;
    END IF;

    decision_instant := clock_timestamp();
    IF NEW.scheduled_for > decision_instant THEN
        RAISE EXCEPTION 'materializacao antecipada - scheduled_for no futuro (schedule_id=%, scheduled_for=%)', NEW.schedule_id, NEW.scheduled_for;
    END IF;

    IF schedule_resumed_at IS NOT NULL AND NEW.scheduled_for < schedule_resumed_at THEN
        RAISE EXCEPTION 'scheduled_for anterior ao marco de retomada (schedule_id=%, scheduled_for=%, resumed_at=%)', NEW.schedule_id, NEW.scheduled_for, schedule_resumed_at;
    END IF;

    SELECT revision_id, late_tolerance_seconds INTO found_revision_id, found_tolerance
    FROM automation_schedule_revisions
    WHERE schedule_id = NEW.schedule_id AND effective_from <= NEW.scheduled_for
    ORDER BY effective_from DESC, revision_number DESC
    LIMIT 1;

    IF found_revision_id IS NULL THEN
        RAISE EXCEPTION 'nenhuma revisao vigente para scheduled_for (schedule_id=%, scheduled_for=%)', NEW.schedule_id, NEW.scheduled_for;
    END IF;

    NEW.revision_id := found_revision_id;
    NEW.expires_at := NEW.scheduled_for + (found_tolerance * INTERVAL '1 second');

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- === Evolucao aditiva de 009: process_schedule_occurrence - lock de organizations primeiro,
--     capacidade decisiva (nao best-effort) e novo motivo CAPACITY_UNAVAILABLE ===
--
-- Ordem definitiva: organizations -> automation_schedules -> automation_schedule_occurrences.
-- Travar organizations depois de qualquer um dos outros dois arriscaria a mesma inversao de
-- lock ja corrigida uma vez nesta funcao (ver relatorio da 009). O retorno idempotente
-- (ocorrencia ja decidida) continua acontecendo ANTES de qualquer checagem de negocio,
-- inclusive capacidade - reprocessar uma ocorrencia concluida sempre devolve sua decisao
-- original, mesmo que a organizacao esteja sem capacidade agora.

CREATE OR REPLACE FUNCTION process_schedule_occurrence(p_occurrence_id UUID, p_authorized BOOLEAN)
RETURNS TABLE (out_status SMALLINT, out_skip_reason SMALLINT, out_execution_id UUID) AS $$
DECLARE
    lookup_organization_id UUID;
    lookup_schedule_id UUID;
    occ RECORD;
    org_status SMALLINT;
    schedule_status SMALLINT;
    decision_instant TIMESTAMPTZ;
    revision RECORD;
    ws_status SMALLINT;
    automation_status SMALLINT;
    version_status SMALLINT;
    command_json_snapshot JSONB;
    new_execution_id UUID;
    dep RECORD;
    secret_revoked TIMESTAMPTZ;
    limit_rec RECORD;
    pending_count BIGINT;
BEGIN
    -- organization_id e schedule_id de uma ocorrencia sao origem imutavel - ler sem lock e
    -- seguro, so para saber o que travar primeiro.
    SELECT organization_id, schedule_id INTO lookup_organization_id, lookup_schedule_id
    FROM automation_schedule_occurrences WHERE occurrence_id = p_occurrence_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'ocorrencia inexistente (occurrence_id=%)', p_occurrence_id;
    END IF;

    -- 1) trava organizations PRIMEIRO (capacidade)
    SELECT status INTO org_status FROM organizations WHERE organization_id = lookup_organization_id FOR UPDATE;

    -- 2) trava o agendamento
    SELECT s.status INTO schedule_status FROM automation_schedules s WHERE s.schedule_id = lookup_schedule_id FOR UPDATE;

    -- 3) trava a ocorrencia; reprocessamento e idempotente por construcao - retorna a decisao
    -- ja existente ANTES de avaliar qualquer condicao de negocio, inclusive capacidade.
    SELECT * INTO occ FROM automation_schedule_occurrences WHERE occurrence_id = p_occurrence_id FOR UPDATE;

    IF occ.status <> 1 THEN
        RETURN QUERY SELECT occ.status, occ.skip_reason, occ.execution_id;
        RETURN;
    END IF;

    -- 4) instante da decisao, sempre depois dos locks
    decision_instant := clock_timestamp();

    -- 5) tentativa antecipada e erro do chamador, nao uma decisao de negocio
    IF decision_instant < occ.scheduled_for THEN
        RAISE EXCEPTION 'tentativa de processar ocorrencia antes do horario previsto (occurrence_id=%, scheduled_for=%)', p_occurrence_id, occ.scheduled_for;
    END IF;

    -- 6) agendamento suspenso
    IF schedule_status <> 1 THEN
        UPDATE automation_schedule_occurrences SET status = 3, skip_reason = 3, decided_at = decision_instant
        WHERE occurrence_id = p_occurrence_id;
        RETURN QUERY SELECT 3::SMALLINT, 3::SMALLINT, NULL::UUID;
        RETURN;
    END IF;

    -- 7) vencida
    IF decision_instant >= occ.expires_at THEN
        UPDATE automation_schedule_occurrences SET status = 3, skip_reason = 1, decided_at = decision_instant
        WHERE occurrence_id = p_occurrence_id;
        RETURN QUERY SELECT 3::SMALLINT, 1::SMALLINT, NULL::UUID;
        RETURN;
    END IF;

    -- 8) autorizacao efetiva, decidida pela aplicacao antes de chamar
    IF NOT p_authorized THEN
        UPDATE automation_schedule_occurrences SET status = 3, skip_reason = 5, decided_at = decision_instant
        WHERE occurrence_id = p_occurrence_id;
        RETURN QUERY SELECT 3::SMALLINT, 5::SMALLINT, NULL::UUID;
        RETURN;
    END IF;

    -- 9) sobreposicao - escopada por agendamento, nao por automacao
    IF EXISTS (
        SELECT 1
        FROM automation_schedule_occurrences o
        JOIN executions e ON e.execution_id = o.execution_id
        WHERE o.schedule_id = occ.schedule_id
          AND o.occurrence_id <> p_occurrence_id
          AND e.status IN (1, 2, 3)
    ) THEN
        UPDATE automation_schedule_occurrences SET status = 3, skip_reason = 2, decided_at = decision_instant
        WHERE occurrence_id = p_occurrence_id;
        RETURN QUERY SELECT 3::SMALLINT, 2::SMALLINT, NULL::UUID;
        RETURN;
    END IF;

    -- 10) capacidade da fila de pendentes (010) - decisiva, nao best-effort: o lock de
    -- organizations do passo 1 permanece seguro at o INSERT, sem janela de corrida.
    SELECT * INTO limit_rec FROM get_effective_resource_limit(lookup_organization_id, 3);
    IF NOT FOUND THEN
        RAISE EXCEPTION 'organizacao sem configuracao de limite para execucoes pendentes (occurrence_id=%)', p_occurrence_id;
    END IF;
    IF limit_rec.limit_mode = 1 THEN
        SELECT count(*) INTO pending_count FROM executions WHERE organization_id = lookup_organization_id AND status = 1;
        IF pending_count >= limit_rec.limit_value THEN
            UPDATE automation_schedule_occurrences SET status = 3, skip_reason = 6, decided_at = decision_instant
            WHERE occurrence_id = p_occurrence_id;
            RETURN QUERY SELECT 3::SMALLINT, 6::SMALLINT, NULL::UUID;
            RETURN;
        END IF;
    END IF;

    -- 11) dependencias - checagem proativa (nao a conversao de uma excecao de INSERT rejeitado)
    SELECT * INTO revision FROM automation_schedule_revisions WHERE revision_id = occ.revision_id;

    SELECT status INTO ws_status FROM workspaces WHERE workspace_id = occ.workspace_id;
    SELECT status INTO automation_status FROM automations WHERE automation_id = revision.automation_id;
    SELECT status, command_json INTO version_status, command_json_snapshot
    FROM automation_versions WHERE automation_version_id = revision.automation_version_id;

    IF org_status IS DISTINCT FROM 1 OR ws_status IS DISTINCT FROM 1
       OR automation_status IS DISTINCT FROM 1 OR version_status IS DISTINCT FROM 2 THEN
        UPDATE automation_schedule_occurrences SET status = 3, skip_reason = 4, decided_at = decision_instant
        WHERE occurrence_id = p_occurrence_id;
        RETURN QUERY SELECT 3::SMALLINT, 4::SMALLINT, NULL::UUID;
        RETURN;
    END IF;

    FOR dep IN
        SELECT sb.secret_id AS dep_id
        FROM automation_version_bindings b
        JOIN automation_version_secret_bindings sb ON sb.binding_id = b.binding_id
        WHERE b.automation_version_id = revision.automation_version_id
        ORDER BY sb.secret_id::text
    LOOP
        SELECT revoked_at INTO secret_revoked FROM workspace_secrets WHERE secret_id = dep.dep_id FOR UPDATE;
        IF secret_revoked IS NOT NULL THEN
            UPDATE automation_schedule_occurrences SET status = 3, skip_reason = 4, decided_at = decision_instant
            WHERE occurrence_id = p_occurrence_id;
            RETURN QUERY SELECT 3::SMALLINT, 4::SMALLINT, NULL::UUID;
            RETURN;
        END IF;
    END LOOP;

    -- 12) elegivel - cria a execucao; a trigger AFTER INSERT finaliza a ocorrencia nesta
    -- mesma transacao.
    INSERT INTO executions (
        organization_id, workspace_id, automation_id, automation_version_id,
        request_key, request_fingerprint, origin, authorized_by_membership_id,
        schedule_occurrence_id, command_snapshot, status
    ) VALUES (
        occ.organization_id, occ.workspace_id, revision.automation_id, revision.automation_version_id,
        'schedule:' || p_occurrence_id::text, encode(digest(p_occurrence_id::text, 'sha256'), 'hex'),
        2, revision.authorized_by_membership_id,
        p_occurrence_id, command_json_snapshot, 1
    ) RETURNING executions.execution_id INTO new_execution_id;

    RETURN QUERY SELECT 2::SMALLINT, NULL::SMALLINT, new_execution_id;
END;
$$ LANGUAGE plpgsql;
