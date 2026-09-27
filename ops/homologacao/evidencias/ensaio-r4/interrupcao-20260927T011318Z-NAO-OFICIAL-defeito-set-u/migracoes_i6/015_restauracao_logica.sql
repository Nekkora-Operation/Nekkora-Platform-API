-- Nekkora Platform - restauracao logica (pg_dump/pg_restore) da base: correcao de search_path.
--
-- Achado do ensaio de atualizacao populada (docs/relatorios/2026-09-26-validacao-api-v1.md, §8.1): o pg_restore
-- executa o COPY com search_path vazio. O CHECK de automation_versions.command_json e de
-- executions.command_snapshot chama is_valid_command_contract (005, LANGUAGE sql, sem SET search_path), que chama
-- is_valid_relative_entrypoint SEM schema; com search_path vazio a funcao nao e encontrada e a restauracao falha.
--
-- Escopo RESTRITO (autorizado em 2026-09-26): fixar o search_path da unica funcao executada durante a
-- restauracao que chama outra funcao do schema sem qualificar. As demais funcoes executadas no restore
-- (is_valid_relative_entrypoint, is_valid_event_payload, is_valid_log_payload, is_valid_cron_expression) so chamam
-- funcoes nativas e nao mudam. O corpo da funcao NAO muda; nenhuma migration anterior e alterada.
--
-- O pg_dump passa a gravar "SET search_path TO 'pg_catalog', 'public'" na definicao da funcao, e o CHECK
-- encontra is_valid_relative_entrypoint mesmo com o search_path vazio da restauracao.
--
-- Limite: vale para backups gerados DEPOIS desta migration. Um backup antigo ainda contem a funcao sem
-- search_path; restaura-lo exige o procedimento proprio descrito em db/tests/restauracao-015/README.md
-- (restaurar pre-data, aplicar este mesmo ALTER, depois data e post-data). Aplicar a 015 depois de uma
-- restauracao que ja falhou nao a conserta.
--
-- Efeito colateral conhecido: funcao SQL com SET nao e mais expandida inline pelo planejador. Ela so e usada em
-- CHECK de linha (avaliada por linha escrita), sem uso em consulta.
--
-- Depende de 001-014. Atomica: uma transacao.

BEGIN;

ALTER FUNCTION public.is_valid_command_contract(jsonb) SET search_path = pg_catalog, public;

-- === Invariantes ===
DO $$
DECLARE
    v_bad TEXT;
BEGIN
    IF (SELECT proconfig FROM pg_proc WHERE oid = 'public.is_valid_command_contract(jsonb)'::regprocedure)
       IS DISTINCT FROM ARRAY['search_path=pg_catalog, public'] THEN
        RAISE EXCEPTION 'is_valid_command_contract sem o search_path esperado';
    END IF;

    -- Nenhuma funcao chamada por CHECK do schema public pode chamar outra funcao do schema sem qualificar e sem
    -- search_path proprio (o pg_restore avalia CHECK com search_path vazio).
    WITH fn AS (
        SELECT p.oid, p.proname, p.prosrc, p.proconfig
        FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public'
    ), raiz AS (
        SELECT DISTINCT f.oid FROM fn f JOIN pg_constraint c ON c.contype = 'c'
        JOIN pg_namespace n ON n.oid = c.connamespace AND n.nspname = 'public'
        WHERE pg_get_constraintdef(c.oid) ~ ('(^|[^A-Za-z0-9_.])(public\.)?' || f.proname || '\(')
    )
    SELECT string_agg(f.proname, ', ') INTO v_bad
    FROM raiz r JOIN fn f ON f.oid = r.oid
    WHERE NOT coalesce(array_to_string(f.proconfig, ';') LIKE '%search_path=%', false)
      AND EXISTS (SELECT 1 FROM fn g WHERE g.oid <> f.oid AND f.prosrc ~ ('(^|[^A-Za-z0-9_.])' || g.proname || '\('));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'funcao de CHECK chama funcao do schema sem qualificar e sem search_path: %', v_bad;
    END IF;
END
$$;

COMMIT;
