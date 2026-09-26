-- MUTACAO m1b (teste do runner; NUNCA numa base real) - variante que confia no nekkora.fn_organization_id
-- definido pelo CHAMADOR: portal_list_workspaces so define o contexto interno se o chamador nao o fez.
-- Com o GUC apontando para O2 e a rota O1, o resultado muda (a funcao deixa de ser dona do contexto).
-- O t03 PRECISA falhar.
DO $$
DECLARE
    d TEXT := pg_get_functiondef('public.portal_list_workspaces(uuid,text)'::regprocedure);
    ancora CONSTANT TEXT := $a$PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(p_organization_id::text, ''), true);$a$;
BEGIN
    IF (length(d) - length(replace(d, ancora, ''))) / length(ancora) <> 1 THEN
        RAISE EXCEPTION 'mutacao m1b: ancora nao unica';
    END IF;
    d := replace(d, ancora, $a$PERFORM pg_catalog.set_config('nekkora.fn_organization_id', coalesce(nullif(v_prev_fn_org, ''), p_organization_id::text, ''), true); -- MUTACAO m1b$a$);
    EXECUTE d;
END $$;
SELECT 'mutacao m1b aplicada' AS resultado, position('MUTACAO m1b' IN prosrc) > 0 AS presente
FROM pg_proc WHERE oid = 'public.portal_list_workspaces(uuid,text)'::regprocedure;
