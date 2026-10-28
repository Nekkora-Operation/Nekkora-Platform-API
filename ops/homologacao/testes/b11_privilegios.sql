-- B11 (h016.sh): fotografia dos PRIVILEGIOS do banco nekkora, comparada entre a origem e a base restaurada. Uma linha por
-- item, ordenada. Superusuarios ficam fora (os de cada cluster tem nomes diferentes e tudo por definicao), assim como os
-- atributos dos papeis predefinidos pg_*; a concessao de um papel predefinido a papel nosso entra. So catalogo: nenhum dado,
-- nenhuma senha.
--   PAPEL       atributos de cada papel nao superusuario (sem senha)
--   PERTINENCIA papel -> membro (membro nao predefinido) com ADMIN/INHERIT/SET; o CONCEDENTE fica de fora (a restauracao o troca)
--   BANCO       dono e ACL do banco
--   ESQUEMA     dono e ACL de public e ops
--   RELACAO     tipo, dono, ACL, RLS ligada e forcada de cada tabela, visao e sequencia de public e ops
--   COLUNA      ACL por coluna (so as que tem ACL propria)
--   POLITICA    politica de RLS: comando, papeis, permissiva, expressoes
--   FUNCAO      dono, SECURITY DEFINER, configuracao fixa e ACL de cada funcao de public e ops
--   EXTENSAO    dono e esquema de cada extensao
--   PADRAO      privilegios padrao (ALTER DEFAULT PRIVILEGES)
\pset footer off
SELECT linha FROM (
    SELECT format('PAPEL %s login=%s createrole=%s createdb=%s bypassrls=%s replication=%s inherit=%s connlimit=%s',
                  r.rolname, r.rolcanlogin, r.rolcreaterole, r.rolcreatedb, r.rolbypassrls, r.rolreplication, r.rolinherit, r.rolconnlimit) AS linha
      FROM pg_roles r WHERE NOT r.rolsuper AND r.rolname !~ '^pg_'
    UNION ALL
    SELECT format('PERTINENCIA %s -> %s admin=%s inherit=%s set=%s', pg_get_userbyid(m.roleid), pg_get_userbyid(m.member),
                  m.admin_option, m.inherit_option, m.set_option)
      FROM pg_auth_members m JOIN pg_roles a ON a.oid = m.roleid JOIN pg_roles b ON b.oid = m.member
     WHERE NOT a.rolsuper AND NOT b.rolsuper AND b.rolname !~ '^pg_'   -- papel predefinido concedido a papel nosso entra (pg_read_all_data)
    UNION ALL
    SELECT format('BANCO %s dono=%s acl=%s', d.datname, pg_get_userbyid(d.datdba), coalesce(d.datacl::text, '-'))
      FROM pg_database d WHERE d.datname = current_database()
    UNION ALL
    SELECT format('ESQUEMA %s dono=%s acl=%s', n.nspname, pg_get_userbyid(n.nspowner), coalesce(n.nspacl::text, '-'))
      FROM pg_namespace n WHERE n.nspname IN ('public', 'ops')
    UNION ALL
    SELECT format('RELACAO %s.%s tipo=%s dono=%s acl=%s rls=%s forcada=%s', n.nspname, c.relname, c.relkind, pg_get_userbyid(c.relowner),
                  coalesce(c.relacl::text, '-'), c.relrowsecurity, c.relforcerowsecurity)
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname IN ('public', 'ops') AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
    UNION ALL
    SELECT format('COLUNA %s.%s.%s acl=%s', n.nspname, c.relname, a.attname, a.attacl::text)
      FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname IN ('public', 'ops') AND a.attacl IS NOT NULL AND NOT a.attisdropped
    UNION ALL
    SELECT format('POLITICA %s.%s.%s cmd=%s papeis=%s permissiva=%s using=%s check=%s', p.schemaname, p.tablename, p.policyname, p.cmd,
                  p.roles::text, p.permissive, coalesce(p.qual, '-'), coalesce(p.with_check, '-'))
      FROM pg_policies p WHERE p.schemaname IN ('public', 'ops')
    UNION ALL
    SELECT format('FUNCAO %s dono=%s definer=%s config=%s acl=%s', p.oid::regprocedure::text, pg_get_userbyid(p.proowner), p.prosecdef,
                  coalesce(p.proconfig::text, '-'), coalesce(p.proacl::text, '-'))
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname IN ('public', 'ops')
    UNION ALL
    SELECT format('EXTENSAO %s dono=%s esquema=%s', e.extname, pg_get_userbyid(e.extowner), e.extnamespace::regnamespace::text)
      FROM pg_extension e
    UNION ALL
    SELECT format('PADRAO dono=%s esquema=%s tipo=%s acl=%s', pg_get_userbyid(d.defaclrole), coalesce(d.defaclnamespace::regnamespace::text, '-'),
                  d.defaclobjtype, d.defaclacl::text)
      FROM pg_default_acl d
) s ORDER BY linha COLLATE "C";
