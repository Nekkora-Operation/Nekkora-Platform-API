-- Nekkora Platform - retencao, no servidor, dos registros e dos arquivos de resultado das execucoes remotas
-- (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, fechamento). Contrato: docs/platform-client-execucao-remota-001/retencao-no-servidor.md.
--
-- CANDIDATA ate a aprovacao do PR. Depende da 050, 051, 052, 053 e 055. Numero 057 conferido em 16/11 contra a dev e os PRs
-- abertos.
--
-- DECISAO DA TITULAR (16/11)
--   - Registros e arquivos de resultado: 30 dias, configuraveis, contados da CONCLUSAO DA ENTREGA ou do ENCERRAMENTO EXPLICITO
--     dessa entrega.
--   - Historico minimo da execucao e auditoria: FORA desta exclusao automatica.
--   - A limpeza registra o que venceu, remove o conteudo, confirma a exclusao e pode ser repetida com seguranca.
--   - Isto nao autoriza elevacao nem aplicacao em producao.
--
-- POR QUE NAO O MECANISMO DA 010/011/026 (avaliado; ver o documento): ele e por DIA de recebimento, com barreira e fechamento
-- do dia para a contagem de consumo, exige politica por organizacao e a janela de SUPERUSER para qualquer mudanca (os donos sao
-- papeis proprios), e o EXECUTE do expurgo nunca foi concedido. Aqui o prazo conta por TENTATIVA, a partir do fim da entrega, e
-- ha bytes fora do banco. Reaproveitamos dele o PRINCIPIO: o conteudo e apagado, a linha fica; e ha um registro do que venceu.
-- As 050 e 051 ja nasceram preparadas para isso (redacted_at e removed_at, com gatilhos que so aceitam essa mudanca).
--
-- O QUE FAZ
--   1. remote_attempt_delivery_ended_at(organizacao, tentativa, tipo): QUANDO a entrega daquele tipo (1 = registros,
--      2 = resultados) terminou, e como:
--        1 = CLOSED       a maquina fechou o envio (050/051) - entrega concluida
--        2 = LOSS_NOTICE  a maquina avisou que perdeu o que guardava, pelo prazo dela (052) - encerramento explicito
--        3 = LINK_ENDED   a tentativa terminou e o vinculo sob o qual ela rodou foi encerrado: a maquina nao tem mais como
--                         enviar (053) - encerramento explicito
--      Nulo: a entrega continua aberta, e NADA vence.
--   2. remote_attempt_content_expirations: o registro do que venceu - uma linha por tentativa e tipo, com o fim da entrega, o
--      prazo aplicado, quantos itens e bytes havia, quando venceu e quando a remocao foi CONFIRMADA. O banco CONFERE o fato
--      (entrega encerrada + prazo vencido) e calcula ele mesmo as datas e as contagens: a aplicacao so informa o prazo em dias.
--   3. O texto de uma linha so pode ser apagado, e um arquivo so pode ser marcado como removido, se o vencimento daquela
--      tentativa e tipo estiver REGISTRADO e ainda nao confirmado. A confirmacao so e aceita quando nao sobra conteudo.
--   4. Depois do vencimento registrado, nada novo daquele tipo e aceito para a tentativa.
--   5. Privilegios minimos para a Platform (nekkora_platform): gravar o vencimento, apagar o texto de linha, marcar arquivo
--      removido, confirmar. O canal das maquinas (nekkora_ingestion) so le o registro do vencimento.
--
-- O QUE FICA PARA SEMPRE (historico minimo e auditoria): a solicitacao e a tentativa (estados, datas, causa do encerramento,
-- codigo de saida, relato tardio); os fechamentos e os avisos de perda; de cada linha, a sequencia, o instante, o canal e o
-- corte; de cada arquivo, o nome, o tamanho e o SHA-256; a trilha de quem baixou; e o proprio registro do vencimento.
--
-- O QUE NAO FAZ: nao apaga linha de tabela nenhuma (nenhum DELETE); nao remove bytes (quem remove e o componente da Platform com
-- acesso ao armazenamento de resultados, depois de o vencimento estar registrado aqui); nao define o prazo (configuracao da
-- aplicacao; o banco guarda o piso de 1 dia e o teto de 3650).
--
-- DADOS EXISTENTES: nenhuma linha e alterada.
--
-- REVERSAO (manual; so enquanto remote_attempt_content_expirations estiver vazia):
--   BEGIN;
--   DROP TRIGGER trg_remote_attempt_log_lines_expiration_guard ON public.remote_attempt_log_lines;
--   DROP TRIGGER trg_remote_attempt_log_lines_after_expiration ON public.remote_attempt_log_lines;
--   DROP TRIGGER trg_remote_attempt_results_expiration_guard ON public.remote_attempt_results;
--   DROP TRIGGER trg_remote_attempt_results_after_expiration ON public.remote_attempt_results;
--   REVOKE UPDATE (message, redacted_at) ON public.remote_attempt_log_lines FROM nekkora_platform;
--   REVOKE UPDATE (removed_at) ON public.remote_attempt_results FROM nekkora_platform;
--   DROP TABLE public.remote_attempt_content_expirations;
--   DROP FUNCTION public.remote_attempt_content_expiration_on_insert();
--   DROP FUNCTION public.remote_attempt_content_expiration_on_update();
--   DROP FUNCTION public.remote_attempt_content_expiration_guard();
--   DROP FUNCTION public.remote_attempt_content_after_expiration();
--   DROP FUNCTION public.remote_attempt_delivery_ended_at(uuid, uuid, smallint);
--   COMMIT;
--
-- SEM ELEVACAO: tabela e funcoes novas, gatilhos SECURITY INVOKER, concessoes em tabelas do proprio dono. Nenhum papel novo,
-- nenhuma funcao SECURITY DEFINER.

BEGIN;

-- === 1. Pre-condicao ===
DO $$
BEGIN
    IF to_regclass('public.remote_attempt_results') IS NULL OR to_regclass('public.remote_attempt_log_lines') IS NULL
       OR to_regclass('public.remote_attempt_retention_losses') IS NULL OR to_regclass('public.remote_execution_organizations') IS NULL THEN
        RAISE EXCEPTION '057 exige a 050, a 051, a 052 e a 055 aplicadas';
    END IF;
    IF to_regclass('public.remote_attempt_content_expirations') IS NOT NULL THEN
        RAISE EXCEPTION '057: remote_attempt_content_expirations ja existe';
    END IF;
END;
$$;

-- === 2. Quando a entrega terminou ===
CREATE FUNCTION public.remote_attempt_delivery_ended_at(p_organization UUID, p_attempt UUID, p_kind SMALLINT,
                                                        OUT ended_at TIMESTAMPTZ, OUT how SMALLINT)
LANGUAGE plpgsql STABLE SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    t RECORD;
    link_end TIMESTAMPTZ;
BEGIN
    -- 1. a maquina fechou o envio: entrega concluida
    IF p_kind = 1 THEN
        SELECT c.closed_at INTO ended_at FROM public.remote_attempt_log_closures c WHERE c.organization_id = p_organization AND c.attempt_id = p_attempt;
    ELSIF p_kind = 2 THEN
        SELECT c.closed_at INTO ended_at FROM public.remote_attempt_result_closures c WHERE c.organization_id = p_organization AND c.attempt_id = p_attempt;
    ELSE
        RETURN;
    END IF;
    IF ended_at IS NOT NULL THEN
        how := 1;
        RETURN;
    END IF;
    -- 2. a maquina avisou que perdeu o que guardava (052): nao ha mais o que entregar
    SELECT l.reported_at INTO ended_at FROM public.remote_attempt_retention_losses l
     WHERE l.organization_id = p_organization AND l.attempt_id = p_attempt AND l.kind = p_kind;
    IF ended_at IS NOT NULL THEN
        how := 2;
        RETURN;
    END IF;
    -- 3. a tentativa terminou e o vinculo sob o qual ela rodou foi encerrado: a maquina nao tem mais como enviar
    SELECT a.ended_at, a.created_at, a.agent_id, a.worker_id INTO t FROM public.remote_attempts a
     WHERE a.organization_id = p_organization AND a.attempt_id = p_attempt AND a.state >= 7;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    SELECT wa.ended_at INTO link_end FROM public.worker_agent_assignments wa
     WHERE wa.organization_id = p_organization AND wa.agent_id = t.agent_id AND wa.worker_id = t.worker_id AND wa.created_at <= t.created_at
     ORDER BY wa.created_at DESC LIMIT 1;
    IF link_end IS NULL THEN
        SELECT g.updated_at INTO link_end FROM public.agents g WHERE g.organization_id = p_organization AND g.agent_id = t.agent_id AND g.status = 4;
    END IF;
    IF link_end IS NOT NULL THEN
        ended_at := greatest(link_end, t.ended_at);
        how := 3;
    END IF;
END;
$$;
COMMENT ON FUNCTION public.remote_attempt_delivery_ended_at(UUID, UUID, SMALLINT) IS
    'Quando a entrega dos registros (1) ou dos resultados (2) de uma tentativa terminou, e como: 1=CLOSED, 2=LOSS_NOTICE, 3=LINK_ENDED. '
    'Nulo: a entrega continua aberta e nada vence (057).';

-- === 3. O registro do que venceu ===
CREATE TABLE public.remote_attempt_content_expirations (
    attempt_id         UUID NOT NULL,
    kind               SMALLINT NOT NULL CHECK (kind IN (1, 2)),
    organization_id    UUID NOT NULL,
    delivery_ended_at  TIMESTAMPTZ NOT NULL,
    delivery_end       SMALLINT NOT NULL CHECK (delivery_end IN (1, 2, 3)),
    retention_days     INTEGER NOT NULL CHECK (retention_days BETWEEN 1 AND 3650),
    items              INTEGER NOT NULL CHECK (items BETWEEN 0 AND 50000),
    bytes              BIGINT NOT NULL CHECK (bytes >= 0),
    expired_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    confirmed_at       TIMESTAMPTZ,
    PRIMARY KEY (attempt_id, kind),
    FOREIGN KEY (organization_id, attempt_id) REFERENCES public.remote_attempts (organization_id, attempt_id) ON DELETE RESTRICT,
    CHECK (confirmed_at IS NULL OR confirmed_at >= expired_at)
);
CREATE INDEX ix_remote_attempt_content_expirations_open ON public.remote_attempt_content_expirations (organization_id, expired_at)
    WHERE confirmed_at IS NULL;
COMMENT ON TABLE public.remote_attempt_content_expirations IS
    'O que venceu pela retencao do servidor (057): por tentativa e tipo (1=registros, 2=resultados), o fim da entrega, o prazo aplicado, '
    'quantos itens e bytes havia, quando venceu e quando a remocao do conteudo foi confirmada. Nunca apagado.';
COMMENT ON COLUMN public.remote_attempt_content_expirations.delivery_end IS '1=CLOSED (entrega concluida), 2=LOSS_NOTICE, 3=LINK_ENDED (encerramento explicito)';
COMMENT ON COLUMN public.remote_attempt_content_expirations.confirmed_at IS 'Nulo: a remocao ainda nao terminou (a limpeza repete ate confirmar)';

CREATE FUNCTION public.remote_attempt_content_expiration_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    e RECORD;
BEGIN
    NEW.expired_at := clock_timestamp();
    IF NEW.confirmed_at IS NOT NULL THEN
        RAISE EXCEPTION 'o vencimento nasce sem confirmacao' USING ERRCODE = '23514';
    END IF;
    IF NEW.retention_days IS NULL OR NEW.retention_days < 1 THEN
        RAISE EXCEPTION 'prazo de retencao invalido' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO e FROM public.remote_attempt_delivery_ended_at(NEW.organization_id, NEW.attempt_id, NEW.kind);
    IF e.ended_at IS NULL THEN
        RAISE EXCEPTION 'a entrega nao foi concluida nem encerrada: nada vence' USING ERRCODE = '23514';
    END IF;
    IF e.ended_at + make_interval(days => NEW.retention_days) > NEW.expired_at THEN
        RAISE EXCEPTION 'o prazo de retencao ainda nao venceu' USING ERRCODE = '23514';
    END IF;
    -- as datas e as contagens sao as do banco, nao as de quem insere
    NEW.delivery_ended_at := e.ended_at;
    NEW.delivery_end := e.how;
    IF NEW.kind = 1 THEN
        SELECT count(*), coalesce(sum(octet_length(l.message)), 0) INTO NEW.items, NEW.bytes FROM public.remote_attempt_log_lines l
         WHERE l.organization_id = NEW.organization_id AND l.attempt_id = NEW.attempt_id AND l.redacted_at IS NULL;
    ELSE
        SELECT count(*), coalesce(sum(r.size_bytes), 0) INTO NEW.items, NEW.bytes FROM public.remote_attempt_results r
         WHERE r.organization_id = NEW.organization_id AND r.attempt_id = NEW.attempt_id AND r.removed_at IS NULL;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempt_content_expirations_on_insert BEFORE INSERT ON public.remote_attempt_content_expirations
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_content_expiration_on_insert();

-- A unica mudanca: confirmar a remocao, uma vez, e so quando nao sobra conteudo.
CREATE FUNCTION public.remote_attempt_content_expiration_on_update() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF (to_jsonb(NEW) - 'confirmed_at') IS DISTINCT FROM (to_jsonb(OLD) - 'confirmed_at') OR OLD.confirmed_at IS NOT NULL OR NEW.confirmed_at IS NULL THEN
        RAISE EXCEPTION 'o vencimento so muda para confirmar a remocao, uma vez' USING ERRCODE = '42501';
    END IF;
    IF (OLD.kind = 1 AND EXISTS (SELECT 1 FROM public.remote_attempt_log_lines l
                                  WHERE l.organization_id = OLD.organization_id AND l.attempt_id = OLD.attempt_id AND l.redacted_at IS NULL))
       OR (OLD.kind = 2 AND EXISTS (SELECT 1 FROM public.remote_attempt_results r
                                     WHERE r.organization_id = OLD.organization_id AND r.attempt_id = OLD.attempt_id AND r.removed_at IS NULL)) THEN
        RAISE EXCEPTION 'ainda ha conteudo: a remocao nao pode ser confirmada' USING ERRCODE = '23514';
    END IF;
    NEW.confirmed_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempt_content_expirations_on_update BEFORE UPDATE ON public.remote_attempt_content_expirations
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_content_expiration_on_update();
CREATE TRIGGER trg_remote_attempt_content_expirations_prevent_delete BEFORE DELETE ON public.remote_attempt_content_expirations
    FOR EACH ROW EXECUTE FUNCTION public.prevent_delete();
CREATE TRIGGER trg_remote_attempt_content_expirations_prevent_truncate BEFORE TRUNCATE ON public.remote_attempt_content_expirations
    FOR EACH STATEMENT EXECUTE FUNCTION public.prevent_truncate();

-- === 4. O conteudo so sai com o vencimento registrado; depois dele, nada novo entra ===
CREATE FUNCTION public.remote_attempt_content_expiration_guard() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.remote_attempt_content_expirations x
                    WHERE x.organization_id = OLD.organization_id AND x.attempt_id = OLD.attempt_id AND x.kind = TG_ARGV[0]::smallint
                      AND x.confirmed_at IS NULL) THEN
        RAISE EXCEPTION 'conteudo so e removido com o vencimento registrado e nao confirmado' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;
CREATE FUNCTION public.remote_attempt_content_after_expiration() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.remote_attempt_content_expirations x
                WHERE x.organization_id = NEW.organization_id AND x.attempt_id = NEW.attempt_id AND x.kind = TG_ARGV[0]::smallint) THEN
        RAISE EXCEPTION 'o conteudo desta tentativa ja venceu pela retencao' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_remote_attempt_log_lines_expiration_guard BEFORE UPDATE ON public.remote_attempt_log_lines
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_content_expiration_guard('1');
CREATE TRIGGER trg_remote_attempt_log_lines_after_expiration BEFORE INSERT ON public.remote_attempt_log_lines
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_content_after_expiration('1');
CREATE TRIGGER trg_remote_attempt_results_expiration_guard BEFORE UPDATE ON public.remote_attempt_results
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_content_expiration_guard('2');
CREATE TRIGGER trg_remote_attempt_results_after_expiration BEFORE INSERT ON public.remote_attempt_results
    FOR EACH ROW EXECUTE FUNCTION public.remote_attempt_content_after_expiration('2');

-- === 5. RLS ===
ALTER TABLE public.remote_attempt_content_expirations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.remote_attempt_content_expirations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_request_context ON public.remote_attempt_content_expirations AS PERMISSIVE FOR ALL TO nekkora_platform, nekkora_ingestion
    USING (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(pg_catalog.current_setting('nekkora.organization_id', true), '')::uuid);

-- === 6. Privilegios ===
-- Platform: registra o vencimento, apaga o texto, marca o arquivo removido, confirma. Maquina: so le o vencimento (para a recusa
-- do que chegar depois). Ninguem apaga linha de tabela.
REVOKE ALL ON public.remote_attempt_content_expirations FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_delivery_ended_at(UUID, UUID, SMALLINT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_content_expiration_on_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_content_expiration_on_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_content_expiration_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.remote_attempt_content_after_expiration() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.remote_attempt_delivery_ended_at(UUID, UUID, SMALLINT) TO nekkora_platform;
GRANT SELECT, INSERT ON public.remote_attempt_content_expirations TO nekkora_platform;
GRANT UPDATE (confirmed_at) ON public.remote_attempt_content_expirations TO nekkora_platform;
GRANT SELECT ON public.remote_attempt_content_expirations TO nekkora_ingestion;
GRANT UPDATE (message, redacted_at) ON public.remote_attempt_log_lines TO nekkora_platform;
GRANT UPDATE (removed_at) ON public.remote_attempt_results TO nekkora_platform;

-- === 7. Invariantes ===
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = 'public.remote_attempt_content_expirations'::regclass AND c.relrowsecurity AND c.relforcerowsecurity) THEN
        RAISE EXCEPTION '057: RLS ausente';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.remote_attempt_content_expirations', 'DELETE')
       OR has_table_privilege('nekkora_ingestion', 'public.remote_attempt_content_expirations', 'INSERT, UPDATE, DELETE')
       OR has_column_privilege('nekkora_platform', 'public.remote_attempt_content_expirations', 'retention_days', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.remote_attempt_log_lines', 'INSERT, DELETE')
       OR has_table_privilege('nekkora_platform', 'public.remote_attempt_results', 'INSERT, DELETE')
       OR has_column_privilege('nekkora_platform', 'public.remote_attempt_log_lines', 'sequence', 'UPDATE')
       OR has_column_privilege('nekkora_platform', 'public.remote_attempt_results', 'sha256', 'UPDATE')
       OR has_table_privilege('nekkora_ingestion', 'public.remote_attempt_log_lines', 'UPDATE, DELETE')
       OR has_table_privilege('nekkora_ingestion', 'public.remote_attempt_results', 'UPDATE, DELETE') THEN
        RAISE EXCEPTION '057: privilegio alem do previsto na retencao';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_proc p WHERE p.pronamespace = 'public'::regnamespace AND p.proname LIKE 'remote_attempt_%' AND p.prosecdef) THEN
        RAISE EXCEPTION '057: funcao SECURITY DEFINER fora do previsto';
    END IF;
END;
$$;

COMMIT;
