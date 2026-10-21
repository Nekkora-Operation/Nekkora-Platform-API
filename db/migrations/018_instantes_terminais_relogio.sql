-- Nekkora Platform - instantes terminais com o relogio do banco recuado (missao PLATFORM-IDENTIDADE-002, frente 1).
-- Relatorio: docs/relatorios/2026-10-21-recuo-do-relogio-018.md.
--
-- CANDIDATA ate a aprovacao do relatorio da suite db/tests/relogio-018.
--
-- Problema. clock_timestamp() e o relogio de parede do servidor e pode recuar entre duas transacoes. Uma linha criada logo
-- antes do recuo fica com created_at A FRENTE do instante medido pelo ato que a encerra, e a CHECK de ordem temporal
-- (encerramento >= criacao) recusa o ato inteiro com 23514: a acao de seguranca e desfeita. Reproduzido, para as funcoes
-- desta migration, por RecuoDoRelogioIT (nekkora-ops) e RecuoDoRelogioIdentidadeIT (nekkora-platform).
--
-- O que muda (corpo de quatro funcoes e DOIS privilegios de leitura; nenhuma tabela, CHECK, trigger, dono ou politica):
--   * ops_mfa_execute (016): codigos, fator e sessoes do titular encerrados com greatest(v_agora, created_at), como o reset
--     administrativo da Platform (MfaRepository.disableFactor, SessionRepository). executed_at, registro e avisos: v_agora.
--   * identity_mfa_replace (017): fator antigo desabilitado e fator novo confirmado com greatest(v_t, created_at); restricao
--     de recuperacao levantada com greatest(v_t, recovery_used_at). Codigos e sessoes ja usavam greatest na 017.
--   * ops_operator_revoke e ops_operator_disable (016): habilitacao do operador retirada com greatest(v_agora, granted_at)
--     (CHECK revoked_at >= granted_at). Sem isso, o operador mantinha a habilitacao. Certificados e operadores nao tem
--     CHECK de ordem nessas colunas; continuam com v_agora.
--   * privilegios: SELECT (created_at) em user_mfa_factors e user_mfa_recovery_codes para nekkora_ops_mfa_owner (NOLOGIN,
--     dono de ops_mfa_execute). A 016 concede a ele leitura por coluna e nao incluia created_at nessas duas tabelas (em
--     user_sessions ja inclui); sem ela, greatest(v_agora, created_at) falha com 42501 e a execucao inteira e desfeita
--     (medido). Nenhuma escrita nova; nenhum papel de LOGIN muda. nekkora_identity_owner ja le created_at.
-- Corpos copiados da 016 e da 017 sem nenhuma outra alteracao: mesmos locks, na mesma ordem, mesma hora medida depois
-- deles, mesmas reconferencias e recusas, mesma auditoria, mesmos SET de configuracao. 016 e 017 NAO sao editadas.
--
-- Sentido do instante ajustado: com o relogio recuado, o valor gravado e o limite inferior aceito pela CHECK (o created_at
-- ou o recovery_used_at), NAO o instante exato do ato. O encerramento vale (as leituras usam presenca: IS NULL / IS NOT
-- NULL); nenhuma consulta compara esses instantes com o relogio, e nenhuma janela de autenticacao depende deles. Marcas
-- que ABREM janela (mfa_verified_at, recovery_used_at, mfa_step_up_at) nao sao tocadas e continuam recusadas no futuro.
--
-- Fora daqui, de proposito (registrado no relatorio): escritas de CONCLUSAO (confirmacao do primeiro fator, uso de codigo,
-- consumo de token) - o recuo as recusa sem efeito parcial (falha fechada, o ato pode ser repetido); prazos de politica da
-- 016 (24 h entre confirmacao e aprovacao, aceite + 24 h) - ajustar dispensaria a espera exigida.
--
-- Elevacao: as funcoes pertencem a nekkora_ops_mfa_owner e nekkora_identity_owner, papeis sem membership de ninguem
-- (invariantes 9 da 017 e da 016); CREATE OR REPLACE exige ser o dono, e ops_mfa_execute fixa o parametro
-- nekkora.fn_organization_id na propria configuracao. A 018 precisa da janela de SUPERUSER do migrador, como a 016 e a 017
-- (db/bootstrap/aplicar_como_migrador.sh). Sem ela, a primeira recusa medida e "permission denied to set parameter
-- nekkora.fn_organization_id" (42501), e nada muda (suite db/tests/relogio-018, B1).
-- O SUPERUSER preserva dono, ACL, SECURITY DEFINER e configuracao das funcoes substituidas; a secao 3 confere.

BEGIN;

-- === 1. Pre-condicao: 017 aplicada e corpos ainda os da 016/017 (nenhuma substituicao fora do controle de versao) ===
DO $$
BEGIN
    IF to_regprocedure('public.ops_mfa_execute(uuid)') IS NULL OR to_regprocedure('public.identity_mfa_replace(text, uuid, text[])') IS NULL
       OR to_regprocedure('public.ops_operator_revoke(uuid, text)') IS NULL OR to_regprocedure('public.ops_operator_disable(uuid)') IS NULL THEN
        RAISE EXCEPTION '018 exige a 016 e a 017 aplicadas';
    END IF;
    IF (SELECT prosrc FROM pg_proc WHERE oid = 'public.ops_mfa_execute(uuid)'::regprocedure)
           !~ 'UPDATE public\.user_mfa_factors SET disabled_at = v_agora\s'
       OR (SELECT prosrc FROM pg_proc WHERE oid = 'public.identity_mfa_replace(text, uuid, text[])'::regprocedure)
           !~ 'UPDATE public\.identity_mfa_factors SET disabled_at = v_t WHERE factor_id = v_old;'
       OR (SELECT prosrc FROM pg_proc WHERE oid = 'public.ops_operator_revoke(uuid, text)'::regprocedure)
           !~ 'UPDATE ops\.ops_operator_permissions SET revoked_at = v_agora, revoked_by = v_op'
       OR (SELECT prosrc FROM pg_proc WHERE oid = 'public.ops_operator_disable(uuid)'::regprocedure)
           !~ 'UPDATE ops\.ops_operator_permissions SET revoked_at = v_agora, revoked_by = v_op' THEN
        RAISE EXCEPTION '018: corpo de ops_mfa_execute, identity_mfa_replace, ops_operator_revoke ou ops_operator_disable diferente do da 016/017';
    END IF;
END;
$$;

CREATE TEMP TABLE n018_antes ON COMMIT DROP AS
SELECT p.oid, p.proname, pg_get_userbyid(p.proowner) AS dono, p.prosecdef, p.proconfig, p.proacl::text AS acl, p.provolatile,
       obj_description(p.oid, 'pg_proc') AS comentario
FROM pg_proc p
WHERE p.oid IN ('public.ops_mfa_execute(uuid)'::regprocedure, 'public.identity_mfa_replace(text, uuid, text[])'::regprocedure,
                'public.ops_operator_revoke(uuid, text)'::regprocedure, 'public.ops_operator_disable(uuid)'::regprocedure);

-- === 2. Privilegios de leitura do dono operacional (so o necessario ao greatest) ===
GRANT SELECT (created_at) ON public.user_mfa_factors TO nekkora_ops_mfa_owner;
GRANT SELECT (created_at) ON public.user_mfa_recovery_codes TO nekkora_ops_mfa_owner;

-- === 3. Funcoes ===

-- Execucao da recuperacao emergencial (016, contrato secao 7). Unica mudanca: secao 4, instantes terminais.
CREATE OR REPLACE FUNCTION public.ops_mfa_execute(p_request UUID)
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
    v_demais UUID[];
    v_travadas UUID[];
BEGIN
    PERFORM ops.exigir_read_committed();
    SELECT q.organization_id, q.target_user_id INTO v_org, v_target FROM ops.ops_mfa_recovery_requests q WHERE q.request_id = p_request;
    IF NOT FOUND THEN
        SELECT * INTO v_aut FROM ops.autorizar(v_op, 3::smallint, clock_timestamp());
        PERFORM ops.registrar(NULL, NULL, NULL, 'EXECUTAR', 'RECUSA', 'SOLICITACAO_INEXISTENTE', v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, 'SOLICITACAO_INEXISTENTE'::text, p_request, NULL::text;
        RETURN;
    END IF;
    -- 1. locks: organizacao -> contas (alvo e destinatarios dos avisos, crescentes) -> solicitacao -> participantes
    PERFORM set_config('nekkora.fn_organization_id', v_org::text, true);
    PERFORM 1 FROM public.organizations WHERE organization_id = v_org FOR UPDATE;
    v_demais := ARRAY(SELECT ops.demais_admins(v_org, v_target));
    v_travadas := ops.travar_contas(v_org, v_target, v_demais);
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
    IF v_motivo IS NULL AND ops.contas_em_mudanca(v_org, v_target, v_travadas) THEN v_motivo := 'CONTAS_EM_MUDANCA'; END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.registrar(p_request, v_org, v_target, 'EXECUTAR', 'RECUSA', v_motivo, v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, p_request, ops.estado_nome(r.state);
        RETURN;
    END IF;
    -- prazo antes da condicao de aviso: vencida, a solicitacao EXPIRA mesmo que um reenvio tenha alterado a condicao
    IF ops.vencida(r.state, r.opened_at, r.t_total, r.final_approved_at, v_agora) THEN
        PERFORM ops.encerrar(p_request, 8::smallint, 'PRAZO_ENCERRADO', v_op, v_agora);
        PERFORM ops.registrar(p_request, v_org, v_target, 'EXECUTAR', 'RECUSA', 'PRAZO_ENCERRADO', v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, 'PRAZO_ENCERRADO'::text, p_request, 'EXPIRADA'::text;
        RETURN;
    END IF;
    -- item 10: qualquer tentativa criada depois da aprovacao (reenvio a qualquer destinatario, inclusive o titular), ou
    -- versao registrada que deixou de estar aceita, impede executar - recusa do ato, sem reiniciar a confirmacao nem
    -- ampliar prazos (a solicitacao continua APROVADA ate executar ou vencer)
    IF r.notice_seq > r.notice_seq_at_approval
       OR EXISTS (SELECT 1 FROM ops.ops_mfa_approval_attempts a JOIN ops.ops_mfa_notices n ON n.notice_id = a.notice_id
                  WHERE a.request_id = p_request AND n.state <> 4) THEN
        PERFORM ops.registrar(p_request, v_org, v_target, 'EXECUTAR', 'RECUSA', 'CONDICAO_DE_AVISO_ALTERADA', v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, 'CONDICAO_DE_AVISO_ALTERADA'::text, p_request, ops.estado_nome(r.state);
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
    IF v_motivo IS NULL THEN v_motivo := ops.declaracao_invalida(p_request, v_org, v_target); END IF;
    IF v_motivo IS NULL THEN v_motivo := ops.alvo_inelegivel(v_org, v_target); END IF;
    IF v_motivo IS NULL THEN v_motivo := ops.limite_executado(v_org, v_target, v_agora); END IF;
    IF v_motivo IS NOT NULL THEN
        PERFORM ops.encerrar(p_request, 6::smallint, v_motivo, v_op, v_agora);
        PERFORM ops.registrar(p_request, v_org, v_target, 'EXECUTAR', 'RECUSA', v_motivo, v_op, v_aut.o_emissor, v_aut.o_serial);
        RETURN QUERY SELECT 'RECUSA'::text, v_motivo, p_request, 'RECUSADA'::text;
        RETURN;
    END IF;

    -- 4. efeitos (os do reset administrativo). 018: instante terminal = greatest(instante, created_at) nos codigos, no fator e
    --    nas sessoes - com o relogio do banco recuado, o created_at (ajustado para a CHECK; nao e o instante exato).
    --    executed_at, o registro e os avisos continuam com v_agora.
    UPDATE public.auth_challenges SET invalidated_at = v_agora, credential_hash = NULL, pending_password_hash = NULL
     WHERE organization_id = v_org AND user_id = v_target AND consumed_at IS NULL AND invalidated_at IS NULL;
    UPDATE public.user_mfa_recovery_codes SET invalidated_at = greatest(v_agora, created_at)
     WHERE organization_id = v_org AND user_id = v_target AND used_at IS NULL AND invalidated_at IS NULL;
    UPDATE public.user_mfa_factors SET disabled_at = greatest(v_agora, created_at)
     WHERE organization_id = v_org AND user_id = v_target AND disabled_at IS NULL;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    IF v_n <> 1 THEN
        RAISE EXCEPTION 'execucao: fator aprovado nao desabilitado' USING ERRCODE = 'P0001';
    END IF;
    UPDATE public.user_sessions SET revoked_at = greatest(v_agora, created_at)
     WHERE organization_id = v_org AND user_id = v_target AND revoked_at IS NULL;
    INSERT INTO public.mfa_events (organization_id, user_id, event_type, correlation_id, ops_request_id)
    VALUES (v_org, v_target, 4, gen_random_uuid(), p_request);
    -- avisos de execucao ANTES de marcar EXECUTADA (a sequencia da solicitacao nao muda em estado terminal)
    PERFORM ops.criar_avisos(p_request, v_org, v_target, 3::smallint, v_demais);
    UPDATE ops.ops_mfa_recovery_requests SET state = 5, executed_at = v_agora, executed_by = v_op
     WHERE request_id = p_request AND state = 4
    RETURNING request_id INTO v_ok;
    IF v_ok IS NULL THEN
        RAISE EXCEPTION 'execucao: transicao para EXECUTADA falhou' USING ERRCODE = 'P0001';
    END IF;
    PERFORM ops.registrar(p_request, v_org, v_target, 'EXECUTAR', 'OK', NULL, v_op, v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text, p_request, 'EXECUTADA'::text;
END;
$$;

-- T1 (DC-17.13), inteiro e atomico (017). Unica mudanca: instantes do fator antigo, do fator novo e da restricao.
CREATE OR REPLACE FUNCTION public.identity_mfa_replace(p_session_token TEXT, p_new_factor_id UUID, p_code_hashes TEXT[])
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    s RECORD;
    v_old UUID;
    v_t TIMESTAMPTZ;
BEGIN
    PERFORM public.identity_require_read_committed();
    IF NOT public.identity_valid_code_hashes(p_code_hashes) OR p_new_factor_id IS NULL THEN
        RETURN false;
    END IF;
    -- locks: identidade -> sessao -> fator antigo -> fator novo -> codigos vivos -> demais sessoes
    SELECT * INTO s FROM public.identity_session_resolve(p_session_token);
    IF s.o_identity_id IS NULL THEN
        RETURN false;
    END IF;
    SELECT f.factor_id INTO v_old FROM public.identity_mfa_factors f
     WHERE f.identity_id = s.o_identity_id AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL FOR UPDATE;
    IF v_old IS NULL THEN
        RETURN false;
    END IF;
    PERFORM 1 FROM public.identity_mfa_factors f
     WHERE f.factor_id = p_new_factor_id AND f.identity_id = s.o_identity_id AND f.confirmed_at IS NULL AND f.disabled_at IS NULL
       FOR UPDATE;
    IF NOT FOUND THEN
        RETURN false;
    END IF;
    PERFORM 1 FROM public.identity_mfa_recovery_codes r
     WHERE r.identity_id = s.o_identity_id AND r.used_at IS NULL AND r.invalidated_at IS NULL FOR UPDATE;
    PERFORM 1 FROM public.identity_sessions x
     WHERE x.identity_id = s.o_identity_id AND x.identity_session_id <> s.o_session_id AND x.revoked_at IS NULL FOR UPDATE;
    -- R2: hora medida depois de TODOS os locks; a autorizacao (DC-17.13) e reconferida com ela
    v_t := pg_catalog.clock_timestamp();
    IF NOT public.identity_session_proof(s.o_session_id, v_t, 'troca') THEN
        RETURN false;
    END IF;
    -- 018: instantes ajustados as CHECKs da 017 se o relogio do banco recuou (fator antigo, fator novo pendente, marca de
    -- recuperacao); os codigos e as sessoes ja usavam greatest. Ajustado nao e o instante exato do ato.
    UPDATE public.identity_mfa_factors SET disabled_at = greatest(v_t, created_at) WHERE factor_id = v_old;
    UPDATE public.identity_mfa_factors SET confirmed_at = greatest(v_t, created_at) WHERE factor_id = p_new_factor_id;
    UPDATE public.identity_mfa_recovery_codes SET invalidated_at = greatest(v_t, created_at)
     WHERE identity_id = s.o_identity_id AND used_at IS NULL AND invalidated_at IS NULL;
    INSERT INTO public.identity_mfa_recovery_codes (identity_id, factor_id, code_hash)
    SELECT s.o_identity_id, p_new_factor_id, h FROM pg_catalog.unnest(p_code_hashes) h;
    IF s.o_restrita THEN
        UPDATE public.identity_sessions SET recovery_cleared_at = greatest(v_t, recovery_used_at) WHERE identity_session_id = s.o_session_id;
    END IF;
    UPDATE public.identity_sessions SET revoked_at = greatest(v_t, created_at)
     WHERE identity_id = s.o_identity_id AND identity_session_id <> s.o_session_id AND revoked_at IS NULL;
    INSERT INTO public.identity_events (identity_id, event_type, correlation_id) VALUES (s.o_identity_id, 11, pg_catalog.gen_random_uuid());
    RETURN true;
END;
$$;

-- Retirada de habilitacao (016). Unica mudanca: revoked_at da habilitacao.
CREATE OR REPLACE FUNCTION public.ops_operator_revoke(p_operator UUID, p_act TEXT)
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
    -- 018: revoked_at = greatest(instante, granted_at) - ajustado a CHECK da 016 se o relogio recuou; certificados e registro: v_agora
    UPDATE ops.ops_operator_permissions SET revoked_at = greatest(v_agora, granted_at), revoked_by = v_op
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

-- Desativacao de operador (016). Unica mudanca: revoked_at das habilitacoes.
CREATE OR REPLACE FUNCTION public.ops_operator_disable(p_operator UUID)
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
    -- 018: revoked_at = greatest(instante, granted_at) - ajustado a CHECK da 016 se o relogio recuou; demais instantes: v_agora
    UPDATE ops.ops_operator_permissions SET revoked_at = greatest(v_agora, granted_at), revoked_by = v_op
     WHERE operator_id = p_operator AND revoked_at IS NULL;
    FOR v_cert IN UPDATE ops.ops_operator_certificates SET revoked_at = v_agora, revoke_reason = 'OPERADOR_DESATIVADO'
                  WHERE operator_id = p_operator AND revoked_at IS NULL RETURNING certificate_id LOOP
        PERFORM ops.registrar_cadastro('CERTIFICADO_REVOGADO', 'OK', NULL, 1::smallint, v_op, p_operator, NULL, v_cert);
    END LOOP;
    PERFORM ops.registrar_cadastro('OPERADOR_DESATIVADO', 'OK', NULL, 1::smallint, v_op, p_operator, NULL, NULL, NULL, NULL,
                                   v_aut.o_emissor, v_aut.o_serial);
    RETURN QUERY SELECT 'OK'::text, NULL::text;
END;
$$;

-- === 4. Invariantes: dono, ACL, SECURITY DEFINER, configuracao, volatilidade e comentario preservados; corpos com o ajuste ===
DO $$
DECLARE
    v_bad TEXT;
BEGIN
    SELECT string_agg(a.proname, ', ') INTO v_bad
    FROM n018_antes a JOIN pg_proc p ON p.oid = a.oid
    WHERE pg_get_userbyid(p.proowner) IS DISTINCT FROM a.dono OR p.prosecdef IS DISTINCT FROM a.prosecdef
       OR p.proconfig IS DISTINCT FROM a.proconfig OR p.proacl::text IS DISTINCT FROM a.acl OR p.provolatile IS DISTINCT FROM a.provolatile
       OR obj_description(p.oid, 'pg_proc') IS DISTINCT FROM a.comentario;
    IF v_bad IS NOT NULL OR (SELECT count(*) FROM n018_antes) <> 4 THEN
        RAISE EXCEPTION '018: atributos alterados pela substituicao: %', v_bad;
    END IF;
    -- leitura de created_at onde o greatest le; escrita do dono operacional continua so nas colunas terminais da 016
    IF NOT (has_column_privilege('nekkora_ops_mfa_owner', 'public.user_mfa_factors', 'created_at', 'SELECT')
            AND has_column_privilege('nekkora_ops_mfa_owner', 'public.user_mfa_recovery_codes', 'created_at', 'SELECT')
            AND has_column_privilege('nekkora_ops_mfa_owner', 'public.user_sessions', 'created_at', 'SELECT')
            AND has_column_privilege('nekkora_identity_owner', 'public.identity_mfa_factors', 'created_at', 'SELECT')
            AND has_column_privilege('nekkora_identity_owner', 'public.identity_sessions', 'recovery_used_at', 'SELECT'))
       OR has_column_privilege('nekkora_ops_mfa_owner', 'public.user_mfa_factors', 'created_at', 'UPDATE, INSERT, REFERENCES')
       OR has_column_privilege('nekkora_ops_mfa_owner', 'public.user_mfa_recovery_codes', 'created_at', 'UPDATE, INSERT, REFERENCES') THEN
        RAISE EXCEPTION '018: privilegios de created_at divergentes';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_proc WHERE oid IN ('public.ops_operator_revoke(uuid, text)'::regprocedure, 'public.ops_operator_disable(uuid)'::regprocedure)
                 AND pg_get_userbyid(proowner) <> 'nekkora_ops_mfa_owner')
       OR (SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE oid = 'public.ops_mfa_execute(uuid)'::regprocedure) <> 'nekkora_ops_mfa_owner'
       OR (SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE oid = 'public.identity_mfa_replace(text, uuid, text[])'::regprocedure) <> 'nekkora_identity_owner' THEN
        RAISE EXCEPTION '018: dono inesperado';
    END IF;
    -- 016, secao 19: funcao plpgsql do dono operacional comeca exigindo READ COMMITTED
    IF EXISTS (SELECT 1 FROM pg_proc p WHERE p.oid IN ('public.ops_mfa_execute(uuid)'::regprocedure, 'public.ops_operator_revoke(uuid, text)'::regprocedure,
                                                      'public.ops_operator_disable(uuid)'::regprocedure)
                 AND substring(p.prosrc FROM '\mBEGIN\M.*') !~ '^BEGIN\s+PERFORM ops\.exigir_read_committed\(\);') THEN
        RAISE EXCEPTION '018: funcao operacional sem a exigencia de READ COMMITTED no inicio';
    END IF;
    IF (SELECT count(*) FROM regexp_matches((SELECT prosrc FROM pg_proc WHERE oid = 'public.ops_mfa_execute(uuid)'::regprocedure),
                                            'greatest\(v_agora, created_at\)', 'g')) <> 3
       OR (SELECT count(*) FROM regexp_matches((SELECT prosrc FROM pg_proc WHERE oid = 'public.identity_mfa_replace(text, uuid, text[])'::regprocedure),
                                               'greatest\(v_t, (created_at|recovery_used_at)\)', 'g')) <> 5
       OR (SELECT count(*) FROM regexp_matches((SELECT prosrc FROM pg_proc WHERE oid = 'public.ops_operator_revoke(uuid, text)'::regprocedure),
                                               'revoked_at = greatest\(v_agora, granted_at\)', 'g')) <> 1
       OR (SELECT count(*) FROM regexp_matches((SELECT prosrc FROM pg_proc WHERE oid = 'public.ops_operator_disable(uuid)'::regprocedure),
                                               'revoked_at = greatest\(v_agora, granted_at\)', 'g')) <> 1 THEN
        RAISE EXCEPTION '018: ajuste dos instantes terminais ausente';
    END IF;
END;
$$;

COMMIT;
