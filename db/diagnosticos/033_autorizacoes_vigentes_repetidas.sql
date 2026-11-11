-- Diagnostico SOMENTE LEITURA para antes da 033 (PLATFORM-AGENT-V2-003, T12). Nao altera nada: roda numa transacao READ ONLY.
-- Rodar como administrador do cluster (atravessa a RLS de todas as organizacoes), por exemplo:
--   psql -X -v ON_ERROR_STOP=1 -d nekkora -f db/diagnosticos/033_autorizacoes_vigentes_repetidas.sql
--
-- VIGENTE = nao revogada e valid_until depois do horario do servidor (o mesmo criterio do gateway).
-- ESCOPO = organizacao + autorizacao do Worker no workspace + versao (pacote, source_kind 1/2) ou automacao (monitoramento, 3).
-- Secao 1 (IMPEDE a 033): escopos com mais de uma autorizacao vigente. Decisao humana, caso a caso: nada e revogado
-- automaticamente. Procedimento: na Web, em "Execucao local" (versao) ou "Monitoramento" (automacao), "Revogar" as que nao
-- devem ficar; o historico e preservado (revogar nao apaga).
-- Secao 2 (informativa, NAO impede): quantas vigentes ha por organizacao e origem.
BEGIN TRANSACTION READ ONLY;

\echo '== 1. Escopos com mais de uma autorizacao local vigente (impedem a 033) =='
SELECT z.organization_id, z.workspace_id, z.worker_id, z.worker_workspace_assignment_id,
       CASE WHEN z.source_kind = 3 THEN 'monitoramento' ELSE 'pacote' END AS origem,
       z.automation_id, z.automation_version_id, z.authorization_id, z.granted_at, z.valid_until, z.granted_by_membership_id
  FROM public.local_run_authorizations z
 WHERE z.revoked_at IS NULL AND z.valid_until > clock_timestamp()
   AND (z.organization_id, z.worker_workspace_assignment_id, z.automation_id, (z.source_kind = 3),
        coalesce(z.automation_version_id, '00000000-0000-0000-0000-000000000000'::uuid)) IN (
        SELECT organization_id, worker_workspace_assignment_id, automation_id, (source_kind = 3),
               coalesce(automation_version_id, '00000000-0000-0000-0000-000000000000'::uuid)
          FROM public.local_run_authorizations
         WHERE revoked_at IS NULL AND valid_until > clock_timestamp()
         GROUP BY 1, 2, 3, 4, 5
        HAVING count(*) > 1)
 ORDER BY z.organization_id, z.worker_workspace_assignment_id, z.automation_id, z.automation_version_id, z.granted_at, z.authorization_id;

\echo '== 2. Autorizacoes vigentes por organizacao e origem (informativo) =='
SELECT organization_id, CASE WHEN source_kind = 3 THEN 'monitoramento' ELSE 'pacote' END AS origem, count(*) AS vigentes
  FROM public.local_run_authorizations
 WHERE revoked_at IS NULL AND valid_until > clock_timestamp()
 GROUP BY 1, 2
 ORDER BY 1, 2;

ROLLBACK;
