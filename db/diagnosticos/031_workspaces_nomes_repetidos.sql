-- Diagnostico SOMENTE LEITURA antes da 031 (PLATFORM-V2-001, T03): workspaces com o mesmo nome na mesma organizacao, pela
-- mesma chave da migration (lower(btrim(name))). Nenhuma alteracao. Rodar como administrador do cluster ou migrador:
--   psql -X -v ON_ERROR_STOP=1 -d nekkora -f db/diagnosticos/031_workspaces_nomes_repetidos.sql
-- Sem linhas = a 031 pode ser aplicada. Com linhas: decidir caso a caso (renomear um deles; arquivar nao libera o nome) e
-- registrar a decisao; a 031 nao apaga, nao funde, nao renomeia e nao troca responsaveis.
BEGIN TRANSACTION READ ONLY;
SELECT w.organization_id,
       o.name                       AS organizacao,
       lower(btrim(w.name))         AS chave,
       w.workspace_id,
       w.name                       AS nome_visivel,
       CASE w.status WHEN 1 THEN 'ACTIVE' WHEN 2 THEN 'SUSPENDED' ELSE 'ARCHIVED' END AS situacao,
       w.client_id,
       w.created_at,
       (SELECT count(*) FROM public.automations a WHERE a.workspace_id = w.workspace_id) AS automacoes,
       count(*) OVER (PARTITION BY w.organization_id, lower(btrim(w.name))) AS repeticoes
  FROM public.workspaces w
  JOIN public.organizations o ON o.organization_id = w.organization_id
 WHERE (w.organization_id, lower(btrim(w.name))) IN (
        SELECT organization_id, lower(btrim(name)) FROM public.workspaces GROUP BY 1, 2 HAVING count(*) > 1)
 ORDER BY w.organization_id, chave, w.created_at, w.workspace_id;
ROLLBACK;
