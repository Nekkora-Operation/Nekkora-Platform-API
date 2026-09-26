-- f_impressao_portal - papel: nekkora (so leitura). Uma linha: impressao digital dos objetos do portal
-- (assinatura, owner, SECURITY DEFINER, volatilidade, search_path, ACL e md5 do corpo das funcoes
-- portal_*, exceto a variante de pausa do 7(c), cuja remocao e conferida no catalogo final) e das
-- configuracoes do papel. Usada pelo runner antes de cada mutacao e depois da
-- restauracao: precisa ser identica. Uso: psql -At (sem o prefixo de assercoes).
SELECT md5(
  (SELECT string_agg(format('%s|%s|%s|%s|%s|%s|%s', p.oid::regprocedure, pg_get_userbyid(p.proowner), p.prosecdef, p.provolatile,
                            p.proconfig, p.proacl, md5(p.prosrc)), ';' ORDER BY p.oid::regprocedure::text)
   FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname LIKE 'portal\_%'
     AND p.proname <> 'portal_list_executions_pausa')
  || '#' ||
  (SELECT string_agg(c, ',' ORDER BY c) FROM pg_db_role_setting s, unnest(s.setconfig) c WHERE s.setrole = 'nekkora_portal'::regrole)
) AS impressao;
