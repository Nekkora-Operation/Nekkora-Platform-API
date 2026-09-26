-- x_variante_remove - papel: nekkora (superusuario). Remove a variante de pausa do teste 7(c).
\pset pager off
DROP FUNCTION public.portal_list_executions_pausa(uuid,text,uuid,smallint[],timestamptz,timestamptz,timestamptz,uuid,integer);
SELECT pg_temp.afirma(NOT EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'portal_list_executions_pausa'), 'variante de pausa removida');
