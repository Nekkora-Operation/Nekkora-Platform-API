-- Conferencia SOMENTE LEITURA da unica mudanca de dados da 024, sobre a massa declarada (sql/massa_024_convites.sql). Como
-- admin_cluster. Mesma saida antes e depois da 024; testes/janela_023_024.sh compara as linhas:
--   a_fechar=N                         convites abertos da massa cujo emissor esta sem a autoridade do convite (023)
--   fechados_motivo_4_sem_revogador=N  convites da massa revogados com o motivo 4 e sem revogador (so a 024 grava assim)
--   intactos|...                       os convites que NAO podem mudar (aberto valido, aberto de quem perdeu e recuperou
--                                      a autoridade antes da 024, aceito, revogado): estado e md5 da linha inteira
--   acessos|...                        acessos ja concedidos da organizacao: md5 por tabela (vinculos, papeis de
--                                      organizacao e de workspace, vinculos de cliente, concessoes, contas)
--   perdeu_e_recuperou_antes=...       aberto|fechado: o convite de quem perdeu e recuperou o papel ANTES da 024
SELECT '# ' || CASE WHEN to_regprocedure('public.identity_user_is_linked(uuid)') IS NULL THEN 'sem a 024' ELSE 'com a 024' END;
SELECT 'a_fechar=' || count(*) FROM public.identity_invitations i
 WHERE i.organization_id = 'c0240000-0000-4000-8000-000000000001' AND i.accepted_at IS NULL AND i.revoked_at IS NULL
   AND i.issued_by_membership_id IS NOT NULL
   AND NOT public.access_can_invite(i.organization_id, i.issued_by_membership_id, i.role_id, i.workspace_id);
SELECT 'fechados_motivo_4_sem_revogador=' || count(*) FROM public.identity_invitations
 WHERE organization_id = 'c0240000-0000-4000-8000-000000000001' AND revoke_reason = 4 AND revoked_by_membership_id IS NULL;
SELECT 'intactos|' || email_normalized || '|' || CASE WHEN accepted_at IS NOT NULL THEN 'aceito' WHEN revoked_at IS NOT NULL THEN 'revogado:' || revoke_reason ELSE 'aberto' END
       || '|' || md5(row(i.*)::text)
  FROM public.identity_invitations i
 WHERE invitation_id IN ('c0240000-0000-4000-8000-000000000101', 'c0240000-0000-4000-8000-000000000104',
                         'c0240000-0000-4000-8000-000000000105', 'c0240000-0000-4000-8000-000000000106')
 ORDER BY email_normalized;
SELECT 'acessos|organization_memberships|' || count(*) || '|' || md5(coalesce(string_agg(row(x.*)::text, ',' ORDER BY x.membership_id), ''))
  FROM public.organization_memberships x WHERE x.organization_id = 'c0240000-0000-4000-8000-000000000001';
SELECT 'acessos|organization_role_assignments|' || count(*) || '|' || md5(coalesce(string_agg(row(x.*)::text, ',' ORDER BY x.assignment_id), ''))
  FROM public.organization_role_assignments x WHERE x.organization_id = 'c0240000-0000-4000-8000-000000000001';
SELECT 'acessos|workspace_role_assignments|' || count(*) || '|' || md5(coalesce(string_agg(row(x.*)::text, ',' ORDER BY x.assignment_id), ''))
  FROM public.workspace_role_assignments x WHERE x.organization_id = 'c0240000-0000-4000-8000-000000000001';
SELECT 'acessos|client_memberships|' || count(*) || '|' || md5(coalesce(string_agg(row(x.*)::text, ',' ORDER BY x.client_membership_id), ''))
  FROM public.client_memberships x WHERE x.organization_id = 'c0240000-0000-4000-8000-000000000001';
-- concessoes: as colunas anteriores a 024 (a 024 acrescenta colunas novas, nulas nas linhas antigas)
SELECT 'acessos|client_workspace_grants|' || count(*) || '|' || md5(coalesce(string_agg(row(x.grant_id, x.status, x.approved_by_membership_id,
           x.approved_at, x.starts_at, x.expires_at, x.next_review_at, x.revoked_at)::text, ',' ORDER BY x.grant_id), ''))
  FROM public.client_workspace_grants x WHERE x.organization_id = 'c0240000-0000-4000-8000-000000000001';
SELECT 'acessos|users|' || count(*) || '|' || md5(coalesce(string_agg(row(x.user_id, x.status, x.email)::text, ',' ORDER BY x.user_id), ''))
  FROM public.users x WHERE x.organization_id = 'c0240000-0000-4000-8000-000000000001';
SELECT 'perdeu_e_recuperou_antes=' || CASE WHEN revoked_at IS NULL AND accepted_at IS NULL THEN 'aberto' ELSE 'fechado' END
  FROM public.identity_invitations WHERE invitation_id = 'c0240000-0000-4000-8000-000000000104';
