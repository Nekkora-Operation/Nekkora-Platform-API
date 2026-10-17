-- Nekkora Platform - fechamento verificavel dos agregados diarios e expurgo controlado dos
-- dados de origem (execution_events/logs), com separacao real de privilegio no PostgreSQL.
-- Depende de 001-010 (toca execution_events/logs da 007 e organization_usage_daily da 010
-- via ALTER/CREATE OR REPLACE aditivo).
--
-- Codigos novos (SMALLINT/INTEGER, permanentes):
--   execution_events.purge_category / logs.purge_category : fixo 1 / fixo 2 (discriminador,
--     mesmo padrao de binding_kind, 008) - liga ao category de organization_purge_runs
--   organization_usage_daily.status : ganha o valor 2=CLOSED, agora alcancavel (era so 1=OPEN
--     na 010) - so por close_usage_day(), nunca por INSERT nem pelo papel de aplicacao
--
-- Regras centrais:
--   - received_at e sempre atribuido pelo servidor (clock_timestamp(), nunca aceito do
--     chamador) no momento em que a linha-barreira de organization_usage_daily e travada
--     FOR SHARE - com retentativa se o dia mudar durante a espera do lock. Isso dissolve o
--     problema de "evento atrasado": um evento ocorrido antes cai no dia em que foi de fato
--     recebido, nunca precisa ser rejeitado por isso.
--   - A linha-barreira nasce sempre OPEN (verificado por trigger, nao so por privilegio) e
--     com consolidated_at NULL quando criada so pela ingestao (distingue "ainda nao
--     consolidado" de "consolidado, zero uso" - mesma distincao que a 010 fazia pela
--     ausencia da linha, agora tambem pela ausencia de consolidated_at).
--   - Fechamento (OPEN->CLOSED) so acontece via close_usage_day(): SECURITY DEFINER, so dias
--     passados, exige isolamento READ COMMITTED (verificado, nao so documentado), trava
--     FOR UPDATE a mesma linha que a ingestao trava FOR SHARE, agrega em comando posterior
--     ao lock (snapshot novo). Uma vez CLOSED, a linha e congelada por inteiro.
--   - Expurgo nunca e DELETE. E um UPDATE que redige o payload (-> NULL) preservando
--     identidade e fingerprint para sempre (deduplicacao de reenvio sobrevive ao expurgo) -
--     mas o EXECUTE de purge_expired_records() nao e concedido nesta migration: a garantia
--     de "reconhecido como identico" depende de um contrato de fingerprint (007/008) ainda
--     nao fechado. A funcao existe, testavel; a ativacao operacional e separada.
--   - Privilegio real do PostgreSQL, nao convencao de codigo: o papel de aplicacao nao tem
--     UPDATE/DELETE em execution_events/logs, nem privilegio sobre a coluna status de
--     organization_usage_daily. As funcoes privilegiadas sao SECURITY DEFINER, de um papel
--     dedicado sem LOGIN, com search_path fixo (pg_catalog, pg_temp) e objetos qualificados
--     por schema - nenhuma delas confia em variavel de sessao como prova de autorizacao.
--   - Proprietario das TABELAS (nekkora_retention_tables_owner) e um papel diferente do
--     proprietario das FUNCOES (nekkora_retention_owner). Ser proprietario de uma tabela da
--     poder administrativo irrestrito sobre ela (ALTER, DROP, desabilitar trigger), que nenhum
--     REVOKE de privilegio ordinario consegue reduzir - por isso as funcoes SECURITY DEFINER
--     nao sao donas das tabelas que tocam; recebem so os GRANTs explicitos que de fato usam
--     (ver matriz de privilegios), exatamente como a documentacao descreve o seu acesso.
--   - close_usage_day()/purge_expired_records() chamam assert_single_category_per_transaction()
--     como PRIMEIRA instrucao, antes de qualquer tentativa de lock - travar depois checaria
--     tarde demais (o lock ja teria sido disputado). Isso impede que uma unica transacao que
--     chame as duas funcoes de manutencao (ou a mesma funcao duas vezes) processe mais de um
--     (organization_id, resource_code/category) por transacao - o cenario que formaria ciclo
--     com uma ingestao mista (evento+log no mesmo commit, que so adquire locks compartilhados).
--     ESCOPO DESSA GARANTIA, explicitamente: só cobre chamadas a essas duas funções. Uma
--     atualizacao direta de organization_usage_daily feita por nekkora_app (reconsolidacao de
--     records_count/bytes_count enquanto OPEN, ver matriz) toma o lock de linha equivalente sem
--     passar por este guard - uma transacao de aplicacao que reconsolide duas categorias no
--     mesmo commit NAO e impedida por este mecanismo. Alem disso, o guard usa um GUC de sessao
--     (set_config) que qualquer chamador com EXECUTE nas funcoes poderia redefinir manualmente
--     antes de chamar - e uma rede de seguranca para um chamador que nao manipula o GUC de
--     proposito, nao uma restricao do banco impossivel de contornar. "Elimina o ciclo" só é
--     válido dentro desse escopo (as duas funções, chamador que não toca o GUC); não é uma
--     prova geral de ausência de deadlock para qualquer transação que escreva nesta tabela.
--
-- Nota de implementacao (nao uma decisao de produto): o papel usado para aplicar migrations
-- (POSTGRES_USER do docker-compose) e criado como superusuario pela imagem oficial do
-- Postgres - superusuarios ignoram toda checagem de privilegio. Testar as restricoes desta
-- migration exige conectar como nekkora_app/nekkora_retention_executor, nunca como esse papel.
-- Esta migration tambem nao faz um levantamento de privilegios para as tabelas de 001-010 -
-- nekkora_app recebe aqui so o necessario para os objetos que a 011 toca; um levantamento
-- completo permanece pendencia separada.

-- === Papeis ===

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_retention_tables_owner') THEN
        CREATE ROLE nekkora_retention_tables_owner NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_retention_owner') THEN
        CREATE ROLE nekkora_retention_owner NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_retention_executor') THEN
        CREATE ROLE nekkora_retention_executor LOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nekkora_app') THEN
        CREATE ROLE nekkora_app LOGIN;
    END IF;
END;
$$;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- === organization_purge_runs (auditoria em lote, imutavel, uma linha por chamada) ===

CREATE TABLE organization_purge_runs (
    purge_run_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    UUID NOT NULL REFERENCES organizations (organization_id) ON DELETE RESTRICT,
    category             SMALLINT NOT NULL CHECK (category IN (1, 2)),
    records_purged           INTEGER NOT NULL CHECK (records_purged >= 0),
    occurred_at                 TIMESTAMPTZ NOT NULL,
    purged_by_user_id              UUID NOT NULL REFERENCES users (user_id) ON DELETE RESTRICT,

    UNIQUE (purge_run_id, organization_id, category)
);

COMMENT ON COLUMN organization_purge_runs.category IS '1=EXECUTION_EVENTS, 2=LOGS - mesmo catalogo de organization_retention_policy_revisions';

CREATE TRIGGER trg_org_purge_runs_prevent_update
    BEFORE UPDATE ON organization_purge_runs
    FOR EACH ROW
    EXECUTE FUNCTION prevent_update();

CREATE TRIGGER trg_org_purge_runs_prevent_delete
    BEFORE DELETE ON organization_purge_runs
    FOR EACH ROW
    EXECUTE FUNCTION prevent_delete();

CREATE TRIGGER trg_org_purge_runs_prevent_truncate
    BEFORE TRUNCATE ON organization_purge_runs
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_truncate();

-- === Evolucao aditiva de 007: colunas de expurgo em execution_events/logs ===

ALTER TABLE execution_events ADD COLUMN purge_category SMALLINT NOT NULL DEFAULT 1 CHECK (purge_category = 1);
ALTER TABLE execution_events ADD COLUMN purged_at TIMESTAMPTZ;
ALTER TABLE execution_events ADD COLUMN purged_by_user_id UUID REFERENCES users (user_id) ON DELETE RESTRICT;
ALTER TABLE execution_events ADD COLUMN purge_run_id UUID;
ALTER TABLE execution_events ADD CONSTRAINT execution_events_purge_pair_check
    CHECK ((purged_at IS NULL) = (purged_by_user_id IS NULL) AND (purged_at IS NULL) = (purge_run_id IS NULL));
ALTER TABLE execution_events ADD CONSTRAINT execution_events_payload_purge_check
    CHECK (purged_at IS NOT NULL OR payload IS NOT NULL);
ALTER TABLE execution_events ALTER COLUMN payload DROP NOT NULL;
-- payload so pode ser NULL quando purged_at estiver preenchido - checagem acima cobre os dois
-- sentidos (nao pode ficar NULL sem purged_at, nem ficar preenchido tentando "des-expurgar").
ALTER TABLE execution_events ADD CONSTRAINT execution_events_purge_run_fkey
    FOREIGN KEY (purge_run_id, organization_id, purge_category)
    REFERENCES organization_purge_runs (purge_run_id, organization_id, category)
    ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

COMMENT ON COLUMN execution_events.purge_category IS 'fixo 1=EXECUTION_EVENTS - discriminador da FK composta com organization_purge_runs';
COMMENT ON COLUMN execution_events.purged_at IS 'expurgo e sempre UPDATE (redacao), nunca DELETE - payload vira NULL, identidade e fingerprint sobrevivem';

-- A CHECK da 007 usa "IS TRUE" exatamente para que payload=NULL nao passe silenciosamente
-- (NULL nao e TRUE) - isso bloquearia toda redacao legitima agora que payload e nullable.
-- Substituicao aditiva: dispensa a validacao de forma so quando purged_at ja esta preenchido
-- (redacao legitima, payload sempre NULL nesse caso); para qualquer linha nao expurgada a
-- checagem original da 007 continua exatamente tao estrita quanto antes.
ALTER TABLE execution_events DROP CONSTRAINT execution_events_check;
ALTER TABLE execution_events ADD CONSTRAINT execution_events_check
    CHECK (purged_at IS NOT NULL OR is_valid_event_payload(event_type, payload) IS TRUE);

ALTER TABLE logs ADD COLUMN purge_category SMALLINT NOT NULL DEFAULT 2 CHECK (purge_category = 2);
ALTER TABLE logs ADD COLUMN purged_at TIMESTAMPTZ;
ALTER TABLE logs ADD COLUMN purged_by_user_id UUID REFERENCES users (user_id) ON DELETE RESTRICT;
ALTER TABLE logs ADD COLUMN purge_run_id UUID;
ALTER TABLE logs ADD CONSTRAINT logs_purge_pair_check
    CHECK ((purged_at IS NULL) = (purged_by_user_id IS NULL) AND (purged_at IS NULL) = (purge_run_id IS NULL));
ALTER TABLE logs ADD CONSTRAINT logs_payload_purge_check
    CHECK (purged_at IS NOT NULL OR payload IS NOT NULL);
ALTER TABLE logs ALTER COLUMN payload DROP NOT NULL;
ALTER TABLE logs ADD CONSTRAINT logs_purge_run_fkey
    FOREIGN KEY (purge_run_id, organization_id, purge_category)
    REFERENCES organization_purge_runs (purge_run_id, organization_id, category)
    ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

COMMENT ON COLUMN logs.purge_category IS 'fixo 2=LOGS - discriminador da FK composta com organization_purge_runs';

-- mesma razao da correcao acima em execution_events_check (nome real do constraint sem
-- rotulo explicito na 007 e logs_check3, confirmado via \d logs).
ALTER TABLE logs DROP CONSTRAINT logs_check3;
ALTER TABLE logs ADD CONSTRAINT logs_check3
    CHECK (purged_at IS NOT NULL OR is_valid_log_payload(channel, payload) IS TRUE);

-- === Evolucao aditiva de 010: organization_usage_daily aceita CLOSED, distingue barreira de consolidacao ===

ALTER TABLE organization_usage_daily ALTER COLUMN consolidated_at DROP NOT NULL;
ALTER TABLE organization_usage_daily DROP CONSTRAINT organization_usage_daily_status_check;
ALTER TABLE organization_usage_daily ADD CONSTRAINT organization_usage_daily_status_check CHECK (status IN (1, 2));
ALTER TABLE organization_usage_daily ADD CONSTRAINT organization_usage_daily_closed_consolidated_check
    CHECK (status <> 2 OR consolidated_at IS NOT NULL);

COMMENT ON COLUMN organization_usage_daily.status IS '1=OPEN, 2=CLOSED - CLOSED so alcancavel via close_usage_day(); consolidated_at NULL enquanto a linha existe so como barreira de ingestao, nunca como medicao';
COMMENT ON COLUMN organization_usage_daily.consolidated_at IS 'NULL = linha-barreira, ainda sem nenhuma consolidacao real; preenchido = pelo menos uma consolidacao (inclusive a que o proprio fechamento faz) ja ocorreu';

-- Nasce sempre OPEN - verificado pelo banco, nao so pela ausencia de privilegio sobre status
-- (a ingestao precisa de INSERT nesta tabela para criar a linha-barreira).
CREATE FUNCTION enforce_usage_daily_born_open() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status <> 1 THEN
        RAISE EXCEPTION 'agregado diario deve nascer OPEN (organization_id=%, resource_code=%, usage_date=%)', NEW.organization_id, NEW.resource_code, NEW.usage_date;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_org_usage_daily_enforce_born_open
    BEFORE INSERT ON organization_usage_daily
    FOR EACH ROW
    EXECUTE FUNCTION enforce_usage_daily_born_open();

-- Congelamento total uma vez CLOSED - inclusive records_count/bytes_count/consolidated_at,
-- mesmo que o papel de aplicacao tenha privilegio de coluna sobre eles (o privilegio diz o
-- que pode ser tentado; esta trigger decide o que e aceito).
CREATE OR REPLACE FUNCTION prevent_usage_origin_change() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 2 THEN
        RAISE EXCEPTION 'agregado diario fechado e imutavel (usage_id=%)', OLD.usage_id;
    END IF;

    IF NEW.usage_id <> OLD.usage_id
       OR NEW.organization_id <> OLD.organization_id
       OR NEW.resource_code <> OLD.resource_code
       OR NEW.usage_date <> OLD.usage_date THEN
        RAISE EXCEPTION 'origem do agregado diario e imutavel (usage_id=%)', OLD.usage_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- === Politica de retencao vigente num instante especifico (por registro, nao um corte unico) ===

CREATE FUNCTION get_retention_policy_at(p_organization_id UUID, p_category INTEGER, p_at TIMESTAMPTZ)
RETURNS TABLE (retention_mode SMALLINT, retention_days INTEGER)
LANGUAGE sql STABLE AS $$
    SELECT retention_mode, retention_days
    FROM public.organization_retention_policy_revisions
    WHERE organization_id = p_organization_id AND category = p_category AND effective_from <= p_at
    ORDER BY effective_from DESC, revision_number DESC
    LIMIT 1;
$$;

-- === Barreira ingestao x fechamento: garante a linha, trava FOR SHARE, atribui received_at ===
--
-- Roda com o privilegio do CHAMADOR (nao e SECURITY DEFINER) - por isso nekkora_app precisa
-- de INSERT/SELECT em organization_usage_daily (ver GRANTs ao final). A protecao contra
-- status=2 nesse caminho e a trigger de nascimento acima, nao a ausencia de privilegio.
--
-- Laço limitado: se o dia mudar durante a espera do lock (virada de meia-noite exatamente
-- entre a leitura do instante e a obtencao do lock), repete para o novo dia - o lock
-- supérfluo sobre o dia antigo permanece até o fim da transacao (Postgres nao libera lock de
-- linha no meio de uma transacao sem SAVEPOINT), o que tem custo real (pode prolongar a
-- espera de quem tentar fechar/reconsolidar aquele dia antigo), mas nao compete com outro
-- FOR SHARE (locks compartilhados nunca competem entre si). Ao esgotar as tentativas, falha e
-- desfaz a transacao - nunca insere sem a barreira corretamente travada para o dia final.
CREATE FUNCTION assign_received_at_and_lock_barrier(p_organization_id UUID, p_resource_code INTEGER)
RETURNS TIMESTAMPTZ AS $$
DECLARE
    probe TIMESTAMPTZ;
    candidate_day DATE;
    confirmed TIMESTAMPTZ;
    confirmed_day DATE;
    row_status SMALLINT;
    attempts INTEGER := 0;
BEGIN
    LOOP
        attempts := attempts + 1;
        IF attempts > 5 THEN
            RAISE EXCEPTION 'nao foi possivel estabilizar a barreira de ingestao apos % tentativas (organization_id=%, resource_code=%)', attempts - 1, p_organization_id, p_resource_code;
        END IF;

        probe := clock_timestamp();
        candidate_day := (probe AT TIME ZONE 'UTC')::date;

        INSERT INTO organization_usage_daily (organization_id, resource_code, usage_date, records_count, bytes_count, status, consolidated_at)
        VALUES (p_organization_id, p_resource_code, candidate_day, 0, 0, 1, NULL)
        ON CONFLICT (organization_id, resource_code, usage_date) DO NOTHING;

        SELECT status INTO row_status FROM organization_usage_daily
        WHERE organization_id = p_organization_id AND resource_code = p_resource_code AND usage_date = candidate_day
        FOR SHARE;

        confirmed := clock_timestamp();
        confirmed_day := (confirmed AT TIME ZONE 'UTC')::date;

        IF confirmed_day = candidate_day THEN
            IF row_status <> 1 THEN
                RAISE EXCEPTION 'agregado do dia ja esta fechado, insercao nao pode prosseguir (organization_id=%, resource_code=%, usage_date=%)', p_organization_id, p_resource_code, candidate_day;
            END IF;
            RETURN confirmed;
        END IF;
        -- dia mudou durante a espera do lock - repete para o novo dia (mantendo o lock supérfluo do antigo)
    END LOOP;
END;
$$ LANGUAGE plpgsql;

-- === Evolucao aditiva de 007: received_at sempre atribuido pelo servidor, sob a barreira ===

CREATE OR REPLACE FUNCTION enforce_execution_event_agent_origin() RETURNS TRIGGER AS $$
DECLARE
    exec_waa_id UUID;
    waa_agent_id UUID;
BEGIN
    SELECT worker_agent_assignment_id INTO exec_waa_id FROM executions WHERE execution_id = NEW.execution_id;
    IF exec_waa_id IS NULL THEN
        RAISE EXCEPTION 'execucao sem alocacao nao pode receber evento (execution_id=%)', NEW.execution_id;
    END IF;

    SELECT agent_id INTO waa_agent_id FROM worker_agent_assignments WHERE assignment_id = exec_waa_id;
    IF waa_agent_id IS DISTINCT FROM NEW.agent_id THEN
        RAISE EXCEPTION 'agent nao corresponde a alocacao historica da execucao (event_id=%)', NEW.event_id;
    END IF;

    -- Os campos de expurgo nascem sempre NULL - a CHECK (purged_at IS NOT NULL OR
    -- is_valid_event_payload(...) IS TRUE) dispensa a validacao de forma quando purged_at esta
    -- preenchido, exatamente para permitir a transicao de redacao (UPDATE) legitima; sem esta
    -- checagem aqui, um INSERT poderia chegar ja "pre-redigido" (payload NULL, purged_at/
    -- purge_run_id apontando para um lote de expurgo real e existente) sem nunca ter passado
    -- por purge_expired_records/enforce_redaction_or_reject (que so valida UPDATE, nunca
    -- INSERT).
    IF NEW.purged_at IS NOT NULL OR NEW.purged_by_user_id IS NOT NULL OR NEW.purge_run_id IS NOT NULL THEN
        RAISE EXCEPTION 'evento nao pode nascer ja expurgado (event_id=%)', NEW.event_id;
    END IF;

    -- 011: received_at nunca vem do chamador - resource_code 5 = EVENT_BYTES_DAILY
    NEW.received_at := assign_received_at_and_lock_barrier(NEW.organization_id, 5);

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION enforce_log_historical_origin() RETURNS TRIGGER AS $$
DECLARE
    exec_waa_id UUID;
    waa_agent_id UUID;
BEGIN
    IF NEW.execution_id IS NOT NULL THEN
        SELECT worker_agent_assignment_id INTO exec_waa_id FROM executions WHERE execution_id = NEW.execution_id;
        IF exec_waa_id IS NULL THEN
            RAISE EXCEPTION 'execucao sem alocacao nao pode receber log vinculado (execution_id=%)', NEW.execution_id;
        END IF;
        IF NEW.worker_agent_assignment_id IS DISTINCT FROM exec_waa_id THEN
            RAISE EXCEPTION 'associacao informada diverge da registrada na execucao (execution_id=%)', NEW.execution_id;
        END IF;
    END IF;

    IF NEW.worker_agent_assignment_id IS NOT NULL THEN
        SELECT agent_id INTO waa_agent_id FROM worker_agent_assignments WHERE assignment_id = NEW.worker_agent_assignment_id;
        IF waa_agent_id IS DISTINCT FROM NEW.agent_id THEN
            RAISE EXCEPTION 'agent nao corresponde a associacao informada (log_id=%)', NEW.log_id;
        END IF;
    END IF;

    -- mesma razao da checagem equivalente em enforce_execution_event_agent_origin.
    IF NEW.purged_at IS NOT NULL OR NEW.purged_by_user_id IS NOT NULL OR NEW.purge_run_id IS NOT NULL THEN
        RAISE EXCEPTION 'log nao pode nascer ja expurgado (log_id=%)', NEW.log_id;
    END IF;

    -- 011: received_at nunca vem do chamador - resource_code 4 = LOG_BYTES_DAILY
    NEW.received_at := assign_received_at_and_lock_barrier(NEW.organization_id, 4);

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- === Trigger de redacao - substitui o prevent_update() generico so em execution_events/logs ===
--
-- Nao depende de nenhuma variavel de sessao: valida a FORMA exata da transicao de expurgo e
-- revalida a ELEGIBILIDADE (dia fechado E prazo de retencao vencido - CLOSED por si so nao
-- basta, um dia pode fechar muito antes do prazo de retencao acabar). Defesa em profundidade
-- contra um bug dentro da propria funcao confiavel - o chamador externo ja foi excluido pela
-- ausencia de privilegio de UPDATE (papel de aplicacao nao tem; so nekkora_retention_owner).

CREATE FUNCTION enforce_redaction_or_reject() RETURNS TRIGGER AS $$
DECLARE
    shape_ok BOOLEAN;
    day_status SMALLINT;
    resource_code_for_check INTEGER;
    rp RECORD;
BEGIN
    IF TG_TABLE_NAME = 'execution_events' THEN
        shape_ok := (
            OLD.purged_at IS NULL AND NEW.purged_at IS NOT NULL
            AND NEW.payload IS NULL
            AND NEW.purge_run_id IS NOT NULL
            AND NEW.purged_by_user_id IS NOT NULL
            AND NEW.event_id = OLD.event_id
            AND NEW.organization_id = OLD.organization_id
            AND NEW.workspace_id = OLD.workspace_id
            AND NEW.execution_id = OLD.execution_id
            AND NEW.agent_id = OLD.agent_id
            AND NEW.sequence = OLD.sequence
            AND NEW.event_type = OLD.event_type
            AND NEW.occurred_at = OLD.occurred_at
            AND NEW.received_at = OLD.received_at
            AND NEW.fingerprint = OLD.fingerprint
            AND NEW.purge_category = OLD.purge_category
        );
        resource_code_for_check := 5;
    ELSIF TG_TABLE_NAME = 'logs' THEN
        shape_ok := (
            OLD.purged_at IS NULL AND NEW.purged_at IS NOT NULL
            AND NEW.payload IS NULL
            AND NEW.purge_run_id IS NOT NULL
            AND NEW.purged_by_user_id IS NOT NULL
            AND NEW.log_id = OLD.log_id
            AND NEW.organization_id = OLD.organization_id
            AND NEW.workspace_id IS NOT DISTINCT FROM OLD.workspace_id
            AND NEW.agent_id = OLD.agent_id
            AND NEW.worker_agent_assignment_id IS NOT DISTINCT FROM OLD.worker_agent_assignment_id
            AND NEW.execution_id IS NOT DISTINCT FROM OLD.execution_id
            AND NEW.channel = OLD.channel
            AND NEW.occurred_at = OLD.occurred_at
            AND NEW.received_at = OLD.received_at
            AND NEW.fingerprint = OLD.fingerprint
            AND NEW.purge_category = OLD.purge_category
        );
        resource_code_for_check := 4;
    ELSE
        RAISE EXCEPTION 'trigger de redacao usada em tabela inesperada (%)', TG_TABLE_NAME;
    END IF;

    IF NOT shape_ok THEN
        RAISE EXCEPTION 'atualizacao nao permitida em % (so a transicao de expurgo e aceita)', TG_TABLE_NAME;
    END IF;

    -- Esta trigger roda tanto sob o search_path normal (UPDATE feito por um chamador comum)
    -- quanto dentro do search_path restrito de purge_expired_records (pg_catalog, pg_temp) - o
    -- UPDATE que ela valida acontece DENTRO dessa funcao SECURITY DEFINER, herdando o mesmo
    -- search_path para a duracao da chamada. Toda referencia aqui precisa ser qualificada para
    -- funcionar em ambos os contextos.
    SELECT status INTO day_status FROM public.organization_usage_daily
    WHERE organization_id = NEW.organization_id AND resource_code = resource_code_for_check
      AND usage_date = (NEW.received_at AT TIME ZONE 'UTC')::date;

    IF day_status IS DISTINCT FROM 2 THEN
        RAISE EXCEPTION 'dia ainda nao fechado, expurgo nao autorizado (received_at=%)', NEW.received_at;
    END IF;

    SELECT * INTO rp FROM public.get_retention_policy_at(NEW.organization_id, NEW.purge_category, NEW.received_at);
    IF NOT FOUND OR rp.retention_mode <> 1 OR clock_timestamp() < NEW.received_at + (rp.retention_days * INTERVAL '1 day') THEN
        RAISE EXCEPTION 'prazo de retencao ainda nao vencido para este registro (received_at=%)', NEW.received_at;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER trg_execution_events_prevent_update ON execution_events;
CREATE TRIGGER trg_execution_events_enforce_redaction_or_reject
    BEFORE UPDATE ON execution_events
    FOR EACH ROW
    EXECUTE FUNCTION enforce_redaction_or_reject();

DROP TRIGGER trg_logs_prevent_update ON logs;
CREATE TRIGGER trg_logs_enforce_redaction_or_reject
    BEFORE UPDATE ON logs
    FOR EACH ROW
    EXECUTE FUNCTION enforce_redaction_or_reject();

-- === Restricao de organizacao+categoria unica por transacao de manutencao ===
--
-- Impede, dentro da MESMA transacao, chamar close_usage_day/purge_expired_records para mais
-- de um (organization_id, resource_code/category) - alinhado a decisao documentada na
-- preparacao (nao so a categoria: duas organizacoes da mesma categoria, processadas em ordens
-- opostas por duas transacoes de manutencao distintas, tambem podem formar um ciclo AB-BA
-- classico entre si, independente do cenario de ingestao mista). Rastreado via configuracao
-- transacional (set_config com is_local=true, nunca vaza para outra transacao) - uso interno
-- de bookkeeping dentro de funcoes ja confiaveis, nao um mecanismo de autorizacao externa.
--
-- ESCOPO: so protege quem chama esta funcao ANTES de tentar o lock (por isso close_usage_day/
-- purge_expired_records chamam como primeira instrucao). Nao cobre um UPDATE direto de
-- organization_usage_daily fora dessas duas funcoes (ex.: reconsolidacao de records_count por
-- nekkora_app enquanto o dia ainda esta OPEN) - esse caminho toma o lock de linha equivalente
-- sem passar por aqui. Tampouco impede um chamador com EXECUTE nestas funcoes de redefinir o
-- GUC manualmente antes de chamar. E uma rede de seguranca para os dois caminhos de manutencao
-- desta migration, nao uma garantia do banco contra qualquer transacao futura.
CREATE FUNCTION assert_single_category_per_transaction(p_organization_id UUID, p_resource_or_category INTEGER) RETURNS VOID AS $$
DECLARE
    prior TEXT;
    current_key TEXT;
BEGIN
    current_key := p_organization_id::text || ':' || p_resource_or_category::text;
    -- current_setting(..., true) sobre um GUC customizado que ja existiu nesta sessao (mesmo
    -- que so dentro de uma transacao ja encerrada) volta como '' apos o fim daquela transacao,
    -- nao NULL - so retorna NULL se o GUC nunca foi tocado nesta sessao. Tratar '' como "nenhuma
    -- chave registrada ainda", igual a NULL.
    prior := current_setting('nekkora.retention_txn_category', true);
    IF prior IS NOT NULL AND prior <> '' AND prior <> current_key THEN
        RAISE EXCEPTION 'esta transacao ja processou organizacao/categoria % - fechamento/expurgo nao pode misturar organizacao/categoria na mesma transacao (tentativa atual=%)', prior, current_key;
    END IF;
    PERFORM set_config('nekkora.retention_txn_category', current_key, true);
END;
$$ LANGUAGE plpgsql;

-- === close_usage_day: unica forma de OPEN->CLOSED ===

CREATE FUNCTION close_usage_day(p_organization_id UUID, p_resource_code INTEGER, p_usage_date DATE)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    iso TEXT;
    today DATE;
    computed_records BIGINT;
    computed_bytes BIGINT;
    row_status SMALLINT;
    v_category INTEGER;
BEGIN
    -- resource_code (catalogo de organization_usage_daily/limites) e category (catalogo de
    -- organization_purge_runs/retencao) sao domains numericos distintos para o mesmo recurso
    -- fisico (5=EXECUTION_EVENTS aqui, 1 la; 4=LOGS aqui, 2 la) - o guard de transacao unica
    -- precisa comparar no MESMO domain (category), senao close_usage_day(5) e
    -- purge_expired_records(1) sobre o mesmo recurso seriam vistos como categorias diferentes.
    v_category := CASE p_resource_code
        WHEN 5 THEN 1
        WHEN 4 THEN 2
        ELSE NULL
    END;
    IF v_category IS NULL THEN
        RAISE EXCEPTION 'resource_code invalido para fechamento de consumo diario (%)', p_resource_code;
    END IF;
    PERFORM public.assert_single_category_per_transaction(p_organization_id, v_category);

    iso := current_setting('transaction_isolation');
    IF iso <> 'read committed' THEN
        RAISE EXCEPTION 'fechamento exige transaction_isolation=read committed (atual=%)', iso;
    END IF;

    today := (clock_timestamp() AT TIME ZONE 'UTC')::date;
    IF p_usage_date >= today THEN
        RAISE EXCEPTION 'fechamento so processa dias estritamente passados (usage_date=%, hoje=%)', p_usage_date, today;
    END IF;

    INSERT INTO public.organization_usage_daily (organization_id, resource_code, usage_date, records_count, bytes_count, status, consolidated_at)
    VALUES (p_organization_id, p_resource_code, p_usage_date, 0, 0, 1, NULL)
    ON CONFLICT (organization_id, resource_code, usage_date) DO NOTHING;

    SELECT status INTO row_status FROM public.organization_usage_daily
    WHERE organization_id = p_organization_id AND resource_code = p_resource_code AND usage_date = p_usage_date
    FOR UPDATE;

    IF row_status = 2 THEN
        RAISE EXCEPTION 'dia ja esta fechado (organization_id=%, resource_code=%, usage_date=%)', p_organization_id, p_resource_code, p_usage_date;
    END IF;

    IF p_resource_code = 5 THEN
        SELECT count(*), coalesce(sum(octet_length(payload::text)), 0) INTO computed_records, computed_bytes
        FROM public.execution_events
        WHERE organization_id = p_organization_id AND (received_at AT TIME ZONE 'UTC')::date = p_usage_date;
    ELSE
        SELECT count(*), coalesce(sum(octet_length(payload::text)), 0) INTO computed_records, computed_bytes
        FROM public.logs
        WHERE organization_id = p_organization_id AND (received_at AT TIME ZONE 'UTC')::date = p_usage_date;
    END IF;

    UPDATE public.organization_usage_daily
    SET records_count = computed_records, bytes_count = computed_bytes, status = 2, consolidated_at = clock_timestamp()
    WHERE organization_id = p_organization_id AND resource_code = p_resource_code AND usage_date = p_usage_date;
END;
$$;

-- === purge_expired_records: expurgo em lote limitado, redacao + auditoria na mesma transacao ===
--
-- Nao recebe data-limite manual - elegibilidade e sempre por registro, contra a politica
-- vigente em received_at daquele registro. So processa registros de dias ja CLOSED.

CREATE FUNCTION purge_expired_records(
    p_organization_id UUID,
    p_category INTEGER,
    p_purged_by_user_id UUID,
    p_batch_limit INTEGER
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    run_id UUID;
    affected INTEGER;
BEGIN
    PERFORM public.assert_single_category_per_transaction(p_organization_id, p_category);

    IF p_batch_limit IS NULL OR p_batch_limit <= 0 THEN
        RAISE EXCEPTION 'lote de expurgo deve ser positivo (recebido=%)', p_batch_limit;
    END IF;
    IF p_batch_limit > 1000 THEN
        RAISE EXCEPTION 'lote de expurgo excede o teto permitido (recebido=%, teto=1000)', p_batch_limit;
    END IF;

    IF p_category NOT IN (1, 2) THEN
        RAISE EXCEPTION 'categoria invalida (%)', p_category;
    END IF;

    run_id := gen_random_uuid();

    IF p_category = 1 THEN
        UPDATE public.execution_events e
        SET payload = NULL, purged_at = clock_timestamp(), purged_by_user_id = p_purged_by_user_id, purge_run_id = run_id
        WHERE e.event_id IN (
            SELECT ev.event_id
            FROM public.execution_events ev
            JOIN public.organization_usage_daily d
                ON d.organization_id = ev.organization_id AND d.resource_code = 5
                AND d.usage_date = (ev.received_at AT TIME ZONE 'UTC')::date
            CROSS JOIN LATERAL public.get_retention_policy_at(ev.organization_id, 1, ev.received_at) rp
            WHERE ev.organization_id = p_organization_id
              AND ev.purged_at IS NULL
              AND d.status = 2
              AND rp.retention_mode = 1
              AND clock_timestamp() >= ev.received_at + (rp.retention_days * INTERVAL '1 day')
            ORDER BY ev.received_at
            LIMIT p_batch_limit
            FOR UPDATE OF ev
        );
    ELSE
        UPDATE public.logs l
        SET payload = NULL, purged_at = clock_timestamp(), purged_by_user_id = p_purged_by_user_id, purge_run_id = run_id
        WHERE l.log_id IN (
            SELECT lg.log_id
            FROM public.logs lg
            JOIN public.organization_usage_daily d
                ON d.organization_id = lg.organization_id AND d.resource_code = 4
                AND d.usage_date = (lg.received_at AT TIME ZONE 'UTC')::date
            CROSS JOIN LATERAL public.get_retention_policy_at(lg.organization_id, 2, lg.received_at) rp
            WHERE lg.organization_id = p_organization_id
              AND lg.purged_at IS NULL
              AND d.status = 2
              AND rp.retention_mode = 1
              AND clock_timestamp() >= lg.received_at + (rp.retention_days * INTERVAL '1 day')
            ORDER BY lg.received_at
            LIMIT p_batch_limit
            FOR UPDATE OF lg
        );
    END IF;

    GET DIAGNOSTICS affected = ROW_COUNT;

    INSERT INTO public.organization_purge_runs (purge_run_id, organization_id, category, records_purged, occurred_at, purged_by_user_id)
    VALUES (run_id, p_organization_id, p_category, affected, clock_timestamp(), p_purged_by_user_id);

    RETURN affected;
END;
$$;

-- === Propriedade e privilegios ===
--
-- Proprietario das TABELAS != proprietario das FUNCOES SECURITY DEFINER (ver nota no cabecalho
-- desta migration). nekkora_retention_tables_owner e NOLOGIN, nunca recebe EXECUTE em nada, e
-- existe so para segurar o poder administrativo (ALTER/DROP/trigger) fora do alcance do papel
-- que as funcoes de manutencao realmente assumem em runtime. nekkora_retention_owner (dono das
-- funcoes) recebe so os GRANTs explicitos abaixo - exatamente o que a matriz de privilegios
-- documenta, sem o poder administrativo implicito que viria de ser tambem dono das tabelas.

ALTER TABLE execution_events OWNER TO nekkora_retention_tables_owner;
ALTER TABLE logs OWNER TO nekkora_retention_tables_owner;
ALTER TABLE organization_usage_daily OWNER TO nekkora_retention_tables_owner;
ALTER TABLE organization_purge_runs OWNER TO nekkora_retention_tables_owner;
ALTER FUNCTION close_usage_day(UUID, INTEGER, DATE) OWNER TO nekkora_retention_owner;
ALTER FUNCTION purge_expired_records(UUID, INTEGER, UUID, INTEGER) OWNER TO nekkora_retention_owner;

GRANT SELECT, INSERT ON execution_events TO nekkora_app;
GRANT SELECT, INSERT ON logs TO nekkora_app;
GRANT SELECT, INSERT ON organization_usage_daily TO nekkora_app;
GRANT UPDATE (records_count, bytes_count, consolidated_at) ON organization_usage_daily TO nekkora_app;
GRANT SELECT ON organization_retention_policy_revisions TO nekkora_app;

-- nekkora_retention_owner precisa destes GRANTs explicitos porque NAO e mais proprietario das
-- tabelas (ver nota acima) - sem isso, close_usage_day/purge_expired_records (que rodam com o
-- privilegio deste papel, por serem SECURITY DEFINER) nao conseguiriam nem ler nem escrever.
-- Cada GRANT abaixo e exatamente o que a funcao correspondente exercita, nada mais:
--   - SELECT em execution_events/logs: leitura de agregacao (close_usage_day) e de elegibilidade
--     (purge_expired_records).
--   - UPDATE restrito as colunas de redacao em execution_events/logs: so o que purge_expired_
--     records precisa mudar; a trigger enforce_redaction_or_reject continua validando a forma
--     exata da transicao, independente do privilegio de coluna.
--   - SELECT/INSERT/UPDATE completo em organization_usage_daily: close_usage_day precisa
--     garantir a linha-barreira (INSERT ... ON CONFLICT DO NOTHING) e fechar (UPDATE que muda
--     status, o que nekkora_app nunca pode fazer - ver GRANT de nekkora_app acima, restrito e
--     sem a coluna status).
--   - SELECT/INSERT em organization_purge_runs: leitura (purge_expired_records nao le hoje, mas
--     mantido por simetria com a auditoria) e insercao da linha de auditoria.
GRANT SELECT ON execution_events TO nekkora_retention_owner;
GRANT UPDATE (payload, purged_at, purged_by_user_id, purge_run_id) ON execution_events TO nekkora_retention_owner;
GRANT SELECT ON logs TO nekkora_retention_owner;
GRANT UPDATE (payload, purged_at, purged_by_user_id, purge_run_id) ON logs TO nekkora_retention_owner;
GRANT SELECT, INSERT, UPDATE ON organization_usage_daily TO nekkora_retention_owner;
GRANT SELECT, INSERT ON organization_purge_runs TO nekkora_retention_owner;

-- SECURITY DEFINER checa privilegio contra o PROPRIETARIO da funcao, nao contra quem a chamou -
-- nekkora_retention_owner nao herda acesso a organization_retention_policy_revisions so por ser
-- dono de close_usage_day/purge_expired_records; a tabela foi criada na 010 por outro papel e
-- precisa de GRANT explicito para que get_retention_policy_at() funcione quando chamada de
-- dentro dessas funcoes.
GRANT SELECT ON organization_retention_policy_revisions TO nekkora_retention_owner;

-- As triggers de origem de execution_events/logs (ja existentes desde a 007, nao alteradas
-- em substancia) rodam com o privilegio do CHAMADOR e precisam consultar estas duas tabelas -
-- sem isso nekkora_app nao consegue inserir nenhum evento/log, o que tornaria o papel
-- inutilizavel para a propria finalidade que a 011 pressupoe. Minimo necessario para a
-- ingestao funcionar, nao um levantamento geral de privilegios de 001-010.
GRANT SELECT ON executions TO nekkora_app;
GRANT SELECT ON worker_agent_assignments TO nekkora_app;

REVOKE EXECUTE ON FUNCTION close_usage_day(UUID, INTEGER, DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION close_usage_day(UUID, INTEGER, DATE) TO nekkora_retention_executor;

-- Ativacao deliberadamente NAO concedida nesta migration - depende do contrato de fingerprint
-- (007/008) para sustentar a garantia de reenvio reconhecido apos a redacao do payload.
REVOKE EXECUTE ON FUNCTION purge_expired_records(UUID, INTEGER, UUID, INTEGER) FROM PUBLIC;
