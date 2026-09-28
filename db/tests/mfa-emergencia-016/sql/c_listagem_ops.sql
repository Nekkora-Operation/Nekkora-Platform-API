-- c_listagem_ops - papel: nekkora (leitura de catalogo). Listagem do schema ops da 016 (o c_listagem_catalogo da
-- portal-013 cobre so public): tabelas, colunas, constraints, indices, triggers, ACL, funcoes (dono, flags, config,
-- md5 do corpo) e ACL de funcao e do schema. Uma linha por fato, sem OIDs, ordenada.
\set ON_ERROR_STOP on
SELECT linha FROM (
  SELECT format('rel|%s|%s|owner=%s|rls=%s', c.relname, c.relkind, pg_get_userbyid(c.relowner), c.relrowsecurity) AS linha
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'ops'
  UNION ALL
  SELECT format('relacl|%s|%s|%s', c.relname, CASE a.grantee WHEN 0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END, a.privilege_type)
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace, aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) a
  WHERE n.nspname = 'ops' AND c.relkind = 'r'
  UNION ALL
  SELECT format('col|%s|%s|%s|notnull=%s|default=%s', c.relname, a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull, pg_get_expr(d.adbin, d.adrelid))
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
  LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
  WHERE n.nspname = 'ops' AND c.relkind = 'r'
  UNION ALL
  SELECT format('con|%s|%s|%s', c.relname, k.conname, pg_get_constraintdef(k.oid))
  FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'ops'
  UNION ALL
  SELECT format('idx|%s', pg_get_indexdef(i.indexrelid))
  FROM pg_index i JOIN pg_class c ON c.oid = i.indrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'ops'
  UNION ALL
  SELECT format('trg|%s|%s|enabled=%s', c.relname, t.tgname, t.tgenabled)
  FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'ops' AND NOT t.tgisinternal
  UNION ALL
  SELECT format('fn|%s|owner=%s|secdef=%s|config=%s|md5=%s', p.oid::regprocedure, pg_get_userbyid(p.proowner), p.prosecdef, p.proconfig, md5(p.prosrc))
  FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'ops'
  UNION ALL
  SELECT format('fnacl|%s|%s|%s', p.oid::regprocedure, CASE a.grantee WHEN 0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END, a.privilege_type)
  FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace, aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
  WHERE n.nspname = 'ops'
  UNION ALL
  SELECT format('nspacl|ops|%s|%s', CASE a.grantee WHEN 0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END, a.privilege_type)
  FROM pg_namespace s, aclexplode(coalesce(s.nspacl, acldefault('n', s.nspowner))) a WHERE s.nspname = 'ops'
) l ORDER BY linha COLLATE "C";
