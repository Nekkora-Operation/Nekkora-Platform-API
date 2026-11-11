-- Diagnostico SOMENTE LEITURA para antes da 034 (PLATFORM-AGENT-V2-003, T10). Nao altera nada: roda numa transacao READ ONLY.
-- Rodar como administrador do cluster (atravessa a RLS de todas as organizacoes), por exemplo:
--   psql -X -v ON_ERROR_STOP=1 -d nekkora -f db/diagnosticos/034_organizacoes_por_criadora.sql
--
-- Regra (aprovada em 11/11): nome unico entre as organizacoes CRIADAS PELA MESMA IDENTIDADE pelo cadastro. Autoria, nao
-- propriedade. Autoria so com evidencia inequivoca: exatamente UM vinculo 'cadastro_web' de conta da organizacao E o evento
-- de provisionamento 'cadastro_web' dessa conta. Nunca deduzida de quem e administradora hoje.
-- Secao 1 (IMPEDE a 034): a mesma identidade criou mais de uma organizacao com o mesmo nome normalizado. Decisao humana, caso
--   a caso: nada e excluido, fundido nem renomeado automaticamente.
-- Secao 2 (informativa, NAO impede): organizacoes SEM autoria inequivoca (provisionadas pela operacao ou ambiguas). Ficam
--   fora da restricao de nome; recebem identificador do mesmo jeito. Casos ambiguos podem pedir decisao.
-- Secao 3 (informativa): organizacoes cujo identificador legivel colide na base e recebera sufixo (-2, -3...), na ordem de
--   criacao.
BEGIN TRANSACTION READ ONLY;

\echo '== 1. Mesma identidade criadora, mesmo nome normalizado (impedem a 034) =='
WITH e AS (SELECT iu.identity_id, u.organization_id, u.user_id
      FROM public.identity_users iu JOIN public.users u ON u.user_id = iu.user_id
     WHERE iu.link_channel = 'cadastro_web'
       AND (SELECT count(*) FROM public.identity_users iu2 JOIN public.users u2 ON u2.user_id = iu2.user_id
             WHERE iu2.link_channel = 'cadastro_web' AND u2.organization_id = u.organization_id) = 1
       AND EXISTS (SELECT 1 FROM public.identity_provisioning_events p
                    WHERE p.organization_id = u.organization_id AND p.channel = 'cadastro_web' AND p.provisioned_by_user_id = u.user_id)),
     k AS (SELECT e.identity_id, lower(btrim(regexp_replace(o.name, '[[:space:]' || U&'\00A0\2000-\200A\202F\205F\3000' || ']+', ' ', 'g'))) AS nome_normalizado, o.organization_id, o.name, o.created_at
             FROM e JOIN public.organizations o ON o.organization_id = e.organization_id)
SELECT * FROM k
 WHERE (identity_id, nome_normalizado) IN (SELECT identity_id, nome_normalizado FROM k GROUP BY 1, 2 HAVING count(*) > 1)
 ORDER BY identity_id, nome_normalizado, created_at, organization_id;

\echo '== 2. Organizacoes sem autoria inequivoca (informativo: ficam fora da regra de nome) =='
SELECT o.organization_id, o.name, o.created_at,
       (SELECT count(*) FROM public.identity_users iu JOIN public.users u ON u.user_id = iu.user_id
         WHERE iu.link_channel = 'cadastro_web' AND u.organization_id = o.organization_id) AS vinculos_cadastro,
       (SELECT string_agg(DISTINCT p.channel, ',') FROM public.identity_provisioning_events p WHERE p.organization_id = o.organization_id) AS canais_provisionamento,
       CASE WHEN NOT EXISTS (SELECT 1 FROM public.identity_users iu JOIN public.users u ON u.user_id = iu.user_id
                              WHERE iu.link_channel = 'cadastro_web' AND u.organization_id = o.organization_id)
            THEN 'sem vinculo de cadastro (provisionada pela operacao ou outro canal)'
            ELSE 'ambigua: vinculo de cadastro sem o evento correspondente, ou mais de um' END AS situacao
  FROM public.organizations o
 WHERE o.organization_id NOT IN (SELECT organization_id FROM (SELECT iu.identity_id, u.organization_id, u.user_id
      FROM public.identity_users iu JOIN public.users u ON u.user_id = iu.user_id
     WHERE iu.link_channel = 'cadastro_web'
       AND (SELECT count(*) FROM public.identity_users iu2 JOIN public.users u2 ON u2.user_id = iu2.user_id
             WHERE iu2.link_channel = 'cadastro_web' AND u2.organization_id = u.organization_id) = 1
       AND EXISTS (SELECT 1 FROM public.identity_provisioning_events p
                    WHERE p.organization_id = u.organization_id AND p.channel = 'cadastro_web' AND p.provisioned_by_user_id = u.user_id)) e)
 ORDER BY o.created_at, o.organization_id;

\echo '== 3. Identificadores legiveis que colidem (informativo: sufixo na ordem de criacao) =='
SELECT b.base, string_agg(format('%s (%s)', b.organization_id, b.name), ', ' ORDER BY b.created_at, b.organization_id) AS organizacoes
  FROM (SELECT o.organization_id, o.name, o.created_at,
               coalesce(nullif(btrim(left(btrim(regexp_replace(lower(translate(o.name,
                   U&'\00C1\00C0\00C2\00C3\00C4\00C5\00E1\00E0\00E2\00E3\00E4\00E5\00C9\00C8\00CA\00CB\00E9\00E8\00EA\00EB\00CD\00CC\00CE\00CF\00ED\00EC\00EE\00EF\00D3\00D2\00D4\00D5\00D6\00F3\00F2\00F4\00F5\00F6\00DA\00D9\00DB\00DC\00FA\00F9\00FB\00FC\00C7\00E7\00D1\00F1\00DD\00FD\00FF',
                   'AAAAAAaaaaaaEEEEeeeeIIIIiiiiOOOOOoooooUUUUuuuuCcNnYyy')), '[^a-z0-9]+', '-', 'g'), '-'), 40), '-'), ''), 'organizacao') AS base
          FROM public.organizations o) b
 GROUP BY b.base HAVING count(*) > 1
 ORDER BY b.base;

ROLLBACK;
