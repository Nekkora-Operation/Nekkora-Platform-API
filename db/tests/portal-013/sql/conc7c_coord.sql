-- conc7c (coordenador) - papel: nekkora (superusuario; so controla a pausa e observa, nao le dados de
-- negocio). Teste 7(c): obtem o advisory lock do harness; espera a chamada do portal (variante de pausa)
-- ficar bloqueada nele - ou seja, JA passou pela resolucao da sessao; registra a evidencia; espera a
-- mudanca de estado confirmada pelo nekkora_platform (condicao :cond) e so entao libera a leitura.
\pset pager off
SELECT pg_advisory_lock(820713);
\echo MARCADOR_LOCK_OBTIDO
DO $$
DECLARE i INTEGER := 0;
BEGIN
    LOOP
        -- pg_stat_activity fica em cache durante a transacao: sem limpar, o laco nunca veria o portal chegar
        PERFORM pg_stat_clear_snapshot();
        EXIT WHEN EXISTS (SELECT 1 FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
                          WHERE l.locktype = 'advisory' AND l.objid = 820713 AND NOT l.granted AND a.usename = 'nekkora_portal');
        i := i + 1;
        IF i > 150 THEN RAISE EXCEPTION 'FALHA DE ASSERCAO: a chamada do portal nao chegou ao ponto de pausa em 30 s'; END IF;
        PERFORM pg_sleep(0.2);
    END LOOP;
END $$;
SELECT clock_timestamp() AS c_observacao, a.pid, a.usename, a.state, a.wait_event_type, a.wait_event, pg_blocking_pids(a.pid) AS bloqueado_por
FROM pg_stat_activity a WHERE a.usename = 'nekkora_portal' AND a.state <> 'idle';
SELECT pg_temp.afirma(EXISTS (SELECT 1 FROM pg_stat_activity a
    WHERE a.usename = 'nekkora_portal' AND a.wait_event_type = 'Lock' AND a.wait_event = 'advisory' AND pg_backend_pid() = ANY (pg_blocking_pids(a.pid))),
  'portal parado no ponto de pausa (depois da resolucao), bloqueado pelo coordenador');
SELECT pg_temp.afirma(EXISTS (SELECT 1 FROM pg_stat_activity a
    WHERE a.usename = 'nekkora_portal' AND a.wait_event = 'advisory' AND a.backend_xid IS NOT NULL),
  'a transacao pausada do portal ja tem xid (FOR UPDATE/toque da sessao feitos na resolucao)');
\echo MARCADOR_PORTAL_PAUSADO
SELECT set_config('teste.cond', :'cond', false) IS NOT NULL AS cond_ok;
DO $$
DECLARE i INTEGER := 0; v BOOLEAN;
BEGIN
    LOOP
        EXECUTE 'SELECT (' || current_setting('teste.cond') || ')' INTO v;
        EXIT WHEN v;
        i := i + 1;
        IF i > 150 THEN RAISE EXCEPTION 'FALHA DE ASSERCAO: a mudanca de estado nao foi confirmada em 30 s'; END IF;
        PERFORM pg_sleep(0.2);
    END LOOP;
END $$;
\echo MARCADOR_MUDANCA_CONFIRMADA
SELECT pg_advisory_unlock(820713) AS liberado;
