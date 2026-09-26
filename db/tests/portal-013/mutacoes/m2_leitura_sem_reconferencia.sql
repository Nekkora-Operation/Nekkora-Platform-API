-- MUTACAO m2 (teste do runner; NUNCA numa base real) - comando de leitura SEM a reconferencia de conta,
-- vinculo, cliente e organizacao: portal_authorized_workspaces passa a olhar so a concessao e o
-- workspace. Uma suspensao confirmada entre a resolucao e a leitura deixa de zerar o resultado. O
-- bloco 7(c) PRECISA falhar (conta, vinculo, cliente, organizacao).
DO $$
DECLARE
    d TEXT := pg_get_functiondef('public.portal_authorized_workspaces(uuid,uuid,uuid,timestamptz)'::regprocedure);
    trechos CONSTANT TEXT[] := ARRAY[
        E'       AND u.account_type = 2 AND u.status = 2 AND u.email_verified_at IS NOT NULL\n',
        E'       AND o.status = 1\n',
        E' AND m.status = 2\n',
        E'       AND c.status = 1\n'];
    t TEXT;
BEGIN
    FOREACH t IN ARRAY trechos LOOP
        IF (length(d) - length(replace(d, t, ''))) / length(t) <> 1 THEN
            RAISE EXCEPTION 'mutacao m2: trecho nao unico: %', t;
        END IF;
        d := replace(d, t, CASE WHEN t = E' AND m.status = 2\n' THEN E'\n' ELSE '' END);
    END LOOP;
    d := replace(d, E'SELECT g.workspace_id\n', E'SELECT g.workspace_id -- MUTACAO m2\n');
    EXECUTE d;
END $$;
SELECT 'mutacao m2 aplicada' AS resultado, position('MUTACAO m2' IN prosrc) > 0 AS presente, prosrc !~ 'u\.status = 2' AS sem_conta
FROM pg_proc WHERE oid = 'public.portal_authorized_workspaces(uuid,uuid,uuid,timestamptz)'::regprocedure;
