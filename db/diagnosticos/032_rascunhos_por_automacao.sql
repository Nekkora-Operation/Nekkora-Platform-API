-- Diagnostico SOMENTE LEITURA para antes da 032 (PLATFORM-AGENT-V2-002, T09). Nao altera nada: roda numa transacao READ ONLY.
-- Rodar como administrador do cluster (atravessa a RLS de todas as organizacoes), por exemplo:
--   psql -X -v ON_ERROR_STOP=1 -d nekkora -f db/diagnosticos/032_rascunhos_por_automacao.sql
--
-- Secao 1 (IMPEDE a 032): automacoes com mais de um rascunho. Decisao humana, caso a caso: nada e apagado, publicado nem
-- renumerado automaticamente.
-- Secao 2 (informativa, NAO impede): rascunhos legados sem pacote (criados pela rota antiga). Sendo o unico rascunho da
-- automacao, viram o rascunho dela e aparecem na Web como "Continuar rascunho".
BEGIN TRANSACTION READ ONLY;

\echo '== 1. Automacoes com mais de um rascunho (impedem a 032) =='
SELECT v.organization_id, v.automation_id, a.name AS automacao, v.automation_version_id, v.version_number,
       CASE WHEN v.command_json ? 'artifact' THEN v.command_json->'artifact'->>'sha256' END AS pacote_sha256, v.created_at
  FROM public.automation_versions v
  JOIN public.automations a ON a.organization_id = v.organization_id AND a.automation_id = v.automation_id
 WHERE v.status = 1
   AND (v.organization_id, v.automation_id) IN (SELECT organization_id, automation_id FROM public.automation_versions
                                                 WHERE status = 1 GROUP BY organization_id, automation_id HAVING count(*) > 1)
 ORDER BY v.organization_id, v.automation_id, v.version_number;

\echo '== 2. Rascunhos sem pacote (informativo) =='
SELECT v.organization_id, v.automation_id, a.name AS automacao, v.automation_version_id, v.version_number, v.created_at,
       (SELECT count(*) FROM public.automation_versions o WHERE o.organization_id = v.organization_id AND o.automation_id = v.automation_id
                                                           AND o.status = 1) AS rascunhos_da_automacao
  FROM public.automation_versions v
  JOIN public.automations a ON a.organization_id = v.organization_id AND a.automation_id = v.automation_id
 WHERE v.status = 1 AND NOT (v.command_json ? 'artifact')
 ORDER BY v.organization_id, v.automation_id, v.version_number;

ROLLBACK;
