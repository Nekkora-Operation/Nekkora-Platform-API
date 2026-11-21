-- PLATFORM-LICENCIAMENTO-001 - inventario SOMENTE LEITURA do que existe hoje, para decidir a migracao das licencas.
-- Rodar como migrador ou superusuario (as tabelas tem RLS forcada). Nao altera nada: a transacao e READ ONLY e termina
-- em ROLLBACK. Nao imprime e-mail, nome de pessoa nem segredo; so identificadores, nomes de organizacao e contagens.
--
--   psql -v ON_ERROR_STOP=1 -f db/diagnosticos/licenciamento_001_inventario.sql
--
-- O que cada bloco responde esta em docs/platform-licenciamento-001/t00-inventario-e-contrato.md, secao 5.

BEGIN TRANSACTION READ ONLY;

\echo '== 1. Organizacoes: situacao, ativacao, automacoes e uso de execucao/monitoracao =='
WITH auto AS (
    SELECT organization_id,
           count(*)                           AS automacoes,
           count(*) FILTER (WHERE status = 1) AS ativas,
           count(*) FILTER (WHERE status = 2) AS suspensas,
           count(*) FILTER (WHERE status = 3) AS arquivadas
      FROM public.automations GROUP BY organization_id
), remota AS (
    SELECT organization_id, count(DISTINCT automation_id) AS automacoes_com_execucao_remota, max(requested_at) AS ultima
      FROM public.remote_executions GROUP BY organization_id
), relatada AS (
    SELECT organization_id, count(DISTINCT automation_id) AS automacoes_com_execucao_relatada
      FROM public.agent_runs GROUP BY organization_id
), vigente AS (
    SELECT organization_id,
           count(DISTINCT automation_id)                               AS automacoes_com_autorizacao_vigente,
           count(DISTINCT automation_id) FILTER (WHERE source_kind = 3) AS automacoes_com_monitoramento_vigente
      FROM public.local_run_authorizations
     WHERE revoked_at IS NULL AND valid_until > clock_timestamp() GROUP BY organization_id
)
SELECT o.organization_id, o.name, o.status AS situacao_1_ativa_2_suspensa_3_desativada,
       s.state AS ativacao_1_aguardando_2_ativada,
       coalesce(a.automacoes, 0) AS automacoes, coalesce(a.ativas, 0) AS ativas, coalesce(a.suspensas, 0) AS suspensas,
       coalesce(a.arquivadas, 0) AS arquivadas,
       coalesce(v.automacoes_com_autorizacao_vigente, 0) AS com_autorizacao_vigente,
       coalesce(v.automacoes_com_monitoramento_vigente, 0) AS com_monitoramento_vigente,
       coalesce(r.automacoes_com_execucao_remota, 0) AS com_execucao_remota,
       coalesce(l.automacoes_com_execucao_relatada, 0) AS com_execucao_relatada,
       r.ultima AS ultima_execucao_remota
  FROM public.organizations o
  LEFT JOIN ops_activation.organization_states s USING (organization_id)
  LEFT JOIN auto a USING (organization_id)
  LEFT JOIN remota r USING (organization_id)
  LEFT JOIN relatada l USING (organization_id)
  LEFT JOIN vigente v USING (organization_id)
 ORDER BY o.created_at, o.organization_id;

\echo '== 2. Automacoes candidatas a licenca: as que tem uso de execucao remota ou monitoracao hoje =='
SELECT a.organization_id, a.automation_id, a.name, a.status AS situacao_1_ativa_2_suspensa_3_arquivada,
       EXISTS (SELECT 1 FROM public.local_run_authorizations z
                WHERE z.organization_id = a.organization_id AND z.automation_id = a.automation_id
                  AND z.revoked_at IS NULL AND z.valid_until > clock_timestamp()) AS autorizacao_vigente,
       (SELECT count(*) FROM public.remote_executions x
         WHERE x.organization_id = a.organization_id AND x.automation_id = a.automation_id) AS execucoes_remotas,
       (SELECT count(*) FROM public.agent_runs g
         WHERE g.organization_id = a.organization_id AND g.automation_id = a.automation_id) AS execucoes_relatadas
  FROM public.automations a
 WHERE EXISTS (SELECT 1 FROM public.local_run_authorizations z
                WHERE z.organization_id = a.organization_id AND z.automation_id = a.automation_id)
    OR EXISTS (SELECT 1 FROM public.remote_executions x
                WHERE x.organization_id = a.organization_id AND x.automation_id = a.automation_id)
    OR EXISTS (SELECT 1 FROM public.agent_runs g
                WHERE g.organization_id = a.organization_id AND g.automation_id = a.automation_id)
 ORDER BY a.organization_id, a.name;

\echo '== 3. Limites vigentes por organizacao (1 Workers, 2 execucoes simultaneas, 3 execucoes pendentes) =='
SELECT DISTINCT ON (organization_id, resource_code)
       organization_id, resource_code, limit_mode AS modo_1_finito_2_ilimitado, limit_value, revision_number
  FROM public.organization_resource_limit_revisions
 WHERE resource_code IN (1, 2, 3)
 ORDER BY organization_id, resource_code, revision_number DESC;

\echo '== 4. Organizacoes vazias (sem automacao) por identidade criadora; acima de 3 = acima do limite proposto =='
SELECT w.creator_identity_id,
       count(*) AS organizacoes_criadas,
       count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM public.automations a WHERE a.organization_id = w.organization_id)) AS vazias
  FROM public.organization_authorship w
 GROUP BY w.creator_identity_id
 ORDER BY vazias DESC, organizacoes_criadas DESC;

\echo '== 5. Organizacoes sem autoria registrada (provisionadas pela operacao ou anteriores a 034) =='
SELECT o.organization_id, o.name, o.created_at,
       NOT EXISTS (SELECT 1 FROM public.automations a WHERE a.organization_id = o.organization_id) AS vazia
  FROM public.organizations o
 WHERE NOT EXISTS (SELECT 1 FROM public.organization_authorship w WHERE w.organization_id = o.organization_id)
 ORDER BY o.created_at;

\echo '== 6. Contas do tipo Cliente (account_type 2) por organizacao =='
SELECT organization_id, count(*) AS contas_cliente FROM public.users WHERE account_type = 2 GROUP BY organization_id ORDER BY 2 DESC;

\echo '== 7. Trabalho em andamento agora (o que uma mudanca de regra encontraria no meio do caminho) =='
SELECT 'execucoes remotas com a ultima tentativa nao encerrada' AS o_que, count(*)
  FROM public.remote_executions x
 WHERE NOT EXISTS (SELECT 1 FROM public.remote_attempts t WHERE t.organization_id = x.organization_id
                      AND t.remote_execution_id = x.remote_execution_id AND t.state >= 7)
UNION ALL
SELECT 'execucoes relatadas pelo Agent ainda em andamento', count(*) FROM public.agent_runs WHERE supervisor_state IN (1, 2, 3)
UNION ALL
SELECT 'pedidos ao Agent anterior em aberto e no prazo', count(*) FROM public.run_requests
 WHERE state IN (1, 2) AND valid_until > clock_timestamp();

ROLLBACK;
