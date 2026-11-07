-- c_listagem_catalogo - papel: nekkora (somente leitura de catalogo). Rodado em DOIS bancos do mesmo
-- cluster isolado: nekkora (001-013, pelo entrypoint) e nekkora_ref (001-012, aplicadas pelo runner).
-- Uma linha por fato, sem OIDs, ordenada; o runner faz o diff. Cobre o que uma migration pode mudar
-- no banco: tabelas, colunas, constraints, indices, triggers, RLS, politicas, ACL de tabela e coluna,
-- funcoes (owner, flags, config, ACL, md5 do corpo), tipos, schema, extensoes, privilegios padrao e
-- ACL do banco. Papeis e configuracoes de papel sao do cluster e sao conferidos em t00.
-- Uso: psql -At -v ON_ERROR_STOP=1 (sem o prefixo de assercoes).
\set ON_ERROR_STOP on
SELECT linha FROM (
  SELECT format('rel|%s|%s|owner=%s|rls=%s|force=%s', c.relname, c.relkind, pg_get_userbyid(c.relowner), c.relrowsecurity, c.relforcerowsecurity) AS linha
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
  UNION ALL
  SELECT format('relacl|%s|%s|%s|grant=%s', c.relname, CASE a.grantee WHEN 0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END, a.privilege_type, a.is_grantable)
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace,
       aclexplode(coalesce(c.relacl, acldefault(CASE WHEN c.relkind = 'S' THEN 's'::"char" ELSE 'r'::"char" END, c.relowner))) a
  WHERE n.nspname = 'public'
  UNION ALL
  SELECT format('col|%s|%s|%s|notnull=%s|default=%s|gen=%s', c.relname, a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull,
                pg_get_expr(d.adbin, d.adrelid), a.attgenerated)
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
  LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
  WHERE n.nspname = 'public' AND c.relkind IN ('r', 'v', 'm', 'p', 'f')
  UNION ALL
  SELECT format('colacl|%s|%s|%s|%s|grant=%s', c.relname, a.attname, CASE x.grantee WHEN 0 THEN 'PUBLIC' ELSE pg_get_userbyid(x.grantee) END, x.privilege_type, x.is_grantable)
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped, aclexplode(a.attacl) x
  WHERE n.nspname = 'public'
  UNION ALL
  SELECT format('con|%s|%s|%s', c.relname, k.conname, pg_get_constraintdef(k.oid))
  FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
  UNION ALL
  SELECT format('idx|%s', pg_get_indexdef(i.indexrelid))
  FROM pg_index i JOIN pg_class c ON c.oid = i.indrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
  UNION ALL
  SELECT format('trg|%s|%s|enabled=%s|%s', c.relname, t.tgname, t.tgenabled, pg_get_triggerdef(t.oid))
  FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public' AND NOT t.tgisinternal
  UNION ALL
  SELECT format('pol|%s|%s|%s|%s|%s|using=%s|check=%s', tablename, policyname, cmd, permissive, roles, qual, with_check)
  FROM pg_policies WHERE schemaname = 'public'
  UNION ALL
  SELECT format('fn|%s|owner=%s|secdef=%s|vol=%s|strict=%s|lang=%s|config=%s|result=%s|md5=%s', p.oid::regprocedure, pg_get_userbyid(p.proowner),
                p.prosecdef, p.provolatile, p.proisstrict, l.lanname, p.proconfig, pg_get_function_result(p.oid), md5(p.prosrc))
  FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace JOIN pg_language l ON l.oid = p.prolang WHERE n.nspname = 'public'
  UNION ALL
  SELECT format('fnacl|%s|%s|%s|grant=%s', p.oid::regprocedure, CASE a.grantee WHEN 0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END, a.privilege_type, a.is_grantable)
  FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace, aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
  WHERE n.nspname = 'public'
  UNION ALL
  SELECT format('type|%s|%s|owner=%s', t.typname, t.typtype, pg_get_userbyid(t.typowner))
  FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
  WHERE n.nspname = 'public' AND t.typtype IN ('c', 'e', 'd') AND NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = t.typrelid AND c.relkind <> 'c')
  UNION ALL
  SELECT format('nsp|%s|owner=%s', nspname, pg_get_userbyid(nspowner)) FROM pg_namespace WHERE nspname NOT LIKE 'pg\_%' AND nspname <> 'information_schema'
  UNION ALL
  SELECT format('nspacl|%s|%s|%s|grant=%s', s.nspname, CASE a.grantee WHEN 0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END, a.privilege_type, a.is_grantable)
  FROM pg_namespace s, aclexplode(coalesce(s.nspacl, acldefault('n', s.nspowner))) a WHERE s.nspname = 'public'
  UNION ALL
  SELECT format('ext|%s|%s|schema=%s', e.extname, e.extversion, e.extnamespace::regnamespace) FROM pg_extension e
  UNION ALL
  SELECT format('defacl|%s|%s|%s|%s|%s', pg_get_userbyid(d.defaclrole), coalesce(d.defaclnamespace::regnamespace::text, '-'), d.defaclobjtype,
                CASE a.grantee WHEN 0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END, a.privilege_type)
  FROM pg_default_acl d, aclexplode(d.defaclacl) a
  UNION ALL
  SELECT format('dbacl|%s|%s|grant=%s', CASE a.grantee WHEN 0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END, a.privilege_type, a.is_grantable)
  FROM pg_database d, aclexplode(coalesce(d.datacl, acldefault('d', d.datdba))) a WHERE d.datname = current_database()
) l ORDER BY linha COLLATE "C";
