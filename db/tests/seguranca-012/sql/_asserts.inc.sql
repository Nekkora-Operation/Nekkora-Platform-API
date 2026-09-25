-- _asserts.inc.sql - prefixado pelo run.sh a TODO script da suite, na mesma sessao.
-- Toda a suite roda com ON_ERROR_STOP: um resultado inesperado derruba o script e o runner.
--   pg_temp.afirma(condicao, descricao)        - falha se a condicao nao for TRUE (NULL tambem falha).
--   pg_temp.espera_erro(sql, sqlstate, trecho) - executa o sql; falha se ele TIVER SUCESSO, se o
--       SQLSTATE for outro, ou se a mensagem nao contiver o trecho. O erro esperado e desfeito
--       (subtransacao) e registrado como NOTICE, preservando a evidencia na saida.
-- Funcoes em pg_temp: existem so nesta sessao, so sao chamadas qualificadas (pg_temp.*) e nunca
-- entram na resolucao de nomes das funcoes da base. Usam o privilegio TEMP, concedido a PUBLIC por
-- padrao (se TEMP for revogado dos papeis de servico no futuro, este harness precisa de outro meio).
\set ON_ERROR_STOP on
\set VERBOSITY default
SET client_min_messages = notice;

CREATE FUNCTION pg_temp.afirma(p_condicao BOOLEAN, p_descricao TEXT) RETURNS TEXT
LANGUAGE plpgsql AS $f$
BEGIN
    IF p_condicao IS NOT TRUE THEN
        RAISE EXCEPTION 'FALHA DE ASSERCAO: %', p_descricao;
    END IF;
    RETURN 'ok: ' || p_descricao;
END;
$f$;

CREATE FUNCTION pg_temp.espera_erro(p_sql TEXT, p_sqlstate TEXT, p_trecho TEXT) RETURNS TEXT
LANGUAGE plpgsql AS $f$
DECLARE
    v_state TEXT;
    v_msg TEXT;
BEGIN
    BEGIN
        EXECUTE p_sql;
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
    END;
    IF v_state IS NULL THEN
        RAISE EXCEPTION 'FALHA DE ASSERCAO: operacao deveria ter falhado (esperado %), mas teve sucesso: %', p_sqlstate, p_sql;
    END IF;
    IF v_state <> p_sqlstate THEN
        RAISE EXCEPTION 'FALHA DE ASSERCAO: SQLSTATE % (esperado %) - %', v_state, p_sqlstate, v_msg;
    END IF;
    IF position(p_trecho IN v_msg) = 0 THEN
        RAISE EXCEPTION 'FALHA DE ASSERCAO: mensagem sem o trecho "%": %', p_trecho, v_msg;
    END IF;
    RAISE NOTICE 'rejeitado como esperado [%] %', v_state, v_msg;
    RETURN 'ok: rejeitado [' || v_state || ']';
END;
$f$;
