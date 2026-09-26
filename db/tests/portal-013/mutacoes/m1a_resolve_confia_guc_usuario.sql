-- MUTACAO m1a (teste do runner; NUNCA numa base real) - variante DELIBERADAMENTE VULNERAVEL para o teste 3:
-- portal_resolve_session passa a aceitar a identidade vinda de um GUC do cliente (nekkora.user_id),
-- como na alternativa 1 rejeitada do desenho. Com o token de X e nekkora.user_id = Y, a leitura passa a
-- devolver o escopo de Y (WS2). O t03 PRECISA falhar ("identico ao baseline").
-- Derivada do corpo instalado (pg_get_functiondef) por uma unica insercao, conferida.
DO $$
DECLARE
    d TEXT := pg_get_functiondef('public.portal_resolve_session(uuid,text)'::regprocedure);
    ancora CONSTANT TEXT := E'    SELECT m.client_membership_id\n      INTO o_client_membership_id';
BEGIN
    IF (length(d) - length(replace(d, ancora, ''))) / length(ancora) <> 1 THEN
        RAISE EXCEPTION 'mutacao m1a: ancora nao unica';
    END IF;
    d := replace(d, ancora, E'    o_user_id := coalesce(nullif(pg_catalog.current_setting(''nekkora.user_id'', true), '''')::uuid, o_user_id); -- MUTACAO m1a\n' || ancora);
    EXECUTE d;
END $$;
SELECT 'mutacao m1a aplicada' AS resultado, position('MUTACAO m1a' IN prosrc) > 0 AS presente
FROM pg_proc WHERE oid = 'public.portal_resolve_session(uuid,text)'::regprocedure;
